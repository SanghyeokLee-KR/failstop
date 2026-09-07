// 조회 전용 부하. VU 상한을 묶어 부하 도구가 먼저 무너지지 않게 한다.
import http from 'k6/http';
const BASE = __ENV.BASE_URL || 'http://ex-lb:80';
const MARKET = __ENV.MARKET || 'KRW-BTC';
export const options = {
  scenarios: {
    reads: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RPS || 4000), timeUnit: '1s',
      duration: __ENV.DUR || '300s',
      preAllocatedVUs: Number(__ENV.VUS || 300),
      maxVUs: Number(__ENV.MAXVUS || 800),
    },
  },
  thresholds: {},
  discardResponseBodies: true,
};
export default function () {
  http.get(`${BASE}/api/v1/orderbook?market=${MARKET}`);
}
