// 정적 export용 서버 셸. 거래 화면 본체 (§2). 전 마켓 사전 생성.
// COIN_META가 마켓 유니버스의 단일 원천이다. 정적 export는 빌드타임에 라우트를 확정해야 하므로
// 런타임 백엔드 /markets로 라우트를 파생할 수 없고, 고정 카탈로그가 계약이다. http 모드에서도
// 실시간 데이터는 이 카탈로그의 마켓에 바인딩된다. 백엔드가 마켓을 추가·제거하면 재빌드로 반영한다.
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
