// 부하 주행 전 사전 점검. 서명·키·경로·coid 규약·잔고 헤드룸을 1회 확인한다.
// 기능 실패와 용량 실패가 같은 지표에 합산되면 튜닝 대상을 오판한다(검증설계 §2-4 5항).
// 낸 주문은 전부 취소한다. GTC로 두면 호가창에 쌓이고 잠금이 풀리지 않는다.
// checks 임계로 종료코드를 만들어 호출자가 즉시 안다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { signRequest, orderCanonicalBody } from './lib/sign.js';

const BASE = __ENV.BASE_URL || 'http://localhost:58080';
const MARKET = __ENV.MARKET || 'KRW-BTC';
// 주문 생성과 by-client 취소의 마켓 힌트 헤더(API 설계 §2-5-d, ADR 0034 S5). 기본 1, 0 이면 싣지 않는다. order_flow.js 와 같다.
const MARKET_HINT = String(__ENV.MARKET_HINT || '1') !== '0';

function hinted(headers) {
  if (MARKET_HINT) {
    headers['X-Exchange-Market'] = MARKET;
  }
  return headers;
}
const KEYS = JSON.parse(open(__ENV.K6_KEYS || './secrets/k6_keys.json'));

// 마켓 심볼은 quote-base 순이다(markets 뷰 실측: KRW-BTC = quote KRW, base BTC).
const QUOTE = MARKET.split('-')[0];
const BASE_ASSET = MARKET.split('-')[1];
// assets.scale 정본. quote KRW는 0, base BTC는 8이다. 잔고를 이 자리수의 정수로 환산해
// 비교한다. 부동소수 연산으로 금액을 다루지 않기 위해서다.
const QUOTE_SCALE = parseInt(__ENV.CHECK_QUOTE_SCALE || '0', 10);
const BASE_SCALE = parseInt(__ENV.CHECK_BASE_SCALE || '8', 10);

// 본 주행 식별자. 뒤따를 주행과 같은 값을 받아야 여기서 검증한 coid 규약이 그 주행의 규약이
// 된다. order_flow.js와 같이 기본값을 두지 않는다. 기본값으로 때우면 프리플라이트가 본 주행이
// 실제로 쓸 키 공간이 아니라 자기 키 공간을 검증하고 통과한다.
const RUN_ID = __ENV.RUN_ID;
if (!RUN_ID) {
  throw new Error('RUN_ID는 필수다. 뒤따를 주행과 같은 값을 준다. 없으면 프리플라이트가 본 주행의 client_order_id 공간을 검증하지 못한다(order_flow.js와 같은 계약).');
}
// order_flow.js의 RUN_ID 규칙을 그대로 쓴다. 여기가 더 느슨하면 프리플라이트를 통과한 RUN_ID로
// 본 주행이 init에서 죽는다. 두 파일에 같은 규칙이 사는 것은 k6 진입점이 둘이기 때문이고,
// 정본은 order_flow.js다.
const RUN_ID_RULE = /^[A-Za-z0-9_-]{1,16}$/;
// OrderGateway.place의 서버측 규칙. 위반은 400 invalid_parameter다.
const COID_RULE = /^[A-Za-z0-9_-]{1,36}$/;
// 본 주행 coid = k6-<RUN_ID>-<vuId>-<iter>(order_flow.js orderPayload). 숫자 두 자리의 최악값은
// order_flow.js가 각 7자리로 잡았다. 그 최악 조립이 서버 규칙을 통과하는지 실제 문자열로 잰다.
// 위 RUN_ID_RULE과 겹치지만 겹침을 남긴다. RUN_ID_RULE은 본 주행 init 가드를 그대로 옮긴 것이고
// 이쪽은 서버 규칙에 대한 조립 검사라, 조립 규약이 바뀌면 이쪽만 반응해야 한다.
const WORST_COID = `k6-${RUN_ID}-9999999-9999999`;

// 본 주행 규모. 헤드룸을 이 값에서 유도한다. 기본값은 확정치(2026-08-29 캘리브레이션,
// harness.calibration_runs id=1) 기저 10 OPS·30분 주행이다. 금액이 아니라 건수 산출 입력이다.
// 스파이크가 겹치는 주행(order_flow.js orders_spike)은 그 몫을 더한 실효 발행률을 넣는다.
// 여기 STEADY_RATE만 넣으면 헤드룸을 실제보다 낮게 잡는다.
const CHECK_OPS = parseFloat(__ENV.CHECK_OPS || '10');
const CHECK_DURATION_SEC = parseInt(__ENV.CHECK_DURATION_SEC || '1800', 10);
// order_flow.js는 iteration 홀짝으로 매수·매도를 교대한다. 계정별로도 절반씩 갈린다.
const BID_PERCENT = parseInt(__ENV.CHECK_BID_PERCENT || '50', 10);
// 1건당 잠금. 매수는 price x volume, 매도는 volume이다(order_flow.js orderPayload의
// 49,900,000 x 0.0010과 0.0010). 곱을 여기서 다시 계산하지 않는 이유는 quote scale
// 올림(ParsedOrder.reserve)이 서버 쪽 규칙이라 클라이언트 곱셈과 어긋날 수 있어서다.
const LOCK_QUOTE = __ENV.CHECK_LOCK_QUOTE || '49900';
const LOCK_BASE = __ENV.CHECK_LOCK_BASE || '0.0010';

// 프리플라이트 주문. 체결되지 않을 가격이라 체결로 잔고가 움직이지는 않지만, 접수되는 순간
// price x volume = 10,000 KRW가 잠긴다. 잠금은 취소로만 풀린다.
// 최소 주문 금액(API 정본 5,000원)은 넘겨야 한다. 안 넘기면 under_min_order 400이 나서
// 프리플라이트가 서명·키가 아니라 자기 파라미터를 실패로 보고한다.
const PF_PRICE = __ENV.CHECK_PF_PRICE || '1000000';
const PF_VOLUME = __ENV.CHECK_PF_VOLUME || '0.01';
const CANCEL_ATTEMPTS = parseInt(__ENV.CHECK_CANCEL_ATTEMPTS || '5', 10);

const MAX_SAFE = 9007199254740991;

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1.0'] },
};

// 십진 문자열을 scale 자리 정수로 환산한다. 응답은 ApiFormats.amount의
// stripTrailingZeros().toPlainString()이라 지수 표기가 없다. 초과 소수부는 절사한다.
// 보유는 낮게, 필요는 높게 잡는 쪽이 헤드룸 판정에서 안전한 방향이다.
// 안전 정수 범위를 넘으면 비교가 조용히 틀리므로 null로 돌려 판정 불가를 드러낸다.
function toScaled(amount, scale) {
  const s = String(amount === undefined || amount === null ? '0' : amount).trim();
  if (!/^-?\d+(\.\d+)?$/.test(s)) {
    return null;
  }
  const neg = s.charAt(0) === '-';
  const abs = neg ? s.slice(1) : s;
  const dot = abs.indexOf('.');
  const intPart = dot < 0 ? abs : abs.slice(0, dot);
  let frac = dot < 0 ? '' : abs.slice(dot + 1);
  if (frac.length > scale) {
    frac = frac.slice(0, scale);
  }
  while (frac.length < scale) {
    frac += '0';
  }
  const v = parseInt((intPart === '' ? '0' : intPart) + (scale > 0 ? frac : ''), 10);
  if (!isFinite(v) || Math.abs(v) > MAX_SAFE) {
    return null;
  }
  return neg ? -v : v;
}

// 계정당 필요 헤드룸. 주행 전체 주문 수를 키 수로 나눈 뒤 매수·매도 몫에 잠금단가를 곱한다.
// order_flow.js creds()가 VU를 키에 라운드로빈하므로 계정별 몫은 균등하다.
function requirement() {
  const totalOrders = Math.ceil(CHECK_OPS * CHECK_DURATION_SEC);
  const perKey = Math.ceil(totalOrders / KEYS.length);
  const bids = Math.ceil((perKey * BID_PERCENT) / 100);
  const asks = perKey - bids;
  const lockQuote = toScaled(LOCK_QUOTE, QUOTE_SCALE);
  const lockBase = toScaled(LOCK_BASE, BASE_SCALE);
  if (lockQuote === null || lockBase === null) {
    return null;
  }
  const quote = bids * lockQuote;
  const base = asks * lockBase;
  if (quote > MAX_SAFE || base > MAX_SAFE) {
    return null;
  }
  return { totalOrders, perKey, bids, asks, quote, base };
}

// 서명 GET /api/v1/accounts. 응답은 PageResponse 엔벌로프이고 행의 balance가 available이다.
function fetchBalances(c) {
  const path = '/api/v1/accounts';
  const res = http.get(BASE + path, {
    headers: { Authorization: signRequest('GET', path, '', c.access_key, c.secret) },
    tags: { name: 'accounts' },
  });
  if (res.status !== 200) {
    return { ok: false, detail: `status=${res.status} body=${String(res.body).slice(0, 200)}` };
  }
  let rows;
  try {
    rows = JSON.parse(res.body).data;
  } catch (e) {
    return { ok: false, detail: `본문 파싱 실패 body=${String(res.body).slice(0, 200)}` };
  }
  if (!rows || typeof rows.length !== 'number') {
    return { ok: false, detail: 'data 배열 없음' };
  }
  // 행이 없는 자산은 0으로 본다. 미개설을 판정 제외로 두면 헤드룸이 아예 없는 계정이 통과한다.
  let quote = 0;
  let base = 0;
  for (let i = 0; i < rows.length; i++) {
    if (rows[i].currency === QUOTE) {
      quote = toScaled(rows[i].balance, QUOTE_SCALE);
    } else if (rows[i].currency === BASE_ASSET) {
      base = toScaled(rows[i].balance, BASE_SCALE);
    }
  }
  if (quote === null || base === null) {
    return { ok: false, detail: '잔고 문자열을 정수로 환산하지 못했다(자리수 초과 또는 형식 위반)' };
  }
  return { ok: true, quote, base };
}

// by-client 취소(§4-B-2). 409 order_in_progress는 저널에는 있으나 프로젝션 미반영이라는
// 뜻이라 재시도로 수렴한다(OrderGateway.cancelByClient). 한 번에 끊으면 프리플라이트가
// 자기 잔여를 남긴 채 통과한다.
function cancelByClient(coid, c) {
  const path = `/api/v1/orders/by-client/${coid}`;
  let last = null;
  for (let a = 0; a < CANCEL_ATTEMPTS; a++) {
    const res = http.del(BASE + path, null, {
      headers: hinted({ Authorization: signRequest('DELETE', path, '', c.access_key, c.secret) }),
      tags: { name: 'cancel' },
    });
    last = res;
    if (res.status !== 409) {
      break;
    }
    if (a < CANCEL_ATTEMPTS - 1) {
      sleep(0.3);
    }
  }
  if (!last) {
    return { ok: false, detail: '취소 요청을 보내지 못했다' };
  }
  if (last.status === 404) {
    // 저널에도 없음 = 접수되지 않았다. 잔여가 생기지 않았으므로 잔여 없음은 성립한다.
    return { ok: true, status: 'NOT_FOUND' };
  }
  if (last.status !== 200) {
    return { ok: false, detail: `status=${last.status} body=${String(last.body).slice(0, 200)}` };
  }
  let status = '';
  try {
    status = JSON.parse(last.body).status || '';
  } catch (e) {
    return { ok: false, detail: `취소 응답 파싱 실패 body=${String(last.body).slice(0, 200)}` };
  }
  return { ok: status === 'CANCELED', status, detail: `취소 후 status=${status}` };
}

export default function () {
  // 키가 0개면 아래 루프가 통째로 돌지 않아 어떤 키도 검증하지 않은 채 통과한다.
  check(null, { '부하 키 존재': () => KEYS.length > 0 });

  // 본 주행이 만들 최악 길이의 coid가 서버 규칙을 통과하는지 먼저 본다. 주행이 시작된 뒤
  // 전량 400으로 드러나면 그 주행은 통째로 버린다.
  check(null, { 'RUN_ID 형식': () => RUN_ID_RULE.test(RUN_ID) });
  check(null, { 'coid 규약(최악 길이)': () => COID_RULE.test(WORST_COID) });
  if (!RUN_ID_RULE.test(RUN_ID) || !COID_RULE.test(WORST_COID)) {
    console.error(`coid 규약 위반: RUN_ID "${RUN_ID}"(${RUN_ID.length}자), ` +
      `최악 조립 "${WORST_COID}"(${WORST_COID.length}자). ` +
      'RUN_ID는 [A-Za-z0-9_-] 1~16자여야 한다.');
  }

  const need = requirement();
  check(null, { '헤드룸 산출 가능': () => need !== null });
  if (need) {
    console.log(`헤드룸 요건: 주행 ${CHECK_DURATION_SEC}s x ${CHECK_OPS} OPS = ${need.totalOrders}건, ` +
      `키 ${KEYS.length}개 기준 계정당 ${need.perKey}건(매수 ${need.bids}·매도 ${need.asks}). ` +
      `필요 ${QUOTE} ${need.quote}, ${BASE_ASSET} ${need.base}(scale ${BASE_SCALE} 정수)`);
  }

  // 조회부터 본다. 여기서 막히면 서명이 아니라 경로·LB 문제다.
  const ob = http.get(`${BASE}/api/v1/orderbook?market=${MARKET}`, { tags: { name: 'orderbook' } });
  check(ob, { '조회 200': (r) => r.status === 200 });
  if (ob.status !== 200) {
    console.error(`조회 실패 status=${ob.status} body=${String(ob.body).slice(0, 200)}`);
  }

  // 키를 전부 돌린다. 하나만 보면 일부만 실효인 상태를 놓친다.
  for (let i = 0; i < KEYS.length; i++) {
    const c = KEYS[i];
    const label = `키${i}(${c.access_key.slice(0, 8)}...)`;

    const bal = fetchBalances(c);
    check(null, { [`키${i} 잔고 조회`]: () => bal.ok });
    if (!bal.ok) {
      console.error(`${label} 잔고 조회 실패 ${bal.detail}`);
    }
    // 조회 실패를 헤드룸 통과로 접으면 프리플라이트가 자기 눈을 가린다. 미판정은 실패로 낸다.
    const headroomOk = bal.ok && need !== null;
    check(null, {
      [`키${i} ${QUOTE} 헤드룸`]: () => headroomOk && bal.quote >= need.quote,
    });
    check(null, {
      [`키${i} ${BASE_ASSET} 헤드룸`]: () => headroomOk && bal.base >= need.base,
    });
    if (headroomOk && (bal.quote < need.quote || bal.base < need.base)) {
      console.error(`${label} 헤드룸 부족 ${QUOTE} ${bal.quote}/${need.quote}, ` +
        `${BASE_ASSET} ${bal.base}/${need.base}. 주행 중 400 insufficient_funds로 처리율이 내려간다.`);
    }

    // 본 주행과 같은 규약의 coid를 쓴다. vu·iter 자리에 pf를 넣어 본 주행 키 공간과 겹치지
    // 않게 한다. 겹치면 본 주행이 그 키를 멱등 재생(200)으로 처리해 엔진에 닿지 않는다.
    const coid = `k6-${RUN_ID}-pf-${i}`;
    const o = {
      clientOrderId: coid, market: MARKET, ordType: 'limit', price: PF_PRICE,
      side: 'bid', timeInForce: 'GTC', volume: PF_VOLUME,
    };
    const body = orderCanonicalBody(o);
    const path = '/api/v1/orders';
    const res = http.post(BASE + path, body, {
      headers: hinted({
        'Content-Type': 'application/json',
        Authorization: signRequest('POST', path, body, c.access_key, c.secret),
      }),
      tags: { name: 'placeOrder' },
    });
    // 429는 정상이다. 수용 게이트가 살아 있다는 뜻이지 설정 오류가 아니다.
    const accepted = res.status === 201 || res.status === 200;
    const ok = accepted || res.status === 429;
    check(res, { [`키${i} 주문 접수`]: () => ok });
    if (!ok) {
      console.error(`${label} status=${res.status} body=${String(res.body).slice(0, 240)}`);
    }

    // 접수된 주문만 취소 대상이다. 429는 접수 자체가 없어 잔여가 생기지 않는다.
    if (accepted) {
      const cancel = cancelByClient(coid, c);
      check(null, { [`키${i} 잔여 취소`]: () => cancel.ok });
      if (!cancel.ok) {
        console.error(`${label} 취소 실패 ${cancel.detail}. 잔여 주문이 호가창과 잠금에 남는다.`);
      }
    }
  }
}
