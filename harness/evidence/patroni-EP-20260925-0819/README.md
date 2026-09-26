# E-P7 한 노드 경로 지연 첫 회차 (2026-09-25 08:19 UTC)

[ep-p7-slow-path.ps1](../../scripts/ep-p7-slow-path.ps1)의 첫 회차다. 판정 도구는 **FAIL** 을 냈지만 그 원인은 실행기 결함이다. 비공허 축(`exchange_write_probe_unconfirmed_total` 증가)이 0 으로 읽혔는데, 그 계수는 태그가 없어 `/actuator/prometheus` 에 중괄호 없이 찍히고 실행기의 정규식은 중괄호를 요구했다. api-1 의 로그에는 주입 2.4초 뒤(08:21:27.224) 확인 안 된 느림 경고(`쓰기 가능 탐지가 2000ms 넘게 끝나지 않았으나 DB 쪽 확인이 없어 ACTIVE 로 둔다`)가 있고, 그 경고는 계수를 올린 직후에 찍힌다. 이 회차 뒤 파싱을 고치고([ep-lib.ps1](../../scripts/ep-lib.ps1) `ConvertFrom-EpMetricsText`, 시험 [ep-lib.Tests.ps1](../../scripts/tests/ep-lib.Tests.ps1)) 다시 돌렸다. 같은 결함이 앞선 회차의 계수에 준 영향은 아래에 적었다.

## 조건

| 항목 | 값 |
|---|---|
| 스택 | 회차 시작에 새로 세움(이 회차에서 bootJar 와 앱 이미지를 새로 만듦). Patroni 리더 `patroni-db-1`, 동기 Standby `patroni-db-2`, 앱 리더 `patroni-api-1`(epoch 1) |
| 주입 | 08:21:24.789 toxiproxy `pg-api1` 에 양방향 지연 3000ms, 60.2초 뒤 제거 |
| 호스트 | CPU 표본 27·33·31·24·19%, etcd 리더 교체 0, 실험 밖 부하 도구 java-gradle 하나(2초에 CPU 203ms) |

## 관찰

- api-2 가 주입 5.6초 뒤 epoch 2 로 승격했고(실행기 관찰 08:21:30.400) 첫 체결은 5.32초였다. api-1 은 경로가 풀린 직후(08:22:25.407) 리스 갱신이 거부돼 파티션 강등 1회였다.
- api-1 은 2.4초에 확인 안 된 느림(ACTIVE 유지)을 적었고, 7.4초에 확인 연결이 두 번 실패해 `database_unavailable`(UNREACHABLE)이 됐다. 확인 연결의 소켓 제한이 2초인데 경로 왕복이 6초라 확인 질의가 끝나지 않았다. `replication_unavailable`(SUSPENDED) 표본은 0 이고 게이트 423 도 0 이었다. api-1 이 받은 쓰기는 503 으로 거절됐다(17 건).
- api-2 는 끝까지 ACTIVE 였다. 사후 권위 run 위반 0, C16 0, 구 epoch 쓰기 증분 0, 주문 500 0.
- LB 쓰기 표본은 401 67·429 3, 조회 표본은 200 220·시간 초과 20 이었다. 시간 초과는 느린 경로의 api-1 로 간 조회로 보이며 표본에는 어느 노드가 답했는지가 없다.

## 같은 파싱 결함의 영향

태그 없는 계수는 `exchange_write_probe_unconfirmed_total`, 노드 단위 `exchange_leader_standdown_total`, `exchange_order_intake_commit_recovered_total`, `exchange_order_intake_unresolved_total` 이다. 앞선 E-P2a([patroni-EP-20260925-0732](../patroni-EP-20260925-0732/README.md))의 강등 축은 노드 단위 자발적 강등을 읽지 못했을 수 있다. 그 회차 두 앱의 로그에는 강등·포기 줄이 없어 축 결과는 바뀌지 않는다. E-P1 두 회차에는 이 계수를 쓰는 축이 없다.

## 파일

`EP7-20260925-081948.*` 가 판정, 시각, 사실, 관측 표본, 기록기 대조, 해시, lib.ps1 표준 판정 파일이다. 원문 로그는 저장소 밖(`%TEMP%\exchange-patroni-ep\EP7-20260925-081948`)에 있다. 끝에 스택을 지웠고 이 프로젝트의 컨테이너·볼륨·망 0 을 확인했다.
