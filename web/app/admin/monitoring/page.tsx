"use client";

/**
 * 모니터링(/admin/monitoring). 불변식·복구(RTO 추이)·노드·지표. 읽기 전용(§2-3).
 * 파괴 액션 버튼을 노출하지 않는다(§6 안전). 헬스 색 도메인만 사용, 시세색 차용 금지.
 */

import { useCallback, useEffect, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import type { HaRecovery, HaStatus, InvariantCheck } from "@/lib/admin/types";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import { HealthPill, PageHeader, StatTile } from "@/components/admin/ui";
import type { Health } from "@/components/admin/ui";
import { formatKRW, formatTimeHMS } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

const NODE_HEALTH: Record<string, Health> = { healthy: "success", degraded: "warning", down: "danger" };
const NODE_HEALTH_LABEL: Record<string, string> = { healthy: "정상", degraded: "성능 저하", down: "중단" };

const RECOVERY_COLUMNS: DataColumn<HaRecovery["runs"][number]>[] = [
  { key: "startedAt", label: "시각", width: "90px", render: (r) => formatTimeHMS(r.startedAt) },
  { key: "rto", label: "RTO", width: "90px", align: "right", render: (r) => `${(r.durationMs / 1000).toFixed(1)}초` },
  { key: "rpo", label: "RPO", width: "80px", align: "right", render: (r) => `${r.rpoMs}ms` },
  {
    key: "seq",
    label: "재현 구간(seq)",
    width: "minmax(150px, 1fr)",
    align: "right",
    render: (r) => `${formatKRW(r.replayedFromSeq)} → ${formatKRW(r.replayedToSeq)}`,
  },
  {
    key: "matched",
    label: "지문",
    width: "96px",
    render: (r) => (r.matched ? <HealthPill health="success" label="일치" /> : <HealthPill health="danger" label="불일치" />),
  },
  {
    key: "verdict",
    label: "판정",
    width: "96px",
    render: (r) =>
      r.verdict === "PASS" ? <HealthPill health="success" label="PASS" /> : <HealthPill health="danger" label="FAIL" />,
  },
];

export default function MonitoringPage() {
  const [ha, setHa] = useState<HaStatus | null>(null);
  const [rec, setRec] = useState<HaRecovery | null>(null);
  const [inv, setInv] = useState<InvariantCheck[] | null>(null);

  const load = useCallback(async () => {
    try {
      const api = getAdminApi();
      const [h, r, i] = await Promise.all([api.getHaStatus(), api.getRecovery(), api.getInvariants()]);
      setHa(h);
      setRec(r);
      setInv(i);
    } catch {
      // 세션 게이트 처리
    }
  }, []);

  useEffect(() => {
    void load();
    const iv = setInterval(() => void load(), 2000);
    return () => clearInterval(iv);
  }, [load]);

  const nodeCols: DataColumn<HaStatus["nodes"][number]>[] = [
    { key: "id", label: "노드", width: "minmax(120px, 1fr)", render: (n) => n.id },
    { key: "role", label: "역할", width: "90px", render: (n) => (n.role === "leader" ? "leader" : "follower") },
    { key: "epoch", label: "epoch", width: "80px", align: "right", render: (n) => n.epoch },
    { key: "seq", label: "seq", width: "minmax(100px, 1fr)", align: "right", render: (n) => formatKRW(n.seq) },
    { key: "lag", label: "follower lag", width: "110px", align: "right", render: (n) => `${n.followerLagMs}ms` },
    {
      key: "health",
      label: "상태",
      width: "110px",
      render: (n) => <HealthPill health={NODE_HEALTH[n.health]} label={NODE_HEALTH_LABEL[n.health]} />,
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader title="모니터링" desc="고가용성 상태·불변식·복구 이력. 읽기 전용." />

      {!ha ? (
        <div className={styles.kpiGrid}>
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} variant="card" width="100%" height={96} />
          ))}
        </div>
      ) : (
        <div className={styles.kpiGrid}>
          <StatTile label="리더" value={ha.leaderNodeId} sub={`epoch ${ha.epoch}`} />
          <StatTile label="시퀀서 큐" value={formatKRW(ha.sequencerQueueSize)} sub="대기 커맨드" tone={ha.sequencerQueueSize > 200 ? "warning" : undefined} />
          <StatTile label="주문 지연 p99" value={`${ha.orderLatencyP99Ms}ms`} tone={ha.orderLatencyP99Ms > 60 ? "warning" : "success"} />
          <StatTile label="원장 불균형" value={ha.ledgerImbalance} sub="자산 보존 0" tone={ha.ledgerImbalance === "0" ? "success" : "danger"} />
          <StatTile label="Hikari 대기" value={ha.hikariPending} sub="커넥션 대기" />
          <StatTile label="누적 체결" value={formatKRW(ha.tradesTotal)} sub="trades_total" />
        </div>
      )}

      <PanelCard title="노드 상태">
        {!ha ? (
          <Skeleton variant="card" width="100%" height={160} />
        ) : (
          <DataTable columns={nodeCols} rows={ha.nodes} rowKey={(n) => n.id} rowHeight={44} ariaLabel="HA 노드 상태" />
        )}
      </PanelCard>

      <PanelCard title="불변식 7종">
        {!inv ? (
          <Skeleton variant="card" width="100%" height={120} />
        ) : (
          <ul className={styles.invGrid}>
            {inv.map((i) => (
              <li key={i.name} className={styles.invItem}>
                <span className={styles.invLabel}>{i.label}</span>
                {i.ok ? (
                  <HealthPill health="success" label="정상" />
                ) : (
                  <HealthPill health="danger" label={`위반 ${i.violatedRows}행`} />
                )}
              </li>
            ))}
          </ul>
        )}
      </PanelCard>

      <PanelCard title="복구 이력 · RTO 추이">
        {!rec ? (
          <Skeleton variant="card" width="100%" height={160} />
        ) : (
          <DataTable
            columns={RECOVERY_COLUMNS}
            rows={rec.runs}
            rowKey={(r) => r.id}
            rowHeight={44}
            ariaLabel="복구 실행 이력"
            empty={
              <div className="empty-state">
                <span className="empty-state__title">복구 이력이 없어요</span>
              </div>
            }
          />
        )}
      </PanelCard>
    </div>
  );
}
