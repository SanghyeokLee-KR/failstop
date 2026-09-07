# fencing 왕복 축소 (2026-09-07)

증거 `scenario-20260907` 5 단계가 남긴 다음 표적이다. 주문 하나가 단일 라이터를 통과하는 데
쓰는 시간을 줄이려면 그 스레드가 하는 일을 줄여야 한다. fencing 검증이 그중 하나다.

## 종전 구조

저널 적재와 정산이 같은 트랜잭션에서 두 문장을 연달아 보냈다.

| 문장 | 하는 일 | 없으면 |
|---|---|---|
| `SELECT assert_current_leader(h, e)` | 시드행·holder·epoch·만료를 서로 다른 메시지로 RAISE | 거부 사유를 잃는다. 자바가 그 문구로 강등을 분류한다 |
| `SELECT epoch ... FOR SHARE` | 커밋까지 epoch 불변을 강제 | 판정과 커밋 사이가 무방비가 된다 |

**둘은 겹치지 않는다.** 하나를 지우는 것으로는 합칠 수 없고, 둘 다 하는 함수가 필요하다.

## 실측 (pg_stat_statements, 명령당)

| 문장 | 종전 | 현행 |
|---|---|---|
| `assert_current_leader` (앱이 보냄) | 2.73 | **1.00** |
| `assert_and_lock_leader` (앱이 보냄) | - | **1.00** |
| `SELECT epoch ... FOR SHARE` (앱이 보냄) | 1.00 | **0 (함수 안으로)** |
| **앱이 보내는 fencing 왕복** | **3** | **2** |

서버 실행 시간은 `assert_current_leader` 가 건당 0.040ms 로 작다. **비용은 실행이 아니라 왕복
자체**라 왕복 수가 판정 축이다.

## 설계에서 갈린 지점

처음에는 검증 로직을 새 함수에 **복사**했다. 컴파일과 대부분의 시험을 통과했으나
`PromotionStandDownIntegrationTest.recoverableStandDownNeverOpensTwoLeaderWindow` 가 깨졌다.

그 시험은 `assert_current_leader` 를 **갈아끼워** fencing 거부를 주입한다
(`installFencingFault`). 검증을 복사하면 그 주입이 정산과 이벤트 적재를 비껴가고,
**split-brain 을 막는 시험이 조용히 무력해진다.**

그래서 새 함수가 잠금만 잡고 판정은 기존 함수에 위임하도록 바꿨다. 규칙이 한 곳에 남고 주입도
그대로 닿는다. 시험이 통과한다.

**시험이 설계를 고쳤다.** 복사본이었다면 이 결함이 라이브까지 갔다.

## 독립 검증

fencing 은 split-brain 을 막는 자리라 병합안을 적대적으로 검증했다. 네 갈래로 반증을 시도했고
전부 실패했다.

| 공격 축 | 결과 |
|---|---|
| 잠금 획득 시점 | 반증 실패. 잠근 뒤 판정하므로 판정과 커밋 사이에 epoch 이 바뀔 수 없다 |
| 만료 경계 | 반증 실패. 통과조건(`>= now`)과 탈취조건(`< now`)이 정확한 여집합이라 두 노드가 동시에 참이 되지 않는다 |
| 트랜잭션 격리 | 반증 실패. 쓰기 경로는 READ COMMITTED 고 REPEATABLE READ 는 읽기 전용 검증에만 쓰인다 |
| 예외 처리 경로 | **결함 1건 발견** (아래) |

### 발견된 결함과 처리

새 함수는 stale 일 때 0행이 아니라 RAISE 한다. 그래서 호출자의 `StaleLeaderException` 분기가
사실상 도달 불가능해지고, **정산 경로의 강등 판정이 전적으로 문자열 매칭 하나에 걸린다**
(`FencingErrors.isFencingRejection` 이 P0001 과 `fencing 위반` 문구를 요구한다).

종전에는 정산이 두 경로로 stale 을 잡았다. 0행이면 예외를 던지고, RAISE 면 문구를 봤다. 병합이
앞쪽을 없앴다.

문구가 어긋나면 **stale 리더가 강등되지 않고 자기를 리더로 믿는다.** 쓰기는 계속 막히지만 그
상태가 조용히 지속된다.

`MigrationSmokeTest` 에 계약을 못 박아 닫았다. 새 함수가 실제로 P0001 과 `fencing 위반` 문구로
던지는지, 정상 경로가 epoch 을 돌려주는지를 실제 DB 에서 확인한다. **문구가 바뀌면 런타임이
아니라 빌드가 깨진다.**

## 남은 위험

- **fencing 은 저렴한 항목이었다.** 총 서버 실행 시간 112686ms 중 `assert_current_leader` 가
  482ms 로 0.4% 다. 같은 창에서 `next_journal_value('order_id')` 가 3202ms, 그 안의
  `UPDATE journal_counters` 가 2553ms 로 **fencing 의 12 배**다. 다음 표적은 그쪽이다.
- 왕복 축소의 처리량 효과를 격리해 재지 못했다. 전후 측정이 서로 다른 부하 조건에서 났다.
  확정한 것은 왕복 수(3 -> 2)뿐이고 처리량 이득은 미측정이다.
- `CommandJournalAppender` 는 손대지 않았다. 그 경로의 `assert_current_leader` 는
  INSERT...SELECT 의 자체 fencing 비교와 겹치는 의도된 이중 방어이고, 병합하면 잠금 보유
  구간이 채번과 advisory lock 대기를 감싸게 되어 리더 하트비트와 새로 결합한다.
- 정산 트랜잭션이 `FOR SHARE` 를 커밋까지 쥐므로 그 길이가 곧 리스 갱신 차단 시간이다. 정산이
  남은 TTL 을 넘기면 정당한 단독 리더가 자기 정산으로 자기 하트비트를 굶길 수 있다. 이 위험은
  병합 이전부터 있었고 이번에 바뀌지 않았으나, 정산 tx 지속시간 분포를 TTL 대비로 실측한 적이
  없다.
