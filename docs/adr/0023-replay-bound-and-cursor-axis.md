# ADR 0023. 재생 상한을 연속 물질화 경계로 바꾸고 커서 축을 인자에서 없앤다

- 상태: 채택
- 날짜: 2026-09-12
- 관련: 정본 `docs/contracts/백엔드_DB_설계.md` §5.5-a 개정(체크포인트 정의, 불변식 M 단서, 승격 생략 항 신설), `docs/product/복구_RTO_설계.md` §3-b 개정. ADR 0006 의 이벤트 워터마크 유도를 대체한다. ADR 0021 5단계가 만든 전제 변화를 뒤늦게 반영한다
- 코드: `ReplayService`, `EventJournalReader`, `JournalCheckpoint`, `FollowerCatchUp`, `EngineRecovery`, `SnapshotSource`, `SnapshotStore`, `PromotionCoordinator`, `WriterRouter`, `OrderBookImpl`
- 증거: `harness/evidence/materialization-holes-20260912/`

## 맥락

2026-09-12 감사에서 재생 경로 결함 둘이 나왔다. 둘 다 ADR 0021 5단계가 바꾼 전제를 코드와
정본이 따라가지 않아 생겼다.

**결함 A. 승격 재생 생략이 설치까지 생략했다.** 부팅 시 라우터가 복구본의 북을 참조로 나눠
담아 전역 엔진과 샤드 엔진이 같은 북을 공유하고 `lastAppliedSeq` 만 따로 들었다. 팔로워
구간에 공유 북이 전진해도 샤드 카운터는 부팅값에 멈춘다. 승격 시 전역 엔진이 head 에 닿아
있으면 생략 분기가 설치를 건너뛰고, 첫 명령이 갭으로 보여 이미 반영된 구간이 다시 적용된다.
재적용이 레스팅 잔량을 다시 깎으므로 자산 생성 경로다.

**결함 B. 팔로워 상태 일치 검증이 판정 대상 0 건으로 돌았다.** 재생 진입점이 이벤트 커서
시작점을 `long` 으로 받았고, 팔로워가 그 자리에 `max(event_seq)` 를 넘겼다. V36 이 `event_seq`
를 `market_id * 10^7 + market_seq` 로 바꾼 뒤 그 값이 명령 seq 보다 몇 자릿수 커져 커서가 첫
조회에서 소진됐다. 재생이 만든 이벤트가 전부 pending 으로 떨어졌고 아무도 보지 않았다.
팔로워가 리더와 갈려도 `STATE_DIVERGED` 가 설 수 없었다.

## 결정

### 1. 재생 상한을 연속 물질화 경계로 바꾼다

`EventJournalReader.materializedThrough(fromCmdSeq)` 가 `command_journal` 에서 이벤트가 없는
첫 명령을 찾아 그 바로 앞을 돌려준다. 구멍이 없으면 명령 head 다.

종전 `max(source_cmd_seq)` 를 쓸 수 없는 이유가 실측으로 확인됐다. 명령 저널은 접수 시점에
커밋되고 이벤트는 마켓별 라이터 열둘이 각자 정산 트랜잭션에서 적재하므로 `max` 아래에 구멍이
남는다. 600표본 10밀리초 간격, 봇 120 OPS 에서 구멍 최대 1, 구멍이 있던 표본 2 다.

빈도가 낮은 것이 안전을 뜻하지 않는다. 팔로워는 주기마다 대조하므로 언젠가 그 창을 밟고,
밟으면 거짓 불일치로 굳는다. 복귀 경로가 없다. 한 번이면 충분한 종류의 결함이다.

### 2. 커서 시작점을 인자에서 없앤다

진입점을 셋으로 가른다. `rebuild(engine)` 전량 재생, `verifyTo(engine, bound)` 대조 전용,
`replayTail(engine, upperCmdSeq)` 크래시 창 산출 전용이다. 셋 다 커서 시작점을 받지 않고
본체가 `engine.lastAppliedSeq()` 에서 유도한다.

**2026-09-14 주.** ADR 0022 2단계 병합에서 `replayTail` 이 삭제됐다(`ff41f18`). 운영 진입점은 마켓판이다.
대조는 `verifyToMarket(engine, marketId, checkpoints)`(ReplayService.java:297), 크래시 창 산출은
`replayTailMarket(engine, marketId, headOfMarket)`(:318)이고 둘 다 커서를 `engine.appliedSeq(marketId)`
에서 연다(:294). 커서 시작점을 인자에서 없앤 이 절의 결정은 그대로다. 전역 `rebuild`·`verifyTo` 는
운영 호출자 없이 시험만 쓴다.

전 호출자에서 그 자리에 올 수 있는 올바른 값이 하나뿐이었으므로, 인자를 지우면 축을 잘못
넣을 자리 자체가 사라진다. 이벤트 축 값을 내는 함수 넷도 지운다. `ReplayService.watermarkAt`,
`EventJournalReader.watermark`, `SnapshotSource.eventWatermark`, `JournalCheckpoint.eventSeq`
다. 재발 통로를 주석이 아니라 삭제로 닫는다.

`verifyTo` 가 pending 이 하나라도 나오면 던진다. 가드를 호출측이 아니라 서비스 안에 둔다.
호출측에 두면 다음 호출자가 안 부르고 지나간다. 실제로 그렇게 됐다. 복구 경로에는 그 검사가
있었고 팔로워 경로에는 없었다.

`replayTail` 은 이벤트 커서를 열지 않는다. 종전 2 단계는 커서가 1 단계 끝에서 소진돼 있다는
성질에 기댔는데, 그 성질이 성립하던 이유가 상한의 이벤트 축 값이 대역 파생으로 거대했기
때문이다. 우연을 구조로 바꾼다. (2026-09-14 주: 마켓판 `replayTailMarket` 도 이벤트 커서를 열지 않는다. `EventCursor.NONE` 이다(ReplayService.java:322))

### 3. 승격 생략은 재생만 생략한다

생략 분기가 `host.install(current)` 를 부른다. 라우터는 북을 복사해 담아 별칭을 끊고,
설치 이후 지연 생성되는 샤드는 0 이 아니라 설치 진행도에서 출발한다.

전량 재생으로 바꾸는 안을 버렸다. 팔로워 샤드는 게이트가 닫혀 전진하지 않으므로 거의 모든
승격이 전량 재생이 되고, 스냅샷 훅이 미배선이라 그 재생의 시작점이 0 이다. 명제 2(RTO 30초)가
이 변경 직후 미확인으로 떨어진다.

## 대안과 기각 사유

**커서 축만 바로잡는다.** 범위가 가장 작으나 상한을 `max` 로 둔 채 축을 살리면 팔로워가
구멍을 밟을 때마다 거짓 불일치를 낸다. 축을 고치는 것이 오히려 결함을 깨우는 형태다.
상한 재정의를 같은 변경에 묶어야 하고, 그러면 범위가 같아진다.

**상한을 좁히되 2 단계 커서를 엔진 위치에서 살린다.** 연속 경계 위에는 구멍 때문에 건너뛴
물질화 명령이 남을 수 있고, 그 행이 커서에 잡혀 짝이 어긋난다. 승격 복구에서 그것이 나면
승격 실패다.

## 영향

`command_journal` 을 함께 읽으므로 상한 질의가 한 표에서 두 표로 늘었다. `fromCmdSeq` 가
성능 하한이라 이미 재생한 구간을 다시 훑지 않는다.

2 단계 구간의 방출에는 크래시 창과 경계 위 이미 물질화된 명령이 섞인다. 후자는 재적재에서
`event_emission_registry` 멱등 스킵이 걸러낸다. 그 구간이 payload 대조를 받지 않고 넘어가는
것은 남는 한계다. 마켓 안에서는 커밋 순서가 seq 순서이므로 마켓별 tail 대조가 그 구멍을 닫는
다음 단계다. ADR 0022 의 마켓 벡터 체크포인트와 합류시킨다.

관측 축을 하나 늘렸다. `exchange.follower.catchup.verified_events` 다. 적용 건수만 보면 대조가
도는지 알 수 없다는 것이 결함 B 가 남긴 교훈이다.
