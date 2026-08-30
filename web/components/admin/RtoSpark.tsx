/**
 * 입력은 recovery_runs.duration_ms(저널 재생 소요)이며 페일오버 RTO가 아니다.
 * RTO 원천은 harness.failover_runs.rto_seconds이고 판정은 verdict.failover_ok가 한다.
 * PASS 무채·FAIL danger에 상단 ■ 마커를 병기한다(색 단독 아님).
 */

import type { RecoveryRun } from "@/lib/admin/types";
import styles from "./admin.module.css";

const MAX_RUNS = 20;
const MIN_RUNS = 3;
const BAR_W = 6;
const BAR_GAP = 4;
const H = 40;
// FAIL ■ 마커가 최고 막대 위에 겹치지 않게 상단 존을 예약한다
const TOP_RESERVE = 8;
/**
 * 표본 최댓값을 덮는 최소 단계로 창을 고정한다. 고정 기준선은 전 막대를 최소 높이 스텁으로 만들고,
 * 자기 최댓값 정규화는 최고 막대를 항상 천장에 붙여 목표 대비 판독을 지운다.
 * 단계가 바뀌면 축이 바뀐 것을 svg title이 밝힌다(모니터링 계기 METER_WINDOW_STEPS와 같은 규약).
 */
const SCALE_STEPS_MS = [200, 500, 1_000, 5_000, 30_000, 120_000];

/** 자릿수가 달라지므로 초 고정 표기를 쓰지 않는다. 1초 미만은 ms로 읽는다. */
function fmt(ms: number): string {
  return ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}초`;
}

export default function RtoSpark({ runs }: { runs: RecoveryRun[] }) {
  if (runs.length === 0) return null;
  if (runs.length < MIN_RUNS) {
    return <span className={styles.rtoFew}>이력 {runs.length}건 · 표본 부족</span>;
  }
  // 원본 정렬 보증이 없어 시간순 사본으로 그린다
  const recent = [...runs]
    .sort((a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime())
    .slice(-MAX_RUNS);
  const durations = recent.map((r) => r.durationMs);
  const maxMs = Math.max(...durations);
  // 표본을 덮는 최소 단계. 마지막 단계보다 크면 자기 최댓값에 여유를 준다.
  const scaleMs = SCALE_STEPS_MS.find((step) => maxMs <= step) ?? maxMs * 1.2;
  const minMs = Math.min(...durations);
  const failCount = recent.filter((r) => r.verdict === "FAIL").length;
  const width = recent.length * BAR_W + (recent.length - 1) * BAR_GAP;
  const drawH = H - 1 - TOP_RESERVE;
  const label =
    `최근 ${recent.length}회 재생, 최소 ${fmt(minMs)} · 최대 ${fmt(maxMs)}, ` +
    `창 ${fmt(scaleMs)}, 불일치 ${failCount}회`;

  return (
    <svg
      className={styles.rtoSpark}
      width={width}
      height={H}
      viewBox={`0 0 ${width} ${H}`}
      role="img"
      aria-label={label}
    >
      <title>{`창 ${fmt(scaleMs)} 기준`}</title>
      <line className={styles.rtoBase} x1={0} x2={width} y1={H - 0.5} y2={H - 0.5} />
      {recent.map((r, i) => {
        const h = Math.max(2, (r.durationMs / scaleMs) * drawH);
        const x = i * (BAR_W + BAR_GAP);
        const y = H - 1 - h;
        const fail = r.verdict === "FAIL";
        return (
          <g key={r.id}>
            <rect className={styles.rtoBar} data-fail={fail ? "" : undefined} x={x} y={y} width={BAR_W} height={h} />
            {fail && <rect className={styles.rtoFailMark} x={x + BAR_W / 2 - 2} y={y - 6} width={4} height={4} />}
          </g>
        );
      })}
    </svg>
  );
}
