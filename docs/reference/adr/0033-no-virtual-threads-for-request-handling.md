# ADR 0033. 요청 처리에 가상 스레드를 사용하지 않는다

- 상태: 채택. 운영 장애 대응으로 정한 실행 설정이며 커넥션 풀 구현을 바꾼 결정은 아니다.
- 날짜: 2026-09-21
- 대체 범위: [API 설계](../contracts/백엔드_API_설계.md)의 REST 계층 기술 선택 중 `spring.threads.virtual.enabled=true` 전제와 §7-5의 검증 결론.
- 근거: 학교 app-1에서 실제로 발생한 요청 처리 중단과 두 차례의 스레드 덤프. 원본은 [증거본](../../../증거본/2026-09-21_앱장애_행/README.md)에 있다.

## 배경

2026-09-21 08:18 UTC에 공개 서비스의 `/api/*`가 모두 30초 뒤 504를 반환했다. nginx의 연결 타임아웃은 3초이므로 프로세스가 죽었다면 즉시 502가 나야 한다. 앱은 `active (running)` 상태로 8080을 listen하고 있었고 호스트 안에서 호출한 관리 포트 readiness도 응답하지 않았다.

자원은 병목이 아니었다. 메모리는 944MiB 여유, 루트 디스크는 13% 사용, Primary·Standby 모두 5432 연결이 성립했고 `vmstat`의 steal은 0이었다. 대신 컨텍스트 스위치가 초당 459,033~573,169회, CPU 시스템 시간이 57~64%였다.

8분 간격으로 뜬 두 스레드 덤프는 모두 같은 세 지점에서 `Thread.yield()` 루프에 갇혀 있었다. `HikariPool-1:connection-adder`가 `ConcurrentBag.add`, `HikariPool-1:housekeeper`가 `ConcurrentBag.unreserve`, `market-catalog-refresh`가 `ConcurrentBag.requite`다. 세 지점 모두 대기자가 있을 때 연결을 인계 큐로 직접 넘기고 실패하면 양보하며 재시도하는 구간이다. JVM은 데드락을 보고하지 않았다.

앱은 `spring.threads.virtual.enabled: true`로 실행 중이었고 덤프의 `ForkJoinPool-1-worker-1`은 가상 스레드를 운반하는 상태였다. 연결을 기다리는 쪽이 가상 스레드여서 인계가 성립하지 않고, 반납하는 쪽은 플랫폼 스레드라 양보 루프가 CPU를 그대로 소모한다. 2 vCPU에서 이 플랫폼 스레드 세 개가 코어를 채우면 캐리어 스레드가 스케줄될 여유가 없어 인계가 더 어려워지는 자기강화 구조가 된다.

기존 §7-5는 가상 스레드와 JDBC 조합에서 풀 크기 고정과 `synchronized` 구간의 캐리어 고정만 위험으로 다뤘다. 연결 반납 인계가 성립하지 않아 생기는 양보 스핀은 다루지 않았다.

## 결정

요청 처리에 가상 스레드를 사용하지 않는다. [application.yml](../../../server/app/src/main/resources/application.yml)의 `spring.threads.virtual.enabled`를 `false`로 두고 프로파일에서 다시 켜지 않는다. 학교 app-1에는 재배포 없이 `/etc/exchange/app.env`의 `SPRING_THREADS_VIRTUAL_ENABLED=false`로 먼저 적용했고, 이 ADR로 저장소 기본값을 같은 값으로 맞춘다.

이 결정은 Spring이 관리하는 실행기에만 적용된다. `BotFleet`이 `Thread.ofVirtual()`로 직접 만드는 봇 스레드는 이 설정의 대상이 아니며 이번 결정으로 바꾸지 않는다. 봇 스레드는 `BotApiClient`로 HTTP 주문을 보내고 DB 접근은 기동 시 시드와 제어 명령 경로에 있어 같은 인계 구간을 반복해서 지나지 않는다. 다만 봇 JVM에서 같은 조합이 성립하지 않는다는 것을 시험으로 확인하지는 않았다.

다시 켜려면 연결 인계 경로를 먼저 바꾸고, 실제 부하 조건에서 라이브락이 재현되지 않는 것을 확인한다. 커넥션 풀 구현이나 버전을 바꾸는 판단은 이 ADR의 범위가 아니다.

## 근거와 한계

이 조치는 관측한 실패 경로를 제거한 것이고 HikariCP의 인계 실패 자체를 고친 것은 아니다. 같은 조합을 다시 켜면 재발할 수 있다.

조치 후 같은 항목을 다시 측정해 대조했다. 컨텍스트 스위치는 초당 78~1,227회, 시스템 시간은 1~2%로 내려갔고 `ConcurrentBag`의 양보 프레임과 가상 스레드 운반 표시는 0건이었다. 요청 스레드는 `http-nio` 플랫폼 스레드 24개로 바뀌었다. 호스트 내부 `/api/v1/markets`는 0.004초, 공개 HTTPS의 같은 경로는 33ms에 200을 반환했고 봇 체결도 이어졌다.

다만 장애를 의도적으로 재현해 원인을 확정하지는 않았고 최초 유발 계기도 확인하지 못했다. 플랫폼 스레드 풀이 더 높은 부하와 장시간 운영에서 충분한지도 아직 측정하지 않았다. 현재 봇 목표는 세 종목 합계 3 OPS이며 이 규모에서 스레드 모델 변경에 따른 처리량 저하는 관측하지 않았다. 과거 로컬 Docker에서 얻은 처리량 수치를 이 설정의 성능 근거로 쓰지 않는다.

가상 스레드를 채택하며 기대했던 DB 대기 구간의 tail latency 이점은 포기한다. 현재 우선순위는 발표 전까지의 운영 안정성이며, 조회 계층을 ALB·ASG로 확장하는 목표는 [ADR 0027](0027-msp-operations-and-autoscaling.md)과 [ADR 0030](0030-nat-free-lab-and-bounded-read-asg.md)을 그대로 따른다.

## 영향

[API 설계](../contracts/백엔드_API_설계.md)의 REST 계층 표와 §7-5를 이 결정에 맞춰 갱신한다. 풀 크기를 작게 고정한다는 기존 기준과 트랜잭션 경계를 짧게 유지한다는 기준은 유지한다. [0099](0099-current-architecture.md)의 현재 구조 설명과 ADR 표, [로드맵](../../ROADMAP.md)의 진행 상태, [AWS 작업 안내](../../reports/AWS_작업.md)의 운영 기록에도 같은 변경에서 반영한다.
