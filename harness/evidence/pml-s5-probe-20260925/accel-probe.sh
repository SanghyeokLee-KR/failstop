#!/usr/bin/env bash
# ADR 0034 S5 설계 3절 N5 의 부가 확인(Git Bash). nginx-s5-probe.sh 의 X1(421 + X-Accel-Redirect)이 넘기지 않은 까닭을 가른다.
# 가짜 백엔드가 요청 헤더로 고른 상태 코드와 X-Accel-Redirect 값(이름 붙은 location 이나 내부 URI)을 돌려주고, nginx 가 넘겼는지와
# 넘긴 뒤 메서드·바디 길이를 본다. 폐기용 컨테이너(pml-probe-s5x*-<난수>)만 쓰고 끝나면 지운다. 호스트 포트를 열지 않는다.
# 사용: bash accel-probe.sh > accel-probe.log 2>&1
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
TAG="${RANDOM}${RANDOM}"
NET="pml-probe-s5xnet-$TAG"; BE="pml-probe-s5xbe-$TAG"; NG="pml-probe-s5xng-$TAG"; CL="pml-probe-s5xcl-$TAG"
WORK="$(mktemp -d)"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() { docker rm -f "$BE" "$NG" "$CL" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
cat > "$WORK/be.py" <<'EOF'
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def _r(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        st = int(self.headers.get("X-Accel-Status") or 200)
        target = self.headers.get("X-Accel-Target")
        if self.headers.get("X-Hop") == "second" or self.path.startswith("/internal"):
            body = json.dumps({"hop": "second", "method": self.command, "path": self.path, "body_len": len(raw)}).encode()
            self.send_response(200)
        else:
            body = json.dumps({"hop": "first", "method": self.command, "body_len": len(raw)}).encode()
            self.send_response(st)
            if target:
                self.send_header("X-Accel-Redirect", target)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    do_GET = do_POST = do_DELETE = _r
    def log_message(self, *a): pass
ThreadingHTTPServer(("0.0.0.0", 8080), H).serve_forever()
EOF
cat > "$WORK/nginx.conf" <<EOF
worker_processes 1;
events { worker_connections 256; }
http {
    resolver 127.0.0.11 valid=10s ipv6=off;
    log_format x '\$request_method \$uri st=\$status ust="\$upstream_status" ups="\$upstream_addr"';
    access_log /var/log/nginx/access.log x;
    upstream first { zone first 64k; server $BE:8080 resolve max_fails=1 fail_timeout=2s; }
    upstream second { zone second 64k; server $BE:8080 resolve; }
    server {
        listen 8080;
        location /first/ {
            proxy_pass http://first;
            proxy_http_version 1.1;
            proxy_next_upstream error timeout http_502 http_503 non_idempotent;
        }
        location @named {
            proxy_pass http://second;
            proxy_set_header X-Hop second;
            proxy_http_version 1.1;
        }
        location /internal/ {
            internal;
            proxy_pass http://second;
            proxy_http_version 1.1;
        }
    }
}
EOF
docker network create "$NET" >/dev/null
docker create --name "$BE" --network "$NET" python:3.12-alpine python /tmp/be.py >/dev/null
docker cp "$(to_host "$WORK/be.py")" "$BE:/tmp/be.py"; docker start "$BE" >/dev/null
docker create --name "$NG" --network "$NET" nginx:1.29 >/dev/null
docker cp "$(to_host "$WORK/nginx.conf")" "$NG:/etc/nginx/nginx.conf"; docker start "$NG" >/dev/null
docker create --name "$CL" --network "$NET" --entrypoint sh curlimages/curl:8.11.0 -c 'sleep 100000' >/dev/null
docker start "$CL" >/dev/null
sleep 2
docker exec "$NG" nginx -v 2>&1 || { docker logs "$NG" 2>&1 | tail -5; exit 1; }
BODY='{"market":"KRW-ETH","pad":"0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"}'
run() { # run <이름표> <메서드> <상태> <대상>
    out=$(docker exec "$CL" curl -s -m 10 -X "$2" "http://$NG:8080/first/x" -H 'Content-Type: application/json' \
        --data "$BODY" -H "X-Accel-Status: $3" -H "X-Accel-Target: $4" -w ' http=%{http_code}')
    echo "$1 status=$3 target=$4 -> $out"
}
run A1 POST 200 @named
run A2 POST 421 @named
run A3 POST 503 @named
run A4 POST 409 @named
run A5 POST 200 /internal/x
run A6 POST 421 /internal/x
run A7 DELETE 421 @named
echo
echo "== access log"
docker logs "$NG" 2>/dev/null | grep -E '^(POST|DELETE|GET) ' | sed -E 's/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:8080/<ip>:8080/g'
echo "== error log (선택)"
docker logs "$NG" 2>&1 >/dev/null | grep -vE 'docker-entrypoint|notice' | sed -E 's/^[0-9\/: ]+//; s/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/<ip>/g' | head -20 || true
