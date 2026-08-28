"use client";

/**
 * 카오스 콘솔(/admin/chaos). E1~E9 카탈로그 주입·해제. 전부 2인 승인 필수(§2-2).
 * 정적 카탈로그를 제시하고, 주입은 명시 클릭 + 승인 + 요청자 실행 3단계. 자동 주입 없음(§6).
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import type { AdminPage, ChaosExperimentDef, ChaosRun, DestructiveAction } from "@/lib/admin/types";
import { formatTimeHMS } from "@/lib/format";
import Pager from "@/components/Pager";
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

/* 한 쪽 50건. 폴링이 같은 쪽을 다시 읽으므로 쪽이 커지면 그 비용이 계속 나간다 */
const PAGE_SIZE = 50;

/* 등급 어휘. 멈추는 범위를 말한다. "위험/주의" 같은 감정어 대신 무엇이 멈추는지를 적는다 */
const SEVERITY_LABEL: Record<"high" | "medium" | "low", string> = {
  high: "체결 중단",
  medium: "계층 차단",
  low: "지연 주입",
};

export default function ChaosPage() {
  const canChaos = useCan("CHAOS_OPS");
  const catalog: ChaosExperimentDef[] = getAdminApi().getChaosCatalog();
  /* 실험 이력은 감사 로그에 영구 누적된다. 종전 고정 상한 200 밖의 실험은 볼 방법이 없었다 */
  const [page, setPage] = useState(0);
  const cursors = useRef<(string | null)[]>([null]);
  const loader = useCallback(async () => {
    const r = await getAdminApi().getChaosRuns({ cursor: cursors.current[page] ?? null, limit: PAGE_SIZE });
    cursors.current[page + 1] = r.nextCursor;
    return r;
  }, [page]);
  const res = useAdminResource<AdminPage<ChaosRun>>(loader, { intervalMs: 2000, isSessionError });
  const runs = res.data?.data ?? null;
  const hasMore = res.data?.hasMore ?? false;
  const load = res.reload;
  useEffect(() => {
    void load();
    // load는 안정 참조다. 쪽 변화에만 반응한다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);
  const actions = useMemo(() => CHAOS_ACTIONS, []);

  const running = (runs ?? []).filter((r) => r.state === "RUNNING");

  return (
    <div className={styles.page}>
      <PageHeader
        title="카오스 콘솔"
        desc="자동 주입 없음 · 전 실험 2인 승인"
        actions={res.stale ? <StaleBadge since={res.lastUpdatedAt} /> : undefined}
      />

      {/* 진행 중·승인 대기는 짧은 상태 패널이라 2열로 눕혀 카탈로그를 첫 화면으로 올린다 */}
      <div className={styles.pairGrid}>
        <PanelCard title="진행 중 실험" className={styles.stretchCard}>
          {running.length === 0 ? (
            <div className="empty-state empty-state--compact">
              <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
              <span className="empty-state__title">주입 중인 실험 없음</span>
              <span className="empty-state__desc">카탈로그에서 주입을 요청하고 다른 운영자 승인을 받으면 시작</span>
            </div>
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

        <PanelCard title="승인 대기" className={styles.stretchCard}>
          <ApprovalList
            actions={actions}
            onChange={load}
            emptyLabel="대기 중인 카오스 승인 없음"
            emptyDesc="주입·해제 요청이 들어오면 여기에서 발급·실행"
          />
        </PanelCard>
      </div>

      <PanelCard title="실험 카탈로그" actions={<span className={styles.panelMeta}>전 실험 2인 승인</span>}>
        {!canChaos && <PermissionNote>카오스 권한(CHAOS_OPS 이상) 없음. 주입 요청 불가</PermissionNote>}
        <ul className={styles.chaosList}>
          {catalog.map((e) => {
            const isRunning = running.some((r) => r.experimentId === e.id);
            return (
              // 상세 설명은 행에서 걷고 title·주입 확인 다이얼로그로 강등한다
              <li key={e.id} className={styles.chaosRow} title={e.description}>
                <span className={styles.chaosId}>{e.id}</span>
                <span className={styles.chaosLabel}>{e.label}</span>
                {/* 등급을 글자로도 싣는다. 버튼 형태만으로 가르면 색 단독 표현이 된다.
                    "무엇이 깨지나"(blastRadius)와 "얼마나 깨지나"(등급)는 다른 질문이다 */}
                <span className={styles.chaosBlast}>
                  {e.blastRadius}
                  <span className={styles.chaosSeverity} data-severity={e.severity}>
                    {SEVERITY_LABEL[e.severity]}
                  </span>
                </span>
                {isRunning ? (
                  // 진행 중 실험에 주입 요청 버튼을 남기면 상단 해제 카드와 어포던스가 상반된다
                  <span className="status-pill" data-health="warning">
                    ▲ 주입 중
                  </span>
                ) : (
                  <RequestButton
                    action="chaos.inject"
                    payload={{ action: "chaos.inject", experimentId: e.id }}
                    label="주입 요청"
                    tone={e.severity === "high" ? "destructive" : "neutral"}
                    onRequested={load}
                    confirm={{
                      title: "카오스 실험 주입 요청",
                      description: "승인·실행 뒤 실제 장애 주입. 해제는 별도 요청 필요",
                      target: `${e.id} · ${e.label}`,
                      blastRadius: e.blastRadius,
                      tone: "danger",
                      confirmLabel: "주입 요청",
                    }}
                  />
                )}
              </li>
            );
          })}
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
                <span className="empty-state__title">실행된 실험 없음</span>
              </div>
            }
          />
        )}

        <Pager page={page} hasMore={hasMore} onMove={setPage} ariaLabel="실험 이력 쪽 이동" />
      </PanelCard>
    </div>
  );
}
