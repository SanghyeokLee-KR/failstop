"use client";

/**
 * 운영자 바의 전체 상태 알약과 경보 종. 관제 판에서 활성 경보와 이상 노드를 세어 정상·주의·장애 세 단계로
 * 적는다. 장애가 하나라도 있으면 정상이라고 쓰지 않는다. 판을 받기 전이나 원천이 연결 전이면 단계를
 * 짐작하지 않고 그 사실을 적는다(§12-3, §12-8).
 */

import Link from "next/link";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import type { ControlCenter, ThresholdKey } from "@/lib/admin/types";
import { AdminIcon } from "../icons";
import { hostState } from "./Infra";
import styles from "./control.module.css";

export type OverallLevel = "ok" | "warn" | "down" | "none" | "loading";

export interface Overall {
  level: OverallLevel;
  alerts: number;
  /** 임계를 넘었거나 멈춘 노드 수 */
  nodes: number;
}

/** 전체 상태. 경보는 보류 중이어도 세고(문제가 남아 있다), 정보 등급 경보는 단계를 올리지 않는다 */
export function overallOf(c: ControlCenter | null): Overall {
  if (c === null) return { level: "loading", alerts: 0, nodes: 0 };
  const sections = [c.alerts, c.hosts, c.readTier, c.replication];
  if (sections.every((s) => s.state === "NOT_CONNECTED")) return { level: "none", alerts: 0, nodes: 0 };
  const reg = c.thresholds.state === "READY" ? c.thresholds.data : null;
  const val = (k: ThresholdKey) => reg?.items.find((t) => t.key === k)?.value ?? null;
  let down = false;
  let warn = false;
  let nodes = 0;
  const alerts = c.alerts.state === "READY" ? c.alerts.data : [];
  if (alerts.some((a) => a.severity === "critical")) down = true;
  if (alerts.some((a) => a.severity === "warning")) warn = true;
  if (c.hosts.state === "READY") {
    // 수집이 끊긴 노드는 상태를 모르는 것이라 정상으로 세지 않고 주의로 올린다
    for (const h of c.hosts.data) {
      const t = hostState(h, reg).tone;
      if (t === "success") continue;
      nodes += 1;
      if (t === "danger") down = true;
      else warn = true;
    }
  }
  if (c.readTier.state === "READY") {
    const tier = c.readTier.data;
    const serving = tier.nodes.filter((n) => n.state === "IN_SERVICE").length;
    const out = val("read.scale_out_cpu");
    if (tier.desired > 0 && serving === 0) {
      down = true;
      nodes += 1;
    } else if (tier.avgCpuPct !== null && out !== null && tier.avgCpuPct >= out) {
      warn = true;
      nodes += 1;
    }
  }
  if (c.replication.state === "READY") {
    if (c.replication.data.stream === "STOPPED") down = true;
    else if (c.replication.data.stream === "CATCHUP") warn = true;
  }
  if (c.aiServer.state === "READY" && c.aiServer.data.link === "DOWN") {
    warn = true;
    nodes += 1;
  }
  return { level: down ? "down" : warn ? "warn" : "ok", alerts: alerts.length, nodes };
}

const LEVEL: Record<OverallLevel, { shape: string; word: string }> = {
  ok: { shape: "●", word: "정상" },
  warn: { shape: "▲", word: "주의" },
  down: { shape: "■", word: "장애" },
  none: { shape: "◌", word: "상태 원천 연결 전" },
  loading: { shape: "◌", word: "상태 확인 중" },
};

export default function OpsSignals() {
  const { data } = useOpsCenter();
  const o = overallOf(data);
  const lv = LEVEL[o.level];
  const parts = [o.alerts > 0 ? `경보 ${o.alerts}` : null, o.nodes > 0 ? `노드 ${o.nodes}` : null].filter(Boolean);
  const alerts = data?.alerts.state === "READY" ? data.alerts.data : [];
  const bellTone = alerts.some((a) => a.severity === "critical") ? "danger" : alerts.some((a) => a.severity === "warning") ? "warning" : "info";
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
        aria-label={`경보·사건 · 활성 경보 ${alerts.length}건`}
      >
        <AdminIcon name="incidents" size={18} />
        {alerts.length > 0 && (
          <span className={`${styles.bellCount} num`} data-tone={bellTone} aria-hidden="true">
            {alerts.length}
          </span>
        )}
      </Link>
    </>
  );
}
