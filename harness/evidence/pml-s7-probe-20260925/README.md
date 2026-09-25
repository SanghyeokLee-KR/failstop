# ADR 0034 S7 동작 확인 기록 (2026-09-25)

[S7 설계](../../../docs/reference/architecture/마켓분할_리더_S7_설계.md) 3절이 인용하는 원자료다. S7(P3 전환과 파티션 실험)의 설계와 계약을 쓰기 전에, 확실하지 않던 PostgreSQL·nginx·compose 동작을 폐기용 컨테이너와 설정 해석으로 확인했다. S7 코드는 아직 없다. 여기의 결과는 관문 판정이 아니고, 폐기용 스택 실험(설계 5절 Q0~Q16)은 모두 NOT RUN이다.

## 안전 조건

- 컨테이너 이름은 `pml-probe-s7*-<난수>`다. PostgreSQL 컨테이너는 `--rm`이고 호스트 포트를 열지 않거나 `127.0.0.1` 무작위 포트만 연다. 설계 검토 대응 확인(`pml-probe-s7rv-*`)은 호스트 포트 없이 CPU 0.5로 묶어 같은 호스트의 S6 관문 실험을 흔들지 않게 했다. nginx 확인은 전용 망(`pml-probe-s7net-<난수>`)을 만들고 끝나면 컨테이너와 망을 지운다.
- 카오스 스택(`chaos-*`), 실험 스택(compose 프로젝트 `exchange-pml`), 보존 볼륨 `exchange-chaos_chaos-pgdata`, 백업 볼륨 `failstop-keep-*`는 건드리지 않았다. 이미지를 굽거나 태그하지 않았고 새 이미지를 받지 않았다(로컬에 있던 `postgres:17-alpine`, `nginx:1.29`, `python:3.12-alpine`, `curlimages/curl:8.11.0`만 썼다).
- compose는 `docker compose config`로 해석만 했다(프로젝트 이름 `pml-probe-s7cfg`, 가짜 값의 임시 env 파일). 비밀 파일(`harness/.env.chaos` 등)은 열지 않았다.
- 같은 호스트에서 S6 관문 실험(다른 작업, 프로젝트 `exchange-pml`)이 돌고 있었다. 시간 표본(P7-6·P7-7)은 그 잡음을 탈 수 있다.

## 환경

Windows 11, Docker Desktop, Git Bash. PostgreSQL 17.10(`postgres:17-alpine`), nginx 1.29.8(`nginx:1.29`), Docker Compose 5.1.4. 이미지 digest는 각 로그 첫 줄에 있다.

## 파일

| 파일 | 내용 |
|---|---|
| `v55-draft.sql` | V55 초안. 파일 바이트(끝 줄바꿈 하나 포함)의 sha256 앞 16자리는 `f750f412d27be052`이고, 대조 규칙(LF, 끝 줄바꿈 제외)의 문자열 해시는 `7a75c805b58f1646`이다. DB 설계 v2.59 §3.10의 SQL 블록과 같다 |
| `c22p-c30-draft.sql` | C22'(DB 설계 §6.4 ⑯)과 C30(⑳)의 첫 초안. P7-5가 이 판으로 돌았다. 검토 대응 뒤 계약의 ⑯은 구간 경계 팔에 `snapshots`를 더하고 열을 `(arm, tbl, subject)`로 바꿔 이 파일과 다르다. 계약 판은 R7이 문서에서 뽑아 돌았다. ⑳은 같다 |
| `s7-pg-probe.sh`, `s7-pg-apply.sh` | P7-0~P7-6 실행기. 저장소의 V0~V54를 파일마다 한 트랜잭션으로 올린 뒤 V55와 C22'·C30을 올린다. `S7_SOURCE=doc`이면 DB 설계 문서의 블록을 뽑아 쓴다 |
| `s7-pg-probe.log` | 계약 문서 블록으로 돈 기록(`source=documents`) |
| `s7-pg-probe-draft.log` | 초안으로 돈 기록. 시각과 이름을 빼면 위 기록과 본문이 같다 |
| `s7-cost-probe.sh`, `s7-cost-apply.sh`, `cost-queries/` | P7-7 비용 표본 실행기와 문장 여섯 |
| `s7-cost-probe-200k.log`, `s7-cost-probe-1m.log`, `s7-cost-probe-1m-b.log`, `s7-cost-probe-1m-c.log` | P7-7 기록 |
| `s7-rowlock-probe.sh`, `s7-rowlock-probe.log` | P7-8. pgrowlocks와 멈춘 클라이언트의 잠금 |
| `s7-oneshot-probe.sh`, `s7-oneshot-probe.log` | P7-9. 한 번만 실패하는 트리거와 사용자 정의 SQLSTATE |
| `compose-s7-config.sh`, `compose-s7-config.log` | C7. 오버레이 순서와 LB 마운트 해석 |
| `docker-compose.p3-draft.yml` | P3 오버레이 초안(구현 때 `harness/docker-compose.p3.yml`) |
| `nginx-p3-relay-draft.conf` | LB 합본 초안(구현 때 `harness/nginx/nginx.partition-relay.conf`, sha256 앞 16자리 `a7476f65bfce31a9`) |
| `nginx-s7-probe.sh`, `nginx-s7-backend.py`, `nginx-s7-client.sh`, `nginx-s7-probe.log` | N7-0~N7-4 |
| `c22p-count-r2.sql` | 검토 대응의 C22' 수 판(`cost-queries/q_6_c22p_arrays.sql`에 스냅샷 항을 더함). 앱 enum과 하네스 SQL의 기준 |
| `segment-old-rows.sql` | 구간 경계 증분 표본 초안. 이력 행마다 네 표의 옛 구간 epoch 행 수 |
| `s7-pg-review-probe.sh`, `s7-pg-review-apply.sh`, `s7-pg-review-probe.log` | R7-1~R7-5(설계 검토 대응 확인) |
| `compose-s7-config-r2.log` | C7 재해석. 초안에 JDBC 주소를 넣고 해석 스크립트가 주소를 함께 적게 고친 뒤 돌았다 |

## 명령

```bash
cd harness/evidence/pml-s7-probe-20260925
bash s7-pg-probe.sh > s7-pg-probe-draft.log 2>&1
S7_SOURCE=doc bash s7-pg-probe.sh > s7-pg-probe.log 2>&1
bash s7-cost-probe.sh 200000 > s7-cost-probe-200k.log 2>&1
bash s7-cost-probe.sh 1000000 > s7-cost-probe-1m.log 2>&1
bash s7-rowlock-probe.sh > s7-rowlock-probe.log 2>&1
bash s7-oneshot-probe.sh > s7-oneshot-probe.log 2>&1
bash compose-s7-config.sh > compose-s7-config.log 2>&1
bash nginx-s7-probe.sh > nginx-s7-probe.log 2>&1
# 설계 검토 대응(2026-09-25)
bash s7-pg-review-probe.sh > s7-pg-review-probe.log 2>&1
bash compose-s7-config.sh > compose-s7-config-r2.log 2>&1
```

`s7-cost-probe-1m-b.log`는 `q_5` 문장을 더한 재측정, `s7-cost-probe-1m-c.log`는 `S7_COST_ONLY=q_6`으로 `q_6`만 잰 기록이다.

## 결과

### PostgreSQL (P7)

- **P7-0 적용.** V0~V54(55개 파일)를 올린 DB에 V55가 오류 없이 올라갔다. 자산 셋의 FEE_INCOME 샤드 1 계정이 잔고 0의 `balances` 행과 함께 생겼고, GENESIS·GATEWAY 샤드 1은 생기지 않았다. 트리거 `trg_accounts_fee_income_shard`는 `owner_type = 'SYSTEM' AND purpose = 'FEE_INCOME' AND shard = 0`인 행에만 걸린다. 함수 권한은 `leader_protocol_blockers`가 소유자와 `harness_role`, `seed_fee_income_shard`(SECURITY DEFINER)와 `set_leader_protocol`이 소유자만이다.
- **P7-1 권한과 트리거.** 앱 로그인이 자산 4와 그 FEE_INCOME 샤드 0을 넣자 샤드 1이 같이 생겼고, 같은 행을 다시 넣으면 `ON CONFLICT`로 넘어갔다. 앱 로그인은 트리거 함수를 자기 임시 표에 걸지 못했다(42501). 조회 로그인은 선행 조건 함수를 부를 수 있고 전환 함수는 부르지 못했다. 앱 로그인은 둘 다 부르지 못했다.
- **P7-2 전이와 선행 조건.** P1에서 1->3은 전이 거부(55000)다. 1->2 뒤 선행 조건을 하나씩 어기고 풀었다. 명령 대역 래치가 없으면 `band_latch_missing`, 정산되지 않은 주문 생성 명령이 있으면 `unsettled_new`(목표 2에도 나온다), FEE_INCOME 샤드 1이 없는 자산이 있으면 `fee_shard_missing`, 시드 트리거를 끄고 넣은 마켓 6에는 `band_leaf_missing`·`market_partition_missing`·`market_counter_missing`, `app_role`에 `leader_lease` UPDATE(표 전체와 열 하나 모두)를 되돌려 주면 `app_role_lease_update`, `command_journal` INSERT를 되돌려 주면 `app_role_journal_insert`가 나왔다. 선행 조건이 있는 동안 2->3은 모든 사유를 한 메시지로 모아 55000으로 끝나고 아무것도 바꾸지 않았다. 모두 푼 뒤 2->3이 커밋됐고 퇴역 epoch는 p0 5, p1 6, `matching-engine` 7, 이력 행은 `3:P3:floor=4`다.
- **P7-3 P3의 획득과 fencing.** P3에서 최고 모드 2의 획득은 55000(이 호출은 P2까지만 안다), `matching-engine` 획득도 55000이다. 최고 모드 3은 p0 epoch 8, p1 epoch 9를 ACQUIRE로 얻었다. p0의 holder·epoch로 p1 마켓(2)의 명령 행을 넣으면 0행, p0 마켓이면 1행이다(시장 도출 fencing). 3->1과 3->3은 전이 거부, 확인 인자 없는 3->2는 되돌리기 거부다.
- **P7-4 잠금 시한.** 다른 세션이 p1 행에 KEY SHARE를 7초 쥔 동안 3->2는 5.005초에 55P03으로 끝났고 모드와 리스 행이 그대로였다.
- **P7-5 C22'·C30.** P2 행 둘 뒤에 P3 두 파티션 행 다섯을 번갈아 넣은 저널(7행)에서 종전 C22의 시간 축 팔은 거짓 위반 2건을 냈고 C22'는 0이다. 주입은 각각 롤백했다. V1(p0를 다른 노드가 새 epoch로 넘겨받은 뒤 옛 p0 epoch로 쓴 행)은 C22' 시간 축 팔 1·마켓 안 팔 1. V2(p1 마켓 행을 p0 epoch로, 명령·이벤트·스냅샷·체결 넷)는 C30 표마다 1. V3(P2 구간 epoch로 P3에서 앞 행이 없는 마켓 3에 쓴 행)은 C22' 구간 경계 팔 1, C30 0, 종전 C22 시간 축 팔 3(거짓 위반 2 포함). V4(P3 퇴역 epoch로 쓴 행)와 V5(획득 이력이 없는 P3 범위 epoch)는 C30 각 1. 3->2 되돌리기 뒤 P2 행을 더해도 C22'·C30은 0이다. 이력은 `1:P1:0 2:P2:0 3:P3:4 4:P2:10`이다.
- **P7-6 비용 한 판.** 같은 컨테이너에서 명령 20만 행을 한 트랜잭션에 넣고(롤백) 종전 C22 131.6ms, C22' 567.9ms, C30 22.5ms였다.
- **P7-7 비용 표본(판정 아님).** 합성 명령 행(P2 구간 절반, P3 구간 절반, 마켓 12개)을 넣은 CPU 1 컨테이너에서 문장마다 세 번 쟀다. 트랜잭션 밖, `EXPLAIN (ANALYZE, BUFFERS OFF, TIMING OFF)`, JIT 끔이다.

  | 문장 | 20만 행 | 100만 행 | 100만 행 재측정 |
  |---|---|---|---|
  | 종전 C22(`q_1`) | 154~159ms, 결과 41,666 | 862~868ms, 결과 208,333 | 784~808ms |
  | C22' 목록 판(`q_2`) | 306~318ms | 1,475~1,514ms | 1,472~1,558ms |
  | C22' 수 판(`q_3`) | 255~299ms | 1,296~1,447ms | 1,398~1,694ms |
  | C30(`q_4`) | 41~45ms | 219~255ms | 204~232ms |
  | C22' 한 번 훑기(`q_5`) | | | 1,525~1,619ms |
  | C22' 배열 수 판(`q_6`) | | | 1,293~1,358ms(`-c` 기록) |

  종전 C22의 큰 결과 수는 합성 P3 행이 두 파티션 epoch로 번갈아 기록돼 생긴 거짓 위반이다. C22'의 결과는 모두 0이다. C30 표본은 명령 행만 있고 이벤트·체결·스냅샷 표가 비어 있다.
- **P7-8 멈춘 클라이언트의 행 잠금.** `postgres:17-alpine`에 pgrowlocks 1.2가 있다. `application_name = 'chaos-api-1'` 세션이 행 1을 UPDATE하고 커밋 전에 머물면 `pgrowlocks('balances')`가 그 행을 `No Key Update`, `idle in transaction`으로 보였다. 클라이언트 psql을 SIGSTOP으로 멈춘(docker pause 흉내) 뒤에도 잠금은 그대로였고, 다른 행 UPDATE는 곧바로 됐으며, 같은 행 UPDATE는 `lock_timeout` 1초에서 55P03이었다. `idle_in_transaction_session_timeout` 5초가 세션을 끊어(`FATAL: terminating connection due to idle-in-transaction timeout`) idle 시작 약 5.7초(0.5초 표본) 뒤 잠금이 사라졌고, 그 뒤 같은 행 UPDATE가 됐다.
- **P7-9 한 번만 실패하는 주입.** `RAISE ... USING ERRCODE = 'S7F01'`(사용자 정의 SQLSTATE)이 받아들여져 오류가 `S7F01`로 나왔다. `trades` BEFORE INSERT 트리거가 마켓 2 행이고 시퀀스 `nextval`이 1일 때만 실패하게 하자, 첫 마켓 2 행은 실패했고 그 트랜잭션이 롤백된 뒤에도 시퀀스는 `last_value 1, is_called true`로 남아 두 번째 마켓 2 행은 들어갔다. 마켓 1 행은 처음부터 들어갔다.

### compose (C7)

- 기본 파일만이면 chaos-lb가 `nginx/nginx.conf`를 마운트한다.
- 중계 오버레이를 겹치면 `nginx/nginx.relay.conf`, 두 앱은 `relay_mode=broker`, `chaos-broker`가 더해진다.
- 중계 뒤에 P3 초안을 겹치면 `nginx/nginx.partition-relay.conf`, api-1 선호 `matching-p0`, api-2 선호 `matching-p1`이다.
- 순서를 뒤집어 P3 초안 뒤에 중계를 겹치면 선호는 그대로이고 chaos-lb는 `nginx/nginx.relay.conf`로 돌아간다. compose가 같은 컨테이너 경로의 마운트를 뒤 파일 것으로 바꾸기 때문이다.
- 검토 대응 재해석(`compose-s7-config-r2.log`). 첫 초안에는 JDBC 주소가 없었다. 초안에 두 앱의 `SPRING_DATASOURCE_URL`을 넣고 다시 해석하자 중계 뒤에 P3 초안을 겹친 판에서 api-1은 `jdbc:postgresql://chaos-toxiproxy:15432/exchange?ApplicationName=chaos-api-1`, api-2는 `...:25432/exchange?ApplicationName=chaos-api-2`였다. 기본 파일만이거나 중계만 겹친 판은 종전 주소(`?ApplicationName` 없음)였다. 나머지 필드는 첫 해석과 같다.
- 중계 오버레이만 겹치고 `CHAOS_LB_CONF=./nginx/nginx.partition.conf`를 주면 chaos-lb는 여전히 `nginx/nginx.relay.conf`다. 변수는 기본 파일의 마운트 원본만 바꾼다.

### nginx (N7)

가짜 노드 A(처음 p0 소유)와 B(p1 소유), 초안을 이름만 바꿔 띄운 nginx 1.29.8, curl 컨테이너 하나로 확인했다. 가짜 노드는 소유하지 않는 파티션의 주문 쓰기에 선검사처럼 `503` + `Retry-After: 1` + `X-Exchange-Route: not-owner`를 내고, WS 거절 모드에서는 업그레이드 전 `503`을 낸다.

- **N7-0.** 이름을 바꾸지 않은 초안 원문의 `nginx -t`가 통과했다.
- **N7-1 평상시.** 힌트를 실은 쓰기(생성, 배치, 단건 취소, 일괄 취소)는 그 파티션 1차로 곧바로 갔다(p0는 A, p1은 B). 힌트를 실은 주문 조회(GET)는 종전 쓰기 경로(A)로 갔다. 호가·티커·마켓 조회는 A·B로 번갈아 갔고 WS 네 번도 A·B로 번갈아 101이었다.
- **N7-2 WS 거절.** A가 WS를 거절하는 동안 WS 넷은 모두 101이었다. 첫 요청은 `ups="A, B" ust="503, 101"`로 넘어갔고 나머지 셋은 B로 곧바로 갔다. 같은 순간 A의 p0 쓰기 둘은 A에서 `200`이었고 호가는 A·B로 계속 나뉘었다.
- **N7-3 한 노드가 두 파티션을 쥔 모양.** B가 소유를 잃고 A가 p0·p1을 쥐자 p1 쓰기 넷 가운데 처음 둘은 `ups="B, A" ust="503, 200"`, 나머지 둘은 A로 곧바로 갔다(B의 두 번째 실패로 `exchange_p1`에서 빠짐). 2.5초 뒤 p1 쓰기는 다시 B를 먼저 거쳐 A에서 `200`이었다. p0 쓰기는 내내 A였다.
- **N7-4 힌트 없는 쓰기.** 힌트 없는 KRW-ETH 생성은 `exchange_write` 1차 A(비소유)의 `503` 뒤 B에서 `200`이었고 A가 `fail_timeout` 동안 빠졌다. 곧이은 힌트 없는 KRW-BTC 생성은 B(비소유)의 `503` 뒤 살아 있는 업스트림이 없어 `502`였다(`no live upstreams`). 같은 순간 힌트를 실은 KRW-BTC 생성은 `exchange_p0`의 A에서 `200`이었다. 2.5초 뒤의 힌트 없는 KRW-BTC 생성도 `502`였다. nginx는 실패 시각을 초 단위로 재므로 `fail_timeout=2s`의 실제 제외 창이 2~3초다.

### 설계 검토 대응 확인 (R7, 2026-09-25 21:56 KST)

[S7 설계](../../../docs/reference/architecture/마켓분할_리더_S7_설계.md) 11절의 검토 대응을 확인했다. `s7-pg-review-probe.sh`가 DB 설계 문서에서 V55 블록과 고친 ⑯(C22' 목록 판)·⑳(C30)을 뽑고, 이 폴더의 `c22p-count-r2.sql`(수 판)과 `segment-old-rows.sql`(구간 경계 증분 표본)을 함께 올렸다. 저장소의 V0~V54를 올린 PostgreSQL 17.10(CPU 0.5)이다. 쓰기 행은 소유자가 흉내 함수로 직접 넣었다. 쓰기 판정을 우회한 결함, 곧 사후 감사가 잡아야 할 것의 흉내다.

- **R7-1 `lease_row_missing`.** 1->2와 명령 대역 래치 뒤 blockers(3)은 빈 결과였다. `leader_lease`의 `matching-p1` 행을 보통 세션에서 지우면 `lease_acquisitions_resource_fkey`로 23503이었다. 소유자 세션이 `SET LOCAL session_replication_role = replica`로 트리거를 끄고 지우자 blockers(3)이 `lease_row_missing | matching-p1` 한 줄만 냈고(대응 행 셋은 남음), 롤백 뒤 리스 행 셋이 그대로였다.
- **R7-2 기준.** P2 행(명령 둘, 이벤트·스냅샷·체결 하나씩)과 2->3 뒤 P3 두 파티션 행(명령 넷, 이벤트 하나, 스냅샷 둘, 체결 하나)에서 C22'의 시간 축·마켓 안·구간 경계(명령)·구간 경계(스냅샷) 팔, 수 판, C30이 모두 0이었고 목록 판과 수 판의 행 수가 같았다. 구간 경계 증분 표본은 2->3 이력 행(`epoch_floor` 4)에서 전환 직후와 P3 행 뒤가 모두 5였다.
- **R7-3 2->3 뒤 P2 epoch 쓰기.** 새 트랜잭션이 P2 epoch 4로 스냅샷을 쓰면 구간 경계(스냅샷) 1, 행이 없던 마켓 3에 명령을 쓰면 구간 경계(명령) 1이었다. 둘 다 표본을 5에서 6으로 늘렸다(각각 롤백).
- **R7-4 3->2를 가로지른 트랜잭션.** 세션 A가 12:56:47.617 UTC에 트랜잭션을 열고, 3->2가 12:56:49.628에 커밋된 뒤, 새 P2 리더가 마켓 1에 명령 하나를 먼저 커밋했다. A는 6초 뒤 옛 P3 epoch로 마켓 3(p1 epoch 9)과 마켓 1(p0 epoch 8)에 직접 쓰고 12:56:53.630에 커밋했다. 두 행의 `created_at`은 3->2 전이었다. 구간 경계 팔은 0, 마켓 안 팔은 마켓 1 행 하나(새 구간 행 뒤 seq), C30은 0이었다. 3->2 이력 행(`epoch_floor` 9)의 표본은 3->2 직후 13에서 A 커밋 뒤 15가 됐다.
- **R7-5 3->2 뒤 P3 epoch의 이벤트·체결·스냅샷.** 새 트랜잭션이 P3 epoch로 이벤트 하나와 체결 하나를 쓰면 C22'(마켓 안 팔은 R7-4의 1 그대로)와 C30은 늘지 않고 표본만 15에서 17이 됐다. 같은 트랜잭션에 스냅샷 하나를 더하면 구간 경계(스냅샷) 1, 표본 18이었다(롤백 뒤 15).

이 결과로 설계가 적은 사후 감사의 범위(구간 경계 팔은 전환 뒤 시작한 트랜잭션의 명령·스냅샷만, 이벤트·체결과 전환을 가로지른 트랜잭션은 못 셈)와 구간 경계 증분의 필요를 확인했다. 판정이 아니고, 앱 코드와 실제 쓰기 판정을 거친 확인은 코드 단계의 `CutoverStaleWriteTest`·`HarnessSqlVerdictTest`가 한다.

## 한계

- 시간 표본은 CPU 1 컨테이너에서 쟀고, 같은 호스트에서 다른 폐기용 스택이 돌았다. C22' 비용 판정은 하네스 검증설계 §4-1의 절차(권위 run과 같은 읽기 전용 반복 읽기 트랜잭션 안의 `EXPLAIN (ANALYZE, BUFFERS)`, 100만 행 이상)를 따르지 않았으므로 NOT RUN이다.
- 합성 명령 행은 채번 함수를 거치지 않고 소유자가 직접 넣었다. 카운터와 어긋나지만 비용 표본과 술어 확인에는 상관없다.
- nginx 확인은 가짜 노드로 했다. 실제 앱의 선검사·WS 게이트와 같은 응답을 흉내 냈을 뿐이고 앱의 동작 자체는 확인하지 않았다.
- P7-8은 `docker pause` 대신 같은 컨테이너 안 psql에 SIGSTOP을 보냈다. 서버 쪽 시한은 클라이언트가 멈춘 방식과 무관하지만, 앱의 연결 풀이 멈춘 순간 어느 상태였는지는 실험 스택에서만 본다.
- compose는 해석만 했다. 오버레이를 실제로 띄운 것이 아니다.
