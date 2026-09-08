# 실험 중 컨테이너 상태 (2026-09-08)

## PHASE 2 회차 중 스냅샷 (2026-09-08T04:05:51Z)
```
NAMES              IMAGE                     STATUS
ex-k6-p2b          grafana/k6:1.4.0          Up 2 minutes
ex-pg3             postgres:17-alpine        Up 3 minutes
ex-pg2             postgres:17-alpine        Exited (137) 46 seconds ago
ex-app3            failstop-app:wslimit      Exited (143) 51 minutes ago
ex-app2            failstop-app:wslimit      Up 3 hours
ex-lb              nginx:1.29                Up 3 hours
ex-app             11d781ba869e              Up 8 minutes
ex-bot             541d9758c671              Up 2 minutes
ex-graf            grafana/grafana:13.1.0    Up 2 hours
ex-prom            prom/prometheus:v3.13.0   Up 2 hours
ex-web             nginx:1.29                Exited (255) 4 hours ago
ex-pg              postgres:17-alpine        Up 3 hours
```

## 볼륨
```
ex-pg-clean
ex-pg-sb1
ex-pg-sb2
```

## 실험 종료 후 스냅샷 (2026-09-08T04:33:58Z)

standby 컨테이너와 볼륨을 제거하고 슬롯을 떨궜다. 비활성 슬롯을 남기면 primary 가
WAL 을 무한히 붙들어 디스크를 채운다.

```
NAMES              IMAGE                     STATUS
ex-app             failstop-app:wslimit      Up 7 minutes
ex-app3            failstop-app:wslimit      Exited (143) About an hour ago
ex-app2            failstop-app:wslimit      Up 3 hours
ex-lb              nginx:1.29                Up 7 minutes
ex-bot             541d9758c671              Up 30 minutes
ex-graf            grafana/grafana:13.1.0    Up 2 hours
ex-prom            prom/prometheus:v3.13.0   Up 2 hours
ex-web             nginx:1.29                Exited (255) 5 hours ago
ex-pg              postgres:17-alpine        Up 4 hours
```

복제 슬롯 0건.
복제 세션 0건.
`synchronous_standby_names` 빈값. readiness 200.
