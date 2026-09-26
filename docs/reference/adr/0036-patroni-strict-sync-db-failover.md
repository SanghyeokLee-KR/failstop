# ADR 0036. DB 자동 전환을 Patroni 엄격 동기 2노드로 한다

- 상태: 채택(사용자 결정 2026-09-24~25). 2026-09-25 설계 검토 지적 13건을 반영해 결정 3·4·6·8과 근거·영향을 고쳤다([설계](../architecture/DB_자동전환_Patroni_설계.md) 14절). 그 뒤 작업 브랜치 `claude/patroni`에 앱·웹·하네스 코드와 로컬 실험 스택(`harness/patroni/`)을 만들고 로컬 Docker에서 E-P1~E-P7을 돌렸다. 코드 검토 대응 뒤 이미지의 한 벌에서 E-P1, E-P2a, E-P2b, E-P3a, E-P3b, E-P4a, E-P4b, E-P5(두 번), E-P6(a·b)가 PASS였고, E-P1b는 Patroni가 같은 노드를 다시 승격하며 타임라인을 올려 처음 판정에서 '타임라인 불변' 축이 FAIL이었다. 사용자가 2026-09-25에 타임라인 변경을 Patroni의 정상 동작으로 보고 판정 축에서 기록 항목으로 옮기기로 확정했고, 유실 0과 안전 축은 관문으로 그대로 두었다. 기록된 회차를 바꾼 판정 도구로 다시 판정한 결과는 PASS다. E-P2c, E-P4c, E-P8, E-P9는 돌리지 않았다. 판정과 증거는 [설계](../architecture/DB_자동전환_Patroni_설계.md) 15절, 현재 상태는 [로드맵](../../ROADMAP.md)에 있다. 단일 노드 장애의 유실 0은 로컬 Docker 한 호스트의 이 회차들에 한해 확인했다(아래 영향). AWS에는 적용하지 않았다. 그래서 이 실험 스택 밖에서 지금 실행 중인 구조(학교 DB 두 대, `deploy/compose.node-data.yml`, 로컬 카오스 스택)는 여전히 [ADR 0024](0024-async-two-node-replication.md)의 비동기 복제와 수동 승격, [ADR 0025](0025-rejoin-by-basebackup-no-wal-retention.md)의 전량 베이스백업 재편입이다. 효력은 이 구성을 적용한 환경부터 생긴다.
- 날짜: 2026-09-25
- 결정자: 사용자가 2026-09-24~25 대화로 정했다. DB 자동 전환은 Patroni(MIT)로 한다. DB는 두 대(Primary·Standby)를 유지한다. 전환 판정의 합의 저장소는 etcd 세 표(db-1, db-2, bot-1)다. 앱은 HAProxy로 현재 Primary에 연결한다. 유실 0을 가용성보다 앞에 두어 엄격 동기 복제(`synchronous_mode`와 `synchronous_mode_strict`)를 쓰고, Standby가 없으면 쓰기를 멈추며 거래 화면은 '작동 안 함, 거래 일시 중단' 상태로 바꾼다(사용자 문장: '원래 페이지는 작동 안함으로'). AI는 어느 모드에서도 DB 승격·페일오버를 실행하지 않고 전환 판단은 Patroni 규칙만 한다(ADR 0035. 다른 작업 브랜치에 있어 이 브랜치에는 파일이 없다). Docker에서 먼저 검증하고 학교·개인 AWS는 나중이며, 개인 AWS는 DB 두 대 t3.medium, etcd는 DB 두 대와 bot-1이다. 아래 「결정」의 나머지 값(HAProxy 위치, 리스 갱신의 로컬 커밋, 거절 응답 `423`과 그 뜻, 쓰기 가능 탐지 문장과 임계와 DB 쪽 확인, 리스 획득 시점, 베이스백업 `--no-slot`, `failsafe_mode`, Patroni 시간 값, 호스트 손실의 목표 시간)은 이 설계를 맡은 AI가 2026-09-25에 고른 것이고 사용자 확인이 필요하다([설계](../architecture/DB_자동전환_Patroni_설계.md) 13절).
- 대체 범위: [ADR 0024](0024-async-two-node-replication.md)의 결정(비동기 2노드, `synchronous_standby_names` 비움, DB 수동 승격)과 대가 절(RPO 0 비보장)을 대체한다. [ADR 0025](0025-rejoin-by-basebackup-no-wal-retention.md)의 결정 1(재편입 기본 경로는 사람이 하는 전량 베이스백업)을 Patroni의 자동 재합류(`pg_rewind`, 실패하면 자동 재초기화)로 대체한다. 0025의 'WAL 보존 확대와 아카이브를 채택하지 않는다'는 유지한다. 두 ADR은 적용 전 환경의 현재 동작을 설명하는 문서로 남는다.
- 바뀌는 전제: [ADR 0034](0034-market-partitioned-leadership.md) 「유지하는 결정」의 'DB 자동 페일오버는 선택·구현하지 않았다는 전제'와 `AGENTS.md`의 같은 문장은 이 결정으로 '선택했으나 구현하지 않았다'가 됐다. 두 문장은 이 브랜치에서 고치지 않았다. `AGENTS.md`는 사용자가 확인한 뒤 고친다.
- 관련: [ADR 0004](0004-no-rds-self-managed-postgres.md)(자체 운영 DB), [ADR 0017](0017-leader-lease-out-of-commit-path.md)(리스를 커밋 경로 밖으로), [ADR 0018](0018-synchronous-quorum-any1.md)(동기 정족수와 그 실측), [ADR 0026](0026-leader-lease-key-share-lock.md)(리스 행 KEY SHARE), [ADR 0029](0029-school-aws-first-personal-account-expansion.md)(학교 AWS 선행).
- 근거 자료: 사전 확인 `harness/evidence/patroni-sync-probe-20260925/`(폐기용 PostgreSQL 17.10 두 노드, 검토 뒤 `'*'`와 크래시 재시작 확인, 경보 식 promtool 확인 포함), 동기 복제 장애 실측 `harness/evidence/replication-fault-20260908/`, 처리량 비용 `harness/evidence/sync-replication-20260908/`, 비동기 유실 실측 `harness/evidence/failover-rpo-20260912/`.

## 배경

지금 DB는 Primary 한 대와 Standby 한 대의 비동기 스트리밍 복제다. Primary가 사라지면 사람이 옛 Primary를 격리하고 Standby를 승격한 뒤 앱의 DB 주소를 바꾼다. 이 구성은 두 가지를 판다. 하나는 유실이다. 비동기라 ACK를 받은 주문도 Standby에 닿기 전에 Primary가 죽으면 사라진다. 로컬 실측에서 Standby를 8초 멈춘 뒤 Primary를 죽이자 ACK 122건 가운데 36건을 잃었다(`failover-rpo-20260912`). 저널도 같은 DB에 있어 재생으로 되살릴 수 없다. 다른 하나는 복구 시간이다. 학교 AWS의 계획 전환(쓰기를 멈추고 복제를 맞춘 뒤)도 옛 Primary 격리부터 거래 재개 확인까지 2분 52초, 앱 중단부터 3분 52초였고 사람이 확인한 간격이 들어 있다.

사용자는 유실 0을 가용성보다 앞에 두기로 했다. 대가는 ADR 0024가 피하려던 바로 그것이다. 동기 복제에서 동기 대기 서버를 잃으면 Primary가 살아 있어도 커밋이 멈춘다. 2026-09-08 실측에서 동기 Standby 한 대를 죽이자 2초 만에 접수가 0이 됐고, 리스 갱신도 커밋이라 함께 멈춰 Standby가 돌아온 순간 fencing이 리더를 끌어내렸으며 앱 재기동 전까지 회복하지 않았다(`replication-fault-20260908`). 사용자는 그 정지를 숨기지 않고 화면에 '거래 일시 중단'으로 보이기로 했다.

## 결정

1. PostgreSQL 17 두 노드(db-1, db-2)를 Patroni가 관리한다. Primary 선출과 강등은 Patroni가 etcd의 리더 키와 동기 대기 서버 기록(`/sync`)으로만 한다. 사람은 `patronictl switchover`·`failover`로 계획 전환을 할 수 있고 그 명령과 결과를 증거로 남긴다. AI와 운영 보고 서비스는 Patroni의 쓰기 API를 부르지 않는다. Patroni REST의 변경 메서드는 인증을 요구하고 그 자격 증명은 DB 호스트의 운영자만 가진다.
2. etcd는 세 멤버다. db-1, db-2, bot-1에 하나씩 둔다. bot-1의 멤버는 표만 던지고 PostgreSQL을 두지 않는다. 두 표가 있어야 리더 키를 바꿀 수 있으므로 한 호스트를 잃어도 선출이 된다.
3. 복제는 엄격 동기다. `synchronous_mode: true`, `synchronous_mode_strict: true`, `synchronous_node_count: 1`이다. Primary는 Standby가 WAL을 디스크에 쓴 뒤에만 커밋을 확인하고, 동기 Standby가 없으면 비동기로 내려가지 않고 쓰기를 멈춘다. Patroni는 `/sync`에 적힌 동기 Standby만 자동 승격하므로, 승격된 노드는 확인된 커밋을 모두 가진다. Patroni가 동기 후보를 잃었을 때 거는 `synchronous_standby_names = '*'`는 flush 위치를 보고하는 모든 복제 연결을 동기 대기 서버로 받는다(사전 확인 P·M). 그래서 멤버가 아닌 복제 클라이언트(`pg_receivewal`, 임시 슬롯의 `pg_basebackup` 스트림)를 붙이지 않고, 백업은 `--no-slot`으로 받거나 Standby에서 받으며, 멤버가 아닌 복제 연결을 경보로 잡는다.
4. 옛 Primary는 Patroni가 강등하고 `pg_rewind`로 새 Primary에 맞춰 Standby로 되돌린다(`use_pg_rewind: true`, `wal_log_hints: on`). 되감기가 실패하면 Patroni가 데이터 디렉터리를 지우고 새 베이스백업으로 다시 만든다(`remove_data_directory_on_rewind_failure: true`). 그 베이스백업은 `--no-slot`이다(`postgresql.basebackup`). 임시 슬롯이면 백업 스트림이 `'*'` 아래에서 동기 대기 서버가 되어 새 Standby가 다 만들어지기 전에 커밋이 확인된다(사전 확인 M·N). `wal_keep_size`는 Patroni가 받는 가장 작은 값(4.1.5에서 16MB)으로 두고 아카이브는 켜지 않는다. 되감기를 위해 WAL 보존을 늘리지 않는다는 ADR 0025의 판단은 유지한다. Standby가 잠깐 끊겼을 때 필요한 WAL은 Patroni 멤버 슬롯이 붙잡고 상한은 `max_slot_wal_keep_size`다.
5. HAProxy는 앱 노드와 봇 노드마다 로컬 사이드카로 둔다. 각 HAProxy가 두 DB의 Patroni REST `/primary`를 1초마다 확인해 Primary로만 TCP를 넘기고, 노드가 내려가면 그 노드로 연결된 세션을 끊는다(`on-marked-down shutdown-sessions`). 앱의 JDBC는 로컬 HAProxy에 붙는다.
6. 앱은 쓰기 가능 상태를 스스로 잰다. 노드마다 한 스레드가 1초마다 `BEGIN; SELECT pg_logical_emit_message(true, 'exchange-write-probe', ''); COMMIT`을 한 번에 하나씩만 보낸다. 이 커밋이 2초 안에 끝나지 않고 DB에서 그 원인(탐지 백엔드의 `SyncRep` 대기)을 확인하거나, 설정이 `'*'`면 '거래 일시 중단'으로 바꾸고, DB 쓰기를 하는 요청을 DB에 닿기 전에(수용 제한 필터보다 앞에서) `423 trading_suspended`로 거절한다. 원인을 확인하지 못한 느림은 이 노드만의 문제일 수 있어 중단으로 보지 않고 종전 좀비 판정에 맡긴다. 이 노드가 DB에 연결조차 못 하면 같은 자리에서 `503`(DB 연결 장애)이다. 조회는 계속 받는다. 탐지 커밋이 다시 빠르게 끝나고 설정이 `'*'`가 아니면 자동으로 재개한다. `423`은 '이 시도는 쓰지 않았다'는 뜻이다. 인그레스가 다시 보낸 시도일 수 있으므로 클라이언트는 재개 뒤 같은 키로 다시 내거나 재조회하고 새 키로 다시 내지 않는다. 공개 상태 API `GET /api/v1/status`가 이 상태를 낸다(API 설계 v2.60 §2-5-e, §4-A-7).
7. 커밋 대기는 끊지 않는다. 앱은 쓰기에 질의 취소를 쓰지 않고, 운영자는 Primary에서 커밋 대기 중인 백엔드를 `pg_cancel_backend`·`pg_terminate_backend`로 끊지 않는다. 취소는 복제되지 않은 커밋을 성공으로 돌려주기 때문이다(사전 확인 G). 앱 스레드는 쓰기 게이트가 새 쓰기를 막고, 접수 저널 append 풀만 클라이언트 쪽 소켓 제한(6초)으로 풀어 V47 판정에 넘긴다.
8. 리스 갱신(하트비트와 fencing 거부 뒤의 재확인)만 `synchronous_commit = local`로 커밋한다. 획득·게시·모드 전환처럼 epoch를 바꾸는 쓰기와 모든 거래 쓰기는 `on`(원격 flush)이다. Standby가 없는 동안에도 현직 리더가 리더십을 잃지 않게 해 2026-09-08의 리스 연쇄를 끊는다. 획득은 쓰기 가능 상태가 ACTIVE일 때만 하고, 반환 뒤 그 리스가 아직 유효한지 읽어 거짓이면 승격하지 않는다. `lease_until`은 커밋 전에 정해지므로 커밋 대기에 걸린 획득은 풀리는 순간 만료된 리스를 주기 때문이다(DB 설계 v2.60 §5.4-f).
9. 웹의 거래 화면은 상태 API를 따라 '거래 일시 중단'을 보이고 주문·취소 버튼을 끈다. 시세·호가·체결·잔고 조회는 계속된다.
10. 검증은 로컬 Docker의 폐기용 스택(compose 프로젝트 `exchange-patroni`)에서 E-P1~E-P9를 먼저 하고, 학교·개인 AWS 적용은 그 뒤 사용자 승인으로 한다. 적용 절차는 [설계](../architecture/DB_자동전환_Patroni_설계.md) 9절에 초안만 있다.

## 근거

- 유실 0. 엄격 동기에서 클라이언트가 받은 커밋 확인은 동기 Standby의 WAL flush 뒤에 나간다. Patroni는 `/sync`에 적힌 노드만 승격하고, 동기 Standby를 바꿀 때 먼저 `/sync`를 줄인 뒤 설정을 바꾸는 순서를 지킨다. 그래서 단일 노드 장애(프로세스, 호스트, 네트워크 격리)에서 승격된 노드는 확인된 커밋을 모두 가진다.
- 두 Primary의 쓰기가 갈라지지 않는다. 노드가 둘이고 동기 대기 서버가 될 수 있는 것이 멤버 하나뿐이므로, 옛 Primary가 격리된 채 살아 있어도 확인해 줄 Standby가 없어 새 커밋을 확인할 수 없다(사전 확인 A·B). 새 Primary도 옛 노드가 돌아오기 전에는 커밋을 확인하지 않는다. 이 성질은 멤버가 아닌 복제 연결이 `'*'` 아래에 붙지 않는다는 조건이 필요하다. 붙으면 한 사본만으로 커밋이 확인되는 것을 사전 확인 P·M에서 봤고, 결정 3·4의 규칙과 탐지기의 `'*'` 확인이 그 조건을 지킨다. Docker에는 워치독이 없어도 이 성질이 갈라진 확인 커밋을 막는다. 앱 쪽의 리더 권한 검사(epoch fencing)는 그대로 겹쳐 있다.
- 비용. DB는 두 대 그대로이고 etcd 세 번째 표는 이미 있는 bot-1에 둔다. 새 EC2가 없다.
- 리스 연쇄. 갱신을 로컬 커밋으로 하면 커밋이 멈춘 동안에도 리스가 유지된다(사전 확인 E). 갱신은 `lease_until`만 바꾸고 epoch를 바꾸지 않으며, 그 값은 Primary에서 곧바로 보이므로 같은 Primary 안의 fencing 판정은 그대로다. DB 전환 뒤 새 Primary의 리스가 조금 오래된 값이어도 전환 시간(Patroni TTL)이 앱 리스 TTL보다 길어 어차피 만료로 보이고, epoch를 바꾸는 획득은 동기 커밋이라 새 Primary에 남는다. 획득은 새 Primary가 동기 Standby를 얻어 쓰기를 받게 된 뒤에 하므로 곧바로 커밋되고 만료된 리스를 받지 않는다.
- 중단 판정의 근거. 탐지 커밋 시간은 노드마다 재므로 한 노드의 경로만 느려도 2초를 넘는다. 그 노드를 중단으로 보면 `423`이 인그레스 재시도·퇴출 대상이 아니고 리스 갱신은 로컬 커밋이라, 다른 노드가 멀쩡해도 중단이 풀리지 않는다. `SyncRep` 대기와 `'*'`는 동기 대기 서버를 기다린다는 클러스터의 사실이라 이 판정의 근거로 쓴다.
- 크래시 재시작. 커밋 대기 중에 postmaster가 크래시 재시작을 하면 기다리던 행이 복제 없이 보인다(사전 확인 Q1). 그래서 앱은 탐지 연결이 끊긴 뒤 다시 확인된 탐지가 나오기 전과 ACTIVE가 아닌 동안 재조회·저널 확인의 확정 응답을 미룬다(API 설계 v2.60 §2-5-e).
- 사용자 선택. Patroni와 HAProxy는 종전 API 설계 초안이 적었던 조합이고(API 설계 §1 DB 자체 HA 행), 사용자가 이번에 채택했다.

## 대안

| 대안 | 판단 |
|---|---|
| ADR 0024 유지(비동기, 수동 승격) | 기각(사용자). ACK 유실과 사람 판단 시간이 남는다 |
| Patroni 동기 모드에서 엄격을 끈다(Standby가 없으면 비동기로 내림) | 기각. 눈에 보이는 정지를 눈에 보이지 않는 유실 위험으로 바꾼다. ADR 0024가 자동 폴백을 기각한 사유와 같다 |
| DB 세 대(`ANY 1` 정족수) | 보류. Standby 한 대를 잃어도 쓰기가 계속되지만 사용자가 두 대 유지를 정했다. 가용성이 더 필요해지면 다시 본다 |
| pg_auto_failover, repmgr, Stolon, CloudNativePG | 기각(사용자가 Patroni를 골랐다). pg_auto_failover는 감시 노드 하나가 판정의 중심이고, repmgr은 합의 저장소 없이 승격하며, CloudNativePG는 Kubernetes를 전제한다 |
| RDS Multi-AZ | 기각(ADR 0004). 장애 주입과 Linux 운영 검증 범위 밖이 된다 |
| HAProxy 없이 pgjdbc 다중 호스트(`targetServerType=primary`) | 기각. 드라이버는 읽기 전용 여부만 보고 Patroni의 리더 키를 보지 않는다. 사용자가 HAProxy를 정했다 |
| 별도 HAProxy 노드(가상 IP 포함) | 기각. EC2가 늘고 새 단일 장애점이 생기며 AWS의 가상 IP는 추가 구성이 필요하다 |
| 리스 갱신도 동기 커밋(지금 그대로) | 기각. Standby가 없을 때 갱신이 멈춰 리스가 만료되고, 재개 순간 리더가 강등되는 2026-09-08 연쇄가 되살아난다. 재개마다 앱 페일오버가 붙고 강등이 차단기에 쌓인다 |
| 리스를 DynamoDB로(ADR 0017 경로) | 기각. 파티션 모드(P3)에서 쓸 수 없고(ADR 0034 결정 18) AWS 의존이 는다 |
| 쓰기 거절을 `503`으로 | 기각. 쓰기 업스트림이 `503` 한 건으로 리더를 빼고 팔로워도 `503`이라, 중단 동안 쓰기 업스트림을 지나는 계좌·주문 조회까지 `502`가 된다(S4b 설계 3-2). DB에 닿지 못할 때만 `503`이다 |
| 탐지를 `txid_current()`로 | 기각. WAL을 쓰지 않아 동기 대기를 타지 않는다(사전 확인 F) |
| 탐지를 Patroni REST(`/sync`)로 | 기각(주 신호로는). 커밋이 실제로 끝나는지를 보여 주지 않고 앱에서 8008로의 의존이 는다. HAProxy와 관측이 쓴다 |
| 중단 판정을 탐지 커밋 시간만으로 | 기각(설계 검토 R3). 한 노드의 경로만 느려도 그 노드가 중단으로 가고, 리더면 다른 노드가 멀쩡해도 중단이 풀리지 않는다 |
| 중단 판정에 `pg_stat_replication`의 동기 멤버 부재를 쓴다 | 기각. 소리 없는 정지에서 walsender가 `wal_sender_timeout`까지 멈춘 Standby를 `sync`로 보여 늦게 잡고, 앱 역할에 `pg_read_all_stats` 권한이 필요하다. 멤버 확인은 수집기와 경보로 한다 |
| `423`을 '확정 미접수'로 정의 | 기각(설계 검토 R2). 인그레스가 10초 뒤 다시 보낸 시도의 `423`이면 앞선 시도가 재개 뒤 커밋될 수 있다. '이 시도는 쓰지 않음'으로 좁히고 새 키 재발급을 금지한다 |
| 베이스백업의 기본 임시 슬롯 유지 | 기각(설계 검토 R1). `'*'` 아래에서 백업 스트림이 동기 대기 서버가 된다 |

## 영향

- 계약. [DB 설계](../contracts/백엔드_DB_설계.md) v2.60(§5.4 유효 범위, §5.4-a, §5.4-c 8, §5.4-f 신설, §7.1, §7.2), [API 설계](../contracts/백엔드_API_설계.md) v2.60(§0-2, §1 DB 자체 HA 행, §2-3, §2-5-e 신설, §4-A-7 신설, §4-B-4 (a), §4-F, §7-6), [하네스 검증설계](../contracts/하네스_검증설계.md)(Patroni 패치 노트, §3 E-P1~E-P9, §5-1, §5-2), [하네스 CI/CD](../contracts/하네스_CICD_IaC.md)(§3-5, §5-2, §8-1), [관리자 설계](../product/관리자_설계.md) v1.6(4-2 상황별 대본의 DB 문구)을 고쳤다. 같은 날 설계 검토 뒤 같은 판 안에서 다시 고쳤다. 새 마이그레이션은 없다.
- 쓰기 가용성. DB 노드 하나를 잃으면 그 노드가 Standby로 돌아올 때까지 거래가 멈춘다. Standby를 잃은 경우(E-P2)뿐 아니라 Primary를 잃은 경우(E-P1)도 같다. 새 Primary는 동기 Standby가 없어 옛 노드가 되감기로 돌아오거나 새 Standby가 만들어질 때까지 쓰기를 받지 않는다. 옛 노드를 영영 잃으면 사람이 새 인스턴스를 만들고 etcd 멤버를 바꾸고 새 Standby를 `--no-slot` 베이스백업(로컬 428.7MiB에 264.7초, ADR 0025)으로 붙일 때까지 멈춘다. 그 절차와 목표 시간은 [설계](../architecture/DB_자동전환_Patroni_설계.md) 9-5다. 목표는 경보부터 거래 재개까지 60분 이내이고 AI 제안이라 사용자 확인이 필요하다. 엄격을 잠시 끄는 비상 선택지(`patronictl edit-config -s synchronous_mode_strict=false`)는 그 창의 유실 0 주장을 포기하는 사람의 결정이고 자동화하지 않는다. 판단 기준, 증거, 엄격 복귀도 9-5에 있다.
- 처리량. 동기 복제는 비동기보다 느리다. 2026-09-08 로컬 실측에서 비동기에서 동기로 19.6%, AZ 왕복 2ms를 더하면 12.8%가 더 내려갔다(`sync-replication-20260908`, 회차 한정 비율). AWS 값은 재지 않았다.
- 유실 0의 범위. 단일 노드 장애의 전환에 한한다. 두 노드를 함께 잃거나 두 노드의 스토리지를 잃는 이중 장애는 WAL 아카이브가 없어 범위 밖이다(DB 설계 §7.2). 2026-09-25 로컬 Docker 한 호스트에서 E-P1, E-P3a, E-P3b, E-P5(두 번), E-P6(b)가 PASS해 DB 수준 ACK 기록기와 C16 모두 확인받은 쓰기의 유실이 0이었다. 이 결과는 로컬 시간 값(Patroni 20·5·5)과 워치독 없는 한 호스트의 것이고, AWS에서는 적용 뒤 같은 실험으로 다시 확인하기 전까지 주장하지 않는다.
- 운영. etcd 세 멤버와 Patroni 두 개, HAProxy 사이드카가 관리 대상에 들어온다. 보안 그룹에 2379·2380·8008 규칙이 는다. bot-1에는 etcd가 더해져 메모리 여유가 줄어든다. 관측·경보·대시보드 파일을 새로 만든다(설계 7절).
- 결정 순서. 계약은 코드보다 먼저 고쳤다. 코드와 Docker 실험, AWS 적용은 이 ADR과 설계의 순서를 따르고, 각 결과가 나오면 이 머리말의 상태를 고친다.

## 확인하지 못한 것

Patroni 4.1.5·etcd v3.5.34·HAProxy 3.0.27의 로컬 실험 스택에서 E-P1~E-P7을 돌렸다. 승격(22.75~27.68초, 시간 축까지 판정한 회차), Standby가 없을 때 Patroni가 거는 `'*'`, `failsafe_mode`로 Primary를 지키는 것(E-P4b), 옛 Primary의 `pg_rewind` 재합류(E-P1·E-P3a·E-P3b·E-P5), 빈 데이터 디렉터리의 재초기화와 `--no-slot` 전달(E-P6(b), 실행 중 `pg_basebackup` 명령줄)을 봤다. 리더 키를 쥔 채 빠르게 재기동한 노드는 전환 없이 돌아오지만 Patroni가 읽기 전용으로 띄운 뒤 스스로 승격해 타임라인이 하나 오른다(E-P1b). 이 변화는 판정하지 않고 기록한다(사용자 확정 2026-09-25). 되감기 실패 뒤의 자동 재초기화, 재초기화 베이스백업 연결의 `application_name`, 멤버 슬롯의 WAL 보존과 옛 멤버 슬롯의 정리는 확인하지 않았다. 결과를 모르는 주문 접수를 새 Primary에서 V47이 판정하는 장면은 E-P5 두 회차에서 나지 않았다. Docker Desktop에는 `/dev/watchdog`이 없어 Patroni 워치독을 쓸 수 없다. pgjdbc와 HikariCP 위의 커밋 대기는 Testcontainers 시험(`WriteAvailabilityIntegrationTest`, `SyncStandbyLossIntegrationTest`)으로 봤다. AWS의 SG 적용, softdog 적재, 학교 기존 클러스터의 Patroni 전환과 되돌리기, etcd 멤버 교체는 해 보지 않았다.
