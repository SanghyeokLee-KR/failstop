// S6 관문의 k6 WS 관찰자(마켓분할_리더_S6_설계 7-1, 하네스 검증설계 S6 패치 노트 (3)). 아직 스택에서 돌린 적이 없다(NOT RUN).
//
// 노드 주소마다 VU 하나가 연결 하나로 마켓 셋의 호가·체결·티커를 구독한다. 노드·마켓별 수신 수, 받은 epoch, 조밀 채널(호가·티커)
// 결번, epoch 상승, 재연결, 종료 코드(1013 포함), 핸드셰이크 실패와 뒤따라 뜬 거절 코드를 센다. 호가는 받은 (epoch, seq) 를
// 구간 목록으로 남겨 두 노드 관찰자의 수신 집합을 비교할 수 있게 한다.
//
// k6 는 VU 의 상태를 끝 요약(handleSummary)으로 넘기지 못한다. 그래서 VU 마다 10초 간격과 끝에 'WATCH <json>' 한 줄을
// 콘솔에 쓴다. 누적 상태라 마지막 줄이 최종 값이고, 줄들을 이으면 10초 간격 시계열이 된다. 실행기가 --log-format json 으로
// 띄우고 docker logs 를 저장하면 harness/scale/s6_relay_axes.py watch 가 노드마다 모은다.
// seq 0 스냅샷은 lib/seq_baseline.js 규칙대로 기준점을 비우고 결번으로 세지 않는다.
//
// 환경 변수
//   WS_URLS       쉼표로 나눈 WS 주소. 노드마다 VU 하나. 기본은 두 앱 노드와 LB 다
//   MARKETS       쉼표로 나눈 마켓. 기본 KRW-BTC,KRW-ETH,KRW-XRP
//   DURATION      관찰 시간(예: 600s, 10m)
//   OUT           끝 요약(k6 기본 지표) JSON 경로. 비우면 쓰지 않는다
//   RECONNECT_MS  끊긴 뒤 다시 붙기까지(기본 2000)
import http from 'k6/http';
import { WebSocket, connectFrame, subscribeFrame, parseFrames } from './lib/stomp.js';
import { newBaseline, judgeSeq, addToRanges } from './lib/seq_baseline.js';

const URLS = (__ENV.WS_URLS || 'ws://chaos-api-1:8080/ws/v1,ws://chaos-api-2:8080/ws/v1,ws://chaos-lb:8080/ws/v1')
  .split(',').map((s) => s.trim()).filter((s) => s.length > 0);
const MARKETS = (__ENV.MARKETS || 'KRW-BTC,KRW-ETH,KRW-XRP')
  .split(',').map((s) => s.trim()).filter((s) => s.length > 0);
const DURATION_S = parseDuration(__ENV.DURATION || '600s');
const RECONNECT_MS = parseInt(__ENV.RECONNECT_MS || '2000', 10);
const FLUSH_MS = 10000;
// 목록형 기록의 상한. 한 줄이 커지면 콘솔 로그가 무거워진다. 판정은 계수를 쓰고 목록은 시각 확인용이다.
const CAP = 200;
// 거절 코드 표본 간격. 핸드셰이크가 연달아 실패해도 확인 요청은 이 간격에 한 번이다.
const PROBE_EVERY_MS = 5000;
const DEST = /^\/topic\/(orderbook|trade|ticker)\.(.+)$/;

export const options = {
  scenarios: {
    watch: {
      executor: 'per-vu-iterations',
      vus: URLS.length,
      iterations: 1,
      maxDuration: `${DURATION_S + 60}s`,
    },
  },
};

function parseDuration(text) {
  const m = /^(\d+)(ms|s|m|h)?$/.exec(String(text).trim());
  if (!m) {
    throw new Error(`DURATION 형식 위반: ${text}`);
  }
  const n = parseInt(m[1], 10);
  const unit = m[2] || 's';
  if (unit === 'ms') return Math.max(1, Math.round(n / 1000));
  if (unit === 'm') return n * 60;
  if (unit === 'h') return n * 3600;
  return n;
}

function push(list, item) {
  if (list.length < CAP) list.push(item);
}

function newChannel(withRanges) {
  const ch = { realtime: 0, snapshot: 0, zero: 0, stale: 0, oldEpoch: 0, gapCount: 0, gaps: [], epochs: [],
    epochFirstAt: {}, firstAt: null, lastAt: null };
  if (withRanges) ch.ranges = {};
  return ch;
}

function newState(url) {
  const markets = {};
  for (const m of MARKETS) {
    markets[m] = { orderbook: newChannel(true), ticker: newChannel(false), trade: { messages: 0, epochs: [], epochFirstAt: {},
      firstAt: null, lastAt: null } };
  }
  return { v: 1, node: url, final: false, at: Date.now(), startedAt: Date.now(), connects: 0, opens: 0,
    closes: [], closeCodes: {}, handshakeErrors: 0, errors: [], refusals: [], epochRises: [], stompErrors: 0,
    parseErrors: 0, markets };
}

function noteEpoch(ch, epoch, now) {
  if (typeof epoch !== 'number') return;
  if (ch.epochs.indexOf(epoch) < 0) {
    ch.epochs.push(epoch);
    ch.epochFirstAt[String(epoch)] = now;
  }
}

function onMessage(state, baseline, frame) {
  const dest = frame.headers.destination || '';
  const m = DEST.exec(dest);
  if (!m) return;
  const kind = m[1];
  const market = m[2];
  let msg;
  try {
    msg = JSON.parse(frame.body);
  } catch (e) {
    state.parseErrors += 1;
    return;
  }
  const ms = state.markets[market];
  if (!ms) return;
  const ch = ms[kind];
  const now = Date.now();
  if (ch.firstAt === null) ch.firstAt = now;
  ch.lastAt = now;
  noteEpoch(ch, msg.epoch, now);
  const r = judgeSeq(baseline, dest, market, msg.epoch, msg.seq);
  if (r.rise) push(state.epochRises, { at: now, market, epoch: msg.epoch, kind });
  if (kind === 'trade') {
    if (r.verdict === 'ok' || r.verdict === 'gap') ch.messages += 1;
    return;
  }
  if (r.verdict === 'snapshot_reset') {
    ch.zero += 1;
    return;
  }
  if (r.verdict === 'stale') {
    ch.stale += 1;
    return;
  }
  if (r.verdict === 'old_epoch') {
    ch.oldEpoch += 1;
    return;
  }
  if (r.verdict === 'no_seq') return;
  if (r.verdict === 'gap') {
    ch.gapCount += 1;
    push(ch.gaps, { at: now, epoch: msg.epoch, from: r.prev, to: msg.seq });
  }
  if (msg.stream === 'snapshot') ch.snapshot += 1;
  else ch.realtime += 1;
  if (kind === 'orderbook' && typeof msg.epoch === 'number') {
    const key = String(msg.epoch);
    if (!ch.ranges[key]) ch.ranges[key] = [];
    addToRanges(ch.ranges[key], msg.seq);
  }
}

// 핸드셰이크가 업그레이드 전에 거절됐는지 HTTP 로 한 번 더 물어 상태 코드와 Retry-After 를 남긴다. 게이트가 그 사이 열렸으면
// 서버가 101 로 답하고 요청이 시한까지 붙어 있을 수 있어 시한을 짧게 둔다.
function probeRefusal(state, url) {
  const httpUrl = url.replace(/^ws/, 'http');
  const res = http.get(httpUrl, {
    headers: { Upgrade: 'websocket', Connection: 'Upgrade', 'Sec-WebSocket-Key': 'czYtcmVsYXktd2F0Y2gtcHJvYmU=',
      'Sec-WebSocket-Version': '13' },
    timeout: '3s',
    responseType: 'none',
    tags: { name: 'ws_handshake_probe' },
  });
  push(state.refusals, { at: Date.now(), status: res.status, retryAfter: res.headers['Retry-After'] || null });
}

export default function () {
  const url = URLS[(__VU - 1) % URLS.length];
  const host = url.replace(/^wss?:\/\//, '').split('/')[0];
  const state = newState(url);
  const deadline = Date.now() + DURATION_S * 1000;
  let ws = null;
  let stopped = false;
  let lastProbe = 0;
  let flushTimer = null;

  function flush(final) {
    state.at = Date.now();
    state.final = final;
    console.log('WATCH ' + JSON.stringify(state));
  }

  function scheduleFlush() {
    flushTimer = setTimeout(() => {
      if (stopped) return;
      flush(false);
      scheduleFlush();
    }, FLUSH_MS);
  }

  function connect() {
    if (stopped || Date.now() >= deadline) return;
    state.connects += 1;
    // 재연결은 모든 채널을 다시 맞춘다(웹 클라이언트와 같다). 기준점을 새로 잡는다
    const baseline = newBaseline();
    let opened = false;
    const socket = new WebSocket(url);
    ws = socket;
    socket.onopen = () => {
      opened = true;
      state.opens += 1;
      socket.send(connectFrame(host));
      MARKETS.forEach((m, i) => {
        socket.send(subscribeFrame(`ob-${i}`, `/topic/orderbook.${m}`));
        socket.send(subscribeFrame(`tr-${i}`, `/topic/trade.${m}`));
        socket.send(subscribeFrame(`tk-${i}`, `/topic/ticker.${m}`));
      });
    };
    socket.onmessage = (e) => {
      for (const f of parseFrames(e.data)) {
        if (f.command === 'MESSAGE') onMessage(state, baseline, f);
        else if (f.command === 'ERROR') state.stompErrors += 1;
      }
    };
    socket.onerror = (e) => {
      const now = Date.now();
      push(state.errors, { at: now, handshake: !opened, error: String((e && e.error) || e) });
      if (!opened) {
        state.handshakeErrors += 1;
        if (now - lastProbe >= PROBE_EVERY_MS) {
          lastProbe = now;
          probeRefusal(state, url);
        }
      }
    };
    socket.onclose = (e) => {
      const code = e && typeof e.code === 'number' ? e.code : null;
      push(state.closes, { at: Date.now(), code, reason: (e && e.reason) || null, opened });
      const k = String(code);
      state.closeCodes[k] = (state.closeCodes[k] || 0) + 1;
      if (!stopped && Date.now() + RECONNECT_MS < deadline) {
        setTimeout(connect, RECONNECT_MS);
      }
    };
  }

  connect();
  scheduleFlush();
  setTimeout(() => {
    stopped = true;
    if (flushTimer !== null) clearTimeout(flushTimer);
    flush(true);
    if (ws !== null) ws.close();
  }, Math.max(0, deadline - Date.now()));
}

export function handleSummary(data) {
  if (!__ENV.OUT) return {};
  const out = {};
  out[__ENV.OUT] = JSON.stringify({ urls: URLS, markets: MARKETS, durationSec: DURATION_S, metrics: data.metrics }, null, 2);
  return out;
}
