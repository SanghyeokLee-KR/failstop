/**
 * 정책 검사표 한 칸의 형태(디자인 시스템 §12-13의 3 v1.21). 통과는 무채 체크, 막음은 장애 네모, 자동이면 막힘과 승인 대기는 흐린
 * 빈 원, 해당 없음은 짧은 줄이다. AI 판단 상세와 시나리오의 정책 엔진 판정 칸이 같은 형태를 쓴다
 */

import type { CheckView } from "@/lib/admin/aiPolicy";
import StatusShape from "../StatusShape";

export default function CheckMark({ view, className }: { view: CheckView; className?: string }) {
  if (view === "na") return <span aria-hidden="true" className={className}>-</span>;
  const kind = view === "pass" ? "check" : view === "block" ? "square" : "ring";
  return <StatusShape kind={kind} className={className} />;
}
