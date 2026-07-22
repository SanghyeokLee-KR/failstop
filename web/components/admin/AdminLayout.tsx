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
      <AdminSidebar />
      <div className={styles.main}>
        <OperatorBar />
        <div className={styles.content}>{children}</div>
      </div>
    </div>
  );
}
