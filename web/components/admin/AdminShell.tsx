"use client";

/**
 * 관리 평면 크롬(§4-1). 좌측 사이드바 + 상단 운영자 바. 공개 HeaderNav 미사용.
 *
 * 세션 게이트는 여기 없다. 게이트가 크롬 안에 있으면 평면마다 게이트가 한 벌씩
 * 생겨 두 곳에서 각자 부트스트랩을 돌린다. 게이트는 /admin 라우트 하나가 소유하고
 * (AdminGate) 이 컴포넌트는 그 안쪽의 모양만 맡는다.
 */

import type { ReactNode } from "react";
import type { AdminNavGroup } from "@/lib/admin/nav";
import AdminSidebar from "./AdminSidebar";
import OperatorBar from "./OperatorBar";
import styles from "./admin.module.css";

export default function AdminShell({
  title,
  groups,
  crossLink,
  children,
}: {
  title: string;
  groups: AdminNavGroup[];
  crossLink: { href: string; label: string };
  children: ReactNode;
}) {
  return (
    <div className={styles.shell}>
      {/* 전역 #main은 셸 전체를 감싸는 앵커라 관리 평면에서는 아무것도 건너뛰지 못한다.
          자체 앵커를 두되 전역 링크와 문구를 갈라 두 링크가 구분 낭독되게 한다 */}
      <a href="#admin-main" className="skip-link">
        관리 본문 바로가기
      </a>
      <AdminSidebar title={title} groups={groups} crossLink={crossLink} />
      <div className={styles.main}>
        <OperatorBar />
        <main id="admin-main" tabIndex={-1} className={styles.content}>
          {children}
        </main>
      </div>
    </div>
  );
}
