// 조회 전용 부하. 폐쇄 루프(고정 VU)다.
//
// 도착률 방식(constant-arrival-rate)을 쓰지 않는 이유. 서버가 느려지면 k6 가 VU 상한에 걸려
// 반복을 버린다. 그때 찍히는 요청 수는 서버 능력이 아니라 부하 도구가 포기한 값이다. 실측에서
// 그것 때문에 1 노드 구간이 오염됐다. 고정 VU 는 버리지 않고 각 VU 가 응답을 기다렸다가 다음을
// 보내므로, 나온 처리량이 곧 서버가 낸 값이다.
import http from 'k6/http';
import { Trend } from 'k6/metrics';
const BASE = __ENV.BASE_URL || 'http://ex-lb:80';
const MARKET = __ENV.MARKET || 'KRW-BTC';
const lat = new Trend('read_ms', true);
export const options = {
  scenarios: {
    reads: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 400),
      duration: __ENV.DUR || '120s',
    },
  },
  thresholds: {},
  discardResponseBodies: true,
};
export default function () {
  const r = http.get(`${BASE}/api/v1/orderbook?market=${MARKET}`);
  lat.add(r.timings.duration);
}
