"use client";

/**
 * 서비스 흐름과 관제 화면이 함께 보는 재생 주행. 두 화면은 같은 시뮬레이션을 봐야 한다.
 * 서비스 흐름에서 DB 전환을 걸고 관제로 넘어가면 그 전환이 차트에 이어져 있어야 한다.
 * 그래서 주행을 화면 상태가 아니라 모듈 수준 스토어에 둔다. 관리 셸 안에서 화면을 옮겨도
 * 모듈은 그대로라 주행이 끊기지 않는다. 새로 고치면 처음부터 다시 시작한다.
 *
 * 시계는 벽시계를 따른다. 몇 틱이 밀렸는지를 벽시계로 세고 그만큼 밟는다. 배경 탭은 타이머를
 * 늦추고, 두 화면 밖에 있는 동안은 타이머가 아예 없으므로 콜백마다 한 틱씩 나아가면 화면의
 * 시각(origin + 틱)이 실제 시각보다 뒤처진다.
 */

import { useEffect } from "react";
import { create } from "zustand";
import { onBeat } from "./ticker";
import {
  createFlow,
  startScenario,
  stepFlow,
  type FlowSample,
  type FlowState,
  type ScenarioId,
  type TargetId,
} from "./flowSim";

/** 표본 보관 상한. 차트 창은 150초라 넉넉히 10분을 둔다. 한 번의 따라잡기도 이만큼까지만 밟는다 */
export const MAX_SAMPLES = 600;

interface FlowRun {
  /** 초기화마다 바뀐다. 차트를 새로 만드는 열쇠다 */
  key: number;
  /** 틱 0 의 벽시계 초. 화면의 모든 시각은 origin + 틱이다 */
  origin: number;
  state: FlowState;
  samples: FlowSample[];
  paused: boolean;
  /** 벽시계만큼 밀린 틱을 밟는다 */
  advance: () => void;
  /** 재생을 건다. 막힌 재생이면 아무것도 바꾸지 않고 false */
  start: (id: ScenarioId, target?: TargetId) => boolean;
  reset: () => void;
  setPaused: (paused: boolean) => void;
}

/**
 * 새 주행. 첫 표본을 바로 하나 만들어 둔다. 표본이 없으면 첫 1초 동안 켜져 있는 노드의 CPU 가
 * 미수집으로 보여 꺼진 노드처럼 읽힌다. 그 표본이 틱 1 이므로 틱 0 을 1초 전으로 잡는다
 */
function boot(key: number): Pick<FlowRun, "key" | "origin" | "state" | "samples"> {
  const { state, sample } = stepFlow(createFlow());
  return { key, origin: Math.floor(Date.now() / 1000) - 1, state, samples: [sample] };
}

export const useFlowRun = create<FlowRun>((set, get) => ({
  ...boot(0),
  paused: false,
  advance: () => {
    const { origin, state, paused, samples } = get();
    if (paused) return;
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
    const prev = get().state;
    const next = startScenario(prev, id, target);
    if (next === prev) return false;
    set({ state: next });
    return true;
  },
  // 초기화는 일시정지를 풀지 않는다. 멈춤은 보는 사람의 선택이라 시뮬레이션 상태와 따로 둔다
  reset: () => set((r) => boot(r.key + 1)),
  setPaused: (paused) => {
    // 재개할 때 원점을 지금에 다시 맞춘다. 그대로 두면 멈춘 동안의 초가 한꺼번에 밀린 틱으로 잡혀
    // 수백 틱을 몰아 진행한다. 시뮬레이션에는 멈춘 구간이 없으므로 축을 이어 붙인다
    if (!paused && get().paused) set({ origin: Math.floor(Date.now() / 1000) - get().state.tick });
    set({ paused });
  },
}));

/**
 * 주행 시계. 재생을 보여 주는 화면이 하나씩 건다. 멈춘 동안에는 박자를 타지 않는다. 콘솔 공용 박자는
 * 초 경계 바로 뒤에 뛰므로 한 박자에 한 틱씩 밟고, 탭이 숨은 동안 밀린 틱은 돌아와 한 번에 밟는다
 */
export function useFlowClock(): void {
  const paused = useFlowRun((r) => r.paused);
  useEffect(() => {
    if (paused) return;
    const advance = useFlowRun.getState().advance;
    advance();
    return onBeat(advance);
  }, [paused]);
}
