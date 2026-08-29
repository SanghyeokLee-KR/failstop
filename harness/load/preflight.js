// 부하 주행 전 사전 점검. 서명·키·주문 경로가 실제로 통과하는지 1회 확인한다.
//
// 있는 이유. k6 SLO 임계가 실패했을 때 그것이 용량 부족인지 설정 오류인지 구분되지 않으면
// 엉뚱한 곳을 튜닝하게 된다. 실측 사례 둘이 근거다.
//   secrets/k6_keys.json이 현 DB에 없는 키를 담고 있어 주문 2,000건 전량 401. 실패율
//   37%가 용량 문제로 보였다(2026-08-29).
//   LB가 재생성된 api 컨테이너의 옛 IP를 붙잡아 전 요청 502. 같은 날.
// 둘 다 이 스크립트 1회 실행이면 30초에 드러난다.
//
// checks 임계로 종료코드를 만든다. 실패 시 k6가 비영으로 끝나 호출자가 즉시 안다.
import http from 'k6/http';
import { check } from 'k6';
import { signRequest, orderCanonicalBody } from './lib/sign.js';

const BASE = __ENV.BASE_URL || 'http://localhost:58080';
const MARKET = __ENV.MARKET || 'KRW-BTC';
const KEYS = JSON.parse(open(__ENV.K6_KEYS || './secrets/k6_keys.json'));

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1.0'] },
};

export default function () {
  // 조회부터 본다. 여기서 막히면 서명이 아니라 경로·LB 문제다.
  const ob = http.get(`${BASE}/api/v1/orderbook?market=${MARKET}`);
  check(ob, { '조회 200': (r) => r.status === 200 });
  if (ob.status !== 200) {
    console.error(`조회 실패 status=${ob.status} body=${String(ob.body).slice(0, 200)}`);
  }

  // 키를 전부 돌린다. 하나만 보면 일부만 실효인 상태를 놓친다.
  for (let i = 0; i < KEYS.length; i++) {
    const c = KEYS[i];
    const o = {
      // 체결되지 않을 가격에 낸다. 시드 잔고를 축내지 않는다. 다만 최소 주문 금액
      // (API 정본 5,000원)은 넘겨야 한다. 안 넘기면 under_min_order 400이 나서
      // 프리플라이트가 서명·키가 아니라 자기 파라미터를 실패로 보고한다.
      clientOrderId: `preflight-${i}-${Date.now()}`,
      market: MARKET, ordType: 'limit', price: '1000000',
      side: 'bid', timeInForce: 'GTC', volume: '0.01',
    };
    const body = orderCanonicalBody(o);
    const path = '/api/v1/orders';
    const res = http.post(BASE + path, body, {
      headers: {
        'Content-Type': 'application/json',
        Authorization: signRequest('POST', path, body, c.access_key, c.secret),
      },
    });
    // 429는 정상이다. 수용 게이트가 살아 있다는 뜻이지 설정 오류가 아니다.
    const ok = res.status === 201 || res.status === 200 || res.status === 429;
    check(res, { [`키${i} 주문 접수`]: () => ok });
    if (!ok) {
      console.error(`키${i}(${c.access_key.slice(0, 8)}...) status=${res.status} ` +
        `body=${String(res.body).slice(0, 240)}`);
    }
  }
}
