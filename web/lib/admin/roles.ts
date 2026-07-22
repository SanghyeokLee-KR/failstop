/**
 * lib/admin/roles.ts. 역할 기반 권한(RBAC) 판정과 라벨 (§1-2).
 * 최소 권한 원칙. 파괴 액션 2인 승인은 역할과 독립이며 여기서 강제하지 않는다(승인 원장이 강제).
 */

import type { AdminRole, DestructiveAction } from "./types";

export const ROLE_LABEL: Record<AdminRole, string> = {
  VIEWER: "조회",
  BOT_OPS: "봇 운영",
  CHAOS_OPS: "카오스 운영",
  MEMBER_MOD: "회원 운영",
  SUPER: "총괄",
};

export const ROLE_DESC: Record<AdminRole, string> = {
  VIEWER: "모니터링·상태·감사 열람 전용",
  BOT_OPS: "봇 플릿 제어(생성·정지·OPS·파라미터)",
  CHAOS_OPS: "카오스 주입·해제, 봇 제어 포함",
  MEMBER_MOD: "회원·게시판 운영",
  SUPER: "전 권한 + 운영자·역할 관리",
};

/** 조회 이상 요구 등급 어휘 */
export type Capability = "VIEW" | "BOT_OPS" | "CHAOS_OPS" | "MEMBER_MOD" | "SUPER";

/**
 * 역할 집합이 요구 권한을 충족하는지 판정.
 * SUPER는 전 권한을 포함하고, CHAOS_OPS는 봇 제어(BOT_OPS)를 포함한다(§1-2).
 */
export function can(roles: AdminRole[], cap: Capability): boolean {
  if (roles.includes("SUPER")) return true;
  switch (cap) {
    case "VIEW":
      return roles.length > 0;
    case "BOT_OPS":
      return roles.includes("BOT_OPS") || roles.includes("CHAOS_OPS");
    case "CHAOS_OPS":
      return roles.includes("CHAOS_OPS");
    case "MEMBER_MOD":
      return roles.includes("MEMBER_MOD");
    case "SUPER":
      return false; // SUPER는 위에서 이미 통과
  }
}

/** 파괴 액션별 요청 요구 권한 */
export const ACTION_CAP: Record<DestructiveAction, Capability> = {
  "chaos.inject": "CHAOS_OPS",
  "chaos.clear": "CHAOS_OPS",
  "sim.bots.params": "BOT_OPS",
  "config.market.halt": "SUPER",
  "config.market.resume": "SUPER",
  "operators.roles": "SUPER",
};

export const ACTION_LABEL: Record<DestructiveAction, string> = {
  "chaos.inject": "카오스 주입",
  "chaos.clear": "카오스 해제",
  "sim.bots.params": "봇 파라미터 변경",
  "config.market.halt": "마켓 거래 중지",
  "config.market.resume": "마켓 거래 재개",
  "operators.roles": "운영자 역할 변경",
};

/** 요청 권한 충족 여부 */
export function canRequest(roles: AdminRole[], action: DestructiveAction): boolean {
  return can(roles, ACTION_CAP[action]);
}
