"use client";

/**
 * 비용 막대(디자인 시스템 §12-15 CostBar). 한 줄 막대의 전체 길이가 크레딧 총액이다. 앞에서부터 계획 소진(운영 종료일까지)은 무채
 * 채움, app-3을 늘 켜면 더해질 몫은 같은 색의 사선 무늬(판독용 무늬), 남는 몫은 트랙이다. 눈금은 오늘(2px 잉크)과 운영
 * 종료일(1px 점선)이다. 계획 소진이 크레딧을 넘으면 넘는 몫을 막대 오른쪽 끝 밖으로 주의 색 채움으로 그린다(주의 색은 그때만 쓴다).
 * 무채 채움은 가로 배율(scaleX)로 그린다(§12-4 움직이는 속성). 사선 무늬 몫은 배율로 늘리면 무늬가 찌그러져 폭으로 그린다.
 */

import { usd } from "@/lib/admin/cost";
import StatusShape from "./StatusShape";
import styles from "./CostBar.module.css";

export default function CostBar({ credit, plan, extra, today, label }: {
  /** 크레딧 총액(셈 단위) */
  credit: number;
  /** 운영 종료일까지의 계획 소진 */
  plan: number;
  /** app-3을 늘 켜면 더해질 몫 */
  extra: number;
  /** 오늘까지 쓴 계획 금액 */
  today: number;
  label: string;
}) {
  const p = (v: number) => Math.max(0, Math.min(1, v / credit));
  const over = Math.max(0, plan - credit);
  return (
    <div className={styles.wrap}>
      <div className={styles.bar} role="img" aria-label={label}>
        <span className={styles.plan} style={{ transform: `scaleX(${p(plan)})` }} />
        {/* 사선 무늬는 가로 배율로 늘리면 무늬가 찌그러져 폭으로 그린다. 이 막대는 움직이지 않는다 */}
        <span className={styles.extra} style={{ left: `${p(plan) * 100}%`, width: `${(p(plan + extra) - p(plan)) * 100}%` }} />
        <span className={styles.today} style={{ left: `${p(today) * 100}%` }} />
        <span className={styles.end} style={{ left: `${p(plan) * 100}%` }} />
        {over > 0 && <span className={styles.over} style={{ transform: `scaleX(${Math.min(1, over / credit)})` }} />}
      </div>
      {over > 0 && (
        <span className={styles.overWord}>
          <StatusShape kind="triangle" className={styles.overShape} />
          종료일 전 소진 예상 · 넘는 몫 {usd(over)}
        </span>
      )}
    </div>
  );
}
