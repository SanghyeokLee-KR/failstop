# 하네스 CI/CD·IaC·관측성 설계서 (v2.0)

> 전제 계약: `docs/contracts/백엔드_DB_설계.md`, `docs/contracts/백엔드_API_설계.md`, `docs/contracts/하네스_검증설계.md`. 판번호는 각 문서 헤더가 갖는다. 여기에 복사하지 않는다. 복사한 판번호는 반드시 드리프트한다.
>
> 상위 정본은 `제출_20260901/기획안_원본.md`다. 이 문서와 정본이 어긋나면 정본이 이긴다.
>
> 설계 원칙: 정확성(증명 가능성) > 재현성(1커맨드 기동) > 성능 > 편의성.
>
> 하네스의 임무는 증명 축 셋을 데이터로 보이게 만드는 것이다. ① 체결 무결(자산합 0, 유실 0, 중복 0) ② 자동 복구(재생, 페일오버, RTO) ③ 병목 이동과 안정화(병목 계층을 관측으로 특정하고 조작 전후 지표 차와 그 방향으로 판정). 축이 셋인 근거는 정본 §요약과 §1.3이다.
>
> ①과 ②는 CI 게이트와 실험 스크립트가 단일 임계로 판정한다. ③만은 라이브 교차 실험이라 단일 임계가 없다. 한 번에 한 축만 바꾸고 조작 전후를 비교한다(`harness/scale/pool_db_matrix.py`, 오버레이 `harness/docker-compose.scale.yml`).

## 이 문서를 읽는 법

**§0부터 §7까지는 저장소에 실재하는 것만 적는다.** 만들지 않은 것은 §8에 모았다. 이 분리가 이 판의 요점이다.

종전 판은 실물과 목표 명세를 한 층에 섞었다. 그래서 한 곳을 실물로 고치면 옆자리가 어긋났고, 같은 문서가 같은 사실을 두 곳에서 반대로 말하는 상태가 됐다. 목표를 §8 한 곳에 모으면 무엇을 만들었을 때 어디를 옮기는지가 자명해진다.

수치에는 센 방법을 함께 적는다. 알람 개수와 대시보드 장수는 판마다 틀렸던 값이다.

실측 기준일은 2026-09-09다.

---

## 0. 실물 스택

저장소의 컴포즈와 워크플로가 실제로 참조하는 것만 싣는다.

| 계층 | 구성요소 | 실물 값 | 확인 방법 |
|---|---|---|---|
| CI | GitHub Actions | `checkout@v4`, `setup-java@v4`(temurin 21), `setup-gradle@v4`, `setup-node@v4`(Node 22) | `.github/workflows/*.yml` `uses:` 전수 |
| 이미지 빌드 | buildx | `setup-qemu@v3`, `setup-buildx@v3`, `login@v3`, `metadata@v5`, `build-push@v6` | `image.yml` |
| 레지스트리 | GHCR | `ghcr.io/<repo>/exchange-app`, `linux/amd64,linux/arm64` | `image.yml` |
| 앱 | Spring Boot, Java 21 | `harness/Dockerfile.app` 로 굽는다 | `image.yml` context `harness` |
| DB | PostgreSQL | `postgres:17-alpine` | 컴포즈 `image:` |
| 임차 보관소 | DynamoDB Local(선택) | `amazon/dynamodb-local` | 컴포즈. 기본 경로는 PostgreSQL 행 |
| 진입 | Nginx | `nginx:1.29` | 컴포즈 |
| 웹 | Next.js 정적 export | 런타임 없음. `nginx:1.29`가 `web/out`을 서빙 | `web/next.config.ts` `output:"export"` |
| 네트워크 카오스 | Toxiproxy | `ghcr.io/shopify/toxiproxy:2.12.0` | 컴포즈 |
| 메트릭 | Prometheus | `prom/prometheus:v3.13.0` | 컴포즈, `ci-backend.yml` promtool |
| 알람 통지 | Alertmanager | `prom/alertmanager` | 컴포즈 |
| 대시보드 | Grafana | `grafana/grafana:13.1.0` | 컴포즈 |
| 부하 | k6 | `grafana/k6:1.4.0` | 컴포즈, 전 증거 회차 |
| Gradle | 9.6.1 | Configuration Cache 활성 | `gradle-wrapper.properties` |

컴포즈 7개 파일 어디에도 `build:` 키가 없다. 모든 이미지는 받아 쓴다. 앱 이미지만 예외로 `harness/scripts/up.ps1`이 따로 굽는다.

k6의 STOMP는 `k6/experimental/websockets` 위 순수 JS다. 핀 이미지에 stable `k6/websockets`가 없다. 실측 오류가 `unknown dependency : k6/websockets`이고 근거는 `harness/load/lib/stomp.js` 머리 주석이다.

**종전 판이 확정 스택으로 싣던 것 중 저장소에 없는 것.** Redis, Kafka, RabbitMQ, Tempo, Loki, Gatling, k3d, 커스텀 PostgreSQL 이미지다. 일곱 개 컴포즈 파일 전부에 대해 `grep -ric` 결과가 0이다. 이들은 §8로 옮겼다.

---

## 1. 저장소 구조와 환경 분리

### 1-1. 디렉터리 레이아웃

```
.github/workflows/   ci-backend.yml  ci-web.yml  image.yml  style.yml
server/              app  engine        (단일 Gradle 트리)
web/                 Next.js 정적 export
harness/             prometheus  grafana  alertmanager  nginx  toxiproxy
                     load  scripts  scale  replication  sql  evidence  config
                     Dockerfile.app  docker-compose.chaos.yml  docker-compose.scale.yml
deploy/              compose.node-{app,bot,data,edge}.yml  nginx-edge.conf.template
scripts/             check-{style,doclinks,tokens,browser}  외 3
seed-data/           수집 카탈로그
artifacts/           verification/role-system
제출_20260901/       심사자용 산출물
compose.yaml         카오스 스택 진입점
```

`infra/`, `observability/`, 루트 `load/`는 존재하지 않는다. 종전 판의 모듈 트리(`exchange-core`, `exchange-api`, `common-dto`)도 없다. `ci-backend.yml:7` 주석이 그 사실을 스스로 적는다.

루트에 `package.json`이 없다. `package.json`은 `web/`, `seed-data/`, `제출_20260901/diagram/`, `제출_20260901/build/`에 있다.

### 1-2. 평면

셋이다. 정본 §5의 평면 분리를 컴포즈 축으로 옮긴 것이다.

| 평면 | 진입점 | 서비스 수 | 용도 |
|---|---|---|---|
| 카오스·데모 | `compose.yaml`(+ `harness/docker-compose.chaos.yml` include) | 프로파일 없이 8, `full` 13, 전 프로파일 16 | 실험, 데모, 개발 |
| 규격 실험 | `harness/docker-compose.scale.yml` 오버레이 | 위에 3 서비스 덮어씀 | 풀과 DB 코어 격자 |
| 배포 | `deploy/compose.node-{app,bot,data,edge}.yml` | 5 (app, bot, db, edge, certbot) | 상시면. 미검증 |

세는 방법은 `docker compose ... config --services | wc -l`이다.

`e1`~`e9` 실험 경로는 루트 `compose.yaml`을 쓰지 않는다. `harness/scripts/lib.ps1`의 `$Global:CHAOS.ComposeFile`이 `harness/docker-compose.chaos.yml`로 고정돼 있다.

**배포 평면은 미검증이다.** `compose.node-app.yml`, `node-bot.yml`, `node-data.yml` 머리 주석이 스스로 "실제 다중 호스트에서 기동한 적이 없다"고 적는다. 동봉된 `.env.example`만으로는 `docker compose config` 파싱도 되지 않는다.

---

## 2. CI 파이프라인

### 2-1. 토폴로지와 게이트 정책

워크플로 4종이다. 461행이다.

| 워크플로 | 트리거 | 잡 | paths |
|---|---|---|---|
| `ci-backend` | PR + push (main, develop) | build-test, observability | `server/**`, `harness/prometheus/**`, `harness/grafana/dashboards/**`, 자기 파일 |
| `ci-web` | PR + push (main, develop) | build-test | `web/**`, `scripts/prune-admin-bundle.mjs`, 자기 파일 |
| `image` | `workflow_run`(ci-backend 성공, main) + 수동 | build-push | 없음 |
| `style` | PR (main, develop) | style, doclinks, tokens, browser | 없음 |

`image`는 `ci-backend` 성공에 물려 있다. 백엔드가 깨지면 이미지가 나가지 않는다.

`style.yml`만 `permissions`도 `concurrency`도 `timeout-minutes`도 없다. 나머지 셋은 앞의 둘을 갖는다. `image.yml`에 `timeout-minutes`가 없다.

`develop` 브랜치가 원격에 없다. 원격은 `main`과 `design/full-polish`다. 네 워크플로 전부가 `develop`을 트리거 조건에 적는데 그 브랜치가 없다.

### 2-2. `ci-backend.yml`

`build-test` 잡은 `server`에서 `./gradlew build` 하나를 돈다. timeout 30분, 캐시는 main 밖에서 읽기 전용이다.

`observability` 잡은 저장소 루트에서 네 검사를 돈다. `uses:`는 checkout 하나뿐이고 나머지는 전부 `run:`이다.

1. `promtool check config` (`prom/prometheus:v3.13.0` 컨테이너)
2. `promtool check rules` (`exchange-recording.yml`, `exchange-alerts.yml`)
3. 대시보드 sanity (`jq -e '(.panels|type)=="array" and (.panels|length)>0'`)
4. 대시보드 중복 키 (python `object_pairs_hook`)

곧 관측 설정이 문법 오류이거나 대시보드에 패널이 없으면 백엔드 CI가 막힌다. 관측을 코드와 같은 게이트에 올린 것이 이 잡의 요점이다.

`integrationTest` 소스셋도 ArchUnit도 없다. `./gradlew build` 안에서 Testcontainers 통합 검증이 함께 돈다.

### 2-3. `ci-web.yml`

`web`에서 스텝 9개를 돈다. `npm ci`, `npm run build`, `npx tsc --noEmit`, `npm test`에 검증 스텝 넷이 붙는다.

- 데이터 원천 단일화: `.env.production`이 있고 `NEXT_PUBLIC_DATASOURCE=http`가 정확히 일치하며 git 추적 중인지 확인한다.
- 정적 export 성립: `out/`이 비지 않고 `out/index.html`이 있는지 확인한다.
- 관리 화면 분리: `node ../scripts/prune-admin-bundle.mjs`로 공개 번들에서 관리 경로를 걷어 낸 뒤 `--check`로 다시 확인한다.

관리 화면을 공개 번들에서 빼는 것은 라우팅 없음이 인증 검사보다 앞선 방어이기 때문이다. 근거는 ADR 0011이다.

### 2-4. `image.yml`

`server`에서 `./gradlew :app:bootJar --no-daemon`으로 jar를 굽고, `-plain.jar`가 아닌 것을 `harness/app.jar`로 옮긴 뒤 `harness/Dockerfile.app`으로 이미지를 만든다.

태그는 둘이다. `head_sha`와, 기본 브랜치일 때만 붙는 `latest`다.

마지막 스텝이 `docker buildx imagetools inspect`로 amd64와 arm64 둘 다 있는지 python으로 확인하고 하나라도 없으면 실패시킨다. 배포 대상이 t4g 계열(Graviton, ARM64)이라 amd64 단독이면 EC2에서 `exec format error`가 난다. app.jar는 아키텍처 독립이므로 차이는 베이스 레이어뿐이다.

digest는 `GITHUB_STEP_SUMMARY`에 `APP_IMAGE=...@digest`로 적힌다. 배포가 이 digest를 받는다.

`harness/app.jar`가 작업 트리에 53MB로 있으나 git 추적 대상이 아니다. CI가 매번 새로 만든다.

### 2-5. `style.yml`

잡 4개다. 각각 `scripts/` 아래 게이트에 대응한다. 다만 `style` 잡은 `check-style.ps1`을 부르지 않고 같은 검사를 워크플로 안에 다시 구현한다. 나머지 셋은 스크립트를 직접 부른다.

| 잡 | 실행 | 대응 부록 |
|---|---|---|
| style | 인라인 (git diff 추가 줄 검사) | S |
| doclinks | `pwsh scripts/check-doclinks.ps1` | U |
| tokens | `node scripts/check-tokens.mjs` | T |
| browser | `node scripts/check-browser.mjs` | T-5 |

`style` 잡은 추가된 줄만 본다. `git diff --unified=0 origin/base...HEAD`로 모으고 `.html`, `.pdf`, `.svg`, `.lock`, `.jar`, `package-lock.json`, `node_modules`, `web/out`, `web/.next`, `docs/archive`를 뺀다. 커밋 메시지에도 같은 패턴을 건다.

`tokens` 잡은 `npm ci` 없이 돈다. `check-tokens.mjs`의 import가 `node:fs`, `node:path`, `node:url` 셋뿐이라 외부 의존이 없다.

---

## 3. 카오스·데모 토폴로지

### 3-1. 서비스 구성

`compose.yaml`이 `harness/docker-compose.chaos.yml`을 include한다. 실효 서비스는 전 프로파일 16개이고 프로파일 없이 8개가 뜬다.

| 서비스 | 이미지 | 호스트 포트 | 프로파일 |
|---|---|---|---|
| `chaos-pg` | `postgres:17-alpine` | 55432 | 기본 |
| `chaos-api-1` | 앱 | 18080 | 기본 |
| `chaos-api-2` | 앱 | 28080 | 기본 |
| `chaos-lb` | `nginx:1.29` | 58080 | 기본 |
| `chaos-bots` | 앱 | 없음 | 기본 |
| `chaos-toxiproxy` | Toxiproxy 2.12.0 | 58474 | 기본 |
| `chaos-dynamodb` | DynamoDB Local | 없음 | 기본 |
| `chaos-prometheus` | Prometheus v3.13.0 | 59090 | obs, full |
| `chaos-grafana` | Grafana 13.1.0 | 53001 | obs, full |
| `chaos-alertmanager` | Alertmanager | 59093 | obs, full |
| `chaos-web` | `nginx:1.29` | 3000 | web, full |
| `chaos-admin` | 앱 | 8443 | admin, full |
| `chaos-k6` 외 2 | `grafana/k6:1.4.0` | 없음 | load |

웹 포트가 3000인 것은 우연이 아니다. 앱의 WebSocket 오리진 허용 목록과 개발 CORS 기본값이 `http://localhost:3000`만 받는다. 다른 포트로 내면 시세와 차트는 나와도 WebSocket 핸드셰이크가 403으로 막힌다(2026-09-08 실측).

웹의 API 주소는 빈 문자열이다. 같은 출처로 부른다는 뜻이고 `harness/nginx/web.conf`가 `/api/`와 `/ws/`를 로드밸런서로 넘긴다. 상시면에서 콘텐츠 전송 네트워크 두 오리진이 하는 일을 로컬에서는 이 프록시가 대신한다.

네트워크와 볼륨 실명은 `exchange-chaos_chaos-net`과 `exchange-chaos_chaos-pgdata`다. compose가 프로젝트명을 접두로 붙인다.

`container_name` 16개가 전부 고정이라 같은 호스트에서 두 스택을 동시에 띄울 수 없다.

### 3-2. Toxiproxy 배선

`harness/toxiproxy/populate.json`이 프록시 2개를 정의한다. `pg-api1`과 `pg-api2`다. 노드별로 DB 경로를 따로 끊을 수 있어야 리더만 겨냥한 실험이 성립한다.

admin API(8474)는 카오스 네트워크 내부 전용이다. 호스트 포트 58474는 디버깅용이고 승인 경로를 우회하는 통로가 되면 안 된다.

Redis 프록시는 없다. Redis 서비스 자체가 없기 때문이다.

### 3-3. 진입 배선

`harness/nginx/nginx.conf`가 API 로드밸런서이고 `harness/nginx/web.conf`가 웹 서빙과 동일 출처 프록시다.

`web.conf`는 `^/(admin/v1|harness|actuator)/`를 404로 막는다. 관리 API와 하네스 API는 공개 진입으로 나가지 않는다. 관리 화면 자체(`/admin`)는 막지 않는다.

### 3-4. 리소스 계획

`deploy.resources` 캡을 가진 서비스는 카오스 평면 4개와 오버레이 1개다. 캡이 없으면 JVM의 `MaxRAMPercentage`가 호스트 전체 메모리를 기준으로 잡아 WSL2에서 과할당된다.

`harness/docker-compose.scale.yml`이 규격 실험용 오버레이다. `SCALE_PG_CPUS`, `SCALE_PG_MEM`, `SCALE_POOL` 셋을 받는다.

### 3-5. 데이터베이스 이중화

현행 구성은 정본 §6.3이다. primary 한 대와 대기 서버 두 대를 `synchronous_standby_names = ANY 1`로 묶는다. 실험 수단은 `harness/replication/`이고 파일 4개다(`run.sh`, `repl_sampler.py`, `analyze.py`, `dynamo_lease.py`).

정족수 설정은 `harness/scale/standby_up.sh`가 `ALTER SYSTEM SET`으로 직접 건다.

Patroni, etcd, HAProxy는 쓰지 않는다. 정본 §4.2 선택표에도 §4.3 비채택표에도 없고 저장소 전역에 0건이다.

---

## 4. 배포와 페일오버

### 4-1. 리더 선출과 배포 순서

한 시점에 쓰기를 하는 노드는 하나다. 배포는 조회 노드부터 하고 리더를 마지막에 한다. 리더를 먼저 내리면 그 순간 승격이 일어나고, 이어서 새 리더를 내리면 승격이 두 번 난다.

임차 보관소는 갈아 끼울 수 있고 구현이 둘이다. 기본값은 PostgreSQL `leader_lease` 행이고, DynamoDB 항목 하나를 쓰는 구현이 선택지다(ADR 0017). 세대 번호 검증은 어느 쪽이든 거래 트랜잭션 안에 남는다.

### 4-2. 롤링 재시작

절차를 자동화한 스크립트가 없다. `deploy/`에 `rolling.sh`가 없다. 현재는 수동이다.

### 4-3. RTO 측정 정의

RTO는 정지 시각에서 승격 후 최초 신규 체결의 엔진 시각까지다. 단일 계산점은 새 리더의 승격 콜백이다.

측정 원천은 `recovery_runs` 테이블과 `harness/evidence/leader-failover-20260908/`이다. 정본 §10.5가 리더 교체 시간을 9.6초에서 13.1초로 적는다.

---

## 5. 관측성

### 5-1. Actuator

`server/app/src/main/resources/application.yml`은 459줄이고 YAML 문서 6개에 `management` 블록 5개를 갖는다.

노출 엔드포인트는 프로파일마다 다르다. 기본 2개(health, info), dev 5개, prod 5개, bots 5개, chaos-monkey 6개다.

헬스 그룹이 둘이다.

| 그룹 | 경로 | 포함 | 대상 그룹 |
|---|---|---|---|
| readiness | `/actuator/health/readiness` | readinessState | 쓰기 |
| read | `/actuator/health/read` | db, ping | 조회 |

조회 대상 그룹이 `readiness`를 쓰면 팔로워가 빠져 조회 분산이 성립하지 않는다. `deploy/nginx-edge.conf.template`이 그 사용을 명시적으로 금지한다.

관리 포트 분리는 상시면 토폴로지 규약이다. 격리 하네스는 개발 노출이라 8080을 스크레이프한다.

### 5-2. 커스텀 메트릭

고유 이름 31개다. 등록 호출 지점은 34개다. 타입 분포는 Counter 9, FunctionCounter 3, Timer 2, `Gauge.builder` 13, `MeterRegistry.gauge` 3, MultiGauge 1이다.

세는 방법은 `server/app/src/main/java` 아래에서 `.builder("`와 `.gauge("` 등록 인자를 뽑아 중복을 제거하는 것이다.

**규칙이나 대시보드가 실제로 참조하는 것은 19개다.** 12개가 방출만 되고 아무도 보지 않는다. 방출 비용은 이미 내고 있으므로, 쓰지 않을 것이면 걷고 쓸 것이면 규칙이나 패널에 걸어야 한다. 이 12개의 처분은 §8-2에 미결로 둔다.

`AdmissionControl`의 scope는 4개다(order, query, requery, ws).

### 5-3. Prometheus

`harness/prometheus/prometheus.yml`은 31줄이고 scrape job 2개에 대상 엔드포인트 3개다. `exchange-api`가 `chaos-api-1`과 `chaos-api-2`를, `chaos-bots`가 봇 노드를 본다.

exporter가 없다. `postgres_exporter`, `node_exporter`, `blackbox_exporter`, cAdvisor 전부 없다. 컨테이너와 호스트 자원은 `docker stats`로 따로 잰다.

규칙 파일 2개다.

| 파일 | 개수 | 세는 방법 |
|---|---|---|
| `exchange-recording.yml` | record 9 | `grep -c '^      - record:'` |
| `exchange-alerts.yml` | alert 17 | `grep -c '^      - alert:'` |

### 5-4. Grafana

`harness/grafana/dashboards/` 아래 9종이고 패널 총합 92개다. 파일은 01-integrity, 02-recovery, 03-load-slo, 04-system, 05-ops, 10-msp-overview, 11-msp-service, 12-msp-infra, 13-msp-db다.

데이터소스 2개다. `prometheus`와 `pg-proof`(PostgreSQL 읽기 전용)다. 대시보드 2개가 `pg-proof`를 직접 조회하고 rawSql 패널이 5개다. 지표로 못 보이는 판정을 원장에서 직접 긁기 위한 것이다.

파일 프로비저닝이다. Grafana 자체 알림 규칙(`provisioning/alerting/`)은 쓰지 않는다. 알람은 전부 Prometheus 쪽이다.

### 5-5. 대시보드 9종

| 번호 | 이름 | 축 |
|---|---|---|
| 01 | integrity | 증명 1. 불변식과 자산 보존 |
| 02 | recovery | 증명 2. 페일오버와 재생 |
| 03 | load-slo | 부하와 지연 분위수 |
| 04 | system | 자원 |
| 05 | ops | 운영 상황판 |
| 10 | msp-overview | 서비스 전반 |
| 11 | msp-service | 서비스별 |
| 12 | msp-infra | 인프라 |
| 13 | msp-db | 데이터베이스 |

### 5-6. 알람 17개

severity는 critical 5, warning 9, 템플릿 1이다. 템플릿 1은 `InvariantViolation`이고 위반한 체크의 등급을 라벨로 그대로 옮긴다.

| 알람 | severity |
|---|---|
| LedgerImbalance | critical |
| InvariantViolation | 템플릿(위반 체크 등급) |
| FailoverRtoExceeded | critical |
| LeaderAbsent | critical |
| RecoverableStandDownRepeating | critical |
| WsConnectionsRejected | critical |
| OrderP99SloBreach | warning |
| SnapshotPersistFailed | warning |
| SnapshotDropped | warning |
| SnapshotStalled | warning |
| SequencerQueueBackpressure | warning |
| OrderRejectSpike | warning |
| BotFleetDown | warning |
| TradeSilence | warning |
| WsConnectionsNearLimit | warning |

`for` 분포는 0s 3개, 15s 1개, 30s 2개, 1m 5개, 2m 4개다. `component` 라벨은 snapshot 3, bots 2, ws 2, sequencer 1, order-gateway 1이고 6개는 라벨이 없다.

`TradeSilence`가 체결 0을 본다. 체결 0은 평온이거나 사망인데 그 둘을 가르는 것이 봇 플릿의 24시간 기저 트래픽이다(정본 §7.10).

**CRITICAL 계열 발화 시 앱 자동 조치 상한.** 원장과 저널은 자동 수정하지 않는다. 정정은 `REVERSAL` 상계 분개와 1인 승인 티켓으로만 한다(ADR 0019). 알람에 자동 액션을 연결하지 않는다. 판정은 자동이고 실행은 사람이다(정본 §7.5).

### 5-7. Alertmanager

`harness/alertmanager/alertmanager.yml`은 68줄이다. route는 최상위 1개에 하위 1개, receiver는 `chat` 하나(Discord), inhibit_rule 1개다.

critical은 `group_wait` 10초에 `repeat_interval` 1시간이다. 나머지는 4시간이다.

**수신 주소가 비어 있다.** `harness/alertmanager/secrets/discord_webhook_url`이 없다. `.gitignore`와 `.example`만 있다. 이 파일이 없으면 `obs` 프로파일에서 alertmanager가 뜨지 않는다. 곧 통지 경로는 배선됐고 주소만 미기입이다(정본 한계 15).

런북 문서가 없다. `exchange-alerts.yml` 머리 주석이 그 사실을 스스로 적는다. 알람의 `runbook` 필드는 정본 절을 가리킨다.

---

## 6. 부하와 카오스

### 6-1. k6

`harness/load/` 아래 스크립트 8개다(`scenarios/` 포함, `lib/` 제외). 결과 산출물이 `load/artifacts/`에 102개 쌓여 있고 `.gitignore`가 없다.

k6는 시스템 완전 외부에서 돈다. 개방 루프(ramping-arrival-rate)라 시스템이 느려져도 요청 주입률이 떨어지지 않는다. 닫힌 루프면 시스템이 느려질 때 부하도 같이 줄어 천장이 보이지 않는다.

부하 계정 키는 `harness/load/secrets/k6_keys.json`이고 git 추적 대상이 아니다.

### 6-2. 실험 카탈로그

`harness/scripts/` 아래 `.ps1` 15개이고 그중 실험이 `e1`~`e9` 9개다. 결번이 없다. 공통 함수는 `lib.ps1`에 41개 있다. 실험별 환경은 `harness/config/*.env` 9개다.

| 번호 | 스크립트 | 대상 |
|---|---|---|
| E1 | `e1-pg-latency.ps1` | 데이터베이스 경로 지연 |
| E2 | `e2-db-cut.ps1` | 데이터베이스 연결 절단 |
| E3 | `e3-redis-outage.ps1` | 캐시 계층 정지 |
| E4 | `e4-leader-kill.ps1` | 리더 노드 정지 |
| E5 | `e5-net-partition.ps1` | 네트워크 분단 |
| E6 | `e6-spike-failover.ps1` | 폭주 중 페일오버 |
| E7 | `e7-gray-pause.ps1` | 회색 장애 |
| E8 | `e8-app-assault.ps1` | 앱 내부 교란 |
| E9 | `e9-commit-cut.ps1` | 커밋 경로 절단 |
| E10 | 없음 | 커널 계층 지연. 미실시 |

번호는 정본 §10.3과 `하네스_검증설계.md` §3과 파일명이 모두 같다.

**E3은 지금 돌지 않는다.** Redis 서비스가 어떤 컴포즈에도 없다. 스크립트만 남아 있다. `하네스_검증설계.md`가 이 사유로 E3 SKIP을 유효 판정으로 확정했다.

e1부터 e9를 순서대로 도는 오케스트레이터가 없다. 하나씩 부른다.

### 6-3. 불변식 판정

세는 단위는 SQL 판정 블록이다.

| 원천 | 개수 | 세는 방법 |
|---|---|---|
| `harness/sql/invariants.sql` | **27** | 판정 블록. `\echo '== C` 29행에서 C24·C25의 분기 중복 2행을 뺀다 |
| `InvariantCheck.java` enum | 26 | 27에서 C04가 빠진다 |
| 카탈로그 코드 | 25 | C01~C25 |

C04는 지연 제약 트리거가 데이터베이스 안에서 강제하므로 러너의 조회 대상이 아니다. 코드 축 C01~C25에서 C07이 C07·C07P로, C13이 C13a·C13b로 갈려 블록이 27개가 된다.

enum의 Severity 분포는 CRITICAL 15, HIGH 8, WARN 3이다. Scope 분포는 SCHEDULED 19, AUTHORITATIVE_ONLY 7이다. 권위 run 전용을 따로 둔 이유는 라이브 프로젝션 지연과 스테일 ack가 오탐을 만들기 때문이다.

술어 정본은 `하네스_검증설계.md` §4-1이다. 이 절은 미러다.

### 6-4. 규격 실험

`harness/scale/` 파일 5개다. `pool_db_matrix.py`가 격자를 돌고 `analyze_matrix.py`가 표로 만든다. 기본 셀 4개는 A(pool 16, pg 1.0 CPU), B(16, 4.0), C(48, 1.0), D(48, 4.0)이다.

증명 축 ③의 실행 수단이다. 단일 임계가 없고 조작 전후 지표 차와 그 방향으로 판정한다.

### 6-5. 증거

`harness/evidence/` 아래 디렉터리 28개이고 27개가 `README.md`를 갖는다.

---

## 7. 시크릿과 부트스트랩

### 7-1. 시크릿 계층

| 대상 | 위치 | 추적 |
|---|---|---|
| 카오스 환경 | `harness/.env.chaos` | 아니오. `.example`만 |
| Discord 웹훅 | `harness/alertmanager/secrets/discord_webhook_url` | 아니오. 미기입 |
| 부하 계정 키 | `harness/load/secrets/k6_keys.json` | 아니오 |
| 데모 PKI | `server/config/admin-demo/*.p12` | 예. 데모 전용 |

`harness/.env.chaos`에 유효 키가 7개다. 컴포즈가 참조하는 `CHAOS_LEASE_PROVIDER` 등은 기본값으로 해소된다.

**데모 PKI는 개인키를 포함한 PKCS12이고 저장소에 커밋돼 있다.** storepass가 `changeit`이다. 데모 전용이고 상시면 인증서와 별개다. 상시면에 그대로 쓰면 안 된다.

`application.yml`의 dev datasource 자격증명이 평문 `exchange/exchange`다. dev 프로파일 전용이다.

### 7-2. Flyway 실행 주체와 롤

카오스 스택에서는 `chaos-api-1`이 단독으로 마이그레이션을 돈다. 배포 평면에서는 먼저 뜨는 노드가 돈다.

`V0__roles.sql`이 `app_role`과 `harness_role`을 만든다. **두 롤 모두 런타임에 배선돼 있지 않다.** 둘 다 NOLOGIN이고 실제 접속은 다른 계정으로 한다. 롤 경계가 스키마에는 있고 런타임에는 없다. 처분은 §8-2에 둔다.

Flyway 설정 파일도 Gradle 플러그인도 없다. Spring Boot 자동구성이 돈다.

---

## 8. 목표 명세와 미결

**여기 있는 것은 저장소에 없다.** §0부터 §7까지는 실물이고 이 절은 계획이다. 만들면 해당 절로 옮기고 여기서 지운다.

### 8-1. 만들지 않은 것

| 대상 | 무엇 | 왜 필요한가 |
|---|---|---|
| `.github/workflows/deploy.yml` | 배포 워크플로 | GHCR digest를 노드로 내리는 마지막 칸이 없다 |
| 인프라 코드화 | `.tf` 0개 | 정본 13.4 한계 17 |
| `deploy/rolling.sh` | 롤링 재시작 | §4-2가 수동이다 |
| `deploy/reset.sh` | 데모 리셋 | 수동이다 |
| Redis | 캐시 계층 | E3이 못 돈다 |
| exporter 3종 | node, postgres, blackbox | 계층 프로브가 없다. 정본 13.4 한계 14 |
| 런북 | 알람별 조치 카드 | 알람이 가리킬 문서가 없다 |
| Discord 웹훅 주소 | 수신 주소 | 통지가 아무에게도 안 간다 |
| 상시면 관측 배선 | 배포 컴포즈의 관측 서비스 | 정의 0건. 정본 13.4 한계 13 |
| E10 | 커널 계층 지연(tc netem) | 검증면 전용 |
| 실험 오케스트레이터 | e1~e9 순차 실행 | 하나씩 부른다 |

Kafka, RabbitMQ, Tempo, Loki, Gatling, k3d, 커스텀 PostgreSQL 이미지는 종전 판이 확정 스택으로 실었으나 저장소에 없고 로드맵에도 없다. 채택도 비채택도 아닌 상태이므로 정본 §4.3 비채택표에 올리거나 이 표로 내려야 한다. 판단은 §8-2에 둔다.

### 8-2. 결정이 필요한 것

1. **미참조 메트릭 12개.** 방출은 하는데 규칙도 대시보드도 보지 않는다. 걷을지 걸지 정한다.
2. **`app_role`·`harness_role` 미배선.** 스키마의 롤 경계를 런타임에 세울지, 스키마에서 뺄지 정한다.
3. **브로커 지위.** Kafka·RabbitMQ를 정본 비채택표에 올릴지 로드맵에 둘지 정한다. `outbox` 테이블은 실재하고 발행기만 없다.
4. **`develop` 브랜치.** 네 워크플로가 트리거에 적는데 원격에 없다. 만들거나 트리거에서 뺀다.
5. **`style.yml` 비대칭.** 혼자 `permissions`·`concurrency`·`timeout-minutes`가 없다.
6. **배포 평면 검증.** `deploy/` 셋이 스스로 미검증이라 적고 `.env.example`로는 파싱도 안 된다.
7. **`load/artifacts/` 102개.** `.gitignore`가 없어 결과가 계속 쌓인다.
8. **E3.** Redis를 넣어 살릴지, 카탈로그에서 뺄지 정한다.

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
