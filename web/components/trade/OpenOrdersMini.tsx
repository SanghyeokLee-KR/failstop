"use client";

/**
 * 현재 마켓의 OPEN·PARTIAL만 보여 거래내역 화면 이탈 없이 잔량 확인·취소를 끝낸다.
 * 신규 폴러·구독 없이 기존 스토어·구독·취소 경로를 그대로 재사용한다.
 */

import { useMemo, useState } from "react";
import PanelCard from "@/components/PanelCard";
import { toast } from "@/components/Toast";
import { useExchangeStore } from "@/lib/store";
import type { Order } from "@/lib/types";
import { getDataSource } from "@/lib/api/datasource";
import { formatPrice, formatQty } from "@/lib/format";
import styles from "./OpenOrdersMini.module.css";

function sideLabel(side: Order["side"]): string {
  return side === "bid" ? "매수" : "매도";
}

export default function OpenOrdersMini({ market }: { market: string }) {
  const openOrders = useExchangeStore((s) => s.openOrders);
  const markets = useExchangeStore((s) => s.markets);
  const loadOrders = useExchangeStore((s) => s.loadOrders);
  const upsertOrder = useExchangeStore((s) => s.upsertOrder);
  const [canceling, setCanceling] = useState<Set<string>>(new Set());

  const spec = markets.find((m) => m.market === market);
  const qtyScale = spec?.qty_scale ?? 8;
  const base = market.split("-")[1] ?? "";

  const rows = useMemo(
    () =>
      openOrders
        // openOrders는 비종결만 담지만 계약(OPEN·PARTIAL 한정)을 자리에서 못박는다
        .filter((o) => o.market === market && (o.status === "OPEN" || o.status === "PARTIAL"))
        .slice()
        .sort((a, b) => (b.created_at > a.created_at ? 1 : b.created_at < a.created_at ? -1 : 0)),
    [openOrders, market],
  );

  // 즉시 취소(가역 액션). 확인 다이얼로그 없음, 거래내역 화면 관례와 동일
  const cancel = async (order: Order) => {
    if (canceling.has(order.id)) return;
    setCanceling((s) => new Set(s).add(order.id));
    try {
      // 마켓 힌트로 그 마켓을 접수하는 노드에 보낸다(API 설계 §2-5-d)
      const canceled = await getDataSource().cancelOrder(order.id, order.market);
      // 200 응답이 권위 Order다. 재조회 왕복 전에 행을 확정한다(§4-B-2)
      upsertOrder(canceled);
      toast("주문 취소 완료", "info");
      void loadOrders();
    } catch (e) {
      toast(e instanceof Error ? e.message : "취소 실패. 잠시 후 다시 시도 필요", "error");
    } finally {
      setCanceling((s) => {
        const next = new Set(s);
        next.delete(order.id);
        return next;
      });
    }
  };

  /* 볼 것이 없으면 자리를 차지하지 않는다. 미체결 0건에 71px을 쓰면 그만큼 주문 폼이 접히거나
     마켓 행이 줄어든다. 주문이 생겨 그 정보가 실제로 필요할 때 자리를 내준다 */
  if (rows.length === 0) return null;

  return (
    <PanelCard
      dense
      scroll
      className={styles.card}
      title={
        <>
          미체결 <b className="num">{rows.length}</b>
        </>
      }
    >
      <ul className={styles.list}>
          {rows.map((o) => {
            const busy = canceling.has(o.id);
            const priceText = o.price ? `${formatPrice(o.price, spec?.tick_size)}원` : "시장가";
            return (
              <li key={o.id} className={styles.row}>
                <div className={styles.info}>
                  <div className={styles.top}>
                    <span className="status-pill" data-order={o.side}>
                      {o.side === "bid" ? "▲" : "▼"} {sideLabel(o.side)}
                    </span>
                    <b className={`${styles.price} num`}>{priceText}</b>
                  </div>
                  <span className={`${styles.qty} num`}>
                    잔량 {formatQty(o.remaining_volume, qtyScale)} / {formatQty(o.volume ?? o.remaining_volume, qtyScale)}{" "}
                    {base}
                  </span>
                </div>
                <button
                  type="button"
                  className={styles.cancelBtn}
                  onClick={() => void cancel(o)}
                  disabled={busy}
                  aria-busy={busy}
                  aria-label={`${sideLabel(o.side)} ${priceText} 주문 취소`}
                >
                  {busy ? "취소 중" : "취소"}
                </button>
              </li>
            );
          })}
      </ul>
    </PanelCard>
  );
}
