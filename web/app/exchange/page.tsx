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
  return null;
}
