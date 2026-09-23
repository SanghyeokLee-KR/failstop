"use client";

/**
 * 관리 평면 세션 게이트. 운영 관제와 백오피스가 이 하나를 공유한다.
 *
 * 크롬(사이드바·운영자 바)과 갈라 둔 이유. 두 평면이 각자 게이트를 들면 부트스트랩이
 * 두 벌 돌고, 평면을 건너갈 때마다 세션 확인 화면이 한 번씩 더 깜빡인다. 세션은 평면과
 * 무관한 사실이므로 /admin 라우트 하나가 소유한다.
 *
 * 세션 유래·자가신고 금지 계약은 AdminApi가 보증한다.
 */

import type { ReactNode } from "react";
import { useAdminReady, useAdminSession, useAdminSessionBootstrap } from "@/lib/admin/store";
import AdminLogin from "./AdminLogin";
import styles from "./admin.module.css";

export default function AdminGate({ children }: { children: ReactNode }) {
  useAdminSessionBootstrap();
  const ready = useAdminReady();
  const session = useAdminSession();

  if (!ready) {
    return <main className={styles.booting} aria-busy="true" aria-label="세션 확인 중" />;
  }
  if (!session) {
    return <AdminLogin />;
  }
  return <>{children}</>;
}
