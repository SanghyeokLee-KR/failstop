// WebSocket 동시 접속 용량 측정.
//
// 주문 처리량과 조회 처리량은 이미 쟀다. 남은 축이 "화면을 열어둔 사람" 이다. 그 사람은
// 주문을 내지 않고 WS 하나만 붙잡은 채 호가와 체결을 받는다. 주문 천장과 별개의 한도다.
//
// 계단으로 연결 수를 올리면서 세 가지를 본다.
//   1. 연결이 맺어지는가 (ws_open 대 ws_error)
//   2. 맺힌 연결이 메시지를 받는가 (ws_first_msg 지연, ws_msg 건수)
//   3. 서버가 버티는가 (서버측 exchange_ws_clients·CPU·힙은 Prometheus 로 따로 본다)
//
// 연결만 세면 안 된다. 소켓은 열려 있는데 메시지가 끊긴 상태가 실제 붕괴 모습이다.
// 그래서 연결 하나마다 첫 메시지까지의 시간과 수신 건수를 함께 남긴다.
//
// STOMP 프레임은 harness/load/lib/stomp.js 를 쓴다. order_flow.js 와 같은 구현이라
// 서버가 보는 클라이언트 모습이 기존 검증과 동일하다.
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { WebSocket, connectFrame, subscribeFrame, parseFrames, topicOrderbook, topicTrade } from './lib/stomp.js';

const BASE = __ENV.BASE_URL || 'http://ex-app:8080';
const WS_URL = __ENV.WS_URL || BASE.replace(/^http/, 'ws') + '/ws/v1';
const HOST = __ENV.WS_HOST || 'ex-app';
const MARKET = __ENV.MARKET || 'KRW-BTC';

// 동시에 붙잡을 연결 수. 한 번 실행이 한 계단이다.
const VUS = Number(__ENV.WS_VUS || 500);
// 연결 하나를 붙잡는 시간.
const HOLD_MS = Number(__ENV.HOLD_MS || 60000);
// 목표 인원까지 걸리는 시간. 0 이면 전원이 동시에 붙는다.
//
// 두 값을 갈라야 하는 이유. 구독 성공 직후 서버가 그 구독에만 호가 스냅샷을 직송하고
// 그때 호가 집계를 한 번 돈다(OrderbookSnapshotOnSubscribe). 전원이 동시에 붙으면 그
// 집계가 인원수만큼 한꺼번에 몰린다. 실제 사용자는 그렇게 들어오지 않는다. 동시 붙기는
// 급증 방어를 재는 축이고, 서서히 붙기는 평시 수용력을 재는 축이다.
const RAMP_MS = Number(__ENV.WS_RAMP_MS || 0);

const wsOpen = new Counter('ws_open');            // 연결 성립
const wsError = new Counter('ws_error');          // 연결 실패·중도 오류
const wsClosedEarly = new Counter('ws_closed_early');  // 서버가 먼저 끊음
const wsMsg = new Counter('ws_msg');              // 수신 MESSAGE 프레임
const wsSilent = new Counter('ws_silent');        // 열렸으나 메시지를 한 건도 못 받음
const wsFirstMsg = new Trend('ws_first_msg_ms', true);

// VU 하나가 정확히 한 번만 돈다. 계단을 한 실행에 몰아넣으면 시험이 끝날 때 모든 연결이
// 강제 종료되고 그 종료가 실패로 계상된다. 한 실행에 한 계단만 두고 유지 시간이 다 차면
// 스스로 닫게 해야 성공과 실패가 갈린다.
export const options = {
  scenarios: {
    ws_hold: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: __ENV.WS_MAXDUR || '10m',
    },
  },
  // 임계는 두지 않는다. 이 스크립트는 합격·불합격을 가르는 것이 아니라 한도를 찾는다.
  thresholds: {},
  discardResponseBodies: true,
};

export default function () {
  if (RAMP_MS > 0) {
    // VU 번호에 비례해 접속 시각을 흩는다. 1 번은 즉시, 마지막 번호는 RAMP_MS 뒤에 붙는다.
    sleep((__VU / VUS) * RAMP_MS / 1000);
  }
  const t0 = Date.now();
  let got = 0;
  let opened = false;
  let done = false;

  const ws = new WebSocket(WS_URL);

  ws.onopen = () => {
    opened = true;
    wsOpen.add(1);
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
      if (got === 0) {
        wsFirstMsg.add(Date.now() - t0);
      }
      got++;
      wsMsg.add(1);
    }
  };

  ws.onerror = () => {
    // 유지 시간을 다 채우고 스스로 닫는 중이면 정상이다. 그 구분이 없으면 시험 종료가
    // 전부 실패로 계상된다.
    if (!done) {
      wsError.add(1);
    }
  };

  ws.onclose = () => {
    if (done) {
      return;
    }
    done = true;
    // 유지 시간을 채우기 전에 닫혔으면 서버가 먼저 끊은 것이다. 연결 수만 세면 이 구간이
    // 성공으로 보인다.
    wsClosedEarly.add(1);
  };

  // 하트비트. CONNECT 프레임이 heart-beat:10000,10000 을 약속하므로 10 초마다 무언가를
  // 보내야 한다. 보내지 않으면 서버가 침묵한 연결로 보고 끊는다. 실측에서 이것을 빠뜨려
  // 60 초 유지가 전부 조기 종료로 계상됐다. STOMP 하트비트는 개행 한 글자다.
  const hb = setInterval(() => {
    if (done) {
      return;
    }
    // STOMP 하트비트는 개행 한 글자다. 소스에 보이지 않는 제어문자를 박지 않으려고
    // 코드포인트로 만든다(lib/stomp.js 와 같은 방식).
    try { ws.send(String.fromCharCode(10)); } catch (err) { /* 이미 닫힘 */ }
  }, 8000);

  // 유지 시간이 끝나면 스스로 닫는다. 이 타이머가 반복을 살려 두어 연결이 유지된다.
  setTimeout(() => {
    if (opened && got === 0) {
      wsSilent.add(1);
    }
    done = true;
    clearInterval(hb);
    try { ws.close(); } catch (err) { /* 이미 닫힘 */ }
  }, HOLD_MS);
}

export function handleSummary(data) {
  const c = (n) => (data.metrics[n] && data.metrics[n].values.count) || 0;
  const t = (n, s) => (data.metrics[n] && data.metrics[n].values[s]) || 0;
  const out = {
    vus: VUS,
    hold_ms: HOLD_MS,
    ramp_ms: RAMP_MS,
    ws_open: c('ws_open'),
    ws_error: c('ws_error'),
    ws_closed_early: c('ws_closed_early'),
    ws_silent: c('ws_silent'),
    ws_msg: c('ws_msg'),
    first_msg_p50_ms: t('ws_first_msg_ms', 'med'),
    first_msg_p95_ms: t('ws_first_msg_ms', 'p(95)'),
    first_msg_max_ms: t('ws_first_msg_ms', 'max'),
    vus_max: t('vus_max', 'max') || (data.metrics.vus_max && data.metrics.vus_max.values.max) || 0,
  };
  const path = __ENV.WS_OUT || '/artifacts/ws_capacity.json';
  const res = {};
  res[path] = JSON.stringify(out, null, 2);
  res.stdout = '\n' + JSON.stringify(out, null, 2) + '\n';
  return res;
}
