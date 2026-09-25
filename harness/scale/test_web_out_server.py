"""web_out_server.py 시험(ADR 0034 S7, 마켓분할_리더_S7_설계.md 4-13 웹 관찰 배선).

정적 export 의 경로 해석이 chaos-web 의 nginx 규칙(try_files $uri $uri.html $uri/)과 같은지, 루트 밖 경로를 막는지, 루프백 서버가
거래 화면·자산·RSC 페이로드·404 를 내는지 본다. 임시 폴더에 산출물 모양만 만들어 돌린다(웹 빌드 없음).

돌리는 법:
  python -B -m unittest discover -s harness/scale -p "test_*.py"
"""
import io
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import web_out_server as w  # noqa: E402

FILES = {
    'index.html': '<html>home</html>',
    '404.html': '<html>missing</html>',
    'exchange.html': '<html>exchange list</html>',
    'exchange/KRW-BTC.html': '<html>btc</html>',
    'exchange/KRW-BTC.txt': 'rsc payload',
    '_next/static/chunks/app.js': 'console.log(1)',
    'admin/index.html': '<html>admin index</html>',
}


def make_tree(root):
    for rel, body in FILES.items():
        path = os.path.join(root, *rel.split('/'))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf-8') as fh:
            fh.write(body)


class ResolveTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = os.path.join(self.tmp.name, 'out')
        os.makedirs(self.root)
        make_tree(self.root)
        with open(os.path.join(self.tmp.name, 'secret.txt'), 'w', encoding='utf-8') as fh:
            fh.write('outside')

    def tearDown(self):
        self.tmp.cleanup()

    def rel(self, raw):
        path = w.resolve(self.root, raw)
        return None if path is None else os.path.relpath(path, os.path.realpath(self.root)).replace(os.sep, '/')

    def test_route_without_extension_is_the_html_file(self):
        self.assertEqual('exchange/KRW-BTC.html', self.rel('/exchange/KRW-BTC'))
        self.assertEqual('exchange.html', self.rel('/exchange'), '폴더와 같은 이름의 .html 이 폴더 index 보다 먼저다')

    def test_query_does_not_pick_the_file(self):
        self.assertEqual('exchange/KRW-BTC.html', self.rel('/exchange/KRW-BTC?market=KRW-ETH'))
        self.assertEqual('exchange/KRW-BTC.txt', self.rel('/exchange/KRW-BTC.txt?_rsc=abc'))

    def test_root_and_folder_index(self):
        self.assertEqual('index.html', self.rel('/'))
        self.assertEqual('admin/index.html', self.rel('/admin'))
        self.assertEqual('admin/index.html', self.rel('/admin/'))

    def test_static_asset(self):
        self.assertEqual('_next/static/chunks/app.js', self.rel('/_next/static/chunks/app.js'))

    def test_paths_outside_the_root_are_refused(self):
        for raw in ('/../secret.txt', '/%2e%2e/secret.txt', '/exchange/../../secret.txt', '/..%5csecret.txt',
                    '/%00index.html'):
            self.assertIsNone(w.resolve(self.root, raw), raw)

    def test_missing_is_none(self):
        self.assertIsNone(self.rel('/exchange/KRW-NOPE'))
        self.assertIsNone(self.rel('/nope/'))

    def test_content_types(self):
        self.assertEqual('text/html; charset=utf-8', w.content_type('a/b.html'))
        self.assertEqual('text/plain; charset=utf-8', w.content_type('a/b.txt'), 'export 의 RSC 페이로드')
        self.assertEqual('application/javascript; charset=utf-8', w.content_type('a/b.JS'))
        self.assertEqual('font/woff2', w.content_type('a/b.woff2'))
        self.assertEqual('application/octet-stream', w.content_type('a/b.unknown'))


class ServeTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        make_tree(self.tmp.name)
        quiet = mock.patch.object(w.Handler, 'log_message', lambda *a, **k: None)
        quiet.start()
        self.addCleanup(quiet.stop)
        self.server = w.OutServer(('127.0.0.1', 0), self.tmp.name)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:%d' % self.server.server_address[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)
        self.tmp.cleanup()

    def get(self, path, method='GET'):
        req = urllib.request.Request(self.base + path, method=method)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, r.headers.get('Content-Type'), r.read()
        except urllib.error.HTTPError as e:
            with e:
                return e.code, e.headers.get('Content-Type'), e.read()

    def test_trade_screen_asset_and_rsc_payload(self):
        self.assertEqual((200, 'text/html; charset=utf-8', b'<html>btc</html>'), self.get('/exchange/KRW-BTC'))
        self.assertEqual(200, self.get('/_next/static/chunks/app.js')[0])
        self.assertEqual((200, 'text/plain; charset=utf-8', b'rsc payload'), self.get('/exchange/KRW-BTC.txt?_rsc=1'))

    def test_missing_route_is_404_with_the_404_page(self):
        self.assertEqual((404, 'text/html; charset=utf-8', b'<html>missing</html>'), self.get('/exchange/KRW-NOPE'))
        self.assertEqual(404, self.get('/../secret.txt')[0])

    def test_head_has_no_body(self):
        status, ctype, body = self.get('/exchange/KRW-BTC', method='HEAD')
        self.assertEqual((200, 'text/html; charset=utf-8', b''), (status, ctype, body))

    def test_port_held_by_another_socket_fails_to_bind(self):
        with self.assertRaises(OSError):
            w.OutServer(('127.0.0.1', self.server.server_address[1]), self.tmp.name)

    def test_main_refuses_a_root_without_index(self):
        with tempfile.TemporaryDirectory() as empty, mock.patch('sys.stderr', new_callable=io.StringIO) as err:
            self.assertEqual(2, w.main(['--root', empty, '--port', '0']))
        self.assertIn('index.html', err.getvalue())


if __name__ == '__main__':
    unittest.main()
