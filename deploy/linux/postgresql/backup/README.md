# 학교 DB 예약 백업과 외부 보관

학교 EC2의 PostgreSQL을 Docker 없이 OS 서비스로 매일 백업하는 도구다. 두 DB 서버에 같은 타이머를 두면, 실행할 때마다 현재 역할을 확인해 Primary에서만 물리 베이스백업을 만든다. 백업은 매니페스트와 WAL까지 검증한 뒤에만 완료로 표시하고, SHA-256을 기록하고, 설정이 있으면 비공개 S3에 올려 원격 크기와 체크섬을 대조한다. 결과는 node_exporter textfile 지표로 남겨 경보가 읽는다.

**이 폴더의 파일은 저장소에만 있고 학교 서버에는 아직 설치하지 않았다.** 2026-09-23 현재 학교에서 확인된 백업 두 개는 옛 Primary(AZ-a, 지금 Standby)의 로컬 디스크에만 있고 PGDATA 밖이라 재편입 베이스백업에도 복사되지 않았다. 쓰기를 받는 AZ-c Primary(`172.31.33.195`)에는 백업·예약·외부 보관이 없다([AWS 작업 안내](../../../../docs/reports/AWS_작업.md)의 2026-09-20 백업 절, [증거](../../../../증거본/2026-09-20_백업_복구_보고/README.md)). 아래 설치 절차를 실행하고 증거본에 남기기 전에는 이 도구를 운영 백업 완료로 쓰지 않는다.

## 한 번 실행할 때 일어나는 일

`db-backup.py`는 postgres OS 계정으로 돌고 로컬 소켓 peer 인증으로 접속하므로 비밀번호를 쓰지 않는다. 먼저 설정 파일이 root 소유이고 그룹·기타 쓰기 권한이 없는지 확인한다. 백업 디렉터리의 `.lock`에 `flock`을 걸어 수동 실행과 타이머 실행이 겹치지 않게 한다.

다음으로 `pg_is_in_recovery()`로 현재 역할을 본다. 설정한 역할(`BACKUP_ROLE`, 기본 `primary`)과 다르면 아무것도 만들지 않고 `SKIPPED_ROLE`로 끝난다. 그래서 두 DB에 같은 설정을 설치해 두면 수동 승격·재편입 뒤에도 그때의 Primary가 백업을 이어 받는다. 역할이 맞으면 백업 디렉터리가 PGDATA 밖인지 확인하고, 디스크 여유를 계산한다. 압축 전 DB 크기의 세 배(압축 산출물, 검증용 압축 해제본, 논리 덤프)에 남겨 둘 하한을 더한 값보다 여유가 적으면 새 백업을 만들지 않고 실패한다. 하한은 `MIN_FREE_BYTES`와 전체 용량의 `MIN_FREE_PERCENT`(기본 20%) 중 큰 값이다. 학교 디스크 경보가 여유 15% 미만에서 울리므로 백업이 그 선을 넘기지 않게 기본값을 그보다 높게 두었다.

백업은 `pg_basebackup --format=tar --wal-method=stream --compress=gzip:6 --checkpoint=spread`로 만든다. `base.tar.gz`, 백업 중 생긴 WAL인 `pg_wal.tar.gz`, `backup_manifest` 세 파일이 나온다. `spread`는 Primary의 정규 체크포인트가 끝나기를 기다리므로 즉시 체크포인트를 거는 `fast`보다 느리지만 쓰기 지연을 만들지 않는다. [ADR 0025](../../../../docs/reference/adr/0025-rejoin-by-basebackup-no-wal-retention.md)가 기록한 대로 부하 중 `fast`는 리더 리스 갱신을 밀어낸 적이 있다. 로컬 시험에서 쓰기가 계속되는 111MB DB의 첫 백업은 체크포인트 대기 때문에 4분 32초가 걸렸다.

PostgreSQL 17의 `pg_verifybackup`은 tar 형식을 직접 읽지 못한다. 그래서 두 tar를 백업 디렉터리 옆 임시 경로에 풀고 `pg_verifybackup --manifest-path`로 파일 크기·체크섬과 WAL 연속성을 검사한 뒤 풀어 놓은 사본을 지운다. tar 안에 절대 경로, `..`, 링크, 장치 파일이 있으면 풀지 않고 실패한다. 검증이 통과하면 논리 덤프를 추가하고, 모든 파일의 SHA-256을 `metadata.json`과 `sha256sum -c`로 확인할 수 있는 `SHA256SUMS`에 적는다. 마지막으로 `.staging-<ID>` 디렉터리 이름을 `<ID>`로 바꾸는 것이 완료 표시다. 이름이 바뀌기 전의 산출물은 보존 개수나 마지막 성공으로 세지 않는다.

완료된 백업만 외부 보관 단계로 넘어간다. 외부 보관이 끝나면 완료 백업을 이름순으로 정렬해 `KEEP_LOCAL`(기본 3)개만 남기고 오래된 것을 지운다. 이번 백업은 시계가 뒤로 가도 지우지 않는다. 실패한 시도는 `failed-<ID>`로 이름을 바꿔 가장 최근 한 건만 남기며, 실패한 실행은 이전 백업을 하나도 지우지 않는다. 이 도구는 `BACKUP_DIR` 안의 `<ID>` 형식 디렉터리만 다룬다. 2026-09-19에 AZ-a에 남긴 `/var/lib/pgsql/backups/exchange-before-markets-20260919.dump`와 `/var/lib/pgsql/backup-checks/`의 기존 결과는 건드리지 않는다.

| 상태 | 뜻 | 종료 코드 |
|---|---|---|
| `LOCAL_ONLY` | 로컬 백업 검증 완료. 외부 보관 설정이 비어 있어 올리지 않았다. 외부 보관 성공이 아니다. | 0 |
| `OFFSITE_VERIFIED` | 로컬 검증 완료, S3 업로드와 원격 크기·SHA-256·암호화 대조 통과 | 0 |
| `OFFSITE_FAILED` | 로컬 검증은 완료, 업로드나 원격 대조 실패 | 2 |
| `SKIPPED_ROLE` | 현재 역할이 설정한 역할과 달라 실행하지 않았다 | 0 |
| `FAIL` | 설정·접속·디스크·베이스백업·검증·논리 덤프 중 한 단계 실패. `stage`에 단계가 있다 | 1 |
| `BUSY` | 다른 실행이 잠금을 잡고 있다. 지표를 바꾸지 않는다. 서비스는 `SuccessExitStatus=75`로 이 종료를 실패로 남기지 않는다 | 75 |

## 논리 덤프도 함께 두는 이유

물리 베이스백업이 주 백업이다. 서버나 볼륨을 통째로 잃었을 때 모든 DB, 역할, 설정을 한 시점으로 되살리는 방법이 이것뿐이다. 그래도 같은 실행에서 `pg_dump -Fc`와 비밀번호를 뺀 `pg_dumpall --globals-only --no-role-passwords`를 함께 만든다(`LOGICAL_DUMP=on`). 근거는 세 가지다.

첫째, 10월 초 개인 계정 재배포([ADR 0029](../../../../docs/reference/adr/0029-school-aws-first-personal-account-expansion.md))에서 데이터를 옮길 수 있는 형식이 필요하다. 물리 백업은 같은 PostgreSQL 주 버전과 같은 아키텍처에서만 복원할 수 있고, 개인 계정은 Graviton 등 다른 규격이 후보다. 둘째, 잘못 바뀐 테이블 하나를 살필 때 클러스터 전체를 올리지 않고 `pg_restore`로 필요한 객체만 꺼낼 수 있다. 셋째, 학교에서 실제로 복원까지 확인한 절차(2026-09-19 백업 `20260919T162452010003Z`)가 이 형식이었다. 비용은 작다. 그 시점 64MB DB의 덤프가 4.5MB였다. 대가도 있다. 덤프가 도는 동안 스냅샷을 잡아 vacuum이 늦어지고, DDL과 겹치면 `--lock-wait-timeout=10s` 뒤 실패한다. 논리 덤프가 실패하면 그 실행 전체를 실패로 본다. 논리 덤프의 목차(`pg_restore --list`)에 테이블 데이터가 있는지만 실행 때 확인하며, 논리 덤프 자체의 전체 복원은 아래 기존 모드로 따로 확인한다.

## 파일

| 파일 | 서버 위치 | 용도 |
|---|---|---|
| [db-backup.py](db-backup.py) | `/usr/local/lib/failstop/db-backup.py` | 역할 확인, 백업, 검증, 외부 보관, 보존, 지표 |
| [db-backup.conf.example](db-backup.conf.example) | `/etc/failstop/db-backup.conf` (root:postgres 0640) | 설정. 비밀 값 없음. 실제 버킷 이름은 서버 파일에만 적는다 |
| [failstop-db-backup.service](failstop-db-backup.service), [.timer](failstop-db-backup.timer) | `/etc/systemd/system/` | 매일 03:40 KST, 최대 20분 무작위 지연, `Persistent=true`, postgres 실행, 샌드박스 |
| [backup-alerts.yml](backup-alerts.yml) | bot-1 `/etc/prometheus/backup-alerts.yml` (root:root 0644) | 백업 경보 7개. [시험 파일](backup-alerts.test.yml)로 `promtool test rules` |
| [../../observability/node_exporter-textfile.env.db](../../observability/node_exporter-textfile.env.db) | 두 DB의 `/etc/node_exporter/textfile.env` (root:root 0644) | node_exporter가 읽을 textfile 디렉터리 |
| [../backup-restore-check.py](../backup-restore-check.py) | `/usr/local/lib/failstop/backup-restore-check.py` | `--basebackup`으로 이 도구의 백업을 격리 복원해 확인 |
| [../../../../harness/sql/summary.sql](../../../../harness/sql/summary.sql) | `/usr/local/lib/failstop/summary.sql` | 복원본의 거래 정합성 검사 |

서비스는 `ProtectSystem=strict`로 파일시스템을 읽기 전용으로 두고 `/var/lib/pgsql/backups`와 `/var/lib/node_exporter/textfile`에만 쓰게 한다. 두 디렉터리가 없으면 서비스가 시작되지 않는다. `TEXTFILE_DIR`이나 `BACKUP_DIR`을 바꾸면 유닛의 `ReadWritePaths`도 같이 바꾼다. 네트워크는 로컬 소켓과 S3·인스턴스 메타데이터 접속만 쓰고, `MemoryMax=512M`, 낮은 CPU·IO 가중치로 거래 쿼리에 자원을 양보한다.

## 학교 Primary 설치

Primary(AZ-c, `172.31.33.195`)에서 먼저 한다. [증거본 규칙](../../../../증거본/README.md)에 따라 새 날짜 폴더를 만들고 명령 한 개마다 화면과 출력을 남긴다. 파일은 기존과 같이 비공개 S3 배포 묶음이나 base64 전송으로 옮기고 SHA-256을 대조한다.

1. 대상과 전제를 읽기 전용으로 확인한다. `pg_is_in_recovery()`가 `f`여야 Primary다. peer 복제 접속 규칙(`local replication all peer`)이 있어야 `pg_basebackup`이 소켓으로 접속한다. `max_wal_senders=4` 중 Standby가 한 개, 백업이 두 개를 쓴다.

   ```bash
   hostname -I
   sudo -u postgres psql -X -qAt -c 'SELECT pg_is_in_recovery()'
   sudo -u postgres psql -X -qAt -c 'SHOW data_directory'
   sudo -u postgres psql -X -qAt -c "SELECT line_number,database,user_name,auth_method FROM pg_hba_file_rules WHERE type='local'"
   sudo -u postgres psql -X -qAt -c 'SELECT count(*) FROM pg_stat_replication'
   df -h /var/lib/pgsql
   python3 --version; pg_basebackup --version; pg_verifybackup --version
   ```

2. 스크립트, 설정, 정합성 SQL을 배치한다. 설정은 예시를 그대로 쓰면 외부 보관 미설정 상태다.

   ```bash
   sudo install -d -o root -g root -m 0755 /usr/local/lib/failstop /etc/failstop
   sudo install -o root -g root -m 0755 db-backup.py backup-restore-check.py /usr/local/lib/failstop/
   sudo install -o root -g root -m 0644 summary.sql /usr/local/lib/failstop/summary.sql
   sudo install -o root -g postgres -m 0640 db-backup.conf.example /etc/failstop/db-backup.conf
   sha256sum /usr/local/lib/failstop/* /etc/failstop/db-backup.conf
   ```

3. 백업과 지표 디렉터리를 만든다. 이미 있으면 먼저 `ls -ld`로 소유자를 확인한다. node_exporter는 textfile 디렉터리를 읽기만 한다.

   ```bash
   sudo install -d -o postgres -g postgres -m 0700 /var/lib/pgsql/backups /var/lib/pgsql/backups/basebackup
   sudo install -d -o root -g root -m 0755 /var/lib/node_exporter
   sudo install -d -o postgres -g node_exporter -m 0755 /var/lib/node_exporter/textfile
   ```

4. 타이머를 켜기 전에 수동으로 한 번 실행한다. 출력 JSON의 `status`가 `LOCAL_ONLY`이고 `offsite.status`가 `UNCONFIGURED`인지 본다. 이어서 파일과 체크섬, 지표를 확인한다.

   ```bash
   sudo -u postgres python3 -B /usr/local/lib/failstop/db-backup.py --config /etc/failstop/db-backup.conf
   sudo -u postgres ls -la /var/lib/pgsql/backups/basebackup/
   sudo -u postgres sh -c 'cd /var/lib/pgsql/backups/basebackup/<ID> && sha256sum -c SHA256SUMS'
   cat /var/lib/node_exporter/textfile/failstop_db_backup.prom
   ```

5. 방금 만든 백업을 TCP 없는 시험 클러스터에 복원해 확인한다. 운영 DB에는 접속하지 않는다. `status`가 `PASS`, `counts_within_backup_window`가 `true`, 필수 정합성 실패가 0건이어야 한다. 시험 클러스터는 포트 55432의 Unix 소켓만 열고 검사가 끝나면 멈춘 뒤 지운다.

   ```bash
   sudo -u postgres python3 -B /usr/local/lib/failstop/backup-restore-check.py \
     --invariants /usr/local/lib/failstop/summary.sql \
     --basebackup /var/lib/pgsql/backups/basebackup/<ID>
   ```

6. systemd에 올리고 샌드박스 안에서 한 번 더 실행한 뒤 타이머를 켠다. `systemd-analyze security`의 점수는 참고 기록으로 남긴다.

   ```bash
   sudo install -o root -g root -m 0644 failstop-db-backup.service failstop-db-backup.timer /etc/systemd/system/
   sudo systemd-analyze verify /etc/systemd/system/failstop-db-backup.service /etc/systemd/system/failstop-db-backup.timer
   sudo systemctl daemon-reload
   sudo systemctl start failstop-db-backup.service
   systemctl status failstop-db-backup.service --no-pager
   sudo journalctl -u failstop-db-backup.service -n 60 --no-pager
   sudo systemd-analyze security failstop-db-backup.service --no-pager
   sudo systemctl enable --now failstop-db-backup.timer
   systemctl list-timers failstop-db-backup.timer --no-pager
   ```

Standby(AZ-a, `172.31.1.229`)에도 1~3단계와 6단계를 같은 설정으로 적용한다. 수동 실행 결과는 `SKIPPED_ROLE`이어야 하고 새 백업을 만들지 않는다. 다음 수동 승격 뒤에는 새 Primary에서 자동으로 백업이 시작되지만, 승격 절차 끝에 수동 실행과 격리 복원을 한 번 더 확인한다. 옛 Primary에 남은 로컬 백업은 그 호스트에서 지워지지 않고 보존 개수 계산에도 다른 호스트와 섞이지 않는다.

## 관측 연결

지표는 `/var/lib/node_exporter/textfile/failstop_db_backup.prom`에 쓴다. 같은 디렉터리의 임시 파일에 쓰고 `fsync`한 뒤 이름 변경으로 바꾸므로 node_exporter가 쓰다 만 파일을 읽지 않는다.

| 지표 | 뜻 |
|---|---|
| `failstop_db_backup_role_eligible` | 현재 역할이 백업 대상이면 1 |
| `failstop_db_backup_last_run_timestamp_seconds` | 마지막 실행 종료 시각 |
| `failstop_db_backup_last_run_success` | 검증된 백업 생성과 로컬 정리까지 끝났으면 1. 역할이 달라 건너뛴 실행에는 없다 |
| `failstop_db_backup_last_verify_success` | 매니페스트·WAL 검증 결과. 검증 단계에 가지 못한 실행에는 없다 |
| `failstop_db_backup_last_success_timestamp_seconds`, `_last_success_bytes` | 가장 최근 완료 백업의 시각과 크기 |
| `failstop_db_backup_local_backups` | 이 호스트의 완료 백업 수 |
| `failstop_db_backup_offsite_configured` | 버킷과 접두어가 설정됐으면 1 |
| `failstop_db_backup_offsite_last_success` | 이번 업로드·원격 대조 결과. 미설정이면 없다 |
| `failstop_db_backup_offsite_last_success_timestamp_seconds` | 원격 대조를 마지막으로 통과한 시각 |
| `failstop_backup_last_success_timestamp_seconds`, `failstop_backup_last_run_success` | 위 두 지표와 같은 값을 옛 이름으로 한 번 더 낸 것 |

같은 파일에 옛 이름을 함께 쓰는 이유는 기존 소비자가 아직 옛 백업 도구([deploy/backup/backup.py](../../../backup/backup.py))의 이름을 조회하기 때문이다. 학교 일일 AI 보고의 `backup_success` 항목([config.school.json](../../reporting/config.school.json)), MSP DB 대시보드의 백업 경과 패널 두 개([15-msp-db-replication.json](../../observability/msp-dashboards/15-msp-db-replication.json)), 선택 실행하는 MSP 관제 콘솔의 백업 시각([metrics.py](../../../console/metrics.py))이 `failstop_backup_last_success_timestamp_seconds`를 읽는다. 옛 이름을 내지 않으면 설치 뒤에도 이 화면과 보고가 계속 백업 없음으로 보인다. 소비자를 새 이름으로 옮긴 뒤에는 옛 이름을 뺄 수 있다. 옛 도구를 같은 호스트에서 같은 textfile 디렉터리로 함께 돌리면 같은 이름이 두 파일에 생겨 node_exporter가 수집 오류를 내므로 함께 쓰지 않는다. 옛 이름도 새 이름과 같이 호스트별 값이다. 승격 전에 만든 백업이 남은 옛 Primary는 자기 로컬 백업의 시각을 계속 내므로, 호스트별 패널에서는 그 호스트만 오래된 것으로 보일 수 있다. 두 DB 중 가장 최근 값을 보는 패널과 아래 `DatabaseBackupStale` 경보는 영향을 받지 않는다. 옛 WSL·Docker 관측의 `DatabaseBackupUnknownOrStale`([linux-operations.yml](../../../observability/rules/linux-operations.yml))은 호스트별로 26시간을 보므로 학교 Prometheus에 함께 넣지 않는다.

textfile 경로도 옛 관측과 다르다. 옛 [install-node-exporter.sh](../../../observability/install-node-exporter.sh)는 WSL·Docker 실습용으로 `/var/lib/failstop/metrics`를 썼다. 학교 node_exporter는 그 스크립트로 설치하지 않았고 2026-09-23 현재 서버의 유닛에는 textfile 수집기가 꺼져 있다. 이 도구는 `/var/lib/node_exporter/textfile`을 쓰고, 저장소의 node_exporter 유닛이 그 경로를 읽는다.

저장소의 [node_exporter-host.service](../../observability/node_exporter-host.service)는 textfile 수집기를 켜고 디렉터리를 `/etc/node_exporter/textfile.env`의 `NODE_EXPORTER_TEXTFILE_DIR`에서 받는다. 같은 유닛을 app-1도 쓰므로 경로를 유닛에 적지 않고 호스트마다 이 파일로 정한다. 파일이 없으면 값이 비어 textfile을 읽지 않는다. DB 두 대에는 [node_exporter-textfile.env.db](../../observability/node_exporter-textfile.env.db)를 둔다. 없는 디렉터리를 가리키면 `node_textfile_scrape_error`가 1이 되므로 위 3단계에서 디렉터리를 만든 뒤에 둔다.

```bash
sudo install -o root -g root -m 0644 node_exporter-textfile.env.db /etc/node_exporter/textfile.env
# 유닛이 이미 저장소 판이면 다음 줄은 건너뛴다.
sudo install -o root -g root -m 0644 node_exporter-host.service /etc/systemd/system/node_exporter.service
sudo systemctl daemon-reload && sudo systemctl restart node_exporter
curl -s http://<DB 사설 주소>:9100/metrics | grep -E 'failstop_db_backup|failstop_backup_|node_textfile_scrape_error'
```

같은 유닛의 `--collector.systemd.unit-include`에는 `failstop-db-backup`이 들어 있다. 다만 이 유닛은 [학교 경보 규칙](../../reporting/school-alerts.yml)의 `LinuxServiceFailed`에서 뺐다. 한 번 실패하면 다음 날 실행까지 failed로 남아 아래 백업 경보와 같은 사건을 critical로 한 번 더 보내기 때문이다. 대신 지표로 기록되지 않은 실패만 `DatabaseBackupServiceFailed`가 알린다. 설정 오류, 지표 디렉터리 쓰기 실패, 메모리 상한 초과나 SIGKILL이 여기에 든다. `BUSY`(75)는 서비스의 `SuccessExitStatus=75`로 실패로 남지 않는다.

[backup-alerts.yml](backup-alerts.yml)은 bot-1의 `/etc/prometheus/backup-alerts.yml`에 둔다. 저장소의 [prometheus.yml](../../observability/prometheus.yml)은 이 경로를 `rule_files`에 이미 적어 두었다. 나열한 파일이 없으면 `promtool check config`가 실패하므로 서버에는 파일과 `rule_files` 줄을 함께 더한다. 두 DB의 백업 지표가 bot-1 Prometheus에서 조회되기 전에 넣으면 `DatabaseBackupStale`이 기록 없음으로 10분 뒤 발생한다. 그래서 Primary의 첫 수동 실행이 `LOCAL_ONLY`로 끝나고 `failstop_db_backup_last_success_timestamp_seconds{job="linux-host"}`가 조회된 뒤에 넣는다.

경보는 두 DB 중 어디에도 26시간 안의 검증 백업이 없거나 지표 자체가 없을 때(`DatabaseBackupStale`, critical), 검증 실패(`DatabaseBackupVerifyFailed`, critical), 검증 전 단계 실패(`DatabaseBackupRunFailed`), 외부 보관 실패(`DatabaseBackupOffsiteFailed`), 실제로 백업하는 호스트의 외부 보관 미설정(`DatabaseBackupOffsiteUnconfigured`, 30분 지속), 지표를 남기지 못한 서비스 실패(`DatabaseBackupServiceFailed`), textfile 읽기 실패(`LinuxTextfileMetricsInvalid`)다. 마지막 규칙은 워치독 결과를 읽는 app-1·bot-1의 textfile도 함께 본다. 외부 보관을 켜기 전까지 미설정 경보는 계속 발생 상태이며, 저장소의 [학교 Alertmanager 설정](../../reporting/alertmanager.school.yml)대로라면 `repeat_interval: 4h`에 따라 Teams와 보고 서비스로 4시간마다 다시 간다. 사실과 맞는 경보이지만 이 반복을 받아들일지, 이 경보만 반복 간격을 늘릴지는 설치 전에 정한다. 규칙을 추가하면 `promtool check rules`와 `promtool test rules backup-alerts.test.yml`을 먼저 통과시킨다.

## 외부 보관 켜기

학교 데이터 반출 정책을 확인하기 전에는 `S3_BUCKET`과 `S3_PREFIX`를 비워 둔다. 백업에는 회원·거래 데이터와 DB 역할의 SCRAM 검증값이 들어 있다. 확인할 것은 네 가지다. 이 데이터를 학교 계정의 비공개 S3에 둬도 되는지, 버킷 이름이 IAM 사용자 이름으로 시작하는 규칙을 지키는지, Block Public Access와 기본 암호화가 켜져 있는지, EC2 역할에 새 접두어의 `s3:PutObject`와 `s3:GetObject`(head-object에 필요)가 있는지다. 2026-09-20에 DB 서버가 [복구 준비 도구](../prepare-db-recovery.py)로 기존 버킷의 `transfer/` 접두어에 인증서 같은 작은 파일을 올린 기록은 있지만([증거 022](../../../../증거본/2026-09-20_백업_복구_보고/022_복제_암호화_수신준비.png)), 다른 접두어와 백업 크기의 업로드는 확인하지 않았다.

확인이 끝나면 서버의 `/etc/failstop/db-backup.conf`에만 버킷과 접두어를 적는다. 업로드는 `aws s3api put-object`로 파일마다 한 번에 올리고 `--checksum-sha256`을 붙여 S3가 받은 본문을 직접 검증하게 한다. 서버 측 암호화는 기본 SSE-S3(`AES256`)이다. 올린 뒤 `head-object --checksum-mode ENABLED`로 크기, 전체 객체 SHA-256, 암호화 방식, 메타데이터의 SHA-256을 대조한다. 한 파일이 5GiB를 넘으면 단일 업로드 상한 때문에 실패로 처리한다. 객체 키는 `<접두어>/<시스템 식별자>/<ID>/<파일>`이고 `SHA256SUMS`를 마지막에 올린다. 원격에 `SHA256SUMS`가 있으면 나머지 파일이 모두 대조됐다는 뜻이다. 결과는 백업 디렉터리의 `offsite.json`에 남는다. Access Key는 쓰지 않고 인스턴스 역할만 쓴다.

S3 쪽 오래된 백업은 이 도구가 지우지 않는다. 수명주기 규칙을 만들 권한이 없으면 사람이 주기적으로 정리해야 하며, 보관량은 대략 하루 백업 크기에 보관 일수를 곱한 값이다. 로컬 시험의 111MB DB는 백업 한 벌이 약 11~18MB였다. 요금은 적용 전에 공식 요금표로 다시 계산한다.

## 복원

시험 복원은 위 5단계의 `backup-restore-check.py --basebackup`이다. `SHA256SUMS` 전체를 먼저 대조하고, 두 tar를 풀어 `pg_verifybackup`을 통과시킨 뒤, 복원본의 설정을 소켓 전용 설정으로 덮어쓰고 시작한다. 복원본의 원래 설정은 운영 사설 주소 listen, TLS 키, `/etc/postgresql` include를 가리키므로 그대로 쓰지 않는다. 네 테이블(명령·이벤트·체결·원장)은 삭제·갱신 금지 트리거가 있는 append-only 기록이라, 복원 건수가 백업 시작 전 건수 이상이고 종료 뒤 건수 이하인지로 백업 시점까지 복원됐는지 확인한다. 이어서 정합성 SQL의 CRITICAL·HIGH가 모두 PASS여야 통과다. 통과하면 풀어 놓은 시험 클러스터를 지우고(`--keep-restore-data`로 보존 가능), 실패하면 원인 확인을 위해 남긴다. 역할·ACL 재현과 앱 기동은 확인하지 않는다. `--basebackup` 없이 실행하면 예전처럼 새 `pg_dump`를 떠서 같은 스냅샷 건수와 비교한다.

외부 보관본으로 확인할 때는 다른 DB 호스트에서 같은 키의 파일을 `aws s3 cp`로 받아 한 디렉터리에 모은 뒤 같은 명령을 실행한다. `offsite.json`이 없어도 된다.

실제 장애 복구에 물리 백업을 쓰려면 새 호스트의 빈 PGDATA에 `base.tar.gz`를, 그 아래 `pg_wal`에 `pg_wal.tar.gz`를 풀고 `pg_verifybackup`을 통과시킨 뒤, `/etc/postgresql`의 `exchange.conf`·`node.conf`·인증서를 그 호스트에 맞게 준비하고 시작한다. 이 경로는 학교에서 실행한 적이 없다. 수동 승격·앱 전환 순서는 [DB 수동 페일오버](../../../../docs/reference/operations/DB_수동_페일오버.md)를 따른다.

## 실패했을 때

먼저 `journalctl -u failstop-db-backup.service`의 JSON에서 `stage`와 `error`를 본다.

- `config`: 설정 파일 소유권·권한, 알 수 없는 키, 버킷과 접두어 중 하나만 적힌 경우, 백업 디렉터리가 PGDATA 안인 경우다. 설정을 고치고 수동 실행으로 확인한다.
- `database`, `role`, `counts`: 로컬 소켓 접속 실패다. `systemctl status postgresql`과 peer 규칙을 본다. DB가 멈춘 호스트에서는 역할을 알 수 없으므로 실패로 기록된다.
- `disk`: 새 백업을 만들지 않았다. 하한을 낮춰 억지로 통과시키지 않는다. `du -sh /var/lib/pgsql/*`로 원인을 찾고, 오래된 백업을 손으로 지워야 한다면 가장 최근 완료 백업의 격리 복원이 통과한 뒤에 한다.
- `basebackup`: WAL sender 부족(`max_wal_senders`), 체크포인트 대기 중 시간 초과, 테이블스페이스 추가가 원인일 수 있다. `failed-<ID>`에 남은 산출물을 본다.
- `verify`: 새 백업은 완료로 표시되지 않았고 이전 백업은 그대로다. `failed-<ID>`와 디스크 오류(`dmesg`, `journalctl -k`)를 확인한다.
- `logical`: DDL과 겹친 잠금 대기 초과가 흔하다. 다음 실행에서 다시 확인하고 반복되면 원인 DDL을 찾는다. Standby에서 백업하도록 바꾼 경우에는 복제 충돌로 덤프가 취소될 수 있다.
- `OFFSITE_FAILED`: 로컬 백업은 완료됐다. `offsite.json`의 `error`로 권한 거부(AccessDenied), 원격 대조 불일치, 크기 상한을 구분한다.
- `signal`이나 오류 문구의 `SIGTERM`: 서비스가 `TimeoutStartSec=3h`를 넘겼거나 `systemctl stop`·종료 요청을 받았다. 외부 명령마다 1시간 상한이 따로 걸려 합이 3시간을 넘을 수 있어서다. `stage`에 그때 돌던 단계가 남고, 실행 실패 지표를 쓰고 끝난다. 검증까지 끝난 백업은 완료로 남지만 외부 보관과 오래된 백업 정리는 하지 않는다. 반복되면 그 단계가 느린 원인(체크포인트 대기, 디스크 IO, 업로드 대역폭)을 먼저 찾는다. `pg_basebackup`이 체크포인트를 기다리던 중에 끊겼다면 서버 쪽 백업 세션은 그 체크포인트가 끝날 때까지(`spread`면 최대 약 4분 30초) WAL sender 한 개를 쥐고 있다가 "could not send data to client: Broken pipe"를 남기고 끝난다. 바로 다시 실행하려면 `pg_stat_activity`에서 `backend_type='walsender'`이고 `application_name='failstop_backup'`인 세션이 사라진 것을 먼저 확인한다.
- 지표가 갱신되지 않은 채 멈춤: 메모리 상한(`MemoryMax=512M`) 초과나 SIGKILL로 끝나면 스크립트가 지표를 쓸 기회가 없다. 직전 실행의 성공 지표가 남는다. node_exporter가 이 유닛의 상태를 수집하면 `DatabaseBackupServiceFailed`가 1분 뒤 알리고, 수집하지 않으면 26시간 뒤 `DatabaseBackupStale`만 울린다. `systemctl status`의 `Result`와 `journalctl -k`의 OOM 기록을 본다. 남은 `.staging-*`는 다음 실행이 `failed-*`로 옮긴다.
- `BUSY`: 다른 실행이 돌고 있다. 잠금 파일을 지우지 않는다.

## 복제 공백 선택지 (사용자 결정 D6, 구현하지 않음)

2026-09-20 재편입 뒤 복제는 슬롯 없이 붙어 있고 `wal_keep_size=0`, `archive_mode=off`다([prepare-db-recovery.py](../prepare-db-recovery.py)의 `primary_slot_name = ''`, [exchange.conf](../exchange.conf)). Primary는 체크포인트가 지나면 Standby가 아직 받지 않은 WAL도 재활용할 수 있으므로, Standby가 오래 끊기면 따라잡지 못하고 베이스백업으로 다시 만들어야 한다. 이 도구의 예약 백업은 이 공백을 메우지 않는다. 백업은 하루 한 번의 복원 지점이고, 그 사이의 시점으로 되돌리는 수단(PITR)은 아래 C뿐이다. 어느 쪽도 이번 변경에 넣지 않았다.

**A. 물리 복제 슬롯을 되살린다.** 처음 구성은 `standby_1` 슬롯을 썼다. Primary가 Standby에 필요한 WAL을 지우지 않고 기다리며, 기존 `max_slot_wal_keep_size = '4GB'`가 그 상한이다. 상한을 넘으면 슬롯이 무효화되고 베이스백업이 필요하다. Standby가 멈춘 동안 Primary의 `pg_wal`이 최대 4GB 가까이 늘 수 있으므로 슬롯 지연 경보가 함께 필요하고, 수동 승격 때마다 새 Primary에 슬롯을 다시 만들어야 한다.

```sql
-- Primary
SELECT pg_create_physical_replication_slot('school_standby_a');
-- Standby
ALTER SYSTEM SET primary_slot_name = 'school_standby_a';
SELECT pg_reload_conf();
```

**B. `wal_keep_size`를 둔다.** 상태 없이 두 노드에 같은 설정으로 적용되고 승격 뒤에도 그대로다. 대신 항상 그만큼 디스크를 쓰고, 버티는 시간은 크기를 WAL 생성 속도로 나눈 값뿐이다. 로컬 상시 부하 측정값 0.3637 MiB/s를 쓰면 1GB는 약 47분이다. 학교 WAL 속도는 재지 않았다. ADR 0025가 되감기 목적의 상향을 기각했으므로 이 선택은 그 결정을 다시 여는 일이다.

```ini
# /etc/postgresql/exchange.conf, 두 DB, reload로 반영
wal_keep_size = '1GB'
```

**C. WAL 보관(`archive_mode`)을 켠다.** 베이스백업과 함께 쓰면 특정 시점 복원이 가능해지고, Standby가 보관된 WAL로 따라잡을 수도 있다. 운영자 실수를 되돌리는 유일한 선택지다. 그러나 보관이 실패하면 `pg_wal`이 `max_slot_wal_keep_size`와 무관하게 계속 늘어 디스크를 채우고, 그 끝은 거래 중단이다. S3로 보내면 16MB 세그먼트마다 CLI 호출이 생기고, 승격한 노드에서도 보관이 이어지게 두 노드를 같이 설정해야 한다. `archive_mode`는 재시작이 필요하고 ADR 0025가 기각한 선택지다.

```ini
archive_mode = on
archive_command = 'test ! -f /var/lib/pgsql/wal-archive/%f && cp %p /var/lib/pgsql/wal-archive/%f'
```

**D. 지금대로 둔다.** 긴 단절 뒤에는 ADR 0025의 전량 베이스백업으로 재구성하고, 논리 오류는 하루 단위 백업으로만 되돌린다. 설정 변경이 없고, 공백 동안 Primary를 또 잃으면 복제본이 없다.

## 확인한 범위와 한계

로컬 Docker의 일회용 컨테이너(PostgreSQL 17.10 Alpine, Python 3.12, 외부 네트워크 없음)에서 실제 `pg_basebackup`, `pg_verifybackup`, `pg_dump`, 격리 복원을 실행했다. 쓰기가 계속되는 중의 백업, 보존 개수 정리, 여유 부족 거부, 그룹 쓰기 가능 설정 거부, 동시 실행 잠금, Standby 역할 감지와 Standby에서의 백업·복원, 변조된 백업의 복원 거부, 기존 `pg_dump` 모드 회귀를 확인했다. `spread` 체크포인트를 기다리는 실제 `pg_basebackup` 도중에 Python 프로세스 하나와 프로세스 그룹 전체에 각각 SIGTERM을 보내, 두 경우 모두 `FAIL`(단계 `basebackup`, 종료 코드 1), 자식 명령 종료, `failed-<ID>` 보관, 새 이름·옛 이름의 `last_run_success` 0 기록, 이전 백업 보존, 다음 실행 성공을 확인했다. 옛 이름 지표로 기존 일일 보고 식과 MSP DB 대시보드 식 두 개가 값을 돌려주는지는 학교 수집 라벨을 붙인 합성 시계열로 `promtool test rules`에서 확인했다. S3는 가짜 CLI로만 확인했다. 2026-09-23 관측 연결 뒤에는 `DatabaseBackupServiceFailed`가 기록되지 않은 실패에만 울리고 기록된 실행 실패와 외부 보관 실패에는 울리지 않는 것, bot-1의 textfile 읽기 실패도 잡는 것을 `promtool test rules`로 확인했다. [연결 시험](../../tests/test_observability_wiring.py)은 node_exporter가 읽을 디렉터리와 이 도구의 `TEXTFILE_DIR`·`ReadWritePaths`, 기존 소비자가 조회하는 지표 이름이 이 도구의 출력과 맞는지 고정한다. systemd가 `TimeoutStartSec`으로 보내는 실제 SIGTERM, `SuccessExitStatus=75`, node_exporter가 `textfile.env`로 디렉터리를 받는 동작은 실제 systemd에서 실행하지 않았다. 실제 AWS 업로드, Amazon Linux 2023의 Python 3.9와 systemd 샌드박스, 학교 규모의 소요 시간과 거래 영향은 확인하지 않았다. 스크립트 문법은 Python 3.9 기준으로만 검사했다.

학교에 설치하기 전에는 이 백업을 외부 보관이나 예약 백업 완료로 기록하지 않는다. 설치 뒤에도 `LOCAL_ONLY`는 호스트·볼륨 상실 대비가 아니다.
