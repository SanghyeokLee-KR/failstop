"use client";

/**
 * 거래내역 화면, §3.2 와이드 레이아웃.
 * 전폭 단일 컬럼: 필터 바(SegmentedTab + 마켓 셀렉트 + 기간 칩) + 본문 테이블 카드(행 44px).
 * 실시간: myOrder 채널로 미체결 to 체결 전이(스토어 apply), 취소 성공 시 행 제거 + Toast.
 */

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import DataTable, { type DataColumn } from "@/components/DataTable";
import SegmentedTab from "@/components/SegmentedTab";
import Skeleton from "@/components/Skeleton";
import SymbolChip from "@/components/SymbolChip";
import { toast } from "@/components/Toast";
import { useExchangeStore, useLiveChannels } from "@/lib/store";
import { dest, type Order } from "@/lib/types";
import { getDataSource } from "@/lib/api/datasource";
import { COIN_META } from "@/lib/sim/engine";
import { formatKRW, formatQty, formatTimeHMS, toNum } from "@/lib/format";
import styles from "./page.module.css";

type HistoryTab = "open" | "closed";
type Period = "all" | "1d" | "1w" | "1m";

const TAB_ITEMS = [
  { value: "open" as HistoryTab, label: "미체결" },
  { value: "closed" as HistoryTab, label: "체결" },
];

const PERIODS: { value: Period; label: string; ms: number }[] = [
  { value: "all", label: "전체", ms: 0 },
  { value: "1d", label: "1일", ms: 24 * 3600_000 },
  { value: "1w", label: "1주", ms: 7 * 24 * 3600_000 },
  { value: "1m", label: "1개월", ms: 30 * 24 * 3600_000 },
];

function sideLabel(side: Order["side"]): string {
  return side === "bid" ? "매수" : "매도";
}

export default function HistoryPage() {
  const openOrders = useExchangeStore((s) => s.openOrders);
  const closedOrders = useExchangeStore((s) => s.closedOrders);
  const loadOrders = useExchangeStore((s) => s.loadOrders);

  const [tab, setTab] = useState<HistoryTab>("open");
  const [marketFilter, setMarketFilter] = useState("all");
  const [period, setPeriod] = useState<Period>("all");
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const [canceling, setCanceling] = useState<Set<string>>(new Set());

  useLiveChannels(useMemo(() => [dest.myOrder], []));

  // loaded는 성공·실패 모두 확정한다. catch 없으면 REST 실패 시 스켈레톤이 영구 고착된다
  const load = useCallback(() => {
    setFailed(false);
    loadOrders()
      .then(() => setFailed(false))
      .catch(() => setFailed(true))
      .finally(() => setLoaded(true));
  }, [loadOrders]);

  useEffect(() => {
    load();
  }, [load]);

  const cancel = async (order: Order) => {
    if (canceling.has(order.id)) return;
    setCanceling((s) => new Set(s).add(order.id));
    try {
      await getDataSource().cancelOrder(order.id);
      toast("주문을 취소했어요", "info");
      void loadOrders();
    } catch (e) {
      toast(e instanceof Error ? e.message : "취소에 실패했어요. 잠시 후 다시 시도해 주세요", "error");
    } finally {
      setCanceling((s) => {
        const next = new Set(s);
        next.delete(order.id);
        return next;
      });
    }
  };

  const metaOf = (market: string) => COIN_META.find((c) => c.market === market);

  const rows = useMemo(() => {
    const base = tab === "open" ? openOrders : closedOrders;
    const cutoff =
      period === "all" ? 0 : Date.now() - (PERIODS.find((p) => p.value === period)?.ms ?? 0);
    return base.filter((o) => {
      if (marketFilter !== "all" && o.market !== marketFilter) return false;
      if (cutoff > 0 && new Date(o.created_at).getTime() < cutoff) return false;
      return true;
    });
  }, [tab, openOrders, closedOrders, marketFilter, period]);

  const columns: DataColumn<Order>[] = useMemo(() => {
    const cols: DataColumn<Order>[] = [
      {
        key: "time",
        label: "시각",
        width: "84px",
        sortValue: (o) => o.created_at,
        render: (o) => formatTimeHMS(o.canceled_at ?? o.created_at),
      },
      {
        key: "market",
        label: "마켓",
        width: "minmax(150px, 1.1fr)",
        sortValue: (o) => metaOf(o.market)?.koreanName ?? o.market,
        firstDir: "asc",
        render: (o) => {
          const meta = metaOf(o.market);
          return (
            <span className={styles.marketCell}>
              <SymbolChip meta={meta} size="sm" />
              <span className={styles.marketName}>{meta?.koreanName ?? o.market}</span>
            </span>
          );
        },
      },
      {
        key: "side",
        label: "구분",
        width: "84px",
        render: (o) => (
          <span className="status-pill" data-order={o.side}>
            {o.side === "bid" ? "▲" : "▼"} {sideLabel(o.side)}
          </span>
        ),
      },
      {
        key: "price",
        label: "주문가격",
        width: "minmax(110px, 1fr)",
        align: "right",
        sortValue: (o) => toNum(o.price ?? 0),
        render: (o) => (o.price ? formatKRW(o.price) : "시장가"),
      },
      {
        key: "volume",
        label: "주문수량",
        width: "minmax(110px, 1fr)",
        align: "right",
        render: (o) => formatQty(o.volume ?? o.remaining_volume, 8),
      },
      {
        key: "executed",
        label: "체결수량",
        width: "minmax(110px, 1fr)",
        align: "right",
        render: (o) => formatQty(o.executed_volume, 8),
      },
      {
        key: "status",
        label: "상태",
        width: "96px",
        render: (o) => {
          if (o.status === "PARTIAL")
            return (
              <span className="status-pill" data-health="warning">
                ◐ 부분체결
              </span>
            );
          if (o.status === "FILLED")
            return (
              <span className="status-pill" data-health="success">
                ● 체결
              </span>
            );
          if (o.status === "CANCELED") return <span className={styles.neutralPill}>취소됨</span>;
          return <span className={styles.neutralPill}>접수</span>;
        },
      },
    ];
    if (tab === "open") {
      cols.push({
        key: "action",
        label: "액션",
        width: "88px",
        align: "right",
        render: (o) => {
          const busy = canceling.has(o.id);
          return (
            <button
              type="button"
              className={styles.cancelBtn}
              onClick={() => void cancel(o)}
              disabled={busy}
              aria-busy={busy}
            >
              {busy ? "취소 중" : "취소"}
            </button>
          );
        },
      });
    }
    return cols;
    // canceling 변경 시 취소 버튼 상태를 다시 그려야 하므로 의존성에 포함한다
  }, [tab, canceling]);

  return (
    <main className={styles.page} aria-busy={!loaded}>
      {/* ===== 필터 바 (48px) ===== */}
      <div className={styles.filterBar}>
        <SegmentedTab items={TAB_ITEMS} value={tab} onChange={setTab} variant="neutral" ariaLabel="거래내역 구분" />
        <select
          className={styles.select}
          value={marketFilter}
          aria-label="마켓 필터"
          onChange={(e) => setMarketFilter(e.target.value)}
        >
          <option value="all">전체 마켓</option>
          {COIN_META.map((c) => (
            <option key={c.market} value={c.market}>
              {c.koreanName}
            </option>
          ))}
        </select>
        <div className={styles.chips} role="group" aria-label="기간 필터">
          {PERIODS.map((p) => (
            <button
              key={p.value}
              type="button"
              className={styles.chip}
              data-on={period === p.value || undefined}
              aria-pressed={period === p.value}
              onClick={() => setPeriod(p.value)}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>

      {/* ===== 본문 테이블 카드 ===== */}
      {!loaded ? (
        <Skeleton variant="card" width="100%" height={320} />
      ) : failed ? (
        <section className={`card ${styles.tableCard}`} aria-label="주문 목록">
          <div className="empty-state" role="alert">
            <span className={styles.emptyMark} aria-hidden="true" />
            <span className="empty-state__title">거래내역을 불러오지 못했어요</span>
            <span className="empty-state__desc">서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요</span>
            <button type="button" className="btn btn--secondary" onClick={load}>
              다시 시도
            </button>
          </div>
        </section>
      ) : (
        <section className={`card ${styles.tableCard}`} aria-label="주문 목록">
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(o) => o.id}
            defaultSort={{ key: "time", dir: "desc" }}
            rowHeight={44}
            ariaLabel={tab === "open" ? "미체결 주문" : "체결·취소 주문"}
            empty={
              <div className="empty-state">
                <span className={styles.emptyMark} aria-hidden="true" />
                <span className="empty-state__title">
                  {tab === "open" ? "대기 중인 주문이 없어요" : "거래내역이 없어요"}
                </span>
                <span className="empty-state__desc">주문을 접수하면 여기에서 확인할 수 있어요</span>
                <Link href="/exchange" className="btn btn--secondary">
                  거래소 가기
                </Link>
              </div>
            }
          />
        </section>
      )}
    </main>
  );
}
