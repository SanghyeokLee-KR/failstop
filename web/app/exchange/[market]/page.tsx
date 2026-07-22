// 정적 export용 서버 셸. 거래 화면 본체 (§2). 전 마켓 사전 생성.
import { COIN_META } from "@/lib/sim/engine";
import ExchangeMarketClient from "./ExchangeMarketClient";

export function generateStaticParams() {
  return COIN_META.map((c) => ({ market: c.market }));
}

export default function ExchangeMarketPage() {
  return <ExchangeMarketClient />;
}
