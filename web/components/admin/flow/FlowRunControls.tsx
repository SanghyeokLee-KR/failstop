"use client";

/**
 * 재생 주행의 머리 조작. 서비스 흐름과 지표 관제가 같은 주행을 보므로 같은 조작을 같은 자리에 둔다.
 * 한쪽에서 멈추거나 초기화하면 다른 화면으로 옮겨도 그대로다(lib/admin/flowRun.ts).
 */

import { HEALTH_ICON } from "@/components/admin/ui";
import adminStyles from "@/components/admin/admin.module.css";
import { describeScenario, isBusy, scenarioLength } from "@/lib/admin/flowSim";
import { useFlowRun } from "@/lib/admin/flowRun";
import styles from "./flow.module.css";

export default function FlowRunControls() {
  const state = useFlowRun((r) => r.state);
  const paused = useFlowRun((r) => r.paused);
  const setPaused = useFlowRun((r) => r.setPaused);
  const reset = useFlowRun((r) => r.reset);

  const busy = state.scenario !== null && isBusy(state);
  const title = busy && state.scenario ? describeScenario(state, state.scenario, state.target ?? undefined).title : null;
  const length = state.scenario ? scenarioLength(state.scenario) : 0;

  return (
    <div className={styles.headActions}>
      {/* 재생 표시 자리는 비어 있을 때도 폭을 잡아 둔다. 표시가 나타날 때마다 설명 줄이
          다시 접히면 아래 패널 전체가 한 줄씩 오르내린다 */}
      <span className={styles.runSlot}>
        {title !== null && (
          <span className="status-pill" data-health="warning">
            <span aria-hidden="true">{HEALTH_ICON.warning}</span>
            <span>
              재생 중 · {title}{" "}
              <span className="num">
                {Math.min(state.elapsed, length)}/{length}초
              </span>
            </span>
          </span>
        )}
      </span>
      {/* 화면의 시안 표식은 사용자 요청으로 뺐다(2026-09-23). 값이 실측으로 낭독되지 않게 보조기술에는 남긴다 */}
      <span className="sr-only">값은 시뮬레이션</span>
      {/* 전환 버튼이라 이름은 고정하고 상태는 aria-pressed 와 눌림 면으로 보인다. 이름을 재개로
          바꾸면 "재개, 눌림"으로 읽혀 멈췄는지 돌고 있는지가 거꾸로 들린다 */}
      <button
        type="button"
        className={`${adminStyles.neutralBtn} ${styles.pauseBtn}`}
        aria-pressed={paused}
        onClick={() => setPaused(!paused)}
      >
        일시정지
      </button>
      <button type="button" className={adminStyles.neutralBtn} onClick={reset}>
        초기화
      </button>
    </div>
  );
}
