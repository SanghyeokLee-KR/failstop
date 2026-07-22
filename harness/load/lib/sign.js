// HMAC 서명 클라이언트. 봇과 동일 규약(API§3-3): Authorization: Bearer <HS256 JWT>,
// payload에 access_key·nonce·timestamp·query_hash(SHA512(canonical)) 박제.
// canonical = METHOD + "\n" + PATH + "\n" + JCS(body).
//
// 서명 비용 절감(검증설계 §2-2): 주문 바디는 client_order_id·price·volume·side만 변하는
// 고정 형태다. 매 iteration 전체 JCS 라이브러리 호출 대신 키가 이미 UTF-16 오름차순으로
// 정렬된 canonical 문자열을 직접 조립해 그 문자열을 HTTP 바디로 그대로 전송한다. 서버가
// 수신 바디를 JCS 재정규화해도 이미 정규형이라 바이트 동일 → query_hash 일치가 보장된다.
// nonce/timestamp만 요청마다 신규(서명 생략 불가, API§3-4 nonce 계약).

import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// 주문 바디 키 순서는 API§3-3 JCS(UTF-16 코드유닛 오름차순) 정렬 결과와 일치해야 한다.
// client_order_id < market < ord_type < price < side < time_in_force < volume.
// 금액·수량은 문자열이므로 JCS 수치 정규화 대상이 아니다(따옴표 유지).
export function orderCanonicalBody(o) {
  return '{"client_order_id":"' + o.clientOrderId + '",' +
         '"market":"' + o.market + '",' +
         '"ord_type":"' + o.ordType + '",' +
         '"price":"' + o.price + '",' +
         '"side":"' + o.side + '",' +
         '"time_in_force":"' + o.timeInForce + '",' +
         '"volume":"' + o.volume + '"}';
}

let nonceCounter = 0;

// 단조 증가 nonce. VU·iteration·프로세스 시각·카운터 조합으로 서버 Redis 중복 검사(§3-4 4)를
// 통과한다. UUID 라이브러리 의존을 피한다(k6 내장 부재).
function nextNonce() {
  nonceCounter += 1;
  return `k6-${__VU}-${__ITER}-${Date.now()}-${nonceCounter}`;
}

function b64url(s) {
  return encoding.b64encode(s, 'rawurl');
}

// method+path+body 서명 → Authorization 헤더 값. recvWindow는 서명 대상 클레임으로만
// 전달한다(§3-4 3, 미서명 채널 금지). accessKey/secret는 seed 산출물(secrets/k6_keys.json).
export function signRequest(method, path, bodyStr, accessKey, secret, recvWindowMs) {
  const canonical = method + '\n' + path + '\n' + (bodyStr || '');
  const queryHash = crypto.sha512(canonical, 'hex');
  const header = { alg: 'HS256', typ: 'JWT' };
  const payload = {
    access_key: accessKey,
    nonce: nextNonce(),
    timestamp: Date.now(),
    query_hash: queryHash,
    query_hash_alg: 'SHA512',
  };
  if (recvWindowMs) {
    payload.recvWindow = recvWindowMs;
  }
  const signingInput = b64url(JSON.stringify(header)) + '.' + b64url(JSON.stringify(payload));
  const sig = crypto.hmac('sha256', secret, signingInput, 'base64rawurl');
  return 'Bearer ' + signingInput + '.' + sig;
}
