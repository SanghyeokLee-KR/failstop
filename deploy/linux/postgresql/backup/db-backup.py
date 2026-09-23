#!/usr/bin/env python3
"""현재 DB 역할을 확인하고 물리 베이스백업을 만들어 검증한 뒤 로컬 보존과 외부 보관을 처리한다."""
import argparse
import base64
import datetime as dt
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import stat
import subprocess
import tarfile
import tempfile
import time

CONFIG_DEFAULTS = {
    'BACKUP_ROLE': 'primary',
    'BACKUP_DIR': '/var/lib/pgsql/backups/basebackup',
    'KEEP_LOCAL': '3',
    'MIN_FREE_BYTES': str(2 * 1024 ** 3),
    'MIN_FREE_PERCENT': '20',
    'COMPRESSION': 'gzip:6',
    'CHECKPOINT': 'spread',
    'DATABASE': 'exchange',
    'LOGICAL_DUMP': 'on',
    'TEXTFILE_DIR': '/var/lib/node_exporter/textfile',
    'S3_BUCKET': '',
    'S3_PREFIX': '',
    'S3_REGION': 'ap-northeast-2',
    'S3_SSE': 'AES256',
    'AWS_CLI': '/usr/bin/aws',
}
BACKUP_ID = re.compile(r'\d{8}T\d{6}Z-[0-9a-f]{6}')
S3_BUCKET = re.compile(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]')
S3_PREFIX = re.compile(r'[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*')
# 네 테이블은 삭제·갱신 금지 트리거가 있는 append-only 기록이다. 복원 검사가 이 건수로
# 백업 시작 전과 종료 뒤 사이의 시점에 복원됐는지 확인한다.
COUNTS = """SELECT json_build_object(
'commands',(SELECT count(*) FROM command_journal),
'events',(SELECT count(*) FROM event_journal),
'trades',(SELECT count(*) FROM trades),
'ledger_entries',(SELECT count(*) FROM ledger_entries));"""
METRIC = 'failstop_db_backup'
# 옛 백업 도구(deploy/backup/backup.py)의 지표 이름이다. 학교 일일 보고의 backup_success 항목과 MSP DB 대시보드의
# 백업 경과 패널이 아직 이 이름을 조회하므로, 소비자를 새 이름으로 옮기기 전까지 같은 값을 이 이름으로도 낸다.
LEGACY_METRIC = 'failstop_backup'
SHORT_TIMEOUT = 120
LONG_TIMEOUT = 3600
# put-object 한 번에 올릴 수 있는 S3 상한. 넘으면 멀티파트가 필요해 전체 SHA-256 대조가 달라진다.
SINGLE_PUT_LIMIT = 5 * 1024 ** 3
EXIT_FAIL, EXIT_OFFSITE_FAIL, EXIT_BUSY = 1, 2, 75


class BackupError(Exception):
    def __init__(self, stage, message):
        super().__init__(message)
        self.stage = stage


class BackupInterrupted(BackupError):
    pass


class BackupBusy(Exception):
    pass


def stop_on_sigterm():
    # systemd는 TimeoutStartSec을 넘기거나 중지 요청을 받으면 SIGTERM을 보낸다. 기본 동작대로 끝나면 finally가
    # 돌지 않아 직전 실행의 성공 지표가 남는다. 예외로 바꿔 실패 시도 보관과 실패 지표 기록을 거치게 한다.
    def interrupt(number, frame):
        # 정리하는 동안 받은 두 번째 신호가 지표 기록을 끊지 않게 한다. 멈추지 않으면 systemd가 SIGKILL을 보낸다.
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        raise BackupInterrupted('signal', 'SIGTERM을 받아 중단했다. 서비스 시간 상한 초과나 중지 요청이다.')

    signal.signal(signal.SIGTERM, interrupt)


def run(args, stage, timeout=SHORT_TIMEOUT, env=None):
    try:
        result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True,
                                encoding='utf-8', errors='replace', timeout=timeout, env=env)
    except FileNotFoundError:
        raise BackupError(stage, f'{args[0]} 실행 파일을 찾지 못했다.') from None
    except subprocess.TimeoutExpired:
        raise BackupError(stage, f'{args[0]} {timeout}초 초과') from None
    except BackupInterrupted as interrupted:
        # subprocess.run이 자식 명령을 이미 종료했다. 어느 단계가 오래 걸렸는지 남긴다.
        raise BackupInterrupted(stage, f'{args[0]} 실행 중 {interrupted}') from None
    if result.returncode:
        # 이 스크립트는 비밀번호를 인수나 환경으로 넘기지 않는다. 진단에 필요한 오류 끝부분만 남긴다.
        raise BackupError(stage, f'{args[0]} 실패(반환값 {result.returncode}): {result.stderr.strip()[-800:]}')
    return result.stdout


def psql(sql, database='postgres'):
    return run(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-d', database, '-c', sql], 'database').strip()


def load_config(path, require_root_owned=True):
    info = os.lstat(path)
    if not stat.S_ISREG(info.st_mode):
        raise BackupError('config', f'{path}는 일반 파일이어야 한다.')
    # 백업 계정이 자기 설정을 바꿔 보존 개수나 보관 위치를 조작하지 못하게 한다.
    if require_root_owned and (info.st_uid != 0 or info.st_mode & 0o022):
        raise BackupError('config', f'{path}는 root 소유이고 그룹·기타 쓰기 권한이 없어야 한다.')
    values = dict(CONFIG_DEFAULTS)
    seen = set()
    for number, line in enumerate(Path(path).read_text(encoding='utf-8').splitlines(), 1):
        text = line.strip()
        if not text or text.startswith('#'):
            continue
        key, separator, value = text.partition('=')
        key = key.strip()
        if not separator or key not in CONFIG_DEFAULTS or key in seen:
            raise BackupError('config', f'{path}:{number} 알 수 없거나 중복된 설정: {key}')
        seen.add(key)
        values[key] = value.strip()
    return validate_config(values)


def validate_config(values):
    def fail(key):
        raise BackupError('config', f'{key} 값이 올바르지 않다: {values[key]!r}')

    def integer(key, low, high):
        if not re.fullmatch(r'\d+', values[key]) or not low <= int(values[key]) <= high:
            fail(key)
        return int(values[key])

    for key, allowed in (('BACKUP_ROLE', ('primary', 'standby', 'any')), ('CHECKPOINT', ('spread', 'fast')),
                         ('LOGICAL_DUMP', ('on', 'off')), ('S3_SSE', ('AES256', 'aws:kms'))):
        if values[key] not in allowed:
            fail(key)
    for key in ('BACKUP_DIR', 'TEXTFILE_DIR', 'AWS_CLI'):
        if not values[key].startswith('/'):
            fail(key)
    if not re.fullmatch(r'none|gzip(:[1-9])?', values['COMPRESSION']):
        fail('COMPRESSION')
    if not re.fullmatch(r'[a-z_][a-z0-9_]{0,62}', values['DATABASE']):
        fail('DATABASE')
    if not re.fullmatch(r'[a-z]{2}(-[a-z]+)+-\d', values['S3_REGION']):
        fail('S3_REGION')
    config = {
        'role': values['BACKUP_ROLE'],
        'backup_dir': Path(values['BACKUP_DIR']),
        'textfile_dir': Path(values['TEXTFILE_DIR']),
        'keep_local': integer('KEEP_LOCAL', 1, 30),
        'min_free_bytes': integer('MIN_FREE_BYTES', 0, 2 ** 62),
        'min_free_percent': integer('MIN_FREE_PERCENT', 0, 90),
        'compression': values['COMPRESSION'],
        'checkpoint': values['CHECKPOINT'],
        'database': values['DATABASE'],
        'logical': values['LOGICAL_DUMP'] == 'on',
        's3': None,
    }
    bucket, prefix = values['S3_BUCKET'], values['S3_PREFIX']
    if bucket or prefix:
        # 둘 중 하나만 적힌 설정은 오타일 가능성이 커서 조용히 '미설정'으로 넘기지 않는다.
        if not S3_BUCKET.fullmatch(bucket):
            fail('S3_BUCKET')
        if not S3_PREFIX.fullmatch(prefix) or '..' in prefix.split('/'):
            fail('S3_PREFIX')
        config['s3'] = {'bucket': bucket, 'prefix': prefix, 'region': values['S3_REGION'],
                        'sse': values['S3_SSE'], 'cli': values['AWS_CLI']}
    return config


def acquire_lock(directory):
    import fcntl
    lock = (directory / '.lock').open('a')
    try:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as error:
        lock.close()
        if error.errno in (errno.EAGAIN, errno.EACCES):
            raise BackupBusy(f'{directory}에서 다른 백업이 실행 중이다.') from None
        raise
    return lock


def backup(config):
    directory = config['backup_dir']
    if directory.is_symlink():
        raise BackupError('config', f'{directory}는 심볼릭 링크일 수 없다.')
    directory.mkdir(mode=0o700, exist_ok=True)
    directory.chmod(0o700)
    lock = acquire_lock(directory)
    try:
        state = {'eligible': True, 'success': False, 'verified': None, 'offsite': None}
        report = {'started_at': utc_text(time.time())}
        try:
            perform_backup(config, state, report)
        except BackupError as error:
            report.update(status='FAIL', stage=error.stage, error=str(error))
        finally:
            # 예상하지 못한 예외로 끝나도 실패 지표를 남겨 경보가 26시간을 기다리지 않게 한다.
            write_metrics(config['textfile_dir'], render_metrics(
                state, local_history(directory), config['s3'] is not None, time.time()))
        return report
    finally:
        lock.close()


def perform_backup(config, state, report):
    role = current_role()
    report['role'] = role
    if config['role'] not in ('any', role):
        # 두 DB 모두 같은 타이머를 두고, 승격·재편입 뒤에는 역할에 맞는 쪽만 실제로 백업한다.
        state['eligible'] = False
        report['status'] = 'SKIPPED_ROLE'
        return
    directory = config['backup_dir']
    clear_leftovers(directory)
    ensure_outside_pgdata(directory)
    database_bytes = int(psql('SELECT sum(pg_database_size(oid)) FROM pg_database'))
    report['disk'] = check_free_space(directory, database_bytes, config)
    backup_id = new_backup_id()
    report['backup_id'] = backup_id
    staging = directory / ('.staging-' + backup_id)
    staging.mkdir(mode=0o700)
    started = time.time()
    try:
        metadata = create_verified_backup(config, role, backup_id, staging, state)
        metadata['started_at'] = utc_text(started)
        metadata['completed_at'] = utc_text(time.time())
        write_json(staging / 'metadata.json', metadata)
        files = {name: entry['sha256'] for name, entry in metadata['files'].items()}
        files['metadata.json'] = sha256_file(staging / 'metadata.json')
        (staging / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in sorted(files.items())),
                                            encoding='utf-8')
        final = directory / backup_id
        # 이름 변경이 완료 표시다. 검증 전 산출물은 보존 개수나 마지막 성공으로 세지 않는다.
        staging.rename(final)
    except BaseException:
        keep_failed_attempt(directory, staging, backup_id)
        raise
    report.update(bytes=sum(entry['bytes'] for entry in metadata['files'].values()),
                  duration_seconds=round(time.time() - started, 3), path=str(final))
    offsite = upload_offsite(final, backup_id, metadata['source']['system_identifier'], config)
    write_json(final / 'offsite.json', offsite)
    state['offsite'] = offsite['status']
    report['offsite'] = offsite
    report['removed'] = prune_local(directory, backup_id, config['keep_local'])
    state['success'] = True
    report['status'] = {'UNCONFIGURED': 'LOCAL_ONLY', 'VERIFIED': 'OFFSITE_VERIFIED'}.get(
        offsite['status'], 'OFFSITE_FAILED')


def new_backup_id():
    # UTC 시각이 앞에 있어 이름순이 생성순이다. 같은 초의 수동 실행과 겹치지 않게 임의 값을 붙인다.
    return time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + secrets.token_hex(3)


def current_role():
    value = psql('SELECT pg_is_in_recovery()')
    if value not in ('t', 'f'):
        raise BackupError('role', f'pg_is_in_recovery() 결과를 해석할 수 없다: {value!r}')
    return 'standby' if value == 't' else 'primary'


def clear_leftovers(directory):
    # 잠금을 잡은 상태라 남은 작업 디렉터리는 중단된 이전 실행의 흔적이다.
    for leftover in directory.glob('.verify-*'):
        shutil.rmtree(leftover)
    for leftover in sorted(directory.glob('.staging-*')):
        keep_failed_attempt(directory, leftover, leftover.name[len('.staging-'):])


def keep_failed_attempt(directory, staging, backup_id):
    if not staging.exists():
        return
    # 원인 조사용으로 가장 최근 실패 한 건만 남긴다. 실패가 반복돼도 디스크를 계속 쓰지 않는다.
    for old in directory.glob('failed-*'):
        if old.is_dir() and not old.is_symlink():
            shutil.rmtree(old)
    staging.rename(directory / ('failed-' + backup_id))


def ensure_outside_pgdata(directory):
    data = Path(psql('SHOW data_directory')).resolve()
    target = directory.resolve()
    if target == data or data in target.parents:
        # PGDATA 안의 백업은 다음 베이스백업과 Standby 재구성에 함께 복사된다.
        raise BackupError('config', f'BACKUP_DIR {target}가 PGDATA {data} 안에 있다.')


def check_free_space(directory, database_bytes, config):
    usage = shutil.disk_usage(directory)
    # 압축 전 DB 크기를 상한으로 잡는다. 압축 산출물, 검증용 압축 해제본, 논리 덤프가 동시에 놓인다.
    copies = 3 if config['logical'] else 2
    floor = max(config['min_free_bytes'], usage.total * config['min_free_percent'] // 100)
    need = copies * database_bytes + floor
    if usage.free < need:
        raise BackupError('disk', f'디스크 여유 부족: 여유 {usage.free}바이트, 필요 {need}바이트'
                                  f'(남겨 둘 하한 {floor}바이트 포함). 새 백업을 만들지 않았다.')
    return {'free_bytes': usage.free, 'required_bytes': need, 'database_bytes': database_bytes}


def create_verified_backup(config, role, backup_id, staging, state):
    system_identifier = psql('SELECT system_identifier FROM pg_control_system()')
    version = int(psql('SHOW server_version_num'))
    counts_before = table_counts(config['database'])
    command = ['pg_basebackup', '-D', staging, '--format=tar', '--wal-method=stream',
               '--checkpoint=' + config['checkpoint'], '--label=failstop-' + backup_id,
               '--no-password', '--dbname=application_name=failstop_backup']
    if config['compression'] != 'none':
        command.append('--compress=' + config['compression'])
    run(command, 'basebackup', LONG_TIMEOUT)
    counts_after = table_counts(config['database'])
    suffix = '.tar' if config['compression'] == 'none' else '.tar.gz'
    produced = sorted(path.name for path in staging.iterdir())
    if produced != sorted(['backup_manifest', 'base' + suffix, 'pg_wal' + suffix]):
        raise BackupError('basebackup', f'예상과 다른 산출물: {produced}. 테이블스페이스는 지원하지 않는다.')
    manifest = json.loads((staging / 'backup_manifest').read_text(encoding='utf-8'))
    state['verified'] = False
    if str(manifest.get('System-Identifier', system_identifier)) != system_identifier:
        raise BackupError('verify', '매니페스트의 시스템 식별자가 현재 클러스터와 다르다.')
    verify_basebackup(staging, suffix, staging.parent / ('.verify-' + backup_id))
    state['verified'] = True
    logical = take_logical_dump(staging, config['database']) if config['logical'] else {'enabled': False}
    wal = manifest['WAL-Ranges']
    return {
        'format': 'failstop-basebackup-1',
        'backup_id': backup_id,
        'source': {'host': socket.gethostname(), 'role': role, 'system_identifier': system_identifier,
                   'server_version_num': version},
        'basebackup': {'compression': config['compression'], 'checkpoint': config['checkpoint'],
                       'timeline': wal[-1]['Timeline'], 'start_lsn': wal[0]['Start-LSN'],
                       'end_lsn': wal[-1]['End-LSN'], 'manifest_files': len(manifest['Files']),
                       'data_bytes': sum(entry['Size'] for entry in manifest['Files'])},
        'counts_before': counts_before,
        'counts_after': counts_after,
        'logical_dump': logical,
        'verification': {'pg_verifybackup': 'PASS', 'wal_parsed': True},
        'files': {path.name: {'bytes': path.stat().st_size, 'sha256': sha256_file(path)}
                  for path in sorted(staging.iterdir())},
        'restore_checked': False,
    }


def table_counts(database):
    return json.loads(run(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-d', database, '-c', COUNTS], 'counts'))


def verify_basebackup(staging, suffix, workdir):
    # PostgreSQL 17의 pg_verifybackup은 tar 형식을 직접 읽지 못한다. 풀어 놓은 사본으로 매니페스트와 WAL을 검사한다.
    data = workdir / 'data'
    try:
        data.mkdir(mode=0o700, parents=True)
        extract(staging / ('base' + suffix), data)
        extract(staging / ('pg_wal' + suffix), data / 'pg_wal')
        run(['pg_verifybackup', '--manifest-path', staging / 'backup_manifest', data], 'verify', LONG_TIMEOUT)
    finally:
        if workdir.exists():
            shutil.rmtree(workdir)


def extract(archive, target):
    target.mkdir(mode=0o700, exist_ok=True)
    with tarfile.open(archive, 'r:*') as tar:
        for member in tar:
            parts = Path(member.name).parts
            if member.name.startswith('/') or '..' in parts or not (member.isfile() or member.isdir()):
                raise BackupError('verify', f'{archive.name}에 허용하지 않는 항목이 있다: {member.name}')
            tar.extract(member, target, set_attrs=False)


def take_logical_dump(staging, database):
    dump = staging / (database + '.dump')
    # 잠금 대기 상한은 DDL 뒤에 줄 선 거래 쿼리가 덤프 때문에 묶이는 시간을 제한한다.
    run(['pg_dump', '--format=custom', '--lock-wait-timeout=10s', '--no-password',
         '--dbname=' + database, '--file=' + str(dump)], 'logical', LONG_TIMEOUT)
    run(['pg_dumpall', '--globals-only', '--no-role-passwords', '--no-password',
         '--file=' + str(staging / 'globals.sql')], 'logical')
    listing = run(['pg_restore', '--list', dump], 'logical')
    table_data = sum(1 for line in listing.splitlines() if ' TABLE DATA ' in line)
    if not table_data:
        raise BackupError('logical', '논리 덤프 목차에 테이블 데이터가 없다.')
    return {'enabled': True, 'database': database, 'table_data_entries': table_data}


def upload_offsite(directory, backup_id, system_identifier, config):
    s3 = config['s3']
    if s3 is None:
        return {'status': 'UNCONFIGURED', 'checked_at': utc_text(time.time())}
    prefix = f"{s3['prefix']}/{system_identifier}/{backup_id}"
    recorded = read_sha256sums(directory / 'SHA256SUMS')
    # SHA256SUMS를 마지막에 올려, 원격에 이 파일이 있으면 나머지가 모두 대조됐다는 뜻이 되게 한다.
    names = sorted(recorded) + ['SHA256SUMS']
    recorded['SHA256SUMS'] = sha256_file(directory / 'SHA256SUMS')
    env = dict(os.environ, AWS_PAGER='', AWS_RETRY_MODE='standard', AWS_MAX_ATTEMPTS='5')
    uploaded = []
    try:
        for name in names:
            path = directory / name
            digest = sha256_file(path)
            if digest != recorded[name]:
                raise BackupError('offsite', f'{name}의 현재 SHA-256이 기록과 다르다. 올리지 않았다.')
            size = path.stat().st_size
            if size > SINGLE_PUT_LIMIT:
                raise BackupError('offsite', f'{name}이 단일 업로드 상한 5GiB를 넘는다.')
            key = f'{prefix}/{name}'
            checksum = base64.b64encode(bytes.fromhex(digest)).decode('ascii')
            location = ['--bucket', s3['bucket'], '--key', key, '--region', s3['region'], '--output', 'json']
            # S3가 받은 본문의 SHA-256을 직접 계산해 다르면 업로드를 거부한다.
            run([s3['cli'], 's3api', 'put-object', *location, '--body', path,
                 '--server-side-encryption', s3['sse'], '--checksum-sha256', checksum,
                 '--metadata', 'sha256=' + digest], 'offsite', LONG_TIMEOUT, env)
            output = run([s3['cli'], 's3api', 'head-object', *location, '--checksum-mode', 'ENABLED'],
                         'offsite', SHORT_TIMEOUT, env)
            try:
                head = json.loads(output)
            except ValueError:
                raise BackupError('offsite', f'{key} head-object 응답을 해석하지 못했다.') from None
            mismatch = [field for field, ok in (
                ('크기', head.get('ContentLength') == size),
                ('SHA-256', head.get('ChecksumSHA256') == checksum),
                ('암호화', head.get('ServerSideEncryption') == s3['sse']),
                ('메타데이터', head.get('Metadata', {}).get('sha256') == digest)) if not ok]
            if mismatch:
                raise BackupError('offsite', f'{key} 원격 대조 실패: {", ".join(mismatch)}')
            uploaded.append(name)
    except BackupInterrupted:
        # 중지 요청은 외부 보관 실패로 바꾸지 않는다. 로컬 정리까지 이어 가지 않고 실행 실패로 끝낸다.
        raise
    except BackupError as error:
        return {'status': 'FAILED', 'bucket': s3['bucket'], 'prefix': prefix, 'uploaded': uploaded,
                'error': str(error), 'checked_at': utc_text(time.time())}
    return {'status': 'VERIFIED', 'bucket': s3['bucket'], 'prefix': prefix, 'uploaded': uploaded,
            'checked_at': utc_text(time.time())}


def read_sha256sums(path):
    entries = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        digest, _, name = line.partition('  ')
        entries[name] = digest
    return entries


def complete_backups(directory):
    return sorted((path.name for path in directory.iterdir()
                   if BACKUP_ID.fullmatch(path.name) and path.is_dir() and not path.is_symlink()
                   and (path / 'metadata.json').is_file()), reverse=True)


def prune_local(directory, current, keep):
    # 새 백업의 검증과 완료 표시가 끝난 뒤에만 호출된다. 이번 백업은 시계가 뒤로 가도 남긴다.
    names = complete_backups(directory)
    keep_names = [current] + [name for name in names if name != current][:keep - 1]
    removed = [name for name in names if name not in keep_names]
    for name in removed:
        shutil.rmtree(directory / name)
    return removed


def local_history(directory):
    names = complete_backups(directory)
    history = {'count': len(names), 'last_success': None, 'last_bytes': None, 'offsite_last_success': None}
    if names:
        metadata = json.loads((directory / names[0] / 'metadata.json').read_text(encoding='utf-8'))
        history['last_success'] = utc_epoch(metadata['completed_at'])
        history['last_bytes'] = sum(entry['bytes'] for entry in metadata['files'].values())
    for name in names:
        offsite = directory / name / 'offsite.json'
        if offsite.is_file():
            record = json.loads(offsite.read_text(encoding='utf-8'))
            if record['status'] == 'VERIFIED':
                history['offsite_last_success'] = utc_epoch(record['checked_at'])
                break
    return history


def render_metrics(state, history, offsite_configured, now):
    lines = []

    def gauge(name, value, description, prefix=METRIC):
        lines.extend([f'# HELP {prefix}_{name} {description}', f'# TYPE {prefix}_{name} gauge',
                      f'{prefix}_{name} {value}'])

    gauge('role_eligible', int(state['eligible']), '현재 DB 역할이 설정한 백업 대상 역할과 맞으면 1')
    gauge('last_run_timestamp_seconds', int(now), '마지막 실행이 끝난 시각')
    # 역할이 맞지 않아 건너뛴 실행은 성공·실패 지표를 내지 않는다. 승격 뒤 옛 Primary의 실패가 계속 울리지 않게 한다.
    if state['eligible']:
        gauge('last_run_success', int(state['success']), '마지막 실행이 검증된 백업 생성과 로컬 정리까지 끝났으면 1')
        if state['verified'] is not None:
            gauge('last_verify_success', int(state['verified']), '마지막 베이스백업의 매니페스트·WAL 검증 통과 여부')
        if state['offsite'] in ('VERIFIED', 'FAILED'):
            gauge('offsite_last_success', int(state['offsite'] == 'VERIFIED'), '마지막 외부 보관 업로드·원격 대조 통과 여부')
    gauge('offsite_configured', int(offsite_configured), '외부 보관 버킷과 접두어가 설정됐으면 1')
    gauge('local_backups', history['count'], '이 호스트에 남은 검증 완료 백업 수')
    if history['last_success'] is not None:
        gauge('last_success_timestamp_seconds', int(history['last_success']), '가장 최근 검증 완료 백업의 완료 시각')
        gauge('last_success_bytes', history['last_bytes'], '가장 최근 검증 완료 백업의 파일 크기 합계')
    if history['offsite_last_success'] is not None:
        gauge('offsite_last_success_timestamp_seconds', int(history['offsite_last_success']),
              '외부 보관 대조를 마지막으로 통과한 시각')
    if state['eligible']:
        gauge('last_run_success', int(state['success']), f'{METRIC}_last_run_success와 같은 값(옛 이름)',
              LEGACY_METRIC)
    if history['last_success'] is not None:
        gauge('last_success_timestamp_seconds', int(history['last_success']),
              f'{METRIC}_last_success_timestamp_seconds와 같은 값(옛 이름)', LEGACY_METRIC)
    return '\n'.join(lines) + '\n'


def write_metrics(directory, text):
    # node_exporter가 쓰는 중인 파일을 읽지 않도록 같은 디렉터리의 임시 파일을 이름 변경으로 교체한다.
    handle = tempfile.NamedTemporaryFile('w', dir=directory, prefix=f'.{METRIC}.', suffix='.tmp',
                                         delete=False, encoding='utf-8')
    try:
        with handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(handle.name, 0o644)
        os.replace(handle.name, Path(directory) / f'{METRIC}.prom')
    except BaseException:
        if os.path.exists(handle.name):
            os.unlink(handle.name)
        raise


def write_json(path, value):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.replace(temporary, path)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def utc_text(epoch):
    return dt.datetime.fromtimestamp(epoch, dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def utc_epoch(text):
    return dt.datetime.strptime(text, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=dt.timezone.utc).timestamp()


def exit_code(report):
    return {'FAIL': EXIT_FAIL, 'OFFSITE_FAILED': EXIT_OFFSITE_FAIL}.get(report['status'], 0)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=Path('/etc/failstop/db-backup.conf'))
    args = parser.parse_args(argv)
    os.umask(0o077)
    stop_on_sigterm()
    import pwd
    try:
        if pwd.getpwuid(os.geteuid()).pw_name != 'postgres':
            raise BackupError('config', 'postgres OS 계정으로 실행해야 한다. 로컬 소켓 peer 인증을 쓴다.')
        report = backup(load_config(args.config))
    except BackupBusy as busy:
        print(json.dumps({'status': 'BUSY', 'error': str(busy)}, ensure_ascii=False))
        return EXIT_BUSY
    except BackupError as error:
        print(json.dumps({'status': 'FAIL', 'stage': error.stage, 'error': str(error)}, ensure_ascii=False))
        return EXIT_FAIL
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return exit_code(report)


if __name__ == '__main__':
    raise SystemExit(main())
