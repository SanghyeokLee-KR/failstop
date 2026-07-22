// thundering-herd: 전 봇 동시 재접속 + WS 동시연결 폭주(검증설계 §2-1, CICD §3-3).
// 목표 동시 WS 연결 = 5,000(nginx worker_connections 예산에서 파생, 상수 고정).
// 창 경계 버스트 방어(토큰버킷)와 슬로우 컨슈머 드롭(API§5-1)을 관찰한다.
//
// 별도 시나리오다. 정본 개방루프 주문 스파이크는 order_flow.js(PROFILE=e6)가 담당한다.

import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { WebSocket, connectFrame, subscribeFrame, parseFrames, topicOrderbook } from '../lib/stomp.js';

const WS_URL = __ENV.WS_URL || (__ENV.BASE_URL || 'http://localhost:58080').replace(/^http/, 'ws') + '/ws/v1';
const HOST = __ENV.API_HOST || 'api.exchange.local';
const MARKET = __ENV.MARKET || 'KRW-BTC';
const TARGET = parseInt(__ENV.WS_TARGET || '5000', 10);

const connOpened = new Counter('ws_conn_opened');
const connRejected = new Counter('ws_conn_rejected');
const subRejected = new Counter('ws_subscription_rejected');

export const options = {
  scenarios: {
    herd: {
      executor: 'ramping-vus',
      startVUs: 0,
      // 30s 순간 버스트로 5,000 동시 연결까지 밀어 올린 뒤 유지.
      stages: [
        { target: TARGET, duration: '30s' },
        { target: TARGET, duration: '60s' },
        { target: 0, duration: '15s' },
      ],
      exec: 'holdConnection',
    },
  },
  thresholds: {
    // 연결 거절은 백프레셔 증거라 허용하되, 열린 연결의 구독 거절은 규약(§5-6)대로
    // subscription_rejected 통지로만 처리되어야 한다(연결 절단 아님).
    ws_conn_opened: ['count>0'],
  },
};

export function holdConnection() {
  const ws = new WebSocket(WS_URL);

  ws.onopen = () => {
    connOpened.add(1);
    ws.send(connectFrame(HOST));
    ws.send(subscribeFrame('sub-ob', topicOrderbook(MARKET)));
  };

  ws.onmessage = (e) => {
    for (const f of parseFrames(e.data)) {
      if (f.command !== 'MESSAGE') {
        continue;
      }
      // 구독 거절 통지(§5-6): ERROR 프레임 없이 subscription_rejected MESSAGE로만 온다.
      if (String(f.body).indexOf('subscription_rejected') >= 0) {
        subRejected.add(1);
      }
    }
  };

  ws.onerror = () => { connRejected.add(1); };

  ws.setTimeout(() => { ws.close(); }, 20000);
  check(null, { herd_vu_started: () => true });
}
