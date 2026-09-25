/**
 * 비용 계획(관리자 설계 4-10-5). 두 원천이 같은 정적 입력이고 app-3 가동 시간만 관제 판에서 읽는다. 금액은 계획 계산이고 실제
 * 청구액은 AWS Cost Explorer를 읽지 않아 두 계정 모두 원천 연결 전이다. 통화를 서로 바꾸지 않는다(환율 근거가 없다).
 *
 * 금액은 부동소수점으로 셈하지 않는다. 입력은 십진 문자열이고, 1만분의 1 달러(정수)로 옮겨 셈하며 화면은 센트로 반올림해 소수
 * 둘째 자리로 적는다.
 */

import { kstDay } from "./timeText";
import type { CostPlan } from "./types";

/** 1달러의 셈 단위(1만분의 1 달러). 앱-3 단가 $0.0335/시간이 정수로 든다 */
const UNIT = 10_000;
const DAY_MS = 86_400_000;

/**
 * 계획 입력. 크레딧 $163.62는 2026-09-19 콘솔에서 확인한 예상 잔액(사용자 확정 2026-09-26)이고 운영 기간은 2026-09-25 계획 입력이며, 하루 $6.73·$7.53은 같은 날의 계획 계산이다(앱 2대, DB t3.medium
 * 2대, bot-1, ALB). app-3 단가는 AWS 작업 안내의 '30일 비용 계산' 단가(t3.small, gp3 20GiB, 공인 IPv4 한 개)다. 하루 $6.73의 항목별
 * 계산 내역(볼륨 크기, bot-1 규격, 공인 IPv4 수)은 저장소에 없어 항목별 금액을 적지 않는다(관리자 설계 7-10 (n))
 */
export const COST_PLAN: CostPlan = {
  creditUsd: "163.62",
  creditNote: "2026-09-19 콘솔 확인 잔액 · 사용자 확정 2026-09-26",
  startDay: "2026-10-02",
  endDay: "2026-10-16",
  dailyUsd: "6.73",
  dailyWithApp3Usd: "7.53",
  app3HourlyUsd: "0.0335",
  excluded: ["데이터 전송", "CloudWatch", "S3·EBS 스냅숏", "세금"],
  school: {
    monthlyKrw: 400_000,
    note: "사용자가 정한 운영 예산입니다. 지급·정산 조건과 실제 청구액은 확인하지 않았습니다",
    reference: { label: "학교 4대 30일 계산값(2026-09-19 단가)", usd: "102.05" },
  },
};

/** 계획 구성(항목별 금액 없이). 계산 내역이 저장소에 없어 무엇이 들었는지만 적는다 */
export const PLAN_PARTS = ["앱 app-1·app-2(t3.small)", "DB Primary·Standby(t3.medium)", "bot-1(시장 봇·관측)", "ALB"];

export const COST_SOURCE = "AWS Cost Explorer(개인 계정 GET /admin/v1/msp/cost 초안 · 학교 계정은 운영자 영역)";

/** 십진 문자열의 달러를 셈 단위로. 소수 넷째 자리까지 받고 그 밖의 꼴은 계획 입력의 오류라 던진다 */
export function toUnits(usd: string): number {
  const m = /^(\d+)(?:\.(\d{1,4}))?$/.exec(usd);
  if (!m) throw new Error(`달러 입력 꼴이 아님: ${usd}`);
  return Number(m[1]) * UNIT + Number((m[2] ?? "").padEnd(4, "0"));
}

/** 셈 단위를 센트로 반올림한 달러 글자('$100.95') */
export function usd(units: number): string {
  const cents = Math.round(units / 100);
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(cents);
  return `${sign}$${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`;
}

/** 원 글자('400,000원') */
export function krw(v: number): string {
  return `${v.toLocaleString("ko-KR")}원`;
}

const dayMs = (day: string) => Date.parse(`${day}T00:00:00+09:00`);
const addDays = (day: string, n: number) => kstDay(dayMs(day) + n * DAY_MS + 12 * 3_600_000);

export interface CostView {
  /** 운영 기간(양 끝 포함) */
  days: number;
  credit: number;
  daily: number;
  dailyWithApp3: number;
  planTotal: number;
  planWithApp3: number;
  remain: number;
  remainWithApp3: number;
  /** 크레딧을 넘는 날(착수일부터 셈). 운영 기간 안이면 운영 종료 전에 소진한다 */
  exhaustDay: string;
  exhaustDayWithApp3: string;
  /** 착수 전, 운영 중, 끝남 */
  phase: "BEFORE" | "RUNNING" | "AFTER";
  /** 오늘까지 쓴 계획 금액(착수 전이면 0, 끝났으면 계획 합) */
  spentToDate: number;
}

/** 크레딧을 넘는 날. 착수일을 1일째로 세어 하루 비용을 쌓다가 크레딧을 넘는 날이다 */
function exhaustOn(start: string, credit: number, daily: number): string {
  return addDays(start, Math.floor(credit / daily));
}

export function costView(plan: CostPlan, now: number): CostView {
  const days = Math.round((dayMs(plan.endDay) - dayMs(plan.startDay)) / DAY_MS) + 1;
  const credit = toUnits(plan.creditUsd);
  const daily = toUnits(plan.dailyUsd);
  const dailyWithApp3 = toUnits(plan.dailyWithApp3Usd);
  const today = kstDay(now);
  const phase = today < plan.startDay ? "BEFORE" : today > plan.endDay ? "AFTER" : "RUNNING";
  const elapsed = phase === "BEFORE" ? 0 : phase === "AFTER" ? days : Math.round((dayMs(today) - dayMs(plan.startDay)) / DAY_MS) + 1;
  return {
    days, credit, daily, dailyWithApp3,
    planTotal: daily * days,
    planWithApp3: dailyWithApp3 * days,
    remain: credit - daily * days,
    remainWithApp3: credit - dailyWithApp3 * days,
    exhaustDay: exhaustOn(plan.startDay, credit, daily),
    exhaustDayWithApp3: exhaustOn(plan.startDay, credit, dailyWithApp3),
    phase,
    spentToDate: daily * elapsed,
  };
}

/** 오늘 app-3 계획 비용(셈 단위). 가동 초에 시간 단가를 곱한다(기동·운영·드레이닝을 모두 센다) */
export function app3Cost(onSeconds: number, plan: CostPlan = COST_PLAN): number {
  return Math.round((onSeconds * toUnits(plan.app3HourlyUsd)) / 3600);
}

/** 계획 소진 예상일이 운영 종료일보다 이른가. 사이드바 배지(▲)가 이 판정을 쓴다 */
export function exhaustsEarly(plan: CostPlan = COST_PLAN): boolean {
  const v = costView(plan, dayMs(plan.startDay));
  return v.exhaustDay <= plan.endDay;
}

/** 날짜 글자('10.27') */
export function md(day: string): string {
  return `${Number(day.slice(5, 7))}.${Number(day.slice(8, 10))}`;
}
