# 마켓 분할 리더 S4b 상세 설계: S4 관문 결함 보정

2026-09-25 작성. [ADR 0034](../adr/0034-market-partitioned-leadership.md) S4(파티션 단위 Java)의 관문을 막은 결함을 고치는 보정 단계(S4b)의 설계다. S4 코드(`48629d5d`)는 서버 전체 빌드를 통과했지만, 구현 검토 수정 뒤 폐기용 스택 재주행에서 E2가 두 번 모두, E1이 세 번 가운데 한 번 FAIL이라 관문을 통과하지 못했다([S4 설계](마켓분할_리더_S4_설계.md) 13-2·13-3, [재주행 증거](../../../harness/evidence/pml-S4-20260925-0012/README.md)). 이 단계는 그 원인 셋(재구성 재시도의 DB 연결 실패가 C17 불일치로 남음, DB 절단 중 접수·인증의 `500`, 원인 SQLSTATE를 모르는 E1의 라이터 치명)과 최종 검토가 남긴 참여 끄기 경합을 고치고, 관문 판정의 빈틈 둘(`orders_500_0`, X7 칸 값)을 메운다. 기준 코드는 작업 브랜치 `claude/per-market-leader`의 `48629d5d`이고 인용한 줄 번호는 그 커밋 기준이다. 계약은 코드보다 먼저 고쳤다. [DB 설계](../contracts/백엔드_DB_설계.md) v2.56(§5.4-c 5·6·9, §5.5), [API 설계](../contracts/백엔드_API_설계.md) v2.56(§2-3, §2-4, §2-5-c 신설, §4-B-3, §4-B-6, §6-C, §6-E), [하네스 검증설계](../contracts/하네스_검증설계.md)의 S4b 패치 노트와 §3·§4-1 C17, [하네스 CI/CD](../contracts/하네스_CICD_IaC.md) §5-2다(8절). Spring·PostgreSQL·nginx 동작 확인의 명령과 원시 출력은 [`harness/evidence/pml-s4b-probe-20260925/`](../../../harness/evidence/pml-s4b-probe-20260925/README.md)에 있다. 같은 날 설계 검토 지적 10건을 받아 설계와 계약을 고쳤다(11절). 코드 단계의 구현과 시험은 12절, 폐기용 스택 결과와 E1 재현 뒤의 정산 재시도 한도 보정(F1)은 13절, 그 코드에 대한 구현 검토 지적 둘의 대응은 14절, 그 보정까지 넣은 이미지의 재주행은 15절, 문서 단계와 남은 것은 16절에 있다. 코드와 증거는 문서 단계에서 작업 브랜치에 한 커밋으로 남겼다.

S4b가 끝나도 DB 모드는 P2이고 파티션은 `matching-engine` 하나다. 새 마이그레이션은 없다(4-2). 자산 보존·주문 멱등·리더 권한 확인·오래된 리더 쓰기 차단을 지키는 DB 쪽 장치(저널 append의 리스 판정, 정산 머리말 판정, 원장 합 0, 잔고 CHECK, 멱등 제약, V47 판정)는 하나도 바꾸지 않는다. 바뀌는 것은 앱이 DB 장애를 분류해 기록하고 응답하는 방식, 참여 조작의 원자성, 관측과 하네스 판정이다(5절).

이 설계를 맡은 AI가 2026-09-25에 정했고 사용자 확인이 필요한 것은 여덟이다(10절). 첫째와 둘째는 작업 지시가 AI 선택으로 정해 준 방향을 구체화한 것이다. 원칙은 하나다. 쓰기 업스트림에서 리더를 빼는 `503`은 이 노드가 DB에 닿지 못함을 두 번째 DB 접근으로 확인했거나 이 노드가 그 마켓의 리더가 아닐 때만 내고, 재전송이 안전하지 않은 쓰기에는 새로 만들지 않는다.

1. 주문 접수의 append가 DB 연결 장애로 끝나면 저널 확인(V47)을 부르고, 확인마저 DB에 닿지 못했을 때만 `503 service_unavailable` + `Retry-After: 1` + 'DB 연결 장애. 재조회 후 판단 필요'이며 멱등 키를 남긴다. 확인이 DB에 닿았는데 판정하지 못했으면(잠금 대기 초과 등) `409 order_in_progress`다. 취소는 대상 주문을 다시 읽어 DB에 닿으면 `409`, 닿지 못하면 `503`이다. 인증 필터의 DB 연결 장애는 `503`이다(4-3).
2. 저널 확인이 이 요청의 명령이 저널에 없다고 답한 연결 장애와, 그 마켓의 게이트를 연 채 받은 리더 자격 거부(재확인이 참인 fencing 거부 등)는 마켓 접수 경합과 같은 규칙(API 설계 §2-5-b)이다. V47이 이 요청의 키를 지웠으면(확정 미접수) `429`(`detail.bucket=market`), 그 밖은 `409 order_in_progress`이고, 이미 접수된 주문의 재전송도 `409`다. 작업 지시의 '확정 미접수는 기존처럼 429/409 규칙'을 구체화한 것이고, 원인이 연결 장애여도 `bucket=market`을 그대로 쓰는 것이 이 설계의 선택이다(4-3).
3. 컨트롤러 계층의 DB 연결 장애는 안전 메서드(GET·HEAD·OPTIONS)와 재전송 안전으로 표시한 처리기(주문, 이체)만 `503`이다. 멱등 키가 없는 쓰기(게시판, 문의, 회원 인증, 관리·하네스 평면)는 S4와 같은 응답(연결 끊김은 `500`)이다(4-3).
4. 배치 주문과 일괄 취소(회원 폐쇄 포함)는 한 원소가 'DB에 닿지 못함'으로 끝나면 남은 원소를 시도하지 않고 비종결 사유로 적는다(4-3).
5. 판정 없이 끝난 재생(DB 접근 실패, 일시 오류 넷, 리더 자격 상실)은 `recovery_runs`에 행을 남기지 않는다. 미완료를 뜻하는 행을 따로 두지 않는다. 스냅샷 폴백은 획득 실패와 연결 끊김일 때만 생략한다(4-2).
6. 응답을 쓸 수 없게 된 요청(`AsyncRequestNotUsableException`, 클라이언트나 LB가 먼저 끊음)은 서버 오류로 렌더하지 않는다(4-3).
7. `orders_500_0`을 LB 로그, 프로메테우스 `max_over_time`, 노드 누계 세 원천으로 판정하고 인증 단계에서 끝난 주문 요청(uri `UNKNOWN`)과 DELETE를 넣는다. X7 칸 값은 k6 주행 창의 저널 행 수를 주행 초로 나눈 값이다(4-7).
8. (조건부) E1이 재현되고 원인이 정산 일시 오류 재시도 소진이면 재시도 한도를 기한 15초·시도 8회로 늘린다. 재현되지 않으면 바꾸지 않는다(4-6). 코드 단계에서 재현돼 적용했다(13-2·13-3).

리더 자격을 잃은 노드의 fencing 거부 `503`은 새 선택이 아니다. S4 설계 4-5·4-13과 API 설계 §2-3이 '게이트 검사와 append 사이에 기간이 바뀐 append는 페일오버 창의 `503`'이라고 이미 적었는데 코드가 `500`을 냈다(2-2). 코드를 계약에 맞춘다. 다만 fencing 거부를 받고도 그 마켓의 게이트가 열려 있는 노드(재확인으로 리스 권위를 증명했거나 새 기간으로 다시 승격함)는 리더이므로 `503`을 내지 않는다(4-3).

## 1. 범위와 전제

범위는 작업 지시의 다섯이다.

- (1) E2 원인 A. `EngineRecovery.replayRecorded`가 재구성 재시도의 DB 연결 실패를 `recovery_runs`의 불일치(C17)로 적는다. 판정 없이 끝난 재생은 재시도 대상으로 가르고, 진짜 내용 불일치는 지금처럼 `matched=false`로 남긴다.
- (2) E2 원인 B. DB 절단 중 주문 접수의 append 롤백 실패(`TransactionSystemException`)와 `ApiAuthFilter`의 `DataAccessResourceFailureException`(08006)이 `500`으로 나간다. 응답 계약을 API 설계에 먼저 적고, 그 `503`이 인그레스에서 리더를 빼는 효과와 DB 복구 뒤 복귀 시간을 확인해 적는다.
- (3) `PartitionRuntime`의 참여 끄기 경합(최종 검토 low).
- (4) E1 첫 FAIL. 라이터 치명 경로에 SQLSTATE와 원인 사슬을 남기고, 컨테이너 로그를 저장하는 실행기로 E1을 반복해 재현을 노린다. 재현되면 원인별 판단표(4-6)대로 고친다.
- (5) 하네스 판정. `orders_500_0` 보강, X7 러너의 칸 값.

전제는 넷이다. DB 모드는 P2이고 이 단계에서 main을 병합하지 않는다. main의 `V47_1` 순서 문제는 S4 설계 9절 그대로 남는다. AWS에 접속하지 않고, 검증은 Testcontainers 일회용 DB와 compose 프로젝트 `exchange-pml`의 폐기용 스택에서만 한다. 새 마이그레이션은 필요 없다(V54 이상을 쓰지 않는다). 호스트에서 다른 작업(관제센터 서버 빌드와 Testcontainers, 웹 검사)이 함께 돌 수 있으므로 실험 판정 전에 호스트 CPU를 기록하고, 잡음이 시간 축 판정을 흔들면 다시 잰다(7-3).

## 2. 현재 코드와 실패 증거에서 확인한 것

인용 줄 번호는 `48629d5d` 기준이다. 경로는 `server/app/src/main/java/kr/exchange/` 아래를 줄여 적었다. 실패 증거는 [재주행 증거](../../../harness/evidence/pml-S4-20260925-0012/README.md)와, 저장소 밖에 둔 그 주행의 컨테이너 로그 원문(`%TEMP%\pml-s4-logs\X3`·`X4`·`X4r`·`X8r-E1`·`X8r2-E1`)이다. 로그 시각은 UTC다.

### 2-1. 재구성 재시도의 연결 실패가 C17 불일치로 남는 길

`PartitionRuntime.rebuild`(`engine/PartitionRuntime.java:263-288`)는 강등한 파티션을 팔로워로 되돌리려고 `EngineRecovery.recoverFollower`(:273)를 부르고, 실패하면 1초에서 30초까지 두 배씩 늘어나는 간격으로 다시 한다(:279-285). 팔로워 복구는 스냅샷 적재(`engine/EngineRecovery.java:314-320`)와 재생(`replayRecorded`, :348-371)을 거친다. `replayRecorded`는 재생이 던진 `RuntimeException`을 원인과 무관하게 `recordMismatch`로 넘겨 `recovery_runs`에 `matched=false` 행을 남긴다(:355-363). C17은 그 행 수다(`harness/InvariantCheck.java:225-226`, `harness/sql/invariants.sql:205`, `harness/sql/summary.sql:184`). 관리 API는 `matched IS DISTINCT FROM true`인 행을 실패로 세고(`admin/HaMonitoringController.java:302`), 웹 복구 표는 `matched === true`만 PASS로 그린다(`web/lib/admin/httpAdmin.ts:1001`, :1015).

첫 번째 E2(재주행 X4)의 옛 리더 로그가 그 길을 그대로 보여 준다. 15:42:08에 라이터가 정산의 I/O 오류와 롤백 실패로 `WRITER_FATAL` 강등했다. 재구성은 15:42:11·21·32·45에 시도했고, 시도마다 먼저 스냅샷 적재에서 커넥션 획득 3초를 기다려 실패한 뒤 '스냅샷 적재 실패. 스냅샷 없이 전량 재생으로 폴백한다'를 ERROR로 남기고(:317-320, :343-346) 전량 재생으로 가서 다시 실패했다. 앞의 세 번은 불일치 행 적재도 DB가 없어 실패했다('recovery_runs 적재 실패' WARN 세 줄). DB가 돌아온 직후인 15:42:49.487의 넷째 실패(`CannotGetJdbcConnectionException`)만 적재가 성공해 C17 1행이 됐다. 다섯째 시도(15:42:57)는 성공했다. 결정론 위반이 아닌 DB 장애가 C17로 기록됐고, 스냅샷 폴백 계수(`exchange.recovery.snapshot_fallback`)는 스냅샷 결함이 아닌 DB 장애를 네 번 셌으며, 쓸모없는 폴백 때문에 시도 하나가 약 6초로 길어졌다.

같은 부류가 하나 더 있다. 승격 재생이 도중에 리스를 잃으면 2단계 진입의 권한 확인이 `IllegalStateException`('크래시 창 마켓 … 재적재 불가: 리더 자격 없음')을 던지는데(:403-405), 이것도 `replayRecorded` 안이라 불일치 행이 된다. S4 주행에서 관측하지는 않았다.

### 2-2. 접수 경로에서 `500`이 나간 네 갈래

주문 접수 append는 append 전용 풀의 `JdbcTransactionManager` 위 `TransactionTemplate`에서 돈다(`config/EngineConfig.java:209-210`, `journal/CommandJournalAppender.java:193-252`). append가 잡는 예외는 `StaleLeaderException`과 `DataAccessException`뿐이다(:241-251). 게이트웨이는 append 예외 뒤 이 요청이 키를 잡았으면 V47 판정을 부르고(`api/order/OrderGateway.java:301-335`, :556-574), `submitFailure`(:590-612)는 마켓 접수 경합·라이터 정지·정지 마켓·P2 잔고 부족이 아닌 예외를 그대로 올린다(:611). `ApiExceptionHandler`는 `TransientDataAccessException`·`CannotGetJdbcConnectionException`·`CannotCreateTransactionException`만 `503`으로 바꾸고(`api/common/ApiExceptionHandler.java:105-117`) 나머지는 마지막 처리기의 `500`이다(:126-131). 인증 필터도 `TransientDataAccessException`·`CannotGetJdbcConnectionException`만 `503`이다(`api/auth/ApiAuthFilter.java:113-120`, 그 밖은 :121-130).

재주행의 앱 로그에서 `500` 네 갈래를 찾았다.

| 갈래 | 사슬 | 관측 | 클라이언트가 받았나 |
|---|---|---|---|
| (가) 접수 롤백 실패 | append 문장의 08006 뒤 같은 연결의 롤백이 `Connection is closed`로 실패해 `TransactionSystemException("JDBC rollback failed")`가 되고, V47 판정도 DB에 닿지 못해 게이트웨이가 그대로 올린다 | X4 E2 15:42:49, X4r E2 16:32:42.996, X4 E5 15:46:44.544 각 1 | 아니다. nginx가 10초(`proxy_read_timeout`)에 이미 끊어 그 창의 LB 로그 `500`은 0이다 |
| (나) 인증 필터 | 절단 중 걸려 있던 `api_keys` 조회가 DB 복귀 순간 08006(`DataAccessResourceFailureException`)으로 끝나 `500` | X4 E2 4, X4r E2 16:33:19.54 5 | 아니다(같은 이유) |
| (다) fencing 거부 | DB 복귀 뒤 옛 리더의 append가 `assert_and_lock_market_leader`의 P0001 'fencing 위반'(`UncategorizedSQLException`)을 받고, append가 재확인 뒤 강등한 다음 다시 던져 게이트웨이가 그대로 올린다 | X4r E2 16:33:21.161 1 | 받았다. LB 로그 16:33:21의 `"POST /api/v1/orders HTTP/1.1" 500`이 재주행 전체의 유일한 클라이언트 `500`이다 |
| (라) 응답 불가 | 멈췄던 옛 리더가 재개해 이미 끊긴 연결에 응답을 쓰다 `AsyncRequestNotUsableException`(Broken pipe)을 받고, 마지막 처리기가 '처리되지 않은 예외' ERROR를 남기고 `500`을 쓰려 한다 | X3 E7 15:39:26.80x 21 | 아니다 |

(가)(나)는 계약과 어긋난다. API 설계 §2-3의 `503` 행은 v2.17부터 '일시적 DB 접근 실패'를 그 코드로 적었고, 하네스 검증설계 §3의 E2 기대 동작은 'append 트랜잭션이 실패하면 ACK 보류(503+Retry-After)'다. (다)는 S4 설계 4-5·4-13이 '게이트 검사와 append 사이에 기간이 바뀐 append'를 페일오버 창의 `503`으로 적은 경우인데 매핑이 없었다. 채번 뒤 삽입이 0행이면 던지는 `StaleLeaderException`(`CommandJournalAppender.java:327-331`)도 처리기가 없어 같은 `500`이다. (나)는 컨트롤러에 닿지 않아 관측 이름표의 uri가 `UNKNOWN`으로 남는다(`config/PublicRequestObservationConvention.java`는 공개 조회의 `429`에만 이름을 준다). 그래서 S3 공통 판정의 `orders_500_0`(uri `/api/v1/orders`)은 이 갈래를 볼 수 없다(2-5). 네 갈래 모두 자산·원장 불변식, 구 epoch 쓰기, 멱등 위반은 없었다.

응답을 가르는 데 필요한 정보 둘이 지금 게이트웨이까지 오지 않는다. 첫째, V47 판정(`resolveFailedSubmit`, :556-574)은 실패 원인을 삼키고 null만 돌려준다. 판정이 DB에 닿지 못했는지, 닿았는데 같은 키의 어드바이저리 락을 기다리다 잠금 대기 초과(55P03)로 끝났는지 가를 수 없다. 기본 풀에는 prod와 prod-timeouts 모두 `lock_timeout` 2초·`statement_timeout` 3초가 걸린다(`application.yml:209`, :447). 둘째, append는 fencing 거부 뒤 재확인이 참이어서 강등하지 않았어도(리스 권위 유지) 같은 예외를 다시 던진다(`CommandJournalAppender.java:241-251`, `engine/LeaderLeaseService.java:366-397`). 재확인이 거짓이면 같은 스레드 안에서 강등이 그 파티션의 게이트를 먼저 닫으므로(`engine/PartitionRuntime.java:187-200`, `engine/PromotionCoordinator.java:269-283`) 예외를 받은 시점의 게이트 상태가 재확인 결과를 드러낸다.

### 2-3. 참여 끄기와 재구성 완료의 경합

참여 여부 `participate`는 `PartitionRuntime`의 volatile 필드다(`PartitionRuntime.java:60`). 재구성 완료(`settleAfterRebuild`)는 그 값을 승격 조율의 상태 락 밖에서 읽은 뒤 `coordinator.rearmIfFollower()`를 부른다(:300). 참여 끄기(`setParticipate(false)`)도 값을 쓰고 후보 표식을 내리는 일을 락 밖에서 한다(:309-311). 그래서 재구성 완료가 참을 읽은 뒤 참여 끄기가 끝나고, 이어서 `rearmIfFollower`(`engine/PromotionCoordinator.java:303-307`)가 상태 락 안에서 표식을 다시 세울 수 있다. 끈 파티션이 후보가 되고, 다음 틱의 획득을 획득 콜백(:238-248)이 상태 `FOLLOWER`와 후보 표식을 보고 정상 승격으로 받는다. 차단기 해제의 `transition(BLOCKED, FOLLOWER) && participate` 뒤 `rearmIfFollower`(`PartitionRuntime.java:333-335`)도 같은 모양이다. 쓰기 권한은 DB 판정을 받으므로 자산·오래된 리더 차단과는 무관하지만 운영자의 재배치(ADR 결정 4)가 무력해진다.

### 2-4. E1 첫 FAIL의 단서

첫 E1(runId `E1-20260925-012408`)의 증거는 판정 파일과 실행기 결과뿐이다. 컨테이너 로그는 스택과 함께 사라졌다. 옛 리더의 `/actuator/partitions` 강등 기록은 `WRITER_FATAL: UncategorizedSQLException`(16:26:48.63)이고, 주입 창의 갱신 거부·오류 증가가 0(`renewRejectedDelta`, `renewErrorDelta`), 갱신 성공 49회, 주입 p99 2,133.5ms, 리더의 접수 경합이 `55P03|429` 4건·`lock_wait|429` 1건이다(`harness/evidence/pml-S4-20260925-0012/X8/E1/`). 로그를 남긴 재주행 둘(PASS)에서는 정산이 55P03을 각각 1회·3회 만났고 모두 첫 재시도에서 흡수했다('정산 일시 DB 오류, 배치를 다시 돌린다 … sqlstate=55P03, 시도=1/4').

라이터 치명 경로는 이렇다. 정산은 55P03·57014·40P01·40001을 첫 시도 포함 4회, 첫 시도부터 5초 기한 안에서 다시 돌리고 넘으면 원래 예외를 올린다(`ledger/SettlementService.java:85-87`, :353-399). fencing 거부(P0001)는 재확인(`demoteUnlessStillLeader`) 뒤 다시 던지고(:437-441) 재시도 대상이 아니라 그대로 올라간다(:370-372). `LoopFatalHandler`는 원인과 무관하게 강등하고(`engine/LoopFatalHandler.java:41-55`), 강등 기록의 `detail`은 사유와 예외의 단순 이름뿐이라(`PartitionRuntime.java:171`) SQLSTATE가 남지 않는다.

3-1의 확인에서 `UncategorizedSQLException`으로 끝나는 것은 55P03과 P0001이다. 57014는 `QueryTimeoutException`, 40P01·40001과 연결 장애는 다른 타입이다. 갱신이 주입 내내 성공했으므로 리스 만료 뒤의 P0001은 가능성이 낮고, DB 설계 §5.4 v2.23은 재확인이 참인 P0001을 DB 시계 후진과 자기 재승격 두 경우로만 본다. 그래서 선두 가설은 정산 55P03 재시도 소진이다. 5초 기한 안에서 잠금 대기 초과(prod-timeouts 기본 풀의 `lock_timeout` 2초)가 세 번 이어지면 셋째 시도 뒤에 기한을 넘는다. 확인한 것이 아니라 추론이다.

### 2-5. 하네스 판정의 빈틈

S3 공통 판정의 `orders_500_0`은 `sum by (instance) (increase(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status="500"}[win]))`가 0인지 본다(`harness/scale/s3_common_axes.py:260-261`, :320). 빈틈이 둘이다. 첫 `500`에서야 생기는 시계열은 그 앞 표본이 없어 `increase()`가 첫 증가를 세지 못한다. 첫 E2 옛 리더의 `500`이 판정에 잡히지 않은 까닭이다. 그리고 uri 필터가 인증 단계 `500`(uri `UNKNOWN`)과 DELETE·배치 경로를 뺀다. S4 실행기의 X1 축 `leader_500_0`은 노드 계수의 전후 차이였지만 같은 uri 필터를 썼다(`harness/evidence/pml-S4-20260925-0012/run-s4-gate.ps1:206-212`, :441).

X7 칸 값 '접수/s'는 k6가 130초 도는 중 `fill` 시점의 프로메테우스 1분 창 rate다(`harness/scale/roundtrip_probe.py:192-207`, :525-530). 재주행 X7에서 이 값이 k6 주행 내내 올라 정점에 닿지 않았고, 손실 L 0.20이 같은 k6 창의 저널 행 수 비(1.001, 0.989)와 맞지 않았다. 러너는 이미 k6 주행 창의 NEW 행을 마켓별로 세고(:301-320) 단일 마켓 칸은 그 수를 주행 초로 나눈다(:396-414).

### 2-6. 인그레스가 다시 보내는 요청

하네스 LB는 조회 세 경로(`/api/v1/(markets|trades|candles)`)를 뺀 모든 경로를 쓰기 업스트림으로 보내고 `proxy_next_upstream error timeout http_502 http_503 non_idempotent`를 건다(`harness/nginx/nginx.conf:59-73`). 주문뿐 아니라 게시판·문의·회원 인증·관리·하네스 평면이 모두 여기를 지난다. 엣지도 주문·이체의 쓰기와 나머지 `/api/` 전부에 같은 설정을 넣는다(`deploy/proxy-common.conf:11`, `deploy/nginx-edge.conf.template:141-152`). 그래서 `503`을 받은 POST는 다음 노드로 한 번 더 간다(3-2의 D1·D3). PUT·DELETE는 `non_idempotent` 없이도 다시 보낸다. `500`은 이 목록에 없어 다시 보내지 않고 `max_fails`에도 세지 않는다.

설정 주석이 밝히듯 이 재전송의 안전 근거는 주문의 `client_order_id` 멱등이다. 이체 요청은 필수 `Idempotency-Key`와 유일 제약으로 같은 효과를 낸다(`api/transfer/TransferService.java:60-84`). 게시판 글 작성의 멱등은 노드 메모리(`api/community/CommunityService.java:56`)라 다른 노드에서는 보이지 않고, 댓글·신고·좋아요·문의 작성에는 DB 멱등이 없다. 인증 필터는 `api_keys`와 세션을 읽기만 하고 nonce는 노드 메모리에 두므로(`api/auth/ApiAuthService.java`, `NonceStore`) 인증 단계에서 끝난 요청은 어느 노드에도 흔적을 남기지 않는다. 2-2의 롤백 실패처럼 커밋 여부를 모르는 예외(3-1의 J8)를 `503`으로 내면 멱등 키가 없는 쓰기가 다른 노드에서 한 번 더 실행된다. S4 이전부터 `503`이던 예외(커넥션 획득 실패, 트랜잭션 시작 실패, `TransientDataAccessException`)는 그 트랜잭션이 커밋 전에 끝난 경우다.

## 3. Spring·PostgreSQL·nginx 동작 확인

설계 전에 불확실한 동작 두 묶음을 폐기용 컨테이너(`pml-probe-*`, `--rm`)에서 확인했다. 명령과 원시 출력은 [`harness/evidence/pml-s4b-probe-20260925/`](../../../harness/evidence/pml-s4b-probe-20260925/README.md)에 있다. 카오스 스택, 실험 스택, 보존 볼륨에는 접근하지 않았고, 끝난 뒤 남은 컨테이너와 망이 없는 것을 확인했다.

### 3-1. J: 예외 번역과 트랜잭션 템플릿

앱 JAR(`harness/app.jar`, S4 재주행 이미지와 같은 파일)에서 꺼낸 Spring JDBC·TX 6.2.19, pgjdbc 42.7.11, HikariCP 6.3.3으로 PostgreSQL 17.10을 불렀다. 연결 장애는 toxiproxy 2.12.0에 E2와 같은 독성(`timeout` 0, `downstream`)을 걸고 2초 뒤 지워 만들었다.

| 번호 | 장면 | 앱이 받은 예외(원인 SQLSTATE) |
|---|---|---|
| J1 | `statement_timeout` 300ms에 `pg_sleep(1)` | `QueryTimeoutException`(57014) |
| J2 | 다른 세션이 행을 `FOR UPDATE`로 쥔 동안 `lock_timeout` 300ms | `UncategorizedSQLException`(55P03) |
| J3 | 교착 | `PessimisticLockingFailureException`(40P01) |
| J4 | 반복 읽기 갱신 충돌 | `CannotAcquireLockException`(40001) |
| J5 | `RAISE EXCEPTION 'fencing 위반: …'` | `UncategorizedSQLException`(P0001). 뒤이어 풀 연결은 정상 |
| J6 | 트랜잭션 안에서 그 백엔드를 `pg_terminate_backend`한 뒤 문장 | `TransactionSystemException("JDBC rollback failed")`, 원인 `SQLException("Connection is closed")`(SQLSTATE 없음), `getApplicationException()`은 `DataAccessResourceFailureException`(57P01) |
| J7 | E2 모양(문장 중 차단, 2초 뒤 제거) | J6과 같은 모양, `getApplicationException()`은 `DataAccessResourceFailureException`(08006, `EOFException`) |
| J8 | 같은 차단을 커밋 직전에 | `TransactionSystemException("JDBC rollback failed")`, `getApplicationException()` 없음. 관리 연결로 본 행 수는 1이라 커밋은 됐다 |
| J9 | 프록시를 끈 직후 풀 연결로 조회, 이어 트랜잭션 시작 | 0ms에 `DataAccessResourceFailureException`(08006), 1.5초 뒤 `CannotCreateTransactionException`(원인 `SQLTransientConnectionException` 08001) |

이 표에서 설계에 쓰는 사실은 넷이다. 연결이 끊긴 트랜잭션은 HikariCP가 그 연결을 버린 뒤 롤백이 SQLSTATE 없는 `SQLException`으로 실패해 `TransactionSystemException`이 되고, 원래 예외는 `getApplicationException()`에만 남는다(J6, J7). SQLSTATE로만 분류하면 이 예외를 놓치며 2-2의 (가)가 바로 이것이다. `TransactionSystemException`은 커밋이 됐을 수도 있다(J8). 앱이 받은 예외만으로는 결과를 알 수 없고 V47 판정만 가를 수 있다. 57014는 이미 일시 오류이므로 E1의 `UncategorizedSQLException`은 57014가 아니다(2-4). 끊긴 풀 연결은 검증 없이 한 번 나갈 수 있다(J9). HikariCP는 최근 500ms 안에 쓴 연결을 검증하지 않고 내주기 때문이다. DB가 흔들린 직후에는 DB가 이미 살아 있어도 연결 장애 한 건이 날 수 있다(4-4). 그런 오류 뒤 같은 풀의 다음 조회는 정상이었다(J7·J8 뒤 `SELECT 1 = 1`). 연결 끊김으로 끝난 판정을 한 번 더 부르면 다른 연결로 DB 도달 여부를 다시 볼 수 있다는 뜻이다(4-3).

### 3-2. N2: DB 연결 장애 `503`과 쓰기 업스트림

S4 확인의 가짜 노드와 nginx 1.29.8 설정 틀(하네스와 엣지의 `exchange_write`와 같은 줄)을 그대로 쓰고, 0.1초 간격 요청으로 응답과 복귀 시간을 쟀다. 하네스 형태는 1차(A)와 `backup max_fails=0`(B), 엣지 형태는 두 노드 `max_fails=1` 라운드로빈이다. 둘 다 `proxy_next_upstream error timeout http_502 http_503 non_idempotent`, `tries 2`, `fail_timeout=2s`다.

| 번호 | 배치 | 결과 |
|---|---|---|
| D1 | 하네스. A(DB에 닿지 못하는 옛 리더)가 `503`, B(새 리더)가 `200` | 20건 모두 `200`. A는 첫 요청 한 번만 받고 빠졌다 |
| D1' | 같은 배치에서 A가 `500` | 20건 모두 `500`. B는 한 번도 받지 않았다 |
| D2 | 하네스. A(리더)가 DB 불통으로 `503`, B(팔로워)가 `503`. 3.25초에 A 복구 | 첫 요청 `503` 뒤 `502`(`no live upstreams`). 복구 뒤 첫 `200`은 5.61초(복구 뒤 2.36초) |
| D2' | 같은 배치에서 A가 `500` | 불통 동안 모두 `500`, 복구 뒤 첫 요청(0.11초 뒤)부터 `200` |
| D3 | 엣지. A가 `503`, B가 `200` | 20건 모두 `200`. A는 한 번 받았다 |
| D4 | 엣지. A(리더) `503`, B(팔로워) `503`. 3.23초에 A 복구 | 불통 동안 `503` 두 건 외에는 `502`. 복구 뒤 첫 `200`은 5.58초(복구 뒤 2.35초) |
| D4' | 같은 배치에서 A가 `500` | 불통 동안 `500`, 복구 뒤 곧바로 `200` |

S4 확인의 N-C1·N-C4도 함께 읽는다. DB가 멀쩡한 리더가 `503`을 한 건만 내도 하네스 형태에서 약 1.3초, 엣지 형태에서 약 2.1초 동안 모든 쓰기가 `502`였고 각각 3.47초·4.50초에 회복했다([S4 확인](../../../harness/evidence/pml-s4-probe-20260924/README.md)).

## 4. 설계

### 4-1. DB 불통 분류

새 판별 `journal/DbOutageErrors`를 `TransientDbErrors`·`FencingErrors`·`SqlErrors` 옆에 둔다. `kind(Throwable)`은 예외를 넷으로 가르고 앞의 규칙이 먼저다.

1. 획득 실패. 원인 사슬에 `CannotGetJdbcConnectionException`이 있거나, 원인에 HikariCP의 획득 시간 초과(`SQLTransientConnectionException`)를 둔 `CannotCreateTransactionException`이 있다(J9). 풀에서 연결을 얻지 못해 문장을 보내지 않았다.
2. 연결 끊김. 원인 사슬에 `TransactionSystemException`이나 그 밖의 `CannotCreateTransactionException`이 있거나, 사슬에서 처음 만난 SQLSTATE가 08(연결 예외)이나 57P01·57P02·57P03(관리자 종료, 비정상 종료, 기동 중)이거나, SQLSTATE 없이 `DataAccessResourceFailureException`이 있다(J6~J9). 얻은 연결이 문장·커밋·롤백 도중 끊긴 것이라 커밋 여부를 모를 수 있다(J8).
3. 자원 부족. 처음 만난 SQLSTATE가 53(연결 수 초과, 디스크, 메모리)이다. 서버가 그 문장을 거부했다.
4. 아님. 그 밖이다. 일시 오류 넷(55P03·57014·40P01·40001, `TransientDbErrors`), fencing 거부(P0001, `FencingErrors`), 무결성 위반(23xxx), 프로그램 한도(54xxx), 시스템 오류(58xxx), 내부 오류(XX000), DB 밖 예외가 여기 든다. 54xxx와 58xxx는 스프링이 `DataAccessResourceFailureException`으로 번역하지만 연결이 아니라 문장이나 서버 결함의 신호라 넣지 않는다.

앞의 셋을 합쳐 DB 불통(`isOutage`)이라 한다. 쓰임은 넷이고 쓰임마다 대상이 다르다.

| 쓰임 | 대상 | 절 |
|---|---|---|
| 접수·취소·인증·컨트롤러의 응답 | DB 불통 셋 | 4-3 |
| 재생의 판정 없는 끝(불일치로 적지 않음) | DB 불통 셋, 일시 오류 넷, 리더 자격 상실 | 4-2 |
| 스냅샷 폴백 생략 | 획득 실패, 연결 끊김 | 4-2 |
| V47 판정과 취소 재조회를 한 번 더 부르기 | 연결 끊김 | 4-3 |

모든 호출자의 분류 순서는 마켓 접수 경합(접수 락 대기 초과와 일시 오류 넷), 리더 자격 거부(fencing 거부와 기간 종료), DB 불통, 그 밖이다. 롤백까지 실패한 fencing 거부는 원래 예외가 `TransactionSystemException` 뒤에 가려져 오므로 연결 끊김으로 분류된다. 결과를 모르는 쪽으로 다루는 것이라 안전하다.

`SqlErrors`에 `describe(Throwable)`를 더한다. 원인 사슬을 `UncategorizedSQLException > PSQLException(55P03)` 꼴로 여섯 단계까지 적고, `TransactionSystemException`이면 ` | 앱 예외 ` 뒤에 `getApplicationException()`의 사슬을 붙인다. 강등 기록, 라이터 치명 로그, 재생 미완료 로그, 접수의 DB 불통 로그가 같이 쓴다.

### 4-2. 판정 없이 끝난 재생은 불일치가 아니다

`replayRecorded`의 catch(:355-363)를 둘로 가른다(DB 설계 §5.5 v2.56).

- 판정 없는 끝. 사슬에 `ReplayMismatchException`이 없고, `DbOutageErrors.isOutage`이거나 `TransientDbErrors.isRetryable`이거나 사슬에 `NotLeaderException`이 있다. 재생이 대조를 끝내지 못한 것이라 `recovery_runs`에 적지 않고, WARN 한 줄('재생이 판정 없이 끝났다. 불일치로 적지 않고 복구를 실패로 올린다', `from_seq`, `describe`)을 남긴 뒤 그대로 던진다.
- 그 밖. `ReplayMismatchException`, 엔진·계약 예외, 분류되지 않은 DB 예외는 지금처럼 `matched=false` 행을 남기고 던진다. 원인을 모르는 예외를 불일치 쪽에 두는 것이 C17을 보수적으로 지키는 쪽이다.

`replayToCheckpointThenTail`의 권한 확인 실패(:403-405)는 `IllegalStateException` 대신 `NotLeaderException`을 던져 판정 없는 끝임을 예외 타입으로 드러낸다. 재생 밖에서 같은 뜻으로 던지는 문장(:268-271)은 기록 경로가 아니라 바꾸지 않는다. 승격 조율은 재생 실패를 타입과 무관하게 회복 가능 반납으로 내리므로(VM 오류만 따로 본다) 동작이 같다.

`recoverFromSnapshots`의 두 catch(:317-320, :329-336)는 폴백 여부를 기록 여부와 따로 정한다. 스냅샷 적재나 스냅샷 시작 재생이 획득 실패나 연결 끊김으로 끝나면 폴백하지 않고 던지며 폴백 계수도 올리지 않는다. 이 노드가 지금 DB에 닿지 못하므로 전량 재생도 첫 조회에서 같은 이유로 실패하고, 폴백 계수가 스냅샷 결함이 아닌 DB 장애를 세기 때문이다(2-1의 네 번). 일시 오류 넷과 자원 부족은 종전처럼 폴백한다. 57014(문장 시간 초과)나 53200(메모리 부족)은 특정 문장의 실패라, 기본 풀의 `statement_timeout` 3초 아래에서 큰 bytea 스냅샷을 읽는 조회(`snapshot/SnapshotStore.java:93-118`)에서만 계속 날 수 있다. 전량 재생은 짧은 구간 조회라 성공할 수 있다. 이런 실패에 폴백하지 않으면 재구성은 30초 간격으로 끝없이 실패하고 기동 복구는 컨테이너 재기동을 되풀이한다. 스냅샷은 저널 파생 최적화라 그 결함이나 크기가 복구를 막으면 안 된다(ADR 0022 7단계). 스냅샷 판독 실패나 불일치 같은 스냅샷 결함도 지금처럼 폴백한다. 불일치 행은 `replayRecorded`가 이미 남긴다. 일시 오류 넷이나 자원 부족으로 끝난 스냅샷 시작 재생은 판정 없는 끝이라 행을 남기지 않고 폴백한다. 승격 재생이 리더 자격을 잃었으면 종전처럼 폴백하지 않는다(:330-333).

호출자는 바꾸지 않는다. 파티션 재구성은 1초에서 30초까지의 간격으로 다시 하고, 승격 재생은 회복 가능 반납 뒤 다음 획득에서 처음부터 다시 하며, 기동 복구의 실패는 기동 실패라 컨테이너 재기동 정책이 다시 띄운다.

판정 없는 끝을 따로 적는 행(`matched` NULL과 원인 문자열)을 두는 안은 고르지 않았다. 관리 API와 웹이 `matched`가 참이 아닌 행을 실패로 세므로 세 소비자를 함께 바꿔야 하고(2-1), DB가 닿지 않는 동안에는 그 행을 적을 수도 없다(첫 E2의 네 번 가운데 세 번). 관측은 WARN 로그와 기존 지표(재구성의 `exchange.partition.rebuild{outcome="failure"}`, 승격의 회복 가능 반납 계수)로 한다. `recovery_runs`의 열 주석(V24)은 남는 행에 대해 여전히 참이다. 그래서 새 마이그레이션이 없다.

### 4-3. DB 연결 장애와 리더 자격 거부의 응답

응답 계약은 API 설계 v2.56 §2-5-c다. 게이트웨이 `submitFailure`(:590-612)와 취소 경로(`cancel` :682-704, `submit` :801-823)는 경합 판별 다음에 리더 자격 거부와 DB 불통을 더 가른다. 인증 필터와 컨트롤러 계층은 DB 불통을 요청의 성격에 따라 바꾼다. 가르는 기준은 하나다. 쓰기 업스트림에서 이 노드를 빼는 `503`은 이 노드가 그 마켓의 게이트를 닫았거나(리더가 아님), 실패한 문장과 다른 두 번째 DB 접근마저 DB에 닿지 못했을 때만 낸다. DB에 닿는다는 것이 확인되면 마켓 접수 경합과 같은 `429`·`409`다(3-2, S4 설계 4-13). 그리고 재전송이 안전하지 않은 쓰기에는 새 `503`을 만들지 않는다(2-6).

**V47 판정의 결과.** `resolveFailedSubmit`은 판정 결과나 마지막 실패의 예외를 돌려준다. 종전에는 실패를 삼키고 null이었다(2-2). 첫 호출이 연결 끊김으로 끝나면 한 번 더 부른다. 끊긴 연결은 풀에서 버려지므로(3-1) 두 번째 호출은 다른 연결을 쓴다. 획득 실패는 다시 부르지 않는다. 기본 풀의 연결 대기 3초를 한 번 더 쓰게 되고, 획득 실패 자체가 풀에서 연결을 얻지 못했다는 확인이기 때문이다. 둘째 호출은 키를 지우지 않는다(`p_discard_key` 거짓). 첫 호출이 실제로 돌아 이 요청의 키를 지우고 커밋한 뒤 응답만 잃었으면, 그 사이 같은 내용의 다른 요청이 같은 해시로 키를 새로 넣고 접수했을 수 있다. V47은 해시와 빈 공유 표시만 보고 키를 넣은 요청을 가리지 못하므로(`V47__order_intake_failure_resolution.sql:76-81`) 둘째 호출이 키를 지우면 그 주문이 키를 잃는다(14절 I1). 그래서 둘째 호출은 저널만 본다. 커밋된 NEW는 두 호출 모두 `JOURNALED`로 보고, 저널에 없으면 `NOT_JOURNALED`라 확정 미접수를 주장하지 않는 `409`다. 결과는 셋으로 모인다. 판정됨(JOURNALED·DISCARDED·KEPT·ABSENT·NOT_JOURNALED), DB에 닿았으나 판정 못 함(마지막 실패가 DB 불통이 아닌 55P03·57014 등), DB에 닿지 못함(마지막 실패가 DB 불통)이다.

**리더 자격 거부와 게이트.** fencing 거부(`assert_and_lock_market_leader`의 P0001 'fencing 위반', 옛 epoch의 삽입 0행 `StaleLeaderException`)와 기간 종료(append 전 기간 검사의 `NotLeaderException`)는 그 append가 트랜잭션째 롤백됐거나 DB에 가지 않은 것이라 이 명령은 저널에 없다. 응답은 예외를 받은 순간 이 노드가 그 마켓의 게이트를 열어 두었는지로 가른다. 새 `EngineBridge.accepting(marketId)`가 접수 게이트의 기간(`AcceptGate.openTerm`)을 메모리에서 읽는다. 재확인이 거짓이면 append가 예외를 올리기 전에 같은 스레드에서 강등이 게이트를 닫으므로(2-2), 닫힌 게이트는 '이 노드는 그 마켓의 리더가 아니다'를 뜻한다. 이때는 페일오버 창의 `503`이고 LB가 같은 요청을 새 리더로 넘긴다. 게이트가 열려 있는 경우는 셋이다. 재확인이 참이다(DB 시계 후진 등, DB 설계 §5.4 v2.23). 재확인 왕복이 실패해 판단을 다음 갱신 틱에 넘겼다. 같은 노드가 새 기간으로 다시 승격했다. 첫째와 셋째는 이 노드가 정당한 리더이므로 `503`으로 빼면 안 되고, 둘째는 DB에 닿지 못하는 노드일 수 있으므로 V47 결과에 맡긴다. 그래서 게이트가 열려 있으면 연결 장애와 같은 V47 규칙을 쓴다. DB 설계 §5.4-c 6의 '재확인이 참이면 재시도한다'는 접수 경로에서 클라이언트의 같은 키 재전송(`429`·`409`, `Retry-After: 1`)이 맡는다.

주문 생성에서 키를 잡은 요청은 V47이 JOURNALED를 답하면 어느 경우든 종전처럼 커밋 회수(정상 접수 응답)다. 그 밖은 아래 표다. 리더 자격 거부이고 게이트가 닫혔으면 V47 결과와 무관하게 `503` 페일오버(`fencing|503`)이고, V47은 키 정리를 위해 그대로 부른다.

| V47 판정 | append가 DB 불통 | append가 리더 자격 거부, 게이트 열림 |
|---|---|---|
| DISCARDED | `429 too_many_requests`, `detail.bucket=market`, `Retry-After: 1` (`connection\|429`) | `429` 같은 모양 (`fencing\|429`) |
| KEPT·ABSENT·NOT_JOURNALED | `409 order_in_progress`, `Retry-After: 1` (`connection\|409`) | `409` (`fencing\|409`) |
| 판정 실패, DB에 닿음 | `409 order_in_progress` (`connection\|409`) | `409` (`fencing\|409`) |
| 판정 실패, DB에 닿지 못함 | `503 service_unavailable`, 'DB 연결 장애. 재조회 후 판단 필요', `Retry-After: 1`. 키를 남긴다 (`connection\|503`) | `503` 같은 모양 (`connection\|503`) |

키를 잡지 않은 재전송은 V47을 부르지 않는다. DB 불통이면 `409 order_in_progress`(`connection|409`)이고, 리더 자격 거부면 게이트가 닫혔을 때 `503` 페일오버(`fencing|503`), 열렸을 때 `409`(`fencing|409`)다. S4의 응답은 모든 칸이 `500`(롤백 실패, 연결 끊김, fencing 거부)이나 `503`(획득 실패, 기간 종료)이었다. 나머지 경로는 아래와 같다.

| 경로 | 실패 | S4 응답 | S4b 응답 |
|---|---|---|---|
| 취소(단건, by-client) | DB 불통 | `500`·`503` | 대상 주문 재조회가 DB에 닿으면 `409 order_in_progress`('취소 처리 중. 재조회 필요'), 닿지 못하면 `503` 'DB 연결 장애' |
| 취소 | 리더 자격 거부 | `500`·`503` | 게이트 닫힘 `503` 페일오버, 열림 `429`(bucket market) |
| 일괄 취소, 회원 폐쇄 | 위 둘 | 요청 전체가 오류 | 원소별 사유를 적고 미완료로 센다(아래) |
| 배치 주문 원소 | 접수의 위 실패 | 배치 전체가 오류 | 원소별 `in_progress`(아래) |
| 인증 필터 | DB 불통 | `500`(연결 끊김)·`503`(획득 실패) | `503`, '일시적 처리 불가. 잠시 후 재시도 필요' |
| 컨트롤러, 안전 메서드와 재전송 안전 처리기 | DB 불통 | `500`·`503`(획득 실패) | `503`, 'DB 연결 장애. 재조회 후 판단 필요' |
| 컨트롤러, 그 밖의 쓰기 | 연결 끊김, 자원 부족 | `500` | 그대로(로그만 바꾼다) |
| 컨트롤러, 모든 요청 | 획득 실패, 트랜잭션 시작 실패, `TransientDataAccessException` | `503`(v2.17) | 그대로 |
| 컨트롤러 | 그 밖의 `DataAccessResourceFailureException`(54xxx, 58xxx) | `500` | 그대로 |
| 비접수 경로 | 55P03 | `500` | 그대로(S5에서 다시 본다) |
| 어디든 | 응답을 쓸 수 없음(`AsyncRequestNotUsableException`) | ERROR와 `500` 렌더 시도 | 렌더하지 않고 WARN 한 줄 |

키와 예약은 V47 규칙 그대로다. 판정이 실패하면(DB에 닿았든 닿지 못했든) 키를 지우지 않고(:315-319, :556-574), P1의 in-flight 예약은 TTL로 지연 회수하며(:396-405), P2의 DB 예약은 저널 행과 함께 커밋되거나 롤백된다. 그래서 같은 키 재시도가 DB 복구 뒤에 들어오면, 커밋됐던 주문은 저장된 해시 대조와 저널 확인으로 멱등 재생(`200`)이 되고, 커밋되지 않았던 주문은 공유 표시를 채워 새 접수(`201`)가 된다. 이 두 갈래가 6-2 `IntakeOutageRetryIntegrationTest`의 대상이다. 판정이 실패했는데 키를 지우면 커밋됐던 주문의 재시도가 대조 불가 `409 duplicate_client_order_id`로 끝나 클라이언트가 새 키로 다시 내게 되고, 그것이 이중 주문 경로다. 게이트웨이 분기는 P1과 P2가 함께 쓰므로 P1의 지연 회수(`outcomeUnknown`)와 즉시 해제도 새 분기마다 시험한다(6-2).

DB에 닿지 못했을 때만 `503`인 이유는 인그레스다(4-4). 두 번째 DB 접근마저 닿지 못했다면 이 노드를 쓰기 업스트림에서 빼도 잃는 것이 없고, 옛 리더라면 오히려 새 리더로 넘어간다. DB에 닿은 경우에 `503`을 내면 살아 있는 리더를 빼서 최대 약 3.5초 모든 쓰기를 `502`로 만들 수 있다(3-2). 판정이 잠금 대기 초과로 끝난 경우도 DB에 닿은 경우다. append 연결의 클라이언트 쪽만 끊기고 서버 쪽 백엔드가 트랜잭션 안에서 남아 있으면(`idle_in_transaction_session_timeout` 전) 그 백엔드가 쥔 `cmd-new:` 어드바이저리 락을 V47이 기다리다 `lock_timeout` 2초 뒤 55P03으로 끝난다. DB는 살아 있으므로 `409`다. 그래서 작업 지시대로 마켓 접수 경합과 같은 규칙(API 설계 §2-5-b)을 쓴다. `429`·`409` 모두 `proxy_next_upstream`에 없어 리더를 빼지 않는다. DISCARDED는 원인이 연결 장애여도 확정 미접수다. V47은 같은 키의 append 머리말과 같은 트랜잭션 어드바이저리 락(`cmd-new:` 키, 현재 머리말은 V50 판)을 잡고 판정하므로(`V47__order_intake_failure_resolution.sql:56-57`, `V50__order_intake_reservation.sql:113-114`), 연결이 끊긴 append가 서버 쪽에서 아직 끝나지 않았으면 그 커밋이나 롤백을 기다린 뒤 본다. 커밋됐으면 JOURNALED이고, 키를 지웠으면 그 순간 이 키로 커밋됐거나 커밋 중인 주문이 없다. 그래서 `429`의 약속(같은 키로 다시 보내면 새 접수 하나로 수렴)이 경합 때와 같이 성립한다. KEPT·ABSENT·NOT_JOURNALED와 재전송은 확정 미접수가 아니므로 `409 order_in_progress`다. 이 코드는 §2-4·§2-5-b에서 이미 'Retry-After 뒤 같은 키로 다시 보내거나 재조회하라'는 뜻이다. `429`의 `detail.bucket`은 경합과 같은 `market`으로 둔다. 연결 장애용 버킷 값을 새로 만들면 §2-3의 버킷 목록을 읽는 클라이언트(웹, 봇, k6)가 바뀌어야 하고 클라이언트 동작은 같기 때문이다. 원인은 새 계수기의 `cause`와 WARN 로그로 가른다. 대가는 9절에 적었다.

게이트가 닫힌 노드의 fencing 거부 `503`은 계약에 코드를 맞추는 것이다. 거부된 append는 트랜잭션째 롤백돼 저널에 없고(J5 뒤 풀 연결이 정상), 이 노드는 더는 그 파티션의 리더가 아니다. `proxy_next_upstream http_503 non_idempotent`가 같은 POST를 다음 노드로 넘기므로 새 리더가 같은 키로 받는다. S4 재주행 X4r의 유일한 클라이언트 `500`이 이 경우였다(재확인이 거짓이라 강등했다).

**취소의 재조회.** 취소에는 V47 같은 저널 판정이 없다. append가 DB 불통으로 끝나면 대상 주문 행을 한 번 다시 읽어(`orders.byInternalId`) DB 도달 여부만 본다. 연결 끊김이면 한 번 더 읽는다. 읽혔으면 `409 order_in_progress` + `Retry-After: 1`('취소 처리 중. 재조회 필요')이다. 취소 명령이 저널에 들어갔는지는 모르므로 읽은 상태가 종결이어도 `409`로 답하고, 클라이언트는 재조회나 같은 취소의 재전송으로 수렴한다(종결 주문의 재취소는 `200` + 현재 상태, §4-B-2). 읽기마저 DB에 닿지 못했으면 `503`이다. 리더 자격 거부는 그 취소가 롤백된 것이 확정이라, 게이트가 열려 있으면 경합과 같은 `429`(확정 미접수), 닫혀 있으면 `503` 페일오버다. 커밋을 가린 롤백 실패로 저널에만 들어가고 라이터 큐에 들어가지 못한 취소는 같은 마켓의 다음 명령이 갭을 드러낼 때 적용된다. 주문 생성의 커밋 회수처럼 즉시 회수하지 않는 것은 S4와 같다.

**원소별 처리와 조기 종료.** 배치 주문(§4-B-6)은 원소의 실패를 위 응답으로 바꿔 `in_progress`로 적고 다음 원소로 간다. 일괄 취소(§4-B-3)와 회원 폐쇄(§6-E)는 DB 불통으로 끝난 취소를 단건 응답(`409`·`503`)과 무관하게 `service_unavailable`로 적고 미완료로 센다. 연결 장애로 끝난 취소는 저널에 들어갔을 수 있지만 확인하지 못했기 때문이다. 단건의 `409 order_in_progress`를 그대로 쓰면 일괄 취소가 그것을 '저널에 들어간 뒤 반영 대기'로 보고 미완료로 세지 않는다(§6-E). 리더 자격 거부는 게이트가 닫혔으면 `service_unavailable`, 열렸으면 `too_many_requests`이고 둘 다 미완료다. 한 원소가 'DB에 닿지 못함'(V47 판정이나 취소 재조회마저 DB 불통)으로 끝나면 남은 원소는 시도하지 않는다. 배치는 `in_progress`(`service_unavailable`, `retry_after=1`), 일괄 취소는 `service_unavailable`로 적는다. 이유는 둘이다. 남은 원소를 시도하면 그 원소의 접수 전 조회(계정 상태, 멱등 키, 취소 대상 주문)가 같은 DB 불통을 원시 예외로 올려 요청 전체가 오류로 끝나고, 앞 원소들의 결과를 잃는다. 그리고 원소마다 append 획득 대기(append 풀 2초)와 판정·재조회의 연결 대기(기본 풀 3초)를 다시 쓰며 수용 슬롯과 스레드를 쥔다. 조기 종료는 그 시점까지의 결과를 살린다. 시도하지 않은 원소는 같은 `client_order_id`로 다시 보내면 새 접수가 되고, 일괄 취소는 다시 부르면 남은 주문을 취소한다. 접수 전 조회의 DB 불통이 요청 전체를 끝내는 것은 S4와 같다(주문 경로는 재전송 안전 처리기라 `503`이다). 게이트웨이는 'DB에 닿지 못함'의 `503`에 렌더하지 않는 표식을 달아 반복문이 알게 한다. 라이터 정지나 게이트 닫힘의 `503`은 다른 마켓에 해당하지 않을 수 있으므로 조기 종료하지 않는다.

**인증 필터와 컨트롤러.** 인증 필터는 DB 불통을 모두 `503` + `Retry-After: 1`('일시적 처리 불가. 잠시 후 재시도 필요')로 낸다. 인증은 읽기만 하므로(2-6) 어느 메서드든 다음 노드로 넘어가도 이중 실행이 없다. 컨트롤러 계층(`ApiExceptionHandler`)은 다시 보낸 요청이 한 번의 효과로 모이는지로 가른다. 안전 메서드(GET·HEAD·OPTIONS)와, 재전송 안전 표식(새 `api/common/RetrySafeWrite`)을 단 처리기의 DB 불통은 `503`('DB 연결 장애. 재조회 후 판단 필요', `Retry-After: 1`)이다. 표식은 `OrderController`와 `TransferController` 두 클래스에만 단다. 주문 경로는 `client_order_id` 멱등과 멱등 취소가, 이체는 필수 `Idempotency-Key`와 유일 제약이 다시 보낸 요청을 한 번의 효과로 모은다. 그 밖의 쓰기(게시판, 문의, 회원 인증, 데모 세션, 관리·하네스 평면)는 S4와 같은 응답이다. 연결 끊김과 자원 부족은 `500 server_error`이고, 로그만 '처리되지 않은 예외' ERROR 대신 'DB 연결 장애로 쓰기 결과를 확인하지 못했다(재전송이 안전하지 않은 경로라 500)' WARN 한 줄로 바꾼다. `500`은 `proxy_next_upstream` 목록에 없어 다른 노드가 같은 쓰기를 다시 하지 않는다. 획득 실패·트랜잭션 시작 실패·`TransientDataAccessException`은 v2.17부터 모든 메서드에서 `503`이고 바꾸지 않는다(한계는 9절). 처리기는 `@ExceptionHandler`가 받는 `HandlerMethod` 인자로 표식을 본다. 재전송 안전은 그 처리기의 멱등 설계에서 나오는 성질이라 처리기 클래스에 표식을 둔다. 표식이 없는 처리기는 `500` 쪽(재전송 없음)으로 닫히고, 표식을 단 클래스 목록은 시험이 고정한다(6-2 `RetrySafeWriteInventoryTest`).

DB 불통과 리더 자격 거부의 응답은 마켓 접수 경합이 아니므로 좀비 판정 수요에서 빼지 않는다. 45초 넘게 DB에 닿지 못하는 리더는 두 층 판정으로 강등돼야 한다(DB 설계 §5.4-c 8). `ApiException`의 수요 제외 표식을 세우지 않는 것으로 충분하다.

관측은 새 계수기 하나다. `exchange.order.intake.unavailable{cause,response}`를 `ApiConfig`의 경합 계수기 옆(`config/ApiConfig.java:390-399`)에 미리 등록한다. `cause`는 `connection`·`fencing`, `response`는 `503`·`429`·`409`이고 여섯 조합을 모두 쓴다. `fencing|503`은 게이트가 닫힌 노드의 응답이고, `fencing|429`·`fencing|409`는 게이트를 연 채 리더 자격 거부를 받은 노드의 응답이다. 연결 장애의 `429`·`409`는 모양이 경합 응답과 같지만 경합 계수기(`exchange.order.intake.contention`)에는 세지 않는다. S4 관문의 '창 밖 접수 경합 상한' 축이 DB 장애를 경합으로 세지 않게 하려는 것이다. 미리 등록해야 프로메테우스 `increase()`가 첫 증가를 센다. 판정 실패 수(`exchange.order.intake.unresolved`, DB에 닿았든 닿지 못했든)와 커밋 회수 수(`exchange.order.intake.commit_recovered`)는 그대로 쓴다(:409-423). 로그는 응답마다 WARN 한 줄(경로, 응답 코드, V47 호출 수, `describe`)이다.

응답을 쓸 수 없는 요청은 `ApiExceptionHandler`에 `AsyncRequestNotUsableException` 처리기를 두어 아무것도 렌더하지 않고 WARN 한 줄(메서드, 경로)만 남긴다. 스프링의 기본 처리(`DefaultHandlerExceptionResolver`)도 이 예외에 응답을 쓰지 않지만, 마지막 처리기(`Exception`)가 먼저 잡아서 그 경로에 닿지 않았다. 클라이언트에게 가지 않은 응답을 서버 오류로 세지 않으려는 것이고, 보강한 `orders_500_0`(4-7)이 E7에서 이 경우를 `500`으로 세지 않게 한다. 그 요청의 서버 쪽 결과는 달라지지 않는다. 접수됐으면 저널에 있고 클라이언트는 §7-6 재조회로 수렴한다.

### 4-4. 인그레스 영향과 E2 판정의 정합

인그레스(`harness/nginx/nginx.conf`, `deploy/nginx-edge.conf.template`)는 바꾸지 않는다. S4b가 리더의 `503`을 새로 만드는 곳은 넷이다. V47 판정마저 DB에 닿지 못한 주문 접수, 대상 주문 재조회마저 DB에 닿지 못한 취소, 인증 필터의 DB 불통, 컨트롤러의 DB 불통 가운데 안전 메서드와 재전송 안전 처리기다. 리더 자격 거부의 `503`은 그 마켓의 게이트를 닫은 노드, 곧 강등된 옛 리더의 것이다. 재확인으로 리스 권위를 증명했거나 새 기간으로 다시 승격해 게이트를 열어 둔 노드는 `429`·`409`를 낸다(4-3). 3-2의 결과로 보면 효과는 장애의 모양에 따라 셋으로 갈린다.

- 옛 리더만 DB에 닿지 못하고 다른 노드가 새 리더인 경우(E2). 옛 리더의 `503`은 다음 노드로 넘어가 클라이언트가 새 리더의 `201`을 받는다(D1, D3). 종전 `500`은 그대로 클라이언트에게 갔고, 하네스 배치에서는 옛 리더가 1차라 빠지지 않고 모든 쓰기를 `500`으로 돌려보냈다(D1'). S4b는 E2에서 클라이언트가 보는 응답을 좋게 만든다.
- 리더가 DB에 닿지 못하고 다른 노드는 팔로워인 경우(DB 전체 장애, 리더만의 짧은 단절). 장애 동안은 `503`·`502`와 `500` 모두 5xx라 클라이언트 규약(§2-3 장애 규약, 재조회)이 같다. 다른 점은 복귀다. `503`은 리더를 `fail_timeout`(2초) 동안 빼므로 DB가 돌아온 뒤 최대 약 2.4초 동안 `502`가 남는다(D2, D4). 종전 `500`은 곧바로 회복했다(D2', D4'). 이 지연은 그런 장애의 복구 시간에 더해진다. 하네스 E2는 리더의 프록시 하나만 끊는 실험이라 이 모양을 재지 않는다.
- DB는 살아 있는데 쓰던 연결만 끊긴 경우. 장애 직후 풀이 끊긴 연결을 검증 없이 내주면(J9) 그 연결을 받은 한 건이 실패한다. 인증 필터와 컨트롤러의 조회(주문 접수 전의 계정 상태·멱등 키·잔고 조회 포함)는 그 한 건을 곧바로 `503`으로 내므로, 하네스 배치에서 약 1.3초, 엣지 배치에서 약 2.1초 동안 모든 쓰기가 `502`다(S4 N-C1, N-C4). 종전 `500`은 그 한 건만 실패했다. append 뒤의 V47 판정과 취소 재조회는 연결 끊김이면 한 번 더 DB에 닿아 보므로 끊긴 연결 하나로는 `503`이 되지 않고, 잠금 대기 초과처럼 DB가 답한 실패도 `409`다(4-3). 연결 끊김이 두 번 이어지면(끊긴 연결이 풀에 여럿이고 모두 최근 500ms 안에 쓰였을 때) 여전히 `503`이 날 수 있다. HikariCP는 최근 500ms 안에 쓴 연결만 검증 없이 내주므로 이 경우는 500ms보다 짧은 흔들림이나 쓰던 연결을 골라 끊는 경우(운영자 종료, `idle_in_transaction_session_timeout`)에 한정되고, 정상 운영에서는 끊긴 연결이 생기지 않는다. X1(10분 부하)의 LB `no live upstreams`·`502` 0 판정이 정상 운영에서 이 경로가 나지 않는지 보고, B1(7-2 Y6)이 DB가 흔들린 직후의 크기를 잰다.

재전송이 안전하지 않은 쓰기의 연결 끊김은 S4와 같은 `500`이라 LB가 다른 노드로 다시 보내지 않고 리더도 빼지 않는다(4-3). 새 `503`이 다른 노드에서 실행되는 요청은 아무것도 쓰지 않았거나(인증, 안전 메서드) 다시 보내도 한 번의 효과로 모이는 요청(주문 경로, 이체)뿐이다.

E2의 판정은 이 동작과 맞는다. E2 RTO는 주입 시각부터 새 epoch의 첫 체결까지이고(`Get-FirstTradeSql`, §5-1 T5), 새 리더(api-2)의 체결로 잰다. 절단 동안 옛 리더의 요청은 DB 응답을 기다리다 nginx의 10초 `proxy_read_timeout`이나 커넥션 획득 초과의 `503`(종전부터 있음)으로 다음 노드에 넘어간다. S4b가 바꾸는 것은 DB가 돌아온 순간 옛 리더가 내는 응답뿐이고, 그 무렵에는 새 리더가 이미 체결하고 있으므로 RTO는 바뀌지 않는다. `orders_500_0`은 이제 옳은 이유로 참이어야 한다. 복귀 순간 옛 리더의 접수는 DB가 돌아왔으므로 대개 V47이 답해 `429`·`409`이고, 판정마저 닿지 못했으면 `503`이다. fencing 거부는 재확인이 거짓(새 리더가 리스를 쥠)이라 강등이 게이트를 닫으므로 `503`이고 LB가 새 리더로 넘긴다. 주문 경로의 컨트롤러 조회와 인증 필터의 연결 끊김도 `503`이다. 이미 끊긴 연결에 쓰는 응답은 서버 계수에 남더라도 `500`이 아니다. 판정은 보강한 세 원천(4-7)으로 하고, 새 계수기로 이 경로들이 실제로 났는지를 기록한다(7-2).

S5가 파티션별 업스트림을 만들 때(단계 계획 S5) 이 판단과 비접수 55P03의 매핑을 다시 본다. `max_fails=0`이나 `proxy_next_upstream`에서 `http_503`을 빼는 안은 S4 설계 4-13과 같은 이유로 쓰지 않는다.

### 4-5. 참여 여부와 후보 표식을 한 락 안에서

참여 여부를 승격 조율(`PromotionCoordinator`)의 상태 락(`stateLock`, :76) 아래로 옮긴다(DB 설계 §5.4-c 9 v2.56).

- `PromotionCoordinator`에 `participating`(상태 락으로 보호, 기본 참)과 `setParticipating(boolean on)`을 둔다. 락 안에서 값을 바꾸고, 끄면 같은 락 안에서 리스의 후보 표식을 내린 뒤(`lease.disarm()`) 그 순간 쥔 기간(`heldTerm`)을 돌려준다.
- `rearmIfFollower()`(:303-307)는 락 안에서 `state == FOLLOWER && participating`일 때만 표식을 세운다.
- `PartitionRuntime`은 `participate` 필드(:60)를 없애고 생성 때 `coordinator.setParticipating(participate)`를 부른다. `start()`(:153-159)의 `disarm`은 그 호출이 대신한다. `settleAfterRebuild`(:300)와 `resetBreaker`(:333-335)는 참여 여부를 읽지 않고 `rearmIfFollower()`만 부른다. `setParticipate(false)`(:308-316)는 `setParticipating(false)`가 돌려준 기간이 있으면 계획 강등한다. `setParticipate(true)`는 차단기가 닫혀 있으면 `rearmIfFollower()`를 부른다. `participating()`과 관리 조회(:365)는 승격 조율의 값을 읽는다.

이러면 두 순서만 남는다. 참여 끄기가 먼저 락을 잡으면 재구성 완료의 `rearmIfFollower`는 꺼진 값을 보고 표식을 세우지 않는다. 재구성 완료가 먼저면 표식이 섰다가 참여 끄기가 같은 락 안에서 다시 내린다. 끈 뒤의 획득은 획득 콜백이 락 안에서 표식이 내려간 것을 보고 곧바로 놓는다(:239-243). 락 순서는 상태 락에서 리스의 `leadershipLock`으로 한 방향이고(종전 `rearmIfFollower`와 같다), 리스 쪽에서 상태 락을 잡는 경로는 없다.

### 4-6. E1: 기록, 재현, 원인별 판단

기록은 셋을 고친다.

- `LoopFatalHandler.accept`(:41-55)의 ERROR 한 줄에 파티션 자원, 기간의 epoch, 처음 만난 SQLSTATE, `describe` 사슬을 싣는다. 스택 트레이스는 지금처럼 남긴다.
- `PartitionRuntime.demote`의 강등 기록 `detail`(:171)을 `WRITER_FATAL: UncategorizedSQLException sqlstate=55P03` 꼴로 바꾼다. `TransactionSystemException`이면 `sqlstate=08006(앱 예외)`처럼 앱 예외의 SQLSTATE를 적고, 없으면 `sqlstate=-`다. `/actuator/partitions`의 `demotions[].detail`로 나가므로(API 설계 §6-C v2.56) 컨테이너 로그를 잃어도 실험 실행기가 긁은 파티션 스냅샷에 원인이 남는다.
- 정산 재시도 소진 로그(`SettlementService.java:378-381`)는 이미 SQLSTATE와 시도 수를 싣는다. 바꾸지 않는다.

재현은 7-2의 Y3로 한다. E1을 새 스택에서 다섯 번 돌린다. 관문 조건(세 번 PASS)을 앞 회차에서 채워도 회차를 줄이지 않는다. 실행마다 컨테이너 로그 원문을 저장소 밖(`%TEMP%\pml-s4b-logs`)에 저장하고 해시를 남기며, 두 노드의 정산 일시 오류 재시도(`exchange_settlement_transient_retries_by_state_total{sqlstate}`), 재시도 소진(`exchange_settlement_transient_exhausted_total`), 파티션 강등 기록, 로그 문구 수('재시도 제한 안에서 흡수하지 못했다', '단일 라이터 루프 정지', 'fencing 거부를 받았으나')를 실행 전후로 남긴다. 관문은 E1 세 번 PASS다(재현되면 고친 뒤 다시 세 번). 첫 세 번이 모두 PASS여도 재현을 노려 두 번을 더 돌리고, 그 두 번의 FAIL은 재현으로 다룬다.

재현되면 로그와 기록으로 원인을 가르고 아래 표대로 판단한다. 어느 경우에도 DB 쪽 판정(fencing), 기간 결속, 강등 뒤 재구성, 차단기는 약화하지 않는다.

| 관측 | 판단 | 조치 |
|---|---|---|
| 정산 재시도 소진(55P03이나 57014, '재시도 제한 안에서 흡수하지 못했다') | 재시도 한도(4회, 5초)가 E1 지연(200±100ms)의 잠금 대기 사슬보다 짧다. 한도를 넘긴 일시 오류를 치명으로 올리는 fail-stop 자체는 옳다(아래 문단) | 조건부 수정 F1(한도 조정) |
| P0001 뒤 재확인이 참('fencing 거부를 받았으나 리스 권위는 유지된다') 인데 루프가 치명 | DB 설계 §5.4 v2.23과 §5.4-c 6은 재확인이 참이면 '호출자가 재시도한다'고 적었는데 정산은 P0001을 재시도 대상에서 빼고 올린다(:437-441, :370-372). 계약과 코드가 어긋난다 | 조건부 수정 F2(재확인 참이면 한도 안에서 재시도) |
| P0001 뒤 재확인이 거짓 | 리스를 실제로 잃었다. 강등이 옳다. E1 계약(리스 유지)이 깨진 원인은 갱신 쪽이다 | 갱신 지연·실패의 원인 조사. 강등 규칙은 그대로 |
| 40P01·40001 소진 | 정산 잠금 계획(S3) 뒤에는 결함 신호다 | 원인 분석 뒤 별도 판단. 응답과 한도는 바꾸지 않는다 |
| 08xxx·`TransactionSystemException` | 커밋 여부를 알 수 없는 연결 오류라 치명이 설계다(ADR 결정 8). 지연 주입만으로 연결이 끊겼다면 주입 도구 쪽 원인이다 | 치명 유지, 주입 경로 조사 |
| 그 밖(25P02, XX000 등) | 결함 | 원인 분석 |

57014(문장 취소)가 라이터를 치명으로 만드는 것이 옳은지부터 본다. 한 번의 57014는 라이터를 치명으로 만들지 않는다. 정산이 일시 오류로 제한 재시도하고(S1), 치명은 한도를 넘겼을 때뿐이다. 한도를 넘긴 일시 오류를 치명으로 올리는 것은 옳다. 파티션 층 좀비 판정은 접수 append 커밋도 완료로 세므로(DB 설계 §5.4-c 8) 정산만 멈춘 라이터를 보지 못한다. 한도 없이 재시도하면 접수는 계속 ACK되는데 그 마켓의 체결이 무기한 멈춘 반쪽 리더가 된다(`LoopFatalHandler` 머리 주석의 실패 모드). 따라서 고칠 수 있는 것은 한도의 크기이고 한도의 존재가 아니다. J1에서 57014는 `QueryTimeoutException`으로 번역되므로 E1 첫 FAIL의 `UncategorizedSQLException`은 57014가 아니었다.

조건부 수정 F1(원인이 정산 재시도 소진일 때만). 정산의 일시 오류 재시도를 시도 8회, 첫 시도부터 15초 기한으로 늘리고 대기는 50~100, 100~200, 그 뒤 200~400밀리초로 둔다. 15초는 멈춘 트랜잭션을 끊는 `idle_in_transaction_session_timeout`(카오스 10초)에 잠금 대기 초과 2초와 여유를 더한 값이다. E7처럼 멈춘 옛 리더가 잔고 행을 쥔 채 끊기기를 기다리는 새 리더의 정산도 이 한도 안에 든다. 시도마다 기간 확인(`term.requireCurrent`)과 DB fencing 판정(`assert_and_lock_market_leader`), 레지스트리 멱등 판정을 새 트랜잭션에서 다시 하므로 이중 정산과 오래된 리더의 쓰기가 생기지 않는다. 한도를 넘기면 여전히 치명이다. 대가는 한 마켓 라이터가 치명 전에 멈춰 있을 수 있는 시간이 약 7초에서 약 18초로 느는 것이고, 좀비 판정 창(45초)과 in-flight 예약 TTL(P1, 30초)보다 짧다. 적용 순서는 DB 설계 §5.4 v2.51 정산 sink 오류 분류와 ADR 결정 11의 값을 먼저 고치고(사용자 확인 대상), 코드와 `SettlementRetryBudgetIntegrationTest`(외부 세션이 잔고 행을 9초 쥐면 흡수, 20초 쥐면 치명)를 넣은 뒤 이미지를 다시 굽는 것이다.

조건부 수정 F2(원인이 재확인 참인 P0001일 때만). 정산이 fencing 거부를 받고 기간의 재확인이 참이면 그 배치를 재시도 한도 안에서 다시 돌린다. 재확인이 참이라는 것은 `renew_leader_lease`가 (holder, epoch)가 여전히 리스 행의 값이고 만료 전임을 DB로 증명했다는 뜻이다. 다시 돌리는 시도도 DB 판정을 다시 받는다. 거짓이면 종전처럼 강등하고 올린다. 재현되지 않으면 이 어긋남은 9절에 남기고 이 단계에서 고치지 않는다.

### 4-7. 하네스 판정 보강

`orders_500_0`은 세 원천을 모두 본다(하네스 검증설계 S4b 패치 노트). 판정 대상은 주문 경로(`POST`·`DELETE`, uri가 `/api/v1/orders`로 시작하는 것)와 인증 단계에서 끝난 `POST`·`DELETE`(uri `UNKNOWN`)의 `500`이다.

1. LB 로그. `docker logs --since <창 시작> chaos-lb`에서 `"(POST|DELETE) /api/v1/orders[^ ]* HTTP/[0-9.]+" 500 ` 줄 수가 0이다. 클라이언트가 실제로 받은 `500`이다. 응답 바이트 수가 500인 정상 줄을 세지 않도록 요청 줄의 닫는 따옴표를 붙인다(S4 설계 12-1의 11).
2. 프로메테우스. `sum by (instance) (max_over_time(http_server_requests_seconds_count{method=~"POST|DELETE",status="500",uri=~"/api/v1/orders.*|UNKNOWN"}[<창>]))`가 0이다. 실험마다 스택을 새로 세우므로 계수는 프로세스 시작 때 0이고, 창 안의 최댓값이 0보다 크면 `500`이 한 번이라도 긁힌 것이다. `increase()`의 첫 증가 누락이 없다.
3. 노드 누계. 판정 시점에 두 노드의 `/actuator/prometheus`에서 같은 필터의 누계 합이 0이다. 마지막 스크레이프 뒤에 난 `500`을 잡는다. 실험이 멈춘 채 두는 노드(판정 시점에 컨테이너가 돌지 않는 노드, E6이 kill한 옛 리더)는 누계를 읽을 수 없으므로 합에서 빼고 `stopped`로 적는다. 긁기에 실패한 노드가 멈췄는지는 `docker inspect`의 `State.Running`으로 가른다. 돌고 있거나 확인하지 못하면 읽지 못한 것이다(13-4, 코드 단계에서 더했다).

셋 가운데 하나라도 읽지 못하면 축은 거짓이다. 창 안의 LB 줄이 하나도 없거나 창 안에 `http_server_requests_seconds_count` 시계열이 하나도 없는 것도 읽지 못한 것으로 친다. 로그 수집이나 긁기가 멈춘 창에서 공허한 0이 나오는 것을 막으려는 것이다. 종전 `increase()` 값은 기록으로 남긴다. 노드가 재기동한 실험(E4 합류, COLD)에서 재기동 전 누계는 2가 덮고, 2는 kill 직전 2초 안의 `500`을 놓칠 수 있지만 그것이 클라이언트에게 갔다면 1이 잡는다. 멈춘 채 두는 노드(E6)도 같은 이유로 1·2가 덮는다. 읽은 노드가 하나도 없으면(두 노드가 모두 멈췄거나 도는 노드를 긁지 못함) 3을 읽지 못한 것으로 친다.

구현은 `harness/scale/s3_common_axes.py`에 판정 함수와 하위 명령 `orders500 --window-from <ISO> --out <json>`을 두고, `judge`가 같은 함수로 `orders_500_0`을 낸다. S4b 실행기의 X1과 X8은 하위 명령을 부른다. 같은 파일의 DB 상태 질의(`STATE_SQL`)에 `recovery_runs`의 `matched` 참·거짓 행 수를 더하고, 축 `recovery_runs_mismatch_0`(거짓 0)과 양성 축(참 1 이상)을 둔다. 실험은 P2 전환이 두 노드를 다시 띄우므로 기동 복구의 성공 행이 늘 있다. 이 양성 축은 C17의 기록 경로가 배선됐다는 증거이고, 재구성 경로를 탔다는 증거는 아니다(그것은 7-2의 기록 항목이다).

X7 칸 값은 `roundtrip_probe.py`에 '접수/s(k6 저널 행)'을 더해 k6 주행 창의 k6 NEW 행 합을 주행 초(`--duration`)로 나눈 값으로 한다. 단일 마켓 칸(:396-414)과 같은 방식이다. 1분 창 값은 기록으로 남긴다. 창 시작 시각(`t0`)과 끝 시각(`t1`)의 간격이 아니라 주행 초로 나누는 이유는 `t1`이 k6 종료 뒤 표본 스레드가 멈출 때까지(최대 15초) 늦게 찍히기 때문이다. X7은 관문이 아니므로 러너 변경은 unittest로 확인하고 실제 칸 측정은 선택이다(7-2 Y5).

### 4-8. P2에서 달라지는 것

S4 이미지와 비교해 P2에서 보이는 차이다. 관문의 판정 술어(RTO, 구 epoch 쓰기, 불변식)는 같고 `orders_500_0`과 `recovery_runs` 축이 더 엄격해진다.

| 차이 | S4 | S4b | 관문에서 보는 곳 |
|---|---|---|---|
| 주문 접수의 DB 연결 장애 | `500`(롤백 실패, 연결 끊김)이나 `503`(획득 초과)이 섞임 | V47이 DB에 닿지 못하면 `503`, 닿았으면 `429`(확정 미접수)·`409` | Y2 E2, Y6 B1, `OrderGatewayOutageMappingTest` |
| V47 판정 | 실패를 삼키고 null | 결과나 실패 원인을 돌려주고 연결 끊김이면 한 번 더 부름 | `OrderGatewayOutageMappingTest` |
| 리더 자격 거부로 끝난 접수·취소 | `500`(fencing)·`503`(기간 종료) | 게이트 닫힘 `503` 페일오버, 열림 `429`·`409` | Y2 E2, `OrderGatewayOutageMappingTest` |
| 취소의 DB 연결 장애 | `500`·`503` | 재조회가 DB에 닿으면 `409`, 닿지 못하면 `503` | Y6 B1, `OrderGatewayOutageMappingTest` |
| 일괄 취소·배치의 DB 연결 장애 | 요청 전체가 오류 | 원소별 사유, DB에 닿지 못하면 남은 원소는 시도하지 않음 | `OrderGatewayOutageMappingTest`, `OutageResponseChainTest` |
| 인증 필터의 DB 연결 장애 | `500` | `503` | Y2 E2, `ApiAuthFilterOutageTest` |
| 컨트롤러의 DB 연결 장애 | `500` | 안전 메서드와 재전송 안전 처리기는 `503`, 그 밖의 쓰기는 `500` 그대로 | `ApiExceptionHandlerTest`, `RetrySafeWriteInventoryTest` |
| 판정 없이 끝난 재생 | `matched=false` 행 | 행 없음, WARN | Y2 E2, `RebuildOutageRetryIntegrationTest` |
| 스냅샷 적재·시작 재생의 DB 장애 | 전량 재생으로 폴백, 폴백 계수 증가 | 획득 실패·연결 끊김이면 폴백하지 않고 실패, 일시 오류 넷과 자원 부족은 종전처럼 폴백 | `SnapshotHookWiringIntegrationTest` |
| 참여 끄기와 재구성 완료의 경합 | 끈 파티션이 후보로 돌아올 수 있음 | 돌아오지 않음 | `PartitionTermRaceTest` |
| 강등 기록 `detail` | 사유와 예외 이름 | SQLSTATE 추가 | Y3 E1, `PartitionsEndpointTest` |
| 응답을 쓸 수 없는 요청 | ERROR와 `500` 렌더 시도 | 렌더하지 않음, WARN | Y4 E7 |
| 지표 | 없음 | `exchange.order.intake.unavailable{cause,response}` 여섯 조합 | Y2·Y6 기록 |
| (조건부) 정산 재시도 한도 | 4회, 5초 | 8회, 15초 | Y3 E1(재현 시) |

### 4-9. 바꾸는 파일

경로는 `server/app/src/main/java/kr/exchange/` 아래를 줄여 적었다.

- 분류: 새 `journal/DbOutageErrors`(`kind`, `isOutage`), `journal/SqlErrors`(`describe`).
- 재생: `engine/EngineRecovery`(판정 없는 끝, 권한 확인 실패의 `NotLeaderException`, 스냅샷 경로의 폴백 생략은 획득 실패와 연결 끊김만).
- 응답: `api/order/OrderGateway`(`submitFailure`, V47 판정의 결과·실패 원인 반환과 한 번 더 부르기, 리더 자격 거부의 게이트 확인, 취소 재조회, 일괄 취소의 조기 종료, 'DB에 닿지 못함' 표식, 새 계수기 원천), `api/order/OrderController`(배치의 조기 종료, 재전송 안전 표식), `api/transfer/TransferController`(재전송 안전 표식), 새 `api/common/RetrySafeWrite`(표식), `api/common/ApiException`(렌더하지 않는 'DB에 닿지 못함' 표식), `api/common/ApiExceptionHandler`(DB 불통 처리기의 메서드·표식 판정, `AsyncRequestNotUsableException` 처리기), `api/auth/ApiAuthFilter`, `engine/EngineBridge`(`accepting(marketId)`), `config/ApiConfig`(계수기 여섯 조합 등록).
- 수명주기: `engine/PromotionCoordinator`(`participating`, `setParticipating`, `rearmIfFollower`), `engine/PartitionRuntime`(참여 위임, 강등 `detail`), `engine/LoopFatalHandler`(로그).
- (조건부) `ledger/SettlementService`(F1 한도, F2 재확인 참의 재시도).
- 하네스: `harness/scale/s3_common_axes.py`와 `test_s3_common_axes.py`, `harness/scale/roundtrip_probe.py`와 새 `test_roundtrip_probe.py`, S4b 실행기 `harness/evidence/pml-S4b-<YYYYMMDD-HHMM>/run-s4b-gate.ps1`(S4 재주행 실행기를 복사해 7-1대로 고친다).
- 시험 도우미: `engine/PartitionNode`(재생 결함 주입점, `recovery_runs` 기록자 배선, append 뒤 주입점), `engine/PartitionTermRaceTest`의 하니스(후보 표식 진입을 붙잡는 리스), `OrderGateway`의 V47 판정 이음매(패키지 전용, 시험이 실패를 주입한다). 운영 코드에는 결함 주입점을 두지 않는다.
- 웹과 인그레스, 마이그레이션, 경보 규칙은 바꾸지 않는다.

## 5. 안전 논증

S4b는 DB의 판정 함수, 원장 함수, 멱등 판정(V44 어드바이저리 락, V47 `resolve_failed_order_intake`), 스키마를 바꾸지 않는다. 논증은 앱 쪽 변경이 그 장치를 우회하거나 약화하지 않는다는 것이다.

자산 보존. 원장 게시는 여전히 `post_ledger_transaction`만 하고 자산별 합 0과 잔고 CHECK는 DB가 강제한다. 바뀌는 것은 실패의 응답 코드와 기록뿐이고 쓰는 SQL은 그대로다. P2의 DB 예약은 저널 행과 한 트랜잭션이라 응답이 `503`이든 `429`·`409`든 커밋 여부와 같이 움직인다. P1의 in-flight 예약은 V47이 판정하지 못하면(DB에 닿았든 닿지 못했든) 종전처럼 TTL로 지연 회수하고, V47이 이 요청의 명령이 저널에 없다고 판정하면(DISCARDED·KEPT·ABSENT·NOT_JOURNALED) 종전처럼 곧바로 푼다(6-2에서 P1 사례로 확인). 재구성과 재생은 원장을 쓰지 않는다. 판정 없는 재생을 불일치로 적지 않는 것은 C17의 기록만 바꾸고, 진짜 불일치(`ReplayMismatchException`)는 지금처럼 적힌다. 스냅샷 폴백의 범위를 좁힌 것은 전량 재생을 할지 말지만 정하고, 전량 재생도 같은 대조를 거친다.

주문 멱등. 결과를 모르는 접수는 키를 지우지 않는다(V47 규칙 그대로). `503`은 '재조회 후 판단'이라 클라이언트가 새 키로 다시 내지 않고 같은 키로 재조회하거나 재전송하며, 그 재전송은 저장된 해시 대조와 저널 확인으로 멱등 재생이나 새 접수 하나로 수렴한다. 판정이 된 경우의 `429`는 V47이 같은 키의 append와 같은 어드바이저리 락 아래에서 이 요청의 명령이 저널에 없고 공유 표시도 없음을 확인해 키를 지운 경우뿐이라, 같은 키 재전송은 새 접수 하나가 된다. V47을 한 번 더 부를 때는 키를 지우지 않으므로 이 `429`는 키를 지운 첫 호출의 응답을 받았을 때만 나간다. 첫 호출이 키를 지운 뒤 응답만 잃었으면 둘째 호출은 저널만 보고 `NOT_JOURNALED`라 `409`이며, 그 사이 같은 키로 들어와 키를 새로 넣은 요청의 키는 남는다. 커밋된 NEW는 두 호출 모두 JOURNALED로 본다. `409 order_in_progress`는 확정 미접수를 주장하지 않는 비종결 응답이다. 같은 키의 동시 요청 규칙(§2-5-b)은 경합 판별이 먼저라 바뀌지 않는다. 리더 자격 거부는 트랜잭션째 롤백됐거나 DB에 가지 않은 것이라, 게이트가 닫혀 다른 노드가 같은 키로 받아도, 게이트가 열려 같은 노드가 재전송을 받아도 이중 접수가 없다(키 단위 유일성은 어드바이저리 락과 저널 조회가 지킨다). 취소의 `409`는 취소가 저널에 들어갔는지 주장하지 않고, 같은 취소의 재전송은 종결 주문에서 `200` + 현재 상태다. 일괄 취소와 회원 폐쇄는 확인하지 못한 취소를 미완료로 세므로 폐쇄가 남은 주문을 완료로 덮지 않는다.

재전송. S4b가 새로 내는 `503`은 LB가 다른 노드로 다시 보내는 코드다(2-6). 그래서 새 `503`은 아무것도 쓰지 않은 요청(인증 단계, 안전 메서드)과, 다시 보내도 한 번의 효과로 모이는 요청(주문 경로의 `client_order_id`와 멱등 취소, 이체의 필수 `Idempotency-Key`)에만 둔다. 커밋 여부를 모르는 연결 끊김이 멱등 키 없는 쓰기에서 나면 S4와 같은 `500`이라 다시 보내지 않는다. 배치와 일괄 취소의 조기 종료는 시도하지 않은 원소를 비종결로 적을 뿐 아무것도 쓰지 않는다.

리더 권한 확인과 오래된 리더의 쓰기 차단. 모든 엔진 기원 쓰기는 종전처럼 DB에서 기록할 행의 마켓으로 리스를 도출해 holder·epoch·만료를 판정받고, 기간 결속도 그대로다. 리더 자격 거부의 응답을 게이트로 가르는 것은 응답 코드만 정한다. 거부 자체, 재확인, 재확인 뒤 강등은 그대로이고, 게이트를 연 채 `429`·`409`를 받은 클라이언트의 재전송도 다시 DB 판정을 받는다. 게이트 확인은 메모리 조회라 DB에 쓰지 않는다. 참여 수정은 후보 표식을 세우는 조건을 좁힐 뿐이다. 재생 분류는 무엇을 기록하느냐만 바꾸고 무엇을 쓰느냐는 바꾸지 않는다. 조건부 F1은 시도마다 기간 확인과 DB 판정을 다시 받고 한도를 넘기면 여전히 치명이며, F2는 DB가 리스 권위를 증명한 경우에만 다시 돌린다.

금액 계산. S4b는 금액 연산을 더하지 않는다. 새 코드는 예외 분류, 응답, 로그, 계수기다.

## 6. 시험

### 6-1. 원칙

새 시험은 먼저 실패하는 모양으로 쓴다. 고친 결함을 코드에 잠시 되살리는 변이로 새 단언이 실제로 실패하는지 확인하고, 변이는 되돌려 파일 해시가 고친 판과 같은지 대조한다(S4 13절과 같은 방법). DB가 필요한 시험은 Testcontainers 일회용 `postgres:17-alpine`에서 돈다. 스프링 예외 객체는 J의 번역과 같은 모양으로 만든다. 예를 들어 `PSQLException`(`PSQLState.CONNECTION_FAILURE`)을 스프링 번역기에 넣어 얻은 `DataAccessResourceFailureException`, SQLSTATE 없는 `SQLException("Connection is closed")`을 원인으로 하고 앱 예외를 초기화한 `TransactionSystemException`이다. V47 판정의 실패는 게이트웨이의 패키지 전용 판정 이음매가 주입한다. 함수 이름을 바꿔 V47을 끄는 기존 방법(`OrderGatewayReserveMappingTest` :201-216)은 42883 오류라 'DB에 닿았으나 판정 못 함'이 되므로 'DB에 닿지 못함' 사례에 쓰지 않는다. 잠금 대기 초과 사례 하나는 실제 어드바이저리 락으로 만든다.

### 6-2. 관문 시험

| 시험 | 장면 | 통과 기준 | 변이 확인 |
|---|---|---|---|
| `DbOutageErrorsTest` (DB 없음) | J1~J9 모양의 예외 표와 53200·54000·58030·P0001·23514·DB 밖 예외 | 획득 실패(J9의 두 예외), 연결 끊김(J6~J8의 `TransactionSystemException`, 08006·57P01, SQLSTATE 없는 자원 실패), 자원 부족(53200)으로 가르고 나머지는 아님이다. `isOutage`는 앞의 셋이다. 경합·리더 자격 거부·불통 분류 순서 | 연결 끊김 규칙에서 `TransactionSystemException`을 빼면 롤백 실패 사례가, 53을 연결 끊김에 넣으면 자원 부족 사례가 실패 |
| `RecoveryRunClassificationTest` (DB 없음) | 가짜 재생 서비스가 `CannotGetJdbcConnectionException`, 08006·57P01의 `DataAccessResourceFailureException`, 롤백 실패 `TransactionSystemException`, 53200, 57014, `NotLeaderException`, `ReplayMismatchException`, `IllegalStateException`을 던지고 기록자를 가로챈다 | 앞의 일곱은 기록 0이고 예외가 그대로 나온다. 불일치와 `IllegalStateException`은 `matched=false` 한 번 | 판정 없는 끝을 늘 거짓으로 하면(종전) 앞의 일곱이 실패, 늘 참으로 하면 뒤의 둘이 실패 |
| `RebuildOutageRetryIntegrationTest` | P2 한 노드(`PartitionNode`, 기록자 배선). 정산 주입으로 `WRITER_FATAL` 강등을 일으키고 재구성의 재생이 처음 두 번 `CannotGetJdbcConnectionException`으로 끝나게 한다. 따로 한 번은 `ReplayMismatchException`으로 끝나게 한다 | 연결 실패 사례: 재구성이 1초·2초 뒤 다시 해 `FOLLOWER`, 실패 타이머 2, `recovery_runs` 거짓 0·참 1 이상. 불일치 사례: 거짓 1, 다음 시도 성공 | 위와 같은 두 변이 |
| `SnapshotHookWiringIntegrationTest` (사례 추가) | (a) 스냅샷 원천이 `CannotGetJdbcConnectionException`을 던진다. (b) 스냅샷 적재가 `QueryTimeoutException`(57014)을 던진다. (c) 스냅샷 시작 재생이 57014로 끝난다. (d)(e) 적재는 성공하고 시작 재생의 첫 대조가 `CannotGetJdbcConnectionException`, 롤백 실패(`TransactionSystemException`, 앱 예외 08006)로 끝난다(14절 I2) | (a) 폴백 계수 0, 복구가 예외로 끝남, `recovery_runs` 0행. (b) 폴백 계수 1, 전량 재생으로 복구 성공, 불일치 행 0. (c) 폴백 계수 1, 불일치 행 0, 전량 재생의 성공 행 1. (d)(e) 복구가 그 예외로 끝나고 폴백 계수 0, 새 행 0. 기존 결함 스냅샷·판독 실패 사례는 종전 결과(폴백과 불일치 행) | 폴백 생략을 판정 없는 끝 전체로 넓히면 (b)(c)가, 적재의 생략을 빼면 (a)가, 시작 재생의 생략을 빼면 (d)가, 생략에서 연결 끊김을 빼면 (e)가 실패 |
| `OrderGatewayOutageMappingTest` 주문 생성 | 실제 DB의 키·V47, 가짜 브리지(`OrderGatewayReserveMappingTest` 틀). 브리지가 롤백 실패·08006·획득 실패를 던지고, V47은 정상이거나 판정 이음매가 획득 실패, 55P03, 첫 호출만 08006을 던진다 | V47 획득 실패는 `503` 'DB 연결 장애', 키 1행, `unresolved` 1, `connection\|503` 1. V47 55P03은 `409`, 키 1행, `connection\|409` 1, `connection\|503` 0. 첫 V47이 08006이면 둘째 호출은 키를 지우지 않아 NOT_JOURNALED라 `409`(`connection\|409`, `connection\|429` 0), 키 1행, V47 호출 2, `connection\|503` 0(14절 I1). DISCARDED `429`(`connection\|429`), KEPT `409`(`connection\|409`)이고 두 경우 모두 경합 계수기 0. 재전송 `409` | 게이트웨이 분기를 빼면 원래 예외. V47 다시 부르기를 빼면 08006 사례가 `503`. 둘째 호출도 키를 지우면 08006 사례가 `429`. 판정 실패를 모두 `503`으로 하면 55P03 사례가 실패 |
| `OrderGatewayOutageMappingTest` 리더 자격 거부 | 브리지가 P0001 'fencing 위반', 삽입 0행 `StaleLeaderException`, `NotLeaderException`을 던지고 `accepting`이 참이거나 거짓이다 | 게이트 닫힘은 셋 모두 `503` 페일오버, `fencing\|503`, 키는 V47이 정리. 게이트 열림은 DISCARDED면 `429`(`fencing\|429`), KEPT면 `409`(`fencing\|409`), V47 획득 실패면 `503` 'DB 연결 장애'(`connection\|503`). 재전송은 게이트 닫힘 `503`·열림 `409` | 게이트 확인을 빼면(늘 `503`) 게이트 열림 사례가 실패 |
| `OrderGatewayOutageMappingTest` 취소 | 취소 append가 롤백 실패·08006을 던지고, 대상 주문 재조회는 정상이거나 첫 번만 08006이거나 획득 실패다. 리더 자격 거부는 게이트 열림·닫힘 | 재조회 성공 `409 order_in_progress`(`connection\|409`). 첫 재조회 08006 뒤 성공도 `409`(재조회 2회). 재조회 획득 실패 `503`(`connection\|503`). 리더 자격 거부는 닫힘 `503`(`fencing\|503`), 열림 `429` bucket market(`fencing\|429`) | 재조회를 빼면(늘 `503`) 재조회 성공 사례가 실패 |
| `OrderGatewayOutageMappingTest` 일괄 취소 | 열린 주문 셋, 둘째 취소의 append가 롤백 실패. 재조회 성공과 재조회 획득 실패 두 장면. 셋째가 게이트 열림의 리더 자격 거부인 장면 | 재조회 성공이면 둘째 `failed[].reason=service_unavailable`, 셋째는 시도됨, 미완료 1. 재조회 실패면 둘째·셋째 `service_unavailable`, 브리지 호출 2, 미완료 2. 게이트 열림 거부는 `too_many_requests`로 미완료 | 조기 종료를 빼면 브리지 호출 3으로 실패. 단건의 `409`를 그대로 쓰면 미완료가 0으로 실패 |
| `OrderGatewayOutageMappingTest` P1 | P1 게이트웨이(in-flight 예약). V47 획득 실패, V47 55P03, DISCARDED | 앞의 둘은 `503`·`409`이고 in-flight 누계가 예약액 그대로(지연 회수). DISCARDED는 `429`이고 누계 0 | `outcomeUnknown` 설정을 빼면 앞의 둘의 누계가 0으로 실패 |
| `IntakeOutageRetryIntegrationTest` | P2 한 노드와 실제 게이트웨이(`SameKeyContentionIntegrationTest` 틀). (a) 실제 append가 커밋된 뒤 롤백 실패를 던지고 V47 이음매가 획득 실패를 던진다. (b) append 전에 같은 예외를 던지고 V47 이음매가 획득 실패를 던진다. (c) append 전에 롤백 실패를 던지고, 다른 세션이 같은 키의 `cmd-new:` 어드바이저리 락(V47과 같은 키 식)을 트랜잭션 안에서 쥔 채 게이트웨이 연결의 `lock_timeout`을 300ms로 둔다. (d) append 풀을 감싼 시험 도우미가 머리말 문장에 P0001 'fencing 위반'을 한 번 던진다. 리스는 유효하므로 실제 재확인(`renew_leader_lease`)이 참이다. 모두 이음매를 되돌리거나 락을 놓은 뒤 같은 키로 다시 보낸다. (e) 요청 A의 append 전에 롤백 실패를 던지고, V47 이음매가 첫 호출에서 실제 판정(키 삭제와 커밋)을 부른 뒤 같은 내용의 요청 B를 같은 키로 접수시키고 연결 끊김을 던진다(14절 I1) | (a) 첫 응답 `503`, 키 1행, NEW 1행, 예약 1행. 재시도는 재생(같은 주문 id)이거나, 물질화 전이면 `409 order_in_progress`이고 같은 마켓의 다음 명령 뒤 재시도는 재생이다. NEW·예약은 끝까지 1행. (b) 첫 응답 `503`, 키 1행, NEW 0. 재시도는 `201`, NEW 1행, 예약 1행, 잠긴 잔고가 예약액과 같다. (c) 첫 응답 `409 order_in_progress`, 키 1행, NEW 0, `connection\|503` 0. 재시도는 `201`, NEW 1행. (d) 강등 0, 게이트 열림, 리더 유지, 첫 응답 `429`(DISCARDED)와 `fencing\|429` 1, 재시도 `201`. (e) B는 `201`, A는 `409 order_in_progress`(`connection\|409` 1, `connection\|429` 0), 두 판정의 키 삭제 인자는 참·거짓, B의 키 1행이 남고 B의 재전송은 B의 주문 id의 재생, NEW·예약 1행 | 판정 실패 때 키를 지우면 (a) 재시도가 `409 duplicate_client_order_id`로 실패. 판정 실패를 모두 `503`으로 하면 (c)가, 게이트 확인을 빼면 (d)가, 둘째 판정도 키를 지우면 (e)가 실패 |
| `OutageResponseChainTest` (MockMvc) | 실제 `ApiAuthFilter`, `OrderController`, `OrderGateway`, `ApiExceptionHandler`를 운영 순서로 세운다(`ContentionDemandChainTest` 틀). (1) 롤백 실패와 V47 획득 실패. (2) V47이 키를 지움. (3) P0001, 게이트 열림, KEPT. (4) 배치 세 원소 가운데 둘째가 롤백 실패와 V47 획득 실패 | (1) HTTP `503`, `Retry-After: 1`, `service_unavailable`. (2) HTTP `429`, `detail.bucket=market`. (3) HTTP `409 order_in_progress`. 세 사례 모두 노드 층 수요가 되돌려지지 않는다(경합이 아니다). (4) HTTP `200`, 원소 `accepted`·`in_progress`(`service_unavailable`)·`in_progress`(`service_unavailable`), 브리지 호출 2 | 수요 제외 표식을 세우면 수요 단언이, 배치 조기 종료를 빼면 브리지 호출 3으로 실패 |
| `ApiAuthFilterOutageTest` | 인증 서비스가 08006 `DataAccessResourceFailureException`, 롤백 실패, 획득 실패, 57014, `IllegalArgumentException`을 던진다. POST와 GET 둘 다 | 앞의 넷은 메서드와 무관하게 `503`과 `Retry-After`, 마지막은 `500`. 주문 쓰기 수요는 센다 | 종전 catch로 되돌리면 08006·롤백 실패가 `500`으로 실패 |
| `ApiExceptionHandlerTest` (사례 추가) | GET의 08006·57P01·SQLSTATE 없는 `DataAccessResourceFailureException`과 `TransactionSystemException`. 재전송 안전 표식을 단 처리기(주문)의 POST와 표식 없는 처리기(게시판 글 작성)의 POST에서 `TransactionSystemException`·08006. 표식 없는 POST의 획득 실패. 54000. `AsyncRequestNotUsableException` | GET 넷은 `503`. 표식 있는 POST는 `503`. 표식 없는 POST의 `TransactionSystemException`·08006은 `500`이고 로그는 '처리되지 않은 예외'가 아닌 WARN 문구다. 표식 없는 POST의 획득 실패는 `503`(v2.17 그대로). 54000은 `500`. 응답 불가는 렌더하지 않음(렌더러 호출 0) | 메서드·표식 판정을 빼면 표식 없는 POST의 `TransactionSystemException`이 `503`이 되어 실패. 처리기를 빼면 GET 사례가 `500`으로 실패 |
| `RetrySafeWriteInventoryTest` (DB 없음) | 스프링 컨텍스트 없이 클래스패스 스캐너로 `@RestController`를 훑는다 | 재전송 안전 표식을 단 클래스가 정확히 `OrderController`와 `TransferController`다 | 표식을 `CommunityController`에 달면 실패 |
| `EngineBridgeMarketGateTest` (사례 추가) | 기간을 드는 게이트와 드지 않는 게이트 | `accepting(marketId)`는 그 마켓 파티션에 열린 기간이 있을 때만 참이고, 기간을 드지 않는 게이트는 `isAccepting` 값이다 | 늘 참을 돌려주면 닫힌 게이트 사례가 실패 |
| `PartitionTermRaceTest` (사례 둘 추가) | 리스의 후보 표식 세우기 진입을 래치로 붙잡는 하니스. 재구성 완료(`onDiverged`)가 표식을 세우는 중에 다른 스레드가 참여를 끈다. 같은 장면을 차단기 해제로 한 번 더 | 두 스레드가 끝난 뒤 참여 거짓, 후보 표식 거짓, 다음 `tryAcquire`가 거짓이고 승격이 없다 | 참여를 락 밖에서 읽는 종전 코드로 되돌리면 표식이 참으로 남아 실패 |
| `PartitionsEndpointTest` (사례 추가) | `UncategorizedSQLException`(55P03)과 롤백 실패(앱 예외 08006) 원인의 `WRITER_FATAL` 강등 | `demotions[].detail`에 `sqlstate=55P03`, `sqlstate=08006(앱 예외)` | 종전 `detail`로 되돌리면 실패 |

기존 관문과 안전 시험은 통과해야 한다. `SameKeyContentionIntegrationTest`, `ContentionDemandChainTest`, `ContentionStallExclusionTest`, `IntakeContentionMetricsTest`, `TermBoundWriteTest`, `PartitionFatalIsolationTest`, `PromotionRecoveryIntegrationTest`(`replayFailureRecordsMismatchAndRethrows`가 불일치 기록 쪽 대조군이다), `FollowerHandoverRaceTest`, `OrderIntakeFailureResolutionTest`(V47 판정 계약)다.

### 6-3. 고칠 기존 시험

- `OrderGatewayReserveMappingTest.nonContentionFailuresKeepTheirS3Responses`(:218-234)는 SQLSTATE 없는 `DataAccessResourceFailureException("connection refused")`이 그대로 올라간다고 단언한다. S4b에서는 연결 끊김이고 V47이 이 요청의 키를 지우므로(DISCARDED) `429`(bucket market)와 `connection|429`다. 이름과 주석을 '경합도 DB 불통도 리더 자격 거부도 아닌 예외의 응답은 그대로'로 고치고, 그대로 올라가는 대조 예외는 54000 `DataAccessResourceFailureException`과 `IllegalArgumentException`으로 바꾼다. DB 불통 사례는 `OrderGatewayOutageMappingTest`로 옮긴다. 같은 파일의 경합 뒤 V47 판정 실패 사례(:201-216, 함수 이름 바꾸기)는 'DB에 닿았으나 판정 못 함'이라 종전처럼 `409`다. 바꾸지 않는다.
- `ApiExceptionHandlerTest`: 비접수 55P03의 `500` 단언(`appendLockTimeoutStaysServerErrorUntilIngressDecision`)은 그대로다. 처리기가 `HandlerMethod`를 받으므로 기존 사례의 호출에 처리기 인자를 더한다.
- `OrderGatewayReserveMappingTest.FakeBridge`: 게이트 상태를 고를 수 있게 `accepting`을 덮는다. 기본값은 참(종전 `AcceptGate.ALWAYS_OPEN`과 같다)이다.
- `PartitionTermRaceTest`의 하니스는 리스를 생성자에서 직접 만든다. 후보 표식 진입을 붙잡는 하위 클래스를 고를 수 있게 한다. 기존 네 사례는 바뀌지 않는다.
- `PartitionNode`: 재생 결함 주입점(재생 서비스 하위 클래스의 `verifyToMarket`·`materializedThroughByMarket` 앞), `recovery_runs` 기록자 배선, append 뒤 주입점, 머리말 문장에 한 번 SQL 오류를 던지는 append 풀 감싸개를 더한다. 운영 코드에는 주입점을 두지 않는다.
- `harness/scale/test_s3_common_axes.py`: 4-7의 사례(프로메테우스 `increase()`는 0인데 `max_over_time`은 1, LB 창 안 `500` 1줄, 창 밖 줄 무시, 바이트 수 500인 `200` 줄 무시, 노드 누계의 `UNKNOWN` `POST` `500`, 읽기 실패는 거짓, 창 안 LB 줄 0이나 시계열 0은 거짓, 모두 0이면 참)와 `recovery_runs` 축.
- 새 `harness/scale/test_roundtrip_probe.py`: k6 저널 행 합을 주행 초로 나누는지, 봇 행을 세지 않는지, 창이 없으면 값이 없는지.

### 6-4. 명령

```powershell
cd C:\dev\exchange-pml\server
.\gradlew.bat :app:test --tests "*DbOutageErrorsTest" --tests "*RecoveryRunClassificationTest" --tests "*RebuildOutageRetryIntegrationTest" --tests "*SnapshotHookWiringIntegrationTest" --tests "*PromotionRecoveryIntegrationTest" --tests "*OrderGatewayOutageMappingTest" --tests "*OrderGatewayReserveMappingTest" --tests "*OrderIntakeFailureResolutionTest" --tests "*IntakeOutageRetryIntegrationTest" --tests "*OutageResponseChainTest" --tests "*ApiAuthFilterOutageTest" --tests "*ApiExceptionHandlerTest" --tests "*RetrySafeWriteInventoryTest" --tests "*EngineBridgeMarketGateTest" --tests "*PartitionTermRaceTest" --tests "*PartitionsEndpointTest" --tests "*SameKeyContentionIntegrationTest" --tests "*ContentionDemandChainTest" --tests "*IntakeContentionMetricsTest"
.\gradlew.bat :app:test --rerun --tests "*PartitionTermRaceTest" --tests "*RebuildOutageRetryIntegrationTest" --tests "*IntakeOutageRetryIntegrationTest" --tests "*OutageResponseChainTest"
.\gradlew.bat build --console=plain --continue
cd C:\dev\exchange-pml
python -B -m unittest discover -s harness/scale -p "test_*.py"
pwsh -NoProfile -File scripts/check-docs.ps1
pwsh -NoProfile -File scripts/check-doclinks.ps1
pwsh -NoProfile -File scripts/check-adr-current.ps1
pwsh -NoProfile -File scripts/check-style.ps1
```

둘째 줄(경합·시각 시험의 `--rerun`)은 세 번 돌린다. 전체 빌드의 결과 XML과 로그에서 `localhost:5432`·`localhost:55432` JDBC 주소가 0건인지 본다(Testcontainers만 썼다는 증거). 웹, 경보 규칙, 인그레스를 바꾸지 않으므로 웹 검사와 promtool은 돌리지 않는다. 계약이나 설계 문서를 바꾸는 단계마다 0099를 대조하고 `sources-sha256`을 갱신하므로 `check-adr-current`도 통과해야 한다(8절).

## 7. 폐기용 스택 실험

### 7-0. 안전 수칙

S4 설계 7-0을 그대로 따른다. 실험 스택은 compose 프로젝트 `exchange-pml`, 앱 이미지 태그 `exchange-chaos-app:pml`로만 띄우고 `exchange-chaos-app:local`을 덮어쓰지 않는다. 보존 볼륨 `exchange-chaos_chaos-pgdata`와 백업 볼륨 `failstop-keep-*`는 어떤 명령으로도 마운트·삭제·수정하지 않는다. 프로젝트 `exchange-chaos`에는 `down -v`, `volume rm`, `up`을 하지 않는다. `down -v`는 `-p exchange-pml`이 붙은 명령으로만 한다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/k6_keys.json`)은 경로로만 넘기고 열지 않는다. 시작 전에 `chaos-*` 컨테이너가 없어야 하고, 있으면 BLOCKED로 적고 기다린다. 모든 단계의 앞뒤로 보존 볼륨 두 개의 생성 시각이 같은지 본다.

S4b에 더하는 수칙은 둘이다. 새 이미지를 굽기 전에 지금 `:pml`이 가리키는 S4 재주행 이미지(`sha256:5b8c279f…`)에 참조 태그 `exchange-chaos-app:s4r1ref`가 붙어 있는지 보고, 새 이미지에는 참조 태그 `exchange-chaos-app:s4bref`를 붙인다(containerd 저장소에서는 이름을 모두 잃은 이미지가 사라진다, S4 설계 7-0). F1·F2로 다시 구우면 `s4b2ref`를 붙인다. 태그를 잃은 이미지를 지우는 명령은 쓰지 않는다. 실험 동안 이 세션에서 Gradle 빌드나 Testcontainers 시험을 함께 돌리지 않는다.

### 7-1. 준비와 실행기

실행기는 S4 재주행의 `run-s4-gate.ps1`을 `harness/evidence/pml-S4b-<YYYYMMDD-HHMM>/run-s4b-gate.ps1`로 복사해 고친다. 셸 변수 둘, `lib.ps1`의 프로젝트, compose 해석값이 모두 `exchange-pml`일 때만 시작하는 가드는 그대로다. 고치는 곳은 일곱이다.

1. Prep: 참조 태그 확인(7-0), `images.json`에 새 이미지 id·HEAD·작업 트리 diff 해시·JAR 해시·Flyway 최대 판(53)을 남긴다.
2. 모든 단계: 실험 앞뒤로 호스트 부하(`Get-HostLoad`, 전체 CPU 표본 다섯과 도커·앱·부하 도구 밖의 바쁜 프로세스 수)를 결과에 남긴다(7-3).
3. X1: `leader_500_0`을 `python harness/scale/s3_common_axes.py orders500 --window-from <스택 기동 시각> --out <json>`의 결과로 바꾸고, 노드 지표 목록에 `exchange_order_intake_unavailable_total`(여섯 조합)을 더한다.
4. Chaos: 반복마다 `-SubDir`을 주고 컨테이너 로그를 `%TEMP%\pml-s4b-logs\<하위 폴더>`에 남긴다(오케스트레이터의 `-LocalLogDir`). 끝난 뒤 저장한 로그에서 문구 수('재생이 판정 없이 끝났다', '파티션 재구성 실패', '처리되지 않은 예외', '인증 처리 실패로 500', '클라이언트 연결이 끊겨')를 세어 결과에 붙인다.
5. X8: E1을 하위 폴더별로 돌린다. 노드 지표 목록에 `exchange_settlement_transient_retries_by_state_total`, `exchange_settlement_transient_exhausted_total`, `exchange_order_intake_unavailable_total`을 더해 실행 전후 차이를 남기고, 파티션 스냅샷의 강등 `detail`과 4-6의 로그 문구 수, `orders500` 결과를 남긴다. 컨테이너 로그 저장은 S4 재주행 판 그대로다.
6. X7: S3·S4b 칸을 번갈아 돌리는 절차는 같고, 판정 보조는 '접수/s(k6 저널 행)'을 쓴다.
7. B1(새 단계 `-Phase B1`): 새 스택(P2)에 봇 300 OPS를 세우고 2분 예열한 뒤, 리더의 pg 프록시(`Get-LeaderContainer`로 고른 `pg-api1`이나 `pg-api2`)에 toxiproxy `reset_peer`(`timeout` 0)를 걸고 500ms 뒤 지운다. 지우기는 `finally`의 `Remove-AllToxics`가 보장한다. `reset_peer`는 그 프록시를 지나는 연결을 끊을 뿐 DB는 살려 두므로, DB가 흔들린 직후 쓰던 연결만 끊긴 장면(4-4의 셋째)을 만든다. 60초 간격으로 세 번 하고, 매번 앞뒤로 리스 holder·epoch, 두 노드의 `exchange_order_intake_unavailable_total`·`exchange_order_intake_unresolved_total`·`http_server_requests_seconds_count{status="503"}`(uri·메서드별), LB 접근 로그의 창 안 `502`·`503`·`500` 줄 수와 첫·끝 시각, LB 오류 로그의 `no live upstreams` 줄 수를 남긴다.

```powershell
$ev = 'C:\dev\exchange-pml\harness\evidence\pml-S4b-<YYYYMMDD-HHMM>'
$r = "$ev\run-s4b-gate.ps1"
pwsh -NoProfile -File $r -Phase Prep
pwsh -NoProfile -File $r -Phase X1 -WithX6
pwsh -NoProfile -File $r -Phase B1
foreach ($n in 1..5) { pwsh -NoProfile -File $r -Phase Chaos -Experiments E2 -SubDir "E2-r$n" }
foreach ($n in 1..5) { pwsh -NoProfile -File $r -Phase X8 -X8Experiments E1 -SubDir "E1-r$n" }
pwsh -NoProfile -File $r -Phase Chaos -Experiments E4,E5,E6,E7,COLD -SubDir regress
pwsh -NoProfile -File $r -Phase Final
```

Chaos 단계는 `harness/scripts/adr0022-stage5-chaos.ps1 -Image exchange-chaos-app:pml -Experiments <E> -Protocol 2 -PartitionAxes -EvidenceDir <하위 폴더> -LocalLogDir <로그 폴더>`를 부르고, 프로필은 `dev,chaos-monkey,prod-timeouts`다. 실험마다 스택을 새로 세우고 P2로 전환한다(S3 설계 7-2의 `protocol_latch.py transition --target 2`). 반복 회차의 규칙은 두 실험이 다르다. E2는 연속 세 번 PASS이고 그 가운데 원인 A 경로(재구성 재시도가 '재생이 판정 없이 끝났다'를 남김)를 한 번 이상 탔으면 남은 회차를 돌지 않는다. 타지 않았으면 넷째·다섯째를 돈다(7-2 Y2). E1은 재현을 노려 다섯 번을 모두 돈다. 앞의 세 번이 PASS여도 줄이지 않는다(4-6, 7-2 Y3).

### 7-2. 실험 목록과 판정

모든 실험의 공통 판정은 S4 설계 7-2의 공통 판정(사후 권위 run과 드레인 뒤 `summary.sql`의 CRITICAL·HIGH가 모두 PASS, 정산 23514 0, 잠금 계획 이탈 0, 접수 40P01 0, 교착 증가 0, 드레인 뒤 정산 전 예약 0)과 S4 축(창 밖 접수 경합 상한, 창 밖 파티션 경보 0, 실험 끝 리더 노드의 강등 0)에 S4b 축 셋을 더한 것이다. 보강한 `orders_500_0`(4-7), `recovery_runs_mismatch_0`과 그 양성 축, 두 api 노드 로그의 '처리되지 않은 예외'·'인증 처리 실패로 500' 0줄이다. 불변식 위반 0과 구 epoch 쓰기 증분 0은 모든 실험의 판정이다.

- Y1 X1 재확인(P2 봇 300 OPS 10분, 앱 역할 로그인). 판정: S4 X1의 축 21개(보강한 `orders_500_0`으로 바꾼 것 포함), LB `no live upstreams`·`502` 0, `exchange_order_intake_unavailable_total` 증가 0(정상 운영에서 DB 불통 응답이 없다), `recovery_runs_mismatch_0`. 같은 스택에서 X6 a~d를 이어 돌려 S4 설계 7-2 X6의 판정(확정 미접수 `429`, 같은 키 동시 요청 `409`, `503` 0, 장기 잠금에서 두 층 강등 0)을 다시 본다. 게이트웨이의 실패 분기를 고쳤기 때문이다.
- Y2 E2(DB 절단), 연속 세 번 PASS가 관문이다. 판정: 스크립트 규칙(RTO < 30초, epoch 단조 증가와 holder 교체, 구 epoch 쓰기 증분 0, rpo_records 0, 사후 CRITICAL 0, 승격 안정성, 회복 가능 반납 1 이하), 공통 판정, 파티션 축. 기록: 옛 리더의 `exchange_order_intake_unavailable_total`(여섯 조합), `exchange_order_intake_unresolved_total`, `exchange_order_intake_commit_recovered_total`, 재구성 실패 수(`exchange_partition_rebuild_seconds_count{outcome="failure"}`)와 '재생이 판정 없이 끝났다' 줄 수, 창 안 LB `502`·`503`·`504` 줄 수, RTO. 재구성 경로(원인 A)와 복귀 순간의 접수 경로(원인 B)를 실제로 탔는지는 이 기록으로 적는다. 세 번 안에 원인 A 경로를 한 번도 타지 않으면 넷째·다섯째 회차를 돌고(그것도 PASS여야 연속이 유지된다), 그래도 타지 않으면 원인 A의 스택 증거가 없다고 적고 시험(6-2)으로만 확인했다고 한다. FAIL이 나면 연속 수를 0으로 되돌리고 원인을 본다.
- Y3 E1(PG 지연), 세 번 PASS가 관문이다. 새 스택마다 P2로 전환한 뒤 `harness/scripts/e1-pg-latency.ps1`을 직접 부른다. 판정: 스크립트 1차 판정(무결성 0, epoch 불변, 자발적 강등 증가 0, 주입 중 status·readiness 유지, 체결 정지 0, 해제 뒤 회복, 리스 표본 양성 축), 두 층 강등 0, 리더의 획득 `lock_timeout` 계수 0, 보강한 `orders_500_0`, 로그의 '처리되지 않은 예외' 0줄. 기록: 4-6의 계수와 로그 문구, 강등 `detail`, 주입 창 접수 경합 수. 세 번이 PASS여도 재현을 노려 두 번을 더 돌린다(4-6). 재현되면 원인을 가르고 판단표대로 고친 뒤(F1·F2는 계약을 먼저 고친다), 이미지를 다시 굽고 E1을 새로 세 번 PASS까지 돌리며, 정산 경로가 바뀌므로 Y1(X1만), Y2 E2 한 번, E7 한 번을 다시 돈다.
- Y4 S4 관문 재확인. 최종 이미지가 바뀌었으므로 E4(합류 단계 포함), E5, E6, E7, COLD를 한 번씩 돈다. 판정은 S4 설계 7-2의 X2~X5와 같고 공통 판정의 S4b 축을 더한다. E7에서는 재개된 옛 리더의 로그에 '처리되지 않은 예외'가 0줄이고, 응답을 쓸 수 없던 요청은 WARN('클라이언트 연결이 끊겨')으로 센다(기록).
- Y5 X7 처리량(선택, 판정 아님). S3·S4b 이미지 칸을 번갈아 돌고 '접수/s(k6 저널 행)'으로 잡음과 손실을 계산한다. 호스트가 조용하지 않으면 NOT RUN으로 두고 S7 전에 한다.
- Y6 B1 DB 흔들림(기록 실험, 관문 아님). 리더가 DB에 닿는 채로 쓰던 연결만 끊겼을 때 S4b의 새 `503`이 리더를 쓰기 업스트림에서 얼마나 빼는지 잰다(4-4, 11절 R7). 7-1의 7대로 한 스택에서 세 번 흔든다. 판정(안전 축): 불변식 위반 0, 구 epoch 쓰기 증분 0, 보강한 `orders_500_0`, 두 노드 로그의 '처리되지 않은 예외' 0줄. 흔들기마다 리스 holder·epoch가 그대로여야 이 실험의 전제(리더 유지)가 선다. 바뀌었으면 그 회차는 N/A로 적고 끊는 시간을 줄여 다시 돈다. 기록: 리더의 `503` 수를 원천별(인증 필터는 uri `UNKNOWN`, 컨트롤러는 uri별, 접수·취소는 `exchange_order_intake_unavailable_total{cause,response}`)로, LB 창 안 `502`·`503` 줄 수와 이어진 시간, `no live upstreams` 수, V47 판정 실패 수. 이 값은 사용자 확인 1·3(10절)의 근거로 옮긴다. 판정 기준으로 쓰지 않는 것은 이 비용의 크기를 처음 재는 것이라 기준값을 정할 근거가 없기 때문이다.
- E3은 S4b가 그 경로를 바꾸지 않고 S4 재주행에서 SKIP이 유효 판정이었으므로 돌리지 않는다(N/A 근거로 적는다).

### 7-3. 호스트 잡음 규칙

실험마다 앞뒤로 호스트 부하를 남긴다. 실험이 시간 축(RTO, E1의 p99 회복·`last_renew_age`·주입 p99 배수)에서만 FAIL이고 그 실험의 표본에서 도커·앱·부하 도구 밖의 바쁜 프로세스가 하나 이상이거나 전체 CPU 평균이 80% 이상이면, 같은 이미지로 한 번 다시 돌리고 두 결과를 모두 적는다. 무결성, 구 epoch 쓰기, `orders_500_0`, C17, 강등 축은 잡음을 이유로 면제하지 않는다. E2·E1의 연속·횟수 판정에서 잡음으로 다시 돈 회차는 원래 회차를 대신한다고 적는다.

### 7-4. 판정 기록

증거는 `harness/evidence/pml-S4b-<YYYYMMDD-HHMM>/`에 둔다. `README.md`(조건, 명령, 판정 표, 한계), `commands.log`, `images.json`, 볼륨 확인, 실험별 폴더(전환 JSON, 판정 JSON, 공통·파티션 축 JSON, `orders500` JSON, 컨테이너 로그 해시, 계수 전후 값, 호스트 부하)다. 상태 어휘는 PASS·FAIL·NOT RUN·BLOCKED·N/A이고 실행하지 않은 실험을 통과로 적지 않는다. 측정 표는 `docs/measurements/`의 그날 파일에 옮긴다.

## 8. 계약 문서 반영

코드보다 먼저 고친 계약은 넷이다. 11절의 검토 대응으로 같은 날 다시 고쳤고 판 번호는 그대로 v2.56이다.

- [DB 설계](../contracts/백엔드_DB_설계.md) v2.56: §5.4-c 5(재구성 재생의 DB 접근 실패는 불일치 행이 아니다, 폴백 생략은 획득 실패와 연결 끊김만, 강등 기록의 SQLSTATE), §5.4-c 6(접수 경로에서 재확인이 참인 fencing 거부의 응답과 재시도 주체), §5.4-c 9(참여 여부와 후보 표식의 원자성), §5.5(STEP1 폴백의 예외 범위, 판정 없이 끝난 재생의 기록 규칙), 패치 노트.
- [API 설계](../contracts/백엔드_API_설계.md) v2.56: §2-3 `429`·`500`·`503` 행, §2-4 V47 뒤 응답(판정 결과 셋, 한 번 더 부르기, 리더 자격 거부의 게이트), §2-5-c 신설(원칙, 주문 생성, 리더 자격 거부, 취소 재조회, 원소별 처리와 조기 종료, 인증과 컨트롤러의 재전송 안전 구분, 응답을 쓸 수 없는 요청, 인그레스 영향, 관측), §4-B-3, §4-B-6, §6-C(`/actuator/partitions`의 강등 `detail`과 참여 끄기의 원자성), §6-E(미완료 사유와 조기 종료), 패치 노트.
- [하네스 검증설계](../contracts/하네스_검증설계.md): 계약 핀 v2.56, S4b 패치 노트(C17 기록 규칙과 폴백 범위, E2 기대 응답, `orders_500_0` 세 원천, `recovery_runs` 축, E1 실행기의 로그·계수 저장, E2·E1 반복 규칙과 호스트 잡음, X7 칸 값, B1 기록 실험), §3 LB 배선 항목과 E2 행, §4-1 C17 주석.
- [하네스 CI/CD](../contracts/하네스_CICD_IaC.md) §5-2: `exchange.order.intake.unavailable{cause,response}` 여섯 조합.

[0099](../adr/0099-current-architecture.md)는 이 단계에서 대조했다(11절 R4). 쓰기 구조 문단과 ADR 표에 S4b가 설계와 계약만 고친 단계라는 상태를 적고 `sources-sha256`을 갱신했다. 구현과 문서 단계에서 계약이나 설계를 다시 바꾸면 그때마다 0099를 대조하고 해시를 갱신한다. ADR 0034(머리말 「S4 진행」, 결정한 값 11의 DB 연결 장애 응답), [단계 계획](마켓분할_리더_단계계획.md) S4 절, 로드맵, 코드 지도, 측정 파일, S4 설계 13-3의 이 문서 연결은 코드 결과가 나온 뒤 문서 단계에서 고친다. V53 파일이 '커밋 전'이라는 상태 문장(DB 설계 §3 표의 V53 행과 §3.8, 하네스 검증설계의 계약 핀)도 그때 커밋 `48629d5d` 기준으로 고친다. 이 단계의 계약 수정에는 넣지 않았다. 조건부 F1은 재현 뒤 DB 설계 §5.4 v2.51 정산 sink 오류 분류와 ADR 결정 11을 먼저 고치고(13-3에서 그렇게 적용했다), F2는 §5.4 v2.23의 '호출자가 재시도한다'를 정산 경로에서 어떻게 지키는지 적는다.

## 9. 남은 위험

- DB가 살아 있는데 쓰던 연결만 끊긴 경우의 `503`. 인증 필터와 컨트롤러의 조회(주문 접수 전 조회 포함) 한 건이 리더를 쓰기 업스트림에서 빼 약 1.3~2.1초 동안 모든 쓰기가 `502`가 된다(4-4). 종전 `500`은 그 한 건만 실패했다. V47 판정과 취소 재조회는 한 번 더 DB에 닿아 보지만 끊긴 연결이 두 번 이어지면 `503`이 날 수 있다. DB가 흔들린 직후에 한정되는 비용이지만 S4 설계 4-13의 전제('S4는 리더의 `503`을 새로 만들지 않는다')가 이 범위에서 달라진다. 크기는 Y6 B1에서 재고, S5의 파티션별 업스트림에서 다시 본다.
- DB 전체 장애 뒤의 복귀 지연. 리더가 DB에 닿지 못하는 동안 `503`으로 빠져 있으면 DB 복귀 뒤 최대 약 2.4초 `502`가 남는다(D2, D4). 하네스 E2는 이 모양을 재지 않는다.
- S4 이전부터 있는 재전송 한계. 획득 실패·트랜잭션 시작 실패·`TransientDataAccessException`은 v2.17부터 모든 메서드에서 `503`이다. 그 트랜잭션은 커밋 전에 끝났지만, 한 요청 안에서 트랜잭션을 둘 이상 커밋하는 멱등 키 없는 쓰기는 앞 트랜잭션이 이미 커밋됐을 수 있어 LB가 다른 노드로 다시 보내면 앞부분이 두 번 실행될 수 있다. S4b는 이 범위를 넓히지 않았고 바꾸지도 않는다(4-3). 해당 처리기를 골라내는 일은 S5 인그레스 재검토로 넘긴다.
- 게이트를 연 채 재확인 왕복이 실패한 노드. 강등하지 않았으므로 LB에 남아 `429`·`409`를 내고, 다음 갱신 틱이 판단할 때까지(최대 리스 TTL) 이어질 수 있다. 쓰기는 DB fencing이 계속 막으므로 가용성만의 문제다. 그 노드가 DB에 닿지 못하면 V47이나 재조회가 'DB에 닿지 못함'을 확인해 `503`이 된다.
- V47 판정과 취소 재조회를 한 번 더 부르면 DB에 닿지 못하는 경우의 응답이 기본 풀 연결 대기(3초)만큼 늦어질 수 있다. 첫 호출이 끊긴 연결로 곧바로 실패하고 둘째 호출이 연결 대기로 끝나는 경우다. append 획득 대기(2초)와 합쳐도 nginx `proxy_read_timeout` 10초 안이다. 획득 실패는 다시 부르지 않는다.
- 취소의 `409`. 재조회가 종결 상태를 읽어도 `409`로 답하므로 클라이언트가 이미 끝난 취소를 한 번 더 확인해야 한다. 커밋을 가린 롤백 실패로 저널에만 들어간 취소는 같은 마켓의 다음 명령이 갭을 드러낼 때 적용된다(S4와 같다).
- 배치와 일괄 취소의 조기 종료는 시도하지 않은 원소를 비종결로 돌려준다. 클라이언트가 그 원소를 다시 보내야 한다. 조기 종료는 'DB에 닿지 못함'이 확인된 뒤에만 하므로, DB가 살아 있는데 끊긴 연결이 두 번 이어진 경우가 아니면 헛되이 멈추지 않는다.
- E1 재현 실패. 다섯 번 안에 재현되지 않으면 첫 FAIL의 원인은 추론(정산 55P03 재시도 소진)으로 남고, 뒤 단계에서 같은 FAIL이 날 수 있다. 관측된 빈도는 네 번 가운데 한 번(S4 첫 주행 1회 PASS, 재주행 3회 가운데 1회 FAIL)이다.
- E2에서 원인 A 경로를 타지 않을 수 있다. 재구성 재시도가 DB 복귀 순간에 걸치는 것은 절단 중 라이터가 치명으로 끝났는지에 달렸다. 타지 않으면 원인 A는 시험으로만 확인한 것이다(7-2 Y2).
- 판정이 된 연결 장애와 게이트를 연 리더 자격 거부의 `429`·`409`. 봇은 `201`·`200`이 아니면 그 주문을 다시 보내지 않으므로 그 주문은 버려진다(S4 설계 2-10). k6 `order_flow.js`는 주문 생성의 `409`를 정상 구간 실패로 세고 `bucket=market`인 `429`를 `market_contention`으로 세지만 이 응답은 장애 창에서만 난다. 연결 장애의 `429`가 경합과 같은 `bucket=market`이라 클라이언트는 원인을 가르지 못하고 '마켓 혼잡'으로 보일 수 있다. 원인은 서버의 계수기 `cause`와 로그로만 보인다.
- 정산의 fencing 재확인 계약 어긋남. DB 설계 §5.4 v2.23·§5.4-c 6은 재확인이 참이면 호출자가 다시 돈다고 적었지만 정산 경로는 올린다(2-4). 접수 경로는 이번에 클라이언트의 같은 키 재전송으로 정리했고(4-3), 정산은 E1에서 드러나지 않으면 이 단계에서 고치지 않는다. 재확인이 참인 경우는 DB 시계 후진과 자기 재승격뿐이고 기간 결속이 뒤의 것을 막으므로 드물다.
- 보강한 `orders_500_0`이 S4 판정이 놓치던 다른 `500`을 드러낼 수 있다. 그것이 목적이다. 새로 드러난 `500`은 원인을 보고 고치며 판정 기준을 낮추지 않는다.
- 응답을 쓸 수 없는 요청을 WARN으로 낮추면 클라이언트 끊김이 ERROR 경보에서 빠진다. 재전송이 안전하지 않은 쓰기의 연결 끊김도 WARN이다. 개수는 로그 문구와 `http_server_requests`의 `500`으로 센다.
- main 병합의 Flyway 순서는 S4 설계 9절 그대로다.

## 10. 사용자 확인이 필요한 것

모두 2026-09-25 이 설계를 맡은 AI가 정했다. 같은 날 검토 대응(11절)으로 1~4를 고쳤다.

1. 주문 접수의 DB 연결 장애는 V47 판정마저 DB에 닿지 못했을 때만 `503 service_unavailable` + `Retry-After: 1` + 'DB 연결 장애. 재조회 후 판단 필요'로 내고, 닿았는데 판정하지 못했으면 `409 order_in_progress`로 내는 것. 판정이 연결 끊김으로 끝나면 키를 지우지 않는 판정으로 한 번 더 부르고, 그 판정이 저널에 없다고 하면 `409`로 내는 것(14절 I1). 취소도 대상 주문 재조회로 같은 기준을 쓰는 것. 인증 필터의 DB 연결 장애 `503`(4-3, API 설계 §2-5-c). 작업 지시의 AI 선택을 구체화한 것이다. 이 `503`들이 인그레스에서 리더를 빼는 비용(9절 첫 두 항목, Y6 B1의 측정값)을 받아들이는지도 함께 확인한다.
2. 판정이 된 연결 장애와 게이트를 연 채 받은 리더 자격 거부에 마켓 접수 경합과 같은 규칙(DISCARDED면 `429`, 그 밖과 재전송은 `409 order_in_progress`, 취소는 `429`)을 쓰는 것(4-3). 규칙 적용은 작업 지시의 AI 선택이고, 연결 장애의 `429`에도 `bucket=market`을 쓰고 경합 계수기가 아닌 새 계수기로 세는 것과 게이트 상태로 페일오버 `503`을 가르는 것은 이 설계의 선택이다.
3. 컨트롤러 계층의 DB 연결 장애를 안전 메서드와 재전송 안전 처리기(주문, 이체)만 `503`으로 내고, 그 밖의 쓰기의 연결 끊김은 S4와 같은 `500`으로 두는 것(4-3). 표식을 받을 처리기 목록도 함께 확인한다.
4. 배치 주문과 일괄 취소(회원 폐쇄 포함)가 'DB에 닿지 못함' 뒤 남은 원소를 시도하지 않는 것(4-3).
5. 판정 없이 끝난 재생을 `recovery_runs`에 적지 않는 것과 스냅샷 폴백 생략을 획득 실패와 연결 끊김으로 한정하는 것(4-2). 미완료 행을 따로 두는 안을 고르지 않았다.
6. 응답을 쓸 수 없는 요청을 서버 오류로 렌더하지 않는 것(4-3).
7. `orders_500_0`의 세 원천과 판정 범위(uri `UNKNOWN`, DELETE 포함), `recovery_runs` 축, X7 칸 값의 저널 행 기준, E2·E1 반복 규칙과 호스트 잡음 규칙, B1 기록 실험(4-7, 7-1, 7-2, 7-3).
8. (조건부) E1 재현 시의 정산 재시도 한도 기한 15초·시도 8회(F1)와 재확인 참인 fencing 거부의 재시도(F2)(4-6). F1은 재현돼 적용했고 F2는 원인이 아니어서 적용하지 않았다(13-2·13-3).

## 11. 검토 대응 (2026-09-25)

같은 날 이 설계와 계약에 대한 검토 지적 10건을 받았다. 코드, 계약, 인그레스 설정, 폐기용 컨테이너 확인 결과(3절)로 대조했다. 모두 실제 문제로 판단해 설계와 계약을 고쳤고 통째로 반박한 지적은 없다. 검토가 낸 선택지와 다르게 고친 곳(R1·R5의 응답 코드, R2의 상한안, R3·R8의 전달 방식)은 이유를 적었다. R1과 R5, R3과 R8은 같은 결함을 두 문서에서 짚은 것이라 함께 답한다. 대조 중에 찾은 것 둘(리더 자격 거부의 다른 두 모양, 원소별 처리의 지연과 결과 유실)도 함께 고쳤다. 번호 R1~R10은 받은 순서이고 심각도는 검토가 매긴 값이다.

### R1·R5. 컨트롤러의 `503`이 커밋됐을 수 있는 멱등 키 없는 쓰기를 다른 노드에서 다시 실행하게 한다 (medium API 설계, high 설계)

지적대로다. 두 인그레스 모두 쓰기 경로에 `proxy_next_upstream ... http_503 non_idempotent`를 걸어 `503`을 받은 POST를 다음 노드로 다시 보낸다(2-6, N2의 D1·D3). 첫 판은 컨트롤러 계층의 연결 끊김(롤백 실패 `TransactionSystemException`, 문장 중 08006·57P01)을 메서드와 무관하게 `503`으로 바꿨는데, 이 예외는 커밋이 됐을 수 있다(J8). 게시판 글 작성의 멱등은 노드 메모리라 다른 노드에서 보이지 않고(`CommunityService.java:56`), 댓글·신고·좋아요·문의 작성에는 DB 멱등이 없다. 첫 판의 안전 논증과 인그레스 분석은 `client_order_id`가 있는 주문 경로만 다뤘다. 종전 `500`은 다시 보내지 않았으므로 첫 판이 이중 실행 경로를 새로 만든 것이다.

바꾼 것: 컨트롤러 계층의 DB 불통 `503`을 안전 메서드(GET·HEAD·OPTIONS)와 재전송 안전 표식을 단 처리기로 좁혔다(4-3). 표식은 `OrderController`(`client_order_id`, 멱등 취소)와 `TransferController`(필수 `Idempotency-Key`와 유일 제약) 두 클래스에만 달고, `RetrySafeWriteInventoryTest`가 그 목록을 고정한다. 그 밖의 쓰기의 연결 끊김과 자원 부족은 S4와 같은 `500`이고, 로그만 '처리되지 않은 예외' ERROR에서 원인이 드러나는 WARN으로 바꿨다. 검토가 함께 제시한 `504`는 고르지 않았다. `504`도 `proxy_next_upstream` 목록 밖이라 효과는 같지만 게이트웨이 시간 초과를 뜻하는 코드라 §2-3 표에 새 행이 필요하고, `500`은 이미 §2-3 장애 규약(5XX면 재조회)에 들어 있다. 인증 필터는 읽기만 하고(nonce는 노드 메모리) 컨트롤러에 닿기 전이라 메서드와 무관하게 `503`으로 둔다. S4 이전(v2.17)부터 모든 메서드에서 `503`이던 획득 실패·트랜잭션 시작 실패·`TransientDataAccessException`은 그 트랜잭션이 커밋 전에 끝난 경우라 그대로 두었다. 다만 한 요청 안에서 트랜잭션을 둘 이상 커밋하는 쓰기에는 이 판단이 요청 단위로 성립하지 않는다. S4b가 넓히지 않은 기존 한계라 9절에 적고 S5 인그레스 재검토로 넘겼다. API 설계 §2-3 `500`·`503` 행과 §2-5-c, 하네스 검증설계 §3 LB 배선 항목을 고쳤다. 시험: `ApiExceptionHandlerTest`의 '표식 없는 POST의 `TransactionSystemException`은 `503`이 아니다' 사례와 메서드·표식 판정을 빼는 변이(6-2).

### R2. 일시 오류와 자원 부족에서 스냅샷 폴백을 생략하면 재구성과 기동이 끝없이 실패할 수 있다 (medium)

지적대로다. 첫 판의 근거('DB가 돌아오지 않으면 전량 재생도 같은 자리에서 실패')는 연결 장애에만 맞는다. 57014와 53200은 특정 문장의 실패라, 기본 풀의 `statement_timeout` 3초(prod·prod-timeouts, `application.yml:209`, :447) 아래에서 큰 bytea 스냅샷을 읽는 조회(`snapshot/SnapshotStore.java:93-118`)에서만 계속 날 수 있고, 전량 재생은 짧은 구간 조회라 성공할 수 있다. 첫 판대로면 재구성은 30초 간격으로 실패를 되풀이하고 기동 복구는 컨테이너 재기동을 되풀이한다. 스냅샷은 저널 파생 최적화라 복구를 막으면 안 된다는 원칙(ADR 0022 7단계, `EngineRecovery.java:303-309` 주석)과도 어긋났다.

바꾼 것: 폴백 생략을 획득 실패와 연결 끊김으로 좁혔다(4-1의 분류, 4-2). 일시 오류 넷과 자원 부족은 종전처럼 폴백하고 폴백 계수도 올린다. 불일치 행 기록 여부는 폴백과 따로 정한다. 재생이 일시 오류 넷이나 자원 부족으로 끝나면 판정 없는 끝이라 행을 남기지 않고 폴백한다. 연속 N회 뒤 폴백하는 상한안은 고르지 않았다. 연결 장애에서는 전량 재생도 첫 조회에서 실패하므로 상한이 얻는 것이 없고, 문장별 실패에서는 첫 번에 폴백하는 것이 종전 동작이다. DB 설계 §5.4-c 5·§5.5 STEP1 주석과 하네스 검증설계 패치 노트 (1)을 같이 고쳤다. 시험: `SnapshotHookWiringIntegrationTest`에 57014 적재와 57014 시작 재생 사례를 더하고, 폴백 생략을 넓히는 변이로 실패를 확인한다(6-2).

### R3·R8. 재확인이 참인 fencing 거부에서 정당한 리더가 `503`을 낸다 (low 설계, low API 설계)

맞다. append는 재확인이 참이어서 강등하지 않았어도 같은 예외를 다시 던지고(`CommandJournalAppender.java:241-251`), 첫 판은 그 예외를 결과와 무관하게 `503`으로 냈다. 그러면 정당한 리더가 쓰기 업스트림에서 빠져 약 1.3~2.1초 모든 쓰기가 `502`가 된다. 첫 판 4-3·4-4와 API §2-5-c의 '이 노드는 더는 그 마켓의 리더가 아니다'는 이 경우 거짓이었다. 대조 중에 같은 모양이 둘 더 보였다. 재확인 왕복이 실패하면 강등하지 않은 채 거짓을 돌려주고(`engine/LeaderLeaseService.java:371-379`), 같은 노드가 강등·재구성 뒤 새 기간으로 다시 승격했으면 옛 기간의 거부(DB의 epoch 불일치나 앱의 `NotLeaderException`)는 아무것도 강등하지 않는다.

바꾼 것: 리더 자격 거부의 응답을 예외를 받은 순간 그 마켓의 게이트 상태로 가른다(4-3). 재확인이 거짓이면 append가 예외를 올리기 전에 같은 스레드에서 강등이 게이트를 먼저 닫으므로(`engine/PartitionRuntime.java:187-200`, `engine/PromotionCoordinator.java:269-283`) 닫힌 게이트는 리더가 아니라는 뜻이다. 그때만 `503` 페일오버다. 게이트가 열려 있으면 연결 장애와 같은 V47 규칙(`429`·`409`, V47이 DB에 닿지 못하면 `503`)이고, 취소는 `429`다. 검토가 낸 '재확인 결과를 다른 예외 타입이나 표식으로 전달'은 고르지 않았다. 재확인이 참인 경우만 잡고 새 기간의 재승격과 재확인 왕복 실패를 놓치며, 게이트는 이미 같은 스레드에서 확정된 상태라 메모리 조회 하나로 셋을 모두 가를 수 있다. 새 `EngineBridge.accepting(marketId)`를 더한다. DB 설계 §5.4-c 6에는 '재확인이 참이면 재시도한다'를 접수 경로에서는 클라이언트의 같은 키 재전송이 맡는다고 적었다. 계수기는 `fencing|429`·`fencing|409`를 더해 여섯 조합이다. 시험: `OrderGatewayOutageMappingTest`의 리더 자격 거부 사례(P0001, 삽입 0행, `NotLeaderException` 각각 게이트 열림·닫힘)와 게이트 확인을 빼는 변이, 실제 재확인이 참이 되는 `IntakeOutageRetryIntegrationTest` (d), `EngineBridgeMarketGateTest`의 `accepting` 사례.

### R4. 계약 원본을 바꾸고 0099 검토를 미뤄 `check-adr-current`가 실패한다 (low, 0099)

맞다. 이 저장소의 공통 규칙은 구조·검증 상태 변경 때 관련 원본과 0099를 같은 변경에서 검토하라고 한다. 첫 판은 문서 단계로 미뤘다.

바꾼 것: 이 검토 대응에서 0099를 대조했다. 쓰기 구조 문단의 S4 관문 문장 뒤에 S4b가 설계와 계약(DB·API 설계 v2.56)만 고친 단계이고 코드와 재주행이 없다는 문장을, ADR 표의 0034 행에 같은 상태를, 검토 기준일 문단에 이 대조를 적었다. 그 뒤 `check-adr-current.ps1 -PrintDigest`로 `sources-sha256`을 갱신했다. 구현과 문서 단계에서 계약이나 설계를 다시 바꾸면 그때마다 같은 절차를 밟는다(8절). ADR 0034의 「S4 진행」과 결정한 값 11은 코드 결과가 나온 뒤 문서 단계에서 고친다.

### R6. V47 판정 실패가 DB 불통을 뜻하지 않는다 (medium)

맞다. 첫 판 4-3의 '판정마저 실패했다는 것은 이 노드가 지금 DB에 닿지 못한다는 뜻'은 틀렸다. V47은 기본 풀에서 `lock_timeout` 2초·`statement_timeout` 3초 아래 돌고 같은 키의 `cmd-new:` 어드바이저리 락을 기다린다(`V47__order_intake_failure_resolution.sql:56-57`). append 연결의 클라이언트 쪽만 끊기고 서버 쪽 백엔드가 트랜잭션 안에 남아 있으면 V47은 2초 뒤 55P03으로 끝난다. 끊긴 풀 연결이 검증 없이 나가면(J9) DB가 살아 있어도 08006이다. 첫 판은 두 경우를 `503`으로 내 멀쩡한 리더를 뺐고, S4가 §2-5-b(S4 설계 11절 R6)에서 판정 실패를 `503`에서 `409`로 바꾼 결정을 근거 없이 일부 되돌렸다. `resolveFailedSubmit`이 예외를 삼키므로(`OrderGateway.java:556-574`) 지금 구조로는 원인을 가를 수도 없었다.

바꾼 것: `resolveFailedSubmit`이 판정 결과나 마지막 실패의 예외를 돌려준다. 첫 호출이 연결 끊김이면 한 번 더 부르고(검토가 낸 두 선택지를 함께 썼다), 마지막 실패가 DB 불통일 때만 'DB에 닿지 못함'이라 `503`이다. 55P03·57014 같은 그 밖의 판정 실패는 S4처럼 `409 order_in_progress`다(4-3). V47을 다시 불러도 결과가 약속을 어기지 않는 이유를 4-3과 5절에 적었다. 4-4 셋째 항목과 API §2-5-c의 인그레스 영향 문장을 같이 고쳤다. 시험: `OrderGatewayOutageMappingTest`의 'append 연결 장애와 V47 55P03이면 `409`이고 `connection|503` 0' 사례와 첫 V47 08006 뒤 성공 사례, `IntakeOutageRetryIntegrationTest` (c)의 실제 어드바이저리 락 사례. 함수 이름을 바꿔 V47을 끄는 기존 방법은 42883이라 'DB에 닿음'이 되므로, 'DB에 닿지 못함' 사례는 게이트웨이의 판정 이음매로 획득 실패를 주입한다(6-1).

### R7. 단건 취소의 DB 연결 장애는 늘 `503`이라 끊긴 연결 하나로 살아 있는 리더가 빠진다 (medium, API 설계)

맞다. 첫 판의 취소는 V47이 없어 DB 연결 장애면 늘 `503`이었고, 인그레스 영향과 9절은 인증 필터·컨트롤러 조회만 적어 취소를 빠뜨렸다. 봇은 주기마다 취소를 보내므로 DB가 흔들린 직후 끊긴 append 연결을 취소가 먼저 잡을 수 있다. Y1(정상 10분)은 DB 흔들림이 없어 이 비용을 재지 못한다.

바꾼 것: 취소에 주문 생성과 같은 기준을 둔다(검토의 첫 선택지). append가 DB 불통으로 끝나면 대상 주문 행을 다시 읽어 DB에 닿으면 `409 order_in_progress`, 닿지 못하면 `503`이다. 연결 끊김이면 한 번 더 읽는다(4-3). 취소 명령이 저널에 들어갔는지는 모르므로 `429`(확정 미접수)가 아니라 `409`다. 일괄 취소와 회원 폐쇄는 이 경우를 `service_unavailable`로 적고 미완료로 센다. 단건의 `409`를 그대로 쓰면 일괄 취소가 그것을 '저널 뒤 반영 대기'로 보고 미완료에서 빼기 때문이다. 대조 중에 첫 판의 원소별 처리가 만든 문제도 찾았다. S4는 첫 연결 장애에서 요청 전체를 끝냈는데 첫 판은 다음 원소로 넘어가, 남은 원소마다 연결 대기를 다시 쓰고, 다음 원소의 접수 전 조회가 같은 장애를 원시 예외로 올리면 요청 전체가 오류가 되어 앞 결과를 잃었다. 한 원소가 'DB에 닿지 못함'이면 남은 원소를 시도하지 않게 했다(4-3). 인그레스 영향(4-4, API §2-5-c)에 취소와 주문 접수 전 조회를 넣고 9절을 고쳤다. DB가 흔들린 직후의 비용을 재는 기록 실험 B1(7-1의 7, 7-2 Y6)을 더했다. 리더의 pg 프록시에 `reset_peer`를 500ms 걸어 쓰던 연결만 끊고 DB는 살려 둔 채 LB `502` 수와 리더 `503`의 원천을 잰다. 시험: `OrderGatewayOutageMappingTest`의 취소·일괄 취소 사례와 조기 종료를 빼는 변이, `OutageResponseChainTest`의 배치 조기 종료 사례.

### R9. E1 반복 규칙이 절마다 다르다 (low)

맞다. 7-1의 조기 종료 문장이 E1에도 걸려, 따르면 재현을 노리는 넷째·다섯째 회차를 건너뛸 수 있었다.

바꾼 것: 7-1의 조기 종료를 E2에만 적용하고(연속 세 번 PASS이고 원인 A 경로를 한 번 이상 탔을 때), E1은 앞의 세 번이 PASS여도 다섯 번을 모두 돈다고 4-6, 7-1, 7-2 Y3에 맞춰 적었다. 하네스 검증설계 패치 노트 (6)도 같게 고쳤다.

### R10. 새 응답 분기의 시험이 P2뿐이라 P1의 예약 회수를 보지 않는다 (low)

맞다. 게이트웨이 분기는 P1과 P2가 함께 쓰고(`OrderGateway.java:396-405`의 finally가 `outcomeUnknown`·`journaled`로 in-flight 예약의 해제와 지연 회수를 가른다), P1은 지금 기본 모드이며 앞 단계와의 호환 대상이다.

바꾼 것: `OrderGatewayOutageMappingTest`에 P1 게이트웨이 사례 셋을 더했다. V47이 DB에 닿지 못해 `503`인 경우와 DB에 닿았으나 55P03으로 `409`인 경우는 in-flight 누계가 예약액 그대로(지연 회수)이고, DISCARDED로 `429`인 경우는 0이다. `outcomeUnknown` 설정을 빼는 변이로 앞의 두 사례가 실패하는지 본다(6-2).

## 12. 구현 결과 (2026-09-25)

코드 단계는 같은 날 같은 작업 트리(`C:\dev\exchange-pml`, HEAD `48629d5d`)에서 했고 커밋하지 않았다. 계약(DB·API 설계 v2.56, 하네스 검증설계의 S4b 패치 노트, 하네스 CI/CD §5-2)은 설계 단계에서 고친 판을 기준으로 코드를 맞췄다. 이 단계에서 계약을 다시 고친 것은 둘이고 둘 다 코드보다 먼저 고쳤다. E1 재현 뒤의 F1(DB 설계 §5.4·§6.4와 ADR 0034 결정한 값 11, 13-3)과, 폐기용 스택의 E6이 드러낸 `orders_500_0` 판정의 멈춘 노드 규칙(하네스 검증설계 S4b 패치 노트 (3)과 4-7, 13-4)이다. 새 마이그레이션은 없고 웹, 인그레스, 경보 규칙, DB 함수는 바꾸지 않았다. 바꾼 운영 코드는 4-9의 목록과 같고, 조건부 항목 가운데 F1만 적용했다(`ledger/SettlementService`). F2는 재현되지 않아 적용하지 않았다. 목록에 없던 새 파일은 시험 도우미 둘이다. `journal/DbFailures`는 3-1의 J1~J9 모양 예외를 드라이버 예외와 JdbcTemplate 기본 번역기로 만들고, `api/order/OrderGatewayTestAccess`는 다른 패키지의 시험이 게이트웨이의 V47 판정 이음매(패키지 전용)에 실패를 넣게 한다.

### 12-1. 설계와 다르게 하거나 설계가 정하지 않은 것

모두 이 코드를 맡은 AI가 정했다. 동작과 안전 성질을 바꾸는 것은 없고 표식의 모양, 처리기를 읽는 자리, 시험 배선이 대부분이다.

1. 'DB에 닿지 못함' 표식의 모양. 4-3은 `ApiException`에 렌더하지 않는 표식 하나를 적었다. 구현은 세 값(`NONE`, `REACHED`, `UNREACHABLE`)의 `ApiException.DbOutage`다. 일괄 취소는 DB 연결 장애로 끝난 단건 취소의 `409`(재조회가 DB에 닿음)를 '저널 뒤 반영 대기'의 `409`와 가려 `service_unavailable`로 세야 하므로 표식이 하나 더 필요했다. 배치 주문과 일괄 취소의 조기 종료는 `UNREACHABLE`만 본다. 주문 생성의 응답은 `REACHED`를 달지 않는다. 그 값을 읽는 곳이 없기 때문이다.
2. 컨트롤러의 처리기 판정. 4-3은 `@ExceptionHandler`가 받는 `HandlerMethod` 인자로 재전송 안전 표식을 본다고 적었다. 스프링은 처리기가 없는 요청에서 그 인자를 해석하지 못하면 예외 처리기 자체를 건너뛰므로, 매핑이 요청에 남기는 속성(`HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE`)에서 처리기를 읽는다. 판정 결과는 같다.
3. 기존 시험 하나를 더 고쳤다. `BootAsFollowerIntegrationTest.endedTermCannotReinject`는 끝난 기간의 재적재 거부를 `IllegalStateException`으로 기대했다. 4-2가 그 거부를 `NotLeaderException`으로 바꿨으므로 기대 예외를 고쳤다. 6-3의 목록에 없던 것이다.
4. `PartitionNode`의 주입점. 머리말 문장에 SQL 오류를 한 번 던지는 append 풀 감싸개(`PrologueFaultDataSource`, 연결 프록시)를 append의 `JdbcClient`와 `TransactionTemplate`이 함께 쓴다. 주입하지 않으면 모든 호출을 그대로 넘긴다. 재생 결함 주입점은 복구 재생의 재생 서비스에만 걸고 팔로워 따라잡기의 재생 서비스는 그대로 둔다. `recovery_runs` 기록자는 운영 배선(`EngineConfig`)처럼 늘 붙인다.
5. `PartitionsEndpointTest`의 배선. 강등 기록을 만들려면 그 파티션이 승격 중인 기간을 쥐고 있어야 해서, 승격 작업을 돌리지 않는 워커와 빈 저널의 팔로워 복구로 바꿨다. 기존 네 사례는 그대로 통과한다.
6. `SnapshotHookWiringIntegrationTest`의 새 사례(6-2의 (a)(b)(c))는 전역 채번 한 모드로 돈다. 폴백 분류가 채번 모드와 무관하기 때문이다.
7. 하네스의 노드 계수 읽기. `s3_common_axes.py`의 줄 정규식(`PROM_LINE`)은 레이블 값에 중괄호가 있는 줄(`uri="/api/v1/orders/{id}"`)을 건너뛴다. 시험에서 이것을 찾아 `node_orders_500`에는 마지막 중괄호까지 레이블로 읽는 식을 따로 두었다. S4 실행기의 `Get-NodeMetrics`도 같은 이유로 그런 줄을 놓쳤고, S4b 실행기는 같은 방식으로 고쳤다.
8. 실행기의 다시 굽기. 7-0은 F1·F2로 다시 구우면 `s4b2ref`를 붙인다고만 적었다. 실행기에 `-Rebake`를 두어, 지금 `:pml`이 첫 이미지(`s4bref`)인지와 `s4b2ref`가 아직 없는지 확인한 뒤 굽고, 첫 굽기의 `images.json`을 `images-s4bref.json`으로 옮겨 둔다. X7의 참조 태그 확인은 `images.json`의 `refTag`를 읽는다.

### 12-2. 시험

전체 빌드(`server`에서 `.\gradlew.bat build --console=plain --continue`, 2026-09-25 04:56~05:13, 17분 27초)가 성공했다. app 모듈은 167개 클래스의 시험 868개가 모두 통과했고 실패·건너뜀은 0이다. engine 모듈은 입력이 바뀌지 않아 그 빌드에서 최신 판정(UP-TO-DATE)이었으므로 `:engine:test --rerun`으로 다시 돌려 67개가 모두 통과했다. 빌드 로그와 결과 XML에 `localhost:5432`·`localhost:55432`(127.0.0.1 포함) JDBC 주소는 0건이다. DB가 필요한 시험은 모두 Testcontainers 일회용 `postgres:17-alpine`에서 돌았다. F1 뒤의 마지막 전체 빌드는 13-3에 적는다.

새 시험 클래스는 8개, 시험 24개다. `DbOutageErrorsTest` 4, `RecoveryRunClassificationTest` 2, `RebuildOutageRetryIntegrationTest` 2, `OrderGatewayOutageMappingTest` 10, `IntakeOutageRetryIntegrationTest` 1(6-2의 네 장면을 한 노드에서 차례로 돈다), `OutageResponseChainTest` 2, `ApiAuthFilterOutageTest` 2, `RetrySafeWriteInventoryTest` 1이다. 사례를 더한 시험은 `SnapshotHookWiringIntegrationTest` 1, `ApiExceptionHandlerTest` 5, `EngineBridgeMarketGateTest` 1, `PartitionTermRaceTest` 2, `PartitionsEndpointTest` 1이다. 고친 기존 시험은 `OrderGatewayReserveMappingTest`(6-3, 대조 예외를 54000과 `IllegalArgumentException`으로 바꾸고 이름을 고쳤다)와 `BootAsFollowerIntegrationTest`(12-1의 3)다. 유지해야 할 시험(6-2 끝의 아홉)은 모두 통과했다.

6-1의 변이 확인은 24개를 돌렸고 모두 새 단언이 실패했다. 변이마다 원본을 보관하고 치환해 대상 시험을 돌린 뒤 원본을 되돌렸고, 되돌린 파일의 해시가 고친 판과 같은지 대조했다(운영 코드 248개 파일 전체도 전후 해시가 같다). 변이와 실패한 시험은 이렇다. F1의 변이 둘은 13-3에 적는다.

| 변이 | 실패한 시험 |
|---|---|
| 연결 끊김 규칙에서 `TransactionSystemException`을 뺀다 / 53을 연결 끊김에 넣는다 | `DbOutageErrorsTest` |
| 판정 없는 끝을 늘 거짓(S4 동작) / 늘 참 | `RecoveryRunClassificationTest`와 `RebuildOutageRetryIntegrationTest`의 연결 실패 사례 / 불일치 사례 |
| 스냅샷 적재의 폴백 생략을 없앤다 / 폴백 생략을 판정 없는 끝 전체로 넓힌다 | `SnapshotHookWiringIntegrationTest`의 (a) / (b)(c). 시작 재생의 폴백 생략을 없애는 변이에 걸리는 시험은 이때 없었다(14절 I2) |
| 게이트웨이의 리더 자격 거부·DB 불통 분기를 뺀다 | `OrderGatewayOutageMappingTest`의 네 시험 |
| V47 다시 부르기를 뺀다 | 끊긴 판정을 다시 부르는 사례(이때는 둘째 호출이 키를 지워 DISCARDED인 것을 기대했다. 14절 I1에서 고쳤다) |
| 판정 실패를 모두 `503`으로 한다 | 55P03·57014 사례와 `IntakeOutageRetryIntegrationTest` (c) |
| 게이트 확인을 뺀다(늘 `503`) | 게이트 열림 사례와 `IntakeOutageRetryIntegrationTest` (d) |
| 취소 재조회를 뺀다(늘 `503`) | 취소 재조회 사례 |
| 일괄 취소의 조기 종료를 뺀다 / 단건의 `409`를 그대로 쓴다 | 일괄 취소 사례 |
| P1의 `outcomeUnknown`을 세우지 않는다 | P1 사례 |
| 판정 실패 때 키를 지운다 | `IntakeOutageRetryIntegrationTest` (a) |
| 배치 조기 종료를 뺀다 / 수요 제외 표식을 세운다 | `OutageResponseChainTest`의 배치 사례 / 응답 사례 |
| 인증 필터를 S4의 catch로 되돌린다 | `ApiAuthFilterOutageTest` |
| 처리기의 메서드·표식 판정을 뺀다(늘 `503`) / 새 처리기를 뺀다 | `ApiExceptionHandlerTest`의 표식 없는 쓰기 사례 / 안전 메서드·재전송 안전 사례 |
| `CommunityController`에 표식을 단다 | `RetrySafeWriteInventoryTest` |
| `accepting`이 늘 참을 돌려준다 | `EngineBridgeMarketGateTest` |
| 강등 `detail`을 S4 모양으로 되돌린다 | `PartitionsEndpointTest`의 새 사례 |
| `PromotionCoordinator`·`PartitionRuntime`을 `48629d5d`로 되돌린다(참여를 락 밖에서 읽음) | `PartitionTermRaceTest`의 새 두 사례 |

경합·시각을 보는 네 시험(`PartitionTermRaceTest`, `RebuildOutageRetryIntegrationTest`, `IntakeOutageRetryIntegrationTest`, `OutageResponseChainTest`)은 `--rerun`으로 세 번 돌렸고 세 번 모두 통과했다. 하네스는 `python -B -m unittest discover -s harness/scale -p "test_*.py"` 325개가 통과했다(`test_s3_common_axes.py`에 8개, 새 `test_roundtrip_probe.py`에 3개를 더했다).

## 13. 폐기용 스택 실험과 E1 보정 (2026-09-25)

증거는 [`harness/evidence/pml-S4b-20260925-0456/`](../../../harness/evidence/pml-S4b-20260925-0456/README.md)에 있다. 실행기는 7-1대로 S4 재주행 실행기를 복사해 고친 `run-s4b-gate.ps1`이고, 모든 실험의 DB 모드는 P2, 파티션은 `matching-engine` 하나다. 7-0의 수칙을 지켰다. 단계마다 앞뒤로 보존 볼륨 두 개의 생성 시각이 같았고, `exchange-chaos` 프로젝트와 `exchange-chaos-app:local`은 건드리지 않았으며, 실험 동안 이 세션에서 Gradle과 Testcontainers를 돌리지 않았다. 호스트 표본(실험마다 앞뒤 다섯)에서 도커·앱·부하 도구 밖의 바쁜 프로세스는 첫 이미지의 실험에서 늘 0, 다시 구운 이미지의 실험에서 0~1개였다. 시간 축에서만 FAIL한 실험이 없어 7-3의 잡음 재실행은 한 번도 하지 않았다.

### 13-1. 첫 이미지의 결과

첫 이미지는 12-2의 전체 빌드가 만든 jar로 구운 `sha256:c19e0d0e…`(참조 태그 `s4bref`, V0~V53, 앞 이미지 `s4r1ref`는 그대로 두었다)다.

| 실험 | 판정 | 요지 |
|---|---|---|
| Y1 X1 P2 봇 부하 10분 | PASS | 판정 축 25개 참. P2 epoch NEW = 예약 22,153, 체결 15,965, 리더 epoch 6 유지, 두 노드 강등 0, 창 밖 경합 0(append 17,603), 연결 최대 38. `orders_500_0` 세 원천 0(LB 접근 로그 36,054줄), 접수 불가 계수 증가 0, `recovery_runs` 성공 6·불일치 0, 두 api 로그의 '처리되지 않은 예외'·'인증 처리 실패로 500' 0줄 |
| Y1 X6 a~d | PASS | a: L 30건 `429`(market), M 최대 1,025ms. b: M `201` 36(최대 2,027ms), `429` 14(market 13, node 1), 5xx 0. c: 같은 키 동시 요청 10회 모두 둘 다 `409 order_in_progress`, R 재전송 `200` 7·`409` 3, `503` 0. d: 66회 `429`(market), 두 층 강등 0, epoch 불변 |
| Y2 E2 DB 절단 1~5회 | PASS 5/5 | RTO 5.943·5.963·11.978·5.972·12.114초, epoch 5 -> 6, rpo_records 0, 구 epoch 쓰기 0. 세 원천 `orders_500_0`, `recovery_runs` 불일치 0, '처리되지 않은 예외' 0줄 |
| Y3 E1 PG 지연 1~4회 | PASS 4/4 | 정산 일시 오류 재시도 5·5·4·1회(55P03, 셋째는 57014 1회 포함), 소진 0, 두 층 강등 0, 획득 `lock_timeout` 0 |
| Y3 E1 PG 지연 5회 | FAIL(재현) | 리더 정산이 55P03을 두 번 만나 7.3초 만에 재시도 한도(4회·5초)를 넘겨 `WRITER_FATAL: UncategorizedSQLException sqlstate=55P03`로 강등, epoch 5 -> 6(13-3) |
| Y6 B1 흔들림 500ms | N/A(측정 1/3) | 측정한 둘째 흔들기: 리더 접수 `503` 1건(`connection|503`, V47 두 번 모두 DB에 닿지 못함), LB `502` 82줄 약 2초. 첫째·셋째는 라이터 치명으로 epoch가 올라 N/A |
| Y6 B1 흔들림 100ms | N/A(측정 2/3) | 측정한 첫째·둘째: 인증 필터 `503` 1건씩(uri `UNKNOWN`), LB `502` 83·73줄 약 2초. 셋째는 라이터 치명으로 N/A |

E2는 원인 B 경로를 탔다. 첫째 회차에서 DB가 돌아온 순간 옛 리더 api-1의 접수 append가 fencing 거부(P0001)를 받았고, 같은 스레드의 강등이 게이트를 먼저 닫아 세 건이 `503`(`fencing|503`)으로 끝났다. S4 재주행 X4r에서 클라이언트에게 `500`으로 나간 2-2의 (다) 갈래다. 둘째는 복귀 뒤 갱신 거부로, 넷째는 복귀 순간 라이터의 08006으로 강등했고 접수 `503`은 없었다. 셋째와 다섯째는 절단 중 라이터가 롤백 실패(`TransactionSystemException`, 강등 기록 `sqlstate=08006(앱 예외)`)로 치명이 된 뒤 재구성이 스냅샷 적재에서 획득 실패로 다섯 번 끝났고(1·2·4·8·16초 간격), DB가 돌아온 뒤 여섯째 시도에서 `FOLLOWER`로 돌아왔다. 4-2대로 그 다섯 번은 폴백하지 않았고 `recovery_runs`에 행을 남기지 않았다. S4 X4의 C17 1행은 스냅샷 적재 실패 뒤 전량 재생으로 가던 시도가 DB 복귀 순간 끝나면서 생겼는데, 이 경로가 이제 스냅샷 단계에서 끊긴다. 재생 도중에 DB가 끊겨 '재생이 판정 없이 끝났다'를 남기는 분기는 다섯 번 모두 타지 않았다. 7-2 Y2의 규칙대로 다섯 번을 모두 돌았고, 원인 A의 그 분기는 시험(6-2)으로만 확인했다. 창 안 LB 줄은 `502` 13·45·117·37·113, `503` 2·2·5·4·9, `500`·`504` 0이다.

B1은 기록 실험이라 판정은 안전 축뿐이고, 두 주행 모두 불변식 위반 0, 세 원천 `orders_500_0`, '처리되지 않은 예외' 0줄, `recovery_runs` 불일치 0이었다. 여섯 번 가운데 셋은 흔들기가 리더 라이터의 쓰던 연결을 끊어 롤백 실패(`TransactionSystemException`, `sqlstate=08006(앱 예외)`)로 치명이 됐다. 같은 노드가 약 1초 뒤 재구성해 팔로워로 돌아오고 약 5초 뒤 새 epoch로 다시 승격했으며, 그동안 LB `502`가 209·214·212줄(약 7~9초) 났다. 커밋 여부를 모르는 연결 오류를 치명으로 올리는 것은 ADR 0034 결정 8의 설계이고 S4b가 바꾸지 않았다. 7-2 Y6은 흔들기 시간을 줄여 다시 돌라고 했고 100ms에서도 셋째가 같았으므로, 이 치명은 흔들기 시간보다 그 순간 라이터가 연결을 쓰고 있었는지에 달렸다. 측정한 세 번의 리더 `503`은 모두 1건이고 LB `502`는 약 2초 이어졌다. 이 수는 10절 1·3의 사용자 확인 근거로 옮긴다.

### 13-2. E1 재현과 원인

다섯째 회차(2026-09-25 06:33~06:39 KST)가 S4 첫 E1 FAIL을 재현했다. 06:37:42 리더 api-1의 정산(`cmd_seq=1450`)이 잔고 행 잠금 대기 초과(55P03, 'canceling statement due to lock timeout')를 두 번 만나, 첫 시도부터 7,323ms가 지나 둘째 시도 뒤 기한(5초)을 넘겼다. 로그는 '정산 일시 DB 오류를 재시도 제한 안에서 흡수하지 못했다 ... 시도=2/4, 경과_ms=7323'과 '단일 라이터 루프 정지(fail-fast): resource=matching-engine, epoch=5, sqlstate=55P03, 원인=UncategorizedSQLException > PSQLException(55P03)'이고, 강등 기록은 `WRITER_FATAL: UncategorizedSQLException sqlstate=55P03`이다. 4-6의 기록 보강이 원인을 바로 드러냈다. 옛 리더는 4.7초 뒤 재구성해 `FOLLOWER`로 돌아왔고, api-2가 epoch 6으로 승격해 1,154ms 재생 뒤 접수를 열었다. 무결성 위반, 구 epoch 쓰기, 주문 `500`은 없었다.

한 시도가 약 3.6초인 것은 PG 지연(200±100ms)이 정산 트랜잭션의 문장마다 붙고, 잠금 대기 초과는 `lock_timeout` 2초를 채운 뒤에야 나기 때문이다. 4-6의 판단표에서 이 관측은 첫 행(정산 재시도 소진)이다. 한도를 넘긴 일시 오류를 치명으로 올리는 규칙은 옳고 한도의 크기가 E1 지연의 잠금 대기 사슬보다 짧았다. 재확인이 참인 P0001, 08xxx, 40P01·40001은 다섯 번 모두 나오지 않았으므로 F2는 적용하지 않았다(9절의 계약 어긋남은 그대로 남는다).

### 13-3. F1 적용과 시험

4-6의 조건부 수정 F1을 적용했다. 계약을 먼저 고쳤다. DB 설계 §5.4 정산 sink 오류 분류를 시도 8회, 첫 시도부터 15초 기한, 대기 50~100·100~200밀리초 뒤 200~400밀리초로 바꾸고 흔한 최악의 시간(약 17~18초), 좀비 판정 창·예약 TTL과의 관계, 15초의 근거, 이번 재현을 적었다. §6.4의 재구축 서술과 v2.56 패치 노트, ADR 0034 결정한 값 11도 같이 고쳤다(AI 선택, 사용자 확인 필요). 코드는 `SettlementService`의 상수 셋(`MAX_ATTEMPTS` 8, `DEFAULT_RETRY_DEADLINE_MS` 15,000, 대기 상한 400ms)과 대기 식, 주석이다. 한도를 넘기면 여전히 치명이고, 시도마다 새 트랜잭션에서 기간 확인, DB fencing 판정, 레지스트리 멱등 판정을 다시 하는 구조는 그대로다.

새 시험 `SettlementRetryBudgetIntegrationTest`는 운영 기본 풀과 같은 `lock_timeout` 2초의 정산 풀에서 다른 세션이 사용자의 KRW 잔고 행을 쥔다. 9초 쥐면 정산이 10.7초에 재시도 4회로 흡수하고(소진 0, 예약 한 번, 리더 유지), 20초 쥐면 기한에 걸려 일곱째 시도 뒤 55P03으로 올리며 소진 1, 주문 행 0이다. `SettlementRetryClassificationTest`의 한도 사례는 재시도 7회로 고쳤다. 변이 둘로 시험을 확인했다. 한도를 종전 값(4회·5초)으로 되돌리면 두 사례가 모두 실패하고, 한도를 사실상 없애면(1,000회·600초) 20초 사례가 실패한다. 두 변이 뒤 운영 코드 해시가 고친 판과 같았다.

F1 뒤 이 단계의 마지막 전체 빌드(2026-09-25 06:46~07:03, 17분 43초)가 성공했다. app 모듈은 168개 클래스의 시험 870개가 모두 통과했고(12-2보다 `SettlementRetryBudgetIntegrationTest`의 둘이 늘었다) 실패·건너뜀은 0이다. engine 모듈은 다시 최신 판정이라 `:engine:test --rerun`으로 67개가 모두 통과했다. 빌드 로그, engine 재실행 로그, 결과 XML에 `localhost:5432`·`localhost:55432`(127.0.0.1 포함) JDBC 주소는 0건이다. 하네스 단위 시험 325개도 다시 통과했다.

### 13-4. 다시 구운 이미지의 결과

이 빌드의 bootJar(SHA-256 `3c4e51d0…`)로 이미지를 다시 구웠다(`sha256:e7baeb2f…`, 참조 태그 `s4b2ref`, V0~V53, 작업 트리 diff SHA-256 `71e096d8…`). 첫 이미지와 `s4bref`는 그대로 두었다. 7-2 Y3·Y4대로 E1 세 번, X1, E2 한 번, E4(합류 단계 포함)·E5·E6·E7·COLD를 한 번씩 돌았다. 앞부분(다시 굽기, E2, X1 앞부분)에는 다른 세션의 서버 시험이 호스트에서 함께 돌았다(실행 전후 그 세션의 Testcontainers 컨테이너 최대 34개, 증거의 `host-concurrency.txt`).

| 실험 | 판정 | 요지 |
|---|---|---|
| Y3 E1 PG 지연 1~3회 | PASS 3/3 | 스크립트 1차 판정, 두 층 강등 0, 획득 `lock_timeout` 0, 세 원천 `orders_500_0`, '처리되지 않은 예외' 0줄. 정산 일시 오류 재시도 7·1·2회(55P03, 첫째는 57014 1회 포함), 소진 0, epoch 5 유지, 주입 p99 2,237·2,056·2,091ms, 해제 뒤 회복 4.19·2.04·2.15초 |
| Y1 X1 P2 봇 부하 10분 | PASS | 판정 축 25개 참. 창 안 주문 `201` 15,586(첫 X1 15,736), P2 epoch NEW = 예약 15,750(X6을 잇지 않아 첫 X1의 22,153보다 적다), 체결 11,699, 리더 epoch 6 유지, 연결 최대 39, `recovery_runs` 성공 6·불일치 0, LB 접근 로그 35,892줄에 주문 `500` 0 |
| Y2 E2 DB 절단 | PASS | RTO 10.276초, epoch 5 -> 6, rpo_records 0, 구 epoch 쓰기 0. 절단 중 옛 리더의 접수 append가 롤백 실패(`TransactionSystemException`)로 끝나고 V47도 DB에 닿지 못해 `503` 1건(`connection|503`, 미해결 1). S4 재주행에서 '처리되지 않은 예외'로 `500`이던 2-2의 (가) 갈래다. 창 안 LB `502` 46·`503` 2·`500` 0 |
| Y4 E4 리더 kill과 합류 | PASS | RTO 5.385초, epoch 5 -> 6, rpo_records 0. 되살린 옛 리더가 12.8초 만에 `FOLLOWER`로 합류(승격 문구 0, 리스 행 불변) |
| Y4 E5 네트워크 분리 | PASS | RTO 5.782초. 옛 리더의 접수 append가 fencing 거부(P0001)를 받아 `503` 1건(`fencing|503`) |
| Y4 E6 부하 급증 중 kill | FAIL(공통 판정) 뒤 재실행 PASS | 첫 실행은 RTO 5.543초로 스크립트 판정이 PASS였고 LB 3,961줄과 프로메테우스의 주문 `500`이 0이었지만, kill한 옛 리더의 노드 누계를 읽지 못해 `orders_500_0`이 거짓이었다(아래). 판정을 고친 뒤 재실행은 RTO 6.276초, 옛 리더 `stopped`, LB 3,702줄·프로메테우스·새 리더 누계 모두 0 |
| Y4 E7 리더 pause | PASS | RTO 12.333초. 재개한 옛 리더는 갱신 거부로 강등했다. 응답을 쓰지 못한 요청 22건은 WARN('클라이언트 연결이 끊겨')이고 '처리되지 않은 예외'는 0줄이다(S4 X3에서는 같은 요청 21건이 그 ERROR였다). 멈춰 있던 라이터는 강등 63ms 뒤 유휴 트랜잭션 끊김(25P03)으로 치명이 됐다 |
| Y4 COLD 콜드 재기동 | PASS | 접수 재개 11.725초, 첫 체결 11.825초. 먼저 올린 api-1이 팔로워로 기동한 뒤 승격했고 나중 노드는 `FOLLOWER`, 강등 0(`-S4Boot` 참) |
| Y5 X7 처리량(선택, 판정 아님) | 기록(칸 추가 필요) | 칸 값 '접수/s(k6 저널 행)'이 S3 칸 171.1·103.3, S4 칸 148.5·142.8이다. S3 두 칸의 잡음이 0.49라 판정 보조 규칙상 칸을 더 돌아야 하고 손실은 판단하지 않는다(아래) |

첫째 E1에서 한 정산(`cmd_seq=1412`)이 55P03을 두 번 만나고 셋째 시도에서 끝났다. 두 실패 사이가 3.96초이고 첫 시도도 잠금 대기 초과(2초)까지 걸렸으므로, 종전 한도였다면 둘째 실패에서 기한 5초를 넘겨 라이터가 치명이 됐을 장면이다. F1이 이 경우를 흡수한 스택 증거다. E1 여덟 번 가운데 다섯 번(첫 이미지의 셋째·넷째, 다시 구운 이미지의 셋)은 주입 전·중·후 권위 run 가운데 한두 번에서 C13A 1행을 ERROR로 남겼다. C13A는 append와 정산 커밋 사이의 비동기 물질화 창이라 드레인 전 판정에서 빼는 체크이고(`harness/scripts/lib.ps1`의 게이팅 면제 목록), 스크립트의 게이팅 판정은 모두 참이었다.

E6 첫 실행의 FAIL은 판정 결함이었다. E6은 옛 리더를 kill한 채 두는데, 4-7의 첫 규칙은 판정 시점에 두 노드의 누계를 모두 읽어야 해서 그 노드를 읽지 못한 원천으로 치고 축을 거짓으로 만들었다(`nodes`의 api-1이 `null`). 이 규칙이면 E6은 늘 거짓이다. kill 전 누계는 재기동한 노드(E4·COLD)와 같은 이유로 LB 로그와 프로메테우스가 덮고, 둘 다 0이었다. 하네스 검증설계 S4b 패치 노트 (3)과 4-7을 먼저 고쳐, 실험이 멈춘 채 두는 노드를 노드 누계에서 빼고 `stopped`로 적게 했다. 멈췄다고 치는 것은 `docker inspect`의 `State.Running`이 거짓일 때뿐이고, 돌고 있는 노드를 긁지 못했거나 읽은 노드가 하나도 없으면 여전히 거짓이다. `test_s3_common_axes.py`에 시험 셋을 더했고(하네스 단위 시험 328개 통과), 규칙을 되돌리는 변이, 읽은 노드가 없어도 참으로 치는 변이, 확인하지 못한 노드를 멈춘 것으로 치는 변이가 모두 새 시험에 걸렸다. 같은 이미지로 E6만 다시 돌아 PASS였다. 나머지 넷은 판정 시점에 두 노드를 모두 읽었으므로 이 보정과 무관하다.

X7은 선택 실험이라 관문에 넣지 않았다. S3 이미지(`s3ref`)와 다시 구운 이미지를 번갈아 칸마다 새 스택에서 돌았다(12마켓 600/s, 부하 계정 16개, k6 130초). 칸 값 '접수/s(k6 저널 행)'은 k6 창의 k6 NEW 행 수를 주행 초로 나눈 값이고 네 칸 모두 나왔다(S3a 22,246행 171.1, S4a 19,305행 148.5, S3b 13,432행 103.3, S4b 18,559행 142.8). 종전 칸 값인 1분 창 값은 161.0·113.9·62.5·99.0으로 칸마다 저널 행 기준보다 6~40% 낮았다. S3 두 칸의 잡음 n = |171.1 - 103.3| / 137.2 = 0.49가 0.10을 넘어 판정 보조 규칙상 칸을 더 돌아야 하므로 손실 L(-0.06)은 판단에 쓰지 않는다. S3b 칸 뒤 호스트 표본은 전체 CPU 46~69%에 도구 밖의 바쁜 프로세스 1개였다. 범위를 중앙값으로 나누는 잡음 식은 칸을 더해도 이 한 칸의 이탈을 지우지 못하므로 더 돌지 않았다. 모든 칸에서 400은 0건, 드레인 뒤 `summary.sql` CRITICAL·HIGH PASS, 교착 증가 0이고, S4 칸 리더의 접수 경합 `429`는 26·25건이다.

### 13-5. 남은 것

- 사용자 확인. 10절의 여덟 가지가 그대로 남았다. 8(F1)은 적용한 값(시도 8회, 첫 시도부터 15초, 대기 50~100·100~200밀리초 뒤 200~400밀리초)과 그 대가(한 마켓 라이터가 치명 전에 멈춰 있을 수 있는 시간이 약 7초에서 약 17~18초로 는다)를 확인받는다. 1·3의 근거로 B1이 잰 비용을 쓴다. 리더의 쓰던 연결만 끊긴 순간 리더 `503`은 한 건이었고 LB `502`가 약 2초 이어졌다.
- B1에서 드러난 더 큰 비용. DB가 살아 있는데 리더의 쓰던 연결만 100~500ms 끊겨도, 그때 라이터가 그 연결로 쓰고 있었으면 롤백 실패로 치명이 되고 재구성과 재승격까지 약 7~9초 동안 쓰기가 LB `502`였다(여섯 번 가운데 셋). 커밋 여부를 모르는 연결 오류를 치명으로 올리는 것은 ADR 0034 결정 8의 규칙이고 S4b가 바꾸지 않았지만, 9절 첫 항목이 적은 비용(약 1.3~2.1초)보다 크다. 줄일지는 S5 인그레스 재검토 전에 판단할 일로 남긴다(사용자 확인).
- 원인 A 가운데 재생 도중 DB가 끊겨 '재생이 판정 없이 끝났다'를 남기는 분기는 스택에서 한 번도 타지 않았다(E2 여섯 번). 시험(6-2)과 변이로만 확인했다. 스냅샷 적재 단계에서 끊는 쪽(S4 X4의 C17 경로)은 E2 두 번에서 탔다. 9절의 'E1 재현 실패' 위험은 재현과 F1로 해소됐다.
- F2와 정산의 fencing 재확인 계약 어긋남(9절)은 E1에서 드러나지 않아 그대로다.
- Y5 X7은 칸 값 '접수/s(k6 저널 행)'이 스택에서 나오는 것까지 확인했지만 잡음(0.49)이 커서 손실을 판단하지 못했다. S7 전에 조용한 호스트에서 다시 잰다. E3은 7-2대로 N/A다.
- 문서 단계로 넘긴 것(8절): ADR 0034 머리말 「S4 진행」, 단계 계획 S4 절, 로드맵, 코드 지도, `docs/measurements/`의 그날 파일, V53의 '커밋 전' 문장, S4 설계 13-3의 이 문서 연결. 코드와 증거의 커밋도 문서 단계에서 한다.

## 14. 구현 검토 대응 (2026-09-25)

12·13절의 코드에 대한 구현 검토 지적 둘을 받았다. 코드, V47 함수, 시험을 대조해 둘 다 실제 문제로 판단하고 고쳤다. I1·I2는 받은 순서이고 심각도는 검토가 매긴 값이다. 계약은 코드보다 먼저 고쳤다. API 설계 v2.56의 §2-4·§2-5-b·§2-5-c와 패치 노트, DB 설계 §3 표의 V47 행과 v2.56 패치 노트이고 판 번호는 그대로다. 이 문서의 4-3, 5절, 6-2, 10절 1, 12-2도 같이 고쳤다.

### I1. 끊긴 V47 판정을 다시 부르면 같은 키로 들어온 다른 요청의 키를 지운다 (medium)

지적대로다. 4-3의 구현은 연결이 끊긴 판정을 같은 인자(`p_discard_key` 참)로 한 번 더 불렀다(`OrderGateway.resolveFailedSubmit`). 첫 호출이 요청 A의 키를 지우고 커밋한 뒤 응답만 잃었으면 A의 키는 이미 없다. 두 호출 사이에 같은 내용·같은 `client_order_id`의 요청 B가 오면 B의 `claimKey`는 INSERT에 성공해 키를 새로 넣고 접수한다. 둘째 호출은 어드바이저리 락에서 B의 append를 기다린 뒤 A의 주문 id로 저널을 보고 없으니, 해시가 같고 공유 표시가 빈 B의 키를 지우고 DISCARDED를 돌려준다(`V47__order_intake_failure_resolution.sql:76-83`). 그러면 B의 커밋된 주문이 키를 잃어 B의 정당한 재전송이 대조 불가 `409 duplicate_client_order_id`로 끝난다. A가 받은 확정 미접수 `429`의 약속(같은 키로 다시 보내면 새 접수 하나로 수렴)도 깨져 A의 재전송이 같은 `409`를 받고, 클라이언트가 새 키로 다시 내면 이중 주문이다. 4-3과 5절의 '둘째 호출은 키가 없어 ABSENT'는 두 호출 사이에 같은 키의 요청이 없을 때만 맞았다. V47은 '공유 표시가 없는 이 해시의 키 행은 이 요청이 넣은 것'이라는 전제에 기대는데, 다시 부를 때는 이 전제가 성립하지 않는다. S4 코드는 판정 실패를 삼키고 다시 부르지 않았으므로 S4b가 만든 경로다. submit 전에 끝난 요청의 키를 finally에서 정리하는 호출도 같은 반복을 탔다.

바꾼 것: 다시 부르는 판정은 키를 지우지 않는다. 둘째 호출에는 `p_discard_key`를 거짓으로 준다. 둘째 호출은 A의 주문 id로 저널만 보고, 있으면 종전처럼 커밋 회수이고 없으면 `NOT_JOURNALED`라 확정 미접수를 주장하지 않는 `409 order_in_progress`다. 검토가 낸 두 선택지(키를 지우지 않는 판정으로 다시 부르기, 둘째 호출에서 DELETE를 보내지 않기)는 같은 동작이라 V47의 기존 인자를 쓰는 쪽을 골랐다. 키 행에 넣은 요청의 표식(예: 채번한 주문 id)을 더해 둘째 호출도 자기 키만 지우게 하는 안은 고르지 않았다. 새 마이그레이션과 V47 새 판이 필요하고, 얻는 것은 드문 장면의 응답을 `409`에서 `429`로 바꾸는 것뿐이다. 첫 호출이 DB에 가지 않았으면 A의 키가 고아로 남는다. 고아 키는 V47 머리말이 적은 대로 무해하다. 재전송이 그 키로 대조한 뒤 저널 실재로 잔고 가드 적용을 가른다. P1의 in-flight 예약은 A의 명령이 저널에 없다는 판정이므로 종전처럼 곧바로 푼다. `IntakeOutcome.NOT_JOURNALED`의 설명을 두 경우(공유 표시를 채운 요청, 끊긴 판정을 다시 본 요청)로 고쳤다. 계수기와 로그는 그대로이고 이 경우는 `connection|409`와 '확인 호출=2' WARN으로 보인다.

시험: `IntakeOutageRetryIntegrationTest`에 (e)를 더했다. P2 한 노드와 실제 게이트웨이에서 A의 append를 저널 전에 롤백 실패로 끝내고, 판정 이음매가 첫 호출에서 실제 V47(A의 키 삭제와 커밋)을 부른 뒤 같은 내용의 B를 같은 키로 끝까지 접수시키고 연결 끊김을 던진다. B는 `201`, A는 `409 order_in_progress`(`connection|409` 1, `connection|429` 0)이고, 두 판정의 키 삭제 인자는 참·거짓이며, B의 키 1행이 남고, B의 재전송은 B의 주문 id의 재생이며 NEW와 예약은 1행이다. `OrderGatewayOutageMappingTest.verdictIsCalledOnceMoreAfterALostConnection`은 첫 판정이 DB에 가지 않은 장면이라, 둘째 판정이 `NOT_JOURNALED`로 `409`이고 키 1행(고아)이 남도록 기대를 고쳤다. 판정 이음매에 실제 판정을 부른 뒤 예외를 던지는 시험 도우미(`OrderGatewayTestAccess.interceptAfterResolutions`)를 더했다.

### I2. 스냅샷 시작 재생의 폴백 생략을 확인하는 시험이 없다 (medium)

지적대로다. `recoverFromSnapshots`의 둘째 catch(스냅샷 시작 재생)에서 획득 실패·연결 끊김이면 폴백하지 않는 검사를 지워도 실패하는 시험이 없었다. `SnapshotHookWiringIntegrationTest`의 사례 가운데 DB 장애를 넣는 곳은 적재의 (a) 하나였고, 둘째 catch만 타는 (c)는 57014라 검사가 있든 없든 폴백한다. `RebuildOutageRetryIntegrationTest`의 `PartitionNode`는 스냅샷 원천이 없는 파티션 배선이라 스냅샷 경로를 타지 않는다. 12-2의 변이 표는 '스냅샷 폴백 생략을 없앤다'가 새 사례에 걸린다고 적었지만 그때 돌린 변이는 적재 쪽이었다. 스택에서도 E2가 탄 것은 스냅샷 적재의 획득 실패였다(13-1). 계약(DB 설계 §5.5 STEP1 v2.56, 하네스 검증설계 S4b 패치 노트 (1))과 코드는 맞았고 시험만 비어 있었다.

바꾼 것: 같은 시험에 (d)(e)를 더했다. 실제 스냅샷 원천으로 적재는 성공시키고, 재생 서비스의 첫 대조가 (d) `CannotGetJdbcConnectionException`, (e) 롤백 실패(`TransactionSystemException`, 앱 예외 08006)를 던진다. 두 경우 모두 복구가 그 예외 그대로 끝나고 폴백 계수 0, `recovery_runs`의 새 행 0이다. (c)와 같은 재생 서비스 하위 클래스는 시험 도우미(`firstVerifyFails`)로 묶었다. 운영 코드는 바꾸지 않았다.

### 14-1. 검증

변이 넷을 돌렸고 모두 새 단언이 실패했다. 변이마다 원본을 보관하고 치환해 대상 시험을 돌린 뒤 되돌렸고, 되돌린 뒤 운영 코드 305개 파일의 해시가 변이 전과 같았다.

| 변이 | 실패한 시험 |
|---|---|
| 다시 부르는 판정도 키를 지운다(I1 전 동작) | `IntakeOutageRetryIntegrationTest` (e)에서 A가 `429`, `OrderGatewayOutageMappingTest.verdictIsCalledOnceMoreAfterALostConnection`에서 `429` |
| 시작 재생의 폴백 생략을 지운다(둘째 catch) | `SnapshotHookWiringIntegrationTest` (d)가 폴백해 예외 없이 끝남 |
| 적재의 폴백 생략을 지운다(첫째 catch) | 같은 시험 (a) |
| 폴백 생략에서 연결 끊김을 뺀다(획득 실패만 생략) | 같은 시험 (e) |

첫 변이에서 A가 `429`를 받았다는 것은 둘째 판정이 DISCARDED, 곧 해시가 같고 공유 표시가 빈 키 행을 지웠다는 뜻이다. A의 키는 첫 판정이 이미 지웠으므로 지워진 것은 B의 키다.

관련 시험 열두 클래스의 62개(`IntakeOutageRetryIntegrationTest`, `OrderGatewayOutageMappingTest`, `SnapshotHookWiringIntegrationTest`, `OrderGatewayReserveMappingTest`, `OrderIntakeFailureResolutionTest`, `OutageResponseChainTest`, `SameKeyContentionIntegrationTest`, `ContentionDemandChainTest`, `IntakeContentionMetricsTest`, `RecoveryRunClassificationTest`, `RebuildOutageRetryIntegrationTest`, `PromotionRecoveryIntegrationTest`)가 모두 통과했고, 바꾼 세 시험 클래스는 `--rerun`을 포함해 세 번 통과했다. 이 라운드의 마지막 서버 전체 빌드(`server`에서 `.\gradlew.bat build --console=plain --continue`, 2026-09-25 09:15~09:33, 17분 28초)가 성공했다. app 모듈은 168개 클래스의 시험 870개가 모두 통과했고 실패·건너뜀은 0이다. 새 사례를 기존 시험 메서드 안에 더했으므로 개수는 13-3과 같다. engine 모듈은 최신 판정이라 `:engine:test --rerun`으로 67개가 모두 통과했다. 빌드 로그, engine 재실행 로그, 결과 XML에 `localhost:5432`·`localhost:55432`(127.0.0.1 포함) JDBC 주소는 0건이다.

폐기용 스택은 다시 돌지 않았다. I1이 바꾼 경로, 곧 연결이 끊긴 V47 판정을 다시 불러 판정이 난 경우는 13절의 주행에서 나지 않았다. 접수 계수기를 남긴 주행(X1 두 번, X6, B1 두 번, E2 여섯 번, E4~E7·COLD, E6 재실행)에서 `connection|409`·`connection|429` 증가는 모두 0이었고, 장애 실험(B1, E2, E4~E7)의 접수 경합 계수기 증가도 0이었다. 판정이 끝내 실패한 두 건(B1 첫 주행과 다시 구운 이미지의 E2, 각각 `connection|503` 1)은 판정이 DB에 닿지 못한 경우다. E1 주행의 증거에는 이 계수기가 없다. I2는 시험만 더했다. 그래서 13절의 판정은 바뀌지 않지만 그 이미지(`s4b2ref`)는 이 보정 전 코드다. 다음 스택 주행은 이 보정을 넣은 이미지로 한다. 그 주행이 15절이다.

## 15. 보정 이미지의 재주행 (2026-09-25)

14절의 보정까지 넣은 코드로 7절의 실험을 다시 돌았다(09:40~11:13 KST). 증거는 [`harness/evidence/pml-S4b-20260925-0940/`](../../../harness/evidence/pml-S4b-20260925-0940/README.md)에 있다. 이미지는 14-1의 마지막 서버 전체 빌드가 만든 bootJar(SHA-256 `39aabcbd…`, 09:15:32, 마지막 운영 코드 변경보다 뒤)로 한 번만 구운 `sha256:dcf0e6a2…`이고, 7-0이 정한 두 태그에 더해 참조 태그 `exchange-chaos-app:s4b3ref`를 붙였다. 앞 이미지(`s4b2ref`, `s4bref`, `s4r1ref`, `s3ref`)와 `:local`은 그대로 두었다. Flyway는 V0~V53이고 새 마이그레이션은 없다. DB 모드는 모든 실험에서 P2, 파티션은 `matching-engine` 하나다.

실행기는 13절의 `run-s4b-gate.ps1`을 복사해 참조 태그와 로그 폴더(`%TEMP%\pml-s4b3-logs`)만 바꿨다. 단계 순서와 E2 멈춤 규칙은 `drive.ps1`이 7-1·7-2대로 따랐고, `step.ps1`이 단계마다 앞뒤로 Testcontainers 컨테이너 수와 CPU 표본을 남겼다. 7-0의 수칙을 지켰다. 보존 볼륨 두 개의 생성 시각은 시작·끝과 모든 단계 앞뒤에서 같았고, `exchange-chaos` 프로젝트에는 아무 명령도 하지 않았으며, 끝에 `-p exchange-pml` 스택만 `down -v --remove-orphans`로 지웠다. 이 세션은 실험 동안 Gradle과 Testcontainers를 돌리지 않았고 단계 앞뒤 Testcontainers 컨테이너 수는 모두 0이었다. 도커·앱·부하 도구 밖의 바쁜 프로세스는 X1·B1·E2와 E1 첫째·둘째에서 0, 그 뒤 regress까지 1개였고, 10:51부터 다른 작업의 추론 서버가 CPU를 크게 썼다. 시간 축에서만 FAIL한 실험이 없어 7-3의 잡음 재실행은 하지 않았다.

| 실험 | 판정 | 요지 |
|---|---|---|
| Y1 X1 P2 봇 부하 10분 | PASS | 판정 축 25개 참. P2 epoch NEW = 예약 21,441(뒤이은 X6 포함), 체결 15,522, 리더 epoch 6 유지, 연결 최대 38. LB 접근 로그 34,848줄·프로메테우스·노드 누계의 주문 `500` 0, 접수 불가 계수 증가 0, `recovery_runs` 성공 6·불일치 0 |
| Y1 X6 a~d | PASS | c에서 같은 키 동시 요청 20건이 모두 `409 order_in_progress`, 재전송은 `200` 6·`409` 4·`503` 0. 네 변형 모두 LB `no live upstreams`·`502` 0, 리더 강등 0, 정산 소진 0 |
| Y6 B1 흔들림 500ms | 측정 3/3, 안전 축 참 | 세 번 모두 holder·epoch 유지. 리더 `503`은 인증 필터 2와 체결 조회 1, 인증 필터 1, 단건 취소 1과 체결 조회 1. LB `502` 105·80·54줄로 각 약 2초 |
| Y2 E2 DB 절단 1~5회 | PASS 5/5 연속 | RTO 5.868·11.256·12.196·5.814·6.583초, epoch 5 -> 6, rpo_records 0, 구 epoch 쓰기 0 |
| Y3 E1 PG 지연 1~5회 | PASS 5/5 | 정산 재시도(55P03) 3·6·3·4·6회(셋째·다섯째는 57014 1회 더), 소진 0, 두 층 강등 0, 획득 `lock_timeout` 0, epoch 5 유지 |
| Y4 E4·E5·E6·E7·COLD | 모두 PASS | RTO 5.143·5.963·6.372·10.499초, COLD 첫 체결 12.225초. E4의 옛 리더는 12.6초 만에 `FOLLOWER`로 합류했고 E6의 옛 리더는 `stopped`로 적혔다. E7의 응답을 쓰지 못한 요청 21건은 WARN이고 '처리되지 않은 예외'는 0줄이다 |
| Y5 X7 처리량(선택) | NOT RUN | 호스트가 조용하지 않았다(전체 CPU 표본 최대 82%). 7-2 Y5대로 S7 전에 조용한 호스트에서 잰다 |
| E3 | N/A | 7-2대로 돌리지 않았다 |

모든 실험에서 자산·원장 불변식 위반, 구 epoch 쓰기, 세 원천의 주문 `500`, `recovery_runs` 불일치, 두 api 로그의 '처리되지 않은 예외'·'인증 처리 실패로 500'이 0이었다.

E2는 원인 B 경로를 다시 탔다. 첫째와 넷째는 DB가 돌아온 순간 옛 리더의 접수 append가 fencing 거부(P0001)를 받아 `503` 1건씩(`fencing|503`)으로 끝났다. 둘째와 셋째는 절단 중 라이터가 롤백 실패(`TransactionSystemException`, 강등 기록 `sqlstate=08006(앱 예외)`)로 치명이 된 뒤 재구성이 스냅샷 적재의 획득 실패로 다섯 번 끝났고, DB가 돌아온 뒤 `FOLLOWER`로 돌아왔다. 그 다섯 번은 `recovery_runs`에 행을 남기지 않았다. 재생 도중 DB가 끊겨 '재생이 판정 없이 끝났다'를 남기는 원인 A 분기는 다섯 번 모두 타지 않아 7-2 Y2의 규칙대로 다섯 번을 모두 돌았다. 창 안 LB 줄은 `502` 15·69·116·24·19, `503` 1·1·7·3·4이고 `500`·`504`는 0이다.

E1에서는 정산 재시도 소진과 라이터 치명이 재현되지 않았다. 재시도는 모두 F1 한도 안에서 흡수됐다. 그래서 4-6의 판단표를 다시 적용할 일이 없었고 이미지도 다시 굽지 않았다. 다섯 번 가운데 넷은 13-4와 같이 주입 전·중·후 권위 run의 한두 번에서 C13A 1~3행을 ERROR 로그로 남겼고, 게이팅 판정은 모두 참이었다.

B1은 13-1과 달리 세 번 모두 리더를 유지해 처음 계획한 500ms로 세 번을 모두 쟀다. 라이터 치명은 한 번도 나지 않았고 접수 불가·미해결·커밋 회수·접수 경합 계수의 증가는 모두 0이었다. 곧 V47 판정 실패는 없었다. 셋째의 단건 취소 `503`은 11절 R7이 적은 경로(단건 취소의 DB 연결 장애)가 스택에서 한 건 난 것이다. 리더 `503` 한두 건 뒤 LB `502`가 약 2초 이어진 것은 13-1의 측정 세 번과 같은 크기다.

I1이 바꾼 경로는 이 주행에서도 나지 않았다. 계수기를 남긴 모든 실험(X1, X6, B1, E2 다섯 번, E1 다섯 번, E4~E7·COLD)에서 `connection|409`·`connection|429` 증가는 0이고, E1 다섯 번은 접수 불가 계수 전체의 증가가 0이다. 원인 A의 재생 도중 절단 분기와 I1 경로는 시험과 변이로만 확인한 상태다.

그래서 7-2의 관문(Y1 X1과 X6, Y2 E2 연속 세 번, Y3 E1 세 번, Y4 S4 관문 재확인)을 보정 이미지로 통과했다. 로컬 Docker 단일 호스트의 판정이고 AWS의 장애나 처리량을 대신하지 않는다.

## 16. 문서 단계와 남은 것 (2026-09-25)

문서 단계에서 ADR 0034(상태, 현재 적용, 「S4b 진행」, 결정한 값 11의 DB 연결 장애 응답, 단계 표), [단계 계획](마켓분할_리더_단계계획.md) S4 절, 0099, 로드맵, 코드 지도, [09-25 측정](../../measurements/20260925.md), S4 설계 13-3의 이 문서 연결을 고쳤다. DB 설계 §3 표의 V53 행과 §3.8, 하네스 검증설계 계약 핀에 남아 있던 V53의 '커밋 전'은 커밋 `48629d5d`로 고쳤다. 계약 내용은 바뀌지 않는다. 그리고 S4b 코드·시험·하네스·증거(`pml-s4b-probe-20260925`, `pml-S4b-20260925-0456`, `pml-S4b-20260925-0940`)와 함께 작업 브랜치에 한 커밋으로 남겼다. main은 병합하지 않았다.

남은 것은 다음과 같다. 다음 단계는 S5(REST 라우팅)다.

- 사용자 확인. 10절의 여덟 가지가 그대로 남았다. 1·3의 근거는 B1 두 주행이 잰 비용이다. 리더의 쓰던 연결만 끊긴 순간 리더 `503`은 한두 건이었고 LB `502`가 약 2초 이어졌다(측정 여섯 번). 참조 태그 `s4b3ref`와 실행기의 `drive.ps1`·`step.ps1`도 AI가 정한 하네스 절차다.
- B1 첫 주행에서 드러난 라이터 치명의 비용(13-5). 이번 주행에서는 나지 않았지만, 흔들기 순간 라이터가 연결을 쓰고 있으면 약 7~9초 쓰기가 `502`다. 줄일지는 S5 인그레스 재검토 전에 판단한다.
- 원인 A의 재생 도중 절단 분기와 I1 경로는 스택 증거가 없다(15절).
- F2와 정산의 fencing 재확인 계약 어긋남(9절)은 그대로다.
- X7은 S7 전에 조용한 호스트에서 다시 잰다. E3은 N/A다.
- main 병합의 Flyway 순서(V47_1)는 S4 설계 9절 그대로다.
