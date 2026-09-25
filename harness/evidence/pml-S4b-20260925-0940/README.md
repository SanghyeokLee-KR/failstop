# S4b 관문 폐기용 스택 재주행: 구현 검토 보정 이미지 (2026-09-25 09:40~11:13 KST)

[S4b 설계](../../../docs/reference/architecture/마켓분할_리더_S4b_설계.md) 7절의 실험을 14절 구현 검토 대응(I1·I2)까지 넣은 코드로 다시 돈 기록이다. 앞 주행([pml-S4b-20260925-0456](../pml-S4b-20260925-0456/README.md))의 마지막 이미지(`s4b2ref`)는 I1 보정 전 코드였고, 설계 14-1은 다음 스택 주행을 이 보정을 넣은 이미지로 하라고 적었다. 스택은 compose 프로젝트 `exchange-pml`의 폐기용 스택이고 DB 모드는 모든 실험에서 P2, 파티션은 `matching-engine` 하나다. 이미지는 한 번만 구웠다.

절차는 [run-s4b-gate.ps1](run-s4b-gate.ps1)(앞 주행 실행기를 복사해 굽는 참조 태그와 로그 폴더만 바꿨다), 단계 순서와 E2 반복 규칙은 [drive.ps1](drive.ps1), 단계마다 호스트 동시 작업 기록은 [step.ps1](step.ps1)이 남겼다. 단계별 시각과 종료 코드는 [commands.log](commands.log)와 [driver.log](driver.log), 보존 볼륨 확인은 [volumes.txt](volumes.txt)·[volumes-start.txt](volumes-start.txt)·[volumes-end.txt](volumes-end.txt), 다른 세션의 Testcontainers 컨테이너 수와 CPU 표본은 [host-concurrency.txt](host-concurrency.txt)다. 단계마다 실행기 출력은 `<하위 폴더>-runner.log`에 있다. [summarize.ps1](summarize.ps1)·[summarize-e1.ps1](summarize-e1.ps1)은 결과 파일에서 아래 표의 값을 뽑는 읽기 전용 보조다.

## 결론

관문을 통과했다. E2는 다섯 번 연속 PASS이고, E1은 다섯 번 모두 PASS다. E1의 정산 재시도 소진과 라이터 치명은 이번에는 재현되지 않았다. X1(P2 봇 300 OPS 10분)과 X6 a~d, S4 관문 재확인(E4 합류 포함·E5·E6·E7·COLD)도 모두 PASS다. B1은 세 번 흔들기가 모두 리더를 유지해 측정됐고 안전 축이 참이다. 어느 실험에서도 자산·원장 불변식 위반, 구 epoch 쓰기, 주문 `500`(LB 로그, Prometheus, 노드 누계 세 원천), `recovery_runs` 불일치, '처리되지 않은 예외'·'인증 처리 실패로 500' 로그는 나오지 않았다. X7은 선택 실험이고 호스트가 조용하지 않아 NOT RUN이다.

| 실험 | 판정 | 요지 |
|---|---|---|
| Y1 X1 P2 봇 부하 10분 | PASS | 판정 축 25개 참. P2 epoch NEW = 예약 21,441(뒤이은 X6 포함), 체결 15,522, 리더 epoch 6 유지, 연결 최대 38, LB 접근 로그 34,848줄에 주문 `500` 0, Prometheus·노드 누계 0, 접수 불가 계수 증가 0, `recovery_runs` 성공 6·불일치 0 |
| Y1 X6 a~d | PASS | a L `429` 30(55P03), M 최대 1,031ms. b M `201` 34(최대 2,019ms)·`429` 16(market 15, node 1)·5xx 0. c 같은 키 동시 요청 20건 모두 `409 order_in_progress`, R 재전송 `200` 6·`409` 4·`503` 0. d 66회 `429`. 네 변형 모두 LB 0, 리더 강등 0, 정산 소진 0 |
| Y6 B1 흔들림 500ms | 측정 3/3, 안전 축 참 | 세 번 모두 holder·epoch 유지. 리더 `503`은 회마다 3·1·2건, LB `502` 105·80·54줄 약 2초 |
| Y2 E2 DB 절단 1~5회 | PASS 5/5 | RTO 5.868·11.256·12.196·5.814·6.583초, epoch 5 -> 6, rpo_records 0 |
| Y3 E1 PG 지연 1~5회 | PASS 5/5 | 정산 재시도(55P03) 3·6·3·4·6회(셋째·다섯째는 57014 1회 더), 소진 0, 두 층 강등 0, 획득 `lock_timeout` 0, epoch 5 유지 |
| Y4 E4 리더 kill과 합류 | PASS | RTO 5.143초. 되살린 옛 리더가 12.6초 만에 `FOLLOWER`로 합류, 승격 문구 0 |
| Y4 E5 네트워크 분리 | PASS | RTO 5.963초. 옛 리더가 갱신 거부로 강등(`LEASE_LOST_WHILE_ACCEPTING`) |
| Y4 E6 부하 급증 중 kill | PASS | RTO 6.372초. 옛 리더 노드 누계 `stopped`, LB 3,729줄·Prometheus·새 리더 누계 0 |
| Y4 E7 리더 pause | PASS | RTO 10.499초. 재개한 옛 리더가 갱신 거부로 강등. 응답을 쓰지 못한 요청 21건은 WARN('클라이언트 연결이 끊겨'), '처리되지 않은 예외' 0줄 |
| Y4 COLD 콜드 재기동 | PASS | 첫 체결 12.225초. 팔로워 기동 뒤 승격(`-S4Boot` 참) |
| Y5 X7 처리량(선택) | NOT RUN | 10:51부터 호스트에서 이 세션 밖의 추론 서버(`llama-server`)가 CPU를 크게 썼다(전체 CPU 표본 최대 82%). 설계 7-2 Y5대로 조용하지 않은 호스트에서는 돌지 않는다 |
| E3 Redis 장애 | N/A | S4b가 그 경로를 바꾸지 않았고 S4 재주행에서 SKIP이 유효 판정이었다(설계 7-2) |

## 조건

| 항목 | 값 |
|---|---|
| 이미지 | `sha256:dcf0e6a2686d5737d2d5fdd919655a2e06b39063f7a55004fa45b2ead1d13d24`, 참조 태그 `exchange-chaos-app:s4b3ref`, 2026-09-25 09:41 KST. 기록은 [images.json](images.json) |
| 원천 | 작업 트리 `C:\dev\exchange-pml`(브랜치 `claude/per-market-leader`, HEAD `48629d5d`)의 커밋하지 않은 S4b 변경. 추적 파일 diff SHA-256 `e0a2a7ff…`, 변경 경로 52. 앞 주행의 둘째 이미지(`71e096d8…`, 50)와의 차이는 설계 14절 I1(끊긴 V47 판정을 다시 부를 때 키를 지우지 않는다)·I2(시험만)와 문서, 하네스 판정 보정이다 |
| jar | SHA-256 `39aabcbd…`, 2026-09-25 09:15:32. 설계 14-1의 마지막 서버 전체 빌드(09:15~09:33)가 만든 bootJar이고, 마지막 운영 코드 변경(`EngineRecovery.java` 09:12)보다 뒤다. Prep의 `:app:bootJar`는 최신 판정으로 이 산출물을 그대로 썼다 |
| 마이그레이션 | V0~V53(`flyway_schema_history` 최대 53). 새 마이그레이션은 없다 |
| 앞 이미지 | `s4b2ref`(`e7baeb2f…`), `s4bref`, `s4r1ref`, `s3ref`, `:local`(`611169b8bf40`)은 그대로다 |
| 프로필 | `dev,chaos-monkey,prod-timeouts`. X1은 앱 역할 로그인(`pml_app`·`pml_bot`) |
| 호스트 | Windows 11, Docker Desktop, 논리 CPU 16. 이 세션은 실험 동안 Gradle과 Testcontainers를 돌리지 않았고, 단계 앞뒤 Testcontainers 컨테이너 수는 모두 0이다. 실험 앞뒤 표본에서 도커·앱·부하 도구 밖의 바쁜 프로세스는 X1·B1·E2·E1 첫째·둘째에서 0, E1 둘째 뒤부터 regress까지 1개였다. 10:51부터 다른 작업의 `llama-server`가 돌았다(E1 다섯째 끝과 regress 전체). 시간 축에서만 FAIL한 실험이 없어 7-3의 잡음 재실행은 하지 않았다 |
| 보존 자원 | `exchange-chaos_chaos-pgdata`(생성 2026-09-13T11:42:01Z), `failstop-keep-chaos-pgdata-20260924`(2026-09-23T15:47:36Z). 시작·끝과 단계마다 앞뒤 생성 시각이 같았다 |

실행기는 셸 변수 둘(`CHAOS_PROJECT`, `COMPOSE_PROJECT_NAME`), `lib.ps1`의 프로젝트, compose 해석값이 모두 `exchange-pml`일 때만 시작한다. `harness/scripts/lib.ps1`·`up.ps1`·`down.ps1`은 이미 `CHAOS_PROJECT`와 셸의 `CHAOS_APP_IMAGE`를 따르므로 고치지 않았다. 시작할 때 `chaos-*` 컨테이너와 `exchange-chaos` 프로젝트 컨테이너는 없었다. `exchange-chaos` 프로젝트는 내리거나 올리지 않았고, `down -v`는 모두 `exchange-pml` 프로젝트에만 했다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/k6_keys.json`)은 경로로만 넘겼고, X1의 런타임 로그인 비밀번호는 실행마다 무작위로 만들어 셸 변수로만 넘겼다.

```powershell
$ev = 'C:\dev\exchange-pml\harness\evidence\pml-S4b-20260925-0940'
pwsh -NoProfile -File "$ev\step.ps1" -Label Prep -Phase Prep
pwsh -NoProfile -File "$ev\drive.ps1" -From X1
# drive.ps1 순서: X1 -WithX6 -> B1(N/A면 100ms로 B1-r2) -> E2-r1..r5(Chaos, 멈춤 규칙) -> E1-r1..r5(X8) -> regress(E4,E5,E6,E7,COLD) -> Final
```

## E2 (다섯 번 PASS)

| 회 | RTO(초) | 옛 리더 강등 사유 | 옛 리더 접수 `503`(원인) | 재구성 실패 | '재생이 판정 없이 끝났다' | 창 안 LB `502`·`503`·`500` |
|---|---:|---|---|---:|---:|---|
| 1 | 5.868 | `LEASE_LOST_WHILE_ACCEPTING`(접수 append의 fencing 거부) | 1(`fencing`) | 0 | 0 | 15·1·0 |
| 2 | 11.256 | `WRITER_FATAL`(`TransactionSystemException sqlstate=08006(앱 예외)`) | 0 | 5 | 0 | 69·1·0 |
| 3 | 12.196 | `WRITER_FATAL`(`TransactionSystemException sqlstate=08006(앱 예외)`) | 0 | 5 | 0 | 116·7·0 |
| 4 | 5.814 | `LEASE_LOST_WHILE_ACCEPTING`(접수 append의 fencing 거부) | 1(`fencing`) | 0 | 0 | 24·3·0 |
| 5 | 6.583 | `LEASE_LOST_WHILE_ACCEPTING`(갱신 거부) | 0 | 0 | 0 | 19·4·0 |

모든 회차가 스크립트 판정(RTO < 30초, epoch 5 -> 6과 holder 교체, 구 epoch 쓰기 증분 0, rpo_records 0, 사후 CRITICAL 0, 승격 안정성), 공통 판정(세 원천 `orders_500_0`, `recovery_runs` 불일치 0과 성공 양성 축), 파티션 축에서 참이었다. `504`는 모든 회차에서 0이다. 첫째·넷째는 DB가 돌아온 순간 옛 리더 접수 append가 fencing 거부(P0001)를 받아 `503`(`fencing|503`)으로 끝났다(설계 2-2의 (다) 갈래). 둘째·셋째는 절단 중 라이터가 롤백 실패로 치명이 된 뒤 재구성이 스냅샷 적재에서 획득 실패로 다섯 번 끝났고, DB가 돌아온 뒤 `FOLLOWER`로 돌아왔다. 그 다섯 번은 `recovery_runs`에 불일치 행을 남기지 않았다. 재생 도중 DB가 끊겨 '재생이 판정 없이 끝났다'를 남기는 원인 A 분기는 다섯 번 모두 타지 않았다. 설계 7-2 Y2의 규칙대로 셋째에서 멈추지 않고 넷째·다섯째를 돌았으며 모두 PASS라 연속 다섯이다. 그 분기는 앞 주행과 같이 시험으로만 확인한 상태다. I1이 바꾼 경로(`connection|409`, `connection|429`)는 다섯 번 모두 증가 0이었다.

## E1 (다섯 번 PASS)

| 회 | 판정 | 정산 재시도(55P03·57014) | 소진 | 주입 p99(ms) | 해제 뒤 회복(초) | 접수 경합 `429` | 바쁜 다른 프로세스(앞·뒤) |
|---|---|---|---:|---:|---:|---:|---|
| 1 | PASS | 3·0 | 0 | 2,222.5 | 2.22 | 12 | 0·0 |
| 2 | PASS | 6·0 | 0 | 2,137.9 | 4.24 | 13 | 0·1 |
| 3 | PASS | 3·1 | 0 | 2,135.4 | 2.15 | 17 | 1·1 |
| 4 | PASS | 4·0 | 0 | 2,239.5 | 2.15 | 14 | 1·1 |
| 5 | PASS | 6·1 | 0 | 2,063.8 | 2.23 | 18 | 1·1 |

판정은 스크립트 1차 판정(무결성, epoch 불변, 자발적 강등 증가 0, 주입 중 status·readiness 유지, 체결 정지 0, 해제 뒤 회복, 리스 표본 양성 축), 두 층 강등 0, 리더의 획득 `lock_timeout` 0, 세 원천 `orders_500_0`, 두 api 로그의 '처리되지 않은 예외' 0줄이다. 다섯 번 모두 epoch 5가 유지됐고 정산 소진은 0이다. 앞 주행의 F1 한도(시도 8회, 첫 시도부터 15초)로 재시도가 모두 흡수됐다. 다섯 번 가운데 넷(첫째·둘째·넷째·다섯째)은 주입 전·중·후 권위 run 가운데 한두 번에서 C13A 1~3행을 ERROR 로그로 남겼다. C13A는 append와 정산 커밋 사이의 비동기 물질화 창이라 드레인 전 판정에서 빼는 체크이고(`harness/scripts/lib.ps1`의 게이팅 면제 목록), 게이팅 판정은 모두 참이었다. 앞 주행에서도 같은 양상이었다. E1의 재현이 없었으므로 F1·F2 보정과 다시 굽기는 하지 않았다.

## B1 DB 흔들림 (기록 실험)

새 스택(P2, 봇 300 OPS, 2분 예열)에서 리더(api-1)의 pg 프록시 `pg-api1`에 toxiproxy `reset_peer`를 500ms 걸었다 지우기를 60초 간격으로 세 번 했다([B1/result.json](B1/result.json)). 세 번 모두 holder·epoch(5)가 그대로라 측정 회차이고, 앞 주행과 달리 라이터 치명이 한 번도 나지 않아 100ms 재주행은 하지 않았다. 안전 축(불변식 위반 0, 세 원천 `orders_500_0`, '처리되지 않은 예외'·'인증 처리 실패로 500' 0줄, 드레인)은 참이다.

| 회 | 리더 `503`(원천) | 팔로워 `503`(POST·DELETE) | LB `502`·`503`·`500` | `no live upstreams` | 5xx 창(KST) |
|---|---|---|---|---:|---|
| 1 | 인증 필터 2(uri `UNKNOWN`), 체결 조회 1 | 98·9 | 105·2·0 | 105 | 10:04:55~57 |
| 2 | 인증 필터 1(uri `UNKNOWN`) | 70·11 | 80·1·0 | 80 | 10:05:56~58 |
| 3 | 단건 취소 1(`/api/v1/orders/{id}`), 체결 조회 1 | 48·6 | 54·0·0 | 54 | 10:06:57~59 |

접수 불가 계수(여섯 조합), 미해결, 커밋 회수, 접수 경합 계수는 세 번 모두 증가 0이다. 곧 V47 판정 실패는 0이다. 셋째의 단건 취소 `503`은 설계 11절 R7이 적은 경로(단건 취소의 DB 연결 장애는 늘 `503`)가 실제로 한 건 난 것이다. 리더 `503` 한두 건 뒤 LB `502`가 약 2초 이어진 것은 앞 주행의 측정 세 번과 같은 크기다. 이 수는 설계 10절 1·3의 사용자 확인 근거에 더한다.

## S4 관문 재확인 (E4·E5·E6·E7·COLD)

한 번씩 돌았다([regress](regress/summary.json), [regress-s4b.json](regress-s4b.json)). 판정은 S4 설계 7-2의 X2~X5와 같고 공통 판정의 S4b 축을 더했다.

| 실험 | RTO(초) | 요지 | 창 안 LB `502`·`503`·`504`·`500` |
|---|---:|---|---|
| E4 + 합류 | 5.143 | 되살린 옛 리더가 12.6초 만에 `FOLLOWER`로 합류, 승격 문구 0 | 133·0·0·0 |
| E5 | 5.963 | 옛 리더가 갱신 거부로 강등했다. 라이터 정지 1줄은 강등 13.6초 뒤 `NotLeaderException`으로 난 것이다 | 73·0·0·0 |
| E6 | 6.372 | 옛 리더 `stopped`, LB 3,729줄·Prometheus·새 리더 누계 0 | 171·0·0·0 |
| E7 | 10.499 | 재개한 옛 리더가 갱신 거부로 강등. 응답을 쓰지 못한 요청 21건은 WARN, '처리되지 않은 예외' 0줄 | 0·0·0·0 |
| COLD | 12.225(첫 체결) | 팔로워 기동 뒤 승격, 나중 노드 `FOLLOWER` | 69·0·4·0 |

`recovery_runs` 불일치는 다섯 실험 모두 0이고 접수 불가 계수 증가도 0이다. regress 동안 호스트에 다른 작업(`llama-server`)이 돌았지만 FAIL이 없어 잡음 규칙은 적용할 일이 없었다.

## 끝 상태

Final 단계(11:12)에서 `-p exchange-pml` 스택을 `down -v --remove-orphans`로 지웠다. 남은 `chaos-*` 컨테이너와 `exchange-pml` 볼륨은 없다([docker-ps-after.txt](docker-ps-after.txt), [volume-ls-end.txt](volume-ls-end.txt)는 빈 파일). `:pml`은 이 주행의 이미지(`dcf0e6a2…`, `s4b3ref`)를 가리킨다. 컨테이너 로그 원문은 저장소 밖 `%TEMP%\pml-s4b3-logs`의 하위 폴더(`B1`, `E2-r1`~`E2-r5`, `E1-r1-E1`~`E1-r5-E1`, `regress`)에 있고 해시는 각 결과 파일의 `containerLogs`에 있다. X1은 로그 원문을 남기지 않고 문구 수만 결과 파일에 남겼다.

## 한계

- 로컬 Docker Desktop 단일 호스트의 결과이고 AWS의 장애나 처리량을 대신하지 않는다. DB 모드는 P2이고 파티션은 하나다.
- 원인 A 가운데 재생 도중 DB가 끊기는 분기는 이번에도 스택에서 타지 않았다(E2 다섯 번). I1이 바꾼 경로(끊긴 V47 판정을 다시 불러 판정이 난 경우)도 이 주행에서 나지 않았다. 두 경로는 시험과 변이로만 확인한 상태다.
- E1의 정산 소진은 이번 다섯 번에서 재현되지 않았다. F1 한도가 흡수했다는 직접 증거는 앞 주행의 다시 구운 이미지 첫째 E1이다. F2 경로(재확인이 참인 fencing 거부)도 나오지 않았다.
- B1은 기록 실험이라 판정 기준값이 없다. 이번 세 번은 모두 리더를 유지했지만, 앞 주행에서는 여섯 번 가운데 셋이 라이터 치명(약 7~9초 쓰기 `502`)이었다. 치명 여부는 흔들기 순간 라이터가 연결을 쓰고 있었는지에 달린다.
- E1 둘째 뒤부터 regress까지 호스트에 다른 작업이 돌았다. 시간 축에서 FAIL한 실험은 없었다.
- X7은 NOT RUN이다. 앞 주행의 X7 기록(잡음 0.49, 손실 판단 불가)이 마지막 값이고 S7 전에 조용한 호스트에서 다시 잰다.
- 실행기 변경, B1 단계, F1 값, `503` 응답 계약은 AI가 정했고 사용자 확인 대상이다. 코드와 증거는 커밋하지 않았다. `docs/measurements/`와 설계 문서는 고치지 않았다(문서 단계).
