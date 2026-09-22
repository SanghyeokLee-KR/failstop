# APP01과 APP02 구축 절차

Rocky Linux 10.2 minimal 위에 Java 21과 거래 앱 JAR을 직접 설치하고 systemd로 운영하는 앱 노드 두 대를 만드는 절차다. 값의 기준은 `build/00-주소계획.md` 한 곳이며 이 문서는 그 표의 값을 쓴다. 표와 다른 값이 필요하면 계획서를 먼저 고치고 여기를 맞춘다.

이 문서는 실행하면 이렇게 확인된다는 절차이지 실행 결과 보고가 아니다. 아래의 어떤 항목도 아직 수행하지 않았다. VM을 만들고 명령을 실제로 돌리기 전까지 모든 검증은 미수행이다.

## 이 노드가 맡는 것과 맡지 않는 것

APP01은 `192.168.10.21`(lab-lan)과 `192.168.56.21`(host-only), APP02는 `192.168.10.22`와 `192.168.56.22`를 쓴다. 두 대의 설정은 주소만 다르고 나머지는 같다. 스키마 마이그레이션만 APP01에서 한 번 적용하고 APP02는 적용된 스키마를 그대로 쓴다.

리더는 서비스 설정이 정하지 않는다. DB01의 `leader_lease` 행이 정한다. 두 대 모두 엔진을 켠 채 같은 DB를 보며, 리스를 쥔 쪽만 쓰기를 받고 다른 쪽은 readiness가 503이다. 앱을 두 대 켠 것만으로 고가용성이 성립한다고 적지 않는다.

이 노드에 DB나 관측 도구를 같이 올리지 않는다. PostgreSQL은 DB01과 DB02, Prometheus와 Grafana는 OPS01이 맡는다. 설치를 시작하기 전에 `Linux_VM_운영환경_구성도.svg`와 주소계획의 배치 표를 한 번 더 대조한다.

## 먼저 준비돼야 하는 것

DB01이 `exchange` 데이터베이스와 계정 두 개를 만들어 두어야 한다. 소유자 `exchange_owner`는 스키마를 적용하고, 실행 계정 `exchange_app`은 스키마 생성과 원장 INSERT가 거부되는 계정이다. 이 분리는 학교 AWS에서 쓴 구조와 같으며 근거는 저장소 `docs/reports/AWS_작업.md`에 있다. 두 계정이 준비되기 전에는 앱을 기동할 수 없다.

두 계정 모두 비밀번호가 있어야 한다. `pg_hba.conf`의 인증 방식이 `hostssl ... scram-sha-256`이라 비밀번호가 없는 역할은 인증에 성공하지 못한다. DB01의 `passwords` 단계 대상은 `exchange_owner exchange_app replicator` 셋이다. `exchange_owner`가 빠지면 스키마 적용 자체가 되지 않는다.

EDGE01이 사용자에게 보여줄 오리진은 `https://192.168.56.11` 하나로 정해져 있다. 작업 PC 브라우저가 host-only 주소로 들어오므로 브라우저가 보내는 Origin 헤더가 이 값이고, 앱의 CORS와 WebSocket 오리진 검사도 같은 값이어야 한다. lab-lan 주소 `192.168.10.11`을 오리진으로 넣으면 핸드셰이크는 EDGE01까지 도달하고 앱의 검사에서 막힌다. prod 프로파일은 `exchange.cors.allowed-origins`가 비면 기동을 중단하고(`ProdCorsConfig`), `app.ws.allowed-origins`에 `localhost`나 `demo.exchange.local`이 들어 있어도 중단한다(`WebSocketConfig`). 그래서 오리진이 확정되기 전에는 앱이 아예 뜨지 않는다.

pfSense는 사용자가 직접 구축한다. 이 절차는 NAT로 패키지를 받는 상태를 전제한다. pfSense가 올라오고 기본 경로를 `192.168.10.1`로 바꾼 뒤 NAT를 내리면 마지막 재부팅 검증을 다시 한다. 그 전까지는 모든 통신이 방화벽을 지난다고 적지 않는다.

## 저장소에서 가져다 쓰는 것

새로 만들지 않고 저장소 `deploy/linux` 자산을 그대로 쓴다. 바꾼 것은 학교 AWS 전용 값뿐이다.

| 저장소 원본 | VM에서 쓰는 방법 |
|---|---|
| `deploy/linux/app/exchange-app.service` | 고치지 않고 그대로 `/etc/systemd/system/exchange-app.service`로 설치한다. 설치 스크립트가 SHA-256 `5ee44fb22544d1f51a1ff2cdec145b19996a565632b73a120da2cf01afbb1d0d`을 대조해 원본과 같은지 확인한다 |
| `deploy/linux/app/app.env.school-private` | `build-files/app/app.env.vm.example`의 원본이다. 바꾼 곳은 아래에 적었다 |
| `deploy/linux/app/MigrateDatabase.java` | APP01의 스키마 적용에 그대로 쓴다. 접속 정보를 환경변수 세 개로만 받는다 |
| `deploy/linux/firewall-host.sh`, `common.sh` | 규칙의 모양만 참고한다. VM에서는 실행하지 않는다. 방화벽은 공통 기반의 `table inet vmlab` 한 계통이다 |
| `deploy/linux/observability/node_exporter-host.service` | 리슨 주소를 `/etc/node_exporter/listen.env`로 받으므로 유닛은 고치지 않는다. 수집 대상 목록에 `exchange-app`이 이미 들어 있다 |

`app.env`에서 학교 값을 바꾼 곳은 네 군데다. `SERVER_ADDRESS`와 `MANAGEMENT_SERVER_ADDRESS`를 `127.0.0.1`에서 각 노드의 lab-lan 주소로 바꿨고, DB 주소를 `172.31.33.195`에서 `192.168.10.31`로, 인증서 경로를 `primary.crt`에서 `db01.crt`로 바꿨다. 오리진 두 개는 정본 진입 오리진 `https://192.168.56.11`로 채우고, 학교 파일에 없던 `EXCHANGE_TRUSTED_PROXIES`를 새로 넣었다. 커넥션 풀 크기와 나머지 키는 학교 값을 그대로 둔다.

학교에서 쓴 `nginx-metrics.conf`는 VM에서 쓰지 않는다. 학교는 8081을 루프백에 두고 Nginx 9101 프록시로 지표를 냈지만, VM에서는 8081을 lab-lan에 직접 열고 nftables로 OPS01만 허용하므로 프록시 한 겹이 필요 없다.

`switch-db-endpoint.py`는 DB 승격 뒤 앱 접속을 바꾸는 절차의 모형으로만 읽는다. 학교 IP와 S3 경로가 하드코딩돼 있어 VM에서 그대로 실행하지 않는다. 순서, 즉 서비스를 먼저 내리고 `app.env`와 인증서를 백업한 뒤 URL과 인증서를 바꾸는 순서만 가져온다.

## 작업 PC에서 하는 일

먼저 앱 JAR을 만든다. 저장소 `server`에서 실행한다.

```powershell
cd C:\Users\kopo26\Desktop\failstop\server
.\gradlew.bat :app:bootJar
Get-ChildItem .\app\build\libs
Get-FileHash -Algorithm SHA256 .\app\build\libs\app-0.1.0-SNAPSHOT.jar
```

출력된 해시를 적어 둔다. VM에 올린 뒤 같은 값이 나오는지 확인하는 기준이다.

다음으로 VM에 올릴 준비물을 한곳에 모은다. 아래는 스테이징 폴더를 `C:\Users\kopo26\Desktop\vm-lab\stage`로 잡은 예다.

```powershell
$stage = "C:\Users\kopo26\Desktop\vm-lab\stage"
$repo  = "C:\Users\kopo26\Desktop\failstop"
$build = "C:\Users\kopo26\Desktop\vm-lab\build"
New-Item -ItemType Directory -Force -Path "$stage\build-files\app", "$stage\linux\app", "$stage\linux\systemd", "$stage\linux\observability" | Out-Null

Copy-Item "$repo\server\app\build\libs\app-0.1.0-SNAPSHOT.jar" "$stage\app.jar"
(Get-FileHash -Algorithm SHA256 "$stage\app.jar").Hash.ToLower() | Set-Content -NoNewline "$stage\app.jar.sha256"

Copy-Item "$build\build-files\app\exchange-app.service"  "$stage\build-files\app\"
Copy-Item "$build\build-files\app\app.env.vm.example"    "$stage\build-files\app\"
Copy-Item "$repo\deploy\linux\common.sh"                 "$stage\linux\"
Copy-Item "$repo\deploy\linux\firewall-host.sh"          "$stage\linux\"
Copy-Item "$repo\deploy\linux\app\MigrateDatabase.java"  "$stage\linux\app\"
Copy-Item "$repo\deploy\linux\observability\node_exporter-host.service" "$stage\linux\observability\"
```

`node_exporter-1.12.1.linux-amd64.tar.gz`는 공식 다운로드 목록에서 받아 같은 폴더에 둔다. 학교 설치에서 대조한 1.12.1 배포본의 SHA-256은 `b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63`이며, 설치 스크립트가 이 값을 기본으로 대조한다. 다른 버전을 받으면 공식 목록에서 다시 대조하고 `--node-exporter-sha256`으로 준다.

셸 스크립트는 LF 줄바꿈으로 올려야 한다. Windows에서 편집하면서 CRLF가 섞이면 VM에서 실행되지 않으므로, 전송 후 `file` 또는 `head -1` 출력으로 확인한다.

## VM 생성과 OS 설치

`build/ks/app01.ks`와 `build/ks/app02.ks`가 Rocky Linux 10.2 minimal의 무인 설치를 맡는다. 두 파일은 hostname과 IP 두 줄, `/etc/vm-lab-node` 표식만 다르다.

```powershell
pwsh -NoProfile -File C:\Users\kopo26\Desktop\vm-lab\build\scripts\01-create-vms.ps1 -Role APP01
pwsh -NoProfile -File C:\Users\kopo26\Desktop\vm-lab\build\scripts\01-create-vms.ps1 -Role APP02
```

kickstart가 만드는 상태는 다음과 같다. vCPU 2, 메모리 3GB, 디스크 25GB이고 저장 경로는 D 드라이브다. 디스크는 `/boot` 1GB, 루트 14GB, `/var/log` 4GB, 스왑 2GB로 나누고 볼륨 그룹에 약 4GB를 남긴다. `/var/log`를 따로 뗀 이유는 저널이 커져도 루트가 차지 않게 하기 위해서다.

NIC는 세 개다. NIC1 NAT(`enp0s3`)만 기본 경로를 잡고, NIC2 lab-lan(`enp0s8`)과 NIC3 host-only(`enp0s9`)는 `--nodefroute`로 경로를 만들지 않는다. SELinux는 Enforcing으로 둔다. firewalld와 배포판 `nftables.service`는 둘 다 꺼진 상태로 시작한다. 방화벽을 공통 기반의 `table inet vmlab` 한 계통으로만 운영해야 무엇이 막았는지 설명할 수 있기 때문이다.

관리 계정 `ops`는 비밀번호가 잠긴 채 SSH 키만 받는다. 개인키는 `%USERPROFILE%\.ssh\vm-lab_ed25519`에 두고 산출물 폴더에 넣지 않는다. kickstart에 들어가는 것은 같은 키쌍의 공개키 `build/keys/vm-lab_ed25519.pub`다. sshd는 host-only 주소에만 바인딩하며 lab-lan에는 SSH를 열지 않는다. 바인딩 주소를 좁히면 그 주소가 올라오기 전에 sshd가 실패할 수 있으므로 `network-online.target` 뒤로 기동 순서를 미루는 드롭인을 함께 넣는다.

`ops`는 비밀번호가 잠겨 있어 암호를 묻는 sudo가 통하지 않는다. 그래서 구축 중에는 `/etc/sudoers.d/10-ops-build`로 전체 sudo를 둔다. 구축이 끝나면 이 파일을 필요한 명령으로 좁힌다. 좁히기 전에 제한된 sudo를 적용했다고 적지 않는다.

패키지는 kickstart에서 최소만 설치한다. Rocky minimal ISO에는 BaseOS 일부만 들어 있어 `java-21-openjdk-headless` 같은 AppStream 패키지가 없고, 없는 패키지를 `%packages`에 적으면 설치가 그 자리에서 멈춘다. 나머지는 VM이 뜬 뒤 NAT를 통해 dnf로 설치한다.

설치가 끝나면 접속한다.

```bash
ssh -i $env:USERPROFILE\.ssh\vm-lab_ed25519 ops@192.168.56.21
```

## 준비물 전송

작업 PC에서 각 VM으로 스테이징 폴더를 통째로 올린다.

```powershell
$key = "$env:USERPROFILE\.ssh\vm-lab_ed25519"
scp -i $key -r C:\Users\kopo26\Desktop\vm-lab\stage ops@192.168.56.21:/home/ops/stage
scp -i $key C:\Users\kopo26\Desktop\vm-lab\build\scripts\20-app-setup.sh   ops@192.168.56.21:/home/ops/
scp -i $key C:\Users\kopo26\Desktop\vm-lab\build\scripts\21-app-migrate.sh ops@192.168.56.21:/home/ops/
```

APP02에는 `21-app-migrate.sh`를 올리지 않아도 된다. 올리더라도 스크립트가 주소를 보고 APP01이 아니면 실행을 거부한다.

## APP01 구축

### 0. 공통 기반

여섯 대에 공통으로 거는 기반이 `scripts/00-common-setup.sh`에 있다. 기본 차단 nftables 정책, journald 보존, 호스트 점검 타이머, node_exporter가 거기서 온다. 이 기반을 쓰기로 했다면 앱 절차보다 먼저 적용한다.

```bash
sudo bash /home/ops/00-common-setup.sh --plan
sudo bash /home/ops/00-common-setup.sh --apply --node-exporter-tarball /home/ops/stage/node_exporter-1.12.1.linux-amd64.tar.gz
```

앱 절차는 기반이 있는지 스스로 보고 맞춰 동작한다. 기반이 있으면 방화벽 역할 파일만 덧붙이고 node_exporter를 덮어쓰지 않는다. 기반이 없으면 단독 구성으로 둘 다 직접 만든다. 어느 쪽으로 갔는지는 스크립트 출력의 `방화벽 경로` 줄에 나오며 그 값을 증거에 적는다.

### 1. 기본 설치

```bash
sudo bash /home/ops/20-app-setup.sh 1 --origin https://192.168.56.11 --generate-app-secrets
```

`--origin`은 정본 진입 오리진 `https://192.168.56.11`이다. 이 값이 그대로 `app.env`의 `EXCHANGE_CORS_ALLOWED_ORIGINS`와 `APP_WS_ALLOWED_ORIGINS`에 들어가고, EDGE01의 인증서 SAN, 웹 번들 오리진, Nginx가 앱으로 보내는 Host 헤더와 같은 값이어야 한다. lab-lan 주소 `https://192.168.10.11`을 넣지 않는다. `localhost`나 `demo.exchange.local`이 들어가면 스크립트가 먼저 막고, 통과하더라도 앱이 기동에서 멈춘다.

`--generate-app-secrets`는 마스터키, 페퍼, 세션 시드 세 값을 `openssl rand -base64 32`로 만든다. APP01에서 한 번만 쓴다. 스크립트는 값을 화면에 내지 않으며 이 구간은 화면 캡처를 남기지 않는다.

스크립트가 하는 일은 순서대로 이렇다. 대상 노드의 주소와 역할을 확인하고, dnf로 패키지를 설치하고, firewalld와 배포판 nftables를 끄고, 로그인 불가 서비스 계정 `exchange`와 경로 세 개를 만들고, JAR을 해시 대조 후 `/opt/exchange/releases/<리비전>`에 두고 `current` 링크를 걸고, `app.env`를 만들고, 비밀 값 파일의 뼈대를 만들고, `exchange-app.service`를 저장소 원본 해시와 대조해 설치하고, node_exporter가 아직 없으면 배포본 해시를 대조해 배치하고, 방화벽 규칙을 적용한다.

비밀 값이나 DB 인증서가 아직 없으면 스크립트는 `exchange-app`을 enable하지도 start하지도 않고 무엇이 남았는지 알려 준 뒤 끝난다. 준비가 안 된 채 enable하면 부팅 때마다 기동에 실패해 `StartLimitBurst`에 걸리기 때문이다.

### 2. DB 비밀번호와 인증서

`/etc/exchange/app-secret.env`의 `SPRING_DATASOURCE_PASSWORD`에 DB01이 `exchange_app`에 정한 값을 넣는다. 이 값은 DB01에서 정해지므로 앱 쪽에서 새로 만들지 않는다. 파일은 root:root 0600이고 편집 중에도 권한을 바꾸지 않는다.

```bash
sudo vi /etc/exchange/app-secret.env
sudo ls -l /etc/exchange/app-secret.env
```

DB01의 서버 공개 인증서를 받아 `/etc/exchange/db01.crt`에 두고 지문을 DB01에서 본 값과 대조한다. 공개 인증서이므로 소유는 root:exchange 0644로 둔다.

```bash
sudo install -o root -g exchange -m 0644 /home/ops/stage/db01.crt /etc/exchange/db01.crt
sudo sha256sum /etc/exchange/db01.crt
```

접속과 TLS 성립을 psql로 먼저 확인한다. 앱을 띄우기 전에 확인해야 기동 실패의 원인을 좁힐 수 있다.

```bash
psql "host=192.168.10.31 port=5432 dbname=exchange user=exchange_app \
      sslmode=verify-full sslrootcert=/etc/exchange/db01.crt" -c "SELECT current_user, ssl_is_used();"
```

### 3. 스키마 적용

먼저 DB01에서 마이그레이션 창을 연다. 평상시 `pg_hba.conf`에는 `exchange_owner`용 규칙이 없고 그 역할은 `NOLOGIN`이므로, 창을 열지 않으면 Flyway가 붙지 못한다. 창을 열지 않은 채 마이그레이션이 성공한 것처럼 적지 않는다.

```bash
# DB01에서
sudo /opt/vm-lab/scripts/30-db-primary-setup.sh migrate-open
```

이 명령이 `pg_hba.conf`에 `hostssl exchange exchange_owner 192.168.10.21/32 scram-sha-256` 한 줄을 넣고 reload한다. 출발지는 APP01 한 대뿐이며 APP02는 들어 있지 않다. `exchange_owner`의 비밀번호는 DB01의 `passwords` 단계에서 이미 만들어 두어야 한다. 비밀번호가 없는 역할은 scram 인증에 성공하지 못한다.

그다음 APP01에서 적용한다.

```bash
sudo bash /home/ops/21-app-migrate.sh migrate
```

적용이 끝나면 곧바로 DB01에서 창을 닫는다. 열어 둔 채로 두면 접근제어도와 어긋난다.

```bash
# DB01에서
sudo /opt/vm-lab/scripts/30-db-primary-setup.sh migrate-close
```

`migrate-close`가 그 한 줄을 지우고 `ALTER ROLE exchange_owner NOLOGIN`을 실행한다. 닫은 뒤 `pg_hba_file_rules`에 `exchange_owner` 줄이 남아 있지 않은지, `pg_roles`에서 `rolcanlogin`이 `f`인지 확인하고 그 출력을 증거로 남긴다.

이 스크립트는 앱을 기동하지 않고 배포 JAR 안의 Flyway로 스키마만 적용한다. JAR에서 `BOOT-INF/lib` 전체와 `BOOT-INF/classes/db/migration`을 임시 디렉터리로 풀고, `MigrateDatabase.java`를 단일 파일 소스로 실행한다. 라이브러리를 골라 담지 않고 통째로 푸는 이유는 Flyway가 어떤 의존을 더 요구하는지 추측하지 않기 위해서다.

계정은 소유자 `exchange_owner`다. 비밀번호는 인자로 받지 않고 화면에 보이지 않게 직접 입력하거나 root 전용 0600 파일로 준다. 인자로 주면 `ps` 출력과 셸 이력에 남는다. 실행이 끝나면 임시 디렉터리를 지운다.

출력에서 `Applied migrations` 개수와 `Schema validation passed`를 확인한다. 저장소의 `server/app/src/main/resources/db/migration`에는 마이그레이션이 47개 있고 배포 JAR 안의 파일과 같아야 한다. 개수가 다르면 JAR과 소스가 어긋난 것이므로 먼저 확인한다.

체크섬 오류가 나면 repair하지 않는다. 이미 적용한 Flyway 파일은 고치지 않는다. 무엇이 바뀌었는지 먼저 확인한다.

이 단계는 APP02에서 실행하지 않는다. 스크립트도 `192.168.10.21`이 아니면 거부한다.

### 4. 마켓 기본값

`/api/v1/markets`가 값을 내려면 DB 카탈로그에 자산과 마켓이 들어 있어야 한다. 등록은 DB01에서 `deploy/linux/app/bootstrap-markets.sql`로 한 번만 한다. 앱 노드의 작업이 아니다. 빈 카탈로그가 아니면 스크립트가 스스로 멈춘다. 이 등록 전에는 마켓 API가 200이어도 빈 배열이므로 판정 기준으로 쓰지 않는다.

### 5. 기동

```bash
sudo systemctl enable --now exchange-app
sudo systemctl status exchange-app --no-pager
sudo journalctl -u exchange-app -n 80 --no-pager
```

로그에서 두 가지를 본다. Flyway가 꺼진 채 기동했는지(`SPRING_FLYWAY_ENABLED=false`), 그리고 리더 획득 로그가 있는지다.

## APP02 구축

같은 절차를 주소만 바꿔 실행하되 마이그레이션은 건너뛴다.

```bash
sudo bash /home/ops/20-app-setup.sh 2 --origin https://192.168.56.11
```

APP02도 스키마가 이미 적용된 뒤에만 기동한다. APP01 안의 `/etc/exchange/.migrated` 표식은 APP02에서 볼 수 없으므로 DB에 직접 물어서 확인한다. 이 확인이 실패하면 기동하지 않고 멈춘다. 스키마가 없는 DB에 앱이 붙으면 기동에 실패하고 `exchange-app.service`의 `StartLimitBurst`에 걸려 실패 상태로 남는다.

```bash
psql "host=192.168.10.31 port=5432 dbname=exchange user=exchange_app \
      sslmode=verify-full sslrootcert=/etc/exchange/db01.crt" \
     -c "SELECT count(*) FROM flyway_schema_history;"
```

`--generate-app-secrets`를 쓰지 않는다. 마스터키, 페퍼, 세션 시드 세 값은 APP01과 같아야 하므로 APP01의 값을 그대로 옮긴다. 값이 다르면 회원 세션과 비밀번호 검증이 노드마다 갈린다. DB 비밀번호와 인증서는 APP01과 같은 방법으로 넣는다.

기동한 뒤 이 노드는 리더가 아니므로 readiness가 503이어야 정상이다. 503이 나왔다고 실패로 적지 않는다.

## 방화벽

방화벽 계통은 하나다. 공통 기반 `scripts/00-common-setup.sh`가 `table inet vmlab`을 만들고 input 정책을 drop으로 두며 관리 SSH 22와 OPS01이 읽는 9100을 이미 열어 둔다. 앱 절차는 그 위에 얹기만 한다. 그래서 공통 기반을 먼저 적용하지 않고 앱 절차를 시작하지 않는다.

앱이 더 열 것은 8080과 8081 둘뿐이므로 `/etc/vm-lab/firewall.d/21-app01.nft` 같은 역할 파일 하나만 덧붙인다. `add rule` 형태로만 쓰고 테이블과 체인을 다시 선언하지 않는다. 역할 파일만 따로 검사할 수 없으므로 공통 파일 전체를 `nft --check`로 검사한 뒤 `systemctl reload vm-lab-firewall.service`로 적용한다. 검사에 실패하면 역할 파일을 지우고 멈춘다.

`firewall-host.sh`가 만드는 `inet failstop` 단독 테이블은 쓰지 않는다. 그 경로는 input 정책이 accept라 적은 포트만 출발지로 거르고, 접근제어도의 "허용하지 않은 새 인바운드 연결은 차단"을 만족하지 못한다. 배포판 `nftables.service`도 두 번째 정책원으로 살려 두지 않고 disable과 mask 상태로 둔다. 정책원이 둘이면 무엇이 막았는지 설명할 수 없다. 단독 테이블로 먼저 시험한 노드가 있으면 `/etc/failstop/firewall.nft`와 `failstop-firewall.service`를 걷어낸 뒤 역할 파일로 옮긴다.

같은 이유로 node_exporter도 한 번만 설치한다. `/usr/local/bin/node_exporter`와 유닛, `listen.env`가 이미 있으면 설치 스크립트는 덮어쓰지 않고 리슨 주소가 계획과 같은지만 확인한다. 공통 기반이 올린 것을 앱 절차가 다른 내용으로 덮어쓰면 수집 대상 목록이 조용히 바뀐다. 일부러 다시 깔아야 하면 `--force-node-exporter`를 준다.

역할 파일이 여는 목록은 주소계획의 포트 표와 같다.

| 포트 | 허용 출발지 | 용도 |
|---|---|---|
| TCP 8080 | 192.168.10.11/32 | API. EDGE01만 |
| TCP 8081 | 192.168.10.41/32 | Actuator. OPS01만 |
| TCP 9100 | 192.168.10.41/32 | node_exporter. OPS01만 |
| TCP 22 | 192.168.56.0/24 | 관리 SSH |

22와 9100은 공통 파일이 이미 열었으므로 역할 파일에는 8080과 8081만 들어간다. 기본 정책이 drop이라 포트마다 drop 줄을 따로 붙이지 않는다. `lo`와 기존 연결은 공통 파일의 앞쪽 규칙에서 통과한다. 적용 전에 `nft --check`를 통과해야 하며 실패하면 적용하지 않는다.

이 규칙은 input과 forward만 다룬다. 나가는 통신 제한은 pfSense가 맡으므로 여기서 아웃바운드 차단을 확인했다고 적지 않는다.

## 확인 절차

아래를 직접 실행하고 출력을 증거로 남긴다. 실행하지 않은 항목을 통과로 적지 않는다.

앱 노드에서 확인하는 것은 다음과 같다.

```bash
java -version
systemctl status exchange-app --no-pager
ss -ltnp
sudo ausearch -m avc -ts recent
sudo grep -i thread /etc/exchange/app.env
sudo systemctl is-enabled exchange-app node_exporter vm-lab-firewall
sudo systemctl is-enabled nftables      # masked 여야 한다
sudo nft list table inet vmlab
```

`ss -ltnp`에서 8080, 8081, 9100이 각 노드의 `192.168.10.2x`에만 있어야 한다. `0.0.0.0`이나 `127.0.0.1`에 있으면 바인딩 설정이 잘못된 것이다. 22는 `192.168.56.2x`에만 있어야 한다.

`grep -i thread`는 출력이 없어야 한다. `application.yml`이 `spring.threads.virtual.enabled=false`로 못 박은 값을 환경변수로 덮지 않았다는 확인이다. 2026년 9월 21일 학교 app-1에서 이 설정을 켠 채 HikariCP 인계 경로가 라이브락에 빠져 요청 처리가 전면 중단됐고, 그 결정이 ADR 0033이다.

`ausearch`에 `exchange-app`이나 `node_exporter` 관련 denied가 나오면 SELinux를 끄지 않는다. 필요한 포트와 문맥만 `semanage`로 추가하고 무엇을 왜 추가했는지 기록한다.

다른 노드에서 확인하는 것은 다음과 같다.

```bash
# OPS01에서
curl -fsS http://192.168.10.21:8081/actuator/health/readiness
curl -fsS http://192.168.10.22:8081/actuator/health/readiness
curl -fsS http://192.168.10.22:8081/actuator/prometheus | head

# EDGE01에서
curl -o /dev/null -w '%{http_code}\n' http://192.168.10.21:8080/api/v1/markets
curl -o /dev/null -w '%{http_code}\n' http://192.168.10.21:8080/actuator/health
```

리더 노드의 readiness는 200 UP, 대기 노드는 503 OUT_OF_SERVICE다. 두 대가 동시에 200이면 안 된다. 마켓 API는 200이며, 마켓 기본값을 등록하기 전에는 빈 배열이므로 등록 후에 판정한다. 8080의 `/actuator/health`는 404여야 한다. prod는 actuator 전량을 8081로 옮기므로 고객 평면에 actuator가 없다.

차단은 허용과 같은 명령을 허용 노드와 차단 노드에서 각각 새 연결로 실행해 비교한다. EDGE01에서 8081, OPS01이 아닌 노드에서 9100에 붙으면 연결이 성립하지 않아야 한다. 기존 연결이 열려 있으면 `ct state established`로 통과하므로 반드시 새 연결로 시험한다.

OPS01의 Prometheus에서 `up` 지표로 수집 성공까지 확인한다. curl로 지표가 나오는 것과 수집기가 실제로 긁는 것은 다른 확인이다.

## 리더 전환 확인

APP01의 `exchange-app`을 중지하고 OPS01에서 APP02의 readiness가 200으로 바뀌는 시각을 잰다.

```bash
# APP01에서
sudo systemctl stop exchange-app

# OPS01에서
while true; do
  printf '%s %s\n' "$(date -Is)" \
    "$(curl -s -o /dev/null -w '%{http_code}' http://192.168.10.22:8081/actuator/health/readiness)"
  sleep 1
done
```

전환은 리스 TTL만큼 지연된다. 이 값은 VM에서 실제로 측정하고 학교나 로컬 측정값을 옮겨 적지 않는다.

DB 승격 뒤의 접속 전환은 이 문서에서 절차만 정했다. 승격은 수동이고 복제는 비동기라 미복제 기록이 유실될 수 있다. 앱 쪽에서도 이 점을 숨기지 않는다.

## 재부팅 확인

두 대를 재부팅하고 같은 확인을 다시 한다.

```bash
sudo reboot
# 다시 접속한 뒤
systemctl status exchange-app node_exporter --no-pager
ss -ltnp
sudo nft list table inet vmlab
```

서비스 자동 기동, 리슨 주소, 방화벽 규칙이 재부팅 전과 같아야 한다. pfSense 구축이 끝나 기본 경로를 `192.168.10.1`로 바꾸고 NAT를 내린 뒤에는 이 검증을 한 번 더 한다.

## 증거로 남기는 것과 남기지 않는 것

명령과 출력을 단계별로 남긴다. `ss -ltnp`, `nft list table`, `systemctl status`, readiness 응답, 차단 시험의 실패 출력이 판정의 근거다. 실행하지 않은 명령의 출력을 만들어 적지 않는다.

비밀 값 입력 화면은 캡처하지 않는다. `app-secret.env`의 내용, DB 비밀번호, 마스터키와 페퍼와 세션 시드는 문서, 로그, 증거 어디에도 남기지 않는다. 캡처를 건너뛴 구간은 건너뛴 사실과 이유를 적는다.

## 아직 정하지 못한 것과 위험

8081을 OPS01에만 허용하면 EDGE01이 readiness로 리더를 판별할 수 없다. 주문, 취소, WebSocket을 현재 리더로 보내는 방법을 EDGE01 사양과 함께 정해야 한다. 8081 허용 목록에 EDGE01을 넣기로 하면 `00-주소계획.md`의 표를 먼저 고치고 근거를 남긴 뒤 설치 스크립트에 `--edge-health`를 준다.

DB01의 TLS 서버 인증서에 `192.168.10.31` IP SAN이 없으면 `sslmode=verify-full` 접속이 실패한다. 발급 조건을 DB01 사양과 맞춰야 한다. DB01이 TLS를 켜지 않기로 하면 `--db-url`의 `sslmode`를 그 결정에 맞춰 명시하고 이유를 기록한다.

연결 예산을 다시 계산해야 한다. 한 노드가 요청 16, append 14, 리스 2로 32개이고 두 대면 64개다. DB01의 `max_connections` 100 안에서 postgres_exporter와 관리 접속까지 합산해야 한다. 초과하면 DB01 사양과 함께 풀 크기를 다시 정한다.

JVM 힙 최대 768MiB와 서비스 `MemoryMax` 1400MiB는 학교 t3.small 값을 그대로 쓴 것이다. VM은 3GiB라 여유는 있으나 VM에서 측정한 값이 아니다.

JAR에서 Flyway 라이브러리와 SQL을 꺼내 단일 파일 소스로 실행하는 절차는 저장소에 없고 이번에 정한 것이다. 첫 실행에서 실제로 확인해야 한다. `java-21-openjdk-headless`만으로 `jdk.compiler` 모듈이 있는지도 그때 확인된다. 없으면 `java-21-openjdk-devel`을 설치한다. 스크립트가 실행 전에 이 조건을 검사한다.

SELinux Enforcing에서 앱이 거부되는 경우를 아직 확인하지 않았다. 첫 기동 뒤 `ausearch`로 확인하고 필요한 포트와 문맥만 추가한다.

이 문서의 모든 절차는 미수행이다. 실행 결과는 실제로 실행한 뒤에 따로 기록한다.
