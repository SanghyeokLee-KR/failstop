# 엄격 동기 복제의 커밋 대기 확인 (2026-09-25)

DB 자동 전환 설계([Patroni 설계](../../../docs/reference/architecture/DB_자동전환_Patroni_설계.md), [ADR 0036](../../../docs/reference/adr/0036-patroni-strict-sync-db-failover.md))가 기대는 PostgreSQL 동작을 폐기용 컨테이너 두 개로 먼저 확인한 기록이다. 동기 대기 서버가 없을 때 커밋이 어떻게 멈추고 무엇으로 끝나는지, 멈춘 트랜잭션이 잠금을 쥐는지, 앱이 쓸 수 있는 시간 제한이 그 대기를 끊는지, 쓰기 가능 탐지에 어떤 문장을 쓸 수 있는지를 봤다. Patroni·etcd·HAProxy는 띄우지 않았다. 1·2차는 Patroni의 엄격 동기 모드가 대기 서버를 잃었을 때 거는 설정(`synchronous_standby_names = '*'`) 대신 대기 서버 이름 `s1`을 걸고 그 서버를 멈췄다. 커밋이 대기 서버의 확인을 기다린다는 점은 같다.

같은 날 설계 검토 뒤 세 가지를 더 확인했다(설계 14절). 3차는 `'*'`를 직접 걸고 대기 서버를 멈춘 채 멤버가 아닌 복제 클라이언트(`pg_receivewal`, `pg_basebackup`의 WAL 스트림)가 커밋 대기를 푸는지 봤다(검토 R1). 4차는 커밋 대기 중에 postmaster가 크래시 재시작을 하면 기다리던 행이 보이는지 봤다(검토 R4). 경보 식은 로컬 promtool로 읽히는지와 몇 장면의 발화를 봤다(검토 R12).

## 환경

| 항목 | 값 |
|---|---|
| 호스트 | 이 PC의 Docker Desktop. 같은 시각에 다른 작업(마켓별 리더 S5·S6의 컨테이너와 빌드)이 함께 돌았다 |
| 이미지 | 로컬에 있던 `postgres:17-alpine`(PostgreSQL 17.10, musl). 새로 내려받지 않았다 |
| 구성 | compose 프로젝트 `exchange-patroni`, 컨테이너 `patroni-probe-pg1`(primary)·`patroni-probe-pg2`(대기 서버, `application_name=s1`), 전용 망, 호스트 포트 없음 |
| 복제 | pg2가 pg1에서 베이스백업을 받아 스트리밍한다. 초기화가 끝난 뒤 `ALTER SYSTEM SET synchronous_standby_names = 's1'`과 설정 재적재로 동기 복제를 켰다. `synchronous_commit = on` |
| 실행 | [run-probe.ps1](run-probe.ps1)(1차), [run-probe-2.ps1](run-probe-2.ps1)(2차), [run-probe-3.ps1](run-probe-3.ps1)(3차), [run-probe-4.ps1](run-probe-4.ps1)(4차), [alerts/run-promtool.ps1](alerts/run-promtool.ps1)(경보 식). 단계 본문은 [steps.sh](steps.sh), 구성은 [compose.yml](compose.yml)과 [pg_hba.conf](pg_hba.conf). 3·4차는 같은 compose 파일을 쓰고 단계만 더했다 |
| 원시 출력 | [probe-output.txt](probe-output.txt)(03:27:26~03:28:19 UTC), [probe-2-output.txt](probe-2-output.txt)(03:29:29~03:29:58 UTC), [probe-3a-output.txt](probe-3a-output.txt)(3차 첫 주행, 04:34:29~04:39:19 UTC), [probe-3-output.txt](probe-3-output.txt)(3차 재주행, 04:41:04~04:45:10 UTC), [probe-4-output.txt](probe-4-output.txt)(04:47:39~04:48:04 UTC), [alerts/promtool-output.txt](alerts/promtool-output.txt)(04:49:48~04:49:51 UTC) |

대기 서버를 멈추는 방법은 `docker stop patroni-probe-pg2`다. 정상 종료라 복제 연결이 곧바로 끊긴다. 소리 없는 네트워크 분할(연결은 살아 있고 응답만 없는 경우)은 재지 않았다.

## 결과

| 번호 | 장면 | 결과 |
|---|---|---|
| 기준 | 대기 서버가 붙은 동기 커밋 | 0.01초에 끝났다 |
| A | 대기 서버 없음. 세션 `statement_timeout` 1초로 자동 커밋 INSERT | 1초에 끝나지 않았다. 8초 뒤 클라이언트 쪽 `timeout`이 psql을 죽였고(rc 143), 서버 백엔드는 `IPC/SyncRep`에서 계속 기다렸다. 그 행은 다른 세션에 보이지 않았다 |
| B | 같은 조건의 명시 트랜잭션 `COMMIT` | A와 같다. `COMMIT` 문장이 `SyncRep`에서 기다리고 `statement_timeout`이 끊지 않았다 |
| C | 커밋 대기 중인 트랜잭션이 쥔 트랜잭션 어드바이저리 락을 `lock_timeout` 1초로 요청 | 1.01초에 `55P03 canceling statement due to lock timeout`. 커밋 대기 중에도 락을 쥐고 있다 |
| D | 커밋 대기 중인 자동 커밋 UPDATE가 쥔 행을 `lock_timeout` 1초로 UPDATE | 1.00초에 55P03. 행 잠금도 쥐고 있다 |
| E | 트랜잭션 안에서 `SET LOCAL synchronous_commit = local` 뒤 INSERT·COMMIT | 0.01초에 끝났고 곧바로 보였다. 대기 서버가 돌아온 뒤 대기 서버에도 있었다 |
| F | `BEGIN; SELECT txid_current(); COMMIT` | 0.00초에 끝났다. 트랜잭션 번호만 받고 WAL 레코드를 쓰지 않은 트랜잭션은 동기 대기를 타지 않는다. 쓰기 가능 탐지 문장으로 쓸 수 없다 |
| G | 대기 중인 INSERT를 다른 세션이 `pg_cancel_backend` | 클라이언트가 `WARNING 01000 canceling wait for synchronous replication due to user request`와 `INSERT 0 1`을 받고 rc 0으로 끝났다. 곧바로 다른 세션에 보였다. 복제되지 않은 커밋이 성공으로 응답된 것이다 |
| H | 대기 중인 INSERT를 `pg_terminate_backend` | 클라이언트는 `WARNING 57P01 ... terminating connection` 뒤 연결을 잃었다(rc 2). 행은 곧바로 보였다 |
| I | `client_connection_check_interval = 1s`에서 클라이언트가 먼저 죽은 커밋 대기(id 8) | 4초 뒤에도 백엔드가 `SyncRep`에 남았다. 이 설정은 커밋 대기를 끝내지 않는다 |
| I-lock | 같은 설정에서 클라이언트가 먼저 죽은 행 잠금 대기 | 4초 뒤 백엔드가 없었다. 대기 서버가 돌아온 뒤에도 그 UPDATE는 적용되지 않았다(행 1의 최종값이 D의 값) |
| K(2차) | 대조군. 설정 0(기본)에서 클라이언트가 먼저 죽은 행 잠금 대기 | 4초 뒤에도 백엔드가 `Lock/transactionid`로 남았다. 대기 서버가 돌아와 잠금이 풀리자 그 UPDATE가 실행돼 커밋됐다(행 1의 최종값이 K의 값). 클라이언트가 모르는 쓰기가 나중에 커밋된 것이다 |
| J(2차) | `BEGIN; SELECT pg_logical_emit_message(true, 'exchange-write-probe', ''); COMMIT`을 superuser가 아닌 로그인 역할(`probe_app`)로 | 대기 서버가 있으면 0.01초, 없으면 5초 뒤 `timeout`에 죽을 때까지 `SyncRep`에서 기다렸다. 역할의 실행 권한은 기본으로 있었다(`has_function_privilege` 참) |
| 복귀 | `docker start patroni-probe-pg2` | 스트리밍과 `sync`로 돌아온 뒤 남아 있던 대기 백엔드가 모두 끝났다. 클라이언트가 죽은 A·B·I의 행과 C·D의 쓰기가 모두 커밋돼 보였고 대기 서버에도 같았다. 연결을 유지한 C·D 클라이언트는 `COMMIT`과 rc 0을 받았다 |

## 3차: `'*'`와 멤버가 아닌 복제 클라이언트

pg1에 `synchronous_standby_names = '*'`를 걸자 대기 서버 `s1`이 우선순위 1의 `sync`로 잡혔고 커밋은 0.01초였다. 그 뒤 pg2를 멈추고 사례마다 커밋 하나를 기다리게 한 다음 pg1 컨테이너 안에서 `127.0.0.1`로 복제 클라이언트를 붙였다. 줄의 `repl`은 `pg_stat_replication`의 이름·상태·우선순위·`sync_state`·보고된 flush 위치다.

| 사례 | 장면 | 결과(재주행 기준) |
|---|---|---|
| P | `'*'`, 슬롯 없는 `pg_receivewal --synchronous`를 12초 | 붙은 지 4초 뒤 `pg_receivewal\|streaming\|1\|sync`였고, 기다리던 id 40이 풀려 클라이언트가 `INSERT 0 1`(rc 0)을 받고 보였다. 새 커밋 id 41은 0.01초였다. 연결이 끝난 뒤의 id 42는 다시 `SyncRep`에서 기다렸다 |
| M | `'*'`, `pg_basebackup -X stream -c fast --max-rate=32k`(기본 임시 슬롯 `pg_basebackup_338`) | 백업 스트림이 `pg_basebackup\|streaming\|1\|sync`, 보고 flush `0/5000000`이었다. 백업 시작점이 `0/5000028`이라 시작의 WAL 세그먼트 전환이 그 앞 세그먼트를 닫았고, 백업 전부터 기다리던 id 42와 50이 시작 3초 뒤의 첫 관찰에서 이미 풀려 있었다. 백업 중에 들어온 id 52는 수동 세그먼트 전환(`pg_switch_wal`)까지 16초 동안 기다렸고, 전환 뒤 보고 flush가 `0/6000000`으로 오르며 풀렸다(전환 6.4초 뒤 관찰에서는 대기, 9.7초 뒤 관찰에서는 보임) |
| N | `'*'`, 같은 백업에 `--no-slot` | 백업 스트림이 `pg_basebackup\|streaming\|0\|async\|-`(flush 보고 없음)였다. 백업 전의 id 60과 백업 중의 id 62가 백업 시작의 세그먼트 전환, 수동 전환, 그 뒤 약 17초(마지막 관찰 04:42:51)까지 풀리지 않았다 |
| O | 설정을 `s1`로 되돌린 뒤 `pg_receivewal --synchronous`를 10초 | `pg_receivewal\|streaming\|0\|async`였고 id 70이 풀리지 않았다 |

첫 주행([probe-3a-output.txt](probe-3a-output.txt))에서는 P와 M의 결과가 같았지만, M의 `pg_basebackup`이 `timeout` 뒤에도 남아 N과 O를 오염시켰다. `timeout`이 본 프로세스만 끝냈고, 백업의 WAL 수신 자식이 두 복제 연결의 소켓을 물려받아 살아 있었다. 그래서 N에서 M의 스트림(`sync`)이 id 60을 풀었고, O는 `max_wal_senders`(4)가 차서 연결하지 못했으며, 마지막에 pg2도 다시 붙지 못했다. 재주행은 사례마다 `bbkill`로 부모와 자식을 모두 끝내고 복제 연결이 0이 된 것을 확인한 뒤 다음 사례로 갔다.

재주행에서도 사례 N 끝의 `bbkill` 직후 기다리던 id 60과 62가 보이게 됐다. 원인은 4차에서 확인했다. `bbkill`이 끝낸 프로세스 가운데 busybox `timeout`의 감시 프로세스가 PID 1(postmaster)의 자식이 되어 있었고, 신호로 끝난 그 프로세스를 postmaster가 백엔드 충돌로 보고 크래시 재시작을 했다(4차 Q2). 그래서 N의 판정은 `bbkill` 전(04:42:51까지)의 관찰로 한다. 두 주행 모두 마지막에 pg2가 스트리밍으로 돌아오지 못해 실행기가 시간 초과로 끝났다. 첫 주행은 `max_wal_senders`가 찼기 때문이고, 재주행의 원인은 확인하지 않았다. 확인용 대기 서버는 슬롯이 없는데, 백업 두 번의 체크포인트와 크래시 재시작, 세그먼트 전환 여섯 번 사이에 그 서버가 필요로 한 WAL이 재사용됐을 가능성이 있다. 두 경우 모두 3차의 판정 대상이 아니다.

## 4차: 커밋 대기 중의 크래시 재시작

pg1의 PID 1이 postmaster인 것을 먼저 확인했다(`pid1: postgres -c hba_file=...`). `'*'`에서 pg2를 멈추고 커밋을 기다리게 한 뒤 세 장면을 봤다. 로그 줄은 `docker logs`에서 크래시·복구 관련 줄만 옮겼다.

| 사례 | 장면 | 결과 |
|---|---|---|
| Q1 | 커밋 대기(id 80) 중에 다른 조회 백엔드 하나를 `kill -9`(메모리 부족 종료 흉내) | `server process (PID 187) was terminated by signal 9: Killed`, `terminating any other active server processes`, 자동 복구 뒤 `database system is ready to accept connections`. id 80은 대기 서버 없이 보였고 그 클라이언트는 `WARNING: 57P02 terminating connection because of crash of another server process`와 연결 끊김(rc 2)을 받았다 |
| Q3 | 대조. PID 1의 자식이 된 고아(`sleep 3`, 부모 PID 1)가 정상 종료 | 크래시 재시작 로그가 없었고 id 82는 계속 기다렸다 |
| Q2 | PID 1의 자식이 된 고아(`sleep 300`)를 `pkill -TERM` | `server process (PID 317) was terminated by signal 15: Terminated` 뒤 같은 크래시 재시작이 났고, 기다리던 id 82가 보였다. 클라이언트는 Q1과 같은 57P02와 연결 끊김을 받았다 |

## 경보 식(promtool)

로컬에 있던 `prom/prometheus:v3.13.0`의 promtool 3.13.0을 `--network none`, 이 폴더 읽기 전용 연결로 돌렸다. 설계 첫 판 7-2의 `PatroniFailoverOccurred`(`changes(max(patroni_postgres_timeline)[5m]) > 0`)는 `ranges only allowed for vector selectors`로 거부됐다([alerts/original-failover.yml](alerts/original-failover.yml)). 첫 판의 `EtcdQuorumLost`(`count(up{job="etcd"} == 1) < 2 or max(etcd_server_has_leader) == 0`)는 세 멤버가 모두 `up` 0인 장면에서 발화하지 않았다. '발화 없음'을 기대로 둔 시험이 통과한 것이 그 증거다([alerts/original-quorum.yml](alerts/original-quorum.yml), [alerts/original-quorum.test.yml](alerts/original-quorum.test.yml)). 고친 초안 12개([alerts/patroni-alerts-draft.yml](alerts/patroni-alerts-draft.yml))는 읽혔고 여섯 장면 시험([alerts/patroni-alerts-draft.test.yml](alerts/patroni-alerts-draft.test.yml))을 통과했다. 장면은 etcd 세 멤버 정지(과반 상실, 멤버 정지 셋, Primary 시계열 없음), 한 멤버 정지(과반 유지), 타임라인 증가, 모든 앱 노드가 ACTIVE인데 커밋 대기가 있음, 중단 중의 커밋 대기(중단 경보만 발화), 멤버가 아닌 복제 연결이다. 수집기 확장 질의가 낼 지표 이름(`pg_syncrep_waiters_count`, `pg_nonmember_walsenders_count`)은 가정이다.

## 이 결과가 설계에 주는 것

1. 커밋 대기는 서버 쪽 시간 제한(`statement_timeout`, `lock_timeout`)으로 끝나지 않는다. 대기 서버의 확인, 취소 요청, 종료 요청, postmaster 종료만 끝낸다(A·B·G·H). 앱이 매달리지 않게 하려면 쓰기를 시작하기 전에 막는 문(거래 일시 중단)과 클라이언트 쪽 제한이 필요하다.
2. 취소 요청은 복제되지 않은 커밋을 성공으로 돌려준다(G). 앱과 운영자는 커밋 대기를 취소하지 않는다. 종료 요청은 결과를 알 수 없는 연결 끊김으로 끝나지만 행은 로컬에 커밋된다(H).
3. 커밋 대기 중인 트랜잭션은 보이지 않으면서 어드바이저리 락과 행 잠금을 쥔다(A~D). 같은 키의 저널 확인(V47)과 같은 행의 다음 쓰기는 `lock_timeout`으로 끝난다. 대기 서버가 돌아오면 모두 커밋된다.
4. 클라이언트가 먼저 끊어도 서버의 커밋 대기는 남고 나중에 커밋된다(A·B·I). 결과를 모르는 쓰기로 다뤄야 한다. 잠금 대기는 `client_connection_check_interval`을 켜면 정리되고(I-lock), 끄면 나중에 실행돼 커밋될 수 있다(K).
5. 이 트랜잭션만 `synchronous_commit = local`로 커밋하면 기다리지 않는다(E). 리스 갱신에 쓸 수 있다.
6. 쓰기 가능 탐지는 WAL을 쓰는 트랜잭션이어야 한다. `txid_current()`만으로는 동기 대기를 재지 못하고(F), `pg_logical_emit_message(true, ...)`는 일반 로그인 역할로 동기 대기를 탄다(J). 새 마이그레이션 없이 쓸 수 있다.
7. `'*'`는 이름과 무관하게 flush 위치를 보고하는 모든 복제 연결을 동기 대기 서버로 받는다(P·M). 멤버가 아닌 연결이 붙으면 온전한 사본이 Primary 하나뿐인데 커밋이 확인되고, 베이스백업은 시작하자마자 그때까지 기다리던 커밋을 모두 푼다. `--no-slot` 백업 스트림은 flush를 보고하지 않아 대상이 아니고(N), 설정에 멤버 이름이 적혀 있으면 그 이름의 연결만 대상이다(O). 설계는 베이스백업 `--no-slot`, 멤버가 아닌 복제 클라이언트 금지, 탐지기의 `'*'` 확인, 경보를 둔다.
8. 커밋 대기 중인 행은 크래시 재시작 뒤 복제 없이 보인다(Q1). '대기 중인 행은 보이지 않는다'는 크래시 재시작이 없을 때만 참이다. 설계는 탐지 연결이 끊긴 뒤 다시 확인된 탐지가 나오기 전과 ACTIVE가 아닌 동안 재조회·저널 확인의 확정 응답을 미룬다.
9. Docker에서 postmaster가 PID 1이면 컨테이너 안 고아 프로세스의 비정상 종료만으로도 크래시 재시작이 난다(Q2·Q3). 실험 스택의 DB 컨테이너는 PID 1을 init에 맡기고, 컨테이너 안에서 띄운 도구를 신호로 끝내는 정리 단계를 쓰지 않는다.

## 확인하지 않은 것

pgjdbc의 확장 질의 프로토콜과 HikariCP 풀에서의 같은 동작, Patroni가 `'*'`를 거는 시점과 Patroni의 승격·강등, Patroni가 `basebackup` 옵션의 `no-slot`을 넘기는지, 재초기화 베이스백업 연결의 `application_name`, HAProxy의 세션 절단, 소리 없는 네트워크 분할, 부하 아래의 시간, 다중 호스트, Amazon Linux의 PostgreSQL 17.11은 재지 않았다. 3차는 `'*'`를 `ALTER SYSTEM`으로 직접 걸었고 Patroni가 건 값이 아니다. 3차 M의 풀림은 백업 시작의 세그먼트 전환과 상태 보고 주기(pg_basebackup 기본 10초)에 달려 있어, 커밋 대기가 풀리는 정확한 시점은 WAL 양에 따라 다르다. `idle_in_transaction_session_timeout`은 켜지 않았다. 커밋 대기 백엔드는 상태가 `active`라 그 설정의 대상이 아니라고 보지만(설정 문서 기준) 여기서 확인하지는 않았다. 경보 식은 promtool의 구문과 합성 표본으로만 봤고 실제 수집기 지표 이름과 대조하지 않았다.

## 정리

모든 주행 끝에서 `docker compose -p exchange-patroni down -v --remove-orphans`로 컨테이너와 망을 지웠고, 이름이 `patroni-`인 컨테이너, 프로젝트 라벨이 `exchange-patroni`인 볼륨, 이름이 `exchange-patroni`인 망이 남지 않은 것을 출력으로 확인했다. 경보 식 확인은 `docker run --rm --name patroni-promtool --network none`으로 띄워 끝나면 지워졌고, 남은 `patroni-` 컨테이너가 없음을 출력으로 확인했다. 1·2차는 주행 전후 `docker volume ls` 목록이 같았다(이름 없는 볼륨 셋과 이름 있는 볼륨 넷). 3·4차와 경보 식 확인 뒤(04:50 UTC 무렵)에는 목록이 달랐다. 이름 없는 볼륨 여섯이 새로 있었고 하나(`426b0bd5…`)가 없어졌다. 새 볼륨 여섯의 생성 시각(04:33:26, 04:36:00, 04:39:22, 04:40:36, 04:42:00, 04:46:04 UTC)은 이 확인의 컨테이너 생성 시각(04:34:29, 04:41:04, 04:47:39)과 맞지 않았고 쓰는 컨테이너도 없었다. 같은 시각에 돈 다른 작업(마켓별 리더 S5·S6의 폐기용 확인)의 것으로 보이며, 이 확인의 명령은 그 볼륨들을 만들거나 지우지 않았다. 보존 볼륨 `exchange-chaos_chaos-pgdata`와 `failstop-keep-chaos-pgdata-20260924`는 마운트하지 않았고 목록에 그대로 있었다. compose 프로젝트 `exchange-chaos`·`exchange-pml`과 다른 작업의 컨테이너에는 명령을 보내지 않았다.
