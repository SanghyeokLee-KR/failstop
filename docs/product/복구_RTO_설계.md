# 복구 RTO 설계 (승격 재생 병목 해소)

> 정본 계약: `docs/contracts/백엔드_DB_설계.md`(§5.4 리스·§5.5 복구·§6.3 스냅샷), `docs/contracts/하네스_검증설계.md`(§5-1 RTO 체인·§3 E4/E6·§4-1 C13), `docs/architecture/M4_하드닝설계.md`(§3-3(d) 승격 재생 병목·§1 심각도 카탈로그). 코드 정본은 `server/`. 컬럼·라인은 실제 파일 인용, 추측 금지.
> 판정 원칙: SQL 위반 행 0, 메트릭 임계, 벽시계 RTO. 구현 상태(2026-09-09 저장소 확인): 3-a·3-b·3-c는 코드에 배선됐고 4-2 RTO 측정 계약 정정만 미착수다. 단계별 상태는 §6-1이 소유한다.
> 스타일: 개조식 평서문. em dash·en dash·이모지·도구 서명 금지. 완료 전 `scripts/check-style.ps1`.

---

## 1. 문제 정의

### 1-1. 확정 사실(무결성)

15분 데모 카오스(스파이크 중첩 리더 kill, `docs/contracts/하네스_검증설계.md` §7 데모 프로파일)에서 무결성은 증명됐다.

- RPO=0: ACK 명령 전량 `command_journal` 존재(C16 0행).
- 자산합 0: `ledger_entries` 자산별 합 0(C01 0행).
- 불변식 위반 0: 이 회차에서 평가한 14종(C01·C02·C05·C06·C07·C07P·C08·C13A·C13B·C14·C16·C17·C18·C19)이 전건 green.
  - 이 14종은 현 구현 카탈로그 전량이 아니다. `InvariantCheck.java` enum은 26종이고(C01·C02·C03·C05·C06·C07·C07P·C08·C09·C10·C11·C12·C13A·C13B·C14·C15·C16·C17·C18·C19·C20·C21·C22·C23·C24·C25), `harness/sql/invariants.sql`은 여기에 C04를 더해 27개 식별자를 쓴다. 카탈로그 총수는 §5 확정 항목이 소유한다. 세는 단위는 SQL 판정 블록이고 총수는 27개다.

안전성 결함이 아니었다. catch-up 경로는 멱등 스킵이고 원장은 무결하며 유실이 없다. 2026-08 시점의 라이브니스·RTO 결함이고, 현재 상태는 §1-2-a가 소유한다.

### 1-2. RTO 이중성(metric vs 실제 가시 체결)

아래 표는 2026-08 회차(15분 데모 카오스) 기준이다. 현재 상태가 아니라 문제를 발견한 시점의 측정값이다.

| 축 | 값 | 측정 시점 | 근거 |
|---|---|---|---|
| metric RTO | 약 11.289s | 2026-08 회차 | `docs/contracts/하네스_검증설계.md` §5-1 T5: `min(created_at) FROM event_journal WHERE event_seq > W_event AND event_type='TRADE'` |
| 실제 가시 체결 재개 | 약 4~8분 | 2026-08 회차 | 승격 후 신규 명령의 체결 커밋·WS 게시가 실제로 관측된 벽시계 시각 |
| 목표 | < 30s | 계약 | `docs/architecture/M4_하드닝설계.md` §3-2, API §7-4 |

metric RTO가 실측을 은폐한다. 근인은 T5의 `created_at`이 벽시계 커밋 시각이 아니라 `engine_ts` 파생 논리 시각이라는 점이다(§2-4). 2026-08 회차 판정 = FAIL(실제 30s 초과).

#### 1-2-a. 결과: 2026-09-08 leader-failover 실측에서 해소

- 상태: 2GiB 노드 규격 조건부 해소. 위 FAIL은 2026-08 회차 판정이고 현재 상태가 아니다. 무조건 해소가 아니다. 1GiB 노드는 OPEN 주문 195,808건 조건에서 승격 재생을 끝내지 못했다(§2-1 남은 결함).
- 실측: 두 노드 리더 kill 세 회차에서 승격 완료가 T0+11.3초·12.0초·13.1초이고, 새 리더 처리량 정상화(68.1 주문/s)가 T0+15초다. 출처 `harness/evidence/leader-failover-20260908/README.md` 결론 표(판정 A·D). 정본 `제출_20260901/기획안_원본.md` 10.5는 같은 증거를 근거로 리더 교체 시간을 9.6초에서 13.1초로 적는다. 같은 결과표의 네 번째 리더 kill 회차(1GiB, OPEN 19.6만, f4)는 실패로 남아 있다. 성공 세 회차는 그 조건 밖이다.
- 해소 경로: 승격 gain 훅과 접수 개방 전 선복원이 배선됐다(§2-1). 대규모 delta가 정산 sink를 타지 않으므로 4~8분 정체가 재발하지 않는다.
- 정본 정합: 정본 3.1이 리더 선출과 세대 번호 펜싱을 성립으로 등재하고 근거를 같은 증거 디렉터리로 단다. 정본 3.2 미성립 표에 RTO 초과 항목이 없다.
- 측정 평면 한계: 위 실측은 전부 로컬 도커 2노드다. 배포 대상 인스턴스 실물 재측정은 0건이다(정본 3.3).

### 1-3. 트리거 조건

스파이크(저널 27k+ 명령 누적) 이후 리더 kill. 오래 팔로우한 스탠바이가 승격 주체일 때만 발현한다. 저널이 작을 때(부팅 직후 kill)는 재생량이 작아 은폐되지 않는다.

---

## 2. 근인(코드 라인 확정)

### 2-1. 승격 gain 훅과 접수 개방 전 선복원(3-a 배선 완료)

당초 근인은 승격 통지 인터페이스 부재였다. 현재는 3-a가 배선돼 있다.

- `LeaderLeaseService`가 획득·상실 두 리스너 목록을 보유한다. `gainListeners`(LeaderLeaseService.java:90), `public void onLeadershipGained(Consumer<LeaderIdentity> listener)`(:194). 상실 축은 `lossListeners`(:83), `onLeadershipLost`(:172).
- 승격 경로가 epoch 세팅 직후 gain 리스너를 발화한다. 발화 루프는 `for (Consumer<LeaderIdentity> listener : gainListeners)`(LeaderLeaseService.java:369)이고 바로 앞 :366이 `serviceHealth.onLeadershipGained()`다.
- 배선측이 gain을 건다. `EngineConfig.java:322` `leaderLeaseService.onLeadershipGained(identity -> catchUp.stopForPromotion());`, `EngineConfig.java:324` `leaderLeaseService.onLeadershipGained(coordinator::onLeadershipGained);`. 상실 배선은 `EngineConfig.java:372` `lease.onLeadershipLost(loop::halt);`다.
- 승격 시점에 재생이 다시 돈다. `PromotionCoordinator.replayOnce`가 fresh `MatchingEngineImpl`을 만들어 `recovery.recover(fresh)`를 돌리고 `loop.installEngine(fresh)`로 핸드오프한다(PromotionCoordinator.java:310~313). 부팅 경로는 `recovery.recoverAtBoot(engine)`로 분리됐다(EngineConfig.java:382).

결과: 승격 시 인메모리 엔진이 접수 개방 전에 head까지 선복원된다. 스탠바이가 부팅 시점 seq에 머무는 상태는 해소됐다.

남은 결함: 승격 재생의 메모리 소요다. OPEN 주문 195,808건 상태에서 1GiB 노드가 승격 재생을 끝내지 못하고 GC 스파이럴에 빠졌고, 2GiB로 올리자 같은 조건에서 성립했다(`harness/evidence/leader-failover-20260908/README.md` 결론 절). 3-b 스냅샷이 tail 재생량을 상한하기 전에는 노드 규격이 이 경로의 제약이다.

### 2-2. 스탠바이 엔진이 stale lastAppliedSeq로 열림(2026-08 근인. 3-a 배선으로 닫힘)

- `MatchingEngineImpl.lastAppliedSeq`는 마지막 apply한 명령 seq다(MatchingEngineImpl.java:19, :28, :33). 스탠바이는 리더가 아니라 큐에 명령이 공급되지 않으므로, 2026-08 시점에는 부팅 후 이 값이 전진하지 않았다.
- 부팅 시 저널이 작았다면 lastAppliedSeq는 0 또는 소수였다. 스파이크 동안 리더가 27k 명령을 적재해도 스탠바이 엔진은 따라가지 않았다. 현재는 `FollowerCatchUp`(2026-09-08 배선)이 체크포인트 상한까지 팔로워 엔진을 계속 재생해 이 얼어붙음을 없앤다(`server/app/src/main/java/kr/exchange/engine/FollowerCatchUp.java`, 배선 EngineConfig.java:354~357). 상세는 §3-d다.
- 승격 후 게이트웨이 접수가 열려 첫 신규 명령(seq 약 27k+)이 큐에 들어오면, `SingleWriterLoop.process`가 `command.seq() > last + 1`을 관측하고 `catchUpTo(command.seq())`를 호출했다(`server/engine/src/main/java/kr/exchange/engine/core/SingleWriterLoop.java:203`, :204). 현재는 접수 게이트가 rebuild 완료 전 유입을 막아 이 경로에 대규모 gap이 들어오지 않는다(§2-1·§3-a).

### 2-3. catch-up의 sink가 인메모리 rebuild가 아니라 정산 경로(2026-08 근인)

- `catchUpTo`는 `applyAndSink`로 각 명령을 처리한다(SingleWriterLoop.java:212~227, 호출 지점 :221).
- `applyAndSink`는 `engine.apply` 후 방출 이벤트를 `eventSink.accept`로 넘긴다(SingleWriterLoop.java:229~239, accept 호출 :232).
- 이 `eventSink`는 정산 경로다. `EngineConfig.java:240~248` 람다가 `settlementService.appendAndSettle(events)`(:242) 결과를 `marketDataPublisher.publishSettled(settled)`(:247)로 잇는다.
- `appendAndSettle`는 명령마다 원격 PG 왕복 트랜잭션 1개다(`SettlementService.appendAndSettle`). 트랜잭션 본체는 `settleInTx`이고 내부는 다음과 같다.
  - fencing 검증과 `leader_lease` 행 잠금을 한 왕복으로 한다. `SettlementService.ASSERT_AND_LOCK_SQL`(`SELECT assert_and_lock_leader(?, ?)`). V30 이전에는 `assert_current_leader` 호출과 `leader_lease FOR SHARE` 조회 두 문장이었다.
  - `event_emission_registry` count로 멱등 판정(`settleInTx`의 `SELECT count(*) FROM event_emission_registry WHERE source_cmd_seq = ?`). 이미 적재분이면 `registered == events.size()`로 전량 스킵.
  - BEGIN·COMMIT(`appendAndSettle`의 `tx.execute(status -> settleInTx(...))`). PG는 주문당 fsync 구성(`docs/contracts/하네스_검증설계.md` §2-4).

catch-up 배치가 27k건이면 27k개의 직렬 원격 트랜잭션이 toxiproxy 경유로 순차 커밋된다. 전부 멱등 스킵이라 상태를 바꾸지 않지만, 각 트랜잭션이 다중 왕복 + fsync 커밋이다. RTO = O(저널 크기) × 정산 왕복 지연. 이것이 2026-08 회차 4~8분 정체의 근인이었다. 3-a 배선 이후 승격 delta는 이 경로를 타지 않는다(§2-1).

대조군: `ReplayService.rebuild`는 같은 27k를 인메모리로만 처리한다. `engine.apply`(순수, `ReplayService.rebuild`의 재생 루프) + `event_journal` 배치 대조(`ReplayService.verifyMatch`, 커서는 내부 클래스 `JournalEventCursor`). 쓰기 트랜잭션 0, 커밋 0, 정산 0. 500행 배치 읽기(`ReplayService.BATCH`)라 왕복 수가 O(저널/500)이고 fsync가 없다. 크래시 창 pending만 정산한다(`EngineRecovery.recover`의 pending 재적재 루프, sink는 `reappendSink`). 즉 올바른 sink는 이미 존재했고, 승격 경로가 이 sink 대신 정산 sink를 탄 것이 결함이었다. 3-a가 승격 재생을 `recover` 경로로 옮겨 닫았다.

### 2-4. metric RTO 은폐 메커니즘

- `event_journal.created_at`은 `engine_ts` 파생값이다. `SettlementService.insertEvent`가 `INSERT_EVENT_SQL`의 `created_at` 자리에 `toUtc(event.engineTs())`를 바인딩한다. 재생 대조도 `created_at == engine_ts`를 강제한다(`ReplayService.verifyMatch`의 "created_at이 engine_ts 파생값과 다르다" 분기).
- `engine_ts`는 게이트웨이가 접수 시점에 박제한다(`OrderGateway`, 접수 박제 계약 P0-4). 즉 명령이 수락된 논리 시각이다.
- 승격 후 첫 신규 명령의 `engine_ts`는 접수 시각(약 승격+11s)에 박제됐다. 그러나 그 명령의 `event_journal` 행은 27k catch-up이 끝난 뒤(약 4~8분 후) 커밋된다. §5-1 T5의 `min(created_at)`은 박제된 이른 논리 시각을 읽어 11.289s로 보고한다. 실제 커밋·게시 벽시계는 분 단위 뒤다.
- `event_journal`에는 커밋 벽시계 컬럼이 없다. 따라서 T5를 `created_at`으로 재면 구조적으로 catch-up 지연이 소거된다.

---

## 3. 수정 설계

### 3-a. 승격 gain 훅 배선 + 접수 개방 전 인메모리 선복원(배선 완료)

핵심. 접수를 열기 전에 인메모리 rebuild로 엔진을 head까지 끌어올린다. 대규모 delta를 정산 sink로 태우지 않는다. 아래는 배선된 설계이고 실물 대응은 §2-1이 인용한다.

#### 상태기계

```
FOLLOWER  --(리스 만료 감지·tryAcquire 성공, epoch+1)-->  PROMOTING
PROMOTING --(fresh 엔진 rebuild 완료 + 크래시 창 재정산 완료)-->  ACCEPTING
ACCEPTING --(fencing 거부·정산 실패·리스 상실)-->  DEMOTED(self-shutdown)
```

- 감지·승격: `tryAcquire` 성공 시 gain 콜백이 발화한다. `LeaderLeaseService`가 `onLeadershipGained(Consumer<LeaderIdentity>)` 리스너 목록을 보유하고(LeaderLeaseService.java:90, :194) epoch 세팅 직후 발화한다(:369). 승격 성공 경로는 부팅 `start()` 최초 획득과 런타임 tick 승격 둘 다다. 부팅 경로는 `recoverAtBoot`로 분리해 이중 실행을 막는다(EngineConfig.java:382).
- rebuild: gain 콜백이 접수를 닫은 채 **fresh 엔진**을 만들어 `recover`(스냅샷 로드 + tail 재생 + 크래시 창 재정산)를 돌린다(`PromotionCoordinator.replayOnce`, PromotionCoordinator.java:294~313).
- 접수 개방: rebuild 완료 후에만 접수 게이트를 연다(PromotionCoordinator.java:228 닫힘, :233 개방).

#### fresh 엔진 필수(정합성 제약)

**커서 어긋남 위험은 해소됐다(2026-09-12, ADR 0023).** 종전에는 재생 진입점이 이벤트 커서 시작점을 인자로 받았고, 그 자리에 잘못된 축의 값을 넣으면 대조가 어긋났다. 지금은 세 진입점 모두 커서 시작점을 받지 않고 본체가 `engine.lastAppliedSeq()` 에서 유도하므로, 명령 커서와 이벤트 커서가 구조적으로 같은 값에서 출발한다. 승격 재생이 fresh 엔진을 쓰는 계약은 그대로다. 이유가 커서 정합에서 재생 중단 시 적용 범위 불확정으로 바뀌었을 뿐이다. 대조 전용 진입점은 `verifyTo(MatchingEngine, JournalCheckpoint)` 이고 크래시 창 산출은 `replayTail(MatchingEngine, long)` 이다(§3-b).

#### 루프로의 엔진 핸드오프

- 승격 sequence는 fresh 엔진을 rebuild한 뒤 단일 라이터 루프가 이 엔진을 쓰도록 설치한다. 접수가 닫힌 PROMOTING 구간이라 동시 apply가 없어 스왑이 안전하다.
- 구현 선택지 확정: (i)을 채택했다. `SingleWriterLoop.engine`이 volatile이고(SingleWriterLoop.java:49) 접수 닫힘 구간에서만 호출하는 `installEngine`이 있다(:92). 대안 (ii) halt 후 루프 재시작은 큐·스레드 재생성 비용 때문에 버렸다. "접수 닫힘 + 라이터 유휴" 불변은 이 절이 소유한다.

#### 접수 게이트(readiness 연동)

- 종전 접수는 `EngineBridge.submit`이 `loop.isRunning()`만 검사했고 append는 `lease.requireLeader()`로 리더십만 봤다. 승격 즉시 epoch가 세팅되므로 rebuild 완료 전에도 append·offer가 통과해 결함 경로가 재발했다.
- 접수 개방 여부를 리더십과 분리한 명시 게이트로 둔다. `isLeader() == true`여도 접수 게이트가 닫힌 PROMOTING 구간을 만든다. 실물은 `AcceptGate.isAccepting()`이고(AcceptGate.java:13), `EngineBridge.submit`이 루프 생존(EngineBridge.java:74)에 이어 게이트를 확인해(:79) 미개방 시 `NotReadyException`으로 거부한다(:82). 이 예외는 503 service_unavailable로 렌더된다(ApiExceptionHandler.java:67~71). 봇·k6는 503을 재시도로 흡수한다(`SimConfig` 주석, 접수 보류 계약).
- Spring readiness 상태와 연동한다. PROMOTING = `REFUSING_TRAFFIC`(`HaAvailability.refusing()`), ACCEPTING = `ACCEPTING_TRAFFIC`(`HaAvailability.accepting()`). 리더 인지 LB(`docs/architecture/M4_하드닝설계.md` §3-4)가 readiness DOWN 노드를 제외하므로 rebuild 중 외부 트래픽 유입을 원천 차단한다.

#### 승격 중 리스 유지

- rebuild는 lease 스케줄러 스레드가 아니라 별도 승격 워커에서 돌린다. lease tick(TTL 10s 기본, tick 3s, `EngineProperties`)이 rebuild 동안 계속 renew해야 만료·플래핑이 없다. 27k 인메모리 rebuild는 배치 읽기 O(저널/500) 왕복이라 지연 주입 하에서도 수 초 내지만, TTL 의존을 없애려 rebuild를 renew와 분리한다.
- rebuild 실패(ReplayMismatch·재정산 실패)는 승격 거부·self-shutdown이다(무음 유실 금지, 현 `EngineRecovery`·`LoopFatalHandler` 계약 유지).

#### catch-up의 잔여 역할 축소

- `SingleWriterLoop.catchUpTo`는 제거하지 않는다. 큐 유실 같은 소규모 gap(수 건)의 방어선으로 유지한다(EngineRuntimeIntegrationTest `seqGapIsRecoveredByCatchUp` 커버).
- 단 승격 delta는 절대 catch-up이 메우지 않는다. 접수 개방 시점에 엔진이 head에 있으므로 gap은 항상 소규모다. 방어적으로 catch-up gap이 임계(예: CATCH_UP_BATCH 초과)를 넘으면 접수 게이트가 우회된 상태로 보고 fail-fast하는 가드를 검토한다.

### 3-b. 주기적 스냅샷(catch-up 상한을 저널 크기와 분리, 배선 완료)

3-a의 인메모리 rebuild는 스파이크(27k)에서는 빠르지만 장기 soak(수백만 명령)에서는 다시 O(저널)로 커진다. 스냅샷이 tail 재생량을 상한한다. 아래는 배선된 설계이고 단계 상태는 §6-1이 소유한다.

- 저장소: 기존 `snapshots` 테이블을 그대로 쓴다(`docs/contracts/백엔드_DB_설계.md` §3.1 DDL 블록 6.3 `CREATE TABLE snapshots`). 신규 DDL 없음. 컬럼: `last_applied_seq`, `state`(BYTEA), `state_checksum`, `engine_version`, `config_hash`, `format_version`, `fencing_epoch`, `created_at`. append-only 강제는 같은 블록의 `trg_snap_noupd`·`trg_snap_notrunc` 트리거와, §3.1 권한 블록이 `app_role`에 `GRANT SELECT, INSERT`만 부여하는 것이다.
- 주기·트리거: 리더가 N 명령마다 또는 T초마다(둘 중 먼저) 스냅샷 1건을 기록한다. 실물은 `SingleWriterLoop.SnapshotHook` 구현체 `server/app/src/main/java/kr/exchange/snapshot/SnapshotCapture.java`의 `afterCommand`이고, 배선은 `EngineConfig`의 `snapshotCapture` 빈과 `loop.setSnapshotHook(capture)`다. 라이터 스레드는 정책 판정과 마켓별 결정론 인코딩만 하고 DB 영속화는 별도 워커 스레드가 한다. 유계 큐가 차면 드롭한다(best-effort. tail이 길어질 뿐 정확성 무손실). 경계 seq에서의 인메모리 상태 정합은 단일 라이터라 자연 확보된다(그 seq까지 apply 완료 시점 캡처). 주기 값은 `SnapshotProperties`가 소유하고 기본값은 `commandInterval` 100,000 명령·`timeInterval` 60초다.
  - 미결: 정확 주기 값은 캘리브레이션(`docs/architecture/M4_하드닝설계.md` §3-3(d))에서 확정해야 하며 아직 정해지지 않았다. 위 기본값은 캘리브레이션 전 초기값이고 실측 근거가 없다.
- 직렬화: `MatchingEngineImpl`의 마켓별 `OrderBookImpl`(`bids`·`asks`·`ordersById` TreeMap)을 결정론 바이트로 인코딩하는 codec은 `server/engine/src/main/java/kr/exchange/engine/core/SnapshotCodec.java`다(`encode`·`decode`, `FORMAT_VERSION` 1). 순회 순서가 정의된 자료구조만 쓰므로(TreeMap·ArrayDeque, 코어 순수성 T3-A) 인코딩이 결정론이다. `state_checksum`은 기존 `OrderBookImpl.stateChecksum()`(`MatchingEngineImpl.stateChecksum()`과 동일 규약)을 병기해 로드 시 무결 검증한다.
- 복원(스냅샷 + 델타): 승격 rebuild가 최신 유효 스냅샷을 로드해(`EngineRecovery`의 `snapshotSource.loadLatestAdoptable()`, DB 설계 §5.5 STEP1) fresh 엔진에 역직렬화한 뒤, `command_journal`에서 `seq > snapshot.last_applied_seq`만 tail 재생한다(DB 설계 §5.5 STEP2).
  - 이벤트 커서 정렬(2026-09-12 개정, ADR 0023): 커서 시작점을 인자로 넘기지 않는다. `loadSnapshot` 이 `engine.lastAppliedSeq()` 를 스냅샷 seq 로 세우고 재생이 그 값에서 명령 커서와 이벤트 커서를 함께 연다. 종전의 이벤트 워터마크 유도(`SnapshotStore.eventWatermark`)는 삭제했다. 그 유도는 `event_seq` 가 명령 순서대로 결번 없이 배정된다는 전제 위에 있었고 V36 의 마켓 대역 파생이 그것을 깼다.
  - 채택 판정: `engine_version`·`config_hash`·`format_version` 불일치 스냅샷은 무시하고 이전 스냅샷 또는 seq 0 재생으로 폴백한다(DB 설계 §3.1 DDL 블록 6.3 주석 "불일치 스냅샷은 무시하고 저널 재생으로 폴백한다"). `state_checksum` 불일치도 폐기가 아니라 무시·폴백(테이블 append-only, 삭제 금지).
- 단일 정본 규율: 스냅샷은 저널 파생이다. 원천은 `command_journal`·`event_journal`이고 스냅샷은 재생 시작점 최적화일 뿐이다(DB 설계 §1.1 ① 복구 원천, :16). 스냅샷 부재·손상 시 항상 저널 전량 재생으로 동일 결과에 도달하므로 단일 정본이 유지된다.

### 3-c. C13A 스코프 AUTHORITATIVE_ONLY 수정(적용 완료)

- 종전 결함: `C13A_ORDER_MATERIALIZED`가 `Scope.SCHEDULED`였다. 이 체크는 `command_journal`의 모든 NEW 명령에 대응 `orders` 행 존재를 요구한다(InvariantCheck.java:124~128).
- 오탐 메커니즘: NEW 명령은 append 시점에 커밋되지만, `orders` 행은 이후 정산 sink가 ACCEPTED 이벤트를 처리할 때 INSERT된다(SettlementService.java:225~240 `settleAccepted`). append와 정산 커밋 사이의 비동기 창에서 명령은 있으나 주문 행이 없다. 라이브 부하·스파이크에서 큐 백로그가 이 창을 키워 C13A가 대량 위반으로 오탐했다.
- 수정(적용 완료): 실물이 `C13A_ORDER_MATERIALIZED(Severity.CRITICAL, Scope.AUTHORITATIVE_ONLY, ...)`다(InvariantCheck.java:124, 사유 주석 :120~123). 상시 스케줄·soak에서 제외하고, 정지 시점·실험 사후 권위 run(큐 드레인 상태)에서만 평가한다. 근거는 기존 C16·C19의 AUTHORITATIVE_ONLY 사유와 동일하다("라이브 프로젝션 지연과 스테일 loadtest_acks가 오탐을 만들기 때문이다", InvariantCheck.java:13~14).
- 커버리지 손실 없음: 역방향 C13B(orders에서 command_journal 방향, InvariantCheck.java:129~133)는 정산 이후에만 orders가 존재하므로 프로젝션 지연 오탐이 없다. C13B는 `SCHEDULED` 유지. 유실 주문(NEW without order)은 큐 드레인된 권위 run에서 C13A가 잡는다.
- E3(구 번호, 정본 E6) 스파이크 게이트의 스퍼리어스 FAIL: 동일 근인이다. 스파이크 창에서 상시 C13A가 프로젝션 지연을 위반으로 집계해 게이트를 오탈락시켰다. C13A를 AUTHORITATIVE_ONLY로 옮겨 상시 집계에서 뺐다. 스파이크 게이트 스퍼리어스 FAIL 0 은 실측이 아직 없고 §5 수용 기준으로 남는다. 데모 15분 스택은 구 잠정 번호 E3 = 정본 E6(스파이크+kill 복합, `docs/architecture/M4_하드닝설계.md` §0-3)을 가리킨다.
- 문서 동기화(완료): `docs/architecture/M4_하드닝설계.md` §1(심각도 카탈로그)·§2-2(soak 판정의 CRITICAL·SCHEDULED 열거)·§4-2(알람 라우팅 critical 열거) 세 자리 모두 C13A를 CRITICAL·AUTHORITATIVE_ONLY로 적고 상시·soak 대상에서 뺀다. 코드와 정합이다.

### 3-d. 핫 스탠바이(연속 적용) 검토·판정(2026-09-08 도입으로 갱신)

- 대안: 스탠바이가 `command_journal`을 실시간 tail해 인메모리 엔진만(정산 없이) 연속 apply하는 웜 팔로워. 승격 시 delta가 0에 수렴해 rebuild가 사실상 불필요.
- 종전 판정(설계 시점): 현 단계 도입하지 않는다. 근거는 아래 셋이었다.
  - 스냅샷 + 빠른 rebuild가 목표(RTO<30s)를 충족한다. 27k 인메모리 rebuild는 수 초, 스냅샷으로 tail을 상한하면 더 짧다.
  - 웜 팔로워는 상태 전진 경로를 하나 더 만든다. 이 경로가 재생과 비트동일해야 하고(결정론 표면 확대), 팔로워 이벤트 대조·발산 감지를 rebuild와 별도로 유지해야 한다. 정확성 > 편의성 원칙에서 표면 확대는 비용이다.
  - 단일 코드 경로(recover/rebuild) 유지가 검증·감사를 단순화한다.
- 갱신 판정(2026-09-08): 도입했다. `FollowerCatchUp`이 체크포인트 상한까지 팔로워 엔진을 계속 재생한다(`server/app/src/main/java/kr/exchange/engine/FollowerCatchUp.java`, 배선 EngineConfig.java:354~357).
  - 표면 확대 우려는 재생 경로를 새로 만들지 않고 `ReplayService` 재생을 그대로 돌려 해소했다. 이벤트 payload 바이트 대조가 곧 발산 감지다.
  - 승격 경로는 바뀌지 않는다. 승격은 여전히 fresh 엔진 rebuild이고, 설치 엔진이 이미 저널 head에 닿았을 때만 재생을 생략한다(PromotionCoordinator.java:295, :301). 생략의 안전 근거는 FollowerCatchUp 불변식 M(설치 엔진의 lastAppliedSeq가 관측 체크포인트를 넘지 않음)이다.
- 계량: 승격 rebuild delta 벽시계는 `recovery_runs.duration_ms`로 상시 계량한다. 웜 팔로워 도입 여부라는 재검토 트리거 자체는 위 갱신 판정으로 종결됐다.

---

## 4. 회귀 테스트 설계

종전 CI 사각: 어느 통합테스트도 "빈(또는 stale) 엔진 + 대규모 gap을 정산 sink로 catch-up" 경로를 밟지 않았다. `EngineRuntimeIntegrationTest.seqGapIsRecoveredByCatchUp`(EngineRuntimeIntegrationTest.java:190)은 gap이 2건이고, 더 결정적으로 루프를 열기 전에 `replayService.rebuild(runningEngine)`로 엔진을 미리 head에 올린다(:193). 즉 결함을 은폐하는 조건에서만 catch-up을 검증한다. 4-1이 이 사각을 메운다.

### 4-1. 통합테스트: 승격 재생 경로(대규모 gap)(추가 완료)

- 이름: `PromotionRecoveryIntegrationTest`. 실물은 `server/app/src/test/java/kr/exchange/engine/PromotionRecoveryIntegrationTest.java`다. Testcontainers postgres:17.
- 준비: 초기 리더로 대규모 저널을 만든다. NEW·매칭 명령을 N건(스파이크 재현 위해 27k+, CI 축약본은 수천) 접수·정산해 `command_journal`·`event_journal`·`orders`를 채운다.
- 승격 시뮬레이션: 스탠바이 부팅을 재현한다. lastAppliedSeq=0(또는 소수)인 fresh `MatchingEngineImpl` + 접수 닫힘 루프. 초기 리더 강등 후 승격 콜백을 발화한다.
- 계약 단언:
  - (A) 정산 sink 호출 계량. 계수 sink를 주입해 승격 rebuild가 `appendAndSettle`를 O(pending)회만 호출하고 O(저널)회 호출하지 않음을 단언한다. 클린 승격이면 pending=0이라 호출 0회. 이것이 정산 catch-up 회귀의 직접 방지선이다.
  - (B) 엔진 head 도달. rebuild 후 `engine.lastAppliedSeq() == max(command_journal.seq)`, `stateChecksum` == 초기 리더 종료 상태와 일치.
  - (C) 접수 게이트 순서. rebuild 완료 전 `submit` 시도는 503(NotReady), 완료 후 첫 신규 명령은 통과.
  - (D) 벽시계 RTO. 아래 4-2 측정 계약으로 승격~가시 첫 체결 < 예산.
- 스냅샷 케이스(3-b, 추가 완료): 별도 테스트 클래스가 소유한다. `server/app/src/test/java/kr/exchange/snapshot/SnapshotRecoveryIntegrationTest.java`의 `adoptsSnapshotAndReplaysOnlyTail`이 스냅샷 채택 시 tail 재생이 `seq > snapshot.last_applied_seq`로 상한됨을, `discardsVersionMismatchedSnapshotAndFullReplays`가 `engine_version` 불일치 스냅샷의 seq 0 전량 재생 폴백을 단언한다. `server/app/src/test/java/kr/exchange/snapshot/SnapshotEquivalenceIntegrationTest.java`의 `storeRecoveryAdoptsLatestThenFallsBackOnCorruption`이 `state_checksum` 손상 시 직전 유효 스냅샷 폴백을, `versionAndConfigMismatchDiscardAllAndFullReplay`가 전량 재생 폴백을 단언한다.
  - 단언 축은 `ReplayResult.replayedCommands`와 엔진 바이트·체크섬이다. `recovery_runs.replayed_from_seq/to_seq` 대조로 같은 사실을 재확인하는 단언은 아직 없다.

### 4-2. RTO 측정 계약(engine_ts 금지, 가시 첫 체결 기준)

- 문제: §5-1 T5가 `created_at`(= engine_ts 파생)을 쓰면 catch-up 지연이 소거된다(§2-4).
- 계약: RTO는 승격(T0)부터 **가시 첫 체결**(post-promotion 신규 명령의 TRADE가 실제로 커밋·게시된 벽시계 시각, T5_wall)까지로 잰다. `engine_ts`·`created_at`을 T5 산정에 쓰지 않는다.
- 측정점(택1 또는 병행):
  - 서버 벽시계 프로브: 테스트가 승격 후 신규 주문을 넣고, 그 주문의 TRADE 정산 커밋(또는 WS 게시)이 관측된 `System.nanoTime` 벽시계를 T5_wall로 기록. Awaitility로 대기.
  - 외부 관측: k6 마지막 2xx~최초 체결 응답 갭(§5-1 T4·T5 외부 관측 병기). 데모·E6에서 3원 대조.
- 기존 SQL은 논리 교차검증으로만 유지한다. `event_journal`에 커밋 벽시계 컬럼이 없으므로(append-only 핫패스 오염 회피 위해 추가하지 않음), 정본 판정은 관측 벽시계다. `docs/contracts/하네스_검증설계.md` §5-1 T5의 정의를 "created_at 기준"에서 "관측 벽시계 기준(created_at은 논리 교차검증)"으로 정정 권고한다.

### 4-3. 편입

- `docs/architecture/M4_하드닝설계.md` §1-2 E4·E6 회귀 연계에 본 테스트를 추가한다. E4 판정 RTO<30s·`recovery_runs.matched=true`가 이 경로를 실제로 밟도록 게이트한다.

---

## 5. 수용 기준

- 스파이크 조건(저널 27k+ 명령 누적) 리더 kill에서 실제 가시 체결 재개 RTO < 30s(벽시계, §4-2 계약).
- RPO=0 유지: C16 0행, ACK 명령 전량 `command_journal` 존재.
- 불변식 카탈로그 전건 위반 0(권위 run): `InvariantCheck.java` enum 26종. C13A·C16·C19·C22·C23·C24·C25는 `Scope.AUTHORITATIVE_ONLY`로 평가한다.
- 확정(2026-09-09): 불변식을 세는 단위는 SQL 판정 블록이다. `harness/sql/invariants.sql` 의 판정 블록 27개가 정본 수치다. 정본 `제출_20260901/기획안_원본.md`(요약·1.3·3.1·10.2·12절 A2) 다섯 곳을 종전 37개에서 27개로 정정했다. `InvariantCheck.java` enum 26종은 앱 러너가 순회하는 크기이고 27개에서 C04 하나가 빠진 값이다. C04 는 지연 제약 트리거가 DB 안에서 강제하므로 러너의 조회 대상이 아니다. 카탈로그 코드 축 C01~C25 는 그대로 두되 총수를 적을 때는 27을 쓴다.
- `recovery_runs.matched=true`, epoch 정확히 +1, 중복 체결 0.
- 승격 rebuild가 `appendAndSettle`를 O(pending)회만 호출(정산 catch-up 재발 0, §4-1 (A)).
- soak 2h에서 C13A 프로젝션 지연 오탐 0(SCHEDULED 미평가), E6 스파이크 게이트 스퍼리어스 FAIL 0.

---

## 6. 구현 순서·영향 범위·리스크

### 6-1. 순서(작은 단위 완결)

1. C13A 스코프 수정(3-c). 완료. `InvariantCheck.java:124`가 AUTHORITATIVE_ONLY이고 `docs/architecture/M4_하드닝설계.md` 세 자리도 정합이다(3-c).
2. 승격 gain 훅 + fresh 엔진 rebuild-before-accept + 접수 게이트(3-a). 코드 배선 완료. 스키마 무변경. 실물은 `LeaderLeaseService`·`PromotionCoordinator`·`AcceptGate`·`EngineBridge`·`SingleWriterLoop.installEngine`이다(§2-1·§3-a 인용). 회귀 테스트는 `server/app/src/test/java/kr/exchange/engine/PromotionRecoveryIntegrationTest.java`다. 실측 조건과 남은 규격 제약은 §1-2-a·§2-1이 소유한다.
3. 주기 스냅샷(3-b). 코드 배선 완료. codec `server/engine/src/main/java/kr/exchange/engine/core/SnapshotCodec.java`, writer `server/app/src/main/java/kr/exchange/snapshot/SnapshotCapture.java`, 로드 경로 `EngineRecovery`의 `snapshotSource.loadLatestAdoptable()`(EngineRecovery.java:146~151). 장기 soak에서 tail 상한이 실제로 걸리는지의 실측은 아직 없다.
4. RTO 측정 계약 정정(4-2)과 문서 동기화. 미착수. `docs/contracts/하네스_검증설계.md` §5-1 T5가 아직 `min(e.created_at)` 기준이다. E4·E6 게이트에 벽시계 RTO 반영도 남았다.

각 단계는 컴파일·테스트 통과 상태로만 멈춘다.

### 6-2. 영향 범위

- 변경된 곳(3-a·3-b·3-c 반영 완료): `LeaderLeaseService`(gain 리스너), `EngineConfig`(gain 배선·접수 게이트·스냅샷 빈), `EngineBridge`/append 진입점(접수 게이트 확인), `SingleWriterLoop`(엔진 핸드오프·스냅샷 훅), `EngineRecovery`/`ReplayService`(스냅샷 로드·이벤트 커서 정렬), 스냅샷 codec `SnapshotCodec`·writer `SnapshotCapture`(신설 완료), `InvariantCheck`(C13A 스코프).
- 남은 변경: 4-2 RTO 측정 계약 정정과 문서 동기화(§6-1 4번), §3-a의 catch-up gap 임계 가드(검토 단계).
- 코어 순수성 유지: 스냅샷 codec는 순수 Java 결정론 인코딩. 엔진 모듈에 Spring 의존 금지. 현재 시각·랜덤·비결정 순회 금지.
- 원장 게시는 `post_ledger_transaction` 단일 경로 유지. 승격 rebuild는 크래시 창 pending만 정산(기존 `appendAndSettle` 경로 재사용), 원장 직접 DML 없음.

### 6-3. 마이그레이션

- 3-b 스냅샷은 기존 `snapshots` 테이블(V1)을 그대로 쓴다. 기본 계획은 신규 마이그레이션 불필요.
- 스냅샷 지원에 스키마 변경(예: 이벤트 워터마크 컬럼 추가, 조회 인덱스, 스냅샷 채택 부기)이 필요하다고 판명되면 착수 시점 다음 가용 V 번호로 배정한다. 본 문서에서 번호를 고정하지 않는다. 배정 규칙은 `docs/contracts/백엔드_DB_설계.md` 마이그레이션 표의 로드맵(미배정) 행(번호는 착수 시점 다음 가용값으로 배정)이 소유한다.
- 2026-09-09 확인 시점의 최신 적용본은 V34__single_operator_approval.sql이다(`server/app/src/main/resources/db/migration/` 35개 파일, V0~V34). 적용된 V0~V34는 수정하지 않는다.

### 6-4. 리스크

- 엔진 핸드오프 레이스: 접수 닫힘·라이터 유휴 불변이 깨지면 stale·fresh 엔진 동시 사용 가능. 접수 게이트와 승격 상태기계로 강제하고 테스트 4-1 (C)로 고정.
- 승격 중 리스 만료: rebuild가 TTL을 초과하면 재승격 플래핑. rebuild를 renew와 분리(별도 워커)하고 스냅샷으로 rebuild 시간을 짧게 유지.
- 스냅샷 결정론 결함: codec가 비결정이면 로드 상태가 재생과 어긋난다. `state_checksum` 로드 검증 + 불일치 폴백으로 방어. `recovery_runs`의 fp 비교가 상시 감사.
- 이벤트 커서 정렬 오류(스냅샷 seq 기준): 잘못 정렬하면 prefix 대조가 오작동. 스냅샷에 event 워터마크 병기(권장)로 유도를 없앤다.
- 문서·코드 동시 정정 누락: C13A 스코프·RTO 측정 정의는 정본 문서가 코드와 어긋나면 멈추고 보고. 코드 변경과 문서 정정을 한 커밋으로 묶는다.
