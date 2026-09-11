# ADR 0022. 명령 저널을 마켓별 순서 원장으로 가른다

- 상태: 제안. 0단계 착수 게이트 통과 전에는 착수하지 않는다
- 날짜: 2026-09-11
- 선례: `docs/adr/0021-market-partitioned-ordering.md`(이벤트 축), `V36__market_seq.sql`
- 근거 실측: `harness/evidence/submit-lock-20260911/README.md`, `harness/evidence/wal-fsync-20260907/README.md`, `harness/evidence/leader-failover-20260908/`
- 코드: `V39__command_seq_band.sql`, `V40__band_headroom.sql`, `EngineBridge`, `CommandJournalAppender`, `EngineRecovery`, `ReplayService`, `WriterRouter`, `InvariantCheck`

---

## 1. 무엇을 왜 쪼개는가

### 1-1. 실측이 상한을 특정했다

2026-09-11 회차(카오스 obs 프로파일, DB 4 vCPU/4g, 커넥션 풀 64 고정, 마켓 12, k6 `calibrate.js` 150/s 130초, 칸마다 `down -v`).

| 정원 | 접수/s | 락 보유 점유 | 락 대기 점유 | 락 보유 1회 | 라이터 sink 점유 |
|---|---|---|---|---|---|
| 24 | 127.56 | 0.993 | 19.046 | 7.723밀리초 | 2.205 |
| 36 | 130.97 | 0.993 | 31.085 | 7.522밀리초 | 2.171 |
| 48 | 132.12 | 0.993 | 42.997 | 7.402밀리초 | 2.146 |

- 보유 점유 0.993 은 전역 락 하나의 물리 상한 1.0 이다. 정원을 네 배 벌려도 접수가 평평하고 대기만 19.0 에서 43.0 으로 늘었다.
- 분해에서 락 보유 1회 6.935밀리초 중 6.879밀리초가 저널 append 이고 호출 수가 35,647 로 같다. 보유의 99.2퍼센트다.
- 1 을 6.935밀리초로 나누면 초당 144.2 이고 관측 천장 126~132 가 그 88~92퍼센트다.
- 소거된 후보를 함께 적는다. DB 규격(4코어 중 1.5), 라이터 직렬 구간(12.0 중 2.2), 수용 정원(네 배 평평), 커넥션 풀(16에서 128 평평), 행 잠금(3.8퍼센트).

곧 상한은 `EngineBridge.submitLock`(EngineBridge.java:33) 하나다. 모든 마켓의 모든 접수가 이 락을 지나고 그 안에서 `command_journal` 에 기록하고 커밋한다.

### 1-2. 락만 가를 수 없다

`CommandJournalAppender.appendInTx`(CommandJournalAppender.java:99-101)가 append 트랜잭션 안에서 `SELECT next_journal_value('command_seq')` 를 부른다. 그 함수는 `journal_counters` 한 행의 `UPDATE`(V1__init.sql:551-565)이고 행 배타 잠금이 커밋까지 유지된다. 락을 가르면 12개 트랜잭션이 JVM 락 대신 이 행에서 다시 줄을 선다.

V36 이 같은 형태를 격리 실측으로 쟀다(ADR 0021 「결정」 표).

| 라이터 | 전역 카운터 | 마켓별 카운터 |
|---|---|---|
| 1 | 39.7 | 39.7 |
| 4 | 42.9 | 168.2 |
| 12 | 43.5 | 514.5 |

곧 "락을 가른다" 는 "채번을 가른다" 의 종속 결과이고 순서를 바꿀 수 없다. 이 판은 채번을 먼저 가르고 락을 그 위에 얹는다.

### 1-3. 덤으로 닫히는 것 셋

셋 다 이 판이 만드는 문제가 아니라 지금 이미 열려 있는 구멍이다.

1. **갭 fast path 소실.** 마켓별 라이터에서 전역 채번을 쓰면 한 마켓 스트림의 seq 가 성기다. `SingleWriterLoop.process`(SingleWriterLoop.java:236)의 `command.seq() > last + 1` 이 사실상 모든 라이브 명령에서 참이라 명령마다 `catchUpTo` 가 저널을 한 번 더 SELECT 한다. 그 왕복은 `applyNanos`·`sinkNanos`·`hookNanos` 어디에도 계상되지 않으므로 실측 "라이터 sink 점유 2.146" 은 과소 보고다. 대역 파생이면 마켓 안에서 seq 가 +1 씩 증가해 이 왕복이 사라진다.
2. **전역 체크포인트가 12 라이터에서 이미 불건전하다.** `EventJournalReader.checkpoint()`(EventJournalReader.java:22-31)가 `max(source_cmd_seq)` 하나를 읽고 "이 값 이하는 이벤트가 전부 있다" 로 해석한다. 그 해석은 라이터가 하나일 때만 참이다. 라이터가 열둘이면 그 값은 가장 앞선 마켓의 것이고, 뒤진 마켓의 in-flight 명령이 상한 안에 들어와 `EngineRecovery.verifiedTo`(EngineRecovery.java:306-310)의 "체크포인트 상한 재생에서 pending 이 나왔다" 로 기동을 세운다. 2026-09-08 두 노드 실측에서 장애 노드가 재합류하지 못한 경로와 같은 모양이다.
3. **`WriterRouter.installFrom` 의 무음 폐기.** 복구본의 전역 `lastAppliedSeq` 스칼라 하나를 전 마켓 엔진에 심는다(WriterRouter.java:121-143). 그 값은 어느 한 마켓의 값이므로 다른 마켓 엔진은 자기 명령을 `seq <= lastAppliedSeq`(MatchingEngineImpl.java:22-24)로 조용히 버린다. 예외도 로그도 없다. 상시 감사에 안 보인다(C13A 가 AUTHORITATIVE_ONLY).

### 1-4. 파티션 시한

`command_journal` 은 `PARTITION BY RANGE (seq)` 이고 파티션이 `p0 [0,10^8)`, `p100000000 [10^8,2x10^8)`, `DEFAULT` 셋뿐이다(V1__init.sql:592-594). V1 이후 파티션을 더한 마이그레이션이 0건이다. 현재 저널이 약 10^6 자리이므로 132.12/s 로 `DEFAULT` 유입까지 약 17.4일이다. `DEFAULT` 에 한 행이라도 들어가면 그 구간의 파티션을 영영 만들 수 없다(PostgreSQL 이 `DEFAULT` 를 훑어 거부한다). C15 는 WARN 이라 멈추지 않는다.

**이 시한은 이 판을 하든 안 하든 온다.** V39 가 그것을 먼저 닫는다.

---

## 2. 역할 분리. V36 이 event_seq 에 한 것과의 대응

### 2-1. 값 공간

```
seq = marketId * BAND + market_cmd_seq        BAND = 10^12, market_cmd_seq 는 1 부터
market_cmd_seq = seq - marketId * BAND        역산. 감사와 뷰에서만 쓴다
```

마켓 m 의 행은 정확히 `[m*10^12 + 1, (m+1)*10^12 - 1]` 에 있다. 마켓 id 는 1..63 으로 상한하고 최대 seq 는 6.4x10^13 이다.

### 2-2. 대응표

| 역할 | event_seq (V36) | command seq (이 판) |
|---|---|---|
| 전역 유일 식별자 | `event_seq`, 대역 파생 | `seq`, 대역 파생. 같다 |
| 마켓 안의 순서 | `market_seq` **별도 컬럼** | `seq` 자체. **새 컬럼을 두지 않는다** |
| 결번 없음 대상 | C06. 마켓별, 구판 행 제외 | C05. 두 항 합. 구판 행 **포함** |
| 파티션 키 | `RANGE(event_seq)`. 기존 `p0` 경계 2x10^8 안에 들어가려고 대역을 10^7 로 잡았다 | `RANGE(seq)`. 대역마다 파티션을 새로 만든다. 그래서 대역이 10^12 다 |
| 재생 커서 | `(source_cmd_seq, event_ordinal)` | 마켓별 `seq` 커서 |
| 채번 | `market_journal_counters` 행 UPSERT, append 와 같은 트랜잭션 | 같다 |

### 2-3. 두 축의 실제 차이 넷

**(1) 명령 축은 새 컬럼을 두지 않는다. 이것이 이 판의 결정적 선택이다.**

V36 은 `market_seq` 를 컬럼으로 더했고 V36 이전 행은 그 컬럼이 영구히 NULL 이다(append-only 트리거 `trg_ej_noupd` 가 백필 UPDATE 를 물리로 막는다). 그래서 이벤트 축 검사는 구판 행을 대상에서 뺐다.

명령 축에서 같은 선택을 하면 재생이 깨진다. 커트오버 이전 저널 약 97.7만 행(2026-09-08 실측)에 그 컬럼이 영구 NULL 인데, 소비측 커서·갭 판정·갭 회수·체크포인트·승격·스냅샷의 축을 전부 그 컬럼으로 옮기면 그 구간을 읽을 수단이 없다. 스냅샷이 한 건도 안 찍혀 복구가 항상 seq 0 전량 재생이므로(6-4 참조) 첫 기동 재생이 그 구간에서 바로 깨진다.

대역 파생은 같은 컬럼(`seq`)의 **값만** 옮기므로 그 문제가 없다.

- 마켓 m 의 레거시 행은 `seq < 10^7` 이고 대역 행은 `seq > 10^12` 다. 둘 다 오름차순이고 대역 행이 항상 크다.
- 레거시 구간은 전역 락이 직렬화했으므로 seq 순서가 곧 커밋 순서다. 대역 구간은 마켓별 락이 직렬화하므로 마켓 안에서 seq 순서가 곧 커밋 순서다.
- 곧 `WHERE market_id = m AND seq > cursor ORDER BY seq` 라는 한 커서가 커트오버 경계를 그대로 넘는다. 경계를 아는 코드가 한 줄도 필요 없다.

부수로 체크섬이 그대로 산다. `JournalChecksum.of` 의 정규 문자열 첫 두 필드가 `seq|marketId` 다(JournalChecksum.java:43-44). 파생을 쓰면 마켓 안의 순서 번호가 그 둘의 파생값이므로 체크섬이 순서 축을 이미 덮는다. 별도 컬럼을 더하면 순서 축이 체크섬 밖으로 나가 변조 탐지가 재생 축을 못 덮는다. `verify` 는 `ReplayService` 와 `JournalCatchUpSource` 가 공유하는 단일 지점이므로(JournalChecksum.java:33-40) 정규 문자열은 바꾸지 않는다.

**(2) 명령 축에는 C22 가 있고 이벤트 축에는 없다.**

`C22_FENCING_MONOTONIC` 이 `lag(fencing_token) OVER (ORDER BY seq)` 로 epoch 비감소를 본다(InvariantCheck.java:249). 대역 파생이면 seq 오름차순이 시간 순서가 아니므로 마켓 경계마다 거짓 CRITICAL 이 난다. V36 이 이 문제를 안 만난 이유는 이벤트 축에 대응 검사가 없기 때문이다. 6-2 에서 시간 축을 `created_at` 으로 재정의한다.

**(3) 실패 반경이 다르다.**

이벤트 축의 채번 예외는 정산 트랜잭션 안, 곧 라이터 스레드에서 나므로 `LoopFatalHandler` 를 타고 리더십까지 내려간다. 명령 축의 채번 예외는 append 트랜잭션 안, 곧 접수 스레드에서 난다. `FencingErrors.isFencingRejection` 은 P0001 중 '`fencing 위반`' 문구만 강등 사유로 인정하므로(FencingErrors.java:16-23) '마켓 순서 대역 초과' 는 강등을 만들지 않는다. 라이터는 계속 돌고 그 마켓의 접수만 5xx 가 된다.

**(4) 대역 폭이 10^5 배 다르다.**

V36 이 10^7 을 고른 이유는 기존 `event_journal_p0` 경계 2x10^8 안에 들어가려는 것이었고, 그 대가가 정본 13.3 한계 43(마켓당 10.3일, 최다 마켓 6.9일)이다. 이 판은 파티션을 새로 만들므로 그 제약이 없다. 접수 132.12/s 기준으로 비교한다.

| 대역 | 12마켓 균등(마켓당 11.01/s) | 단일 마켓 집중(132.12/s) | 이 판 예고 210/s 단일 마켓 |
|---|---|---|---|
| 10^7 (V36 값) | 10.5일 | 21.0시간 | 13.2시간 |
| 10^12 (이 판) | 2,880년 | 240년 | 151년 |

상한 둘을 확인했다. `BIGINT` 최대 9.223x10^18 대비 6.4x10^13 은 여유 14만 배다. JavaScript `Number` 안전 정수 2^53 = 9.007x10^15 대비 여유 140배다. 후자가 실제 제약이다. 접수 응답의 `seq` 가 JSON 숫자로 나가므로(`OrderResponse.seq`) 대역을 10^15 이상으로 두면 브라우저에서 정밀도가 깨진다.

폭이 넓으면 커트오버 여유도 함께 산다. 대역 하한이 10^7 이면 전역 카운터가 132.12/s 로 19.0시간 만에 마켓 1 대역을 침범한다. 곧 10^7 은 커트오버 자체가 경주였다.

---

## 3. 스키마 변경

### 3-1. V39__command_seq_band.sql (되돌릴 수 있다. 채번 경로를 바꾸지 않는다)

**(1) 대역 명세표.** 대역 상수를 코드·마이그레이션·감사 세 곳에 복제하지 않기 위한 단일 원천이다.

```
CREATE TABLE journal_band_spec (
    name           TEXT     PRIMARY KEY,
    band_span      BIGINT   NOT NULL CHECK (band_span > 0),
    max_band_index SMALLINT NOT NULL CHECK (max_band_index >= 1)
);
INSERT INTO journal_band_spec VALUES ('event_seq', 10000000, 19), ('command_seq', 1000000000000, 63);
```

`event_seq` 행은 V36 의 하드코딩 값을 글자 그대로 옮긴 것이다. 의미가 바뀌지 않는다.

**(2) 축 경계 상수.** V36 이전 `event_journal` 행은 `market_id` 가 NULL 이라 마켓별 체크포인트에 들어오지 않는다. 그 구간의 상한은 전역 스칼라 하나로 고정돼 있고 다시 늘지 않으므로 마이그레이션이 한 번 계산해 박제한다. 이것이 없으면 V36 이후 이벤트가 하나도 없는 조용한 마켓의 체크포인트가 0 이 되고, 그 마켓의 레거시 명령 전부가 크래시 창으로 오판된다.

```
CREATE TABLE journal_axis_boundary (name TEXT PRIMARY KEY, cmd_seq BIGINT NOT NULL);
INSERT INTO journal_axis_boundary
SELECT 'pre_v36_event', COALESCE(max(source_cmd_seq), 0) FROM event_journal WHERE market_id IS NULL;
```

배포 시 일회 확인이 붙는다. `cmd_seq` 이하의 명령 중 이벤트가 없는 것이 0건이어야 한다. 0 이 아니면 V36 이전 구간이 단일 라이터였다는 전제가 틀린 것이고 여기서 멈춘다.

**(3) 파티션을 코드보다 먼저 만든다.**

- 레거시 꼬리 `command_journal_p200000000` = `[2x10^8, 10^12)` 하나. **이것 하나가 1-4 의 17.4일 시한을 닫는다.** 이 판을 접어도 이 파티션은 남긴다.
- 대역 `command_journal_b<K>` = `[K*10^12, (K+1)*10^12)` 를 K=1..63 에 대해.
- 전부 비어 있으므로 DROP 으로 되돌릴 수 있다. 대역 값이 처음 쓰이는 것은 6단계이고 이것은 1단계다. 배포 순서가 곧 안전이다.

**(4) `next_market_journal_value` 일반화.** 하드코딩된 `> 10000000`(V36:66)을 `journal_band_spec` PK 조회로 바꾼다. 2행짜리 표의 PK 조회라 공유 버퍼에서 끝나지만 이 함수는 정산 핫패스이므로 1단계 배포 전후로 `exchange.writer.sink.nanos` 를 대조한다.

**(5) `next_market_command_seq(p_market_id SMALLINT) RETURNS BIGINT` 신설.** `band_span`·`max_band_index` 를 조회하고, `p_market_id < 1 OR > max_band_index` 면 예외, `next_market_journal_value(p_market_id, 'command_seq', 1)` 로 지역 번호를 받고, 지역 번호가 `band_span` 이상이면 예외, `p_market_id * band_span + local` 을 반환한다. 파생을 DB 에 두어 앱이 대역 폭을 모르게 한다.

**(6) 시드와 트리거.** `market_journal_counters` 에 기존 마켓의 `'command_seq'` 행을 넣고, `seed_market_journal_counters`(V36:78-89)를 확장해 `'command_seq'` 도 시드하며 `NEW.id > max_band_index` 면 거부한다. `markets.id` 는 `SMALLINT PRIMARY KEY` 로 운영이 값을 직접 준다(V1:38). 파티션 없는 마켓이 애초에 만들어지지 않게 하는 구조적 차단이 이것이다.

**(7) 권한.** GRANT 는 표 단위라 그대로이고 새 함수에만 EXECUTE 를 준다. 두 새 표는 `harness_role` 에 SELECT.

되돌리기: 파티션 DROP(비어 있음), 두 함수 CREATE OR REPLACE 복귀, 시드 행 DELETE, 표 둘 DROP.

### 3-2. V40__band_headroom.sql (7단계)

- `v_journal_band_headroom` 뷰. 마켓별 `next_value`, `band_span - next_value`, 잔여 비율.
- 게이지 `exchange.journal.band.remaining{name,market}`. 정적 바닥은 SQL 이, 소진 속도는 게이지가 본다.

### 3-3. V41__event_seq_band_widen.sql (선택. 별건)

이벤트 축의 한계 43 을 같은 처방으로 닫는다. `journal_band_spec` 의 `('event_seq', 10^7, 19)` 를 `(10^12, 63)` 으로 올리고 `event_journal` 파티션을 같은 모양으로 더한다. 이벤트 축은 `market_seq` 가 별도 컬럼이라 지역 번호가 이어지고 파생값만 뛴다. 기존 행은 그대로 유일하고 마켓 안에서 증가도 유지된다. **이 판의 필수가 아니다.** 명령 축 커트오버가 안정화된 뒤 별도 회차로 판단한다.

### 3-4. 새 컬럼·새 인덱스가 없다

`command_journal` 에 컬럼을 더하지 않는다. 인덱스도 더하지 않는다. 마켓별 읽기는 기존 `idx_cmd_market (market_id, seq)`(V1:585)로 끝나고 대역 술어가 파티션 프루닝까지 한다. 멱등 조회는 기존 `idx_cmd_client_new` 로 끝난다(정렬 대상이 0 또는 1행이라 추가 정렬 비용이 없다). 크래시 창 정렬은 메모리에서 한다(5-3).

---

## 4. 코드 변경

### 4-1. 접수 락

`EngineBridge.submitLock`(EngineBridge.java:33)의 `ReentrantLock` 하나를 마켓별로 바꾼다.

```
private final ConcurrentHashMap<Integer, ReentrantLock> submitLocks = new ConcurrentHashMap<>();
private ReentrantLock lockFor(int marketId) {
    return submitLocks.computeIfAbsent(marketId, id -> new ReentrantLock());
}
```

- 배열 + `marketId % N` 스트라이핑을 쓰지 않는다. 모듈로는 두 마켓을 한 락에 합칠 뿐 한 마켓을 두 락에 가르지 않아 안전하기는 하나, 마켓 id 가 63 으로 상한된 이상 맵이 더 단순하고 병렬성이 정확하다.
- `synchronized` 로 바꾸지 않는다. 임계구역 안에서 DB 를 왕복하고 JDK 21 의 `synchronized` 블로킹은 가상 스레드를 캐리어에 고정한다. 2 vCPU 실측에서 주문 16/s 에 노드가 영구 정지했다(`harness/evidence/gateoff-20260903-2vcpu/`). 락 종류는 바꾸지 않는다.

**계약이 왜 그대로 성립하는가.** 락이 지키는 것은 append 커밋(seq 확정)과 `sink.offer` 사이의 역전이고(EngineBridge.java:15-18), 역전이 해로운 이유는 소비측이 뒤진 seq 를 폐기하기 때문이다. 그 소비측은 이미 마켓별이다(`WriterRouter`, ADR 0021 5단계). 곧 계약이 실제로 요구하는 것은 마켓 안의 투입 순서다. 마켓 m 의 락 안에서만 m 의 채번과 커밋과 offer 가 일어나므로 m 의 `market_cmd_seq` 는 락 획득 순서대로 배정되고 같은 순서로 큐에 들어간다. 마켓 사이에는 순서 계약이 없고 필요하지도 않다. 북이 마켓 단위이기 때문이다.

**계측도 함께 가른다.** `lockWaitNanos`·`lockHoldNanos`·`submitCount` 세 `LongAdder`(EngineBridge.java:43-48)를 마켓별로 두고 `EngineConfig` 의 `FunctionCounter` 등록에 `market` 태그를 반드시 붙인다. 태그 없이 등록하면 두 번째 마켓에서 미터 id 가 충돌해 첫 것만 관측된다. 이 저장소가 2026-09-10 에 같은 사고를 두 번 겪었다(EngineConfig.java:262-270 주석).

### 4-2. 접수 게이트

`submit` 의 게이트 셋 중 둘이 전역이다(EngineBridge.java:120-134). **둘 중 하나만 가른다.**

- `CommandSink.isRunning(int marketId)` 를 더하고 `WriterRouter.isRunning(int marketId)` 가 `started && shards.get(marketId).loop().isRunning()` 을 본다. 한 샤드의 정지가 그 마켓의 접수만 닫는다. WriterRouter.java:97-103 의 '마켓별 게이트는 이 판의 범위가 아니다' 주석이 여기서 해소된다. 전체 기준 `isRunning()` 은 수명주기와 헬스 응답이 계속 쓴다.
- `acceptGate` 는 전역으로 둔다. 승격 재생 중 접수 보류는 리더십 단위의 사실이고 리더는 노드 전체로 하나다. 마켓별로 가르면 강등·승격 판정의 단위를 다시 정의해야 하고 그것은 이 판의 범위가 아니다.

### 4-3. 채번과 멱등

- `CommandJournalAppender.appendInTx` 의 `SELECT next_journal_value('command_seq')`(:99-101) 한 줄을 `SELECT next_market_command_seq(?)` 로 바꾼다. 6단계다.
- 채번은 지금도 멱등 판정 뒤에 있다(:92-101). 그 순서가 C05 의 근거이므로 주석이 아니라 구조로 유지한다. 채번과 INSERT 사이에 조건 분기를 넣지 않는다.
- **어드바이저리 락 키를 건드리지 않는다.** `pg_advisory_xact_lock(hashtextextended('cmd-new:' || user_id || ':' || client_order_id, 0))`(:125)의 키에 `market_id` 를 넣지 않는다. 지금은 전역 `submitLock` 이 모든 append 를 직렬화하므로 이 락의 교차 마켓 역할이 잠들어 있다. 락을 가르는 순간 겹이 둘에서 하나로 줄고 이 락만 남는다. 키에 마켓을 넣으면 서로 다른 마켓의 같은 `(user_id, client_order_id)` 동시 재전송 둘이 서로를 못 보고 둘 다 ACK 를 받는다. 그 뒤 정산의 `orders` INSERT 가 `uq_orders_client` 23505 로 죽고 라이터가 fail-fast 한다. `command_journal` 은 seq 파티션이라 `(user_id, client_order_id)` UNIQUE 를 물리로 걸 수 없으므로 대체 방어선이 없다(V1:586-588 주석이 그 사실을 이미 적고 있다).
- 같은 이유로 `findExistingNew` 의 WHERE 에도 `market_id` 를 넣지 않는다(:129-135). 멱등 네임스페이스가 `(user_id, client_order_id)` 이고 마켓이 없다.
- **한 줄이 바뀐다.** `ORDER BY seq LIMIT 1`(:133)은 대역 파생에서 '가장 이른 시각' 이 아니라 '가장 작은 marketId' 를 고른다. 그 행의 `seq` 와 `assigned_order_id` 가 `AppendResult` 로 나가 200 replayed 응답에 실리므로, 사용자가 내지 않은 마켓의 주문이 자기 주문으로 돌아간다. `ORDER BY created_at, seq LIMIT 1` 로 바꾼다. `idempotency_keys` 내용 해시 대조가 앞에서 409 로 끊지만 그 표는 7일 보존이고 저널은 무기한 권위라 만료 이후가 이 경로다.

### 4-4. 잠금 계층과 교착

마켓별로 12개 append 트랜잭션이 동시에 도는 것은 이 배선에서 처음이다. 한 append 가 잡는 잠금은 순서대로 셋이다.

1. `pg_advisory_xact_lock('cmd-new:user:coid')`. 트랜잭션당 정확히 하나.
2. 자기 마켓의 `market_journal_counters` 행. 마켓마다 다르다.
3. `leader_lease` 행 `FOR SHARE`. 공유 모드라 서로 호환.

모든 트랜잭션이 같은 순서로 잡고 (1)이 하나이며 (2)가 마켓마다 다르므로 순환이 생길 조합이 없다. 논증이므로 다중 마켓 동시 append 통합 시험(`MultiMarketConcurrentAppendTest`)으로 고정한다.

### 4-5. 축 이전이 닿는 파일

| 파일 | 무엇을 바꾸나 |
|---|---|
| `engine/core/MatchingEngineImpl` | `lastAppliedSeq` 스칼라를 `Map<Integer,Long>` 으로. `apply` 비교를 마켓별로. `loadSnapshot` 을 `loadMarketSnapshot(marketId, seq, book)` 으로. 무인자 `lastAppliedSeq()` 는 `maxAppliedSeqForReporting()` 으로 개명해 판정 경로에서 뺀다 |
| `engine/core/SingleWriterLoop` | `process`·`catchUpTo` 를 마켓 커서로. `catchUpCalls`·`catchUpNanos` 계측 신설 |
| `engine/WriterRouter` | `isRunning(int)`, `installFrom` 이 마켓별 커서를 심는다 |
| `engine/EngineBridge` | 4-1, 4-2 |
| `engine/EngineRecovery` | 마켓 순회 2단계 재생. 크래시 창 재적재 축(5-3) |
| `engine/PromotionCoordinator` | `replayOnce` 의 head 비교를 벡터 dominates 판정으로 |
| `engine/FollowerCatchUp` | 마켓별 커서·상한·lag. 불변식 M 재서술 |
| `journal/CommandJournalReader` | `maxSeqByMarket()`·`maxSeqForMarket(int)` 신설. `maxSeq()` 는 표시용으로만 남긴다 |
| `journal/EventJournalReader` | `checkpointByMarket()` 신설 |
| `journal/JournalCheckpoint` | `JournalCheckpoints`(마켓 벡터)로 |
| `journal/ReplayService` | `rebuildMarket(engine, marketId, from, bound)` 로 일반화. `PendingBatch` 반환 |
| `journal/ReplayResult` | `pendingBatches()` 추가. `pendingEvents()` 는 평탄화 뷰로 남긴다 |
| `journal/CommandJournalAppender` | 4-3 |
| `snapshot/SnapshotStore` | 세트 개념 폐기. `loadLatestAdoptablePerMarket()` |
| `config/EngineConfig` | 지표 마켓 태그. 스냅샷 훅을 샤드 팩토리로(7단계) |
| `admin/HaMonitoringController` | `command_watermarks` 배열, `apply_lag` 최대와 합 |
| `harness/InvariantCheck` | 6절 |

문서·하네스: `harness/sql/invariants.sql`, `harness/sql/summary.sql`, `harness/scripts/lib.ps1`, `web/lib/admin/httpAdmin.ts`, `harness/grafana/dashboards/02-recovery.json`, `docs/demo/15분_라이브데모_대본.md` 네 자리, `docs/contracts/하네스_검증설계.md` R0·R3·R5, `docs/contracts/백엔드_API_설계.md` §7-1 gapless 문구.

---

## 5. 재생과 복구

### 5-1. 축이 왜 마켓 벡터여야 하는가

논리 사슬이 하나다.

1. 대역 파생이면 전역 `max(source_cmd_seq)` 가 '가장 높은 대역을 쓰는 마켓의 최근 명령' 일 뿐 전역 물질화 경계가 아니다. 곧 체크포인트가 마켓 벡터여야 한다.
2. 체크포인트가 벡터이면 재생 상한도 마켓마다 다르다.
3. 상한이 마켓마다 다르면 `WHERE seq > cursor` 라는 전역 스칼라 커서로 그것을 표현할 수 없다. 곧 재생 커서가 마켓별이어야 한다.
4. 재생 커서가 마켓별이면 엔진 커서도, 스냅샷 세트 키도, 승격·팔로워 판정도 마켓별이어야 한다.

**이 사슬은 대역 파생과 무관하게 지금 당장 옳다.** 마켓 안에 라이터가 하나이고 정산이 명령 단위 원자 커밋이므로 `max(source_cmd_seq) GROUP BY market_id` 가 그 마켓의 진짜 물질화 경계다. 전역 max 가 12 라이터에서 이미 불건전하다는 1-3(2)가 그 증거다. 그래서 2단계의 시험 조건을 '오늘 전역 조밀 축에서도 결과가 같다' 로 둘 수 있다.

### 5-2. 체크포인트 벡터의 정의

```
legacy_bound  = journal_axis_boundary('pre_v36_event').cmd_seq        -- 마이그레이션이 박제한 상수
band_bound(m) = (SELECT source_cmd_seq FROM event_journal
                  WHERE market_id = m AND market_seq IS NOT NULL
                  ORDER BY market_seq DESC LIMIT 1)                    -- idx_evt_market_seq 로 끝난다
bound(m)      = GREATEST(legacy_bound, COALESCE(band_bound(m), 0))
```

- 마켓 안에는 라이터가 하나이므로 `market_seq` 최대 행이 곧 `source_cmd_seq` 최대 행이다. 새 인덱스가 필요 없다.
- `legacy_bound` 는 V36 이전(단일 라이터) 구간의 전역 물질화 경계이고 다시 늘지 않는다. V36 이후 이벤트가 없는 조용한 마켓의 상한이 0 으로 떨어지는 것을 막는다.

### 5-3. 크래시 창 재적재의 축은 seq 가 아니라 (created_at, seq) 다

**이것이 이 판에서 가장 조용한 비가역 결함이고, 심사가 지적한 치명 결함이다.**

`EngineRecovery`(EngineRecovery.java:205-230)는 재생이 낸 pending 을 `source_cmd_seq` 오름차순·연속이라는 전제로 잘라 정산 sink 에 넘긴다. 그 sink 가 `SettlementService.settleAccepted` 를 타고 `SELECT reserve_funds(...)`(SettlementService.java:322-327)를 부른다. 바로 위 주석이 못박는다. "잔고 부족(23514)은 여기서 배치 전체를 롤백시킨다. 클래스 주석의 fail-fast 계약". 그리고 `chk_balance_nonneg CHECK (balance >= 0 OR allow_negative)`(V1:119)가 계정마다 하드 제약이다.

곧 **예약은 접수가 아니라 정산에서 일어난다.** 접수 경로(`EngineBridge.submit` → `CommandJournalAppender.appendInTx`)는 `command_journal` 과 `leader_lease` 만 건드린다.

대역 파생에서 `seq` 오름차순은 시간 순서가 아니라 마켓 id 순서다. 크래시 창 안에서 사용자가 마켓 12 에서 팔아 생긴 KRW 로 마켓 2 에서 샀다면, 재적재가 마켓 2 를 먼저 돌려 잔고 부족 23514 를 낸다. 배치 전체 롤백에 라이터 fail-fast 이고, 명령은 이미 저널에 커밋돼 있으므로 재기동해도 같은 자리에서 같은 순서로 다시 죽는다. **영구 벽돌이다.**

**해결.**

```
재적재 축 = (command_journal.created_at, seq)   -- seq 가 아니다
```

근거를 적는다.

- `created_at` 은 `now()` 기본값이므로 append 트랜잭션의 **시작 시각**이고 단일 프라이머리의 단일 시계다(V1:582).
- 인과 사슬이 보존된다. 명령 X 의 ACK 를 보고 명령 Y 를 낸다면, `created_at(Y) = start(Y) > commit(X) > start(X) = created_at(X)` 다. 곧 사용자가 만든 모든 자금 의존은 이 축에서 순서가 맞다.
- 마켓 안에서는 이 축이 seq 축과 정확히 같다. 마켓별 락이 append 를 통째로 직렬화하므로 같은 마켓의 두 트랜잭션은 겹치지 않는다.
- **오늘은 이 축이 전역 seq 축과 완전히 같다.** 전역 락이 append 를 통째로 직렬화하기 때문이다. 곧 이 변경은 커트오버 전에 '오늘과 같은 결과' 로 검증할 수 있다. 2단계에 넣는 이유가 그것이다.

**구현.** `ReplayResult` 에 `pendingBatches()` 를 더한다. `PendingBatch(long seq, OffsetDateTime createdAt, List<EngineEvent> events)` 다. `EngineRecovery` 가 마켓별 2단계 재생이 낸 배치들을 모아 `(createdAt, seq)` 로 안정 정렬한 뒤 배치마다 `reappendSink.accept` 한다. 지금의 "연속 같은 `sourceCmdSeq` 를 훑어 자른다" 루프가 사라진다. 크래시 창은 이미 메모리에 전량 올라와 있으므로(`List<EngineEvent> pending`) 정렬에 추가 비용이 없고 새 인덱스도 필요 없다.

**정직하게 남는 것.** 이 축이 복원하는 것은 '오늘의 보장' 이지 '원래 실행 순서' 가 아니다. 원래의 마켓 사이 정산 순서는 12개 라이터 스레드의 실제 인터리빙이고 어디에도 기록돼 있지 않다. 같은 사용자가 잔고를 초과해 **동시에** 낸 명령 둘이 같은 크래시 창에 들어가면 오늘도 이후도 벽돌이다. 그것은 게이트웨이 사전 예약 검증(M1-4)의 몫이고 이 판이 닫지 않는다. 9절에 등재한다.

**회귀 시험.** `CrashWindowReinjectionOrderTest`. (a) 오늘 축에서 정렬이 무연산임을 단언한다. (b) 마켓 12 의 매도 체결과 마켓 2 의 매수가 같은 크래시 창에 있고 잔고가 딱 맞는 저널을 합성해, seq 축 재적재는 23514 로 죽고 `(created_at, seq)` 축은 통과함을 단언한다.

### 5-4. 나머지 복구 경로

- **1단계(안정 구간).** 마켓마다 `bound(m)` 까지 대조만 한다. 리더 자격이 필요 없다. 여기서 pending 이 나오면 원자 커밋 전제가 깨진 것이므로 종전대로 예외다.
- **2단계(크래시 창).** 마켓마다 `head(m) = max(seq) FROM command_journal WHERE market_id = m` 으로 상한한다. 상한을 잡아 두면 루프가 유한하게 끝나고, 리스를 잃었다면 이어지는 재적재가 fencing 으로 거부된다는 성질이 그대로다.
- **리스 예산.** 마켓 수만큼 늘어난 재생 구간이 TTL 안에 들어오는지 다시 따진다. `RENEW_EVENT_INTERVAL`(10,000) 판정을 배치 경계마다 도는 것은 그대로 두되, 마켓 순회가 아니라 정렬된 배치 순회이므로 renew 지점이 자연히 시간 순으로 흩어진다.
- **갭 회수.** `JournalCatchUpSource` 는 이미 마켓 조건을 갖고 있다(JournalCatchUpSource.java:44-46). 커서만 마켓별로 바뀐다.
- **스냅샷.** 세트 개념을 버린다. `loadLatestAdoptablePerMarket()` 이 마켓마다 독립으로 최신 채택 가능본을 고르고, 한 마켓이 폐기되면 그 마켓만 0 으로 폴백한다. `MatchingEngineImpl.loadMarketSnapshot` 의 fresh 가드도 마켓 단위다.
- **승격 생략.** `PromotionCoordinator.replayOnce`(PromotionCoordinator.java:307-316)의 `lastAppliedSeq >= head` 를 '모든 마켓에서 커서가 그 마켓 head 에 닿았다' 로 바꾼다. 한 마켓만 닿은 상태에서 생략이 걸리면 재생이 안 된 마켓 위에서 접수가 열린다.
- **팔로워.** 불변식 M 을 '모든 마켓에서 커서가 그 마켓 체크포인트를 넘지 않는다' 로 다시 쓴다. lag 는 마켓별 격차의 최대와 합 둘을 낸다. 합만 내면 한 마켓의 정체가 열한 마켓의 진행에 묻힌다. `Math.max(0, ...)` 클램프는 마켓 안에서는 발화할 수 없는 값이므로 발화하면 ERROR 로그를 남긴다.

---

## 6. 불변식

축어 술어가 세 파일에 복제돼 있다. `server/app/src/main/java/kr/exchange/harness/InvariantCheck.java`, `harness/sql/invariants.sql`, `harness/sql/summary.sql`. **셋을 같은 커밋에 고친다.** 한 벌만 고치면 앱 상시 run 과 psql 권위 run 이 갈리고, 어느 쪽이 정본인지 문서가 말하지 않는다.

### 6-1. C05_CMD_GAPLESS (CRITICAL, SCHEDULED). 두 항 합으로

지금: `COALESCE((max(seq) - min(seq) + 1) - count(*), 0) FROM command_journal`(InvariantCheck.java:38).

이후:

```
레거시 항  seq < band_span            전역 조밀. 오늘 술어와 글자 그대로 같다
대역 항    seq >= band_span           마켓별로 (max-min+1)-count 를 세어 합친다
band_span  (SELECT band_span FROM journal_band_spec WHERE name='command_seq')
```

- 경계 상수를 어디에도 적지 않는다. 술어 안의 `band_span` 조회가 단일 원천이다.
- **커트오버 전에 오늘과 같은 값을 낸다.** 대역 항의 대상이 0행이고 레거시 항이 표 전체다. 같은 값이 안 나오면 술어가 틀린 것이고 3단계에서 멈춘다.
- 뜻이 바뀐다. '전역 결번 없음' 이 '레거시 구간 전역 결번 없음 + 마켓 안 결번 없음' 이 된다. RPO=0 주장은 약해지지 않는다. 마켓 안에서 결번이 없으면 전체에서 유실이 없고, RPO=0 의 외부 증거는 애초에 C16(ACK 원장 ⊆ `command_journal`)이 대며 그 축은 `(user_id, client_order_id)` 라 이 변경과 무관하다.
- V36 이 이벤트 축에서 구판 행을 검사 밖으로 뺀 것과 다르다. 명령 축은 레거시 구간을 **포함**한다. 컬럼을 더하지 않았기 때문이다.

`lib.ps1:369` 의 `$rpo = $cmdGap + $evtGap + $ordersLost` 는 무변경이다. C05 위반 행수가 계속 0 이면 되기 때문이다.

### 6-2. C22_FENCING_MONOTONIC (CRITICAL, AUTHORITATIVE_ONLY). 시간 축으로

지금: `lag(fencing_token) OVER (ORDER BY seq)`(InvariantCheck.java:249).

이후: `lag(fencing_token) OVER (ORDER BY created_at, seq)`.

- `PARTITION BY market_id` 로 도망가지 않는다. 그러면 검사가 보증하는 것이 '마켓 안의 epoch 단조' 로 줄고, 마켓 사이 split-brain 을 이 백스톱이 못 본다.
- 시간 축이 맞다는 논증. 커밋된 구 epoch 행은 `INSERT ... SELECT FROM leader_lease WHERE epoch = ?`(CommandJournalAppender.java:28-32) 를 통과한 행이다. 리스가 새 epoch 로 넘어간 뒤에는 그 SELECT 가 0행이 되어 `StaleLeaderException` 으로 롤백된다. 곧 커밋된 구 epoch 행은 전부 리스 전환 시각 이전에 INSERT 를 실행했고, 새 epoch 행은 전부 그 이후에 트랜잭션을 시작했다. `created_at` 이 두 집합을 엄격히 가른다.
- 비용. `created_at` 에 인덱스가 없으므로 정렬이 든다. 권위 run 한정이고 정지 상태에서 도는 검사다. 3단계에서 실측 시간을 기록한다. 인덱스를 더하지 않는 이유는 그것이 append 핫패스의 유지 비용이기 때문이다.
- **커트오버 전에 오늘과 같은 값(0)을 낸다.** 오늘은 `created_at` 순서가 `seq` 순서와 같다.

### 6-3. C23_COUNTER_SEQ_CONSISTENCY (CRITICAL, AUTHORITATIVE_ONLY). 명령 축을 마켓별로

지금 명령 축은 전역 `journal_counters('command_seq').next_value` 대 전역 `max(seq)` 다(InvariantCheck.java:266-271).

이후 세 팔이다.

1. 레거시 팔. 전역 `command_seq` 행의 `next_value` 대 `max(seq) WHERE seq < band_span`. 커트오버 뒤 이 행은 호출자가 없어지지만 값이 얼어붙어 계속 성립한다. V36 이 `event_seq` 전역 행에 한 판단과 같다. 행을 지우지 않는다(복원 리시드의 기준행이다).
2. 대역 팔. `market_journal_counters(name='command_seq').next_value` 대 그 마켓의 `max(seq) - market_id * band_span`.
3. 이벤트 팔. 무변경.

### 6-4. 신설 둘

- **C26_CMD_BAND_HEADROOM (HIGH, SCHEDULED).** `v_journal_band_headroom` 에서 잔여 비율 10퍼센트 미만인 마켓 수. **정직하게 적는다. 10^12 대역에서 이 검사는 사실상 공허하다**(단일 마켓 집중에서도 240년). 남기는 이유는 둘이다. 비용이 0 이고, 소진이 비가역이다(PK 가 seq 재사용을 막고 append-only 트리거가 백필을 막으므로 운영이 카운터를 되돌릴 수 없다). 실제 방어선은 이 검사가 아니라 `next_market_command_seq` 의 `max_band_index` 예외와 `markets` INSERT 트리거의 거부다.
- **C27_CMD_DEFAULT_PARTITION (CRITICAL, SCHEDULED).** `SELECT count(*) FROM command_journal_default`. C15 를 통째로 올리지 않는 이유는 그것이 WARN 이고 다섯 표를 합산하기 때문이다(InvariantCheck.java:200-206). 통째로 올리면 다른 네 표의 정상 운영까지 CRITICAL 이 된다. 명령 축만 떼어 CRITICAL 로 세운다. C15 는 나머지 넷으로 남는다.

### 6-5. 하네스 판정식. 스테일 쓰기 fail-open 을 양쪽 축에서 닫는다

`Get-StaleWriteCount`(lib.ps1:533-543)가 지금 이렇다.

```
command_journal WHERE seq > $CmdWatermark        AND fencing_token = $OldEpoch
event_journal   WHERE event_seq > $EvtWatermark  AND fencing_epoch = $OldEpoch
```

- **이벤트 축은 V36 이후 이미 fail-open 이다.** `event_seq` 가 대역 파생이라 워터마크가 높은 대역에서 잡히면 낮은 대역 마켓의 구 epoch 쓰기가 전부 `event_seq > W` 밖으로 빠져 0 으로 세어진다. 이중 쓰기가 실재하는데 E5·E7 이 PASS 를 낸다. 이 판과 무관하게 지금 열려 있는 결함이고, 그 사이 회차의 split-brain 반증은 소급 무효일 수 있다.
- 명령 축은 대역 파생이 되면 같은 모양으로 열린다.

**둘 다 마켓별 워터마크 벡터로 바꾼다.** 주입 직전 마켓별 `max(seq)` 와 마켓별 `max(market_seq)` 를 잡고, 마켓마다 그 초과분 중 구 epoch 행을 센다. 주입 시점에 없던 마켓은 `LEFT JOIN + COALESCE 0` 으로 그 마켓 전량을 센다(fail-closed). `Get-CommandWatermark`·`Get-EventWatermark` 도 스칼라에서 벡터로 간다. `-Required` 규율은 그대로다.

### 6-6. 관측·문서

- `HaMonitoringController.status()`: `command_watermark` 스칼라를 `command_watermarks` 배열로. `apply_lag` 는 마켓별 (명령 head - 물질화 경계)의 **최대**를 내고 `apply_lag_total` 을 합으로 따로 낸다. 합만 내면 한 마켓의 정체가 묻힌다.
- 좀비 리더 판별식(`harness/evidence/gateoff-20260903-2vcpu/README.md:228,238`): "`lease_until` 은 전진하는데 **어느 마켓의** head 도 전진하지 않으면 좀비 리더, **일부 마켓만** 전진하지 않으면 부분 정체 경보". 지금은 부분 정체가 아예 안 보이므로 이것은 상실이 아니라 이득이다.
- Grafana `02-recovery.json` 의 `replay_tail_commands`: 마켓별 (명령 head - 스냅샷 커서)의 합.
- `web/lib/admin/httpAdmin.ts:1008-1015` 의 노드 `seq` 표시: 마켓별 최대의 최대로. 표시용이라 정확성 사고는 아니다.
- `docs/contracts/백엔드_API_설계.md` 의 "`seq` 는 gapless" 문구와 "§6-C `last_applied_seq` 와 동일 축" 문구 둘 다 거짓이 된다. 값은 그대로 쓸 수 있다(프론트가 소비하지 않고 커서 축이 `(created_at, id)`·`trade_id` 다). 문서를 고친다.
- 데모 대본 네 자리의 C05 SQL 과 "명령 저널 gap 도 0행" 대사.
- `하네스_검증설계.md` R0(워터마크 선캡처), R3('전역 seq 단일 패스' 문면), R5 분류 C.

---

## 7. 단계 계획

되돌릴 수 없는 것을 검증 뒤로 미룬다. 중간에 멈춰도 시스템이 살아 있는 것이 이 순서의 요점이다. ADR 0021 의 규율을 그대로 쓴다.

### 0단계. 계측과 착수 게이트 (되돌릴 수 있다. 계측만 더한다)

재기 전에는 아무것도 청구하지 않는다. 넷을 잰다.

- **(a) 동시 커밋 확장성.** 세션 N 개가 각자 작은 트랜잭션을 커밋하는 사다리를 N=1,2,4,8,12,24 로 돌려 초당 커밋 수와 p95 커밋 지연을 낸다. `pg_test_fsync` 의 311.9회/s 는 **겹치지 않는 직렬 플러시**의 값이고 곧 지연 3.206밀리초의 다른 표현이다. 동시 커밋이 하나의 플러시를 공유하는지(그룹 커밋) 아닌지가 이 판의 처리량 이득 전부를 가른다. 8-2 참조.
- **(b) 채번 행 보유 구간.** append 트랜잭션의 '채번부터 커밋까지' 나노초. 오늘 이 행의 **대기**는 항상 0 에 가깝다(전역 락이 앞에서 직렬화하고 실측이 '행 잠금 3.8퍼센트' 로 이미 소거했다). 잴 수 있는 것은 보유 구간이고, 그 역수가 곧 '락만 가르고 채번을 그대로 두었을 때의 마켓당 상한' 이다.
- **(c) 갭 회수 왕복.** `SingleWriterLoop` 에 `catchUpCalls`·`catchUpNanos` 를 `market` 태그로 더한다. 예상은 명령당 1 - 1/12 = 91.7퍼센트이고 **계산값이지 실측이 아니다.** 이 수치가 없으면 fast path 복원 이득을 청구할 수 없다. 부수로 '라이터 sink 점유 2.146' 이 얼마나 과소 보고였는지가 나온다.
- **(d) 제공 부하 준비.** 지금 `calibrate.js` 150/s 에 접수 128 + 수용 거절 40 이라 요청 쪽이 이미 포화다. 새 천장을 재려면 제공 부하가 600/s 는 돼야 하고 시드 계정과 k6 VU 를 함께 늘려야 한다. 올리지 않고 재면 '개선했는데 수치가 그대로' 가 나온다.

**착수 게이트.** (a)에서 N=12 의 초당 커밋이 N=1 의 3배 미만이면 저장소가 직렬 장치다. 그 경우 이 판의 처리량 상한은 156/s(1.18배)이고, 비가역 단계(6단계)를 그 이득으로 살 것인지를 **명시적으로 고른다.** 처리량 외의 산출물(파티션 시한 제거, 체크포인트 정합, `installFrom` 무음 폐기 제거, 스테일 쓰기 fail-closed, 마켓별 가용성)은 5단계까지에서 전부 나오고 전부 가역이다.

**멈추면.** 오늘 그대로다. 되돌리기는 배포 롤백이다.

### 1단계. V39 (되돌릴 수 있다. 채번 경로를 바꾸지 않는다)

3-1 전부. **처리량이 오르기 전에 넣는다.**

**멈추면.** 오늘 그대로 살아 있고 **17.4일짜리 파티션 시한 하나가 제거된 상태다.** 이 판을 접어도 이 단계는 남긴다.

되돌리기: 파티션 DROP(비어 있음), 함수 둘 복귀, 시드 행 DELETE, 표 둘 DROP.

### 2단계. 소비측·재생·복구의 축 이전 (되돌릴 수 있다. 표면이 가장 넓다)

4-5 와 5절 전부. 채번도 락도 아직 전역이다.

**시험 조건: 전역 조밀 축에서 결과가 오늘과 같다.**

- 마켓별 커서는 전역 seq 에서도 그 마켓 명령에 대해 단조 증가하므로 중복 판정이 바뀌지 않는다.
- 갭 판정 빈도도 바뀌지 않는다(여전히 성기다).
- 크래시 창 정렬은 오늘 무연산이다(5-3).
- 마켓별 체크포인트는 전역 max 보다 **낮거나 같으므로** 상한이 좁아진다. 1-3(2)의 간헐 실패가 여기서 사라지는 것이 관측 가능한 유일한 차이다.

`SnapshotEquivalenceIntegrationTest` 를 확장해 4단계 shadow 가 닫지 못한 운영 축 다섯을 덮는다. 상한 있는 마켓별 재생, 스냅샷 시작점에서 이어붙이는 tail 재생, 마켓 둘 이상이 동시에 크래시 창을 가진 재적재, 라이브 라이터가 도는 중의 팔로워 따라잡기, 승격 생략 판정(마켓 하나만 head 에 닿은 상태에서 생략이 걸리지 않아야 한다). `installFrom` 의 무음 폐기 회귀를 여기서 반드시 단언한다. 지금 그것을 잡는 상시 감사가 없다.

**멈추면.** 오늘 그대로이고 1-3 의 구멍 셋이 닫힌 상태다.

### 3단계. 감사·관측 축 이전 (되돌릴 수 있다)

6절 전부. 세 파일 + 하네스 + 런북 + 대본.

**시험 조건: 커트오버 전에 오늘과 똑같은 값을 낸다.** C05 대역 항 0행, C22 시간 축 0건, C23 대역 팔 대상 없음, C26 대상 없음, C27 0건. 같은 값이 안 나오면 술어가 틀린 것이고 여기서 멈춘다. 이 순서를 뒤집으면 첫 페일오버 실험에서 R5 분류 C 가 정상 시스템의 서비스 재개를 자동으로 금지한다.

부수로 **6-5 의 이벤트 축 fail-open 이 여기서 닫힌다.** 그것은 이 판의 채택 여부와 무관한 단독 산출물이다.

**멈추면.** 오늘 그대로이고 split-brain 반증이 양쪽 축에서 fail-closed 가 된 상태다.

### 4단계. 접수 락 분할 (되돌릴 수 있다)

4-1 부터 4-4 까지. `findExistingNew` 의 `ORDER BY created_at, seq` 포함.

**이 단계에서 처리량 이득은 없다.** 채번이 아직 전역이라 12개 트랜잭션이 `journal_counters` 행 잠금에서 다시 직렬화된다. V36 격리 실측으로 12 라이터 전역 카운터가 43.5/s 였다. ADR 0021 3단계와 같은 성질이고 같은 문장으로 적는다. 목적은 이득이 아니라 이득의 전제를 까는 것이고, 커트오버 단계를 최대한 작게 만드는 것이다.

**그 평평함 자체가 증거다.** 여기서 접수가 크게 안 오르는 것이 "락 분할은 채번 분할의 종속 결과" 라는 명제의 실측 증거이고, 5단계와 합치면 그 증거를 잃는다.

**멈추면.** 오늘과 같은 처리량에 마켓별 접수 게이트가 붙은 상태다.

### 5단계. 장애 시험 (되돌릴 수 있다)

E2·E4·E5·E6·E7 을 돌린다. 승격·팔로워·스냅샷 회귀는 장애 때만 드러난다. `CrashWindowReinjectionOrderTest` 와 `MultiMarketConcurrentAppendTest` 가 여기 앞에 있어야 한다.

### 6단계. 커트오버 (**되돌릴 수 없다**)

`CommandJournalAppender.appendInTx` 의 한 줄을 `SELECT next_market_command_seq(?)` 로 바꾼다. 첫 대역 행이 커밋되는 순간이 돌아올 수 없는 지점이다. 그 뒤에 '역시 전역으로 돌아가자' 를 하려면 이미 쓴 대역 값보다 큰 값에서 전역 채번을 이어야 하는데, 그러면 전역 축이 다시 결번투성이라 C05 레거시 항이 영영 성립하지 않는다.

배포 전 확인 다섯.

1. `command_journal_b1`..`b63` 과 꼬리 파티션이 실재하는가.
2. 3단계 술어가 오늘 값과 같은가.
3. 5단계가 통과했는가.
4. `market_journal_counters` 에 12마켓의 `command_seq` 행이 다 있는가.
5. 0단계 (a)의 판정과 착수 결정이 기록돼 있는가.

되돌리기: 없다.

### 7단계. V40 + 스냅샷 훅 배선 (되돌릴 수 있다)

- 대역 여유 뷰·게이지·C26 알람 라우팅.
- `EngineConfig.snapshotCapture` 가 레거시 `singleWriterLoop` 빈에만 훅을 거는 것(EngineConfig.java:211-223)을 샤드 팩토리(EngineConfig.java:245-288)로 옮긴다. `setSnapshotHook` 호출부가 코드 전체에 그 한 줄뿐이고 라우터 샤드에는 없다. 곧 **지금 스냅샷이 한 건도 안 찍히고 복구가 항상 seq 0 전량 재생이다.** 샤드마다 `SnapshotCapture` 를 하나 두면 `seq - lastCapturedSeq >= commandInterval` 판정이 자연히 마켓 단위가 된다.
- **커트오버 뒤로 미루는 이유.** 배선하는 순간 처음 도는 코드다. 새 코드와 새 축을 같은 창에 겹치지 않게 한다. 실패해도 오늘의 동작(전량 재생)으로 떨어지는 폴백을 남긴다.

### 8단계. commit_delay 실측 (선택. 되돌릴 수 있다)

`wal-fsync-20260907` 이 "재지 않았다" 로 남긴 자리다. 그 문서의 유보 사유가 "커밋이 단일 라이터에서 직렬화되어 동시 커밋자가 적다" 였고, **6단계가 그 전제를 바꾼다.** `synchronous_commit=off` 와 다르다. 동기 커밋을 유지한 채 플러셔가 형제 트랜잭션을 모으도록 기다리게 하는 것이라 'fsync 후 ACK' 계약을 깨지 않는다. `commit_siblings` 와 함께 격자로 돈다. `ALTER SYSTEM RESET` 으로 되돌린다.

### 9단계. V41 이벤트 축 대역 확대 (선택. 별건)

3-3. 이 판의 필수가 아니다.

---

## 8. 예상 이득과 그 근거

### 8-1. 지금의 상한

| 항목 | 실측 | 산술 |
|---|---|---|
| 락 보유 1회 | 6.935밀리초 (호출 35,647) | 락 하나의 상한 1/0.006935 = **144.2/s** |
| 락 보유 점유 | 0.993 | 전역 락 하나의 물리 상한 1.0 |
| 관측 접수 | 128.11/s (정원 48 칸), 132.12/s (락 표본 칸 중앙값) | 144.2 의 89~92퍼센트 |

### 8-2. 저장소 축을 상한 사다리에 넣는다

앞선 심사 둘이 같은 지적을 했고 옳다. `harness/evidence/wal-fsync-20260907/README.md` 가 이 호스트의 `fdatasync` 를 초당 311.9회(건당 3.206밀리초)로 쟀고, 명령 하나가 내구 커밋을 두 번 요구한다(저널 append 한 번, 정산 한 번). 오늘 132.12/s 는 264.2 플러시/s 이고 311.9 의 84.7퍼센트다. 이 값을 그대로 상한으로 읽으면 **명령 상한이 156/s** 이고 250/s 같은 예고는 성립하지 않는다.

그러나 그 산술에는 전제가 하나 숨어 있다. **311.9회/s 는 겹치지 않는 직렬 플러시의 값이고 곧 지연 3.206밀리초의 다른 표현이다.** `pg_test_fsync` 는 한 스레드가 쓰고 플러시하기를 반복한다. PostgreSQL 의 그룹 커밋은 한 번의 `fdatasync` 로 동시에 커밋 대기 중인 트랜잭션 여럿을 함께 내구화하므로, 동시 커밋자가 있으면 '초당 커밋' 이 '초당 플러시' 를 넘을 수 있다.

지금 이 배선이 그 조건을 어떻게 막고 있는지 적는다.

| 축 | 동시 커밋자 | 평균 동시성 (132/s x 3.206밀리초) |
|---|---|---|
| 저널 append | **구조적으로 1**. 전역 `submitLock` 이 append 를 통째로 직렬화한다 | 0.42 (그러나 상한이 1) |
| 정산 | 최대 12. 라이터가 열둘이다 | 0.42 |

곧 **저널 축은 그룹 커밋이 원리적으로 불가능한 상태이고, 접수 락을 가르는 것이 정확히 그 조건을 여는 변경이다.** 정산 축은 겹칠 수 있으나 접수가 132/s 로 묶여 있어 겹칠 일이 별로 없다. 저장소가 동시 플러시를 흡수하는지 아닌지를 오늘 데이터로는 가를 수 없다. 그래서 0단계 (a)가 착수 게이트다.

### 8-3. 락을 가른 뒤의 상한 사다리

접수 132.12/s 를 분모로 1건당 자원 소비를 내고 각 자원의 상한으로 나눈다.

| 자원 | 1건당 | 상한 | 그 축만의 천장 |
|---|---|---|---|
| **저장소(분기 A: 직렬)** | 커밋 2회 | 311.9 플러시/s | **156/s** |
| DB CPU | 1.5코어 / 132.12 = 0.01135 코어초 | 4코어 | **352/s** |
| 라이터 직렬 구간 | 2.146 / 132.12 = 16.24밀리초 | 12.0 | 739/s (과소 보고. 8-5) |
| 마켓별 카운터 행 | 보유 구간 (0단계 (b)) | 마켓 12 | 0단계 (b)가 낸다 |
| 접수 락(분할 후) | 6.935밀리초 | 12 | 1,730/s |
| **저장소(분기 B: 동시)** | 커밋 2회, 동시 최대 24 | 0단계 (a)가 낸다 | 352 위 |

### 8-4. 예고를 두 분기로 낸다

**분기 A. 0단계 (a)에서 동시 커밋이 확장되지 않을 때.**
- 상한 156/s. 접수 128 에서 **약 150/s (1.18배)**.
- 이 경우 병목은 락에서 저장소로 옮겨 가고, 그 저장소는 WSL2 VHDX 라 이 PC 에서 개선할 수 없다(RAM 대비 1,600배). 다음 판은 코드가 아니라 배포 대상 저장소의 규격이다.

**분기 B. 동시 커밋이 확장될 때.**
- 다음 벽은 DB CPU 352/s 다.
- 접수 128 에서 **180~260/s, 중앙 210/s (약 1.6배)**.
- 하한 근거는 ADR 0021 이 라이터 분할에서 실제로 얻은 배수 1.41(=181/s)이다. 상한 근거는 DB CPU 산식 352/s 이고, 접수가 N 배가 되면 정산도 N 배라 DB 코어도 대략 N 배이므로 N=2 에서 3.0/4 코어(75퍼센트)가 된다. 데이터베이스는 75퍼센트 근처에서 지연이 꺾이므로 그 앞에서 멈춘다고 본다. 100퍼센트 가정인 352/s 는 도달하지 않는다.

**12배로 내지 않는 이유를 명시한다.** ADR 0021 이 격리 실측으로 12 라이터에 514.5/s 를 예고하고 실제는 101.6/s 였다(예고의 19.7퍼센트). 사유는 격리 시험이 채번 경합만 떼어 재고 나머지 비용을 흉내로 고정했기 때문이다. 같은 종류의 낙관이 1,730/s 에도 있다. 그것은 '락 축만 본' 수이고 다른 자원이 그 아래에서 먼저 찬다.

### 8-5. 함께 예고하는 것

| 항목 | 지금 | 예고 | 근거 |
|---|---|---|---|
| 락 대기 점유 | 42.997 (정원 48) | 마켓당 3.6 근처 | 리틀의 법칙. 같은 부하가 12 락에 나뉜다 |
| 평균 접수 대기 | 42.997 / 132.12 = 325밀리초 | 27밀리초 근처 | 위와 같은 축 |
| p95 | 471.0밀리초 | 100밀리초 아래 | 지연의 지배항이 락 대기였다 |
| 갭 회수 왕복 | 적용 118.73/s x 91.7퍼센트 ≈ 108.9회/s | 0 | 대역 안에서 `seq == last + 1` |
| 라이터 직렬 점유 | 2.146 / 12.0 | 접수 배수만큼 | 과소 보고가 함께 사라진다 |

마지막 두 줄의 91.7퍼센트는 '같은 마켓 명령이 전역 seq 에서 연속일 확률 1/12' 의 여집합이고 **계산값이지 실측이 아니다.** 그래서 0단계 (c)가 이 왕복을 먼저 센다. 실측 없이 이 이득을 청구하지 않는다. 부수로 그 실측은 '라이터 sink 점유 2.146' 이 얼마나 과소 보고였는지도 말해 준다. `catchUpTo` 의 DB 왕복은 라이터 직렬 구간 안에서 도는데 `applyNanos`·`sinkNanos`·`hookNanos` 어디에도 안 잡힌다.

### 8-6. 처리량이 아닌 산출물

이것들은 분기 A 에서도 전부 남고 5단계까지에서 전부 나온다.

- `command_journal` 파티션 17.4일 시한 제거(1단계).
- 마켓별 체크포인트로 기동·재합류 간헐 실패 제거(2단계).
- `installFrom` 무음 폐기 경로 제거(2단계).
- 스테일 쓰기 판정을 양쪽 축에서 fail-closed 로(3단계). **이벤트 축은 V36 이후 이미 열려 있던 구멍이다.**
- 한 샤드 정지가 전 마켓 접수를 닫지 않는다(4단계).
- 부분 정체(마켓 일부만 멈춤)가 좀비 판별식에 처음 보인다(3단계).
- 스냅샷 부활로 RTO 가 전량 재생에서 tail 재생으로(7단계).

---

## 9. 위험과 미해결

정직하게 적는다.

1. **저장소 동시 커밋 확장성이 미측정이다.** 이 판의 처리량 이득 전부가 여기에 걸려 있고, 오늘 데이터로는 가를 수 없다. 0단계 (a)가 착수 게이트인 이유다. 8-2 의 논거는 '그룹 커밋의 조건을 연다' 까지이고 '저장소가 그것을 흡수한다' 를 증명하지 않는다.
2. **크래시 창 재적재의 `(created_at, seq)` 축은 오늘의 보장을 복원할 뿐이다.** 같은 사용자가 잔고를 초과해 동시에 낸 명령 둘이 같은 크래시 창에 들어가면 오늘도 이후도 23514 로 벽돌이다. 원래의 마켓 사이 정산 순서는 12 라이터의 실제 인터리빙이고 어디에도 기록돼 있지 않으므로 어떤 축으로도 재현할 수 없다. 이 구멍은 게이트웨이 사전 예약 검증(M1-4)의 몫이고 이 판이 닫지 않는다.
3. **`LoopFatalHandler` 가 여전히 한 샤드의 fatal 에서 노드 전체를 강등시킨다.** 곧 마켓별 게이트가 실제로 사는 경우는 '샤드가 멈췄는데 강등은 아직 안 온 창' 과 '접수 단계 예외(대역 초과 포함)' 뿐이다. 라이터 fatal 의 폭발 반경을 마켓으로 가두는 것은 별건이다.
4. **승격·강등의 단위는 노드로 남는다.** `acceptGate` 를 가르지 않았다.
5. **스냅샷은 지금 한 건도 안 찍힌다.** 7단계가 처음 도는 코드이고, 채택 게이트가 실동작하는 것도 처음이다. 시험 부담이 가장 큰 단계이며 폴백(전량 재생)을 남긴다.
6. **6단계는 비가역이다.** 되돌리면 전역 축의 C05 원형이 영영 성립하지 않는다.
7. **C26 은 10^12 대역에서 사실상 공허하다.** 6-4 에 이유와 실제 방어선을 적었다.
8. **파티션 67개의 전역 정렬 비용을 안 쟀다.** 점 조회와 마켓별 읽기는 프루닝되지만 C22 의 `ORDER BY created_at, seq` 는 전량 정렬이다. 3단계에서 실측 시간을 기록한다.
9. **`journal_band_spec` 조회가 정산 핫패스에 들어간다.** `next_market_journal_value` 가 일반화되면서 호출마다 2행짜리 표의 PK 조회가 붙는다. 1단계 배포 전후로 `exchange.writer.sink.nanos` 를 대조한다.
10. **제공 부하 600/s 를 낼 시드·VU 가 아직 없다.** 이것은 위험이 아니라 절차이고 0단계 (d)에서 준비한다. 올리지 않고 재면 '개선했는데 수치가 그대로' 가 나온다.
11. **6-5 의 이벤트 축 fail-open 은 V36 이후 이미 존재했다.** 그 사이 회차의 E5·E7 split-brain 반증 PASS 는 소급 무효일 수 있다. 정본 한계로 등재한다.
12. **`legacy_bound` 박제는 'V36 이전 구간이 단일 라이터였다' 는 전제 위에 있다.** 1단계 배포 시 일회 질의로 확인하고, 0 이 아니면 거기서 멈춘다.
13. **`findExistingNew` 의 교차 마켓 동일 coid 는 여전히 200 replayed 로 돌아간다.** `ORDER BY created_at, seq` 는 '가장 이른 것' 을 옳게 고르지만, 다른 마켓의 그 주문을 409 로 끊지는 않는다. 행동 변경이라 이 판의 범위 밖으로 둔다. `idempotency_keys` 7일 보존 뒤가 그 경로다.

2026-09-11 재심에서 셋을 더 찾았다. 셋 다 이 판의 채택 여부와 무관하게 실재한다.

14. **레거시 이벤트 구간이 마켓별 재생에서 통째로 빠진다. 커트오버가 아니라 축 이전 배포
    당일에 터진다.** `EventJournalReader.readAfterForMarket` 이 `WHERE market_id = ?` 로
    읽는데 V36 이전 `event_journal` 행은 `market_id` 가 NULL 이고 `trg_ej_noupd`(V1:619)가
    백필을 물리로 막는다. 현재 이벤트 저널이 전량 그 구간이다. 곧 마켓별 재생의 1단계
    대조에서 전량이 pending 으로 떨어져 `EngineRecovery.verifiedTo` 가 기동을 세운다.
    스냅샷이 0건이라(9-5) 복구가 항상 전량 재생이므로 첫 기동에서 만난다. 지금 그 경로를
    부르는 곳이 `SnapshotEquivalenceIntegrationTest` 뿐이라 아직 안 터졌을 뿐이다.
    해는 레거시 구간을 `command_journal` 조인으로 유도하는 두 패스 구조이고, 2단계 시험
    조건에 명시한다.
15. **`findExistingNew` 가 파티션 잎 전부를 훑는다.** 술어가
    `(command_type, user_id, client_order_id)` 뿐이라 파티션 프루닝이 계획시에도 실행시에도
    0 이다. 그 조회가 어드바이저리 락 안, append 트랜잭션 안, 곧 상한 그 자체인 6.879밀리초
    안이다. 3-4 의 "추가 비용이 없다" 는 정렬만 따지고 Append 자식 수를 안 따진 문장이다.
    배치 순서가 더 나쁘다. 1단계가 잎을 67개로 만드는데 락 분할은 뒤 단계라, 그 사이 내내
    전역 락이 구속인 상태에서 이 비용만 먼저 문다. 완화는 b1..b63 선생성을 마켓 생성 시
    잎 생성으로 바꾸는 것이고 그러면 잎이 열다섯이 된다. 대신 3-1(6)의 구조적 차단 논거가
    약해지므로 그 거래를 0단계 (e) 실측 뒤에 고른다.
16. **마켓별 축이 새 실패 모드를 만든다. 지금 그것을 잡는 검사가 없다.** 오늘은 저널이
    전역 축 하나라 백업 복원이 한 점으로 잘린다. 마켓별 축이면 열두 마켓이 저마다 다른
    지점으로 잘린 상태가 물리적으로 표현 가능하고, 마켓 안에서는 여전히 조밀하므로
    **C05 를 통과한다.** 독립 로그안도 같으므로 어느 안 고유의 비용이 아니다. 새 검사가
    필요하고 축은 `created_at` 이다.

---

## 10. 기각한 대안

### 10-1. 접수 그룹 커밋. 장부를 쪼개지 않고 내구 커밋 예산을 줄인다

기각. 사유 셋.

- 지시가 "장부 자체를 쪼개는 설계" 다. 이 안은 장부를 쪼개지 않는다.
- 헤드라인이 자기 모형과 모순이다. 저자 스스로 배치 크기를 `B = λ x T(B)` 의 Nagle 관계로 정의했는데, 주장 착지 293.6/s 에서 그 식이 주는 B 는 2.21 이다. 명령당 플러시가 1.0625 가 아니라 1.452 이고 요구 플러시 426/s 가 저자가 든 예산 311.9/s 를 초과한다. B=16 은 λ≈2,100/s 가 선행돼야 도달한다. 자기 일관해는 약 172/s 다.
- 그리고 `wal-fsync-20260907` 이 이미 같은 방안을 재고 기각했다. "저널 적재 배치로 얻을 수 있는 최대가 요청 시간의 8퍼센트다."

**살려서 이 판에 넣은 것.** (a) 저장소 내구 커밋 예산을 상한 사다리에 넣는다(8-2). (b) V39 파티션 선행 확장과 `command_journal_default` 유입을 CRITICAL 로 올리는 것을 1단계와 6-4 로 옮겼다. 이 판을 접어도 남긴다. (c) '채번을 멱등 판정 뒤에 두고 그 순서를 주석이 아니라 구조로 강제한다' 는 규율(4-3). (d) 그룹 커밋 자체는 배타적이지 않다. 락을 가르면 저널 축에 동시 커밋자가 생겨 PostgreSQL 이 알아서 묶을 수 있다. 8단계가 그 자리다.

### 10-2. 전역 시퀀스 + `market_cmd_seq` 별도 컬럼

기각. 결정적 사유 하나다.

커트오버 이전 약 97.7만 행에 그 컬럼이 영구 NULL 이다. `trg_cj_noupd` 와 SELECT/INSERT 전용 권한이 백필을 물리로 막는다(V1:595-598). 그런데 이 안은 소비측 커서·갭 판정·갭 회수·체크포인트·승격·스냅샷의 축을 전부 그 컬럼으로 옮긴다. 스냅샷이 0건이라 복구가 항상 seq 0 전량 재생인 현재 배선에서(9-5) 첫 기동 재생이 그 구간에서 깨진다. NULL 을 0 으로 매핑하면 `<= lastApplied` 로 첫 레거시 명령부터 무음 폐기되고, NULL 을 특례로 접어 전역 축으로 폴백해도 갭 회수원이 그 특례를 따라가지 못한다.

대역 파생은 같은 컬럼의 값만 옮기므로 커서가 커트오버 경계를 그대로 넘는다(2-3(1)). **이것이 대역 파생을 고르는 결정적 이유다.**

부수로 `nextval` 은 트랜잭션 밖이라 롤백에 결번이 남고, `CACHE > 1` 이면 세션별 예약 블록이 값 순서를 호출 순서와 어긋나게 해 `MatchingEngineImpl.apply` 의 무음 폐기로 간다. ADR 0021 이 이미 같은 이유로 시퀀스를 기각했다.

### 10-3. 전역 카운터에서 블록을 예약해 JVM 안에서 나눠 쓴다

기각. 무음 유실 때문이다.

블록 예약은 seq 의 '커밋 순서 단조' 를 없앤다. 정상 상태에서 12마켓 중 열한 마켓의 현재 블록 번호는 가장 바쁜 마켓의 블록보다 낮으므로, 전역 스칼라 커서(= 전 마켓 최대)가 항상 나머지 열한 마켓의 in-flight 번호보다 위에 있다. 그 마켓들이 지금 커밋하는 명령은 `seq > 커서` 를 만족하지 못해 다시는 읽히지 않는다. 그 경로가 둘이다. `FollowerCatchUp.advance`(매 틱 `from = engine.lastAppliedSeq()`)와 `EngineRecovery.replayToCheckpointThenTail`(리더십 대기 구간 뒤 `after` 로 다시 읽는다). 이벤트 커서도 같은 축이라 건너뛴 명령의 이벤트도 함께 건너뛴다. `verifyMatch` 가 발화하지 않고 pending 도 안 생긴다. 예외·지표·불변식 하나 없이 영구 무음 유실이다.

**살려서 이 판에 넣은 것.** `created_at` 을 시간 축으로 승격하는 착상. 이 판의 6-2(C22), 6-5 의 대안 검토, 5-3(크래시 창)이 전부 그 착상에서 나왔다. 그리고 0단계 (b)의 '채번부터 커밋까지' 계측.

### 10-4. 복합 키 `(market_id, seq)` 로 옮기고 전역 라벨을 없앤다

2026-09-11 재심. **기각은 유지하나 처음 적은 사유 둘은 둘 다 틀렸다. 다시 쓴다.**

사용자가 "마켓마다 독립 로그. 전역 숫자를 만들 필요 자체가 없음" 을 제시해 독립 로그안
셋을 새로 설계하고 이 판과 축 넷에서 맞붙였다. 집계는 대역 8승, 무승부 4, 독립 로그
0승이다. 그 과정에서 처음 적은 기각 사유가 둘 다 무너졌다.

**틀렸던 사유 (1). "축 이전이 백필보다 앞서 97.7만 행의 새 컬럼이 NULL 이다."**
10-2(새 컬럼 추가)의 형상을 복합 키에 그대로 옮겨 읽은 것이다. `seq` 컬럼을 재해석하는
변형에는 NULL 구간이 애초에 생기지 않는다. `market_id` 가 이미 SMALLINT NOT NULL 이고
레거시 seq 가 전역 유일이므로 `(market_id, seq)` 는 전 행에서 이미 유일하고 이미 채워져
있다. 백필이 0행이다.

**틀렸던 사유 (2). "PK 와 파티션 키 교체 비용."**
오독이다. PostgreSQL 이 요구하는 것은 파티션 키 컬럼이 유니크 제약 컬럼의 부분집합인
것이고 `seq` 가 `(market_id, seq)` 안에 있으므로 `PARTITION BY RANGE (seq)` 가 그대로
유효하다. 신표 생성도 정지 창도 없다. `command_journal` 을 가리키는 외래키도 0건이다.

**유지되는 진짜 사유 넷.**

1. **`event_emission_registry` 의 PK 가 `(source_cmd_seq, event_ordinal)` 이다**(V1:620-645).
   쌍으로 가면 마켓 2의 명령 5와 마켓 7의 명령 5가 둘 다 ordinal 0을 방출해 PK 가 정상
   방출을 23505로 거부하고, 그것이 정산 트랜잭션이라 라이터가 fail-fast 한다. 해는
   `market_id` 추가와 PK 확장이고 가능하나 약 159만 행 백필과 비어 있지 않은 표의 PK
   교체다. **대역 파생은 이 표를 한 줄도 안 건드린다.**
2. **정산 머리말의 점 조회 셋이 조용히 틀린다.** `SettlementService.PROLOGUE_SQL` 의
   `LEFT JOIN command_journal ON seq = ?` 가 "언제나 한 행" 계약을 깨고, 멱등 탐침이 남의
   마켓 이벤트를 세어 정산을 통째로 무음 스킵하며, `withRegisteredSeqs` 가 남의 마켓
   event_seq 를 이 체결의 id 로 배정한다. 셋 다 핫패스이고 셋 다 예외 없이 틀린다.
3. **커트오버 경계를 넘는 단일 커서 성질을 잃는다.** 2-3(1)에 적은 성질이다. 지역 번호를
   1부터 시작시키면 신판이 구판보다 작아져 마켓 안 오름차순이 깨지고, `rebuildMarket` 은
   마켓 안 seq 연속성을 일부러 검사하지 않으므로 예외 없이 틀린 북을 세운다.
4. **35일 안에 낼 수 있는 처리량 배수가 0이다.** 옛 표의 PK 가 `(seq)` 단독이라 교체 전에는
   마켓별 채번을 켤 수 없다. 곧 이 판의 이득이 전부 비가역 단계 뒤에 있다. 대역은 커트오버가
   `appendInTx` 한 줄 교체다.

**실패 모양의 비대칭이 결정적이다.** 대역에서 놓친 호출부는 seq 가 여전히 전역 유일이라
"순서가 틀린다" 로 끝난다. 쌍에서 놓친 호출부는 "행이 틀린다" 다. 무음 오정산, 무음 스킵,
명령 단위 원자성 파괴, 거짓 통과가 전부 예외도 지표도 없이 일어난다.

**그러나 방향 자체는 옳다. 이 판이 그 다음 회차의 비용을 미리 낮춘다.**

대역 파생은 쌍을 하나의 BIGINT 에 무손실 인코딩한 것이다. `market_id = seq / 10^12`,
`local = seq % 10^12` 로 언제든 복원된다. 곧 대역 파생은 복합 키의 상위 집합이고, 나중에
라벨을 버리고 쌍으로 내려가는 것은 값 안에 쌍이 이미 있으므로 **백필 0행이고 파티션 키도
그대로다.** 반대 방향은 불가능하다.

전수 결과가 그 판단을 받친다. `command_journal.seq` 의 전역 단일성에 매달린 약 65개 자리
중 쌍으로 원리적으로 복원 불가능한 것은 하나다. `OrderResponse.seq` 로 나가
`idempotency_keys.response_body` 에 굳는 대외 숫자이고, 그것도 같은 응답에 마켓 심볼이
이미 실려 있고 웹이 그 값을 대소 비교하지 않는다. **곧 전역 숫자가 정보로서 필요하다는
명제는 이 저장소에서 성립하지 않는다.**

**쌍으로 내려가는 조건 넷.** 전부 만족한 뒤에 한다.

1. 대역 커트오버가 끝나고 마켓별 축이 최소 7일 soak 된 뒤. 곧 발표 이후다.
2. `event_emission_registry` 백필과 PK 교체의 실제 ACCESS EXCLUSIVE 창을 복제본에서 실측한
   뒤. 리스 TTL 10초와의 여유가 지금 미검증이다.
3. 정산 머리말 세 술어와 C12·C13B 조인이 이미 쌍으로 돌고 있고, `command_journal` 을 seq
   단독으로 조건하는 SQL 을 거부하는 게이트가 선 뒤.
4. `command_journal` 보존 정책이 정해진 뒤. 지금 계약이 무기한 보존이라 레거시 구간이
   영원히 남고, 그 위에서 쌍으로 가면 경계 분기도 영구가 된다.

**살려서 이 판에 넣은 것.** `lib.ps1` 스테일 쓰기 판정의 fail-open 발견(6-5). `apply_lag` 를
최대와 합 둘로 내는 것. 좀비 판별식의 마켓별 확장. 부분 정체 경보. 그리고 아래 셋을
이 판의 단계에 이식했다. C12·C13B 조인을 지금 쌍으로 바꾸기, `findExistingNew` 의
`ORDER BY created_at, seq`, 복원 리시드 절차서 작성이다. 셋 다 두 안 공통이고 전부 가역이라
나중에 어느 쪽을 고르든 버려지지 않는다.

### 10-5. 대역 폭 10^7 (V36 값을 그대로 쓴다)

기각. 셋 다 산술이다.

- 수명이 일 단위다. 12마켓 균등 10.5일, 단일 마켓 집중 21.0시간.
- `marketId` 20 이상이면 대역 하한이 2x10^8 이라 첫 행부터 `command_journal_default` 로 간다. 무음 강등이고 유입 뒤에는 성장 경로가 영구히 닫힌다.
- 커트오버 자체가 경주다. 현재 전역 seq 가 10^6 자리이고 대역 하한이 10^7 이면 전역 카운터가 132.12/s 로 19.0시간 만에 마켓 1 대역을 침범한다. 10^12 면 240년이다.

### 10-6. 어드바이저리 락 키에 `market_id` 를 넣는다

기각. 4-3 에 사유를 적었다. 락을 가르는 순간 이 락이 `(user, coid)` 의 유일한 직렬화기가 되고, `command_journal` 은 seq 파티션이라 대체 UNIQUE 를 물리로 걸 수 없다.

### 10-7. `acceptGate` 를 마켓별로 가른다

기각. 리더십 단위를 다시 정의해야 하고 그것은 처리량 판과 섞으면 실패 시 원인 귀속이 불가능하다. 4-2 에 적었다.

### 10-8. C22 를 `PARTITION BY market_id` 로 좁힌다

기각. 검사가 보증하는 것이 '마켓 안의 epoch 단조' 로 줄어 마켓 사이 split-brain 을 백스톱이 못 본다. 시간 축(`created_at`)이 이 불변식의 원래 뜻이고, 6-2 에 그 축이 epoch 집합을 엄격히 가른다는 논증을 적었다.