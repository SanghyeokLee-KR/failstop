# OPS01 관측 구축 절차

OPS01 한 대에 Prometheus, Grafana, Alertmanager, node_exporter를 Rocky Linux 10.2 minimal에 직접 설치하고 systemd로 운영하는 절차다. 무인 설치 답안 파일 `ks/ops01.ks`로 OS를 올리고, `scripts/40-ops01-observability.sh`로 관측 도구를 올린 다음, 작업 PC에서 SSH 터널로 관제 화면을 여는 데까지를 다룬다.

주소와 포트는 [주소 계획](../00-주소계획.md)의 값만 쓴다. 설계 근거는 `아키텍쳐/교수님_보고본/06_VM_운영환경`의 `Linux_VM_운영환경_구성도.svg`와 `VM_네트워크_접근제어도.svg`다. 설정 파일은 대부분 `failstop/deploy/linux` 아래의 학교 AWS 자산을 그대로 가져왔고, 바꾼 자리는 각 파일의 머리말 주석에 적었다.

이 문서는 절차다. 2026-09-22에 이 절차로 OPS01의 관측 역할을 설치했고, 실제 출력은 [역할 설치 캡처](../../캡처/역할설치/)의 `OPS01_*.txt`에 있다. 각 단계의 기대 출력은 "실행하면 이렇게 보여야 한다"는 기준이며 관측한 결과가 아니다. 결과는 캡처 파일을 사실로 보고, 기대와 다르면 그 자리에서 멈추고 원인을 적는다. 보고 역할(`OPS01_보고_구축절차.md`)은 아직 설치하지 않았다.

OPS01 관측 문서는 이 파일 하나다. 설계 근거와 학교 AWS 값 대조표는 아래 "설계에서 의도적으로 벗어난 곳" 절에 함께 담았다. 다른 다섯 역할과 같은 구성이다.

## 이 역할이 맡는 것과 맡지 않는 것

맡는 것은 OPS01 한 대다. Prometheus, Grafana, Alertmanager, OPS01 자신의 node_exporter, 그리고 이 네 가지를 감싸는 nftables 규칙과 화면 프로비저닝이다.

맡지 않는 것은 나머지 다섯 VM의 node_exporter 설치, DB 두 대의 postgres_exporter, 앱의 Actuator 바인딩, AI 운영 보고 서비스다. 각 역할이 끝나야 해당 수집 대상이 up이 된다. 그 전까지의 down은 예상된 상태이며 증거에도 그렇게 적는다. pfSense는 사용자가 직접 구축하므로 여기서 다루지 않는다.

## 산출물

| 경로 | 쓰임 |
|---|---|
| `ks/ops01.ks` | OPS01 무인 설치 답안 파일 |
| `scripts/40-ops01-observability.sh` | 설치·설정·기동·검증 스크립트 |
| `build-files/ops/prometheus.vm.yml` | 수집 설정. `/etc/prometheus/prometheus.yml`로 간다 |
| `build-files/ops/alertmanager.vm.yml` | Alertmanager 최종 설정. 비밀 파일을 만든 뒤에 적용한다 |
| `build-files/ops/linux-dashboard.vm.json` | Linux 운영 화면. 작업 이름을 `linux-host`로 바꾼 사본 |
| `build-files/ops/make-vm-linux-dashboard.py` | 위 사본을 다시 만드는 변환기 |
| `build-files/ops/` 나머지 | 저장소에서 값 변경 없이 가져온 서비스·설정 파일 |
| `scripts/ops01/msp-dashboards-vm/` | MSP 화면 7개. VM 라벨과 `role` 정규식을 바꾼 사본 |
| `scripts/ops01/` 나머지 | 설계 단계에서 만든 사본. `ops01-firewall.nft`는 쓰지 않는다 |

스크립트는 설치 원본을 `build-files/ops`에서 먼저 찾고, 없으면 `scripts/ops01`에서 찾는다. 같은 내용이 두 곳에 있으면 `preflight`가 차이를 알린다. 한쪽만 고치고 지나가지 않게 하려는 장치다.

## 1단계. VM 생성과 OS 설치

설치는 `01-create-vms.ps1` 하나로 한다. 이 스크립트가 VM을 만들고 `ks/ops01.ks`를 답안 파일로 넘겨 무인 설치를 건다.

```powershell
pwsh -NoProfile -File C:\Users\kopo26\Desktop\vm-lab\build\scripts\01-create-vms.ps1 -Role ops01
```

`inst.ks=http://...`로 작업 PC에 간이 HTTP 서버를 띄워 답안 파일을 전달하는 경로는 쓰지 않는다. `ks-ops01.cfg`나 `ops01.generated.ks` 같은 두 번째 세트도 만들지 않는다. `ks/base.ks.template`은 설계 근거를 읽을 때만 보고 그 파일로 설치하지 않는다. 템플릿에는 `10-ops01-tunnel.conf`와 `/etc/vm-lab/role` 표식이 없어서, 그쪽으로 설치하면 관제 화면의 유일한 접근 경로인 SSH 터널이 막히고 스크립트의 역할 확인도 경고로 빠진다.

공개키 자리표시자는 스크립트가 `build/keys/vm-lab_ed25519.pub`로 임시 사본에 치환해 넘기고, 치환 후 자리표시자가 남아 있으면 설치를 시작하지 않는다.

스크립트가 만드는 구성은 주소 계획의 OPS01 행과 같다. vCPU 2개, 메모리 4GB, 디스크 30GB, 저장 경로는 D 드라이브다. 네트워크 어댑터는 세 장이고 순서가 중요하다. 어댑터1이 NAT, 어댑터2가 내부 네트워크 `lab-lan`, 어댑터3이 호스트 전용 어댑터다. 이 순서가 어긋나면 `ops01.ks`의 `enp0s3`, `enp0s8`, `enp0s9` 대응이 깨진다. 실행 뒤 `VBoxManage showvminfo ops01`로 한 번 대조한다.

`ops01.ks`가 만드는 상태는 다음과 같다. 관측 도구는 아직 하나도 없다.

- `ops` 계정에 SSH 키로 로그인된다. root 로그인과 비밀번호 인증은 막혀 있다.
- SSH는 host-only 주소 `192.168.56.41`의 22번에만 붙는다.
- `AllowTcpForwarding local`과 `PermitOpen`으로 관제 화면 세 포트만 터널 목적지로 허용한다.
- `/var/log`와 `/var/lib`를 따로 뗀 LVM 구성이다. Prometheus TSDB와 Grafana DB가 루트를 채우지 않는다.
- SELinux는 Enforcing, firewalld와 nftables.service는 꺼져 있다. 방화벽 규칙은 아직 없다.

설치가 끝나면 콘솔에서 먼저 확인한다.

```bash
ip -brief address            # enp0s8 192.168.10.41, enp0s9 192.168.56.41
getenforce                   # Enforcing
sudo sshd -T | grep -E 'listenaddress|allowtcpforwarding|permitopen'
cat /root/ks-post-state.log
```

`allowtcpforwarding local`과 `permitopen 127.0.0.1:3000` 세 줄이 보여야 7단계의 터널이 열린다. 여기서 값이 다르면 `/etc/ssh/sshd_config.d/10-ops01-tunnel.conf`가 `20-vm-lab.conf`보다 먼저 읽히는지 확인한다. sshd는 먼저 읽은 값을 쓴다.

## 2단계. 구축 파일 전달

작업 PC에서 `build` 폴더를 OPS01로 옮긴다. 하위 폴더를 하나씩 지정하고 `keys`는 빼서 옮긴다. 개인키는 `%USERPROFILE%\.ssh\vm-lab_ed25519`에 있어 산출물 폴더 밖이지만, 폴더를 통째로 넘기는 습관을 남기지 않는다.

```powershell
$key = "$env:USERPROFILE\.ssh\vm-lab_ed25519"
$root = "C:\Users\kopo26\Desktop\vm-lab\build"
ssh -i $key ops@192.168.56.41 "mkdir -p /home/ops/build"
scp -i $key -r "$root\build-files" "$root\scripts" "$root\ks" "$root\문서" "$root\00-주소계획.md" ops@192.168.56.41:/home/ops/build/
```

OPS01에서 제자리로 옮기고 실행 권한을 준다.

```bash
sudo rsync -a /home/ops/build/ /opt/vm-lab/
sudo chmod 0755 /opt/vm-lab/scripts/40-ops01-observability.sh
ls /opt/vm-lab/build-files/ops
find /opt/vm-lab -name 'vm-lab_ed25519*' ! -name '*.pub'
find /opt/vm-lab -name 'lab_ed25519*'
```

마지막 두 명령은 아무것도 출력하지 않아야 한다.

## 3단계. 공통 기반 설정

여섯 대에 공통으로 적용하는 절차가 따로 있다. `scripts/00-common-setup.sh`가 계정과 SSH, 로그 보존, nftables 기본 차단 정책, node_exporter를 맡는다. OPS01도 그 절차를 먼저 받는 쪽이 낫다. 기본 정책이 `policy drop`이라 접근제어도의 "허용하지 않은 새 인바운드 연결은 차단"에 더 가깝고, 여섯 대의 방화벽 모양이 같아지기 때문이다. 자세한 내용은 [공통 구축절차](공통_구축절차.md)를 따른다.

공통 절차를 적용하면 관측 쪽에서 두 가지가 정해진다.

첫째, node_exporter 바인딩이 여섯 대 모두 lab-lan 주소다. OPS01도 `192.168.10.41:9100`이며 루프백으로 두는 분기는 쓰지 않는다. Prometheus 대상도 같은 `192.168.10.41:9100`이라 수집 설정과 실제 바인딩이 한 값으로 맞는다. Prometheus가 자기 호스트 주소로 붙어도 실제 트래픽은 `lo`로 들어오므로 공통 규칙의 루프백 허용에서 통과하고, 다른 VM이 같은 포트로 붙는 것은 기본 차단에 걸린다. `exporter` 단계는 기존 유닛이 있으면 그대로 두고 실제 바인딩과 수집 설정이 같은지 확인해 출력한다.

둘째, 방화벽 정책원이 `table inet vmlab` 하나다. `firewall` 단계는 현재 정책만 보여 주고 규칙을 더하지 않는다. OPS01에는 더 열 포트가 없다. 관제 포트 세 개와 보고 포트 9088은 루프백 바인딩이고 기본 정책이 drop이며, Prometheus가 다른 VM을 읽는 것은 나가는 연결이라 응답이 `established`로 돌아온다. `table inet failstop` 단독 규칙과 배포판 `nftables.service`는 쓰지 않는다.

## 4단계. 관측 도구 설치

스크립트는 단계별로 실행할 수도 있고 `all`로 한 번에 돌릴 수도 있다. 처음 구축할 때는 단계별로 실행해 각 출력을 증거로 남기는 쪽을 권한다. 출력은 `/var/log/vm-lab/40-ops01-<단계>.log`에도 쌓인다.

```bash
sudo /opt/vm-lab/scripts/40-ops01-observability.sh preflight
sudo /opt/vm-lab/scripts/40-ops01-observability.sh all
```

| 단계 | 하는 일 | 확인할 것 |
|---|---|---|
| `preflight` | OS, 역할 표시, SELinux, chronyd, 주소, 자원, 설치 원본 파일을 본다. 아무것도 바꾸지 않는다 | 실패 0건. 실패가 있으면 그 자리에서 멈춘다 |
| `packages` | `nftables chrony tar gzip python3 policycoreutils-python-utils audit` 설치 | 설치 전후 목록으로 새로 깔린 것과 이미 있던 것을 구분한다 |
| `accounts` | `prometheus`, `node_exporter`, `alertmanager` 서비스 계정 생성 | 셸이 `/sbin/nologin`인지 |
| `prometheus` | 공식 tar를 받아 SHA-256 대조 후 설치, 설정·규칙 배치, `promtool` 검사, 기동 | `promtool check config`가 SUCCESS, `/-/ready` 응답 |
| `exporter` | 기존 유닛이 있으면 그대로 두고, 없으면 설치해 `192.168.10.41:9100`으로 기동. 실제 바인딩과 수집 설정을 맞춘다 | `/metrics` 응답과 수집 설정의 ops01 대상이 둘 다 `192.168.10.41:9100`인지 |
| `firewall` | 공통 정책 `table inet vmlab`의 현재 규칙만 보여 준다. OPS01은 더 열 포트가 없다 | input policy가 drop이고 3000·9090·9093·9088에 accept 규칙이 없는지 |
| `grafana` | 공식 RPM 저장소에서 설치, 설정·데이터원본·화면 8개 배치, 기동 | `api/health`의 `database: ok` |
| `alertmanager` | 설치 후 전송 설정이 없는 `hold` 설정으로 기동 | `amtool check-config` 통과, `/-/ready` |

몇 가지는 스크립트가 일부러 막아 둔다. `preflight`는 `/etc/vm-lab/role`이 `ops01`이 아니면 중단한다. 구성도의 역할 분리를 지키기 위해서이고, 그래도 강행하려면 `OPS01_ALLOW_ROLE_MISMATCH=1`을 붙여야 한다. SELinux가 Disabled면 중단하고, chronyd가 꺼져 있어도 중단한다. 시각이 어긋난 상태로 수집을 시작하면 지표와 경보 시각을 믿을 수 없기 때문이다.

Prometheus, Alertmanager, node_exporter는 dnf로 설치하지 않는다. 저장소의 학교 설치 기록이 공식 릴리스 tar와 SHA-256 대조를 쓰고 있고, Rocky 10 기본 저장소와 EPEL에서 이 세 가지를 확인한 기록이 없기 때문이다. node_exporter 1.12.1은 저장소에 적힌 해시가 있어 공식 목록의 값과 그 값을 함께 대조한다. 나머지 둘은 공식 `sha256sums.txt` 대조까지만 한다. 파일 이름이 같다는 이유로 검사를 건너뛰지 않는다.

`firewall` 단계는 아무 규칙도 넣지 않고 현재 정책을 보여 주고 끝낸다. 방화벽 계통은 공통 절차의 `table inet vmlab` 하나이고 OPS01이 더 열 포트가 없기 때문이다. `scripts/ops01/ops01-firewall.nft`의 단독 테이블은 쓰지 않는다. 그 파일은 input 정책이 accept라 열거한 포트만 drop하고 나머지는 열린 채로 두므로, 접근제어도의 "허용하지 않은 새 인바운드 연결은 차단"과 어긋난다. 같은 단계에서 배포판 `nftables.service`를 disable하고 mask한다. 이 서비스는 정지할 때 ruleset 전체를 비우므로 규칙 파일을 넣는 서비스와 함께 두면 어느 규칙이 적용된 상태인지 알 수 없게 된다.

Grafana 화면 중 Linux 운영 화면은 저장소 원본이 아니라 VM 사본을 넣는다. 원본은 모든 식이 `job="linux"`를 고르는데 VM은 여섯 대를 모두 `job="linux-host"`로 수집하므로, 원본을 그대로 두면 패널이 전부 빈 채로 열린다. 바꾼 자리는 작업 이름과 uid, 제목, 태그뿐이고 식의 구조와 임계값은 그대로다.

## 5단계. Grafana 관리자 1명 만들기

스크립트는 관리자 계정을 만들지 않는다. 비밀번호 입력 구간을 스크립트 로그에 남기지 않기 위해서다. 사용자가 터미널에서 직접 실행한다.

```bash
sudo bash /opt/vm-lab/build-files/ops/bootstrap-grafana-admin.sh
```

아이디와 비밀번호는 화면에 보이지 않고 이 폴더 어디에도 남기지 않는다. 증거를 찍을 때 입력 구간은 건너뛰고 누락 구간이라고 표시한다. 기존 사용자가 이미 있으면 스크립트가 중단한다. 비밀번호 재설정용이 아니다.

## 6단계. 비밀 값과 Alertmanager 전송 연결

Teams 웹훅 주소와 보고 서비스 토큰은 OPS01에서 직접 만든다. 값 자체는 이 폴더와 증거에 남기지 않는다.

```bash
sudo install -d -o root -g root -m 0755 /etc/failstop
sudo install -o root -g alertmanager -m 0640 /dev/null /etc/failstop/teams-webhook
sudo install -o root -g alertmanager -m 0640 /dev/null /etc/failstop/reporter-token
sudo tee /etc/failstop/teams-webhook >/dev/null    # 값을 붙여 넣고 Ctrl-D
sudo tee /etc/failstop/reporter-token >/dev/null   # 값을 붙여 넣고 Ctrl-D
sudo /opt/vm-lab/scripts/40-ops01-observability.sh connect
```

`connect` 단계는 두 파일이 있고 비어 있지 않은지, 소유와 권한이 `root:alertmanager 0640`인지만 확인하고 값은 읽지 않는다. 그다음 `alertmanager.vm.yml`을 `/etc/failstop/alertmanager.yml`로 바꾸고 `amtool check-config`를 통과시킨 뒤 다시 기동한다.

경보는 두 갈래로 나간다. `teams` 수신자가 기본 장애 알림을 바로 보내고, `evidence-reporter`가 같은 경보를 `127.0.0.1:9088`의 보고 서비스로 넘긴다. 보고 역할을 아직 설치하지 않았다면 뒤쪽 수신자는 계속 실패하고 앞쪽 Teams 알림만 나간다. 두 경로가 독립이라 보고 서비스나 AI가 멈춰도 기본 알림은 전달된다. 보고 역할을 붙일 때 그쪽의 실제 바인딩 주소와 포트를 `alertmanager.vm.yml`의 값과 대조한다.

## 7단계. SSH 터널로 관제 화면 열기

Grafana는 `127.0.0.1:3000`, Prometheus는 `127.0.0.1:9090`, Alertmanager는 `127.0.0.1:9093`에만 뜬다. 방화벽에서도 세 포트를 drop 한다. 서비스가 루프백에만 붙어 있어도 바인딩 실수를 한 번 더 막기 위해서다. 그래서 VM 밖에서 화면을 열 수 있는 길은 SSH 터널 하나뿐이다. 접근제어도의 "관리 화면은 localhost 바인딩 + SSH 터널"과 "SSH 전달 목적지 제한"이 이 구성이다.

### 열기

작업 PC의 PowerShell에서 실행한다. Windows 10 이상의 기본 OpenSSH 클라이언트를 쓴다.

```powershell
$key = "$env:USERPROFILE\.ssh\vm-lab_ed25519"
ssh -i $key -N `
    -L 3000:127.0.0.1:3000 `
    -L 9090:127.0.0.1:9090 `
    -L 9093:127.0.0.1:9093 `
    -o ExitOnForwardFailure=yes `
    ops@192.168.56.41
```

`-N`은 원격 명령 없이 터널만 연다는 뜻이고, `-L 3000:127.0.0.1:3000`은 작업 PC의 3000번으로 온 연결을 OPS01 안의 `127.0.0.1:3000`으로 넘긴다는 뜻이다. `ExitOnForwardFailure=yes`를 붙이면 전달이 막혔을 때 조용히 붙어 있지 않고 바로 끊긴다. 터널을 닫을 때는 이 창에서 Ctrl+C를 누른다.

매번 길게 치지 않으려면 `%USERPROFILE%\.ssh\config`에 아래를 넣고 `ssh -N ops01`로 연다.

```
Host ops01
    HostName 192.168.56.41
    User ops
    IdentityFile %USERPROFILE%\.ssh\vm-lab_ed25519
    IdentitiesOnly yes
    ExitOnForwardFailure yes
    LocalForward 3000 127.0.0.1:3000
    LocalForward 9090 127.0.0.1:9090
    LocalForward 9093 127.0.0.1:9093
```

개인키 파일의 권한이 넓으면 OpenSSH가 키를 거부한다. 그때는 상속을 끊고 현재 사용자만 읽게 바꾼다.

```powershell
icacls "$env:USERPROFILE\.ssh\vm-lab_ed25519" /inheritance:r
icacls "$env:USERPROFILE\.ssh\vm-lab_ed25519" /grant:r "$($env:USERNAME):(R,W)"
```

### 보기

터널이 열린 동안 브라우저에서 아래 주소를 연다. 화면은 모두 8개다.

| 화면 | 주소 |
|---|---|
| Linux 운영 (여섯 VM) | `http://127.0.0.1:3000/d/vm-linux` |
| 01 전체 운영 | `http://127.0.0.1:3000/d/msp-overview?var-customer=exchange&var-environment=vm-lab` |
| 02 트래픽·Spring | `http://127.0.0.1:3000/d/msp-service?var-customer=exchange&var-environment=vm-lab` |
| 03 인프라 | `http://127.0.0.1:3000/d/msp-infra?var-customer=exchange&var-environment=vm-lab` |
| 04 DB 상태 | `http://127.0.0.1:3000/d/msp-db?var-customer=exchange&var-environment=vm-lab` |
| 05 SQL 부하 | `http://127.0.0.1:3000/d/msp-db-workload?var-customer=exchange&var-environment=vm-lab` |
| 06 복제 상태 | `http://127.0.0.1:3000/d/msp-db-replication?var-customer=exchange&var-environment=vm-lab` |
| 07 디스크·용량 | `http://127.0.0.1:3000/d/msp-storage?var-customer=exchange&var-environment=vm-lab` |

Prometheus는 `http://127.0.0.1:9090`, Alertmanager는 `http://127.0.0.1:9093`이다. 셋 다 작업 PC의 루프백 주소이며 OPS01의 주소를 직접 여는 것이 아니다.

MSP 화면 7개의 패널 선택자는 `role=~"edge|app|reader|data|standby|probe|observability"`다. `build-files/ops/reporting.config.vm.json`과 같은 값이며, 이 값이어야 `role`이 `edge`인 EDGE01과 `observability`인 OPS01도 패널에 나온다. 저장소 원본의 `role=~"app|reader|data|standby|probe"`를 그대로 두면 `prometheus.vm.yml`이 일부러 수집하는 두 대가 일곱 화면 어디에도 나오지 않는다. 화면을 열기 전에 `scripts/ops01/msp-dashboards-vm/*.json`에서 원본 정규식이 남아 있지 않은지 확인하고, 남아 있으면 `make-vm-dashboards.py`로 7개를 다시 생성한 뒤 연다.

### 막혔을 때

`channel 3: open failed: administratively prohibited`가 나오면 OPS01의 `PermitOpen` 목록에 그 목적지가 없다는 뜻이다. `sudo sshd -T | grep permitopen`으로 세 줄을 확인한다. 목적지를 늘려야 한다면 `/etc/ssh/sshd_config.d/10-ops01-tunnel.conf`를 고치고 이유를 기록한다.

`bind: Address already in use`는 작업 PC에서 그 포트를 이미 쓰고 있다는 뜻이다. `-L 13000:127.0.0.1:3000`처럼 앞의 번호만 바꾸고 브라우저 주소도 함께 바꾼다.

화면이 열리지 않고 브라우저가 비어 있으면 터널이 아니라 Grafana를 본다. OPS01에서 `curl -fsS http://127.0.0.1:3000/api/health`가 응답하는지 먼저 확인한다.

터널을 열어 둔 채 자리를 비우지 않는다. Grafana에는 관리자 1명만 있고 익명 접근과 가입은 꺼 두었지만, 터널이 열려 있는 동안에는 작업 PC에서 화면에 닿을 수 있다.

## 검증

```bash
sudo /opt/vm-lab/scripts/40-ops01-observability.sh verify
```

| 확인 | 기대 결과 |
|---|---|
| `systemctl is-active prometheus grafana-server alertmanager node_exporter` | `active` 네 줄 |
| `sudo ss -lntp` | `127.0.0.1:9090`, `127.0.0.1:3000`, `127.0.0.1:9093`, 그리고 `192.168.10.41:9100`. `0.0.0.0` 바인딩이 보이면 실패다 |
| `sudo promtool check config /etc/prometheus/prometheus.yml` | `SUCCESS` |
| `sudo amtool check-config /etc/failstop/alertmanager.yml` | 문법 오류 없음 |
| `curl -fsS http://127.0.0.1:9090/-/ready` | `Prometheus Server is Ready.` |
| `count(up == 1)` | `/api/v1/targets?state=active`의 대상 수와 같다. 지금 설정에서는 13 |
| `up == 0` | 비어 있어야 한다. 값이 있으면 그 대상의 바인딩과 방화벽을 본다 |
| `min by(node)(node_scrape_collector_success{job="linux-host"})` | 노드마다 1. HTTP 수집 성공과 수집기별 성공은 다르다 |
| `curl -fsS http://127.0.0.1:3000/api/health` | `database: ok` |
| `systemctl show grafana-server -p MemoryMax` | `939524096` |
| `sudo nft list table inet vmlab` | input policy가 drop이고 3000·9090·9093·9088에 accept 규칙이 없다. counter가 보인다 |
| `systemctl is-enabled nftables` | masked다. 정책원은 `vm-lab-firewall.service` 하나다 |
| `journalctl -k --since -1h` | OOM 기록이 없다 |
| `sudo ausearch -m AVC -ts recent` | 거부 없음. 거부가 나오면 포트 타입이나 파일 문맥을 더한다 |

수집 대상 13개는 Prometheus 자신 1개, 여섯 VM의 9100 6개, 앱 두 대의 8081 2개, DB 두 대의 9187 2개, EDGE01 진입 경로의 합성 프로브 2개다. `count(up)`은 응답하지 않는 대상까지 세므로 판정에 쓰지 않는다. `verify`는 `up == 1`인 대상 수를 Prometheus가 실제로 불러들인 대상 수와 비교한다. 이 판정 수정은 저장소 스크립트만 고친 것이며 OPS01에서 다시 실행하지 않았다.

스크립트가 대신할 수 없는 확인이 네 가지 있다.

첫째, 차단 확인이다. 다른 VM에서 아래 두 명령이 모두 실패해야 한다. 성공하면 바인딩이나 규칙이 틀린 것이다.

```bash
curl --max-time 3 http://192.168.10.41:9090/-/ready
curl --max-time 3 http://192.168.10.41:9100/metrics   # OPS01 자신 외에는 막힌다
```

둘째, 경보 경로다. EDGE01에서 `sudo systemctl stop node_exporter`를 하고 1분을 기다린 뒤 Prometheus의 `/api/v1/alerts`에 `LinuxHostUnreachable`이 firing으로 뜨고, Alertmanager의 `/api/v2/alerts`에도 같은 경보가 도착하는지 본다. `hold` 설정을 쓰는 동안에는 여기까지가 끝이고 Teams로는 가지 않는다. 다시 켜서 경보가 해제되는 것까지 확인한다.

셋째, 재부팅이다. `sudo reboot` 뒤에 `verify`를 다시 돌린다. `enable`만 해 두고 끝내지 않는다.

넷째, 화면이다. 8개를 차례로 열어 패널에 값이 들어오는지 본다. 거래가 없어 비는 패널은 비어 있는 그대로 둔다. 미수집을 0이나 정상으로 바꾸지 않는다.

## 설계에서 의도적으로 벗어난 곳

여기부터는 이 구성이 저장소 원본과 어디에서 왜 달라졌는지를 적는다. 명령을 실행하는 절차가 아니라 값을 고를 때 본 근거다.

### 재사용한 저장소 파일

`C:\Users\kopo26\Desktop\failstop` 아래의 파일을 원본으로 쓴다. 새로 만든 것은 VM 주소 체계로 바꾼 사본뿐이며, 바꾼 자리는 각 사본의 머리말 주석에 적었다.

| 원본 | 쓰임 | VM 사본 |
|---|---|---|
| `deploy/linux/observability/prometheus.yml` | 수집 대상과 라벨 | `build-files/ops/prometheus.vm.yml` |
| `deploy/linux/observability/prometheus.service` | Prometheus systemd 서비스 | 그대로 쓴다 |
| `deploy/linux/observability/grafana.ini` | Grafana 루프백 바인딩, 익명 접근 차단 | 그대로 쓴다 |
| `deploy/linux/observability/grafana-limits.conf` | Grafana 메모리 한도 | 그대로 쓴다 |
| `deploy/linux/observability/grafana-datasource.yml` | Prometheus 데이터 원본 | 그대로 쓴다 |
| `deploy/linux/observability/grafana-dashboards.yml` | Linux 화면 프로비저닝 | `scripts/ops01/grafana-dashboards.vm.yml` |
| `deploy/linux/observability/grafana-msp-dashboards.yml` | MSP 화면 프로비저닝 | `scripts/ops01/grafana-msp-dashboards.vm.yml` |
| `deploy/linux/observability/msp-dashboards/*.json` | 운영 화면 7개 | `scripts/ops01/msp-dashboards-vm/*.json` |
| `deploy/linux/observability/linux-dashboard.json` | Linux 기본 화면 | `build-files/ops/linux-dashboard.vm.json` |
| `deploy/linux/observability/bootstrap-grafana-admin.sh` | 최초 관리자 생성 | 그대로 쓴다 |
| `deploy/linux/observability/node_exporter-host.service` | node_exporter 서비스 | `scripts/ops01/node_exporter.ops01.service` |
| `deploy/linux/observability/grafana.repo` | Grafana RPM 저장소 | 그대로 쓴다 |
| `deploy/linux/reporting/school-alerts.yml` | 경보 규칙 | `build-files/ops/alerts.vm.yml` |
| `deploy/linux/reporting/alertmanager.service` | Alertmanager systemd 서비스 | `scripts/ops01/alertmanager.vm.service` |
| `deploy/linux/reporting/alertmanager.school.yml` | Alertmanager 라우팅 | `build-files/ops/alertmanager.vm.yml` |

`grafana-public.ini`와 `nginx-metrics.conf`, `nginx-grafana.conf`는 쓰지 않는다. `sang.it.kr` 도메인과 Let's Encrypt 인증서, 학교 EC2의 사설 대역에 묶인 설정이기 때문이다. VM의 Grafana는 도메인 없이 SSH 터널로만 연다.

`deploy/linux/firewall-host.sh`도 그대로 실행하지 않는다. 이 스크립트는 SSH 포트가 22이면 거부하는데 주소 계획은 22를 쓰고, `observability` 역할이 8080과 9088과 관리 포트 3종을 관리 대역에 여는데 VM에서는 관리 포트를 루프백 밖으로 내보내지 않는다. 방화벽은 공통 절차의 `table inet vmlab` 하나를 쓰고 OPS01은 역할 규칙을 더하지 않는다.

### 학교 AWS 값을 VM 값으로 옮긴 자리

| 항목 | 학교 AWS | VM |
|---|---|---|
| 앱 Linux 지표 | `172.31.2.86:9100` | `192.168.10.21:9100`, `192.168.10.22:9100` |
| DB Linux 지표 | `172.31.33.195:9100`, `172.31.1.229:9100` | `192.168.10.31:9100`, `192.168.10.32:9100` |
| DB 통계 | `172.31.33.195:9187`, `172.31.1.229:9187` | `192.168.10.31:9187`, `192.168.10.32:9187` |
| 앱 지표 경로 | Nginx `172.31.2.86:9101/metrics` 프록시 | 앱 `192.168.10.2x:8081/actuator/prometheus` 직접 |
| 관측 노드 자신 | `127.0.0.1:9100` | `192.168.10.41:9100` |
| 환경 라벨 | `environment: aws`, `account: school` | `environment: vm-lab`, `account` 제거 |
| 노드 라벨 | `app-1`, `db-primary`, `db-standby`, `bot-1` | `edge01`, `app01`, `app02`, `db01`, `db02`, `ops01` |
| 보고 수신 주소 | `172.31.44.141:9088` | `127.0.0.1:9088` |
| 경보 그룹 이름 | `school.operations` | `vm.operations` |

앱 지표를 8081에서 직접 읽는 것은 접근제어도의 정책을 따른 것이다. 학교에서는 앱의 Actuator가 루프백에만 떠 있고 Nginx가 9101에서 중계했지만, VM 도면은 OPS01이 앱의 8081을 직접 읽는다. 그래서 앱 역할에서 `MANAGEMENT_SERVER_ADDRESS`를 각 앱의 lab-lan 주소로 두고 노출 엔드포인트를 지표와 헬스로 제한해야 한다. 이 조건이 맞지 않으면 `exchange-api` 수집은 실패한다.

관측 노드 자신의 주소를 `192.168.10.41:9100`으로 둔 것은 여섯 대의 모양을 같게 하려는 것이다. OPS01만 루프백으로 두는 분기를 쓰지 않는다.

노드 라벨을 바꾸면 화면의 `environment` 변수에 저장된 값도 맞춰야 한다. 원본 JSON에는 `aws`가 들어 있어 그대로 두면 화면을 열었을 때 대상이 하나도 잡히지 않는다. `scripts/ops01/make-vm-dashboards.py`가 이 값과 설명, 태그, 그리고 패널의 `role` 정규식을 바꾼다. 이 변환은 작업 PC에서 돌려 JSON 7개를 만들었다는 뜻이고, OPS01에서 화면이 실제로 그려진다는 뜻이 아니다. 화면 확인은 위 검증 단계에서 따로 한다.

### 수집 대상

수집 대상은 모두 13개다. 여섯 VM의 9100, 앱 두 대의 8081, DB 두 대의 9187, Prometheus 자신, 그리고 EDGE01 진입 경로를 확인하는 합성 프로브 두 개다.

| 작업 이름 | 대상 | 라벨 |
|---|---|---|
| `prometheus` | `127.0.0.1:9090` | `role=observability`, `node=ops01` |
| `linux-host` | 192.168.10.11 / .21 / .22 / .31 / .32 / .41 의 9100 | `role`은 edge, app, app, data, standby, observability |
| `exchange-api` | 192.168.10.21 / .22 의 8081 | `role=app`, `node=app01`, `app02` |
| `postgres` | 192.168.10.31 / .32 의 9187 | `role=data`, `role=standby` |
| `blackbox-http` | `https://192.168.10.11/`, `https://192.168.10.11/api/v1/markets` | `role=edge`, `node=edge01`. 수집 주소는 OPS01 루프백의 blackbox_exporter `127.0.0.1:9115`다 |

`blackbox-http` 작업은 `build-files/ops/prometheus.vm.yml`에 들어 있지만 blackbox_exporter 설치는 `40-ops01-observability.sh`의 단계에 없다. 2026-09-22에 OPS01에서 따로 설치했고 그 기록은 `캡처/역할설치/OPS01_주기조회_프로브.txt`에 있다. 이 스크립트만으로 OPS01을 새로 만들면 두 프로브 대상은 down으로 남는다.

작업 이름은 학교와 같게 둔다. 경보 규칙이 `job="linux-host"`, `job="postgres"`, `job="exchange-api"`를 쓰고 화면과 보고 코드도 같은 이름을 쓴다. 이름을 바꾸면 세 곳을 같이 고쳐야 한다.

여섯 대를 모두 `linux-host`에 넣은 것은 학교와 다르다. 학교에서는 관측 노드 자신만 `linux` 작업으로 따로 두었는데, 그러면 그 노드에는 호스트 경보가 걸리지 않는다. VM에서는 여섯 대가 같은 규칙을 받는다. 보고 코드는 `job=~"linux|linux-host"`로 두 이름을 모두 읽으므로 이 변경으로 깨지지 않는다.

### 바인딩과 포트

| 서비스 | 바인딩 | 외부 접근 |
|---|---|---|
| Prometheus | `127.0.0.1:9090` | 없다. SSH 터널로만 연다 |
| Grafana | `127.0.0.1:3000` | 없다. SSH 터널로만 연다 |
| Alertmanager | `127.0.0.1:9093` | 없다 |
| node_exporter | `192.168.10.41:9100` | 자기 자신의 Prometheus만 |

### Grafana 메모리 한도

`GOMEMLIMIT=512MiB`, `MemoryHigh=768M`, `MemoryMax=896M`이다. 학교 AWS에서 MSP 화면 7개를 띄웠을 때 384MiB 상한에서 Grafana가 OOM으로 종료됐고 512와 640MiB로 올린 뒤에도 회수 지연이 나타나 이 값으로 올렸다. VM에서는 아직 측정하지 않았다. Go 런타임의 회수 목표와 서비스 한도를 함께 두는 구성이며 런타임 목표는 강제 상한이 아니다.

Linux 운영 화면을 저장소 원본이 아니라 VM 사본으로 넣는 이유도 여기에 적어 둔다. 원본은 모든 식이 `job="linux"`를 고르는데 VM의 `prometheus.yml`에는 그 작업 이름이 없고 여섯 대를 모두 `job="linux-host"`로 수집한다. 원본을 그대로 넣으면 패널이 전부 빈 채로 열린다. 바꾼 자리는 작업 이름과 uid, 제목, 태그뿐이며 변환기는 `build-files/ops/make-vm-linux-dashboard.py`다.

## 증거 남기기

AWS와 Linux 작업은 [증거본](../../../../증거본/README.md) 기준대로 단계별 실제 화면과 명령·출력을 남긴다. 스크립트 출력은 `/var/log/vm-lab/`에 쌓이므로 그 파일과 화면 캡처를 함께 둔다. Grafana 관리자 비밀번호와 Teams 웹훅, 보고 토큰을 넣는 구간은 캡처하지 않고 누락 구간으로 표시한다.

## 한계와 미확인

이 절은 절차를 쓸 때 확인하지 못한 항목이다. 2026-09-22 설치에서 실제로 본 결과는 캡처 파일에 있으며, 이 절의 항목을 캡처와 하나씩 대조해 지우지는 않았다. 화면 변환기 두 개를 작업 PC에서 돌린 것은 JSON 파일이 만들어졌다는 뜻이지 OPS01에서 화면이 그려진다는 뜻이 아니다.

Grafana RPM 저장소가 Rocky 10에 맞는 패키지를 주는지 확인하지 않았다. 학교에서 설치한 13.1.0은 EL9 기준이었다. `dnf install grafana`에서 의존성 문제가 나오면 그 자리에서 판단하고 기록한다. Prometheus 3.13.3, Alertmanager 0.31.1, node_exporter 1.12.1이 Rocky 10의 glibc에서 도는지도 실행해 봐야 안다.

SELinux는 Enforcing으로 둔다. `/usr/local/bin`의 바이너리는 `bin_t` 문맥을 받고 세 서비스가 루프백이나 자기 대역에만 붙으므로 포트 타입을 더할 일이 없을 것으로 본다. 다만 `ausearch`로 확인하기 전에는 필요 없다고 단정하지 않는다.

`exchange-api` 수집은 앱 역할이 Actuator를 각 앱의 lab-lan 주소에 열고 노출 엔드포인트를 지표와 헬스로 제한해야 성립한다. 학교에서는 Nginx가 9101에서 중계했지만 VM은 도면대로 8081을 직접 읽는다. 제한 없이 열면 Actuator 전체가 lab-lan에 노출되므로 앱 역할과 이 조건을 맞춘다. `postgres` 수집은 DB 역할이 postgres_exporter와 `failstop_monitor` 계정을 만들어야 성립한다.

Alertmanager를 `failstop-report`가 아니라 전용 계정 `alertmanager`로 돌린다. 보고 역할이 `failstop-report`로 9088을 열면 두 계정이 같은 비밀 파일을 읽어야 할 수 있다. 권한을 넓히기 전에 파일을 나누는 쪽을 먼저 본다.

공통 절차와 이 절차가 같은 자리를 건드리는 곳이 두 군데 있다. 하나는 `/etc/systemd/system/node_exporter.service`이고 다른 하나는 방화벽 정책이다. 정본은 둘 다 공통 절차다. node_exporter는 여섯 대가 lab-lan 주소에 붙고 OPS01도 `192.168.10.41:9100`이며, 방화벽은 `table inet vmlab` 하나다. 이 스크립트는 기존 유닛이 있으면 그대로 두고 실제 바인딩에 수집 설정을 맞추며, 방화벽에는 규칙을 더하지 않는다. 2026-09-22 [관측 검증 캡처](../../캡처/역할설치/OPS01_관측검증.txt)에는 node_exporter가 `192.168.10.41:9100`에만 붙은 `ss` 출력과 `roles` 체인이 비어 있는 `table inet vmlab`, masked 상태의 `nftables.service`가 있다. OPS01 한 대의 두 자리는 이 캡처로 확인했고, 다른 노드와의 통일은 각 역할 캡처에서 따로 본다.

경보 규칙 파일이 아직 두 벌이다. 정본은 `build-files/ops/alerts.vm.yml`이고 `scripts/ops01/vm-alerts.yml`은 설계 단계의 사본이다. 두 파일은 경보 그룹 이름 한 줄이 다르다. 정본은 `vm.operations`인데 사본에는 학교 AWS 표기 `school.operations`가 남아 있다. 둘 다 `/etc/prometheus/vm-alerts.yml`로 가므로 사본이 설치되면 학교 이름이 붙은 규칙이 올라간다. `preflight`가 차이를 알리지만, 사본을 지우거나 그 한 줄을 정본과 맞추기 전에는 이 항목이 정리됐다고 적지 않는다. 같은 이유로 `scripts/ops01`의 `prometheus.yml`과 `alertmanager.vm.yml` 사본도 남아 있어 매 실행마다 차이 경고가 나온다.

OPS01 메모리는 4GB이고 관측 네 서비스의 상한 합계는 약 1.66GB다. 나머지는 OS와 이후 붙일 보고 서비스의 몫이다. 여섯 대를 동시에 켜고 화면 여러 개를 열며 부하를 거는 최악 조건은 아직 재 본 적이 없다.

pfSense는 사용자 구축 범위라 건드리지 않았다. NAT를 내리고 기본 경로를 `192.168.10.1`로 바꾸기 전까지는 모든 통신이 방화벽을 지난다고 적지 않는다. 나가는 통신은 OPS01의 nftables에서 제한하지 않는다.

단일 PC의 VM 시험이다. 여기서 얻은 결과를 학교 AWS나 개인 AWS의 검증 결과로 옮겨 적지 않는다.
