"use client";

/**
 * 용량(/admin/capacity). 조회 서버를 늘리고 줄인다(ADR 0015 수동 스케일링).
 *
 * 화면이 세 가지를 보인다. 있어야 할 서버 수와 실제로 응답한 서버 수, 서버 한 대씩의 상태,
 * 그리고 지금 무엇을 하면 되는지다. 판정은 서버가 하고 실행은 사람이 누른다.
 *
 * 문구 규칙. 이 화면은 처음 보는 사람이 읽는다. 내부 용어를 그대로 쓰지 않는다.
 * readiness는 "주문 받음", 타겟그룹 소속은 "받는 트래픽", Scale-Out은 "서버 추가"로 적고
 * 원어는 부제로만 병기한다. 문서 절 번호를 화면에 싣지 않는다.
 *
 * 늘려도 안 되는 것을 숨기지 않는다. 주문을 처리하는 서버는 한 대뿐이고 늘릴 수 없다.
 * 그 서버가 죽으면 서버를 추가해도 조회 용량만 돌아온다. 화면이 그렇게 적는다.
 */

import { useCallback, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type { CapacityAdvice, CapacityNode, CapacityState } from "@/lib/admin/types";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import { HealthPill, PageHeader, PermissionNote, StatTile } from "@/components/admin/ui";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { useCan } from "@/lib/admin/store";
import { toast } from "@/components/Toast";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

/** 권고 등급을 헬스 색으로 옮긴다. 시세색을 빌려 쓰지 않는다. */
const ADVICE_HEALTH = { ok: "success", info: "success", warn: "warning", danger: "danger" } as const;

/** 초당 요청 수는 누적값의 차로 낸다. 서버가 비율을 만들지 않는 이유는 두 표본 사이 간격을
 *  화면이 알기 때문이다. 첫 표본에는 이전 값이 없으므로 빈칸으로 둔다. */
type RateSample = { at: number; total: number };

export default function CapacityPage() {
  const api = getAdminApi();
  const load = useCallback(() => api.getCapacity(), [api]);
  const res = useAdminResource<CapacityState>(load, { intervalMs: 5000, isSessionError });
  const canScale = useCan("SUPER");
  const [pending, setPending] = useState<"SCALE_OUT" | "SCALE_IN" | null>(null);
  const [busy, setBusy] = useState(false);
  const rates = useRef<Map<string, RateSample>>(new Map());

  const run = async (kind: "SCALE_OUT" | "SCALE_IN") => {
    setBusy(true);
    try {
      if (kind === "SCALE_OUT") {
        await api.scaleOut();
        toast("서버 추가 요청됨. 준비되면 트래픽 수신", "success");
      } else {
        await api.scaleIn();
        toast("서버 축소 요청됨. 진행 중인 요청을 먼저 흘려보냄", "success");
      }
      setPending(null);
      res.reload();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "요청 실패", "error");
    } finally {
      setBusy(false);
    }
  };

  /** 누적 요청 수의 차로 초당 값을 낸다. 이전 표본이 없거나 값이 줄었으면(재기동) 빈칸이다. */
  const perSecond = (node: CapacityNode): number | null => {
    if (node.httpRequests === null) {
      rates.current.delete(node.id);
      return null;
    }
    const now = Date.now();
    const prev = rates.current.get(node.id);
    rates.current.set(node.id, { at: now, total: node.httpRequests });
    if (!prev || node.httpRequests < prev.total) return null;
    const seconds = (now - prev.at) / 1000;
    if (seconds < 1) return null;
    return (node.httpRequests - prev.total) / seconds;
  };

  if (res.loading) return <Skeleton variant="card" width="100%" height={320} />;
  if (res.error || !res.data) {
    return <AdminErrorState reason={res.errorReason} onRetry={res.reload} />;
  }
  const d = res.data;
  const pct = d.readAvgCpuRatio === null ? null : Math.round(d.readAvgCpuRatio * 100);

  return (
    <>
      <PageHeader
        title="용량"
        desc="조회를 받는 서버 증설과 축소. 주문을 처리하는 서버는 한 대 고정"
        actions={res.stale ? <StaleBadge since={res.lastUpdatedAt} /> : undefined}
      />

      {/* 권고. 표시이고 자동 조치가 아니다. 조치 버튼이 붙는 것만 사람이 누른다 */}
      <div className={styles.capacityAdviceList}>
        {d.advice.map((a) => (
          <AdviceCard
            key={a.id}
            advice={a}
            canScale={canScale}
            onAct={(k) => setPending(k)}
          />
        ))}
      </div>

      <PanelCard title="서버 대수">
        <div className={styles.kpiGrid} data-cols="3">
          <StatTile
            label="지금 응답하는 서버"
            value={d.observedTotal}
            unit="대"
            hero
            tone={d.reconciled ? "success" : "warning"}
            sub={d.reconciled ? "계약과 일치" : `있어야 할 수 ${d.plan.desired}대`}
            title="observed / desired"
          />
          <StatTile
            label="있어야 할 서버"
            value={d.plan.desired}
            unit="대"
            sub={`최소 ${d.plan.min} · 최대 ${d.plan.max}`}
            title="desired capacity"
          />
          <StatTile
            label="조회 평균 사용률"
            value={pct === null ? "읽지 못함" : pct}
            unit={pct === null ? undefined : "%"}
            tone={pct === null ? "warning" : pct >= 60 ? "warning" : "success"}
            sub={pct === null ? "판단 근거 없음" : "서버를 추가하면 이 값이 나뉜다"}
            meter={pct === null ? undefined : { value: pct, max: 100, threshold: 60 }}
            title="average CPU of read-serving nodes"
          />
        </div>
        {!d.reconciled && (
          <p className={styles.capacityNote}>
            요청한 대수와 실제 대수가 다르다. 서버 한 대를 세우는 데 약 17초가 걸리므로
            추가 직후에는 잠시 이 상태가 된다. 오래 지속되면 서버가 뜨지 못한 것이다.
          </p>
        )}
        {!canScale && <PermissionNote>서버 수 변경은 총괄 권한 필요</PermissionNote>}
      </PanelCard>

      <PanelCard title="서버 한 대씩">
        <div className={styles.capacityNodes}>
          {d.nodes.map((n) => (
            <NodeCard key={n.id} node={n} perSecond={perSecond(n)} />
          ))}
        </div>
      </PanelCard>

      <PanelCard title="무엇을 늘릴 수 있나">
        <table className={styles.capacityTable}>
          <thead>
            <tr>
              <th scope="col">축</th>
              <th scope="col">서버를 늘리면</th>
              <th scope="col">근거</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <th scope="row">조회</th>
              <td>
                <HealthPill health="success" label="용량 증가" />
              </td>
              <td>서버마다 조회를 나눠 받는다.</td>
            </tr>
            <tr>
              <th scope="row">주문</th>
              <td>
                <HealthPill health="warning" label="증가 없음" />
              </td>
              <td>
                주문은 순서를 지켜야 해서 한 대가 전부 처리한다. 서버를 늘려도 그 한 대가
                그대로라 처리량이 오르지 않는다. 넘치는 주문은 받지 않고 거절한다.
              </td>
            </tr>
            <tr>
              <th scope="row">데이터베이스</th>
              <td>
                <HealthPill health="warning" label="증가 없음" />
              </td>
              <td>
                데이터베이스는 쓰기를 나눌 수 없어 한 대가 처리한다. 대기 서버가 한 대 더 있지만
                평소에는 트래픽을 받지 않고 장애 때 대신 선다.
              </td>
            </tr>
          </tbody>
        </table>
      </PanelCard>

      {pending && (
        <ConfirmDialog
          title={pending === "SCALE_OUT" ? "서버 한 대 추가" : "서버 한 대 축소"}
          description={
            pending === "SCALE_OUT"
              ? "새 서버가 준비되면 조회 트래픽을 나눠 받는다. 약 17초가 걸리고 서비스는 멈추지 않는다. 서버가 늘면 비용도 는다."
              : "진행 중인 요청을 먼저 흘려보낸 뒤 서버를 내린다. 서비스는 멈추지 않는다."
          }
          target={`조회 서버 ${d.plan.desired}대 -> ${d.plan.desired + (pending === "SCALE_OUT" ? 1 : -1)}대`}
          confirmLabel={pending === "SCALE_OUT" ? "추가" : "줄이기"}
          tone={pending === "SCALE_OUT" ? "warning" : "danger"}
          busy={busy}
          onConfirm={() => run(pending)}
          onClose={() => setPending(null)}
        />
      )}
    </>
  );
}

function AdviceCard({
  advice,
  canScale,
  onAct,
}: {
  advice: CapacityAdvice;
  canScale: boolean;
  onAct: (kind: "SCALE_OUT" | "SCALE_IN") => void;
}) {
  const health = ADVICE_HEALTH[advice.level];
  return (
    <div className={styles.capacityAdvice} data-health={health}>
      <div>
        <HealthPill health={health} label={advice.headline} />
        <p className={styles.capacityNote}>{advice.detail}</p>
      </div>
      {advice.action && canScale && (
        <button type="button" className="btn" onClick={() => onAct(advice.action!)}>
          {advice.action === "SCALE_OUT" ? "서버 추가" : "서버 줄이기"}
        </button>
      )}
    </div>
  );
}

/**
 * 서버 한 대. 계약과 관측을 위아래로 나눠 싣는다.
 *
 * 응답하지 않는 서버는 관측 칸을 비운다. 마지막으로 본 값을 남기면 죽은 서버가 살아 있는
 * 것처럼 보인다.
 */
function NodeCard({ node, perSecond }: { node: CapacityNode; perSecond: number | null }) {
  const health = !node.reachable
    ? node.expected
      ? "danger"
      : "success"
    : node.cpuRatio !== null && node.cpuRatio >= 0.9
      ? "warning"
      : "success";
  const stateLabel = !node.reachable
    ? node.expected
      ? "응답 없음"
      : "꺼져 있음"
    : node.leader
      ? "주문 + 조회"
      : "조회만";
  const heapPct =
    node.heapUsedBytes !== null && node.heapMaxBytes !== null && node.heapMaxBytes > 0
      ? Math.round((node.heapUsedBytes / node.heapMaxBytes) * 100)
      : null;

  return (
    <div className={styles.nodeCard} data-health={health}>
      <div className={styles.nodeHead}>
        <strong>{node.id}</strong>
        <HealthPill health={health} label={stateLabel} />
      </div>
      <dl className={styles.capacityMeta}>
        <dt>가용영역</dt>
        <dd>{node.az || "미지정"}</dd>
        <dt>사양</dt>
        <dd>{node.instanceType || "미지정"}</dd>
        <dt>역할</dt>
        <dd>
          {node.engine ? "주문 처리 담당" : "조회 전용"}
          {!node.scalable && <span className={styles.capacityTag}>늘릴 수 없음</span>}
        </dd>
        <dt>계약</dt>
        <dd>{node.expected ? "떠 있어야 함" : "지금은 필요 없음"}</dd>
      </dl>
      {node.reachable ? (
        <dl className={styles.capacityMeta}>
          <dt>사용률</dt>
          <dd>
            {node.cpuRatio === null ? "읽지 못함" : `${Math.round(node.cpuRatio * 100)}%`}
            {node.cpuCount !== null && <span className={styles.capacityTag}>{node.cpuCount}코어</span>}
          </dd>
          <dt>메모리</dt>
          <dd>{heapPct === null ? "읽지 못함" : `${heapPct}%`}</dd>
          <dt>초당 요청</dt>
          <dd>{perSecond === null ? "측정 중" : `${Math.round(perSecond).toLocaleString()}건`}</dd>
          <dt>동시 접속</dt>
          <dd>{node.wsClients === null ? "없음" : `${Math.round(node.wsClients).toLocaleString()}명`}</dd>
        </dl>
      ) : (
        <p className={styles.capacityNote}>
          {node.expected
            ? "이 서버가 응답하지 않는다. 상태를 읽을 수 없다."
            : "지금 대수에서는 쓰지 않는 서버다."}
        </p>
      )}
    </div>
  );
}
