/**
 * 구 공개 시스템 상태 화면. 모니터링을 관리 평면(/admin)으로 이관하며 안내로 대체한다.
 * 일반 사용자에게 시스템 내부(노드·불변식·복구·이벤트)를 노출하지 않는다(관리자_설계 §2-3, §6).
 * 딥링크 보존을 위해 라우트는 유지하되 내부 지표는 렌더하지 않는다.
 */

import Link from "next/link";
import styles from "./page.module.css";

export default function SystemPage() {
  return (
    <main className={styles.page}>
      <section className={`card ${styles.notice}`} aria-label="시스템 상태 안내">
        <h1 className={styles.title}>시스템 상태는 관리 평면으로 이동했습니다</h1>
        <p className={styles.desc}>
          노드·불변식·복구 지표 등 운영 정보는 운영자 전용 관리 콘솔에서만 제공합니다. 관리 콘솔은 별도 호스트에서 mTLS
          클라이언트 인증서로만 접근할 수 있으며, 거래 서비스와 물리적으로 분리되어 있습니다.
        </p>
        <Link href="/exchange" className="btn btn--secondary">
          거래소로 돌아가기
        </Link>
      </section>
    </main>
  );
}
