# 고가용성 가상 암호화폐 거래소 CI/CD·IaC·관측성 설계서 (설계 B, v1.1 제출본)

> 전제 계약: `docs/contracts/백엔드_DB_설계.md` **v2.29**(PG17+, V1 수동 파티션 부트스트랩 + V2 pg_partman 5.x/pg_cron 채택(adoption), V3 통합테스트 위임, `invariant_checks`/`recovery_runs` 관측 테이블, §5.4 전용 리스 풀·자발적 강등·강등 등급 분리·표식 해제 지점, §2-5-a 수용 제한, V13 원가 프로젝션), `docs/contracts/백엔드_API_설계.md` **v2.20**(SB 3.5.16+Java 21, 배포단위 A/B/C, §0-2 토폴로지, §3-6 admin 격리·2인 승인, §6-A/B/C 관리·카오스·HA API, §7-4 SLO).
> 설계 원칙: **정확성(증명 가능성) > 재현성(1커맨드 기동) > 성능 > 편의성**. 인프라의 임무는 두 증명 목표, ① 체결 무결(자산합 0·유실 0·중복 0), ② 자동 복구(재생·페일오버·RTO<30s)를 **CI가 매 커밋, 데모가 실시간으로 데이터로 보여주게 만드는 것**이다.
> 2026-07-21: 스타일 정리, 내용 무변경.
> 2026-07-24: §5-6 C-카탈로그에 C21~C23(전부 CRITICAL, InvariantCheck enum 실재분) 수록, InvariantViolation 라우팅 열거 갱신. 단일 권위는 검증설계 §4-1 불변(§5-6은 2차 미러).
> 2026-07-24 2차: 전제 계약 핀을 실헤더 기준으로 갱신(DB v2.5→v2.17, API v2.4→v2.15). 하네스_검증설계.md 핀(v2.17/v2.15)과 통일. §8 정정 권고 항목은 발견 시점 기록으로 존치.
> 2026-08-07: §5-2에 `exchange.leader.recoverable_standdown` 등재(DB v2.24 §5.4 회복 가능 반납 관측 축). 종신 강등 축과 합치지 않는 근거와 판독 조합을 미방출 항목 절에 병기.
> 2026-08-07 2차: `exchange_leader_recoverable_standdown_total`을 §5-6 알람 룰과 E2 실험 판정에 배선. 종전에는 §5-2 등재와 판독 조합 PromQL만 있어 자동 판정이 아니라 수동 판독 지침이었다.
> 2026-08-18 3차: §5-6 C-카탈로그 미러에 C24·C25(원가 프로젝션 검증 뷰, 전부 warn, R5·실험 사후 권위 run 한정) 수록, InvariantViolation 라우팅 warn 열거 확장. 단일 권위는 검증설계 §4-1이다. 이 시점에는 뷰를 제공하는 V13이 병합 전이라 하네스 스크립트 조회가 미가동이었다(현행 상태는 아래 4차 노트).
> 2026-08-18 4차: V13 병합(c8eb71b)으로 두 뷰가 실재하고 앱 `InvariantCheck`가 C24·C25를 권위 run에서 평가한다(899fd48). 하네스 SQL 카탈로그 `harness/sql/invariants.sql`·`summary.sql`도 같은 뷰를 직조회하며, 그 과정에서 종전 C19에서 멈춰 있던 C20~C23과 누락돼 있던 C15까지 이식했다. 술어 정본은 앱 `InvariantCheck` enum이고 하네스 SQL은 축어 이식이다. V13 미적용 DB에서는 원가 2행이 SKIP으로 나온다(미평가를 무결로 접지 않는다).
> 2026-08-18 2차: 전제 계약 핀 상향(DB v2.17->v2.29, API v2.15->v2.20). 검증설계 핀과 통일. 인용 절 번호는 현행 정본 대조로 전건 실재 확인. DB 요약 괄호도 v2.18 이후 순증분(전용 리스 풀·자발적 강등·수용 제한·강등 등급 분리·V13)을 반영해 갱신.
> 2026-08-18: 헤더의 알람 룰 절 번호를 §5-4에서 §5-6으로 정정하고, §5-6 표에 RecoverableStandDownRepeating 행을 수록(종전에는 룰 파일에만 있어 문서 미러가 비어 있었다). 같은 신호의 임계 셋이 기저가 달라 공존하는 근거를 §5-2 미방출 항목 절과 룰 주석에 명시.

---

## 0. 확정 스택 버전표 (2026-07 검증 완료)

| 계층 | 구성요소 | 확정 버전/이미지 | 근거·주의 |
|---|---|---|---|
| CI | GitHub Actions (public repo) | `checkout@v6` / `setup-java@v5`(temurin 21) / `gradle/actions/setup-gradle@v6` / `setup-node@v6`(Node 22) | ubuntu-latest = Docker 내장 → Testcontainers 무설정 |
| 레지스트리 | GHCR | `ghcr.io` + `GITHUB_TOKEN` | 별도 시크릿 불요 |
| 이미지 빌드 | buildx | `setup-buildx-action@v3` / `build-push-action@v6` / `metadata-action@v5`, 캐시 `type=gha` | `linux/amd64,linux/arm64` 둘. **v2.x 개정(2026-08-30)**: 배포 대상이 t4g 계열(AWS Graviton, ARM64)이라 amd64 단독이면 EC2에서 exec format error로 컨테이너가 뜨지 않는다. app.jar는 아키텍처 독립이라 차이는 베이스 레이어뿐이다. 액션 버전도 실제 사용값으로 정정한다(종전 v4/v7/v6은 실재하지 않는 태그였다) |
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

`exchange-admin/Dockerfile`만 예외적으로 **docker-cli 패키지를 추가하지 않는다**. 관리 서비스는 Docker를 조작하지 않기 때문이다(§6-2 명령 소비기 모델). 거래·관리 프로세스에 docker.sock을 주면 그 프로세스의 원격 코드 실행 하나가 호스트 장악이 되고, 리더 kill 실험에서는 명령을 받은 프로세스가 그 명령으로 사라져 결과를 기록할 주체가 없어진다. 주입은 별도 소비기가 수행한다. Next.js는 `output:'standalone'` 3-스테이지(deps/builder/runner, non-root, `HOSTNAME=0.0.0.0`).

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
    MANAGEMENT_SERVER_PORT: "8081"
  depends_on:
    flyway-migrate: { condition: service_completed_successfully }   # 마이그레이션 선행(§7-2)
    postgres:       { condition: service_healthy }
    redis:          { condition: service_healthy }
    toxiproxy-init: { condition: service_completed_successfully }   # 프록시 생성 완료까지 대기, 콜드 스타트 결정화
    kafka:          { condition: service_started }
    rabbitmq:       { condition: service_started }
  healthcheck:
    test: ["CMD","wget","-qO-","http://localhost:8081/actuator/health/readiness"]
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
    # /actuator·/admin은 공개 LB에 라우팅하지 않는다(관리 포트 8081/9090 분리, API§3-6 정합)
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
- **DB 적재의 계약 정합(정정)**: DB v2.37의 `recovery_runs` 컬럼은 `market_id/snapshot_id/replayed_from·to_seq/fp_replay/fp_live/matched/duration_ms/created_at`뿐. **rto_seconds·rpo_records·verdict를 적재할 컬럼이 없다**. 따라서 역할을 재정렬한다: `recovery_runs` = **재생 결정론 증거**(`matched`·재생 창·재생 소요)의 원천. 판정은 `ReplayService.rebuild`의 이벤트 단위 대조이고 fp 두 컬럼은 창 식별자다(DB§5.5 v2.37), RTO/RPO/판정 = **신규 `failover_runs` 테이블**(experiment_id, old/new_leader, old/new_epoch, last_heartbeat_ts, takeover_ts, rto_seconds, rpo_records, verdict), DB 계약 v2.37 추가를 §8-7로 정식 권고하고, 채택 전까지는 additive 관측 마이그레이션(`V90__observability_ext.sql`, 원장·저널 무접촉)으로 선반영한다. 대시보드 ②의 Table 2장(recovery_runs=재생 결정론 `matched`, failover_runs=RTO 이력)과 §6-C API가 각각 자기 원천을 갖는 구조로 "3중 산출물" 서사를 재정렬한다.
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
          readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8081 }, periodSeconds: 3 }
          livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: 8081 } }
          lifecycle: { preStop: { exec: { command: ["sh","-c","sleep 5"] } } }   # 엔드포인트 제거 전파 대기
```

+ `server.shutdown=graceful` + PDB `minAvailable: 1`. Spring `ReadinessState.REFUSING_TRAFFIC`이 readinessProbe DOWN으로 반영, API§0-2 문구가 문자 그대로 구현된다. 이미지는 `k3d image import` 로컬 순환(오프라인 데모 = "런타임 외부 의존 0" 정합). 증거 화면 = `kubectl rollout status deploy/exchange-api`.

---

## 5. 관측성, Micrometer → Prometheus → Grafana "증명 대시보드"

### 5-1. Actuator 설정 (exchange-api/admin 공통, SB 3.5 정확 프로퍼티)

```yaml
management:
  server: { port: 8081 }                    # 관리 포트 분리(코드 실값 application.yml:165), 공개 LB 미노출
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

**E3 배선 정합(수정)**: admin의 `POST /admin/v1/chaos/app` 릴레이 대상인 `POST /actuator/chaosmonkey/**`가 위 demo 프로파일 문서로 **실제 노출·활성**된다(기본 노출 목록만으로는 404였음). 관리 포트 8081는 공개 LB에 라우팅되지 않고(§3-3) chaosmonkey 릴레이는 admin-net 내부에서만 이뤄지므로 §3-6 격리 원칙과 정합. `local`/CI 프로파일에서는 chaos.monkey 미활성.

원칙: **classic 히스토그램 + SLO 경계 버킷**이 기본(`le="0.5"` 직독 = 보간 없는 500ms 판정). `publishPercentiles`(클라이언트 계산)는 다노드 집계 불가, 서버측 `histogram_quantile`만 권위. exemplar(OTel bridge)로 지연 스파이크 점 → Tempo 트레이스 점프(권장). liveness 그룹에 의존성 체크 금지(재시작 루프 방지), 의존성은 readiness에만.

### 5-2. 커스텀 메트릭 명세표 (계약 §7-4 확정명과 정합)

| Micrometer 이름 | 타입 | 태그 | Prometheus 노출명 | 용도 |
|---|---|---|---|---|
| `exchange.trades` | Counter | `market` | `exchange_trades_total` | 체결/초, "체결 곡선 부활" 증명 |
| `exchange.order.latency` | Timer(+hist) | `market`,`outcome` | `exchange_order_latency_seconds_*` | 접수에서 체결확정 발행까지의 전파 지연. **§2-3 주문 API SLO의 판정 축이 아니다**(그것은 k6 `accepted_req_duration`, 검증설계 §2-3 각주). 이름이 비슷해 혼동을 부른 자리다 |
| `exchange.orderbook.depth` | MultiGauge | `market`,`side` | `exchange_orderbook_depth` | 호가 잔량 |
| `exchange.ws.clients` | Gauge | `channel` | `exchange_ws_clients` | STOMP 연결 수(§3-3 예산 관찰) |
| `exchange.failover.downtime.seconds` | Gauge | - | `exchange_failover_downtime_seconds` | **RTO(§7-4 확정명)** |
| `exchange.leader.epoch` | Gauge | `node` | `exchange_leader_epoch` | epoch 계단(42→43) |
| `exchange.leader.is_leader` | Gauge 0/1 | `node` | `exchange_leader_is_leader` | 리더 소유권 밴드·absent 알람 |
| `exchange.node.serviceable` | Gauge 0/1 | - | `exchange_node_serviceable` | 리더 일 수행 가능(1)·서비스 불능 지속(0). 좀비 리더 판정(DB§5.4) |
| `exchange.leader.standdown` | Counter | - | `exchange_leader_standdown_total` | 서비스 불능 지속으로 리스 갱신을 포기한 횟수(종신 강등). E1은 증가 0, E7'은 1 |
| `exchange.leader.recoverable_standdown` | Counter | - | `exchange_leader_recoverable_standdown_total` | 승격 재생 실패로 리더십을 회복 가능하게 반납한 횟수(DB§5.4 강등 등급 분리). E2 페일오버는 0~1, 지속 증가는 재생 불가 데이터 신호 |
| `exchange.node.write_completion_ratio` | Gauge | - | `exchange_node_write_completion_ratio` | 누적 쓰기 커밋률(append·정산 커밋/주문 쓰기 요청). 좀비 판정 축(DB§5.4). 실측 정상 2.4~3.9, 좀비 0.04, 임계 0.05 |
| `exchange.ledger.imbalance` | Gauge | `asset` | `exchange_ledger_imbalance` | **자산 보존(§7-4 확정명)**, 항상 0 |
| `exchange.invariant.violations` | Gauge | `check_name` | `exchange_invariant_violations` | `invariant_checks.violated_rows` 미러, **check_name은 §5-6 C-카탈로그 표준명** |
| `exchange.duplicate.fills` | Counter | - | `exchange_duplicate_fills_total` | 중복 체결(§6-C ③), 항상 0 |
| `exchange.journal.append` | Timer | - | `exchange_journal_append_seconds_*` | fsync 지연(RPO=0 비용) |
| `exchange.journal.replay.commands` | Counter | - | `exchange_journal_replay_commands_total` | 복구 재생 처리량 |
| `exchange.sequencer.queue.size` | Gauge | - | `exchange_sequencer_queue_size` | 백프레셔(P4) |
| `exchange.orders.rejected` | Counter | `reason` | `exchange_orders_rejected_total` | 거부율(429/domain_rejected) |
| `exchange.admission.rejected` | Counter | `scope` | `exchange_admission_rejected_total` | 수용 제한으로 처리 이전에 거절·보류한 요청 수(API §2-5-a). 백프레셔 발동 축 |
| `exchange.admission.in_flight` | Gauge | `scope` | `exchange_admission_in_flight` | 처리 중인 요청 수 |
| `exchange.admission.limit` | Gauge | `scope` | `exchange_admission_limit` | 동시 처리 상한(`order`·`query`는 요청 풀의 40%, `requery`는 10%). 포화도 = in_flight/limit |
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
- `exchange.orders.rejected`: `reason`은 종결 거부(4xx: 429·422·400·403·413) 에러코드 집합이며 429는 대시보드 PromQL 정합을 위해 `rate_limited`로 정규화한다. 409·503은 재조회 수렴 신호라 거부율에서 제외한다. **`OrderRejectSpike` 임계 1/s의 유도**: 값은 확정 스파이크 상한 100 req/s(검증설계 §2-4)의 1%다. 제외 목록을 뺀 잔여 사유의 정상 기대값이 0이므로 임계의 역할은 상한 배분이 아니라 노이즈 바닥 위에 선을 긋는 것이다. `rate1m`에서 단발 1건은 1/60 = 0.017/s이므로 1/s는 그 60배이고, `for: 2m`과 겹쳐 1분 60건이 2분 지속돼야 발화한다. 일회성 오타·재시도 한 건으로는 뜨지 않고 클라이언트 버그·인증 이상만 남는다. 종전 10/s는 명목 스파이크 3000/s의 0.33%로 유도한 값이라 상한 확정 후에는 스파이크 도착률의 10%·노드 수용 천장 40/s의 25%가 되어 조기 경보가 아니라 사후 확인이 됐다. 유도 기저가 바뀌면 값도 함께 재산정한다.
- `exchange.leader.standdown` / `exchange.leader.recoverable_standdown`: 두 축을 합치지 않는다. 전자는 종신 강등(복귀는 재기동)이고 후자는 승격 재생 실패의 회복 가능 반납(만료 후 같은 노드 재승격 허용)이라, 합치면 [재생 실패 -> 반납 -> 재승격]의 반복이 자발적 강등 1회와 구분되지 않는다. 반복 자체는 `exchange_leader_epoch` 계단으로도 정상 페일오버와 갈리지 않는다. 라이브 판독 조합은 `increase(exchange_leader_recoverable_standdown_total[5m]) > 1 and exchange_leader_is_leader == 0`이며, 참이면 그 노드의 재생이 결정적으로 실패한다는 뜻이라 데이터 쪽을 본다. **같은 신호에 임계가 셋 공존하는데 기저가 달라서 통일하지 않는다.** 여기 판독 조합과 E2 실험 게이트는 `> 1`이고 §5-6 알람 룰만 `> 2`다. 실험 게이트는 주입 전후 델타, 즉 한 페일오버 사건의 실측 반납 횟수를 세므로 정상 상한 1을 그대로 임계로 쓴다. 알람 룰의 `increase()`는 5분 창의 외삽 추정치다. 창 안 첫 표본이 0이 아니고 표본 구간이 창보다 짧으면 증가 위치와 무관하게 계수가 실증분보다 커진다(실측: 1회 증가에 1.0526). `> 1`로 두면 정상 페일오버 1회가 critical을 발화시킨다. `> 2`의 마진은 그 외삽 오차를 흡수하려는 것이지 정상 상한이 2라는 뜻이 아니다. 뒤집어 말하면 이 룰은 실증분 2회에서 발화한다(실측 2.1053). 알람 문구도 그렇게 읽어야 한다. 판독 조합이 `> 1`인 것은 사람이 `exchange_leader_is_leader == 0` 동반 조건과 함께 읽는 수동 축이라 오탐 비용이 낮기 때문이다. **따라서 일괄 `> 1` 통일은 권장하지 않는다.** 노출명 정합은 `AdmissionIntegrationTest.prometheusExposesContractedMeterNames`가 스크레이프 본문으로 고정한다(익스포트를 켠 컨텍스트가 그것뿐이라 §5-2 노출명 검증의 단일 고정점으로 둔다). 레지스트리 조회만으로는 점 표기와 스크레이프명의 불일치가 잡히지 않아 게이트가 공허 통과한다.
- `exchange.admission.*`: 수용 제한은 인증 이전(AdmissionFilter, order 6)에서 거절하므로 컨트롤러 계층의 `exchange.orders.rejected`에 잡히지 않는다. 의도적 분리다. 후자는 '클라이언트가 요청을 고쳐야 하는 종결 거부'이고 전자는 '같은 요청을 그대로 재시도하면 되는 노드 포화'라 한 시계열에 섞으면 거부율의 의미가 무너진다. `scope`는 `order`·`query`·`requery` 3값 고정이다. 태그 없이 조회하면 세 시계열이 따로 반환되므로 총량은 `sum(...)`으로 집계한다. 라이브 판독의 핵심 조합은 버킷별 포화도 `exchange_admission_in_flight / exchange_admission_limit`와 거절률 `rate(exchange_admission_rejected_total[1m])`이다. 판독 주의 둘. (1) `scope=query`는 주문 쓰기가 아닌 쓰기(로그인·게시글 작성)도 포함하므로 순수 조회 지표가 아니다. (2) `scope=requery`의 증가는 HTTP 429가 아니라 `409 requery_deferred`(판정 보류)를 뜻한다. 다른 두 버킷과 달리 종결 거절이 아니며 k6의 `idempotent_safe` 판정에서 안전으로 계상된다.

### 5-3. Prometheus 구성 (`infra/prometheus/prometheus.yml`)

```yaml
global: { scrape_interval: 5s, evaluation_interval: 5s }
scrape_configs:
  - job_name: exchange-api
    metrics_path: /actuator/prometheus
    scrape_interval: 2s                 # 핵심 잡 고해상도, 페일오버 창을 2초 격자로 포착
    static_configs: [{ targets: ['api-1:8081','api-2:8081'] }]
  - job_name: exchange-admin
    metrics_path: /actuator/prometheus
    static_configs: [{ targets: ['admin:8081'] }]
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

### 5-5. 증명 대시보드 5장 (`observability/grafana/dashboards/`)

| # | 대시보드 | 핵심 패널 (원천) |
|---|---|---|
| ① | **체결 무결성 증명** | Stat `max(abs(exchange_ledger_imbalance))`(0=녹/≠0=적) · 자산별 imbalance 수평선 Timeseries · Stat 3연(중복 체결 0·유실 0·시퀀스 갭 0, pg-proof SQL 직조회) · Table `SELECT check_name, ok, violated_rows, checked_at FROM invariant_checks ORDER BY checked_at DESC LIMIT 20`(**DB 원본이 메트릭 미러보다 강한 증거**), 카오스 음영 위에서 전부 무결 |
| ② | **자동 복구 증명** | Gauge `exchange_failover_downtime_seconds` vs 임계 30s · State-timeline `exchange_leader_is_leader{node}`(kill 시점 리더 밴드 교대) · `exchange_leader_epoch` 계단 + kill annotation 수직선 정렬 · `exchange:trades_per_second:rate1m` 곡선 사멸→수초 부활 · **Table 2장: `recovery_runs`(matched=true. 결정론 판정은 ReplayService가 재생 이벤트를 event_journal 행과 하나씩 대조한 결과다. fp_replay·fp_live는 재생 창 식별자이지 대조 대상이 아니다. 기록자가 성공 경로에서 두 값을 같게 적으므로 그 비교로는 항상 참이다) + `failover_runs`(rto_seconds·rpo_records·verdict 이력, §6-C API와 동일 계산점, §4-3)** · replay rate · **복구 경로 구분(pg-proof): snapshot_head=0이면 `FULL_REPLAY_FROM_0`(적)/아니면 `SNAPSHOT_TAIL`(녹), replay_tail=command_head-snapshot_head 재생 명령 수 · 채택 가능 스냅샷 재고(버전 튜플별, 드리프트 무음 폴백 노출) · 스냅샷 캡처 파이프라인 rate(captured/dropped/persist_failed) · `exchange_snapshot_last_persisted_seq` 진행 계단** |
| ③ | **부하·SLO** | k6 Remote Write 계열(공식 대시보드 19665 병행 임포트) · `exchange:slo_500ms_ratio_1m` · p95/p99 · 429 비율(`exchange_orders_rejected_total{reason="rate_limited"}`) · `exchange_sequencer_queue_size` · Resilience4j CB/RateLimiter, "거부는 늘어도 p99·무결성은 방어" 장 |
| ④ | **시스템** | HikariCP active/pending · JVM heap/GC · `exchange_ws_clients` · orderbook depth · Kafka lag · `up`/readiness |
| ⑤ | **운영 상황판** | Stat 4연(거래소 `up` · 봇 플릿 `up` · 리더 존재 · 초당 체결) · 주문 접수율 vs 용량 상한 20/s · p95·p99 vs 2s · 시퀀서 큐 vs 임계 50 · HikariCP 대기·사용·상한 · CPU(발화하지 않는 지표임을 설명에 명시) · 수용 게이트 in-flight vs limit · 거절률 · 노드별 스레드와 WS. 임계는 전부 2026-09-03 실측 유도값이고 축 하한을 주어 평시에도 기준선이 보인다 |

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
| C24 | `C24_POSITION_COST_QTY` | 원가 수량 보존: cost_qty + unknown_qty = 실보유 (DB§6.4 ⑱, 뷰 `position_cost_qty_check`) | warn | R5·실험 사후 권위 run 한정 |
| C25 | `C25_POSITION_COST_SANITY` | 원가 누산기 정합: 부호·쌍·워터마크·통화 (DB§6.4 ⑲, 뷰 `position_cost_sanity_check`) | warn | R5·실험 사후 권위 run 한정 |

`exchange_invariant_violations{check_name="C01_ASSET_CONSERVATION", severity="CRITICAL"}` 형태로 노출된다.

**알람, Grafana 통합 얼럿 파일 프로비저닝**(Alertmanager 컨테이너 생략). `provisioning/alerting/rules.yaml`, 그룹 interval 10s, contact point=데모 webhook 1개:

| 알람 | 식 | 조건 | severity |
|---|---|---|---|
| `LedgerImbalance` | `max(abs(exchange_ledger_imbalance))` | `>0` for 0s | **critical**, 자산 보존 위반 즉시 |
| `DuplicateFill` | `increase(exchange_duplicate_fills_total[5m])` | `>0` | **critical** |
| `InvariantViolation` | `max by(check_name)(exchange_invariant_violations)` | `>0` for 30s | **게이지 `severity` 라벨 패스스루로 라우팅**(critical=C01·C02·C05~C08(C07' 포함)·C13·C16~C18·C21~C23 / high=C03·C09~C12·C14·C19·C20 / warn=C15·C24·C25. C04는 DB 트리거 강제라 게이지 미노출) |
| `OrderP99SloBreach` | `exchange:order_latency:p99_1m` | `>0.5` for 2m | warning 체결 전파 지연 경보다. §2-3 주문 API SLO 판정과 다른 축이므로 이 경보 발화가 곧 SLO 위반은 아니다 |
| `FailoverRtoExceeded` | `max(exchange_failover_downtime_seconds)` | `>30` | critical |
| `LeaderAbsent` | `absent(exchange_leader_is_leader == 1)` | for 15s | critical |
| `RecoverableStandDownRepeating` | `increase(exchange_leader_recoverable_standdown_total[5m])` | `>2` for 0s | **critical** |
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

**실행 경로(v2.36 확정)**. 관리 API는 주입을 수행하지 않고 지시만 남긴다. `POST /admin/v1/chaos/*`가 승인 토큰을 소각하고 `chaos_commands`(V21)에 `PENDING` 행을 적재하면, 하네스 소비기가 `FOR UPDATE SKIP LOCKED`로 한 건을 점유해 실제 주입을 수행하고 컨테이너 id·상태를 `result`에 되쓴다. 콘솔은 `GET /admin/v1/chaos/runs`의 `exec_state`로 지시가 집행됐는지 본다.

감사 로그를 큐로 쓰지 않는 이유가 둘이다. `admin_audit_log`는 append-only라(V5 `forbid_mutation`) 처리 표시를 적을 자리가 없고, 자리가 없으면 소비기 재시작이 같은 지시를 다시 읽어 리더를 두 번 죽인다. 기록과 작업 지시는 다른 것이다.

소비기가 별도 프로세스인 것이 E4·E5의 전제다. 대상이 죽어도 소비기는 살아서 증빙을 남긴다. 소비기는 주입 원시연산만 수행하고, 실험 전체 프로토콜(사전 권위 run → 주입 → 무개입 관찰 → 사후 판정)은 `e1`~`e9` 스크립트가 계속 담당한다.

| 실험 | 주입 수단(인프라 경로) | 관찰 판정 |
|---|---|---|
| E1 DB 지연/단절 | admin → toxiproxy(admin-net 내부), `pg-api1`/`pg-api2` **노드 선택** latency/timeout=0/reset_peer/down | Hikari pending↑, p99, 무결성 유지 |
| E2 Redis 장애(리더 한정) | **`redis-api{리더}` 프록시만** toxic, 스탠바이의 Redis 경로는 생존 | 리더만 Redisson 리스 만료 → **스탠바이 fencing 승격**, 구 리더 append 0행 |
| E3 예외 폭풍/지연 | admin → api 노드 `POST /actuator/chaosmonkey/**` 릴레이(admin-net, **§5-1 demo 프로파일에서 노출·활성 확인됨**) | CB open, 429/503+Retry-After 규약 |
| E4 AppKiller/자원 고갈 | Chaos Monkey AppKiller/Memory/CPU + `restart: unless-stopped` | 프로세스 수준 자동 복구(메모리 캡 §3-4 내에서) |
| E5 노드 kill(핵심) | admin이 `chaos_commands`에 지시를 적재하고 **하네스 소비기**(`scripts/chaos-consumer.ps1`)가 점유해 `docker kill chaos-api-1` 수행. 관리 프로세스는 Docker에 닿지 않는다 | restart→재생→readiness UP→LB 재편입, RTO<30s, epoch+1 |
| E6 폭주+kill 동시 | k6 spike 중첩 + E5 | 유실 0·중복 0·자산합 0·재생 결정론 `matched=true`(P6 증거 표) + C16 |
| 보완(그레이 장애) | `docker pause`(freezer), **pause는 restart 정책으로 복구 안 됨 → 실험 타이머가 unpause 자동 종료(필수 훅)** | 헬스체크 vs TCP 생존 괴리, 좀비 리더 fencing |
| 보완(패킷) | Pumba netem loss/corrupt (`chaos-net`), **사전 검증 필수**: `docker run --rm --cap-add NET_ADMIN alpine sh -c "apk add iproute2 && tc qdisc add dev eth0 root netem loss 10%"` 성공 시에만 활성. WSL2 커널 sch_netem 미탑재 시 **packet loss 실험은 범위 제외로 문서화** | Toxiproxy 공백 전담(조건부) |

모든 실험은 감사 로그·Grafana annotation 자동 삽입(§5-4)을 경유한다. 계약 §3-6/§6-B 그대로. approval_token 은 v2.34 에서 불요로 완화됐다(제시하면 검증·소각). 실행 주체가 1인이라 발급 주체와 사용 주체가 달라야 하는 2인 승인이 성립하지 않았고, 운영자 계정을 하나 더 만들어 혼자 두 역을 하는 우회는 통제를 형해화하면서 형식만 남긴다. 역할 게이트(CHAOS_OPS)와 전량 감사는 그대로다. 카오스 주입의 유일한 입구는 admin API다(toxiproxy 8474 호스트 미공개, §3-1).

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

**클라우드 OIDC는 채택으로 이전한다(2026-08-30).** 학교 공지의 필수 조건이 "반드시 배포하여 외부에서 접속 가능한 상태로 서비스할 것"이라 클라우드 미사용 전제가 성립하지 않는다. GitHub Actions가 AWS에 붙는 경로는 OIDC 페더레이션이고 장기 액세스 키를 발급하지 않는다. 비채택(근거): Vault 서버, Sealed Secrets(GitOps 컨트롤러 부재). admin 평면 시크릿(mTLS 인증서, admin_secret)은 admin-net 내에서만 마운트.

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

## 부록 T. 디자인 토큰 게이트 (token-gate)

스타일 게이트가 "AI 흔적"을 막는다면 이 게이트는 "토큰 계약 드리프트"를 막는다. 목적이 다르므로 검사 범위도 다르다. 스타일 게이트는 PR의 추가된 줄만 보고, 토큰 게이트는 저장소 전체 상태를 본다. 드리프트는 새로 추가된 줄이 아니라 선언과 소비가 어긋난 현재 상태이기 때문이다.

### T-0. 화면별 1차 지표

| 화면 | 1차 지표 | 규격 | 배율(본문 13px 대비) |
|---|---|---|---|
| 온보딩 | 헤드라인 | display-lg 34 | 2.6 |
| 홈 | 24H 총 거래대금 | display-md 28 | 2.15 |
| 거래 | 현재가 | display-md 28 | 2.15 |
| 자산 | 총 평가금액 | display-lg 34 | 2.6 |
| 관리 콘솔 | 종합 헬스 상태어 | display-md 28 | 2.15 |
| 거래내역·토론 | 없음(표·목록 화면) | - | - |

거래 화면은 요약 바 64px, 관리 히어로는 128px 예산이 있어 display-md가 상한이다. 홈은 스크롤 화면이지만 스트립이 가로 1행이라 같은 단계를 쓴다. 홈 위계는 집계 28 > 스포트라이트 시세 24 > 랭킹 13으로 문서 순서와 크기 순서를 일치시킨다.

### T-1. 배경

토큰 계약은 tokens.css에 실측 대비까지 명문화돼 있는데 소비 쪽이 반복해서 어긋났다. 대표 사례는 MarketListPanel.module.css다. 31행에 "sunken 면에 secondary 금지(4.27:1 미달)"를 주석으로 적고 97줄 아래에서 같은 파일이 그 조합을 썼다. 포커스 링, 전일대비 표기, 카피 종결형, 스크롤바도 같은 형태로 갈렸다. 규칙을 사람이 기억하는 구조라 계속 샌다.

### T-2. 2층 구조

| 층 | 도구 | 잡는 것 | 못 잡는 것 |
|---|---|---|---|
| 정적 | scripts/check-tokens.mjs | 토큰 값 드리프트, 색 리터럴, 미선언·미소비 토큰, 카피 종결형, 방향 표기, 자체 포커스 링 | 요소가 어느 면 위에 놓이는지, 배치 |
| 브라우저 | scripts/check-browser.mjs | 요소 단위 실측 대비(면 합성 포함), 플레이스홀더 대비, 의도치 않은 감김, 가로 스크롤, 무스크롤 예산 | 기본 상태 밖(탭·필터·다이얼로그), 전이 중 색 |

정적 층만으로는 부족하다는 것이 두 번 실측으로 확인됐다.

1. sunken 면 승격을 제거한 상태를 주입했을 때 정적 게이트는 통과했고 브라우저 층이 두 셀을 4.27:1로 잡았다. 조합이 CSS 두 파일에 나뉘어 있어 정적으로는 보이지 않는다.
2. 마켓 목록 등락률 열 폭을 되돌려 2줄 감김을 주입했을 때도 정적 게이트는 통과했다. 값은 전부 정상이고 결함은 배치에만 있다. 브라우저 층이 `dir-text num 2줄 38px가 MarketListPanel_row 상자 40px를 넘김`으로 잡았다.

### T-2-1. 픽셀 비교를 쓰지 않는 이유

시세가 초당 바뀐다. 시간과 엔진을 동결해도 캔버스 렌더와 폰트 힌팅이 흔들려 오탐이 상시로 난다. 기준 이미지를 화면 수 곱하기 뷰포트 수 곱하기 테마 수만큼 저장하고 갱신하는 비용도 붙는다. 재는 대상을 픽셀이 아니라 기하로 바꾼다. 시세 문자열이 바뀌어도 줄 수와 상자 관계는 안 바뀐다.

### T-3. 검사 항목 (정적)

| 규칙 | 내용 | 예외 |
|---|---|---|
| contract | tokens.css 주석이 근거로 든 텍스트·면 조합의 실측 대비를 단정한다. 텍스트 4.5:1, 비텍스트 3:1 | 라이트 btn-secondary-border는 의도적 1.41:1이라 다크만 검사 |
| color-literal | 색 리터럴 금지. 토큰으로 소비 | 코인 브랜드색(engine.ts), 대비 계산 입력(brandChip.ts), tokens.css |
| undefined-token | var(--x)의 x가 어디에도 선언되지 않음 | 없음 |
| dead-token | 시맨틱·별칭 토큰의 소비처 0 | 팔레트 램프(미사용 단계는 결함 아님) |
| copy-tone | UI 카피 존댓말 종결 금지. web/ 문자열·JSX와 server/ ApiException 메시지를 함께 본다 | 시드 게시글·댓글(사용자 작성물) |
| direction-format | formatChangeRate 직접 호출 금지, formatDirectional 사용 | 허용 목록에 이유 기재 시 |
| focus-ring | :focus 계열에서 자체 outline 선언 금지 | none 또는 --focus-outline-* 소비 |

카피 톤 검사가 server/까지 보는 이유는 사용자가 읽는 문구의 절반이 거기서 오기 때문이다. `ApiError.message`가 서버 본문의 문장을 그대로 담고, 주문 패널과 관리 콘솔 11곳이 그것을 토스트에 붓는다. web/만 보던 동안 같은 토스트 자리에 "주문 접수 완료"(명사형)와 "주문 가능 잔고가 부족합니다."(존댓말)가 번갈아 떴고, 규칙은 있는데 경계 밖이라 아무도 못 봤다. 3차 감사에서 서버 메시지 73종 중 38종이 존댓말로 확인됐다.

프론트가 코드만 매핑해 자체 문구로 대체하는 방법은 택하지 않았다. `invalid_parameter` 하나에 메시지가 52종이라 코드로 접으면 "가격 단위 오류"와 "최대 주문 금액 초과"의 구분이 사라진다. 정보는 서버 메시지에 있으므로 어투만 제품 목소리에 맞춘다. 인증 화면은 예외로 `mapAuthError`가 코드를 화면 문구로 옮긴다. 그 경로는 코드가 1:1이라 정보 손실이 없다.

한계: `ApiException` 생성자만 본다. 문자열 결합으로 만든 메시지는 앞 리터럴만 검사한다.

주석은 파싱 전에 걷어낸다. tokens.css 주석은 규칙 근거로 토큰명과 실측 hex를 인용하므로 걷지 않으면 선언 파싱이 깨지고 색 리터럴 검사가 전부 오탐이 된다.

### T-4. 강제 지점

1. CI: .github/workflows/style.yml의 tokens 잡. 전체 상태 검사, 위반 시 병합 차단.
2. 로컬: `npm run check:tokens`(web/) 또는 `node scripts/check-tokens.mjs`(루트).
3. 대비 행렬: `node scripts/check-tokens.mjs --matrix`. 어느 텍스트 토큰이 어느 면에서 안전한지 표로 출력한다. 컴포넌트를 새로 만들 때 참조한다.
4. 브라우저 게이트: .github/workflows/style.yml의 browser 잡. 로컬은 `npm run check:browser`(web/) 또는 `node scripts/check-browser.mjs`(루트). 화면 하나만 보려면 `--only=exchange`, 눈으로 보려면 `--headed`. dev 서버는 스크립트가 직접 띄우고 끝나면 죽인다. 데이터 소스는 sim으로 고정해 백엔드 기동에 묶이지 않는다. 전 화면 1회에 약 4분.

### T-5. 브라우저 게이트 검사 항목

| 프로브 | 판정 | 근거 |
|---|---|---|
| contrast | 텍스트 4.5:1, large text 3:1, aria-hidden 글리프 3:1 | 1.4.3, 1.4.11 |
| placeholder | 4.5:1 | 플레이스홀더는 1.4.3상 텍스트. ::placeholder는 의사요소라 요소 순회에 안 잡혀 별도 프로브 |
| wrap | 여러 줄 텍스트가 조상 상자를 넘기면 실패 | 겹쳐 그려진다. 상자 scrollHeight로는 못 잰다(44px 히트 확장 ::after가 absolute라 상자마다 부풀림) |
| viewport | 1280px에서 가로 스크롤 0 | 고정형 최소 폭 계약 |
| budget | 거래 화면 세로 스크롤 0 | 무스크롤 예산 |

aria-hidden 글리프에 3:1을 적용하는 이유는 면제가 아니라 기준 교체다. 접근성 트리에서 뺐다는 건 의미를 옆 텍스트가 지고 이건 시각 장식이라는 선언이므로 1.4.3의 텍스트가 아니다. 다만 눈에는 보이므로 1.4.11의 최소 대비는 받는다.

### T-6. 사각지대

| 못 보는 것 | 이유 | 대응 |
|---|---|---|
| 기본 상태 밖 | 화면을 열고 기본 상태만 잰다. 탭 뒤, 필터 적용 후, 다이얼로그 안은 안 열린다 | 해당 UI를 바꾼 PR은 눈으로 확인 |
| 전이 중 색 | reduced-motion으로 시세 플래시·팝을 끈다. 껐기 때문에 결정적이다 | 플래시 색은 정적 층의 contract가 본다 |
| 닫힌 대화형 요소 | 3차 감사 전까지 `display`·`visibility`만 봐서 닫힌 `<details>` 안의 내용을 보이는 것으로 셌다. 자신도 조상도 none이 아니고 rect까지 갖는다 | `checkVisibility({checkOpacity, checkVisibilityCSS})`로 교체 |
| 미적 판단 | 여백, 위계, 리듬은 기하로 환원되지 않는다 | 사람이 본다 |

## 부록 U. 문서 링크 게이트 (doclinks-gate)

스타일 게이트가 AI 흔적을, 토큰 게이트가 디자인 계약 드리프트를 막는다면 이 게이트는 문서 인용의 실효성을 막는다. 계약 문서를 인용한 코드·문서가 문서 이동·개명 뒤에도 유효한지 본다. 이 절이 규약 정본이고 루트 CLAUDE.md 12절은 이 절을 참조만 한다.

### U-1. 배경

2026-08-30 문서 24건을 `docs/` 평면에서 `contracts/`·`product/`·`architecture/` 등으로 재배치했다. 재배치 자체는 git mv로 이력을 보존했으나 인용은 자동으로 따라오지 않는다. 이동 직전 커밋에서 깨진 참조 5건이던 것이 이동 직후 341건이 됐다. 사람이 grep으로 쫓는 방식은 이 규모에서 누락을 남긴다.

### U-2. 검사 대상과 판정

| 항목 | 값 |
|---|---|
| 실행 | `pwsh -NoProfile -File scripts/check-doclinks.ps1` (`-Stats`로 인용 상위 20종 출력) |
| 범위 | 저장소 전체 상태. 스타일 게이트와 달리 PR diff가 아니다 |
| 인용 형태 | `docs/` 접두 절대 인용, 문서끼리의 `./`·`../` 상대 인용, `docs/README.md` 색인 표의 `docs/` 기준 상대 인용 |
| 보는 것 | 인용 대상 파일의 실재. 대소문자는 서수 비교(배포 대상이 리눅스라 Windows 파일시스템에 기대면 안 된다) |
| 안 보는 것 | 앵커(`#절`), 절 표기(`§6.2`), 행 번호(`:258`), 문서 내용 |
| 통과 | 깨진 참조 0건. 종료코드 0 |
| 위반 | 종료코드 1. 목록을 `발신파일:행 -> 토큰` 형태로 출력 |

### U-3. 면제

- 발신이 `docs/archive/`인 인용은 판정하지 않는다. 폐기 문서는 시점 기록이라 축어 보존이 원칙이고, 부록 S의 스타일 게이트가 `:!docs/archive/**`로 면제하는 것과 같은 규약이다. 면제가 없던 동안 이 게이트는 종료코드 1에 고정돼 이진 판정을 잃었다.
- 빌드 산출물과 의존성 트리는 원천이 아니므로 스캔하지 않는다. 제외는 이름 세그먼트가 아니라 경로 고정형이다(`server/build`, `server/*/build`, `server/.gradle`, `web/.next`, `web/.test-dist`, `web/out`, `seed-data/out`, 어느 위치의 `node_modules`·`.git`). 이름 기준으로 자르면 추적 원천인 `제출_20260901/build/`가 함께 잘린다.
- 자리표시자 파일명(`NNN`, `XXX` 형태)은 실재 대상이 아니라 판정에서 뺀다.
- `server/app/src/main/resources/db/migration/`은 스캔하지 않는다. Flyway 가 적용된 파일의 체크섬을 검증하므로 주석 한 줄만 고쳐도 기존 볼륨을 쥔 노드가 전부 기동을 거부한다. 이 게이트가 그 안의 문서 경로를 잡으면 문서를 옮길 때마다 마이그레이션을 고치게 되고 그 결과가 전 노드 기동 불가다. 2026-08-31 에 문서를 `docs/contracts/`로 옮기면서 V1·V8·V12 의 주석 경로를 함께 고쳤고, 그 불일치는 기존 볼륨에 대고 이미지를 다시 구운 2026-09-06 에 드러났다. 복구는 `flyway repair` 로 했다. 적용된 마이그레이션의 옛 문서 경로는 고치지 않고 그대로 둔다.

면제가 아니라 표기 제약이 하나 따라온다. 이동·삭제된 옛 경로를 이력 기록에 남길 때는 `docs/` 경로 인용 형태로 적지 않는다. 게이트는 시점을 구분하지 않으므로 실재하지 않는 경로는 영구 위반이 된다. 옛 위치는 폴더를 문장으로 서술하고 파일명만 적는다.

### U-4. 사각지대

| 못 보는 것 | 이유 | 대응 |
|---|---|---|
| 폴더를 넘는 맨 파일명 인용 | 경로 접두가 없어 정규식에 걸리지 않는다. 이동 전에는 전 문서가 `docs/` 평면이라 맨 이름이 위치를 뜻했다 | 해당 문서를 손댈 때 `docs/` 경로 인용으로 바꾼다. 게이트 출력이 "경로 인용" 건수임을 명시한다 |
| 앵커와 절 번호 | 파일 실재만 본다. `§6.2`가 사라져도 통과한다 | 정본을 개정한 커밋이 인용 절 번호를 함께 고친다 |
| `docs/README.md` 산문 줄의 백틱 이름 | 색인 표 행만 판정한다. 산문 백틱에는 경로가 아닌 이름 언급(`tokens.css`)이 섞인다 | 색인은 표에 적는다 |
| 인용 대상의 내용 | 파일이 있으면 통과한다. 인용한 주장이 그 문서에 실제로 있는지는 보지 않는다 | 리뷰 |

### U-5. 강제 지점

1. CI: `.github/workflows/style.yml`의 `doclinks` 잡. 저장소 전체 상태를 보므로 토큰 게이트와 같은 형태로 분리한다. 위반 시 병합 차단.
2. 로컬: 문서를 옮기거나 개명한 커밋 전에 수동 실행. 인용을 먼저 고치고 이동을 커밋한다.
