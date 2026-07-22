import type { Metadata } from "next";
import Link from "next/link";
import styles from "./page.module.css";

export const metadata: Metadata = { title: "시작하기" };

/**
 * 화면 1. 온보딩. 히어로 + 하단 CTA 스택 (§8 화면1).
 * CTA는 회원 가입·둘러보기 2안(회원_설계 §4-1). 둘러보기는 데모 세션으로 /exchange 진입.
 * 보안 규칙: 이 화면은 자격증명 입력 필드를 두지 않는다(가입은 /auth/signup 소관).
 */
export default function WelcomePage() {
  return (
    <main className={styles.root}>
      <section className={styles.hero}>
        <div className={styles.mark} aria-hidden="true">
          <svg width="30" height="30" viewBox="0 0 30 30">
            <rect x="4" y="4" width="22" height="22" rx="6" transform="rotate(45 15 15)" fill="var(--brand-primary)" />
          </svg>
        </div>
        <h1 className={styles.title}>
          암호화폐를
          <br />
          실시간으로 거래
        </h1>
        <p className={styles.sub}>호가와 체결이 살아 있는 모의 거래소</p>
      </section>

      <div className={styles.cta}>
        <Link href="/auth/signup" className="btn btn--primary btn--full btn--xl">
          회원 가입
        </Link>
        <Link href="/exchange" className="btn btn--secondary btn--full btn--xl">
          로그인 없이 둘러보기
        </Link>
        <p className={styles.terms}>
          이미 회원이신가요?{" "}
          <Link href="/auth/login" className={styles.termsLink}>
            로그인
          </Link>
        </p>
      </div>
    </main>
  );
}
