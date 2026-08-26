"use client";

/**
 * 핵심 안전 검사 릴레이 스트립(모니터링 1층). 7종 검사를 고정 7트랙 램프 1행으로 병렬 판독한다.
 * 정상은 잉크(램프·라벨만, 상태는 sr-only 낭독). 위반 셀은 danger 면 + '위반 N행' 인라인으로 발화한다.
 * 위반 원인 상세는 스트립 아래 상시 발화선 1행이 전담한다(정상·위반 모두 1행 점유, 전이 시프트 0).
 * 색 단독 금지: 램프 형태(●=정상, ■=위반)·위반 텍스트 병기. 셀 단위 전이 플래시.
 */

import { INVARIANT_DEFS } from "@/lib/admin/catalog";
import type { InvariantCheck } from "@/lib/admin/types";
import { formatTimeHMS } from "@/lib/format";
import type { Health } from "./ui";
import { useHealthTransition } from "./useHealthTransition";
import styles from "./admin.module.css";

const DEF_BY_NAME = new Map(INVARIANT_DEFS.map((d) => [d.name, d]));

/* 화면 라벨은 정적 정의 우선(한국어 평이어), 미스 시 서버 label 폴백. 원어는 gloss title로 강등 */
function displayLabel(inv: InvariantCheck): { label: string; gloss?: string } {
  const def = DEF_BY_NAME.get(inv.name);
  return { label: def?.label ?? inv.label, gloss: def?.gloss };
}

/* detail이 상태 문구('위반 N행')와 동일하면 새 정보가 아니라 중복 표기다 */
function extraDetail(inv: InvariantCheck): string | null {
  return inv.detail && inv.detail !== `위반 ${inv.violatedRows}행` ? inv.detail : null;
}

function RelayCell({ inv }: { inv: InvariantCheck }) {
  const health: Health = inv.ok ? "success" : "danger";
  const flash = useHealthTransition(health);
  const { label, gloss } = displayLabel(inv);
  return (
    <li
      className={styles.relayCell}
      data-health={health}
      data-transition={flash ?? undefined}
      title={inv.detail}
    >
      <span className={styles.relayLamp} aria-hidden="true">
        {inv.ok ? "●" : "■"}
      </span>
      {/* gloss 부재 시에도 title이 전문을 보존한다(협폭에서 라벨이 잘릴 수 있다) */}
      <span className={styles.relayLabel} title={gloss ?? label}>
        {label}
      </span>
      {inv.ok ? (
        <span className="sr-only">정상</span>
      ) : (
        <span className={`${styles.relayState} num`}>위반 {inv.violatedRows}행</span>
      )}
    </li>
  );
}

export default function InvariantRelayStrip({ checks }: { checks: InvariantCheck[] }) {
  const violated = checks.filter((c) => !c.ok);
  const latestCheckedAt = checks.reduce((a, c) => (c.checkedAt > a ? c.checkedAt : a), "");
  const okCopy = `${checks.length}건 모두 정상`;
  const okTime = latestCheckedAt ? ` · 검사 ${formatTimeHMS(latestCheckedAt)}` : "";
  const violationCopy =
    `위반 ${violated.length}건: ` +
    violated
      .map((v) => {
        const detail = extraDetail(v);
        return `${displayLabel(v).label} ${v.violatedRows}행${detail ? ` · ${detail}` : ""}`;
      })
      .join(" / ");
  return (
    <div className={styles.relayGroup}>
      <span className={styles.kpiGroupLabel} title="불변식 7종">
        핵심 안전 검사
      </span>
      <ul className={styles.relayStrip} aria-label="핵심 안전 검사 현황">
        {checks.map((c) => (
          <RelayCell key={c.name} inv={c} />
        ))}
      </ul>
      {/* 검사 시각은 폴링마다 변한다. 낭독 폭주를 막기 위해 시각 조각만 aria-hidden으로 분리해
          카피 본문이 실제로 바뀔 때만 낭독되게 한다. 전문은 title이 보존 */}
      <p
        className={styles.relayAnnunciator}
        role="status"
        aria-live="polite"
        data-health={violated.length > 0 ? "danger" : undefined}
        title={violated.length > 0 ? `■ ${violationCopy}` : okCopy + okTime}
      >
        {violated.length > 0 ? (
          <>
            <span aria-hidden="true">■ </span>
            {violationCopy}
          </>
        ) : (
          <>
            {okCopy}
            <span aria-hidden="true">{okTime}</span>
          </>
        )}
      </p>
    </div>
  );
}
