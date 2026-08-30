"use client";

/**
 * 종합 헬스 히어로(/admin 1층). 화면 단일 대표 지표라 상태어만 display-md로 승격한다.
 * 색 단독 금지: 램프(●▲■)·상태어·sr-only 낭독을 병기한다.
 * 정지가 기본값이고 헬스 전이 순간에만 밴드 배경이 플래시한다(reduced-motion 명시 킬).
 * 내부는 비대칭 3열: 상태어 존 > 핵심 안전 검사 > 키·값 캡션 칼럼.
 */

import type { AdminOverview } from "@/lib/admin/types";
import { formatTimeHMS } from "@/lib/format";
import { HEALTH_ICON, type Health } from "./ui";
import { useHealthTransition } from "./useHealthTransition";
import styles from "./admin.module.css";

const HEALTH_WORD: Record<Health, string> = { success: "전체 정상", warning: "주의", danger: "장애" };
// 시각 채널(색·형태)이 aria-hidden이라 심각도를 별도 낭독한다
const HEALTH_SR: Record<Health, string> = { success: "정상", warning: "주의", danger: "위험" };

export default function HealthHero({ ov }: { ov: AdminOverview }) {
  const flash = useHealthTransition(ov.overallHealth);
  // 이진 판정이라 정상은 잉크, 이탈만 danger로 발화한다
  const invBroken = ov.invariantsOk !== ov.invariantsTotal;
  const pendingWarn = ov.pendingApprovals > 0;
  const rtoFail = ov.lastVerdict === "FAIL";
  // UNKNOWN은 통과가 아니다. 판정 대상이 없거나 계측값이 NULL인 상태라, 통과로 칠하면
  // 공허 통과가 화면에 통과로 뜬다(API§6-C). 미판정은 미판정으로 적는다.
  const rtoUnknown = ov.lastVerdict === "UNKNOWN";

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
        <span className={styles.heroPlate} title="invariant_checks 최신 행 기준. 수용 기준 7종은 검증설계 §4-4가 이 항목들로 해석한다">
          핵심 안전 검사
        </span>
        <span className={`${styles.heroGaugeValue} num`} data-tone={invBroken ? "danger" : undefined}>
          {invBroken && (
            <>
              <span className={styles.toneIcon} aria-hidden="true">
                {HEALTH_ICON.danger}
              </span>
              <span className="sr-only">심각도 {HEALTH_SR.danger}</span>
            </>
          )}
          {ov.invariantsOk} / {ov.invariantsTotal}
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
