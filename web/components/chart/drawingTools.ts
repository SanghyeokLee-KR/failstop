/**
 * drawingTools.ts. 그리기 도구 → klinecharts overlay 매핑(설계 §3.4).
 * 색·선폭은 chartTheme의 overlay 스타일(브랜드 중립색)에서 온다. 방향색 미사용.
 * 그리기 객체는 세션 로컬 상태이며 영속화는 범위 밖이다.
 */

export interface DrawingTool {
  key: string;
  /** klinecharts 내장 overlay 이름 */
  overlay: string;
  label: string;
}

export const DRAWING_TOOLS: DrawingTool[] = [
  { key: "segment", overlay: "segment", label: "추세선" },
  { key: "priceLine", overlay: "priceLine", label: "수평선" },
  { key: "fibonacciLine", overlay: "fibonacciLine", label: "피보나치" },
];
