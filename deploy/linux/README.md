# Linux 호스트 설치와 운영 점검

학교 EC2는 Amazon Linux 2023에 직접 설치한 `app/`, `bot/`, `postgresql/`, `observability/`, `reporting/` 설정을 사용한다. 현재 호스트·DB 역할·실행 명령은 [AWS 작업 안내](../../docs/reports/AWS_작업.md)를 먼저 읽는다. 아래 Ubuntu·Rocky bootstrap과 Docker 제어 절차는 별도 로컬 실습 구현이며, 학교 서버에 실행했던 명령으로 혼동하거나 기존 서비스에 그대로 적용하지 않는다.

이 폴더는 Ubuntu 실습 환경과 Rocky Linux 운영 호스트의 계정, 별도 SSH 접속, 서비스 자동 기동, 로그 보존과 역할별 방화벽을 구성한다. 기본 명령은 변경 내용을 미리 보여 주며 `--apply`를 붙였을 때만 설치한다. 실제 적용 결과는 [로드맵](../../docs/ROADMAP.md)에서 관리한다. 이 설정을 작성한 것과 AWS에 적용한 것은 구분한다.

운영자 계정 `failstop-ops`에는 Docker 그룹이나 일반 관리자 그룹을 주지 않는다. 공개키로 로그인한 뒤 등록된 역할의 서비스 상태 조회와 재시작만 `sudo failstop-control`로 수행한다. 진단 timer는 로그인할 수 없는 `failstop` 계정으로 실행한다. Docker를 제어하는 Compose 서비스는 root로 실행하고, 해당 구성과 환경 파일은 root만 변경할 수 있게 둔다. 컨테이너 내부 실행 사용자는 각 이미지의 설정을 따른다.

## Ubuntu WSL에서 설치

PID 1이 systemd인지 먼저 확인한다. WSL 안의 서비스 활성화는 해당 배포판이 시작될 때 적용된다. Windows가 시작됐다는 이유만으로 WSL이 계속 가동된다고 보장하지 않으며, systemd 서비스도 WSL 인스턴스를 유지하지 않는다. [Microsoft WSL systemd 안내](https://learn.microsoft.com/en-us/windows/wsl/systemd)

```powershell
wsl --list --verbose
wsl -d Ubuntu -- sh -lc 'cat /etc/os-release; uname -m; cat /proc/1/comm'
```

공개키는 기존 개인키의 `.pub` 파일을 준비하거나 별도로 생성한다. 개인키는 저장소에 넣지 않는다. 아래 Linux 셸 명령의 공개키 경로는 준비한 파일로 바꾼다. 설치는 root 권한과 패키지 저장소 접속이 필요하다.

```bash
cd /mnt/d/dev/failstop
bash deploy/linux/tests/test-linux.sh
sudo bash deploy/linux/bootstrap-host.sh --plan \
  --public-key /tmp/failstop-operator.pub --admin-cidr 127.0.0.1/32
sudo bash deploy/linux/bootstrap-host.sh --apply --install-packages \
  --public-key /tmp/failstop-operator.pub --admin-cidr 127.0.0.1/32
```

SSH는 `127.0.0.1:2222`의 `failstop-ssh.service`로 실행한다. 기존 22번 포트의 SSH 구성과 기존 사용자를 바꾸지 않는다. OpenSSH가 처음 설치되는 호스트는 패키지의 기본 SSH 서비스와 소켓을 mask해 의도하지 않은 22번 포트가 열리지 않게 한다. 이미 설치된 SSH 서비스에는 이 처리를 하지 않는다.

별도 SSH는 Ed25519 공개키 한 개, 지정한 운영자와 관리 CIDR만 허용한다. root 로그인, 비밀번호 인증, 키보드 인증과 forwarding은 비활성화한다. `/etc/failstop/keys`는 root 소유의 읽기 가능한 공개키 디렉터리이며 운영자가 내용을 바꿀 수 없다. 실제 비밀 파일은 `/etc/failstop/ROLE.env`의 root:root 0600 파일이다.

```powershell
ssh -p 2222 -i <개인키_경로> failstop-ops@127.0.0.1
```

첫 접속에서 보여 주는 호스트키 지문은 WSL root가 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`로 조회한 값과 비교한다. 호스트키 검증을 끄거나 지문 확인을 생략하는 옵션을 기본 절차에 넣지 않는다. Windows의 WSL localhost 전달이 다른 출발지 주소를 사용하면 로그인은 거절된다. 이때 ssh journal에서 실제 출발지와 WSL 네트워크 모드를 확인하고, 전체 대역을 열지 말고 필요한 관리 CIDR을 명시한다.

## 역할별 방화벽

방화벽은 전용 nftables 테이블 `inet failstop`만 갱신한다. 호스트의 기존 기본 정책이나 다른 테이블을 지우지 않는다. 지정한 서비스 포트는 허용 CIDR 이외의 접근을 IPv4와 IPv6 모두 차단한다. 기존 관리 포트 22와 관련 없는 서비스는 이 규칙의 범위 밖에 있다. `/0` 입력은 거절한다.

| 역할 | 접근을 허용할 출발지 |
|---|---|
| 공통 SSH 2222 | `--admin-cidr` |
| 공통 exporter 9100 | `--monitor-cidr` |
| app·reader API 8080 | `--client-cidr`, AWS에서는 ALB가 위치한 사설 대역 |
| app·reader 관리 8081 | `--monitor-cidr` |
| data·standby PostgreSQL 5432 | `--client-cidr`, `--replica-cidr` |
| data·standby PostgreSQL exporter 9187 | `--monitor-cidr` |
| bot·observability 봇 관리 8080 | `--monitor-cidr` |
| bot·observability 운영 보고 9088 | `--monitor-cidr` |
| bot·observability Grafana 53001, Prometheus 59090, Alertmanager 59093 | `--admin-cidr` |

포트를 바꾸면 `--ssh-port`, `--api-port`, `--management-port`, `--db-port`, `--exporter-port`, `--postgres-exporter-port`, `--reporter-port`, `--grafana-port`, `--prometheus-port`, `--alertmanager-port`를 실제 호스트의 공개 포트에 맞춘다. 같은 호스트에 여러 역할을 임의로 겹쳐 올리지 않는다. 봇과 관측은 같은 호스트 역할로 함께 허용한다. 사설망의 CIDR 기반 호스트 규칙은 AWS의 보안 그룹별 출발지 통제를 대신하지 않는다.

```bash
sudo bash deploy/linux/firewall-host.sh --plan --role app \
  --admin-cidr 127.0.0.1/32 --monitor-cidr 127.0.0.1/32 --client-cidr 127.0.0.1/32
sudo bash deploy/linux/firewall-host.sh --apply --role app \
  --admin-cidr 127.0.0.1/32 --monitor-cidr 127.0.0.1/32 --client-cidr 127.0.0.1/32
```

Docker의 published port는 DNAT 뒤에 INPUT 체인을 지나지 않을 수 있다. 따라서 이 구성은 FORWARD 체인에서 연결의 원래 목적지 포트도 검사한다. UFW 설정 목록만으로 컨테이너 접근 차단을 판단하지 않는다. 직접 라우팅한 컨테이너 주소나 macvlan 네트워크는 이 DNAT 검사 범위가 아니므로 사용하지 않는다. [Docker 방화벽 안내](https://docs.docker.com/engine/network/packet-filtering-firewalls/), [nftables 연결 추적 필드](https://wiki.nftables.org/wiki-nftables/index.php/Matching_connection_tracking_stateful_metainformation)

`nft --check`가 실패하면 적용하지 않는다. `failstop-firewall.service`는 재기동 때 저장한 규칙을 다시 읽으며 서비스를 중지해도 규칙을 삭제하지 않는다. 기존에 연결된 세션은 유지한다. 허용·거절 시험은 새로운 연결로 각각 수행한다. 다른 방화벽이 먼저 차단하는 경로를 이 규칙의 `accept`가 강제로 열지는 않는다.

`sudo bash deploy/linux/tests/test-firewall-network.sh --apply`는 별도 network·mount namespace에서 두 출발지 주소의 접속을 비교한다. 호스트 INPUT과 Docker published port를 모사한 DNAT/FORWARD 경로의 허용·거절을 모두 실제 TCP 접속으로 판정하고 같은 규칙의 재적용도 검사한다. 호스트의 방화벽과 네트워크를 바꾸지 않는다. 이 시험이 통과해도 실제 AWS 보안 그룹과 호스트 사이의 검증은 남는다.

`--health-cidr`를 반복해서 앱·reader 8081에 ALB 상태 점검 출발지를 추가할 수 있다. 이 주소에는 호스트 exporter 접근을 주지 않는다. `--monitor-cidr`도 반복할 수 있다. 같은 호스트의 관측 컨테이너에서 exporter를 호출할 때는 확인한 bridge CIDR를 별도로 넣는다. reporter 9088과 DB exporter 9187은 해당 역할의 monitor CIDR만 허용한다.

## Compose 역할 설치

Docker Engine과 Compose 플러그인은 호스트에서 먼저 설치·검증한다. AWS 이미지 digest, 역할별 DB·인증·프록시 값과 스키마 적용 순서는 [배포 안내](../README.md)를 따른다. Linux bootstrap은 실제 운영 비밀 값을 생성하거나 저장소의 `.env`를 자동 복사하지 않는다.

```bash
sudo bash deploy/linux/install-service.sh --plan --role app --env-file /root/app.env
sudo bash deploy/linux/install-service.sh --apply --role app --env-file /root/app.env --start
sudo /opt/failstop/linux/compose-runner.sh status app
sudo failstop-control status app
sudo failstop-control restart app
```

설치기는 [허용한 소스](runtime-files.py)만 `/opt/failstop`에 복사하고 입력한 환경 파일 하나를 `/etc/failstop/app.env`에 0600으로 설치한다. 숨김 상태·env·런타임 JSON·덤프를 복사하지 않으며 standby entrypoint 등 셸 스크립트는 0755로 둔다. 심볼릭 링크나 다른 사용자가 쓰는 배포 파일은 실행 전에 거절한다. 환경 파일은 `source`하지 않고 Compose의 `--env-file`로 읽는다. 설정 내용과 컨테이너 환경 전체를 출력하지 않는다.

시작 전 [배포 설정 검사기](../check-config.py)가 역할별 안전 조건을 검사한다. 로컬에서 빌드한 태그 이미지를 쓸 때만 설치 명령에 `--local-image`를 명시한다. 이 선택은 root 소유의 역할별 `.image-mode`에 저장되며, 해당 옵션 없이 다시 설치하면 production 검사로 돌아간다. 환경 변수만으로 digest 검사를 자동 우회하지 않는다.

지원 역할은 `app`, `reader`, `data`, `standby`, `bot`, `observability`다. reader는 `compose.node-read.yml`의 `read` 서비스를 사용하며 엔진 비활성화, 조회 역할과 별도 DB 계정을 적용한다. ALB 라우팅과 애플리케이션의 쓰기 거절도 함께 확인한다. observability는 `compose.node-observability.yml`을 사용하며 참조하는 기존 Prometheus 규칙·Grafana 대시보드·blackbox 설정도 `/opt/failstop/harness`에 함께 복사한다.

`failstop@ROLE.service`는 부팅 시 `compose up -d --wait`를 실행하고 중지 시 `compose stop`을 실행한다. 볼륨을 삭제하지 않는다. `active (exited)`는 시작 명령이 완료됐다는 뜻이며 이후 컨테이너 건강이나 API 준비 상태의 증거가 아니다. 컨테이너 restart 정책과 실제 HTTP·DB·관측 검사도 함께 확인한다. Docker daemon이 같은 Linux의 `docker.service`로 실행되는 호스트가 대상이며 Docker Desktop의 외부 daemon 연결은 이 서비스의 재부팅 검증을 대신하지 않는다. [systemd 서비스 형식](https://manpages.ubuntu.com/manpages/noble/man5/systemd.service.5.html)

## 로그와 진단

`failstop-host-check.timer`는 5분마다 계정이 아닌 호스트 상태를 읽는다. OS·PID 1, 파일시스템, 디스크 용량·inode, 블록 장치, 메모리, I/O, 인터페이스·경로·LISTEN 포트, SSH와 timer 상태를 기록한다. 실패한 수집은 `status=ERROR`, 마지막은 `collection_success=0`과 실패 종료코드로 남는다. 디스크 포맷·파티션·마운트 변경이나 서비스를 자동 복구하는 명령은 없다.

```bash
bash deploy/linux/check-host.sh --inspect
sudo bash deploy/linux/check-host.sh --verify
sudo journalctl -u failstop-ssh.service --since '-10 min' --no-pager
sudo journalctl -u failstop-host-check.service --since '-10 min' --no-pager
sudo systemctl list-timers failstop-host-check.timer
sudo nft list table inet failstop
```

`--verify`는 설치 후 점검이다. SSH 구문, 서비스 활성화, 로그 저장, 계정·그룹·sudo 범위, 환경 파일 권한과 방화벽 설치를 확인한다. root의 0600 시험 파일을 운영자가 읽지 못하는지, 임의 sudo가 거절되는지도 실제로 검사한다. SSH 비밀번호·잘못된 키 거절과 다른 출발지의 네트워크 차단은 별도 접속 시험이 필요하다.

로컬 WSL 설치 후에는 다음 명령으로 해당 거절 조건과 서비스 재시작을 함께 검사한다. 호스트의 공개키로 검증 파일을 만들고 `StrictHostKeyChecking=yes`를 사용한다. 127.0.0.1:2222의 실습 서비스만 대상으로 하며 기존 22번 SSH를 재시작하지 않는다. 이 명령은 app 역할의 loopback 방화벽을 적용하고 진단 timer와 실습 SSH를 재시작한다. `--network-test`는 앞서 설명한 격리 네트워크 시험도 실행한다.

```bash
sudo bash deploy/linux/tests/test-live.sh --apply \
  --private-key /home/kopo26/.ssh/failstop-lab-ops --network-test
```

journal은 persistent 저장과 256 MiB 사용 상한, 512 MiB 여유 공간, 14일 보존을 요청한다. 다른 journald 설정의 적용 순서와 실제 저장량도 `systemd-analyze cat-config systemd/journald.conf`, `journalctl --disk-usage`로 확인한다. 진단 파일 `/var/log/failstop/host-check.log`는 일일 또는 logrotate 실행 시 10 MiB 초과 조건으로 회전하며 7개를 보존한다. 이미 끝난 oneshot 진단은 파일을 계속 붙잡고 있지 않다. Docker 로그 제한은 해당 Compose 설정을 따른다. [journald 저장 설정](https://manpages.ubuntu.com/manpages/noble/man5/journald.conf.5.html)

## Ubuntu와 Rocky의 차이

| 항목 | Ubuntu 실습 | Rocky 운영 준비 |
|---|---|---|
| 패키지 | apt, iproute2 | dnf, iproute |
| 기존 SSH | ssh.service·ssh.socket | sshd.service |
| 기본 접근 제어 | 호스트의 기존 UFW/AppArmor 상태 확인 | firewalld와 SELinux 적용 상태 확인 |
| 이 폴더의 방화벽 | 별도 nftables 테이블 | 같은 테이블, 기존 firewalld와 함께 검사 |
| 추가 SSH 포트 | 별도 sshd 구성 | SELinux 활성 시 ssh_port_t 포트와 공개키 파일 문맥 추가 |
| CPU·부팅 | 확인한 WSL 배포판 기준 | AMI·ARM64 이미지 digest와 실제 EC2 부팅 검증 필요 |

Rocky에서도 기존 SSH 설정과 SELinux 활성 상태를 유지한다. 포트가 다른 SELinux 서비스 타입에 이미 속하면 bootstrap은 그 타입을 강제로 바꾸지 않고 실패한다. public key 접속 성공, 원치 않는 조작의 거절, 재기동 뒤 서비스 상태를 확인해야 해당 호스트의 적용 검증이 끝난다.
