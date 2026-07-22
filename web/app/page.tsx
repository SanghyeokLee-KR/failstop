"use client";

// 루트 → 홈 대시보드 이관. 정적 export라 클라이언트 replace 사용.

import { useEffect } from "react";
import { useRouter } from "next/navigation";

export default function Home() {
  const router = useRouter();
  useEffect(() => {
    router.replace("/home");
  }, [router]);
  return null;
}
