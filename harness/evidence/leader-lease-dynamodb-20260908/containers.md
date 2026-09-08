# 실험 전후 컨테이너 상태 (2026-09-08)

## 회차 중
```
NAMES              IMAGE                         STATUS
ex-app             failstop-app:lease            Up 12 minutes
ex-pg3             postgres:17-alpine            Up 2 minutes
ex-pg2             postgres:17-alpine            Up 2 minutes
ex-dynamodb        amazon/dynamodb-local:2.5.2   Up 24 minutes
ex-app3            failstop-app:wslimit          Exited (143) 3 hours ago
ex-app2            failstop-app:wslimit          Up 4 hours
ex-lb              nginx:1.29                    Up 12 minutes
ex-bot             541d9758c671                  Up 11 minutes
ex-graf            grafana/grafana:13.1.0        Exited (0) 39 minutes ago
ex-prom            prom/prometheus:v3.13.0       Exited (137) 39 minutes ago
ex-web             nginx:1.29                    Exited (255) 6 hours ago
ex-pg              postgres:17-alpine            Up 5 hours
```

## PostgreSQL 복제
```
standby_1|streaming|quorum
standby_2|streaming|quorum
ANY 1 (standby_1, standby_2)
```

## leader_lease 행 (게시 결과)
```
f342b817-17d2-41e0-a4f0-23dfacd0ec50|10|2126-09-08 05:44:54.582573+00
```

## DynamoDB 리스 항목
```
{"leaseUntil": 1788847050177, "clusterId": "exchange-local", "fencingToken": 10, "leaderId": "f342b817-17d2-41e0-a4f0-23dfacd0ec50", "updatedAt": 1788847040177}
```

## 되돌리기 절차 실증 (2026-09-08T06:00Z)

dynamodb 에서 postgres 로 되돌리는 절차를 실제로 밟았다. 절차가 문서에만 있으면 되돌릴 수
없는 결정이 된다.

```
1. 앱 정지
2. SELECT expire_leader_lease_now()
   전: f342b817-...|10|2126-09-08 05:44:54.582573+00
   후: f342b817-...|10|2026-09-08 06:00:07.677343+00
3. ALTER SYSTEM SET synchronous_standby_names = ''  후 pg_reload_conf()
4. 앱을 provider 기본값(postgres)으로 기동
   리스 보관소: postgres(leader_lease) + 계측
   리더 승격: epoch 11
   readiness 200
```

2 단계를 빠뜨리면 lease_until 이 2126 년에 머물러 try_acquire_leader 의 만료 조건이 영원히
거짓이고 리더가 뽑히지 않는다.
