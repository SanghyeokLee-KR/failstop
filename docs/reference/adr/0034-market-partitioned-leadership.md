# ADR 0034. 매칭 리더십을 마켓 파티션 두 개로 나눈다

- 상태: 채택 · 단계 진행 중 (S0·S1)
- 날짜: 2026-09-23
- 현재 적용: 실행 중인 구조는 여전히 `resource='matching-engine'` 리스 행 하나를 쥔 단일 리더(P1)다. 이 결정은 단계마다 관문 시험을 통과한 뒤에만 효력이 생긴다. 접수 시점 예약 모드(P2)와 파티션 리더 모드(P3)는 아직 구현하지 않았고 측정한 결과도 없다.
- 결정자: 사용자가 방향을 정했다. 매칭 리더십을 마켓 단위로 나눠 app-1과 app-2에 두되 자산 보존을 깨지 않는다. 아래 「결정한 값」의 20개 항목은 사용자가 하나씩 고른 값이 아니라, 작업을 맡은 AI 세션이 [단계 계획](../architecture/마켓분할_리더_단계계획.md)의 권장 기본값으로 정한 것이다. 사용자가 다른 값을 고르면 해당 항목과 계획을 함께 고친다.
- 대체 범위: 각 항목은 적은 단계부터 효력이 있다. 그 전까지는 원래 ADR의 문장이 현재 동작을 설명한다.
  - [ADR 0002](0002-db-leader-lease-single-writer.md) 결정 절의 마켓별 리스 분할 금지(docs/reference/adr/0002-db-leader-lease-single-writer.md:29)와 대안 절의 마켓별 `leader_lease` 행 기각(같은 파일:53). P3 전환(S7)부터다.
  - [ADR 0022](0022-market-partitioned-command-journal.md)의 전역 `acceptGate`(docs/reference/adr/0022-market-partitioned-command-journal.md:379, 10-7 :1067-1069)와 9절 2·3의 노드 단위 재적재·강등 전제(같은 파일:895-896). 파티션 단위 Java(S4)부터다.
  - ADR 0022 6-2의 "C22 시간 축 팔을 마켓 안 팔로 대체하지 않는다"(같은 파일:547)와 10-8(:1071). P3 구간에서만 뒤집는다. P1·P2 구간의 판정은 그대로다.
  - [DB 설계](../contracts/백엔드_DB_설계.md) v2.9의 "예약은 정산에서 한다" 변형(docs/reference/contracts/백엔드_DB_설계.md:2872-2873). 정본 §5.1의 원자 커밋(같은 파일:1876-1891)으로 되돌린다. P2 전환(S3)부터이며, 계약 문서는 S3 코드보다 먼저 고친다.
- 유지하는 결정: ADR 0002의 "강등 시 리스 즉시 반납 기각"(0002:56). 파티션 인계도 TTL 만료로만 한다. DB 자동 페일오버는 선택·구현하지 않았다는 전제와 "매칭 엔진·DB를 ASG로 자동 확장하지 않는다"는 규칙(AGENTS.md)도 유지한다. 노드 수는 2로 고정하고 자동 증감하지 않는다.
- 선행: ADR 0022 명령 대역 래치(V44), [ADR 0026](0026-leader-lease-key-share-lock.md) 리스 행 FOR KEY SHARE(V41).
- 근거 자료: 설계안 두 개와 적대 검토 두 건을 대조해 만들었다. 네 원문은 저장소에 두지 않았고, 채택한 결과와 이유를 이 문서와 단계 계획에 옮겼다. 수치는 [09-14 측정](../../measurements/20260914.md)과 harness/evidence/accept-concurrency-20260913/README.md를 인용했다. 인용한 코드 줄 번호는 main `5e59ff0` 기준이다. 파티션 모드 실측은 없다.

## 배경

지금은 `leader_lease`의 `resource='matching-engine'` 한 행을 쥔 JVM 하나가 12개 마켓을 모두 쓴다(V1__init.sql:693-706). ADR 0022가 그 JVM 안의 접수 락과 채번을 마켓별로 나눴지만 리스와 승격 단위는 노드 하나로 남겼다(0022:379, :895-896).

리스를 둘로 나누는 데 가장 큰 걸림돌은 리스가 아니라 자금이다. 권위 있는 차감인 `reserve_funds`는 저널 커밋, 곧 ACK 뒤의 정산 트랜잭션에서 일어난다(SettlementService.java:43-46). ACK 전의 잔고 확인은 게이트웨이의 사전 가드와 JVM 메모리의 in-flight 누계(`InflightReserves`)가 맡는다. 코드 주석이 이 장치를 "단일 노드 전제"라고 적었다(OrderGateway.java:28-32, InflightReserves.java:13-14).

두 JVM이 서로 다른 마켓에서 같은 사용자의 같은 자산을 동시에 쓰면 두 주문이 모두 가드를 통과해 ACK된다. 늦게 정산되는 쪽이 `chk_balance_nonneg`(V1__init.sql:112-120)에 걸려 23514로 실패한다. 라이터는 어떤 sink 예외든 fatal로 처리하고(SingleWriterLoop.java:226-244) 노드를 강등한다(LoopFatalHandler.java:28-31). 명령은 이미 저널에 있으므로 다음 소유자가 재적재해도 같은 자리에서 실패하고 그 마켓은 영구히 멈춘다(0022:476). 원장 합과 CHECK는 DB가 지키므로 C01·C02는 깨지지 않는다. 대신 ACK 계약과 C13A, 가용성이 깨진다. 그래서 자금 예약의 권위를 DB로 옮기지 않은 채 리스만 나누는 안은 받아들일 수 없다.

## 결정

1. 파티션은 두 개로 고정한다. `leader_lease`에 `matching-p0`, `matching-p1` 두 행을 새 마이그레이션이 미리 넣는다. 마켓과 파티션의 대응은 추가 전용 표 `market_lease_partition(market_id PK → markets, resource → leader_lease)`에 둔다. 배정 값은 아래 「결정한 값」 3이다. UPDATE·DELETE·TRUNCATE는 V44의 `journal_cutover`처럼 트리거로 거부한다. 이 판에서는 마켓을 다른 파티션으로 옮기지 않는다. 마켓을 추가하면 기존 시드 트리거(V40:161-169, V36:87-89) 옆의 SECURITY DEFINER 트리거가 대응 행도 같이 넣는다. 대응이 없는 마켓은 쓰기가 거부된다.
2. 선호 소유자는 app-1이 p0, app-2가 p1이다. 선호는 힌트이고 권위는 리스 획득 하나뿐이다. 비선호 파티션은 부팅 직후 2×TTL, 그 뒤에는 만료를 볼 때마다 1×TTL을 기다린 뒤에만 획득을 시도한다. 한 노드가 죽으면 살아 있는 노드가 TTL 만료 뒤 두 파티션을 모두 쥔다. 자동으로 되돌려 주지 않는다. 되돌리기는 운영자가 정지 절차로 하고 리스는 반납하지 않고 만료시킨다(ADR 0002:56 유지).
3. epoch는 전역 시퀀스 하나(`leader_epoch_seq`, CACHE 1, NO CYCLE)에서 받는다. 마이그레이션이 `setval(max(leader_lease.epoch))`로 시드한다. 새 획득 함수와 CREATE OR REPLACE한 2인자 `try_acquire_leader`는 모두 `epoch = GREATEST(nextval, epoch + 1)`을 쓰고 추가 전용 `lease_acquisitions(epoch PK, resource, holder, protocol, acquired_at)`에 한 행을 남긴다. 그러면 epoch는 파티션 사이에서 유일하고 파티션 안에서 단조다. 단, 이 유일성은 DB 타임라인 하나 안에서만 성립한다.
4. 쓰기 권한 검사는 DB가 기록할 행의 `market_id`에서 리스 행을 찾는다. 앱은 resource를 넘기지 않는다. 함수 `lease_resource_for_market(market_id)`는 프로토콜 P3이면 대응 표의 값을, 그 전이면 `'matching-engine'`을 돌려준다. 저널 append는 market_id를 한 번만 바인딩하는 INSERT…SELECT로 바꾼다(지금은 CommandJournalAppender.java:35-44에서 resource를 하드코딩한다). 정산 머리말, 스냅샷 저장, 이벤트 저널 시험 경로도 같은 도출을 쓴다. 판정 함수는 V41처럼 행을 조건 없이 FOR KEY SHARE로 잠근 뒤 판정한다(V41__lease_key_share_lock.sql:32-52 형태).
5. 자금 예약은 저널 append 트랜잭션 안에서 한다. 새 머리말 함수는 순서대로 다음을 한다. 리스 행 KEY SHARE와 리더 판정, NEW 멱등 판정(V44:50-66 그대로), 예약액 계산과 `reserve_funds`, 채번, 예약 기록 `order_reservations` INSERT다. 예약액은 checksum이 덮는 payload 값과 카탈로그 scale로 DB가 계산하고, 앱이 넘긴 값은 대조에만 쓴다. 잔고가 부족하면 23514로 트랜잭션 전체가 롤백되어 저널 행과 채번이 남지 않고, 게이트웨이는 400 `insufficient_funds`를 돌려준다. 정산은 예약된 주문에 대해 `reserve_funds`를 다시 부르지 않고 `order_reservations.amount`를 권위로 쓴다.
6. 모드 전환은 DB의 단일 행 `leader_protocol`과 추가 전용 이력으로 한다. P1은 오늘과 같다. P2는 단일 리더에 시장 도출 fencing과 접수 시점 예약을 켠 상태다. P3은 파티션 리더다. 전환 함수는 소유자 계정만 실행하고, 정지 창에서만 부른다. 함수는 먼저 모든 리스 행을 FOR UPDATE로 잠가 진행 중인 쓰기가 끝나기를 기다린다. 그 뒤에 선행 조건을 검사하고, 모든 리스 행을 새 epoch로 퇴역시켜 이전 모드의 쓰기를 펜싱한다. P2 이상에서는 2인자 레거시 함수가 거부하거나 NULL을 돌려준다. 앱 설정은 참여 허용 스위치일 뿐이고 모드를 정하지 않는다(0022:17과 같은 원칙).
7. 게이트, 승격, 강등, 팔로워, 좀비 판정을 파티션 단위로 바꾼다. 부팅은 늘 팔로워로 시작하고 승격 경로는 런타임 경로 하나로 통일한다. 복구 대상은 획득한 파티션의 마켓 M(P)로 한정하고, 대상 밖 샤드를 빈 엔진으로 덮는 코드(WriterRouter.java:125-132)는 지운다. 팔로워 엔진은 마켓별 인스턴스로 나눈다. 파티션 fatal은 그 파티션만 멈추고 fresh 재구성으로 팔로워에 복귀시키며, 반복 fault에는 차단기를 둔다. VM Error만 노드 전체 강등으로 남긴다.
8. 노드 사이의 잠금 결합이 강등으로 번지지 않게 한다. 정산은 55P03, 57014, 40P01, 40001을 제한 재시도한다. 재시도 제한을 넘긴 일시 오류와 재시도 대상이 아닌 오류는 종전처럼 fatal이다. 후자는 fencing 거부와 무결성 위반, 커밋 여부를 알 수 없는 연결 오류(08xxx), 계약 위반(`IllegalStateException`)이다. 예약 단계에는 짧은 `lock_timeout`을 걸어 초과를 503으로 돌린다. 앱 역할의 `idle_in_transaction_session_timeout`을 TTL 이하로 둔다. P3 이후의 체결 수수료는 파티션별 FEE_INCOME 샤드로 나눈다(지금은 K=1 박제, SettlementService.java:50-52).
9. 라우팅은 서버 안전성의 근거가 아니다. 잘못 보낸 요청은 503이나 fencing 거부로 끝난다. 웹과 봇은 `X-Exchange-Market` 힌트를 보내고 nginx는 파티션별 upstream 두 개로 보낸다. 서버는 인증 필터보다 앞에서 소유권을 검사해 비소유면 nonce·레이트리밋·멱등키·예약을 건드리기 전에 503과 Retry-After를 낸다. WebSocket은 외부 STOMP 브로커 릴레이가 필요하며, 채택 전까지 파티션 모드는 봇과 REST 부하 시험에만 쓴다.

## 결정한 값

계획이 코드 전에 정하라고 남긴 20개 항목의 값이다. 번호는 [단계 계획](../architecture/마켓분할_리더_단계계획.md)의 "결정 N"과 같다.

1. 범위. 2026-10-16 발표 전에는 S0과 S1을 한다. 여유가 있으면 S2를 로컬에서 진행한다. S3 이후는 발표 뒤에 한다.
2. 목적. 앱 노드 하나가 죽어도 다른 파티션의 마켓은 계속 접수하게 하는 가용성과, 앱 CPU를 두 노드에 나누는 것이다. 처리량 증가는 기대하지 않는다. 측정된 병목이 DB 커밋이기 때문이다(아래 근거 절).
3. 마켓 배정. 명시적인 정적 표로 둔다. 파티션은 p0(선호 app-1)과 p1(선호 app-2) 두 개다. 봇이 도는 세 마켓(application.yml:326-340)은 두 파티션이 모두 봇 부하를 받도록 나눠 KRW-BTC(1)와 KRW-XRP(4)는 p0, KRW-ETH(2)는 p1에 둔다. 나머지 아홉 마켓은 market_id 순서로 p1부터 번갈아 둔다. 그러면 p0은 1·4·5·7·9·11(BTC, XRP, DOGE, TRX, DOT, AVAX), p1은 2·3·6·8·10·12(ETH, SOL, ADA, LINK, MATIC, ATOM)로 여섯 개씩이다. id와 심볼은 InitialMarketCatalog.java:34-45를 따른다. S2 마이그레이션이 이 표를 시드한다.
4. 인계. TTL 만료로만 한다(ADR 0002:56 유지). 자동으로 선호 노드에 되돌려 주지 않는다. 재배치는 운영자가 정지 절차로 한다. 비선호 노드의 파티션 참여를 끄고 그 리스가 만료된 뒤 선호 노드가 획득하게 한다.
5. 마켓당 접수 천장 손실. S3에서 같은 날 같은 스택으로 P1과 P2를 재서 20퍼센트 이하이면 받아들인다. 넘으면 대안의 2단계 예약안을 고르고 S4로 넘어가지 않는다.
6. 예약 기록. `order_reservations` 표를 쓴다.
7. 예약액 계산. DB가 계산한다. 필요한 자산·마켓 scale 열의 위치는 S2에서 확인하고, 없으면 새 마이그레이션으로 카탈로그에 scale 열을 더한다. 적용된 Flyway 파일은 고치지 않는다.
8. P2에서 P1로 되돌리기. 정지 창에서 미정산 NEW가 0건일 때만 허용한다. 이 조건이 안전하다는 것을 시험 R1으로 확인하기 전에는 비가역으로 취급한다.
9. 명령 대역 래치. 폐기용 Docker DB에만 건다. 학교와 개인 AWS DB는 사용자가 승인한 뒤에 판단한다.
10. 수수료 계정. P3부터 FEE_INCOME을 파티션별 샤드로 나눈다.
11. 타임아웃과 재시도. 앱 역할의 `idle_in_transaction_session_timeout`을 리스 TTL 이하로 둔다. 학교 DB 설정은 지금 10초(deploy/linux/postgresql/exchange.conf:35)이고, 리스 TTL은 기본 10초·카오스 6초다(application.yml:140-141 주석). 정산은 55P03·57014·40P01·40001을 제한 재시도한다. S1에서 정한 재시도 제한은 첫 시도를 포함해 4회이고, 첫 시도 시작부터 5초가 지나면 새 시도를 열지 않으며, 대기는 50~100, 100~200, 200~400밀리초의 무작위 값이다(DB 설계 §5.4 v2.51). 접수 경로의 마켓 락 대기 상한(`exchange.engine.accept-lock-wait`)과 append 트랜잭션의 `lock_timeout`(`exchange.engine.accept-lock-timeout`)은 S1에서 설정만 넣고 기본값을 0(끔)으로 둔다. 켜면 초과한 요청 하나가 503을 받고, `max_fails=1`인 nginx 업스트림(harness/nginx/nginx.conf:21, deploy/nginx-edge.conf.template:54-55)이 그 503으로 노드를 2초 빼 한 마켓의 정체가 모든 마켓의 쓰기 중단으로 번진다. 그래서 값은 줄 머리 막힘을 실제로 끊어야 하는 S3·S4에서 인그레스 설정과 함께 정한다.
12. 파티션 fatal. 그 파티션만 멈추고 fresh 재구성으로 팔로워에 복귀시킨다. 같은 fault가 반복되면 차단기가 막는다. 차단기의 횟수와 시간 창은 S4에서 정한다.
13. WebSocket. 외부 STOMP 릴레이 채택을 미룬다(S6 보류). 그때까지 파티션 모드는 REST와 봇 부하에만 쓴다.
14. nonce와 레이트리밋. 둘 다 노드 로컬로 둔다. 노드를 넘는 NEW 재전송은 client_order_id 멱등이 막는다. 레이트리밋 한도는 S5에서 다시 본다.
15. 마켓 없는 cancelAll. 서버가 파티션별로 나눠 보낸다. 관리 경로의 취소도 같다. 한 파티션이 응답하지 못했을 때의 응답 계약은 S5에서 API 설계에 먼저 적는다.
16. C14'의 출금 항. 출금이 LOCKED를 쥐는 `transfer_requests` 상태 집합(V14__transfer_requests.sql:29-30)을 확인한 뒤 더한다.
17. `leader_lease` 직접 UPDATE 권한. S2에서 함수가 모든 경로를 덮은 뒤 app_role에서 회수한다(V1__init.sql:805).
18. DynamoDB 리스 공급자. 파티션 모드에서는 쓰지 못하게 막는다.
19. 검증 장소. 로컬 Docker에서 먼저 하고 개인 AWS에서 다시 한다. 학교는 P1로 유지한다. 학교에는 app-2를 만들지 않기로 했다(docs/ROADMAP.md:77).
20. 문서. 이 ADR, 0002·0022의 대체 표시, 0099, DB·API·하네스 계약의 해당 절을 고친다. AGENTS.md의 "단일 리더 매칭 엔진" 문구는 이번에 고치지 않고 사용자에게 물은 뒤 정한다.

## 근거

- 두 노드의 append가 같은 잔고 행을 다투면 `post_ledger_transaction`이 잔고 행을 account_id 오름차순 FOR NO KEY UPDATE로 잠그고 한 문장 UPDATE로 차감한다(V38__ledger_balance_update_index_scan.sql:98-127). 뒤에 온 트랜잭션은 앞 트랜잭션의 커밋을 기다린 뒤 최신 값에서 계산해 CHECK(V1__init.sql:112-120)에 걸린다(추론, READ COMMITTED 재평가. 격리 수준을 바꾸는 설정은 찾지 못했다). 노드를 가리지 않는 판정 지점이 DB의 한 곳이 된다.
- 체결 정산의 음수 leg는 매도자와 매수자의 LOCKED뿐이다(SettlementService.java:446-456). 주문별 상한은 `orders.locked_amount >= 0`(V1__init.sql:173)이다. 접수 시점에 LOCKED가 채워져 있으면 정산 시점 23514는 버그가 없는 한 도달하지 않는다(추론, 시험으로 고정). 그러면 마켓 사이 정산 순서가 음수 잔고와 무관해져 0022:476-491의 "한 노드가 전 마켓 크래시 창을 병합 재적재" 전제가 자금 측면에서 필요 없어진다.
- 출금도 `reserve_funds`를 직접 부르므로(TransferService.java:94-97, 299-306) 같은 CHECK에서 판정된다. 단일 리더에서도 열려 있던 "가드 통과 → 출금 → 정산 23514" 경로가 닫힌다.
- resource를 앱이 넘기면 두 노드가 같은 마켓의 파티션을 다르게 알 때 각자 다른 리스로 통과하는 조용한 split-brain이 된다. DB가 기록할 행의 market_id에서 찾으면 이 경우가 구조적으로 없다.
- 리스 행마다 KEY SHARE가 따로 걸린다. `uq_leader_lease_fencing_key(resource, holder, epoch)`가 이미 resource를 포함하므로(V41:27) "승격은 그 행의 진행 중 쓰기를 기다린다"는 논증(V41:7-11)이 파티션 행마다 독립으로 성립한다.
- 주문 멱등은 노드를 넘어도 유지된다. client_order_id가 필수이고(OrderGateway.java:83-86) 어드바이저리 락과 저널 조회가 공유 DB에서 마켓 없이 돈다(V44:50-66, 0022:381-386).
- 전역 시퀀스 epoch는 C22와 하네스의 epoch 집계가 파티션 사이 값 충돌 없이 동작하게 하고, `lease_acquisitions`로 "이 epoch는 어느 파티션의 것인가"를 사후에 증명할 수 있게 한다.
- 파티션을 2개로 둔 이유. 노드가 둘이면 마켓 12개 리스는 이득 없이 틱·승격 단위·할당량 규칙만 늘린다. 검토에서 할당량 규칙이 파티션을 무기한 무소유로 남기는 경우를 찾았다.

기대 효과는 가용성과 앱 CPU 분산이다. 앱 노드 하나가 죽어도 다른 노드가 쥔 마켓은 계속 접수하고, 평소에는 두 노드가 마켓을 나눠 매칭한다. 처리량은 늘지 않는다. 병목이 DB 커밋이기 때문이다. 185/s에서 DB CPU 238.5%, api-1 143.5%였고(harness/evidence/accept-concurrency-20260913/README.md:121, :137), 대역 래치 뒤 214.12/s에서 커밋 1회가 5.756ms였다(docs/measurements/20260914.md:62-66). 두 노드는 같은 프라이머리에 커밋하고 주문당 DB 일은 줄지 않는다. 이 수치는 모두 단일 리더의 로컬 Docker 측정이며 파티션 모드의 값이 아니다.

## 대안

- 리스만 나누고 InflightReserves를 유지한다. 기각. 배경의 영구 벽돌 경로가 그대로 열린다.
- 예약을 잔고 행 SELECT … FOR UPDATE로 확인만 한다. 기각. 차감이 기록되지 않아 직렬로 들어온 두 노드가 모두 통과한다.
- 2단계 예약(락 밖 별도 트랜잭션에서 예약, 락 안에서 확정, 회수기). 보류. 접수 락 보유 증가가 결정 5의 한도를 넘을 때만 고른다. 커밋이 하나 늘고 상태와 회수기가 필요하다.
- 접수 때 orders PENDING 행을 만든다. 선택지로 남긴다. C14를 바꾸지 않아도 되지만 정산의 INSERT가 UPDATE로 바뀐다.
- 마켓 1:1 리스 12행과 할당량 기반 동적 배정. 기각. 노드 둘에서는 이득이 없고 무소유 기아 경로가 생긴다.
- 앱 설정 파일로 파티션 맵을 둔다. 기각. 노드 사이 맵 불일치가 조용한 split-brain이 된다.
- 즉시 반납(`release_partition`)으로 인계 시간을 줄인다. 보류. 0002:56의 불변을 깨고, 반납 뒤 판정을 통과한 append가 새어 나오는 경로를 따로 막아야 한다.
- JVM 내부 전달이나 클라이언트 직접 라우팅. 기각. 요청 스레드를 원격 대기로 묶거나 노드 토폴로지를 외부에 드러낸다.
- nonce 공유 저장소. 기본안에서 제외. 503 재시도가 모두 401이 된다. NEW는 coid 멱등으로 이미 replayed가 된다.

## 불변식

지키는 것과 새로 거는 것을 나눈다. 라이브 강제는 DB 제약과 fencing이고, 아래 검사는 그 백스톱이다.

- C01·C02(InvariantCheck.java:19-26): 바꾸지 않는다. 파티션 시험의 1차 판정이다.
- C05, C23, C27, C28: 바꾸지 않는다. 대역과 seq 축이다.
- C13A(InvariantCheck.java:148): 정의를 유지한다. 하네스 드레인 판정은 두 노드 모든 파티션의 큐와 정산이 비었는지로 바꾼다.
- C14를 C14'로 바꾼다(InvariantCheck.java:160). LOCKED 잔고 = 열린 주문의 `locked_amount` 합 + 아직 정산되지 않은 예약의 합이다. 미정산은 `NOT EXISTS (SELECT 1 FROM event_emission_registry r WHERE r.source_cmd_seq = res.command_seq)`로 판정한다. 정산은 끝났는데 orders 행이 없는 예약은 누수로 센다. 대기 중 출금의 LOCKED 항은 결정 16에 따라 상태 집합을 확인한 뒤 넣는다. 열 이름은 `ledger_transactions.business_event_type`, `ledger_entries.transaction_id`, `accounts(owner_type, owner_id, purpose, asset_id, shard)`다(V1__init.sql:93-105, 270-287).
- C22를 C22'로 바꾼다(InvariantCheck.java:292). 마켓 안 팔은 그대로 둔다. 시간 축 팔은 프로토콜 이력으로 구간을 나눠 P1·P2 구간은 지금처럼 전역으로 보고, P3 구간은 파티션별로 본다. P3 구간의 마켓 사이 split-brain 탐지는 C30이 대신한다.
- 신규 C29_MARKET_PARTITION_MAP(CRITICAL, SCHEDULED): 대응 행이 없는 마켓 수와 리스 행이 없는 resource 수.
- 신규 C30_FENCING_RESOURCE_MATCH(CRITICAL, AUTHORITATIVE_ONLY): P3 epoch로 기록된 command_journal, event_journal, trades, snapshots 행 중 `lease_acquisitions.resource`가 그 행 마켓의 파티션과 다른 건수.
- 신규 C31_RESERVATION_LINK(CRITICAL, SCHEDULED): 원장 RESERVE 거래가 없거나 금액·계정이 다른 예약, 예약 표식이 있는데 예약 행이 없는 NEW, 저널에 없는 예약 행의 합.
- 운영 지표: 정산 23514 건수는 0이어야 하고 1 이상이면 경보다. 오래된 미정산 예약의 나이도 경보로 본다.

## 단계

되돌릴 수 없는 것을 검증 뒤로 미룬다. 세부 종료 조건과 관문 시험은 [단계 계획](../architecture/마켓분할_리더_단계계획.md)에 있다.

- S0 문서: 이 ADR, 단계 계획, 0002·0022의 대체 표시, 0099와 로드맵 갱신은 2026-09-23에 했다. DB 설계 §5.1 복귀, API §5-1·§5-2, 하네스 검증설계의 C14'·C22'·C29~C31 개정은 남았다.
- S1 단일 리더 안전 보강: 정산 오류 분류와 재시도, 고아 멱등 키 조건부 삭제, 접수 lock_timeout, prod 타임아웃을 쓰는 카오스 프로필. 모드는 P1 그대로다. 진행 중이며 관문 시험을 통과하기 전에는 완료로 적지 않는다.
- S2 스키마와 시장 도출 fencing: 대응 표, 파티션 리스 행, epoch 시퀀스와 획득 이력, 3인자 판정·획득 함수, 레거시 함수 CREATE OR REPLACE, 'matching-engine' 하드코딩 교체. P1에서 동작이 오늘과 같다.
- S3 접수 시점 예약과 P2 전환: 새 머리말, 정산 분기, C14'·C31, 23514 매핑. 폐기용 Docker DB에서 P2로 전환하고 단일 리더 카오스를 다시 돈다.
- S4 파티션 단위 Java: 리스 서비스·게이트·코디네이터·팔로워·강등·좀비 판정·지표를 파티션 단위로 바꾼다. P2의 단일 resource를 "한 파티션이 전 마켓을 덮는 경우"로 표현해 코드 경로를 하나로 둔다.
- S5 REST 라우팅: 선검사 필터, 헤더 힌트, 파티션별 nginx upstream, cancelAll과 관리 경로 팬아웃.
- S6 WebSocket 브로커 릴레이와 마켓별 epoch. 결정 13에 따라 보류한다.
- S7 P3 전환과 파티션 카오스(폐기용 Docker DB).
- S8 개인 AWS에 app-2를 만들고 같은 시험의 부분 집합을 다시 돈다. 학교 DB는 P3으로 바꾸지 않는다(결정 19).

## 검증

아래는 계획한 시험이다. 2026-09-23 현재 실행한 것은 없고, 파티션 모드와 P2의 측정값도 없다.

- 핵심 시험은 교차 노드 이중 지출이다. 같은 사용자 U의 KRW available=X에서 p0 마켓과 p1 마켓에 각각 X짜리 매수를 장벽 동기화로 1,000회 동시에 넣는다. 매 회 201 하나와 400 하나, 정산 23514·라이터 fatal·강등 0, C01·C02·C13A·C14'·C22'·C30·C31 0이어야 한다. 출금 동시 경합, 한쪽 노드 pause, 같은 coid의 교차 마켓 동시 전송(409와 replayed만 허용) 변형을 함께 돈다.
- 오래된 리더: 다른 파티션 리스로 쓰기 0행, 전환 중 진행 중이던 이전 모드 쓰기 0행, 레거시 2인자 함수 거부, fencing 주입 시험을 3인자 판으로 옮긴 뒤 양성 대조로 주입이 실제 거부를 낳는지 확인한다(PromotionStandDownIntegrationTest.java:697-711, V30:23-24).
- 카오스는 prod 타임아웃 프로필로 돈다. 지금 카오스 스택은 dev 프로필이라 기본 풀에 lock_timeout이 없어(application.yml:100-112) 교차 노드 잠금 결합이 강등으로 번지는 실패를 재현하지 못한다. prod 기본 풀에는 `lock_timeout 2000ms`가 있다(application.yml:191).
- 가용성 판정에는 양성 축을 둔다. 한 파티션 소유자를 kill할 때 다른 파티션 마켓의 ACK 간격 최대가 기준선보다 1초 넘게 늘지 않아야 한다. 영향받은 파티션의 RTO는 단일 모드 실측 범위(5.277~10.507초, docs/measurements/20260914.md:41-45)와 비교한다.
- 처리량은 같은 날 같은 스택에서 P1·P2·P3의 600/s 주행을 비교하고 접수 락 보유와 커밋 지연을 함께 기록한다. P1과 P2의 비교가 결정 5의 판정 근거다.

## 되돌리기

- S1, S2: 앱 롤백이 자유롭다. S2의 새 함수는 P1에서 오늘과 같은 결과를 낸다.
- P2 전환 뒤: 구 바이너리는 레거시 함수 거부로 리더가 될 수 없다. P2에서 P1로 되돌리기는 결정 8의 조건에서만 한다. 과거 예약 주문의 정산 경로는 두 갈래를 영구히 유지한다.
- P3 전환 뒤: 소프트 롤백은 한 노드만 두 파티션에 참여하도록 설정하는 것이다. 자원 형태가 오늘과 같아진다. 하드 롤백은 P3→P2 전환 함수로 하며 정지 창에서 모든 파티션 게이트를 닫고 비운 뒤에만 한다. 전환이 모든 리스 행을 퇴역시키므로 데이터 이관은 없다. 수수료 샤드는 P3 구간의 행만 파티션 샤드를 쓰고, P2로 돌아가면 새 행은 샤드 0으로 돌아간다(추론).
- 명령 대역 래치(V44)는 되돌릴 수 없다(0022:17). P3의 선행 조건이며 결정 9에 따라 폐기용 Docker DB에만 건다.

## 영향

- 개정할 문서: 0099의 현재 구조와 역할 문장 "리더·팔로워를 서로 다른 EC2"(docs/reference/adr/0099-current-architecture.md:39)는 P3 전환 때 고친다. DB 설계 §5.1, API 설계 §5-1·§5-2, 하네스 검증설계의 C14'·C22'·C29~C31은 해당 단계 코드보다 먼저 고친다. AGENTS.md의 "단일 리더 매칭 엔진" 문구는 결정 20에 따라 사용자에게 물은 뒤 고친다.
- 하네스: 'matching-engine' 직접 참조 5곳(e2-db-cut.ps1:85, e3-redis-outage.ps1:26-27, e5-net-partition.ps1:122-140, e7-gray-pause.ps1:110-125, adr0022-stage7-cold-restart.ps1:164)과 `Get-FirstTradeSql`(harness/scripts/lib.ps1:269)의 epoch 비교, `harness.failover_runs`의 resource 열.
- 운영: 학교 진입점은 app-1 nginx 하나다(deploy/linux/app/nginx-https.conf:60-83). 파티션을 해도 app-1이 죽으면 외부 진입이 끊긴다. 개인 AWS에서 ALB를 쓰면 ALB는 503을 재시도하지 않는 것으로 알려져 있어(추론) ALB 뒤에 nginx 계층이 필요하다.
- 비용: 예약이 접수 락 안으로 들어와 락 보유와 ACK 지연이 늘어난다. reserve_funds 실측 1.444ms와 2.6ms는 V38 이전 값이다(harness/evidence/ledger-post-v27-20260906/README.md:31, harness/evidence/counter-20260907/README.md:68). 정산과 같은 잔고 행을 다투면 더 늘 수 있다.

## 확인하지 못한 것

- PostgreSQL 동작: INSERT…SELECT에서 `FOR KEY SHARE OF l`과 파생 테이블의 조합 허용 여부, EvalPlanQual 재평가 때 SET 절 nextval의 재계산, 대기 중인 FOR UPDATE 앞에서 KEY SHARE를 주는 순서.
- DB 안에서 예약액을 계산할 때 쓸 자산·마켓 scale 열의 위치. `markets` 표에서 scale 열을 찾지 못했다(V1__init.sql:38-56). 결정 7에 따라 S2에서 확인한다.
- 출금이 LOCKED를 쥐는 상태 집합(transfer_requests 상태는 V14__transfer_requests.sql:29-30).
- FEE_SHARD 변경이 follower catch-up의 샤드 배정 동일성에 주는 영향(SettlementService.java:51 주석).
- 두 노드에서 정산 교착 빈도, V38 이후 reserve_funds 실비용, 파티션마다 틱을 돌릴 때 리스 풀 크기.
- Spring user destination 브로드캐스트 요건, nginx 전원 퇴출 동작, ALB 503 비재시도.
- 기존 통합 시험 넷(CrashWindowReinjectionOrderTest, PromotionRecoveryIntegrationTest, FollowerRejoinIntegrationTest, BandModeFailoverIntegrationTest)의 단일 리스 전제.
