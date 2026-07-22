"use client";

/**
 * 404 화면(§프론트 상태·UX). 없는 라우트 진입 시 백지 대신 복귀 경로를 제공한다.
 * 정적 export는 미매칭 경로를 이 트리로 렌더한다. 루트 레이아웃(헤더·토스터) 안에서 표시된다.
 */

import Link from "next/link";
import styles from "./error.module.css";

export default function NotFound() {
  return (
    <main className={styles.shell}>
      <div className={styles.panel}>
        <span className={styles.code}>404</span>
        <h1 className={styles.title}>페이지를 찾을 수 없어요</h1>
        <p className={styles.desc}>주소가 바뀌었거나 삭제된 페이지예요. 거래소로 돌아가 주세요.</p>
        <div className={styles.actions}>
          <Link href="/exchange" className="btn btn--primary">
            거래소 가기
          </Link>
          <Link href="/" className="btn btn--secondary">
            홈으로
          </Link>
        </div>
      </div>
    </main>
  );
}
