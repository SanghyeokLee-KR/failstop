"use client";

/**
 * 화면 단일 대표 지표라 상태어만 display-md로 승격한다.
 * 색 단독 금지라 램프(●▲■)·상태어·sr-only 낭독을 병기하고, 헬스 전이 순간에만 플래시한다.
 *
 * 안전 검사는 항목별 릴레이가 아니라 한 줄 판독이다. 칸을 늘어놓아도 조작이 붙지 않아
 * 자리만 먹고, 항목별 내역은 Grafana의 체결 무결성 증명 화면이 그린다.
 * 대신 미수집·부분수집을 통과와 다른 상태로 적는다. 분모가 응답 행 수가 아니라 기대 검사
 * 수라 빈 표나 일부만 실린 표가 통과로 칠해지지 않는다(lib/admin/invariants).
 */

import type { AdminOverview, InvariantState } from "@/lib/admin/types";
import { formatTimeHMS } from "@/lib/format";
import { HEALTH_ICON, type Health } from "./ui";
import GrafanaLink from "./GrafanaLink";
import { useHealthTransition } from "./useHealthTransition";
import styles from "./admin.module.css";

const HEALTH_WORD: Record<Health, string> = { success: "전체 정상", warning: "주의", danger: "장애" };
// 시각 채널(색·형태)이 aria-hidden이라 심각도를 별도 낭독한다
const HEALTH_SR: Record<Health, string> = { success: "정상", warning: "주의", danger: "위험" };

/** 미수집·부분수집은 통과가 아니라 미판정이라 warning으로 발화한다 */
const INV_TONE: Record<InvariantState, "danger" | "warning" | undefined> = {
  PASS: undefined,
  VIOLATED: "danger",
  PARTIAL: "warning",
  NONE: "warning",
};
const INV_SR: Record<InvariantState, string> = {
  PASS: "통과",
  VIOLATED: "위험",
  PARTIAL: "주의",
  NONE: "주의",
};

export default function HealthHero({ ov }: { ov: AdminOverview }) {
  const flash = useHealthTransition(ov.overallHealth);
  const inv = ov.invariants;
  const invTone = INV_TONE[inv.state];
  const pendingWarn = ov.pendingApprovals > 0;
  const rtoFail = ov.lastVerdict === "FAIL";
  // UNKNOWN은 통과가 아니다. 판정 대상이 없거나 계측값이 NULL인 상태라, 통과로 칠하면
  // 공허 통과가 화면에 통과로 뜬다(API§6-C). 미판정은 미판정으로 적는다.
  const rtoUnknown = ov.lastVerdict === "UNKNOWN";

  const invWord =
    inv.state === "VIOLATED"
      ? `위반 ${inv.violated}건`
      : inv.state === "NONE"
        ? "미수집"
        : inv.state === "PARTIAL"
          ? `${inv.ok} / ${inv.expected} 부분 수집`
          : `${inv.ok} / ${inv.expected} 통과`;
  // 검사 시각이 없으면 없는 대로 둔다. 현재 시각으로 채우면 안 돈 배치가 방금 돈 것으로 읽힌다
  const invSub = inv.checkedAt === null ? "하네스 미가동" : `검사 ${formatTimeHMS(inv.checkedAt)}`;

  return (
    <section className={styles.healthHero} data-transition={flash ?? undefined} aria-label="종합 헬스">
      <div className={styles.heroMain}>
        <span className={styles.heroPlate}>종합 헬스 · 갱신 {formatTimeHMS(ov.generatedAt)}</span>
        <span className={styles.heroWord} data-health={ov.overallHealth}>
          <span aria-hidden="true">{HEALTH_ICON[ov.overallHealth]}</span>
          {HEALTH_WORD[ov.overallHealth]}
        </span>
        <span className="sr-only">종합 헬스 심각도 {HEALTH_SR[ov.overallHealth]}</span>
      </div>
      <div className={styles.heroGauge}>
        <span className={styles.heroPlate}>안전 검사</span>
        <span className={`${styles.heroGaugeValue} num`} data-tone={invTone}>
          {invTone && (
            <>
              <span className={styles.toneIcon} aria-hidden="true">
                {HEALTH_ICON[invTone]}
              </span>
              <span className="sr-only">심각도 {INV_SR[inv.state]}</span>
            </>
          )}
          {invWord}
        </span>
        <span className={styles.heroGaugeSub}>
          {invSub}
          <GrafanaLink board="proof-integrity" label="항목별 내역" />
        </span>
      </div>
      <div className={styles.heroKv}>
        {/* 값과 판정을 한 필드에 묶지 않는다. 원천이 다르다. 복구 시간은 최신 주행 1건이고
            판정은 표 전체 집계라, 붙여 놓으면 3.2초 주행이 통과했는데도 다른 주행의 위반
            때문에 "3.2초 실패"로 읽힌다. 두 행으로 가르고 판정 행에 범위를 밝힌다. */}
        <span className={styles.heroKvRow}>
          <span className={styles.heroKvLabel} title="RTO">
            최근 복구 시간
          </span>
          <span className={`${styles.heroKvValue} num`}>
            {ov.lastRtoMs == null ? (
              "실행 이력 없음"
            ) : (
              <>
                {(ov.lastRtoMs / 1000).toFixed(1)}
                <span className="unit-suffix">초</span>
              </>
            )}
          </span>
        </span>
        <span className={styles.heroKvRow}>
          <span className={styles.heroKvLabel}>복구 판정(전체)</span>
          <span
            className={styles.heroKvValue}
            data-tone={rtoFail ? "danger" : rtoUnknown ? "warning" : undefined}
          >
            {ov.lastVerdict === null && "이력 없음"}
            {ov.lastVerdict === "PASS" && "통과"}
            {rtoUnknown && (
              <>
                <span aria-hidden="true">▲ </span>미판정
              </>
            )}
            {rtoFail && (
              <>
                <span aria-hidden="true">■ </span>실패
              </>
            )}
          </span>
        </span>
        <span className={styles.heroKvRow}>
          <span className={styles.heroKvLabel}>대기 승인</span>
          <span className={`${styles.heroKvValue} num`} data-tone={pendingWarn ? "warning" : undefined}>
            {pendingWarn && <span aria-hidden="true">▲ </span>}
            {ov.pendingApprovals}
            <span className="unit-suffix">건</span>
          </span>
        </span>
      </div>
    </section>
  );
}
