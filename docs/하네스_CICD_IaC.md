# 고가용성 가상 암호화폐 거래소 CI/CD·IaC·관측성 설계서 (설계 B, v1.1 최종 제출본)

> 전제 계약: `C:\Users\DEV-PC\Desktop\프로젝트\docs\백엔드_DB_설계.md` **v2.17**(PG17+, V1 수동 파티션 부트스트랩 + V2 pg_partman 5.x/pg_cron 채택(adoption), V3 통합테스트 위임, `invariant_checks`/`recovery_runs` 관측 테이블, §5.4 epoch 보존 heartbeat), `C:\Users\DEV-PC\Desktop\프로젝트\docs\백엔드_API_설계.md` **v2.15**(SB 3.5.16+Java 21, 배포단위 A/B/C, §0-2 토폴로지, §3-6 admin 격리·2인 승인, §6-A/B/C 관리·카오스·HA API, §7-4 SLO).
> 설계 원칙: **정확성(증명 가능성) > 재현성(1커맨드 기동) > 성능 > 편의성**. 인프라의 임무는 두 증명 목표, ① 체결 무결(자산합 0·유실 0·중복 0), ② 자동 복구(재생·페일오버·RTO<30s)를 **CI가 매 커밋, 데모가 실시간으로 데이터로 보여주게 만드는 것**이다.
> 2026-07-21: 스타일 정리, 내용 무변경.
> 2026-07-24: §5-6 C-카탈로그에 C21~C23(전부 CRITICAL, InvariantCheck enum 실재분) 수록, InvariantViolation 라우팅 열거 갱신. 단일 권위는 검증설계 §4-1 불변(§5-6은 2차 미러).
> 2026-07-24 2차: 전제 계약 핀을 실헤더 기준으로 갱신(DB v2.5→v2.17, API v2.4→v2.15). 하네스_검증설계.md 핀(v2.17/v2.15)과 통일. §8 정정 권고 항목은 발견 시점 기록으로 존치.

---

## 0. 확정 스택 버전표 (2026-07 검증 완료)

| 계층 | 구성요소 | 확정 버전/이미지 | 근거·주의 |
|---|---|---|---|
| CI | GitHub Actions (public repo) | `checkout@v6` / `setup-java@v5`(temurin 21) / `gradle/actions/setup-gradle@v6` / `setup-node@v6`(Node 22) | ubuntu-latest = Docker 내장 → Testcontainers 무설정 |
| 레지스트리 | GHCR | `ghcr.io` + `GITHUB_TOKEN` | 별도 시크릿 불요 |
| 이미지 빌드 | buildx | `setup-buildx-action@v4` / `build-push-action@v7` / `metadata-action@v6`, 캐시 `type=gha`(scope=모듈별) | `linux/amd64` 단일 플랫폼만 |
| DB | PostgreSQL | **`postgres:17.6-bookworm` 기반 커스텀 이미지**를 CI가 빌드해 **GHCR digest로 배포**(`infra/pg/Dockerfile`: `postgresql-17-partman=5.*`·`postgresql-17-cron` apt 버전 핀) | **PG18 금지**(PGDATA 경로 변경·계약 PG17+). V2가 `CREATE EXTENSION pg_partman/pg_cron` 요구 → 순정 이미지로는 Flyway 실패. 베이스 `postgres:17`(유동 태그) 직접 사용 금지. TC·compose·CI가 **동일 GHCR digest를 공용 pull**해 드리프트 봉쇄(§1-2) |
| 캐시/브로커 | Redis / Kafka / RabbitMQ | `redis:8` / `apache/kafka:4.0.0`(KRaft 단일노드) / `rabbitmq:4-management`(+`enabled_plugins` 파일 마운트로 **rabbitmq_stomp 명시 활성**, 기본 비활성) | **Bitnami 이미지 전면 금지**(카탈로그 폐지). RabbitMQ = API§5-1 `/user/queue/**` 크로스노드 릴레이 계약상 필수 |
| LB | Nginx | `nginx:1.29` | readiness 기반 제외(API§0-2), WS 스케일 파라미터 §3-3 |
| 네트워크 카오스 | Toxiproxy | `ghcr.io/shopify/toxiproxy:2.12.0` | **노드별 프록시 4개** 상시 배선(§3-2). admin API(8474)는 **admin-net 내부 전용, 호스트 미공개**. packet loss 불가 → Pumba 보완 |
| 패킷 카오스(선택) | Pumba | `gaiaadm/pumba:1.1.7` | Windows 호스트 실행 불가 → compose 서비스로만. **WSL2 커널 sch_netem 사전 검증 필수**(§6-2 보완행), 실패 시 packet loss 실험은 범위 제외로 명시 |
| 앱 카오스 | Chaos Monkey for SB | `de.codecentric:chaos-monkey-spring-boot:3.3.0` | **3.3.0 = SB 3.5.x 정합. 4.0.0은 SB4 전용, 사용 금지.** demo 프로파일 한정 활성 + actuator 노출(§5-1) |
| 메트릭 | Micrometer + Prometheus | `prom/prometheus:v3.13.0`(LTS) | classic 히스토그램 + SLO 경계 버킷 기본 |
| 대시보드 | Grafana | `grafana/grafana:13.1.0` | 12.x EOL 라인 회피. 파일 프로비저닝, 시크릿은 datasource `$__file` 표기(§5-4) |
| 트레이스/로그 | Tempo / Loki | `grafana/tempo` / `grafana/loki` (`obs` 프로파일) | exemplar → Tempo 점프(권장) |
| 부하 | k6 | **v2.1.0** (+ Gatling 3.15 발표용 선택) | STOMP = stable `k6/websockets` 위 순수 JS(확장 빌드 0). JMeter 배제 |
| 로컬 k8s(선택) | k3d | v5.9.x | rolling 무중단 시연 전용 가산점 트랙 |
| Gradle | 9.6.1 | Configuration Cache 활성 | |
| Next.js | 16.2.x LTS | `output:'standalone'` 3-스테이지 | |

---

## 1. 저장소 구조·환경 분리

### 1-1. 디렉터리 레이아웃 (exchange-platform 모노레포)

```
exchange-platform/
├── .github/workflows/           ci-backend.yml · ci-frontend.yml · integration.yml · image.yml · nightly-slo.yml
├── common-dto/ exchange-core/ exchange-api/ exchange-admin/ bot-simulator/   (계약 §0-1 멀티모듈)
├── frontend/                    Next.js 16 (standalone)
├── infra/
│   ├── pg/
│   │   ├── Dockerfile           postgres:17.6-bookworm + partman(5.* 핀) + cron  (TC·compose 공용, GHCR digest 배포)
│   │   └── initdb/01-roles.sql  app_role/grafana_ro 롤 생성 + 기본 GRANT (§7-2 롤 분리)
│   ├── nginx/nginx.conf
│   ├── rabbitmq/enabled_plugins [rabbitmq_management,rabbitmq_stomp].
│   ├── toxiproxy/{populate.json, populate-ha.json}   노드별 프록시 / ha-db 전환용
│   ├── prometheus/{prometheus.yml, rules/exchange-recording.yml}
│   ├── patroni/                 (ha-db 오버레이 전용: patroni×2 + etcd + haproxy)
│   └── deploy/{rolling.sh, reset.sh}
├── observability/grafana/
│   ├── provisioning/{datasources,dashboards,alerting}/*.yaml
│   └── dashboards/{01-integrity,02-recovery,03-load-slo,04-system}.json
├── load/                        k6: lib/{sign.js,stomp.js} · scenarios/{steady,spike,ramp,thundering-herd}.js · slo-gate.js
├── compose.yaml  compose.ci.yaml  compose.ha-db.yaml  compose.k3d/(선택)
├── secrets/                     *.txt (git 제외, .gitignore + .dockerignore 동시 등재)
└── .env.example                 전체 변수 카탈로그(값 없음, 커밋 대상). TAG 규약 명문화(§2-4)
```

### 1-2. 환경 매트릭스와 이미지 단일 원천

| 환경 | 기동 방식 | Spring 프로파일 | DB/브로커 | 카오스 | 용도 |
|---|---|---|---|---|---|
| `local` | Gradle 직기동 + Testcontainers(@ServiceConnection 싱글턴) | `local` | TC 임시 컨테이너(**GHCR pg 이미지 동일 digest**) | Toxiproxy TC 모듈 | 개발·단위/통합 테스트 |
| `ci-slim` | `docker compose -f compose.yaml -f compose.ci.yaml --profile slim up -d --wait` | `demo` | compose(단일 api 노드, **RabbitMQ 포함**) | 미주입 | nightly k6 SLO 게이트 |
| `demo-full` | `docker compose --profile full up -d --wait` | `demo` | compose 전체(api×2) | Toxiproxy 상시 배선 + admin API | 발표·카오스 실험 E1~E6 |
| `demo-ha-db` | `--profile full` + **`-f compose.ha-db.yaml` 오버레이** | `demo` | Patroni×2+etcd+HAProxy(**단독 PG 대체**, §3-5) | + DB 페일오버 | DB HA 시연 세션 한정 |
| `k3d`(선택) | `k3d cluster create` + kubectl | `demo` | compose DB 재사용 | - | rolling 무중단 시연 |

**이미지 단일 원천 규율**: PG 커스텀 이미지는 `image.yml`이 빌드해 `ghcr.io/<org>/exchange-platform/exchange-pg:<sha>`로 푸시하고, `.env`의 `PG_IMAGE`(digest 고정)를 compose·Testcontainers(`-Dexchange.pg.image=` 시스템 프로퍼티)·CI가 **공용 참조**한다. 베이스는 `postgres:17.6-bookworm` + apt 버전 핀, "버전 드리프트 = 파티션드 IDENTITY/partman 동작 차이 = 증명 무효" 규율을 이미지 자체에도 적용한다. 앱 이미지 태그는 `.env`의 `TAG` 하나로 일원화한다.

---

## 2. CI 파이프라인 (GitHub Actions)

### 2-1. 토폴로지와 게이트 정책

```
PR      → [ci-backend]  컴파일 + 단위테스트 + ArchUnit(T3-A 결정론 정적 방어)   ~3분 목표
          [ci-frontend] lint + next build                                    (paths: frontend/**)
main    → [integration] Testcontainers 통합 = V3 실증 스위트 T1~T5            5~10분
          [image]       5종 이미지 빌드 → GHCR push (pg/api/admin/bot/frontend)
nightly → [nightly-slo] ci-slim compose 기동 + k6 thresholds 게이트 + jqwik tries 2000
```

- **PR 게이트 = 핵심 불변식·결정론**(빠르고 항상), **무거운 것(컨테이너·부하) = main/nightly**. `concurrency: cancel-in-progress`로 낭비 차단.
- **V3 스위트가 CI에 귀속되는 것이 이 설계의 핵심 서사다**: DB 문서가 "문서 단정만으로 신뢰하지 않는다"며 V3에 위임한 명제(커밋 백스톱 파티션 전파, partman 채택, 재생 결정론, fencing 원자성)를 **매 main 커밋마다 CI가 재증명**한다. T2(경계 기준 partman 채택 판정) 실패는 코드 버그가 아니라 **DB 문서 정정 트리거**로 취급한다(§8).

### 2-2. `ci-backend.yml` (PR)

```yaml
name: ci-backend
on:
  pull_request:
    paths: ['exchange-*/**','common-dto/**','bot-simulator/**','build.gradle*','settings.gradle*','gradle/**']
permissions: { contents: read }
concurrency: { group: ci-backend-${{ github.ref }}, cancel-in-progress: true }
jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '21' }     # cache 옵션 금지(아래 캐시 규칙)
      - uses: gradle/actions/setup-gradle@v6
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}   # PR=읽기전용 → 캐시 오염 방지
      - run: ./gradlew build --configuration-cache               # 단위 + ArchUnit 포함
```

**캐시 전략**: Gradle 캐시는 `setup-gradle@v6` **전담**, `setup-java`의 `cache: gradle`·수동 `actions/cache` 병행 금지(공식 문서 명시 상호 간섭). main만 캐시 쓰기, PR은 읽기 전용. Docker 레이어는 buildx `type=gha`(모듈별 `scope=`, `mode=max`). Next.js `.next/cache`만 별도 `actions/cache`(선택).

**ArchUnit 게이트(T3-A)**: `exchange-core`에서 `System.currentTimeMillis/nanoTime`, `Random/UUID.randomUUID`, `Instant.now`, `HashMap/HashSet` 필드, `double/float` 필드 금지. 재생 결정론(API§7-2)의 컴파일 타임 방어가 PR마다 강제된다.

### 2-3. `integration.yml` (main), V3 실증 스위트

```yaml
name: integration
on: { push: { branches: [main] } }
permissions: { contents: read, packages: read }
jobs:
  v3-suite:
    runs-on: ubuntu-latest          # Docker 내장, DinD/service 컨테이너 불요
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew integrationTest -Dexchange.pg.image=${{ vars.PG_IMAGE }}
```

**Testcontainers 규율(그대로 구현)**:
- PG 컨테이너 = GHCR `exchange-pg` digest(싱글턴 static, 스위트 전체 1회 기동), 기동 파라미터 `-c shared_preload_libraries=pg_cron -c cron.database_name=exchange -c timezone=UTC`.
- **마이그레이션·롤 분리**(§7-2와 동일 구조): initdb 스크립트가 `app_role`/`grafana_ro`를 생성하고, Flyway는 슈퍼유저(`postgres`)로 1회 실행. 테스트 대상 앱 DataSource는 `app_role`. **T2/T5의 REVOKE 검증(42501)은 반드시 app_role 커넥션으로 수행**한다.
- 테스트 격리 = **TRUNCATE 금지(append-only!)**, 템플릿 DB 복제. **클론 절차 명세**: ① 마이그레이션 완료된 `exchange`를 원본으로 두고 `pg_terminate_backend`로 템플릿 활성 커넥션 정리(TEMPLATE 복제는 활성 커넥션 0 요구) → ② `CREATE DATABASE test_n TEMPLATE exchange` → ③ 테스트별 동적 DataSource 재바인딩(JDBC URL의 DB명만 교체하는 `DelegatingDataSource`). **단, pg_cron은 `cron.database_name=exchange` 단일 DB에서만 동작하므로 `cron.job` 카탈로그 단언(T2-5)은 원본 exchange DB에서만 수행**하고, 클론에서는 크론이 부르는 함수를 직접 호출(`CALL partman.run_maintenance_proc()`)해 판정한다. 클론의 cron.job 행은 복사본에 불과해 단언 대상이 아니다.
- 이미지 태그 전부 핀: `redis:8`, `apache/kafka:4.0.0`, `ghcr.io/shopify/toxiproxy:2.12.0`(TC toxiproxy 모듈=SB BOM 위임, API§1 v2.4 결정 그대로). `latest` 금지. 비동기 대기 = Awaitility, sleep 금지.
- 스위트 구성: **T1** DEFERRABLE 커밋 백스톱, JDBC 수동 `commit()`에서 P0001·전체 롤백, V1 수동/partman 신규/DEFAULT 파티션 3곳 반복 · **T2** partman 채택, **이름이 아니라 `pg_get_expr(relpartbound)` 경계 기준** 겹침 0·premake 충족·REVOKE 전파(app_role 42501)·명명 규칙 기록 · **T3** 재생 결정론, jqwik 속성(로컬 50/CI 200/nightly 2000 tries) + 크래시 창 catch-up 멱등(2회째 no-op) · **T4** fencing, CyclicBarrier 동시 승격 1승자, 스테일 epoch 0행 append, **리스 갱신 epoch 불변(T4-7, DB v2.5 §5.4 heartbeat 문장의 회귀 방지)** · **T5** append-only/REVOKE/복합 FK 물리 강제.
- 판정: **T1~T5 전건 green = "V3 실증 완료"의 정의.**

### 2-4. `image.yml` (main), GHCR 5종 푸시

```yaml
name: image
on: { push: { branches: [main] } }
permissions: { contents: read, packages: write }
jobs:
  backend-images:
    runs-on: ubuntu-latest
    strategy: { matrix: { module: [exchange-api, exchange-admin, bot-simulator] } }
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew :${{ matrix.module }}:bootJar
      - uses: docker/setup-buildx-action@v4
      - uses: docker/login-action@v4
        with: { registry: ghcr.io, username: '${{ github.actor }}', password: '${{ secrets.GITHUB_TOKEN }}' }
      - uses: docker/metadata-action@v6
        id: meta
        with:
          images: ghcr.io/${{ github.repository }}/${{ matrix.module }}
          tags: |
            type=sha
            type=ref,event=branch
            type=raw,value=latest,enable={{is_default_branch}}   # latest 실제 발행, TAG 기본값과 정합
      - uses: docker/build-push-action@v7
        with:
          context: .
          file: ${{ matrix.module }}/Dockerfile
          push: true
          platforms: linux/amd64
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha,scope=${{ matrix.module }}
          cache-to: type=gha,scope=${{ matrix.module }},mode=max
  pg-image:        # infra/pg, 동일 골격. 푸시 digest를 repo variable PG_IMAGE로 갱신(워크플로 마지막 step)
    ...
  frontend-image:  # 동일 골격, context=frontend/, Node 22 setup-node cache:npm
    ...
```

**TAG 규약(`.env.example`에 명문화)**: ① `latest`는 main 최신을 항상 가리키도록 위 `type=raw` 규칙으로 **실제 발행**된다(compose 기본값 `${TAG:-latest}`가 유효해짐). ② 단 **재현성이 판정에 개입하는 경로(nightly-slo, 데모 리허설/본번)는 `TAG=sha-<pin>` 명시 주입이 규율**이고 latest는 로컬 편의 fallback일 뿐이다. nightly 워크플로는 `TAG`를 최근 main SHA로 주입한다(§2-5).

**Spring Boot Dockerfile 표준형** (`exchange-api/Dockerfile`, 3모듈 공통, `jarmode` 레이어 추출):

```dockerfile
FROM eclipse-temurin:21-jre AS extractor
WORKDIR /app
COPY exchange-api/build/libs/exchange-api.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r spring
USER spring
COPY --from=extractor /app/extracted/dependencies/ ./
COPY --from=extractor /app/extracted/spring-boot-loader/ ./
COPY --from=extractor /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor /app/extracted/application/ ./
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java","-jar","app.jar"]
```

`exchange-admin/Dockerfile`만 예외적으로 **docker-cli 패키지를 추가하지 않는다**. E5 kill-node는 CLI가 아니라 **docker-java 라이브러리(Docker Engine API, unix socket)** 로 구현한다(§6-2, 이미지 순수성 유지). Next.js는 `output:'standalone'` 3-스테이지(deps/builder/runner, non-root, `HOSTNAME=0.0.0.0`).

### 2-5. `nightly-slo.yml`, k6 SLO 게이트 (API§1 "Thresholds→exit code" 계약 이행)

```yaml
name: nightly-slo
on: { schedule: [{ cron: '0 18 * * *' }], workflow_dispatch: {} }   # KST 03:00
jobs:
  slo-gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - name: resolve TAG (최근 main SHA, latest 의존 제거)
        run: echo "TAG=sha-$(git rev-parse --short HEAD)" >> $GITHUB_ENV
      - run: docker compose -f compose.yaml -f compose.ci.yaml --profile slim up -d --wait
      - uses: grafana/setup-k6-action@v1
      - uses: grafana/run-k6-action@v1
        with: { path: load/slo-gate.js }        # thresholds 실패 = non-zero exit = 잡 실패
      - if: always()
        run: docker compose -f compose.yaml -f compose.ci.yaml --profile slim down -v
```

`slo-gate.js` thresholds(§7-4 SLO 1:1): `http_req_failed{scenario:orders} < 0.001`, `http_req_duration{scenario:orders}: p(99)<500, p(95)<200`, 커스텀 `Counter duplicate_ack==0`. CI는 slim(1노드) 축소 실행, 본 부하는 demo-full에서.

**`compose.ci.yaml` 실체(신규 명세)**, nightly 재현성의 핵심 파일:

```yaml
# compose.ci.yaml, CI 전용 오버라이드 (base compose.yaml 위에 겹침)
services:
  postgres:
    volumes: []                       # 명명 볼륨 제거
    tmpfs: [/var/lib/postgresql/data] # 일회성 실행, 디스크 I/O 제거, down -v 불요
  api-1:
    deploy: { resources: { limits: { memory: 768m } } }   # CI 러너 7GB 예산 내 축소
  prometheus:
    command: ["--storage.tsdb.retention.time=2h","--config.file=/etc/prometheus/prometheus.yml"]
  lb:      { ports: ["8080:8080"] }   # 호스트 노출은 lb 하나만 유지
  grafana: { profiles: [never] }      # CI에선 판정 주체가 k6 thresholds, 대시보드 불요
  frontend: { profiles: [never] }
  bots:     { profiles: [never] }     # 부하 원천은 k6 단독(개방 루프 순수 측정)
```

(`profiles: [never]`는 "어떤 활성 프로파일에도 안 걸리는 프로파일명" 관례, slim 기동에서 제외하는 표준 수법.)

---

## 3. 데모 토폴로지 (Docker Compose)

### 3-1. `compose.yaml` (구현 골자, `version:` 키는 obsolete, 미사용)

```yaml
name: exchange-demo

x-api-common: &api-common
  image: ghcr.io/<org>/exchange-platform/exchange-api:${TAG:-latest}
  environment: &api-env
    SPRING_PROFILES_ACTIVE: demo
    DB_HOST: toxiproxy            # 앱→toxiproxy→postgres. 포트는 노드별 상이(아래), E2/그레이 장애의 노드 선택성 확보
    REDIS_HOST: toxiproxy
    KAFKA_BOOTSTRAP: kafka:9092
    STOMP_RELAY_HOST: rabbitmq
    MANAGEMENT_SERVER_PORT: "9404"
  depends_on:
    flyway-migrate: { condition: service_completed_successfully }   # 마이그레이션 선행(§7-2)
    postgres:       { condition: service_healthy }
    redis:          { condition: service_healthy }
    toxiproxy-init: { condition: service_completed_successfully }   # 프록시 생성 완료까지 대기, 콜드 스타트 결정화
    kafka:          { condition: service_started }
    rabbitmq:       { condition: service_started }
  healthcheck:
    test: ["CMD","wget","-qO-","http://localhost:9404/actuator/health/readiness"]
    interval: 5s
    timeout: 3s
    retries: 12
    start_period: 40s
  restart: unless-stopped          # docker kill → 자동 재기동 → snapshot+journal 재생 → readiness UP
  deploy: { resources: { limits: { memory: 1g } } }   # 메모리 캡, MaxRAMPercentage=75가 이 1g 기준으로 동작
  profiles: [slim, full]

services:
  flyway-migrate:                  # Flyway 전담 잡(슈퍼유저), 앱은 spring.flyway.enabled=false (§7-2)
    image: flyway/flyway:11
    command: ["migrate"]
    environment:
      FLYWAY_URL: jdbc:postgresql://postgres:5432/exchange
      FLYWAY_USER: postgres
    volumes: ["./exchange-api/src/main/resources/db/migration:/flyway/sql:ro"]
    secrets: [pg_password]        # FLYWAY_PASSWORD_FILE 지원, /run/secrets 참조
    depends_on: { postgres: { condition: service_healthy } }
    restart: "no"
    profiles: [slim, full]

  seed:                            # 데모 시드 잡, 봇/부하 계정 + GENESIS 입금 (§7-3, 멱등)
    image: ghcr.io/<org>/exchange-platform/exchange-admin:${TAG:-latest}
    environment: { SPRING_PROFILES_ACTIVE: seed }     # 시드 전용 프로파일: 실행 후 종료(exit 0)
    depends_on:
      flyway-migrate: { condition: service_completed_successfully }
    volumes: ["./secrets:/out/secrets"]               # k6/봇 access_key 산출물 기록(git 제외 경로)
    secrets: [pg_password, master_key]
    restart: "no"
    profiles: [slim, full]

  postgres:
    image: ${PG_IMAGE}             # GHCR digest 고정(§1-2). build: 지시자는 로컬 개발 fallback으로만
    command: ["postgres","-c","shared_preload_libraries=pg_cron",
              "-c","cron.database_name=exchange","-c","timezone=UTC",
              "-c","synchronous_commit=on"]
    environment:
      POSTGRES_DB: exchange
      POSTGRES_PASSWORD_FILE: /run/secrets/pg_password
    secrets: [pg_password]
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./infra/pg/initdb:/docker-entrypoint-initdb.d:ro     # 01-roles.sql, app_role/grafana_ro 생성(§7-2)
    healthcheck: { test: ["CMD-SHELL","pg_isready -U postgres -d exchange"], interval: 5s, retries: 10, start_period: 15s }
    restart: unless-stopped
    deploy: { resources: { limits: { memory: 1536m } } }
    profiles: [slim, full]

  toxiproxy:
    image: ghcr.io/shopify/toxiproxy:2.12.0
    # 8474 호스트 포트 매핑 제거, admin API는 admin-net 내부 전용.
    #   호스트 직접 주입은 approval_token·감사로그·annotation을 우회하는 뒷문이라 계약(§3-6/§6-B) 위반.
    #   디버깅은 docker compose exec admin curl toxiproxy:8474/... 경유로 한정.
    networks: [default, admin-net]
    restart: unless-stopped
    deploy: { resources: { limits: { memory: 128m } } }
    profiles: [slim, full]
  toxiproxy-init:                  # 기동 시 프록시 사전 정의(POST /populate). 실패=non-zero exit=기동 전체 명확 실패
    image: curlimages/curl:8
    depends_on: [toxiproxy]
    volumes: ["./infra/toxiproxy/populate.json:/populate.json:ro"]
    command: ["sh","-c","for i in $(seq 1 20); do curl -sf -XPOST toxiproxy:8474/populate -d @/populate.json && exit 0; sleep 1; done; exit 1"]
    restart: "no"
    profiles: [slim, full]

  redis:
    image: redis:8
    command: ["redis-server","--maxmemory","200mb","--maxmemory-policy","noeviction"]
    healthcheck: { test: ["CMD","redis-cli","ping"], interval: 5s, retries: 10 }
    restart: unless-stopped
    deploy: { resources: { limits: { memory: 256m } } }
    profiles: [slim, full]

  kafka:
    image: apache/kafka:4.0.0      # KRaft 단일노드(controller+broker)
    environment: { KAFKA_NODE_ID: "1", KAFKA_PROCESS_ROLES: "broker,controller",
                   KAFKA_HEAP_OPTS: "-Xmx512m -Xms256m", ... }
    restart: unless-stopped
    deploy: { resources: { limits: { memory: 1g } } }
    profiles: [slim, full]

  rabbitmq:
    image: rabbitmq:4-management
    volumes: ["./infra/rabbitmq/enabled_plugins:/etc/rabbitmq/enabled_plugins:ro"]
    # enabled_plugins 내용: [rabbitmq_management,rabbitmq_stomp]. STOMP(61613)는 기본 비활성이므로 명시 활성
    restart: unless-stopped
    deploy: { resources: { limits: { memory: 512m } } }
    profiles: [slim, full]         # slim 포함, §5-1(A) 릴레이 계약을 slim에서도 동일 배선(환경 분기 최소화 원칙).
                                   #   x-api-common depends_on과의 프로파일 불일치(slim 기동 불능) 해소.

  api-1:
    <<: *api-common
    environment: { <<: *api-env, NODE_ID: exchange-api-1,
                   DB_PORT: "15432", REDIS_PORT: "16379" }    # api-1 전용 프록시 리스너
  api-2:
    <<: *api-common
    environment: { <<: *api-env, NODE_ID: exchange-api-2,
                   DB_PORT: "25432", REDIS_PORT: "26379" }    # api-2 전용 프록시 리스너
    profiles: [full]               # slim=1노드, full=2노드(리더/스탠바이는 Redisson 선출이 결정)

  lb:
    image: nginx:1.29
    volumes: ["./infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro"]
    ports: ["8080:8080"]
    depends_on: { api-1: { condition: service_healthy } }
    restart: unless-stopped
    deploy: { resources: { limits: { memory: 128m } } }
    profiles: [slim, full]

  admin:
    image: ghcr.io/<org>/exchange-platform/exchange-admin:${TAG:-latest}
    ports: ["9090:9090"]           # 분리 포트(API§3-6)
    networks: [default, admin-net] # admin-net에서만 toxiproxy:8474·docker.sock 접근
    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]   # docker-java(Engine API)로 kill-node 실행(§6-2)
    environment: { GRAFANA_URL: "http://grafana:3000", GRAFANA_SA_TOKEN_FILE: /run/secrets/grafana_sa_token }
    secrets: [grafana_sa_token, master_key]
    restart: unless-stopped
    deploy: { resources: { limits: { memory: 768m } } }
    profiles: [full]

  bots:
    image: ghcr.io/<org>/exchange-platform/bot-simulator:${TAG:-latest}
    depends_on:
      lb:   { condition: service_started }
      seed: { condition: service_completed_successfully }     # 계정 시드 후 기동, 인증 불가 문제 해소
    restart: unless-stopped        # 카오스 중에도 생존 → "외부 관점 자동 복구" 관찰(API§0-1)
    deploy: { resources: { limits: { memory: 512m } } }
    profiles: [full]

  frontend:
    image: ghcr.io/<org>/exchange-platform/frontend:${TAG:-latest}
    ports: ["3000:3000"]
    restart: unless-stopped
    deploy: { resources: { limits: { memory: 384m } } }
    profiles: [full]

  prometheus:
    image: prom/prometheus:v3.13.0
    volumes: ["./infra/prometheus:/etc/prometheus:ro", "promdata:/prometheus"]
    ports: ["9091:9090"]
    restart: unless-stopped
    deploy: { resources: { limits: { memory: 512m } } }
    profiles: [slim, full]
  grafana:
    image: grafana/grafana:13.1.0
    volumes: ["./observability/grafana/provisioning:/etc/grafana/provisioning:ro",
              "./observability/grafana/dashboards:/var/lib/grafana/dashboards:ro",
              "./secrets/grafana_ro_password.txt:/run/secrets/grafana_ro_password:ro"]
    # GF_*__FILE 방식 미사용, 그 메커니즘은 grafana.ini 설정 키 한정이라 datasource 비밀번호에 무효.
    #   pg-proof 비밀번호는 datasource 프로비저닝 YAML의 $__file 표기로 주입(§5-4).
    ports: ["3001:3000"]
    restart: unless-stopped
    deploy: { resources: { limits: { memory: 512m } } }
    profiles: [slim, full]

  tempo: { image: grafana/tempo:latest, profiles: [obs], restart: unless-stopped }
  loki:  { image: grafana/loki:latest,  profiles: [obs], restart: unless-stopped }

  pumba:                            # 선택: netem loss/corrupt. 기동 전 §6-2 사전 검증 커맨드 필수
    image: gaiaadm/pumba:1.1.7
    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
    profiles: [chaos-net]
    restart: "no"

secrets:
  pg_password:         { file: ./secrets/pg_password.txt }
  grafana_sa_token:    { file: ./secrets/grafana_sa_token.txt }
  master_key:          { file: ./secrets/master_key.txt }      # AES-GCM 봉투암호화 마스터키(API§3-2)
volumes: { pgdata: , promdata: }
networks: { admin-net: }
```

### 3-2. Toxiproxy 노드별 배선 (`infra/toxiproxy/populate.json`)

```json
{ "proxies": [
  { "name": "pg-api1",    "listen": "0.0.0.0:15432", "upstream": "postgres:5432", "enabled": true },
  { "name": "pg-api2",    "listen": "0.0.0.0:25432", "upstream": "postgres:5432", "enabled": true },
  { "name": "redis-api1", "listen": "0.0.0.0:16379", "upstream": "redis:6379",    "enabled": true },
  { "name": "redis-api2", "listen": "0.0.0.0:26379", "upstream": "redis:6379",    "enabled": true } ] }
```

**공유 프록시 2개 → 노드별 4개로 분리한 이유(핵심 수정)**: 공유 리스너로는 toxic 주입 시 api-1·api-2가 **동시에** 단절되어, E2의 판정 시나리오(리더만 Redis 상실 → Redisson 리스 만료 → **스탠바이가** fencing 승격 → 구 리더 append 0행)가 재현 불가였다. 스탠바이도 Redis를 잃으면 승격 주체가 없다. 그레이 장애(리더만 DB 단절) 역시 동일. 노드별 프록시로 **blast radius를 노드 축으로 선택**할 수 있어야 실험 카탈로그가 성립한다.

exchange-admin의 `POST /admin/v1/chaos/network`(API§6-B)는 admin-net 내부에서 toxiproxy admin API(8474)로 릴레이하며, 요청 바디에 **`target_node`(`api-1`|`api-2`|`all`) 축을 추가**해 프록시명(`pg-api1` 등)으로 매핑한다(계약 §6-B 바디에 노드 축이 없으므로 §8 정정 권고 항목). 계약 열거 toxic 전부 실재 확인: `latency`(+jitter)/`bandwidth`/`timeout`(**timeout=0=블랙홀**)/`reset_peer`/`down`(enabled=false). **packet loss는 Toxiproxy에 없음**, `chaos-net` 프로파일의 Pumba가 전담(사전 검증 §6-2). 실험 종료 훅은 프록시별 `POST /reset`.

### 3-3. `infra/nginx/nginx.conf` 핵심 (+WS 스케일 파라미터 신규)

```nginx
worker_processes auto;
worker_rlimit_nofile 65535;            # thundering-herd WS 상한의 물리 전제
events { worker_connections 16384; }   # 워커당 상한, WS 목표 동시연결(아래)과 정합

upstream exchange_api {
    server api-1:8080 max_fails=2 fail_timeout=3s;
    server api-2:8080 max_fails=2 fail_timeout=3s;
}
map $http_upgrade $connection_upgrade { default upgrade; '' close; }
server {
    listen 8080;
    location /api/ { proxy_pass http://exchange_api; proxy_next_upstream error timeout http_502 http_503; }
    location /ws/  { proxy_pass http://exchange_api;
                     proxy_http_version 1.1;
                     proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection $connection_upgrade;
                     proxy_read_timeout 70s; }      # STOMP heart-beat 10s 대비 여유
    # /actuator·/admin은 공개 LB에 라우팅하지 않는다(관리 포트 9404/9090 분리, API§3-6 정합)
}
```

**WS 동시연결 예산(명세)**: thundering-herd 시나리오 목표 = **동시 WS 5,000 연결**(k6 `vus` 상한과 1:1). nginx 여유율: 5,000 연결 × 업/다운 2 fd ≈ 10,000 fd < `worker_rlimit_nofile 65535`, 워커 1개 기준 `worker_connections 16384`로 커버. 앱 측은 노드당 3,000 연결 상한(`exchange_ws_clients` 게이지로 관찰). k6 ws 시나리오의 목표 수치는 이 예산표에서 파생하며 `load/scenarios/thundering-herd.js` 상수로 고정한다.

### 3-4. 리소스 계획 (Windows 11 + WSL2 단일 PC), compose 캡과 1:1 정합

| 서비스 | compose 메모리 캡 | 비고 |
|---|---|---|
| postgres | 1536m | synchronous_commit=on |
| kafka | 1g | KAFKA_HEAP_OPTS -Xmx512m |
| rabbitmq | 512m | |
| redis | 256m | maxmemory 200mb |
| api-1 / api-2 | 각 1g | **JVM 힙 = 캡 1g × MaxRAMPercentage 75% ≈ 768m**, 산정의 전제가 compose에 물리적으로 존재 |
| admin | 768m | |
| bots | 512m | |
| prometheus / grafana | 각 512m | |
| lb / toxiproxy / frontend | 128m/128m/384m | |
| **합계(full)** | **≈ 8.2GB** | `%UserProfile%\.wslconfig`에 `memory=12GB` 이상 |

캡이 없으면 `MaxRAMPercentage=75`가 WSL2 전체(12GB) 기준으로 동작해 폭주 부하 시 힙이 수 GB로 팽창 → OOM 킬이 **의도치 않은 노드 kill**로 카오스 판정을 오염시킨다. 캡 명시는 산정표 정합이 아니라 **실험 무결성 요건**이다. `ha-db`·`obs`·`chaos-net`은 필요 세션에만 겹쳐 기동.

### 3-5. `compose.ha-db.yaml`, DB HA 오버레이 (단독 PG 동시 기동 문제 해소)

```yaml
# demo-ha-db 세션: docker compose -f compose.yaml -f compose.ha-db.yaml --profile full up -d --wait
services:
  postgres:
    profiles: [never]              # 단독 PG를 오버레이에서 비활성, Patroni와 동시 기동 금지
  flyway-migrate:
    environment: { FLYWAY_URL: jdbc:postgresql://haproxy:5432/exchange }
    depends_on: { haproxy: { condition: service_started } }
  toxiproxy-init:
    volumes: ["./infra/toxiproxy/populate-ha.json:/populate.json:ro"]   # pg upstream → haproxy:5432
  patroni-1: { build: infra/patroni, volumes: [pgdata-ha1:/data], ... }
  patroni-2: { build: infra/patroni, volumes: [pgdata-ha2:/data], ... }
  etcd:      { image: quay.io/coreos/etcd:v3.6..., ... }
  haproxy:   { image: haproxy:3.0, ... }     # Patroni REST 헬스체크로 현 리더에만 5432 라우팅
volumes: { pgdata-ha1: , pgdata-ha2: }        # 단독 pgdata와 볼륨 분리, 데이터 계보 혼선 차단
```

`populate-ha.json`은 `pg-api1/pg-api2`의 upstream만 `haproxy:5432`로 바꾼 사본이다. 이 오버레이로 **앱 트래픽이 실제 Patroni 경로를 경유**하며, DB 페일오버 시연(Patroni 리더 kill → HAProxy 재라우팅 → 앱 Hikari 재수립)이 실질이 된다. 오버레이 없이 `--profile ha-db`만 켜는 방식은 금지(단독 PG와 동시 기동 + upstream 미전환으로 공회전).

### 3-6. 데모 리셋 표준 절차 (`infra/deploy/reset.sh` 신규)

계약 §2-4 "데모 리셋 시 네임스페이스 초기화"의 인프라 구현. **선별 리셋은 금지**. append-only 원장·저널의 부분 삭제는 계약 위반이자 증명 오염이므로, 리셋은 항상 전체 볼륨 파기다:

```bash
docker compose --profile full down -v          # pgdata·promdata 전부 파기
docker compose --profile full up -d --wait     # flyway-migrate → seed 재실행(멱등) → 새 네임스페이스
```

Grafana 대시보드는 파일 프로비저닝이라 리셋과 무관하게 재현되고, annotation 이력만 소실된다(발표 리허설과 본번 사이 의도된 초기화).

---

## 4. 무중단·페일오버 배포

### 4-1. 리더 선출과 배포 순서 계약

- 리더십 = Redisson 리더 락 + DB `leader_lease` 원자 승격(DB§5.4). **epoch+1은 리스 만료 탈취 시에만, 리스 갱신(heartbeat)은 epoch 보존 별도 UPDATE**, DB v2.5(v2.4 패치)에 이미 계약화되어 있으며, V3 T4-7이 이 문장의 회귀를 방지한다.
- **배포 순서 원칙: 팔로워 먼저.** ① `GET /admin/v1/ha/status`로 현재 리더 확인 → ② 팔로워 갱신·healthy 대기 → ③ 리더 갱신. 리더 갱신은 곧 의도된 페일오버이므로 graceful 경로(readiness=REFUSING_TRAFFIC → in-flight 완료 → 스냅샷 flush → 종료)를 태워 새 리더의 tail 재생량을 최소화한다.
- 클라이언트 관점 "무중단"의 정의(API 계약 유래): **주문 접수 실패 0 + 이중체결 0**, 5xx/타임아웃 창은 `client_order_id` 재조회 상태머신(API§7-6)으로 수렴하므로, 배포 중에도 k6가 `checks: 접수 유실 0·중복 0`을 동시 측정해 증거화한다.

### 4-2. Compose rolling 재시작 절차 (`infra/deploy/rolling.sh`)

```bash
#!/usr/bin/env bash
set -euo pipefail
TAG="$1"
leader=$(curl -s --cert op.pem --key op.key https://localhost:9090/admin/v1/ha/status | jq -r .node)
if [ "$leader" = "exchange-api-1" ]; then order=(api-2 api-1); else order=(api-1 api-2); fi
for svc in "${order[@]}"; do
  TAG="$TAG" docker compose up -d --no-deps --wait "$svc"     # --wait = healthy까지 블록
done
docker compose ps
```

정직한 한계(명시): Compose에는 네이티브 rolling이 없어 이 절차는 "순단 최소화 + 멱등 수렴"이지 엄밀한 무중단 보장이 아니다. 엄밀한 rolling 시연이 평가 항목이면 4-4의 k3d 트랙을 사용한다.

### 4-3. RTO 측정 정의, 원천 재정렬 (계약 정합 수정)

- `RTO = (신 리더 takeover_ts) − (구 리더 last_heartbeat_ts)`. 신 리더가 리더십 획득 콜백에서 **1회 계산**하고, 그 단일 계산값을 ① 게이지 `exchange_failover_downtime_seconds` set, ② `GET /admin/v1/ha/recovery`의 `rto_seconds`(API§6-C) 응답에 사용한다. **"화면 숫자 = API 숫자"의 동일 원천은 이 단일 계산점**이다.
- **DB 적재의 계약 정합(정정)**: DB v2.5의 `recovery_runs` 컬럼은 `market_id/snapshot_id/replayed_from·to_seq/fp_replay/fp_live/matched/duration_ms/created_at`뿐. **rto_seconds·rpo_records·verdict를 적재할 컬럼이 없다**. 따라서 역할을 재정렬한다: `recovery_runs` = **재생 결정론 증거**(fp 대조·matched·재생 소요)의 원천, RTO/RPO/판정 = **신규 `failover_runs` 테이블**(experiment_id, old/new_leader, old/new_epoch, last_heartbeat_ts, takeover_ts, rto_seconds, rpo_records, verdict), DB 계약 v2.6 추가를 §8-7로 정식 권고하고, 채택 전까지는 additive 관측 마이그레이션(`V90__observability_ext.sql`, 원장·저널 무접촉)으로 선반영한다. 대시보드 ②의 Table 2장(recovery_runs=재생 지문, failover_runs=RTO 이력)과 §6-C API가 각각 자기 원천을 갖는 구조로 "3중 산출물" 서사를 재정렬한다.
- 목표 분해: 인프라 재기동(restart 정책, ~수 초) + 앱 재생(snapshot+tail replay) 합산 < 30s(API§7-4). 외부 관점 RTO는 k6가 `exchange:trades_per_second:rate1m` 곡선 단절 폭으로 교차 검증(§5-3 레코딩 규칙명과 통일).

### 4-4. (선택 가산점 트랙) k3d rolling 무중단

k3d v5.9(경량, servicelb/Traefik 내장; kind는 대안 문서화만). 매니페스트 요지:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: exchange-api }
spec:
  replicas: 2
  strategy: { type: RollingUpdate, rollingUpdate: { maxUnavailable: 0, maxSurge: 1 } }
  template:
    spec:
      terminationGracePeriodSeconds: 30
      containers:
        - name: api
          image: ghcr.io/<org>/exchange-platform/exchange-api:sha-<pin>
          resources: { limits: { memory: 1Gi } }      # compose 캡과 동일 예산
          readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 9404 }, periodSeconds: 3 }
          livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: 9404 } }
          lifecycle: { preStop: { exec: { command: ["sh","-c","sleep 5"] } } }   # 엔드포인트 제거 전파 대기
```

+ `server.shutdown=graceful` + PDB `minAvailable: 1`. Spring `ReadinessState.REFUSING_TRAFFIC`이 readinessProbe DOWN으로 반영, API§0-2 문구가 문자 그대로 구현된다. 이미지는 `k3d image import` 로컬 순환(오프라인 데모 = "런타임 외부 의존 0" 정합). 증거 화면 = `kubectl rollout status deploy/exchange-api`.

---

## 5. 관측성, Micrometer → Prometheus → Grafana "증명 대시보드"

### 5-1. Actuator 설정 (exchange-api/admin 공통, SB 3.5 정확 프로퍼티)

```yaml
management:
  server: { port: 9404 }                    # 관리 포트 분리, 공개 LB 미노출
  endpoints: { web: { exposure: { include: health,info,prometheus,metrics } } }
  endpoint:
    health: { probes: { enabled: true }, show-details: never }   # /health/liveness|readiness 분리
  prometheus: { metrics: { export: { enabled: true } } }
  metrics:
    tags: { application: exchange-api, node: ${NODE_ID:exchange-api-1} }
    distribution:
      percentiles-histogram: { http.server.requests: true, exchange.order.latency: true }
      slo:
        http.server.requests: 50ms,100ms,200ms,500ms,1s
        exchange.order.latency: 10ms,50ms,100ms,200ms,500ms,1s,5s   # SLO 경계를 버킷으로 박제
      minimum-expected-value: { exchange.order.latency: 1ms }
      maximum-expected-value: { exchange.order.latency: 30s }

---   # demo 프로파일 전용 문서(E3 앱 카오스 활성, API§6-B 계약 이행)
spring.config.activate.on-profile: demo
chaos:
  monkey:
    enabled: true
    watcher: { rest-controller: true, service: true }
management.endpoints.web.exposure.include: health,info,prometheus,metrics,chaosmonkey
```

**E3 배선 정합(수정)**: admin의 `POST /admin/v1/chaos/app` 릴레이 대상인 `POST /actuator/chaosmonkey/**`가 위 demo 프로파일 문서로 **실제 노출·활성**된다(기본 노출 목록만으로는 404였음). 관리 포트 9404는 공개 LB에 라우팅되지 않고(§3-3) chaosmonkey 릴레이는 admin-net 내부에서만 이뤄지므로 §3-6 격리 원칙과 정합. `local`/CI 프로파일에서는 chaos.monkey 미활성.

원칙: **classic 히스토그램 + SLO 경계 버킷**이 기본(`le="0.5"` 직독 = 보간 없는 500ms 판정). `publishPercentiles`(클라이언트 계산)는 다노드 집계 불가, 서버측 `histogram_quantile`만 권위. exemplar(OTel bridge)로 지연 스파이크 점 → Tempo 트레이스 점프(권장). liveness 그룹에 의존성 체크 금지(재시작 루프 방지), 의존성은 readiness에만.

### 5-2. 커스텀 메트릭 명세표 (계약 §7-4 확정명과 정합)

| Micrometer 이름 | 타입 | 태그 | Prometheus 노출명 | 용도 |
|---|---|---|---|---|
| `exchange.trades` | Counter | `market` | `exchange_trades_total` | 체결/초, "체결 곡선 부활" 증명 |
| `exchange.order.latency` | Timer(+hist) | `market`,`outcome` | `exchange_order_latency_seconds_*` | 접수→체결확정 p95/p99 (SLI) |
| `exchange.orderbook.depth` | MultiGauge | `market`,`side` | `exchange_orderbook_depth` | 호가 잔량 |
| `exchange.ws.clients` | Gauge | `channel` | `exchange_ws_clients` | STOMP 연결 수(§3-3 예산 관찰) |
| `exchange.failover.downtime.seconds` | Gauge | - | `exchange_failover_downtime_seconds` | **RTO(§7-4 확정명)** |
| `exchange.leader.epoch` | Gauge | `node` | `exchange_leader_epoch` | epoch 계단(42→43) |
| `exchange.leader.is_leader` | Gauge 0/1 | `node` | `exchange_leader_is_leader` | 리더 소유권 밴드·absent 알람 |
| `exchange.ledger.imbalance` | Gauge | `asset` | `exchange_ledger_imbalance` | **자산 보존(§7-4 확정명)**, 항상 0 |
| `exchange.invariant.violations` | Gauge | `check_name` | `exchange_invariant_violations` | `invariant_checks.violated_rows` 미러, **check_name은 §5-6 C-카탈로그 표준명** |
| `exchange.duplicate.fills` | Counter | - | `exchange_duplicate_fills_total` | 중복 체결(§6-C ③), 항상 0 |
| `exchange.journal.append` | Timer | - | `exchange_journal_append_seconds_*` | fsync 지연(RPO=0 비용) |
| `exchange.journal.replay.commands` | Counter | - | `exchange_journal_replay_commands_total` | 복구 재생 처리량 |
| `exchange.sequencer.queue.size` | Gauge | - | `exchange_sequencer_queue_size` | 백프레셔(P4) |
| `exchange.orders.rejected` | Counter | `reason` | `exchange_orders_rejected_total` | 거부율(429/domain_rejected) |
| `exchange.snapshot.captured` | Counter(FunctionCounter) | - | `exchange_snapshot_captured_total` | 큐잉된 스냅샷 캡처 수(SnapshotCapture) |
| `exchange.snapshot.dropped` | Counter(FunctionCounter) | - | `exchange_snapshot_dropped_total` | 큐 포화·인코딩 실패 드롭, rate>0 알람 |
| `exchange.snapshot.persist_failed` | Counter(FunctionCounter) | - | `exchange_snapshot_persist_failed_total` | 워커 영속화 실패, rate>0 알람 |
| `exchange.snapshot.last_persisted_seq` | Gauge | - | `exchange_snapshot_last_persisted_seq` | 영속화 최신 seq, 정지=복구 tail 상한 무력화 |

자동 계측(코드 0): `http_server_requests_*`, `hikaricp_connections_active/pending`(§7-5 풀 고갈→p99 인과), `resilience4j_circuitbreaker_state`, `resilience4j_ratelimiter_available_permissions`, `spring_kafka_*`, `jvm_*`.

**카디널리티 규칙(강제)**: 태그는 유한 집합만(`market`/`side`/`node`/`reason`/`check_name`). `user_id`·`client_order_id`·`bot_id` 태그 절대 금지. 개별 주문 추적은 Tempo/Loki의 몫(exemplar가 다리).

**계측 위치(코어 순수성)**: exchange-core에 Micrometer 반입 금지. 주문 지연 Timer는 시퀀서 ACK→event_journal 소비 시점을 api 계층이 측정, 오더북 depth는 방출 이벤트의 프로젝션 Gauge, 불변식 게이지는 검증기 배치가 `invariant_checks` INSERT와 동시 갱신(별도 postgres_exporter 불요).

**미방출 항목(의도적 보류)**:
- `exchange.orderbook.depth`: 마켓 269개 × side 2 = 538 시계열이라 방출 보류. 상한/집계(전체 합 또는 top-N) 설계 확정 후 배선한다. 오더북 잔량은 시스템 대시보드 부가 신호라 무데이터 패널을 증거로 두지 않는 원칙에 따라 제외한다.
- `exchange.duplicate.fills`: InvariantVerifier의 C-카탈로그 검증(중복 체결 0)이 `exchange_invariant_violations{check_name}`로 이미 커버해 별도 Counter는 중복이라 방출하지 않는다.
- `exchange.ws.clients`: `channel` 태그는 연결 단위 계량이라 단일 값(`stomp`)만 방출한다. 목적지별 분해는 세션-구독 회계가 필요해 후속.
- `exchange.orders.rejected`: `reason`은 종결 거부(4xx: 429·422·400·403·413) 에러코드 집합이며 429는 대시보드 PromQL 정합을 위해 `rate_limited`로 정규화한다. 409·503은 재조회 수렴 신호라 거부율에서 제외한다.

### 5-3. Prometheus 구성 (`infra/prometheus/prometheus.yml`)

```yaml
global: { scrape_interval: 5s, evaluation_interval: 5s }
scrape_configs:
  - job_name: exchange-api
    metrics_path: /actuator/prometheus
    scrape_interval: 2s                 # 핵심 잡 고해상도, 페일오버 창을 2초 격자로 포착
    static_configs: [{ targets: ['api-1:9404','api-2:9404'] }]
  - job_name: exchange-admin
    metrics_path: /actuator/prometheus
    static_configs: [{ targets: ['admin:9404'] }]
rule_files: ["rules/*.yml"]
```

레코딩 규칙(`rules/exchange-recording.yml`), 대시보드·알람이 같은 식을 공유:

```yaml
groups:
- name: exchange.recording
  interval: 5s
  rules:
  - record: exchange:trades_per_second:rate1m
    expr: sum(rate(exchange_trades_total[1m]))
  - record: exchange:order_latency:p99_1m
    expr: histogram_quantile(0.99, sum by (le) (rate(exchange_order_latency_seconds_bucket[1m])))
  - record: exchange:order_latency:p95_1m
    expr: histogram_quantile(0.95, sum by (le) (rate(exchange_order_latency_seconds_bucket[1m])))
  - record: exchange:slo_500ms_ratio_1m          # SLO 경계 버킷 직독(보간 0)
    expr: sum(rate(exchange_order_latency_seconds_bucket{le="0.5"}[1m]))
          / sum(rate(exchange_order_latency_seconds_count[1m]))
```

Grafana 쿼리는 고정 `[1m]` 대신 `$__rate_interval` 사용(rate 창≥스크레이프 4배 규칙).

### 5-4. Grafana 프로비저닝 + 카오스 annotation

- 데이터소스 4종, **uid 고정**(대시보드 JSON 참조 안정성): `prometheus`, `pg-proof`(PostgreSQL, 읽기전용 `grafana_ro`, `invariant_checks`/`recovery_runs`/`failover_runs`/`admin_audit_log` SELECT만), `tempo`, `loki`. 비밀번호 주입은 **datasource YAML의 `$__file` 표기**(`GF_*__FILE` 환경변수는 grafana.ini 키 한정이라 datasource에 무효):

```yaml
# provisioning/datasources/pg-proof.yaml
datasources:
  - name: pg-proof
    uid: pg-proof
    type: postgres
    url: postgres:5432
    user: grafana_ro
    jsonData: { database: exchange, sslmode: disable }
    secureJsonData:
      password: $__file{/run/secrets/grafana_ro_password}
```

- 대시보드는 파일 프로비저닝(`allowUiUpdates:false`, 변경=파일 수정→`POST /api/admin/provisioning/dashboards/reload`).
- **카오스 annotation 규약(API§6-B `grafana_annotation` 필드 구현)**: exchange-admin이 실험 시작 시 `POST /api/annotations`(service account 토큰, epoch ms, `tags:["chaos","E5","kill-node"]`) → 응답의 정수 `id`를 `anno-{id}` 접두 표기로 계약 응답에 반환 + `admin_audit_log` 기록 → 복구 판정 시 `PATCH /api/annotations/{id}`로 `timeEnd` 채워 region 승격(실험 구간 음영). 대시보드 4장 모두 태그 구독형 annotation 쿼리 내장(`type:"tags", tags:["chaos"]` / `["failover"]`), 조직 annotation이라 전 대시보드 동일 마커 자동 표기.

### 5-5. 증명 대시보드 4장 (`observability/grafana/dashboards/`)

| # | 대시보드 | 핵심 패널 (원천) |
|---|---|---|
| ① | **체결 무결성 증명** | Stat `max(abs(exchange_ledger_imbalance))`(0=녹/≠0=적) · 자산별 imbalance 수평선 Timeseries · Stat 3연(중복 체결 0·유실 0·시퀀스 갭 0, pg-proof SQL 직조회) · Table `SELECT check_name, ok, violated_rows, checked_at FROM invariant_checks ORDER BY checked_at DESC LIMIT 20`(**DB 원본이 메트릭 미러보다 강한 증거**), 카오스 음영 위에서 전부 무결 |
| ② | **자동 복구 증명** | Gauge `exchange_failover_downtime_seconds` vs 임계 30s · State-timeline `exchange_leader_is_leader{node}`(kill 시점 리더 밴드 교대) · `exchange_leader_epoch` 계단 + kill annotation 수직선 정렬 · `exchange:trades_per_second:rate1m` 곡선 사멸→수초 부활 · **Table 2장: `recovery_runs`(fp_replay=fp_live, matched=true, 재생 결정론) + `failover_runs`(rto_seconds·rpo_records·verdict 이력, §6-C API와 동일 계산점, §4-3)** · replay rate · **복구 경로 구분(pg-proof): snapshot_head=0이면 `FULL_REPLAY_FROM_0`(적)/아니면 `SNAPSHOT_TAIL`(녹), replay_tail=command_head-snapshot_head 재생 명령 수 · 채택 가능 스냅샷 재고(버전 튜플별, 드리프트 무음 폴백 노출) · 스냅샷 캡처 파이프라인 rate(captured/dropped/persist_failed) · `exchange_snapshot_last_persisted_seq` 진행 계단** |
| ③ | **부하·SLO** | k6 Remote Write 계열(공식 대시보드 19665 병행 임포트) · `exchange:slo_500ms_ratio_1m` · p95/p99 · 429 비율(`exchange_orders_rejected_total{reason="rate_limited"}`) · `exchange_sequencer_queue_size` · Resilience4j CB/RateLimiter, "거부는 늘어도 p99·무결성은 방어" 장 |
| ④ | **시스템** | HikariCP active/pending · JVM heap/GC · `exchange_ws_clients` · orderbook depth · Kafka lag · `up`/readiness |

발표 프리셋: refresh 5s, 시간창 15m 고정. 증명 한 줄(API§7-4) "총자산 선은 수평, 체결 곡선은 수초 만에 부활"이 ①·②에 그대로 시각화된다.

### 5-6. 불변식 check_name 카탈로그(C-코드) + 알람

**카탈로그 개정(검증설계 §4-1 정합)**: 종전 표는 DB§6 ①~⑫ 직접 매핑의 구 체계(C08/C09 분리, C10=TRADE_SEQ_EVENT_MATCH, C14=BOT_ACK_SUBSET, C90 등)로 검증설계 §4-1 카탈로그·앱 검증기(InvariantCheck enum)와 코드 배정이 어긋났다. §4-1 체계로 개정한다. 단일 권위는 검증설계 §4-1이며, `invariant_checks.check_name`·게이지 태그에는 InvariantCheck enum 상수명이 그대로 실리고 심각도는 게이지 `severity` 라벨로 패스스루된다. DB 문서 §6에의 역수록 권고는 §8-8 유지:

| 코드 | check_name(표준) | 원천(검증설계 §4-1) | 심각도 | 스코프 |
|---|---|---|---|---|
| C01 | `C01_ASSET_CONSERVATION` | 자산별 원장 총합=0 (DB§6.1 ①) | **critical** | 상시 |
| C02 | `C02_BAL_VS_LEDGER` | balances = 원장 재계산 (②) | **critical** | 상시 |
| C03 | `C03_TXN_UNSEALED` | 1분 초과 OPEN 거래 (③) | high | 상시 |
| C04 | (러너 미평가) | SEALED 불균형 (④) | **critical** | DEFERRED CONSTRAINT TRIGGER 하드 강제(V1) |
| C05 | `C05_CMD_GAPLESS` | command_journal gapless (⑤) | **critical** | 상시 |
| C06 | `C06_EVT_GAPLESS` | event_journal gapless (⑥) | **critical** | 상시 |
| C07 | `C07_DUP_ORDER` | 중복 접수 0, 프로젝션 축 (⑦) | **critical** | 상시 |
| C07' | `C07P_DUP_ORDER_JOURNAL` | 중복 접수 0, 저널 축 | **critical** | 상시 |
| C08 | `C08_DUP_FILL` | 중복 체결 0 + 레지스트리 1:1 (⑧⑧') | **critical** | 상시 |
| C09 | `C09_TRADE_SEQ_EVENT_MATCH` | trades ⊆ event_journal(TRADE) (⑨) | high | 상시 |
| C10 | `C10_ORDER_FILL_AGG` | 체결 합 = filled_qty (⑩) | high | 상시 |
| C11 | `C11_TRADE_ORPHAN` | 고아 체결 (⑪) | high | 상시 |
| C12 | `C12_EVENT_ORPHAN` | 고아 이벤트 (⑫) | high | 상시 |
| C13 | `C13A_ORDER_MATERIALIZED` / `C13B_ORDER_JOURNALED` | NEW 명령 ↔ orders 1:1 | **critical** | a축 권위 run 한정 / b축 상시 |
| C14 | `C14_LOCKED_RESERVATION` | LOCKED 잔고 = 열린 주문 예약 합 | high | 상시 |
| C15 | `C15_DEFAULT_PARTITION` | DEFAULT 파티션 유입 0 | warn | 상시(가용성 전조, 무결성 위반 아님) |
| C16 | `C16_ACK_JOURNALED` | ACK 원장 ⊆ command_journal(RPO=0 외부 증거) | **critical** | 실험 사후 권위 run 한정 |
| C17 | `C17_REPLAY_DETERMINISM` | 재생 결정론 matched=true | **critical** | 상시 |
| C18 | `C18_FILL_PRICE_MAKER` | 체결가 = maker 지정가 | **critical** | 상시 |
| C19 | `C19_ORDERBOOK_NONCROSS` | bestBid < bestAsk | high | 정지 시점 run 한정 |
| C20 | `C20_ORDER_FEE_MATERIALIZED` | paid_fee·trades_count = fee leg 합 | high | 상시 |
| C21 | `C21_EVENT_EMISSION_REGISTRY` | 이벤트 방출 레지스트리 1:1 (DB§6.4 ⑮) | **critical** | 상시 |
| C22 | `C22_FENCING_MONOTONIC` | fencing_token 단조(split-brain 흔적 0, DB§6.4 ⑯) | **critical** | 권위 run 한정 |
| C23 | `C23_COUNTER_SEQ_CONSISTENCY` | 채번 카운터 next_value > 저널 최대 seq (DB§6.4 ⑰) | **critical** | 권위 run 한정 |

`exchange_invariant_violations{check_name="C01_ASSET_CONSERVATION", severity="CRITICAL"}` 형태로 노출된다.

**알람, Grafana 통합 얼럿 파일 프로비저닝**(Alertmanager 컨테이너 생략). `provisioning/alerting/rules.yaml`, 그룹 interval 10s, contact point=데모 webhook 1개:

| 알람 | 식 | 조건 | severity |
|---|---|---|---|
| `LedgerImbalance` | `max(abs(exchange_ledger_imbalance))` | `>0` for 0s | **critical**, 자산 보존 위반 즉시 |
| `DuplicateFill` | `increase(exchange_duplicate_fills_total[5m])` | `>0` | **critical** |
| `InvariantViolation` | `max by(check_name)(exchange_invariant_violations)` | `>0` for 30s | **게이지 `severity` 라벨 패스스루로 라우팅**(critical=C01·C02·C05~C08(C07' 포함)·C13·C16~C18·C21~C23 / high=C03·C09~C12·C14·C19·C20 / warn=C15. C04는 DB 트리거 강제라 게이지 미노출) |
| `OrderP99SloBreach` | `exchange:order_latency:p99_1m` | `>0.5` for 2m | warning |
| `FailoverRtoExceeded` | `max(exchange_failover_downtime_seconds)` | `>30` | critical |
| `LeaderAbsent` | `absent(exchange_leader_is_leader == 1)` | for 15s | critical |
| `SnapshotPersistFailed` | `rate(exchange_snapshot_persist_failed_total[5m])` | `>0` for 1m | warning |
| `SnapshotDropped` | `rate(exchange_snapshot_dropped_total[5m])` | `>0` for 1m | warning |
| `SnapshotStalled` | `changes(exchange_snapshot_last_persisted_seq[5m])==0 and rate(exchange_snapshot_captured_total[5m])>0` | for 2m | warning |

스냅샷 3종은 정확성 무손실이라 warning이다(다음 캡처가 대체). 지속되면 채택 스냅샷이 낡아 복구가 저널 seq 0 전량 재생으로 폴백해 RTO가 저널 크기에 비례한다. 데모 저널이 작아 전량 재생이 RTO를 통과해도 이 경보와 대시보드 ②의 복구 경로 지표가 스냅샷 경로 회귀를 드러낸다. `SnapshotStalled`는 비리더(캡처 rate 0)에서 발화하지 않는다.

CRITICAL(무결성 계열) 발화 시 앱 자동 조치 상한: **정지(HALTED + self-shutdown)·프로젝션 재빌드·멱등 재정산까지만 자동**, 원장·저널 자동 수정 금지(정정=REVERSAL+2인 승인, API§3-6).

### 5-7. Tempo/Loki (`obs` 프로파일)

Micrometer Tracing(OTel bridge)→OTLP→Tempo, Logback JSON(trace_id 포함)→Loki. 데이터소스 상관: Loki `derivedFields`로 trace_id→Tempo 링크, Prometheus exemplar→Tempo. 카오스 시 노드 간 로그 상관분석(API§1 계약)의 물리 키 = `trace_id`.

---

## 6. 부하·카오스 실행 계층

### 6-1. k6 역할 분담과 시나리오

- **역할 공식**: 봇 플릿(B) = 시장 형성 + 상시 배경 부하(폐쇄 루프, admin API 제어) / **k6 = 외부 블랙박스 SLO 계측기**(개방 루프 `ramping-arrival-rate`, coordinated omission 없는 p99). 카오스 실험은 둘 다 유지한 채 k6 thresholds가 중립 판정.
- k6 v2.1.0 확정. STOMP는 stable `k6/websockets` 위 순수 JS 프레임 구현(`load/lib/stomp.js`, ~100줄: CONNECT/SUBSCRIBE/MESSAGE/`\0` 종단), 확장 빌드 0, seq/epoch gap·`subscription_rejected` 검사 자유 코딩. xk6-stomp는 k6 v2 호환 미확인으로 예비안.
- 서명 클라이언트: `k6/crypto`(HMAC-SHA256) + JCS canonical(`load/lib/sign.js`), 봇과 동일 규약(API§3-3). **전용 부하 계정은 §7-3 seed 잡이 발급**하며 k6는 `secrets/k6_keys.json`(seed 산출물)을 읽는다. 계정 키 버킷(API§2-5) 준수, 재시도는 동일 `client_order_id`(§2-4).
- 출력: `-o experimental-prometheus-rw`(`K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9091/api/v1/write`) → 기존 Grafana 합류(추가 인프라 0) + `K6_WEB_DASHBOARD_EXPORT` HTML 리포트 보존. Gatling 3.15는 발표용 HTML 한정(WS 시나리오 배치 금지, 블로킹 체크 한계).
- 시나리오 프리셋(steady/spike/ramp/thundering-herd)은 API§6-A 봇 프리셋과 이름 대응. thundering-herd의 WS 목표 연결 수는 §3-3 예산표(5,000)에서 파생.

### 6-2. 카오스 도구 배선, 실험 카탈로그 E1~E6 매핑 (API§6-B)

| 실험 | 주입 수단(인프라 경로) | 관찰 판정 |
|---|---|---|
| E1 DB 지연/단절 | admin → toxiproxy(admin-net 내부), `pg-api1`/`pg-api2` **노드 선택** latency/timeout=0/reset_peer/down | Hikari pending↑, p99, 무결성 유지 |
| E2 Redis 장애(리더 한정) | **`redis-api{리더}` 프록시만** toxic, 스탠바이의 Redis 경로는 생존 | 리더만 Redisson 리스 만료 → **스탠바이 fencing 승격**, 구 리더 append 0행 |
| E3 예외 폭풍/지연 | admin → api 노드 `POST /actuator/chaosmonkey/**` 릴레이(admin-net, **§5-1 demo 프로파일에서 노출·활성 확인됨**) | CB open, 429/503+Retry-After 규약 |
| E4 AppKiller/자원 고갈 | Chaos Monkey AppKiller/Memory/CPU + `restart: unless-stopped` | 프로세스 수준 자동 복구(메모리 캡 §3-4 내에서) |
| E5 노드 kill(핵심) | admin이 **docker-java(Engine API, docker.sock)** 로 `exchange-demo-api-1-1` kill, CLI 미탑재 이미지에서도 실행 가능(구현 방식 확정) | restart→재생→readiness UP→LB 재편입, RTO<30s, epoch+1 |
| E6 폭주+kill 동시 | k6 spike 중첩 + E5 | 유실 0·중복 0·자산합 0·해시 일치(P6 증거 표) + C16 |
| 보완(그레이 장애) | `docker pause`(freezer), **pause는 restart 정책으로 복구 안 됨 → 실험 타이머가 unpause 자동 종료(필수 훅)** | 헬스체크 vs TCP 생존 괴리, 좀비 리더 fencing |
| 보완(패킷) | Pumba netem loss/corrupt (`chaos-net`), **사전 검증 필수**: `docker run --rm --cap-add NET_ADMIN alpine sh -c "apk add iproute2 && tc qdisc add dev eth0 root netem loss 10%"` 성공 시에만 활성. WSL2 커널 sch_netem 미탑재 시 **packet loss 실험은 범위 제외로 문서화** | Toxiproxy 공백 전담(조건부) |

모든 실험은 approval_token(2인 승인)·감사 로그·Grafana annotation 자동 삽입(§5-4)을 경유한다. 계약 §3-6/§6-B 그대로. 카오스 주입의 유일한 입구는 admin API다(toxiproxy 8474 호스트 미공개, §3-1).

### 6-3. 실험 판정 프로토콜 (불변식 검증기 연계)

① 사전 권위 run(run_id=B, REPEATABLE READ 전량) green + 워터마크 기록, **봇 ACK 기록 시작(§7-4 경로)** → ② 주입(annotation) → ③ R0~R7 자동 복구(격리→승격→스냅샷→검증 재생→tail→catch-up→불변식 권위 run→아웃박스→재개) → ④ 사후 권위 run(run_id=A) + C16(ACK 원장 ⊆ command_journal) + `recovery_runs.matched` + `failover_runs.rto_seconds`. **PASS = matched=true ∧ CRITICAL 체크(C-카탈로그) 전부 0행 ∧ rpo_records=0 ∧ rto<30s**, §6-C API·대시보드 ②·`invariant_checks(B/A)`+`failover_runs`가 §4-3의 원천 구조대로 산출된다. 검증 주체는 pg_cron(앱 다운에도 생존)과 앱 스케줄러(@Scheduled+ShedLock, 알람 발화·권위 run)의 이중화.

---

## 7. 시크릿/환경/부트스트랩 관리

### 7-1. 시크릿 계층

| 계층 | 방식 | 비고 |
|---|---|---|
| 로컬/데모 | `.env`(git 제외) + Compose `secrets:`(file → `/run/secrets/*`) | `docker inspect` 유출면에서 환경변수보다 우수. `.env.example`만 커밋(TAG 규약 포함) |
| CI | GitHub Actions Secrets + `GITHUB_TOKEN` 최소 권한(`permissions:` 전 워크플로 명시) | GHCR push 별도 자격증명 불요 |
| AES-GCM 마스터키(API§3-2) | compose=secrets 파일(`master_key`) / k3d=K8s Secret | Vault는 단일 PC 과설계, 계약 §0-2 허용 범위 |
| Grafana↔DB | `grafana_ro` 전용 롤 + datasource `$__file` 주입(§5-4) | 원장 테이블 접근 불가 |
| 유출 방지 | gitleaks pre-commit + CI 스캔, `secrets/` `.gitignore`+`.dockerignore` 동시 등재 | seed 산출물(`k6_keys.json`)도 동일 경로 |
| (선택) 팀 공유 | SOPS+age | 필요 시에만 |

비채택(근거): 클라우드 OIDC(클라우드 미사용), Vault 서버, Sealed Secrets(GitOps 컨트롤러 부재). admin 평면 시크릿(mTLS 인증서, admin_secret)은 admin-net 내에서만 마운트.

### 7-2. Flyway 실행 주체·DB 롤 분리 (신규, V2 권한 요건 충족)

V1/V2는 `CREATE EXTENSION pg_partman/pg_cron`·`REVOKE ... FROM app_role`을 수행하므로 **앱 런타임 계정으로는 실행 불가**다(권한 오류로 기동 실패). 3주체 분리로 확정한다:

| 주체 | 계정 | 역할 |
|---|---|---|
| initdb (`infra/pg/initdb/01-roles.sql`) | postgres(superuser, 이미지 엔트리포인트) | `CREATE ROLE app_role LOGIN PASSWORD ...; CREATE ROLE grafana_ro LOGIN ...;` + 스키마 기본 GRANT(SELECT/INSERT 등 최소). V1의 `REVOKE ... FROM app_role`이 참조하는 롤을 **선재**시킨다 |
| 마이그레이션 (`flyway-migrate` 잡, §3-1) | postgres(superuser) | 전 마이그레이션(V0~V10) + `V90__observability_ext.sql` 실행. CREATE EXTENSION·REVOKE·cron.schedule 전부 이 주체 |
| 런타임 | `app_role` | exchange-api/admin/bots DataSource. `spring.flyway.enabled=false`(demo 프로파일), 앱은 마이그레이션을 절대 실행하지 않는다 |
| 관측 조회 | `grafana_ro` | invariant_checks/recovery_runs/failover_runs/admin_audit_log SELECT만 |

Testcontainers도 동일 구조를 재현한다(§2-3). 로컬·CI·데모의 권한 모델이 한 장이어야 T2/T5의 42501 검증이 데모 환경의 실제 방어력을 증명한다.

**적용 완료 마이그레이션 재작성 대응(2026-07 확정)**. 원칙은 재작성 금지다(Flyway checksum 불변, 변경은 신규 버전 추가로만). V8·V10처럼 적용 완료본의 실배포 결함(타임아웃 상속·DEFAULT 재배치·TZ 의존)을 제자리 교정할 수밖에 없었던 경우는 아래 절차로 수습한다.

- 신선 배포(빈 DB): 조치 불요. 재작성본이 그대로 적용된다.
- 기존 적용 환경: 재작성본과 저장된 checksum이 어긋나 `flyway validate`가 실패한다. 1순위는 DB 재생성. 데모·카오스 DB는 seed 잡(§7-3)으로 재구축 가능한 소모품이라 재생성이 정본 경로다.
- 보존 데이터가 있는 환경 한정 flyway repair 런북: ① 재작성 diff의 실효 변경이 해당 DB에 이미 반영됐거나 무해함을 확인 ② `flyway repair`로 checksum 재정렬 ③ `flyway validate` 통과 확인 후 배포 재개.
- chaos 스택: `harness/docker-compose.chaos.yml` down 후 `chaos-pgdata` 볼륨 삭제, 재기동으로 전 체인 재적용.

### 7-3. 데모 시드/부하 계정 프로비저닝 (신규, 1커맨드 기동 완결)

`seed` 잡(§3-1, `SPRING_PROFILES_ACTIVE=seed`, 실행 후 exit 0, **멱등**, 전 INSERT `ON CONFLICT DO NOTHING` + 기시드 검사):
1. 봇 사용자 N명(`users.kind='BOT'`) + `api_keys` 발급, secret은 master_key로 AES-GCM 봉투암호화 저장(API§3-2).
2. **k6 전용 부하 계정**(계정 키 버킷 대상, API§2-5) 발급 → access_key/secret을 `/out/secrets/k6_keys.json`에 기록(git 제외 경로, k6 `open()`으로 로드).
3. GENESIS 입금 시드: `ledger_transactions(txn_type='GENESIS')` + GENESIS 계정 −x / USER_AVAILABLE +x 페어 분개(합=0), 시드조차 정본 회계 경로를 경유해 C01이 첫 순간부터 0행.
4. 마켓·자산 참조 데이터(assets/markets) upsert.

`bots`·k6는 seed 완료(`service_completed_successfully`) 후에만 기동하므로 full 프로파일 1커맨드 기동 직후 인증 실패가 구조적으로 없다.

### 7-4. 봇 ACK 기록 경로 (E6/C16 판정 배선, 검증설계 §2-5 정합 개정)

- ACK 원장 정본은 **`harness.loadtest_acks`**(검증설계 §2-5, `source='bot'|'k6'`)다. 초안의 `sim.bot_ack_log` 별도 테이블은 봇·k6 이원 원천으로 C16 판정이 갈라지므로 폐기하고 단일 테이블로 통합한다. 파일이 아닌 DB 채택 이유: C16 대조가 `command_journal`과의 **단일 SQL 조인**으로 판정 가능해야 "SQL로 즉시 증명" 원칙(API§6-C)과 정합하고, 봇 컨테이너 재시작에도 기록이 생존한다.
- C16 판정 SQL은 검증설계 §4-1 C16 그대로: `harness.loadtest_acks` LEFT JOIN `command_journal`, 0행 = ACK된 주문의 저널 유실 0. 실험 시작 훅의 `TRUNCATE harness.loadtest_acks` 선행이 전제(스테일 오탐 차단, §2-5).

---

## 8. 계약 문서 정정 권고 및 미결 (조사·검증 발견분 이관)

1. **API§1 선정표 갱신 권고**: 부하 행 "k6 2.0/Gatling 3.13+" → "**k6 2.1.0**(STOMP=순수 JS over stable `k6/websockets`, Prometheus RW 합류) + Gatling 3.15(선택)". 카오스 행 chaos-monkey "3.x" → **3.3.0 핀**(BOM 미관리 항목, 원칙 정합).
2. **API§7-1 타입 규약 `NUMERIC(36,18)`** → DB v2.5의 `NUMERIC(40,18)`로 정정 필요(§7-1 표·타입 규약 두 곳 모두, 문서 간 불일치 잔존 확인).
3. **DB§5.4 리스 갱신**: ~~문장 추가 권고~~ → **v2.4 패치로 이미 계약화 확인**(epoch 보존 heartbeat UPDATE 실재, §5.4). 정정 불요. V3 T4-7은 회귀 방지 테스트로 유지.
4. **DB§3.1 `_pYYYY_MM` 명명**: v2.5가 부트스트랩 파티션을 partman 규칙에 정렬하고 `p_start_partition` 채택 원칙을 명문화했으나, partman 5.x의 실제 접미사 규칙(`_pYYYYMMDD` 가능성)은 여전히 **V3 T2가 경계 기준으로 실증**한다. T2 실패 시 §3.2 채택 원칙을 partman 공식 기존-세트 절차로 정정.
5. SB 3.5 OSS EOL(2026-06-30) 기도래, §1 트레이드오프 논거 유지, Resilience4j SB4 GA 확인 시 이관 재검토.
6. `grafana_annotation:"anno-88"`은 Annotations API 정수 id의 접두 표기로 구현(정합), v2.5에서 정수 정리 선택 사항.
7. **신규(DB v2.6 권고): `failover_runs` 테이블 추가**, `(id, experiment_id VARCHAR, old_leader, new_leader, old_epoch BIGINT, new_epoch BIGINT, last_heartbeat_ts TIMESTAMPTZ, takeover_ts TIMESTAMPTZ, rto_seconds NUMERIC(8,3), rpo_records BIGINT, verdict VARCHAR(8), created_at)`. 현행 recovery_runs에는 RTO/RPO/verdict 컬럼이 없어 §6-C API 응답의 DB 원천이 부재했다(§4-3의 역할 재정렬 근거). 채택 전까지 `V90__observability_ext.sql`로 선반영.
8. **신규(DB §6 권고): check_name 표준 카탈로그 수록**, §5-6의 C코드 표(검증설계 §4-1 체계, C01~C20)를 DB 문서 §6에 역수록해 `invariant_checks.check_name`·심각도 분류의 계약 권위를 확립(현행은 'ASSET_CONSERVATION' 등 일부만 예시 수준).
9. **신규(API§6-B 권고): `POST /admin/v1/chaos/network` 바디에 `target_node` 축 추가**, 노드별 toxiproxy 배선(§3-2)의 API 표면. approval_token 바인딩 대상 식별자에도 target_node 포함.

---

**구현 착수 순서 요약**: ① `infra/pg/Dockerfile`+initdb 롤 스크립트 + compose slim(flyway-migrate/seed 포함) + `.env.example` → ② ci-backend/integration 워크플로(V3 T1·T5 우선) → ③ Actuator 설정(+demo 카오스 문서)+메트릭 카탈로그+Prometheus/Grafana 프로비저닝(대시보드 ①) → ④ image.yml(latest 규칙 포함)+full 프로파일(api×2 노드별 프록시 배선, LB, RabbitMQ STOMP) → ⑤ 카오스 배선(E5 docker-java 경로)+failover_runs+대시보드 ② → ⑥ k6 slo-gate+nightly(compose.ci.yaml) → ⑦ (선택) ha-db 오버레이·k3d·Pumba(사전 검증 통과 시).

---

## 반영한 검증 사항

**[high · 4/4 전부 반영]**
1. **slim 프로파일 기동 불능(rabbitmq depends_on)**: rabbitmq를 `profiles: [slim, full]`로 확장해 공통 앵커 depends_on과 프로파일 정합화(§3-1). slim에서도 §5-1(A) 릴레이 배선이 동일해 환경 분기 최소화 원칙에도 부합.
2. **`latest` 태그 영구 미발행 → GHCR pull 404**: metadata-action에 `type=raw,value=latest,enable={{is_default_branch}}` 추가(§2-4) + nightly는 `TAG=sha-<pin>` 명시 주입(§2-5) + `.env.example` TAG 규약 명문화.
3. **공유 toxiproxy 프록시로 E2 재현 불가**: 노드별 프록시 4개(`pg-api1/pg-api2/redis-api1/redis-api2`)로 분리, api-1/api-2 포트 개별 배선(§3-1/§3-2), admin chaos API에 `target_node` 축 추가(+계약 정정 권고 §8-9). E2를 "리더 프록시만 toxic"으로 재정의(§6-2).
4. **메모리 캡 부재 → MaxRAMPercentage가 WSL2 12GB 기준 동작**: 전 서비스 `deploy.resources.limits.memory` 명시, 리소스 계획표를 compose 캡과 1:1 표로 재작성(§3-4). OOM 킬에 의한 카오스 판정 오염 차단을 명분으로 명문화.

**[medium · 6/6 전부 반영]**
5. **ha-db 시 단독 PG 동시 기동·upstream 미전환**: `compose.ha-db.yaml` 오버레이 신설, postgres 비활성, `populate-ha.json`(upstream=haproxy:5432), flyway URL 전환, pgdata 볼륨 분리(§3-5).
6. **chaosmonkey 액추에이터 404**: demo 프로파일 한정 `chaos.monkey.enabled=true`+watcher+exposure 추가(§5-1), E3 배선 정합 명시(§6-2).
7. **recovery_runs에 rto_seconds/rpo_records 부재**: RTO 원천을 단일 계산점(신 리더 콜백)으로 재정의하고 `failover_runs` 신설(DB v2.6 정정 권고 §8-7 + V90 선반영), recovery_runs는 재생 결정론 증거로 역할 재정렬(§4-3, 대시보드 ② Table 2장).
8. **C01~C17 미정의 분류**: DB §6 ①~⑫(+⑧')·DEFAULT 감시를 1:1 매핑한 C01~C14·C90 카탈로그 표(check_name 표준·심각도·근거) 신설(§5-6) + DB 문서 역수록 권고(§8-8). 알람 라우팅을 이 표 기준으로 교체.
9. **api가 populate 완료를 안 기다림**: `toxiproxy-init: service_completed_successfully` depends_on 추가 + init에 재시도 루프·명확한 non-zero exit(§3-1).
10. **toxiproxy 8474 호스트 공개 = 승인 우회 뒷문**: 호스트 포트 매핑 제거, admin-net 내부 전용 + `compose exec` 디버깅 한정 명시(§3-1), "카오스 주입의 유일한 입구는 admin API" 원칙화(§6-2).

**[low · 7/7 전부 반영]** 11. E5 실행 수단을 docker-java(Engine API)로 한 줄 확정(§2-4/§6-2). 12. rabbitmq `enabled_plugins` 파일 마운트로 STOMP 명시 활성(§0/§3-1). 13. `GF_*__FILE` 오용 제거 → datasource `secureJsonData.password: $__file{...}`(§3-1/§5-4). 14. Pumba WSL2 sch_netem 사전 검증 커맨드 + 실패 시 범위 제외 문서화(§6-2). 15. TC 템플릿 클론 절차(커넥션 정리→CREATE DATABASE→동적 DataSource) 명세 + cron.job 단언은 원본 exchange DB 한정으로 T2 보정(§2-3). 16. PG 이미지를 `postgres:17.6-bookworm`+apt 핀+CI 빌드 GHCR digest 공용 pull로 드리프트 봉쇄(§0/§1-2). 17. `exchange:trades_per_second:rate1m` 명칭 참조 통일(§4-3).

**[missing · 6/6 전부 반영]** Flyway 주체·롤 분리(initdb 롤 스크립트+flyway-migrate 잡+app_role/grafana_ro 3주체 표, §7-2) · 시드/부하 계정 프로비저닝(seed 잡, GENESIS 정본 분개 경유, k6 키 산출물, §7-3) · 봇 ACK 기록 경로(`sim.bot_ack_log` DB 영속화+C14 판정 SQL, §7-4) · `compose.ci.yaml` 실체(tmpfs pgdata·자원 축소·bots/grafana 제외, §2-5) · nginx WS 스케일 파라미터(worker_rlimit_nofile/worker_connections+동시연결 예산표와 k6 목표 정합, §3-3) · 데모 리셋 표준 절차(전체 볼륨 파기 원칙, 선별 리셋 금지 근거 포함, §3-6).

**[추가 정정]** 계약 버전 표기를 실물 기준으로 교정(DB **v2.5**/API v2.4), 이에 따라 초안 §8-3(리스 갱신 문장 "추가 권고")은 "v2.4 패치로 이미 계약화 확인, 정정 불요"로 갱신했다(§8-3).

---

## 부록 S. 엔지니어링 스타일 게이트 (style-gate)

산출물에서 AI 작성 흔적을 제거하는 것이 목적이다. AI 도구 사용은 허용하되, 결과물은 시니어 엔지니어가 작성한 것과 구분되지 않아야 한다. 규칙 정본은 루트 CLAUDE.md, 기여 절차는 .github/CONTRIBUTING.md.

### S-1. 금지 목록

| 항목 | 내용 | 검출 |
|---|---|---|
| dash | em dash(U+2014), en dash(U+2013). 쉼표·마침표·가운뎃점으로 대체 | 정규식 코드포인트 |
| 이모지 | 코드, 문서, 커밋, PR 전부 | 유니코드 범위 |
| AI 어투 | "물론"+"입니다", "해 드리겠"+"습니다" 연결형 상투 표현, 과장 수식어 | 문구 목록 |
| 도구 서명 | "Generated"+" with", "Co-Authored-By:"+" Claude" 연결형 등 | 문구 목록 |
| 문체 | 문서·주석은 개조식 평서문, 존댓말 금지, 결론 반복 금지 | 리뷰 |

### S-2. 강제 지점

1. CI: .github/workflows/style.yml. PR의 변경분(추가된 줄)과 커밋 메시지를 검사한다. 위반 시 병합 차단.
2. 로컬: scripts/check-style.ps1. 커밋 전 수동 실행. CI와 같은 규칙.
3. 리뷰: 정규식으로 못 잡는 문체 위반은 리뷰어가 반려한다. PR 템플릿 체크리스트에 포함.

### S-3. 적용 범위와 예외

- 게이트는 PR 변경분에 적용한다. 게이트 도입 이전에 작성된 설계 문서(docs/*.md)는 소급 차단하지 않고 순차 정리한다.
- 규칙 정의 파일(CLAUDE.md, CONTRIBUTING.md, PR 템플릿, style.yml, check-style.ps1)은 금지 문구를 예시로 인용하므로 문구 검사만 면제한다. dash·이모지 검사는 동일하게 적용한다.
- 목업·화면설계 HTML의 UI 묘사용 그림 문자는 코드가 아니므로 제외한다(*.html 제외 패턴).

### S-4. 위반 처리

- CI 실패는 수정 후 재푸시로만 해소한다. 게이트 우회 금지.
- 반복 위반 패턴이 발견되면 금지 문구 목록에 추가하고 본 절을 갱신한다.
