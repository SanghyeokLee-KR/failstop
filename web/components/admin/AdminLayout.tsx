"use client";

/**
 * 관리 평면 셸(§4-1). 좌측 사이드바 + 상단 운영자 바. 공개 HeaderNav 미사용.
 * 세션이 없으면 접속 화면을 렌더한다. 세션 유래·자가신고 금지 계약은 AdminApi가 보증한다.
 */

import type { ReactNode } from "react";
import { useAdminReady, useAdminSession, useAdminSessionBootstrap } from "@/lib/admin/store";
import AdminSidebar from "./AdminSidebar";
import OperatorBar from "./OperatorBar";
import AdminLogin from "./AdminLogin";
import styles from "./admin.module.css";

export default function AdminLayout({ children }: { children: ReactNode }) {
  useAdminSessionBootstrap();
  const ready = useAdminReady();
  const session = useAdminSession();

  if (!ready) {
    return <main className={styles.booting} aria-busy="true" aria-label="세션 확인 중" />;
  }
  if (!session) {
    return <AdminLogin />;
  }

  return (
    <div className={styles.shell}>
      {/* 전역 #main은 셸 전체를 감싸는 앵커라 관리 평면에서는 아무것도 건너뛰지 못한다.
          자체 앵커를 두되 전역 링크와 문구를 갈라 두 링크가 구분 낭독되게 한다 */}
      <a href="#admin-main" className="skip-link">
        관리 본문 바로가기
      </a>
      <AdminSidebar />
      <div className={styles.main}>
        <OperatorBar />
        <main id="admin-main" tabIndex={-1} className={styles.content}>
          {children}
        </main>
      </div>
    </div>
  );
}
