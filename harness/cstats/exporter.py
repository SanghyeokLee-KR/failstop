"""컨테이너 자원 exporter. 도커 API 를 직접 읽는다.

cAdvisor 를 쓰지 않는 이유는 실측이다. Docker Desktop(WSL2) 에서 cAdvisor 가 컨테이너
메타데이터를 못 읽는다. 2026-09-09 실측에서 `Failed to create existing container` 가
컨테이너마다 나고 `name` 라벨이 붙은 시계열이 0건이었다. 마운트를 표준 셋(/rootfs,
/var/run, /sys, /var/lib/docker, /dev/disk)으로 바꿔도 같았다. 리눅스 호스트에서는
동작하지만 이 프로젝트의 검증면이 Windows 라 쓸 수 없다.

도커 API 의 stats 스트림은 그 계층을 지나지 않고 데몬이 직접 답한다. 그래서 호스트
종류를 타지 않는다. `docker stats` 가 쓰는 것과 같은 원천이다.

CPU 계산은 `docker stats` 와 같은 식이다. 델타 기반이고 코어 수를 곱한다. 곧 100 이
1코어다. 절대값이며 컨테이너 캡 대비 비율이 아니다.
"""
import json
import socket
import time
import threading
import http.server

SOCK = '/var/run/docker.sock'
PORT = 9200
INTERVAL = 5

_lock = threading.Lock()
_text = ''


def _api(path):
    """도커 유닉스 소켓에 HTTP 요청 하나를 보낸다. 라이브러리를 쓰지 않는다."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(10)
    s.connect(SOCK)
    s.sendall(('GET %s HTTP/1.1\r\nHost: docker\r\nConnection: close\r\n\r\n' % path).encode())
    buf = b''
    while True:
        chunk = s.recv(65536)
        if not chunk:
            break
        buf += chunk
    s.close()
    head, _, body = buf.partition(b'\r\n\r\n')
    if b'Transfer-Encoding: chunked' in head:
        out, rest = b'', body
        while rest:
            ln, _, rest = rest.partition(b'\r\n')
            try:
                n = int(ln.split(b';')[0], 16)
            except ValueError:
                break
            if n == 0:
                break
            out += rest[:n]
            rest = rest[n + 2:]
        body = out
    return json.loads(body.decode('utf-8', 'replace'))


def _cpu_percent(st):
    """docker stats 와 같은 식. 코어 수를 곱하므로 100 이 1코어다."""
    c, p = st.get('cpu_stats', {}), st.get('precpu_stats', {})
    cu = c.get('cpu_usage', {}).get('total_usage')
    pu = p.get('cpu_usage', {}).get('total_usage')
    cs, ps = c.get('system_cpu_usage'), p.get('system_cpu_usage')
    if None in (cu, pu, cs, ps):
        return None
    d_c, d_s = cu - pu, cs - ps
    if d_s <= 0 or d_c < 0:
        return None
    n = c.get('online_cpus') or len(c.get('cpu_usage', {}).get('percpu_usage') or []) or 1
    return d_c / d_s * n * 100.0


def collect():
    rows = []
    for c in _api('/containers/json'):
        name = (c.get('Names') or ['/?'])[0].lstrip('/')
        try:
            st = _api('/containers/%s/stats?stream=false' % c['Id'])
        except Exception:
            continue
        cpu = _cpu_percent(st)
        mem = st.get('memory_stats', {})
        used = mem.get('usage')
        # cache 는 회수 가능하다. 빼지 않으면 사용량이 실제보다 크게 보인다.
        cache = (mem.get('stats') or {}).get('inactive_file') or 0
        if used is not None:
            used = max(0, used - cache)
        nets = st.get('networks') or {}
        rx = sum(v.get('rx_bytes', 0) for v in nets.values())
        tx = sum(v.get('tx_bytes', 0) for v in nets.values())
        rows.append({
            'name': name,
            'container_cpu_percent': cpu,
            'container_mem_bytes': used,
            'container_mem_limit_bytes': mem.get('limit'),
            'container_net_rx_bytes': rx,
            'container_net_tx_bytes': tx,
            'container_pids': (st.get('pids_stats') or {}).get('current'),
        })
    return rows


HELP = {
    'container_cpu_percent': '컨테이너 CPU 사용률. 100 이 1코어다(docker stats 규약)',
    'container_mem_bytes': '컨테이너 메모리 사용량. inactive_file 을 뺀 값이다',
    'container_mem_limit_bytes': '컨테이너 메모리 상한',
    'container_net_rx_bytes': '수신 누적 바이트',
    'container_net_tx_bytes': '송신 누적 바이트',
    'container_pids': '프로세스 수',
}
TYPE = {
    'container_net_rx_bytes': 'counter',
    'container_net_tx_bytes': 'counter',
}


def render(rows):
    out = []
    for metric, help_text in HELP.items():
        out.append('# HELP %s %s' % (metric, help_text))
        out.append('# TYPE %s %s' % (metric, TYPE.get(metric, 'gauge')))
        for r in rows:
            v = r.get(metric)
            if v is None:
                continue
            out.append('%s{name="%s"} %g' % (metric, r['name'], v))
    return '\n'.join(out) + '\n'


def loop():
    global _text
    while True:
        try:
            t = render(collect())
        except Exception as e:
            t = '# collect_error %s\n' % type(e).__name__
        with _lock:
            _text = t
        time.sleep(INTERVAL)


class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with _lock:
            b = _text.encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain; version=0.0.4')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def log_message(self, *a):
        pass


if __name__ == '__main__':
    threading.Thread(target=loop, daemon=True).start()
    time.sleep(2)
    http.server.ThreadingHTTPServer(('', PORT), H).serve_forever()
