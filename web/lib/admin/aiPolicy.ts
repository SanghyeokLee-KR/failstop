/**
 * AI 운영 조치 규칙(ADR 0035). 시뮬레이터가 이 값으로 자동 실행을 판정하고, 화면은 같은 값을
 * 모드 대화상자와 가드레일에 적는다. 한 곳에 두어야 화면이 말하는 한도와 실제 판정이 갈리지 않는다.
 * 2단계에서는 서버 정책 엔진이 같은 규칙을 다시 검사한다. 이 파일은 그 엔진이 아니다.
 */

import type { AiActionKind, AiLimit, AiLimitKey } from "./types";

/** 운영자도 화면에서 바꾸지 못하는 규칙 */
export const AI_RULES = {
  /** 자동 모드 최대 유효 시간 */
  autoMaxHours: 8,
  /** 근거 지표가 이보다 오래되면 자동 실행하지 않는다 */
  evidenceMaxAgeSec: 60,
  /** 자동 조치 뒤 관찰 창 */
  observeSec: 5 * 60,
  /** 조회 노드 증감 쿨다운 */
  readNodesCooldownSec: 10 * 60,
  /** 임계값 변경 쿨다운 */
  thresholdCooldownSec: 30 * 60,
  /** 경보 보류 최대 길이 */
  silenceMaxMin: 30,
  /** 연속 같은 판단 횟수 */
  sameJudgmentStreak: 2,
} as const;

export const AUTO_HOURS = [1, 2, 4, 8] as const;

export type AiLimits = Record<AiLimitKey, number>;

interface AiLimitDef {
  key: AiLimitKey;
  label: string;
  desc: string;
  unit: string;
  initial: number;
  min: number;
  max: number;
  tighterWhenHigher: boolean;
}

/**
 * 운영자가 1인 승인(ops.ai.limits)으로 바꾸는 한도. 초기값은 사용자가 확정한 값이고(2026-09-25, AI 권장값
 * 채택) min~max는 코드에 고정한 하드 범위다. AI는 이 값을 바꾸지 못한다(금지 목록 8).
 */
export const AI_LIMIT_DEFS: readonly AiLimitDef[] = [
  { key: "confidencePct", label: "확신도 기준", desc: "이 값 이상이어야 자동 실행", unit: "%", initial: 80, min: 70, max: 95, tighterWhenHigher: true },
  { key: "autoCap", label: "자동 조치 상한", desc: "자동 모드 1회 활성당. 닿으면 수동 전환", unit: "회", initial: 5, min: 1, max: 10, tighterWhenHigher: false },
  { key: "thresholdPerItem", label: "임계값 항목당 횟수", desc: "자동 모드 1회 활성당 한 항목의 자동 변경", unit: "회", initial: 2, min: 1, max: 3, tighterWhenHigher: false },
  { key: "botCooldownMin", label: "봇 부하 쿨다운", desc: "봇 부하 줄임 뒤 다음 줄임까지", unit: "분", initial: 10, min: 5, max: 60, tighterWhenHigher: true },
  { key: "backupDiskFreePct", label: "백업 디스크 여유", desc: "이 값 이상이어야 백업 즉시 실행 제안", unit: "%", initial: 30, min: 20, max: 50, tighterWhenHigher: true },
];

export const AI_LIMITS_DEFAULT: AiLimits = Object.fromEntries(AI_LIMIT_DEFS.map((d) => [d.key, d.initial])) as AiLimits;

/** 판독한 한도 목록을 값 표로 바꾼다. 원천이 없으면 확정 초기값을 쓴다 */
export function limitsOf(items: AiLimit[] | null | undefined): AiLimits {
  if (!items) return AI_LIMITS_DEFAULT;
  return { ...AI_LIMITS_DEFAULT, ...Object.fromEntries(items.map((l) => [l.key, l.value])) };
}

export const AI_ACTION_LABEL: Record<AiActionKind, string> = {
  READ_NODES: "조회 노드 증감",
  THRESHOLD: "임계값 변경",
  SILENCE: "경보 보류",
  BOT_LOAD: "시장 봇 부하 줄임",
  BACKUP_NOW: "백업 즉시 실행",
};

/** 조치 종류별 쿨다운. 운영자가 실행한 조치도 쿨다운을 건다. 사람이 바꾼 직후 AI가 반대로 되돌리지 않게 한다 */
export function cooldownSec(kind: AiActionKind, limits: AiLimits = AI_LIMITS_DEFAULT): number {
  switch (kind) {
    case "READ_NODES":
      return AI_RULES.readNodesCooldownSec;
    case "THRESHOLD":
      return AI_RULES.thresholdCooldownSec;
    case "BOT_LOAD":
      return limits.botCooldownMin * 60;
    case "SILENCE":
    case "BACKUP_NOW":
      return 0;
  }
}

/** 허용 목록. 모두 되돌릴 수 있고 데이터와 무관하다 */
export function allowedRules(limits: AiLimits = AI_LIMITS_DEFAULT): { kind: AiActionKind; rule: string }[] {
  return [
    { kind: "READ_NODES", rule: "최소~최대(2~3대) 안에서 1회 ±1, 쿨다운 10분" },
    { kind: "THRESHOLD", rule: `항목별 허용 범위 안, 1회 변경폭 제한, 쿨다운 30분, 자동 모드 1회당 항목별 ${limits.thresholdPerItem}회` },
    { kind: "SILENCE", rule: "최대 30분, 장애 등급 불가, 같은 경보 연속 보류 불가" },
    { kind: "BOT_LOAD", rule: `부하를 줄이는 방향만, 1회 최대 절반, 쿨다운 ${limits.botCooldownMin}분` },
    { kind: "BACKUP_NOW", rule: `Databasus 백업, 하루 1회, 저장 디스크 여유 ${limits.backupDiskFreePct}% 이상` },
  ];
}

/** 어느 모드에서도 AI가 하지 않는 조치 */
export const AI_FORBIDDEN: string[] = [
  "DB 승격·페일오버·복제 재구성",
  "매칭 리더 전환·리스 조작·파티션 재배정",
  "데이터·로그·백업 삭제, 스키마 변경",
  "보안 그룹·IAM·네트워크·인증서·비밀 값 변경",
  "앱·DB 재시작과 중지",
  "회원·자산·주문·출금 조작",
  "비용이 드는 자원 생성(ASG 최대 초과 포함)",
  "자기 권한 변경(자동 모드 켜기, 한도 늘리기)",
];

/** 자동 실행 전에 모두 맞아야 하는 조건 */
export function autoPreconditions(limits: AiLimits = AI_LIMITS_DEFAULT): string[] {
  return [
    `근거 지표 수집 ${AI_RULES.evidenceMaxAgeSec}초 이내. 미수집이면 실행하지 않음`,
    `확신도 ${limits.confidencePct}% 이상과 연속 ${AI_RULES.sameJudgmentStreak}회 같은 판단`,
    `한 번에 한 조치, 조치 뒤 ${AI_RULES.observeSec / 60}분 관찰 창`,
    `자동 모드 1회 활성당 자동 조치 ${limits.autoCap}회 상한. 넘으면 수동 전환과 Teams 알림`,
    "관찰 창 안에서 악화하면 자동 되돌림, 수동 전환, Teams 알림",
    "AI 무응답이나 출력 형식 불일치면 조치 없음. 기존 경보와 ASG 정책은 계속",
  ];
}
