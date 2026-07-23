# 고가용성 가상 암호화폐 거래소 · 완전 API 및 기술 스택 명세서 (v2.14, 제출본)

> 본 문서는 5개 조사 보고서(real-exchange-api / spring-ha-stack / matching-engine / ha-recovery / db-best-practices / bot-simulation / observability-chaos)와 **설계 검증·기술 검증** 두 리뷰의 지적(critical/high 전부 + medium/low 반영)을 통합하여, **그대로 구현·제출 가능한 수준**으로 확정한 거래소 전체 명세다.
> 설계 원칙 우선순위: **정확성(체결 정합성) > 고가용성(자동 복구) > 성능 > 편의성**.
> 두 증명 목표(① 폭주·장애에도 체결이 어긋나지 않음, ② 자동 복구)를 관통하는 축: **결정론적 단일 라이터 + 이벤트 소싱 + (필수)멱등성 + 복식부기 원장 + epoch(fencing, 전 쓰기 경로 §7-1)**.

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
exchange-platform/
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
   │  readiness=REFUSING_TRAFFIC 프로브 기반 라우팅 (페일오버 시 즉시 제외)
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
                       리더선출: Redisson RLock + fencing token(epoch)
                                       │ (4) 같은 트랜잭션: assert_current_leader(holder, epoch) 통과 후 trades + outbox + ledger (fencing_epoch 저장)
                                       ▼
                       [Kafka trades 토픽] ──┬─▶ [PostgreSQL 프로젝션 (JPA/jOOQ)]
                                            └─▶ [STOMP 팬아웃: /topic=Redis Pub/Sub 릴레이, /user/queue=외부 STOMP 브로커 릴레이]
                                                     ▼  실시간 호가/체결/잔고 푸시
[DB HA: Patroni(+etcd) 동기복제 + HAProxy 라우팅. 무손실 failover 전제 조건은 §1 DB 자체 HA 행]
[복구: snapshot + journal replay | 정산배치: @Scheduled + ShedLock + fencing]
[시크릿: 로컬=.env / 운영데모=Docker Secret·K8s Secret (secret_key는 AES-GCM 암호화 저장)]
```

**컨테이너 재기동 정책**: Docker Compose `restart: unless-stopped`(또는 K8s Deployment + livenessProbe)로 kill 후 자동 재기동 → "자동 복구"의 인프라 토대. 카오스 API의 `docker_kill`은 이 재기동 정책과 짝을 이룬다.

**설계 결정 3가지**: ① 매칭=메모리(단일 라이터)·DB=비동기 프로젝션, ② 이벤트 소싱(command_journal)으로 복구 리플레이, ③ 활성-대기 리더선출(epoch fencing)로 HA. fencing 검증은 command_journal 삽입에 한정되지 않는다. 체결·취소·catch-up·스냅샷을 포함한 모든 엔진 기원 쓰기 트랜잭션이 `assert_current_leader(holder, epoch)`를 통과해야 커밋되며, event_journal·trades·snapshots에 `fencing_epoch`를 저장한다(§7-1). split-brain 차단 주장은 이 계약이 성립할 때에 한한다.

---

<a name="1"></a>
## 1. Spring 생태계 라이브러리 선정표 (기술 검증 반영)

> **버전 경로 확정 (기술 검증 반영)**: 초안은 "Spring Boot 4.1 주력"을 권했으나, **회복탄력성 계층을 전면 의존하는 Resilience4j가 SB4(Spring Framework 7)를 아직 GA로 지원하지 않는다**(spring-boot3 모듈은 Spring 6 의존, 이슈 #2351/#2371 오픈). CircuitBreaker·RateLimiter·Bulkhead가 미지원되면 신뢰성 캡스톤의 핵심 계층이 공백이 된다. 따라서 **과제 요구("3.x")와도 부합하고 전 스택이 GA로 검증되는 Spring Boot 3.5.16(3.5 최종) + Java 21 LTS 경로를 확정**한다. 보안 패치는 3.5 브랜치가 커버되는 시점까지 유효하며, EOL 리스크는 "검증된 회복탄력성 스택"과의 트레이드오프에서 후자를 택했다.

| 관심사 | 최종 선택 | 확정 버전 (2026-07 기준) | 선택 이유 |
|---|---|---|---|
| **런타임 기반** | **Spring Boot 3.5.16 + Java 21** | SB 3.5.16 | 전 스택 GA 검증. Resilience4j·Chaos Monkey 등 회복탄력성/카오스 도구가 정식 지원되는 유일한 안정 경로. |
| **REST 계층** | Spring MVC + **Java 21 Virtual Threads** (`spring.threads.virtual.enabled=true`) | SB BOM | DB-heavy 워크로드에서 가상 스레드가 tail latency 우세. JPA/JDBC 그대로 사용 → 완성도·유지보수 유리. |
| **실시간 푸시** | **WebSocket + STOMP** (`spring-boot-starter-websocket`) | SB BOM | 호가/체결/잔고 다채널 구독. 공개 `/topic/**`=Redis Pub/Sub 릴레이, 비공개 `/user/queue/**`=**외부 STOMP 브로커 릴레이(RabbitMQ/ActiveMQ)로 크로스노드 user-destination 라우팅**(§5-1). |
| **영속성(도메인)** | **Spring Data JPA / Hibernate 6.6.x** | SB BOM | 계정·지갑·주문 CRUD. `@Version` 낙관적 락. |
| **영속성(핫패스 조회)** | **jOOQ** (Community, Apache 2.0) | **SB BOM 위임(3.5.x 관리 라인=3.19.x)** | 호가 집계·체결 페이징·리포팅. 컴파일타임 타입안전. 정정(v2.4): 개별 핀 `3.20/3.21` 제거. jOOQ는 SB BOM 관리 항목이라 개별 핀은 버전 고정 원칙(하단) 위반이자 BOM 라인 오버라이드(Q12의 Flyway와 동일 논리). |
| **DB 마이그레이션** | **Flyway** | **SB BOM 위임(3.5.x 관리 라인=11.x)** | SQL-first·저마찰. Liquibase 5.0 FSL 라이선스 이슈 회피. 정정(v2.2): 개별 핀 `12.9.0` 제거. Flyway는 SB BOM 관리 항목이라 개별 핀은 버전 고정 원칙(하단) 위반이자 BOM 메이저 오버라이드(SB 3.5 자동구성이 검증한 11.x와 상이)였다. |
| **메시징/이벤트** | **Spring for Apache Kafka** | **SB BOM 위임(3.3.x, SB3 정합)**, Kafka 4.x KRaft | 트랜잭셔널 아웃박스 발행·리플레이. EOS로 이중발행 방지. (초안의 4.1.0은 SB4용 → SB3 경로에선 BOM이 관리하는 3.3.x. v2.2: 개별 핀 표기 제거. BOM 관리 항목) |
| **캐시/분산 프리미티브** | **Redisson** (+ Spring Data Redis 보조) | **redisson-spring-data-35 (Spring Data Redis 3.5 정합)**, Redisson 최신 GA | 분산 락·리더선출·RateLimiter·Pub/Sub·nonce 저장. |
| **회복탄력성** | **Resilience4j** | **`resilience4j-spring-boot3` 2.4.0** | CircuitBreaker·RateLimiter·Bulkhead·Retry·TimeLimiter. Micrometer 통합. 버전 정정: 3.x는 미존재, 최신 GA=2.4.0. SB3.5에서 GA 검증. |
| **리더선출/분산락** | **Redisson RLock + fencing token(epoch)** (매칭) / **ShedLock 7.7.0**(배치) | Redisson / ShedLock 7.7.0 | 매칭 단일 활성화=Redisson 리더 락. 배치 중복방지=ShedLock. **주의: ShedLock은 correctness 보장 X → fencing token 병행 필수**(적용 범위: 모든 엔진 기원 쓰기 트랜잭션, §7-1). |
| **관측성(메트릭)** | **Actuator + Micrometer(Prometheus registry) + Grafana** | **버전 BOM 위임(개별 핀 제거)** | 정정: `micrometer-registry-prometheus` 마이크로버전 개별 고정 제거(‘BOM 위임’ 원칙과 모순 해소). 스타터만 추가. |
| **관측성(트레이싱)** | **Micrometer Tracing(OTel Bridge) → 백엔드 Grafana Tempo** | SB BOM + OTLP exporter | 신규: 트레이스 **수집·조회 백엔드를 Tempo로 확정**(미지정 시 트레이스 미수집). Zipkin/Jaeger 대체 가능. |
| **관측성(로그)** | **Logback JSON → Loki (Grafana)** | (인프라) | 신규: 중앙 로그 집계. 카오스 시 노드 간 로그 상관분석(trace_id 키). |
| **테스트** | **JUnit5 + Testcontainers + Awaitility + ArchUnit + jqwik** | **Testcontainers·Awaitility=SB BOM 위임** / ArchUnit 1.4.2·jqwik 최신 GA(BOM 미관리 → 개별 핀 유지) | 실 PG·Kafka·Redis·Toxiproxy 컨테이너. 비동기=Awaitility(sleep 금지). 경계=ArchUnit. 불변식=jqwik. 정정(v2.4): TC·Awaitility 개별 핀 제거. 둘 다 SB BOM 관리 항목(버전 고정 원칙 정합). |
| **부하 테스트** | **k6 (메인)** + Gatling(발표용, 선택) | k6 2.0 / Gatling 3.13+ | Thresholds→exit code로 SLO CI 게이팅. WS 부하 지원. |
| **카오스** | **Toxiproxy(네트워크) + Chaos Monkey(앱) + docker kill(인프라)** | toxiproxy 모듈=**TC(SB BOM) 위임**(v2.4, TC 개별 핀 제거와 동일 논리) / **chaos-monkey-spring-boot 3.x (SB3 정합, BOM 미관리 → 개별 관리)** | 정정: 초안의 "Chaos Monkey 4.0.0=SB3 타깃" 경고는 **사실과 반대**(4.0.0은 SB4.0.2 기반=SB4 지원). SB3.5 경로에선 **3.x 라인을 사용**. Toxiproxy=네트워크, Chaos Monkey=앱 계층 상호보완. |
| **매칭엔진 동시성** | **LMAX Disruptor** | 4.0.0 (min JDK 11, Java 21 OK) | 락-프리 링버퍼. 참고: 4.0.0(2023-09) 이후 유지보수 정체 → **1차 `ArrayBlockingQueue`로 결정론 완성 후, 벤치마크로 실측 이득 확인되면 교체**(handler 인터페이스 동일). |
| **커넥션 풀** | **HikariCP** | SB BOM | 신규: VirtualThreads+JDBC 조합의 **pinning·풀 사이징** 명시(§7-5). 풀 고갈이 p99 SLO 직결. |
| **DB 자체 HA** | **Patroni(+etcd) + HAProxy** | (인프라) | failover RPO=0은 무조건 성립하지 않는다. 성립 조건(운영 요건): `synchronous_standby_names`로 동기 standby 강제, 정산·저널 트랜잭션에 `synchronous_commit=on`(remote flush 이상), DB 클러스터의 리더 선출과 STONITH(Patroni+etcd 담당), PITR 아카이브 병행. 비동기 standby failover·스토리지 손실·조기 승격에서는 유실 가능. HAProxy가 Patroni REST 헬스체크로 새 리더 라우팅, RTO 수초. |

**버전 고정 원칙**: Micrometer/JUnit/Hibernate/Kafka-client 개별 고정 금지 → **Spring Boot BOM 위임**. 스타터만 추가. (표에서 개별 핀은 Resilience4j·ShedLock 등 BOM 미관리 항목에만 남긴다.)

---

<a name="2"></a>
## 2. 공통 규약

### 2-1. 기본 규약

| 항목 | 채택안 |
|---|---|
| REST 버전 prefix | `/api/v1` |
| WS 버전 prefix | `/ws/v1` (STOMP `accept-version:1.2` 별도) |
| Admin 버전 prefix | `/admin/v1` |
| 스키마 버전 | 모든 REST/WS 페이로드에 `schema_version:"1.0"` 포함 |
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
| 400 | `invalid_parameter` | 파라미터 형식·조합 오류(§4-B-1 상호배타·금지 조합 위반 포함, `detail.constraint`에 위반 규칙명) |
| 400 | `invalid_price` / `invalid_volume` | 가격단위(tick)/수량단위 위반 |
| 400 | `under_min_order` / `over_max_order` | 최소 미달 / **최대 초과(신규)** |
| 400 | `insufficient_funds` | 주문 가능 잔고 부족 |
| 400 | `client_order_id_required` | **멱등키 누락(신규, §2-4)** |
| 401 | `jwt_verification` | 서명 검증 실패. v2.2: `expired_jwt` 삭제. payload에 `exp` 클레임이 없고(§3-3) 신선도 위반은 §3-4의 `timestamp_out_of_window`로 일원화(도달 불가 코드 정리) |
| 401 | `invalid_query_payload` | canonical 서명(method+path+params) 불일치 |
| 401 | `nonce_used` | nonce 재사용(재전송 공격) |
| 401 | `timestamp_out_of_window` | `timestamp`가 허용창 밖 |
| 401 | `auth_throttled` | **반복 401 임계 초과 일시 차단(신규)** |
| 403 | `out_of_scope` | API Key 권한 부족(조회키로 주문 등), 또는 세션 종류가 리소스 요구와 불일치(데모·API 키로 회원 전용 `/me` 접근, §3-8) |
| 403 | `admin_forbidden` | **관리 스코프/승인 부족(신규, §3-6)** |
| 400 | `weak_password` | **회원 비밀번호 최소 길이(8자) 미달(신규, §3-8)** |
| 400 | `invalid_email` | **회원 이메일 형식 오류(신규, §3-8)** |
| 401 | `invalid_credentials` | **로그인 이메일·비밀번호 불일치. 존재 여부 비노출(미가입도 동일 코드·메시지·유사 지연) (신규, §3-8)** |
| 401 | `refresh_reuse` | **소각·폐기된 리프레시 재제출. 도난 신호로 family 전체 폐기(신규, §3-8)** |
| 401 | `session_expired` | **리프레시 만료·부재(신규, §3-8)** |
| 403 | `account_suspended` | **`users.status` SUSPENDED·CLOSED 계정의 로그인·갱신 거부(신규, §3-8)** |
| 409 | `email_taken` | **이메일 중복 가입(신규, §3-8)** |
| 423 | `account_locked` | **로그인 실패 누적 백오프 잠금(+`Retry-After`, 신규, §3-8)** |
| 404 | `order_not_found` | 주문 없음 |
| 405 | `method_not_allowed` | **허용되지 않은 메서드(신규)** |
| 409 | `duplicate_client_order_id` | 멱등키 충돌(내용 상이) |
| 409 | `order_in_progress` | **동일 멱등키 in-flight 처리 중(신규, §2-4)** |
| 413 | `payload_too_large` | **요청 바디 상한 초과(신규, 기본 64KB)** |
| 415 | `unsupported_media_type` | Content-Type 불일치 |
| 422 | `domain_rejected` | 도메인 거부(하위 `reject_reason`, §4-B-1) |
| 429 | `too_many_requests` | rate limit 초과 (+`Retry-After`, +위반 버킷) |
| 418 | `auto_banned` | 지속 위반 IP 밴 (+해제 조건, §2-5) |
| 500 | `server_error` | 일시적 서버 오류 |
| 503 | `service_unavailable` | 페일오버 중 (주문 상태 `UNKNOWN` → 재조회, **+`Retry-After` 필수**) |

**장애 규약(중요)**: 5XX/타임아웃 수신 시 클라이언트는 주문 상태를 `UNKNOWN`으로 간주하고 **동일 `client_order_id`로 재조회**(§4-B-4-a) 후 판단한다. 신규 재전송 금지 → 이중 체결 방지. Binance 관례 계승. 재조회 상태머신은 §7-6.

### 2-4. 멱등성 (client_order_id) · 최중요 (필수 승격)

- 필드: `client_order_id`, 1~36자, `[A-Za-z0-9_-]`.
- **POST /orders·POST /orders/batch에서 진짜 필수(required)로 못박음.** 미제공 시 **예외 없이 `400 client_order_id_required`** 반환한다.
  - 서버 생성 fallback(`srv-{user_id}-{seq}`) 제거(이전 버전의 자기모순 해소): "필수인데 서버 기본값 존재"는 사실상 optional이 되어, 타임아웃 후 키 없이 재전송하는 순진한 클라이언트가 매 재시도마다 다른 키를 받아 이중 접수될 수 있었다(이중체결 escape hatch). 이를 원천 차단하기 위해 **키 생성 책임을 전적으로 클라이언트(봇 포함)에 둔다.**
  - 정확성(체결 정합) > 편의성 원칙에 따라, 데모 편의보다 이중체결 불가능성을 택한다. 모든 봇·재시도 클라이언트는 재시도 시 **동일** `client_order_id`를 재사용해야 하며(내용 동일 시 §2-3 멱등 replay로 수렴), 그것이 유일한 안전 재전송 경로다.
- DB 제약: `UNIQUE (user_id, client_order_id)`(주문 생성 커맨드 한정 부분 유니크 인덱스, §7-1, v2.5).
- **동시 in-flight 규약(신규)**: 동일 `(user_id, client_order_id)`의 두 요청이 첫 커밋 전에 동시 도착:
  - 저널 append 시 `UNIQUE` 제약이 두 번째를 차단 → 두 번째는 원본 커밋 완료까지 대기 후 **내용 비교를 수행**한다: 내용 동일이면 **기존 주문 반환(200, `X-Idempotent-Replay: true`)**, 내용 상이면 **`409 duplicate_client_order_id`** (v2.2: 동시 도착과 순차 도착의 결과를 동일화. 대기 경로도 아래 '동작 요약'의 내용 비교 규칙을 그대로 적용).
  - 원본이 아직 ACK 전(대기 타임아웃 내 미완료)이면 **`409 order_in_progress` + `Retry-After`** → 클라이언트는 재조회로 수렴.
- 동작 요약:
  - 동일 키 & **내용 동일** → 기존 주문 반환(200, `X-Idempotent-Replay: true`).
  - 동일 키 & **내용 상이** → `409 duplicate_client_order_id`.
- **내용 비교 기준(v2.3 확정, §3-3 "재현 불가능한 표현 금지" 정합)**: '내용 동일/상이'는 저널에 저장된 **원본 요청 바디의 JCS(RFC 8785, §3-3(b)) 정규화 UTF-8 바이트열**과 재요청 바디의 JCS 바이트열의 **바이트 단위 동일성**으로 판정한다(서명 canonical과 동일 정규화 재사용 → 키 순서·공백·개행 차이는 동일로 수렴). 필드 생략과 기본값 명시(예: `time_in_force` 생략 vs `"GTC"` 명시)는 JCS 바이트가 다르므로 **상이(409)**다. 재시도 클라이언트는 원 요청과 필드 구성까지 동일한 바디를 재전송해야 한다. batch(§4-B-6)의 per-item 비교도 **해당 원소 객체의 JCS 바이트**로 동일 기준을 적용한다.
- **취소(DELETE) 멱등**: 이미 `CANCELED`인 주문 재취소 → `200` + 현재 상태 반환(에러 아님). 없는 주문 → `404`.
- 재생 계층: 복구는 항상 `seq > last_applied_seq`만 적용 → 재생 시 이중 적용 원천 불가.
- **보존 정책(신규)**: `client_order_id` UNIQUE는 `command_journal`에서 영구 유지. 조회 프로젝션(orders)은 90일 후 콜드 파티션으로 이관하되 UNIQUE 원장은 유지 → 키 재사용 금지(무기한). 데모 리셋 시에만 네임스페이스 초기화.

### 2-5. Rate limit (알고리즘·다중버킷·프록시 확정)

- **알고리즘**: 고정창이 아닌 **토큰버킷**(Resilience4j RateLimiter / Redisson `RRateLimiter`). 초당 리필. thundering-herd 시나리오에서 창 경계 2배 버스트·리셋 herd 방지. `X-RateLimit-Reset`은 **"다음 토큰 가용 시각(epoch s)"**으로 재정의.
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
| 공개 조회 (orderbook/ticker/trades) | 1~2 | 300 토큰 / 10s (IP) |
| 계좌/주문 조회 | 2 | 200 토큰 / 10s (계정) |
| 주문 생성/취소 | 5 | 100 토큰 / 10s (계정) |
| 관리 API(admin) | 10 | 60 토큰 / 60s (admin 계정). **신규: 관리 평면도 rate limit 적용** |

- **신뢰 프록시(X-Forwarded-For)**: LB(HAProxy/Nginx) IP를 신뢰 프록시 목록에 등록하고, XFF를 **우측부터 신뢰 프록시 홉을 제거한 뒤 남는 첫 IP(rightmost-untrusted)**를 클라이언트 IP로 채택한다. 최좌측 값은 원 클라이언트가 임의 주입 가능하므로 신뢰하지 않는다(최좌측 채택은 폐기). 구현은 forward-headers-strategy(RemoteIpValve)로 이 파생을 컨테이너에 위임하고, 애플리케이션은 getRemoteAddr()만 읽는다(원시 XFF 파싱 금지, 단일 유틸 ClientIp.of). **봇/데모 트래픽은 계정 키 기반 버킷을 우선 적용**(모든 봇이 동일 프록시 IP로 보여 IP 버킷이 마비되는 것 방지). 미인증 공개 조회만 IP 버킷.
  - **신뢰 경계 핀(배포 불변식)**: `server.tomcat.remoteip.internal-proxies`를 프로파일별로 명시 지정한다. RemoteIpValve 기본 internalProxies(전 RFC1918+loopback)를 그대로 쓰면 사설 소켓피어 배포에서 클라이언트가 보낸 단일 XFF가 rightmost-untrusted로 채택되어 회전 스푸핑으로 IP 버킷을 우회한다. 운영은 LB VIP CIDR만 등록(`EXCHANGE_TRUSTED_PROXIES` 주입, 미주입 시 루프백 전용 fail-closed), 프록시 없는 dev·데모·통합테스트는 루프백 전용으로 좁힌다. 인그레스는 인바운드 XFF를 strip한 뒤 자신을 append하는 것을 배포 전제로 한다.
- **인증 실패 스로틀(신규)**: 동일 `access_key`/IP의 401 반복이 임계(예: 60s 내 20회) 초과 시 `401 auth_throttled` + 지수 백오프 락아웃 → secret 브루트포스 방어.
- **418 auto_ban 해제(신규)**: 지속 위반 시 5분 밴. 밴 중 신규 요청은 카운트 미증가, TTL 만료 시 자동 해제. 위반 카운터는 60s 슬라이딩 윈도로 감쇠.

이 헤더 노출·버킷 계측이 "부하 제어를 데이터/그래프로 증명"하는 핵심.

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
> 위 커서를 디코딩하면 `{"ts":"2026-07-21T09:00:00.000Z","id":"01J8ZT"}`. 커서의 `id` 축은 **외부 노출 id 문자열**(주문=UUID(v2.7 W3), 체결=event_seq 파생 id. §7-1, §4-B-4 커서 통일)이다. id 발급 규약(order=사전 발급 박제, trade=event_seq 결정론 파생)은 §7-1 타입 규약(v2.6)을 따른다. 내부 BIGINT PK는 커서에 절대 노출하지 않는다(v2.3, 이전 예시의 정수 id `90011`은 문자열 id 통일 규칙 위반이라 교정). **예외 · candles(v2.5)**: candles는 외부 노출 id가 없는 자연키 리소스이므로 커서의 `id` 축으로 **`(candle_time, market)` 자연키를 사용**한다(§4-A-5, 내부 PK 미노출 원칙은 동일 유지).

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

**canonical 예시 (배열 바디 · batch)**: 요청 `POST /api/v1/orders/batch`, 바디
```json
[{"client_order_id":"b1","market":"KRW-BTC","ord_type":"limit","price":"50000000","side":"bid","volume":"0.01"},
 {"client_order_id":"b2","market":"KRW-ETH","ord_type":"limit","price":"3000000","side":"ask","volume":"0.2"}]
```
→ JCS 정규화(배열 순서 보존, 각 객체 키 정렬·공백 제거):
```
[{"client_order_id":"b1","market":"KRW-BTC","ord_type":"limit","price":"50000000","side":"bid","volume":"0.01"},{"client_order_id":"b2","market":"KRW-ETH","ord_type":"limit","price":"3000000","side":"ask","volume":"0.2"}]
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
- **비가역 액션 이중 승인(v2.3 완결 규정, v2.12 config·operators 확장)**: `approval_token` 필수 액션은 다음 **완결 열거**로 확정한다('등'으로 열어두지 않는다): `POST /admin/v1/chaos/kill-node`, `POST /admin/v1/chaos/network`, `DELETE /admin/v1/chaos/network/{toxic}`, `POST /admin/v1/chaos/app`, `PATCH /admin/v1/sim/bots/{id}/params`, `POST /admin/v1/config/markets/{symbol}/halt`, `POST /admin/v1/config/markets/{symbol}/resume`, `PATCH /admin/v1/config/markets/{symbol}/fees`, `POST /admin/v1/operators`, `PATCH /admin/v1/operators/{id}/status`, `POST /admin/v1/operators/{id}/roles`. **그 외 모든 관리 엔드포인트**(봇 생성/제거, `sim/start|stop|pause`, `scenarios/{name}/activate`, `PUT /sim/rate`, 공지 발급·갱신·소프트 삭제, 조회류)는 `operator_id`만 필수이고 approval_token **불요**다(§6-A 봇 생성 예시와 정합. 전 액션 감사 로그 대상은 동일). 필수 액션은 요청 바디에 `operator_id` + `approval_token` 포함, 누락·불일치·만료 시 `403 admin_forbidden`. §6-A/§6-B 표의 **승인 컬럼**이 엔드포인트별 요구 여부의 권위 소스다.
  - **approval_token 수명주기(v2.3)**: 요청 operator와 **다른** operator가 `POST /admin/v1/approvals`(mTLS + `scope=admin` 서명, 2인 승인 절차)로 발급하며, 발급 시 **`action` + 대상 식별자**(`node`/`bot_id`/`toxic` 등)에 바인딩된다. 토큰은 **단회용**(검증 성공 시 즉시 소각)·**TTL 5분**. 바인딩된 action·대상과 다른 요청에의 사용, 만료·기소각 토큰 재사용은 `403 admin_forbidden`. 정적·재사용 가능한 토큰은 금지된다(동일 토큰으로 반복 kill-node 승인 불가. nonce가 막지 못하는 '새 요청+같은 토큰' 경로 차단). 발급·사용·만료·거부는 모두 `admin_audit_log`에 기록.
  - **operator 신원 바인딩(v2.4, 2인 통제의 검증 수단 확정)**: `operator_id`는 바디 자가신고 값으로 판정하지 않는다. 각 admin `access_key`(및 mTLS 클라이언트 인증서)는 발급 시 정확히 **하나의 `operator_id`에 1:1 등록**되며(`api_keys`에 operator_id 바인딩, §7-1), 서버는 **모든 관리 요청과 approvals 발급 각각에서 인증 주체(access_key / 인증서 CN)로부터 operator_id를 도출**해 바디의 `operator_id`와 대조한다. 불일치 시 `403 admin_forbidden`(바디 필드는 가독·감사 편의용이고 권위는 인증 주체 유래 값이다. `admin_audit_log`에도 인증 주체 유래 operator_id를 기록). **2인 승인 판정**: "요청 operator와 다른 operator"는 자가신고 값이 아니라 **approvals 발급 요청의 인증 주체 ≠ approval_token 사용 요청의 인증 주체**로 서버가 검증한다 → 단일 admin 키 보유자가 발급 시 `op-02`, 사용 시 `op-01`로 바꿔 신고해 혼자 2인 승인을 완성하는 경로를 차단한다(발급 시 토큰에 발급 주체 operator를 함께 바인딩).
  - **바인딩 target·바디 필드 확정표(v2.13, 발급·소각 정합 단일 규칙)**: `POST /admin/v1/approvals`가 발급하는 `target` 문자열과 파괴 액션 엔드포인트가 소각 시 재구성하는 `target`은 아래 표의 형식으로 **바이트 단위 동일**해야 한다. 불일치는 `403 admin_forbidden`(`detail.reason=binding_mismatch`). `target`은 두 계열로 나뉜다. (1) **자원 스코프**: `target`=파괴 대상 자원 식별자. 페이로드 값(수수료·method·params·type)은 바인딩에 넣지 않는다. (2) **신원·권한 전이 스코프**(`operators.status`·`operators.roles`): `target`에 전이 방향·대상 등급까지 포함해 승인된 토큰을 더 위험한 전이(예: `GRANT VIEWER` 승인분을 `GRANT SUPER`로, `ACTIVE` 승인분을 `DISABLED`로)로 재사용하는 경로를 차단한다. 전이 스코프의 상태·역할·방향 토큰은 모두 **대문자**로 정규화해 바인딩한다. 모든 파괴 액션 바디는 `operator_id`(수행자, 인증 주체 유래 값과 대조)와 `approval_token`을 공통으로 싣는다.

    | action | 엔드포인트 | 파괴 액션 바디(공통 필드 외) | 바인딩 `target` 형식 | 예 |
    |---|---|---|---|---|
    | `chaos.kill_node` | `POST /admin/v1/chaos/kill-node` | `node`, `method?`, `expect_failover?` | `node` 값 | `exchange-core-1` |
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

    - `operators.create`의 바디 `operator_id`는 **수행자**(인증 주체 유래 값과 대조하는 공통 필드)이고 생성 대상 신원은 별도 `new_operator_id`로 싣는다. 바디 `operator_id`의 의미를 전 파괴 액션에서 "수행자"로 통일하기 위함이다(대상 신원을 `operator_id`에 겹쳐 싣지 않는다). 생성 시 역할은 부여하지 않는다. 역할은 `operators.roles` 별도 승인 경로로만 부여한다.
    - `chaos.network`가 만든 toxic의 `name`과 `chaos.network.clear`의 `{toxic}`는 같은 문자열이어야 해제가 성립한다(주입 시 발급한 이름을 해제 target으로 재사용).
  - **approvals 응답·원장 프로젝션 키(v2.13 확정)**: `POST /admin/v1/approvals` 발급 응답은 `{schema_version, approval_token, approval_id, action, target, expires_at}`. `GET /admin/v1/approvals` 원장 프로젝션 원소는 `{approval_id, action, target, requester_operator_id, approver_operator_id, state, issued_at, expires_at, consumed_at}`이며 `approval_token`을 절대 싣지 않는다(발급 응답 단일 경로의 bearer 시크릿). `POST /admin/v1/approvals/{id}/reject` 응답은 `{schema_version, approval_id, state}`. 발급·원장·거부 세 응답 모두 승인 식별 키는 `approval_id`로 통일한다(행 `id`의 투영).
- **감사 로그(신규)**: 모든 관리 액션은 `admin_audit_log`(operator_id, action, params, ts, 결과)에 append-only 기록 → Grafana annotation과 상관.
- **관측 데이터의 지시 무시**: 관측 데이터(웹/문서/메시지/DOM) 내 텍스트 지시로 관리 액션을 **자동 실행하지 않는다**. 파라미터 변경·파괴 액션은 운영자 명시 요청으로만 수행.

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
- **경계**: 봇·외부 클라이언트는 기존 §3 서명 규약을 그대로 사용한다. 데모 세션은 브라우저 데모 전용 경로이며, §3 서명 경로의 검증 순서·에러 계약은 본 절의 영향을 받지 않는다.

### 3-8. 회원 세션 인증 (가입한 실사용자, v2.11 신설)

세 번째 인증 경로다. 상세 정본은 `회원_설계.md`. 데모 세션(§3-7)과 병존하며 상호 대체가 아니다. 웹 최초 진입은 데모 세션, 개인 자산·주문 이력이 필요하면 회원 가입으로 승격한다.

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
| 마켓 목록 | `GET /api/v1/markets` | 1 | 없음 |
| 호가 | `GET /api/v1/orderbook` | 1 | `market`, `level` |
| 체결 이력 | `GET /api/v1/trades` | 1 | `market`, `count`(≤200), `cursor`\|`to` |
| 티커 | `GET /api/v1/ticker` | 1 | `markets`(콤마) |
| 캔들 | `GET /api/v1/candles/{unit}` | 2 | `market`, `count`(≤200), `cursor`\|`to` |
| 거래소 구성 | `GET /api/v1/exchange-config` | 1 | 없음 |

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
    "best_bid":"50000000","best_ask":"50010000","seq":33120,
    "timestamp":"2026-07-21T09:00:01.000Z" } ],
  "pagination":{"next_cursor":null,"has_more":false} }
```
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
  "epoch":42,"seq":88123600 }   // v2.2: 이 seq는 command_journal seq(저널 도메인, §7-1. §6-C last_applied_seq와 동일 축, journal_counters DB 원자 채번 v2.6). WS 채널 seq(§5-2)와는 별개 도메인
```

**id·시각·seq 결정론(v2.6, id 표기 v2.7 정정)**: 응답 `id`는 게이트웨이가 명령 접수 전 사전 발급해 command_journal에 박제한 **UUID**다(구현 정본 §7-1. 이전 판의 ULID 표기는 오기). 저널 append 성공 이후에는 프로젝션 반영 전이라도 id가 확정되어 있으며, 저널 재생 시에도 동일 값이 재사용된다(재발급 금지). `created_at`은 시퀀서가 command에 박제한 `engine_ts`에서 파생하므로 재구축 시에도 동일하다. `seq`는 `journal_counters` 단일 행 `UPDATE ... RETURNING` 원자 채번으로 gapless가 보장된다(§7-1, §6-C ①).

**notional(시장가 매수) 응답 예**: `remaining_notional`(잔여 금액), `executed_funds`(체결 금액), `executed_volume`(환산 수량) 채움. 접수 시점 기준가로 수량 환산 규칙 명시.

**상태값**: `OPEN` / `PARTIAL` / `FILLED` / `CANCELED` / `REJECTED`. 상태 불변식(v2.6): 모든 상태에서 주문의 락 금액은 0 이상이다. `FILLED`이면 `remaining_volume`은 `"0"`(시장가 매수는 `remaining_notional`이 `"0"`)이고 해당 주문 기여분의 락 잔량도 0이다(§4-B-5 최종 체결의 실제 잔여 락 소비 규약).

**도메인 거부(신규 `reject_reason`)**: `422 domain_rejected` + `detail.reject_reason` ∈
`market_suspended`(마켓 정지) / `post_only_would_take`(POST_ONLY 즉시체결) / `fok_not_filled`(FOK 전량충족 불가) / `ioc_partial_canceled`(IOC 잔량 취소 사유·정보성) / `self_trade_prevented`(자전거래 방지 발동). `myOrder` 이벤트에도 동일 `reject_reason` 노출.
> v2.2: `over_max_order`를 이 목록에서 **삭제**. 최소/최대 주문 한도는 마켓 메타(`min_order_amount`/`max_order_amount`) 기반 **게이트웨이 정적 검증**이므로 `under_min_order`와 대칭으로 **`400 over_max_order`(§2-3) 단일 경로**로 확정한다(동일 위반의 400/422 이중 정의 상충 해소).

**FOK 자기유동성(정본 §7-2 상호참조)**: `fok_not_filled` 게이트는 자기 유동성(같은 `user_id`의 반대편 레스팅)을 충족 용량으로 센다. 자기 유동성만으로 전량 충족되는 FOK는 `fok_not_filled`로 거부되지 않고 접수되며, 실행 시 해당 부분은 체결이 아니라 STP-DC로 소멸한다(maker·taker 양쪽 `self_trade_prevented` CANCELED, taker 잔량 0 도달, 무체결 전량 소멸 가능). 사전 스캔이 자기 유동성을 제외하면 스캔·실행 판정이 갈려 결정론이 깨지므로 포함이 계약이다. 판정·소멸 산식의 정본은 §7-2이고 엔진 동작(`wouldFullyFill`·STP-DC)은 무변경이다.

**멱등 재요청**: 동일 키·동일 내용 → `200` + `X-Idempotent-Replay: true` + 기존 주문. in-flight 동시 → §2-4.

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

#### 4-B-3. 일괄 취소 `DELETE /api/v1/orders?market=KRW-BTC`
```json
// 200
{ "canceled_count":37,"canceled_ids":["01J8ZT...", "..."],
  "failed":[{"id":"01J8ZU...","reason":"order_not_found"}] }
```
페일오버/폭주 중 다수 미체결을 한 번에 취소하는 운영 시나리오 지원.

#### 4-B-4. 조회
- `GET /api/v1/orders/{id}`, `GET /api/v1/orders/by-client/{client_order_id}` → 단건 객체(위 201 스키마와 동일 필드).
- **(a) 재조회 계약**: `UNKNOWN` 해소용. **판정의 권위 소스는 `command_journal`(진실의 원천, §7-1)이다(v2.2 확정)**. orders는 Kafka 경유 비동기 프로젝션(§0-2)이라 지연될 수 있으므로, by-client 조회는 프로젝션만 보고 404를 반환해서는 안 된다. 5XX/타임아웃 후 클라이언트는 `by-client/{client_order_id}`로 조회 → **저널에 없음 = `404`**(미접수, 재전송 가능) / **저널에 있으나 프로젝션 미반영 = `409 order_in_progress` + `Retry-After`**(비종결, 재조회 반복) / 프로젝션 반영 후 = 상태 반환(접수됨, 재전송 금지). §7-6 상태머신. 주문 `id`는 명령 접수 전 사전 발급되어 저널에 박제되므로(§4-B-1 v2.6), 저널 존재 판정 시점에 이미 id가 확정되어 있고 프로젝션 반영 후 조회되는 id와 항상 동일하다.
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

```json
// 요청  POST /api/v1/orders/batch
[ {"market":"KRW-BTC","side":"bid","ord_type":"limit","price":"50000000","volume":"0.01","client_order_id":"b1"},
  {"market":"KRW-ETH","side":"ask","ord_type":"limit","price":"3000000","volume":"0.2","client_order_id":"b2"},
  {"market":"KRW-BTC","side":"bid","ord_type":"limit","price":"50000000","volume":"0.01","client_order_id":"b1"} ]

// 200 (부분 성공: 1건 접수 · 1건 도메인 거부 · 1건 멱등 replay)
{ "schema_version":"1.0",
  "results":[
    { "index":0,"client_order_id":"b1","status":"accepted",
      "order":{"id":"01J8ZT...A","status":"OPEN","market":"KRW-BTC","side":"bid",
               "remaining_volume":"0.01","executed_volume":"0","epoch":42,"seq":88123601} },
    { "index":1,"client_order_id":"b2","status":"rejected",
      "error":{"name":"domain_rejected","detail":{"reject_reason":"market_suspended"},
               "message":"마켓이 정지 상태입니다."} },
    { "index":2,"client_order_id":"b1","status":"replayed","idempotent_replay":true,
      "order":{"id":"01J8ZT...A","status":"OPEN","market":"KRW-BTC","side":"bid",
               "remaining_volume":"0.01","executed_volume":"0","epoch":42,"seq":88123601} } ],
  "summary":{"accepted":1,"rejected":1,"replayed":1,"total":3} }
```

- `status` ∈ `accepted`(신규 접수) / `replayed`(멱등 replay, 기존 주문 반환) / `rejected`(개별 거부·**종결**) / **`in_progress`(v2.2 신설: 동일 `client_order_id`가 in-flight 처리 중, 비종결·재시도 대상)**. `accepted`·`replayed`는 `order`(§4-B-1 스키마)를, `rejected`·`in_progress`는 `error`를 담는다. per-item `error`는 **§2-3 표준 에러 객체의 축약형**이다(v2.3): `name`·`message`에 더해 기계가독 부가정보는 §2-3·§4-B-1과 동일하게 **`detail` 안에** 둔다(`rejected`=`detail.reject_reason`, top-level `reject_reason` 금지). `in_progress`=`{"name":"order_in_progress","retry_after":<s>}`(`retry_after`는 §2-3 top-level 필드와 동일 위치). `in_progress`는 종결 거부가 아니므로 클라이언트는 `Retry-After` 후 동일 `client_order_id`로 재전송/재조회하면 멱등 수렴한다. `summary`에는 해당 시 `in_progress` 카운트를 포함한다.
- cancel-all(§4-B-3)의 `failed[]`와 대칭: 생성은 성공·실패를 **동일 `results[]`**에 per-item으로 담아 대칭 계약을 이룬다.

### 4-C. 계좌 (인증 필요)

| 기능 | 메서드·경로 | weight |
|---|---|---|
| 전체 잔고 | `GET /api/v1/accounts` | 2 |
| 체결 내역(내 거래) | `GET /api/v1/my/trades` | 2 |
| 거래(원장) 내역 | `GET /api/v1/my/ledger` | 2 |
| API 키 회전 | `POST /api/v1/keys/{access_key}/rotate` | 5 |
| API 키 폐기 | `DELETE /api/v1/keys/{access_key}` (§3-2 즉시 revocation, 표 등재 v2.5) | 5 |

#### 4-C-1. `GET /api/v1/accounts`
```json
{ "data":[
  { "currency":"KRW","balance":"1000000.000000000000000000","locked":"50000.000000000000000000",
    "avg_buy_price":"0","unit_currency":"KRW" },
  { "currency":"BTC","balance":"0.490000000000000000","locked":"0.010000000000000000",
    "avg_buy_price":"49500000","unit_currency":"KRW" } ],
  "pagination":{"next_cursor":null,"has_more":false} }
```
불변식: `balance(=available) + locked = total`, 항상 `available ≥ 0`이고 `locked ≥ 0`(둘 다 DB CHECK).
#### 4-C-2. `GET /api/v1/my/trades` · `liquidity_flag`(MAKER/TAKER)·`fee`·해당 체결가 노출(원장 정합성 검증 현실성).

#### 4-C-3. `GET /api/v1/my/ledger` · 거래(원장) 내역 (v2.5 상세 신설, 표에만 있고 상세 절이 없던 비대칭 해소)
- 파라미터: `currency`(선택, 예 `KRW`), `count`(≤200), `cursor`\|`to`. §2-6 opaque 커서 규약 동일(`(ts,id)`, id 축=외부 노출 id 문자열. 주문 id는 UUID, v2.7 §7-1 정정).
- 응답: §2-2 리스트 엔벌로프. 각 항목은 복식부기 원장 분개의 사용자 관점 투영이며, `entry_type` 어휘는 DB 설계 문서 `ledger_entries`와 동일(DEPOSIT/WITHDRAW/TRADE/FEE/RESERVE/RELEASE …).
```json
{ "schema_version":"1.0",
  "data":[
    { "entry_id":"01J8ZW...","currency":"BTC","amount":"0.003998","entry_type":"TRADE",
      "ref":{"type":"TRADE","trade_id":"01J8ZT..."},
      "timestamp":"2026-07-21T09:00:01.060Z" },
    { "entry_id":"01J8ZV...","currency":"KRW","amount":"-200000","entry_type":"TRADE",
      "ref":{"type":"TRADE","trade_id":"01J8ZT..."},
      "timestamp":"2026-07-21T09:00:01.060Z" } ],
  "pagination":{"next_cursor":"eyJ0cyI6IjIwMjYtMDctMjFUMDk6MDA6MDEuMDYwWiIsImlkIjoiMDFKOFpWIn0","has_more":true} }
```
> 예시 수치는 §5-5 체결 시나리오와 정합(v2.7 재계산: bid 0.004 체결 → 체결대금 200,000 차감 TRADE 분개 + 수취 BTC 순액 0.003998 게시 TRADE 분개, 동일 timestamp 축). taker 수수료 0.000002 BTC의 FEE leg는 FEE_INCOME 시스템 계정 귀속이라 사용자 원장에는 나타나지 않는다(§4-B-5 v2.7 수취 자산 수수료 모델). 동일 체결에서 파생된 분개 묶음은 원장 트랜잭션의 `UNIQUE(business_event_type, business_event_id)`(§7-1)로 멱등이라, 재생·재시도로 같은 업무 이벤트가 다시 적용되어도 중복 분개가 생기지 않는다.

---

### 4-D. 게시판(커뮤니티) API (M3, `/api/v1/community`)

게시판_설계.md §4 정본. 거래 평면과 aud 클레임으로 경계 분리한다: 읽기(GET)는 무인증, 쓰기는 회원 세션(EdDSA, aud=community, kind=HUMAN)을 CommunityAuthenticator가 자체 검증한다. 거래 평면 인증 필터(ApiAuthFilter)를 통과하지 않는다. 공통 규약(엔벌로프 §2-2, 에러 §2-3, 커서 §2-6)을 상속한다. schema_version "1.0" 고정.

| 기능 | 메서드·경로 | 인증 | 비고 |
|---|---|---|---|
| 글 목록 | `GET /api/v1/community/boards/{market}/posts` | 불필요 | `sort`(latest\|popular), `cursor`, `count`(기본 20, ≤50) |
| 글 작성 | `POST /api/v1/community/boards/{market}/posts` | 회원 | `Idempotency-Key` 헤더 선택(1~36자 `[A-Za-z0-9_-]`). 재요청은 200 + `X-Idempotent-Replay: true` |
| 글 단건 | `GET /api/v1/community/posts/{externalId}` | 불필요 | soft-deleted·hidden은 마스킹 본문 |
| 글 수정 | `PATCH /api/v1/community/posts/{externalId}` | 회원 | 작성자 본인만 |
| 글 삭제 | `DELETE /api/v1/community/posts/{externalId}` | 회원 | soft delete, 본인만 |
| 댓글 목록 | `GET /api/v1/community/posts/{externalId}/comments` | 불필요 | `cursor`, `count`. created_at ASC 커서 |
| 댓글 작성 | `POST /api/v1/community/posts/{externalId}/comments` | 회원 | `parent_comment_id` 선택(1뎁스). 2뎁스는 400 |
| 댓글 삭제 | `DELETE /api/v1/community/comments/{externalId}` | 회원 | soft delete, 본인만 |
| 글 좋아요 | `POST` / `DELETE /api/v1/community/posts/{externalId}/like` | 회원 | PK 유니크로 멱등. 응답 `{liked, like_count}` |
| 댓글 좋아요 | `POST` / `DELETE /api/v1/community/comments/{externalId}/like` | 회원 | 멱등 |
| 신고 | `POST /api/v1/community/reports` | 회원 | `{target_type, target_id, reason, detail?}`. 대상별 1인 1회. 임계 초과 자동 HIDDEN |

**본문·길이·이스케이프**: 제목 ≤120, 글 본문 ≤4000, 댓글 ≤1000자. 초과·빈 값은 `400 invalid_parameter`. 저장은 원문, 응답 렌더 시 HTML 유의 문자를 엔티티로 이스케이프한다(XSS 방어). 파라미터 바인딩만 사용(인젝션 방어).

**rate limit**(게시판 전용 버킷, 코어 주문 버킷과 별개): 조회 IP당 300/10s, 쓰기 회원당 60/60s. 초과는 `429 too_many_requests` + Retry-After.

**에러 코드**(§2-3 재사용 + 게시판 추가): `400 invalid_parameter`(길이·sort·커서), `401 jwt_verification`(미인증·비회원 토큰 쓰기), `403 out_of_scope`(작성자 불일치), `404 not_found`(board/post/comment 미존재), `409 duplicate_report`(중복 신고), `429 too_many_requests`.

**운영(모더레이션, 관리 평면 MEMBER_MOD, `/admin/v1/community`)**: 숨김·복구·삭제·신고 처리는 배포 단위 C(exchange-admin)가 받아 커뮤니티 서비스에 위임한다. 관리자 조작은 게시판 도메인(posts·comments·reports) 밖(원장·저널)에 닿지 않고 admin_audit_log에 적재된다.

| 기능 | 메서드·경로 | 역할 |
|---|---|---|
| 글 모더레이션 | `POST /admin/v1/community/posts/{externalId}/moderate` | MEMBER_MOD, `{action(HIDE\|UNHIDE\|DELETE), note?}` |
| 댓글 모더레이션 | `POST /admin/v1/community/comments/{externalId}/moderate` | MEMBER_MOD |
| 신고 목록 | `GET /admin/v1/community/reports?status=` | MEMBER_MOD |
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
- 공개 채널: 인증 불필요. 비공개(`/user/**`): `CONNECT` 시 JWT 필요.
- **heart-beat(신규)**: `10000,10000`(10s). 슬로우 컨슈머는 송신 버퍼 상한 초과 시 해당 subscription **드롭 후 절단**(백프레셔). 연결당 **최대 구독 채널 50개** 상한. 상한 초과 SUBSCRIBE는 **§5-6 구독 거절 규약을 준용**한다(v2.5: RECEIPT 미발급 + 해당 subscription으로 `subscription_rejected` 통지 MESSAGE 1건 후 서버측 해제, 연결·기존 구독은 유지).

### 5-2. seq / epoch 의미 (high 확정)

- **`seq`는 채널 단위 단조 증가**로 정의. **일반 규칙(v2.3): seq 도메인 = 구독 destination 단위**다. 공개 채널의 채널 단위 = market × type이며, **unit 차원이 있는 candle은 `market × type × unit` 단위 독립 단조 증가**다(`/topic/candle.1m.KRW-BTC`와 `/topic/candle.5m.KRW-BTC`는 독립 seq 도메인. 1m만 구독한 클라이언트가 5m/1d 증가분으로 false-gap을 감지하는 일 없음). 예: `orderbook.KRW-BTC`와 `trade.KRW-BTC`는 각자 독립 seq 도메인. 특정 채널(destination)만 구독해도 false-gap 없음.
- **seq 도메인의 두 실현(v2.11 확정)**: 채널 seq는 종류에 따라 두 방식으로 실현하되 둘 다 채널 단위 단조 증가라는 상위 규칙을 지킨다. (1) 팬아웃 대상이 점 상태인 `orderbook`·`ticker`는 노드 인메모리 조밀 카운터로 실현한다(destination당 +1 단조, REST 스냅샷은 현재 카운터값 §4-A-2·§4-A-4, 연속성 판정 = 다음 seq가 직전 +1). (2) 팬아웃 대상이 영속 이벤트 리스트인 `trade`·`candle`은 `event_seq`로 실현한다(`trade` seq = event_seq, `candle` seq = 버킷 최대 event_seq, REST 리스트 스냅샷 §4-A-3·§4-A-5와 **동일 값·동일 도메인**). (2)는 리스트 스냅샷의 과거 행이 저마다 seq를 지녀야 하는데 인메모리 카운터로는 과거 행 값을 재구성할 수 없어 영속 축(event_seq)을 쓴다. `event_seq`는 전역 gapless라 채널 내에서는 sparse 단조 증가이므로 **연속성 판정은 strictly-increasing**(`seq ≤ 기준`은 중복·역행 폐기, `seq > 기준`은 순서대로 수용)이며 조밀 +1을 요구하지 않는다. 어느 실현이든 REST 스냅샷 seq가 곧 WS 라이브 seq 기준점이라, 재연결·gap·epoch 교체 후 재동기화에서 라이브가 도메인 불일치로 폐기되는 영구 정지가 없다.
- **비공개 채널의 seq 도메인(v2.2 확정)**: `myOrder`/`myAsset`은 market 차원이 없으므로 **`user × type` 단위 독립 단조 증가**로 정의한다(사용자별·채널별 별도 카운터, 두 채널 간 번호 공유 없음). gap 감지·재동기화도 이 도메인 기준으로 수행한다(불연속 감지 시 REST 스냅샷(주문 `GET /orders/open`·잔고 `GET /accounts`)으로 재동기화).
- **전역 순서가 필요하면 `global_seq` 병기**(선택). gap 감지는 채널 seq로만 수행.
- **`epoch`(리더 세대)**: 모든 WS·orderbook REST 페이로드에 포함. 페일오버로 새 리더가 seq 도메인을 재설정하면 epoch 증가. **클라이언트는 epoch 변화 감지 시 무조건 REST 스냅샷 재동기화**(backward/중복 seq 오인 방지).
- **구독 즉시 계약(v2.4 적용 범위 확정, orderbook 채널 한정)**: **orderbook 채널에 한해** SUBSCRIBE 성공 → 서버가 `stream:"snapshot"` 1건 전송(현재 호가 전체 상태 + 경계 `seq`) → 이후 `stream:"realtime"`. snapshot의 seq가 이후 realtime 연속성의 기준점. **스냅샷·realtime 순서 역전(v2.7)**: 스냅샷 전송과 realtime 게시는 경합할 수 있어 구독 직후 realtime이 스냅샷보다 먼저 도착하거나 `seq <= snapshot.seq`인 realtime이 스냅샷 이후 도착할 수 있다. 클라이언트는 스냅샷 수신 전 도착한 realtime과 `seq <= snapshot.seq`인 realtime을 **폐기**하고, `seq > snapshot.seq`부터 연속성 검사를 시작한다. 서버가 스냅샷 산출과 게시를 직렬화하더라도 이 클라이언트 계약은 유지된다(전달 계층 재정렬 방어). **`stream` 필드는 orderbook 채널 메시지에만 존재**한다. §5-4 trade/ticker/candle 및 §5-5 myOrder/myAsset 스키마에 `stream` 필드가 없는 것이 계약이며, 클라이언트 파서는 해당 채널에서 이 필드를 기대하지 않는다. **여타 채널(trade/ticker/candle/myOrder/myAsset)은 snapshot 메시지를 전송하지 않으며**, 연속성 기준 seq는 **구독 후 첫 realtime 메시지의 seq**다(그 이후의 불연속만 gap으로 판정). 상태 초기값이 필요한 비공개 채널은 구독 직후 REST 스냅샷(주문 `GET /orders/open`·잔고 `GET /accounts`)으로 초기 상태를 확보한다(아래 비공개 도메인 재동기화 규칙과 동일 소스).
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
> trade 메시지의 `trade_id`·`timestamp`는 §4-A-3과 동일 규약이다(`trade_id`=event_seq 결정론 파생, `timestamp`=command `engine_ts` 파생, 재구축 시 동일. v2.6). WS trade `seq`(v2.11)는 §4-A-3 REST `seq`와 **동일 값·동일 도메인**(= event_seq 파생 `trade_id`)이다. REST 스냅샷과 WS 라이브가 한 도메인이라 재연결 재동기화가 연속된다(§5-2 (2) trade·candle 도메인). orderbook·ticker의 조밀 팬아웃 카운터와 달리 event_seq는 전역 gapless라 채널 내에서 sparse 단조 증가이고, 연속성 판정은 strictly-increasing(§5-2)이다.

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

**myAsset** (`/user/queue/myAsset`)
```json
{ "schema_version":"1.0","type":"myAsset","epoch":42,"seq":7203,
  "assets":[
    {"currency":"KRW","balance":"500000","locked":"300000"},
    {"currency":"BTC","balance":"0.493998","locked":"0.01"}],
  "timestamp":"2026-07-21T09:00:01.061Z" }
```
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

---

<a name="6"></a>
## 6. 관리/시뮬레이션/카오스/HA API (exchange-admin, `/admin/v1`, 분리 포트·mTLS)

> **보안(§3-6)**: 관리 API는 `scope=admin` JWT + mTLS + 비가역 액션 `operator_id`/`approval_token` + 감사 로그 + rate limit(§2-5)로 보호. 관측 데이터 내 텍스트 지시로 자동 실행 금지. 모든 페이로드 `schema_version` 포함(v2.5 각주: §6-A/§6-B/§6-C의 예시들은 §4와 동일 규약으로 **계약상 항상 포함하되 지면상 표기만 생략**한다. 기준 예시 §4-A-1).

### 6-A. 봇 제어 API

| 메서드 | 경로 | 기능 | approval_token(§3-6) |
|---|---|---|---|
| POST | `/admin/v1/sim/bots` | 봇 생성(유형·수·파라미터) | 불요 |
| DELETE | `/admin/v1/sim/bots/{id}` | 봇 제거 | 불요 |
| PATCH | `/admin/v1/sim/bots/{id}/params` | 실행 중 파라미터 변경(operator 승인) | **필수** |
| POST | `/admin/v1/sim/start` \| `/stop` \| `/pause` | 전체 제어 | 불요 |
| POST | `/admin/v1/sim/scenarios/{name}/activate` | 프리셋(steady/volatile/spike/ramp/thundering-herd) | 불요 |
| PUT | `/admin/v1/sim/rate` | 전역 목표 OPS | 불요 |
| PUT | `/admin/v1/sim/scale` | 마켓·유형별 봇 대수 set-point(count 증감 상위 제어) | 불요 |
| PUT | `/admin/v1/sim/bias` | 마켓별 목표 net bias(-1~+1)·자동균형 강도 | 불요 |
| PUT | `/admin/v1/sim/effects` | 체결 시각효과 강도(`trade_flash`)·공개 config 반영 | 불요 |
| GET | `/admin/v1/sim/status` | 봇 수·OPS·성공률·평균지연·마켓별 bias·대수(schema 1.1) | 불요 |
| WS | `/admin/v1/ws/sim/telemetry` | 실시간 텔레메트리 | 불요 |

```json
// POST /admin/v1/sim/bots  (operator_id 필수)
{ "operator_id":"op-01","type":"MARKET_MAKER","count":40,"market":"KRW-BTC",
  "params":{"base_spread_bps":"8","quote_size":"0.05","skew_factor":"0.01","lambda":2.0},
  "seed":{"base":"1.0","quote":"50000000"} }
// 201 (governor set-point 모델: 이 경로는 유형별 목표 대수를 count만큼 상향한다)
{ "schema_version":"1.0","created":40,"bot_ids":[],"fleet_total":140,
  "fleet_present":true,"applied_target":40,"clamped":false }

// GET /admin/v1/sim/status → 200 (schema 1.1, 봇_방향성 §5-5)
{ "schema_version":"1.1", "fleet_present":true, "target_ops":"20",
  "trade_flash":"normal",
  "markets":[
    { "market":"KRW-BTC",
      "target_net_bias":"0.30", "measured_net_bias":"0.22", "effective_bias":"0.24",
      "auto_balance":{"enabled":true,"strength":"0.5"},
      "scale":{"MARKET_MAKER":{"target":40,"active":40},"TAKER":{"target":30,"active":28}},
      "fill_ratio":"0.61", "trade_flash":"normal" } ] }
```
**봇 유형**: `MARKET_MAKER`(유동성·재고 스큐) / `TAKER` / `TREND`(모멘텀·역추세 이원 페르소나, 봇_방향성 §2-1-a) / `NOISE`. 코드 경로는 둘(MarketMakerBot·TakerBot)이고 TREND·NOISE는 TakerBot 프리셋으로 실현한다. 가격은 **OU 평균회귀 + 점프 프로세스**로 fair value만 제공, 체결가는 오더북에서 창발.

**`POST /sim/bots` 응답 계약(v2.11, governor set-point 반영)**: 이 경로는 개별 봇 인스턴스를 즉시 기동하지 않고 인메모리 governor의 유형별 목표 대수를 `count`만큼 상향한다(캡 클램프 적용). 실제 인스턴스는 별도 `bots` 프로파일이 목표 대수를 좇아 기동하므로 이 경로는 인스턴스 id를 발급하지 않는다. 따라서 `bot_ids`는 상시 빈 배열이다. `created`는 클램프 후 순증분, `applied_target`은 클램프 후 해당 유형 목표, `fleet_total`은 전 마켓 목표 대수 합계, `clamped`는 캡에 걸렸는지, `fleet_present`는 governor 주입 여부다. governor 부재 배포는 `count`를 그대로 `created`·`applied_target`·`fleet_total`로 보고하고 `fleet_present:false`를 준다. Cluster B는 `created`·`fleet_total`로 결과를 표시하고 `clamped`로 캡 도달을 안내한다.

**set-point 제어(v2.10, 봇_방향성 §5·체결_시각효과 §5-6 정본 반영)**: `PUT /sim/scale`·`/sim/bias`·`/sim/effects`는 개별 봇 생성/제거를 대체하지 않고 그 위에 마켓·유형 단위 목표값을 얹는 상위 제어다. 셋 다 파괴 액션이 아니므로 approval_token 불요이고 감사 로그만 적재한다(§3-6 필수 완결 열거 불변, 신규 필수 액션 없음). `/sim/effects`의 `trade_flash`는 공개 `GET /api/v1/exchange-config`(§4-A-6)에 반영된다. `GET /sim/status`는 schema를 1.1로 올려 마켓별 `target/measured/effective_bias`, `auto_balance`, 유형별 `scale{target,active}`, `fill_ratio`, `trade_flash`를 additive로 더한다(기존 1.0 소비자 무영향).

### 6-B. 카오스 주입 API

| 메서드 | 경로 | 기능 | approval_token(§3-6) |
|---|---|---|---|
| POST | `/admin/v1/chaos/network` | Toxiproxy toxic(latency/down/timeout/bandwidth/reset_peer) | **필수** |
| DELETE | `/admin/v1/chaos/network/{toxic}` | toxic 해제 | **필수** |
| POST | `/admin/v1/chaos/app` | Chaos Monkey assault(latency/exception/appkiller) | **필수** |
| POST | `/admin/v1/chaos/kill-node` | 매칭 노드 강제 종료(docker kill) | **필수** |
| GET | `/admin/v1/chaos/experiments` | 진행 중 실험·blast radius | 불요 |
| POST | `/admin/v1/approvals` | approval_token 발급(요청자와 **다른** operator의 2인 승인, v2.3 §3-6) | 불요(발급 자체) |
| GET | `/admin/v1/approvals` | 승인 원장 조회(state 필터, VIEWER+). `approval_token`은 응답에서 제외 | 불요(조회) |
| POST | `/admin/v1/approvals/{id}/reject` | ISSUED 승인 거부(`ISSUED→REJECTED`). 호출 주체는 그 행의 approver 또는 requester여야 한다(소유·반결탁) | 불요(de-escalation) |

승인 라이프사이클(§3-6): `ISSUED → CONSUMED | EXPIRED | REJECTED`. 발급·사용·만료·거부 전이는 모두 admin_audit_log에 기록된다. `GET /approvals`는 원장 프로젝션이며 `approval_token`을 절대 반환하지 않는다(토큰은 발급 응답 단일 경로의 bearer 시크릿). `reject`는 `consume`과 대칭으로 `FOR UPDATE` 행 잠금·소유 검증을 거치고 `consumed_at`을 건드리지 않는다.

```json
// POST /admin/v1/chaos/kill-node  (operator_id + approval_token 필수)
// approval_token은 단회용·TTL 5분·(action=kill-node, node=exchange-core-1) 바인딩 발급분(§3-6, POST /admin/v1/approvals)
{ "operator_id":"op-01","approval_token":"apv-8842",
  "node":"exchange-core-1","method":"docker_kill","expect_failover":true }
// 202
{ "experiment_id":"E5-2026-0721-01","killed_at":"2026-07-21T09:05:00.000Z",
  "grafana_annotation":"anno-88" }
```
**실험 카탈로그(E1~E6)**: DB 지연/단절, 예외 폭풍, AppKiller, **노드 kill→페일오버(핵심 데모)**, 폭주+kill 동시. 각 실험은 자동으로 Grafana annotation을 삽입해 지표 귀속. 페일오버 실험(E5)의 PASS 판정에는 구 리더 잔존 프로세스의 주문·취소·체결·스냅샷 쓰기 시도가 전부 0행 또는 예외로 차단되었는지 확인(불변식 ⑦, 하네스 V3 회귀 테스트)을 포함한다. (앱 계층=Chaos Monkey 3.x[SB3], 네트워크=Toxiproxy, 인프라=docker kill + restart policy.)

### 6-C. 헬스/복구 상태(RTO/RPO) API

| 메서드 | 경로 | 기능 |
|---|---|---|
| GET | `/actuator/health/liveness` | 생존 프로브 |
| GET | `/actuator/health/readiness` | 준비 프로브(페일오버 시 `REFUSING_TRAFFIC` → LB가 제외) |
| GET | `/admin/v1/ha/status` | 리더/팔로워·epoch·seq 정합 |
| GET | `/admin/v1/ha/recovery` | 최근 복구 RTO/RPO·불변식 결과 |
| GET | `/admin/v1/ha/invariants` | 불변식 7종 실시간 검증 |

```json
// GET /admin/v1/ha/status → 200
{ "role":"LEADER","node":"exchange-core-1","epoch":42,
  "last_applied_seq":88123606,"follower_lag_seq":3,"snapshot_seq":88100000,
  "matching_market_owner":{"KRW-BTC":"exchange-core-1"} }

// GET /admin/v1/ha/recovery → 200
{ "last_experiment":"E5-2026-0721-01","rto_seconds":3.2,"rpo_records":0,
  "failover":{"old_leader":"exchange-core-1","new_leader":"exchange-core-2",
              "old_epoch":42,"new_epoch":43},
  "invariants":{"sequence_gapless":true,"orders_lost":0,"duplicate_fills":0,
                "asset_conservation":true,"fill_integrity":true,"replay_state_hash_match":true,
                "fencing_violations":0},
  "verdict":"PASS" }
```
> `/admin/v1/ha/status` 실제 방출(단일 라이터 데모, 관리자 §2-3 읽기 전용): `schema_version`, `lease{holder,epoch,lease_until,leader_alive,lease_remaining_ms}`, `command_watermark`, `event_watermark`, `applied_command_watermark`, `apply_lag`(저널 head 대비 applied seq 지연), `snapshot_seq`, `trades_count`, `recovery_runs_count`, `last_failover{downtime_seconds,rto_seconds,verdict,at}`. 전부 DB 실적재분이고 시각은 §2-1 밀리초 UTC다. 이상형 예시의 `role`·`node`·`follower_lag_seq`·`matching_market_owner`는 노드 팔로워 레지스트리·복제 큐가 실재해야 하나 본 구조(단일 라이터 + 저널 재생 복구)에 없어 방출하지 않는다(미추적 필드 날조 회피). 리더 권위는 `leader_lease` DB 직조회다. 확장은 기존 필드 불변의 additive만 한다.

> `rpo_records`의 의미(v2.6): ACK 이후 유실된 저널 레코드 수. 0의 보장 범위는 §7-6 하단과 동일하다. 로컬 fsync는 단일 인스턴스의 프로세스·전원 장애까지 보장하며, failover 포함 0은 §1의 동기복제 운영 요건 충족 시 성립한다.
**불변식 7종**(모두 SQL로 즉시 증명): ①시퀀스 무결 `max(seq)-min(seq)+1==count(*)`(gapless 전제=`journal_counters` 단일 행 DB 원자 채번, §7-1 v2.6. 복수 worker의 할당·롤백·역순 커밋에서도 영구 결번 없음) ②주문 유실 0(ACK된 client_order_id ⊆ 저널·orders. 보장 범위는 §7-6 하단과 동일: 로컬 fsync는 프로세스·전원 장애까지, failover 포함 무손실은 §1 동기복제 운영 요건 충족 시) ③중복 접수·중복 체결 0(v2.4 두 축 분리·대상 테이블 명기. (i) **주문 중복 접수 0**: `command_journal`의 **주문 생성 커맨드 한정**(§7-1 부분 유니크 축과 동일, v2.5) `(user_id,client_order_id)` 그룹당 1건(=UNIQUE 위반 0), (ii) **중복 체결 0**: `trades` 기준 `trade_id` 단위 중복 행 0 **및** 주문별 `SUM(trades.volume) ≤ orders.volume`. 한 주문의 다건 부분체결(§4-B-1 `trades_count`, §4-B-2)은 정상이며, 위반은 **동일 체결 이벤트의 이중 적용**만을 뜻한다. 원장 측 이중 적용은 `ledger_transactions`의 `UNIQUE(business_event_type, business_event_id)`(§7-1)가 DB 제약으로 선차단하므로 본 불변식 SQL은 그 제약의 사후 감사다) ④**자산 보존** 이중부기 `SUM(delta)`=0(v2.6 범위 명시: 수기 검증으로 확인한 경로는 LIMIT BUY taker 단일 시나리오다. market·best·IOC·FOK·POST_ONLY·부분체결 반올림 잔차를 포함한 전 주문 유형의 커버는 V3 무작위 회귀 테스트, 즉 무작위 가격·수량·부분체결 조합에서 자산별 합 0과 FILLED 주문 락 잔량 0 검증으로 위임한다) ⑤체결 정합(체결가=maker 지정가, `bestBid<bestAsk`, 수량 보존) ⑥재생 결정론(저널 재생 상태 해시==라이브 해시. 해시 입력에 외부 id·executed_at·event_ordinal 포함, §7-2 v2.6 규약으로 ID·시각·이벤트 순서까지 재현 동일) ⑦fencing 무결(split-brain 차단): `event_journal`·`trades`·`snapshots`의 `fencing_epoch`가 커밋 시점의 `leader_lease` 승인 (holder, epoch)와 불일치하는 행 0. 리더 교체 후 구 epoch로 커밋된 자산 변경 행 0. 이 항목이 통과할 때에만 "split-brain 없음"을 주장한다(계약 정의 §7-1).

### 6-D. 마켓 구성·공지·운영자 API (관리자 설계 §3-1)

| 메서드 | 경로 | 기능 | approval_token(§3-6) |
|---|---|---|---|
| GET | `/admin/v1/config/markets` | 마켓 상태·수수료 조회(VIEWER+) | 불요 |
| POST | `/admin/v1/config/markets/{symbol}/halt` | 마켓 정지(`ACTIVE→HALTED`) | **필수** |
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
## 7. 부록

### 7-1. 핵심 DB 스키마 (복구·정합성)

| 테이블 | 역할 | 복구 필수 이유 |
|---|---|---|
| `command_journal` | **진실의 원천(WAL)**, append-only, `seq` PK(**`journal_counters` 단일 행 `UPDATE ... RETURNING` DB 원자 채번, gapless**, v2.6), `fencing_token(epoch)`, **사전 발급 주문 `external_id`(UUID, v2.7 W3) 박제 컬럼**(명령 접수 전 발급·저널 고정, §4-B-1), `UNIQUE(user_id,client_order_id)`, **주문 생성 커맨드 한정 부분 유니크 인덱스**(`WHERE command_type='NEW'`, v2.5): 취소 등 비생성 커맨드도 동일 저널에 적재되며 동일 `client_order_id`를 동반할 수 있으므로(by-client 취소) 전 커맨드 무조건 UNIQUE는 성립 불가 | 재생 원천 |
| `journal_counters` | `seq` 채번 전용 단일 행. `UPDATE ... RETURNING` 원자 채번으로 할당·롤백·역순 커밋에서도 영구 결번 없음(인메모리 카운터 재사용 금지, v2.6) | gapless 증명(§6-C ①) |
| `snapshots` | 재생 시작점(`last_applied_seq`+체크섬), `fencing_epoch` 저장, `engine_version`·`config_hash` 병기(재생 전제 조건 검증, v2.6) | RTO 단축 |
| `event_journal` | 출력 이벤트(검증·발행). `event_seq` PK, **`event_ordinal`(원인 커맨드 내 이벤트 순번) + `UNIQUE(source_cmd_seq, event_ordinal)`**, `payload_schema_version`, `engine_version`·`config_hash`·`fencing_epoch` 저장(v2.6). **`trade_id`는 `event_seq`에서 결정론 파생**되어 재구축 시 동일 | 재생 대조·read model |
| `outbox` | Kafka 신뢰 발행(같은 트랜잭션 INSERT) | dual-write 방지 |
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
| P0 | 순수 Java 단일스레드 매칭 코어 + command_journal(WAL) + Log-first + 재생 복구 | 로컬 장애 RPO=0(프로세스·전원 장애 범위, §7-6), 재생 해시 일치 |
| P1 | 스냅샷 + 핫 스탠바이 재생 | RTO 단축 측정 |
| P2 | Redisson 리더선출 + 전 쓰기 경로 fencing(`assert_current_leader`, epoch, §7-1) + Patroni 동기복제 | 구 리더 쓰기 0행(fencing 계약 하 split-brain 차단, 하네스 V3) |
| P3 | 트랜잭셔널 아웃박스 + Kafka + 이중부기 + 불변식 배치 | 자산 보존 그래프 |
| P4 | 봇 부하 + 백프레셔(유계 큐·토큰버킷) | 폭주 하 p99·거부율 |
| P5 | (벤치 후 이득 확인 시) Disruptor 파이프라인 | 처리량 벤치, zero-alloc |
| P6 | Toxiproxy/Chaos Monkey/kill 자동화 + property-based test | "유실 0·중복 0·자산합 0·해시 일치·fencing 위반 0" 표 |

### 7-4. SLO

| SLI | SLO | 측정 |
|---|---|---|
| 주문 API 가용성 | 99.9% 2xx | `http_req_failed`, `up` |
| 체결 지연 | p99<500ms, p95<200ms | Micrometer Timer → `histogram_quantile` |
| 자금 정합성 | **총자산 편차=0(불변식)** | `exchange_ledger_imbalance==0` |
| 복구 RTO | <30s(목표), 프록시 시 수초 | `exchange_failover_downtime_seconds` |

**증명 한 줄**: *"부하로 흔들고 카오스로 부숴도, 총자산 선은 수평(무손실), 체결 곡선은 수초 만에 되살아난다(자동복구)."*

### 7-5. VirtualThreads × JDBC 커넥션 풀 (기술 검증 반영)

- HikariCP `maximumPoolSize`는 CPU/DB 코어에 맞춰 **작게 고정**(예: 20~30). 가상 스레드는 수만 개여도 **DB 동시성은 풀 크기로 제한**하는 것이 핵심(무한 확장 금지).
- **pinning 회피**: JDBC 드라이버가 `synchronized` 블록에서 캐리어 스레드를 고정할 수 있으므로, 트랜잭션 경계를 짧게 유지하고 `jdk.tracePinnedThreads`로 모니터링. 풀 고갈이 p99 SLO 직결 → 부하테스트로 풀 사이즈 튜닝.

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
**보장 범위(v2.6)**: 위 RPO=0은 로컬 COMMIT fsync의 내구성에 근거하므로 단일 PostgreSQL 인스턴스의 프로세스·전원 장애 범위에서 성립한다. 비동기 standby로의 failover, 스토리지 손실, 조기 승격에서는 fsync된 커밋도 유실될 수 있다. failover를 포함한 무손실(RPO=0)은 §1 DB 자체 HA 행의 운영 요건(`synchronous_standby_names` 동기 standby 강제, 정산·저널 트랜잭션 `synchronous_commit=on`, DB 클러스터 리더 선출과 STONITH, PITR) 충족을 전제로 한다. 이 전제가 깨진 failover 후에는 "재조회 404 = 저널에 없음" 판정도 유실 구간에서 거짓 음성이 될 수 있으므로, 클라이언트 안전 재전송 계약은 동기복제 전제 하에서만 완전하다.

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
| high | Resilience4j "3.x" 오기 + SB4 미지원 | **경로를 Spring Boot 3.5.16 + resilience4j-spring-boot3 2.4.0으로 확정**(전 스택 GA 검증, 과제 "3.x" 부합). spring-kafka 3.3.x(BOM 위임)·redisson-spring-data-35 등 SB3.5 정합으로 통일(§1) |
| medium | Chaos Monkey 4.0.0 경고 반대(실제 SB4 지원) | 경고 **삭제·정정**. SB3.5 경로에선 **chaos-monkey 3.x 라인** 사용 명시(§1) |
| low | Disruptor 유지보수 정체 | **ABQ 우선 → 벤치 후 교체** 전략 유지·명문화(§1, P5) |
| low | micrometer-registry-prometheus 개별 핀 모순 | **핀 제거·BOM 위임**, 트레이싱 백엔드 **Tempo 확정**(§1) |
| missing | 앱티어 LB / 오케스트레이터 / 트레이스 백엔드 / 로그집계 / 시크릿 / 커넥션풀 | **§0-2 토폴로지에 LB(Nginx/HAProxy/Envoy)·Docker/K8s·Tempo·Loki·Secret 주입 명시**, HikariCP 사이징·pinning §7-5 신설 |

### 8-3. 최종 미결(운영 판단) 사항
- SB 3.5.16 EOL 시점 도래 시 보안 패치 경로: Resilience4j의 SB4 GA 릴리스 확인 후 SB4 이관 재검토(현재는 검증된 3.5 경로 확정).
- 시장가 매수 기준: **notional(금액) 확정**(§4-B-1). 정책 변경 시 `remaining_notional` 스키마 유지.

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
