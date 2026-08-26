"use client";

/**
 * 라우트 에러 바운더리(§프론트 상태·UX). 정적 export에서 클라이언트 렌더 중 미포착 예외를
 * 흡수해 백지·언마운트를 막는다. reset으로 세그먼트 재마운트, 홈 이동을 함께 제공한다.
 * 실시간 메시지·스키마 드리프트 등 예외 유입이 잦은 트레이딩 UI의 복구 경로.
 */

import Link from "next/link";
import { useEffect } from "react";
import styles from "./error.module.css";

interface Props {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function RouteError({ error, reset }: Props) {
  useEffect(() => {
    // 진단 근거만 남긴다. 외부 전송 없음(런타임 외부 의존 금지)
    console.error(error);
  }, [error]);

  return (
    <main className={styles.shell}>
      <div className={styles.panel} role="alert">
        <span className={styles.mark} aria-hidden="true" />
        <h1 className={styles.title}>화면 표시 실패</h1>
        <p className={styles.desc}>일시적 문제 발생. 다시 시도하거나 홈으로 이동.</p>
        <div className={styles.actions}>
          <button type="button" className="btn btn--primary" onClick={() => reset()}>
            다시 시도
          </button>
          <Link href="/" className="btn btn--secondary">
            홈으로
          </Link>
        </div>
      </div>
    </main>
  );
}
