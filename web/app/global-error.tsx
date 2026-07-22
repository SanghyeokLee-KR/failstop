"use client";

/**
 * 전역 에러 바운더리(§프론트 상태·UX). 루트 레이아웃 자체가 던진 예외를 흡수한다.
 * 레이아웃을 대체하므로 html·body를 직접 렌더하고 토큰·전역 스타일을 자체 로드한다.
 * 헤더·토스터 크롬은 없다. 최소 복구 경로(재시도·홈)만 제공한다.
 */

import { useEffect } from "react";
import "@/styles/tokens.css";
import "./globals.css";
import styles from "./error.module.css";

interface Props {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function GlobalError({ error, reset }: Props) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <html lang="ko">
      <body className={styles.rootFallback}>
        <main className={styles.shell}>
          <div className={styles.panel} role="alert">
            <span className={styles.mark} aria-hidden="true" />
            <h1 className={styles.title}>앱을 불러오지 못했어요</h1>
            <p className={styles.desc}>일시적인 문제가 생겼어요. 다시 시도하거나 새로고침해 주세요.</p>
            <div className={styles.actions}>
              <button type="button" className="btn btn--primary" onClick={() => reset()}>
                다시 시도
              </button>
              <a href="/" className="btn btn--secondary">
                홈으로
              </a>
            </div>
          </div>
        </main>
      </body>
    </html>
  );
}
