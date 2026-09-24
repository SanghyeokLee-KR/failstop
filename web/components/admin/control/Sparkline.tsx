"use client";

/**
 * 상태 요약 칸의 작은 추세선. 차트 라이브러리 없이 SVG로 그린다. 선은 무채이고 마지막 점만 크롬 악센트다.
 * 칸이 목표를 벗어나면 마지막 점이 상태색을 받는다. 가리키면 그 시점의 값을 보이고, 같은 값은 보조기술용
 * 요약(최저·최고·마지막)과 칸의 수치로도 읽힌다. 가리키기는 값을 더할 뿐 값을 숨기지 않는다.
 *
 * 점이 많으면 60점 이하로 줄여 그린다(lib/admin/series.ts). 선은 묶음마다 평균에서 가장 먼 값을 남겨 순간
 * 치솟음을 지우지 않고, 건수 막대는 묶음의 합을 남긴다.
 */

import { memo, useState } from "react";
import { downsample } from "@/lib/admin/series";
import styles from "./control.module.css";

const VIEW_H = 100;

function Sparkline({ points: raw, kind = "line", tone, format, stepMin, label, minSpan, zero = false }: {
  points: (number | null)[];
  /** 사건처럼 드문 일은 칸마다 막대로 그린다 */
  kind?: "line" | "bars";
  /**
   * 세로 축이 덮는 최소 폭(값 단위). 최저·최고로만 축을 잡으면 1ms 흔들림도 칸 높이 전체로 늘어나
   * 없는 변동을 그린다. 지표마다 의미 있는 변화의 크기를 준다
   */
  minSpan?: number;
  /** 건수처럼 0에서 시작해야 읽히는 선 */
  zero?: boolean;
  tone?: "warning" | "danger";
  format: (v: number) => string;
  /** 점 사이 간격(분) */
  stepMin: number;
  /** 보조기술 요약의 머리말. 무엇의 몇 시간 추세인지 적는다 */
  label: string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const { points, factor } = downsample(raw, undefined, kind === "bars" ? "sum" : "extreme");
  const vals = points.filter((v): v is number => v !== null);
  if (vals.length < 2) {
    return (
      <span className={styles.spark} data-empty="">
        <span className="sr-only">{label} 기록 없음</span>
      </span>
    );
  }
  const n = points.length;
  let lo = kind === "bars" || zero ? 0 : Math.min(...vals);
  let hi = kind === "bars" || zero ? Math.max(1, ...vals) : Math.max(...vals);
  if (kind === "line" && !zero && minSpan !== undefined && hi - lo < minSpan) {
    const mid = (hi + lo) / 2;
    lo = mid - minSpan / 2;
    hi = mid + minSpan / 2;
  }
  const span = hi - lo;
  // 축 폭을 받지 못한 선은 변화가 거의 없을 때 가운데 눕힌다. 작은 흔들림을 끝까지 늘리면 없는 변동을 그린다
  const flat = kind === "line" && minSpan === undefined && !zero && span < Math.max(Math.abs(hi) * 0.002, 1e-6);
  const y = (v: number) =>
    flat ? VIEW_H / 2 : kind === "bars" ? VIEW_H - (v / hi) * (VIEW_H - 8) : VIEW_H - 10 - ((v - lo) / span) * (VIEW_H - 20);
  const x = (i: number) => (i / (n - 1)) * 100;

  let path = "";
  points.forEach((v, i) => {
    if (v === null) return;
    path += `${path === "" || points[i - 1] === null ? "M" : "L"}${x(i).toFixed(2)} ${y(v).toFixed(2)} `;
  });
  const lastIdx = points.length - 1 - [...points].reverse().findIndex((v) => v !== null);
  const last = points[lastIdx] as number;
  // 가리킨 점의 시각. 마지막 점을 지금으로 보고 거슬러 센다. 가리킬 때만 읽으므로 시계를 타지 않는다
  const at = (i: number) => {
    const d = new Date(Date.now() - (n - 1 - i) * stepMin * factor * 60_000);
    return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  };

  const onMove = (e: React.PointerEvent<HTMLSpanElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    const i = Math.round(((e.clientX - r.left) / r.width) * (n - 1));
    setHover(Math.max(0, Math.min(n - 1, i)));
  };
  const hv = hover !== null ? points[hover] : null;

  return (
    <span className={styles.spark} data-tone={tone} onPointerMove={onMove} onPointerLeave={() => setHover(null)}>
      <svg className={styles.sparkSvg} viewBox={`0 0 100 ${VIEW_H}`} preserveAspectRatio="none" aria-hidden="true" focusable="false">
        {kind === "bars" && <line className={styles.sparkBase} x1={0} x2={100} y1={VIEW_H} y2={VIEW_H} vectorEffect="non-scaling-stroke" />}
        {kind === "bars" ? (
          points.map((v, i) =>
            v === null || v === 0 ? null : (
              <line key={i} className={styles.sparkBar} x1={x(i)} x2={x(i)} y1={VIEW_H} y2={y(v)} vectorEffect="non-scaling-stroke" />
            ),
          )
        ) : (
          <path className={styles.sparkLine} d={path} vectorEffect="non-scaling-stroke" />
        )}
        {hover !== null && <line className={styles.sparkCursor} x1={x(hover)} x2={x(hover)} y1={0} y2={VIEW_H} vectorEffect="non-scaling-stroke" />}
      </svg>
      {/* 점은 SVG 밖 HTML로 둔다. 가로로 늘인 SVG 안의 원은 타원이 된다 */}
      {kind === "line" && <span className={styles.sparkDot} style={{ left: `${x(lastIdx)}%`, top: `${y(last)}%` }} aria-hidden="true" />}
      {hover !== null && hv !== null && (
        <span className={`${styles.sparkTip} num`} style={{ left: `${Math.min(84, Math.max(16, x(hover)))}%` }} aria-hidden="true">
          {at(hover)} · <b>{format(hv)}</b>
        </span>
      )}
      <span className="sr-only">
        {label} 최저 {format(Math.min(...vals))}, 최고 {format(Math.max(...vals))}, 마지막 {format(last)}
      </span>
    </span>
  );
}

/** 점 배열이 그대로면(opsCenter가 같은 참조를 지킨다) 다시 그리지 않는다 */
export default memo(Sparkline);
