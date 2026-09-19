# PostgreSQL 백업과 격리 복원

`backup.py`는 별도 DB 로그인으로 전체 데이터베이스를 `pg_dump` custom 형식에 저장한다. 백업 성공과 복원 성공은 별도로 판정한다. 복원 검사는 포트를 공개하지 않는 새 PostgreSQL 컨테이너를 만들고, 거래 데이터와 원장 불변식을 확인한 뒤 그 컨테이너만 삭제한다. 원본 DB 접속 주소를 복원 대상으로 받지 않는다.

운영 서버에는 Linux, Python 3, Docker Engine, systemd와 `/opt/failstop`의 저장소가 필요하다. Docker의 호스트 네트워크에서 백업 대상 PostgreSQL에 접속할 수 있어야 한다. PostgreSQL 클라이언트 이미지는 기본 `postgres:17-alpine`이다. 운영에서는 검증한 이미지 digest를 `--image` 인자로 지정하고 서비스의 `ExecStart`에도 같은 값을 적용한다.

## 계정과 비밀 파일

DB 스키마를 만든 뒤 백업 전용 계정을 준비한다. 앱, 조회, 봇 또는 관리 계정을 재사용하지 않는다. [provision-backup-role.sql](provision-backup-role.sql)은 `pg_read_all_data`를 부여하고 기본 트랜잭션을 읽기 전용으로 설정한다. 기존 관리 권한이나 다른 역할 멤버십이 있는 계정은 거부한다. 이 계정은 백업을 위해 모든 테이블을 읽을 수 있으므로 조회 API의 제한된 계정과 다르다.

root 셸에서 다음과 같이 파일을 준비한다. 예시 비밀번호는 없으며 실제 값은 서버에서 입력한다.

```bash
sudo install -d -o root -g root -m 0755 /etc/failstop
sudo install -o root -g root -m 0600 \
  /opt/failstop/deploy/backup/backup.env.example /etc/failstop/backup.env
sudoedit /etc/failstop/backup.env
```

`DB_HOST`, `DB_PORT`, `DB_NAME`, `BACKUP_DB_USER`, `BACKUP_DB_PASSWORD`를 채운다. 파일은 `KEY=value` 한 줄 형식이며 변수 치환은 하지 않는다. 아래 Docker 명령과 같은 파일을 쓰므로 값에 따옴표를 덧붙이지 않는다. 비밀번호에 줄바꿈을 넣지 않는다.

Linux root 셸에서 아래 명령을 실행한다. `DB_ADMIN_USER`는 스키마 소유자 로그인으로 바꾸며, 그 비밀번호는 `psql` 프롬프트에 입력한다. 백업 비밀번호는 root 전용 파일에서 컨테이너 환경으로 전달되어 SQL의 문자열 인용으로 처리된다. 셸 추적이나 환경 전체 출력은 켜지 않는다.

```bash
sudo docker run --rm -it --network host \
  --env-file /etc/failstop/backup.env \
  --env DB_ADMIN_USER=exchange_owner \
  --mount type=bind,source=/opt/failstop/deploy/backup/provision-backup-role.sql,target=/provision.sql,readonly \
  postgres:17-alpine sh -c \
  'exec psql -X -W -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_ADMIN_USER" -d "$DB_NAME" -f /provision.sql'
```

`backup.py`는 Linux root 실행과 비밀 파일의 root 소유권, `0600` 권한, 심볼릭 링크 여부를 검사한다. 실제 `pg_dump` 비밀번호는 임시 `0600` pgpass 파일에만 쓰고 명령 인자로 넘기지 않는다. 저장 디렉터리는 `0700`, 완성한 덤프는 `0600`이며, 실패한 임시 파일은 정리한다.

## 수동 실행과 예약

예약을 켜기 전에 실제 접속과 백업을 먼저 확인한다.

```bash
sudo python3 /opt/failstop/deploy/backup/backup.py backup \
  --env /etc/failstop/backup.env
```

성공하면 JSON의 `archive`에 `/var/backups/failstop/<UTC 시각>-<임의 ID>.dump`가 나온다. 겹치는 수동 실행은 파일 잠금으로 거부한다. 다음 절차로 복원까지 확인한 뒤 예약을 설치한다.

```bash
sudo sh /opt/failstop/deploy/backup/install.sh
sudo systemctl status failstop-backup.timer --no-pager
sudo systemctl list-timers failstop-backup.timer --no-pager
sudo journalctl -u failstop-backup.service --since today --no-pager
```

타이머는 매일 한국 시간 03:30부터 최대 5분의 지연을 두고 실행한다. 서버가 꺼져 놓친 예약은 부팅 후 실행한다. 필요하면 설치된 타이머에 systemd override를 적용한다. 예약을 중단할 때는 `sudo systemctl disable --now failstop-backup.timer`를 쓴다. 성공 기록은 백업 파일이 생성됐다는 뜻이며 복원 가능성을 대신 증명하지 않는다.

## 새 DB에 복원 검증

`--archive`에는 앞서 출력된 덤프의 실제 절대 경로를 넣는다. 파일 이름을 임의로 운영 DB 이름과 맞출 필요는 없다.

```bash
sudo python3 /opt/failstop/deploy/backup/backup.py restore-test \
  --archive /var/backups/failstop/실제-백업.dump \
  --result /var/backups/failstop/restore-check.json \
  --metrics-dir /var/lib/failstop/metrics
```

검사는 고유 이름의 새 컨테이너를 `--network none`으로 시작하고 그 안에 복원한다. 호출자가 기존 컨테이너나 원본 DB를 지정하는 옵션은 없다. 원장, 주문 명령, 이벤트, 체결이 모두 있어야 하며 [불변식 SQL](../../harness/sql/summary.sql)의 CRITICAL/HIGH 판정이 모두 PASS여야 성공한다. 거래가 없는 새 DB는 이 복구 증명 조건을 충족하지 않는다.

결과 JSON에는 각 판정과 데이터 건수를 남긴다. `application_boot_checked: false`는 이 CLI가 Java 앱 기동까지 검사하지 않았다는 뜻이다. 앱 기동과 연결 전환을 포함한 실습은 [DB 복구 실습](../lab/README.md)을 따른다. 복원 검사는 역할과 ACL을 재현하지 않고 `--no-owner --no-acl`로 스키마·데이터·원장 상태를 확인한다. 실제 대체 서버는 [배포 안내](../README.md)의 마이그레이션 및 역할 프로비저닝 절차도 수행해야 한다.

## 관측과 한계

`/var/lib/failstop/metrics/failstop_backup.prom`에 마지막 성공 시각과 마지막 실행 성공 여부를 기록한다. 실패하면 이전 성공 시각을 보존하고 `failstop_backup_last_run_success`를 `0`으로 바꾼다. 복원 검사에 `--metrics-dir`를 주면 `failstop_backup_restore` 이름으로 같은 정보를 기록한다. [호스트 관측](../observability/README.md)의 node exporter textfile 수집 경로에 이 디렉터리를 연결한다.

이 백업은 DB 단위 논리 백업이다. WAL 보관에 따른 PITR, 서버 밖 복사, 암호화 저장, 보존 기간과 자동 삭제는 구성하지 않았다. 운영 전에는 저장 용량과 보존 정책을 정하고 별도 장애 영역에 복사해야 한다. 비동기 standby에서 백업하면 복제 지연만큼 최근 데이터가 빠질 수 있으므로 대상과 시각을 기록한다.

Windows의 Docker Desktop에서 Linux 컨테이너로 실행 본문을 검사할 수 있지만, 그것만으로 운영 서버의 예약과 네트워크가 검증되지는 않는다. 해당 Linux 서버에서 수동 실행과 복원이 성공한 다음 타이머를 활성화한다.
