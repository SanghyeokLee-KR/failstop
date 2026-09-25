/**
 * 담당자·연락 체계와 호출 정책(관리자 설계 4-10-2·4-10-7). sim의 예시 명부와 당번 교대다. 관제 시뮬레이터(사건 담당
 * 자동 배정과 단계별 호출)와 관리 어댑터의 연락 체계 조회(getOnCall)가 이 한 벌을 읽어 두 자리가 같은 사람을 적는다.
 * http는 명부 원천(GET /admin/v1/msp/oncall, 3라운드 계약 초안)이 없어 이 값을 쓰지 않고 원천 연결 전이다.
 *
 * 역할 이름은 보고 서비스의 권고 담당 역할과 같다(deploy/reporting/intervention.py). 담당은 사건을 맡은 사람이고
 * 권고 담당 역할은 보고서가 권하는 역할이라 서로 다르다.
 */

import { isoNow } from "../format";
import { ESCALATION_LEVEL_WORD } from "./opsDetail";
import { weekStartKst } from "./timeText";
import type { EscalationLevel, EscalationStep, OnCallDesk, OnCallEntry } from "./types";

/** 당번이 맡는 역할. 1차 호출을 받고 새 사건의 담당으로 자동 배정된다 */
export const ONDUTY_ROLE = "서비스 운영 담당";

export const ONCALL_ROSTER: OnCallEntry[] = [
  { role: "총괄 · DB 운영 담당", person: "김수퍼", operatorId: "op-01", channel: "Teams 운영 채널 멘션 · 당번 호출", hours: "평일 09~18시 · 야간은 당번" },
  { role: "Linux·관측 운영 담당", person: "이오퍼", operatorId: "op-02", channel: "Teams 운영 채널 멘션", hours: "평일 09~18시" },
  { role: ONDUTY_ROLE, person: "당번 교대", operatorId: null, channel: "당번 호출 · 사건 보고 P1·P2", hours: "야간·주말 주 단위 교대" },
];

/**
 * 호출 정책. 발생 뒤 운영자의 확인·결정이 없으면 5·15·30분에 1차(당번), 2차(Linux·관측), 책임자(총괄)를 호출한다.
 * 누구든 확인·결정하면 다음 호출을 멈추고, 해제되면 멈춘다. sim은 밖으로 보내지 않고 사건 기록에만 남긴다
 */
export const ESCALATION_POLICY: EscalationStep[] = [
  { level: 1, afterMin: 5, role: ONDUTY_ROLE, channel: "Teams 멘션 · 당번 호출" },
  { level: 2, afterMin: 15, role: "Linux·관측 운영 담당", channel: "Teams 운영 채널 멘션" },
  { level: 3, afterMin: 30, role: "총괄 · DB 운영 담당", channel: "Teams 멘션 · 당번 호출" },
];

/** 호출 단계의 이름. 화면(opsDetail)과 사건 기록이 같은 말을 쓴다 */
export const LEVEL_WORD: Record<EscalationLevel, string> = ESCALATION_LEVEL_WORD;

/** 당번 교대 순서. 주마다(월요일 09:00 KST) 한 사람씩 돌아간다 */
const ROTATION: { person: string; operatorId: string }[] = [
  { person: "이오퍼", operatorId: "op-02" },
  { person: "김수퍼", operatorId: "op-01" },
];

const WEEK_MS = 7 * 86_400_000;
/** 교대의 기준 주. 2026-01-05(월) 09:00 KST다 */
const ROTATION_EPOCH = Date.UTC(2026, 0, 5, 0, 0, 0);

/** 그 시각의 당번 */
export function onDutyAt(ms: number): { person: string; operatorId: string; from: number; until: number } {
  const from = weekStartKst(ms);
  const n = Math.floor((from - ROTATION_EPOCH) / WEEK_MS);
  const who = ROTATION[((n % ROTATION.length) + ROTATION.length) % ROTATION.length];
  return { ...who, from, until: from + WEEK_MS };
}

/** 호출 한 단계가 부르는 사람. 1차는 그때의 당번이고 나머지는 명부의 역할 담당이다 */
export function pageTarget(step: EscalationStep, at: number): { person: string | null; operatorId: string | null } {
  if (step.role === ONDUTY_ROLE) {
    const d = onDutyAt(at);
    return { person: d.person, operatorId: d.operatorId };
  }
  const r = ONCALL_ROSTER.find((x) => x.role === step.role);
  return { person: r?.person ?? null, operatorId: r?.operatorId ?? null };
}

/** 관제 판의 당번 칸. 명부, 호출 정책, 지금 당번, 이번 주와 다음 주 교대다 */
export function oncallDesk(now: number): OnCallDesk {
  const cur = onDutyAt(now);
  const next = onDutyAt(cur.until);
  return {
    roster: ONCALL_ROSTER.map((r) => ({ ...r })),
    policy: ESCALATION_POLICY.map((s) => ({ ...s })),
    onDuty: { role: ONDUTY_ROLE, person: cur.person, operatorId: cur.operatorId, until: isoNow(cur.until) },
    rotation: [cur, next].map((w) => ({ person: w.person, operatorId: w.operatorId, from: isoNow(w.from), until: isoNow(w.until) })),
  };
}
