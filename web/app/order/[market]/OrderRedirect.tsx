"use client";

// /order/[market]?side=&price= → /exchange/[market]?side=&price= (§1.2)
// 서버 redirect는 쿼리 승계가 불가하므로 반드시 클라이언트 처리. 쿼리는 OrderPanel 프리필로 승계.

import { Suspense, useEffect } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";

function OrderRedirectBody() {
  const params = useParams<{ market: string }>();
  const search = useSearchParams();
  const router = useRouter();

  useEffect(() => {
    const market = decodeURIComponent(params.market);
    const qs = new URLSearchParams();
    const side = search.get("side");
    const price = search.get("price");
    if (side) qs.set("side", side);
    if (price) qs.set("price", price);
    const suffix = qs.toString();
    router.replace(`/exchange/${market}${suffix ? `?${suffix}` : ""}`);
  }, [params.market, router, search]);

  return null;
}

export default function OrderRedirect() {
  // useSearchParams는 정적 export에서 Suspense 경계 필수
  return (
    <Suspense fallback={null}>
      <OrderRedirectBody />
    </Suspense>
  );
}
