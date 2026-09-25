# ADR 0034 S5 설계 3절 N5 확인용 가짜 앱 노드. 표준 라이브러리만 쓴다(S4 확인의 nginx-backend.py 를 넓혔다).
# 노드는 파티션 소유 집합(owns)을 갖고, 주문 쓰기(POST·DELETE)의 마켓을 X-Exchange-Market 헤더에서, 없으면 JSON 바디의
# market 에서 읽어 그 마켓의 파티션을 소유하면 200, 아니면 앱의 소유권 선검사처럼 503 + Retry-After: 1 +
# X-Exchange-Route: not-owner 를 낸다. 마켓을 모르면(힌트도 바디도 없음) 소유 파티션이 하나라도 있으면 200 이다.
# 요청 헤더 X-Want-<노드 이름> 이 있으면 그 요청만 그 코드로 답한다(소유자의 일시 503 흉내).
# 요청 헤더 X-Probe-Accel 이 있고 소유자가 아니면 421 + X-Accel-Redirect: <그 값> 을 낸다(대안 확인용).
# 조작은 nginx 를 거치지 않고 직접 부른다. POST /mode?owns=p0,p1 은 소유 집합을 바꾸고(빈 값은 없음), GET /count 는 받은
# 요청 수를 (메서드, 마켓, 판정)별로 돌려준다. 응답 바디에 노드 이름, 판정, 받은 바디 길이, 받은 힌트 헤더 목록을 싣는다.
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

NAME = os.environ["NODE_NAME"]
PARTITION = {
    "KRW-BTC": "p0", "KRW-XRP": "p0", "KRW-DOGE": "p0", "KRW-TRX": "p0", "KRW-DOT": "p0", "KRW-AVAX": "p0",
    "KRW-ETH": "p1", "KRW-SOL": "p1", "KRW-ADA": "p1", "KRW-LINK": "p1", "KRW-MATIC": "p1", "KRW-ATOM": "p1",
}
STATE = {"owns": set(filter(None, os.environ.get("OWNS", "").split(","))), "count": {}}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, status, payload, extra=None):
        body = json.dumps(payload).encode()
        self.send_response(status)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _reply(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        url = urlparse(self.path)
        if url.path == "/mode":
            owns = parse_qs(url.query).get("owns", [""])[0]
            STATE["owns"] = set(filter(None, owns.split(",")))
            self._send(200, {"node": NAME, "owns": sorted(STATE["owns"])})
            return
        if url.path == "/count":
            self._send(200, {"node": NAME, "count": STATE["count"]})
            return
        hints = self.headers.get_all("X-Exchange-Market") or []
        market = hints[0] if hints else None
        if market is None and raw:
            try:
                market = json.loads(raw.decode()).get("market")
            except Exception:
                market = None
        write = self.command in ("POST", "DELETE")
        want = self.headers.get("X-Want-" + NAME)
        if want:
            status, verdict = int(want), "forced"
        elif not write:
            status, verdict = 200, "read"
        elif market in PARTITION:
            owner = PARTITION[market] in STATE["owns"]
            status, verdict = (200, "owner") if owner else (503, "not-owner")
        else:
            status, verdict = (200, "owner-unknown-market") if STATE["owns"] else (503, "not-owner")
        extra = {}
        if status == 503:
            extra["Retry-After"] = "1"
            if verdict == "not-owner":
                extra["X-Exchange-Route"] = "not-owner"
        if verdict == "not-owner" and self.headers.get("X-Probe-Accel"):
            status, extra = 421, {"X-Accel-Redirect": self.headers.get("X-Probe-Accel"), "X-Exchange-Route": "not-owner"}
        key = "%s %s %s" % (self.command, market or "-", verdict)
        STATE["count"][key] = STATE["count"].get(key, 0) + 1
        self._send(status, {"node": NAME, "verdict": verdict, "market": market, "body_len": len(raw),
                            "hints": hints, "method": self.command}, extra)

    do_GET = _reply
    do_POST = _reply
    do_DELETE = _reply

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
