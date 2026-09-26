# S4 관문 폐기용 스택 주행 (2026-09-24 20:20~22:14 KST)

[S4 설계](../../../docs/reference/architecture/마켓분할_리더_S4_설계.md) 7절의 실험(X1~X8)을 compose 프로젝트 `exchange-pml`의 폐기용 스택에서 돌린 기록이다. 이미지는 S4 코드(커밋하지 않은 작업 트리)로 한 번 구웠고, X7의 S3 칸만 S3 커밋으로 다시 구운 이미지를 `:pml`로 붙여 돌렸다. DB 모드는 모든 실험에서 P2이고 파티션은 `matching-engine` 하나다. 그래서 이 주행은 S4의 새 코드 경로(파티션 리스, 기간 결속, 팔로워 기동, 파티션 강등과 재구성, 경합 응답)를 P2 한 파티션 계획으로 시험한 것이고, 두 파티션(P3)의 격리는 Testcontainers 관문 시험이 본다.

절차는 [run-s4-gate.ps1](run-s4-gate.ps1)이고, 단계별 시각과 종료 코드는 [commands.log](commands.log), 보존 볼륨 확인은 [volumes.txt](volumes.txt)다. 처리량 칸의 판정 보조는 [judge_x7.py](judge_x7.py)다.

## 결론

| 실험 | 판정 | 요지 |
|---|---|---|
| X1 P2 봇 부하(앱 역할 로그인) | PASS | 판정 축 21개 참. P2 epoch NEW = 예약 행 22,130, 리더 epoch 6 유지, 두 노드 강등 0, 창 밖 접수 경합 0, LB `no live upstreams`·`502` 0줄 |
| X2 E4 리더 kill과 합류 | PASS | RTO 5.436초, epoch 5 -> 6, rpo_records 0. 되살린 옛 리더가 13초 만에 `FOLLOWER`로 합류(승격 문구 0, 리스 행 불변, 강등 0) |
| X3 E7 리더 pause | PASS | RTO 10.019초. 재개된 옛 리더가 재기동 없이 `LEASE_LOST_WHILE_ACCEPTING` 1로 강등하고 377ms 뒤 재구성해 `FOLLOWER`, 차단기 닫힘 |
| X4 E2 DB 절단 | PASS | RTO 6.016초, 옛 리더 재구성 뒤 `FOLLOWER` |
| X4 E5 네트워크 분리 | PASS | RTO 5.627초, 옛 리더 재구성 뒤 `FOLLOWER`, 강등 문구 양성 대조 참 |
| X4 E6 부하 급증 중 kill | PASS | RTO 6.343초, 스파이크 창 권위 run 위반 0 |
| X5 콜드 재기동 | PASS | 먼저 올린 노드가 팔로워로 기동한 뒤 첫 틱에 승격(인계로 재생 생략), 재개 12.314초. 나중 노드는 `FOLLOWER`로 합류 |
| X6a 기본 | PASS | L 30건 `429`(market), 같은 키 재전송 `201`과 NEW 한 행. M 30건 `201`(최대 1,026ms) |
| X6b 줄 머리 막힘, 첫 주행 | FAIL | M의 주문 두 건이 `429`(node, 노드 수용 거절). 첫 판 기준 밖 |
| X6b 재주행(기준 수정) | PASS | L 30건 `429`(market), M `201` 30·`429` market 18·`429` node 2, 5xx 0, 노드 수용 거절 +30 |
| X6c 같은 키와 재생 | PASS | 같은 키 동시 요청은 둘 다 `409 order_in_progress`, 재전송 `200` 6·`409` 4, `503` 0 |
| X6d 저트래픽 장기 잠금 | PASS | 70초 동안 66회 `429`(market), 두 층 강등 0, epoch 불변, serviceable 1 |
| X7 처리량(판정 아님) | 기록, 잡음 규칙 미충족 | 칸 여덟. 접수 201/s 중앙값 S3 115.9, S4 118.2. S3 칸의 범위/중앙값 0.48 |
| X8 E1 PG 지연 | PASS | 스크립트 1차 판정 PASS, epoch 5 불변, 두 층 강등 0, 획득 `lock_timeout` 0 |
| X8 E3 Redis 장애 | PASS(SKIP이 유효 판정) | Redis 토폴로지 없음. 리스 holder 살아 있음, 무결성 위반 0 |

X1~X5와 X8에서 자산·원장 불변식(권위 run과 드레인 뒤 `summary.sql`의 CRITICAL·HIGH)은 모두 PASS였고, 정산 23514, 잠금 계획 이탈, 접수 500, 접수 40P01, `pg_stat_database.deadlocks` 증가가 0이었다. 장애 실험(E2·E4·E5·E6·E7)의 구 epoch 쓰기 증분은 모두 0이고 RTO는 모두 30초 아래다. S3 관문과 같은 판정 술어가 S4 이미지에서도 성립한다.

## 조건

| 항목 | 값 |
|---|---|
| 이미지 | `exchange-chaos-app:pml` = `sha256:92429e71ea0ddb39da440a1d371480b6f66e84782cb4e57fd60f4d95b444e3ea`(2026-09-24 20:20:45 KST, [images.json](images.json)) |
| 원천 | 작업 트리 `C:\dev\exchange-pml`(브랜치 `claude/per-market-leader`, HEAD `91813dd5`)의 커밋하지 않은 S4 변경. 굽는 시점의 추적 파일 diff SHA-256 `62e6d182…`, 변경 경로 138개 |
| jar | `harness/app.jar` SHA-256 `66d72e83…`. 같은 작업 트리의 전체 빌드(19:52~20:08, 시험 전부 통과)가 만든 bootJar이고, 이미지를 구운 뒤 서버 코드는 바꾸지 않았다 |
| 마이그레이션 | V0~V53(`flyway_schema_history` 최대 53, 54행, 실패 0) |
| S3 비교 이미지 | 계획은 S3 관문 재주행 이미지 `sha256:0ff1d3c13083a0aac8fc4c67f939822c33703af61e6e307ad7a5935bdd5bf43e`였다. 이 PC의 Docker는 containerd 이미지 저장소라 S4 이미지를 `:pml`로 구운 순간 이름을 잃은 S3 이미지가 사라졌다(지우는 명령은 쓰지 않았다). 그래서 S3 커밋 `60be79e0`(HEAD `91813dd5`와 서버 코드 같음)을 저장소 밖 폴더에 풀어 다시 빌드한 `exchange-chaos-app:s3ref` = `sha256:6d97e047c9f96e5dac5568f58c51232b255a3b9bb467584145188167a7eaa31b`(jar SHA-256 `35e3e4cc…`)를 X7의 S3 칸에만 `:pml`로 붙였다. S4 이미지에도 `exchange-chaos-app:s4ref`를 붙여 `:pml`을 옮겨도 사라지지 않게 했다 |
| 호스트 | Windows 11, Docker Desktop 단일 호스트, 논리 CPU 16. 앱 노드는 compose 한도 1 vCPU·1 GiB. 주행 동안 사용자 프로그램이 함께 돌 수 있었다(X7 칸마다 호스트 부하 표본을 남겼다) |
| 보존 자원 | `exchange-chaos_chaos-pgdata`(생성 2026-09-13T11:42:01Z), `failstop-keep-chaos-pgdata-20260924`(2026-09-23T15:47:36Z). 단계마다 앞뒤로 생성 시각을 확인했고 바뀌지 않았다. `exchange-chaos-app:local`(`611169b8bf40`)은 그대로다 |

실행기는 셸 변수 둘(`CHAOS_PROJECT`, `COMPOSE_PROJECT_NAME`), `lib.ps1`의 프로젝트, compose 해석값이 모두 `exchange-pml`일 때만 시작한다. 시작할 때 컨테이너는 하나도 없었다([docker-ps-before.txt](docker-ps-before.txt)는 빈 파일). 이미지를 구운 뒤 Gradle 데몬을 멈췄다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/k6_keys.json`)은 경로로만 넘겼고, X1·X6의 런타임 로그인 비밀번호는 실행마다 무작위로 만들어 셸 변수로만 넘겼다. X6의 부하 계정 키는 구동기가 하네스 API로 새로 발급해 메모리에만 뒀다.

## 주행 중에 바꾼 하네스 절차

모두 이 주행을 맡은 AI가 정했고 사용자 확인 대상이다.

- `harness/scale/s4_partition_axes.py`: 주행 전에 LB 로그의 `502` 계수를 고쳤다. nginx 기본 형식에서 `' 502 '`로 세면 응답 바이트 수가 502인 정상 줄도 세므로 상태 필드 앞의 따옴표를 붙여(`'" 502 '`) 센다. 단위 시험에 그 줄을 더했다.
- X6b 판정 기준: 첫 주행(X6)에서 M의 주문 둘이 `429`(`bucket=node`, 노드 수용 거절)로 끝나 `M_201_or_market_429` 축이 거짓이었다. 수용 거절도 처리 전에 끝나는 확정 미접수이고(API 설계 §2-5-a) 인그레스가 리더를 빼지 않으므로, 설계 7-2 X6b를 먼저 고치고 구동기의 축을 `M_201_or_429_market_or_node`로 바꾼 뒤 X6b만 새 스택에서 다시 돌렸다(X6r). 첫 주행의 FAIL은 그대로 남긴다. 구동기는 이때부터 변형마다 노드의 주문 수용 거절 증가를 기록한다.
- `harness/scripts/adr0022-stage5-chaos.ps1`: X4부터 `-PartitionAxes`일 때 LB 컨테이너 로그 원문도 저장소 밖 로그 폴더에 남긴다. X2에서 창 밖 `502` 한 줄의 원인을 로그로 볼 수 없었기 때문이다.
- `run-s4-gate.ps1`: X6 재주행용 `-Phase X6`, X7 칸마다 호스트 부하 표본과 LB 5xx 계수, 노드 수용 거절 계수를 더했다.

## X1 P2 봇 부하 (앱 역할 로그인, PASS)

소유자 로그인 스택을 세워 api-1이 V1~V53을 적용했다. [provision-runtime-roles.sql](../../../deploy/provision-runtime-roles.sql)로 런타임 로그인을 만들고 S2 관문의 [앱 로그인 오버레이](../pml-s2-gate-20260924-0440/override-app-login.yml)로 api·봇을 다시 만들었다. P1 리더(epoch 2)가 선 뒤 [protocol_latch.py](../../scale/protocol_latch.py)로 P2로 전환하고(퇴역 `matching-p0` 3 < `matching-p1` 4 < `matching-engine` 5, 드레인 2.2초, 새 리더 epoch 6) 봇 목표 300 OPS로 10분(11:24:06~11:34:21 UTC) 돌렸다. 원자료는 [X1/result.json](X1/result.json), [X1/samples.json](X1/samples.json), [X1/partition_axes.json](X1/partition_axes.json)이다.

| 판정 축 | 결과 |
|---|---|
| 판정 축 21개(S3형 15, S4 6) | 모두 참. 권위 run PASS, `summary.sql` CRITICAL·HIGH 29행 PASS, 정산 23514 0, 잠금 계획 이탈 0, 접수 500 0, 접수 40P01 0, 교착 증가 0, 드레인 뒤 정산 전 예약 0, 라이터 정지·강등 문구 0, 권한·함수 오류 0줄, 새 epoch 스냅샷 3마켓 |
| P2 epoch NEW = 예약 행 | 22,130 = 22,130(창 안 예약 22,060) |
| 리더 epoch | 표본 모두 6, 끝에도 6 |
| 로그인 | api 연결 `pml_app`만, 봇 `pml_bot`만 |
| 파티션 상태 | api-1 `ACCEPTING`(epoch 6), api-2 `FOLLOWER`. 두 노드 강등 계수 0, 차단기 닫힘 |
| 접수 경합 상한 | 창 밖(주행 전체) 경합 0건, 같은 구간 append 17,510건(상한 17.5건) |
| 파티션 경보 | `PartitionBreakerOpen`·`PartitionRebuildFailing` 발화 표본 0 |
| LB | `no live upstreams` 0줄, `502` 0줄 |
| 획득 `lock_timeout` 계수 | 0(기대값 0) |

기록 값. 봇이 낸 주문은 창 안 15,685건(201, 약 25.5/s)과 422 6건이고 400·409·429·5xx는 0건이다. 목표 300 OPS에 못 미친 것은 S3 X1(약 27.7/s)과 같고 원인은 재지 않았다. DB 연결 표본(15초 간격)의 최댓값은 37이고, 끝 표본은 `pml_app` 24·`pml_bot` 10·소유자 2로 S3 X1 끝 표본(`pml_app` 27·`pml_bot` 10·소유자 2)과 같은 구성이다. 경보는 주행 중 `EdgeProbeDown`·`EdgeProbeSlow`(S3와 같음)가, X6 뒤 드레인 시점에 `OrderP99SloBreach`(X6가 일부러 만든 1~2초 대기)와 `BotFleetDown` 대기(봇 정지)가 떴다. 판정 축이 아니다.

## X6 접수 경합 (a·c·d PASS, b 첫 주행 FAIL 뒤 기준 수정 재주행 PASS)

X1 스택에서 이어 돌렸다([harness/scripts/s4-intake-contention.ps1](../../scripts/s4-intake-contention.ps1), 구동기 [s4_intake_contention.py](../../scale/s4_intake_contention.py)). 잠금은 `docker exec chaos-pg psql` 세션이 부하 계정 L의 KRW AVAILABLE 잔고 행을 `SELECT … FOR UPDATE`로 쥔다. 주문은 KRW-BTC 지정가 매수(가격 4,990만 원, 수량 0.001)이고 시세 아래라 체결되지 않는다. 네 변형 모두 공통 판정(LB의 `no live upstreams`·`502` 0, 리더 강등 0, 정산 재시도 소진 0)이 참이었다. 원자료는 [X6/](X6/)와 [X6r/](X6r/)의 변형 폴더(`contention.json`, `partition_axes.json`)와 `x6_summary.json`이다.

| 변형 | 판정 | 요지 |
|---|---|---|
| a 기본(봇 20 OPS, 30회) | PASS | L 30건 모두 `429`(`bucket=market`), 재전송 전 저널 행 0, 같은 키 재전송 30건 모두 `201`과 NEW 한 행. M 30건 모두 `201`, 지연 최대 1,026ms(중앙 1,017ms). 경합 계수 `55P03|429` +30 |
| b 줄 머리 막힘(10회) 첫 주행 | FAIL | L 30건 모두 `429`(market), 저널 행 0, 5xx 0. M 50건 가운데 `201` 37, `429` market 11, `429` node 2. node 두 건이 첫 판 기준(`201`이나 market `429`) 밖이었다 |
| b 재주행(X6r, 새 스택) | PASS | L 30건 모두 `429`(market), 저널 행 0. M `201` 30(지연 최대 1,851ms), `429` market 18, `429` node 2, 5xx 0. 이 동안 노드 수용 거절 증가 30(봇 주문 포함) |
| c 같은 키와 재생(10회) | PASS | 같은 키 동시 두 요청은 10회 모두 둘 다 `409 order_in_progress`. 잠금이 풀린 뒤 그 키로 `201`과 NEW 한 행. 이미 접수된 R의 재전송은 `200`(재생) 6회, `409 order_in_progress` 4회이고 `503`은 0, 재조회는 모두 `200` |
| d 저트래픽 장기 잠금(70초, 봇 0) | PASS | L의 같은 주문 66회 모두 `429`(market). 두 층 강등 0, 리더 api-1 epoch 6 그대로, `exchange_node_serviceable`·`exchange_partition_serviceable`이 두 노드에서 표본 66개 모두 1, 잠금이 풀린 뒤 `201` |

b에서 본 것은 설계 9절에 적었다. 접수 락을 기다리는 요청은 최대 2초 동안 노드의 주문 수용 자리(카오스 스택 12)를 쥔다. L의 주문 셋이 줄을 서면 그 줄과 봇 주문이 수용 한도를 채워, 마켓과 무관한 새 주문이 `429`(`bucket=node`)로 즉시 거절된다. 재주행에서 10회 동안 노드 수용 거절이 30건 늘었고 그 가운데 M의 것은 2건이다. 나머지는 봇 주문이고 어느 마켓인지는 계수기로 가를 수 없다. 확정 미접수라 자금과 멱등에는 영향이 없고 인그레스도 리더를 빼지 않았다(LB 0줄). 사용자 확인 6(`accept-lock-wait` 2000ms, `accept-lock-timeout` 1000ms)의 판단 근거다. 재주행도 드레인 뒤 권위 run과 `summary.sql` CRITICAL·HIGH가 PASS였다([X6r/result.json](X6r/result.json)).

## X2~X5 장애 실험 (P2, prod-timeouts)

실행기의 Chaos 단계가 실험마다 `-p exchange-pml` 스택을 지운 뒤 `harness/scripts/adr0022-stage5-chaos.ps1 -Image exchange-chaos-app:pml -Experiments <E> -Protocol 2 -PartitionAxes`를 불렀다. 그 스크립트가 스택을 세우고, 실험 전에 P2로 전환하고(epoch 1 -> 퇴역 2·3·4 -> P2 리더 5), 실험 전후로 [s4_partition_axes.py](../../scale/s4_partition_axes.py) 스냅샷을 뜬다. 끝나면 [s3_common_axes.py](../../scale/s3_common_axes.py)가 봇을 멈추고 드레인한 뒤 S3 공통 판정을 모으고, 이어서 파티션 축을 판정한다. E4는 판정 뒤 합류 단계를, E5·E7은 옛 리더 강등 문구의 양성 대조(`--expect-demotion`)를, COLD는 `-S4Boot` 판정을 더한다. 폴더마다 `summary.json`(실험·분리 판정), `<E>/verdict.json`, `<E>/s3_common.json`, `<E>/partition_axes.json`, E4는 `<E>/rejoin.json`이 있다. 컨테이너 로그 원문은 저장소 밖 `%TEMP%\pml-s4-logs`에 두고 해시만 `container_logs.json`에 남겼다.

| 실험 | RTO(초) | epoch | rpo_records | C22 두 팔 | 예약 행 | 승격 뒤 정산된 예약 명령 | 옛 리더 끝 상태 | 공통 판정 | 파티션 축 | 분리 판정 |
|---|---:|---|---:|---|---:|---:|---|---|---|---|
| X2 E4 | 5.436 | 5 -> 6 | 0 | 0, 0 | 1,094 | 1 | kill 뒤 합류 단계에서 `FOLLOWER`, 강등 0 | PASS | PASS | PASS(합류 PASS) |
| X3 E7 | 10.019 | 5 -> 6 | 0 | 0, 0 | 1,398 | 0 | 재기동 없이 `FOLLOWER`, `LEASE_LOST_WHILE_ACCEPTING` 1, 차단기 닫힘 | PASS | PASS | PASS |
| X4 E2 | 6.016 | 5 -> 6 | 0 | 0, 0 | 1,347 | 0 | 재기동 없이 `FOLLOWER`, `LEASE_LOST_WHILE_ACCEPTING` 1, 차단기 닫힘 | PASS | PASS | PASS |
| X4 E5 | 5.627 | 5 -> 6 | 0 | 0, 0 | 2,418 | 0 | 재기동 없이 `FOLLOWER`, `LEASE_LOST_WHILE_ACCEPTING` 1, 차단기 닫힘 | PASS | PASS | PASS |
| X4 E6 | 6.343 | 5 -> 6 | 0 | 0, 0 | 1,456 | 0 | kill(스크립트가 되살리지 않음) | PASS | PASS | PASS |
| X5 COLD | 12.302(첫 체결), 재개 12.314 | 5 -> 6 | 0 | 0, 0 | 1,176 | 0 | 먼저 올린 api-1이 리더, 나중 api-2 `FOLLOWER`, 두 노드 강등 0 | PASS | PASS | PASS(`-S4Boot` PASS) |

S3 관문 재주행과 같은 실험의 RTO는 E4 6.157, E7 10.155, E2 10.488, E5 5.942(재주행), E6 6.978초였고 epoch는 모두 5에서 6이었다. 관문의 판정 술어(RTO < 30초, 구 epoch 쓰기 0, 불변식 0)는 S4에서도 모두 성립한다. E2가 S3보다 4.5초 짧은 것은 한 번씩 잰 값이라 차이의 원인을 주장하지 않는다.

모든 실험에서 S3 공통 판정(드레인 뒤 `summary.sql` CRITICAL·HIGH PASS, 정산 23514·잠금 계획 이탈·접수 500·접수 40P01·교착 증가 0, 드레인 뒤 정산 전 예약 0, 리더 노드의 정지·강등 문구 0, 창 밖 `ReservationUnsettledStale` 0)과 파티션 축(창 밖 접수 경합 상한, 창 밖 파티션 경보 0, 실험 끝 리더 노드의 강등 계수 0)이 참이었다. 구 epoch 쓰기 증분은 모든 실험에서 0이다. 획득 `lock_timeout` 계수는 모든 실험에서 0이었다.

X2 E4와 합류. 리더 api-1을 11:47:23.857에 `docker kill`했고 api-2가 11:47:29.103에 epoch 6으로 승격해 11:47:29.293에 첫 체결을 냈다(RTO 5.436초). 판정 뒤 11:47:38에 api-1을 `docker start`로 되살렸다. api-1은 부팅 획득 없이 "팔로워로 기동"을 남기고(승격 문구 0줄) 13.0초 만에 liveness, 팔로워 상태 1, `/actuator/partitions`의 `FOLLOWER`를 모두 냈다. 되살리기 전후 `leader_lease` 세 행의 holder·epoch가 같고 api-1의 강등 계수는 0이다([X2/E4/rejoin.json](X2/E4/rejoin.json)). 이것이 '기동은 늘 팔로워'의 스택 확인이다. 사전 권위 run은 부하 중이라 C13A 1(물질화 전 진행 중 NEW)로 FAIL이었고 판정 비게이팅이다. S3 관문의 E4도 같았다. 승격 재생이 멈추기 전 리더의 예약 명령 1건을 정산했다(`reserved_settled_by_later_epoch` 1). LB 로그는 장애 창 안에 `no live upstreams`·`502`가 각 171줄, 창 밖(정착 30초 뒤 11초)에 각 1줄이었다. 판정 축이 아니고, 이 주행은 LB 로그 원문을 남기지 않아 창 밖 한 줄의 원인은 보지 못했다(X4부터 원문을 남긴다).

X3 E7. 리더 api-1을 11:50:24.403에 멈췄고 api-2가 TTL(6초)이 지난 11:50:30.382에 epoch 6으로 승격했다(RTO 10.019초). 획득이 옛 리더의 KEY SHARE를 기다린 흔적은 없다(획득 `lock_timeout` 계수 0). S3 관문 X3에서는 멈춘 리더의 트랜잭션이 `idle_in_transaction_session_timeout`(10초)으로 끊길 때까지 획득이 기다린 것으로 보였는데, 이번에는 멈춘 시점에 리스 행을 쥔 트랜잭션이 없었던 것으로 보인다(추론). 재개된 api-1은 재기동 없이 11:51:09.062에 갱신 거부로 리더십을 잃고 곧바로 파티션 강등(`LEASE_LOST_WHILE_ACCEPTING`, 차단기 1회·닫힘)했으며, 377ms 뒤 재구성을 마치고 팔로워로 돌아왔다(후보 참). 실험 끝에 api-1은 `FOLLOWER`이고 강등 계수는 `LEASE_LOST_WHILE_ACCEPTING` 1, `WRITER_FATAL` 0으로 합 1이며, 옛 리더 로그의 강등 문구 양성 대조가 참이다. 강등 전 루프의 늦은 실패로 두 번째 강등이 생기지 않았다. 창 안의 접수 경합은 원인·응답별로 모두 0이다. LB 로그는 창 밖(P2 전환 중)에 `502` 1줄, 창 안 0줄이다.

X4 E2·E5·E6. E2(리더의 DB 절단)와 E5(리더 망 분리)에서 옛 리더 api-1은 복귀 뒤 갱신 거부로 리더십을 잃고 파티션 강등(`LEASE_LOST_WHILE_ACCEPTING` 1, 차단기 닫힘)한 뒤 재기동 없이 재구성해 `FOLLOWER`로 돌아왔다. E5는 옛 리더 강등 문구의 양성 대조가 참이다. E5의 격리(11:57:53~) 동안 옛 리더의 갱신은 연결 오류로만 실패했고, 강등은 망이 돌아온 뒤 첫 갱신이 거부된 11:59:01.751에 났으며 재구성은 35ms 만에 끝났다. 그래서 격리 중 재구성 시도가 없었고 `PartitionRebuildFailing`도 뜨지 않았다(창 밖·안 모두 파티션 경보 표본 0). E6(봇 스파이크 40 -> 400 OPS 정점에서 리더 kill)은 스파이크 창 권위 run 31회 스캔에서 위반 0이었다. E6 스크립트는 k6를 쓰지 않아 `market_contention` 임계는 돌지 않았다(설계 7-2 X4의 정정). LB 로그는 장애 창 안에 E2 53줄, E5 71줄, E6 116줄의 `no live upstreams`·`502`가 있었고, 창 밖 줄(E5 `no live upstreams`·`502` 각 1줄, E6 `502` 1줄)은 저장한 LB 로그 원문의 시각으로 보면 모두 실험 전 P2 전환이 두 노드를 다시 띄운 구간(E5 11:56:54, E6 12:00:37 UTC)이다.

X5 COLD. 스크립트가 두 노드를 멈춘 뒤(12:06:11) api-1을 먼저 올렸다. api-1은 기동 대조(마켓 3개, 적용 1,041건, 대조 1,859건)를 마치고 12:06:22.566에 "팔로워로 기동"을 남겼고, 97ms 뒤 첫 틱에서 리스를 얻어 epoch 6으로 승격했다. 승격은 팔로워 엔진이 모든 마켓에서 저널 head에 닿아 있어 재생을 생략하고 인계 엔진을 설치했다. 접수 재개는 멈춘 뒤 12.314초, 첫 체결은 12.302초다(기동 10.9초, 승격까지 12.0초). 나중에 올린 api-2는 팔로워 상태 1, `FOLLOWER`, 승격 문구 0, 강등 0으로 합류했다(`-S4Boot` 판정 참, [X5/COLD/verdict.json](X5/COLD/verdict.json)의 `s4Boot`). 사후 권위 run CRITICAL 17개 검사 위반 0이다. LB 로그는 두 노드가 멈춘 창 안에 `no live upstreams`·`502` 각 68줄, 창 밖에 각 1줄(P2 전환 구간 12:05:12 UTC)이다. 크래시 창이 있는 콜드 재기동의 이중 재생(설계 9절)은 이번에는 인계로 재생을 생략해 생기지 않았다. S3 이미지의 COLD 값은 S3 관문 증거에 없다. 참고로 ADR 0022 7단계 증거([adr0022-stage7-snapshot-20260915](../adr0022-stage7-snapshot-20260915/README.md), P1 전역 채번)의 COLD RTO는 9.465초였다. 이미지·모드·스냅샷 조건이 달라 비교 판정에 쓰지 않는다.

## X7 처리량 기록 (판정 아님, 잡음 규칙 미충족)

S3 관문 X5 (i)과 같은 러너([roundtrip_probe.py](../../scale/roundtrip_probe.py), 12마켓 600/s, 부하 계정 16개, 봇 20 OPS 예열 뒤 k6 130초, 프로필 `dev,chaos-monkey`, append 분포 오버레이)로 칸마다 `-p exchange-pml` 스택과 볼륨을 새로 세웠다. P2 전환은 예열 전에 했다. 칸 값은 k6 주행 중 1분 창의 접수 201/s다. S3 칸은 S3 커밋으로 다시 구운 `exchange-chaos-app:s3ref`(`sha256:6d97e047…`)를 `:pml`로 붙여 돌렸다. S3 관문 재주행 이미지 `sha256:0ff1d3c1…`는 S4 이미지를 구울 때 사라졌기 때문이다([images.json](images.json)의 `s3ImageLost`, 설계 7-0의 정정). 판정 보조는 [judge_x7.py](judge_x7.py)가 낸 [X7/judge.json](X7/judge.json)이다.

| 칸 | 이미지 | 접수 201/s | 명령당 접수 락 보유(ms) | 락 대기(ms) | append(ms) | 라이터 직렬(ms/명령) | 리더 응답 | 팔로워 503 | 접수 경합 429 | LB 502 | LB 503·504 |
|---|---|---:|---:|---:|---:|---:|---|---:|---:|---:|---|
| S3a | S3 | 158.4 | 23.44 | 9.50 | 27.63 | 13.18 | 201 28,276, 422 5 | 1,870 | 0 | 2,970 | 13·2 |
| S4a | S4 | 143.7 | 20.53 | 7.74 | 27.14 | 13.68 | 201 23,873, 429 26, 422 1 | 3,480 | 29 | 5,913 | 66·17 |
| S3b | S3 | 127.0 | 24.93 | 9.64 | 32.68 | 15.26 | 201 25,723, 422 6 | 2,418 | 0 | 4,931 | 31·5 |
| S4b | S4 | 94.6 | 21.02 | 7.39 | 34.93 | 18.96 | 201 22,502, 429 28, 422 5 | 3,774 | 30 | 6,224 | 50·1 |
| S3c | S3 | 102.8 | 22.33 | 8.23 | 35.32 | 18.22 | 201 23,571, 422 4 | 3,594 | 0 | 6,289 | 70·25 |
| S4c | S4 | 129.6 | 23.02 | 8.21 | 30.66 | 16.05 | 201 24,937, 429 22, 422 2 | 2,662 | 28 | 4,881 | 43·21 |
| S3d | S3 | 104.7 | 19.27 | 7.09 | 32.31 | 16.73 | 201 23,297, 422 1 | 4,233 | 0 | 6,095 | 45·5 |
| S4d | S4 | 106.9 | 18.85 | 7.20 | 31.53 | 15.77 | 201 24,429, 429 22, 422 8 | 4,336 | 29 | 6,198 | 52·2 |

리더·팔로워 응답과 접수 경합은 칸마다 새로 세운 스택의 노드 누계라 예열 구간을 포함한다. 첫 네 칸(S3a·S4a·S3b·S4b)의 잡음 n = |158.4 - 127.0| / 142.7 = 0.22가 0.10을 넘어 네 칸을 더 돌았다. 여덟 칸에서도 S3 칸의 범위/중앙값이 0.48이라 잡음 규칙을 채우지 못했다. 그래서 손실 값은 판단에 쓰지 않는다. 기록으로 남기면 중앙값은 S3 115.9/s, S4 118.2/s(L = -0.021)이고, 연달아 돈 짝(a·b·c·d)의 S4/S3 비는 0.907, 0.745, 1.261, 1.021(중앙값 0.964)이다. 명령당 비용의 중앙값은 접수 락 보유 S3 22.9ms·S4 20.8ms, append 32.5ms·31.1ms, 라이터 직렬 16.0ms·15.9ms로 같은 범위다. 칸 값은 두 이미지 모두 append 시간을 따라 움직였다(append가 긴 칸일수록 낮다). 호스트 부하 표본에서 도커·앱·부하 도구 밖의 바쁜 프로세스는 모든 칸에서 0이었지만 전체 CPU 표본은 칸 사이에 3~65%로 달랐다.

모든 칸에서 400은 0건이고 드레인 뒤 `summary.sql` CRITICAL·HIGH PASS, 교착 증가 0이다. 리더의 주문 접수 응답(`POST /api/v1/orders`)에 5xx는 없었다. S4 칸의 접수 경합 `429`(칸마다 22~30건, 원인 `55P03`과 `lock_wait`)는 600/s 포화에서 난 것이고, S4 칸의 획득 `lock_timeout` 계수와 강등 계수는 0이다. LB의 `502`는 모두 `no live upstreams`와 짝이고, S3b 칸에서 원문을 보니 쓰기 업스트림(`exchange_write`)의 `POST /api/v1/orders`와 `GET /api/v1/orderbook`였다. 포화에서 리더가 가끔 읽기 시간 초과(`504`)나 연결 오류를 내면 `max_fails=1` 규칙이 리더를 2초 빼고, 그 동안의 쓰기가 팔로워 `503`과 LB `502`로 끝나는 것으로 보인다(추론, 원인 확인 안 함). 두 이미지에서 같은 규모이고 인그레스는 S4가 바꾸지 않았다. 파티션별 업스트림(S5)에서 다시 볼 일이다.

## X8 E1·E3 (PASS)

실험마다 새 스택(소유자 로그인)을 세우고 P2로 전환한 뒤(P2 리더 epoch 5) 스크립트를 직접 불렀다. 원자료는 [X8/E1](X8/E1/)과 [X8/E3](X8/E3/)의 `verdict.json`·`result.json`·`console.log`다.

E1([e1-pg-latency.ps1](../../scripts/e1-pg-latency.ps1))은 리더 api-1의 DB 경로에 지연 200ms(흔들림 100ms)를 넣었다. 스크립트 1차 판정이 PASS다. 주입 중 주문 p99가 2,107ms로 기준 17.1ms의 열 배를 넘었고, status·readiness 불량 표본 0, 체결은 주입 중 85건·해제 뒤 354건으로 멈추지 않았고, 해제 뒤 2.2초에 회복했다. epoch는 5에서 그대로이고 자발적 강등 증가, 갱신 거부·오류가 0이며 무결성 위반은 0이다. S4 축은 두 노드의 파티션 강등 계수 0, 리더의 획득 `lock_timeout` 계수 0이다. 주입 창의 접수 경합은 리더에서 `429` 16건(`55P03` 11, `lock_wait` 5)이었다. 설계 4-12가 적은 대로 지연 아래에서 정산이 잔고 행을 1초 넘게 쥐어 같은 계정의 접수가 경합으로 끝난 경우다. 스크립트가 남긴 주문 수용 거절 계수는 193이다.

E3([e3-redis-outage.ps1](../../scripts/e3-redis-outage.ps1))은 Redis 토폴로지가 없어 SKIP이고, 스크립트가 적은 대로 그것이 유효 판정이다. 판정 파일의 리스 holder(`9c5db92d…`)가 비어 있지 않고 살아 있으며 무결성 위반은 0이다. `Get-LeaseResource`로 바꾼 리스 조회가 P2에서 `matching-engine`을 읽었다. E1·E3는 S3 관문에 없던 실험이라 S3와 비교하지 않는다.

## 끝 상태

Final 단계(22:14)에서 `-p exchange-pml` 스택을 `down -v --remove-orphans`로 지웠다. 남은 `chaos-*` 컨테이너와 `exchange-pml` 볼륨은 없다([docker-ps-after.txt](docker-ps-after.txt), [volume-ls-end.txt](volume-ls-end.txt)는 빈 파일). `:pml`은 S4 이미지를 가리킨다. X7을 위해 붙인 참조 태그 `exchange-chaos-app:s3ref`(`6d97e047…`)와 `exchange-chaos-app:s4ref`(`92429e71…`)는 남겼다. `:local`과 앞 단계의 참조 태그(`s2ref`, `mainref`, `b0-ceecaef`)는 그대로다.

## 한계

- X6b와 X7을 빼면 실험마다 1회다. 로컬 Docker Desktop 단일 호스트라 CPU와 디스크를 스택 전체가 나눠 쓰고, 이 기록은 AWS의 장애나 처리량을 대신하지 않는다.
- DB 모드는 P2이고 파티션은 하나다. 두 파티션의 격리, 차단기, 선호·유예, 파티션 층 좀비 판정은 Testcontainers의 P3 흉내 시험으로만 확인했다.
- X7의 S3 칸은 S3 관문 재주행 이미지가 아니라 S3 커밋으로 다시 구운 이미지다. 두 이미지가 같은 코드인지는 jar가 남지 않아 확인하지 못했다. 칸 여덟 개로도 잡음 규칙을 채우지 못했다.
- k6 `order_flow.js`의 `market_contention` 임계는 이 관문의 어느 실험에서도 돌지 않았다. E6는 봇 스파이크이고 X7은 `calibrate.js`다. `k6 inspect`로 임계가 등록되는 것만 확인했다.
- X1의 봇은 목표 300 OPS에 못 미쳤다(약 25.5/s). 원인은 재지 않았고 S3 X1과 같다.
- X2 E4의 창 밖 LB 한 줄은 LB 로그 원문을 남기지 않은 주행이라 원인을 보지 못했다. X4부터는 원문을 남겼고 창 밖 줄은 모두 P2 전환 구간이었다.
- 주행 중에 바꾼 하네스 절차와 X6b 판정 기준은 AI가 정했고 사용자 확인 대상이다. 코드와 증거는 커밋하지 않았다.
