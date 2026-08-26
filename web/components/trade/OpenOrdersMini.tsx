"use client";

/**
 * OpenOrdersMini: 주문 패널 하단 미체결 미니 리스트.
 * 지정가 접수 후 잔량 확인·취소가 거래내역 화면 이탈 없이 되도록 현재 마켓의 OPEN·PARTIAL만 보인다.
 * 데이터·갱신은 기존 리듬 재사용: 목록은 store.openOrders(마운트 시 loadOrders는 ExchangeMarketClient,
 * 실시간 전이는 OrderPanel의 myOrder 구독이 apply), 취소는 거래내역 화면과 같은
 * cancelOrder→upsertOrder→loadOrders 경로다. 신규 폴러·구독 없음.
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
      const canceled = await getDataSource().cancelOrder(order.id);
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
      {rows.length === 0 ? (
        <p className={styles.empty}>미체결 없음</p>
      ) : (
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
      )}
    </PanelCard>
  );
}
