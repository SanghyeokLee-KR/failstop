# 게이트 OFF 100 OPS 실측 1회차 (2026-09-02)

수용 게이트(백엔드_API_설계 §2-5-a)를 런타임 스위치(v2.41)로 끈 상태에서 폭주 100 OPS를 주입한 첫 실측이다. 목적은 게이트가 없는 보통의 Spring 서비스가 같은 부하에서 어떻게 죽는지, 무엇으로 탐지되는지, 어떤 레버가 도달하는지를 재는 것이다. 이 디렉터리는 원자료와 측정 방법만 담는다. 해석은 기획 문서(MSP 장애 대응 계획 4판 7항)에 있다.

## 조건

- 백엔드 1대. 로컬 jar(app-0.1.0-SNAPSHOT, JDK 21.0.12), 프로파일 dev·bots, 포트 8090, 요청 풀 16, connection-timeout 3000ms, 가상 스레드.
- DB. 격리 Postgres 17(도커 컨테이너 exchange-pg-gateoff, 호스트 포트 5433). 기존 8080 인스턴스와 DB를 공유하지 않는다.
- 봇 3마켓(BTC·ETH·XRP, MM 2·TAKER 1, requote-ms 3000).
- k6 grafana/k6:1.4.0, order_flow.js, PROFILE=demo, STEADY_RATE=10, READ_RATE=17, SPIKE_RATE=100, MARKET=KRW-BTC, RUN_ID=gateoff01. 부하 계정 4개(/harness/v1/loadtest-keys).
- 기계. 12코어 Windows 11 워크스테이션. CPU 백분율은 1코어=100% 기준이다. 기계 전체 비율은 12로 나눈다.

## 시각 (KST)

| 시각 | 사건 |
|---|---|
| 20:50:27 | k6 시작 (정상 10 OPS + 조회 17/s + WS 300) |
| 20:50:54 | 게이트 OFF (POST /actuator/admission enabled=false) |
| 20:52:27 | 폭주 램프 시작 (30초 후 100 OPS) |
| 20:52:35 | 마지막 접수 ACK. 이후 ACK 0건 |
| 20:52:35 | 샘플: pending 137, active 16/16, 시퀀서 큐 177, PG CPU 22% |
| 20:53:02 | 이후 /actuator/prometheus·/actuator/admission 5초 타임아웃 지속 |
| 20:54:00 | readiness·admission·markets 호스트 직접 호출 8초 타임아웃 |
| 20:54:18 | 스레드 덤프 1 (2810, 가상 2408) |
| 20:54:45 | 게이트 ON 1회 시도 60초 타임아웃. 이후 2회 더 실패 |
| 20:56:02 | netstat: CLOSE_WAIT 2410, FIN_WAIT_2 2410, ESTABLISHED 1428 |
| 20:56:57 | 폭주 plateau 종료, 램프다운 |
| 20:57:27 | 외부 부하 정상 10 OPS 복귀 |
| 20:59:03 | 스레드 덤프 2 (5756, 가상 5355, 빈 스택 4805). 여전히 무응답 |
| 20:59:06 | jstat: GC 카운터 불변(YGC 126, FGC 0). GC 아님 |

## 파일

- samples_15s.csv. 15초 샘플러. 열은 sampler.ps1 머리글 참조. gate=ERR 는 actuator 무응답.
  jvm_cpu_pct 열은 이 파일에서는 무효다(PS 5.1에서 프로세스 선택이 실패해 다른 java 프로세스를 읽었다). JVM CPU는 jvm_cpu_pid8784.csv 를 쓴다.
- jvm_cpu_pid8784.csv. 대상 PID 고정 JVM CPU(1코어=100%), OS 스레드 수, 워킹셋.
- threads_gateoff_205418.summary.txt, threads_gateoff_205903.summary.txt. jcmd Thread.dump_to_file 요약(첫 앱 프레임별 집계). 원본 덤프는 크기와 키 노출 때문에 싣지 않는다.
- gate_on_attempts.log. 게이트 ON 전환 시도 기록.
- k6_failures_by_kind.txt. k6 실패 종류 집계와 ACK 수. k6 원본 로그는 access key 가 찍혀 싣지 않는다.
- sampler.ps1, jvmcpu.ps1, analyze.py. 측정 방법. sampler.ps1 의 프로세스 선택은 실측 뒤 CIM 조회로 고쳤다.

## 이 실측이 확정한 것

- 폭주 8초 뒤 pending 0에서 137, active 16/16, 시퀀서 큐 177. 그 뒤 앱 내부 지표 수집 경로가 같이 죽는다.
- 노드 전체 무응답. readiness 포함. k6 쪽은 connect 타임아웃. 서버측 CLOSE_WAIT 누적.
- JVM CPU는 1코어 100% 고정(기계 8%). PG CPU 0.1% 미만. DB도 CPU도 아니다.
- 복구 레버(actuator 게이트 ON)가 도달하지 못한다. 외부 부하가 빠져도 돌아오지 않는다.
- 기전. InflightReserves.tryReserve(synchronized)가 안에서 DB 조회를 하고, 풀 고갈로 그 조회가 대기하는 동안 가상 스레드가 캐리어에 고정된다(JDK 21). 캐리어 12개가 모두 고정되면 스케줄링이 멈춘다. 2회차 덤프의 빈 스택 4805개가 그 증거다.

## 이 실측이 확정하지 못한 것

- H3(리더 자기 강등). 지표 무응답으로 판정 불가. 아래 리스 표 조회 결과 참조.
- JVM 1코어 100%의 주체. GC 는 아니다. 프로파일링하지 않았다.
- k6 최종 요약(http_req_failed 등). 주행 종료 시점 값은 이 README 끝에 붙인다.

## 리스 표 조회 (정지 9분째, 21:01:29 UTC)

```
select * from leader_lease;
matching-engine|509a15ee-b334-4a4c-a75a-5de5c1ba6d47|1|2026-09-02 12:01:36.16634+00
select now();  2026-09-02 12:01:29.271139+00
select state, count(*) from pg_stat_activity where datname='exchange' group by state;
active|1
idle|17
```

epoch 1 그대로, 만료 시각이 현재보다 7초 앞이라 리스는 계속 갱신 중이다. H3 판정: 리더는 물러나지 않는다. 자기 강등도 발화하지 않았다. 요청 처리 가상 스레드만 죽고 리스 갱신 플랫폼 스레드는 살아 있는 좀비 리더 상태다.

## 종료 시점 (21:04:32, 정지 시작 12분 뒤, 외부 폭주 종료 7분 뒤)

- readiness 3초 타임아웃(HTTP 응답 없음). 회복 없음.
- 서버측 CLOSE_WAIT 5107, FIN_WAIT_2 5107, ESTABLISHED 224. 누적은 계속 늘고 반납은 없다.
- JVM CPU 100.3%(1코어), OS 스레드 424, 워킹셋 531MB. 스레드 수는 늘지 않는다(캐리어 12개 고정으로 새 가상 스레드는 OS 스레드를 만들지 않는다).
- PG CPU 0.01~0.03%.
- 이 인스턴스는 실험 뒤 종료했다. 복구 시도는 하지 않았다. 유일한 레버가 재기동임을 확인하는 것이 2회차의 첫 항목이다.

## k6 종료 상태

k6 는 15분 주행을 마친 뒤 WS 300 세션의 종료 대기에서 빠져나오지 못했다(서버가 close 에 응답하지 않는다). 그래서 k6 의 최종 요약과 summary export 는 없다. 진행 로그에서 읽은 값만 있다. 접수 ACK 1215건(마지막 20:52:35), Request Failed 4443건(connect·request timeout). k6_failures_by_kind.txt 참조. 부하기까지 서버에 묶이는 것도 이 장애의 특성이다.
