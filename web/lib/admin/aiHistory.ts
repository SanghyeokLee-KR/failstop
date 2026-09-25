/**
 * AI 모드·정지 이력(관리자 설계 4-10-2). 감사 기록의 ops.ai.* 행을 옮긴다. 두 데이터 원천이 같은 코드를 쓴다. 서버는
 * 자동 켜기(ops.ai.auto, 대상 AUTO:{시간}, 승인 티켓), 즉시 끄기(ops.ai.manual), 정지(ops.ai.stop), 재개(ops.ai.resume),
 * 유효 시간 만료(ops.ai.mode.expire), 시스템 수동 전환(ops.ai.mode.system)을 남기고, 관제 시뮬레이터도 같은 코드로 남긴다.
 *
 * 서버는 사유를 감사 상세(JSON)에 두고 감사 목록(GET /admin/v1/audit)은 그 상세를 내려주지 않는다. 그래서 http의 사유는
 * 비어 있고, sim은 대상 칸에 적은 사유를 읽는다. 전환 전 모드는 감사 목록을 오래된 순서로 따라가며 센다.
 */

import type { AiMode, AiModeRow, AuditEntry, OpsActor } from "./types";

const KIND_OF: Record<string, AiModeRow["kind"]> = {
  "ops.ai.auto": "AUTO_ON",
  "ops.ai.manual": "AUTO_OFF",
  "ops.ai.stop": "STOP",
  "ops.ai.resume": "RESUME",
  "ops.ai.mode.expire": "EXPIRE",
  "ops.ai.mode.system": "SYSTEM",
};

const TO: Record<AiModeRow["kind"], AiMode> = {
  AUTO_ON: "AUTO",
  AUTO_OFF: "MANUAL",
  STOP: "STOPPED",
  RESUME: "MANUAL",
  EXPIRE: "MANUAL",
  SYSTEM: "MANUAL",
};

/** 전환의 이름. 표의 '전환' 칸이 쓴다 */
export const MODE_CHANGE_WORD: Record<AiModeRow["kind"], string> = {
  AUTO_ON: "자동 켜기",
  AUTO_OFF: "자동 끄기",
  STOP: "AI 즉시 정지",
  RESUME: "AI 재개",
  EXPIRE: "유효 시간 만료",
  SYSTEM: "시스템 수동 전환",
};

export const MODE_WORD: Record<AiMode, string> = { MANUAL: "수동", AUTO: "자동", STOPPED: "정지" };

function actorOf(operatorId: string): OpsActor {
  if (operatorId.startsWith("system")) return "SYSTEM";
  if (operatorId === "ai-ops") return "AI";
  return "OPERATOR";
}

/** 대상 칸의 사유. sim은 '… · 사유 {사유}'로, 시스템 전환은 이유 글 자체로 적는다. 서버의 대상(AUTO:2, MANUAL, AI)은 사유가 아니다 */
function reasonOf(kind: AiModeRow["kind"], target: string): string | null {
  const m = / · 사유 (.+)$/.exec(target);
  if (m) return m[1].trim();
  if ((kind === "SYSTEM" || kind === "EXPIRE") && !/^(MANUAL|AUTO|AI)$/.test(target.trim())) return target.trim() || null;
  return null;
}

/**
 * 감사 기록에서 모드·정지 이력을 만든다. 성공한 행만 옮기고 최신이 앞이다. 감사 목록이 최근 몇 쪽뿐이면 그 앞의 모드는
 * 모르므로 첫 행의 전환 전 모드는 수동에서 시작한다고 본다(모드의 기본값)
 */
export function aiModeHistory(audit: AuditEntry[]): AiModeRow[] {
  // 감사 목록은 최신이 앞이다. 같은 밀리초에 남은 행이 있어 뒤집은 뒤 시각으로 세워야 같은 시각끼리 일어난 순서가 된다
  const rows = [...audit]
    .reverse()
    .filter((e) => e.result === "OK" && KIND_OF[e.action] !== undefined)
    .sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  const out: AiModeRow[] = [];
  let mode: AiMode = "MANUAL";
  for (const e of rows) {
    const kind = KIND_OF[e.action];
    const hours = kind === "AUTO_ON" ? Number(/^AUTO:(\d+)/.exec(e.target)?.[1] ?? NaN) : NaN;
    const to = TO[kind];
    out.push({
      auditId: e.id,
      at: e.at,
      kind,
      from: mode,
      to,
      actor: actorOf(e.operatorId),
      actorName: e.operatorName || e.operatorId,
      reason: reasonOf(kind, e.target),
      hours: Number.isFinite(hours) ? hours : null,
      approvalId: e.approvalId,
      action: e.action,
    });
    mode = to;
  }
  return out.reverse();
}
