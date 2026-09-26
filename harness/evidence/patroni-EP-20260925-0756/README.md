# E-P1 Primary 강제 종료 둘째 회차 (2026-09-25 07:56 UTC)

첫 회차([patroni-EP-20260925-0738](../patroni-EP-20260925-0738/README.md))에서 드러난 실행기 결함(매달린 psql)과 판정 축 결함(/cluster 기준 확인 커밋 구간)을 고친 뒤 [ep-p1-primary-kill.ps1](../../scripts/ep-p1-primary-kill.ps1)을 다시 돌렸다. 판정은 **INCONCLUSIVE** 다. 시간 축이 아닌 축은 모두 PASS 였고, 시간 축 넷은 값으로는 모두 상한 안이었지만 회차 무효 조건 (c)로 판정에서 뺐다. 주입 뒤 호스트에서 이 작업 밖의 Gradle java 프로세스(pid 36856)가 2초에 CPU 312ms 를 썼다.

## 조건

| 항목 | 값 |
|---|---|
| 스택 | 회차 시작에 새로 세움. Patroni 리더 `patroni-db-2`(타임라인 1), 동기 Standby `patroni-db-1`, 앱 리더 `patroni-api-1`(epoch 1) |
| 주입 | 07:57:16.762 `docker kill patroni-db-2`, 60.1초 뒤 `docker start`(계획 60초) |
| 부하 | 봇 10 OPS, k6 접수 주행, DB 수준 ACK 기록기(스레드 넷, 0.5초 복제 표본) |
| 호스트 | CPU 표본 26·28·22·24·13%, etcd 리더 교체 0, fsync p99 3.7ms, 피어 왕복 p99 1.6ms, 실험 밖 부하 도구 java-gradle 하나(위) |

## 시각(T0 기준 초)과 시간 축

| 기호 | 값 | 축(상한) |
|---|---|---|
| T_down | 2.19 | |
| T_suspend | 4.19 | api-1 은 4.2초에 `database_unavailable`, 25.2초에 `replication_unavailable` |
| T_promote | 22.18 | 승격 22.18초(30초) |
| T_reads | 25.50 | 승격 뒤 조회 복구 3.32초(10초) |
| T_back | 60.12 | |
| 멤버 walsender 시작 | 63.36 | 새 Primary(db-1)의 `pg_stat_replication.backend_start`, 옛 노드 db-2 가 되감기 뒤 붙음 |
| T5 | 65.85 | |
| T_resume | 66.18 | 재개 지연(T_resume − T_sync) −0.51초(5초) |
| T_sync | 66.69 | 체결 재개 지연(T5 − T_sync) −0.84초(30초) |

## 시간 축이 아닌 축(모두 PASS)

앱 epoch 정확히 +1(api-1 → api-2, 실행기가 07:58:24.680 에 관찰), 승격한 리더의 승격 뒤 30초 안 fencing 거부 0·강등 0, 획득 사후 확인 실패 0, 멤버 사본 없이 확인된 쓰기 0(승격부터 멤버 walsender 시작까지, 네 스레드 모두 그 구간에 새 Primary 로 시도를 보냄), 중단 동안 상태 API 가 참, 옛 Primary 가 pg_rewind 로 재합류한 뒤 두 노드의 주요 표 건수·합 같음, 사후 권위 run 위반 0(체크 31), C16 0, 구 epoch 쓰기 증분 0, 주문 500 0, DB 수준 ACK 유실 0(acked 24,186, 주입 전 30초 5,394, 재개 뒤 30초 5,400, acked 가 아닌 시도는 강제 종료 순간의 연결 끊김 4).

설계 문구대로 센 'T_promote~T_sync 사이 확인된 쓰기'는 600 건이고 모두 멤버 walsender 가 붙은 뒤다. 첫 회차와 같은 모양이다. 멤버가 아닌 walsender 표본은 없었다.

## 기록

- T0 부터 T_resume 사이 쓰기 표본: 423 41, 502 21, 429 3, 401 2. 조회 표본은 T0 부터 T_reads 사이 429 49, 200 18, 시간 초과 8 이었다.
- 경보: `TradingSuspended`, `PatroniSyncStandbyMissing`, `PatroniFailoverOccurred` 발화.

## 파일

`EP1-20260925-075605.*` 가 판정, 시각, 사실, 관측 표본, 기록기 대조, 해시, lib.ps1 표준 판정 파일이다. 기록기 원본과 컨테이너 로그 원문은 저장소 밖(`%TEMP%\exchange-patroni-ep\EP1-20260925-075605`)에 두었다. 끝에 스택을 지웠고 이 프로젝트의 컨테이너·볼륨·망 0 을 확인했다.
