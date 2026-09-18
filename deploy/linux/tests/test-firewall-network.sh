#!/usr/bin/env bash
# 별도 mount/network namespace 안에서 INPUT과 DNAT/FORWARD 허용·거부를 실제 접속으로 판정한다.
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
[[ $(id -u) == 0 ]] || { printf 'Run as root with --apply.\n' >&2; exit 1; }
case "${1:-}" in
    --apply)
        for command in unshare ip nft python3 mount; do command -v "$command" >/dev/null; done
        exec unshare --mount --net --fork --propagation private bash "$0" --inside ;;
    --inside)
        [[ $(readlink /proc/self/ns/net) != "$(readlink /proc/1/ns/net)" ]]
        [[ $(readlink /proc/self/ns/mnt) != "$(readlink /proc/1/ns/mnt)" ]] ;;
    *) printf 'Usage: test-firewall-network.sh --apply\n' >&2; exit 1 ;;
esac
temporary=$(mktemp -d)
trap 'kill $(jobs -pr) 2>/dev/null || true; rm -rf -- "$temporary"' EXIT
mkdir -p /run/netns
mount -t tmpfs tmpfs /run/netns
ip link set lo up
ip netns add client
ip netns add backend
ip link add client-host type veth peer name client-peer
ip link set client-peer netns client
ip address add 192.0.2.10/24 dev client-host
ip link set client-host up
ip -n client address add 192.0.2.20/24 dev client-peer
ip -n client address add 192.0.2.21/24 dev client-peer
ip -n client address add 192.0.2.22/24 dev client-peer
ip -n client link set client-peer up
ip -n client link set lo up
ip link add backend-host type veth peer name backend-peer
ip link set backend-peer netns backend
ip address add 198.51.100.1/24 dev backend-host
ip link set backend-host up
ip -n backend address add 198.51.100.2/24 dev backend-peer
ip -n backend link set backend-peer up
ip -n backend link set lo up
ip -n backend route add default via 198.51.100.1
sysctl -q -w net.ipv4.ip_forward=1
cat > "$temporary/listen.py" <<'PY'
import socket
import sys

with socket.socket() as server:
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((sys.argv[1], int(sys.argv[2])))
    server.listen()
    while True:
        connection, _ = server.accept()
        with connection:
            connection.sendall(b"ready\n")
PY
python3 "$temporary/listen.py" 192.0.2.10 8080 &
ip netns exec backend python3 "$temporary/listen.py" 198.51.100.2 80 &
for attempt in $(seq 1 50); do
    if ss -lnt | grep -q ':8080 ' && ip netns exec backend ss -lnt | grep -q ':80 '; then break; fi
    sleep 0.1
done

bash "$here/firewall-host.sh" --plan --role app --admin-cidr 192.0.2.20/32 \
    --monitor-cidr 192.0.2.20/32 --client-cidr 192.0.2.20/32 --health-cidr 192.0.2.22/32 > "$temporary/firewall.nft"
nft --check -f "$temporary/firewall.nft"
nft -f "$temporary/firewall.nft"
# 같은 규칙을 다시 적용해도 테이블·체인이 중복되지 않아야 한다.
nft -f "$temporary/firewall.nft"
nft -f - <<'NFT'
table ip test_nat {
 chain prerouting {
  type nat hook prerouting priority dstnat; policy accept;
  ip daddr 192.0.2.10 tcp dport 8081 dnat to 198.51.100.2:80
 }
}
NFT
cat > "$temporary/probe.py" <<'PY'
import socket
import sys

source, port, expected = sys.argv[1], int(sys.argv[2]), sys.argv[3]
connected = False
with socket.socket() as client:
    client.settimeout(1)
    client.bind((source, 0))
    try:
        client.connect(("192.0.2.10", port))
        connected = client.recv(16) == b"ready\n"
    except (TimeoutError, ConnectionRefusedError, OSError):
        pass
if connected != (expected == "allow"):
    raise SystemExit(f"FAIL source={source} port={port} expected={expected} connected={connected}")
print(f"PASS source={source} port={port} expected={expected}")
PY
ip netns exec client python3 "$temporary/probe.py" 192.0.2.20 8080 allow
ip netns exec client python3 "$temporary/probe.py" 192.0.2.21 8080 deny
ip netns exec client python3 "$temporary/probe.py" 192.0.2.20 8081 allow
ip netns exec client python3 "$temporary/probe.py" 192.0.2.21 8081 deny
ip netns exec client python3 "$temporary/probe.py" 192.0.2.22 8080 deny
ip netns exec client python3 "$temporary/probe.py" 192.0.2.22 8081 allow
nft list table inet failstop
printf 'PASS: isolated host INPUT and published-port DNAT/FORWARD access checks.\n'
