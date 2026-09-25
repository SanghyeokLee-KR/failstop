# S5 관문 폐기용 스택 둘째 주행: REST 라우팅과 요청 경계 (2026-09-25 17:54~18:57 KST)

[S5 설계](../../../docs/reference/architecture/마켓분할_리더_S5_설계.md) 7절의 실험을 S5 코드(커밋 전)로 한 번 더 돈 기록이다. 첫 주행은 [pml-S5-20260925-1515](../pml-S5-20260925-1515/README.md)이고 Z1이 판정식 경계(한 줄 차이)로, 판정식을 고쳐 다시 돈 Z1r이 k6 WebSocket orderbook 결번 4건으로 FAIL이었다. 그 뒤 구현 검토 대응(설계 12-6)이 시험만 더하고 운영 코드를 바꾸지 않았으므로, 같은 이미지로 설계 7-2의 실험 전체를 새 스택에서 다시 돌리고 결번의 S5 이전 기준선(Z1base)을 더했다. 스택은 compose 프로젝트 `exchange-pml`, DB 모드는 모든 실험에서 P2, 리스 자원은 `matching-engine` 하나다.

절차는 [run-s5-gate.ps1](run-s5-gate.ps1)(첫 주행 실행기를 복사해 세 곳을 고쳤다. 아래 '실행기 변경')과 단계 순서를 잇는 [run-all.ps1](run-all.ps1)이다. 직접 호출과 LB 로그 분해는 [harness/scale/s5_route_probe.py](../../scale/s5_route_probe.py)가 한다. 단계별 시각과 종료 코드는 [commands.log](commands.log)와 [run-all.log](run-all.log), 단계마다 실행기 출력은 `<단계>-runner.log`, 보존 볼륨 확인은 [volumes.txt](volumes.txt)·[volume-ls-start.txt](volume-ls-start.txt)·[volume-ls-end.txt](volume-ls-end.txt)다.

## 결론

설계 7-2의 판정 실험은 모두 PASS다. Z0·Z1·Z1d·Z2·Z3·Z4·Z5(E4·E7·E2)가 PASS이고, 기록 실험 Z6은 첫 주행과 같이 셋째 회가 라이터 치명으로 epoch가 바뀌어 N/A(측정 2/3)이며 안전 축은 참이다. Z1은 이번 주행 전에 정한 괄호 판정식과 설계 7-2 본문의 '같음' 판정식 모두에서 참이었다(팔로워 `not_owner` 증가 162, LB 창의 첫 시도 팔로워 `503` 162, 괄호 162~163). k6 네 번은 모두 임계를 통과했고 WebSocket orderbook 결번은 0이다. 설계 밖 기준선 Z1base(S4b 보정 이미지, 기본 LB, 같은 봇·k6 조건)도 k6 네 번 모두 통과하고 결번 0이었다. 첫 주행 Z1r의 결번 4건은 이번 여덟 번의 k6 주행(S5 넷, S4b 넷)에서 다시 나지 않았고 원인은 여전히 모른다. 어느 실험에서도 자산·원장 불변식 위반, 구 epoch 쓰기, 주문 `500`(LB 로그, Prometheus, 노드 누계 세 원천), `recovery_runs` 불일치, '처리되지 않은 예외'·'인증 처리 실패로 500' 로그는 나오지 않았다.

| 실험 | 판정 | 요지 |
|---|---|---|
| Z0 E4 기본 LB | PASS | RTO 4.628초(첫 주행 5.591, S4b 재주행 5.143), epoch 5 -> 6, 옛 리더가 10.6초 만에 `FOLLOWER`로 합류(승격 문구 0). 창 안 LB `502` 143·`503` 0(첫 주행 139·0, S4b 133·0). 'P2 E4 결과 불변' 칸이다 |
| Z1 라우팅 분할 | PASS | 라우팅 축 모두 참. 두 판정식 모두 참(아래). k6 네 번 통과, orderbook 결번 0 |
| Z1base 결번 기준선(설계 밖) | 기록 | S4b 이미지 `dcf0e6a2…`, 기본 LB. k6 네 번 통과, orderbook 결번 0·역순 폐기 0. 안전 축 모두 참 |
| Z1d 힌트 맵과 기본 업스트림(설계 밖 보충) | PASS | 봇을 멈추고 LB로 여섯 요청. 도구 축 여덟 개 모두 참(힌트 있는 ETH 생성은 팔로워 `503` 뒤 리더 `201`, 힌트 없는 ETH 생성·맵에 없는 힌트·힌트 없는 일괄 취소는 종전 쓰기 경로의 1차 한 번, 힌트 있는 BTC 생성은 `exchange_p0` 1차 한 번, 힌트를 실은 GET은 힌트로 가르지 않음, 팔로워 `not_owner` +1) |
| Z2 선검사 무부작용 | PASS | 팔로워와 `chaos-api-3`에 다섯 모양 모두 `503`·`Retry-After: 1`·`X-Exchange-Route: not-owner`·레이트리밋 헤더 없음, 키·저널·예약 0, 두 노드 `not_owner` +5씩, 같은 키를 리더에 보내 `201`과 NEW 1행 |
| Z3 힌트·배치 거절 | PASS | 여섯 사례 모두 기대한 `400`, 리더 거절 계수 mismatch 3·duplicate 2·batch 1, 키·NEW 0, 힌트가 틀린 취소 뒤 주문은 `OPEN` |
| Z4 인계 | PASS | 도구 축 스물여섯 개 모두 참. 아래 표 |
| Z5 E4 파티션 LB | PASS | RTO 5.332초, 합류 10.6초, 창 안 LB `502` 101·`503` 2·`504` 1 |
| Z5 E7 파티션 LB | PASS | RTO 10.574초, 창 안 LB 5xx 0, 가장 긴 요청 10.003초 |
| Z5 E2 파티션 LB | PASS | RTO 11.578초(S4b 다섯 번 5.8~12.2초), 창 안 LB `502` 69·`503` 9. 옛 리더의 인증 키 조회 한 번 더 읽기 `recovered` 3 |
| Z6 B1 재기록 | N/A(측정 2/3) | 측정 두 회의 LB `502` 45·1줄(첫 주행 0·1, S4b 105·80·54). 셋째 회는 라이터 치명(`WRITER_FATAL`)으로 epoch 5 -> 6이라 N/A, 그 회의 LB `502` 146줄 약 6초. 안전 축은 참 |

## 조건

| 항목 | 값 |
|---|---|
| 이미지 | `sha256:1696fdb04f3d047985070b319892c836a300239682c6bfe8b52c48022b512095`(첫 주행과 같은 id, 참조 태그 `exchange-chaos-app:s5ref`). 기록은 [images.json](images.json) |
| 다시 굽기 | Prep이 `up.ps1 -Obs`로 다시 구웠다. `:app:bootJar`는 최신 판정이라 JAR(`d57b3790…`, 14:50:38)이 그대로였고, 새로 나온 이미지(첫 시도 `f84ad774…`, 둘째 `78d0b123…`)는 층(RootFS) 아홉 개와 생성 시각이 첫 주행 이미지와 같았다. id만 다른 것은 이 호스트의 이미지 저장소가 매니페스트 요약을 새로 만든 것으로 보인다(추정). 두 주행이 같은 id를 쓰도록 `:pml`을 `1696fdb0…`로 되돌렸고 새 참조 태그(`s5r2ref`)는 만들지 않았다 |
| 원천 | 작업 트리 `C:\dev\exchange-pml`(브랜치 `claude/per-market-leader`, HEAD `9a6d2474`)의 커밋하지 않은 S5 변경. 추적 파일 diff SHA-256 `0daa6d97…`(첫 주행 `06197bab…`와 다른 것은 구현 검토 대응의 시험·문서 변경 때문이다). 운영 코드 두 파일(`OrderController.java`, `OrderCancelHandoff.java`)의 수정 시각이 이미지보다 뒤지만, 변이 확인 뒤 되돌린 것이고 bootJar 최신 판정과 같은 층이 내용이 같음을 보인다 |
| 마이그레이션 | V0~V54(Prep의 `flyway_schema_history` 최대 54, 실패 0) |
| 프로필 | `dev,chaos-monkey,prod-timeouts`, 소유자 로그인 |
| LB | Z0과 Z1base는 `harness/nginx/nginx.conf`, 나머지는 `harness/nginx/nginx.partition.conf`(`CHAOS_LB_CONF`를 실행기 프로세스에만 줌). 스택을 세운 단계마다 `nginx -t` 성공과 컨테이너 안 설정 파일 sha256이 저장소 파일과 같음을 `lb.json`에 남겼다. Z0·Z5는 실험 스크립트가 스택을 세우므로 저장한 LB 로그 형식으로 확인했다(Z0 `ups=` 0줄, Z5 모든 접근 줄) |
| 호스트 | Windows 11, Docker Desktop, 논리 CPU 16. 이 세션은 실험 동안 Gradle과 Testcontainers를 돌리지 않았다(Prep의 bootJar 최신 판정만). 시작 직전(17:48)에는 다른 작업 트리(`exchange-pml6`, `exchange-patroni`)의 Gradle 빌드와 Testcontainers 컨테이너 60여 개가 돌았고, 단계 앞뒤 표본에서 다른 프로젝트 컨테이너는 최대 17개였다. 단계 앞뒤 표본의 전체 CPU는 최대 38.1%(다섯 표본 평균 최대 28.2%), 도커·앱·부하 도구 밖의 바쁜 프로세스는 모든 표본에서 0이었다. 시간 축에서만 FAIL한 실험이 없어 설계 7-3의 잡음 재실행은 하지 않았다 |
| 보존 자원 | `exchange-chaos_chaos-pgdata`(생성 2026-09-13T11:42:01Z), `failstop-keep-chaos-pgdata-20260924`(2026-09-23T15:47:36Z). 모든 단계 앞뒤 생성 시각이 같았다 |

실행기는 셸 변수 둘(`CHAOS_PROJECT`, `COMPOSE_PROJECT_NAME`), `lib.ps1`의 프로젝트, compose 해석값이 모두 `exchange-pml`일 때만 시작한다. `harness/scripts/lib.ps1`·`up.ps1`·`down.ps1`는 앞 단계에서 이미 `CHAOS_PROJECT`와 셸의 `CHAOS_APP_IMAGE`를 따르게 되어 있어 고치지 않았다. 시작할 때 `chaos-*` 컨테이너는 없었다. `exchange-chaos` 프로젝트는 내리거나 올리지 않았고 `down -v`는 모두 `-p exchange-pml`에만 했다. 관리 평면은 저장소 루트 `compose.yaml`의 `chaos-admin`을 `docker compose -p exchange-pml -f compose.yaml --env-file harness/.env.chaos --profile admin up -d --no-deps --wait chaos-admin`으로만 띄웠다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/k6_keys.json`)은 경로로만 넘겼다. Z1·Z1base는 k6 주행 전에 부하 계정을 다시 발급해 `k6_keys.json`을 새로 썼고(개수만 확인), Z2~Z4의 도구는 계정을 새로 발급해 키를 메모리에만 두었다.

```powershell
$ev = 'C:\dev\exchange-pml\harness\evidence\pml-S5-20260925-1752'
pwsh -NoProfile -File "$ev\run-s5-gate.ps1" -Phase Prep     # 첫 시도는 포트 59090 충돌로 실패(Prep1-runner.log), 18:05 다시
pwsh -NoProfile -File "$ev\run-all.ps1"                     # 아래 순서를 잇달아 돈다
#   -Phase Chaos -LbConf default -Experiments E4 -SubDir Z0
#   -Phase Z1 -LbConf partition
#   -Phase Z1base -LbConf default                           # 설계 밖 기준선. :pml 을 s4b3ref 로 옮겼다가 되돌린다
#   -Phase Z1d -LbConf partition                            # 설계 밖 보충
#   -Phase Z2 / Z3 / Z4 -LbConf partition
#   -Phase Chaos -LbConf partition -Experiments E4,E7,E2 -SubDir Z5
#   -Phase B1 -LbConf partition                             # 결과 폴더 Z6
#   -Phase Final
```

Prep의 첫 시도(17:54)는 이미지를 구운 뒤 `compose up`에서 호스트 포트 59090(Prometheus)이 이미 쓰이고 있어 실패했다. 몇 분 뒤 그 포트는 비어 있었고 어느 프로세스였는지는 확인하지 못했다. 반쯤 선 `exchange-pml` 스택을 `-p exchange-pml down -v`로 지우고 `:pml`을 첫 주행 id로 되돌린 뒤 다시 돌았다(18:05, 성공).

## 실행기 변경

1. Prep: 지금 `:pml`이 첫 주행 이미지(`s5ref`)인지 보고 다시 구운 뒤, 층과 생성 시각이 같으면 `:pml`을 첫 주행 id로 되돌리고 새 참조 태그를 만들지 않는다. 다르면 `s5r2ref`를 붙이게 했다(이번에는 쓰이지 않았다).
2. Z1: 첫 주행 뒤 고친 괄호 판정식을 이번 주행 전에 정해 `verdict`에 쓰고, 설계 7-2 본문의 '같음' 판정식(창 `t0-1초`~`t1+3초`)도 계산해 `verdictOriginalFormula`로 함께 남긴다. k6 로그에서 `ws_` 계열 값(결번, 역순 폐기, 관측 수)을 주행마다 뽑는다. k6 반복은 Z1base와 같은 함수(`Invoke-K6OrderFlow`)를 쓴다.
3. Z1base: `:pml`을 `-BaseRef`(기본 `s4b3ref`) 이미지로 옮기고 기본 LB로 Z1과 같은 봇 300 OPS·k6 네 번을 돈 뒤, 끝나면(실패해도) `:pml`을 S5 이미지로 되돌린다. 라우팅 축은 없고(S4b 이미지에는 선검사가 없고 기본 LB는 힌트로 가르지 않는다) 안전 축과 k6 WebSocket 수치만 남긴다.

## Z1·Z1base

P2에서 리더는 api-1이라 `exchange_p0`(BTC·XRP 등)은 1차가 리더이고 `exchange_p1`(ETH 등)은 1차가 팔로워다. 봇 300 OPS에 k6 `order_flow.js`(`PROFILE=smoke`, `STEADY_RATE=5`, `SPIKE_RATE=10`, `READ_RATE=5`, `WS_VUS=10`, `MARKETS=KRW-BTC,KRW-ETH,KRW-XRP`, `MARKET_HINT=1`)를 네 번 이어 돌렸다(예열 60초 포함 약 9분). k6 WebSocket은 KRW-BTC의 orderbook과 trade를 30초씩 구독한다. 판정 창의 LB 접근 로그는 [Z1/lbsplit.json](Z1/lbsplit.json)으로 나눴다.

| 축 | Z1(이번) | 첫 주행 Z1 | 첫 주행 Z1r |
|---|---|---|---|
| `exchange_p0` 힌트 주문 쓰기(첫 시도가 모두 리더) | 15,373줄, 팔로워 0 | 15,404줄 | 15,366줄 |
| `exchange_p1` 힌트 주문 쓰기 | 6,675줄, 첫 시도 팔로워 162줄 모두 `503, 2xx`로 리더에서 끝남 | 6,808줄, 163줄 | 6,659줄, 162줄 |
| 두 번 시도 비율(`exchange_p1`) | 2.43% | 2.39% | 2.43% |
| 팔로워 `exchange_routing_precheck_total{result="not_owner"}` 증가 | 162 | 162 | 162 |
| 본문 판정식: LB 창(`t0-1초`~`t1+3초`) 줄 수와 같음 | 162, 참 | 163, 거짓 | 적용 안 함 |
| 괄호 판정식: 안쪽 ≤ 증가 ≤ 바깥쪽 | 162 ≤ 162 ≤ 163, 참 | 적용 안 함 | 162 ≤ 162 ≤ 163, 참 |
| 창 안 `502`·`no live upstreams` | 0·0 | 0·0 | 0·0 |
| 두 노드 `cancel_not_owner`·힌트 거절 증가, 리더 `not_owner` 증가 | 0·0·0 | 0·0·0 | 0·0·0 |
| k6 네 번 | 모두 통과 | 모두 통과 | 둘째만 결번 4 |
| 창 안 체결 | 12,214 | 12,110 | 12,204 |

이번에도 괄호의 바깥쪽이 안쪽보다 한 줄 많았다(경계 초의 줄). 그 줄이 본문 판정식의 창 밖이어서 두 판정식이 같은 답을 냈다. 본문 판정식이 경계 초에 따라 참·거짓이 갈린다는 점은 그대로이고, 괄호 판정식을 확정할지는 여전히 사용자 확인 대상이다.

| k6 WebSocket | Z1(S5 이미지, 파티션 LB) | Z1base(S4b 이미지, 기본 LB) | 첫 주행 Z1r(S5) |
|---|---|---|---|
| orderbook 결번(`ws_seq_gap{phase:steady,dest:orderbook}`) | 0·0·0·0 | 0·0·0·0 | 0·4·0·0 |
| orderbook 역순 폐기 | 0 | 0 | 0 |
| orderbook realtime 관측 | 19,190·19,540·20,130·18,700 | 19,940·18,660·19,180·18,850 | 19,700·18,980·19,680·19,590 |
| trade 관측(합) | 54,550 | 53,920 | 55,500 |
| 재동기화 실패 | 0 | 0 | 0 |

첫 주행의 결번 4건은 역순 폐기 0과 함께 났으므로 순서가 뒤바뀐 것이 아니라 받지 못한 메시지다(`seq <= 앞 값`이면 역순 폐기로 센다). 이번에는 S5·S4b 두 이미지 모두 여덟 번에 한 번도 나지 않아 S5가 만든 것인지는 가르지 못했다. 빈도가 낮은 사건(첫 주행 k6 여덟 번에 한 번)이라 네 번씩으로는 없음을 보일 수 없다. Z1base의 창 안 LB 5xx는 0이다.

## Z4 인계

| 경우 | 응답 | 걸린 시간 | V54 행 | 주문 |
|---|---|---:|---|---|
| (a) 팔로워 마켓 없는 일괄 취소 | `200`, `canceled_count` 4, `failed` 없음 | 351.1ms | `DONE`(`cancel_all`) | 넷 모두 `CANCELED`, 잠긴 KRW 187,800 -> 0 |
| (b) `chaos-api-3` 같은 요청 | `200`, 4, 없음 | 258.5ms | `DONE` | 넷 모두 `CANCELED`, 잠긴 KRW 원복 |
| (c) 관리 평면 회원 폐쇄 | `200`, `canceled_orders` 4 | 352.1ms | `DONE`(`member_close`) | 넷 모두 `CANCELED`, 감사 행 1 |
| (d) 참여를 끄고 폐쇄 | `409 orders_cancel_incomplete`, `pending_reasons={"partition_not_started": 4}` | 5,134.1ms | `ABANDONED` | 새 리더(같은 holder, epoch 5 -> 6)가 선 뒤 10초에도 넷 모두 `OPEN` |
| (d) 새 승인으로 다시 폐쇄 | `200`, 4 | 414.0ms | 새 행 `DONE` | 넷 모두 `CANCELED` |

(c)(d)는 첫 주행과 같이 부하 계정 둘의 `users.kind`를 `HUMAN`으로 바꿔 폐쇄 대상으로 썼다. 리더의 작업자 계수는 점유 4·완료 4, 팔로워와 `chaos-api-3`의 요청자 계수는 `done` 1씩이고, (a)~(c) 동안 리더의 선검사 `not_owner` 증가는 0, 옛 epoch 쓰기 증분은 0이었다.

## Z5 E4·E7·E2 (파티션 LB)

| 실험 | RTO(초) | 창 안 LB `502`·`503`·`504` | 두 번 시도 줄 | 가장 긴 요청(초) | 첫 주행 |
|---|---:|---|---:|---:|---|
| E4 + 합류 | 5.332 | 101·2·1 | 122 | 2.004 | 5.419초, 90·2·1 |
| E7 | 10.574 | 0·0·0 | 36 | 10.003 | 10.906초, 0·0·0 |
| E2 | 11.578 | 69·9·0 | 126 | 10.000 | 6.553초, 24·6·0 |

세 실험 모두 스크립트 판정, 공통 판정, 파티션 축이 참이다. 기록 항목은 이렇다. E4에서 새 리더(api-2)의 승격 전 선검사 `not_owner`는 109였다(설계 11절 R6대로 기록만 한다). E7에서 재개한 옛 리더는 파티션 강등 1줄을 남겼다. E2는 S4b 재주행 다섯 번의 범위(RTO 5.8~12.2초, `502` 15~116, `503` 1~7)와 견주면 RTO와 `502`는 범위 안이고 `503` 9는 범위보다 두 건 많다. DB 절단 동안 옛 리더를 거친 `503`은 LB `rt` 약 3초가 여섯 줄, 6초가 한 줄이었고, 두 줄은 첫 시도가 `proxy_read_timeout`(10초)으로 `504` 뒤 둘째 시도 `503`이었다. 옛 리더의 한 번 더 읽기는 인증 키 조회에서 `recovered` 3·`unreachable` 0, 접수 불가 계수는 `connection|503` 1, 결말 미상 계수(`exchange_order_intake_unresolved_total`)는 1, 노드 수용 거절(`exchange_admission_rejected_total`)은 두 노드 모두 0이었다.

## Z6 B1 재기록

새 스택(P2, 봇 300 OPS, 2분 예열)에서 리더(api-1)의 pg 프록시에 `reset_peer`를 500ms 걸었다 지우기를 60초 간격으로 세 번 했다.

| 회 | 판정 | 리더 `503` | 한 번 더 읽기 | LB `502`·`503`·`no live upstreams` | 5xx 창 |
|---|---|---|---|---|---|
| 1 | 측정 | 인증 단계 2(`UNKNOWN`) | `auth` `unreachable` 2 | 45·2·45 | 09:52:05~07 UTC 약 2초 |
| 2 | 측정 | 인증 단계 2(`UNKNOWN`) | `auth` `unreachable` 2 | 1·1·1 | 09:53:07 한 초 |
| 3 | N/A | 라이터 치명(`WRITER_FATAL`) 뒤 재구성·재승격, epoch 5 -> 6(같은 holder) | - | 146·4·146 | 09:54:08~14 UTC 약 6초 |

첫 회의 `502` 45줄은 모두 `exchange_p0`(BTC·XRP)의 주문 쓰기였고 `ust`가 `503, 502`였다. 리더의 `503` 두 건이 파티션 업스트림 1차의 `max_fails=2`를 채워 1차가 `fail_timeout`(2초) 동안 빠졌고, 그동안 첫 시도가 백업(팔로워, 선검사 `503`)으로 가고 둘째 시도에 살아 있는 업스트림이 없어 `502`가 났다. 첫 주행의 측정 두 회(리더 `503` 두 건씩, `502` 0·1줄)와 달리 이번 첫 회는 두 `503`이 같은 파티션 업스트림에 떨어진 것으로 보인다(추정). 따라서 `max_fails=2`는 리더 `503` 한 건은 흡수하지만 같은 파티션에 두 건이 몰리면 S4b와 같은 약 2초 `502`를 낸다. 셋째 회의 라이터 치명 비용(약 6초 `502`)은 S4b 앞 주행 기록(7~9초)과 첫 주행(약 8초)보다 짧았다. 안전 축(불변식, 세 원천 `orders_500_0`, 로그, 드레인)은 참이다.

## 끝 상태

Final 단계(18:55)에서 `-p exchange-pml` 스택을 `down -v --remove-orphans`로 지웠다. 남은 `chaos-*` 컨테이너와 `exchange-pml` 볼륨은 없고, `:pml`과 `s5ref`는 `1696fdb0…`, `s4b3ref`는 `dcf0e6a2…`, `:local`은 `611169b8bf40` 그대로다([volume-ls-end.txt](volume-ls-end.txt), [docker-ps-after.txt](docker-ps-after.txt)). Prep 두 번의 재빌드가 만든 태그 없는 이미지(`f84ad774…`, `78d0b123…`)는 지우지 않았다. 컨테이너 로그, LB 로그, k6 로그 원문은 저장소 밖 `%TEMP%\pml-s5-logs`의 하위 폴더에 있고(첫 주행과 같은 폴더를 공유하며 하위 폴더 이름이 같은 것은 이번 주행이 덮었다) 해시는 각 결과 파일에 있다.

## 한계

- 로컬 Docker Desktop 단일 호스트의 결과이고 AWS를 대신하지 않는다. P2라 파티션은 하나이고 한 노드가 모든 마켓을 소유한다. 파티션 LB의 효과는 '팔로워가 1차인 파티션의 첫 시도가 선검사 `503` 뒤 리더'인 모양까지만 봤고, 파티션별 격리는 S7의 P3 관문이다. 두 파티션에 걸친 폐쇄는 P2 스택에서 만들 수 없어 시험(`MemberCloseHandoffTest`)으로만 봤다.
- 첫 주행 Z1r의 WebSocket orderbook 결번 4건은 이번 여덟 번의 k6 주행에서 재현되지 않았다. 원인과 S5 관련 여부는 여전히 모르고, 네 번씩의 주행으로는 드문 사건의 없음을 보일 수 없다.
- Z1의 괄호 판정식은 첫 주행 뒤 AI가 고친 것이고 이번 주행 전에 정했다. 사용자 확인 전이라 본문 판정식의 결과를 함께 적었다(이번에는 둘 다 참).
- `partition_pending`, 작업자의 이어 하기·재점유·만료, 끝내기의 리스 확인은 스택에서 나지 않았다. 이 경로들은 시험과 변이로만 확인했다.
- Z6은 두 주행 모두 측정 두 회다. 셋째 회가 두 번 다 라이터 치명이었고 다시 돌지 않았다.
- 실행기 변경, Z1base, Z4의 `users.kind` 변경은 AI가 정했다. 코드와 증거는 커밋하지 않았다. `docs/measurements/`와 설계 문서 12-4는 고치지 않았다(문서 단계).
