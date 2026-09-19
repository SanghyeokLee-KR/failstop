"""공개 경로와 데모 읽기 권한을 검사한다. 토큰·잔고 원문은 출력하지 않는다."""
import json
import urllib.error
import urllib.request

BASE = 'https://www.sang.it.kr'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect)


def check(path, expected, method='GET', token=None, base=BASE):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    req = urllib.request.Request(base + path, headers=headers, method=method)
    try:
        response = opener.open(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read()
        status = response.code
        print(f'{method} {path}: {status}, expected={expected}')
        if status != expected:
            raise SystemExit('응답 코드 불일치')
        if expected == 308 and response.headers.get('Location') != BASE + path:
            raise SystemExit('HTTPS 이동 주소 불일치')
        return body


check('/exchange/KRW-BTC', 308, base='http://www.sang.it.kr')
check('/exchange/KRW-BTC', 200)
for path in ('/admin', '/admin.html', '/api/admin/health', '/api/v1/admin', '/actuator/health'):
    check(path, 403)
check('/.env', 404)
check('/api/v1/accounts', 401)
markets = json.loads(check('/api/v1/markets', 200))['data']
if len(markets) != 12:
    raise SystemExit('등록된 마켓 수 불일치')
print('markets:', len(markets))
session = json.loads(check('/api/v1/demo/session', 200, method='POST'))
accounts = json.loads(check('/api/v1/accounts', 200, token=session['access_token']))['data']
if len(accounts) != 13:
    raise SystemExit('데모 자산 계정 수 불일치')
print('demo assets:', len(accounts))
print('PASS: TLS 검증, 공개 경로, 관리 경로 차단, 데모 조회')
