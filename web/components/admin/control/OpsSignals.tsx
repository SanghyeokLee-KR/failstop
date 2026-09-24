"use client";

/**
 * 운영자 바의 전체 상태 알약과 경보 종. 관제 판에서 활성 경보와 이상 노드를 세어 정상·주의·장애 세 단계로
 * 적는다. 장애가 하나라도 있으면 정상이라고 쓰지 않는다. 판을 받기 전이나 원천이 연결 전이면 단계를
 * 짐작하지 않고 그 사실을 적는다(§12-3, §12-8).
 */

import Link from "next/link";
import { useOpsPickShallow } from "@/lib/admin/opsCenter";
import { overallOf, type Overall, type OverallLevel } from "@/lib/admin/opsHealth";
import type { ControlCenter } from "@/lib/admin/types";
import { AdminIcon } from "../icons";
import styles from "./control.module.css";

const LEVEL: Record<OverallLevel, { shape: string; word: string }> = {
  ok: { shape: "●", word: "정상" },
  warn: { shape: "▲", word: "주의" },
  down: { shape: "■", word: "장애" },
  none: { shape: "◌", word: "상태 원천 연결 전" },
  loading: { shape: "◌", word: "상태 확인 중" },
};

/** 운영자 바가 그리는 글자만 고른다. 판이 매초 새로 와도 단계와 개수가 같으면 다시 그리지 않는다 */
function signalsOf(c: ControlCenter | null): Overall & { bell: "danger" | "warning" | "info" } {
  const alerts = c?.alerts.state === "READY" ? c.alerts.data : [];
  const bell = alerts.some((a) => a.severity === "critical") ? "danger" : alerts.some((a) => a.severity === "warning") ? "warning" : "info";
  return { ...overallOf(c), bell };
}

export default function OpsSignals() {
  const o = useOpsPickShallow(signalsOf);
  const lv = LEVEL[o.level];
  const parts = [o.alerts > 0 ? `경보 ${o.alerts}` : null, o.nodes > 0 ? `노드 ${o.nodes}` : null].filter(Boolean);
  return (
    <>
      <Link
        href="/admin/incidents"
        className={styles.statusPill}
        data-level={o.level}
        aria-label={`전체 상태 ${lv.word}${o.alerts > 0 ? `, 활성 경보 ${o.alerts}건` : ""}${o.nodes > 0 ? `, 이상 노드 ${o.nodes}대` : ""}. 경보·사건 화면으로 이동`}
      >
        <span className={styles.statusShape} aria-hidden="true">
          {lv.shape}
        </span>
        <span aria-hidden="true">{lv.word}</span>
        {parts.length > 0 && (
          <span className={`${styles.statusDetail} num`} aria-hidden="true">
            {parts.join(" · ")}
          </span>
        )}
      </Link>
      <Link
        href="/admin/incidents"
        className={styles.bell}
        aria-label={`경보·사건 · 활성 경보 ${o.alerts}건`}
      >
        <AdminIcon name="incidents" size={18} />
        {o.alerts > 0 && (
          <span className={`${styles.bellCount} num`} data-tone={o.bell} aria-hidden="true">
            {o.alerts}
          </span>
        )}
      </Link>
    </>
  );
}
