// harness/load/lib/seq_baseline.js 판정 규칙 시험(마켓분할_리더_S6_설계 6-4). k6 없이 node 로 돈다.
// 돌리는 법: node --test harness/load/tests/seq_baseline.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { newBaseline, judgeSeq, addToRanges } from '../lib/seq_baseline.js';

const OB = '/topic/orderbook.KRW-BTC';
const TR = '/topic/trade.KRW-BTC';

function run(state, events) {
  return events.map(([key, market, epoch, seq]) => judgeSeq(state, key, market, epoch, seq).verdict);
}

test('seq 0 스냅샷은 기준점을 비우고 다음 실시간을 결번으로 세지 않는다', () => {
  const s = newBaseline();
  const verdicts = run(s, [
    [OB, 'KRW-BTC', 5, 57],
    [OB, 'KRW-BTC', 5, 0],
    [OB, 'KRW-BTC', 5, 90],
    [OB, 'KRW-BTC', 5, 91],
  ]);
  assert.deepEqual(verdicts, ['ok', 'snapshot_reset', 'ok', 'ok']);
});

test('종전 규칙: 기준점 이하는 버리고 기준점을 전진시키지 않는다', () => {
  const s = newBaseline();
  const verdicts = run(s, [
    [OB, 'KRW-BTC', 5, 100],
    [OB, 'KRW-BTC', 5, 99],
    [OB, 'KRW-BTC', 5, 100],
    [OB, 'KRW-BTC', 5, 101],
  ]);
  assert.deepEqual(verdicts, ['ok', 'stale', 'stale', 'ok']);
});

test('종전 규칙: 정상 창의 결번은 gap 으로 세고 기준점을 전진시킨다', () => {
  const s = newBaseline();
  const verdicts = run(s, [
    [OB, 'KRW-BTC', 5, 10],
    [OB, 'KRW-BTC', 5, 13],
    [OB, 'KRW-BTC', 5, 14],
  ]);
  assert.deepEqual(verdicts, ['ok', 'gap', 'ok']);
  assert.equal(judgeSeq(s, OB, 'KRW-BTC', 5, 13).verdict, 'stale');
});

test('epoch 가 오르면 그 마켓 채널의 기준점을 비운다', () => {
  const s = newBaseline();
  run(s, [
    [OB, 'KRW-BTC', 5, 500],
    [TR, 'KRW-BTC', 5, 9000],
  ]);
  const r = judgeSeq(s, OB, 'KRW-BTC', 6, 3);
  assert.equal(r.verdict, 'ok', '새 리더의 낮은 seq 가 기준점이 된다');
  assert.equal(r.rise, true);
  assert.equal(judgeSeq(s, TR, 'KRW-BTC', 6, 9001).verdict, 'ok', '같은 마켓의 다른 채널도 비웠다');
});

test('같은 마켓의 작은 epoch 는 옛 기간 메시지라 버린다', () => {
  const s = newBaseline();
  run(s, [[OB, 'KRW-BTC', 6, 3]]);
  assert.equal(judgeSeq(s, OB, 'KRW-BTC', 5, 999).verdict, 'old_epoch');
  assert.equal(judgeSeq(s, OB, 'KRW-BTC', 6, 4).verdict, 'ok', '옛 메시지가 기준점을 건드리지 않았다');
});

test('다른 마켓끼리는 epoch 를 비교하지 않는다(P3)', () => {
  const s = newBaseline();
  const verdicts = run(s, [
    ['/topic/orderbook.KRW-BTC', 'KRW-BTC', 5, 10],
    ['/topic/orderbook.KRW-ETH', 'KRW-ETH', 9, 20],
    ['/topic/orderbook.KRW-BTC', 'KRW-BTC', 5, 11],
    ['/topic/orderbook.KRW-ETH', 'KRW-ETH', 9, 21],
  ]);
  assert.deepEqual(verdicts, ['ok', 'ok', 'ok', 'ok']);
  const rise = judgeSeq(s, '/topic/orderbook.KRW-ETH', 'KRW-ETH', 10, 1);
  assert.equal(rise.rise, true);
  assert.equal(judgeSeq(s, '/topic/orderbook.KRW-BTC', 'KRW-BTC', 5, 12).verdict, 'ok',
    'KRW-ETH 의 상승이 KRW-BTC 기준점을 비우지 않는다');
});

test('seq 가 없는 메시지는 seq 규칙을 건너뛴다', () => {
  const s = newBaseline();
  assert.equal(judgeSeq(s, OB, 'KRW-BTC', 5, undefined).verdict, 'no_seq');
  assert.equal(judgeSeq(s, OB, 'KRW-BTC', undefined, 7).verdict, 'ok');
});

test('수신 seq 구간 목록: 이어지면 늘리고 끊기면 새 구간, 중복은 무시, 메우면 합친다', () => {
  const r = [];
  for (const seq of [1, 2, 3, 5, 6, 3, 9]) addToRanges(r, seq);
  assert.deepEqual(r, [[1, 3], [5, 6], [9, 9]]);
  addToRanges(r, 4);
  assert.deepEqual(r, [[1, 6], [9, 9]], '4 가 두 구간을 잇는다');
  addToRanges(r, 8);
  assert.deepEqual(r, [[1, 6], [8, 9]]);
  addToRanges(r, 7);
  assert.deepEqual(r, [[1, 9]], '7 이 앞 구간과 뒤 구간을 잇는다');
  addToRanges(r, 0);
  assert.deepEqual(r, [[0, 9]]);
  const late = [[10, 12]];
  addToRanges(late, 5);
  assert.deepEqual(late, [[5, 5], [10, 12]], '재연결 뒤 앞선 seq 도 제자리에 들어간다');
});
