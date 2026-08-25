# exchange server

고가용성 가상 암호화폐 거래소 백엔드. Gradle 멀티모듈 골격.

## 요구 환경

- JDK 21 (Temurin 권장)
- Gradle 8.14. wrapper 생성 완료, `gradlew` 사용. (배포판 위치: C:\Users\DEV-PC\tools\gradle-8.14)
- PostgreSQL 17 이상. 파티션드 테이블의 `GENERATED ... AS IDENTITY`가 PG17 의존이다(DB 설계 v2.8 전제). 16으로는 V1 마이그레이션이 실패한다.
- 통합테스트는 Docker(Testcontainers) 필요

## 주의: 한글 경로에서 테스트 실패

프로젝트가 한글 경로(Desktop\프로젝트)에 있으면 `gradlew test`가 ClassNotFoundException으로 실패한다.
Gradle이 긴 클래스패스를 매니페스트 jar로 전달할 때 비ASCII 프로젝트 경로가 깨지는 Windows 이슈다.
컴파일은 되고 테스트 워커만 클래스를 못 찾는 증상이면 이 문제다.

해결: ASCII 정션을 만들어 그 경로에서 빌드한다. 이 머신에는 이미 생성돼 있다.

```
New-Item -ItemType Junction -Path C:\dev\exchange -Target C:\Users\DEV-PC\Desktop\프로젝트
cd C:\dev\exchange\server
.\gradlew.bat test
```

IDE도 C:\dev\exchange\server 로 여는 것을 권장한다.

검증 셸은 PowerShell을 쓴다. 정션 경로에서도 Git Bash에서는 테스트 워커가
전 클래스 ClassNotFoundException으로 실패하는 사례가 재현됐다(로케일·인코딩 추정).
PowerShell에서는 동일 명령이 정상 통과한다. CI 러너는 ASCII 경로라 영향 없다.

## 모듈 구조

```
server/
├── settings.gradle.kts      루트 exchange, 모듈 engine / app
├── engine/                  순수 Java 21. 매칭엔진 결정론 코어(Spring 의존 금지)
│   └── kr.exchange.engine.core
│       ├── OrderCommand       command_journal 1:1 명령 레코드
│       ├── EngineEvent        event_journal 1:1 이벤트 레코드
│       ├── OrderBook(Impl)    가격 레벨 정렬 맵 + 레벨 내 FIFO, 정준 상태 체크섬
│       ├── MatchingEngine(Impl) 전 마켓 라우팅, lastAppliedSeq 이중 적용 방지
│       └── SingleWriterLoop   유계 큐 + seq 연속성 검증 + catch-up + fail-fast
└── app/                     Spring Boot 3.5.x 애플리케이션
    ├── kr.exchange.api      REST/WS 공개 API
    ├── kr.exchange.engine   엔진 어댑터(Spring 수명주기, 리더 리스 연동)
    ├── kr.exchange.ledger   복식부기 원장, 봉인(seal)
    ├── kr.exchange.journal  저널 append, 스냅샷, 재생 복구
    ├── kr.exchange.config   스프링 구성
    └── resources/db/migration/V1__init.sql   핵심 스키마(DB 설계 v2.8 §3.1 추출)
```

## 빌드 / 실행

```
# 빌드 (wrapper 생성 이후에는 gradlew 사용)
gradle build

# 통합테스트 (Docker 필요, PG17 컨테이너로 Flyway V1 검증)
gradle :app:test

# 실행 (dev 프로파일 기본, localhost:5432/exchange 접속)
gradle :app:bootRun
```

dev 프로파일 DB 접속 정보는 `app/src/main/resources/application.yml`의 dev 섹션 참조.
메트릭은 `/actuator/prometheus`로 노출된다.

## 계약 문서 (정본)

- `../docs/백엔드_DB_설계.md` (v2.15). DDL 정본. V0~V10 마이그레이션의 원천
- `../docs/백엔드_API_설계.md` (v2.12). 스택/버전, API, 인증(데모 세션 §3-7 포함), 결정론 규칙
- `../docs/하네스_검증설계.md` (v1.1). 불변식 검증기 C01~C19, 카오스 매트릭스, V3 통합테스트

스키마와 코드가 어긋나면 문서가 이긴다. 변경은 문서 개정 후 반영한다.

## M1-4 인계 (정산 계층 계약)

M1-3(정산 계층) 산출물이 REST/WS 게이트웨이 단계에 요구하는 계약이다.

- 운영 sink는 SettlementService.appendAndSettle 단일 경로다. 한 호출 = 한 명령의
  방출 배치 = 한 트랜잭션(이벤트 append + 유형별 정산 + trades + orders + outbox,
  DB 설계 §5.2·§5.3·§3.1 6.2). 크래시 창 복구(EngineRecovery)도 같은 sink를 명령
  단위로 호출한다(§5.5 ② 전체). 멱등 판정은 event_emission_registry의
  (source_cmd_seq, event_ordinal) 사전 조회이며, 배치가 이미 적재돼 있으면 정산도
  같은 커밋에 있었으므로 통째로 스킵한다. EventJournalAppender는 정산 없는
  저널 계층 테스트 전용으로 남는다.
- 예약·차감 양자화 규약(§1.4, §4-B-5): LIMIT 매수 예약은 qty x price를 quote scale
  UP 양자화, 체결·감액별 차감은 DOWN 절사, 최종 체결·종결 취소는 실잔여 전량
  소비·해제 + 잔차 환급. 매도는 base 수량 그대로. 시장가 매수는 notional 그대로
  예약하고 엔진 소진액 규약(scale 18 DOWN)과 동일 산식으로만 차감한다. 잔차는
  자산별 마지막 FEE leg가 흡수한다(음수 FEE leg는 fail-fast).
- 수수료 권위: 명령 payload의 maker_fee_rate/taker_fee_rate 박제값이다. 게이트웨이는
  접수 시점 markets 요율을 OrderIntake에 실어 박제해야 한다(§5.2 수수료 박제 규칙).
  확정 금액 산식은 Fees 단일 지점이고 재생 대조(ReplayService)가 같은 산식으로
  payload를 재구성한다. 수수료 샤드는 K=1(shard 0) 박제이며 K 변경은 재생 샤드
  동일성을 깬다.
- 잔고 사전 검증은 M1-4 필수 가드다. ACCEPTED 정산의 예약 실패(잔고 부족 23514)는
  배치 롤백 + 루프 정지 + demote로 처리된다. 엔진이 이미 수락·매칭한 뒤의 사후
  거절은 오더북과 disposition을 가르기 때문이다(§5.1의 접수 시점 거절과 다른 지점).
  가드 없이는 잔고 0 사용자가 엔진을 세울 수 있고, 재기동 복구도 같은 지점에서
  막힌다. §5.5 STEP2의 잔고부족 REJECTED skip 분기는 이 전제(도달 불가)로 미구현이다.
- external_id는 게이트웨이가 접수 전 발급한 UUID를 payload에 박제한다(P0-4).
  미박제 NEW 명령은 ACCEPTED 정산에서 fail-fast다.
- 스케일 정합 가드: 정산은 자산 scale로 양자화된 금액만 원장에 게시할 수 있다
  (post_ledger_transaction 재검증). lot_size·tick_size는 자산 scale과 정합해야 하고,
  시장가 매수(notional 나눗셈이 scale 18 소진액을 만든다)를 허용하는 마켓은
  quote 자산 scale 18이 전제다. 위반 주문은 정산 fail-fast로 이어지므로 게이트웨이
  검증 경로에서 막는다(§1.4 앱 가드 목록).
- STP 부분 감액은 orders.canceled_qty에 쌓인다(DB 설계 v2.8 보존식
  filled+remaining+canceled=quantity). CANCELED 이벤트 qty의 단위는 주문의 잔여
  추적 단위를 따른다: 시장가 매수(notional) 주문은 잔여 취소·STP 감액 모두 quote
  금액, 그 외는 base 수량(§5.3 (0) v2.8 정정).
- self-shutdown 배선: sink 실패는 원인 불문(fencing 확정 거부, 일시 DB 오류)
  루프 정지 + demote다(LoopFatalHandler). demote된 인스턴스는 재승격이 금지되고
  리스는 TTL 만료로 풀려 failover가 열린다. EngineBridge는 루프 정지 상태의 접수를
  저널 커밋 전에 거부한다. 복귀는 재생을 포함한 프로세스 재기동뿐이다.
- 엔진 거부 사유 어휘 중 no_liquidity, order_not_found, invalid_parameters,
  duplicate_order_id는 API 설계 §4-B-1 reject_reason 목록에 없는 엔진 내부 어휘다.
  나머지(self_trade_prevented, post_only_would_take, fok_not_filled, ioc_partial_canceled)는
  §4-B-1 도메인 거부 목록과 일치한다. 미지원 TIF·주문유형은 게이트웨이가 접수 전
  400 invalid_parameter로 정적 거부하므로 unsupported_time_in_force·unsupported_ord_type
  reject_reason은 엔진에서 제거됐다(§4-B-1 상호배타 규칙). 내부 어휘는 orders.reject_reason에
  그대로 적재되므로 게이트웨이 노출 시 매핑 또는 문서 개정이 선행돼야 한다.
- EngineBridge.submit의 synchronized 직렬화는 소비측 seq 연속성 검증(갭 감지 +
  catch-up)이 들어온 뒤에도 유지한다. 큐 투입 순서 역전은 catch-up 왕복을 만들 뿐
  오류는 아니지만, 핫패스에서 불필요한 저널 재조회를 피하는 쪽이 이득이다.
- 멱등 replay는 내용을 비교하지 않는다. API §2-4의 동일 키·내용 상이 재전송에 대한
  409 duplicate_client_order_id 판정은 게이트웨이 책임이고, AppendResult에 원본
  payload가 없으므로 CommandJournalReader 또는 orders 재조회로 비교한다.
- orders.created_at은 DB now()다(파생 프로젝션 계층, §5.1 예시와 동일). trades의
  executed_at·created_at만 engine_ts 파생 명시 삽입이다. 재정산으로 orders
  created_at이 달라질 수 있음을 프로젝션 소비자가 전제해서는 안 된다.
- 스냅샷(snapshots 적재·채택 판별·S_prev 대조)은 미구현이다. 재생은 항상 seq 0부터
  전역 단일 패스이며, recovery_runs 적재도 스냅샷 도입 시점에 붙인다.

## M1-5 인계 (게이트웨이 계약과 잔여 결정)

M1-4(REST·WS 게이트웨이) 산출물이 봇·프론트 연동 단계에 넘기는 계약이다.

- 인증 왕복 레퍼런스: 클라이언트 서명 규약은 CanonicalRequests(JCS RFC 8785, method+path
  바인딩)가 정본이고 테스트 서명기 TestSigner가 왕복 검증된 참조 구현이다. 봇은 동일
  규약으로 서명한다. 키 발급은 DemoKeySeeder.issue(사용자, scope, label). 마스터키는
  EXCHANGE_AUTH_MASTER_KEY_BASE64(base64 32바이트) 주입이 전제이며 미주입 시 프로세스 한정
  임시 키로 동작한다(재기동 후 기존 키 무효).
- candles(M1-5 인계 시점 미구현, 결정 필요): GET /api/v1/candles/{unit}와 /topic/candle.{unit}.{market}
  채널이 없었다. trades 실시간 집계(쿼리·머티리얼라이즈드 뷰) vs 별도 candles 테이블
  (V1 제외분, §3.1) 적재 중 택일해야 했다. WS candle의 seq 도메인은 market x type x unit
  (§5-2 v2.3)임을 구현 시 지켜야 한다.
  현행 상태: GET /api/v1/candles/{unit}는 trades 실시간 집계로 구현됨(MarketController, API §4-A-5 X2 결정). 별도 candles 테이블 미적재.
- rate limit(§2-5) 미구현: 토큰버킷·X-RateLimit 헤더·auth_throttled·418 auto_ban 전부.
  Resilience4j 의존성은 이미 들어 있다.
- nonce 저장소는 단일 노드 인메모리다(NonceStore). API 정본 §3-4 구현 각주(v2.7)로
  문서화됐다. 다중 노드 승격(M2) 시 Redis 교체가 선행 조건이다.
- WS는 단일 노드 SimpleBroker다. 크로스노드 팬아웃(§5-1: /topic Redis 릴레이,
  /user 외부 STOMP 브로커 릴레이)은 미구성이며 다중 노드에서 비공개 채널이 유실된다.
- WS 게시는 정산 커밋 후 best-effort다(MarketDataPublisher가 예외를 삼킨다). 게시 유실은
  클라이언트 seq gap 감지 → REST 재동기화로 흡수된다(§5-2). 기동 복구(EngineRecovery)의
  재정산분은 게시하지 않는다.
- 엔진 내부 reject_reason(no_liquidity, order_not_found, invalid_parameters,
  duplicate_order_id)는 API §4-B-1 목록에 없는 채로 detail.reject_reason에 노출된다.
  매핑 표 확정 또는 API 문서 개정이 필요하다. self_trade_prevented·post_only_would_take·
  fok_not_filled·ioc_partial_canceled는 §4-B-1 도메인 거부와 일치한다. unsupported_time_in_force·
  unsupported_ord_type는 게이트웨이 정적 거부(400)로 대체돼 엔진에서 제거됐다. IOC/FOK/POST_ONLY/BEST는
  게이트웨이가 통과시키고 엔진 REJECTED(422 domain_rejected)로 종결된다.
- API 설계 §5-5·§4-B-5·§4-C-3 수치 예시는 v2.7에서 '수취 자산 수수료 + 예약 무수수료'
  모델로 재계산 정정됐다. WS trade.fee는 trades.maker_fee/taker_fee 박제값(수취 자산 단위)이다.
- 잔고 사전 가드의 가드-정산 경합 창은 게이트웨이 인메모리 in-flight 예약 누계로 좁혔다.
  다중 게이트웨이 노드에서는 성립하지 않으므로 M2에서 재설계한다.
- 시장가 매수(notional)는 quote 자산 scale 18 마켓만 허용된다(§1.4 v2.9 앱 가드).
  KRW(scale 0) 마켓은 시장가 매수가 400으로 거부된다. 봇 시나리오 작성 시 전제.
- 미구현 REST(M1-5 인계 시점): orders/batch, 일괄 취소(DELETE /orders), orders/chance, my/trades,
  my/ledger, keys rotate·폐기, by-client 취소 라우트. by-client 조회(§7-6 재조회 계약)와
  GET /orders/{id}는 구현돼 있다.
  현행 상태: orders/batch·일괄 취소(DELETE /orders)·by-client 취소 라우트·candles는 이후 구현됨
  (OrderController·MarketController). orders/chance·my/trades·my/ledger·keys rotate·폐기는 미구현 유지.
- accounts의 avg_buy_price는 "0" 고정이다(평단가 산출 미구현).
  현행 상태: V13 원가 프로젝션(position_cost) 도입으로 실측 산출한다(§4-C-1). cost_basis_qty
  ·cost_basis_amount를 함께 노출하고 myAsset WS 델타도 같은 필드 집합을 싣는다. 보유 전량이
  원가 미상인 자산은 여전히 "0"이며 그 "0"은 평단가 0원이 아니라 산출 불가를 뜻한다.

## M1-6 인계 (하네스 계약과 잔여 결정)

M1-5(데모 세션·봇 플릿) 산출물이 하네스(부하·카오스·불변식 검증) 단계에 넘기는 계약이다.

- WS 게시 병목 후보: MarketDataPublisher는 정산 커밋 직후 같은 흐름에서 SimpleBroker로
  동기 게시한다. 부하 시 게시 비용(스냅샷 조회 + 직렬화 + 브로커 전달)이 정산 처리량을
  갉아먹는 구조라, 하네스 부하 측정에서 병목이 확인되면 게시 전용 유계 큐 분리(드롭 정책
  포함)를 검토한다. 게시는 best-effort 계약(§5-2 seq gap 재동기화)이라 드롭이 허용된다.
  스냅샷·realtime 순서 역전의 클라이언트 폐기 규칙은 §5-2 v2.7에 명문화됐다.
- 봇 플릿은 kr.exchange.sim, Spring 프로파일 bots로 분리 기동한다. 봇은 외부 클라이언트
  규약(§3 서명, 자체 HTTP 클라이언트)만 사용하며 내부 서비스 직호출이 없다(카오스 실험에서
  거래소만 죽이고 봇을 살리는 외부 관점 관찰 전제). 파라미터는 exchange.sim.* 설정,
  기본값 근거는 SimProperties 주석. 전역 OPS는 OpsGovernor.setTargetOps 런타임 훅으로
  변경 가능하나 관리 API(PUT /admin/v1/sim/rate) 배선은 미구현이다.
- 봇 결정론: 봇별 시드 PRNG(SplittableRandom, 전역 seed 파생)로 의사결정 열이 재현된다.
  단 도착 시각은 벽시계 의존이라 체결 열 자체의 재현은 보장하지 않는다(재현 대상은
  전략 파라미터·의사결정 분포).
- 데모 세션(§3-7)의 서명 키는 프로세스 한정이다. 재기동·다중 노드에서 세션을 유지하려면
  마스터키 파생 키로 교체해야 한다. 발급 스로틀은 컨트롤러 자체 구현(IP당 10회/60초)이며
  §2-5 전역 rate limit은 여전히 미구현이다.
- 게이트웨이 in-flight 예약은 종결·정산 관측 시에만 즉시 해제된다. 프로젝션 타임아웃
  경로는 주문 row 관측 또는 TTL(30초)로 지연 회수한다(InflightReserves).
- SimFleetIntegrationTest가 플릿 구동 후 검증하는 불변식: 자산별 원장 합 0,
  balance_ledger_check mismatch 0행, command/event_journal gapless, FILLED 락 잔량 0.
  하네스 V3 검증기(C01~C19)는 이 축을 상시 검증으로 확장한다.
- candles, rate limit(§2-5), 미구현 REST 목록, WS 크로스노드 팬아웃 잔여는 M1-5 인계
  절의 서술이 그대로 유효하다.

## V1 마이그레이션 범위

DB 설계 v2.8 §3.1에서 핵심 테이블만 추출했다: assets, markets, users, api_keys,
accounts, balances, orders, trades(+파티션), trade_seq_registry, ledger_transactions,
ledger_entries(+파티션), command_journal(+파티션), event_journal(+파티션), snapshots,
outbox(+파티션), processed_events, idempotency_keys, leader_lease. 부착 트리거와
봉인/append-only 함수 포함. 제외분(transfer_requests, candles, audit_log,
invariant_checks, recovery_runs, shedlock)과 §9 app_role REVOKE, §3.2 partman 자동화는
후속 마이그레이션에서 추가한다. V2__gateway_counters.sql은 게이트웨이 주문 id 채번용
journal_counters('order_id') 시드만 담는다(데이터 시드, 스키마 무변경).
