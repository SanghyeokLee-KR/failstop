# 두 노드 리더 페일오버 실측 (2026-09-08)

증거 `leader-lease-dynamodb-20260908` 이 리더 리스를 PostgreSQL 커밋 경로에서 뗐다. 그 회차는
리더가 죽는 축을 재지 않았다. 노드가 하나뿐이었기 때문이다. 이 회차는 노드를 둘로 늘리고
현재 리더를 실제로 죽여서 팔로워가 승격하는지 잰다.

## 결론

| 판정 | 내용 | 실측이 뒷받침하는가 |
|---|---|---|
| A | PostgreSQL 정상 상태에서 리더 kill 시 팔로워가 자동 승격한다 | **그렇다.** 세 회차에서 T0+11.3초, +12.0초, +13.1초 |
| B | 페일오버 동안 split-brain 이 발생하지 않는다 | **그렇다.** 토큰이 한 번도 겹치지 않았고 두 노드가 동시에 readiness 200 인 표본이 0개다 |
| C | 옛 리더가 복귀해도 옛 토큰으로 리더 권한을 되찾지 못한다 | **그렇다.** 복귀는 팔로워로만 성립했다 |
| D | 페일오버 후 앱 재시작 없이 주문 처리가 복구된다 | **그렇다.** 새 리더에서 T0+15초에 68.1 주문/s |
| E | 커밋 stall 중에는 리스 획득은 되어도 epoch 게시 때문에 승격이 막힌다 | **그렇다.** 토큰 20에서 21로 올랐는데 PG epoch 은 20에 머물고 readiness 503 |

성공 기준 10개 중 8개가 성립하고 2개가 조건부다. 조건부 둘을 먼저 적는다.

- **옛 리더의 복귀는 부하 중에 실패한다.** 크래시 창 재적재가 리더 자격을 요구하는데 새 리더가
  리스를 쥐고 있어서, 15초 대기 뒤 기동을 거부하고 프로세스가 종료한다. 부하를 끊으면 정상
  복귀한다. 이것은 리스 설계가 아니라 `EngineRecovery` 의 기존 규칙이고 의도된 동작이다.
- **승격 재생이 메모리를 두 배로 쓴다.** OPEN 주문 195,808건 상태에서 1GiB 노드는 승격 재생을
  끝내지 못하고 GC 스파이럴에 빠졌다. 2GiB 로 올리자 같은 조건에서 성립했다.

## 실험 전 구조

| 항목 | 값 |
|---|---|
| Leader | app-1 (`ex-app`, 호스트 8080) |
| Follower | app-2 (`ex-app2`, 호스트 8081) |
| 이미지 | `failstop-app:lease` |
| 노드 규격 | 2 vCPU / 2 GiB (초기 회차는 1 GiB, 아래 참조) |
| 리스 보관소 | DynamoDB Local `ex-dynamodb`, 테이블 `exchange-leader-lease`, 클러스터 `exchange-local` |
| 리스 TTL / 틱 | 10초 / 3초 |
| PostgreSQL | Primary `ex-pg` + `ex-pg2`(standby_1) + `ex-pg3`(standby_2) |
| 동기 복제 | `synchronous_standby_names = 'ANY 1 (standby_1, standby_2)'` |
| Admission | 노드당 주문 정원 6 |
| 로드밸런서 | `ex-lb` nginx 1.29 |
| 부하 | `harness/load/calibrate.js`, `BASE_OPS=100` |
| 봇 | `ex-bot` 가동 |

PHASE 2 직전 상태다.

| 항목 | 값 |
|---|---|
| Leader | app-1 |
| Follower | app-2 |
| `fencingToken` | 19 |
| PostgreSQL `epoch` | 19 |
| app-1 readiness | 200 |
| app-2 readiness | 503 |
| 주문 처리 | 가능. LB 경유 조회 200, 접수 73.9/s |

## Leader failover 흐름

```
  T0  리더 프로세스 소멸
      |
      | 리스 갱신이 멈춘다. 남은 TTL 만큼 아무 일도 일어나지 않는다
      v
  T2  DynamoDB leaseUntil 경과. 리스가 만료된다
      |
      | 팔로워의 리스 틱(3초 주기)이 다음 차례에 조건부 쓰기를 시도한다
      v
  T4  팔로워가 acquire 성공. fencingToken 이 1 오른다
      |
      v
  T6  publish_leader_epoch 으로 PostgreSQL leader_lease 에 epoch 게시
      |    <- 이 단계가 커밋이다. 커밋이 멈춰 있으면 여기서 막힌다(PHASE 5)
      v
      승격 확정. 승격 재생(스냅샷 + 저널 tail)으로 인메모리 엔진을 다시 만든다
      |
      v
  T7  readiness ACCEPTING_TRAFFIC
  T8  첫 주문 성공
```

## 변경 파일 목록과 이유

| 파일 | 변경 | 이유 |
|---|---|---|
| `harness/replication/run.sh` | 신규(공용 자리로 이설) | 이전 회차 드라이버는 표본 대상이 한 노드였다. 페일오버는 카운터가 노드 사이를 옮겨가므로 다중 노드가 필요하다. 이전 회차 사본은 그 회차가 실제로 돌린 것이라 손대지 않았다 |
| `harness/replication/repl_sampler.py` | `--app-url` 반복 허용, 노드별 증분, 병렬 조회, `--pg-every` | 아래 "표본기 결함 둘" 참조 |
| `harness/replication/analyze.py` | 변경 없음 | 다중 노드 표본이 종전 키를 그대로 채우도록 표본기 쪽에서 맞췄다 |
| `server/.../engine/lease/LeaderLeaseStore.java` | `now()` 기본 메서드 추가 | 만료 판정을 묻는 쪽 시계로 하면 스큐가 판정에 들어온다. 보관소가 자기 시계를 준다 |
| `server/.../engine/lease/PostgresLeaderLeaseStore.java` | `storeNow()` 를 `now()` 재정의로 승격 | 위와 짝 |
| `server/.../engine/lease/MeteredLeaderLeaseStore.java` | `now()` 위임 | 감싸기가 계약을 가리면 안 된다 |
| `server/.../harness/HarnessController.java` | `leader_alive` 판정 권위를 리스 보관소로 이설 | 아래 "하네스 오라클" 참조 |
| `scratchpad/lb/nginx.conf` (로컬 스택) | 쓰기 upstream 에 두 번째 노드를 `backup` 으로 추가 | 쓰기 대상이 한 대뿐이면 리더가 죽어도 쓰기가 죽은 노드로 계속 가서 페일오버가 클라이언트에게 보이지 않는다 |

주문 처리 로직, Manual Scaling, Admission Control, `ANY 1` 구성, `LeaderLeaseStore` 의
DynamoDB 구현은 건드리지 않았다.

## 실행 명령

```
# 두 노드. 같은 이미지, 같은 리스 좌표. app-2 만 flyway 를 끈다
docker run -d --name ex-app  --network exnet -p 8080:8080 -m 2g --cpus 2 ... failstop-app:lease \
  --exchange.leader.lease.provider=dynamodb --exchange.leader.lease.endpoint=http://ex-dynamodb:8000 \
  --exchange.leader.lease.cluster-id=exchange-local
docker run -d --name ex-app2 --network exnet -p 8081:8080 -m 2g --cpus 2 ... failstop-app:lease \
  ... --spring.flyway.enabled=false

# PHASE 2 / PHASE 4. 리더 kill. 재기동은 부하 중에 실패하므로 회차 안에서 하지 않는다
OUT_DIR=... APPS="http://localhost:8080 http://localhost:8081" \
  SAMPLER_ARGS="--pg-every 5 --stat-every 5 --node-timeout 0.5" \
  DURATION=170 LOAD=150 KILL_AT=50 RESTART_AT=100 \
  bash harness/replication/run.sh f5 ex-app "" ""

# PHASE 5. 두 단계 kill 이라 전용 드라이버
bash harness/evidence/leader-failover-20260908/stall_then_kill.sh <out> ex-app2

# 리스 직접 확인
python harness/replication/dynamo_lease.py --endpoint http://localhost:8000 --cluster exchange-local
```

## 실제 타임라인

### PHASE 2. app-1 kill (2 GiB, 회차 f5)

| 이벤트 | 시각(UTC) | 경과 |
|---|---|---:|
| Leader kill | 06:48:11 | 0 |
| Lease renew 중단 | 06:48:11 | 0 |
| Lease 만료 | 06:48:21 | +10초 |
| Follower acquire 성공, `fencingToken` 19에서 20 | 06:48:21.74 | **+10.7초** |
| PG epoch publish 성공 | 06:48:21.741 | +10.7초 |
| 승격 재생 완료 | 06:48:22.964 | +12.0초 |
| readiness 200 | 06:48:22.964 | **+12.0초** |
| 첫 주문 성공 | 06:48:23 | +12초 |
| 처리량 정상화(68.1 주문/s) | 06:48:26 | +15초 |

acquire 와 publish 가 같은 밀리초에 찍힌다. 두 호출이 같은 틱 안에서 연달아 일어나고 그 사이에
아무것도 없기 때문이다. 이 회차에서 그 간격은 1밀리초 미만이다.

### PHASE 4. app-2 kill (반대 방향, 1 GiB, 회차 f3)

| 이벤트 | 시각(UTC) | 경과 |
|---|---|---:|
| Leader kill | 06:25:16 | 0 |
| Lease 만료 | 06:25:25 | +9초 |
| acquire 성공, `fencingToken` 14에서 15 | 06:25:28.026 | +12.0초 |
| PG epoch publish 성공 | 06:25:28.026 | +12.0초 |
| readiness 200 | 06:25:29.058 | **+13.1초** |

방향을 바꿔도 같은 순서로 같은 시간대에 끝난다. 페일오버가 특정 노드에 종속되지 않는다.

### PHASE 5. 커밋 stall 중 리더 kill (회차 f6)

| 이벤트 | 시각(UTC) | 경과 |
|---|---|---:|
| 부하 시작 | 06:53:31 | |
| standby 두 대 kill. 커밋 stall 시작 | 06:54:17 | 0 |
| 리더 유지. 리스 갱신 0.34/s, readiness 200 | 06:54:17 ~ 06:54:53 | 전 구간 |
| 리더 kill | 06:54:53 | +36초 |
| 리스 만료 | 06:55:03 | +46초 |
| **팔로워 acquire 성공. 토큰 20에서 21** | 06:55:05 ~ 06:55:09 | +48 ~ 52초 |
| **PG epoch publish 막힘. epoch 20 그대로** | 계속 | |
| 팔로워 readiness | 503 유지 | |
| 리스 갱신 0.00/s. 틱 스레드가 게시에서 멈춘다 | 계속 | |
| standby 재기동 | 06:56:32 | +135초 |
| 막혔던 게시 완료. 승격 epoch 21 | 06:56:33.699 | +136.7초 |
| 승격 재생 실패, 회복 가능 반납 | 06:56:35.373 | +138.4초 |
| 재게시 epoch 22, 승격 | 06:56:36.715 | +139.7초 |
| 승격 재생 완료, readiness 200 | 06:56:37.491 | +140.5초 |

**재기동 시점 기준으로는 5.5초 만에 정상화됐다.** 앱 재기동은 없었다.

토큰 21이 버려지고 22가 쓰였다. 첫 승격의 재생이 실패했기 때문이고, 그 실패는 종신 강등이
아니라 회복 가능 반납으로 처리돼 같은 노드가 1.3초 뒤 다시 승격했다. 이 등급 구분이 없었다면
클러스터가 후보를 소진했다.

## 결과표

| 조건 | failover 성공 | failover 시간 | 5xx/s | 429/s | split-brain | app restart |
|---|---|---:|---:|---:|---|---|
| app-1 kill -> app-2 승격 (2GiB, f5) | 성공 | 12.0초 | 37.3(창 안) | 3.5(창 안) | 없음 | 불필요 |
| app-1 복귀 (부하 중) | **실패** | 없음 | 해당 없음 | 해당 없음 | 없음 | 프로세스 종료 |
| app-1 복귀 (부하 정지) | 성공 | 40초(기동) | 0.0 | 0.0 | 없음 | 불필요 |
| app-2 kill -> app-1 승격 (1GiB, f3) | 성공 | 13.1초 | 41.8(창 안) | 0.1(창 안) | 없음 | 불필요 |
| app-1 kill -> app-2 승격 (1GiB, OPEN 19.6만, f4) | **실패** | 없음 | 측정 불가 | 측정 불가 | 없음 | 필요 |
| DB stall 중 leader kill (f6) | **실패(설계대로)** | stall 해소 +5.5초 | 0.0 | 124.8 | 없음 | 불필요 |

## 구간별 처리량

앱 Micrometer 카운터를 노드별로 증분 낸 뒤 더한 값이다.

### PHASE 2 (f5)

| 구간 | 접수/s | 429/s | 5xx/s | 평균ms | Hikari활성 | readiness200 | 토큰 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 정상(app-1 리더) | 73.9 | 53.4 | 0.0 | 76.9 | 1.95 | 100% | 19 |
| 페일오버 창 12초 | 0.0 | 3.5 | 37.3 | 56.4 | 1.11 | **0%** | 전이 중 |
| 새 리더(app-2) | **92.0** | 8.0 | 0.0 | 59.3 | 2.03 | 100% | 20 |

### PHASE 5 (f6)

| 구간 | 접수/s | 429/s | 5xx/s | 평균ms | 복제 | 리스갱신/s | readiness200 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 정상 | 99.9 | 0.0 | 25.5 | 16.0 | 2 | 0.32 | 100% |
| DB stall, 리더 생존 | 0.0 | 119.9 | **0.0** | 5015.7 | 0 | **0.34** | **100%** |
| DB stall + 리더 kill | 0.0 | 124.8 | **0.0** | 없음 | 0 | **0.00** | **0%** |

## p95 / p99 와 클라이언트 관점

부하 도구의 주행 전체 집계다. 구간별 백분위는 재지 않았다.

| 회차 | p95 | p99 | 접수 | 429 | 502 | 503 |
|---|---:|---:|---:|---:|---:|---:|
| f1 app-1 kill (1GiB, 초기) | 88.8ms | 154.5ms | 13,654 | 2,663 | 1,188 | 0 |
| f3 app-2 kill (1GiB) | 116.5ms | 188.7ms | 12,627 | 3,708 | 639 | 538 |
| f5 app-1 kill (2GiB) | 96.7ms | 173.3ms | 11,265 | 2,708 | 596 | 467 |
| f6 DB stall + 리더 kill | 32.2ms | 50.7ms | 4,469 | 12,650 | **4** | 0 |

**f6 이 이 표의 핵심이다.** 커밋이 멈추고 리더까지 없는 구간에서 클라이언트가 본 하드 오류가
502 네 건뿐이다. 나머지는 전부 계획된 429다. 수용 제한이 리더 부재 구간까지 흡수했다.

f1, f3, f5 의 502와 503은 페일오버 창 12~13초 동안 클라이언트가 실제로 본 오류다. 이 창에서는
쓰기를 받을 노드가 없으므로 오류가 나는 것이 맞다. 다만 **정상 구간의 서버측 5xx 25~37/s 는
클라이언트 오류가 아니다.** LB 의 쓰기 upstream 이 정적이라 리더가 `backup` 쪽일 때 모든 쓰기가
팔로워에 먼저 닿아 503을 받고 재시도된다. 그 503을 팔로워의 Micrometer 가 센다. 클라이언트는
재시도 결과만 본다. 서버측 5xx 합계(약 3,900건)와 클라이언트 503(467건)의 차가 그 몫이다.

## fencingToken 과 PostgreSQL epoch 변화

| 시점 | 리더 | `fencingToken` | PG `epoch` | 일치 |
|---|---|---:|---:|---|
| 실험 시작 | app-1 | 12 | 12 | 일치 |
| f1 페일오버 후 | app-2 | 13 | 13 | 일치 |
| app-2 재기동 후 | app-2 | 14 | 14 | 일치 |
| f3 페일오버 후 | app-1 | 15 | 15 | 일치 |
| 노드 재구성 후 | app-1 | 17 | 17 | 일치 |
| f4 승격(재생 미완) | 없음 | 18 | 18 | 일치 |
| 2GiB 재구성 후 | app-1 | 19 | 19 | 일치 |
| f5 페일오버 후 | app-2 | 20 | 20 | 일치 |
| **f6 stall 중 acquire** | **없음** | **21** | **20** | **불일치** |
| f6 stall 해소 후 | app-1 | 22 | 22 | 일치 |

**불일치는 한 번 나왔고 원인이 명확하다.** 커밋이 멈춘 동안 DynamoDB acquire 는 성공하고 PG
게시는 막힌다. 그 구간에서 토큰이 epoch 보다 1 앞선다. 이 불일치가 위험하지 않은 이유는 게시
없이는 승격이 확정되지 않기 때문이다. 토큰만 앞선 노드는 리더가 아니고 쓰기도 하지 않는다.
게시가 풀리면 그 노드가 다시 승격을 시도하고 그때 둘이 다시 맞는다.

곧 **PostgreSQL epoch 은 "쓰기 권한을 가진 마지막 리더" 이고 DynamoDB 토큰은 "리스를 마지막으로
잡은 노드" 다.** 정상 상태에서 둘은 같고, 게시가 막힌 구간에서만 토큰이 앞선다.

## split-brain 여부

없다. 근거 셋이다.

- **표본 기준.** 세 페일오버 회차의 전 표본에서 두 노드가 동시에 `readiness 200` 인 표본이
  0개다. `exchange_leader_is_leader` 가 동시에 1인 표본도 0개다.
- **토큰 기준.** 위 표에서 같은 토큰이 두 노드에 발급된 적이 없다. 조건부 쓰기가 정확히 한
  쪽만 통과시킨다. 단위 검증이 12 스레드 동시 경쟁에서 같은 성질을 따로 잰다
  (`DynamoDbLeaderLeaseStoreIntegrationTest`).
- **쓰기 기준.** 옛 리더는 프로세스가 소멸했으므로 쓰기 자체가 없다. 복귀한 옛 리더는 아래대로
  팔로워이거나 기동에 실패했다.

## old leader 재기동 결과

두 조건에서 다르게 나온다.

**부하 중 재기동은 실패한다.** 프로세스가 종료한다.

```
Caused by: java.lang.IllegalStateException:
  크래시 창 이벤트 5건 재적재 불가: 리더 승격 실패(대기 15000ms 소진)
    at kr.exchange.engine.EngineRecovery.recover(EngineRecovery.java:156)
```

`EngineRecovery` 의 규칙이다. 재생 결과에 `event_journal` 에 없는 이벤트가 남으면 그것을 다시
적재해야 하고, 적재는 리더 자격을 요구한다. 그 코드의 주석은 "그 리더가 같은 창을 이미 메웠다면
애초에 pending 이 비고" 를 전제로 든다. **그 전제가 부하 중에는 성립하지 않는다.** 현재 리더가
계속 쓰고 있으므로, 재생하는 순간에도 `command_journal` 에는 있고 `event_journal` 에는 아직 없는
명령이 항상 몇 건 존재한다. 부팅하는 노드는 그것을 자기 크래시 창으로 읽는다.

곧 **부하 중에는 노드를 붙일 수 없다.** 이것은 리스 설계와 무관한 기존 동작이고, 이번 회차가
두 노드를 실제로 돌려서 처음 드러냈다.

**부하를 끊으면 정상 복귀한다.** 40초 만에 기동을 마치고 readiness 503 팔로워가 된다. 현재
리더는 영향을 받지 않고 readiness 200 을 유지한다. 옛 토큰으로 리더 행세를 하지 않는다.
`fencingToken` 도 오르지 않는다. 유효한 리스가 있으므로 `acquire` 조건이 거짓이기 때문이다.

## DB stall 중 failover 결과

판정 E 그대로다. 순서까지 실측했다.

1. **DynamoDB acquire 는 된다.** 토큰이 20에서 21로 올랐다. 리스 보관소는 PostgreSQL 과 무관하다.
2. **PG epoch publish 는 막힌다.** `publish_leader_epoch` 이 커밋이고 동기 대상 standby 가 전부
   없으므로 반환하지 않는다. PG epoch 이 20에 머문다.
3. **승격이 확정되지 않는다.** 게시 실패는 승격 실패이므로 `readiness` 가 503 에 머문다.
4. **리스 틱 스레드가 게시 안에서 멈춘다.** 그래서 갱신이 0.00/s 가 되고, 방금 얻은 리스가
   갱신되지 않은 채 만료된다. 다른 후보가 있었다면 그 후보가 같은 자리에서 같은 이유로 막힌다.
5. **stall 이 풀리면 자동으로 이어진다.** 막혀 있던 게시가 완료되고 승격이 확정된다. 이 회차에서는
   첫 승격의 재생이 실패해 회복 가능 반납이 한 번 일어났고, 1.3초 뒤 재승격해 5.5초 만에
   readiness 200 이 됐다. 앱 재기동은 필요 없었다.

**이 구간에서 클라이언트가 본 하드 오류는 502 네 건이다.** 나머지 12,650건은 429다. 쓸 수 없는
구간에서 요청을 계획적으로 거절하는 성질이 리더 부재까지 덮었다.

## 하네스 오라클

`HarnessController` 의 `leader_alive` 가 `leader_lease.lease_until > clock_timestamp()` 였다.
리스가 DynamoDB 로 옮겨간 뒤 그 행은 승격 때 한 번만 게시되고 `lease_until` 이 100년 뒤이므로
이 판정은 **항상 참**이 된다.

PHASE 4 페일오버 창에서 실측했다.

```
06:25:17 오라클 leaderAlive=True holder=8b0ee8f8 epoch=14 | DDB holder=8b0ee8f8 tok=14 ttl=+7.6s
06:25:22 오라클 leaderAlive=True holder=8b0ee8f8 epoch=14 | DDB holder=8b0ee8f8 tok=14 ttl=+2.6s
06:25:28 오라클 leaderAlive=True holder=8b0ee8f8 epoch=14 | DDB holder=8b0ee8f8 tok=14 ttl=-2.5s
06:25:30 오라클 leaderAlive=True holder=cc7f0817 epoch=15 | DDB holder=cc7f0817 tok=15 ttl=+8.4s
```

`8b0ee8f8` 은 06:25:16 에 죽은 노드다. 오라클은 그 노드가 죽고 리스가 만료된 뒤에도 11초 동안
`leaderAlive=True` 를 답했다. 리더 생존을 이 값으로 판정하는 실험은 그 창에서 통과한다.

**최소 수정으로 권위를 옮겼다.** `LeaderLeaseStore.current()` 와 `LeaderLeaseStore.now()` 를
읽는다. 응답 모양은 그대로다. 만료 판정을 보관소 시계로 하는 이유는 묻는 쪽 시계를 쓰면 스큐가
판정에 들어오기 때문이다.

수정 후 같은 엔드포인트가 실제 만료 시각을 답한다.

```
:8080 leaderAlive=True holder=f2c002d1 epoch=22 leaseUntil=2026-09-08T06:59:07.939Z
```

종전에는 이 자리에 `2126-09-08` 이 나왔다. 두 노드가 같은 값을 답하는 것도 확인했다. 판정의
원천이 노드 로컬 상태가 아니라 공용 보관소이기 때문이다.

`admin/HaMonitoringController` 의 `lease_remaining_ms` 와 `leader_alive` 는 같은 문제를 갖고
있고 이번 범위에 넣지 않았다. 남은 위험에 등재한다.

## 실패와 예외

- **표본기 결함 둘을 이 회차가 드러냈고 둘 다 고쳤다.**
  - 노드별 누계를 합산해 비율을 냈더니, 노드가 사라진 틱에서 합계가 줄어 **거절이 초당 -1,864**
    로 찍혔다. 노드별로 증분을 내고 그 증분을 더하도록 바꿨다. 사라진 노드는 0 이고 갓 붙은
    노드는 첫 틱을 건너뛴다.
  - 죽은 노드를 직렬로 조회했더니 도커 포트 매핑이 접속을 즉시 거절하지 않아 **1초 틱이 승격
    구간에서만 9초로 벌어졌다.** 하필 그 구간이 측정 대상이었다. 노드 조회를 병렬화하고
    타임아웃을 0.5초로 줄였다. 복제 조회와 `docker stats` 도 성기게 할 수 있게 했다.
- **LB 의 쓰기 upstream 이 한 대뿐이라 페일오버가 클라이언트에게 보이지 않았다.** 두 번째 노드를
  `backup` 으로 넣어 닫았다. `resolve`·`zone` 과 함께 `backup` 이 서는지는 `nginx -t` 로 확인했다.
- **1GiB 노드가 OPEN 주문 195,808건에서 승격 재생을 끝내지 못했다.** 승격 06:34:28 에 게시까지
  성공했으나 재생이 끝나지 않았고, 부하를 끊은 뒤에도 CPU 200%, 메모리 770MiB/1GiB 로 고정된 채
  `readiness` 가 응답하지 않았다. `OutOfMemoryError` 로그는 0건이고 GC 스파이럴이다. 승격 재생은
  새 엔진을 만들어 설치하므로 그 순간 엔진 상태가 두 벌이 된다. 2GiB 로 올려 같은 조건에서
  성립시켰다. 팔로워 정상 상태의 메모리가 484MiB 이므로 두 벌이 1GiB 힙(742MB)을 넘는다.
- **f2 회차는 무효다.** app-1 이 앞선 회차에서 기동에 실패해 이미 죽어 있었고, 그 상태에서 app-2
  를 죽여 페일오버 대상이 없었다. 산출물을 증거에 넣지 않는다.
- **정상 구간의 서버측 5xx 는 클라이언트 오류가 아니다.** 위 "클라이언트 관점" 절에 근거를 적었다.

## 로컬 실험의 한계

- **DynamoDB Local 은 실제 DynamoDB 가 아니다.** 같은 호스트의 컨테이너라 왕복이 매우 작다.
  실제 리전 내 왕복은 더 크고, 그만큼 acquire 시각이 늦어져 페일오버 시간이 늘어난다.
- **노드가 둘뿐이다.** 셋 이상에서의 경쟁은 재지 않았다. 단위 검증이 12 스레드 동시 경쟁을
  따로 재지만 그것은 프로세스 안의 스레드이지 노드가 아니다.
- **시계 스큐를 만들지 않았다.** 두 노드가 같은 호스트라 스큐가 정의되지 않는다. 만료 판정이
  노드 시계에 걸린다는 성질은 그대로 미검증이다.
- **네트워크 분단을 재지 않았다.** 이 회차의 장애는 전부 프로세스 소멸이다. 리더가 살아 있는데
  보관소만 안 보이는 조건은 다르게 거동한다.
- **호스트가 12 코어이고 과할당 상태다.** 절대 처리량을 클라우드 값으로 읽지 않는다.
- **회차가 조건마다 하나다.** 페일오버 시간 11.3 / 12.0 / 13.1초는 세 회차의 관측이지 분포가 아니다.
- **엔진 상태가 실험 중 계속 커졌다.** OPEN 주문이 회차 사이에 19.6만에서 20.8만으로 늘었다.
  회차 사이의 처리량 차에 이 몫이 섞여 있다.

## 남은 위험

- **부하 중에는 노드를 붙일 수 없다.** 크래시 창 재적재가 리더 자격을 요구하는데, 현재 리더가
  쓰는 중이면 부팅 노드가 항상 pending 을 본다. 운영에서 이것은 "장애 노드를 교체하려면 부하를
  멈춰야 한다" 는 뜻이다. 무중단 교체가 성립하지 않는다.
- **승격 재생 메모리가 상태 크기에 비례해 두 배로 든다.** 노드 규격을 정상 운영 기준으로 잡으면
  승격에서 죽는다. 규격 산정에 이 몫을 넣어야 한다.
- **커밋이 멈추면 승격도 멈춘다.** 설계대로이지만, 그 구간에 리더까지 잃으면 클러스터가 리더
  없이 대기한다. stall 이 길면 그만큼 길어진다.
- **리스 틱 스레드가 게시에서 막힌다.** 단일 스레드 스케줄러라 게시가 막히면 그 노드의 리스
  활동 전체가 멈춘다. 방금 얻은 리스도 갱신되지 않아 만료된다.
- **`admin/HaMonitoringController` 의 리더 판정이 아직 `leader_lease` 행 기준이다.**
- **`spring.datasource.url` 이 고정 primary 를 본다.** primary 자체를 잃는 축은 여전히 범위 밖이다.

## 다음 단계

1. **크래시 창 판정을 부하와 무관하게 만든다.** 부팅 노드가 "현재 리더가 쓰는 중인 명령" 과
   "옛 리더가 남긴 크래시 창" 을 구분해야 무중단 노드 교체가 성립한다. 현재는 구분하지 않는다.
2. **승격 재생 메모리를 재고 노드 규격에 반영한다.** OPEN 주문 수 대 승격 시 최대 힙의 관계를
   재면 규격 산정에 쓸 수 있다.
3. **게시를 리스 틱 스레드에서 뗀다.** 게시가 막혀도 그 노드가 다른 후보에게 자리를 넘길 수
   있어야 한다. 지금은 틱 스레드가 통째로 멈춘다.
4. **네트워크 분단 회차.** 프로세스는 살아 있는데 보관소가 안 보이는 조건에서 "왕복 실패는 상실로
   단정하지 않는다" 계약이 실제로 어떻게 거동하는지 잰다.
5. **`admin/HaMonitoringController` 오라클 이설.**
6. **AWS 실물에서 페일오버 시간 재측정.** DynamoDB 왕복이 커지면 acquire 시각이 늦어진다.

## 산출물 위치

| 파일 | 내용 |
|---|---|
| `samples/f5.jsonl` | PHASE 2. app-1 kill, 2GiB |
| `samples/f3.jsonl` | PHASE 4. app-2 kill, 1GiB |
| `samples/f6.jsonl` | PHASE 5. DB stall 중 리더 kill |
| `samples/*_marks.txt` | 회차별 사건 시각 |
| `samples/*_k6.json` | 부하 도구 주행 요약 |
| `samples/promotion-1gib-failure.txt` | 1GiB 승격 실패 관측 |
| `stall_then_kill.sh` | PHASE 5 전용 드라이버 |
| `containers.md` | 환경과 상태 스냅샷 |
| `harness/replication/run.sh` | 공용 회차 드라이버 |
| `harness/replication/dynamo_lease.py` | 리스 항목 조회 |
| `harness/evidence/leader-lease-dynamodb-20260908/` | 리스 분리 회차 |
| `docs/adr/0017-leader-lease-out-of-commit-path.md` | 결정 기록 |
