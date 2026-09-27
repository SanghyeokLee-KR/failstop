"""컨테이너 자원 exporter. 도커 API 를 직접 읽는다.

cAdvisor 를 쓰지 않는 이유는 실측이다. Docker Desktop(WSL2) 에서 cAdvisor 가 컨테이너
메타데이터를 못 읽는다. 2026-09-09 실측에서 `Failed to create existing container` 가
컨테이너마다 나고 `name` 라벨이 붙은 시계열이 0건이었다. 마운트를 표준 셋(/rootfs,
/var/run, /sys, /var/lib/docker, /dev/disk)으로 바꿔도 같았다. 리눅스 호스트에서는
동작하지만 이 프로젝트의 검증면이 Windows 라 쓸 수 없다.

도커 API 의 stats 스트림은 그 계층을 지나지 않고 데몬이 직접 답한다. 그래서 호스트
종류를 타지 않는다. `docker stats` 가 쓰는 것과 같은 원천이다.

CPU 계산은 `docker stats` 와 같은 식이다. 델타 기반이고 코어 수를 곱한다. 곧 100 이
1코어다. 절대값이며 컨테이너 캡 대비 비율이 아니다. 캡 대비 비율이 필요한 화면(로컬 MSP 03 보드)은
container_cpu_limit_cores 로 나눈다. 부하 측정 스크립트(harness/scale)는 절대값을 그대로 쓴다.

델타 창은 `docker stats` 의 1초가 아니라 이 수집기의 한 주기(쉬는 5초 + 한 바퀴)다. 도커 API 의
stream=false 는 컨테이너마다 두 번째 표본을 1~2초 기다려, 컨테이너가 29개인 시연 스택에서 한 바퀴가
약 60초였다. 그래서 새로 뜬 app-3 이 1분 가까이 늦게 보였고 값도 1분에 한 번 1초 창으로만 바뀌었다
(2026-09-27 시연 녹화 8회차). 지금은 one-shot 으로 기다리지 않고 한 번 읽고, 직전 주기에 읽은 누적값과의
차로 CPU 를 낸다. 컨테이너를 처음 본 주기(새 컨테이너, 수집기 재시작)에는 CPU 를 내지 않는다.
"""
import json
import socket
import time
import threading
import http.server
import http.client

SOCK = '/var/run/docker.sock'
PORT = 9200
INTERVAL = 5

_lock = threading.Lock()
_text = 'failstop_container_collection_success 0\n'
_last_success = 0


def _api(path):
    """도커 유닉스 소켓에 HTTP 요청 하나를 보낸다. 라이브러리를 쓰지 않는다."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(10)
        connection.connect(SOCK)
        connection.sendall(('GET %s HTTP/1.1\r\nHost: docker\r\nConnection: close\r\n\r\n' % path).encode())
        with http.client.HTTPResponse(connection) as response:
            response.begin()
            if response.status != 200:
                raise RuntimeError('Docker API collection failed')
            body = response.read(8 * 1024 * 1024 + 1)
            if len(body) > 8 * 1024 * 1024:
                raise RuntimeError('Docker API response exceeded limit')
    return json.loads(body)


def _cpu_percent(st, prev):
    """docker stats 와 같은 식. 코어 수를 곱하므로 100 이 1코어다. prev 는 같은 컨테이너의 직전 주기
    cpu_stats 다(one-shot 응답의 precpu_stats 는 비어 있다). 없으면 값을 내지 않는다."""
    c, p = st.get('cpu_stats', {}), prev or {}
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


def _cpu_limit(info, st):
    """컨테이너에 준 코어 수. --cpus(NanoCpus), CPU 할당량(CpuQuota/CpuPeriod), cpuset 순으로 보고,
    상한이 없으면 컨테이너가 보는 호스트 코어 수다. container_cpu_percent 를 이 값으로 나누면 AWS 호스트
    CPU 처럼 받은 코어 전체가 100 이 된다(2코어 DB 가 200% 로 보이지 않는다)."""
    host = info.get('HostConfig') or {}
    nano = host.get('NanoCpus') or 0
    if nano > 0:
        return nano / 1e9
    quota, period = host.get('CpuQuota') or 0, host.get('CpuPeriod') or 0
    if quota > 0 and period > 0:
        return quota / period
    cpuset = host.get('CpusetCpus') or ''
    if cpuset:
        count = 0
        for part in cpuset.split(','):
            first, _, last = part.partition('-')
            count += int(last) - int(first) + 1 if last else 1
        return count
    c = st.get('cpu_stats', {})
    return c.get('online_cpus') or len(c.get('cpu_usage', {}).get('percpu_usage') or []) or None


_prev_cpu = {}   # 컨테이너 id → 직전 주기 cpu_stats(수집 스레드만 쓴다)


def collect():
    rows = []
    seen = {}
    for c in _api('/containers/json'):
        name = (c.get('Names') or ['/?'])[0].lstrip('/')
        try:
            st = _api('/containers/%s/stats?stream=false&one-shot=true' % c['Id'])
            info = _api('/containers/%s/json' % c['Id'])
        except Exception as error:
            # 일부 컨테이너를 빠뜨린 합계를 정상 사용량으로 게시하지 않는다.
            raise RuntimeError('container stats unavailable') from error
        cpu = _cpu_percent(st, _prev_cpu.get(c['Id']))
        seen[c['Id']] = st.get('cpu_stats', {})
        mem = st.get('memory_stats', {})
        used = mem.get('usage')
        # cache 는 회수 가능하다. 빼지 않으면 사용량이 실제보다 크게 보인다.
        cache = (mem.get('stats') or {}).get('inactive_file') or 0
        if used is not None:
            used = max(0, used - cache)
        nets = st.get('networks')
        rx = sum(v['rx_bytes'] for v in nets.values()) if nets and all('rx_bytes' in v for v in nets.values()) else None
        tx = sum(v['tx_bytes'] for v in nets.values()) if nets and all('tx_bytes' in v for v in nets.values()) else None
        rows.append({
            'name': name,
            'container_cpu_percent': cpu,
            'container_cpu_limit_cores': _cpu_limit(info, st),
            'container_mem_bytes': used,
            'container_mem_limit_bytes': mem.get('limit'),
            'container_net_rx_bytes': rx,
            'container_net_tx_bytes': tx,
            'container_pids': (st.get('pids_stats') or {}).get('current'),
        })
    # 한 바퀴를 다 읽은 뒤에만 바꾼다. 중간에 실패한 주기는 직전 누적값을 그대로 두어 다음 주기의 창이 길어질 뿐이다
    _prev_cpu.clear()
    _prev_cpu.update(seen)
    return rows


HELP = {
    'container_cpu_percent': '컨테이너 CPU 사용률. 100 이 1코어다(docker stats 규약)',
    'container_cpu_limit_cores': '컨테이너에 준 CPU 코어 수. 상한이 없으면 호스트 코어 수다',
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
            name = r['name'].replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
            out.append('%s{name="%s"} %g' % (metric, name, v))
    return '\n'.join(out) + '\n'


def sample():
    """실패한 수집은 오래된 값이나 0으로 바꾸지 않고 별도 상태를 낸다."""
    global _last_success
    try:
        rows = collect()
        body = render(rows)
        _last_success = time.time()
        success = 1
    except Exception:
        body = ''
        success = 0
    return (body + 'failstop_container_collection_success %d\n' % success
            + 'failstop_container_collection_last_success_seconds %g\n' % _last_success)


def loop():
    global _text
    while True:
        t = sample()
        with _lock:
            _text = t
        time.sleep(INTERVAL)


class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/metrics':
            self.send_error(404)
            return
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
