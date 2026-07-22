"use client";

/**
 * lib/admin/store.ts. 관리 평면 전역 상태(zustand). 고객 스토어(lib/store.ts)와 분리.
 * 세션은 AdminApi.onSessionChange가 유일한 갱신 경로다(자가신고 금지 계약과 정합).
 * 화면은 셀렉터로 세션·역할만 구독한다.
 */

import { useEffect } from "react";
import { create } from "zustand";
import { getAdminApi } from "./api";
import type { AdminSession, AdminRole } from "./types";
import { can, type Capability } from "./roles";

interface AdminState {
  session: AdminSession | null;
  ready: boolean;
  setSession(s: AdminSession | null): void;
}

export const useAdminStore = create<AdminState>((set) => ({
  session: null,
  ready: false,
  setSession(s) {
    set({ session: s, ready: true });
  },
}));

/** 레이아웃 1곳에서 호출. onSessionChange 구독을 세션 상태에 연결한다 */
export function useAdminSessionBootstrap(): void {
  const setSession = useAdminStore((s) => s.setSession);
  useEffect(() => getAdminApi().onSessionChange(setSession), [setSession]);
}

export function useAdminSession(): AdminSession | null {
  return useAdminStore((s) => s.session);
}

export function useAdminReady(): boolean {
  return useAdminStore((s) => s.ready);
}

export function useAdminRoles(): AdminRole[] {
  return useAdminStore((s) => s.session?.roles ?? []);
}

/** 요구 권한 충족 여부(셀렉터). 세션 없으면 false */
export function useCan(cap: Capability): boolean {
  return useAdminStore((s) => (s.session ? can(s.session.roles, cap) : false));
}
