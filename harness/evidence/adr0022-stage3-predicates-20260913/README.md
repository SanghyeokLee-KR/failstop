# ADR 0022 3단계 판정 술어 실저널 대조와 C22 비용 (2026-09-13)

## 결론

카오스 스택(PostgreSQL 17.10, 앱 2노드)에서 `run_parity.ps1` 을 한 번 끝까지 돌렸다. `result.json` 종료코드 0이다.
대조 규칙과 비용 문턱은 `docs/contracts/하네스_검증설계.md` §4-1 대역 축 술어의 대조 규칙을 따른다.

- 두 마켓 이상 체결 뒤 리더 kill 한 번(epoch 1에서 2)을 만들고 부하를 끊은 정지 저널에 권위 run(`InvariantCheck`),
  `summary.sql`, `invariants.sql`, 개정 전 술어(`legacy_predicates.sql`)를 대었다. 판정 대조 70건 전부 일치, 양성 축 5개 충족,
  NOT RUN 0건이다.
- 대조 저널은 257행, 마켓 3개, `fencing_token` 2종, `created_at` 역행 0행, timeline_id 1이다. C22 동치의 판정력 조건
  (마켓 2 이상, epoch 2 이상)을 넘는다.
- 1상(저널 그대로)에서 C05·C15·C22·C23·C27이 세 벌 모두 0이고 개정 전 술어도 0이다. 2상은 `journal_counters`
  `command_seq` 를 1로 내리고 `outbox` DEFAULT 잎에 행 1개를 넣었다. C15 1, C23 1로 세 벌과 개정 전 술어가 같다.
- C05 대역 항과 C23 대역 팔은 N/A(대상 행 0, 커트오버 전)다. 근거는 `legacy_*.txt` 의 `band_rows` 0이다.
  대역 팔의 판정력은 이 주행이 아니라 `server/app/src/test/java/kr/exchange/harness/InvariantBandAxisIntegrationTest.java` 의
  합성 대역 행 시험이 맡는다.
- C22 문장 비용 PASS다. 1,000,257행에서 세 번 1,051.5 / 1,056.5 / 1,127.2ms, 최댓값 1,127.2ms 대 문턱 1,500ms다.
- 같은 원천(`result.json` `sources` 해시 일치)에서 `gradlew clean build --no-build-cache` 가 BUILD SUCCESSFUL 이다. 시험 XML 85개,
  app 533건과 engine 64건 합 597건, 실패 0, 오류 0, 건너뜀 0이다(`gradle_build_totals.txt`).

## C22 비용 측정 조건

- 앱 노드를 내린 뒤 합성 CANCEL 1,000,000행을 마켓 12개의 대역 잎 `command_journal_b1`~`b12` 에 83,333~83,334행씩 넣었다.
  `seq = market_id * band_span + 지역 번호` 이고 `created_at` 은 마켓을 돌아가며 1ms 씩 늘어 잎 사이 시간 순서가 엇갈린다.
  커트오버 뒤 배치다. 정렬 입력이 `(created_at, seq)` 순서가 아니다.
- 잎 16개 중 행이 든 잎은 13개다(레거시 `command_journal_p0` 257행과 대역 잎 12개). 잎별 행 수는 `result.json` `cost.leafRows`.
- 시간 축 팔은 Append 1,000,257행을 external merge(디스크 33,304kB)로 정렬한 뒤 WindowAgg 를 돈다. 마켓 안 팔은
  `idx_cmd_market` 잎별 Index Scan 을 Merge Append 해 정렬이 없다(`c22_cost_explain_*.txt`).
- 정렬에 실리는 열은 `created_at`, `seq`, `fencing_token` 이라 `payload` 폭은 정렬 비용에 들지 않는다. 합성 행 `payload` 는
  `{}`, `fencing_token` 은 실저널 최댓값 하나다.
- `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY` 안의 `EXPLAIN (ANALYZE, BUFFERS)` 다. psql 세션 `statement_timeout` 0,
  `work_mem` 4MB, `shared_buffers` 128MB, JIT 켜짐. 호스트는 Docker Desktop 29.5.2, NCPU 16, 메모리 16,299,147,264B 다.

## timeline_id 기록의 범위

- 대조 전후 `pg_control_checkpoint()` 의 timeline_id 1을 기록했다(`legacy_*.txt`).
- 이 값은 승격과 시점 복구 이력만 배제한다. 한 프라이머리 안의 서버 시계 조정은 배제하지 못한다(ADR 0022 9-17).
- 순서를 뒤집은 시계 조정은 `created_at_regressions`(0)가 드러낸다.

## 파일

| 파일 | 내용 |
|---|---|
| `run_parity.ps1` | 주행 스크립트. 재현은 `pwsh -NoProfile -File harness/evidence/adr0022-stage3-predicates-20260913/run_parity.ps1`(`harness/.env.chaos` 필요) |
| `result.json` | 대조 114건(판정 70, 참고 44), 양성 축, NOT RUN, 비용, 페일오버, HEAD 와 미커밋 원천 세 파일 SHA-256 |
| `legacy_predicates.sql` | 개정 전 술어, C22 두 팔, 양성 축 조회 |
| `legacy_*.txt` | 위 조회의 대조 전(`<상>`)과 대조 후(`<상>_after`) 출력. 둘이 같아야 정지 저널이다 |
| `authoritative_run_*.json` | 하네스 API 권위 run 응답 |
| `summary_*.txt`, `invariants_*.txt` | 하네스 SQL 두 벌 출력 |
| `c22_cost_load.sql`, `c22_cost_explain.sql`, `c22_cost_explain_*.txt` | 합성 행 적재, 측정 문장, 세 번의 계획 |
| `gradle_build_totals.txt` | 같은 작업 트리의 `gradlew clean build --no-build-cache` 시험 XML 집계 |
| `up.log`, `down.log` | 스택 기동과 `down -v` 파기 |

## 한계

- 로컬 카오스 스택 1회 주행이다. 클라우드 스택 측정은 NOT RUN 이다.
- 비용은 psql 로 C22 문장만 잰 값이다. 앱 권위 run 전체 트랜잭션 시간이 아니다.
- 합성 행은 시간 역행이 없고 마켓 12개에 고르게 나뉜 배치다. 한 마켓 쏠림 배치는 재지 않았다.
- `result.json` `head` 는 커밋 전 작업 트리 기준이다. 원천 대조는 `sources` 해시로 한다.
