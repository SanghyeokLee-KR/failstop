/**
 * lib/admin/roles.ts. 역할 기반 권한(RBAC) 판정과 라벨 (§1-2).
 * 최소 권한 원칙. 파괴 액션 승인 티켓은 역할과 독립이며 여기서 강제하지 않는다(승인 원장이 강제).
 */

import type { AdminRole, ApprovalPayload, DestructiveAction } from "./types";

export const ROLE_LABEL: Record<AdminRole, string> = {
  VIEWER: "조회",
  BOT_OPS: "봇 운영",
  CHAOS_OPS: "실험 운영",
  MEMBER_MOD: "회원 운영",
  SUPER: "총괄",
};

export const ROLE_DESC: Record<AdminRole, string> = {
  VIEWER: "모니터링·상태·감사 열람 전용",
  BOT_OPS: "봇 플릿 제어(생성·정지·OPS·파라미터)",
  // 서버 AdminRole이 내려주는 역할이고 봇 제어 승계가 걸려 있다. 장애 주입은 서버 API만 있다
  CHAOS_OPS: "봇 제어 포함, 장애 주입 API 실행(콘솔 화면 없음)",
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

/** 파괴 액션별 요청(소각) 요구 권한. 소각은 요청 운영자가 수행하므로 이 권한은 요청자 기준이다 */
export const ACTION_CAP: Record<DestructiveAction, Capability> = {
  "sim.bots.params": "BOT_OPS",
  "config.market.halt": "SUPER",
  "config.market.resume": "SUPER",
  "config.market.fees": "SUPER",
  "operators.create": "SUPER",
  "operators.status": "SUPER",
  "operators.roles": "SUPER",
  "members.close": "MEMBER_MOD",
  // 관제센터 조치는 용량 조작과 같이 인프라 조치라 SUPER다(관리자 설계 4-4)
  "ops.threshold.set": "SUPER",
  "ops.ai.auto": "SUPER",
};

export const ACTION_LABEL: Record<DestructiveAction, string> = {
  "sim.bots.params": "봇 파라미터 변경",
  "config.market.halt": "마켓 거래 중지",
  "config.market.resume": "마켓 거래 재개",
  "config.market.fees": "수수료 변경",
  "operators.create": "운영자 생성",
  "operators.status": "운영자 상태 변경",
  "operators.roles": "운영자 역할 변경",
  "members.close": "회원 폐쇄",
  "ops.threshold.set": "임계값 변경",
  "ops.ai.auto": "AI 자동 모드 켜기",
};

/** 요청 권한 충족 여부 */
export function canRequest(roles: AdminRole[], action: DestructiveAction): boolean {
  return can(roles, ACTION_CAP[action]);
}

/**
 * 콘솔 UI 표시 키·라벨. 원장·의도 목록의 중복 방지 키(key)와 표시 라벨(label)을 만든다.
 * 백엔드 승인 바인딩 target(node·toxic·symbol 등)과는 별개다(httpAdmin.wireBinding).
 */
export function approvalConsoleTarget(payload: ApprovalPayload): { key: string; label: string } {
  switch (payload.action) {
    case "sim.bots.params":
      return { key: `sim.bots.params:${payload.botId}`, label: `봇 ${payload.botId}` };
    case "config.market.halt":
      return { key: `config.market.halt:${payload.symbol}`, label: payload.symbol };
    case "config.market.resume":
      return { key: `config.market.resume:${payload.symbol}`, label: payload.symbol };
    case "config.market.fees":
      return { key: `config.market.fees:${payload.symbol}`, label: `${payload.symbol} 수수료` };
    case "operators.create":
      return { key: `operators.create:${payload.operatorId}`, label: `신규 ${payload.operatorId}` };
    case "operators.status":
      return { key: `operators.status:${payload.operatorId}`, label: `운영자 ${payload.operatorId}` };
    case "operators.roles":
      return {
        // 같은 운영자의 서로 다른 역할 변경이 하나로 뭉개지지 않게 role·방향까지 키에 넣는다
        key: `operators.roles:${payload.operatorId}:${payload.grant}:${payload.role}`,
        label: `운영자 ${payload.operatorId} · ${ROLE_LABEL[payload.role]} ${payload.grant === "GRANT" ? "부여" : "회수"}`,
      };
    case "members.close":
      return {
        key: `members.close:${payload.externalId}`,
        // 식별자는 UUID라 눈으로 못 읽는다. 운영자가 보는 손잡이는 이메일이다
        label: `회원 ${payload.email} 폐쇄`,
      };
    case "ops.threshold.set":
      return {
        key: `ops.threshold.set:${payload.key}`,
        label: `${payload.label} ${payload.from}${payload.unit} → ${payload.to}${payload.unit}${payload.revertOf ? " (되돌리기)" : ""}`,
      };
    case "ops.ai.auto":
      return { key: "ops.ai.auto", label: `AI 자동 모드 ${payload.hours}시간` };
  }
}
