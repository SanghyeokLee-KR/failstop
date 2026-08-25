"use client";

/**
 * 전역 비동기 실패 통지.
 *
 * 데이터 경로 대부분은 자체 catch와 재시도 UI를 갖지만, 그 그물을 빠져나간 rejection은 콘솔에만
 * 남고 화면에는 아무 표시가 없다. 사용자는 "눌렀는데 아무 일도 안 일어난다"로만 인지한다.
 * 실제로 백엔드 수용 제한(429 too_many_requests)에 걸렸을 때 콘솔에만 찍히고 화면은 조용했다.
 *
 * 렌더 예외는 error.tsx·global-error.tsx가 받는다. 여기서 다루는 것은 렌더 밖에서 발생해
 * 아무도 잡지 않은 Promise rejection뿐이다.
 *
 * 잡지는 않는다. preventDefault를 호출하지 않으므로 콘솔 경고와 기존 진단 경로는 그대로 남고,
 * 사용자에게 통지만 얹는다. 판정 로직은 globalErrorNotice에 두어 DOM 없이 테스트한다.
 */

import { useEffect } from "react";
import { toast } from "@/components/Toast";
import { messageOf, shouldNotify } from "@/components/globalErrorNotice";

export default function GlobalErrorReporter() {
  useEffect(() => {
    let lastMessage: string | null = null;
    let lastAt = 0;
    const onRejection = (e: PromiseRejectionEvent) => {
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
