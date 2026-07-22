"use client";

/**
 * 관리 평면 좌측 사이드바(§4-1). 거래 UI의 HeaderNav를 쓰지 않는다.
 * 고정형 데스크톱 전제. 축소 변형 없음.
 */

import Link from "next/link";
import { usePathname } from "next/navigation";
import styles from "./admin.module.css";

interface AdminNav {
  href: string;
  label: string;
  exact?: boolean;
}

const NAV: AdminNav[] = [
  { href: "/admin", label: "종합 상태", exact: true },
  { href: "/admin/monitoring", label: "모니터링" },
  { href: "/admin/bots", label: "봇 플릿" },
  { href: "/admin/chaos", label: "카오스" },
  { href: "/admin/config", label: "마켓·공지" },
  { href: "/admin/operators", label: "운영자" },
  { href: "/admin/audit", label: "감사 로그" },
];

export default function AdminSidebar() {
  const pathname = usePathname();
  const isActive = (n: AdminNav) => (n.exact ? pathname === n.href : pathname === n.href || pathname.startsWith(`${n.href}/`));

  return (
    <aside className={styles.sidebar} aria-label="관리 메뉴">
      <div className={styles.brand}>
        <span className={styles.brandMark} aria-hidden="true">
          <svg width="18" height="18" viewBox="0 0 30 30">
            <rect x="6" y="6" width="18" height="18" rx="5" transform="rotate(45 15 15)" fill="currentColor" />
          </svg>
        </span>
        <span className={styles.brandText}>
          관리 콘솔
          <small className={styles.brandSub}>admin plane</small>
        </span>
      </div>
      <nav className={styles.nav} aria-label="관리 화면">
        {NAV.map((n) => {
          const active = isActive(n);
          return (
            <Link
              key={n.href}
              href={n.href}
              className={styles.navLink}
              data-active={active || undefined}
              aria-current={active ? "page" : undefined}
            >
              {n.label}
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}
