// WS 재접속 폭주 측정.
//
// 동시 연결 상한을 붙이면 초과분이 거절된다. 거절된 클라이언트가 곧바로 다시 붙으면 거절 자체가
// 부하가 된다. 상한이 노드를 지키는 대신 다른 방식으로 무너뜨리는지 확인한다.
//
// 재시도 간격은 실제 웹 클라이언트를 따른다. `web/lib/api/httpDatasource.ts` 의 STOMP 설정이
// `reconnectDelay: 2000` 이고 지터도 지수 백오프도 없다. 거절이 한꺼번에 나면 재시도도 한꺼번에
// 온다는 뜻이라 이 값이 최악의 경우다.
//
// 두 무리를 같이 돌린다.
//   holders  : 상한 안에 들어와 자리를 잡은 사용자. 폭주 중에도 계속 메시지를 받아야 한다.
//   stormers : 상한 밖에서 거절당하고 계속 다시 붙는 무리.
//
// 판정 축은 노드 생존이 아니라 holders 다. 노드가 살아도 기존 접속자가 굶으면 장애다.
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { WebSocket, connectFrame, subscribeFrame, parseFrames, topicOrderbook, topicTrade } from './lib/stomp.js';

const BASE = __ENV.BASE_URL || 'http://ex-app:8080';
const WS_URL = __ENV.WS_URL || BASE.replace(/^http/, 'ws') + '/ws/v1';
const HOST = __ENV.WS_HOST || 'ex-app';
const MARKET = __ENV.MARKET || 'KRW-BTC';

const HOLDERS = Number(__ENV.WS_HOLDERS || 1900);
const STORMERS = Number(__ENV.WS_STORMERS || 600);
// 자리를 잡는 무리가 한꺼번에 붙으면 구독마다 도는 호가 집계가 몰린다(증거 31회차). 흩어 붙인다.
const HOLDER_RAMP_MS = Number(__ENV.WS_HOLDER_RAMP_MS || 40000);
const HOLD_MS = Number(__ENV.WS_HOLD_MS || 200000);
// 폭주가 시작되는 시각. 자리를 다 잡은 뒤여야 거절이 나온다.
const STORM_START = __ENV.WS_STORM_START || '60s';
const STORM_DURATION = __ENV.WS_STORM_DURATION || '120s';
// 실제 웹 클라이언트 값이다. 낮추면 폭주가 세지고 올리면 약해진다.
const RETRY_MS = Number(__ENV.WS_RETRY_MS || 2000);
/*
 * 대조군 스위치. 켜면 stormers 가 연결을 맺지 않고 쉬기만 한다.
 *
 * 왜 필요한가. 폭주군을 붙이면 k6 의 VU 수도 함께 늘어난다. VU 가 많아지면 이쪽 이벤트
 * 루프가 밀려 holders 의 하트비트 타이머가 늦어지고, 그러면 서버가 아니라 부하 도구 탓으로
 * 세션이 끊길 수 있다. VU 수는 같고 서버 부하만 없는 대조군이 있어야 둘을 가른다.
 */
const STORM_IDLE = __ENV.WS_STORM_IDLE === '1';
// 흐름이 이 시간 넘게 끊기면 끊김으로 센다. 체결이 도는 상태에서 홀더는 초당 수십 건을 받으므로
// 이 값은 넉넉하다.
const STALL_MS = Number(__ENV.WS_STALL_MS || 10000);

// 수용된 연결을 붙잡아 두는 시간. 이 시간이 지나면 스스로 닫는다.
const PROBE_HOLD_MS = Number(__ENV.WS_PROBE_HOLD_MS || 1000);

const attempt = new Counter('storm_attempt');
const rejected = new Counter('storm_rejected');
const admitted = new Counter('storm_admitted');
const admitLatency = new Trend('storm_admit_ms', true);

const holderMsg = new Counter('holder_msg');
const holderLost = new Counter('holder_lost');
const holderSilent = new Counter('holder_silent');
/*
 * 끊김 구간. holders 가 살아 있는 동안 정해진 시간을 넘겨 아무것도 받지 못한 창을 센다.
 *
 * 이 축이 필요한 이유. 서버 게이지 exchange_ws_clients 가 폭주 중에 크게 떨어지는데
 * holder_msg 총량은 대조군과 거의 같았다. 둘 중 하나는 틀렸다. 소켓이 열려 있는지(holder_lost)
 * 와 총량만으로는 갈리지 않고, 받는 흐름이 끊겼는지를 직접 봐야 한다. 사용자에게 장애는
 * 소켓이 닫히는 것이 아니라 화면이 멈추는 것이다.
 */
const holderStall = new Counter('holder_stall_window');
const holderStalled = new Counter('holder_stalled_any');
const holderFirstMsg = new Trend('holder_first_msg_ms', true);

export const options = {
  scenarios: {
    holders: {
      executor: 'per-vu-iterations',
      vus: HOLDERS,
      iterations: 1,
      maxDuration: '15m',
      exec: 'holder',
      startTime: '0s',
    },
    stormers: {
      executor: 'constant-vus',
      vus: STORMERS,
      duration: STORM_DURATION,
      exec: 'stormer',
      startTime: STORM_START,
    },
  },
  thresholds: {},
  discardResponseBodies: true,
};

/** 상한 안에 자리를 잡고 폭주 내내 버틴다. 이 무리가 굶으면 상한이 사용자를 지키지 못한 것이다. */
export function holder() {
  sleep((__VU / HOLDERS) * HOLDER_RAMP_MS / 1000);

  const t0 = Date.now();
  let got = 0;
  let done = false;
  let lastMsgAt = Date.now();
  let stalledOnce = false;
  const ws = new WebSocket(WS_URL);

  ws.onopen = () => {
    ws.send(connectFrame(HOST));
    ws.send(subscribeFrame('sub-ob', topicOrderbook(MARKET)));
    ws.send(subscribeFrame('sub-tr', topicTrade(MARKET)));
  };

  ws.onmessage = (e) => {
    for (const f of parseFrames(e.data)) {
      if (f.command !== 'MESSAGE') {
        continue;
      }
      if (got === 0) {
        holderFirstMsg.add(Date.now() - t0);
      }
      got++;
      lastMsgAt = Date.now();
      holderMsg.add(1);
    }
  };

  ws.onclose = () => {
    if (done) {
      return;
    }
    done = true;
    // 유지 시간을 채우기 전에 끊겼다. 폭주가 기존 접속자를 밀어낸 것이다.
    holderLost.add(1);
  };

  const hb = setInterval(() => {
    if (done) {
      return;
    }
    try { ws.send(String.fromCharCode(10)); } catch (err) { /* 이미 닫힘 */ }
  }, 8000);

  // 흐름 감시. 첫 메시지를 받은 뒤부터 STALL_MS 넘게 조용하면 그 창을 센다.
  const watch = setInterval(() => {
    if (done || got === 0) {
      return;
    }
    if (Date.now() - lastMsgAt > STALL_MS) {
      holderStall.add(1);
      if (!stalledOnce) {
        stalledOnce = true;
        holderStalled.add(1);
      }
      lastMsgAt = Date.now();
    }
  }, STALL_MS);

  setTimeout(() => {
    if (!done && got === 0) {
      holderSilent.add(1);
    }
    done = true;
    clearInterval(hb);
    clearInterval(watch);
    try { ws.close(); } catch (err) { /* 이미 닫힘 */ }
  }, HOLD_MS);
}

/**
 * 거절당하고 다시 붙기를 반복한다. 반복 하나가 시도 한 번이고, 끝나면 k6 가 곧바로 다음 반복을
 * 시작하므로 재시도 간격은 아래 sleep 이 정한다.
 */
export function stormer() {
  if (STORM_IDLE) {
    // VU 는 살아 있되 서버를 건드리지 않는다. 부하 도구 쪽 부담만 남긴다.
    sleep(RETRY_MS / 1000);
    return;
  }
  let sawMessage = false;
  let settled = false;
  const t0 = Date.now();
  const ws = new WebSocket(WS_URL);

  attempt.add(1);

  ws.onopen = () => {
    ws.send(connectFrame(HOST));
    ws.send(subscribeFrame('sub-ob', topicOrderbook(MARKET)));
  };

  ws.onmessage = (e) => {
    for (const f of parseFrames(e.data)) {
      if (f.command === 'MESSAGE') {
        sawMessage = true;
      }
    }
  };

  /*
   * 수용과 거절을 종료 코드로 가르지 못한다. k6 의 experimental/websockets 는 close 이벤트에
   * code 를 싣지 않는다(실측: keys=type,target,timestamp). 그래서 메시지 수신 여부로 가른다.
   * 수용된 연결은 구독 직후 서버가 호가 스냅샷을 직송하므로 반드시 한 건을 받는다. 거절된
   * 연결은 안쪽 핸들러에 닿지 않아 STOMP 응답 자체가 없다.
   *
   * 판정을 전부 onclose 에 모은 이유. 종전에는 타이머에서도 수용으로 계상했는데, sleep 이
   * 이벤트 루프를 막는 동안 타이머와 close 가 함께 큐에 쌓여 타이머가 먼저 도는 경우가 있었다.
   * 그때 거절이 전부 수용으로 잡혔다(실측: 서버 거절 117825 건에 클라이언트 거절 0 건).
   * close 는 서버가 닫든 이쪽이 닫든 반드시 한 번 오므로 여기 한 곳에서만 센다.
   */
  ws.onclose = () => {
    if (settled) {
      return;
    }
    settled = true;
    if (sawMessage) {
      admitted.add(1);
      admitLatency.add(Date.now() - t0);
    } else {
      rejected.add(1);
    }
  };

  setTimeout(() => {
    try { ws.close(); } catch (err) { /* 이미 닫힘 */ }
  }, PROBE_HOLD_MS);

  sleep(RETRY_MS / 1000);
}

export function handleSummary(data) {
  const c = (n) => (data.metrics[n] && data.metrics[n].values.count) || 0;
  const t = (n, s) => (data.metrics[n] && data.metrics[n].values[s]) || 0;
  const out = {
    holders: HOLDERS,
    stormers: STORMERS,
    retry_ms: RETRY_MS,
    storm_attempt: c('storm_attempt'),
    storm_rejected: c('storm_rejected'),
    storm_admitted: c('storm_admitted'),
    // 시도와 판정의 차. close 가 반복 종료 전에 오지 않은 건수다. 0 이 아니면 아래 수치가
    // 그만큼 덜 센 것이다.
    storm_unresolved: c('storm_attempt') - c('storm_admitted') - c('storm_rejected'),
    admit_p50_ms: t('storm_admit_ms', 'med'),
    holder_msg: c('holder_msg'),
    holder_lost: c('holder_lost'),
    holder_stall_window: c('holder_stall_window'),
    holder_stalled_any: c('holder_stalled_any'),
    holder_silent: c('holder_silent'),
    holder_first_msg_p50_ms: t('holder_first_msg_ms', 'med'),
    holder_first_msg_p95_ms: t('holder_first_msg_ms', 'p(95)'),
  };
  const path = __ENV.WS_OUT || '/artifacts/ws_reconnect_storm.json';
  const res = {};
  res[path] = JSON.stringify(out, null, 2);
  res.stdout = '\n' + JSON.stringify(out, null, 2) + '\n';
  return res;
}
