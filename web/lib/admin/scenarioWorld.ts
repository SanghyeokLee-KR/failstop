/**
 * 시나리오 재생 세계(관리자 설계 4-10-6·4-10-9). 시나리오마다 새 관제 시뮬레이터를 만들어 가상 시계로 밟는다. 앞으로 가면 이어서
 * 밟고, 되감기와 스크럽은 처음부터 그 시각까지 다시 밟아 만든다. 시뮬레이터는 같은 시각이면 같은 값을 내므로(틱에서 정해지는
 * 흔들림) 같은 시각은 늘 같은 판이다. 관제센터와 다른 화면의 값은 이 재생으로 바뀌지 않는다.
 *
 * 시나리오를 고르면 두 모드(수동, 자동)를 끝까지 한 번씩 돌려 타임라인을 만든다. 스크럽 바의 기록 표지, 단계가 서는 시각, 비교
 * 보기의 결과 표가 그 타임라인에서 나온다. 한 시각의 네 칸(관제 상태, AI 제안, 정책 엔진 판정, 운영자 조치와 Teams 카드)은 같은
 * 판에서 나온다.
 */

import { OpsSim } from "./opsSim";
import { REPLAY_OPERATOR } from "./scenarios";
import type { AiJudgmentTrace, ControlCenter, IncidentEvent, ScenarioCue, ScenarioSpec, ScenarioStage, WriteState } from "./types";

export type ReplayMode = "MANUAL" | "AUTO";

/** 재생의 자동 모드 유효 시간. 가장 긴 재생(DB Primary 장애 11분 30초)보다 길다 */
const AUTO_HOURS = 8;

/** 대본 한 줄을 세계에 건다. 콘솔 결정은 시뮬레이터의 조작 경로를, 런북과 장애는 재생 경로를 탄다 */
function applyCue(sim: OpsSim, cue: ScenarioCue, t: number): void {
  switch (cue.kind) {
    case "APPROVE_FIRST": {
      const id = sim.firstProposal();
      // 임계값 조치안은 승인 티켓이 있어야 실행된다. 재생은 발급·소각을 흉내 낸 티켓 id를 싣는다
      if (id !== null) sim.decide(id, "APPROVE", REPLAY_OPERATOR, t, "apr-replay");
      return;
    }
    case "ACK_ALERT": {
      const id = sim.firstUnackedAlert(cue.alert);
      if (id !== null) sim.ackAlert(id, REPLAY_OPERATOR, t);
      return;
    }
    default:
      sim.replayOp(cue.op ?? "NOTE", cue.note, REPLAY_OPERATOR, t);
  }
}

export class ReplayWorld {
  private sim: OpsSim;
  private sec = 0;

  constructor(readonly spec: ScenarioSpec, readonly mode: ReplayMode, readonly t0: number) {
    this.sim = this.fresh();
  }

  private fresh(): OpsSim {
    // id는 이 세계 안의 번호다. 되감아 다시 만든 세계도 같은 시각이면 같은 id라 목록이 새 줄로 오인하지 않는다
    const sim = new OpsSim(this.t0, undefined, { localIds: true });
    if (this.mode === "AUTO") sim.enableAuto(AUTO_HOURS, "시나리오 재생 · 자동 모드", REPLAY_OPERATOR, "apr-replay-auto", this.t0);
    sim.startReplay(this.spec.id, this.spec.title, this.t0);
    for (const cue of this.cuesAt(0)) applyCue(sim, cue, this.t0);
    return sim;
  }

  private cuesAt(sec: number): ScenarioCue[] {
    return this.spec.cues.filter((c) => c.atSec === sec && (c.mode === "ANY" || c.mode === this.mode));
  }

  get at(): number {
    return this.sec;
  }

  /** 그 초로 옮긴다. 앞이면 이어서 밟고, 뒤면 처음부터 다시 밟는다 */
  seek(sec: number): void {
    const target = Math.max(0, Math.min(this.spec.durationSec, Math.round(sec)));
    if (target < this.sec) {
      this.sim = this.fresh();
      this.sec = 0;
    }
    while (this.sec < target) {
      this.sec += 1;
      const t = this.t0 + this.sec * 1000;
      this.sim.advance(t);
      for (const cue of this.cuesAt(this.sec)) applyCue(this.sim, cue, t);
    }
  }

  /** 지금 초의 한 판 */
  board(): ControlCenter {
    return this.sim.snapshot(this.t0 + this.sec * 1000);
  }

  readings(): ReplaySample {
    return this.sim.readings();
  }

  /** 재생 시작 뒤의 기록(오래된 것이 앞) */
  events(): IncidentEvent[] {
    return this.sim.eventsSince(this.t0);
  }

  /** 주입 문장을 따른 조치안이 나왔다면의 검사표. 주입 경보가 없으면 null */
  hypothetical(): Pick<AiJudgmentTrace, "candidates" | "checks"> | null {
    return this.sim.hypotheticalChecks(this.t0 + this.sec * 1000);
  }
}

export type ReplaySample = ReturnType<OpsSim["readings"]>;

/** 스크럽 바의 기록 표지. 사건 기록 레일의 문법이다(경보 발생, AI, 운영자, 시스템, 해제) */
export type MarkerKind = "OPEN" | "AI" | "OPERATOR" | "SYSTEM" | "RESOLVE";

export interface ReplayMarker {
  sec: number;
  kind: MarkerKind;
  text: string;
}

export interface ReplayResult {
  /** 첫 사건이 선 초와 해제된 초. 없거나 재생 끝까지 해제되지 않으면 null */
  openedSec: number | null;
  resolvedSec: number | null;
  /** 운영자의 결정과 조치(확인, 승인, 거절, 나중에, 되돌리기, 런북 단계) */
  decisions: number;
  autoRuns: number;
  reverts: number;
  p95Max: number;
  /** 마지막 앱 계층 희망 대수 */
  lastTier: number;
  /** Teams 통지(단계별 호출은 뺌). 가장 높은 개입 등급과 등급이 붙은 통지 수 */
  notices: { count: number; top: string | null; graded: number };
  /** 쓰기가 열려 있지 않았던 초 */
  writesDownSec: number;
}

export interface ReplayTimeline {
  mode: ReplayMode;
  durationSec: number;
  /** 초마다의 판독(0초부터 끝까지) */
  samples: ReplaySample[];
  /** 재생 시작 뒤의 기록과 그 초. 오래된 것이 앞이다 */
  events: { sec: number; e: IncidentEvent }[];
  /** 단계마다 선 초. 서지 않은 단계는 null이다 */
  stageSecs: (number | null)[];
  markers: ReplayMarker[];
  result: ReplayResult;
}

function matches(e: IncidentEvent, on: Extract<ScenarioStage["on"], { kind: "EVENT" }>): boolean {
  return on.events.includes(e.kind) && (on.actor === undefined || on.actor === e.actor) && (on.prefix === undefined || e.text.startsWith(on.prefix));
}

/** 단계가 서는 초. 단계마다 앞 단계가 선 기록 뒤의 첫 기록을 찾는다 */
export function stageSecs(spec: ScenarioSpec, events: { sec: number; e: IncidentEvent }[]): (number | null)[] {
  let from = 0;
  return spec.stages.map((st) => {
    if (st.on.kind === "START") return 0;
    const on = st.on;
    const idx = events.findIndex((x, i) => i >= from && matches(x.e, on));
    if (idx < 0) return null;
    from = idx + 1;
    return events[idx].sec;
  });
}

/** 지금 단계. 이 초까지 선 단계 가운데 마지막이다 */
export function stageAt(secs: (number | null)[], sec: number): number {
  let cur = 0;
  secs.forEach((s, i) => {
    if (s !== null && s <= sec) cur = i;
  });
  return cur;
}

const RANK: Record<MarkerKind, number> = { OPEN: 0, RESOLVE: 1, OPERATOR: 2, AI: 3, SYSTEM: 4 };

function markerOf(e: IncidentEvent): MarkerKind | null {
  if (e.kind === "OPENED") return "OPEN";
  if (e.kind === "RESOLVED") return e.text.startsWith("해제 ·") ? "RESOLVE" : null;
  if (e.kind === "MODE") return null;
  if (e.actor === "OPERATOR") return "OPERATOR";
  if (e.actor === "AI") return "AI";
  return "SYSTEM";
}

/** 같은 초의 표지는 하나로 모은다. 경보 발생, 해제, 운영자, AI, 시스템 순서로 앞선다 */
export function markersOf(events: { sec: number; e: IncidentEvent }[]): ReplayMarker[] {
  const bySec = new Map<number, ReplayMarker>();
  for (const { sec, e } of events) {
    const kind = markerOf(e);
    if (kind === null) continue;
    const cur = bySec.get(sec);
    if (!cur || RANK[kind] < RANK[cur.kind]) bySec.set(sec, { sec, kind, text: e.text });
  }
  return [...bySec.values()].sort((a, b) => a.sec - b.sec);
}

/** Teams 통지의 등급(P1~P4). 등급이 없는 통지(경보 카드)는 null */
export function priorityOf(text: string): string | null {
  return /\bP([1-4])\b/.exec(text)?.[0] ?? null;
}

const DECISION_KINDS = new Set<IncidentEvent["kind"]>(["ACKED", "APPROVED", "REJECTED", "DEFERRED", "REVERTED", "ACTED"]);

export function resultOf(events: { sec: number; e: IncidentEvent }[], samples: ReplaySample[]): ReplayResult {
  const opened = events.find((x) => x.e.kind === "OPENED") ?? null;
  const resolved = opened === null ? null : events.find((x) => x.sec >= opened.sec && x.e.kind === "RESOLVED" && x.e.text.startsWith("해제 ·")) ?? null;
  const notices = events.filter((x) => x.e.kind === "NOTIFIED" && !x.e.text.startsWith("호출 "));
  const grades = notices.map((x) => priorityOf(x.e.text)).filter((p): p is string => p !== null).sort();
  return {
    openedSec: opened?.sec ?? null,
    resolvedSec: resolved?.sec ?? null,
    decisions: events.filter((x) => x.e.actor === "OPERATOR" && DECISION_KINDS.has(x.e.kind)).length,
    autoRuns: events.filter((x) => x.e.kind === "AUTO_ACTED").length,
    reverts: events.filter((x) => x.e.kind === "AUTO_REVERTED" || x.e.kind === "REVERTED").length,
    p95Max: samples.reduce((a, s) => Math.max(a, s.p95), 0),
    lastTier: samples[samples.length - 1]?.desired ?? 0,
    notices: { count: notices.length, top: grades[0] ?? null, graded: grades.length },
    writesDownSec: samples.filter((s) => s.writes !== ("OPEN" satisfies WriteState)).length,
  };
}

/** 한 모드를 끝까지 한 번 돌려 타임라인을 만든다 */
export function runTimeline(spec: ScenarioSpec, mode: ReplayMode, t0: number): ReplayTimeline {
  const world = new ReplayWorld(spec, mode, t0);
  const samples: ReplaySample[] = [world.readings()];
  for (let s = 1; s <= spec.durationSec; s += 1) {
    world.seek(s);
    samples.push(world.readings());
  }
  const events = world.events().map((e) => ({ sec: Math.max(0, Math.round((Date.parse(e.at) - t0) / 1000)), e }));
  return { mode, durationSec: spec.durationSec, samples, events, stageSecs: stageSecs(spec, events), markers: markersOf(events), result: resultOf(events, samples) };
}

/** 재생 세계의 시작 시각. 분 경계로 내려 같은 세션에서 두 모드와 되감기가 같은 세계를 만든다 */
export function replayStart(now: number): number {
  return Math.floor(now / 60_000) * 60_000;
}

/** Teams 통지 한 줄의 모양(Teams 카드 미리보기). 보고 서비스의 사건 보고, AI 통지, Alertmanager 경보 카드, 단계별 호출이다 */
export interface TeamsNotice {
  kind: "REPORT" | "AI" | "ALERT" | "PAGE";
  priority: string | null;
  label: string;
  title: string;
  target: string | null;
}

export function teamsNoticeOf(text: string): TeamsNotice {
  const parts = text.split(" · ");
  const pri = priorityOf(text);
  if (text.startsWith("Teams 사건 보고")) {
    return { kind: "REPORT", priority: pri, label: parts[1] ?? "", title: parts.slice(2).join(" · "), target: null };
  }
  if (text.startsWith("Teams 알림")) {
    return { kind: "AI", priority: pri, label: "AI 운영 통지", title: parts.slice(1).join(" · "), target: null };
  }
  if (text.startsWith("Teams 경보 알림")) {
    return { kind: "ALERT", priority: null, label: "Alertmanager 경보 카드", title: parts[1] ?? text, target: parts[2] ?? null };
  }
  return { kind: "PAGE", priority: null, label: "단계별 호출", title: parts.slice(0, 2).join(" · "), target: parts[2] ?? null };
}
