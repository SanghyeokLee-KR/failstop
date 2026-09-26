// S5 관문 Z1r 의 WS orderbook 결번을 가르는 대조 실험(설계 밖, 12-4 기록). run-s5-gate.ps1 -Phase WsRace 가 부른다.
//
// 가설: 구독 즉시 스냅샷(OrderbookSnapshotOnSubscribe)은 SUBSCRIBE 를 받은 이벤트에서 채널 seq 를 읽고, 브로커는 그 구독을
// 인바운드 채널의 다른 스레드에서 등록한다. 스냅샷이 seq N 을 읽은 뒤 구독이 등록되기 전에 N+1 이 발행되면 그 구독자는 N+1 을
// 받지 못해 스냅샷 뒤 첫 realtime 이 N+2 다(결번). 봇 부하가 한 마켓에 몰려 발행이 잦을수록 잦아진다. S5 는 이 경로를 바꾸지
// 않았으므로 S5 이미지와 S4b 이미지에서 같은 빈도로 나야 한다.
//
// 세는 것(연결마다 HOLD_MS 동안 구독 하나):
//   race_subscriptions     구독 수
//   race_snapshot_first    첫 메시지가 스냅샷
//   race_realtime_first    첫 메시지가 realtime(스냅샷보다 먼저 도착)
//   race_start_gap         스냅샷이 기준인 채로 받은 첫 realtime 이 기준 + 1 보다 크다(구독 경계의 결번)
//   race_later_gap         그 뒤 realtime 사이의 결번(구독 경계 밖의 유실)
//   race_stale             기준 이하의 seq(역순 도착, 계약상 폐기)
//   race_no_snapshot       HOLD_MS 안에 스냅샷을 받지 못한 구독
// 판정은 하지 않는다(임계 없음). 요약 JSON 을 /out/ws_race_summary.json 에 쓴다.
import { Counter } from 'k6/metrics';
import { WebSocket, connectFrame, subscribeFrame, parseFrames, topicOrderbook } from '../../load/lib/stomp.js';

const BASE = __ENV.BASE_URL || 'http://chaos-lb:8080';
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws/v1';
const MARKET = __ENV.MARKET || 'KRW-BTC';
const HOLD = parseInt(__ENV.HOLD_MS || '1500', 10);

const subs = new Counter('race_subscriptions');
const snapshotFirst = new Counter('race_snapshot_first');
const realtimeFirst = new Counter('race_realtime_first');
const startGap = new Counter('race_start_gap');
const laterGap = new Counter('race_later_gap');
const stale = new Counter('race_stale');
const noSnapshot = new Counter('race_no_snapshot');

export const options = {
  scenarios: {
    race: { executor: 'constant-vus', vus: parseInt(__ENV.VUS || '20', 10), duration: __ENV.DURATION || '120s' },
  },
};

export default function () {
  let snapSeq = null;
  let last = null;
  let firstKind = null;
  let realtimeAfterSnapshot = false;
  const ws = new WebSocket(WS_URL);

  ws.onopen = () => {
    ws.send(connectFrame('api.exchange.local'));
    ws.send(subscribeFrame('ob', topicOrderbook(MARKET)));
    subs.add(1);
  };

  ws.onmessage = (e) => {
    for (const f of parseFrames(e.data)) {
      if (f.command !== 'MESSAGE') {
        continue;
      }
      let m;
      try { m = JSON.parse(f.body); } catch (err) { continue; }
      if (m.seq === undefined) {
        continue;
      }
      if (m.stream === 'snapshot') {
        if (firstKind === null) { firstKind = 'snapshot'; snapshotFirst.add(1); }
        if (last === null || m.seq > last) {
          snapSeq = m.seq;
          last = m.seq;
        } else {
          stale.add(1);
        }
        continue;
      }
      if (firstKind === null) { firstKind = 'realtime'; realtimeFirst.add(1); }
      if (last !== null && m.seq <= last) {
        stale.add(1);
        continue;
      }
      if (last !== null && m.seq > last + 1) {
        if (!realtimeAfterSnapshot && snapSeq !== null && last === snapSeq) {
          startGap.add(1);
        } else {
          laterGap.add(1);
        }
      }
      if (snapSeq !== null) {
        realtimeAfterSnapshot = true;
      }
      last = m.seq;
    }
  };

  setTimeout(() => {
    if (snapSeq === null) {
      noSnapshot.add(1);
    }
    ws.close();
  }, HOLD);
}

export function handleSummary(data) {
  const pick = {};
  for (const name of ['race_subscriptions', 'race_snapshot_first', 'race_realtime_first', 'race_start_gap',
    'race_later_gap', 'race_stale', 'race_no_snapshot', 'ws_sessions', 'ws_msgs_received']) {
    const m = data.metrics[name];
    pick[name] = m && m.values ? (m.values.count !== undefined ? m.values.count : m.values) : 0;
  }
  return { '/out/ws_race_summary.json': JSON.stringify(pick, null, 1), stdout: JSON.stringify(pick) + '\n' };
}
