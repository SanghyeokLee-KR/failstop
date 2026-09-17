# ADR 0009. 리스 하트비트를 전용 풀로 격리하고 서비스 정지가 지속되면 리더가 스스로 갱신을 포기한다

- 상태: 채택
- 날짜: 2026-07-28
- 결정자: 아키텍처 담당(정본 문서 관리자), backend-engineer(구현), reviewer(판정)

## 배경

리더십은 `leader_lease` TTL 갱신으로 유지된다. 그 갱신이 요청 트래픽과 같은 HikariCP 풀을 쓰면 부하가 곧 리더십 상실이 된다.

하네스 검증 E1(pg 지연 200/100) 라이브에서 그것이 실측됐다. downstream latency가 결과 패킷마다 지연을 더해 다중 행 쿼리 점유를 약 1s로 폭증시키고(주입 20ms에서도 리더 p99 1044ms), 무제한 봇 부하와 겹쳐 풀을 고갈시켜 하트비트를 굶겼다. 200·50·30·20 전 조합에서 epoch+1 페일오버가 났다(docs/reference/contracts/하네스_검증설계.md:26). 무결성은 항상 유지돼(C01~C23 0위반) CP 안전성은 정상이었고, 잃은 것은 가용성뿐이었다.

992e59b가 하트비트를 전용 풀로 격리해 이 축을 닫았다. 같은 E1을 정본 지연값으로 재실행한 결과 리더가 페일오버하지 않았고(epoch 불변), 해제 후 복구 4s, 무결성 0위반이었다(docs/reference/contracts/하네스_검증설계.md:27).

그 격리가 반대편 결함을 만들었다. 하트비트 생존성이 서비스 생존성에서 분리되면서, 요청을 하나도 처리하지 못하는 노드도 하트비트만은 성공한다. 리스가 계속 유효하니 스탠바이는 만료를 보지 못하고 승격하지 않는다. 라이브 실측(E1 200/100 주입 후 해제): 리더의 readiness·status 전량 타임아웃, `Failed to obtain JDBC Connection` 반복, 체결 30분 이상 0, 그 사이 `lease_until`만 계속 전진하고 epoch 불변. 리더 컨테이너 재기동으로만 해소됐다(docs/reference/contracts/백엔드_DB_설계.md:2714). 페일오버도 자력복구도 없는 클러스터 정지다.

## 결정

두 축을 함께 둔다.

- 리스 전용 커넥션 풀. `renew_leader_lease` 하트비트와 `try_acquire_leader` 승격 UPDATE, 승격 다운타임 관측 SELECT만 소형 전용 풀로 수행한다(`exchange.engine.lease-datasource`: maximum-pool-size 2·minimum-idle 1·connection-timeout 2000ms·connection-init-sql UTC). 접속 좌표는 `spring.datasource`를 재사용하고 크기·타임아웃만 분리한다. 기본 풀이 `@Primary`를 유지해 저널·정산·조회·Flyway 자동배선이 불변이다(server/app/src/main/java/kr/exchange/config/LeaseDataSourceConfig.java:28,47).
- 서비스 불능 지속 시 자발적 강등. renew 직전에 이 노드가 리더 일을 할 수 있는지 판정하고, 불가 상태가 임계 시간 동안 끊김 없이 지속되면 갱신을 포기한다. 판정 명제는 "쓰기 수요 대비 누적 완료율이 붕괴한 상태가 임계 시간 지속되면 리더 자격을 내려놓는다"이고 원인은 묻지 않는다.

판정의 구체 형태.

- 두 축뿐이고 둘 다 인메모리 카운터다. 수요는 AdmissionFilter(order 6) 통과 후 ApiAuthFilter가 DB 접촉 이전에 센다(server/app/src/main/java/kr/exchange/api/auth/ApiAuthFilter.java:81). 완료는 리더 기원 쓰기 커밋만 센다(CommandJournalAppender.java:70, SettlementService.java:146).
- 마지막 건강 판정 이후의 수요·완료를 누적해 비율을 낸다. 임계는 완료율 5%(`MIN_COMPLETION_RATIO`), 시간은 `exchange.engine.lease-standdown-after`(기본 45s). 창 초기화는 누적 비율이 임계를 회복할 때만 한다(LeaderStallDetector.java:72,168).
- 누적 수요가 20건 미만이면 판정을 보류한다. arm도 초기화도 하지 않는다(LeaderStallDetector.java:74,162).
- `lease_until`을 미는 경로는 앱에 하나뿐이고 판정이 그 안에 있다(LeaderLeaseService.java:423).
- `leader_lease`에는 아무것도 쓰지 않는다. 리스를 즉시 반납하지도 않는다. 승격은 종전대로 TTL 만료 후 스탠바이의 `try_acquire_leader`(epoch +1) 단독 UPDATE로만 성립한다(docs/reference/contracts/백엔드_DB_설계.md:2017).

정본 반영은 DB 설계 §5.4(v2.18·v2.19)와 하네스 검증설계 §3 E1 전제·E7' 행이다.

## 근거

- 전용 풀은 실측으로 갈렸다. 같은 실험·같은 지연값에서 992e59b 이전은 전 조합 페일오버, 이후는 epoch 불변이다.
- 신호를 비율로 잡은 것은 라이브 3회 실패의 결과다(docs/reference/contracts/백엔드_DB_설계.md:2004). 이 장애는 정지가 아니라 처리율 붕괴이므로 이진 조건이 전부 미끄러졌다.
- 임계 여백이 넓다. 정상 구간 완료율 2.4~3.9, 좀비 구간 0.04, 임계 0.05다. 정상 대비 약 50배 여백이라 임계 선택이 예민하지 않다(docs/reference/contracts/백엔드_DB_설계.md:2011). 다만 좀비 쪽 여백은 약 1.25배로 좁아, 완료 축 정의를 바꾸면 이 대조가 먼저 깨진다.
- 계측 지점이 판정의 전제다. 좀비 상태에서는 인증의 `api_keys` 조회부터 실패하므로 컨트롤러·게이트웨이·저널 append 중 어디에서 세든 수요가 0으로 관측된다.
- 판정이 DB를 타지 않는다. 판정에 DB 왕복을 넣으면 판정 자체가 굶어 하트비트를 막는다.
- 시간 임계 45s는 리스 TTL 기본 10s의 4.5배, 카오스 6s의 7.5배라 정상 갱신 주기와 겹치지 않고, 실측 좀비 지속(30분 이상)보다는 훨씬 짧다. `leaseStanddownAfter <= leaseTtl`은 기동 시 거부한다(EngineProperties.java:41).
- 원자성이 불변이다. 리스 행 무접촉이므로 만료 구동 승격 규칙·epoch 단조·stale append 0행 거부가 그대로다.

## 대안

- 하트비트를 공용 요청 풀로 되돌린다. 기각. 풀 포화가 정당 단독 리더를 헛페일오버시킨 것이 E1 실측이고, 좀비 리더는 그 교정의 부작용이지 교정의 오류가 아니다.
- 능동 커넥션 프로브로 생존성을 잰다. 기각(docs/reference/contracts/백엔드_DB_설계.md:2004). 992e59b 직후 E1은 리더가 페일오버하지 않는 것이 정답인 구간인데도 요청 풀 32가 포화되고 리더 status GET이 큐잉·타임아웃했다(docs/reference/contracts/하네스_검증설계.md:27). 그 구간에서 요청 풀을 빌리는 프로브는 실패해 과부하를 정지로 오판할 수 있다(추정). HikariCP 대기자 처리 순서는 저장소 안에 근거가 없어 기각 사유에서 뺐다.
- 지연(p99)을 판정 기준으로 삼는다. 기각. E1은 느려지되 체결이 계속되는 구간이다. 지연으로 강등하면 그것이 곧 헛페일오버다. 지연이 아니라 처리율 붕괴를 본다.
- 요청 풀 전량 점유(active >= maximum-pool-size)를 필수 조건으로 둔다. 기각. 1차 구현이 이것이었고 라이브에서 발동하지 않았다. 실제 좀비는 커넥션 대기가 아니라 CPU 폭주(chaos-api-1 463%, HikariCP housekeeper 지연 59초)로 죽어 있어 커넥션이 계속 반납됐고, 점유 조건이 거짓인 표본이 섞여 누적이 매번 초기화됐다(docs/reference/contracts/백엔드_DB_설계.md:2004). 풀 점유는 강등 로그의 사후 진단 정보로만 남긴다(f05198e, LeaderStallDetector.java:210).
- 완료 0을 조건으로 둔다. 기각. 2차 구현이 이것이었다. 노드는 완전히 죽지 않고 5분에 체결 1건을 내는 상태였고(정상은 초당 20건), 완료 1건이 누적을 초기화해 역시 발동하지 않았다. 누적 완료율로 교체했다(2a3ee53, docs/reference/contracts/백엔드_DB_설계.md:2004).
- 완료 축에 인증 조회 성공·계정 조회 성공·조기 거절을 포함한다. 기각. 3차 구현이 이것이었다. 축이 "이 노드가 주문을 처리하는가"가 아니라 "어딘가 DB 호출 하나가 성공했는가"를 재게 된다. 체결이 6분에 6건인 노드가 완료율 1.35를 유지했다(기준선 3.85, docs/reference/contracts/백엔드_DB_설계.md:2006). 읽기 완료 계상 3곳을 제거했다(3bebb0a).
- 전용 풀 세션에 `statement_timeout`을 건다. 기각. 단일 행 renew가 슬로 윈도에서 취소되면 그대로 리더십 상실이다. 운영 기본 풀은 3000ms를 유지한다.
- 강등 후 재승격을 허용한다. 기각. 기존 self-shutdown과 등급을 같이 둔다. 종신 강등이고 복귀는 재기동이다.

## 영향

- 설정 키 2개가 는다. `exchange.engine.lease-datasource.*`와 `exchange.engine.lease-standdown-after`(server/app/src/main/resources/application.yml:95,142). 스키마·함수 3종·마이그레이션은 무변경이다.
- 별도 DataSource 빈 도입으로 `DataSourceAutoConfiguration`의 풀 자동구성이 back-off 한다. 기본 요청 풀을 명시 정의해야 dev·prod·카오스 오버라이드(`SPRING_DATASOURCE_*`)가 보존된다(LeaseDataSourceConfig.java:25).
- 관측축 3개가 는다. `exchange.leader.standdown`, `exchange.node.serviceable`, `exchange.node.write_completion_ratio`. 하네스 CICD §5-2 명세표에 등재한다.
- 하네스에 실험 클래스가 는다. E7'(그레이 장애 2형, 자원 고갈 좀비). E7의 freezer는 renew 자체를 멈추므로 이 클래스를 재현하지 못한다. 문서 상태는 보강·미구현이다(docs/reference/contracts/하네스_검증설계.md:365).
- RTO 경로가 갈린다. 이 경로의 총 페일오버 시간은 `lease-standdown-after` + TTL이고, kill·pause 경로의 RTO<30s 게이트 대상이 아니다.
- 알려진 사각을 수용한다. 도메인 사유로 주문이 100% 거절되는 노드는 쓰기 커밋이 0이라 오탐이 날 수 있다. 완료 축을 읽기까지 넓히면 막히지만 그러면 신호가 눈먼다. 수요 하한 20건과 45초 임계로 교환을 감수한다.
- 판정 불변식이 하나 생긴다. 수용 제한으로 거절된 요청은 수요 축에 계상하지 않는다. 거절을 수요로 세면 부하를 정상적으로 흘리는 건강한 노드가 분모만 키워 헛강등한다. 계측 순서 [수용 판정 -> `recordAttempt` -> 인증]이 뒤집히면 백프레셔가 곧 페일오버가 된다(docs/reference/contracts/백엔드_DB_설계.md:2013).
- 대증요법이라는 한계가 남는다. 뿌리는 수용 제한 부재이며 별도 과제로 분리해 v2.20·v2.21에서 막았다(쓰기 40%·조회 40%·예비 20% 배분). E1은 그 뒤에 라이브 PASS했다(errorRate 0, epoch 불변, recoverySec 2.06, cpu 22%. 종전 463%/319%. docs/reference/contracts/하네스_검증설계.md:29).
- 후속 교정이 이어졌다. 승격 경계에서 판정 창을 초기화하지 않으면 갓 승격한 정상 리더가 임계 시간 뒤 확정적으로 재강등한다(v2.23, docs/reference/contracts/백엔드_DB_설계.md:1999). 리스 연장 경로가 둘이면 그중 하나가 판정을 건너뛴다(v2.24, 같은 문서 1997행).
- 강등 노드는 프로세스가 살아 남아 nginx primary 슬롯을 비우지 않으므로 결정 시점에는 약 1.25%(2s·40 ops/s 기준)의 POST 실패 바닥이 남았다. 이후 `harness/nginx/nginx.conf`에 `non_idempotent`를 켜 그 1건도 신 리더로 재시도되므로 현재 바닥은 0이고 오류율 예산에 넣지 않는다(docs/reference/contracts/하네스_검증설계.md:365).
- 되돌리려면 두 축을 따로 걷어야 한다. 자발적 강등만 되돌리면 좀비 리더가 재발하고, 전용 풀까지 되돌리면 E1 헛페일오버로 회귀한다. 되돌림 비용은 DB 설계 §5.4의 두 불릿과 그 파생 규정(v2.23·v2.24), 하네스 §3 E1 전제·E7' 행, 관측축 3개, 설정 키 2개를 함께 철회하는 것이다.
