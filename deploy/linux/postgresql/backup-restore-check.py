#!/usr/bin/env python3
"""운영 DB를 백업하고 외부 접속이 없는 별도 PostgreSQL에서 읽기·정합성을 확인한다."""
import argparse
import csv
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import time


COUNTS = """SELECT json_build_object(
'commands',(SELECT count(*) FROM command_journal),
'events',(SELECT count(*) FROM event_journal),
'trades',(SELECT count(*) FROM trades),
'ledger_entries',(SELECT count(*) FROM ledger_entries));"""


def run(args, **kw):
    return subprocess.run(args, check=True, text=True, capture_output=True,
                          timeout=600, **kw).stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--invariants', required=True, type=Path)
    args = parser.parse_args()
    if os.geteuid() != pwd.getpwnam('postgres').pw_uid:
        raise RuntimeError('postgres OS 계정으로 실행해야 합니다.')
    os.umask(0o077)
    base = Path('/var/lib/pgsql/backup-checks')
    base.mkdir(mode=0o700, exist_ok=True)
    if base.is_symlink():
        raise RuntimeError('백업 경로의 심볼릭 링크는 허용하지 않습니다.')
    psql = ['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-d', 'exchange']
    if run(psql + ['-c', 'SELECT pg_is_in_recovery()']) != 'f':
        raise RuntimeError('현재 Primary에서만 실행합니다.')
    size = int(run(psql + ['-c', "SELECT pg_database_size('exchange')"]))
    disk = shutil.disk_usage(base)
    if disk.free < max(4 * size + 2 * 1024**3, disk.total // 5):
        raise RuntimeError('백업·시험 복원에 필요한 디스크 여유가 부족합니다.')
    backup_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    root = base / backup_id
    root.mkdir(mode=0o700)
    archive, data, sock = root / 'exchange.dump', root / 'restore-data', root / 'socket'
    sock.mkdir(mode=0o700)
    started = time.monotonic()
    result = {'backup_id': backup_id, 'status': 'RUNNING', 'source_bytes': size,
              'source_data_preserved': True, 'external_copy': False,
              'roles_acl_checked': False, 'application_boot_checked': False}
    holder = None
    running = False
    try:
        # dump와 원본 건수를 같은 스냅샷에서 얻는다. 봇 주문은 계속 처리한다.
        holder = subprocess.Popen(psql, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=(root / 'snapshot-errors.log').open('w'), text=True)
        holder.stdin.write("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
                           "SET LOCAL idle_in_transaction_session_timeout='10min';\n"
                           "SELECT pg_export_snapshot();\n" + COUNTS + '\n')
        holder.stdin.flush()
        snapshot = holder.stdout.readline().strip()
        source_counts = json.loads(holder.stdout.readline())
        if not snapshot or not all(value > 0 for value in source_counts.values()):
            raise RuntimeError('거래 데이터가 있는 스냅샷을 확보하지 못했습니다.')
        run(['pg_dump', '-Fc', '--snapshot=' + snapshot, '--lock-wait-timeout=10s',
             '-d', 'exchange', '-f', str(archive)])
        holder.stdin.write('COMMIT;\n\\q\n')
        holder.stdin.flush()
        if holder.wait(timeout=15) != 0:
            raise RuntimeError('백업 스냅샷 세션 오류')
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        result.update(archive=str(archive), archive_bytes=archive.stat().st_size,
                      sha256=digest, source_counts=source_counts)
        run(['initdb', '-D', str(data), '--auth-local=peer', '--auth-host=reject',
             '--encoding=UTF8', '--locale=C.UTF-8'])
        with (data / 'postgresql.conf').open('a') as config:
            config.write("\nlisten_addresses=''\nport=55432\nshared_buffers='32MB'\n"
                         "max_connections=10\nautovacuum=off\n"
                         f"unix_socket_directories='{sock}'\n")
        run(['pg_ctl', '-D', str(data), '-l', str(root / 'restore-server.log'), '-w', 'start'])
        running = True
        run(['createdb', '-h', str(sock), '-p', '55432', 'exchange'])
        run(['pg_restore', '--exit-on-error', '--no-owner', '--no-acl', '-h', str(sock),
             '-p', '55432', '-d', 'exchange', str(archive)])
        target = ['psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-h', str(sock),
                  '-p', '55432', '-d', 'exchange']
        restored = json.loads(run(target + ['-At', '-c', COUNTS]))
        result['restored_counts'] = restored
        if restored != source_counts:
            raise RuntimeError('동일 스냅샷의 원본·복원 데이터 건수가 다릅니다.')
        output = run(target + ['--csv', '-f', str(args.invariants.resolve(strict=True))])
        (root / 'invariants.csv').write_text(output + '\n')
        rows = list(csv.DictReader(io.StringIO(output)))
        if len(rows) < 28 or any(row.get('verdict') not in ('PASS', 'FAIL', 'SKIP') for row in rows):
            raise RuntimeError('정합성 검사 결과가 불완전합니다.')
        failed = [r['code'] for r in rows if r['severity'] in ('CRITICAL', 'HIGH') and r['verdict'] != 'PASS']
        result.update(invariant_count=len(rows), failed_invariants=failed)
        if failed:
            raise RuntimeError('복원 데이터의 필수 정합성 검사가 실패했습니다.')
        result['status'] = 'PASS'
    except Exception as error:
        result.update(status='FAIL', error_type=type(error).__name__)
        raise
    finally:
        if holder and holder.poll() is None:
            holder.terminate()
            holder.wait(timeout=15)
        if running:
            run(['pg_ctl', '-D', str(data), '-m', 'fast', '-w', 'stop'])
        result['test_cluster_stopped'] = not (data / 'postmaster.pid').exists()
        result['duration_seconds'] = round(time.monotonic() - started, 3)
        result['completed_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
        (root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
