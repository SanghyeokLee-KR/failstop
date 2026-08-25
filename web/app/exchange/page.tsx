"use client";

// /exchange. 최근 마켓(없으면 KRW-BTC)으로 이동 (§1.2)
// 정적 export라 서버 redirect 불가, 클라이언트 replace 사용

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { readRecentMarket } from "./recentMarket";

export default function ExchangeIndexPage() {
  const router = useRouter();
  useEffect(() => {
    router.replace(`/exchange/${readRecentMarket()}`);
  }, [router]);
  // null을 반환하면 이동이 끝날 때까지 백지가 보인다. 보통은 한 프레임이지만 콜드 로드·저사양
  // 단말에서는 체감되고, 스크립트가 죽으면 영구 백지가 된다. 이동 중임을 알리는 최소 화면을 둔다.
  return (
    <main className="route-transition" aria-busy="true">
      <h1 className="sr-only">거래소</h1>
      <p className="route-transition__text">거래 화면으로 이동하고 있어요</p>
    </main>
  );
}
