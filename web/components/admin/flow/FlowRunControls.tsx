"use client";

/**
 * 재생 주행의 머리 조작. 서비스 흐름과 지표 관제가 같은 주행을 보므로 같은 조작을 같은 자리에 둔다.
 * 한쪽에서 멈추거나 초기화하면 다른 화면으로 옮겨도 그대로다(lib/admin/flowRun.ts).
 *
 * 재생 표지는 지표 관제에서만 보인다. 서비스 흐름은 무대 머리 줄의 상황 자막이 같은 내용을 더 크게
 * 실으므로 indicator={false} 로 표지와 그 자리를 뺀다. 재생 중은 상태가 아니라서 주의색을 쓰지 않고
 * 크롬 악센트 약틴트 면에 ▶ 형태를 둔다(디자인 시스템 §12-3).
 */

import adminStyles from "@/components/admin/admin.module.css";
import { describeScenario, isBusy, scenarioLength } from "@/lib/admin/flowSim";
import { useFlowRun } from "@/lib/admin/flowRun";
import styles from "./flow.module.css";
import StatusShape from "../StatusShape";

export interface FlowRunControlsProps {
  /** 재생 표지를 그린다. 서비스 흐름은 자막 바가 같은 내용을 실어 끈다 */
  indicator?: boolean;
}

export default function FlowRunControls({ indicator = true }: FlowRunControlsProps) {
  const state = useFlowRun((r) => r.state);
  const paused = useFlowRun((r) => r.paused);
  const setPaused = useFlowRun((r) => r.setPaused);
  const reset = useFlowRun((r) => r.reset);

  const busy = state.scenario !== null && isBusy(state);
  const title = busy && state.scenario ? describeScenario(state, state.scenario, state.target ?? undefined).title : null;
  const length = state.scenario ? scenarioLength(state.scenario) : 0;

  return (
    <div className={styles.headActions}>
      {/* 재생 표시 자리는 비어 있을 때도 폭을 잡아 둔다. 표시가 드나들 때마다 왼쪽 설명 줄이
          다시 접히면 아래 패널 전체가 한 줄씩 오르내린다 */}
      {indicator && (
        <span className={styles.runSlot}>
          {title !== null && (
            <span className={styles.runPill}>
              <span aria-hidden="true"><StatusShape kind="play" /></span>
              <span>
                재생 중 · {title}{" "}
                <b className="num">
                  {Math.min(state.elapsed, length)}/{length}초
                </b>
              </span>
            </span>
          )}
        </span>
      )}
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
