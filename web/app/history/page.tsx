"use client";

/**
 * 거래내역 화면, §3.2 와이드 레이아웃.
 * 전폭 단일 컬럼: 필터 바(SegmentedTab + 마켓 셀렉트 + 기간 칩) + 본문 테이블 카드(행 44px).
 * 실시간: myOrder 채널로 미체결 to 체결 전이(스토어 apply), 취소 성공 시 행 제거 + Toast.
 */

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import DataTable, { type DataColumn } from "@/components/DataTable";
import { ConnectionBanner } from "@/components/StatusSignals";
import Pager from "@/components/Pager";
import SegmentedTab from "@/components/SegmentedTab";
import Skeleton from "@/components/Skeleton";
import SymbolChip from "@/components/SymbolChip";
import { toast } from "@/components/Toast";
import { useExchangeStore, useLiveChannels, useMarketSpecs } from "@/lib/store";
import { dest, type Order } from "@/lib/types";
import { isSuspended } from "@/lib/tradingStatus";
import { getDataSource } from "@/lib/api/datasource";
import { COIN_META } from "@/lib/sim/engine";
import { formatHistoryTime, formatPrice, formatQty, toNum } from "@/lib/format";
import styles from "./page.module.css";
import { usePageTitle } from "@/components/PageTitle";

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

/* 한 쪽 50건. 표를 훑는 화면이라 목록·문의보다 크게 잡는다. 서버 상한은 200이고
   여기서는 화면이 정한다 */
const CLOSED_PAGE_SIZE = 50;

export default function HistoryPage() {
  usePageTitle("거래내역");
  const openOrders = useExchangeStore((s) => s.openOrders);
  // 주문가격·수량 표시 자릿수는 마켓 스펙에서 유도한다. 미로드면 폴백 자릿수로 위조된다
  const markets = useMarketSpecs();
  const loadOrders = useExchangeStore((s) => s.loadOrders);
  const upsertOrder = useExchangeStore((s) => s.upsertOrder);
  // 거래 일시 중단 동안 취소도 쓰기라 받지 않는다(§2-5-d). 목록은 그대로 보인다
  const suspended = useExchangeStore((s) => isSuspended(s.tradingStatus));

  const [tab, setTab] = useState<HistoryTab>("open");
  const [marketFilter, setMarketFilter] = useState("all");
  const [period, setPeriod] = useState<Period>("all");
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const [canceling, setCanceling] = useState<Set<string>>(new Set());

  /* 체결 탭은 스토어를 보지 않는다. 전역이 드는 것은 첫 쪽이고 이 화면은 아카이브라
     커서로 끝까지 따라가야 한다. 미체결 탭은 반대다. 지금 걸어둔 주문 전부를 보고 취소를
     판단하는 작업 집합이라 쪽을 나누지 않고 WS 갱신이 바로 반영되는 스토어를 쓴다 */
  const [closedPage, setClosedPage] = useState<Order[] | null>(null);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const cursorsRef = useRef<(string | null)[]>([null]);

  useLiveChannels(useMemo(() => [dest.myOrder], []));

  // loaded는 성공·실패 모두 확정한다. catch 없으면 REST 실패 시 스켈레톤이 영구 고착된다
  const load = useCallback(() => {
    setFailed(false);
    loadOrders()
      .then(() => setFailed(false))
      .catch(() => setFailed(true))
      .finally(() => setLoaded(true));
  }, [loadOrders]);

  /* 마켓 필터는 서버로 넘긴다. 한 쪽을 받아 화면에서 거르면 "전체 기간 BTC"가 실제로는
     "최근 100건 중 BTC"가 되고, 그 차이가 화면에 드러나지 않는다 */
  const loadClosed = useCallback(async (target = 0) => {
    setFailed(false);
    try {
      const res = await getDataSource().getClosedOrders({
        market: marketFilter === "all" ? undefined : marketFilter,
        cursor: cursorsRef.current[target] ?? null,
        limit: CLOSED_PAGE_SIZE,
      });
      cursorsRef.current[target + 1] = res.next_cursor;
      setClosedPage(res.data);
      setHasMore(res.has_more);
      setPage(target);
    } catch {
      setClosedPage(null);
      setFailed(true);
    } finally {
      setLoaded(true);
    }
  }, [marketFilter]);

  useEffect(() => {
    load();
  }, [load]);

  /* 탭 전환과 마켓 필터 변경은 쪽 경계를 새로 만든다. 들고 있던 커서는 이전 조건의
     것이라 버리고 첫 쪽부터 다시 센다 */
  useEffect(() => {
    if (tab !== "closed") return;
    cursorsRef.current = [null];
    void loadClosed(0);
  }, [tab, loadClosed]);

  const cancel = async (order: Order) => {
    if (canceling.has(order.id) || suspended) return;
    setCanceling((s) => new Set(s).add(order.id));
    try {
      const canceled = await getDataSource().cancelOrder(order.id);
      // 200 응답이 권위 Order다. 재조회 왕복 전에 행을 체결·취소 탭으로 확정한다(§4-B-2)
      upsertOrder(canceled);
      toast("주문 취소 완료", "info");
      // 취소는 이미 성공했다. 뒤따르는 재조회 실패가 성공 통지를 오류로 덮으면 안 된다
      loadOrders().catch(() => {});
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

  const metaOf = (market: string) => COIN_META.find((c) => c.market === market);
  // 표시 자리수는 주문 마켓 스펙에서 유도한다(호출부 상수 금지). 미로딩 시 폴백
  const specOf = (market: string) => markets.find((m) => m.market === market);

  const cutoff = useMemo(
    () => (period === "all" ? 0 : Date.now() - (PERIODS.find((p) => p.value === period)?.ms ?? 0)),
    [period],
  );

  const rows = useMemo(() => {
    // 체결 탭은 서버가 마켓을 이미 걸렀다. 기간만 화면에서 자른다
    const base = tab === "open" ? openOrders : (closedPage ?? []);
    return base.filter((o) => {
      if (tab === "open" && marketFilter !== "all" && o.market !== marketFilter) return false;
      if (cutoff > 0 && new Date(o.created_at).getTime() < cutoff) return false;
      return true;
    });
  }, [tab, openOrders, closedPage, marketFilter, cutoff]);

  /* 목록은 최신순이라 기간 필터는 앞쪽만 남기는 절단이다. 이번 쪽 마지막 행이 이미 기간
     밖이면 다음 쪽은 전부 기간 밖이라 볼 것이 없다. 그때 "다음"을 살려두면 눌러서 빈 쪽을
     보고서야 끝을 안다 */
  const periodExhausted =
    cutoff > 0 &&
    closedPage !== null &&
    closedPage.length > 0 &&
    new Date(closedPage[closedPage.length - 1].created_at).getTime() < cutoff;

  const columns: DataColumn<Order>[] = useMemo(() => {
    const cols: DataColumn<Order>[] = [
      {
        key: "time",
        label: "시각",
        width: "104px",
        sortValue: (o) => o.created_at,
        render: (o) => formatHistoryTime(o.canceled_at ?? o.created_at),
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
        render: (o) => (o.price ? formatPrice(o.price, specOf(o.market)?.tick_size) : "시장가"),
      },
      {
        key: "volume",
        label: "주문수량",
        width: "minmax(110px, 1fr)",
        align: "right",
        render: (o) => formatQty(o.volume ?? o.remaining_volume, specOf(o.market)?.qty_scale ?? 8),
      },
      {
        key: "executed",
        label: "체결수량",
        width: "minmax(110px, 1fr)",
        align: "right",
        render: (o) => formatQty(o.executed_volume, specOf(o.market)?.qty_scale ?? 8),
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
              disabled={busy || suspended}
              aria-busy={busy}
            >
              {busy ? "취소 중" : "취소"}
            </button>
          );
        },
      });
    }
    return cols;
    // canceling·suspended 변경 시 취소 버튼 상태를 다시 그려야 하므로 의존성에 포함한다.
    // markets 로딩 후 표시 자리수를 재유도하도록 의존성에 포함한다
  }, [tab, canceling, markets, suspended]);

  return (
    <main className={styles.page} aria-busy={!loaded}>
      {/* 연결 끊김은 전역 상태다. 화면마다 알려주다 말면 사용자는 배너 없음을 정상으로
          읽는다. 이 화면은 미체결 주문 취소가 걸려 있어 특히 그렇다 */}
      <ConnectionBanner />
      {/* 페이지 레벨 h1. 화면에는 필터 바가 먼저 오고 제목 자리가 없어 sr-only로 둔다.
          없으면 heading 계층이 h2에서 시작해 스크린리더 문서 개요가 끊긴다(WCAG 2.4.6) */}
      <h1 className="sr-only">거래내역</h1>
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

      {!loaded ? (
        <Skeleton variant="card" width="100%" height={320} />
      ) : failed ? (
        <section className={`card ${styles.tableCard}`} aria-label="주문 목록">
          <div className="empty-state" role="alert">
            <span className="empty-state__mark" aria-hidden="true" />
            <span className="empty-state__title">거래내역을 불러오지 못함</span>
            <span className="empty-state__desc">서버 연결 실패. 잠시 후 다시 시도 필요</span>
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
                <span className="empty-state__mark" aria-hidden="true" />
                <span className="empty-state__title">
                  {tab === "open" ? "대기 중인 주문 없음" : "거래내역 없음"}
                </span>
                <span className="empty-state__desc">주문을 접수하면 여기에서 확인 가능</span>
                <Link href="/exchange" className="btn btn--secondary">
                  거래소 가기
                </Link>
              </div>
            }
          />

          {/* 미체결은 작업 집합이라 쪽이 없다. 체결 아카이브만 쪽을 넘긴다 */}
          {tab === "closed" && (
            <Pager
              page={page}
              hasMore={hasMore && !periodExhausted}
              onMove={(target) => void loadClosed(target)}
              ariaLabel="체결·취소 주문 쪽 이동"
            />
          )}
        </section>
      )}
    </main>
  );
}
