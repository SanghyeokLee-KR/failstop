# ADR 0003. 금액·수량을 NUMERIC(40,18)로 통일하고 전 계층에서 float을 금지한다

- 상태: 채택
- 날짜: 2026-07-22
- 결정자: 아키텍처 담당(정본 문서 관리자), backend-engineer

## 배경

스키마 확정 전에 금액 표현 타입을 정해야 했다. 제약은 네 가지였다.

- 증명 목표 두 가지가 전부 값의 정확한 재현에 달려 있다. "체결이 어긋나지 않음"은 복식부기 원장의 자산별 합 = 0으로, "자동 복구"는 명령 저널의 결정론 재생으로 증명한다(docs/reference/contracts/백엔드_DB_설계.md:12-20 §1.1).
- 이진 부동소수는 0.1을 유한 자리로 담지 못해 그 두 증명을 동시에 깬다. 합계에 잔차가 남고, 같은 명령을 재생해도 누적 순서에 따라 결과가 갈린다.
- 취급 자산의 유효 소수 자리 폭이 넓다. KRW=0, XRP·DOGE·ADA·TRX·MATIC=2, LINK·DOT·AVAX·ATOM=4, SOL=6, BTC=8, ETH=18이다(server/app/src/main/java/kr/exchange/dev/DevDataSeeder.java:31-43). 시드 자산은 13개다. 확인 명령: `awk 'NR>=31 && NR<=43' DevDataSeeder.java | grep -c "new AssetSeed"` 결과 13, 같은 범위에 `grep -c ", 2),$"` 결과 5로 scale=2 군이 5종이다. 원장·잔고·주문·체결이 한 컬럼 계열을 공유하므로 저장 타입 하나가 scale 0과 scale 18을 동시에 덮어야 한다.
- 초안 타입 `NUMERIC(36,18)`은 정수부가 18자리라 저장 가능한 절댓값이 10^18 미만이다. `qty x price`로 만든 notional이 이 선을 넘으면 PostgreSQL이 `numeric field overflow(22003)`를 던지고 트랜잭션 전체가 실패한다. 초안 검토가 medium으로 지적했다(docs/reference/contracts/백엔드_DB_설계.md:59 §1.4).

실패 지점이 삽입 시점이라는 게 결정을 앞당겼다. 매칭은 이미 성립했는데 원장 게시가 22003으로 롤백되면 체결 사실과 회계 기록이 갈라진다. 정밀 부족은 값이 조용히 틀리고, 폭 부족은 트랜잭션이 요란하게 죽는다. 둘 다 원장에서는 같은 등급의 사고다.

## 결정

- 모든 금액·수량·가격을 `NUMERIC(40,18)`로 저장한다. V1 마이그레이션에 20행 선언돼 있다. 확인 명령: `grep -c "NUMERIC(40,18)" server/app/src/main/resources/db/migration/V1__init.sql` 결과 20, `grep -o ... | wc -l`도 20으로 행당 1건이다.
- 정수부 22자리로 곱셈 중간값 오버플로 여유를 둔다.
- 소수부 18자리 고정. 1 ETH = 10^18 wei를 무손실로 담는다(docs/reference/contracts/백엔드_DB_설계.md:60).
- 자산별 유효 소수 자리는 `assets.scale`로 관리하고, 입력값은 저장 전 그 scale로 양자화한다(server/app/src/main/resources/db/migration/V1__init.sql:35 컬럼 주석).
- 방향별 양자화 레짐을 용도별로 고정한다(docs/reference/contracts/백엔드_DB_설계.md:62-66 §1.4).
  - 예약(락 산정)은 `RoundingMode.UP`.
  - 체결·감액 차감·환급은 `RoundingMode.DOWN`.
  - 수수료는 `RoundingMode.HALF_UP`.
  - 종결(FILLED·CANCELED)은 계산값이 아니라 그 주문의 실잔여 락을 전량 소비·해제해 락 잔량을 정확히 0으로 만든다. 분개 잔차는 자산별 마지막 FEE leg가 흡수한다. 음수 FEE leg는 산식 계약 위반이라 fail-fast다(docs/reference/contracts/백엔드_DB_설계.md:66).
- 주문별 notional·qty 상한은 앱 가드가 강제한다. `markets.max_notional`·`max_qty`는 상한값 저장용이고, DDL CHECK는 설정값 자체의 양수성만 본다(server/app/src/main/resources/db/migration/V1__init.sql:45-47).
- 전송과 프론트는 문자열로 다룬다. JSON float을 쓰지 않는다(docs/reference/contracts/백엔드_API_설계.md:122 §2-1).
- 서버 산술은 `BigDecimal`과 명시적 `RoundingMode`. `float`·`double` 금지(docs/reference/contracts/백엔드_DB_설계.md:67, CLAUDE.md:60).

## 근거

- 정수부 22자리는 초안 대비 1만배 여유다. 시드 마켓의 `max_notional`이 10^9 KRW인데(DevDataSeeder.java:47-58, 12개 마켓 전부 동일), 저장 상한 10^22와 사이에 13자리가 남는다. 곱셈 중간결과와 원장 누적이 상한을 건드릴 경로가 없다.
- 소수부를 자산별로 나누지 않고 18로 통일하면 원장·잔고·주문이 같은 타입을 공유해 조인·집계에서 암묵 캐스팅이 생기지 않는다. 자산별 차이는 `assets.scale` 데이터로 흡수한다.
- 양자화 방향을 용도별로 고정한 이유는 잔차의 부호를 한 방향으로 몰기 위해서다. 예약이 UP인 것은 체결별 차감이 DOWN 절사이기 때문이다. 예약이 차감 합보다 작아지면 중간 체결이 락 잔여를 초과한다(server/app/src/main/java/kr/exchange/ledger/SettlementService.java:236-242 주석과 구현). 초과 예약 잔차는 최종 체결의 실잔여 전량 소비가 환급으로 되돌린다.
- 수수료만 HALF_UP인 것은 요율 곱이 양방향 편향 없이 떨어져야 하기 때문이다(server/app/src/main/java/kr/exchange/ledger/Fees.java:33-34). 반올림 잔차는 자산별 마지막 FEE leg가 흡수해 합 = 0을 유지한다.
- 소수부 18은 시장가 매수 경로에서 하한이기도 하다. notional 나눗셈이 scale 18 소진액을 만들므로 정산이 그 자리를 그대로 받아야 한다. 구현이 `NOTIONAL_SOAK_SCALE = 18`로 상수화해 DOWN 절사한다(server/app/src/main/java/kr/exchange/ledger/SettlementService.java:54, 362). 소수부를 18 밑으로 잡았으면 정산 양자화 재검증이 fail-fast로 떨어진다.
- 문자열 전송은 파싱 계층에서 float 왕복을 원천 차단한다. 숫자 리터럴로 보내면 수신 측 JSON 파서가 double로 복원하는 순간 정밀이 사라지고, 그 손실은 계약으로 막을 수 없다.

## 대안

- `NUMERIC(36,18)` 유지(초안안). 기각. 정수부 18자리로 상한이 10^18 미만이라 notional이 이를 넘는 순간 `22003`으로 트랜잭션이 죽는다. 실패가 삽입 시점에 터져 매칭 성립과 원장 게시가 갈라진다(docs/reference/contracts/백엔드_DB_설계.md:59).
- `float`·`double`(또는 프론트의 JS number) 산술. 기각. 이진 부동소수가 십진 소수를 정확히 담지 못해 합 = 0 불변식과 재생 결정론을 동시에 깬다. 정수부 여유와 무관하게 2^53을 넘으면 인접 정수조차 구분하지 못한다.
- 주문별 `notional <= max_notional`·`qty <= max_qty`를 DB CHECK로 강제. 기각. 상한은 마켓별 설정값이고(V1__init.sql:46-47 `markets`), 검사 대상은 다른 테이블인 `orders`다(V1__init.sql:161-173). CHECK 식은 자기 행 밖을 참조하지 못하므로 구조상 성립하지 않는다. 강제 주체를 주문 검증 경로의 앱 가드로 넘겼다(server/app/src/main/java/kr/exchange/api/order/OrderGateway.java:476-478, `over_max_order` 400 반환). 이 결정은 DB 설계 v2.2 패치 반영 Medium 11번으로 사후 정정됐다. 본문이 "CHECK로 상한을 건다"고 서술해 방어가 미구현으로 남을 위험이 있었고, `§1.4`·`§7`을 "상한값은 markets에 저장, 앱 가드가 강제"로 고쳤다(docs/reference/contracts/백엔드_DB_설계.md:2400-2402).
- 자산별로 컬럼 타입을 분화(KRW는 소수부 0, ETH는 18). 기각. 원장·잔고가 자산을 가리지 않는 단일 테이블이라 컬럼 타입을 자산별로 나눌 자리가 없다. `ledger_entries.amount`는 모든 자산의 분개를 한 컬럼에 받는다(server/app/src/main/resources/db/migration/V1__init.sql:290).

## 영향

- Flyway V1이 이 타입으로 확정됐다. 저장소 베이스라인 커밋 `8e8e1fa`(2026-07-22)에 `V1__init.sql`이 들어갔다. 되돌리려면 20개 선언을 바꾸는 마이그레이션과 기존 행 재캐스팅이 필요하고, 소수부를 줄이는 방향은 데이터 손실이라 사실상 비가역이다.
- 프론트가 십진 문자열 연산 계층을 따로 가져야 한다. `web/lib/decimal.ts`(166행)가 곱·비교·스케일 변환을 BigInt로 처리하는 단일 출처다. `format.ts`(표시)와 `orderMath.ts`(주문 격자)가 이를 공유한다.
- 표시 계층에만 좁은 예외가 남는다. `web/lib/format.ts:92-97`의 `toNum`은 폴백 대역 판정 같은 비산술 경계 변환 전용이고, 금액 산술에는 쓰지 않는다고 함수 주석이 못박는다.
- 결정 이후에도 float 경로가 두 번 재발했다. 계약만으로는 막히지 않는다는 뜻이다.
  - `b258155`(2026-08-18) 퍼센트 프리셋이 float 경로를 타 스케일드 값이 2^53을 넘는 잔고에서 수량을 위조했다. 회귀 테스트가 `9007199254740993`(= 2^53 + 1)을 고정했고, double은 이 값을 `...992`로 뭉갠다(web/tests/orderMath.test.ts:54-55).
  - `261458e`(2026-08-18) 마이페이지 보유현금을 `toNum`으로 float 합산해 표시했다. 십진 합·차를 `addDecimal`·`subDecimal`로 승격해 단일 출처로 두고 해소했다(web/tests/decimal.test.ts:19-21).
- 저장 타입과 전송 표기가 별개라는 점을 계약에 따로 못박아야 했다. 문자열 계약이 자릿수 고정을 요구하지 않으므로 정본 표기는 후행 0을 제거한 평문이다. 종전 예시가 `"1000000.000000000000000000"`처럼 18자리를 채워 적어 구현(`ApiFormats.amount`)과 어긋났고, API 설계 v2.21에서 정정했다(docs/reference/contracts/백엔드_API_설계.md:780).
- 문서 간 타입 표기 드리프트가 하네스 문서에 아직 남아 있다. API 설계는 정정됐다. docs/reference/contracts/백엔드_API_설계.md:122가 현재 `NUMERIC(40,18)`이고, `grep -c "NUMERIC(36,18)" docs/reference/contracts/백엔드_API_설계.md` 결과 0이다. 반면 하네스 두 문서의 정정 권고 항목은 갱신되지 않아, 이미 해소된 드리프트를 미해소로 서술한다. docs/reference/contracts/하네스_검증설계.md:721이 "불일치", docs/reference/contracts/하네스_CICD_IaC.md:899가 "정정 필요"로 남아 있고, 두 문서가 해소 항목에 붙이는 표식(검증설계 728행 `해소(v2.36)`, CICD 900행 `정정 불요`)이 이 두 행에는 없다. 계약 문서 전수 검색은 아직 남은 후속 작업이다.
- 앱 가드가 상한 강제를 맡으므로 게이트웨이 검증 경로가 회귀 테스트 대상이다. 가드가 빠지면 DB가 잡아주지 않는다. 가드 목록은 상한 외에도 tick_size·lot_size 정합, MARKET BUY의 quantity·quote_order_qty 동시 지정 거부, MARKET BUY notional 소수 자리 18 초과 시 400 거부까지 포함한다(docs/reference/contracts/백엔드_DB_설계.md:68 §1.4).
- MARKET BUY(notional)를 허용하는 마켓은 quote 자산 scale이 18이어야 한다는 파생 제약이 생겼다. quote scale이 18 미만이면 소진액의 자리를 정산이 표현하지 못해 재검증에서 fail-fast로 이어지고, 게이트웨이가 선차단한다(docs/reference/contracts/백엔드_DB_설계.md:68). 마켓을 새로 열 때 이 조건을 확인해야 한다.
