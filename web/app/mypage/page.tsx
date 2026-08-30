"use client";

/**
 * 자산·주문 본체는 /assets·/history로 딥링크한다(§4-4).
 * 미로그인 상태에서는 가입 유도 카드만 노출한다(§4-1 보호 규약).
 */

import Link from "next/link";
import { useEffect, useMemo } from "react";
import { useExchangeStore, useMemberSession } from "@/lib/store";
import { addDecimal, compareDecimal } from "@/lib/decimal";
import { formatKRW } from "@/lib/format";
import styles from "./page.module.css";
import { usePageTitle } from "@/components/PageTitle";
import { ConnectionBanner } from "@/components/StatusSignals";
import Skeleton from "@/components/Skeleton";

export default function MyPage() {
  usePageTitle("마이페이지");
  const session = useMemberSession();
  const assets = useExchangeStore((s) => s.assets);
  const openOrders = useExchangeStore((s) => s.openOrders);
  const closedOrders = useExchangeStore((s) => s.closedOrders);
  /* 전역이 드는 것은 체결 목록 첫 쪽이다. 그 길이를 총계로 말하면 100건이 넘는 사용자에게
     틀린 수를 사실로 보여준다. 잘렸을 때는 하한만 말하고 정확한 수는 거래내역이 센다 */
  const closedTruncated = useExchangeStore((s) => s.closedOrdersTruncated);
  /* 0은 빈 상태가 아니라 아직 모르는 값이다. 구분 없이 그리면 콜드 진입 첫 프레임에
     "0원 0종목 0건"을 사실로 표시하고 값이 튄다. 사용자는 그 깜빡임을 오류로 읽는다
     (스토어 noticesLoaded 주석과 같은 실패 모드) */
  const accountsLoaded = useExchangeStore((s) => s.accountsLoaded);
  const ordersLoaded = useExchangeStore((s) => s.ordersLoaded);
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
        {/* 연결 끊김은 전역 상태다. 화면마다 알려주다 말면 사용자는 배너 없음을 정상으로
            읽는다. 이 화면은 주문 취소·잔고 판독이 걸려 있어 특히 그렇다 */}
        <ConnectionBanner />
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
      <section className={`card ${styles.profile}`} aria-label="프로필">
        <span className={styles.avatar} aria-hidden="true">
          {initial}
        </span>
        <div className={styles.profileText}>
          <b className={styles.displayName}>{user.display_name}</b>
          <span className={styles.email}>{user.email}</span>
        </div>
        {/* 초록 배지를 쓰지 않는다. 이 화면은 회원 세션에서만 열리므로 값이 항상 같고,
            정보량이 0인 표식에 성공색을 쓰면 "정상은 잉크" 규칙이 무너진다. 무엇이
            정상인지 색으로 말하기 시작하면 진짜 경보가 묻힌다. 계정 종류는 잉크로 적는다 */}
        <span className={styles.accountKind}>회원 계정</span>
      </section>

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
            {accountsLoaded ? (
              <b className={`${styles.statValue} num`}>
                {formatKRW(cash)}
                <small>원</small>
              </b>
            ) : (
              <Skeleton variant="text" width={116} height={22} />
            )}
          </div>
          <div className={styles.stat}>
            <span className={styles.statLabel}>보유 코인</span>
            {accountsLoaded ? (
              <b className={`${styles.statValue} num`}>{holdingsCount}종목</b>
            ) : (
              <Skeleton variant="text" width={56} height={22} />
            )}
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
            {ordersLoaded ? (
              <b className={`${styles.statValue} num`}>{openOrders.length}건</b>
            ) : (
              <Skeleton variant="text" width={44} height={22} />
            )}
          </div>
          <div className={styles.stat}>
            <span className={styles.statLabel}>체결·취소</span>
            {ordersLoaded ? (
              <b className={`${styles.statValue} num`}>
                {closedOrders.length}건{closedTruncated ? " 이상" : ""}
              </b>
            ) : (
              <Skeleton variant="text" width={64} height={22} />
            )}
          </div>
        </section>
      </div>
    </main>
  );
}
