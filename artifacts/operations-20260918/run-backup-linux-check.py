import importlib.util, json, os, pathlib, secrets, subprocess, sys, tempfile
root=pathlib.Path(r'D:\dev\failstop')
out=root/'artifacts/operations-20260918/backup-linux'
out.mkdir(parents=True,exist_ok=True)
docker=r'C:\Users\kopo26\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
source='failstop-ops-lab-standby-1'
name='failstop-backup-linux-check'
role='backup_check_'+secrets.token_hex(5)
password=secrets.token_hex(32)
def run(*args, input=None, timeout=300):
 p=subprocess.run([docker,*map(str,args)],input=input,text=True,capture_output=True,encoding='utf-8',errors='replace',timeout=timeout)
 if p.returncode: raise RuntimeError(str(args[0])+' failed '+str(p.returncode)+': '+p.stderr.replace(password,'[redacted]')[-1200:])
 return p.stdout.strip()
info=json.loads(run('inspect',source))[0]
assert info['Config']['Labels']['com.docker.compose.project']=='failstop-ops-lab'
ip=next(iter(info['NetworkSettings']['Networks'].values()))['IPAddress']
assert ip
assert not run('ps','-aq','--filter','name=^/'+name+'$')
sql=(root/'deploy/backup/provision-backup-role.sql').read_text(encoding='utf-8')
sql=sql.replace('\\getenv backup_user BACKUP_DB_USER',"\\set backup_user '"+role+"'")
sql=sql.replace('\\getenv backup_password BACKUP_DB_PASSWORD',"\\set backup_password '"+password+"'")
run('exec','-i',source,'psql','-X','-U','ops_admin','-d','exchange',input=sql)
created=False
try:
 (out/'Dockerfile').write_text('FROM python:3.12-alpine\nRUN apk add --no-cache docker-cli\n',encoding='utf-8')
 run('build','-t','failstop-backup-check:local',out,timeout=600)
 envfile=out/'runtime.env'
 envfile.write_text('DB_HOST='+ip+'\nDB_NAME=exchange\nBACKUP_DB_USER='+role+'\nBACKUP_DB_PASSWORD='+password+'\n',encoding='utf-8')
 script='''import json,os,pathlib,subprocess
p=pathlib.Path('/tmp/failstop-backup-linux-check')
p.mkdir(exist_ok=True)
e=p/'backup.env'
e.write_text('\\n'.join(k+'='+os.environ[k] for k in ['DB_HOST','DB_NAME','BACKUP_DB_USER','BACKUP_DB_PASSWORD'])+'\\n')
e.chmod(0o600)
base=['python3','/workspace/deploy/backup/backup.py']
subprocess.run(base+['backup','--env',str(e),'--output',str(p/'dumps'),'--metrics-dir',str(p/'metrics')],check=True)
a=next((p/'dumps').glob('*.dump'))
subprocess.run(base+['restore-test','--archive',str(a),'--result',str(p/'restore.json'),'--metrics-dir',str(p/'metrics')],check=True)
(p/'checked.json').write_text(json.dumps({'linux_root':os.geteuid()==0,'env_mode':oct(e.stat().st_mode&0o777),'backup_mode':oct(a.stat().st_mode&0o777),'backup_bytes':a.stat().st_size,'backup_role':'dedicated pg_read_all_data','restore':json.loads((p/'restore.json').read_text())}))
e.unlink()
'''
 (out/'runner.py').write_text(script,encoding='utf-8')
 run('run','-d','--name',name,'--env-file',envfile,'--mount','type=bind,source='+str(root)+',target=/workspace,readonly','-v','/var/run/docker.sock:/var/run/docker.sock','-v','/tmp/failstop-backup-linux-check:/tmp/failstop-backup-linux-check','failstop-backup-check:local','python3','/workspace/artifacts/operations-20260918/backup-linux/runner.py')
 created=True
 code=run('wait',name,timeout=300)
 logs=run('logs',name)
 (out/'run.log').write_text(logs.replace(password,'[redacted]'),encoding='utf-8')
 if code!='0': raise RuntimeError('Linux backup runner failed: '+logs.replace(password,'[redacted]')[-1500:])
 run('cp',name+':/tmp/failstop-backup-linux-check/checked.json',out/'checked.json')
 print((out/'checked.json').read_text(encoding='utf-8')[:600])
finally:
 if created: run('rm','-fv',name)
 if (out/'runtime.env').exists(): (out/'runtime.env').unlink()
 run('exec','-i',source,'psql','-X','-U','ops_admin','-d','exchange',input='DROP ROLE '+role+';')
 run('run','--rm','-v','/tmp/failstop-backup-linux-check:/clean','failstop-backup-check:local','python3','-c',"import pathlib,shutil;p=pathlib.Path('/clean');[(shutil.rmtree(x) if x.is_dir() else x.unlink()) for x in p.iterdir()]")
