"""보고 URL·비밀을 출력하지 않고 실제 HTTPS 파일과 저장 상태를 확인한다."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import time
import urllib.request

sys.path.insert(0, '/opt/failstop/deploy/reporting')
from artifacts import signature, key

for line in Path('/etc/failstop/reporting.env').read_text().splitlines():
    if line and not line.startswith('#'):
        name, value = line.split('=', 1)
        os.environ[name] = value
db = sqlite3.connect('/var/lib/failstop-reporting/outbox.sqlite3')
row = db.execute("SELECT id,status,report,error FROM jobs WHERE id='school-live-check-20260920'").fetchone()
if not row or row[1] != 'sent':
    raise SystemExit('현재 보고 미접수: ' + str(None if not row else (row[1], row[3])))
report = json.loads(row[2])
print('Teams webhook accepted:', row[0], 'analysis:', report['analysis'].get('mode'))
settings = json.loads(Path('/etc/failstop/reporting.json').read_text())['artifacts']
identifier = hashlib.sha256(row[0].encode()).hexdigest()
expires = int(time.time()) + 3600
for ext in ('pdf', 'html'):
    url = settings['public_base_url'] + f'/reports/{identifier}/{expires}/' + signature(identifier, expires, ext, key()) + '/report.' + ext
    with urllib.request.urlopen(url, timeout=20) as response:
        body = response.read(8 * 1024 * 1024 + 1)
        assert response.status == 200
        assert len(body) <= 8 * 1024 * 1024
        assert body == (Path(settings['directory']) / identifier / ('report.' + ext)).read_bytes()
        assert (body.startswith(b'%PDF-') if ext == 'pdf' else b'<html' in body[:1000].lower())
        print(ext.upper(), 'HTTPS=200', 'stored-content-match=true', 'bytes=', len(body))
print('PASS: URL 서명 원문은 기록하지 않음. 채널 실제 표시 확인은 별도.')
