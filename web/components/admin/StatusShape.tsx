/**
 * 형태 기호 부품(디자인 시스템 §12-3 v1.14). ●▲■○◌◇▶◔를 글꼴 글자가 아니라 같은 10 격자의 SVG로 그린다.
 * 글꼴마다 ●와 ▲의 크기와 기준선이 달라 같은 줄의 형태가 1~2px씩 들쭉날쭉했다. 넓이를 맞춰 원·네모·세모가
 * 같은 무게로 읽히게 했다. 색은 currentColor라 소비처의 형태 색(data-tone)을 따르고, 뜻은 옆 글자가 싣는다.
 */

import type { ReactNode } from "react";
import styles from "./admin.module.css";

export type StatusShapeKind = "circle" | "triangle" | "square" | "ring" | "dashed" | "diamond" | "play" | "half";

/** 글자 기호를 부품 모양으로 옮긴다. 문장 안의 기호(사건 기록 문장)는 글자로 둔다 */
export const GLYPH_SHAPE: Record<string, StatusShapeKind> = {
  "●": "circle",
  "▲": "triangle",
  "■": "square",
  "○": "ring",
  "◌": "dashed",
  "◇": "diamond",
  "◆": "diamond",
  "▶": "play",
  "◔": "half",
};

const SHAPES: Record<StatusShapeKind, ReactNode> = {
  circle: <circle cx="5" cy="5" r="4" fill="currentColor" />,
  triangle: <path d="M5 0.9 L9.5 8.9 H0.5 Z" fill="currentColor" strokeLinejoin="round" />,
  square: <rect x="1.4" y="1.4" width="7.2" height="7.2" rx="0.9" fill="currentColor" />,
  ring: <circle cx="5" cy="5" r="3.4" fill="none" stroke="currentColor" strokeWidth="1.5" />,
  dashed: <circle cx="5" cy="5" r="3.4" fill="none" stroke="currentColor" strokeWidth="1.4" strokeDasharray="1.8 1.35" />,
  diamond: <path d="M5 0.5 L9.5 5 L5 9.5 L0.5 5 Z" fill="currentColor" />,
  play: <path d="M2 0.9 L9.2 5 L2 9.1 Z" fill="currentColor" />,
  // 반만 채운 원. 경보 보류(끄지 않고 알림만 멈춤)다. 빈 원(중지)과 채운 원(정상) 사이의 뜻이라 모양도 그 사이다
  half: (
    <>
      <circle cx="5" cy="5" r="3.4" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <path d="M5 1.6 A3.4 3.4 0 0 1 5 8.4 Z" fill="currentColor" />
    </>
  ),
};

export default function StatusShape({ kind, size = 10, tone, className }: {
  kind: StatusShapeKind;
  size?: number;
  /** 소비처 CSS가 형태 색을 고르는 표지 */
  tone?: string;
  className?: string;
}) {
  return (
    <svg
      className={`${styles.statusShape}${className ? ` ${className}` : ""}`}
      data-tone={tone}
      data-shape={kind}
      width={size}
      height={size}
      viewBox="0 0 10 10"
      aria-hidden="true"
      focusable="false"
    >
      {SHAPES[kind]}
    </svg>
  );
}
