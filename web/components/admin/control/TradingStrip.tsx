"use client";

/**
 * 거래 요약 띠. 관제센터에서 거래 쪽 사실은 이 한 줄만 싣고 자세한 것은 기존 화면으로 보낸다.
 * 값은 기존 조회 여섯 건의 합성(getOverview)이라 http 원천에서도 실측이다. 초당 체결은 두 표본의
 * 누적 체결 차로 낸다. 첫 표본에는 이전 값이 없어 비워 둔다.
 */

import { memo, useEffect, useRef, useState } from "react";
import Link from "next/link";
import type { AdminOverview, InvariantState } from "@/lib/admin/types";
import { formatKRW } from "@/lib/format";
import { hms } from "./parts";
import styles from "./control.module.css";

const INV_WORD: Record<InvariantState, string> = { PASS: "통과", VIOLATED: "위반", PARTIAL: "부분 수집", NONE: "미수집" };

/** 30초마다 오는 합성 조회만 받는다. 관제 판이 매초 새로 와도 다시 그리지 않는다 */
function TradingStrip({ ov, at }: { ov: AdminOverview | null; at: number | null }) {
  const prev = useRef<{ at: number; total: number } | null>(null);
  const [rate, setRate] = useState<number | null>(null);
  useEffect(() => {
    if (!ov || at === null) return;
    const p = prev.current;
    const sec = p ? (at - p.at) / 1000 : 0;
    setRate(p && sec >= 1 && ov.tradesTotal >= p.total ? (ov.tradesTotal - p.total) / sec : null);
    prev.current = { at, total: ov.tradesTotal };
  }, [ov, at]);

  if (!ov) {
    return (
      <section className={styles.strip} aria-label="거래 요약">
        <span className={styles.stripTitle}>거래 요약</span>
        <span className={styles.stripItem}>불러오는 중</span>
      </section>
    );
  }
  const inv = ov.invariants;
  const invTone = inv.state === "VIOLATED" ? "danger" : inv.state === "PASS" ? undefined : "warning";
  return (
    <section className={styles.strip} aria-label="거래 요약">
      <span className={styles.stripTitle}>거래 요약</span>
      <span className={styles.stripItem}>
        매칭 리더 <b>{ov.leaderNodeId || "없음"}</b> · 세대 <b className="num">{ov.epoch}</b>
      </span>
      <span className={styles.stripItem}>
        마켓 파티션 <b className="num">{ov.marketPartitions ?? "미보고"}</b>
      </span>
      <span className={styles.stripItem}>
        봇 <b className="num">{ov.currentOps === null ? "미보고" : `${formatKRW(ov.currentOps)} OPS`}</b>
      </span>
      <span className={styles.stripItem}>
        체결 <b className="num">{rate === null ? "측정 중" : `${rate.toFixed(1)}/s`}</b>
      </span>
      <span className={styles.stripItem}>
        안전 검사{" "}
        <b className="num" data-tone={invTone}>
          {invTone && <span aria-hidden="true">{invTone === "danger" ? "■ " : "▲ "}</span>}
          {inv.state === "VIOLATED" ? `위반 ${inv.violated}건` : `${inv.ok}/${inv.expected} ${INV_WORD[inv.state]}`}
        </b>
      </span>
      <span className={styles.stripItem}>
        앱 복구 판정{" "}
        <b data-tone={ov.lastVerdict === "FAIL" ? "danger" : ov.lastVerdict === "UNKNOWN" ? "warning" : undefined}>
          {ov.lastVerdict === null ? "이력 없음" : ov.lastVerdict === "PASS" ? "통과" : ov.lastVerdict === "FAIL" ? "■ 실패" : "▲ 미판정"}
        </b>
      </span>
      <span className={styles.stripItem}>
        중지 마켓 <b className="num" data-tone={ov.suspendedMarkets > 0 ? "warning" : undefined}>{ov.suspendedMarkets}</b>
      </span>
      <span className={styles.stripItem}>
        대기 승인 <b className="num" data-tone={ov.pendingApprovals > 0 ? "warning" : undefined}>{ov.pendingApprovals}</b>
      </span>
      <span className={styles.stripItem}>{at === null ? "" : `${hms(new Date(at).toISOString())} 기준`}</span>
      <span className={styles.stripLinks}>
        <Link href="/admin/capacity" className={styles.stripLink}>용량·부하</Link>
        <Link href="/admin/bots" className={styles.stripLink}>봇 플릿</Link>
        <Link href="/admin/flow" className={styles.stripLink}>서비스 흐름</Link>
      </span>
    </section>
  );
}

export default memo(TradingStrip);
