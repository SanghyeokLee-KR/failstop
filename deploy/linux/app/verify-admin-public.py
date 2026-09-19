"""공개 로그인 화면과 비인증 API 차단을 검사한다. 비밀번호나 토큰을 쓰지 않는다."""
import urllib.error
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect)
for path, method, expected in (
    ('/admin/bots', 'GET', 200),
    ('/admin/v1/session', 'POST', 401),
    ('/admin/v1/session/', 'POST', 404),
    ('/admin/v1/sim/status', 'GET', 401),
    ('/grafana/login', 'GET', 200),
    ('/grafana/api/search', 'GET', 401),
    ('/api/admin/health', 'GET', 403),
    ('/actuator/health', 'GET', 403),
    ('/.env', 'GET', 404),
    ('/exchange/KRW-BTC', 'GET', 200),
):
    request = urllib.request.Request('https://www.sang.it.kr' + path, method=method)
    try:
        response = opener.open(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        print(f'{method} {path}: {response.code}, expected={expected}')
        if response.code != expected:
            raise SystemExit('응답 코드 불일치')
print('PASS: HTTPS 화면과 비인증 접근 차단')
