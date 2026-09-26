/**
 * AI 판단 화면의 판단 기록 표(관리자 설계 4-10-2). 판단 기록을 결과 묶음으로 가르고 확신도, 정책 판정, 처리 결과 칸을 만든다.
 * 판단 기록은 결과가 바뀔 때만 남으므로 표 한 줄이 판단 하나다. 화면은 이 규칙만 읽고 따로 판정하지 않는다
 */

import type { AiJudgment } from "./types";

/** 결과 묶음. 조치안을 낸 판단, 확인·관찰, 이상 없음, AI가 멈췄거나 판단에 실패한 기록이다 */
export type JudgmentGroup = "PROPOSAL" | "WATCH" | "CLEAR" | "HALT";

export const JUDGMENT_FILTERS: { id: JudgmentGroup | "ALL"; label: string }[] = [
  { id: "ALL", label: "전체" },
  { id: "PROPOSAL", label: "조치안" },
  { id: "WATCH", label: "확인·관찰" },
  { id: "CLEAR", label: "이상 없음" },
  { id: "HALT", label: "멈춤·오류" },
];

/** 기간 거르기(시간). 기록은 지난 24시간만 남는다 */
export const JUDGMENT_HOURS = [1, 6, 24] as const;

/**
 * 결과 낱말의 묶음. sim의 결과(조치안 n건, 남은 조치안, 자동 실행, 이상 없음, AI 정지, AI 서버 응답 없음)와 서버 판단 목록의
 * 결과(조치 요청, 이상 없음, 모델 출력 형식 오류, 모델 호출 실패, 근거가 오래돼 판단하지 않음)를 함께 읽는다. 나머지(운영자 확인
 * 필요, 조치 효과 관찰, 되돌림 뒤 복구 관찰, 재생의 금지 목록 대조)는 확인·관찰이다
 */
export function judgmentGroup(outcome: string): JudgmentGroup {
  if (/^(남은 )?조치안|^조치 요청|^자동 실행/.test(outcome)) return "PROPOSAL";
  if (outcome === "이상 없음") return "CLEAR";
  if (/^AI 정지|^AI 서버|모델 출력|모델 호출|근거가 오래/.test(outcome)) return "HALT";
  return "WATCH";
}

/**
 * 결과 칸의 형태. 조치안은 AI 표지, 이상 없음은 정상 점, AI 정지·확인 필요·되돌림은 주의, 판단 실패는 장애, 관찰은 무채 빈 원이다.
 * AI 정지는 운영자가 멈춘 주의 상태다(디자인 시스템 §12-3)
 */
export function judgmentMark(outcome: string): { kind: "ai" | "circle" | "triangle" | "square" | "ring"; tone: "ai" | "success" | "warning" | "danger" | "off" } {
  const g = judgmentGroup(outcome);
  if (g === "PROPOSAL") return { kind: "ai", tone: "ai" };
  if (g === "CLEAR") return { kind: "circle", tone: "success" };
  if (g === "HALT") return /^AI 정지/.test(outcome) ? { kind: "triangle", tone: "warning" } : { kind: "square", tone: "danger" };
  if (/확인 필요|되돌림/.test(outcome)) return { kind: "triangle", tone: "warning" };
  return { kind: "ring", tone: "off" };
}

/** 판단의 확신도. 기록이 싣지 않으면 판단 과정의 후보 가운데 가장 높은 값이다. 후보가 없으면 null */
export function judgmentConfidence(j: AiJudgment): number | null {
  if (j.confidencePct !== undefined && j.confidencePct !== null) return j.confidencePct;
  const c = j.trace?.candidates ?? [];
  return c.length === 0 ? null : Math.max(...c.map((x) => x.confidencePct));
}

/**
 * 정책 판정 한 줄. 수동 모드면 모든 후보가 운영자 승인을 기다리고, 자동 모드면 막힌 규칙이 없는 후보 수와 첫 막힌 규칙을 적는다.
 * 판단 과정이 없는 기록(후보 없음, http 목록)은 null이다. http 판단 목록은 검사표를 싣지 않아 상세를 열 때 읽는다
 */
export function policyVerdict(j: AiJudgment): { word: string; blocked: boolean } | null {
  const tr = j.trace;
  if (tr === null || tr.candidates.length === 0) return null;
  if (tr.mode !== "AUTO") return { word: "수동 · 승인 대기", blocked: false };
  const blockedBy = tr.candidates.map((_, i) => tr.checks.find((r) => r.results[i]?.state === "BLOCK")?.label ?? null);
  const free = blockedBy.filter((b) => b === null).length;
  if (free === tr.candidates.length) return { word: "자동 실행 조건 통과", blocked: false };
  const first = blockedBy.find((b) => b !== null) as string;
  return free === 0 ? { word: `자동 막힘 · ${first}`, blocked: true } : { word: `통과 ${free} · 막힘 ${tr.candidates.length - free}`, blocked: true };
}

/** 묶음과 기간으로 거른다. 기간은 판의 시각(now)에서 거꾸로 센다 */
export function filterJudgments(list: AiJudgment[], group: JudgmentGroup | "ALL", hours: number, now: number): AiJudgment[] {
  const from = now - hours * 3_600_000;
  return list.filter((j) => Date.parse(j.at) >= from && (group === "ALL" || judgmentGroup(j.outcome) === group));
}
