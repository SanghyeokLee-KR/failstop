import json,os,pathlib,subprocess
p=pathlib.Path('/tmp/failstop-backup-linux-check')
p.mkdir(exist_ok=True)
e=p/'backup.env'
e.write_text('\n'.join(k+'='+os.environ[k] for k in ['DB_HOST','DB_NAME','BACKUP_DB_USER','BACKUP_DB_PASSWORD'])+'\n')
e.chmod(0o600)
base=['python3','/workspace/deploy/backup/backup.py']
subprocess.run(base+['backup','--env',str(e),'--output',str(p/'dumps'),'--metrics-dir',str(p/'metrics')],check=True)
a=next((p/'dumps').glob('*.dump'))
subprocess.run(base+['restore-test','--archive',str(a),'--result',str(p/'restore.json'),'--metrics-dir',str(p/'metrics')],check=True)
(p/'checked.json').write_text(json.dumps({'linux_root':os.geteuid()==0,'env_mode':oct(e.stat().st_mode&0o777),'backup_mode':oct(a.stat().st_mode&0o777),'backup_bytes':a.stat().st_size,'backup_role':'dedicated pg_read_all_data','restore':json.loads((p/'restore.json').read_text())}))
e.unlink()
