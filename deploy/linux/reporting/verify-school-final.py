"""이번 작업의 관측·봇·보고 상태만 출력한다. 비밀 값과 보고 링크는 제외한다."""
import json
from pathlib import Path
import subprocess
import urllib.parse
import urllib.request

for query in ('up', 'pg_up', 'pg_wal_position_in_recovery', 'pg_replication_client_sent_replay_bytes'):
    with urllib.request.urlopen('http://127.0.0.1:9090/api/v1/query?' + urllib.parse.urlencode({'query': query}), timeout=10) as r:
        data = json.load(r)
    print(query, [(x['metric'].get('node'), x['value'][1]) for x in data['data']['result']])
subprocess.run(['systemctl', 'show', 'exchange-bot', 'grafana-server', 'failstop-reporting', 'alertmanager',
                '-p', 'Id', '-p', 'ActiveState', '-p', 'NRestarts', '-p', 'MemoryCurrent'], check=True)
guard = json.loads(Path('/var/lib/exchange-bot-guard/state.json').read_text())
print('disk_guard', guard)
subprocess.run(['systemctl', 'list-timers', 'failstop-reporting-daily.timer', '--no-pager'], check=True)
