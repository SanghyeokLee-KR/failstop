/**
 * AI 판단 모델 평가(관리자 설계 4-10-2). 측정 문서 docs/measurements/20260925.md의 '재평가(근거 구조화 뒤)' 최종
 * 회차를 옮긴 정적 기록이다. 측정값이라 '시뮬레이션' 표식 대신 출처와 날짜를 달고, 두 데이터 원천이 같은 값을 쓴다.
 * 로컬 RTX 4090의 결과이고 DGX Spark(ai-1)의 결과가 아니다. 측정 문서가 바뀌면 이 기록을 같은 변경에서 고친다.
 */

import type { ModelEvaluation, ModelEvalRow } from "./types";

export const MODEL_EVALUATION: ModelEvaluation = {
  source: "docs/measurements/20260925.md",
  measuredAt: "2026-09-25 10:37~11:30 KST",
  where: "로컬 RTX 4090 · Ollama 0.34.4",
  promptVersion: "msp-ai/3",
  rows: [
    {
      model: "gpt-oss:20b", think: null, wrongExec: 2, approvalWrong: 3, correct: "98/111", schema: "111/111",
      consistent: "31/37", latency: "5.9초 / 8.5초", honorific: "99/111", outOfCatalog: 1, injection: "21/21", chat: "14/15",
    },
    {
      model: "qwen3.6:35b-a3b", think: "false", wrongExec: 0, approvalWrong: 0, correct: "90/111", schema: "111/111",
      consistent: "37/37", latency: "2.2초 / 3.2초", honorific: "111/111", outOfCatalog: 9, injection: "21/21", chat: "15/15",
    },
  ],
  errorTypes: [
    "근거에 바꿀 이유가 없는데 임계를 민감한 방향으로 옮긴 변경 · gpt-oss:20b의 S05·H03, 2회",
    "카탈로그에 없는 축소 · qwen3.6:35b-a3b 9회, 정책 D2가 막음",
    "봇 부하 과다를 놓침 · qwen3.6:35b-a3b의 H09",
  ],
  next: "병합 뒤 판단 프롬프트가 msp-ai/4로 바뀌어 이 표(msp-ai/3)는 다시 재야 합니다. DGX Spark 평가 예정 · 1순위 qwen3.6:35b-a3b(think=false), 견줄 모델 gpt-oss:120b",
};

/**
 * 표의 행. 모델 선택 기준(ADR 0035)대로 잘못 실행될 조치가 먼저이고 정답, 지연, 같은 입력의 일치가 뒤다. better는 두
 * 모델 가운데 나은 쪽을 가르는 방향이다(낮을수록 좋음 'low', 높을수록 좋음 'high', 가르지 않음 null)
 */
export const MODEL_EVAL_METRICS: { key: keyof ModelEvalRow; label: string; note: string | null; better: "low" | "high" | null }[] = [
  { key: "wrongExec", label: "잘못 실행될 조치", note: "자동 모드의 가장 나쁜 조건에서 정책 엔진을 지나는 오답. 0이어야 합니다", better: "low" },
  { key: "approvalWrong", label: "승인 대기 오답", note: "수동 모드에서 사람이 승인하면 실행될 오답", better: "low" },
  { key: "correct", label: "모델 정답", note: "상황 37개를 세 번씩 부른 111회", better: "high" },
  { key: "schema", label: "출력 스키마 통과", note: null, better: null },
  { key: "consistent", label: "3회 같은 결정", note: "같은 입력에 같은 결정을 낸 상황 수", better: "high" },
  { key: "latency", label: "지연 p50 / p95", note: "시간 상한 30초", better: "low" },
  { key: "honorific", label: "합니다체", note: null, better: "high" },
  { key: "outOfCatalog", label: "카탈로그 밖 조치", note: "정책이 막음", better: "low" },
  { key: "injection", label: "구조화 필드 주입 시험", note: "I01~I07, 21회", better: null },
  { key: "chat", label: "상담 5문항", note: "15회", better: "high" },
];

/** 비교할 수 있는 값. 'n/m'은 비율, '5.9초 / 8.5초'는 앞(p50)의 초, 수는 그대로다 */
export function evalValue(v: ModelEvalRow[keyof ModelEvalRow]): number | null {
  if (typeof v === "number") return v;
  if (v === null) return null;
  const frac = /^(\d+)\/(\d+)$/.exec(v);
  if (frac) return Number(frac[1]) / Number(frac[2]);
  const sec = /^([\d.]+)초/.exec(v);
  return sec ? Number(sec[1]) : null;
}
