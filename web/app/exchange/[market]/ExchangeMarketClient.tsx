"use client";

/**
 * 쿼리를 읽는 주문 슬롯만 Suspense 경계에 둔다(§2).
 * 그래야 나머지 패널이 정적 export HTML에 프리렌더된다.
 */

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import TradeLayout, { ORDER_SLOT_ID } from "@/components/trade/TradeLayout";
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
import type { AccountsHealth } from "@/lib/store";
import { dest } from "@/lib/types";
import { COIN_META } from "@/lib/sim/engine";
import { writeRecentMarket } from "../recentMarket";

interface OrderSlotProps {
  market: string;
  /** 호가 클릭 프리필(부모 소유). 쿼리 승계분보다 항상 최신이다 */
  prefill: PricePrefill | null;
  accountsHealth: AccountsHealth;
  onRetryAccounts: () => void;
}

/**
 * /order/[market]?side=...&price=... 리다이렉트 쿼리 승계 (§1.2).
 * useSearchParams는 정적 export 프리렌더를 CSR로 떨어뜨리므로 이 슬롯만 Suspense 경계에 넣는다.
 * 쿼리 읽기를 화면 본체에 두면 호가·체결·차트까지 통째로 바일아웃해 HTML이 빈 셸이 된다.
 */
function OrderPanelSlot({ market, prefill, accountsHealth, onRetryAccounts }: OrderSlotProps) {
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
      accountsHealth={accountsHealth}
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
  /* 잔고 신선도는 스토어가 든다(§2-3 429 클라이언트 규약). 종전에는 여기 로컬 플래그였고,
     그래서 myAsset 이 잔고를 되살려도 그 성공을 관측하지 못해 데이터는 최신인데 표시만
     실패로 남았다. 단일 in-flight 가드도 스토어로 옮겨 다른 화면의 조회와 함께 유계화된다 */
  const accountsHealth = useExchangeStore((s) => s.accountsHealth);
  // 사설 슬라이스 재조회의 트리거. 이 값이 바뀌면 잔고와 주문을 다시 읽는다
  const memberId = useExchangeStore((s) => s.session?.user.external_id ?? null);

  const reloadAccounts = useCallback(() => {
    // 상태는 스토어가 기록한다. 여기서는 실패를 삼키기만 한다(미처리 거부 방지)
    loadAccounts().catch(() => {});
  }, [loadAccounts]);

  /* 429 자동 재조회. 서버가 준 대기 초를 지킨 뒤 정확히 한 번만 다시 읽는다.
     조건 넷을 전부 만족할 때만 켠다(§2-3 자동 재요청 유계 조건).
       - Retry-After 존중: 유계화한 초만큼 기다린다
       - 단일 in-flight: 스토어 가드가 중복 발사를 막는다
       - 최대 1회: 같은 대기값으로 두 번 걸지 않는다. 실패가 이어지면 사용자가 판단한다
       - 언마운트 정리: 화면을 떠난 뒤 타이머가 요청을 쏘지 않는다
     429가 아닌 실패에는 걸지 않는다. 그 경우 회복 시각을 서버가 말한 바 없다 */
  const autoRetried = useRef(false);
  const retrySec = accountsHealth.retryAfterSec;
  useEffect(() => {
    if (retrySec === null) {
      autoRetried.current = false;
      return;
    }
    if (autoRetried.current) return;
    /* 소진 표시는 예약이 아니라 발사 시점에 세운다. 예약 시점에 세우면 정리 함수가 타이머를
       거둬 간 뒤에도 표시가 남아, 재마운트(StrictMode 이중 마운트, 마켓 전환)에서 자동
       재조회가 한 번도 나가지 않고 사라진다. 발사하지 못한 예약은 소진이 아니다 */
    let fired = false;
    const id = setTimeout(() => {
      fired = true;
      autoRetried.current = true;
      reloadAccounts();
    }, retrySec * 1000);
    return () => {
      clearTimeout(id);
      if (!fired) autoRetried.current = false;
    };
  }, [retrySec, reloadAccounts]);

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
  }, [hydrateFavorites]);

  /* 사설 슬라이스는 신원에 걸린다. deps 를 스토어 액션만으로 두면 이 효과는 마운트에 한 번
     돌고 끝이라, 부트스트랩 복원·로그인·로그아웃으로 신원이 바뀌어도 다시 읽지 않는다.
     스토어가 신원 교체 때 잔고를 비우고 신선도를 idle 로 되돌리므로, 재조회 주체가 없으면
     화면은 재시도 자리도 없는 스켈레톤으로 고착한다(decideBalance: idle -> loading) */
  useEffect(() => {
    reloadAccounts();
    void loadOrders();
  }, [memberId, reloadAccounts, loadOrders]);

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
      {/* 읽기 순서는 시각 배치를 따르지만 그 탓에 주문 폼 앞에 호가 20행이 놓인다.
          화면의 주 행동에 48탭을 요구하지 않도록 우회로를 연다 */}
      <a href={`#${ORDER_SLOT_ID}`} className="skip-link">
        주문으로 바로가기
      </a>
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
                accountsHealth={accountsHealth}
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
