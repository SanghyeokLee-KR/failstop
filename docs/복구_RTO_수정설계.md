# 복구 RTO 수정 설계 (승격 재생 병목 해소)

> 정본 계약: `백엔드_DB_설계.md`(§5.4 리스·§5.5 복구·§6.3 스냅샷), `하네스_검증설계.md`(§5-1 RTO 체인·§3 E4/E6·§4-1 C13), `M4_하드닝설계.md`(§3-3(d) 승격 재생 병목·§1 심각도 카탈로그). 코드 정본은 `server/`. 컬럼·라인은 실제 파일 인용, 추측 금지.
> 판정 원칙: SQL 위반 행 0, 메트릭 임계, 벽시계 RTO. 본 문서는 설계 확정이며 코드 구현은 관리자 워크플로 완료 후 별도로 한다.
> 스타일: 개조식 평서문. em dash·en dash·이모지·도구 서명 금지. 완료 전 `scripts/check-style.ps1`.

---

## 1. 문제 정의

### 1-1. 확정 사실(무결성)

15분 데모 카오스(스파이크 중첩 리더 kill, `하네스_검증설계.md` §7 데모 프로파일)에서 무결성은 증명됐다.

- RPO=0: ACK 명령 전량 `command_journal` 존재(C16 0행).
- 자산합 0: `ledger_entries` 자산별 합 0(C01 0행).
- 불변식 14/14 green: 현 구현 카탈로그 전건(C01·C02·C05·C06·C07·C07P·C08·C13A·C13B·C14·C16·C17·C18·C19, `InvariantCheck.java`).

안전성 결함이 아니다. catch-up 경로는 멱등 스킵이고 원장은 무결하며 유실이 없다. 라이브니스·RTO 결함이다.

### 1-2. RTO 이중성(metric vs 실제 가시 체결)

| 축 | 값 | 근거 |
|---|---|---|
| metric RTO | 약 11.289s | `하네스_검증설계.md` §5-1 T5: `min(created_at) FROM event_journal WHERE event_seq > W_event AND event_type='TRADE'` |
| 실제 가시 체결 재개 | 약 4~8분 | 승격 후 신규 명령의 체결 커밋·WS 게시가 실제로 관측된 벽시계 시각 |
| 목표 | < 30s | `M4_하드닝설계.md` §3-2, API §7-4 |

metric RTO가 실측을 은폐한다. 근인은 T5의 `created_at`이 벽시계 커밋 시각이 아니라 `engine_ts` 파생 논리 시각이라는 점이다(§2-4). 판정 = FAIL(실제 30s 초과).

### 1-3. 트리거 조건

스파이크(저널 27k+ 명령 누적) 이후 리더 kill. 오래 팔로우한 스탠바이가 승격 주체일 때만 발현한다. 저널이 작을 때(부팅 직후 kill)는 재생량이 작아 은폐되지 않는다.

---

## 2. 근인(코드 라인 확정)

### 2-1. 승격이 recover/rebuild에 미배선(gain 콜백 부재)

- `LeaderLeaseService`는 상실 리스너만 보유한다. `onLeadershipLost`(LeaderLeaseService.java:74), `lossListeners`(:39). 승격 통지(onLeadershipGained 류) 인터페이스가 없다.
- 승격은 `tick()`의 `tryAcquire()`가 조용히 수행한다(LeaderLeaseService.java:157~168, :91~107). epoch를 세팅할 뿐 어떤 콜백도 발화하지 않는다.
- 배선측도 상실만 건다. `EngineConfig.java:133` `lease.onLeadershipLost(loop::halt);`. gain 대응 배선이 없다.
- `recover`는 앱 기동 1회만 호출된다. `EngineConfig.java:141`의 `engineRuntimeLifecycle.start()` 내부 `recovery.recover(engine);`. 부팅 이후 승격 시점에는 재생·재구성이 다시 돌지 않는다.

결과: 승격해도 스탠바이의 인메모리 엔진은 부팅 시점 seq에 머문다.

### 2-2. 스탠바이 엔진이 stale lastAppliedSeq로 열림

- `MatchingEngineImpl.lastAppliedSeq`는 마지막 apply한 명령 seq다(MatchingEngineImpl.java:18, :27, :32). 스탠바이는 리더가 아니라 큐에 명령이 공급되지 않으므로 부팅 후 이 값이 전진하지 않는다.
- 부팅 시 저널이 작았다면 lastAppliedSeq는 0 또는 소수다. 스파이크 동안 리더가 27k 명령을 적재해도 스탠바이 엔진은 따라가지 않는다.
- 승격 후 게이트웨이 접수가 열려 첫 신규 명령(seq 약 27k+)이 큐에 들어오면, `SingleWriterLoop.process`가 `command.seq() > last + 1`을 관측(SingleWriterLoop.java:136)하고 `catchUpTo(command.seq())`를 호출한다(:137).

### 2-3. catch-up의 sink가 인메모리 rebuild가 아니라 정산 경로

- `catchUpTo`는 `applyAndSink`로 각 명령을 처리한다(SingleWriterLoop.java:153, :145~160).
- `applyAndSink`는 `engine.apply` 후 방출 이벤트를 `eventSink.accept`로 넘긴다(SingleWriterLoop.java:162~167).
- 이 `eventSink`는 정산 경로다. `EngineConfig.java:110~111`에서 `events -> marketDataPublisher.publishSettled(settlementService.appendAndSettle(events))`로 배선됐다.
- `appendAndSettle`는 명령마다 원격 PG 왕복 트랜잭션 1개다(SettlementService.java:106~131). 트랜잭션 내부:
  - `assert_current_leader(holder, epoch)`(SettlementService.java:134~137)
  - `leader_lease FOR SHARE` epoch 확인(:139~147, `SELECT_EPOCH_SQL` :51~56)
  - `event_emission_registry` count로 멱등 판정(:149~157). 이미 적재분이면 `registered == events.size()`로 전량 스킵.
  - BEGIN·COMMIT(`tx.execute`, :120). PG는 주문당 fsync 구성(`하네스_검증설계.md` §2-4).

catch-up 배치가 27k건이면 27k개의 직렬 원격 트랜잭션이 toxiproxy 경유로 순차 커밋된다. 전부 멱등 스킵이라 상태를 바꾸지 않지만, 각 트랜잭션이 다중 왕복 + fsync 커밋이다. RTO = O(저널 크기) × 정산 왕복 지연. 이것이 4~8분의 정체다.

대조군: `ReplayService.rebuild`는 같은 27k를 인메모리로만 처리한다. `engine.apply`(순수, ReplayService.java:64) + `event_journal` 배치 대조(:57, :148~168). 쓰기 트랜잭션 0, 커밋 0, 정산 0. 500행 배치 읽기라 왕복 수가 O(저널/500)이고 fsync가 없다. 크래시 창 pending만 정산한다(EngineRecovery.java:41~73). 즉 올바른 sink는 이미 존재하며, 승격 경로가 이 sink 대신 정산 sink를 타는 것이 결함이다.

### 2-4. metric RTO 은폐 메커니즘

- `event_journal.created_at`은 `engine_ts` 파생값이다. `SettlementService.insertEvent`가 `toUtc(event.engineTs())`로 삽입한다(SettlementService.java:510~519). 재생 대조도 `created_at == engine_ts`를 강제한다(ReplayService.java:109).
- `engine_ts`는 게이트웨이가 접수 시점에 박제한다(`OrderGateway`, 접수 박제 계약 P0-4). 즉 명령이 수락된 논리 시각이다.
- 승격 후 첫 신규 명령의 `engine_ts`는 접수 시각(약 승격+11s)에 박제됐다. 그러나 그 명령의 `event_journal` 행은 27k catch-up이 끝난 뒤(약 4~8분 후) 커밋된다. §5-1 T5의 `min(created_at)`은 박제된 이른 논리 시각을 읽어 11.289s로 보고한다. 실제 커밋·게시 벽시계는 분 단위 뒤다.
- `event_journal`에는 커밋 벽시계 컬럼이 없다. 따라서 T5를 `created_at`으로 재면 구조적으로 catch-up 지연이 소거된다.

---

## 3. 수정 설계

### 3-a. 승격 gain 훅 배선 + 접수 개방 전 인메모리 선복원

핵심. 접수를 열기 전에 인메모리 rebuild로 엔진을 head까지 끌어올린다. 대규모 delta를 정산 sink로 태우지 않는다.

#### 상태기계

```
FOLLOWER  --(리스 만료 감지·tryAcquire 성공, epoch+1)-->  PROMOTING
PROMOTING --(fresh 엔진 rebuild 완료 + 크래시 창 재정산 완료)-->  ACCEPTING
ACCEPTING --(fencing 거부·정산 실패·리스 상실)-->  DEMOTED(self-shutdown)
```

- 감지·승격: `tryAcquire` 성공 시 gain 콜백 발화. `LeaderLeaseService`에 `onLeadershipGained(Consumer<LeaderIdentity>)` 리스너 목록을 추가하고 `tryAcquire`가 epoch 세팅 직후 발화한다(현 `markLost` 대칭). 승격 성공 경로는 부팅 `start()` 최초 획득과 런타임 tick 승격 둘 다다. 부팅 경로는 이미 `recover`가 도는 lifecycle이라 이중 실행을 막는 가드가 필요하다(아래).
- rebuild: gain 콜백이 접수를 닫은 채 **fresh 엔진**을 만들어 `recover`(스냅샷 로드 + tail 재생 + 크래시 창 재정산)를 돌린다.
- 접수 개방: rebuild 완료 후에만 접수 게이트를 연다.

#### fresh 엔진 필수(정합성 제약)

stale 스탠바이 엔진에 `rebuild`를 재호출하면 안 된다. `ReplayService.rebuild`의 이벤트 커서는 `event_seq` 0부터 시작하는데(ReplayService.java:51, JournalEventCursor `lastSeq` 초기값 0, :148~168), 명령 커서는 `engine.lastAppliedSeq()` 초과분부터 읽는다(:57). 엔진이 이미 부팅 seq만큼 전진해 있으면 명령은 부팅 seq+1부터, 이벤트는 event_seq 1부터 대조돼 prefix가 어긋나고 `ReplayMismatchException`이 뜬다(:100~119). 따라서 승격 rebuild는 반드시 새 `MatchingEngineImpl`에 대해 0(또는 스냅샷 seq)부터 수행한다.

#### 루프로의 엔진 핸드오프

- 승격 sequence는 fresh 엔진을 rebuild한 뒤 단일 라이터 루프가 이 엔진을 쓰도록 설치한다. 접수가 닫힌 PROMOTING 구간이라 동시 apply가 없어 스왑이 안전하다.
- 구현 선택지(구현 워크플로에서 확정): (i) `SingleWriterLoop.engine`을 volatile로 두고 접수 닫힘 상태에서만 호출 가능한 `installEngine`을 추가, (ii) halt 후 fresh 엔진으로 루프 재시작. 정확성 우선이면 (i)가 큐·스레드 재생성을 피해 단순하다. 어느 쪽이든 "접수 닫힘 + 라이터 유휴" 불변을 문서화한다.

#### 접수 게이트(readiness 연동)

- 현재 접수는 `EngineBridge.submit`이 `loop.isRunning()`만 검사하고(EngineBridge.java:33) append는 `lease.requireLeader()`로 리더십만 본다(CommandJournalAppender.java:42). 승격 즉시 epoch가 세팅되므로 rebuild 완료 전에도 append·offer가 통과해 결함 경로가 재발한다.
- 접수 개방 여부를 리더십과 분리한 명시 게이트로 둔다. `isLeader() == true`여도 `acceptingCommands == false`인 PROMOTING 구간을 만든다. `EngineBridge.submit`(과 append 진입점)이 이 게이트를 확인해 미개방 시 503(NotLeaderException 계열 재사용 또는 신규 NotReadyException)으로 거부한다. 봇·k6는 503을 재시도로 흡수한다(`SimConfig` 주석, 접수 보류 계약).
- Spring readiness 상태와 연동한다. PROMOTING = `REFUSING_TRAFFIC`, ACCEPTING = `ACCEPTING_TRAFFIC`. 리더 인지 LB(`M4_하드닝설계.md` §3-4)가 readiness DOWN 노드를 제외하므로 rebuild 중 외부 트래픽 유입을 원천 차단한다.

#### 승격 중 리스 유지

- rebuild는 lease 스케줄러 스레드가 아니라 별도 승격 워커에서 돌린다. lease tick(TTL 10s 기본, tick 3s, `EngineProperties`)이 rebuild 동안 계속 renew해야 만료·플래핑이 없다. 27k 인메모리 rebuild는 배치 읽기 O(저널/500) 왕복이라 지연 주입 하에서도 수 초 내지만, TTL 의존을 없애려 rebuild를 renew와 분리한다.
- rebuild 실패(ReplayMismatch·재정산 실패)는 승격 거부·self-shutdown이다(무음 유실 금지, 현 `EngineRecovery`·`LoopFatalHandler` 계약 유지).

#### catch-up의 잔여 역할 축소

- `SingleWriterLoop.catchUpTo`는 제거하지 않는다. 큐 유실 같은 소규모 gap(수 건)의 방어선으로 유지한다(EngineRuntimeIntegrationTest `seqGapIsRecoveredByCatchUp` 커버).
- 단 승격 delta는 절대 catch-up이 메우지 않는다. 접수 개방 시점에 엔진이 head에 있으므로 gap은 항상 소규모다. 방어적으로 catch-up gap이 임계(예: CATCH_UP_BATCH 초과)를 넘으면 접수 게이트가 우회된 상태로 보고 fail-fast하는 가드를 검토한다.

### 3-b. 주기적 스냅샷(catch-up 상한을 저널 크기와 분리)

3-a의 인메모리 rebuild는 스파이크(27k)에서는 빠르지만 장기 soak(수백만 명령)에서는 다시 O(저널)로 커진다. 스냅샷이 tail 재생량을 상한한다.

- 저장소: 기존 `snapshots` 테이블(DB 설계 §6.3, `백엔드_DB_설계.md`:1032~1050). 신규 DDL 불필요. 컬럼: `last_applied_seq`, `state`(BYTEA), `state_checksum`, `engine_version`, `config_hash`, `format_version`, `fencing_epoch`, `created_at`. append-only(트리거+REVOKE, :1047~1050).
- 주기·트리거: 리더가 N 명령마다 또는 T초마다(둘 중 먼저) 스냅샷 1건 기록. 정산 핫패스를 블록하지 않도록 매칭 코어 밖 별도 스레드가 특정 seq 경계의 엔진 상태를 직렬화한다. 경계 seq에서의 인메모리 상태 정합은 단일 라이터라 자연 확보된다(그 seq까지 apply 완료 시점 캡처). 정확 주기 값은 캘리브레이션에서 확정(`M4_하드닝설계.md` §3-3(d) 연계).
- 직렬화: `MatchingEngineImpl`의 마켓별 `OrderBookImpl`(bids·asks·ordersById TreeMap, `OrderBookImpl.java:37~41`)을 결정론 바이트로 인코딩하는 codec 신설. 순회 순서가 정의된 자료구조만 쓰므로(TreeMap·ArrayDeque, 코어 순수성 T3-A) 인코딩이 결정론이다. `state_checksum`은 기존 `stateChecksum()`(MatchingEngineImpl.java:38~44)과 동일 규약으로 병기해 로드 시 무결 검증한다.
- 복원(스냅샷 + 델타): 승격 rebuild가 최신 유효 스냅샷을 로드(DB 설계 §5.5 STEP1, :1753~1755)해 fresh 엔진에 역직렬화한 뒤, `command_journal`에서 `seq > snapshot.last_applied_seq`만 tail 재생한다(STEP2, :1757~1767).
  - 이벤트 커서 정렬: `ReplayService`의 이벤트 커서는 현재 event_seq 0부터다. 스냅샷 로드 시 커서를 `source_cmd_seq > snapshot.last_applied_seq`의 첫 이벤트 event_seq부터 시작하도록 확장한다. 스냅샷이 대응 event 워터마크를 함께 기록하거나(권장), 로드 시 `SELECT min(event_seq) FROM event_journal WHERE source_cmd_seq > :snap_seq`로 유도한다. 이 확장이 스냅샷 지원의 유일한 `ReplayService` 변경점이다.
  - 채택 판정: `engine_version`·`config_hash`·`format_version` 불일치 스냅샷은 무시하고 이전 스냅샷 또는 seq 0 재생으로 폴백(DB 설계 §5.5 STEP1, :1030~1031). `state_checksum` 불일치도 폐기가 아니라 무시·폴백(테이블 append-only, 삭제 금지).
- 단일 정본 규율: 스냅샷은 저널 파생이다. 원천은 `command_journal`·`event_journal`이고 스냅샷은 재생 시작점 최적화일 뿐이다(DB 설계 §1.1 ① 복구 원천, :16). 스냅샷 부재·손상 시 항상 저널 전량 재생으로 동일 결과에 도달하므로 단일 정본이 유지된다.

### 3-c. C13A 스코프 AUTHORITATIVE_ONLY 수정

- 결함: `C13A_ORDER_MATERIALIZED`가 `Scope.SCHEDULED`다(InvariantCheck.java:63). 이 체크는 `command_journal`의 모든 NEW 명령에 대응 `orders` 행 존재를 요구한다(:63~67).
- 오탐 메커니즘: NEW 명령은 append 시점에 커밋되지만, `orders` 행은 이후 정산 sink가 ACCEPTED 이벤트를 처리할 때 INSERT된다(SettlementService.java:225~240 `settleAccepted`). append와 정산 커밋 사이의 비동기 창에서 명령은 있으나 주문 행이 없다. 라이브 부하·스파이크에서 큐 백로그가 이 창을 키워 C13A가 대량 위반으로 오탐한다.
- 수정: `C13A_ORDER_MATERIALIZED`를 `Scope.AUTHORITATIVE_ONLY`로 변경(1행). 상시 스케줄·soak에서 제외하고, 정지 시점·실험 사후 권위 run(큐 드레인 상태)에서만 평가한다. 근거는 기존 C16·C19의 AUTHORITATIVE_ONLY 사유와 동일하다("라이브 프로젝션 지연 오탐 차단", InvariantCheck.java:12~13).
- 커버리지 손실 없음: 역방향 C13B(orders → command_journal, InvariantCheck.java:68~72)는 정산 이후에만 orders가 존재하므로 프로젝션 지연 오탐이 없다. C13B는 `SCHEDULED` 유지. 유실 주문(NEW without order)은 큐 드레인된 권위 run에서 C13A가 잡는다.
- E3(구 번호, 정본 E6) 스파이크 게이트의 스퍼리어스 FAIL: 동일 근인이다. 스파이크 창에서 상시 C13A가 프로젝션 지연을 위반으로 집계해 게이트를 오탈락시킨다. C13A를 AUTHORITATIVE_ONLY로 옮기면 스파이크 게이트도 정리된다. 데모 15분 스택은 구 잠정 번호 E3 = 정본 E6(스파이크+kill 복합, `M4_하드닝설계.md` §0-3)을 가리킨다.
- 문서 동기화(구현 시 함께): `M4_하드닝설계.md` §1(CRITICAL·SCHEDULED 열거)·§2-2(soak SQL의 `check_name IN` 목록)·§4-2(알람 라우팅 critical 열거)가 C13A를 SCHEDULED로 나열한다. C13A를 CRITICAL·AUTHORITATIVE_ONLY로 이동해 정정한다. 정본이 문서이므로 코드 변경과 문서 정정을 한 커밋으로 묶는다.

### 3-d. 핫 스탠바이(연속 적용) 검토·판정

- 대안: 스탠바이가 `command_journal`을 실시간 tail해 인메모리 엔진만(정산 없이) 연속 apply하는 웜 팔로워. 승격 시 delta가 0에 수렴해 rebuild가 사실상 불필요.
- 판정: 현 단계 도입하지 않는다.
  - 스냅샷 + 빠른 rebuild가 목표(RTO<30s)를 충족한다. 27k 인메모리 rebuild는 수 초, 스냅샷으로 tail을 상한하면 더 짧다.
  - 웜 팔로워는 상태 전진 경로를 하나 더 만든다. 이 경로가 재생과 비트동일해야 하고(결정론 표면 확대), 팔로워 이벤트 대조·발산 감지를 rebuild와 별도로 유지해야 한다. 정확성 > 편의성 원칙에서 표면 확대는 비용이다.
  - 단일 코드 경로(recover/rebuild) 유지가 검증·감사를 단순화한다.
- 재검토 트리거: 최대 스냅샷 간격에서 측정한 승격 rebuild delta 벽시계가 예산에 근접(예: 10s 초과)하면 웜 팔로워를 재평가한다. `recovery_runs.duration_ms`로 상시 계량한다.

---

## 4. 회귀 테스트 설계

CI 사각: 어느 통합테스트도 "빈(또는 stale) 엔진 + 대규모 gap을 정산 sink로 catch-up" 경로를 밟지 않는다. `EngineRuntimeIntegrationTest.seqGapIsRecoveredByCatchUp`은 gap이 2건이고, 더 결정적으로 루프를 열기 전에 `replayService.rebuild(runningEngine)`로 엔진을 미리 head에 올린다(EngineRuntimeIntegrationTest.java:198~199). 즉 결함을 은폐하는 조건에서만 catch-up을 검증한다.

### 4-1. 신규 통합테스트: 승격 재생 경로(대규모 gap)

- 이름(안): `PromotionRecoveryIntegrationTest` (또는 `EngineRuntimeIntegrationTest`에 케이스 추가). Testcontainers postgres:17.
- 준비: 초기 리더로 대규모 저널을 만든다. NEW·매칭 명령을 N건(스파이크 재현 위해 27k+, CI 축약본은 수천) 접수·정산해 `command_journal`·`event_journal`·`orders`를 채운다.
- 승격 시뮬레이션: 스탠바이 부팅을 재현한다. lastAppliedSeq=0(또는 소수)인 fresh `MatchingEngineImpl` + 접수 닫힘 루프. 초기 리더 강등 후 승격 콜백을 발화한다.
- 계약 단언:
  - (A) 정산 sink 호출 계량. 계수 sink를 주입해 승격 rebuild가 `appendAndSettle`를 O(pending)회만 호출하고 O(저널)회 호출하지 않음을 단언한다. 클린 승격이면 pending=0이라 호출 0회. 이것이 정산 catch-up 회귀의 직접 방지선이다.
  - (B) 엔진 head 도달. rebuild 후 `engine.lastAppliedSeq() == max(command_journal.seq)`, `stateChecksum` == 초기 리더 종료 상태와 일치.
  - (C) 접수 게이트 순서. rebuild 완료 전 `submit` 시도는 503(NotReady), 완료 후 첫 신규 명령은 통과.
  - (D) 벽시계 RTO. 아래 4-2 측정 계약으로 승격~가시 첫 체결 < 예산.
- 스냅샷 케이스(3-b 구현 후): 스냅샷 존재 시 tail 재생 구간이 `seq > snapshot.last_applied_seq`로 축소됨을 `recovery_runs.replayed_from_seq/to_seq`로 단언. 손상 스냅샷 주입 시 이전 스냅샷·seq 0 폴백 단언.

### 4-2. RTO 측정 계약(engine_ts 금지, 가시 첫 체결 기준)

- 문제: §5-1 T5가 `created_at`(= engine_ts 파생)을 쓰면 catch-up 지연이 소거된다(§2-4).
- 계약: RTO는 승격(T0)부터 **가시 첫 체결**(post-promotion 신규 명령의 TRADE가 실제로 커밋·게시된 벽시계 시각, T5_wall)까지로 잰다. `engine_ts`·`created_at`을 T5 산정에 쓰지 않는다.
- 측정점(택1 또는 병행):
  - 서버 벽시계 프로브: 테스트가 승격 후 신규 주문을 넣고, 그 주문의 TRADE 정산 커밋(또는 WS 게시)이 관측된 `System.nanoTime` 벽시계를 T5_wall로 기록. Awaitility로 대기.
  - 외부 관측: k6 마지막 2xx~최초 체결 응답 갭(§5-1 T4·T5 외부 관측 병기). 데모·E6에서 3원 대조.
- 기존 SQL은 논리 교차검증으로만 유지한다. `event_journal`에 커밋 벽시계 컬럼이 없으므로(append-only 핫패스 오염 회피 위해 추가하지 않음), 정본 판정은 관측 벽시계다. `하네스_검증설계.md` §5-1 T5의 정의를 "created_at 기준"에서 "관측 벽시계 기준(created_at은 논리 교차검증)"으로 정정 권고한다.

### 4-3. 편입

- `M4_하드닝설계.md` §1-2 E4·E6 회귀 연계에 본 테스트를 추가한다. E4 판정 RTO<30s·`recovery_runs.matched=true`가 이 경로를 실제로 밟도록 게이트한다.

---

## 5. 수용 기준

- 스파이크 조건(저널 27k+ 명령 누적) 리더 kill에서 실제 가시 체결 재개 RTO < 30s(벽시계, §4-2 계약).
- RPO=0 유지: C16 0행, ACK 명령 전량 `command_journal` 존재.
- 불변식 14/14: C01·C02·C05·C06·C07·C07P·C08·C13A·C13B·C14·C16·C17·C18·C19 위반 0(권위 run). C13A는 AUTHORITATIVE_ONLY로 평가.
- `recovery_runs.matched=true`, epoch 정확히 +1, 중복 체결 0.
- 승격 rebuild가 `appendAndSettle`를 O(pending)회만 호출(정산 catch-up 재발 0, §4-1 (A)).
- soak 2h에서 C13A 프로젝션 지연 오탐 0(SCHEDULED 미평가), E6 스파이크 게이트 스퍼리어스 FAIL 0.

---

## 6. 구현 순서·영향 범위·리스크

### 6-1. 순서(작은 단위 완결)

1. C13A 스코프 수정(3-c). 1행 + 문서 3곳 정정. 최소 리스크, 오탐 즉시 제거. `InvariantVerifierIntegrationTest`가 스코프 필터를 검증하도록 확장.
2. 승격 gain 훅 + fresh 엔진 rebuild-before-accept + 접수 게이트(3-a). 스키마 무변경. 스파이크(27k)에서 RTO<30s 달성. 회귀 테스트 4-1 동반.
3. 주기 스냅샷(3-b). 스냅샷 codec + writer + 스냅샷 로드 rebuild 경로. 장기 soak에서 tail 상한. 스냅샷 케이스 테스트 동반.
4. RTO 측정 계약 정정(4-2)과 문서 동기화. E4·E6 게이트에 벽시계 RTO 반영.

각 단계는 컴파일·테스트 통과 상태로만 멈춘다.

### 6-2. 영향 범위

- 변경: `LeaderLeaseService`(gain 리스너), `EngineConfig`(gain 배선·접수 게이트·스냅샷 빈), `EngineBridge`/append 진입점(접수 게이트 확인), `SingleWriterLoop`(엔진 핸드오프·catch-up 가드), `EngineRecovery`/`ReplayService`(스냅샷 로드·이벤트 커서 정렬), 신규 스냅샷 codec·writer, `InvariantCheck`(C13A 스코프).
- 코어 순수성 유지: 스냅샷 codec는 순수 Java 결정론 인코딩. 엔진 모듈에 Spring 의존 금지. 현재 시각·랜덤·비결정 순회 금지.
- 원장 게시는 `post_ledger_transaction` 단일 경로 유지. 승격 rebuild는 크래시 창 pending만 정산(기존 `appendAndSettle` 경로 재사용), 원장 직접 DML 없음.

### 6-3. 마이그레이션

- 3-b 스냅샷은 기존 `snapshots` 테이블(V1)을 그대로 쓴다. 기본 계획은 신규 마이그레이션 불필요.
- 스냅샷 지원에 스키마 변경(예: 이벤트 워터마크 컬럼 추가, 조회 인덱스, 스냅샷 채택 부기)이 필요하다고 판명되면 다음 가용 버전으로 번호를 예약한다(현재 최신 적용본 V11__sim_run_ordinal.sql, 다음 가용 V12). 적용된 V0~V11은 수정하지 않는다.

### 6-4. 리스크

- 엔진 핸드오프 레이스: 접수 닫힘·라이터 유휴 불변이 깨지면 stale·fresh 엔진 동시 사용 가능. 접수 게이트와 승격 상태기계로 강제하고 테스트 4-1 (C)로 고정.
- 승격 중 리스 만료: rebuild가 TTL을 초과하면 재승격 플래핑. rebuild를 renew와 분리(별도 워커)하고 스냅샷으로 rebuild 시간을 짧게 유지.
- 스냅샷 결정론 결함: codec가 비결정이면 로드 상태가 재생과 어긋난다. `state_checksum` 로드 검증 + 불일치 폴백으로 방어. `recovery_runs`의 fp 비교가 상시 감사.
- 이벤트 커서 정렬 오류(스냅샷 seq 기준): 잘못 정렬하면 prefix 대조가 오작동. 스냅샷에 event 워터마크 병기(권장)로 유도를 없앤다.
- 문서·코드 동시 정정 누락: C13A 스코프·RTO 측정 정의는 정본 문서가 코드와 어긋나면 멈추고 보고. 코드 변경과 문서 정정을 한 커밋으로 묶는다.
