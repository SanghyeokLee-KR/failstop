# S5 관문 폐기용 스택 주행: REST 라우팅과 요청 경계 (2026-09-25 15:19~16:47 KST)

[S5 설계](../../../docs/reference/architecture/마켓분할_리더_S5_설계.md) 7절의 실험을 S5 코드(커밋 전)로 돈 기록이다. 스택은 compose 프로젝트 `exchange-pml`의 폐기용 스택이고, DB 모드는 모든 실험에서 P2, 리스 자원은 `matching-engine` 하나다. 이미지는 한 번만 구웠다. 판정 뒤의 설명과 설계에서 달라진 곳은 S5 설계 12절에 있다.

절차는 [run-s5-gate.ps1](run-s5-gate.ps1)(S4b 실행기 `pml-S4b-20260925-0940/run-s4b-gate.ps1`를 복사해 설계 7-1의 다섯 곳을 고쳤다)이고, 직접 호출과 LB 로그 분해는 [harness/scale/s5_route_probe.py](../../scale/s5_route_probe.py)가 한다. 단계별 시각과 종료 코드는 [commands.log](commands.log), 단계마다 실행기 출력은 `<단계>-runner.log`, 보존 볼륨 확인은 [volumes.txt](volumes.txt)다. 설계 밖의 대조 실험 하나(WsRace)는 [ws_subscribe_race.js](ws_subscribe_race.js)를 쓴다.

## 결론

설계 7-2의 관문 조건 가운데 S5가 바꾼 경로(선검사, 힌트, 한 배치 한 마켓, 인계, 파티션 LB, 한 번 더 읽기)의 판정은 모두 참이다. 단계 판정은 Z0·Z1d·Z2·Z3·Z4r·Z5가 PASS, Z1과 Z1r이 FAIL, Z6이 N/A다. Z1의 FAIL은 판정식의 경계 문제였고(아래), 판정식을 고쳐 다시 돈 Z1r에서는 라우팅 축이 모두 참이었으나 k6 네 번 가운데 한 번이 WebSocket orderbook 결번 임계(`ws_seq_gap{phase:steady,dest:orderbook}` 4건)로 실패했다. S5는 WebSocket 경로를 바꾸지 않았고 그 원인은 찾지 못했다. Z4의 첫 주행은 도구 결함(관리 평면 호출의 줄바꿈)으로 (c)(d)를 판정하지 못해 도구를 고치고 Z4r로 다시 돌았다. 어느 실험에서도 자산·원장 불변식 위반, 구 epoch 쓰기, 주문 `500`(LB 로그, Prometheus, 노드 누계 세 원천), `recovery_runs` 불일치, '처리되지 않은 예외'·'인증 처리 실패로 500' 로그는 나오지 않았다.

| 실험 | 판정 | 요지 |
|---|---|---|
| Z0 E4 기본 LB | PASS | RTO 5.591초(S4b 5.143초), epoch 5 -> 6, 옛 리더가 13초 만에 `FOLLOWER`로 합류(승격 문구 0). 창 안 LB `502` 139·`503` 0(S4b 133·0). 단계 계획의 'P2 E4 결과 불변' 칸이다 |
| Z1 라우팅 분할 | FAIL(판정식) | 라우팅 축 모두 참. 팔로워 `not_owner` 증가 162와 LB 창의 '첫 시도 팔로워 `503`' 163이 한 줄 달랐다. 그 한 줄은 계수를 긁기 전 초(06:25:42)에 끝난 봇 요청이라 초 단위 로그 창에만 들었다. 나머지 25축과 k6 네 번은 참 |
| Z1r 라우팅 분할(판정식 고침) | FAIL(k6 WS 임계) | 라우팅 축 모두 참(괄호 162 ≤ 162 ≤ 163). k6 둘째 주행만 `ws_seq_gap{dest:orderbook}` 4건으로 실패. 원인 미확인 |
| Z1d 힌트 맵과 기본 업스트림(설계 밖 보충) | PASS | 봇을 멈추고 LB로 여섯 요청. 힌트 있는 ETH 생성은 팔로워 `503` 뒤 리더 `201`, 힌트 없는 ETH 생성·맵에 없는 힌트(`KRW-FOO`, 리더가 `400 market_hint_mismatch`)·힌트 없는 일괄 취소는 종전 쓰기 경로의 1차(리더) 한 번, 힌트 있는 BTC 생성은 `exchange_p0` 1차(리더) 한 번, 힌트를 실은 조회(GET)는 힌트로 가르지 않고 리더 한 번. 팔로워 `not_owner` +1 |
| Z2 선검사 무부작용 | PASS | 팔로워에 다섯 모양 모두 `503`·`Retry-After: 1`·`X-Exchange-Route: not-owner`·레이트리밋 헤더 없음, 키·저널·예약 0, 팔로워 `not_owner` +5, 같은 키를 리더에 보내 `201`과 NEW 1행, `chaos-api-3`도 다섯 모두 같은 `503` |
| Z3 힌트·배치 거절 | PASS | 여섯 사례 모두 기대한 `400`, 거절 계수 mismatch 3·duplicate 2·batch 1, 키·NEW 0, 힌트가 틀린 취소 뒤 주문은 `OPEN` |
| Z4 인계(첫 주행) | FAIL(도구) | (a)(b) 참. (c)(d)는 관리 평면 호출이 Windows 텍스트 파이프의 CRLF 때문에 토큰 끝에 CR이 붙어 Tomcat이 헤더를 거절했다('Error parsing HTTP request header'). 도구를 고쳐 Z4r로 다시 돌았다 |
| Z4r 인계 | PASS | (a) 팔로워 일괄 취소 `200`·네 건 취소·V54 행 `DONE`·잠긴 잔고 원복 (b) `chaos-api-3` 같은 결과 (c) 관리 평면 폐쇄 `200`·네 건 취소·감사 행 (d) 참여를 끈 뒤 폐쇄 `409 orders_cancel_incomplete`·`partition_not_started` 4·행 `ABANDONED`, 새 리더(epoch 6) 뒤 10초에도 `OPEN`, 새 승인으로 `200`·네 건 취소, 옛 epoch 쓰기 증분 0 |
| Z5 E4 파티션 LB | PASS | RTO 5.419초, 합류 13.2초, 창 안 LB `502` 90·`503` 2·`504` 1 |
| Z5 E7 파티션 LB | PASS | RTO 10.906초(S4b 10.499초), 창 안 LB 5xx 0. 멈춘 1차에서 10초를 기다린 요청은 힌트 주문 쓰기 11·그 밖 28 |
| Z5 E2 파티션 LB | PASS | RTO 6.553초(S4b 다섯 번 5.8~12.2초), 창 안 LB `502` 24·`503` 6(S4b 15~116·1~7). 옛 리더의 인증 키 조회 한 번 더 읽기 `recovered` 3 |
| Z6 B1 재기록 | N/A(측정 2/3) | 측정 두 회는 리더 `503` 두 건씩, LB `502` 0·1줄(S4b 105·80·54줄 약 2초). 셋째 회는 라이터 치명(`WRITER_FATAL`)으로 epoch 5 -> 6이라 N/A, 그 회의 LB `502` 143줄 약 8초. 안전 축은 참 |
| WsRace 대조(설계 밖) | 기록 | S5 이미지, 봇 300 OPS에서 1.5초짜리 구독 1,600번. 스냅샷 뒤 결번 0, 이후 결번 0, 역순 0 |

## 조건

| 항목 | 값 |
|---|---|
| 이미지 | `sha256:1696fdb04f3d047985070b319892c836a300239682c6bfe8b52c48022b512095`, 참조 태그 `exchange-chaos-app:s5ref`, 2026-09-25 15:19 KST. 기록은 [images.json](images.json) |
| 원천 | 작업 트리 `C:\dev\exchange-pml`(브랜치 `claude/per-market-leader`, HEAD `9a6d2474`)의 커밋하지 않은 S5 변경. 추적 파일 diff SHA-256 `06197bab…`, 변경 경로 88 |
| jar | SHA-256 `d57b3790…`, 2026-09-25 14:50:38. 서버 전체 빌드(14:50~15:08)가 만든 bootJar이고 그 뒤 운영 코드 변경은 없다. Prep의 `:app:bootJar`는 최신 판정으로 이 산출물을 그대로 썼다 |
| 마이그레이션 | V0~V54(`flyway_schema_history` 최대 54, 55행, 실패 0) |
| 앞 이미지 | `s4b3ref`(`dcf0e6a2…`), `:local`(`611169b8bf40`)은 그대로다. WsRace는 S5 이미지로만 돌아 `:pml`이 바뀌지 않았다 |
| 프로필 | `dev,chaos-monkey,prod-timeouts`, 소유자 로그인 |
| LB | Z0과 WsRace는 `harness/nginx/nginx.conf`, 나머지는 `harness/nginx/nginx.partition.conf`(`CHAOS_LB_CONF`를 실행기 프로세스에만 줌). 스택을 세운 단계마다 `nginx -t` 성공과 컨테이너 안 설정 파일 sha256이 저장소 파일과 같음을 `lb.json`에 남겼다. Z0·Z5는 실험 스크립트가 스택을 세우므로 저장한 LB 로그의 형식(`ups=` 필드)으로 파일을 확인했다(Z0 0줄, Z5 모든 접근 줄) |
| 호스트 | Windows 11, Docker Desktop, 논리 CPU 16. 이 세션은 실험 동안 Gradle과 Testcontainers를 돌리지 않았다. 다른 세션의 작업이 함께 돌았다. 다른 compose 프로젝트 `exchange-patroni`(Prep·Z0·Z1 앞과 Z6 뒤), 다른 세션의 Testcontainers 컨테이너(Z5 뒤부터 최대 53개). 단계 앞뒤 표본의 전체 CPU는 최대 61.1%, 도커·앱·부하 도구 밖의 바쁜 프로세스는 모든 표본에서 0이었다. 시간 축에서만 FAIL한 실험이 없어 설계 7-3의 잡음 재실행은 하지 않았다 |
| 보존 자원 | `exchange-chaos_chaos-pgdata`(생성 2026-09-13T11:42:01Z), `failstop-keep-chaos-pgdata-20260924`(2026-09-23T15:47:36Z). 열네 단계 모두 앞뒤 생성 시각이 같았다 |

실행기는 셸 변수 둘(`CHAOS_PROJECT`, `COMPOSE_PROJECT_NAME`), `lib.ps1`의 프로젝트, compose 해석값이 모두 `exchange-pml`일 때만 시작한다. 시작할 때 `chaos-*` 컨테이너는 없었다([docker-ps-before.txt](docker-ps-before.txt)에는 다른 프로젝트 `exchange-patroni`의 컨테이너만 있다). `exchange-chaos` 프로젝트는 내리거나 올리지 않았고 `down -v`는 모두 `-p exchange-pml`에만 했다. 관리 평면은 저장소 루트 `compose.yaml`의 `chaos-admin`을 `docker compose -p exchange-pml -f compose.yaml --env-file harness/.env.chaos --profile admin up -d --no-deps --wait chaos-admin`으로만 띄웠고, 그 전에 해석된 프로젝트 이름이 `exchange-pml`인지 봤다. 운영자 인증서는 컨테이너에 마운트된 데모 PKI 경로(`/pki/op-01.p12`)로만 썼다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/k6_keys.json`)은 경로로만 넘겼다. Z1·Z1r은 k6 주행 전에 부하 계정을 다시 발급해 `k6_keys.json`을 새로 썼고(종전 도구 `clean_suite.reissue_keys`와 같은 길, 개수만 확인), Z2~Z4의 도구는 계정을 새로 발급해 키를 메모리에만 두었다.

```powershell
$ev = 'C:\dev\exchange-pml\harness\evidence\pml-S5-20260925-1515'
$r = "$ev\run-s5-gate.ps1"
pwsh -NoProfile -File $r -Phase Prep
pwsh -NoProfile -File $r -Phase Chaos -LbConf default -Experiments E4 -SubDir Z0
pwsh -NoProfile -File $r -Phase Z1 -LbConf partition                 # 판정식을 고친 뒤 -SubDir Z1r 로 한 번 더
pwsh -NoProfile -File $r -Phase Z2 -LbConf partition
pwsh -NoProfile -File $r -Phase Z3 -LbConf partition
pwsh -NoProfile -File $r -Phase Z4 -LbConf partition                 # 도구를 고친 뒤 -SubDir Z4r 로 한 번 더
pwsh -NoProfile -File $r -Phase Chaos -LbConf partition -Experiments 'E4,E7,E2' -SubDir Z5
pwsh -NoProfile -File $r -Phase B1 -LbConf partition                 # 결과 폴더 Z6
pwsh -NoProfile -File $r -Phase WsRace -LbConf default -SubDir W-s5  # 설계 밖 대조
pwsh -NoProfile -File $r -Phase Final
pwsh -NoProfile -File $r -Phase Z1d -LbConf partition                # 설계 밖 보충(기본 업스트림의 실제 요청)
pwsh -NoProfile -File $r -Phase Final                                 # 다시(Final2-runner.log)
```

## Z1·Z1r 라우팅 분할

P2에서 리더는 api-1이라 `exchange_p0`(BTC·XRP 등)은 1차가 리더이고 `exchange_p1`(ETH 등)은 1차가 팔로워다. 봇 300 OPS(여러 마켓에 주문과 취소를 내고 늘 힌트를 싣는다. 판정 창의 힌트 없는 주문 쓰기는 0줄이었다)에 k6 `order_flow.js`(`PROFILE=smoke`, `STEADY_RATE=5`, `SPIKE_RATE=10`, `WS_VUS=10`, `MARKETS=KRW-BTC,KRW-ETH,KRW-XRP`, `MARKET_HINT=1`)를 네 번 이어 돌렸다(약 8분). k6 조건은 설계에 값이 없어 실행기가 정했다. 판정 창의 LB 접근 로그를 도구가 힌트와 첫 시도 업스트림으로 나눴다([Z1r/lbsplit.json](Z1r/lbsplit.json)).

| 축 | Z1 | Z1r |
|---|---|---|
| `exchange_p0` 힌트 주문 쓰기(첫 시도가 모두 리더) | 15,404줄, 팔로워 0 | 15,366줄, 팔로워 0 |
| `exchange_p1` 힌트 주문 쓰기 | 6,808줄, 첫 시도 팔로워 163줄 모두 `503, 2xx`로 리더에서 끝남, 나머지 6,645줄은 팔로워가 빠진 동안 리더가 첫 시도 | 6,659줄, 첫 시도 팔로워 162줄 모두 `503, 2xx`, 나머지 6,497줄 리더 |
| 두 번 시도 비율(`exchange_p1`) | 2.39% | 2.43% |
| 팔로워 `exchange_routing_precheck_total{result="not_owner"}` 증가 | 162 | 162 |
| 그와 대조한 LB 줄 수 | 163(판정식: 같음, 거짓) | 괄호 안쪽 162·바깥쪽 163(판정식: 안쪽 ≤ 증가 ≤ 바깥쪽, 참) |
| 창 안 `502`·`no live upstreams` | 0·0 | 0·0 |
| 두 노드 `cancel_not_owner`·힌트 거절 증가, 리더 `not_owner` 증가 | 0·0·0 | 0·0·0 |
| k6 네 번 | 모두 통과 | 둘째만 `ws_seq_gap{dest:orderbook}` 4건 |
| 창 안 체결 | 12,110 | 12,204 |

첫 판정식은 계수를 긁은 앞뒤 시각을 초 단위 LB 로그 창(`t0-1초`~`t1+3초`)에 그대로 대었다. 계수를 긁기 전 초(06:25:42)에 끝난 봇의 팔로워 첫 시도 한 줄이 창에 들어가 한 줄이 남았다. 봇이 ETH에도 주문을 내므로 경계 초에 이런 줄이 생길 수 있다. 판정식을 두 창으로 바꿨다. 안쪽 창(첫 긁기가 끝난 초 + 2초부터 둘째 긁기가 시작된 초 - 1초까지)의 줄은 반드시 두 긁기 사이이고, 바깥쪽 창(첫 긁기가 시작된 초부터 둘째 긁기가 끝난 초 + 1초까지)은 두 긁기 사이의 줄을 모두 담는다. 계수 증가가 두 수 사이에 들면 참이다. Z1의 기록에서 그 경계 초의 한 줄을 빼면 162로 같지만, 고친 판정식의 판정은 다시 돈 Z1r의 것만 적는다.

Z1r의 k6 둘째 주행(06:40:42~06:42:43)은 WebSocket 세션 40개 가운데 몇 개에서 orderbook 결번 4건을 셌다. 같은 주행의 역순 폐기는 0이고, 서버 로그에 경고가 없고, 모든 세션이 30초를 채웠으며 LB 로그의 WebSocket 160세션은 모두 리더(api-1)가 받았다. S5는 WebSocket 게시·구독 코드를 바꾸지 않았다. 구독 즉시 스냅샷이 구독 등록 전에 seq를 읽는 경합을 의심해 설계 밖 대조 실험(WsRace)을 돌렸으나, 같은 이미지·같은 봇 부하에서 1.5초짜리 구독 1,600번에 결번이 하나도 없어 이 가설로는 설명하지 못했다. 원인은 찾지 못했다. S4·S4b 관문은 봇 300 OPS 아래에서 k6 WebSocket 임계를 돌린 적이 없어 비교 기준선도 없다.

## Z4 인계

| 경우 | 응답 | 걸린 시간 | V54 행 | 주문 |
|---|---|---:|---|---|
| (a) 팔로워 마켓 없는 일괄 취소 | `200`, `canceled_count` 4, `failed` 없음 | 256.5ms | `DONE`(`cancel_all`) | 넷 모두 `CANCELED`, 잠긴 KRW 187,800 -> 0 |
| (b) `chaos-api-3` 같은 요청 | `200`, 4, 없음 | 247.5ms | `DONE` | 넷 모두 `CANCELED` |
| (c) 관리 평면 회원 폐쇄 | `200`, `canceled_orders` 4 | 342.4ms | `DONE`(`member_close`) | 넷 모두 `CANCELED`, 감사 행 1 |
| (d) 참여를 끄고 폐쇄 | `409 orders_cancel_incomplete`, `pending_reasons={"partition_not_started": 4}` | 5,152.9ms | `ABANDONED` | 새 리더(같은 holder, epoch 5 -> 6)가 선 뒤 10초에도 넷 모두 `OPEN` |
| (d) 새 승인으로 다시 폐쇄 | `200`, 4 | 315.5ms | 새 행 `DONE` | 넷 모두 `CANCELED` |

(c)(d)는 부하 계정 둘의 `users.kind`를 `HUMAN`으로 바꿔 폐쇄 대상으로 썼다(폐쇄는 `HUMAN`만 받는다. 도구가 쓴 DB 쓰기는 이 두 건뿐이다). 리더의 작업자 계수는 점유 4·완료 4였고, (a)~(c) 동안 리더의 선검사 `not_owner` 증가는 0, 옛 epoch(5)로 쓰인 저널 행은 새 리더가 선 전후 같았다(명령 160, 이벤트 265).

## Z5 E4·E7·E2 (파티션 LB)

| 실험 | RTO(초) | 창 안 LB `502`·`503`·`504` | 두 번 시도 줄 | 가장 긴 요청(초) | 노드 기록 |
|---|---:|---|---:|---:|---|
| E4 + 합류 | 5.419 | 90·2·1 | 110 | 2.001 | 새 리더(api-2)의 승격 전 선검사 `not_owner` 97. 합류 13.2초, 승격 문구 0 |
| E7 | 10.906 | 0·0·0 | 45 | 10.001 | 재개한 옛 리더가 강등(파티션 강등 1줄), 응답을 쓰지 못한 요청 WARN 22줄. 멈춘 1차에서 `proxy_read_timeout`(10초)을 기다린 요청은 힌트 주문 쓰기 11(`499` 10, 재시도 뒤 `401` 1)·그 밖 28 |
| E2 | 6.553 | 24·6·0 | 79 | 8.652 | 옛 리더 `exchange_db_read_retry_total{site="auth",outcome="recovered"}` 3, `unreachable` 0, 접수 불가 `fencing|503` 1. 노드 수용 거절 0. Prometheus의 `/api/v1/orders` 생성 응답 증가는 `201` 1,631이고, 인증 단계와 선검사의 `503`은 uri가 `UNKNOWN`이라 여기에 들지 않는다 |

세 실험 모두 스크립트 판정, 공통 판정, 파티션 축이 참이다. E2의 옛 리더는 DB 절단 동안 요청을 받은 지 약 3초 뒤 `503`을 냈고(커넥션 획득 대기로 보인다. 어느 단계의 `503`인지는 가르지 않았다, 추정), 두 번째 시도(팔로워)도 `503`이라 클라이언트는 약 3초 뒤 `503`을 받았다. 파티션 LB의 기본 파일 대비 차이를 같은 조건으로 잰 판은 없다(Z0은 E4만 기본 파일이다). E7의 재시도 뒤 `401`은 멈춘 1차에서 10초를 기다린 서명 요청의 시각이 허용창을 넘은 것으로 보인다(추정).

## Z6 B1 재기록

새 스택(P2, 봇 300 OPS, 2분 예열)에서 리더(api-1)의 pg 프록시에 `reset_peer`를 500ms 걸었다 지우기를 60초 간격으로 세 번 했다.

| 회 | 판정 | 리더 `503`(원천) | 한 번 더 읽기 | LB `502`·`503`·`no live upstreams` | 5xx 창 |
|---|---|---|---|---|---|
| 1 | 측정 | 인증 필터 1(`UNKNOWN`), 시세 조회 1 | `auth` `unreachable` 1 | 0·1·0 | 07:20:56 한 초 |
| 2 | 측정 | 주문 생성 1(`connection|503`), 인증 필터 1 | `auth` `unreachable` 1 | 1·1·1 | 07:21:57 한 초 |
| 3 | N/A | 라이터 치명(`WRITER_FATAL`) 뒤 재구성·재승격, epoch 5 -> 6(같은 holder) | - | 143·6·143 | 07:22:58~07:23:06 약 8초 |

S4b B1(같은 조건, 기본 파일)의 LB `502`는 105·80·54줄(약 2초)이었다. 측정한 두 회에서 리더의 `503`은 한두 건으로 S4b와 같은 크기이고, 파티션 업스트림 1차의 `max_fails=2`가 그 한 건을 흡수해 `502`가 0·1줄이었다. 한 번 더 읽기는 두 회 모두 둘째 읽기도 끊겨(`unreachable`) `503`을 막지 못했다. `reset_peer`가 500ms 동안 걸려 있어 둘째 읽기도 그 창 안이었다. 셋째 회의 라이터 치명 비용(약 8초 `502`)은 S4b의 앞 주행 기록(7~9초)과 같다. 안전 축(불변식, 세 원천 `orders_500_0`, 로그, 드레인)은 참이다.

## 끝 상태

Final 단계(16:30, Z1d 뒤 16:46에 한 번 더)에서 `-p exchange-pml` 스택을 `down -v --remove-orphans`로 지웠다. 남은 `chaos-*` 컨테이너와 `exchange-pml` 볼륨은 없다([docker-ps-after.txt](docker-ps-after.txt)에는 다른 세션의 컨테이너만 있고, [volume-ls-end.txt](volume-ls-end.txt)는 빈 파일이다). `:pml`은 이 주행의 이미지(`1696fdb0…`, `s5ref`)를 가리킨다. 컨테이너 로그, LB 로그, k6 로그 원문은 저장소 밖 `%TEMP%\pml-s5-logs`의 하위 폴더에 있고 해시는 각 결과 파일에 있다. 첫 Z4 주행의 로그는 실행기 결함(단계 이름 변수를 compose 해석값이 덮음, 고침)으로 `%TEMP%\pml-s5-logs\exchange-pml`에 남았다. `Z1/result.json`·`Z1r/result.json`의 k6 임계 줄에 있던 통과·실패 기호는 저장소 문체 규칙(기호 금지) 때문에 주행 뒤 `[통과]`·`[실패]`로 바꿨다. 값은 바꾸지 않았고, 실행기도 뒤로는 그렇게 적는다.

## 한계

- 로컬 Docker Desktop 단일 호스트의 결과이고 AWS를 대신하지 않는다. P2라 파티션은 하나이고 한 노드가 모든 마켓을 소유한다. 파티션 LB의 효과는 '팔로워가 1차인 파티션의 첫 시도가 선검사 `503` 뒤 리더'인 모양까지만 봤고, 파티션별 격리는 S7의 P3 관문이다.
- Z1r의 k6 WebSocket orderbook 결번 4건은 원인을 찾지 못했다. S5가 그 경로를 바꾸지 않았다는 것은 코드 대조이고 실험으로 원인을 배제한 것은 아니다.
- Z1의 판정식은 주행 뒤 고쳤다. 고친 판정식의 결과는 다시 돈 Z1r만 적었다.
- `partition_pending`, 작업자의 이어 하기·재점유·만료, 끝내기의 리스 확인은 스택에서 나지 않았다. 스택의 인계는 모두 한 행이 기다림 안에 끝났다. 이 경로들은 시험과 변이로만 확인했다.
- Z6은 측정 두 회다. 셋째 회는 라이터 치명이라 N/A로 적었고 다시 돌지 않았다.
- 실행기의 k6 조건, 판정식 변경, WsRace, Z4의 `users.kind` 변경은 AI가 정했다. 코드와 증거는 커밋하지 않았다. `docs/measurements/`는 고치지 않았다(문서 단계).
