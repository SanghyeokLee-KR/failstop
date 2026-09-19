"""승인된 기존 복제 자격증명을 전용 passfile로 연결한다."""
import json
import os
from pathlib import Path
import shutil
import subprocess

if os.geteuid() != 0 or '172.31.1.229' not in subprocess.check_output(['hostname', '-I'], text=True).split():
    raise SystemExit('대상 호스트 불일치')
def sql(query):
    return subprocess.check_output(['runuser', '-u', 'postgres', '--', 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', query], text=True).strip()
if sql('SELECT pg_is_in_recovery()') != 't':
    raise SystemExit('Standby 역할 확인 필요')
root = Path('/etc/postgresql/recovery-20260920')
secret = json.loads(subprocess.check_output(['openssl', 'cms', '-decrypt', '-binary', '-inform', 'DER', '-in', str(root / 'replication.p7m'), '-recip', str(root / 'recipient.crt'), '-inkey', str(root / 'recipient.key')], stderr=subprocess.DEVNULL))
if secret['host'] != '172.31.33.195' or secret['user'] != 'replicator':
    raise SystemExit('복제 대상 불일치')
path = Path('/var/lib/pgsql/.pgpass-rejoin-20260920')
os.umask(0o077)
escaped = secret['password'].replace('\\', '\\\\').replace(':', '\\:')
path.write_text('172.31.33.195:5432:replication:replicator:' + escaped + '\n')
path.chmod(0o600)
shutil.chown(path, user='postgres', group='postgres')
conn = 'host=172.31.33.195 port=5432 user=replicator sslmode=verify-full sslrootcert=/etc/postgresql/new-primary.crt passfile=' + str(path) + ' application_name=school_standby_a'
sql("ALTER SYSTEM SET primary_conninfo = '" + conn + "'")
sql("ALTER SYSTEM SET primary_slot_name = ''")
sql('SELECT pg_reload_conf()')
print('PASS: postgres 전용 0600 passfile 연결, TLS verify-full 유지')
