# ADR 0022. 명령 저널을 마켓별 순서 원장으로 가른다

- 상태: 채택. **2026-09-13 착수를 앞당긴다. 클라우드 배포가 지원금 확정 대기로 밀려, 되돌릴 수 있는 0~5단계를 배포 전에 한다. 되돌릴 수 없는 6단계 래치 전환은 클라우드 스토리지에서 fsync 와 동시 커밋 확장성을 다시 잰 뒤 따로 결정한다. 6단계 배포는 래치 전이라 되돌릴 수 있다.** 아래 「이 회차가 함께 바꾸는 것」 의 착수 시점 항목이 이 결정으로 갈음된다. **2026-09-12 재개. 보류 사유였던 전제 (4)가 실측으로 뒤집혔다. 락 보유 안의 60퍼센트가 커넥션 획득과 트랜잭션 경계일 것이라 봤으나 실제로는 커밋이 99퍼센트다. 커밋은 마켓별로 곱해지지 않는 직렬 구간이 아니라 동시성으로 접히는 구간이므로 이 판이 치는 자리가 맞다.** 아래 「재개 근거」 절을 먼저 읽는다
- 날짜: 2026-09-11
- 선례: `docs/adr/0021-market-partitioned-ordering.md`(이벤트 축), `V36__market_seq.sql`
- 후속 연계: `docs/adr/0023-replay-bound-and-cursor-axis.md`. 재생 상한을 명령 축 연속 경계로 바꿨다. 이 판이 명령 축을 대역화하면 그 경계 술어와 C05·C22 를 함께 본다
- 근거 실측: `harness/evidence/submit-lock-20260911/README.md`, `harness/evidence/stage0-20260911/README.md`, `harness/evidence/stage0c-20260912/README.md`, `harness/evidence/leader-failover-20260908/`
- 코드: `V40__command_seq_band.sql`, 이름만 정한 마이그레이션 넷(3-1. 번호는 착수 시 다음 가용값), `EngineBridge`, `CommandJournalAppender`, `InflightReserves`, `LeaseDataSourceConfig`, `ApiExceptionHandler`, `EngineRecovery`, `ReplayService`, `WriterRouter`, `InvariantCheck`

## 계약 개정 (2026-09-13)

코드보다 계약을 먼저 고친다(CLAUDE.md 9절). 선행 커밋은 셋이다. 1단계 V40 이 `e8f993c`, ADR 0026 의 리스 행 FOR KEY SHARE 전환(V41)이 `2a818b6`, 그 위 카오스 재검증(E1 3회, E2·E4·E5·E6·E7 각 1회 PASS)이 `62095f2` 다. 2단계 이후 코드는 이 개정 시점에 main 에 없다.

- **체크포인트를 마켓별 연속 물질화 경계로 바꾼다(5-1, 5-2).** ADR 0023 결정 1 의 정의를 마켓마다 적용하고 `max(source_cmd_seq)` 정의를 버린다. max 정의는 마켓 안 seq 순 정산 전제가 깨지면 경계가 미물질화 명령 위로 올라간다.
- **커서는 엔진에서 유도한다(4-5).** 마켓 재생 진입점 셋이 커서 시작점을 받지 않는다(ADR 0023 결정 2). 그 인자 자리에 다른 축의 값이 들어간 것이 ADR 0023 결함 B 였다.
- **크래시 창 재적재를 k-way 병합으로 바꾼다(5-3).** 전역 `(created_at, seq)` 안정 정렬을 버리고 마켓별 seq 큐를 머리 배치의 `(created_at, seq)` 로 병합한다. DB 서버 시계가 뒤로 가면(프라이머리 전환 포함) `created_at` 이 마켓 안 순서를 뒤집을 수 있고, 병합은 큐 안 순서를 건드리지 않는다.
- **6단계 스위치는 DB 단방향 래치다(4-3, 7절 6단계).** 앱 설정으로 두면 낡은 설정으로 뜬 노드가 마켓 커서 아래 seq 를 써 무음 폐기된다. 래치 전 배포는 되돌릴 수 있고 래치 행 커밋이 비가역 지점이다. 복구와 팔로워 경로는 DB 가 막지 못하므로 래치 뒤 2단계 이전 앱으로의 롤백을 금지한다.
- **C26 을 3단계에서 7단계로 옮긴다(6-4).** 술어가 읽는 뷰를 7단계 마이그레이션이 만든다.
- **4-1 에 append 전용 풀과 `InflightReserves` 전역 락을 더한다.** 둘은 락을 가르면 드러날 전역 직렬 장치라 함께 가르지 않으면 4단계 평평함 실측을 오염시킨다.
- **9-13 을 정정한다.** 교차 마켓 동일 coid 는 게이트웨이가 409 로 끊고 200 replayed 로 돌아가지 않는다.
- **1-3(3) 을 정정한다.** `installFrom` 과 지연 생성 샤드의 스칼라 바닥은 전역 조밀 축에서 맞는 값이고, 대역 채번 뒤의 위험이다.
- 함께 고친 것. 4-4 와 6-2 를 V41 `uq_leader_lease_fencing_key` 전제로 다시 쓰고 C22 에 마켓 안 팔을 더한다. 4단계 평평 판정 문턱을 주행 전에 고정한다(7절 4단계). 6-5 를 현재 판정으로 정리하고 9절에 17 을 더한다. 미래 마이그레이션은 번호 없이 이름으로 적는다(3-1). 표류한 행 인용을 고친다.

---

## 0단계 실측 결과 (2026-09-11 추가)

증거는 `harness/evidence/stage0-20260911/README.md`, 정본 5.7.10 이다. 러너는
`harness/scale/stage0_probe.py` 다. 이 문서의 전제 셋이 뒤집힌다.

**(1) 저장소는 구속이 아니다. 8-2 의 분기 A 가 성립하지 않는다.**

동시 커밋을 1에서 24까지 쟀다. 단일 세션이 749.8 커밋/s 이고 명령 하나가 커밋을 둘
요구하므로 명령 374/s 다. 관측 천장 128/s 의 2.9배다. 8에서 24 구간은 2.92배로 사실상
선형이라 저장소가 동시 플러시를 흡수한다.

0단계 (a)의 문턱 "N=12 가 N=1 의 3배 미만이면 직렬 장치" 는 실측 배수 2.47 로 기계적으로는
분기 A 를 가리키나 **그 문턱 자체가 잘못 잡혔다.** 분자가 작아서가 아니라 분모가 커서 나온
값이다. 8-2 가 상한 근거로 든 `wal-fsync-20260907` 의 초당 311.9회는 `pg_test_fsync` 의
8킬로바이트 직렬 플러시이고 실제 커밋은 단일 세션에서도 그 2.4배다. 곧 분기 A(상한 156/s,
1.18배)는 없다.

**(2) 3-1(3)의 대역 파티션 선생성은 이득이 오기 전에 손해부터 단다.**

`findExistingNew` 는 술어에 파티션 키가 없어 잎 전부를 훑고, 그 조회가 접수 락 안에 있다.

| 잎 | 중앙값 | 스캔 노드 |
|---|---|---|
| 3 | 0.329밀리초 | 3 |
| 15 | 0.584밀리초 | 15 |
| 67 | 3.265밀리초 | 67 |

잎 67이면 2.94밀리초가 락 안에 더 붙어 보유가 9.8밀리초가 되고 천장이 144/s 에서 102/s 로
내려간다. 락 분할은 뒤 단계이므로 그 사이 내내 이 비용만 먼저 문다. **b1..b63 선생성을
버리고 마켓 생성 시 잎 생성으로 바꾼다.** 12마켓에 레거시 잎 셋을 더해 열다섯이고 비용이
0.26밀리초다. 3-1(6)이 선생성으로 얻으려던 마켓 id 상한의 구조적 차단은 채번 함수의 가드로
옮긴다. 꼬리 파티션 `command_journal_p200000000` 은 그대로 만든다. 그것은 잎 하나이고
파티션 시한을 닫는다.

**(3) 락 보유의 3분의 2가 데이터베이스 밖에 있다. 이 문서가 그것을 다루지 않는다.**

| 항목 | 값 |
|---|---|
| 컨테이너 사이 왕복 | 0.124밀리초 |
| 커밋 fsync | 약 1.2밀리초 |
| 데이터베이스 실행 합계 | 약 0.5밀리초 |
| 왕복 일곱 | 약 0.87밀리초 |
| 설명되는 몫 | 약 2.6밀리초 |
| 실측 락 보유 | 6.879밀리초 |
| 설명 안 되는 몫 | 약 4.3밀리초 (62퍼센트) |

후보는 커넥션 획득, 트랜잭션 경계, 페이로드 직렬화, 체크섬 계산, 부하 구간의 CPU 경합이다.
락 보유는 벽시계 시간이라 보유자가 스케줄에서 밀리면 함께 는다. 같은 창에서 앱 CPU 가
118에서 300퍼센트였다.

**장부를 쪼개도 마켓별로 같은 4.3밀리초를 문다.** 곧 이 판의 이득 계산(8-3, 8-4)이 그 몫을
상수로 깔고 있다. 같은 날 0단계 (b)로 그 안을 갈랐다.

**(4) 그 몫의 정체가 커넥션 획득과 트랜잭션 경계다. 이 판보다 싼 조치 둘이 그것을 친다.**

`CommandJournalAppender` 에 문장별 누적기를 넣고 12마켓 240/s 로 쟀다(정본 5.7.11).
락 보유 7.947밀리초 중 7.878이 append 이고 그 안이 이렇다.

| 항목 | 1회 | 비중 |
|---|---|---|
| 커넥션 획득과 트랜잭션 경계와 커밋 | 4.729밀리초 | 60.0퍼센트 |
| 멱등 판정 | 1.085밀리초 | 13.8퍼센트 |
| 리더 검증 | 0.745밀리초 | 9.5퍼센트 |
| 저널 삽입 | 0.693밀리초 | 8.8퍼센트 |
| 전역 채번 | 0.624밀리초 | 7.9퍼센트 |

커밋 fsync 가 약 1.2밀리초이므로 첫 줄에서 약 3.5밀리초가 데이터베이스 일이 아니다.
같은 창에서 데이터베이스 CPU 22.2퍼센트, 앱 CPU 26.2퍼센트인데 락 점유가 0.9923이다.

| 조치 | 보유 예고 | 천장 예고 | 실측(같은 날) |
|---|---|---|---|
| 지금 | 7.88밀리초 | 127/s | 접수 125.7 |
| 문장 넷을 함수 하나로 묶기 | 5.77밀리초 | 173/s | 머리말 2.453 에서 1.104. 예고대로 |
| 그 위에 append 전용 커넥션 고정 | 2.27밀리초 | 440/s | 커넥션 구간 4.729 에서 4.239. 예고의 7분의 1 |

**둘을 같은 날 구현하고 쟀다(정본 5.7.12). 천장이 126에서 160.25/s 로 27.5퍼센트 올랐다.**
조치 1은 예고대로 들었고 조치 2는 안 들었다. 그 구간의 비용이 풀 경합이 아니었다.
예고 440/s 는 틀렸다. 그래도 이 판이 예고한 1.6배(중앙 210/s)의 절반을 비가역 단계 없이 샀다. 장부를 쪼개도
마켓별로 같은 4.729밀리초를 물므로 순서가 뒤집힌다.

**곧 이 ADR 은 우선순위에서 내려간다.** 폐기가 아니다. 위 둘을 하고 나면 보유가 2.27밀리초가
되고 그때 남는 것이 마켓별로 곱해지지 않는 진짜 직렬 구간이라 장부 분할의 이득이 그제서야
깨끗이 보인다. 1단계를 시작하지 않는다. 먼저 위 둘을 재고 그 결과로 이 문서의 8절을 다시
쓴다.

## 재개 근거 (2026-09-12 추가)

이 문서는 재개 조건을 스스로 적었다. "먼저 위 둘을 재고 그 결과로 이 문서의 8절을 다시 쓴다."
그 둘을 2026-09-11 에 구현했고, 남은 구간의 정체를 2026-09-12 에 쟀다.
증거는 `harness/evidence/stage0c-20260912/`, 정본 5.7.13 이다.

**보류 사유였던 전제 (4)가 틀렸다.**

전제 (4)는 락 보유 안의 60퍼센트가 커넥션 획득과 트랜잭션 경계라고 봤다. 근거는 커밋 fsync 를
약 1.2밀리초로 고정하고 4.729 에서 그것을 뺀 잔차 3.5밀리초를 데이터베이스 밖으로 귀속한
것이었다. `f4ba7ca` 가 그 구간을 셋으로 가르는 계측을 넣었고 결과가 이렇다.

| 구간 | 240/s | 600/s |
|---|---:|---:|
| 커넥션 획득과 경계 시작 | 0.005밀리초 | 0.006밀리초 |
| 커밋 | 0.901밀리초 | 1.154밀리초 |
| 정리와 반납 | 0.004밀리초 | 0.006밀리초 |
| 합 | 0.910밀리초 | 1.165밀리초 |

**커밋이 99퍼센트다.** 커넥션을 얻고 돌려주는 비용은 합쳐서 1퍼센트다. 전제 (4)가 지목한
두 후보가 둘 다 아니었다. 5.7.12 가 "전용 풀을 줘도 안 줄어든 것이 그 증거" 로 적은 판단이
맞았고, 그 구간의 비용은 처음부터 커넥션이 아니었다.

**그래서 보류 논리가 성립하지 않는다.**

보류의 문장은 "장부를 쪼개도 마켓별로 같은 4.729밀리초를 물므로 순서가 뒤집힌다" 였다. 그
전제는 그 4.729가 마켓 수로 곱해지지 않는 고정 비용일 때만 참이다. 실제 정체인 커밋은 그
반대다. 전제 (1)이 이미 실측한 대로 동시 커밋이 확장된다.

| 동시 커밋 세션 | 커밋/s |
|---:|---:|
| 1 | 749.8 |
| 8 | 1,280.6 |
| 12 | 1,850.1 |
| 24 | 3,743.9 |

24 세션에서 단일 세션의 5.0배다. 곧 마켓 열둘이 각자 커밋하면 직렬 1회분이 아니라 병렬로
접힌다. 이 판이 치는 자리가 정확히 그 자리다.

**남은 직렬 구간은 전역 채번과 전역 락이다.** 그 둘이 이 문서의 대상이다.

## 이 회차가 함께 바꾸는 것

- **8절의 분기 A 는 폐기한다.** 전제 (1)이 이미 기각했고, 8-2 가 상한 근거로 든
  `wal-fsync-20260907` 의 초당 311.9회가 호스트 상수가 아니라는 것이 2026-09-12 에 확인됐다.
  같은 볼륨 같은 명령으로 2389.3회/s 가 나왔다. 7.7배다. 절대 예산으로 든 플러시/s 는 회차마다
  다시 재야 한다(기획안 한계 47).
- **천장 절대값을 이 문서의 예고 근거로 쓰지 않는다.** 계측만 다른 코드가 같은 요청에서
  157.47 과 255.36/s 로 1.62배 갈렸고 그 차이를 아직 설명하지 못한다. 이 판의 이득은 변화율로
  예고하고 같은 회차 안의 전후 대조로 판정한다.
- **9-14 의 위험 서술은 유효하고 6-5 의 것은 닫혔다.** `EventJournalReader.readAfterForMarket`
  의 V36 이전 NULL 행 문제(9-14)는 V36 적용 전부터 행이 있던 DB 에서 그대로다. `lib.ps1` 의
  스테일 쓰기 판정 fail-open(6-5)은 2026-09-12 에 위치 워터마크를 버리고 epoch 증분 판정으로
  바꿔 닫혔다(lib.ps1:534-573). 하네스 스크립트에 위치 워터마크 함수가 남아 있지 않다.
- **착수 시점.** 2026-09-12 판단은 M4 클라우드 배포 뒤였다. 2026-09-13 에 되돌릴 수 있는
  0~5단계를 배포 전으로 앞당겼다(머리말 상태 줄). CLAUDE.md 1절이 외부 접속 가능한 실제 배포를
  1순위로 두는 것은 그대로이고, 배포가 지원금 확정 대기로 밀린 구간을 쓴다.

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

`CommandJournalAppender` 가 append 트랜잭션 안에서 `append_command_prologue` 를 한 왕복으로 부르고(CommandJournalAppender.java:215-222), 그 함수가 멱등 판정 뒤에 `next_journal_value('command_seq')` 로 채번한다(V39__append_prologue.sql:75). 그 함수는 `journal_counters` 한 행의 `UPDATE`(V1__init.sql:551-565)이고 행 배타 잠금이 커밋까지 유지된다. 락을 가르면 12개 트랜잭션이 JVM 락 대신 이 행에서 다시 줄을 선다.

V36 이 같은 형태를 격리 실측으로 쟀다(ADR 0021 「결정」 표).

| 라이터 | 전역 카운터 | 마켓별 카운터 |
|---|---|---|
| 1 | 39.7 | 39.7 |
| 4 | 42.9 | 168.2 |
| 12 | 43.5 | 514.5 |

곧 "락을 가른다" 는 "채번을 가른다" 의 종속 결과이고 순서를 바꿀 수 없다. 이 판은 채번을 먼저 가르고 락을 그 위에 얹는다.

### 1-3. 이 판이 함께 다루는 것 셋

셋의 성격이 다르다. (1)은 지금 치르는 비용이다. (2)는 ADR 0023 이 전역 축에서 닫았고, 마켓 벡터로 옮기지 않으면 대역 채번 뒤 다시 열린다. (3)은 오늘의 결함이 아니라 대역 채번 뒤의 위험이다.

1. **갭 fast path 소실.** 마켓별 라이터에서 전역 채번을 쓰면 한 마켓 스트림의 seq 가 성기다. `SingleWriterLoop.process`(SingleWriterLoop.java:238)의 `command.seq() > last + 1` 이 사실상 모든 라이브 명령에서 참이라 명령마다 `catchUpTo` 가 저널을 한 번 더 SELECT 한다. 그 왕복은 `applyNanos`·`sinkNanos`·`hookNanos` 어디에도 계상되지 않으므로 실측 "라이터 sink 점유 2.146" 은 과소 보고다. 대역 파생이면 마켓 안에서 seq 가 +1 씩 증가해 이 왕복이 사라진다.
2. **전역 스칼라 체크포인트.** `max(source_cmd_seq)` 하나를 "이 값 이하는 이벤트가 전부 있다" 로 읽는 해석은 라이터가 하나일 때만 참이다. 라이터가 열둘이면 뒤진 마켓의 미물질화 명령이 상한 안에 들어온다. 2026-09-12 에 그 구멍을 실측했고(`harness/evidence/materialization-holes-20260912/README.md`) ADR 0023 결정 1 이 상한을 연속 물질화 경계로 바꿔 전역 축에서 닫았다(EventJournalReader.java:29-44). 대조 구간에서 pending 이 나오면 던지는 가드는 `ReplayService.verifyTo`(ReplayService.java:88-105)에 있다. 그 경계도 `c.seq > engine.lastAppliedSeq()` 한 스칼라에서 구멍을 찾는다. 대역 채번 뒤에는 그 커서가 가장 높은 대역에 서서 낮은 대역 마켓의 구멍을 보지 못한다. 그래서 5-2 가 같은 정의를 마켓마다 적용한다.
3. **`WriterRouter.installFrom` 과 지연 생성 샤드의 스칼라 바닥.** 복구본의 `lastAppliedSeq` 스칼라 하나를 전 마켓 엔진에 심고(WriterRouter.java:131-154), 설치 뒤 새로 생기는 샤드도 그 값을 바닥으로 받는다(WriterRouter.java:48-50, 74-77). 전역 조밀 축에서는 맞는 값이다. 복구본이 그 seq 까지 전 마켓 명령을 반영했고 이후 명령은 seq 가 더 크다(WriterRouter.java:122-124). **대역 채번 뒤에는 틀린다.** 그 값이 가장 높은 대역 마켓의 seq 이므로 낮은 대역 마켓 엔진이 자기 명령을 `seq <= lastAppliedSeq`(MatchingEngineImpl.java:23-25)로 조용히 버린다. 예외도 로그도 없고 상시 감사에 안 보인다(C13A 가 AUTHORITATIVE_ONLY, InvariantCheck.java:135). 오늘의 결함이 아니라 래치 전에 반드시 닫아야 할 위험이고 2단계가 닫는다.

### 1-4. 파티션 시한

`command_journal` 은 `PARTITION BY RANGE (seq)` 이고 파티션이 `p0 [0,10^8)`, `p100000000 [10^8,2x10^8)`, `DEFAULT` 셋뿐이다(V1__init.sql:592-594). V1 이후 파티션을 더한 마이그레이션이 0건이다. 현재 저널이 약 10^6 자리이므로 132.12/s 로 `DEFAULT` 유입까지 약 17.4일이다. `DEFAULT` 에 한 행이라도 들어가면 그 구간의 파티션을 영영 만들 수 없다(PostgreSQL 이 `DEFAULT` 를 훑어 거부한다). C15 는 WARN 이라 멈추지 않는다.

**이 시한은 이 판을 하든 안 하든 온다.** V40 이 그것을 먼저 닫는다.

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

### 3-1. V40__command_seq_band.sql (되돌릴 수 있다. 채번 경로를 바꾸지 않는다)

V39 는 `V39__append_prologue.sql`, V41 은 ADR 0026 의 `V41__lease_key_share_lock.sql` 이 쓴다. 번호의 정본은 `docs/contracts/백엔드_DB_설계.md` 3절 마이그레이션 표다.
이 판에서 번호가 정해진 마이그레이션은 이 절의 V40 하나다. 나머지 넷은 이름으로만 적고 번호는 착수 시 다음 가용값이다. 4단계 `append_prologue_created_at_order`(4-3), 6단계 `command_band_cutover_latch`(7절 6단계), 7단계 `band_headroom`(3-2), 선택 9단계 `event_seq_band_widen`(3-3)이다. 적용된 마이그레이션 파일은 고치지 않는다.

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

**(2) 축 경계 상수.** V36 이전 `event_journal` 행은 `market_id` 가 NULL 이다. 그 구간의 명령 상한은 전역 스칼라 하나로 고정돼 있고 다시 늘지 않으므로 마이그레이션이 한 번 계산해 박제한다. 이 상수는 5-2 구멍 탐색의 바닥이자 9-14 레거시 패스의 경계다. 체크포인트의 정확성은 이 상수에 기대지 않는다. 5-2 의 구멍 판정은 `source_cmd_seq` 로 짝을 짓고 `event_journal.market_id` 를 보지 않으므로 상수가 없어도 레거시 명령이 구멍으로 보이지 않고 탐색 구간이 레거시 구간까지 넓어질 뿐이다. 체크포인트에 `event_journal.market_id` 술어를 걸지 않는다. V40 주석(V40__command_seq_band.sql:36-38)은 이 상수를 조용한 마켓의 체크포인트 0 방지로 설명하나 이 절과 5-2 가 우선한다. 적용된 마이그레이션이라 주석을 고치지 않는다(CLAUDE.md 30절).

```
CREATE TABLE journal_axis_boundary (name TEXT PRIMARY KEY, cmd_seq BIGINT NOT NULL);
INSERT INTO journal_axis_boundary
SELECT 'pre_v36_event', COALESCE(max(source_cmd_seq), 0) FROM event_journal WHERE market_id IS NULL;
```

배포 시 일회 확인이 붙는다. `cmd_seq` 이하의 명령 중 이벤트가 없는 것이 0건이어야 한다. 0 이 아니면 V36 이전 구간이 단일 라이터였다는 전제가 틀린 것이고 여기서 멈춘다.

**(3) 파티션을 코드보다 먼저 만든다. 대역 잎은 마켓이 있는 것만 만든다.**

- 레거시 꼬리 `command_journal_p200000000` = `[2x10^8, 10^12)` 하나. **이것 하나가 1-4 의 17.4일 시한을 닫는다.** 이 판을 접어도 이 파티션은 남긴다.
- 대역 `command_journal_b<K>` = `[K*10^12, (K+1)*10^12)` 를 **지금 있는 마켓 id K 에 대해서만** 만든다. 12마켓이면 잎 12개에 레거시 셋을 더해 열다섯이다. 앞으로 생길 마켓의 잎은 (6)의 트리거가 만든다.
- b1..b63 선생성을 하지 않는 이유는 0단계 실측 (2)다. 멱등 조회가 파티션 키 술어 없이 잎 전부를 훑어, 잎 67개면 접수 락 안에 2.94밀리초가 더 붙고 잎 15개면 0.26밀리초다.
- 전부 비어 있으므로 DROP 으로 되돌릴 수 있다. 대역 값이 처음 쓰이는 것은 6단계이고 이것은 1단계다. 배포 순서가 곧 안전이다.

**(4) `next_market_journal_value` 일반화.** 하드코딩된 `> 10000000`(V36:66)을 `journal_band_spec` PK 조회로 바꾼다. 2행짜리 표의 PK 조회라 공유 버퍼에서 끝나지만 이 함수는 정산 핫패스이므로 1단계 배포 전후로 `exchange.writer.sink.nanos` 를 대조한다.

**(5) `next_market_command_seq(p_market_id SMALLINT) RETURNS BIGINT` 신설.** `band_span`·`max_band_index` 를 조회하고, `p_market_id < 1 OR > max_band_index` 면 예외, 그 마켓의 대역 잎 `command_journal_b<p_market_id>` 가 없으면 예외, `next_market_journal_value(p_market_id, 'command_seq', 1)` 로 지역 번호를 받고, 지역 번호가 `band_span` 이상이면 예외, `p_market_id * band_span + local` 을 반환한다. 파생을 DB 에 두어 앱이 대역 폭을 모르게 한다.

**(6) 시드와 트리거.** `market_journal_counters` 에 기존 마켓의 `'command_seq'` 행을 넣고, `seed_market_journal_counters`(V36:78-89)를 확장해 `'command_seq'` 도 시드하며, `NEW.id > max_band_index` 면 거부하고, 그 마켓의 대역 잎 `command_journal_b<NEW.id>` 를 만든다. `markets.id` 는 `SMALLINT PRIMARY KEY` 로 운영이 값을 직접 준다(V1:38). 마켓 행과 잎이 같은 트랜잭션에서 생기므로 파티션 없는 마켓이 만들어지지 않는다. 대가는 마켓 생성 순간 `command_journal` 부모에 걸리는 ACCESS EXCLUSIVE 잠금이고, 그동안 접수 append 가 기다린다. 마켓 생성은 관리자 조작이라 드물다. prod 풀은 `lock_timeout` 2초라 트래픽이 흐르는 중이면 그 잠금을 못 얻고 마켓 생성이 실패한다. 지금 prod 에 마켓을 만드는 런타임 경로는 없다. `command_journal` 은 partman 10^8 격자와 꼬리 파티션이 겹치므로 partman 대상에서 뺀다(`docs/contracts/백엔드_DB_설계.md` §3.2). 채번 함수 (5)의 `p_market_id > max_band_index` 가드가 같은 상한을 한 번 더 막는다.

**(7) 권한.** 저널 GRANT 는 부모 표 단위라 새 잎에 따로 주지 않는다. `next_market_command_seq` 에 `app_role` EXECUTE 를 준다. `next_market_journal_value` 는 호출자 권한으로 돌고 정산에서 `app_role` 이 부르므로 명세표 읽기 권한이 없으면 정산이 멈춘다. 그래서 두 새 표는 `app_role` 과 `harness_role` 둘 다에 SELECT 를 준다. 잎 생성은 `ensure_command_band_partition`(SECURITY DEFINER, PUBLIC 회수) 하나가 맡고 기존 마켓 시드와 (6)의 트리거가 같이 부른다.

되돌리기: 파티션 DROP(비어 있음), 두 함수 CREATE OR REPLACE 복귀, 시드 행 DELETE, 표 둘 DROP.

### 3-2. band_headroom 마이그레이션 (7단계. 번호는 착수 시 다음 가용값)

- `v_journal_band_headroom` 뷰. 마켓별 `next_value`, `band_span - next_value`, 잔여 비율.
- 게이지 `exchange.journal.band.remaining{name,market}`. 정적 바닥은 SQL 이, 소진 속도는 게이지가 본다.

### 3-3. event_seq_band_widen 마이그레이션 (선택. 별건. 번호는 착수 시 다음 가용값)

이벤트 축의 한계 43 을 같은 처방으로 닫는다. `journal_band_spec` 의 `('event_seq', 10^7, 19)` 를 `(10^12, 63)` 으로 올리고 `event_journal` 파티션을 같은 모양으로 더한다. 이벤트 축은 `market_seq` 가 별도 컬럼이라 지역 번호가 이어지고 파생값만 뛴다. 기존 행은 그대로 유일하고 마켓 안에서 증가도 유지된다. **이 판의 필수가 아니다.** 명령 축 커트오버가 안정화된 뒤 별도 회차로 판단한다.

### 3-4. 새 컬럼·새 인덱스가 없다

`command_journal` 에 컬럼을 더하지 않는다. 인덱스도 더하지 않는다. 마켓별 읽기는 기존 `idx_cmd_market (market_id, seq)`(V1:585)로 끝나고 대역 술어가 파티션 프루닝까지 한다. 멱등 조회는 기존 `idx_cmd_client_new` 로 끝나지만 파티션 키 술어가 없어 잎마다 인덱스를 한 번씩 훑는다. 잎 수가 곧 비용이라 3-1(3)이 잎을 마켓 수로 묶는다. 크래시 창 정렬은 메모리에서 한다(5-3).

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

**계측도 함께 가른다.** `lockWaitNanos`·`lockHoldNanos`·`submitCount` 세 `LongAdder`(EngineBridge.java:43-48)를 마켓별로 두고 `EngineConfig` 의 `FunctionCounter` 등록(EngineConfig.java:468-476)에 `market` 태그를 반드시 붙인다. 태그 없이 등록하면 두 번째 마켓에서 미터 id 가 충돌해 첫 것만 관측된다. 이 저장소가 2026-09-10 에 같은 사고를 두 번 겪었다(EngineConfig.java:366-374 주석).

**락을 가르면 드러날 전역 직렬 장치 둘을 함께 가른다.** 락만 가르면 상한이 이 둘로 옮겨 가고, 4단계의 평평함 실측이 채번 행이 아니라 이 둘을 재게 된다.

- **append 전용 풀 크기 2.** append 풀은 `submitLock` 안에서 쓰여 락이 직렬화한다. `appendDataSource` 가 최대 2로 고정하고(LeaseDataSourceConfig.java:57, `application.yml` 의 `exchange.engine.append-datasource.hikari.maximum-pool-size`) 그 근거를 "`EngineBridge.submitLock` 이 이미 직렬화하므로 동시에 커넥션을 둘 이상 쓸 일이 구조적으로 없다" 로 적는다(LeaseDataSourceConfig.java:43-44). 락을 가르면 그 전제가 사라진다. 크기를 마켓 수 + 2 로 올린다. 풀 대기가 `connectionTimeout` 2초(LeaseDataSourceConfig.java:59)를 넘으면 트랜잭션 시작이 `CannotCreateTransactionException` 으로 실패하는데 503 매핑(ApiExceptionHandler.java:82)에 그 형이 없어 재시도 신호로 나가지 않는다. 매핑을 더한다. 노드 수 x (요청 풀 + 리스 풀 2 + append 풀) 합을 PostgreSQL `max_connections` 와 대조해 기록한다.
- **`InflightReserves` 전역 락.** 잔고 사전 가드가 모든 사용자와 자산의 예약을 `ReentrantLock` 하나로 직렬화한다(InflightReserves.java:32). 이 락은 `submitLock` 앞에 있다. 게이트웨이가 `bridge.submit` 전에 잡고 풀므로(OrderGateway.java:114-115, 158, InflightReserves.java:49-62) 지금 상한으로 드러나지 않는 이유는 락의 직렬화가 아니라 짧은 보유다. 보유는 미측정이고 0단계 계측에 더한다. 그 락 안에서 가용 잔고를 DB 에서 읽고(InflightReserves.java:54, OrderGateway.java:563-571), 만료되지 않은 지연 항목마다 주문 행 실재를 DB 에서 본다(InflightReserves.java:85-95, OrderGateway.java:59-60). 락 단위를 `reserveKey`(사용자:자산, OrderGateway.java:95-96)로 가른다. 마켓 단위로 가르지 않는다. 호가 자산을 여러 마켓이 공유하므로 마켓 단위면 같은 잔고의 이중 통과가 열린다. 가용 잔고 조회는 키 락 안에 남기고(InflightReserves.java:46-47 계약), sweep 은 요청 임계구역 밖으로 뺀다.

### 4-2. 접수 게이트

`submit` 의 게이트 셋 중 둘이 전역이다(EngineBridge.java:120-134). **둘 중 하나만 가른다.**

- `CommandSink.isRunning(int marketId)` 를 더하고 `EngineBridge.submit` 의 첫 게이트(EngineBridge.java:120)가 `sink.isRunning(intake.marketId())` 를 본다. `WriterRouter.isRunning(int marketId)` 는 `started` 가 거짓이면 거짓, 그 마켓의 샤드가 아직 없으면 참, 있으면 그 샤드 루프의 `isRunning()` 이다. `CommandSink.of(loop)` 의 판은 `loop.isRunning()` 이다.
- 샤드가 없는 마켓을 참으로 보는 이유. 샤드는 첫 명령의 `offer` 가 지연 생성하고 `started` 면 만들자마자 띄운다(WriterRouter.java:66-84, 100-102). 정지한 라이터가 없으므로 닫을 사유가 없다. `shards.get(marketId).loop()` 를 바로 부르면 그 마켓의 첫 주문이 NPE 로 떨어진다. 판정에서 `shard(marketId)` 를 부르지도 않는다. 판정이 샤드를 만들면 뒤 게이트(`acceptGate`, 마켓 정지)에서 거부될 접수도 라이터 스레드를 띄운다.
- 한 샤드의 정지가 그 마켓의 접수만 닫는다. WriterRouter.java:104-110 의 떠돌이 주석('마켓별 게이트는 이 판의 범위가 아니다')이 여기서 해소된다. 전체 기준 `isRunning()`(WriterRouter.java:163-168)은 수명주기와 헬스 응답이 계속 쓴다.
- `acceptGate` 는 전역으로 둔다. 승격 재생 중 접수 보류는 리더십 단위의 사실이고 리더는 노드 전체로 하나다. 마켓별로 가르면 강등·승격 판정의 단위를 다시 정의해야 하고 그것은 이 판의 범위가 아니다.

### 4-3. 채번과 멱등

- 채번은 `append_command_prologue` 안의 `next_journal_value('command_seq')` 한 줄이다(V39__append_prologue.sql:75). 6단계의 `command_band_cutover_latch` 마이그레이션이 마켓 id 를 받는 6인자 판을 더한다. 6인자 판은 채번 직전에 같은 트랜잭션에서 래치를 읽고, 래치가 없으면 `next_journal_value('command_seq')`, 있으면 `next_market_command_seq(p_market_id)` 를 부른다. 앱이 6인자 판을 불러도 래치 전에는 채번이 오늘과 같다. 비가역 지점은 첫 대역 행이 아니라 래치 행이 커밋되는 순간이다(7절 6단계).
- 채번은 지금도 멱등 판정 뒤에 있고 V39 가 그 순서를 함수 구조로 못박았다. 그 순서가 C05 의 근거이므로 새 판도 같은 구조를 유지한다. 채번과 INSERT 사이에 조건 분기를 넣지 않는다.
- **어드바이저리 락 키를 건드리지 않는다.** `pg_advisory_xact_lock(hashtextextended('cmd-new:' || user_id || ':' || client_order_id, 0))`(V39 함수 본문)의 키에 `market_id` 를 넣지 않는다. 지금은 전역 `submitLock` 이 모든 append 를 직렬화하므로 이 락의 교차 마켓 역할이 잠들어 있다. 락을 가르는 순간 겹이 둘에서 하나로 줄고 이 락만 남는다. 키에 마켓을 넣으면 서로 다른 마켓의 같은 `(user_id, client_order_id)` 동시 재전송 둘이 서로를 못 보고 둘 다 ACK 를 받는다. 그 뒤 정산의 `orders` INSERT 가 `uq_orders_client` 23505 로 죽고 라이터가 fail-fast 한다. `command_journal` 은 seq 파티션이라 `(user_id, client_order_id)` UNIQUE 를 물리로 걸 수 없으므로 대체 방어선이 없다(V1:586-588 주석이 그 사실을 이미 적고 있다).
- 같은 이유로 멱등 조회의 WHERE 에도 `market_id` 를 넣지 않는다. 그 조회는 `append_command_prologue`(V39) 안에 있다. 멱등 네임스페이스가 `(user_id, client_order_id)` 이고 마켓이 없다.
- **한 줄이 바뀐다.** `ORDER BY c.seq LIMIT 1`(V39__append_prologue.sql:61-62)은 대역 파생에서 '가장 이른 명령' 이 아니라 '가장 작은 marketId 의 명령' 을 고른다. 4단계의 `append_prologue_created_at_order` 마이그레이션이 `ORDER BY c.created_at, c.seq LIMIT 1` 로 바꾼다. 이 선택이 값을 가르는 것은 같은 `(user_id, client_order_id)` 의 NEW 행이 둘 이상일 때뿐이다. 어드바이저리 락이 그 상태를 막고 C07P 가 그 상태를 센다(InvariantCheck.java:62-68). 곧 방어선이고, 뚫렸을 때 '먼저 접수된 것이 이긴다' 는 뜻을 대역 전후로 같게 둔다. 교차 마켓 재사용이 200 replayed 로 나가는 경로는 없다. 게이트웨이가 409 로 끊는다(9-13).
- 같은 마이그레이션이 함수를 `VOLATILE` 로 명시한다. 어드바이저리 락을 기다린 뒤의 멱등 조회가 앞선 트랜잭션의 커밋을 보려면 문장마다 새 스냅숏을 잡아야 하고, READ COMMITTED 와 VOLATILE 이 그 전제다. V39 는 선언이 없어 기본값 VOLATILE 로 돈다(V39__append_prologue.sql:40).

### 4-4. 잠금 계층과 교착

마켓별로 12개 append 트랜잭션이 동시에 도는 것은 이 배선에서 처음이다. 한 append 가 잡는 잠금은 순서대로 셋이다(V39__append_prologue.sql:47-75, CommandJournalAppender.java:34-43).

1. `pg_advisory_xact_lock('cmd-new:user:coid')`. NEW 이고 `client_order_id` 가 있을 때만 하나 잡고 취소는 잡지 않는다(V39__append_prologue.sql:52-54).
2. 채번 카운터 행. 래치 전에는 전 마켓이 같은 `journal_counters('command_seq')` 행이고(V1__init.sql:551-565), 래치 뒤에는 자기 마켓의 `market_journal_counters` 행이다. 둘 다 커밋까지 쥔다.
3. `leader_lease` 행 `FOR KEY SHARE`(CommandJournalAppender.java:42, ADR 0026).

- 모든 트랜잭션이 같은 순서로 잡고 (1)이 많아야 하나이며 (2)가 트랜잭션당 하나이므로 append 끼리 순환이 생길 조합이 없다. 논증이므로 다중 마켓 동시 append 통합 시험(`MultiMarketConcurrentAppendTest`)으로 고정한다.
- (3)의 안전은 V41 의 `uq_leader_lease_fencing_key`(V41__lease_key_share_lock.sql:27)에 기댄다. 그 평문 유일 인덱스가 holder·epoch 를 키 열로 만든다. 그래서 둘을 바꾸는 `try_acquire_leader` 와 새 epoch 의 `publish_leader_epoch` 는 FOR UPDATE 를 잡고 진행 중 append 의 커밋을 기다리고, `lease_until` 만 바꾸는 `renew_leader_lease`·`expire_leader_lease_now` 는 FOR NO KEY UPDATE 라 기다리지 않는다(ADR 0026 결정 표). 인덱스가 없거나 부분·식·INCLUDE 인덱스면 승격이 쓰기를 기다리지 않아 판정과 커밋 사이 epoch 불변이 깨진다(V41__lease_key_share_lock.sql:13-14, ADR 0026 S4). 6-2 의 C22 논증이 이 전제 위에 선다. 시험은 `LeaderLeaseLockModeIntegrationTest` 의 `promotionWaitsForWriterCommitAcrossRenewAndExpire`, `commandAppendKeyShareBlocksPromotionNotRenew`, `promotionDoesNotWaitWithoutFencingKeyIndex` 다.
- 락을 가르면 리스 행 공유 잠금을 쥔 append 가 1 에서 최대 12 로 늘고 정산 쪽 열둘에 더해진다. 갱신은 굶지 않는다(`renewDoesNotWaitForWriterKeyShare`). 겹친 보유자 앞에서 승격과 게시가 기다리는 성질은 FOR SHARE 때와 같다(ADR 0026 대가). 락 분할 뒤 갱신이 굶지 않음은 다중 마켓 동시 append 시험에서 틱마다 갱신 지연으로 확인한다.

### 4-5. 축 이전이 닿는 파일

| 파일 | 무엇을 바꾸나 |
|---|---|
| `engine/core/MatchingEngineImpl` | `lastAppliedSeq` 스칼라를 `Map<Integer,Long>` 으로. `apply` 비교를 마켓별로. 마켓 커서 조회 `appliedSeq(marketId)`. `loadSnapshot` 을 `loadMarketSnapshot(marketId, seq, book)` 으로. 무인자 `lastAppliedSeq()` 는 `maxAppliedSeqForReporting()` 으로 개명해 판정 경로에서 뺀다 |
| `engine/core/SingleWriterLoop` | `process`·`catchUpTo` 를 마켓 커서로. `catchUpCalls`·`catchUpNanos` 계측 신설 |
| `engine/WriterRouter` | `isRunning(int)`(4-2). `installFrom` 이 마켓별 커서를 심고, 지연 생성 샤드의 바닥 `installedSeq` 를 마켓별로 바꾼다(1-3(3)) |
| `engine/EngineBridge` | 4-1, 4-2 |
| `api/order/InflightReserves` | 4-1. 전역 락을 `reserveKey` 단위 락으로 |
| `config/LeaseDataSourceConfig`, `application.yml` | 4-1. append 풀 크기 마켓 수 + 2. 코드 기본값(LeaseDataSourceConfig.java:57)과 dev 프로파일 `exchange.engine.append-datasource.hikari.maximum-pool-size`(application.yml:152)를 함께 바꾼다. yml 바인딩이 코드 기본값을 덮는다(LeaseDataSourceConfig.java:36-37, 52) |
| `api/common/ApiExceptionHandler` | 4-1. 트랜잭션 시작 실패를 503 Retry-After 로 |
| `engine/EngineRecovery` | 마켓 순회 2단계 재생. 크래시 창 재적재 병합(5-3) |
| `engine/PromotionCoordinator` | `replayOnce` 의 head 비교를 벡터 dominates 판정으로 |
| `engine/FollowerCatchUp` | 마켓별 커서·상한·lag. 불변식 M 재서술 |
| `journal/CommandJournalReader` | `maxSeqByMarket()`·`maxSeqForMarket(int)` 신설. `maxSeq()` 는 표시용으로만 남긴다 |
| `journal/EventJournalReader` | `materializedThroughByMarket(cursors)` 신설(5-2). `readAfterForMarket` 에 레거시 구간 패스(9-14) |
| `journal/JournalCheckpoint` | `JournalCheckpoints`(마켓 벡터와 `legacy_bound`)로 |
| `journal/ReplayService` | 마켓 진입점 셋. `rebuildMarket(engine, marketId)`, `verifyToMarket(engine, marketId, bound)`, `replayTailMarket(engine, marketId, headOfMarket)`. 커서 시작점을 인자로 받지 않는다. `PendingBatch` 반환 |
| `journal/ReplayResult` | `pendingBatches()` 추가. `pendingEvents()` 는 평탄화 뷰로 남긴다 |
| `journal/CommandJournalAppender` | 4-3 |
| `snapshot/SnapshotStore` | 세트 개념 폐기. `loadLatestAdoptablePerMarket()` |
| `config/EngineConfig` | 지표 마켓 태그. 스냅샷 훅을 샤드 팩토리로(7단계) |
| `admin/HaMonitoringController` | `command_watermarks` 배열, `apply_lag` 최대와 합 |
| `harness/InvariantCheck` | 6절 |

**커서는 엔진에서 유도한다(ADR 0023 결정 2).** 마켓 진입점 셋은 본체가 `engine.appliedSeq(marketId)` 로 명령 커서와 이벤트 커서를 함께 연다. 호출자가 넘기는 것은 상한뿐이다. 호출자가 커서 시작점을 넘기면 그 자리에 다른 축의 값이 들어갈 수 있고, ADR 0023 결함 B 가 정확히 그 모양이었다. 판독기의 커서 인자(`readAfterForMarket`, `materializedThroughByMarket`)는 재생 본체와 경계 계산 호출자가 엔진에서 읽어 채우고, 그 값은 그 마켓 축의 적용 커서여야 한다. `readAfterForMarket` 의 커서는 돌려줄 행을 정하고, 경계 계산의 커서는 참 경계 이하일 때만 성능 하한이다.

문서·하네스: `harness/sql/invariants.sql`, `harness/sql/summary.sql`, `web/lib/admin/httpAdmin.ts`, `harness/grafana/dashboards/02-recovery.json`, `docs/demo/15분_라이브데모_대본.md` 네 자리, `docs/contracts/하네스_검증설계.md` R0·R5, `docs/contracts/백엔드_API_설계.md` §7-1 gapless 문구.

---

## 5. 재생과 복구

### 5-1. 축이 왜 마켓 벡터여야 하는가

논리 사슬이 하나다.

1. 대역 파생이면 전역 연속 물질화 경계(ADR 0023 결정 1)도 스칼라 커서 하나에서 구멍을 찾으므로, 가장 높은 대역에 선 커서가 낮은 대역 마켓의 구멍을 보지 못한다. 곧 체크포인트가 마켓 벡터여야 한다.
2. 체크포인트가 벡터이면 재생 상한도 마켓마다 다르다.
3. 상한이 마켓마다 다르면 `WHERE seq > cursor` 라는 전역 스칼라 커서로 그것을 표현할 수 없다. 곧 재생 커서가 마켓별이어야 한다.
4. 재생 커서가 마켓별이면 엔진 커서도, 스냅샷 세트 키도, 승격·팔로워 판정도 마켓별이어야 한다.

**이 사슬은 대역 파생과 무관하게 지금 당장 옳다.** 명령은 접수 때 커밋되고 이벤트는 마켓별 라이터가 각자 적재하므로 전역 스칼라 하나로는 마켓마다 다른 진행을 표현할 수 없다. 전역 연속 경계는 안전하지만 한 마켓의 구멍이 전 마켓의 상한을 누른다. 마켓마다 같은 정의를 두면 그 누름이 사라진다(5-2). 마켓별 경계 값은 전역 경계와 달라질 수 있으나 재생 결과, 곧 최종 북과 마켓마다 적용된 명령 집합은 같아야 한다. 그래서 2단계의 시험 조건을 '오늘 전역 조밀 축에서도 결과가 같다' 로 둘 수 있다.

### 5-2. 체크포인트 벡터의 정의

ADR 0023 결정 1 의 연속 물질화 경계를 마켓마다 적용한다(`docs/adr/0023-replay-bound-and-cursor-axis.md`).

```
legacy_bound = journal_axis_boundary('pre_v36_event').cmd_seq     -- V40 이 박제한 상수
cursor(m)    = engine.appliedSeq(m)                                -- 호출자가 엔진에서 읽어 넘긴다. 참 경계 이하이면 성능 하한이다
from(m)      = GREATEST(cursor(m), legacy_bound)
hole(m)      = (SELECT min(c.seq) FROM command_journal c
                 WHERE c.market_id = m AND c.seq > from(m)
                   AND NOT EXISTS (SELECT 1 FROM event_journal e
                                    WHERE e.source_cmd_seq = c.seq))
head(m)      = (SELECT max(c.seq) FROM command_journal c
                 WHERE c.market_id = m AND c.seq > from(m))
bound(m)     = COALESCE(hole(m) - 1, head(m), from(m))
```

- 구멍이 없으면 경계는 head(m)이다. 구멍이 있으면 그 마켓의 첫 미물질화 명령 바로 앞이다. 다른 마켓의 구멍은 이 마켓의 경계를 누르지 않는다.
- `max(source_cmd_seq) GROUP BY market_id` 로 정의하지 않는다. 마켓 안 라이터가 하나이고 seq 순으로 정산하는 동안에는 두 정의가 마켓 m 의 명령 집합을 같은 자리에서 가른다. 값은 다를 수 있다. 연속 정의는 첫 미물질화 명령 바로 앞을, max 정의는 마지막 물질화 명령을 가리키고 그 사이에는 다른 마켓의 seq 만 있다. 갈리는 것은 그 전제가 깨질 때의 방향이다. max 정의는 경계가 미물질화 명령 위로 올라가 대조 구간에서 pending 이 나 기동이 서거나(ReplayService.java:92-103), 승격 생략이 크래시 창 위에서 접수를 연다(PromotionCoordinator.java:310-313). 연속 정의는 이벤트 실재를 직접 보므로 전제에 기대지 않는다. 틀려도 보수 쪽이고 재적재 구간이 늘 뿐이며 그 구간은 `event_emission_registry` 멱등 스킵이 거른다(ADR 0023 영향).
- 구멍 판정은 `source_cmd_seq` 로 짝을 짓고 `event_journal.market_id` 를 보지 않는다. V36 이전 이벤트 행은 `market_id` 가 NULL 이라(9-14) 마켓 술어를 걸면 레거시 명령이 전부 구멍으로 보인다. 짝 조회는 `idx_evt_src`(V1__init.sql:616), 마켓 범위는 `idx_cmd_market`(V1__init.sql:585)로 끝난다. 새 인덱스가 필요 없다.
- `legacy_bound` 는 바닥이다. 그 이하 명령에 이벤트가 전부 있다는 것을 V40 이 적용 때 확인하고 어긋나면 멈춘다(3-1(2), V40__command_seq_band.sql:59-66). 그래서 탐색을 그 위에서 시작한다. V36 이후 이벤트가 없는 조용한 마켓도 경계가 `legacy_bound` 아래로 내려가지 않는다.
- 대역 채번 뒤에도 정의가 같다. 마켓 m 의 대역 행은 레거시 행보다 항상 크므로 `c.market_id = m AND c.seq > from(m)` 한 술어가 커트오버 경계를 넘는다(2-3(1)).

### 5-3. 크래시 창 재적재는 마켓별 seq 큐를 (created_at, seq) 머리 키로 병합한다

**이것이 이 판에서 가장 조용한 비가역 결함이고, 심사가 지적한 치명 결함이다.**

`EngineRecovery`(EngineRecovery.java:201-226)는 재생이 낸 pending 을 `source_cmd_seq` 오름차순·연속이라는 전제로 잘라 정산 sink 에 넘긴다. 그 sink 가 `SettlementService.settleAccepted` 를 타고 `SELECT reserve_funds(...)`(SettlementService.java:324-327)를 부른다. 바로 위 주석이 못박는다(SettlementService.java:323). "잔고 부족(23514)은 여기서 배치 전체를 롤백시킨다. 클래스 주석의 fail-fast 계약". 그리고 `chk_balance_nonneg CHECK (balance >= 0 OR allow_negative)`(V1:119)가 계정마다 하드 제약이다.

곧 **예약은 접수가 아니라 정산에서 일어난다.** 접수 경로(`EngineBridge.submit` → `CommandJournalAppender.appendInTx`)는 `command_journal` 과 `leader_lease` 만 건드린다.

대역 파생에서 `seq` 오름차순은 시간 순서가 아니라 마켓 id 순서다. 크래시 창 안에서 사용자가 마켓 12 에서 팔아 생긴 KRW 로 마켓 2 에서 샀다면, 재적재가 마켓 2 를 먼저 돌려 잔고 부족 23514 를 낸다. 배치 전체 롤백에 라이터 fail-fast 이고, 명령은 이미 저널에 커밋돼 있으므로 재기동해도 같은 자리에서 같은 순서로 다시 죽는다. **영구 벽돌이다.**

**해결.**

```
마켓 큐      q(m) = 마켓 m 의 크래시 창 배치, seq 오름차순     -- 마켓별 tail 재생의 방출 순서 그대로
병합 키      q(m) 머리 배치의 (created_at, seq)
재적재 순서  매 단계 병합 키가 가장 작은 머리 배치 하나를 꺼내 reappendSink 에 넘긴다
```

근거를 적는다.

- `created_at` 은 `now()` 기본값이므로 append 트랜잭션의 **시작 시각**이고 단일 프라이머리의 단일 시계다(V1:582). 그 시계가 뒤로 가지 않는다는 보장은 없다(9-17). 트랜잭션은 접수 락을 잡은 뒤에 시작한다(EngineBridge.java:116, 139).
- 인과 사슬이 보존된다. 명령 X 의 ACK 를 보고 명령 Y 를 낸다면, `created_at(Y) = start(Y) > commit(X) > start(X) = created_at(X)` 다. 곧 사용자가 만든 모든 자금 의존은 이 키에서 순서가 맞다.
- **마켓 안 순서는 키가 아니라 큐가 정한다.** 시계가 단조면 마켓 안에서 `created_at` 순서가 seq 순서와 같다. 마켓별 락이 append 를 통째로 직렬화하기 때문이다. DB 서버 시계가 뒤로 가면 둘이 역전될 수 있다. PostgreSQL 프라이머리 전환(ADR 0024)은 그 한 경우다. 전역 `(created_at, seq)` 안정 정렬은 그때 같은 마켓의 명령 순서를 뒤집는다. 마켓 안 순서는 북 결정론과 예약 순서의 계약이라 어떤 시계 조건에서도 깨면 안 된다. 병합은 큐 안 순서를 건드리지 않으므로 그것을 구조로 지킨다.
- **마켓 안 역전은 멈추지 않고 센다.** 같은 마켓에서 앞 배치보다 `created_at` 이 이른 배치를 만나면 `exchange.recovery.reinject.created_at_inversions` 에 더하고 WARN 로그를 남긴다. 역전된 배치가 머리에 있는 동안 같은 마켓의 뒤 배치가 함께 밀리므로, 그것에 의존한 다른 마켓 배치가 먼저 나갈 수 있다. 그 결과는 23514 fail-fast 로 드러나고 무음으로 가지 않는다. 역전만으로 기동을 세우면 교차 마켓 의존이 없는 창에서도 복구가 막힌다.
- **2·3단계에서 DB 서버 시계가 뒤로 가지 않은 구간이면 병합 순서가 전역 seq 순서와 같다.** 전역 락이 append 를 통째로 직렬화하기 때문이다. 곧 이 변경은 커트오버 전에 '오늘과 같은 결과' 로 검증할 수 있고 2단계에 넣는 이유가 그것이다. 4단계부터는 채번이 전역이어도 마켓 사이에서 트랜잭션 시작 순서와 카운터 획득 순서가 갈려 두 순서가 다르다. 그래서 회귀 시험은 마켓 안 순서 무변만 단언한다.

**구현.** `ReplayResult` 에 `pendingBatches()` 를 더한다. `PendingBatch(long seq, OffsetDateTime createdAt, List<EngineEvent> events)` 다. `EngineRecovery` 가 마켓별 2단계 재생이 낸 배치를 마켓별 큐로 받아 위 병합 순서로 배치마다 `reappendSink.accept` 한다. `RENEW_EVENT_INTERVAL` 판정은 배치 경계에서 한다. 지금의 "연속 같은 `sourceCmdSeq` 를 훑어 자른다" 루프(EngineRecovery.java:201-226)가 사라진다. 크래시 창은 이미 메모리에 전량 올라와 있으므로(`List<EngineEvent> pending`) 병합에 추가 조회가 없고 새 인덱스도 필요 없다.

**정직하게 남는 것.** 이 축이 복원하는 것은 '오늘의 보장' 이지 '원래 실행 순서' 가 아니다. 원래의 마켓 사이 정산 순서는 12개 라이터 스레드의 실제 인터리빙이고 어디에도 기록돼 있지 않다. 같은 사용자가 잔고를 초과해 **동시에** 낸 명령 둘이 같은 크래시 창에 들어가면 오늘도 이후도 벽돌이다. 그것은 게이트웨이 사전 예약 검증(M1-4)의 몫이고 이 판이 닫지 않는다. 9절에 등재한다.

**회귀 시험.** `CrashWindowReinjectionOrderTest`. (a) 병합이 마켓 안 seq 순서를 바꾸지 않음을 단언한다. 전역 seq 순서와의 일치는 단언하지 않는다(2·3단계에서 시계 역행이 없는 구간 한정). (b) 마켓 12 의 매도 체결과 마켓 2 의 매수가 같은 크래시 창에 있고 잔고가 딱 맞는 저널을 합성해, seq 축 재적재는 23514 로 죽고 병합 순서는 통과함을 단언한다. (c) 마켓 안 `created_at` 역전을 합성해 seq 순서가 유지되고 역전 계수가 1 임을 단언한다.

### 5-4. 나머지 복구 경로

- **1단계(안정 구간).** 마켓마다 `bound(m)` 까지 대조만 한다. 리더 자격이 필요 없다. 여기서 pending 이 나오면 원자 커밋 전제가 깨진 것이므로 종전대로 예외다.
- **2단계(크래시 창).** 마켓마다 `head(m) = max(seq) FROM command_journal WHERE market_id = m` 으로 상한한다. 리더 자격을 얻고 그 사이 늘어난 `bound(m)` 구간 대조까지 마친 뒤 모든 마켓의 `head(m)` 을 한 번 읽어 고정하고, 도는 중에 다시 읽지 않는다. 1단계 `bound(m)` 의 구멍 탐색과 head 대체값은 한 SQL 문장에서 구한다(`docs/contracts/백엔드_DB_설계.md` §5.5-a). 상한을 잡아 두면 루프가 유한하게 끝나고, 리스를 잃었다면 이어지는 재적재가 fencing 으로 거부된다는 성질이 그대로다.
- **tail 대조.** ADR 0023 은 전역 경계의 2 단계 방출에 크래시 창과 경계 위 이미 물질화된 명령이 섞이고, 후자가 payload 대조 없이 멱등 스킵으로 넘어가는 것을 한계로 남기고 이 판의 마켓 벡터로 넘겼다(ADR 0023 영향). 마켓 안에서는 라이터 하나가 seq 순으로 정산하므로 bound(m) 위에 그 마켓의 물질화된 명령이 남지 않고, 마켓별 2단계 방출은 크래시 창뿐이다. 멱등 스킵은 안전망으로 남긴다. 논증이므로 2단계 시험에서 마켓별 2단계 방출 중 이벤트가 이미 있는 명령이 0 건임을 단언한다.
- **리스 예산.** 마켓 수만큼 늘어난 재생 구간이 TTL 안에 들어오는지 다시 따진다. `RENEW_EVENT_INTERVAL`(10,000) 판정을 배치 경계마다 도는 것은 그대로 두되, 마켓 순회가 아니라 병합된 배치 순회이므로 renew 지점이 자연히 시간 순으로 흩어진다.
- **갭 회수.** `JournalCatchUpSource` 는 이미 마켓 조건을 갖고 있다(JournalCatchUpSource.java:43-45). 커서만 마켓별로 바뀐다.
- **스냅샷.** 세트 개념을 버린다. `loadLatestAdoptablePerMarket()` 이 마켓마다 독립으로 최신 채택 가능본을 고르고, 한 마켓이 폐기되면 그 마켓만 0 으로 폴백한다. `MatchingEngineImpl.loadMarketSnapshot` 의 fresh 가드도 마켓 단위다.
- **승격 생략.** `PromotionCoordinator.replayOnce`(PromotionCoordinator.java:307-331)의 `current.lastAppliedSeq() >= head`(PromotionCoordinator.java:318)를 '모든 마켓에서 커서가 그 마켓 head 에 닿았다' 로 바꾼다. head 에는 있는데 엔진에 없는 마켓은 닿지 않은 것으로 본다. 한 마켓만 닿은 상태에서 생략이 걸리면 재생이 안 된 마켓 위에서 접수가 열린다.
- **팔로워.** 불변식 M 을 '모든 마켓 m 에서 seq 가 `cursor(m)` 이하인 그 마켓 명령은 전부 이벤트가 있다' 로 다시 쓴다. 커서가 매 틱 새로 읽은 그 마켓의 `bound(m)` 까지만 전진하기 때문에 성립한다. '커서가 체크포인트를 넘지 않는다' 는 체크포인트 정의상 늘 참이라 승격 생략의 근거가 되지 못한다(`docs/contracts/백엔드_DB_설계.md` §5.5-a). lag 는 마켓별 격차의 최대와 합 둘을 낸다. 합만 내면 한 마켓의 정체가 열한 마켓의 진행에 묻힌다. `Math.max(0, ...)` 클램프는 마켓 안에서는 발화할 수 없는 값이므로 발화하면 ERROR 로그를 남긴다.

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

`lib.ps1:378` 의 `$rpo = $cmdGap + $evtGap + $ordersLost` 는 무변경이다. C05 위반 행수가 계속 0 이면 되기 때문이다.

### 6-2. C22_FENCING_MONOTONIC (CRITICAL, AUTHORITATIVE_ONLY). 시간 축 팔과 마켓 안 팔

지금: `lag(fencing_token) OVER (ORDER BY seq)`(InvariantCheck.java:249-256).

이후: 두 팔의 위반 행 수 합.

```
시간 축 팔   lag(fencing_token) OVER (ORDER BY created_at, seq)
마켓 안 팔   lag(fencing_token) OVER (PARTITION BY market_id ORDER BY seq)
```

- 시간 축 팔을 마켓 안 팔로 대체하지 않는다. 마켓 안 팔만 두면 검사가 보증하는 것이 '마켓 안의 epoch 단조' 로 줄고, 마켓 사이 split-brain 을 이 백스톱이 못 본다(10-8). 마켓 안 팔은 더하는 것이다.
- 시간 축 팔이 맞다는 논증. append 는 `INSERT ... SELECT FROM leader_lease l WHERE l.holder = ? AND l.epoch = ? ... FOR KEY SHARE OF l`(CommandJournalAppender.java:34-43)이고 커밋까지 리스 행을 쥔다. holder·epoch 가 `uq_leader_lease_fencing_key` 로 키 열이므로 새 epoch 를 쓰는 승격 UPDATE 는 그 append 가 끝날 때까지 기다린다(4-4). 승격이 먼저 커밋됐으면 머리말의 리더 검증(V39__append_prologue.sql:47)이나 그 SELECT 가 거부해 롤백된다(CommandJournalAppender.java:240-244). 곧 커밋된 구 epoch 행은 전부 승격 커밋 전에 커밋됐고, 새 리더는 승격 커밋 뒤에 자기 epoch 를 알고 트랜잭션을 연다. `created_at` 은 트랜잭션 시작 시각이므로 `created_at(구) < commit(구) < commit(승격) < created_at(신)` 이다. DB 서버 시계가 뒤로 가지 않으면 두 집합이 엄격히 갈린다. 둘째 부등호 `commit(구) < commit(승격)` 가 인덱스에 기댄다. 인덱스가 없으면 승격이 쓰기를 기다리지 않는다(ADR 0026 S4).
- 마켓 안 팔을 더하는 이유. 시간 축 팔은 시계 역행이 없음을 전제한다. DB 서버 시계가 뒤로 가면 역전이 가능하고, PostgreSQL 프라이머리 전환(ADR 0024)으로 `created_at` 을 찍는 시계가 바뀌어 두 노드 시계 차만큼 역전되는 것은 그 한 경우다. 그러면 시간 축 팔이 진짜 역전을 가릴 수 있다. 마켓 안 팔은 시계를 보지 않으므로 그렇게 가려진 마켓 안 역전을 잡는다. 시간 축 팔의 거짓 양성은 합산에 그대로 남는다(9-17). 마켓 안 팔이 맞다는 논증은 이렇다. 카운터 행은 채번부터 커밋까지 잠기므로(4-4 (2)) 커밋된 행의 seq 는 커밋 순서로 커진다. 구 epoch 행은 승격 커밋 전에 커밋되고 새 epoch 행은 승격 커밋 뒤에 채번하므로 마켓 안 seq 순서에서 epoch 가 줄지 않는다. 마켓 사이는 시간 축 팔이 본다.
- 비용. 시간 축 팔은 `created_at` 에 인덱스가 없어 정렬이 든다. 권위 run 한정이고 정지 상태에서 도는 검사다. 3단계에서 실측 시간을 기록한다. 인덱스를 더하지 않는 이유는 그것이 append 핫패스의 유지 비용이기 때문이다. 마켓 안 팔은 `idx_cmd_market (market_id, seq)`(V1:585) 순서로 읽는다.
- **커트오버 전에 DB 서버 시계가 뒤로 가지 않은 구간에서 오늘과 같은 값(0)을 낸다.** 3단계 시점에는 전역 접수 락이 append 를 통째로 직렬화해 시계 역행이 없으면 `created_at` 순서가 `seq` 순서와 같다. 4단계 뒤에는 마켓 사이에서 두 순서가 갈리지만 위 논증의 epoch 분리는 시계 역행이 없으면 그대로라 값은 0 이다. 시계 역행(프라이머리 전환 포함) 뒤 시간 축 팔의 거짓 양성은 9-17 이다. 마켓 안 팔은 전역 조밀 축에서 지금 술어의 부분열이라 지금 술어가 0 이면 0 이다.

### 6-3. C23_COUNTER_SEQ_CONSISTENCY (CRITICAL, AUTHORITATIVE_ONLY). 명령 축을 마켓별로

지금 명령 축은 전역 `journal_counters('command_seq').next_value` 대 전역 `max(seq)` 다(InvariantCheck.java:266-271).

이후 세 팔이다.

1. 레거시 팔. 전역 `command_seq` 행의 `next_value` 대 `max(seq) WHERE seq < band_span`. 커트오버 뒤 이 행은 호출자가 없어지지만 값이 얼어붙어 계속 성립한다. V36 이 `event_seq` 전역 행에 한 판단과 같다. 행을 지우지 않는다(복원 리시드의 기준행이다).
2. 대역 팔. `market_journal_counters(name='command_seq').next_value` 대 그 마켓의 `max(seq) - market_id * band_span`.
3. 이벤트 팔. 무변경.

### 6-4. 신설 둘. 단계가 다르다

- **C27_CMD_DEFAULT_PARTITION (CRITICAL, SCHEDULED). 3단계.** `SELECT count(*) FROM command_journal_default`. C15 를 통째로 올리지 않는 이유는 그것이 WARN 이고 다섯 표를 합산하기 때문이다(InvariantCheck.java:200-206). 통째로 올리면 다른 네 표의 정상 운영까지 CRITICAL 이 된다. 명령 축만 떼어 CRITICAL 로 세운다. C15 는 나머지 넷으로 남는다.
- **C26_CMD_BAND_HEADROOM (HIGH, SCHEDULED). 7단계.** `v_journal_band_headroom` 에서 잔여 비율 10퍼센트 미만인 마켓 수. 술어가 읽는 뷰를 7단계의 `band_headroom` 마이그레이션(3-2)이 만들므로 3단계에 둘 수 없다. 세 벌에 더하는 것도 7단계다. **정직하게 적는다. 10^12 대역에서 이 검사는 사실상 공허하다**(단일 마켓 집중에서도 240년). 남기는 이유는 둘이다. 비용이 0 이고, 소진이 비가역이다(PK 가 seq 재사용을 막고 append-only 트리거가 백필을 막으므로 운영이 카운터를 되돌릴 수 없다). 실제 방어선은 이 검사가 아니라 `next_market_command_seq` 의 `max_band_index` 예외와 `markets` INSERT 트리거의 거부다.

### 6-5. 하네스 스테일 쓰기 판정. 위치 축을 쓰지 않는다

이 판을 기다리지 않고 2026-09-12 에 닫혔다. 판정은 epoch 기준 증분이다(lib.ps1:534-573). 이 판의 어느 단계에도 할 일이 없다.

- 주입 직후와 관찰 후에 `command_journal WHERE fencing_token = 구 epoch` 와 `event_journal WHERE fencing_epoch = 구 epoch` 의 행 수를 각각 재고(`Get-EpochWriteCounts`) 그 차를 스테일 쓰기로 본다(`Get-StaleWriteDelta`).
- 위치 워터마크(`seq > W`)를 쓰지 않는 이유. 대역 파생 축에서는 워터마크가 가장 높은 대역의 값이라 낮은 대역 마켓의 구 epoch 쓰기가 워터마크 아래로 빠져 0 으로 세어진다. 이벤트 축은 V36 부터 그 상태였고 명령 축은 래치 뒤 같은 모양이 된다. 이미 물질화된 명령을 구 리더가 다시 적재하는 경우도 위치 축은 구조적으로 못 잡는다.
- 축이 없으므로 명령 축이 대역화돼도 술어가 그대로 성립한다.
- 기준선이 0 이면 술어가 틀렸거나 부하가 안 실린 것이므로 주행을 세운다(`Test-StaleWriteBaseline`, §22 양성 증거 축).
- V36 부터 2026-09-12 까지 회차의 E5·E7 split-brain 반증 PASS 는 이벤트 축 판정이 fail-open 인 채로 돌았다(9-11).

### 6-6. 관측·문서

- `HaMonitoringController.status()`: `command_watermark` 스칼라를 `command_watermarks` 배열로. `apply_lag` 는 마켓별 (명령 head - 물질화 경계)의 **최대**를 내고 `apply_lag_total` 을 합으로 따로 낸다. 합만 내면 한 마켓의 정체가 묻힌다.
- 좀비 리더 판별식(`harness/evidence/gateoff-20260903-2vcpu/README.md:228,238`): "`lease_until` 은 전진하는데 **어느 마켓의** head 도 전진하지 않으면 좀비 리더, **일부 마켓만** 전진하지 않으면 부분 정체 경보". 지금은 부분 정체가 아예 안 보이므로 이것은 상실이 아니라 이득이다.
- Grafana `02-recovery.json` 의 `replay_tail_commands`: 마켓별 (명령 head - 스냅샷 커서)의 합.
- `web/lib/admin/httpAdmin.ts:1008-1015` 의 노드 `seq` 표시: 마켓별 최대의 최대로. 표시용이라 정확성 사고는 아니다.
- `docs/contracts/백엔드_API_설계.md` 의 "`seq` 는 gapless" 문구와 "§6-C `last_applied_seq` 와 동일 축" 문구 둘 다 거짓이 된다. 값은 그대로 쓸 수 있다(프론트가 소비하지 않고 커서 축이 `(created_at, id)`·`trade_id` 다). 문서를 고친다.
- 데모 대본 네 자리의 C05 SQL 과 "명령 저널 gap 도 0행" 대사.
- `하네스_검증설계.md` R0(워터마크 선캡처), R5 분류 C.

---

## 7. 단계 계획

되돌릴 수 없는 것을 검증 뒤로 미룬다. 중간에 멈춰도 시스템이 살아 있는 것이 이 순서의 요점이다. ADR 0021 의 규율을 그대로 쓴다.

### 0단계. 계측과 착수 게이트 (되돌릴 수 있다. 계측만 더한다)

재기 전에는 아무것도 청구하지 않는다. 넷을 잰다.

- **(a) 동시 커밋 확장성.** 세션 N 개가 각자 작은 트랜잭션을 커밋하는 사다리를 N=1,2,4,8,12,24 로 돌려 초당 커밋 수와 p95 커밋 지연을 낸다. `pg_test_fsync` 의 311.9회/s 는 **겹치지 않는 직렬 플러시**의 값이고 곧 지연 3.206밀리초의 다른 표현이다. 동시 커밋이 하나의 플러시를 공유하는지(그룹 커밋) 아닌지가 이 판의 처리량 이득 전부를 가른다. 8-2 참조.
- **(b) 채번 행 보유 구간.** append 트랜잭션의 '채번부터 커밋까지' 나노초. 오늘 이 행의 **대기**는 항상 0 에 가깝다(전역 락이 앞에서 직렬화하고 실측이 '행 잠금 3.8퍼센트' 로 이미 소거했다). 잴 수 있는 것은 보유 구간이고, 그 역수가 곧 '락만 가르고 채번을 그대로 두었을 때의 마켓당 상한' 이다.
- **(c) 갭 회수 왕복.** `SingleWriterLoop` 에 `catchUpCalls`·`catchUpNanos` 를 `market` 태그로 더한다. 예상은 명령당 1 - 1/12 = 91.7퍼센트이고 **계산값이지 실측이 아니다.** 이 수치가 없으면 fast path 복원 이득을 청구할 수 없다. 부수로 '라이터 sink 점유 2.146' 이 얼마나 과소 보고였는지가 나온다.
- **(d) 제공 부하 준비.** 지금 `calibrate.js` 150/s 에 접수 128 + 수용 거절 40 이라 요청 쪽이 이미 포화다. 새 천장을 재려면 제공 부하가 600/s 는 돼야 하고 시드 계정과 k6 VU 를 함께 늘려야 한다. 올리지 않고 재면 '개선했는데 수치가 그대로' 가 나온다.

**착수 게이트.** (a)는 저장소가 동시 커밋을 흡수하는지를 가른다. 'N=12 의 초당 커밋이 N=1 의 3배 미만이면 직렬 장치' 라는 배수 문턱은 쓰지 않는다. 단일 세션 커밋이 빠르면 분모가 커져 흡수하는 저장소도 문턱 아래로 나온다(0단계 실측 결과 (1), 배수 2.47). 로컬 판정은 기록돼 있다. 단일 세션 749.8 커밋/s 가 명령 374/s 로 관측 천장 128/s 의 2.9배이고 8에서 24 세션 구간이 사실상 선형이라 저장소는 직렬 장치가 아니며 8절의 분기 A 는 없다. 배포 대상 저장소에서 같은 사다리를 다시 재고, 흡수하지 않으면 비가역 단계(6단계)를 처리량 외 산출물만으로 살 것인지를 **명시적으로 고른다.** 그 재측정의 판정 문턱은 주행 전에 고정한다(9-1, 6단계 래치 전 확인 7). 처리량 외의 산출물(파티션 시한 제거, 마켓별 체크포인트, 대역 채번 전 스칼라 바닥 제거, 마켓별 가용성)은 5단계까지에서 전부 나오고 전부 가역이다.

**멈추면.** 오늘 그대로다. 되돌리기는 배포 롤백이다.

### 1단계. V40 (되돌릴 수 있다. 채번 경로를 바꾸지 않는다)

3-1 전부. **처리량이 오르기 전에 넣는다.**

2026-09-13 작성. 증거는 `CommandSeqBandMigrationIntegrationTest` 다. 대역 명세, 빈 DB 의 축 경계 0, 레거시 DB 의 경계 박제와 이벤트 없는 명령에서의 정지, 꼬리 잎 경로와 꼬리 상한이 첫 대역 잎 하한과 맞물림, 마켓 생성 트리거의 잎과 카운터, 대역 밖 id 거부, 파생 seq 와 잎 경로, 잎 없는 마켓의 채번 거부, `event_seq` 대역 상한 불변, 명세 없는 카운터 거부를 단언한다. 12마켓 카오스 스택에서 V40 이 적용된 채 E6·E7 이 PASS 다(`harness/evidence/chaos-runs/E6-20260913-130519.json`, `E7-20260913-130723.json`). 두 회차는 `SET LOCAL` 타임아웃 해제 두 줄을 넣기 전 V40 본문으로 돌았다. 판정 파일은 미커밋 경로를 개수(`code.uncommittedPaths` 2)로만 남기고, 그 시각 `server`·`harness` 아래 미커밋 경로가 V40 마이그레이션과 그 테스트 둘이었다는 것은 작업 기록 기준이다. 그 스택은 소유자 계정과 dev 프로파일로 붙어 `app_role` 권한과 prod 타임아웃은 검증하지 않는다.

**멈추면.** 오늘 그대로 살아 있고 **17.4일짜리 파티션 시한 하나가 제거된 상태다.** 이 판을 접어도 이 단계는 남긴다.

되돌리기: 파티션 DROP(비어 있음), 함수 둘 복귀, 시드 행 DELETE, 표 둘 DROP.

### 2단계. 소비측·재생·복구의 축 이전 (되돌릴 수 있다. 표면이 가장 넓다)

4-5 와 5절 전부. 채번도 락도 아직 전역이다.

**시험 조건: 전역 조밀 축에서 결과가 오늘과 같다.** 결과는 최종 북과 마켓마다 적용된 명령 집합이다. 경계 값은 달라도 된다(5-1).

- 마켓별 커서는 전역 seq 에서도 그 마켓 명령에 대해 단조 증가하므로 중복 판정이 바뀌지 않는다.
- 갭 판정 빈도도 바뀌지 않는다(여전히 성기다).
- 크래시 창 병합은 마켓 안 seq 순서를 바꾸지 않는다(5-3). 시험 (a)는 이것만 단언한다. 병합이 전역 seq 순서와 같다는 것은 전역 접수 락이 있는 2·3단계의 시계 역행이 없는 구간에서만 참이고 4단계부터는 보장되지 않는다.
- 마켓별 경계는 전역 연속 경계와 값이 다를 수 있다. 한 마켓의 구멍은 전역 경계를 누르지만 다른 마켓의 경계는 누르지 않는다(5-2). 관측 가능한 차이는 그 하나이고, 어느 마켓의 경계도 그 마켓의 미물질화 명령을 넘지 않는다.

`SnapshotEquivalenceIntegrationTest` 를 확장해 4단계 shadow 가 닫지 못한 운영 축 다섯을 덮는다. 상한 있는 마켓별 재생, 스냅샷 시작점에서 이어붙이는 tail 재생, 마켓 둘 이상이 동시에 크래시 창을 가진 재적재, 라이브 라이터가 도는 중의 팔로워 따라잡기, 승격 생략 판정(마켓 하나만 head 에 닿은 상태에서 생략이 걸리지 않아야 한다). 대역 채번 뒤 `installFrom` 과 지연 생성 샤드가 낮은 대역 명령을 버리지 않음을 여기서 반드시 단언한다(1-3(3)). 그것을 잡는 상시 감사가 없다.

**멈추면.** 오늘 그대로이고 1-3 의 (2)(3)이 대역 채번 전에 닫힌 상태다.

### 3단계. 감사·관측 축 이전 (되돌릴 수 있다)

6절 전부. 세 파일 + 하네스 + 런북 + 대본.

**시험 조건: 커트오버 전에 오늘과 똑같은 값을 낸다.** C05 대역 항 0행, C22 시간 축 팔과 마켓 안 팔 0건, C23 대역 팔 대상 없음, C27 0건. 같은 값이 안 나오면 술어가 틀린 것이고 여기서 멈춘다. C22 시간 축 팔의 대조는 시계 역행(PostgreSQL 프라이머리 전환 포함)을 겪지 않은 DB 에서 한다. 역행을 겪은 DB 에서는 술어가 맞아도 0 이 아닐 수 있다(6-2, 9-17). 이 순서를 뒤집으면 첫 페일오버 실험에서 R5 분류 C 가 정상 시스템의 서비스 재개를 자동으로 금지한다. C26 은 이 단계에 없다(6-4).

스테일 쓰기 판정은 이 단계와 무관하다. 축 없는 epoch 증분이라 이미 fail-closed 다(6-5).

**멈추면.** 오늘 그대로이고 판정 술어 세 벌이 대역 행에서도 오늘과 같은 값을 내도록 바뀐 상태다. 대역 행이 실재하지 않는 시점이라 그 값은 아직 실측으로 보이지 않았다. 시계 역행(PostgreSQL 프라이머리 전환 포함) 뒤 시간 축 팔의 거짓 양성은 남는다(9-17).

### 4단계. 접수 락 분할 (되돌릴 수 있다)

4-1 부터 4-4 까지. 멱등 조회의 `ORDER BY created_at, seq` 포함. 그 조회가 V39 함수 안이라 함수 본문을 바꾸는 `append_prologue_created_at_order` 마이그레이션이 붙고, CREATE OR REPLACE 로 되돌릴 수 있다. append 전용 풀 크기와 `InflightReserves` 락 단위(4-1)는 아래 B4 측정 전에 넣는다. 넣지 않으면 아래 평평함이 채번 행이 아니라 그 둘을 잰다.

**이 단계에서 처리량이 채번 분할만큼 오르지 않는다.** 채번이 아직 전역이라 12개 트랜잭션이 `journal_counters` 행 잠금에서 다시 직렬화된다. V36 격리 실측으로 12 라이터 전역 카운터가 43.5/s 였다. ADR 0021 3단계와 같은 성질이다. 다만 이득이 0 이라고 단정하지 않는다. V39 가 채번을 머리말 끝에 두므로(V39__append_prologue.sql:47-75) 그 앞의 리더 검증, 어드바이저리 락, 멱등 조회는 락을 가르면 마켓 사이에서 겹친다. 목적은 이득이 아니라 이득의 전제를 까는 것이고, 커트오버 단계를 최대한 작게 만드는 것이다.

**그 평평함 자체가 증거다.** 여기서 접수가 채번 분할 몫만큼 오르지 않는 것이 "락 분할은 채번 분할의 종속 결과" 라는 명제의 실측 증거이고, 5단계와 합치면 그 증거를 잃는다.

**판정 문턱. 주행 전에 고정하고 주행 뒤 고치지 않는다.**

B0 는 전역 접수 락, B4 는 이 단계를 모두 넣은 상태다. 같은 날, 같은 스택, 제시 부하 600/s(0단계 (d))의 `harness/scale/roundtrip_probe.py` 산출물끼리만 비교한다. 같은 요청에서 날짜를 넘어 접수가 1.62배 갈렸다. 같은 날 재주행 차는 240/s 에서 0.08퍼센트였고 600/s 의 재현성은 재지 않았다(`harness/evidence/stage0c-20260912/README.md` §2).

양성 축. 하나라도 빠지면 그 칸은 판정하지 않는다(NOT RUN).

- B0 접수 락 보유 점유 0.9 이상. 포화하지 않은 칸의 비는 제시 부하가 정한 값이라 아무것도 판정하지 않는다. 같은 스택에서 240/s 가 0.508, 600/s 가 0.906 이었다(같은 증거 `probe.json`, `probe_saturated.json`).
- B4 마켓별 접수 락 보유 점유 합 1.0 초과. append 가 실제로 겹쳤다는 증거다.
- B4 카운터 행 잠금 표본 채집 5회 이상(15초 간격, `pg_locks`·`pg_blocking_pids` 를 `append_command_prologue` 질의에 맞춘 것).
- 칸마다 12마켓 각 접수 0 초과, `k6_rc` 0, fsync 키 존재, `hikaricp_connections_pending{pool="exchange-append"}` 0.

문턱.

- **T1. 접수 비 B4/B0 가 1.15 이하면 평평이다.** 12 라이터가 전역 카운터 하나를 나눠 쓴 격리 실측이 1 라이터 대비 1.096배였다(1-2 표, 43.5/39.7). 격리 시험은 8-4 에서 예고의 19.7퍼센트만 맞았으므로 그 값은 하한 참고용이다. 1.15 는 그 위의 여유다.
- **T2. 커밋 동시성 `sum(rate(exchange_journal_commit_nanos_total))/1e9` 가 1.2 이하.** B0 는 접수 락이 커밋을 감싸 구조적으로 1.0 이하다. B4 는 카운터 행 잠금이 커밋까지 유지되므로 커밋 창이 겹치는 것은 채번하지 않는 멱등 재전송 커밋과 계측 경계뿐이다. 600/s 에서 커넥션 획득과 반납이 각 0.006밀리초로 커밋 1.154밀리초의 1퍼센트 아래였다(`probe_saturated.json`). 0.2 는 그 여유다.
- **600/s 에서 T2 의 '1.2 이하' 는 판정력이 없다.** 조건은 둘이다. 카운터 행이 커밋을 직렬화하는 칸이고, 커밋 1회가 B0 값에 머문다. 그 조건에서 제시 부하 606/s 에 커밋 1.154밀리초를 곱한 산술 상한이 0.70 이라 1.2 를 넘을 수 없다. 그래서 그 칸의 1.2 이하는 N/A 로 적고 PASS 로 세지 않는다. 같은 조건에서 판정력이 생기는 제시 부하는 1.2 를 커밋 1회로 나눈 약 1,040/s 부터다. 커밋이 겹치면 커밋 1회 자체가 는다. 0단계 (a)에서 동시 1 이 평균 1.334밀리초, 동시 4~24 가 6.236~6.486밀리초였다. 그래서 1.2 초과는 600/s 아래에서도 날 수 있고 그대로 T2 초과로 적는다.
- **T1 을 넘을 때.** 곧 반증이 아니다. 카운터 행이 재직렬화하면 B4 접수는 카운터 행 보유 1회의 역수를 넘지 못한다. 채번이 머리말 끝이고(V39__append_prologue.sql:75) 삽입과 커밋이 그 뒤에 오므로(CommandJournalAppender.java:232-238) 그 보유는 저널 삽입 1회와 커밋 1회의 합보다 짧을 수 없다. 곧 접수 비의 상한은 A = B0 락 보유 1회 / ((B4 저널삽입 1회 + B4 커밋 1회) x B0 락 보유 점유) 다. 삽입과 커밋은 B4 칸의 값을 쓴다. 그러면 커밋 1회가 B0 값에서 움직여도 상한이 선다. 1.15 < B4/B0 <= A 이고 카운터 행 대기 표본이 0 초과면 명제는 서고, 결과 행에 배수와 A 를 함께 적는다.
- **반증.** 양성 축을 모두 채웠는데 카운터 행 대기 표본이 0 이거나 B4/B0 가 A 를 넘으면 카운터 행이 재직렬화하지 않는다는 뜻이다. 1-2 의 논증이 틀린 것이고 5단계로 가지 않는다.
- 참고. `probe_saturated.json` 값(락 보유 1회 2.420, 저널삽입 1회 0.494, 커밋 1회 1.154, 점유 0.906)을 넣으면 A 는 1.62 이고, 같은 칸의 제시 부하 대 접수 비도 1.62 다. 접수는 제시 부하를 넘을 수 없으므로 B4 의 삽입과 커밋이 B0 값 근처면 600/s 에서 비 팔의 반증 폭이 0 이다. 그 칸의 반증은 카운터 행 대기 표본 0 하나가 맡는다.

**멈추면.** 오늘과 같은 처리량에 마켓별 접수 게이트가 붙은 상태다.

### 5단계. 장애 시험 (되돌릴 수 있다)

E2·E4·E5·E6·E7 을 돌린다. 승격·팔로워·스냅샷 회귀는 장애 때만 드러난다. `CrashWindowReinjectionOrderTest` 와 `MultiMarketConcurrentAppendTest` 가 여기 앞에 있어야 한다.

### 6단계. 커트오버 (배포는 되돌릴 수 있다. **래치는 되돌릴 수 없다**)

전환 스위치는 앱 설정이 아니라 DB 의 단방향 래치다. 배포와 래치 전환을 나눈다.

**6-a. 배포(래치 행이 없는 동안 되돌릴 수 있다).** `command_band_cutover_latch` 마이그레이션과, 6인자 판을 부르는 앱이다.

- 삽입 전용 표 `journal_cutover(name PK, latched_at, latched_by)`. UPDATE 와 DELETE 는 트리거가 거부한다. 행이 없으면 전역 채번, 있으면 대역 채번이다. 기본은 전역이다.
- 6인자 `append_command_prologue(..., p_market_id SMALLINT)` 가 채번 직전에 같은 트랜잭션에서 래치를 읽는다(4-3). 판정이 채번과 같은 트랜잭션, 같은 마켓 접수 락 안에 있으므로 한 마켓 안에서 래치 전 행이 모두 먼저 커밋되고 대역 행이 뒤따른다. 멱등 조회 정렬, 어드바이저리 락 키, VOLATILE 은 `append_prologue_created_at_order` 본문과 같다.
- 5인자 판은 래치가 있으면 예외를 낸다. 메시지에 '`fencing 위반`' 을 넣지 않는다. 그 문구만 강등 사유라(FencingErrors.java:13-19) 접수만 5xx 가 되고 리더십은 남는다(2-3(3)).
- 기동 때 래치 상태를 게이지와 INFO 로그로 낸다.
- 래치 전에는 채번이 오늘과 같다. 되돌리기는 새 마이그레이션으로 6인자 판과 표를 지우고 5인자 판을 `append_prologue_created_at_order` 본문으로 두는 것이며, 래치 행이 없을 때만 한다.

**왜 앱 설정이 아닌가.** 모드를 앱 속성으로 두면 낡은 설정으로 뜬 노드가 승격한 순간 대역 행 뒤에 전역 seq 를 쓴다. 그 값은 그 마켓 커서보다 낮아 엔진이 예외 없이 버린다(MatchingEngineImpl.java:23-25, SingleWriterLoop.java:234-237). 노드마다 설정이 달라도 같다. DB 래치는 모드를 채번 지점 한 곳에서 정하고 되돌릴 수 없게 한다.

**6-b. 래치 전환(되돌릴 수 없다).** 소유자 계정으로 `latch_command_band_cutover()` 를 부른다. PUBLIC 과 `app_role` 의 EXECUTE 는 회수한다. 첫 대역 행이 아니라 래치 행이 커밋되는 순간이 돌아올 수 없는 지점이다. 그 뒤에 전역으로 돌아가면 전역 카운터가 대역 값 아래에서 채번을 이어 마켓 커서 아래로 무음 폐기되거나, 대역 값 위에서 이어 전역 축이 결번투성이라 C05 레거시 항이 영영 성립하지 않는다.

래치 전 확인. 함수가 검사하는 것과 절차로 확인하는 것을 나눈다.

1. (함수) 모든 마켓의 대역 잎 `command_journal_b<K>` 와 꼬리 파티션이 실재한다.
2. (함수) `market_journal_counters` 에 마켓마다 `command_seq` 행이 있다.
3. (함수) `command_journal_default` 가 0행이다.
4. (절차) 3단계 술어가 오늘 값과 같다.
5. (절차) 5단계가 통과했다. 폐기용 DB 에서 트래픽 전에 래치를 건 대역 모드 장애 시험도 통과했다.
6. (절차) 모든 엔진 노드가 2~4단계 코드와 6인자 판을 부르는 앱으로 올라와 있다.
7. (절차) 0단계 (a)의 판정과 착수 결정이 기록돼 있고, 머리말 상태 줄의 게이트(클라우드 저장소 fsync 와 동시 커밋 재측정)를 통과했다. 2026-09-13 현재 클라우드 배포가 지원금 확정 대기라 이 항이 차단 상태다.

**래치 뒤 롤백 금지.** 래치 뒤에는 6인자 판을 부르는 앱만 접수할 수 있다. 그보다 이전 앱, 특히 2단계 이전 앱으로 되돌리면 5인자 판이 거부해 접수가 닫힌다. 무음 폐기가 아니라 fail-closed 다. 그러나 복구와 팔로워 경로는 채번 함수를 거치지 않아 DB 가 막지 못한다. 스칼라 커서 앱의 크래시 창 재적재는 대역 행을 seq 축으로 정산해 23514 벽돌로 간다(5-3). 그래서 래치 뒤 2단계 이전 앱으로의 롤백은 금지다.

**섞임 감사.** C28_CMD_LEGACY_AFTER_CUTOVER(CRITICAL, SCHEDULED)를 세 벌에 더한다. 술어는 마켓마다 대역 행의 최소 `created_at` 보다 늦은 레거시 행(`seq < band_span`) 수다. `latched_at` 과 비교하지 않는다. 래치 트랜잭션이 커밋되기 전에 시작해 래치를 못 본 정당한 전역 행이 `latched_at` 보다 늦은 `created_at` 을 가질 수 있기 때문이다. C28 은 마켓 안 역전(대역 행보다 늦은 레거시 행)만 센다. 대역 행이 없는 마켓의 레거시 행은 그 마켓 커서 아래로 폐기되지 않으므로 대상이 아니다. 시계 역행(PostgreSQL 프라이머리 전환 포함)은 이 술어에도 거짓 양성을 낼 수 있다(9-17).

되돌리기: 6-a 는 래치 행이 없을 때만. 6-b 는 없다.

### 7단계. 대역 여유 마이그레이션(3-2) + 스냅샷 훅 배선 (되돌릴 수 있다)

- `band_headroom` 마이그레이션의 대역 여유 뷰와 게이지. C26 신설과 알람 라우팅(6-4). C26 이 읽는 뷰가 이 단계에서 생기므로 세 벌에 C26 을 더하는 것도 이 단계다.
- `EngineConfig.snapshotCapture` 가 레거시 `singleWriterLoop` 빈에만 훅을 거는 것(EngineConfig.java:261-273)을 샤드 팩토리(EngineConfig.java:285-337)로 옮긴다. `setSnapshotHook` 호출부가 코드 전체에 그 한 줄뿐이고(EngineConfig.java:270) 라우터 샤드에는 없다. 곧 **지금 스냅샷이 한 건도 안 찍히고 복구가 항상 seq 0 전량 재생이다.** 샤드마다 `SnapshotCapture` 를 하나 두면 `seq - lastCapturedSeq >= commandInterval` 판정이 자연히 마켓 단위가 된다.
- **커트오버 뒤로 미루는 이유.** 배선하는 순간 처음 도는 코드다. 새 코드와 새 축을 같은 창에 겹치지 않게 한다. 실패해도 오늘의 동작(전량 재생)으로 떨어지는 폴백을 남긴다.

### 8단계. commit_delay 실측 (선택. 되돌릴 수 있다)

`wal-fsync-20260907` 이 "재지 않았다" 로 남긴 자리다. 그 문서의 유보 사유가 "커밋이 단일 라이터에서 직렬화되어 동시 커밋자가 적다" 였고, **6단계가 그 전제를 바꾼다.** `synchronous_commit=off` 와 다르다. 동기 커밋을 유지한 채 플러셔가 형제 트랜잭션을 모으도록 기다리게 하는 것이라 'fsync 후 ACK' 계약을 깨지 않는다. `commit_siblings` 와 함께 격자로 돈다. `ALTER SYSTEM RESET` 으로 되돌린다.

### 9단계. 이벤트 축 대역 확대 마이그레이션(3-3) (선택. 별건)

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

**전제 정정(2026-09-12).** 아래가 311.9회/s 를 "이 호스트의" 값으로 읽는데 그것이 호스트의
상수가 아니다. 같은 볼륨에 같은 명령을 다시 걸어 2026-09-12 에 2389.3회/s(건당 0.419밀리초)
가 나왔다. 7.7배다. 아래 산술의 방향과 그룹 커밋 논증은 그대로 유효하나, 절대 예산으로 든
플러시/s 는 회차마다 다시 재야 한다(기획안 한계 47, `harness/evidence/stage0c-20260912/`).

앞선 심사 둘이 같은 지적을 했고 옳다. `harness/evidence/wal-fsync-20260907/README.md` 가 이 호스트의 `fdatasync` 를 초당 311.9회(건당 3.206밀리초)로 쟀고, 명령 하나가 내구 커밋을 두 번 요구한다(저널 append 한 번, 정산 한 번). 오늘 132.12/s 는 264.2 플러시/s 이고 311.9 의 84.7퍼센트다. 이 값을 그대로 상한으로 읽으면 **명령 상한이 156/s** 이고 250/s 같은 예고는 성립하지 않는다.

그러나 그 산술에는 전제가 하나 숨어 있다. **311.9회/s 는 겹치지 않는 직렬 플러시의 값이고 곧 지연 3.206밀리초의 다른 표현이다.** `pg_test_fsync` 는 한 스레드가 쓰고 플러시하기를 반복한다. PostgreSQL 의 그룹 커밋은 한 번의 `fdatasync` 로 동시에 커밋 대기 중인 트랜잭션 여럿을 함께 내구화하므로, 동시 커밋자가 있으면 '초당 커밋' 이 '초당 플러시' 를 넘을 수 있다.

지금 이 배선이 그 조건을 어떻게 막고 있는지 적는다.

| 축 | 동시 커밋자 | 평균 동시성 (132/s x 3.206밀리초) |
|---|---|---|
| 저널 append | **구조적으로 1**. 전역 `submitLock` 이 append 를 통째로 직렬화한다 | 0.42 (그러나 상한이 1) |
| 정산 | 최대 12. 라이터가 열둘이다 | 0.42 |

곧 **저널 축은 그룹 커밋이 원리적으로 불가능한 상태이고, 접수 락 분할과 대역 채번(6단계 래치)이 함께 그 조건을 연다.** 락만 가른 4단계에서는 전역 카운터 행 잠금이 커밋까지 유지돼 커밋 창이 여전히 겹치지 않는다(1-2, 7절 4단계 T2). 정산 축은 겹칠 수 있으나 접수가 132/s 로 묶여 있어 겹칠 일이 별로 없다. 저장소가 동시 플러시를 흡수하는지 아닌지를 오늘 데이터로는 가를 수 없다. 그래서 0단계 (a)가 착수 게이트다.

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

이것들은 처리량 이득과 무관하게 남는다. 마지막 항목(7단계)을 빼면 5단계까지에서 전부 나온다.

- `command_journal` 파티션 17.4일 시한 제거(1단계).
- 한 마켓의 미물질화 구멍이 다른 마켓의 재생 상한을 누르지 않는다(2단계).
- 대역 채번 뒤 `installFrom` 과 지연 생성 샤드가 낮은 대역 명령을 버리는 경로를 래치 전에 닫는다(2단계).
- 판정 술어 세 벌이 대역 행에서도 오늘과 같은 값을 내도록 바뀐다(3단계). 시계 역행(PostgreSQL 프라이머리 전환 포함) 뒤 시간 축 팔의 거짓 양성은 남는다(9-17). 스테일 쓰기 판정의 fail-closed 는 이 판 밖에서 먼저 얻었다(6-5).
- 한 샤드 정지가 전 마켓 접수를 닫지 않는다(4단계).
- 부분 정체(마켓 일부만 멈춤)가 좀비 판별식에 처음 보인다(3단계).
- 스냅샷 부활로 RTO 가 전량 재생에서 tail 재생으로(7단계).

---

## 9. 위험과 미해결

정직하게 적는다.

1. **배포 대상 저장소의 동시 커밋 확장성이 미측정이다.** 로컬 저장소에서는 확장을 실측했다. 단일 세션 749.8 커밋/s, 24 세션 3,743.9 커밋/s 다(0단계 (1), `harness/evidence/stage0-20260911/README.md` (a)). 그러나 같은 볼륨의 플러시 속도가 회차 사이에 7.7배 움직였고(`harness/evidence/stage0c-20260912/README.md` §4) 클라우드 저장소는 한 번도 재지 않았다. 이 판의 처리량 이득 전부가 여기에 걸려 있으므로 로컬 실측을 배포 대상 값으로 옮기지 않는다. 6단계 래치 전환이 클라우드 저장소 재측정을 기다리는 이유다.
2. **크래시 창 재적재의 `(created_at, seq)` 병합 키는 오늘의 보장을 복원할 뿐이다.** 같은 사용자가 잔고를 초과해 동시에 낸 명령 둘이 같은 크래시 창에 들어가면 오늘도 이후도 23514 로 벽돌이다. 원래의 마켓 사이 정산 순서는 12 라이터의 실제 인터리빙이고 어디에도 기록돼 있지 않으므로 어떤 축으로도 재현할 수 없다. 이 구멍은 게이트웨이 사전 예약 검증(M1-4)의 몫이고 이 판이 닫지 않는다.
3. **`LoopFatalHandler` 가 여전히 한 샤드의 fatal 에서 노드 전체를 강등시킨다.** 곧 마켓별 게이트가 실제로 사는 경우는 '샤드가 멈췄는데 강등은 아직 안 온 창' 과 '접수 단계 예외(대역 초과 포함)' 뿐이다. 라이터 fatal 의 폭발 반경을 마켓으로 가두는 것은 별건이다.
4. **승격·강등의 단위는 노드로 남는다.** `acceptGate` 를 가르지 않았다.
5. **스냅샷은 지금 한 건도 안 찍힌다.** 7단계가 처음 도는 코드이고, 채택 게이트가 실동작하는 것도 처음이다. 시험 부담이 가장 큰 단계이며 폴백(전량 재생)을 남긴다.
6. **6단계의 래치 전환은 비가역이다.** 래치 전에는 6단계 배포까지 되돌릴 수 있다. 래치 뒤에 전역 채번으로 돌아가면 낮은 seq 가 마켓 커서 아래로 무음 폐기되거나 전역 축의 C05 원형이 영영 성립하지 않는다. 래치 뒤 2단계 이전 앱으로의 롤백은 금지다(7절 6단계).
7. **C26 은 10^12 대역에서 사실상 공허하다.** 6-4 에 이유와 실제 방어선을 적었다.
8. **파티션 15개의 전역 정렬 비용을 안 쟀다.** 점 조회와 마켓별 읽기는 프루닝되지만 C22 의 `ORDER BY created_at, seq` 는 전량 정렬이다. 3단계에서 실측 시간을 기록한다.
9. **`journal_band_spec` 조회가 정산 핫패스에 들어간다.** `next_market_journal_value` 가 일반화되면서 호출마다 2행짜리 표의 PK 조회가 붙는다. 1단계 배포 전후로 `exchange.writer.sink.nanos` 를 대조한다.
10. **제공 부하 600/s 를 낼 시드·VU 가 아직 없다.** 이것은 위험이 아니라 절차이고 0단계 (d)에서 준비한다. 올리지 않고 재면 '개선했는데 수치가 그대로' 가 나온다.
11. **이벤트 축 스테일 쓰기 판정이 V36 부터 2026-09-12 까지 fail-open 이었다(6-5).** 그 사이 회차의 E5·E7 split-brain 반증 PASS 는 소급 무효일 수 있다. 정본 한계로 등재한다. 판정은 지금 epoch 증분이라 닫혀 있다(lib.ps1:534-573).
12. **`legacy_bound` 박제는 'V36 이전 구간이 단일 라이터였다' 는 전제 위에 있다.** 1단계 배포 시 일회 질의로 확인하고, 0 이 아니면 거기서 멈춘다.
13. **교차 마켓 동일 coid 는 게이트웨이가 409 로 끊는다.** `idempotency_keys` 행이 있으면 요청 바디 해시 대조가 마켓이 다른 바디를 409 `duplicate_client_order_id` 로 거부한다(OrderGateway.java:93-94, 102-104, 261-265). 행이 없으면 저널 멱등이 replayed 를 돌려주지만 내용 대조를 한 적이 없으므로 같은 409 로 거부한다(OrderGateway.java:174-179). 곧 다른 마켓의 주문이 200 replayed 로 돌아가는 경로는 없다. 행이 없는 경우는 둘이다. 보존 기간 퍼지는 아직 배선되지 않았다(OrderGateway.java:173). 모호한 커밋 뒤의 고아 키 삭제는 오늘 도달한다. 커밋은 됐는데 `bridge.submit` 이 예외를 받으면 `journaled` 가 거짓으로 남고(OrderGateway.java:158-159) 키가 지워져(OrderGateway.java:200-207) 같은 키 재시도가 이 409 를 받는다. 이 분석은 NEW 경로(OrderGateway.java:158)의 것이다. 게이트웨이의 다른 `bridge.submit` 호출부(OrderGateway.java:338)는 취소라 멱등 조회를 타지 않는다(V39__append_prologue.sql:52). 남는 것은 둘이다. 거부 응답이 '새 키 필요' 뿐이라 원 주문이 어느 마켓인지 알려 주지 않는다. 그리고 모호한 커밋 뒤 409 '새 키 필요' 를 받은 클라이언트가 새 키로 같은 주문을 다시 내면 이중 주문이 된다. CLAUDE.md 14절 주문 멱등성 위반이고 이 판의 범위 밖 결함이다.

2026-09-11 재심에서 셋을 더 찾았다. 셋 다 이 판의 채택 여부와 무관하게 실재한다.

14. **레거시 이벤트 구간이 마켓별 재생에서 통째로 빠진다. 커트오버가 아니라 축 이전 배포
    당일에 터진다.** `EventJournalReader.readAfterForMarket` 이 `WHERE market_id = ?` 로
    읽는데 V36 이전 `event_journal` 행은 `market_id` 가 NULL 이고 `trg_ej_noupd`(V1:619)가
    백필을 물리로 막는다. 현재 이벤트 저널이 전량 그 구간이다. 곧 마켓별 재생의 1단계
    대조에서 전량이 pending 으로 떨어져 `ReplayService.verifyTo`(ReplayService.java:88-105)가 던지고 기동이 선다.
    스냅샷이 0건이라(9-5) 복구가 항상 전량 재생이므로 첫 기동에서 만난다. 지금 그 경로를
    부르는 곳이 `SnapshotEquivalenceIntegrationTest` 뿐이라 아직 안 터졌을 뿐이다.
    해는 레거시 구간을 `command_journal` 조인으로 유도하는 두 패스 구조이고, 2단계 시험
    조건에 명시한다. **2026-09-13 확인. 새로 만든 DB 에는 이 구간이 없다.** HEAD 로 세운
    카오스 스택에서 `event_journal` 5,648행 중 `market_id` NULL 이 0행이었다. 빈 DB 에
    V36 까지 한 번에 적용되므로 모든 행이 V36 이후다. 곧 이 결함은 V36 적용 전부터 행이 있던
    DB 에서만 발화하고, 새 DB 로 시작하는 클라우드 배포에는 해당하지 않는다. 두 패스 구조는
    그대로 넣는다. `legacy_bound` 가 0 이면 레거시 패스가 빈 구간이라 비용이 없다.
15. **멱등 조회가 파티션 잎 전부를 훑는다. 3-1(3)으로 해소했다.** 술어가
    `(command_type, user_id, client_order_id)` 뿐이라 파티션 프루닝이 없고, 그 조회가 접수
    락 안에 있다. 2026-09-13 실행 계획으로 확인했다. 잎마다 인덱스 스캔 하나를 붙인 Append 다.
    조회는 `findExistingNew` 에서 `append_command_prologue`(V39)로 옮겨졌고 술어는 같다.
    0단계 실측 (2)가 잎 3·15·67 에서 중앙값 0.329·0.584·3.265밀리초를 냈다. 그래서
    b1..b63 선생성을 버리고 마켓이 있는 잎만 만든다. 구조적 차단은 채번 함수 가드와 마켓
    생성 트리거로 옮겼다(3-1(5), 3-1(6)).
16. **마켓별 축이 새 실패 모드를 만든다. 지금 그것을 잡는 검사가 없다.** 오늘은 저널이
    전역 축 하나라 백업 복원이 한 점으로 잘린다. 마켓별 축이면 열두 마켓이 저마다 다른
    지점으로 잘린 상태가 물리적으로 표현 가능하고, 마켓 안에서는 여전히 조밀하므로
    **C05 를 통과한다.** 독립 로그안도 같으므로 어느 안 고유의 비용이 아니다. 새 검사가
    필요하고 축은 `created_at` 이다.
17. **`created_at` 을 쓰는 판정은 DB 서버 시계 역행(PostgreSQL 프라이머리 전환 포함)을 넘지 못한다.** C22 시간 축
    팔, C28, 크래시 창 병합 키가 시계 역행이 없음을 전제한다. 역행의 원인은 둘이다. 하나는 프라이머리
    하나에서 서버 시계가 뒤로 조정되는 경우이고, 다른 하나는 비동기 복제의 수동 승격(ADR 0024) 뒤
    `created_at` 을 찍는 시계가 바뀌어 두 노드 시계 차만큼 역전되는 경우다. C22 마켓 안 팔은
    가려진 마켓 안 역전을 잡지만 시간 축 팔의 거짓 CRITICAL 은 합산에 그대로 남고, C28 도 같은
    거짓 양성을 낼 수 있다. 병합은 마켓 안 순서를 지키고 역전을 센다(5-3). 승격 경우는 수동 절차가
    승격 뒤 앱을 새 primary 로 바꿔 재기동하므로(`docs/operations/DB_수동_페일오버.md`) 그 창이 운영
    절차 안에서 열린다. 프라이머리 하나의 시계 역행은 운영 절차와 무관하게 열린다. 시계 차 보정과
    `system_identifier` 대조는 이 판 밖이다.

---

## 10. 기각한 대안

### 10-1. 접수 그룹 커밋. 장부를 쪼개지 않고 내구 커밋 예산을 줄인다

기각. 사유 셋.

- 지시가 "장부 자체를 쪼개는 설계" 다. 이 안은 장부를 쪼개지 않는다.
- 헤드라인이 자기 모형과 모순이다. 저자 스스로 배치 크기를 `B = λ x T(B)` 의 Nagle 관계로 정의했는데, 주장 착지 293.6/s 에서 그 식이 주는 B 는 2.21 이다. 명령당 플러시가 1.0625 가 아니라 1.452 이고 요구 플러시 426/s 가 저자가 든 예산 311.9/s 를 초과한다. B=16 은 λ≈2,100/s 가 선행돼야 도달한다. 자기 일관해는 약 172/s 다.
- 그리고 `wal-fsync-20260907` 이 이미 같은 방안을 재고 기각했다. "저널 적재 배치로 얻을 수 있는 최대가 요청 시간의 8퍼센트다."

**살려서 이 판에 넣은 것.** (a) 저장소 내구 커밋 예산을 상한 사다리에 넣는다(8-2). (b) V40 파티션 선행 확장과 `command_journal_default` 유입을 CRITICAL 로 올리는 것을 1단계와 6-4 로 옮겼다. 이 판을 접어도 남긴다. (c) '채번을 멱등 판정 뒤에 두고 그 순서를 주석이 아니라 구조로 강제한다' 는 규율(4-3). (d) 그룹 커밋 자체는 배타적이지 않다. 접수 락 분할과 대역 채번(6단계 래치)이 함께 저널 축에 동시 커밋자를 만들어 PostgreSQL 이 알아서 묶을 수 있다. 8단계가 그 자리다.

### 10-2. 전역 시퀀스 + `market_cmd_seq` 별도 컬럼

기각. 결정적 사유 하나다.

커트오버 이전 약 97.7만 행에 그 컬럼이 영구 NULL 이다. `trg_cj_noupd` 와 SELECT/INSERT 전용 권한이 백필을 물리로 막는다(V1:595-598). 그런데 이 안은 소비측 커서·갭 판정·갭 회수·체크포인트·승격·스냅샷의 축을 전부 그 컬럼으로 옮긴다. 스냅샷이 0건이라 복구가 항상 seq 0 전량 재생인 현재 배선에서(9-5) 첫 기동 재생이 그 구간에서 깨진다. NULL 을 0 으로 매핑하면 `<= lastApplied` 로 첫 레거시 명령부터 무음 폐기되고, NULL 을 특례로 접어 전역 축으로 폴백해도 갭 회수원이 그 특례를 따라가지 못한다.

대역 파생은 같은 컬럼의 값만 옮기므로 커서가 커트오버 경계를 그대로 넘는다(2-3(1)). **이것이 대역 파생을 고르는 결정적 이유다.**

부수로 `nextval` 은 트랜잭션 밖이라 롤백에 결번이 남고, `CACHE > 1` 이면 세션별 예약 블록이 값 순서를 호출 순서와 어긋나게 해 `MatchingEngineImpl.apply` 의 무음 폐기로 간다. ADR 0021 이 이미 같은 이유로 시퀀스를 기각했다.

### 10-3. 전역 카운터에서 블록을 예약해 JVM 안에서 나눠 쓴다

기각. 무음 유실 때문이다.

블록 예약은 seq 의 '커밋 순서 단조' 를 없앤다. 정상 상태에서 12마켓 중 열한 마켓의 현재 블록 번호는 가장 바쁜 마켓의 블록보다 낮으므로, 전역 스칼라 커서(= 전 마켓 최대)가 항상 나머지 열한 마켓의 in-flight 번호보다 위에 있다. 그 마켓들이 지금 커밋하는 명령은 `seq > 커서` 를 만족하지 못해 다시는 읽히지 않는다. 그 경로가 둘이다. `FollowerCatchUp.advance`(매 틱 `from = engine.lastAppliedSeq()`)와 `EngineRecovery.replayToCheckpointThenTail`(리더십 대기 구간 뒤 `after` 로 다시 읽는다). 이벤트 커서도 같은 축이라 건너뛴 명령의 이벤트도 함께 건너뛴다. `verifyMatch` 가 발화하지 않고 pending 도 안 생긴다. 예외·지표·불변식 하나 없이 영구 무음 유실이다.

**살려서 이 판에 넣은 것.** `created_at` 을 시간 축으로 승격하는 착상. 이 판의 6-2(C22)와 5-3(크래시 창)이 전부 그 착상에서 나왔다. 그리고 0단계 (b)의 '채번부터 커밋까지' 계측.

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
   마켓별 채번을 켤 수 없다. 곧 이 판의 이득이 전부 비가역 단계 뒤에 있다. 대역은 마켓별
   채번을 켜는 데 PK 교체가 필요 없고 전환 지점이 DB 래치 행 하나다(7절 6단계).

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
최대와 합 둘로 내는 것. 좀비 판별식의 마켓별 확장. 부분 정체 경보. 멱등 조회의
`ORDER BY created_at, seq` 는 4단계 `append_prologue_created_at_order` 마이그레이션으로
넣는다(4-3). 복원 리시드 절차서는 6단계 문서 개정에 넣는다. C12·C13B 조인을 쌍으로 바꾸는
것은 이 판에서 N/A 다. 두 조인은 `c.seq = e.source_cmd_seq`, `c.seq = o.command_seq` 점
조인이고(InvariantCheck.java:124-128, 140-144) 대역 파생에서도 seq 가 전역 유일이라(2-2 표)
결과가 바뀌지 않는다. 쌍으로 내려가는 회차(위 조건 3)에서 바꾼다.

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