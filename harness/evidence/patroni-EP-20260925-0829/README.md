# E-P4b etcd 두 멤버 정지 첫 회차 (2026-09-25 08:29 UTC)

[ep-p4-etcd.ps1](../../scripts/ep-p4-etcd.ps1) `-Variant b` 의 첫 회차다. 판정은 **PASS** 다. E-P4b 의 PASS 조건에는 시간 축이 없어, 같은 시각 호스트에서 이 작업 밖의 Gradle java 프로세스가 CPU 를 크게 쓴 것(2초에 9,734ms 인 표본 포함)은 판정을 바꾸지 않는다. 판정 도구가 무효 조건으로 빼는 것은 시간 축뿐이다.

## 조건

| 항목 | 값 |
|---|---|
| 스택 | 회차 시작에 새로 세움. Patroni 리더 `patroni-db-1`, 동기 Standby `patroni-db-2`, `failsafe_mode` 켬(patroni.yml), 앱 리더 `patroni-api-1`(epoch 1) |
| 주입 | 08:30:25.139 `docker stop patroni-etcd-1 patroni-etcd-2`(셋 가운데 둘), 60.2초 뒤 `docker start` |
| 부하 | 봇 10 OPS, k6 접수 주행(ACK 1,432 줄), DB 수준 ACK 기록기 |
| 호스트 | CPU 표본 27·29·48·16·25%, etcd WAL fsync p99 1ms |

## 축(모두 PASS)

- 쓰기 중단 없음: 주입부터 복귀까지 상태 API 표본 150 건이 모두 ACTIVE. LB 쓰기 표본 70 건이 모두 401(게이트 통과 뒤 인증 거절), 조회 표본 350 건이 모두 200.
- `patroni_failsafe_mode_is_active` 최댓값 1, 경보 `EtcdQuorumLost`·`EtcdMemberDown` 발화.
- Patroni 리더는 `/cluster` 표본 140 건 내내 `patroni-db-1` 이었고 타임라인과 앱 epoch 는 그대로였다.
- 사후 권위 run 위반 0(체크 31), C16 0, 주문 500 0. 구 epoch 쓰기는 epoch 가 바뀌지 않아 해당이 없다.

DB 수준 ACK 기록기의 acked 가 아닌 시도는 0 이었다. etcd 리더 교체 1 은 주입의 결과라 무효 조건 (a)에서 뺀다.

## 파일

`EP4b-20260925-082908.*` 가 판정, 시각, 사실, 관측 표본, 기록기 대조, 해시, lib.ps1 표준 판정 파일이다. 원문 로그는 저장소 밖(`%TEMP%\exchange-patroni-ep\EP4b-20260925-082908`)에 있다. 끝에 스택을 지웠고 이 프로젝트의 컨테이너·볼륨·망 0 을 확인했다.
