"use client";

// 구 마켓 목록 → 거래 화면 이관 (§1.2). 경로는 딥링크 보존을 위해 유지.

import { useEffect } from "react";
import { useRouter } from "next/navigation";

export default function MarketPage() {
  const router = useRouter();
  useEffect(() => {
    router.replace("/exchange");
  }, [router]);
  return null;
}
