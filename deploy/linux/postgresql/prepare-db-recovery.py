"""학교 DB 두 대의 수동 복구 준비·재편입. 승격과 앱 전환은 별도로 확인한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import time

BUCKET = 's3://2026-ai-kopo-04-exchange-artifacts-619256964117/transfer/db-recovery-20260920/'
OLD = '172.31.1.229'
NEW = '172.31.33.195'
WORK = Path('/etc/postgresql/recovery-20260920')


def run(args, **kw):
    try:
        return subprocess.run(args, check=True, capture_output=True, **kw).stdout
    except subprocess.CalledProcessError as error:
        # 인수와 오류 출력에 연결 정보가 섞일 수 있어 실패 단계만 남긴다.
        if run.step == 'basebackup':
            stderr = error.stderr.decode(errors='replace') if isinstance(error.stderr, bytes) else error.stderr
            # pg_basebackup은 비밀번호를 인수로 받지 않는다. 저장 전에도 전달 값을 제거한다.
            password = kw.get('env', {}).get('PGPASSWORD')
            if password:
                stderr = stderr.replace(password, '[redacted]')
            print((stderr or '')[:1000])
        raise RuntimeError('명령 실패: ' + args[0] + ' / 단계 ' + str(run.step) +
                           ' / 반환값 ' + str(error.returncode)) from None


run.step = 'start'


def sql(query):
    return run(['runuser', '-u', 'postgres', '--', 'psql', '-X', '-qAt',
                '-v', 'ON_ERROR_STOP=1', '-d', 'exchange', '-c', query], text=True).strip()


def copy_s3(source, destination):
    run(['aws', 's3', 'cp', str(source), str(destination), '--region', 'ap-northeast-2', '--only-show-errors'])


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('prepare-old', 'prepare-new', 'rejoin-old'))
    parser.add_argument('--recipient-sha256')
    parser.add_argument('--server-sha256')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise RuntimeError('root 실행 필요')
    expected = NEW if args.mode == 'prepare-new' else OLD
    if expected not in run(['hostname', '-I'], text=True).split():
        raise RuntimeError('대상 호스트 불일치')
    os.umask(0o077)
    WORK.mkdir(mode=0o700, exist_ok=True)
    private, recipient = WORK / 'recipient.key', WORK / 'recipient.crt'
    if args.mode == 'prepare-old':
        if sql('SELECT pg_is_in_recovery()') != 'f':
            raise RuntimeError('기존 Primary가 아님')
        if private.exists() or recipient.exists():
            raise RuntimeError('전달 키가 이미 존재함. 기존 값을 확인하세요.')
        run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '1',
             '-subj', '/CN=school-db-rejoin-20260920', '-keyout', str(private), '-out', str(recipient)])
        copy_s3(recipient, BUCKET + 'recipient.crt')
        print('recipient_sha256=' + digest(recipient))
    elif args.mode == 'prepare-new':
        if sql('SELECT pg_is_in_recovery()') != 't':
            raise RuntimeError('현재 Standby에서만 준비 가능')
        conn = dict(item.split('=', 1) for item in shlex.split(sql('SHOW primary_conninfo')))
        if conn.get('host') != OLD or conn.get('user') != 'replicator':
            raise RuntimeError('기존 복제 연결의 비밀 전달 형식을 확인해야 함')
        if not conn.get('password') and conn.get('passfile') == '/var/lib/pgsql/.pgpass':
            # libpq의 콜론·역슬래시 이스케이프를 보존해 기존 자격증명만 읽는다.
            for line in Path(conn['passfile']).read_text().splitlines():
                if not line or line.startswith('#'):
                    continue
                fields, current, escaped = [], '', False
                for char in line:
                    if escaped:
                        current += char
                        escaped = False
                    elif char == '\\':
                        escaped = True
                    elif char == ':':
                        fields.append(current)
                        current = ''
                    else:
                        current += char
                fields.append(current)
                if len(fields) == 5 and all(value in ('*', expected) for value, expected in
                        zip(fields[:4], (OLD, '5432', 'replication', 'replicator'))):
                    conn['password'] = fields[4]
                    break
        if not conn.get('password'):
            raise RuntimeError('기존 복제 자격증명을 찾지 못함')
        copy_s3(BUCKET + 'recipient.crt', recipient)
        if not args.recipient_sha256 or digest(recipient) != args.recipient_sha256:
            raise RuntimeError('수신자 인증서 불일치')
        payload = json.dumps({'user': 'replicator', 'password': conn['password'], 'host': NEW}).encode()
        envelope = run(['openssl', 'cms', '-encrypt', '-aes256', '-binary', '-outform', 'DER', str(recipient)], input=payload)
        encrypted = WORK / 'replication.p7m'
        encrypted.write_bytes(envelope)
        copy_s3(encrypted, BUCKET + 'replication.p7m')
        copy_s3('/etc/postgresql/server.crt', BUCKET + 'new-primary.crt')
        hba = Path(sql('SHOW hba_file'))
        backup = WORK / 'pg_hba.before.conf'
        if not backup.exists():
            shutil.copy2(hba, backup)
        text = hba.read_text()
        for database, user, address in (('exchange', 'exchange_app', '172.31.2.86'),
                                         ('exchange', 'exchange_app', '172.31.44.141'),
                                         ('replication', 'replicator', OLD)):
            rule = f'hostssl {database} {user} {address}/32 scram-sha-256'
            if rule not in text:
                text += '\n' + rule + '\n'
        hba.write_text(text)
        if sql('SELECT count(*) FROM pg_hba_file_rules WHERE error IS NOT NULL') != '0':
            shutil.copy2(backup, hba)
            raise RuntimeError('HBA 형식 오류')
        sql('SELECT pg_reload_conf()')
        print('new_primary_certificate_sha256=' + digest(Path('/etc/postgresql/server.crt')))
        print('PASS: TLS HBA app /32, bot /32, old-primary replication /32. encrypted transfer ready.')
    else:
        if run(['systemctl', 'show', 'postgresql', '-p', 'ActiveState', '--value'], text=True).strip() != 'inactive':
            raise RuntimeError('기존 Primary가 격리되지 않음')
        if run(['systemctl', 'show', 'postgresql', '-p', 'UnitFileState', '--value'], text=True).strip() != 'masked':
            raise RuntimeError('기존 Primary 재기동 차단 미확인')
        data = Path('/var/lib/pgsql/data')
        newdata = Path('/var/lib/pgsql/data-rejoin-20260920')
        preserved = Path('/var/lib/pgsql/data-before-recovery-20260920')
        if data.is_symlink() or preserved.exists() or (newdata.exists() and
                (newdata.is_symlink() or any(newdata.iterdir()))):
            raise RuntimeError('재편입 디렉터리 상태 확인 필요. 기존 데이터는 덮어쓰지 않음')
        run.step = 'download'
        copy_s3(BUCKET + 'replication.p7m', WORK / 'replication.p7m')
        copy_s3(BUCKET + 'new-primary.crt', WORK / 'new-primary.crt')
        if not args.server_sha256 or digest(WORK / 'new-primary.crt') != args.server_sha256:
            raise RuntimeError('새 Primary 공개 인증서 불일치')
        run.step = 'decrypt'
        secret = json.loads(run(['openssl', 'cms', '-decrypt', '-binary', '-inform', 'DER',
                                '-in', str(WORK / 'replication.p7m'), '-recip', str(recipient), '-inkey', str(private)]))
        if secret['host'] != NEW or secret['user'] != 'replicator':
            raise RuntimeError('복제 대상 불일치')
        cert = Path('/etc/postgresql/new-primary.crt')
        shutil.copyfile(WORK / 'new-primary.crt', cert)
        cert.chmod(0o644)
        newdata.mkdir(mode=0o700, exist_ok=True)
        shutil.chown(newdata, user='postgres', group='postgres')
        passfile = Path('/var/lib/pgsql/.pgpass-rejoin-20260920')
        escaped = secret['password'].replace('\\', '\\\\').replace(':', '\\:')
        passfile.write_text(f'{NEW}:5432:replication:replicator:{escaped}\n')
        passfile.chmod(0o600)
        shutil.chown(passfile, user='postgres', group='postgres')
        conn = f'host={NEW} port=5432 user=replicator sslmode=verify-full sslrootcert={cert} passfile={passfile} application_name=school_standby_a'
        run.step = 'basebackup'
        run(['runuser', '-u', 'postgres', '--', 'pg_basebackup',
             '-d', conn, '-D', str(newdata), '-R', '-X', 'stream', '--no-password'])
        run.step = 'verifybackup'
        run(['runuser', '-u', 'postgres', '--', 'pg_verifybackup', str(newdata)])
        if not (newdata / 'standby.signal').is_file() or NEW not in (newdata / 'postgresql.auto.conf').read_text():
            raise RuntimeError('새 데이터의 Standby 역할 미확인')
        # 승격 전 Standby가 참조하던 슬롯은 새 Primary에 없다. 슬롯 없이 재편입한다.
        auto = newdata / 'postgresql.auto.conf'
        auto.write_text(auto.read_text() + "\nprimary_slot_name = ''\n")
        data.rename(preserved)
        newdata.rename(data)
        run(['systemctl', 'unmask', 'postgresql'])
        run(['systemctl', 'enable', '--now', 'postgresql'])
        if sql('SELECT pg_is_in_recovery()') != 't':
            run(['systemctl', 'stop', 'postgresql'])
            raise RuntimeError('재편입 뒤 Standby 역할 검사 실패')
        for _ in range(15):
            if sql('SELECT status FROM pg_stat_wal_receiver') == 'streaming':
                break
            time.sleep(2)
        else:
            raise RuntimeError('Standby는 기동했으나 streaming 미확인. 인증·슬롯·WAL을 점검하세요.')
        print('PASS: original PGDATA preserved; basebackup verified; old primary joined as standby.')
        print('wal_receiver=streaming')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # 연결 문자열이나 OpenSSL 입력이 예외에 포함돼도 비밀 원문은 출력하지 않는다.
        detail = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        raise SystemExit('복구 준비 중단: ' + detail) from None
