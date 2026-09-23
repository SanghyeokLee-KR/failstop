"use client";

/**
 * 값이 없는 이유를 네 가지로 가른다.
 *
 * ADR 0027이 "지표 없음, 수집 실패, 정상 0은 다른 상태다"라고 못 박았다. 한 화면에서
 * 그것을 전부 빈칸이나 0으로 적으면 운영자가 세 상황을 구분할 수 없고, 그 구분이 무너지면
 * 관제 화면이 정상을 단정하게 된다. 이 컴포넌트가 그 구분의 어휘다.
 *
 * 미수집  원천은 있는데 이번에 못 읽었다. 다시 읽으면 값이 온다
 * 원천 없음  이 환경에 수집기가 없다. 다시 읽어도 오지 않는다
 * 미실행  조작이 아직 일어나지 않았다. 통과도 실패도 아닌 미판정이다
 * 미반영  조작은 접수됐는데 실체가 아직 따라오지 않았다
 *
 * 특히 마지막 둘을 가르는 것이 중요하다. 미실행을 통과로 칠하면 공허 통과가 되고,
 * 미반영을 실패로 칠하면 아직 진행 중인 조작이 실패로 보인다.
 */

import styles from "./admin.module.css";

export type SourceState = "MISSING" | "NO_SOURCE" | "NOT_RUN" | "NOT_APPLIED";

const WORD: Record<SourceState, string> = {
  MISSING: "미수집",
  NO_SOURCE: "원천 없음",
  NOT_RUN: "미실행",
  NOT_APPLIED: "미반영",
};

export default function SourceMark({ state, note }: { state: SourceState; note?: string }) {
  return (
    <span className={styles.sourceMark} data-state={state}>
      {WORD[state]}
      {note && <span className={styles.sourceNote}>{note}</span>}
    </span>
  );
}

/** 문구만 필요한 자리(표 셀 등)에서 쓴다 */
export function sourceWord(state: SourceState): string {
  return WORD[state];
}
