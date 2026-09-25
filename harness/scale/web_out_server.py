"""정적 export(web/out)를 루프백에서 서빙한다(ADR 0034 S7, docs/reference/architecture/마켓분할_리더_S7_설계.md 4-13 웹 관찰).

S7 관문의 W6·W7 웹 관찰은 p0 소유 노드에 직결하도록 빌드한 웹을 띄워 두고 s6-web-resync.mjs 로 거래 화면을 연다. 웹은
web/next.config.ts 가 output: "export" 라서 `next start` 로 띄울 수 없다. Next 15.5 는 그 설정에서 next start 를 예외로 끝낸다.
그래서 빌드 산출물 web/out 을 이 서버가 낸다.

경로 해석은 하네스 chaos-web 의 nginx 규칙(harness/nginx/web.conf 의 try_files $uri $uri.html $uri/)과 같다. 파일 그대로,
파일.html, 폴더/index.html 순서로 찾고 없으면 404.html 을 404 로 낸다. /exchange/KRW-BTC 는 exchange/KRW-BTC.html 이다. 질의
문자열(`?_rsc=` 등)은 파일 선택에 쓰지 않는다. API·WS 는 프록시하지 않는다. 웹은 빌드 때 받은 NEXT_PUBLIC_API_BASE·
NEXT_PUBLIC_WS_BASE 로 노드에 직접 붙는다. 루트 밖을 가리키는 경로(.. 등)는 404 다.

루프백에만 묶는다. 127.0.0.1 과, 되면 ::1 에도 묶어 브라우저가 localhost 를 어느 쪽으로 풀든 곧바로 붙는다. 포트를 다른 소켓이
쥐고 있으면 묶기에 실패하고 종료코드 2 로 끝난다(주소 재사용을 켜지 않는다).

사용: python -B harness/scale/web_out_server.py --root web/out --port 3000
"""
import argparse
import json
import os
import socket
import socketserver
import sys
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit

# 산출물에 나오는 확장자의 형식. 모르는 확장자는 바이트로 낸다. .txt 는 정적 export 의 RSC 페이로드이고 Next 는 export 에서
# text/plain 을 받는다.
TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.mjs': 'application/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json',
    '.map': 'application/json',
    '.txt': 'text/plain; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.webp': 'image/webp',
    '.ico': 'image/x-icon',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
    '.ttf': 'font/ttf',
    '.otf': 'font/otf',
}


def content_type(path):
    return TYPES.get(os.path.splitext(path)[1].lower(), 'application/octet-stream')


def _inside(base, path):
    base_n = os.path.normcase(base)
    path_n = os.path.normcase(path)
    return path_n == base_n or path_n.startswith(base_n.rstrip(os.sep) + os.sep)


def resolve(root, raw_path):
    """요청 경로를 root 안의 파일 경로로 푼다. 없거나 root 밖이면 None 이다."""
    path = unquote(urlsplit(raw_path).path)
    if '\x00' in path or '\\' in path:
        return None
    base = os.path.realpath(root)
    rel = path.lstrip('/')
    target = os.path.realpath(os.path.join(base, *[p for p in rel.split('/') if p]))
    if not _inside(base, target):
        return None
    candidates = []
    if rel and not rel.endswith('/'):
        candidates += [target, target + '.html']
    candidates.append(os.path.join(target, 'index.html'))
    for c in candidates:
        real = os.path.realpath(c)
        if _inside(base, real) and os.path.isfile(real):
            return real
    return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'web-out-server'

    def do_GET(self):
        self._serve(body=True)

    def do_HEAD(self):
        self._serve(body=False)

    def _serve(self, body):
        root = self.server.root
        path = resolve(root, self.path)
        status = HTTPStatus.OK
        if path is None:
            status = HTTPStatus.NOT_FOUND
            fallback = os.path.join(root, '404.html')
            path = fallback if os.path.isfile(fallback) else None
        if path is None:
            data, ctype = b'not found', 'text/plain; charset=utf-8'
        else:
            with open(path, 'rb') as fh:
                data = fh.read()
            ctype = content_type(path)
        self.send_response(status)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(data)))
        # 관문마다 다시 빌드하므로 브라우저가 옛 산출물을 쓰지 않게 한다(chaos-web 의 HTML no-cache 와 같은 뜻).
        self.send_header('Cache-Control', 'no-cache')
        self.end_headers()
        if body:
            self.wfile.write(data)

    def log_message(self, fmt, *args):
        sys.stderr.write('%s %s\n' % (self.log_date_time_string(), fmt % args))


class OutServer(ThreadingHTTPServer):
    daemon_threads = True
    # 주소 재사용을 켜면 윈도에서 다른 프로세스가 쥔 포트에도 묶여 요청이 어느 쪽으로 갈지 모른다.
    allow_reuse_address = False

    def __init__(self, address, root, family=socket.AF_INET):
        self.address_family = family
        self.root = os.path.realpath(root)
        super().__init__(address, Handler)

    def server_bind(self):
        # HTTPServer.server_bind 는 이름을 역방향 조회(getfqdn)해 루프백에서도 느릴 수 있다. 이름은 쓰지 않으므로 묶기만 한다.
        socketserver.TCPServer.server_bind(self)
        self.server_name = str(self.server_address[0])
        self.server_port = self.server_address[1]


def serve(root, port, ipv4_only=False):
    """루프백 서버를 세운다. 첫 서버는 127.0.0.1 이고, ::1 은 묶을 수 있을 때만 더한다."""
    servers = [OutServer(('127.0.0.1', port), root)]
    if not ipv4_only and socket.has_ipv6:
        try:
            servers.append(OutServer(('::1', servers[0].server_address[1]), root, socket.AF_INET6))
        except OSError as e:
            sys.stderr.write('::1 에 묶지 못해 127.0.0.1 만 쓴다: %s\n' % e)
    return servers


def main(argv=None):
    ap = argparse.ArgumentParser(description='web/out 정적 export 를 루프백에서 서빙한다(S7 관문 웹 관찰).')
    ap.add_argument('--root', required=True, help='정적 export 폴더(web/out)')
    ap.add_argument('--port', type=int, default=3000)
    ap.add_argument('--ipv4-only', action='store_true')
    a = ap.parse_args(argv)
    root = os.path.realpath(a.root)
    if not os.path.isfile(os.path.join(root, 'index.html')):
        sys.stderr.write('index.html 이 없다: %s (먼저 npm --prefix web run build)\n' % root)
        return 2
    try:
        servers = serve(root, a.port, a.ipv4_only)
    except OSError as e:
        sys.stderr.write('127.0.0.1:%d 에 묶지 못했다: %s\n' % (a.port, e))
        return 2
    for s in servers[1:]:
        threading.Thread(target=s.serve_forever, daemon=True).start()
    print(json.dumps({'root': root, 'port': servers[0].server_address[1],
                      'listen': [s.server_address[0] for s in servers]}), flush=True)
    try:
        servers[0].serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        for s in servers:
            s.server_close()
    return 0


if __name__ == '__main__':
    sys.exit(main())
