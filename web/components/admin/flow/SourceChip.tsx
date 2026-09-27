/**
 * 무대 머리 줄 맨 왼쪽의 출처. 관제 값 모드는 관제센터와 같은 한 판에서 옮긴 값이라 그 사실을 중립 칩으로 적고,
 * 재생 중에는 관제 판과 다른 세계라 '재생 · 관제 값과 별개' 표식을 크게 둔다(디자인 시스템 §12-12).
 * 실측 모드는 관리 API 가 Prometheus 에서 읽은 값이라는 사실을 적고, 흐름 조회가 실패하거나 값이 10초를 넘기면 '수집 실패 ·
 * N초 전 값'(주의)으로 바꾼다. 크게 보기에서는 화면 머리가 가려져 이 칩이 신선도를 알리는 유일한 자리다.
 */

import StatusShape from "@/components/admin/StatusShape";
import { SimMark } from "@/components/admin/ui";
import type { FlowMode } from "@/lib/admin/flowRun";
import { ago } from "@/lib/admin/timeText";
import { useMeasuredFreshness } from "./MeasuredMark";
import styles from "./flow.module.css";

function MeasuredSource() {
  const f = useMeasuredFreshness();
  if (f.at !== null && (f.failed || f.stale)) {
    return (
      <span className={styles.sourceChip} data-kind="stale">
        <StatusShape kind="triangle" />
        {f.failed ? "수집 실패" : "지연"} · {ago(f.at, f.now)} 값
      </span>
    );
  }
  return (
    <span className={styles.sourceChip}>
      Prometheus 실측 · 2초
      <span className="sr-only"> · 관리 API 가 Prometheus 에서 2초 간격으로 읽은 최근 150초입니다</span>
    </span>
  );
}

export default function SourceChip({ mode }: { mode: FlowMode }) {
  if (mode === "replay") return <SimMark kind="replay" />;
  if (mode === "measured") return <MeasuredSource />;
  return (
    <span className={styles.sourceChip}>
      관제 판 값
      <span className="sr-only"> · 관제센터와 같은 한 판에서 옮긴 값입니다</span>
    </span>
  );
}
