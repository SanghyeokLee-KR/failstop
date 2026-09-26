# MSP 인프라 업무와 연결할 Linux·네트워크 실습

「Linux·AWS 기반 인프라 운영 및 온프레미스 AI 관제 시스템 구축」에서 보여 줄 운영 업무를 조사했다. 개발팀이 거래소와 대시보드를 제공한 상황에서, 인프라 담당자가 배포 환경을 만들고 문제를 진단·복구하는 역할이다. 설정을 많이 추가하기보다 작업 대상과 영향 범위를 확인하고, 필요한 변경을 적용한 뒤 정상 동작과 복구 가능성을 검증하는 경험을 우선한다.

조사일은 2026-09-18이다. 아래 내용은 회사 공개 자료와 제품 공식 문서를 바탕으로 선정한 실습 제안이다. 시스원 내부 운영 표준이나 면접 채점표가 아니며, 설정을 실제 적용했다는 기록도 아니다. 사용자가 말한 실습 기간은 이틀, 남은 사용 가능 금액은 130달러, 발표 시간은 15분이다. 월 운영 예산 40만 원과 이번 실습 잔액을 구분한다. 실제 AWS 권한·크레딧·요금과 실습 완료 여부는 조회하지 않았다.

## 공개 자료에서 확인한 업무

시스원 공식 연락처는 IDC 사업 담당을 MSP 인프라지원팀으로 표기하고, 클라우드사업팀과 스토리지사업팀을 따로 안내한다. MSP 소개에는 전산시스템 구축·운영 관리, 서버 호스팅, DR, 백업, 보안, 모니터링 서비스가 포함된다. 따라서 이번 프로젝트를 해당 업무와 연결하려면 서버와 네트워크의 운영 과정을 설명하는 것이 타당하다는 판단이다. 해당 팀의 상세 채용 직무기술서와 내부 작업 기준은 이번 조사에서 확보하지 못했다. [담당 부서 안내](https://www.sysone.co.kr/cs01.php), [MSP 서비스](https://www.sysone.co.kr/product_list.php?idx=15)

AiWACS 공개 설명에는 서버·네트워크 상태, CPU·메모리·디스크·프로세스, 장애 이력, 하드웨어 센서, SNMPv3 수집과 Windows 이벤트 알림이 나온다. 관제 화면을 새로 만드는 것보다, 기존 화면에 실제 운영 상태를 연결하고 이상을 발견한 뒤 확인할 로그와 명령을 설명하는 방향이 이 기능들과 맞는다. 제품 기능 소개가 그 팀의 모든 담당 업무를 뜻하는 것은 아니다. [AiWACS 소개](https://sysone.co.kr/en/product_view.php?idx=28)

Red Hat의 실습형 시스템 관리자 평가도 계정·권한, 서비스, 저장장치, 네트워크, 보안 설정과 재부팅 후 지속성을 다룬다. 이 자료는 기본 역량의 범위를 대조하는 데 사용했으며 특정 자격증을 필수로 취득해야 한다는 근거로 쓰지 않았다. [RHCSA 평가 범위](https://www.redhat.com/en/services/training/ex200-red-hat-certified-system-administrator-rhcsa-exam)

## 설정 전에 알아야 할 것

개발팀에서 실행 이미지와 CPU 아키텍처, 환경변수 이름, 시작·종료 순서, 포트와 통신 상대, 저장 경로, 정상 상태 확인 방법을 받는다. 설정 변경 전에 현재 접속한 서버의 이름·주소·역할·OS를 확인한다. 호스트와 컨테이너, DB primary와 standby, 운영망과 관리망을 구분하지 못하면 명령 자체가 맞아도 잘못된 작업이 된다.

변경할 때는 대상, 이유, 영향, 되돌릴 방법, 검증 결과를 남긴다. 별도의 관리 시스템이나 문서를 계속 만들 필요는 없다. 기존 작업 기록과 증거에 이 정보를 담으면 된다. AI가 작성한 명령도 적용 위치와 영향을 사람이 설명할 수 있어야 한다.

RHEL 10 문서는 Rocky Linux 계열의 개념과 설정을 조사하는 기준으로 사용했다. 실제 AMI·패키지·systemd·OpenSSH·Docker 버전의 지원 여부는 해당 서버에서 확인해야 한다. Ubuntu의 네트워크 관리 방식이나 AppArmor를 RHEL의 NetworkManager·SELinux와 같은 설정으로 취급하지 않는다. 현재 이미지·비용·데이터 보존 조건은 [배포 안내](../../deploy/README.md)와 [0099](../reference/adr/0099-current-architecture.md)를 따른다.

## Linux에서 우선할 설정과 검증

### 계정과 관리 접속

개인 운영 계정과 서비스 실행 계정을 나눈다. 필요한 작업만 sudo로 허용하고, 서비스 설정이나 root가 실행하는 스크립트를 제한된 운영 계정이 수정할 수 있는지도 확인한다. 그룹 이름만 분리했다고 권한이 분리된 것은 아니다. 계정 회수 때는 비밀번호뿐 아니라 SSH 키, 기존 세션과 자동화에 남은 접근도 확인한다.

SSH는 키 인증과 접속 가능 계정·경로를 정한다. `PermitRootLogin`, `PasswordAuthentication`, `KbdInteractiveAuthentication`, `AllowGroups` 등은 실제 인증 정책과 버전에 맞춰 검토한다. 변경 전 구문 검사, 적용 후 유효 설정과 새 연결을 확인한다. 기존 관리 연결과 복구 경로를 확보한 상태에서 작업한다. 포트 번호 변경만으로 접근 통제를 완료했다고 설명하지 않는다. [OpenSSH 설정 설명](https://man.openbsd.org/sshd_config)

일반 운영자를 Docker 그룹에 추가하면 root 수준 권한을 줄 수 있다. 제한된 서비스 조회·재시작 권한과 Docker 전체 제어 권한을 구분한다. [Docker의 Linux 운영 권한 안내](https://docs.docker.com/engine/install/linux-postinstall/)

검증 사례는 “운영자는 정해진 서비스의 상태를 확인하고 재시작할 수 있지만, 다른 서비스와 비밀 설정에는 접근할 수 없다”로 잡는다. 허용한 동작과 금지한 동작을 모두 시험한다.

### 서비스 기동과 재부팅

systemd에서 실행 계정, 작업 경로, 환경 파일, 종료 처리, 재시작 조건과 반복 실패 제한을 확인한다. 배포 패키지의 원본 unit을 직접 고치는 대신 필요한 변경을 drop-in으로 관리하는 방법을 검토한다. 설정 다시 읽기와 프로세스 재시작은 다른 작업이고, reload를 지원하지 않는 서비스도 있다. [RHEL systemd 관리](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/using_systemd_unit_files_to_customize_and_optimize_your_system/managing-system-services-with-systemctl), [unit 변경 방법](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html-single/using_systemd_unit_files_to_customize_and_optimize_your_system/index)

데이터 디스크 마운트와 필요한 네트워크가 준비된 뒤 서비스가 실행되게 한다. 네트워크 준비 완료가 원격 DB의 준비 완료를 보장하지는 않는다. 데이터 마운트가 빠졌을 때 루트 디스크의 빈 디렉터리에 잘못 쓰지 않도록 동작을 확인한다. [네트워크와 systemd 기동 순서](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/configuring_and_managing_networking/systemd-network-targets-and-services)

컨테이너 서비스라면 systemd가 관리하는 것이 Docker 데몬인지, Compose 기동 작업인지, 실제 장기 실행 프로세스인지 구분한다. 재시작 정책을 붙였다는 이유만으로 내부 애플리케이션 복구를 검증했다고 쓰지 않는다. 재부팅 후 프로세스, 포트, 실제 요청과 로그를 함께 확인한다.

### 로그와 시간

재부팅 이전의 원인을 조사할 수 있도록 journal 보존 여부를 확인하고, 디스크를 소진하지 않도록 저장량과 보존 기간을 정한다. 애플리케이션·컨테이너 로그의 순환 방식도 따로 확인한다. 로그를 읽는 권한과 중앙 전송 범위를 정하고 비밀번호·토큰을 기록하거나 보고서로 전달하지 않는다. [journal 영구 보존](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/automating_system_administration_by_using_rhel_system_roles/configuring-the-systemd-journal-by-using-rhel-system-roles)

여러 서버의 사건 순서를 비교하려면 시간 동기화가 필요하다. chrony의 서비스 실행 여부와 실제 동기화 상태를 구분하고 `tracking`, `sources` 결과를 확인한다. 원본 로그의 시간대와 보고서 표시 시간대를 명시한다. 거래가 진행 중인 서버에서 시험 목적으로 시스템 시간을 임의 변경하지 않는다. [chrony 운영](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/configuring_time_synchronization/using-chrony)

### 디스크와 파일시스템

| 고객 요청·현상 | 확인하고 처리할 내용 | 완료 근거 |
|---|---|---|
| 데이터 저장 공간 증설 | 볼륨, 파티션, LVM 사용 여부, 파일시스템 종류와 실제 마운트를 순서대로 확인한다. | OS가 보는 용량 증가, 기존 파일·권한과 서비스 정상 상태 |
| 용량은 남았는데 쓰기 실패 | inode, 권한, 읽기 전용 마운트, 삭제됐지만 프로세스가 열고 있는 파일 등을 구분한다. | 실제 원인과 조치 전후 쓰기 결과 |
| 재부팅 후 저장장치 누락 | UUID 기반 마운트와 부팅 의존성, 경로·권한을 확인한다. | 재부팅 뒤 같은 저장장치가 붙고 서비스가 올바른 경로를 사용 |
| 디스크 사용량 급증 | 로그·백업·컨테이너 데이터 중 증가 원인을 찾고 보존 정책을 조정한다. | 남겨야 할 데이터를 보존하면서 증가 원인을 제어 |

EBS 용량 증가만으로 파일시스템까지 늘어나는 것은 아니다. LVM과 파티션 유무에 따라 단계가 달라지고 XFS와 ext4의 도구도 다르다. 먼저 복원 가능한 백업이나 일관성 있는 스냅샷을 준비하고, 실습용 데이터 디스크에서 절차를 검증한다. 크기를 다시 줄이는 방식으로 쉽게 되돌릴 수 있다고 가정하지 않는다. [AWS EBS 파일시스템 확장](https://docs.aws.amazon.com/ebs/latest/userguide/recognize-expanded-volume-linux.html), [RHEL 영구 마운트](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/managing_file_systems/persistently-mounting-file-systems)

### SELinux·패치·자원 진단

RHEL 계열에서 접근이 거부되면 일반 파일 권한과 SELinux 거부를 구분한다. AVC 로그와 파일·프로세스 문맥을 확인하고, 필요한 라벨·포트·정책 설정을 수정한다. SELinux 전체 비활성화나 무조건적인 허용 정책 생성으로 원인을 덮지 않는다. 실제 컨테이너와 볼륨 라벨에도 같은 검토가 필요하다. [RHEL SELinux 문제 해결](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/using_selinux/troubleshooting-problems-related-to-selinux)

패치는 현재 버전, 변경 내용, 재시작 영향과 복구 수단을 확인한 뒤 시험 노드부터 적용하는 실습을 권한다. 정기 작업에는 실행 계정, 절대 경로, 시간대, 중복 실행 방지와 실패 확인을 포함한다. 백업이나 일일 보고가 실행되지 않은 경우도 발견할 수 있어야 한다.

CPU·메모리·I/O 문제는 같은 시간대의 프로세스와 서비스 지연을 함께 본다. 아래 명령은 조사 위치를 잡기 위한 조회 예시이며 설치된 도구와 권한을 먼저 확인한다. 한 수치만 보고 병목을 확정하지 않는다.

| 확인할 현상 | 조회 예시 | 설명해야 할 판단 |
|---|---|---|
| CPU·프로세스 부하 | `top`, `ps`, `pidstat`, `vmstat` | CPU 사용, 대기, 특정 프로세스의 증가가 요청 지연과 연결되는가 |
| 메모리 부족 | `free`, `vmstat`, `journalctl -k` | 캐시와 사용 가능 메모리, swap, OOM 종료를 구분했는가 |
| 저장장치 지연 | `iostat`, `lsblk`, `df -hT`, `df -i` | 용량 문제와 I/O 지연을 구분했는가 |
| 서비스 실행 실패 | `systemctl status`, `systemctl cat`, `journalctl -u` | 실행 사용자·경로·의존성·종료 원인 중 무엇이 문제인가 |

`sysctl`, 파일 디스크립터 제한, TCP 옵션과 DB 설정은 측정된 병목이 있을 때만 조정한다. 인터넷의 일괄 성능 튜닝 값을 이유 없이 복사하는 것은 이번 실습의 성과로 삼지 않는다.

## 네트워크에서 보여 줄 설정과 진단

접속 문제는 출발지와 목적지, 실제 이름 해석 결과와 서비스 포트를 먼저 확정한다. 브라우저, 호스트, 컨테이너에서 보는 네트워크 경로가 다를 수 있다. 필요한 통신을 표로 정하고 정상 연결과 차단할 연결을 각각 시험한다.

| 영역 | 설정·확인할 내용 | 프로젝트에 적용할 사례 |
|---|---|---|
| 주소·라우팅 | IP·CIDR·기본 경로·목적지별 경로·반환 경로 | 관리 접속과 앱·DB 통신이 의도한 인터페이스·망을 이용하는지 확인 |
| DNS | 사용 중인 resolver, 내부·외부 이름, 검색 도메인, DNS까지의 경로 | IP로는 되지만 이름으로 실패하는 원인을 설명 |
| 서비스 바인딩 | listen 주소·포트와 컨테이너 publish 주소 | localhost만 수신하거나 불필요하게 외부에 공개된 포트를 발견 |
| 호스트 방화벽 | firewalld 또는 nftables 관리 방식, 활성·영구 설정 | 허용한 관리망에서만 접근되고 재부팅 후에도 정책이 유지 |
| AWS 경계 | 보안 그룹, NACL, 서브넷 라우팅과 NAT | 앱만 DB에 접근하며 DB 관리 포트는 외부에서 접근 불가 |
| TLS·HTTP | 이름과 인증서, 유효 기간, 프록시 경로·상태 코드 | TCP 연결 성공과 정상 HTTPS 응답을 구분 |
| 패킷 | 제한된 대상·포트·시간의 캡처 | 요청이 어디까지 도착하고 응답이 어디서 끊기는지 확인 |
| 네트워크 관제 | 인터페이스 상태·오류·버림·트래픽 추이 | 그래프 이상과 서버·장비의 실제 상태를 대조 |

NetworkManager 환경에서는 연결 프로파일과 실제 적용 상태를 함께 확인한다. DNS 설정은 프로파일과 경로가 맞아야 하며, 내부 이름을 쓰는 망에서 공용 DNS로 바꾸면 오히려 장애가 날 수 있다. EC2의 DHCP·cloud-init 설정을 실습용 고정 IP 설정으로 임의 대체하지 않는다. NetworkManager가 관리하지 않는 서버에 nmcli 설정을 그대로 적용하지도 않는다. [nmcli 공식 설명](https://networkmanager.pages.freedesktop.org/NetworkManager/NetworkManager/nmcli.html), [RHEL DNS 경로 문제](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/configuring_and_managing_networking/configuring-the-order-of-dns-servers)

firewalld를 쓰면 zone과 실제 인터페이스·출발지 연결을 확인하고, 활성 규칙과 영구 규칙을 대조한다. 기존 nftables 구성 위에 별도 관리 정책을 무작정 얹지 않는다. Docker는 자체 규칙과 포워딩 경로를 사용하므로 호스트 INPUT 규칙만 확인해 컨테이너 접근이 차단됐다고 판단하지 않는다. 실제 Docker 방화벽 backend와 네트워크 모드에 맞춰 다른 노드에서 허용·차단을 시험한다. [firewalld 설정](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/configuring_firewalls_and_packet_filters/using-and-configuring-firewalld), [Docker 방화벽 동작](https://docs.docker.com/engine/network/packet-filtering-firewalls/)

AWS 보안 그룹은 상태를 추적하고 NACL은 반환 트래픽도 별도 규칙을 적용한다. 두 계층에 같은 규칙을 무조건 복사하지 않는다. Reachability Analyzer는 AWS 구성에 따른 도달 가능성 분석이며 실제 패킷이나 호스트 애플리케이션 정상 여부를 검사하지 않는다. Linux의 라우트·리스너·방화벽과 실제 접속을 따로 확인한다. [AWS 네트워크 접근 통제](https://docs.aws.amazon.com/vpc/latest/userguide/infrastructure-security.html), [Reachability Analyzer의 범위](https://docs.aws.amazon.com/vpc/latest/reachability/how-reachability-analyzer-works.html)

진단 순서의 예는 `hostnamectl`과 `ip -br addr`로 대상 확인, `getent hosts`·`dig`로 이름 해석 확인, `ip route get`으로 경로 확인, `ss -lntp`로 서버 수신 확인, `curl`로 실제 서비스 확인이다. 필요할 때 양쪽에서 제한된 패킷을 수집한다. ping 실패만으로 서비스를 중단 상태라 단정하거나 TLS 검증을 끈 결과를 정상 HTTPS의 증거로 쓰지 않는다. 캡처·디버그 로그에는 민감한 값이 담길 수 있으므로 공개 자료는 필요한 부분만 정제한다.

## DB·관제·자동화를 운영 업무로 연결하기

### 백업과 복구

백업 파일 생성, 복제와 실제 복원을 구분한다. 실행 중인 DB 파일을 단순 복사한 것을 복구 가능한 백업으로 취급하지 않는다. DB가 지원하는 방식과 일관성 조건을 사용하고 격리된 대상에 복원한 뒤 서비스와 데이터를 확인한다. [PostgreSQL 17 백업 개요](https://www.postgresql.org/docs/17/backup.html), [파일 수준 백업의 조건](https://www.postgresql.org/docs/17/backup-file.html)

우리 DB는 비동기 복제와 수동 승격을 기준으로 한다. 기존 primary 격리, standby 승격, 앱 연결 변경과 서비스 회복, 데이터 대조까지 포함해 측정한다. 운영자가 판단하는 시간을 복구 시간에서 숨기지 않는다. RAID·복제·백업은 각각 다른 장애를 다루며 서로를 대신하지 않는다. 자동 DB 전환이나 WAL 보존 정책 변경은 이번 조사만으로 채택하지 않는다. [DB 수동 페일오버](../reference/operations/DB_수동_페일오버.md), [현재 복제 결정](../reference/adr/0024-async-two-node-replication.md)

### 관제와 Teams 보고

대시보드는 요청 오류·지연, Linux 자원, DB 상태를 같은 시간대로 연결한다. 알림에는 대상 서버, 발생 시각, 고객 영향, 관측값과 확인할 위치를 담는다. 짧은 일시적 변화와 지속 장애를 구분하고, 확인할 행동이 없는 중복 알림을 줄인다. 관제 수집기가 죽은 경우에도 정상처럼 보이지 않게 하며 관제 시스템 자체의 상태도 확인한다. [Prometheus 알림 원칙](https://prometheus.io/docs/practices/alerting/)

Teams에는 감지한 사실, 실제 조치, 조치 후 상태와 남은 문제를 전달한다. AI의 원인 가설과 확인된 원인은 나누고, AI 실패 시 기본 알림은 유지한다. 웹훅 응답 성공과 사용자가 메시지·보고서 링크를 실제로 열 수 있는지도 구분한다. Linux 운영 기능으로는 예약 작업의 로그·미실행·실패·재시도·중복 실행을 검증한다. 이 방향은 이미 채택한 [ADR 0027](../reference/adr/0027-msp-operations-and-autoscaling.md)에 따른다.

### 반복 설정과 변경 관리

같은 초기 설정을 새 서버에 적용하고, 다시 실행해도 불필요한 변경이 쌓이지 않는지 확인한다. 현재 스크립트로 가능하면 도구를 바꾸는 일을 먼저 하지 않는다. Ansible을 쓸 경우 한 호스트에 한정해 변경 예상과 차이를 확인하는 방법이 유용하다. check mode는 실제 실행 검증을 대신하지 않고 모듈·태스크 설정에 따라 동작이 달라진다. diff에는 비밀 값이 노출되지 않게 한다. [Ansible check·diff 모드](https://docs.ansible.com/projects/ansible/latest/playbook_guide/playbooks_checkmode.html)

AWS 자원과 Linux 설정의 담당 범위를 구분하고 이미지·설정 버전을 함께 기록한다. 자동 확장으로 새 노드가 생겼을 때도 동일한 계정·방화벽·로그·관측 설정이 적용되는지 확인한다. 전체 DB 연결 수와 노드 종료 중 요청 처리도 검증한다. 공통 DB 장애를 조회 노드 자체 장애로 오인해 모든 인스턴스를 반복 교체하지 않아야 한다. 이 조건은 [ADR 0027](../reference/adr/0027-msp-operations-and-autoscaling.md)의 확장 범위를 유지한다.

## IDC 직무를 위해 별도로 알아둘 영역

| 영역 | 추가 실습 후보 | AWS 프로젝트와 구분할 한계 |
|---|---|---|
| RAID | 별도 디스크로 RAID1 구성, 저하 상태 감지, 교체·재구성 확인 | EC2 소프트웨어 RAID는 실제 RAID 컨트롤러와 물리 디스크 교체 경험이 아니다. |
| VLAN·본딩 | 관리망·서비스망 분리, VLAN 태그, active-backup 또는 LACP의 구성 조건 | VPC 서브넷을 VLAN과 같다고 설명하지 않는다. 실제 스위치와 이중 물리 경로 시험은 별도다. |
| 네트워크 장비 관제 | 읽기 전용 SNMPv3, 인증·암호화, 수집 출발지 제한, 인터페이스 상태 확인 | AWS 가상 네트워크를 스위치 SNMP 실습 결과로 대신하지 않는다. |
| 하드웨어 관리 | iDRAC 등에서 센서·디스크 상태와 Lifecycle Log 확인, 장비 식별·교체 절차 | EC2 콘솔과 물리 서버 원격 관리 장비는 별개다. |
| VMware | VM 생성·자원 할당, 가상 스위치·포트 그룹, 데이터스토어·스냅샷 수명 관리 | VM 스냅샷을 독립된 백업으로 사용하지 않는다. |
| Windows Server | 계정·서비스·이벤트 로그, DNS·방화벽·TCP 연결, 디스크 관리 | WSL 사용을 Windows Server 운영 경험으로 기록하지 않는다. |

RAID는 복원 가능한 별도 디스크에서만 실습한다. 운영 DB에 추가하는 것을 필수 과제로 삼지 않는다. [RHEL RAID 관리](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/managing_storage_devices/managing-raid)

장비 관제는 지원하는 보안 설정과 수집 권한을 확인하며, 하드웨어의 실제 상태를 확인할 장비 접근이 필요하다. [Cisco SNMP 보안](https://www.cisco.com/c/en/us/support/docs/ip/simple-network-management-protocol-snmp/20370-snmpsecurity-20370.html), [Dell iDRAC 서버 상태 확인](https://www.dell.com/support/kbdoc/en-uk/000391164/poweredge-how-to-monitor-server-health-from-the-idrac9?lang=en)

VMware의 스냅샷과 백업은 구분해야 한다. Windows 네트워크 진단도 출발지와 서버를 나누고 실제 TCP·DNS 결과를 대조하는 방식으로 연습한다. [VMware 스냅샷 권고](https://knowledge.broadcom.com/external/article?legacyId=1025279), [Microsoft DNS 문제 해결](https://learn.microsoft.com/en-us/windows-server/networking/dns/troubleshoot/troubleshoot-dns-data-collection), [Test-NetConnection](https://learn.microsoft.com/en-us/powershell/module/nettcpip/test-netconnection?view=windowsserver2025-ps)

## 이틀 실습에서 먼저 고를 것

아래 우선순위는 이틀 안에 전부 완료한다는 보장이 아니다. 기존 배포 자산을 재사용하고 새로운 관제 UI나 인프라 도구의 도입은 최소화하는 제안이다. 실제 적용 전에는 최신 작업 브랜치와 기존 구현을 확인해 이미 끝낸 설정을 중복 작성하지 않는다.

2026-09-19 사용자 결정인 [ADR 0029](../reference/adr/0029-school-aws-first-personal-account-expansion.md)에 따라 VM 선행 단계는 보류하고 학교 EC2에서 시작한다. Linux에 직접 설치·설정한 서비스를 기본으로 하며 Docker는 필요한 항목에만 이유를 기록한다. 첫 설치와 진단을 단계별로 확인한 뒤 자동화한다. 우선 Docker·WSL의 기존 결과를 확인하고 학교 EC2에서 접속·운영 계정·서비스·로그·시간·통신 경로를 검증한다. 재부팅 후 기동과 로그 보존을 확인하며 접속 실패는 출발지·경로·리스너·정책을 따라 진단한다.

학교에서 허용되는 범위의 배포·관측·조회 자동 확장·DB 복구를 진행하고 화면·명령·원자료를 보관한다. 권한 때문에 실행하지 못한 항목은 완료로 표시하지 않으며 10월 초 개인 계정에서 보강한다. 기본 구성이 안정되면 허용 범위에서 데이터 디스크나 SELinux·권한 문제 해결 실습을 추가한다. RAID·VMware·Windows·고급 네트워크는 핵심 흐름 이후 선택한다. 이틀 안의 완료를 보장하지 않는다.

15분 발표에서는 Linux·네트워크 문제 해결 한 사례, 조회 부하와 자동 확장 한 사례, DB 복구 한 사례를 중심으로 삼는다. 나머지 설정은 구성도와 운영 기준으로 설명한다. 기존 대본을 새로 만들지 않고 PPTX·영상·캡처의 근거로 사용한다.

| 추천 사례 | 직접 설명할 질문 | 남길 증거 |
|---|---|---|
| 서비스 계정의 권한 오류 | 어느 경로에서 무엇이 거부됐으며 필요한 권한은 무엇이었는가 | 소유자·권한, 오류 로그, 허용·차단 시험 |
| 재부팅 후 기동 실패 | 서비스·디스크·네트워크 중 어느 의존성이 빠졌는가 | unit과 journal, 마운트, 재부팅 뒤 실제 응답 |
| 서버 간 접속 실패 | 요청이 어디까지 도착했고 어느 정책에서 막혔는가 | 출발지·목적지·포트, 라우트·리스너와 접속 결과 |
| 데이터 디스크 증설 | 볼륨과 파일시스템의 차이를 확인했는가 | 장치·마운트·용량의 전후 값, 기존 데이터 확인 |
| 조회 자동 확장 | 증설 후 지연·오류가 개선되고 DB 부담은 허용 범위였는가 | ASG·ALB 상태, 요청량·지연·오류·연결 수 |
| DB 장애와 복원 | 이전 primary를 격리했고 어느 데이터까지 복구됐는가 | 조치 시각, 복제 상태, 서비스 회복과 기록 대조 |

각 사례는 고객 요청 또는 시험 목적, 변경한 서버와 설정, 확인한 원인, 조치, 재검증 결과로 설명한다. 아직 실행하지 않은 문제는 예정된 시나리오로 표시한다. 수행하지 않은 조치를 AI 보고서가 완료로 쓰지 않게 한다.

## 이번 조사에서 확인하지 않은 것

시스원의 비공개 업무 분장·내부 표준·면접 선호, 현재 AWS 계정 권한·크레딧·자원·청구액, 실제 호스트 OS와 명령 출력은 확인하지 않았다. 회사 일부 페이지는 직접 열기에 실패해 검색 도구가 제공한 해당 공식 페이지의 본문으로 대조했다. 검색 결과에 나온 개인 후기나 커뮤니티 답변은 기술 근거로 사용하지 않았다.

현재 체크아웃은 `3362117`이고 로컬에 알려진 원격 작업 브랜치는 `6ee1179`다. 이번 조사는 두 버전의 전체 구현 차이를 재검토한 작업이 아니다. 기존 로컬·원격 검증 기록을 없던 것으로 취급하지 않으며, 위 목록을 전부 미구현 항목으로 읽어서도 안 된다. 코드·배포 설정·AWS 자원은 변경하지 않았고 Linux 명령이나 장애 시험도 실행하지 않았다.
