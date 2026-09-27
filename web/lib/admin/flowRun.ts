"use client";

/**
 * 서비스 흐름과 지표 관제가 함께 보는 주행. 두 화면은 같은 값을 봐야 한다. 서비스 흐름에서 DB 전환을 걸고
 * 지표 관제로 넘어가면 그 전환이 차트에 이어져 있어야 한다. 그래서 주행을 화면 상태가 아니라 모듈 수준
 * 스토어에 둔다. 관리 셸 안에서 화면을 옮겨도 모듈은 그대로라 주행이 끊기지 않는다. 새로 고치면 처음부터다.
 *
 * 주행에는 세 모드가 있다(관리자 설계 4-2). 관제 값 모드(live)는 관제 시뮬레이터의 한 판(opsCenter)이 올 때마다
 * 그 판을 흐름 상태로 옮겨 관제센터와 같은 값을 그린다. 재생 모드(replay)는 로컬 Docker 측정 기록을 재생하는
 * 다른 세계라 화면이 '재생 · 관제 값과 별개'를 크게 적는다. 노드를 눌러 재생을 걸면 재생 모드가 되고,
 * 초기화하면 관제 값 모드로 돌아온다. 실측 모드(measured)는 http 원천의 유일한 모드다. 관리 API 흐름 조회가 Prometheus
 * 에서 읽은 최근 150초를 2초마다 받아 그대로 그리고 재생을 걸 수 없다(2026-09-27 사용자 결정).
 *
 * 재생 시계는 벽시계를 따른다. 몇 틱이 밀렸는지를 벽시계로 세고 그만큼 밟는다. 배경 탭은 타이머를
 * 늦추고, 두 화면 밖에 있는 동안은 타이머가 아예 없으므로 콜백마다 한 틱씩 나아가면 화면의
 * 시각(origin + 틱)이 실제 시각보다 뒤처진다.
 */

import { useEffect } from "react";
import { create } from "zustand";
import { AdminError, getAdminApi, isAdminHttp } from "./api";
import { onBeat } from "./ticker";
import { backoffMs, opsCenterStore } from "./opsCenter";
import { MEASURED_POLL_MS, emptyBoard, measuredFlow, pendingBoard } from "./flowMeasured";
import {
  LEASE_TICK,
  LEASE_TTL,
  LIVE_EVENT_HEALTH,
  createFlow,
  flowFromCenter,
  liveEventLabel,
  sampleFromTape,
  startScenario,
  stepFlow,
  type EngineId,
  type FlowEvent,
  type FlowSample,
  type FlowState,
  type ScenarioId,
  type TargetId,
} from "./flowSim";
import type { ControlCenter, FlowMeasured } from "./types";

/** 표본 보관 상한. 차트 창은 150초라 넉넉히 10분을 둔다. 한 번의 따라잡기도 이만큼까지만 밟는다 */
export const MAX_SAMPLES = 600;
/** 사건 표식 보관 상한. 관제 값 모드는 관제 기록에서 옮겨 오므로 끝없이 쌓이지 않게 한다 */
const MAX_EVENTS = 120;
/**
 * 흐름 조회가 없는 배포(연결 전)를 다시 확인하는 주기. 관리 요청은 모두 감사에 남으므로 없는 경로를 2초마다 두드리지 않는다.
 * 관리 평면을 새로 배포한 뒤 곧 붙도록 관제 판(5분)보다 짧게 둔다
 */
const MEASURED_ABSENT_MS = 60_000;
/** 서버가 Prometheus 를 읽지 못했다고 답하면 원천이 돌아올 때까지 천천히 다시 본다 */
const MEASURED_FAILING_MS = 10_000;
/**
 * 흐름 조회 요청이 실패한 뒤 다시 읽는 간격의 상한. 관제 판처럼 1분까지 늘리면 원천이 돌아온 뒤에도 화면이 오래 옛 값에
 * 머문다(2026-09-27 시연 11회차, 주 DB 전환 동안의 500). 429 의 Retry-After 는 따른다
 */
const MEASURED_RETRY_MAX_MS = 6_000;

export type FlowMode = "live" | "replay" | "measured";

/** 실측 모드의 흐름 조회 상태 */
export interface MeasuredPoll {
  /** 마지막으로 받은 판. 아직 없으면 null */
  board: FlowMeasured | null;
  /** 마지막 성공 시각(ms) */
  lastAt: number | null;
  /** 마지막 읽기가 실패했는가. 받은 판이 있으면 그 판을 두고 지연으로 그린다 */
  failed: boolean;
  failures: number;
  /** 다음 읽기 시각(ms) */
  nextAt: number;
}

interface FlowRun {
  /** 초기화와 모드가 바뀔 때마다 바뀐다. 차트를 새로 만드는 열쇠다 */
  key: number;
  mode: FlowMode;
  /** 틱 0 의 벽시계 초. 화면의 모든 시각은 origin + 틱이다 */
  origin: number;
  state: FlowState;
  samples: FlowSample[];
  paused: boolean;
  /**
   * 관제 값 모드에서 아직 첫 판을 받지 못했는가. 화면은 그동안 재생 값을 보이지 않고 기다린다. 실측 모드는 기다리지 않고
   * 첫 응답 전에도 같은 도면을 미수집으로 그린다
   */
  waiting: boolean;
  /** 실측 모드의 조회 상태. 다른 모드에서는 null 이다 */
  measured: MeasuredPoll | null;
  /** 재생 모드에서 벽시계만큼 밀린 틱을 밟는다 */
  advance: () => void;
  /** 재생을 건다. 관제 값 모드면 재생 세계를 새로 세운다. 막힌 재생이면 아무것도 바꾸지 않고 false */
  start: (id: ScenarioId, target?: TargetId) => boolean;
  /** 관제 판이 있으면 관제 값 모드로, 없으면 재생 세계의 첫 상태로 돌아간다 */
  reset: () => void;
  setPaused: (paused: boolean) => void;
}

/**
 * 재생 세계의 첫 상태. 관제 값 모드에서 app-2 의 리더 중단을 고르면 app-2 가 리더인 세계로 시작해 고른 노드가
 * 멈춘다(관제 값의 매칭 리더는 파티션마다 하나라 둘 다 리더다). 대화상자의 설명과 막힘 판정도 이 상태로 한다
 */
export function replayBase(id?: ScenarioId, target?: TargetId): FlowState {
  const leader: EngineId = id === "leaderKill" && target === "api2" ? "api2" : "api1";
  return stepFlow(createFlow(leader)).state;
}

/**
 * 재생 주행의 처음. 첫 표본을 바로 하나 만들어 둔다. 표본이 없으면 첫 1초 동안 켜져 있는 노드의 CPU 가
 * 미수집으로 보여 꺼진 노드처럼 읽힌다. 그 표본이 틱 1 이므로 틱 0 을 1초 전으로 잡는다
 */
type Boot = Pick<FlowRun, "key" | "mode" | "origin" | "state" | "samples" | "waiting" | "measured">;

function bootReplay(key: number, leader: EngineId = "api1"): Boot {
  const { state, sample } = stepFlow(createFlow(leader));
  return { key, mode: "replay", origin: Math.floor(Date.now() / 1000) - 1, state, samples: [sample], waiting: false, measured: null };
}

/**
 * 실측 주행의 처음. 첫 판이 올 때까지도 같은 도면을 그리고 칸마다 미수집이다(2026-09-27 사용자 결정). 골격이나 빈 상자로
 * 바꾸지 않는다. 받은 판이 없다는 사실은 화면 머리의 출처 칩('실측 확인 중')이 적는다
 */
function bootMeasured(key: number): Boot {
  const { state, origin } = measuredFlow(pendingBoard(), opsCenterStore.getState().data);
  return {
    key,
    mode: "measured",
    origin,
    state,
    samples: [],
    waiting: false,
    measured: { board: null, lastAt: null, failed: false, failures: 0, nextAt: 0 },
  };
}

/** 관제 값 주행이 이미 옮긴 관제 기록. 같은 기록을 사건 띠에 두 번 올리지 않는다 */
let seen = new Set<string>();
/** 관제 값 주행을 시작한 벽시계(ms). 그 앞의 관제 기록(시드 사건)은 사건 띠에 올리지 않는다 */
let liveSince = Date.now();
/** 마지막으로 옮긴 판의 수집 시각. 같은 판을 두 번 표본으로 쌓지 않는다 */
let lastCollected: string | null = null;

function bootLive(key: number): Boot {
  seen = new Set();
  liveSince = Date.now();
  lastCollected = null;
  void loadTape(key);
  // 첫 판이 오기 전의 자리. 화면은 waiting 동안 이 값을 그리지 않는다
  return { key, mode: "live", origin: Math.floor(Date.now() / 1000), state: createFlow(), samples: [], waiting: true, measured: null };
}

/**
 * 관제 시뮬레이터의 지표 관제 기록(최근 10분)으로 주행을 앞으로 채운다. 화면에 들어온 뒤부터만 쌓으면 150초 창의 대부분이 빈
 * 격자로 시작한다. 원점을 기록의 첫 박자로 옮기고 이미 쌓은 표본과 사건 표지의 틱을 그만큼 민다. 그 구간의 관제 기록도 사건 띠에
 * 올리도록 liveSince를 기록의 처음으로 당긴다. http 원천에는 기록이 없다(null)
 */
async function loadTape(key: number): Promise<void> {
  let tape: Awaited<ReturnType<ReturnType<typeof getAdminApi>["getFlowTape"]>> = null;
  try {
    tape = await getAdminApi().getFlowTape();
  } catch {
    return;
  }
  const r = useFlowRun.getState();
  if (tape === null || tape.length === 0 || r.key !== key || r.mode !== "live") return;
  const first = Math.floor(tape[0].t / 1000);
  const shift = r.origin - first;
  if (shift <= 0) return;
  // 이미 쌓은 표본과 같은 초는 기록에서 빼고, 그 앞의 박자만 싣는다
  const liveFrom = r.samples.length > 0 ? r.origin + r.samples[0].at : Math.floor(Date.now() / 1000) + 1;
  const past = tape.filter((e) => Math.floor(e.t / 1000) < liveFrom).map((e) => sampleFromTape(e, Math.floor(e.t / 1000) - first));
  const moved = r.samples.map((x) => ({ ...x, at: x.at + shift, lease: LEASE_TTL - ((x.at + shift) % LEASE_TICK) }));
  const events = r.state.events.map((e) => ({ ...e, at: e.at + shift }));
  const all = [...past, ...moved];
  liveSince = tape[0].t;
  useFlowRun.setState({
    origin: first,
    samples: all.length > MAX_SAMPLES ? all.slice(all.length - MAX_SAMPLES) : all,
    state: { ...r.state, tick: r.state.tick + shift, events },
    waiting: all.length === 0,
  });
  onCenter(opsCenterStore.getState().data, true);
}

/** 관제 판이 흐름으로 옮길 수 있는 판인가. sim 원천이고 필요한 칸이 모두 있어야 한다 */
function liveReady(c: ControlCenter | null): c is ControlCenter {
  return c !== null && flowFromCenter(c, 0, []) !== null;
}

export const useFlowRun = create<FlowRun>((set, get) => ({
  ...(isAdminHttp() ? bootMeasured(0) : liveReady(opsCenterStore.getState().data) ? bootLive(0) : bootReplay(0)),
  paused: false,
  advance: () => {
    const { mode, origin, state, paused, samples } = get();
    if (paused || mode !== "replay") return;
    const due = Math.min(Math.floor(Date.now() / 1000) - origin - state.tick, MAX_SAMPLES);
    if (due <= 0) return;
    // 밀린 틱도 하나씩 밟는다. 재생 단계와 리스 만료는 틱마다 판정되고, 차트는 표본이 1초
    // 간격으로 빈틈없이 이어진다고 보고 사건 좌표를 구한다
    let s = state;
    const fresh: FlowSample[] = [];
    for (let i = 0; i < due; i += 1) {
      const next = stepFlow(s);
      s = next.state;
      fresh.push(next.sample);
    }
    const all = samples.concat(fresh);
    set({ state: s, samples: all.length > MAX_SAMPLES ? all.slice(all.length - MAX_SAMPLES) : all });
  },
  start: (id, target) => {
    const r = get();
    // 실측 화면은 재생을 걸지 않는다. 같은 일은 실제 스택에 일으키고 화면은 그 결과를 잰다
    if (r.mode === "measured") return false;
    if (r.mode === "live") {
      const boot = bootReplay(r.key + 1, id === "leaderKill" && target === "api2" ? "api2" : "api1");
      const next = startScenario(boot.state, id, target);
      if (next === boot.state) return false;
      set({ ...boot, state: next });
      return true;
    }
    const prev = r.state;
    const next = startScenario(prev, id, target);
    if (next === prev) return false;
    set({ state: next });
    return true;
  },
  // 초기화는 일시정지를 풀지 않는다. 멈춤은 보는 사람의 선택이라 시뮬레이션 상태와 따로 둔다. 실측은 곧바로 다시 읽는다
  reset: () =>
    set((r) =>
      r.mode === "measured"
        ? { measured: r.measured && { ...r.measured, nextAt: 0 } }
        : liveReady(opsCenterStore.getState().data) ? bootLive(r.key + 1) : bootReplay(r.key + 1),
    ),
  setPaused: (paused) => {
    // 재개할 때 원점을 지금에 다시 맞춘다. 그대로 두면 멈춘 동안의 초가 한꺼번에 밀린 틱으로 잡혀
    // 수백 틱을 몰아 진행한다. 시뮬레이션에는 멈춘 구간이 없으므로 축을 이어 붙인다
    if (!paused && get().paused) set({ origin: Math.floor(Date.now() / 1000) - get().state.tick });
    set({ paused });
  },
}));

/**
 * 관제 값 모드의 표본. 관제 판이 새로 올 때마다 하나를 쌓는다. 판은 운영 관제 셸의 폴러가 박자마다 읽으므로
 * 이 모듈을 한 번 불러온 뒤에는 흐름·지표 관제 화면 밖에서도 최근 10분이 쌓여 차트가 빈 채로 시작하지 않는다.
 * 틱은 벽시계 초라 탭이 숨어 판이 끊긴 동안은 표본 사이가 비고 차트는 그 구간을 공백으로 둔다
 */
function onCenter(c: ControlCenter | null, again = false): void {
  const r = useFlowRun.getState();
  if (r.mode !== "live" || r.paused || c === null || (!again && c.collectedAt === lastCollected)) return;
  const tick = Math.max(0, Math.floor(Date.now() / 1000) - r.origin);
  const events: FlowEvent[] = r.state.events.slice();
  if (c.incidents.state === "READY") {
    for (const e of [...c.incidents.data.events].reverse()) {
      const health = LIVE_EVENT_HEALTH[e.kind];
      if (!health || seen.has(e.id) || Date.parse(e.at) < liveSince) continue;
      seen.add(e.id);
      // 표지는 기록이 난 초에 둔다. 화면에 들어오기 전의 기록을 지금 초에 모으면 표지가 창 오른쪽 끝에 겹쳐 쌓인다
      events.push({ at: Math.min(tick, Math.max(0, Math.floor(Date.parse(e.at) / 1000) - r.origin)), health, label: liveEventLabel(e.text) });
    }
    events.sort((a, b) => a.at - b.at);
  }
  const next = flowFromCenter(c, tick, events.length > MAX_EVENTS ? events.slice(events.length - MAX_EVENTS) : events);
  if (next === null) return;
  lastCollected = c.collectedAt;
  const last = r.samples[r.samples.length - 1];
  // 같은 초에 판이 두 번 오면(조작 직후의 강제 읽기) 앞 표본을 새 값으로 바꾼다. 차트의 시간축은 초 단위다
  const kept = last !== undefined && last.at === tick ? r.samples.slice(0, -1) : r.samples;
  const all = kept.concat(next.sample);
  useFlowRun.setState({
    state: next.state,
    samples: all.length > MAX_SAMPLES ? all.slice(all.length - MAX_SAMPLES) : all,
    waiting: false,
  });
}

/** 받은 흐름 조회를 주행에 싣는다. 관제 판(임계값, 사건 기록)은 지금 가진 것을 함께 옮긴다 */
function applyMeasured(board: FlowMeasured, at: number): void {
  const { state, samples, origin } = measuredFlow(board, opsCenterStore.getState().data);
  const period = board.flow.state === "NOT_CONNECTED" ? MEASURED_ABSENT_MS
    : board.flow.state === "UNAVAILABLE" ? MEASURED_FAILING_MS : MEASURED_POLL_MS;
  useFlowRun.setState({
    state,
    samples,
    origin,
    waiting: false,
    measured: { board, lastAt: at, failed: false, failures: 0, nextAt: at + period },
  });
}

let measuredInflight = false;

/** 흐름 조회 한 건. 겹쳐 부르지 않는다. 실패하면 받은 판을 두고 주기를 두 배씩 늘리며 429 의 Retry-After 를 따른다 */
async function pollMeasured(key: number): Promise<void> {
  if (measuredInflight) return;
  measuredInflight = true;
  try {
    const board = await getAdminApi().getFlowMeasured();
    const r = useFlowRun.getState();
    if (board !== null && r.key === key && r.mode === "measured") applyMeasured(board, Date.now());
  } catch (e) {
    const r = useFlowRun.getState();
    if (r.key !== key || r.measured === null) return;
    const failures = r.measured.failures + 1;
    const retryAfter = e instanceof AdminError ? e.retryAfter : null;
    // 받은 판이 없으면 같은 도면에 불러오기 실패를 싣는다. 받은 판이 있으면 그 값을 두고 머리 칩이 지연을 적는다
    const fallback = r.measured.board === null
      ? measuredFlow(
          emptyBoard({ state: "UNAVAILABLE", source: "관리 API 흐름 조회", reason: e instanceof AdminError ? `요청 실패 ${e.status}` : "요청 실패" }),
          opsCenterStore.getState().data,
        )
      : null;
    useFlowRun.setState({
      ...(fallback ?? {}),
      waiting: false,
      measured: {
        ...r.measured,
        failed: true,
        failures,
        nextAt: Date.now() + (retryAfter !== null
          ? backoffMs(MEASURED_POLL_MS, failures, retryAfter)
          : Math.min(MEASURED_RETRY_MAX_MS, backoffMs(MEASURED_POLL_MS, failures, null))),
      },
    });
  } finally {
    measuredInflight = false;
  }
}

opsCenterStore.subscribe((st, prev) => {
  if (st.data === prev.data) return;
  const r = useFlowRun.getState();
  // 실측 모드는 흐름 조회를 다시 부르지 않고 마지막 판에 새 임계값과 사건 기록만 다시 얹는다
  if (r.mode === "measured") {
    if (r.measured?.board) useFlowRun.setState(measuredFlow(r.measured.board, st.data));
    else if (r.measured && !r.measured.failed) useFlowRun.setState(measuredFlow(pendingBoard(), st.data));
    return;
  }
  // 관제 판이 처음 서면(이 화면으로 곧바로 들어와 판보다 먼저 모듈이 뜬 경우) 재생의 첫 상태에서 관제 값 모드로 옮긴다.
  // 운영자가 건 재생은 초기화하기 전까지 그대로 둔다
  if (r.mode === "replay" && r.state.scenario === null && liveReady(st.data)) useFlowRun.setState(bootLive(r.key + 1));
  onCenter(st.data);
});

/**
 * 주행 시계. 주행을 보여 주는 화면이 하나씩 건다. 재생 모드는 콘솔 공용 박자에 맞춰 한 틱씩 밟고, 관제 값 모드는
 * 관제 판 폴러를 구독해 판이 올 때마다 표본을 쌓는다. 실측 모드는 박자마다 다음 읽기 시각이 됐는지 보고 흐름 조회를 부른다.
 * 멈춘 동안에는 박자를 타지 않는다
 */
export function useFlowClock(): void {
  const paused = useFlowRun((r) => r.paused);
  const mode = useFlowRun((r) => r.mode);
  useEffect(() => {
    // 관제 판 폴러는 구독자 수로 켜고 끈다. 사이드바 배지가 이미 구독하지만 이 화면도 판을 읽으므로 따로 건다
    const { subscribe, unsubscribe } = opsCenterStore.getState();
    subscribe();
    return unsubscribe;
  }, []);
  useEffect(() => {
    if (paused || mode !== "replay") return;
    const advance = useFlowRun.getState().advance;
    advance();
    return onBeat(advance);
  }, [paused, mode]);
  useEffect(() => {
    if (mode !== "measured") return;
    const due = (now: number) => {
      const r = useFlowRun.getState();
      if (r.measured !== null && now >= r.measured.nextAt) void pollMeasured(r.key);
    };
    due(Date.now());
    return onBeat(due);
  }, [mode]);
}
