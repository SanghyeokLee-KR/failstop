# ADR 0006. 복구 재생을 저널 전량 재생에서 스냅샷 로드 후 tail 재생으로 바꾼다

- 상태: 채택
- 날짜: 2026-07-23
- 결정자: 아키텍처 담당, backend-engineer

## 배경

스파이크 중첩 리더 kill(저널 27k+ 명령 누적, `docs/product/복구_RTO_설계.md`:33) 데모에서 복구 라이브니스가 목표를 벗어났다.

- 실제 가시 체결 재개 약 4~8분. 목표는 RTO 30초 미만(`docs/product/복구_RTO_설계.md`:26~27, `docs/architecture/M4_하드닝설계.md`:87).
- 보고 지표 metric RTO는 약 11.289s였다(:25). T5의 `created_at`이 벽시계 커밋 시각이 아니라 `engine_ts` 파생 논리 시각이라 실측을 은폐했다(:29, :73).
- 무결성 결함이 아니다. RPO=0(C16 0행), 자산합 0(C01 0행), 그 회차에서 평가한 불변식 14종 전건 green 으로 안전성은 증명됐다(카탈로그 총수는 27이고 이 회차가 그 부분집합을 돌렸다)(:15~17). 라이브니스 결함이다.

직접 근인은 승격 경로가 인메모리 rebuild 대신 정산 sink를 타면서 27k건을 직렬 원격 트랜잭션으로 커밋한 것이고(:65), 이 근인은 `PromotionCoordinator`의 gain 훅·fresh 엔진 선복원(설계 §3-a)이 b3ebf07 이전에 해소했다(`git show b3ebf07^:server/app/src/main/java/kr/exchange/engine/PromotionCoordinator.java`:145~149).

남은 것은 구조 문제다. 인메모리 rebuild도 저널 길이에 O(N)이라 장기 soak(수백만 명령)에서 목표를 다시 깬다(`docs/product/복구_RTO_설계.md`:123). `docs/architecture/M4_하드닝설계.md`:25가 같은 한계를 미체크 항목으로 명시한다.

스냅샷 자산은 이미 있었으나 배선이 없었다. 감사 기록은 `SnapshotCodec`·`loadSnapshot`·`snapshots` 테이블이 테스트에서만 참조되고 main INSERT/SELECT 0건, writer 스케줄러도 없는 죽은 코드라고 남겼다(2026-07 자가 감사(폐기):74. archive는 이력이며 계약 근거가 아니다).

## 결정

승격·복구 시 최신 채택 가능 스냅샷을 로드하고 `snapshot_seq+1`부터 tail만 재생한다(b3ebf07).

- 캡처 지점: `SingleWriterLoop`에 명령 경계 훅 `SnapshotHook`을 둔다(`server/engine/src/main/java/kr/exchange/engine/core/SingleWriterLoop.java`:41~42, 호출은 `applyAndSink` 말미 :236~238). 라이터 스레드는 정책 판정과 결정론 인코딩만 하고 DB 영속화는 별도 워커가 맡는다(`server/app/src/main/java/kr/exchange/snapshot/SnapshotCapture.java`:23~26). 훅은 예외를 밖으로 던지지 않고, 큐가 차면 드롭하고 워터마크만 전진시킨다(:28~37). (2026-09-14 주: 이 훅은 ADR 0022 7단계 전까지 접수를 받지 않는 레거시 단일 루프 빈에만 걸려 캡처가 한 번도 돌지 않았다. 7단계가 마켓별 샤드마다 캡처를 걸고 적재 트랜잭션에 리더 검증을 넣었다. 위 행 인용은 그 전 판이다)
- 캡처 주기: 직전 캡처 이후 명령 10만건 또는 60초 중 먼저 도달하는 쪽. 큐 용량 8(`server/app/src/main/java/kr/exchange/config/SnapshotProperties.java`:39~46).
- 로드: `EngineRecovery.recover`가 `loadLatestAdoptable()`로 fresh 엔진에 스냅샷을 적재한다. **이벤트 커서 유도는 ADR 0023 이 대체했다.** 커서 시작점을 세우지 않고 재생이 `engine.lastAppliedSeq()` 에서 연다(`server/app/src/main/java/kr/exchange/engine/EngineRecovery.java`:115~121). (2026-09-14 주: ADR 0022 2단계 병합으로 로드는 `loadLatestAdoptablePerMarket()` 가 마켓마다 최신 채택본을 고르고(`EngineRecovery.java`:166) 엔진은 `loadMarketSnapshot` 으로 마켓별 커서를 세운다(`MatchingEngineImpl.java`:85). 위 행 인용은 병합 전 판이다)
- **이 오버로드는 ADR 0023 이 삭제했다(2026-09-12).** 이하는 폐기 시점의 서술이다. 재생: `ReplayService.rebuild(engine, eventStartSeq)` 오버로드. 명령 커서는 `engine.lastAppliedSeq()` 초과분이고(`server/app/src/main/java/kr/exchange/journal/ReplayService.java`:72), 이벤트 커서는 W로 초기화해 W+1부터 읽는다(:66. 계약은 javadoc :56~64). `eventStartSeq=0`이면 종전 전량 재생 경로 그대로다(:52~54).
- 채택 게이트: `format_version`·`engine_version`·`config_hash`가 런타임 값과 일치하고, 디코딩한 마켓별 북의 `state_checksum`이 기록값과 일치해야 채택한다. 하나라도 어긋나면 그 세트 전체를 폐기하고 다음(더 낮은) 세트로, 없으면 seq 0 전량 재생으로 폴백한다(`server/app/src/main/java/kr/exchange/snapshot/SnapshotStore.java`:24~26, :96~117). (2026-09-14 주: ADR 0022 2단계 병합으로 채택이 마켓 단위다. 어긋나면 그 마켓의 그 seq 만 폐기하고, 한 마켓의 손상이 다른 마켓의 채택을 끌어내리지 않는다(`SnapshotStore.java`:17~24). 위 행 인용은 병합 전 판이다)
- **이벤트 워터마크 W는 ADR 0023 이 폐기했다(2026-09-12).** V36 의 마켓 대역 파생이 아래 전제(`event_seq` 오름차순 gapless 배정)를 깼고, 그 유도값이 커서 시작점 자리에 들어가 대조가 판정 대상 0 건으로 돌았다. 이하는 폐기 시점의 서술이다. `SELECT COALESCE(max(event_seq), 0) FROM event_journal WHERE source_cmd_seq <= :snap_seq`로 유도한다(`SnapshotStore.java`:124~131). 설계 §3-b는 스냅샷에 event 워터마크를 병기하는 쪽을 권장하고 `min(event_seq) WHERE source_cmd_seq > :snap_seq` 유도를 대안으로 둔다(`docs/product/복구_RTO_설계.md`:129, 재확인 :222). 본 결정은 스키마 무변경을 위해 유도를 택했고 유도식만 위 형태로 바꿨다. 커서가 lastSeq를 W로 들고 `readAfter(W)`가 W+1부터 읽으므로 설계가 지정한 시작점과 같은 경계다. event_seq가 source_cmd_seq 오름차순 gapless 배정이라는 전제에 의존하며, 커서를 W로 두는 편이 선두 결번 검사와 맞물린다(`ReplayService.java`:161~165, :180~186).
- 재생 단위: 마켓별이 아니라 전 마켓 통합·전역 seq 오름차순 단일 패스(`docs/contracts/백엔드_DB_설계.md` §5.5 STEP2:2032~2037, `docs/architecture/아키텍처_개관.md`:168~169). 설계의 마켓별 `last_applied_seq` skip 규칙은 본 구현에서 축퇴한다. 한 세트를 단일 `last_applied_seq`로 적재하고(`SnapshotStore.java`:53~62) 그 seq로 세트를 로드하므로(:66~69) 마켓별 경계가 없고, 재생은 전역 `engine.lastAppliedSeq()` 초과분만 읽는다(`ReplayService.java`:72). (2026-09-13 주: 재생 단위는 ADR 0022 2단계에서 마켓별 2상으로 바뀐다. 계약은 `docs/contracts/백엔드_DB_설계.md` v2.44 §5.5-a·§5.5 STEP2 가 갖고 그 코드는 아직 main 에 없다) (2026-09-14 주: 그 코드가 ADR 0022 2단계 병합으로 main 에 들어왔다)

RTO 상한이 저널 크기 O(N)에서 O(tail)로 바뀐다.

## 근거

- 저널은 단조 증가하고 스냅샷 없는 재생은 그 길이에 선형이다. 목표 위반 시점이 코드 변경 없이 시간만으로 도래한다. tail 상한만이 RTO를 저널 크기와 탈동조한다(`docs/product/복구_RTO_설계.md`:123).
- 스냅샷은 저널 파생이지 정본이 아니다. 원천은 `command_journal`·`event_journal`이고 스냅샷은 재생 시작점 최적화다(`docs/product/복구_RTO_설계.md`:131 단일 정본 규율). 채택 세트가 없으면 로더가 empty를 반환해 seq 0 전량 재생으로 같은 상태에 도달한다(`SnapshotStore.java`:65~77, `ReplayService.java`:52~54). 손상·구식을 삭제가 아니라 무시로 처리하는 것도 같은 규율이다(`docs/contracts/백엔드_DB_설계.md`:1157~1158). 단 DB 설계 §1.1:16은 `snapshots`를 `command_journal`·`event_journal`과 같은 ① 복구 원천 계층에 넣는다. 계층 분류가 어긋나며 본 ADR은 파생 취급을 따른다.
- 등가성이 테스트로 증명된다. `SnapshotEquivalenceIntegrationTest`가 3마켓·부분체결·시장가 IOC·취소·거부·FIFO 저널(`server/app/src/test/java/kr/exchange/snapshot/SnapshotEquivalenceIntegrationTest.java`:313~330 명령 스크립트, 마켓 3종 단언 :156)에 대해 K 11지점(1·2·4·6·9·11·13·15·16·17·18, :76)에서 (A) seq 0 전량 재생과 (B) K 스냅샷 로드 후 K+1 tail 재생의 인코딩 바이트 동일을 단언하고, tail 재생 명령 수가 `head - K`로 상한되는지·pending이 비는지도 함께 검사한다(:177~198). (2026-09-14 주: 이 시험은 ADR 0022 2단계 병합의 `1df13cd` 에서 마켓별 라이터로 바뀌었다. 캡처 지점 11개는 같고 정의가 :89 로 옮겼다. 위 행 인용은 병합 전 판이다)
- 무결성 폴백도 전수 검사한다. `state_checksum` 손상 세트는 직전 유효 프리픽스로 폴백하고 tail 상한을 유지한다(:201~229). `engine_version`·`config_hash` 불일치는 전 세트 폐기 후 전량 재생이며 `replayedCommands == leaderHead`로 단언한다(:231~250).
- 캡처를 명령 경계에 두면 단일 라이터라 그 seq까지 apply 완료 상태가 자연히 정합이다. 별도 스레드가 임의 시점을 훔쳐볼 때 필요한 잠금이 없다(`docs/product/복구_RTO_설계.md` §3-b:126).
- 인코딩 결정론은 순회 순서가 정의된 자료구조(TreeMap·ArrayDeque)만 쓰는 코어 순수성에 근거한다(같은 절 :127).

## 대안

- 전량 재생 유지. 기각. 현 축약 저널에서는 수 초라 당장 SLO를 위반하지 않지만, 저널이 자라면 목표가 시간과 함께 깨진다. 결함 도래 시점이 운영 중 임의 시점이라 회피가 아니라 지연이다(2026-07 자가 감사(폐기):74).
- 스냅샷 무결성 미검증 채택. 기각. 스냅샷은 엔진 상태의 직렬화라 엔진 버전·설정이 바뀌면 같은 바이트의 의미가 달라진다. 검증 없이 채택하면 손상·구식 스냅샷이 조용히 잘못된 오더북을 세우고, 그 위의 tail 재생은 자기 구간만 대조하므로 결정론 검증을 통과한다. 채택 게이트를 로드 시점에 두고 불일치는 폐기·폴백한다(`SnapshotStore.java`:96~117).
- 마켓별 독립 재생. 기각. `seq`·`event_seq`는 전역 gapless 단일 시퀀스라 마켓별로 나누면 크래시 창 이벤트의 전역 인터리빙(=event_seq 배정)을 재구성할 수 없다(`docs/contracts/백엔드_DB_설계.md` §5.5 STEP2:2032~2037). 단 마켓별 재생 자체가 금지는 아니다. 오더북이 마켓 로컬이라 결정론 검증 재생에는 여전히 유효하고, 전역 단일 패스 규칙은 event_seq를 새로 배정하는 서비스 복원·catch-up 재생에 적용된다(같은 절 말미 주석 :2059~2061). (2026-09-13 주: V36 부터 event_seq 는 적재 트랜잭션이 마켓 카운터에서 받으므로 이 기각 사유가 성립하지 않는다. ADR 0022 와 DB 설계 v2.44 가 마켓별 재생과 크래시 창 k-way 병합 재적재를 채택했다)
- 캡처를 매칭 코어 밖 별도 스레드가 전담(설계 §3-b:126 원안: 별도 스레드가 특정 seq 경계의 엔진 상태를 직렬화). 부분 기각. 엔진 상태는 라이터 전용이라 외부 스레드가 경계 상태를 읽으려면 잠금이나 복제가 필요하고 둘 다 핫패스 비용이다. 인코딩만 라이터가 명령 경계에서 하고 DB 영속화만 워커로 넘기는 절충을 택했다(`SnapshotCapture.java`:23~26). 잔여 비용은 인코딩 지연이며 캡처 주기로 상한한다.
- 손상 스냅샷 DELETE 후 재기록. 기각. `snapshots`는 append-only다(트리거+REVOKE, `docs/contracts/백엔드_DB_설계.md`:1158). 손상·구식은 삭제가 아니라 무시·폴백으로 처리한다.
- 핫 스탠바이(스탠바이가 저널을 실시간 tail해 인메모리만 연속 apply). 기각. 스냅샷+rebuild가 목표를 충족하는데 상태 전진 경로를 하나 더 만들면 결정론 표면이 넓어지고 팔로워 발산 감지를 rebuild와 별도로 유지해야 한다(`docs/product/복구_RTO_설계.md` §3-d:145~148). 재검토 트리거는 최대 스냅샷 간격에서 잰 승격 rebuild delta 벽시계가 예산에 근접(예: 10s 초과)하는 경우다(:149).

## 영향

- 신규 DDL 없다. 기존 `snapshots` 테이블(`docs/contracts/백엔드_DB_설계.md` §6.3:1141~1162)을 그대로 쓴다. 마이그레이션 되돌림 대상이 없다.
- 잔여 부채: 설계 §3-b:129 권장인 스냅샷 event 워터마크 병기를 도입하지 않았다. `snapshots`에 해당 컬럼이 없어(`docs/contracts/백엔드_DB_설계.md`:1144~1155) 로드마다 `event_journal` 유도 질의를 한 번 더 친다(`SnapshotStore.java`:124~131).
- 캡처 주기 10만 명령·60초는 기본값이지 확정치가 아니다. 부하 실측으로 확정해야 한다(`docs/product/복구_RTO_설계.md` §3-b:126, `docs/architecture/M4_하드닝설계.md` §3-3(d) 연계).
- 스냅샷은 끌 수 있다. `enabled=false`(`SnapshotProperties.java`:36~37 기본 true) 또는 `snapshotSource=null`(`EngineRecovery.java`:46~47)이면 전량 재생 경로로 되돌아간다. 정확성 원천이 아니므로 비활성이 기능 손실이 아니다.
- 되돌리려면 `SnapshotCapture` 훅 배선 해제와 `EngineRecovery` 3인자 생성자 사용으로 충분하다. `ReplayService` 오버로드와 `SnapshotStore`는 남아도 무해하다(`eventStartSeq=0`이 종전 동작).
- 승격 구간이 리스 획득과 첫 fencing 쓰기 사이에 스냅샷 로드·tail 재생을 통째로 끼운다. 그 구간의 하트비트 지연이 정당한 새 리더의 첫 정산을 만료 리스로 거부시키므로, b3ebf07 이후 dcaf1a6(2026-07-29)이 크래시 창 재적재 첫 배치 직전 1회 renew를 추가했다(`EngineRecovery.java`:162~165). 재적재 루프의 주기 renew 자체는 b3ebf07 이전부터 있었다(`git show b3ebf07^:server/app/src/main/java/kr/exchange/engine/EngineRecovery.java`:57~68).
- 검증 부채가 남는다. 등가성 테스트는 CI 축약 저널이라 스파이크 규모 실측을 대체하지 않는다. 스냅샷 도입 후 재측정으로 RTO 저널 크기 탈동조를 실증해야 한다(`docs/architecture/M4_하드닝설계.md`:184). 대상 실험은 E4·E6이고(`docs/product/복구_RTO_설계.md`:180) 측정 기준은 가시 첫 체결 벽시계다(:172).
- 드롭은 정상 동작이라 관측 없이는 조용히 tail이 길어진다. `exchange.snapshot.captured`·`dropped`·`persist_failed` 카운터와 `last_persisted_seq` 게이지를 노출한다(`SnapshotCapture.java`:71~79). 드롭·영속화 실패가 지속되면 RTO 상한이 사실상 해제된 상태이므로 경보 대상이다.
- 승격 rebuild 소요는 `recovery_runs.duration_ms`로 상시 계량한다. 이 값이 핫 스탠바이 재검토의 판단 축이다(`docs/product/복구_RTO_설계.md`:149).
