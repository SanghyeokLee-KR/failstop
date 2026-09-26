"use client";

/**
 * 관리 평면 크롬(§4-1). 좌측 사이드바 + 상단 운영자 바. 공개 HeaderNav 미사용.
 *
 * 세션 게이트는 여기 없다. 게이트가 크롬 안에 있으면 평면마다 게이트가 한 벌씩
 * 생겨 두 곳에서 각자 부트스트랩을 돌린다. 게이트는 /admin 라우트 하나가 소유하고
 * (AdminGate) 이 컴포넌트는 그 안쪽의 모양만 맡는다.
 */

import { useEffect, type CSSProperties, type ReactNode } from "react";
import { usePathname } from "next/navigation";
import { useChat } from "@/lib/admin/chatStore";
import type { AdminNavGroup, AdminNavItem } from "@/lib/admin/nav";
import { usePageTitle } from "@/components/PageTitle";
import AdminSidebar from "./AdminSidebar";
import OperatorBar from "./OperatorBar";
import styles from "./admin.module.css";

export default function AdminShell({
  title,
  groups,
  footLinks,
  crossLink,
  sidebarFoot,
  brandAction,
  barSearch,
  barAi,
  barSignals,
  overlay,
  children,
}: {
  title: string;
  groups: AdminNavGroup[];
  /** 사이드바 바닥 줄의 화면. 운영 관제는 설정을 넘긴다 */
  footLinks?: AdminNavItem[];
  crossLink: { href: string; label: string };
  /** 사이드바 바닥 상태 줄. 운영 관제만 AI 운영 모드를 넘긴다 */
  sidebarFoot?: ReactNode;
  /** 사이드바 명판 줄의 단추. 운영 관제는 AI 상담 단추를 넘긴다 */
  brandAction?: ReactNode;
  /** 운영자 바의 검색, AI 즉시 정지와 상태 신호. 관제 데이터를 읽으므로 운영 관제만 넘긴다 */
  barSearch?: ReactNode;
  barAi?: ReactNode;
  barSignals?: ReactNode;
  /** 본문 위에 뜨는 층(AI 상담 패널) */
  overlay?: ReactNode;
  children: ReactNode;
}) {
  // 관리 화면은 전부 클라이언트 페이지라 metadata로 제목을 못 단다. 내비 이름을 탭 제목으로 쓴다
  const pathname = usePathname();
  const current = [...groups.flatMap((g) => g.items), ...(footLinks ?? [])]
    .find((n) => (n.exact ? pathname === n.href : pathname === n.href || pathname.startsWith(`${n.href}/`)));
  usePageTitle(`${current?.label ?? title} · 관리 콘솔`);
  // AI 상담 패널이 열리면 1600px 이상 창에서 패널 폭만큼 본문을 민다(레이아웃 §7). 상담을 두는 셸(운영 관제)만 민다
  const chatOpen = useChat((s) => s.open) && overlay != null;
  const chatWidth = useChat((s) => s.width);
  // 토스트는 셸 밖(body 아래)에 뜬다. 상담 패널이 열린 동안에는 패널 왼쪽에 서도록 body에 패널 폭을 알린다(§12-12)
  useEffect(() => {
    if (!chatOpen) return;
    const body = document.body;
    body.dataset.adminChat = "";
    body.style.setProperty("--admin-chat-w", `${chatWidth}px`);
    return () => {
      delete body.dataset.adminChat;
      body.style.removeProperty("--admin-chat-w");
    };
  }, [chatOpen, chatWidth]);

  return (
    <div
      className={`${styles.shell} ${styles.typeScale}`}
      data-chat={chatOpen ? "" : undefined}
      style={chatOpen ? ({ "--admin-chat-w": `${chatWidth}px` } as CSSProperties) : undefined}
    >
      {/* 전역 #main은 셸 전체를 감싸는 앵커라 관리 평면에서는 아무것도 건너뛰지 못한다.
          자체 앵커를 두되 전역 링크와 문구를 갈라 두 링크가 구분 낭독되게 한다 */}
      <a href="#admin-main" className="skip-link">
        관리 본문 바로가기
      </a>
      <AdminSidebar title={title} groups={groups} footLinks={footLinks} crossLink={crossLink} brandAction={brandAction} foot={sidebarFoot} />
      <div className={styles.main}>
        <OperatorBar search={barSearch} ai={barAi} signals={barSignals} />
        <main id="admin-main" tabIndex={-1} className={styles.content}>
          {children}
        </main>
      </div>
      {overlay}
    </div>
  );
}
