import argparse
import collections
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock

if os.name != 'posix':
    raise unittest.SkipTest('backup-restore-check.py는 pwd 모듈을 쓰는 Linux 전용 도구다.')

SPEC = importlib.util.spec_from_file_location('restore_check', Path(__file__).resolve().parents[1] / 'backup-restore-check.py')
rc = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rc)

GIB = 1024 ** 3
Usage = collections.namedtuple('Usage', 'total used free')
COUNTS_BEFORE = {'commands': 10, 'events': 20, 'trades': 5, 'ledger_entries': 40}
COUNTS_AFTER = {'commands': 12, 'events': 24, 'trades': 6, 'ledger_entries': 48}
INVARIANTS = 'code,check_name,severity,scope,violated_rows,verdict\n' + ''.join(
    f'C{index:02d},CHECK,CRITICAL,ALWAYS,0,PASS\n' for index in range(1, 31))


def add_file(tar, name, data):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    tar.addfile(info, io.BytesIO(data))


class FakeCluster:
    """pg_verifybackup·pg_ctl·psql 호출을 대신한다. 시작하면 postmaster.pid를 만들고 멈추면 지운다."""

    def __init__(self, restored):
        self.restored = restored
        self.calls = []

    def __call__(self, args, **kw):
        self.calls.append(args)
        program = args[0]
        if program == 'pg_verifybackup':
            data = Path(args[-1])
            assert (data / 'PG_VERSION').is_file() and (data / 'pg_wal' / 'segment').is_file()
            return 'backup successfully verified'
        if program == 'pg_ctl':
            data = Path(args[args.index('-D') + 1])
            if args[-1] == 'start':
                assert "listen_addresses = ''" in (data / 'postgresql.conf').read_text()
                (data / 'postmaster.pid').write_text('1')
            else:
                (data / 'postmaster.pid').unlink()
            return ''
        if program == 'psql':
            assert '-h' in args and '55432' in args, '운영 DB 소켓이 아니라 시험 클러스터에만 접속해야 한다.'
            return json.dumps(self.restored) if '-c' in args else INVARIANTS.strip()
        raise AssertionError(args)


class BasebackupRestoreCheckTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / '20260923T184000Z-a1b2c3'
        self.source.mkdir()
        with tarfile.open(self.source / 'base.tar.gz', 'w:gz') as tar:
            add_file(tar, 'PG_VERSION', b'17\n')
            add_file(tar, 'postgresql.conf', b"include = '/etc/postgresql/exchange.conf'\n")
            add_file(tar, 'standby.signal', b'')
        with tarfile.open(self.source / 'pg_wal.tar.gz', 'w:gz') as tar:
            add_file(tar, 'segment', b'\0' * 16)
        (self.source / 'backup_manifest').write_text(json.dumps({'Files': [{'Path': 'PG_VERSION', 'Size': 3}]}))
        (self.source / 'metadata.json').write_text(json.dumps({
            'backup_id': self.source.name, 'counts_before': COUNTS_BEFORE, 'counts_after': COUNTS_AFTER}))
        self.write_sums()
        self.invariants = self.root / 'summary.sql'
        self.invariants.write_text('SELECT 1;')
        self.base = self.root / 'checks'
        self.base.mkdir()
        disk = mock.patch.object(rc.shutil, 'disk_usage', return_value=Usage(50 * GIB, 10 * GIB, 40 * GIB))
        disk.start()
        self.addCleanup(disk.stop)

    def write_sums(self):
        names = sorted(path.name for path in self.source.iterdir() if path.name != 'SHA256SUMS')
        (self.source / 'SHA256SUMS').write_text(''.join(
            f'{hashlib.sha256((self.source / name).read_bytes()).hexdigest()}  {name}\n' for name in names))

    def check(self, restored, keep=False):
        cluster = FakeCluster(restored)
        args = argparse.Namespace(basebackup=self.source, invariants=self.invariants, keep_restore_data=keep)
        with mock.patch.object(rc, 'run', cluster):
            try:
                rc.check_basebackup(args, self.base)
            finally:
                self.result = json.loads(next(self.base.glob('*/result.json')).read_text())
        return cluster

    def test_restore_inside_backup_window_passes_and_removes_restored_copy(self):
        cluster = self.check({'commands': 11, 'events': 22, 'trades': 6, 'ledger_entries': 44})

        self.assertEqual('PASS', self.result['status'])
        self.assertTrue(self.result['counts_within_backup_window'])
        self.assertEqual(30, self.result['invariant_count'])
        self.assertTrue(self.result['test_cluster_stopped'])
        self.assertTrue(self.result['restore_data_removed'])
        self.assertFalse(self.result['tcp_listen'])
        self.assertFalse(self.result['external_copy'])
        self.assertEqual(['pg_verifybackup', 'pg_ctl', 'psql', 'psql', 'pg_ctl'], [call[0] for call in cluster.calls])
        start = next(call for call in cluster.calls if call[-1] == 'start')
        self.assertIn("listen_addresses=''", start[start.index('-o') + 1])
        self.assertFalse(list(self.base.glob('*/restore-data')))

    def test_restored_config_opens_only_the_socket(self):
        self.check({'commands': 11, 'events': 22, 'trades': 6, 'ledger_entries': 44}, keep=True)

        data = next(self.base.glob('*/restore-data'))
        config = (data / 'postgresql.conf').read_text()
        self.assertIn("listen_addresses = ''", config)
        self.assertNotIn('include', config)
        self.assertEqual('local all all peer\n', (data / 'pg_hba.conf').read_text())
        self.assertFalse((data / 'standby.signal').exists())

    def test_counts_outside_backup_window_fail(self):
        with self.assertRaises(RuntimeError):
            self.check({'commands': 11, 'events': 22, 'trades': 7, 'ledger_entries': 44})

        self.assertEqual('FAIL', self.result['status'])
        self.assertFalse(self.result['counts_within_backup_window'])
        self.assertTrue(self.result['test_cluster_stopped'])
        self.assertTrue(list(self.base.glob('*/restore-data')), '실패한 복원본은 원인 확인을 위해 남긴다.')

    def test_empty_trading_data_is_not_a_recovery_proof(self):
        with self.assertRaises(RuntimeError):
            self.check({'commands': 0, 'events': 0, 'trades': 0, 'ledger_entries': 0})
        self.assertEqual('FAIL', self.result['status'])

    def test_changed_archive_is_rejected_before_extraction(self):
        with (self.source / 'base.tar.gz').open('ab') as archive:
            archive.write(b'tampered')

        with self.assertRaises(RuntimeError):
            self.check(COUNTS_BEFORE)
        self.assertIn('SHA-256', self.result['error'])
        self.assertFalse(list(self.base.glob('*/restore-data')))

    def test_file_missing_from_checksum_list_is_rejected(self):
        (self.source / 'extra.bin').write_bytes(b'x')

        with self.assertRaises(RuntimeError):
            rc.verify_checksums(self.source)


if __name__ == '__main__':
    unittest.main()
