# APP02 거래 앱 노드 무인 설치 답안 파일. Rocky Linux 10.2 minimal 기준이다.
#
# 설치 경로는 하나다. build\scripts\01-create-vms.ps1 이 이 파일을 임시 사본으로 복사하면서
# 자리표시자 OPS_SSH_PUBKEY 를 build\keys\vm-lab_ed25519.pub 의 한 줄로 치환하고,
# 치환한 사본을 VBoxManage unattended install --script-template 에 넘긴다. 치환 뒤에도
# 치환되지 않은 자리표시자가 남아 있으면 그 스크립트가 멈춘다. inst.ks=http://... 로 받는 경로와
# ks-<이름>.cfg 두 번째 세트는 쓰지 않는다. 근거는 build\02-정본결정.md 2절이다.
#
# 짝이 되는 개인키는 %USERPROFILE%\.ssh\vm-lab_ed25519 에 두고 VM 에 복사하지 않는다.
# build 폴더를 VM 으로 옮길 때 keys\ 는 제외한다.
#
# 주소와 자원은 build\00-주소계획.md 의 값만 쓴다. 여기와 다르면 계획서를 먼저 고친다.
#   lab-lan   192.168.10.22/24 (enp0s8, 서비스 통신)
#   host-only 192.168.56.22/24 (enp0s9, 관리 SSH)
#   NAT       DHCP             (enp0s3, 구축 단계의 패키지 설치 경로)
#   vCPU 2 · 메모리 3GB · 디스크 25GB
#
# app01.ks 와 다른 곳은 주소와 이름, /etc/vm-lab-node 의 값뿐이다. 나머지는 같다.
#
# 이 파일은 OS 설치와 관리 접속까지만 만든다. Java 와 거래 앱은 설치하지 않는다.
# Rocky minimal ISO 에는 BaseOS 일부만 들어 있어 java-21-openjdk-headless 같은 AppStream
# 패키지가 없다. 앱 설치는 VM 이 뜬 뒤 build/scripts/20-app-setup.sh 가 맡는다.
#
# 이 설치가 끝난 시점의 상태는 다음과 같다.
#   - ops 계정에 SSH 키로 로그인된다. root 로그인과 비밀번호 인증은 막혀 있다.
#   - SSH 는 host-only 주소 192.168.56.22 의 22번에만 붙는다.
#   - journald 가 영구 저장으로 돌고 보존 한도가 걸려 있다.
#   - 방화벽 정책은 아직 없다. firewalld 와 배포판 nftables.service 는 꺼져 있고
#     nftables ruleset 은 비어 있다. 기본 차단 정책과 node_exporter, 진단 타이머는
#     설치 후 scripts/00-common-setup.sh 가 건다. 8080 과 8081 은 그다음에
#     20-app-setup.sh 가 역할 규칙으로 덧붙인다.
#
# 이 파일은 설치 절차이며 검증 결과가 아니다. 아직 이 답안으로 설치를 돌린 적이 없다.

#version=RHEL10

# 설치 화면은 텍스트로 둔다. 그래픽 설치는 minimal ISO 에 필요하지 않다.
text
# 패키지는 부팅에 쓴 DVD 에서 읽는다. ks 파일은 01-create-vms.ps1 이 만드는 보조 매체로 들어온다.
cdrom
eula --agreed
# 설치 후 초기 설정 마법사를 띄우지 않는다. 무인 설치의 전제다.
firstboot --disable
# 설치가 끝나면 ISO 를 빼고 재부팅한다. 매체가 붙은 채로 재부팅하면 설치 메뉴로 다시 들어가
# 같은 디스크를 또 지울 수 있다.
reboot --eject

lang en_US.UTF-8
keyboard --xlayouts='us'
# VM 은 --rtcuseutc on 으로 만든다. 하드웨어 시계가 UTC 이므로 --utc 를 붙인다.
# 하드웨어 시계를 UTC 로 맞춰야 리더 리스 만료 판단과 OPS01 지표 시각 대조가 어긋나지 않는다.
# RHEL 10 계열에서 timezone 의 NTP 관련 옵션은 timesource 로 분리됐다. 여기서는 시간대만 정한다.
timezone Asia/Seoul --utc

# SELinux 는 끄지 않는다. 앱이 거부되면 첫 기동 뒤 ausearch 로 확인하고 포트와 문맥만 추가한다.
selinux --enforcing
# firewalld 는 쓰지 않는다. 방화벽 정책원은 /etc/vm-lab/firewall.nft 하나이고 그 규칙은
# vm-lab-firewall.service 가 넣는다(02-정본결정.md 4절).
firewall --disabled
# 배포판 nftables.service 도 켜지 않는다. 이 유닛은 /etc/sysconfig/nftables.conf 를 읽고
# 정지할 때 ruleset 전체를 비우므로 같은 커널 후크에 정책원을 둘로 두게 된다.
services --enabled=sshd,chronyd --disabled=firewalld,nftables

# NIC 순서는 여섯 대가 모두 같다. NIC1 NAT, NIC2 lab-lan, NIC3 host-only 다.
# 순서가 다르면 아래 enp0s3 / enp0s8 / enp0s9 대응이 깨진다. VM 생성 때 순서를 맞춘다.
# IPv6 는 쓰지 않는다. 주소 체계를 하나로 두어야 방화벽과 바인딩 확인이 단순해진다.
#
# NIC1 enp0s3: 구축 단계의 패키지 설치용 NAT. 기본 경로는 이 연결 하나만 가진다.
network --device=enp0s3 --bootproto=dhcp --onboot=yes --activate --noipv6
# NIC2 enp0s8: lab-lan 서비스 통신. 기본 경로와 DNS 를 가져가지 않는다.
# pfSense 구축 후 이 연결에 gateway 192.168.10.1 을 붙이고 NAT 를 내린다.
network --device=enp0s8 --bootproto=static --ip=192.168.10.22 --netmask=255.255.255.0 --nodefroute --onboot=yes --activate --noipv6
# NIC3 enp0s9: host-only 관리 접속. SSH 가 이 주소에만 붙는다.
network --device=enp0s9 --bootproto=static --ip=192.168.56.22 --netmask=255.255.255.0 --nodefroute --onboot=yes --activate --noipv6
# 이름은 여섯 대 모두 <노드>.lab 로 통일한다. DB 인증서의 CN 과 SAN DNS 가 쓰는 이름과 같다.
network --hostname=app02.lab

# root 는 비밀번호를 잠그고 SSH 직접 로그인도 막는다. 관리 작업은 ops 의 sudo 로 한다.
rootpw --lock
# ops 는 비밀번호가 잠긴 계정이며 SSH 키로만 들어온다.
# wheel 그룹은 구축 단계의 전체 sudo 를 위한 것이다. 구축이 끝나면 좁힌다.
user --name=ops --groups=wheel --shell=/bin/bash --gecos="VM lab operator" --lock
# 자리표시자는 01-create-vms.ps1 이 치환한다. 여섯 답안 파일이 같은 형식을 쓴다.
# root 와 ops 가 모두 잠겨 있으므로 이 키가 유일한 진입 수단이다. 치환되지 않으면
# 아래 %post 의 확인 단계가 설치를 실패로 끝낸다.
sshkey --username=ops "@@OPS_SSH_PUBKEY@@"

# OS 디스크는 sda 하나만 쓴다. APP 두 대에는 별도 데이터 디스크를 붙이지 않는다.
# 파티션 형식은 여섯 대가 같다. VirtualBox 무인 설치가 EFI 부팅 경로만 패치하므로 UEFI로 쓴다.
# VirtualBox 에서 EFI 를 켜지 않은 기본 설정을 전제한다.
ignoredisk --only-use=sda
zerombr
clearpart --all --initlabel --drives=sda --disklabel=gpt
part /boot/efi --fstype=efi --size=600 --ondisk=sda --fsoptions="umask=0077,shortname=winnt"
part /boot --fstype=xfs --size=1024 --ondisk=sda --label=boot
part pv.01 --size=1024 --grow --ondisk=sda
volgroup vg_os pv.01
logvol swap     --vgname=vg_os --name=lv_swap   --fstype=swap --size=2048
# /var/log 를 따로 떼어 저널이 넘쳐도 루트 파일시스템이 멈추지 않게 한다.
logvol /var/log --vgname=vg_os --name=lv_varlog --fstype=xfs  --size=4096
# 루트에 --grow 를 주지 않는다. 볼륨 그룹에 약 4GB 를 남겨 앱 배포본이 커졌을 때 늘릴 여지를 둔다.
logvol /        --vgname=vg_os --name=lv_root   --fstype=xfs  --size=14336
bootloader --boot-drive=sda --timeout=1 --append="clocksource=hpet tsc=unstable"

# kdump 를 끈다. 메모리 3GB 노드에서 예약 메모리를 아끼려는 선택이다.
# 커널 크래시 덤프를 포기하는 것이므로 장애 분석 때 한계로 인식한다.
%addon com_redhat_kdump --disable
%end

# 여섯 대가 같은 목록을 쓴다. minimal ISO 에 없는 항목은 --ignoremissing 으로 넘기고,
# 설치가 끝난 뒤 역할 스크립트의 packages 단계가 NAT 경로로 같은 목록을 다시 설치한다.
# 설치가 패키지 하나 때문에 멈추지 않게 하려는 값이다.
%packages --ignoremissing
@^minimal-environment
openssh-server
chrony
# nft 명령이 필요하다. nftables.service 는 켜지 않는다.
nftables
# semanage 와 restorecon 을 쓴다. SELinux 를 끄지 않고 포트와 문맥을 다루기 위한 패키지다.
policycoreutils-python-utils
# 감사 로그에서 SELinux 거부를 찾을 때 쓰는 ausearch 가 여기에 들어 있다.
audit
# 설치가 LVM 볼륨을 만들고 DB 두 대는 데이터 디스크를 뒤에 더 자른다.
lvm2
parted
xfsprogs
util-linux
# 자체 서명 인증서 생성과 지문 대조
openssl
tar
gzip
rsync
less
logrotate
sysstat
python3
iproute
ca-certificates
# 허용과 차단 시험, 이름 확인에 쓴다.
nmap-ncat
bind-utils
# curl 은 minimal 환경의 curl-minimal 이 /usr/bin/curl 을 제공한다.
# curl 패키지를 따로 지정하면 두 패키지가 충돌해 설치가 멈출 수 있으므로 넣지 않는다.
# VM 에는 무선 장치가 없다. 쓰지 않는 펌웨어를 설치하지 않는다.
-iwl*-firmware
-libertas-*-firmware
%end

%pre --log=/tmp/ks-pre.log
# 설치 시작 시점의 장치 이름을 남긴다. NIC 이름이 enp0s3 / enp0s8 / enp0s9 가 아니면
# 위의 network 줄이 맞지 않는다는 뜻이므로 설치 로그에서 바로 확인할 수 있게 한다.
echo "=== link ==="
ip -brief link
echo "=== block ==="
lsblk -o NAME,TYPE,SIZE,MODEL
%end

# 이 구간은 설치된 시스템 안(chroot)에서 돈다. 재부팅 직후 SSH 키 접속과 로그 보존이
# 되는 상태까지만 만든다. 방화벽 정책, node_exporter, 진단 타이머는 scripts/00-common-setup.sh
# 가 맡는다. set -e 는 쓰지 않는다. 한 명령이 실패해도 나머지 표식과 로그를 남겨야 원인을
# 찾을 수 있다. 대신 관리 접속에 필요한 항목은 아래에서 직접 확인하고, 어긋나면 exit 1 로
# 설치를 실패시킨다. %post 의 --erroronfail 이 그 실패를 설치 실패로 만든다.
%post --erroronfail --log=/root/ks-post.log
set -ux

# --- 공통 표식 --------------------------------------------------------------
# check-host.sh 와 역할 스크립트가 이 값을 읽어 대상 VM 과 구축 단계를 판정한다.
install -d -o root -g root -m 0755 /etc/vm-lab /var/log/vm-lab
# 작업 PC 에서 scp 로 바로 받을 수 있게 ops 소유로 만든다.
install -d -o ops -g ops -m 0755 /opt/vm-lab
printf 'ops\n' > /etc/vm-lab/operator
chmod 0644 /etc/vm-lab/operator
# build 는 구축 단계라는 뜻이다. 전체 sudo 를 좁힌 뒤 operate 로 바꾼다.
printf 'build\n' > /etc/vm-lab/phase
chmod 0644 /etc/vm-lab/phase
printf 'app02\n' > /etc/vm-lab/role
chmod 0644 /etc/vm-lab/role
# 20-app-setup.sh 가 대상 노드를 잘못 고르지 않도록 주소까지 남긴다.
# ROLE 값은 build-files/ops/prometheus.vm.yml 의 role 라벨과 같은 낱말을 쓴다.
cat > /etc/vm-lab-node <<'EOF'
NODE=app02
ROLE=app
LAN_IP=192.168.10.22
MGMT_IP=192.168.56.22
EOF
chmod 0644 /etc/vm-lab-node

# --- 관리 SSH ---------------------------------------------------------------
# sshd 는 같은 지시자에 대해 먼저 읽은 값을 쓴다. 배포판 기본 drop-in 이 50-redhat.conf 이므로
# 20 번을 붙여 이 값이 먼저 읽히게 한다. 파일 이름과 내용은 scripts/00-common-setup.sh 가
# 나중에 설치하는 것과 같다. 이름이 다르면 두 파일이 함께 남아 번호가 작은 쪽이 이기고,
# 공통 절차가 넣는 출발지 제한이 무시된다.
install -d -o root -g root -m 0755 /etc/ssh/sshd_config.d
cat > /etc/ssh/sshd_config.d/20-vm-lab.conf <<'EOF'
# VM 실습 관리 접속 정책. 키 인증만 허용하고 host-only 주소에만 붙는다.
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
AuthenticationMethods publickey
PubkeyAuthentication yes
PermitEmptyPasswords no
StrictModes yes
LoginGraceTime 30
MaxAuthTries 3
ClientAliveInterval 60
ClientAliveCountMax 3
LogLevel VERBOSE
X11Forwarding no
AllowAgentForwarding no
PermitTunnel no
AllowTcpForwarding no
PermitUserEnvironment no
AddressFamily inet
EOF
# 관리 대역과 바인딩 주소는 VM 마다 다르므로 덧붙인다.
# 출발지 제한은 00-주소계획.md 포트 표의 "TCP 22, 관리 대역 192.168.56.0/24" 를 따른다.
cat >> /etc/ssh/sshd_config.d/20-vm-lab.conf <<'EOF'
AllowUsers ops@192.168.56.0/24
ListenAddress 192.168.56.22
EOF
chown root:root /etc/ssh/sshd_config.d/20-vm-lab.conf
chmod 0600 /etc/ssh/sshd_config.d/20-vm-lab.conf
# 이 시점에는 호스트키가 아직 없어 sshd -t 가 실패한다. 구문 검사는 첫 부팅 후에 한다.

# ListenAddress 를 host-only 주소로 고정했으므로 그 주소가 올라오기 전에 sshd 가 뜨면
# bind 에 실패한다. 배포판 sshd.service 에는 Restart 지시자가 없어 실패한 채로 남고,
# root 와 ops 가 모두 잠긴 상태라 콘솔로도 복구할 수 없다. 네트워크가 준비된 뒤 기동하게 한다.
# 파일 이름과 내용은 scripts/00-common-setup.sh 가 설치하는 것과 같다.
install -d -o root -g root -m 0755 /etc/systemd/system/sshd.service.d
cat > /etc/systemd/system/sshd.service.d/10-wait-network.conf <<'EOF'
[Unit]
# ListenAddress 가 고정 주소라 인터페이스가 올라온 뒤에 기동해야 한다.
After=network-online.target
Wants=network-online.target
EOF
chmod 0644 /etc/systemd/system/sshd.service.d/10-wait-network.conf
systemctl enable NetworkManager-wait-online.service || true

# --- sudo -------------------------------------------------------------------
# 구축 단계의 전체 sudo 다. ops 는 비밀번호가 잠겨 있어 암호를 묻는 sudo 가 통하지 않는다.
# 이 구간에서 ops 키를 가진 사람은 root 와 같다. 구축이 끝난 뒤 필요한 명령만 남기도록 좁힌다.
# 좁히기 전에는 어디에도 "제한된 sudo 를 적용했다" 고 적지 않는다.
# 파일 이름은 scripts/00-common-setup.sh 가 설치하는 것과 같게 둔다.
umask 0077
tmp_sudoers=$(mktemp)
cat > "$tmp_sudoers" <<'EOF'
# 구축 단계 한정. 종료 후 필요한 명령만 남기도록 좁힌다.
ops ALL=(ALL) NOPASSWD: ALL
EOF
if visudo -cf "$tmp_sudoers"; then
    install -o root -g root -m 0440 "$tmp_sudoers" /etc/sudoers.d/90-vm-lab-ops
else
    printf '%s\n' '[확인 필요] sudoers 구문 검사에 실패해 /etc/sudoers.d/90-vm-lab-ops 를 설치하지 않았다.' >> /root/build-notes.txt
fi
rm -f -- "$tmp_sudoers"
umask 0022

# --- 로그 보존 --------------------------------------------------------------
# failstop/deploy/linux/journald.conf.d/60-exchange-lab.conf 를 값 변경 없이 그대로 쓴다.
install -d -o root -g root -m 0755 /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/60-exchange-lab.conf <<'EOF'
[Journal]
Storage=persistent
Compress=yes
SystemMaxUse=256M
SystemKeepFree=1G
RuntimeMaxUse=64M
MaxRetentionSec=7day
EOF
chmod 0644 /etc/systemd/journald.conf.d/60-exchange-lab.conf
# 영구 저장 디렉터리를 미리 만든다. 첫 부팅의 journald 가 이 디렉터리에 바로 쓴다.
install -d -o root -g systemd-journal -m 2755 /var/log/journal

# --- 이름 해석 --------------------------------------------------------------
# lab-lan 에는 DNS 가 없다. 여섯 대와 방화벽 주소를 적어 두면 확인 명령이 읽기 쉬워진다.
# TLS 검증은 인증서의 IP SAN 으로 하므로 이 이름에 의존하지 않는다.
# 주소는 build/00-주소계획.md 의 lab-lan 열과 같아야 한다.
cat >> /etc/hosts <<'EOF'

# BEGIN vm-lab
192.168.10.1    pfsense-fw.lab pfsense-fw
192.168.10.11   edge01.lab edge01
192.168.10.21   app01.lab app01
192.168.10.22   app02.lab app02
192.168.10.31   db01.lab db01
192.168.10.32   db02.lab db02
192.168.10.41   ops01.lab ops01
# END vm-lab
EOF

# --- 서비스 상태 ------------------------------------------------------------
systemctl enable sshd.service chronyd.service
# firewalld 와 배포판 nftables.service 는 지우지 않고 비활성으로 둔다. 설치 여부와 상태를
# 증거에 남길 수 있게 한다. 규칙은 vm-lab-firewall.service 하나가 넣는다.
systemctl disable firewalld.service || true
systemctl mask firewalld.service || true
systemctl disable nftables.service || true
systemctl mask nftables.service || true

# --- SELinux 문맥 -----------------------------------------------------------
# %post 에서 만든 파일은 라벨이 맞지 않을 수 있다. enforcing 상태에서 키 인증이 실패하는
# 가장 흔한 원인이므로 여기서 복구한다.
restorecon -R /home/ops /etc/ssh/sshd_config.d /etc/sudoers.d /etc/hosts /etc/vm-lab /opt/vm-lab 2>/dev/null || true

# --- 관리 키 배치 확인 ------------------------------------------------------
# sshkey 지시자가 %post 보다 먼저 ops 의 authorized_keys 를 만든다. 그 파일이 없거나 공개키
# 형식이 아니면 root 와 ops 가 모두 잠긴 상태라 SSH 도 VirtualBox 콘솔도 들어갈 수 없다.
# 그런 VM 을 설치 완료로 남기지 않는다. 여기서 멈추고 다시 설치하는 쪽이 낫다.
ops_keys=/home/ops/.ssh/authorized_keys
if [ ! -s "$ops_keys" ] || ! grep -qE '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-)[[:space:]]' "$ops_keys"; then
    printf '%s\n' \
      '[치명] ops 의 authorized_keys 가 없거나 공개키 형식이 아니다. 설치를 중단한다.' \
      '01-create-vms.ps1 이 @@OPS_SSH_PUBKEY@@ 를 치환했는지 확인한 뒤 다시 설치한다.' \
      >> /root/build-notes.txt
    exit 1
fi
install -d -o ops -g ops -m 0700 /home/ops/.ssh
chown ops:ops "$ops_keys"
chmod 0600 "$ops_keys"
restorecon -R /home/ops/.ssh 2>/dev/null || true

# --- 다음 작업 안내 ---------------------------------------------------------
cat > /etc/motd <<'EOF'
이 VM 은 kickstart 기본 설치만 끝난 상태다. 역할은 APP02 거래 앱이다.
방화벽 기본 차단, node_exporter, Java, 거래 앱은 아직 없다.
  1) scripts/00-common-setup.sh 를 --plan 으로 본 뒤 --apply 로 적용한다.
  2) scripts/20-app-setup.sh 로 Java 와 거래 앱을 올린다.
EOF

cat >> /root/build-notes.txt <<'EOF'

APP02 설치 후 순서
 1. 작업 PC 에서 ssh -i %USERPROFILE%\.ssh\vm-lab_ed25519 ops@192.168.56.22 로 접속한다.
 2. 작업 PC 의 build 폴더를 /opt/vm-lab 로 복사한다. keys\ 는 제외한다.
 3. sudo /opt/vm-lab/scripts/00-common-setup.sh --plan 으로 본 뒤 --apply 로 공통 기반을 올린다.
 4. DB01 의 스키마가 먼저 적용돼 있어야 앱이 기동한다. APP01 이 만드는 /etc/exchange/.migrated
    표식은 APP02 에서 볼 수 없으므로, DB 에 직접 질의해 스키마를 확인한 뒤 다음으로 넘어간다.
 5. sudo /opt/vm-lab/scripts/20-app-setup.sh 2 로 앱을 올린다.
 6. verify 출력과 화면을 증거로 남긴다.
자세한 절차는 build/문서/APP_구축절차.md 에 있다.
EOF

# 설치 시점의 실제 적용값을 남긴다. 나중에 무엇이 들어갔는지 대조할 근거가 된다.
{
    echo "=== ks-post 완료 $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
    echo "--- 인터페이스 ---"
    ip -brief address || true
    echo "--- sshd drop-in 목록 (읽는 순서 확인용) ---"
    ls -l /etc/ssh/sshd_config.d/ || true
    echo "--- 파일시스템 ---"
    lsblk -o NAME,TYPE,SIZE,MOUNTPOINT || true
    echo "--- 설치된 커널 ---"
    rpm -q kernel || true
} >> /root/ks-post-state.log 2>&1
chmod 0600 /root/ks-post-state.log

# 첫 부팅 후 확인하지 못한 항목
#  1. sshd -T 의 최종 적용값. 배포판 기본 drop-in 과 읽는 순서를 실제로 대조해야 한다.
#  2. ListenAddress 고정 상태에서 sshd 가 기동에 성공하는지.
#  3. minimal ISO 에 policycoreutils-python-utils, audit, nmap-ncat 가 들어 있는지.
#  4. RHEL 10 계열에서 위 kickstart 명령이 모두 유효한지. 특히 timezone --utc 와
#     UEFI 부팅과 /boot/efi 파티션을 설치 로그로 확인해야 한다.
#  5. AppStream 에서 java-21-openjdk-headless 를 받을 수 있는지. NAT 가 살아 있는 동안에만 가능하다.
exit 0
%end
