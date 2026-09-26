# S2 관문 폐기용 스택 주행 (2026-09-24 04:52~05:31 KST)

[S2 설계](../../../docs/reference/architecture/마켓분할_리더_S2_설계.md) 6-4의 폐기용 스택 관문이다. 앱 역할 로그인 종단 주행, 같은 날 A/B 부하 비교, 되돌림 주행을 설계 순서대로 돌렸다. 모두 compose 프로젝트 `exchange-pml`의 폐기용 DB에서 했다. 절차는 [run-s2-gate.ps1](run-s2-gate.ps1)이고, 단계별 명령 기록은 각 하위 폴더의 `commands.log`, 판정 원자료는 `result.json`이다.

## 결론

**S2 관문은 통과하지 않았다.** 종단 주행과 되돌림 주행은 PASS다. A/B 비교는 주행 전에 정한 세 조건 가운데 "5xx 증가 없음"을 채우지 못해 FAIL이다. 같은 조합을 한 번 더 돌린 원인 좁히기 주행(B2, A3)에서도 S2 이미지의 접수 처리율이 약 12% 낮고 5xx가 더 많은 모습이 되풀이됐다. 원인은 특정하지 못했다(아래 「원인 좁히기」). 코드는 고치지 않았다.

같은 날 사용자 결정(5xx는 리더가 낸 것만 센다)으로 설계 6-4의 조건을 고친 뒤 A/B를 A-B-A-B-A-B로 다시 돌렸고 PASS였다. A/B 판정은 [pml-s2-gate-rerun-20260924-1553](../pml-s2-gate-rerun-20260924-1553/README.md)이 대신하며, 이 폴더의 FAIL은 옛 규칙의 기록으로 남긴다.

| 관문 항목(6-4) | 결과 | 근거 |
|---|---|---|
| 6-1·6-3 시험 통과 | 이번에 실행하지 않음 | `b04acd5` 커밋이 적은 서버 빌드 790개 통과를 다시 돌리지 않았다 |
| 앱 역할 로그인 종단 주행(S2 이미지) | PASS | [e2e/result.json](e2e/result.json) |
| 같은 날 A/B 부하 비교 | **FAIL**(5xx 조건) | [ab/result.json](ab/result.json) |
| 되돌림 주행(V48 DB 위 S2 이전 이미지) | PASS | [rollback/result.json](rollback/result.json) |
| V49 이미지 관문 | NOT RUN | V49 파일이 아직 없다(설계 4-1) |
| 카오스 E4·E7 재실행 | PASS | S1 관문 주행이 같은 이미지다([pml-s1-gate-20260924-0440](../pml-s1-gate-20260924-0440/README.md)) |

## 이미지와 조건

| 칸 | 태그 | 이미지 id | 커밋 | 마이그레이션 |
|---|---|---|---|---|
| A(S2 이전) | `exchange-chaos-app:mainref` | `sha256:d8136cf99aa1…` | main `ec8ef8e` | V1~V47 |
| B(S2) | `exchange-chaos-app:pml` | `sha256:09aacc67818b…` | `b04acd5` | V1~V48 |

A는 임시 작업 트리 `C:\dev\exchange-mainref`(`ec8ef8e` detached)에서 bootJar와 이미지를 만들고, 주행이 끝난 뒤 `git worktree remove`로 지웠다. 두 이미지의 jar 해시와 빌드 시각은 [images.json](images.json)이다. `exchange-chaos-app:local` 태그는 쓰지 않았다.

프로필은 A/B 모두 기본(`dev,chaos-monkey`)이다. A에는 `prod-timeouts` 프로필이 없어 같은 조건을 만들 수 없기 때문이다. 앱 노드는 compose 한도 1 vCPU·1 GiB, DB는 scale 오버레이의 4 CPU·4 GiB다. 로컬 Docker Desktop 단일 호스트다.

## 1. 앱 역할 로그인 종단 주행 (E2E, PASS)

새 스택을 소유자 로그인으로 올려 api-1이 Flyway로 V1~V48을 적용하게 한 뒤(`flyway_schema_history` 49행, 최대 48, 실패 0), 배포와 같은 [provision-runtime-roles.sql](../../../deploy/provision-runtime-roles.sql)로 런타임 로그인 `pml_app`·`pml_bot`·`pml_read`를 만들었다. `pml_app`·`pml_bot`은 수퍼유저가 아니고 `app_role`에만 속한다([e2e/runtime_roles.json](e2e/runtime_roles.json)). 비밀번호는 실행마다 무작위로 만들어 셸 환경변수로만 넘겼고 어디에도 적지 않았다.

이어서 [override-app-login.yml](override-app-login.yml)로 api-1·api-2를 `pml_app`(Flyway 끔), 봇을 `pml_bot`으로 다시 만들었다.

- 13초 뒤 epoch 2 리더가 섰다(전환 전 epoch 1).
- 약 1분 동안 새 epoch 2로 명령 저널 569행, 이벤트 990행(TRADE 337), 스냅샷 3행(3마켓)을 썼고 전환 뒤 주문 504건, 체결 337건이 생겼다.
- `pg_stat_activity`에서 toxiproxy를 거친 api 연결은 모두 `pml_app`(22개), 봇 연결은 모두 `pml_bot`(10개)이었다. 소유자 `exchange` 연결은 하네스의 로컬 소켓 psql 하나다.
- 세 컨테이너 로그에 `permission denied`, `42501`, 함수 부재 문구가 0줄이다.
- 봇을 멈추고 드레인한 뒤 소유자 로그인으로 [harness/sql/summary.sql](../../../harness/sql/summary.sql)을 돌렸다. C01~C29 31행이 모두 PASS이고 SKIP이 없다([e2e/summary-B-app-role.txt](e2e/summary-B-app-role.txt)).

V49를 적용하지 않은 상태다. 앱 역할은 `leader_lease` UPDATE와 `command_journal` INSERT 권한을 아직 가지고 있으므로 이 주행은 V49 권한 상태의 근거가 아니다.

## 2. 같은 날 A/B 부하 비교 (FAIL)

### 주행과 사전 판정 규칙

`harness/scale/roundtrip_probe.py --rate 600`을 칸마다 새 스택(`down -v` 뒤 `up`)에서 A1(A), A2(A), B(B) 순서로 돌렸다. 부하는 k6 600/s 개방 루프 130초와 봇 20 ops, 12마켓, 부하 계정 4개다. 09-14 5.7.17과 같은 러너·부하이고 S3의 P1·P2 비교에도 같은 값을 쓸 수 있다. `exchange.journal.append` 타이머에는 코드에서 히스토그램이 없어 p95를 재려고 [override-append-histogram.yml](override-append-histogram.yml)로 Spring Boot 계측 분포 설정만 두 이미지에 똑같이 켰다(하네스의 `CHAOS_EXTRA_COMPOSE`).

판정 규칙은 설계 6-4의 문구 그대로다. 불변식 위반 0, 5xx 증가 없음, append 평균과 p95의 증가가 A-A 차이보다 작음. 이번에 쓴 셈법은 "증가 = B − (A1, A2 평균)", "A-A 차이 = |A1 − A2|"다. 5xx는 설계가 범위를 정하지 않았으므로 서버 지표 `http_server_requests_seconds_count`의 5xx 전부(k6 주행 창의 Prometheus 증가분)를 썼다.

### 결과

| 칸 | 접수/s | append 평균(ms) | append p95(ms) | append p99(ms) | 머리말(ms) | 저널 삽입(ms) | 커밋(ms) | 락 보유 1회(ms) | 적용/s | sink 1회(ms) | 서버 5xx | 리더 5xx | LB 5xx | 주문 p95(ms) | 불변식 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| A1 | 146.7 | 11.096 | 51.76 | 80.10 | 7.822 | 1.159 | 2.406 | 8.828 | 193.4 | 20.42 | 3,309 | 0 | 기록 없음 | 348.5 | 0(C29 SKIP) |
| A2 | 163.0 | 10.247 | 50.10 | 79.04 | 7.110 | 1.118 | 2.141 | 8.829 | 203.3 | 20.00 | 2,726 | 0 | 4,860 | 334.8 | 0(C29 SKIP) |
| B | 136.9 | 10.982 | 52.50 | 80.72 | 7.651 | 1.201 | 2.555 | 8.197 | 190.0 | 20.30 | 4,137 | 0 | 6,166 | 409.9 | 0 |

- 불변식: 세 칸 모두 드레인 뒤 summary.sql 위반 0이다. A 칸의 C29 SKIP은 V48 표가 없는 V47 DB라서 나오는 계약된 값이다(하네스 검증설계 C29 주석).
- append 평균: 증가 +0.310ms, A-A 차이 0.849ms. 조건 충족.
- append p95: 증가 +1.565ms, A-A 차이 1.664ms. 조건 충족(여유 0.1ms). p95는 히스토그램 버킷 보간값이다.
- 5xx: 증가 +1,119.5, A-A 차이 583. **조건 미충족.** B의 5xx는 A 두 칸 어느 쪽보다도 많다.
- fsync 건당은 세 칸 모두 0.41ms, 교착 0, 연결 고갈 없음, k6 종료코드 0이다.

"LB 5xx"는 `chaos-lb` 접근 로그에서 k6 주행 창 안의 5xx를 센 것이다([lb_status.py](lb_status.py), [ab/lb_status_A2.json](ab/lb_status_A2.json), [ab/lb_status_B.json](ab/lb_status_B.json)). A1 칸은 다음 칸의 스택 초기화 전에 로그를 뜨지 못해 없다. A2와 B는 주행 스크립트 밖의 감시 스크립트가 probe 결과 파일이 생긴 직후 로그를 떴다. 원 로그는 요청 주소를 담아 저장소 밖에 두었다.

### 원인 좁히기 (판정에 쓰지 않음)

사전 규칙의 판정을 바꾸지 않고 원인만 보려고 같은 스크립트로 B2(B), A3(A)를 한 번 더 돌렸다([ab-extra/](ab-extra/)). 이 두 칸부터는 스크립트가 LB 로그를 직접 집계한다.

| 칸 | 접수/s | append 평균(ms) | append p95(ms) | 머리말(ms) | 저널 삽입(ms) | 적용/s | 서버 5xx | LB 5xx | 불변식 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| B2 | 138.0 | 11.36 | 50.18 | 8.01 | 1.23 | 187.7 | 3,673 | 5,267 | 0 |
| A3 | 160.0 | 10.12 | 49.76 | 7.07 | 1.09 | 206.7 | 2,493 | 3,895 | 0(C29 SKIP) |

확인한 것은 다음과 같다.

- 다섯 칸 모두 리더 노드가 낸 5xx는 0이다. 서버 5xx는 전부 팔로워(api-2)의 `POST /api/v1/orders` 503이다. 팔로워는 설계상 쓰기를 503으로 거절한다.
- 클라이언트가 받은 5xx는 대부분 nginx의 502 `no live upstreams`다. 쓰기 업스트림은 api-1 주, api-2 백업이고 `max_fails=1 fail_timeout=2s`다. api-1 연결 대기가 `proxy_connect_timeout 2s`를 넘으면 api-1이 2초 동안 빠지고, 그동안 백업 api-2의 503 뒤에 502가 나간다. 연결 시간 초과 수는 A2 2,360회, B 2,243회로 B가 더 많지 않았다. 502 수는 시간 초과가 몰리는 모양과 과부하 정도에 따라 달라진다.
- 5xx 차이와 함께 되풀이된 것은 처리율이다. B 두 칸의 접수는 136.9·138.0/s로 A 세 칸(146.7·163.0·160.0/s)의 최솟값보다 낮다. 평균으로 약 12% 낮다. 적용/s도 B 190.0·187.7, A 193.4·203.3·206.7이다. k6 주문 7만 8천여 건 가운데 201은 약 3분의 1이고 약 3분의 2가 429다. 과부하 영역의 비교다.
- 접수 락 안에서 잰 DB 구간은 차이를 설명하지 못한다. 저널 삽입은 B가 0.04~0.14ms 길어 함수 호출 비용으로 보이지만, 락 보유 1회는 B 8.20·9.07ms, A 8.83·8.83·9.19ms로 B가 길지 않다. 머리말·커밋·sink 1회는 A 칸들 사이의 흩어짐 안이다.
- probe의 명령당 트랜잭션은 B 20.57·20.42, A 18.23·17.81·18.25다. 표별 쓰기(명령당 삽입·갱신)는 같다. 이 값은 봇·표본기 같은 배경 트랜잭션을 명령 수로 나눈 비라 접수가 줄면 커진다. 명령당 왕복이 실제로 늘었는지는 가르지 못했다.
- 남은 후보는 락 밖의 요청 경로(수용 제어 대기, 게이트웨이의 조회, 앱 CPU)와 소유자 권한 함수 호출의 DB CPU다. api-1은 1 vCPU에서 83~105%였다. 프로파일링은 하지 않았다.

### 한계

- 칸마다 1회이고 A 3회, B 2회뿐이다. 12% 차이의 신뢰구간은 없다.
- 5xx의 범위(리더가 낸 오류인지, 팔로워의 설계상 503과 nginx 502까지인지)를 설계가 정하지 않았다. 리더가 낸 오류 5xx만 세면 다섯 칸 모두 0이라 조건을 채운다. 이 판정은 가장 넓은 범위를 택했고, 범위를 어떻게 정할지는 사용자 확인이 필요하다.
- 과부하(제공 부하가 접수 능력의 약 4배) 비교다. 포화 전 부하에서의 차이는 재지 않았다.
- A1 칸의 LB 로그와 칸별 Prometheus 원 시계열은 스택 초기화로 남지 않았다. 창 집계값만 result.json에 있다.

## 3. 되돌림 주행 (Rollback, PASS)

A/B의 B 칸이 남긴 스택(V48 DB, 체결이 쌓인 상태)에서 이어 했다.

1. 런타임 로그인을 만들고 봇, api-2, api-1을 멈췄다.
2. 배포 경로: A 이미지의 `kr.exchange.deploy.MigrateSchema`(compose.migrate.yml의 migrate와 같은 진입점)를 소유자로 돌렸다. 종료코드 0이다. Flyway가 49개 마이그레이션을 검증하고 "Schema has a version (48) that is newer than the latest available migration (47)" 경고 뒤 "up to date"로 끝났다([rollback/migrate-A.log](rollback/migrate-A.log)). 설계 4-1이 jar 기본 문자열로만 확인했던 `ignoreMigrationPatterns=*:future` 동작을 실행으로 확인했다.
3. 카오스 스택 경로: A의 api-1을 소유자 로그인·Spring Flyway 켠 채 띄웠다. 같은 검증을 지나 기동했고 epoch 2로 리더가 됐다([rollback/spring-flyway-A.log](rollback/spring-flyway-A.log)).
4. 두 경로 뒤에도 `flyway_schema_history`는 49행, 최대 48, 실패 0 그대로다.
5. A 이미지로 api-1·api-2를 `pml_app`, 봇을 `pml_bot`으로 다시 만들었다. 13초 뒤 epoch 3 리더가 섰고, 약 1분 동안 epoch 3으로 명령 저널 573행, 이벤트 1,121행(TRADE 418), 스냅샷 3행을 썼으며 주문 509건, 체결 418건이 생겼다. 연결 로그인은 api `pml_app`, 봇 `pml_bot`뿐이고 권한 거부·함수 부재 문구는 0줄이다.
6. 드레인 뒤 summary.sql C01~C29 31행이 모두 PASS다([rollback/summary-A-rollback-app-role.txt](rollback/summary-A-rollback-app-role.txt)).

S2 이전 바이너리는 V48 함수를 쓰지 않고 옛 2인자 함수와 직접 SQL을 쓴다. 이 주행은 "S2 이미지(V48만 적용)에서 S2 이전 이미지로 권한 조치 없이 돌아간다"(설계 4-1의 1)의 실행 근거다. V49 적용 뒤의 되돌리기(GRANT 두 줄 필요)는 이 주행의 범위가 아니다.

## 보존 볼륨과 정리

- 주행 전후 `exchange-chaos_chaos-pgdata`(생성 2026-09-13T11:42:01Z)와 `failstop-keep-chaos-pgdata-20260924`(생성 2026-09-23T15:47:36Z)가 남아 있고 생성 시각이 같다([volumes-after.txt](volumes-after.txt), S1 폴더의 volumes-inspect-before.txt). 어느 명령도 두 볼륨을 마운트하지 않았다.
- 끝에 `docker compose -p exchange-pml ... --profile obs --profile scale down -v --remove-orphans`로 폐기용 스택과 볼륨 `exchange-pml_chaos-pgdata`를 지웠다([final-down.log](final-down.log)). `exchange-chaos` 프로젝트는 다시 올리지 않았다.
- 이미지 `exchange-chaos-app:pml`과 `exchange-chaos-app:mainref`는 로컬 이미지 저장소에 남아 있다.
- probe가 쓴 k6 로그(`harness/evidence/k6-rt-*.log`)는 부하 계정 키 식별자를 담아 `.gitignore` 대상이며 커밋하지 않았다.
