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

import { useEffect, useLayoutEffect, type ReactNode } from "react";
import { useAdminReady, useAdminSession, useAdminSessionBootstrap } from "@/lib/admin/store";
import AdminLogin from "./AdminLogin";
import styles from "./admin.module.css";

// 서버 렌더에는 문서가 없어 레이아웃 효과가 할 일이 없다. 같은 자리를 useEffect로 받아 경고를 피한다
const useIsoLayoutEffect = typeof window === "undefined" ? useEffect : useLayoutEffect;

/**
 * 관리 기본 테마(§12-1). 저장된 선택도 명시 테마도 없을 때만 다크를 세우고 표시를 남긴다.
 * 첫 로드는 admin/layout.tsx의 인라인 스크립트가 먼저 세우고, 클라이언트 이동으로 들어온
 * 경우는 여기서 세운다. 떠날 때는 자기가 세운 표시만 거둬 거래 화면의 기본(시스템)을 돌려준다.
 * 운영자가 테마를 고르면 ThemeToggle이 표시를 지워 그 선택은 남는다.
 *
 * 레이아웃 효과로 세운다. 클라이언트 이동에서는 셸의 ThemeToggle이 같은 커밋에 마운트되고, 그 일반
 * 효과가 data-theme을 읽어 제 상태를 잡는다. 일반 효과로 세우면 자식 효과가 먼저 돌아 토글이 세우기
 * 전의 시스템 값을 잡고, 화면은 다크인데 토글은 다크로 전환하라고 읽힌다. 레이아웃 효과는 같은 커밋의
 * 일반 효과보다 먼저 돈다.
 */
function useAdminDefaultTheme() {
  useIsoLayoutEffect(() => {
    const d = document.documentElement;
    try {
      if (!localStorage.getItem("theme") && !d.dataset.theme) {
        d.dataset.theme = "dark";
        d.dataset.themeOrigin = "admin";
      }
    } catch {
      // 저장소를 읽을 수 없으면 시스템 설정을 따른다
    }
    return () => {
      if (d.dataset.themeOrigin === "admin") {
        delete d.dataset.theme;
        delete d.dataset.themeOrigin;
      }
    };
  }, []);
}

export default function AdminGate({ children }: { children: ReactNode }) {
  useAdminDefaultTheme();
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
