"use client";

/**
 * 카오스 콘솔(/admin/chaos). E1~E9 카탈로그 주입·해제. 전부 2인 승인 필수(§2-2).
 * 정적 카탈로그를 제시하고, 주입은 명시 클릭 + 승인 + 요청자 실행 3단계. 자동 주입 없음(§6).
 */

import { useMemo } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import type { ChaosExperimentDef, ChaosRun, DestructiveAction } from "@/lib/admin/types";
import { formatTimeHMS } from "@/lib/format";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import RequestButton from "@/components/admin/RequestButton";
import ApprovalList from "@/components/admin/ApprovalList";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { HealthPill, PageHeader, PermissionNote } from "@/components/admin/ui";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

const CHAOS_ACTIONS: DestructiveAction[] = ["chaos.inject", "chaos.clear"];

const RUN_COLUMNS: DataColumn<ChaosRun>[] = [
  { key: "started", label: "시각", width: "90px", render: (r) => formatTimeHMS(r.startedAt) },
  { key: "exp", label: "실험", width: "80px", render: (r) => r.experimentId },
  { key: "label", label: "내용", width: "minmax(160px, 1fr)", render: (r) => r.label },
  { key: "blast", label: "영향 범위", width: "minmax(140px, 1fr)", render: (r) => r.blastRadius },
  { key: "anno", label: "annotation", width: "minmax(140px, 1fr)", render: (r) => r.grafanaAnnotation },
  {
    key: "state",
    label: "상태",
    width: "100px",
    // 종결 상태(해제됨)는 잉크, 진행 중만 warning으로 발화한다
    render: (r) => (r.state === "RUNNING" ? <HealthPill health="warning" label="주입 중" /> : <span className={styles.stateText}>해제됨</span>),
  },
];

export default function ChaosPage() {
  const canChaos = useCan("CHAOS_OPS");
  const catalog: ChaosExperimentDef[] = getAdminApi().getChaosCatalog();
  const res = useAdminResource<ChaosRun[]>(() => getAdminApi().getChaosRuns(), { intervalMs: 2000, isSessionError });
  const runs = res.data;
  const load = res.reload;
  const actions = useMemo(() => CHAOS_ACTIONS, []);

  const running = (runs ?? []).filter((r) => r.state === "RUNNING");

  return (
    <div className={styles.page}>
      <PageHeader
        title="카오스 콘솔"
        desc="자동 주입 없음 · 전 실험 2인 승인"
        actions={res.stale ? <StaleBadge since={res.lastUpdatedAt} /> : undefined}
      />

      <PanelCard title="진행 중 실험">
        {running.length === 0 ? (
          <p className={styles.approvalEmpty}>주입 중인 실험이 없습니다</p>
        ) : (
          <ul className={styles.runningList}>
            {running.map((r) => (
              <li key={r.id} className={styles.runningItem}>
                <span className={styles.runningLabel}>
                  {r.experimentId} · {r.label}
                </span>
                <span className={styles.runningBlast}>{r.blastRadius}</span>
                <RequestButton
                  action="chaos.clear"
                  payload={{ action: "chaos.clear", runId: r.clearKey }}
                  label="해제 요청"
                  onRequested={load}
                  confirm={{
                    title: "카오스 실험 해제 요청",
                    description: "승인·실행 시 주입 중인 장애가 걷힙니다.",
                    target: `${r.experimentId} · ${r.label}`,
                    blastRadius: r.blastRadius,
                    confirmLabel: "해제 요청",
                  }}
                />
              </li>
            ))}
          </ul>
        )}
      </PanelCard>

      <PanelCard title="승인 대기">
        <ApprovalList actions={actions} onChange={load} emptyLabel="대기 중인 카오스 승인이 없습니다" />
      </PanelCard>

      <PanelCard title="실험 카탈로그" actions={<span className={styles.panelMeta}>전 실험 2인 승인</span>}>
        {!canChaos && <PermissionNote>카오스 권한(CHAOS_OPS 이상)이 없어 주입 요청을 만들 수 없습니다.</PermissionNote>}
        <ul className={styles.chaosList}>
          {catalog.map((e) => (
            // 상세 설명은 행에서 걷고 title·주입 확인 다이얼로그로 강등한다
            <li key={e.id} className={styles.chaosRow} title={e.description}>
              <span className={styles.chaosId}>{e.id}</span>
              <span className={styles.chaosLabel}>{e.label}</span>
              <span className={styles.chaosBlast}>{e.blastRadius}</span>
              <RequestButton
                action="chaos.inject"
                payload={{ action: "chaos.inject", experimentId: e.id }}
                label="주입 요청"
                onRequested={load}
                confirm={{
                  title: "카오스 실험 주입 요청",
                  description: "승인·실행 뒤 실제 장애가 주입됩니다. 해제하려면 별도 요청이 필요합니다.",
                  target: `${e.id} · ${e.label}`,
                  blastRadius: e.blastRadius,
                  tone: "danger",
                  confirmLabel: "주입 요청",
                }}
              />
            </li>
          ))}
        </ul>
      </PanelCard>

      <PanelCard title="실험 이력">
        {res.error ? (
          <AdminErrorState reason={res.errorReason} onRetry={res.reload} />
        ) : !runs ? (
          <Skeleton variant="card" width="100%" height={160} />
        ) : (
          <DataTable
            columns={RUN_COLUMNS}
            rows={runs}
            rowKey={(r) => r.id}
            rowHeight={44}
            ariaLabel="카오스 실험 이력"
            empty={
              <div className="empty-state">
                <span className="empty-state__title">실행된 실험이 없습니다</span>
              </div>
            }
          />
        )}
      </PanelCard>
    </div>
  );
}
