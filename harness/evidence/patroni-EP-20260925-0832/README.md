# E-P3b 비대칭 격리 첫 회차 (2026-09-25 08:32 UTC)

[ep-p3b-asymmetric-isolate.ps1](../../scripts/ep-p3b-asymmetric-isolate.ps1)의 첫 회차다. 판정은 **INCONCLUSIVE** 다. 시간 축이 아닌 축은 모두 PASS 였고, 시간 축 넷은 값으로는 모두 상한 안이었지만 회차 무효 조건 (c)로 판정에서 뺐다. 주입 전후 호스트에서 이 작업 밖의 Gradle java 프로세스 둘이 CPU 를 썼다(2초에 312~2,578ms). 판정 파일은 무효 조건 (a)에서 E-P3a·b 를 빼도록(Primary 와 같은 역할의 etcd 멤버를 함께 떼므로 etcd 리더 교체 3 은 주입의 결과다) 판정 도구를 고친 뒤 같은 원시 파일로 다시 계산한 것이다.

## 조건

| 항목 | 값 |
|---|---|
| 스택 | 회차 시작에 새로 세움. Patroni 리더 `patroni-db-2`(타임라인 1), 동기 Standby `patroni-db-1`, 앱 리더 `patroni-api-1`(epoch 1) |
| 주입 | 08:34:05.694 `patroni-db-2` 와 `patroni-etcd-2` 를 DB 망(`patroni-db`)에서만 뗌. 앱 망은 그대로. 60.6초 뒤 별칭(`db-2-repl`)을 붙여 다시 연결 |
| 부하 | 봇 10 OPS, k6 접수 주행(ACK 1,107 줄), DB 수준 ACK 기록기(0.5초 복제 표본) |
| 호스트 | CPU 표본 38·35·20·37·32%, etcd WAL fsync p99 7.3ms, 피어 왕복 p99 1.6ms |

## 시각(T0 기준 초)

| 기호 | 값 | 비고 |
|---|---|---|
| T_suspend | 4.18 | 두 앱이 `replication_unavailable`(SUSPENDED). 옛 Primary 가 Standby 에서 떨어져 커밋이 동기 복제를 기다렸다 |
| T_down | 21.19 | HAProxy 가 db-2 를 내림(db-2 의 Patroni 가 19.7초에 DCS 통신 오류를 적은 뒤). 같은 때 두 앱이 `database_unavailable`. db-2 의 Patroni 는 failsafe 로 db-1 의 REST 에 닿지 못하자 27.6초에 스스로 강등했다 |
| T_promote | 27.66 | db-1 이 타임라인 2 의 리더(상한 30초). 23.2~27.7초에는 /cluster 에 리더가 없었다 |
| T_reads | 31.36 | 승격 뒤 3.70초(상한 10초) |
| T_back | 60.59 | 다시 연결 |
| 멤버 walsender 시작 | 65.22 | 새 Primary 에서 되감기를 마친 db-2 가 붙음 |
| T_resume | 69.18 | T_sync(71.16)보다 1.99초 앞섬(상한 5초) |
| T5 | 73.49 | T_sync 뒤 2.33초(상한 30초). api-1 이 epoch 2 로 다시 리더가 됐다 |

## 축(시간 축이 아닌 것은 모두 PASS)

- 두 노드가 같은 시간대에 확인한 커밋 0: 기록기의 acked 끝 시각은 db-2 에서 T0 + 0.06초까지, db-1 에서 멤버가 붙은 65.2초부터였다. 격리 뒤 옛 Primary 가 확인한 쓰기는 없다.
- 승격 뒤 멤버 사본 없이 확인된 쓰기 0(네 스레드 모두 그 구간에 새 Primary 로 시도를 보냄). 설계 문구대로 센 T_promote~T_sync 사이 확인은 1,056 건이고 모두 멤버가 붙은 뒤다.
- 앱 epoch 정확히 +1, 승격한 리더의 승격 뒤 30초 안 fencing 거부 0·강등 0, 획득 사후 확인 실패 0, 중단 동안 상태 API 가 참, 옛 Primary 가 pg_rewind 로 재합류한 뒤 두 노드의 주요 표 건수·합 같음, 사후 권위 run 위반 0(체크 31), C16 0, 구 epoch 쓰기 증분 0, 주문 500 0, DB 수준 ACK 유실 0(acked 24,265).
- acked 가 아닌 시도는 격리 순간의 연결 끊김 4 건이고 넷 모두 최종 Primary 의 표에 있었다. 격리 직전 Standby 가 받은 WAL 에 있던 커밋으로 보인다(확인 응답만 돌아오지 못함).

## 기록

- 경보: `EtcdMemberDown`, `TradingSuspended`, `PatroniSyncStandbyMissing`, `PatroniFailoverOccurred` 발화.
- 실행기의 재개 대기 루프가 상태 API 를 노드마다 0.5초마다 읽어 관측기 표본과 합쳐 상한(분당 120회)을 넘었고, 끝 무렵 api-1 상태 표본 하나가 429 였다. 판정은 200 표본만 쓴다. 이 회차 뒤 실행기의 상태 읽기를 2초 간격으로 줄였다.

## 파일

`EP3b-20260925-083238.*` 가 판정, 시각, 사실, 관측 표본, 기록기 대조, 해시, lib.ps1 표준 판정 파일이다. 원문 로그는 저장소 밖(`%TEMP%\exchange-patroni-ep\EP3b-20260925-083238`)에 있다. 끝에 스택을 지웠고 이 프로젝트의 컨테이너·볼륨·망 0 을 확인했다.
