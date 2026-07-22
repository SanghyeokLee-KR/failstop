// STOMP 1.2 프레임을 k6 WebSocket 위에 순수 JS로 구현한다(확장 빌드 0, 검증설계 §6-1).
// 필요한 프레임만: CONNECT / SUBSCRIBE / 수신 MESSAGE·CONNECTED 파싱 / 0x00 종단.
//
// 모듈 경로: 안정 k6/websockets를 쓴다. 부재 시(사전 게이트 실패) import를 아래
// 'k6/experimental/websockets'로 교체한다(§0 폴백 규약). 두 모듈의 WebSocket API는
// 동일 형태라 이 파일 나머지는 무변경이다.
import { WebSocket } from 'k6/websockets';

// STOMP 프레임 종단은 널 옥텟(0x00)이다. 소스에 보이지 않는 제어문자를 박지 않으려고
// 코드포인트로 생성한다.
const NULL = String.fromCharCode(0);

export function connectFrame(host) {
  // heart-beat 10000,10000 = API§5-1 계약. host 헤더는 STOMP 1.2 필수.
  return 'CONNECT\naccept-version:1.2\nheart-beat:10000,10000\nhost:' + host + '\n\n' + NULL;
}

export function subscribeFrame(id, destination) {
  return 'SUBSCRIBE\nid:' + id + '\ndestination:' + destination + '\n\n' + NULL;
}

// 수신 데이터에서 프레임을 분리한다. STOMP는 0x00으로 프레임을 끝낸다. 하나의 onmessage에
// 복수 프레임이 붙어 올 수 있어 전부 분리한다. 하트비트(개행 단독)는 빈 command로 걸러진다.
export function parseFrames(data) {
  const frames = [];
  const chunks = String(data).split(NULL);
  for (const raw of chunks) {
    const text = raw.replace(/^\n+/, '');
    if (text.length === 0) {
      continue;
    }
    const sep = text.indexOf('\n\n');
    const head = sep >= 0 ? text.slice(0, sep) : text;
    const body = sep >= 0 ? text.slice(sep + 2) : '';
    const lines = head.split('\n');
    const command = lines[0];
    if (!command) {
      continue;
    }
    const headers = {};
    for (let i = 1; i < lines.length; i++) {
      const idx = lines[i].indexOf(':');
      if (idx > 0) {
        headers[lines[i].slice(0, idx)] = lines[i].slice(idx + 1);
      }
    }
    frames.push({ command, headers, body });
  }
  return frames;
}

// 공개 채널 destination(API§5-3). 봇·k6 공용.
export function topicOrderbook(market) { return '/topic/orderbook.' + market; }
export function topicTrade(market) { return '/topic/trade.' + market; }

export { WebSocket };
