# 안정성 증명 하네스 (M1-6)

격리 다중 노드 카오스 토폴로지와 카오스 실험 E1~E3. 데모 스택(PG 5432·Spring 8080·web
3000)을 건드리지 않는다. 컨테이너명 `chaos-*`, 포트·볼륨·네트워크 전부 분리.

정본: `../docs/하네스_검증설계.md`(C01~C19·E1~E9·실험 판정 프로토콜·RTO), `../docs/하네스_CICD_IaC.md`.

## 구성

| 요소 | 파일 | 비고 |
|---|---|---|
| 토폴로지 | `docker-compose.chaos.yml` | pg·toxiproxy·api-1(리더)·api-2(스탠바이)·lb·bots, (obs)prometheus·grafana |
| 앱 이미지 | `Dockerfile.app` | 호스트 bootJar 기반. up.ps1이 빌드 |
| 변수 | `.env.chaos` | 포트·DB·마스터키·리스 파라미터 |
| 토식 배선 | `toxiproxy/populate.json` | 노드별 pg 프록시(pg-api1 15432 / pg-api2 25432) |
| LB | `nginx/nginx.conf` | api-1 1차 / api-2 백업(리더 kill 시 승계) |
| 실험 설정 | `config/e1.env`,`e2.env`,`e3.env` | 시드·타임라인·판정 임계(재현용) |
| 스크립트 | `scripts/*.ps1` | up/down + E1~E3 오케스트레이터 + 공용 lib |

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

PowerShell에서(한글 경로 이슈 회피 위해 정션 `C:\dev\exchange` 사용).

```powershell
cd C:\dev\exchange\harness
pwsh -File scripts/up.ps1            # bootJar 빌드 → 이미지 → compose up --wait
# (관측 스택 포함) pwsh -File scripts/up.ps1 -Obs

pwsh -File scripts/e1-leader-kill.ps1     # E1 실제 실행(파괴적, 격리 스택 한정)
pwsh -File scripts/e2-db-cut.ps1
pwsh -File scripts/e3-spike-failover.ps1

pwsh -File scripts/down.ps1          # 전체 볼륨 파기(append-only 선별 리셋 금지)
```

## 판정(기계)

실험 판정 프로토콜: 사전 권위 run(전 체크 green) → 주입(창 기록) → 무개입 관찰 → 사후
권위 run → PASS = 유실 0 ∧ 중복 0 ∧ 자산합 0 ∧ epoch 정확히 +1 ∧ RTO < 30s.

- 사전·사후 권위 run: `POST /harness/v1/invariants/run`(REPEATABLE READ READ ONLY, C01~C19).
- 결과 적재: `invariant_checks`(체크별 ok·위반행·details), `harness.failover_runs`(RTO/RPO/verdict).
- 판정 원천: SQL 위반 행 수 0, epoch 단조 +1, RTO 임계. 사람 눈 판정 없음.

### 결과 적재 위치(산출물 5 정정)

RTO/RPO/verdict는 `harness.failover_runs`에 적재한다. 정본 `recovery_runs`(V3, DB v2.10)에는
rto/rpo/verdict 컬럼이 없고 재생 결정론 지문(fp_replay·fp_live·matched) 전용이라 분리한다.
`recovery_runs`는 승격 시 스냅샷 재생 지문을 적재하는 M4 이후 채워진다. 그전까지 E1 실험의
페일오버 증거 원천은 `failover_runs`다.

### 컨테이너 E1 증거 한정

컨테이너 E1의 RPO는 저널 무결(C05/C06 gapless + C13a 물질화 0)로 판정한다. C16(외부 ACK
원장 → command_journal 대조)과 C17(재생 결정론)은 각각 봇 ACK COPY·재생 지문 배선이 M4
이월이라 이 스택에서는 빈 테이블 대조로 공허 통과한다. 스크립트는 실행 말미에 loadtest_acks·
recovery_runs 행수를 출력해 공허 통과 여부를 증거에 명시한다. C16 실데이터 교차대조와
검증기 반증(위반 주입 검출)은 서버측 통합테스트가 별도 실증한다(아래).

## 데이터 레벨 증거(CI 재현)

`server`의 `InvariantVerifierIntegrationTest`가 봇 체결 → 사전 권위 run(C01~C19 green) →
리더 상실·승격(epoch+1)·스테일 fencing 거부 → 사후 권위 run → `failover_runs` PASS 적재를
Testcontainers로 재현한다. 컨테이너 E1(스탠바이 실엔진 체결 재개)의 데이터 레벨 절반을
CI에 고정한 것. 사전·사후 run이 봇 NEW 명령을 `loadtest_acks`로 적재해 C16 조인을 실데이터로
평가하므로, 이 경로의 C16은 공허 통과가 아니다.

`verifierCatchesInjectedViolations`(음성 테스트)는 위반을 고의 주입해 검증기 자체를 반증한다.
recovery_runs에 `matched=false` 1건 → C17 검출·제거 후 clean 복귀, loadtest_acks에 미저널 ACK
1건 → C16 검출·criticalClean/pass=false·invariant_checks에 ok=false 적재. "항상 통과하는
검증기" 가능성을 배제한다.

CI 재현 게이트는 `.github/workflows/ci-backend.yml`(ubuntu-latest, `./gradlew build`)이다.
ubuntu 러너는 ASCII 경로라 Windows 정션(비ASCII 물리 경로) + cp949 워커의 클래스패스 손상과
무관하게 green을 재현한다. 로컬 Windows 실행은 `gradle.properties`·`app/build.gradle.kts`의
`sun.jnu.encoding=UTF-8` 고정으로 콘솔 코드페이지와 무관하게 통과한다.

## 한계

- LB는 api-1 1차/api-2 백업 정적 구성이다. 엄밀한 리더 인지 라우팅이 아니라 kill 시 백업
  승계에 의존한다. 리더 인지 라우팅은 M4 하드닝 대상.
- 스냅샷 미구현(README M1-6). 승격 노드는 SingleWriterLoop catch-up으로 저널 전량 재생해
  인메모리 북을 복원한다. 저널 성장 시 재생 시간이 RTO에 선형 반영된다.
- E3 스파이크는 봇 노드 재기동 기반 축약 재현이다. 정본 개방루프 스파이크(k6
  `load/order_flow.js` PROFILE=e6)는 별도.
