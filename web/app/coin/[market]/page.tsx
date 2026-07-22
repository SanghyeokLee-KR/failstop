// 정적 export용 서버 셸. 딥링크 보존, 본문은 /exchange/[market]로 이관 (§1.2)
import { COIN_META } from "@/lib/sim/engine";
import CoinRedirect from "./CoinRedirect";

export function generateStaticParams() {
  return COIN_META.map((c) => ({ market: c.market }));
}

export default function CoinDetailPage() {
  return <CoinRedirect />;
}
