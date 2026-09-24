"use client";

/**
 * 관리 평면 좌측 사이드바(§4-1). 거래 UI의 HeaderMenu를 쓰지 않는다.
 * 고정형 데스크톱 전제. 접으면 아이콘 기둥만 남는다(2026-09-23 사용자 요청). 서비스 흐름 구성도처럼
 * 넓게 봐야 하는 화면에서 본문 폭을 돌려받으려는 것이다. 접힌 상태는 브라우저에 기억해 화면을
 * 옮겨도 유지한다. 저장소를 못 쓰는 환경에서는 매번 펼친 채로 시작할 뿐 동작은 같다.
 *
 * 내비 목록은 이 파일이 갖지 않는다. 운영 관제와 백오피스가 같은 셸 모양을 쓰되 다른
 * 목록을 싣기 때문이고, 목록을 여기 박으면 두 평면이 한 배열을 나눠 쓰게 되어 갈라 놓은
 * 의미가 없어진다. 목록의 정본은 lib/admin/nav.ts다.
 */

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import BrandMark from "@/components/BrandMark";
import type { AdminNavGroup, AdminNavItem } from "@/lib/admin/nav";
import { NavBadge } from "./control/NavStatus";
import { AdminIcon } from "./icons";
import styles from "./admin.module.css";

const COLLAPSE_KEY = "admin.sidebar.collapsed";

export default function AdminSidebar({
  title,
  groups,
  crossLink,
  foot,
}: {
  title: string;
  groups: AdminNavGroup[];
  /** 다른 평면으로 건너가는 링크. 두 평면이 서로를 모르면 운영자가 주소를 외워야 한다 */
  crossLink: { href: string; label: string };
  /** 평면 전환 링크 위에 두는 상태 줄. 운영 관제는 AI 운영 모드를 싣는다 */
  foot?: ReactNode;
}) {
  const pathname = usePathname();
  const isActive = (n: AdminNavItem) =>
    n.exact ? pathname === n.href : pathname === n.href || pathname.startsWith(`${n.href}/`);
  const [collapsed, setCollapsed] = useState(false);

  // 첫 그리기는 서버와 같게 펼친 채로 하고, 기억한 값은 붙은 뒤에 읽는다. 서버에는 저장소가 없어
  // 처음부터 읽으면 수화 불일치가 난다
  useEffect(() => {
    try {
      if (window.localStorage.getItem(COLLAPSE_KEY) === "1") setCollapsed(true);
    } catch {
      // 저장소가 막힌 환경(사생활 모드 등)은 펼친 채로 둔다
    }
  }, []);

  const toggle = () => {
    const next = !collapsed;
    setCollapsed(next);
    try {
      window.localStorage.setItem(COLLAPSE_KEY, next ? "1" : "0");
    } catch {
      // 기억만 못 할 뿐 지금 화면의 접기는 그대로 된다
    }
  };

  return (
    <aside className={styles.sidebar} data-collapsed={collapsed || undefined} aria-label="관리 메뉴">
      <div className={styles.brand}>
        <span className={styles.brandMark} aria-hidden="true">
          <BrandMark size={18} />
        </span>
        <span className={styles.brandText}>{title}</span>
        {/* 이름은 바꾸지 않고 펼침 여부는 aria-expanded 로 알린다. 이름을 접기·펼치기로 뒤집으면
            "펼치기, 펼쳐짐"처럼 상태와 반대로 들린다 */}
        <button
          type="button"
          className={styles.sidebarToggle}
          aria-expanded={!collapsed}
          aria-controls="admin-nav"
          aria-label="관리 메뉴"
          title={collapsed ? "메뉴 펼치기" : "메뉴 접기"}
          onClick={toggle}
        >
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <rect x="2" y="2.75" width="12" height="10.5" rx="1.5" />
            <path d="M6 2.75 V13.25" />
            {collapsed ? <path d="M8.75 6.25 L10.5 8 L8.75 9.75" /> : <path d="M10.5 6.25 L8.75 8 L10.5 9.75" />}
          </svg>
        </button>
      </div>
      <nav id="admin-nav" className={styles.nav} aria-label="관리 화면">
        {groups.map((g) => (
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
                  title={collapsed ? n.label : undefined}
                >
                  <span className={styles.navIcon} aria-hidden="true">
                    <AdminIcon name={n.icon} />
                  </span>
                  <span className={styles.navLabel}>{n.label}</span>
                  {n.badge && (
                    <span className={styles.navBadgeSlot}>
                      <NavBadge kind={n.badge} />
                    </span>
                  )}
                </Link>
              );
            })}
          </div>
        ))}
      </nav>
      {foot != null && <div className={styles.navFoot}>{foot}</div>}
      {/* 평면 전환은 내비 항목이 아니라 바닥의 별도 링크다. 같은 목록에 섞으면
          "이 콘솔의 화면 하나"로 읽혀 두 평면을 가른 의미가 흐려진다 */}
      <Link href={crossLink.href} className={styles.navCross} title={collapsed ? crossLink.label : undefined}>
        <svg className={styles.navCrossIcon} width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M3 5.5 H12.5 M10 3 L12.5 5.5 L10 8" />
          <path d="M13 10.5 H3.5 M6 8 L3.5 10.5 L6 13" />
        </svg>
        <span className={styles.navLabel}>{crossLink.label}</span>
      </Link>
    </aside>
  );
}
