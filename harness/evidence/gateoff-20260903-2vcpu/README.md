# 게이트 OFF 2 vCPU 실측 (2026-09-03)

전날 실측(`../gateoff-20260902/`)은 12코어 워크스테이션에서 쟀다. 배포 대상은 2 vCPU 인스턴스다.
가상 스레드 캐리어 수가 `Runtime.availableProcessors()` 라서 코어 수는 이 장애의 임계에 직접 걸린다.
그래서 코어 수만 바꾸고 나머지를 고정해 다시 쟀다.

## 조건

전날과 같은 것: jar, 프로파일(dev·bots), 요청 풀 16, connection-timeout 3000ms, JDK 21.0.12,
격리 Postgres 17(5433), 봇 3마켓, k6 demo 프로파일(주문 10/s, 조회 17/s, WS 300 VU, 폭주 100 OPS),
게이트 OFF.

바꾼 것 하나: `-XX:ActiveProcessorCount=2` 와 프로세서 어피니티 2코어(마스크 0x3).
기동 뒤 `jcmd Thread.print` 로 `ForkJoinPool-1-worker` 가 2개임을 확인했다(전날은 12개).

CPU 백분율 단위. `jvm_cpu_1core_pct` 는 1코어=100% 다. 2 vCPU 인스턴스 기준 사용률은 그 절반이다.
`vcpu_pct` 열이 그 값이나 일부 행에서 계산이 비었다. 판단에는 `jvm_cpu_1core_pct / 2` 를 쓴다.

## 시각 (KST)

| 시각 | 사건 |
|---|---|
| 09:21:18 | 게이트 OFF |
| 09:21:20 | k6 시작 (기저: 주문 10/s, 조회 17/s, WS 300 VU) |
| 09:21:24 | pending 0에서 154, active 16/16, 시퀀서 큐 6. actuator 는 아직 200 을 158ms 에 반환 |
| 09:21:28 | actuator 무응답 시작(3초 타임아웃) |
| 09:21:35 | 마지막 접수 ACK. 이후 0건 |
| 09:21:52~ | readiness 5초 타임아웃. 이후 회복 없음 |
| 09:23:20 | k6 폭주 램프 시작. 노드는 이미 정지 상태라 무의미 |
| 09:24:22 | 같은 DB 에 두 번째 노드 기동(포트 8091, 코어 2·3) |
| 09:24:30 | 두 번째 노드 리더 승격 실패. readiness 503 OUT_OF_SERVICE |
| 09:24:46 | 두 번째 노드 기동 거부로 프로세스 종료 |
| 09:25:19 | 스레드별 CPU 귀속 측정 시작 |
| 09:27:00 | 실험 종료. 정지 노드 강제 종료 |

## 결과

### 1. 2 vCPU 에서는 폭주가 아니라 기저 부하에서 죽는다

접수 ACK 5건, DB 주문 21건. k6 시작 15초 뒤가 마지막 접수다. 100 OPS 구간에 도달하지 못했다.
전날 12코어는 같은 기저를 2분간 견디고 폭주에서 죽었다. 코드도 부하도 같다. 코어 수만 다르다.

기저 부하에는 WS 300 VU 동시 접속이 들어 있다. 붕괴를 당긴 것이 주문율인지 WS 동시 접속인지는
이 주행이 가르지 못한다. 그 분리는 `run3`(WS 없는 캘리브레이션)이 한다.

### 2. CPU 는 이 장애를 가리키지 못한다

| 구간 | JVM CPU (1코어=100%) | 2 vCPU 인스턴스 사용률 |
|---|---|---|
| 붕괴 직전 | 112.7 | 56% |
| 붕괴 직후 | 22~30 | 11~15% |
| 정지 지속 | 100~105 | 50~53% |

정지 상태가 정상 상태보다 CPU 를 더 쓴다. 그리고 그 값은 건강한 중간 부하와 구별되지 않는다.
CPU 임계 알람(통상 70~80%, 2주기 지속)은 발화하지 않는다. 낮은 임계로 내려도 붕괴 직후
11~15% 구간에서 해제됐다가 다시 오르므로 지속 조건을 채우지 못한다.

### 3. 그 CPU 는 일이 아니다

`jcmd Thread.print` 로 OS 스레드 단위 CPU 를 귀속했다. 코어 하나를 태우는 것은 스레드 하나다.

```
"HikariPool-1:housekeeper" #26 [20804] daemon cpu=185140.62ms elapsed=333.16s runnable
   java.lang.Thread.State: RUNNABLE
	at java.lang.Thread.yield0(java.base@21.0.12/Native Method)
	at java.lang.Thread.yield(java.base@21.0.12/Thread.java:445)
	at com.zaxxer.hikari.util.ConcurrentBag.unreserve(ConcurrentBag.java:322)
	at com.zaxxer.hikari.pool.HikariPool$KeepaliveTask.run(HikariPool.java:887)
```

가동 333초 중 CPU 185초. 10초 창 측정에서 97.7~98.0%. HikariCP 킵얼라이브가 고갈된 풀에서
커넥션 상태 전이를 기다리며 `Thread.yield()` 로 헛돈다. 처리량 0 인데 CPU 는 반 코어를 넘는다.

### 4. readiness 는 짧게 거짓말한 뒤 아예 대답하지 않는다

pending 154, active 16/16 인 샘플에서 actuator 는 200 을 158ms 에 반환했다. 그 창은 4초 미만이다.
그 뒤로는 5초 타임아웃이 영구 지속된다. ALB 관점에서 이 대상은 unhealthy 로 빠진다.
"헬스체크가 초록이라 ALB 가 계속 보낸다"는 서술은 4초 미만의 창에서만 참이고, 그 뒤에는
"ALB 가 대상을 빼내지만 대신 넣을 것이 없다"가 참이다.

### 5. 노드를 늘리면 기동을 거부당한다

정지 노드가 살아 있는 채로 같은 DB 에 두 번째 노드를 띄웠다.

```
WARN  kr.exchange.engine.EngineRecovery : 리더 승격 실패(유효한 타 리더 존재). 리스 만료 대기
ERROR o.s.boot.SpringApplication        : Application run failed
Caused by: java.lang.IllegalStateException:
    크래시 창 이벤트 8건 재적재 불가: 리더 승격 실패(대기 15000ms 소진)
```

readiness 는 그 전에 503 OUT_OF_SERVICE 였고, 15초 뒤 프로세스가 종료됐다.
정지 노드가 리더 리스를 계속 갱신하기 때문이다(epoch 1 불변, `lease_until` 이 항상 now 보다 앞).
`EngineRecovery` 는 이것을 기동 거부로 처리한다. 의도된 분기다(주석 참조).

크래시 창에 재적재할 이벤트가 있을 때의 거동이다. 이벤트가 0건인 경우는 이 주행이 재지 않았다.

### 6. 소켓은 단조 증가한다

서버측 CLOSE_WAIT 이 40초에 1264 에서 1975 로 늘었다(전날 12코어 주행은 5107 까지).
ESTABLISHED 는 1422 에서 고정. 노드는 새 연결을 계속 받아들이되 아무것도 닫지 않는다.
앱 지표가 죽은 뒤에도 밖에서 보이는 유일한 단조 신호다.

## 파일

- `run2_samples.csv` 5초 간격 샘플. `gate=ERR`, `code=0` 은 actuator 무응답.
- `run2_readiness.csv` ALB 와 같은 조건(타임아웃 5초)의 readiness 프로브.
- `run2_topthreads.csv` OS 스레드별 CPU 귀속. `java_thread` 열이 `(unmapped)` 인 것은
  수집 스크립트가 JDK 21 의 `[tid]` 형식 대신 옛 `nid=0x` 형식을 찾았기 때문이다.
  귀속 자체는 유효하다. 이름 대조는 `hikari_housekeeper_spin.txt` 에 따로 있다.
- `hikari_housekeeper_spin.txt` 문제 스레드의 스택과 CPU 상위 스레드 목록.
- `node2_boot_refused.txt` 두 번째 노드의 기동 거부 로그.
- `k6_run2_counts.txt` 접수 ACK, 실패, DB 주문 수.
- `sampler2.ps1`, `topthreads.ps1`, `readiness_probe.ps1` 측정 방법.

## 확정하지 못한 것

- 붕괴를 당긴 것이 주문율인지 WS 300 동시 접속인지 가르지 못했다.
- 크래시 창 이벤트가 0건일 때 두 번째 노드가 팔로워로 기동하는지 재지 않았다.
- 메모리는 이번 변수에서 뺐다(최대 힙 8.5GB 그대로). 2GB 인스턴스 모사는 별도 주행이 필요하다.
- 게이트 ON 상태의 같은 부하(429 흡수, 복구 시간)는 아직 재지 않았다.

## 3회차. 용량 계단 (WS 없음)

주문·조회만 계단으로 올려 이 인스턴스의 용량 상한을 찾았다. 각 단 90초, 단 사이 25초 유휴,
게이트 OFF, 봇 3마켓 동시 가동. 원자료는 `run3_staircase.log`, `run3_samples.csv`.

| 주문/s | 조회/s | 접수 ACK | 기대치 | 요청 실패 | pending | 인스턴스 CPU 최대 | 판정 |
|---|---|---|---|---|---|---|---|
| 4 | 7 | 360 | 360 | 0 | 0 | 56% | 정상 |
| 8 | 13 | 721 | 720 | 0 | 0 | 21% | 정상 |
| 12 | 20 | 1081 | 1080 | 0 | 0 | 24% | 정상 |
| 16 | 27 | 427 | 1440 | 176 | 판독 불가 | 54% | 정지. 회복 없음 |

12 와 16 사이에 절벽이 있다. 12 는 접수율 100%, 실패 0, pending 0 이고 16 은 접수율 30% 뒤
영구 정지다. 이 값이 t4g.small 1대의 용량이다. 12코어에서 잰 40~42/s 는 이 인스턴스의 값이 아니다.

### CPU 곡선. 최고점은 죽은 뒤에 온다

16 주문/s 주행의 5초 샘플이다. 괄호 안은 2 vCPU 인스턴스 사용률(측정값의 절반)이다.

| 시각 | 인스턴스 CPU | pending | 시퀀서 큐 | CLOSE_WAIT | HTTP | 상태 |
|---|---|---|---|---|---|---|
| 09:39:06 | 16% | 0 | 10 | 0 | 200 | 정상 |
| 09:39:19 | 18% | 0 | 13 | 0 | 200 | 정상. 마지막 응답 |
| 09:39:25 | 16% | 판독 불가 | 판독 불가 | 1 | 무응답 | 정지 |
| 09:39:34 | 12% | 판독 불가 | 판독 불가 | 14 | 무응답 | 정지. CPU 최저 |
| 09:39:44 | 39% | 판독 불가 | 판독 불가 | 17 | 무응답 | 정지 |
| 09:39:53 | 54% | 판독 불가 | 판독 불가 | 22 | 무응답 | 정지. CPU 최고 |
| 09:41:21 | 53% | 판독 불가 | 판독 불가 | 368 | 무응답 | 정지 고정 |

셋을 읽는다. 죽기 직전 CPU 는 16~18% 다. 죽는 순간 12% 로 내려간다. 최고점 54% 는 정지 시작
28초 뒤에 온다. CPU 임계 알람은 이 장애를 예고하지 못하고, 발화한다면 끝난 장애를 뒤늦게 알린다.

pending 은 이 주행에서 경고를 주지 못했다. 마지막 정상 샘플의 pending 이 0 이고 그 다음 샘플이
판독 불가다. 5초 간격으로는 그 사이에 아무 값도 잡히지 않았다. 2회차(12코어)에서 한 샘플만
154 를 보인 것과 다르다. 앱 내부 지표는 이 인스턴스에서 더 빨리 죽는다.

정지 뒤 스레드 귀속은 2회차와 같다. `HikariPool-1:housekeeper` 가 `ConcurrentBag.unreserve` 의
`Thread.yield()` 루프에서 코어 하나를 태운다. 리더 리스도 계속 갱신됐다(epoch 1 불변, 유효).
두 주행이 같은 기전으로 재현됐다.

## 3회차가 2회차에 대해 알려주는 것

2회차는 주문 10/s + 조회 17/s + WS 300 VU 에서 죽었다. 3회차는 주문 12/s + 조회 20/s 를
WS 없이 견뎠다. 곧 2회차를 넘긴 것은 주문율이 아니라 WS 300 동시 접속이 더한 몫이다.
그 몫의 크기는 재지 않았다(WS 축 계단은 미실시).

## 부수 발견. 기동 중 하네스 키 발급이 기동을 죽인다

`POST /harness/v1/loadtest-keys` 를 봇 프로비저닝이 끝나기 전에 호출하면 GENESIS
`business_event_id` 채번이 충돌해 `botFleetLifecycle` 기동이 실패하고 애플리케이션이 죽는다.
2 vCPU 에서 재현했다(`Failed to start bean 'botFleetLifecycle'`,
`duplicate key value violates unique constraint "uq_ledger_business_event"`).
readiness 200 은 Tomcat 이 먼저 열려 조기 참이 되므로 기동 완료 판정에 쓸 수 없다.
로그의 `Started ExchangeApp` 를 기다린 뒤 호출해야 한다. 하네스 엔드포인트가 애플리케이션
기동을 막을 수 있다는 뜻이라 별도 등재 대상이다.
