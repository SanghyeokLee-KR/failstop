"use client";

/**
 * 마이페이지(§4-4). 회원 세션 허브: 프로필 + 자산 요약 + 주문 요약.
 * 자산·주문 본체는 /assets·/history로 딥링크한다(레이아웃 재사용, 별도 신규 레이아웃 없음).
 * 데이터는 DataSource가 회원 세션 Bearer로 조회한다(store loadAccounts·loadOrders).
 * 미로그인(데모 둘러보기) 상태에서는 가입 유도 카드만 노출한다(§4-1 보호 규약).
 */

import Link from "next/link";
import { useEffect, useMemo } from "react";
import { useExchangeStore, useMemberSession } from "@/lib/store";
import { addDecimal, compareDecimal } from "@/lib/decimal";
import { formatKRW } from "@/lib/format";
import styles from "./page.module.css";
import { usePageTitle } from "@/components/PageTitle";

export default function MyPage() {
  usePageTitle("마이페이지");
  const session = useMemberSession();
  const assets = useExchangeStore((s) => s.assets);
  const openOrders = useExchangeStore((s) => s.openOrders);
  const closedOrders = useExchangeStore((s) => s.closedOrders);
  const loadAccounts = useExchangeStore((s) => s.loadAccounts);
  const loadOrders = useExchangeStore((s) => s.loadOrders);

  useEffect(() => {
    if (!session) return;
    void loadAccounts();
    void loadOrders();
  }, [session, loadAccounts, loadOrders]);

  // 금액은 문자열 십진으로 다룬다. float 합산은 NUMERIC(40,18) 정밀을 잃고 2^53 상한에 걸린다
  const cash = useMemo(() => {
    const krw = assets.find((a) => a.currency === "KRW");
    return krw ? addDecimal(krw.balance, krw.locked) : "0";
  }, [assets]);

  const holdingsCount = useMemo(
    () =>
      assets.filter(
        (a) => a.currency !== "KRW" && compareDecimal(addDecimal(a.balance, a.locked), "0") > 0,
      ).length,
    [assets],
  );

  if (!session) {
    return (
      <main className={styles.page}>
        <section className={`card ${styles.gate}`} aria-labelledby="gate-title">
          <h1 id="gate-title" className={styles.gateTitle}>
            회원 전용 페이지
          </h1>
          <p className={styles.gateDesc}>
            지금은 둘러보기 모드. 회원 가입하면 개인 자산과 주문 이력을 이 페이지에서 관리 가능.
          </p>
          <div className={styles.gateCta}>
            <Link href="/auth/signup" className="btn btn--primary btn--full btn--xl">
              회원 가입
            </Link>
            <Link href="/auth/login" className="btn btn--secondary btn--full">
              로그인
            </Link>
          </div>
        </section>
      </main>
    );
  }

  const user = session.user;
  const initial = user.display_name.trim().charAt(0).toUpperCase() || "?";

  return (
    <main className={styles.page}>
      {/* ===== 프로필 카드 ===== */}
      <section className={`card ${styles.profile}`} aria-label="프로필">
        <span className={styles.avatar} aria-hidden="true">
          {initial}
        </span>
        <div className={styles.profileText}>
          <b className={styles.displayName}>{user.display_name}</b>
          <span className={styles.email}>{user.email}</span>
        </div>
        <span className="status-pill" data-health="success">
          ● 회원 계정
        </span>
      </section>

      {/* ===== 요약 카드 2열 ===== */}
      <div className={styles.grid}>
        <section className={`card ${styles.summary}`} aria-label="자산 요약">
          <div className={styles.summaryHead}>
            <h2 className={styles.summaryTitle}>자산</h2>
            <Link href="/assets" className={styles.deepLink}>
              자세히 보기
            </Link>
          </div>
          <div className={styles.stat}>
            <span className={styles.statLabel}>보유현금</span>
            <b className={`${styles.statValue} num`}>
              {formatKRW(cash)}
              <small>원</small>
            </b>
          </div>
          <div className={styles.stat}>
            <span className={styles.statLabel}>보유 코인</span>
            <b className={`${styles.statValue} num`}>{holdingsCount}종목</b>
          </div>
        </section>

        <section className={`card ${styles.summary}`} aria-label="주문 요약">
          <div className={styles.summaryHead}>
            <h2 className={styles.summaryTitle}>주문</h2>
            <Link href="/history" className={styles.deepLink}>
              거래내역 보기
            </Link>
          </div>
          <div className={styles.stat}>
            <span className={styles.statLabel}>미체결</span>
            <b className={`${styles.statValue} num`}>{openOrders.length}건</b>
          </div>
          <div className={styles.stat}>
            <span className={styles.statLabel}>체결·취소</span>
            <b className={`${styles.statValue} num`}>{closedOrders.length}건</b>
          </div>
        </section>
      </div>
    </main>
  );
}
