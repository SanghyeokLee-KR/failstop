# S4 관문 폐기용 스택 재주행 (2026-09-25 00:13~01:47 KST)

[S4 설계](../../../docs/reference/architecture/마켓분할_리더_S4_설계.md) 7절의 실험(X1~X8)을 구현 검토 라운드 1(설계 13절) 수정 뒤 코드로 다시 돌린 기록이다. 첫 주행([pml-S4-20260924-1950](../pml-S4-20260924-1950/README.md))은 수정 전 이미지였고 설계 12-4가 수정 뒤 재주행을 NOT RUN으로 남겼다. 스택은 compose 프로젝트 `exchange-pml`의 폐기용 스택이고 DB 모드는 모든 실험에서 P2, 파티션은 `matching-engine` 하나다.

절차는 [run-s4-gate.ps1](run-s4-gate.ps1)(첫 주행의 실행기를 복사해 Prep의 이미지 기록, X7의 참조 태그, X8의 하위 폴더 인자와 컨테이너 로그 저장만 바꿨다), 단계별 시각과 종료 코드는 [commands.log](commands.log), 보존 볼륨 확인은 [volumes.txt](volumes.txt), 처리량 판정 보조는 [judge_x7.py](judge_x7.py)다.

## 결론

관문은 통과하지 못했다. E2(리더의 DB 절단)가 두 번 모두 공통 판정에서 FAIL이었고, X8 E1(PG 지연)이 세 번 가운데 첫 번째에서 FAIL이었다. 나머지 실험은 PASS다. 자산·원장 불변식 위반과 구 epoch 쓰기는 어느 실험에서도 나오지 않았다. 두 E2 FAIL은 DB 일시 장애를 영구 실패로 분류하는 코드 경로 둘에서 나왔고(아래 'E2 FAIL의 원인'), E1 FAIL은 원인 로그가 스택과 함께 사라져 원인을 좁히지 못했다.

| 실험 | 판정 | 요지 |
|---|---|---|
| X1 P2 봇 부하 10분(앱 역할 로그인) | PASS | 판정 축 21개 참. P2 epoch NEW = 예약 21,942, 리더 epoch 6 유지, `ACCEPTING` 한 노드·`FOLLOWER` 한 노드, 두 노드 강등 0, 창 밖 경합 0(append 17,347), LB `no live upstreams`·`502` 0, 획득 `lock_timeout` 0, 연결 최대 37 |
| X2 E4 리더 kill과 합류 | PASS | RTO 6.958초, epoch 5 -> 6, rpo_records 0. 되살린 옛 리더가 13.2초 만에 `FOLLOWER`로 합류(승격 문구 0, 리스 행 불변, 강등 0) |
| X3 E7 리더 pause | PASS | RTO 11.860초. 새 리더의 획득이 `lock_timeout`으로 두 번 끊긴 뒤 얻었다. 옛 리더는 재기동 없이 `LEASE_LOST_WHILE_ACCEPTING` 1로 강등하고 521ms 뒤 재구성해 `FOLLOWER`, 차단기 닫힘 |
| X4 E2 DB 절단 | FAIL(공통 판정) | 스크립트 판정 PASS(RTO 12.148초, rpo 0, 구 epoch 쓰기 0). 드레인 뒤 `summary.sql`의 C17 `REPLAY_DETERMINISM` 1행 |
| X4r E2 재주행 | FAIL(공통 판정) | 스크립트 판정 PASS(RTO 10.186초). 옛 리더의 주문 접수 `500`(축 `orders_500_0` 거짓). `summary.sql`은 PASS |
| X4 E5 네트워크 분리 | PASS | RTO 5.587초, 옛 리더 강등 뒤 56ms 만에 재구성해 `FOLLOWER`, 강등 문구 양성 대조 참 |
| X4 E6 부하 급증 중 kill | PASS | RTO 5.106초, 스파이크 창 권위 run 31회 스캔 위반 0 |
| X5 콜드 재기동 | PASS | 먼저 올린 api-1이 팔로워로 기동한 뒤 195ms 만에 승격(인계로 재생 생략), 재개 15.279초, 첫 체결 15.892초. 나중 노드 `FOLLOWER`, 강등 0(`-S4Boot` 참) |
| X6a 기본 | PASS | L 30건 `429`(market), 재전송 `201`과 NEW 한 행. M 30건 `201`(최대 1,030ms) |
| X6b 줄 머리 막힘 | PASS | L 30건 `429`(market), 저널 행 0. M `201` 35(최대 2,019ms), `429` market 15, node 0, 5xx 0 |
| X6c 같은 키와 재생 | PASS | 같은 키 동시 요청 10회 모두 둘 다 `409 order_in_progress`. R 재전송 `200` 7·`409` 3, `503` 0 |
| X6d 저트래픽 장기 잠금 | PASS | 70초 동안 66회 `429`(market), 두 층 강등 0, epoch 불변, serviceable 1. 쓰기 완료율 표본을 처음 남겼다 |
| X7 처리량(판정 아님) | 기록 | 잡음 n 0.008. 1분 창 값의 손실 L 0.20이 0.10을 넘었으나 같은 k6 창의 저널 행 수는 S4/S3 1.001·0.989다(아래) |
| X8 E1 PG 지연 | FAIL(1/3) | 첫 번째 FAIL(리더 `WRITER_FATAL: UncategorizedSQLException`로 파티션 강등, epoch 5 -> 6). 로그를 남긴 재주행 둘은 PASS |
| X8 E3 Redis 장애 | PASS(SKIP이 유효 판정) | 리스 holder 살아 있음, 무결성 위반 0 |

## 조건

| 항목 | 값 |
|---|---|
| 이미지 | `exchange-chaos-app:pml` = `sha256:5b8c279fb47240bd002f23c53d7e0059638102ced846f58f0f513784b2f0079a`, 참조 태그 `exchange-chaos-app:s4r1ref`([images.json](images.json)) |
| 원천 | 작업 트리 `C:\dev\exchange-pml`(브랜치 `claude/per-market-leader`, HEAD `91813dd5`)의 커밋하지 않은 S4 변경과 구현 검토 라운드 1 수정. 추적 파일 diff SHA-256 `e1c5f20d…`, 변경 경로 149개 |
| jar | `harness/app.jar` SHA-256 `b64eaa64…`. 설계 13-1의 전체 빌드(2026-09-24 23:32~23:48, 시험 834개 통과)가 만든 bootJar와 같은 파일이다. Prep의 `:app:bootJar`는 최신 판정이었다 |
| 마이그레이션 | V0~V53(`flyway_schema_history` 최대 53, 54행, 실패 0) |
| X7 S3 칸 | 첫 주행과 같은 `exchange-chaos-app:s3ref` = `sha256:6d97e047…`(S3 커밋 `60be79e0`을 다시 구운 이미지) |
| 수정 전 S4 이미지 | `sha256:92429e71…`(참조 태그 `exchange-chaos-app:s4ref`). 이번 주행에는 쓰지 않았고 지우지 않았다 |
| 프로필 | X1~X6·X8·장애 실험 `dev,chaos-monkey,prod-timeouts`, X7 `dev,chaos-monkey`와 append 분포 오버레이(첫 주행과 같음) |
| 호스트 | Windows 11, Docker Desktop, 논리 CPU 16. 사용자 프로그램이 함께 돌았다(X7 칸 앞뒤 표본에서 도커·앱·부하 도구 밖의 바쁜 프로세스 0~1개, 전체 CPU 26~83%) |
| 보존 자원 | `exchange-chaos_chaos-pgdata`(생성 2026-09-13T11:42:01Z), `failstop-keep-chaos-pgdata-20260924`(2026-09-23T15:47:36Z). 단계마다 앞뒤로 생성 시각이 같았다. `exchange-chaos-app:local`(`611169b8bf40`)은 그대로다 |

실행기는 셸 변수 둘(`CHAOS_PROJECT`, `COMPOSE_PROJECT_NAME`), `lib.ps1`의 프로젝트, compose 해석값이 모두 `exchange-pml`일 때만 시작한다. 시작할 때 `chaos-*` 컨테이너는 없었다([docker-ps-before.txt](docker-ps-before.txt)는 빈 파일). `exchange-chaos` 프로젝트는 내리거나 올리지 않았다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/k6_keys.json`)은 경로로만 넘겼고, X1의 런타임 로그인 비밀번호는 실행마다 무작위로 만들어 셸 변수로만 넘겼다.

## 이번 주행에서 바꾼 하네스

- `harness/scale/s4_intake_contention.py`: X6d 표본에 두 노드의 `exchange_node_write_completion_ratio`와 `exchange_partition_write_completion_ratio`를 더했다. 설계 13절 C6이 X6d 재주행에서 판정 창의 완료 수를 기록하라고 남긴 것이다. 판정 축은 바꾸지 않았다.
- 실행기의 X8: `-SubDir`로 하위 폴더를 가르고, 스택을 내리기 전에 컨테이너 로그 원문을 저장소 밖 `%TEMP%\pml-s4-logs\<하위 폴더>-<실험>`에 두고 해시를 `result.json`의 `containerLogs`에 남긴다. 첫 X8 E1 FAIL의 원인 로그가 스택과 함께 사라져서 더했다.

## E2 FAIL의 원인

두 번 모두 스크립트 판정(RTO < 30초, epoch 단조 증가와 holder 교체, 구 epoch 쓰기 증분 0, rpo_records 0, 사후 CRITICAL 0)과 파티션 축은 참이었고, S3 공통 판정의 한 축이 거짓이었다. 컨테이너 로그 원문은 `%TEMP%\pml-s4-logs\X4`와 `X4r`에 있다.

첫 번째(X4 E2)는 C17 `REPLAY_DETERMINISM` 1행이다. DB가 끊긴 동안(15:41:18~15:42:49 UTC) 리더 api-1의 라이터가 정산 I/O 오류 뒤 롤백까지 실패해(`TransactionSystemException`) 15:42:08에 `WRITER_FATAL`로 파티션 강등했다. 강등 뒤 fresh 재구성은 DB가 없어 1·2·4·8초 간격으로 실패했다. 네 번째 시도(15:42:45 시작)는 스냅샷 적재가 실패해 전량 재생으로 가던 중 DB가 돌아온 직후 15:42:49.487에 `CannotGetJdbcConnectionException`으로 끝났다. `EngineRecovery.replayRecorded`는 재생이 던진 모든 `RuntimeException`을 `recovery_runs`의 불일치(`matched=false`)로 적는다. 앞 세 번은 DB가 없어 그 적재도 실패했지만(로그 `recovery_runs 적재 실패` 셋), 네 번째는 DB가 돌아온 뒤라 적재가 성공했다. 그래서 결정론 위반이 아닌 연결 실패가 C17 1행으로 남았다(로그 대조로 좁힌 추론이다. DB가 지워져 그 행의 `fp_live`는 보지 못했다). 다섯 번째 시도(15:42:57)는 성공해 `FOLLOWER`로 돌아왔다. 수정 전 코드의 첫 주행 E2는 옛 리더가 DB 복귀 뒤 갱신 거부로 강등해 재구성이 DB가 있을 때 한 번에 끝났다. 이번에는 절단 중 라이터 치명이 먼저 나서 재구성 재시도가 복귀 시점에 걸쳤다. 같은 결함이 S3에 있었는지는 확인하지 않았다(S3에는 파티션 재구성 재시도가 없다).

두 번째(X4r E2)는 옛 리더 api-1의 주문 접수 `500`이다(Prometheus `orders_500_by_instance` api-1 1.02, LB 로그 `500` 1줄). 로그에 두 경로가 있다. (1) 16:32:42.996 `처리되지 않은 예외`: `CommandJournalAppender.append`의 append가 I/O 오류로 실패하고 롤백도 실패해 `TransactionSystemException: JDBC rollback failed`가 `ApiExceptionHandler`의 매핑 밖으로 나갔다. (2) 16:33:19.54 DB 복귀 순간 `ApiAuthFilter`가 깨진 연결의 `DataAccessResourceFailureException`(SQLSTATE 08006)을 일시 오류로 분류하지 않아 인증 단계에서 `500`을 다섯 번 냈다. 이 필터는 `TransientDataAccessException`과 `CannotGetJdbcConnectionException`만 `503`으로 보낸다. 첫 번째 주행의 api-1 로그에도 같은 두 경로가 15:42:49에 다섯 줄 있었는데, Prometheus `increase()`가 처음 생긴 `status="500"` 계열의 첫 증가를 세지 못해 축이 참으로 나왔다. 그래서 `orders_500_0` 축은 이 장애에서 결함을 놓칠 수 있다. 이 두 경로는 단계 계획이 S4에 넣은 '게이트웨이 접수·취소 경로의 일시 DB 오류 per-item 처리'에 해당한다.

두 결함 모두 확정 미접수를 `500`으로 내거나 검증 표를 오염시키는 분류 문제이고, 자산·원장 불변식(C01·C02 등), 구 epoch 쓰기, 멱등성 위반은 나오지 않았다.

## X8 E1 FAIL (첫 번째)

첫 번째 E1(runId `E1-20260925-012408`)에서 지연 주입(200ms, 흔들림 100ms, 16:24:55 UTC부터) 중 16:26:48.63에 리더 api-1이 `WRITER_FATAL: UncategorizedSQLException`로 파티션 강등했고 api-2가 epoch 6으로 승격했다. 스크립트 판정은 `epochStable` 거짓, `leaseGateOk` 거짓(`last_renew_age` 최대 100.5초. 강등 뒤 옛 리더의 값이 자란 것으로 보인다), 사후 헬스 불량 표본 7로 FAIL이다. 무결성 위반은 주입 중·사후 모두 0이고 오류율 0이다. 그 실행기는 컨테이너 로그를 남기지 않아 어떤 SQLSTATE가 분류되지 않았는지 보지 못했다. `prod-timeouts`의 `statement_timeout` 3초, 펜싱 함수의 `RAISE`(P0001), 트랜잭션 상태 오류가 후보지만 확인하지 않았다. 로그를 남기게 고친 뒤 두 번 더 돌렸고([X8r](X8r/E1/), [X8r2](X8r2/E1/)) 둘 다 PASS(epoch 5 불변, 두 층 강등 0, 획득 `lock_timeout` 0, 주입 p99 2,128·2,224ms, 해제 뒤 2.4·2.3초 회복)였다. 첫 주행(수정 전 이미지)의 E1은 1회 PASS였다. 세 번 가운데 한 번이라 재현 조건을 모른다.

## X1 P2 봇 부하 (PASS)

소유자 로그인 스택에서 api-1이 V1~V53을 적용한 뒤 런타임 로그인과 [앱 로그인 오버레이](../pml-s2-gate-20260924-0440/override-app-login.yml)로 다시 띄웠다. P1 리더(epoch 2) 뒤 P2로 전환했다(퇴역 `matching-p0` 3 < `matching-p1` 4 < `matching-engine` 5, 드레인 2.3초, 새 리더 epoch 6). 봇 목표 300 OPS로 10분(15:16:27~15:26:43 UTC) 돌았다. 원자료는 [X1/result.json](X1/result.json), [X1/partition_axes.json](X1/partition_axes.json)이다.

판정 축 21개가 모두 참이다. 권위 run PASS, `summary.sql` CRITICAL·HIGH 29행 PASS, 정산 23514 0, 잠금 계획 이탈 0, 접수 500 0, 접수 40P01 0, 교착 증가 0, 드레인 뒤 정산 전 예약 0, 라이터 정지·강등 문구 0, 권한·함수 오류 0줄, 새 epoch 스냅샷 3마켓, 리더 epoch 표본 모두 6, api 연결 `pml_app`·봇 `pml_bot`만, api-1 `ACCEPTING`·api-2 `FOLLOWER`, 창 밖 경합 0(상한 17.3건), 파티션 경보 발화 0, LB 0줄. 봇은 창 안 201 15,513건(약 25/s)과 422 2건이고 목표에 못 미친 것은 첫 주행·S3 X1과 같다. 연결 표본 최댓값 37, 끝 표본 `pml_app` 24·`pml_bot` 10·소유자 2로 첫 주행과 같다. 경보는 주행 중 `EdgeProbeDown`·`EdgeProbeSlow`(첫 주행과 같음), 드레인 뒤 `OrderP99SloBreach`(X6의 1~2초 대기)와 `BotFleetDown` 대기가 떴고 판정 축이 아니다.

## X6 접수 경합 (a~d PASS)

X1 스택에서 이어 돌렸다([s4-intake-contention.ps1](../../scripts/s4-intake-contention.ps1), 구동기 [s4_intake_contention.py](../../scale/s4_intake_contention.py)). 네 변형 모두 공통 판정(LB `no live upstreams`·`502` 0, 리더 강등 0, 정산 재시도 소진 0)이 참이다. 요약은 [X6/x6_summary.json](X6/x6_summary.json)이다.

| 변형 | 판정 | 요지 |
|---|---|---|
| a(30회) | PASS | L 30건 `429`(market), 재전송 전 저널 행 0, 재전송 30건 `201`과 NEW 한 행. M 30건 `201`, 지연 최대 1,030ms(중앙 1,016ms). 경합 계수 `55P03|429` +30 |
| b(10회) | PASS | L 30건 `429`(market), 저널 행 0. M 50건 가운데 `201` 35(최대 2,019ms), `429` market 15, node 0, 5xx 0. 노드 수용 거절 +28(봇 포함). 경합 `55P03|429` +22, `lock_wait|429` +28 |
| c(10회) | PASS | 같은 키 동시 두 요청 10회 모두 둘 다 `409 order_in_progress`, 해제 뒤 K `201`과 NEW 한 행. R 재전송 `200` 7·`409 order_in_progress` 3, `503` 0, 재조회 모두 `200` |
| d(70초, 봇 0) | PASS | L 66회 모두 `429`(market). 두 층 강등 0, api-1 epoch 6 그대로, serviceable 두 노드·두 층 표본 모두 1, 해제 뒤 `201` |

X6d의 쓰기 완료율 게이지는 리더 api-1에서 노드 층 1.59~2.30, 파티션 층 1.78~2.30이었고 팔로워 api-2는 1이다. 봇이 멈춘 70초 동안 L의 `429` 66건이 수요에서 빠져 완료율이 판정 기준 아래로 내려가지 않았다(C6 수정의 스택 쪽 기록). 첫 주행의 b에서 나온 `429`(node)는 이번에는 M에서 0건이었다.

## X2~X5 장애 실험

실행기가 실험마다 `-p exchange-pml` 스택을 지운 뒤 `adr0022-stage5-chaos.ps1 -Image exchange-chaos-app:pml -Experiments <E> -Protocol 2 -PartitionAxes`를 불렀다(X2 E4, X3 E7, X4 E2·E5·E6, X5 COLD, X4r E2). 폴더마다 `summary.json`, `<E>/verdict.json`, `<E>/s3_common.json`, `<E>/partition_axes.json`이 있고 E4는 `rejoin.json`이 있다.

| 실험 | RTO(초) | epoch | rpo | C22 두 팔 | 예약 행 | 뒤 epoch가 정산한 예약 | 옛 리더 끝 상태 | 공통 판정 | 파티션 축 | 분리 판정 |
|---|---:|---|---:|---|---:|---:|---|---|---|---|
| X2 E4 | 6.958 | 5 -> 6 | 0 | 0, 0 | 1,184 | 0 | 합류 단계에서 `FOLLOWER`, 강등 0 | PASS | PASS | PASS(합류 PASS) |
| X3 E7 | 11.860 | 5 -> 6 | 0 | 0, 0 | 1,277 | 0 | `FOLLOWER`, `LEASE_LOST_WHILE_ACCEPTING` 1, 차단기 닫힘 | PASS | PASS | PASS |
| X4 E2 | 12.148 | 5 -> 6 | 0 | 0, 0 | 1,537 | 1 | `FOLLOWER`, `WRITER_FATAL` 1, 차단기 닫힘 | FAIL(C17 1행) | PASS | FAIL |
| X4r E2 | 10.186 | 5 -> 6 | 0 | 0, 0 | 1,342 | 0 | `FOLLOWER`, `LEASE_LOST_WHILE_ACCEPTING` 1 | FAIL(접수 500) | PASS | FAIL |
| X4 E5 | 5.587 | 5 -> 6 | 0 | 0, 0 | 2,417 | 0 | `FOLLOWER`, `LEASE_LOST_WHILE_ACCEPTING` 1, 차단기 닫힘 | PASS | PASS | PASS |
| X4 E6 | 5.106 | 5 -> 6 | 0 | 0, 0 | 1,424 | 1 | kill(되살리지 않음) | PASS | PASS | PASS |
| X5 COLD | 15.892(첫 체결), 재개 15.279 | 5 -> 6 | 0 | 0, 0 | 1,197 | 0 | 먼저 올린 api-1 리더, 나중 api-2 `FOLLOWER`, 강등 0 | PASS | PASS | PASS(`-S4Boot` PASS) |

모든 실험에서 파티션 축(창 밖 접수 경합 0, 창 밖 파티션 경보 0, 실험 끝 리더 노드 강등 0)이 참이다. E5·E7의 옛 리더 강등 문구 양성 대조가 참이다. 첫 주행의 같은 실험 RTO는 E4 5.436, E7 10.019, E2 6.016, E5 5.627, E6 6.343, COLD 12.314(재개)초였고 S3 관문 재주행은 E4 6.157, E7 10.155, E2 10.488, E5 5.942, E6 6.978초였다. RTO 상한(30초)과 구 epoch 쓰기 0은 이번에도 모든 실험에서 성립한다. 한 번씩 잰 값이라 RTO 차이의 원인은 주장하지 않는다.

X2 E4. api-1을 15:35:57에 kill했고 api-2가 epoch 6으로 승격해 15:36:03.83에 첫 체결을 냈다. 판정 뒤 api-1을 `docker start`로 되살리자 부팅 획득 없이 "팔로워로 기동"을 남기고(승격 문구 0) 13.2초 만에 liveness, 팔로워 상태 1, `FOLLOWER`를 냈다. 되살리기 전후 `leader_lease` 세 행의 holder·epoch가 같고 강등 계수는 0이다([X2/E4/rejoin.json](X2/E4/rejoin.json)). LB는 창 안 `no live upstreams`·`502` 각 193줄, 창 밖 0줄이다.

X3 E7. 새 리더 api-2의 획득 `lock_timeout` 계수가 2였다. 멈춘 api-1이 리스 행을 쥔 트랜잭션 안에서 멈춘 장면이 이번에 나왔고, 획득이 1초 상한으로 두 번 끊긴 뒤 15:38:53.638에 epoch 6을 얻었다(RTO 11.860초). 첫 주행에서는 이 장면이 나오지 않아 획득 상한이 스택에서 발동한 적이 없었다(설계 12-3). 재개된 api-1은 15:39:26.770에 갱신 거부로 리더십을 잃고 15:39:26.799에 파티션 강등(차단기 1회·닫힘)했으며 15:39:27.320에 재구성을 마치고 팔로워로 돌아왔다. 창 안 접수 경합은 모두 0이다.

X4 E5·E6. E5의 옛 리더는 망이 돌아온 뒤 15:46:32.061에 갱신 거부로 강등하고 56ms 뒤 재구성을 마쳤다. 창 안에 `lock_wait_stuck|429` 6건이 있었다. E6 스크립트는 k6를 쓰지 않아 `market_contention` 임계는 이번에도 돌지 않았다. E2의 새 리더도 획득 `lock_timeout` 2(X4r 1)였다.

X5 COLD. 두 노드를 15:52:38에 멈추고 api-1을 먼저 올렸다. api-1은 기동 대조(마켓 3개, 적용 1,062건, 대조 1,887건)를 마치고 15:52:51.990에 "팔로워로 기동"을 남긴 뒤 15:52:52.185에 epoch 6으로 승격했고, 팔로워 엔진이 head에 닿아 있어 재생을 생략하고 인계 엔진을 설치했다. 기동 12.573초, 새 리더 13.712초, 접수 재개 15.279초, 첫 체결 15.892초다. api-2는 팔로워 상태 1, 승격 문구 0, 강등 0으로 합류했다. 사후 권위 run CRITICAL 17개 검사 위반 0이다.

LB 창 밖 줄은 X3 1/1, X4 E2 1/1, E5 `502` 2, E6 `502` 1, X5 1/1(`no live upstreams`/`502`)이고 판정 축이 아니다. 첫 주행처럼 P2 전환이 두 노드를 다시 띄운 구간으로 보이지만 이번에는 원문 시각을 대조하지 않았다.

## X7 처리량 기록 (판정 아님)

첫 주행과 같은 러너([roundtrip_probe.py](../../scale/roundtrip_probe.py), 12마켓 600/s, 부하 계정 16개, 봇 20 OPS 예열 뒤 k6 130초)로 S3a·S4a·S3b·S4b를 칸마다 새 스택에서 돌았다. 판정 보조는 [X7/judge.json](X7/judge.json)이다.

| 칸 | 이미지 | 접수 201/s(1분 창) | k6 창 저널 행 | 칸 끝 1분 창 | 명령당 트랜잭션 | 락 보유(ms) | append(ms) | 직렬(ms/명령) | 리더 201·429 | 팔로워 503 | LB 502 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|
| S3a | S3 | 71.5 | 13,625 | 137 | 22.1 | 32.9 | 51.8 | 27.7 | 16,909·0 | 2,521 | 5,424 |
| S4a | S4 | 54.7 | 13,639 | 158 | 30.2 | 24.2 | 53.9 | 28.9 | 16,717·26 | 4,594 | 8,762 |
| S3b | S3 | 72.1 | 11,705 | 111 | 23.2 | 29.5 | 48.2 | 27.7 | 15,055·0 | 3,657 | 6,356 |
| S4b | S4 | 59.9 | 11,572 | 119 | 27.1 | 27.0 | 53.5 | 28.1 | 14,897·30 | 4,338 | 8,603 |

잡음 n = |71.5 - 72.1| / 71.8 = 0.008이라 칸을 더 돌지 않았다. 판정 보조의 손실 L = 1 - 57.3/71.8 = 0.20이 0.10을 넘는다. 그러나 같은 k6 창의 저널 행 수는 짝마다 S4/S3가 1.001, 0.989이고 칸 전체의 리더 `201` 누계도 1% 안이다. 15초 간격 표본을 보면 1분 창 값이 k6 130초 동안 계속 올라 정점에 닿지 않았고(칸 끝 값은 S4가 더 높다), 칸 값은 그 오르막의 어느 시점을 읽느냐에 따라 달라진다. 그래서 L 0.20은 처리량 손실이 아니라 측정 창 위치에서 온 값으로 본다(추론). 이 러너의 1분 창 값으로 손실을 정하려면 k6 주행을 정점이 나오게 늘려야 한다. 명령당 DB 트랜잭션은 S4 칸이 22~23에서 27~30으로 많았고 원인은 보지 않았다. LB `502`와 팔로워 `503`은 S4 칸이 더 많았다(`max_fails=1`과 `proxy_next_upstream http_503`이 리더를 빼는 동안의 쓰기, 첫 주행 README의 추론과 같다). 모든 칸에서 400은 0건, 드레인 뒤 `summary.sql` CRITICAL·HIGH PASS, 교착 증가 0, 리더 주문 접수 5xx 0, S4 칸의 강등과 획득 `lock_timeout` 0이다.

## X8 E1·E3

실험마다 새 스택(소유자 로그인)을 세우고 P2로 전환한 뒤(리더 epoch 5) 스크립트를 직접 불렀다. E1 결과는 위 'X8 E1 FAIL' 절과 [X8/E1](X8/E1/), [X8r/E1](X8r/E1/), [X8r2/E1](X8r2/E1/)에 있다. 재주행 두 번의 주입 창 경합은 리더에서 `55P03|429` 8·12, `lock_wait|429` 4·4였다. E3([X8/E3](X8/E3/))는 Redis 토폴로지가 없어 SKIP이고 그것이 유효 판정이다. 리스 holder(`31640d22…`)가 살아 있고 무결성 위반 0이며, `Get-LeaseResource`로 바꾼 리스 조회가 `matching-engine`을 읽었다.

## 끝 상태

Final 단계(01:47)에서 `-p exchange-pml` 스택을 `down -v --remove-orphans`로 지웠다. 남은 `chaos-*` 컨테이너와 `exchange-pml` 볼륨은 없다([docker-ps-after.txt](docker-ps-after.txt), [volume-ls-end.txt](volume-ls-end.txt)는 빈 파일). `:pml`은 이번 S4 이미지(`5b8c279f…`)를 가리킨다. 참조 태그 `s4r1ref`(이번 이미지), `s4ref`(수정 전 S4), `s3ref`와 앞 단계 태그, `:local`은 그대로다. 컨테이너 로그 원문은 저장소 밖 `%TEMP%\pml-s4-logs`의 `X2`~`X5`, `X4r`, `X8r-E1`, `X8r2-E1`에 있다. `X2`~`X5` 폴더는 첫 주행과 같은 경로라 첫 주행의 원문을 덮어썼다.

## 한계

- E2 FAIL 두 건과 E1 FAIL 한 건이 남았다. 원인이 코드 쪽이라 이 주행에서 고치지 않았다. E1 첫 FAIL의 원인은 확인하지 못했다.
- 공통 판정의 `orders_500_0` 축은 Prometheus `increase()`가 처음 생긴 `status="500"` 계열의 첫 증가를 세지 못해, 짧은 장애에서 난 `500`을 놓칠 수 있다(첫 E2의 로그에 `500` 다섯 줄이 있었는데 축은 참이었다).
- 실험마다 1회이고(E2 2회, E1 3회), 로컬 Docker Desktop 단일 호스트라 AWS의 장애나 처리량을 대신하지 않는다. DB 모드는 P2이고 파티션은 하나다. 두 파티션의 격리, 차단기 열림, 선호·유예는 Testcontainers 시험으로만 확인했다.
- X7의 S3 칸은 S3 커밋으로 다시 구운 이미지다. X7의 1분 창 값은 오르막 위치에 따라 달라져 손실 판단에 쓰기 어렵다.
- k6 `order_flow.js`의 `market_contention` 임계는 이번에도 어느 실험에서도 돌지 않았다.
- 이 주행에서 바꾼 하네스(X6d 완료율 기록, X8 로그 저장)는 AI가 정했고 사용자 확인 대상이다. 코드와 증거는 커밋하지 않았다. `docs/measurements/`와 설계 12·13절은 고치지 않았다.
