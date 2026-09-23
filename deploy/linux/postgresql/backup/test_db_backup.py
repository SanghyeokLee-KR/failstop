import base64
import collections
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import tarfile
import tempfile
import unittest
from unittest import mock

if os.name != 'posix':
    raise unittest.SkipTest('db-backup.py는 Linux 전용이다. 경로 규칙과 flock이 POSIX 기준이다.')

SPEC = importlib.util.spec_from_file_location('db_backup', Path(__file__).with_name('db-backup.py'))
db = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(db)
REAL_RUN = db.run

SYSTEM_ID = '7688711809730498594'
GIB = 1024 ** 3
Usage = collections.namedtuple('Usage', 'total used free')


def add_file(tar, name, data):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    tar.addfile(info, io.BytesIO(data))


def add_dir(tar, name):
    info = tarfile.TarInfo(name)
    info.type = tarfile.DIRTYPE
    tar.addfile(info)


class FakeServer:
    """psql·pg_basebackup·pg_verifybackup·pg_dump·aws 호출을 대신한다. 실제 프로세스는 띄우지 않는다."""

    def __init__(self):
        self.recovery = 'f'
        self.pgdata = '/var/lib/pgsql/data'
        self.database_bytes = 64 * 1024 ** 2
        self.counts = {'commands': 10, 'events': 20, 'trades': 5, 'ledger_entries': 40}
        self.verify_ok = True
        self.corrupt_remote = False
        self.objects = {}
        self.calls = []

    def programs(self):
        return [Path(call[0]).name for call in self.calls]

    def __call__(self, args, stage, timeout=db.SHORT_TIMEOUT, env=None):
        args = [str(arg) for arg in args]
        self.calls.append(args)
        program = Path(args[0]).name
        if program == 'psql':
            return self.psql(args[-1])
        if program == 'pg_basebackup':
            return self.basebackup(Path(args[args.index('-D') + 1]))
        if program == 'pg_verifybackup':
            data = Path(args[-1])
            if not (data / 'PG_VERSION').is_file() or not (data / 'pg_wal' / '000000010000000000000002').is_file():
                raise AssertionError('검증 전에 두 tar를 모두 풀어야 한다.')
            if not self.verify_ok:
                raise db.BackupError(stage, 'pg_verifybackup 실패(반환값 1): checksum mismatch')
            return 'backup successfully verified\n'
        if program == 'pg_dump':
            Path(next(a for a in args if a.startswith('--file='))[7:]).write_bytes(b'PGDMP custom dump')
            return ''
        if program == 'pg_dumpall':
            Path(next(a for a in args if a.startswith('--file='))[7:]).write_text('CREATE ROLE app;\n')
            return ''
        if program == 'pg_restore':
            return ';\n3001; 0 16400 TABLE DATA public trades postgres\n'
        if program == 'aws':
            return self.aws(args)
        raise AssertionError(f'예상하지 못한 명령: {args}')

    def psql(self, sql):
        if 'pg_is_in_recovery' in sql:
            return self.recovery + '\n'
        if 'data_directory' in sql:
            return self.pgdata + '\n'
        if 'pg_database_size' in sql:
            return f'{self.database_bytes}\n'
        if 'system_identifier' in sql:
            return SYSTEM_ID + '\n'
        if 'server_version_num' in sql:
            return '170011\n'
        if 'json_build_object' in sql:
            return json.dumps(self.counts) + '\n'
        raise AssertionError(sql)

    def basebackup(self, target):
        with tarfile.open(target / 'base.tar.gz', 'w:gz') as tar:
            add_file(tar, 'PG_VERSION', b'17\n')
            add_dir(tar, 'pg_wal')
        with tarfile.open(target / 'pg_wal.tar.gz', 'w:gz') as tar:
            add_file(tar, '000000010000000000000002', b'\0' * 64)
        manifest = {'PostgreSQL-Backup-Manifest-Version': 2, 'System-Identifier': int(SYSTEM_ID),
                    'Files': [{'Path': 'PG_VERSION', 'Size': 3}],
                    'WAL-Ranges': [{'Timeline': 1, 'Start-LSN': '0/2000028', 'End-LSN': '0/200AD80'}]}
        (target / 'backup_manifest').write_text(json.dumps(manifest))
        return ''

    def aws(self, args):
        key = args[args.index('--key') + 1]
        if args[2] == 'put-object':
            body = Path(args[args.index('--body') + 1]).read_bytes()
            self.objects[key] = {'body': body, 'sse': args[args.index('--server-side-encryption') + 1],
                                 'checksum': args[args.index('--checksum-sha256') + 1],
                                 'metadata': args[args.index('--metadata') + 1]}
            return '{}'
        stored = self.objects[key]
        digest = hashlib.sha256(stored['body']).digest()
        if self.corrupt_remote:
            digest = hashlib.sha256(b'other').digest()
        return json.dumps({'ContentLength': len(stored['body']),
                           'ChecksumSHA256': base64.b64encode(digest).decode(),
                           'ServerSideEncryption': stored['sse'],
                           'Metadata': {'sha256': stored['metadata'].split('=', 1)[1]}})


class DbBackupTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.backups = self.root / 'backups'
        self.textfile = self.root / 'textfile'
        self.textfile.mkdir()
        self.server = FakeServer()
        # 같은 초에 여러 번 실행해도 생성 순서가 이름 순서와 같도록 ID를 차례로 준다.
        ids = iter(f'20260923T1{index:05d}Z-abc{index:03x}' for index in range(1, 100))
        for patcher in (mock.patch.object(db, 'run', self.server),
                        mock.patch.object(db, 'new_backup_id', lambda: next(ids)),
                        mock.patch.object(db.shutil, 'disk_usage', return_value=Usage(50 * GIB, 10 * GIB, 40 * GIB))):
            patcher.start()
            self.addCleanup(patcher.stop)

    def config(self, **values):
        settings = dict(db.CONFIG_DEFAULTS, BACKUP_DIR=str(self.backups), TEXTFILE_DIR=str(self.textfile))
        settings.update({key: str(value) for key, value in values.items()})
        return db.validate_config(settings)

    def metrics(self):
        text = (self.textfile / 'failstop_db_backup.prom').read_text(encoding='utf-8')
        return {line.split(' ')[0]: line.split(' ')[1] for line in text.splitlines() if not line.startswith('#')}

    def complete(self):
        return db.complete_backups(self.backups)

    def test_primary_backup_is_verified_hashed_and_marked_local_only(self):
        report = db.backup(self.config())

        self.assertEqual('LOCAL_ONLY', report['status'])
        self.assertEqual(0, db.exit_code(report))
        backup = self.backups / report['backup_id']
        self.assertEqual(['SHA256SUMS', 'backup_manifest', 'base.tar.gz', 'exchange.dump', 'globals.sql',
                          'metadata.json', 'offsite.json', 'pg_wal.tar.gz'],
                         sorted(path.name for path in backup.iterdir()))
        for line in (backup / 'SHA256SUMS').read_text().splitlines():
            digest, name = line.split('  ')
            self.assertEqual(hashlib.sha256((backup / name).read_bytes()).hexdigest(), digest)
        metadata = json.loads((backup / 'metadata.json').read_text())
        self.assertEqual('primary', metadata['source']['role'])
        self.assertEqual(self.server.counts, metadata['counts_before'])
        self.assertEqual('0/200AD80', metadata['basebackup']['end_lsn'])
        self.assertEqual('UNCONFIGURED', json.loads((backup / 'offsite.json').read_text())['status'])
        self.assertNotIn('aws', self.server.programs())
        basebackup = next(call for call in self.server.calls if Path(call[0]).name == 'pg_basebackup')
        self.assertIn('--format=tar', basebackup)
        self.assertIn('--wal-method=stream', basebackup)
        self.assertIn('--checkpoint=spread', basebackup)
        self.assertIn('--compress=gzip:6', basebackup)
        metrics = self.metrics()
        self.assertEqual('1', metrics['failstop_db_backup_last_run_success'])
        self.assertEqual('1', metrics['failstop_db_backup_last_verify_success'])
        self.assertEqual('0', metrics['failstop_db_backup_offsite_configured'])
        self.assertNotIn('failstop_db_backup_offsite_last_success', metrics)
        self.assertEqual('1', metrics['failstop_db_backup_local_backups'])
        self.assertFalse(list(self.backups.glob('.verify-*')))

    def test_standby_is_skipped_without_touching_backups_when_primary_is_required(self):
        first = db.backup(self.config())
        self.server.recovery = 't'
        calls = len(self.server.calls)

        report = db.backup(self.config())

        self.assertEqual('SKIPPED_ROLE', report['status'])
        self.assertEqual(0, db.exit_code(report))
        self.assertEqual(['psql'], [Path(call[0]).name for call in self.server.calls[calls:]])
        self.assertEqual([first['backup_id']], self.complete())
        metrics = self.metrics()
        self.assertEqual('0', metrics['failstop_db_backup_role_eligible'])
        self.assertNotIn('failstop_db_backup_last_run_success', metrics)
        self.assertNotIn('failstop_db_backup_last_verify_success', metrics)
        self.assertIn('failstop_db_backup_last_success_timestamp_seconds', metrics)

    def test_standby_setting_backs_up_the_standby(self):
        self.server.recovery = 't'

        report = db.backup(self.config(BACKUP_ROLE='standby'))

        self.assertEqual('LOCAL_ONLY', report['status'])
        metadata = json.loads((self.backups / report['backup_id'] / 'metadata.json').read_text())
        self.assertEqual('standby', metadata['source']['role'])

    def test_low_disk_refuses_before_basebackup_and_keeps_existing_backup(self):
        existing = db.backup(self.config())['backup_id']
        calls = len(self.server.calls)
        # 50GiB의 20%(10GiB) 하한만으로도 여유 5GiB를 넘는다.
        with mock.patch.object(db.shutil, 'disk_usage', return_value=Usage(50 * GIB, 45 * GIB, 5 * GIB)):
            report = db.backup(self.config(KEEP_LOCAL=1))

        self.assertEqual(('FAIL', 'disk'), (report['status'], report['stage']))
        self.assertEqual(1, db.exit_code(report))
        self.assertNotIn('pg_basebackup', [Path(call[0]).name for call in self.server.calls[calls:]])
        self.assertEqual([existing], self.complete())
        self.assertFalse(list(self.backups.glob('failed-*')))
        metrics = self.metrics()
        self.assertEqual('0', metrics['failstop_db_backup_last_run_success'])
        self.assertNotIn('failstop_db_backup_last_verify_success', metrics)

    def test_verify_failure_keeps_every_previous_backup_and_only_the_latest_failed_attempt(self):
        older = [db.backup(self.config(KEEP_LOCAL=2))['backup_id'] for _ in range(2)]
        self.server.verify_ok = False

        first_failure = db.backup(self.config(KEEP_LOCAL=1))
        second_failure = db.backup(self.config(KEEP_LOCAL=1))

        self.assertEqual(('FAIL', 'verify'), (second_failure['status'], second_failure['stage']))
        self.assertEqual(sorted(older, reverse=True), self.complete())
        self.assertEqual(['failed-' + second_failure['backup_id']],
                         [path.name for path in self.backups.glob('failed-*')])
        self.assertFalse((self.backups / ('failed-' + first_failure['backup_id'])).exists())
        self.assertFalse(list(self.backups.glob('.verify-*')))
        self.assertFalse(list(self.backups.glob('.staging-*')))
        metrics = self.metrics()
        self.assertEqual('0', metrics['failstop_db_backup_last_verify_success'])
        self.assertEqual('2', metrics['failstop_db_backup_local_backups'])

        self.server.verify_ok = True
        recovered = db.backup(self.config(KEEP_LOCAL=1))
        self.assertEqual([recovered['backup_id']], self.complete())
        self.assertEqual(sorted(older), sorted(recovered['removed']))

    def test_retention_keeps_newest_backups(self):
        ids = [db.backup(self.config(KEEP_LOCAL=2))['backup_id'] for _ in range(3)]

        self.assertEqual([ids[2], ids[1]], self.complete())

    def test_leftover_staging_from_interrupted_run_becomes_failed_attempt(self):
        self.backups.mkdir(mode=0o700)
        (self.backups / '.staging-20260101T000000Z-000000').mkdir()
        (self.backups / '.verify-20260101T000000Z-000000').mkdir()

        report = db.backup(self.config())

        self.assertEqual('LOCAL_ONLY', report['status'])
        self.assertTrue((self.backups / 'failed-20260101T000000Z-000000').is_dir())
        self.assertFalse(list(self.backups.glob('.verify-*')))

    def test_backup_dir_inside_pgdata_is_rejected(self):
        self.server.pgdata = str(self.root)

        report = db.backup(self.config())

        self.assertEqual(('FAIL', 'config'), (report['status'], report['stage']))
        self.assertNotIn('pg_basebackup', self.server.programs())

    def test_offsite_upload_is_verified_against_remote_checksum(self):
        report = db.backup(self.config(S3_BUCKET='placeholder-bucket', S3_PREFIX='db/basebackup'))

        self.assertEqual('OFFSITE_VERIFIED', report['status'])
        self.assertEqual(0, db.exit_code(report))
        prefix = f"db/basebackup/{SYSTEM_ID}/{report['backup_id']}/"
        puts = [call[call.index('--key') + 1] for call in self.server.calls if call[2:3] == ['put-object']]
        self.assertEqual(prefix + 'SHA256SUMS', puts[-1])
        self.assertEqual(7, len(puts))
        self.assertTrue(all(obj['sse'] == 'AES256' for obj in self.server.objects.values()))
        metrics = self.metrics()
        self.assertEqual('1', metrics['failstop_db_backup_offsite_configured'])
        self.assertEqual('1', metrics['failstop_db_backup_offsite_last_success'])
        self.assertIn('failstop_db_backup_offsite_last_success_timestamp_seconds', metrics)

    def test_remote_checksum_mismatch_is_offsite_failure_not_success(self):
        self.server.corrupt_remote = True

        report = db.backup(self.config(S3_BUCKET='placeholder-bucket', S3_PREFIX='db'))

        self.assertEqual('OFFSITE_FAILED', report['status'])
        self.assertEqual(2, db.exit_code(report))
        self.assertIn('SHA-256', report['offsite']['error'])
        self.assertEqual([report['backup_id']], self.complete())
        metrics = self.metrics()
        self.assertEqual('1', metrics['failstop_db_backup_last_run_success'])
        self.assertEqual('0', metrics['failstop_db_backup_offsite_last_success'])
        self.assertNotIn('failstop_db_backup_offsite_last_success_timestamp_seconds', metrics)

    def test_legacy_metric_names_follow_the_new_metrics(self):
        # 학교 일일 보고와 MSP DB 대시보드가 조회하는 옛 이름이 새 지표와 같은 값을 가져야 한다.
        db.backup(self.config())
        metrics = self.metrics()
        self.assertEqual('1', metrics['failstop_backup_last_run_success'])
        self.assertEqual(metrics['failstop_db_backup_last_success_timestamp_seconds'],
                         metrics['failstop_backup_last_success_timestamp_seconds'])
        succeeded_at = metrics['failstop_backup_last_success_timestamp_seconds']

        self.server.verify_ok = False
        db.backup(self.config())
        metrics = self.metrics()
        self.assertEqual('0', metrics['failstop_backup_last_run_success'])
        self.assertEqual(succeeded_at, metrics['failstop_backup_last_success_timestamp_seconds'])

        self.server.recovery = 't'
        db.backup(self.config())
        metrics = self.metrics()
        self.assertNotIn('failstop_backup_last_run_success', metrics)
        self.assertEqual(succeeded_at, metrics['failstop_backup_last_success_timestamp_seconds'])

    def test_sigterm_during_basebackup_records_failed_run_and_keeps_previous_backup(self):
        # systemd가 TimeoutStartSec을 넘기면 서비스에 SIGTERM을 보낸다. 실제 신호와 실제 자식 프로세스로 확인한다.
        previous = db.backup(self.config(KEEP_LOCAL=1))['backup_id']
        self.addCleanup(signal.signal, signal.SIGTERM, signal.getsignal(signal.SIGTERM))
        db.stop_on_sigterm()

        def slow_basebackup(args, stage, timeout=db.SHORT_TIMEOUT, env=None):
            if Path(str(args[0])).name != 'pg_basebackup':
                return self.server(args, stage, timeout, env)
            # 자식이 부모인 이 테스트 프로세스에 SIGTERM을 보내고 오래 걸리는 백업처럼 기다린다.
            return REAL_RUN(['sh', '-c', 'kill -TERM "$PPID"; exec sleep 10'], stage, 60)

        with mock.patch.object(db, 'run', slow_basebackup):
            report = db.backup(self.config(KEEP_LOCAL=1))

        self.assertEqual(('FAIL', 'basebackup'), (report['status'], report['stage']))
        self.assertIn('SIGTERM', report['error'])
        self.assertEqual(1, db.exit_code(report))
        self.assertEqual([previous], self.complete())
        self.assertTrue((self.backups / ('failed-' + report['backup_id'])).is_dir())
        self.assertFalse(list(self.backups.glob('.staging-*')))
        metrics = self.metrics()
        self.assertEqual('0', metrics['failstop_db_backup_last_run_success'])
        self.assertEqual('0', metrics['failstop_backup_last_run_success'])
        self.assertNotIn('failstop_db_backup_last_verify_success', metrics)
        self.assertEqual('1', metrics['failstop_db_backup_local_backups'])
        self.assertIs(signal.SIG_IGN, signal.getsignal(signal.SIGTERM))

    def test_stop_request_during_upload_is_run_failure_not_offsite_failure(self):
        older = db.backup(self.config())['backup_id']
        original = self.server.aws

        def interrupted_aws(args):
            if args[2] == 'put-object':
                raise db.BackupInterrupted('offsite', 'aws 실행 중 SIGTERM을 받아 중단했다.')
            return original(args)

        self.server.aws = interrupted_aws
        report = db.backup(self.config(KEEP_LOCAL=1, S3_BUCKET='placeholder-bucket', S3_PREFIX='db'))

        self.assertEqual(('FAIL', 'offsite'), (report['status'], report['stage']))
        # 검증까지 끝난 백업은 완료로 남고, 중단된 실행은 오래된 백업을 지우지 않는다.
        self.assertEqual(sorted([older, report['backup_id']], reverse=True), self.complete())
        self.assertFalse((self.backups / report['backup_id'] / 'offsite.json').exists())
        metrics = self.metrics()
        self.assertEqual('0', metrics['failstop_db_backup_last_run_success'])
        self.assertEqual('1', metrics['failstop_db_backup_last_verify_success'])
        self.assertNotIn('failstop_db_backup_offsite_last_success', metrics)

    def test_partial_or_invalid_offsite_settings_are_rejected(self):
        for values in ({'S3_BUCKET': 'placeholder-bucket'}, {'S3_PREFIX': 'db'},
                       {'S3_BUCKET': 'Bad_Bucket', 'S3_PREFIX': 'db'},
                       {'S3_BUCKET': 'placeholder-bucket', 'S3_PREFIX': 'db/../x'}):
            with self.subTest(values=values), self.assertRaises(db.BackupError):
                self.config(**values)

    def test_config_file_rejects_unknown_keys(self):
        path = self.root / 'db-backup.conf'
        path.write_text('BACKUP_ROLE=primary\nBACKUP_ROLES=standby\n')
        with self.assertRaises(db.BackupError):
            db.load_config(path, require_root_owned=False)
        path.write_text('# 주석\nBACKUP_ROLE=any\nKEEP_LOCAL=5\n')
        self.assertEqual(('any', 5), (db.load_config(path, require_root_owned=False)['role'],
                                      db.load_config(path, require_root_owned=False)['keep_local']))

    @unittest.skipUnless(os.geteuid() == 0, 'root가 만든 파일로 소유권·권한 검사를 확인한다.')
    def test_config_file_must_not_be_group_writable(self):
        path = self.root / 'db-backup.conf'
        path.write_text('BACKUP_ROLE=primary\n')
        path.chmod(0o664)
        with self.assertRaises(db.BackupError):
            db.load_config(path)
        path.chmod(0o640)
        self.assertEqual('primary', db.load_config(path)['role'])

    def test_metrics_replace_is_atomic_and_leaves_no_partial_file(self):
        db.write_metrics(self.textfile, 'failstop_db_backup_local_backups 1\n')
        replaced = []
        original_replace = os.replace

        def record_replace(source, target):
            replaced.append((Path(source).parent, Path(target)))
            original_replace(source, target)

        with mock.patch.object(db.os, 'replace', side_effect=record_replace):
            db.write_metrics(self.textfile, 'failstop_db_backup_local_backups 2\n')
        self.assertEqual([(self.textfile, self.textfile / 'failstop_db_backup.prom')], replaced)

        with mock.patch.object(db.os, 'fsync', side_effect=OSError('disk full')), self.assertRaises(OSError):
            db.write_metrics(self.textfile, 'failstop_db_backup_local_backups 3\n')
        self.assertEqual('failstop_db_backup_local_backups 2\n',
                         (self.textfile / 'failstop_db_backup.prom').read_text())
        self.assertEqual(['failstop_db_backup.prom'], [path.name for path in self.textfile.iterdir()])

    def test_concurrent_run_is_rejected_without_touching_metrics(self):
        self.backups.mkdir(mode=0o700)
        holder = db.acquire_lock(self.backups)
        self.addCleanup(holder.close)

        with self.assertRaises(db.BackupBusy):
            db.backup(self.config())
        self.assertEqual([], self.server.calls)
        self.assertFalse((self.textfile / 'failstop_db_backup.prom').exists())

    def test_archive_with_parent_path_is_refused(self):
        archive = self.root / 'evil.tar.gz'
        with tarfile.open(archive, 'w:gz') as tar:
            add_file(tar, '../escape', b'x')
        with self.assertRaises(db.BackupError):
            db.extract(archive, self.root / 'target')
        self.assertFalse((self.root / 'escape').exists())


if __name__ == '__main__':
    unittest.main()
