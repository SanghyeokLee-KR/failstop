# 리더 리스를 커밋 경로에서 뗀 뒤의 장애 내성 (2026-09-08)

증거 `replication-fault-20260908` 이 사고 하나를 남겼다. 동기 복제 대상 standby 를 전부 잃으면
커밋이 멈추고, 리스 갱신도 커밋이라 함께 멈추고, 그 사이 리스가 만료되고, standby 가 돌아와
커밋이 풀리는 순간 fencing 이 리더를 끌어내렸다. 복제가 `streaming` 으로 완전히 복구된 뒤에도
readiness 는 503 에 머물렀고 복구에 앱 재기동이 필요했다.

이 회차는 리스 갱신을 PostgreSQL 밖으로 옮긴 뒤 같은 장애를 같은 조건으로 다시 낸다.

## 결론

| 성공 기준 | 판정 | 근거 |
|---|---|---|
| 1. 커밋 stall 중에도 리스 갱신이 계속된다 | 성립 | stall 87초 동안 갱신 0.35/s, 거부 0, 오류 0 |
| 2. standby 전체 상실 동안 리스가 만료되지 않는다 | 성립 | 마지막 갱신 이후 경과 최대 3.0초. TTL 10초 |
| 3. standby 복구 후 fencing 오류가 없다 | 성립 | 앱 전 수명에서 fencing 위반 로그 0건 |
| 4. streaming 복구 후 앱 재기동 없이 정상으로 돌아온다 | 성립 | 재기동 +3초에 접수 45.8/s, +6초에 56.0/s |
| 5. 리더가 불필요하게 재선출되지 않는다 | 성립 | `fencingToken` 10 고정. 승격 로그 1건(기동 시) |
| 6. Manual Scaling 동작에 영향이 없다 | 성립 | 해당 코드 무변경. 통합 검증 471건 전량 통과 |
| 7. Admission Control 동작에 영향이 없다 | 성립 | 정원 6 그대로. stall 구간 5xx 0.0/s, 대기 0.00 |
| 8. 단일 standby 장애에서 `ANY 1` 내성이 유지된다 | 성립 | kill 중 52.1 에서 55.3 주문/s. 5xx 0, 503 0 |

해결하지 못한 것을 함께 적는다. **커밋이 멈춘 동안 새 리더가 승격할 수는 없다.** 승격은 epoch 을
`leader_lease` 에 게시해야 하고 그 게시가 커밋이기 때문이다. 이것은 의도다. PostgreSQL 에 쓸 수
없는 노드는 단일 라이터가 될 수 없다. 다만 "리스를 옮겼으니 모든 것이 DB 와 무관해졌다" 는 아니다.

## 이전 구조 대비

같은 부하, 같은 토폴로지, 같은 kill 대상이다. 이전 값은 `replication-fault-20260908` 의
PHASE 2-E 와 그 직후 복구 관측이다.

| 항목 | PostgreSQL Lease | DynamoDB Lease |
|---|---|---|
| 양쪽 standby kill 시 DB commit | stall | stall |
| Leader lease renew | 실패(커밋에 막혀 반환하지 않음) | **성공. 0.35/s** |
| Lease expiration | 발생. kill +9.6초 | **없음. 리스 나이 최대 3.0초** |
| Leader fencing | 발생. `fencing 위반: 리스 만료` | **없음. 로그 0건** |
| Standby 복구 후 DB | 정상. `streaming`/`quorum` | 정상. `streaming`/`quorum` |
| Readiness 자동 회복 | 실패. 503 영구 | **성공. 200 을 벗어난 적 없음** |
| App restart 필요 | 필요 | **불필요** |
| 자동 정상화 시간 | 없음 | **약 3초** |
| stall 구간 접수 | 0.0/s | 0.0/s |
| stall 구간 429 | 115.0/s | 124.7/s |
| stall 구간 5xx | 0.0/s | 0.0/s |
| stall 구간 Hikari 활성 / 대기 | 6.73 / 0.00 | 6.64 / 0.00 |
| 복구 후 접수 | **0.0/s** | **55.6/s** |
| 주행 전체 503 | 0 | 0 |

두 줄만 바뀌었고 그 두 줄이 이 작업의 전부다. **리스가 만료되지 않고, 복구가 자동이다.**

stall 자체는 그대로다. 동기 복제의 대가는 옮긴다고 사라지지 않는다. 옮긴 것은 그 대가가
리더십까지 가져가던 경로다.

## 변경한 구조

```
종전
  Spring Boot ──┬── 거래/주문/체결 ──→ PostgreSQL
                └── 리더 리스 갱신 ──→ PostgreSQL   (같은 커밋 경로)

지금
  Spring Boot ──┬── 거래/주문/체결 ──→ PostgreSQL   Primary
                │                                    ├── standby_1
                │                                    └── standby_2
                │                                    synchronous_standby_names = ANY 1 (...)
                └── 리더 리스 갱신 ──→ DynamoDB      항목 1개, 조건부 쓰기

  승격 때만 1 회: DynamoDB 토큰 ──게시──→ PostgreSQL leader_lease.epoch
```

트랜잭션 안의 fencing 검증(`assert_current_leader`, `assert_and_lock_leader`)은 한 글자도 바꾸지
않았다. 그 함수들이 읽는 `leader_lease` 행은 승격 때 한 번 게시되고, 그때 `lease_until` 이 100년
뒤로 밀린다. 곧 만료 판정만 외부로 넘어가고 (holder, epoch) 일치 검사는 그대로 남는다.

## DynamoDB 테이블 스키마

| 항목 | 값 |
|---|---|
| 테이블 | `exchange-leader-lease` |
| 파티션 키 | `clusterId` (S) |
| 정렬 키 | 없음. 클러스터 하나가 항목 하나다 |
| 과금 | `PAY_PER_REQUEST` |

| 속성 | 형 | 뜻 |
|---|---|---|
| `clusterId` | S | 클러스터 식별자. 로컬 회차는 `exchange-local` |
| `leaderId` | S | 현재 보유자. 프로세스 기동마다 새로 발급하는 UUID |
| `leaseUntil` | N | 만료 시각. epoch 밀리초 |
| `fencingToken` | N | 승격마다 1 증가. PostgreSQL 의 `epoch` 과 같은 것 |
| `updatedAt` | N | 마지막 쓰기 시각. epoch 밀리초 |

회차 종료 시점의 실제 항목이다.

```
{"clusterId": "exchange-local", "leaderId": "f342b817-17d2-41e0-a4f0-23dfacd0ec50",
 "fencingToken": 10, "leaseUntil": 1788847050177, "updatedAt": 1788847040177}
```

## 조건부 쓰기

세 연산 모두 왕복 한 번이다. 읽고 나서 쓰면 두 노드가 같은 만료를 보고 함께 승격한다.

| 연산 | 조건식 | 갱신식 |
|---|---|---|
| 승격 | `attribute_not_exists(clusterId) OR attribute_not_exists(leaseUntil) OR leaseUntil < :now` | `SET leaderId = :me, leaseUntil = :until, fencingToken = if_not_exists(fencingToken, :zero) + :one, updatedAt = :now` |
| 갱신 | `leaderId = :me AND fencingToken = :tok AND leaseUntil >= :now` | `SET leaseUntil = :until, updatedAt = :now` |
| 반납 | `leaderId = :me AND fencingToken = :tok` | `SET leaseUntil = :past, updatedAt = :now` |

승격 조건에 `attribute_not_exists(leaseUntil)` 갈래가 있는 이유는 실측이다. 보관소를 전환하면
토큰 하한만 심긴 항목이 만들어지는데, 없는 속성과의 비교는 거짓이라 이 갈래가 없으면 그 항목에서
승격이 영원히 실패한다. 이번 전환에서 실제로 막혔다.

갱신 조건이 만료 전을 요구하는 이유는 PostgreSQL `renew_leader_lease` 와 같은 성질을 지키기
위해서다. 만료 뒤 갱신을 허용하면 오래 멈췄던 노드가 아무 일 없었다는 듯 리더를 이어받는다.

거부와 왕복 실패를 가른다. `ConditionalCheckFailedException` 만 거짓이고 나머지 예외는 그대로
던진다. 섞으면 접속 오류만으로 리더가 내려간다.

## fencing 토큰

토큰은 승격에서만 오른다. 갱신은 토큰을 보존한다. 올리면 진행 중인 저널 적재가 자기를 fencing 한다.

새 리더는 접수를 열기 전에 `publish_leader_epoch(holder, token)`(V33)으로 그 토큰을 PostgreSQL
`leader_lease` 에 올린다. 단조 조건은 `epoch < p_epoch OR (epoch = p_epoch AND holder = p_holder)`
이고, 뒤쪽 갈래는 게시 재시도를 정상 경로로 두기 위한 것이다. 게시 실패는 승격 실패다.

**보관소 전환에는 토큰 하한 정렬이 필요하다.** 두 보관소가 서로 다른 카운터를 쓰기 때문이다.
이번 회차에서 PostgreSQL `epoch` 은 이미 9 였고 새 DynamoDB 항목의 토큰은 0 에서 시작했다.
그대로 켜면 첫 승격이 토큰 1 을 받고 단조 조건이 1 을 9 위에 올리기를 거부한다. 게시 실패는
승격 실패이므로 클러스터가 리더를 영원히 뽑지 못한다. 기동 시 정렬을 코드로 넣어 닫았다
(`FencingTokenAlignment`). 조건부 쓰기라 여러 노드가 동시에 불러도 단조이고, 이미 충분히 크면
아무것도 하지 않는다.

정렬 결과가 로그에 남는다.

```
fencing 토큰 정렬: DynamoDB 토큰 하한을 PostgreSQL epoch 9 으로 올렸다
fencing epoch 게시: holder=f342b817-17d2-41e0-a4f0-23dfacd0ec50, epoch=10
리더 승격: holder=f342b817-17d2-41e0-a4f0-23dfacd0ec50, epoch=10
```

## 환경

| 항목 | 값 |
|---|---|
| 호스트 | 12 코어 |
| primary | `ex-pg`, `postgres:17-alpine`, PostgreSQL 17.11, `synchronous_commit=on` |
| 복제 | `synchronous_standby_names = 'ANY 1 (standby_1, standby_2)'` |
| standby | `ex-pg2`, `ex-pg3`. 같은 이미지 |
| 리스 보관소 | `ex-dynamodb`, `amazon/dynamodb-local:2.5.2`, `-inMemory -sharedDb` |
| 앱 리더 | `ex-app`, 2 vCPU / 1GB, 요청 풀 16, 주문 정원 6 |
| 앱 조회 | `ex-app2`. `exchange.engine.enabled=false` 라 리더 경쟁에서 빠진다 |
| 리스 TTL / 틱 | 10초 / 3초 (`exchange.engine` 기본값) |
| 부하 | `harness/load/calibrate.js`, `BASE_OPS=100`, 235초. 이전 회차와 동일 |

## 실행 명령

앱을 리스 보관소 설정과 함께 띄운다. 로컬 주소는 설정으로만 들어오고 코드에는 없다.

```
docker run -d --name ex-dynamodb --network exnet -p 8000:8000 \
  amazon/dynamodb-local:2.5.2 -jar DynamoDBLocal.jar -inMemory -sharedDb

docker run -d --name ex-app --network exnet -p 8080:8080 -m 1g --cpus 2 \
  -e AWS_ACCESS_KEY_ID=local -e AWS_SECRET_ACCESS_KEY=local \
  failstop-app:lease --spring.profiles.active=dev \
  --spring.datasource.url=jdbc:postgresql://ex-pg:5432/exchange \
  --exchange.leader.lease.provider=dynamodb \
  --exchange.leader.lease.endpoint=http://ex-dynamodb:8000 \
  --exchange.leader.lease.region=ap-northeast-2 \
  --exchange.leader.lease.cluster-id=exchange-local \
  --exchange.leader.lease.create-table-if-missing=true
```

장애 회차는 이전 회차의 드라이버를 그대로 쓴다.

```
bash harness/evidence/replication-fault-20260908/run.sh p3b ex-pg2 ex-pg2 ex-pg2,ex-pg3
bash harness/evidence/replication-fault-20260908/run.sh p3e "ex-pg2 ex-pg3" "ex-pg2 ex-pg3" ex-pg2,ex-pg3
```

집계는 같은 도구다.

```
python harness/replication/analyze.py --samples samples/p3e.jsonl --marks samples/p3e_marks.txt \
  --window 05:52:09,05:53:23,3E-A --window 05:53:25,05:54:49,3E-B --window 05:54:59,05:55:53,3E-C
python harness/replication/dynamo_lease.py --endpoint http://localhost:8000 --cluster exchange-local
```

## 측정 결과

### 회차 p3b. standby_1 만 kill (`ANY 1` 내성 유지 확인)

| 구간 | 접수/s | 429/s | 5xx/s | 평균ms | Hikari활성 | 복제 | 리스갱신/s | 토큰 | readiness200 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 정상 2대 | 52.1 | 69.9 | 0.0 | 112.7 | 1.79 | 2 | 0.34 | 10 | 100% |
| standby_1 kill | 55.3 | 69.2 | 0.0 | 104.6 | 2.07 | 1 | 0.34 | 10 | 100% |
| 복구 | 56.3 | 59.1 | 0.0 | 102.2 | 2.19 | 2 | 0.33 | 10 | 100% |

주행 전체: p95 176.9ms, p99 257.8ms, 접수 11,623건, 429 11,878건, **503 0건**.

### 회차 p3e. 양쪽 kill 뒤 부하 중 복구 (핵심 회차)

| 구간 | 접수/s | 429/s | 5xx/s | 평균ms | Hikari활성 | Hikari대기 | 복제 | 리스갱신/s | 리스나이 | 토큰 | readiness200 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 정상 2대 | 54.7 | 68.3 | 0.0 | 105.5 | 2.20 | 0.00 | 2 | 0.32 | 1.4초 | 10 | 100% |
| 양쪽 kill | 0.1 | 124.7 | 0.0 | 2595.0 | 6.64 | 0.00 | 0 | **0.35** | **1.5초** | **10** | **100%** |
| 복구 | 55.6 | 61.8 | 0.0 | 103.9 | 2.16 | 0.00 | 2 | 0.32 | 1.6초 | 10 | 100% |

주행 전체: p95 165.3ms, p99 200.6ms, 접수 7,193건, 429 16,298건, **503 0건**.

`리스나이` 는 마지막 갱신 성공 이후 경과다. stall 구간 평균 1.5초이고 관측 최대가 3.0초다.
TTL 이 10초이므로 만료까지의 여유가 7초 남았다. 곧 커밋이 멈춘 것과 리스 갱신은 완전히 무관하다.

### 타임라인 (p3e)

| 이벤트 | 시각(UTC) | 경과 |
|---|---|---:|
| 양쪽 standby kill | 05:53:23 | 0 |
| 복제 세션 0건 | 05:53:24 | +1초 |
| 접수 0/s 도달 | 05:53:26 | +3초 |
| 첫 지연 이상 관측(5,034ms) | 05:53:30 | +7초 |
| 리스 만료 예정 시각(종전 구조라면) | 05:53:33 | +10초 |
| 리스 갱신 계속. readiness 200 유지 | 05:53:23 ~ 05:54:50 | 전 구간 |
| standby 양쪽 재기동 | 05:54:50 | +87초 |
| 복제 `streaming` 1건 복귀 | 05:54:52 | +89초 |
| 복제 `quorum` 2건 복귀 | 05:54:53 | +90초 |
| **접수 재개 45.8/s** | 05:54:53 | **재기동 +3초** |
| 접수 정상 복귀 56.0/s | 05:54:56 | 재기동 +6초 |
| 앱 재기동 | 없음 | 없음 |

### 테스트 1. 정상 리스와 갱신 대상

리더 1대(`ex-app`), 조회 1대(`ex-app2`, 리더 경쟁 제외). 15초를 3초 간격으로 관측했다.

```
05:45:32  DDB updatedAt=1788846330705   PG lease_until=2126-09-08 05:44:54.582573+00
05:45:35  DDB updatedAt=1788846333712   PG lease_until=2126-09-08 05:44:54.582573+00
05:45:39  DDB updatedAt=1788846336719   PG lease_until=2126-09-08 05:44:54.582573+00
05:45:43  DDB updatedAt=1788846339727   PG lease_until=2126-09-08 05:44:54.582573+00
05:45:46  DDB updatedAt=1788846345740   PG lease_until=2126-09-08 05:44:54.582573+00
05:45:50  DDB updatedAt=1788846348749   PG lease_until=2126-09-08 05:44:54.582573+00
```

DynamoDB 는 3초마다 갱신되고 PostgreSQL 행은 승격 시각에 고정돼 있다. `leaderId` 와
`fencingToken`(10) 이 양쪽에서 같다. **갱신이 PostgreSQL 에 닿지 않는다는 것이 이 표다.**

### 테스트 2. 동시 승격 경쟁

`DynamoDbLeaderLeaseStoreIntegrationTest` 가 잰다. 12 스레드가 같은 순간에 승격을 시도한다.

- 승격 성공 정확히 1건
- 발급된 토큰 집합이 `{1}` 단일 원소. 같은 토큰이 둘에게 가지 않았다
- 연속 페일오버 5회에서 토큰이 1,2,3,4,5 로 단조 증가

## 실패와 예외

- **AWS SDK 가 Apache HttpClient 5 를 끌고 와 통합 검증 8건이 깨졌다.** 그것이 클래스패스에
  있으면 Spring 의 `ClientHttpRequestFactory` 자동 선택이 JDK 클라이언트 대신 그것을 고르고,
  `TestRestTemplate` 의 급송이 소형 커넥션 풀에 직렬화된다. 레이트리밋 급송 검증이 한도를 넘지
  못해 429 대신 201 이 나오고 회차 시간이 5배가 됐다. 베이스라인 커밋에 의존만 얹어 재현해
  원인을 확정했고, `httpclient5`·`httpcore5`·`httpcore5-h2` 를 배제해 닫았다. 배제 후 471건 전량
  통과다. 전송은 `url-connection-client` 하나로 충분하다.
- **전환 첫 시도에서 리더가 뽑히지 않았다.** PostgreSQL `epoch` 이 9 인데 DynamoDB 토큰이 1 부터
  시작해 게시 단조 조건에 걸렸다. `FencingTokenAlignment` 로 닫았다.
- **정렬이 만든 항목에서 승격이 다시 막혔다.** 토큰만 심긴 항목에는 `leaseUntil` 이 없고, 없는
  속성과의 비교는 거짓이라 승격 조건이 영원히 성립하지 않았다. 승격 조건에
  `attribute_not_exists(leaseUntil)` 갈래를 넣고 정렬이 기본값을 함께 채우도록 고쳤다. 회귀
  검증을 남겼다.
- **`LeaderStallDetector` 는 이번 회차에서 발동하지 않았다.** 자발적 강등 임계가 45초이고 stall 이
  87초였으므로 발동 조건이 될 수 있었다. 발동하지 않은 이유는 수용 제한이 거절을 수요 축에서
  빼기 때문이다. 거절은 `AdmissionFilter`(order 6)가 `recordAttempt`(`ApiAuthFilter`, order 10)
  앞에서 처리하므로 수요로 계상되지 않고, 정원 6 이 전부 묶인 구간에서는 누적 수요가 판정 하한
  20 에 닿지 않는다. 곧 이 회차에서는 수용 제한이 좀비 판정의 오발동까지 막았다. **다만 이것은
  이 부하 조건에서 관측된 것이고, 정원이 크거나 요청이 게이트를 통과해 실패하는 조건에서는
  발동할 수 있다.** 그 조건은 재지 않았다.

## 로컬 실험의 한계

- **DynamoDB Local 은 실제 DynamoDB 가 아니다.** 같은 호스트의 컨테이너이고 왕복이 매우 작다.
  실제 DynamoDB 는 리전 내 왕복이 이보다 크고, 조건부 쓰기의 지연과 스로틀링이 다르다. 이 회차의
  리스 갱신 지연을 AWS 값으로 읽으면 안 된다.
- **DynamoDB 를 죽여 보지 않았다.** 보관소 자체가 사라졌을 때의 거동은 이 회차의 대상이 아니다.
  코드상 왕복 실패는 상실로 단정하지 않고 다음 틱에 미루지만, 그 성질을 부하 아래에서 재지 않았다.
- **시계 스큐를 재지 않았다.** DynamoDB 는 조건식에서 쓸 서버 시계를 노출하지 않으므로 만료 판정
  기준이 호출 노드의 시계다. 노드가 하나뿐인 이 회차에서는 스큐가 정의되지 않는다.
- **노드 2대 경쟁을 라이브로 재지 않았다.** 동시 승격 경쟁은 단위 검증에서 12 스레드로 쟀고,
  실제 두 앱 컨테이너가 리더를 다투는 회차는 돌리지 않았다.
- **승격 자체는 여전히 커밋 경로 위다.** 커밋이 멈춘 구간에서 새 리더를 세우는 회차는 재지 않았고,
  설계상 실패해야 한다.
- **호스트가 12 코어이고 과할당 상태다.** 절대 처리량을 클라우드 값으로 읽지 않는다. 유효한 것은
  같은 호스트에서 같은 조건으로 잰 이전 회차와의 상대차다.
- **회차가 조건마다 하나다.** 분산을 재지 않았다.

## 남은 위험

- **`leader_alive` 오라클이 이 보관소에서 권위를 잃는다.** `HarnessController` 가
  `lease_until > clock_timestamp()` 로 리더 생존을 판정하는데 게시가 100년 뒤를 밀어 두므로 항상
  참이다. 권위는 DynamoDB 항목의 `leaseUntil` 이다. 하네스 오라클 이설은 이번 범위 밖이다.
- **되돌리기에 수동 절차가 필요하다.** `dynamodb` 에서 `postgres` 로 되돌리려면 앱을 전부 내린
  상태에서 `SELECT expire_leader_lease_now()`(V33)를 한 번 부른다. 빠뜨리면 `try_acquire_leader`
  의 만료 조건이 영원히 거짓이라 리더가 뽑히지 않는다.
- **자발적 강등이 여전히 커밋 정지에 반응할 수 있다.** 위 실패 절의 마지막 항목이다.
- **`spring.datasource.url` 이 고정 primary 를 본다.** primary 자체를 잃는 축은 이번 범위가 아니다.

## 다음 단계

1. **두 노드 라이브 경쟁 회차.** 앱 컨테이너 둘을 같은 클러스터로 띄우고 리더를 죽여 페일오버를
   잰다. 토큰 단조와 게시 순서가 라이브에서도 서는지, 그리고 게시가 커밋이라는 성질이 페일오버
   시간에 얼마를 더하는지를 잰다.
2. **보관소 장애 회차.** DynamoDB 를 죽이고 왕복 실패가 상실로 번지지 않는지 잰다.
3. **`LeaderStallDetector` 의 판정 축을 다시 본다.** 원인을 묻지 않는 설계가 "이 노드가 고장" 과
   "DB 가 전원에게 안 보임" 을 구분하지 못한다. 후자에서는 강등해도 다음 리더가 더 잘할 수 없다.
4. **하네스 리더 생존 오라클 이설.** 위 남은 위험의 첫 항목이다.
5. **AWS 실물 회차.** DynamoDB Local 이 아닌 실제 테이블에서 갱신 지연과 비용을 잰다.

## 산출물 위치

| 파일 | 내용 |
|---|---|
| `samples/p3b.jsonl` | standby_1 kill 회차 초 단위 표본 |
| `samples/p3e.jsonl` | 양쪽 kill 뒤 복구 회차 초 단위 표본 |
| `samples/*_marks.txt` | 회차별 사건 시각 |
| `samples/*_k6.json` | 부하 도구 주행 요약 |
| `containers.md` | 실험 중 컨테이너·복제·리스 상태 |
| `harness/replication/dynamo_lease.py` | DynamoDB 리스 항목 조회 |
| `harness/replication/repl_sampler.py` | 표본기. 리스 지표와 readiness 포함 |
| `harness/replication/analyze.py` | 집계기 |
| `harness/evidence/replication-fault-20260908/` | 이전 구조의 같은 실험 |
| `docs/reference/adr/0017-leader-lease-out-of-commit-path.md` | 결정 기록 |
