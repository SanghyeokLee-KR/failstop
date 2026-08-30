"use client";

/**
 * 정적 export라 서버 판정이 불가해 메모리 세션과 localStorage 표식만 보고 동기로 끝낸다.
 * 어떤 실패도 /home으로 수렴시켜 데모 진행을 막지 않는다.
 */

import { useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { getDataSource } from "@/lib/api/datasource";
import { entryPathFor, hasOnboarded, markOnboarded } from "@/lib/entryGate";

export default function Home() {
  const router = useRouter();
  // 판정은 실행당 1회다. 표식을 남기는 부수효과가 있어 이펙트가 두 번 돌면(StrictMode)
  // 2회차가 자기 표식을 읽고 홈으로 보내 첫 방문자가 온보딩을 못 본다
  const decided = useRef(false);
  useEffect(() => {
    if (decided.current) return;
    decided.current = true;
    let next = "/home";
    try {
      // getDataSource는 첫 호출에 연결을 연다. 레이아웃의 HeaderNav가 먼저 부르므로
      // 여기서 새로 열리는 연결은 없다
      next = entryPathFor(getDataSource().getMemberSession() !== null, hasOnboarded());
      if (next === "/welcome") markOnboarded();
    } catch {
      next = "/home";
    }
    router.replace(next);
  }, [router]);
  return null;
}
