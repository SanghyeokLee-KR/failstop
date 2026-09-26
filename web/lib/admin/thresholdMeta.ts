/**
 * 임계값 아홉 항목의 화면 이름과 단위(관리자 설계 4-4, 관제센터 2단계 설계 5-1). 값·범위·AI 1회 폭은 원천마다 온다.
 * sim은 시뮬레이터가, http는 임계값 레지스트리(서버 V70)가 준다. 이름과 단위만 한곳에 두어 두 원천의 화면이 같은
 * 말을 쓰게 한다. 시뮬레이터 본체(opsSim.ts)를 http 묶음에 끌어들이지 않으려고 따로 둔다.
 *
 * 단위는 화면 단위다. 서버는 비율(ratio)·초(seconds)·크레딧(credits)으로 싣고, 어댑터가 백분율과 시간으로 옮긴다
 * (2단계 설계 4-5 대응표).
 */

import type { ThresholdKey, ThresholdUnit } from "./types";

export interface ThresholdMeta {
  label: string;
  appliesTo: string;
  unit: ThresholdUnit;
  /** 편집 칸의 한 눈금(화면 단위) */
  step: number;
}

export const THRESHOLD_META: Record<ThresholdKey, ThresholdMeta> = {
  "read.scale_out_cpu": { label: "증설 CPU 임계", appliesTo: "앱 계층 평균 CPU. 넘으면 app-3 켜기", unit: "%", step: 1 },
  "read.scale_in_cpu": { label: "축소 CPU 임계", appliesTo: "앱 계층 평균 CPU. 아래면 app-3 끄기", unit: "%", step: 1 },
  "host.cpu_alert": { label: "호스트 CPU 경보", appliesTo: "EC2 호스트 CPU", unit: "%", step: 1 },
  "host.mem_alert": { label: "메모리 경보", appliesTo: "EC2 호스트 메모리", unit: "%", step: 1 },
  "host.disk_free_alert": { label: "디스크 여유 경보", appliesTo: "루트 디스크 여유(미만이면 경보)", unit: "%", step: 1 },
  "db.replication_lag_alert": { label: "복제 지연 경보", appliesTo: "Standby 재생 지연", unit: "초", step: 5 },
  "backup.delay_alert": { label: "백업 지연 경보", appliesTo: "Databasus 마지막 성공 뒤 경과", unit: "시간", step: 1 },
  "bot.capacity_guard": { label: "봇 용량 보호", appliesTo: "bot-1 CPU. 넘으면 봇 부하 줄임", unit: "%", step: 1 },
  "host.cpu_credit_alert": { label: "CPU 크레딧 잔량 경보", appliesTo: "t3 호스트 CPU 크레딧(미만이면 경보)", unit: "크레딧", step: 10 },
};

export const THRESHOLD_KEYS = Object.keys(THRESHOLD_META) as ThresholdKey[];

export function isThresholdKey(v: unknown): v is ThresholdKey {
  return typeof v === "string" && v in THRESHOLD_META;
}
