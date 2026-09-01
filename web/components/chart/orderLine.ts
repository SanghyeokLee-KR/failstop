/**
 * orderLine.ts. 미체결 주문 가격선 오버레이.
 *
 * 왜 내장 priceLine 을 쓰지 않는가. 내장은 클릭 지점에서 오른쪽 끝까지만 긋는 반쪽 선이고
 * 사용자가 끌어 옮길 수 있다. 주문선은 사용자가 그린 것이 아니라 서버 상태의 투영이므로
 * 전체 폭으로 긋고 잠근다. 끌어서 가격이 바뀌는 것처럼 보이면 주문이 옮겨진다는 오해를 준다.
 *
 * 색에 방향색을 쓰지 않는다. chartTheme §3.4 가 그리기 객체를 브랜드 중립색으로 못박았고
 * 주문선도 같은 범주다. 캔들 색은 "시장이 어디로 갔는가"이고 주문선은 "내가 무엇을 걸어놨는가"라
 * 축이 다르다. 같은 빨강이 두 뜻을 가지면 어느 쪽인지 물어야 하는 도면이 된다.
 * 매수와 매도는 색이 아니라 라벨 텍스트로 가른다. TradingView 와 같은 방식이다.
 *
 * 라벨은 좌측에 둔다. 우측은 y축 눈금과 현재가 배지가 이미 쓰고 있어 겹친다.
 */

import { registerOverlay, type OverlayCreate } from "klinecharts";
import type { Order } from "@/lib/types";

/** 오버레이 이름. 등록과 생성이 같은 문자열을 봐야 하므로 상수로 묶는다 */
export const ORDER_LINE = "orderLine";

/** 그릴 때 읽는 값. 색은 토큰 해석값이라 여기에 실어 나른다(캔버스는 CSS 변수를 못 읽는다) */
interface OrderLineExtend {
  color: string;
  textColor: string;
  label: string;
}

/** 주문 하나가 만드는 오버레이 id. 취소·체결 시 이 id 로 지운다 */
const idOf = (orderId: string) => `order-line-${orderId}`;

let registered = false;

/**
 * 오버레이 정의를 등록한다. klinecharts 등록은 전역이라 두 번 부르면 덮어쓴다.
 * 차트가 여러 번 마운트돼도 한 번만 돌게 래치를 건다.
 */
export function registerOrderLine(): void {
  if (registered) return;
  registered = true;

  registerOverlay({
    name: ORDER_LINE,
    totalStep: 1,
    lock: true,
    // 서버 상태의 투영이라 선택·이동 대상이 아니다. 기본 점 도형도 그리지 않는다
    needDefaultPointFigure: false,
    needDefaultXAxisFigure: false,
    needDefaultYAxisFigure: true,
    styles: {
      point: { color: "transparent", borderColor: "transparent", activeColor: "transparent" },
    },
    createPointFigures: ({ coordinates, bounding, overlay }) => {
      const y = coordinates[0]?.y;
      if (y === undefined) return [];
      /* 색은 전부 생성 측이 토큰에서 읽어 실어 준다. 캔버스는 CSS 변수를 못 읽으므로
         해석된 값이 여기까지 와야 한다. 리터럴 기본값을 두면 테마 전환을 따라가지 못하는
         색이 하나 생기고 토큰 게이트도 그것을 잡는다. 값이 없으면 선을 그리지 않는다 */
      const ex = overlay.extendData as OrderLineExtend | undefined;
      if (!ex) return [];
      const { color, textColor, label } = ex;

      return [
        {
          type: "line",
          attrs: { coordinates: [{ x: 0, y }, { x: bounding.width, y }] },
          styles: { color, size: 1, style: "dashed", dashedValue: [4, 3] },
        },
        {
          type: "text",
          attrs: { x: 8, y: y - 9, text: label, align: "left", baseline: "bottom" },
          styles: {
            color: textColor,
            backgroundColor: color,
            size: 10,
            paddingLeft: 5,
            paddingRight: 5,
            paddingTop: 2,
            paddingBottom: 2,
            borderRadius: 2,
          },
        },
      ];
    },
  });
}

/** 차트에 넘길 최소 형태. Order 전체를 들고 다니지 않는다 */
export interface OrderLineSpec {
  id: string;
  price: number;
  label: string;
  color: string;
  textColor: string;
}

/**
 * 미체결 주문을 오버레이 명세로 바꾼다.
 *
 * 시장가 주문은 가격이 없으므로 뺀다. 체결 대기 중인 지정가만 선이 된다.
 * 같은 가격에 여러 건이 있으면 한 선에 건수를 합쳐 적는다. 선을 겹쳐 그으면
 * 파선이 실선처럼 보여 현재가선과 구분되지 않는다.
 */
export function toOrderLines(
  orders: Order[],
  market: string,
  tokens: { brandPrimary: string; textInverse: string },
  fmtQty: (v: string) => string,
): OrderLineSpec[] {
  const open = orders.filter(
    (o) => o.market === market
      && (o.status === "OPEN" || o.status === "PARTIAL")
      && o.price != null
      && Number.isFinite(Number(o.price)),
  );

  // 가격+방향으로 묶는다. 키에 방향을 넣지 않으면 같은 가격의 매수·매도가 한 선이 된다
  const bucket = new Map<string, { price: number; side: Order["side"]; qty: number; n: number }>();
  for (const o of open) {
    const price = Number(o.price);
    const key = `${o.side}@${price}`;
    const remain = Number(o.remaining_volume ?? o.volume ?? 0);
    const cur = bucket.get(key);
    if (cur) {
      cur.qty += remain;
      cur.n += 1;
    } else {
      bucket.set(key, { price, side: o.side, qty: remain, n: 1 });
    }
  }

  return [...bucket.entries()].map(([key, b]) => ({
    id: idOf(key),
    price: b.price,
    color: tokens.brandPrimary,
    textColor: tokens.textInverse,
    label: b.n > 1
      ? `${b.side === "bid" ? "매수" : "매도"} ${fmtQty(String(b.qty))} · ${b.n}건`
      : `${b.side === "bid" ? "매수" : "매도"} ${fmtQty(String(b.qty))}`,
  }));
}

/**
 * 차트 위 주문선을 명세와 일치시킨다. 매 갱신마다 전부 지우고 다시 그리지 않는다.
 * 봇이 초당 수 건씩 체결을 내는 화면에서 전삭제·재생성은 선이 깜박이게 만든다.
 */
export function syncOrderLines(
  chart: { createOverlay: (o: OverlayCreate) => unknown; removeOverlay: (f: { id: string }) => void },
  prev: Map<string, OrderLineSpec>,
  next: OrderLineSpec[],
): Map<string, OrderLineSpec> {
  const wanted = new Map(next.map((s) => [s.id, s]));

  // 사라진 선을 지운다
  for (const id of prev.keys()) {
    if (!wanted.has(id)) chart.removeOverlay({ id });
  }

  // 새로 생겼거나 라벨이 바뀐 선을 다시 그린다. 가격은 id 에 들어 있어 바뀌면 다른 선이다
  for (const [id, spec] of wanted) {
    const before = prev.get(id);
    if (
      before
      && before.label === spec.label
      && before.color === spec.color
      && before.textColor === spec.textColor
    ) continue;
    if (before) chart.removeOverlay({ id });
    chart.createOverlay({
      name: ORDER_LINE,
      id,
      lock: true,
      points: [{ value: spec.price }],
      extendData: { color: spec.color, textColor: spec.textColor, label: spec.label } satisfies OrderLineExtend,
    } as OverlayCreate);
  }

  return wanted;
}
