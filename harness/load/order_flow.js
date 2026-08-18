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
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { signRequest, orderCanonicalBody } from './lib/sign.js';
import { WebSocket, connectFrame, subscribeFrame, parseFrames, topicOrderbook, topicTrade } from './lib/stomp.js';

const BASE = __ENV.BASE_URL || 'http://localhost:58080';
const WS_URL = __ENV.WS_URL || BASE.replace(/^http/, 'ws') + '/ws/v1';
const HOST = __ENV.API_HOST || 'api.exchange.local';
const MARKET = __ENV.MARKET || 'KRW-BTC';
const SPIKE_RATE = parseInt(__ENV.SPIKE_RATE || '3000', 10);
const T0 = parseFloat(__ENV.CHAOS_T0_S || 'Infinity');
const T1 = parseFloat(__ENV.CHAOS_T1_S || 'Infinity');
// 페일오버 창 임계 유도용. 창 전체가 RTO 목표만큼 끊겨도 정본상 정상이므로 창 실패율 상한은
// RTO 목표 / 창 길이다. 임의 상수를 쓰지 않기 위해 오케스트레이터가 준 값에서 계산한다.
const RTO_TARGET_S = parseFloat(__ENV.RTO_TARGET_S || '30');
const CHAOS_WINDOW_S = T1 - T0;

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
const wsSeqGap = new Counter('ws_seq_gap');             // 채널 seq 결번(steady 창 0)
const wsEpochResyncFail = new Counter('ws_epoch_resync_fail'); // 재동기화 실패(전역 0)
// 페일오버 창 접수 실패(5xx·타임아웃) 건수. 아래 가용성 임계에서 창을 제외하는 대신 이 축으로
// 남겨 관측을 잃지 않는다. 창 실패는 페일오버의 정의상 결과라 정상 구간과 같은 분모로 재면 안 된다.
const chaosWindow5xx = new Counter('chaos_window_5xx_total');

// 정상 구간(phase:steady) 가용성 게이트. 종전에는 phase 필터가 없어 페일오버를 포함하는 run에서
// 창 안 5xx가 그대로 분자에 들어갔고, 그러면 이 임계는 구조적으로 통과 불가다(RTO가 0이 아닌 한
// 창 동안 접수는 반드시 실패한다). 창의 판정은 아래 chaos 서브메트릭이 따로 맡는다.
const thresholds = {
  // 분자를 접수 요청(POST /orders)으로 좁힌다. 종전 scenario 단독 필터는 같은 시나리오 안에서
  // 일어나는 by-client 재조회까지 삼켰다. 재조회의 409 requery_deferred는 판정 보류(백프레셔)이고
  // 404는 미접수 확인이라 둘 다 규약상 정상 경로인데, 가용성 임계의 실패 분자로 계상됐다.
  // expectedStatuses에 409를 전역 추가하는 방식은 쓰지 않는다. POST /orders의
  // duplicate_client_order_id·order_in_progress까지 성공으로 계상돼 진짜 거절이 가려진다.
  'http_req_failed{scenario:orders_steady,name:placeOrder,phase:steady}': ['rate<0.001'], // 가용성 99.9%, 수용 제한 응답 제외
  // 재조회는 별도 임계로 관측을 유지한다. 200·404·409·429를 그 요청에 한해 정상으로 선언했으므로
  // (lookupByClient의 responseCallback) 이 분자에는 5xx·타임아웃만 남는다. 정상 창 한정인 것은
  // 카오스 창에서는 재조회 5xx가 정상 재시도 대상이기 때문이다. 그 창의 규약 위반은 이 임계가
  // 아니라 idempotent_safe가 잡는다(전역 rate==1).
  'http_req_failed{name:lookup,phase:steady}': ['rate<0.001'],
  'accepted_req_duration{scenario:orders_steady}': ['p(95)<200', 'p(99)<500'], // 수용 요청 SLO(API§7-4)
  'checks{check:idempotent_safe}': ['rate==1'],   // UNKNOWN→재조회→재전송 규약 위반 0
  'ws_seq_gap{phase:steady}': ['count==0'],       // 정상 창 seq 결번 0(카오스 창은 phase:chaos 제외)
  'ws_epoch_resync_fail': ['count==0'],           // epoch 변화·gap 후 재동기화 실패 0(전역)
  // 이중 신규 접수 0 검증은 서버측 C07'(command_journal NEW 축) 상시 검증이 전담한다(§4-1).
  // k6는 UNKNOWN 시 재조회만 하고 재전송 경로가 없어 dup를 만들 수 없으므로, 여기에 임계를
  // 두면 어떤 상태에서도 통과하는 토톨로지가 된다(§2-2).
};

// 페일오버 창 임계. 상한을 RTO 목표에서 유도한다: 창 전체가 RTO 목표만큼 끊겨도 정상이므로
// 실패율 상한 = RTO 목표 / 창 길이. 창이 RTO 예산보다 짧으면 상한이 1 이상이라 판별력이 없으므로
// 임계를 걸지 않는다(어떤 값에도 통과하는 토톨로지 방지). 카오스 창 미설정(T0/T1 부재) 시에도
// 표본이 없어 공허 통과가 되므로 선언하지 않는다.
if (Number.isFinite(CHAOS_WINDOW_S) && CHAOS_WINDOW_S > 0 && RTO_TARGET_S < CHAOS_WINDOW_S) {
  const max = (RTO_TARGET_S / CHAOS_WINDOW_S).toFixed(4);
  thresholds['http_req_failed{scenario:orders_steady,name:placeOrder,phase:chaos}'] = [`rate<${max}`];
}

export const options = {
  scenarios: {
    orders_steady: {
      executor: 'constant-arrival-rate', rate: 300, timeUnit: '1s',
      duration: P.total, preAllocatedVUs: 200, exec: 'placeOrder',
    },
    orders_spike: {
      executor: 'ramping-arrival-rate', startRate: 300, timeUnit: '1s', preAllocatedVUs: 1500,
      stages: [
        { target: SPIKE_RATE, duration: '30s' },     // 10x 램프업(상한 = §2-4 캘리브레이션)
        { target: SPIKE_RATE, duration: P.plateau },  // 폭주 plateau, 이 창에 리더 kill 중첩
        { target: 300, duration: '30s' },
      ],
      exec: 'placeOrder', startTime: P.spikeStart,
    },
    market_reads: {
      executor: 'constant-arrival-rate', rate: 500, timeUnit: '1s',
      duration: P.total, preAllocatedVUs: 100, exec: 'readMarket',
    },
    ws_stomp: {
      executor: 'constant-vus', vus: parseInt(__ENV.WS_VUS || '300', 10),
      duration: P.total, exec: 'wsSubscribe',
    },
  },
  thresholds,
};

// k6 기본은 429=실패다. 이 선언이 없으면 가용성 지표가 오염된다(§2-2). init 단계 의무.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 429));

function phase() {
  const s = exec.instance.currentTestRunDuration / 1000;
  return (s >= T0 && s <= T1) ? 'chaos' : 'steady';
}

function creds() {
  return KEYS[(exec.vu.idInTest - 1) % KEYS.length];
}

// 결정론 없이 가격·수량만 변주. 체결 여부는 무결성 판정과 무관하므로 부하 형성만 목적.
function orderPayload() {
  const side = (exec.vu.iterationInInstance % 2 === 0) ? 'bid' : 'ask';
  const price = side === 'bid' ? '49900000' : '50100000';
  const coid = `k6-${exec.vu.idInTest}-${exec.vu.iterationInInstance}`;
  return {
    clientOrderId: coid, market: MARKET, ordType: 'limit',
    price, side, timeInForce: 'GTC', volume: '0.0010',
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
  const res = http.post(BASE + path, body, {
    headers: { 'Content-Type': 'application/json', Authorization: auth },
    tags: { name: 'placeOrder', phase: ph },
  });

  if (res.status >= 200 && res.status < 300) {
    acceptedReqDuration.add(res.timings.duration, { phase: ph });
    // 멱등 replay(200 + X-Idempotent-Replay)는 신규 접수가 아니므로 dup 계상 제외.
    const replay = String(res.headers['X-Idempotent-Replay'] || '').toLowerCase() === 'true';
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
    rateLimitedTotal.add(1);
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
// 흡수(ws_epoch_resync_fail==0으로 판정), steady 창 gap은 0이어야 한다(§2-2).
export function wsSubscribe() {
  const seen = {};        // destination → 마지막 seq
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
        if (prev !== undefined && msg.seq > prev + 1) {
          if (ph === 'chaos') {
            // 카오스 창 gap은 재동기화로 흡수. 기준선만 전진시키고 실패로 계상하지 않는다.
            // 재동기화조차 실패하면(구현상 후속 gap 연속) 아래 resync fail 경로가 잡는다.
          } else {
            wsSeqGap.add(1, { phase: ph });
          }
        }
        seen[dest] = msg.seq;
      }
    }
  };

  ws.onerror = () => {
    // 카오스 창 밖의 연결 오류는 재동기화 실패로 계상(전역 threshold).
    if (phase() !== 'chaos') {
      wsEpochResyncFail.add(1);
    }
  };

  // VU 수명 동안 구독 유지. 채널 이벤트를 관찰하고 종료한다.
  ws.setTimeout(() => { ws.close(); }, 30000);
}
