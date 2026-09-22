"use client";

/**
 * 카오스 콘솔(/admin/chaos). E1~E9 카탈로그 주입·해제. 1인 실행·전량 감사(§2-2, §3-6 v2.34).
 * 정적 카탈로그를 제시하고, 주입은 명시 클릭 + 승인 + 요청자 실행 3단계. 자동 주입 없음(§6).
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import type { AdminPage, ChaosExperimentDef, ChaosRun } from "@/lib/admin/types";
import { formatTimeHMS } from "@/lib/format";
import Pager from "@/components/Pager";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import ExecuteButton from "@/components/admin/ExecuteButton";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { HealthPill, PageHeader, PermissionNote } from "@/components/admin/ui";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

/* 실험 열과 annotation 열을 걷었다.
   실험 열은 experiment_id 를 적었는데 그 값이 UUID 36자다. 80px 열에 넣으면 하이픈에서
   줄바꿈해 고정 행 높이를 넘고 아래 행 위에 겹쳐 그려졌다. 그 값은 이름이 아니라 상관추적
   키라 표에서 읽을 것이 없다. 필요하면 감사 로그에서 request_id 로 따라간다.
   annotation 열은 실서버 투영에 그 필드가 없어 어댑터가 빈 문자열을 박고 있었다.
   늘 비는 열은 데이터를 못 받은 화면과 구분되지 않는다.
   대신 대상과 요청자를 세운다. 둘 다 서버가 실제로 보내는 값이다 */
const RUN_COLUMNS: DataColumn<ChaosRun>[] = [
  { key: "started", label: "시각", width: "96px", render: (r) => formatTimeHMS(r.startedAt) },
  { key: "label", label: "내용", width: "minmax(200px, 1.4fr)", render: (r) => r.label },
  { key: "target", label: "대상", width: "minmax(140px, 1fr)", render: (r) => r.clearKey || "-" },
  { key: "by", label: "요청자", width: "110px", render: (r) => r.injectedBy || "-" },
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
  /* 실험 이력은 감사 로그에 영구 누적된다. 고정 상한으로 자르면 그 밖의 실험에 도달할 방법이 없다 */
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

  const running = (runs ?? []).filter((r) => r.state === "RUNNING");

  /* 카탈로그 표. 종전에는 카드 목록이었는데 1fr·1.4fr 로 영향 범위 위치가 행마다 흔들려
     아홉 행을 세로로 훑을 수가 없었다. 전 열 고정 폭 + 중앙 정렬로 축을 세운다.
     이름과 영향 범위만 좌측이다. 길이가 제각각인 문장을 중앙에 두면 좌우가 다 들쭉날쭉해진다 */
  const CATALOG_COLUMNS: DataColumn<ChaosExperimentDef>[] = [
    { key: "id", label: "실험", width: "72px", align: "center", render: (e) => <b className={styles.chaosId}>{e.id}</b> },
    {
      key: "label",
      label: "이름",
      width: "minmax(180px, 1fr)",
      // 상세 설명은 행에서 걷고 title·주입 확인 다이얼로그로 강등한다
      render: (e) => <span className={styles.chaosLabel} title={e.description}>{e.label}</span>,
    },
    { key: "blast", label: "영향 범위", width: "minmax(150px, 0.8fr)", render: (e) => e.blastRadius },
    {
      key: "severity",
      label: "등급",
      width: "110px",
      align: "center",
      // 등급을 글자로도 싣는다. 색이나 버튼 형태만으로 가르면 색 단독 표현이 된다.
      // "무엇이 깨지나"(영향 범위)와 "얼마나 깨지나"(등급)는 다른 질문이다
      render: (e) => (
        <span className={styles.chaosSeverity} data-severity={e.severity}>
          {SEVERITY_LABEL[e.severity]}
        </span>
      ),
    },
    {
      key: "action",
      label: "주입",
      width: "132px",
      align: "center",
      render: (e) =>
        running.some((r) => r.experimentId === e.id) ? (
          // 진행 중 실험에 주입 버튼을 남기면 상단 해제 카드와 어포던스가 상반된다
          <span className="status-pill" data-health="warning">
            ▲ 주입 중
          </span>
        ) : (
          <ExecuteButton
            payload={{ action: "chaos.inject", experimentId: e.id }}
            label="주입"
            disabled={!canChaos}
            tone={e.severity === "high" ? "destructive" : "neutral"}
            onDone={load}
            confirm={{
              title: "카오스 실험 주입",
              description: "확정 즉시 실제 장애 주입. 해제는 별도 조작 필요",
              target: `${e.id} · ${e.label}`,
              blastRadius: e.blastRadius,
              tone: "danger",
              confirmLabel: "주입",
            }}
          />
        ),
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader
        title="카오스 콘솔"
        desc="자동 주입 없음 · 전 실험 1인 실행 · 전량 감사"
        actions={res.stale ? <StaleBadge since={res.lastUpdatedAt} /> : undefined}
      />

      {/* 승인 대기 패널을 걷었다. 카오스가 1인 실행이 되면서 이 화면은 승인을 만들지 않고,
          비지 않는 날이 없는 패널은 데이터를 못 받은 화면과 구분되지 않는다(§3-6 v2.34).
          승인 원장 자체는 살아 있고 마켓·운영자·회원 화면이 그대로 쓴다 */}
      <div>
        <PanelCard title="진행 중 실험">
          {running.length === 0 ? (
            <div className="empty-state empty-state--compact">
              <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
              <span className="empty-state__title">주입 중인 실험 없음</span>
              <span className="empty-state__desc">카탈로그에서 주입하면 여기에 뜹니다</span>
            </div>
          ) : (
            <ul className={styles.runningList}>
              {running.map((r) => (
                <li key={r.id} className={styles.runningItem}>
                  <span className={styles.runningLabel}>
                    {r.experimentId} · {r.label}
                  </span>
                  <span className={styles.runningBlast}>{r.blastRadius}</span>
                  {/* 해제도 주입과 같은 경로다. 주입이 1인인데 해제만 2인이면 걷어낼 수 없는
                      장애를 만들 수 있다. 두 방향의 문턱은 같아야 한다 */}
                  <ExecuteButton
                    payload={{ action: "chaos.clear", runId: r.clearKey }}
                    label="해제"
                    disabled={!canChaos}
                    onDone={load}
                    confirm={{
                      title: "카오스 실험 해제",
                      description: "확정 즉시 주입 중인 장애가 걷힙니다.",
                      target: `${r.experimentId} · ${r.label}`,
                      blastRadius: r.blastRadius,
                      confirmLabel: "해제",
                    }}
                  />
                </li>
              ))}
            </ul>
          )}
        </PanelCard>
      </div>

      <PanelCard title="실험 카탈로그" actions={<span className={styles.panelMeta}>1인 실행 · 전량 감사</span>}>
        {!canChaos && <PermissionNote>카오스 권한(CHAOS_OPS 이상) 없음. 주입 불가</PermissionNote>}
        <DataTable
          columns={CATALOG_COLUMNS}
          rows={catalog}
          rowKey={(e) => e.id}
          /* 주입 버튼의 44px 히트영역(::after)이 행 안에 온전히 들어오는 최소 높이.
             종전 카드 목록이 min-height 52px 이었던 것과 같은 근거다 */
          rowHeight={56}
          ariaLabel="카오스 실험 카탈로그"
          defaultAlign="center"
        />
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
            defaultAlign="center"
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
