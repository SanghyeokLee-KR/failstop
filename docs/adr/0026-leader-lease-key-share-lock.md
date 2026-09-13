# ADR 0026. 쓰기 경로의 리스 행 잠금을 FOR KEY SHARE 로 낮춘다

- 상태: 채택
- 날짜: 2026-09-13
- 관련: ADR 0002(DB 리더 리스 단일 라이터), ADR 0017(리스를 커밋 경로 밖으로), ADR 0021(마켓별 순서 축), ADR 0022(마켓별 명령 저널)
- 근거 실측: `harness/evidence/e1-lease-starvation-20260913/`, `harness/evidence/lease-key-share-pg17-20260913/`, `harness/evidence/chaos-runs/`
- 관련 정본: `docs/contracts/백엔드_DB_설계.md` 3.1, 5.4, `docs/contracts/하네스_검증설계.md` 3절 E1, 6절 T4
- 코드: `V41__lease_key_share_lock.sql`(`uq_leader_lease_fencing_key`, `assert_and_lock_leader`), `CommandJournalAppender`, `SettlementService`, `EventJournalAppender`

## 맥락

E1 은 리더의 PostgreSQL 경로에 지연 200ms, 지터 100ms 를 넣고 페일오버가 없어야 하는 실험이다.
2026-09-13 현 코드에서 세 번 연속 FAIL 했고 셋 다 epoch 1 -> 2 로 리더가 바뀌었다.

잠금 표본이 원인을 보였다. 리스 갱신 백엔드가 `Lock/transactionid` 로 3.203초 이상 기다렸고,
막은 쪽은 정산 트랜잭션 90, 83, 84 로 차례로 바뀌었다. 그 사이 리스가 만료되고 다른 노드가
승격했다.

정산 머리말의 `assert_and_lock_leader`(V30)와 명령 append 의 `INSERT ... SELECT`
(`CommandJournalAppender`)가 `leader_lease` 행을 FOR SHARE 로 잡고 커밋까지 쥔다.
`renew_leader_lease`(V1)는 같은 행의 `lease_until` 을 바꾸는 UPDATE 다. FOR SHARE 는 모든
UPDATE 와 충돌한다. 지연이 트랜잭션을 늘리고 마켓별 라이터 12개가 공유 잠금을 교대로 이어 쥐면
갱신이 TTL 6초 안에 잠금을 얻지 못한다.

V1 은 이 위험을 알고 있었다. `assert_current_leader` 머리 주석이 리스 행을 잠그지 않는 이유로
"리스 갱신 UPDATE 의 liveness 가 깨진다" 를 적는다. FOR SHARE 는 그 대가를 치르고 "판정과
커밋 사이에 epoch 이 바뀌지 않는다" 를 얻었다. 그 대가가 단일 라이터에서는 잠들어 있다가 마켓별
라이터 배선(`cd2e026`)에서 깨어났다.

## 결정

**쓰기 경로가 리스 행을 FOR KEY SHARE 로 잡고, holder·epoch 를 키 열로 만든다.**

1. `V41__lease_key_share_lock.sql` 이 `CREATE UNIQUE INDEX uq_leader_lease_fencing_key ON leader_lease (resource, holder, epoch)` 를 만든다. 부분, 식, INCLUDE 없는 평문 인덱스다.
2. 같은 마이그레이션, 같은 트랜잭션에서 인덱스 다음에 `assert_and_lock_leader` 를 FOR KEY SHARE 로 재정의한다. 판정 위임과 거부 메시지는 V30 과 같다.
3. `CommandJournalAppender.INSERT_SQL` 을 `FOR KEY SHARE OF l` 로 바꾼다. 리스 행을 잠그는 지점은 이 둘뿐이고 둘을 함께 바꾼다.
4. 리더십 이전은 holder 나 epoch 값을 실제로 바꾸는 UPDATE 로만 한다. `lease_until` 만 바꾸는 경로는 리더십을 옮기지 않는다.

PostgreSQL 은 부분·식이 아닌 유일 인덱스의 열을 키 열로 본다. 키 열을 바꾸는 UPDATE 는 FOR UPDATE,
나머지 UPDATE 는 FOR NO KEY UPDATE 를 잡는다. FOR KEY SHARE 는 FOR UPDATE 와만 충돌한다.

| 문장 | 바꾸는 열 | 잡는 잠금 | 쓰기 잠금 보유 중 |
|---|---|---|---|
| `renew_leader_lease` | lease_until | FOR NO KEY UPDATE | 기다리지 않는다 |
| `expire_leader_lease_now` | lease_until | FOR NO KEY UPDATE | 기다리지 않는다 |
| `try_acquire_leader` | holder, epoch, lease_until | FOR UPDATE | 커밋까지 기다린다 |
| `publish_leader_epoch`(새 epoch) | holder, epoch, lease_until | FOR UPDATE | 커밋까지 기다린다 |
| `publish_leader_epoch`(같은 값) | lease_until | FOR NO KEY UPDATE | 기다리지 않는다 |

## 사유

### 갱신만 풀리고 보장은 남는다

격리 PostgreSQL 17.10 에서 쟀다(`lease-key-share-pg17-20260913`).

- 쓰기 잠금이 겹친 상태에서 갱신 10회가 2.2~3.3ms 에 끝났다(S5). 같은 부하를 FOR SHARE 로 돌리면 갱신이 14초 동안 행을 얻지 못했다(S2).
- 쓰기 트랜잭션이 커밋하기 전까지 승격과 게시가 기다렸다(S3-C, S3-D).
- 갱신, 같은 값 게시, 만료 세 번의 비키 UPDATE 뒤에도 FOR KEY SHARE 가 새 행 버전으로 넘어가 게시를 계속 막았다(S3-D2).
- 인덱스를 추가해도 갱신은 HOT 갱신을 유지했다(S8).

### 인덱스가 보장의 전제다

**인덱스가 없으면 이 결정은 안전하지 않다.** 같은 실측에서 인덱스 없이 FOR KEY SHARE 만 쓰면
`try_acquire_leader` 가 2.3ms 에 epoch 7 을 얻었고, 쓰기 트랜잭션은 새 holder 를 본 채로 커밋에
성공했다(S4). 판정과 커밋 사이 epoch 불변이 깨진다.

`UNIQUE(resource, epoch)` 만 두면 holder 만 바꾸는 UPDATE 를 막지 못하고, 부분 유일 인덱스와 식 유일
인덱스는 키 열을 만들지 않는다(S7). INCLUDE 인덱스가 키 열을 만들지 않는다는 것과, 함수를 먼저 바꾸고
인덱스를 나중에 만들면 그 틈에 승격이 쓰기를 기다리지 않는다는 것은 이 증거의 시나리오에 없다.
PostgreSQL 의 키 열 규칙과 S4 에서 나온 판단이다.
그래서 한 마이그레이션에서 인덱스를 먼저 만든다.

이 전제를 기계로 지킨다. `LeaderLeaseLockModeIntegrationTest` 가 카탈로그에서 인덱스 모양을 보고,
같은 컨테이너의 별도 DB 에서 인덱스를 지운 뮤턴트가 승격을 막지 못함을 단언한다. 인덱스에는
`COMMENT ON INDEX` 로 지우면 안 되는 이유를 붙인다.

## 대가

**단일 라이터 안전성이 인덱스 하나에 기댄다.** 사람이 인덱스를 직접 지우거나, V41 이 적용되지 않은 DB 에
이 코드가 붙으면 stale write 가 열린다. Flyway 는 한 노드만 돈다(`docs/contracts/하네스_CICD_IaC.md` 7-2,
`deploy/compose.node-app.yml` 의 `SPRING_FLYWAY_ENABLED` 기본 false). 그래서 **마이그레이션 노드가 V41 적용을
끝낸 뒤 나머지 노드를 새 이미지로 올리는 순서가 배포 전제다.** 기동이나 승격 때 인덱스 모양을 보고
리더십을 거부하는 코드 가드는 넣지 않았다. 첫 롤링 배포 전에 넣을 후속이다.

**겹친 쓰기 잠금이 승격과 게시를 굶길 수 있는 성질은 그대로다.** FOR SHARE 와 같다. postgres 리스는
만료 뒤 쓰기가 판정에서 거부되며 잠금이 풀린다. dynamodb 리스는 `lease_until` 이 100년 뒤라 구 리더
쓰기가 멈출 때까지 풀리지 않는다(S10). 이 변경이 없애는 것은 갱신 굶김뿐이다.

**요청 경로가 반쯤 멈춘 리더가 리스를 더 오래 쥔다.** FOR SHARE 굶김은 탐지 장치가 아니었지만
결과적으로 멈춘 라이터를 끌어내리는 경우가 있었다. 이제 한 마켓 라이터가 멈춰도 갱신은 계속된다.
`LeaderStallDetector` 는 전역 비율이라 한 마켓 정지를 잡지 못한다.

**만료 직전 쓰기가 거짓 거부를 받을 수 있다.** 쓰기가 갱신 커밋 전의 `lease_until` 을 보면 리스
만료로 거부된다. `demoteUnlessStillLeader` 의 재확인이 연장에 성공하면 강등은 없고 그 요청만 실패한다.

## 기각한 대안

| 대안 | 기각 사유 |
|---|---|
| FOR KEY SHARE 만 바꾸고 인덱스를 두지 않는다 | 승격이 쓰기를 기다리지 않아 stale 커밋이 난다(S4) |
| `UNIQUE(resource, epoch)` | holder 만 바꾸는 UPDATE 를 막지 못한다(S7) |
| 리스 행 잠금을 없앤다(V1 원형) | 판정과 커밋 사이 창이 다시 열린다. V30 이 닫은 자리다 |
| 명령 append 잠금을 SQL 함수(V39 머리말) 안으로 옮긴다 | ADR 0022 4-4 의 잠금 순서(권고 잠금, 카운터, 리스)가 바뀌고 보유 구간이 늘어난다 |
| 갱신에 `lock_timeout` 을 건다 | 굶김 원인을 없애지 못하고 실패만 앞당긴다. `lock_timeout` 은 대기 한 번의 상한이라 겹친 보유자 앞에서 총 대기를 묶지 못한다(S5-publish) |
| 리스 보관소를 dynamodb 로 기본 전환한다 | ADR 0017 이 비교 가능성 때문에 postgres 기본을 유지했다. dynamodb 에서도 쓰기 경로는 같은 행을 잠그고 게시가 같은 굶김을 겪는다 |
| 쓰기 트랜잭션을 줄여 잠금 보유를 짧게 한다 | 지연 주입에서 보유 시간은 왕복 수 곱하기 지연이다. 줄여도 라이터 12개의 겹침은 남는다 |

## 미충족

- **카오스 재검증 NOT RUN.** E1 3회와 E2·E4·E5·E6·E7 재주행 전까지 E1 FAIL 을 해제하지 않는다.
  E1 판정기에 갱신 잠금 대기 표본 0, 마지막 갱신 경과 4초 미만, 쓰기 잠금 동시 보유 2 이상의
  양성 축을 더한 뒤 돈다.
- **dynamodb 보관소 게시 굶김 실측 0건.** 격리 실측 S10 뿐이다.
- **단일 쓰기 커넥션 블랙홀 실험 0건.** pgjdbc `socketTimeout` 설정이 저장소에 없다.
- **대기한 승격의 리스가 대기 전 시각으로 찍힌다.** 6.43초 기다린 `try_acquire_leader` 가 이미
  0.43초 지난 리스를 얻었다(S3-C). FOR SHARE 에서도 같은 기전이라 이 결정의 회귀는 아니다.
- **`SnapshotStore.persist` 에 fencing 이 없다.** 잠금 모드와 무관한 CLAUDE.md 14절 위반이라 별도
  작업으로 둔다.

## 영향

- `docs/contracts/백엔드_DB_설계.md` v2.43: 3절 표 V41 행, 3.1 DDL 인덱스, `assert_current_leader`
  주석, 5.4 append 문장과 주석.
- `docs/contracts/하네스_검증설계.md`: E1 기대 전제와 합격 기준, E5 인용 SQL, T4-9.
- ADR 0002, 0017, 0022 의 FOR SHARE 서술에 이 문서를 잇는다. ADR 0022 의 미래 마이그레이션 번호
  선명명을 번호 없는 이름으로 바꾼다.
- `docs/README.md` 색인의 결정 기록 편수를 26편으로 올린다.
