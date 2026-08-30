// COIN_META가 마켓 유니버스의 단일 원천이다. 정적 export는 빌드타임에 라우트를 확정해야 해
// 런타임 /markets로 파생할 수 없고, 마켓 증감은 재빌드로 반영한다.
import { COIN_META } from "@/lib/sim/engine";
import LiveTitle from "@/components/trade/LiveTitle";
import ExchangeMarketClient from "./ExchangeMarketClient";

// generateStaticParams는 정적 export 필수. 삭제 금지(동적 라우트 사전 생성원)
export function generateStaticParams() {
  return COIN_META.map((c) => ({ market: c.market }));
}

export default async function ExchangeMarketPage({ params }: { params: Promise<{ market: string }> }) {
  const { market } = await params;
  return (
    <>
      <LiveTitle market={market} />
      <ExchangeMarketClient />
    </>
  );
}
