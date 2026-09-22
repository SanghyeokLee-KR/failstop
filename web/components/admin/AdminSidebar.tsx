"use client";

/**
 * 관리 평면 좌측 사이드바(§4-1). 거래 UI의 HeaderMenu를 쓰지 않는다.
 * 고정형 데스크톱 전제. 축소 변형 없음.
 *
 * 내비 목록은 이 파일이 갖지 않는다. 운영 관제와 백오피스가 같은 셸 모양을 쓰되 다른
 * 목록을 싣기 때문이고, 목록을 여기 박으면 두 평면이 한 배열을 나눠 쓰게 되어 갈라 놓은
 * 의미가 없어진다. 목록의 정본은 lib/admin/nav.ts다.
 */

import Link from "next/link";
import { usePathname } from "next/navigation";
import BrandMark from "@/components/BrandMark";
import type { AdminNavGroup, AdminNavItem } from "@/lib/admin/nav";
import { AdminIcon } from "./icons";
import styles from "./admin.module.css";

export default function AdminSidebar({
  title,
  groups,
  crossLink,
}: {
  title: string;
  groups: AdminNavGroup[];
  /** 다른 평면으로 건너가는 링크. 두 평면이 서로를 모르면 운영자가 주소를 외워야 한다 */
  crossLink: { href: string; label: string };
}) {
  const pathname = usePathname();
  const isActive = (n: AdminNavItem) =>
    n.exact ? pathname === n.href : pathname === n.href || pathname.startsWith(`${n.href}/`);

  return (
    <aside className={styles.sidebar} aria-label="관리 메뉴">
      <div className={styles.brand}>
        <span className={styles.brandMark} aria-hidden="true">
          <BrandMark size={18} />
        </span>
        <span className={styles.brandText}>{title}</span>
      </div>
      <nav className={styles.nav} aria-label="관리 화면">
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
      {/* 평면 전환은 내비 항목이 아니라 바닥의 별도 링크다. 같은 목록에 섞으면
          "이 콘솔의 화면 하나"로 읽혀 두 평면을 가른 의미가 흐려진다 */}
      <Link href={crossLink.href} className={styles.navCross}>
        {crossLink.label}
      </Link>
    </aside>
  );
}
