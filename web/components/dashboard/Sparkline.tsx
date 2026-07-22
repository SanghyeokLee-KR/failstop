/**
 * Sparkline: 미니 추세선. 시장 요약 스트립의 대표 코인 카드용.
 * 색은 방향 토큰만 소비(--color-up/down/flat, dir-hero 경로). 하드코딩 hex 없음.
 * 그래픽 요소라 접근성 라벨은 부모 카드가 텍스트로 대체 제공한다.
 */

import type { Direction } from "@/lib/types";

interface Props {
  points: number[];
  dir: Direction;
  width?: number;
  height?: number;
}

export default function Sparkline({ points, dir, width = 96, height = 36 }: Props) {
  if (points.length < 2) {
    return <svg width={width} height={height} aria-hidden="true" />;
  }

  const min = Math.min(...points);
  const max = Math.max(...points);
  const span = max - min || 1; // 평탄 구간에서 0 나눗셈 방지
  const stepX = width / (points.length - 1);
  const pad = 2; // 선 두께가 잘리지 않도록 상하 여백

  const d = points
    .map((p, i) => {
      const x = i * stepX;
      const y = pad + (1 - (p - min) / span) * (height - pad * 2);
      return `${i === 0 ? "M" : "L"}${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(" ");

  return (
    <svg
      className="dir-hero"
      data-dir={dir}
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <path d={d} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
