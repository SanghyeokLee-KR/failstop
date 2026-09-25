// WS 채널 seq 기준점 판정(API§5-2 v2.58, 마켓분할_리더_S6_설계 6-4). k6 order_flow.js·ws_relay_watch.js 와 node 시험
// (harness/load/tests/seq_baseline.test.mjs)이 같은 함수를 쓴다. k6 와 node 가 모두 읽도록 표준 ES 모듈 문법만 쓴다
// (k6 전역, node 전용 모듈, ?. 와 ?? 를 쓰지 않는다).
//
// 규칙은 넷이다.
//   1. epoch 는 마켓마다 본다. 파티션 리더(P3)에서는 마켓마다 게시하는 라이터가 달라 다른 마켓끼리 epoch 를 비교하지 않는다.
//      같은 마켓에서 작은 epoch 는 옛 리더가 늦게 보낸 것이라 버린다(old_epoch). 크면 그 마켓 채널들의 기준점을 비운다.
//   2. seq 0 은 소유하지 않은 노드의 DB 집계 스냅샷이다. 상태만 반영하고 기준점을 비운다(snapshot_reset). 결번으로 세지 않고
//      양성 계수에도 넣지 않는다. 종전 규칙은 seq 0 을 기준점으로 삼아 다음 실시간을 결번으로 셌다.
//   3. 기준점 이하의 seq 는 버린다(stale). 기준점을 전진시키지 않는다.
//   4. 기준점 + 1 보다 크면 결번(gap)이다. 조밀 채널(호가·티커)에서만 유실이고, 체결은 전역 event_seq 라 호출부가 가른다.

/** 연결 하나의 판정 상태. 재연결하면 새로 만든다(재연결은 모든 채널을 다시 맞춘다). */
export function newBaseline() {
  return { seq: {}, epoch: {} };
}

/**
 * 메시지 하나를 판정한다.
 * key 는 채널(목적지), market 은 epoch 를 비교할 마켓이다. epoch·seq 가 숫자가 아니면 그 규칙을 건너뛴다.
 * 반환: { verdict: 'ok'|'gap'|'stale'|'old_epoch'|'snapshot_reset'|'no_seq', rise: epoch 가 올랐는가, prev: 직전 기준점 }
 */
export function judgeSeq(state, key, market, epoch, seq) {
  let rise = false;
  if (typeof epoch === 'number' && market) {
    const last = state.epoch[market];
    if (last !== undefined && epoch < last) {
      return { verdict: 'old_epoch', rise: false, prev: null };
    }
    if (last !== undefined && epoch > last) {
      rise = true;
      for (const k of Object.keys(state.seq)) {
        if (state.seq[k].market === market) delete state.seq[k];
      }
    }
    state.epoch[market] = epoch;
  }
  if (typeof seq !== 'number') {
    return { verdict: 'no_seq', rise, prev: null };
  }
  if (seq === 0) {
    delete state.seq[key];
    return { verdict: 'snapshot_reset', rise, prev: null };
  }
  const prev = state.seq[key];
  if (prev !== undefined && seq <= prev.seq) {
    return { verdict: 'stale', rise, prev: prev.seq };
  }
  state.seq[key] = { seq, market };
  if (prev !== undefined && seq > prev.seq + 1) {
    return { verdict: 'gap', rise, prev: prev.seq };
  }
  return { verdict: 'ok', rise, prev: prev === undefined ? null : prev.seq };
}

/**
 * 받은 seq 를 정렬된 닫힌 구간 목록([[from, to], ...])에 넣는다. 관찰자가 호가 (epoch, seq) 수신 집합을 작게 남기는 데 쓴다.
 * 조밀 채널이라 대개 마지막 구간을 늘린다. 이미 든 seq 는 무시하고, 이어지는 두 구간은 합친다.
 */
export function addToRanges(ranges, seq) {
  for (let i = ranges.length - 1; i >= 0; i--) {
    const r = ranges[i];
    if (seq >= r[0] && seq <= r[1]) return;
    if (seq === r[1] + 1) {
      r[1] = seq;
      if (i + 1 < ranges.length && ranges[i + 1][0] === seq + 1) {
        r[1] = ranges[i + 1][1];
        ranges.splice(i + 1, 1);
      }
      return;
    }
    if (seq > r[1]) {
      ranges.splice(i + 1, 0, [seq, seq]);
      return;
    }
    if (seq === r[0] - 1) {
      r[0] = seq;
      if (i > 0 && ranges[i - 1][1] === seq - 1) {
        ranges[i - 1][1] = r[1];
        ranges.splice(i, 1);
      }
      return;
    }
  }
  ranges.unshift([seq, seq]);
}
