"""Nginx와 봇 서버 사이의 mTLS를 준비한다. PC용 인증서는 만들지 않는다."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess

BUCKET = 's3://2026-ai-kopo-04-exchange-artifacts-619256964117/transfer/'
WORK = Path('/root/exchange-admin-gateway')


def run(*args, data=None):
    result = subprocess.run(args, input=data, capture_output=True)
    if result.returncode:
        raise SystemExit(f'{Path(args[0]).name} 작업 실패. 종료 코드 {result.returncode}')
    return result.stdout


def new_directory(path):
    if path.exists():
        raise SystemExit(f'기존 경로를 덮어쓰지 않는다: {path}')
    path.mkdir(mode=0o700, parents=True)


def write_private(path, data):
    with path.open('xb') as file:
        file.write(data)
    path.chmod(0o600)


def app_recipient():
    new_directory(WORK)
    run('openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '90',
        '-keyout', str(WORK / 'recipient.key'), '-out', str(WORK / 'recipient.crt'),
        '-subj', '/CN=School app admin gateway transfer')
    run('aws', 's3', 'cp', str(WORK / 'recipient.crt'), BUCKET + 'admin-gateway-recipient.crt',
        '--region', 'ap-northeast-2', '--only-show-errors')
    print('앱 수신자 공개 인증서 게시 완료. 개인키는 root 전용 경로에 보관.')


def bot_pki():
    new_directory(WORK)
    pki = Path('/etc/exchange-bot/admin-pki')
    new_directory(pki)
    pki.chmod(0o750)
    shutil.chown(pki, user='root', group='exchange-bot')
    password = secrets.token_hex(32)
    write_private(WORK / 'store-password', (password + '\n').encode())
    write_private(Path('/etc/exchange-bot/admin-secret.env'),
                  (f'SERVER_SSL_KEY_STORE_PASSWORD={password}\n'
                   f'SERVER_SSL_TRUST_STORE_PASSWORD={password}\n').encode())
    run('openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '90',
        '-keyout', str(WORK / 'ca.key'), '-out', str(WORK / 'ca.crt'),
        '-subj', '/CN=School exchange service CA',
        '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
        '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
    for name, cn, extensions in (
        ('server', 'school-bot-admin.internal', 'extendedKeyUsage=serverAuth\nsubjectAltName=DNS:school-bot-admin.internal\n'),
        ('gateway', 'sanghyuk-bot-operator', 'extendedKeyUsage=clientAuth\n'),
    ):
        run('openssl', 'req', '-new', '-newkey', 'rsa:3072', '-nodes',
            '-keyout', str(WORK / f'{name}.key'), '-out', str(WORK / f'{name}.csr'), '-subj', '/CN=' + cn)
        write_private(WORK / f'{name}.ext',
                      ('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n' + extensions).encode())
        run('openssl', 'x509', '-req', '-in', str(WORK / f'{name}.csr'),
            '-CA', str(WORK / 'ca.crt'), '-CAkey', str(WORK / 'ca.key'), '-CAcreateserial',
            '-days', '60', '-sha256', '-extfile', str(WORK / f'{name}.ext'), '-out', str(WORK / f'{name}.crt'))
    run('openssl', 'pkcs12', '-export', '-name', 'exchange-admin',
        '-inkey', str(WORK / 'server.key'), '-in', str(WORK / 'server.crt'),
        '-certfile', str(WORK / 'ca.crt'), '-out', str(pki / 'server.p12'),
        '-passout', 'file:' + str(WORK / 'store-password'))
    run('keytool', '-importcert', '-noprompt', '-alias', 'school-service-ca',
        '-file', str(WORK / 'ca.crt'), '-keystore', str(pki / 'operators.p12'),
        '-storetype', 'PKCS12', '-storepass:file', str(WORK / 'store-password'))
    for path in pki.glob('*.p12'):
        path.chmod(0o640)
        shutil.chown(path, user='root', group='exchange-bot')
    recipient = WORK / 'app-recipient.crt'
    run('aws', 's3', 'cp', BUCKET + 'admin-gateway-recipient.crt', str(recipient),
        '--region', 'ap-northeast-2', '--only-show-errors')
    payload = json.dumps({name: (WORK / name).read_text()
                          for name in ('gateway.key', 'gateway.crt', 'ca.crt')}).encode()
    encrypted = run('openssl', 'cms', '-encrypt', '-binary', '-aes256', '-outform', 'DER', str(recipient), data=payload)
    write_private(WORK / 'gateway.cms', encrypted)
    run('aws', 's3', 'cp', str(WORK / 'gateway.cms'), BUCKET + 'admin-gateway.cms',
        '--region', 'ap-northeast-2', '--only-show-errors')
    print('서버 PKI와 암호화된 게이트웨이 인증서 준비 완료. 서버 간 인증서 유효기간 60일.')


def app_install():
    output = Path('/etc/nginx/admin-pki')
    if output.exists():
        raise SystemExit('기존 게이트웨이 PKI를 덮어쓰지 않는다.')
    encrypted = run('aws', 's3', 'cp', BUCKET + 'admin-gateway.cms', '-',
                    '--region', 'ap-northeast-2', '--only-show-errors')
    clear = run('openssl', 'cms', '-decrypt', '-binary', '-inform', 'DER',
                '-recip', str(WORK / 'recipient.crt'), '-inkey', str(WORK / 'recipient.key'), data=encrypted)
    values = json.loads(clear)
    if set(values) != {'gateway.key', 'gateway.crt', 'ca.crt'}:
        raise SystemExit('전달 파일 구성이 다르다.')
    stage = WORK / 'verified'
    new_directory(stage)
    for name, value in values.items():
        write_private(stage / name, value.encode())
    run('openssl', 'verify', '-purpose', 'sslclient', '-CAfile', str(stage / 'ca.crt'), str(stage / 'gateway.crt'))
    public = run('openssl', 'x509', '-in', str(stage / 'gateway.crt'), '-pubkey', '-noout')
    if public != run('openssl', 'pkey', '-in', str(stage / 'gateway.key'), '-pubout'):
        raise SystemExit('게이트웨이 인증서와 개인키가 다르다.')
    new_directory(output)
    for name in values:
        shutil.copy2(stage / name, output / name)
    print('Nginx root 전용 경로에 서버 간 인증서를 배치했다. 개인키 출력 없음.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['app-recipient', 'bot-pki', 'app-install'])
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit('root 권한이 필요하다.')
    os.umask(0o077)
    {'app-recipient': app_recipient, 'bot-pki': bot_pki, 'app-install': app_install}[args.action]()
