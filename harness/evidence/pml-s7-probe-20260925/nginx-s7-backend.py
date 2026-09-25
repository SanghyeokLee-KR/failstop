# ADR 0034 S7 설계 3절 N7 확인용 가짜 앱 노드. 표준 라이브러리만 쓴다(S5 확인의 nginx-s5-backend.py 를 넓혔다).
# 주문 쓰기(POST·DELETE)는 S5 판과 같다. 마켓을 X-Exchange-Market 헤더, 없으면 JSON 바디의 market 에서 읽어 그 파티션을
# 소유하면 200, 아니면 앱의 소유권 선검사처럼 503 + Retry-After: 1 + X-Exchange-Route: not-owner 를 낸다.
# /ws/ 로 온 업그레이드 요청은 ws 모드가 refuse 면 업그레이드 전 503 + Retry-After: 5(앱의 WS 게이트 거절 흉내), 아니면
# 101 Switching Protocols 를 보내고 0.5초 뒤 연결을 닫는다. 조작은 nginx 를 거치지 않고 직접 부른다.
# POST /mode?owns=p0,p1&ws=accept|refuse 는 소유 집합과 WS 모드를 바꾸고, GET /count 는 받은 요청 수를 돌려준다.
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

NAME = os.environ["NODE_NAME"]
PARTITION = {
    "KRW-BTC": "p0", "KRW-XRP": "p0", "KRW-DOGE": "p0", "KRW-TRX": "p0", "KRW-DOT": "p0", "KRW-AVAX": "p0",
    "KRW-ETH": "p1", "KRW-SOL": "p1", "KRW-ADA": "p1", "KRW-LINK": "p1", "KRW-MATIC": "p1", "KRW-ATOM": "p1",
}
STATE = {"owns": set(filter(None, os.environ.get("OWNS", "").split(","))), "ws": os.environ.get("WS", "accept"),
         "count": {}}


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

    def _count(self, key):
        STATE["count"][key] = STATE["count"].get(key, 0) + 1

    def _reply(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        url = urlparse(self.path)
        if url.path == "/mode":
            q = parse_qs(url.query, keep_blank_values=True)
            if "owns" in q:
                STATE["owns"] = set(filter(None, q["owns"][0].split(",")))
            if "ws" in q:
                STATE["ws"] = q["ws"][0]
            self._send(200, {"node": NAME, "owns": sorted(STATE["owns"]), "ws": STATE["ws"]})
            return
        if url.path == "/count":
            self._send(200, {"node": NAME, "count": STATE["count"]})
            return
        if url.path.startswith("/ws/"):
            upgrade = (self.headers.get("Upgrade") or "").lower() == "websocket"
            if STATE["ws"] == "refuse" or not upgrade:
                self._count("WS refuse" if upgrade else "WS no-upgrade")
                self._send(503, {"node": NAME, "verdict": "ws-refused"}, {"Retry-After": "5"})
                return
            self._count("WS 101")
            self.send_response(101)
            self.send_header("Upgrade", "websocket")
            self.send_header("Connection", "Upgrade")
            self.send_header("Sec-WebSocket-Accept", "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
            self.end_headers()
            self.wfile.flush()
            time.sleep(0.5)
            self.close_connection = True
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
        self._count("%s %s %s %s" % (self.command, url.path, market or "-", verdict))
        self._send(status, {"node": NAME, "verdict": verdict, "market": market, "method": self.command}, extra)

    do_GET = _reply
    do_POST = _reply
    do_DELETE = _reply

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
