// 용량 캘리브레이션 계측(검증설계 §2-4). 기저 OPS를 확정해 스파이크 상한을 유도한다.
// order_flow.js의 placeOrder·readMarket을 그대로 쓴다. 별도 요청 코드를 쓰면 서명 비용과
// 페이로드가 달라져 정본 부하의 기저가 아니게 된다. 스파이크는 뺀다. 포화 상태에서 잰
// 10배는 서버 백프레셔가 아니라 호스트 포화를 재는 값이다.
import { placeOrder, readMarket } from './order_flow.js';

// 기본값은 확정 기저다. 엔진 직렬 처리율이 40~42/s라 이보다 큰 기본값은 BASE_OPS를
// 빼먹은 주행을 계측이 아니라 포화 시험으로 만든다. 상한 탐색은 명시 인자로 한다.
const BASE_OPS = parseInt(__ENV.BASE_OPS || '10', 10);
// 조회는 주문의 배수로 함께 움직인다. 정본 시나리오 비(주문 300 : 조회 500)를 유지한다.
const READ_RATIO = parseFloat(__ENV.READ_RATIO || '1.667');
const DURATION = __ENV.CAL_DURATION || '30m';

export const options = {
  discardResponseBodies: false,
  // p(99)는 k6 기본 집계에 없다(기본은 avg·min·med·max·p(90)·p(95)). 선언하지 않으면
  // handleSummary에서 undefined가 나오고, 그것을 판정에 쓰면 측정 실패가 판정 실패로
  // 둔갑한다. §2-3 spike 열이 p99를 요구하므로 반드시 있어야 한다.
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
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
  // 조합만 집계한다. 주문 경로와 조회 경로는 iteration 구성이 달라(서명, 응답 본문 파싱,
  // ACK 로그) 합쳐 보면 어느 쪽이 계측을 오염시키는지 가리지 못하므로 갈라 선언한다.
  // 서브메트릭 키는 여기 적은 문자열 그대로 data.metrics의 키가 된다. 태그 순서가 다른
  // 같은 조합은 새 키를 만들지 않고 먼저 등록된 이름으로 접히므로(k6 1.4.0 실측),
  // handleSummary의 조회 문자열은 아래와 한 글자도 달라서는 안 된다.
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
    // 접수 요청(POST /orders)만의 접수·거절. scenario 단독 필터는 같은 시나리오의 by-client
    // 재조회까지 삼켜 재조회의 200·429가 재생·수용 거절로 계상된다. 접수 0은 용량이 아니라
    // 고장이므로 비율 축(C0b)과 별도로 존재 축을 둔다.
    'http_reqs{scenario:cal_orders,name:placeOrder,status:201}': ['count>0'],
    'http_reqs{scenario:cal_orders,name:placeOrder,status:429}': [],
    // 200은 멱등 재생이다(API§7-6). 엔진을 거치지 않으므로 부하로 계상하면 안 된다.
    // 이 값이 크면 client_order_id 공간이 이전 주행과 겹쳤다는 뜻이다(RUN_ID 확인).
    'http_reqs{scenario:cal_orders,name:placeOrder,status:200}': [],
    // 400은 도메인 거절이라 엔진에 닿지 않은 요청이다. 잔고 소진(insufficient_funds)과
    // client_order_id 형식 위반(invalid_parameter)이 여기 들어온다. 접수·거절·재생 어느 축에도
    // 안 잡혀 제공 부하와 계상 합이 말없이 어긋나던 자리다. calibrate.ps1 C8이 이 축을 쓴다.
    'http_reqs{scenario:cal_orders,name:placeOrder,status:400}': [],
    // 수용(201) 전용 지연. 전체 축(order_http_p95_ms)은 429가 모집단의 대부분이라
    // 거절 응답의 빠른 지연이 백분위를 끌어내린다. §2-3 spike 열이 요구하는 것은
    // "수용(2xx) 요청 기준 p99<500ms"이므로 그 축을 따로 내야 판정이 성립한다.
    'http_req_duration{scenario:cal_orders,name:placeOrder,status:201}': [],
    // 재조회를 주문 축에서 뺀 것이 관측을 버린다는 뜻이 되면 안 되므로 별도 축으로 남긴다.
    // 캘리브레이션에서 이 값이 0이 아니면 주문 경로에 5xx·타임아웃이 있었다는 뜻이다.
    'http_reqs{name:lookup}': [],

    // 여기부터는 실제 임계다. 위 빈 배열은 서브메트릭 생성 지시일 뿐이라 k6가 어떤
    // 상태에서도 종료코드 0을 낸다. calibrate.ps1의 C0가 그 종료코드를 보므로, 계측
    // 주행 자체가 헛돌아도 C0는 통과했다. 판정 대상이 없는데 판정이 통과하는 상태다.
    //
    // 계측 주행에 SLO를 걸지 않는다. 상한을 찾는 것이 목적이라 느린 것은 결과지 실패가
    // 아니다. 대신 "이 주행이 실제로 돌았는가"만 양성 증거로 건다.
    //
    // 주문 경로가 한 번도 안 돈 주행은 계측이 아니다. 시나리오 미기동·인증 실패·
    // 키 파일 손상이 여기 걸린다. 하한은 최소 주행(1분 x 최저 기저)에서도 넘도록 낮게
    // 두되 0보다는 커야 한다. 0으로 두면 k6가 표본 0을 통과로 처리해 축이 무의미해진다.
    'http_reqs{scenario:cal_orders,name:placeOrder}': ['count>0'],
    'http_reqs{scenario:cal_reads}': ['count>0'],
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
  // 주문 경로와 조회 경로를 갈라 본다. 합치면 iteration 구성이 다른 둘이 한 중앙값에 묻힌다.
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
    k6_overhead_orders_ms: diff(ordIter, ordHttp),
    k6_overhead_reads_ms: diff(rdIter, rdHttp),
    // 주문 iteration 오버헤드 빼기 조회 iteration 오버헤드. 서명 몫이 아니다. 주문 경로에만
    // 있는 응답 본문 JSON.parse와 접수 1건당 ACK console.log가 이 차이에 함께 들어간다
    // (order_flow.js placeOrder). 조회 readMarket에는 둘 다 없다.
    // §2-4 3항(서명 비용이 k6 CPU의 지배 항목이 아닐 것)은 이 축으로도 k6_overhead_orders_ms로도
    // 판정되지 않는다. C1~C7 어디도 참조하지 않으므로 현재 미판정 항목이다.
    order_path_overhead_ms: diff(diff(ordIter, ordHttp), diff(rdIter, rdHttp)),
    order_http_med_ms: ordHttp,
    order_http_p95_ms: m('http_req_duration{scenario:cal_orders}', 'p(95)'),
    accepted_p95_ms: m('http_req_duration{scenario:cal_orders,name:placeOrder,status:201}', 'p(95)'),
    accepted_p99_ms: m('http_req_duration{scenario:cal_orders,name:placeOrder,status:201}', 'p(99)'),
    iteration_duration_med_ms: iterMed,
    http_req_duration_med_ms: httpMed,
    http_req_duration_p95_ms: m('http_req_duration', 'p(95)'),
    http_reqs: m('http_reqs', 'count'),
    http_req_rate: m('http_reqs', 'rate'),
    http_req_failed_rate: m('http_req_failed', 'rate'),
    // 개방 루프에서 VU가 모자라 발행하지 못한 반복. 부하기 포화의 직접 증거다.
    dropped_iterations: m('dropped_iterations', 'count') || 0,
    vus_max: m('vus_max', 'value'),
    order_accepted: m('http_reqs{scenario:cal_orders,name:placeOrder,status:201}', 'count') || 0,
    order_throttled: m('http_reqs{scenario:cal_orders,name:placeOrder,status:429}', 'count') || 0,
    order_replayed: m('http_reqs{scenario:cal_orders,name:placeOrder,status:200}', 'count') || 0,
    // 엔진 미도달 거절. 접수·거절·재생 합이 제공 부하에 못 미치면 여기부터 본다.
    order_rejected: m('http_reqs{scenario:cal_orders,name:placeOrder,status:400}', 'count') || 0,
    // 주문 경로 5xx·타임아웃 뒤에 붙는 재조회. 0이 아니면 주문 축 수치를 그대로 믿으면 안 된다.
    order_lookup: m('http_reqs{name:lookup}', 'count') || 0,
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
