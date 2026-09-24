/**
 * AI 운영 조치 규칙(ADR 0035). 시뮬레이터가 이 값으로 자동 실행을 판정하고, 화면은 같은 값을
 * 모드 대화상자와 가드레일에 적는다. 한 곳에 두어야 화면이 말하는 한도와 실제 판정이 갈리지 않는다.
 * 2단계에서는 서버 정책 엔진이 같은 규칙을 다시 검사한다. 이 파일은 그 엔진이 아니다.
 */

import type { AiActionKind } from "./types";

/** 사용자가 정한 값. 초기값과 섞지 않도록 따로 둔다 */
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

/** ADR 0035가 초기값으로 적은 값. 2단계 시험 뒤 조정한다 */
export const AI_INITIAL = {
  autoDailyCap: 5,
  confidencePct: 80,
  thresholdDailyPerItem: 2,
  botLoadCooldownSec: 10 * 60,
  /** 백업 저장 호스트 디스크 여유 하한 */
  backupDiskFreePct: 30,
} as const;

export const AUTO_HOURS = [1, 2, 4, 8] as const;

export const AI_ACTION_LABEL: Record<AiActionKind, string> = {
  READ_NODES: "조회 노드 증감",
  THRESHOLD: "임계값 변경",
  SILENCE: "경보 보류",
  BOT_LOAD: "시장 봇 부하 줄임",
  BACKUP_NOW: "백업 즉시 실행",
};

/** 조치 종류별 쿨다운. 운영자가 실행한 조치도 쿨다운을 건다. 사람이 바꾼 직후 AI가 반대로 되돌리지 않게 한다 */
export function cooldownSec(kind: AiActionKind): number {
  switch (kind) {
    case "READ_NODES":
      return AI_RULES.readNodesCooldownSec;
    case "THRESHOLD":
      return AI_RULES.thresholdCooldownSec;
    case "BOT_LOAD":
      return AI_INITIAL.botLoadCooldownSec;
    case "SILENCE":
    case "BACKUP_NOW":
      return 0;
  }
}

/** 허용 목록. 모두 되돌릴 수 있고 데이터와 무관하다 */
export const AI_ALLOWED: { kind: AiActionKind; rule: string }[] = [
  { kind: "READ_NODES", rule: "최소~최대(2~3대) 안에서 1회 ±1, 쿨다운 10분" },
  { kind: "THRESHOLD", rule: "항목별 허용 범위 안, 1회 변경폭 제한, 쿨다운 30분, 항목당 하루 2회" },
  { kind: "SILENCE", rule: "최대 30분, 장애 등급 불가, 같은 경보 연속 보류 불가" },
  { kind: "BOT_LOAD", rule: "부하를 줄이는 방향만, 1회 최대 절반" },
  { kind: "BACKUP_NOW", rule: "Databasus 백업, 하루 1회, 저장 디스크 여유 30% 이상" },
];

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
export const AI_AUTO_PRECONDITIONS: string[] = [
  `근거 지표 수집 ${AI_RULES.evidenceMaxAgeSec}초 이내. 미수집이면 실행하지 않음`,
  `확신도 ${AI_INITIAL.confidencePct}% 이상과 연속 ${AI_RULES.sameJudgmentStreak}회 같은 판단`,
  `한 번에 한 조치, 조치 뒤 ${AI_RULES.observeSec / 60}분 관찰 창`,
  `하루 자동 조치 ${AI_INITIAL.autoDailyCap}회 상한. 넘으면 수동 전환과 Teams 알림`,
  "관찰 창 안에서 악화하면 자동 되돌림, 수동 전환, Teams 알림",
  "AI 무응답이나 출력 형식 불일치면 조치 없음. 기존 경보와 ASG 정책은 계속",
];
