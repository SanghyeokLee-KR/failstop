/**
 * 체결 알림 판정 (§8 화면6 실시간 규약).
 * 재연결마다 startResync가 전 채널에 걸리고, myOrder 재동기화는 REST Order를 합성한
 * 메시지를 재전달한다. 합성 메시지는 채널 seq 도메인이 없어 seq 0이다. §5-2는 채널 seq를
 * 단조 증가로만 규정하고 초기값을 정하지 않지만, 서버·시뮬레이터 모두 카운터를 1부터
 * 발행하므로(ChannelSequences.next, sim engine nextSeq) seq 0은 합성분만 가진다.
 * 이 차이를 판별자로 써 재전달을 신규 체결과 가른다.
 * 이미 종결로 알던 주문의 재통지도 제외해, 같은 체결이 두 번 알려지지 않게 한다.
 */

import type { MyOrderMessage, Order } from "../../lib/types";

/** 재동기화 합성 메시지 판별. 합성은 seq 0, 서버 실시간은 1 이상 */
export function isResyncReplay(msg: Pick<MyOrderMessage, "seq">): boolean {
  return msg.seq <= 0;
}

/**
 * 체결 토스트 발화 여부. apply 이전 스토어 상태로 판정한다.
 * 신규 체결(FILLED)이면서 재전달이 아니고, 아직 종결로 알지 못한 주문일 때만 참.
 */
export function shouldAnnounceFill(
  msg: Pick<MyOrderMessage, "id" | "status" | "seq">,
  closedBefore: readonly Pick<Order, "id">[],
): boolean {
  if (msg.status !== "FILLED") return false;
  if (isResyncReplay(msg)) return false;
  return !closedBefore.some((o) => o.id === msg.id);
}
