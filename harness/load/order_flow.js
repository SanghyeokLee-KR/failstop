// 외부 SLO 계측기 + CI 게이트(검증설계 §2-2). 개방 루프(ramping-arrival-rate)로 스파이크를
// 중첩 주입하고 coordinated-omission 없는 p99를 계측한다. thresholds 위반 시 non-zero exit로
// 실험/CI를 FAIL 판정한다(사람 눈 판정 없음).
//
// PROFILE로 실험/데모 타이밍을 파라미터화한다(§7 데모 표와 1:1).
//   e6:   spike 10m 시작·plateau 60s (표준 E6, kill = plateau 진입 +30s)
//   demo: spike 2m 시작·plateau 240s (데모, kill 05:30이 plateau 안)
// 카오스 창 제외 규약: 오케스트레이터가 CHAOS_T0_S/CHAOS_T1_S를 k6와 카오스 주입에 동일
//   타임라인으로 주입한다. 창 안 표본은 phase:chaos, 밖은 phase:steady로 태깅해 정상
//   재동기화 경로가 FAIL로 오판되지 않게 한다(§2-2).

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { signRequest, orderCanonicalBody } from './lib/sign.js';
import { WebSocket, connectFrame, subscribeFrame, parseFrames, topicOrderbook, topicTrade } from './lib/stomp.js';

const BASE = __ENV.BASE_URL || 'http://localhost:58080';
const WS_URL = __ENV.WS_URL || BASE.replace(/^http/, 'ws') + '/ws/v1';
const HOST = __ENV.API_HOST || 'api.exchange.local';
const MARKET = __ENV.MARKET || 'KRW-BTC';
// 주문을 여러 마켓에 흩는다(ADR 0021 5단계 실측용). 기본값은 단일 마켓이라 기존 회차의
// 부하 형태가 바뀌지 않는다.
//
// 왜 필요한가. 마켓별 라이터의 이득은 부하가 여러 마켓에 걸릴 때만 난다. 한 마켓만 치면
// 라이터도 하나만 돌아 이득이 0으로 나오는데, 그것은 코드가 아니라 부하 형태의 결과다.
// 그 둘을 갈라야 이번 변경을 실제로 잰 것이 된다.
const MARKETS = (__ENV.MARKETS || MARKET).split(',').map((m) => m.trim()).filter((m) => m);
// 주문 생성의 마켓 힌트 헤더(API 설계 §2-5-d, ADR 0034 S5). 서명 밖이라 canonical 을 바꾸지 않고 인그레스가 그 마켓을 접수하는
// 노드로 보낸다. 기본 1(싣는다), 0 이면 힌트 없는 클라이언트를 흉내 낸다(S7 P3 관문의 힌트 없는 부하).
const MARKET_HINT = String(__ENV.MARKET_HINT || '1') !== '0';

// 마켓마다 호가 단위와 가격대가 다르다. 같은 가격과 수량을 전 마켓에 보내면 시장 규칙에
// 걸려 400 이 된다(2026-09-10 실측: 다른 마켓 주문 20,826건이 전량 400). tick 배수와
// 최소 주문 금액 5,000원을 함께 만족하는 값을 마켓마다 둔다. 매수는 기준가 아래,
// 매도는 위로 두어 즉시 체결되지 않고 호가로 남게 한다. 부하 형성이 목적이다.
const MARKET_RULES = {
  'KRW-BTC': { bid: '49900000', ask: '50100000', volume: '0.0010' },
  'KRW-ETH': { bid: '4400000', ask: '4600000', volume: '0.0100' },
  'KRW-SOL': { bid: '200000', ask: '220000', volume: '0.1000' },
  'KRW-XRP': { bid: '700', ask: '800', volume: '20.0000' },
  'KRW-DOGE': { bid: '300', ask: '400', volume: '50.0000' },
  'KRW-ADA': { bid: '700', ask: '800', volume: '20.0000' },
  'KRW-TRX': { bid: '300', ask: '400', volume: '50.0000' },
  'KRW-LINK': { bid: '20000', ask: '22000', volume: '1.0000' },
  'KRW-DOT': { bid: '6000', ask: '7000', volume: '2.0000' },
  'KRW-MATIC': { bid: '600', ask: '700', volume: '20.0000' },
  'KRW-AVAX': { bid: '30000', ask: '33000', volume: '1.0000' },
  'KRW-ATOM': { bid: '7000', ask: '8000', volume: '2.0000' },
};

// 외부 주입 레이트(k6 축). 봇 거버너 축(EXCHANGE_SIM_TARGET_OPS)과 다른 축이고, 섞으면
// 이미 PASS한 카오스 실험의 전제가 바뀐다. 기본값은 2026-08-29 캘리브레이션 확정치이고
// (harness.calibration_runs id=1) READ는 기저 x 1.667, SPIKE는 기저 x 10이다.
function positiveRate(name, raw, fallback) {
  const v = parseInt(raw || fallback, 10);
  // 0·음수·NaN은 k6가 뒤늦게 모호한 오류를 내거나 아무것도 발행하지 않는다. 발행량 0인
  // 주행은 모든 임계를 표본 없이 통과하므로 설정 오류 단계에서 끊는다.
  if (!Number.isInteger(v) || v <= 0) {
    throw new Error(`${name}는 1 이상의 정수여야 한다(받은 값: ${String(raw)}).`);
  }
  return v;
}
const STEADY_RATE = positiveRate('STEADY_RATE', __ENV.STEADY_RATE, '10');
const READ_RATE = positiveRate('READ_RATE', __ENV.READ_RATE, '17');
const SPIKE_RATE = positiveRate('SPIKE_RATE', __ENV.SPIKE_RATE, '100');
// orders_spike는 STEADY_RATE에서 출발한다. 두 값이 역전되면 스파이크 시나리오가 램프다운이
// 되어 부하가 줄어든 주행이 폭주 실험 결과로 보고된다.
if (SPIKE_RATE < STEADY_RATE) {
  throw new Error(`SPIKE_RATE(${SPIKE_RATE})가 STEADY_RATE(${STEADY_RATE})보다 작다. orders_spike가 램프업이 아니라 램프다운이 되어 폭주 주입이 부하 감소 주행이 된다.`);
}

const T0 = parseFloat(__ENV.CHAOS_T0_S || 'Infinity');
const T1 = parseFloat(__ENV.CHAOS_T1_S || 'Infinity');
// 창 실패 예산(§2-2). 페일오버 창에만 RTO 유도 상한을 쓴다. 표식이 없으면 비페일오버 주입이라
// 다운타임 허용이 없고(§3 E1 1차 "주입 중에도 status·readiness 응답 유지") 창에도 정상 상한을 건다.
const FAILOVER_WINDOW = String(__ENV.FAILOVER_WINDOW || '') === '1';
const RTO_TARGET_S = parseFloat(__ENV.RTO_TARGET_S || 'NaN');
const CHAOS_WINDOW_S = T1 - T0;
const STEADY_FAIL_MAX = 0.001;  // 가용성 99.9%(API§7-4)

// 창은 반쪽으로 설정할 수 없다. T0만 주면 phase()가 T0 이후 전 구간을 chaos로 태깅해
// phase:steady 임계가 그 뒤로 아무것도 재지 않는다. 창이 정상 임계를 오염시키던 결함을
// 반대편 무판정으로 옮기는 것이라 설정 오류로 즉시 중단한다(§2-2 창 설정 무결성).
if (Number.isFinite(T0) !== Number.isFinite(T1)) {
  throw new Error('CHAOS_T0_S와 CHAOS_T1_S는 함께 설정한다. 한쪽만 주면 T0 이후 전 구간이 phase:chaos가 되어 정상 구간 임계가 무판정이 된다(검증설계 §2-2).');
}
if (Number.isFinite(T0) && CHAOS_WINDOW_S <= 0) {
  throw new Error(`카오스 창 길이가 0 이하다(T0=${T0}, T1=${T1}). T1 = T0 + 60s(RTO 2배 마진)가 정본이다.`);
}
// 유도 근거 없는 페일오버 표식은 받지 않는다. 기본값으로 때우면 임의 상수를 쓰는 것과 같다.
if (FAILOVER_WINDOW && !Number.isFinite(RTO_TARGET_S)) {
  throw new Error('FAILOVER_WINDOW=1에는 RTO_TARGET_S가 필요하다. 창 실패율 상한을 RTO 목표에서 유도하기 때문이다(검증설계 §2-2).');
}

const P = {
  e6:   { spikeStart: '10m', plateau: '60s',  total: '13m' },
  demo: { spikeStart: '2m',  plateau: '240s', total: '15m' },
  smoke:{ spikeStart: '30s', plateau: '30s',  total: '2m'  },
}[__ENV.PROFILE || 'e6'];

// 부하 계정 키(§7-3 seed 잡 산출물). 부재 시 단일 계정(스모크)으로 폴백한다.
const KEYS = new SharedArray('keys', () => {
  try {
    return JSON.parse(open(__ENV.K6_KEYS || './secrets/k6_keys.json'));
  } catch (e) {
    return [{ access_key: __ENV.ACCESS_KEY || '', secret: __ENV.SECRET || '' }];
  }
});

// 수용(2xx) 요청 전용 지연 Trend. 429는 여기 넣지 않아 가용성 지표 오염을 막는다(§2-2).
const acceptedReqDuration = new Trend('accepted_req_duration', true);
const rateLimitedTotal = new Counter('rate_limited_total');
// 마켓 접수 경합(API 설계 §2-5-b, S4 설계 4-18). 429 바디의 error.detail.bucket 이 market 이면 레이트리밋이 아니라 접수 락 대기
// 초과나 append 잠금 경합이다. 429 는 실패로 세지 않으므로(아래 expectedStatuses) 이 비율이 없으면 경합이 가용성 판정에서
// 사라진다. 분모는 주문 접수 요청 전부다.
const marketContention = new Rate('market_contention');
const marketContentionTotal = new Counter('market_contention_total');
// 접수 건수. 주문 축의 양성 증거다. accepted_req_duration은 Trend라 count 임계를 못 걸고
// (k6: unsupported aggregation method count on metric of type trend) 지연 임계도
// http_req_failed도 표본 0에서 통과한다. 별도 Counter로 하한을 건다.
const acceptedTotal = new Counter('accepted_total');
// 채택한 seq 수. 양성 증거 축이다. 위 세 임계가 전부 단측(count==0·count<200)이라
// 메시지가 한 건도 안 오면 전건 통과한다. 즉 관측 실패가 통과 신호가 된다. 실측:
// 존재하지 않는 마켓을 구독하면 MESSAGE 0건인데 세 임계가 다 통과하고 exit 0이었다.
// cleanup.js가 같은 실패 클래스를 cleanup_accounts_done count>=N으로 막은 것과 같은 규약.
const wsSeqObserved = new Counter('ws_seq_observed');
const wsStaleDiscarded = new Counter('ws_stale_discarded');
const wsSeqGap = new Counter('ws_seq_gap');             // 채널 seq 결번. dest 태그로 채널을 가른다

// destination을 채널 종류로 접는다. 마켓별로 태그가 갈리면 임계 키가 마켓 수만큼 늘어
// 계약이 고정 문자열로 서지 않는다.
function destKind(dest) {
  if (dest.indexOf('/topic/orderbook') === 0) return 'orderbook';
  if (dest.indexOf('/topic/trade') === 0) return 'trade';
  return 'other';
}
// WS 연결 오류. 이름은 재동기화 실패지만 실제 증가 지점은 onerror 하나뿐이고 카오스 창은
// 게이트로 뺀다. 즉 재동기화 성패를 재지 않는다. API§5-2의 gap 감지 후 REST 스냅샷
// 재동기화는 미구현이다(하네스는 계측기라 흡수하지 않고 실패로 계상하는 설계다).
// 이름과 계측 대상이 어긋난 자리이며 검증설계 §2-2에 미해소로 등재했다.
const wsEpochResyncFail = new Counter('ws_epoch_resync_fail');
// 페일오버 창 접수 실패(5xx·타임아웃) 건수. 아래 가용성 임계에서 창을 제외하는 대신 이 축으로
// 남겨 관측을 잃지 않는다. 창 실패는 페일오버의 정의상 결과라 정상 구간과 같은 분모로 재면 안 된다.
const chaosWindow5xx = new Counter('chaos_window_5xx_total');

// 정상 구간(phase:steady) 가용성 게이트. 창 안 5xx를 같은 분모에 넣으면 RTO가 0이 아닌 한
// 구조적으로 통과 불가다. 창 판정은 아래 chaos 서브메트릭이 맡는다.
const thresholds = {
  // 분자를 접수 요청(POST /orders)으로 좁힌다. scenario 단독 필터는 같은 시나리오의 by-client
  // 재조회까지 삼켜 규약상 정상인 409 requery_deferred·404 미접수 확인을 실패 분자로 만든다.
  // expectedStatuses 전역 추가는 쓰지 않는다. POST /orders의 duplicate_client_order_id·
  // order_in_progress까지 성공으로 계상돼 진짜 거절이 가려진다.
  'http_req_failed{scenario:orders_steady,name:placeOrder,phase:steady}': [`rate<${STEADY_FAIL_MAX}`], // 가용성 99.9%, 수용 제한 응답 제외
  // 재조회는 별도 임계로 관측을 유지한다. 200·404·409·429를 그 요청에 한해 정상으로 선언했으므로
  // (lookupByClient의 responseCallback) 이 분자에는 5xx·타임아웃만 남는다. 정상 창 한정인 것은
  // 카오스 창에서는 재조회 5xx가 정상 재시도 대상이기 때문이다. 그 창의 규약 위반은 이 임계가
  // 아니라 idempotent_safe가 잡는다(전역 rate==1).
  'http_req_failed{name:lookup,phase:steady}': ['rate<0.001'],
  // 지연 SLO도 정상 구간 한정이다. 가용성 축과 같은 결함 클래스라 함께 좁힌다. 창에서는
  // 성공하되 느린 2xx(E1 지연 주입, E7 그레이 장애, 승격 직후 큐 해소)가 상위 백분위를 점유해
  // 정상 구간 SLO를 깬다. 창의 지연은 아래 phase:chaos 서브메트릭으로 관측한다.
  'accepted_req_duration{scenario:orders_steady,phase:steady}': ['p(95)<200', 'p(99)<500'],
  // 양성 증거. 지연 임계도 http_req_failed도 표본 0에서 통과하고 429는 어느 축에도
  // 남지 않아, 주문이 전량 429인 주행이 HTTP 축 전건을 통과한다(실측).
  // 하한은 STEADY_RATE와 주행 길이에서 유도한다(데모 프로파일 9,000건의 10%).
  'accepted_total{phase:steady,replay:false}': ['count>900'], // 수용 요청 SLO(API§7-4)
  'checks{check:idempotent_safe}': ['rate==1'],   // UNKNOWN→재조회→재전송 규약 위반 0
  // 정상 구간 마켓 접수 경합 상한(하네스 검증설계 §2-2, S4 설계 4-21). 경합 429 는 가용성 분자에서 빠지므로 이 비율이 대신 본다.
  // 창 안의 값은 기록만 한다.
  'market_contention{phase:steady}': ['rate<0.001'],
  // API§5-2가 seq 도메인의 두 실현을 나눈다. orderbook은 채널별 조밀 카운터라 결번이 곧
  // 유실이고, trade는 전역 event_seq라 단일 마켓 구독자에게 결번이 정상이다.
  // 그래서 결번 0은 orderbook 채널만 요구한다.
  'ws_seq_gap{phase:steady,dest:orderbook}': ['count==0'],
  // 폐기율. 계약이 seq <= 기준점 폐기를 지시하므로 폐기 자체는 실패가 아니다. 다만 상시
  // 폐기는 전달 계층 재정렬 신호다. orderbook은 구독 경계 폐기가 정상이라 기저가 있고
  // trade는 기저가 0이라, 예산을 섞으면 trade 이상이 orderbook 정상분에 흡수된다.
  'ws_stale_discarded{phase:steady,dest:orderbook}': ['count<200'],
  'ws_stale_discarded{phase:steady,dest:trade}': ['count<10'],
  // 양성 증거. 위 임계가 전부 단측이라 표본 0이면 채널이 죽어도 초록이 된다. 실패 축과
  // 같은 dest로 가르지 않으면 orderbook 전면 사망이 trade 표본에 가려진다.
  // 구독마다 스냅샷 1건이 별도 경로로 오므로 realtime 한정으로 센다. 하한 유도는 §2-4에 둔다.
  'ws_seq_observed{phase:steady,dest:orderbook,stream:realtime}': ['count>100'],
  'ws_seq_observed{phase:steady,dest:trade}': ['count>10'],
  'ws_epoch_resync_fail': ['count==0'],           // 카오스 창 밖 WS 연결 오류 0. 이름과 달리 재동기화 성패를 재지 않는다(위 선언부 주석)
  // 이중 신규 접수 0 검증은 서버측 C07'(command_journal NEW 축) 상시 검증이 전담한다(§4-1).
  // k6는 UNKNOWN 시 재조회만 하고 재전송 경로가 없어 dup를 만들 수 없으므로, 여기에 임계를
  // 두면 어떤 상태에서도 통과하는 토톨로지가 된다(§2-2).
};

// 카오스 창 임계. 창을 정상 구간 임계에서 뺀 것이 창을 안 본다는 뜻이 되면 안 된다.
// 상한은 실험 성격에 종속된다(§2-2 창 실패 예산). 페일오버 창은 RTO 목표에서 유도하고,
// 비페일오버 주입은 다운타임 허용이 없어 정상 상한을 쓴다.
if (Number.isFinite(CHAOS_WINDOW_S)) {
  let max = STEADY_FAIL_MAX;
  if (FAILOVER_WINDOW) {
    // 창 전체가 RTO 목표만큼 끊겨도 정본상 정상이므로 상한 = RTO 목표 / 창 길이.
    // 창이 RTO 예산 이하면 상한이 1 이상이 되어 어떤 값에도 통과하는 토톨로지가 되므로,
    // 그 설정은 창 길이가 잘못 잡힌 것으로 보고 중단한다(§2-2: T1 = T0 + RTO x 2).
    if (RTO_TARGET_S >= CHAOS_WINDOW_S) {
      throw new Error(`카오스 창(${CHAOS_WINDOW_S}s)이 RTO 목표(${RTO_TARGET_S}s) 이하라 창 임계가 토톨로지가 된다. 정본은 T1 = T0 + RTO x 2다(검증설계 §2-2).`);
    }
    max = (RTO_TARGET_S / CHAOS_WINDOW_S).toFixed(4);
  }
  thresholds['http_req_failed{scenario:orders_steady,name:placeOrder,phase:chaos}'] = [`rate<${max}`];
  console.log(`CHAOS_WINDOW|${JSON.stringify({ t0: T0, t1: T1, windowSec: CHAOS_WINDOW_S, failover: FAILOVER_WINDOW, rtoTargetSec: FAILOVER_WINDOW ? RTO_TARGET_S : null, chaosFailMax: max })}`);
} else if (FAILOVER_WINDOW) {
  // 창 없이 표식만 온 경우. 동작은 옳지만(창 표본이 없어 판정 대상이 없다) 조용히 버리면
  // 오케스트레이터가 창을 빠뜨린 사실이 리포트에 남지 않는다. 무시했다는 것을 기록한다.
  console.log('CHAOS_WINDOW|{"note":"FAILOVER_WINDOW 표식이 있으나 CHAOS_T0_S/T1_S가 없어 카오스 창 임계를 걸지 않았다. 창 표본이 생기지 않으므로 전 구간이 phase:steady로 판정된다."}');
}

// 개방 루프에서 VU가 모자라면 dropped_iterations가 뜨고, 그 순간 재는 대상이 서버가 아니라
// 부하기가 된다. 반대로 maxVUs < preAllocatedVUs면 k6가 기동 자체를 거부한다(실측: 종료코드
// 104. 낮은 OPS에서 상수 하한과 배수가 역전되며 주행이 통째로 실패했다). 두 값을 한 곳에서
// 함께 내 그 역전을 구조적으로 막는다. 하한·배수는 calibrate.js와 같다. 확정치를 낸 30분
// 주행이 그 부하기 구성으로 통과했으므로 정본 부하도 같은 구성을 쓴다.
function loaderVus(peakRate, floor, headroom) {
  const pre = Math.max(floor, peakRate);
  return { preAllocatedVUs: pre, maxVUs: Math.max(pre, peakRate * headroom) };
}

export const options = {
  scenarios: {
    orders_steady: Object.assign({
      executor: 'constant-arrival-rate', rate: STEADY_RATE, timeUnit: '1s',
      duration: P.total, exec: 'placeOrder',
    }, loaderVus(STEADY_RATE, 50, 6)),
    // startRate와 마지막 복귀 target은 STEADY_RATE에 묶는다. 이 시나리오는 orders_steady
    // 위에 겹쳐 주입되므로 램프 방향이 기저 대비 상승이어야 스파이크가 성립한다. 두 값을
    // 상수로 따로 두면 STEADY_RATE만 내렸을 때 옛 명목치가 남아 램프다운이 된다.
    // plateau 배수는 SPIKE_RATE / STEADY_RATE로 결정된다(확정 기본값 기준 10x).
    // 동시 주입이라 스파이크 밖 구간의 외부 발행량은 STEADY_RATE x 2다.
    orders_spike: Object.assign({
      executor: 'ramping-arrival-rate', startRate: STEADY_RATE, timeUnit: '1s',
      stages: [
        { target: SPIKE_RATE, duration: '30s' },
        { target: SPIKE_RATE, duration: P.plateau },  // 폭주 plateau, 이 창에 리더 kill 중첩
        { target: STEADY_RATE, duration: '30s' },
      ],
      exec: 'placeOrder', startTime: P.spikeStart,
    }, loaderVus(SPIKE_RATE, 50, 6)),
    market_reads: Object.assign({
      executor: 'constant-arrival-rate', rate: READ_RATE, timeUnit: '1s',
      duration: P.total, exec: 'readMarket',
    }, loaderVus(READ_RATE, 30, 3)),
    ws_stomp: {
      executor: 'constant-vus', vus: parseInt(__ENV.WS_VUS || '300', 10),
      duration: P.total, exec: 'wsSubscribe',
    },
  },
  thresholds,
};

// k6 기본은 429=실패다. 이 선언이 없으면 가용성 지표가 오염된다(§2-2). init 단계 의무.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 429));

// 마켓 접수 경합 429 인가(API 설계 §2-5-b). 표준 오류 바디(§2-3)의 error.detail.bucket 이 market 이다. 계정 토큰버킷 429 는
// bucket 이 다르다.
function isMarketContention(res) {
  try {
    const body = JSON.parse(res.body);
    const detail = body && body.error && body.error.detail;
    return !!(detail && detail.bucket === 'market');
  } catch (e) {
    return false;
  }
}

function phase() {
  const s = exec.instance.currentTestRunDuration / 1000;
  return (s >= T0 && s <= T1) ? 'chaos' : 'steady';
}

function creds() {
  return KEYS[(exec.vu.idInTest - 1) % KEYS.length];
}

// 주행 식별자. 필수다. 없으면 재주행이 이전 주행과 같은 client_order_id를 내고 서버가
// 멱등 재생(200)으로 처리해 그 요청이 엔진에 닿지 않는다(API§7-6). 기본값을 두지 않는
// 이유는 미주입 오염이 리포트 어디에도 남지 않기 때문이다. 주행 안에서는 고정이라
// 재시도 멱등 규약은 그대로다.
const RUN_ID = __ENV.RUN_ID;
if (!RUN_ID) {
  throw new Error('RUN_ID는 필수다. 없으면 재주행이 이전 주행의 client_order_id를 재사용하고, 서버가 그것을 멱등 재생(200)으로 처리해 요청이 엔진에 닿지 않는다(실측 78%). 주행마다 새 값을 준다.');
}
// 서버는 client_order_id를 [A-Za-z0-9_-]{1,36}으로 검증한다(OrderGateway.java:64). 허용 문자를
// 벗어난 RUN_ID는 주문 전량이 400 invalid_parameter가 되고, 그것은 부하 주행이 아니라 거절
// 주행이다. 길이 16 상한 근거: 'k6-' 3자 + RUN_ID 16자 + 구분자 2자 = 21자 고정. 남는 15자가
// VU 번호와 반복 카운터 몫이라 둘이 각각 7자리(9,999,999)여도 36자 안에 든다. 실제 주행은
// VU 3자리·반복 5자리 수준이다.
if (!/^[A-Za-z0-9_-]{1,16}$/.test(RUN_ID)) {
  throw new Error(`RUN_ID 형식 오류: "${RUN_ID}". [A-Za-z0-9_-] 1~16자여야 한다. 벗어나면 client_order_id가 서버 검증(OrderGateway [A-Za-z0-9_-]{1,36})에 걸려 주문 전량이 400이 된다.`);
}

// 결정론 없이 가격·수량만 변주. 체결 여부는 무결성 판정과 무관하므로 부하 형성만 목적.
function orderPayload() {
  const side = (exec.vu.iterationInInstance % 2 === 0) ? 'bid' : 'ask';
  const mk = MARKETS[(exec.vu.idInTest + exec.vu.iterationInInstance) % MARKETS.length];
  const rule = MARKET_RULES[mk] || MARKET_RULES['KRW-BTC'];
  const price = side === 'bid' ? rule.bid : rule.ask;
  // VARCHAR(36) 상한. RUN_ID 16자 가드가 이 조립의 최악 길이를 36자 안에 묶는다(위 참조).
  const coid = `k6-${RUN_ID}-${exec.vu.idInTest}-${exec.vu.iterationInInstance}`;
  return {
    // VU 마다 다른 마켓에서 시작해 한 마켓에 몰리지 않게 한다.
    clientOrderId: coid, market: mk, ordType: 'limit',
    price, side, timeInForce: 'GTC', volume: rule.volume,
  };
}

// by-client 재조회. UNKNOWN 상태머신(API§7-6): 200=존재(재전송 금지), 404=미접수(재전송 허용),
// 409 requery_deferred=판정 보류(재조회 버킷 백프레셔). 셋 다 규약상 정상 응답이다.
// responseCallback을 이 요청에만 붙이는 이유: 전역 expectedStatuses에 409를 넣으면 POST /orders의
// duplicate_client_order_id·order_in_progress까지 성공으로 계상돼 진짜 거절이 가려진다.
function lookupByClient(coid, c, ph) {
  const path = `/api/v1/orders/by-client/${coid}`;
  const auth = signRequest('GET', path, '', c.access_key, c.secret);
  const res = http.get(BASE + path, {
    headers: { Authorization: auth },
    tags: { name: 'lookup', phase: ph },
    // 429를 빠뜨리면 안 된다. 이 경로는 수용 게이트 면제지만 계정 토큰버킷(API§2-5) 대상이라
    // 429가 온다. 전역 콜백이 429를 성공으로 두는 규약(§2-2)을 요청별 콜백이 덮어써서는 안 된다.
    responseCallback: http.expectedStatuses({ min: 200, max: 399 }, 404, 409, 429),
  });
  return res.status;
}

export function placeOrder() {
  const c = creds();
  const o = orderPayload();
  const body = orderCanonicalBody(o);
  const path = '/api/v1/orders';
  const auth = signRequest('POST', path, body, c.access_key, c.secret);
  const ph = phase();
  const headers = { 'Content-Type': 'application/json', Authorization: auth };
  if (MARKET_HINT) {
    headers['X-Exchange-Market'] = o.market;
  }
  const res = http.post(BASE + path, body, {
    headers,
    tags: { name: 'placeOrder', phase: ph },
  });

  const contention = res.status === 429 && isMarketContention(res);
  marketContention.add(contention, { phase: ph });
  if (res.status >= 200 && res.status < 300) {
    acceptedReqDuration.add(res.timings.duration, { phase: ph });
    // 멱등 replay(200 + X-Idempotent-Replay)는 신규 접수가 아니므로 dup 계상 제외.
    const replay = String(res.headers['X-Idempotent-Replay'] || '').toLowerCase() === 'true';
    // replay를 태그로 가른다. 2xx 전체를 세면 RUN_ID를 재사용한 주행이 전량 재생 200이
    // 되는데도 양성 증거가 채워진다. 그 요청은 엔진에 닿지 않으므로 용량을 증명하지
    // 못한다(§2-4 C7이 규정한 실패 클래스). 임계는 replay:false로 건다.
    acceptedTotal.add(1, { phase: ph, replay: replay ? 'true' : 'false' });
    let oid = '';
    try { oid = JSON.parse(res.body).id || ''; } catch (e) { oid = ''; }
    if (!replay) {
      // §2-5 파이프라인이 stdout ACK 라인을 loadtest_acks로 적재(C16 원천).
      console.log('ACK|' + JSON.stringify({ ak: c.access_key, coid: o.clientOrderId, oid, t: Date.now() }));
    }
    check(res, { idempotent_safe: () => true });
    return;
  }

  if (res.status === 429) {
    if (contention) {
      marketContentionTotal.add(1, { phase: ph });
    } else {
      rateLimitedTotal.add(1);
    }
    // 토큰버킷 증거는 Retry-After 100% 동반이어야 정상(§2-3).
    check(res, { retry_after_present: (r) => r.headers['Retry-After'] !== undefined });
    return;
  }

  // 5xx/timeout(status 0) = UNKNOWN. 재조회로 안전 판정한다. 재조회 없이 재전송하면 이중 접수.
  if (res.status >= 500 || res.status === 0) {
    // 페일오버 창 실패는 위 가용성 임계에서 빠지므로 여기에 별도로 남긴다. 창을 제외한다는 것이
    // 관측을 버린다는 뜻이 되면 안 된다. 창 판정은 phase:chaos 서브메트릭 임계가 맡는다.
    if (ph === 'chaos') { chaosWindow5xx.add(1); }
    const look = lookupByClient(o.clientOrderId, c, ph);
    // 200=이미 접수(응답만 유실, 재전송 금지), 404=미접수(안전 재전송 가능). 어느 쪽이든
    // 이중 접수를 만들지 않으면 규약 준수. 409(내용 상이 충돌)도 재전송 아님이라 safe로 본다.
    const safe = (look === 200 || look === 404 || look === 409);
    check(null, { idempotent_safe: () => safe });
    return;
  }

  // 4xx(429 제외)는 도메인 거절(정상 경로). 규약 위반은 아니다.
  check(res, { idempotent_safe: () => true });
}

export function readMarket() {
  const ph = phase();
  const res = http.get(`${BASE}/api/v1/orderbook?market=${MARKET}`, {
    tags: { name: 'readMarket', phase: ph },
  });
  check(res, { ob_ok: (r) => r.status === 200 || r.status === 429 });
}

// STOMP 공개 채널 구독 후 seq 결번·epoch 변화를 계측한다. 카오스 창 gap은 재동기화로
// 흡수(ws_epoch_resync_fail==0으로 판정), steady 창 결번은 orderbook 채널만 0이어야 한다(§2-2, API§5-2 seq 도메인 두 실현).
export function wsSubscribe() {
  const seen = {};
  let lastEpoch = null;
  const ws = new WebSocket(WS_URL);

  ws.onopen = () => {
    ws.send(connectFrame(HOST));
    ws.send(subscribeFrame('sub-ob', topicOrderbook(MARKET)));
    ws.send(subscribeFrame('sub-tr', topicTrade(MARKET)));
  };

  ws.onmessage = (e) => {
    const frames = parseFrames(e.data);
    for (const f of frames) {
      if (f.command !== 'MESSAGE') {
        continue;
      }
      let msg;
      try { msg = JSON.parse(f.body); } catch (err) { continue; }
      const dest = f.headers.destination || 'unknown';
      const ph = phase();

      // epoch 변화(리더 교대) 감지 → 재동기화 기대. 실패 카운트는 전역 0이어야 한다.
      if (msg.epoch !== undefined) {
        if (lastEpoch !== null && msg.epoch !== lastEpoch) {
          // 재동기화 규약(API§5-2): epoch 변화 시 seq 기준선을 리셋한다. 리셋 실패(음수 점프
          // 지속)만 fail로 계상. 여기서는 기준선 재설정으로 정상 흡수한다.
          for (const k in seen) { delete seen[k]; }
        }
        lastEpoch = msg.epoch;
      }

      if (msg.seq !== undefined) {
        const prev = seen[dest];
        // 구독 즉시 계약(API§5-2). 기준점은 마지막으로 채택한 seq이고 epoch 변화 시 비운다.
        // 스냅샷과 realtime의 순서 역전은 계약상 정상이라 낮은 seq는 채택하지 않는다.
        // 채택하면 표시 상태가 되돌아가고 이어지는 realtime이 전부 결번으로 보인다.
        const kind = destKind(dest);
        if (prev !== undefined && msg.seq <= prev) {
          // 계약이 두 채널 모두에 폐기를 지시한다(API§5-2). 기준점을 전진시키지 않는 것도
          // 계약이다. 뒤처진 값을 기준으로 삼으면 이후 메시지가 전부 결번으로 보인다.
          // 실패로 계상하면 정상 거동이 실패가 되고 재정렬 이후가 통째로 실패로 번진다.
          wsStaleDiscarded.add(1, { phase: ph, stream: msg.stream || 'unknown', dest: kind });
          continue;
        }
        if (prev !== undefined && msg.seq > prev + 1) {
          if (ph === 'chaos') {
            // 카오스 창 gap은 무계상이다. 기준선만 전진시킨다. 그 창의 WS 판정 축은
            // 없다(검증설계 §2-4 미해소 항). ws_epoch_resync_fail은 재동기화를 재지 않는다.
          } else {
            // destination 태그가 있어야 채널별 판정이 선다. orderbook seq는 채널별 연속이라
            // 결번이 곧 유실이지만, trade seq는 전역 체결 번호라 다른 마켓 체결이 번호를
            // 소비한다. 단일 마켓 구독자에게 그 결번은 정상이고 한 축으로 세면 상시 실패한다.
            wsSeqGap.add(1, { phase: ph, dest: kind });
          }
        }
        seen[dest] = msg.seq;
        // stream 태그가 있어야 양성 증거를 realtime 한정으로 셀 수 있다. 스냅샷까지 세면
        // realtime 발행이 죽어도 구독 수만큼 하한이 채워진다.
        wsSeqObserved.add(1, { phase: ph, dest: kind, stream: msg.stream || 'realtime' });
      }
    }
  };

  ws.onerror = () => {
    // 카오스 창 밖의 연결 오류는 재동기화 실패로 계상(전역 threshold).
    if (phase() !== 'chaos') {
      wsEpochResyncFail.add(1);
    }
  };

  // 전역 setTimeout을 쓴다. k6/experimental/websockets의 WebSocket에는 setTimeout이 없어
  // ws.setTimeout을 부르면 첫 호출에서 iteration이 중단되고, onmessage가 한 번도 돌지 않아
  // count==0 임계 둘이 어떤 상태에서도 통과한다. 수명은 구독 폭주 재현용으로만 연다.
  setTimeout(() => { ws.close(); }, parseInt(__ENV.WS_HOLD_MS || '30000', 10));
}
