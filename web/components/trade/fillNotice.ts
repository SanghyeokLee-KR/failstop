/**
 * myOrder 재동기화가 합성한 REST Order는 채널 seq 도메인이 없어 seq 0이다.
 * 서버·시뮬레이터 모두 카운터를 1부터 발행하므로 seq 0을 재전달 판별자로 쓴다(§5-2).
 * 이미 종결로 알던 주문의 재통지도 제외해 같은 체결이 두 번 알려지지 않게 한다.
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

/**
 * 접수 응답(201) 직후 알릴 문구. 접수와 체결을 가른다.
 *
 * 왜 가르는가. 지정가를 현재가에서 떨어뜨려 걸면 접수만 되고 체결은 나중이거나 영영 없다.
 * 그런데 화면은 "주문 접수 완료" 하나만 말했다. 사용자는 그것을 "샀다"로 읽고, 보유 수량이
 * 0인 화면을 보고 무엇이 잘못됐는지 찾게 된다. 실제로는 원화가 묶인 채 대기 중이다.
 * 문구가 상태를 말하면 그 탐색이 없어진다.
 */
export function placedNotice(
  order: Pick<Order, "status" | "side">,
): { text: string; kind: "success" | "info" } {
  const label = order.side === "bid" ? "매수" : "매도";
  if (order.status === "FILLED") return { text: `${label} 전량 체결`, kind: "success" };
  if (order.status === "PARTIAL") return { text: `${label} 일부 체결. 잔량 대기 중`, kind: "success" };
  // OPEN. 취소·거부는 접수 경로로 오지 않으므로 나머지는 전부 대기다
  return { text: `${label} 주문 접수. 체결 대기 중`, kind: "info" };
}
