# ADR 0017. 리더 리스 갱신을 PostgreSQL 커밋 경로에서 뗀다

- 상태: 채택
- 날짜: 2026-09-08
- 결정자: harness-engineer(실측), backend-engineer(구현), 아키텍처 담당(정본 반영)

## 배경

ADR 0002가 리더 리스의 권위를 `leader_lease` 행에 뒀다. 그 결정의 핵심은 "락 획득과 데이터 커밋이 같은 트랜잭션 경계 안에 있어야 stale 리더의 커밋을 막을 수 있다"였고 그 근거는 지금도 유효하다.

2026-09-08에 그 결정의 대가가 실측됐다(`harness/evidence/replication-fault-20260908/`). 리스가 PostgreSQL 행이면 리스 갱신은 곧 커밋이다. `synchronous_standby_names`가 지목한 standby를 전부 잃으면 그 커밋이 멈춘다. 연쇄는 이렇게 진행됐다.

1. standby 전량 상실. `synchronous_commit = on`이라 커밋이 대기한다.
2. 리스 갱신도 함께 막힌다. 갱신이 커밋이기 때문이다.
3. kill 9.6초 뒤 리스가 만료된다. 그런데 만료를 확인할 커밋 자체가 막혀 있어 아무도 감지하지 못한다.
4. standby가 돌아와 커밋이 풀린다. 그 순간 막혀 있던 `assert_current_leader`가 `fencing 위반: 리스 만료`를 던지고 리더가 내려간다.
5. 복제가 `streaming`으로 완전히 복구된 뒤에도 readiness는 503에 머문다. `재승격 금지, 복귀는 재기동` 규칙이 걸린다.

PHASE 1(standby 1대)과 PHASE 2-E(standby 2대 `ANY 1`)에서 각각, 그리고 복구 관측에서 한 번 더, 모두 세 회차에서 재현했다.

곧 거래 데이터의 내구성 설정이 리더 선출의 생존성을 결정한다. 두 축은 서로 다른 이유로 존재하므로 결합되어서는 안 된다. 내구성을 올릴수록 가용성이 떨어지는 것은 정상이지만, 내구성 설정 하나가 앱 재기동 없이는 복구 불가능한 상태를 만드는 것은 정상이 아니다.

## 결정

리스 **갱신**을 PostgreSQL 밖으로 옮긴다. 리스 **검증**은 PostgreSQL 트랜잭션 안에 그대로 둔다.

- `LeaderLeaseStore` 인터페이스를 두고 구현을 둘 둔다. `PostgresLeaderLeaseStore`가 종전 경로이고 기본값이다. `DynamoDbLeaderLeaseStore`가 새 경로이며 `exchange.leader.lease.provider=dynamodb`로만 켜진다.
- DynamoDB 보관소는 항목 하나를 쓴다. 파티션 키 `clusterId`, 값 `leaderId`·`leaseUntil`·`fencingToken`·`updatedAt`이다. 승격·갱신·반납 모두 조건부 쓰기 한 번이다.
- 트랜잭션 안의 `assert_current_leader`와 `assert_and_lock_leader`는 **한 글자도 바꾸지 않는다**. (ADR 0026이 잠금 모드를 FOR KEY SHARE로 바꿨다. 검증 위임과 거부 메시지는 그대로다)
- 대신 승격 때 `publish_leader_epoch(holder, epoch)`(V33)가 얻은 epoch을 `leader_lease` 행에 단조 게시하고 `lease_until`을 100년 뒤로 민다. 만료 판정을 외부 보관소로 넘긴다는 뜻이다.
- 게시는 접수 개방 전에 끝난다. 게시 실패는 승격 실패다.

이 결정이 해결하는 것과 해결하지 않는 것을 명시한다.

- 해결한다. 커밋이 멈춘 동안 **현직 리더가 리더십을 잃지 않는다**. 갱신이 PostgreSQL을 타지 않기 때문이다. 커밋이 풀리면 앱 재기동 없이 처리가 재개된다.
- 해결하지 않는다. 커밋이 멈춘 동안 **새 리더가 승격할 수는 없다**. 게시가 커밋이기 때문이다. 이것은 결함이 아니라 의도다. PostgreSQL에 쓸 수 없는 노드는 단일 라이터가 될 수 없다. 승격시켜도 아무것도 처리하지 못하면서 fencing 토큰만 소비한다.
  2026-09-08에 두 노드로 실측했다. 커밋이 멈춘 상태에서 리더를 죽이니 팔로워가 DynamoDB 리스는 얻었고(토큰 20에서 21) PostgreSQL epoch은 20에 머물렀으며 readiness가 503에서 움직이지 않았다. stall이 풀리자 막혀 있던 게시가 완료되고 5.5초 만에 readiness 200이 됐다. 앱 재기동은 없었다. 근거는 `harness/evidence/leader-failover-20260908/`이다.
  그 회차가 부수적으로 드러낸 것이 하나 더 있다. 게시가 리스 틱 스레드에서 돌므로 게시가 막히면 그 노드의 리스 활동 전체가 멈추고, 방금 얻은 리스도 갱신되지 않아 만료된다. 다른 후보가 있었다면 같은 자리에서 같은 이유로 막힌다.

## 근거

- **실측이 요구한다.** 위 연쇄의 3단계와 4단계는 전적으로 "갱신이 커밋"이라는 성질에서 나온다. 그 성질을 없애면 3단계가 성립하지 않는다.
- **검증까지 옮기면 ADR 0002를 뒤집는다.** ADR 0002가 Redisson RLock을 기각한 사유가 "락 획득과 DB 커밋이 다른 시스템이면 그 사이가 항상 열린다"였다. 검증을 외부로 옮기면 그 창이 그대로 돌아오고 GC pause 한 번이 stale 리더의 커밋을 통과시킨다. V30의 행 잠금(ADR 0026 이후 FOR KEY SHARE)이 닫던 "판정과 커밋 사이"도 다시 열린다. 그래서 검증은 옮기지 않는다.
- **함수를 고치지 않는 이유가 세 개다.** `FencingErrors.rejectionDetail`이 거부 메시지 두 갈래로 강등 사유를 가른다. `MigrationSmokeTest`가 만료 메시지 문구를 단언한다. `PromotionStandDownIntegrationTest.installFencingFault`가 `assert_current_leader`를 갈아끼워 결함을 주입하고, 그 주입이 정산과 이벤트 적재 경로를 함께 덮는다. 규칙을 바꾸면 셋이 함께 흔들린다. 데이터로만 푸는 쪽이 파급이 작다.
- **epoch 게시만으로 stale 리더가 막힌다.** fencing의 본질은 "다음 리더가 토큰을 올리면 이전 토큰의 쓰기가 거부된다"이다. 만료 검사는 그 위의 보조 장치이고, 보조 장치가 주 장치의 가용성을 무너뜨리고 있었다. 게시가 접수 개방보다 앞서는 한 주 장치는 그대로 선다.
- **기본값을 바꾸지 않아 비교 가능성이 남는다.** 옮기기 전과 옮긴 뒤를 같은 조건에서 재려면 종전 경로가 살아 있어야 한다. 기존 통합 검증 전량도 종전 경로를 전제한다.

## 대안

- **갱신마다 게시한다.** 기각. 게시가 곧 커밋이라 옮긴 의미가 사라진다.
- **검증도 외부 보관소로 옮긴다.** 기각. ADR 0002의 기각 사유가 그대로 적용된다.
- **런타임 검증을 없애고 저장된 `fencing_epoch` 사후 감사만 남긴다.** 기각. `InvariantCheck`의 epoch 비감소 검사는 사후 판정이지 차단이 아니다. 런타임 split-brain 차단이 사라진다.
- **갱신만 옮기고 승격은 `try_acquire_leader`로 남긴다.** 기각. epoch 원천이 둘로 갈린다. 두 보관소가 서로 다른 만료 판정을 내는 순간 이중 리더가 성립한다.
- **`assert_current_leader`에서 만료 분기를 지운다.** 기각. 위 근거의 세 가지가 함께 깨진다.
- **`lease_until`을 유한한 긴 유예로 두고 리더가 유예의 절반마다 재게시한다.** 보류. 게시 없이 방치된 리더가 유예 뒤 자동 차단되는 안전망이 생기는 것이 이점이다. 대가는 재게시가 커밋이라 "갱신이 커밋"이 부분적으로 돌아온다는 것이고, 빈도가 TTL 대비 훨씬 낮아 실질 위험은 작을 수 있다. 채택하지 않은 이유는 유예 값의 근거가 될 실측이 없기 때문이다. stall 지속 시간 분포를 재기 전에는 값을 고를 수 없고, 근거 없는 값은 이 저장소가 금지한다. 재개 조건은 아래에 적는다.
- **`lease_until`을 `'infinity'`로 민다.** 기각. 100년과 뜻은 같은데 계약을 여럿 깬다. `HaMonitoringController`가 `EXTRACT(EPOCH FROM (lease_until - clock_timestamp())) * 1000`을 `bigint`로 캐스트하고, 관리 평면 검증이 이 값이 타임스탬프 패턴을 만족한다고 단언한다. 100년은 밀리초로 약 3.15e12라 `bigint`에 넉넉히 들고 패턴도 만족한다.

## 영향

- **ADR 0002를 부분 대체한다.** "권위를 DB 행에 둔다" 중 갱신 축이 바뀐다. 검증 축과 단일 라이터 전제는 그대로다.
- **ADR 0009의 전제가 좁아진다.** 리스 전용 커넥션 풀은 요청 풀 포화로부터 하트비트를 지키기 위한 것이었다. DynamoDB 보관소에서는 갱신이 그 풀을 쓰지 않으므로, 그 풀의 남은 역할은 승격 게시 커밋 하나다. 풀 자체는 그대로 둔다. 종전 보관소가 기본값이라 두 역할이 모두 살아 있어야 한다.
- **`leader_alive` 오라클이 dynamodb 보관소에서 권위를 잃는다.** `HarnessController`가 `lease_until > clock_timestamp()`로 리더 생존을 판정하는데, 게시가 100년 뒤를 밀어 두므로 항상 참이 된다. 그 보관소에서 권위는 DynamoDB 항목의 `leaseUntil`이다. 2026-09-08 두 노드 페일오버 실측에서 이 오라클이 죽은 노드를 11초 동안 살아 있다고 답하는 것을 확인하고 같은 날 `HarnessController`의 판정 권위를 보관소로 옮겼다. `admin/HaMonitoringController`는 아직 옮기지 않았고 남은 위험으로 등재한다.
- **되돌리기에 절차가 필요하다.** dynamodb에서 postgres로 되돌리려면 앱을 전부 내린 상태에서 `SELECT expire_leader_lease_now()`를 한 번 부른다. 게시가 `lease_until`을 100년 뒤로 밀어 두므로 그대로 두면 `try_acquire_leader`의 만료 조건이 영원히 거짓이다.
- **시계 스큐가 판정에 들어온다.** PostgreSQL은 만료를 `clock_timestamp()`로 판정해 모든 노드가 같은 시계를 봤다. DynamoDB는 조건식에서 쓸 서버 시계를 노출하지 않으므로 기준 시각이 호출한 노드의 시계다. 스큐가 TTL에 가까우면 이른 탈취가 가능하다. `제출_20260901/기획안_원본.md` 13.2 한계 7이 시계 스큐 내성을 검증 범위 밖으로 두고 있으므로 그 자리에 함께 등재한다.
- **AWS 자원이 하나 는다.** DynamoDB 테이블 한 개, 항목 한 개, 초당 갱신 한 번이다. 비용 판정은 9장이 소유한다.
- **Patroni, etcd, HAProxy, PgBouncer, DNS failover, 자동 primary discovery는 이번 범위가 아니다.** `spring.datasource.url`이 고정 primary를 보는 문제는 그대로 남는다.

## 이 결정을 다시 여는 조건

- stall 지속 시간 분포를 재고, 유한 유예 방식(위 대안)의 유예 값을 근거 있게 고를 수 있게 될 때.
- 시계 스큐 내성을 검증 범위에 넣기로 할 때. 그때는 만료 판정 기준 시각을 노드 시계에서 떼는 설계가 필요하다.
- 하네스의 리더 생존 오라클은 2026-09-08에 이설했다. `HarnessController`가 `LeaderLeaseStore.current()`와 `LeaderLeaseStore.now()`를 읽는다. 이설 전 페일오버 창에서 죽은 노드를 11초 동안 살아 있다고 답한 것을 실측했다. `admin/HaMonitoringController`는 아직 `leader_lease` 행 기준이고 남은 위험이다.
- 게시를 리스 틱 스레드에서 뗄 때. 지금은 게시가 막히면 그 노드의 리스 활동 전체가 멈춘다.
