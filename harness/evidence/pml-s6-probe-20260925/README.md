# ADR 0034 S6 브로커 동작 확인 (2026-09-25)

[S6 설계](../../../docs/reference/architecture/마켓분할_리더_S6_설계.md) 3절의 원자료다. WebSocket 중계에 쓸 외부 STOMP 브로커의 메모리, 메시지 순서와 사용자 머리글 보존, 큐 정리, 멈춘 구독자와 하트비트, 메모리 경보를 폐기용 컨테이너에서 확인했다. Spring `StompBrokerRelayMessageHandler`의 브로커 불통 동작은 로컬 Gradle 캐시의 `spring-messaging-6.2.19.jar`를 `javap`로 읽어 확인했다.

## 조건

- 시각: 2026-09-25 03:08~03:42 UTC(12:08~12:42 KST). 브로커 확인은 03:34에 끝났고 nginx 확인(N1)을 03:41에 더했다. 파일 `started_utc.txt`, `finished_utc.txt`.
- 호스트: Windows 11 개발 PC, Docker 29.5.2. 같은 시각 다른 작업 트리의 컨테이너는 떠 있지 않았다(`docker ps -a` 빈 목록).
- 컨테이너: 이름 `pml-probe-*`, 네트워크 `pml-probe-net`(끝에 지움), 호스트 포트는 `127.0.0.1` 무작위 포트. 카오스 스택, 실험 스택(`exchange-pml`), 보존 볼륨에는 접근하지 않았다.
- 이미지: `rabbitmq:4-alpine`과 `rabbitmq:4.3.6-alpine`(같은 id `sha256:2531fe16e1cb…`, RabbitMQ 4.3.6, Erlang/OTP 27), `apache/activemq-artemis:latest-alpine`(`sha256:1bce124d2324…`, Artemis 2.44.0, JDK 21.0.8). 확인 클라이언트는 `python:3.12-alpine` 안의 `stomp_probe.py`(asyncio STOMP 1.2 TCP 클라이언트)다. nginx 확인은 로컬에 있던 `nginx:1.29`(1.29.8) 세 개와 네트워크 `pml-probe-net2`(끝에 지움)로 했다. RabbitMQ와 Artemis 이미지는 이 확인을 위해 Docker Hub에서 새로 받았다.
- RabbitMQ 설정: `rabbit/enabled_plugins`(`rabbitmq_stomp`), `rabbit/rabbitmq.conf`(첫 기동), `rabbit/rabbitmq-small.conf`(t3.small 흉내: `vm_memory_high_watermark.absolute = 192MiB`, 컨테이너 `--memory 320m --cpus 2`, Erlang `+S 2:2`).
- Artemis 설정: `artemis/start.sh`. STOMP 수신기에 `anycastPrefix=/queue/;multicastPrefix=/topic/`를 붙이고 `JAVA_ARGS`로 `-XX:+UseSerialGC -Xms64M -Xmx128M`을 준다. 컨테이너 `--memory 512m --cpus 2`.

## 모양 두 가지

- 노드 단위 중계(`bus`): 앱 노드마다 발행 연결 하나와 구독 연결 하나가 한 목적지(`/topic/exch.bus.v1`)를 쓴다. 확인에서는 발행자 둘과 구독자 둘이 사용자 머리글(`x-origin`, `x-dest`, `x-user`, `x-kind`, `x-seq`)을 단 메시지를 주고받는다.
- 세션 단위 중계(`perclient`): Spring `enableStompBrokerRelay`의 모양이다. 브라우저 세션마다 브로커 연결 하나가 생기고, 그 연결이 공개 토픽 12개(`/topic/orderbook.<마켓>`)와 사용자 큐 둘(`/queue/myOrder-user<세션>`, `/queue/myAsset-user<세션>`)을 구독한다.

## 결과

| 번호 | 확인 | 결과 | 파일 |
|---|---|---|---|
| R0 | RabbitMQ 기본 설정 유휴 | 120.3MiB. 스케줄러 16개(호스트 코어 수) | `r0_idle.txt` |
| R1 | 노드 단위, 발행자 둘 x 5,000건 | 구독자 둘 모두 10,000건 수신, 발행자별 순서 보존, 사용자 머리글 10,000건 모두 보존. 직후 216MiB, 20초 뒤 140.9MiB. 구독 큐는 연결을 닫자 사라졌다 | `r1_bus_rabbit.json`, `r1b_settle.txt` |
| R2 | 세션 단위 500 연결(구독 14개씩, 큐 7,000개) | 467.3MiB(유휴보다 약 327MiB, 세션당 약 0.65MiB). `queue_procs` 73.8MB, `binary` 71.0MB, `connection_other` 47.5MB | `r2_perclient_500.txt` |
| R3a | R2를 닫은 뒤 사용자 큐 | `/queue/` 큐 1,000개가 `durable=true auto_delete=false`로 남고 메모리 410.6MiB 유지 | `r3a_leftover_noautodelete.txt` |
| R3b | SUBSCRIBE에 `auto-delete:true durable:false`를 단 200 연결 | 닫은 뒤 남은 사용자 큐 0 | `r3b_leftover_autodelete.txt` |
| R4 | 제한 설정 유휴와 노드 단위 발행자 둘 x 20,000건 | 유휴 79.8MiB(`smp:2:2`). 40,000건 모두 순서·머리글 보존, 직후 122.5MiB, 20초 뒤 98.8MiB | `r4_small_idle.txt`, `r4_small_bus.json` |
| R5 | 제한 설정, 세션 단위 200 연결(auto-delete) | 218.4MiB에서 메모리 경보(수위 192MiB). 닫은 뒤 152.4MiB | `r5_small_perclient_200.txt` |
| R6 | 읽지 않는 구독자, `ack:auto`, `x-max-length=2000`, `x-message-ttl=10000`, 30,000건 | 큐 인자는 붙었으나 큐 길이 0. 자동 확인은 메시지를 연결 쪽으로 밀어내므로 큐 상한이 걸리지 않는다. 메모리 125.4MiB | `r6_stall_bounded.txt` |
| R6b | 같은 조건에 `ack:client-individual`, `prefetch-count=100` | 준비 2,000건 + 미확인 100건에서 멈춤. TTL 10초 뒤 준비 0건 | `r6b_stall_bounded_clientack.txt` |
| R7 | 하트비트를 협상하고 한 바이트도 보내지 않는 클라이언트 | `2000,2000`은 6.0초, `10000,10000`은 30.0초 뒤 브로커가 연결을 닫음(간격의 3배). 첫 판(읽지도 않음)은 EOF를 볼 수 없어 판정하지 못했다 | `r7_heartbeat_close.json`, `r7_heartbeat_close_v1_inconclusive.txt` |
| R8 | 메모리 경보를 켠 채 노드 단위 발행 | 발행 쓰기가 막혀 40초 제한에 걸림. ERROR 프레임은 오지 않음. 수위를 되돌리자 경보 해제 | `r8_alarm_block.txt`, `r8_alarm_block_readme.txt` |
| A0 | Artemis 유휴(`-Xmx128M`, SerialGC) | 129.3MiB | `a0_artemis_idle.txt` |
| A1 | Artemis 노드 단위, 구독자를 차례로 읽음 | 60초 안에 첫 구독자 15,250건, 둘째 5,008건만 받음. 순서·머리글은 보존. 244.1MiB. 읽는 동안 다른 구독자가 멈춰 있던 판이라 아래 A2로 다시 쟀다(원인은 확인하지 않음) | `a1_bus_artemis.json` |
| A2 | Artemis 노드 단위, 구독자를 발행과 동시에 읽음 | 40,000건 모두 순서·머리글 보존, 3.6초. 253.2MiB | `a2_bus_artemis_concurrent.json` |
| A3 | Artemis 세션 단위 200 연결 | `OutOfMemoryError: Java heap space`, ERROR 프레임과 연결 실패. 247.1MiB | `a3_artemis_perclient_200.txt` |
| N1 | nginx 1.29.8, `least_conn` 두 노드, `proxy_next_upstream error timeout http_503`, `max_fails=1 fail_timeout=2s`. 노드 a는 `/ws/` 업그레이드 요청을 업그레이드 전에 `503`, 노드 b는 `200`(표지 머리글) | 10건 모두 b가 답함. 첫 요청은 a `503` 뒤 b로 넘어갔고(`ups=503, 200`), 그 뒤 약 2초 동안 a를 건너뛰었다가 다시 한 번 a를 거쳐 b로 넘어감 | `n1_ws_next_upstream.txt`, `nginx/` |
| S1 | Spring 6.2.19 `StompBrokerRelayMessageHandler.handleMessageInternal` 바이트코드 | 브로커가 없을 때 세션이 없거나 `_system_` 세션인 메시지(앱의 `SimpMessagingTemplate` 게시)는 `MessageDeliveryException("Message broker not active…")`을 던진다. 클라이언트 세션의 메시지는 그 세션에 `ERROR "Broker not available."`을 보내고 중계 연결을 지운다 | 아래 명령 |

S1 명령(로컬 Gradle 캐시의 jar를 임시 폴더에 풀어 읽었다):

```bash
J=$(find ~/.gradle/caches/modules-2/files-2.1/org.springframework/spring-messaging/6.2.19 -name "spring-messaging-6.2.19.jar")
unzip -q -o "$J" 'org/springframework/messaging/simp/stomp/StompBrokerRelayMessageHandler*.class'
javap -c -p -constants org/springframework/messaging/simp/stomp/StompBrokerRelayMessageHandler.class | sed -n 495,575p
```

## 해석과 한계

- 세션 단위 중계는 브로커 메모리가 브라우저 세션 수에 비례한다(RabbitMQ 약 0.65MiB/세션, 구독 14개 기준). 노드당 WS 상한(최대 3,000, 힙 768MB면 약 2,000)을 두 노드가 채우면 수 GB가 필요하다. t3.small 흉내 설정에서는 200 세션에서 이미 메모리 경보가 났고(R5), 경보는 발행 연결을 오류 없이 멈춘다(R8). Artemis는 128MB 힙에서 200 세션을 받지 못했다(A3).
- 노드 단위 중계는 브로커 연결 수가 노드 수의 두 배로 고정되고 메모리가 80~125MiB에 머문다(R4). 순서는 발행자별로 지켜지고 사용자 머리글이 그대로 온다(R1, R4, A2).
- 멈춘 구독자는 `ack:auto`이면 큐 상한이 걸리지 않는다(R6). 구독은 클라이언트 확인과 `prefetch-count`, `x-max-length`, `x-message-ttl`을 함께 써야 브로커 쪽에서 묶인다(R6b). 하트비트를 쓰면 멈춘 노드의 연결과 큐는 간격의 3배 뒤 정리된다(R7).
- 사용자 목적지를 RabbitMQ `/queue/`로 중계하면 SUBSCRIBE에 `auto-delete` 머리글을 붙이지 않는 한 세션마다 큐가 남는다(R3a, R3b).
- 중계가 끊긴 노드가 WS를 거절할 때는 업그레이드 전에 `503`을 내야 nginx가 다른 노드로 넘긴다(N1). 업그레이드를 받은 뒤 닫으면 nginx에는 성공(`101`)으로 보이므로 넘기지 않고, `least_conn`은 연결이 적은 그 노드를 계속 고른다(추론, 101 경우는 재지 않았다).
- 수치는 이 PC Docker의 짧은 표본이다. AWS t3.small(Amazon Linux 2023)의 값이 아니고, 장시간 운영의 메모리 추세는 재지 않았다. Artemis는 JVM 설정을 더 줄일 여지가 있고 그 조정은 해 보지 않았다.
- 이 폴더의 `stomp_probe.py`는 마지막 판이다. `bus` 모드의 R1·R4·A1은 구독자를 발행이 끝난 뒤 차례로 읽던 첫 판으로 돌렸다. RabbitMQ는 그 판에서도 모두 전달했다. A1이 일부만 받아 발행과 동시에 읽는 판(`drain_subscriber`)으로 바꾼 뒤 A2와 R8을 돌렸다. `stall`·`silent` 모드는 그 뒤에 더했다.
- Spring 쪽 확인은 바이트코드 읽기뿐이다. `ReactorNettyTcpStompClient`의 재연결·하트비트 감지 시간과 경보 중 쓰기 대기의 실제 모습은 S6 코드 단계의 Testcontainers 시험이 처음 확인한다.

## 설계 검토 대응 확인 (R9~R11)

같은 날 설계 검토가 두 가지를 짚었다. 설계가 채택한 구독(클라이언트 누적 확인, 선반입 1,000, 큐 상한)은 위 R6b와 조건이 달라 확인된 적이 없다는 것, 그리고 메모리 경보가 발행만 막고 구독 연결은 살려 두므로 비소유 노드가 구독 연결 상태만 보고는 소유 노드의 발행이 멈춘 것을 모른다는 것이다. 같은 호스트에서 폐기용 컨테이너로 다시 확인했다.

- 시각: 2026-09-25 04:33~04:48 UTC. 명령과 원시 출력은 `review_commands.log`, 실행기는 `review_probe.ps1`, 확인 클라이언트는 이 폴더의 `stomp_probe.py`(`bus` 모드의 구독 선택지와 `alarm` 모드를 더한 판)다. 첫 기동(04:33)은 실패했다. 브로커가 뜨기 전에 `rabbitmq-diagnostics`를 root로 불러 쿠키 파일이 root 소유로 만들어졌고 브로커가 `eacces`로 죽었다. 그 뒤로는 6초 기다렸다가 `rabbitmq` 사용자로 부른다.
- 컨테이너: 브로커 `pml-probe-rmq3`(`rabbitmq:4.3.6-alpine`, 흉내 설정 `rabbit/rabbitmq-small.conf`, `--memory 320m --cpus 2`, `+S 2:2`), 클라이언트 `pml-probe-*`(`python:3.12-alpine`), 네트워크 `pml-probe-net3`. 호스트 포트는 열지 않았고 끝에 모두 지웠다. 카오스 스택, 실험 스택(`exchange-pml`), 보존 볼륨에는 접근하지 않았다. 시작 전 `chaos-*` 컨테이너는 없었다. Docker 29.5.2.
- 구독 머리글: 첫 판 채택값은 `ack:client`, `prefetch-count:1000`, `x-max-length:20000`, `x-message-ttl:10000`이다. R10 뒤 설계가 고친 최종값은 `x-max-length:5000`과 `x-max-length-bytes:4194304`(4MiB)를 쓴다. 누적 확인은 200건이나 200ms마다 마지막으로 받은 메시지의 `ack` 값으로 보낸다.

| 번호 | 확인 | 결과 | 파일 |
|---|---|---|---|
| R9a | 첫 판 채택 구독. 발행자 둘이 20,000건씩 약 30초에 나눠 보냄(합계 초당 약 1,300건) | 구독자 둘 모두 40,000건을 받음. 발행자별 순서와 머리글 보존, 누적 확인 200번. 직후 120.6MiB | `r9a_bus_clientack_paced.json` |
| R9b | 같은 구독. 40,000건을 0.46초에 몰아 보냄 | 29,841건과 30,150건을 받음. 큐 상한을 넘은 앞쪽 메시지가 버려졌고 받은 것의 순서는 보존 | `r9b_bus_clientack_burst.json` |
| R9c | 최종 구독. R9a와 같은 부하 | 40,000건 모두 받음. 순서와 머리글 보존. 96.3MiB | `r9c_bus_final_paced.json` |
| R9d | 최종 구독. R9b와 같은 몰아 보내기 | 16,554건과 16,784건을 받음(상한 밖은 버림). 순서 보존. 86.1MiB | `r9d_bus_final_burst.json` |
| R10 | 첫 판 채택 구독으로 읽지도 확인하지도 않는 구독자. 본문 약 470바이트 30,000건 | 준비 20,000건 + 미확인 1,000건에서 멈춤. TTL 10초 뒤 준비 0건. 몰아 넣는 동안 브로커가 169.2MiB까지 오름(경보 수위 192MiB) | `review_commands.log` |
| R10b | 새 브로커, 최종 구독. 본문 약 1,450바이트 30,000건 | 준비 2,900건(바이트 상한) + 미확인 1,000건에서 멈춤, 큐 5.6MB. 유휴 109.5MiB에서 168.9MiB로 올랐다가 TTL 뒤 140.2MiB | `review_commands.log` |
| R11a | 메모리 경보 약 25초(수위 absolute 1MiB, 기동 뒤 15초에 켜고 40초에 끔). 발행 연결 하나가 초당 1,000건(약 1KB)과 1초마다 하트비트 봉투를 보낸다. 쓰기 완료를 1초 넘게 기다리면 SO_LINGER 0으로 끊고 1·2·4·8·10초 뒤 다시 붙는다. 구독 연결은 첫 판 채택 구독, 하트비트 `5000,5000` | 구독 연결은 끊기지 않았고 ERROR도 없었으며 확인·하트비트 쓰기도 막히지 않았다. 경보 뒤 약 1.8초에 첫 쓰기 시간 초과가 났다. 경보 동안 다시 붙을 때마다 첫 프레임 하나만 통과해 하트비트가 2.7·3.9·5.9·9.9·11.9초 간격으로 드문드문 왔다. 끊은 연결의 늦은 전달은 0건, 연결 세대 역전도 0건이다. 끊을 때 버퍼에 있던 메시지(연결마다 약 700건)는 사라졌다. 경보가 풀린 뒤 발행은 재연결 대기(10초)가 끝난 49.7초부터 이어졌다 | `r11a_alarm_rst.json` |
| R11b | R11a와 같되 시간 초과 때 보통 닫기(FIN) | 경보가 풀리자 끊었던 연결 다섯의 메시지 3,105건이 한꺼번에 도착했다. 가장 오래된 것은 25.6초 묵었고, 새 연결의 메시지보다 뒤에 온 옛 연결 메시지가 2,396건이다 | `r11b_alarm_graceful.json` |

해석:

- 채택 구독은 초당 약 1,300건에서 메시지를 잃지 않고 순서와 머리글을 지킨다(R9a, R9c). 멈춘 구독자의 큐는 건수·바이트 상한과 TTL로 묶인다(R10, R10b). 몰아 보내기에서 상한을 넘은 앞쪽 메시지는 버려지고(R9b, R9d) 클라이언트는 결번으로 알아챈다.
- 첫 판의 건수 상한 20,000은 큐 하나에 수십 MiB를 허용한다. 또 30,000건을 2초 안에 몰아 넣는 동안에는 큐 상한과 무관하게 브로커가 169MiB 안팎까지 올랐다(R10, R10b). 봉우리가 큐에 남은 양보다 처리 중인 메시지에서 온다는 해석은 추론이다. 같은 부하를 나눠 보낸 R9a·R9c는 96~121MiB였다.
- 메모리 경보는 발행 연결만 막고 구독 연결은 살려 둔다(R11). 구독 연결 상태로는 소유 노드의 발행이 막힌 것을 알 수 없다. 다시 붙을 때마다 첫 프레임이 통과하므로 하트비트 한 개가 도착한 것만으로 발행 경로가 살아 있다고 볼 수도 없다.
- 쓰기 시간 초과 뒤 보통 닫기를 하면 브로커는 경보가 풀린 뒤 옛 연결에 쌓인 메시지를 늦게, 새 연결의 메시지와 순서가 바뀐 채 전달한다(R11b). SO_LINGER 0으로 끊은 R11a에는 늦은 전달이 없었다.

한계: 발행자와 구독자는 Python asyncio이고 앱의 Reactor Netty STOMP 클라이언트가 아니다. asyncio 쓰기 버퍼 상한을 0으로 두어 Netty의 쓰기 완료 대기를 흉내 냈을 뿐이고, Netty에서 SO_LINGER 0으로 끊는 동작과 커널 버퍼 크기는 코드 단계 시험이 처음 확인한다. R11의 구독은 첫 판 채택값(건수 20,000, 바이트 상한 없음)이다. 경보 중에는 큐에 새 메시지가 들어오지 않으므로 결과가 같다고 보지만 재지는 않았다. 수치는 이 PC Docker의 짧은 표본이다.

## 구현 검토 대응 확인 (N2)

코드 단계 구현 검토가 배포 엣지(`deploy/nginx-edge.conf.template`)의 `/ws/`를 짚었다. `/ws/`가 조회와 같은 upstream(`exchange_read`)을 쓰는데 `proxy_next_upstream`에 `http_503`을 더했으므로, 팔로워가 WS 핸드셰이크를 `503`으로 거절할 때마다 그 팔로워가 조회 분산에서도 빠진다는 것이다. 엣지와 같은 모양(`least_conn`, `resolve`, `max_fails=1 fail_timeout=2s`, `/api/`는 `proxy-common.conf`의 재시도 목록)으로 두 판을 견줬다.

- 시각: 2026-09-25 08:24:44~08:25:01 UTC. 원시 출력은 `n2_ws_503_upstream_split.txt`, 설정은 `nginx/n2-*.conf`, 클라이언트는 `nginx/n2-client.sh`다.
- 컨테이너: `nginx:1.29`(1.29.8) 셋(`pml6-probe-leader`, `pml6-probe-follower`, `pml6-probe-lb`)과 클라이언트 `pml6-probe-client`(`curlimages/curl:8.11.0`), 네트워크 `pml6-probe-net`. 모두 로컬에 있던 이미지이고 호스트 포트는 열지 않았으며 끝에 지웠다. 카오스 스택, 실험 스택(`exchange-pml`), 보존 볼륨과 같은 시각에 떠 있던 다른 작업의 컨테이너에는 접근하지 않았다.
- 리더 흉내는 `/ws/`와 `/api/`에 `200`, 팔로워 흉내는 `/ws/`에 `503`과 `Retry-After: 5`, `/api/`에 `200`이다. 클라이언트는 `/ws/` 업그레이드 요청 둘 바로 뒤에 조회 10건, 3초 뒤 조회 10건을 보낸다.

| 번호 | 판 | 결과 |
|---|---|---|
| N2-A | `/ws/`와 `/api/`가 upstream 하나(`n2-lb-shared.conf`, 고치기 전 엣지 모양) | 첫 `/ws/`가 팔로워 `503` 뒤 리더로 넘어갔고(`ups=503, 200`), 곧바로 보낸 조회 10건이 모두 리더로 갔다. 3초 뒤 조회 10건은 두 노드에 번갈아 갔다 |
| N2-B | `/ws/`만 upstream `exchange_ws`(`n2-lb-split.conf`, 고친 엣지 모양) | 같은 `/ws/` 거절 뒤 곧바로 보낸 조회 10건이 팔로워·리더에 번갈아 갔다(5건씩). 3초 뒤도 같다 |

해석: nginx는 `proxy_next_upstream`에 적힌 응답을 그 서버의 실패로 세고, 실패 표시는 upstream 블록에 붙는다. 그래서 WS 거절이 같은 블록을 쓰는 조회 분산에서 그 노드를 `fail_timeout` 동안 뺐다(N2-A). 블록을 나누면 조회 분산은 그대로다(N2-B). 한계: 흉내 노드는 WebSocket 업그레이드(`101`)를 하지 않고 `200`을 낸다(N1과 같다). 요청을 차례로 보냈으므로 `least_conn`의 연결 수는 늘 같았고, 오래 붙어 있는 WS 세션이 리더에 몰린 상태의 분산은 재지 않았다.
