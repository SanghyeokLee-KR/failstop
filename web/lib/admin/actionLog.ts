/**
 * 조치 기록(관리자 설계 4-10-4). 감사 기록을 사람·AI·시스템의 조치 한 줄로 옮기고, 원문 코드마다 종류와 한국어 주해를 단다.
 * 서버와 관제 시뮬레이터는 같은 일을 다른 코드로 남기기도 한다(msp.alert.ack와 ops.alert.ack). 종류는 같게 묶고 원문 코드는
 * 그대로 보인다.
 *
 * 서버 감사에는 관리 요청마다 남는 줄('GET /admin/v1/msp/overview')이 함께 있다. 읽기(GET)는 조치가 아니라 빼고, 같은 요청의
 * 도메인 코드 줄이 있으면 요청 줄을 뺀다. 도메인 줄이 없는 요청 줄(필터에서 거부된 요청)은 남긴다.
 */

import { hms, kstParts } from "./timeText";
import type { ActionCategory, ActionFeed, ActionRecord, AuditEntry, OpsActor } from "./types";

export const CATEGORY_WORD: Record<ActionCategory, string> = {
  ALERT: "경보",
  AI_ACTION: "AI 조치",
  AI_MODE: "AI 모드",
  THRESHOLD: "임계값",
  AI_LIMIT: "AI 한도",
  CAPACITY: "앱 계층",
  BOT: "봇",
  BACKUP: "백업",
  WINDOW: "계획 작업 창",
  CHANGE: "변경 요청",
  INCIDENT: "사건",
  REPORT: "보고",
  SESSION: "세션·승인",
  OTHER: "기타",
};

/** 거르기 줄의 종류 순서 */
export const CATEGORY_ORDER: ActionCategory[] = [
  "ALERT", "AI_ACTION", "AI_MODE", "THRESHOLD", "AI_LIMIT", "CAPACITY", "BOT", "BACKUP", "WINDOW", "CHANGE", "INCIDENT", "REPORT", "SESSION", "OTHER",
];

interface CodeRule {
  test: RegExp;
  category: ActionCategory;
  gloss: string;
}

/** 원문 코드의 종류와 주해. 위에서부터 처음 맞는 규칙을 쓴다 */
const RULES: CodeRule[] = [
  { test: /^(ops|msp)\.alert\.ack$/, category: "ALERT", gloss: "경보 확인" },
  { test: /^(ops|msp)\.alert\.silence$/, category: "ALERT", gloss: "경보 보류" },
  { test: /^msp\.silence\.expire$/, category: "ALERT", gloss: "보류 만료" },
  { test: /^DELETE \/admin\/v1\/msp\/silences\//, category: "ALERT", gloss: "보류 해제" },
  { test: /^ai\.approve:|^ops\.ai\.action\.approve$/, category: "AI_ACTION", gloss: "AI 조치 승인·실행" },
  { test: /^ai\.auto:|^ops\.ai\.action\.execute$/, category: "AI_ACTION", gloss: "AI 자동 실행" },
  { test: /^ai\.auto_revert:|^ops\.ai\.action\.auto_revert$/, category: "AI_ACTION", gloss: "AI 자동 되돌림" },
  { test: /^ai\.revert:|^ops\.ai\.action\.revert$/, category: "AI_ACTION", gloss: "AI 조치 되돌리기" },
  { test: /^ai\.reject:|^ops\.ai\.action\.reject$/, category: "AI_ACTION", gloss: "AI 조치 거절" },
  { test: /^ai\.defer:|^ops\.ai\.action\.defer$/, category: "AI_ACTION", gloss: "AI 조치 나중에" },
  { test: /^ops\.ai\.action\.deny$/, category: "AI_ACTION", gloss: "정책 엔진 거부" },
  { test: /^ops\.ai\.auto$|^execute:ops\.ai\.auto$/, category: "AI_MODE", gloss: "자동 모드 켜기" },
  { test: /^ops\.ai\.manual$/, category: "AI_MODE", gloss: "자동 모드 끄기" },
  { test: /^ops\.ai\.stop$/, category: "AI_MODE", gloss: "AI 즉시 정지" },
  { test: /^ops\.ai\.resume$/, category: "AI_MODE", gloss: "AI 재개" },
  { test: /^ops\.ai\.mode\.expire$/, category: "AI_MODE", gloss: "자동 모드 유효 시간 만료" },
  { test: /^ops\.ai\.mode\.system$/, category: "AI_MODE", gloss: "시스템 수동 전환" },
  { test: /^ops\.threshold\.revert$/, category: "THRESHOLD", gloss: "임계값 되돌리기" },
  { test: /^(execute:)?ops\.threshold\.set$/, category: "THRESHOLD", gloss: "임계값 변경" },
  { test: /^(execute:)?ops\.ai\.limits$/, category: "AI_LIMIT", gloss: "AI 한도 변경" },
  { test: /^capacity\.scale_out$|^POST \/admin\/v1\/capacity\/scale-out$/, category: "CAPACITY", gloss: "app-3 켜기" },
  { test: /^capacity\.scale_in$|^POST \/admin\/v1\/capacity\/scale-in$/, category: "CAPACITY", gloss: "app-3 끄기" },
  { test: /^sim\.rate$/, category: "BOT", gloss: "봇 발행률 변경" },
  { test: /^sim\.start$/, category: "BOT", gloss: "봇 시작" },
  { test: /^sim\.stop$/, category: "BOT", gloss: "봇 정지" },
  { test: /^sim\.pause$/, category: "BOT", gloss: "봇 일시정지" },
  { test: /^sim\.(bots\.create|bots\.delete|scale)$/, category: "BOT", gloss: "봇 대수 변경" },
  { test: /^(execute:)?sim\.bots\.params$/, category: "BOT", gloss: "봇 파라미터 변경" },
  { test: /^sim\.(bias|scenario|effects)$/, category: "BOT", gloss: "봇 국면·방향 설정" },
  { test: /^ops\.backup\.run$|^msp\.ops\.backup$/, category: "BACKUP", gloss: "백업 즉시 실행" },
  { test: /^msp\.window\.create$/, category: "WINDOW", gloss: "계획 작업 창 등록" },
  { test: /^msp\.window\.end$/, category: "WINDOW", gloss: "계획 작업 창 조기 종료" },
  { test: /^msp\.change\.create$/, category: "CHANGE", gloss: "변경 요청 등록" },
  { test: /^(execute:)?ops\.change\.approve$/, category: "CHANGE", gloss: "변경 요청 승인" },
  { test: /^msp\.change\.start$/, category: "CHANGE", gloss: "변경 작업 진행" },
  { test: /^msp\.change\.finish$/, category: "CHANGE", gloss: "변경 작업 종료" },
  { test: /^msp\.change\.cancel$/, category: "CHANGE", gloss: "변경 요청 취소" },
  { test: /^msp\.incident\.assign$/, category: "INCIDENT", gloss: "사건 담당 지정" },
  { test: /^msp\.incident\.postmortem$/, category: "INCIDENT", gloss: "사후 분석 저장" },
  { test: /^ops\.report\.teams_test$/, category: "REPORT", gloss: "Teams 연결 시험" },
  { test: /^session\.establish$|^POST \/admin\/v1\/session$/, category: "SESSION", gloss: "관리 세션 발급" },
  { test: /^approval\.issue:|^POST \/admin\/v1\/approvals$/, category: "SESSION", gloss: "승인 티켓 발급" },
  { test: /^approval\.reject(\.denied)?(:|$)|^approvals\.reject$/, category: "SESSION", gloss: "승인 티켓 거부" },
  { test: /^execute\.denied$/, category: "SESSION", gloss: "승인 티켓 사용 거부" },
  { test: /^ops\.scenario\.(start|reset)$/, category: "OTHER", gloss: "관제 시뮬레이션 조작" },
  { test: /^(execute:)?(config|members|operators|community)\./, category: "OTHER", gloss: "백오피스 조치" },
];

/** 조치 기록에 넣지 않는 원문 코드. 조회와 상담은 무엇을 바꾸지 않는다 */
const NOT_ACTION = /^GET |^ops\.ai\.chat$/;

export function classify(action: string): { category: ActionCategory; gloss: string } {
  const hit = RULES.find((r) => r.test.test(action));
  if (hit) return { category: hit.category, gloss: hit.gloss };
  const m = /^(POST|PUT|PATCH|DELETE) (\/admin\/v1\/\S+)$/.exec(action);
  if (m) return { category: "OTHER", gloss: `관리 요청 ${m[1]}` };
  return { category: "OTHER", gloss: "기타 조치" };
}

export function isAction(code: string): boolean {
  return !NOT_ACTION.test(code);
}

export function actorOf(operatorId: string): OpsActor {
  if (operatorId.startsWith("system")) return "SYSTEM";
  if (operatorId === "ai-ops") return "AI";
  return "OPERATOR";
}

export const ACTOR_WORD: Record<OpsActor, string> = { OPERATOR: "운영자", AI: "AI", SYSTEM: "시스템" };

/** 감사 한 줄을 조치 기록으로 옮긴다. 이음(사건, 판단, 되돌림)은 부르는 쪽이 채운다 */
export function recordOf(e: AuditEntry): ActionRecord {
  const { category } = classify(e.action);
  return {
    id: e.id,
    at: e.at,
    actor: actorOf(e.operatorId),
    actorId: e.operatorId,
    actorName: e.operatorName || e.operatorId,
    category,
    action: e.action,
    target: e.target,
    title: null,
    result: e.result,
    approvalId: e.approvalId,
    incidentId: null,
    judgmentId: null,
    revertedBy: null,
    revertOf: null,
    requestId: e.requestId || null,
    raw: { ...e },
  };
}

/** 관리 필터가 요청마다 남기는 줄. 원문 코드가 'POST /admin/…'이고 대상이 요청 주소다 */
function isRequestRow(e: AuditEntry): boolean {
  return /^(POST|PUT|PATCH|DELETE) \//.test(e.action) && e.target.startsWith("/admin/");
}

/**
 * 서버 감사 목록을 조치 기록으로 옮긴다. 읽기와 상담을 빼고, 같은 요청 id에 다른 줄(컨트롤러가 남긴 도메인 줄)이 있으면 필터의
 * 요청 줄을 뺀다. 최신이 앞인 순서를 지킨다
 */
export function serverRecords(audit: AuditEntry[]): ActionRecord[] {
  const perRequest = new Map<string, number>();
  for (const e of audit) if (e.requestId) perRequest.set(e.requestId, (perRequest.get(e.requestId) ?? 0) + 1);
  return audit
    .filter((e) => isAction(e.action))
    .filter((e) => !(isRequestRow(e) && e.requestId && (perRequest.get(e.requestId) ?? 0) > 1))
    .map(recordOf);
}

/** 되돌림을 잇는다. revertOfRef가 가리키는 기록(같은 참조의 가장 이른 실행)을 찾아 두 줄을 서로 가리키게 한다 */
export function linkReverts(rows: ActionRecord[], refs: Map<string, { ref: string | null; revertOfRef: string | null }>): void {
  const byRef = new Map<string, ActionRecord>();
  // 최신이 앞이라 뒤에서부터 보면 같은 참조의 가장 이른 줄이 남는다
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    const r = rows[i];
    const ref = refs.get(r.id)?.ref;
    if (ref && !byRef.has(ref)) byRef.set(ref, r);
  }
  for (const r of rows) {
    const target = refs.get(r.id)?.revertOfRef;
    if (!target) continue;
    const orig = byRef.get(target);
    if (!orig || orig === r) continue;
    r.revertOf = orig.id;
    orig.revertedBy = r.id;
    if (orig.result === "OK") orig.result = "REVERTED";
  }
}

/** 지난 24시간에 실행한 AI 조치 수와 되돌린 수. AI 판단 화면의 평상 한 줄과 같은 규칙이다(승인·자동 실행과 되돌림) */
export function aiRecentOf(rows: ActionRecord[], now: number): { runs: number; reverts: number } {
  const out = { runs: 0, reverts: 0 };
  for (const r of rows) {
    if (r.result === "DENIED" || r.result === "ERROR" || now - Date.parse(r.at) > 86_400_000) continue;
    const m = /^ai\.(approve|auto|auto_revert|revert):|^ops\.ai\.action\.(approve|execute|auto_revert|revert)$/.exec(r.action);
    if (!m) continue;
    const verb = m[1] ?? m[2];
    if (verb === "approve" || verb === "auto" || verb === "execute") out.runs += 1;
    else out.reverts += 1;
  }
  return out;
}

/* ===== 거르기 ===== */

export type ActorFilter = "ALL" | OpsActor;
export type ResultFilter = "ALL" | "OK" | "DENIED" | "REVERTED";
export type PeriodFilter = "today" | "week" | "all";

export interface ActionFilter {
  actor: ActorFilter;
  category: ActionCategory | "ALL";
  result: ResultFilter;
  period: PeriodFilter;
  query: string;
}

export const DEFAULT_FILTER: ActionFilter = { actor: "ALL", category: "ALL", result: "ALL", period: "week", query: "" };

export function applyFilter(rows: ActionRecord[], f: ActionFilter, now: number, dayOf: (ms: number) => string): ActionRecord[] {
  const today = dayOf(now);
  const q = f.query.trim().toLowerCase();
  return rows.filter((r) => {
    if (f.actor !== "ALL" && r.actor !== f.actor) return false;
    if (f.category !== "ALL" && r.category !== f.category) return false;
    if (f.result === "OK" && r.result !== "OK") return false;
    if (f.result === "DENIED" && r.result !== "DENIED" && r.result !== "ERROR") return false;
    if (f.result === "REVERTED" && r.result !== "REVERTED") return false;
    const t = Date.parse(r.at);
    if (f.period === "today" && dayOf(t) !== today) return false;
    if (f.period === "week" && now - t > 7 * 86_400_000) return false;
    if (q) {
      const hay = `${r.action} ${classify(r.action).gloss} ${r.target} ${r.title ?? ""} ${r.actorName} ${r.actorId} ${r.approvalId ?? ""} ${r.incidentId ?? ""}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });
}

/* ===== CSV ===== */

export const RESULT_WORD: Record<ActionRecord["result"], string> = { OK: "성공", DENIED: "거부", ERROR: "오류", REVERTED: "되돌림" };

function cell(v: string): string {
  return /[",\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

/** KST 'YYYY-MM-DD HH:MM:SS'. 밖으로 나가는 파일이라 날짜까지 적는다 */
function kstStamp(iso: string): string {
  const d = new Date(Date.parse(iso) + 9 * 3_600_000);
  return `${d.toISOString().slice(0, 10)} ${hms(iso)}`;
}

/** 보이는 열 그대로의 CSV. 시각은 KST이고 머리 줄에 그렇게 적는다. 엑셀이 한글을 읽게 BOM을 붙인다 */
export function actionsCsv(rows: ActionRecord[]): string {
  const head = ["시각(KST)", "행위자 구분", "행위자", "인증 주체", "종류", "주해", "원문 코드", "대상", "결과", "승인 티켓", "사건"];
  const lines = rows.map((r) => {
    const c = classify(r.action);
    return [
      kstStamp(r.at), ACTOR_WORD[r.actor], r.actorName, r.actorId, CATEGORY_WORD[r.category], c.gloss, r.action,
      r.title ?? r.target, RESULT_WORD[r.result], r.approvalId ?? "", r.incidentId ?? "",
    ].map((v) => cell(String(v))).join(",");
  });
  return `﻿${[head.join(","), ...lines].join("\r\n")}\r\n`;
}

/** 파일 이름. 원천(sim·live)과 KST 시각을 넣는다 */
export function csvFileName(origin: ActionFeed["origin"], now: number): string {
  const k = kstParts(now);
  const d = new Date(now + 9 * 3_600_000).toISOString().slice(0, 10).replace(/-/g, "");
  const t = `${String(k.h).padStart(2, "0")}${String(k.m).padStart(2, "0")}${String(k.s).padStart(2, "0")}`;
  return `ops-actions_${origin === "SIM" ? "sim" : "live"}_${d}-${t}KST.csv`;
}
