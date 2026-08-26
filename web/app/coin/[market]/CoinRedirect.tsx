"use client";

// /coin/[market] → /exchange/[market] 클라이언트 이관 (§1.2)

import { useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import RedirectShell from "@/components/RedirectShell";

export default function CoinRedirect() {
  const params = useParams<{ market: string }>();
  const router = useRouter();

  useEffect(() => {
    router.replace(`/exchange/${decodeURIComponent(params.market)}`);
  }, [params.market, router]);

  return <RedirectShell />;
}
