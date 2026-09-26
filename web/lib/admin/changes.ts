/**
 * 변경·작업(관리자 설계 4-10-4). 계획 작업 창과 변경 요청의 규칙과 판독이다. 관제 시뮬레이터(창과 요청의 전이, AI 보류의
 * 창 판정), 변경·작업 화면(달력, 단계 막대, 변경 기록), 관제센터 모드 줄과 사이드바 배지가 이 한 곳을 읽는다.
 *
 * 창의 규칙은 서버 계약(API 설계 §6-F 계획 작업 창)을 따른다. 끝은 시작 뒤 24시간 이내, 대상은 경보명과 라벨이 모두 맞아야
 * 하고 둘 가운데 하나는 있어야 하며, 라벨 키는 node·role·component·mountpoint뿐이다. 사유는 1~200자다. AI의 경보 보류는
 * 이 창과 겹치는 주의(warning) 경보뿐이고, 창 안이어도 복제·DB·백업·디스크 계열과 관측 경로 경보는 보류하지 않는다(S5~S7).
 */

import { mdhm, remainWord, mmss } from "./timeText";
import type { StageMark } from "./opsDetail";
import type {
  ActionRecord,
  ChangeInput,
  ChangeRequest,
  ChangeRisk,
  ChangeState,
  MaintenanceWindow,
  OpsAlert,
  ThresholdKey,
  WindowInput,
  WindowLabelKey,
} from "./types";

export const WINDOW_MAX_MS = 24 * 3_600_000;
export const WINDOW_REASON_MAX = 200;
export const LABEL_KEYS: readonly WindowLabelKey[] = ["node", "role", "component", "mountpoint"];

export const CHANGE_STATE_WORD: Record<ChangeState, string> = {
  REQUESTED: "승인 대기",
  APPROVED: "예정",
  IN_PROGRESS: "진행 중",
  DONE: "완료",
  ROLLED_BACK: "롤백",
  CANCELED: "취소",
};

export const RISK_WORD: Record<ChangeRisk, string> = { LOW: "낮음", MEDIUM: "중간", HIGH: "높음" };

/** 규칙 상자의 글(ADR 0035, API 설계 §6-F 정책 S5~S8). 두 원천에서 같은 규칙이다 */
export const WINDOW_RULES: string[] = [
  "계획 작업 창 안에서만 AI가 주의(warning) 경보를 30분까지 보류할 수 있습니다. 보류의 끝은 창의 끝을 넘지 않습니다.",
  "복제·DB·백업·디스크 계열과 관측 경로 경보, 형식 밖 값이 든 경보는 창 안에서도 AI가 보류하지 않습니다.",
  "사람의 보류는 창과 무관합니다. 창을 일찍 끝내는 것은 AI 권한을 줄이는 조작이라 누구나 할 수 있습니다.",
];

/** 입력의 문제. 없으면 null이다. 서버 계약의 창 규칙과 같다 */
export function windowInputError(input: WindowInput, now: number): string | null {
  const start = input.startsAt === null ? now : Date.parse(input.startsAt);
  const end = Date.parse(input.endsAt);
  if (!Number.isFinite(start) || !Number.isFinite(end)) return "시작과 끝 시각을 읽지 못했습니다";
  if (end <= now) return "끝 시각이 이미 지났습니다";
  if (end <= start) return "끝은 시작보다 뒤여야 합니다";
  if (end - start > WINDOW_MAX_MS) return "창은 24시간을 넘지 못합니다";
  const labels = Object.entries(input.labels).filter(([, v]) => (v ?? "").trim() !== "");
  if (labels.some(([k]) => !(LABEL_KEYS as readonly string[]).includes(k))) return "라벨 키는 node·role·component·mountpoint뿐입니다";
  if ((input.alertname ?? "").trim() === "" && labels.length === 0) return "경보명과 라벨 가운데 하나는 있어야 합니다";
  const reason = input.reason.trim();
  if (reason.length === 0) return "사유를 적어야 합니다";
  if (reason.length > WINDOW_REASON_MAX) return `사유는 ${WINDOW_REASON_MAX}자 이내입니다`;
  return null;
}

/** 변경 요청 입력의 문제. 창 규칙과 제목·대상·계획·되돌리기 계획을 본다 */
export function changeInputError(input: ChangeInput, now: number): string | null {
  if (input.title.trim().length < 2) return "제목을 두 글자 이상 적어야 합니다";
  if (input.target.trim().length === 0) return "대상을 적어야 합니다";
  if (input.plan.trim().length === 0) return "작업 계획을 적어야 합니다";
  if (input.rollback.trim().length === 0) return "되돌리기 계획을 적어야 합니다";
  return windowInputError({ startsAt: input.startsAt, endsAt: input.endsAt, alertname: input.alertname, labels: input.labels, reason: input.title }, now);
}

/** 빈 라벨 값을 걷은 라벨 */
export function cleanLabels(labels: Partial<Record<WindowLabelKey, string>>): Partial<Record<WindowLabelKey, string>> {
  const out: Partial<Record<WindowLabelKey, string>> = {};
  for (const k of LABEL_KEYS) {
    const v = labels[k]?.trim();
    if (v) out[k] = v;
  }
  return out;
}

/** 창이 지금 열려 있는가. 조기 종료나 끝 시각이 지나면 닫힌다 */
export function windowOpen(w: MaintenanceWindow, now: number): boolean {
  return w.endedAt === null && Date.parse(w.startsAt) <= now && now < Date.parse(w.endsAt);
}

/** 지금 열린 창. 끝이 먼저 오는 순서다 */
export function openWindows(windows: MaintenanceWindow[], now: number): MaintenanceWindow[] {
  return windows.filter((w) => windowOpen(w, now)).sort((a, b) => Date.parse(a.endsAt) - Date.parse(b.endsAt));
}

export type WindowPhase = "SCHEDULED" | "OPEN" | "ENDED";

export function windowPhase(w: MaintenanceWindow, now: number): WindowPhase {
  if (w.endedAt !== null || Date.parse(w.endsAt) <= now) return "ENDED";
  return Date.parse(w.startsAt) > now ? "SCHEDULED" : "OPEN";
}

/** 창이 끝난 시각. 조기 종료면 그 시각, 아니면 끝 시각이다 */
export function windowClosedAt(w: MaintenanceWindow): string {
  return w.endedAt ?? w.endsAt;
}

/**
 * 창의 남은 시간. 1시간 이상은 시와 분, 그 아래는 mm:ss 카운트다운이다(디자인 시스템 §12-12 기간). 관제센터 모드 줄과 변경·작업
 * 화면이 같은 꼴로 적는다
 */
export function windowRemainWord(ms: number): string {
  return ms >= 3_600_000 ? remainWord(ms) : mmss(ms);
}

/**
 * 계획의 길이. 계획 시각은 분 단위라 초를 적지 않는다('1시간', '1시간 5분', '29분'). 1분이 안 되면 '1분 미만'이다. 진행 중
 * 남은 시간(mm:ss)과 사건의 걸린 시간(durationWord)은 초까지 적는 다른 글이다
 */
export function planDurationWord(ms: number): string {
  const m = Math.round(Math.max(0, ms) / 60_000);
  if (m < 1) return "1분 미만";
  if (m < 60) return `${m}분`;
  return m % 60 === 0 ? `${Math.floor(m / 60)}시간` : `${Math.floor(m / 60)}시간 ${m % 60}분`;
}

/** 계획 시각의 글('9.26 03:00 → 04:00 · 1시간'). 날이 바뀌면 끝에도 날짜를 붙인다 */
export function planSpanWord(start: string, end: string): string {
  const a = mdhm(start);
  const b = mdhm(end);
  const [aDay] = a.split(" ");
  const [bDay, bTime] = b.split(" ");
  return `${a} → ${aDay === bDay ? bTime : b} · ${planDurationWord(Date.parse(end) - Date.parse(start))}`;
}

/** 창 대상의 글('앱 계층 CPU 높음 · role=app') */
export function windowTargetWord(t: { alertname: string | null; labels: Partial<Record<WindowLabelKey, string>> }): string {
  const parts: string[] = [];
  if (t.alertname) parts.push(t.alertname);
  for (const k of LABEL_KEYS) if (t.labels[k]) parts.push(`${k}=${t.labels[k]}`);
  return parts.length === 0 ? "대상 없음" : parts.join(" · ");
}

/**
 * 입력칸의 KST 날짜·시각. 콘솔의 시각은 브라우저 시간대와 무관하게 KST라(디자인 시스템 §12-12) datetime-local을 쓰지 않고
 * 날짜와 시각 칸을 KST로 읽고 쓴다
 */
export function kstFields(ms: number): { date: string; time: string } {
  const iso = new Date(ms + 9 * 3_600_000).toISOString();
  return { date: iso.slice(0, 10), time: iso.slice(11, 16) };
}

/** KST 날짜와 시각 칸을 시각 문자열로. 읽지 못하면 null이다 */
export function kstIso(date: string, time: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) return null;
  const ms = Date.parse(`${date}T${time}:00+09:00`);
  return Number.isFinite(ms) ? new Date(ms).toISOString() : null;
}

/** 계획 시각의 기본값. 지금에서 10분 단위로 올린 뒤 한 시간 뒤에 시작해 한 시간 동안이다 */
export function defaultSpan(now: number): { start: number; end: number } {
  const step = 10 * 60_000;
  const start = Math.ceil(now / step) * step + 3_600_000;
  return { start, end: start + 3_600_000 };
}

/* ===== sim 경보의 라벨과 AI 보류(S5~S7) ===== */

const HOST_ROLE: Record<string, string> = {
  "app-1": "app",
  "app-2": "app",
  "app-3": "app",
  "db-primary": "db",
  "db-standby": "db",
  "bot-1": "bot",
};

/**
 * sim 경보의 라벨. 서버 경보 규칙의 라벨처럼 대상 노드(node), 역할(role), 계열(component), 디스크 경보의 마운트 지점을 둔다.
 * 앱 계층 경보는 역할만 app이다
 */
export function simAlertLabels(a: Pick<OpsAlert, "target" | "thresholdKey" | "name">): Partial<Record<WindowLabelKey, string>> {
  const out: Partial<Record<WindowLabelKey, string>> = {};
  const role = HOST_ROLE[a.target];
  if (role) {
    out.node = a.target;
    out.role = role;
  } else if (a.target === "앱 계층") {
    out.role = "app";
  }
  const key = a.thresholdKey;
  if (key === "host.disk_free_alert") {
    out.component = "storage";
    out.mountpoint = "/";
  } else if (key === "db.replication_lag_alert") {
    out.component = "database";
  } else if (key === "backup.delay_alert") {
    out.component = "backup";
  } else if (key === "bot.capacity_guard") {
    out.component = "bot";
  }
  return out;
}

/** 창이 이 경보를 대상으로 삼는가. 경보명과 라벨이 모두 맞아야 한다 */
export function windowCovers(w: MaintenanceWindow, alertName: string, labels: Partial<Record<WindowLabelKey, string>>): boolean {
  if (w.alertname !== null && w.alertname !== alertName) return false;
  return LABEL_KEYS.every((k) => w.labels[k] === undefined || w.labels[k] === labels[k]);
}

/** 창 안이어도 AI가 보류하지 않는 계열(S7: 복제·DB·백업·디스크, S5: 관측 경로) */
const NO_AI_SILENCE_KEYS = new Set<ThresholdKey>(["host.disk_free_alert", "db.replication_lag_alert", "backup.delay_alert"]);
const NO_AI_SILENCE_COMPONENTS = new Set(["database", "db", "backup", "storage", "monitoring", "watchdog"]);

export function aiMaySilenceKind(a: Pick<OpsAlert, "thresholdKey" | "name">, labels: Partial<Record<WindowLabelKey, string>>): boolean {
  if (a.thresholdKey !== null && NO_AI_SILENCE_KEYS.has(a.thresholdKey)) return false;
  if (labels.component && NO_AI_SILENCE_COMPONENTS.has(labels.component)) return false;
  return !/복제|백업|디스크|inode|DB/i.test(a.name);
}

/* ===== 변경 요청의 단계와 기록 ===== */

/** 변경 요청이 실제로 차지한 시간. 진행했으면 창의 시각, 아니면 계획한 시각이다 */
export function changeSpan(c: ChangeRequest, windows: MaintenanceWindow[]): { start: string; end: string } {
  const w = c.windowId === null ? null : windows.find((x) => x.id === c.windowId) ?? null;
  if (c.startedAt) return { start: c.startedAt, end: c.closedAt ?? (w ? windowClosedAt(w) : c.endsAt) };
  return { start: c.startsAt, end: c.endsAt };
}

/** 창의 시각 안에 일어난 조치. 창을 연 기록과 닫은 기록, 변경 요청 자체의 기록은 뺀다 */
export function actionsIn(records: ActionRecord[], span: { start: string; end: string }): ActionRecord[] {
  const a = Date.parse(span.start);
  const b = Date.parse(span.end);
  return records.filter((r) => {
    const t = Date.parse(r.at);
    return t >= a && t <= b && r.category !== "WINDOW" && r.category !== "CHANGE" && r.category !== "SESSION";
  });
}

/**
 * 변경 요청의 단계 막대(디자인 시스템 §12-15). 등록, 승인, 진행, 종료, 검토다. 취소한 요청은 그 뒤 단계를 '취소'로 건너고,
 * 롤백한 요청의 종료는 실패 모양이 아니라 건너뛴 모양 없이 '롤백'을 적는다(롤백은 계획된 되돌리기 계획의 실행이다).
 * 검토는 종료 뒤 그 창 안의 조치 수다
 */
export function changeStages(c: ChangeRequest, actionCount: number | null): StageMark[] {
  const mark = (key: string, label: string, over: Partial<StageMark>): StageMark => ({ key, label, state: "todo", at: null, note: null, ai: false, marks: [], ...over });
  const canceled = c.state === "CANCELED";
  const closed = c.state === "DONE" || c.state === "ROLLED_BACK";
  const out: StageMark[] = [
    mark("request", "등록", { state: "done", at: c.requestedAt }),
    c.approvedAt
      ? mark("approve", "승인", { state: "done", at: c.approvedAt })
      : canceled
        ? mark("approve", "승인", { state: "skipped", note: "취소" })
        : mark("approve", "승인", { state: "current", note: "승인 대기" }),
    c.startedAt
      ? mark("start", "진행", { state: "done", at: c.startedAt })
      : canceled
        ? mark("start", "진행", { state: "skipped", note: "취소" })
        : mark("start", "진행", c.approvedAt ? { state: "current", note: "시작 전" } : {}),
    closed
      ? mark("finish", "종료", { state: "done", at: c.closedAt, note: c.state === "ROLLED_BACK" ? "롤백" : null })
      : canceled
        ? mark("finish", "종료", { state: "skipped", note: "취소" })
        : mark("finish", "종료", c.startedAt ? { state: "current", note: "진행 중" } : {}),
    closed
      ? mark("review", "검토", { state: "done", at: c.closedAt, note: actionCount === null ? "조치 기록 확인" : `조치 ${actionCount}건` })
      : canceled
        ? mark("review", "검토", { state: "skipped", note: "취소" })
        : mark("review", "검토", {}),
  ];
  return out;
}

/** 요청의 지금 단계에서 운영자가 할 일 */
export function nextStep(c: ChangeRequest): string {
  switch (c.state) {
    case "REQUESTED":
      return "총괄이 승인 티켓(ops.change.approve)으로 승인합니다";
    case "APPROVED":
      return "시각이 되면 진행을 눌러 같은 대상의 계획 작업 창을 엽니다";
    case "IN_PROGRESS":
      return "작업을 마치면 결과(완료·롤백)를 적어 종료합니다. 창도 함께 닫힙니다";
    default:
      return "";
  }
}

/** 창 id가 변경 요청의 창인가. 창만 따로 등록했으면 null이다 */
export function changeOfWindow(changes: ChangeRequest[] | null, windowId: string): ChangeRequest | null {
  return changes?.find((c) => c.windowId === windowId) ?? null;
}

/** 달력과 고른 날 목록의 항목 한 건. 변경 요청이거나 창만 따로 등록한 계획 작업 창이다 */
export interface PlanItem {
  id: string;
  kind: "change" | "window";
  start: number;
  end: number;
  title: string;
  /** 달력 막대의 상태(§12-15 항목 칩) */
  state: "REQUESTED" | "APPROVED" | "IN_PROGRESS" | "DONE" | "ROLLED_BACK" | "CANCELED";
  target: string;
  change: ChangeRequest | null;
  window: MaintenanceWindow | null;
}

const WINDOW_STATE: Record<WindowPhase, PlanItem["state"]> = { SCHEDULED: "APPROVED", OPEN: "IN_PROGRESS", ENDED: "DONE" };

/**
 * 계획 항목. 변경 요청은 진행했으면 창의 시각, 아니면 계획한 시각에 선다. 변경 요청 원천이 없으면(http) 창만 싣는다. 변경
 * 요청이 연 창은 그 요청과 한 항목이다
 */
export function planItems(windows: MaintenanceWindow[], changes: ChangeRequest[] | null, now: number): PlanItem[] {
  const out: PlanItem[] = [];
  for (const c of changes ?? []) {
    const span = changeSpan(c, windows);
    const w = c.windowId === null ? null : windows.find((x) => x.id === c.windowId) ?? null;
    out.push({
      id: c.id, kind: "change", start: Date.parse(span.start), end: Math.max(Date.parse(span.end), Date.parse(span.start) + 60_000),
      title: c.title, state: c.state, target: c.target, change: c, window: w,
    });
  }
  const owned = new Set((changes ?? []).map((c) => c.windowId).filter((id): id is string => id !== null));
  for (const w of windows) {
    if (owned.has(w.id)) continue;
    out.push({
      id: w.id, kind: "window", start: Date.parse(w.startsAt), end: Date.parse(windowClosedAt(w)),
      title: w.reason || "계획 작업 창", state: WINDOW_STATE[windowPhase(w, now)], target: windowTargetWord(w), change: null, window: w,
    });
  }
  return out.sort((a, b) => a.start - b.start);
}

/** 지금 열린 창의 항목(변경 요청이 연 창이면 그 요청과 함께). 끝이 먼저 오는 순서다 */
export function openPlanItems(items: PlanItem[], now: number): PlanItem[] {
  return items.filter((it) => it.window !== null && windowOpen(it.window, now)).sort((a, b) => Date.parse(a.window?.endsAt ?? "") - Date.parse(b.window?.endsAt ?? ""));
}
