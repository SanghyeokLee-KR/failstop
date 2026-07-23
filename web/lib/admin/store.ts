"use client";

/**
 * lib/admin/store.ts. 관리 평면 전역 상태(zustand). 고객 스토어(lib/store.ts)와 분리.
 * 세션은 AdminApi.onSessionChange가 유일한 갱신 경로다(자가신고 금지 계약과 정합).
 * 화면은 셀렉터로 세션·역할만 구독한다.
 */

import { useEffect } from "react";
import { create } from "zustand";
import { getAdminApi } from "./api";
import type { AdminSession, AdminRole, ApprovalPayload, DestructiveAction, IssuedApproval } from "./types";
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

/* ===== 발급형 승인 2인 UX 보관소 =====
 * 발급형 계약(§3-6)에는 서버측 "요청" 상태가 없다. 요청 운영자의 의도는 이 브라우저 로컬
 * 보관소에 둔다(intents). 발급 응답의 단회 토큰은 원장 프로젝션에 없으므로(GET /approvals 제외)
 * 발급자->요청자 전달을 이 보관소가 모사한다(tokens). 데모 단일 브라우저 운영자 전환 흐름 전제.
 * 서버가 상태를 소유하지 않는 UI 조정용이라 고객 스토어·세션과 분리한다.
 */

/** 발급 전 로컬 요청 의도(서버 상태 아님) */
export interface ApprovalIntent {
  key: string; // approvalConsoleTarget(payload).key. 중복 제안 방지
  requesterId: string;
  requesterName: string;
  action: DestructiveAction;
  payload: ApprovalPayload;
  targetLabel: string;
  proposedAt: string;
}

interface VaultState {
  intents: ApprovalIntent[];
  /** 발급된 원장 id -> 단회 토큰 동반 승인. 소각 실행에 필요 */
  tokens: Record<string, IssuedApproval>;
  propose(intent: ApprovalIntent): void;
  cancelIntent(key: string): void;
  stashToken(issued: IssuedApproval): void;
  dropToken(id: string): void;
}

export const useApprovalVault = create<VaultState>((set) => ({
  intents: [],
  tokens: {},
  propose(intent) {
    set((s) => ({ intents: [intent, ...s.intents.filter((x) => x.key !== intent.key)] }));
  },
  cancelIntent(key) {
    set((s) => ({ intents: s.intents.filter((x) => x.key !== key) }));
  },
  stashToken(issued) {
    set((s) => ({ tokens: { ...s.tokens, [issued.id]: issued } }));
  },
  dropToken(id) {
    set((s) => {
      const next = { ...s.tokens };
      delete next[id];
      return { tokens: next };
    });
  },
}));
