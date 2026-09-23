# 조회 부하 계단. 단계마다 조회 계층 평균 사용률과 실패 사유를 함께 본다.
import collections
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

LB = "http://localhost:58080"
PATHS = [
    "/api/v1/trades?market=KRW-BTC&count=50",
    "/api/v1/candles/1m?market=KRW-BTC&count=100",
    "/api/v1/markets",
]
BASH = "C:/Users/DEV-PC/Program Files"  # 미사용. 아래 ADM 로 대체
SC = r"C:\Users\DEV-PC\AppData\Local\Temp\claude\C--Users-DEV-PC-Desktop-----\5aae4403-49b5-429a-9f08-31993fe2481d\scratchpad"
GITBASH = "C:/Program Files/Git/bin/bash.exe"


def cap():
    p = subprocess.run([GITBASH, SC + r"\adm.sh", "GET", "/admin/v1/capacity"],
                       capture_output=True, text=True, encoding="utf-8", timeout=120)
    d = json.loads(p.stdout)
    return d["read_avg_cpu_ratio"], [a["id"] for a in d.get("advice", [])]


def run_step(vus, sec):
    stop = threading.Event()
    stat = collections.Counter()
    lock = threading.Lock()

    def worker(idx):
        i = idx
        while not stop.is_set():
            try:
                with urllib.request.urlopen(LB + PATHS[i % len(PATHS)], timeout=8) as r:
                    r.read()
                    key = str(r.status)
            except urllib.error.HTTPError as e:
                key = f"http{e.code}"
            except Exception as e:
                key = type(e).__name__
            with lock:
                stat[key] += 1
            i += 1

    ts = [threading.Thread(target=worker, args=(k,), daemon=True) for k in range(vus)]
    t0 = time.time()
    for t in ts:
        t.start()
    # 초반 10초는 예열이라 빼고 잰다
    time.sleep(min(10, sec / 3))
    mid = []
    end = t0 + sec
    while time.time() < end:
        c, adv = cap()
        mid.append(c)
        time.sleep(6)
    stop.set()
    el = time.time() - t0
    ok = stat["200"]
    got = [m for m in mid if m is not None]
    avg = round(sum(got) / len(got), 3) if got else None
    print(f"VU {vus:3d}  {el:.0f}초  성공 {ok} ({ok/el:.0f}/s)  조회평균 {avg}  권고 {adv}  상태 {dict(stat)}", flush=True)
    return avg


for vus in (5, 10, 20, 40):
    run_step(vus, 30)
    time.sleep(5)
