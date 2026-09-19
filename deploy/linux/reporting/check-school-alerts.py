"""경보·전송 상태만 출력한다. 웹훅·서명 링크·원문 보고서는 출력하지 않는다."""
import json
import sqlite3
import urllib.request

with urllib.request.urlopen('http://127.0.0.1:9090/api/v1/alerts', timeout=10) as r:
    value = json.load(r)
for alert in value['data']['alerts']:
    print('PROM', alert['labels'].get('alertname'), alert['labels'].get('node'),
          alert['state'], alert.get('activeAt'))
with urllib.request.urlopen('http://127.0.0.1:9093/api/v2/alerts', timeout=10) as r:
    alerts = json.load(r)
for alert in alerts:
    print('AM', alert['labels'].get('alertname'), alert['labels'].get('node'), alert['status']['state'])
with sqlite3.connect('file:/var/lib/failstop-reporting/outbox.sqlite3?mode=ro', uri=True) as db:
    for row in db.execute('SELECT j.id,j.kind,j.status,j.error,a.status,j.report FROM jobs j LEFT JOIN report_artifacts a ON j.id=a.id ORDER BY j.created DESC LIMIT 10'):
        doc = json.loads(row[5]) if row[5] else {}
        incident = next((fact.get('records', {}) for fact in doc.get('evidence', {}).get('facts', [])
                         if fact.get('id') == 'trigger'), {})
        print('REPORT', row[:5], 'AI=', doc.get('analysis', {}).get('mode'),
              'incident=', [(event.get('labels', {}).get('alertname'), event.get('status'))
                            for event in incident] if isinstance(incident, list) else None)
