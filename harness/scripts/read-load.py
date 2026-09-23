#!/usr/bin/env python3
"""read-load.py. 조회 계층에 부하를 넣는다.

봇 발행률로는 조회 계층이 거의 움직이지 않는다. 봇 요청의 대부분은 주문·취소·계좌 조회와
호가·티커 폴링인데, 앞의 셋은 쓰기 경로이고 호가·티커도 노드 로컬 순번을 실어 보내므로 쓰기
경로에 남는다(검증설계 §3 LB 배선). 평균 회귀 추세 봇의 체결 조회는 조회 업스트림으로 가지만
양이 적다. 로드밸런서가 조회 업스트림으로 보내는 것은 DB 에서 완결되는 markets·trades·candles
뿐이라, 조회 계층을 밀려면 그 경로로 넣어야 한다.

2026-09-23 실측(로컬 카오스, 앱 노드 1 vCPU 두 대). 몇 시간 돌린 스택이다.

  VU  5   초당 203 건   조회 계층 평균 사용률 0.157
  VU 10   초당 307 건   0.229
  VU 20   초당 401 건   0.331
  VU 40   초당 370 건   0.225, 429 가 7,794 건, 500 이 3 건

20 VU 근처가 이 스택의 천장이다. 더 올리면 수용 제어(ADR 0007 조회 버킷)가 먼저 걸려
처리량이 오히려 준다. 같은 날 20 VU 로 420 초를 이어 돌린 주행은 200 이 145,078 건
(초당 345 건)이고 그 밖의 응답이 0 이었다. 시연에 쓰기 좋은 값이다.

사용률은 표본마다 크게 흔들린다. 재기동 50 초 뒤 같은 20 VU 로 시작한 종단 리허설에서 두 대
표본은 원자료 순서로 다음과 같았다. 봇 목표를 올리기 전 첫 표본(부하 시작 약 10 초 뒤)이
0.86, 봇 목표 초당 300 건이 겹친 구간이 0.53~0.58, 봇 목표를 10 건으로 되돌리고 축소한 뒤가
0.27~0.28 이다. 0.86 은 봇 300 건과 겹치지 않은 단일 표본이라 재기동 직후의 예열 때문인지
확인하지 못했다(harness/evidence/scale-rehearsal-20260923/rehearse.jsonl).
워밍된 스택의 계단 측정에서는 20 VU 가 0.33 에서 그쳤으므로 계약값 0.60 을 이 스택의 기준으로
쓰면 워밍된 상태에서는 권고가 아예 나오지 않는다. application.yml 의 admin-chaos 프로파일이
0.25 로 내리는 이유가 그것이고, AWS 운영값 0.60 과 구분한다.

폐쇄 루프다. 각 작업자가 응답을 기다렸다가 다음을 보내므로 나온 처리량이 곧 서버가 낸
값이다. 도착률 방식은 서버가 느려질 때 도구가 요청을 버려 그 수가 서버 능력이 아니게 된다.

사용:
  python harness/scripts/read-load.py --vus 20 --sec 120
  python harness/scripts/read-load.py --base http://localhost:58080 --market KRW-ETH
"""

import argparse
import collections
import threading
import time
import urllib.error
import urllib.request


def paths(market):
    """전부 DB 에서 완결되는 조회다. 호가·티커는 쓰기 경로라 여기 없다."""
    return [
        f"/api/v1/trades?market={market}&count=50",
        f"/api/v1/candles/1m?market={market}&count=100",
        "/api/v1/markets",
    ]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default="http://localhost:58080", help="로드밸런서 기점")
    p.add_argument("--market", default="KRW-BTC")
    p.add_argument("--vus", type=int, default=20, help="동시 작업자. 이 스택의 천장은 20 근처다")
    p.add_argument("--sec", type=float, default=120)
    p.add_argument("--timeout", type=float, default=8)
    a = p.parse_args()

    urls = [a.base + q for q in paths(a.market)]
    stop = threading.Event()
    stat = collections.Counter()
    lock = threading.Lock()

    def worker(seed):
        i = seed
        while not stop.is_set():
            try:
                with urllib.request.urlopen(urls[i % len(urls)], timeout=a.timeout) as r:
                    r.read()
                    key = str(r.status)
            except urllib.error.HTTPError as e:
                key = "http%d" % e.code
            except Exception as e:
                key = type(e).__name__
            with lock:
                stat[key] += 1
            i += 1

    threads = [threading.Thread(target=worker, args=(k,), daemon=True) for k in range(a.vus)]
    t0 = time.time()
    for t in threads:
        t.start()
    try:
        while time.time() - t0 < a.sec:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    stop.set()
    elapsed = time.time() - t0
    ok = stat["200"]
    # 성공만 세지 않는다. 429 는 수용 제어가 일한 결과라 같이 봐야 천장을 읽을 수 있다
    print("VU %d, %.1f초, 200 %d (%.0f/s), 그 밖 %s"
          % (a.vus, elapsed, ok, ok / elapsed if elapsed else 0,
             dict((k, v) for k, v in stat.items() if k != "200") or "없음"))


if __name__ == "__main__":
    main()
