# 실험 환경과 상태 (2026-09-08)

## 컨테이너
```
NAMES                                                      IMAGE                         STATUS
ex-pg3                                                     postgres:17-alpine            Up 2 minutes
ex-pg2                                                     postgres:17-alpine            Up 2 minutes
ex-app2                                                    failstop-app:lease            Exited (137) 3 minutes ago
ex-app                                                     failstop-app:lease            Up 6 minutes
ex-dynamodb                                                amazon/dynamodb-local:2.5.2   Up About an hour
ex-app3                                                    failstop-app:wslimit          Exited (143) 4 hours ago
ex-lb                                                      nginx:1.29                    Up 12 minutes
ex-bot                                                     541d9758c671                  Exited (143) 36 seconds ago
ex-graf                                                    grafana/grafana:13.1.0        Exited (0) 2 hours ago
ex-prom                                                    prom/prometheus:v3.13.0       Exited (137) 2 hours ago
ex-web                                                     nginx:1.29                    Exited (255) 7 hours ago
ex-pg                                                      postgres:17-alpine            Up 6 hours
```

## 실험 종료 시점 리더십
```
app-1 readiness 200
app-2 readiness 000
leaderId=f2c002d1-af54-417a-a4af-d4fb0d2e5788 fencingToken=22 leaseUntil=1788850744932 updatedAt=1788850734932
PG holder=f2c002d1-af54-417a-a4af-d4fb0d2e5788 epoch=22
standby_1 streaming quorum
standby_2 streaming quorum
```

## 엔진 상태 규모 (승격 재생 비용의 축)
```
OPEN 주문 207950
command_journal 954073
event_journal 1559359
```

## 하네스 오라클 (수정 후)
```
:8080 leaderAlive=True holder=f2c002d1 epoch=22 leaseUntil=2026-09-08T06:59:07.939Z
:8081 응답 없음
```
