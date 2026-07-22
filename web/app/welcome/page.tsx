import type { Metadata } from "next";
import Link from "next/link";
import styles from "./page.module.css";

export const metadata: Metadata = { title: "시작하기" };

/**
 * 화면 1. 온보딩. 히어로 + 하단 CTA 스택 (§8 화면1).
 * 보안 규칙: 실제 계정 생성·비밀번호 입력·인증 UI 없음(별도 인증 플로우 소관).
 * 본 캡스톤은 완전 시뮬레이션이라 두 CTA 모두 데모 세션(/exchange)으로 진입한다.
 */
export default function WelcomePage() {
  return (
    <main className={styles.root}>
      <section className={styles.hero}>
        <div className={styles.mark} aria-hidden="true">
          <svg width="30" height="30" viewBox="0 0 30 30">
            <rect x="4" y="4" width="22" height="22" rx="6" transform="rotate(45 15 15)" fill="var(--text-on-brand)" opacity=".92" />
          </svg>
        </div>
        <h1 className={styles.title}>
          쉽고 안전한
          <br />
          암호화폐 거래
        </h1>
        <p className={styles.sub}>간편하게 시작해요</p>
      </section>

      <div className={styles.cta}>
        <Link href="/exchange" className="btn btn--primary btn--full btn--xl">
          시작하기
        </Link>
        <Link href="/exchange" className="btn btn--secondary btn--full btn--xl">
          이미 계정이 있어요
        </Link>
        <p className={styles.terms}>가입하면 이용약관에 동의해요 · 시뮬레이션 데모예요</p>
      </div>
    </main>
  );
}
