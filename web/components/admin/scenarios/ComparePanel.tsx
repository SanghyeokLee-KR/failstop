"use client";

/**
 * 비교 보기(관리자 설계 4-10-6). 같은 시나리오를 수동과 자동 두 재생 세계로 함께 돌려 두 줄의 스크럽 바와 결과 표를 나란히 둔다.
 * 두 줄은 같은 시각 축이고 손잡이를 함께 옮긴다. 줄 머리 아래에는 그 시각의 판독(앱 계층, p95, 경보, 쓰기)을 적는다. AI 조치가 없는
 * 시나리오는 두 줄이 같고 결과 표 머리에 '자동 모드여도 금지 목록의 일은 사람이 합니다'를 적는다.
 */

import { mmss } from "@/lib/admin/timeText";
import type { ReplayMode, ReplayTimeline } from "@/lib/admin/scenarioWorld";
import type { ScenarioSpec } from "@/lib/admin/types";
import ScrubBar from "../ScrubBar";
import StatusShape from "../StatusShape";
import styles from "./scenarios.module.css";

const MODE_WORD: Record<ReplayMode, string> = { MANUAL: "수동", AUTO: "자동" };

function span(tl: ReplayTimeline): string {
  const r = tl.result;
  if (r.openedSec === null) return "사건 없음";
  if (r.resolvedSec === null) return "재생 끝까지 진행 중";
  return mmss((r.resolvedSec - r.openedSec) * 1000);
}

function noticeWord(tl: ReplayTimeline): string {
  const n = tl.result.notices;
  if (n.count === 0) return "없음";
  return n.top ? `${n.count}건 · 가장 높은 등급 ${n.top}` : `${n.count}건 · 경보 카드`;
}

export default function ComparePanel({ spec, timelines, sec, onSeek, onToggle, onStage, animate, valueText }: {
  spec: ScenarioSpec;
  timelines: Record<ReplayMode, ReplayTimeline>;
  sec: number;
  onSeek: (s: number) => void;
  onToggle: () => void;
  onStage: (dir: -1 | 1) => void;
  animate: boolean;
  valueText: (s: number) => string;
}) {
  const modes: ReplayMode[] = ["MANUAL", "AUTO"];
  const same = spec.tags.includes("NO_AI");
  const rows: { label: string; value: (tl: ReplayTimeline) => string; better?: "low" | "high"; num?: (tl: ReplayTimeline) => number | null }[] = [
    { label: "해제까지 걸린 시간", value: span, better: "low", num: (tl) => (tl.result.openedSec === null || tl.result.resolvedSec === null ? null : tl.result.resolvedSec - tl.result.openedSec) },
    { label: "운영자 결정·조치", value: (tl) => `${tl.result.decisions}건` },
    { label: "AI 자동 실행", value: (tl) => `${tl.result.autoRuns}회` },
    { label: "되돌림", value: (tl) => `${tl.result.reverts}회` },
    { label: "응답 p95 최고", value: (tl) => `${tl.result.p95Max}ms`, better: "low", num: (tl) => tl.result.p95Max },
    { label: "마지막 앱 계층", value: (tl) => `${tl.result.lastTier}대` },
    { label: "Teams 통지", value: noticeWord },
  ];
  if (modes.some((m) => timelines[m].result.writesDownSec > 0)) {
    rows.push({ label: "쓰기가 막힌 시간", value: (tl) => mmss(tl.result.writesDownSec * 1000), better: "low", num: (tl) => tl.result.writesDownSec });
  }
  return (
    <section className={styles.compare} aria-labelledby="rp-compare">
      <div className={styles.cellHead}>
        <h3 id="rp-compare" className={styles.cellTitle}>
          수동과 자동
        </h3>
        <span className={styles.cellMeta}>같은 시각 축 · 손잡이를 함께 옮깁니다</span>
      </div>
      <div className={styles.lanes}>
        {modes.map((m) => {
          const s = timelines[m].samples[Math.min(sec, timelines[m].samples.length - 1)];
          return (
            <div key={m} className={styles.laneRow}>
              <ScrubBar
                lane={MODE_WORD[m]}
                duration={spec.durationSec}
                value={sec}
                onSeek={onSeek}
                onToggle={onToggle}
                onStage={onStage}
                stages={spec.stages.map((st, i) => ({ sec: timelines[m].stageSecs[i], title: st.title }))}
                markers={timelines[m].markers}
                label={`${MODE_WORD[m]} 재생 위치`}
                valueText={valueText}
                animate={animate}
              />
              <p className={`${styles.laneNow} num`} aria-label={`${MODE_WORD[m]} · 지금 시각의 판독`}>
                <span>앱 {s.serving}대</span>
                <span data-tone={s.p95 > 400 ? "warning" : undefined}>p95 {s.p95}ms</span>
                <span data-tone={s.alerts > 0 ? "warning" : undefined}>경보 {s.alerts}</span>
                <span data-tone={s.writes !== "OPEN" ? "danger" : undefined}>
                  쓰기 {s.writes === "OPEN" ? "열림" : s.writes === "FAILING" ? "실패" : "일시 중단"}
                </span>
              </p>
            </div>
          );
        })}
      </div>
      <div className={styles.resultHead}>
        <span className={styles.blockHead}>결과 · 재생 {mmss(spec.durationSec * 1000)} 동안</span>
        {same && (
          <span className={styles.sameNote}>
            <StatusShape kind="ring" className={styles.shape} /> 자동 모드여도 금지 목록의 일은 사람이 합니다
          </span>
        )}
      </div>
      <table className={styles.results}>
        <thead>
          <tr>
            <th scope="col">항목</th>
            <th scope="col" data-num="">
              수동
            </th>
            <th scope="col" data-num="">
              자동
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const a = r.num?.(timelines.MANUAL) ?? null;
            const b = r.num?.(timelines.AUTO) ?? null;
            const win = r.better && a !== null && b !== null && a !== b ? (r.better === "low" ? (a < b ? "MANUAL" : "AUTO") : a > b ? "MANUAL" : "AUTO") : null;
            return (
              <tr key={r.label}>
                <th scope="row">{r.label}</th>
                {modes.map((m) => (
                  <td key={m} data-num="" data-win={win === m ? "" : undefined}>
                    {r.value(timelines[m])}
                    {win === m && <span className="sr-only"> · 더 나음</span>}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}
