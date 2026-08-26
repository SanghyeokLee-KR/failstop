# 안정성 증명 하네스 (M1-6)

격리 다중 노드 카오스 토폴로지와 카오스 실험 E1~E9(실험·스크립트 매핑은 아래 실행 절).
데모 스택(PG 5432·Spring 8080·web 3000)을 건드리지 않는다. 컨테이너명 `chaos-*`,
포트·볼륨·네트워크 전부 분리.

정본: `../docs/하네스_검증설계.md`(C01~C25·E1~E9·실험 판정 프로토콜·RTO), `../docs/하네스_CICD_IaC.md`.

## 구성

| 요소 | 파일 | 비고 |
|---|---|---|
| 토폴로지 | `docker-compose.chaos.yml` | pg·toxiproxy·api-1(리더)·api-2(스탠바이)·lb·bots, (obs)prometheus·grafana |
| 앱 이미지 | `Dockerfile.app` | 호스트 bootJar 기반. up.ps1이 빌드 |
| 변수 | `.env.chaos` | 포트·DB·마스터키·리스 파라미터 |
| 토식 배선 | `toxiproxy/populate.json` | 노드별 pg 프록시(pg-api1 15432 / pg-api2 25432) |
| LB | `nginx/nginx.conf` | api-1 1차 / api-2 백업(리더 kill 시 승계) |
| 실험 설정 | `config/e*.env`(실험별 1:1) | 시드·타임라인·판정 임계(재현용) |
| 스크립트 | `scripts/*.ps1` | up/down + E1~E9 오케스트레이터 + 공용 lib |
| k6 부하 | `load/` | 개방루프 SLO 계측 시나리오 + STOMP/서명 라이브러리(§2-2). `load/README.md` 참조 |
| 검증 SQL | `sql/invariants.sql`,`sql/summary.sql` | 불변식 C01~C25 psql 직조회본(§4-1). 데모 라이브 판정·수동 감사. C24·C25는 V13 미적용 DB에서 SKIP 표기 |
| 관측 규칙 | `prometheus/rules/*.yml` | 레코딩(§5-3)·알람(§5-6). obs 프로파일 |
| 대시보드 | `grafana/dashboards/*.json` | 증명 4장(①무결성 ②복구 ③SLO ④시스템) |
| 프로비저닝 | `grafana/provisioning/**` | 데이터소스(prometheus·pg-proof) + 대시보드 프로바이더 |

## 포트 (데모 스택과 무충돌)

| 서비스 | 호스트 포트 |
|---|---|
| chaos-pg | 55432 |
| chaos-toxiproxy(admin) | 58474 |
| chaos-api-1 | 18080 |
| chaos-api-2 | 28080 |
| chaos-lb | 58080 |
| chaos-prometheus(obs) | 59090 |
| chaos-grafana(obs) | 53001 |

## 실행

PowerShell에서 실행한다.

```powershell
cd $env:EXCHANGE_HOME\harness
pwsh -File scripts/up.ps1            # bootJar 빌드 → 이미지 → compose up --wait
# (관측 스택 포함) pwsh -File scripts/up.ps1 -Obs

pwsh -File scripts/e4-leader-kill.ps1     # E4 리더 kill 실제 실행(파괴적, 격리 스택 한정)
pwsh -File scripts/e2-db-cut.ps1
pwsh -File scripts/e6-spike-failover.ps1

pwsh -File scripts/down.ps1          # 전체 볼륨 파기(append-only 선별 리셋 금지)
```

실험 번호는 `docs/하네스_검증설계.md` §3 정본과 1:1이다(M4 §0-3 개명 완료).

| 실험(docs §3) | 스크립트 | 비고 |
|---|---|---|
| E1 PG 지연/지터 | `scripts/e1-pg-latency.ps1` | 비페일오버 |
| E2 PG 단절·블랙홀 | `scripts/e2-db-cut.ps1` | 페일오버 |
| E3 Redis 장애 | `scripts/e3-redis-outage.ps1` | SKIP=유효 판정(Redisson 미배선) |
| E4 매칭 리더 kill(핵심 데모) | `scripts/e4-leader-kill.ps1` | 구 e1-leader-kill |
| E5 네트워크 분할 | `scripts/e5-net-partition.ps1` | split-brain 반증 |
| E6 폭주+리더 kill(최종 증명) | `scripts/e6-spike-failover.ps1` | 구 e3-spike-failover |
| E7 그레이 장애(pause) | `scripts/e7-gray-pause.ps1` | 좀비 리더 반증 |
| E8 앱 지연·예외 폭풍 | `scripts/e8-app-assault.ps1` | Chaos Monkey assault |
| E9 커밋 응답 절단 | `scripts/e9-commit-cut.ps1` | 멱등성 증명 |

## 판정(기계)

실험 판정 프로토콜: 사전 권위 run(전 체크 green) → 주입(창 기록) → 무개입 관찰 → 사후
권위 run → PASS = 유실 0 ∧ 중복 0 ∧ 자산합 0 ∧ epoch 정확히 +1 ∧ RTO < 30s.

- 사전·사후 권위 run: `POST /harness/v1/invariants/run`(REPEATABLE READ READ ONLY, C01~C19).
- 결과 적재: `invariant_checks`(체크별 ok·위반행·details), `harness.failover_runs`(RTO/RPO/verdict).
- 판정 원천: SQL 위반 행 수 0, epoch 단조 +1, RTO 임계. 사람 눈 판정 없음.

### 결과 적재 위치(산출물 5 정정)

RTO/RPO/verdict는 `harness.failover_runs`에 적재한다. 정본 `recovery_runs`(V3, DB v2.10)에는
rto/rpo/verdict 컬럼이 없고 재생 결정론 지문(fp_replay·fp_live·matched) 전용이라 분리한다.
`recovery_runs`는 승격 시 스냅샷 재생 지문을 적재하는 M4 이후 채워진다. 그전까지 E4 실험의
페일오버 증거 원천은 `failover_runs`다.

### 컨테이너 E4 증거 한정

컨테이너 E4의 RPO는 저널 무결(C05/C06 gapless + C13a 물질화 0)로 판정한다. C16(외부 ACK
원장 → command_journal 대조)과 C17(재생 결정론)은 각각 봇 ACK COPY·재생 지문 배선이 M4
이월이라 이 스택에서는 빈 테이블 대조로 공허 통과한다. 스크립트는 실행 말미에 loadtest_acks·
recovery_runs 행수를 출력해 공허 통과 여부를 증거에 명시한다. C16 실데이터 교차대조와
검증기 반증(위반 주입 검출)은 서버측 통합테스트가 별도 실증한다(아래).

## 데이터 레벨 증거(CI 재현)

`server`의 `InvariantVerifierIntegrationTest`가 봇 체결 → 사전 권위 run(C01~C19 green) →
리더 상실·승격(epoch+1)·스테일 fencing 거부 → 사후 권위 run → `failover_runs` PASS 적재를
Testcontainers로 재현한다. 컨테이너 E4(스탠바이 실엔진 체결 재개)의 데이터 레벨 절반을
CI에 고정한 것. 사전·사후 run이 봇 NEW 명령을 `loadtest_acks`로 적재해 C16 조인을 실데이터로
평가하므로, 이 경로의 C16은 공허 통과가 아니다.

`verifierCatchesInjectedViolations`(음성 테스트)는 위반을 고의 주입해 검증기 자체를 반증한다.
recovery_runs에 `matched=false` 1건 → C17 검출·제거 후 clean 복귀, loadtest_acks에 미저널 ACK
1건 → C16 검출·criticalClean/pass=false·invariant_checks에 ok=false 적재. "항상 통과하는
검증기" 가능성을 배제한다.

CI 재현 게이트는 `.github/workflows/ci-backend.yml`(ubuntu-latest, `./gradlew build`)이다.
ubuntu 러너는 ASCII 경로라 비ASCII 경로 + cp949 워커의 클래스패스 손상과 무관하게 green을
재현한다. 로컬 Windows도 프로젝트를 ASCII 경로(`$env:EXCHANGE_HOME`)에 두면 동일하게 통과한다.

## 관측성 배선

obs 프로파일(`up.ps1 -Obs`)로 prometheus(59090)·grafana(53001)를 띄운다. 대시보드·알람은
파일 프로비저닝이라 컨테이너 기동 즉시 로드된다. PromQL은 서버 실등록 메트릭 7종에만 배선한다:
`exchange_trades_total`, `exchange_order_latency_seconds_*`, `exchange_leader_epoch`,
`exchange_leader_is_leader`, `exchange_ledger_imbalance`, `exchange_failover_downtime_seconds`,
`exchange_invariant_violations`. DB 원본 패널(`invariant_checks`·`recovery_runs`·
`harness.failover_runs`)은 pg-proof 데이터소스로 직조회한다.

정본(하네스_CICD_IaC.md §5-2/§5-3/§5-6) 대비 격리 하네스의 의도된 축약:

- 스크레이프 타깃은 dev 8080 `/actuator/prometheus`다. 정본 §5-3의 admin 9404 분리는 prod
  토폴로지 규약이고, 이 스택 api 노드는 dev 노출이다.
- 알람은 Prometheus 네이티브 규칙으로 평가한다(§5-6은 Grafana 통합 얼럿). Alertmanager를
  생략해도 발화 상태는 `ALERTS` 시계열·`/alerts`로 조회 가능해 기계 판정이 성립한다. contact
  point 라우팅은 정본 infra/ 스택이 맡는다.
- pg-proof는 컴포즈 PG 계정을 읽기용으로 쓴다(§5-4 `grafana_ro` 롤 하드닝은 infra/ 대상).
- 무데이터 패널을 증거로 두지 않는다. 서버 미등록 메트릭(`exchange_duplicate_fills_total`,
  `exchange_orders_rejected_total`, `exchange_sequencer_queue_size`,
  `exchange_journal_replay_commands_total`, `exchange_orderbook_depth`, `exchange_ws_clients`)에
  걸린 §5-5 패널·§5-6 `DuplicateFill` 알람은 배선하지 않고 대시보드 텍스트로 사유를 명시한다.
  중복 체결 검출은 `exchange_invariant_violations{check_name="TRADE_SEQ_DUP"}`(C08)이 DB
  원본으로 방어하므로 `InvariantViolation` 알람이 커버한다.

## 한계

- LB는 api-1 1차/api-2 백업 정적 구성이다. 엄밀한 리더 인지 라우팅이 아니라 kill 시 백업
  승계에 의존한다. 리더 인지 라우팅은 M4 하드닝 대상.
- 스냅샷 미구현(README M1-6). 승격 노드는 SingleWriterLoop catch-up으로 저널 전량 재생해
  인메모리 북을 복원한다. 저널 성장 시 재생 시간이 RTO에 선형 반영된다.
- E6 스파이크는 봇 노드 재기동 기반 축약 재현이다. 정본 개방루프 스파이크(k6
  `load/order_flow.js` PROFILE=e6)는 별도.
