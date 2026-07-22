/**
 * tradeDedup.ts. 체결 스트림 (epoch, seq) 단조 중복 판정.
 * 재연결·재동기화는 이미 본 체결을 다시 흘린다(handleConnect가 lastSeq를 null로 초기화 후
 * REST 재전달). candleStream이 이를 그대로 합성하면 같은 체결의 volume이 이중계상된다.
 * 마지막으로 반영한 (epoch, seq)를 기억해 중복·되돌이를 거른다.
 */

export interface TradeCursor {
  epoch: number | null;
  seq: number;
}

/** 초기 커서. 아직 아무 체결도 반영하지 않은 상태 */
export function newTradeCursor(): TradeCursor {
  return { epoch: null, seq: 0 };
}

/**
 * 이미 반영한 체결이면 true.
 * epoch 상승은 리더 세대 교체로 seq 도메인이 리셋되므로 통과시킨다.
 * 같은 epoch에서 seq가 커서 이하면 중복·되돌이(재전달)로 본다.
 */
export function isDuplicateTrade(cursor: TradeCursor, msg: { epoch: number; seq: number }): boolean {
  if (cursor.epoch === null) return false;
  if (msg.epoch < cursor.epoch) return true;
  if (msg.epoch === cursor.epoch && msg.seq <= cursor.seq) return true;
  return false;
}

/** 반영 확정 시 커서 전진 */
export function advanceTradeCursor(cursor: TradeCursor, msg: { epoch: number; seq: number }): void {
  cursor.epoch = msg.epoch;
  cursor.seq = msg.seq;
}
