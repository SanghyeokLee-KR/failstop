# k6 부하 시나리오 (외부 SLO 계측 + CI 게이트)

시스템 완전 외부에서 개방 루프(ramping-arrival-rate)로 주문·조회를 주입하고,
coordinated-omission 없는 p99를 계측한다. thresholds 위반 시 non-zero exit로 실험/CI를
FAIL 판정한다. 정본: `../../docs/하네스_검증설계.md` §2-2·§6, `../../docs/하네스_CICD_IaC.md` §6-1.

봇 플릿(시장 형성·배경 부하)과 직교한다. 봇은 폐쇄 루프로 시장을 만들고, k6는 그 위에
개방 루프 스파이크를 중첩해 외부 관점 SLO·다운타임을 계측한다.

## 파일

| 파일 | 역할 |
|---|---|
| `order_flow.js` | 정본 시나리오. orders_steady·orders_spike·market_reads·ws_stomp 4종 + thresholds |
| `lib/sign.js` | HMAC 서명(API§3-3). JCS 템플릿 치환으로 서명 비용 절감(§2-2) |
| `lib/stomp.js` | STOMP 1.2 프레임 순수 JS 구현(k6/websockets 위, §6-1) |
| `scenarios/thundering-herd.js` | WS 동시연결 5,000 버스트(§3-3 예산) |
| `config/e6.env`,`config/demo.env` | 타임라인·환경변수(재현용). 오케스트레이터가 카오스와 공유 |
| `secrets/k6_keys.example.json` | 부하 계정 키 형식. 실계정은 seed 잡이 발급(gitignore) |

## 도구 핀 (사전 게이트로 검증)

k6 **v2.1.0**. WS는 안정 `k6/websockets`. 부재 시 `lib/stomp.js`의 import를
`k6/experimental/websockets`로 교체(§0 폴백). CI 사전 스모크:

```
k6 version
echo "import { WebSocket } from 'k6/websockets'; export default function(){}" > /tmp/ws.js && k6 run --vus 0 --iterations 0 /tmp/ws.js
```

## 실행

```
# 부하 계정 키 준비(seed 잡 산출물을 복사)
cp secrets/k6_keys.example.json secrets/k6_keys.json   # 실키로 치환

# 표준 E6(폭주 + 리더 kill 복합). config/e6.env를 -e로 전개
k6 run $(sed 's/^/-e /' config/e6.env | grep -v '^-e #') order_flow.js

# 데모(15분). PROFILE=demo, kill 05:30
k6 run -e PROFILE=demo -e CHAOS_T0_S=330 -e CHAOS_T1_S=390 order_flow.js

# thundering-herd(WS 5,000 동시연결)
k6 run -e WS_TARGET=5000 scenarios/thundering-herd.js
```

PowerShell에서는 각 줄을 `--env KEY=VALUE`로 전개한다.

## 판정(기계)

| threshold | 의미 |
|---|---|
| `http_req_failed{scenario:orders_steady,name:placeOrder} < 0.001` | 접수 경로 가용성 99.9%(API§7-4). 수용 제한 응답은 expectedStatuses로 제외 |
| `http_req_failed{name:lookup,phase:steady} < 0.001` | 정상 창 재조회 실패 0. 200·404·409·429는 요청별 responseCallback으로 정상 선언 |
| `accepted_req_duration{scenario:orders_steady} p95<200 p99<500` | 수용(2xx) 요청 지연 SLO |
| `checks{check:idempotent_safe} == 1` | UNKNOWN→재조회→재전송 규약 위반 0 |
| `ws_seq_gap{phase:steady} == 0` | 정상 창 채널 seq 결번 0. 카오스 창(phase:chaos)은 제외 |
| `ws_epoch_resync_fail == 0` | epoch 변화·gap 후 재동기화 실패 0(전역) |

이중 신규 접수 0 검증은 서버측 C07'(command_journal NEW 축) 상시 검증이 전담한다(검증설계
§4-1). k6는 UNKNOWN 시 재조회만 하고 재전송 경로가 없어 dup를 만들 수 없으므로, k6 임계로
두면 항상 통과하는 토톨로지가 된다(구 dup_ack_order 임계 제거 사유).

## 카오스 창 제외 규약

오케스트레이터가 `CHAOS_T0_S`(주입 시각 초)·`CHAOS_T1_S`(T0+60s)를 k6와 카오스 주입에
동일 타임라인으로 주입한다. 스크립트는 경과 시간이 창 안이면 `phase:chaos`, 밖이면
`phase:steady`로 표본을 태깅한다. 카오스 창 내 seq gap은 재동기화 성공(`ws_epoch_resync_fail==0`)
으로 판정하고, steady 창의 gap만 0을 강제한다. E3의 "gap 감지→재동기화 흡수" 정상 경로가
FAIL로 오판되지 않는다.

## 출력

- `-o experimental-prometheus-rw`(`K6_PROMETHEUS_RW_SERVER_URL`) → Grafana 합류(대시보드 19665).
- `K6_WEB_DASHBOARD_EXPORT=report.html` → HTML 리포트 보존(발표 증거).
- 각 2xx 신규 접수는 `ACK|{ak,coid,oid,t}` 라인을 stdout에 출력한다. 실험 러너가 이를 추출해
  `harness.loadtest_accounts` 조인 후 `harness.loadtest_acks`로 COPY(source='k6')한다(§2-5, C16 원천).
  실험 시작 훅이 `POST /harness/v1/loadtest-acks/reset`으로 TRUNCATE를 선행한다.

## ACK → loadtest_acks 적재(§2-5 파이프라인)

```
k6 run ... order_flow.js 2>&1 | grep '^ACK|' | sed 's/^ACK|//' > acks.ndjson
# acks.ndjson의 ak를 harness.loadtest_accounts로 user_id 변환 후 CSV 생성 → \copy
psql -c "\copy harness.loadtest_acks(user_id,client_order_id,order_id,acked_at,source) FROM 'acks.csv' CSV"
```
