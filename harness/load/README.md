# k6 부하 실행

k6로 거래 서버에 일정한 도착률의 주문·조회 요청을 보내고 지연, 거절과 오류를 측정한다. 시장을 만드는 봇과 함께 사용할 수 있다. 판정 기준은 [하네스 검증 설계](../../docs/reference/contracts/하네스_검증설계.md) §2와 §6을 따른다.

## 준비

현재 [Compose](../../compose.yaml)는 `grafana/k6:1.4.0`을 사용한다. [STOMP 구현](lib/stomp.js)은 이 버전의 `k6/experimental/websockets`를 가져온다. 다른 버전이나 모듈 경로로 바꾸려면 호환성을 먼저 확인한다.

실행 전에 API, 봇과 실제 부하 계정 키를 준비한다. 예제 JSON을 복사한 것만으로 유효한 계정이 생기지는 않는다. 기동과 계정 준비는 [실행 가이드](../../실행_가이드.md)를 따른다.

`RUN_ID`는 회차마다 새로 만들고, 사전 검사와 본 실행에서는 같은 값을 사용한다. 이 값은 주문 식별자에 포함되므로 재사용하면 새 요청이 기존 주문의 재시도로 처리될 수 있다.

## 기본 실행

로컬 스택과 부하 계정 키가 준비된 상태에서 저장소 루트의 PowerShell로 실행한다. 이 명령은 Compose에 정의된 `demo` 프로파일의 k6 시나리오를 사용한다.

```powershell
$env:RUN_ID = (Get-Date).ToString('yyyyMMdd-HHmmss')
docker compose --env-file harness/.env.chaos --profile load run --rm chaos-k6
```

Compose의 `chaos-preflight`와 본 실행은 공통 환경 값을 사용한다. 사전 검사가 실패하면 계정, 잔고, 주문 식별자와 연결 조건을 먼저 확인한다.

E6 등 다른 시나리오는 [config/e6.env](config/e6.env)와 [order_flow.js](order_flow.js)의 입력을 확인해 실행한다. `harness/config/e6.env`의 봇 OPS와 `harness/load/config/e6.env`의 k6 요청률은 다른 값이다. 이름이 비슷하다는 이유로 서로 복사하지 않는다.

## 파일 위치

| 파일 | 역할 |
|---|---|
| [order_flow.js](order_flow.js) | 정상·폭주 주문, 시세 조회와 STOMP 측정 |
| [preflight.js](preflight.js) | 계정, 식별자와 실행 조건 사전 확인 |
| [cleanup.js](cleanup.js) | 부하 계정의 잔여 주문 취소 |
| [lib/sign.js](lib/sign.js) | API 요청 서명 |
| [lib/stomp.js](lib/stomp.js) | STOMP 연결과 메시지 처리 |
| [scenarios/thundering-herd.js](scenarios/thundering-herd.js) | WebSocket 동시 연결 부하 |
| [config/](config/) | 시나리오별 부하와 장애 시간 설정 |

## 판정할 때 주의할 점

`order_flow.js`의 thresholds가 실행 결과를 판정한다. 현재 적용할 숫자는 소스와 검증 설계에서 확인하며, 다른 회차의 수치를 그대로 가져오지 않는다.

- 신규 접수량과 실제 표본 수를 함께 본다. 전량 거절되거나 표본이 없으면 지연이 낮아도 정상 처리의 증거가 아니다.
- `accepted_req_duration`은 수용된 요청의 지연이다. 전체 요청 지연과 구분한다.
- 주문 재시도는 같은 식별자로 재조회하는지 확인한다. 서버의 신규 접수 중복 검사는 별도의 불변식 검증이 맡는다.
- 호가와 체결 채널은 순번 규칙이 다르다. 체결 순번의 빈 구간을 곧바로 유실로 계산하지 않는다.
- `ws_epoch_resync_fail`이라는 이름만으로 재동기화 전체를 검증했다고 해석하지 않는다. 현재 측정 범위와 남은 한계는 검증 설계 §2-4를 본다.
- 거래 일시 중단(Patroni 실험 스택)의 `423 trading_suspended`는 접수 실패로 세지 않고 `trading_suspended_total`로 따로 센다. 423을 받은 키는 그 VU의 다음 주문이 게이트를 지난 뒤 같은 키로 재조회해 `suspended_key_committed_total`(같은 키의 앞선 시도가 커밋됨)과 `suspended_key_absent_total`(미접수)로 나눈다. 두 수의 합이 423 수보다 작으면 결과를 정하지 못한 채 주행이 끝난 키가 있다. k6에는 재전송 경로가 없어 423을 받은 키를 새 키로 다시 내지 않는다.

## 장애 구간 설정

`CHAOS_T0_S`와 `CHAOS_T1_S`를 함께 지정한다. 이 구간의 표본은 `phase:chaos`, 나머지는 `phase:steady`로 나눈다. 한쪽만 주면 실행을 중단한다.

페일오버를 허용하는 실험은 `FAILOVER_WINDOW=1`과 `RTO_TARGET_S`를 지정한다. 장애 구간의 접수 실패율 상한은 복구 시간 목표를 구간 길이로 나눈 값이다. 예를 들어 60초 구간과 30초 목표면 `rate<0.5`다. 페일오버를 허용하지 않는 실험에는 같은 예산을 적용하지 않는다.

정상 구간에서 통과했다는 사실만으로 장애 구간의 WebSocket 복구까지 통과했다고 볼 수 없다. k6 결과와 장애 스크립트의 판정을 함께 확인한다.

## 결과 보존

실행 로그, 종료코드, 실제 요청량과 요약 결과를 같은 회차로 보관한다. 필요하면 Prometheus 출력이나 HTML 보고서를 함께 남긴다. 새 주문 접수의 `ACK|...` 로그는 외부 응답 기록과 DB 명령 기록을 대조하는 데 사용한다.

ACK 수집·적재는 실험 러너와 [하네스 검증 설계](../../docs/reference/contracts/하네스_검증설계.md) §2-5를 따른다. 로그에 값이 있다는 것과 `harness.loadtest_acks`에 실제 적재됐다는 것을 구분한다.
