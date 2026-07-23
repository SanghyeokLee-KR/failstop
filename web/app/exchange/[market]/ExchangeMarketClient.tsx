"use client";

/**
 * 거래 화면 본체 (§2). TradeLayout 그리드에 패널 6종 조립.
 * useLiveChannels로 ticker(전 마켓)·orderbook·trade(현재 마켓) 구독을 배선하고,
 * /order 리다이렉트 쿼리(side·price)와 호가 클릭 프리필을 OrderPanel로 전달한다.
 */

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import TradeLayout from "@/components/trade/TradeLayout";
import SummaryBar from "@/components/trade/SummaryBar";
import ChartPanel from "@/components/trade/ChartPanel";
import OrderbookPanel from "@/components/trade/OrderbookPanel";
import TradesPanel from "@/components/trade/TradesPanel";
import OrderPanel, { type PricePrefill } from "@/components/trade/OrderPanel";
import MarketListPanel from "@/components/trade/MarketListPanel";
import { ConnectionBanner } from "@/components/StatusSignals";
import { toast } from "@/components/Toast";
import { useExchangeStore, useLiveChannels, useEffectConfigPolling } from "@/lib/store";
import { dest, type Side } from "@/lib/types";
import { COIN_META } from "@/lib/sim/engine";
import { writeRecentMarket } from "../recentMarket";

function ExchangeMarketBody() {
  const params = useParams<{ market: string }>();
  const market = decodeURIComponent(params.market);
  const meta = COIN_META.find((c) => c.market === market);

  // /order/[market]?side=...&price=... 리다이렉트 쿼리 승계 (§1.2)
  const search = useSearchParams();
  const initialSide: Side = search.get("side") === "ask" ? "ask" : "bid";
  const initialPrice = search.get("price");

  const [prefill, setPrefill] = useState<PricePrefill | null>(
    initialPrice ? { price: initialPrice, seq: 0 } : null,
  );

  // 마켓 전환 시 이전 마켓 가격 프리필 파기 (렌더 중 파생 상태 리셋)
  const [prefillMarket, setPrefillMarket] = useState(market);
  if (prefillMarket !== market) {
    setPrefillMarket(market);
    setPrefill(null);
  }

  // 호가 클릭 → 가격만 주입 (side 유지, §2.5 데스크톱 규약)
  const onPricePrefill = useCallback((price: string) => {
    setPrefill((p) => ({ price, seq: (p?.seq ?? 0) + 1 }));
  }, []);

  const hydrateFavorites = useExchangeStore((s) => s.hydrateFavorites);
  const loadMarkets = useExchangeStore((s) => s.loadMarkets);
  const loadAccounts = useExchangeStore((s) => s.loadAccounts);
  const loadOrders = useExchangeStore((s) => s.loadOrders);
  const marketsLoaded = useExchangeStore((s) => s.markets.length > 0);
  const [accountsFailed, setAccountsFailed] = useState(false);

  // 계좌 로드 실패를 표면화한다. catch 없으면 assets가 빈 배열로 남아 주문 CTA가 영구 비활성된다
  const reloadAccounts = useCallback(() => {
    setAccountsFailed(false);
    loadAccounts().catch(() => {
      setAccountsFailed(true);
      toast("잔고를 불러오지 못했어요. 다시 시도해 주세요", "error");
    });
  }, [loadAccounts]);

  // 실시간 배선 (§2.5). 전 마켓 ticker는 마켓 전환과 무관해 deps 고정 배열로 분리한다.
  // 한 배열에 담으면 전환마다 무관한 ticker 채널 전부가 철거·재구독되고 http 모드에서
  // 마켓 수만큼 초기 스냅샷 REST가 재발사된다.
  useLiveChannels(useMemo(() => COIN_META.map((c) => dest.ticker(c.market)), []));
  useLiveChannels(useMemo(() => [dest.orderbook(market), dest.trade(market)], [market]));
  // 효과 강도는 WS가 아니라 폴링으로 반영(§4-A-6)
  useEffectConfigPolling();

  useEffect(() => {
    hydrateFavorites();
    if (!marketsLoaded) void loadMarkets();
    reloadAccounts();
    void loadOrders();
  }, [hydrateFavorites, loadMarkets, reloadAccounts, loadOrders, marketsLoaded]);

  useEffect(() => {
    if (meta) writeRecentMarket(market);
  }, [market, meta]);

  if (!meta) {
    return (
      <main>
        <div className="empty-state">
          <span className="empty-state__title">존재하지 않는 마켓이에요</span>
          <Link href="/exchange" className="link">
            거래소로 돌아가기
          </Link>
        </div>
      </main>
    );
  }

  return (
    <main>
      <TradeLayout
        banner={<ConnectionBanner />}
        summary={<SummaryBar market={market} meta={meta} />}
        chart={<ChartPanel market={market} />}
        book={<OrderbookPanel market={market} onPricePrefill={onPricePrefill} />}
        trades={<TradesPanel market={market} />}
        order={
          <OrderPanel
            key={market}
            market={market}
            initialSide={initialSide}
            prefill={prefill}
            accountsFailed={accountsFailed}
            onRetryAccounts={reloadAccounts}
          />
        }
        marketList={<MarketListPanel market={market} />}
      />
    </main>
  );
}

export default function ExchangeMarketClient() {
  // useSearchParams는 정적 export 프리렌더에서 Suspense 경계 필수
  return (
    <Suspense fallback={null}>
      <ExchangeMarketBody />
    </Suspense>
  );
}
