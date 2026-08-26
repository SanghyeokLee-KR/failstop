/**
 * 복구 RTO 미니 막대(복구 이력 패널 헤더 계기). 최근 20건을 시간순으로 새긴다.
 * PASS 무채·FAIL danger + 상단 ■ 마커(색 단독 아님). 수치 판독은 아래 표가 주 채널이라
 * 요약 aria-label만 낭독한다.
 */

import type { RecoveryRun } from "@/lib/admin/types";
import styles from "./admin.module.css";

const MAX_RUNS = 20;
const BAR_W = 6;
const BAR_GAP = 4;
const H = 40;
// FAIL ■ 마커가 최고 막대 위에 겹치지 않게 상단 존을 예약한다
const TOP_RESERVE = 8;

export default function RtoSpark({ runs }: { runs: RecoveryRun[] }) {
  if (runs.length === 0) return null;
  // 원본 정렬 보증이 없어 시간순 사본으로 그린다
  const recent = [...runs]
    .sort((a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime())
    .slice(-MAX_RUNS);
  const durations = recent.map((r) => r.durationMs);
  const maxMs = Math.max(...durations, 1);
  const minSec = (Math.min(...durations) / 1000).toFixed(1);
  const maxSec = (Math.max(...durations) / 1000).toFixed(1);
  const failCount = recent.filter((r) => r.verdict === "FAIL").length;
  const width = recent.length * BAR_W + (recent.length - 1) * BAR_GAP;
  const label = `최근 ${recent.length}회 복구, 최소 ${minSec}초 · 최대 ${maxSec}초, FAIL ${failCount}회`;

  return (
    <svg
      className={styles.rtoSpark}
      width={width}
      height={H}
      viewBox={`0 0 ${width} ${H}`}
      role="img"
      aria-label={label}
    >
      <line className={styles.rtoBase} x1={0} x2={width} y1={H - 0.5} y2={H - 0.5} />
      {recent.map((r, i) => {
        const h = Math.max(2, (r.durationMs / maxMs) * (H - 1 - TOP_RESERVE));
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
