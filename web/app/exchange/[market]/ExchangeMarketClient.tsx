"use client";

/**
 * 거래 화면 본체 (§2). TradeLayout 그리드에 패널 6종 조립.
 * useLiveChannels로 ticker(전 마켓)·orderbook·trade(현재 마켓) 구독을 배선하고,
 * /order 리다이렉트 쿼리(side·price)와 호가 클릭 프리필을 OrderPanel로 전달한다.
 * 쿼리를 읽는 주문 슬롯만 Suspense 경계에 두어 나머지 패널은 정적 export HTML에 프리렌더된다.
 */

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import TradeLayout from "@/components/trade/TradeLayout";
import SummaryBar from "@/components/trade/SummaryBar";
import ChartPanel from "@/components/trade/ChartPanel";
import OrderbookPanel from "@/components/trade/OrderbookPanel";
import TradesPanel from "@/components/trade/TradesPanel";
import OrderPanel from "@/components/trade/OrderPanel";
import OrderPanelSkeleton from "@/components/trade/OrderPanelSkeleton";
import OpenOrdersMini from "@/components/trade/OpenOrdersMini";
import {
  effectivePrefill,
  nextClickPrefill,
  prefillSideOf,
  queryPrefillOf,
  type PricePrefill,
} from "@/components/trade/prefill";
import MarketListPanel from "@/components/trade/MarketListPanel";
import { ConnectionBanner } from "@/components/StatusSignals";
import { toast } from "@/components/Toast";
import { useExchangeStore, useLiveChannels, useEffectConfigPolling, useMarketSpecs } from "@/lib/store";
import { dest } from "@/lib/types";
import { COIN_META } from "@/lib/sim/engine";
import { writeRecentMarket } from "../recentMarket";

interface OrderSlotProps {
  market: string;
  /** 호가 클릭 프리필(부모 소유). 쿼리 승계분보다 항상 최신이다 */
  prefill: PricePrefill | null;
  accountsFailed: boolean;
  onRetryAccounts: () => void;
}

/**
 * /order/[market]?side=...&price=... 리다이렉트 쿼리 승계 (§1.2).
 * useSearchParams는 정적 export 프리렌더를 CSR로 떨어뜨리므로 이 슬롯만 Suspense 경계에 넣는다.
 * 쿼리 읽기를 화면 본체에 두면 호가·체결·차트까지 통째로 바일아웃해 HTML이 빈 셸이 된다.
 */
function OrderPanelSlot({ market, prefill, accountsFailed, onRetryAccounts }: OrderSlotProps) {
  const search = useSearchParams();
  const initialSide = prefillSideOf(search.get("side"));

  // 쿼리 프리필은 마운트 1회 승계다. 이후 호가 클릭이 들어오면 그쪽이 최신이라 우선한다
  const [queryPrefill, setQueryPrefill] = useState<PricePrefill | null>(() =>
    queryPrefillOf(search.get("price")),
  );
  const [queryMarket, setQueryMarket] = useState(market);
  if (queryMarket !== market) {
    setQueryMarket(market);
    setQueryPrefill(null);
  }

  return (
    <OrderPanel
      key={market}
      market={market}
      initialSide={initialSide}
      prefill={effectivePrefill(prefill, queryPrefill)}
      accountsFailed={accountsFailed}
      onRetryAccounts={onRetryAccounts}
    />
  );
}

function ExchangeMarketBody() {
  const params = useParams<{ market: string }>();
  const market = decodeURIComponent(params.market);
  const meta = COIN_META.find((c) => c.market === market);

  const [prefill, setPrefill] = useState<PricePrefill | null>(null);

  // 마켓 전환 시 이전 마켓 가격 프리필 파기 (렌더 중 파생 상태 리셋)
  const [prefillMarket, setPrefillMarket] = useState(market);
  if (prefillMarket !== market) {
    setPrefillMarket(market);
    setPrefill(null);
  }

  // 호가 클릭 → 가격만 주입 (side 유지, §2.5 데스크톱 규약)
  const onPricePrefill = useCallback((price: string) => {
    setPrefill((p) => nextClickPrefill(p, price));
  }, []);

  const hydrateFavorites = useExchangeStore((s) => s.hydrateFavorites);
  const loadAccounts = useExchangeStore((s) => s.loadAccounts);
  const loadOrders = useExchangeStore((s) => s.loadOrders);
  const [accountsFailed, setAccountsFailed] = useState(false);

  // 계좌 로드 실패를 표면화한다. catch 없으면 assets가 빈 배열로 남아 주문 CTA가 영구 비활성된다
  const reloadAccounts = useCallback(() => {
    setAccountsFailed(false);
    loadAccounts().catch(() => {
      setAccountsFailed(true);
      toast("잔고 조회 실패. 다시 시도 필요", "error");
    });
  }, [loadAccounts]);

  // 실시간 배선 (§2.5). 전 마켓 ticker는 마켓 전환과 무관해 deps 고정 배열로 분리한다.
  // 한 배열에 담으면 전환마다 무관한 ticker 채널 전부가 철거·재구독되고 http 모드에서
  // 마켓 수만큼 초기 스냅샷 REST가 재발사된다.
  useLiveChannels(useMemo(() => COIN_META.map((c) => dest.ticker(c.market)), []));
  useLiveChannels(useMemo(() => [dest.orderbook(market), dest.trade(market)], [market]));
  // 효과 강도는 WS가 아니라 폴링으로 반영(§4-A-6)
  useEffectConfigPolling();
  // 틱·수량 격자의 근거. 주문 검증·표시 자릿수가 이 스펙에서 나온다
  useMarketSpecs();

  useEffect(() => {
    hydrateFavorites();
    reloadAccounts();
    void loadOrders();
  }, [hydrateFavorites, reloadAccounts, loadOrders]);

  useEffect(() => {
    if (meta) writeRecentMarket(market);
  }, [market, meta]);

  if (!meta) {
    return (
      <main>
        <div className="empty-state">
          <span className="empty-state__title">존재하지 않는 마켓</span>
          <Link href="/exchange" className="link">
            거래소로 돌아가기
          </Link>
        </div>
      </main>
    );
  }

  return (
    <main>
      {/* 마켓별로 다른 문서라 심볼까지 라벨에 싣는다. 멀티 탭 감시 시 탭 제목과 같은 판독 단위 */}
      <h1 className="sr-only">{`${meta.koreanName} ${market} 거래`}</h1>
      <TradeLayout
        banner={<ConnectionBanner />}
        summary={<SummaryBar market={market} meta={meta} />}
        chart={<ChartPanel market={market} />}
        book={<OrderbookPanel market={market} onPricePrefill={onPricePrefill} />}
        trades={<TradesPanel market={market} />}
        order={
          <>
            {/* fallback은 콜드 마운트 주문 패널과 같은 높이다. 다르면 아래 마켓 목록이 밀린다 */}
            <Suspense fallback={<OrderPanelSkeleton market={market} />}>
              <OrderPanelSlot
                market={market}
                prefill={prefill}
                accountsFailed={accountsFailed}
                onRetryAccounts={reloadAccounts}
              />
            </Suspense>
            {/* Suspense 밖: 미체결 목록은 쿼리와 무관해 프리렌더에 남고 스켈레톤 높이 계약도 안 건드린다 */}
            <OpenOrdersMini market={market} />
          </>
        }
        marketList={<MarketListPanel market={market} />}
      />
    </main>
  );
}

export default function ExchangeMarketClient() {
  return <ExchangeMarketBody />;
}
