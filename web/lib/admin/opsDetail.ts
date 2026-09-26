/**
 * 상세 층의 판독 규칙(관리자 설계 4-9). 노드 상세의 이웃 순서와 서비스, 노드에 걸린 기록, 사건의 확인·해제까지
 * 걸린 시간과 절, 판단 기록 찾기다. 관제 판(ControlCenter)만 읽고 새 값을 지어내지 않는다.
 */

import { directionParticle, objectParticle } from "./text";
import { kstDay } from "./timeText";
import type {
  AiJudgment,
  AiProposal,
  AiServer,
  ControlCenter,
  EscalationLevel,
  EscalationStep,
  Incident,
  IncidentEvent,
  IncidentLog,
  IncidentPage,
  OpsAlert,
  OpsHost,
  OpsHostRole,
  ReadNode,
} from "./types";

export type NodeRef =
  | { kind: "host"; id: string; host: OpsHost }
  | { kind: "read"; id: string; node: ReadNode }
  | { kind: "ai"; id: string; server: AiServer };

/** 노드 상세의 이웃 순서. 인프라 칸을 읽는 순서다(앱 계층 app-1·app-2·app-3, DB, 관측, 온프레미스) */
export function nodeList(c: ControlCenter): NodeRef[] {
  const out: NodeRef[] = [];
  const hosts = c.hosts.state === "READY" ? c.hosts.data : [];
  const role = (r: OpsHostRole) => hosts.filter((h) => h.role === r).map((h): NodeRef => ({ kind: "host", id: h.id, host: h }));
  out.push(...role("APP"));
  if (c.readTier.state === "READY") out.push(...c.readTier.data.nodes.map((n): NodeRef => ({ kind: "read", id: n.id, node: n })));
  out.push(...role("DB_PRIMARY"), ...role("DB_STANDBY"));
  out.push(...role("BOT_OBS"));
  if (c.aiServer.state === "READY") out.push({ kind: "ai", id: c.aiServer.data.id, server: c.aiServer.data });
  return out;
}

export interface NodeService {
  unit: string;
  role: string;
}

/**
 * 노드에서 도는 systemd 서비스. 이름은 배포의 node_exporter systemd 수집기가 모으는 대상
 * (deploy/linux/observability/node_exporter.service 의 unit-include)이고, ai-1은 ADR 0038의 실행기다
 */
const SERVICES: Record<OpsHostRole | "READ" | "AI", NodeService[]> = {
  APP: [
    { unit: "nginx", role: "HTTPS 종단 · 관리 게이트웨이" },
    { unit: "exchange-app", role: "Spring Boot · 매칭 리더 · 조회" },
    { unit: "failstop-watchdog", role: "서비스 멈춤 감시" },
    { unit: "node_exporter", role: "호스트 지표" },
    { unit: "sshd", role: "운영자 접속" },
    { unit: "chronyd", role: "시각 동기화" },
  ],
  READ: [
    { unit: "exchange-app", role: "Spring Boot · 조회 전용(read 프로파일)" },
    { unit: "node_exporter", role: "호스트 지표" },
    { unit: "sshd", role: "운영자 접속" },
    { unit: "chronyd", role: "시각 동기화" },
  ],
  DB_PRIMARY: [
    { unit: "postgresql", role: "PostgreSQL 17 · Primary" },
    { unit: "postgres_exporter", role: "DB 지표" },
    { unit: "failstop-db-backup", role: "백업 · 타이머로 돎" },
    { unit: "node_exporter", role: "호스트 지표" },
    { unit: "sshd", role: "운영자 접속" },
    { unit: "chronyd", role: "시각 동기화" },
  ],
  DB_STANDBY: [
    { unit: "postgresql", role: "PostgreSQL 17 · Standby" },
    { unit: "postgres_exporter", role: "DB 지표" },
    { unit: "node_exporter", role: "호스트 지표" },
    { unit: "sshd", role: "운영자 접속" },
    { unit: "chronyd", role: "시각 동기화" },
  ],
  BOT_OBS: [
    { unit: "exchange-bot", role: "시장 봇" },
    { unit: "exchange-bot-capacity", role: "봇 용량 보호 · 타이머로 돎" },
    { unit: "prometheus", role: "지표 수집" },
    { unit: "alertmanager", role: "경보 수신·보류" },
    { unit: "grafana-server", role: "추이 대시보드" },
    { unit: "failstop-reporting", role: "운영 보고 · Teams 전송" },
    { unit: "node_exporter", role: "호스트 지표" },
    { unit: "sshd", role: "운영자 접속" },
    { unit: "chronyd", role: "시각 동기화" },
  ],
  AI: [{ unit: "ollama", role: "로컬 LLM 실행기 · 구축 전" }],
};

export function nodeServices(ref: NodeRef): NodeService[] {
  if (ref.kind === "read") return SERVICES.READ;
  if (ref.kind === "ai") return SERVICES.AI;
  return SERVICES[ref.host.role];
}

/** app-3의 기록은 앱 계층 단위로 남는다. 노드 이름과 함께 이 말들로 찾는다 */
const READ_WORDS = /app-3|앱 계층|자동 증설/;

/**
 * 노드에 걸린 기록. 글이나 속한 사건의 이름이 그 노드를 가리키는 기록이다. 시스템 기록(경보 발생·해제, 보류 만료,
 * 관찰 창)과 조치 기록(운영자·AI가 한 일과 그 결과)으로 가른다. 최신이 앞이다
 */
export function nodeEvents(log: IncidentLog, ref: NodeRef): { system: IncidentEvent[]; actions: IncidentEvent[] } {
  const titles = new Map(log.incidents.map((i) => [i.id, i.title]));
  const hits = (text: string) => (ref.kind === "read" ? READ_WORDS.test(text) : text.includes(ref.id));
  const mine = log.events.filter((e) => hits(e.text) || (e.incidentId !== null && hits(titles.get(e.incidentId) ?? "")));
  return {
    system: mine.filter((e) => e.actor === "SYSTEM" && e.kind !== "ACTED"),
    actions: mine.filter((e) => e.actor !== "SYSTEM" || e.kind === "ACTED"),
  };
}

/** 노드에 지금 걸린 경보. 경보가 가리키는 자리(ref)나 대상이 그 노드다 */
export function nodeAlerts(alerts: OpsAlert[], ref: NodeRef): OpsAlert[] {
  return alerts.filter((a) =>
    ref.kind === "read"
      ? a.ref?.kind === "READ_TIER"
      : (a.ref?.kind === "HOST" && a.ref.id === ref.id) || a.target === ref.id,
  );
}

/**
 * 운영자가 사건을 처음 확인하거나 결정한 기록의 종류. 경보 확인, 승인, 거절, 나중에, 보류, 되돌리기, 운영자 조치다.
 * 담당 지정과 사후 분석은 확인·결정이 아니라 넣지 않는다. 확인까지(MTTA)와 호출 멈춤이 이 한 규칙을 쓴다
 */
const OPERATOR_TOUCH = new Set<IncidentEvent["kind"]>(["ACKED", "APPROVED", "REJECTED", "DEFERRED", "SILENCED", "REVERTED", "ACTED"]);

/** 운영자의 확인·결정 기록인가. 관제 시뮬레이터가 이 규칙으로 호출을 멈춘다 */
export function isOperatorTouch(e: Pick<IncidentEvent, "actor" | "kind">): boolean {
  return e.actor === "OPERATOR" && OPERATOR_TOUCH.has(e.kind);
}

export interface IncidentTimes {
  /** 발생에서 운영자의 첫 확인·결정까지. 없으면 null */
  ttaMs: number | null;
  /** 발생에서 해제까지. 진행 중이면 null */
  ttrMs: number | null;
  /** 운영자 결정 없이 자동 조치로만 다룬 사건인가 */
  autoOnly: boolean;
}

export function incidentTimes(inc: Incident, events: IncidentEvent[]): IncidentTimes {
  const opened = Date.parse(inc.openedAt);
  const mine = events.filter((e) => e.incidentId === inc.id && Date.parse(e.at) >= opened);
  const firstTouch = mine
    .filter(isOperatorTouch)
    .reduce<IncidentEvent | null>((a, e) => (a === null || Date.parse(e.at) < Date.parse(a.at) ? e : a), null);
  return {
    ttaMs: firstTouch === null ? null : Date.parse(firstTouch.at) - opened,
    ttrMs: inc.resolvedAt === null ? null : Date.parse(inc.resolvedAt) - opened,
    autoOnly: firstTouch === null && mine.some((e) => e.kind === "AUTO_ACTED" || e.kind === "AUTO_REVERTED"),
  };
}

/** 오늘 사건 수와 해제된 사건의 평균 복구 시간. 오늘은 KST 날짜 기준이다(§12-12). 관제센터 요약 칸과 경보·사건 화면이 쓴다 */
export function todayIncidents(log: IncidentLog, now: number): { count: number; open: number; mttrMs: number | null } {
  const today = kstDay(now);
  const list = log.incidents.filter((i) => kstDay(Date.parse(i.openedAt)) === today);
  const done = list.filter((i) => i.resolvedAt !== null);
  const mttrMs = done.length === 0 ? null
    : done.reduce((a, i) => a + (Date.parse(i.resolvedAt as string) - Date.parse(i.openedAt)), 0) / done.length;
  return { count: list.length, open: list.length - done.length, mttrMs };
}

export function alertCounts(alerts: OpsAlert[]): Record<"critical" | "warning" | "info", number> {
  const out = { critical: 0, warning: 0, info: 0 };
  for (const a of alerts) out[a.severity] += 1;
  return out;
}

/** KST 오늘 연 사건 가운데 값이 있는 것의 평균 확인·해제 시간 */
export function todayMeans(log: IncidentLog, now: number): { mttaMs: number | null; mttrMs: number | null } {
  const today = kstDay(now);
  const times = log.incidents.filter((i) => kstDay(Date.parse(i.openedAt)) === today).map((i) => incidentTimes(i, log.events));
  const mean = (xs: number[]) => (xs.length === 0 ? null : xs.reduce((a, b) => a + b, 0) / xs.length);
  return {
    mttaMs: mean(times.map((t) => t.ttaMs).filter((v): v is number => v !== null)),
    mttrMs: mean(times.map((t) => t.ttrMs).filter((v): v is number => v !== null)),
  };
}

/** 단계별 호출 기록인가. 관제 시뮬레이터가 호출을 '호출 1차 · …'로 사건 기록에 남긴다(Teams 알림과 같은 종류) */
export function isPageEvent(e: Pick<IncidentEvent, "kind" | "text">): boolean {
  return e.kind === "NOTIFIED" && e.text.startsWith("호출 ");
}

/** 사건 드로어의 절. 기록이 오래된 순서다. 단계별 호출은 Teams 전송과 따로 호출 절이 싣는다 */
export function incidentSections(inc: Incident, events: IncidentEvent[]): {
  alerts: IncidentEvent[];
  ai: IncidentEvent[];
  operator: IncidentEvent[];
  teams: IncidentEvent[];
  pages: IncidentEvent[];
} {
  const mine = events.filter((e) => e.incidentId === inc.id).sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  return {
    alerts: mine.filter((e) => e.actor === "SYSTEM" && (e.kind === "OPENED" || e.kind === "RESOLVED")),
    ai: mine.filter((e) => e.actor === "AI"),
    operator: mine.filter((e) => e.actor === "OPERATOR"),
    teams: mine.filter((e) => e.kind === "NOTIFIED" && !isPageEvent(e)),
    pages: mine.filter(isPageEvent),
  };
}

/* ===== 사건 생애와 호출 단계(관리자 설계 4-10-2, 디자인 시스템 §12-15 단계 막대) ===== */

export type StageState = "done" | "current" | "todo" | "skipped" | "failed";

/** 단계 막대의 한 칸. 사건 생애, 호출 단계, 변경 요청, 시나리오 단계가 같은 모양을 쓴다 */
export interface StageMark {
  key: string;
  label: string;
  state: StageState;
  /** 그 단계가 된 시각. 오지 않았으면 null */
  at: string | null;
  /** 건너뜀의 이유나 짧은 보조 말('확인 없음 · 자동 조치', '초안') */
  note: string | null;
  /** AI가 한 단계인가(AI 강조색 테두리, 지나면 채움) */
  ai: boolean;
  /** 칸 안의 보조 표지. 대응 칸 아래의 AI 판단과 승인이다 */
  marks: { label: string; at: string; ai: boolean }[];
}

/**
 * 대응 단계의 첫 조치 기록. 운영자가 승인한 실행, AI 자동 실행, 경보 보류(운영자·AI), 운영자가 한 조치, 되돌리기다.
 * 시스템이 남긴 보류 만료와 조치 결과 줄은 조치가 아니다
 */
function isResponse(e: IncidentEvent): boolean {
  switch (e.kind) {
    case "APPROVED":
    case "AUTO_ACTED":
    case "AUTO_REVERTED":
      return true;
    case "SILENCED":
    case "ACTED":
    case "REVERTED":
      return e.actor !== "SYSTEM";
    default:
      return false;
  }
}

/**
 * 사건 생애 다섯 단계. 발생(경보 발생), 확인(운영자의 첫 확인·결정, MTTA와 같은 정의), 대응(첫 조치 기록), 해제(MTTR의
 * 끝), 사후 분석(완료)이다. 운영자 확인 없이 자동 조치로 풀린 사건의 확인은 '확인 없음 · 자동 조치', 조치 없이 풀린
 * 사건의 대응은 '조치 없이 해제'로 건넌다. 진행 중이면 오지 않은 첫 단계가 지금 단계이고, 해제된 사건은 사후 분석이
 * 지금 단계다. 사후 분석 원천이 없으면(http) 그 칸은 오지 않은 단계로 두고 연결 전을 적는다
 */
export function lifecycleOf(inc: Incident, events: IncidentEvent[]): StageMark[] {
  const opened = Date.parse(inc.openedAt);
  const mine = events
    .filter((e) => e.incidentId === inc.id && Date.parse(e.at) >= opened)
    .sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  const t = incidentTimes(inc, events);
  const resolved = inc.resolvedAt !== null;
  const firstOf = (pred: (e: IncidentEvent) => boolean) => mine.find(pred) ?? null;
  const touch = firstOf(isOperatorTouch);
  const act = firstOf(isResponse);
  const judged = firstOf((e) => e.kind === "AI_JUDGED");
  const approved = firstOf((e) => e.kind === "APPROVED");
  const mark = (key: string, label: string, over: Partial<StageMark>): StageMark => ({
    key, label, state: "todo", at: null, note: null, ai: false, marks: [], ...over,
  });
  const out: StageMark[] = [
    mark("open", "발생", { state: "done", at: inc.openedAt }),
    touch
      ? mark("ack", "확인", { state: "done", at: touch.at })
      : resolved
        ? mark("ack", "확인", { state: "skipped", note: t.autoOnly ? "확인 없음 · 자동 조치" : "확인 없음" })
        : mark("ack", "확인", {}),
    act
      ? mark("act", "대응", { state: "done", at: act.at, ai: act.actor === "AI" })
      : resolved
        ? mark("act", "대응", { state: "skipped", note: "조치 없이 해제" })
        : mark("act", "대응", {}),
    resolved ? mark("resolve", "해제", { state: "done", at: inc.resolvedAt }) : mark("resolve", "해제", {}),
  ];
  const marks: StageMark["marks"] = [];
  if (judged) marks.push({ label: "AI 판단", at: judged.at, ai: true });
  if (approved) marks.push({ label: "승인", at: approved.at, ai: false });
  out[2].marks = marks;
  const pm = inc.postmortem;
  if (pm === undefined) {
    out.push(mark("postmortem", "사후 분석", { note: resolved ? "원천 연결 전" : null }));
  } else if (pm !== null && pm.status === "DONE") {
    out.push(mark("postmortem", "사후 분석", { state: "done", at: pm.updatedAt }));
  } else {
    out.push(mark("postmortem", "사후 분석", resolved ? { state: "current", note: pm?.status === "DRAFT" ? "초안" : "작성 전" } : {}));
  }
  if (!resolved) {
    const cur = out.findIndex((s) => s.state === "todo");
    if (cur >= 0 && cur < 4) out[cur] = { ...out[cur], state: "current" };
  }
  return out;
}

/** 호출 단계의 모양. 첫 알림과 정책의 단계들, 지금 호출 중인지, 다음 호출이다 */
export interface EscalationView {
  steps: StageMark[];
  /** 보낸 가장 높은 단계. 보낸 호출이 없으면 0 */
  level: 0 | EscalationLevel;
  /** 확인·결정 없이 호출 중인가(가장 높은 호출이 아직 SENT) */
  paging: boolean;
  /** 다음 호출 단계와 그 시각(ms). 확인됐거나 해제됐거나 다 보냈으면 null */
  next: { level: EscalationLevel; at: number } | null;
}

/**
 * 사건의 호출 단계(관리자 설계 4-10-2). 호출 원천이 없어(http) 사건에 호출 기록 칸이 없으면 null이다. 경보·사건 목록,
 * 사건 드로어, 관제센터 경보 행의 칩이 이 한 함수로 같은 단계를 적는다
 */
export function escalationOf(inc: Incident, policy: EscalationStep[] | null, events: IncidentEvent[]): EscalationView | null {
  const pages = inc.pages;
  if (pages === undefined) return null;
  const opened = Date.parse(inc.openedAt);
  const t = incidentTimes(inc, events);
  const touchAt = t.ttaMs === null ? null : opened + t.ttaMs;
  const endAt = inc.resolvedAt === null ? null : Date.parse(inc.resolvedAt);
  const levels = (policy ?? []).length > 0 ? (policy as EscalationStep[]) : pages.map((p) => ({ level: p.level, afterMin: 0, role: p.role, channel: p.channel }));
  // 첫 알림은 이 사건의 첫 Teams 전송이다(경보 알림, 자동 조치 통지). 기록이 없으면 보냈다고 적지 않는다
  const first = events
    .filter((e) => e.incidentId === inc.id && e.kind === "NOTIFIED" && !isPageEvent(e))
    .reduce<IncidentEvent | null>((a, e) => (a === null || Date.parse(e.at) < Date.parse(a.at) ? e : a), null);
  const steps: StageMark[] = [
    first
      ? { key: "first", label: "첫 알림", state: "done", at: first.at, note: "Teams", ai: false, marks: [] }
      : { key: "first", label: "첫 알림", state: "skipped", at: null, note: "기록 없음", ai: false, marks: [] },
  ];
  let next: EscalationView["next"] = null;
  for (const s of levels) {
    const page = pages.find((p) => p.level === s.level) ?? null;
    const due = opened + s.afterMin * 60_000;
    const label = `${ESCALATION_LEVEL_WORD[s.level]} · ${s.afterMin}분`;
    if (page) {
      steps.push({ key: `l${s.level}`, label, state: "done", at: page.at, note: page.person ?? s.role, ai: false, marks: [] });
    } else if (touchAt !== null && touchAt <= due) {
      steps.push({ key: `l${s.level}`, label, state: "skipped", at: null, note: "확인으로 멈춤", ai: false, marks: [] });
    } else if (endAt !== null && endAt <= due) {
      steps.push({ key: `l${s.level}`, label, state: "skipped", at: null, note: "해제로 멈춤", ai: false, marks: [] });
    } else if (next === null && touchAt === null && endAt === null) {
      next = { level: s.level, at: due };
      steps.push({ key: `l${s.level}`, label, state: "current", at: null, note: s.role, ai: false, marks: [] });
    } else {
      steps.push({ key: `l${s.level}`, label, state: "todo", at: null, note: s.role, ai: false, marks: [] });
    }
  }
  const top = pages.reduce<IncidentPage | null>((a, p) => (a === null || p.level > a.level ? p : a), null);
  return { steps, level: top?.level ?? 0, paging: top !== null && top.state === "SENT", next };
}

/** 호출 단계의 이름. 정책과 사건 기록이 같은 말을 쓴다(oncall.ts의 LEVEL_WORD와 같다) */
export const ESCALATION_LEVEL_WORD: Record<EscalationLevel, string> = { 1: "1차", 2: "2차", 3: "책임자" };

/** 진행 중인 사건. 오래된 것이 앞이다 */
export function openIncidents(log: IncidentLog): Incident[] {
  return log.incidents.filter((i) => i.resolvedAt === null).sort((a, b) => Date.parse(a.openedAt) - Date.parse(b.openedAt));
}

/** 경보·사건 목록의 기간 거르기. 오늘은 KST 날짜, 7일은 지금부터 거꾸로 7×24시간이다 */
export function incidentsIn(log: IncidentLog, range: "today" | "week", now: number): Incident[] {
  const today = kstDay(now);
  return log.incidents
    .filter((i) => (range === "today" ? kstDay(Date.parse(i.openedAt)) === today : now - Date.parse(i.openedAt) <= 7 * 86_400_000))
    .sort((a, b) => Date.parse(b.openedAt) - Date.parse(a.openedAt));
}

/**
 * 사건 기록의 AI 행에 맞는 판단 기록. 그 시각 이전(1초 여유)의 가장 가까운 판단 과정이 있는 기록이다. 판은 최근
 * 판단 기록만 싣으므로 오래된 사건이면 null이다
 */
export function judgmentAt(history: AiJudgment[], at: string): AiJudgment | null {
  const t = Date.parse(at) + 1000;
  let best: AiJudgment | null = null;
  for (const j of history) {
    const jt = Date.parse(j.at);
    if (j.trace === null || jt > t) continue;
    if (best === null || jt > Date.parse(best.at)) best = j;
  }
  if (best !== null && t - Date.parse(best.at) > 60_000) return null;
  return best;
}

/** 조치 확인의 동사형 제목(디자인 시스템 §12-13의 5). 조치를 무엇을 하는지로 적는다 */
export function actionVerb(p: Pick<AiProposal, "kind" | "title" | "change">): string {
  const c = p.change;
  switch (p.kind) {
    case "READ_NODES":
      // 제목은 앱 계층의 대수로 적는다. app-3 뒤 조사는 읽는 법(쓰리·삼)에 따라 달라 쓰지 않는다
      if (c === null) return "앱 계층 대수를 바꿉니다";
      return c.to < c.from ? `앱 계층을 ${c.to}대로 줄입니다` : `앱 계층을 ${c.to}대로 늘립니다`;
    case "THRESHOLD": {
      const name = /^(.*?)\s*\d/.exec(p.title)?.[1] ?? "임계값";
      if (c === null) return `${name}${objectParticle(name)} 바꿉니다`;
      const to = `${c.to}${c.unit}`;
      return `${name}${objectParticle(name)} ${c.from}${c.unit}에서 ${to}${directionParticle(to)} ${c.to < c.from ? "낮춥니다" : "올립니다"}`;
    }
    case "SILENCE":
      return "경보를 30분 보류합니다";
    case "BOT_LOAD":
      return "시장 봇 부하를 줄입니다";
    case "BACKUP_NOW":
      return "Databasus 백업을 지금 실행합니다";
  }
}

/** 변경 전후를 큰 숫자로 적는 두 조각. 대수와 %는 숫자에 붙이고 OPS는 뒤에 한 번 띄어 적는다 */
export function changeParts(c: NonNullable<AiProposal["change"]>): { from: string; to: string } {
  const fmt = (v: number) => (c.unit === "OPS" ? `${v} OPS` : `${v}${c.unit}`);
  return { from: fmt(c.from), to: fmt(c.to) };
}
