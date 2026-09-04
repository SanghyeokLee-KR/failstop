# -*- coding: utf-8 -*-
"""컨테이너 자원 수집기.

cAdvisor 가 Docker Desktop 에서 컨테이너 이름을 내지 못한다(cgroup 단위만 보인다).
`docker stats` 는 이름·CPU·메모리·네트워크·디스크를 한 번에 주므로 그것을 Prometheus
형식으로 옮긴다. 5초마다 한 번만 실행하고 그 사이 요청에는 캐시를 돌려준다.
`docker stats --no-stream` 이 컨테이너 수에 비례해 1~2초 걸리기 때문이다.
"""
import json
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

INTERVAL = 5.0
_lock = threading.Lock()
_cache = {"text": "", "at": 0.0}

UNIT = {"B": 1, "KB": 1e3, "KIB": 1024, "MB": 1e6, "MIB": 1024 ** 2,
        "GB": 1e9, "GIB": 1024 ** 3, "TB": 1e12, "TIB": 1024 ** 4}


def to_bytes(s):
    m = re.match(r"^\s*([0-9.]+)\s*([A-Za-z]+)\s*$", s or "")
    if not m:
        return 0.0
    return float(m.group(1)) * UNIT.get(m.group(2).upper(), 1)


def collect():
    fmt = "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.BlockIO}}\t{{.PIDs}}"
    try:
        out = subprocess.run(["docker", "stats", "--no-stream", "--format", fmt],
                             capture_output=True, text=True, timeout=20).stdout
    except Exception:
        return "# docker stats 실패\n"

    lines = ["# HELP container_cpu_percent 컨테이너 CPU 사용률(호스트 코어 1개=100)",
             "# TYPE container_cpu_percent gauge",
             "# HELP container_mem_bytes 컨테이너 메모리 사용량",
             "# TYPE container_mem_bytes gauge",
             "# HELP container_mem_limit_bytes 컨테이너 메모리 상한",
             "# TYPE container_mem_limit_bytes gauge",
             "# HELP container_net_rx_bytes 네트워크 수신 누계",
             "# TYPE container_net_rx_bytes counter",
             "# HELP container_net_tx_bytes 네트워크 송신 누계",
             "# TYPE container_net_tx_bytes counter",
             "# HELP container_pids 컨테이너 프로세스·스레드 수",
             "# TYPE container_pids gauge"]
    for row in out.strip().split("\n"):
        p = row.split("\t")
        if len(p) < 7:
            continue
        name = p[0].strip()
        if not name:
            continue
        cpu = float(p[1].replace("%", "") or 0)
        mem_used, _, mem_lim = (p[2] + " / 0B").partition(" / ")
        net_rx, _, net_tx = (p[4] + " / 0B").partition(" / ")
        lines.append('container_cpu_percent{name="%s"} %g' % (name, cpu))
        lines.append('container_mem_bytes{name="%s"} %g' % (name, to_bytes(mem_used)))
        lines.append('container_mem_limit_bytes{name="%s"} %g' % (name, to_bytes(mem_lim)))
        lines.append('container_net_rx_bytes{name="%s"} %g' % (name, to_bytes(net_rx)))
        lines.append('container_net_tx_bytes{name="%s"} %g' % (name, to_bytes(net_tx)))
        try:
            lines.append('container_pids{name="%s"} %g' % (name, float(p[6])))
        except ValueError:
            pass
    return "\n".join(lines) + "\n"


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        now = time.time()
        with _lock:
            if now - _cache["at"] > INTERVAL:
                _cache["text"] = collect()
                _cache["at"] = now
            body = _cache["text"]
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 58089), H).serve_forever()
