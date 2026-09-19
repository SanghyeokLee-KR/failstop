"""봇 수신자 인증서로 설정을 암호화한다. 비밀 원문은 화면이나 S3에 쓰지 않는다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

KEYS = {
    'SPRING_DATASOURCE_PASSWORD',
    'EXCHANGE_AUTH_MASTER_KEY_BASE64',
    'EXCHANGE_MEMBER_PASSWORD_PEPPER_BASE64',
    'EXCHANGE_MEMBER_SESSION_SEED_BASE64',
}


def validate_env(text):
    lines = [line for line in text.splitlines() if line and not line.startswith('#')]
    keys = [line.partition('=')[0] for line in lines]
    if len(keys) != len(KEYS) or set(keys) != KEYS:
        raise SystemExit('필요한 네 가지 설정만 전달해야 한다.')
    if any(not line.partition('=')[2] for line in lines):
        raise SystemExit('비어 있는 비밀 설정이 있다.')
    return '\n'.join(lines) + '\n'


def write_new(path, data, mode):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(fd, 'wb') as file:
        file.write(data)


parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['seal', 'open'])
parser.add_argument('--sha256')
args = parser.parse_args()
os.umask(0o077)
work = Path('/root/exchange-bot-transfer')
work.mkdir(mode=0o700, exist_ok=True)
envelope = work / 'bot-settings.cms'
if args.action == 'seal':
    source = Path('/etc/exchange/app-secret.env').read_text()
    chosen = '\n'.join(line for line in source.splitlines() if line.partition('=')[0] in KEYS)
    payload = json.dumps({
        'env': validate_env(chosen),
        'cert': Path('/etc/exchange/primary.crt').read_text(),
    }).encode()
    encrypted = subprocess.run([
        'openssl', 'cms', '-encrypt', '-binary', '-aes256', '-outform', 'DER',
        str(work / 'recipient.crt'),
    ], input=payload, capture_output=True, check=True).stdout
    write_new(envelope, encrypted, 0o600)
    print('암호화 완료. SHA256:', hashlib.sha256(encrypted).hexdigest())
else:
    encrypted = envelope.read_bytes()
    if not args.sha256 or hashlib.sha256(encrypted).hexdigest() != args.sha256:
        raise SystemExit('암호문 해시가 앱에서 확인한 값과 다르다.')
    payload = subprocess.run([
        'openssl', 'cms', '-decrypt', '-binary', '-inform', 'DER',
        '-recip', str(work / 'recipient.crt'), '-inkey', str(work / 'recipient.key'),
    ], input=encrypted, capture_output=True, check=True).stdout
    values = json.loads(payload)
    env = validate_env(values['env']).encode()
    cert = values['cert'].encode()
    subprocess.run(['openssl', 'x509', '-noout'], input=cert, capture_output=True, check=True)
    target = Path('/etc/exchange-bot')
    target.mkdir(mode=0o755, exist_ok=True)
    os.chmod(target, 0o755)
    if any((target / name).exists() for name in ('bot-secret.env', 'primary.crt')):
        raise SystemExit('기존 봇 설정이 있다. 덮어쓰지 않는다.')
    write_new(target / 'bot-secret.env', env, 0o600)
    write_new(target / 'primary.crt', cert, 0o644)
    os.chmod(target / 'primary.crt', 0o644)
    print('봇 전용 경로에 설정 네 항목과 DB 공개 인증서 배치 완료. 원문 출력 없음.')
