# Patroni 실험 스택

PostgreSQL 두 노드를 Patroni 엄격 동기 복제로 묶고, 거래 앱 두 대가 노드별 HAProxy 로 현재 Primary 에 붙는 로컬 실험 스택이다. 설계는 [DB 자동 전환(Patroni) 설계](../../docs/reference/architecture/DB_자동전환_Patroni_설계.md) 3-7절, 결정은 [ADR 0036](../../docs/reference/adr/0036-patroni-strict-sync-db-failover.md)이다. 동기 Standby 가 없으면 DB 가 커밋을 확인하지 않고, 앱의 쓰기 가능 탐지가 그것을 알아 쓰기를 `423 trading_suspended`로 거절하며 조회는 계속 받는다(API 설계 §2-5-d). AWS 에는 적용하지 않았다.

## 구성

| 컨테이너 | 역할 | 호스트 포트(루프백) |
|---|---|---|
| `patroni-etcd-1`·`-2`·`-3` | etcd 세 표(db-1, db-2, bot-1 역할). DB 망에만 붙는다 | 없음 |
| `patroni-db-1`·`-2` | Patroni + PostgreSQL 17. PID 1 은 tini(`init: true`) | Patroni REST 59008, 59009 |
| `patroni-haproxy-1`·`-2` | api-1·api-2 의 사이드카. `/primary` 가 200 인 노드로만 보낸다 | 통계·지표 59404, 59405 |
| `patroni-haproxy-b` | 봇·도구·ACK 기록기의 사이드카 | 쓰기 리스너 59432 |
| `patroni-toxiproxy` | api-N 과 그 HAProxy 사이. 평소에는 독성 없이 통과(E-P7 주입점) | 없음 |
| `patroni-api-1`·`-2` | 거래 앱(엔진 켬, `patroni` 프로파일) | 59181, 59182 |
| `patroni-lb` | nginx. 카오스 스택 LB 와 같은 배치 | 59180 |
| `patroni-bots` | 봇(엔진 끔, 쓰기 가능 탐지 켬) | 없음 |
| `patroni-toolbox` | `docker exec` 로 쓰는 psql. `patroni-haproxy-b` 로 현재 Primary 에 붙는다 | 없음 |
| ep 프로파일: `patroni-ackrec` | DB 수준 ACK 기록기. 실행기가 띄우고 멈춘다 | 없음 |
| obs 프로파일: `patroni-prometheus`, `patroni-pg-exporter-1`·`-2` | 관측 | Prometheus 59190 |

망은 둘이다. DB 망(`patroni-db`, 10.159.0.0/24)에는 etcd 와 DB 가, 앱 망(`patroni-app`, 10.159.1.0/24)에는 나머지와 DB 가 붙는다. Patroni 의 복제·REST 연결 주소와 etcd 주소는 DB 망 별칭(`db-N-repl`)이고 HAProxy 와 수집기는 앱 망 별칭(`db-N-app`)으로 붙는다. 그래서 DB 한 대를 DB 망에서만 떼면 복제·etcd 에서는 격리되고 앱에서는 닿는 비대칭 분할(E-P3b)이 된다. `pg_hba` 는 복제를 DB 망에서만 받는다.

## 판

| 구성 요소 | 판 | 비고 |
|---|---|---|
| PostgreSQL | 17.11 (`postgres:17.11-bookworm@sha256:639ab7ce…`) | glibc. 학교 DB(Amazon Linux)와 정렬 규칙이 가깝다 |
| Patroni | 4.1.5 (`patroni[etcd3,psycopg3]`, 가상 환경 `/opt/patroni`) | psycopg 3.3.6, python-etcd 0.4.5 |
| etcd | v3.5.34 (`quay.io/coreos/etcd@sha256:2c029095…`) | |
| HAProxy | 3.0.27 (`haproxy:3.0.27-alpine@sha256:30860bc9…`) | 내장 Prometheus 내보내기 |
| 앱 | `exchange-chaos-app:patroni` | 이 작업 트리의 bootJar. 다른 작업의 태그와 겹치지 않는다 |

## 띄우기와 정리

```powershell
Copy-Item harness/patroni/.env.patroni.example harness/patroni/.env.patroni   # 실험 전용 값. 실제 비밀을 넣지 않는다
pwsh -NoProfile -File harness/patroni/up.ps1          # bootJar, 두 이미지, compose up --wait
pwsh -NoProfile -File harness/patroni/up.ps1 -Obs     # 관측 포함
pwsh -NoProfile -File harness/patroni/down.ps1        # down -v --remove-orphans 와 남은 자원 0 확인
```

회차마다 스택을 새로 세운다. 앱의 강등 계수와 차단기(10분 3회), 탐지기 상태, 지표가 0 에서 시작해야 한다(설계 8-1). 다른 작업(마켓별 리더 S5·S6)의 compose 프로젝트, `chaos-*` 컨테이너, `exchange-chaos-app` 의 다른 태그, 보존 볼륨(`exchange-chaos_chaos-pgdata`, `failstop-keep-*`)에는 명령을 보내지 않는다.

상태 확인은 다음과 같다.

```powershell
docker exec patroni-db-1 patronictl -c /patroni/patroni.yml list
curl.exe -s http://127.0.0.1:59180/api/v1/status
docker exec patroni-toolbox psql -Atc "select resource, holder, epoch from leader_lease"
```

## 설정 메모

- `wal_keep_size` 는 16MB 다. Patroni 4.1.5 는 16MB 아래 값을 검증에서 거르고 128MB 로 되돌린다. 멤버 슬롯(`use_slots`)이 Standby 에 필요한 WAL 을 붙잡는다. 설계 3-2 와 ADR 0036 결정 4 도 이 값으로 적었다.
- 앱 계정 `exchange` 는 카오스 스택처럼 superuser 다. 마이그레이션 V0 이 역할을 만든다. 운영 배치의 런타임 역할(`deploy/provision-runtime-roles.sql`)은 쓰지 않았다.
- Patroni 자신의 로컬 연결은 유닉스 소켓(`use_unix_socket`, `use_unix_socket_repl`)이다. TCP 줄은 망 단위로만 연다.
- 경보 규칙은 `harness/prometheus/rules` 가 아니라 이 폴더의 [prometheus/rules](prometheus/rules/patroni-alerts.yml)에 둔다. 거기 두면 Patroni 가 없는 카오스 스택 Prometheus 가 읽어 `PatroniPrimaryAbsent`·`EtcdQuorumLost` 가 늘 발화한다. 수집기 확장 질의도 같은 이유로 [pg-queries.patroni.yaml](prometheus/pg-queries.patroni.yaml)에 따로 둔다(설계 7-3).
- `pg_nonmember_walsenders` 는 멤버가 아니면서 flush 위치를 보고하는 연결만 센다. `--no-slot` 베이스백업 스트림은 flush 를 보고하지 않아(사전 확인 N) 재초기화 때 `NonMemberWalSender` 가 울리지 않는다.

Patroni 4.1.5 는 `basebackup` 목록의 `no-slot` 을 `pg_basebackup -X stream ... --no-slot` 으로 넘긴다(`patroni/postgresql/bootstrap.py` 의 `basebackup`·`process_user_options`를 읽어 확인했다). 실행 중 명령줄은 E-P6(b)에서 확인한다.

## 경보 식 확인

```powershell
docker run --rm --name patroni-promtool --network none -v "${PWD}/harness/patroni/prometheus:/w:ro" -w /w/tests --entrypoint /bin/promtool prom/prometheus:v3.13.0 check rules /w/rules/patroni-alerts.yml
docker run --rm --name patroni-promtool --network none -v "${PWD}/harness/patroni/prometheus:/w:ro" -w /w/tests --entrypoint /bin/promtool prom/prometheus:v3.13.0 test rules patroni-alerts.test.yml
```

## 실험 실행(E-P)

실행기는 `harness/scripts/ep-*.ps1` 이고 공용 흐름은 [ep-lib.ps1](../scripts/ep-lib.ps1)에 있다. 한 회차는 스택을 새로 세우고(`down.ps1` 뒤 `up.ps1 -Obs`), 주입 전 40초 창과 사전 권위 run, 시계 가드, 주입, 유지, 복귀, 재개 관찰, 재개 뒤 40초 창, 봇 정지와 드레인, 사후 권위 run, 판정 순서로 돈다. 끝나면 스택을 지운다(`-KeepStack` 이면 남긴다). 코드를 바꾼 뒤 첫 회차는 `-Build` 로 bootJar 와 이미지를 새로 만든다.

```powershell
pwsh -NoProfile -File harness/scripts/ep-p2a-standby-stop.ps1 -Build   # E-P2a Standby 정지
pwsh -NoProfile -File harness/scripts/ep-p1-primary-kill.ps1           # E-P1 Primary 강제 종료
pwsh -NoProfile -File harness/scripts/ep-p4-etcd.ps1 -Variant b        # E-P4b etcd 두 멤버 정지
```

| 실행기 | 실험 |
|---|---|
| `ep-p1-primary-kill.ps1` | E-P1. 재합류 뒤 두 노드의 주요 표 건수·합 비교(E-P6a 기록)를 함께 한다 |
| `ep-p1b-primary-restart.ps1` | E-P1b |
| `ep-p2a-standby-stop.ps1`, `ep-p2b-standby-pause.ps1` | E-P2a, E-P2b. 출금 요청기와 풀·수용 in-flight 표본을 더한다 |
| `ep-p3a-primary-isolate.ps1`, `ep-p3b-asymmetric-isolate.ps1` | E-P3a, E-P3b |
| `ep-p4-etcd.ps1 -Variant a\|b\|c` | E-P4a~c(c 는 기록) |
| `ep-p5-load-failover.ps1` | E-P5(봇 40 OPS, k6, 출금 요청기) |
| `ep-p6b-reinit.ps1` | E-P6(b). E-P1 주입 뒤 옛 Primary 의 컨테이너와 이 프로젝트의 데이터 볼륨을 지우고 다시 만든다. 판정 도구에 E-P6(b) 축이 없어 회차 판정은 E-P1 축으로 따로 내고 재초기화 축은 [ep6b_axes.py](../evidence/patroni-20260925-1015/ep6b_axes.py)로 계산한다 |
| `ep-p7-slow-path.ps1` | E-P7(toxiproxy 로 api-1 경로 지연) |

E-P2c(멤버가 아닌 복제 연결), E-P8a·b(DB 호스트 영구 손실과 비상 비동기 전환), E-P9(되돌리기 연습)는 실행기가 아직 없다.

회차가 쓰는 도구는 넷이다.

- DB 수준 ACK 기록기 [ack_recorder.py](../scale/ack_recorder.py)(컨테이너 `patroni-ackrec`, `ep` 프로파일). `infra_probe.ack_probe` 에 스레드 넷이 자동 커밋으로 한 행씩 넣고 시도마다 acked·failed·unknown 을 적는다. 취소 요청과 statement_timeout 을 쓰지 않고 소켓 제한 90초와 keepalive(5·2·3)로만 끊는다(설계 8-1).
- 관측기 [patroni_observe.py](../scale/patroni_observe.py). Patroni `/cluster` 0.5초, 노드별 상태 API 1초, 조회 대표 경로 1초, 인증 없는 주문 POST 1초(ACTIVE 401, 중단 423), HAProxy 서버 상태 1초와 선택으로 풀 표본·출금 요청기를 돈다.
- 판정 [patroni_axes.py](../scale/patroni_axes.py). 시각(T_suspend, T_promote, T_sync, T_resume 등)을 표본에서 뽑고 설계 8-3 의 축마다 PASS·FAIL·판정 불가를 낸다. 회차 무효 조건(etcd 리더 교체, fsync·피어 왕복 p99, 호스트 CPU, 실험 밖 부하 도구)에 걸리면 시간 축만 판정에서 뺀다.
- 하네스 공용 스크립트. `lib.ps1` 과 `load-acks.ps1` 은 `CHAOS_PSQL_CONTAINER` 등 환경 변수로 대상을 바꾼다. 실행기는 psql 을 `patroni-toolbox` 에서 돌려 현재 Primary 에 붙는다. 값을 주지 않으면 카오스 스택 기본값 그대로다([lib-target.Tests.ps1](../scripts/tests/lib-target.Tests.ps1)).

판정 JSON, 시각, 사실, 관측 표본, 로그 해시는 `harness/evidence/patroni-EP-<YYYYMMDD-HHMM>/` 에 남는다. 기록기 원본과 컨테이너 로그 원문은 저장소 밖(`%TEMP%\exchange-patroni-ep\<run>`)에 두고 해시만 남긴다. 부하 계정 키도 그 폴더에만 둔다.

대시보드는 [db-ha-patroni.json](../grafana/dashboards/db-ha-patroni.json)이다. 이 스택에는 Grafana 를 두지 않았다. Prometheus(59190)를 데이터 소스로 붙여 쓴다.

## 기록

- 기동 확인(E-P 아님): [patroni-stack-smoke-20260925](../evidence/patroni-stack-smoke-20260925/README.md). Standby 정지와 Primary 강제 종료에서 탐지기 전이, 쓰기 게이트 423, 리스 획득 시점, pg_rewind 재합류를 한 번씩 봤다.
- 설계가 기대는 PostgreSQL 동작의 사전 확인: [patroni-sync-probe-20260925](../evidence/patroni-sync-probe-20260925/README.md).
- DB 수준 ACK 기록기 동작 확인(E-P 아님): [patroni-ackrec-check-20260925](../evidence/patroni-ackrec-check-20260925/README.md).
- 실행기 첫 회차들. 아래 회차는 같은 PC 의 다른 작업(Gradle 빌드)과 겹쳐 시간 축을 판정에서 뺀 INCONCLUSIVE 다. E-P1 첫 회차는 실행 규약을 벗어났고 E-P7 첫 회차의 FAIL 은 실행기 결함에서 났다.
  - E-P2a: [patroni-EP-20260925-0732](../evidence/patroni-EP-20260925-0732/README.md). 시간 축이 아닌 축 모두 PASS. 중단 탐지는 `docker stop` 뒤 6.43초로 설계 상한 5초를 넘었고, Standby 는 그동안 복제를 이어 가 커밋은 6.53초에 멈췄다.
  - E-P1: [patroni-EP-20260925-0738](../evidence/patroni-EP-20260925-0738/README.md)(실행기 psql 이 매달려 유지 시간이 어긋난 회차), [patroni-EP-20260925-0756](../evidence/patroni-EP-20260925-0756/README.md)(시간 축이 아닌 축 모두 PASS, 시간 축 값도 상한 안).
  - E-P7: [patroni-EP-20260925-0819](../evidence/patroni-EP-20260925-0819/README.md)(실행기의 계수 파싱 결함으로 난 FAIL), [patroni-EP-20260925-0825](../evidence/patroni-EP-20260925-0825/README.md)(시간 축이 아닌 축 모두 PASS). 느린 노드는 SUSPENDED 가 아니라 확인 연결 시간 초과로 UNREACHABLE 이 된다.
  - E-P3b: [patroni-EP-20260925-0832](../evidence/patroni-EP-20260925-0832/README.md). 시간 축이 아닌 축 모두 PASS, 두 노드가 같은 시간대에 확인한 커밋 0.
- E-P4b: [patroni-EP-20260925-0829](../evidence/patroni-EP-20260925-0829/README.md). **PASS**(이 실험의 PASS 조건에는 시간 축이 없다). etcd 과반을 잃는 동안 failsafe 로 Primary 를 지키고 쓰기가 멈추지 않았다.
- E-P1~E-P6 한 벌(2026-09-25 10:14~14:30 UTC): [patroni-20260925-1015](../evidence/patroni-20260925-1015/README.md). 무효 조건에 걸리지 않은 회차에서 E-P1, E-P2a, E-P2b, E-P3a, E-P3b, E-P4a, E-P4b, E-P5(두 회차), E-P6(a·b) 모두 PASS 다. E-P1b 는 처음 판정에서 '타임라인 불변' 축이 FAIL 이었다. Patroni 가 리더 키를 쥔 채 재기동한 노드를 읽기 전용으로 띄운 뒤 스스로 승격시켜 타임라인을 올리기 때문이고, 노드 전환은 없었다. 사용자가 2026-09-25 에 타임라인 변경을 허용·기록 항목으로 확정해 판정 도구에서 그 축을 뺐고, 같은 원자료를 다시 판정한 결과는 PASS 7/7 이다. 첫 E-P2a 회차는 멈춘 DB 컨테이너의 호스트 포트(59009, Windows 동적 포트 대역)를 다른 작업의 시험 JVM 이 잡아 복귀가 실패한 무효 회차이고, 그 뒤 실행기의 복귀를 다시 시도하는 `Start-EpContainer` 로 바꿨다.

승격 뒤 확인 커밋 축(설계 8-1 (7))은 '승격 뒤 멤버 walsender 가 붙기 전 확인된 쓰기 0'으로 잰다. 새 Primary 는 동기 대상이 `'*'` 라 되감기를 마친 옛 노드가 붙는 순간부터 커밋을 확인하고, /cluster 의 sync_standby 표시는 Patroni HA 루프만큼 늦다. 두 E-P1 회차에서 T_promote~T_sync 사이의 확인이 692 건, 600 건 있었고 둘째 회차에서는 모두 멤버가 붙은 뒤였다. 설계 문구도 이 기준으로 고쳤다(2026-09-25 코드 검토 뒤). E-P7 판정 파일은 느린 노드의 UNREACHABLE 표본 수(`extra.api1_unreachable`)와 게이트 503 수를 기록으로 싣는다. 위 두 E-P7 회차의 판정 파일은 이 기록을 넣기 전에 만든 것이다.
