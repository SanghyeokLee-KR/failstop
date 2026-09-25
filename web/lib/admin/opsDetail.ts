/**
 * 상세 층의 판독 규칙(관리자 설계 4-9). 노드 상세의 이웃 순서와 서비스, 노드에 걸린 기록, 사건의 확인·해제까지
 * 걸린 시간과 절, 판단 기록 찾기다. 관제 판(ControlCenter)만 읽고 새 값을 지어내지 않는다.
 */

import { directionParticle, objectParticle } from "./text";
import { kstDay } from "./timeText";
import type { AiJudgment, AiProposal, AiServer, ControlCenter, Incident, IncidentEvent, IncidentLog, OpsAlert, OpsHost, OpsHostRole, ReadNode } from "./types";

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

/** 운영자가 사건을 처음 확인하거나 결정한 기록의 종류. 경보 확인, 승인, 거절, 나중에, 보류, 되돌리기다 */
const OPERATOR_TOUCH = new Set<IncidentEvent["kind"]>(["ACKED", "APPROVED", "REJECTED", "DEFERRED", "SILENCED", "REVERTED", "ACTED"]);

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
    .filter((e) => e.actor === "OPERATOR" && OPERATOR_TOUCH.has(e.kind))
    .reduce<IncidentEvent | null>((a, e) => (a === null || Date.parse(e.at) < Date.parse(a.at) ? e : a), null);
  return {
    ttaMs: firstTouch === null ? null : Date.parse(firstTouch.at) - opened,
    ttrMs: inc.resolvedAt === null ? null : Date.parse(inc.resolvedAt) - opened,
    autoOnly: firstTouch === null && mine.some((e) => e.kind === "AUTO_ACTED" || e.kind === "AUTO_REVERTED"),
  };
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

/** 사건 드로어의 절. 기록이 오래된 순서다 */
export function incidentSections(inc: Incident, events: IncidentEvent[]): {
  alerts: IncidentEvent[];
  ai: IncidentEvent[];
  operator: IncidentEvent[];
  teams: IncidentEvent[];
} {
  const mine = events.filter((e) => e.incidentId === inc.id).sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  return {
    alerts: mine.filter((e) => e.actor === "SYSTEM" && (e.kind === "OPENED" || e.kind === "RESOLVED")),
    ai: mine.filter((e) => e.actor === "AI"),
    operator: mine.filter((e) => e.actor === "OPERATOR"),
    teams: mine.filter((e) => e.kind === "NOTIFIED"),
  };
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
