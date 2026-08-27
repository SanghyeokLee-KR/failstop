"use client";

/**
 * HeaderNav. 전 화면 공통 상단 내비 (§1.1).
 * 로고 + 링크 4개 + 회원 인증 상태 + WS 연결 상태 + 테마 토글.
 * 고정형 정책(§4)에 따라 축소 변형 없음. 전 해상도 동일 구성.
 * 연결 상태는 스토어 connected가 원천이며 DataSource.onConnectionChange로만 갱신된다.
 * 회원 세션은 useMemberSessionBootstrap이 DataSource 구독으로 반영한다(§4-2).
 */

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import BrandMark from "./BrandMark";
import ThemeToggle from "./ThemeToggle";
import { toast } from "./Toast";
import { useExchangeStore, useMemberSession, useMemberSessionBootstrap } from "@/lib/store";
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

/** 회원 드롭다운 항목(§4-2). 로그아웃은 별도 danger 처리 */
const MENU_LINKS = [
  { href: "/mypage", label: "마이페이지" },
  { href: "/assets", label: "자산" },
  { href: "/history", label: "거래내역" },
];

function MemberMenu({ displayName }: { displayName: string }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const initial = displayName.trim().charAt(0).toUpperCase() || "?";

  const menuItems = () =>
    Array.from(menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);

  const closeAndReturn = () => {
    setOpen(false);
    triggerRef.current?.focus();
  };

  // 열릴 때 첫 항목으로 포커스 이동(§4-2 키보드 흐름)
  useEffect(() => {
    if (!open) return;
    menuItems()[0]?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    // 포커스가 메뉴를 벗어난 경우의 Escape 안전망. 트리거로 포커스 복귀
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeAndReturn();
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const onMenuKeyDown = (e: React.KeyboardEvent) => {
    const list = menuItems();
    if (list.length === 0) return;
    const idx = list.indexOf(document.activeElement as HTMLElement);
    if (e.key === "ArrowDown") {
      e.preventDefault();
      list[(idx + 1) % list.length]?.focus();
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      list[(idx - 1 + list.length) % list.length]?.focus();
    } else if (e.key === "Home") {
      e.preventDefault();
      list[0]?.focus();
    } else if (e.key === "End") {
      e.preventDefault();
      list[list.length - 1]?.focus();
    } else if (e.key === "Escape") {
      e.preventDefault();
      closeAndReturn();
    }
  };

  const logout = async () => {
    setOpen(false);
    try {
      await getDataSource().logout();
      toast("로그아웃 완료", "info");
      router.push("/exchange");
    } catch {
      toast("로그아웃 실패. 잠시 후 다시 시도 필요", "error");
    }
  };

  return (
    <div className={styles.member} ref={rootRef}>
      <button
        ref={triggerRef}
        type="button"
        className={styles.memberChip}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setOpen(true);
          }
        }}
      >
        <span className={styles.avatar} aria-hidden="true">
          {initial}
        </span>
        <span className={styles.memberName}>{displayName}</span>
        <span className={styles.caret} aria-hidden="true" data-open={open || undefined}>
          ▾
        </span>
      </button>
      {open && (
        <div
          ref={menuRef}
          className={styles.menu}
          role="menu"
          aria-label="회원 메뉴"
          aria-orientation="vertical"
          onKeyDown={onMenuKeyDown}
        >
          {MENU_LINKS.map((m) => (
            <Link
              key={m.href}
              href={m.href}
              role="menuitem"
              className={styles.menuItem}
              onClick={() => setOpen(false)}
            >
              {m.label}
            </Link>
          ))}
          <div className={styles.menuSep} role="separator" />
          <button
            type="button"
            role="menuitem"
            className={styles.menuLogout}
            onClick={() => void logout()}
          >
            로그아웃
          </button>
        </div>
      )}
    </div>
  );
}

export default function HeaderNav() {
  const pathname = usePathname();
  const connected = useExchangeStore((s) => s.connected);
  const setConnected = useExchangeStore((s) => s.setConnected);
  const session = useMemberSession();

  useEffect(() => getDataSource().onConnectionChange(setConnected), [setConnected]);
  useMemberSessionBootstrap();

  // 관리 평면은 자체 셸(AdminLayout)을 쓴다. 공개 HeaderNav를 렌더하지 않는다(§4-1)
  if (pathname === "/admin" || pathname.startsWith("/admin/")) return null;

  // 온보딩은 진입 게이트다. 내비가 홈·거래소를 열면 게이트를 우회하고, 로그인·가입 링크는
  // 본문 CTA와 중복된다. 브랜드도 히어로 명판이 이미 세운다. 테마 토글만 남긴다(§8 화면1 v1.7)
  if (pathname === "/welcome") {
    return (
      <header className={styles.bare}>
        <ThemeToggle />
      </header>
    );
  }

  const isActive = (link: NavLink) =>
    link.match.some((m) => pathname === m || pathname.startsWith(`${m}/`));

  return (
    <header className={styles.root}>
      <div className={styles.inner}>
        <Link href="/home" className={styles.logo} aria-label="홈으로 이동">
          <span className={styles.logoMark} aria-hidden="true">
            <BrandMark size={20} />
          </span>
          <span className={styles.logoText}>페일스톱</span>
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
          {session ? (
            <MemberMenu displayName={session.user.display_name} />
          ) : (
            <span className={styles.auth}>
              <Link href="/auth/login" className={styles.loginLink}>
                로그인
              </Link>
              <Link href="/auth/signup" className={styles.signupLink}>
                회원 가입
              </Link>
            </span>
          )}
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
