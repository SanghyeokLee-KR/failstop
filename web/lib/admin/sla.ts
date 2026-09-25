/**
 * SLA 월간 보고(관리자 설계 4-10-5). 한 달의 외부 가용성과 오류 예산, 사건 수·MTTA·MTTR, 끝난 변경 요청, AI 조치와 되돌림, 다음 달
 * 권고를 낸다. 가용성은 외부 프로브 성공률이고 주문 API SLO 99.9%(API 설계 §7-4)와 재는 지표가 달라 섞지 않는다.
 *
 * sim은 관제 판의 기록이 닿는 날(지난 48시간이 걸친 날)을 그 판과 감사 기록에서 센다. 그 앞의 날은 날짜에서 정해지는 예시(시뮬레이터
 * 시드)라 같은 날짜는 늘 같은 값이다. 예시 사건은 관제 판 밖이라 드로어를 열 곳이 없다. 변경 요청은 관제 판이 싣는 지난 7일과 그
 * 앞의 예시다. http는 원천 연결 전이다(외부 프로브와 사건 저장소의 월 집계 경로가 계약 초안이다).
 */

import { incidentTimes } from "./opsDetail";
import { baselineUnavailMinutes } from "./opsTrends";
import { kstDay } from "./timeText";
import type { AlertSeverity, ControlCenter, EscalationLevel, SlaChange, SlaDay, SlaIncident, SlaMonth, SlaRule } from "./types";

/** SLA 목표(외부 가용성). 관제 시뮬레이터의 목표와 같고 서버 목표 설정은 결정 대기다(관리자 설계 7-11 (d)) */
export const SLA_TARGET_PCT = 99.5;

/** 원천 연결 전일 때 붙일 원천 */
export const SLA_SOURCE = "SLA 월 집계(GET /admin/v1/msp/sla?month=YYYY-MM · 3라운드 계약 초안 · 외부 프로브 연결 전)";

const DAY_MS = 86_400_000;
const MIN_MS = 60_000;
/** 관제 판이 싣는 사건 기록의 범위. 이 범위가 걸친 날을 판에서 센다 */
const BOARD_SPAN_MS = 48 * 3_600_000;
/** 관제 판이 싣는 변경 요청의 범위 */
const CHANGE_SPAN_MS = 7 * DAY_MS;

export function daysIn(month: string): number {
  const [y, m] = month.split("-").map(Number);
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

/** 그 달의 오류 예산(분). 그 달 전체의 분에 (100 - 목표)%를 곱한다. 30일 달 216분, 31일 달 223.2분이다 */
export function budgetMinutes(month: string, targetPct = SLA_TARGET_PCT): number {
  return Math.round(daysIn(month) * 1440 * (100 - targetPct) * 10) / 1000;
}

const dayStart = (day: string) => Date.parse(`${day}T00:00:00+09:00`);

export function monthOf(now: number): string {
  return kstDay(now).slice(0, 7);
}

export function prevMonth(month: string): string {
  const [y, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(y, m - 2, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

/* ===== 예시(시드) =====
 * 날짜마다 정해지는 값이다. 사건의 종류는 관제 시뮬레이터의 경보 이름이고 시각·걸린 시간은 지어낸 예시다 */

/** 날짜와 번호에서 정해지는 0 이상 1 미만의 수 */
function rnd(day: string, k: number): number {
  let h = 2166136261;
  for (const ch of `${day}#${k}`) {
    h ^= ch.charCodeAt(0);
    h = Math.imul(h, 16777619);
  }
  h ^= h >>> 15;
  h = Math.imul(h, 2246822507);
  h ^= h >>> 13;
  return (h >>> 0) / 4294967296;
}

const between = (r: number, [lo, hi]: [number, number]) => lo + (hi - lo) * r;

interface SeedKind {
  alert: string;
  target: string;
  severity: AlertSeverity;
  weight: number;
  /** 가용성을 온전히 잃은 것과 같은 분 */
  down: [number, number];
  tta: [number, number];
  ttr: [number, number];
  /** AI 조치(app-3 켜기, 봇 부하 줄임)로 다룬 사건인가 */
  ai: boolean;
}

const SEED_KINDS: SeedKind[] = [
  { alert: "응답 지연 증가", target: "외부 응답", severity: "warning", weight: 3, down: [0.6, 2.4], tta: [1, 4], ttr: [4, 9], ai: true },
  { alert: "복제 지연", target: "db-standby", severity: "warning", weight: 3, down: [0, 0], tta: [1, 17], ttr: [5, 18], ai: false },
  { alert: "앱 계층 CPU 높음", target: "앱 계층", severity: "warning", weight: 2, down: [0, 0.6], tta: [1, 5], ttr: [3, 8], ai: true },
  { alert: "봇 용량 보호", target: "bot-1", severity: "warning", weight: 1, down: [0, 0], tta: [1, 3], ttr: [3, 6], ai: true },
  { alert: "디스크 여유 부족", target: "bot-1", severity: "warning", weight: 1, down: [0, 0], tta: [2, 7], ttr: [8, 22], ai: false },
  { alert: "백업 지연", target: "Databasus", severity: "warning", weight: 0.6, down: [0, 0], tta: [6, 40], ttr: [30, 90], ai: false },
  { alert: "외부 가용성 저하", target: "외부 프로브", severity: "critical", weight: 0.25, down: [6, 12], tta: [1, 3], ttr: [8, 15], ai: false },
];
const WEIGHT_SUM = SEED_KINDS.reduce((a, k) => a + k.weight, 0);

function pickKind(r: number): SeedKind {
  let acc = 0;
  for (const k of SEED_KINDS) {
    acc += k.weight / WEIGHT_SUM;
    if (r < acc) return k;
  }
  return SEED_KINDS[0];
}

const SEED_CHANGES: [string, "DONE" | "ROLLED_BACK", string][] = [
  ["Grafana 대시보드 갱신", "DONE", "완료 · 새 패널로 경보와 추세를 확인했습니다"],
  ["node_exporter 수집 목록 조정", "DONE", "완료 · 수집 대상과 경보를 확인했습니다"],
  ["로그 보관 기간 조정", "DONE", "완료 · 보관 기간과 압축 주기를 적용했습니다"],
  ["봇 발행률 조정 시험", "ROLLED_BACK", "되돌림 · bot-1 CPU가 봇 용량 보호 기준을 넘었습니다"],
  ["Nginx 설정 정리", "DONE", "완료 · 설정 검사와 reload 뒤 응답을 확인했습니다"],
  ["경보 규칙 임계 조정", "DONE", "완료 · 새 기준으로 경보 발생과 해제를 확인했습니다"],
];

interface SeedDay {
  unavailMin: number;
  incidents: SlaIncident[];
  changes: SlaChange[];
  ai: { runs: number; autoRuns: number; reverts: number; autoReverts: number };
}

const seedCache = new Map<string, SeedDay>();

function levelOf(ttaMin: number | null): 0 | EscalationLevel {
  if (ttaMin === null) return 0;
  return ttaMin >= 30 ? 3 : ttaMin >= 15 ? 2 : ttaMin >= 5 ? 1 : 0;
}

/** 예시 날 하루. 사건은 0~2건, 변경은 가끔이고, AI 조치는 AI가 다룬 사건에서 센다 */
function seedDay(day: string): SeedDay {
  const hit = seedCache.get(day);
  if (hit) return hit;
  const from = dayStart(day);
  const out: SeedDay = { unavailMin: baselineUnavailMinutes(from, from + DAY_MS), incidents: [], changes: [], ai: { runs: 0, autoRuns: 0, reverts: 0, autoReverts: 0 } };
  const r0 = rnd(day, 0);
  const n = r0 < 0.5 ? 0 : r0 < 0.88 ? 1 : 2;
  for (let i = 0; i < n; i += 1) {
    const kind = pickKind(rnd(day, 10 + i));
    const at = from + Math.floor(between(rnd(day, 20 + i), [1, 23]) * 3_600_000 + rnd(day, 30 + i) * 3_600_000);
    const auto = kind.ai && rnd(day, 40 + i) < 0.35;
    const ttaMin = auto ? null : Math.round(between(rnd(day, 50 + i), kind.tta) * 10) / 10;
    const ttrMin = Math.round(between(rnd(day, 60 + i), kind.ttr) * 10) / 10;
    out.unavailMin += between(rnd(day, 70 + i), kind.down);
    const pm = rnd(day, 80 + i);
    out.incidents.push({
      id: `seed-${day}-${i + 1}`, title: `${kind.alert} · ${kind.target}`, alert: kind.alert, severity: kind.severity,
      openedAt: new Date(at).toISOString(), resolvedAt: new Date(at + ttrMin * MIN_MS).toISOString(),
      ttaMs: ttaMin === null ? null : ttaMin * MIN_MS, ttrMs: ttrMin * MIN_MS, pagedLevel: levelOf(ttaMin),
      postmortem: kind.severity === "critical" || pm < 0.3 ? "DONE" : pm < 0.5 ? "DRAFT" : "NONE", source: "SEED",
    });
    if (kind.ai) {
      out.ai.runs += 1;
      if (auto) {
        out.ai.autoRuns += 1;
        if (rnd(day, 90 + i) < 0.15) {
          out.ai.reverts += 1;
          out.ai.autoReverts += 1;
        }
      }
    }
  }
  const rc = rnd(day, 100);
  if (rc < 0.22) {
    const [title, state, result] = SEED_CHANGES[Math.floor(rnd(day, 101) * SEED_CHANGES.length)];
    out.changes.push({
      id: `CHG-${day.slice(5, 7)}${day.slice(8, 10)}`, title, state, closedAt: new Date(from + 15 * 3_600_000 + Math.floor(rnd(day, 102) * 3) * 3_600_000).toISOString(),
      result, source: "SEED",
    });
  }
  seedCache.set(day, out);
  return out;
}

/* ===== 관제 판의 날 ===== */

/** 관제 판의 사건을 SLA 사건 줄로 옮긴다. 담당·호출 원천이 없는 판(http)은 호출 단계가 0이다 */
function boardIncidents(c: ControlCenter): SlaIncident[] {
  if (c.incidents.state !== "READY") return [];
  const { incidents, events } = c.incidents.data;
  return incidents.map((i) => {
    const t = incidentTimes(i, events);
    const opened = Date.parse(i.openedAt);
    const touch = t.ttaMs === null ? Number.POSITIVE_INFINITY : opened + t.ttaMs;
    const before = (i.pages ?? []).filter((p) => Date.parse(p.at) <= touch);
    return {
      id: i.id, title: i.title, alert: i.title.split(" · ")[0], severity: i.severity, openedAt: i.openedAt, resolvedAt: i.resolvedAt,
      ttaMs: t.ttaMs, ttrMs: t.ttrMs, pagedLevel: before.reduce<0 | EscalationLevel>((a, p) => (p.level > a ? p.level : a), 0),
      postmortem: i.postmortem?.status ?? "NONE", source: "BOARD",
    };
  });
}

const mean = (xs: number[]) => (xs.length === 0 ? null : xs.reduce((a, b) => a + b, 0) / xs.length);

/** 감사 한 줄에서 AI 조치를 가른다. AI 판단 화면의 '지난 24시간 AI 조치'와 같은 규칙이다(simAdmin.aiRecent) */
export function aiAuditKind(action: string): "approve" | "auto" | "auto_revert" | "revert" | null {
  const m = /^ai\.(approve|auto|auto_revert|revert):/.exec(action);
  return m ? (m[1] as "approve" | "auto" | "auto_revert" | "revert") : null;
}

export interface SlaInputs {
  month: string;
  now: number;
  /** 관제 판. 사건 기록과 변경 요청을 읽는다 */
  board: ControlCenter;
  /** 두 시각 사이에 가용하지 않았던 분. 관제 판이 닿는 날을 센다 */
  unavail: (from: number, to: number) => number;
  /** 감사 기록(성공한 줄만 센다) */
  audit: { at: string; action: string; result: string }[];
  /** AI 판단 화면과 같은 지난 24시간 AI 조치 수. 이번 달만 싣는다 */
  aiRecent: { runs: number; reverts: number } | null;
}

const ADVICE: Record<string, string> = {
  "복제 지연": "Standby 디스크 I/O와 복제 지연 경보 기준을 함께 점검합니다",
  "응답 지연 증가": "증설 CPU 임계와 app-3 기동 시간을 함께 점검합니다",
  "앱 계층 CPU 높음": "증설·축소 임계와 조회 수요 추세를 함께 점검합니다",
  "디스크 여유 부족": "로그 보관 기간과 디스크 증설 시점을 정합니다",
  "봇 용량 보호": "봇 발행률 기준과 bot-1 규격을 함께 점검합니다",
  "백업 지연": "백업 예약과 외부 보관 경로를 점검합니다",
};

/** 그 달의 수에서 나온 권고. 규칙에 걸린 것만 문장을 만들고 근거 수를 붙인다 */
export function recommendationsOf(m: Pick<SlaMonth, "budget" | "incidentRows" | "changeRows"> & { autoReverts: number }): SlaMonth["recommendations"] {
  const out: SlaMonth["recommendations"] = [];
  const add = (rule: SlaRule, basis: string, text: string) => out.push({ rule, basis, text });
  const used = m.budget.usedMin;
  if (used !== null && used >= m.budget.allowedMin * 0.8) {
    add("BUDGET", `오류 예산 ${Math.round((used / m.budget.allowedMin) * 100)}% 사용(${used.toFixed(1)}분 / ${m.budget.allowedMin}분)`, "남은 날의 변경 작업을 줄이고 가용성을 떨어뜨린 사건의 원인부터 닫습니다");
  }
  const byAlert = new Map<string, number>();
  for (const i of m.incidentRows) byAlert.set(i.alert, (byAlert.get(i.alert) ?? 0) + 1);
  for (const [alert, n] of [...byAlert].sort((a, b) => b[1] - a[1])) {
    if (n >= 3) add("REPEAT", `${alert} 경보로 연 사건 ${n}건`, ADVICE[alert] ?? "경보 기준과 원인을 함께 점검합니다");
  }
  if (m.autoReverts >= 1) add("AUTO_REVERT", `AI 자동 되돌림 ${m.autoReverts}회`, "되돌린 조치의 판단 근거와 축소 조건을 검토합니다");
  const paged = m.incidentRows.filter((i) => i.pagedLevel >= 2).length;
  if (paged >= 1) add("PAGED", `확인 없이 2차 호출까지 간 사건 ${paged}건`, "당번 호출 경로와 야간 대응 시간을 점검합니다");
  const rolled = m.changeRows.filter((c) => c.state === "ROLLED_BACK");
  if (rolled.length >= 1) add("ROLLED_BACK", `되돌린 변경 요청 ${rolled.length}건(${rolled.map((c) => c.id).join(", ")})`, "되돌린 작업의 사전 점검 항목을 변경 요청 양식에 더합니다");
  return out;
}

/**
 * 한 달의 SLA. 이번 달이면 오늘까지, 지난 달이면 그 달 끝까지 센다. 오늘 줄의 사건 수와 평균 확인·해제 시간은 경보·사건 화면의
 * 오늘 값과 같은 함수(incidentTimes)로 센다
 */
export function slaMonth(inp: SlaInputs): SlaMonth {
  const { month, now, board } = inp;
  const total = daysIn(month);
  const today = kstDay(now);
  const last = `${month}-${String(total).padStart(2, "0")}`;
  const through = today.slice(0, 7) === month ? today : last;
  const boardFrom = kstDay(now - BOARD_SPAN_MS);
  const changeFrom = kstDay(now - CHANGE_SPAN_MS);
  const monthStart = dayStart(`${month}-01`);
  const onBoard = boardIncidents(board);
  const days: SlaDay[] = [];
  const incidentRows: SlaIncident[] = [];
  const changeRows: SlaChange[] = [];
  const ai = { runs: 0, autoRuns: 0, reverts: 0, autoReverts: 0 };
  for (let d = 1; d <= Number(through.slice(8, 10)); d += 1) {
    const day = `${month}-${String(d).padStart(2, "0")}`;
    const from = dayStart(day);
    const to = Math.min(from + DAY_MS, now);
    if (day >= boardFrom) {
      const list = onBoard.filter((i) => kstDay(Date.parse(i.openedAt)) === day);
      const unavail = inp.unavail(from, to);
      incidentRows.push(...list);
      days.push({
        day, unavailMin: unavail, availabilityPct: to > from ? 100 * (1 - unavail / ((to - from) / MIN_MS)) : null, incidents: list.length,
        mttaMs: mean(list.map((i) => i.ttaMs).filter((v): v is number => v !== null)),
        mttrMs: mean(list.map((i) => i.ttrMs).filter((v): v is number => v !== null)),
        source: "BOARD",
      });
      for (const e of inp.audit) {
        if (e.result !== "OK" || kstDay(Date.parse(e.at)) !== day) continue;
        const k = aiAuditKind(e.action);
        if (k === "approve" || k === "auto") ai.runs += 1;
        if (k === "auto") ai.autoRuns += 1;
        if (k === "auto_revert" || k === "revert") ai.reverts += 1;
        if (k === "auto_revert") ai.autoReverts += 1;
      }
    } else {
      const s = seedDay(day);
      incidentRows.push(...s.incidents);
      days.push({
        day, unavailMin: s.unavailMin, availabilityPct: 100 * (1 - s.unavailMin / 1440), incidents: s.incidents.length,
        mttaMs: mean(s.incidents.map((i) => i.ttaMs).filter((v): v is number => v !== null)),
        mttrMs: mean(s.incidents.map((i) => i.ttrMs).filter((v): v is number => v !== null)),
        source: "SEED",
      });
      ai.runs += s.ai.runs;
      ai.autoRuns += s.ai.autoRuns;
      ai.reverts += s.ai.reverts;
      ai.autoReverts += s.ai.autoReverts;
      if (day < changeFrom) changeRows.push(...s.changes);
    }
  }
  // 관제 판의 변경 요청(지난 7일). 끝난 요청만 이 달의 변경으로 센다
  if (board.changes.state === "READY" && board.changes.data.changes.state === "READY") {
    for (const c of board.changes.data.changes.data) {
      if ((c.state !== "DONE" && c.state !== "ROLLED_BACK") || c.closedAt === null || kstDay(Date.parse(c.closedAt)).slice(0, 7) !== month) continue;
      changeRows.push({ id: c.id, title: c.title, state: c.state, closedAt: c.closedAt, result: c.result, source: "BOARD" });
    }
  }
  incidentRows.sort((a, b) => Date.parse(b.openedAt) - Date.parse(a.openedAt));
  changeRows.sort((a, b) => Date.parse(b.closedAt) - Date.parse(a.closedAt));
  const counted = days.reduce((a, d) => a + (d.unavailMin ?? 0), 0);
  const elapsedMin = (Math.min(now, dayStart(last) + DAY_MS) - monthStart) / MIN_MS;
  const budget = { allowedMin: budgetMinutes(month), usedMin: Math.round(counted * 10) / 10 };
  const bySeverity: Record<AlertSeverity, number> = { critical: 0, warning: 0, info: 0 };
  for (const i of incidentRows) bySeverity[i.severity] += 1;
  return {
    month, throughDay: through, daysInMonth: total, targetPct: SLA_TARGET_PCT,
    availabilityPct: elapsedMin > 0 ? 100 * (1 - counted / elapsedMin) : null,
    budget,
    incidents: {
      total: incidentRows.length, bySeverity,
      mttaMs: mean(incidentRows.map((i) => i.ttaMs).filter((v): v is number => v !== null)),
      mttrMs: mean(incidentRows.map((i) => i.ttrMs).filter((v): v is number => v !== null)),
      postmortems: incidentRows.filter((i) => i.postmortem === "DONE").length,
    },
    changes: { done: changeRows.filter((c) => c.state === "DONE").length, rolledBack: changeRows.filter((c) => c.state === "ROLLED_BACK").length },
    ai: { runs: ai.runs, autoRuns: ai.autoRuns, reverts: ai.reverts, autoReverts: ai.autoReverts, recent: through === today ? inp.aiRecent : null },
    days, incidentRows, changeRows,
    recommendations: recommendationsOf({ budget, incidentRows, changeRows, autoReverts: ai.autoReverts }),
  };
}
