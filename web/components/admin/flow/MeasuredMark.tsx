"use client";

/**
 * 실측 모드의 화면 머리 표식. 관제센터와 같은 규칙이다(디자인 시스템 §12-9). 환경 배지와 출처 칩('● 실측 · N초 전',
 * 늦으면 ▲ 지연, 원천이 없으면 ◌ 원천 연결 전)을 둔다. 신선도는 흐름 조회가 실은 마지막 격자의 시각으로 잰다.
 * 'N초 전'이 매초 바뀌므로 시계는 이 조각만 탄다
 */

import { useFlowRun } from "@/lib/admin/flowRun";
import { useOpsPick } from "@/lib/admin/opsCenter";
import StatusShape from "../StatusShape";
import { EnvBadge, ago, useClock } from "../control/parts";
import styles from "../control/control.module.css";
import flowStyles from "./flow.module.css";

/** 폴링 두 번(4초)과 가장 느린 수집 간격(DB·컨테이너 5초)을 넘기면 지연이다 */
export const STALE_MS = 10_000;

/**
 * 실측 판의 신선도. at 은 마지막으로 받은 격자의 시각이고 받은 판이 없으면 null 이다. failed 는 마지막 읽기가 실패했는지,
 * stale 은 그 값이 STALE_MS 를 넘긴 옛 값인지다. 시계를 타므로 이 훅을 부르는 조각만 매초 다시 그린다
 */
export function useMeasuredFreshness(): { at: string | null; failed: boolean; stale: boolean; now: number } {
  const poll = useFlowRun((r) => r.measured);
  const now = useClock();
  const board = poll?.board ?? null;
  const failed = poll?.failed === true;
  if (board === null || board.flow.state !== "READY" || board.points === 0) return { at: null, failed, stale: false, now };
  const at = new Date((board.start + (board.points - 1) * board.step) * 1000).toISOString();
  return { at, failed, stale: now - Date.parse(at) > STALE_MS, now };
}

/**
 * 실측 값이 옛 값이 된 동안(흐름 조회 실패나 지연으로 마지막 격자가 10초를 넘김) 무대를 흐리게 덮는다. 마지막 값이 지금 값처럼
 * 보이지 않게 하려는 것이다(2026-09-27 시연 11회차). 누름은 아래로 흘려보내 팝오버와 단추는 그대로 쓴다
 */
export function StaleVeil() {
  const f = useMeasuredFreshness();
  if (f.at === null || !f.stale) return null;
  return <span className={flowStyles.staleVeil} aria-hidden="true" />;
}

export default function MeasuredMark() {
  const poll = useFlowRun((r) => r.measured);
  // 흐름 조회가 없는 배포는 환경을 싣지 않는다. 같은 관리 JVM 의 한 판이 싣는 환경으로 채운다
  const centerEnv = useOpsPick((c) => c?.environment ?? null);
  const now = useClock();
  const board = poll?.board ?? null;
  const env = board?.environment === "UNKNOWN" && centerEnv ? centerEnv : board?.environment ?? "UNKNOWN";
  if (board === null) {
    return (
      <span className={styles.sourceChip} data-kind={poll?.failed ? "stale" : "none"}>
        <span aria-hidden="true"><StatusShape kind={poll?.failed ? "triangle" : "dashed"} /></span>
        {poll?.failed ? "실측 · 불러오기 실패" : "실측 확인 중"}
      </span>
    );
  }
  if (board.flow.state !== "READY") {
    const failed = board.flow.state === "UNAVAILABLE";
    return (
      <>
        <EnvBadge env={env} />
        <span className={styles.sourceChip} data-kind={failed ? "stale" : "none"}>
          <span aria-hidden="true"><StatusShape kind={failed ? "triangle" : "dashed"} /></span>
          {failed ? "실측 · 불러오기 실패" : "원천 연결 전"}
        </span>
      </>
    );
  }
  const at = new Date((board.start + (board.points - 1) * board.step) * 1000).toISOString();
  const stale = poll?.failed === true || now - Date.parse(at) > STALE_MS;
  return (
    <>
      <EnvBadge env={env} />
      <span className={styles.sourceChip} data-kind={stale ? "stale" : "live"}>
        <span aria-hidden="true"><StatusShape kind={stale ? "triangle" : "circle"} /></span>
        실측 · {ago(at, now)}
        {stale && " · 지연"}
      </span>
    </>
  );
}
