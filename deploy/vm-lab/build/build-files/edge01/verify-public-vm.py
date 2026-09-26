"""EDGE01 공개 경로와 데모 읽기 권한을 검사한다. 토큰과 잔고 원문은 출력하지 않는다.

원본은 failstop/deploy/linux/app/verify-public.py 다. 바꾼 것은 두 가지다.
  1. BASE 를 학교 도메인에서 VM 정본 진입 주소로 바꿨다.
  2. 자체 서명 인증서를 쓰므로 그 인증서 하나만 신뢰 기준으로 삼는 SSLContext 를 만든다.
     -k 로 검증을 건너뛰지 않는다. 인증서의 SAN 과 유효기간을 실제로 검사한다.

검사 항목은 원본과 같다. 308 전환, 관리 경로 차단, /.env 404, 인증 없는 조회 401,
마켓 12개, 데모 계정 13개다.

실행:
  python3 verify-public-vm.py
  EDGE01_BASE=https://192.168.56.10 python3 verify-public-vm.py
  EDGE01_CA=/tmp/edge01.crt python3 verify-public-vm.py   # 작업 PC 에서 돌릴 때

정본 진입 주소는 pfSense 경계형 전환(2026-09-26) 뒤 https://192.168.56.10 하나다.
기본값이 그 값이므로 보통은 변수를 주지 않는다. 전환 전 구축 단계에서는
EDGE01_BASE=https://192.168.56.11 을 준다.
lab-lan 주소로도 확인하려면 정본 경로를 먼저 통과시킨 뒤 보조 경로 점검용으로만 돌린다.
  EDGE01_BASE=https://192.168.10.11 python3 verify-public-vm.py   # 보조 경로 점검용
그 결과를 정본 경로의 검증 결과로 옮겨 적지 않는다.

앱과 DB 가 올라와 있어야 마켓과 데모 계정 검사가 의미를 갖는다.
앱이 없으면 /api/ 경로에서 502 가 나오고 여기서 멈춘다.
"""
import json
import os
import ssl
import urllib.error
import urllib.request

BASE = os.environ.get('EDGE01_BASE', 'https://192.168.56.10')
HTTP_BASE = BASE.replace('https://', 'http://', 1)
CA_FILE = os.environ.get('EDGE01_CA', '/etc/pki/tls/certs/edge01.crt')

if not os.path.exists(CA_FILE):
    raise SystemExit(f'인증서 파일이 없다: {CA_FILE}. EDGE01_CA 로 경로를 지정한다.')

# 자체 서명 인증서를 자기 자신의 신뢰 기준으로 넣는다.
# 10-edge01-setup.sh 가 basicConstraints CA:TRUE 로 만들기 때문에 이 방식이 성립한다.
# 호스트 검사는 켜 둔다. 인증서 SAN 의 IP 와 접속 주소가 다르면 여기서 실패해야 한다.
context = ssl.create_default_context(cafile=CA_FILE)
context.check_hostname = True
context.verify_mode = ssl.CERT_REQUIRED


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=context))


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


check('/exchange/KRW-BTC', 308, base=HTTP_BASE)
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
print('PASS: 인증서 검증, 공개 경로, 관리 경로 차단, 데모 조회')
