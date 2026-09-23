# ADR 0002. 단일 라이터 권위를 DB leader_lease 행에 두고 외부 합의·분산락을 배제한다

- 상태: 채택. 부분 대체: ADR 0017(갱신 축), ADR 0026(잠금 모드), [ADR 0034](0034-market-partitioned-leadership.md)(마켓 파티션 리스, 단계 진행 중). 2026-09-23 주: 0034가 결정 절의 "마켓별 리스 분할은 금지한다"(29행)와 대안 절의 마켓별 `leader_lease` 행 분할 기각(53행)을 대체해 `matching-p0`·`matching-p1` 두 리스 행을 두기로 했다. 효력은 파티션 리더(P3) 전환부터이며 그 전까지 실제 동작은 이 문서의 단일 행 리스다. 강등 시 즉시 반납 기각(56행)은 0034도 유지한다.
- 날짜: 2026-07-22
- 결정자: 아키텍처 담당(정본 문서 관리자). 계약 충돌 신고는 harness-engineer(하네스 설계 부록 A #6)

## 배경

매칭은 인메모리 단일 라이터다. 두 프로세스가 동시에 엔진을 돌리면 원장이 갈라진다. 그 차단 장치의 권위를 어디에 둘 것인지가 미결이었다. 결정을 강제한 것은 설계 결함 세 건과 문서 간 계약 충돌 한 건이다. 결함은 발견 라운드가 다르다. 첫 건이 초안 검증 소산이고, 나머지 둘은 v2.3 제출 후 잔여 검증에서 지적됐다(docs/reference/contracts/백엔드_DB_설계.md:2451 v2.4 패치 머리말).

- 초안 fencing이 실제로 막지 못했다. `read-then-insert` 비원자 + `>=` 비교 + 저널 max 참조 조합이라 같은 epoch를 든 두 리더가 함께 통과한다(docs/reference/contracts/백엔드_DB_설계.md:1935, 같은 문서 §8 요약 :2290).
- 부트스트랩 교착. 승격을 조건부 UPDATE 단독으로 두면 대상 행이 선재해야 한다. 빈 테이블에서는 UPDATE가 항상 0행이고, 0행은 규칙상 "타 리더 존재"로 읽히므로 최초 선출이 영구 불가다(DB 설계 v2.4 패치 #6, docs/reference/contracts/백엔드_DB_설계.md:2471-2473).
- 갱신 규칙 부재. 승격문으로 하트비트를 대신하면 epoch가 갱신마다 올라 진행 중 append가 자기 하트비트에 자기-fencing된다. 반대로 갱신을 하지 않으면 TTL마다 탈취·복구가 도는 가용성 플래핑이 된다(DB 설계 v2.4 패치 #7, :2474).
- 계약 충돌. API 설계는 리더선출 권위를 Redisson RLock으로 서술했다. 같은 시기 하네스 설계는 DB `leader_lease` 권위를 전제로 E3(Redis 장애)를 설계했다. 두 정본이 다른 모델을 가리키는 상태로는 카오스 실험의 기대값을 확정할 수 없다(docs/reference/contracts/하네스_검증설계.md:360 E3 전제 칸).
- 그 충돌을 harness-engineer가 부록 A #6(HIGH)으로 등재하고 API 문서 정정 선행을 권고했다(docs/reference/contracts/하네스_검증설계.md:746). 권고만으로는 어느 쪽이 권위인지 정해지지 않으므로 결정이 필요했다.

## 결정

매칭 단일 라이터 권위를 `leader_lease` 단일 행(`resource='matching-engine'`)에 둔다. 외부 합의 코디네이터와 분산락은 권위 경로에서 배제한다.

- 테이블은 `resource`(PK)·`holder`·`epoch`·`lease_until` 4컬럼이고 운영 중 행은 하나다(V1__init.sql:697-702).
- 부트스트랩 시드 행을 만료 상태로 미리 넣는다. `('matching-engine', '', 0, now() - interval '1 s')`(V1__init.sql:703-706).
- 승격은 `try_acquire_leader`. `lease_until < clock_timestamp()`를 UPDATE 술어에 포함한 조건부 갱신이며, 만료된 리스만 탈취하고 `epoch = epoch + 1` 후 획득한 epoch를 반환한다. 실패는 NULL이다(V1__init.sql:737-749).
- 갱신은 `renew_leader_lease`. `holder`·`epoch` 일치하고 만료 전인 경우만 `lease_until`을 민다. epoch는 보존한다(V1__init.sql:752-762).
- 그 epoch가 fencing 토큰의 유일한 원천이다. 자산을 바꾸는 모든 엔진 기원 트랜잭션은 커밋 전 `assert_current_leader(holder, epoch)`를 호출하고, holder·epoch 불일치 또는 리스 만료면 예외로 롤백한다(V1__init.sql:715-733).
- 저널 append와 정산은 그 호출에 더해 같은 트랜잭션에서 `leader_lease`를 `FOR SHARE`로 잠근 채 epoch 일치를 재검증한다. 0행이면 stale 확정이다(CommandJournalAppender.java:32, EventJournalAppender.java:38 · :88, SettlementService.java:60 · :167). (잠금 모드는 ADR 0026에서 FOR KEY SHARE로 바뀌었다)
- TTL과 갱신 주기는 `tickInterval < ttl`을 기동 시 강제한다. 갱신 주기가 TTL 이상이면 정상 리더도 만료를 반복해 탈취·복구 플래핑이 된다(LeaderLeaseService.java:106-109).
- `holder`는 호스트명이 아니라 프로세스 기동마다 새로 발급하는 UUID다(LeaderLeaseService.java:52). 같은 호스트에서 재기동한 구·신 프로세스를 구분하지 못하면 fencing이 무력화된다.
- 마켓별 리스 분할은 금지한다. 전역 seq 채번 권위가 분열되기 때문이다(V1__init.sql:693-694, docs/reports/프로젝트_이해하기.md:145). (2026-09-13 주: ADR 0022 가 채번을 마켓 대역으로 가르지만 리스는 하나로 남는다. 리스 분할 금지는 유지된다)
- 강등은 리스 행에 아무것도 쓰지 않는다. 갱신을 멈출 뿐이다. 다음 리더는 종전대로 TTL 만료 뒤 `try_acquire_leader`(epoch +1) 단독 UPDATE로만 정해진다(docs/reference/contracts/백엔드_DB_설계.md:1989 · :2017, LeaderLeaseService.java:401-408).
- `lease_until`을 미는 앱 경로는 정확히 하나다. 하트비트와 fencing 거부 재확인이 같은 `renew_leader_lease`를 쓰므로 생존성 판정을 그 단일 경로 안에 넣는다(LeaderLeaseService.java:415-432, docs/reference/contracts/백엔드_DB_설계.md:1997).

앱 측 구현은 이 함수 3종을 호출만 한다. `LeaderLeaseService`가 승격·갱신 틱과 강등 판정을 들고 있으며, 저장소 베이스라인 커밋 8e8e1fa 시점부터 존재한다.

## 근거

- 승격 원자성이 DB 한 문장으로 닫힌다. 만료 검사와 탈취가 같은 UPDATE의 술어·SET이라 check-then-act 창이 없다. 승격 경로가 이 문장 하나뿐이므로 epoch 단조 증가가 경로 수와 무관하게 성립한다.
- 판정과 쓰기가 같은 트랜잭션에 든다. 외부 락은 락 획득과 DB 커밋이 서로 다른 시스템이라 그 사이가 항상 열려 있고, GC pause 한 번이 stale 리더의 커밋을 통과시킨다. `assert_current_leader`는 정산·저널 트랜잭션 내부에서 호출되어 롤백이 곧 판정이다. 현재 SQL 호출 지점은 세 곳이다(CommandJournalAppender.java:87, EventJournalAppender.java:83, SettlementService.java:163).
- 그것만으로는 부족해서 `FOR SHARE`를 덧댔다. 잠금 없는 스냅샷 읽기면 문장 실행과 COMMIT 사이가 열리고, fencing 문장이 예약·정산 비즈니스 트랜잭션 내부에 있는 규칙상 그 창이 GC pause 등으로 수 초까지 벌어진다. `FOR SHARE`는 탈취 UPDATE를 이 트랜잭션 종료까지 대기시켜 stale append가 커밋되는 레이스 창 자체를 닫는다(docs/reference/contracts/백엔드_DB_설계.md:1969-1976). (ADR 0026 이후 같은 성질을 FOR KEY SHARE가 낸다. 전제는 holder·epoch가 키 열이라는 것이다)
- 그 대가는 승격 지연 한 건으로 유계다. 탈취·하트비트 UPDATE가 진행 중 append의 커밋을 기다리지만, 단일 라이터의 직렬 실행상 대기는 최대 트랜잭션 1개 분량이다(같은 절). (ADR 0026: 이 유계는 마켓별 라이터 배선 cd2e026 이후 거짓이었다. 현재 하트비트는 기다리지 않고 탈취가 진행 중 쓰기 트랜잭션 전부를 기다린다)
- 판정 결과가 데이터로 남는다. `trades.fencing_epoch`(V1__init.sql:229), `command_journal.fencing_token`(:580), `event_journal.fencing_epoch`(:612), `snapshots.fencing_epoch`(:659)에 승격 epoch를 저장해 stale 쓰기를 사후 감사한다. 런타임 차단과 사후 증명이 같은 토큰을 쓴다.
- 승격 실패 신호가 값 하나로 끝난다. `try_acquire_leader`의 NULL 반환이 곧 "유효한 타 리더 존재"이고, 앱은 이를 `optional()`로 받아 그대로 판정한다(LeaderLeaseService.java:315-322). 실패 여부를 알기 위한 별도 상태 조회가 없으므로 조회와 판정 사이의 창도 없다.
- 비교 기준을 `clock_timestamp()`로 잡았다. `now()`는 트랜잭션 시작 시각으로 고정되어 장기 트랜잭션이 만료된 리스를 유효로 오판한다(V1__init.sql:710-711).
- 시드 행이 승격 경로를 하나로 유지한다. 시드가 없으면 "빈 테이블이면 INSERT" 분기를 따로 둬야 하고, 그 분기가 두 번째 승격 경로가 되어 원자성 주장이 깨진다.
- `assert_current_leader`는 의도적으로 리스 행을 잠그지 않는다. 잠그면 모든 정산 트랜잭션이 단일 행 잠금에 직렬화되고 리스 갱신 UPDATE의 liveness가 깨진다. 잠금 없는 읽기의 보완이 저장된 fencing 컬럼 감사다(V1__init.sql:712-714). (ADR 0026: 쓰기 경로 잠금은 FOR KEY SHARE이고 갱신과 호환이다)
- 정상 경로의 판정 비용이 0이다. 리더가 아니면 인메모리 epoch가 null이므로 DB 왕복 없이 즉시 거부한다(LeaderLeaseService.java:135-141). 권위는 DB에 있지만 상시 비용은 갱신 틱 하나로 상각된다.
- 운영 부품이 늘지 않는다. PostgreSQL은 이미 SoT이며 리스는 그 안의 행 하나다. 권위를 외부 시스템에 두면 "DB는 살아 있는데 코디네이터가 죽은" 상태가 새 장애 축으로 생긴다.

## 대안

- Redisson RLock 리더선출. 기각. API 설계가 이 모델을 서술했으나 채택하지 않았다. 락 만료와 DB 커밋이 다른 시스템이라 fencing이 트랜잭션 원자성을 얻지 못하고, Redis가 죽으면 권위 자체가 사라진다. Redis는 권위가 아니라 후보 조율·알림 보조로 강등했다(docs/reports/프로젝트_이해하기.md:248). 실제 배선도 하지 않았다. `grep -rniI "redisson" server/`는 2건이고 둘 다 주석이며, 둘 다 리더선출이 아니라 레이트리밋 분산화의 M2 유예 언급이다(AccountRateLimiter.java:16, PublicRateLimitFilter.java:23). `grep -rniI "redis" --include=*.gradle --include=*.kts --include=*.toml server/`는 0건이라 의존조차 없다.
- etcd·ZooKeeper 등 외부 합의 코디네이터 도입. 기각. 앱 계층 단일 라이터를 위해 별도 쿼럼 클러스터를 운영·검증해야 하고, 위와 같은 이유로 fencing이 여전히 DB 트랜잭션 밖에 남는다. 다만 이 축을 버린 것이 아니라 계층을 나눴다. DB 클러스터의 primary 교체와 STONITH는 Patroni + etcd 운영 요건으로 분리했다(docs/reference/contracts/백엔드_DB_설계.md §7.2, docs/reports/프로젝트_이해하기.md:184-190).
- 마켓별 `leader_lease` 행 분할. 기각. 마켓 단위 병렬 승격으로 처리량을 늘릴 수 있으나 `seq`·`event_seq`가 전역 gapless 시퀀스라 채번 권위가 분열된다. 재생도 전 마켓 통합 전역 seq 오름차순 단일 패스라 마켓별 독립 리스는 크래시 창을 놓친다(docs/reports/프로젝트_이해하기.md:168). (2026-09-13 주: `event_seq` 는 V36 부터 마켓 카운터 파생이고, `seq` 는 ADR 0022 6단계에서 마켓 대역 채번이 되며, 재생은 마켓별 2상으로 바뀐다. 리스는 여전히 하나이고 이 기각의 결론은 유지된다)
- PostgreSQL advisory lock(`pg_try_advisory_lock`). 기각. 정본 문서에 별도 기각 기록은 없고 아래는 이 ADR에서 정리한 사유다. advisory lock은 세션 수명에 묶이고 단조 증가 토큰을 남기지 않는다. 저장된 `fencing_epoch` 감사 축이 사라져 사후 증명이 불가능해지고, 커넥션 풀 위에서 세션 동일성을 보장하는 부담이 새로 생긴다.
- 리더를 두지 않고 전 노드가 DB 행 잠금으로 직렬화한다. 기각. 선출 자체가 사라지므로 가장 단순하지만, 매칭을 DB 왕복에 묶어 처리량 상한을 잠금 경합에 내준다. DB는 매칭 핫패스가 아니라 SoT이자 최종 방어선이라는 전제와 정면으로 어긋나고(docs/reference/contracts/백엔드_DB_설계.md:5), 인메모리 결정론 상태기계·재생 복구 구조 전체가 성립하지 않는다.
- 강등 시 리스 즉시 반납. 기각. 페일오버를 TTL만큼 앞당길 수 있으나, 반납 UPDATE가 승격 경로 밖에서 리스 행을 쓰는 유일한 예외가 된다. 그 순간 "리스 행을 바꾸는 것은 만료 구동 단독 UPDATE뿐"이라는 불변이 깨지고 epoch 단조·stale append 0행 거부의 논거도 함께 약해진다. 자발적 강등도 회복 가능 반납도 리스 행에는 쓰지 않는 쪽으로 확정했다(docs/reference/contracts/백엔드_DB_설계.md:1989 · :2017).

## 영향

- 문서 정합 부채가 남았다. API 설계는 v2.33 시점에도 리더선출을 Redisson RLock으로 적는다(docs/reference/contracts/백엔드_API_설계.md:61 §0-2 토폴로지 다이어그램, :94 §1 선정표 행). 개관 §5가 이 상충을 스스로 기록하고 API 문서 정정이 선행돼야 한다고 남겼다(docs/reports/프로젝트_이해하기.md:255). 미해소 항목이다.
- 카오스 E3(Redis 장애)의 판정 성격이 바뀌었다. Redis가 권위 경로에 없으므로 매칭·리더십·split-brain에 도달할 경로가 없고, E3 SKIP은 미완이 아니라 유효 판정이다(868a152).
- 쓰기 경로 확장 시 계약이 따라붙는다. 자산을 바꾸는 엔진 기원 트랜잭션을 새로 추가할 때마다 같은 트랜잭션 안에 `assert_current_leader` 호출을 넣어야 한다. 빠뜨리면 split-brain 차단 주장이 그 경로에서만 조용히 무너진다. 개관도 이 주장을 조건부로 적는다(docs/reports/프로젝트_이해하기.md:153).
- 검증 축이 하나 생겼다. 저장된 epoch가 감사 가능해지면서, 리더 epoch가 seq 오름차순으로 비감소여야 한다는 불변식을 검사로 걸었다. 라이브 강제는 `assert_current_leader`이고 이 감사는 그 백스톱이라 상시 스케줄에서 제외하고 정지 시점·실험 사후 권위 run에서만 평가한다(C22_FENCING_MONOTONIC, Scope.AUTHORITATIVE_ONLY, InvariantCheck.java:250-252, docs/reference/contracts/하네스_검증설계.md:512). 2026-09-14 주: 인용한 InvariantCheck.java:250-252 는 ADR 0022 3단계 병합 전 판이고, 병합 판의 C22 는 :267 에서 seq 순 한 줄이 아니라 시간 축 팔과 마켓 안 팔로 epoch 비감소를 본다(ADR 0022 6-2).
- 부팅 경로에 대기 절차가 생겼다. 강제 종료 직후 재기동은 직전 프로세스의 리스가 TTL 내 유효하므로, 부팅 복구는 즉시 실패하지 않고 TTL 1.5배(상한 30초)까지 짧은 간격으로 `try_acquire_leader`를 재시도한다. 만료 전 탈취가 없다는 승격 규칙은 그대로다(docs/reference/contracts/백엔드_DB_설계.md:1984, d24e9f7).
- 관측이 승격 문장에 종속됐다. `try_acquire_leader`가 `lease_until`을 덮어쓰므로, 페일오버 다운타임(승격 시각 - 구 리더 최종 심박)은 취득 시도 **전에** 구값을 읽어야만 산출된다(LeaderLeaseService.java:311-313 · :363-381). 하네스는 같은 요구를 T1 선캡처 승격 SQL로 등재했다(docs/reference/contracts/하네스_검증설계.md:610, 부록 A #10 행 :729).
- 처리량 상한이 단일 라이터에 고정됐다. 마켓별 리스 분할을 금지했으므로 매칭 수평 확장은 리스 구조 변경이 아니라 전역 seq 채번 권위를 다시 설계하는 별도 결정을 요구한다. (2026-09-13 주: 그 별도 결정이 ADR 0022 다. 리스는 하나로 두고 마켓별 대역 채번과 마켓별 접수 락으로 넓힌다)
- 범위 한계를 명시해야 한다. `leader_lease`는 같은 primary에 접속한 앱 인스턴스 사이의 장치이고 DB 클러스터 primary 교체는 조율하지 못한다. RPO=0 주장도 그 범위로 한정했다(docs/reference/contracts/백엔드_DB_설계.md:2264, docs/reports/프로젝트_이해하기.md:182).
- 리스 자체가 후속 결함 계보의 출발점이 됐다. 하트비트를 요청 풀에서 전용 소형 풀로 격리하자(v2.18, 992e59b) 하트비트 생존성이 서비스 생존성과 분리되어 좀비 리더가 성립했고(v2.19), 그 뿌리인 수용 제한 부재를 다시 막았다(v2.20). 좀비 라이브 실측은 요청 풀 고갈 리더가 30분 넘게 체결 0인 채 `lease_until`만 전진하고 epoch는 불변인 상태였다(docs/reference/contracts/백엔드_DB_설계.md:2002).
- 그 계보의 실측 대비는 완료율 비율로 남았다. 정상 구간 2.4~3.9, 좀비 구간 0.04다(docs/reference/contracts/백엔드_DB_설계.md:2011). 리스만으로는 "리더가 살아 있다"와 "리더가 일을 한다"를 구분하지 못한다는 것이 이 결정의 잔여 비용이고, 자발적 강등 판정기가 그 자리를 메운다.
- 강등 등급 분리도 이 결정의 파생물이다. E2 라이브에서 스탠바이가 epoch 2를 획득한 123ms 뒤 승격 재생 정산이 `assert_current_leader` 거부를 받아 종신 강등했고, 같은 창에서 구 리더도 종신 강등해 승격 가능한 후보가 0이 됐다. 무결성은 유지됐고 결함은 가용성 등급 판정에 있었다(docs/reference/contracts/백엔드_DB_설계.md:1988).
- 판정 전체가 DB 시계 단조성에 얹힌다. `assert_current_leader`의 만료 조건(`lease_until < clock_timestamp()`)과 `renew_leader_lease`의 성공 조건(`lease_until >= clock_timestamp()`)은 같은 행·같은 시각에 정확히 여집합이다(docs/reference/contracts/백엔드_DB_설계.md:1990). 시계 후진은 이 여집합 관계를 깨므로 코드가 아니라 하네스 §3 전제조건으로 분리했고, 카오스 페일오버 실험에는 시계 스큐 가드를 붙였다(18b8f7e).
- 두 계층의 접합점은 정의돼 있다. DB primary가 교체되면 앱은 신규 primary로 재접속해 승격 절차를 새로 수행하고, `holder`가 기동별 UUID이므로 구 리더 세션의 잔존 리스는 epoch 탈취로 무효화된다(docs/reference/contracts/백엔드_DB_설계.md:2277).
- 개정한 정본 절은 다음과 같다(ADR 0001 §영향 링크 규칙). docs/reports/프로젝트_이해하기.md §3.1·§5, docs/reference/contracts/백엔드_DB_설계.md §5.4·§7.1, docs/reference/contracts/하네스_검증설계.md §3 E3 전제 칸·부록 A #6. docs/reference/contracts/백엔드_API_설계.md §0-2·§1은 미개정으로 남았다.
- 되돌리기 비용. 권위를 외부 코디네이터로 옮기려면 세 가지를 함께 해야 한다. (1) V1 함수 3종과 시드 행 제거, (2) NOT NULL인 fencing 컬럼 네 곳에 새 토큰 원천 공급, (3) 트랜잭션 내부 판정을 트랜잭션 밖 락 상태 조회로 교체. 실질 장벽은 (3)이다. 판정이 커밋과 같은 트랜잭션을 떠나는 순간 이 ADR이 사려던 성질을 잃는다.
