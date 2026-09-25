/**
 * AI 운영 조치 규칙(ADR 0035). 시뮬레이터가 이 값으로 자동 실행을 판정하고, 화면은 같은 값을
 * 모드 대화상자와 가드레일에 적는다. 한 곳에 두어야 화면이 말하는 한도와 실제 판정이 갈리지 않는다.
 * 2단계에서는 서버 정책 엔진이 같은 규칙을 다시 검사한다. 이 파일은 그 엔진이 아니다.
 */

import type { AiActionKind, AiLimit, AiLimitKey, ThresholdKey } from "./types";

/** 운영자도 화면에서 바꾸지 못하는 규칙 */
export const AI_RULES = {
  /** 자동 모드 최대 유효 시간 */
  autoMaxHours: 8,
  /** 근거 지표가 이보다 오래되면 자동 실행하지 않는다 */
  evidenceMaxAgeSec: 60,
  /** 자동 조치 뒤 관찰 창 */
  observeSec: 5 * 60,
  /** app-3 켜기·끄기(앱 계층 대수) 쿨다운 */
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

/**
 * 같은 조치를 세는 열쇠. 감사 기록의 대상은 조치안 제목이다. app-3 켜기와 끄기는 서로 다른
 * 조치라 제목의 방향으로 가른다
 */
export function actionKey(kind: AiActionKind, title: string): string {
  if (kind !== "READ_NODES") return kind;
  // 켜기(앱 계층 +1)와 끄기(−1)는 되돌리는 방향이 달라 따로 센다. 옛 기록의 '+1' 제목도 켜기로 읽는다
  return title.includes("켜기") || title.includes("+1") ? "READ_NODES:+1" : "READ_NODES:-1";
}

export const AI_ACTION_LABEL: Record<AiActionKind, string> = {
  READ_NODES: "app-3 켜기·끄기",
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
    { kind: "READ_NODES", rule: "조회 전용 app-3 켜기·끄기(앱 계층 2~3대), 쿨다운 10분" },
    { kind: "THRESHOLD", rule: `항목별 허용 범위 안, 1회 변경폭 제한, 쿨다운 30분, 자동 모드 1회당 항목별 ${limits.thresholdPerItem}회. 자동은 더 민감하게 하는 방향만, 완화는 승인으로만` },
    { kind: "SILENCE", rule: "최대 30분, 장애 등급 불가, 같은 경보 연속 보류 불가" },
    { kind: "BOT_LOAD", rule: `bot-1 CPU가 봇 용량 보호 기준 이상일 때만, 부하를 줄이는 방향만, 1회 최대 절반, 쿨다운 ${limits.botCooldownMin}분` },
    { kind: "BACKUP_NOW", rule: `Databasus 백업, 하루 1회, 저장 디스크 여유 ${limits.backupDiskFreePct}% 이상` },
  ];
}

/**
 * 임계값마다 '더 민감한(더 조심하는)' 방향. -1은 값을 내릴수록 경보·증설·봇 보호가 먼저 걸린다는 뜻이고, +1은 올릴수록
 * 먼저 걸린다는 뜻이다(여유·잔량 '미만이면 경보' 항목). 자동 모드는 이 방향의 변경만 실행한다(ADR 0035, 사용자 확정
 * 2026-09-25). 경보 문장 속 명령문을 모델이 따라 임계값을 완화한 평가 사례가 있어, 완화는 사람의 승인으로만 한다
 */
export const STRICTER: Record<ThresholdKey, -1 | 1> = {
  "read.scale_out_cpu": -1,
  "read.scale_in_cpu": -1,
  "host.cpu_alert": -1,
  "host.mem_alert": -1,
  "host.disk_free_alert": 1,
  "db.replication_lag_alert": -1,
  "backup.delay_alert": -1,
  "bot.capacity_guard": -1,
  "host.cpu_credit_alert": 1,
};

/** 임계값 변경이 더 민감한 방향인가. 같은 값이면 바뀌는 것이 없어 민감한 쪽으로 보지 않는다 */
export function isStricter(key: ThresholdKey, from: number, to: number): boolean {
  return Math.sign(to - from) === STRICTER[key];
}

/**
 * 조치 카드와 조치 확인이 '자동 실행 가능'이라고 적을 수 있는가. 자동 모드이고, 자동으로 할 수 있는 조치(방향·봇 보호,
 * AiProposal.autoAllowed)이며, 확신도가 기준에 닿아야 한다. 하나라도 아니면 '승인 필요'다
 */
export function canAutoRun(p: { autoAllowed: boolean; confidencePct: number }, mode: "MANUAL" | "AUTO" | "STOPPED", limits: AiLimits): boolean {
  return mode === "AUTO" && p.autoAllowed && p.confidencePct >= limits.confidencePct;
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
    "임계값은 더 민감하게 하는 방향만 자동. 완화 방향은 자동 모드에서도 승인으로만",
    "봇 부하 줄임은 bot-1 CPU가 봇 용량 보호 기준 이상일 때만 자동",
  ];
}
