"""128MiB 시험 파일시스템만 채운다. 운영 DB·루트 파일은 변경하지 않는다."""
import os
from pathlib import Path
import subprocess

image = Path('/var/lib/exchange-disk-alert-test.img')
mount = Path('/mnt/exchange-disk-alert-test')
if os.geteuid() != 0 or image.exists() or mount.exists():
    raise SystemExit('root 및 새로운 시험 경로가 필요합니다.')
with image.open('xb') as stream:
    stream.truncate(128 * 1024 * 1024)
mount.mkdir(mode=0o755)
subprocess.run(['mkfs.ext4', '-F', '-m', '0', str(image)], check=True, stdout=subprocess.DEVNULL)
subprocess.run(['mount', '-o', 'loop,nodev,nosuid,noexec', str(image), str(mount)], check=True)
# 명령이나 연결이 중단돼도 시험 파일의 공간은 자동으로 반환한다.
subprocess.run(['systemd-run', '--unit=exchange-disk-test-release', '--on-active=420s',
                '/usr/bin/truncate', '-s', '0', str(mount / 'fill')], check=True)
stat = os.statvfs(mount)
with (mount / 'fill').open('xb') as stream:
    os.posix_fallocate(stream.fileno(), 0, int(stat.f_bavail * stat.f_frsize * .97))
subprocess.run(['df', '-h', str(mount)], check=True)
print('시험용 128MiB 파일시스템. 7분 후 자동으로 공간 반환. 운영 루트·DB는 유지.')
