"use client";

/**
 * 거래 요약 띠. 관제센터에서 거래 쪽 사실은 이 한 줄만 싣고 자세한 것은 기존 화면으로 보낸다.
 *
 * 매칭 리더, 세대, 봇 발행률, 초당 체결은 관제 판의 거래 칸을 먼저 읽는다. sim에서는 그 칸이 관제 시뮬레이터의
 * 값이라 인프라 칸, 봇 플릿, 서비스 흐름과 같은 숫자다(관리자 설계 4-4 sim 단일 원천). 거래 칸이 원천 연결 전인
 * http에서는 기존 조회 여섯 건의 합성(getOverview)을 읽고, 초당 체결은 두 표본의 누적 체결 차로 낸다. 첫
 * 표본에는 이전 값이 없어 비워 둔다. 안전 검사, 앱 복구 판정, 중지 마켓, 대기 승인은 늘 합성 조회에서 온다.
 */

import { memo, useEffect, useRef, useState } from "react";
import Link from "next/link";
import type { AdminOverview, InvariantState, OpsSection, OpsTrading } from "@/lib/admin/types";
import { formatKRW } from "@/lib/format";
import { hms } from "./parts";
import styles from "./control.module.css";
import StatusShape from "../StatusShape";

const INV_WORD: Record<InvariantState, string> = { PASS: "통과", VIOLATED: "위반", PARTIAL: "부분 수집", NONE: "미수집" };

/** 30초마다 오는 합성 조회와 관제 판의 거래 칸만 받는다. 판의 다른 칸이 바뀌어도 다시 그리지 않는다 */
function TradingStrip({ ov, at, trading }: { ov: AdminOverview | null; at: number | null; trading: OpsSection<OpsTrading> | null }) {
  const prev = useRef<{ at: number; total: number } | null>(null);
  const [rate, setRate] = useState<number | null>(null);
  useEffect(() => {
    if (!ov || at === null) return;
    const p = prev.current;
    const sec = p ? (at - p.at) / 1000 : 0;
    setRate(p && sec >= 1 && ov.tradesTotal >= p.total ? (ov.tradesTotal - p.total) / sec : null);
    prev.current = { at, total: ov.tradesTotal };
  }, [ov, at]);

  const t = trading?.state === "READY" ? trading.data : null;
  if (!ov && !t) {
    return (
      <section className={styles.strip} aria-label="거래 요약">
        <span className={styles.stripTitle}>거래 요약</span>
        <span className={styles.stripItem}>불러오는 중</span>
      </section>
    );
  }
  const inv = ov?.invariants ?? null;
  const invTone = inv === null ? undefined : inv.state === "VIOLATED" ? "danger" : inv.state === "PASS" ? undefined : "warning";
  const ops = t ? t.botOps : ov?.currentOps ?? null;
  const tps = t ? t.tradesPerSec : rate;
  return (
    <section className={styles.strip} aria-label="거래 요약">
      <span className={styles.stripTitle}>거래 요약</span>
      {t ? (
        <>
          <span className={styles.stripItem}>
            매칭 리더{" "}
            {t.leaders.map((l, i) => (
              <span key={l.partition} className={styles.nowrap}>
                {i > 0 && " · "}
                <b>{l.partition}</b> {l.node} <span className="num">epoch {l.epoch}</span>
              </span>
            ))}
          </span>
          <span className={styles.stripItem}>
            마켓 <b className="num">{t.leaders.reduce((a, l) => a + l.markets.length, 0)}</b>
            <span className="num"> ({t.leaders.map((l) => l.markets.length).join("·")})</span>
          </span>
        </>
      ) : (
        <>
          <span className={styles.stripItem}>
            매칭 리더 <b>{ov?.leaderNodeId || "없음"}</b> · 세대 <b className="num">{ov?.epoch}</b>
          </span>
          <span className={styles.stripItem}>
            마켓 파티션 <b className="num">{ov?.marketPartitions ?? "미보고"}</b>
          </span>
        </>
      )}
      <span className={styles.stripItem}>
        봇 <b className="num">{ops === null ? "미보고" : `${formatKRW(ops)} OPS`}</b>
      </span>
      <span className={styles.stripItem}>
        체결 <b className="num">{tps === null ? "측정 중" : `${tps.toFixed(1)}/s`}</b>
      </span>
      {inv && (
        <span className={styles.stripItem}>
          안전 검사{" "}
          <b className="num" data-tone={invTone}>
            {invTone && <span aria-hidden="true"><StatusShape kind={invTone === "danger" ? "square" : "triangle"} /> </span>}
            {inv.state === "VIOLATED" ? `위반 ${inv.violated}건` : `${inv.ok}/${inv.expected} ${INV_WORD[inv.state]}`}
          </b>
        </span>
      )}
      {ov && (
        <>
          <span className={styles.stripItem}>
            앱 복구 판정{" "}
            <b data-tone={ov.lastVerdict === "FAIL" ? "danger" : ov.lastVerdict === "UNKNOWN" ? "warning" : undefined}>
              {ov.lastVerdict === null ? "이력 없음" : ov.lastVerdict === "PASS" ? "통과" : ov.lastVerdict === "FAIL" ? <><StatusShape kind="square" /> 실패</> : <><StatusShape kind="triangle" /> 미판정</>}
            </b>
          </span>
          <span className={styles.stripItem}>
            중지 마켓 <b className="num" data-tone={ov.suspendedMarkets > 0 ? "warning" : undefined}>{ov.suspendedMarkets}</b>
          </span>
          <span className={styles.stripItem}>
            대기 승인 <b className="num" data-tone={ov.pendingApprovals > 0 ? "warning" : undefined}>{ov.pendingApprovals}</b>
          </span>
        </>
      )}
      {/* 리더·봇·체결은 매초 오는 관제 판의 값이고 검사·판정·승인은 30초마다 오는 합성 조회의 값이다. 한 줄에 두 시점이
          섞이므로 판 값이 있을 때는 시각이 어느 값의 것인지 적는다(§12-9 판단 시점 표지와 같은 뜻) */}
      <span className={`${styles.stripItem} num`}>{at === null ? "" : `${t ? "검사·승인 " : ""}${hms(new Date(at).toISOString())} 기준`}</span>
      <span className={styles.stripLinks}>
        <Link href="/admin/capacity" className={styles.stripLink}>용량·부하</Link>
        <Link href="/admin/bots" className={styles.stripLink}>봇 플릿</Link>
        <Link href="/admin/flow" className={styles.stripLink}>서비스 흐름</Link>
      </span>
    </section>
  );
}

export default memo(TradingStrip);
