// 부하 잔여 회수. GTC 미체결분이 쌓이면 잠금 잔고가 400 insufficient_funds를 만들고
// 호가창에 벽이 선다. 둘 다 접수·429 어느 축에도 안 잡혀 처리율만 조용히 내려간다.
// LB를 지나지 않는 이유는 cancelAll이 수 분 걸리는데 proxy_read_timeout이 10초라서다.
// 대안인 볼륨 초기화는 harness.calibration_runs(§2-4 확정치의 원천)도 함께 지운다.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { signRequest } from './lib/sign.js';

// 계정 처리 완료 수. checks만으로는 판정이 안 된다. k6는 표본이 0인 임계를 통과로 계상하므로
// 스크립트가 아무 일도 못 하고 끝나도 exit 0이 된다. 실측(2026-08-29): maxDuration 기본 10분이
// 진행 중인 DELETE를 끊어 checks_total 0에 exit 0이 나왔다. 관측 실패가 통과 신호가 된 것이다.
const accountsDone = new Counter('cleanup_accounts_done');

// 기본이 LB가 아닌 이유는 위 머리말과 같다. 회수는 게이트가 아니라 정리 작업이라
// LB 경유 필요가 없다.
const BASE = __ENV.CLEANUP_BASE || 'http://chaos-api-1:8080';
// BASE 가 그 마켓을 접수하지 않는 노드면(페일오버 뒤 팔로워) 소유권 선검사가 아무것도 건드리지 않은 503 을
// X-Exchange-Route: not-owner 로 돌려준다(API 설계 §2-5-d, ADR 0034 S5). 그때 새 nonce 로 다시 서명해 이 노드로 한 번 다시 보낸다.
const BASE_ALT = __ENV.CLEANUP_BASE_ALT || 'http://chaos-api-2:8080';
const MARKET = __ENV.MARKET || 'KRW-BTC';
const KEYS = JSON.parse(open(__ENV.K6_KEYS || './secrets/k6_keys.json'));
const KEYS_LEN = KEYS.length;
// 한 요청이 계정 전체를 도므로 라운드를 반복해 경합분을 흡수한다. 나열과 취소 사이에
// 체결된 주문은 실패로 계상되고 다음 라운드에서 사라진다.
const MAX_ROUNDS = parseInt(__ENV.CLEANUP_ROUNDS || '5', 10);
// 계정당 상한. cancelAll이 동기 순회라 잔여에 비례해 길어진다.
const REQ_TIMEOUT = __ENV.CLEANUP_TIMEOUT || '30m';

// cancelAll은 주문당 커맨드 1건이라(실측 천장 약 40 cmd/s) 10만 건 규모면 시간 단위다.
// k6 기본 maxDuration 10분으로는 중간에 끊긴다.
const MAX_DURATION = __ENV.CLEANUP_MAX_DURATION || '3h';

export const options = {
  scenarios: {
    cleanup: {
      executor: 'per-vu-iterations', vus: 1, iterations: 1,
      maxDuration: MAX_DURATION,
    },
  },
  thresholds: {
    // 잔여 0이 판정이다.
    checks: ['rate==1.0'],
    // 표본 자체가 있어야 위 판정이 의미를 갖는다. 중간에 끊기면 이 임계가 잡는다.
    cleanup_accounts_done: [`count>=${KEYS_LEN}`],
  },
};

// 쿼리 요청의 canonical은 METHOD + 경로 + 키 오름차순 정렬 쿼리다
// (CanonicalRequests.forQuery). signRequest의 세 번째 인자가 그 자리이고, 경로에는
// 쿼리를 붙이지 않는다. 둘을 섞으면 401 invalid_query_payload가 난다(실측).
function signedQuery(method, path, params, c, base) {
  const query = Object.keys(params).sort().map((k) => `${k}=${params[k]}`).join('&');
  return {
    url: (base || BASE) + path + (query ? `?${query}` : ''),
    auth: signRequest(method, path, query, c.access_key, c.secret),
  };
}

// 소유권 선검사의 거절인가. 아무것도 쓰지 않은 503 이라 다른 노드로 다시 보내도 한 번의 효과다.
function notOwner(res) {
  return res.status === 503 && String(res.headers['X-Exchange-Route'] || '').toLowerCase() === 'not-owner';
}

function openCount(c) {
  const q = signedQuery('GET', '/api/v1/orders/open', { market: MARKET, limit: 1 }, c);
  const res = http.get(q.url, {
    headers: { Authorization: q.auth },
    timeout: '60s',
  });
  if (res.status !== 200) return -1;
  try {
    // 응답은 {schema_version, data, pagination}이다(실측). 총계 필드가 없으므로
    // limit=1 첫 페이지가 비었는지로 잔여 유무만 본다. 정확한 건수는 필요 없다.
    const items = JSON.parse(res.body).data;
    return Array.isArray(items) ? items.length : -1;
  } catch (e) {
    return -1;
  }
}

export default function () {
  for (let i = 0; i < KEYS.length; i++) {
    const c = KEYS[i];
    const tag = `키${i}(${c.access_key.slice(0, 8)})`;
    let canceled = 0;
    let round = 0;

    for (; round < MAX_ROUNDS; round++) {
      if (openCount(c) === 0) break;
      const q = signedQuery('DELETE', '/api/v1/orders', { market: MARKET }, c);
      let res = http.del(q.url, null, {
        headers: { Authorization: q.auth },
        timeout: REQ_TIMEOUT,
      });
      if (notOwner(res)) {
        // 새 nonce 로 다시 서명해 다른 노드로 한 번 보낸다. 같은 서명 요청을 다시 쓰지 않는다.
        const alt = signedQuery('DELETE', '/api/v1/orders', { market: MARKET }, c, BASE_ALT);
        console.log(`${tag} 라운드${round} ${BASE} 가 소유하지 않는 마켓이라 ${BASE_ALT} 로 다시 보낸다`);
        res = http.del(alt.url, null, {
          headers: { Authorization: alt.auth },
          timeout: REQ_TIMEOUT,
        });
      }
      if (res.status !== 200) {
        console.error(`${tag} 라운드${round} 취소 실패 status=${res.status} ` +
          `body=${String(res.body).slice(0, 200)}`);
        break;
      }
      let n = 0;
      try {
        const body = JSON.parse(res.body);
        n = body.canceled_count !== undefined ? body.canceled_count
          : (body.canceled_ids ? body.canceled_ids.length : 0);
      } catch (e) {
        console.error(`${tag} 응답 파싱 실패`);
        break;
      }
      canceled += n;
      console.log(`${tag} 라운드${round} 취소 ${n}건 (누적 ${canceled})`);
      if (n === 0) break;
    }

    const remaining = openCount(c);
    check(null, { [`${tag} 잔여 0`]: () => remaining === 0 });
    accountsDone.add(1);
    if (remaining !== 0) {
      console.error(`${tag} 회수 미완: 잔여 ${remaining === -1 ? '조회 실패' : remaining}건, ` +
        `라운드 ${round}/${MAX_ROUNDS}. CLEANUP_ROUNDS를 늘리거나 볼륨 초기화를 검토한다.`);
    } else {
      console.log(`${tag} 회수 완료. 총 ${canceled}건 취소`);
    }
  }
}
