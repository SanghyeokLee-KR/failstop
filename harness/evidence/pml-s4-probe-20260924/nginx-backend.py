# ADR 0034 S4 설계 3절 N 확인용 가짜 앱 노드. 표준 라이브러리만 쓴다.
# 기본 상태 코드는 환경 변수 DEFAULT_STATUS(리더면 200, 팔로워면 503)이고 /mode/<코드> 로 실행 중에 바꾼다(nginx 를 거치지 않고 직접 부른다).
# 요청 헤더 X-Want-<이름> 이 있으면 그 요청만 그 코드로 답한다. 응답 바디에 노드 이름과 답한 코드를 싣는다.
# /count 는 모드 조작을 뺀 받은 요청 수다(nginx 가 어느 노드에 몇 번 보냈는지 센다).
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NAME = os.environ["NODE_NAME"]
STATE = {"default": int(os.environ.get("DEFAULT_STATUS", "200")), "count": 0}


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        if status in (429, 503):
            self.send_header("Retry-After", "1")
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _reply(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if self.path.startswith("/mode/"):
            STATE["default"] = int(self.path.rsplit("/", 1)[1])
            self._send(200, {"node": NAME, "default": STATE["default"]})
            return
        if self.path == "/count":
            self._send(200, {"node": NAME, "count": STATE["count"]})
            return
        STATE["count"] += 1
        want = self.headers.get("X-Want-" + NAME)
        status = int(want) if want else STATE["default"]
        self._send(status, {"node": NAME, "status": status})

    do_GET = _reply
    do_POST = _reply

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
