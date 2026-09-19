// 로컬 실습의 단일 출발지 공개 조회 시험. 요청 제한은 기존 설정을 유지한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const rates = [10, 18, 30, 60, 120];
const endpoints = [
  ['orderbook', '/api/v1/orderbook?market=KRW-BTC'],
  ['trades', '/api/v1/trades?market=KRW-BTC&count=100'],
  ['candles', '/api/v1/candles/1m?market=KRW-BTC&count=100'],
];
const responses = new Counter('responses');
const accepted = new Counter('accepted');
const limited = new Counter('limited');
const unexpected = new Rate('unexpected');
const acceptedLatency = new Trend('accepted_latency', true);
const thresholds = {unexpected: [{threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '15s'}]};
const scenarios = {};
for (const [i, rate] of rates.entries()) {
  const stage = `r${rate}`;
  scenarios[stage] = {executor: 'constant-arrival-rate', rate, timeUnit: '1s',
    duration: '60s', startTime: `${i * 60}s`, preAllocatedVUs: 10, maxVUs: 40,
    gracefulStop: '5s', tags: {stage}};
  // 요청이 없는 결과도 같은 집계 키로 보존한다. 비교 구간은 각 단계의 마지막 40초다.
  for (const metric of ['responses', 'accepted', 'limited', 'accepted_latency']) {
    const rule = metric === 'accepted_latency' ? ['p(95)>=0'] : ['count>=0'];
    thresholds[`${metric}{stage:${stage},window:steady}`] = rule;
    for (const [endpoint] of endpoints) {
      thresholds[`${metric}{stage:${stage},window:steady,endpoint:${endpoint}}`] = rule;
    }
  }
}

export const options = {
  scenarios, thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
};

export default function () {
  const [endpoint, path] = endpoints[exec.scenario.iterationInTest % endpoints.length];
  const tags = {endpoint, stage: exec.scenario.name,
    window: Date.now() - exec.scenario.startTime >= 20000 ? 'steady' : 'settling'};
  const response = http.get(`http://app:8080${path}`, {tags, timeout: '3s', redirects: 0});
  let valid = false;
  if (response.status === 200) {
    try {
      const body = response.json();
      valid = body.schema_version === '1.0'
        && Array.isArray(endpoint === 'orderbook' ? body.orderbook_units : body.data);
    } catch (_) { valid = false; }
  }
  responses.add(1, tags);
  accepted.add(valid ? 1 : 0, tags);
  limited.add(response.status === 429 ? 1 : 0, tags);
  unexpected.add(!valid && response.status !== 429, tags);
  if (valid) acceptedLatency.add(response.timings.duration, tags);
}

export function handleSummary(data) {
  return {'/evidence/k6-summary.json': JSON.stringify(data, null, 2)};
}
