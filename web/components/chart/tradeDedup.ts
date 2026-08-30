/**
 * 재연결·재동기화가 이미 본 체결을 다시 흘려 candleStream 합성에서 volume이 이중계상된다.
 * 어댑터가 재전달에 채널 전역 lastEpoch를 찍어 (epoch, seq) 커서만으로는 통과하므로 trade_id를 1차 방어로 쓴다.
 */

import { TRADE_TAPE_LIMIT } from "../../lib/types";

/**
 * 기억할 trade_id 수. 재동기화 1회 재전달분(테이프 상한)의 두 배를 둬,
 * 재전달 도중 들어온 신규 체결이 직전 재전달분을 밀어내지 않게 한다.
 */
const SEEN_LIMIT = TRADE_TAPE_LIMIT * 2;

export interface TradeCursor {
  epoch: number | null;
  seq: number;
  /** 반영한 체결 id. Set이 삽입 순서를 보존하므로 상한 초과 시 가장 오래된 것부터 버린다 */
  seen: Set<string>;
}

/** 초기 커서. 아직 아무 체결도 반영하지 않은 상태 */
export function newTradeCursor(): TradeCursor {
  return { epoch: null, seq: 0, seen: new Set() };
}

/** trade_id는 필수다. 선택으로 두면 신규 호출부가 1차 방어를 조용히 끌 수 있다 */
interface TradeKey {
  epoch: number;
  seq: number;
  trade_id: string;
}

/**
 * 같은 trade_id면 세대·seq와 무관하게 중복이다.
 * epoch 상승은 리더 세대 교체로 seq 도메인이 리셋되므로 통과시키고, 같은 epoch에서 seq가 커서 이하면 재전달로 본다.
 */
export function isDuplicateTrade(cursor: TradeCursor, msg: TradeKey): boolean {
  if (cursor.seen.has(msg.trade_id)) return true;
  if (cursor.epoch === null) return false;
  if (msg.epoch < cursor.epoch) return true;
  if (msg.epoch === cursor.epoch && msg.seq <= cursor.seq) return true;
  return false;
}

/** 반영 확정 시 커서 전진 */
export function advanceTradeCursor(cursor: TradeCursor, msg: TradeKey): void {
  cursor.epoch = msg.epoch;
  cursor.seq = msg.seq;
  cursor.seen.add(msg.trade_id);
  while (cursor.seen.size > SEEN_LIMIT) {
    const oldest = cursor.seen.values().next().value;
    if (oldest === undefined) break;
    cursor.seen.delete(oldest);
  }
}
