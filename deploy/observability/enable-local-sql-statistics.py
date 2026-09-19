"""Enable SQL statistics on the known, promoted local lab without changing DB roles."""
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
DOCKER = os.environ.get('DOCKER_BIN', 'docker')
PRIMARY = 'failstop-ops-lab-standby-1'
REPLICA = 'failstop-ops-lab-rejoined-1'
APP = 'failstop-ops-lab-app-1'


def docker(*args, stdin=None):
    result = subprocess.run([DOCKER, *args], input=stdin, text=True, encoding='utf-8',
                            capture_output=True, timeout=60, check=True)
    return result.stdout.strip()


def sql(container, query):
    return docker('exec', '-i', container, 'psql', '-X', '-U', 'ops_admin', '-d', 'exchange',
                  '-At', '-v', 'ON_ERROR_STOP=1', stdin=query)


def settings(container):
    return json.loads(sql(container, "SELECT json_build_object('recovery',pg_is_in_recovery(),"
        "'preload',current_setting('shared_preload_libraries'),'version',current_setting('server_version'));"))


def wait_for(predicate, message):
    for _ in range(60):
        try:
            if predicate():
                return
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise RuntimeError(message)


def counts():
    return json.loads(sql(PRIMARY, "SELECT json_build_object('orders',(SELECT count(*) FROM orders),"
        "'trades',(SELECT count(*) FROM trades),'ledger',(SELECT count(*) FROM ledger_entries),"
        "'commands',(SELECT count(*) FROM command_journal));"))


def main():
    evidence = {'started_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                'before': {name: settings(name) for name in (PRIMARY, REPLICA)}}
    if evidence['before'][PRIMARY]['recovery'] or not evidence['before'][REPLICA]['recovery']:
        raise RuntimeError('Expected promoted lab roles were not confirmed')
    if docker('inspect', 'failstop-ops-lab-primary-1', '--format', '{{.State.Running}}') != 'false':
        raise RuntimeError('Old primary must remain stopped')
    app_running = docker('inspect', APP, '--format', '{{.State.Running}}') == 'true'
    requires_restart = any('pg_stat_statements' not in item['preload'].replace(' ', '').split(',')
                           for item in evidence['before'].values())
    try:
        if requires_restart and app_running:
            docker('stop', '-t', '30', APP)
        evidence['data_before'] = counts()
        for name in (REPLICA, PRIMARY):
            previous = evidence['before'][name]['preload']
            if 'pg_stat_statements' not in previous.replace(' ', '').split(','):
                # Preserve any previously enabled library. Only a SQL literal is constructed.
                merged = ','.join(filter(None, [previous, 'pg_stat_statements'])).replace("'", "''")
                sql(name, "ALTER SYSTEM SET shared_preload_libraries = '" + merged + "';")
                docker('restart', '-t', '30', name)
                wait_for(lambda: settings(name)['recovery'] == (name == REPLICA), 'DB did not recover its previous role')
            sql(name, "ALTER SYSTEM SET pg_stat_statements.track = 'top';")
            sql(name, "ALTER SYSTEM SET pg_stat_statements.track_utility = 'off';")
            sql(name, "ALTER SYSTEM SET compute_query_id = 'auto';")
            sql(name, 'SELECT pg_reload_conf();')
        sql(PRIMARY, (Path(__file__).with_name('enable-sql-statistics.sql')).read_text(encoding='utf-8'))
        wait_for(lambda: sql(REPLICA, "SELECT to_regclass('public.pg_stat_statements') IS NOT NULL;") == 't',
                 'Extension catalogue did not reach replica')
        wait_for(lambda: sql(PRIMARY, "SELECT count(*) FROM pg_stat_replication WHERE state='streaming';") == '1',
                 'Replication did not resume')
        evidence['data_after'] = counts()
        if evidence['data_before'] != evidence['data_after']:
            raise RuntimeError('Trade evidence counts changed during statistics setup')
        evidence['after'] = {name: settings(name) for name in (PRIMARY, REPLICA)}
        evidence['monitor_queries'] = {
            name: docker('exec', name, 'psql', '-X', '-U', 'failstop_monitor', '-d', 'exchange', '-Atc',
                         'SELECT count(*) >= 0 FROM pg_stat_statements;') == 't'
            for name in (PRIMARY, REPLICA)}
        if not all(evidence['monitor_queries'].values()):
            raise RuntimeError('Dedicated monitor cannot read SQL statistics')
    finally:
        if app_running:
            docker('start', APP)
        evidence['finished_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
        output = ROOT / 'artifacts/operations-20260918/grafana-db'
        output.mkdir(parents=True, exist_ok=True)
        (output / 'sql-statistics-activation.json').write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    if app_running:
        def ready():
            with urllib.request.urlopen('http://127.0.0.1:58180/actuator/health/readiness', timeout=3) as response:
                return json.load(response)['status'] == 'UP'
        wait_for(ready, 'Spring readiness did not return')
    print(json.dumps({'roles_preserved': True, 'trade_counts_preserved': True,
                      'monitor_queries': evidence['monitor_queries'], 'app_ready': app_running}))


if __name__ == '__main__':
    main()
