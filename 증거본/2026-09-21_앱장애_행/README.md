# 학교 AWS 앱 노드 무응답 진단 (HikariCP 라이브락)

2026-09-21 08:18~08:20 UTC(17:18~17:20 KST)에 학교 계정 app-1 `i-0465dd41a0390f262`에서 수집했다. 공개 서비스 `https://www.sang.it.kr`의 `/api/*` 경로가 응답하지 않는 상태에서 실행한 읽기 전용 진단이며, 이 시점까지 서비스 재시작·설정 변경·자원 변경은 하지 않았다. 최신 운영 상태는 [로드맵](../../docs/ROADMAP.md), 재현 순서는 [AWS 작업 안내](../../docs/reports/AWS_작업.md)를 따른다.

접속은 사용자가 앱 보안 그룹 `sg-0e321b38da5cc8aaf`에 작업 PC 공인 IP 한 개(`112.76.112.180/32`)를 허용하는 임시 규칙 `sgr-0d9048f7fa2710caa`를 추가한 뒤 기존 키페어 `2026-ai-kopo-04-ssh-01`로 직접 SSH했다. 이 규칙은 진단용 임시 허용이며 작업 종료 후 제거 대상이다. 호스트 키는 최초 접속에서 수락했고 기존 SSM 경유 경로에서 수집한 known_hosts와 대조하지는 않았다. 비밀 값은 수집·저장하지 않았고 설정 grep 출력에서 마스킹했다.

## 확인한 증상

외부에서는 정적 웹과 Grafana가 정상 응답하는데 `/api/v1/markets`와 `/api/v1/candles`만 30초를 채우고 504를 반환했다. nginx의 `proxy_connect_timeout`은 3초, `proxy_read_timeout`은 30초이므로 프로세스가 죽었다면 연결 거부로 즉시 502가 나야 한다. 504가 난 것은 8080 연결은 성립하고 응답만 오지 않는다는 뜻이다. 호스트에서 확인한 결과도 같았다. `exchange-app` 서비스는 `active (running)`이고 PID 49460이 8080·8081을 루프백에 listen 중이었으나, 호스트 안에서 직접 호출한 `/api/v1/markets`와 관리 포트 `/actuator/health/readiness` 모두 15초 타임아웃으로 응답하지 않았다.

자원은 병목이 아니었다. 메모리는 1,909MiB 중 944MiB 여유이고 서비스 사용량은 500MiB로 한도 1.3GiB 아래였다. 루트 디스크는 20GiB 중 13% 사용, inode 1% 사용이다. 현재 Primary `172.31.33.195`와 Standby `172.31.1.229` 모두 5432 TCP 연결이 성립했고 앱이 Primary로 12개 연결을 유지하고 있었다. `vmstat`의 steal은 0이므로 t3 CPU 크레딧 소진도 아니다.

대신 CPU가 커널에서 타고 있었다. load average는 2 vCPU에서 3.37이고 `%Cpu`는 사용자 36~43%·시스템 57~64%, 컨텍스트 스위치는 초당 459,033~573,169회였다. java 프로세스 단독으로 %CPU 182.4를 차지했다.

## 원인

스레드 덤프를 8분 간격으로 두 번 떴고 두 번 모두 동일한 세 지점에서 `Thread.yield()` 루프에 갇혀 있었다. `HikariPool-1:connection-adder`는 `ConcurrentBag.add(224)`, `HikariPool-1:housekeeper`는 `ConcurrentBag.unreserve(322)`, `market-catalog-refresh`는 `ConcurrentBag.requite(198)`이다. 세 지점 모두 HikariCP가 대기자가 있을 때 인계 큐에 반납을 시도하고 실패하면 양보하며 재시도하는 구간이다. JVM은 데드락을 보고하지 않았다. 진행이 없는 채로 CPU만 소모하는 라이브락이다.

앱은 `spring.threads.virtual.enabled: true`로 실행 중이다. 덤프의 `ForkJoinPool-1-worker-1`은 `Carrying virtual thread #1450149` 상태였고 가상 스레드 번호와 `connection-adder`의 스레드 번호 `#1450104`는 기동 이후 백만 단위의 스레드가 생성됐음을 보여 준다. 연결을 기다리는 가상 스레드는 언파크 즉시 인계를 받지 못하는데, 반납하는 쪽은 플랫폼 스레드라서 양보 루프가 CPU를 통째로 소모한다. 2 vCPU에서 이 플랫폼 스레드 세 개가 코어를 채우면 캐리어 스레드가 스케줄될 여유가 없어 요청 처리가 더 밀리는 자기강화 구조가 된다.

즉 DB 장애나 메모리 부족이 아니라 가상 스레드와 현재 HikariCP 조합에서 발생한 연결 인계 라이브락이다. 다만 이번 진단은 실행 중 상태의 관측이며 동일 조건을 재현해 원인을 확정한 것은 아니다. 최초 유발 시점과 계기는 확인하지 못했다. 서비스 로그에는 30분 주기 WebSocket 통계만 남아 있고 오류 기록이 없다.

## 파일 읽는 순서

TXT는 실제 명령과 출력 원문이다. 화면 캡처는 이번 작업이 브라우저 콘솔이 아닌 SSH 세션이라 남기지 않았고, 대신 명령과 출력을 그대로 보존했다.

- [01-pre-state.txt](01-pre-state.txt): 수집 시각, load, 메모리, 디스크, vmstat, top, 서비스 상태, listen 포트, DB 연결 시험, 호스트 내부 호출 결과
- [02-threads-top.txt](02-threads-top.txt): 스레드별 CPU 점유와 덤프 채집 과정
- [10-top-threads.txt](10-top-threads.txt): `top -H` 원문
- [20-threaddump-1.txt](20-threaddump-1.txt): 1회차 스레드 덤프 원문(journald 접두사 포함)
- [20-threaddump-1.clean.txt](20-threaddump-1.clean.txt): 같은 덤프에서 접두사만 제거한 읽기용 사본
- [23-threaddump-2.txt](23-threaddump-2.txt): 8분 뒤 2회차 덤프. 같은 세 지점에 갇혀 있음을 확인
- [30-config-grep.txt](30-config-grep.txt): 풀 크기 설정 grep 결과. 비밀 값은 마스킹

풀 상한은 요청 16, append 14, 리스 2로 문서 기록과 같다. 이 값 자체가 이번 라이브락의 원인이라는 근거는 확보하지 않았다.

## 조치와 복구 확인

사용자 승인으로 두 가지를 적용했다. 먼저 `/etc/exchange/app.env`를 기존 명명 규칙에 맞춰 `app.env.before-vthreads-20260921`로 복사하고 SHA-256이 같음을 확인했다. 그다음 되돌리는 방법과 근거를 주석으로 적고 `SPRING_THREADS_VIRTUAL_ENABLED=false` 한 줄을 추가했다. 파일 권한은 `root:exchange 0640`을 유지했다. 애플리케이션을 다시 빌드하거나 배포하지는 않았고 JAR과 코드 리비전은 그대로다. 저장소의 `application.yml`에 남은 `spring.threads.virtual.enabled: true`도 수정하지 않았으므로, 이 서버는 환경변수로만 덮어쓴 상태다.

08:21:19 UTC에 `systemctl restart exchange-app`을 실행했고 PID는 49460에서 169481로 바뀌었다. 기동은 12.7초가 걸렸다. 엔진 복구는 마켓별 스냅샷 `[1@414037, 2@413887, 4@413992]`를 채택하고 83건을 재생했으며, 리더 리스는 epoch=6으로 승격한 뒤 모든 마켓이 head에 도달해 승격 재생을 생략했다. readiness는 `ACCEPTING_TRAFFIC`으로 전이했고 08:22:07에 200을 반환했다.

복구는 장애 때와 같은 항목으로 다시 측정해 대조했다.

| 항목 | 장애 중 | 조치 후 |
|---|---|---|
| 컨텍스트 스위치 | 초당 459,033~573,169 | 초당 78~1,227 |
| CPU 시스템 시간 | 57~64% | 1~2% |
| `ConcurrentBag` yield 스핀 프레임 | 3건(두 덤프 모두 동일 지점) | 0건 |
| `Carrying virtual thread` | 1건 | 0건 |
| 요청 처리 스레드 | 가상 스레드·ForkJoin 캐리어 | `http-nio` 플랫폼 스레드 24개 |
| 호스트 내부 `/api/v1/markets` | 15초 타임아웃 | 200, 0.004초 |
| 공개 `https://www.sang.it.kr/api/v1/markets` | 504, 30초 | 200, 33ms |
| 앱의 Primary 연결 수 | 12 | 10 |

작업 PC에서 공개 도메인으로 `/api/v1/markets`, `/api/v1/candles/1m`, `/api/v1/orderbook`, `/api/v1/trades`를 호출해 모두 200을 확인했다. 봇 거래도 재개돼 08:22:18~08:22:19에 KRW-BTC 체결이 이어졌다. 진단 초기에 사용한 `/api/v1/candles?market=...`의 404는 실제 경로가 `/api/v1/candles/{unit}`이기 때문이며 이번 장애와 관계없다.

가상 스레드를 끈 것은 증상의 원인 경로를 제거한 조치이고, HikariCP 쪽 인계 실패 자체를 고친 것은 아니다. 같은 조합을 다시 켜면 재발할 수 있다. 부하를 올린 장시간 운영에서 플랫폼 스레드 풀이 충분한지, 발표 전까지 재발이 없는지는 아직 확인하지 않았다. 최초 유발 계기도 확인하지 못했다.

진단을 위해 추가한 보안 그룹 임시 규칙 `sgr-0d9048f7fa2710caa`(22번, `112.76.112.180/32`)는 아직 남아 있으며 제거 대상이다.

- [40-threaddump-after.txt](40-threaddump-after.txt): 조치 후 스레드 덤프. 스핀 프레임 없음
- [50-조치-적용.txt](50-조치-적용.txt): 백업·해시 대조·설정 추가·재시작 명령과 출력
- [51-재시작-복구확인.txt](51-재시작-복구확인.txt): readiness 폴링과 기동 로그
- [52-정상화-검증.txt](52-정상화-검증.txt): vmstat, 스레드 구성, 스핀 재발 여부, API 응답, 체결 확인
