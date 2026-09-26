"use client";

/**
 * 상황 자막 바. 재생이 진행 중일 때만 무대 머리 줄에 한 줄로 선다. 시나리오 제목, 이번 재생에서 가장
 * 최근 사건 한 줄, 경과와 그 아래 진행 막대다. 사건을 쌓는 목록이 아니라 지금 한 줄만 보인다.
 * 주행 상태를 읽어 표시용 문자열만 만든다. 새로 계산하는 값이 없다.
 *
 * 경과가 매초 바뀌므로 자막 전체를 보조기술에서 가린다. 붙여 두면 포커스를 옮길 때마다 바뀐 초가
 * 다시 읽힌다. 재생 중이라는 사실만 한 번 적고 사건 낭독은 EventLog 가 맡는다.
 */

import { HEALTH_ICON } from "@/components/admin/ui";
import { describeScenario, isBusy, scenarioLength } from "@/lib/admin/flowSim";
import { useFlowRun } from "@/lib/admin/flowRun";
import styles from "./flow.module.css";

const pad = (n: number) => String(n).padStart(2, "0");
const mmss = (sec: number) => `${pad(Math.floor(sec / 60))}:${pad(sec % 60)}`;

export default function FlowCaption() {
  const state = useFlowRun((r) => r.state);
  if (state.scenario === null || !isBusy(state)) return null;

  const title = describeScenario(state, state.scenario, state.target ?? undefined).title;
  const length = scenarioLength(state.scenario);
  const elapsed = Math.min(state.elapsed, length);
  // 이번 재생이 시작된 틱. 그 뒤의 사건만 이번 재생의 것이다. 앞선 재생의 사건은 자막에 올리지 않는다
  const startTick = state.tick - state.elapsed;
  const last = state.events[state.events.length - 1];
  const latest = last !== undefined && last.at >= startTick ? last : null;
  const progress = length > 0 ? elapsed / length : 0;

  return (
    <div className={styles.caption}>
      <span className="sr-only">재생 중 · {title}</span>
      <div className={styles.capLine} aria-hidden="true">
        <span className={styles.capTitle}>{title}</span>
        <span className={styles.capStep}>
          {latest !== null && (
            <span className={styles.capShape} data-health={latest.health}>
              {HEALTH_ICON[latest.health]}
            </span>
          )}
          <span className={styles.capStepText}>{latest !== null ? latest.label : "시작"}</span>
        </span>
        <span className={`${styles.capTime} num`}>
          {mmss(elapsed)} / {mmss(length)}
        </span>
      </div>
      <div className={styles.capTrack} aria-hidden="true">
        <span className={styles.capFill} style={{ transform: `scaleX(${progress})` }} />
      </div>
    </div>
  );
}
