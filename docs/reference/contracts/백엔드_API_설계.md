# API 설계 (v2.60)

2026-09-20 학교 배포 예외: 관리 UI의 단일 운영자는 HTTPS 비밀번호 게이트웨이로 로그인하며 내부 관리 API는 기존 mTLS·단기 세션 검사를 유지한다. 3-6절의 PC별 인증서 전제와 달리 앱 Nginx가 신뢰 경계에 포함된다. 운영자 한 명과 `VIEWER`·`BOT_OPS`에 한정한 [ADR 0031](../adr/0031-school-admin-password-gateway.md)을 함께 읽는다. 거래 API·서명·승인 검증 규칙은 바꾸지 않는다.

거래소의 요청·응답, 인증, 주문 재시도와 실시간 채널을 정의한다. 클라이언트를 수정할 때는 2절의 공통 규칙을 확인한 뒤 4절 REST 또는 5절 WebSocket으로 이동한다. 관리자 기능은 3-6절 인증과 6절을 함께 읽는다.

거래 정확성, 장애 복구, 성능, 편의성 순으로 설계한다. 같은 주문의 재시도, 정산 후 알림과 리더 세대 검사는 각 API에서 유지해야 할 조건이다. 전체 처리 흐름은 [아키텍처 개관](../../reports/프로젝트_이해하기.md)에 있다.

성능 관련 기준은 2-5-a절의 요청 수용량과 DB 대기, 7-4절의 지연 목표, 7-5절의 커넥션 풀, 6-A-a절의 용량 관리에서 확인한다. 계층별 CPU에 대한 계약은 이 API 문서에 정의되어 있지 않다. 배포·관측의 남은 범위는 [로드맵](../../ROADMAP.md)을 따른다.

---

## 목차
- [0. 시스템 개요 및 아키텍처](#0)
- [1. Spring 생태계 라이브러리 선정표 (기술 검증 반영 버전)](#1)
- [2. 공통 규약 (버전·엔벌로프·에러·멱등성·rate limit·페이지네이션)](#2)
- [3. 인증/서명 방식 설계 (거래 + 관리 평면)](#3)
- [4. REST API 전체 명세](#4)
- [5. WebSocket(STOMP) 채널 명세](#5)
- [6. 관리/시뮬레이션/카오스/HA API](#6)
- [7. 부록: DB 스키마·결정론 규칙·로드맵·SLO](#7)
- [8. 적용한 검증 반영 사항 (요약)](#8)

---

<a name="0"></a>
## 0. 시스템 개요 및 아키텍처

### 0-1. 배포 단위 (Gradle 멀티모듈)

```
exchange-platform/   (목표 구조. 정정 2026-09-10: 실제 Gradle 모듈은 engine 과 app 둘뿐이다.
                      server/settings.gradle.kts:3 이 include("engine", "app") 이다.
                      아래 다섯 갈래는 패키지 경계로만 있고 배포 단위로 갈려 있지 않다)
├── common-dto/        순수 DTO·enum (OrderRequest, Side, OrderType…), 순환의존 방지
├── exchange-core/     순수 Java 매칭엔진 + 오더북 + 복식부기 원장 + 불변식 검증
│                       (Spring/락/I/O/벽시계/난수 금지 = 결정론 보장)
├── exchange-api/      REST/WS 공개 API, 인증, 게이트웨이, 시퀀서   ← 배포단위 A (다중 인스턴스)
├── exchange-admin/    관리/시뮬레이션/카오스 제어 API (분리 포트·분리 네트워크) ← 배포단위 C
└── bot-simulator/     봇 플릿 (가상 스레드) + 부하 생성            ← 배포단위 B (독립 스케일)
```

**핵심 근거**: 카오스 실험 시 배포단위 A(거래소)만 죽이고 B(봇)는 살려야 "외부 관점 자동 복구"를 관찰할 수 있다. 같은 JVM이면 불가능하다.

### 0-2. 인프라 토폴로지 (기술 검증 미정의 항목 확정)

```
                         ┌──────────────── 관측 스택 ────────────────┐
                         │ Prometheus ← Micrometer  |  Grafana        │
                         │ Tempo ← OTel Tracing      |  Loki ← Logs    │
                         └──────────────────────────────────────────┘
[봇/클라이언트]
   │
   ▼
[L7 LB / Reverse Proxy : Nginx or HAProxy or Envoy]   ← 신규: exchange-api 앞단 LB
   │  대상 그룹 둘(정본 6.4). 쓰기=`/actuator/health/readiness`(REFUSING_TRAFFIC 이면 제외)
   │  조회=`/actuator/health/read`(팔로워는 조회 200 이라 대상 그룹에 남는다)
   ▼
[exchange-api × N (Docker/K8s)]                        ← 신규: 컨테이너 오케스트레이션
   │ REST 주문 (Spring MVC + Virtual Threads, Resilience4j RateLimiter/CB)
   │ client_order_id (필수 멱등키)
   ▼
[Gateway] 인증·검증·멱등 dedup ──▶ [Sequencer 단일 라이터 + epoch(fencing)]
                                       │ (1) command_journal append + fsync ← 로컬 커밋 내구성(RPO=0 범위는 §7-6 하단)
                                       │ (2) ACK "접수됨" (+ client_order_id 반환)
                                       ▼ (3) 적용
                       [Matching Engine 결정론 상태기계]  ← exchange-core, 단일 스레드
                       리더선출: 임차 보관소(기본 PostgreSQL `leader_lease`, 선택 DynamoDB) + fencing epoch(ADR 0017. 정본 4.2·6.5 대조 미결은 §1 리더선출 행과 §8-3)
                                       │ (4) 같은 트랜잭션: assert_current_leader(holder, epoch) 통과 후 trades + outbox + ledger (fencing_epoch 저장)
                                       ▼
                       [PostgreSQL 프로젝션 (JPA/jOOQ)]   ← (4)의 같은 트랜잭션이 그대로 쓴다
                       [outbox 적재]  발행기가 없어 나가는 경로가 없다. 읽는 곳은 InvariantCheck·SettlementService 둘뿐(§1 메시징/이벤트 행, §7-1)
                                       ▼ (5) 커밋 이후 단일 라이터 스레드가 게시(MarketDataPublisher)
                       [STOMP 게시: 노드마다 내장 SimpleBroker(/topic·/queue)가 자기 WS 세션을 맡는다]  WebSocketConfig.enableSimpleBroker
                       (v2.58, ADR 0034 S6) 노드 사이 전달은 §5-1의 노드 단위 중계다. exchange.ws.relay.mode=broker 이면 게시를 외부 STOMP 브로커(RabbitMQ)의
                       토픽 하나로 나누고, 기본값 simple 은 종전처럼 단일 노드 게시다. 세션 단위 중계(enableStompBrokerRelay)와 Redis 릴레이는 채택하지 않았다
                                                     ▼  실시간 호가/체결/잔고 푸시
[DB HA: PostgreSQL 직접 운영. primary 1대 + standby 1대, 비동기 스트리밍 복제(ADR 0024). synchronous_standby_names는 비운다. DB 자동 페일오버 없음(수동 절차 docs/reference/operations/DB_수동_페일오버.md). failover 포함 RPO 0은 주장하지 않는다. 범위는 §1 DB 자체 HA 행]
[DB HA 목표(v2.60, ADR 0036, 적용 전. 작업 브랜치에서 로컬 검증 중): Patroni 2노드 + etcd 세 표 + 노드별 HAProxy 사이드카, 엄격 동기 복제. 동기 standby가 없으면 쓰기를 멈추고 §2-5-e의 거래 일시 중단으로 거절한다]
[복구: snapshot + journal replay | 정산배치: @Scheduled + ShedLock + fencing]
[시크릿: 로컬=.env / 운영데모=Docker Secret·K8s Secret (secret_key는 AES-GCM 암호화 저장)]
```

**Kafka 미채택**: `outbox` 테이블은 실재한다(`server/app/src/main/resources/db/migration/V12__outbox_2026_08_partition.sql`). 발행기는 없다. `server/app/build.gradle.kts` 와 `server/app/src` 전역에 kafka 가 0건이고, 정본 6.1 전체 구조에도 메시지 브로커가 없다(정본은 데이터베이스 밖 별도 저장소를 리더 임차 하나로 못박는다). 정본 4.3 비채택표에도 Kafka 행이 없어 채택으로도 비채택으로도 등재되지 않았다. **브로커 도입 여부는 미결이다.**

**컨테이너 재기동 정책**: Docker Compose `restart: unless-stopped`(또는 K8s Deployment + livenessProbe)로 kill 후 자동 재기동 → "자동 복구"의 인프라 토대. 카오스 API의 `docker_kill`은 이 재기동 정책과 짝을 이룬다.

**설계 결정 3가지**: ① 매칭=메모리(단일 라이터)·DB=비동기 프로젝션, ② 이벤트 소싱(command_journal)으로 복구 리플레이, ③ 활성-대기 리더선출(epoch fencing)로 HA. fencing 검증은 command_journal 삽입에 한정되지 않는다. 체결·취소·catch-up·스냅샷을 포함한 모든 엔진 기원 쓰기 트랜잭션이 `assert_current_leader(holder, epoch)`를 통과해야 커밋되며, event_journal·trades·snapshots에 `fencing_epoch`를 저장한다(§7-1). split-brain 차단 주장은 이 계약이 성립할 때에 한한다. (v2.59, ADR 0034 S7) 리더 모드 P3(파티션 리더)에서는 활성-대기 단위가 파티션이다. 리스 행 `matching-p0`·`matching-p1`을 두 앱 노드가 하나씩 쥐고 그 파티션 마켓을 매칭하며, 한 노드가 죽으면 다른 노드가 리스 만료와 유예 뒤 그 파티션을 넘겨받는다. 쓰기 권한 검사는 기록할 행의 마켓에서 리스 행을 찾으므로(DB 설계 §5.4-b) 한 노드가 다른 파티션 마켓에 쓰지 못한다. 모드 전환은 DB 소유자가 정지 창에서 하고(DB 설계 §5.4-e) 앱 설정은 모드를 정하지 않는다. DB 자동 페일오버는 여전히 없다.

---

<a name="1"></a>
## 1. Spring 생태계 라이브러리 선정표 (기술 검증 반영)

> **버전 경로 확정 (기술 검증 반영)**: 초안은 "Spring Boot 4.1 주력"을 권했으나, **회복탄력성 계층을 전면 의존하는 Resilience4j가 SB4(Spring Framework 7)를 아직 GA로 지원하지 않는다**(spring-boot3 모듈은 Spring 6 의존, 이슈 #2351/#2371 오픈). CircuitBreaker·RateLimiter·Bulkhead가 미지원되면 신뢰성 캡스톤의 핵심 계층이 공백이 된다. 따라서 **과제 요구("3.x")와도 부합하고 전 스택이 GA로 검증되는 Spring Boot 3.5.16(3.5 최종) + Java 21 LTS 경로를 확정**한다. 보안 패치는 3.5 브랜치가 커버되는 시점까지 유효하며, EOL 리스크는 "검증된 회복탄력성 스택"과의 트레이드오프에서 후자를 택했다.

| 관심사 | 최종 선택 | 확정 버전 (2026-07 기준) | 선택 이유 |
|---|---|---|---|
| **런타임 기반** | **Spring Boot 3.5.16 + Java 21** | SB 3.5.16 | 전 스택 GA 검증. Resilience4j·Chaos Monkey 등 회복탄력성/카오스 도구가 정식 지원되는 유일한 안정 경로. |
| **REST 계층** | Spring MVC + **플랫폼 스레드**(`spring.threads.virtual.enabled=false`) | SB BOM | 정정(2026-09-21): 가상 스레드를 껐다. 학교 app-1에서 HikariCP `ConcurrentBag`의 연결 인계가 가상 스레드 대기자에게 성립하지 않아 반납·추가·예약해제가 `Thread.yield` 루프에 갇히는 라이브락이 발생했다(ADR 0033, §7-5). 이 뒤는 종전 선정 사유이며 현 설정이 아니다. ~~DB-heavy 워크로드에서 가상 스레드가 tail latency 우세.~~ JPA/JDBC 그대로 사용 → 완성도·유지보수 유리. |
| **실시간 푸시** | **WebSocket + STOMP** (`spring-boot-starter-websocket`) | SB BOM | 호가/체결/잔고 다채널 구독. 공개 `/topic/**`=Redis Pub/Sub 릴레이, 비공개 `/user/queue/**`=**외부 STOMP 브로커 릴레이(RabbitMQ/ActiveMQ)로 크로스노드 user-destination 라우팅**(§5-1). 정정(v2.58, ADR 0034 S6): 채택한 배선은 노드 단위 중계다. 각 노드의 내장 SimpleBroker가 자기 세션을 맡고, 노드 사이는 RabbitMQ 4.3 STOMP 플러그인의 토픽 하나로 게시를 나눈다. 중계 클라이언트는 spring-messaging의 TCP STOMP 클라이언트이고 `io.projectreactor.netty:reactor-netty-core`(SB BOM)가 필요하다. 앞 문장의 Redis 릴레이와 세션 단위 브로커 릴레이는 채택하지 않았다(§5-1). |
| **영속성(도메인)** | ~~Spring Data JPA / Hibernate 6.6.x~~ **미채택** | 없음 | 정정(2026-09-10): 의존과 사용처가 저장소에 0건이다. 실제 배선은 `spring-boot-starter-jdbc` 의 `JdbcClient` 하나다(`server/app/build.gradle.kts:19`). 낙관적 락은 `@Version` 이 아니라 SQL 로 건다. |
| **영속성(핫패스 조회)** | **미채택**. 정정(2026-09-10): jOOQ 의존이 저장소에 0건이고 핫패스 조회도 `JdbcClient` 로 쓴다. 이 뒤는 종전 선정 사유이며 현 배선이 아니다. ~~jOOQ~~ (Community, Apache 2.0) | **SB BOM 위임(3.5.x 관리 라인=3.19.x)** | 호가 집계·체결 페이징·리포팅. 컴파일타임 타입안전. 정정(v2.4): 개별 핀 `3.20/3.21` 제거. jOOQ는 SB BOM 관리 항목이라 개별 핀은 버전 고정 원칙(하단) 위반이자 BOM 라인 오버라이드(Q12의 Flyway와 동일 논리). |
| **DB 마이그레이션** | **Flyway** | **SB BOM 위임(3.5.x 관리 라인=11.x)** | SQL-first·저마찰. Liquibase 5.0 FSL 라이선스 이슈 회피. 정정(v2.2): 개별 핀 `12.9.0` 제거. Flyway는 SB BOM 관리 항목이라 개별 핀은 버전 고정 원칙(하단) 위반이자 BOM 메이저 오버라이드(SB 3.5 자동구성이 검증한 11.x와 상이)였다. |
| **메시징/이벤트** | **미채택**(아웃박스 테이블만 적재, 발행기 없음) | (없음) | 정정(v2.44): 초안이 Spring for Apache Kafka·Kafka 4.x KRaft 였으나 배선하지 않았다. `server/app/build.gradle.kts` 와 `server/app/src` 전역에 kafka 가 0건이고 정본 6.1 전체 구조에 메시지 브로커가 없다. 같은 트랜잭션 `outbox` INSERT 는 실재하고 발행 경로만 없다(§7-1). 브로커 도입 여부는 정본 4.3 비채택표에도 없어 미결이다. |
| **캐시/분산 프리미티브** | **미배선**. 정정(2026-09-10): Spring Data Redis 의존이 0건이고 Redis 서비스도 어느 컴포즈 파일에도 없다. Pub/Sub 릴레이와 nonce 저장은 현재 PostgreSQL 이 진다 | 없음 | Pub/Sub 릴레이·nonce 저장. 정정(v2.40): Redisson 은 저장소에 의존이 없다. 분산 RateLimiter 용도로 남은 것은 M2 선행 조건이고 현 배선이 아니다. 리더선출은 이 행의 범위가 아니다(아래 행). |
| **회복탄력성** | **Resilience4j** | **`resilience4j-spring-boot3` 2.4.0** | 정정(2026-09-10): 실사용처는 카오스 프로브의 CircuitBreaker 하나다(`server/app/src/main/java/kr/exchange/chaos/ChaosProbeGateway.java`). RateLimiter·Bulkhead·Retry·TimeLimiter 는 미사용이고 레이트리밋은 자체 구현이다. Micrometer 통합. 버전 정정: 3.x는 미존재, 최신 GA=2.4.0. SB3.5에서 GA 검증. |
| **리더선출/분산락** | **임차 보관소 + fencing epoch**(매칭) / ShedLock **미배선**(배치). 정정(2026-09-10): ShedLock 의존이 저장소에 0건이다 | `LeaderLeaseStore` 구현 2종(기본 PostgreSQL, 선택 DynamoDB, AWS SDK v2) / ShedLock 7.7.0 | 매칭 단일 활성화=임차 획득. 정정(v2.40): 초안이 Redisson RLock 이었으나 채택하지 않았다. 저장소에 redisson 의존이 0건이다. 기본값은 `PostgresLeaderLeaseStore`(`exchange.leader.lease.provider=postgres`)이고, ADR 0017 이 임차 **갱신**을 커밋 경로 밖으로 빼는 선택지로 `DynamoDbLeaderLeaseStore` 를 더했다. 임차 **검증**(`assert_current_leader`)은 어느 쪽이든 거래 트랜잭션 안에 남는다. 배치 중복방지=ShedLock. **주의: ShedLock은 correctness 보장 X → fencing 병행 필수**(적용 범위: 모든 엔진 기원 쓰기 트랜잭션, §7-1). **정본 대조 미결(v2.44)**: 정본 4.2 선택표는 리더 임차를 "DynamoDB 항목 1개" 로, 정본 6.5 는 "임차 갱신은 DynamoDB 항목 하나에 조건부 쓰기" 로 단정한다. 이 문서는 저장소 실물을 적었다(`server/app/src/main/resources/application.yml` 77행 `provider: postgres`, `kr/exchange/config/LeaseStoreConfig.java` 가 고르는 구현 2종 `PostgresLeaderLeaseStore`·`DynamoDbLeaderLeaseStore`). 정본을 "기본 postgres·선택 dynamodb" 로 고칠지, 배포 기본값을 dynamodb 로 바꿔 정본에 맞출지는 미결이며 이 문서가 정하지 않는다(§8-3). |
| **관측성(메트릭)** | **Actuator + Micrometer(Prometheus registry) + Grafana** | **버전 BOM 위임(개별 핀 제거)** | 정정: `micrometer-registry-prometheus` 마이크로버전 개별 고정 제거(‘BOM 위임’ 원칙과 모순 해소). 스타터만 추가. |
| **관측성(트레이싱)** | Micrometer Tracing(OTel Bridge) / 백엔드 **미배선** | SB BOM | 정정(2026-09-10): Tempo 가 어느 컴포즈 파일에도 없다. `docs/reference/contracts/하네스_CICD_IaC.md` 도 같은 사실을 저장소에 없는 것으로 적는다. 확정 스택이 아니라 목표다. |
| **관측성(로그)** | Logback JSON / 중앙 집계 **미배선** | 없음 | 정정(2026-09-10): Loki 가 어느 컴포즈 파일에도 없다. 노드 간 로그 상관분석은 아직 수단이 없다. |
| **테스트** | **JUnit5 + Testcontainers + Awaitility + jqwik**. 정정(2026-09-10): ArchUnit 은 의존이 0건이라 뺀다 | **Testcontainers·Awaitility=SB BOM 위임** / ArchUnit 1.4.2·jqwik 최신 GA(BOM 미관리 → 개별 핀 유지) | 실 PG 컨테이너. 비동기=Awaitility(sleep 금지). 경계=ArchUnit. 불변식=jqwik. 정정(v2.4): TC·Awaitility 개별 핀 제거. 둘 다 SB BOM 관리 항목(버전 고정 원칙 정합). 정정(v2.44): 컨테이너 열거에서 Kafka·Redis·Toxiproxy 를 함께 뺀다. 종전 정정은 Kafka 만 빼고 같은 기준을 나머지 둘에 적용하지 않았다. `server/app/build.gradle.kts` 의 Testcontainers 선언은 `spring-boot-testcontainers`·`junit-jupiter`·`postgresql` 셋뿐이고 kafka·redis·toxiproxy 가 각각 0건이다. Toxiproxy 는 테스트 컨테이너가 아니라 하네스 카오스 스택이 띄운다(`harness/docker-compose.chaos.yml` 의 `chaos-toxiproxy`). |
| **부하 테스트** | **k6 (메인)** + Gatling(발표용, 선택) | k6 2.0 / Gatling 3.13+ | Thresholds→exit code로 SLO CI 게이팅. WS 부하 지원. |
| **카오스** | **Toxiproxy(네트워크) + Chaos Monkey(앱) + docker kill(인프라)** | toxiproxy 모듈=**TC(SB BOM) 위임**(v2.4, TC 개별 핀 제거와 동일 논리) / **chaos-monkey-spring-boot 3.x (SB3 정합, BOM 미관리 → 개별 관리)** | 정정: 초안의 "Chaos Monkey 4.0.0=SB3 타깃" 경고는 **사실과 반대**(4.0.0은 SB4.0.2 기반=SB4 지원). SB3.5 경로에선 **3.x 라인을 사용**. Toxiproxy=네트워크, Chaos Monkey=앱 계층 상호보완. |
| **매칭엔진 동시성** | **LMAX Disruptor** | 4.0.0 (min JDK 11, Java 21 OK) | 락-프리 링버퍼. 참고: 4.0.0(2023-09) 이후 유지보수 정체 → **1차 `ArrayBlockingQueue`로 결정론 완성 후, 벤치마크로 실측 이득 확인되면 교체**(handler 인터페이스 동일). |
| **커넥션 풀** | **HikariCP** | SB BOM | 신규: VirtualThreads+JDBC 조합의 **pinning·풀 사이징** 명시(§7-5). 풀 고갈이 p99 SLO 직결. 정정(2026-09-21): 가상 스레드 조합 자체를 철회했다(ADR 0033). 풀을 작게 고정하는 기준과 트랜잭션 경계를 짧게 유지하는 기준은 유지한다. |
| **DB 자체 HA** | **PostgreSQL 직접 운영**(primary 1대 + standby 1대, 비동기 스트리밍 복제) | (인프라) | 정정(v2.44): 초안이 Patroni(+etcd)+HAProxy 였으나 채택하지 않았다. 그때 `server`·`deploy`·`harness` 전역에 patroni·etcd·haproxy 가 0건이었다(v2.60 정정 참조). 정정(ADR 0024): 상시면 복제를 비동기 2노드로 내린다. `synchronous_standby_names` 를 비우므로 standby 상실이 primary 커밋을 멈추지 않는다. 배선은 `deploy/compose.node-data.yml` 이다. 종전의 동기 정족수(`ANY 1`, standby 2대, ADR 0018)는 `harness/scale/standby_up.sh` 가 거는 카오스·규격 평면 실험 구성으로만 남고 상시면 계약이 아니다. **failover 포함 RPO=0 은 주장하지 않는다.** 유실 창은 복제 지연만큼이다. 정정(v2.45): 2026-09-12 실측에서 복제가 따라오는 중 급사는 ACK 88건 중 0건, 대기 서버를 8초 멈춘 뒤 급사는 ACK 122건 중 36건이다(`harness/evidence/failover-rpo-20260912/`). 정상 구간의 0을 무손실 보장으로 읽지 않는다. 무손실 failover 요건은 DB§7.2 가 소유하며 현행 구성은 그 요건을 충족하지 않는다. **DB 자동 페일오버가 없다**(저장소 전역에 `pg_ctl promote`·`pg_promote()`·`promote_trigger_file` 0건). 수동 승격 절차는 `docs/reference/operations/DB_수동_페일오버.md` 다. 진입 라우팅은 `deploy/nginx-edge.conf.template` 의 두 업스트림(`exchange_read`·`exchange_write`)이 맡는다. | 정정(v2.44): 초안이 Patroni(+etcd)+HAProxy 였으나 채택하지 않았다. 그때 `server`·`deploy`·`harness` 전역에 patroni·etcd·haproxy 가 0건이었고(v2.60 정정 참조), 정족수는 `harness/scale/standby_up.sh` 가 `ALTER SYSTEM SET synchronous_standby_names = 'ANY 1 (...)'` 로 직접 건다(정본 6.3, ADR 0018). failover RPO=0은 무조건 성립하지 않는다. 성립 조건(운영 요건): 동기 standby 강제, 정산·저널 트랜잭션에 `synchronous_commit=on`(remote flush 이상), PITR 아카이브 병행. 비동기 standby failover·스토리지 손실·조기 승격에서는 유실 가능. 진입 라우팅은 `deploy/nginx-edge.conf.template` 의 두 업스트림(`exchange_read`·`exchange_write`)이 맡는다. **DB 클러스터 리더 선출과 STONITH 의 담당 주체는 미결이다**(정본 13.2 의 10 이 승격 시 동기 복제 대상 재설정을 미배선으로 등재). 정정(v2.60, ADR 0036): 사용자가 2026-09-24~25 Patroni(etcd 세 표: db-1·db-2·bot-1)와 HAProxy를 채택했다. 복제는 엄격 동기(`synchronous_mode`, `synchronous_mode_strict`)이고 동기 standby가 없으면 쓰기를 멈춘다. 리더 선출은 Patroni, 동기 대상 재설정은 Patroni의 `/sync` 관리가 맡고, 워치독은 AWS에서 softdog이다. 2026-09-25 현재 작업 브랜치 `claude/patroni`에 앱 코드(§2-5-e·§4-A-7)와 로컬 실험 스택(`harness/patroni/`)이 있고, `deploy/`와 학교 배포에는 Patroni·etcd·HAProxy 구성이 없다. 적용 전 환경은 위 비동기·수동 절차 그대로다. 설계는 `docs/reference/architecture/DB_자동전환_Patroni_설계.md`, 앱의 응답은 §2-5-e다. |

**버전 고정 원칙**: Micrometer/JUnit/Hibernate 개별 고정 금지 → **Spring Boot BOM 위임**. 스타터만 추가. (표에서 개별 핀은 Resilience4j·ShedLock 등 BOM 미관리 항목에만 남긴다.) 정정(v2.44): 종전 열거의 Kafka-client 를 뺀다. 메시징/이벤트가 미채택이라 BOM 위임 대상 자체가 없다(위 메시징/이벤트 행).

---

<a name="2"></a>
## 2. 공통 규약

### 2-1. 기본 규약

| 항목 | 채택안 |
|---|---|
| REST 버전 prefix | `/api/v1` |
| WS 버전 prefix | `/ws/v1` (STOMP `accept-version:1.2` 별도) |
| Admin 버전 prefix | `/admin/v1` |
| 스키마 버전 | 모든 REST/WS 페이로드에 `schema_version` 포함. 기본은 `"1.0"` 이고 관리자 응답 일부는 응답별 판을 쓴다(§6, v2.50 명시) |
| Base URL | `https://api.exchange.local` (거래) / `https://admin.exchange.local` (관리, 분리) |
| 시각 표현 | **항상 UTC, 밀리초 3자리 고정** `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (예: `2026-07-21T09:00:01.000Z`). DB는 `TIMESTAMPTZ`. 모든 예시 통일. **예외**: 서명 프리미티브(§3-3 JWT payload의 `timestamp`)는 밀리초 문자열이 아니라 **정수 epoch ms**(`1784624400123`)를 사용한다. HMAC canonical 계산의 결정론·간결성을 위한 명시적 예외이며, 이 예외는 JWT payload 한정이다. |
| 금액/수량 | 문자열 표기 `NUMERIC(40,18)` (JSON float 금지, `"0.00000012"`; DB 설계 v2.0+와 동일, 정수부 22자리로 `qty×price` 중간값 오버플로 방지). Java `BigDecimal`+명시적 `RoundingMode`. |
| 마켓 표기 | `KRW-BTC` (quote-base) |
| Content-Type | `application/json; charset=utf-8` (요청도 강제. 불일치 시 `415`) |
| 상관 헤더 | 모든 응답에 `X-Request-Id`, `traceparent` 반환 |

### 2-2. 표준 응답 엔벌로프 (일관성 확정)

**모든 리스트 반환 엔드포인트**는 예외 없이 다음 엔벌로프를 사용한다(candles·ticker·markets·accounts 포함):

```json
{ "schema_version":"1.0",
  "data": [ ... ],
  "pagination": { "next_cursor": "eyJ0cyI6...", "has_more": true } }
```

**단건/집계 객체**(orderbook 스냅샷, orders/chance, 개별 order)는 엔벌로프 없이 객체를 직접 반환하되 `schema_version`을 포함한다. 페이지네이션이 없는 리스트도 `pagination:{next_cursor:null,has_more:false}`로 통일한다.

### 2-3. 표준 에러 포맷 (관측성 필드 확정)

```json
{ "error": {
    "name": "invalid_price",
    "message": "가격 단위(tick)가 올바르지 않습니다.",
    "detail": { "tick": "1000" },
    "trace_id": "3fa85f64...",
    "request_id": "01J8Z...",
    "retry_after": null
} }
```

| HTTP | name | 의미 |
|---|---|---|
| 400 | `invalid_parameter` | 파라미터 형식·조합 오류(§4-B-1 상호배타·금지 조합 위반 포함, `detail.constraint`에 위반 규칙명). (v2.57, ADR 0034 S5) 마켓 힌트 헤더 `X-Exchange-Market`이 요청이 가리키는 마켓과 다르거나 마켓 없는 일괄 취소에 실렸으면 `market_hint_mismatch`, 같은 이름의 헤더가 둘 이상이거나 값에 쉼표가 있으면(여러 마켓을 이은 한 줄) `market_hint_duplicate`, 일괄 주문 원소의 마켓이 둘 이상이면 `batch_single_market`이다(§2-5-d, §4-B-6) |
| 400 | `invalid_price` / `invalid_volume` | 가격단위(tick)/수량단위 위반 |
| 400 | `under_min_order` / `over_max_order` | 최소 미달 / **최대 초과(신규)** |
| 400 | `insufficient_funds` | 주문 가능 잔고 부족. 리더 모드 P2(ADR 0034 S3, v2.54)에서는 저널 append 트랜잭션 안의 DB 예약이 판정 권위다. 잔고가 모자라면(DB 23514, `chk_balance_nonneg`) 그 트랜잭션 전체가 롤백된 뒤 이 코드로 끝나고 저널·채번·예약이 남지 않으므로 주문은 확정 미접수다. 같은 `client_order_id`로 다시 보내면 새 접수로 판정된다(§2-4) |
| 400 | `client_order_id_required` | **멱등키 누락(신규, §2-4)** |
| 401 | `jwt_verification` | 서명 검증 실패. v2.2: `expired_jwt` 삭제. payload에 `exp` 클레임이 없고(§3-3) 신선도 위반은 §3-4의 `timestamp_out_of_window`로 일원화(도달 불가 코드 정리) |
| 401 | `invalid_query_payload` | canonical 서명(method+path+params) 불일치 |
| 401 | `nonce_used` | nonce 재사용(재전송 공격) |
| 401 | `timestamp_out_of_window` | `timestamp`가 허용창 밖 |
| 401 | `auth_throttled` | **반복 401 임계 초과 일시 차단(신규)** |
| 403 | `out_of_scope` | API Key 권한 부족(조회키로 주문 등), 또는 세션 종류가 리소스 요구와 불일치(데모·API 키로 회원 전용 `/me` 접근, §3-8) |
| 403 | `admin_forbidden` | **관리 스코프/승인 부족(신규, §3-6)** |
| 409 | `msp_source_not_connected` | **관제센터 조작에 필요한 원천(Prometheus·Alertmanager·Databasus·LLM) 설정이 없다. `detail.source`(v2.52.1 설계, §6-F)** |
| 502 | `msp_upstream_failed` | **관제센터 조작 중 원천 호출 실패. `detail.source`, 적용 여부를 모르면 `detail.outcome=UNKNOWN`(v2.52.1 설계, §6-F)** |
| 409 | `msp_policy_denied` | **관제센터 정책 엔진 거부. `detail.rule`에 규칙 id, `detail.reason_code`에 사유 분류(v2.52.1 설계, v2.52.2 구현, §6-F)** |
| 409 | `ai_action_not_open` | **이미 처리·만료된 AI 조치 요청(v2.52.1 설계, §6-F)** |
| 409 | `threshold_revert_conflict` | **최신 변경이 아니거나 현재값이 달라 되돌릴 수 없는 임계값 변경(v2.52.1 설계, §6-F)** |
| 409 | `alert_already_silenced` | **이미 사일런스에 눌린 경보에 보류 요청(v2.52.1 설계, §6-F)** |
| 403 | `member_session_required` | **데모 세션으로 쓰기 시도(신규, §3-7 읽기 전용 계약). 봇·회원은 해당 없음** |
| 400 | `invalid_transfer_asset` | **KRW 외 자산 입출금 요청(신규, §4-E)** |
| 400 | `invalid_transfer_amount` | **입출금 금액이 0 이하·소수점·한도 초과·자릿수 위반(신규, §4-E)** |
| 403 | `transfer_not_owned` | **타인의 입출금 요청 조작 시도(신규, §4-E)** |
| 409 | `transfer_not_pending` | **이미 확정·취소된 입출금 요청의 재처리(신규, §4-E)** |
| 400 | `invalid_support_input` | **문의 분류 어휘 위반 또는 제목·본문 길이 위반(신규, §4-G)** |
| 409 | `support_already_answered` | **답변된 문의에 재답변 시도(신규, §4-G)** |
| 400 | `weak_password` | **회원 비밀번호 최소 길이(8자) 미달(신규, §3-8)** |
| 400 | `invalid_email` | **회원 이메일 형식 오류(신규, §3-8)** |
| 401 | `invalid_credentials` | **로그인 이메일·비밀번호 불일치. 존재 여부 비노출(미가입도 동일 코드·메시지·유사 지연) (신규, §3-8)** |
| 401 | `refresh_reuse` | **소각·폐기된 리프레시 재제출. 도난 신호로 family 전체 폐기(신규, §3-8)** |
| 409 | `refresh_in_progress` | **동시 회전 경합(v2.39). 소각 10초 이내 + family 생존이면 도난이 아니라 경합으로 보고 폐기하지 않는다. `Retry-After` 동반, 재시도 대상** |
| 401 | `session_expired` | **리프레시 만료·부재(신규, §3-8)** |
| 403 | `account_suspended` | **`users.status` SUSPENDED·CLOSED 계정의 로그인·갱신 거부(신규, §3-8)** |
| 409 | `email_taken` | **이메일 중복 가입(신규, §3-8)** |
| 423 | `account_locked` | **로그인 실패 누적 백오프 잠금(+`Retry-After`, 신규, §3-8)** |
| 423 | `trading_suspended` | **(v2.60 신설, ADR 0036) DB 쓰기 불가로 닫힌 쓰기 게이트의 거절.** 동기 standby가 없어 커밋이 끝나지 않는 동안 `/api/v1/**`와 관리 평면의 POST·PUT·PATCH·DELETE를 DB에 닿기 전에 거절한다. 이 시도가 이 노드에서 쓴 것은 없다. 인그레스가 앞선 시도를 다시 보낸 것일 수 있어, 같은 요청의 앞선 시도가 재개 뒤 커밋될 수 있다(같은 날 설계 검토로 좁힌 뜻). 재개를 §4-A-7로 확인한 뒤 같은 `client_order_id`(이체는 같은 `Idempotency-Key`)로 다시 내거나 재조회(§4-B-4 (a))로 결과를 정하고, 새 키로 다시 내지 않는다. `Retry-After`와 `detail.reason`·`since`·`status_url`을 싣는다(§2-5-e) |
| 404 | `order_not_found` | 주문 없음 |
| 405 | `method_not_allowed` | **허용되지 않은 메서드(신규)** |
| 409 | `duplicate_client_order_id` | 멱등키 충돌(내용 상이) |
| 409 | `order_in_progress` | **동일 멱등키 in-flight 처리 중(신규, §2-4)** |
| 409 | `requery_deferred` | **by-client 재조회의 노드 용량 초과. 판정 보류이며 저널 존재를 주장하지 않는다(+`Retry-After`, 신규 v2.18, §2-5-a). 클라이언트 동작은 `order_in_progress`와 동일: 재조회 반복으로 수렴** |
| 413 | `payload_too_large` | **요청 바디 상한 초과(신규, 기본 64KB)** |
| 415 | `unsupported_media_type` | Content-Type 불일치 |
| 422 | `domain_rejected` | 도메인 거부(하위 `reject_reason`, §4-B-1) |
| 429 | `too_many_requests` | rate limit 초과, 또는 노드 수용 제한 초과 (+`Retry-After`, +위반 버킷 `account`\|`node`, §2-5). v2.55부터 마켓 접수 경합으로 저널 전에 끝났고 이 요청이 접수되지 않았음이 확정된 주문 생성·취소도 이 코드다(`detail.bucket=market`, §2-5-b, ADR 0034 S4 코드부터). 같은 경합이라도 확정할 수 없으면 `409 order_in_progress`다. (v2.56, ADR 0034 S4b) append가 DB 연결 장애로 끝났거나, 그 마켓의 게이트를 연 노드가 리더 자격 거부(fencing 거부, 기간 종료)를 받았어도 저널 확인(V47)이 이 요청의 확정 미접수를 답한 주문 생성은 같은 모양(`detail.bucket=market`)의 이 코드다. 게이트를 연 노드의 리더 자격 거부로 끝난 취소도 이 코드다(§2-5-c) |
| 418 | `auto_banned` | 지속 위반 IP 밴 (+해제 조건, §2-5) |
| 500 | `server_error` | 일시적 서버 오류. (v2.56) DB 연결 장애와 리더 자격 거부로 끝난 주문 생성·취소는 이 코드로 내지 않는다(§2-5-c). 재전송이 안전하지 않은 쓰기(멱등 키가 없는 POST·PATCH·PUT·DELETE, 주문·이체 밖)가 연결 끊김(롤백·커밋 실패, 문장 중 08xxx·57P01~03)이나 자원 부족(53xxx)으로 끝나면 이 코드다. 커밋됐을 수 있고, 이 코드는 인그레스가 다른 노드로 다시 보내지 않는다(§2-5-c). 클라이언트 연결이 먼저 끊겨 응답을 쓸 수 없는 요청은 이 코드로 렌더하지 않는다 |
| 503 | `service_unavailable` | 페일오버 중, 또는 일시적 DB 접근 실패(커넥션 획득 타임아웃·트랜잭션 시작 실패·락 타임아웃 등. 인증 계층과 컨트롤러 계층이 동일 코드로 매핑, v2.17) (주문 상태 `UNKNOWN` → 재조회, **+`Retry-After` 필수**). 마켓 라이터 샤드가 멈춘 창에서는 그 마켓의 주문 접수만 이 코드로 거부된다(v2.49, ADR 0022 4단계). 서버 설정 `exchange.engine.accept-lock-wait`를 켠 노드에서는 마켓 접수 락 대기 상한을 넘긴 주문·취소도 저널 전에 이 코드로 거부된다(v2.53, 기본 꺼짐). 잠금 대기 초과(DB 55P03)도 이 코드로 매핑한다(v2.54 계약). 적용은 ADR 0034 S4에서 쓰기 업스트림의 `max_fails`·`proxy_next_upstream` 조정과 `accept-lock-wait`·`accept-lock-timeout` 값과 함께 하고, 그 전의 실제 응답은 `500 server_error`다. 같은 시점부터 주문 생성·취소 경로에서 나는 일시 DB 오류(55P03·57014·40P01·40001)는 배치 주문(§4-B-6)의 그 원소를 `in_progress`로, 일괄 취소(§4-B-3)의 그 주문을 `failed[].reason=service_unavailable`로 적고 다음 원소로 넘어가며, 회원 폐쇄(§6-E)는 그 주문을 미완료로 센다. **(v2.55 개정, ADR 0034 S4)** S4 코드는 인그레스(`max_fails`·`proxy_next_upstream`)를 바꾸지 않는다. 오픈소스 nginx에는 리더의 일시 `503`을 재시도하면서 노드 장애로 세지 않는 설정이 없어서다(S4 설계 3절 N). 그래서 주문 생성·취소 경로의 접수 경합(접수 락 대기 상한 초과와 append 트랜잭션의 55P03·57014·40P01·40001)은 이 코드로 내지 않는다. 이 요청이 접수되지 않았음이 확정되면 `429 too_many_requests`(`detail.bucket=market`)이고, 확정할 수 없으면(같은 키의 다른 요청이 관여, 저널 판정 실패, 이미 접수된 주문의 재전송) `409 order_in_progress` + `Retry-After`다(§2-5-b). 위 원소별 처리에서 그 경우의 사유도 `too_many_requests`나 `order_in_progress`다. 주문 생성·취소 밖 경로(인증 계층과 컨트롤러 계층)의 55P03에는 v2.54의 이 코드 매핑을 적용하지 않고 `500 server_error`로 둔다. 인그레스를 바꾸지 않으므로 이 매핑만 켜면 리더를 쓰기 업스트림에서 빼는 경로가 새로 생기기 때문이다. 이 매핑은 파티션별 업스트림을 만드는 ADR 0034 S5에서 다시 정한다. 이 코드로 남는 것은 리더 아님·승격 중·라이터 정지와, v2.55 이전부터 이 코드이던 DB 접근 실패(커넥션 획득 타임아웃·트랜잭션 시작 실패, 접수 append 밖에서 난 57014·40P01·40001)다. **(v2.56 개정, ADR 0034 S4b)** DB 연결 장애(SQLSTATE 08xxx(연결 예외)·53xxx(자원 부족)·57P01·57P02·57P03(관리자 종료, 비정상 종료, 기동 중), 커넥션 획득·트랜잭션 시작 실패, 연결이 끊겨 커밋이나 롤백이 실패한 경우)는 이 노드가 DB에 닿지 못함이 확인됐거나 다시 보내도 한 번의 효과로 모이는 요청일 때 이 코드다. 인증 계층은 모든 요청을, 컨트롤러 계층은 안전 메서드(GET·HEAD·OPTIONS)와 재전송 안전 처리기(주문, 이체)의 요청을 이 코드로 매핑한다. 그 밖의 쓰기의 연결 끊김과 자원 부족은 `500`이다. 주문 생성·취소 경로에서는 저널 확인(V47)이나 대상 주문 재조회마저 DB에 닿지 못했을 때만 이 코드이고, DB에 닿았으면 §2-5-b와 같은 `429`·`409 order_in_progress`다. 리더 자격 거부(fencing 거부, 기간 종료)는 그 마켓의 게이트를 닫은 노드(이 노드가 더는 그 마켓의 리더가 아님)일 때 페일오버 창의 이 코드다(§2-5-c). 이 매핑이 쓰기 업스트림에서 리더를 빼는 효과도 §2-5-c에 적었다. **(v2.57 개정, ADR 0034 S5)** 주문 쓰기가 이 노드가 접수하지 않는 마켓(그 마켓 파티션의 접수 게이트가 이 노드에서 닫힘)을 가리키면 소유권 선검사가 수용 제한·IP 버킷·인증보다 먼저 이 코드 + `Retry-After: 1` + `detail={"route":"not_owner"}` + 응답 헤더 `X-Exchange-Route: not-owner`로 끝낸다. nonce·레이트리밋·멱등 키·예약·채번을 건드리지 않고 DB에 가지 않은 거절이라 인그레스가 같은 요청을 다른 노드로 다시 보낸다. 힌트 없는 단건 취소가 소유하지 않는 노드에 가면 게이트웨이가 대상 주문을 읽은 뒤 채번 전에 같은 모양으로 낸다(§2-5-d). 인증 필터의 키 조회와 주문 생성·취소의 접수 전 읽기(계정 상태, 저장된 해시, 가용 잔고, 대상 주문, 저널 존재)가 연결 끊김으로 끝나면 다른 연결로 한 번 더 읽고, 그마저 DB에 닿지 못할 때만 이 코드를 낸다(§2-5-d 'DB가 흔들린 직후'). **(v2.60, ADR 0036)** 쓰기 가능 탐지를 켠 노드는 탐지기가 DB에 두 번 연달아 연결하지 못했으면(UNREACHABLE) 쓰기를 DB에 닿기 전에 곧바로 이 코드(`Retry-After: 1`, 'DB 연결 장애. 재조회 후 판단 필요')로 거절한다. DB에는 닿지만 커밋이 끝나지 않는 동안의 거절은 이 코드가 아니라 `423 trading_suspended`다(§2-5-e) |

**장애 규약(중요)**: 5XX/타임아웃 수신 시 클라이언트는 주문 상태를 `UNKNOWN`으로 간주하고 **동일 `client_order_id`로 재조회**(§4-B-4-a) 후 판단한다. 신규 재전송 금지 → 이중 체결 방지. Binance 관례 계승. 재조회 상태머신은 §7-6.

**429 클라이언트 규약(v2.36 신설)**: 위 장애 규약은 5XX·타임아웃만 다룬다. 429는 장애가 아니라 설계된 백프레셔이고 성질이 반대다. 5XX는 상태가 `UNKNOWN`이라 재조회가 먼저이고, 429는 접수·처리 이전 거절이라 상태가 **확정 미접수**다(§2-5-a). 이 구분이 문서에 없어 화면마다 처리가 갈렸으므로 여기서 못박는다.

| 축 | 조회 429 (`GET` 계열) | 접수 429 (`POST /orders`·`/orders/batch`) |
|---|---|---|
| 주문 상태 | 해당 없음 | **확정 미접수**. `UNKNOWN` 아님 |
| 재조회(§7-6) | 대상 아님 | 대상 아님 |
| 자동 재요청 | **허용**. `Retry-After` 경과 후 1회 | **금지**. 사용자 확인 후 재제출 |
| `client_order_id` | 해당 없음 | **보존**. 같은 키로 재제출한다 |
| 화면 표시 | 마지막 성공값을 지우지 않는다. 지연을 표시한다 | 대기 시간을 안내한다. 접수 실패로 적지 않는다 |

- **낡은 값이 확정 행위를 막지 않는다(v2.37 정정).** 잔고가 지연됐다는 이유로 주문 버튼을 잠그지 않는다. 클라이언트 잔고 검사는 편의이지 안전장치가 아니며, 잔고 판정의 권위는 서버(`insufficient_balance`)에 있고 그 게이트는 화면이 무엇을 하든 그대로 작동한다. 막아서 얻는 것은 요청 한 건 절약이고, 잃는 것은 백프레셔가 걸린 바로 그 구간에서 주문할 수단이다. 그 구간은 거래가 몰리는 때, 곧 사용자가 가장 확정해야 하는 때다. 화면이 거기서 매도를 막으면 429를 흡수한 것이 아니라 더 나쁜 것으로 바꾼 것이다. 종전 판은 이 자리에서 CTA를 막았고 정정한다.
- **다만 표시할 값이 아예 없으면 막는다.** 이때 잔고 표시는 0으로 폴백되고 비율 버튼도 0을 기준으로 계산하므로, 화면이 거짓 숫자를 보이고 사용자가 그것으로 확정하게 된다. 지연된 값(있음)과 부재(없음)는 다른 상태이고 이 경계가 유일한 차단 근거다.
- **조회 429를 종결 실패로 적지 않는다.** 서버가 `Retry-After`로 회복 시각을 명시했는데 화면이 그것을 영구 실패로 저장하면, 설계된 백프레셔가 사용자에게 고장으로 보인다. 마지막 성공값이 있으면 그 값을 유지하고 지연을 표시한다.
- **접수 429에 자동 재전송을 걸지 않는다.** 노드가 이미 굶고 있는 시점에 클라이언트가 스스로 다시 쏘면 굶김을 연장한다. 같은 사유로 §2-5-a는 MM 봇의 ticker 429 → orderbook 폴백이 조회 버킷에 최대 2배 요청을 만드는 증폭을 기록해 두었다. 재제출 판단은 사람이 한다.
- **자동 재요청은 유계여야 한다.** 허용되는 조회 재요청도 `Retry-After` 존중·단일 in-flight·최대 1회·상한 clamp를 전부 만족할 때만이다. 셋 중 하나라도 없으면 백프레셔 구간에 요청을 더하는 쪽이 된다.
- **`detail.bucket`으로 분기하지 않는다.** 429 `detail`은 진단 정보다(§2-5의 `account`·`ip`, §2-5-a의 `node`, §3-6의 `admin`). 클라이언트가 버킷별로 다른 동작을 하면 서버의 버킷 재배치가 곧 클라이언트 회귀가 된다. 로깅·표시에만 쓴다.
- **`Retry-After` 파싱은 세 평면이 같은 규칙을 쓴다.** 바디 `error.retry_after`를 먼저 읽고(`error.name` 유무와 무관하게), 없으면 `Retry-After` 헤더를 초 단위로 폴백한다. HTTP-date 형식이면 값을 만들지 않고 "잠시 후"로 열화한다. 비-JSON 429도 `too_many_requests`로 분류한다. 상태코드가 이미 429인데 바디 파싱 실패를 이유로 다른 코드로 떨어뜨리면, 그 화면은 무관한 오류를 사용자에게 보인다.

### 2-4. 멱등성 (client_order_id) · 최중요 (필수 승격)

- 필드: `client_order_id`, 1~36자, `[A-Za-z0-9_-]`.
- **POST /orders·POST /orders/batch에서 진짜 필수(required)로 못박음.** 미제공 시 **예외 없이 `400 client_order_id_required`** 반환한다.
  - 서버 생성 fallback(`srv-{user_id}-{seq}`) 제거(이전 버전의 자기모순 해소): "필수인데 서버 기본값 존재"는 사실상 optional이 되어, 타임아웃 후 키 없이 재전송하는 순진한 클라이언트가 매 재시도마다 다른 키를 받아 이중 접수될 수 있었다(이중체결 escape hatch). 이를 원천 차단하기 위해 **키 생성 책임을 전적으로 클라이언트(봇 포함)에 둔다.**
  - 정확성(체결 정합) > 편의성 원칙에 따라, 데모 편의보다 이중체결 불가능성을 택한다. 모든 봇·재시도 클라이언트는 재시도 시 **동일** `client_order_id`를 재사용해야 하며(내용 동일 시 §2-3 멱등 replay로 수렴), 그것이 유일한 안전 재전송 경로다.
- 저장 계층 강제(v2.49 정정): `command_journal` 은 `seq` 파티션이라 `(user_id, client_order_id)` 유니크를 물리로 걸 수 없다(DB 설계 §3.1 `idx_cmd_client_new` 주석). 같은 키의 주문 생성 명령 append 는 트랜잭션 어드바이저리 락(키 `cmd-new:{user_id}:{client_order_id}`, 마켓을 넣지 않는다)으로 직렬화하고, 락을 얻은 뒤 기존 주문 생성 명령을 `created_at, seq` 순으로 조회해 재전송을 판정한다(DB 설계 §5.1 (a), `append_command_prologue`). 물리 유니크는 조회 프로젝션 `orders` 의 `uq_orders_client` 가 진다.
- **동시 in-flight 규약(신규. 2026-09-23 정정: 판정 위치와 순서를 구현에 맞춘다)**: 동일 `(user_id, client_order_id)`의 두 요청이 첫 커밋 전에 동시 도착하면, 게이트웨이(`OrderGateway`)가 저널 append 전에 아래 순서로 판정한다.
  1. 저장된 해시(`idempotency_keys.request_hash`)가 있으면 먼저 대조한다. 내용 상이면 저널에 가기 전에 **`409 duplicate_client_order_id`**다.
  2. 원본 주문 생성 명령이 아직 저널에 없으면(저장된 해시가 없거나, 있어도 `command_journal`에 원본이 커밋되지 않았으면) 잔고 사전 가드(DB 설계 §5.1 v2.9)를 거친다. 가드는 가용 잔고에서 이 노드의 in-flight 예약 누계를 빼고 판정하므로, 아직 커밋되지 않은 원본 요청의 예약도 차감된다. 부족하면 **`400 insufficient_funds`**이고 멱등 키를 소비하지 않는다. 리더 모드 P2(ADR 0034 S3, v2.54)에서는 가드가 in-flight 누계 없이 가용 잔고만 보는 빠른 거부이고, 판정 권위는 4단계 append 트랜잭션 안의 DB 예약이다(DB 설계 §5.1). 거기서 잔고가 모자라면 트랜잭션 전체가 롤백되고 같은 `400 insufficient_funds`가 나며, 이번 요청이 넣은 해시는 아래 '저널 append 가 예외로 끝난 접수' 규칙대로 정리된다. P2의 빠른 거부는 처음 읽을 때 저장된 해시가 없던 요청에만 건다(2026-09-24 설계 검토 반영). 저장된 해시와 내용이 같은 요청은 원본이 저널에 없어도 빠른 거부를 건너뛰고 3·4단계로 간다. 처음 읽을 때 해시가 없어 빠른 거부에 걸린 요청은 `400`을 내기 전에 해시를 한 번 더 읽는다. 그 사이 같은 내용의 해시가 생겼으면 거부하지 않고 3·4단계로 보내고, 다른 내용이면 `409 duplicate_client_order_id`, 여전히 없으면 `400 insufficient_funds`다. 빠른 거부는 판정 권위가 아니므로 건너뛰어도 잔고 판정은 4단계의 DB 예약이 한다.
  3. 저장된 해시가 없었으면 가드를 통과한 뒤 `idempotency_keys` 행을 `INSERT ... ON CONFLICT DO NOTHING`으로 선점한다. 선점 경합에서 진 쪽은 저장된 해시를 다시 읽어 대조하고, 상이면 `409 duplicate_client_order_id`다. 같으면 처음 읽을 때 보지 않은 저널을 이때 본다. 같은 키의 원본이 이미 커밋돼 있으면 2단계에서 잡은 예약을 풀고 가드 없이 4단계 재생으로 간다(2단계에서 원본을 본 요청과 같은 경로).
  4. 내용 동일이면 append로 간다. 저널 append 의 어드바이저리 락이 두 번째를 원본 커밋 완료까지 대기시킨 뒤 기존 주문 생성 명령을 찾아 재전송으로 판정한다. 결과는 **기존 주문 반환(200, `X-Idempotent-Replay: true`)**이다.
  - 동시 도착과 순차 도착의 결과는 2단계 한 경우를 빼고 같다(v2.2 동일화). 원본이 저널에 커밋되기 전 창에 도착한 같은 내용의 재전송은, 가용 잔고가 원본 예약과 이번 요청을 함께 감당하지 못하면 200 replay가 아니라 `400 insufficient_funds`로 끝난다. 원본이 커밋된 뒤 도착한 재전송은 가드를 거치지 않으므로 200 replay다. 이 400은 원본의 접수 여부를 말하지 않는다. 원본 결과를 모르는 클라이언트는 새 키로 재전송하지 말고 §4-B-4 (a) 재조회로 판정한다. P2에서는 이 예외가 없다. 가드가 in-flight 누계를 쓰지 않고, 원본이 해시를 저장한 뒤에 판정되는 같은 내용의 재전송은 2단계의 빠른 거부를 거치지 않는다(해시를 다시 읽는 경우 포함). 4단계 어드바이저리 락이 원본 커밋을 기다린 뒤 재생으로 판정되며 재생은 예약하지 않는다. 그래서 동시 도착도 `200` replay이고, 원본이 잔고 부족으로 끝났으면 재전송도 같은 `400`이다. 원본이 해시를 저장하기 전에 빠른 거부까지 마친 요청만 그 시점 잔고로 `400`을 받는데, 그때는 원본이 아직 예약하지 않았으므로 원본과 무관한 잔고 부족이다. 2026-09-24 첫 판은 빠른 거부를 모든 미커밋 원본의 재전송에 걸어 두고도 이 문장을 적었다. 원본이 재전송의 저널 확인과 잔고 조회 사이에 커밋하면 재전송이 원본의 DB 예약이 빠진 잔고를 읽고 `400`을 낼 수 있었으므로 위 규칙으로 고쳤다(S3 설계 11절 R1).
  - 재전송 판정이든 새 접수든 게이트웨이가 5초 안에 조회 프로젝션(`orders`)에서 그 주문을 보지 못하면 **`409 order_in_progress` + `Retry-After`** → 클라이언트는 재조회로 수렴.
- 동작 요약:
  - 동일 키 & **내용 동일** → 기존 주문 반환(200, `X-Idempotent-Replay: true`).
  - 동일 키 & **내용 상이** → `409 duplicate_client_order_id`.
- **내용 비교 기준(v2.3 확정, §3-3 "재현 불가능한 표현 금지" 정합)**: '내용 동일/상이'는 **원본 요청 바디의 JCS(RFC 8785, §3-3(b)) 정규화 UTF-8 바이트열**과 재요청 바디의 JCS 바이트열의 **바이트 단위 동일성**으로 판정한다(서명 canonical과 동일 정규화 재사용 → 키 순서·공백·개행 차이는 동일로 수렴). 비교 원본은 저널이 아니라 게이트웨이가 저장한 그 바이트열의 SHA-256 16진값 `idempotency_keys.request_hash`이고, 재요청의 같은 값과 대조한다(2026-09-23 정정). 필드 생략과 기본값 명시(예: `time_in_force` 생략 vs `"GTC"` 명시)는 JCS 바이트가 다르므로 **상이(409)**다. 재시도 클라이언트는 원 요청과 필드 구성까지 동일한 바디를 재전송해야 한다. batch(§4-B-6)의 per-item 비교도 **해당 원소 객체의 JCS 바이트**로 동일 기준을 적용한다.
- **취소(DELETE) 멱등**: 이미 `CANCELED`인 주문 재취소 → `200` + 현재 상태 반환(에러 아님). 없는 주문 → `404`.
- 재생 계층: 복구는 항상 `seq > last_applied_seq`만 적용 → 재생 시 이중 적용 원천 불가.
- **보존 정책(신규)**: `client_order_id` 유일성의 원장인 `command_journal` 주문 생성 명령 행은 영구 유지. 조회 프로젝션(orders)은 90일 후 콜드 파티션으로 이관하되 그 원장은 유지 → 키 재사용 금지(무기한). 데모 리셋 시에만 네임스페이스 초기화.
- **해시 만료 구간의 재사용 판정**: 위 보존 정책은 키 유일성을 무기한으로 두지만, 내용 비교의 근거인 `idempotency_keys.request_hash`는 DB 설계의 보존 규정에 따라 7일 경과분이 삭제된다(pg_cron 일괄 DELETE). 두 규정이 겹치는 구간, 즉 **해시는 지워졌는데 저널에는 같은 키가 남은 상태**에서는 내용 동일성을 판정할 근거가 없다. 이때 저널 replay를 `200`으로 흡수하면 요청과 내용이 다를 수 있는 원 주문을 멱등 응답으로 위장하게 되므로, **대조 불가는 재사용으로 보고 `409 duplicate_client_order_id`로 거절한다**. 판정 기준은 "저장된 해시가 없다"가 아니라 "이번 요청에서 내용 대조를 실제로 수행하지 못했다"이다. 동시 도착 경합에서 `ON CONFLICT`로 밀린 쪽은 저장된 해시를 다시 읽어 대조하므로 이 거절 대상이 아니다. 또한 **거절 경로는 그 요청의 해시를 남기지 않는다**. 남기면 다음 재시도가 그 해시로 대조에 성공해 거절이 무력화되고, 결함이 한 번의 재시도 뒤 그대로 재현된다.
- **저널 append 가 예외로 끝난 접수의 키와 예약(v2.52, DB 설계 V47)**: 게이트웨이는 저널 append 전에 해시를 저장하고 in-flight 잔고 예약을 잡는다. (P2에서는 in-flight 잔고 예약이 없다. 잔고 예약은 append 트랜잭션과 함께 커밋되거나 함께 롤백되므로 아래의 예약 유지·지연 회수는 P1에만 해당한다. 해시 규칙은 같다.) append 가 예외로 끝나면 커밋 응답만 잃었을 수 있으므로, 같은 키의 append 가 끝나기를 어드바이저리 락으로 기다린 뒤 **이 요청이 채번한 주문 id 로 저널을 본다**.
  - 커밋돼 있으면 해시와 예약을 남기고 라이터에 그 명령의 회수를 요청한 뒤 **정상 접수와 같은 응답**(`201`, 도메인 거부면 `422`, 반영 대기가 예산을 넘으면 `409 order_in_progress`)을 낸다. 종전에는 해시를 지우고 예약을 바로 풀어 같은 키 재시도가 대조 불가 `409` 로 끝났고(새 키로 다시 내면 이중 주문), 정산 전 창에서 같은 잔고를 다른 주문이 통과할 수 있었다.
  - 커밋돼 있지 않으면 종전처럼 해시를 지워 다음 재시도가 잔고 가드를 다시 받게 하고, 예외의 원래 응답(라이터 정지는 `503 service_unavailable` + `Retry-After`)을 낸다. 다만 **같은 내용의 다른 요청이 그 해시로 대조했으면 지우지 않는다**. 기존 해시로 대조한 요청은 append 전에 해시 행에 공유 표시를 남기고, 해시를 넣은 요청은 공유 표시가 있는 행을 지우지 않는다. 공유 표시는 저널에 같은 키의 주문이 없음을 실제로 관측한 요청만 남긴다. 2단계에서 원본이 없음을 본 요청이 그렇고, 3단계 선점 경합에서 진 요청은 표시 전에 저널을 다시 봐 원본이 있으면 표시하지 않는다. 대조한 요청이 커밋했으면 그 주문의 해시이고, 대조한 요청마저 실패했으면 해시가 고아로 남는다. 고아 해시는 무해하다. 재시도는 그 해시로 대조한 뒤 저널 실재로 잔고 가드 적용을 가른다. (v2.55, ADR 0034 S4) 원래 예외가 마켓 접수 경합(§2-5-b)이면 응답은 `503`이 아니다. 이 요청이 해시를 새로 넣었고 판정이 그 해시를 지웠으면 `429 too_many_requests`(bucket market)이고, 다른 요청의 공유 표시 때문에 남겼거나 해시가 없거나 이 요청이 공유 표시를 채운 요청이면 `409 order_in_progress` + `Retry-After`다. (v2.56, ADR 0034 S4b) 원래 예외가 DB 연결 장애이거나, 그 마켓의 게이트를 연 노드가 받은 리더 자격 거부(fencing 거부, 기간 종료)여도 판정이 이 요청의 명령이 저널에 없다고 답하면 위 경합과 같은 규칙이다. 이 요청이 해시를 새로 넣었고 판정이 그 해시를 지웠으면 `429 too_many_requests`(bucket market)이고, 공유 표시로 남겼거나 해시가 없거나 이 요청이 공유 표시를 채운 요청이거나 연결이 끊긴 판정을 해시를 지우지 않는 판정으로 다시 본 것이면(아래) `409 order_in_progress` + `Retry-After`다. 판정이 DB에 닿았으므로 이 노드를 쓰기 업스트림에서 빼는 `503`을 내지 않는다(§2-5-c). 리더 자격 거부를 받은 노드가 그 마켓의 게이트를 닫았으면(재확인으로 강등해 더는 그 마켓의 리더가 아님) 판정 결과와 무관하게 페일오버 창의 `503 service_unavailable` + `Retry-After`다.
  - 저널 확인마저 실패하면(DB 불통) 해시를 남기고 예약은 TTL 로 지연 회수하며 원래 응답을 낸다. 커밋됐다면 재시도가 멱등 재생으로, 커밋되지 않았다면 재시도가 새 접수로 수렴한다. (v2.55) 원래 예외가 마켓 접수 경합이면 응답은 `409 order_in_progress` + `Retry-After`다. (v2.56, ADR 0034 S4b) 원래 예외가 DB 연결 장애(또는 게이트를 연 노드의 리더 자격 거부)면 판정 실패를 둘로 가른다. 판정이 연결이 끊겨 실패하면 다른 연결로 한 번 더 부른다. 다시 부르는 판정은 해시를 지우지 않고 이 요청의 주문이 저널에 있는지만 본다. 첫 판정이 해시를 지우고 커밋한 뒤 응답만 잃었으면 그 사이 같은 내용의 다른 요청이 같은 해시를 새로 넣고 접수했을 수 있는데, 판정은 해시를 넣은 요청을 가리지 못해 그 요청의 해시를 지우게 되고 그 주문의 재전송이 대조 불가 `409 duplicate_client_order_id`로 끝나기 때문이다. 다시 부른 판정이 저널에 있다고 하면 위 커밋 회수이고, 없다고 하면 첫 판정이 해시를 지웠는지 모르므로 확정 미접수가 아닌 `409 order_in_progress` + `Retry-After`다. 마지막 실패마저 DB 연결 장애(판정이 DB에 닿지 못함)면 `503 service_unavailable` + `Retry-After: 1`이고 메시지는 'DB 연결 장애. 재조회 후 판단 필요'다. 판정이 DB에 닿았는데 실패했으면(같은 키의 어드바이저리 락 대기 초과 55P03, 문장 시간 초과 57014 등) 경합과 같은 `409 order_in_progress` + `Retry-After`다(§2-5-c). 어느 경우에도 해시를 지우지 않는다. 연결이 끊겨 롤백이나 커밋이 실패한 append는 실제로 커밋됐을 수 있기 때문이다(S4b 설계 3절 J8).
  - 판정에 저장 시각을 쓰지 않는다. `created_at` 선후 비교는 DB 서버 시계가 뒤로 가면(프라이머리 전환, 시계 보정) 틀리기 때문이다.
  - 위 '해시 만료 구간' 거절의 예외가 하나 있다. 이번 요청이 해시를 새로 넣었는데 그 행에 다른 요청의 공유 표시가 있으면, 저널의 주문은 그 요청이 이 해시로 대조한 뒤 커밋한 것이다. 공유 표시를 남긴 요청이 저널에 같은 키의 주문이 없음을 관측했으므로 그 주문은 관측 뒤에 커밋됐고, 해시 만료 전의 옛 주문이 아니다. 내용이 이 해시와 같으므로 대조를 마친 것으로 보고 `200` 재생을 낸다. 같은 내용의 요청 셋이 섞여 앞 요청이 지운 키를 이번 요청이 다시 넣은 경우다. 공유 표시를 읽지 못하면(DB 오류) 대조하지 못한 것으로 보고 `409 duplicate_client_order_id` 로 거절하며 이번 요청이 넣은 해시를 지운다. 이때 공유 표시가 있는 행은 지우지 않는다.
  - 남은 한계. 해시가 만료된 옛 주문이 저널에 있고 내용이 다른 요청이 해시를 넣은 직후, 그 해시로 대조한 같은 내용의 다른 요청은 저널에서 원본을 보고 재생으로 간다. 해시를 넣은 요청은 대조 불가 `409` 로 끝나지만, 대조한 요청은 그 해시가 옛 주문의 해시인지 구별하지 못해 옛 주문을 `200` 으로 받는다. 해시 만료 삭제가 배선되기 전에는 도달하지 않는다(DB 설계 §3.2-a-5). 배선할 때는 해시 행이 저널 대조를 마쳤는지 기록하는 열을 먼저 정한다.
  - 해시를 잡는 도중 같은 키의 다른 요청이 해시를 넣고 지우기를 거듭하면 두 번 시도한 뒤 **`409 order_in_progress` + `Retry-After`** 로 돌려보낸다. 저널에는 닿지 않았다.

### 2-5. Rate limit (알고리즘·다중버킷·프록시 확정)

- **알고리즘**: 고정창이 아닌 **토큰버킷**. 정정(2026-09-10): 구현체는 Resilience4j RateLimiter 가 아니라 자체 구현이다. 현 배선은 노드 단위이고, 노드 간 합산 한도는 M2 선행 조건이다. 분산 구현체(Redisson `RRateLimiter` 등)는 그때 정한다. 초당 리필. thundering-herd 시나리오에서 창 경계 2배 버스트·리셋 herd 방지. `X-RateLimit-Reset`은 **"다음 토큰 가용 시각(epoch s)"**으로 재정의.
- **다중 버킷 노출**: 한 요청이 IP 공개 버킷 + 계정 사설 버킷에 동시 계상 가능 → 버킷별 헤더 노출.

응답 헤더(모든 응답):
```
X-RateLimit-Limit-ip: 300
X-RateLimit-Remaining-ip: 271
X-RateLimit-Limit-account: 100
X-RateLimit-Remaining-account: 87
X-RateLimit-Reset: 1784624401     # 가장 임박한 버킷의 다음 토큰 가용 시각(epoch s, =2026-07-21T09:00:01Z)
Retry-After: 3                    # 429·503·409(order_in_progress) 시 포함(v2.3). 이 블록은 rate limit 헤더 기술이 주목적이며, Retry-After의 전체 발급 조건은 §2-3(503)·§2-4(409)·§4-B-4(a)를 따른다
```
429 응답 `detail`에 위반 버킷명 포함: `{"bucket":"account","scope":"order"}`.

| 그룹 | weight | 한도(기본) |
|---|---|---|
| 공개 조회 (markets/orderbook/ticker/trades/candles/exchange-config) | 1~2 | 300 토큰 / 10s (IP) |
| 계좌/주문 조회 | 2 | 200 토큰 / 10s (계정) |
| 주문 생성/취소 | 5 | 100 토큰 / 10s (계정) |
| 관리 API(admin) | 10 | 60 토큰 / 60s (admin 계정). **신규: 관리 평면도 rate limit 적용** |
| 노드 용량 `scope=order`(수용 제한) | - | 동시 처리 주문 쓰기 = 요청 풀의 40%(하한 4). **v2.16 신설, 아래 §2-5-a** |
| 노드 용량 `scope=query`(수용 제한) | - | 동시 처리 조회 = 요청 풀의 40%(하한 4). **v2.17 신설, 아래 §2-5-a** |
| 노드 용량 `scope=requery`(수용 제한) | - | 동시 처리 by-client 재조회 = 요청 풀의 10%(하한 2). 초과는 429가 아니라 `409 requery_deferred`. **v2.18 신설, 아래 §2-5-a** |

**공개 조회 버킷 확정(v2.19 명문화)**. 종전 표는 weight를 `1~2`로만 두어 어느 경로가 1이고 어느 경로가 2인지 구현이 자의로 정했다. `X-RateLimit-Remaining-ip` 감소폭은 클라이언트 관측값이라 예측 가능해야 한다.
- **weight**: 다중행 리스트 응답(`markets`·`ticker`·`trades`·`candles`)은 **2**, 단건 객체 응답(`orderbook`·`exchange-config`)은 **1**.
- **배치 계상**: `ticker?markets=`는 마켓 수와 무관하게 **요청 1건(weight 2)**으로 센다. 마켓 수로 곱하면 마켓별 개별 폴링이 배치보다 싸져 요청 수를 늘리는 방향으로 클라이언트를 유인한다. 보호 대상은 행 수가 아니라 요청이 점유하는 스레드·커넥션이다.
- **적용 대상**: §4-A 무인증 공개 평면 전체이며, 구현은 인증 제외 판정과 **같은 목록**을 공유한다(목록이 갈라지면 인증도 레이트리밋도 없는 경로가 생긴다). 인증 제외 경로 전체와 같은 집합은 아니다. 게시판(§4-D)·데모 세션·회원 인증(§3-8)은 각자 전용 버킷을 갖는다. 불변식은 **'인증 제외 경로는 반드시 어떤 유량 방벽 하나에 속한다'**이다.
- **한도는 기본값**: 봇 플릿·웹이 한 IP로 뭉치는 배포(데모, 격리 카오스 스택)는 `exchange.public.rate-limit.capacity`로 상향한다. 이 평면에는 계정 버킷이 없으므로 상향이 유일한 조절 수단이고, 노드 생존은 §2-5-a 수용 제한(`scope=query`)이 별도로 지킨다.
- **적용 순서**: 수용 게이트(§2-5-a) **뒤**, 인증 **앞**이다(필터 6 -> 7 -> 10). 백프레셔로 거절한 요청이 IP 토큰을 갉으면 장애 중에 정상 클라이언트의 예산이 깎인다. 반대로 인증 뒤면 미인증 플러드가 이미 `api_keys`를 조회한 뒤다.

#### 2-5-a. 노드 용량 버킷 (수용 제한, admission control · v2.16 신설, v2.17 조회 확장, v2.18 재조회 버킷)

계정 토큰버킷은 한 계정의 유량을 재고, 노드 용량 버킷은 **이 노드가 동시에 처리 중인 요청 수**를 잰다. 둘은 독립이며 계정 버킷을 통과한 요청도 노드가 포화면 거절된다. 노드 용량은 다시 `order`·`query`·`requery` 세 버킷으로 나뉜다.

**왜 여러 버킷인가(라이브 실증)**. v2.16은 쓰기만 유계화했다. 라이브에서 쓰기는 실제로 잘렸다(`in_flight` 23~24, `rejected` 276 증가). 그런데도 노드는 cpu 319%로 죽었고 주입 해제 후 자력 회복하지 못했다. 남은 부하가 조회였다. 쓰기가 상한을 채우면 남는 커넥션은 status 전용이 아니라 **모든 조회가 경합하는 공용 잔량**이라, 봇의 다중행 시장 데이터 조회가 그것을 채우는 순간 생존 신호가 종전대로 굶는다. 한 버킷으로 합치지 않는 이유는 그러면 한쪽이 다른 쪽을 굶기기 때문이다. v2.17(쓰기·조회 2버킷)은 E1 라이브 PASS로 검증됐다(errorRate 0, epoch 불변, 주입 중 status 200 유지, 해제 후 자력 회복, cpu 22%).

- **버킷 분류**
  - `order`: `POST /orders`, `POST /orders/batch`, `DELETE /orders*`(`by-client` 취소 포함).
  - `query`: 위와 아래를 제외한 `/api/v1/**` 전부. 공개 시장 데이터(`markets`·`orderbook`·`ticker`·`trades`·`candles`), 계좌/주문 조회, `auth`·`community` 평면을 포함한다. **이름은 `query`지만 주문 쓰기가 아닌 쓰기(로그인·회원가입·게시글 작성)도 여기 들어간다.** 버킷의 경계는 HTTP 메서드가 아니라 '주문 접수 경로인가'다. 라이브 판독에서 `scope=query` 거절을 조회만의 지표로 읽으면 안 된다.
  - `requery`: `GET /orders/by-client/{client_order_id}` 전용(아래 별도 항목).
- **면제 경로**(어느 버킷에도 들어가지 않음)
  - `/actuator/**`(health·readiness·liveness·prometheus)와 `/harness/v1/**`. **노드가 살아 있다는 것은 곧 이 질문들에 답할 수 있다는 뜻**이고 LB·컨테이너 healthcheck·하네스가 전부 그 응답으로 판단한다. 막히면 생존 판정 자체가 무너진다. 게이트의 URL 패턴이 `/api/v1/*`이라 구조적으로 도달하지 않는다.
  - `OPTIONS`(CORS 프리플라이트). DB를 건드리지 않으며 막으면 브라우저가 본 요청을 보내지도 못한다.
- **`requery` 전용 버킷(v2.18)**: `GET /orders/by-client/{coid}`는 §7-6 UNKNOWN 재조회의 유일한 수렴 경로다.
  - **왜 `query`에 넣지 않는가**: 여기가 429가 되면 클라이언트는 접수 여부를 확정하지 못하고 안전 재전송과 이중 접수 중 하나를 골라야 한다. 멱등 규약이 깨지는 자리다.
  - **왜 완전 면제가 아닌가(v2.17의 결함)**: 면제는 동시성 상한을 없앤다. 그런데 이 경로는 하필 노드가 굶는 순간에 몰린다. 커넥션 획득 3초 초과가 `503`을 만들고(§2-3), 클라이언트는 5xx에 재조회로 반응한다(k6 `order_flow.js`). 되먹임 고리이며, 게이트된 트래픽에 대해 없앤 실패 모드(무제한 대기 스레드)가 면제 경로에서 재현된다. E2(PG 블랙홀)·E6(스파이크)처럼 5xx를 대량 유발하는 실험이 정확히 그 노출 구간이다.
  - **초과 응답은 `409 requery_deferred` + `Retry-After`**(신규 코드, §2-3). 429·503이 아닌 이유는 k6의 규약 판정이 재조회 응답 `200`·`404`·`409`에서만 `idempotent_safe`를 만족하기 때문이다. 그 집합 밖으로 내보내면 백프레셔가 곧 멱등 규약 위반으로 계상된다. 이름을 `order_in_progress`로 재사용하지 않는 이유는 그 코드가 '저널에는 있으나 프로젝션 미반영'이라는 **확정 사실**을 주장하는데(§4-B-4(a)), 슬롯이 없어 저널을 보지도 못한 상태는 그 주장을 할 수 없기 때문이다. 이중 접수를 부르는 유일한 오답은 잘못된 `404`(미접수 확정)이고 판정 보류는 그 반대 방향이라 안전하다. **상태를 기준으로 분기하는 클라이언트는 변경이 필요 없다.**
  - **대기를 두지 않는 이유**: '짧은 대기 후 무조건 통과'는 상한이 아니라 지연 추가일 뿐이라 동시성이 다시 무제한이 된다. '짧은 대기 후 거절'은 대기열(= 도착률 x 대기시간)을 되살리면서 거절도 그대로 남는다. 세마포어 대기가 가상 스레드를 pin하지 않아 JDBC 블로킹보다 싸다는 점은 고려했으나, 보류 응답 자체가 이미 비종결·저비용(Retry-After 1s)이라 대기로 성공률을 사는 이득이 작다. 세 버킷 모두 무대기로 통일한다.
  - **소형이어도 되는 근거**: 이 경로의 비용은 인덱스 단건 질의 **3회**다(`ApiKeyDirectory.findActive`의 `api_keys` 인증 조회, `command_journal` count, `orders` select. 인증 조회에는 캐시가 없다). 시스템에서 가장 싼 축이라 슬롯 회전이 빠르다.
- **적용 지점은 인증 이전**이다. 인증이 `api_keys`를 조회하므로 인증 뒤에서 막으면 이미 DB를 탄 뒤라 실효가 없다. 인증 계층은 공개 시장 데이터를 검증 대상에서 제외하므로, 게이트를 인증 필터 안에 두면 정작 노드를 죽인 조회가 게이트 밖에 놓인다. 그래서 별도 선행 필터다. **교환**: 게이트가 인증보다 앞이므로 미인증 트래픽도 조회 슬롯을 소비한다. 미인증 플러드가 조회 평면을 429로 만들 수 있다. 그럼에도 인증 뒤로 옮기지 않는 이유는 인증 자체가 DB 접촉이라 뒤에서 막으면 유계화가 성립하지 않기 때문이다. IP 버킷(§2-5)이 완화 수단이나 **이 게이트보다 뒤**에 온다(v2.19 확정). 순서를 뒤집으면 백프레셔 거절이 IP 토큰을 소비해 장애 중에 정상 클라이언트의 예산을 깎는다. 대신 미인증 플러드가 조회 슬롯을 잠시 점유하는 구간이 남는데, 그 경로의 429 렌더는 DB 무접촉이라 점유 시간이 짧다는 것을 감수 조건으로 둔다.
- **거절 응답**: `429 too_many_requests` + `Retry-After: 1` + `detail={"bucket":"node","scope":"order"|"query"}`(`requery`는 위 항목의 409). **대기 없이 즉시** 반환한다. 대기열을 만들면 스레드가 다시 쌓여 죽는 방식만 옮겨진다.
- **거절 문구는 원인을 노드가 아니라 동시성으로 적는다(v2.40)**. 이 게이트는 세마포어 `limit` 개의 동시 in-flight 를 재는 것이지 노드의 처리 능력을 재는 것이 아니다. 상한이 6 이면 일곱 번째 요청은 노드가 한가해도 거절된다. 종전 문구 "노드 처리 용량 초과"는 그것을 서버 장애로 읽히게 해, 설계된 백프레셔를 고장으로 번역한다. 그 오역을 없애는 것이 §2-3 의 목적이므로 문구가 그 목적과 어긋나면 안 된다. 확정 문구는 **`동시 처리 한도 도달. 잠시 후 재시도 가능`**이다. 명사형 종결이고 회복 가능성을 함께 적는다. 어느 버킷인지는 `detail.scope` 가 이미 싣는다.
- **런타임 스위치(v2.41)**. 게이트는 기본 켜짐이고 `exchange.admission.enabled`(기동값)와 actuator `admission` 엔드포인트(`POST /actuator/admission {"enabled":true|false}`, 읽기는 상태와 버킷별 in-flight)로 오갈 수 있다. 끄는 목적은 하나다. 수용 제어가 없는 상태(보통의 Spring 서비스, 곧 고객 기본값)를 재기동 없이 재현해 탐지·판단·복구 절차를 검증하는 실험이다. 기록된 사고는 그 상태에서 났다(ADR 0007). 관리 평면(8443·mTLS)이 아니라 actuator 에 두는 이유는 이 조작이 운영자가 노드 안에서 localhost 로 치는 조치이고 actuator 가 이미 내부 포트로 분리돼 있어서다. 인터넷에 열리지 않는 것은 SG 가, 사람의 접근은 SSM IAM 이 막는다. 전환마다 WARN 로그 1행(actor, before, after)을 남긴다. 스위치는 세마포어 permit 을 직접 건드리지 않는다. 꺼진 동안 들어온 요청은 버킷을 고르기 전에 통과하므로 획득도 반납도 하지 않는다. **다만 끄기 전에 이미 획득된 슬롯은 그대로 남는다(v2.42 정정)**. 반납은 획득에 성공한 분기의 `finally` 에만 있어 그 요청이 끝나야 돌아온다. 따라서 켜는 순간 in-flight 는 0 이 아니라 끌 때 쥐고 있던 값에서 이어지고, 그 값이 이미 상한이면 켠 직후 첫 요청이 곧바로 거절된다(`AdmissionGateTest.다시_켜면_상한이_즉시_돌아온다`). 요청이 끝나지 않는 정지 상태에서는 이 잔여가 스스로 풀리지 않는다. 운영 배포에서 이 값이 꺼진 채 발견되면 결함이다. 실측 1회차(`harness/evidence/gateoff-20260902/`)에서 게이트 OFF 100 OPS로 정지한 노드에는 이 전환이 도달하지 못했다(60초 타임아웃 3회). 이 스위치는 장애 중 복구 레버가 아니라 실험 전 상태 설정 수단이다. 장애 중 즉시 레버는 앱 바깥(ALB 규칙, WAF 레이트 제한, 재기동)에 둔다.
- **거절은 접수·처리 이전에만 일어난다**. 저널·원장·프로젝션 어디에도 흔적이 없으므로 주문 상태는 `UNKNOWN`이 아니라 **확정 미접수**이며 §7-6 재조회 상태머신의 대상이 아니다. 클라이언트는 동일 `client_order_id`로 그대로 재전송하면 되고 멱등 의미는 불변이다.
- **503이 아닌 이유**: (a) 503은 §2-3 장애 규약상 `UNKNOWN` 재조회를 유발해, 노드가 이미 굶고 있는 시점에 조회 부하를 더 얹는다. (b) 인그레스는 접수 경로의 503을 노드 장애로 계상한다(하네스 nginx `proxy_next_upstream http_503`, `max_fails=1`). 백프레셔를 503으로 내면 정상 리더가 업스트림에서 빠지고, 수요가 끊겨 좀비 판정(DB §5.4)의 분모까지 사라진다. (c) 429는 클라이언트가 이미 다루는 코드다. k6는 429를 가용성 실패로 세지 않고(`expectedStatuses`, 공개 조회 체크도 `200|429`), 봇의 조회는 비 200을 캐시값·앵커 무효로 흡수한 뒤 다음 사이클(호가 갱신 주기 1초, 지수 백오프 포함)까지 쉰다. **다만 완전한 무반응은 아니다.** MM 봇은 ticker 조회가 비 200이면 같은 사이클에 orderbook 조회로 폴백하므로, ticker 429 한 건이 orderbook 요청 한 건을 즉시 유발한다. 폭주는 아니나 조회 429가 만성화되면 조회 버킷에 최대 2배 요청이 들어온다.
- **명시 상한은 기동 시 풀과 대조한다(v2.38)**. `exchange.admission.*-in-flight`로 값을 직접 주면 유도 배분율을 건너뛰므로 합이 풀을 넘을 수 있다. 종전에는 풀 16에 `order-in-flight=30`을 줘도 기동이 성공하고 불변식만 조용히 깨졌다. 그러면 게이트를 통과한 요청이 커넥션을 기다리게 되고, 이 게이트의 존재 이유인 무대기 성질이 사라진다. 노드를 죽인 것은 커넥션이 아니라 커넥션을 기다리던 스레드였다. 그래서 명시 설정에서 예비분이 0 이하면 `AdmissionLimits.resolve`가 기동을 중단한다. 유도 경로는 검사하지 않는다. 초소형 풀에서 하한이 지배해 예비분이 0 이하가 되는 것은 알려진 상태이고 카오스 스택이 풀을 줄여 도는 구간이 있다.
- **상한 배분(v2.17 재설계, v2.18 재조회 몫 추가)**: 요청 커넥션 풀 크기에서 유도한다. **쓰기 40% / 조회 40% / 재조회 10% / 예비 20%**(하한 4·4·2). 풀 32 -> 12·12·3·5, 풀 16 -> 6·6·2·2. `exchange.admission.{order,query,requery}-in-flight`로 버킷별 명시 고정 가능.
  - **합이 풀보다 작아야 하는 이유 (1) 면제 경로 보호**: 면제 경로(actuator health의 DB 지표, `/harness/v1/status`)와 백그라운드(WS 퍼블리셔, 스냅샷 워커, 정산 루프, 불변식 스케줄러)가 같은 풀을 쓰고 수용 게이트가 없다. 게이트된 트래픽이 풀을 전량 점유할 수 있으면 이들이 굶고, 하필 그중 둘이 노드의 생존 신호다. 예비분이 "생존 신호는 막히지 않는다"를 필터 계층이 아니라 커넥션 계층에서 참으로 만든다. **실제 비용**: `/harness/v1/status` 한 건이 커넥션 **2개**를 동시에 쥔다(외부 질의의 RowMapper 안에서 스칼라 질의를 돌려 트랜잭션 없이 두 번째 커넥션을 빌린다). 예비분은 이 비용을 흡수해야 한다.
  - **합이 풀보다 작아야 하는 이유 (2) 스레드 축적**: 합이 풀 이하이면 **게이트된** 요청은 커넥션을 기다릴 일이 없다. 게이트된 경로(주문 쓰기·조회·재조회)는 요청 하나가 동시에 커넥션을 최대 1개만 쥔다. 풀이 게이트된 트래픽에 대해 대기 자원이 아니게 되고, DB에 블록된 스레드 수가 타임아웃이 아니라 **설계로** 유계가 된다. 죽음의 직접 원인이 커넥션이 아니라 그것을 기다리는 스레드였으므로 이 성질이 핵심이다. 이 주장은 게이트된 경로에 한정된다. 면제 경로에는 위 `/harness/v1/status` 반례가 있다.
  - **40/40 대등 배분 근거**: 조회를 더 조이면 봇의 시장 데이터 조회가 실패해 호가 갱신이 멈추고 주문 흐름 자체가 말라 '체결이 멈추지 않는다'는 1차 목표를 조회 쪽에서 깬다. 쓰기를 더 조이면 체결 처리량이 직접 깎인다. 어느 쪽 근거도 더 세지 않아 대등 배분을 택하고, 비대칭이 필요하면 설정으로 덮는다.
  - **하한(4·4·2)**: 초소형 dev·테스트 풀(2~4)에서 상한이 0~1로 내려앉아 정상 경로까지 막는 것을 막는다. 이 구간에서는 합이 풀을 넘을 수 있고 위 (2)의 무대기 성질이 성립하지 않는다(의도된 교환). 풀이 11 이상이면 배분 규칙이 지배해 예비분이 항상 양수다.
- **batch 1건 = 슬롯 1개**. 보호 대상이 요청이 점유하는 스레드·커넥션이라 원소 수와 무관하다. 원소 증폭은 계정 버킷의 weight 5 x 원소 수가 이미 상쇄한다.
- **관측**: `exchange_admission_rejected_total{scope}`, `exchange_admission_in_flight{scope}`, `exchange_admission_limit{scope}`(하네스 CICD §5-2, `scope` = `order`\|`query`\|`requery`). 기동 시 해석된 배분을 INFO 로그 1줄로 남긴다(유도값은 코드에도 설정에도 숫자로 없어 라이브 확인 지점이 필요하다).

- **신뢰 프록시(X-Forwarded-For)**: LB(HAProxy/Nginx) IP를 신뢰 프록시 목록에 등록하고, XFF를 **우측부터 신뢰 프록시 홉을 제거한 뒤 남는 첫 IP(rightmost-untrusted)**를 클라이언트 IP로 채택한다. 최좌측 값은 원 클라이언트가 임의 주입 가능하므로 신뢰하지 않는다(최좌측 채택은 폐기). 구현은 forward-headers-strategy(RemoteIpValve)로 이 파생을 컨테이너에 위임하고, 애플리케이션은 getRemoteAddr()만 읽는다(원시 XFF 파싱 금지, 단일 유틸 ClientIp.of). **봇/데모 트래픽은 계정 키 기반 버킷을 우선 적용**(모든 봇이 동일 프록시 IP로 보여 IP 버킷이 마비되는 것 방지). 미인증 공개 조회만 IP 버킷. **v2.19 정정**: 이 우선 적용은 주문·계좌 평면에만 성립한다. 봇의 시장 데이터 조회는 계정 키를 싣지 않아 정의상 미인증 공개 조회이며, 한 IP로 뭉치는 배포에서는 위 `exchange.public.rate-limit.capacity` 상향이 유일한 조절 수단이다.
  - **신뢰 경계 핀(배포 불변식)**: `server.tomcat.remoteip.internal-proxies`를 프로파일별로 명시 지정한다. RemoteIpValve 기본 internalProxies(전 RFC1918+loopback)를 그대로 쓰면 사설 소켓피어 배포에서 클라이언트가 보낸 단일 XFF가 rightmost-untrusted로 채택되어 회전 스푸핑으로 IP 버킷을 우회한다. 운영은 LB VIP CIDR만 등록(`EXCHANGE_TRUSTED_PROXIES` 주입, 미주입 시 루프백 전용 fail-closed), 프록시 없는 dev·데모·통합테스트는 루프백 전용으로 좁힌다. 인그레스는 인바운드 XFF를 strip한 뒤 자신을 append하는 것을 배포 전제로 한다.
- **인증 실패 스로틀(신규)**: 동일 `access_key`/IP의 401 반복이 임계(예: 60s 내 20회) 초과 시 `401 auth_throttled` + 지수 백오프 락아웃 → secret 브루트포스 방어.
- **418 auto_ban 해제(신규)**: 지속 위반 시 5분 밴. 밴 중 신규 요청은 카운트 미증가, TTL 만료 시 자동 해제. 위반 카운터는 60s 슬라이딩 윈도로 감쇠.

이 헤더 노출·버킷 계측이 "부하 제어를 데이터/그래프로 증명"하는 핵심.

**노드 로컬 한도와 파티션(v2.57, ADR 0034 결정 14 재검토)**. 계정 버킷과 IP 버킷은 노드 메모리이고 한도 값은 바꾸지 않는다. 주문 생성·취소 버킷(100토큰/10초, weight 5)은 요청을 받아 인증한 노드에서 차감한다. 소유권 선검사(§2-5-d)가 소유하지 않는 노드의 주문 쓰기를 인증 전에 돌려보내므로, 힌트를 실었거나 바디에서 마켓을 찾을 수 있는 요청은 인그레스가 다른 노드로 다시 보내도 소유 노드에서 한 번만 차감된다. 힌트 없는 단건 취소가 소유하지 않는 노드에 가면 그 노드와 소유 노드에서 두 번 차감된다. 실효 한도는 모드에 따라 다르다. P1·P2와 한 노드가 두 파티션을 모두 쥔 P3에서는 계정당 계약값이고, 두 노드가 파티션을 하나씩 쥔 P3에서는 두 파티션에 걸쳐 주문하는 계정이 계약값의 최대 두 배를 받는다. 이 버킷의 목적은 노드의 라이터 앞 유량 방벽이므로 노드마다 계약값을 지키는 것으로 충분하다고 본다(AI 선택, 사용자 확인 필요). 마켓 없는 일괄 취소가 다른 파티션 주문을 소유 노드에 넘기는 인계(§4-B-3)는 소유 노드의 계정 버킷을 쓰지 않는다. 받은 노드가 이미 weight를 차감했고, 넘긴 주문 수의 상한은 종전 일괄 취소와 같기 때문이다. 공개 조회 IP 버킷은 조회 업스트림의 노드마다 따로라 실효 한도가 조회 노드 수만큼 곱해진다(종전과 같다).

#### 2-5-b. 마켓 접수 경합 (v2.55 신설, ADR 0034 S4)

주문 생성(`POST /orders`와 배치 원소)과 취소(`DELETE /orders*`)가 마켓 접수 경합으로 저널 전에 끝나면 `503`이 아니라 아래 둘 가운데 하나다. 이 요청이 접수되지 않았음이 확정되면 `429 too_many_requests` + `Retry-After: 1` + `detail={"bucket":"market","scope":"order"}`이고, 확정할 수 없으면 `409 order_in_progress` + `Retry-After: 1`이다. 계약은 코드보다 먼저 적었고 S4 코드부터 적용한다. 그 전의 응답은 v2.54 §2-3의 `503`·`500`이다.

- **경합은 두 가지다.** (a) 마켓 접수 락을 서버 설정 `exchange.engine.accept-lock-wait` 안에 얻지 못한 경우. (b) 저널 append 트랜잭션이 일시 DB 오류 넷(55P03 잠금 대기 초과, 57014 문장 취소, 40P01 교착 희생, 40001 직렬화 실패) 가운데 하나로 롤백된 경우다. 55P03의 상한은 `exchange.engine.accept-lock-timeout`이다. 두 설정은 S4부터 dev·prod 기본값이 각각 2000ms·1000ms다(코드 기본값은 0, 끔). 넷은 모두 커밋 전에 트랜잭션을 롤백하는 오류라 이 요청의 명령은 저널에 남지 않는다.
- **`429`는 이 요청의 확정 미접수일 때만이다.** 취소는 늘 이 경우다. 주문 생성은 이 요청이 멱등 키를 새로 넣었고, append 예외 뒤 V47 판정(`resolve_failed_order_intake`)이 같은 키의 append와 같은 어드바이저리 락 아래에서 이 요청의 명령이 저널에 없고 같은 키를 대조한 다른 요청의 공유 표시도 없음을 확인해 키를 지운 경우(`DISCARDED`)만이다. 같은 키로 새 주문을 커밋할 수 있는 요청은 키를 넣은 요청과 append 전에 공유 표시를 채운 요청뿐이므로, 그 순간 이 키로 커밋됐거나 커밋 중인 주문이 없다.
- **확정할 수 없으면 `409 order_in_progress`다.** 같은 키의 다른 요청이 이 키를 대조했거나(판정 `KEPT`, 또는 이 요청이 공유 표시를 채운 요청이라 판정이 `NOT_JOURNALED`), 키가 사라졌거나(`ABSENT`), 연결이 끊긴 판정을 키를 지우지 않는 판정으로 다시 봤거나(`NOT_JOURNALED`, §2-4), 판정 자체가 실패했거나, 이미 저널에 있는 주문을 다시 보낸 요청(재생)이 경합으로 끝난 경우다. 어느 것도 확정 미접수가 아니다(재생은 이미 접수돼 있다). §2-4의 이 코드와 같은 뜻의 비종결 응답이고, 클라이언트는 `Retry-After` 뒤 같은 `client_order_id`로 다시 보내거나 재조회(§4-B-4 (a))해 수렴한다. 2026-09-24 첫 판은 V47 판정이 이 요청의 명령이 저널에 없다고 답하면 모두 `429`로 냈다. V47은 이 요청이 채번한 주문 id만 보므로, 원본이 키를 넣고 느려진 사이 재전송이 공유 표시를 채우고 경합으로 끝나면 재전송은 '확정 미접수'를 받는데 원본이 뒤이어 커밋할 수 있었다(S4 설계 11절 R1). 판정 실패와 재생을 `503`으로 두던 것도 인그레스가 리더를 빼는 경로라 이 코드로 바꿨다(같은 절 R6).
- **`429`의 뜻.** §2-3의 429 클라이언트 규약을 그대로 따른다. 재조회(§7-6) 대상이 아니고 `client_order_id`를 보존해 같은 키로 다시 보낸다. 접수 429의 자동 재요청 금지도 같다. 확정은 이 요청에만 해당한다. 클라이언트가 앞서 같은 키로 보내 결과를 모르게 된(5xx·타임아웃) 요청이 서버에 늦게 닿으면, 그 요청은 이 `429` 뒤에 키를 새로 넣어 자기 접수를 할 수 있다. 그 요청의 상태는 여전히 `UNKNOWN`이고 §7-6을 따른다. 같은 키로 다시 보내면 어느 쪽이 접수됐든 기존 주문 반환이나 새 접수 하나로 수렴한다. 취소의 `429`는 이 취소 명령이 저널에 들어가지 않았다는 뜻이다. 그 사이 다른 취소나 체결로 주문이 바뀌었을 수 있으므로 같은 취소를 다시 보내 현재 상태를 받는다(이미 종결된 주문의 재취소는 `200` + 현재 상태, §4-B-2).
- **원소별 처리.** 배치 주문(§4-B-6)은 그 원소를 `in_progress`로 적고 다음 원소로 넘어간다(`429`면 `error.name=too_many_requests`, `409`면 `error.name=order_in_progress`, 둘 다 `retry_after=1`). 일괄 취소(§4-B-3)는 그 주문을 `failed[].reason=too_many_requests`로 적는다. 회원 폐쇄(§6-E)는 그 주문을 미완료로 센다.
- **`503`이 아닌 이유.** 쓰기 업스트림은 오픈소스 nginx의 수동 판정(`max_fails=1`, `proxy_next_upstream http_503`)이라 리더의 `503` 하나가 그 노드를 `fail_timeout` 동안 뺀다. 팔로워는 늘 `503`이므로 그동안 살아 있는 업스트림이 없어 모든 마켓의 쓰기가 `502`가 된다. 폐기용 nginx 1.29.8에서 하네스 형태와 엣지 형태 모두 리더의 `503` 한 건 뒤 약 1초 넘게 모든 쓰기가 `502`였고, 같은 자리의 `429`는 그 한 건만 돌아가고 재시도·퇴출이 없었다(S4 설계 3절 N, `harness/evidence/pml-s4-probe-20260924/`). 한 사용자의 잔고 행 대기가 모든 마켓의 쓰기 중단으로 번지지 않게 하려는 것이다. `409`도 `proxy_next_upstream`에 없는 코드라 같은 쪽이다. §2-5-a의 '503이 아닌 이유'와 같은 근거다.
- **한 마켓 안의 증폭.** 마켓 접수 락은 append 동안 유지된다. 그래서 오래 잠긴 잔고 행을 기다리는 주문 하나가 그 마켓의 접수 전체를 최대 `accept-lock-timeout`(1초) 막고, 그런 주문이 둘 이상 줄을 서면 같은 마켓의 다른 사용자도 대기 상한(2초)을 넘겨 `429`를 받는다. 잠긴 행을 쥔 것이 멈춘 트랜잭션이면 `idle_in_transaction_session_timeout`이 끊을 때까지 이어진다.
- **노드 용량 버킷과 다른 점.** 노드 용량 버킷(`node`)은 인증 전에 동시성을 잰다. 이 경합은 인증과 계정 토큰 차감을 지나 게이트가 열린 파티션의 마켓 접수 경로에서 난다. 차감한 계정 토큰은 돌려주지 않는다. 좀비 판정(DB 설계 §5.4-c 8)의 수요로는 두 경우 모두 세지 않는다. 이 경합의 원인은 대개 DB 쪽 잠금이라 페일오버로 풀리지 않기 때문이다. 접수 락을 쥔 요청이 대기 상한보다 오래 쥐고 있어서 난 대기 초과만 수요로 남는다.
- **관측.** `exchange_order_intake_contention_total{cause,response}`(`cause` = `lock_wait`\|`lock_wait_stuck`\|`55P03`\|`57014`\|`40P01`\|`40001`, `response` = `429`\|`409`)가 이 응답 수를 센다. `lock_wait_stuck`은 락을 쥔 요청이 대기 상한보다 오래 쥐고 있던 대기 초과다. 접수 락 대기 초과는 종전 `exchange_submit_lock_timeouts_total`에도, append의 일시 DB 오류는 `exchange_order_append_transient_total{sqlstate}`(v2.55부터 네 상태 모두)에도 함께 잡힌다.

#### 2-5-c. DB 연결 장애와 리더 자격 거부 (v2.56 신설, ADR 0034 S4b)

주문 접수·인증·조회가 DB에 닿지 못하거나 연결을 잃으면, 또는 저널 append가 리더 자격 거부로 끝나면 아래 응답이다. 계약은 코드보다 먼저 적었고 S4b 코드부터 적용한다. DB 연결 장애의 응답 규칙(`500` 대신 `503` + `Retry-After` + '재조회 후 판단 필요', 멱등 키와 예약은 §2-4의 V47 규칙대로 남김)은 사용자 확정(2026-09-25)이다. 그 전의 응답은 연결이 끊겨 롤백이 실패한 경우, 끊긴 풀 연결을 쓴 조회, fencing 거부가 `500`이고 커넥션 획득 초과와 기간 종료가 `503`이었다. 근거와 확인 결과는 `docs/reference/architecture/마켓분할_리더_S4b_설계.md` 2·3·4·11절과 `harness/evidence/pml-s4b-probe-20260925/`에 있다.

- **원칙.** 쓰기 업스트림에서 이 노드를 빼는 `503`은 이 노드가 그 마켓의 리더가 아니거나(게이트 닫힘), 실패한 문장과 다른 두 번째 DB 접근마저 DB에 닿지 못했을 때만 낸다. DB에 닿는다는 것이 확인되면 §2-5-b와 같은 `429`·`409`다. 인그레스는 `503`을 받은 요청을 POST까지 다른 노드로 다시 보내므로(`proxy_next_upstream ... http_503 non_idempotent`), 새 `503`은 아무것도 쓰지 않은 요청이나 다시 보내도 한 번의 효과로 모이는 요청에만 낸다.
- **DB 연결 장애의 범위.** SQLSTATE 08xxx(연결 예외), 53xxx(연결 수·디스크·메모리 부족), 57P01·57P02·57P03(관리자 종료, 비정상 종료, 기동 중), 커넥션 획득 실패와 트랜잭션 시작 실패, 연결이 끊겨 커밋이나 롤백이 실패한 경우다. 셋으로 나눈다. 획득 실패는 연결을 얻지 못해 문장을 보내지 않은 것이다. 연결 끊김은 얻은 연결이 문장·커밋·롤백 도중 끊긴 것이고, 원래 예외가 롤백 실패 뒤에 가려지며, 커밋 도중에 끊겼으면 커밋이 실제로 됐을 수 있다(폐기용 PostgreSQL에서 확인). 자원 부족은 서버가 그 문장을 거부한 것이다. 그래서 앱이 받은 예외만으로 이 요청의 접수 여부를 말하지 않는다. 일시 오류 넷(55P03·57014·40P01·40001)은 이 범위가 아니다. 주문 생성·취소 경로에서는 §2-5-b의 마켓 접수 경합이고 그 밖에서는 종전 응답이다. 54xxx·58xxx처럼 스프링이 자원 실패로 번역해도 연결이 아닌 오류는 종전처럼 `500`이다.
- **주문 생성.** append가 DB 연결 장애로 끝나면 §2-4의 저널 확인(V47)을 부른다. 확인이 연결 끊김으로 실패하면 다른 연결로 한 번 더 부르되 해시는 지우지 않는다. 확인이 이 요청의 주문이 저널에 있다고 하면 정상 접수와 같은 응답(§2-4 커밋 회수)이다. 확인이 저널에 없다고 하면 §2-5-b와 같은 규칙이다. 이 요청이 해시를 새로 넣었고 확인이 그 해시를 지웠으면(확정 미접수) `429 too_many_requests` + `Retry-After: 1` + `detail={"bucket":"market","scope":"order"}`이고, 공유 표시로 남겼거나 해시가 없거나 이 요청이 공유 표시를 채운 요청이거나 다시 부른 확인이 저널에 없다고 답했으면 `409 order_in_progress` + `Retry-After: 1`이다. 확인이 DB에 닿았는데 실패하면(같은 키의 어드바이저리 락 대기 초과 55P03, 문장 시간 초과 57014 등) `409 order_in_progress` + `Retry-After: 1`이다. 확인마저 DB에 닿지 못하면 `503 service_unavailable` + `Retry-After: 1`이고 메시지는 'DB 연결 장애. 재조회 후 판단 필요'다. 판정이 실패한 두 경우 모두 해시와 예약은 §2-4 규칙대로 남긴다. 클라이언트는 §7-6 재조회로 판단하거나 같은 `client_order_id`로 다시 보낸다. 커밋됐던 주문의 재전송은 저장된 해시 대조와 저널 확인으로 기존 주문 반환이 되고, 커밋되지 않았던 주문의 재전송은 새 접수가 된다. 확인은 같은 키의 append와 같은 어드바이저리 락 아래에서 하므로, 서버 쪽에서 아직 끝나지 않은 append가 있으면 그 커밋이나 롤백을 기다린 뒤 답한다. 그래서 연결 장애 뒤에도 해시를 지운 판정은 확정 미접수다. `429`는 해시를 지운 확인의 응답을 받았을 때만 나간다. 첫 확인이 해시를 지운 뒤 응답만 잃었으면 그 사이 같은 내용의 다른 요청이 같은 해시를 새로 넣고 접수했을 수 있고 확인은 해시를 넣은 요청을 가리지 못하므로, 다시 부르는 확인은 해시를 지우지 않고 저널에 없다고 답하면 `409`다(§2-4). 원인이 경합이 아니어도 `429`의 모양(`bucket=market`)은 같다. 클라이언트 동작이 같고, 버킷 값을 늘리면 §2-3의 버킷 목록을 읽는 클라이언트가 바뀌어야 하기 때문이다. 원인은 관측 계수기의 `cause`로 가른다. 이미 저널에 있는 주문의 재전송이 DB 연결 장애로 끝나면 `409 order_in_progress`다.
- **리더 자격 거부.** 저널 append가 리스 판정의 거부(`fencing 위반`), 옛 epoch의 삽입 0행, append 전 기간 검사의 기간 종료로 끝나면 그 명령은 트랜잭션째 롤백됐거나 DB에 가지 않아 저널에 없다. 응답은 그 순간 이 노드가 그 마켓의 접수 게이트를 열어 두었는지로 가른다. 거부 뒤 재확인이 거짓이면 같은 요청 처리 안에서 강등이 게이트를 먼저 닫는다. 게이트가 닫혔으면(이 노드는 더는 그 마켓의 리더가 아님) 페일오버 창과 같은 `503 service_unavailable` + `Retry-After: 1`('페일오버 진행 중. 재조회 후 판단 필요')이고, 쓰기 업스트림이 같은 요청을 다음 노드로 넘긴다. 게이트가 열려 있으면(재확인이 리스 권위를 증명함, 재확인 왕복이 실패해 판단을 다음 갱신으로 넘김, 같은 노드가 새 기간으로 다시 승격함) 이 노드를 빼지 않는다. 주문 생성은 위 V47 규칙(`429`·`409`, 확인마저 DB에 닿지 못하면 `503`), 재전송은 `409 order_in_progress`, 취소는 `429 too_many_requests`(`detail.bucket=market`, 확정 미접수)다. DB 설계 §5.4-c 6의 '재확인이 참이면 재시도한다'는 이 경로에서 클라이언트의 같은 키 재전송이 맡는다. 2026-09-24 S4 설계가 게이트 닫힘의 `503`을 적었으나 S4 코드는 fencing 거부에 `500`을 냈다.
- **취소.** 취소 명령의 append가 DB 연결 장애로 끝나면 대상 주문을 한 번 다시 읽어 DB 도달 여부를 본다(연결 끊김이면 한 번 더). 읽혔으면 `409 order_in_progress` + `Retry-After: 1`('취소 처리 중. 재조회 필요')이다. 취소 명령이 저널에 들어갔는지 모르므로 읽은 상태와 무관하게 이 코드다. 읽기마저 DB에 닿지 못했으면 `503 service_unavailable` + `Retry-After: 1`('DB 연결 장애. 재조회 후 판단 필요')이다. 클라이언트는 재조회하거나 같은 취소를 다시 보내 수렴한다(이미 종결된 주문의 재취소는 `200` + 현재 상태, §4-B-2).
- **원소별 처리와 조기 종료.** 배치 주문(§4-B-6)은 그 원소를 `in_progress`로 적는다(`error.name`은 `service_unavailable`, `too_many_requests`, `order_in_progress` 가운데 하나, `retry_after=1`). 일괄 취소(§4-B-3)는 DB 연결 장애로 끝난 취소를 단건 응답과 무관하게 `failed[].reason=service_unavailable`로 적는다. 리더 자격 거부는 게이트가 닫혔으면 `service_unavailable`, 열렸으면 `too_many_requests`다. 회원 폐쇄(§6-E)는 이 주문들을 미완료로 센다. 한 원소가 'DB에 닿지 못함'(확인이나 재조회마저 DB 연결 장애)으로 끝나면 남은 원소는 시도하지 않고 배치는 `in_progress`(`service_unavailable`), 일괄 취소는 `service_unavailable`로 적는다. 시도하지 않은 원소는 같은 `client_order_id`로 다시 보내면 새 접수가 되고, 일괄 취소는 다시 부르면 남은 주문을 취소한다. 원소의 접수 전 조회(계정 상태, 멱등 키, 대상 주문)가 DB 연결 장애로 끝나면 종전처럼 요청 전체가 오류다(주문 경로라 `503`).
- **인증 계층과 컨트롤러 계층.** 인증의 `api_keys`·세션 조회가 DB 연결 장애로 끝나면 메서드와 무관하게 `503 service_unavailable` + `Retry-After: 1`('일시적 처리 불가. 잠시 후 재시도 필요')이다. 인증은 쓰기 전이고 nonce는 노드 메모리라 다른 노드로 넘어가도 아무것도 두 번 일어나지 않는다. 컨트롤러 계층의 DB 연결 장애는 안전 메서드(GET·HEAD·OPTIONS)와 재전송 안전 처리기(주문 `/api/v1/orders*`, 이체 `/api/v1/transfers*`)에서 `503 service_unavailable` + `Retry-After: 1`('DB 연결 장애. 재조회 후 판단 필요')이다. 그 밖의 쓰기(게시판, 문의, 회원 인증, 데모 세션, 관리·하네스 평면)의 연결 끊김과 자원 부족은 종전처럼 `500 server_error`다. 커밋됐을 수 있는 멱등 키 없는 쓰기를 인그레스가 다른 노드에서 다시 실행하지 않게 하려는 것이다(게시판 작성의 멱등은 노드 메모리라 노드를 넘지 못한다). 커넥션 획득 실패·트랜잭션 시작 실패·일시 오류는 v2.17부터 모든 메서드에서 `503`이고 바뀌지 않는다.
- **응답을 쓸 수 없는 요청.** 클라이언트나 LB가 연결을 먼저 끊어 응답을 쓸 수 없으면 서버는 오류 응답을 렌더하지 않고 서버 오류로 세지 않는다. 그 요청의 서버 쪽 결과는 달라지지 않으며 클라이언트는 §2-3 장애 규약대로 재조회한다.
- **인그레스 영향(2026-09-25 사용자가 위 `503` 응답을 확정했다. 비용을 줄이는 방법은 ADR 0034 S5가 다룬다).** 쓰기 업스트림은 리더의 `503` 한 건으로 그 노드를 `fail_timeout`(2초) 동안 뺀다(§2-5-b의 '503이 아닌 이유'). 옛 리더만 DB에 닿지 못하고 다른 노드가 새 리더이면 그 `503`은 다음 노드로 넘어가 클라이언트가 새 리더의 응답을 받는다. 종전 `500`은 그대로 클라이언트에게 갔다. 리더가 DB에 닿지 못하고 다른 노드가 팔로워이면 장애 동안은 `503`·`502`이고 DB가 돌아온 뒤 최대 약 2.4초 동안 `502`가 남는다. DB는 살아 있는데 풀의 끊긴 연결 하나가 인증이나 컨트롤러 조회(주문 접수 전 조회 포함)에 쓰이면(DB가 흔들린 직후) 약 1.3~2.1초 동안 모든 쓰기가 `502`다. 주문 생성의 저널 확인과 취소 재조회는 연결 끊김이면 한 번 더 DB에 닿아 보고, DB에 닿으면 `429`나 `409`라 끊긴 연결 하나로는 이 경로를 타지 않는다. 폐기용 nginx 1.29.8에서 잰 값이다. 이 비용, DB가 흔들린 직후를 재는 기록 실험(B1), 파티션별 업스트림(ADR 0034 S5)에서의 재검토는 S4b 설계 4-4, 7-2, 9절에 있다.
- **좀비 판정 수요.** DB 연결 장애와 리더 자격 거부의 응답은 마켓 접수 경합이 아니므로 좀비 판정(DB 설계 §5.4-c 8)의 수요에서 빼지 않는다. DB에 닿지 못하는 리더는 강등돼야 한다.
- **관측.** `exchange_order_intake_unavailable_total{cause,response}`(`cause` = `connection`\|`fencing`, `response` = `503`\|`429`\|`409`, 여섯 조합)가 주문 생성·취소의 이 응답 수를 센다. `fencing`은 리더 자격 거부이고, 응답 `503`은 게이트가 닫힌 노드, `429`·`409`는 게이트를 연 노드의 것이다. 경합의 `429`·`409`는 종전처럼 §2-5-b의 계수기에 따로 잡힌다. 저널 확인 실패 수는 종전 `exchange_order_intake_unresolved_total`(DB에 닿았든 닿지 못했든), 확인이 커밋을 찾아 접수로 처리한 수는 `exchange_order_intake_commit_recovered_total`이다. 응답마다 WARN 로그 한 줄(경로, 응답 코드, 확인 호출 수, 원인 예외 사슬과 SQLSTATE)을 남긴다. 인증과 컨트롤러의 응답은 `http_server_requests_seconds_count`의 상태 코드로 센다.

#### 2-5-d. 마켓 파티션 라우팅과 소유권 선검사 (v2.57 신설, ADR 0034 S5)

파티션 리더 모드(P3)에서는 마켓마다 주문을 접수하는 노드가 정해진다(DB 설계 §5.4-c). 클라이언트는 주문 쓰기에 마켓 힌트 헤더를 싣고, 인그레스는 그 힌트로 파티션의 소유 노드에 보내며, 노드는 자기가 접수하지 않는 마켓의 주문을 아무 상태도 건드리기 전에 돌려보낸다. 라우팅은 안전성의 근거가 아니다. 잘못 간 요청은 선검사의 `503`이나 DB fencing 거부로 끝나고, 쓰기 권한 판정은 종전처럼 DB가 기록할 행의 마켓에서 한다(DB 설계 §5.4-b). P1·P2에서도 같은 코드가 돈다. 파티션이 `matching-engine` 하나라 리더 노드가 모든 마켓의 소유 노드다. 계약은 코드보다 먼저 적었고 S5 코드부터 적용한다. 근거와 확인 결과는 `docs/reference/architecture/마켓분할_리더_S5_설계.md`와 `harness/evidence/pml-s5-probe-20260925/`에 있다.

- **힌트 헤더.** `X-Exchange-Market: <마켓 표기>`(예 `KRW-BTC`)다. 주문 생성(`POST /orders`), 일괄 주문(`POST /orders/batch`), 단건 취소(`DELETE /orders/{id}`, `DELETE /orders/by-client/{client_order_id}`), 마켓을 준 일괄 취소(`DELETE /orders?market=`)에 싣는다. 선택 헤더이고 웹과 봇은 늘 싣는다. 서명(§3-3) 밖이라 `query_hash`에 들어가지 않으므로 힌트는 아무 권한도 주지 않는다. 서버는 힌트를 그 요청이 실제로 가리키는 마켓(생성·배치는 바디의 `market`, 단건 취소는 대상 주문의 마켓, 일괄 취소는 `market` 쿼리)과 대조해 다르면 `400 invalid_parameter` + `detail.constraint=market_hint_mismatch`로 끝낸다. 생성·배치의 대조는 멱등 키 선점·예약·채번보다 먼저, 취소의 대조는 채번보다 먼저 하므로 불일치 요청은 그 셋을 남기지 않는다. 마켓 없는 일괄 취소(`DELETE /orders`)에 힌트를 실어도 같은 `400`이다. 같은 이름의 헤더가 둘 이상이면 선검사가 인증 전에 `400 invalid_parameter` + `detail.constraint=market_hint_duplicate`로 끝낸다. 값에 쉼표가 있는 한 줄도 같다. HTTP는 같은 이름의 필드 여러 줄을 쉼표로 이은 한 줄과 같은 뜻으로 보므로 프록시나 클라이언트 라이브러리가 두 줄을 한 줄로 합쳐 보낼 수 있고, 마켓 표기에는 쉼표가 없기 때문이다(S5 코드 단계에서 더함, S5 설계 12절). 값은 앞뒤 공백을 뺀 뒤 마켓 표기와 대소문자까지 같아야 한다. nginx의 `map`은 대소문자를 가리지 않아 소문자 힌트도 그 파티션 업스트림으로 보내지만(S5 설계 3절 N5 R3) 서버는 `400`(`market_hint_mismatch`)으로 끝낸다. 선검사는 카탈로그에 없는 힌트(소문자 등)로 소유를 판정하지 않고 바디나 쿼리의 마켓을 본다(아래 소유권 선검사). 그래서 틀린 모양의 힌트를 실은 주문 생성·배치·마켓을 준 일괄 취소도 소유하지 않는 노드에서는 인증 전에 돌아가고 소유 노드가 `400`을 낸다. 대상 주문을 읽어야 대조할 수 있는 단건 취소만 힌트가 틀리면 소유하지 않는 노드에서 인증될 수 있고, 그 노드가 대상 주문을 읽은 뒤 채번 전에 `400`으로 끝낸다. 브라우저 교차 출처 요청을 위해 CORS 허용 헤더에 이 이름을 더한다(dev·prod 설정).
- **소유권 선검사.** 노드는 주문 쓰기(`POST /orders`, `POST /orders/batch`, `DELETE /orders*`)를 바디 상한(§2-7)을 지난 직후, 수용 제한(§2-5-a)·IP 버킷(§2-5)·인증(§3-4)보다 먼저 검사한다. 필터 순서는 바디 상한 4, 선검사 5, 수용 제한 6, IP 버킷 7, 인증 10이다(v2.57에서 바디 상한을 5에서 4로 옮겼다). 마켓은 서명된 값을 먼저 본다. 주문 생성은 바디 최상위의 `market`, 배치는 바디 배열에서 `market`이 문자열인 첫 원소의 값(앞 원소에 `market`이 없거나 문자열이 아니면 다음 원소를 본다), 일괄 취소는 `market` 쿼리다. 바디는 끝까지 읽고, 한 객체에 `market`이 여럿이면 마지막 값을 쓴다. 컨트롤러의 JSON 해석이 마지막 값을 쓰므로 선검사가 판정한 마켓과 접수되는 마켓이 같아진다. JSON이 끝까지 온전하지 않으면 찾지 못한 것으로 본다(S5 코드 단계에서 더함). 그 값이 없거나 카탈로그에 없으면 힌트를 본다. 단건 취소는 서명된 값에 마켓이 없으므로 힌트로만 판정하고, 힌트 없는 단건 취소는 대상 주문을 DB에서 읽어야 마켓을 알므로 선검사하지 않는다(v2.57 설계 검토 대응. 첫 판은 힌트를 먼저 보고 배치의 첫 원소만 봐, 첫 원소에 `market`이 없는 배치와 카탈로그에 없는 힌트를 실은 요청이 소유하지 않는 노드에서 인증됐다). 찾은 마켓이 이 노드의 메모리 카탈로그와 파티션 대응에 있고 그 마켓 파티션의 접수 게이트가 이 노드에서 닫혀 있으면(그 파티션의 리더가 아니거나, 리더지만 승격 재생 중이거나, 엔진을 끈 노드) `503 service_unavailable` + `Retry-After: 1` + `detail={"route":"not_owner"}` + 응답 헤더 `X-Exchange-Route: not-owner`로 끝낸다. 이 거절은 nonce를 등록하지 않고, 계정·IP 레이트리밋 토큰과 노드 용량 슬롯을 쓰지 않으며, 멱등 키·in-flight 예약·DB 예약·주문 id 채번과 좀비 판정 수요(DB 설계 §5.4-c 8)를 건드리지 않고, DB에 가지 않는다. 마켓을 찾지 못했거나 카탈로그·대응에 없으면 거절하지 않고 뒤 단계로 넘기며 뒤 단계가 종전처럼 판정한다. 선검사는 카탈로그와 대응을 다시 읽지 않는다. 인증 전의 요청이 DB 조회를 일으키지 않게 하려는 것이다. 조회 역할 노드에는 이 검사를 걸지 않는다(조회 노드 필터가 쓰기를 먼저 막는다).
- **재시도.** 선검사의 `503`은 아무것도 쓰지 않은 거절이다. 인그레스는 `proxy_next_upstream http_503 non_idempotent`로 같은 요청을 다른 노드에 다시 보내고, 그 노드가 소유 노드면 접수한다. nonce는 노드 메모리라 같은 서명 요청을 다른 노드가 처음 본다(§3-4 각주). 두 노드 모두 그 마켓을 접수하지 않는 창(인계 중, 승격 재생 중)에는 클라이언트가 `503`이나 인그레스의 `502`를 받고 §2-3 장애 규약대로 재조회한다. 이 거절의 주문은 저널에 없으므로 재조회는 `404`(미접수)를 주고, 같은 `client_order_id`로 다시 보내면 새 접수가 된다.
- **힌트 없는 요청.** 서버의 선검사가 바디에서 마켓을 찾으므로 소유하지 않는 노드에 간 주문 생성과 배치도 아무것도 건드리지 않고 `503`으로 끝난다. 힌트 없는 단건 취소가 소유하지 않는 노드에 가면 인증과 계정 토큰을 쓴 뒤 게이트웨이가 대상 주문을 읽고 채번 전에 같은 `503`(`route=not_owner`)을 낸다(대상 주문이 이미 종결됐으면 `200` + 현재 상태). 이 응답은 좀비 판정 수요에서 뺀다(DB 설계 §5.4-c 8 v2.57). 인그레스의 힌트 없는 경로는 종전 쓰기 경로(1차·백업)다. 그래서 P3에서 한 노드가 소유하지 않는 마켓의 힌트 없는 요청으로 그 노드가 그 경로에서 빠지면, 그 경로로 오는 다른 마켓 요청이 잠시 `502`를 받는다. 폐기용 nginx 1.29.8에서 확인했고 힌트를 실은 경로에는 번지지 않았다(S5 설계 3절 N5). 웹·봇·하네스 부하 도구는 힌트를 싣는다.
- **인그레스(하네스).** `harness/nginx/nginx.partition.conf`가 파티션별 쓰기 업스트림 둘(`exchange_p0`의 1차 app-1, `exchange_p1`의 1차 app-2, 백업은 다른 노드이고 퇴출하지 않는다)과 힌트 맵을 둔다. 주문 쓰기(POST·DELETE)만 힌트로 가르고 조회·WebSocket·호가·티커와 그 밖의 쓰기는 종전 경로로 보낸다. 파티션 1차의 `max_fails`는 2다. 소유 노드가 DB가 흔들린 직후 낸 `503` 한 건으로는 그 파티션 쓰기를 빼지 않고, 두 건이면 종전처럼 약 2초 동안 뺀다. 한 파티션 업스트림의 실패 계수는 다른 파티션과 힌트 없는 경로에 번지지 않는다. 종전 `harness/nginx/nginx.conf`는 그대로 두고 카오스 스택이 `CHAOS_LB_CONF`로 이 파일을 고를 때만 쓴다. 엣지(`deploy/nginx-edge.conf.template`)와 학교 app-1 설정은 이 판에서 바꾸지 않는다(개인 AWS 검증 S8).
- **DB가 흔들린 직후.** 인증 필터의 키 조회와 주문 생성·취소의 접수 전 읽기(계정 상태, 저장된 해시, 가용 잔고, 대상 주문, 저널 존재)가 연결 끊김(§2-5-c의 분류)으로 끝나면 다른 연결로 한 번 더 읽는다. 끊긴 연결은 풀이 버리므로 두 번째 읽기는 다른 연결이다. 두 번째마저 DB에 닿지 못할 때만 §2-5-c의 `503`이다. 커넥션 획득 실패는 다시 읽지 않는다. 쓰기 문장(멱등 키 선점, append)은 다시 하지 않는다. DB가 살아 있는데 쓰던 연결만 끊긴 장면에서 리더가 `503`을 내 쓰기 업스트림에서 빠지는 일을 줄이려는 것이다(S5 설계 4-6).
- **관측.** `exchange_routing_precheck_total{result}`(`result`=`accepted`·`not_owner`·`unresolved`)가 선검사 필터의 결과를, `exchange_routing_cancel_not_owner_total`이 힌트 없는 단건 취소에 게이트웨이가 낸 소유 아님 `503`을, `exchange_routing_hint_rejected_total{reason}`(`market_hint_mismatch`·`market_hint_duplicate`·`batch_single_market`)이 힌트와 배치 규칙의 거절을, `exchange_db_read_retry_total{site,outcome}`(`site`=`auth`·`order`, `outcome`=`recovered`·`unreachable`)이 위의 한 번 더 읽기를 센다(하네스 CI/CD §5-2). 인그레스 접근 로그는 시도한 업스트림과 상태 목록, 힌트를 적는다(하네스 검증설계 S5 패치 노트).

#### 2-5-e. DB 쓰기 불가와 거래 일시 중단 (v2.60 신설, ADR 0036)

Patroni 엄격 동기 구성(ADR 0036)에서는 동기 standby가 없으면 PostgreSQL이 커밋을 확인하지 않고 끝없이 기다린다. 문장 시간 제한도 그 대기를 끊지 않는다(DB 설계 §5.4-f 3). 이 절은 그동안 쓰기 요청을 DB에 닿기 전에 거절하고 조회는 계속 받는 계약이다. 계약은 코드보다 먼저 적었고, 코드는 작업 브랜치 `claude/patroni`에 있으며 쓰기 가능 탐지를 켠 노드(`exchange.write-availability.enabled`, 기본 꺼짐)부터 적용한다. 근거와 확인 결과는 `docs/reference/architecture/DB_자동전환_Patroni_설계.md` 4·6절과 `harness/evidence/patroni-sync-probe-20260925/`에 있다.

- **상태.** 쓰기를 받는 앱 프로세스마다 탐지기가 셋 가운데 하나를 정한다. ACTIVE(쓰기 받음), SUSPENDED(DB가 커밋을 확인하지 않는다는 것을 DB에서 확인함. 동기 standby 설정이 `'*'`이거나, 2초 넘게 끝나지 않은 탐지 커밋이 `SyncRep`에서 기다림), UNREACHABLE(탐지가 두 번 연달아 DB에 연결하지 못함)이다(DB 설계 §5.4-f 4). DB 쪽 확인이 없는 느린 탐지는 이 노드만의 느림일 수 있어 ACTIVE로 둔다. `'*'`인 동안에는 탐지가 빨라도 ACTIVE로 돌아가지 않는다. 공개 상태는 §4-A-7이다.
- **대상.** `/api/v1/**`와 관리 평면의 POST·PUT·PATCH·DELETE다. 주문 생성·취소·배치·일괄 취소, 입출금, 게시판, 문의, 회원 인증과 세션, API 키, 관리 평면의 변경이 모두 든다. 안전 메서드(GET·HEAD·OPTIONS), `/actuator/**`, `/harness/v1/**`, `GET /api/v1/status`는 대상이 아니다. 대상 경로는 필터 등록(`/api/v1/*`, `/admin/v1/*`)이 정한다. 서블릿 컨테이너는 `;` 매개변수를 떼고 퍼센트 인코딩과 `//`·`/./`·`/../`를 푼 경로로 필터와 컨트롤러를 함께 고르므로, `/api/v1;x=1/orders`나 `/api/%761/orders`처럼 적은 쓰기도 컨트롤러에 닿는 한 대상이다. 게이트는 원시 요청 URI로 대상을 다시 가리지 않는다(같은 날 코드 검토).
- **응답.** SUSPENDED면 `423 trading_suspended` + `Retry-After: 5` + `detail={"reason":"replication_unavailable","since":<ms>,"status_url":"/api/v1/status"}`이고 메시지는 '거래 일시 중단. 재개 후 다시 시도 필요'다. UNREACHABLE면 `503 service_unavailable` + `Retry-After: 1`이고 메시지는 'DB 연결 장애. 재조회 후 판단 필요'로 §2-5-c의 'DB에 닿지 못함'과 같다. 거절은 수용 제한 필터(§2-5-a)와 바디 상한 필터보다 앞의 필터(order 3, 아래 병합 주)가 하므로 DB, 수용 허가, 멱등 키, nonce, 계정 토큰, 잔고 예약을 건드리지 않는다. 수용 제한 뒤에 두면 중단 직전에 들어와 커밋을 기다리는 요청이 쥔 허가 때문에 중단 동안의 주문이 `429`를 받는다(같은 날 설계 검토). ADR 0034 S5가 넣을 파티션 선검사 필터보다도 앞이다. 중단은 클러스터 전체의 상태라 파티션과 무관하게 답이 같기 때문이다.
- **`423`의 뜻.** 이 시도가 이 노드에서 쓴 것은 없다. 저널·원장·멱등 키·수용 허가에 이 시도가 남긴 것이 없다. 같은 요청의 앞선 시도가 없었다는 뜻은 아니다. 인그레스는 10초 동안 응답이 없는 POST를 다음 노드로 다시 보내므로, 게이트가 닫히기 직전에 들어와 커밋을 기다리는 첫 시도(기본 풀의 출금, 앞선 대기가 길어 10초를 넘긴 접수)가 있으면 클라이언트는 다시 보낸 시도의 `423`만 받고 첫 시도는 재개 뒤 커밋될 수 있다. 그래서 클라이언트는 중단 중에 자동 재전송하지 않고, 상태 API가 `trading_suspended=false`를 낸 뒤 같은 `client_order_id`(이체는 같은 `Idempotency-Key`)로 다시 내거나 by-client 재조회(§4-B-4 (a))로 결과를 정한다. 새 키로 다시 내지 않는다(5xx의 '신규 재전송 금지'와 같다). 같은 키 재전송은 멱등 키로 한 번의 효과로 모이고, 새 키는 모르는 주문 체결이나 두 번의 출금 잠금을 만들 수 있다. 배치 주문과 일괄 취소는 요청 전체가 `423`이고 원소를 시도하지 않는다. 회원 폐쇄(§6-E)도 요청 전체가 `423`이다.
- **중단을 넘는 요청.** 게이트를 지난 뒤 중단이 시작된 주문 생성·취소·출금은 쓰기 직전에 게이트를 지날 때의 세대 번호를 다시 보고, 바뀌었거나 ACTIVE가 아니면 쓰지 않고 `423`이다. 이미 넣은 멱등 키는 남긴다(§2-4의 고아 해시와 같다). 게이트가 닫히기 전에 커밋을 기다리게 된 접수는 §2-5-c 규칙대로 끝난다. Patroni 구성에서는 접수 append 풀의 소켓 제한(6초)이 그 요청을 풀고, V47 판정이 같은 키의 락에 막혀 `409 order_in_progress`다. 그 append는 재개 뒤 커밋될 수 있다(결과 불명). 앞선 대기가 겹쳐 10초를 넘으면 인그레스가 다시 보낸 시도가 `423`을 받는다(위 `423`의 뜻). 출금은 기본 풀에서 재개까지 기다린 뒤 커밋된다.
- **재조회.** 상태가 ACTIVE가 아니거나 ACTIVE로 돌아온 지 10초가 안 된 노드에서는 `GET /orders/by-client/{client_order_id}`가, 그 사용자의 그 키로 `idempotency_keys` 행이 있는데 저널에 NEW가 없으면 `404` 대신 `409 order_in_progress` + `Retry-After: 1`이다. 커밋을 기다리는 append는 저널에 보이지 않아 `404`가 거짓 미접수가 되기 때문이다(§4-B-4 (a)).
- **확정 응답 보류.** 상태가 ACTIVE가 아니거나 탐지 연결이 끊긴 뒤 다시 확인된 탐지가 아직 없는 노드('재확인 전', DB 설계 §5.4-f 4)에서는, 저널에 NEW가 보여도 by-client 재조회가 '접수됨'(프로젝션 반영 뒤의 `200`)을 내지 않고 `409 order_in_progress` + `Retry-After: 1`이다. 접수의 연결 끊김 뒤 V47이 이 요청의 NEW를 찾아도 커밋 회수의 `201` 대신 `409 order_in_progress`다(라이터 따라잡기 요청은 그대로 한다). postmaster 크래시 재시작 뒤에는 커밋을 기다리던 행이 복제 없이 보이므로(DB 설계 §5.4-f 3), 그 행을 근거로 확정하면 복제되지 않은 데이터에 확인이 나간다. 이 규칙은 기다림만 늘린다. 정상 접수의 `201`은 대상이 아니다. 그 근거는 그 요청 자신의 커밋이 동기 대기를 통과했다는 사실이다. 같은 `client_order_id` 재전송의 재생 `200`(`X-Idempotent-Replay`, 배치 원소의 `replayed` 포함)도 같은 이유로 대상이 아니다. 재생도 append 머리말이 멱등 판정보다 먼저 리스 행을 잠가 WAL을 쓰므로 그 트랜잭션의 커밋이 동기 대기를 통과한 뒤에 나가고, 그때 WAL 순서상 앞선 NEW도 standby에 있다(같은 날 코드 검토, Patroni 설계 4-5). 이체의 같은 `Idempotency-Key` 재요청(§4-E)은 기존 행을 읽기만 해 돌려주므로 이 논거가 서지 않고, 아직 이 규칙의 대상도 아니다(Patroni 설계 12절).
- **재개.** 탐지 커밋이 다시 빠르게 끝나고 동기 standby 설정이 `'*'`가 아니면 ACTIVE로 돌아가고 게이트가 열린다. 사람의 조작은 필요 없다. 클라이언트는 상태 API로 알아챈다. 재개 때 서버는 중단 동안 커밋된 접수가 체결 경로에 오르도록 마켓별 따라잡기를 한다(DB 설계 §5.4-f 6).
- **`503`이 아닌 이유.** 쓰기 업스트림은 `503` 한 건으로 그 노드를 `fail_timeout` 동안 빼고 팔로워도 쓰기에 `503`이다(§2-5-b). 중단이 몇 분 이어지는 동안 쓰기마다 `503`을 내면 업스트림이 계속 비어 쓰기 업스트림을 지나는 계좌·미체결 주문 조회까지 `502`가 된다. `423`은 `proxy_next_upstream` 목록에 없어 재시도·퇴출이 없다. 같은 코드가 로그인 잠금(`account_locked`)으로 이미 있어 클라이언트가 '잠김, `Retry-After` 뒤'로 다룬다. UNREACHABLE의 `503`은 탐지기가 DB에 닿지 못함을 두 번 확인한 뒤이고 아무것도 쓰지 않았으므로 §2-5-c의 원칙(다른 노드로 넘겨도 한 번의 효과)과 같다.
- **좀비 판정.** 게이트가 SUSPENDED로 거절한 요청(`423`)은 인증 필터 앞에서 끝나 수요에 들어가지 않고, SUSPENDED인 동안 두 층 판정은 멈춘다. UNREACHABLE로 거절한 주문 쓰기(`503`)는 게이트가 노드 층 수요로 세고, UNREACHABLE에서는 판정을 멈추지 않는다. 이 노드만 DB에 못 닿는 경우 리더가 물러나야 하기 때문이다(DB 설계 §5.4-f 5).
- **필터 순서(병합 주).** 2026-09-26 main 병합 때 마켓별 리더 S5가 바디 상한 필터를 order 4로, 소유권 선검사를 order 5로 옮겨 두어 게이트를 order 3으로 옮겼다. 순서는 CORS(0), 게이트(3), 바디 상한(4), 소유권 선검사(5), 수용 제한(6), IP 스로틀(7), 인증(10)이다. 중단은 파티션과 무관한 클러스터 상태라 소유권 선검사보다 먼저 답한다. 그래서 UNREACHABLE의 `503`은 이 노드가 소유하지 않는 마켓의 주문 쓰기도 노드 층 수요로 센다(병합을 맡은 AI의 선택, 사용자 확인 대상).
- **관측.** `exchange_write_availability_state{state}`, `exchange_write_gate_rejected_total{response}`(`423`·`503`), `exchange_trading_suspended_total{reason}`, `exchange_write_probe_seconds`, `exchange_write_probe_unconfirmed_total`, `exchange_write_definitive_deferred_total{path}`(`requery`·`v47`)다(하네스 CI/CD §8-1 목표, Patroni 설계 7절).
- **비용(AI 선택, 사용자 확인 필요).** 게이트는 탐지 임계(2초)와 원인 확인만큼 늦게 닫힌다. 그 사이의 쓰기는 커밋 대기에 걸리고, 멱등 키 없는 쓰기(게시판 등)는 nginx의 10초 재전송이 아직 게이트를 닫지 않은 노드에 닿으면 두 번 커밋될 수 있다. `423` 대신 `422 domain_rejected`(정지 마켓의 `market_suspended`와 같은 모양)도 검토했으나 거래 밖 쓰기를 한 이름으로 덮지 못해 고르지 않았다.

### 2-6. 페이지네이션 (opaque 커서 단일 축)

- **모든 리스트**: 단일 `cursor` 파라미터. 서버가 `(ts, id)` 복합키를 base64url 인코딩한 **opaque 커서**를 발급(요청 축=응답 축 일치). 최초 요청만 `to`(종료시각)/`count`(≤200, 주문내역 ≤1000) 허용.
- 정렬: 시장 데이터는 **시각 내림차순**, 경계는 **exclusive**(중복 방지). `next_cursor`는 마지막 레코드의 `(ts,id)` 인코딩.
- 오프셋(page) 방식은 실시간 삽입 시 중복/누락 → 미채택.
- **최대 depth 가드**: `next_cursor` 추종은 서버 보존기간(90일) 내로 제한, 초과 시 `has_more:false`.

```json
{ "data":[ ... ],
  "pagination": { "next_cursor":"eyJ0cyI6IjIwMjYtMDctMjFUMDk6MDA6MDAuMDAwWiIsImlkIjoiMDFKOFpUIn0",
                  "has_more": true } }
```
> 위 커서를 디코딩하면 `{"ts":"2026-07-21T09:00:00.000Z","id":"01J8ZT"}`. 커서의 `id` 축은 **외부 노출 id 문자열**(주문=UUID(v2.7 W3), 체결=event_seq 파생 id. §7-1, §4-B-4 커서 통일)이다. id 발급 규약(order=사전 발급 박제, trade=event_seq 결정론 파생)은 §7-1 타입 규약(v2.6)을 따른다. 내부 BIGINT PK는 커서에 절대 노출하지 않는다(v2.3, 이전 예시의 정수 id `90011`은 문자열 id 통일 규칙 위반이라 교정). **예외 · candles(v2.5)**: candles는 외부 노출 id가 없는 자연키 리소스이므로 커서의 `id` 축으로 **`(candle_time, market)` 자연키를 사용**한다(§4-A-5, 내부 PK 미노출 원칙은 동일 유지). **예외 · ledger_entries(v2.20 보완)**: 원장 분개도 외부 노출 id가 자연키도 없는 리소스이므로 **`entry_id`(분개 PK 10진 문자열)를 외부 식별자로 계약 지정**하고 커서의 `id` 축으로 쓴다(§4-C-3). 대리키 노출을 허용하는 근거: 원장 분개는 클라이언트가 상호참조하는 대상이 아니라 식별자 유출의 실해가 없고, 조회 범위가 자기 계정 분개로 한정되며, 정산 핫패스(`post_ledger_transaction`)에 분개별 외부 id 발급을 얹는 쓰기 비용이 아무도 참조하지 않는 식별자를 위한 것이 되기 때문이다. 이 예외는 `ledger_entries`에 한정하며 다른 리소스의 내부 PK 노출 근거로 재사용하지 않는다.

### 2-7. CORS / 표준 HTTP 가드

- CORS: 데모 화면 오리진만 화이트리스트(`https://demo.exchange.local`). 자격증명 포함 요청 허용, `*` 금지.
- 요청 바디 상한 64KB(`413`), 미지원 메서드 `405`, 미지원 미디어타입 `415`.

---

<a name="3"></a>
## 3. 인증/서명 방식 설계

### 3-1. 선택: JWT(HS256) + canonical 서명(SHA512) + nonce + timestamp/recvWindow

**근거**: 서명 대상=payload, 파라미터·엔드포인트 무결성=canonical hash로 관심사 분리. Binance식 `timestamp`+`recvWindow`+`nonce`로 재전송·시계 오차를 명시 제어. Upbit/Bithumb + Binance 관례의 장점 흡수.

### 3-2. API Key 발급 모델 및 secret 저장/회전 (신규 확정)

- 사용자/봇마다 `access_key`(공개) + `secret_key`(비밀) 쌍 발급.
- 키 scope: `READ` | `TRADE` | `ADMIN`(관리 평면 전용) | `WITHDRAW`(데모 미사용). 대문자가 정본이다(DB CHECK `api_keys.scope IN ('READ','TRADE','WITHDRAW','ADMIN')`, V1__init.sql + V5__admin.sql). 소문자·`read_only` 표기는 폐기한다. 조회키로 주문 시 `403 out_of_scope`.
- **secret 저장**: HMAC 재계산을 위해 서버가 복원 가능해야 하므로, `secret_key`는 **AES-256-GCM 봉투암호화(Envelope Encryption)** 후 DB 저장. 마스터키는 시크릿 관리소(로컬 `.env` / K8s Secret / Vault)에서 주입, 코드·저장소에 평문 금지.
  - **저장 컬럼 명명 확정(신규)**: 저장 컬럼은 `api_keys.enc_secret`(§7-1)이며 값은 AES-256-GCM 암호문이다. bcrypt/SHA 등 **비가역 해시 저장은 금지**한다. 해시로 저장하면 §3-4 2단계(access_key로 secret 복호화 후 HMAC-SHA256 재계산)가 성립하지 않는다. `secret_hash` 류의 명명은 비가역 해시를 함의하므로 사용하지 않는다. DB DDL·마이그레이션(V1 init)의 `secret_hash` 컬럼은 `enc_secret`로 개명하고 암호문 저장 용도로 확정한다(하네스 검증설계 부록 A #11).
- **회전/폐기**: `POST /api/v1/keys/{access_key}/rotate`(새 secret 발급, grace 기간 이중 유효), `DELETE /api/v1/keys/{access_key}`(즉시 revocation, 캐시 무효화 TTL 즉시, §4-C 표 등재, v2.5). 회전 이벤트는 감사 로그.
- **안전 규칙(주의)**: 완전 시뮬레이션이므로 데모용 키만 사용. 사용자의 실제 금융 자격증명(카드/계좌/비밀번호)은 절대 입력·저장하지 않는다.

### 3-3. JWT payload 및 canonical 서명 (method+path 바인딩)

```
Header: Authorization: Bearer <JWT>

JWT payload = {
  access_key : "<공개키>",
  nonce      : "<랜덤 UUID>",
  timestamp  : 1784624400123,        # ms
  recvWindow : 5000,                 # (선택, v2.2) ms 정수. 미포함 시 서버 기본 5000ms 적용
  query_hash : "<SHA512(canonical string)>",
  query_hash_alg : "SHA512"
}
sign = HMAC-SHA256(base64url(header).base64url(payload), secret_key)   # HS256
```

**canonical string (엔드포인트 치환 리플레이 차단 + 배열/중첩 바디 확정)**:
```
canonical = METHOD + "\n" + PATH + "\n" + CANONICAL_PAYLOAD
query_hash = SHA512(canonical)      # hex, UTF-8 바이트 기준
```
- `METHOD` = 대문자 HTTP 메서드(`POST`), `PATH` = 버전 prefix 포함 전체 경로(`/api/v1/orders`).
- `CANONICAL_PAYLOAD`는 요청의 페이로드 위치에 따라 **두 방식 중 하나로 결정론적으로 산출**한다. 어느 경우든 클라이언트·서버가 **바이트 단위로 동일한 문자열**을 재현할 수 있어야 하며, 재현 불가능한(구현자 자유가 있는) 표현은 금지한다.

  **(a) 쿼리 파라미터 요청(GET, 그리고 바디 없는 DELETE)** · `SORTED_QUERY`:
  - 쿼리 파라미터를 **키 오름차순 정렬** 후 `key=value&key=value`로 연결. 비ASCII는 UTF-8 인코딩.
  - 배열/콤마 리스트(`markets=KRW-BTC,KRW-ETH`)는 값 내부를 **정렬하지 않고 원문 유지**하되 키는 정렬. 중복 키 금지(400). 빈 값은 `key=`로 포함.
  - **퍼센트 인코딩 기준(v2.2 확정)**: canonical은 **URL 디코딩된 값 기준**이다. 서버는 수신 쿼리스트링을 퍼센트 디코딩한 뒤 정렬·연결해 재계산하고, 클라이언트는 전송용 인코딩 이전의 원본 키·값으로 canonical을 만든다 → 전송 시 예약문자를 `%2C` 등으로 인코딩하든 그대로 보내든 canonical은 동일(구현자 자유 제거). 예: `GET /api/v1/ticker?markets=KRW-BTC%2CKRW-ETH` → canonical `SORTED_QUERY = "markets=KRW-BTC,KRW-ETH"`.
  - 파라미터 없으면 `SORTED_QUERY=""`(여전히 method+path는 서명됨).

  **(b) JSON 바디 요청(POST/PUT/PATCH, 그리고 바디 있는 DELETE)** · `JCS(body)`:
  - **flat `key=value&…` 평탄화를 사용하지 않는다.** 주문 객체 **배열**(`POST /api/v1/orders/batch`)이나 **중첩 객체**(`POST /admin/v1/sim/bots`의 `params:{…}`, `seed:{…}`)는 flat key=value로 무손실·결정론적 표현이 불가능하므로, **바디 JSON 자체를 canonical 직렬화한 원문 바이트를 해싱**한다.
  - 정규화 방식: **JCS(RFC 8785, JSON Canonicalization Scheme)**. ① 객체 멤버를 키의 **UTF-16 코드유닛 오름차순**으로 정렬(중첩 객체 재귀 적용), ② 배열은 **원소 순서 보존**(정렬 금지), ③ 공백·개행 제거, ④ 숫자는 JCS 수치 정규화, ⑤ 문자열은 JCS 이스케이프 규칙 → 그 결과 **UTF-8 바이트열**이 `CANONICAL_PAYLOAD`.
  - 동등 정의(구현 편의): `CANONICAL_PAYLOAD = SHA512(JCS_bytes)`를 쓰지 않고, **`canonical` 전체에 대해 한 번만 SHA512**를 적용한다(즉 위 공식 그대로 `SHA512(METHOD\nPATH\nJCS_bytes)`). 클라이언트·서버 모두 동일 라이브러리 규약(RFC 8785)을 따른다.
  - **금액/수량은 문자열**이므로(§2-1) JCS 숫자 정규화 대상이 아니다(따옴표 안의 `"0.01"` 그대로). 실제 정수/불리언 필드(예: `count`, `expect_failover`)만 JCS 수치/리터럴 정규화 대상.
  - 중복 키 금지(400). 바디가 비면 `CANONICAL_PAYLOAD=""`.

**서명 밖 헤더(v2.57, ADR 0034 S5)**: 마켓 힌트 `X-Exchange-Market`(§2-5-d)은 canonical에 넣지 않는다. 인그레스의 라우팅 입력일 뿐이라 권한을 주지 않고, 서버는 힌트를 서명된 내용(바디의 `market`, `market` 쿼리)이나 대상 주문의 마켓과 대조해 다르면 `400`으로 끝낸다. 그래서 가로챈 요청의 힌트를 바꿔도 다른 마켓에 효과를 낼 수 없다.

**canonical 예시 (배열 바디 · batch)**: 요청 `POST /api/v1/orders/batch`, 바디(v2.57부터 한 배치의 원소는 한 마켓이다, §4-B-6)
```json
[{"client_order_id":"b1","market":"KRW-BTC","ord_type":"limit","price":"50000000","side":"bid","volume":"0.01"},
 {"client_order_id":"b2","market":"KRW-BTC","ord_type":"limit","price":"51000000","side":"ask","volume":"0.02"}]
```
→ JCS 정규화(배열 순서 보존, 각 객체 키 정렬·공백 제거):
```
[{"client_order_id":"b1","market":"KRW-BTC","ord_type":"limit","price":"50000000","side":"bid","volume":"0.01"},{"client_order_id":"b2","market":"KRW-BTC","ord_type":"limit","price":"51000000","side":"ask","volume":"0.02"}]
```
→ `canonical = "POST\n/api/v1/orders/batch\n" + 위 JCS 문자열`, `query_hash = SHA512(canonical)`.

**canonical 예시 (중첩 바디 · admin 봇 생성)**: 요청 `POST /admin/v1/sim/bots`, 바디
```json
{"operator_id":"op-01","type":"MARKET_MAKER","count":40,"market":"KRW-BTC",
 "params":{"base_spread_bps":"8","quote_size":"0.05","skew_factor":"0.01","lambda":2.0},
 "seed":{"base":"1.0","quote":"50000000"}}
```
→ JCS 정규화(최상위·중첩 객체 모두 키 정렬, `count`/`lambda`는 수치 정규화):
```
{"count":40,"market":"KRW-BTC","operator_id":"op-01","params":{"base_spread_bps":"8","lambda":2,"quote_size":"0.05","skew_factor":"0.01"},"seed":{"base":"1.0","quote":"50000000"},"type":"MARKET_MAKER"}
```
→ `canonical = "POST\n/admin/v1/sim/bots\n" + 위 JCS 문자열`, `query_hash = SHA512(canonical)`. (관리 평면 §3-6도 이 규칙을 그대로 상속하므로 배열/중첩 바디에서 canonical이 확정된다.)

### 3-4. 서버 검증 순서 (게이트웨이)

**인증 경로 분기(v2.11 확정)**: `Authorization: Bearer <JWT>` 수신 시 JWT 헤더 `alg`와 payload `typ`으로 세 경로를 나눈다. 세 경로 모두 통과 후 동일하게 `(user_id, scope)` 컨텍스트를 주입하므로, 이후 주문·조회 핸들러는 인증 경로를 구분하지 않는다.

- `alg=HS256` + `typ="demo_session"` → 데모 세션(§3-7). 대칭 프로세스 키로 서명·`exp`·`scope`만 검증. HMAC canonical·nonce·timestamp 미적용.
- `alg=EdDSA` + `typ="access"` → 회원 세션(§3-8). 공개키로 서명·`exp`·`scope`·`aud` 검증 + `user_id` 주입. HMAC canonical·nonce 미적용. 개인키는 인증 발급자에만 상주하고 검증자는 공개키만 가져 위조 능력이 없다(게시판 평면 침해가 거래 토큰 위조로 전파되지 않음).
- `typ` 미존재(=`access_key` 클레임 존재) → §3 HMAC 경로. 아래 canonical·nonce·timestamp 전 검증을 수행한다.

HMAC 경로(`typ` 미존재)의 검증 순서:

```
1. Authorization 헤더 JWT 파싱 → 실패 401 jwt_verification
2. access_key로 secret 복호화·조회 → HMAC-SHA256 재계산·대조 → 불일치 401 jwt_verification
3. timestamp 검사(단방향, nonce TTL 정합): 유효 recvWindow = min(payload.recvWindow ?? 5000, 60000)ms
   → now-recvWindow ≤ ts ≤ now+clock_skew(기본 1000ms) 밖이면 401 timestamp_out_of_window
   (v2.2: recvWindow는 JWT payload의 **서명 대상 클레임**(§3-3)으로만 전달. 헤더·쿼리 등
    미서명 채널 전달 금지. 공격자가 창을 늘려 재전송하는 리플레이 재개방 차단)
4. nonce 중복 검사(Redis SET, TTL = **해당 요청의 유효 recvWindow** + clock_skew, 허용창 전체 커버)
   → 재사용 401 nonce_used
5. canonical 서명(method+path+params) 재계산·대조 → 불일치 401 invalid_query_payload
6. scope 검사 → 부족 403 out_of_scope
7. rate limit(토큰버킷) 차감 → 초과 429
→ 통과 시 인증 컨텍스트(user_id, scope) 주입
```
nonce TTL 정정: 초안은 TTL=recvWindow(1배)라 허용창 후반부 리플레이 구간이 존재했다. **timestamp 창을 단방향으로 좁히고(now-recvWindow ≤ ts ≤ now+clock_skew) nonce TTL을 그 폭 전체로 설정** → 리플레이 사각지대 제거.

**nonce 저장소 구현 각주(v2.7)**: 4단계의 Redis SET은 다중 노드 구성의 정본 계약이다. 단일 API 노드 전제에서는 동일 TTL 의미론의 인메모리 저장소(NonceStore)를 허용한다. 다중 노드 승격(M2) 시 Redis 교체가 선행 조건이며, 교체 전 다중 노드 기동은 노드 간 nonce 재사용 창을 연다.

**(v2.57 개정, ADR 0034 결정 14와 S5)** 다중 노드에서도 nonce는 노드 메모리에 둔다. 위 각주의 'Redis 교체가 선행 조건'은 마켓별 리더 구성에서 적용하지 않는다(결정 14, AI 기본값, 사용자 확인 필요). 인그레스는 `503`을 받은 요청을 다른 노드로 다시 보내므로 같은 서명 요청이 두 노드에 간다(§2-5-d). 공유 저장소면 그 재시도가 모두 `401 nonce_used`가 된다. 대가는 노드를 넘는 재전송 창이다. 허용창(유효 recvWindow, 최대 60초) 안에 가로챈 서명 요청을 다른 노드로 보내면 그 노드는 nonce를 처음 본다. 효과는 요청의 멱등이 막는다. 주문 생성은 `client_order_id` 멱등(§2-4)으로 기존 주문 반환이고, 단건 취소는 종결 주문의 재취소 멱등이며, 이체는 `Idempotency-Key`다. 멱등이 아닌 것은 일괄 취소로, 창 안에 새로 넣은 주문까지 취소할 수 있다. 소유권 선검사(§2-5-d)는 인증보다 앞이라 소유하지 않는 노드는 이 요청을 인증하지 않는다. 그래서 힌트를 실었거나 바디·쿼리에서 마켓을 찾는 주문 쓰기는 소유 노드가 바뀌는 인계 창 밖에서는 늘 같은 소유 노드에서 인증되고, 그 노드의 nonce 저장소가 재전송을 막는다. 마켓 없는 일괄 취소는 선검사가 마켓을 찾지 못해 어느 노드에서나 인증되고, v2.57부터 받은 노드가 접수하지 않는 파티션의 주문을 소유 노드에 넘겨 실제로 취소한다(§4-B-3). v2.56까지는 P2의 팔로워나 엔진을 끈 노드에 간 이 요청이 모든 취소가 저널 전에 거부돼 효과가 없었으므로, 가로챈 서명 요청을 다른 노드로 다시 보냈을 때의 효과(창 안에 새로 넣은 주문의 취소)는 이 판이 새로 만든다. 이 요청은 인그레스의 힌트 없는 쓰기 경로(1차 고정, 백업은 1차가 빠졌을 때만)로 가므로 공개 진입을 지나는 재전송은 대개 처음과 같은 노드에 닿아 그 노드의 nonce 저장소가 막는다. 노드를 직접 부르거나 1차가 빠진 창에서는 막지 못한다. 막으려면 마켓 없는 일괄 취소의 nonce를 DB에 등록해야 하고 이 판은 하지 않는다(AI 선택, 사용자 확인 필요, S5 설계 11절 R3). 회원·데모 세션은 nonce를 쓰지 않는다(§3-7, §3-8).

### 3-5. 서명 예시 (클라이언트 의사코드)

```python
import time, uuid, hashlib, hmac, base64, json
method, path = "POST", "/api/v1/orders"
params = {"client_order_id":"bot7-2026-0721-0001","market":"KRW-BTC","ord_type":"limit",
          "price":"50000000","side":"bid","volume":"0.01"}
# POST=바디 있는 요청 → §3-3(b): flat key=value 금지. JCS(RFC 8785) 원문 바이트를 서명한다.
import jcs                                                       # RFC 8785 canonicalizer
canonical_payload = jcs.canonicalize(params).decode("utf-8")    # 키 정렬·공백제거·배열 순서보존
canonical = f"{method}\n{path}\n{canonical_payload}"
query_hash = hashlib.sha512(canonical.encode("utf-8")).hexdigest()
payload = {"access_key":ACCESS_KEY,"nonce":str(uuid.uuid4()),
           "timestamp":int(time.time()*1000),"recvWindow":5000,   # 선택(서명 대상). 생략 시 서버 기본 5000ms
           "query_hash":query_hash,"query_hash_alg":"SHA512"}
# HS256 JWT: jwt.encode(payload, SECRET, algorithm="HS256")  → Authorization: Bearer <token>
```

### 3-6. 관리 평면(admin) 인증 · critical 신규 확정

가장 위험한 kill-node/chaos/파라미터 변경 평면의 인증을 거래 평면과 동일 엄밀도로 규정한다.

- **네트워크 격리**: `admin.exchange.local` 별도 호스트/포트, 방화벽으로 운영자 네트워크만 접근. **mTLS 필수**(클라이언트 인증서 검증).
- **서명**: §3의 JWT+canonical+nonce+timestamp를 그대로 재사용하되, `scope=admin` 키 + **별도 `admin_secret`**로 서명. `/admin/v1/**` path가 canonical에 포함되어 거래 서명과 교차 재사용 불가. **중첩 바디(`params`/`seed` 등)를 가진 관리 요청은 §3-3(b) `JCS(body)` canonical 규칙을 상속**하므로 flat 평탄화 불가 문제 없이 서명 대상이 바이트 단위로 확정된다(예시: §3-3 admin 봇 생성).
- **비가역 액션 이중 승인(v2.3 완결 규정, v2.12 config·operators 확장)**: `approval_token` 필수 액션은 다음 **완결 열거**로 확정한다('등'으로 열어두지 않는다): `PATCH /admin/v1/sim/bots/{id}/params`, `POST /admin/v1/config/markets/{symbol}/halt`, `POST /admin/v1/config/markets/{symbol}/resume`, `PATCH /admin/v1/config/markets/{symbol}/fees`, `POST /admin/v1/operators`, `PATCH /admin/v1/operators/{id}/status`, `POST /admin/v1/operators/{id}/roles`, `POST /admin/v1/members/{external_id}/close`(v2.32), 관제센터의 `POST /admin/v1/msp/thresholds/{key}`, `POST /admin/v1/msp/thresholds/changes/{id}/revert`, 경보 등급이 `critical`·`other`이거나 30분을 넘는 `POST /admin/v1/msp/alerts/{fingerprint}/silence`, `POST /admin/v1/msp/ai/mode/auto`, `PUT /admin/v1/msp/ai/limits/{key}`와 조치 종류가 `THRESHOLD`인 `POST /admin/v1/msp/ai/actions/{id}/approve`·`/revert`(v2.52.2 구현, §6-F). **그 외 모든 관리 엔드포인트**(카오스 주입·해제 4종(v2.34), 봇 생성/제거, `sim/start|stop|pause`, `scenarios/{name}/activate`, `PUT /sim/rate`, 공지 발급·갱신·소프트 삭제, 관제센터의 경보 확인, 30분 이하 `warning`·`info` 보류, 보류 해제, 즉시 끄기, AI 즉시 정지와 재개, 계획 작업 창 등록·종료와 나머지 조치 요청 처리(v2.52.2), 조회류)는 `operator_id`만 필수이고 approval_token **불요**다(§6-A 봇 생성 예시와 정합. 전 액션 감사 로그 대상은 동일). 필수 액션은 요청 바디에 `operator_id` + `approval_token` 포함, 누락·불일치·만료 시 `403 admin_forbidden`. §6-A/§6-B/§6-F 표의 **승인 컬럼**이 엔드포인트별 요구 여부의 권위 소스다. 관제센터 AI 자동 모드(ADR 0035, v2.52.1 설계, 미구현)의 허용 목록 조치는 조치별 티켓 없이 실행한다. 자동 켜기 티켓(`ops.ai.auto`)이 그 창의 포괄 승인이고, 허용 목록 밖의 조치와 이 열거의 다른 액션에는 미치지 않는다(§6-F). 임계값을 둔하게 하는 변경과 민감한 방향을 정하지 않은 항목의 변경은 자동 모드에서도 이 포괄 승인에 들지 않고 `ops.threshold.set` 티켓으로만 실행한다(v2.52.6, 정책 T6).
  - **approval_token 수명주기(v2.3)**: operator 가 `POST /admin/v1/approvals`(mTLS + `scope=admin` 서명, 2인 승인 절차)로 발급하며, 발급 시 **`action` + 대상 식별자**(`node`/`bot_id`/`toxic` 등)에 바인딩된다. 토큰은 **단회용**(검증 성공 시 즉시 소각)·**TTL 5분**. 바인딩된 action·대상과 다른 요청에의 사용, 만료·기소각 토큰 재사용은 `403 admin_forbidden`. 정적·재사용 가능한 토큰은 금지된다(동일 토큰으로 반복 kill-node 승인 불가. nonce가 막지 못하는 '새 요청+같은 토큰' 경로 차단). 발급·사용·만료·거부는 모두 `admin_audit_log`에 기록.
  - **operator 신원 바인딩(v2.4, 2인 통제의 검증 수단 확정)**: `operator_id`는 바디 자가신고 값으로 판정하지 않는다. 각 admin `access_key`(및 mTLS 클라이언트 인증서)는 발급 시 정확히 **하나의 `operator_id`에 1:1 등록**되며(`api_keys`에 operator_id 바인딩, §7-1), 서버는 **모든 관리 요청과 approvals 발급 각각에서 인증 주체(access_key / 인증서 CN)로부터 operator_id를 도출**해 바디의 `operator_id`와 대조한다. 불일치 시 `403 admin_forbidden`(바디 필드는 가독·감사 편의용이고 권위는 인증 주체 유래 값이다. `admin_audit_log`에도 인증 주체 유래 operator_id를 기록). **2인 승인 판정**: "요청 operator와 다른 operator"는 자가신고 값이 아니라 **approvals 발급 요청의 인증 주체 ≠ approval_token 사용 요청의 인증 주체**로 서버가 검증한다 → 단일 admin 키 보유자가 발급 시 `op-02`, 사용 시 `op-01`로 바꿔 신고해 혼자 2인 승인을 완성하는 경로를 차단한다(발급 시 토큰에 발급 주체 operator를 함께 바인딩).
  - **바인딩 target·바디 필드 확정표(v2.13, 발급·소각 정합 단일 규칙)**: `POST /admin/v1/approvals`가 발급하는 `target` 문자열과 파괴 액션 엔드포인트가 소각 시 재구성하는 `target`은 아래 표의 형식으로 **바이트 단위 동일**해야 한다. 불일치는 `403 admin_forbidden`(`detail.reason=binding_mismatch`). `target`은 두 계열로 나뉜다. (1) **자원 스코프**: `target`=파괴 대상 자원 식별자. 페이로드 값(수수료·method·params·type)은 바인딩에 넣지 않는다. (2) **신원·권한 전이 스코프**(`operators.status`·`operators.roles`): `target`에 전이 방향·대상 등급까지 포함해 승인된 토큰을 더 위험한 전이(예: `GRANT VIEWER` 승인분을 `GRANT SUPER`로, `ACTIVE` 승인분을 `DISABLED`로)로 재사용하는 경로를 차단한다. 전이 스코프의 상태·역할·방향 토큰은 모두 **대문자**로 정규화해 바인딩한다. 모든 파괴 액션 바디는 `operator_id`(수행자, 인증 주체 유래 값과 대조)와 `approval_token`을 공통으로 싣는다.

    | action | 엔드포인트 | 파괴 액션 바디(공통 필드 외) | 바인딩 `target` 형식 | 예 |
    |---|---|---|---|---|
    | `chaos.kill_node` | `POST /admin/v1/chaos/kill-node` | `node`, `method?`, `expect_failover?` | `node` 값 | `chaos-api-1` |
    | `chaos.network` | `POST /admin/v1/chaos/network` | `name`, `type?` | `name` 값(toxic 이름) | `e1-toxic` |
    | `chaos.app` | `POST /admin/v1/chaos/app` | `assault` | `assault` 값 | `e8-assault` |
    | `chaos.network.clear` | `DELETE /admin/v1/chaos/network/{toxic}` | (경로만) | `{toxic}` 경로값 | `e1-toxic` |
    | `config.market.halt` | `POST /admin/v1/config/markets/{symbol}/halt` | (경로만) | `{symbol}` 경로값 | `KRW-BTC` |
    | `config.market.resume` | `POST /admin/v1/config/markets/{symbol}/resume` | (경로만) | `{symbol}` 경로값 | `KRW-BTC` |
    | `config.market.fees` | `PATCH /admin/v1/config/markets/{symbol}/fees` | `maker_fee?`, `taker_fee?` | `{symbol}` 경로값 | `KRW-BTC` |
    | `sim.bots.params` | `PATCH /admin/v1/sim/bots/{id}/params` | `params` | `{id}` 경로값(bot_id) | `bot-17` |
    | `operators.create` | `POST /admin/v1/operators` | `new_operator_id`, `display_name` | `new_operator_id` 값 | `op-07` |
    | `operators.status` | `PATCH /admin/v1/operators/{id}/status` | `status`(`ACTIVE\|DISABLED`) | `{id}:{STATUS}` | `op-07:DISABLED` |
    | `operators.roles` | `POST /admin/v1/operators/{id}/roles` | `action`(`GRANT\|REVOKE`), `role` | `{id}:{GRANT\|REVOKE}:{ROLE}` | `op-07:GRANT:SUPER` |
    | `ops.threshold.set` | `POST /admin/v1/msp/thresholds/{key}`, 같은 절의 되돌리기, `THRESHOLD` 조치 요청의 승인·되돌리기(v2.52.2 구현) | `value`, `reason` | `{key}:{value}`. `value`는 끝의 0을 지운 평문 십진 | `host.disk_free_alert:0.2` |
    | `ops.alert.silence` | `POST /admin/v1/msp/alerts/{fingerprint}/silence`(등급 `critical`·`other` 또는 30분 초과, v2.52.1 구현) | `minutes`(1~480 정수), `reason` | `{fingerprint}:{minutes}` | `5f3c2a9e0b1d4c77:120` |
    | `ops.ai.auto` | `POST /admin/v1/msp/ai/mode/auto`(v2.52.2 구현) | `hours`(1·2·4·8), `reason` | `AUTO:{hours}` | `AUTO:4` |
    | `ops.ai.limits` | `PUT /admin/v1/msp/ai/limits/{key}`(v2.52.2 구현) | `value`(정수, 하드 범위 안), `reason` | `{key}:{value}` | `auto_cap:3` |

    - `operators.create`의 바디 `operator_id`는 **수행자**(인증 주체 유래 값과 대조하는 공통 필드)이고 생성 대상 신원은 별도 `new_operator_id`로 싣는다. 바디 `operator_id`의 의미를 전 파괴 액션에서 "수행자"로 통일하기 위함이다(대상 신원을 `operator_id`에 겹쳐 싣지 않는다). 생성 시 역할은 부여하지 않는다. 역할은 `operators.roles` 별도 승인 경로로만 부여한다.
    - `chaos.network`가 만든 toxic의 `name`과 `chaos.network.clear`의 `{toxic}`는 같은 문자열이어야 해제가 성립한다(주입 시 발급한 이름을 해제 target으로 재사용).
    - `ops.threshold.set`, `ops.alert.silence`, `ops.ai.auto`, `ops.ai.limits`는 자원 식별자만이 아니라 값(새 임계값, 보류 분, 자동 모드 시간, 새 한도)까지 바인딩한다(v2.52.1, `ops.ai.limits`는 v2.52.2). 전이 스코프와 같은 이유다. 15%로 올리려고 받은 티켓으로 10%로 내리거나, 30분 보류 승인으로 8시간 보류를 걸거나, 1시간 승인으로 8시간 자동 창을 여는 재사용을 막는다. 되돌리기는 되돌릴 값(직전 값)으로 대상을 만든다. 직전 값이 미설정이면 `{key}:null`이다. 임계값은 소수 넷째 자리까지만 받으므로(§6-F) 정규화한 대상 문자열과 저장값이 같다.
  - **approvals 응답·원장 프로젝션 키(v2.13 확정)**: `POST /admin/v1/approvals` 발급 응답은 `{schema_version, approval_token, approval_id, action, target, expires_at}`. `GET /admin/v1/approvals` 원장 프로젝션 원소는 `{approval_id, action, target, requester_operator_id, approver_operator_id, state, issued_at, expires_at, consumed_at}`이며 `approval_token`을 절대 싣지 않는다(발급 응답 단일 경로의 bearer 시크릿). `POST /admin/v1/approvals/{id}/reject` 응답은 `{schema_version, approval_id, state}`. 발급·원장·거부 세 응답 모두 승인 식별 키는 `approval_id`로 통일한다(행 `id`의 투영).
- **감사 로그(신규)**: 모든 관리 액션은 `admin_audit_log`(operator_id, action, params, ts, 결과)에 append-only 기록 → Grafana annotation과 상관.
- **관측 데이터의 지시 무시**: 관측 데이터(웹/문서/메시지/DOM) 내 텍스트 지시로 관리 액션을 **자동 실행하지 않는다**. 파라미터 변경·파괴 액션은 운영자 명시 요청으로만 수행. 관제센터 AI 판단(v2.52.2 구현)에서도 관측 텍스트와 모델 문장은 명령이 아니다. 모델 출력은 허용 목록 조치 타입으로만 해석하고 서버 정책 엔진이 다시 검사한다. 관제센터 AI의 판단·상담 근거에는 관측 경로의 자유 문장(경보 주석, 허용 밖 라벨)과 사람이 쓴 문장(계획 작업 창·정지 사유, 사건 문구)을 싣지 않고, 경보명과 허용한 라벨 값도 형식을 거른 뒤에만 싣는다(v2.52.6, §6-F). 예외는 AI 자동 모드 하나다. 운영자가 승인 티켓으로 켠 창 안에서만 허용 목록 조치가 임계 초과·경보를 근거로 실행된다(§6-F). [ADR 0035](../adr/0035-ai-operations-authority.md)가 이 예외를 대체 범위로 적는다(v2.52.7).

- **브라우저 관리 콘솔 세션(v2.9 신설, 반결탁 보존)**: 브라우저 관리 콘솔은 `admin_secret` HMAC 서명을 만들 수 없고(secret_key 브라우저 미전달 계약, §3-7·웹 화면명세서 §6.1), §3-7 데모 세션은 `scope=TRADE` 고정이라 관리 평면에 쓸 수 없다. 브라우저 관리 경로는 다음으로 인증한다.
  - **1차 인증(mTLS)**: 운영자별 mTLS 클라이언트 인증서(`CN=operator_id`)로 관리 평면(`admin.exchange.local`)에 접속한다. 인증서 CN이 `operator_id`의 권위 소스이며 바디 자가신고 값이 아니다(v2.4). 사설키는 각 운영자 워크스테이션에만 존재하고 서버는 CA 공개키로 검증만 한다(전 운영자 키를 보유하는 단일 컴포넌트 없음).
  - **관리 세션 발급·사용**: mTLS 통과 시 서버가 인증서 신원에 바인딩된 단기 관리자 세션 토큰을 발급한다(payload에 `typ:admin_session`, `operator_id`는 인증서 CN 유래, `scope:admin`, 짧은 `exp`). 세션은 mTLS 신원에서 유래하고 운영자가 신원을 자가신고하지 않는다. 관리 평면 전용이며 거래 평면·데모 세션과 자격을 공유하지 않는다. 후속 요청은 세션 토큰으로 인증하되, HMAC canonical·`query_hash`·nonce는 적용하지 않고 서버가 세션 서명·`exp`·`scope`와 mTLS 인증 주체 일치만 검증한다(§3-7 데모 세션 검증과 동형, 단 mTLS 게이트·`scope=admin`). 만료·서명 불일치·mTLS 실패는 `401 jwt_verification`.
  - **조회 vs 파괴 액션**: 조회류는 단독 운영자 세션으로 가능하다. approval_token 필수 액션(위 완결 열거)은 요청 운영자와 **다른** 운영자가 자신의 mTLS 세션으로 `POST /admin/v1/approvals`를 호출해 발급한 토큰을 동반해야 한다. 발급 요청과 대상 액션 요청은 각각 서로 다른 운영자의 mTLS 인증 주체에서 `operator_id`를 도출하며, 서버는 발급 주체 ≠ 사용 주체를 인증서 CN 기준으로 검증한다(반결탁: 서로 다른 운영자 사설키 점유로만 요청·승인이 성립, 단일 컴포넌트가 전 키 미보유, operator_id는 인증 주체 유래).
  - **경계**: 봇·외부 머신 클라이언트는 기존 mTLS + `admin_secret` HMAC 서명 경로(위 서명 규정)를 그대로 사용한다. 브라우저 관리 세션은 브라우저 콘솔 전용 경로이며 HMAC 서명 경로의 검증 순서·에러 계약을 바꾸지 않는다. 데모 mTLS 인증서 발급 절차는 관리자 설계 §1-6.

**WebSocket 비공개 채널 인증**: STOMP `CONNECT` 프레임 `Authorization` 헤더에 동일 JWT 첨부(§5-6). §3-7 데모 세션 토큰도 동일 헤더로 첨부할 수 있다(v2.7). **CONNECT용 JWT의 canonical(v2.2 확정)**: `METHOD="GET"`, `PATH="/ws/v1"`(핸드셰이크 경로), `CANONICAL_PAYLOAD=""` → `canonical = "GET\n/ws/v1\n"`. 서버는 §3-4 5단계에서 이 문자열로 `query_hash`를 재계산·대조한다. **admin 텔레메트리 WS**(`/admin/v1/ws/sim/telemetry`, §6-A)도 동일 프로토콜(STOMP)·동일 규칙을 적용하되 `canonical = "GET\n/admin/v1/ws/sim/telemetry\n"` + `scope=admin` 키(`admin_secret`)로 서명한다.

### 3-7. 데모 세션 인증 (브라우저 데모 전용, v2.7 신설)

브라우저 데모 화면은 §3의 HMAC 서명을 사용할 수 없다. 서명에는 `secret_key`가 필요한데, 서버 서명 비밀은 브라우저에 절대 전달하지 않는다(웹 화면명세서 §6.1 계약). 브라우저 경로 전용으로 서버 발급 단기 세션 토큰을 신설한다.

- **발급**: `POST /api/v1/demo/session` (무인증, 바디 없음). rate limit 강함: IP당 10회/60초, 초과 시 `429 too_many_requests`.
- **응답(200)**:
```json
{ "schema_version":"1.0",
  "access_token":"<JWT>",
  "token_type":"Bearer",
  "expires_in":900,
  "user":{"external_id":"9f2b7a4e-3d1c-4b0e-9a77-5c2f8e1d6a3b","email":"demo@exchange.local"} }
```
- **토큰**: JWT HS256. payload = `{ "typ":"demo_session", "sub":"<user external_id>", "user_id":<BIGINT>, "scope":"TRADE", "iat":<epoch s>, "exp":<epoch s, iat+900> }`. 서명 키는 서버 프로세스 한정 세션 키다(재기동 시 전 세션 무효. 만료 15분과 결합해 데모 허용 범위).
- **사용**: 보호 경로에 `Authorization: Bearer <access_token>`만으로 인증한다. §3-3~§3-4의 HMAC canonical·`query_hash`·nonce·timestamp 검증은 **적용하지 않는다**. 서버는 `typ=demo_session` 판별 후 서명·`exp`·scope만 검증한다. 만료·서명 불일치는 `401 jwt_verification`.
- **범위 제한**: 데모 세션은 **시드된 데모 계정에 한정**해 발급된다. 임의 사용자 지정 발급은 없다(요청 파라미터 없음). scope는 `TRADE` 고정이며 관리 평면(§3-6)에는 사용 불가.
- **읽기 전용(v2.12 개정)**: 데모 세션은 조회만 가능하다. `/api/v1/` 하위의 쓰기 메서드(GET·HEAD·OPTIONS 외)는 `403 member_session_required`로 거부한다. 판정은 경로 목록이 아니라 메서드다. 경로 목록으로 막으면 새 쓰기 엔드포인트가 목록에 빠진 채 열리는데, 이 경계는 자금 이동이라 실패 시 닫히는 쪽이어야 한다. 발급 계정이 공용 1개라 무가입 쓰기를 허용하면 한 방문자의 주문 결과가 다음 방문자의 첫 화면이 된다. scope 클레임이 `TRADE`인 것은 유지한다(scope는 인증 경로가 아니라 권한 어휘라, 회원 승격 시 같은 클레임이 그대로 쓰기 권한이 된다). 봇·외부 클라이언트(§3 HMAC)와 회원 세션(§3-8)은 영향이 없다.
- **경계**: 봇·외부 클라이언트는 기존 §3 서명 규약을 그대로 사용한다. 데모 세션은 브라우저 데모 전용 경로이며, §3 서명 경로의 검증 순서·에러 계약은 본 절의 영향을 받지 않는다.

### 3-8. 회원 세션 인증 (가입한 실사용자, v2.11 신설)

세 번째 인증 경로다. 상세 정본은 `회원_설계.md`. 데모 세션(§3-7)과 병존하며 상호 대체가 아니다. 웹 최초 진입은 데모 세션으로 열람하고, 주문·입출금이 필요하면 회원 가입으로 승격한다(데모 세션은 읽기 전용, §3-7).

- **계정 범위**: 회원별 개인 계정(`users.kind='HUMAN'`). 자기 `user_id`의 주문·계좌·체결·원장만 접근(§3-3 조회 엔드포인트 재사용). 게이트웨이가 토큰 `user_id`를 주입하고 핸들러는 바디·경로의 사용자 지정을 무시한다.
- **서명(EdDSA/Ed25519, 비대칭)**: 액세스 토큰 payload = `{ "typ":"access", "alg":"EdDSA", "sub":"<external_id>", "user_id":<BIGINT>, "scope":"TRADE", "kind":"HUMAN", "aud":["exchange","community"], "iat":<epoch s>, "exp":<iat+900> }`. 개인키는 인증 발급자 프로세스에만 상주(시크릿 관리소 주입), 공개키는 게이트웨이·community-api에 배포. 만료 15분. M2는 단일 키라 헤더에 `kid`를 넣지 않는다(키 회전은 다중 키 도입 후속 단위).
- **리프레시**: 불투명 256비트 랜덤. httpOnly·Secure·SameSite=Strict 쿠키 `rt`(경로 `/api/v1/auth`)로만 전달. DB에는 SHA-256 해시만 저장. TTL 14일. 회전(단회용 소각)+재사용 탐지: 소각·폐기 토큰 재제출 시 family 전체 폐기 + `401 refresh_reuse`.

**엔드포인트**. 브라우저 전용이라 HMAC canonical·nonce를 요구하지 않는다. `/api/v1/auth/*`는 §3-4 인증 필터 제외이며 진입 rate limit을 자체 적용한다.

| 기능 | 메서드·경로 | 인증 | rate limit |
|---|---|---|---|
| 가입 | `POST /api/v1/auth/signup` | 없음 | IP 5회/60s → `429 too_many_requests` |
| 로그인 | `POST /api/v1/auth/login` | 없음 | IP·이메일 각 10회/60s + 계정 백오프(`423 account_locked`) |
| 토큰 갱신 | `POST /api/v1/auth/refresh` | 쿠키 `rt` | IP 30회/60s |
| 로그아웃 | `POST /api/v1/auth/logout` | 쿠키 `rt` | 표준 |
| 내 정보 | `GET /api/v1/me` | 회원 세션 | 계좌 조회 버킷 |

```json
// POST /api/v1/auth/signup  { "email":"trader@demo.local", "password":"demopass1", "display_name":"데모트레이더" }
// 201 (+ Set-Cookie: rt=...; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth)
{ "schema_version":"1.0", "access_token":"<JWT EdDSA>", "token_type":"Bearer", "expires_in":900,
  "user":{ "external_id":"9f2b7a4e-...", "email":"trader@demo.local", "display_name":"데모트레이더", "kind":"HUMAN" } }
```

- **로그인**: 가입과 동일 스키마(`200`). `invalid_credentials`는 미가입 이메일과 오비밀번호를 동일 코드·동일 메시지·유사 지연으로 처리(계정 존재 여부 비노출).
- **갱신**: 쿠키 `rt` 제출 → 새 `access_token` + 회전된 `rt` 쿠키. 만료·부재는 `401 session_expired`.
- **로그아웃**: 제출된 `rt`의 family 폐기 후 `204`. 인증은 쿠키 `rt` 기반이다(필터 제외 경로라 회원 세션 principal 미주입).
- **내 정보**: `user` 객체 반환. 회원 세션 전용이라 데모·API 키 주체는 `403 out_of_scope`(EdDSA `kind=MEMBER`만 통과).
- **가입 자금**: 초기 KRW 1억을 기존 원장 단일 경로 `post_ledger_transaction`(GENESIS)로 발급. `business_event_id`는 시드 GENESIS와 서로소인 예약 네임스페이스(DB 설계 §3.3)에서 배정해 멱등을 물리 보증한다.

---

<a name="4"></a>
## 4. REST API 전체 명세

> 모든 응답은 `schema_version:"1.0"`를 **실제로 포함**한다(§2-2 의무). 리스트는 §2-2 엔벌로프의 top-level `schema_version`을 반드시 표기하며(기준 예시 §4-A-1), 이하 §4-A-3/4/5 등 일부 예시는 지면상 표기만 생략할 뿐 계약상 항상 포함이다.

### 4-A. 공개 시장 데이터 (인증 불필요)

| 기능 | 메서드·경로 | weight | 주요 파라미터 |
|---|---|---|---|
| 마켓 목록 | `GET /api/v1/markets` | 2 | 없음 |
| 호가 | `GET /api/v1/orderbook` | 1 | `market`, `level` |
| 체결 이력 | `GET /api/v1/trades` | 2 | `market`, `count`(≤200), `cursor`\|`to` |
| 티커 | `GET /api/v1/ticker` | 2 | `markets`(콤마, 마켓 수와 무관하게 요청 1건 계상) |
| 캔들 | `GET /api/v1/candles/{unit}` | 2 | `market`, `count`(≤200), `cursor`\|`to` |
| 거래소 구성 | `GET /api/v1/exchange-config` | 1 | 없음 |
| 거래 상태(v2.60) | `GET /api/v1/status` | 없음(자기 IP 스로틀, §4-A-7) | 없음 |

#### 4-A-1. `GET /api/v1/markets`
```json
{ "schema_version":"1.0",
  "data":[
  { "market":"KRW-BTC","korean_name":"비트코인","base":"BTC","quote":"KRW",
    "state":"ACTIVE","tick_size":"1000","min_order_amount":"5000",
    "max_order_amount":"1000000000","qty_scale":8,"price_scale":0,
    "maker_fee":"0.0005","taker_fee":"0.0005" } ],
  "pagination":{"next_cursor":null,"has_more":false} }
```
> 이 예시는 §2-2 의무대로 **top-level `schema_version`을 실제 표기**한 기준 리스트 예시다(§4-A-3/4/5도 동일 규약, 표기만 생략).
> **수수료 권위 소스(확정)**: 수수료는 **마켓별 고정**이 권위 소스다. `markets`의 `maker_fee`/`taker_fee`와 `orders/chance`(§4-B-5)의 값은 **동일 마켓 기준으로 일치**해야 하며, 체결 시 원장(`my/trades.fee`)도 이 마켓 수수료로 계산되며, 체결 단위 산출·반올림은 §4-B-5의 양자화·잔차 규약(자산 scale 선양자화, 마지막 분개 잔차 흡수, 최종 체결의 실제 잔여 락 소비)을 따른다. 계정 등급별 차등은 v1 범위 밖(전 계정 동일).

#### 4-A-2. `GET /api/v1/orderbook?market=KRW-BTC&level=0` (단건 객체, epoch 포함)
```json
{ "schema_version":"1.0","market":"KRW-BTC",
  "timestamp":"2026-07-21T09:00:00.123Z",
  "epoch":42,                            // 리더 세대. 변경 시 클라이언트 강제 재동기화
  "seq":150042,                          // 채널(KRW-BTC×orderbook) 단위 단조 증가
  "total_ask_size":"12.5","total_bid_size":"9.8",
  "orderbook_units":[
    {"ask_price":"50010000","ask_size":"0.5","bid_price":"50000000","bid_size":"0.7"},
    {"ask_price":"50020000","ask_size":"1.2","bid_price":"49990000","bid_size":"0.4"} ] }
```
> **seq·epoch를 내는 노드(v2.58, ADR 0034 S6)**: `seq`와 `epoch`는 그 마켓을 쥔 리더의 도메인이다. 그 마켓 파티션의 접수 게이트를 연 노드는 자기 채널 카운터와 그 기간의 epoch를 내고, 호가는 그 기간에 만든 엔진 호가 사본(없으면 DB 집계)이다. 쥐지 않은 노드는 자기 카운터를 내지 않는다. 중계 모드(§5-1)에서 다음 셋을 모두 채우면 미러(소유 노드가 마지막으로 게시한 호가와 그 `seq`·`epoch`)를 낸다. 중계 구독이 붙어 있다. 그 마켓 파티션 소유 노드의 하트비트(§5-1)를 지금 구독에서 최근 5초 동안 3개 이상 받았고, 가장 최근 하트비트가 400ms(설정 `exchange.ws.relay.mirror-max-silence`) 안에 왔다. 미러 epoch가 그 마켓 리스의 현재 epoch와 같고, 미러 `seq`가 마지막 하트비트가 알린 그 마켓의 마지막 게시 `seq` 이상이다. 그 밖에는 DB 집계와 `seq: 0`, 리스의 현재 epoch를 낸다. 소유 노드의 발행이 막히거나(브로커 메모리 경보 등) 게시가 빠져 미러가 뒤처지면 구독 연결이 멀쩡해도 미러를 쓰지 않는다. 발행이 조용히 멈추면 5초에 3개 조건은 약 3초 더 참이므로, 그동안 멈춘 미러를 내지 않도록 가장 최근 하트비트의 나이를 함께 본다. `seq: 0`은 이 스냅샷이 seq 도메인의 기준점이 아니라는 표시다(카운터는 1부터 센다). 클라이언트는 상태만 반영하고 다음 realtime을 기준점으로 삼는다(§5-2). 종전에는 쥐지 않은 노드가 자기 카운터와, 강등된 노드면 옛 기간의 엔진 호가를 냈다. 구독 즉시 스냅샷(§5-2)도 같은 선택을 쓴다.

#### 4-A-3. `GET /api/v1/trades?market=KRW-BTC&count=50` (opaque 커서)
```json
{ "data":[
    {"trade_id":"01J8ZT...","market":"KRW-BTC","price":"50005000","volume":"0.01",
     "taker_side":"bid","liquidity_flag_taker":"TAKER",
     "timestamp":"2026-07-21T09:00:01.001Z","seq":72310} ],
  "pagination":{"next_cursor":"eyJ0cyI6IjIwMjYtMDctMjFUMDk6MDA6MDEuMDAxWiIsImlkIjoiMDFKOFpUIn0","has_more":true} }
```
> `trade_id`는 무작위 발급 값이 아니라 event_journal `event_seq`의 결정론 파생 값이고(§7-1, v2.6), `timestamp`(체결 시각 `executed_at`)는 원인 command의 `engine_ts`에서 파생한다. 저널 재생으로 재구축해도 두 값 모두 원본과 동일하다.

#### 4-A-4. `GET /api/v1/ticker?markets=KRW-BTC,KRW-ETH`
```json
{ "data":[
  { "market":"KRW-BTC","trade_price":"50005000","change":"RISE",
    "change_rate":"0.0123","change_price":"605000",
    "high_24h":"51000000","low_24h":"49000000",
    "acc_trade_volume_24h":"1234.56","acc_trade_price_24h":"61728395000",
    "best_bid":"50000000","best_ask":"50010000","seq":33120,"epoch":42,
    "timestamp":"2026-07-21T09:00:01.000Z" } ],
  "pagination":{"next_cursor":null,"has_more":false} }
```
> **epoch(v2.58, ADR 0034 S6)**: 원소마다 `epoch`가 있다. 서버는 이전부터 냈고 이 문서가 빠뜨렸다. `seq`·`epoch`를 내는 노드의 규칙은 §4-A-2와 같다. 그 마켓을 쥐지 않은 노드는 `seq`·`epoch`만 소유 노드의 마지막 ticker 게시 번호(§4-A-2의 조건을 채운 미러)에서 가져오고, 나머지 값(마지막 체결가, 등락, 24시간 고가·저가·누적)은 쥔 노드와 같은 방식으로 요청 때 DB에서 계산한다. 24시간 값과 등락은 체결 없이도 시간이 지나면 바뀌므로 게시 본문을 그대로 내지 않는다. `best_bid`·`best_ask`는 조건을 채운 호가 미러에서, 없으면 DB 집계에서 가져온다. 미러를 쓸 수 없으면 `seq: 0`이다. 클라이언트는 이 epoch로 늦은 스냅샷을 거른다(§5-2).
> **부호·크기 분리(v2.8)**: `change`(RISE/FALL/EVEN)가 부호 권위이고 `change_rate`·`change_price`는 **절대값**이다(색 단독 표현 금지 규칙의 데이터 근거). `change_price`는 24h 전 종가 대비 절대 금액을 quote scale로 HALF_UP, `change_rate`는 그 비율(절대)이다. `acc_trade_price_24h`는 24h 거래대금(Σ 체결가×수량)을 quote scale로 HALF_UP, `acc_trade_volume_24h`는 24h 체결 수량 합이다. 기준가(24h 전 종가)는 `executed_at ≤ now-24h`의 최근 체결가이며, 없으면 최초 체결가로 폴백한다. 24h 윈도에 체결이 없으면 집계값은 `null`/`"0"`이고 `change`는 `EVEN`이다.
> **null 경계(v2.14)**: 체결 이력이 전혀 없는 마켓은 `trade_price`가 `null`이다. `high_24h`·`low_24h`는 24h 윈도 무체결 시, `best_bid`·`best_ask`는 해당 사이드 호가 공백 시 `null`. `change`·`change_rate`·`change_price`·`acc_trade_*`는 이때도 `EVEN`·`"0"`으로 non-null 유지. WS ticker(§5-4)도 동일 경계다.

#### 4-A-5. `GET /api/v1/candles/{unit}` · unit ∈ `1s,1m,3m,5m,15m,30m,60m,240m,1d,1w`
```json
{ "data":[
  { "market":"KRW-BTC","candle_time":"2026-07-21T09:00:00.000Z",
    "open":"50000000","high":"50020000","low":"49990000","close":"50005000",
    "volume":"12.34","trade_count":345,"seq":8801 } ],
  "pagination":{"next_cursor":"eyJ0cyI6...","has_more":true} }
```
> **데이터 소스(v2.8)**: `candles` 테이블은 DB 설계상 비정규화 trades 파생 프로젝션이며 V-로드맵이다(DB 설계 §7). V1·데모는 별도 테이블 없이 **trades를 unit 구간으로 실시간 집계**해 응답한다(재구축 가능, 저장 없음). 구간 경계는 epoch 정렬(`floor(epoch/sec)*sec`)이고 `1d`는 UTC 자정, `1w`는 epoch 주(목요일 기준) 정렬이다. `open`/`close`는 구간 내 (created_at,id) 정렬 최초·최종 체결가, `high`/`low`는 구간 최대·최소가, `volume`은 수량 합, `trade_count`는 체결 수, `seq`는 구간 내 최대 event_seq다(구간 간 단조 증가, §5-2 candle seq 도메인 = market×unit). 버킷팅·정렬 축은 `created_at`이다. trades에서 `created_at = executed_at`(둘 다 원인 command의 engine_ts 파생, DB `now()` 미사용)이 불변식이라 축을 바꿔도 결과가 불변이고, 파티션 키·인덱스가 `created_at`이라 이 축으로 집계·프루닝한다. 체결이 없는 구간은 응답에 포함하지 않는다(sparse, 클라이언트가 갭 처리). 정렬은 `candle_time` DESC, 커서 id 축은 `(candle_time, market)` 자연키(§2-6 U5)다.

#### 4-A-6. `GET /api/v1/exchange-config` (v2.10)
트레이딩 UI(공개 오리진)가 읽는 표면 구성 조회다. `/markets`와 동급 무인증 공개 API이며, system_config의 UI 안전 키 화이트리스트만 노출한다(봇_방향성 §5-4, 체결_시각효과 §6 정본 반영).
```json
// GET /api/v1/exchange-config → 200
{ "schema_version":"1.0", "trade_flash":"normal" }
```
> **노출 경계**: 화이트리스트 전용이다. 운영·보안 config 키는 노출하지 않는다(system_config를 통째로 열지 않는다). 효과 강도(`trade_flash ∈ off|subtle|normal|strong|auto`)는 cosmetic 값이며 관리 평면 `PUT /admin/v1/sim/effects`(§6-A)가 쓰기 권위다. `auto`는 UI가 자체 관측 체결률로 강도를 파생하라는 지시값이고 백엔드는 열거값만 저장한다(봇_방향성 §5-3). 값 변경은 폴링·재조회로 반영한다(실시간 push 아님).

#### 4-A-7. `GET /api/v1/status` (v2.60 신설, ADR 0036)

거래를 지금 받는지 알리는 무인증 공개 조회다. 노드 메모리의 쓰기 가능 상태(§2-5-e)만 읽고 DB에 닿지 않는다. 계약은 코드보다 먼저 적었고, 코드는 작업 브랜치 `claude/patroni`의 `api/system/StatusController`다.

```json
// GET /api/v1/status → 200
{ "schema_version":"1.0",
  "trading_suspended":true,
  "trading_state":"SUSPENDED",
  "reason":"replication_unavailable",
  "since":1790000000000,
  "checked_at":1790000001234,
  "poll_after_seconds":5 }
```

> **필드**: `trading_state`는 `ACTIVE`·`SUSPENDED`이고 `trading_suspended`는 그 불리언이다. 탐지기의 SUSPENDED와 UNREACHABLE은 모두 공개로는 `SUSPENDED`이고 `reason`으로 가른다. `reason`은 `replication_unavailable`(DB 복제 대기 서버가 없어 쓰기 불가), `database_unavailable`(DB 전환 중이거나 이 노드가 DB에 연결하지 못함), ACTIVE면 `null`이다. `since`는 이 노드가 중단을 판정한 시각(§2-1 밀리초 UTC), ACTIVE면 `null`이다. `checked_at`은 마지막 탐지 결과의 시각이고, `poll_after_seconds`는 다음 조회까지의 권장 간격이다. 탐지를 끈 노드는 늘 `ACTIVE`·`null`이다.
> **노출 경계**: 노드·DB 이름과 주소, 복제 지연 수치는 싣지 않는다(관리자 설계 §2-3의 공개 범위). 운영 상세는 관리 평면과 관측이 맡는다.
> **요청 제한과 경로**: 무인증이지만 `PublicMarketPaths`에 넣지 않고 공지 조회(§4-F)처럼 자기 IP 스로틀(60초에 120회)을 든다. DB에 닿지 않으므로 노드 수용 제한(§2-5-a)의 버킷에 넣지 않는다. 응답은 `Cache-Control: no-store`다. 인그레스에서는 쓰기 업스트림(리더 우선)으로 가서 쓰기를 판정하는 노드의 관점을 보인다. 조회 역할 노드는 이 경로를 서빙하지 않는다.
> **클라이언트 규약**: 화면은 열 때, `poll_after_seconds`마다, 쓰기가 `423`을 받았을 때, WebSocket이 다시 붙었을 때 읽는다. 응답이 5xx나 시간 초과면 직전 상태를 유지하고 다시 읽는다. `trading_suspended=true`인 동안 주문·취소 버튼을 끄고 조회는 계속한다. 재개 뒤 자동 재전송은 하지 않는다(Patroni 설계 6-2).

### 4-B. 주문 (인증 필요)

| 기능 | 메서드·경로 | weight | 인증 |
|---|---|---|---|
| 주문 생성 | `POST /api/v1/orders` | 5 | trade |
| 주문 취소(id) | `DELETE /api/v1/orders/{id}` | 5 | trade |
| 주문 취소(client) | `DELETE /api/v1/orders/by-client/{client_order_id}` | 5 | trade |
| **일괄 취소** | `DELETE /api/v1/orders` (`market` 선택) | 10 | trade |
| **일괄 주문** | `POST /api/v1/orders/batch` (≤20건) | 5×n | trade |
| 개별 주문 조회 | `GET /api/v1/orders/{id}` | 2 | read |
| client 조회 | `GET /api/v1/orders/by-client/{client_order_id}` | 2 | read |
| 미체결 목록 | `GET /api/v1/orders/open` | 2 | read |
| 체결완료 목록 | `GET /api/v1/orders/closed` | 2 | read |
| 주문 가능 정보 | `GET /api/v1/orders/chance` | 2 | read |

#### 4-B-1. `POST /api/v1/orders` · 주문 생성

**요청 필드**
| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `market` | string | 필수 | `KRW-BTC` |
| `side` | enum | 필수 | `bid`(매수)/`ask`(매도) |
| `ord_type` | enum | 필수 | `limit`/`market`/`best` |
| `price` | string | 조건부 | limit·시장가매수(notional) 필수 |
| `volume` | string | 조건부 | limit·시장가매도 필수 |
| `time_in_force` | enum | 선택 | `GTC`(기본)/`IOC`/`FOK`/`POST_ONLY` |
| `client_order_id` | string(1~36) | **필수** | 멱등키 `[A-Za-z0-9_-]`. 미제공 시 예외 없이 `400 client_order_id_required`(§2-4). 서버 생성 fallback 없음. 재시도 시 클라이언트가 동일 키 재사용 |

**조건부 규칙(v2.6 상호배타 확정)**: `limit`은 `price`+`volume` 필수. `market`+`bid`(시장가 매수)는 `price`(금액, notional) 필수이며 `volume` 동시 제공 금지(둘은 상호배타). `market`+`ask`는 `volume` 필수이며 `price` 제공 금지. `best`는 `side=ask`(매도) 전용이며 `volume` 필수 + `time_in_force`∈{IOC,FOK}(매수 best는 정적 예약 산식이 없어 `400 best_bid_unsupported`로 거부). `time_in_force=POST_ONLY`는 `ord_type=limit` 전용이며 `market`·`best`와 배타. notional 지정(`market`+`bid`의 `price`)은 non-MARKET 주문에서 금지된다(limit의 `price`는 지정가이며 notional이 아니다). 조합 위반은 전부 `400 invalid_parameter` + `detail.constraint`(위반 규칙명)로 거부한다. 이 규칙은 DB 계약의 orders CHECK(MARKET과 POST_ONLY 배타, MARKET BUY의 수량·notional 상호배타, non-MARKET의 notional 금지)와 1:1 대응한다.
**시장가 매수 정책 확정**: `market`+`bid`는 **notional(금액) 기준**. 응답에 notional 전용 필드 노출(아래).

```json
// 요청
POST /api/v1/orders
{ "market":"KRW-BTC","side":"bid","ord_type":"limit",
  "price":"50000000","volume":"0.01",
  "time_in_force":"GTC","client_order_id":"bot7-2026-0721-0001" }

// 201
{ "schema_version":"1.0",
  "id":"9f2b7a4e-3d1c-4b0e-9a77-5c2f8e1d6a3b","client_order_id":"bot7-2026-0721-0001",
  "market":"KRW-BTC","side":"bid","ord_type":"limit","status":"OPEN",
  "price":"50000000","volume":"0.01","remaining_volume":"0.01",
  "executed_volume":"0","executed_funds":"0","remaining_notional":null,
  "paid_fee":"0","trades_count":0,
  "created_at":"2026-07-21T09:00:00.500Z",
  "epoch":42,"seq":88123600 }   // v2.2: 이 seq는 command_journal seq(저널 도메인, §7-1. §6-C `/admin/v1/ha/status` 실제 방출 `command_watermarks[]` 의 그 마켓 `command_watermark` 와 동일 축(v2.50), 카운터 행 DB 원자 채번 v2.6. 채번 행과 결번 없는 범위는 §7-1 command_journal 행). WS 채널 seq(§5-2)와는 별개 도메인
```

**id·시각·seq 결정론(v2.6, id 표기 v2.7 정정)**: 응답 `id`는 게이트웨이가 명령 접수 전 사전 발급해 command_journal에 박제한 **UUID**다(구현 정본 §7-1. 이전 판의 ULID 표기는 오기). 저널 append 성공 이후에는 프로젝션 반영 전이라도 id가 확정되어 있으며, 저널 재생 시에도 동일 값이 재사용된다(재발급 금지). `created_at`은 시퀀서가 command에 박제한 `engine_ts`에서 파생하므로 재구축 시에도 동일하다. `seq`는 카운터 행 `UPDATE ... RETURNING` 원자 채번이라 전역 유일하고, 결번이 없는 범위는 축별이다. 레거시 구간(`seq < band_span`, `journal_counters('command_seq')` 채번)은 전역에서, 대역 구간(`market_id * band_span + 지역 번호`, `market_journal_counters` 채번)은 마켓 안에서 결번이 없고 대역 사이 빈 구간은 결번이 아니다. 대역 채번은 ADR 0022 6단계 전환 뒤에 시작하고 그 전에는 전 행이 레거시 구간이다(§7-1, §6-C ①).

**notional(시장가 매수) 응답 예**: `remaining_notional`(잔여 금액), `executed_funds`(체결 금액), `executed_volume`(환산 수량) 채움. 접수 시점 기준가로 수량 환산 규칙 명시.

**상태값**: `OPEN` / `PARTIAL` / `FILLED` / `CANCELED` / `REJECTED`. 상태 불변식(v2.6): 모든 상태에서 주문의 락 금액은 0 이상이다. `FILLED`이면 `remaining_volume`은 `"0"`(시장가 매수는 `remaining_notional`이 `"0"`)이고 해당 주문 기여분의 락 잔량도 0이다(§4-B-5 최종 체결의 실제 잔여 락 소비 규약).

**도메인 거부(신규 `reject_reason`)**: `422 domain_rejected` + `detail.reject_reason` ∈
`market_suspended`(마켓 정지) / `post_only_would_take`(POST_ONLY 즉시체결) / `fok_not_filled`(FOK 전량충족 불가) / `ioc_partial_canceled`(IOC 잔량 취소 사유·정보성) / `self_trade_prevented`(자전거래 방지 발동). `myOrder` 이벤트에도 동일 `reject_reason` 노출.
> v2.2: `over_max_order`를 이 목록에서 **삭제**. 최소/최대 주문 한도는 마켓 메타(`min_order_amount`/`max_order_amount`) 기반 **게이트웨이 정적 검증**이므로 `under_min_order`와 대칭으로 **`400 over_max_order`(§2-3) 단일 경로**로 확정한다(동일 위반의 400/422 이중 정의 상충 해소).

**FOK 자기유동성(정본 §7-2 상호참조)**: `fok_not_filled` 게이트는 자기 유동성(같은 `user_id`의 반대편 레스팅)을 충족 용량으로 센다. 자기 유동성만으로 전량 충족되는 FOK는 `fok_not_filled`로 거부되지 않고 접수되며, 실행 시 해당 부분은 체결이 아니라 STP-DC로 소멸한다(maker·taker 양쪽 `self_trade_prevented` CANCELED, taker 잔량 0 도달, 무체결 전량 소멸 가능). 사전 스캔이 자기 유동성을 제외하면 스캔·실행 판정이 갈려 결정론이 깨지므로 포함이 계약이다. 판정·소멸 산식의 정본은 §7-2이고 엔진 동작(`wouldFullyFill`·STP-DC)은 무변경이다.

**멱등 재요청**: 동일 키·동일 내용 → `200` + `X-Idempotent-Replay: true` + 기존 주문. in-flight 동시 → §2-4.

**마켓 힌트(v2.57, ADR 0034 S5)**: 요청 헤더 `X-Exchange-Market`에 바디의 `market`과 같은 값을 싣는다(선택, 서명 밖). 인그레스가 그 마켓 파티션의 소유 노드로 보내고, 소유하지 않는 노드는 아무것도 건드리지 않고 `503`(`route=not_owner`)으로 돌려보내 인그레스가 다른 노드로 다시 보낸다(§2-5-d). 힌트가 바디의 `market`과 다르면 `400 invalid_parameter`(`market_hint_mismatch`)이고 멱등 키·예약·채번을 남기지 않는다. 선검사는 힌트가 있든 없든 바디의 `market`으로 소유를 판정한다(바디의 값이 카탈로그에 없을 때만 힌트를 본다). 그래서 힌트가 틀린 생성도 그 마켓을 접수하지 않는 노드에서는 인증 전에 돌아가고, 소유 노드가 `400`을 낸다.

#### 4-B-2. 취소 (멱등·by-client 라우트 확정)
```json
// DELETE /api/v1/orders/9f2b7a4e-3d1c-4b0e-9a77-5c2f8e1d6a3b
// 또는 DELETE /api/v1/orders/by-client/bot7-2026-0721-0001
// 200
{ "id":"9f2b7a4e-3d1c-4b0e-9a77-5c2f8e1d6a3b","client_order_id":"bot7-...","status":"CANCELED",
  "remaining_volume":"0.006","executed_volume":"0.004",
  "canceled_at":"2026-07-21T09:01:00.000Z","epoch":42 }
// 이미 CANCELED → 200 + 현재 상태(멱등). 없음 → 404 order_not_found. 처리중 → 409 order_in_progress + Retry-After
```

**정지 마켓의 취소(2026-09-23)**: 마켓이 `HALTED`이면 미체결 주문의 취소도 저널 전에 거부한다. 엔진 접수가 명령 종류와 무관하게 정지를 검사하기 때문이다. 응답은 주문 생성의 정지 거부와 같은 `422 domain_rejected` + `detail.reject_reason=market_suspended`다(§4-B-1). 주문은 `OPEN`·`PARTIAL`로 남고 예약 잔고도 그대로라, 마켓을 재개한 뒤 다시 취소해야 한다. 이미 종결된 주문의 재취소는 마켓 상태와 무관하게 200 + 현재 상태다.

**마켓 힌트(v2.57, ADR 0034 S5)**: 단건 취소도 `X-Exchange-Market`에 대상 주문의 마켓을 싣는다(선택, 서명 밖). 단건 취소는 서명된 값에 마켓이 없으므로 선검사가 힌트로 소유를 판정한다. 힌트가 카탈로그에 있는 마켓이면 그 마켓을 접수하지 않는 노드는 대상 주문을 읽기 전에 `503`(`route=not_owner`)으로 돌려보낸다(§2-5-d). 선검사를 지난 노드는 대상 주문을 읽은 뒤 힌트를 그 주문의 마켓과 대조하고 다르면 `400 invalid_parameter`(`market_hint_mismatch`)다. 그래서 힌트가 틀린 단건 취소는 그 주문의 마켓을 접수하지 않는 노드에서 인증될 수 있지만, 채번 전에 `400`으로 끝나 멱등 키·예약·채번을 남기지 않는다. 대조는 종결 여부 판단보다 먼저 하므로 이미 종결된 주문도 힌트가 틀리면 `400`이다. 없는 주문은 힌트와 무관하게 `404`다. by-client 취소에서 저널에는 있으나 프로젝션에 아직 없는 주문(§4-B-4 (a)의 `409`)은 마켓을 모르므로 힌트를 대조하지 않는다. 힌트가 없으면 선검사를 하지 않고, 소유하지 않는 노드는 대상 주문을 읽은 뒤 채번 전에 `503`(`route=not_owner`)을 낸다. 대상 주문이 이미 종결됐으면 소유와 무관하게 `200` + 현재 상태다. 쓰지 않으므로 넘기지 않는다.

#### 4-B-3. 일괄 취소 `DELETE /api/v1/orders?market=KRW-BTC`
```json
// 200
{ "canceled_count":37,"canceled_ids":["01J8ZT...", "..."],
  "failed":[{"id":"01J8ZU...","reason":"order_not_found"}] }
```
페일오버/폭주 중 다수 미체결을 한 번에 취소하는 운영 시나리오 지원.

`failed[].reason`은 그 주문을 단건 취소했을 때의 오류 이름이다(예: `order_not_found`, `order_in_progress`). 도메인 거부는 오류 이름 대신 `detail.reject_reason` 값을 싣는다. 정지 마켓의 주문은 `market_suspended`로 집계되고, 나머지 주문의 취소는 계속된다(2026-09-23, §4-B-2). v2.55부터 마켓 접수 경합으로 저널 전에 끝난 취소는 `too_many_requests`로 적는다(§2-5-b, ADR 0034 S4 코드부터). v2.56부터 취소 명령의 append가 DB 연결 장애로 끝난 취소는 요청 전체를 오류로 끝내지 않고 그 주문을 `service_unavailable`로 적은 뒤 나머지 취소를 계속한다. 리더 자격 거부는 그 마켓의 게이트가 닫혔으면 `service_unavailable`, 열렸으면 `too_many_requests`다. 대상 주문 재조회마저 DB에 닿지 못했으면 남은 주문은 시도하지 않고 `service_unavailable`로 적는다. 취소 전 조회(대상 주문 조회)의 DB 연결 장애는 종전처럼 요청 전체가 오류다(§2-5-c, ADR 0034 S4b 코드부터).

**파티션별 처리와 인계(v2.57, ADR 0034 결정 15·S5)**. 일괄 취소는 `market` 유무와 무관하게 요청 시점의 미체결 주문을 파티션별로 나눈다. 이 노드가 접수 게이트를 연 파티션의 주문은 종전처럼 이 노드가 하나씩 취소한다. 그 밖 파티션의 주문은 목록째 그 파티션의 소유 노드에 넘긴다(DB 설계 §5.4-d의 인계 표 `order_cancel_requests`, 행 하나에 주문 500개까지). 소유 노드는 그 파티션 기간으로 보통의 취소 경로(저널 append와 DB fencing)를 거쳐 넘겨받은 주문만 취소하고 원소별 결과를 적는다. 요청을 받은 노드는 넘긴 뒤 최대 5초(`exchange.order.cancel-handoff.wait`) 기다렸다가 결과를 합친다. P1·P2에서는 파티션이 하나라 리더 노드는 종전처럼 모두 직접 취소하고, 팔로워와 엔진을 끈 노드(관리 평면)는 모두 리더 노드에 넘긴다. `market`을 준 일괄 취소는 보통 그 마켓의 소유 노드로 라우팅돼(§2-5-d) 인계가 없다. 받은 노드의 선검사가 그 마켓을 모르면(기동 뒤 생긴 마켓) 그 노드가 넘긴다(DB 설계 §5.4-d 1).

응답은 여전히 `200`과 원소별 결과다. 한 파티션이 기다림 안에 끝내지 못했을 때의 계약은 이렇다(ADR 0034 결정 15가 S5에 남긴 항목, AI 선택, 사용자 확인 필요).

- 소유 노드가 기다리는 동안 이 요청의 그 파티션 몫을 시작하지 않았으면 그 파티션의 주문은 `failed[].reason=partition_not_started`이다. 소유 노드가 없거나, 앞에 넣은 다른 인계를 처리하느라 차례가 오지 않은 경우다. 받은 노드는 기다림이 끝날 때 그 몫을 포기로 표시하고, 포기한 몫은 뒤에 소유 노드가 가져가지 않는다. 그래서 이 요청은 그 주문에 아무것도 하지 않았다. 다시 부르면 된다. v2.57 첫 판의 이름은 `partition_unavailable`이었고 설계 검토 대응에서 원인과 무관한 이름으로 바꿨다.
- 소유 노드가 이 요청의 그 파티션 몫을 시작했으나 기다리는 동안 끝내지 않았으면 `failed[].reason=partition_pending`이다. 주문이 많아 몫이 여러 행으로 나뉘었으면 아직 차례가 오지 않은 뒷부분도 이 사유다(소유 노드가 번호 순서대로 이어서 처리한다). 그 주문은 곧 취소될 수 있다. 결과는 주문 조회로 확인하고, 다시 부르면 그 사이 종결된 주문은 미체결 목록에서 빠진다.
- 소유 노드가 처리하며 실패한 주문은 단건 취소의 사유 그대로다(`service_unavailable`, `too_many_requests`, `market_suspended`, 먼저 종결된 주문의 상태). 소유 노드의 예기치 않은 오류로 끝난 주문은 `server_error`다. 이 판에서 인계받은 주문에만 나는 사유이고, 로컬 일괄 취소에서 같은 오류는 종전처럼 요청 전체의 `500`이다. 소유 노드가 리더 자격을 잃어 멈춘 몫은 결과를 적지 않고 새 소유 노드가 이어받으므로 `partition_pending`으로 보인다(DB 설계 §5.4-d 3).

`partition_not_started`·`partition_pending`·`server_error`는 모두 '그 주문이 아직 열려 있을 수 있다'는 뜻이고 회원 폐쇄는 미완료로 센다(§6-E). 넘기는 것은 요청 시점의 목록이라 그 뒤에 들어온 주문은 취소하지 않는다. 넘긴 요청은 넣은 지 5분이 지나면 소유 노드가 가져가지 않고, 가져간 몫도 가져간 지 5분 안에 끝나지 않으면 만료로 닫힌다(DB 시계 기준의 유효 시간이고 안전 조건이 아니다). 소유 노드는 약 40건/초로 취소하므로 한 파티션에 약 1만 2천 건을 넘게 넘기면 뒷부분이 유효 시간 안에 처리되지 않을 수 있다. 그 주문은 다시 부르면 다시 넘긴다. 인계는 소유 노드의 계정 레이트리밋을 쓰지 않는다(§2-5).

#### 4-B-4. 조회
- `GET /api/v1/orders/{id}`, `GET /api/v1/orders/by-client/{client_order_id}` → 단건 객체(위 201 스키마와 동일 필드).
- **(a) 재조회 계약**: `UNKNOWN` 해소용. **판정의 권위 소스는 `command_journal`(진실의 원천, §7-1)이다(v2.2 확정)**. orders는 비동기 프로젝션(§0-2)이라 지연될 수 있으므로, by-client 조회는 프로젝션만 보고 404를 반환해서는 안 된다. 5XX/타임아웃 후 클라이언트는 `by-client/{client_order_id}`로 조회 → **저널에 없음 = `404`**(미접수, 재전송 가능) / **저널에 있으나 프로젝션 미반영 = `409 order_in_progress` + `Retry-After`**(비종결, 재조회 반복) / 프로젝션 반영 후 = 상태 반환(접수됨, 재전송 금지). §7-6 상태머신. 주문 `id`는 명령 접수 전 사전 발급되어 저널에 박제되므로(§4-B-1 v2.6), 저널 존재 판정 시점에 이미 id가 확정되어 있고 프로젝션 반영 후 조회되는 id와 항상 동일하다. (v2.60, ADR 0036) 쓰기 가능 상태가 ACTIVE가 아니거나 ACTIVE로 돌아온 지 10초가 안 된 노드에서는, 그 사용자의 그 키로 저장된 멱등 키 행이 있는데 저널에 NEW가 없으면 `404` 대신 `409 order_in_progress` + `Retry-After`다. 엄격 동기에서 커밋을 기다리는 append는 저널에 보이지 않아 `404`가 거짓 미접수가 되기 때문이다(§2-5-e). 같은 노드에서 상태가 ACTIVE가 아니거나 '재확인 전'이면 프로젝션 반영 뒤의 '접수됨'도 내지 않고 `409 order_in_progress`다. 크래시 재시작 뒤에는 복제되지 않은 NEW가 보일 수 있기 때문이다(§2-5-e 확정 응답 보류). `423 trading_suspended`를 받은 키도 재개 뒤 이 재조회로 판정한다.
  - **판정 보류(v2.18)**: 이 경로에 노드 용량 상한이 있고(§2-5-a `scope=requery`), 초과 시 저널을 조회하기 **전에** `409 requery_deferred` + `Retry-After`가 반환된다. 종결 응답이 아니며 저널 존재 여부를 주장하지 않는다. 위 세 갈래는 슬롯을 얻은 요청에만 적용된다. 클라이언트는 `order_in_progress`와 동일하게 `Retry-After` 후 재조회를 반복하면 되고, 상한이 풀리면 세 갈래 중 하나로 확정된다. 보류 중 신규 재전송은 금지다(확정 `404`를 받기 전에는 미접수가 확인되지 않았다).
- `GET /api/v1/orders/open?market=KRW-BTC&cursor=...` / `GET /api/v1/orders/closed?...` → §2-2 엔벌로프.
- **커서 통일()**: 주문 내역 커서도 opaque `(created_at,id)` 인코딩. 초안의 정수 `from_id`는 문자열 id(UUID, v2.7 정정)와 타입 불일치 → 폐기, opaque 커서로 통일.

#### 4-B-5. `GET /api/v1/orders/chance?market=KRW-BTC` (maker/taker 수수료 분리)

**수수료 권위 소스**: 마켓별 고정(§4-A-1). 아래 `maker_fee`/`taker_fee`는 **`GET /markets`의 동일 마켓 값과 반드시 일치**한다(이전 버전의 `taker_fee:"0.0008"`는 markets의 `0.0005`와 어긋나 정정). 계정 등급별 차등 없음(v1).

**수수료 산출·양자화 규약(v2.7 개정, DB 설계 §5.2 모델 정합)**: 수수료는 **수취 자산 기준**이다. 매수 측(수취=base)은 `수취 수량 × fee율`을 `qty_scale`로, 매도 측(수취=quote)은 `체결대금 × fee율`을 `price_scale`로 **HALF_UP 선양자화**해 확정하고, 수취액에서 수수료를 차감한 순액만 사용자 계정에 게시한다(FEE leg는 FEE_INCOME 시스템 계정 귀속). **예약(락)은 수수료를 포함하지 않는다**: LIMIT 매수 락 = `qty × price`의 quote scale UP 양자화, 매도 락 = base 수량 그대로(DB 설계 §1.4 양자화 레짐·§5.1). 부분체결이 반복되며 생기는 반올림 잔차는 **자산별 마지막 FEE leg가 흡수**해 자산별 분개 합이 정확히 0을 유지한다. **최종 체결은 계산값이 아니라 그 주문의 실제 잔여 락 금액을 소비**한다. 따라서 `FILLED` 전이 시점에 해당 주문 기여분의 락 잔량은 정확히 0이며, 잔차로 인한 락 누수·음수 락은 발생하지 않는다. 접수 시 락(대금만, 수수료 예약 없음)과 체결별 차감의 정합 수치 예시는 §5-5.
```json
{ "schema_version":"1.0",
  "market":{"id":"KRW-BTC","tick_size":"1000","min_order_amount":"5000",
            "max_order_amount":"1000000000","state":"ACTIVE"},
  "maker_fee":"0.0005","taker_fee":"0.0005",
  "bid_account":{"currency":"KRW","balance":"1000000","locked":"50000"},
  "ask_account":{"currency":"BTC","balance":"0.5","locked":"0.01"} }
```

#### 4-B-6. `POST /api/v1/orders/batch` · 일괄 주문 (≤20건, per-item 계약·원자성 확정)

**바디**: 주문 객체 **배열**(각 원소는 §4-B-1 `POST /orders` 요청 필드와 동일 스키마, `client_order_id` **각 원소 필수**). 21건 이상이면 `400 invalid_parameter`. 서명 canonical은 §3-3(b) `JCS(body)`(배열 순서 보존).

**원자성 규약(확정)**: batch는 **원자적(전량 롤백)이 아니다.** 각 주문은 **독립적으로 저널 append·접수·매칭**되며, 일부 접수·일부 거부가 정상 결과다. 근거: 매칭은 주문 단위 결정론 상태기계이고(단일 라이터), 서로 무관한 주문을 하나의 실패로 전량 롤백하면 정합성 이득 없이 결합만 늘린다. **원자성이 필요하면 클라이언트가 개별 `POST /orders`로 순차 전송**한다.

**멱등 상호작용**: batch 단위 멱등키는 **없다.** 멱등 단위는 **개별 `client_order_id`**(§2-4)다. 따라서 batch 전체 재전송 시, 이미 접수된 원소는 각자 `X-Idempotent-Replay: true`로 replay되고 미접수 원소만 신규 접수 → **동일 배열 재전송은 항상 동일 결과로 수렴**(부분 재전송·중복 접수 없음). in-flight 동시 충돌 원소는 **per-item `status:"in_progress"`**(하단 enum, v2.2)로 표현한다. top-level은 여전히 `200`이므로 원소 단위 HTTP 409는 존재하지 않는다.

**HTTP 상태 규약**: 요청 자체가 유효(인증·크기·건수 통과)하면 부분 성공이라도 **`200 OK` + per-item `results` 배열**을 반환하고, 개별 성패는 각 원소의 `status`/`error`로 표현한다(207 미사용, 단일 상태로 다건 결과를 표현해 클라이언트 파싱을 단순화). 요청 전체 실패(401 서명, 413 `payload_too_large`, 400 건수 초과 등)만 top-level 에러(§2-3)로 반환한다. `results`는 **요청 배열과 동일 순서·동일 길이**이며 `index`로 매핑한다.

**한 배치는 한 마켓(v2.57, ADR 0034 S5)**: 배치의 원소는 모두 같은 `market`이어야 한다. `market`이 문자열인 원소들의 값이 둘 이상이면 요청 전체를 `400 invalid_parameter` + `detail.constraint=batch_single_market`으로 거부한다. 검사는 배열 형식·건수 검사 직후, 계정 레이트리밋 차감과 원소 처리보다 먼저 하므로 이 거부는 토큰·멱등 키·예약·채번을 남기지 않는다. `market`이 없거나 문자열이 아닌 원소는 종전처럼 그 원소만 `rejected`(`invalid_parameter`)다. 마켓 힌트(§2-5-d)를 실었으면 원소의 `market`과 같아야 하고, 다른 원소가 하나라도 있으면 `400 invalid_parameter`(`market_hint_mismatch`)다. 한 파티션의 소유 노드가 배치 전체를 접수하게 하려는 것이다. P3에서 마켓이 섞인 배치는 한 노드가 모든 원소를 접수할 수 없고, 원소를 다른 노드에 넘기면 원소별 결과와 순서의 계약을 두 노드에 걸쳐 맞춰야 한다. 같은 파티션의 마켓끼리만 섞도록 허용하지 않는 이유는 마켓의 파티션 배정이 운영 데이터라 클라이언트가 알 수 없고, 배정이 바뀌면 받던 배치가 거부되기 때문이다. P1·P2에서도 같은 규칙이다(코드 경로가 같다). 여러 마켓에 주문하려면 마켓마다 배치를 나눠 보낸다. v2.56까지는 마켓이 섞인 배치를 받았다. 저장소의 웹·봇·하네스 부하 도구는 배치를 쓰지 않는다.

```json
// 요청  POST /api/v1/orders/batch   (X-Exchange-Market: KRW-BTC)
[ {"market":"KRW-BTC","side":"bid","ord_type":"limit","price":"50000000","volume":"0.01","client_order_id":"b1"},
  {"market":"KRW-BTC","side":"bid","ord_type":"limit","price":"40000000","volume":"0.2","time_in_force":"FOK","client_order_id":"b2"},
  {"market":"KRW-BTC","side":"bid","ord_type":"limit","price":"50000000","volume":"0.01","client_order_id":"b1"} ]

// 200 (부분 성공: 1건 접수 · 1건 도메인 거부 · 1건 멱등 replay)
{ "schema_version":"1.0",
  "results":[
    { "index":0,"client_order_id":"b1","status":"accepted",
      "order":{"id":"01J8ZT...A","status":"OPEN","market":"KRW-BTC","side":"bid",
               "remaining_volume":"0.01","executed_volume":"0","epoch":42,"seq":88123601} },
    { "index":1,"client_order_id":"b2","status":"rejected",
      "error":{"name":"domain_rejected","detail":{"reject_reason":"fok_not_filled"},
               "message":"도메인 검증에서 거부됨"} },
    { "index":2,"client_order_id":"b1","status":"replayed","idempotent_replay":true,
      "order":{"id":"01J8ZT...A","status":"OPEN","market":"KRW-BTC","side":"bid",
               "remaining_volume":"0.01","executed_volume":"0","epoch":42,"seq":88123601} } ],
  "summary":{"accepted":1,"rejected":1,"replayed":1,"total":3} }
```

- `status` ∈ `accepted`(신규 접수) / `replayed`(멱등 replay, 기존 주문 반환) / `rejected`(개별 거부·**종결**) / **`in_progress`(v2.2 신설: 동일 `client_order_id`가 in-flight 처리 중, 비종결·재시도 대상)**. `accepted`·`replayed`는 `order`(§4-B-1 스키마)를, `rejected`·`in_progress`는 `error`를 담는다. per-item `error`는 **§2-3 표준 에러 객체의 축약형**이다(v2.3): `name`·`message`에 더해 기계가독 부가정보는 §2-3·§4-B-1과 동일하게 **`detail` 안에** 둔다(`rejected`=`detail.reject_reason`, top-level `reject_reason` 금지). `in_progress`=`{"name":"order_in_progress","retry_after":<s>}`(`retry_after`는 §2-3 top-level 필드와 동일 위치). `in_progress`는 종결 거부가 아니므로 클라이언트는 `Retry-After` 후 동일 `client_order_id`로 재전송/재조회하면 멱등 수렴한다. `summary`에는 해당 시 `in_progress` 카운트를 포함한다.
- cancel-all(§4-B-3)의 `failed[]`와 대칭: 생성은 성공·실패를 **동일 `results[]`**에 per-item으로 담아 대칭 계약을 이룬다.
- (v2.56, ADR 0034 S4b) 원소의 저널 append가 DB 연결 장애나 리더 자격 거부로 끝나면 그 원소는 §2-5-c의 단건 응답에 맞춰 `in_progress`(`service_unavailable`, `too_many_requests`, `order_in_progress` 가운데 하나)다. 한 원소가 'DB에 닿지 못함'(저널 확인마저 DB 연결 장애)으로 끝나면 남은 원소는 시도하지 않고 `in_progress`(`service_unavailable`, `retry_after=1`)로 적는다. 시도하지 않은 원소는 같은 배열을 다시 보내면 새로 접수된다(위 멱등 상호작용과 같다).

### 4-C. 계좌 (인증 필요)

| 기능 | 메서드·경로 | weight |
|---|---|---|
| 전체 잔고 | `GET /api/v1/accounts` | 2 |
| 체결 내역(내 거래) | `GET /api/v1/my/trades` | 2 |
| 거래(원장) 내역 | `GET /api/v1/my/ledger` | 2 |
| API 키 회전 | `POST /api/v1/keys/{access_key}/rotate` **미구현** | 5 |
| API 키 폐기 | `DELETE /api/v1/keys/{access_key}` **미구현** (§3-2 즉시 revocation, 표 등재 v2.5) | 5 |

정정(2026-09-10): 위 두 경로는 `server/` 에 대응 핸들러가 없습니다. 설계로만 존재하므로 미구현으로 표시합니다.

#### 4-C-1. `GET /api/v1/accounts`
```json
{ "schema_version":"1.0",
  "data":[
  { "currency":"KRW","balance":"1000000","locked":"50000",
    "avg_buy_price":"0","unit_currency":"KRW",
    "cost_basis_qty":"0","cost_basis_amount":"0","version":57 },
  { "currency":"BTC","balance":"0.49","locked":"0.01",
    "avg_buy_price":"49500000","unit_currency":"KRW",
    "cost_basis_qty":"0.4","cost_basis_amount":"19800000","version":12 } ],
  "pagination":{"next_cursor":null,"has_more":false} }
```
불변식: `balance(=available) + locked = total`, 항상 `available ≥ 0`이고 `locked ≥ 0`(둘 다 DB CHECK).

**잔고 판 `version`(v2.58 신설, ADR 0034 S6)**. 그 사용자·자산의 USER 계정 가운데 AVAILABLE·LOCKED 두 잔고 행의 `balances.version` 합이고, `balance`·`locked`와 같은 SQL 문장에서 읽는다(DB 설계 §4.1). JSON 숫자다. 성질은 셋이다. DB 타임라인 안에서 줄지 않는다. 값이 같으면 `balance`·`locked`도 같다. 수동 DB 승격으로 커밋이 사라졌거나 백업에서 복원하면 줄 수 있고, 그때는 같은 값이 다른 잔고를 가리킬 수도 있다. myAsset(§5-5) 항목의 같은 필드와 같은 값이라 둘을 비교할 수 있다. 클라이언트의 비교 규칙은 §5-2다. additive이며 읽지 않는 클라이언트는 종전과 같다.

**원가 산출 규약(v2.21 신설)**. 서버는 평단가를 저장하지 않는다. 원가는 원장 순델타 파생 프로젝션(DB 설계 §6.4 ⑱⑲, `position_cost`)에 **누산기 쌍**으로만 적재되고, 평단가는 조회 시점에 그 쌍에서 산출한다. 평단가를 저장하면 취득마다 나눗셈 반올림이 누적되고 부분 처분이 그 오차를 확대하기 때문이다.

| 필드 | 원천 | 의미 |
|---|---|---|
| `cost_basis_qty` | `position_cost.cost_qty` | **원가 확보 수량**. 대가를 지불한 취득분만 누산한다 |
| `cost_basis_amount` | `position_cost.cost_amount` | **원가 확보 금액**. 그 취득에 실제 지출한 `unit_currency` 총액 |
| `avg_buy_price` | 산출값 | `cost_basis_amount / cost_basis_qty`를 `unit_currency`의 자산 scale로 **HALF_UP 반올림**. `cost_basis_qty = 0`이면 `"0"` |

- **`avg_buy_price`는 원가 확보분의 평단가이지 보유 전량의 평단가가 아니다.** 두 수는 보유량 전부에 원가가 있을 때만 같다.
- **원가 미상 수량은 별도 필드를 만들지 않고 클라이언트가 유도한다**: `원가 미상 = balance + locked - cost_basis_qty`. 세 항이 모두 같은 응답 항목 안에 있고 서버가 `cost_qty + unknown_qty = available + locked` 항등을 불변식으로 감사하므로(하네스 C24), 필드를 하나 더 실으면 같은 사실의 두 번째 표현이 생겨 둘이 어긋날 여지만 늘어난다. 유도식이 음수가 되는 응답은 그 자체로 C24 위반이며 클라이언트는 0으로 클램프해 표시한다.
- **원가 0과 원가 미상은 다르다.** 시드 발행·입금처럼 매수 상대거래가 없는 취득은 원가가 0이 아니라 **미상**이다. 원가 0으로 계상하면 전액이 수익으로 오표시된다. 그래서 그 수량은 `cost_basis_qty`에 들어가지 않고, 해당 자산의 보유 전량이 미상이면 `avg_buy_price`는 `"0"`이 된다. 클라이언트는 이 `"0"`을 "평단가 0원"이 아니라 **"산출 불가"**로 렌더링한다(`cost_basis_qty = 0`이 그 판별자다).
- **`cost_basis_amount`를 함께 내는 이유**: `avg_buy_price`는 quote scale 반올림값이라 `cost_basis_qty × avg_buy_price`로 총매수를 재계산하면 원 단위로 어긋난다. 위 예시에서 `0.4 × 49500000 = 19800000`은 우연히 맞지만, 반올림이 발생하는 실제 체결 조합에서는 어긋난다. 총매수·평가손익을 정확값으로 계산하려면 나눗셈 이전 원본이 필요하다.
- **quote 자산 행**(v1은 `KRW`)은 원가 개념이 없어 `avg_buy_price`·`cost_basis_qty`·`cost_basis_amount`가 모두 `"0"`을 유지한다. quote 자산에 원가 행이 생기는 것 자체가 불변식 위반이다(C25 `quote_asset_position`).
- `unit_currency`는 v1에서 `KRW` 고정이다. 다중 quote 확장은 자산별로 원가 통화가 갈리므로 이 필드의 행별 산출과 `position_cost` PK 확장이 같이 가야 한다.
- **기존 필드·엔벌로프는 불변이다**(additive only). `cost_basis_qty`·`cost_basis_amount`를 읽지 않는 클라이언트는 종전과 동일하게 동작한다.

> **금액 표기(v2.21 정정)**: 위 예시의 `balance`·`locked`는 종전 판이 `"1000000.000000000000000000"`처럼 18자리를 채워 적었으나, 구현(`ApiFormats.amount`)과 §5-5 myAsset 예시는 후행 0을 제거한 평문 표기다. §2-1의 문자열 표기 계약은 자릿수 고정을 요구하지 않으므로, 정본 표기를 후행 0 제거로 통일하고 §4-C-1 예시를 그에 맞춘다. 저장 타입이 `NUMERIC(40,18)`인 것과 전송 표기는 별개다.
#### 4-C-2. `GET /api/v1/my/trades` · 체결 내역 (v2.20 보완: 표에만 있고 상세가 한 줄이던 비대칭 해소)

`liquidity_flag`(MAKER/TAKER)·`fee`·해당 체결가를 노출한다(원장 정합성 검증 현실성).
- 파라미터: `market`(선택, 예 `KRW-BTC`), `count`(≤200), `cursor`. §2-6 opaque 커서 규약 동일이며 `id` 축은 `trade_id`(체결 id = event_seq 10진 문자열)다.
- 응답: §2-2 리스트 엔벌로프. 정렬은 체결 시각 내림차순(`(created_at, trade_id)` DESC), 경계는 exclusive다.
- 각 항목은 **한 체결의 내 관점 투영**이다. 같은 체결을 maker와 taker가 각자 조회하면 `liquidity_flag`·`side`·`fee`·`fee_currency`가 서로 반대로 나온다.

```json
{ "schema_version":"1.0",
  "data":[
    { "trade_id":"88123601","market":"KRW-BTC","side":"bid",
      "order_id":"9f2b7a4e-3d1c-4b0e-9a77-5c2f8e1d6a3b",
      "price":"50000000","volume":"0.01",
      "liquidity_flag":"TAKER","fee":"0.000005","fee_currency":"BTC",
      "timestamp":"2026-07-21T09:00:01.060Z" } ],
  "pagination":{"next_cursor":"eyJ0cyI6IjIwMjYtMDctMjFUMDk6MDA6MDEuMDYwWiIsImlkIjoiODgxMjM2MDEifQ","has_more":true} }
```

| 필드 | 의미 |
|---|---|
| `side` | **내 주문 방향**(`bid`/`ask`). 체결 레코드의 `taker_side`는 taker 관점이므로 내가 maker면 뒤집어 낸다 |
| `liquidity_flag` | 이 체결에서 내가 maker였으면 `MAKER`, taker였으면 `TAKER` |
| `fee` | **내 측** 수수료(maker면 maker 수수료). 상대편 수수료는 노출하지 않는다 |
| `fee_currency` | 수수료 자산. 수수료는 **수취 자산 기준**이라(§4-B-5 v2.7) 매수는 base, 매도는 quote다. 금액만으로는 자산이 확정되지 않아 함께 낸다 |
| `order_id` | 이 체결을 만든 **내 주문의 외부 UUID**. 내부 주문 PK는 노출하지 않으며, 참조 주문이 조회 범위 밖이면 `null` |
| `trade_id` | 체결 id(= `event_seq`). `/my/ledger`의 `ref.trade_id`, 공개 `GET /trades`의 `trade_id`와 **같은 값**이다 |

> 자전거래는 STP(§4-B-1 `self_trade_prevented`)가 막으므로 한 체결의 양측이 같은 사용자인 경우는 발생하지 않는다. 그럼에도 성립하면 같은 `trade_id`가 두 행이 되어 커서 `id` 축이 중복되므로 `MAKER` 한 행으로 접는다.

#### 4-C-3. `GET /api/v1/my/ledger` · 거래(원장) 내역 (v2.5 상세 신설, 표에만 있고 상세 절이 없던 비대칭 해소)
- 파라미터: `currency`(선택, 예 `KRW`), `count`(≤200), `cursor`\|`to`. §2-6 opaque 커서 규약 동일(`(ts,id)`, id 축=외부 노출 id 문자열. 주문 id는 UUID, v2.7 §7-1 정정).
- 응답: §2-2 리스트 엔벌로프. 각 항목은 복식부기 원장 분개의 사용자 관점 투영이며, `entry_type` 어휘는 DB 설계 문서 `ledger_entries`와 동일(DEPOSIT/WITHDRAW/TRADE/FEE/RESERVE/RELEASE …).
- 조회 범위는 **자기 계정 분개**다(`owner_type='USER'` 스코프). 수수료 leg는 FEE_INCOME 시스템 계정 귀속이라 사용자 원장에 나타나지 않고, 예약·해제는 자기 LOCKED 계정 분개로 보인다.
```json
{ "schema_version":"1.0",
  "data":[
    { "entry_id":"45012","currency":"BTC","amount":"0.003998","entry_type":"TRADE",
      "ref":{"type":"TRADE","trade_id":"88123601"},
      "timestamp":"2026-07-21T09:00:01.060Z" },
    { "entry_id":"45011","currency":"KRW","amount":"-200000","entry_type":"TRADE",
      "ref":{"type":"TRADE","trade_id":"88123601"},
      "timestamp":"2026-07-21T09:00:01.060Z" } ],
  "pagination":{"next_cursor":"eyJ0cyI6IjIwMjYtMDctMjFUMDk6MDA6MDEuMDYwWiIsImlkIjoiNDUwMTEifQ","has_more":true} }
```
> **id 표기(v2.20 보완)**: `entry_id`와 `ref.trade_id`는 **10진 문자열**이다. 이전 판의 ULID 모양 예시(`01J8ZW…`)는 v2.6 이전 표기의 잔존이라 교정한다. `entry_id`는 §2-6의 ledger_entries 예외로 계약 지정된 외부 식별자이고, `trade_id`는 §2-6의 "체결 = event_seq 파생 id" 규정을 그대로 따른다.

**`ref` 형태(v2.20 보완)**. 업무 이벤트 id의 축이 타입마다 달라 키 이름으로 구분한다. 클라이언트는 `type`으로 분기한다.

| `ref.type` | 키 | 값 |
|---|---|---|
| `TRADE` | `trade_id` | 체결 id(= `event_seq`) 10진 문자열. §4-C-2 `trade_id`·공개 `GET /trades`의 `trade_id`와 같은 값 |
| `RESERVE` | `order_id` | 예약을 만든 **주문의 외부 UUID**. 원 업무 이벤트 id는 내부 주문 PK라 그대로 노출하지 않고 치환한다 |
| `RELEASE` / `GENESIS` / `TRANSFER` / `REVERSAL` | `id` | 업무 이벤트 id 10진 문자열(`RELEASE`는 해제를 유발한 `event_seq`, `GENESIS`는 발행 일련) |
> 예시 수치는 §5-5 체결 시나리오와 정합(v2.7 재계산: bid 0.004 체결 → 체결대금 200,000 차감 TRADE 분개 + 수취 BTC 순액 0.003998 게시 TRADE 분개, 동일 timestamp 축). taker 수수료 0.000002 BTC의 FEE leg는 FEE_INCOME 시스템 계정 귀속이라 사용자 원장에는 나타나지 않는다(§4-B-5 v2.7 수취 자산 수수료 모델). 동일 체결에서 파생된 분개 묶음은 원장 트랜잭션의 `UNIQUE(business_event_type, business_event_id)`(§7-1)로 멱등이라, 재생·재시도로 같은 업무 이벤트가 다시 적용되어도 중복 분개가 생기지 않는다.

---

### 4-G. 문의 API (V15 신설, `/api/v1/support`)

사용자가 운영자에게 묻고 운영자가 답한다. 공지(§4-F)가 운영자에서 전체로 가는 단방향이라면 문의는 개인 대 운영자다.

**회원 세션 전용이다.** 무인증 문의를 받지 않는 이유는 답을 전할 방법이 없기 때문이다. 런타임 외부 API 의존 금지 규칙상 메일 발송이 불가능하므로 답변은 앱 안에서만 읽힌다. 계정 없이 받으면 답을 쓰고도 전달할 수 없는 문의가 쌓인다. 화면은 비회원에게 이 사실을 그대로 말하고 로그인으로 보낸다.

| 기능 | 메서드·경로 | 인증 | rate limit |
|---|---|---|---|
| 문의 등록 | `POST /api/v1/support` | 회원 세션 | 회원당 10회/1h |
| 내 문의 목록 | `GET /api/v1/support` | 회원 세션 | 표준. 커서 페이지네이션(§2-6) |
| 대기 큐 조회 | `GET /admin/v1/support` | 관리 세션(VIEWER) | 표준 |
| 답변 등록 | `POST /admin/v1/support/{external_id}/answer` | 관리 세션(MEMBER_MOD) | 표준 |

```json
// POST /api/v1/support
{ "category":"TRADE", "title":"체결이 안 됩니다", "body":"지정가 주문이 30분째 미체결입니다" }

// 201
{ "schema_version":"1.0",
  "external_id":"7c1a...", "category":"TRADE", "title":"체결이 안 됩니다",
  "body":"...", "status":"OPEN", "answer":null, "answered_at":null,
  "created_at":1756300000000 }
```

**분류**: `TRADE`·`TRANSFER`·`ACCOUNT`·`ETC` 넷. 운영자 분류 작업을 사용자에게 미루는 것이 아니라, 넷 중 하나를 고르는 비용이 자유 서술보다 낮고 큐 정렬에 바로 쓰이기 때문이다. 다섯 개를 넘기면 고르는 것 자체가 일이 된다.

**상태**: `OPEN` → `ANSWERED`. 둘뿐이다. 사용자 관점에서 답이 왔는지 아닌지 말고 알아야 할 상태가 없다. `IN_PROGRESS` 같은 중간 상태는 운영자 내부 사정이라 사용자 화면에서 기다림의 성격을 바꾸지 않는다.

**후속 문의**: 스레드를 만들지 않는다. 답변이 불충분하면 새 문의를 등록한다. 스레드는 알림·읽음·정렬이 모두 따라붙는 별도 기능이고, 데모 규모에서 한 번의 문답이 감당하지 못하는 사례가 드물다.

**신규 에러코드**(§2-3 확장).

| HTTP | name | 의미 |
|---|---|---|
| 400 | `invalid_support_input` | 분류 어휘 위반, 제목·본문 길이 위반 |
| 409 | `support_already_answered` | 답변된 문의에 재답변 시도 |

---

### 4-F. 공지 조회 API (V14 신설, `/api/v1/notices`)

관리 평면이 발행한 공지를 사용자에게 노출한다. 쓰기는 §3-6 관리 평면(`/admin/v1/config/notices`)이 단독으로 갖고, 여기는 읽기만이다.

| 기능 | 메서드·경로 | 인증 | rate limit |
|---|---|---|---|
| 공지 목록 | `GET /api/v1/notices` | 없음 | IP 60회/60s(자체 스로틀). 커서 페이지네이션(§2-6) |

무인증이지만 `PublicMarketPaths`에는 넣지 않는다. 그 목록은 §4-A 시장 데이터 전용이고 weight 규칙이 시장 데이터 응답 모양을 전제한다. 공지는 시장 데이터가 아니므로 데모 세션·게시판·회원 인증 평면과 같이 자기 `SlidingWindowThrottle`을 든다(그 클래스 주석의 명문 규칙).

```json
// GET /api/v1/notices
{ "schema_version":"1.0",
  "data":[
    { "id":"12", "title":"정기 점검 안내", "body":"08-28 02:00~04:00 주문 접수 중단",
      "severity":"CRITICAL", "published_at":1756300000000 }
  ],
  "pagination":{ "next_cursor":null, "has_more":false } }
```

**목록 페이지네이션**: `cursor`(§2-6 opaque) + `limit`(기본 50, ≤200). 정렬 축은 `(published_at DESC, id DESC)`다. `published_at`은 유일하지 않아 그것만으로는 쪽 경계가 정해지지 않는다(동시각 발행 두 건이 중복되거나 빠진다). 커서의 id 축은 `notices.id`, 곧 내부 BIGINT PK다. §2-6의 "내부 PK 미노출"에 대한 예외이며 근거는 이 경로가 이미 같은 값을 `data[].id`로 내보내고 있다는 점이다. 공지는 사용자별로 갈리지 않는 전역 공개 자원이라 id가 새어도 남의 것을 세거나 정렬 위치를 캐는 표면이 없다(주문·입출금·문의와 다르다). 커서 앵커는 `active` 조건 없이 되짚는다. 읽는 도중 운영자가 한 건을 철회해도 나머지 쪽이 통째로 400이 되면 안 된다.

**노출 범위**: `active = true`인 공지만 반환한다. 관리자가 내린 공지(`active=false`)는 철회된 것이지 보관된 것이 아니다. 발행자·수정 이력은 운영 정보라 사용자 응답에 싣지 않는다.

**심각도 계약**: `INFO`·`WARNING`·`CRITICAL`. 이 값은 색이 아니라 **전달 채널**을 정한다.

| severity | 전달 | 근거 |
|---|---|---|
| `CRITICAL` | 전역 배너, 닫기 불가 | 거래 중단·점검처럼 모르면 손해를 보는 내용 |
| `WARNING` | 전역 배너, 닫기 가능 | 알아야 하지만 조작을 막지는 않는 내용 |
| `INFO` | 목록과 진입점 표식만 | 배너로 끼어들면 소음이 된다 |

배너는 화면당 하나다. 연결 끊김 배너가 공지 배너보다 우선한다. 연결이 끊긴 상태에서는 어떤 조작도 성립하지 않으므로 그 사실이 먼저 읽혀야 한다. 두 배너를 쌓으면 둘 다 안 읽힌다. (v2.60, ADR 0036) 거래 일시 중단 배너(§4-A-7의 `trading_suspended=true`)는 연결 끊김 다음, 공지 앞이다. 닫을 수 없다.

---

### 4-E. 입출금 API (V14 신설, `/api/v1/transfers`)

원화 입출금. 시뮬레이션이며 실제 은행 연동은 없다. 계좌번호·카드번호 등 실금융정보는 어떤 필드로도 받지 않는다(회원_설계 §6 계승).

**자산 제한**: KRW 전용이다. 코인 입출금은 V14 범위 밖이며 근거는 DB 설계의 `position_cost` 계층 논증이다(비엔진 게시자와 정산이 같은 사용자·비quote 자산을 동시에 건드리면 원가 프로젝션 적용 순서가 갈린다).

**처리 방식**: 입금은 즉시 확정, 출금은 운영자 승인 큐를 지난다. 출금 요청 시점에 금액을 LOCKED로 잠근다. 잠그지 않으면 요청 후 승인 전에 그 돈으로 거래할 수 있고, 승인 시점에 잔고가 모자라 확정이 실패한다.

| 기능 | 메서드·경로 | 인증 | 비고 |
|---|---|---|---|
| 입출금 요청 | `POST /api/v1/transfers` | 회원 세션 | `Idempotency-Key` 헤더 필수 |
| 내 입출금 내역 | `GET /api/v1/transfers` | 회원 세션 | 커서 페이지네이션(§2-6) |
| 출금 요청 취소 | `DELETE /api/v1/transfers/{external_id}` | 회원 세션 | REQUESTED 상태만. 잠금 해제 |
| 대기 큐 조회 | `GET /admin/v1/transfers` | 관리 세션 | `status=REQUESTED` 기본 |
| 출금 승인 | `POST /admin/v1/transfers/{external_id}/approve` | 관리 세션 | LOCKED to GATEWAY 확정 |
| 출금 거절 | `POST /admin/v1/transfers/{external_id}/reject` | 관리 세션 | 잠금 해제, 사유 기록 |

요청·응답.
```json
// POST /api/v1/transfers   Idempotency-Key: web-1a2b3c
{ "direction":"DEPOSIT", "asset":"KRW", "amount":"1000000" }

// 201
{ "schema_version":"1.0",
  "external_id":"3f9a...", "direction":"DEPOSIT", "asset":"KRW",
  "amount":"1000000", "status":"COMPLETED",
  "created_at":"2026-08-27T09:00:00.000Z", "updated_at":"2026-08-27T09:00:00.000Z" }
```

출금은 같은 스키마에 `direction:"WITHDRAW"`, 응답 `status:"REQUESTED"`다. 승인 후 `COMPLETED`, 거절·취소 후 `CANCELED`가 된다. `PENDING`·`FAILED`는 스키마에 있으나 V14 흐름에서는 만들지 않는다.

**멱등**: `Idempotency-Key`는 `transfer_requests.UNIQUE(user_id, idem_key)`로 물리 강제한다. 같은 키 재요청은 기존 리소스를 `200` + `X-Idempotent-Replay: true`로 반환한다. 주문의 `client_order_id`(§2-4)와 같은 사상이며 자금 이동이라 필수다.

**금액 규약**: 문자열 NUMERIC(40,18). KRW는 `price_scale`로 양자화하고 소수점 이하를 허용하지 않는다. 1회 한도는 데모 가드로 `MAX_TRANSFER_KRW`(1억)이다. 실제 한도 정책이 아니라 표시·입력 파손을 막는 상한이다.

**목록 페이지네이션**: `GET /api/v1/transfers`는 §2-6 opaque 커서를 그대로 따른다. 정렬 축은 `(created_at DESC, id DESC)`이고 커서는 `(created_at, external_id)`를 base64url JSON으로 감싼 값이다(`Cursors`). 내부 BIGINT PK는 노출하지 않는다. `count` 대신 `limit`(기본 50, ≤100)을 쓰며 서버는 한 건을 더 읽어 `has_more`를 판정한다. 마지막 쪽의 `next_cursor`는 `null`이다. 커서 행은 `user_id` 스코프로 되짚으므로 타인의 `external_id`를 커서로 넘기면 `invalid_parameter`다(정렬 위치 탐침 차단). V16 인덱스 `idx_transfer_user_keyset`이 이 축을 커버한다.

**신규 에러코드**(§2-3 확장).

| HTTP | name | 의미 |
|---|---|---|
| 400 | `invalid_transfer_asset` | KRW 외 자산 요청 |
| 400 | `invalid_transfer_amount` | 0 이하, 소수점, 한도 초과, 자릿수 위반 |
| 403 | `transfer_not_owned` | 타인의 요청 조작 시도 |
| 409 | `transfer_not_pending` | 이미 확정·취소된 요청의 재처리 |
| 422 | `insufficient_funds` | 출금 요청 시 가용 잔고 부족(기존 코드 재사용) |

---

### 4-D. 게시판(커뮤니티) API (M3, `/api/v1/community`)

게시판_설계.md §4 정본. 거래 평면과 aud 클레임으로 경계 분리한다: 읽기(GET)는 무인증 허용(선택 인증 optional-auth: 유효 회원 세션을 동봉하면 응답 항목에 서버 산출 `mine`을 additive로 포함), 쓰기는 회원 세션(EdDSA, aud=community, kind=HUMAN)을 CommunityAuthenticator가 자체 검증한다. 거래 평면 인증 필터(ApiAuthFilter)를 통과하지 않는다. 공통 규약(엔벌로프 §2-2, 에러 §2-3, 커서 §2-6)을 상속한다. schema_version "1.0" 고정.

| 기능 | 메서드·경로 | 인증 | 비고 |
|---|---|---|---|
| 글 목록 | `GET /api/v1/community/boards/{market}/posts` | 선택 | `sort`(latest\|popular), `cursor`, `count`(기본 20, ≤50). 항목에 `mine` |
| 글 작성 | `POST /api/v1/community/boards/{market}/posts` | 회원 | `Idempotency-Key` 헤더 선택(1~36자 `[A-Za-z0-9_-]`). 재요청은 200 + `X-Idempotent-Replay: true` |
| 글 단건 | `GET /api/v1/community/posts/{externalId}` | 선택 | soft-deleted·hidden은 마스킹 본문. 응답에 `mine` |
| 글 수정 | `PATCH /api/v1/community/posts/{externalId}` | 회원 | 작성자 본인만 |
| 글 삭제 | `DELETE /api/v1/community/posts/{externalId}` | 회원 | soft delete, 본인만 |
| 댓글 목록 | `GET /api/v1/community/posts/{externalId}/comments` | 선택 | `cursor`, `count`. created_at ASC 커서. 항목에 `mine` |
| 댓글 작성 | `POST /api/v1/community/posts/{externalId}/comments` | 회원 | `parent_comment_id` 선택(1뎁스). 2뎁스는 400 |
| 댓글 삭제 | `DELETE /api/v1/community/comments/{externalId}` | 회원 | soft delete, 본인만 |
| 글 좋아요 | `POST` / `DELETE /api/v1/community/posts/{externalId}/like` | 회원 | PK 유니크로 멱등. 응답 `{liked, like_count}` |
| 댓글 좋아요 | `POST` / `DELETE /api/v1/community/comments/{externalId}/like` | 회원 | 멱등 |
| 신고 | `POST /api/v1/community/reports` | 회원 | `{target_type, target_id, reason, detail?}`. 대상별 1인 1회. 임계 초과 자동 HIDDEN |

**소유권 표시(mine)**: 공개 GET(글 목록·단건·댓글 목록)은 선택 인증이다. 유효 회원 Bearer가 있으면 응답 각 항목에 `mine`(불리언)을 additive로 싣는다. `mine`은 서버가 토큰 `user_id`와 작성자 내부 id를 대조해 산출하며 요청자가 작성자면 true다. 토큰 부재·무효·만료는 조회를 막지 않고 `mine=false`로 강등한다(조회 가용성 보존). 작성자 식별자(member_external_id/user_id)는 응답에 노출하지 않는다(external_id는 세션 `sub` 재사용 값이라 공개 노출을 배격, 최소 노출 원칙). 프론트 수정·삭제 어포던스는 handle 대조가 아니라 이 `mine`으로 판정한다(동명이인·표시명 변경에 안전). 기존 필드·엔벌로프는 불변이다.

**본문·길이·이스케이프**: 제목 ≤120, 글 본문 ≤4000, 댓글 ≤1000자. 초과·빈 값은 `400 invalid_parameter`. 저장은 원문, 응답 렌더 시 HTML 유의 문자를 엔티티로 이스케이프한다(XSS 방어). 파라미터 바인딩만 사용(인젝션 방어).

**rate limit**(게시판 전용 버킷, 코어 주문 버킷과 별개): 조회 IP당 300/10s, 쓰기 회원당 60/60s. 초과는 `429 too_many_requests` + Retry-After.

**에러 코드**(§2-3 재사용 + 게시판 추가): `400 invalid_parameter`(길이·sort·커서), `401 jwt_verification`(미인증·비회원 토큰 쓰기), `403 out_of_scope`(작성자 불일치), `404 not_found`(board/post/comment 미존재), `409 duplicate_report`(중복 신고), `429 too_many_requests`.

**신고 큐 행 계약(v2.32)**: `reports` 행만으로는 운영자가 아무것도 못 한다. 처리 액션이 대상의 `external_id`를 받는데 `reports.target_id`는 내부 BIGINT이고, 게시판_설계 §5가 "프론트는 내부 수치 id를 알지 못한다"를 규정하기 때문이다. 그래서 목록 행에 대상 요약 4개를 additive로 더한다. `target_external_id`(처리 액션 인자), `target_status`(VISIBLE·HIDDEN·DELETED), `target_excerpt`(게시글은 제목, 댓글은 본문 앞 120자), `target_author`. 대상 조인이 실패하면 넷 다 null이고 화면은 종결만 허용한다. 신고 사유만 보여주고 원문을 감추면 운영자가 눈 감고 처리하게 된다.

**처리 단위 주의**: 대상 상태 전이(`/posts|comments/{external_id}/moderate`)와 신고 종결(`/reports/{id}/resolve`)은 별개 호출이고 원자적이지 않다. 콘솔은 둘을 한 판단으로 묶어 부르되 앞이 되고 뒤가 실패한 경우를 구분해 알린다. `HIDE`는 같은 상태로 다시 써도 안전해 재시도가 가능하고, `DELETE`는 종료 상태라 재시도 시 400이다.

**운영(모더레이션, 관리 평면 MEMBER_MOD, `/admin/v1/community`)**: 숨김·복구·삭제·신고 처리는 배포 단위 C(exchange-admin)가 받아 커뮤니티 서비스에 위임한다. 관리자 조작은 게시판 도메인(posts·comments·reports) 밖(원장·저널)에 닿지 않고 admin_audit_log에 적재된다.

| 기능 | 메서드·경로 | 역할 |
|---|---|---|
| 글 모더레이션 | `POST /admin/v1/community/posts/{externalId}/moderate` | MEMBER_MOD, `{action(HIDE\|UNHIDE\|DELETE), note?}` |
| 댓글 모더레이션 | `POST /admin/v1/community/comments/{externalId}/moderate` | MEMBER_MOD |
| 신고 목록 | `GET /admin/v1/community/reports?status=&cursor=&limit=` | MEMBER_MOD. 기본 `PENDING`, 오래된 신고부터. 행에 대상 요약을 함께 싣는다(v2.32) |
| 신고 처리 | `POST /admin/v1/community/reports/{reportId}/resolve` | MEMBER_MOD, `{resolution(REVIEWED\|ACTIONED\|DISMISSED), note?}` |

---

<a name="5"></a>
## 5. WebSocket(STOMP) 채널 명세

### 5-1. 엔드포인트 및 프로토콜

- 접속: `wss://api.exchange.local/ws/v1` (SockJS fallback 옵션). 앱 프로토콜 버전 = path의 `/v1` + 페이로드 `schema_version`.
- 프로토콜: **STOMP over WebSocket**. Spring `@EnableWebSocketMessageBroker`.
- **팬아웃 토폴로지(크로스노드 라우팅 확정)**: 다중 노드에서 채널 전달은 대상 destination 종류에 따라 갈린다.
  - **공개 `/topic/**`(orderbook/trade/ticker/candle)**: 순수 **Redis Pub/Sub 릴레이**로 노드 간 브로드캐스트(모든 노드가 모든 구독자에게 동일 전달). 이 경로는 stateless fan-out이라 Redis relay로 충분.
  - **비공개 `/user/queue/**`(myOrder/myAsset)**: Spring **user-destination**은 "사용자의 WS 세션이 붙은 노드"를 알아야 전달되는데, 순수 Redis Pub/Sub 릴레이는 Spring의 **노드 간 user-destination 라우팅을 기본 제공하지 않는다.** 사용자의 세션이 노드 A, 체결이 노드 B에서 발생하면 비공개 이벤트가 유실될 수 있다. 이는 "비공개 채널 관찰" 기반의 체결정합·자동복구 증명에 직접 타격이므로, 아래 둘 중 **하나를 반드시 구성**한다:
    - **(A) 외부 STOMP 브로커 릴레이(권장, 채택안)**: `enableStompBrokerRelay(...)`로 **RabbitMQ(STOMP 플러그인) 또는 ActiveMQ**에 user-destination을 위임한다. 외부 브로커가 노드 간 user queue 라우팅을 담당하므로, 어느 노드에서 체결이 나든 세션이 붙은 노드로 정확히 전달된다. (SimpleBroker 대신 브로커 릴레이 사용.)
    - **(B) Spring 내장 + Redis 릴레이 조합**: `setUserRegistryBroadcast(...)`(노드 간 `UserRegistry` 공유)와 `setUserDestinationBroadcast(...)` + `brokerChannel`을 **Redis로 릴레이**하도록 구성한다. 노드가 해당 사용자의 세션을 로컬에 갖지 않으면 브로드캐스트 채널로 재전달 → 세션 보유 노드가 최종 전달.
  - **크로스노드 전달 보장**: 위 (A) 또는 (B) 구성 하에서, **사용자 세션 노드와 체결 발생 노드가 달라도 `/user/queue/**` 이벤트는 정확한 세션 노드로 전달됨을 보장**한다. 페일오버로 세션이 새 노드로 재연결되면, 클라이언트는 재연결 후 REST 스냅샷(주문/잔고 재조회)으로 재동기화한다(§5-2, §7-6).
  - **채택한 배선(v2.58, ADR 0034 S6): 노드 단위 중계.** 위 (A)·(B) 어느 쪽도 쓰지 않는다. 각 앱 노드의 내장 SimpleBroker가 종전처럼 자기 WS 세션의 구독과 전달을 맡는다. 마켓을 쥔 노드는 게시를 자기 내장 브로커로 보낸 뒤 같은 JSON 본문을 봉투에 담아 외부 STOMP 브로커의 토픽 `/topic/exch.bus.v1`로 보낸다. 모든 노드는 그 토픽을 구독하고, 자기가 보낸 봉투를 빼고 자기 내장 브로커로 다시 보낸다. 사용자 봉투는 `convertAndSendToUser`로 보내므로 그 사용자의 세션이 있는 노드에서만 전달된다. 봉투 머리글은 `x-v`(1), `x-origin`(보낸 노드), `x-kind`(`topic`·`user`·`hb`), `x-dest`(클라이언트 목적지), `x-user`, `x-market`, `x-partition`(`hb`), `x-epoch`, `x-seq`, `x-sent-at`이다. 받는 노드는 `x-dest`가 §5-3의 목적지 모양(사용자는 `/queue/myOrder`·`/queue/myAsset`)이 아니거나 `x-user`가 숫자가 아니거나, 하트비트의 `x-partition`이 파티션 계획에 없으면 버린다. 브라우저는 브로커에 붙지 않는다. 세션 단위 중계를 쓰지 않은 이유는 브로커 메모리가 세션 수에 비례하고(RabbitMQ 세션당 약 0.65MiB), 브로커가 없을 때 앱 게시가 예외가 되며 모든 WS 세션이 `ERROR`로 끊기기 때문이다(`harness/evidence/pml-s6-probe-20260925/`).
  - **소유 노드 하트비트.** 중계 모드에서 파티션의 접수 게이트를 연 노드는 그 파티션마다 200ms 간격(설정 `exchange.ws.relay.owner-heartbeat`, 간격의 두 배가 `mirror-max-silence`를 넘으면 기동을 멈춘다)으로 하트비트 봉투(`x-kind:hb`, `x-partition`, `x-epoch`는 그 기간의 epoch)를 게시와 같은 발행 큐로 보낸다. 본문은 그 파티션 마켓마다 이 노드가 마지막으로 게시한 호가·티커의 `[epoch, seq]`다. 다른 노드는 이것으로 소유 노드의 게시가 자기에게 이어지는지(최근 5초에 3개 이상 받으면 닿는다), 자기 미러가 마지막 게시를 따라잡았는지를 본다(§4-A-2). 하트비트는 클라이언트에 전달하지 않는다.
  - **브로커와 연결.** RabbitMQ 4.3 STOMP 플러그인, 가상 호스트 `exchange`, 계정 하나. 노드마다 발행 연결과 구독 연결을 따로 둔다(메모리 경보가 발행 연결을 막아도 구독은 계속된다). 하트비트 `5000,5000`. 구독은 `ack:client`(누적 확인), `prefetch-count:1000`, `x-max-length:5000`, `x-max-length-bytes:4194304`, `x-message-ttl:10000`이다. 발행은 쓰기 완료를 1초까지 기다리고, 넘거나 연결이 끊기면 발행 큐를 비우고 발행 연결을 SO_LINGER 0으로 끊은 뒤 다시 붙는다. 넣은 지 2초 넘은 봉투는 보내지 않는다. 전달은 최대 한 번이고 브로커는 이력을 다시 보내지 않는다. 빠진 메시지는 클라이언트가 §5-2의 결번·epoch 규칙으로 알아채고 REST로 맞춘다. 모드는 `exchange.ws.relay.mode`이고 기본값 `simple`은 중계 없이 단일 노드 게시다.
  - **브로커 장애.** REST는 중계를 기다리지 않으므로 그대로 동작한다. 게시는 커밋 뒤이고 봉투는 기다리지 않는 큐에 넣으며 넘치면 버린다. 자기 노드의 게시는 자기 세션에 계속 간다. 달라지는 것은 쥐지 않은 노드의 호가·티커가 미러 대신 DB 집계와 `seq: 0`을 내는 것이다(§4-A-2). 모든 마켓을 쥐지 않은 노드는 중계 불능이면 WS 세션을 닫고 새 핸드셰이크를 거절한다(§5-6). 중계 불능은 구독 연결이 5초 넘게 끊겼거나, 그 노드가 쥐지 않은 파티션의 리스를 다른 노드가 유효하게 쥐었는데 그 소유 노드가 리스 TTL + 3초 넘게 닿지 않는 것이다. 뒤의 조건이 메모리 경보처럼 구독 연결은 멀쩡하고 발행만 막힌 경우를 잡는다. 리스가 유효하다는 조건 때문에 소유 노드가 죽은 경우(리스가 TTL 안에 만료된다)는 중계 불능으로 보지 않는다. 모든 마켓을 쥔 노드(P1·P2 리더)는 닫지 않는다.
  - **조회 노드.** 조회 전용 노드(`prod,read`)는 `/ws/v1`을 받지 않는다(404). 인그레스는 `/ws/*`를 매칭 노드(app-1·app-2)로만 보낸다.
- 공개 채널: 인증 불필요. 비공개(`/user/**`): `CONNECT` 시 JWT 필요.
- **heart-beat(신규)**: `10000,10000`(10s). 슬로우 컨슈머는 송신 버퍼 상한 초과 시 해당 subscription **드롭 후 절단**(백프레셔). 연결당 **최대 구독 채널 50개** 상한. 상한 초과 SUBSCRIBE는 **§5-6 구독 거절 규약을 준용**한다(v2.5: RECEIPT 미발급 + 해당 subscription으로 `subscription_rejected` 통지 MESSAGE 1건 후 서버측 해제, 연결·기존 구독은 유지).

### 5-2. seq / epoch 의미 (high 확정)

- **`seq`는 채널 단위 단조 증가**로 정의. **일반 규칙(v2.3): seq 도메인 = 구독 destination 단위**다. 공개 채널의 채널 단위 = market × type이며, **unit 차원이 있는 candle은 `market × type × unit` 단위 독립 단조 증가**다(`/topic/candle.1m.KRW-BTC`와 `/topic/candle.5m.KRW-BTC`는 독립 seq 도메인. 1m만 구독한 클라이언트가 5m/1d 증가분으로 false-gap을 감지하는 일 없음). 예: `orderbook.KRW-BTC`와 `trade.KRW-BTC`는 각자 독립 seq 도메인. 특정 채널(destination)만 구독해도 false-gap 없음.
- **seq 도메인의 두 실현(v2.11 확정)**: 채널 seq는 종류에 따라 두 방식으로 실현하되 둘 다 채널 단위 단조 증가라는 상위 규칙을 지킨다. (1) 팬아웃 대상이 점 상태인 `orderbook`·`ticker`와 비공개 `myOrder`·`myAsset`은 노드 인메모리 조밀 카운터로 실현한다(destination당 +1 단조, 공개 채널의 REST 스냅샷은 현재 카운터값 §4-A-2·§4-A-4, 연속성 판정 = 다음 seq가 직전 +1). 비공개 두 채널은 REST 스냅샷에 seq 도메인이 없어 기준점을 다음 realtime으로 잡는다(아래 구독 즉시 계약). (2) 팬아웃 대상이 영속 이벤트 리스트인 `trade`·`candle`은 `event_seq`로 실현한다(`trade` seq = event_seq, `candle` seq = 버킷 최대 event_seq, REST 리스트 스냅샷 §4-A-3·§4-A-5와 **동일 값·동일 도메인**). (2)는 리스트 스냅샷의 과거 행이 저마다 seq를 지녀야 하는데 인메모리 카운터로는 과거 행 값을 재구성할 수 없어 영속 축(event_seq)을 쓴다. `event_seq`는 전역 유일한 마켓 대역 파생 값이고(`market_id * band_span + market_seq`. `band_span`은 `journal_band_spec('event_seq')`이고 V36~V45는 10^7, V46부터 10^12다. 결번 없는 축은 마켓 안 `market_seq`) 채널은 그 마켓 이벤트의 일부만 실어 채널 내에서는 sparse 단조 증가이므로 **연속성 판정은 strictly-increasing**(`seq ≤ 기준`은 중복·역행 폐기, `seq > 기준`은 순서대로 수용)이며 조밀 +1을 요구하지 않는다. V46 확대 경계에서 한 마켓의 값이 10^7 대역에서 10^12 대역으로 뛰어도 계속 커지므로 판정이 같고, 최댓값 64 x 10^12는 JavaScript 안전 정수 2^53 안이다. 어느 실현이든 REST 스냅샷 seq가 곧 WS 라이브 seq 기준점이라, 재연결·gap·epoch 교체 후 재동기화에서 라이브가 도메인 불일치로 폐기되는 영구 정지가 없다.
- **비공개 채널의 seq 도메인(v2.2 확정)**: `myOrder`/`myAsset`은 market 차원이 없으므로 **`user × type` 단위 독립 단조 증가**로 정의한다(사용자별·채널별 별도 카운터, 두 채널 간 번호 공유 없음). gap 감지·재동기화도 이 도메인 기준으로 수행한다(불연속 감지 시 REST 스냅샷(주문 `GET /orders/open`·잔고 `GET /accounts`)으로 재동기화).
- **비공개 채널 seq 도메인의 중계 모드 판(v2.58, ADR 0034 S6)**: 중계 모드(`exchange.ws.relay.mode=broker`, §5-1)에서는 `myOrder`·`myAsset`의 seq 도메인이 `user × market`이고 두 메시지에 `"seq_domain":"user_market"`을 싣는다. 한 마켓은 한 순간 그 파티션의 리더 하나만 게시하므로 이 도메인은 노드 하나의 카운터로 조밀하다. 클라이언트는 채널 안에서 마켓마다 기준점을 따로 두고, 한 마켓의 결번이면 그 채널을 REST로 재동기화한다. 재동기화 중 쌓인 메시지도 마켓마다 조밀 검사를 한다. `seq_domain`이 없으면 종전 `user × type` 도메인이다(`simple` 모드와 이전 서버).
- **전역 순서가 필요하면 `global_seq` 병기**(선택). gap 감지는 채널 seq로만 수행.
- **`epoch`(리더 세대)**: 모든 WS·orderbook REST 페이로드에 포함. 페일오버로 새 리더가 seq 도메인을 재설정하면 epoch 증가. **클라이언트는 epoch 변화 감지 시 무조건 REST 스냅샷 재동기화**(backward/중복 seq 오인 방지). (v2.55, ADR 0034 S4) 서버는 이 값을 그 마켓 파티션 리스의 epoch로 낸다. P1·P2에서는 파티션이 `matching-engine` 하나라 종전과 같은 값이다. 파티션 리더 모드(P3)의 마켓별 재동기화 계약은 S6에서 정한다. (v2.58, ADR 0034 S6) WS 메시지의 epoch는 그 메시지를 만든 라이터 기간의 epoch이고, REST 호가·티커는 §4-A-2의 선택이 정한 epoch다. epoch는 DB 타임라인 하나 안에서 파티션 사이에 유일하고 배정 순서대로 커지므로(ADR 0034 결정 3) 같은 마켓에서는 큰 값이 뒤의 리더 기간이다. 다른 마켓끼리는 파티션이 다를 수 있어 비교하지 않는다.
- **마켓별 epoch 비교(v2.58, ADR 0034 S6)**: 클라이언트는 epoch를 마켓마다 따로 기억한다. 메시지의 마켓은 공개 채널이면 목적지나 `market`, 비공개 채널이면 `market`이다(`market`이 없는 이전 서버의 myAsset은 epoch를 보지 않는다). 처음 본 마켓은 기록한다. 기억보다 작은 epoch의 메시지는 버린다. 큰 epoch를 받으면 기억을 올리고 **그 마켓만** 재동기화한다. 그 마켓의 인메모리 카운터 채널(orderbook·ticker)은 기준점을 비우고 REST 스냅샷을 새 기준점으로 받고, trade·candle은 기준점을 둔 채 REST로 메우며, 비공개 두 채널은 한 번 REST로 맞춘다. 여러 마켓의 epoch가 잇달아 오르면(P2 페일오버) 비공개 재동기화는 300ms 창으로 모아 한 번 한다. 다른 마켓의 채널은 건드리지 않는다. 작은 epoch의 메시지만 10초 넘게 이어지고 그 사이 그 마켓의 메시지를 하나도 받지 않았으면 그 마켓의 기억을 지우고 재동기화한다. 수동 DB 승격이나 복원으로 epoch가 되돌아간 경우 채널이 영구히 멈추지 않게 하려는 것이다. REST 스냅샷의 epoch가 기억보다 작으면 그 스냅샷을 버리고, 크면 기억을 올린다. 재연결은 종전처럼 모든 채널을 재동기화하되 기억은 지우지 않는다. P1·P2에서는 모든 마켓이 같은 epoch라 종전의 전역 비교와 결과가 같다.
- **`seq: 0` 스냅샷(v2.58, ADR 0034 S6)**: REST 호가·티커나 구독 즉시 스냅샷의 `seq`가 0이면 그 스냅샷은 기준점이 아니다(§4-A-2). 클라이언트는 상태만 반영하고 그 채널의 기준점을 비운다. 다음 realtime이 기준점이다.
- **myAsset 버전 판정(v2.58, ADR 0034 S6)**: 클라이언트는 myAsset 자산 항목과 계좌 조회 원소의 `version`(§4-C-1)으로 통화마다 새 값을 가린다. 게시 노드가 둘이면 같은 자산의 두 메시지가 순서가 뒤집혀 올 수 있고, seq는 노드마다 달라 순서를 말하지 못한다. 실시간 항목은 이렇게 다룬다. 둘 다 version이 있으면 큰 값과 같은 값은 적용하고, 작으면 적용하지 않고 계좌를 다시 조회한다. 같은 값을 적용하는 이유는 원가 필드(`avg_buy_price`, `cost_basis_qty`, `cost_basis_amount`)가 version에 들지 않기 때문이다. 원가 재구축은 잔고를 바꾸지 않고 원가만 고치므로(DB 설계 §4.1) 같은 version의 항목이 새 원가를 싣고 올 수 있다. 같은 version이면 `balance`·`locked`는 같은 값이다. 항목에만 있으면 적용하고 한 번 다시 조회한다. 기억한 값에만 있으면 비교할 수 없으므로 적용하지 않고 다시 조회한다. 둘 다 없으면 종전처럼 덮어쓴다. 계좌 조회 응답은 권위이지만 늦게 올 수 있다. 그래서 요청을 보낼 때의 통화별 version을 기억하고, 응답 version이 지금 값 이상이면 적용하며, 지금 값보다 작지만 요청 때 값 이상이면 그 통화는 지금 값을 둔다(요청 뒤에 더 새 실시간이 왔다). 요청 때 값보다도 작으면 DB가 되돌아간 것이므로 적용한다. version 때문에 부르는 재조회는 2초에 한 번으로 묶는다.
- **구독 즉시 계약(v2.4 적용 범위 확정, orderbook 채널 한정)**: **orderbook 채널에 한해** SUBSCRIBE 성공 → 서버가 `stream:"snapshot"` 1건 전송(현재 호가 전체 상태 + 경계 `seq`) → 이후 `stream:"realtime"`. snapshot의 seq가 이후 realtime 연속성의 기준점. **스냅샷을 읽는 시점(v2.59, 2026-09-26 보정)**: 서버는 노드 내장 브로커가 그 구독을 등록한 **뒤에** 스냅샷의 `seq`와 본문을 읽어 보낸다. 그래서 `seq > snapshot.seq`인 realtime은 모두 그 구독에 전달된다. 등록 전에 읽으면 읽은 뒤·등록 전에 게시된 realtime이 그 구독에 가지 않아 결번이 난다. 마켓을 쥐지 않은 노드가 미러로 스냅샷을 낼 때(§4-A-2)는 받은 중계 봉투로 미러를 먼저 갱신한 뒤 자기 세션에 전달하므로 같은 보장이 선다. 이 보장은 전달 계층의 재정렬을 없애지 않으므로 아래 클라이언트 규칙은 그대로다. **스냅샷·realtime 순서 역전(v2.7)**: 스냅샷 전송과 realtime 게시는 경합할 수 있어 구독 직후 realtime이 스냅샷보다 먼저 도착하거나 `seq <= snapshot.seq`인 realtime이 스냅샷 이후 도착할 수 있다. 클라이언트는 `seq <= snapshot.seq`인 realtime을 **폐기**하고, `seq > snapshot.seq`부터 연속성 검사를 시작한다. 서버가 스냅샷 산출과 게시를 직렬화하더라도 이 클라이언트 계약은 유지된다(전달 계층 재정렬 방어). **기준점 전진 규칙(v2.20, 동일 epoch 내 한정)**: 스냅샷 수신 전에 realtime이 먼저 도착했으면 그 realtime의 seq가 임시 기준점이고, 뒤이어 도착한 스냅샷은 `snapshot.seq > 기준점`일 때만 채택한다. 낮은 seq 스냅샷을 채택하면 이미 표시한 호가가 과거 상태로 되돌고(전체 상태 메시지라 화면이 즉시 역행한다) 이어지는 realtime이 결번 판정을 받아 불필요한 재동기화를 부른다. 같은 epoch 안에서는 REST 재동기화 스냅샷을 포함해 어느 경로에서도 기준점이 내려가지 않는다. **epoch 교체는 이 규칙의 예외이며 상위 규칙이 아래 epoch 규칙이다(인메모리 카운터 실현 채널 한정)**: `orderbook`·`ticker`·`myOrder`·`myAsset`의 seq는 노드 인메모리 카운터라 리더가 바뀌면 낮은 값에서 다시 시작하므로, epoch 변화(v2.58부터 그 채널 마켓의 epoch 상승)를 감지하면 클라이언트는 이 채널들의 기준점을 **비우고**(전진 비교를 하지 않고) 재동기화 스냅샷을 새 기준점으로 채택한다. 이 예외가 없으면 신 리더 메시지가 구 리더 기준점에 막혀 채널이 영구 정지한다. 반대로 `trade`·`candle`은 전역 영속 `event_seq` 실현이라 교체로 리셋되지 않으므로 **기준점을 유지**한다. 여기서 기준점을 비우면 재동기화가 기수신 체결을 재전달해 캔들 합성이 이중계상한다. **`stream` 필드는 orderbook 채널 메시지에만 존재**한다. §5-4 trade/ticker/candle 및 §5-5 myOrder/myAsset 스키마에 `stream` 필드가 없는 것이 계약이며, 클라이언트 파서는 해당 채널에서 이 필드를 기대하지 않는다. **여타 채널(trade/ticker/candle/myOrder/myAsset)은 snapshot 메시지를 전송하지 않으며**, 연속성 기준 seq는 **구독 후 첫 realtime 메시지의 seq**다(그 이후의 불연속만 gap으로 판정). 상태 초기값이 필요한 비공개 채널은 구독 직후 REST 스냅샷(주문 `GET /orders/open`·잔고 `GET /accounts`)으로 초기 상태를 확보한다(아래 비공개 도메인 재동기화 규칙과 동일 소스).
- **gap 감지 → 재동기화 규칙**: 클라이언트가 `seq` 불연속 감지 시 REST 스냅샷(`GET /orderbook`, epoch 포함)으로 재동기화 후, 스냅샷 `seq` 이후 realtime 재개.

### 5-3. STOMP 구독 경로

| 채널 | 종류 | destination | 인증 |
|---|---|---|---|
| 호가 | 공개 | `/topic/orderbook.{market}` | X |
| 체결 | 공개 | `/topic/trade.{market}` | X |
| 티커 | 공개 | `/topic/ticker.{market}` | X |
| 캔들 | 공개 | `/topic/candle.{unit}.{market}` | X |
| 내 주문 | 비공개 | `/user/queue/myOrder` | O (JWT) |
| 내 잔고 | 비공개 | `/user/queue/myAsset` | O (JWT) |

### 5-4. 공개 채널 메시지 스키마 (채널별 seq + epoch)

**orderbook** (`/topic/orderbook.KRW-BTC`)
```json
{ "schema_version":"1.0","type":"orderbook","market":"KRW-BTC",
  "epoch":42,"seq":150043,"stream":"realtime",
  "timestamp":"2026-07-21T09:00:00.200Z",
  "orderbook_units":[
    {"ask_price":"50010000","ask_size":"0.5","bid_price":"50000000","bid_size":"0.7"}] }
```
**trade** (`/topic/trade.KRW-BTC`)
```json
{ "schema_version":"1.0","type":"trade","market":"KRW-BTC","epoch":42,"seq":72311,
  "trade_id":"01J8ZT...","price":"50005000","volume":"0.01","taker_side":"bid",
  "timestamp":"2026-07-21T09:00:01.050Z" }
```
> trade 메시지의 `trade_id`·`timestamp`는 §4-A-3과 동일 규약이다(`trade_id`=event_seq 결정론 파생, `timestamp`=command `engine_ts` 파생, 재구축 시 동일. v2.6). WS trade `seq`(v2.11)는 §4-A-3 REST `seq`와 **동일 값·동일 도메인**(= event_seq 파생 `trade_id`)이다. REST 스냅샷과 WS 라이브가 한 도메인이라 재연결 재동기화가 연속된다(§5-2 (2) trade·candle 도메인). orderbook·ticker의 조밀 팬아웃 카운터와 달리 trade 채널은 그 마켓 이벤트(event_seq, 결번 없는 축은 마켓 안 market_seq) 중 체결만 실어 채널 내에서 sparse 단조 증가이고, 연속성 판정은 strictly-increasing(§5-2)이다.

**ticker** (`/topic/ticker.KRW-BTC`, 1s 집계)
```json
{ "schema_version":"1.0","type":"ticker","market":"KRW-BTC","epoch":42,"seq":33121,
  "trade_price":"50005000","change":"RISE","change_rate":"0.0123",
  "acc_trade_volume_24h":"1234.56","best_bid":"50000000","best_ask":"50010000",
  "timestamp":"2026-07-21T09:00:01.000Z" }
```
**candle** (`/topic/candle.1m.KRW-BTC`)
```json
{ "schema_version":"1.0","type":"candle","unit":"1m","market":"KRW-BTC","epoch":42,"seq":8802,
  "candle_time":"2026-07-21T09:00:00.000Z","open":"50000000","high":"50020000",
  "low":"49990000","close":"50005000","volume":"12.34" }
```
(각 채널 seq는 독립 도메인. 예시 번호가 채널마다 다른 것이 정상.)

### 5-5. 비공개 채널 메시지 스키마

**myOrder** (`/user/queue/myOrder`)
```json
{ "schema_version":"1.0","type":"myOrder","epoch":42,"seq":51001,
  "id":"01J8ZT...","client_order_id":"bot7-...","market":"KRW-BTC","side":"bid","status":"PARTIAL",
  "price":"50000000","executed_volume":"0.004","remaining_volume":"0.006",
  "reject_reason":null,
  "trade":{"trade_id":"01J8ZT...","price":"50000000","volume":"0.004",
           "liquidity_flag":"TAKER","fee":"0.000002"},   // 매수 지정가 50000000 이내 체결(불변식⑤). fee는 수취 자산(BTC) 단위: 수취 0.004 × taker 0.0005 = 0.000002. base scale(qty_scale=8) HALF_UP 선양자화(§4-B-5 v2.7 수취 자산 수수료 모델)
  "timestamp":"2026-07-21T09:00:01.060Z" }
```
> myOrder의 `trade.trade_id`·`timestamp` 역시 §4-A-3 규약을 따른다(event_seq 파생·engine_ts 파생, 재구축 시 동일. v2.6).
> **중계 모드 필드(v2.58, ADR 0034 S6)**: 중계 모드에서는 `"seq_domain":"user_market"`을 싣고 seq 도메인이 user × market이다(§5-2). `epoch`는 그 주문 마켓의 라이터 기간 epoch다. `simple` 모드는 이 필드가 없고 도메인이 종전과 같다.

**myAsset** (`/user/queue/myAsset`)
```json
{ "schema_version":"1.0","type":"myAsset","market":"KRW-BTC","epoch":42,"seq":7203,
  "assets":[
    {"currency":"KRW","balance":"500000","locked":"300000",
     "avg_buy_price":"0","unit_currency":"KRW",
     "cost_basis_qty":"0","cost_basis_amount":"0","version":58},
    {"currency":"BTC","balance":"0.493998","locked":"0.01",
     "avg_buy_price":"50025013","unit_currency":"KRW",
     "cost_basis_qty":"0.003998","cost_basis_amount":"200000","version":13}],
  "timestamp":"2026-07-21T09:00:01.061Z" }
```
> **마켓과 잔고 판(v2.58, ADR 0034 S6)**: `market`은 이 게시를 만든 명령의 마켓이고 `epoch`는 그 마켓의 라이터 기간 epoch다. 한 게시는 명령 하나의 방출이라 마켓이 하나다. 자산 항목의 `version`은 §4-C-1의 잔고 판이다. 두 필드는 두 모드에 모두 싣고, 중계 모드에서는 `"seq_domain":"user_market"`도 싣는다. 클라이언트는 자산 사이의 새 값을 seq가 아니라 `version`으로 가린다(§5-2). 이전 서버의 메시지에는 두 필드가 없다.
> **원가 필드(v2.21 신설)**: 항목 스키마가 §4-C-1과 같은 필드 집합을 쓴다. 산출 규약·원가 미상 유도식·`"0"`의 의미는 전부 §4-C-1 정본을 따르며 여기서 다시 정의하지 않는다. **델타에 이 필드를 싣는 것이 계약이다**: 평단가는 매수 체결마다 바뀌는데 델타에 없으면 클라이언트가 직전 값을 보존할 수밖에 없고, 그 보존이 매수마다 낡은 평단가를 화면에 남긴다(`GET /accounts` 재적재 전까지 틀린 값). 위 예시는 초기 BTC 0.49가 전량 원가 미상(시드 취득)이고 이번 bid 체결로 0.003998만 원가를 확보한 상태다. `200000 / 0.003998 = 50025012.506…`을 KRW scale 0으로 HALF_UP 반올림해 `50025013`이며, 원가 미상 수량은 `0.493998 + 0.01 - 0.003998 = 0.5`로 유도된다. 반올림값과 원본 금액이 함께 실리므로 클라이언트는 총매수를 `cost_basis_amount`로 계산한다.
> v2.7: 위 myOrder(bid) 체결과 **동일 시나리오를 DB 설계 §5.2 모델(수취 자산 수수료, 예약 무수수료)로 재계산한 정합 예시**다. 초기 KRW 1,000,000·BTC 0.49 가정. bid 0.01@50,000,000 접수 시 KRW **500,000 락**(대금만. 수수료는 예약하지 않는다) → balance 500,000. 0.004 체결로 락에서 **200,000 차감**(체결대금) → locked 300,000. **매수 체결 수취는 BTC**이며 taker 수수료 0.000002 BTC(0.004×0.0005)를 차감한 **순액 +0.003998**이 balance에 게시 → 0.493998(bid는 KRW를 락, BTC locked 무변화). BTC locked 0.01은 이 주문과 무관한 기존 매도 주문 락(§4-C-1 기준과 동일). `seq:7203`은 myOrder(51001)와 **독립 도메인**(user × type, §5-2)임을 보이는 예시 번호다. 이 예시는 반올림 잔차가 0인 수치이며, 잔차가 발생하는 부분체결 조합에서는 §4-B-5 규약대로 자산별 마지막 FEE leg가 잔차를 흡수하고 최종 체결이 실제 잔여 락을 소비하므로 FILLED 시 이 주문 기여분의 locked는 정확히 0이 된다.

### 5-6. STOMP 인증·구독 흐름 (부분 실패는 subscription만 거절)

```
>> CONNECT
   Authorization: Bearer <JWT>       # 비공개 채널 시 필수
   accept-version:1.2
   heart-beat:10000,10000
<< CONNECTED
   heart-beat:10000,10000

>> SUBSCRIBE  id:sub-0  destination:/topic/orderbook.KRW-BTC  receipt:r0
<< RECEIPT    receipt-id:r0
   (이어서 stream:"snapshot" 1건 → realtime, orderbook 채널 한정 계약, §5-2 v2.4)

>> SUBSCRIBE  id:sub-1  destination:/user/queue/myOrder  receipt:r1
   # 인증 실패 시(v2.2 정정): ERROR 프레임을 사용하지 않는다. STOMP 1.2 사양상 서버는
   #   ERROR 프레임 전송 후 연결을 반드시 닫아야 하므로(MUST close), ERROR로는 "연결 유지" 불가.
   # 서버는 RECEIPT(r1)를 발급하지 않고, 해당 subscription(sub-1)으로 거절 통지 MESSAGE 1건 전송:
   << MESSAGE  subscription:sub-1  destination:/user/queue/myOrder
      {"schema_version":"1.0","type":"subscription_rejected","error":{"name":"jwt_verification"}}
   #   전송 직후 서버가 sub-1을 서버측 해제(이후 이벤트 미전달)
   # 연결은 유지, 정상 구독(sub-0)은 계속 수신
```
CONNECT 자체 인증 실패만 연결 종료(이때는 STOMP 1.2 규약대로 `ERROR` 프레임 후 절단). 개별 SUBSCRIBE 실패는 **ERROR 프레임 없이**(RECEIPT 미발급 + `subscription_rejected` 통지 MESSAGE) 해당 subscription만 거절. 표준 STOMP 1.2 클라이언트와의 상호운용을 위해 ERROR는 연결 종료 상황에만 사용한다(v2.2).

**WS 수용 조건과 거절(v2.58, ADR 0034 S6)**. 노드는 모든 마켓의 게시를 세션에 줄 수 있을 때만 `/ws/v1`을 받는다. 조건은 두 모드가 같다. 모든 파티션의 접수 게이트를 연 노드(P1·P2 리더)는 늘 받는다. `simple` 모드에서 쥐지 않은 파티션의 리스를 다른 노드가 유효하게 쥐었으면 받지 않는다(`relay_required`). 두 노드 배치의 팔로워와 P3의 한쪽 노드가 여기에 든다. 리스가 만료됐거나 자기 것이면 받는다(앱 노드가 하나인 배치의 기동 직후). 엔진을 끈 노드는 파티션을 쥐지 못하므로 두 모드 모두 늘 받지 않는다(`relay_required`). 중계 모드에서는 중계 불능(§5-1 브로커 장애)이면 받지 않는다(`relay_unavailable`). 받지 않는 노드는 핸드셰이크를 업그레이드 전에 `503`과 `Retry-After: 5`로 거절한다. 업그레이드 뒤에 닫으면 인그레스에는 성공으로 보여 다른 노드로 넘기지 않으므로 업그레이드 전에 거절한다(nginx `proxy_next_upstream http_503`, `harness/evidence/pml-s6-probe-20260925/` N1). 이미 열린 세션은 받지 않게 되는 순간 WebSocket 종료 코드 1013(이유 `relay_required` 또는 `relay_unavailable`)으로 닫는다. STOMP `ERROR` 프레임을 쓰지 않아 CONNECT 인증 실패와 구분된다. 클라이언트는 종전 재연결 규칙(지수 백오프)대로 다시 붙고, 붙으면 모든 채널을 REST로 맞춘다. 같은 판정을 헬스 그룹 `ws`(`/actuator/health/ws`, 받지 않으면 503)로도 낸다. 거절 수는 `exchange.ws.relay.handshake_refused{reason}`(`relay_unavailable`, `relay_required`)로 센다.

---

<a name="6"></a>
## 6. 관리/시뮬레이션/카오스/HA API (exchange-admin, `/admin/v1`, 분리 포트·mTLS)

> **보안(§3-6)**: 관리 API는 `scope=admin` JWT + mTLS + 비가역 액션 `operator_id`/`approval_token` + 감사 로그 + rate limit(§2-5)로 보호. 관측 데이터 내 텍스트 지시로 자동 실행 금지. 모든 페이로드 `schema_version` 포함(v2.5 각주: §6-A/§6-B/§6-C의 예시들은 §4와 동일 규약으로 **계약상 항상 포함하되 지면상 표기만 생략**한다. 기준 예시 §4-A-1).

### 6-A. 봇 제어 API

| 메서드 | 경로 | 기능 | approval_token(§3-6) |
|---|---|---|---|
| POST | `/admin/v1/sim/bots` | 봇 생성(유형·수·파라미터) | 불요 |
| DELETE | `/admin/v1/sim/bots/{id}` | 봇 제거. **미구현** | 불요 |
| PATCH | `/admin/v1/sim/bots/{id}/params` | 실행 중 파라미터 변경(operator 승인) | **필수** |
| POST | `/admin/v1/sim/start` \| `/stop` \| `/pause` | 전체 제어(기동·정지·발행 일시정지 토글, v2.22) | 불요 |
| POST | `/admin/v1/sim/scenarios/{name}/activate` | 프리셋(steady/volatile/spike/ramp/selloff/thundering-herd) | 불요 |
| PUT | `/admin/v1/sim/rate` | 전역 목표 OPS | 불요 |
| PUT | `/admin/v1/sim/scale` | 마켓·유형별 봇 대수 set-point(count 증감 상위 제어) | 불요 |
| PUT | `/admin/v1/sim/bias` | 마켓별 목표 net bias(-1~+1)·자동균형 강도 | 불요 |
| PUT | `/admin/v1/sim/effects` | 체결 시각효과 강도(`trade_flash`)·공개 config 반영 | 불요 |
| GET | `/admin/v1/sim/status` | 봇 수·OPS·성공률·평균지연·마켓별 bias·대수·수명주기(schema 1.2) | 불요 |
| WS | `/admin/v1/ws/sim/telemetry` | 실시간 텔레메트리 | 불요 |

```json
// POST /admin/v1/sim/bots  (operator_id 필수)
{ "operator_id":"op-01","type":"MARKET_MAKER","count":40,"market":"KRW-BTC",
  "params":{"base_spread_bps":"8","quote_size":"0.05","skew_factor":"0.01","lambda":2.0},
  "seed":{"base":"1.0","quote":"50000000"} }
// 201 (governor set-point 모델: 이 경로는 유형별 목표 대수를 count만큼 상향한다)
{ "schema_version":"1.0","created":40,"bot_ids":[],"fleet_total":140,
  "fleet_present":true,"applied_target":40,"clamped":false }

// GET /admin/v1/sim/status → 200 (schema 1.3, 봇_방향성 §5-5). source=local
{ "schema_version":"1.3", "fleet_present":true, "source":"local",
  "running":true, "paused":false,
  "target_ops":"20", "trade_flash":"normal",
  "markets":[
    { "market":"KRW-BTC",
      "target_net_bias":"0.30", "measured_net_bias":"0.22", "effective_bias":"0.24",
      "auto_balance":{"enabled":true,"strength":"0.5"},
      "scale":{"MARKET_MAKER":{"target":40,"active":40},"TAKER":{"target":30,"active":28}},
      "fill_ratio":"0.61", "trade_flash":"normal" } ] }

// GET /admin/v1/sim/status → 200 (schema 1.3). 봇 노드 발행분을 읽은 분리 배치
{ "schema_version":"1.3", "fleet_present":false, "source":"published",
  "observed_at":"2026-09-06T13:40:00.000Z", "stale":false,
  "running":true, "paused":false, "target_ops":"10.0", "trade_flash":"normal",
  "markets":[ /* local 과 같은 서식 */ ] }

// GET /admin/v1/sim/status → 200 (schema 1.3). 발행분도 없는 상태
{ "schema_version":"1.3", "fleet_present":false, "source":"unknown",
  "trade_flash":"normal" }
```

**`GET /sim/status` 원천 규약(v2.43, schema 1.3)**: 값의 출처를 `source`로 밝힌다. `local`은 이 JVM의 거버너, `published`는 봇 노드가 `sim_fleet_state`에 발행한 스냅샷(DB 설계 v2.39 패치 절), `unknown`은 둘 다 없는 상태다. **`unknown`에서는 `running`·`paused`·`target_ops`·`markets`를 싣지 않는다.** `false`·`"0"`·`[]`를 실으면 콘솔이 정지·0대라는 없는 사실을 단정한다. 실측(2026-09-06): 봇이 초당 8.3건을 발행하는 동안 콘솔이 정지·활성 0대를 표시했다. 이것은 §6-A가 관리 평면과 봇 플릿의 동거를 전제로 쓰였는데 배포가 둘을 물리 분리한 데서 나온 공백이다. V22가 관리에서 봇으로 가는 제어 방향만 이었고 반대 방향이 없었다. `published`는 `observed_at`(§2-1 UTC 밀리초 3자리)과 `stale`을 함께 낸다. `stale`은 발행이 끊겨 스냅샷이 늙었다는 뜻이고 판정은 DB 시계 하나로만 한다. 신선도 임계는 10초다. `fleet_present`는 종전 정의(로컬 거버너 주입 여부)를 유지하므로 `published`에서 `false`다. `local`·`published`는 1.2 필드를 그대로 실으므로 구 소비자에게 additive다. `unknown`만 필드 제거이며, 그 상태는 종전 구현이 거짓을 싣던 자리라 하위호환보다 정직을 택한다.
**봇 유형**: `MARKET_MAKER`(유동성·재고 스큐) / `TAKER` / `TREND`(모멘텀·역추세 이원 페르소나, 봇_방향성 §2-1-a) / `NOISE`. 코드 경로는 둘(MarketMakerBot·TakerBot)이고 TREND·NOISE는 TakerBot 프리셋으로 실현한다. 가격은 **OU 평균회귀 + 점프 프로세스**로 fair value만 제공, 체결가는 오더북에서 창발.

**`POST /sim/bots` 응답 계약(v2.11, governor set-point 반영)**: 이 경로는 개별 봇 인스턴스를 즉시 기동하지 않고 인메모리 governor의 유형별 목표 대수를 `count`만큼 상향한다(캡 클램프 적용). 실제 인스턴스는 별도 `bots` 프로파일이 목표 대수를 좇아 기동하므로 이 경로는 인스턴스 id를 발급하지 않는다. 따라서 `bot_ids`는 상시 빈 배열이다. `created`는 클램프 후 순증분, `applied_target`은 클램프 후 해당 유형 목표, `fleet_total`은 전 마켓 목표 대수 합계, `clamped`는 캡에 걸렸는지, `fleet_present`는 governor 주입 여부다. governor 부재 배포는 `count`를 그대로 `created`·`applied_target`·`fleet_total`로 보고하고 `fleet_present:false`를 준다. Cluster B는 `created`·`fleet_total`로 결과를 표시하고 `clamped`로 캡 도달을 안내한다.

**set-point 제어(v2.10, 봇_방향성 §5·체결_시각효과 §5-6 정본 반영)**: `PUT /sim/scale`·`/sim/bias`·`/sim/effects`는 개별 봇 생성/제거를 대체하지 않고 그 위에 마켓·유형 단위 목표값을 얹는 상위 제어다. 셋 다 파괴 액션이 아니므로 approval_token 불요이고 감사 로그만 적재한다(§3-6 필수 완결 열거 불변, 신규 필수 액션 없음). `/sim/effects`의 `trade_flash`는 공개 `GET /api/v1/exchange-config`(§4-A-6)에 반영된다. `GET /sim/status`는 schema를 1.1로 올려 마켓별 `target/measured/effective_bias`, `auto_balance`, 유형별 `scale{target,active}`, `fill_ratio`, `trade_flash`를 additive로 더한다(기존 1.0 소비자 무영향).

**전체 제어(v2.22, `POST /sim/start|stop|pause` 계약 확정)**: 세 명령은 봇 플릿 수명주기를 실제로 움직인다. `start`는 미기동이면 기동, 일시정지 상태면 해제(재기동 아님). `stop`은 플릿 정지(스레드 종료, pause 잔류 없음). `pause`는 토글이며 봇 스레드·시드·오더북 상태를 유지한 채 발행 게이트(전역 OPS 버킷)만 닫아 주문·취소 발행을 멈춘다. 셋 다 멱등 수렴형이라 approval_token 불요·감사 적재는 불변이다. 응답은 schema 1.1: `{ "schema_version":"1.1","command":"pause","applied":true,"running":true,"paused":true,"fleet_present":true }`. `applied`는 명령이 플릿에 반영됐는지(수명주기 플릿 빈 부재 배포는 항상 false, 미기동 플릿에의 `pause`도 false), `running`·`paused`는 실행 후 상태, `fleet_present`는 수명주기 플릿 빈 주입 여부다. `GET /sim/status`는 schema를 1.2로 올려 top-level `running`·`paused`를 additive로 더한다(기존 1.1 소비자 무영향). status의 `fleet_present`는 종전대로 governor 주입 여부이고 제어 응답의 `fleet_present`는 수명주기 빈 기준이므로, 봇 프로파일이 같은 JVM에 켜진 배포에서 둘은 일치한다. 관리 평면을 물리 분리한 배포에서는 둘 다 `false`이며, 그때 status가 무엇을 내는지는 위 원천 규약(schema 1.3)이 규정한다.

**시나리오 프리셋(v2.31, `POST /sim/scenarios/{name}/activate` 계약 확정)**: 프리셋은 시장 국면 하나를 전역 OPS·마켓별 유형 대수·마켓별 bias에 동시에 얹는 상위 set-point다. 기존 `/sim/rate`·`/sim/scale`·`/sim/bias`를 한 번에 호출하는 것과 같고, 그 셋으로 도달할 수 없는 상태는 만들지 않는다. 따라서 파괴 액션이 아니며 approval_token 불요·감사 적재다(§3-6 완결 열거 불변). 프리셋도 목표 OPS를 바꾸므로 `PUT /sim/rate`와 같은 관리 JVM 잠금 안에서 적용하고 감사(`sim.scenario`)를 남긴다. 관제센터를 켠 배포에서는 이 감사가 발행률 설정(`sim.rate`)과 함께 AI 봇 부하 줄임 조치의 쿨다운을 시작시킨다(v2.52.4, §6-F).

- **기준선 상대값**: 프리셋 값은 절대 수치가 아니라 **기준선 대비 배수**다. 기준선은 봇 플릿 기동 시 `registerMarket`이 등록한 유형별 대수와 `OpsGovernor` 생성 시 목표 OPS다. 배포마다 기준 규모가 다르므로(하네스 normal 140봇 대 데모 소규모) 절대값을 못 박으면 한쪽에서 무의미해진다.
- **멱등**: 기준선은 등록 시점 값으로 고정되고 활성화 시점의 현재 값을 쓰지 않는다. 같은 프리셋을 두 번 활성화한 결과는 한 번과 같다. 활성화가 누적되면 `spike` 3회가 기준선 OPS의 1000배가 되어 제어면이 아니라 폭주기가 된다.
- **복귀 경로**: `steady`가 전 배수 1.0이라 기준선 복귀 명령을 겸한다. 발표 중 어느 프리셋에서든 `steady`로 되돌린다.
- **레짐 불간섭**: 프리셋은 변동성 레짐(CALM/TREND/SHOCK)을 건드리지 않는다. 레짐 시퀀스는 마켓별 시드 PRNG가 `controlStep`당 정확히 1회 전진시키는 재생 결정론의 일부다. 외부 강제는 같은 시드가 같은 시퀀스를 낳는다는 계약을 깬다.
- **적용 범위**: 등록된 전 마켓에 적용한다. 대수는 `PUT /sim/scale`과 동일하게 유형·마켓·전역 캡으로 클램프하며, 한 마켓이라도 걸리면 응답 `clamped:true`다. 기준선이 등록되지 않은 마켓은 대수를 건드리지 않고 bias만 적용해 `scale_applied:false`로 보고한다(기준선 0을 곱해 봇을 조용히 0으로 만드는 것을 막는다).

| 프리셋 | OPS | 테이커 계열 λ | MARKET_MAKER | TAKER | TREND | NOISE | target_net_bias | auto_balance | strength | 국면 |
|---|---|---|---|---|---|---|---|---|---|---|
| `steady` | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 0.00 | on | 0.5 | 기준선. 평상 운영이자 복귀 경로 |
| `volatile` | 1.5 | 1.5 | 0.6 | 1.3 | 1.5 | 1.2 | 0.00 | on | 0.15 | 방향 없는 진폭. 호가를 얇게 해 같은 주문이 더 큰 이동을 만든다. 균형 제어는 살아 있되 느슨 |
| `ramp` | 2.0 | 2.0 | 1.0 | 1.4 | 2.0 | 1.0 | +0.35 | on | 0.3 | 완만한 추세. 균형 제어가 붙어 있어 서서히 오른다 |
| `spike` | 10.0 | 10.0 | 1.0 | 1.0 | 1.0 | 1.0 | +0.60 | off | 0.5 | 급등·폭주. 하네스 `spike 10x`의 OPS 축과 같은 크기 |
| `selloff` | 10.0 | 10.0 | 1.0 | 1.0 | 1.0 | 1.0 | -0.60 | off | 0.5 | 급락·투매. `spike`의 방향 대칭. 부하 축은 동일 |
| `thundering-herd` | 12.0 | 12.0 | 1.0 | 1.0 | 1.0 | 1.0 | +0.70 | off | 0.5 | 전 봇이 한 방향으로 몰리는 순간 버스트 |

- **`selloff`는 `spike`의 방향 대칭이다(v2.33)**. 부하 축(OPS·λ·대수)이 `spike`와 완전히 같고 `target_net_bias`의 부호만 반대다. 부하와 방향은 직교하는 축이라, 백프레셔·rate limit 관찰은 어느 방향에서든 같은 것을 잰다. 따라서 이 프리셋은 새 부하 프로파일이 아니고 하네스_검증설계 §2-1 표에 행을 더하지 않는다. 두는 이유는 관측 대상이 아니라 재현 대상이다. 거래소 사고는 상승보다 급락에서 나고(투매·일방 호가·손절 연쇄), 프리셋이 전부 양전이면 운영 콘솔이 한쪽 국면만 재현할 수 있다. 방향 자체는 `PUT /sim/bias`로 이미 [-1, +1] 전 구간에 도달 가능했고, 이 프리셋은 그 도달점을 부하와 묶어 한 번에 놓는 단축일 뿐이다(위 "그 셋으로 도달할 수 없는 상태는 만들지 않는다" 불변).
- **부하 국면은 대수를 건드리지 않는다**. `spike`·`thundering-herd`의 대수 배수가 전부 1.0인 것은 하네스_검증설계 §2-1 프로파일표가 두 국면을 각각 "동일 플릿"·"전 봇"으로 규정하기 때문이다. 대수를 만지면 하네스가 고정한 플릿을 흔든다. 구성으로 국면을 만드는 것은 하네스 표에 없는 `volatile`·`ramp`가 맡는다.
- **두 축을 함께 민다(λ·OPS)**. §2-1 spike 행은 크기를 두 축으로 준다. 전역 목표 OPS 기저의 10배, 그리고 "TAKER λ 10배". 프리셋은 둘 다 집행하고 배수를 같게 둔다. 전역 버킷(`OpsGovernor.acquire`)은 발행 상한이고 실제 발행량은 `min(봇 수요, target_ops)`이므로 한쪽만 올리면 배수가 그대로 나오지 않는다. 상한만 올리면 처리량이 수요 포화점에서 멈춰 배수에 못 닿고, 수요만 올리면 상한이 그대로 막는다.
- **λ 배수는 레짐과 분리한다**. λ 스칼라는 원래 레짐이 소유한다(`BotFleetGovernor.regimeLambdaMult`). 레짐 시퀀스는 마켓별 시드 PRNG가 `controlStep`당 1회 전진시키는 재생 결정론의 일부라 프리셋이 덮으면 계약이 깨진다. 그래서 레짐과 곱해지는 별도 스칼라 `scenarioLambdaMult`를 둔다. 기본값 1.0이고 프리셋 미적용 배포는 기존과 바이트 단위로 같다. `TakerBot.interarrivalMs`는 λ 값과 무관하게 난수를 1회만 소비하므로 배수를 바꿔도 결정 열이 바뀌지 않는다.
- **λ 배수는 도착률에만 싣는다**. `TakerBot`의 `lambdaMult`는 도착 간격과 버스트 확률(`P_BURST`) 두 곳에 쓰이는데 프리셋은 앞쪽만 곱한다. 버스트 확률은 레짐 활성도 신호이고 상한이 0.5라, 10배를 곱하면 즉시 상한에 붙어 레짐의 동적 범위가 사라진다. 두 신호를 섞지 않는다.
- **λ 배수의 적용 범위는 테이커 경로 전체다**. §2-1 표가 λ를 TAKER 행에 적었지만 이 저장소에서 λ는 처음부터 TAKER 전용이 아니다. TREND·NOISE는 별도 클래스가 아니라 `TakerBot` 프리셋으로 실현되고(§6-A 봇 유형 절), 세 유형이 같은 `taker.lambda()`를 읽는다. 유형별로 갈리는 것은 확신 게인과 주문 크기 배수뿐이다. 따라서 배수도 테이커 경로 전체에 걸린다.
- **MM requote 주기는 건드리지 않는다**. λ는 테이커 경로의 파라미터이고 MM은 자체 호가 갱신 주기를 쓴다. 테이커만 빨라지면 호가가 먹히는 속도가 공급 속도를 앞지른다.

- `steady`는 OPS·대수를 즉시 기준선으로 되돌리고, bias는 균형 루프가 다음 주기부터 0으로 수렴시킨다(auto on 계약, 봇_방향성 §4-5). 방향이 뚝 끊기지 않고 잦아든다. 반대로 `spike`·`thundering-herd`는 auto off라 목표 bias가 즉시 반영된다.
- `ramp`는 k6 stage의 요청률 램프업과 이름이 겹치나 뜻이 다르다. 여기서는 시장 국면의 완만한 추세이고, 부하 램프업은 하네스가 담당한다.
- bias 부호는 매수 쏠림이 양수다(상승·매수 Red 규약과 정합). `auto_balance:off` 행의 `strength`는 저장되지만 균형 루프가 돌지 않아 무효다.
- 대수 배수는 기준선에 곱해 반올림한다. 기준선이 0인 유형은 0으로 남는다.
- OPS 상한을 따로 두지 않는다. 최대 배수 12.0이 만드는 값은 `PUT /sim/rate`로 이미 도달 가능하므로 프리셋이 새 위험을 만들지 않는다. λ를 함께 올려도 발행량은 전역 버킷이 `target_ops`로 묶으므로 봇이 상한 밖으로 나가지 않는다. 수용 제한(§2-5 토큰버킷·백프레셔)이 폭주 국면의 방어선이고, 그 발동을 보는 것이 `spike`의 목적이다.

```json
// POST /admin/v1/sim/scenarios/spike/activate  (operator_id 필수, approval_token 불요)
{ "operator_id":"op-02" }
// 200
{ "schema_version":"1.1", "scenario":"spike", "apply_path":"local",
  "target_ops":"300.0", "base_ops":"30.0",
  "auto_balance":false, "target_net_bias":"0.60",
  "markets":[
    { "market":"KRW-BTC", "scale_applied":true,
      "applied":{"MARKET_MAKER":40,"TAKER":30,"TREND":20,"NOISE":50},
      "effective_bias":"0.60" } ],
  "clamped":false, "fleet_present":true, "queued_command_id":0 }
// 200, 관리 평면에 봇 거버너가 없을 때(물리 분리 배치)
{ "schema_version":"1.1", "scenario":"spike", "apply_path":"queued",
  "auto_balance":false, "target_net_bias":"0.60",
  "fleet_present":false, "queued_command_id":7 }
```
알 수 없는 프리셋 이름은 `404 not_found`다(`/sim/{command}`의 미지 명령과 동일 규약).

**`apply_path`(v2.33 신설)**: 봇 거버너는 bots 프로파일 JVM의 인메모리 상태이고 관리 평면은
별도 컨테이너다(관리자 설계 §0 물리 분리). 같은 JVM이면 `local`로 즉시 반영하고, 아니면
`sim_commands`(DB§V22)에 의도를 적재하고 `queued`를 낸다. 봇 JVM이 폴링해 적용하므로
반영까지 최대 1초 지연된다.

**이 절의 생략 규정은 `POST /sim/scenarios/{name}/activate` 한정이다.** `queued`에서
`target_ops`·`base_ops`·`markets`·`clamped`를 **내지 않는다**. 그 값들은 기저 OPS와 마켓별
기준선에서 파생하므로 봇 거버너만 계산할 수 있고 관리 평면이 아는 값이 없다. 종전에는
`base_ops`가 `0.0`으로 고정돼 거기서 나온 `target_ops` `0.0`을 응답과 감사에 남겼고, 봇이
실제로 적용한 값과 달랐다. 빈 배열도 "0개 마켓에 적용했다"는 주장이고 `clamped:false`도
"캡에 걸리지 않았다"는 주장이다. 없는 값은 비운다.

`auto_balance`·`target_net_bias`는 `queued`에서도 낸다. 이 둘은 프리셋 정의값이라
거버너 없이도 정확하다. 요청한 프리셋이 무엇인가의 서술이지 적용 결과가 아니다.

`PUT /sim/rate`는 `apply_path` 규약만 같고 생략 규정은 다르다. 응답은
`{schema_version:"1.1", target_ops, applied, apply_path, queued_command_id}`이고 `target_ops`를
`queued`에서도 낸다. 그 값은 요청 바디의 에코라 관리 평면이 아는 값이기 때문이다. 파생값이
아니므로 생략할 이유가 없다. `applied`는 "이 응답 시점에 봇에 반영됐는가"라 `queued`면
`false`다.

### 6-A-a. 용량 API

이 절은 현재 남아 있는 수동 용량 API와 Docker 조정기의 구현 계약이다. 2026-09-18 [ADR 0027](../adr/0027-msp-operations-and-autoscaling.md)은 AWS 조회 계층의 지표 기반 자동 확장을 채택해 ADR 0015의 수동 전용 정책을 대체했다. 아래 경로·응답은 아직 ASG 제어 API로 바뀐 것이 아니다. AWS에서는 ASG 정책이 용량을 제어하고 실제 상태·활동 이력을 관제한다. 이 레거시 조정기가 같은 AWS 대수를 동시에 강제하지 않게 전환해야 한다.

기존 수동 구현에서는 판정은 서버가 하고 실행은 사람이 누른다.

| 메서드 | 경로 | 기능 | 역할 | approval_token |
|---|---|---|---|---|
| GET | `/admin/v1/capacity` | 계약·관측·권고를 한 번에 | VIEWER+ | 불요(조회) |
| POST | `/admin/v1/capacity/scale-out` | 희망 대수 +1 | SUPER | 불요(잠정) |
| POST | `/admin/v1/capacity/scale-in` | 희망 대수 -1 | SUPER | 불요(잠정) |

이 API 는 노드를 직접 띄우거나 내리지 않는다. `capacity_plan.desired_nodes` 를 바꾸고
조정기가 그것을 맞춘다. 카오스 API 가 `chaos_commands` 에 적재하고 주입기가 소비하는 것과
같은 경계다. 그래서 호출 직후에는 계약과 관측이 어긋난 창이 실재하고, 응답의 `reconciled`
가 그 사실을 그대로 낸다. 감추지 않는다.

응답 축이 셋이다. `plan` 이 계약(min·desired·max), `nodes[]` 가 노드별 계약과 관측,
`advice[]` 가 권고다. 노드 객체는 `expected` 까지가 계약이고 `reachable` 부터가 관측이며,
서버가 읽지 못한 지표는 0 이 아니라 `null` 로 낸다. "사용률 0%" 와 "사용률을 못 읽었다" 는
다른 사실이고, 합치면 화면이 죽은 노드를 한가한 노드로 그린다.

`thresholds` 가 권고 임계를 함께 낸다(`scale_out_cpu_ratio`·`scale_in_cpu_ratio`). 화면이 그 값을 복제하지 않게 하려고 응답에 싣는다. 종전에는 게이지 눈금이 0.60 을 코드에 박고 있어서 서버가 환경에 맞춰 값을 조이면 판정과 눈금이 갈렸다. 기본값은 계약값 0.60/0.30 이고 `exchange.capacity.scale-out-cpu`·`scale-in-cpu` 로 환경마다 덮는다. 로컬 카오스 스택은 조회 부하의 천장이 초당 400 건 근처이고 그 위는 수용 제어가 429 로 흘려보낸다. 같은 부하에서도 워밍이 끝난 스택의 조회 계층 평균은 0.33, 재기동 직후는 0.58~0.86 으로 편차가 컸다(2026-09-23 실측). 0.60 을 기준으로 두면 워밍된 상태에서 권고가 나오지 않으므로 `admin-chaos` 프로파일이 0.25/0.10 으로 내린다. AWS 운영값은 기본값이며 두 값을 같은 것으로 적지 않는다.

`advice[].action` 이 `SCALE_OUT`·`SCALE_IN`·`null` 이다. `null` 은 표시만 하는 권고이고
조치 버튼이 붙지 않는다. 권고는 정본 7.5 가 규정한 대로다. 판정은 자동이고 실행은
사람이다. 이 레거시 경로의 권고 자체에는 자동 액션이 없다. 새 AWS 확장 정책의 자동 실행은 ADR 0027을 따른다.

경계를 넘는 요청은 409 `CAPACITY_OUT_OF_BOUNDS` 다. DB `chk_capacity_bounds` 가 같은 경계를
한 번 더 걸지만 컨트롤러가 먼저 막는다. 그래야 화면이 제약 위반 메시지 대신 사람이 읽을 수
있는 사유를 받는다. 거부도 감사에 `DENIED` 로 남는다.

역할이 잠정이다. 용량 변경은 비용을 쓰는 인프라 조치라 SUPER 로 둔다. ADR 0015 가 승인
정책을 결손으로 등재했고 그 판정이 서면 이 표와 컨트롤러를 함께 고친다.

확장 대상은 조회를 받는 앱 계층뿐이다. 쓰기는 매칭엔진이 단일 라이터라 노드를 늘려도 천장이
오르지 않고(2026-09-07 실측 56.8/s -> 54.3/s), DB 는 primary 가 하나여야 한다. 리더가 죽은
상태에서 노드를 늘려도 쓰기는 재개되지 않는다. 늘어나는 노드는 `exchange.engine.enabled=false`
인 조회 전용이다. ADR 0030 사용자 정정(2026-09-25)에 따라 앱 계층은 늘 켜진 app-1·app-2와 조회 전용
app-3을 합친 2~3대이고, `capacity_plan`의 `app` 행 초기값(V32) min 2·desired 2·max 3이 그 경계다. 증설은
app-3 켜기, 축소는 app-3 끄기다. 권고 문장도 이 말로 쓴다(v2.52.7, 합니다체).

**관제센터 설계와의 관계(v2.52.1, 권고 임계와 잠금은 구현, app-3 켜기·끄기 조치는 v2.52.2 구현).** 관제센터(`exchange.msp.enabled=true`)를 켠 배포에서는 권고 임계의 원천을 설정에서 임계값 레지스트리(§6-F, DB 설계 §10)의 `read.scale_out_cpu`·`read.scale_in_cpu`로 옮긴다. 끈 배포는 지금처럼 `exchange.capacity.scale-out-cpu`·`scale-in-cpu`(기본 0.60·0.30)를 읽고 새 표를 읽지 않는다. 앱이 Flyway를 끄는 배포에서 새 JAR가 V70보다 먼저 떠도 이 경로가 없는 표 때문에 실패하지 않게 하기 위해서다. 응답의 `thresholds` 두 숫자는 어느 쪽이든 모양을 바꾸지 않는다. 켠 배포에서 두 설정값은 환경 기본값이 되어, 레지스트리 항목을 사람이나 AI가 바꾼 적이 없을 때만 기동 시 그 값으로 맞춘다. 증설·축소의 경계 검사와 `capacity_plan` 갱신은 한 서비스(`CapacityPlanService`)가 맡고 관제센터의 app-3 켜기·끄기 조치(§6-F `READ_NODES`)도 그 코드를 다시 쓴다. 이 서비스는 `capacity_plan`을 `FOR UPDATE`로 읽는다. 2026-09-25 전의 구현은 잠금 없이 읽은 값에 1을 더한 절대값을 써서, 동시에 누른 두 변경이 둘 다 같은 전이로 성공하고 한 변경을 잃었다. 지금은 겹친 두 증설 가운데 하나만 반영되고 다른 하나는 `409 CAPACITY_OUT_OF_BOUNDS`다. 경계를 넘은 거부의 감사(`DENIED`)는 트랜잭션 밖에서 남겨 롤백으로 사라지지 않는다. app-3 켜기·끄기 조치는 `read-capacity.mode=CAPACITY_PLAN`인 로컬 Docker에서만 허용되고, 자동 모드(ADR 0035, 사용자 확정 2026-09-24)에서는 사람 버튼 없이 1대씩 바뀔 수 있다. 위 권고 자체에 자동 액션이 없다는 서술은 그대로다. AWS에서는 ASG 정책이 대상당 요청 수로 대수를 정하므로 CPU 권고 임계는 그 판단에 쓰이지 않고, 관제센터가 이 경로로 대수를 바꾸지 않는다.

### 6-B. 카오스 주입 API

| 메서드 | 경로 | 기능 | approval_token(§3-6) |
|---|---|---|---|
| POST | `/admin/v1/chaos/network` | Toxiproxy toxic(latency/down/timeout/bandwidth/reset_peer) | 불요(제시 시 검증·소각) |
| DELETE | `/admin/v1/chaos/network/{toxic}` | toxic 해제 | 불요(제시 시 검증·소각) |
| POST | `/admin/v1/chaos/app` | Chaos Monkey assault(latency/exception/appkiller) | 불요(제시 시 검증·소각) |
| POST | `/admin/v1/chaos/kill-node` | 매칭 노드 강제 종료(docker kill) | 불요(제시 시 검증·소각) |
| GET | `/admin/v1/chaos/experiments` | 진행 중 실험·blast radius | 불요 |
| POST | `/admin/v1/approvals` | approval_token 발급(1인 승인. 발급자와 사용자가 같아도 된다, v2.44 §3-6. 종전 요청자와 **다른** operator의 2인 승인, v2.3 §3-6) | 불요(발급 자체) |
| GET | `/admin/v1/approvals` | 승인 원장 조회(state 필터, VIEWER+). `approval_token`은 응답에서 제외 | 불요(조회) |
| POST | `/admin/v1/approvals/{id}/reject` | ISSUED 승인 거부(`ISSUED→REJECTED`). 호출 주체는 그 행의 approver 또는 requester여야 한다(소유·반결탁) | 불요(de-escalation) |

**카오스 승인 완화(v2.34)**: 카오스 주입·해제 4종을 필수 열거에서 뺀다. 이유는 운영 규율이 아니라 운영 인원이다. 이 배포의 카오스 실행 주체는 1인이고, 2인 승인은 발급 주체와 사용 주체가 서로 달라야 성립한다(반결탁, DB `chk_approval_two_person`). 1인 운영에서는 그 조건을 만족시킬 방법이 없어 실험을 아예 못 돌리거나 운영자 계정을 하나 더 만들어 혼자 두 역을 하는 우회가 된다. 뒤쪽은 2인 통제를 형해화하면서 형식만 남기므로 규정을 사실에 맞춘다.

무엇이 남는가. `approval_token`을 **제시하면 여전히 검증·소각한다**(action·대상 바인딩, TTL, 단회성, 반결탁 전부 그대로). 2인 운영 배포는 토큰을 실어 종전 계약대로 쓴다. 감사 적재는 승인 유무와 무관하게 전 액션 대상이고, `CHAOS_OPS` 역할 게이트와 대상 문자열 검증(`requireSafeTarget`)도 그대로다. 즉 빠진 것은 "다른 사람의 승인"이라는 한 겹뿐이고, 누가 무엇을 언제 주입했는지는 그대로 남는다.

파괴성 자체는 줄지 않았다. 화면은 이 액션들을 계속 파괴 액션으로 표시하고 확인 다이얼로그를 유지한다.

승인 라이프사이클(§3-6): `ISSUED → CONSUMED | EXPIRED | REJECTED`. 발급·사용·만료·거부 전이는 모두 admin_audit_log에 기록된다. `GET /approvals`는 원장 프로젝션이며 `approval_token`을 절대 반환하지 않는다(토큰은 발급 응답 단일 경로의 bearer 시크릿). `reject`는 `consume`과 대칭으로 `FOR UPDATE` 행 잠금·소유 검증을 거치고 `consumed_at`을 건드리지 않는다.

```json
// POST /admin/v1/chaos/kill-node  (operator_id 필수, approval_token 불요. v2.34 완화)
// approval_token을 실으면 종전 계약대로 검증·소각한다. 단회용·TTL 5분·(action=kill-node, node=chaos-api-1) 바인딩(§3-6, POST /admin/v1/approvals)
{ "operator_id":"op-01","approval_token":"apv-8842",
  "node":"chaos-api-1","method":"docker_kill","expect_failover":true }
// 202
{ "experiment_id":"E5-2026-0721-01","killed_at":"2026-07-21T09:05:00.000Z",
  "grafana_annotation":"anno-88" }
```
**실험 카탈로그(E1~E6)**: DB 지연/단절, 예외 폭풍, AppKiller, **노드 kill→페일오버(핵심 데모)**, 폭주+kill 동시. 각 실험은 자동으로 Grafana annotation을 삽입해 지표 귀속. 페일오버 실험(E5)의 PASS 판정에는 구 리더 잔존 프로세스의 주문·취소·체결·스냅샷 쓰기 시도가 전부 0행 또는 예외로 차단되었는지 확인(불변식 ⑦, 하네스 V3 회귀 테스트)을 포함한다. (앱 계층=Chaos Monkey 3.x[SB3], 네트워크=Toxiproxy, 인프라=docker kill + restart policy.)

### 6-C. 헬스/복구 상태(RTO/RPO) API

| 메서드 | 경로 | 기능 |
|---|---|---|
| GET | `/actuator/health/liveness` | 생존 프로브 |
| GET | `/actuator/health/readiness` | 준비 프로브(페일오버 시 `REFUSING_TRAFFIC`). 이 경로를 보는 것은 쓰기 대상 그룹뿐이므로 거절한 노드는 **쓰기 대상 그룹에서만** 빠진다. 조회 대상 그룹은 아래 `/actuator/health/read` 로 판정하므로 팔로워가 남는다(정본 6.4) |
| GET | `/actuator/health/read` | 조회 프로브. 팔로워도 조회는 정상으로 내므로 조회 대상 그룹은 `readiness` 대신 이 경로를 본다. `readiness` 를 쓰면 팔로워가 대상 그룹에서 빠져 조회 분산이 성립하지 않는다(`application.yml` 헬스 그룹 주석, 2026-09-07 실측) |
| GET | `/admin/v1/ha/status` | 리더/팔로워·epoch·seq 정합 |
| GET | `/admin/v1/ha/recovery` | 복구 판정 객체 + `recovery_runs[]` + `failover_runs[]`(불변식은 방출하지 않는다. 아래 실제 방출 문단 참조) |
| GET | `/admin/v1/ha/invariants` | `invariant_checks`의 `check_name`별 최신 행(`ok`·`violated_rows`·`checked_at`). 저장 행 조회이지 호출 시점 재검증이 아니다 |
| GET | `/actuator/partitions` | (v2.55, ADR 0034 S4) 이 노드의 파티션 상태. 파티션마다 `resource`, `state`(`FOLLOWER`\|`PROMOTING`\|`ACCEPTING`\|`REBUILDING`\|`BLOCKED`\|`DEMOTED`), `epoch`(쥐고 있으면), `participate`, `preferred`, 최근 강등 사유와 차단기 계수, 그 파티션의 마켓 목록을 낸다. (v2.56) 강등 기록의 `detail`은 사유, 원인 예외 이름, 원인 사슬에서 처음 만난 SQLSTATE를 싣는다(`WRITER_FATAL: UncategorizedSQLException sqlstate=55P03` 꼴, 롤백 실패 뒤의 원래 예외면 `sqlstate=08006(앱 예외)`, 없으면 `sqlstate=-`). `DEMOTED`는 노드 치명(VM 오류) 뒤의 종신 상태다. 읽기라 actuator가 열린 곳에서 부를 수 있다. 운영(prod)의 actuator는 8081 관리 포트에 있다 |
| POST | `/actuator/partitions` | (v2.55) 운영자 조작. 바디 `{"resource":"matching-p0","participate":true\|false}`는 그 파티션의 참여를 켜고 끈다. 끄면 계획 강등(갱신을 멈추고 리스가 만료되게 둔다)이고 켜면 다시 후보가 된다. (v2.56) 끈 뒤에는 재구성 완료나 차단기 해제와 엇갈려도 이 노드가 그 파티션의 후보로 돌아오지 않는다. 참여 여부와 후보 표식은 승격 조율의 상태 전이와 같은 락 안에서 바뀐다(DB 설계 §5.4-c 9). `{"resource":...,"reset_breaker":true}`는 차단기를 푼다. `DEMOTED`는 풀지 않는다. 전환마다 WARN 로그 한 줄(요청 원격 주소, before, after)을 남긴다. 선호 노드로 되돌리는 재배치(ADR 0034 결정 4)는 이 조작으로 한다. **원격 주소가 loopback(127.0.0.0/8, ::1)인 요청만 받고 나머지는 `403`이다.** 원격 주소는 신뢰 프록시 규칙(§2-5)을 거친 값이다. 운영자는 노드 안에서 localhost로 부른다. 앱이 막는 이유는 dev 프로파일(카오스 스택)의 actuator가 8080에 있어 LB와 호스트 포트로도 닿기 때문이다(2026-09-24 설계 검토, S4 설계 11절 R4) |

```json
// GET /admin/v1/ha/status → 200
{ "role":"LEADER","node":"chaos-api-1","epoch":42,
  "last_applied_seq":88123606,"follower_lag_seq":3,"snapshot_seq":88100000,
  "matching_market_owner":{"KRW-BTC":"chaos-api-1"} }

// GET /admin/v1/ha/recovery → 200 (이상형. 실제 방출은 아래 대조 문단이 정본)
{ "last_experiment":"E5-2026-0721-01","rto_seconds":3.2,"rpo_records":0,
  "failover":{"old_leader":"chaos-api-1","new_leader":"chaos-api-2",
              "old_epoch":42,"new_epoch":43},
  "invariants":{"sequence_gapless":true,"orders_lost":0,"duplicate_fills":0,
                "asset_conservation":true,"fill_integrity":true,"replay_state_hash_match":true,
                "fencing_violations":0},
  "verdict":"PASS" }
```
> `/admin/v1/ha/status` 실제 방출(단일 라이터 데모, 관리자 §2-3 읽기 전용): `schema_version`(1.1, v2.50), `lease{holder,epoch,lease_until,leader_alive,lease_remaining_ms}`, `command_watermark`(표시용 전역 최대), `command_watermarks[]{market_id,command_watermark,apply_lag}`(마켓별 명령 head 와 적용 지연, v2.50), `event_watermark`, `applied_command_watermark`, `apply_lag`(마켓마다 명령 head 에서 물질화 경계를 뺀 값의 최대, v2.50), `apply_lag_total`(그 합, v2.50), `snapshot_seq`, `trades_count`, `recovery_runs_count`, `last_failover{downtime_seconds,rto_seconds,verdict,at}`. 전부 DB 실적재분이고 시각은 §2-1 밀리초 UTC다. 이상형 예시의 `role`·`node`·`follower_lag_seq`·`matching_market_owner`는 노드 팔로워 레지스트리·복제 큐가 실재해야 하나 본 구조(단일 라이터 + 저널 재생 복구)에 없어 방출하지 않는다(미추적 필드 날조 회피). 리더 권위는 `leader_lease` DB 직조회다. 확장은 기존 필드 불변의 additive만 한다. v2.50 의 `apply_lag` 재정의는 이 규칙의 예외라 응답 `schema_version` 을 1.1 로 올렸다. 스칼라 워터마크 셋은 표시용 전역 최대라 서로 빼지 않는다. (v2.55, ADR 0034 S4) additive로 `leader_protocol`(DB 모드 1·2·3)과 `leases[]{resource,holder,epoch,lease_until,leader_alive,lease_remaining_ms,markets[]}`를 더한다. `leases[]`는 그 모드가 쓰는 리스 자원만 담는다(P1·P2는 `matching-engine` 한 행, P3는 `matching-p0`·`matching-p1`). `markets[]`는 그 자원이 덮는 마켓 심볼이다. 기존 `lease` 객체는 `leases[]`의 첫 행과 같다. 필드 뜻이 바뀌지 않으므로 `schema_version`은 1.1 그대로다. (v2.59, ADR 0034 S7) P3에서 `lease`와 스칼라 `epoch`는 `matching-p0` 행의 값이라 두 파티션의 상태를 대표하지 않는다. P3의 리더 상태는 `leases[]` 두 행과 노드마다의 `GET /actuator/partitions`로 읽는다. 응답 모양은 바뀌지 않는다.

> `/admin/v1/ha/recovery` 실제 방출(v2.33, 스키마 1.2): `schema_version`, `verdict{...}`, `recovery_runs[]`, `failover_runs[]`. (v2.55) `failover_runs[]` 행에 `resource`(그 사건이 옮긴 리스 자원, DB 설계 §3.8 V53)를 additive로 더한다. V53 이전 행은 `null`이다. 이상형 예시의 `last_experiment`·`failover`·`invariants`는 실험 메타와 불변식 결과를 한 응답에 합친 형태이나, 그 둘은 각각 `GET /admin/v1/chaos/runs`와 하네스 `invariant_checks`가 원천이라 여기서 방출하지 않는다(미추적 필드 날조 회피).

> `verdict`는 **문자열이 아니라 객체**다. 이상형 예시의 `"verdict":"PASS"`는 단일 실험의 결론이고, 실제 방출은 표 전체(또는 `since` 창) 집계다. 필드는 다음과 같다. `scope`(`all`\|`since`), `since`(창 하한. `all`이면 빈 문자열), `replay_runs`, `replay_mismatched`, `replay_deterministic`, `failover_runs`, `rto_breach`, `rto_missing`, `rpo_breach`, `rpo_missing`, `row_verdict_fail`, `rto_target_seconds`(문자열 `"30.000"`. 같은 응답의 `rto_seconds`와 형을 맞춘다), `failover_ok`, `overall`. 판정 필드 셋(`replay_deterministic`·`failover_ok`·`overall`)은 `PASS`\|`FAIL`\|`UNKNOWN`이다. `failover_runs[]` 행의 `verdict` 컬럼은 그대로 문자열이며 하네스가 적재한 주행별 결론이다.

> **`UNKNOWN`은 통과가 아니다.** 표가 비었거나 `rto_seconds`·`rpo_records`가 NULL이면 `UNKNOWN`이다. 0행 대조로 통과를 내면 판정 대상이 없는데 판정이 통과한 상태이고, NULL을 통과로 세면 계측 실패가 성공으로 보인다. `FAIL`이 `UNKNOWN`을 이긴다.

> **집계는 배열이 아니라 표에서 한다.** `limit`은 `recovery_runs[]`·`failover_runs[]` 배열의 크기만 정한다. 배열을 세면 창 밖 위반이 안 보인다(기본 20에서 21번째 실패 행이 `PASS`가 된다). `since`(ISO-8601 순간)를 주면 그 시각 이후로 집계 범위를 좁히고 `scope`가 `since`가 된다. **`since`는 배열에도 걸린다.** 집계만 좁히면 판정은 `UNKNOWN`인데 표에는 창 밖 실패 행이 그대로 떠서 화면과 판정이 갈린다. `limit`은 창 안에서 몇 행을 보일지만 정한다. 파싱 실패는 `400 invalid_parameter`다. 조용히 전체 창으로 떨어뜨리면 오타 하나가 판정 범위를 넓히는데 응답은 그 사실을 말하지 않는다.

> **`since`가 필요한 이유**: `harness.failover_runs`는 append-only 누적이고 비우는 훅이 없다. 카오스 실험이 한 번 `verdict='FAIL'`을 적재하면 그 뒤로 `overall`이 영구히 `FAIL`이다. 창이 없으면 수동 DB 삭제 외에 복귀 수단이 없다. 기본값을 두지 않는 이유는 기본 창이 판정 범위를 조용히 정하기 때문이다. 좁히는 것은 호출자의 명시 선택이다.

> `rpo_records`의 의미(v2.6): ACK 이후 유실된 저널 레코드 수. 0의 보장 범위는 §7-6 하단과 동일하다. 로컬 fsync는 단일 인스턴스의 프로세스·전원 장애까지 보장하며, failover 포함 0은 DB§7.2 무손실 요건 충족 시에만 성립하고, 현행 상시면 구성(비동기 2노드, ADR 0024)은 그 요건을 충족하지 않는다.
**불변식 7종**(모두 SQL로 즉시 증명): ①시퀀스 무결(명령 저널 `seq` 결번 0. 레거시 구간 `seq < band_span`은 전역 `max(seq)-min(seq)+1==count(*)`, 대역 구간은 `market_id`마다 같은 식이고 둘의 결번 합이 0이다. 대역 사이 빈 구간은 결번이 아니다. 정본 SQL은 DB§6.3 ⑤. 결번 없음 전제=카운터 행 DB 원자 채번(레거시 `journal_counters`, 대역 `market_journal_counters`), §7-1 v2.6. 복수 worker의 할당·롤백·역순 커밋에서도 영구 결번 없음. 마켓마다 다른 지점에서 잘린 복원은 이 식을 통과한다, ADR 0022 9-16) ②주문 유실 0(ACK된 client_order_id ⊆ 저널·orders. 보장 범위는 §7-6 하단과 동일: 로컬 fsync는 프로세스·전원 장애까지, failover 포함 무손실은 DB§7.2 요건 충족 시이며 현행 상시면 구성은 미충족) ③중복 접수·중복 체결 0(v2.4 두 축 분리·대상 테이블 명기. (i) **주문 중복 접수 0**: `command_journal`의 **주문 생성 커맨드 한정**(§7-1 부분 유니크 축과 동일, v2.5) `(user_id,client_order_id)` 그룹당 1건(=UNIQUE 위반 0), (ii) **중복 체결 0**: `trades` 기준 `trade_id` 단위 중복 행 0 **및** 주문별 `SUM(trades.volume) ≤ orders.volume`. 한 주문의 다건 부분체결(§4-B-1 `trades_count`, §4-B-2)은 정상이며, 위반은 **동일 체결 이벤트의 이중 적용**만을 뜻한다. 원장 측 이중 적용은 `ledger_transactions`의 `UNIQUE(business_event_type, business_event_id)`(§7-1)가 DB 제약으로 선차단하므로 본 불변식 SQL은 그 제약의 사후 감사다) ④**자산 보존** 이중부기 `SUM(delta)`=0(v2.6 범위 명시: 수기 검증으로 확인한 경로는 LIMIT BUY taker 단일 시나리오다. market·best·IOC·FOK·POST_ONLY·부분체결 반올림 잔차를 포함한 전 주문 유형의 커버는 V3 무작위 회귀 테스트, 즉 무작위 가격·수량·부분체결 조합에서 자산별 합 0과 FILLED 주문 락 잔량 0 검증으로 위임한다) ⑤체결 정합(체결가=maker 지정가, `bestBid<bestAsk`, 수량 보존) ⑥재생 결정론(`recovery_runs.matched=true`. **v2.33에서 판정 축을 확정한다.** 판정 주체는 `ReplayService.verifyToMarket`(마켓판 대조 전용 재생, ADR 0023. 2026-09-14 ADR 0022 2단계 병합 판)이고 재생이 만든 이벤트를 `event_journal` 행과 하나씩 대조해 어긋나면 예외를 던진다. 대조 범위는 §7-2 v2.6 규약대로 외부 id·executed_at·event_ordinal까지다. 종전 정의였던 "저널 재생 상태 해시==라이브 해시"는 구현되지 않았고 앞으로도 두지 않는다. 대조가 두 벌이면 둘이 어긋날 때 어느 쪽이 정본인지 규정할 자리가 없어지고, 이벤트 단위 대조가 이미 더 촘촘하다. 상태 해시는 스냅샷 로드 무결 검증 전용이다(DB§5.5 v2.37). §4-4 매핑 표의 축 이름 `replay_state_hash_match`는 역사 표기다) ⑦fencing 무결(split-brain 차단): `event_journal`·`trades`·`snapshots`의 `fencing_epoch`가 커밋 시점의 `leader_lease` 승인 (holder, epoch)와 불일치하는 행 0. 리더 교체 후 구 epoch로 커밋된 자산 변경 행 0. 이 항목이 통과할 때에만 "split-brain 없음"을 주장한다(계약 정의 §7-1). (v2.59, ADR 0034 S7) 리더 모드 P3에서는 승인 (holder, epoch)가 그 행 마켓의 파티션 리스 행의 것이다. 판정은 하네스 검증설계 §4-4 ⑦(C22' ∧ C30 ∧ T4-2)이다. 사후 감사 C22'는 시간 축 팔을 P3 구간에서 파티션별로 보고, 모드 전환 뒤 시작한 트랜잭션이 옛 구간 epoch로 `command_journal`·`snapshots`에 쓴 행을 세는 구간 경계 팔을 더한 판이다. C30은 P3 epoch로 기록된 네 표 행의 획득 자원과 그 마켓 파티션을 대조한다. 전환을 가로지른 트랜잭션과 옛 구간 epoch의 `event_journal`·`trades` 쓰기는 사후 감사가 세지 못하므로 T4-2 전환 판(서버 시험)과 P3 실험의 구간 경계 증분이 본다(DB 설계 §5.4-e 5).

### 6-D. 마켓 구성·공지·운영자 API (관리자 설계 §3-1)

| 메서드 | 경로 | 기능 | approval_token(§3-6) |
|---|---|---|---|
| GET | `/admin/v1/config/markets` | 마켓 상태·수수료·주문 규칙 조회(VIEWER+) | 불요 |
| POST | `/admin/v1/config/markets/{symbol}/halt` | 마켓 정지(`ACTIVE→HALTED`) | **필수** |

**마켓 목록 투영 확장(v2.35)**: `GET /config/markets` 행에 `tick_size`·`min_notional`을 더한다. 둘 다 `markets` 테이블이 이미 들고 있는 값이고 조인이 없다. 운영자가 마켓을 정지·재개할 때 보는 것이 상태와 수수료만은 아니다. 호가 단위와 최소 주문 금액은 그 마켓이 어떤 격자로 도는지를 말하고, 수수료 변경 판단에서도 같이 읽힌다. 종전에는 이 값이 고객 평면 `GET /markets`에만 있어 운영자가 다른 평면을 열어야 했다.
| POST | `/admin/v1/config/markets/{symbol}/resume` | 마켓 재개(`HALTED→ACTIVE`) | **필수** |
| PATCH | `/admin/v1/config/markets/{symbol}/fees` | 수수료 부분 갱신(`NUMERIC(10,8)` 0~0.1, 스케일 8) | **필수** |
| GET | `/admin/v1/config/notices` | 공지 목록(active 포함, VIEWER+) | 불요 |
| POST | `/admin/v1/config/notices` | 공지 발급(SUPER) | 불요 |
| PATCH | `/admin/v1/config/notices/{id}` | 공지 부분 갱신·active 토글 | 불요 |
| DELETE | `/admin/v1/config/notices/{id}` | 공지 소프트 삭제(`active=false`) | 불요 |
| GET | `/admin/v1/operators` | 운영자·역할 목록(VIEWER+) | 불요 |
| POST | `/admin/v1/operators` | 운영자 생성(SUPER, 중복 409) | **필수** |
| PATCH | `/admin/v1/operators/{id}/status` | 신원 상태 전이(`ACTIVE\|DISABLED`) | **필수** |
| POST | `/admin/v1/operators/{id}/roles` | 역할 부여·회수(SUPER) | **필수** |
| GET | `/admin/v1/chaos/runs` | 카오스 주입 이력 프로젝션(토큰 미노출, VIEWER+) | 불요 |

- 수수료·마켓 상태의 정본은 `markets` 컬럼(`status`·`*_fee_rate`)이다. `DELISTED`는 종국 상태라 halt/resume 대상이 아니다(`409 market_delisted`).
- 공지 삭제는 소프트 삭제다. `app_role`에 `notices` DELETE 권한이 없어(DB 설계 §3.1, V5 GRANT는 SELECT/INSERT/UPDATE) 하드 DELETE는 운영 롤에서 `42501`로 상시 실패한다. `active=false` UPDATE로 노출만 내리고 게시 원본·감사 이력을 보존한다. 없는 id는 404, 이미 비활성이면 멱등 200.
- 수수료·마켓 halt/resume·운영자 생성/상태/역할은 approval_token 필수(§3-6 완결 열거). 부재·불일치·만료는 `403 admin_forbidden`. 검증 게이트(스케일·범위·중복·부재)는 승인 소각과 같은 트랜잭션이라 실패 시 토큰이 롤백돼 재사용 가능하다.
- 각 액션의 파괴 액션 바디 필드와 승인 발급·소각 시 `target` 문자열 형식은 §3-6 "바인딩 target·바디 필드 확정표"가 정본이다. `operators.status`·`operators.roles`는 전이 방향·대상 등급을 포함한 복합 target(`{id}:{STATUS}`·`{id}:{GRANT|REVOKE}:{ROLE}`), 나머지는 자원 식별자 단독이다. `operators.create`는 수행자 `operator_id`와 생성 대상 `new_operator_id`를 분리한다.

---

<a name="7"></a>
### 6-E. 회원 운영 API (관리자 설계 §2-4·§3-1, v2.32 신설)

관리자_설계 §3-1이 규정한 회원 운영 5경로를 정본에 등재한다. §7-1 절차대로 이 문서가 먼저 개정돼야 코드가 승인 요구를 반영할 수 있다.

| 메서드 | 경로 | 기능 | 역할 | approval_token(§3-6) |
|---|---|---|---|---|
| GET | `/admin/v1/members` | 회원 목록(상태 필터, opaque 커서, 활동 요약 포함) | VIEWER | 불요 |
| GET | `/admin/v1/members/{external_id}` | 회원 상세(계정·상태·활동 요약) | VIEWER | 불요 |
| POST | `/admin/v1/members/{external_id}/suspend` | 정지(`SUSPENDED`) | MEMBER_MOD | 불요 |

**회원 목록 활동 요약(v2.35)**: 목록 행에 `open_order_count`·`last_order_at`·`asset_kinds`를 더한다. 상세(`GET /members/{external_id}`)가 이미 내던 세 값이며 정의도 그대로다. 목록으로 올리는 이유는 판단 지점이다. 정지·폐쇄 대상을 고르는 곳은 목록인데, 종전에는 미체결이 남았는지 보려면 행마다 상세를 열어야 했다. 폐쇄는 미체결을 전량 취소하므로(§6-E) 그 수는 조치 전에 보여야 하는 값이다.

집계는 상관 서브쿼리 3개로 한 왕복에 담는다. 행마다 상세를 부르는 N+1을 화면에 떠넘기지 않는다. `open_order_count`와 `last_order_at`은 `idx_orders_user(user_id, status, created_at DESC)`를, `asset_kinds`는 `accounts`의 `UNIQUE(owner_type, owner_id, purpose, asset_id, shard)` 접두를 탄다. 한 쪽 상한이 200행이라 서브쿼리 수는 유계다.
| POST | `/admin/v1/members/{external_id}/reinstate` | 복구(`ACTIVE`) | MEMBER_MOD | 불요 |
| POST | `/admin/v1/members/{external_id}/close` | 폐쇄(`CLOSED`, 비가역) | MEMBER_MOD | **필수** |

`users.status`는 V1부터 `CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED'))`라 신규 컬럼이 없다. 상태 게이트도 이미 산다. 로그인·갱신은 `MemberAuthService`가, 주문 생성은 `OrderGateway.requireActiveUser`가 `403 account_suspended`로 막는다(§3-8).

**정지와 폐쇄의 차이(v2.32 확정)**. 관리자_설계가 가역·비가역만 규정하고 부수 효과를 비워 뒀다. 아래로 확정한다.

- `suspend`: 세션 전량 폐기 + 상태 전이. **미체결 주문은 남긴다.** 정지는 되돌리는 조치라 주문까지 취소하면 복구해도 원상태가 아니다. 새 주문은 게이트가 막고 기존 주문은 체결될 수 있다.
- `close`: 미체결 주문 전량 취소 + 세션 전량 폐기 + 상태 전이. 비가역이라 남은 주문이 체결되면 폐쇄 계정에 체결이 생긴다. 취소는 예약 잔고를 되돌릴 뿐이라 자산 합 0 불변식을 건드리지 않는다(기존 `OrderGateway.cancelAll` 경유).
  - 정지 마켓(`HALTED`)의 미체결은 §4-B-2에 따라 취소되지 않고 남는다. v2.54부터 폐쇄는 그 주문을 미완료로 세어 아래 `409 orders_cancel_incomplete`로 끝낸다(ADR 0034 결정 15. 2026-09-24 작업을 맡은 AI가 고른 값이고 사용자 확인이 필요하다). 폐쇄를 `200`으로 덮으면 운영자는 남은 주문을 상세의 `open_order_count`로 따로 찾아야 하고, 재개 뒤 그 주문이 폐쇄 계정에서 체결될 수 있기 때문이다. v2.53까지는 `200`으로 끝냈다. 운영자는 그 마켓을 재개한 뒤 새 승인 토큰으로 `close`를 다시 불러(CLOSED→CLOSED 멱등) 남은 주문을 취소한다. 재개와 재호출 사이에 그 주문이 체결될 수 있다.
  - 미완료 취소(v2.53, ADR 0034 S1, v2.54 개정): 취소 명령이 저널에 닿지 못하고 `503`으로 끝난 주문(마켓 라이터 정지, 켜 둔 경우의 접수 락 대기 상한 초과)이나 정지 마켓이라 `422 market_suspended`로 끝난 주문이 남으면 **`409 orders_cancel_incomplete`** 를 낸다. 상태 전이·세션 폐기·나머지 취소는 이미 커밋됐고, 감사 행(`result=OK`, `detail.pending_orders`·`detail.pending_reasons`)도 남은 뒤다. 에러 `detail`에 `status`·`canceled_orders`·`pending_orders`·`pending_reasons`·`approval_id`를 싣는다. `pending_reasons`는 사유별 수 `{"service_unavailable": n, "market_suspended": m}`이고 0인 사유는 싣지 않는다(v2.54 추가 필드). v2.55부터 마켓 접수 경합으로 저널 전에 끝난 취소(§2-5-b)는 `too_many_requests`로 센다. `pending_orders`는 그 합이다. 승인 토큰은 이미 소각됐으므로 운영자는 새 승인으로 `close`를 다시 불러(CLOSED→CLOSED 허용) 남은 주문을 취소한다. `market_suspended`가 남았으면 그 마켓을 먼저 재개한다. 곧바로 다시 불러도 정지 마켓 주문은 취소되지 않는다. 취소가 저널에 들어간 뒤 반영을 기다리다 끝난 `409 order_in_progress`와 나열 뒤 체결로 먼저 종결된 주문도 미완료로 세지 않는다. 게이트웨이는 아직 일시 DB 오류를 per-item 실패로 바꾸지 않으므로, 취소 도중 잠금 대기 초과(55P03)나 문장 취소(57014)가 나면 미완료로 세지 못하고 요청 전체가 오류(55P03은 `500`, 57014 등 분류된 일시 오류는 `503`)로 끝난다. 상태 전이는 이미 커밋된 뒤라 새 승인으로 다시 부른다. v2.54가 이 처리를 §2-3 `503` 행에 계약으로 적었고(일시 DB 오류를 per-item `service_unavailable`로 세어 미완료에 넣는다), 코드는 ADR 0034 S4에서 인그레스 설정과 함께 켠다. 그 전까지는 위와 같이 요청 전체가 오류로 끝난다. (v2.55) S4 코드부터는 취소 도중의 55P03·57014·40P01·40001이 요청 전체를 끝내지 않고 그 주문을 `too_many_requests`로 세어 미완료에 넣는다(§2-5-b). 인그레스는 바꾸지 않는다(§2-3 `503` 행). (v2.56) S4b 코드부터는 취소 명령의 append가 DB 연결 장애나 리더 자격 거부로 끝나도 요청 전체를 끝내지 않고 그 주문을 미완료에 넣는다. 사유는 DB 연결 장애와 게이트가 닫힌 리더 자격 거부가 `service_unavailable`, 게이트가 열린 리더 자격 거부가 `too_many_requests`다(§2-5-c). 연결 장애로 끝난 취소는 실제로는 저널에 들어갔을 수 있지만 확인할 수 없으므로 미완료 쪽으로 센다. 단건 취소라면 대상 주문 재조회가 DB에 닿을 때 `409 order_in_progress`를 내는 경우지만, 회원 폐쇄는 이 경우를 '저널 뒤 반영 대기'의 `409`로 보지 않고 미완료로 센다. 재조회마저 DB에 닿지 못했으면 남은 주문은 시도하지 않고 `service_unavailable`로 센다. 새 승인으로 `close`를 다시 부르면 그 사이 취소가 반영된 주문은 열린 주문 목록에서 빠져 다시 취소하지 않는다. 취소 전 조회의 DB 연결 장애는 종전처럼 요청 전체가 오류다.
  - 파티션 인계(v2.57, ADR 0034 결정 15·S5): 폐쇄는 관리 평면에서 돈다. 로컬 카오스 스택의 `chaos-admin`과 학교 bot-1처럼 관리 평면을 여는 노드는 엔진을 끈 노드라 어떤 마켓의 접수 게이트도 열지 않는다. 그래서 v2.56까지는 폐쇄의 취소 명령이 모두 저널 전에 거부돼 미체결이 있는 회원의 폐쇄가 모드와 무관하게 늘 `409 orders_cancel_incomplete`(`service_unavailable`)였다(S5 설계 2절). v2.57부터 폐쇄의 취소는 §4-B-3의 파티션별 처리와 인계를 따른다. 상태 전이와 세션 폐기를 커밋한 뒤 요청 시점의 미체결 주문을 파티션별로 나눠 그 소유 노드에 넘기고(인계 행의 `origin=member_close`) 최대 5초 기다린다. 소유 노드는 보통의 취소 경로로 취소하므로 저널·fencing·예약 환급은 단건 취소와 같다. 상태를 먼저 닫으므로 그 뒤의 새 주문은 `requireActiveUser`가 막는다. 결과는 위 규칙대로 센다. 소유 노드가 기다림 안에 시작하지 않은 주문(`partition_not_started`), 시작했으나 끝내지 않은 주문(`partition_pending`), 소유 노드의 예기치 않은 오류로 끝난 주문(`server_error`)도 미완료이고 `pending_reasons`에 그 이름으로 싣는다. 소유 노드가 처리한 결과의 사유별 수(`service_unavailable`, `too_many_requests`, `market_suspended`)는 요청을 받은 노드가 직접 취소한 결과와 합쳐 위 규칙대로 센다. 그래서 정지 마켓의 미체결이 있는 회원을 관리 평면에서 폐쇄해도 `409`와 `pending_reasons.market_suspended`가 된다. `partition_pending` 주문은 폐쇄 응답 뒤에 취소될 수 있다. 운영자는 새 승인으로 `close`를 다시 불러 남은 주문을 확인하며, 이미 취소된 주문은 열린 주문 목록에서 빠진다. 엔진을 켠 노드에서 폐쇄를 부르면 그 노드가 게이트를 연 파티션의 주문은 직접 취소한다.
- `reinstate`: 상태 전이만. 세션은 복구하지 않는다. 회원이 다시 로그인한다.
- 세션 폐기는 `auth_sessions.revoked_at`을 채운다. 액세스 토큰은 스테이트리스 15분이라 조회가 그 창만큼 남지만, 주문 생성은 `requireActiveUser`가 즉시 막는다.
- 같은 상태로의 재전이는 멱등 성공이다(정지된 계정 재정지). 상태 기계가 아니라 set-point로 다룬다. 단 `CLOSED`에서 나가는 전이는 없다. `suspend`·`reinstate`를 폐쇄 계정에 부르면 `409 member_closed`.

**목록·상세 계약**.

- 목록은 `status` 필터(미지정 시 전체)와 §2-6 opaque 커서. 정렬 축은 가입 역순(`created_at DESC, id DESC`)이라 최근 가입이 위다. 커서 키셋 인덱스는 DB 설계 v2.35가 추가한다.
- `kind='BOT'` 행은 목록에서 제외한다. 봇 시더가 만든 계정이라 운영 대상이 아니다.
- 행 필드: `external_id`, `email`, `display_name`, `status`, `created_at`. 데모 계정이라 실명·실금융정보가 없고, `email`은 운영자가 회원을 지목하는 유일한 손잡이라 그대로 싣는다(관리자_설계 §3-3 노출 한계와 정합).
- 상세는 행 필드 + `updated_at` + 활동 요약 `open_order_count`·`last_order_at`·`asset_kinds`. 폐쇄 전에 남은 주문이 있는지 운영자가 이 값으로 본다.

**승인 바인딩**. `members.close`를 §3-6 완결 열거에 더한다. 자원 스코프이므로 `target`은 대상 회원의 `external_id`다. 바디는 `operator_id` + `approval_token`.

```json
// POST /admin/v1/members/{external_id}/close  (operator_id + approval_token 필수)
{ "operator_id":"op-01","approval_token":"apv-9910" }
// 200
{ "schema_version":"1.0","external_id":"9f2c-...","status":"CLOSED",
  "canceled_orders":3,"revoked_sessions":1,"approval_id":8842 }
```

### 6-F. MSP 관제센터 API (v2.52.6, 조회·임계값·경보·AI 판단과 조치·상담·추세·통지 구현)

관리 콘솔 관제센터가 읽고 조작하는 경로다. 이 절은 구현보다 먼저 적은 계약이다. 2026-09-25 조회, 임계값 레지스트리, 경보·사건 경로와 백업 즉시 실행 스텁을 구현했고, 같은 날 v2.52.2에서 AI 판단·조치 경로(모드, AI 즉시 정지와 재개, AI 한도, 계획 작업 창, 판단 기록, 조치 요청의 승인·거절·나중에·되돌리기)를 계약에 더해 구현했다. v2.52.3에서 AI 상담, 요약 추세, 온프레미스 AI 서버 상태, 보고 서비스를 거치는 Teams 통지와 보고서 읽기, 원천 읽기의 공유 캐시와 한 판의 `ETag`를 더해 구현했다(아래 '캐시와 한 판'부터 '보고서와 Teams 통지'까지). v2.52.4에서 구현 검토 지적에 따라 실행 직전의 대상 재확인(C5), 임계값 조치 요청의 현재값 바인딩, 시나리오 활성화를 포함한 봇 부하 쿨다운, 관찰 창과 사람 되돌리기가 겹칠 때의 처리를 정했다. v2.52.5에서 로컬 모델 평가([측정 09-25](../../measurements/20260925.md))에 따라 봇 부하의 방향 전제(D4)를 더했다. v2.52.6에서 AI 판단·상담 근거의 경보를 구조화 필드로 좁히고(아래 'AI 판단과 모델 출력'의 판단 근거, 'AI 상담'), 형식 밖 값이 든 경보의 AI 보류를 막는 S8과 자동 모드의 임계값 변경을 민감한 방향만으로 좁히는 T6을 더했다(아래 'AI 모드'). 설계 이유, 코드 출발점, 정책 규칙 id(C1~C6, A1~A10, R·T·S·B·K, D1~D4, M1~M10, L1~L5)는 [관제센터 2단계 설계](../architecture/MSP_관제센터_2단계_설계.md)에 있다. AI 조치 권한은 사용자가 2026-09-24 확정하고 2026-09-25 보강한 [ADR 0035](../adr/0035-ai-operations-authority.md)를 따른다.

- **활성 조건.** `exchange.admin.enabled=true`이고 `exchange.msp.enabled=true`인 관리 JVM에서만 경로가 생긴다. 끈 배포에서는 `/admin/v1/msp/**`가 404다. 켠 관리 JVM은 기동할 때 V70~V73 표(DB 설계 §10)가 있는지 확인하고 없으면 기동하지 않는다. AI 판단 주기는 LLM 설정(`exchange.msp.llm.base-url`)이 있을 때만 돈다. 설정이 없어도 모드, 즉시 끄기, AI 정지와 재개, 한도, 계획 작업 창 경로는 쓸 수 있다. 인증, 레이트리밋(요청당 10토큰), 요청 단위 감사는 §3-6과 관리 필터를 그대로 따른다. 예외는 즉시 끄기(`POST /admin/v1/msp/ai/mode/manual`)와 AI 즉시 정지(`POST /admin/v1/msp/ai/stop`) 둘이다. 공용 버킷을 차감하지 않고 같은 제한기의 별도 키(`{operator_id}:ai-manual`)에서 요청당 1토큰을 쓴다. 화면 폴링이 공용 버킷을 다 써도 AI를 끄고 멈출 수 있어야 하기 때문이다. mTLS 신원, 세션, 활성 운영자 검사와 감사는 같다. AI 신원(`kind='AI'`, `ai-ops`)으로는 세션을 받을 수 없고 필터도 거부한다(`403 admin_forbidden`, `detail.reason=ai_operator`).
- **값 표기.** 비율, 초, 크레딧, 발행률은 십진 문자열이다(`"0.60"`). 정수 대수와 판 번호는 숫자다. 시각은 §2-1 UTC 밀리초 3자리다. 읽지 못한 값은 `null`이고 0으로 채우지 않는다.
- **칸 상태.** 칸마다 `state`가 `READY`, `NOT_CONNECTED`, `UNAVAILABLE` 가운데 하나다. `NOT_CONNECTED`는 원천 설정이 없다는 뜻이고 붙일 원천 이름을 `source`에 싣는다. `UNAVAILABLE`은 설정은 있으나 이번 조회가 실패했다는 뜻이고 `reason`에 분류 코드를 싣는다. `READY`만 `data`를 싣는다.
- **측정 객체.** 지표 값 하나는 `{value, sampled_at, age_seconds, collect}`다. `sampled_at`은 Prometheus 평가 시각이 아니라 그 대상의 마지막 수집 시각이다. `collect`는 `OK`, `STALE`(나이가 `exchange.msp.stale-after` 초과, 기본 60초), `MISSING`(결과 없음)이다.
- **폴링.** 화면은 첫 화면을 `GET /admin/v1/msp/overview` 한 건으로 그리고 15초 이상 주기로 읽는다. 한 판은 추세(24시간)와 AI 서버 상태까지 싣고 `ETag`를 붙인다. 서버는 원천 읽기를 공유 캐시로, 경보를 15초 폴링 결과로 낸다. 운영자 수와 무관하게 원천 호출량이 유계다(아래 '캐시와 한 판').
- **역할.** `BOT_OPS+`는 `BOT_OPS`·`CHAOS_OPS`·`SUPER`다(`AdminPrincipal.has`). 같은 효과의 조작을 사람이 직접 할 때의 역할을 요구한다.
- **감사 action 코드.** 필터의 요청 단위 감사와 별도로 도메인 코드를 남긴다. `msp.alert.ack`, `msp.alert.silence`, `msp.silence.expire`, `ops.threshold.set`, `ops.threshold.revert`, `ops.ai.auto`, `ops.ai.manual`, `ops.ai.stop`, `ops.ai.resume`, `ops.ai.limits`, `msp.window.create`, `msp.window.end`, `ops.ai.action.approve`·`reject`·`defer`·`revert`, `msp.ops.backup`은 사람이, `ops.ai.action.execute`, `ops.ai.action.auto_revert`, `ops.ai.action.deny`는 AI 신원(`ai-ops`)이, `ops.ai.mode.expire`, `ops.ai.mode.system`(상한 도달·악화·관찰 불가·기동 정리에 따른 수동 전환)은 시스템(`system:msp`)이 남긴다. 판단 기록 id와 조치 요청 id를 `detail`에 싣고 비밀 값과 모델 문장은 싣지 않는다. 예외는 AI 상담의 `ops.ai.chat`(사람)이다. 상담은 무엇을 묻고 무엇을 답했는지가 기록의 목적이라 가린 질문과 답을 싣는다(아래 'AI 상담'). 정책 거부는 `detail.rule`과 `detail.reason_code`(예: `direction_conflict` '방향 모순', `no_planned_window` '계획 작업 창 없음')를 싣는다.

| 메서드 | 경로 | 기능 | 역할 | approval_token(§3-6) |
|---|---|---|---|---|
| GET | `/admin/v1/msp/overview` | 한 판: 원천 상태, 요약, 요약 추세(24시간), 호스트, 조회 계층, 복제, 활성 경보, 오늘 사건, 운영 작업, 임계값 현재값, AI 요약, AI 서버 상태. `ETag`·`If-None-Match`로 304 | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/trends` | 요약 추세(`range` `1h`·`6h`·`24h`, 기본 `24h`)와 이전 구간 대비 증감 | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/hosts` | 호스트, 조회 계층, DB 복제 | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/alerts` | 활성 경보와 확인·보류 상태(마지막 폴링 결과) | VIEWER+ | 불요 |
| POST | `/admin/v1/msp/alerts/{fingerprint}/ack` | 경보 확인 기록. Alertmanager 상태를 바꾸지 않는다 | BOT_OPS+ | 불요 |
| POST | `/admin/v1/msp/alerts/{fingerprint}/silence` | 사람 보류(사일런스 생성) | BOT_OPS+, 티켓이 필요한 보류는 SUPER | 등급 `critical`·`other`이거나 30분 초과면 **필수**(`ops.alert.silence`) |
| DELETE | `/admin/v1/msp/silences/{silence_id}` | 관제센터가 만든 사일런스 종료 | BOT_OPS+ | 불요 |
| GET | `/admin/v1/msp/incidents` | 사건 목록(`status` 필터, 커서) | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/incidents/{id}` | 사건과 이벤트 타임라인 | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/ops` | 운영 작업(DB 백업 지표, Databasus, 인증서, 일일 보고) | VIEWER+ | 불요 |
| POST | `/admin/v1/msp/ops/backup` | 백업 즉시 실행(Databasus). 연결 전 스텁 | SUPER | 불요 |
| GET | `/admin/v1/msp/thresholds` | 임계값 항목과 현재값, 게이지 수집 여부 | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/thresholds/changes` | 변경 이력(`key` 필터, 커서) | VIEWER+ | 불요 |
| POST | `/admin/v1/msp/thresholds/{key}` | 임계값 변경 | SUPER | **필수**(`ops.threshold.set`) |
| POST | `/admin/v1/msp/thresholds/changes/{id}/revert` | 변경 되돌리기(최신 변경만) | SUPER | **필수**(`ops.threshold.set`) |
| GET | `/admin/v1/msp/ai/mode` | 모드, 정지 여부, 유효 시간, 이번 활성의 자동 조치 수와 상한, 쿨다운, 관찰 창, LLM 원천 | VIEWER+ | 불요 |
| POST | `/admin/v1/msp/ai/mode/auto` | 자동 모드 켜기(1·2·4·8시간) | SUPER | **필수**(`ops.ai.auto`) |
| POST | `/admin/v1/msp/ai/mode/manual` | 즉시 끄기(언제든, 멱등, 공용 버킷 밖) | VIEWER+ | 불요 |
| POST | `/admin/v1/msp/ai/stop` | AI 즉시 정지(두 모드에서, 멱등, 공용 버킷 밖) | VIEWER+ | 불요 |
| POST | `/admin/v1/msp/ai/resume` | AI 재개(사유 필수, 수동으로만) | SUPER | 불요 |
| GET | `/admin/v1/msp/ai/limits` | AI 한도: 항목별 지금 값, 적용 중인 값, 하드 범위, 조이는 방향 | VIEWER+ | 불요 |
| PUT | `/admin/v1/msp/ai/limits/{key}` | AI 한도 변경(사유 필수) | SUPER | **필수**(`ops.ai.limits`) |
| GET | `/admin/v1/msp/ai/limits/changes` | AI 한도 변경 이력(`key` 필터, 커서) | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/maintenance-windows` | 계획 작업 창 목록(`status` `ACTIVE`·`ALL`) | VIEWER+ | 불요 |
| POST | `/admin/v1/msp/maintenance-windows` | 계획 작업 창 등록(최대 24시간) | BOT_OPS+ | 불요 |
| POST | `/admin/v1/msp/maintenance-windows/{id}/end` | 계획 작업 창 조기 종료 | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/ai/decisions` | 판단 이력(커서) | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/ai/decisions/{id}` | 판단 상세: 근거 스냅숏, 모델, 출력 원문과 해시, 조치 요청, 버린 항목 | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/ai/actions` | 조치 요청 목록(`status` `OPEN`·`ALL`, 커서) | VIEWER+ | 불요 |
| POST | `/admin/v1/msp/ai/actions/{id}/approve` | 조치 요청 승인과 실행 | 조치 종류별 | `THRESHOLD`만 **필수**(`ops.threshold.set`) |
| POST | `/admin/v1/msp/ai/actions/{id}/reject` | 거절 | VIEWER+ | 불요 |
| POST | `/admin/v1/msp/ai/actions/{id}/defer` | 나중에(1~60분, 기본 15분, 요청 만료 시각에서 자름). 자동 실행에서 영구히 뺀다 | VIEWER+ | 불요 |
| POST | `/admin/v1/msp/ai/actions/{id}/revert` | 실행한 조치 되돌리기 | 조치 종류별 | `THRESHOLD`만 **필수**(`ops.threshold.set`) |
| POST | `/admin/v1/msp/ai/chat` | AI 상담(읽기 전용 질의응답, 대화 저장 없음) | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/ai/server` | 온프레미스 AI 서버 상태(버전, 적재 모델, 최근 호출의 지연·실패) | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/reports` | 보고 서비스의 보고 목록(읽기 프록시) | VIEWER+ | 불요 |
| GET | `/admin/v1/msp/reports/status` | 보고 서비스의 Teams 전송 상태와 관제센터 통지 보관함 요약 | VIEWER+ | 불요 |

조치 종류별 역할은 `READ_NODES`·`THRESHOLD`·`BACKUP_NOW`가 SUPER, `SILENCE`·`BOT_LOAD`가 `BOT_OPS+`다. AI의 보류 요청은 계획 작업 창 안의 `warning` 30분 이하뿐이라 `BOT_OPS+`의 승인이 티켓 없는 사람 보류의 범위를 넘지 않는다. 즉시 끄기, AI 즉시 정지, 계획 작업 창 조기 종료, 거절·나중에는 권한을 줄이는 조작이라 승인 거부(§6-B `approvals/{id}/reject`)처럼 VIEWER+다. 계획 작업 창 등록은 AI가 그 창 안의 `warning` 경보를 30분까지 보류할 수 있게 하는 조작이라 같은 효과의 사람 보류와 같은 `BOT_OPS+`다. 재개는 AI가 다시 판단하고 조치안을 내게 하는 조작이라 ADR 0035에 따라 조치안 승인과 같은 SUPER다.

```json
// GET /admin/v1/msp/overview → 200 (학교 AWS, LLM·Databasus 미설정 예)
{ "schema_version":"1.0", "environment":"SCHOOL_AWS", "day_zone":"Asia/Seoul",
  "generated_at":"2026-09-24T09:00:00.000Z",
  "sources":[
    {"name":"prometheus","state":"READY"},
    {"name":"alertmanager","state":"READY","last_poll_at":"2026-09-24T08:59:52.000Z"},
    {"name":"llm","state":"NOT_CONNECTED"},
    {"name":"databasus","state":"NOT_CONNECTED"},
    {"name":"teams","state":"READY","via":"reporting","delivery_enabled":true,
     "last_sent_at":"2026-09-24T00:00:41.000Z","pending":0,"outbox":{"pending":0,"failed":0}}],
  "summary":{"state":"READY","data":{
    "availability":{"value":null,"sampled_at":null,"age_seconds":null,"collect":"MISSING"},
    "p95_seconds":{"value":null,"sampled_at":null,"age_seconds":null,"collect":"MISSING"},
    "error_ratio":{"value":"0.0004","sampled_at":"2026-09-24T08:59:47.000Z","age_seconds":13,"collect":"OK"},
    "slo":{"availability":null,"p95_seconds":null,"error_ratio":null}}},
  "hosts":{"state":"READY","data":[
    {"node":"db-primary","role":"DB","actual_role":"PRIMARY","instance_type":"t3.small","az":"ap-northeast-2c",
     "up":{"value":"1","sampled_at":"2026-09-24T08:59:50.000Z","age_seconds":10,"collect":"OK"},
     "cpu_ratio":{"value":"0.08","sampled_at":"2026-09-24T08:59:50.000Z","age_seconds":10,"collect":"OK"},
     "memory_ratio":{"value":"0.41","sampled_at":"2026-09-24T08:59:50.000Z","age_seconds":10,"collect":"OK"},
     "root_free_ratio":{"value":"0.91","sampled_at":"2026-09-24T08:59:50.000Z","age_seconds":10,"collect":"OK"},
     "cpu_credits":{"value":null,"sampled_at":null,"age_seconds":null,"collect":"MISSING"}}]},
  "read_tier":{"state":"NOT_CONNECTED","source":"read_capacity"},
  "replication":{"state":"READY","data":{"primary":"db-primary","standby":"db-standby","stream":"STREAMING",
    "lag_seconds":{"value":"0.2","sampled_at":"2026-09-24T08:59:50.000Z","age_seconds":10,"collect":"OK"},
    "lag_bytes":{"value":"0","sampled_at":"2026-09-24T08:59:50.000Z","age_seconds":10,"collect":"OK"},
    "label_mismatch":false}},
  "alerts":{"state":"READY","data":[
    {"fingerprint":"5f3c2a9e0b1d4c77","alertname":"LinuxDiskSpaceLow","severity":"warning","node":"db-primary",
     "summary":"디스크 여유 공간 부족 · 임계 미만","starts_at":"2026-09-24T08:40:00.000Z","state":"FIRING",
     "incident_id":12,"threshold_key":"host.disk_free_alert","acked_by":null,"acked_at":null,"silence":null}]},
  "incidents":{"state":"READY","data":{"today_count":1,"today_resolved_mean_seconds":null,"open_ids":[12]}},
  "ops":{"state":"READY","data":{"db_backup":{"state":"READY","data":{"last_success_at":null,"verify_ok":null}},
    "databasus":{"state":"NOT_CONNECTED","source":"databasus"},
    "certificate":{"state":"NOT_CONNECTED","source":"certificate"},
    "daily_report":{"state":"READY","data":{"last_triggered_at":"2026-09-24T00:00:03.000Z"}}}},
  "thresholds":{"state":"READY","data":{"items":[
    {"key":"host.disk_free_alert","unit":"ratio","value":"0.15","min":"0.1","max":"0.4","version":1,
     "in_use":true,"rule_value":null}],
    "gauge_scraped":false}},
  "ai":{"state":"NOT_CONNECTED","source":"llm","mode":"MANUAL","stopped":false,"stop":null,"auto":null,
        "open_actions":0,"last_decision":null},
  "ai_server":{"state":"NOT_CONNECTED","source":"llm"},
  "trends":{"range":"24h","step_seconds":720,"window":{"start":"...","end":"..."},"previous_window":{"start":"...","end":"..."},
            "series":{"availability":{"state":"READY","data":{"...":"아래 '요약 추세'"}}}} }
```

- `environment`는 서버 설정(`exchange.msp.environment`)이다. 설정이 없으면 `UNKNOWN`이고 서버가 짐작하지 않는다. 값은 `LOCAL_DOCKER`, `SCHOOL_AWS`, `PERSONAL_AWS`, `UNKNOWN`이다.
- 호스트의 `role`은 설정의 배치 역할이고 `actual_role`은 DB에 한해 recovery 지표로 정한 실제 역할이다. 둘이 다르면 복제 칸의 `label_mismatch`가 `true`다.
- 경보에는 `instance`(사설 주소와 포트)를 싣지 않는다. `severity`는 정규화한 등급(`critical`, `warning`, `info`, `other`)이고, 라벨이 없거나 모르는 값이면 `other`다. 생존 신호 경보(`exchange.msp.incident.heartbeat-alertnames`, 기본 `Watchdog`)는 경보 목록과 사건에 나오지 않는다. `state`는 `FIRING`, `ACKED`, `SILENCED`다. `threshold_key`는 규칙 주석이 가리키는 레지스트리 항목이다.
- `thresholds.gauge_scraped`는 Prometheus가 `exchange_ops_threshold`를 수집하는지다. 항목의 `rule_value`는 경보 규칙이 지금 쓰는 값(Prometheus가 마지막으로 수집한 게이지 값, 최대 7일 유지)이고, 미설정(게이지 값 NaN)이거나 읽지 못하면 `null`이다. `value`와 다르면 변경이 아직 규칙에 반영되지 않은 것이고, `gauge_scraped`가 `false`이고 `rule_value`도 `null`이면 규칙이 리터럴 대체값으로 판정 중이다. `in_use`는 이 환경에서 그 항목을 쓰는 규칙이나 권고가 있는지다. `read.*`는 `read-capacity.mode=CAPACITY_PLAN`에서만 `true`다.
- `ai`는 LLM 설정이 없어도 `mode`, `stopped`, `stop`을 싣는다. 즉시 끄기와 정지는 모델 연결과 무관하게 쓸 수 있어야 하기 때문이다. LLM 설정이 있으면 `state`가 `READY`이고, 마지막 모델 호출이 실패했으면 `UNAVAILABLE`과 `reason`(분류 코드)이다. `stop`은 정지 중일 때 `{by, at, reason}`, `auto`는 자동일 때 `{since, valid_until, enabled_by, activation, auto_used, auto_cap}`, `open_actions`는 열린 조치 요청 수, `last_decision`은 `{id, decided_at, outcome}`이다. `llm` 원천은 설정됐을 때 `provider`(`openai-compatible`, `ollama-native`), `model`, `transport`(`LOOPBACK`, `TLS`, `PLAINTEXT`)를 싣는다. `PLAINTEXT`는 `exchange.msp.llm.allow-insecure-http`로 명시 허용한 평문이고 수동 모드 전용이다.

**세부 조회와 표시 규칙(2026-09-25 구현 기준).** 세부 조회는 한 판과 같은 칸 객체를 같은 이름으로 싣고 한 판과 같은 머리 필드(`schema_version`, `environment`, `day_zone`, `generated_at`)를 붙인다. `GET .../hosts`는 `hosts`, `read_tier`, `replication`을, `GET .../alerts`는 `alerts`를, `GET .../ops`는 `ops`를, `GET .../thresholds`는 `thresholds`를 싣는다. Prometheus 질의 묶음은 한 판과 같은 10초 캐시를 쓰므로 세부 조회가 원천 호출을 늘리지 않는다.

- **숫자 문자열.** 측정 객체의 값은 비율이면 소수 넷째 자리, 초면 셋째 자리에서 반올림한 십진 문자열이고, 바이트와 0·1 값은 정수 문자열이다. 대수와 판 번호는 위 값 표기대로 숫자다. Prometheus가 NaN이나 무한대를 돌려주면 값은 `null`이고 `collect`는 `MISSING`이다.
- **원천 상태.** `sources`의 `prometheus`는 설정이 없으면 `NOT_CONNECTED`, 질의 묶음 전체가 실패하면 `UNAVAILABLE`(`reason`: `timeout`, `unreachable`, `http_status`, `response_limit`, `query_failed`, `bad_response`)이다. 일부 질의만 실패하면 `READY`이고 그 질의를 쓰는 칸만 `UNAVAILABLE`이다. `alertmanager`는 마지막 폴링이 성공했으면 `READY`와 `last_poll_at`, 그 폴링에서 쓰기에 실패한 경보 수 `write_failures`를 싣는다. 마지막 폴링이 실패했거나 기동 뒤 아직 폴링하지 않았으면 `UNAVAILABLE`(`reason`, 폴링 전이면 `not_polled`)이고 `last_poll_at`은 마지막 성공 시각이다. `teams`는 보고 서비스(`exchange.msp.reporting.url`)를 거치므로 `via:"reporting"`을 싣고, 보고 서비스 설정이 없으면 `NOT_CONNECTED`, 전송 상태 조회가 실패하면 `UNAVAILABLE`(`reason`)이다. `READY`면 보고 서비스의 전송 켬 여부 `delivery_enabled`, 마지막 전송 시각 `last_sent_at`, 보내지 않은 작업 수 `pending`과 관제센터 통지 보관함의 `outbox`(`pending`, `failed`)를 싣는다. 전송이 꺼져 있어도 `READY`다. 화면이 '전송 꺼짐'을 따로 보인다.
- **경보 칸.** `alerts`는 `{state, last_poll_at, data}`다. 마지막 폴링이 실패하면 지난 목록을 내지 않고 `UNAVAILABLE`이다. 항목은 `{fingerprint, alertname, severity, node, role, component, mountpoint, summary, starts_at, state, incident_id, threshold_key, acked_by, acked_at, silence}`이고, `silence`는 `{silence_id, ends_at, actor, by}` 또는 `null`이다. `state`는 Alertmanager가 사일런스로 누른 경보면 `SILENCED`, 확인된 사건이면 `ACKED`, 나머지는 `FIRING`이다. 쓰기에 실패해 사건이 없는 경보는 `incident_id`가 `null`이다. `summary`는 사람이 읽는 화면용 규칙 주석이고, AI 판단·상담 근거에는 싣지 않는다(v2.52.6, 아래 'AI 판단과 모델 출력').
- **조회 계층 칸.** `read-capacity.mode=NONE`이면 `NOT_CONNECTED`(`source=read_capacity`)다. `ASG_POLICY`면 `READY`이고 `data`는 `{mode, scaling_owner:"ASG", scaling_basis:"request_count_per_target"}`다. 대수는 AWS API를 읽지 않으므로 싣지 않는다. `CAPACITY_PLAN`이면 `data`는 `{mode, min, desired, max, observed, avg_cpu_ratio}`다. `min`·`desired`·`max`는 `capacity_plan`, `observed`와 `avg_cpu_ratio`(측정 객체)는 §6-A-a의 노드 조회로 잰 값이다. 노드 조회는 거래 노드를 부르므로 한 판과 세부 조회가 `cache-ttl`(기본 10초) 동안 한 번의 조회를 함께 쓰고, `sampled_at`은 그 조회를 한 시각이다(v2.52.3). AI 판단의 근거는 이 캐시를 쓰지 않고 판단마다 노드를 잰다.
- **복제 칸.** `primary`·`standby`는 `pg_wal_position_in_recovery`가 0·1인 노드다. `stream`은 복제 지표의 `state` 라벨을 대문자로 옮긴 값(`STREAMING` 등)이고 없으면 `null`이다. `label_mismatch`는 수집 설정의 `role` 라벨(`data`는 Primary, `standby`는 Standby)이 recovery 지표와 다를 때 `true`다.
- **임계값 칸.** 항목은 `{key, unit, value, default, min, max, ai_max_step, version, updated_by, updated_at, in_use, rule_value}`다. `gauge_scraped`는 Prometheus를 읽지 못하면 `null`이다. `in_use`는 아래 표로 정한다.

| 항목 | `in_use`가 `true`인 조건 |
|---|---|
| `read.scale_out_cpu`, `read.scale_in_cpu` | `read-capacity.mode=CAPACITY_PLAN`(용량 권고가 참조) |
| `host.disk_free_alert`, `backup.delay_alert` | `environment=SCHOOL_AWS`(학교 `LinuxDiskSpaceLow`·`DatabaseBackupStale`이 참조) |
| `db.replication_lag_alert` | `environment`가 `SCHOOL_AWS`·`LOCAL_DOCKER`(학교·하네스 복제 지연 규칙이 참조) |
| `host.cpu_alert`, `host.mem_alert`, `bot.capacity_guard`, `host.cpu_credit_alert` | 없음. 참조하는 규칙이나 권고가 아직 없다 |

- **사건 조회.** `GET .../incidents`는 `status`(`OPEN`·`RESOLVED`, 생략 시 전체), `limit`(1~200, 기본 50), `cursor`를 받고 `{schema_version, data, pagination}`을 돌려준다. 정렬은 시작 시각 역순이다. 항목은 경보 항목의 라벨 필드(`fingerprint`, `alertname`, `severity`, `node`, `role`, `component`, `mountpoint`)와 `{id, summary, started_at, resolved_at, status, acked_by, acked_at, silence}`다. 사건 표는 주석을 담지 않으므로 `threshold_key`는 경보 칸에만 있다. `GET .../incidents/{id}`는 `{schema_version, incident, events}`이고 `incident`에 `first_seen_at`, `last_seen_at`이 더 있다. `events`는 `{id, kind, actor_type, actor_id, text, ref, at}`을 시각 순으로 싣는다. 없는 id는 `404 not_found`다. 끝 시각이 지난 보류는 `silence`를 `null`로 낸다.
- **변경 이력.** `GET .../thresholds/changes`는 `key`, `limit`(1~200, 기본 50), `cursor`를 받는다. 항목은 `{id, key, from, to, version, via, changed_by, approval_id, ai_action_id, revert_of, reason, changed_at}`이고 변경 시각 역순이다. 없는 `key`는 `404 not_found`다. 되돌리기 응답은 `{schema_version, key, from, to, version, change_id, via:"REVERT", revert_of, approval_id}`다.
- **확인과 보류 해제 응답.** 확인은 `{schema_version, fingerprint, incident_id, acked_by, acked_at}`이다. 활성이 아닌 경보는 `404 not_found`(`detail.reason=alert_not_active`), 생존 신호 경보는 `detail.reason=alert_excluded`다. 보류 해제는 `{schema_version, silence_id, incident_id}`다. 관제센터가 만든 사일런스가 아니면 `404 not_found`(`detail.reason=silence_not_managed`)다. 종료 전에 사일런스를 조회해 Alertmanager에 없거나 이미 끝났으면 종료 호출 없이 끝난 것으로 보고 사건의 보류 칸을 비운다. 보류의 `reason`은 1~200자, 임계값 변경의 `reason`은 500자 이하다.

**경보 확인과 보류.** 확인은 사건에 확인자와 시각을 적는다. 보류는 그 경보의 라벨 묶음 전체를 정확히 맞춘 사일런스이고 `reason`이 필수다. 보류는 두 단계다. `BOT_OPS+`가 티켓 없이 거는 보류는 등급이 `warning`·`info`이고 `minutes`가 1~30이다. 등급이 `critical`·`other`이거나 30분을 넘는 보류(최대 `exchange.msp.silence.human-max`, 기본 480분)는 SUPER와 승인 티켓(`ops.alert.silence`, 대상 `{fingerprint}:{minutes}`)을 요구하고, 없거나 맞지 않으면 `403 admin_forbidden`이다. 경보를 오래 또는 critical까지 누르는 것은 임계값을 둔하게 바꾸는 것과 같은 효과라 같은 등급의 통제를 건다. 이미 사일런스에 눌린 경보는 `409 alert_already_silenced`, 활성이 아닌 경보는 `404 not_found`(`detail.reason=alert_not_active`), 생존 신호 경보는 `404 not_found`(`detail.reason=alert_excluded`), Alertmanager 설정이 없으면 `409 msp_source_not_connected`다. AI의 보류는 이 경로가 아니라 조치 요청(`SILENCE`)으로만 만들어지고, 대상은 운영자가 등록한 계획 작업 창 안의 `warning` 경보뿐이다. 관측 경로(`component`가 `monitoring`·`watchdog`)와 복제·DB·백업·디스크 계열 경보, 경보명이나 허용한 라벨 값이 형식 밖이라 AI 근거에서 버린 경보(S8, v2.52.6)는 창이 있어도 대상이 아니다. 길이는 30분 이하이고 창의 끝을 넘지 않으며, 같은 지문 연속 불가다. 사람의 보류는 창과 무관하게 위 두 단계를 따른다. 사일런스 설명(`comment`)에는 사유와 사건 번호를, AI 보류에는 조치 요청 번호(`msp-ai-action:{id}`)도 싣는다.

```json
// POST /admin/v1/msp/alerts/5f3c2a9e0b1d4c77/silence  (BOT_OPS+, warning 30분 이하라 티켓 불요)
{ "operator_id":"op-01","minutes":30,"reason":"디스크 정리 작업 중" }
// 201
{ "schema_version":"1.0","fingerprint":"5f3c2a9e0b1d4c77","incident_id":12,
  "silence_id":"d6c1f0e2-6a9b-4d1e-9d7a-2b0c3f4e5a61","ends_at":"2026-09-24T09:30:00.000Z" }

// 같은 경로로 120분 보류: SUPER, approval_token 대상 "5f3c2a9e0b1d4c77:120"
{ "operator_id":"op-01","approval_token":"APV...","minutes":120,"reason":"디스크 증설 작업 중" }
```

**임계값.** 항목, 단위(`ratio`·`seconds`·`credits`), 허용 범위, AI 1회 폭은 마이그레이션이 정하고 API로 바꿀 수 없다. 변경은 SUPER와 승인 티켓(`ops.threshold.set`, 대상 `{key}:{value}`)을 거치고, 역할 확인·형식과 범위 검사·티켓 소각·현재 행 잠금·갱신·이력·감사가 한 트랜잭션이다(§6-D 수수료 변경과 같다). 검증이 실패하면 티켓은 소각되지 않는다. 사람의 변경에는 AI 1회 폭을 걸지 않는다. 형식·범위 위반은 `400 invalid_parameter`(`detail.constraint`)다. 값은 소수 넷째 자리까지만 받고 그보다 길면 `detail.constraint=scale`로 거부한다. 저장 열(`NUMERIC(12,4)`)이 조용히 반올림하면 티켓이 승인한 값과 저장한 값이 달라지기 때문이다. 축소 임계는 증설 임계보다 0.15 이상 낮아야 하고(T3), 이 간격은 변경, 되돌리기, AI 조치 요청의 승인·되돌리기에서 모두 검사해 위반이면 `409 msp_policy_denied`(`detail.rule=T3`)다. 서버 안의 자동 실행, 자동 되돌림, 설정 적용도 같은 검사를 거치고, DB가 커밋 때 한 번 더 본다(DB 설계 §10.2). 없는 항목은 `404 not_found`다. 되돌리기는 그 항목의 최신 변경만 되고 직전 값(미설정이면 미설정)으로 되돌린다. 초기값(`via=SEED`)은 되돌릴 수 없고, 초기값이 있는 항목은 미설정이 되지 않는다. 어긋나면 `409 threshold_revert_conflict`다. `in_use`가 `false`인 항목도 사람은 바꿀 수 있지만 AI는 바꾸지 못한다.

```json
// POST /admin/v1/msp/thresholds/host.disk_free_alert  (SUPER, approval_token 대상 "host.disk_free_alert:0.2")
{ "operator_id":"op-01","approval_token":"APV...","value":"0.20","reason":"디스크 증설 전 조기 경보" }
// 200
{ "schema_version":"1.0","key":"host.disk_free_alert","from":"0.15","to":"0.2","version":2,
  "change_id":41,"via":"MANUAL","approval_id":8850 }
```

변경 이력의 `via`는 `SEED`(마이그레이션 초기값), `CONFIG`(환경 기본값 적용), `MANUAL`, `AI_APPROVED`, `AI_AUTO`, `REVERT`, `AUTO_REVERT`다.

**AI 모드.** 수동이 기본이다. 자동 켜기는 SUPER, 승인 티켓(`ops.ai.auto`, 대상 `AUTO:{hours}`), 사유(1~500자), `hours` 1·2·4·8 가운데 하나를 요구한다(ADR 0035). 형식 위반은 `400 invalid_parameter`다. AI 정지 중이면 `409 msp_policy_denied`(`detail.rule=M9`), LLM이나 Prometheus 설정이 없으면 `409 msp_source_not_connected`다. LLM 전송이 평문(`transport=PLAINTEXT`)이면 `409 msp_policy_denied`(`detail.rule=M8`), 결과를 모르는 조치 요청(`UNKNOWN`)이 남아 있으면 `409 msp_policy_denied`(`detail.rule=M1`)다. 응답의 `since`와 `valid_until`은 DB가 정한 값이다. 시작은 커밋 트랜잭션 시각이고 끝은 거기에 티켓의 시간을 더한 값이며, 티켓은 같은 트랜잭션에서 소각된 것이어야 한다(DB 설계 §10.4). 수동에서 켜면 활성 번호(`activation`)가 하나 오르고 이번 활성의 자동 조치 수와 임계값 항목별 수가 0부터 다시 센다. 자동인 동안 새 티켓으로 다시 켜면 지금부터 새 창이 되지만 활성 번호와 횟수는 이어진다. 자동 모드 동안 허용 목록 조치는 조치별 티켓 없이 실행되고, 자동 켜기 티켓이 그 창의 포괄 승인이다(§3-6). 임계값 조치는 그 항목의 민감한 방향(경보를 더 일찍 울리거나 증설을 더 일찍, 축소를 더 늦게 하는 쪽)으로 옮기는 변경만 자동 실행한다. 둔하게 하는 변경과 방향을 정하지 않은 항목(`bot.capacity_guard`)의 변경은 요청으로 남아 `hold_rule=T6`(`hold_reason`에 `loosening` 또는 `direction_undefined`의 사유)이고, 사람의 승인(`ops.threshold.set` 티켓)으로만 실행된다(v2.52.6). 항목별 방향은 설계 5-1절 표다. 즉시 끄기는 티켓 없이 VIEWER+ 누구나 언제든 부를 수 있고, 공용 레이트리밋 버킷을 쓰지 않으며(위 활성 조건), 이미 수동이면 `changed:false`로 성공한다. 즉시 끄기가 커밋된 뒤에는 AI 자동 실행이 없다. 실행 트랜잭션이 모드 행을 공유 잠금으로 읽으므로, 이미 시작한 실행은 끄기보다 먼저 커밋되고 그 뒤의 실행은 수동을 본다. 자동 모드는 유효 시간이 지나면 수동으로 돌아간다. 자동으로 실행한 조치의 관찰 창은 모드가 바뀌거나 정지해도 끝까지 보고, 그 안에서 악화하면 종류별 정책대로 되돌린다(되돌림은 조치를 무르는 안전 방향이다).

```json
// POST /admin/v1/msp/ai/mode/auto  (SUPER, approval_token 대상 "AUTO:4")
{ "operator_id":"op-01","approval_token":"APV...","hours":4,"reason":"리허설 중 조회 급증 대응" }
// 200
{ "schema_version":"1.0","mode":"AUTO","since":"2026-09-24T09:00:00.000Z",
  "valid_until":"2026-09-24T13:00:00.000Z","approval_id":8851,"activation":3 }

// POST /admin/v1/msp/ai/mode/manual  (VIEWER+)
{ "operator_id":"op-02","reason":"수동 확인 필요" }
// 200
{ "schema_version":"1.0","mode":"MANUAL","changed":true }

// GET /admin/v1/msp/ai/mode → 200 (자동 모드)
{ "schema_version":"1.0","mode":"AUTO","stopped":false,"stop":null,
  "auto":{"since":"2026-09-24T09:00:00.000Z","valid_until":"2026-09-24T13:00:00.000Z","enabled_by":"op-01",
          "reason":"리허설 중 조회 급증 대응","approval_id":8851},
  "activation":{"seq":3,"started_at":"2026-09-24T09:00:00.000Z","auto_used":1,"auto_cap":5,
                "threshold_used":{"host.cpu_alert":1}},
  "cooldowns":{"READ_NODES":"2026-09-24T09:12:00.000Z","THRESHOLD":null,"BOT_LOAD":null},
  "observe_until":"2026-09-24T09:07:00.000Z",
  "llm":{"state":"READY","provider":"ollama-native","model":"gpt-oss:20b","transport":"LOOPBACK"} }
```

`activation`은 자동일 때만 싣는다. `cooldowns`는 종류별 쿨다운의 끝 시각이고 쿨다운이 없거나 지났으면 `null`이다. 사람의 같은 종류 조치도 쿨다운을 시작시킨다. 봇 부하(`BOT_LOAD`)는 발행률 설정(`PUT /admin/v1/sim/rate`)과 시나리오 활성화(`POST /admin/v1/sim/scenarios/{name}/activate`)가 모두 센다(v2.52.4). `observe_until`은 진행 중인 관찰 창의 끝이다.

**AI 즉시 정지와 재개.** 정지는 수동·자동 두 모드에서 AI를 멈춘다(사용자 결정 2026-09-25, ADR 0035). 승인 없이 VIEWER+ 누구나 언제든 부를 수 있고 공용 버킷을 쓰지 않는다. 사유는 선택이며 200자 이하다. 정지하면 한 트랜잭션에서 자동 모드가 풀리고, 대기 중이던 조치 요청(`PROPOSED`, `DEFERRED`)이 `EXPIRED`(`hold_rule=M9`)가 된다. 정지 중에는 새 판단과 조치 요청이 없고, 조치 요청 승인과 자동 실행은 `409 msp_policy_denied`(`detail.rule=M9`)다. 정지 전에 자동 실행한 조치의 관찰 창은 끝까지 보고 악화하면 자동으로 되돌린다. 경보와 ASG 정책은 AI와 무관하게 그대로 돈다. 이미 정지 중이면 `changed:false`로 성공한다. 재개는 SUPER가 사유(1~200자)를 적어 하고, 수동으로만 돌아간다. 승인 티켓은 없고 자동은 재개 뒤 다시 `ops.ai.auto`로 켠다. 정지 중이 아니면 `changed:false`다. 둘 다 감사(`ops.ai.stop`, `ops.ai.resume`)와 사건 타임라인(`STOPPED`, `RESUMED`)에 남는다.

```json
// POST /admin/v1/msp/ai/stop  (VIEWER+)
{ "operator_id":"op-02","reason":"AI 판단이 이상함" }
// 200
{ "schema_version":"1.0","stopped":true,"changed":true,"mode":"MANUAL","expired_actions":2 }

// POST /admin/v1/msp/ai/resume  (SUPER)
{ "operator_id":"op-01","reason":"모델 교체 확인" }
// 200
{ "schema_version":"1.0","stopped":false,"changed":true,"mode":"MANUAL" }
```

**AI 한도.** 운영자가 바꾸는 한도는 다섯이다(ADR 0035, 사용자 확정 2026-09-25). 값은 정수이고 하드 범위는 코드와 DB가 함께 건다(DB 설계 §10.4).

| `key` | 뜻 | 확정값 | 하드 범위 | 조이는 방향(`tighter`) |
|---|---|---|---|---|
| `confidence_pct` | 자동 실행에 필요한 확신도(%) | 80 | 70~95 | `UP` |
| `auto_cap` | 자동 모드 1회 활성당 자동 조치 상한(회) | 5 | 1~10 | `DOWN` |
| `threshold_per_item` | 1회 활성당 임계값 항목 하나의 자동 변경(회) | 2 | 1~3 | `DOWN` |
| `bot_cooldown_min` | 봇 부하 줄임 쿨다운(분) | 10 | 5~60 | `UP` |
| `backup_disk_free_pct` | 백업 즉시 실행에 필요한 저장 디스크 여유(%) | 30 | 20~50 | `UP` |

변경은 SUPER, 승인 티켓(`ops.ai.limits`, 대상 `{key}:{value}`), 사유(1~500자)를 요구한다. 값이 정수가 아니거나 하드 범위 밖이면 티켓을 쓰기 전에 `400 invalid_parameter`(`detail.constraint=range`)이고, 없는 항목은 `404 not_found`다. 자동 모드가 꺼져 있으면 곧바로 적용한다. 자동 모드 중에는 AI 권한을 좁히는 변경(`tighter` 방향)을 곧바로, 넓히는 변경을 다음 활성부터 적용한다. 적용 중인 값(`effective`)은 이번 활성이 시작될 때 잡아 둔 값과 지금 값 가운데 조이는 쪽이다. 사용자가 적은 '낮추는 변경은 즉시, 높이는 변경은 다음 활성부터'를 확신도·쿨다운·디스크 여유처럼 올려야 좁아지는 항목에서는 거꾸로 읽은 것이다(ADR 0035의 AI 해석). 자동 조치 상한을 이번 활성에서 이미 쓴 횟수 이하로 내리면 같은 트랜잭션에서 수동으로 바뀐다. 되돌리기는 이력의 직전 값으로 같은 경로를 다시 부르는 것이다. AI 신원은 한도를 바꿀 수 없다(조치 카탈로그에 없고 세션도 없다).

```json
// PUT /admin/v1/msp/ai/limits/auto_cap  (SUPER, approval_token 대상 "auto_cap:3")
{ "operator_id":"op-01","approval_token":"APV...","value":3,"reason":"야간 자동 조치 줄임" }
// 200
{ "schema_version":"1.0","key":"auto_cap","from":5,"to":3,"version":2,"change_id":6,
  "applies":"NOW","effective":3,"mode":"AUTO" }

// GET /admin/v1/msp/ai/limits → 200
{ "schema_version":"1.0",
  "items":[{"key":"confidence_pct","value":75,"effective":80,"pending":true,"min":70,"max":95,
            "tighter":"UP","unit":"%","version":2,"updated_by":"op-01",
            "updated_at":"2026-09-24T09:30:00.000Z","reason":"확신도 기준 완화 시험"}],
  "activation":{"seq":3,"started_at":"2026-09-24T09:00:00.000Z","auto_used":1} }
```

`applies`는 `NOW` 또는 `NEXT_ACTIVATION`이다. `pending`이 참이면 지금 값이 다음 활성부터 적용된다는 뜻이다. `activation`은 자동일 때만 싣는다. 변경 이력(`GET .../ai/limits/changes`)의 항목은 `{id, key, from, to, version, changed_by, approval_id, reason, changed_at}`이고 커서 규칙은 임계값 변경 이력과 같다.

**계획 작업 창.** AI의 경보 보류는 운영자가 등록한 계획 작업 창과 겹치는 `warning` 경보에만 허용한다(정책 S6, 로컬 실측 근거는 설계 7-13절). 창은 시작·끝, 대상(`alertname`, `labels`), 사유, 등록자다. 대상은 경보명과 라벨이 모두 맞아야 하고 둘 가운데 하나는 있어야 한다. 라벨 키는 `node`, `role`, `component`, `mountpoint`만 받는다. `starts_at`은 생략하면 지금이고, `ends_at`은 지금보다 뒤이면서 시작 뒤 24시간 이내다. 사유는 1~200자다. 창 안이라도 복제·DB·백업·디스크 계열과 관측 경로 경보, 형식 밖 값이 든 경보는 AI가 보류하지 못하고(정책 S5, S7, S8), AI 보류의 끝은 창의 끝을 넘지 못한다. 사람의 보류는 창과 무관하다. 조기 종료는 끝난 창이나 이미 끝낸 창이면 `changed:false`다. 등록과 종료는 감사(`msp.window.create`, `msp.window.end`)와 사건 타임라인(`WINDOW`)에 남는다.

```json
// POST /admin/v1/msp/maintenance-windows  (BOT_OPS+)
{ "operator_id":"op-02","ends_at":"2026-09-24T10:00:00.000Z","alertname":"BotOrderLatencyHigh",
  "labels":{"component":"bot"},"reason":"봇 설정 교체 작업" }
// 201
{ "schema_version":"1.0","id":4,"starts_at":"2026-09-24T09:00:00.000Z","ends_at":"2026-09-24T10:00:00.000Z",
  "alertname":"BotOrderLatencyHigh","labels":{"component":"bot"},"reason":"봇 설정 교체 작업",
  "created_by":"op-02","ended_at":null,"ended_by":null,"active":true }
```

목록은 `status=ACTIVE`(기본, 지금 유효한 창)와 `ALL`(최근 100개)을 받고 끝 시각 역순이며 페이지를 나누지 않는다.

**AI 판단과 모델 출력.** 모델은 판단마다 JSON 한 개를 낸다. 필드는 생성 순서대로 `situation_ko`(상황, 400자 이내), `reason_ko`(조치 이유, 400자 이내), `action`, `target`, `value`, `confidence`(0~1)다. 이유를 조치보다 먼저 쓰게 한 것은 로컬 실측(개발 PC RTX 4090의 Ollama `gpt-oss:20b`. DGX Spark 결과가 아니다)에서 조치를 먼저 쓰게 하면 이유와 반대인 조치가 나왔기 때문이다(설계 7-13절). 두 문장은 한국어 합니다체다. `action`은 방향과 대상이 드러나는 이름 여덟 가지다. `NO_ACTION`, `READ_NODE_ADD_ONE`, `READ_NODE_REMOVE_ONE`, `THRESHOLD_RAISE`, `THRESHOLD_LOWER`(`target`은 항목 키, `value`는 새 값), `ALERT_SILENCE`(`target`은 지문, `value`는 분), `BOT_LOAD_REDUCE`(`value`는 0.5 이상 1 미만의 배율), `BACKUP_RUN_NOW`다. 쓰지 않는 `target`·`value`는 빈 문자열이다. 서버는 이 이름을 조치 종류 다섯(`READ_NODES`, `THRESHOLD`, `SILENCE`, `BOT_LOAD`, `BACKUP_NOW`)으로 옮긴다. 출력 스키마는 저장소의 `server/app/src/main/resources/msp/ai-output.schema.json`이다.

**판단 근거(`msp-evidence/2`, v2.52.6).** 판단마다 모델에 보내는 근거 JSON이고 판단 상세의 `evidence`에 그대로 남는다. 머리 필드는 `schema`, `environment`, `collected_at`, `mode`이고 본문은 `read_tier`, `thresholds`, `alerts`, `alerts_polled_at`, `alerts_collect`, `metrics`, `bot_rate`, `limits`, `recent_actions`, `catalog`다. 외부나 사람이 쓴 자유 문장은 싣지 않는다. 경보 항목은 `{fingerprint, alertname, severity, node?, role?, component?, mountpoint?, rejected?, starts_at, silenced, threshold, planned_window}`뿐이다.

- `fingerprint`는 16자리 16진이 아니면 `null`이다. `alertname`은 `^[A-Za-z_:][A-Za-z0-9_:]{0,63}$`와 영문 낱말 다섯 이하, 네 라벨 값은 `^[A-Za-z0-9/][A-Za-z0-9._/-]{0,63}$`와 영문 낱말 셋 이하를 지나야 싣는다. 영문 낱말은 대문자로 시작하는 낱말과 대문자만 이어진 낱말을 따로 센다. 형식이 맞아도 조치 이름(끝의 `ONE`을 뗀 꼴 포함), 레지스트리 임계값 키, 지시 낱말(`ignore`, `override`, `instruction`, `confidence`, `approv`)이 들어 있으면 버린다. 이 비교는 영숫자만 남긴 소문자로 한다. 이 계약에서 '형식 밖'은 이 검사 가운데 하나라도 지나지 못한 값이다. 검사를 모두 지나는 짧은 식별자는 실리므로 뜻을 담은 짧은 값이 남을 수 있고, 조치는 정책 엔진이 다시 막는다.
- 버린 필드는 값을 자르거나 고쳐 싣지 않고 이름만 `rejected`에 싣는다. 버린 것이 없으면 이 키가 없다. 정책 엔진도 거른 값을 쓰고, 값을 버린 경보는 AI가 보류하지 않는다(S8).
- `threshold`는 규칙 주석 `threshold_key`가 레지스트리 키와 같을 때만 `{key, value, metric, observed}`이고 아니면 `null`이다. `value`는 레지스트리 값(미설정이면 `null`), `metric`은 서버가 그 임계로 판정하는 근거 지표의 이름(`read.*`는 `read_tier.avg_cpu`), `observed`는 그 지표의 측정 객체다. 근거 지표가 없는 항목(`host.cpu_credit_alert`)은 `metric`과 `observed`가 `null`이고, 조회 계층이 없는 환경의 `read.*`는 `observed`가 `null`이다.
- Alertmanager 주석(`summary`, `description` 등)과 허용 밖 라벨(`instance`, `job` 등)은 싣지 않는다. 계획 작업 창의 사유, 사건 문구, 모드·정지 사유, 이전 판단의 모델 문장도 판단 근거에 없다. 계획 작업 창은 `planned_window`와 카탈로그의 보류 가능 분으로만, 최근 조치는 서버가 정한 조치 이름·대상·상태로만 보인다.

판단 결과 `outcome`은 `PROPOSED`, `NO_ACTION`, `INVALID_OUTPUT`, `MODEL_ERROR`, `EVIDENCE_STALE`이다. 모델 호출이 실패하면 `MODEL_ERROR`이고 `error`에 분류 코드(`timeout`, `unreachable`, `http_status`, `bad_response`, `response_limit`, `remote_address`)를 싣는다. 모델이 적재 중이면 판단을 기록하지 않고 다음 주기로 넘긴다. 출력이 16 KiB를 넘거나 JSON이 아니거나 필드와 형이 맞지 않거나 자유 문장에 링크 표기가 있으면 `INVALID_OUTPUT`이다. 조치 이름이 여덟 가지 밖이거나(`UNKNOWN_KIND`, 예: `DB_PROMOTE`), 값 형식이 틀리거나(`BAD_PARAMS`), 범위 밖이거나(`OUT_OF_RANGE`), 이 환경에서 쓸 수 없는 종류거나(`UNAVAILABLE_KIND`), 대상이 지금 없거나(`STALE_TARGET`), 임계값의 올림·내림이 값과 반대면(`DIRECTION_MISMATCH`) 그 조치를 버리고 판단 상세의 `dropped`에 남기며 결과는 `NO_ACTION`이다. 한 판단의 조치는 하나다.

남은 조치는 정책 엔진이 제안 때 종류별 규칙과 방향 전제를 검사한다. 방향 전제는 결정적 코드 규칙이다. app-3 켜기(앱 계층 증설)는 근거의 조회 평균 CPU가 증설 임계 이상일 때만(D1), app-3 끄기(축소)는 축소 임계 미만이 설정 시간(기본 10분) 이상 이어질 때만(D2), 임계값 변경은 둔하게 하는 방향이면 그 항목의 경보가 울리는 중이 아니고 근거 지표가 임계를 넘지 않았을 때만(D3) 받는다. 봇 부하 줄이기는 bot-1 CPU(`bot_host_cpu`)가 봇 용량 보호 임계(`bot.capacity_guard`) 이상일 때만(D4, v2.52.5) 받고, 임계가 미설정이거나 CPU를 모르면 받지 않는다. 경보 보류는 활성 `warning` 경보만(S1), 봇 부하는 줄이는 방향만(B1) 받는다. 막히면 요청은 `DENIED`로 남고 `hold_rule`에 규칙 id, `hold_reason`에 사유(예: '방향 모순: 조회 평균 CPU 0.82가 축소 임계 0.30 미만이 아닙니다')를 적는다. 연속 두 번 같은 판단(A4)은 무작위 흔들림을 거르지만 같은 입력에 같은 오답을 내는 결정적 설정의 체계적 오답은 막지 못하므로, 방향 전제가 그 몫을 한다.

조치 요청 상태는 `PROPOSED`, `DEFERRED`, `EXECUTING`, `APPROVED`(사람 승인 실행), `AUTO_EXECUTED`, `FAILED`, `UNKNOWN`(실행이 중간에 끊겨 결과를 원천에서 대조하지 못함), `REJECTED`, `DENIED`(정책이 제안 때 막음), `WITHDRAWN`(뒤 판단이 제안하지 않음), `EXPIRED`(만료 또는 정지), `REVERTED`, `AUTO_REVERTED`다. `WITHDRAWN`과 `EXPIRED`는 `DEFERRED`에도 같다. 나중에는 요청의 만료 시각을 늘리지 않고(`minutes`는 만료 시각에서 자른다), 나중에로 둔 요청은 자동 실행하지 않으며 사람의 승인으로만 실행된다. 같은 조치는 그 요청이 끝난 뒤에도 나중에 시각까지 자동 실행하지 않는다. VIEWER의 나중에가 AI의 실행 기회를 늘리지 않게 하기 위해서다. `UNKNOWN`은 서버가 원천(Alertmanager 사일런스 목록)과 다시 대조해 결과를 채우거나 `FAILED`로 닫는다. 임계값 조치 요청(`THRESHOLD`)은 판단 때의 현재값(`params.from`)에서 새 값으로 바꾸는 요청이다. 같은 새 값을 내는 판단이 이어져도 그 사이에 현재값이 바뀌었으면 열린 요청을 `WITHDRAWN`으로 닫고 새 요청을 만들어 연속 판단을 처음부터 센다(v2.52.4). 그래서 요청의 `verb`(올림·내림)와 `params.from`은 마지막 판단의 현재값과 맞는다.

판단 상세의 `situation`, `reason`과 조치 요청의 `reason`은 모델이 쓴 평문이다. 서버는 링크를 만드는 표기(URL 스킴, `www.`, Markdown 링크·이미지, 꺾쇠 자동 링크와 HTML 태그, `@` 멘션)가 있으면 그 출력을 `INVALID_OUTPUT`으로 버린다. 화면은 이 필드를 Markdown이나 링크로 그리지 않고, Teams 통지에는 싣지 않는다.

승인은 정책 엔진의 공통 규칙(C1~C6), 종류별 규칙, 방향 전제, 정지(M9)를 실행 직전에 새로 모은 근거로 다시 검사한다. 확신도·연속 판단·쿨다운 같은 자동 모드 규칙, 빈도 규칙(활성당 항목별 횟수, 같은 지문 연속 보류, 백업 하루 1회)과 임계값의 민감한 방향(T6)은 사람의 승인에 걸지 않는다. 승인과 자동 실행은 검사하기 전에 대상 행(`READ_NODES`는 용량 계획, `THRESHOLD`는 임계값 항목)을 잠그고, 잠근 값이 실행 직전에 모은 근거의 값과 같은지 본다. `THRESHOLD`는 요청의 `params.from`과도 같아야 한다. 다르면 근거를 모은 뒤나 요청을 만든 뒤에 사람이 대상을 바꾼 것이므로 `409 msp_policy_denied`(`detail.rule=C5`, `detail.reason_code=target_changed`)다(v2.52.4). 거부는 `409 msp_policy_denied`(`detail.rule`, `detail.reason_code`), 이미 처리됐거나 만료된 요청(같은 요청의 두 번째 승인 포함, 정책 C3)은 `409 ai_action_not_open`, 경계(앱 계층 최소 2·최대 3대)를 넘는 app-3 켜기·끄기는 승인 때 정책이 먼저 막아 `409 msp_policy_denied`(`detail.rule=R1`, `reason_code=out_of_bounds`)이고, `CAPACITY_OUT_OF_BOUNDS`는 §6-A-a의 용량 경로에서만 난다(v2.52.7, 서버 응답 스냅숏으로 확인). 되돌리기는 종류마다 다르다. `READ_NODES`는 현재 희망 대수가 조치 결과와 같을 때 반대 방향 1대, `THRESHOLD`는 최신 변경이고 T3을 지킬 때 직전 값, `SILENCE`는 사일런스 종료, `BOT_LOAD`는 직전 목표 발행률이다. `THRESHOLD` 되돌리기 티켓의 대상은 실행 때 바꾸기 전의 값(`{key}:{result.from}`)이다. `BACKUP_NOW`는 되돌리기가 없고, 이미 되돌렸거나 실행되지 않은 요청과 함께 `409 ai_action_not_open`이다.

```json
// GET /admin/v1/msp/ai/decisions/305 → 200
{ "schema_version":"1.0","id":305,"decided_at":"2026-09-24T09:02:00.000Z","mode":"AUTO",
  "outcome":"PROPOSED","error":null,"model":"gpt-oss:20b","prompt_version":"msp-ai/3",
  "evidence":{ "schema":"msp-evidence/2", "collected_at":"2026-09-24T09:01:58.000Z" },
  "evidence_sha256":"9b1f...","oldest_sample_at":"2026-09-24T09:01:45.000Z",
  "output_raw":"{\"situation_ko\":\"...\"}","output_sha256":"4c2e...","output_truncated":false,
  "situation":"조회를 받는 앱 노드의 평균 CPU가 증설 임계를 넘었습니다.","reason":"app-3를 켜서 앱 계층을 2대에서 3대로 늘리면 조회 부하가 나뉩니다.",
  "confidence":"0.86","latency_ms":930,"incident_id":12,
  "actions":[{"id":77,"kind":"READ_NODES","verb":"READ_NODE_ADD_ONE","params":{"delta":1},
              "status":"AUTO_EXECUTED","consecutive":2,"hold_rule":null,"hold_reason":null}],
  "dropped":[] }

// POST /admin/v1/msp/ai/actions/77/approve  (THRESHOLD면 approval_token 동반)
{ "operator_id":"op-01" }
// 200
{ "schema_version":"1.0","action_id":77,"kind":"READ_NODES","status":"APPROVED",
  "result":{"from":2,"to":3},"revertible":true }
```

조치 요청 항목은 `{id, kind, verb, target, identity_key, params, confidence, reason, status, hold_rule, hold_reason, consecutive, auto, deferred_until, decided_by, decided_at, approval_id, executed_by, executed_at, result, observe_until, observe_outcome, reverted_by, reverted_at, created_at, expires_at, first_decision_id, last_decision_id, revertible}`이다. `auto`는 자동 실행한 요청이면 참이다. `observe_outcome`은 관찰 창의 결과(`KEPT`, `REVERTED`, `UNOBSERVABLE`, `REVERT_BLOCKED`, `NOT_REVERTIBLE`)다. 관찰 창 안에서 사람이 먼저 되돌린 요청(`status=REVERTED`)은 관찰 결과를 적지 않아 `null`이고, 서버의 자동 되돌림이 뒤따라 닿아도 '되돌림 불가'로 적거나 통지하지 않는다(v2.52.4). 판단 이력의 항목은 `{id, decided_at, mode, outcome, error, model, confidence, latency_ms}`다. 없는 id는 `404 not_found`다.

판단 상세의 `output_raw`는 16 KiB까지 저장하고 넘으면 자르며 `output_truncated`로 알린다. `output_sha256`은 자르기 전 전체의 해시다. 근거 스냅숏에는 비밀 값, 개인정보, 사설 주소, 경보 주석과 사람이 쓴 문장이 없다.

**캐시와 한 판(v2.52.3).** 관제센터는 거래 앱과 같은 작은 인스턴스(t3.small, CPU 크레딧)에서 돌 수 있으므로 매칭·주문 경로의 자원을 빼앗지 않는 것이 먼저다. 원천 읽기는 관리 JVM 안의 공유 캐시를 거친다. 키는 코드가 정한 이름뿐이고(최대 64개), 키마다 짧은 수명을 두며, 같은 키를 동시에 읽으면 원천 호출은 한 번이고 나머지 요청은 그 결과를 기다린다. 실패한 조회도 수명 동안 같은 결과(칸의 `UNAVAILABLE`)로 공유해 운영자 수만큼 원천을 다시 두드리지 않는다.

| 키 | 수명 | 원천 |
|---|---|---|
| Prometheus instant 명명 질의 묶음 | `exchange.msp.cache-ttl`(기본 10초, 1~10초) | `GET /api/v1/query` |
| 조회 계층 노드 관측 | `cache-ttl` | §6-A-a 노드 조회 |
| 추세 range 질의(계열·범위별) | step에 맞춰 30~60초 | `GET /api/v1/query_range` |
| 사건 추세(범위별) | step에 맞춰 30~60초 | 사건 표 |
| AI 서버 상태 | 30초 | LLM 서버 상태 경로 |
| 보고 목록, 전송 상태 | 30초 | 보고 서비스 |

Alertmanager 경보는 폴러 한 곳이 15초마다 읽고 조회 경로는 그 결과를 쓴다. 캐시의 적중·적재·대기는 `exchange_msp_cache_requests_total{cache,result}`(`result`는 `hit`, `miss`, `wait`), 실제 원천 호출은 `exchange_msp_source_calls_total{source,op}`로 센다. 요약 칸의 가용성, 응답 p95, 5xx 비율과 요청률은 Prometheus가 기록 규칙(`exchange:msp_probe_success:ratio5m`, `exchange:msp_http_p95:seconds5m`, `exchange:msp_http_5xx:ratio5m`, `exchange:msp_http_requests:rate5m`)으로 미리 계산하고 관제센터는 그 이름만 질의한다. 규칙 파일(학교 `school-alerts.yml`, 하네스 `exchange-recording.yml`)이 없는 Prometheus에서는 세 값이 `MISSING`이다. 규칙 안의 job은 규칙 파일이 정하므로, 이 세 값에는 `exchange.msp.prometheus.app-job`·`probe-job`이 수집 시각(`sampled_at`)을 정하는 데만 쓰인다.

`GET .../overview`는 `ETag`를 싣는다. 값은 본문에서 `generated_at`, 모든 `age_seconds`, `last_poll_at`, `ai_server.server.checked_at`을 뺀 JSON의 SHA-256 앞 32자리를 약한 태그(`W/"..."`)로 싼 것이다. 요청의 `If-None-Match`가 같으면 본문 없이 `304`를 낸다. 요청 단위 감사는 그대로 남는다. `Cache-Control`은 `private, no-cache`다. 304를 받은 화면은 이전 본문을 쓰고 나이는 `sampled_at`에서 다시 계산한다. 수집 표본이 바뀌면(학교 수집 주기 15초) 태그도 바뀌므로 304는 주로 같은 표본을 두 번 읽을 때(여러 탭, 캐시 수명 안의 재요청) 난다.

**요약 추세.** 요약 칸(외부 가용성, 응답 p95, 5xx 비율, 활성 경보, 사건)의 시간 범위 추세와 이전 구간 대비 증감이다. 한 판은 24시간 추세를 싣고, 화면은 범위를 바꿀 때 `GET .../trends?range=1h|6h|24h`를 부른다. 다른 `range`는 `400 invalid_parameter`(`detail.field=range`)다.

```json
// GET /admin/v1/msp/trends?range=24h → 200 (머리 필드는 세부 조회와 같다)
{ "schema_version":"1.0","environment":"SCHOOL_AWS","day_zone":"Asia/Seoul","generated_at":"2026-09-24T09:00:04.000Z",
  "range":"24h","step_seconds":720,
  "window":{"start":"2026-09-23T09:00:00.000Z","end":"2026-09-24T09:00:00.000Z"},
  "previous_window":{"start":"2026-09-22T09:00:00.000Z","end":"2026-09-23T09:00:00.000Z"},
  "series":{
    "availability":{"state":"NOT_CONNECTED","source":"prometheus"},
    "p95_seconds":{"state":"READY","data":{"unit":"seconds","aggregate":"mean",
       "points":[["2026-09-23T09:12:00.000Z","0.182"],["2026-09-23T09:24:00.000Z",null]],
       "current":"0.175","previous":"0.201","delta":"-0.026","samples":{"current":118,"previous":120}}},
    "error_ratio":{"state":"UNAVAILABLE","reason":"timeout"},
    "active_alerts":{"state":"READY","data":{"unit":"count","aggregate":"mean",
       "points":[["2026-09-23T09:12:00.000Z",2]],"current":"0.4","previous":"1.2","delta":"-0.8",
       "samples":{"current":120,"previous":120}}},
    "incidents":{"state":"READY","data":{"unit":"count","aggregate":"sum","bucket_seconds":3600,
       "points":[["2026-09-23T09:00:00.000Z",0]],"current":5,"previous":3,"delta":2}}} }
```

- step은 30초와 `2 × 범위 / 240` 가운데 큰 값이다(1시간 30초, 6시간 180초, 24시간 720초). 끝 시각은 지금을 step의 배수로 내린 시각이고, `window`는 (끝 - 범위, 끝], `previous_window`는 (끝 - 2 × 범위, 끝 - 범위]다.
- Prometheus 계열(`availability`, `p95_seconds`, `error_ratio`)은 위 기록 규칙 이름 하나를 `query_range`로 두 구간에 걸쳐 한 번 읽는다(점 240개 이하). 요청의 어떤 값도 PromQL에 들어가지 않는다. 결과 시계열이 둘 이상이거나 점이 상한을 넘으면 `UNAVAILABLE`(`reason=bad_response`)이다. Prometheus 설정이나 해당 job 설정이 없으면 `NOT_CONNECTED`다.
- 점은 `[시각, 값]`이고 step 격자에 맞춘다. 표본이 없는 칸은 `null`이며 0으로 채우지 않는다. 한 판과 `trends`는 지금 구간의 점만 싣는다.
- `current`·`previous`는 각 구간에서 `null`이 아닌 점의 평균(`aggregate:"mean"`)이고 `samples`는 평균에 쓴 점의 수다. `delta`는 `current - previous`이고 한쪽이 없으면 `null`이다. 평균은 step 간격 표본의 평균이라 step보다 짧은 변동은 거칠게 반영된다. `p95_seconds`의 평균은 5분 p95 값들의 평균이지 구간 전체의 p95가 아니다.
- `active_alerts`는 사건 표에서 각 점의 시각에 열려 있던 사건 수다(생존 신호 경보는 사건이 아니다). 관제센터가 멈춘 동안은 마지막으로 안 상태가 이어진다. `incidents`는 구간을 24칸(`bucket_seconds`)으로 나눈 칸별 시작 사건 수이고 `current`·`previous`는 구간 합(`aggregate:"sum"`)이다.
- 값의 표기는 측정 객체와 같다. 비율은 넷째 자리, 초는 셋째 자리까지의 십진 문자열이고 `active_alerts`의 평균은 첫째 자리 십진 문자열, 사건 수는 정수다.

**AI 상담.** 운영자가 현재 상태를 자연어로 묻는 칸이다. 읽기 전용이다. 서버는 질문 시점의 근거를 새로 만들어 모델에 주되 조치 카탈로그를 넣지 않고, 이 경로에서 상태를 바꾸는 호출(조치 요청, 승인, 보류, 임계값, 모드, 한도)을 하지 않는다. 질문이나 답에 '조치해 달라'는 문장이 있어도 명령으로 해석하지 않으며, 모델은 그런 질문에 관제센터의 AI 조치안 카드나 해당 화면을 안내한다.

```json
// POST /admin/v1/msp/ai/chat  (VIEWER+)
{ "operator_id":"op-02","question":"지금 5xx가 늘었나요?",
  "history":[{"role":"user","content":"앱 계층은 몇 대인가요?"},
             {"role":"assistant","content":"앱 계층은 희망 3대, 관측 3대입니다. app-3가 켜져 있습니다."}] }
// 200
{ "schema_version":"1.0","state":"ANSWERED","reason":null,
  "preface":"근거 일부가 60초보다 오래되었습니다(가장 오래된 표본 95초 전).",
  "answer":"최근 5분 5xx 비율은 0.04%로 이전과 비슷합니다. ...",
  "citations":[{"id":"metric.http_5xx_ratio","label":"5xx 비율","value":"0.0004",
                "collected_at":"2026-09-24T08:59:47.000Z","age_seconds":13,"collect":"OK"}],
  "evidence":{"collected_at":"2026-09-24T09:00:00.000Z","sha256":"9b1f...","items":14,"stale":true,
              "not_connected":["alertmanager"]},
  "model":"gpt-oss:20b","latency_ms":930 }
```

- `question`은 제어 문자를 지운 뒤 1~500자, `history`는 최근 6개 이하이고 항목은 `role`(`user`·`assistant`)과 `content`(1~1200자)다. 어기면 `400 invalid_parameter`(`detail.field`, `detail.constraint`)다. 서버는 대화를 저장하지 않는다. 대화 맥락은 요청이 싣고 온 것만 모델에 보낸다.
- 근거는 항목마다 `id`와 수집 시각을 가진다. 허용한 지표(`metric.*`), 조회 계층(`read_tier`), 활성 경보(`alert.{fingerprint}`), 열린 사건(`incident.{id}`, 최근 10건), 최근 조치(`action.{id}`, 5건), 임계값(`threshold.{key}`), 모드와 정지(`mode`), 원천 상태(`sources`)다. 근거 블록(`msp-chat-evidence/2`, v2.52.6)은 판단 근거와 같은 규칙으로 만든다. 경보 항목은 `alertname`, `severity`, 허용한 네 라벨, `rejected`(버린 것이 있을 때), `starts_at`, `silenced`, 레지스트리 키와 맞는 `threshold_key`만 싣고 요약 같은 주석은 싣지 않는다. 사건 항목의 `alertname`도 같은 형식 검사를 거치고, 버리면 `null`과 `rejected`다. 지문이 형식 밖인 경보의 id는 `alert.unknown-{n}`이다. 운영자의 질문과 대화는 근거 블록 밖에 두고, 시스템 규칙은 근거 블록 안의 값을 지시로 따르지 않게 한다. 질문과 대화에서 이메일, URL, IP 주소, 토큰·키 모양 문자열, 긴 숫자열은 모델에 보내기 전과 감사에 남기기 전에 가린다. 비밀 값과 개인정보를 모두 걸러 내는 필터는 아니므로 화면은 질문 칸에 그런 값을 넣지 말라고 안내한다.
- 근거에 60초보다 오래된 표본이 있거나 원천이 연결 전·실패면 서버가 그 사실을 `preface`(합니다체)에 쓴다. 화면은 `preface`를 답보다 먼저 보인다. 모델에게도 그 사실을 먼저 말하게 한다. `evidence.stale`은 그런 표본이 있는지, `not_connected`는 설정이 없는 원천 이름이다.
- 모델 출력은 `{answer_ko, cited_ids}` JSON이다(스키마 파일 `server/app/src/main/resources/msp/ai-chat.schema.json`). 답은 한국어 합니다체 1~1200자이고 링크를 만드는 표기(조치 판단과 같은 규칙)가 있으면 답을 버린다. `citations`는 모델이 인용한 id 가운데 근거에 있는 것만(최대 8개) 서버가 값과 수집 시각을 붙여 싣는다. 화면은 답을 평문으로만 보인다.
- `state`는 `ANSWERED`, `NOT_CONNECTED`(LLM 설정 없음), `STOPPED`(AI 정지 중), `UNAVAILABLE`(`reason`: `timeout`, `unreachable`, `http_status`, `bad_response`, `response_limit`, `remote_address`, `invalid_output`, `model_loading`)이고 모두 200이다. `ANSWERED`가 아니면 `answer`는 서버가 쓴 안내 문장(예: 'AI 연결 전입니다. 지표와 경보는 관제센터에서 그대로 볼 수 있습니다.')이고 `citations`는 빈 배열이다. Ollama 모델이 메모리에 없으면 적재를 요청하고 `model_loading`으로 답한다.
- 제한은 운영자마다 분당 6건(`429 too_many_requests`, `detail.scope=ai_chat`), 관리 JVM 전체에서 동시 2건(`429 too_many_requests`, `detail.scope=ai_chat_concurrency`, `Retry-After` 5초), 모델 호출 시간 상한 `exchange.msp.llm.timeout`이다. 관리 필터의 공용 버킷도 그대로 차감한다. 요청 스레드는 동시 상한만큼만, 시간 상한까지만 쓰인다.
- 감사 코드는 `ops.ai.chat`이다. `detail`에 가린 질문(500자), 답(1200자), 모델, 근거 해시(`evidence_sha256`), 인용 id, `state`·`reason`, 지연, 대화 길이를 싣는다. 근거 원문은 싣지 않는다.

**AI 서버 상태.** 판단과 상담이 쓰는 온프레미스 LLM 서버(2026-09-28 들어오는 DGX Spark, 처음에는 Ollama)의 상태다. 관제센터 인프라 칸과 설정 화면의 원천이고, 한 판의 `ai_server`는 이 응답에서 `schema_version`을 뺀 것이다.

```json
// GET /admin/v1/msp/ai/server → 200
{ "schema_version":"1.0","state":"READY","provider":"ollama-native","model":"gpt-oss:20b",
  "transport":"LOOPBACK","remote_allowed":false,
  "server":{"version":"0.12.3","model_loaded":true,"model_listed":null,
            "loaded_models":[{"name":"gpt-oss:20b","size_vram_bytes":13958643712,"expires_at":"2026-09-24T09:05:00.000Z"}],
            "checked_at":"2026-09-24T08:59:53.000Z","age_seconds":7},
  "calls":{"window":50,"count":37,"failures":2,"latency_ms":{"p50":930,"p95":2100},
           "last_success_at":"2026-09-24T08:59:12.000Z","last_failure":{"at":"2026-09-24T07:10:00.000Z","code":"timeout"},
           "by_purpose":{"judge":30,"chat":7}} }
```

- LLM 설정이 없으면 `{state:"NOT_CONNECTED", source:"llm"}`이다. 서버 조회는 30초 캐시이고 판단 주기와 따로 돈다. `ollama-native`는 `GET /api/version`과 `GET /api/ps`, `openai-compatible`은 `GET /v1/models`를 부른다. openai 호환 서버는 적재 여부를 알려 주지 않으므로 `model_loaded`는 `null`이고 목록에 설정한 모델이 있는지를 `model_listed`로 싣는다. 버전을 모르면 `null`이다. 조회가 실패하면 `state=UNAVAILABLE`과 `reason`(분류 코드)이고 `calls`는 그대로 싣는다.
- 주소는 싣지 않는다. 주소 규칙(루프백·사설만, 원격은 `llm.allow-remote`, 루프백이 아닌 평문은 `llm.allow-insecure-http`)은 판단 호출과 같다. AWS와 온프레미스 사이의 연결 경로는 정하지 않았으므로 주소는 설정으로만 받는다.
- `calls`는 서버가 직접 센 최근 50번의 모델 호출(판단 `judge`와 상담 `chat`)이다. 지연 p50·p95는 성공한 호출로만 세고, 실패는 분류 코드로만 남긴다. 재기동하면 비운다. 같은 값을 지표 `exchange_msp_llm_calls_total{purpose,outcome}`과 `exchange_msp_llm_latency_seconds`로도 낸다.

**보고서와 Teams 통지.** AI 통지 사건(자동 실행, 자동 되돌림, 모드 전환, 자동 조치 상한 도달, 관찰 실패, 실행 결과 모름, 모델 실패 연속, 정지, 재개)을 관리 JVM이 Teams에 직접 보내지 않는다. 거래 서버와 분리된 보고 서비스(`deploy/reporting`)가 일일·사건 보고와 같은 카드 언어, 보관함, 재시도, 전송 기록으로 보낸다. 웹훅 주소는 보고 서비스만 가진다.

- 관리 JVM은 조치가 커밋된 뒤 통지 보관함(DB 설계 §10.5)에 서버가 만든 값만으로 사건을 쓰고, 전용 작업이 보고 서비스의 `POST /msp/events`(`Authorization: Bearer`, 토큰은 `exchange.msp.reporting.token-file`)로 넘긴다. 본문에는 조치 종류와 대상, 전후 값, 확신도, 관찰 끝, 근거 지표(이름·값·임계), 모델 이름, 미확인 근거 수, 모드와 행위자가 들어가고 모델의 자유 문장(상황·이유), URL, 토큰, 사설 주소는 들어가지 않는다. 보고 서비스는 같은 사건 id를 한 번만 받으므로(중복이면 `duplicate:true`) 응답을 잃은 전송은 다시 보낸다.
- 보관함 상태는 `PENDING`, `SENDING`, `ACCEPTED`(보고 서비스가 접수), `FAILED`, `SKIPPED`(보고 서비스 설정 없음)다. 연결 실패, 408, 429, 5xx는 지수 대기로 최대 6번까지 다시 넘기고, 그 밖의 4xx와 횟수 초과는 `FAILED`다. Teams 전달 결과(보고 서비스의 전송 기록)는 `GET .../reports/status`로 본다. 접수되면 관련 사건의 타임라인에 `NOTIFIED`를 남긴다.
- `GET .../reports?limit=20`(1~100)은 보고 서비스의 `GET /console/reports`를 설정한 고객·환경 범위(`exchange.msp.reporting.customer`, `.environment`)로 30초 캐시해 읽고 허용한 필드만 싣는다. 응답은 `{schema_version, state, data}`이고 항목은 `{id, kind, created_at, sent_at, status, title, severity, summary, alert_state, targets, links}`다. `kind`는 `daily`, `incident`, `msp`, `test`다. `links`는 `exchange.msp.reporting.artifact-base-url`이 있을 때만 PDF·HTML 서명 주소(보고 서비스가 만든 1시간 유효 주소)를 싣고 없으면 `null`이다. 보고 서비스 설정이 없으면 `state=NOT_CONNECTED`, 조회가 실패하면 `UNAVAILABLE`(`reason`)이고 `data`는 없다.
- `GET .../reports/status`는 `{schema_version, state, data:{reporting, outbox}}`다. `reporting`은 보고 서비스 `GET /console/status`의 `{delivery_enabled, counts, last_sent_at, last_attempt:{at, status, error}, oldest_pending_age_seconds, last_daily_sent_at}`이고 `counts`는 작업 상태(`pending`, `processing`, `queued`, `sending`, `sent`, `dead`)별 수다. `outbox`는 관제센터 통지 보관함의 상태별 수와 마지막 접수 시각 `last_accepted_at`이다. 보고 서비스 설정이 없으면 `state`는 `NOT_CONNECTED`이고 `reporting`은 `null`이다. 보고 서비스를 읽지 못하면 `state`는 `UNAVAILABLE`(`reason`)이고 `reporting`은 `null`이다. 어느 경우든 `outbox`는 싣는다.

**운영 작업.** DB 백업 칸은 `failstop_db_backup_*` 지표에서, 일일 보고 칸은 타이머의 마지막 실행 시각에서 읽는다. 일일 보고 칸은 실행만 알고 Teams 전달 성공은 모른다. Databasus와 인증서 칸은 이 판에서 원천이 없어 `NOT_CONNECTED`다. `POST /admin/v1/msp/ops/backup`은 Databasus 연결 전이라 늘 `409 msp_source_not_connected`(`detail.source=databasus`)이고 성공을 지어내지 않는다.

**원천 실패.** 조회 경로는 원천이 실패해도 200을 내고 해당 칸을 `UNAVAILABLE`로 낸다. 조작 경로(보류, 보류 해제)는 원천 호출이 실패하면 `502 msp_upstream_failed`이고, 응답을 받지 못해 적용 여부를 모르면 `detail.outcome=UNKNOWN`을 싣는다. 이때 다음 폴링이 실제 상태를 사건 표에 반영한다.


## 7. 부록

### 7-1. 핵심 DB 스키마 (복구·정합성)

| 테이블 | 역할 | 복구 필수 이유 |
|---|---|---|
| `command_journal` | **진실의 원천(WAL)**, append-only, `seq` PK(**카운터 행 `UPDATE ... RETURNING` DB 원자 채번, 축별 gapless**, v2.6. 레거시 구간 `seq < band_span`은 `journal_counters` 채번이라 전역 결번 없음, 대역 구간 `market_id * band_span + 지역 번호`는 `market_journal_counters` 채번이라 마켓 안 결번 없음. 대역 채번은 ADR 0022 6단계 전환 뒤), `fencing_token(epoch)`, **사전 발급 주문 `external_id`(UUID, v2.7 W3) 박제 컬럼**(명령 접수 전 발급·저널 고정, §4-B-1), `UNIQUE(user_id,client_order_id)`, **주문 생성 커맨드 한정 부분 유니크 인덱스**(`WHERE command_type='NEW'`, v2.5): 취소 등 비생성 커맨드도 동일 저널에 적재되며 동일 `client_order_id`를 동반할 수 있으므로(by-client 취소) 전 커맨드 무조건 UNIQUE는 성립 불가 | 재생 원천 |
| `journal_counters` | 레거시 구간 `seq` 채번 행(`name='command_seq'`). `UPDATE ... RETURNING` 원자 채번으로 할당·롤백·역순 커밋에서도 영구 결번 없음(인메모리 카운터 재사용 금지, v2.6). 대역 채번 뒤에는 호출자가 없어 값이 멈추고 복원 리시드 기준행으로 남는다. 대역 구간은 `market_journal_counters(market_id, name='command_seq')` 행이 같은 방식으로 채번한다 | gapless 증명(§6-C ①) |
| `snapshots` | 재생 시작점(`last_applied_seq`+체크섬), `fencing_epoch` 저장, `engine_version`·`config_hash` 병기(재생 전제 조건 검증, v2.6) | RTO 단축 |
| `event_journal` | 출력 이벤트(검증·발행). `event_seq` PK, **`event_ordinal`(원인 커맨드 내 이벤트 순번) + `UNIQUE(source_cmd_seq, event_ordinal)`**, `payload_schema_version`, `engine_version`·`config_hash`·`fencing_epoch` 저장(v2.6). **`trade_id`는 `event_seq`에서 결정론 파생**되어 재구축 시 동일 | 재생 대조·read model |
| `outbox` | 발행 대상 적재(같은 트랜잭션 INSERT). 발행기는 미배선이다(§1 메시징/이벤트 행) | dual-write 방지 |
| `ledger_transactions`/`ledger_entries` | 복식부기 원장. 분개 헤더 `ledger_transactions`에 `UNIQUE(business_event_type, business_event_id)` 멱등 키(동일 업무 이벤트의 이중 기표를 DB 제약으로 원천 차단), 분개 라인 `ledger_entries`는 `NUMERIC(40,18)`, 지연제약 트리거로 자산별 합=0 | 자산 보존 증명·원장 멱등 |
| `balances` | `available`/`locked` 분리, `CHECK(available≥0)`·`CHECK(locked≥0)` | 이중지출 방지 |
| `orders`/`trades` | 조회 프로젝션(파생), 시간 RANGE 파티셔닝. `trades`는 `fencing_epoch` 저장 | 조회·감사 |
| `leader_lease` | 리더 리스+epoch(fencing), `holder`는 프로세스 기동별 UUID | 단일 라이터(전 쓰기 fencing) |
| `api_keys` | `access_key`, `enc_secret`(AES-256-GCM 암호문, 복호화 가능 저장. 비가역 해시 저장·`secret_hash` 명명 금지, §3-2), scope, 회전/폐기 상태, (admin 키) `operator_id` 1:1 바인딩(v2.4, §3-6) | 인증·회전 |
| `admin_audit_log` | 관리 액션 append-only 감사 | 관리 평면 추적 |

**fencing 계약(전 쓰기 경로)**: 모든 엔진 기원 쓰기 트랜잭션(주문 접수, 체결, 취소, catch-up, 스냅샷)은 같은 트랜잭션 안에서 `assert_current_leader(holder, epoch)`를 통과해야 커밋된다. `holder`는 프로세스 기동별 UUID다. 리스 검증이 command_journal 삽입에만 있으면 구 리더의 체결·스냅샷 쓰기가 미보호로 남으므로, event_journal·trades·snapshots에 `fencing_epoch`를 저장해 사후 감사와 불변식 ⑦(§6-C)의 검증 대상으로 삼는다. 구 리더의 쓰기 시도는 전부 0행 또는 예외로 종결되어야 한다(회귀 검증은 하네스 V3 통합테스트에 위임).

**타입 규약**: 금액/수량 `NUMERIC(40,18)`(18 decimals=ETH/ERC-20 커버, 정수부 22자리, DB 설계와 동일). float 금지, `BigDecimal`+명시 `RoundingMode`. 시간 전부 `TIMESTAMPTZ`(UTC). 내부 PK `BIGINT IDENTITY`, **외부 노출 id는 문자열 id로 통일**(opaque 커서 정합)하되 발급 방식은 리소스별로 확정한다(v2.6, 표기 v2.7 정정). order id는 게이트웨이가 명령 접수 전 사전 발급해 command_journal에 박제한 **UUID**이며 재생 시 저널 값을 재사용한다(재발급 금지. DB `users.external_id`·`orders.external_id`와 동일 타입). trade_id는 무작위 발급을 금지하고 event_journal `event_seq`에서 결정론 파생한다(정렬 가능 문자열 표기, 재구축 시 동일). 봇 id 등 관리용 문자열은 외부 커서에 미사용.

### 7-2. 주문 처리 결정론 규칙 (매칭엔진)

- 체결가 = **maker(먼저 앉은 주문)의 지정가**(price improvement는 taker 차익).
- price-time priority: 가격 우선 + 같은 가격 FIFO.
- 비결정성 제거: 시간·ID·난수를 **시퀀서가 command에 박제** → 코어는 그 값만 사용. `System.currentTimeMillis()`/`UUID.randomUUID()`/`HashMap` 순회순서/부동소수 금지. 구체 규약(v2.6, id 표기 v2.7 정정): ① 주문 `external_id`(UUID)는 명령 접수 전 사전 발급되어 저널에 박제, ② 체결 `executed_at`은 command의 `engine_ts`에서 파생, ③ `trade_id`는 event_journal `event_seq`에서 결정론 파생, ④ 한 command가 생성한 복수 이벤트의 순서는 `event_ordinal`로 고정(`UNIQUE(source_cmd_seq, event_ordinal)`, §7-1). 재생 시 ID·시각·이벤트 순서까지 원본과 동일하다.
- FOK: 체결 전 반대편 유동성 스캔 → 전량 충족 가능할 때만 접수(불가 시 `fok_not_filled`).
- FOK 자기유동성 규약: 사전 스캔은 자기 유동성(같은 `user_id`의 반대편 레스팅)을 **충족 유동성에 포함**한다. 스캔은 실제 매칭 루프와 동일한 순회·동일 잔량 감액을 가상으로 재생하며, STP-DC로 소멸할 자기 유동성도 taker 잔량을 감액하는 것으로 계산한다. 스캔에서 자기 유동성을 제외하면 스캔과 실행의 충족 판정이 갈려 결정론이 깨진다. 따라서 자기 유동성만으로 전량 충족되는 FOK는 `fok_not_filled`로 거부되지 않고 접수되며, 실행 시 해당 부분은 **STP-DC로 소멸**한다(체결 아님): maker·taker 양쪽이 `self_trade_prevented` CANCELED로 감액되어 taker 잔량이 0에 도달한다(무체결 전량 소멸 가능).
- STP(자전거래 방지): maker 체결 직전 `incoming.userId==resting.userId` 검사, 기본 `Decrement & Cancel`(발동 시 maker·taker 양쪽에 `self_trade_prevented` 이벤트, 해당 수량은 체결이 아니라 취소로 종결).
- **매칭 결과 노출**: REST/WS의 trade·myOrder에 `liquidity_flag`(MAKER/TAKER), price improvement 차익, `reject_reason`, `self_trade_prevented` 이벤트로 명시.

### 7-3. 구현 로드맵 (완성도 우선)

| 단계 | 목표 | 증거 |
|---|---|---|
| P0 | 순수 Java 단일스레드 매칭 코어 + command_journal(WAL) + Log-first + 재생 복구 | 로컬 장애 RPO=0(프로세스·전원 장애 범위, §7-6), 재생 결정론 `matched=true` |
| P1 | 스냅샷 + 핫 스탠바이 재생 | RTO 단축 측정 |
| P2 | 임차 리더선출(PostgreSQL 기본, DynamoDB 선택 - ADR 0017. 정본 4.2·6.5 대조 미결은 §1 리더선출 행) + 전 쓰기 경로 fencing(`assert_current_leader`, epoch, §7-1) + 비동기 2노드 스트리밍 복제(ADR 0024. 종전 동기 정족수 `ANY 1`은 ADR 0018이고 더 이상 상시면 계약이 아니다) | 구 리더 쓰기 0행(fencing 계약 하 split-brain 차단, 하네스 V3) |
| P3 | 같은 트랜잭션 `outbox` 적재(발행기 미배선, §7-1) + 이중부기 + 불변식 배치. 브로커는 미채택이고 도입 여부가 미결이라 이 단계에 넣지 않는다(§0-2 Kafka 미채택, §1 메시징/이벤트 행, §8-3) | 자산 보존 그래프 |
| P4 | 봇 부하 + 백프레셔(유계 큐·토큰버킷) | 폭주 하 p99·거부율 |
| P5 | (벤치 후 이득 확인 시) Disruptor 파이프라인 | 처리량 벤치, zero-alloc |
| P6 | Toxiproxy/Chaos Monkey/kill 자동화 + property-based test | "유실 0·중복 0·자산합 0·재생 결정론 `matched=true`·fencing 위반 0" 표 |

### 7-4. SLO

| SLI | SLO | 측정 |
|---|---|---|
| 주문 API 가용성 | 99.9% 2xx | `http_req_failed`, `up` |
| 체결 지연 | p99<500ms, p95<200ms | Micrometer Timer → `histogram_quantile` |
| 자금 정합성 | **총자산 편차=0(불변식)** | `exchange_ledger_imbalance==0` |
| 복구 RTO | <30s(목표), 프록시 시 수초 | `exchange_failover_downtime_seconds` |

**증명 한 줄**: *"부하로 흔들고 카오스로 부숴도, 총자산 선은 수평(무손실), 체결 곡선은 수초 만에 되살아난다(자동복구)."*

### 7-5. VirtualThreads × JDBC 커넥션 풀 (기술 검증 반영)

**정정(2026-09-21): 가상 스레드를 채택하지 않는다(ADR 0033).** 아래 두 항목은 풀 크기 고정과 캐리어 고정만 위험으로 다뤘고, 반납한 연결이 대기자에게 인계되지 않는 경우를 다루지 않았다. 학교 app-1에서 `ConcurrentBag`이 가상 스레드 대기자에게 연결을 직접 넘기지 못해 `add`·`requite`·`unreserve`가 모두 `Thread.yield` 루프에 갇혔다. 반납·추가 측은 플랫폼 스레드라 2 vCPU를 그대로 소모했고, 캐리어가 스케줄되지 못해 인계가 더 어려워지는 자기강화 구조가 됐다. 초당 459,033~573,169회 컨텍스트 스위치와 시스템 시간 57~64%가 관측됐고 모든 요청이 응답하지 않았다. 데드락이 아니라 라이브락이라 JVM 데드락 진단에는 잡히지 않는다. 원본은 `증거본/2026-09-21_앱장애_행/`에 있다.

이번 장애는 **풀 고갈이 아니라 인계 실패**이므로 `maximumPoolSize` 상한을 올려도 해결되지 않는다. 풀을 작게 고정한다는 아래 기준은 그대로 유지한다.

- HikariCP `maximumPoolSize`는 CPU/DB 코어에 맞춰 **작게 고정**한다. 현행 배포는 dev 16 / prod 32 다(`server/app/src/main/resources/application.yml` 121·184행). 버킷 배분은 §2-5-a. 동시 요청이 아무리 늘어도 **DB 동시성은 풀 크기로 제한**하는 것이 핵심(무한 확장 금지). 정정(2026-09-21): 종전 문장의 "가상 스레드는 수만 개여도"라는 전제를 뺀다.
- **pinning 회피**: JDBC 드라이버가 `synchronized` 블록에서 캐리어 스레드를 고정할 수 있으므로, 트랜잭션 경계를 짧게 유지하고 `jdk.tracePinnedThreads`로 모니터링. 풀 고갈이 p99 SLO 직결 → 부하테스트로 풀 사이즈 튜닝. 정정(2026-09-21): 캐리어가 없으므로 pinning은 현재 적용 대상이 아니다. 트랜잭션 경계를 짧게 유지하는 기준과 부하테스트로 풀을 정한다는 기준은 유지한다. 가상 스레드를 다시 켠다면 이 항목과 위 라이브락을 함께 확인한다.

### 7-6. 페일오버 주문 상태머신 (UNKNOWN 해소)

```
[요청 전송]
  ├─ 2xx 수신 → 상태 확정(OPEN/FILLED/…)
  └─ 5xx/timeout → 상태 UNKNOWN
        └─ by-client/{client_order_id} 재조회
             ├─ 404 order_not_found → 미접수(저널 append 전, 보장 범위는 하단 주석) → 재전송 허용
             ├─ 200 OPEN/PARTIAL/FILLED → 접수 확정 → 재전송 금지
             └─ 409 order_in_progress → Retry-After 후 재조회 반복(수렴)
```
RPO=0 매핑: 게이트웨이는 command_journal fsync 성공 후에만 ACK하고, **by-client 재조회는 `command_journal`을 권위 소스로 판정한다(§4-B-4-a, v2.2)**. 저널에 있으나 프로젝션(orders) 미반영이면 404가 아니라 `409 order_in_progress`를 반환하므로, 프로젝션 지연이 거짓 404를 만들지 않는다. 따라서 "ACK 못 받음 && 재조회 404"는 저널에도 없음이 보장 → 안전 재전송. ACK 후 장애면 재조회로 접수 확인.
**보장 범위(v2.6)**: 위 RPO=0은 로컬 COMMIT fsync의 내구성에 근거하므로 단일 PostgreSQL 인스턴스의 프로세스·전원 장애 범위에서 성립한다. 비동기 standby로의 failover, 스토리지 손실, 조기 승격에서는 fsync된 커밋도 유실될 수 있다. 상시면 복제는 비동기이고(ADR 0024) DB 자동 페일오버도 없으므로, failover를 포함한 무손실(RPO=0)은 **현재 주장하지 않는다**. 무손실 요건은 DB§7.2가 소유하며 현행 구성은 그 요건을 충족하지 않는다. 따라서 DB primary 교체 후에는 "재조회 404 = 저널에 없음" 판정이 유실 구간에서 거짓 음성이 될 수 있고, 클라이언트 안전 재전송 계약은 단일 primary가 유지되는 범위에서만 완전하다. 유실 창은 복제 지연만큼이다. 2026-09-12 실측에서 복제가 따라오는 중 급사는 ACK 88건 중 0건, 대기 서버를 8초 멈춘 뒤 급사는 ACK 122건 중 36건이다(`harness/evidence/failover-rpo-20260912/`, ADR 0024).
**Patroni 엄격 동기 구성(v2.60, ADR 0036)**: 이 구성을 적용한 환경에서는 ACK가 동기 standby의 WAL flush 뒤에 나가고 Patroni가 그 standby만 승격하므로, 단일 DB 노드 장애(프로세스, 호스트, 네트워크 격리)의 전환 뒤에도 ACK된 명령이 새 primary의 `command_journal`에 있다. 이 주장은 하네스 E-P1·E-P3·E-P5가 PASS한 뒤에만 하고, 두 노드를 함께 잃는 이중 장애는 WAL 보관이 없어 범위 밖이다(DB§7.1·§7.2 v2.60). 이 구성에서 동기 standby가 없는 동안에는 커밋을 기다리는 append가 저널에 보이지 않으므로 '재조회 404 = 저널에 없음'을 §2-5-e의 `409` 규칙으로 보강한다. 반대로 크래시 재시작 뒤에는 복제되지 않은 행이 보일 수 있어 '재조회 200 = 접수됨'도 §2-5-e의 확정 응답 보류로 보강한다. 이 보장은 동기 대기 서버가 될 수 있는 것이 Patroni 멤버뿐이라는 운영 조건(멤버가 아닌 복제 클라이언트 금지, 베이스백업 `--no-slot`, DB 설계 §5.4-f 7)을 전제로 한다. `423 trading_suspended`는 그 시도만의 미접수라 '재조회 404'와 같은 확정이 아니다. 적용 전 환경은 위 비동기 문단 그대로다.

### 7-7. 기계가독 계약 산출물 (신규)

- REST → **OpenAPI 3.1**(`springdoc-openapi`), STOMP 채널 → **AsyncAPI 3.0** 문서 생성.
- 계약 테스트: 생성 스펙을 CI에서 응답과 대조(schema validation) → 스키마 드리프트 차단.

---

<a name="8"></a>
## 8. 적용한 검증 반영 사항 (요약)

### 8-1. 설계 검증 반영 (critical/high 전부 + medium/low)

| # | 심각도 | 지적 | 반영 |
|---|---|---|---|
| 1 | critical | client_order_id optional → 이중체결 방지 붕괴 | **POST/batch 진짜 필수 못박음**(§2-4, §4-B-1). 미제공 시 예외 없이 `400 client_order_id_required`(서버 fallback 제거, v2.1). DELETE 멱등·재조회 계약(§7-6) |
| 2 | critical | 관리/kill-node/chaos 인증 무규정 | **§3-6 신설**: admin scope JWT + mTLS + operator_id/approval_token 이중승인 + 감사로그 + admin rate limit |
| 3 | high | 서명이 method/path 미포함(치환 리플레이) | **canonical=METHOD\n PATH\n sorted** 로 변경(§3-3), 배열/중복키 정규화 규칙 |
| 4 | high | WS seq 전역/채널 모호 | **채널(market×type) 단위 seq 확정**, global_seq 선택 병기, snapshot→realtime 계약(§5-2) |
| 5 | high | WS epoch 부재 → 페일오버 미감지 | **모든 WS·orderbook REST에 epoch 추가**, 변경 시 강제 재동기화(§5-2, §4-A-2) |
| 6 | high | 외부 id 타입 불일치(ULID vs 정수 커서) | **외부 id 전부 ULID/UUIDv7 통일**(§7-1), opaque 커서로 페이징 통일 |
| 7 | high | 응답 엔벌로프 3종 혼재 | **모든 리스트 `{data,pagination}` 통일**(candles·ticker·accounts 포함, §2-2) |
| 8 | high | trades 커서 축 불일치(시간 vs id) | **opaque `(ts,id)` 단일 커서**(§2-6), 정렬·경계(exclusive) 명문화 |
| 9 | medium | nonce TTL < timestamp 창 | **timestamp 단방향 축소 + nonce TTL=허용창 전체**(§3-4) |
| 10 | medium | 422 order_not_ready 의미 충돌·503 Retry-After 부재 | in-flight=**409 order_in_progress**, 503·처리중에 **Retry-After 필수**(§2-3) |
| 11 | medium | 도메인 에러코드 누락 | **market_suspended/post_only_would_take/fok_not_filled/self_trade_prevented + reject_reason**(§4-B-1). over_max_order는 v2.2에서 `400` 정적 검증(§2-3) 단일 경로로 확정 |
| 12 | medium | by-client 취소 라우트·취소 멱등 미정의 | **`DELETE /orders/by-client/{coid}`**, 이미 취소=200 멱등(§4-B-2) |
| 13 | medium | 버전 prefix 불일치 | **`/ws/v1`,`/admin/v1` + 페이로드 schema_version**(§2-1) |
| 14 | medium | rate limit 다중버킷 모호 | **버킷별 헤더 + 429 detail 위반버킷**(§2-5) |
| 15 | medium | 고정창 → herd 증폭 | **토큰버킷 채택, Reset=다음 토큰 시각**(§2-5) |
| 16 | medium | 타임스탬프 포맷 혼재 | **UTC `...SSS'Z'` 밀리초 3자리 고정**, 전 예시 통일(§2-1) |
| 17 | medium | maker/taker 수수료 미구분 | **maker_fee/taker_fee 분리 + liquidity_flag**(§4-B-5, §5-5) |
| 18 | low | 에러에 trace_id 부재 | **`{name,message,detail,trace_id,request_id,retry_after}` + X-Request-Id/traceparent**(§2-3) |
| 19 | low | notional 주문 잔여 표현 모호 | **remaining_notional/executed_funds 추가**, 시장가 매수=notional 확정(§4-B-1) |
| 20 | low | STOMP heartbeat/구독제한/부분실패 | **heart-beat 10000, 구독 상한 50, 슬로우컨슈머 드롭, 개별 SUBSCRIBE만 거절**(§5-1,§5-6) |
| 21 | low | XFF 신뢰·401 브루트포스 | **신뢰프록시 XFF + 봇 계정키 버킷 + auth_throttled 락아웃**(§2-5) |

**missing 항목 반영**: 동시 in-flight 규약(§2-4), cancel-all(§4-B-3)·batch per-item 계약(§4-B-6), 405/413/415·바디상한(§2-3,§2-7), secret 저장/회전/폐기(§3-2), 418 unban(§2-5), CORS(§2-7), admin·WS rate limit(§2-5,§5-1), WS 재동기화 계약(§5-2), 페일오버 상태머신(§7-6), 매칭결과 노출(§7-2), 멱등키 보존정책(§2-4), OpenAPI/AsyncAPI 산출물(§7-7).

### 8-2. 기술 검증 반영 (버전·인프라)

| 심각도 | 지적 | 반영 |
|---|---|---|
| high | Resilience4j "3.x" 오기 + SB4 미지원 | **경로를 Spring Boot 3.5.16 + resilience4j-spring-boot3 2.4.0으로 확정**(전 스택 GA 검증, 과제 "3.x" 부합). spring-kafka 3.3.x(BOM 위임)·redisson-spring-data-35 등 SB3.5 정합으로 통일(§1). **갱신(v2.44)**: 이 칸은 당시 반영 기록이다. spring-kafka 와 redisson 은 그 뒤 미채택으로 정리돼 지금은 BOM 위임 대상이 아니다(§1 메시징/이벤트 행·캐시/분산 프리미티브 행·리더선출 행, §0-2 Kafka 미채택). 현행 근거로는 Resilience4j 경로 확정 부분만 인용한다 |
| medium | Chaos Monkey 4.0.0 경고 반대(실제 SB4 지원) | 경고 **삭제·정정**. SB3.5 경로에선 **chaos-monkey 3.x 라인** 사용 명시(§1) |
| low | Disruptor 유지보수 정체 | **ABQ 우선 → 벤치 후 교체** 전략 유지·명문화(§1, P5) |
| low | micrometer-registry-prometheus 개별 핀 모순 | **핀 제거·BOM 위임**, 트레이싱 백엔드 **Tempo 확정**(§1) |
| missing | 앱티어 LB / 오케스트레이터 / 트레이스 백엔드 / 로그집계 / 시크릿 / 커넥션풀 | **§0-2 토폴로지에 LB(Nginx/HAProxy/Envoy)·Docker/K8s·Tempo·Loki·Secret 주입 명시**, HikariCP 사이징·pinning §7-5 신설 |

### 8-3. 최종 미결(운영 판단) 사항
- SB 3.5.16 EOL 시점 도래 시 보안 패치 경로: Resilience4j의 SB4 GA 릴리스 확인 후 SB4 이관 재검토(현재는 검증된 3.5 경로 확정).
- 시장가 매수 기준: **notional(금액) 확정**(§4-B-1). 정책 변경 시 `remaining_notional` 스키마 유지.
- 리더 임차 보관소 기본값(v2.44 등재): 정본 4.2·6.5 는 DynamoDB 로 단정하고 저장소 기본값은 `application.yml` 77행 `provider: postgres` 다. 정본을 고칠지 배포 기본값을 바꿀지 미결이다(§1 리더선출 행).
- 메시지 브로커 도입 여부(v2.44 등재): `outbox` 적재는 실재하고 발행기가 없다. 정본 4.3 비채택표에도 등재가 없어 채택으로도 비채택으로도 확정되지 않았다(§0-2, §1 메시징/이벤트 행, §7-3 P3).

---

## v2.1 패치 반영 사항

v1.0 제출본에 남아 있던 잔여 결함(high 3건·medium 1건·low 2건)을 확정 수정했다. 아래는 무엇을(문제) 어떻게(수정) 고쳤는지의 대조표와 상세다.

| # | 심각도 | 위치 | 문제 | 수정 |
|---|---|---|---|---|
| P1 | high | §3-3, §3-6, §4-B-6, §6-A | canonical의 `SORTED_QUERY_OR_BODY`가 flat `key=value&…`로만 정의 → 배열 바디(`/orders/batch`)·중첩 바디(`/admin/v1/sim/bots`의 `params`/`seed`)는 서명 대상 문자열을 확정 불가 → 상시 `401 invalid_query_payload` 또는 구현자별 canonical 불일치로 서명 무결성 붕괴 | §3-3을 **(a) 쿼리=SORTED_QUERY / (b) JSON 바디=`JCS(body)`(RFC 8785)** 두 경로로 재정의. 배열/중첩은 flat 평탄화가 아니라 **canonical JSON 직렬화 원문 바이트를 해싱**하도록 명문화. **batch(배열)·admin 봇 생성(중첩) canonical 예시 각 1건 추가**. §3-6이 이 규칙을 상속함을 명시 |
| P2 | high | §2-4, §4-B-1 | `client_order_id`를 required로 승격했다면서 동시에 서버 fallback `srv-{user_id}-{seq}` 허용 → "필수인데 기본값 존재"라는 자기모순(사실상 optional). `{seq}` 산출 기준 미정의 → 단조 카운터라면 키 없이 재전송하는 클라이언트가 매번 다른 키로 이중 접수 → 이중체결 재개방 | **옵션 (A) 채택: 진짜 required로 못박음.** 미제공 시 예외 없이 `400 client_order_id_required`, **서버 생성 fallback 완전 삭제**. 재시도는 클라이언트가 동일 키 재사용(멱등 replay로 수렴). 모순 서술 제거, §4-B-1 필드표·§8-1 #1 동기화 |
| P3 | medium | §4-B(일괄 주문) | batch 응답 스키마·부분성공 매핑·멱등 상호작용·HTTP 상태·원자성 미정의(cancel-all은 `failed[]` 있는데 생성은 대칭 계약 부재) | **§4-B-6 신설**: `results:[{index, client_order_id, status|error}]` per-item 결과 배열 + `summary`. **부분성공 시 200 + 개별 상태**(207 미사용), **비원자(전량 롤백 아님)** 규약, 멱등 단위=개별 `client_order_id` 명시, 예시 추가 |
| P4 | medium | §5-1, §5-3, §1, §0-2 | 다중 노드 팬아웃을 "Redis Pub/Sub 릴레이"로 규정했으나 `/user/queue/**`(myOrder/myAsset)는 Spring user-destination. 순수 Redis 릴레이는 노드 간 user-destination 라우팅 미제공 → 세션 노드≠체결 노드 시 비공개 이벤트 유실 → 증명(비공개 채널 관찰) 타격 | **크로스노드 라우팅 명시**: 공개 `/topic/**`=Redis 릴레이, 비공개 `/user/queue/**`=**(A) 외부 STOMP 브로커 릴레이(RabbitMQ/ActiveMQ) 채택** 또는 (B) `userRegistryBroadcast`+`brokerChannel` Redis 릴레이. **크로스노드 `/user/**` 전달 보장 문장 추가**(§5-1). §1·§0-2 정합 |
| P5 | low | §2-2, §4-A | §2-2 엔벌로프 기준 예시 JSON이 `"next_cursor": "eyJ0cyI6...,`(닫는 따옴표 누락)로 파싱 불가. §4-A 리스트 예시들이 top-level `schema_version` 생략(각주로 회피) | 기준 엔벌로프 예시 따옴표 **`"eyJ0cyI6..."`로 교정**. **§4-A-1 markets에 top-level `schema_version:"1.0"` 실제 표기**(기준 예시), §4 각주를 "항상 포함, 표기만 생략"으로 수정 |
| P6 | low | §2-1, §3-3, §4-A-1, §4-B-5 | (1) §2-1이 "전 예시 UTC ISO ms 3자리 통일" 선언하나 JWT `timestamp`는 정수 epoch ms라 예외인데 명시 없음. (2) 수수료 권위 소스 불명확. markets taker `0.0005` vs chance taker `0.0008` 상이 | (1) §2-1에 **"JWT payload `timestamp`는 정수 ms epoch 예외" 한 줄 추가**. (2) **수수료 권위 소스=마켓별 고정**으로 확정(§4-A-1/§4-B-5), chance taker를 **`0.0005`로 정정**해 markets와 일치. 파급으로 §5-5 myOrder `fee` 예시도 `160→100`(taker 0.0005 재계산)으로 정합 |

**세부 노트**
- **P1 JCS 규약 정밀화**: 금액/수량은 문자열(§2-1)이라 JCS 숫자 정규화 대상이 아님(`"0.01"` 그대로), 실제 정수/불리언(`count`, `lambda`, `expect_failover`)만 수치·리터럴 정규화. 배열은 원소 순서 보존, 객체 멤버만 키 정렬(중첩 재귀). `canonical` 전체에 SHA512 1회.
- **P2 설계 원칙 근거**: "정확성(체결 정합) > 편의성"에 따라 데모 편의(서버 자동 키)보다 이중체결 불가능성을 택함. 키 생성 책임은 전적으로 클라이언트(봇 포함).
- **P3 원자성 결정**: batch는 주문 단위 독립 처리(부분 성공 정상). 전량 롤백이 필요하면 개별 `POST /orders` 순차 전송. batch 단위 멱등키는 없고 per-item `client_order_id`가 멱등 단위 → 동일 배열 재전송은 항상 동일 결과로 수렴.
- **P4 채택안**: 외부 STOMP 브로커 릴레이(A)를 기본 채택(SimpleBroker→broker relay). 페일오버 재연결 후 클라이언트는 REST 스냅샷으로 재동기화(§7-6).
- **하위 정합**: P2로 §8-1 #1, P3으로 §8 missing 목록의 batch 참조(§4-B-3→§4-B-6), P6으로 §5-5 fee 예시까지 연쇄 정정하여 문서 내 상호 참조를 모두 일치시킴.

---

## v2.2 패치 반영 사항

v2.1 잔여 결함(high 1건·medium 12건 상당, 중복 제기 포함 15건 지적 → 13건 실질)을 확정 수정했다.

| # | 심각도 | 위치 | 문제 | 수정 |
|---|---|---|---|---|
| Q1 | high | §3-3, §3-4, §3-5 | `recvWindow`가 "기본 5000ms·최대 60000ms"로 가변임을 시사하나 클라이언트→서버 전달 수단 미정의. 미서명 채널(헤더 등)로 전달하면 공격자가 창을 60000ms로 늘려 동일 JWT 재전송 → nonce TTL(원본 recvWindow 기준)이 이미 만료된 구간에서 timestamp 검사를 통과하는 **리플레이 창 재개방**(§3-4의 "리플레이 사각지대 제거" 선언과 정면 모순) | `recvWindow`를 **JWT payload의 서명 대상 클레임(선택)**으로 추가(§3-3), 미포함 시 서버 기본 5000ms·서버는 `min(요청값, 60000)` 클램프(§3-4 3단계). **미서명 채널 전달 금지 명문화**. nonce TTL = **해당 요청의 유효 recvWindow** + clock_skew(§3-4 4단계). §3-5 의사코드 동기화 |
| Q2 | medium | §5-5 (↔ §4-B-5, §4-C-1) | myAsset 예시가 직전 myOrder(bid 체결)와 모순: BTC locked 감소·balance 증가는 **매도(ask) 락 해제 형태**(bid는 KRW를 락하는 §4-B-5 모델 위반)이고, KRW 수치(799640/49800)는 체결대금 200,000+fee 100의 어떤 조합으로도 재구성 불가. 매수·매도 회계가 뒤섞여 잔고 락/해제 규칙 오해석 유발 | bid 시나리오로 **전면 재계산**: KRW `{balance:499750, locked:300150}`(락 500,250=대금+수수료 예약 → 체결분 200,100 차감), BTC `{balance:0.494(+0.004), locked:0.01(무변화·무관한 매도 주문 락)}`. 산출 근거를 예시 하단 주석으로 명시 |
| Q3 | medium | §2-3 ↔ §4-B-1 | 동일 위반(최대 주문 초과)이 `400 over_max_order`(§2-3)와 `422 domain_rejected`+`reject_reason=over_max_order`(§4-B-1)로 **이중 정의**. 구현자마다 status/name 해석이 갈리는 상충 규칙 | 최대 한도는 마켓 메타 기반 게이트웨이 정적 검증이므로 `under_min_order`와 대칭인 **400 단일 경로로 확정**. §4-B-1 reject_reason 목록에서 삭제, §8-1 #11 연쇄 정정 |
| Q4 | medium | §5-6 (↔ §2-1 accept-version:1.2) | 개별 SUBSCRIBE 인증 실패 시 "ERROR 프레임 + 연결 유지"는 **STOMP 1.2 사양 위반**(ERROR 전송 후 서버는 연결을 반드시 닫아야 함). 표준 클라이언트는 ERROR 수신 시 연결 종료로 간주하므로 "sub-0 계속 수신" 계약이 상호운용 불가 | 구독 거절을 **RECEIPT 미발급 + 해당 subscription으로 `subscription_rejected` 통지 MESSAGE 1건** 후 서버측 구독 해제로 재정의(연결 유지, ERROR 미사용). ERROR는 CONNECT 실패 등 **연결 종료 상황 전용**으로 한정 |
| Q5 | medium | §5-2 ↔ §5-5, §4-B-1 | seq가 "market × type 단위"로만 정의되어 market 차원이 없는 비공개 채널(myOrder/myAsset)의 seq 도메인이 미정의. 예시(51001→51002 연속)는 채널 간 공유 카운터를 시사해 "채널별 독립 도메인" 규칙과 모순. 비공개 채널 gap 감지 구현 불능. §4-B-1 응답 `seq:88123600`의 도메인도 무규정 | §5-2에 **비공개 채널 = `user × type` 단위 독립 단조 증가** 명시(재동기화 스냅샷 소스 포함). §5-5 예시 번호를 독립 도메인답게 분리(myOrder 51001 / myAsset **7203**). §4-B-1 응답 seq는 **command_journal seq**임을 주석으로 확정 |
| Q6 | medium | §2-4 | 동시 in-flight 규약(대기 후 무조건 200 replay)과 동작 요약(내용 상이 → 409)이 충돌. 내용이 다른 두 요청이 동시 도착하면 200/409 중 어느 쪽인지 비일관 | 대기 후에도 **내용 비교 수행**: 동일→200 replay, 상이→`409 duplicate_client_order_id`. 동시/순차 도착의 결과 동일화 명문화 |
| Q7 | medium | §2-3 ↔ §3-3/§3-4 | `401 expired_jwt`가 도달 불가능: payload에 `exp` 클레임이 없고 §3-4 어느 단계도 이를 반환하지 않음(신선도는 `timestamp_out_of_window` 전담). 스테일 에러코드 | **`expired_jwt` 삭제**, 신선도 위반은 `timestamp_out_of_window`로 일원화(에러표에 삭제 사유 명기) |
| Q8 | medium | §3-3(a) | SORTED_QUERY의 퍼센트 인코딩 기준(원문 인코딩 vs 디코딩 값) 무규정. `markets=KRW-BTC,KRW-ETH`의 콤마를 `%2C`로 보내는 클라이언트와 그대로 보내는 클라이언트의 canonical이 갈려 동일 요청이 401로 갈림. "재현 불가능한 표현 금지" 자기 선언과 모순 | **URL 디코딩된 값 기준**으로 확정(서버=수신 쿼리 디코딩 후 재계산, 클라이언트=인코딩 전 원본 값). `%2C` 예약문자 예시 1건 추가 |
| Q9 | medium | §3-6, §5-6, §6-A | WS CONNECT에 첨부하는 "동일 JWT"의 `query_hash` 산출 대상(METHOD/PATH/페이로드) 미정의 → §3-4 5단계 재계산 불능. admin 텔레메트리 WS 인증도 무규정 | CONNECT canonical을 **`GET\n/ws/v1\n`(빈 페이로드)**로 확정. admin WS는 **`GET\n/admin/v1/ws/sim/telemetry\n`** + `scope=admin` 키 서명으로 동일 규칙 적용 명문화(§3-6) |
| Q10 | medium | §4-B-6 | "in-flight 충돌 원소는 개별 `409 order_in_progress`"가 top-level 200 + per-item enum 3종(accepted/replayed/rejected) 계약과 충돌. 원소 단위 HTTP 409는 표현 불가, rejected로 뭉개면 종결 거부와 재시도 대상이 구분 불능 | per-item **`in_progress` 상태 신설**(비종결·재시도 대상, `error={name:order_in_progress, retry_after}`). 원소 단위 HTTP 409 부재 명시, summary에 in_progress 카운트 포함 |
| Q11 | medium | §7-6 ↔ §4-B-4(a), §0-2/§7-1 | orders가 비동기 프로젝션인데 by-client 재조회 소스 무규정 → 저널 fsync 후 ACK 유실 시 프로젝션 지연 동안 404 가능 = "404⇒저널에 없음" 보장(§7-6)이 아키텍처와 상충 | 재조회의 **권위 소스를 `command_journal`로 확정**: 저널에 없음=404, 저널에 있으나 프로젝션 미반영=`409 order_in_progress`+Retry-After. §7-6 보장 논리를 이 계약 위에 재서술 |
| Q12 | medium | §1 | Flyway `12.9.0` 개별 핀이 "개별 핀은 BOM 미관리 항목에만"(버전 고정 원칙)과 모순. Flyway는 SB BOM 관리 항목이고, SB 3.5 관리 라인(11.x)을 메이저 오버라이드해 "전 스택 GA 검증" 근거와도 충돌 | Flyway를 **SB BOM 위임(3.5.x 관리 라인=11.x)**으로 변경, 정정 사유 표기. 같은 이유로 spring-kafka도 `3.3.16` 핀 표기 → **BOM 위임(3.3.x)**으로 정리(§8-2 참조 동기화) |

**중복 제기 처리**: 지적 목록의 (myAsset 회계)×2, (over_max_order 이중 정의)×2, (비공개 seq 도메인)×2는 각각 동일 이슈로 Q2/Q3/Q5에 통합 반영.

---

## v2.3 패치 반영 사항

v2.2 잔여 결함(medium 6건)을 확정 수정했다.

| # | 심각도 | 위치 | 문제 | 수정 |
|---|---|---|---|---|
| R1 | medium | §2-5 ↔ §2-3, §2-4, §4-B-4(a) | `Retry-After` 발급 조건 상충: §2-5 헤더 예시 주석은 "429일 때만"이라 하나, §2-3은 503에 필수·§2-4/§4-B-4(a)는 409 `order_in_progress`에도 반환 규정. §2-5만 따르는 구현이 503/409에서 생략하면 UNKNOWN 재조회 수렴 계약이 깨짐 | §2-5 주석을 **"429·503·409(order_in_progress) 시 포함"**으로 수정하고, 전체 발급 조건의 권위는 §2-3·§2-4·§4-B-4(a)임을 상호참조로 일원화 |
| R2 | medium | §2-4 (↔ §4-B-1, §4-B-6) | 멱등 판정의 '내용 동일/상이' 비교 기준(원문 바이트/정규화/필드 단위) 무규정. 구현에 따라 동일 재시도가 200 replay vs 409로 갈려 안전 재전송 경로가 막히고, §3-3의 "재현 불가능한 표현 금지" 자기 선언과 모순 | **JCS(RFC 8785) 정규화 바이트 비교로 확정**(§3-3(b) 서명 canonical과 동일 정규화 재사용): 저널의 원본 바디 JCS 바이트 vs 재요청 바디 JCS 바이트. 키 순서·공백은 동일 수렴, 필드 생략 vs 기본값 명시는 상이(409). batch per-item도 원소 객체 JCS 바이트로 동일 기준 명시 |
| R3 | medium | §3-6 (↔ §6-A, §6-B) | 이중 승인 미완결: (1) `approval_token` 수명주기(단회성·TTL·액션/대상 바인딩·발급 주체) 무규정 → 정적 토큰이면 유출 시 kill-node 무기한 반복 승인 가능(2인 통제 형해화). (2) 대상 액션이 '등'으로 열려 있어 sim/stop·scenarios 포함 여부 미정. 구현자 간 403 불일치 | (1) **단회용(검증 시 소각)·TTL 5분·action+대상 바인딩**으로 규정, 발급 주체=다른 operator, 발급 엔드포인트 `POST /admin/v1/approvals` 신설(발급·사용·만료 감사 로그). (2) 필수 액션을 **'등' 없이 완결 열거**(chaos 4종 + `bots/{id}/params`), 그 외는 operator_id만 필수로 확정. §6-A/§6-B 표에 **approval_token 컬럼 추가**, kill-node 예시에 바인딩 주석 |
| R4 | medium | §5-2 ↔ §5-3/§5-4 candle | 공개 채널 seq 도메인이 "market × type"으로만 정의되어 unit 차원이 있는 candle(`/topic/candle.{unit}.{market}`)의 도메인 미정. 문면대로 단일 카운터 구현 시 1m 구독자가 5m/1d 증가분으로 false-gap 감지, "특정 채널만 구독해도 false-gap 없음" 목표와 상충 | §5-2에 **일반 규칙 "seq 도메인 = 구독 destination 단위"** 확정, candle은 **`market × type × unit` 단위 독립 단조 증가** 명시 |
| R5 | medium | §2-6 ↔ §4-B-4·§7-1·§4-A-3 | §2-6 규범 커서 예시가 디코딩 시 `{"ts":...,"id":90011}`(정수 id). "외부 노출 id 전부 ULID 통일"(§7-1)·"정수 from_id 폐기"(§4-B-4) 규칙 및 §4-A-3 예시(문자열 ULID)와 상충, 커서 id 축이 내부 BIGINT인지 외부 ULID인지 해석 분기 | 예시 커서를 **ULID 문자열 id 인코딩**(`...ImlkIjoiMDFKOFpUIn0` → `{"ts":"...","id":"01J8ZT"}`)으로 교체, 커서 id 축=외부 ULID·내부 PK 미노출 한 줄 명시 |
| R6 | medium | §4-B-6 ↔ §4-B-1·§2-3 | batch per-item error 예시가 `reject_reason`을 `detail` 밖 error 최상위에 배치. §4-B-1의 `detail.reject_reason` 규정·§2-3 표준 에러 객체와 다른 제3의 스키마여서 클라이언트 파싱 위치가 갈림 | per-item error를 **§2-3 축약형으로 통일**: 예시를 `"detail":{"reject_reason":"market_suspended"}`로 교정하고, 기계가독 부가정보는 `detail` 안(top-level `reject_reason` 금지)·`retry_after`는 §2-3 top-level과 동일 위치임을 명문화 |

**세부 노트**
- R3 승인 대상 확정 근거: §6-A 봇 생성 예시가 `operator_id`만 요구해 온 기존 의도를 존중해 생성/제거·sim 제어·시나리오·rate는 approval_token 제외로 확정했다(전 액션 감사 로그는 유지). 표의 승인 컬럼이 권위 소스다.
- R4는 v2.2 Q5(비공개 채널 user × type 정밀화)의 잔여였던 공개 candle unit 차원을 마저 닫은 것이다.
- 문서 헤더 버전을 v2.3으로 순증(§8·v2.1/v2.2 패치 노트의 역사 서술은 원문 유지).

---

## v2.4 패치 반영 사항

v2.3 잔여 결함(medium 4건)을 확정 수정했다.

| # | 심각도 | 위치 | 문제 | 수정 |
|---|---|---|---|---|
| S1 | medium | §6-C ↔ §4-B-1/§4-B-2 | 불변식 ③의 검증식 `(user_id,client_order_id)` 그룹당 1건이 대상 테이블 무명시로 자기 라벨('중복 체결 0')·부분체결 모델과 불일치: trades 기준으로 읽으면 문서가 허용하는 다건 부분체결(§4-B-1 `trades_count`, §4-B-2 0.004 부분체결)이 전부 오검출되고, 저널 기준으로 읽으면 §2-4 UNIQUE 재서술일 뿐 동일 체결 이중 적용을 증명 못 함. "모두 SQL로 즉시 증명" 선언 하에 구현자별 SQL이 갈리는 실질 모호성 | ③을 **두 축으로 분리·대상 테이블 명기**: (i) 주문 중복 접수 0=`command_journal`의 `(user_id,client_order_id)` UNIQUE 위반 0, (ii) 중복 체결 0=`trades`의 `trade_id` 단위 중복 0 및 주문별 `SUM(trades.volume) ≤ orders.volume`(부분체결 다건은 정상, 위반=동일 체결 이벤트의 이중 적용) |
| S2 | medium | §3-6 ↔ §6-A/§6-B | 2인 승인의 핵심 판정("요청 operator와 다른 operator")을 검증할 수단 무규정: `operator_id`가 바디 자가신고 문자열뿐이고 인증 주체(access_key/mTLS 인증서)와의 바인딩·대조 규정이 부재 → 단일 admin 키 보유자가 발급 시 `op-02`, 사용 시 `op-01`로 자가신고해 혼자 2인 승인 완성 가능(2인 통제 형해화) | §3-6에 **operator 신원 바인딩** 신설: admin `access_key`(및 mTLS 인증서)는 `operator_id`와 1:1 등록(§7-1 `api_keys`), 서버는 요청·approvals 발급 각각에서 **인증 주체로부터 operator_id를 도출**해 바디 값과 대조(불일치 `403 admin_forbidden`), 2인 승인은 **발급 주체 ≠ 사용 주체를 인증 주체 기준으로 검증**(토큰에 발급 주체 바인딩, 감사 로그도 인증 주체 유래 값 기록) |
| S3 | medium | §1 표 ↔ 버전 고정 원칙·v2.2 Q12 | SB BOM 관리 항목인 jOOQ(`3.20/3.21`)·Testcontainers(`TC 2.0.5`)·Awaitility(`4.3.1`)에 개별 핀 잔존. "개별 핀은 BOM 미관리 항목에만"(표 하단 원칙) 및 자신이 Flyway·spring-kafka에 적용한 Q12 정정 논리와 정면 모순(특히 jOOQ는 SB 3.5 관리 라인 3.19.x의 오버라이드) | jOOQ를 **SB BOM 위임(3.5.x 관리 라인=3.19.x)**, Testcontainers·Awaitility를 **SB BOM 위임**으로 변경(정정 사유 표기). ArchUnit·jqwik은 BOM 미관리라 개별 핀 유지 명시. 연쇄로 카오스 행의 `TC toxiproxy 2.0.1` 핀도 **TC(SB BOM) 위임**으로 정리(chaos-monkey는 BOM 미관리 → 유지) |
| S4 | medium | §5-2 ↔ §5-4/§5-5 | '구독 즉시 계약'이 채널 구분 없는 일반 규정인데 `stream` 필드는 §5-4 orderbook 스키마에만 존재하고 trade/ticker/candle·§5-5 myOrder/myAsset 스키마·예시엔 부재, orderbook 외 채널의 snapshot 페이로드('현재 상태')도 미정의. snapshot이 orderbook 전용인지 전 채널 공통인지, 클라이언트 파서의 stream 처리 여부가 구현자별로 갈림 | 계약을 **orderbook 채널 한정으로 확정**: `stream` 필드는 orderbook 메시지에만 존재(여타 스키마의 필드 부재가 계약), 여타 채널은 snapshot 미전송·**연속성 기준 seq=구독 후 첫 realtime 메시지**, 비공개 채널 초기 상태는 구독 직후 REST 스냅샷(`GET /orders/open`·`GET /accounts`)으로 확보. §5-6 흐름 예시 주석에 한정 참조 연쇄 반영 |

**세부 노트**
- S4 채택 근거: 기존 §5-4/§5-5 예시가 모두 `stream` 필드 없이 확정돼 있고, 비공개 채널 재동기화가 이미 REST 스냅샷 경로(§5-2)로 규정돼 있어, 전 채널 snapshot 페이로드를 신설하는 안보다 **orderbook 한정 + 첫 realtime 기준점** 확정이 기존 계약과의 파급이 최소다.
- S3에서 표 하단 '버전 고정 원칙' 문장은 원문 유지(원칙이 옳고 표가 어긋난 사안이므로 표만 원칙에 정렬).
- 문서 헤더 버전을 v2.4로 순증(§8·v2.1~v2.3 패치 노트의 역사 서술은 원문 유지).

---

## v2.5 패치 반영 사항

v2.4 잔여 결함(low·표기 수준 6건)을 확정 수정했다. **전부 표기·예시·상호참조 정밀화이며 실질 계약(인증·멱등·채널·에러 규약) 변경은 없다.**

| # | 심각도 | 위치 | 문제 | 수정 |
|---|---|---|---|---|
| U1 | low | §2-1, §2-5, §3-3 | epoch 예시값(`1721556000123`, `1721556001`)이 2024년대 값, 문서 기준일(2026-07-21)·주변 ISO 예시(`2026-07-21T09:00:0X…Z`)와 연대 불일치 | 문서 기준일로 환산 교체: `1721556000123`→**`1784624400123`**(=2026-07-21T09:00:00.123Z, §2-1 JWT epoch 예외 예시·§3-3 payload `timestamp`), `1721556001`→**`1784624401`**(epoch s, =09:00:01Z. §2-5 `X-RateLimit-Reset`, §2-1 ISO 예시 `09:00:01.000Z`와 정합. 초 단위 헤더는 초 단위 유지) |
| U2 | low | §7-1 (↔ §2-4, §6-C ③) | `command_journal`의 `UNIQUE(user_id, client_order_id)`가 무조건 UNIQUE로 읽힘. 취소 등 비생성 커맨드도 동일 저널에 적재되며 by-client 취소는 동일 `client_order_id`를 동반하므로 문면대로면 정상 취소가 제약 위반 | **주문 생성 커맨드 한정 부분 유니크 인덱스**(`WHERE command_type='NEW'`)임을 §7-1에 명시하고, §2-4 DB 제약·§6-C 불변식 ③(i)에 동일 축 상호참조를 연쇄 정합화 |
| U3 | low | §4-C (↔ §3-2) | `GET /my/ledger`가 표에만 있고 상세 절 부재(§4-C-1/2와 비대칭). §3-2의 키 폐기 `DELETE /api/v1/keys/{access_key}`는 REST 표 미등재(경로도 §3-2에 축약 표기) | **§4-C-3 상세 신설**(파라미터·§2-2 엔벌로프 응답 예시 1건·§2-6 커서 규약, `entry_type` 어휘=DB `ledger_entries`, 예시 수치는 §5-5 체결 시나리오 200,100 차감과 정합) + §4-C 표에 **키 폐기 DELETE 등재**(weight 5), §3-2에 전체 경로 명시 |
| U4 | low | §6 도입부 | "모든 페이로드 `schema_version` 포함" 선언 대비 §6-A/B/C 예시 미표기. §4는 각주로 해소했으나 §6은 각주가 없어 계약/예시 불일치로 읽힘 | §6 도입부에 §4와 동일한 **"항상 포함, 표기만 생략" 각주** 추가(기준 예시 §4-A-1 참조) |
| U5 | low | §2-6 (↔ §4-A-5) | 커서 `id` 축="외부 노출 ULID" 규정이 ULID가 없는 자연키 리소스 candles(`candle_time`)에 적용 불능. 커서 축 해석 공백 | candles는 **`(candle_time, market)` 자연키를 `id` 축으로 사용**하는 명시적 예외 1줄 추가(내부 PK 미노출 원칙은 동일 유지) |
| U6 | low | §5-1 (↔ §5-6) | 연결당 구독 50개 상한 초과 시 거절 형태 무규정. ERROR 프레임 사용 여부가 구현자별로 갈릴 여지(§5-6/v2.2 Q4의 'ERROR=연결 종료 전용' 정정 취지 훼손) | 상한 초과 SUBSCRIBE는 **§5-6 구독 거절 규약 준용** 명시(RECEIPT 미발급 + `subscription_rejected` 통지 MESSAGE 1건 후 서버측 해제, 연결·기존 구독 유지) |

**세부 노트**
- U1 환산 규칙: 2026-07-21T09:00:00.123Z = epoch ms **1784624400123**을 기준으로, 문서에 등장하는 모든 2024년대 epoch 예시를 동일 규칙으로 환산했다(밀리초 필드는 ms, 초 단위 헤더는 s 유지). JWT `timestamp`의 '정수 epoch ms 예외' 규정(§2-1)과 서버 검증 순서(§3-4)의 서술은 변경 없음.
- U2 명칭: 지적 원문의 'PLACE_ORDER'는 DB 설계 문서 §3.1 `command_journal.command_type` CHECK 어휘(`'NEW'`/`'CANCEL'`)에 맞춰 **`'NEW'`**로 표기했다(동일 의미, 주문 생성 커맨드. 문서 간 어휘 분기 방지).
- U3의 §4-C-3 응답 예시는 신규 계약 창설이 아니라 기존 표 등재 엔드포인트의 상세화이며, 필드 구성은 §2-2 엔벌로프·§2-6 커서·DB 원장 어휘의 조합만 사용했다.
- 문서 헤더 버전을 v2.5로 순증(§8·v2.1~v2.4 패치 노트의 역사 서술은 원문 유지).
- 2026-07-21: 스타일 정리, 내용 무변경.

---

## v2.6 패치 반영 사항

v2.6 correctness: 외부 검토 반영(fencing 스코프, RPO 범위 명시, 결정론 이벤트 모델, DB 채번, 정산 양자화, 원장 멱등, 주문 검증 규칙). 외부 분석 3건(HA 주장·이벤트 모델·정산 계약)의 수정문안을 통합 적용했다.

| # | 심각도 | 위치 | 문제 | 수정 |
|---|---|---|---|---|
| R1 | high | §0-2, §1, §6-B, §6-C, §7-1, §7-3 | fencing 검증이 command_journal 삽입 구간에만 표기되어 구 리더의 체결·스냅샷 쓰기가 미보호로 읽힘. "split-brain 없음"을 실측 증명할 불변식 축 부재 | **fencing 스코프를 전 엔진 기원 쓰기 트랜잭션으로 확정**: `assert_current_leader(holder, epoch)` 계약을 §7-1에 신설하고 event_journal·trades·snapshots에 `fencing_epoch` 저장. **불변식 ⑦ fencing 무결 신설**(6종→7종, `/ha/recovery` 예시에 `fencing_violations` 키 추가). E5 PASS 판정과 P2 증거에 구 리더 쓰기 0행 편입 |
| R2 | high | §0-2, §1, §6-C, §7-3, §7-6 | RPO=0이 무조건 성립처럼 서술. 로컬 fsync 내구성(프로세스·전원 장애)과 failover 포함 무손실(동기복제 운영 요건)의 범위 구분 부재 | **RPO=0 보장 범위 문단(§7-6 하단) 신설**, §1 DB 자체 HA 행에 성립 조건(`synchronous_standby_names` 동기 standby 강제·`synchronous_commit=on`·STONITH·PITR) 명시. §0-2 토폴로지·P0 증거·불변식 ②·`rpo_records` 주석을 동일 범위 참조로 정렬 |
| R3 | medium | §4-A-3, §4-B-1, §4-B-4(a), §5-4, §5-5, §7-1, §7-2 | 재생 시 id·시각·이벤트 순서의 재현 규약 부재("외부 id 전부 ULID" 서술과 trade_id 발급 방식의 상충 포함) | **결정론 이벤트 모델 확정**: order id=명령 접수 전 사전 발급·저널 박제(재발급 금지), `trade_id`=event_journal `event_seq` 결정론 파생, `executed_at`=command `engine_ts` 파생, `event_ordinal` + `UNIQUE(source_cmd_seq, event_ordinal)`로 커맨드 내 이벤트 순서 고정. 불변식 ⑥ 해시 입력에 편입, §4-A-3·§5-4·§5-5 예시 각주 연쇄 |
| R4 | medium | §4-B-1, §6-C ①, §7-1 | seq gapless의 채번 방식 미정의(인메모리 카운터면 할당·롤백·역순 커밋에서 영구 결번 가능) | **`journal_counters` 단일 행 `UPDATE ... RETURNING` DB 원자 채번 신설**, 불변식 ①에 gapless 전제 명시 |
| R5 | medium | §4-A-1, §4-B-5, §5-5 | 체결 단위 수수료 반올림·잔차 처리 규약 부재(잔차 누적 시 락 누수·음수 락 가능) | **양자화·잔차 규약(§4-B-5) 신설**: 정산 자산 scale 선양자화, 해당 주문 마지막 분개의 잔차 흡수, 최종 체결의 실제 잔여 락 소비(FILLED 시 해당 주문 기여분 락 잔량 정확히 0). §4-B-1 상태 불변식·§4-A-1·§5-5 각주 연쇄 |
| R6 | medium | §4-C-3, §6-C ③(ii), §7-1 | 원장 이중 기표 차단 수단 미명세. §4-C-3 예시가 대금+수수료 합산 단일 TRADE 분개라 `entry_type` 어휘(TRADE/FEE 분리)와 자기모순 | **`ledger_transactions`에 `UNIQUE(business_event_type, business_event_id)` 멱등 키 명시**, §4-C-3 예시를 TRADE+FEE 분개 분리로 교체(분개 합 200,100은 §5-5 락 차감치와 정합), 불변식 ③(ii)를 이 제약의 사후 감사로 재규정 |
| R7 | medium | §2-3, §4-B-1, §4-C-1, §6-C ④, §7-1 | 주문 파라미터 조합의 상호배타·금지 규칙 미확정(DB orders CHECK와 어긋날 여지), `locked` 음수 금지 CHECK 부재, 자산 보존 증명 범위 과대 서술 | **조건부 규칙을 상호배타로 확정**(위반=`400 invalid_parameter` + `detail.constraint`, DB orders CHECK와 1:1 대응), `CHECK(locked≥0)` 병기(§4-C-1·§7-1), 불변식 ④에 수기 검증 범위(LIMIT BUY taker 단일 시나리오)와 V3 무작위 회귀 테스트 위임 명시 |

**세부 노트**
- 분석 문안의 "회귀 테스트 T-B" 참조는 하네스 문서에 T-A~T-H 목록이 미편입 상태라 잠정 삭제하고 "하네스 V3 통합테스트" 수준 참조로 조정했다(하네스_검증설계.md의 V3 통합테스트 목록과 정합. 목록 편입 시 개별 테스트 id로 재바인딩).
- v2.5 U3 세부 노트의 "200,100 차감과 정합" 문구는 v2.6 이후 "TRADE+FEE 분개 합 200,100과 정합"으로 읽는다(§4-C-3 예시 분리 반영, 역사 서술 원문은 유지).
- §4-B-1 조건부 규칙의 notional 매핑: 본 문서의 `market`+`bid` `price`(notional)가 DB 계약의 quote_order_qty 축에 대응한다. API에 별도 `quote_order_qty` 필드 신설은 계약 변경이므로 본 패치 범위 밖(별도 결정 사항).
- 문서 헤더 버전을 v2.6으로 순증(§8·v2.1~v2.5 패치 노트의 역사 서술은 원문 유지).
---

## v2.7 패치 반영 사항

M1-4 게이트웨이 구현 재판정의 잔여 지적(medium 3건·low 3건)과 M1-5 브라우저 데모 인증 요구를 반영했다.

| # | 심각도 | 위치 | 문제 | 수정 |
|---|---|---|---|---|
| W1 | medium | §3-4 | nonce 저장소가 Redis SET로만 규정. 단일 노드 구현(인메모리 NonceStore)과 문서가 어긋난 채 방치되면 다중 노드 승격 시 nonce 재사용 창이 무규정으로 열림 | §3-4에 **구현 각주 신설**: 단일 노드 전제의 인메모리 저장소 허용, 다중 노드 승격(M2) 시 Redis 교체 선행 조건 명시 |
| W2 | medium | §4-B-5, §5-5, §4-C-3 | 수수료·예약 수치 예시가 구판 모델(quote 수수료 예약 포함 락) 기준. DB 정본 §5.2(수취 자산 수수료, 예약 무수수료)·구현과 상충 | §4-B-5 규약을 **수취 자산 수수료 + 예약 무수수료 모델로 개정**(매수=수취 base×요율 qty_scale, 매도=대금×요율 price_scale, HALF_UP 선양자화, 순액 게시). §5-5 myOrder fee(100→0.000002 BTC)·myAsset 수치(락 500,000·차감 200,000·BTC 순액 +0.003998) 전면 재계산, §4-C-3 예시를 KRW TRADE -200,000 + BTC TRADE +0.003998로 교체(FEE leg는 FEE_INCOME 귀속이라 사용자 원장 미노출) |
| W3 | medium | §4-B-1, §4-B-2, §4-B-4, §4-C-3, §7-1 | 주문 id를 ULID로 표기하나 구현 정본(orders.external_id UUID, 게이트웨이 사전 발급)은 UUID. 커서 id 축 해석도 연쇄 불일치 | 주문 id 표기를 **UUID로 정정**(예시·결정론 서술·커서 설명·§7-1 타입 규약). trade_id의 event_seq 결정론 파생 규약은 무변경 |
| W4 | low | §4-B-1 (↔ DB §1.4) | MARKET BUY notional 값의 소수 자리가 scale 18을 초과하면 예약(양자화 없음)·소진 어느 경로로도 표현 불가. 거부 규정 부재 | notional 소수 자리 초과를 게이트웨이 **400 invalid_parameter 거부**로 확정(DB 설계 §1.4 앱 가드 목록에 항목 추가) |
| W5 | low | §3-3, §3-4 | chunked 전송 요청은 Content-Length가 없어 길이 기반 바디 유무 판정 시 바디 서명(§3-3(b))을 쿼리 canonical로 우회 가능 | 비 GET 요청의 canonical 분기는 길이 헤더가 아니라 **실제 바디 바이트 존재로 판정**함을 확정(바이트가 있으면 (b) JCS 경로 강제) |
| W6 | low | §5-2 | orderbook 스냅샷과 realtime 게시의 경합 시 순서 역전(스냅샷 이전 도착·seq 역전) 처리 무규정 | §5-2 구독 즉시 계약에 **순서 역전 처리 명문화**: 스냅샷 수신 전 realtime과 `seq <= snapshot.seq` realtime은 폐기, `seq > snapshot.seq`부터 연속성 검사 |

**신설**: §3-7 데모 세션 인증(브라우저 데모 전용). `POST /api/v1/demo/session`(무인증·IP당 10회/60초)으로 시드된 데모 계정 한정 단기 JWT(HS256, 만료 15분, scope TRADE)를 발급하고, 브라우저 경로는 Bearer만으로 인증한다(HMAC·query_hash·nonce 미적용). 서버 서명 비밀은 브라우저에 전달하지 않으며(웹 화면명세서 §6.1), 봇·외부 클라이언트는 기존 §3 서명 규약을 유지한다.

**세부 노트**
- W2의 수치 재계산은 게이트웨이 구현·통합테스트(수취 BTC 순액, KRW 락 무수수료)와 대조 검증된 값이다. v2.2 Q2·v2.6 R6 패치 노트의 역사 서술(500,250·200,100)은 원문 유지하고 현행 정본은 본 패치 수치로 읽는다.
- 문서 헤더 버전을 v2.7로 순증(§8·v2.1~v2.6 패치 노트의 역사 서술은 원문 유지).
---

## v2.8 패치 반영 사항

M1-5.5 시장 데이터 구현(candles·ticker 24h)에서 확정한 규약을 반영했다.

| # | 심각도 | 위치 | 문제 | 수정 |
|---|---|---|---|---|
| X1 | medium | §4-A-4 | ticker `change_rate`·`change_price`의 부호 권위가 불명확해 색 단독 표현 금지 규칙(프론트 §6.2)의 데이터 근거가 없었다 | **부호·크기 분리** 명문화: `change`가 부호 권위, `change_rate`·`change_price`는 절대값. 24h 전 종가(`executed_at ≤ now-24h` 최근가, 없으면 최초가 폴백) 대비 산출. 24h 무체결 시 집계 `null`/`"0"`·`change=EVEN` |
| X2 | medium | §4-A-5 | candles 데이터 소스가 미정(별도 테이블 vs trades 집계)이라 `GET /candles/{unit}`가 미구현 500 | V1·데모는 별도 테이블 없이 **trades를 unit 구간으로 실시간 집계**로 확정(재구축 가능, 저장 없음). 구간 경계 epoch 정렬, OHLCV·seq 산출·sparse 갭 규약 명시 |

**세부 노트**
- X2 구현은 스캔 비용 경계를 위해 집계 대상 trades를 `count`·`unit` 창 근방으로 제한한다. 매우 희소한 이력에서는 반환 캔들 수가 `count`보다 적을 수 있으나(sparse 규약과 정합) 데모 관측 창에서는 영향 없다.
- 인라인 버전 태그(§4-A-4·§4-A-5)를 문서 헤더 v2.8과 일치. 문서 헤더 버전을 v2.8로 순증(§8·v2.1~v2.7 패치 노트의 역사 서술은 원문 유지).

---

## v2.10 패치 반영 사항

봇_방향성_거버넌스_설계·체결_시각효과_관리자제어_설계의 정본 개정 선행분과 문서 드리프트 정합을 반영했다. 문서 명문화이며 코드 구현은 봇·효과 모듈 착수 시 별도다.

| # | 심각도 | 위치 | 문제 | 수정 |
|---|---|---|---|---|
| Y1 | high | §3-2 | scope enum이 소문자·`read_only`로 표기돼 DB CHECK(`READ/TRADE/WITHDRAW/ADMIN` 대문자, V1+V5)와 불일치 | scope 어휘를 대문자 `READ|TRADE|ADMIN|WITHDRAW`로 통일. DB CHECK가 정본임을 명시, `read_only`·소문자 폐기 |
| Y2 | medium | §6-A | 봇 set-point 제어(scale/bias/effects)·status schema 1.1이 정본 표에 부재 | `PUT /admin/v1/sim/scale|bias|effects` 3행 추가(approval 불요, 감사만). status schema 1.1 additive 확장 명문화. §3-6 필수 완결 열거 불변 |
| Y3 | medium | §4-A-6 | 트레이딩 UI 효과 강도 공개 조회 경로 부재 | `GET /api/v1/exchange-config` 공개 API 신설(무인증, 화이트리스트 전용). `trade_flash` 노출, 쓰기 권위는 §6-A `/sim/effects` |
| Y4 | low | §4-A-1 | 수수료 예시는 markets `maker/taker=0.0005`로 이미 확정(§4-A-1/§4-B-5). DB 문서·마이그레이션의 taker `0.0010`과 충돌 | 정본값을 `taker=0.0005`로 확정(API·UI 표면값이 정본, 근거 §4-A-1 권위 소스 확정·§5-5 예시). DB 문서 정합은 DB 설계 v2.11, 코드 정합은 마이그레이션 변경 필요(V1__init.sql taker DEFAULT) |

문서 헤더 버전을 v2.10으로 순증(v2.1~v2.8 패치 노트의 역사 서술은 원문 유지).

---

## v2.11 패치 반영 사항

M3 게시판(커뮤니티) API를 §4-D로 신설했다. 게시판_설계.md §4 정본을 REST 계약으로 확정하고 구현(`kr.exchange.api.community`, `V7__community_board.sql`)과 정합시킨 것이다.

| # | 위치 | 내용 |
|---|---|---|
| C1 | §4-D 신설 | `/api/v1/community` 글·댓글·좋아요·신고 엔드포인트. 읽기 무인증, 쓰기 회원 세션(aud=community) |
| C2 | 인증 경계 | 게시판은 거래 평면과 aud로 분리. ApiAuthFilter 제외 경로이며 CommunityAuthenticator가 EdDSA·aud=community·kind=HUMAN을 자체 검증. 비회원 토큰 쓰기는 401 |
| C3 | 모더레이션 | 숨김·복구·삭제·신고 처리는 관리 평면 MEMBER_MOD(`/admin/v1/community`). 원장·저널 무접촉, 전 액션 감사 |
| C4 | 에러 | `409 duplicate_report`(대상별 1인 1회 위반) 추가. 나머지는 §2-3 재사용 |

문서 헤더 버전을 v2.11로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.12 패치 반영 사항

§7-2에 FOK 자기유동성 규약을 명시했다. 신규 계약 창설이 아니라 기존 매칭엔진 구현(`OrderBookImpl.wouldFullyFill`·STP-DC 경로)의 확정 동작을 문서로 드러낸 것이다.

| # | 위치 | 내용 |
|---|---|---|
| D1 | §7-2 | FOK 사전 스캔이 자기 유동성(같은 `user_id` 반대편 레스팅)을 충족 유동성에 포함함을 명시. 스캔은 실행 루프와 동일 순회·동일 감액을 재생하며 STP-DC 소멸분도 taker 잔량 감액으로 계산한다(제외 시 스캔·실행 판정이 갈려 결정론 파괴) |
| D2 | §7-2 | 자기 유동성만으로 전량 충족되는 FOK는 `fok_not_filled`로 거부되지 않고 접수되며, 해당 부분은 체결 아닌 STP-DC 소멸(maker·taker 양쪽 `self_trade_prevented` CANCELED)로 종결, taker 잔량 0 도달(무체결 전량 소멸 가능) |

문서 헤더 버전을 v2.12로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.15 패치 반영 사항

§4-D 공개 GET에 소유권 표시(`mine`)를 additive로 신설했다. 웹이 가변·비유일 handle로 소유권을 파생해 동명이인 회원에게 타인 글 수정·삭제 어포던스를 오노출하던 결함(게시판_설계.md §1.1 위반)을 서버 권위로 옮긴 것이다.

| # | 위치 | 내용 |
|---|---|---|
| E1 | §4-D | 공개 GET(글 목록·단건·댓글 목록)을 optional-auth로 확정. 유효 회원 Bearer 동봉 시 응답 항목에 `mine` 불리언 additive 포함. 토큰 부재·무효·만료는 조회를 막지 않고 `mine=false` 강등 |
| E2 | §4-D | `mine`은 서버가 토큰 `user_id`와 작성자 내부 id를 대조해 산출. 작성자 식별자(member_external_id=세션 `sub`)는 공개 응답에 노출하지 않음(최소 노출). 소유권 어포던스는 handle 대조가 아니라 `mine` 판정 |

문서 헤더 버전을 v2.15로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.16 패치 반영 사항

노드 수용 제한(admission control)을 §2-5-a로 신설했다. DB 지연 하에서 요청이 무한 수용돼 노드가 스스로 죽던 라이브 결함(DB §5.4 v2.20)의 접수 계약 쪽 반영이다. 기존 엔드포인트·스키마·서명 규약은 무변경이며 새 에러코드도 없다(기존 429 재사용).

| # | 위치 | 내용 |
|---|---|---|
| F1 | §2-5-a | 계정 버킷과 독립인 **노드 용량 버킷** 신설. 주문 쓰기 동시 처리 수를 요청 풀의 75%(하한 4)로 유계화하고 초과분을 무대기 거절. 조회 경로는 대상 아님 |
| F2 | §2-3, §2-5-a | 거절은 `429 too_many_requests` + `Retry-After` + `detail.bucket="node"`. 신규 코드를 만들지 않고 기존 429의 위반 버킷 축을 `account`\|`node`로 확장 |
| F3 | §2-5-a, §7-6 | 수용 거절은 **접수 이전**이라 저널·원장에 흔적이 없다. 상태는 `UNKNOWN`이 아니라 확정 미접수이며 재조회 상태머신 대상이 아니다. 동일 `client_order_id` 재전송으로 수렴 |
| F4 | §2-5-a | 503을 쓰지 않는 근거 명문화: 503은 `UNKNOWN` 재조회 부하를 굶는 노드에 되먹이고, 인그레스가 접수 경로 503을 노드 장애로 계상해 정상 리더를 업스트림에서 빼며, 그 결과 좀비 판정의 수요 축까지 소멸시킨다 |

문서 헤더 버전을 v2.16으로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.17 패치 반영 사항

v2.16(쓰기 전용 수용 제한)을 라이브에서 검증한 결과 백프레셔는 작동했으나 노드는 여전히 죽었다. 남은 부하가 조회였다. §2-5-a를 조회 버킷·면제 경로·배분 재설계로 확장했다. 엔드포인트·스키마·서명 규약은 여전히 무변경이고 새 에러코드도 없다.

| # | 위치 | 내용 |
|---|---|---|
| G1 | §2-5-a | 노드 용량 버킷을 `order`·`query` 둘로 분리. 조회(`/api/v1/**` 중 주문 쓰기 외 전부, 공개 시장 데이터 포함)도 유계화한다. 한 버킷으로 합치지 않는 이유는 그러면 한쪽이 다른 쪽을 굶기기 때문 |
| G2 | §2-5-a | 면제 경로 명문화: `/actuator/**`·`/harness/v1/**`(생존 신호, 게이트 URL 패턴 밖), `GET /orders/by-client/{coid}`(§7-6 유일 수렴 경로), `OPTIONS`(프리플라이트) |
| G3 | §2-5-a, §2-5 표 | 배분을 쓰기 75% 단일에서 **쓰기 40%·조회 40%·예비 20%**로 재설계. 합이 풀보다 작아야 하는 근거 둘(면제 경로·백그라운드 보호, 게이트된 트래픽의 커넥션 무대기 성질)을 명시 |
| G4 | §2-5-a | 거절 `detail.scope`가 `order`\|`query`로 갈린다. 코드·헤더는 종전대로 `429` + `Retry-After` |
| G5 | §2-3 | 인증 계층의 일시 DB 실패(커넥션 획득 타임아웃 등)를 `503 service_unavailable` + `Retry-After`로 매핑. 종전에는 인증 필터에서만 `500 server_error`로 나가 같은 사유가 경로에 따라 코드가 갈렸다 |

문서 헤더 버전을 v2.17로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.18 패치 반영 사항

v2.17이 E1 라이브 PASS(errorRate 0, epoch 불변, 주입 중 status 200 유지, 해제 후 자력 회복, cpu 22%)를 냈으나, 적대적 검토가 남은 무제한 DB 접촉 경로 하나를 지적했다. `GET /orders/by-client/{coid}`는 수용 게이트와 계정 토큰버킷 양쪽에서 빠져 동시성·요청률이 모두 무제한이었다. v2.17이 신설한 인증 계층 503이 하필 이 경로의 트래픽을 유발하는 되먹임 고리를 만든다. E2·E6은 5xx를 대량 유발하는 실험이라 정확히 그 노출 구간이다.

| # | 위치 | 내용 |
|---|---|---|
| H1 | §2-5-a, §2-5 표 | by-client 재조회를 완전 면제에서 **전용 소형 버킷(`scope=requery`, 풀의 10%, 하한 2)**으로 교체. 배분은 40/40/10/20 |
| H2 | §2-3, §2-5-a, §4-B-4(a) | 재조회 초과 응답으로 `409 requery_deferred` + `Retry-After` 신설. 429·503은 k6의 안전 집합(200·404·409) 밖이라 백프레셔가 곧 멱등 규약 위반이 된다. `order_in_progress`를 재사용하지 않는 이유는 그 이름이 저널 존재를 확정 주장하기 때문. 상태 기준 분기 클라이언트는 무변경 |
| H3 | §2-5-a | 무대기 유지 근거 명문화: '대기 후 통과'는 상한이 아니고 '대기 후 거절'은 대기열을 되살리며 거절도 남긴다 |
| H4 | §2-5-a | 무대기 성질의 주장 범위를 **게이트된 경로**로 한정. `/harness/v1/status`는 외부 질의 RowMapper 안에서 스칼라 질의를 돌려 한 건이 커넥션 2개를 동시에 쥔다. 예비분 산정에 이 비용을 명시 |
| H5 | §2-5-a | by-client 비용 서술 정정: 인덱스 단건 질의 **3회**(인증 `api_keys` 조회 포함, 캐시 없음) |
| H6 | §2-5-a | 봇의 429 반응 서술 정정: MM 봇은 ticker 비 200 시 같은 사이클에 orderbook으로 폴백하므로 조회 429가 만성화되면 조회 버킷에 최대 2배 요청이 들어온다 |
| H7 | §2-5-a | `scope=query`가 주문 쓰기가 아닌 쓰기(로그인·게시글 작성)를 포함함을 명시. 버킷 경계는 HTTP 메서드가 아니라 '주문 접수 경로인가'다 |
| H8 | §2-5-a | 게이트가 인증보다 앞이라 미인증 트래픽도 조회 슬롯을 소비한다는 교환을 명시. 인증 뒤로 옮기면 인증 자체가 DB 접촉이라 유계화가 성립하지 않는다 |

문서 헤더 버전을 v2.18로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.19 패치 반영 사항

공개 시장 데이터 평면에 IP 토큰버킷을 실제로 배선하면서, 표가 `1~2`로만 두고 넘어간 공백이 드러났다. 어느 경로가 1이고 2인지, 배치 조회를 어떻게 세는지, 버킷을 수용 게이트 앞에 두는지 뒤에 두는지가 전부 미정이었고 구현이 자의로 정할 수 있었다. 세 가지를 계약으로 확정한다.

| # | 위치 | 내용 |
|---|---|---|
| I1 | §2-5 표 | 공개 조회 행의 경로 열거를 실제 대상(`markets`·`orderbook`·`ticker`·`trades`·`candles`·`exchange-config`)으로 교정 |
| I2 | §2-5, §4-A 표 | weight 분할 확정: 다중행 리스트 2, 단건 객체 1. `ticker?markets=` 배치는 마켓 수와 무관하게 요청 1건으로 계상(마켓 수 계상은 개별 폴링을 배치보다 싸게 만들어 요청 수를 늘리는 유인이 된다). §4-A 엔드포인트 표의 경로별 weight도 같은 값으로 정렬(`markets`·`trades`·`ticker` 1 -> 2). 종전 §4-A는 규칙 이전의 부분 배정이라 `candles`만 2였고, 그 표만 읽은 클라이언트는 예산을 2배로 오산한다 |
| I3 | §2-5 | 한도 300이 기본값임을 명시하고 배포별 오버라이드 키(`exchange.public.rate-limit.capacity`)를 등재. 봇 플릿·웹이 한 IP로 뭉치는 배포(데모·격리 카오스 스택)가 적용 대상이며, 노드 생존은 §2-5-a 수용 제한이 별도로 지킨다 |
| I4 | §2-5, §2-5-a | 적용 순서를 수용 게이트 뒤·인증 앞으로 확정(필터 6 -> 7 -> 10). 종전 §2-5-a의 "IP 버킷이 앞단 완화 수단" 서술은 순서를 오해하게 했다. 백프레셔 거절이 IP 토큰을 소비하면 장애 중 정상 클라이언트의 예산이 깎인다 |
| I5 | §2-5 신뢰 프록시 | "봇/데모는 계정 버킷 우선"이 공개 조회에는 성립하지 않음을 정정. 봇의 시장 데이터 조회는 계정 키를 싣지 않아 정의상 미인증 공개 조회다 |

문서 헤더 버전을 v2.19로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.20 패치 반영 사항

웹 어댑터가 §5-2 기준점 규약을 스냅샷·재동기화 경로까지 적용하면서, 순서 역전 규정이 한 방향으로만 쓰여 있던 공백이 드러났다. 문면대로면 늦게 도착한 낮은 seq 스냅샷이 기준점을 되돌린다. 서버 계약(스키마·전송 규약)은 변경이 없고 클라이언트 규칙만 확정한다.

| # | 위치 | 내용 |
|---|---|---|
| J1 | §5-2 구독 즉시 계약 | 기준점 전진 규칙 확정(**동일 epoch 내 한정**): 스냅샷보다 realtime이 먼저 도착했으면 그 seq가 임시 기준점이고, 스냅샷은 기준점보다 클 때만 채택한다. 낮은 seq 스냅샷 채택은 표시 상태를 역행시키고 이어지는 realtime을 결번으로 만든다 |
| J2 | §5-2 gap 규칙 | 같은 epoch 안에서는 기준점이 어느 경로에서도 내려가지 않음을 명시. REST 재동기화 스냅샷이 WS보다 뒤처진 노드를 읽는 경우가 적용 대상이다 |
| J3 | §5-2 epoch 규칙 | epoch 교체는 J1·J2의 예외임을 명시하되 **인메모리 카운터 실현 채널(orderbook·ticker·myOrder·myAsset) 한정**으로 범위를 확정. 그 채널은 리더 교체 시 카운터가 낮은 값에서 재시작하므로 기준점을 비우고 재동기화 스냅샷을 채택한다(전진 비교를 그대로 적용하면 신 리더 메시지가 구 리더 기준점에 막혀 영구 정지). trade·candle은 전역 영속 event_seq라 기준점을 유지한다(비우면 기수신 체결 재전달로 캔들 이중계상). 같은 이유로 실현 (1) 열거에 myOrder·myAsset을 명시해 비공개 채널이 미분류로 남지 않게 한다 |
| J4 | §2-5 공개 조회 weight 표 | v2.19 I2가 확정한 weight 분할(다중행 리스트 2, 단건 1)을 표에 반영. 이 표 수정은 26816d2(서버 IP 토큰버킷)의 문서 짝인데 작업 트리에 함께 있던 탓에 f11f016 커밋에 섞여 들어갔다. 이력이 끊기지 않도록 여기에 기록한다 |

문서 헤더 버전을 v2.20으로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.21 패치 반영 사항

`avg_buy_price`가 계약 필드로만 있고 서버가 상수 `"0"`을 내던 공백을 닫는다. DB 정본 §6.4 ⑱⑲가 원가 프로젝션(`position_cost`)을 등재하면서 원천이 생겼고, 이 판은 그 원천을 API 표면으로 어떻게 내는지를 확정한다. 화면이 평단가·평가손익을 서버 권위로 산출하기 위한 선행 계약이다.

| # | 위치 | 내용 |
|---|---|---|
| K1 | §4-C-1 | `avg_buy_price` 산출 규약 확정: 서버는 평단가를 저장하지 않고 누산기 쌍(`cost_qty`·`cost_amount`)만 적재하며, 조회 시 `cost_basis_amount / cost_basis_qty`를 `unit_currency` 자산 scale로 HALF_UP 반올림해 산출한다. **원가 확보분의 평단가이지 보유 전량의 평단가가 아님**을 명시 |
| K2 | §4-C-1 | additive 필드 2개 신설: `cost_basis_qty`(원가 확보 수량), `cost_basis_amount`(원가 확보 금액). 둘 다 내는 이유는 `avg_buy_price`가 quote scale 반올림값이라 `수량 × 평단가`로 총매수를 재구성하면 원 단위로 어긋나기 때문이다. 정확한 총매수·평가손익은 반올림 이전 원본이 있어야 성립한다. 기존 필드·엔벌로프는 불변(additive only, §4-D `mine` 선례와 동일 규율) |
| K3 | §4-C-1 | **원가 미상 수량은 별도 필드를 만들지 않는다**. 클라이언트가 `balance + locked - cost_basis_qty`로 유도한다. 세 항이 같은 항목 안에 있고 서버가 `cost_qty + unknown_qty = available + locked` 항등을 상시 감사하므로(하네스 C24), 필드를 더하면 같은 사실의 두 번째 표현이 생겨 어긋날 여지만 늘어난다 |
| K4 | §4-C-1 | 원가 0과 원가 미상의 구분을 계약으로 명시. 시드 발행·입금 취득분은 원가 0이 아니라 미상이라 `cost_basis_qty`에 들어가지 않으며, 보유 전량이 미상이면 `avg_buy_price`는 `"0"`이다. 클라이언트는 이 `"0"`을 "평단가 0원"이 아니라 **산출 불가**로 렌더링하고 판별자는 `cost_basis_qty = 0`이다. quote 자산 행(v1 `KRW`)도 같은 이유로 세 필드가 `"0"` |
| K5 | §5-5 myAsset | 델타 항목 스키마에 같은 원가 필드 집합을 신설. **부가가 아니라 필수 계약**이다. 평단가는 매수 체결마다 바뀌는데 델타에 없으면 클라이언트가 직전 값을 보존할 수밖에 없고, 그 보존이 매수마다 낡은 평단가를 `GET /accounts` 재적재 시점까지 화면에 남긴다. 예시를 §4-B-5 수취 자산 수수료 모델과 정합하게 재계산(`200000 / 0.003998` → `50025013`, 원가 미상 0.5) |
| K6 | §4-C-1 | 금액 표기 정정: 예시의 `balance`·`locked`가 18자리를 채운 표기였으나 구현(`ApiFormats.amount`)과 §5-5 예시는 후행 0 제거 평문이다. §2-1 문자열 계약은 자릿수 고정을 요구하지 않으므로 정본 표기를 후행 0 제거로 통일한다(저장 타입 `NUMERIC(40,18)`과 전송 표기는 별개) |
| K7 | §4-C-1 | 예시 엔벌로프에 누락돼 있던 `schema_version` 보정. §2-2 엔벌로프 계약·구현과 일치시키는 표기 정정이며 필드 신설이 아니다 |

`realized_pnl`·`unknown_proceeds`는 `position_cost`에 적재되지만 이 판에서 API로 노출하지 않는다. 화면 요구가 없는 필드를 계약에 넣으면 검증 표면만 늘어난다. 노출 시점에 별도 판으로 등재한다.

문서 헤더 버전을 v2.21로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.22 패치 반영 사항

§6-A 표가 `POST /sim/start|stop|pause`를 "전체 제어"로만 두고 의미·응답을 미정의해, 구현이 감사 적재만 하고 플릿을 건드리지 않아도 문면 위반이 아니었다. 실제 서버가 그 상태였고 웹 콘솔은 미반영을 자인하는 안내로 버텼다. 제어 의미와 응답 계약을 확정해 공백을 닫는다.

| # | 위치 | 내용 |
|---|---|---|
| L1 | §6-A | 전체 제어 의미 확정: `start`=미기동 기동·일시정지 해제, `stop`=정지(pause 잔류 없음), `pause`=발행 일시정지 토글. pause는 봇 스레드·시드·오더북 상태를 유지하고 전역 OPS 게이트만 닫는다(재개 시 워밍업 없이 즉시 발행 재개). 스레드 재생성 없는 게이트 방식이라 토글 반복이 시드·저널에 흔적을 남기지 않는다 |
| L2 | §6-A | 제어 응답 schema 1.1 등재: `command`·`applied`·`running`·`paused`·`fleet_present`. 종전 1.0(`command`뿐)은 반영 여부를 알 수 없어 콘솔이 성공 토스트를 단정할 근거가 없었다. 수명주기 플릿 빈 부재 배포는 `applied:false`로 미반영을 정직하게 보고한다(§6-A `fleet_present` 선례). 미기동 플릿에의 `pause`도 멈출 발행이 없으므로 `applied:false` |
| L3 | §6-A | `GET /sim/status` schema 1.2: top-level `running`·`paused` additive 추가(기존 1.1 소비자 무영향). 웹 콘솔이 `fleet_present`를 running으로 오독해 쓰던 임시 매핑을 실제 수명주기 상태로 대체하기 위한 원천이다 |

문서 헤더 버전을 v2.22로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.27 패치 반영 사항

§4-E `GET /api/v1/transfers`가 `cursor`를 받으면서 `next_cursor`를 항상 `null`로 내려, 표에 "커서 페이지네이션(§2-6)"이라 적힌 리스트를 클라이언트가 2쪽으로 넘길 수 없었다. 발급 경로를 열고 커서 형식을 §2-6 정본에 맞춘다.

| # | 위치 | 내용 |
|---|---|---|
| 1 | §4-E 목록 | `has_more`가 참이면 `next_cursor`를 발급한다. 종전에는 리터럴 `null` 고정이라 문서상 커서 리스트가 실제로는 첫 쪽 전용이었다 |
| 2 | §4-E 목록 | 커서를 내부 BIGINT PK의 10진 문자열에서 §2-6 opaque `(created_at, external_id)` base64url로 교체한다(공용 `Cursors`). 종전 형식은 §2-6의 "내부 BIGINT PK 미노출"에 어긋났고 행 개수·채번 속도를 유추할 여지를 뒀다 |
| 3 | §4-E 목록 | 정렬 축을 `(created_at DESC, id DESC)`로 옮긴다. `id` 단독 축은 겹친 트랜잭션에서 시각 순서와 갈려 요청 축과 응답 축이 어긋난다(§2-6). DB 설계 v2.31 `idx_transfer_user_keyset`이 이 축을 덮는다 |
| 4 | §4-E 목록 | 커서 앵커를 `user_id` 스코프로 되짚는다. 타인의 `external_id`를 커서로 넘겨 남의 정렬 위치를 캐던 무경계 조회를 막고, 부재·위조 커서는 형식 오류와 같은 `invalid_parameter`(400)로 정규화한다. 조용한 빈 쪽은 목록의 끝으로 오독된다. 주문 목록(§4-B)의 선례와 동일하다 |

파라미터는 `limit`(기본 50, ≤100)을 유지한다(§2-6의 `count`와 이름이 다르나 종전 계약 보존). 엔벌로프·에러코드·멱등·금액 규약은 무변경이다.

문서 헤더 버전을 v2.27로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.28 패치 반영 사항

§4-G `GET /api/v1/support`에 커서 페이지네이션을 붙인다. v2.27이 입출금에서 연 것과 같은 규약이며, 문의 목록만 `pagination` 없이 `data`만 내려 §2-2의 "페이지네이션이 없는 리스트도 리터럴로 존재한다"에도 어긋나 있었다.

| # | 위치 | 내용 |
|---|---|---|
| 1 | §4-G 목록 | `cursor` 파라미터와 `pagination:{next_cursor, has_more}` 엔벌로프를 추가한다. 종전에는 `limit`만 있어 상한 밖의 문의를 볼 방법이 없었고, 응답에 `pagination` 자체가 없었다 |
| 2 | §4-G 목록 | 커서는 §2-6 opaque `(created_at, external_id)` base64url이고 정렬 축은 `(created_at DESC, id DESC)`다. DB 설계 v2.32 `idx_support_user_keyset`이 이 축을 덮는다 |
| 3 | §4-G 목록 | 커서 앵커를 `user_id` 스코프로 되짚는다. 부재·위조 커서는 `invalid_parameter`(400)로 정규화한다. §4-E와 동일하다 |

`limit`은 기본 50, ≤100을 유지한다. 등록·답변 경로와 rate limit은 무변경이다.

문서 헤더 버전을 v2.28로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.29 패치 반영 사항

§4-F 공지 목록의 조용한 절단을 닫는다. 공지는 삭제되지 않고 철회만 되므로(DB 설계 §7, `app_role`에 DELETE 미부여) 활성 공지가 상한을 넘는 상태가 실제로 온다. 관리 평면 발행 경로의 길이 미검증도 함께 고친다.

| # | 위치 | 내용 |
|---|---|---|
| 1 | §4-F 목록 | `LIMIT 50` 고정을 `cursor` + `limit`(기본 50, ≤200)으로 바꾸고 `pagination:{next_cursor, has_more}`를 싣는다. 종전에는 51번째 공지가 존재하는데도 어떤 클라이언트도 도달할 수 없었고, 잘렸다는 표시조차 없었다. `pagination` 부재 자체가 §2-2 위반이기도 하다 |
| 2 | §4-F 목록 | 정렬 축을 `(published_at DESC, id DESC)`로 확정한다. `published_at`은 유일하지 않아 단독으로는 쪽 경계가 정해지지 않는다. 동시각 발행 두 건이 쪽을 넘길 때 중복되거나 빠진다. DB 설계 v2.33 `idx_notice_active_keyset`이 이 축을 덮는다 |
| 3 | §3-6 `GET /admin/v1/config/notices` | 같은 결함이다. `LIMIT 100` 고정을 커서로 바꾸고 `pagination`을 싣는다. 관리 목록은 철회분까지 보여주므로 공개 목록보다 빨리 쌓인다 |
| 4 | §3-6 `POST`·`PATCH /admin/v1/config/notices` | 제목·본문 길이 검증을 세운다. 종전에는 검증이 없어 200자를 넘는 제목이 `title VARCHAR(200)`에서 DB 예외로 터져 400이 아니라 500이 됐고, 본문은 상한 자체가 없어 공지 한 건이 화면을 덮을 수 있었다. 제목 200자, 본문 2000자(`SupportInputs.MAX_BODY`와 같은 근거·같은 값), 위반은 `invalid_parameter` |

커서의 id 축이 내부 BIGINT PK인 것은 §2-6 "내부 PK 미노출"의 명시 예외다. 근거는 §4-F 본문에 적었다. 심각도 계약·배너 규칙·노출 범위(`active = true`)는 무변경이다.

문서 헤더 버전을 v2.29로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.30 패치 반영 사항

목록 전수 점검에서 §2-6 커서 규약과 §2-2 엔벌로프를 소비하지 않는 곳 9건이 나왔다. 규약은 문서에 있고 공용 코덱(`Cursors`)도 있는데 커뮤니티·주문 조회만 쓰고 나머지가 각자 고정 `LIMIT`을 다시 만든 상태였다. 조립 로직을 `CursorPages`로 모으고 아홉 곳을 그 위로 옮긴다.

**A. 조용한 절단** (도달 불가 데이터)

| # | 위치 | 종전 | 내용 |
|---|---|---|---|
| 1 | `GET /admin/v1/audit` | `LIMIT 500` | 감사 로그는 append-only 영구 누적인데 상한 밖 기록에 도달할 방법이 없었고 잘렸다는 표시도 없었다. 사고 조사가 목적인 기능에서 "기록이 없다"와 "기록을 못 본다"가 구분되지 않았다 |
| 2 | `GET /api/v1/orders/closed` | 서버는 커서 완비 | 프론트가 `pagination`을 버려 기본 100건 밖의 주문을 볼 수 없었다. 서버 무변경, 소비 측 결함 |
| 3 | `GET /admin/v1/approvals` | `LIMIT 500` | 승인 이력은 소비·만료 뒤에도 남아 누적된다 |
| 4 | `GET /admin/v1/chaos/runs` | `LIMIT 200` | 감사 로그 파생이라 같이 누적된다 |
| 5 | `GET /admin/v1/support`, `GET /admin/v1/transfers` | `LIMIT 200` | `ANSWERED`·`COMPLETED`·`CANCELED` 조회는 처리 뒤에도 남는다 |

**B. 비유일 정렬 축**

| # | 위치 | 종전 | 내용 |
|---|---|---|---|
| 6 | 문의 큐 | `ORDER BY s.created_at` | `id` 보조 축이 없어 같은 시각 두 건 중 어느 쪽이 상한에 잘릴지 비결정이다. 운영자가 새로고침할 때마다 큐 끝이 바뀐다 |
| 7 | 출금 큐 | `ORDER BY t.created_at` | 동일. `AuditController`가 같은 이유로 `id` 보조 축을 이미 명문화해두고 있었다 |

**C. §2-2 엔벌로프 부재**

| # | 위치 | 내용 |
|---|---|---|
| 8 | `GET /admin/v1/community/reports` | `LIMIT 200`이 SQL에 박혀 파라미터로도 못 넘겼다. 커서 + 엔벌로프를 붙인다 |
| 9 | `GET /admin/v1/operators`, `GET /admin/v1/config/markets` | 조직 규모·카탈로그로 유계라 커서는 발급하지 않되 `pagination:{null,false}`를 리터럴로 채운다 |

**부수 교정**: `GET /api/v1/my/trades`·`/my/ledger`가 다음 쪽 존재를 "쪽이 꽉 찼으면 더 있다"로 추정하고 있었다. 마지막 쪽이 정확히 `count`와 같은 수로 끝나면 더 있다고 보고해, 클라이언트가 빈 쪽을 한 번 더 읽고서야 끝을 안다. 화면에서는 눌러도 아무것도 안 나오는 "다음"이었다. 전 목록을 한 건 더 읽는 탐침으로 통일한다.

관리 평면 목록의 커서 id 축은 대상 표의 외부 식별자를 쓰되, 내부 BIGINT를 공개 식별자로 이미 쓰는 표(`admin_audit_log`·`admin_approvals`·`reports`·`notices`)는 그 값을 그대로 쓴다. 관리 평면은 mTLS + 관리 세션 뒤라 사용자 스코프 탐침 표면이 없다. DB 설계 v2.34가 정렬 축 인덱스를 맞춘다.

문서 헤더 버전을 v2.30으로 순증(이전 패치 노트의 역사 서술은 원문 유지).

---

## v2.33 패치 반영 사항

관리 평면의 시뮬레이션 제어가 봇에 닿지 않던 것을 고친다. 실측(2026-08-29): `spike` 프리셋 활성화가 200과 감사 적재를 남기고 `fleet_present:false`로 끝났으며 봇 발행량은 그대로였다.

원인은 배치다. 봇 거버너는 bots 프로파일 JVM의 인메모리 상태이고 관리 평면은 별도 컨테이너다(관리자 설계 §0). 관리 노드에는 그 빈이 없다.

| # | 심각도 | 대상 | 내용 |
|---|---|---|---|
| 1 | high | `POST /sim/scenarios/{name}/activate`, `PUT /sim/rate` | 거버너 부재 시 `sim_commands`(DB§V22)에 의도를 적재하고 봇 JVM이 폴링해 적용한다. 응답에 `apply_path`(`local`\|`queued`)와 `queued_command_id` 순증 |
| 2 | high | `POST /sim/scenarios/{name}/activate` 응답 | `queued`에서 `target_ops`·`base_ops`·`markets`·`clamped`를 내지 않는다. 종전에는 `base_ops`가 `0.0`으로 고정돼 거기서 나온 파생값을 응답과 감사에 남겼고 봇의 실제 적용값과 달랐다. `PUT /sim/rate`의 `target_ops`는 요청 에코라 해당 없다 |
| 3 | med | `PUT /sim/rate`의 `applied` | 뜻을 "이 응답 시점에 봇에 반영됐는가"로 계약에 명시한다. 값 자체는 종전과 같은 "거버너가 이 JVM에 있는가"다. 큐 경로가 생기기 전에는 `false`가 곧 미적용이었으나 이제는 아니라서, 이 필드만 보고 실패를 단정하면 성공 경로를 실패로 표시한다 |
| 4 | high | `GET /admin/v1/ha/recovery` | `verdict` 객체 순증(스키마 1.0에서 1.2). 배열만 반환해 통과 여부를 사람이 눈으로 세야 했다 |
| 5 | high | 같은 엔드포인트 | 판정 집계를 응답 배열이 아니라 표 전체에서 한다. `since` 창으로 좁힐 수 있고 그 창은 배열에도 걸린다. 배열을 세면 창 밖 위반이 안 보였고, 창을 집계에만 걸면 판정과 표시가 갈렸다 |
| 6 | med | 같은 엔드포인트 | `fp_replay`·`fp_live`를 방출한다. SELECT하고 매퍼가 버려 재생 0건과 실재 재생을 API 응답에서 가를 수 없었다. fp에 `verified_events`가 실려 그 구분이 선다 |
| 7 | med | 같은 엔드포인트 `failover_runs[]` | `old_leader`·`new_leader`·`old_epoch`·`new_epoch`를 방출한다. 앞의 둘은 SELECT하고 버렸고 epoch 둘은 SELECT도 없었다. epoch 단조는 페일오버 판정의 축이라 행에 있으면 낸다 |

`verdict`의 판정 기준은 계약이다. RTO<30s, RPO=0(§7-3 P6), 재생 `matched=false` 0건(검증설계 C17), 그리고 `failover_runs.verdict` 행 판정 `PASS` 전건이다. 넷째 축을 두는 이유는 하네스가 RTO·RPO 외에도 무결성 clean·epoch 단조·체결 재개를 함께 보고 그 결론을 행에 박기 때문이다. 그것을 버리면 RTO만 정상인 실패 주행이 통과한다.

NULL 취급이 축마다 다르다. `rto_seconds`·`rpo_records`의 NULL은 `UNKNOWN`이고, `recovery_runs.matched`와 `failover_runs.verdict`의 NULL은 `FAIL`이다. 앞의 둘은 계측값이고 뒤의 둘은 결론값이다.

`matched` NULL을 불일치로 세는 것은 검증설계 C17 SQL(`matched = false`만 본다)과 다르다. 이 응답이 더 엄격하다. 기록자가 값을 못 넣은 것은 재생이 정상 종료하지 않았다는 뜻이고, 그것을 통과로 세면 계측 실패가 성공으로 보인다. C17 SQL은 위반 행을 세는 축이라 NULL을 위반으로 올리면 미기록을 결함으로 신고한다. 두 축의 목적이 달라 규칙이 갈린다. 앞의 둘은 계측값이라 못 잰 것과 위반이 다르지만, 행 판정은 하네스가 반드시 채우는 결론값이라 비어 있다는 것 자체가 정상 종료하지 않은 주행이다. 현재 하네스는 항상 `PASS`나 `FAIL`을 넣으므로 이 경로는 도달하지 않는다.

`verdict`는 세 값을 낸다. `PASS`·`FAIL`·`UNKNOWN`이다. 표가 비었거나 `rto_seconds`·`rpo_records`가 NULL이면 `UNKNOWN`이다. 0행 대조로 통과를 내면 공허 통과이고, NULL을 통과로 세면 계측 실패가 성공으로 보인다. `FAIL`이 `UNKNOWN`을 이긴다.

`verdict.scope`는 창 미지정이면 `all`, `since`를 주면 `since`다. 집계는 어느 쪽이든 배열이 아니라 표에서 한다. 배열은 `limit`로 자른 화면 표시용이라 그것을 세면 창 밖 위반이 안 보인다(기본 20에서 21번째 실패 행이 `PASS`가 된다).

문서 헤더 버전을 v2.33으로 순증.

---

## v2.40 패치 반영 사항

수용 상한 거절 문구를 확정한다(§2-5-a). 응답 코드·헤더·`detail` 구조는 무변경이고 사용자 노출 문장만 바꾼다.

| # | 심각도 | 대상 | 내용 |
|---|---|---|---|
| 1 | medium | §2-5-a 거절 문구 | 종전 `노드 처리 용량 초과. 잠시 후 재시도 필요`는 원인을 노드로 적어 설계된 백프레셔를 서버 장애로 읽히게 했다. 이 게이트가 재는 것은 세마포어 `limit` 개의 동시 in-flight 이지 노드의 처리 능력이 아니며, 상한이 6이면 일곱 번째 요청은 노드가 한가해도 거절된다. `동시 처리 한도 도달. 잠시 후 재시도 가능`으로 확정한다 |
| 2 | medium | 정본 공백 | 이 문장은 사용자에게 그대로 노출되는데 어느 정본에도 등재된 적이 없었다. §2-5-a가 `429 too_many_requests` + `Retry-After: 1` + `detail`까지만 규정하고 문구를 비워 두었다. 22절이 사용자 노출 문구를 정본 대상으로 두므로 공백을 채운다 |

**오역 제거가 §2-3의 목적이므로 문구가 그 목적과 어긋나면 안 된다.** §2-3은 "조회 429를 종결 실패로 적지 않는다"로 백프레셔를 고장으로 번역하지 말 것을 규정한다. 문구가 노드 장애를 지시하면 클라이언트가 아무리 옳게 처리해도 사용자는 서버가 아프다고 읽는다.

어느 버킷이 찼는지는 `detail.scope`(`order`|`query`)가 이미 싣는다. 문구에 버킷을 넣지 않는 이유는 사용자가 그 구분으로 할 수 있는 일이 없어서다. 운영 진단은 `detail`과 지표가 맡는다.

구현은 `server/app/src/main/java/kr/exchange/api/common/AdmissionControl.java`의 `Shed.CAPACITY`이고, 웹은 이 문장을 전역 오류 토스트로 올리지 않는다(§2-3, `globalErrorNotice.isBackpressure`).

문서 헤더 버전을 v2.40으로 순증.

---

## v2.41 패치 반영 사항

수용 게이트 런타임 스위치를 신설한다(§2-5-a). 응답·버킷·상한 유도는 무변경이다.

| # | 심각도 | 대상 | 내용 |
|---|---|---|---|
| 1 | medium | §2-5-a 런타임 스위치 | 종전에는 상한 크기만 설정 가능하고 켜고 끄는 스위치가 없었다. 수용 제어가 없는 상태를 재현하려면 기동 가드가 거부하는 오설정을 쓰거나 코드를 바꿔야 했다. `exchange.admission.enabled`(기동값)와 actuator `admission`(런타임)을 둔다. 기본 켜짐. 끄는 것은 실험 전용이고 전환마다 WARN 감사 로그를 남긴다 |

이 스위치가 필요한 이유는 기록된 사고를 재현하기 위해서다. ADR 0007의 1차 사고(cpu 463%)는 수용 제어가 없는 상태에서 났고, 현재 코드는 그 상태에 도달할 길이 없다. 실험은 그 상태에서 운영자가 무엇을 보고 어떻게 판단해 복구하는지를 검증한다. 스위치의 자리는 실험 전 상태 설정이지 장애 중 복구 레버가 아니다(v2.42 정정, §2-5-a).

문서 헤더 버전을 v2.41로 순증.

---

## v2.42 패치 반영 사항

수용 게이트 런타임 스위치 서술 두 곳을 실측과 코드에 맞게 정정한다(§2-5-a, v2.41 패치 노트). 코드·응답 코드·버킷 분류·상한 유도는 무변경이다.

| # | 심각도 | 대상 | 내용 |
|---|---|---|---|
| 1 | high | v2.41 패치 노트 | `스위치를 켜는 조치가 곧 Runbook 의 즉시 대응이다`가 §2-5-a 본문과 어긋났다. 본문은 같은 스위치를 실험 전 상태 설정 수단으로 규정하고 장애 중 즉시 레버를 앱 바깥(ALB 규칙, WAF 레이트 제한, 재기동)에 둔다. 한 문서 안의 정본 충돌이고, 실측이 본문 쪽을 지지한다 |
| 2 | high | §2-5-a 런타임 스위치 | `꺼진 동안의 요청은 획득도 반납도 하지 않으므로 켜는 순간 in-flight 는 0 에서 시작한다`가 거짓이었다. 앞 절은 참이나 뒤 절이 따라 나오지 않는다. 끄기 전에 획득된 슬롯은 그 요청이 끝나야 반납되므로, 끝나지 않으면 켠 직후 첫 요청이 곧바로 429 다 |

**1의 근거.** `harness/evidence/gateoff-20260902/`. 게이트 OFF 100 OPS 로 정지한 노드에 `POST /actuator/admission {"enabled":true}` 가 도달하지 못했다. 60초 타임아웃 3회다(README 시각 표 20:54:45). 같은 구간에 readiness 와 `/actuator/prometheus` 도 응답하지 않고 `samples_15s.csv` 는 20:53:02부터 `gate=ERR` 만 기록한다. actuator 자체가 죽은 노드에서 actuator 조작을 대응 절차로 적을 수 없다. 정지 기전은 `InflightReserves.tryReserve` 안의 DB 조회가 풀 고갈로 대기하면서 가상 스레드가 캐리어에 고정된 것이고, 이 상태에서 도달한 레버는 재기동뿐이다.

**2의 근거.** `AdmissionFilter.doFilterInternal` 은 게이트가 꺼져 있으면 버킷을 고르기 전에 반환하므로 permit 을 건드리지 않는다. 반납은 `tryAcquire` 성공 분기의 `finally` 에만 있다. 끄기 직전에 획득된 슬롯의 운명은 스위치가 아니라 그 요청의 종료 여부가 정한다. `AdmissionGateTest.다시_켜면_상한이_즉시_돌아온다`가 상한 1을 미리 채운 뒤 OFF 에서 ON 으로 전환하고 첫 요청이 `429` 임을 단언한다. 원래 문장이 참이 되는 조건은 끌 때 in-flight 가 0이었을 때뿐이라, 조건을 빼고 쓰면 정지 노드에서 정반대가 된다.

실측 1회차의 잔여 슬롯은 0이었다. 게이트를 노드가 건강한 10 OPS 구간(20:50:54)에서 껐기 때문이다. `samples_15s.csv` 는 actuator 가 응답한 마지막 샘플(20:52:35)까지 `adm_inflight_order`·`adm_inflight_query` 를 0으로 기록한다. 같은 행의 `active 16.0` 은 요청 커넥션 풀 게이지(`hikaricp_connections_active`)이지 수용 in-flight 가 아니다. 두 지표를 섞어 읽으면 정지 시점의 슬롯 잔여를 16으로 오독한다.

문서 헤더 버전을 v2.42로 순증.

---

## v2.43 패치 반영 사항

§6-A가 `GET /sim/status`를 관리 평면과 봇 플릿의 동거 전제로만 규정해, 물리 분리 배포에서 무엇을 내야 하는지가 공백이었다. 구현은 그 공백을 `false`·`"0"`·`[]`로 메웠고 콘솔이 그것을 사실로 그렸다. 원천을 명시하고 모르는 값을 비우도록 확정한다.

| # | 위치 | 내용 |
|---|---|---|
| L1 | §6-A | `GET /sim/status` schema 1.3: `source` additive 추가(`local`·`published`·`unknown`). 값이 이 JVM의 거버너에서 왔는지, 봇 노드 발행분인지, 아예 모르는지를 소비자가 가를 수 있게 한다 |
| L2 | §6-A | `unknown`에서 `running`·`paused`·`target_ops`·`markets` 생략을 계약으로 못 박는다. 종전 구현은 `running:false`·`target_ops:"0"`·`markets:[]`를 냈고, 봇이 초당 8.3건을 발행하는 동안 콘솔이 정지·활성 0대를 표시했다(실측 2026-09-06). 빈 배열도 0대라는 주장이라 미보고와 구분되지 않는다 |
| L3 | §6-A | `published`에 `observed_at`·`stale` 동반. 봇 노드가 죽어도 발행 행은 남으므로 신선도 없이는 죽은 상태를 현재로 읽는다. 판정은 DB 시계 하나로만 하고 임계는 발행 주기의 10배다 |
| L4 | §6-A | v2.22의 "봇 프로파일이 켜진 정상 배포에서 둘은 일치한다"를 "같은 JVM에 켜진 배포"로 좁힌다. 분리 배포를 비정상으로 읽히게 두면 실제 배포가 계약 밖에 놓인다 |

원천은 `sim_fleet_state`다(DB 설계 v2.39). V22가 관리에서 봇으로 가는 제어 방향만 이었고 이 표가 반대 방향을 잇는다.

문서 헤더 버전을 v2.43으로 순증.

## v2.44 패치 반영 사항

- **파괴 액션 승인을 1인 승인으로 바꾼다(ADR 0019).** 승인 티켓 발급 주체와 사용 주체가 같아도 된다. 종전 반결탁 제약 셋을 전부 뗀다. DB CHECK `chk_approval_two_person`, `ApprovalService.issue` 의 발급 가드, `ApprovalService.consume` 의 소각 가드다. 마이그레이션은 `V34__single_operator_approval.sql` 이다.
- **이 절이 앞선 서술을 갈아친다.** 이 문서에 남은 "2인 승인"·"2인 통제"·"반결탁" 서술은 전부 v2.43 까지의 판이고 v2.44 부터 효력이 없다. 해당 위치는 §3-6 approval_token 수명주기와 operator 신원 바인딩, §3-7 브라우저 관리 콘솔 세션, §6-A 엔드포인트 표, R3·S2 위험 표다. 규칙을 진술하는 두 자리(수명주기 첫 문장, 엔드포인트 표 행)는 본문에서 직접 고쳤다.
- **사유.** 정본 8.2 가 이미 "실제로 한 일은 야간 단독 운영 시간대에 정지 명령을 봉쇄한 것" 이라고 적어 두었다. 곧 이 제약이 한 일은 결탁 차단이 아니라 1인 운영 시간대의 정지 명령 봉쇄다. 운영 인원이 1인이라 두 인증서를 한 사람이 번갈아 제시하는 것은 반결탁의 외형일 뿐이었다.
- **남는 것.** 승인 티켓 별도 발급 단계, TTL 5분, 단회 소각, action·대상 바인딩, requester 바인딩, 역할 게이트, append-only 감사. 사라지는 것은 발급자와 사용자의 분리 한 겹뿐이고 그 대가는 정본 13.3 의 11 에 등재했다.
- **2인 경로는 남는다.** 다른 운영자가 발급한 승인도 그대로 통한다. 운영 인원이 늘면 규칙을 되돌리지 않고 2인 운용이 가능하다. S2 위험 항목은 그래서 폐기가 아니라 무효다.

## v2.45 패치 반영 사항

- **비동기 복제의 유실 창에 값을 붙인다.** 이 문서가 두 자리에서 "미측정" 으로 적던 것을 실측으로 바꾼다. §0-a 기술 스택 선택표 DB 자체 HA 행과 §7-1 보장 범위(v2.6) 단락이다. 근거는 `harness/evidence/failover-rpo-20260912/` 이고 스크립트는 `harness/scale/failover_rpo_check.py` 다.
- **값.** 복제가 따라오는 중 primary 급사는 ACK 88건 중 0건, 대기 서버를 8초 정지시킨 뒤 급사는 ACK 122건 중 36건이다. 유실 판정은 ACK 받은 행 중 승격한 노드에 없는 것을 센다.
- **정상 구간의 0을 무손실로 읽지 않는다.** 유실량은 그 순간의 복제 지연과 같다. RPO=0 을 주장하지 않는다는 §7-1 의 결론은 그대로다.
- **RTO 는 여전히 미측정이다.** 같은 회차가 잰 승격 명령 실행 1.697초와 1.886초는 명령 시간이고, 감지와 판단과 엔드포인트 전환이 그 안에 없다. DB 자동 페일오버가 0건이라는 서술도 그대로다.

## v2.46 패치 반영 사항

- **`event_seq` 를 전역 gapless 로 적던 두 자리를 DB 설계에 맞춘다.** §5-2 seq 도메인의 두 실현 (2) 와 trade 메시지 예시 아래 주석이다. V36 부터 `event_seq` 는 `market_id * 10^7 + market_seq` 대역 파생이라 전역 유일하되 전역 gapless 가 아니고, 결번 없는 축은 마켓 안 `market_seq` 다. 근거는 `docs/reference/contracts/백엔드_DB_설계.md` v2.44 의 `event_journal.event_seq` 열 정의와 `docs/reference/adr/0022-market-partitioned-command-journal.md` 개정이다.
- **클라이언트 계약은 바뀌지 않는다.** 채널 seq 는 여전히 sparse 단조 증가이고 연속성 판정은 strictly-increasing 이다. 처음부터 조밀 +1 을 요구하지 않던 규칙이라 결번 없는 축이 전역에서 마켓 안으로 옮겨도 판정이 같다.

## v2.47 패치 반영 사항

- **§6-C 불변식 ⑥ 재생 결정론의 판정 주체를 병합 판 코드 이름에 맞춘다.** `ReplayService.verifyTo` 를 `ReplayService.verifyToMarket` 으로 고친다. 2026-09-14 ADR 0022 2단계 병합으로 복구·팔로워·승격 경로가 마켓별 커서로 옮겨 가 운영 경로가 부르는 대조 전용 재생이 마켓 진입점이다(`ReplayService.java`:297). 전역 `verifyTo`(:114)는 시험만 부른다.
- **판정 축은 바뀌지 않는다.** 재생이 만든 이벤트를 `event_journal` 행과 하나씩 대조하고 대조 범위가 외부 id·executed_at·event_ordinal 까지라는 v2.33 정의가 그대로다. 대조 단위가 마켓마다 한 번으로 나뉠 뿐이다. 근거는 `docs/reference/contracts/백엔드_DB_설계.md` v2.45 와 `docs/reference/adr/0023-replay-bound-and-cursor-axis.md` 의 2026-09-14 주석이다.

## v2.48 패치 반영 사항

- **명령 저널 `seq` 를 전역 gapless 로 적던 다섯 자리를 축별로 고친다.** §4-B-1 주문 응답 예시 주석, 같은 절 id·시각·seq 결정론 단락, §6-C 불변식 7종 ①, §7-1 `command_journal`·`journal_counters` 두 행이다. ADR 0022 가 명령 `seq` 를 마켓 대역(`market_id * band_span + 지역 번호`)으로 채번하게 하면 전역 유일은 남고, 전역에서 결번이 없는 것은 레거시 구간(`seq < band_span`)뿐이다. 대역 구간의 결번 없는 축은 마켓 안이다.
- **① 의 판정 식이 같이 바뀐다.** 레거시 구간 전역 결번과 마켓 안 대역 결번의 합이 0 이다. 정본 SQL 은 `docs/reference/contracts/백엔드_DB_설계.md` v2.46 §6.3 ⑤, 판정은 `docs/reference/contracts/하네스_검증설계.md` §4-1 C05 다. 대역 채번은 ADR 0022 6단계 전환 뒤에 시작하므로 그 전에는 전 행이 레거시 구간이고 값이 종전 식과 같다.
- **약해지는 것.** 마켓마다 다른 지점에서 잘린 복원은 ① 을 통과한다. 그 축의 검사는 아직 없다(ADR 0022 9-16). ② 의 외부 증거 축은 `(user_id, client_order_id)` 라 이 변경과 무관하다.
- **클라이언트 계약은 바뀌지 않는다.** 프론트는 응답 `seq` 를 소비하지 않고 커서 축은 `(created_at, id)`·`trade_id` 다(ADR 0022 6-6). 같은 주석의 "§6-C `last_applied_seq` 와 동일 축" 문구는 §6-C 상태 응답을 마켓 벡터로 바꾸는 개정에서 함께 고친다.

## v2.49 패치 반영 사항

- **§2-4 멱등의 저장 계층 서술을 실물에 맞춘다.** 종전 판은 `command_journal` 의 `UNIQUE (user_id, client_order_id)` 부분 유니크 인덱스를 적었으나, 그 표는 `seq` 파티션이라 파티션 키가 없는 유니크를 걸 수 없다. DB 설계 §3.1 은 비유니크 `idx_cmd_client_new` 와 어드바이저리 락을 적는다. 같은 키 주문 생성 명령은 트랜잭션 어드바이저리 락으로 직렬화한 뒤 기존 명령을 `created_at, seq` 순으로 조회해 재전송을 판정하고, 물리 유니크는 `orders` 의 `uq_orders_client` 가 진다. 동시 in-flight 규약의 대기 주체와 보존 정책의 'UNIQUE 원장' 표현도 같이 고쳤다. 응답 계약(200 replay, 409 `duplicate_client_order_id`, 409 `order_in_progress`)은 바뀌지 않는다.
- **락 키에 마켓을 넣지 않는다.** ADR 0022 4단계가 접수 락을 마켓별로 가르면 같은 `(user_id, client_order_id)` 의 교차 마켓 동시 재전송을 직렬화하는 겹이 이 락 하나다. 키에 마켓을 넣으면 두 요청이 서로를 못 보고 둘 다 ACK 를 받는다.
- **503 `service_unavailable` 사유에 트랜잭션 시작 실패를 더한다.** 접수 락 분할로 append 전용 커넥션 풀 획득이 타임아웃하면 트랜잭션 시작이 실패하고, 이것도 재조회 계약(`Retry-After`)으로 수렴시킨다. 마켓 라이터 샤드가 멈춘 창에서는 그 마켓의 주문 접수만 이 코드로 거부된다. 근거는 `docs/reference/contracts/백엔드_DB_설계.md` v2.47 과 `docs/reference/adr/0022-market-partitioned-command-journal.md` 4-1~4-3 이다.
- **트랜잭션 시작 실패 매핑의 범위.** `CannotCreateTransactionException` 은 `TransactionTemplate` 을 쓰는 컨트롤러 경로 전체에서 503 `service_unavailable` + `Retry-After` 가 된다. 종전에는 범용 처리기의 `500 server_error` 였다.

## v2.50 패치 반영 사항

ADR 0022 6단계(대역 채번 커트오버)가 §6-C 상태 응답을 마켓 벡터로 바꾼다. 2026-09-14 갱신.

- **`/admin/v1/ha/status` 에 `command_watermarks[]{market_id,command_watermark,apply_lag}` 와 `apply_lag_total` 을 더한다.** `apply_lag` 는 마켓마다 명령 head 에서 물질화 경계를 뺀 값의 최대이고 `apply_lag_total` 은 그 합이다. 합만 내면 한 마켓의 정체가 묻힌다. 대역 채번 뒤 명령 seq 는 `market_id * band_span + 마켓 안 번호` 라 전역 스칼라끼리 빼면 대역 폭이 지연으로 읽힌다.
- **이 응답의 `schema_version` 을 1.1 로 올린다.** `apply_lag` 의 뜻이 바뀌어 §6-C 의 additive 규칙 예외이기 때문이다. 관리자 API 에는 응답별 1.1·1.3 판이 이미 있어, §2-2 표의 스키마 버전 행을 기본 1.0 과 관리자 응답별 판으로 고쳐 적는다.
- **스칼라 `command_watermark`·`event_watermark`·`applied_command_watermark`·`snapshot_seq` 는 남긴다.** 표시용 전역 최대다. 웹 관리 화면(`web/lib/admin/httpAdmin.ts`)은 `command_watermark` 만 읽고, 그 값이 이미 마켓별 최대의 최대라 웹 변경이 없다.
- **§4-B-1 주문 응답 예시 주석의 "§6-C last_applied_seq와 동일 축" 을 고친다.** v2.48 패치가 상태 응답을 마켓 벡터로 바꾸는 개정으로 미룬 문구다. 이제 `command_watermarks[]` 의 그 마켓 `command_watermark` 와 같은 축이다.

## v2.51 패치 반영 사항

ADR 0022 9단계(이벤트 대역 확대, V46)가 `event_seq` 의 대역 폭을 바꾼다. 2026-09-14 갱신.

- **§5-2 seq 도메인의 두 실현 (2) 의 파생식을 `market_id * band_span + market_seq` 로 고친다.** `band_span` 은 `journal_band_spec('event_seq')` 이고 V36~V45 는 10^7, V46 부터 10^12 다. 근거는 `docs/reference/contracts/백엔드_DB_설계.md` v2.50 §4.4 와 `docs/reference/adr/0022-market-partitioned-command-journal.md` 7절 9단계다.
- **클라이언트 계약은 바뀌지 않는다.** 한 마켓의 `event_seq` 는 확대 경계에서 10^7 대역 값에서 10^12 대역 값으로 뛰고 그 뒤로도 커진다. trade·candle 채널 `seq` 와 REST 스냅샷 `seq` 가 같은 값이라 strictly-increasing 판정과 기준점 규칙이 그대로다. 최댓값 64 x 10^12 는 JavaScript 안전 정수 2^53 안이라 JSON 숫자 `seq` 의 정밀도가 깨지지 않는다. `trade_id` 는 종전대로 10진 문자열이다.
- **확대 뒤 되돌리지 않는다.** 명세를 10^7 로 되돌리면 같은 마켓 `seq` 가 기준점 아래로 내려가 폐기 규칙이 채널을 멈춘다. ADR 0022 가 첫 대역 행 뒤 되돌리기를 금지하는 근거 하나다.

## v2.52 패치 반영 사항

주문 접수의 모호한 커밋과 같은 키 동시 요청의 해시 정리를 고친다(DB 설계 v2.51 V47). 2026-09-23 갱신.

- **§2-4 에 저널 append 가 예외로 끝난 접수의 키와 예약 규칙을 더한다.** 이 요청의 주문 id 로 커밋을 확인하면 정상 접수로 응답하고, 확인하지 못하면 종전처럼 해시를 지운다. 같은 내용의 다른 요청이 대조한 해시는 공유 표시로 남긴다. 해시 만료 구간의 대조 불가 `409` 규칙은 그대로이고, 이번 요청이 넣은 해시에 공유 표시가 있는 경우만 대조를 마친 것으로 본다. 공유 표시는 저널에 같은 키의 주문이 없음을 관측한 요청만 남기므로, 선점 경합에서 진 요청은 표시 전에 저널을 다시 본다. 공유 표시 조회가 실패하면 대조 불가로 거절한다.
- **응답 코드 집합은 바뀌지 않는다.** 커밋 응답을 잃은 접수가 종전의 `5xx` 대신 `201`(또는 `422`, `409 order_in_progress`)을 받을 수 있고, 해시를 잡는 경합이 끝나지 않으면 `409 order_in_progress` 가 난다. 둘 다 이미 정의된 코드다.
- **잠금 대기 초과(55P03)의 응답은 이 판에서 바꾸지 않는다.** Spring 기본 번역기가 55P03 을 분류하지 못해 게이트웨이 경로에서 `500 server_error` 로 남는다. `503` 으로 바꾸면 쓰기 업스트림이 `proxy_next_upstream ... http_503` 과 `max_fails=1` 로 그 응답 하나에 리더를 2초 빼는 배치(하네스 nginx, 개인 계정 edge 템플릿)가 있어, 매핑과 LB 설정을 함께 정해야 한다. 정산 쪽 55P03 은 재시도로 흡수한다(DB 설계 §5.4).

## v2.52.1 패치 반영 사항

관리 콘솔 MSP 관제센터의 2단계 서버 계약을 구현보다 먼저 적는다. 2026-09-24 갱신. 서버 코드는 아직 없다.

- **판 번호.** 브랜치 `claude/per-market-leader`가 이 문서의 v2.53~v2.54를 쓰고 있어 겹치지 않도록 v2.52.1로 둔다. DB 설계 v2.51.1과 같은 방식이다.
- **§6-F를 새로 둔다.** 관제센터의 조회(한 판, 호스트, 경보, 사건, 운영 작업, 임계값, AI 모드와 판단), 경보 확인·보류, 임계값 변경·되돌리기, AI 모드 켜기·즉시 끄기, AI 조치 요청의 승인·거절·나중에·되돌리기다. 칸 상태(`READY`·`NOT_CONNECTED`·`UNAVAILABLE`)와 측정 객체의 `sampled_at`은 미수집을 0이나 정상으로 바꾸지 않기 위한 규칙이다. 설계 이유와 정책 규칙 id는 `docs/reference/architecture/MSP_관제센터_2단계_설계.md`에 있다.
- **§3-6 승인 필수 열거에 다섯을 더한다.** 임계값 변경, 임계값 되돌리기, 등급이 `critical`·`other`이거나 30분을 넘는 경보 보류, AI 자동 모드 켜기, 조치 종류가 `THRESHOLD`인 AI 조치 요청의 승인·되돌리기다. 새 액션 `ops.threshold.set`(대상 `{key}:{value}`), `ops.alert.silence`(대상 `{fingerprint}:{minutes}`), `ops.ai.auto`(대상 `AUTO:{hours}`)는 값까지 바인딩해, 승인받은 것보다 둔한 경보, 긴 보류, 긴 자동 창으로 티켓을 다시 쓰지 못하게 한다. 발급 API(`POST /admin/v1/approvals`)는 액션 이름을 제한하지 않으므로 바뀌지 않는다.
- **§2-3에 오류 코드 여섯을 더한다.** `msp_source_not_connected`(409), `msp_upstream_failed`(502), `msp_policy_denied`(409), `ai_action_not_open`(409), `threshold_revert_conflict`(409), `alert_already_silenced`(409)다. 없는 대상은 기존 `404 not_found`에 `detail.reason`을 싣는다. 502는 이 문서에서 처음 쓰는 상태 코드이며 관리 평면의 원천 호출 실패에만 쓴다. 주문 경로의 `503` 재조회 계약과 섞지 않기 위해서다.
- **§6-A-a에 관계를 적는다.** 권고 임계의 원천을 설정에서 임계값 레지스트리로 옮기고 응답 모양은 유지한다. 용량 변경 경로는 관제센터의 조회 노드 조치가 다시 쓰고, 그 조치는 로컬 Docker(`read-capacity.mode=CAPACITY_PLAN`)에서만 허용된다.
- **AI 조치 권한은 ADR 0035(사용자 확정 2026-09-24)를 따른다.** 이 판 작성 시점에 ADR 파일은 다른 브랜치에서 작성 중이라 링크하지 않았다. 이 문서의 §6-A-a 권고 서술('판정은 자동이고 실행은 사람'), §3-6의 '관측 데이터의 지시 무시'(파라미터 변경·파괴 액션은 운영자 명시 요청으로만 수행), 하네스 CI·CD §5-6의 '알람에 자동 액션을 연결하지 않는다', ADR 0027의 'AI에 확장 대수 변경 권한을 주지 않는다', 관리자 설계 §6의 '임계 초과·경보 발생만으로 실행되지 않는다'와의 관계는 ADR 0035 병합 때 대체 관계로 정리한다.
- **설계 검토 반영(2026-09-25, 같은 판).** 구현 전 설계 검토 지적을 반영해 같은 판을 고쳤다. 경보 보류를 두 단계로 나눠 `critical`·`other` 등급이나 30분 넘는 보류에 SUPER와 새 액션 `ops.alert.silence`(대상 `{fingerprint}:{minutes}`)를 요구하고 §3-6 열거와 바인딩 표에 더했다. 티켓 없는 보류가 임계값 승인보다 약한 권한으로 경보를 둔하게 만드는 우회였기 때문이다. §3-6에 자동 모드의 포괄 승인과 '관측 데이터의 지시 무시'의 관제센터 예외를 적었다. 즉시 끄기는 공용 레이트리밋 버킷을 쓰지 않는다. 화면 폴링이 버킷을 비우면 킬 스위치가 429로 막혔다. 경보 `severity`는 정규화한 네 값이고 생존 신호 경보(`Watchdog`)는 목록과 사건에서 뺀다. 임계값은 소수 넷째 자리까지만 받고(`detail.constraint=scale`), T3은 모든 쓰기 경로에서 검사하며, 초기값은 되돌릴 수 없다. 항목에 `in_use`와 `rule_value`를 더했다. 자동 켜기는 평문 LLM 전송(M8)과 `UNKNOWN` 요청(M1)이 있으면 거부되고 창은 DB가 정한다. 조치 요청 상태에 `UNKNOWN`을 더하고, 나중에는 만료를 늘리지 않으며 자동 실행에서 영구히 뺀다. 모델 문장은 평문이며 링크 표기가 있으면 출력을 버린다. §6-A-a는 레지스트리 읽기를 MSP 스위치에 묶고 `capacity_plan`의 잃어버린 갱신을 적었다. 오류 코드는 새로 만들지 않았다. 지적별 판단은 설계 문서 13절에 있다.
- **구현 반영(2026-09-25, 같은 판).** 조회(한 판, 호스트, 경보, 사건, 운영 작업, 임계값과 변경 이력), 임계값 변경·되돌리기, 경보 확인·보류·보류 해제, 백업 즉시 실행 스텁을 구현했다. `/ai/*` 경로와 즉시 끄기의 레이트리밋 예외는 아직 없다. 구현하면서 계약에 없던 세부를 §6-F '세부 조회와 표시 규칙'에 정했다. 세부 조회의 응답 모양, 숫자 문자열의 자릿수, 원천 상태의 `reason`·`write_failures`, 경보 칸의 `last_poll_at`과 항목 필드, 조회 계층 칸의 `data`, 복제 칸의 `label_mismatch` 기준, 임계값 항목의 추가 필드와 `in_use` 표, 사건·변경 이력 조회의 필터와 커서, 확인·보류 해제 응답이다. 보류 해제는 먼저 사일런스를 조회해 이미 끝났거나 없으면 종료 호출을 하지 않고, 이벤트와 감사에 이미 끝났는지를 남긴다. §6-A-a의 용량 변경은 `capacity_plan`을 잠그고 읽도록 고쳐 겹친 두 변경 가운데 하나를 잃던 문제를 없앴고, 권고 임계는 관제센터를 켠 배포에서 레지스트리를 읽는다. 응답 모양은 바뀌지 않았다.

## v2.52.2 패치 반영 사항

관제센터 AI 판단·조치 경로의 계약을 구현보다 먼저 더하고 같은 날 구현했다. 2026-09-25 갱신. 판 번호는 v2.52.1과 같은 이유로 브랜치 `claude/per-market-leader`의 v2.53~v2.54와 겹치지 않게 v2.52.2로 둔다. 스키마는 DB 설계 v2.51.3 §10.4, 설계 이유는 `docs/reference/architecture/MSP_관제센터_2단계_설계.md` 7절이다.

- **사용자 결정(2026-09-25)을 옮겼다.** AI 즉시 정지를 두 모드에 쓰는 정지로 넓혀 `POST .../ai/stop`(VIEWER+, 공용 버킷 밖)과 `POST .../ai/resume`(SUPER, 사유 필수, 수동으로만)을 더했다. 정지하면 자동이 풀리고 대기 조치 요청이 만료되며 승인과 자동 실행이 막히지만, 관찰 창 안의 자동 되돌림은 계속한다. AI 한도 다섯 항목을 `GET·PUT .../ai/limits`로 두고 새 승인 액션 `ops.ai.limits`(대상 `{key}:{value}`)를 §3-6 열거와 바인딩 표에 더했다. 자동 조치 상한과 임계값 항목당 횟수는 하루가 아니라 자동 모드 1회 활성마다 센다. 자동 시간은 ADR 0035에 맞춰 1·2·4·8시간만 받는다.
- **로컬 실측(설계 7-13절)을 옮겼다.** 모델 출력은 이유(`reason_ko`)를 조치보다 먼저 쓰는 JSON이고 조치 이름은 방향과 대상이 드러나는 여덟 가지다. 정책 엔진에 결정적 방향 전제(D1~D3)를 더해 이유와 반대인 조치를 제안 때 `DENIED`로 막는다. AI의 경보 보류는 운영자가 등록한 계획 작업 창 안의 `warning` 경보로 좁히고(S6), 복제·DB·백업·디스크 계열은 창이 있어도 막는다(S7). 계획 작업 창 경로 셋을 더했다.
- **판단 기록의 필드 이름을 바꿨다.** 판단 상세의 `hypothesis`를 모델 출력에 맞춰 `reason`으로 바꾸고 `error`, `latency_ms`, `incident_id`와 조치 요청의 `verb`, `hold_reason`, `auto`, `observe_outcome`을 더했다. 조치 요청 상태에 `DENIED`, 버린 사유에 `DIRECTION_MISMATCH`를 더했다. 조치 요청 목록 `GET .../ai/actions`를 더했다.
- **한 판단의 조치는 하나다.** 종전 계약의 '조치 최대 3개'를 바꿨다. 자동 실행은 어차피 한 판단에 하나이고(A10), 작은 로컬 모델에는 단일 조치 스키마가 더 안정적이었다.
- **감사 코드를 더했다.** `ops.ai.stop`, `ops.ai.resume`, `ops.ai.limits`, `msp.window.create`, `msp.window.end`, `ops.ai.action.deny`, `ops.ai.mode.system`이다. 정책 거부는 `detail.reason_code`를 싣는다. 오류 코드는 새로 만들지 않았다.
- **AI 신원 거부.** `kind='AI'` 운영자는 세션 발급과 관리 필터에서 `403 admin_forbidden`(`detail.reason=ai_operator`)이고, 운영자 상태·역할 변경의 대상이 될 수 없다.
- **Teams 통지는 아직 없다.** 통지 사건을 발행하는 자리만 두었고, 보고 서비스의 카드·보관함으로 보낼지는 다음 구현 단계가 정한다.

## v2.52.3 패치 반영 사항

관제센터의 AI 상담, 요약 추세, 온프레미스 AI 서버 상태, 보고 서비스를 거치는 Teams 통지와 보고서 읽기, 원천 읽기의 성능 예산을 계약에 먼저 더하고 같은 날 구현했다. 2026-09-25 갱신. 판 번호는 v2.52.1과 같은 이유로 브랜치 `claude/per-market-leader`의 v2.53~v2.54와 겹치지 않게 v2.52.3으로 둔다. 스키마는 DB 설계 v2.51.4 §10.5, 설계 이유는 `docs/reference/architecture/MSP_관제센터_2단계_설계.md` 4-6·4-7·7-12·7-17·7-18절이다.

- **경로 다섯을 더했다.** `GET .../trends`(요약 추세와 이전 구간 대비 증감), `POST .../ai/chat`(AI 상담), `GET .../ai/server`(AI 서버 상태), `GET .../reports`(보고 목록 읽기 프록시), `GET .../reports/status`(Teams 전송 상태와 통지 보관함 요약)다. 모두 VIEWER+이고 승인 티켓이 없다. 상담은 읽기 전용이라 §3-6 열거에 들지 않는다.
- **한 판을 넓혔다.** `GET .../overview`가 24시간 추세(`trends`)와 AI 서버 상태(`ai_server`)를 싣고 `ETag`를 붙인다. `If-None-Match`가 같으면 `304`다. 태그는 `generated_at`, `age_seconds`, `last_poll_at`, `ai_server.server.checked_at`을 뺀 본문으로 계산한다. `sources`의 `teams`가 보고 서비스의 전송 상태로 `READY`·`UNAVAILABLE`·`NOT_CONNECTED`를 낸다.
- **원천 읽기의 성능 예산.** 원천 읽기는 이름으로 정한 키의 공유 캐시를 거치고, 같은 키의 동시 읽기는 원천 호출 한 번으로 모은다. 조회 계층 노드 관측도 캐시 수명 동안 공유한다. 요약 세 값과 요청률은 Prometheus 기록 규칙 이름만 질의한다. 이에 따라 `HTTP_5XX_RATIO`·`HTTP_P95_SECONDS`·`PROBE_SUCCESS_RATIO`·`HTTP_REQUEST_RATE` 명명 질의의 PromQL이 기록 규칙 이름으로 바뀌었고, 규칙 파일이 없는 Prometheus에서는 세 값이 `MISSING`이다.
- **Teams 통지는 보고 서비스가 보낸다.** 관리 JVM에 Teams 전송기를 따로 두지 않는다. 카드 모양과 전송 기록이 두 벌이 되지 않게 하기 위해서다. 관리 JVM은 통지 보관함에 쓰고 보고 서비스의 새 수신 경로 `POST /msp/events`로 넘긴다. 설계 3절의 `teams.webhook-url-file`·`console-url` 설정은 만들지 않았다. 웹훅 주소와 카드의 콘솔 링크는 보고 서비스 설정이 가진다.
- **감사 코드 `ops.ai.chat`을 더했다.** 다른 AI 감사와 달리 가린 질문과 답을 싣는다. 오류 코드는 새로 만들지 않았다. 상담의 속도·동시 실행 제한은 기존 `429 too_many_requests`에 `detail.scope`(`ai_chat`, `ai_chat_concurrency`)로 가른다.

## v2.52.4 패치 반영 사항

관제센터 AI 조치 경로의 구현 검토 지적을 계약에 먼저 반영하고 같은 날 코드를 맞췄다. 2026-09-25 갱신. 판 번호는 v2.52.1과 같은 이유로 브랜치 `claude/per-market-leader`의 v2.53~v2.54와 겹치지 않게 v2.52.4로 둔다. 경로, 필드, 오류 코드는 새로 만들지 않았다. 설계 이유는 `docs/reference/architecture/MSP_관제센터_2단계_설계.md` 7-6·7-7·7-8절과 14-4절이다.

- **실행 직전의 대상 재확인(C5).** 조치 요청의 승인과 자동 실행은 대상 행(용량 계획, 임계값 항목)을 잠근 뒤 실행 직전에 모은 근거의 값과 같은지 보고, 임계값은 요청의 `params.from`과도 같은지 본다. 다르면 `409 msp_policy_denied`(`detail.rule=C5`, `detail.reason_code=target_changed`)다. 종전 구현은 잠금 전에 모은 근거로 1회 폭(T2)과 둔하게 바꾸는 방향(D3)을 판정했고 사람의 같은 종류 조치 쿨다운(A8)도 대상 행을 잠그기 전에 읽어, 그 사이에 커밋된 사람의 변경을 덮어쓸 수 있었다.
- **임계값 조치 요청의 현재값 바인딩.** 같은 새 값을 내는 판단이라도 그 사이에 현재값이 바뀌었으면 열린 요청을 `WITHDRAWN`으로 닫고 새 요청을 만든다. 반대 방향의 두 판단이 한 요청의 연속 판단으로 세어지고, 요청의 올림·내림 표시가 실제 방향과 반대로 나가던 문제를 막는다. 되돌리기 티켓의 대상은 실행 때 바꾸기 전의 값이다. §3-6 바인딩 규칙('되돌릴 값으로 대상을 만든다')은 그대로다.
- **이미 처리된 요청의 승인.** 같은 요청을 두 번 승인하거나 만료된 요청을 승인하면 계약대로 `409 ai_action_not_open`이다. 종전 구현은 `409 msp_policy_denied`(`detail.rule=C3`)를 냈다.
- **봇 부하 쿨다운.** 시나리오 활성화(`sim.scenario`)도 발행률 설정(`sim.rate`)과 같은 관리 JVM 잠금 안에서 목표 OPS를 바꾸고 AI 봇 부하 줄임의 쿨다운(A8)을 시작시킨다(§6-A 시나리오 프리셋, §6-F AI 모드의 `cooldowns`).
- **관찰 창과 사람 되돌리기.** 관찰 창 안에서 사람이 먼저 되돌린 요청은 서버의 자동 되돌림이 뒤따라 닿아도 `REVERT_BLOCKED`로 적지 않고 관찰 실패 통지도 내지 않는다. 그 요청의 `observe_outcome`은 `null`이다.

## v2.52.5 패치 반영 사항

관제센터 AI 판단을 로컬 모델로 평가한 결과에 따라 계약과 코드를 같은 변경에서 고쳤다. 2026-09-25 갱신. 판 번호는 v2.52.1과 같은 이유로 브랜치 `claude/per-market-leader`의 v2.53~v2.54와 겹치지 않게 v2.52.5로 둔다. 경로, 필드, 오류 코드는 새로 만들지 않았다. 측정은 [측정 09-25](../../measurements/20260925.md), 설계 이유는 `docs/reference/architecture/MSP_관제센터_2단계_설계.md` 7-5·7-6·7-13절이다.

- **봇 부하의 방향 전제(D4).** 봇 부하 줄이기는 제안, 승인, 자동 실행 모두에서 bot-1 CPU가 봇 용량 보호 임계(`bot.capacity_guard`) 이상일 때만 받는다. 임계가 미설정이거나 bot-1 CPU를 모르면 `409 msp_policy_denied`(`detail.rule=D4`, `detail.reason_code=direction_conflict`)이고 제안은 `DENIED`로 남는다. 그 임계는 설계 5-1절이 정한 'AI 봇 부하 줄임 판단의 bot-1 CPU 기준'이다. 평가에서 경보 요약에 끼워 넣은 '봇 부하를 줄이라'는 문장을 모델이 따랐고, 그 조치가 종전 규칙(B1~B3)을 모두 지나 자동 실행 조건을 채웠다.
- **판단 기록의 프롬프트 판.** 시스템 프롬프트와 조치 설명, 근거의 조치 카탈로그를 고쳐 `prompt_version`이 `msp-ai/2`가 됐다. 판단 근거(`msp-evidence/1`)에는 경보 목록의 신선도 `alerts_collect`(`OK`, `STALE`, `MISSING`)를 더했고, `catalog`에는 방향 전제가 서는 조치와 대상만 싣는다. 필드를 더하기만 했으므로 스키마 이름은 그대로다.
- **상담 근거의 이름.** 상담 근거의 조회 계층 항목(`read_tier`)의 `label`을 '조회 계층 평균 CPU'로 바꿨다. 그 항목의 `value`가 평균 CPU 비율이라는 것을 모델과 인용 화면이 알 수 있게 하기 위해서다.

## v2.52.6 패치 반영 사항

관제센터 AI의 판단·상담 근거에서 외부나 사람이 쓴 자유 문장을 없애고, 자동 모드의 임계값 변경을 민감한 방향만으로 좁혔다. 2026-09-25 갱신. 판 번호는 v2.52.1과 같은 이유로 브랜치 `claude/per-market-leader`의 v2.53~v2.54와 겹치지 않게 v2.52.6으로 둔다. 경로와 오류 코드는 새로 만들지 않았다. 설계 이유는 `docs/reference/architecture/MSP_관제센터_2단계_설계.md` 5-1절(민감한 방향)·7-2절(신뢰 경계)·7-6절(S8, T6)·7-17절, 재평가는 [측정 09-25](../../measurements/20260925.md)다.

- **판단 근거 `msp-evidence/2`.** 경보 항목에서 요약(`summary`, 200자)을 빼고 `{fingerprint, alertname, severity, node?, role?, component?, mountpoint?, rejected?, starts_at, silenced, threshold, planned_window}`만 싣는다(§6-F 'AI 판단과 모델 출력'). Alertmanager 주석과 허용 밖 라벨은 싣지 않고, 경보명과 네 라벨 값은 형식과 낱말 검사를 지난 것만 싣는다. 버린 필드는 이름만 `rejected`에 싣는다. `threshold_key`는 레지스트리 키와 같을 때만 `threshold`(레지스트리 값과 서버가 잰 근거 지표)로 잇는다. `msp-evidence/1`은 요약과 라벨 값을 그대로 실었고, 로컬 평가에서 그 요약에 끼워 넣은 명령문을 모델이 따랐다. 필드를 빼고 모양을 바꿨으므로 스키마 이름을 올린다.
- **상담 근거 `msp-chat-evidence/2`.** 경보 항목에서 요약을 빼고 판단 근거와 같은 형식 검사를 건다. 사건 항목의 경보명도 거른다. 지문이 형식 밖인 경보의 id는 `alert.unknown-{n}`이다. 운영자의 질문과 대화는 근거 블록 밖에 둔다.
- **AI 보류의 S8.** 경보명이나 허용한 라벨 값, 지문을 형식 밖이라 근거에서 버린 경보는 계획 작업 창이 있어도 AI가 보류하지 않는다(`409 msp_policy_denied`, `detail.rule=S8`, `detail.reason_code=malformed_alert`, 제안은 `DENIED`). 거른 값으로는 관측 경로·DB 계열 판정(S5·S7)과 창의 라벨 일치를 온전히 볼 수 없기 때문이다. 카탈로그의 보류 대상에서도 뺀다. 사람의 보류는 바뀌지 않는다.
- **임계값 자동 실행의 비대칭(T6).** 자동 모드는 임계값을 그 항목의 민감한 방향으로 옮기는 변경만 실행한다. 둔하게 하는 변경과 방향을 정하지 않은 항목의 변경은 요청으로 남아(`hold_rule=T6`) 사람의 승인(`ops.threshold.set`)으로만 실행된다(§3-6, §6-F 'AI 모드'). 사용자 결정(2026-09-25)이고 제안·승인 경로의 방향 전제 D3는 그대로다. 로컬 평가에서 모델이 근거에 없는 이유로 증설 임계를 올린 조치가 D3를 지났다.
- **경보명·라벨 값의 낱말 수.** 형식 검사에 영문 낱말 수 상한(경보명 다섯, 라벨 값 셋)을 더하고, 끝의 `ONE`을 뗀 조치 이름도 낱말 검사에 넣었다. 낱말을 이어 붙인 식별자 모양의 문구를 막기 위해서다.
- **판단 기록의 프롬프트 판.** 시스템 규칙 3에서 근거에 없는 '경보 요약'을 빼고 '문장'을 '값'으로 고쳐 `prompt_version`이 `msp-ai/3`이 됐다. 경보 필드와 `rejected`를 설명하는 문장은 평가에서 증설 판단을 줄여 넣지 않았다. 상담 프롬프트는 `rejected`의 뜻을 적는다.
- **화면용 요약은 그대로다.** 경보 칸과 사건 조회의 `summary`는 사람이 읽는 화면용으로 남는다. AI 근거에 싣지 않을 뿐 응답 모양은 바뀌지 않는다.

## v2.52.7 패치 반영 사항

관제센터 화면(`claude/admin-design`)과 관제센터 서버(`claude/msp-control`)를 한 브랜치로 병합했다. 2026-09-25 갱신. 판 번호는 v2.52.1과 같은 이유로 v2.52.7로 둔다. 경로, 요청·응답 모양, 오류 코드는 바뀌지 않는다.

- **ADR 0035 연결.** 병합으로 ADR 파일이 같은 트리에 들어와 §3-6과 §6-F에서 링크한다. v2.52.1이 병합 때 정리하겠다고 적은 대체 관계 가운데 §3-6의 '관측 데이터의 지시 무시'는 이 판에서 ADR 0035의 대체 범위로 적었다. 하네스 CI·CD §5-6, ADR 0027, 관리자 설계 §6의 해당 문장도 같은 병합에서 고쳤다. §6-A-a의 '판정은 자동이고 실행은 사람'은 레거시 용량 권고 경로의 서술이라 그대로이고, 기획안 정본 7.5의 같은 문장은 정본 개정이 남아 있다.
- **ADR 0035에 반영한 서버 쪽 결정.** 방향 전제 D3와 T6의 비대칭, D4 유지, 근거 자유 문장 제거(S8), 통지 개입 등급(P1~P4), DGX Spark 모델 후보를 ADR 0035 본문('서버 구현에서 정한 것')에 적었다. 이 문서의 규칙은 바뀌지 않는다.
- **웹 http 어댑터 연결과 응답 스냅숏(같은 판, 병합 뒤 세 번째 변경).** 관리 콘솔의 http 데이터 원천을 이 절의 경로에 이었다(관리자 설계 4-4 http 동작). 경로와 모양은 바뀌지 않는다. 어댑터 시험의 픽스처로 쓰려고 서버 통합 시험이 실제 응답을 떠 보니(`MspContractSnapshotIntegrationTest`, 원천은 스텁) 이 절의 서술과 다른 곳이 있어 서술을 실제에 맞춘다. (1) 경계를 넘는 app-3 켜기·끄기의 승인은 `409 msp_policy_denied`(R1)이고 `CAPACITY_OUT_OF_BOUNDS`는 §6-A-a 용량 경로에서만 난다. (2) 십진 문자열은 끝 0을 지운다(`"0.6"`, `"0.35"`). 예시의 `"0.60"` 같은 표기는 설명용이고 화면은 수로 읽는다. (3) `GET .../ai/limits`의 `activation`도 `auto_cap`과 `threshold_used`(비었으면 `{}`)를 싣는다. (4) 계획 작업 창 목록도 `pagination`(`next_cursor` null, `has_more` false)을 싣는다. (5) 정지·연결 전 상담 응답은 `preface`, `evidence`, `model`, `latency_ms`가 `null`이다. (6) 조치 요청 처리 응답(승인·거절·나중에·되돌리기)은 `deferred_until`도 싣는다. (7) `GET .../reports/status`의 `outbox.counts`는 대문자 상태 키이고 비었으면 `{}`다. 오류 봉투는 §2-3과 같은 `{error: {name, message, detail, trace_id, request_id, retry_after}}`다.
- **app-3 모델과 합니다체(같은 판, 병합 뒤 두 번째 변경).** ADR 0030 사용자 정정(앱 계층은 app-1·app-2 고정과 조회 전용 app-3 자동 증설의 2~3대)에 맞춰 `READ_NODES` 조치의 말을 'app-3 켜기·끄기(앱 계층 2~3대)'로 고쳤다. 모델에 주는 조치 설명과 시스템 규칙 4·5가 바뀌어 판단 기록의 `prompt_version`이 `msp-ai/4`가 됐다. 출력 스키마와 조치 이름(`READ_NODE_ADD_ONE` 등), 응답 필드는 그대로다. 정책 거부 사유, 상담 근거 이름('앱 계층 조회 평균 CPU'), §6-A-a 용량 권고 문장과 경계 거부 사유를 합니다체로 고쳤다. 사유 문장은 화면에 그대로 나가는 서버 문구라 형식이 계약이 아니다. 로컬 모델 평가([측정 09-25](../../measurements/20260925.md))는 `msp-ai/3` 문구로 돌린 것이라 새 문구로 다시 재야 한다. §6-A-a의 `capacity_plan` 경계(V32 초기값 min 2·max 3)와 AI 조치의 R1이 같은 경계를 쓴다는 것을 서버 시험(`AppTierCapacityIntegrationTest`)으로 고정했다.

## v2.53 패치 반영 사항

ADR 0034 S1(단일 리더 안전 보강)의 나머지가 접수 대기 상한과 폐쇄 응답을 더한다. 2026-09-24 갱신.

- **§6-E `close`에 `409 orders_cancel_incomplete`를 더한다.** 취소가 `503`으로 저널에 닿지 못한 주문이 남으면 성공 `200`으로 덮지 않는다. 남은 주문이 없으면 응답은 종전과 같다. 정지 마켓의 미체결은 2026-09-23 규칙대로 `200`에 남긴다. ADR 0034 결정 15의 관리 경로 규칙("미완료면 409")을 단일 리더에 먼저 적용한 것이다.
- **서버 설정 `exchange.engine.accept-lock-wait`(기본 0, 끔)를 켜면 마켓 접수 락을 그 시간 안에 얻지 못한 주문·취소가 저널 전에 `503 service_unavailable` + `Retry-After: 1`로 끝난다.** 저널 행과 채번이 남지 않고 이 요청이 넣은 멱등 키는 §2-4의 저널 전 실패 규칙대로 정리되므로 같은 `client_order_id`로 다시 보내면 된다. 배치 주문(§4-B-6)은 그 원소를 `in_progress`로, 일괄 취소(§4-B-3)는 그 주문을 `failed[].reason=service_unavailable`로 적고 다음 원소로 넘어간다. 기본값이 0이라 켜지 않은 노드의 응답은 바뀌지 않는다. 켜면 `max_fails=1`인 쓰기 업스트림이 이 503 하나로 노드를 빼므로 값은 ADR 0034 S3·S4에서 인그레스 설정과 함께 정한다.
- **`exchange.engine.accept-lock-timeout`(기본 0, 끔)은 append 트랜잭션에 DB `lock_timeout`을 건다.** 초과는 트랜잭션 전체를 롤백해 저널 행과 채번을 남기지 않는다. 응답은 v2.52에서 정한 대로 아직 `500 server_error`다. 잠금 대기 초과(55P03)를 `503`으로 바꾸는 매핑은 이번에도 넣지 않았다.

## v2.54 패치 반영 사항

ADR 0034 S3(접수 시점 예약과 P2 전환)의 응답 계약을 코드보다 먼저 고친다. 설계는 `docs/reference/architecture/마켓분할_리더_S3_설계.md`, DB 쪽 계약은 `docs/reference/contracts/백엔드_DB_설계.md` v2.54 §3.5·§5.1이다. 2026-09-24 갱신.

- **§2-3 `400 insufficient_funds`에 P2 경로를 적는다.** 리더 모드 P2에서는 잔고 판정의 권위가 저널 append 트랜잭션 안의 DB 예약이다. 잔고가 모자라면 DB가 23514(`chk_balance_nonneg`)로 트랜잭션 전체를 롤백하고 게이트웨이가 이 코드로 끝낸다. 저널·채번·예약이 남지 않으므로 확정 미접수이고 같은 `client_order_id`로 다시 보내면 새 접수로 판정된다. 응답 코드 집합은 바뀌지 않는다. P1(오늘)의 응답은 그대로다.
- **§2-4 2단계와 동시 도착 규칙에 P2를 적는다.** P2의 게이트웨이 가드는 in-flight 누계 없이 가용 잔고만 보는 빠른 거부다. 같은 내용의 동시 재전송은 어드바이저리 락이 원본 커밋을 기다리게 한 뒤 재생으로 판정되고 재생은 예약하지 않으므로, P1에만 있던 "원본 커밋 전 창의 재전송이 `400`으로 끝나는 예외"가 P2에는 없다. append 예외 뒤의 해시 규칙(V47)은 같고, in-flight 예약의 유지·지연 회수는 P1에만 해당한다. 같은 날 설계 검토가 빠른 거부와 저널 확인이 원자적이지 않아 이 문장이 성립하지 않는 인터리빙을 찾았다(재전송이 저널을 본 뒤 잔고를 읽기 전에 원본이 커밋). 그래서 P2의 빠른 거부는 처음 읽을 때 저장된 해시가 없던 요청에만 걸고, 거부 직전에 해시를 다시 읽어 같은 내용의 해시가 생겼으면 거부하지 않게 고쳤다. 응답 코드 집합은 바뀌지 않는다.
- **§2-3 `503` 행에 잠금 대기 초과(55P03)의 매핑과 일시 DB 오류의 원소별 처리를 계약으로 적는다.** 55P03은 `503 service_unavailable` + `Retry-After`다. 주문 생성·취소 경로의 일시 DB 오류(55P03·57014·40P01·40001)는 배치 주문의 원소를 `in_progress`로, 일괄 취소의 주문을 `failed[].reason=service_unavailable`로 적고 다음 원소로 넘어가며 회원 폐쇄는 미완료로 센다. 적용은 ADR 0034 S4에서 쓰기 업스트림의 `max_fails`·`proxy_next_upstream`과 `accept-lock-wait`·`accept-lock-timeout` 값을 함께 정할 때다. 쓰기 업스트림이 `503` 하나로 노드를 빼는 배치를 그대로 두고 매핑만 켜면 한 마켓의 잠금 대기가 모든 마켓의 쓰기 중단으로 번지기 때문이다(v2.52). 그 전의 실제 응답은 종전대로 `500 server_error`다.
- **§6-E 회원 폐쇄에서 정지 마켓의 미체결도 미완료로 센다.** 정지 마켓 주문이 남으면 `200` 대신 `409 orders_cancel_incomplete`이고 에러 `detail`에 사유별 수 `pending_reasons`를 더한다(`service_unavailable`, `market_suspended`). ADR 0034 결정 15의 값이고, 2026-09-24 작업을 맡은 AI가 폐쇄를 완료로 덮지 않는 쪽을 골랐으며 사용자 확인이 필요하다. v2.53까지 이 경우는 `200`이었다. 공개 일괄 취소(`DELETE /api/v1/orders`, §4-B-3)의 응답은 바뀌지 않는다.
- **§4-E 출금의 잔고 부족은 계약대로 `422 insufficient_funds`다.** 계약은 바뀌지 않는다. 지금 코드(`TransferService`)는 출금 예약의 23514를 매핑하지 않아 `500 server_error`를 낸다(2026-09-24 코드 대조, 실행 확인 없음). S3는 접수 예약과 출금이 같은 잔고 행을 다투는 시험을 두므로 이 불일치를 S3 코드에서 계약대로 고친다.

## v2.55 패치 반영 사항

ADR 0034 S4(파티션 단위 Java)의 응답 계약을 코드보다 먼저 고친다. 설계는 `docs/reference/architecture/마켓분할_리더_S4_설계.md`, DB 쪽 계약은 `docs/reference/contracts/백엔드_DB_설계.md` v2.55 §3.8·§5.4-c다. nginx 동작 확인은 `harness/evidence/pml-s4-probe-20260924/`에 있다. 2026-09-24 갱신.

- **§2-5-b를 신설해 마켓 접수 경합을 `503`이 아닌 `429`·`409`로 적는다.** 접수 락 대기 상한 초과와 append 트랜잭션의 일시 DB 오류 넷(55P03·57014·40P01·40001)이다. 이 요청이 접수되지 않았음이 확정되면(취소, 그리고 멱등 키를 새로 넣은 주문 생성 요청의 V47 판정이 `DISCARDED`) `429 too_many_requests`(`detail.bucket=market`)이고 같은 `client_order_id`로 다시 보낸다. 확정할 수 없으면(같은 키의 다른 요청이 관여, V47 판정 실패, 이미 접수된 주문의 재전송) `409 order_in_progress` + `Retry-After`다. `429`의 확정은 이 요청에만 해당하고 앞서 결과를 모르게 된 같은 키 요청은 §7-6을 따른다. 배치 원소는 `in_progress`, 일괄 취소는 `failed[].reason=too_many_requests`, 회원 폐쇄는 미완료다. §2-3의 `429` 행과 §2-4의 append 예외 뒤 응답에 같은 규칙을 적었다.
- **v2.54 §2-3 `503` 행이 예고한 인그레스 조정을 하지 않는다.** 폐기용 nginx 1.29.8에서 리더의 `503` 한 건이 두 쓰기 업스트림 형태 모두에서 약 1초 넘게 모든 쓰기를 `502`로 만들었고, `429`는 재시도·퇴출이 없었다. 오픈소스 nginx에는 리더의 일시 `503`을 재시도하면서 노드 장애로 세지 않는 설정이 없다. 그래서 접수 경합은 `429`·`409`로 내고 `max_fails`·`proxy_next_upstream`은 그대로 둔다. 같은 이유로 주문 생성·취소 밖 경로의 55P03에는 v2.54의 `503` 매핑을 적용하지 않고 `500`으로 두며, ADR 0034 S5의 파티션별 업스트림에서 다시 정한다. `accept-lock-wait`(2000ms)와 `accept-lock-timeout`(1000ms)의 dev·prod 값은 S4 코드와 함께 켠다. ADR 0034 결정 8의 '예약 단계 lock_timeout 초과를 503으로 돌린다'와 결정 11·v2.54의 인그레스 조정 예고, 단계 계획 S4의 '55P03 503 매핑'을 바꾸는 선택이다. 2026-09-24 작업을 맡은 AI가 정했고 사용자 확인 대상이다.
- **§4-B-3, §6-E에 `too_many_requests` 사유를 더한다.** 일괄 취소의 원소 실패 사유와 회원 폐쇄의 `pending_reasons`에 새 사유가 들어간다. 폐쇄의 미완료 판정 규칙(ADR 0034 결정 15의 AI 선택)은 그대로다.
- **§5-2 `epoch`는 그 마켓 파티션 리스의 epoch다.** P1·P2에서는 값이 종전과 같다. P3의 WebSocket 마켓별 재동기화는 S6에서 정한다.
- **§6-C에 파티션 관측과 조작을 더한다.** `/admin/v1/ha/status`에 `leader_protocol`과 `leases[]`, `/admin/v1/ha/recovery`의 `failover_runs[]`에 `resource`를 additive로 더한다. `/actuator/partitions`(GET 상태, POST 참여 켜고 끄기·차단기 해제)를 등재한다. POST는 원격 주소가 loopback인 요청만 받는다. 선호 노드로 되돌리는 재배치(ADR 0034 결정 4)는 이 조작으로 한다.
- 같은 날 설계 검토 지적을 반영해 이 판을 고쳤다. 버전은 그대로 v2.55다. 첫 판은 V47이 이 요청의 명령이 저널에 없다고 답하면 모두 `429`였고 판정 실패와 재생은 `503`, 비접수 55P03도 `503`이었으며 `/actuator/partitions` POST를 내부 포트 운영에만 기댔다. 같은 키 동시 요청에서 `429`가 거짓 확정이 되는 경우, 리더를 쓰기 업스트림에서 빼는 `503` 경로, dev의 actuator 노출을 고쳤다(S4 설계 11절 R1·R4·R6·R7).

## v2.56 패치 반영 사항

ADR 0034 S4b(S4 관문 결함 보정)의 응답 계약을 코드보다 먼저 고친다. 설계는 `docs/reference/architecture/마켓분할_리더_S4b_설계.md`, DB 쪽 계약은 `docs/reference/contracts/백엔드_DB_설계.md` v2.56 §5.4-c·§5.5다. Spring·PostgreSQL·nginx 동작 확인은 `harness/evidence/pml-s4b-probe-20260925/`에 있다. 2026-09-25 갱신.

- **§2-5-c를 신설해 DB 연결 장애와 리더 자격 거부의 응답을 적는다.** S4 관문 재주행의 E2에서 DB 절단 중 주문 접수의 롤백 실패(`TransactionSystemException`), 인증 필터의 끊긴 연결(08006), DB 복귀 뒤 옛 리더 append의 fencing 거부가 `500`이었다. 앞의 둘은 v2.17 이래 §2-3 `503` 행의 '일시적 DB 접근 실패'와 어긋났고, 셋째는 S4 설계가 페일오버 창의 `503`으로 적은 경우였다. 주문 생성은 저널 확인(V47)이 연결 끊김으로 실패하면 한 번 더 부르고, 확인마저 DB에 닿지 못하면 `503 service_unavailable` + `Retry-After: 1`('DB 연결 장애. 재조회 후 판단 필요')이며 해시를 남긴다. 확인이 DB에 닿았는데 실패하면 `409 order_in_progress`, 확인이 저널에 없다고 하면 §2-5-b와 같은 규칙(해시를 지운 확정 미접수는 `429`, 그 밖은 `409 order_in_progress`), 이미 접수된 주문의 재전송이면 `409 order_in_progress`다. 리더 자격 거부(fencing 거부, 기간 종료)는 그 마켓의 게이트를 닫은 노드면 페일오버 창의 `503`, 게이트를 연 노드면 연결 장애와 같은 규칙이다. 취소는 대상 주문 재조회가 DB에 닿으면 `409`, 닿지 못하면 `503`이다. 인증 계층의 연결 장애는 `503`이고, 컨트롤러 계층은 안전 메서드와 재전송 안전 처리기(주문, 이체)만 `503`이다. 배치 주문·일괄 취소·회원 폐쇄의 원소별 처리와 조기 종료를 함께 적었다.
- **§2-3의 `429`·`500`·`503` 행과 §2-4의 V47 뒤 응답에 같은 규칙을 적는다.** 재전송이 안전하지 않은 쓰기의 연결 끊김과 자원 부족, 54xxx·58xxx처럼 연결이 아닌 자원 실패, 비접수 경로의 55P03은 종전처럼 `500`이다. 클라이언트가 먼저 연결을 끊어 응답을 쓸 수 없는 요청은 서버 오류로 렌더하지 않는다.
- **이 `503`은 쓰기 업스트림에서 리더를 뺀다.** 옛 리더만 DB에 닿지 못하는 E2 모양에서는 요청이 새 리더로 넘어가 클라이언트 응답이 좋아지고, 리더가 DB에 닿지 못하는 모양에서는 DB 복귀 뒤 최대 약 2.4초 `502`가 남으며, DB가 살아 있는데 끊긴 풀 연결 하나가 인증이나 컨트롤러 조회에 쓰이면 약 1.3~2.1초 동안 모든 쓰기가 `502`다(폐기용 nginx 1.29.8 확인). 인그레스는 바꾸지 않고 ADR 0034 S5에서 다시 본다. 결과를 확정할 수 없는 연결 장애의 `503`과 확정 미접수에 §2-5-b 규칙을 쓰는 것은 작업 지시가 정한 AI 선택이다. 그 구체화(V47이 DB에 닿았는지로 `503`과 `409`를 가름, 연결 장애의 `429`도 `bucket=market`, 게이트 상태로 리더 자격 거부를 가름, 컨트롤러의 재전송 안전 구분, 조기 종료)와 응답을 쓸 수 없는 요청의 처리는 2026-09-25 작업을 맡은 AI가 정했다. 모두 사용자 확인 대상이다.
- **§4-B-3, §4-B-6, §6-E에 원소별 처리를 적는다.** 일괄 취소는 그 주문을 `service_unavailable`(게이트를 연 리더 자격 거부는 `too_many_requests`)로 적고 계속하며 회원 폐쇄는 미완료로 센다. 배치는 그 원소를 `in_progress`로 적는다. 'DB에 닿지 못함' 뒤의 남은 원소는 시도하지 않는다.
- **§6-C를 고친다.** `/actuator/partitions`의 강등 기록 `detail`에 원인 사슬의 첫 SQLSTATE를 싣고, 참여를 끈 뒤에는 재구성 완료·차단기 해제와 엇갈려도 후보로 돌아오지 않는다고 적었다. S4 최종 검토가 남긴 참여 끄기 경합의 계약이다.

같은 날 설계 검토 지적 10건을 반영해 이 판을 고쳤다. 버전은 그대로 v2.56이다. 첫 판은 컨트롤러 계층의 연결 끊김을 메서드와 무관하게 `503`으로 내 커밋됐을 수 있는 멱등 키 없는 쓰기를 인그레스가 다른 노드에서 다시 실행하게 했고, V47 판정 실패를 모두 `503`으로 내 DB에 닿는 리더(잠금 대기 초과, 끊긴 풀 연결)를 뺐으며, 취소의 연결 장애를 늘 `503`으로 냈고, 재확인이 참인 fencing 거부에서도 `503`을 냈다. 각각 컨트롤러의 재전송 안전 구분, V47 결과의 도달 여부 구분과 한 번 더 부르기, 취소 재조회, 게이트 상태 구분으로 고쳤다. 대응 근거는 S4b 설계 11절이다.

같은 날 구현 검토 지적을 반영해 이 판을 다시 고쳤다. 버전은 그대로 v2.56이다. 이 판은 연결이 끊긴 저널 확인을 해시 삭제까지 같은 인자로 다시 부르게 적었다. 첫 확인이 이 요청의 해시를 지우고 커밋한 뒤 응답만 잃었고 그 사이 같은 내용의 다른 요청이 같은 해시를 새로 넣어 접수했으면, 다시 부른 확인이 그 요청의 해시를 지우고 이 요청에 확정 미접수 `429`를 낸다. 그 주문의 재전송과 이 요청의 같은 키 재전송은 대조 불가 `409 duplicate_client_order_id`로 끝나 새 키로 다시 내는 이중 주문 경로가 된다. 다시 부르는 확인은 해시를 지우지 않고, 저널에 없다고 답하면 확정 미접수가 아닌 `409 order_in_progress`로 고쳤다(§2-4, §2-5-b, §2-5-c). 대응 근거는 S4b 설계 14절이다.

## v2.57 패치 반영 사항

ADR 0034 S5(REST 라우팅과 요청 경계)의 응답 계약을 코드보다 먼저 고친다. 설계는 `docs/reference/architecture/마켓분할_리더_S5_설계.md`, DB 쪽 계약은 `docs/reference/contracts/백엔드_DB_설계.md` v2.57 §3.9·§5.4-c 8·§5.4-d, 인그레스와 실험 계약은 `docs/reference/contracts/하네스_검증설계.md`의 S5 패치 노트다. nginx·PostgreSQL 동작 확인은 `harness/evidence/pml-s5-probe-20260925/`에 있다. 2026-09-25 갱신.

- **§2-5-d를 신설해 마켓 힌트 헤더와 소유권 선검사를 적는다.** 주문 쓰기는 서명 밖 헤더 `X-Exchange-Market`에 마켓을 싣고, 노드는 그 마켓 파티션의 접수 게이트가 닫혀 있으면 바디 상한 직후, 수용 제한·IP 버킷·인증보다 먼저 `503`(`route=not_owner`, `Retry-After: 1`, 응답 헤더 `X-Exchange-Route: not-owner`)으로 돌려보낸다. 이 거절은 nonce·레이트리밋·노드 용량 슬롯·멱등 키·예약·채번·좀비 판정 수요를 건드리지 않고 DB에 가지 않는다. 힌트가 요청이 가리키는 마켓과 다르면 `400 invalid_parameter`(`market_hint_mismatch`), 헤더가 둘 이상이면 `market_hint_duplicate`다. 필터 순서를 바디 상한 4, 선검사 5, 수용 제한 6, IP 버킷 7, 인증 10으로 적었다. §2-3의 `400`·`503` 행, §3-3의 서명 밖 헤더, §4-B-1·§4-B-2의 힌트 규칙이 같은 내용이다.
- **§2-3 `503` 행과 §2-5-d에 'DB가 흔들린 직후'의 한 번 더 읽기를 적는다.** 인증 필터의 키 조회와 주문 생성·취소의 접수 전 읽기가 연결 끊김으로 끝나면 다른 연결로 한 번 더 읽고 그마저 DB에 닿지 못할 때만 `503`이다. S4b가 남긴 비용(끊긴 풀 연결 하나가 인증이나 접수 전 조회에 쓰이면 약 1.3~2.1초 동안 모든 쓰기가 `502`)을 줄이려는 것이다. 쓰기 문장은 다시 하지 않는다.
- **§4-B-6에 한 배치 한 마켓 규칙을 둔다.** 원소의 마켓이 둘 이상이면 `400 invalid_parameter`(`batch_single_market`)이고 레이트리밋 차감 전에 거부한다. 예시를 한 마켓으로 바꿨다. 저장소의 웹·봇·하네스 부하 도구는 배치를 쓰지 않는다.
- **§4-B-3·§6-E에 파티션별 처리와 인계를 적는다(ADR 0034 결정 15).** 마켓 없는 일괄 취소와 회원 폐쇄는 요청 시점의 미체결 주문을 파티션별로 나눠, 이 노드가 게이트를 연 파티션은 직접 취소하고 그 밖은 DB 인계 표로 소유 노드에 넘긴 뒤 최대 5초 기다린다. 한 파티션이 기다림 안에 끝내지 못하면 그 주문은 `partition_not_started`(시작하지 않음, 포기로 표시해 아무것도 하지 않음. 첫 판 이름은 `partition_unavailable`)나 `partition_pending`(시작했으나 끝나지 않음, 곧 취소될 수 있음)이고 회원 폐쇄는 둘 다 미완료로 센다. 엔진을 끈 관리 평면에서 폐쇄의 취소가 모두 저널 전에 거부되던 결함(모드와 무관, v2.56까지)도 이 인계로 고친다.
- **§2-5와 §3-4에 노드 로컬 한도를 다시 적는다(ADR 0034 결정 14).** nonce와 계정·IP 버킷은 노드 메모리이고 한도 값은 그대로다. 두 노드가 파티션을 하나씩 쥔 P3에서는 두 파티션에 걸쳐 주문하는 계정의 실효 주문 한도가 계약값의 최대 두 배다. 인계는 소유 노드의 계정 버킷을 쓰지 않는다. v2.7 각주의 'Redis 교체 선행 조건'은 이 구성에 적용하지 않고, 노드를 넘는 재전송 창과 요청 멱등이 그 창을 막는 범위를 적었다.
- **사용자 확인 대상.** 한 파티션이 기다림 안에 끝내지 못했을 때의 두 사유와 5초 기다림, 인계 행의 5분 유효 시간, 한 배치 한 마켓, 노드별 레이트리밋과 노드 로컬 nonce의 유지, 파티션 업스트림 1차의 `max_fails=2`, 힌트 없는 단건 취소의 게이트웨이 `503`, 접수 전 읽기의 한 번 더 읽기는 2026-09-25 작업을 맡은 AI가 정했다. 결정 14·15의 기본값을 구체화한 것이며 모두 사용자 확인 대상이다.

같은 날 설계 검토 지적을 반영해 이 판을 다시 고쳤다. 버전은 그대로 v2.57이다. 근거는 S5 설계 11절이다.

- **§2-5-d 선검사가 서명된 값을 먼저 본다.** 주문 생성 바디의 `market`, 배치에서 `market`이 문자열인 첫 원소, 일괄 취소의 `market` 쿼리를 먼저 보고, 없거나 카탈로그에 없을 때만 힌트를 본다. 첫 판은 힌트를 먼저 보고 배치의 첫 원소만 봐, 첫 원소에 `market`이 없는 배치나 카탈로그에 없는 힌트(소문자 등)를 실은 요청이 소유하지 않는 노드에서 nonce·계정 토큰을 쓰고, 그 배치는 원소마다 멱등 키 선점과 채번을 거친 뒤 `200`으로 끝났다.
- **§2-5-d 관측을 나눴다.** `exchange_routing_precheck_total`은 선검사 필터의 결과만 세고, 게이트웨이가 힌트 없는 단건 취소에 낸 소유 아님 `503`은 `exchange_routing_cancel_not_owner_total`로 센다.
- **§3-4에 마켓 없는 일괄 취소의 노드 간 재전송을 바로 적었다.** 첫 판은 재전송 창이 종전과 같다고 적었지만, 이 판의 인계 때문에 다른 노드로 다시 보낸 서명 요청이 새로 효과를 낸다. 인그레스의 힌트 없는 경로가 1차 고정이라 공개 진입을 지나는 재전송은 대개 같은 노드의 nonce 저장소가 막고, DB nonce 등록은 하지 않는다(사용자 확인 대상).
- **§4-B-3·§6-E의 인계 사유를 고쳤다.** 포기한 몫의 사유 이름을 `partition_unavailable`에서 원인과 무관한 `partition_not_started`로 바꿨다. 소유 노드가 이미 시작한 요청의 뒷부분은 포기하지 않고 `partition_pending`으로 알린다(첫 판은 큰 인계의 뒷부분과 다른 회원의 인계를, 소유 노드가 멀쩡한데도 '가져가지 않음'으로 알렸다). 소유 노드의 예기치 않은 오류는 원소 사유 `server_error`로 적고 폐쇄는 미완료로 센다. 소유 노드 결과의 사유별 수를 폐쇄의 `pending_reasons`에 합친다는 것과, 가져간 몫도 5분 안에 끝나지 않으면 만료로 닫힌다는 것, 약 1만 2천 건을 넘는 인계의 한계를 적었다. 마켓을 준 일괄 취소도 선검사가 모르는 마켓이면 넘긴다는 것을 §4-B-3에 적어 DB 설계 §5.4-d 1과 맞췄다.

같은 날 S5 코드와 대조해 두 곳을 더 적었다. 버전은 그대로 v2.57이다. 코드가 먼저 이렇게 동작했고 계약을 코드 단계 안에서 맞췄다. 근거는 S5 설계 12절이다.

- **§2-3 `400` 행과 §2-5-d의 `market_hint_duplicate`에 쉼표로 이은 한 줄을 넣었다.** HTTP는 같은 이름의 필드 여러 줄을 쉼표로 이은 한 줄과 같은 뜻으로 본다. 두 줄이 한 줄로 합쳐져 온 요청을 한 마켓으로 읽지 않으려는 것이다. 마켓 표기에는 쉼표가 없다.
- **§2-5-d 선검사가 바디를 끝까지 읽는다.** 한 객체에 `market`이 여럿이면 마지막 값을 쓴다. 컨트롤러의 JSON 해석이 마지막 값을 쓰기 때문이다. 값을 찾으면 멈추는 첫 설계대로면 `market`을 두 번 적은 바디가 앞 값의 소유 노드에서 선검사를 지나 뒤 값의 마켓으로 접수 경로(인증, 멱등 키 선점, 채번)에 들어갈 수 있었다. JSON이 끝까지 온전하지 않으면 찾지 못한 것으로 넘기고 컨트롤러가 종전처럼 `400`을 낸다.

같은 날 S5 문서 단계에서 표지 하나를 고쳤다. 버전은 그대로 v2.57이다. 응답 규칙은 바꾸지 않았다.

- **§2-5-c의 DB 연결 장애 `503` 규칙은 사용자 확정(2026-09-25)이다.** v2.56이 AI 선택으로 적은 규칙(`500` 대신 `503` + `Retry-After` + '재조회 후 판단 필요', 멱등 키와 예약은 V47 규칙대로 남김)을 사용자가 확정해 §2-5-c 머리 문단과 '인그레스 영향' 항목의 표지를 바꿨다. 인그레스 비용을 줄이는 방법은 `docs/reference/architecture/마켓분할_리더_S5_설계.md` 4-6과 12-7에 있다.

## v2.58 패치 반영 사항

ADR 0034 S6(WebSocket 외부 브로커 중계)의 계약을 코드보다 먼저 고친다. 설계는 `docs/reference/architecture/마켓분할_리더_S6_설계.md`, DB 쪽 계약은 `docs/reference/contracts/백엔드_DB_설계.md` v2.58 §4.1이다. 브로커·nginx 동작 확인과 Spring 바이트코드 확인은 `harness/evidence/pml-s6-probe-20260925/`에 있다. 새 마이그레이션은 없다. 2026-09-25 갱신.

- **§5-1에 채택한 배선을 적는다.** 노드 단위 중계다. 각 노드의 내장 SimpleBroker가 자기 세션을 맡고 노드 사이는 외부 STOMP 브로커(RabbitMQ 4.3)의 토픽 하나로 게시를 나눈다. 종전 (A)안(세션 단위 `enableStompBrokerRelay`)과 (B)안(Redis 릴레이)은 채택하지 않았다. 폐기용 컨테이너 확인에서 세션 단위 중계는 브로커 메모리가 세션당 약 0.65MiB로 늘었고 t3.small 흉내 설정에서 200 세션에 메모리 경보가 났으며, Spring 6.2.19는 브로커가 없을 때 앱 게시에 예외를 던지고 모든 클라이언트 세션에 `ERROR`를 보낸다. 노드 단위는 연결이 노드 수의 두 배로 고정되고 브로커가 80~125MiB에 머물렀다. 브로커 장애 때 REST는 그대로이고, 모든 마켓을 쥐지 않은 노드는 중계가 5초 넘게 끊기면 WS를 닫고 거절한다(§5-6). §0-2 그림과 §1 실시간 푸시 행도 맞췄다.
- **§5-2에 마켓별 epoch 비교를 적는다.** 파티션마다 epoch가 다르므로 클라이언트는 마켓마다 기억하고, 작은 epoch는 버리고, 큰 epoch는 그 마켓만 재동기화한다. 작은 epoch만 10초 이어지면 그 마켓의 기억을 지운다(DB 타임라인 변경 대비). 비공개 채널의 seq 도메인은 중계 모드에서만 user × market이고 `seq_domain` 필드로 가른다. myAsset은 `version`으로 새 값을 가리고 비교할 수 없으면 계좌를 다시 조회한다. `seq: 0` 스냅샷은 기준점이 아니다.
- **§4-A-2·§4-A-4에 호가·티커의 seq·epoch를 내는 노드를 적는다.** 그 마켓을 쥔 노드는 자기 카운터, 쥐지 않은 노드는 중계 미러나 DB 집계와 `seq: 0`이다. 종전에는 쥐지 않은 노드가 자기 카운터와, 강등된 노드면 옛 엔진 호가를 냈다. 티커 원소의 `epoch`는 서버가 이미 내던 값을 문서에 올린 것이다.
- **§4-C-1·§5-5에 잔고 판 `version`과 myAsset `market`을 더한다.** 두 잔고 행 `balances.version`의 합이다. additive다.
- **§5-6에 핸드셰이크 거절을 적는다.** 중계 불능 노드와 P3의 `simple` 모드 노드는 업그레이드 전에 `503`으로 거절하고, 열린 세션은 1013으로 닫는다.

중계 모양, 브로커 제품, 비공개 도메인의 모드 한정, 비소유 노드의 `seq: 0`, 중계 불능 거절 규칙, 10초 감시는 2026-09-25 작업을 맡은 AI가 정했고 사용자 확인 대상이다(S6 설계 10절). 문서 헤더 버전을 v2.58 으로 순증.

같은 날 설계 검토 지적 14건을 반영해 이 판을 고쳤다. 버전은 그대로 v2.58이다. 첫 판은 비소유 노드의 미러 사용과 중계 불능을 그 노드의 구독 연결로만 판정했다. 브로커 메모리 경보는 발행 연결만 막고 구독 연결은 살려 두므로(`harness/evidence/pml-s6-probe-20260925/` R11a), 그 판은 멈춘 미러를 `seq`≠0 기준점으로 계속 냈고 WS 세션을 조용히 멈춘 채 두었다. 다시 붙은 뒤에는 끊긴 동안 잃은 게시를 모른 채 끊기기 전 미러를 곧바로 썼다. 그래서 소유 노드의 1초 하트비트와 그것으로 판정하는 미러 조건(§4-A-2)과 중계 불능 조건(§5-1)을 더했다. `simple` 모드의 두 노드 배치에서 팔로워 세션이 실시간을 받지 못하는 것도 막지 않았으므로 WS 수용 조건을 두 모드에 같게 두었다(§5-6). 비소유 노드의 티커는 게시 본문을 그대로 내면 24시간 값이 옛 값이 되므로 번호만 미러에서 가져온다(§4-A-4). myAsset의 같은 version을 무시하면 원가 재구축 뒤 원가 갱신을 잃으므로 같은 version도 적용한다(§5-2). 구독 상한은 폐기용 확인(R9·R10) 뒤 건수 5,000과 바이트 4MiB로 좁혔고, 발행 연결은 쓰기 시간 초과 때 SO_LINGER 0으로 끊는다(R11a·R11b, §5-1). 대응 근거는 S6 설계 11절이다. 하트비트 판정 문턱(5초에 3개, 리스 TTL + 3초), `simple` 모드의 거절 조건, 구독 상한 값은 2026-09-25 작업을 맡은 AI가 정했고 사용자 확인 대상이다.

같은 날 S6 폐기용 스택 관문의 브로커 멈춤(W3)·메모리 경보(W4) 실험에서, 팔로워가 발행이 멈춘 지 1~2초 뒤 리더보다 뒤처진 호가를 `seq`≠0으로 냈다(`harness/evidence/pml-S6-20260925-1954/`). 5초에 하트비트 3개라는 닿음 조건은 발행이 조용히 멈춘 뒤 약 3초 더 참이어서, 그동안 멈춘 미러가 기준점으로 나갔다. 그래서 §4-A-2의 미러 사용 조건에 '가장 최근 하트비트가 400ms 안'을 더하고, §5-1의 하트비트 간격을 1초에서 200ms로 줄였다. 중계 불능 판정(§5-6)의 닿음 조건은 그대로다. 버전은 그대로 v2.58이다. 두 값은 작업을 맡은 AI가 정했고 사용자 확인 대상이다.

같은 날 S6 브랜치(`claude/pml-s6`)를 S5 위에 병합하며 이 판의 번호를 v2.57에서 v2.58로 옮겼다. 두 브랜치가 같은 v2.56을 기준으로 v2.57을 매겼고, 먼저 들어간 S5가 v2.57을 쓴다. 내용은 바꾸지 않았다. S5가 고친 절(§2-3·§2-5·§2-5-c 표지·§2-5-d·§3-3·§3-4·§4-B-1·§4-B-2·§4-B-3·§4-B-6·§6-E)과 이 판이 고친 절은 겹치지 않는다. §2-5-d의 소유권 선검사는 주문 쓰기(`POST /api/v1/orders`, `POST /api/v1/orders/batch`, `DELETE /api/v1/orders`와 그 아래 경로)에만 걸리고, §4-A-2·§4-A-4의 호가·티커 조회와 §5의 `/ws/v1`에는 걸리지 않는다(`MarketRoutingFilter.target`, 필터 등록은 `/api/v1/*`). 그래서 마켓을 쥐지 않은 노드도 선검사 `503` 없이 §4-A-2의 규칙대로 미러나 DB 집계로 호가·티커에 답하고, WS 수용은 §5-6의 게이트만 가른다.

## v2.59 패치 반영 사항

ADR 0034 S7(P3 전환과 파티션 실험)의 계약을 코드보다 먼저 고친다. 설계는 `docs/reference/architecture/마켓분할_리더_S7_설계.md`, DB 쪽 계약은 `docs/reference/contracts/백엔드_DB_설계.md` v2.59 §3.10(V55)·§5.4-e·§6.4, 판정 계약은 `docs/reference/contracts/하네스_검증설계.md`의 S7 패치 노트다. 요청·응답 모양은 바뀌지 않는다. 2026-09-25 갱신.

- **§0-2 설계 결정 ③에 P3의 활성-대기 단위를 적는다.** 모드 P3에서는 파티션마다 리스 행이 있고 두 앱 노드가 하나씩 쥔다. 한 노드가 죽으면 다른 노드가 리스 만료와 비선호 유예 뒤 넘겨받는다. 모드 전환은 DB 소유자의 정지 창 절차이고 앱 설정이 정하지 않는다.
- **§6-C 불변식 ⑦에 P3 판정을 적는다.** P3에서는 fencing 무결을 하네스 C22'와 C30이 함께 본다. C22'는 시간 축 팔을 P3 구간에서 파티션별로 보고, 모드 전환 뒤 시작한 트랜잭션이 옛 구간 epoch로 `command_journal`·`snapshots`에 쓴 행을 센다. 두 파티션 리더가 서로 다른 epoch로 동시에 쓰면 종전 판의 전역 시간 축 팔이 split-brain 없이 위반을 내기 때문이다. 파티션을 넘는 쓰기는 C30이 센다. 같은 날 설계 검토 대응으로 사후 감사의 범위를 좁혀 적고, 사후 감사가 세지 못하는 전환 경계(전환을 가로지른 트랜잭션, 옛 구간 epoch의 이벤트·체결 쓰기)를 T4-2 전환 판과 실험의 구간 경계 증분에 맡겼다.
- **§6-C `/admin/v1/ha/status`의 P3 읽기를 적는다.** P3에서 `lease`와 `epoch`는 `matching-p0` 행이라 두 파티션을 대표하지 않는다. 클라이언트는 `leases[]`와 노드의 `GET /actuator/partitions`를 읽는다. 필드는 그대로다.

C22'의 구간 경계 팔과 C30의 범위(획득 이력 없음과 획득이 아닌 epoch 포함)는 2026-09-25 작업을 맡은 AI가 정했고 사용자 확인 대상이다. 문서 헤더 버전을 v2.59 로 순증.

2026-09-26 S6 관문 기록(`harness/evidence/pml-S6-20260925-1954/` 결번 관찰)이 권고한 대로 §5-2 구독 즉시 계약에 '스냅샷을 읽는 시점'을 더한다. 그 주행의 호가 실시간 315,176건 가운데 결번 1건은 브로커 멈춤으로 다시 붙은 관찰자가 구독 즉시 스냅샷으로 `seq` 831을 받고 다음 realtime으로 833을 받은 것이다. 832는 같은 시각 다른 관찰자가 받았다. 서버는 STOMP `SUBSCRIBE`를 수신 채널(스레드 풀)에 넘긴 직후 나오는 구독 사건에서 스냅샷을 읽었고, 내장 브로커의 구독 등록은 그 풀의 다른 스레드에서 끝났다. 스냅샷을 읽은 뒤 등록 전에 게시된 realtime은 그 구독에 가지 않았다. 이제 스냅샷은 내장 브로커가 그 `SUBSCRIBE`를 처리한 뒤(수신 채널의 `afterMessageHandled`)에 읽고, 중계 수신은 미러 갱신을 자기 세션 전달보다 먼저 한다. 요청·응답 모양과 클라이언트 규칙은 바뀌지 않아 버전은 그대로 v2.59다. 스냅샷 전송 시점을 옮긴 방식은 작업을 맡은 AI가 정했고 사용자 확인 대상이다.

## v2.60 패치 반영 사항

Patroni 브랜치(`claude/patroni`, ADR 0036)가 v2.57로 적은 판이다. 같은 v2.56을 기준으로 마켓별 리더 S5·S6·S7이 v2.57~v2.59를 먼저 main에 넣어, 2026-09-26 병합하며 이 판을 v2.60으로, 절 번호 §2-5-d를 §2-5-e로 옮겼다. DB 설계의 짝 절은 §5.4-d에서 §5.4-f로 옮겼다. 내용은 바꾸지 않았다.

ADR 0036(DB 자동 전환을 Patroni 엄격 동기 2노드로)의 응답 계약을 코드보다 먼저 적는다. 사용자가 2026-09-24~25 Patroni, DB 두 대, etcd 세 표, HAProxy, 엄격 동기와 Standby 부재 시 쓰기 정지, 거래 화면의 '거래 일시 중단'을 정했다. 설계는 `docs/reference/architecture/DB_자동전환_Patroni_설계.md`, DB 쪽 계약은 `docs/reference/contracts/백엔드_DB_설계.md` v2.60 §5.4-f, PostgreSQL 동작 확인은 `harness/evidence/patroni-sync-probe-20260925/`다. 2026-09-25 갱신.

- **§2-5-e를 신설한다.** 쓰기 가능 탐지가 SUSPENDED(DB에는 닿지만 커밋이 끝나지 않음)면 `/api/v1/**`와 관리 평면의 변경 요청을 DB에 닿기 전에 `423 trading_suspended`로, UNREACHABLE(DB에 연결하지 못함)면 `503`으로 거절한다. 조회는 계속 받는다. 중단을 넘는 주문·취소·출금은 세대 번호로 막고, 중단 동안과 재개 뒤 10초의 by-client 재조회는 저장된 멱등 키가 있는데 저널에 없으면 `409 order_in_progress`다.
- **§2-3에 `423 trading_suspended` 행을 더하고 `503` 행에 탐지기의 UNREACHABLE 거절을 적는다.** `503`이 아닌 이유는 쓰기 업스트림의 퇴출(§2-5-b·§2-5-c)이다.
- **§4-A-7을 신설한다.** `GET /api/v1/status`는 노드 메모리의 상태만 읽는 무인증 조회이고 `trading_suspended`, `trading_state`, `reason`, `since`, `checked_at`, `poll_after_seconds`를 낸다.
- **§0-2, §1 DB 자체 HA 행, §4-B-4 (a), §4-F, §7-6을 고친다.** Patroni 채택과 구현 전 상태, 중단 동안의 재조회 규칙, 배너 우선순위(연결 끊김, 거래 일시 중단, 공지), Patroni 구성의 ACK 보장 범위(단일 노드 장애, E-P1·E-P3·E-P5 PASS 뒤에만 주장, 이중 장애는 범위 밖)를 적었다.

`423`과 거절 범위, 탐지 임계(2초 중단, 1초 두 번 재개), 재조회 유예 10초는 2026-09-25 설계를 맡은 AI가 정했고 사용자 확인 대상이다(설계 13절). 문서 헤더 버전을 v2.60 로 순증.

같은 날 설계 검토 지적을 반영해 이 판을 고쳤다. 버전은 그대로 v2.60이다. 대응 근거는 Patroni 설계 14절이다. (1) `423`의 뜻을 '확정 미접수'에서 '이 시도는 이 노드에서 쓰지 않음'으로 좁혔다. 인그레스가 10초 뒤 다시 보낸 시도의 `423`이면 앞선 시도(기본 풀의 출금, 앞선 대기가 길어 10초를 넘긴 접수)가 재개 뒤 커밋될 수 있다. 클라이언트는 재개 뒤 같은 키로 다시 내거나 재조회하고 새 키로 다시 내지 않는다(§2-3, §2-5-e, §4-B-4 (a), §7-6). (2) SUSPENDED를 DB 쪽 확인(`'*'`나 탐지 커밋의 `SyncRep` 대기)이 있을 때로 좁혔다. 한 노드만 느린 경우에 그 노드가 `423`을 내며 중단이 풀리지 않는 것을 막는다. `'*'`인 동안에는 재개하지 않는다(§2-5-e 상태·재개). (3) 게이트 필터를 수용 제한 필터 앞(order 4)으로 옮겼다. 뒤에 두면 중단 직전에 걸린 요청이 쥔 허가 때문에 중단 동안의 주문이 `429`를 받는다(§2-5-e 응답). (4) 확정 응답 보류를 더했다. ACTIVE가 아니거나 '재확인 전'인 노드는 by-client 재조회의 '접수됨'과 V47 커밋 회수의 `201`을 `409 order_in_progress`로 미룬다. 크래시 재시작 뒤 복제되지 않은 행이 보이기 때문이다(§2-5-e, §4-B-4 (a)). (5) 좀비 판정 정지를 SUSPENDED로 좁히고 UNREACHABLE 게이트 거절을 수요로 센다(§2-5-e 좀비 판정). (1)~(5)는 2026-09-25 설계를 맡은 AI가 정했고 사용자 확인 대상이다(설계 13절 13·14·16·17).

같은 날 코드 단계 뒤의 코드 검토를 반영해 이 판을 다시 고쳤다. 버전은 그대로 v2.60이다. (6) 게이트의 대상 경로는 필터 등록이 정하고 게이트가 원시 요청 URI로 다시 가리지 않는다고 적었다. 원시 URI로 가리던 첫 코드는 `;` 매개변수나 퍼센트 인코딩으로 적은 쓰기를 게이트 없이 컨트롤러에 보냈다(§2-5-e 대상). (7) 같은 키 재전송의 재생 `200`이 확정 응답 보류의 대상이 아닌 근거(재생 트랜잭션 자신의 동기 커밋)와, 이체 재생은 아직 대상이 아니라는 것을 적었다(§2-5-e 확정 응답 보류). 응답 코드와 모양은 바뀌지 않는다.
