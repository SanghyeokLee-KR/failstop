# 실험 환경과 상태 (2026-09-08)

## 컨테이너
```
NAMES              IMAGE                         STATUS
ex-app2            failstop-app:lease            Up 6 minutes
ex-app             failstop-app:lease            Up 3 minutes
ex-pg3             postgres:17-alpine            Up 15 minutes
ex-pg2             postgres:17-alpine            Up 15 minutes
ex-dynamodb        amazon/dynamodb-local:2.5.2   Up 2 hours
ex-app3            failstop-app:wslimit          Exited (143) 5 hours ago
ex-lb              nginx:1.29                    Up 6 minutes
ex-bot             541d9758c671                  Up 5 minutes
ex-graf            grafana/grafana:13.1.0        Exited (0) 3 hours ago
ex-prom            prom/prometheus:v3.13.0       Exited (137) 3 hours ago
ex-web             nginx:1.29                    Exited (255) 8 hours ago
ex-pg              postgres:17-alpine            Up 7 hours
```

## 실험 종료 시점
```
app-1 readiness 503  (팔로워)
app-2 readiness 200  (리더)
exchange_follower_catchup_applied_total 5405.0
exchange_follower_catchup_lag_commands 0.0
exchange_follower_catchup_last_tick_seconds 0.0
exchange_follower_catchup_state 1.0
leaderId=70b85af9-561d-400a-898e-89047bb05a95 fencingToken=28 leaseUntil=1788854252324 updatedAt=1788854242324
PG epoch=28
standby_1 streaming quorum
standby_2 streaming quorum
```

## 엔진 상태 규모
```
OPEN 주문 221410
command_journal 977276
event_journal 1590127
```
