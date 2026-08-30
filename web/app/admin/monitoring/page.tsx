"use client";

/**
 * 모니터링(/admin/monitoring). 핵심 안전 검사·복구·노드·지표. 읽기 전용(§2-3).
 * 파괴 액션 버튼을 노출하지 않는다(§6 안전). 헬스 색 도메인만 사용, 시세색 차용 금지.
 * 배치: 검사 릴레이(1층) → 고가용성 KPI → 원장·처리량 → 노드 토폴로지 → 복구 이력.
 */

import { useCallback } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type { HaRecovery, HaStatus, InvariantCheck, RecoveryVerdict } from "@/lib/admin/types";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import { HealthPill, PageHeader, StatTile } from "@/components/admin/ui";
import InvariantRelayStrip from "@/components/admin/InvariantRelayStrip";
import NodeTopologyRail from "@/components/admin/NodeTopologyRail";
import RtoSpark from "@/components/admin/RtoSpark";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { formatKRW, formatTimeHMS } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

interface MonitoringData {
  ha: HaStatus;
  rec: HaRecovery;
  inv: InvariantCheck[];
}

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

// KPI 경보 임계. tone 판정과 미터 기준선이 같은 상수를 소비한다
const QUEUE_WARN = 200;
const P99_WARN_MS = 60;

/**
 * 미터 창. 고정 임계x2 창은 기준선이 모든 레일의 정확히 50%에 박혀 위치가 정보를 나르지 못했다.
 * 연속 자동 스케일(value 배수)은 경보 구간에서 fill을 80% 상수에 앉혀 절대 악화를 지운다.
 * 임계 배수의 이산 단계 창: 같은 단계 안에서 fill이 절대값으로 움직이고 단계 전환이 악화 신호다.
 */
const METER_WINDOW_STEPS = [1.2, 2, 4, 8];
function meterWindow(value: number, warn: number): number {
  const w = METER_WINDOW_STEPS.map((m) => Math.ceil(warn * m)).find((cap) => value <= cap * 0.95);
  return w ?? Math.ceil(warn * METER_WINDOW_STEPS[METER_WINDOW_STEPS.length - 1]);
}

// DataColumn.label이 문자열이라 헤더에 title을 실을 수 없어 원어 gloss는 셀 값 title로 싣는다
const RECOVERY_COLUMNS: DataColumn<HaRecovery["runs"][number]>[] = [
  { key: "startedAt", label: "시각", width: "90px", render: (r) => formatTimeHMS(r.startedAt) },
  {
    key: "replay_ms",
    label: "재생 소요",
    width: "90px",
    align: "right",
    // recovery_runs.duration_ms다. 페일오버 RTO가 아니다. 두 축은 다른 것을 잰다.
    // 실측 대역이 수십에서 수백 ms라 초 고정 표기는 전건 0.0초가 된다.
    render: (r) => (
      <span title="recovery_runs.duration_ms. 페일오버 RTO는 failover_runs가 원천">
        {r.durationMs < 1000 ? `${r.durationMs}ms` : `${(r.durationMs / 1000).toFixed(1)}초`}
      </span>
    ),
  },
    {
    key: "rpo",
    label: "유실",
    width: "80px",
    align: "right",
    // recovery_runs에 RPO 컬럼이 없다. 0을 채우면 화면이 유실 없음을 단정한다.
    render: (r) => <span title="RPO. 원천은 harness.failover_runs">{r.rpoMs === null ? "미측정" : `${r.rpoMs}ms`}</span>,
  },
  {
    key: "seq",
    label: "재현 구간(순번)",
    // 1fr로 두면 이 열이 슬랙을 전량 흡수해 행 한가운데 수백 px 공백이 생긴다. 슬랙은 마지막 열로
    width: "190px",
    align: "right",
    render: (r) => <span title="seq">{`${formatKRW(r.replayedFromSeq)} → ${formatKRW(r.replayedToSeq)}`}</span>,
  },
  {
    key: "matched",
    label: "재현 검증",
    width: "96px",
    render: (r) => (
      <span title="ReplayService의 이벤트 단위 대조 결과. fp 비교가 아니다">
        {r.matched ? <HealthPill health="success" label="일치" /> : <HealthPill health="danger" label="불일치" />}
      </span>
    ),
  },
  {
    // 재생 창 식별자다. 대조 대상이 아니라 무엇을 얼마나 재생했는지의 기록이고, 검증
    // 이벤트 0건과 실재 재생을 이 값으로 가른다. 서버가 스키마 1.2부터 낸다.
    key: "fp",
    label: "재생 창",
    // .cell에 overflow 처리가 없어 fp가 넘치면 44px 행이 2줄이 된다. 말줄임으로 접고 원문은 title이 보존한다
    width: "200px",
    render: (r) => (
      <span
        className={`num ${styles.cellEllipsis}`}
        title={r.fpReplay ? `fp_replay ${r.fpReplay} / fp_live ${r.fpLive}` : "미기록"}
      >
        {r.fpReplay || "-"}
      </span>
    ),
  },
  {
    key: "verdict",
    label: "판정",
    width: "minmax(96px, 1fr)",
    render: (r) =>
      r.verdict === "PASS" ? <HealthPill health="success" label="PASS" /> : <HealthPill health="danger" label="FAIL" />,
  },
];

/** 판정 셋을 같은 어휘로 칠한다. UNKNOWN은 통과도 실패도 아니라 별도 색이다. */
function verdictPill(label: string, v: RecoveryVerdict | null) {
  if (v === null) {
    return null;
  }
  const health = v === "PASS" ? "success" : v === "FAIL" ? "danger" : "warning";
  return <HealthPill health={health} label={`${label} ${v === "UNKNOWN" ? "미판정" : v}`} />;
}

export default function MonitoringPage() {
  const load = useCallback(async (): Promise<MonitoringData> => {
    const api = getAdminApi();
    const [ha, rec, inv] = await Promise.all([api.getHaStatus(), api.getRecovery(), api.getInvariants()]);
    return { ha, rec, inv };
  }, []);
  // GET 3건 합성. 2초 주기(90건/분)는 관리 레이트리밋(60건/분) 초과라 5초로 완화(36건/분)
  const { data, loading, error, errorReason, stale, lastUpdatedAt, reload } = useAdminResource(load, { intervalMs: 5000, isSessionError });

  return (
    <div className={styles.page}>
      <PageHeader title="모니터링" actions={stale ? <StaleBadge since={lastUpdatedAt} /> : undefined} />

      {loading ? (
        <div className={styles.pageStack}>
          {/* 릴레이 존·미터 타일·원장 행 실측 높이와 같은 값이라 로드 후 시프트 0 */}
          <Skeleton variant="card" width="100%" height={89} />
          <div className={styles.kpiGroup}>
            <Skeleton width={72} height={15} />
            <div className={styles.kpiGrid} data-cols="3">
              {Array.from({ length: 3 }, (_, i) => (
                <Skeleton key={i} variant="card" width="100%" height={122} />
              ))}
            </div>
          </div>
          <div className={styles.kpiGroup}>
            <Skeleton width={96} height={15} />
            <div className={styles.kpiGrid} data-cols="3">
              {Array.from({ length: 3 }, (_, i) => (
                <Skeleton key={i} variant="card" width="100%" height={106} />
              ))}
            </div>
          </div>
          {/* 하단 패널 2장(노드 상태·복구 이력)도 미러해 로드 완료 시 화면 전체 골격이 유지된다 */}
          <Skeleton variant="card" width="100%" height={240} />
          <Skeleton variant="card" width="100%" height={240} />
        </div>
      ) : error || !data ? (
        <AdminErrorState reason={errorReason} onRetry={reload} />
      ) : (
        <div className={`${styles.pageStack} ${styles.staleRegion}`} data-stale={stale ? "" : undefined}>
          <InvariantRelayStrip checks={data.inv} />

          <div className={styles.kpiGroup}>
            <span className={styles.kpiGroupLabel}>고가용성</span>
            <div className={styles.kpiGrid} data-cols="3">
              <StatTile
                label="매칭 리더"
                value={data.ha.leaderNodeId}
                sub={<span title="epoch">선출 세대 {data.ha.epoch}</span>}
              />
              <StatTile
                label="주문 처리 대기"
                title="sequencer queue"
                value={formatKRW(data.ha.sequencerQueueSize)}
                sub={`임계 ${formatKRW(QUEUE_WARN)}`}
                tone={data.ha.sequencerQueueSize > QUEUE_WARN ? "warning" : undefined}
                alarm={data.ha.sequencerQueueSize > QUEUE_WARN}
                meter={{ value: data.ha.sequencerQueueSize, max: meterWindow(data.ha.sequencerQueueSize, QUEUE_WARN), threshold: QUEUE_WARN }}
              />
              <StatTile
                label="주문 지연(상위 1%)"
                value={data.ha.orderLatencyP99Ms}
                unit="ms"
                sub={`임계 ${P99_WARN_MS}ms`}
                tone={data.ha.orderLatencyP99Ms > P99_WARN_MS ? "warning" : undefined}
                alarm={data.ha.orderLatencyP99Ms > P99_WARN_MS}
                meter={{ value: data.ha.orderLatencyP99Ms, max: meterWindow(data.ha.orderLatencyP99Ms, P99_WARN_MS), threshold: P99_WARN_MS }}
              />
            </div>
          </div>

          <div className={styles.kpiGroup}>
            <span className={styles.kpiGroupLabel}>원장 · 처리량</span>
            {/* 비대칭 1.6:1:1. 돈의 소실을 말하는 유일한 지표가 누적 카운터와 같은 폭이면 안 된다.
                정의 캡션("0이어야 정상")은 정보가 아니라 걷어내고, 위반 시에만 행동 지시가 발화한다 */}
            <div className={styles.kpiGrid} data-cols="ledger">
              <StatTile
                label="원장 불균형"
                value={data.ha.ledgerImbalance}
                sub={data.ha.ledgerImbalance === "0" ? undefined : "즉시 원장 대조 필요"}
                tone={data.ha.ledgerImbalance === "0" ? undefined : "danger"}
                alarm={data.ha.ledgerImbalance !== "0"}
                hero
              />
              <StatTile label="DB 연결 대기" title="HikariCP pending" value={data.ha.hikariPending} />
              <StatTile label="누적 체결" value={formatKRW(data.ha.tradesTotal)} unit="건" />
            </div>
          </div>

          <PanelCard title="노드 상태">
            <NodeTopologyRail nodes={data.ha.nodes} />
          </PanelCard>

          {/* 표는 limit로 자른 창이고 판정은 표 전체(또는 since 창) 집계다. 두 축을 함께
              띄우지 않으면 창 밖 위반을 화면이 보지 못한다(API§6-C). */}
          <PanelCard
            title="복구 이력"
            actions={
              <>
                <span className={styles.verdictScope}>
                  판정 범위 {data.rec.verdictScope === "since" ? "지정 창" : "전체"}
                </span>
                {verdictPill("재생", data.rec.replayVerdict)}
                {verdictPill("페일오버", data.rec.failoverVerdict)}
                <RtoSpark runs={data.rec.runs} />
              </>
            }
          >
            <DataTable
              columns={RECOVERY_COLUMNS}
              rows={data.rec.runs}
              rowKey={(r) => r.id}
              rowHeight={44}
              ariaLabel="복구 실행 이력"
              empty={
                <div className="empty-state">
                  <span className="empty-state__title">복구 이력 없음</span>
                </div>
              }
            />
          </PanelCard>
        </div>
      )}
    </div>
  );
}
