"use client";

/**
 * 자체 catch를 빠져나간 미포착 Promise rejection만 다룬다(렌더 예외는 error.tsx 소관).
 * preventDefault를 부르지 않아 콘솔 진단 경로는 그대로 두고 통지만 얹는다.
 */

import { useEffect } from "react";
import { toast } from "@/components/Toast";
import { isBackpressure, messageOf, shouldNotify } from "@/components/globalErrorNotice";

export default function GlobalErrorReporter() {
  useEffect(() => {
    let lastMessage: string | null = null;
    let lastAt = 0;
    const onRejection = (e: PromiseRejectionEvent) => {
      // 백프레셔는 오류가 아니라 지연이다. 각 화면이 자기 자리에서 알린다(API 설계 2-3)
      if (isBackpressure(e.reason)) return;
      const message = messageOf(e.reason);
      const now = Date.now();
      if (!shouldNotify(message, lastMessage, now - lastAt)) return;
      lastMessage = message;
      lastAt = now;
      toast(message, "error");
    };
    window.addEventListener("unhandledrejection", onRejection);
    return () => window.removeEventListener("unhandledrejection", onRejection);
  }, []);
  return null;
}
