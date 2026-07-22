"use client";

/**
 * HeaderNav. 전 화면 공통 상단 내비 (§1.1).
 * 로고 + 링크 4개 + WS 연결 상태 + 테마 토글.
 * 고정형 정책(§4)에 따라 축소 변형 없음. 전 해상도 동일 구성.
 * 연결 상태는 스토어 connected가 원천이며 DataSource.onConnectionChange로만 갱신된다.
 */

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
import ThemeToggle from "./ThemeToggle";
import { useExchangeStore } from "@/lib/store";
import { getDataSource } from "@/lib/api/datasource";
import styles from "./HeaderNav.module.css";

interface NavLink {
  href: string;
  label: string;
  /** active 판정용 경로 프리픽스. 레거시 경로도 같은 링크로 귀속 */
  match: string[];
}

// 시스템 내부 상태는 관리 평면(/admin)으로 이관했다. 공개 내비에 노출하지 않는다(관리자_설계 §2-3)
const LINKS: NavLink[] = [
  { href: "/home", label: "홈", match: ["/home"] },
  { href: "/exchange", label: "거래소", match: ["/exchange", "/market", "/coin", "/order"] },
  { href: "/assets", label: "자산", match: ["/assets"] },
  { href: "/history", label: "거래내역", match: ["/history"] },
];

export default function HeaderNav() {
  const pathname = usePathname();
  const connected = useExchangeStore((s) => s.connected);
  const setConnected = useExchangeStore((s) => s.setConnected);

  useEffect(() => getDataSource().onConnectionChange(setConnected), [setConnected]);

  // 관리 평면은 자체 셸(AdminLayout)을 쓴다. 공개 HeaderNav를 렌더하지 않는다(§4-1)
  if (pathname === "/admin" || pathname.startsWith("/admin/")) return null;

  const isActive = (link: NavLink) =>
    link.match.some((m) => pathname === m || pathname.startsWith(`${m}/`));

  return (
    <header className={styles.root}>
      <div className={styles.inner}>
        <Link href="/home" className={styles.logo} aria-label="홈으로 이동">
          <span className={styles.logoMark} aria-hidden="true">
            <svg width="20" height="20" viewBox="0 0 30 30">
              <rect x="6" y="6" width="18" height="18" rx="5" transform="rotate(45 15 15)" fill="currentColor" />
            </svg>
          </span>
          <span className={styles.logoText}>가상거래소</span>
        </Link>

        <nav className={styles.nav} aria-label="주요 메뉴">
          {LINKS.map((link) => {
            const active = isActive(link);
            return (
              <Link
                key={link.href}
                href={link.href}
                className={styles.navLink}
                data-active={active || undefined}
                aria-current={active ? "page" : undefined}
              >
                {link.label}
              </Link>
            );
          })}
        </nav>

        <div className={styles.right}>
          <span className={styles.conn} data-connected={connected || undefined} role="status">
            <span className={styles.connDot} aria-hidden="true" />
            <span className={styles.connLabel}>{connected ? "연결됨" : "연결 끊김"}</span>
          </span>
          <ThemeToggle />
        </div>
      </div>
    </header>
  );
}
