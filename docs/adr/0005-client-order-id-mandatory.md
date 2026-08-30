# ADR 0005. client_order_id를 진짜 필수로 못박고 서버 생성 fallback을 삭제한다

- 상태: 채택
- 날짜: 2026-07-22
- 결정자: 아키텍처 담당(API 정본 관리자)

## 배경

이중 체결 불가능성은 첫째 증명 목표(폭주·장애에도 체결이 어긋나지 않음)를 지탱하는 축이다. API 정본은 그 축을 "결정론적 단일 라이터 + 이벤트 소싱 + (필수)멱등성 + 복식부기 원장 + epoch"로 명시한다(docs/contracts/백엔드_API_설계.md:5).

설계 검증이 그 축의 붕괴를 지적했다. 두 건이 같은 필드를 가리켰다.

- critical 1건. `client_order_id`가 optional이면 이중체결 방지가 붕괴한다(docs/contracts/백엔드_API_설계.md:1466, §8-1 #1).
- high P2. required로 승격했다고 쓰면서 서버 생성 fallback `srv-{user_id}-{seq}`를 동시에 허용해 "필수인데 기본값 존재"라는 자기모순이 남았다. `{seq}` 산출 기준도 미정의였다(docs/contracts/백엔드_API_설계.md:1513).

운영 사고가 아니라 문서 검토에서 나온 지적이다. 재현 경로는 문서가 적은 그대로다.

- 클라이언트가 주문을 보내고 타임아웃을 받는다. 주문 상태는 UNKNOWN이다.
- 키 없이 재전송한다. fallback의 `{seq}`가 단조 카운터인 한 이번에는 다른 키가 붙는다.
- 서버는 두 요청을 서로 다른 주문으로 접수한다. 멱등 계층은 키가 다르므로 개입하지 않는다.

멱등 계층이 있는데도 그 계층을 우회하는 통로가 서버 기본값 형태로 열려 있었다.

장애 규약이 이 통로를 치명적으로 만든다. 5XX·타임아웃 수신 시 주문 상태는 UNKNOWN이고 신규 재전송은 금지다(docs/contracts/백엔드_API_설계.md:194). 키가 없으면 클라이언트는 재조회 대상을 지정할 수단 자체가 없어 규약을 지킬 수 없다. fallback이 붙인 키는 응답을 받지 못한 클라이언트가 알 수 없는 값이라 대체 수단도 되지 못한다.

## 결정

`POST /orders`와 `POST /orders/batch`에서 `client_order_id`를 진짜 required로 둔다. 서버 생성 fallback은 완전히 삭제한다.

- 미제공이면 예외 없이 `400 client_order_id_required`(docs/contracts/백엔드_API_설계.md:158, :199, :629). 형식은 1~36자 `[A-Za-z0-9_-]`.
- 재시도는 같은 키를 재사용한다. 내용 동일이면 `200` + `X-Idempotent-Replay: true`로 수렴하고, 내용 상이면 `409 duplicate_client_order_id`다(§2-4).
- 5XX·타임아웃 수신 시 클라이언트는 상태를 UNKNOWN으로 두고 `GET /orders/by-client/{client_order_id}`로 재조회해 판정한다. 신규 재전송은 금지다(§7-6, docs/contracts/백엔드_API_설계.md:1438~1447).
- 재조회 권위는 `command_journal`이다. 저널에 없으면 `404 order_not_found`(미접수, 재전송 허용), 저널에 있으나 프로젝션 미반영이면 `404`가 아니라 `409 order_in_progress` + `Retry-After`다(§4-B-4-a, docs/contracts/백엔드_API_설계.md:687).
- 키 생성 책임은 전적으로 클라이언트에 둔다. 봇도 예외가 아니다.

## 근거

- 필수 선언과 기본값 존재는 양립하지 않는다. 기본값이 있으면 필드는 사실상 optional이고, 가장 순진한 클라이언트가 가장 위험한 경로로 흘러간다. escape hatch를 열어 둔 채로는 이중체결 불가능성을 주장할 수 없다.
- 설계 원칙 우선순위가 정확성 > 고가용성 > 성능 > 편의성이다(docs/contracts/백엔드_API_설계.md:4). 서버 자동 키는 편의성 항목이고, 이중체결은 정확성 항목이다. 충돌 시 판정은 이미 문서에 있었다.
- 재조회 규약이 키를 전제로 성립한다. `by-client` 라우트는 키가 조회 축이므로, 키 없이 접수된 주문은 UNKNOWN 해소 경로가 없다. fallback 키는 클라이언트가 모르는 값이라 응답을 못 받은 상황에서는 조회에 쓸 수 없다.
- 프로젝션 축과 저널 축 양쪽에 기계 판정이 이미 있다. C07은 `orders`, C07'은 `command_journal`의 NEW 커맨드 축에서 `(user_id, client_order_id)` 중복 0을 상시 검증한다(docs/contracts/하네스_검증설계.md:403~414). 필수화는 이 두 판정의 대상 집합을 전수로 만든다. optional이면 키 없는 주문이 판정 밖에 남는다.

## 대안

정본은 채택안을 "옵션 (A)"로만 기록하고 나머지 후보를 적지 않았다(docs/contracts/백엔드_API_설계.md:1513). 첫 항목이 그 시점의 현상 유지안이고, 나머지 셋은 이 ADR에서 기록하는 기각안이다.

- 서버 생성 fallback `srv-{user_id}-{seq}` 유지. 기각. 위 자기모순이 그대로 남고, 타임아웃 후 키 없이 재전송하는 클라이언트가 매 재시도마다 다른 키를 받아 이중 접수된다. `{seq}` 산출 기준을 정의해도 클라이언트가 그 값을 알 수 없다는 점은 바뀌지 않아 재조회 경로가 복구되지 않는다.
- 서버가 요청 바디 해시를 키로 대체 생성. 기각. 동일 내용의 정상 신규 주문 두 건, 즉 같은 가격·수량의 분할 발주가 같은 키로 접히므로 두 번째가 멱등 replay로 흡수돼 주문이 소실된다. 이중 접수를 막으려다 미접수를 만드는 교환이다.
- 필수는 유지하되 미제공을 경고 후 통과. 기각. 게이트가 차단하지 않으면 규약이 아니라 권고다. 봇 트래픽은 경고 로그를 읽지 않는다.
- DB `NOT NULL`로만 강제하고 API 검증은 생략. 기각. 거절이 저널 append 시점까지 밀려 에러코드가 제약 위반으로 노출되고, `400 client_order_id_required`와 `409 duplicate_client_order_id`를 클라이언트가 구분할 수 없다. 정적 검증은 접수 전에 끝나야 주문 상태가 확정 미접수로 남는다(docs/contracts/백엔드_API_설계.md:269).

## 영향

- 정본 문서 3개 절을 동기화했다. §2-4 서술(:199~200), §4-B-1 필드표(:629), §8-1 #1(:1466). 아키텍처 개관도 같은 결론을 싣는다(docs/architecture/아키텍처_개관.md:137~139 §2.3, :193~201 §3.4).
- 서버 강제 지점은 한 곳이다. `server/app/src/main/java/kr/exchange/api/order/OrderGateway.java:60~67`이 누락·형식 위반을 접수 전에 거절한다. 단건과 batch가 같은 `place`를 타므로 우회 라우트가 없다(OrderController.java:91, :134). 회귀 테스트는 `server/app/src/test/java/kr/exchange/api/ApiGatewayIntegrationTest.java:532~539`.

클라이언트에 키 수명주기 관리 비용이 생겼다.

- 웹은 `web/components/trade/orderKey.ts`에 발급·보존·폐기 규칙을 순수 함수로 분리했다. 제출 실패는 키를 보존하고, 내용 변경만 새 키를 발급한다. 생성기는 `web/components/trade/OrderPanel.tsx:88~90`.
- 봇은 run 단위 coid 접두의 유일성 기준이 프로세스가 아니라 `idempotency_keys` 보존 범위다. 인메모리 카운터로는 DB를 남긴 채 재기동할 때 직전 run의 coid 열을 재생성해 409가 폭주한다(server/app/src/main/java/kr/exchange/sim/RunOrdinalSource.java:3~10).
- 그래서 채번원을 DB 시퀀스로 옮겼다(V11__sim_run_ordinal.sql:10, DbRunOrdinalSource). 필수화가 마이그레이션 한 건을 추가로 낳았다.
- 마켓메이커는 모호 응답 후 같은 레벨을 재발주할 때 같은 coid를 재사용한다(MarketMakerBot.java:68~70, 재사용 지점 :425).

책임 이관이 낳은 결함 2건을 이후 닫았다.

- 58e380c. `idempotency_keys.request_hash`가 보존 기간(7일)으로 삭제된 뒤 저널에는 키가 남은 구간에서, 내용 대조 없이 replay로 흡수하던 경로를 막았다. 판정 기준을 저장된 해시 유무가 아니라 대조 수행 여부로 두고, 거절 경로가 자기 해시를 남기지 않게 했다(OrderGateway.java:88, :117, :156~159). 회귀 테스트는 ApiGatewayIntegrationTest.java:441.
- 26450d1. 제출 중 바뀐 입력이 멱등키에 반영되지 않아 재제출이 같은 키에 다른 내용을 실어 보내던 웹 구간을 닫았다.

DB는 이 필수를 이중으로 강제하지 않는다.

- `orders.client_order_id`는 NULL 허용이고(V1__init.sql:156) UNIQUE도 부분 인덱스다(:208~209).
- `command_journal`은 파티션 키가 `seq`라 `(user_id, client_order_id)` 전역 UNIQUE가 물리적으로 불가하다. 유일성 완결 강제는 `uq_orders_client`가 담당한다(:586~590).
- 결과로 게이트웨이 우회 접수의 탐지는 C07' 상시 검증에 의존한다. 위반은 CRITICAL 등급이라 거래 정지까지 간다(docs/contracts/하네스_검증설계.md:575).

증명 실험이 이 계약을 겨눈다.

- E9는 커밋 응답 절단으로 UNKNOWN을 만들고, 재조회 후 재전송 금지로 수렴하는지를 C07·C07'로 판정한다(docs/contracts/하네스_검증설계.md:367).
- k6 쪽 이중 접수 임계는 제거하고 서버측 C07'에 위임했다(:96). k6에는 재조회 없이 재전송하는 경로가 없어 임계가 항상 통과하는 토톨로지였다.

되돌리려면 fallback 재도입만으로 부족하다.

- 문서 축: §2-4·§4-B-1·§8-1 세 절과 아키텍처 개관 §2.3·§3.4.
- 코드 축: OrderGateway 검증, 통합 테스트 `gatewayGuardsRejectBeforeJournal`(:532~539), 웹 시뮬레이터 거절 경로(web/lib/sim/engine.ts:734), 웹 키 수명주기 모듈.
- 판정 축: C07'을 남겨 두는 한 우회 접수는 CRITICAL 위반으로 드러난다. 되돌림은 불변식 정의 변경까지 포함해야 성립한다.
