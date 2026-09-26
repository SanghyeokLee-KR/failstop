# 거래소 서버

Java 21과 Spring Boot로 만든 백엔드다. `engine`은 주문을 매칭하는 순수 Java 모듈이고, `app`은 API, DB 저장, 정산, 복구와 봇을 연결한다.

전체 처리 흐름은 [아키텍처 개관](../docs/reports/프로젝트_이해하기.md), 기능별 소스와 테스트는 [코드 지도](../docs/reference/architecture/프로젝트_코드지도.md)에 있다.

## 준비와 실행

JDK 21과 Docker가 필요하다. Gradle은 저장소의 Wrapper 8.14를 사용한다. 개발 DB와 통합 테스트는 PostgreSQL 17 계열을 기준으로 한다.

아래 명령은 `server/`에서 PowerShell로 실행한다.

```powershell
.\gradlew.bat build
.\gradlew.bat :engine:test
.\gradlew.bat :app:test
```

`build`에는 테스트가 포함된다. 통합 테스트는 Testcontainers로 DB를 띄우므로 Docker가 실행 중이어야 한다. Linux에서는 `./gradlew`를 사용한다.

개발 서버는 DB와 환경 변수를 준비한 뒤 실행한다.

```powershell
.\gradlew.bat :app:bootRun
```

기본 설정은 [application.yml](app/src/main/resources/application.yml)에 있다. 실제 DB 준비와 웹·봇 연결은 [실행 가이드](../실행_가이드.md)를 따른다. 프로덕션 배포 설정은 [deploy/README.md](../deploy/README.md)에서 확인한다.

Windows에서는 한글이 없는 경로에 저장소를 둔다. 컴파일은 성공하지만 테스트에서 `ClassNotFoundException`이 발생한다면 Gradle 테스트 워커의 경로 문제도 확인한다.

## 코드를 읽는 순서

| 영역 | 시작할 곳 |
|---|---|
| 서버 기동 | [ExchangeApplication](app/src/main/java/kr/exchange/ExchangeApplication.java) |
| 엔진 초기화와 복구 | [EngineConfig](app/src/main/java/kr/exchange/config/EngineConfig.java) |
| 주문 접수 | [OrderGateway](app/src/main/java/kr/exchange/api/order/OrderGateway.java), [EngineBridge](app/src/main/java/kr/exchange/engine/EngineBridge.java) |
| 명령 저장 | [CommandJournalAppender](app/src/main/java/kr/exchange/journal/CommandJournalAppender.java) |
| 매칭 | [MatchingEngineImpl](engine/src/main/java/kr/exchange/engine/core/MatchingEngineImpl.java), [OrderBookImpl](engine/src/main/java/kr/exchange/engine/core/OrderBookImpl.java) |
| 정산 | [SettlementService](app/src/main/java/kr/exchange/ledger/SettlementService.java) |
| 스냅샷과 재생 | [EngineRecovery](app/src/main/java/kr/exchange/engine/EngineRecovery.java), [ReplayService](app/src/main/java/kr/exchange/journal/ReplayService.java) |
| 실시간 알림 | [MarketDataPublisher](app/src/main/java/kr/exchange/api/ws/MarketDataPublisher.java) |
| 봇 | [sim/](app/src/main/java/kr/exchange/sim/) |

## 수정 전에 확인할 규칙

주문 명령을 DB에 커밋한 뒤 엔진에 전달한다. 마켓별로 명령을 순서대로 적용하며, 한 명령의 이벤트와 원장·잔고는 함께 정산한다. WebSocket 알림은 정산 커밋 이후에 보낸다.

재시도는 같은 `client_order_id`를 사용한다. 같은 식별자에 다른 내용이 들어온 경우와 아직 조회 결과에 반영되지 않은 경우를 구분해야 한다. 주문 변경은 게이트웨이, 엔진, DB 제약과 관련 테스트를 함께 확인한다.

금액은 `BigDecimal`과 DB `NUMERIC(40,18)`을 사용한다. 수수료와 예약·환급의 반올림은 [DB 설계](../docs/reference/contracts/백엔드_DB_설계.md)를 따른다. 매칭에 필요한 시각과 수수료율 등은 명령에 기록해 재생할 때도 같은 입력을 사용한다.

리더는 한 노드지만 매칭 처리 루프는 마켓별이다. 복구에는 마켓별 스냅샷과 저널을 사용한다. 명령 순번, 마켓 이벤트 순번, WebSocket 순번을 같은 축으로 비교하지 않는다. 자세한 규칙은 [ADR 0022](../docs/reference/adr/0022-market-partitioned-command-journal.md)와 [ADR 0023](../docs/reference/adr/0023-replay-bound-and-cursor-axis.md)에 있다.

## DB와 검증

[마이그레이션 폴더](app/src/main/resources/db/migration/)에는 V0~V47.1과 관제센터의 V70~V73이 있다. V48~V69는 마켓별 리더 작업의 몫이라 비어 있고, 계속 쓰는 DB에 그보다 먼저 V70 이후를 넣지 않게 기동 때 적용 순서를 점검한다(DB 설계 §10.6). 이미 적용한 파일은 주석을 포함해 수정하지 않고 새 버전으로 변경한다. 실행 중인 DB에 어디까지 적용됐는지는 별도로 확인해야 한다.

API 요청·응답과 인증은 [API 설계](../docs/reference/contracts/백엔드_API_설계.md), 장애와 데이터 검사는 [하네스 검증 설계](../docs/reference/contracts/하네스_검증설계.md)를 따른다. 테스트 위치와 실행 범위는 코드 지도에서 찾을 수 있다.
