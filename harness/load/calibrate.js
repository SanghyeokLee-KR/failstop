// 용량 캘리브레이션 계측(검증설계 §2-4). 기저 OPS를 확정해 스파이크 상한을 유도한다.
//
// order_flow.js의 placeOrder·readMarket을 그대로 import한다. 별도 요청 코드를 쓰면 서명 비용·
// 페이로드 크기가 달라져 "정본 부하의 기저"가 아니라 다른 부하를 재게 된다. §2-4 3항이
// 서명 비용을 캘리브레이션 항목으로 못 박은 이유도 같다.
//
// 스파이크 시나리오는 뺀다. 이 실행의 목적은 폭주 견딤이 아니라 기저에서 호스트·부하기가
// 포화되지 않는지 보는 것이다. 포화된 상태로 잰 10배는 서버 백프레셔가 아니라 호스트
// 포화를 측정한 값이 된다(§2-4 머리말).
//
// 판정에 쓰는 k6측 지표 둘을 handleSummary로 JSON에 낸다.
//   iteration_duration - http_req_duration 중앙값 = 부하기 내부 지연 기여(<5ms 기준)
//   http_req_duration                            = 서버 응답 시간
import { placeOrder, readMarket } from './order_flow.js';

const BASE_OPS = parseInt(__ENV.BASE_OPS || '300', 10);
// 조회는 주문의 배수로 함께 움직인다. 정본 시나리오 비(주문 300 : 조회 500)를 유지한다.
const READ_RATIO = parseFloat(__ENV.READ_RATIO || '1.667');
const DURATION = __ENV.CAL_DURATION || '30m';

export const options = {
  discardResponseBodies: false,
  scenarios: {
    cal_orders: {
      executor: 'constant-arrival-rate',
      rate: BASE_OPS, timeUnit: '1s', duration: DURATION,
      // 개방 루프라 VU가 모자라면 dropped_iterations가 뜬다. 그 자체가 부하기 포화 신호다.
      // maxVUs는 preAllocatedVUs 이상이어야 k6가 기동한다. 낮은 OPS에서 BASE_OPS*6이
      // 하한 50 밑으로 내려가 주행이 통째로 실패했다(실측: 6 OPS에서 종료코드 104).
      preAllocatedVUs: Math.max(50, BASE_OPS),
      maxVUs: Math.max(Math.max(50, BASE_OPS), BASE_OPS * 6),
      exec: 'placeOrder',
    },
    cal_reads: {
      executor: 'constant-arrival-rate',
      rate: Math.round(BASE_OPS * READ_RATIO), timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.max(30, Math.round(BASE_OPS * 0.4)),
      maxVUs: Math.max(Math.max(30, Math.round(BASE_OPS * 0.4)),
                       Math.round(BASE_OPS * READ_RATIO * 3)),
      exec: 'readMarket',
    },
  },
  // 캘리브레이션은 판정이 아니라 계측이다. 임계로 조기 종료하지 않는다.
  // 빈 배열은 임계가 아니라 서브메트릭 생성 지시다. k6는 thresholds에 언급된 태그
  // 조합만 집계하므로, 주문 경로(서명 있음)와 조회 경로(서명 없음)를 갈라 보려면
  // 이렇게 선언해야 한다. §2-4 3항이 서명 비용을 별도 항목으로 두기 때문에 필요하다.
  thresholds: {
    'iteration_duration{scenario:cal_orders}': [],
    'http_req_duration{scenario:cal_orders}': [],
    'iteration_duration{scenario:cal_reads}': [],
    'http_req_duration{scenario:cal_reads}': [],
    // 상태코드 분해. 실패율만 보면 용량 포화(429·503)와 기능 결함(401·502)이 한 숫자에
    // 뭉쳐 캘리브레이션이 엉뚱한 결론을 낸다. 둘은 대응이 정반대다.
    'http_reqs{status:200}': [], 'http_reqs{status:201}': [],
    'http_reqs{status:400}': [], 'http_reqs{status:401}': [],
    'http_reqs{status:409}': [], 'http_reqs{status:429}': [],
    'http_reqs{status:502}': [], 'http_reqs{status:503}': [],
    // 주문 경로만의 접수·거절. 조회의 429가 섞이면 수용 판정이 흐려진다.
    'http_reqs{scenario:cal_orders,status:201}': [],
    'http_reqs{scenario:cal_orders,status:429}': [],
    // 200은 멱등 재생이다(API§7-6). 엔진을 거치지 않으므로 부하로 계상하면 안 된다.
    // 이 값이 크면 client_order_id 공간이 이전 주행과 겹쳤다는 뜻이다(RUN_ID 확인).
    'http_reqs{scenario:cal_orders,status:200}': [],
  },
};

export { placeOrder, readMarket };

export function handleSummary(data) {
  const m = (name, stat) => {
    const v = data.metrics[name];
    return v && v.values && v.values[stat] !== undefined ? v.values[stat] : null;
  };
  const iterMed = m('iteration_duration', 'med');
  const httpMed = m('http_req_duration', 'med');
  // 서명이 붙는 주문 경로만 따로 본다. 조회와 섞으면 서명 비용이 중앙값에 묻힌다.
  const ordIter = m('iteration_duration{scenario:cal_orders}', 'med');
  const ordHttp = m('http_req_duration{scenario:cal_orders}', 'med');
  const rdIter = m('iteration_duration{scenario:cal_reads}', 'med');
  const rdHttp = m('http_req_duration{scenario:cal_reads}', 'med');
  const diff = (a, b) => (a !== null && b !== null ? a - b : null);
  const out = {
    base_ops: BASE_OPS,
    duration: DURATION,
    // 부하기 내부 지연 기여. 이 값이 크면 계측이 서버가 아니라 k6를 재고 있다는 뜻이다.
    k6_overhead_ms: diff(iterMed, httpMed),
    // 주문 오버헤드에서 조회 오버헤드를 빼면 서명·정규화가 더한 몫만 남는다.
    k6_overhead_orders_ms: diff(ordIter, ordHttp),
    k6_overhead_reads_ms: diff(rdIter, rdHttp),
    sign_cost_ms: diff(diff(ordIter, ordHttp), diff(rdIter, rdHttp)),
    order_http_med_ms: ordHttp,
    order_http_p95_ms: m('http_req_duration{scenario:cal_orders}', 'p(95)'),
    iteration_duration_med_ms: iterMed,
    http_req_duration_med_ms: httpMed,
    http_req_duration_p95_ms: m('http_req_duration', 'p(95)'),
    http_reqs: m('http_reqs', 'count'),
    http_req_rate: m('http_reqs', 'rate'),
    http_req_failed_rate: m('http_req_failed', 'rate'),
    // 개방 루프에서 VU가 모자라 발행하지 못한 반복. 부하기 포화의 직접 증거다.
    dropped_iterations: m('dropped_iterations', 'count') || 0,
    vus_max: m('vus_max', 'value'),
    order_accepted: m('http_reqs{scenario:cal_orders,status:201}', 'count') || 0,
    order_throttled: m('http_reqs{scenario:cal_orders,status:429}', 'count') || 0,
    order_replayed: m('http_reqs{scenario:cal_orders,status:200}', 'count') || 0,
    status: [200, 201, 400, 401, 409, 429, 502, 503].reduce((a, c) => {
      const n = m('http_reqs{status:' + c + '}', 'count');
      if (n) a[c] = n;
      return a;
    }, {}),
  };
  // 컨테이너에서는 /load가 읽기 전용이라 상대경로로 못 쓴다. 쓰기 마운트는 /artifacts다.
  const path = __ENV.CAL_OUT || 'artifacts/calibration.json';
  const r = {};
  r[path] = JSON.stringify(out, null, 2);
  return r;
}
