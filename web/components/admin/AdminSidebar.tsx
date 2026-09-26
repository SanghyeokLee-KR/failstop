"use client";

/**
 * 관리 평면 좌측 사이드바(§4-1). 거래 UI의 HeaderMenu를 쓰지 않는다.
 * 고정형 데스크톱 전제. 접으면 아이콘 기둥만 남는다(2026-09-23 사용자 요청). 서비스 흐름 구성도처럼
 * 넓게 봐야 하는 화면에서 본문 폭을 돌려받으려는 것이다. 접힌 상태는 브라우저에 기억해 화면을
 * 옮겨도 유지한다. 저장소를 못 쓰는 환경에서는 매번 펼친 채로 시작할 뿐 동작은 같다.
 *
 * 묶음 이름을 누르면 그 묶음을 접고 편다. 1280x800 창에서도 목록 끝이 스크롤 없이 보이도록 기본으로 접는
 * 묶음(closedByDefault)은 접힌 채로 시작하고, 지금 화면이 든 묶음은 접혀 있어도 펼쳐 보인다. 운영자가 펴거나
 * 접은 선택은 묶음마다 이 브라우저에 기억하고 기본보다 앞선다(관리자 설계 4-10-1). 접힌 묶음 이름 옆에는
 * 항목 수와 그 안의 상태 배지를 적어 경보가 가려지지 않게 한다.
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
import { ROLE_LABEL } from "@/lib/admin/roles";
import { useAdminSession } from "@/lib/admin/store";
import { NavBadge } from "./control/NavStatus";
import { AdminIcon } from "./icons";
import { SessionLeft } from "./OperatorBar";
import styles from "./admin.module.css";

const COLLAPSE_KEY = "admin.sidebar.collapsed";
/** 묶음 이름별 펼침 선택({ 이름: 폈으면 true }). 접은 묶음만 적던 옛 키(admin.nav.closed)와 뜻이 달라 새 키를 쓴다 */
const GROUPS_KEY = "admin.nav.groups";

/** 사이드바 바닥의 사용자 카드. 누가 어떤 역할로 들어와 있고 세션이 얼마나 남았는지 늘 보인다 */
function SessionCard({ collapsed }: { collapsed: boolean }) {
  const session = useAdminSession();
  if (!session) return null;
  const primary = session.roles.includes("SUPER") ? "SUPER" : session.roles[0];
  const role = primary ? ROLE_LABEL[primary] : "역할 없음";
  return (
    <div className={styles.sessionCard} title={collapsed ? `${session.displayName} · ${role}` : undefined}>
      <span className={styles.avatar} aria-hidden="true">
        {session.displayName.slice(0, 1)}
      </span>
      <span className={styles.sessionCardText}>
        <span className={styles.sessionCardName}>{session.displayName}</span>
        <span className={styles.sessionCardMeta}>
          {role} · {session.operatorId}
          {/* 매초 바뀌는 남은 시간은 낭독하지 않는다. 만료 임박은 운영자 바의 알림 리전이 알린다 */}
          <span aria-hidden="true">
            {" "}
            · 세션{" "}
            <span className="num">
              <SessionLeft expiresAt={session.expiresAt} />
            </span>
          </span>
        </span>
      </span>
    </div>
  );
}

function NavItemLink({ n, active, collapsed }: { n: AdminNavItem; active: boolean; collapsed: boolean }) {
  return (
    <Link
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
}

export default function AdminSidebar({
  title,
  groups,
  footLinks = [],
  crossLink,
  brandAction,
  foot,
}: {
  title: string;
  groups: AdminNavGroup[];
  /** 바닥에 평면 전환 링크와 나란히 두는 화면(운영 관제의 설정) */
  footLinks?: AdminNavItem[];
  /** 다른 평면으로 건너가는 링크. 두 평면이 서로를 모르면 운영자가 주소를 외워야 한다 */
  crossLink: { href: string; label: string };
  /** 명판 줄의 접기 단추 옆 자리. 운영 관제는 AI 상담 단추를 싣는다 */
  brandAction?: ReactNode;
  /** 평면 전환 링크 위에 두는 상태 줄. 운영 관제는 AI 운영 모드를 싣는다 */
  foot?: ReactNode;
}) {
  const pathname = usePathname();
  const isActive = (n: AdminNavItem) =>
    n.exact ? pathname === n.href : pathname === n.href || pathname.startsWith(`${n.href}/`);
  const [collapsed, setCollapsed] = useState(false);
  const [picked, setPicked] = useState<Record<string, boolean>>({});

  // 첫 그리기는 서버와 같게 하고, 기억한 값은 붙은 뒤에 읽는다. 서버에는 저장소가 없어
  // 처음부터 읽으면 수화 불일치가 난다
  useEffect(() => {
    try {
      if (window.localStorage.getItem(COLLAPSE_KEY) === "1") setCollapsed(true);
      const saved = window.localStorage.getItem(GROUPS_KEY);
      if (saved !== null) setPicked(JSON.parse(saved) as Record<string, boolean>);
    } catch {
      // 저장소가 막힌 환경(사생활 모드 등)은 기본 모양으로 둔다
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

  const toggleGroup = (label: string, open: boolean) => {
    const next = { ...picked, [label]: !open };
    setPicked(next);
    try {
      window.localStorage.setItem(GROUPS_KEY, JSON.stringify(next));
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
        <span className={styles.brandTools}>
          {brandAction}
          {/* 이름은 바꾸지 않고 펼침 여부는 aria-expanded 로 알린다. 이름을 접기·펼치기로 뒤집으면
              "펼치기, 펼쳐짐"처럼 상태와 반대로 들린다 */}
          <button
            type="button"
            className={styles.brandAction}
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
        </span>
      </div>
      <nav id="admin-nav" className={styles.nav} aria-label="관리 화면">
        {groups.map((g) => {
          const holdsActive = g.items.some(isActive);
          // 아이콘 기둥에서는 묶음 이름이 없어 늘 펼친다. 지금 화면이 든 묶음도 늘 펼친다
          const open = collapsed || holdsActive || (picked[g.label] ?? !g.closedByDefault);
          const listId = `admin-nav-${g.label}`;
          return (
            <div key={g.label} className={styles.navGroup}>
              <button
                type="button"
                className={styles.navGroupHead}
                aria-expanded={open}
                aria-controls={listId}
                disabled={holdsActive}
                title={holdsActive ? "지금 화면이 든 묶음은 접지 않습니다" : undefined}
                onClick={() => toggleGroup(g.label, open)}
              >
                <span className={styles.navGroupLabel}>{g.label}</span>
                {!open && (
                  <>
                    <span className={`${styles.navGroupCount} num`}>{g.items.length}</span>
                    {g.items.map((n) => n.badge && <NavBadge key={n.href} kind={n.badge} />)}
                  </>
                )}
                <span className={styles.navGroupChevron} data-open={open || undefined} aria-hidden="true">
                  <AdminIcon name="chevronDown" size={12} />
                </span>
              </button>
              {open && (
                <div id={listId} className={styles.navGroupItems}>
                  {g.items.map((n) => (
                    <NavItemLink key={n.href} n={n} active={isActive(n)} collapsed={collapsed} />
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </nav>
      {foot != null && <div className={styles.navFoot}>{foot}</div>}
      {/* 설정과 평면 전환은 내비 목록이 아니라 바닥의 한 줄이다. 같은 목록에 섞으면
          "이 콘솔의 화면 하나"로 읽혀 두 평면을 가른 의미가 흐려진다 */}
      <div className={styles.dockLinks}>
        {footLinks.map((n) => (
          <Link
            key={n.href}
            href={n.href}
            className={styles.navCross}
            data-active={isActive(n) || undefined}
            aria-current={isActive(n) ? "page" : undefined}
            title={collapsed ? n.label : undefined}
          >
            <span className={styles.navCrossIcon} aria-hidden="true">
              <AdminIcon name={n.icon} />
            </span>
            <span className={styles.navLabel}>{n.label}</span>
          </Link>
        ))}
        <Link href={crossLink.href} className={styles.navCross} title={collapsed ? crossLink.label : undefined}>
          <svg className={styles.navCrossIcon} width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M3 5.5 H12.5 M10 3 L12.5 5.5 L10 8" />
            <path d="M13 10.5 H3.5 M6 8 L3.5 10.5 L6 13" />
          </svg>
          <span className={styles.navLabel}>{crossLink.label}</span>
        </Link>
      </div>
      <SessionCard collapsed={collapsed} />
    </aside>
  );
}
