/**
 * 주문 가격 프리필의 출처 병합 규칙. OrderPanel에서 분리해 단위테스트로 회귀를 고정한다.
 * 출처는 둘이다. /order 리다이렉트 쿼리(§1.2, 마운트 1회 승계)와 호가 클릭(§2.5).
 * 쿼리 읽기는 정적 export에서 Suspense 경계 안으로 격리돼 있어, 병합 규칙만 여기서 검증한다.
 */

import type { Side } from "../../lib/types";

export interface PricePrefill {
  /** 금액은 문자열이 원본이다. number 왕복은 저가 마켓 격자를 뭉갠다 */
  price: string;
  /** 동일 가격 재클릭도 재주입되도록 하는 시퀀스 */
  seq: number;
}

/** 쿼리 side. ask만 매도로 읽고 미지정·오타는 매수로 좁힌다 */
export function prefillSideOf(raw: string | null): Side {
  return raw === "ask" ? "ask" : "bid";
}

/** 쿼리 가격의 초기 프리필. 가격이 없으면 승계할 것이 없다 */
export function queryPrefillOf(rawPrice: string | null): PricePrefill | null {
  return rawPrice ? { price: rawPrice, seq: 0 } : null;
}

/**
 * 두 출처의 우선순위. 호가 클릭은 사용자가 방금 한 조작이라 마운트 시 승계한 쿼리분보다 항상 최신이다.
 * 순서를 뒤집으면 쿼리로 진입한 화면에서 호가 클릭이 먹지 않는다.
 */
export function effectivePrefill(
  clicked: PricePrefill | null,
  fromQuery: PricePrefill | null,
): PricePrefill | null {
  return clicked ?? fromQuery;
}

/** 호가 클릭 프리필 갱신. 같은 가격 재클릭도 seq가 올라 재주입된다 */
export function nextClickPrefill(prev: PricePrefill | null, price: string): PricePrefill {
  return { price, seq: (prev?.seq ?? 0) + 1 };
}
