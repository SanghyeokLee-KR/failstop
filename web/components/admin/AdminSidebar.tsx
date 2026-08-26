"use client";

/**
 * 관리 평면 좌측 사이드바(§4-1). 거래 UI의 HeaderNav를 쓰지 않는다.
 * 고정형 데스크톱 전제. 축소 변형 없음.
 * 내비는 의미 그룹(상황·제어·거버넌스)으로 구획하고 제도 어휘 아이콘을 병기한다.
 */

import Link from "next/link";
import { usePathname } from "next/navigation";
import { AdminIcon, type AdminIconName } from "./icons";
import styles from "./admin.module.css";

interface AdminNavItem {
  href: string;
  label: string;
  icon: AdminIconName;
  exact?: boolean;
}

interface AdminNavGroup {
  label: string;
  items: AdminNavItem[];
}

const NAV_GROUPS: AdminNavGroup[] = [
  {
    label: "상황",
    items: [
      { href: "/admin", label: "종합 상태", icon: "overview", exact: true },
      { href: "/admin/monitoring", label: "모니터링", icon: "monitoring" },
    ],
  },
  {
    label: "제어",
    items: [
      { href: "/admin/bots", label: "봇 플릿", icon: "bots" },
      { href: "/admin/chaos", label: "카오스", icon: "chaos" },
      { href: "/admin/config", label: "마켓·공지", icon: "config" },
    ],
  },
  {
    label: "거버넌스",
    items: [
      { href: "/admin/operators", label: "운영자", icon: "operators" },
      { href: "/admin/audit", label: "감사 로그", icon: "audit" },
    ],
  },
];

export default function AdminSidebar() {
  const pathname = usePathname();
  const isActive = (n: AdminNavItem) => (n.exact ? pathname === n.href : pathname === n.href || pathname.startsWith(`${n.href}/`));

  return (
    <aside className={styles.sidebar} aria-label="관리 메뉴">
      <div className={styles.brand}>
        <span className={styles.brandMark} aria-hidden="true">
          <svg width="18" height="18" viewBox="0 0 30 30">
            <rect x="6" y="6" width="18" height="18" rx="5" transform="rotate(45 15 15)" fill="currentColor" />
          </svg>
        </span>
        <span className={styles.brandText}>관리 콘솔</span>
      </div>
      <nav className={styles.nav} aria-label="관리 화면">
        {NAV_GROUPS.map((g) => (
          <div key={g.label} className={styles.navGroup}>
            <span className={styles.navGroupLabel}>{g.label}</span>
            {g.items.map((n) => {
              const active = isActive(n);
              return (
                <Link
                  key={n.href}
                  href={n.href}
                  className={styles.navLink}
                  data-active={active || undefined}
                  aria-current={active ? "page" : undefined}
                >
                  <span className={styles.navIcon} aria-hidden="true">
                    <AdminIcon name={n.icon} />
                  </span>
                  {n.label}
                </Link>
              );
            })}
          </div>
        ))}
      </nav>
    </aside>
  );
}
