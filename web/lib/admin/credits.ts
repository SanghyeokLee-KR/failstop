/**
 * t3 CPU 크레딧(관리자 설계 4-10-3 용량·부하). 노드는 모두 t3.small이다(AWS 작업 안내 사양). 크레딧 한 개는 vCPU 하나를
 * 1분 동안 100% 쓰는 양이고, t3.small은 시간당 24개를 쌓아 최대 576개까지 둔다. 기준선은 vCPU마다 20%다(AWS EC2 버스터블
 * 인스턴스 문서의 t3.small 값). 그래서 CPU가 기준선 20%보다 높으면 쌓는 양보다 쓰는 양이 많아 잔량이 준다.
 *
 * 학교 노드는 크레딧 모드 Standard다. 잔량이 0이 되면 추가 과금 없이 CPU가 기준선으로 묶인다. Unlimited는 기준선을 넘겨
 * 쓰고 모자란 몫을 과금하며 CloudWatch의 CPUSurplusCreditsCharged로 드러난다. 관제 시뮬레이터와 화면이 이 한 식을 쓴다.
 */

export const T3_SMALL = {
  vcpu: 2,
  /** 시간당 쌓는 크레딧 */
  earnPerHour: 24,
  /** 쌓아 둘 수 있는 최대 크레딧 */
  maxCredits: 576,
  /** vCPU마다의 기준선(%) */
  baselinePct: 20,
} as const;

/** 크레딧 모드. 학교 노드는 Standard로 만들었다(증거본 2026-09-19 027~030) */
export type CreditMode = "STANDARD" | "UNLIMITED";

/**
 * CPU 사용률(노드 전체, %)에서 시간당 크레딧 증감. 양수면 쌓고 음수면 쓴다. 기준선이면 0이다.
 * 쓰는 양은 사용률 x vCPU x 60(분)이다
 */
export function creditNetPerHour(cpuPct: number): number {
  return T3_SMALL.earnPerHour - (cpuPct / 100) * T3_SMALL.vcpu * 60;
}

/**
 * 지금 추세가 이어질 때 잔량이 0이 될 때까지의 시간(시). 쌓는 중이거나 기준선이면 null이다. 예측이라 화면은 '예측'을 적는다
 */
export function hoursToEmpty(balance: number, cpuPct: number): number | null {
  const net = creditNetPerHour(cpuPct);
  if (net >= 0) return null;
  return balance / -net;
}

/** 크레딧 잔량 원천이 없을 때 붙일 원천의 이름 */
export const CREDIT_SOURCE = "CloudWatch CPUCreditBalance 수집 설정 없음(관제센터 2단계 설계 2-1 (3))";

/** Unlimited 추가 과금 경보의 원천 */
export const SURPLUS_SOURCE = "CloudWatch CPUSurplusCreditsCharged · 연결 전";
