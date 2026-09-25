#!/usr/bin/env python3
"""운영 DB를 백업하고 외부 접속이 없는 별도 PostgreSQL에서 읽기·정합성을 확인한다.

--basebackup을 주면 새로 덤프하지 않고 postgresql/backup/db-backup.py가 만든 tar·매니페스트
백업을 같은 방식의 격리 클러스터에 복원해 확인한다. 이때는 운영 DB에 접속하지 않는다.
"""
import argparse
import csv
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess
import tarfile
import time


COUNTS = """SELECT json_build_object(
'commands',(SELECT count(*) FROM command_journal),
'events',(SELECT count(*) FROM event_journal),
'trades',(SELECT count(*) FROM trades),
'ledger_entries',(SELECT count(*) FROM ledger_entries));"""



# summary.sql 판정 어휘(하네스 검증설계 §4-1 '판정 어휘', ADR 0034 S3). 이 파일을 쓰는 복원·복구 점검 여섯 곳이 같은 뜻으로
# 둔다. N/A 는 그 판정이 읽는 마이그레이션 객체가 모두 없는 행이라 통과로도 실패로도 세지 않고 따로 적는다. N/A 를 낼 수 있는
# 코드는 C29·C30(V48 객체)과 C31(V50 객체)뿐이다(C30 은 ADR 0034 S7). 그 밖의 N/A 는 형식 오류다. 미평가 SKIP 은 종전대로 실패다.
VERDICTS = ("PASS", "FAIL", "SKIP", "N/A")
NOT_APPLICABLE_CODES = ("C29", "C30", "C31")


def judge_invariant_rows(rows):
    """summary.sql 판정 행을 가른다. 형식 오류는 ValueError 다.

    failed 는 CRITICAL·HIGH 행 가운데 FAIL·SKIP 인 코드, not_applicable 은 N/A 코드다. N/A 는 통과 목록에 넣지 않는다.
    """
    if len(rows) < 28:
        raise ValueError("invariant catalogue is incomplete: %d rows" % len(rows))
    for row in rows:
        verdict = row.get("verdict")
        if verdict not in VERDICTS:
            raise ValueError("unknown invariant verdict: %r" % (verdict,))
        if verdict == "N/A" and row.get("code") not in NOT_APPLICABLE_CODES:
            raise ValueError("N/A verdict outside C29/C30/C31: %r" % (row.get("code"),))
    failed = [row["code"] for row in rows
              if row.get("severity") in ("CRITICAL", "HIGH") and row["verdict"] in ("FAIL", "SKIP")]
    not_applicable = [row["code"] for row in rows if row["verdict"] == "N/A"]
    return {"failed": failed, "not_applicable": not_applicable}

def run(args, **kw):
    return subprocess.run(args, check=True, text=True, capture_output=True,
                          timeout=600, **kw).stdout.strip()


def check_invariants(target, invariants, root, result):
    output = run(target + ['--csv', '-f', str(invariants.resolve(strict=True))])
    (root / 'invariants.csv').write_text(output + '\n')
    rows = list(csv.DictReader(io.StringIO(output)))
    try:
        verdicts = judge_invariant_rows(rows)
    except ValueError as error:
        raise RuntimeError('정합성 검사 결과가 불완전합니다: ' + str(error)) from None
    failed = verdicts['failed']
    result.update(invariant_count=len(rows), failed_invariants=failed,
                  not_applicable=verdicts['not_applicable'])
    if failed:
        raise RuntimeError('복원 데이터의 필수 정합성 검사가 실패했습니다.')


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def verify_checksums(source):
    expected = {}
    for line in (source / 'SHA256SUMS').read_text().splitlines():
        digest, _, name = line.partition('  ')
        if not re.fullmatch(r'[0-9a-f]{64}', digest) or not re.fullmatch(r'[A-Za-z0-9_-][A-Za-z0-9._-]*', name):
            raise RuntimeError('SHA256SUMS 형식이 올바르지 않습니다.')
        expected[name] = digest
    present = {path.name for path in source.iterdir()} - {'SHA256SUMS', 'offsite.json'}
    if present != set(expected):
        raise RuntimeError('SHA256SUMS 목록과 백업 디렉터리의 파일이 다릅니다.')
    for name, digest in sorted(expected.items()):
        if sha256_file(source / name) != digest:
            raise RuntimeError(name + '의 SHA-256이 기록과 다릅니다.')
    return len(expected)


def extract(archive, target):
    # 서버에는 이 파일만 복사해 실행하므로 db-backup.py의 같은 함수를 가져오지 않는다.
    target.mkdir(mode=0o700, exist_ok=True)
    with tarfile.open(archive, 'r:*') as tar:
        for member in tar:
            if member.name.startswith('/') or '..' in Path(member.name).parts or not (member.isfile() or member.isdir()):
                raise RuntimeError(archive.name + '에 허용하지 않는 항목이 있습니다: ' + member.name)
            tar.extract(member, target, set_attrs=False)


def isolate(data, sock):
    # 복원본의 설정은 운영 사설 주소 listen, TLS 키, include 경로를 가리킨다. 시험 클러스터는 소켓 하나만 연다.
    (data / 'postgresql.conf').write_text("listen_addresses = ''\nport = 55432\n"
                                          f"unix_socket_directories = '{sock}'\n"
                                          "shared_buffers = '32MB'\nmax_connections = 10\n"
                                          "autovacuum = off\narchive_mode = off\nssl = off\n")
    (data / 'postgresql.auto.conf').write_text('')
    (data / 'pg_hba.conf').write_text('local all all peer\n')
    for name in ('standby.signal', 'recovery.signal'):
        if (data / name).exists():
            (data / name).unlink()


def check_basebackup(args, base):
    source = args.basebackup
    if source.is_symlink() or not source.is_dir():
        raise RuntimeError('베이스백업 디렉터리를 지정해야 합니다.')
    source = source.resolve()
    metadata = json.loads((source / 'metadata.json').read_text())
    backup_id = metadata.get('backup_id', '')
    if not re.fullmatch(r'\d{8}T\d{6}Z-[0-9a-f]{6}', backup_id):
        raise RuntimeError('metadata.json의 백업 ID 형식이 올바르지 않습니다.')
    manifest = json.loads((source / 'backup_manifest').read_text())
    data_bytes = sum(entry['Size'] for entry in manifest['Files'])
    disk = shutil.disk_usage(base)
    if disk.free < max(2 * data_bytes + 2 * 1024**3, disk.total // 5):
        raise RuntimeError('시험 복원에 필요한 디스크 여유가 부족합니다.')
    root = base / (backup_id + '-restore-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    root.mkdir(mode=0o700)
    data, sock = root / 'restore-data', root / 'socket'
    sock.mkdir(mode=0o700)
    offsite = source / 'offsite.json'
    result = {'mode': 'basebackup', 'backup_id': backup_id, 'status': 'RUNNING', 'source_dir': str(source),
              'external_copy': offsite.is_file() and json.loads(offsite.read_text()).get('status') == 'VERIFIED',
              'tcp_listen': False, 'roles_acl_checked': False, 'application_boot_checked': False}
    started = time.monotonic()
    running = False
    try:
        result['sha256_files'] = verify_checksums(source)
        suffix = '.tar.gz' if (source / 'base.tar.gz').is_file() else '.tar'
        extract(source / ('base' + suffix), data)
        extract(source / ('pg_wal' + suffix), data / 'pg_wal')
        run(['pg_verifybackup', '--manifest-path', str(source / 'backup_manifest'), str(data)])
        result['pg_verifybackup'] = 'PASS'
        isolate(data, sock)
        run(['pg_ctl', '-D', str(data), '-l', str(root / 'restore-server.log'), '-w', '-t', '540',
             '-o', f"-c listen_addresses='' -c port=55432 -c unix_socket_directories='{sock}'", 'start'])
        running = True
        target = ['psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-h', str(sock), '-p', '55432', '-d', 'exchange']
        restored = json.loads(run(target + ['-At', '-c', COUNTS]))
        result['restored_counts'] = restored
        if not all(value > 0 for value in restored.values()):
            raise RuntimeError('거래 데이터가 없는 복원은 복구 증명이 되지 않습니다.')
        # 네 테이블은 append-only라 복원 시점이 백업 시작 전과 종료 뒤 건수 사이에 있어야 한다.
        before, after = metadata['counts_before'], metadata['counts_after']
        outside = [name for name, value in sorted(restored.items()) if not before[name] <= value <= after[name]]
        result['counts_within_backup_window'] = not outside
        if outside:
            raise RuntimeError('복원 건수가 백업 시작 전·종료 뒤 건수 범위 밖입니다: ' + ', '.join(outside))
        check_invariants(target, args.invariants, root, result)
        result['status'] = 'PASS'
    except Exception as error:
        result.update(status='FAIL', error_type=type(error).__name__, error=str(error)[:800])
        raise
    finally:
        if running:
            run(['pg_ctl', '-D', str(data), '-m', 'fast', '-w', 'stop'])
        result['test_cluster_stopped'] = not (data / 'postmaster.pid').exists()
        if result['status'] == 'PASS' and not args.keep_restore_data:
            shutil.rmtree(data)
            result['restore_data_removed'] = True
        result['duration_seconds'] = round(time.monotonic() - started, 3)
        result['completed_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
        (root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--invariants', required=True, type=Path)
    parser.add_argument('--basebackup', type=Path,
                        help='db-backup.py가 만든 백업 디렉터리. 주면 새 덤프 대신 이 백업을 복원한다.')
    parser.add_argument('--keep-restore-data', action='store_true',
                        help='--basebackup 검사가 통과해도 풀어 놓은 시험 클러스터를 지우지 않는다.')
    args = parser.parse_args()
    if os.geteuid() != pwd.getpwnam('postgres').pw_uid:
        raise RuntimeError('postgres OS 계정으로 실행해야 합니다.')
    os.umask(0o077)
    base = Path('/var/lib/pgsql/backup-checks')
    base.mkdir(mode=0o700, exist_ok=True)
    if base.is_symlink():
        raise RuntimeError('백업 경로의 심볼릭 링크는 허용하지 않습니다.')
    if args.basebackup:
        check_basebackup(args, base)
        return
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
        check_invariants(target, args.invariants, root, result)
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
