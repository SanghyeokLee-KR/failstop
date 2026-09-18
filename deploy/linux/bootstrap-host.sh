#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$here/common.sh"

mode=plan
operator=failstop-ops
key=
admin_cidr=
ssh_address=127.0.0.1
ssh_port=2222
install_packages=false
while (($#)); do
    case "$1" in
        --plan|--apply) mode=${1#--}; shift ;;
        --operator) operator=${2:?}; shift 2 ;;
        --public-key) key=${2:?}; shift 2 ;;
        --admin-cidr) admin_cidr=${2:?}; shift 2 ;;
        --ssh-address) ssh_address=${2:?}; shift 2 ;;
        --ssh-port) ssh_port=${2:?}; shift 2 ;;
        --install-packages) install_packages=true; shift ;;
        *) die "Unknown argument: $1" ;;
    esac
done
validate_user "$operator"
[[ $operator != failstop ]] || die 'Operator and service accounts must differ.'
validate_ipv4 "$ssh_address"
[[ $ssh_address != 0.0.0.0 ]] || die 'Bind SSH to a specific local address.'
validate_port "$ssh_port"
[[ $ssh_port != 22 ]] || die 'The separate SSH service must not replace port 22.'
validate_cidr "$admin_cidr"
[[ -f $key && ! -L $key ]] || die 'Provide a regular public-key file.'
command -v ssh-keygen >/dev/null || die 'ssh-keygen is required to validate the public key.'
[[ $(awk 'NF { n++ } END { print n+0 }' "$key") == 1 ]] || die 'Provide exactly one public key.'
[[ $(awk '{print $1}' "$key") == ssh-ed25519 ]] || die 'Use an Ed25519 public key without authorized_keys options.'
ssh-keygen -l -f "$key" >/dev/null || die 'Invalid SSH public key.'
source /etc/os-release
case "$ID" in ubuntu|rocky) ;; *) die 'Only Ubuntu and Rocky Linux are supported.' ;; esac
printf 'mode=%s os=%s operator=%s ssh=%s:%s admin=%s packages=%s\n' \
    "$mode" "$ID" "$operator" "$ssh_address" "$ssh_port" "$admin_cidr" "$install_packages"
[[ $mode == apply ]] || exit 0
require_root
require_systemd

# 기존 사용자에게 남아 있는 광범위 권한을 조용히 지우거나 새 계정으로 오인하지 않는다.
if id "$operator" >/dev/null 2>&1; then
    [[ $(id -u "$operator") != 0 ]] || die 'Operator must not use UID 0.'
    for group in $(id -nG "$operator"); do
        case "$group" in root|sudo|wheel|docker|lxd|incus-admin|adm|systemd-journal)
            die 'Existing operator has privileged group membership. Use a dedicated account.' ;;
        esac
    done
    [[ $(getent passwd "$operator" | cut -d: -f6) == "/home/$operator" ]] || die 'Unexpected operator home.'
fi
if id failstop >/dev/null 2>&1; then
    [[ $(id -u failstop) != 0 ]] || die 'Service account must not use UID 0.'
    [[ $(getent passwd failstop | cut -d: -f7) == */nologin ]] || die 'Existing failstop account permits login.'
    for group in $(id -nG failstop); do
        case "$group" in root|sudo|wheel|docker|lxd|incus-admin|adm|systemd-journal)
            die 'Existing service account has privileged group membership.' ;;
        esac
    done
fi

if $install_packages; then
    if [[ $ID == ubuntu ]]; then
        # sshd가 처음 설치될 때 패키지가 공개 SSH 소켓을 자동 시작하지 못하게 한다.
        # 이미 설치된 관리 SSH 서비스의 상태와 설정은 바꾸지 않는다.
        if ! command -v sshd >/dev/null; then
            systemctl mask ssh.service ssh.socket
        fi
        apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server sudo nftables logrotate sysstat curl ca-certificates rsync python3 iproute2 coreutils tar
    else
        if ! command -v sshd >/dev/null; then
            systemctl mask sshd.service
        fi
        dnf install -y openssh-server sudo nftables logrotate sysstat curl ca-certificates rsync python3 iproute coreutils tar policycoreutils-python-utils
    fi
fi
for command in sshd nft visudo logrotate iostat rsync; do
    command -v "$command" >/dev/null || die "Missing $command; rerun with --install-packages."
done

id "$operator" >/dev/null 2>&1 || useradd --create-home --shell /bin/bash "$operator"
usermod --lock "$operator"
id failstop >/dev/null 2>&1 || useradd --system --user-group --home-dir /var/lib/failstop --shell /usr/sbin/nologin failstop
usermod --lock failstop
install -d -o root -g root -m 0755 /opt/failstop /opt/failstop/linux /etc/failstop /var/lib/failstop /var/log/failstop
install -d -o root -g root -m 0755 /etc/failstop/keys
install -d -o root -g root -m 0755 /etc/systemd/journald.conf.d /etc/systemd/system /run/sshd
# 공개키는 인증 대상 사용자가 읽을 수 있어야 한다. 비밀 환경 파일과 달리 공개 자료다.
install -o root -g root -m 0644 "$key" "/etc/failstop/keys/$operator"
for script in common.sh check-host.sh compose-runner.sh operator-control.sh firewall-host.sh; do
    install -o root -g root -m 0755 "$here/$script" "/opt/failstop/linux/$script"
done
install -o root -g root -m 0755 "$here/operator-control.sh" /usr/local/sbin/failstop-control
install -o root -g root -m 0644 "$here/journald.conf" /etc/systemd/journald.conf.d/60-failstop.conf
install -o root -g root -m 0644 "$here/logrotate.conf" /etc/logrotate.d/failstop
install -o root -g root -m 0644 "$here/systemd/"* /etc/systemd/system/

temporary=$(mktemp -d)
trap 'rm -rf -- "$temporary"' EXIT
cat > "$temporary/sshd_config" <<EOF
Port $ssh_port
AddressFamily inet
ListenAddress $ssh_address
HostKey /etc/ssh/ssh_host_ed25519_key
PidFile /run/failstop-ssh.pid
AuthorizedKeysFile /etc/failstop/keys/%u
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
AuthenticationMethods publickey
PubkeyAuthentication yes
PermitEmptyPasswords no
UsePAM yes
AllowUsers $operator@$admin_cidr
DisableForwarding yes
PermitUserEnvironment no
PermitUserRC no
StrictModes yes
LoginGraceTime 30
MaxAuthTries 3
ClientAliveInterval 60
ClientAliveCountMax 3
LogLevel VERBOSE
Subsystem sftp internal-sftp
EOF
ssh-keygen -A
sshd -t -f "$temporary/sshd_config"
printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/failstop-control\n' "$operator" > "$temporary/sudoers"
visudo -cf "$temporary/sudoers"
install -o root -g root -m 0600 "$temporary/sshd_config" /etc/failstop/sshd_config
install -o root -g root -m 0440 "$temporary/sudoers" /etc/sudoers.d/failstop-operator
printf '%s\n' "$operator" > /etc/failstop/operator
chmod 0644 /etc/failstop/operator

if [[ $ID == rocky ]] && command -v getenforce >/dev/null && [[ $(getenforce) != Disabled ]]; then
    if ! semanage port -l | awk '$1 == "ssh_port_t" && $2 == "tcp" { print }' | grep -Eq "(^|[ ,])$ssh_port([, ]|$)"; then
        semanage port -a -t ssh_port_t -p tcp "$ssh_port"
    fi
    semanage fcontext -a -t ssh_home_t '/etc/failstop/keys(/.*)?' 2>/dev/null || \
        semanage fcontext -m -t ssh_home_t '/etc/failstop/keys(/.*)?'
    restorecon -R /etc/failstop/keys
fi
systemd-tmpfiles --create --prefix /var/log/journal
systemctl daemon-reload
systemd-analyze verify /etc/systemd/system/failstop-ssh.service /etc/systemd/system/failstop-host-check.service /etc/systemd/system/failstop-host-check.timer
systemctl restart systemd-journald
journalctl --flush
systemctl enable failstop-ssh.service failstop-host-check.timer
systemctl restart failstop-ssh.service failstop-host-check.timer
systemctl start failstop-host-check.service
printf 'Applied. Verify SSH key login and denied root/password access before relying on this account.\n'
