/**
 * AI 상담의 규칙과 sim 답(관리자 설계 4-6). 운영자가 대화로 지금 상태를 묻고, 답은 관제 판에서 읽은
 * 근거와 함께 온다. 상담은 조회만 한다. 실행을 부탁하는 말은 실행하지 않고 그 조치가 있는 조치 카드와
 * 승인 절차로 안내한다. 상담 화면에는 실행 단추가 없고 절차 화면으로 가는 링크만 있다.
 *
 * sim 답은 모델을 부르지 않는 규칙이다. 질문에서 뜻을 고르고, 관제 판의 값으로 합니다체 문장을 만든다.
 * 서버 계약 초안(POST /admin/v1/msp/ai/chat)의 응답 모양(답, 근거, 수집 시각, 모델, 상태)을 그대로 따른다.
 */

import { AI_ACTION_LABEL } from "./aiPolicy";
import { hostState, overallOf, thresholdOf } from "./opsHealth";
import { ago, durationWord, hm, lagWord, secWord } from "./timeText";
import type {
  AiChatEvidence,
  AiChatGuide,
  AiChatReply,
  AiDesk,
  AiProposal,
  ControlCenter,
  Incident,
  OpsAlert,
  OpsSection,
} from "./types";

// 계약 상수는 types.ts에 둔다. 상담 패널을 열기 전에 도는 코드(대화 저장소, sim 어댑터)가 이 규칙 파일을 끌어오지 않게 한다
export { CHAT_MAX_CHARS, CHAT_RECENT_TURNS } from "./types";
export const SIM_CHAT_MODEL = "시뮬레이터 규칙(모델 호출 없음)";

export const SUGGESTED_QUESTIONS = [
  "지금 상태를 요약해 주세요",
  "왜 조회 CPU가 높나요?",
  "최근 1시간 사건을 알려 주세요",
  "AI가 대기 중인 조치는 무엇인가요?",
  "ai-1 상태는 어떤가요?",
] as const;

export interface ChatLink {
  href: string;
  label: string;
}

/** 절차마다 그 절차가 있는 화면. 상담은 이 화면으로 보내기만 하고 실행하지 않는다 */
export const GUIDE_LINKS: Record<AiChatGuide, ChatLink[]> = {
  READ_NODES: [
    { href: "/admin", label: "관제센터 조치 카드" },
    { href: "/admin/capacity", label: "용량·부하" },
  ],
  THRESHOLD: [{ href: "/admin/thresholds", label: "임계값 관리" }],
  MODE: [
    { href: "/admin", label: "관제센터 모드 줄" },
    { href: "/admin/ai#ai-limits", label: "AI 한도" },
  ],
  SILENCE: [{ href: "/admin/incidents", label: "경보·사건" }],
  BACKUP: [{ href: "/admin/database", label: "데이터베이스" }],
  PROPOSAL: [
    { href: "/admin", label: "관제센터 조치 카드" },
    { href: "/admin/ai", label: "AI 판단" },
  ],
  FORBIDDEN: [{ href: "/admin/ai", label: "AI 판단 · 허용·금지 목록" }],
};

/** 근거가 가리키는 화면. 근거 id의 앞머리(alert:, metric:read_ 등)로 정한다 */
function screenOf(e: AiChatEvidence): ChatLink {
  const id = e.id;
  if (e.kind === "ALERT" || e.kind === "INCIDENT") return { href: "/admin/incidents", label: "경보·사건" };
  if (e.kind === "PROPOSAL") return { href: "/admin/ai", label: "AI 판단" };
  if (e.kind === "THRESHOLD") return { href: "/admin/thresholds", label: "임계값 관리" };
  if (id.startsWith("host:db-") || id.startsWith("metric:replication") || id.startsWith("metric:backup")) {
    return { href: "/admin/database", label: "데이터베이스" };
  }
  if (e.kind === "HOST" || id.startsWith("metric:read") || id.startsWith("metric:ai1")) return { href: "/admin/hosts", label: "서버·노드" };
  if (id.startsWith("metric:mode")) return { href: "/admin/ai", label: "AI 판단" };
  return { href: "/admin", label: "관제센터" };
}

/** 답 아래에 둘 화면 링크. 절차 화면이 앞이고, 같은 화면은 한 번만 둔다 */
export function linksOf(reply: Pick<AiChatReply, "evidence" | "guide">): ChatLink[] {
  const out = new Map<string, ChatLink>();
  for (const l of reply.guide ? GUIDE_LINKS[reply.guide] : []) out.set(l.href, l);
  for (const e of reply.evidence) {
    const l = screenOf(e);
    if (!out.has(l.href)) out.set(l.href, l);
  }
  return [...out.values()].slice(0, 4);
}

/* ===== 질문 읽기 ===== */

/** 실행을 부탁하는 동사. '요약해 주세요'처럼 알려 달라는 말은 여기에 없다 */
const EXEC_VERB = /(늘려|늘리|증설|줄여|줄이|축소|확장|스케일|올려|내려|바꿔|바꾸|변경|재시작|재기동|승격|삭제|지워|켜|꺼|끄|정지|멈춰|멈추|재개|보류|승인|거절|실행|적용|되돌려|되돌리|롤백)/;
/** 부탁하는 끝. 명령형 끝(해, 줘, 늘려, 멈춰 등)도 부탁으로 읽는다 */
const REQUEST_END = /(주세요|줘요|줘|줄래|주실래|해라|하세요|하시오|합시다|하자|부탁|시켜)/;
const IMPERATIVE_END = /(해|줘|봐|라|여|려|워|켜|꺼|춰)\s*[.!~]*$/;
/** 묻는 말. 이런 말이 있으면 실행 동사가 있어도 조회로 읽는다('왜 줄였나요') */
const ASK_WORD = /(왜|무엇|뭐|뭔|어떤|어떻|언제|얼마|몇|인가요|나요|까요|었어|였어|했어|됐어|습니까|알려|설명|요약|보여)/;

/** 실행 부탁이면 안내할 절차를, 아니면 null을 돌려준다. 금지 목록이 가장 앞이다 */
export function requestedGuide(question: string): AiChatGuide | null {
  const q = question.toLowerCase().replace(/\s+/g, " ").trim();
  if (!EXEC_VERB.test(q) || ASK_WORD.test(q)) return null;
  if (!REQUEST_END.test(q) && !IMPERATIVE_END.test(q)) return null;
  if (/(승격|페일오버|failover|재시작|재기동|리부트|reboot|삭제|지워|드롭|drop|보안 ?그룹|iam|리더 ?(전환|바꿔)|파티션)/.test(q)) return "FORBIDDEN";
  if (/(자동 ?모드|수동 ?모드|자동으로|수동으로|ai ?(정지|재개|멈춰|멈추|꺼|켜|끄))/.test(q)) return "MODE";
  if (q.includes("임계")) return "THRESHOLD";
  if (/(보류|사일런스|silence)/.test(q)) return "SILENCE";
  if (/(백업|databasus)/.test(q)) return "BACKUP";
  if (/(늘려|늘리|증설|줄여|줄이|축소|확장|스케일|노드|서버|app-?3|켜|꺼|끄)/.test(q)) return "READ_NODES";
  return "PROPOSAL";
}

type Intent = "SUMMARY" | "READ_CPU" | "INCIDENTS" | "PENDING" | "AI1" | "ALERTS" | "DB" | "BACKUP" | "THRESHOLDS" | "MODE" | "UNKNOWN";

export function intentOf(question: string): Intent {
  const q = question.toLowerCase().replace(/\s+/g, " ").trim();
  if (/(ai-?1|ai ?서버|dgx|추론|ollama|올라마|로컬 ?llm)/.test(q)) return "AI1";
  if (/(조회|read|cpu|씨피유)/.test(q) && /(왜|높|원인|이유|많|몰|부하)/.test(q)) return "READ_CPU";
  if (/(대기|조치안|제안|권장 ?조치|승인 ?대기|할 ?일)/.test(q)) return "PENDING";
  if (/(사건|인시던트|incident|장애 ?이력|무슨 ?일|최근)/.test(q)) return "INCIDENTS";
  if (/(경보|알람|alert)/.test(q)) return "ALERTS";
  if (/(백업|databasus|복원)/.test(q)) return "BACKUP";
  if (/(db|디비|데이터베이스|복제|primary|standby|프라이머리|스탠바이)/.test(q)) return "DB";
  if (q.includes("임계")) return "THRESHOLDS";
  if (/(모드|자동|수동|정지|한도)/.test(q)) return "MODE";
  if (/(요약|상태|현황|전체|괜찮|어때|정상|문제)/.test(q)) return "SUMMARY";
  if (/(조회|cpu)/.test(q)) return "READ_CPU";
  return "UNKNOWN";
}

/* ===== 근거 ===== */

const metric = (id: string, label: string, value: string): AiChatEvidence => ({ id: `metric:${id}`, kind: "METRIC", label, value, severity: null });
const alertEv = (a: OpsAlert): AiChatEvidence => ({ id: `alert:${a.id}`, kind: "ALERT", label: a.name, value: a.target, severity: a.severity });
const incidentEv = (i: Incident): AiChatEvidence => ({
  id: `incident:${i.id}`,
  kind: "INCIDENT",
  label: i.title,
  value: i.resolvedAt === null ? "진행 중" : `해제 ${hm(i.resolvedAt)}`,
  severity: i.resolvedAt === null ? i.severity : null,
});
const proposalEv = (p: AiProposal): AiChatEvidence => ({ id: `proposal:${p.id}`, kind: "PROPOSAL", label: p.title, value: `확신도 ${p.confidencePct}%`, severity: null });

function ready<T>(s: OpsSection<T>): T | null {
  return s.state === "READY" ? s.data : null;
}

const SEVERITY_WORD = { critical: "장애", warning: "주의", info: "정보" } as const;
const LEVEL_WORD = { ok: "정상", warn: "주의", down: "장애", none: "원천 연결 전", loading: "확인 중" } as const;
const MODE_WORD = { MANUAL: "수동 모드", AUTO: "자동 모드", STOPPED: "정지" } as const;

function openProposals(ai: AiDesk | null): AiProposal[] {
  return ai ? ai.proposals.filter((p) => p.status === "PROPOSED") : [];
}

/* ===== 뜻별 답 ===== */

interface Draft {
  answer: string;
  evidence: AiChatEvidence[];
  guide?: AiChatGuide;
}

function summary(c: ControlCenter): Draft {
  const o = overallOf(c);
  const s = ready(c.summary);
  const alerts = ready(c.alerts) ?? [];
  const tier = ready(c.readTier);
  const ai = ready(c.ai);
  const reg = ready(c.thresholds);
  const out: string[] = [`지금 전체 상태는 ${LEVEL_WORD[o.level]}입니다.`];
  const ev: AiChatEvidence[] = [];
  if (s) {
    const bad: string[] = [];
    // 목표가 없는 지표(서버에 SLO 설정 없음)는 견주지 않는다
    if (s.availabilityPct !== null && s.slo.availabilityPct !== null && s.availabilityPct < s.slo.availabilityPct) bad.push("외부 가용성");
    if (s.p95Ms !== null && s.slo.p95Ms !== null && s.p95Ms > s.slo.p95Ms) bad.push("응답 p95");
    if (s.errorRatePct !== null && s.slo.errorRatePct !== null && s.errorRatePct >= s.slo.errorRatePct) bad.push("5xx 비율");
    out.push(
      `외부 가용성 ${s.availabilityPct ?? "미수집"}%, 응답 p95 ${s.p95Ms ?? "미수집"}ms, 5xx ${s.errorRatePct ?? "미수집"}%${
        bad.length === 0 ? "로 세 목표 안에 있습니다." : `입니다. 목표를 벗어난 지표는 ${bad.join("·")}입니다.`
      }`,
    );
    ev.push(metric("availability", "외부 가용성", `${s.availabilityPct}%`), metric("p95", "응답 p95", `${s.p95Ms}ms`), metric("errors", "5xx 비율", `${s.errorRatePct}%`));
  }
  if (alerts.length === 0) out.push("활성 경보는 없습니다.");
  else {
    const order = { critical: 0, warning: 1, info: 2 } as const;
    const top = [...alerts].sort((a, b) => order[a.severity] - order[b.severity])[0];
    out.push(`활성 경보는 ${alerts.length}건이고 가장 무거운 것은 ${SEVERITY_WORD[top.severity]} 등급 '${top.name}'(${top.detail})입니다.`);
    ev.push(alertEv(top));
  }
  if (tier) {
    const moving = tier.nodes.filter((n) => n.state === "PENDING" || n.state === "DRAINING");
    const outTh = thresholdOf(reg, "read.scale_out_cpu");
    out.push(
      `조회는 앱 ${tier.serving}대가 평균 CPU ${tier.avgCpuPct ?? "미수집"}%로 받고 있습니다${outTh !== null ? `(증설 임계 ${outTh}%)` : ""}.` +
        (moving.length > 0 ? ` ${moving.map((n) => `${n.id}는 ${n.state === "PENDING" ? "기동 중" : "드레이닝 중"}`).join(", ")}입니다.` : ""),
    );
    ev.push(metric("read_cpu", "앱 평균 CPU", `${tier.avgCpuPct ?? "미수집"}% · ${tier.serving}대`));
  }
  if (ai) {
    const open = openProposals(ai);
    out.push(ai.mode === "STOPPED" ? "AI는 정지 중이라 새 판단을 내지 않습니다." : `AI는 ${MODE_WORD[ai.mode]}이고 대기 중인 조치안은 ${open.length}건입니다.`);
    if (open[0]) ev.push(proposalEv(open[0]));
  }
  return { answer: out.join(" "), evidence: ev.slice(0, 5) };
}

function readCpu(c: ControlCenter): Draft {
  const tier = ready(c.readTier);
  if (!tier) return { answer: "앱 계층 원천이 연결 전이라 조회 부하를 판단할 근거가 없습니다.", evidence: [] };
  const reg = ready(c.thresholds);
  const outTh = thresholdOf(reg, "read.scale_out_cpu");
  const inTh = thresholdOf(reg, "read.scale_in_cpu");
  const avg = tier.avgCpuPct;
  // 조회는 늘 켜진 app-1·app-2와 켜져 있으면 app-3가 나눠 받는다(ADR 0030, 사용자 정정 2026-09-25)
  const hosts = ready(c.hosts) ?? [];
  const serving = [
    ...hosts.filter((h) => h.role === "APP").map((h) => ({ id: h.id, cpuPct: h.cpuPct })),
    ...tier.nodes.filter((n) => n.state === "IN_SERVICE").map((n) => ({ id: n.id, cpuPct: n.cpuPct })),
  ];
  const ai = ready(c.ai);
  const readAlert = (ready(c.alerts) ?? []).find((a) => a.ref?.kind === "READ_TIER") ?? null;
  const nodeText = serving.map((n) => `${n.id} ${n.cpuPct ?? "미수집"}%`).join(", ");
  const ev: AiChatEvidence[] = [metric("read_cpu", "앱 평균 CPU", `${avg ?? "미수집"}% · ${serving.length}대`)];
  if (outTh !== null) ev.push({ id: "threshold:read.scale_out_cpu", kind: "THRESHOLD", label: "증설 CPU 임계", value: `${outTh}%`, severity: null });
  if (avg === null) return { answer: "조회를 받는 앱 노드의 CPU를 읽지 못했습니다. 미수집은 0이 아니라 값이 없는 상태입니다.", evidence: ev };
  const hot = outTh !== null && avg >= outTh;
  const parts: string[] = [];
  if (hot) {
    parts.push(`앱 평균 CPU가 ${avg}%로 증설 임계 ${outTh}%를 넘었습니다.`);
    parts.push(`조회를 받는 앱 노드가 ${serving.length}대(${nodeText})라 한 대가 맡는 몫이 큽니다.`);
    if (ai?.hypothesis) parts.push(`AI의 원인 가설은 '${ai.hypothesis}'입니다.`);
    const scale = openProposals(ai).find((p) => p.kind === "READ_NODES");
    if (scale) {
      parts.push(`AI가 '${scale.title}' 조치안을 승인 대기로 두었습니다(확신도 ${scale.confidencePct}%).`);
      ev.push(proposalEv(scale));
    }
    if (readAlert) ev.push(alertEv(readAlert));
  } else {
    parts.push(`앱 평균 CPU는 ${avg}%로 증설 임계 ${outTh ?? "미설정"}%보다 낮아 높은 상태가 아닙니다.`);
    parts.push(`노드별로는 ${nodeText}입니다.`);
    if (inTh !== null && avg <= inTh && tier.nodes.some((n) => n.state === "IN_SERVICE")) parts.push(`축소 임계 ${inTh}% 아래라 app-3를 끌 여유가 있습니다.`);
  }
  // 앱 평균에는 DB가 들지 않는다. DB가 원인이라고 오해하지 않게 함께 적는다
  const primary = hosts.find((h) => h.role === "DB_PRIMARY");
  if (primary) parts.push(`앱 평균에는 DB가 들지 않습니다(${primary.id} ${primary.cpuPct ?? "미수집"}%).`);
  return { answer: parts.join(" "), evidence: ev.slice(0, 5) };
}

function incidents(c: ControlCenter, now: number): Draft {
  const log = ready(c.incidents);
  if (!log) return { answer: "사건 기록 원천이 연결 전이라 최근 사건을 알 수 없습니다.", evidence: [] };
  const from = now - 3_600_000;
  const recent = log.incidents.filter((i) => i.resolvedAt === null || Date.parse(i.openedAt) >= from || Date.parse(i.resolvedAt) >= from);
  if (recent.length === 0) {
    const last = log.incidents[0] ?? null;
    if (!last) return { answer: "최근 1시간에 열린 사건은 없고 남아 있는 사건 기록도 없습니다.", evidence: [] };
    const took = last.resolvedAt === null ? null : durationWord(Date.parse(last.resolvedAt) - Date.parse(last.openedAt));
    return {
      answer: `최근 1시간에 열린 사건은 없습니다. 가장 최근 사건은 '${last.title}'로 ${ago(last.openedAt, now)} 열려 ${took ? `${took} 만에 해제되었습니다` : "아직 진행 중입니다"}.`,
      evidence: [incidentEv(last)],
    };
  }
  const items = recent.slice(0, 3).map((i) => {
    const state = i.resolvedAt === null
      ? `진행 중 · ${durationWord(now - Date.parse(i.openedAt))}째`
      : `${durationWord(Date.parse(i.resolvedAt) - Date.parse(i.openedAt))} 만에 해제`;
    return `'${i.title}'(${hm(i.openedAt)} 발생, ${state})`;
  });
  const first = recent[0];
  const steps = log.events.filter((e) => e.incidentId === first.id);
  const ai = steps.filter((e) => e.actor === "AI").length;
  const op = steps.filter((e) => e.actor === "OPERATOR").length;
  return {
    answer:
      `최근 1시간 사건은 ${recent.length}건입니다. ${items.join(", ")}입니다.` +
      ` 가장 최근 사건에는 기록이 ${steps.length}줄 있고 AI 기록 ${ai}줄, 운영자 기록 ${op}줄입니다.`,
    evidence: recent.slice(0, 3).map(incidentEv),
  };
}

function pending(c: ControlCenter): Draft {
  const ai = ready(c.ai);
  if (!ai) return { answer: "AI 판단 원천이 연결 전이라 대기 중인 조치를 알 수 없습니다.", evidence: [] };
  if (ai.mode === "STOPPED") {
    const expired = ai.proposals.filter((p) => p.status === "EXPIRED").length;
    return { answer: `AI가 정지 중이라 새 조치안을 내지 않습니다.${expired > 0 ? ` 정지로 만료된 조치안이 ${expired}건 있고 승인할 수 없습니다.` : ""}`, evidence: [], guide: "MODE" };
  }
  const open = openProposals(ai);
  if (open.length === 0) {
    return { answer: "대기 중인 조치안은 없습니다. AI는 허용 목록 안의 조치가 필요할 때만 조치안을 냅니다.", evidence: [metric("mode", "AI 운영 모드", MODE_WORD[ai.mode])] };
  }
  const lines = open.slice(0, 3).map((p, i) => `${i + 1}번은 '${p.title}'(${p.target}, 확신도 ${p.confidencePct}%, 위험 ${p.risk === "LOW" ? "낮음" : "중간"})`);
  const hold = ai.mode === "AUTO" && open[0].autoHold ? ` 자동 모드지만 1번은 '${open[0].autoHold}' 조건으로 기다립니다.` : "";
  return {
    answer: `대기 중인 조치안은 ${open.length}건입니다. ${lines.join(", ")}입니다.${hold} 실행은 관제센터 조치 카드에서 운영자가 승인해야 합니다.`,
    evidence: open.slice(0, 3).map(proposalEv),
    guide: "PROPOSAL",
  };
}

function aiServer(c: ControlCenter): Draft {
  const s = c.aiServer;
  if (s.state === "NOT_CONNECTED") return { answer: `ai-1 상태 원천이 연결 전입니다(${s.source}).`, evidence: [] };
  if (s.state === "UNAVAILABLE") return { answer: `ai-1 상태를 불러오지 못했습니다(${s.reason}).`, evidence: [] };
  const a = s.data;
  const lat = (ms: number | null) => (ms === null ? "요청 없음" : secWord(ms));
  const state = a.link === "DOWN" ? "관제 서버와 연결이 끊겼습니다" : a.busy ? "지금 판단 요청을 처리하고 있습니다" : "대기 중입니다";
  // 구축 전이라 연결은 가정이다. 가정이라는 사실을 먼저 말하고 가정한 상태와 값을 뒤에 적는다(관리자 설계 4-4)
  const parts = a.assumed
    ? [
        `ai-1(${a.hardware} · ${a.runtime})은 구축 전이고 연결 경로도 정하지 않았습니다(ADR 0038).`,
        `관제 시뮬레이터가 연결을 가정해 만든 값으로는 ${state}.`,
      ]
    : [`ai-1(${a.hardware} · ${a.runtime})은 ${state}.`];
  parts.push(
    `GPU ${a.gpuPct ?? "미수집"}%, 통합 메모리 ${a.memUsedGb === null ? "미수집" : a.memUsedGb.toFixed(1)}/${a.memTotalGb}GB, 추론 지연 p50 ${lat(a.p50Ms)} · p95 ${lat(a.p95Ms)}, 대기 요청 ${a.queue ?? "미수집"}건입니다.`,
    `적재 모델은 평가로 정하는 중이라 '${a.model}'으로 적습니다.`,
  );
  return {
    answer: parts.join(" "),
    evidence: [
      metric("ai1_gpu", "ai-1 GPU", `${a.gpuPct ?? "미수집"}%`),
      metric("ai1_latency", "추론 지연 p95", lat(a.p95Ms)),
      metric("ai1_queue", "대기 요청", `${a.queue ?? "미수집"}건`),
    ],
  };
}

function alertsNow(c: ControlCenter, now: number): Draft {
  const alerts = ready(c.alerts);
  if (!alerts) return { answer: "경보 원천이 연결 전이라 활성 경보를 알 수 없습니다.", evidence: [] };
  if (alerts.length === 0) return { answer: "활성 경보는 없습니다.", evidence: [] };
  const items = alerts.slice(0, 3).map((a) => `${SEVERITY_WORD[a.severity]} '${a.name}'(${a.detail}, ${durationWord(now - Date.parse(a.since))}째${a.state === "ACKED" ? `, ${a.ackBy} 확인` : a.state === "SILENCED" ? ", 보류 중" : ""})`);
  return { answer: `활성 경보는 ${alerts.length}건입니다. ${items.join(", ")}입니다. 확인은 기록이고 경보를 끄지 않습니다.`, evidence: alerts.slice(0, 3).map(alertEv) };
}

function db(c: ControlCenter): Draft {
  const r = ready(c.replication);
  if (!r) return { answer: "DB 복제 원천이 연결 전이라 복제 상태를 알 수 없습니다.", evidence: [] };
  const lagTh = thresholdOf(ready(c.thresholds), "db.replication_lag_alert");
  const stream = { STREAMING: "스트리밍 중", CATCHUP: "따라잡는 중", STOPPED: "끊겼습니다" }[r.stream];
  const hosts = ready(c.hosts) ?? [];
  const reg = ready(c.thresholds);
  const dbHosts = hosts.filter((h) => h.role === "DB_PRIMARY" || h.role === "DB_STANDBY");
  const states = dbHosts.map((h) => `${h.id} ${hostState(h, reg).word}`).join(", ");
  return {
    answer:
      `${r.primary}에서 ${r.standby}로 비동기 복제가 ${stream}${r.stream === "STOPPED" ? "" : "입니다"}. 지연은 ${r.lagSec === null ? "미수집" : lagWord(r.lagSec)}(${r.lagBytes === null ? "미수집" : `${Math.round(r.lagBytes / 1024)}KB`})이고 경보 기준은 ${lagTh ?? "미설정"}초입니다.` +
      ` ${states ? `${states}입니다. ` : ""}DB 승격과 복제 재구성은 운영자가 하며 AI 금지 목록입니다.`,
    evidence: [metric("replication_lag", "복제 지연", r.lagSec === null ? "미수집" : lagWord(r.lagSec)), ...dbHosts.map((h) => ({ id: `host:${h.id}`, kind: "HOST" as const, label: h.id, value: `CPU ${h.cpuPct ?? "미수집"}%`, severity: null }))],
  };
}

function backup(c: ControlCenter, now: number): Draft {
  const jobs = ready(c.jobs);
  if (!jobs) return { answer: "Databasus 원천이 연결 전이라 백업 상태를 알 수 없습니다.", evidence: [] };
  const b = jobs.backup;
  if (!b) return { answer: `백업 원천이 연결 전이라 백업 상태를 알 수 없습니다(${jobs.backupSource ?? "Databasus"}).`, evidence: [] };
  const outcome = (o: string) => (o === "SUCCESS" ? "성공" : o === "FAILED" ? "실패" : "기록 없음");
  return {
    answer:
      `마지막 백업 성공은 ${b.lastSuccessAt ? `${hm(b.lastSuccessAt)}(${ago(b.lastSuccessAt, now)})` : "기록 없음"}입니다. 외부 보관은 ${b.offsite ? `(${b.offsite.target}) ${outcome(b.offsite.outcome)}` : "원천 연결 전"}, 복원 검증은 ${outcome(b.restoreCheck.outcome)}입니다.` +
      `${b.running ? " 지금 백업이 실행 중입니다." : ""} 다음 예약은 ${b.nextAt ? hm(b.nextAt) : "없음"}입니다.`,
    evidence: [metric("backup_last", "마지막 백업", b.lastSuccessAt ? ago(b.lastSuccessAt, now) : "기록 없음"), metric("backup_restore", "복원 검증", outcome(b.restoreCheck.outcome))],
  };
}

function thresholds(c: ControlCenter): Draft {
  const reg = ready(c.thresholds);
  if (!reg) return { answer: "임계값 레지스트리 원천이 연결 전입니다.", evidence: [] };
  const suggested = reg.items.filter((t) => t.aiSuggested !== null);
  const last = reg.history[0] ?? null;
  return {
    answer:
      `임계값은 ${reg.items.length}개 항목입니다. ${suggested.length === 0 ? "AI 제안값이 있는 항목은 없습니다." : `AI 제안값이 있는 항목은 ${suggested.map((t) => `${t.label}(${t.value} → ${t.aiSuggested}${t.unit})`).join(", ")}입니다.`}` +
      ` ${last ? `마지막 변경은 ${hm(last.at)} ${last.by}의 ${last.label} ${last.from} → ${last.to}${last.unit}입니다.` : "초기값에서 바뀐 항목은 없습니다."}`,
    evidence: (suggested.length > 0 ? suggested : reg.items.slice(0, 2)).slice(0, 3).map((t) => ({
      id: `threshold:${t.key}`,
      kind: "THRESHOLD" as const,
      label: t.label,
      value: `${t.value}${t.unit}`,
      severity: null,
    })),
  };
}

function mode(c: ControlCenter, now: number): Draft {
  const ai = ready(c.ai);
  if (!ai) return { answer: "서버 정책 엔진이 연결 전이라 AI 모드를 알 수 없습니다.", evidence: [] };
  let text: string;
  if (ai.mode === "STOPPED") {
    text = `${ai.stopped ? `${hm(ai.stopped.at)}에 운영자(${ai.stopped.by})가 AI를 정지했습니다.` : "AI가 정지 중입니다."} 새 판단과 조치안을 내지 않고, 재개는 사유를 적어 수동으로만 합니다.`;
  }
  else if (ai.mode === "AUTO" && ai.auto) {
    text = `AI는 자동 모드이고 ${durationWord(Date.parse(ai.auto.until) - now)} 남았습니다. 이번 자동 모드에서 ${ai.autoUsed}/${ai.autoCap}회 실행했고, 허용 목록 조치만 한도 안에서 합니다.`;
  } else text = "AI는 수동 모드입니다. 판단하고 조치안을 내지만 실행은 운영자 승인입니다.";
  const cools = ai.cooldowns.filter((x) => Date.parse(x.until) > now).map((x) => `${AI_ACTION_LABEL[x.kind]} ${durationWord(Date.parse(x.until) - now)}`);
  if (cools.length > 0) text += ` 쿨다운은 ${cools.join(", ")} 남았습니다.`;
  return { answer: text, evidence: [metric("mode", "AI 운영 모드", MODE_WORD[ai.mode])] };
}

const GUIDE_TEXT: Record<AiChatGuide, string> = {
  READ_NODES: "앱 계층 대수(조회 전용 app-3 켜기·끄기)는 관제센터 조치 카드에서 운영자가 '승인하고 실행'을 누르거나, 용량·부하 화면의 확인 대화상자를 거쳐 바꿉니다. AWS에서는 지표 정책이 app-3를 켜고 끕니다.",
  THRESHOLD: "임계값은 임계값 관리에서 새 값을 적고 1인 승인(ops.threshold.set)을 발급·실행해야 바뀝니다. 되돌리기도 같은 승인을 거칩니다.",
  MODE: "AI 모드는 관제센터 모드 줄에서 운영자가 바꿉니다. 자동 모드는 사유와 유효 시간을 적고 1인 승인(ops.ai.auto)을 거쳐야 켜지고, 수동 전환은 승인 없이 됩니다. AI 즉시 정지와 재개는 운영 관제 모든 화면의 운영자 바에 있고, 정지는 승인 없이 누구나 누를 수 있습니다.",
  SILENCE: "경보 보류는 경보·사건 화면에서 운영자가 겁니다. 최대 30분이고 장애 등급과 같은 경보의 연속 보류는 막힙니다. AI는 변경·작업 화면의 계획 작업 창 안에서 주의 경보만 보류할 수 있습니다.",
  BACKUP: "Databasus 즉시 백업은 데이터베이스 화면이나 관제센터 운영 작업의 [지금 백업]에서 운영자가 누릅니다.",
  PROPOSAL: "조치안의 승인과 거절은 관제센터 조치 카드에서 운영자가 확인 대화상자를 거쳐 합니다. 상담은 승인을 대신하지 않습니다.",
  FORBIDDEN: "그 일은 금지 목록이라 AI는 어느 모드에서도 하지 않습니다. DB 승격은 운영자가 DB 수동 페일오버 절차로 하고, 재시작과 삭제도 운영자가 절차 문서대로 직접 합니다.",
};

function declined(guide: AiChatGuide, c: ControlCenter): Draft {
  const ai = ready(c.ai);
  const open = openProposals(ai);
  const related = guide === "READ_NODES" ? open.filter((p) => p.kind === "READ_NODES") : guide === "THRESHOLD" ? open.filter((p) => p.kind === "THRESHOLD") : guide === "PROPOSAL" ? open : [];
  const waiting = related[0] ? ` 지금 '${related[0].title}' 조치안이 승인을 기다립니다.` : "";
  return { answer: `상담은 조회만 하고 조치를 실행하지 않습니다. ${GUIDE_TEXT[guide]}${waiting}`, evidence: related.slice(0, 2).map(proposalEv), guide };
}

/** 관제 판으로 질문에 답한다(sim). 모델을 부르지 않는다. AI가 정지 중이면 답하지 않고 그 상태만 돌려준다 */
export function answerFromCenter(question: string, c: ControlCenter, now: number): AiChatReply {
  const base = { collectedAt: c.collectedAt, model: SIM_CHAT_MODEL, note: null };
  const ai = ready(c.ai);
  if (ai?.mode === "STOPPED") return { ...base, state: "STOPPED", answer: "", evidence: [], declined: false, guide: null };
  const guide = requestedGuide(question);
  if (guide) {
    const d = declined(guide, c);
    return { ...base, state: "OK", answer: d.answer, evidence: d.evidence, declined: true, guide: d.guide ?? null };
  }
  const intent = intentOf(question);
  const d: Draft =
    intent === "SUMMARY" ? summary(c)
      : intent === "READ_CPU" ? readCpu(c)
        : intent === "INCIDENTS" ? incidents(c, now)
          : intent === "PENDING" ? pending(c)
            : intent === "AI1" ? aiServer(c)
              : intent === "ALERTS" ? alertsNow(c, now)
                : intent === "DB" ? db(c)
                  : intent === "BACKUP" ? backup(c, now)
                    : intent === "THRESHOLDS" ? thresholds(c)
                      : intent === "MODE" ? mode(c, now)
                        : {
                          answer: "그 질문에는 시뮬레이터 규칙으로 답할 근거를 찾지 못했습니다. 지금 상태, 조회 CPU, 최근 사건, 대기 조치, ai-1, 경보, DB 복제, 백업, 임계값, AI 모드를 물어볼 수 있습니다.",
                          evidence: [],
                        };
  return { ...base, state: "OK", answer: d.answer, evidence: d.evidence, declined: false, guide: d.guide ?? null };
}
