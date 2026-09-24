"use client";

/**
 * 용량(/admin/capacity). 조회 노드를 늘리고 줄인다(ADR 0015 수동 스케일링).
 *
 * 화면이 세 가지를 보인다. 있어야 할 노드 수와 실제로 응답한 노드 수, 노드 한 대씩의 상태,
 * 그리고 지금 무엇을 하면 되는지다. 판정은 서버(관리 API)가 하고 실행은 사람이 누른다.
 *
 * 문구 규칙. 이 화면은 처음 보는 사람이 읽는다. 내부 용어를 그대로 쓰지 않는다. 대상은 콘솔 용어대로
 * '노드'로 적고(디자인 시스템 §12-12), Scale-Out은 "노드 추가"로 적어 원어는 부제로만 병기한다. 문서 절
 * 번호를 화면에 싣지 않는다. 불러오는 동안과 실패했을 때도 화면 머리와 페이지 틀을 그대로 둔다(레이아웃 §7).
 *
 * 늘려도 안 되는 것을 숨기지 않는다. 주문은 마켓마다 리더 한 대가 처리하고(로컬 단일 리더는 한 대가 모든
 * 마켓을 맡는다) 이 화면으로 늘릴 수 없다. 리더가 죽으면 조회 서버를 늘려도 조회 용량만 돌아온다. 문구는 단일
 * 리더와 마켓별 리더(ADR 0034) 어느 쪽에서도 참이어야 한다.
 *
 * sim은 관제 시뮬레이터의 조회 ASG(read-1~3)를 읽고 움직인다. 관제센터와 서버·노드 화면의 대수, 노드 이름,
 * 증설·축소 임계가 이 화면과 같다. http는 로컬 조정기의 용량 API(/admin/v1/capacity)를 읽는다.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { AdminError, getAdminApi, isAdminHttp } from "@/lib/admin/api";
import type { CapacityAdvice, CapacityNode, CapacityState, SimStatus } from "@/lib/admin/types";
import PanelCard from "@/components/PanelCard";
import NodeStrip from "@/components/admin/NodeStrip";
import GrafanaLink, { grafanaConfigured } from "@/components/admin/GrafanaLink";
import DecisionFooter from "@/components/admin/DecisionFooter";
import OpsThrottle from "@/components/admin/OpsThrottle";
import SourceMark, { sourceWord } from "@/components/admin/SourceMark";
import Skeleton from "@/components/Skeleton";
import { HealthPill, PageHeader, PermissionNote, SimMark, StatTile } from "@/components/admin/ui";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { useCan } from "@/lib/admin/store";
import { toast } from "@/components/Toast";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

/** 권고 등급을 헬스 색으로 옮긴다. 시세색을 빌려 쓰지 않는다. */
const ADVICE_HEALTH = { ok: "success", info: "success", warn: "warning", danger: "danger" } as const;

/** 초당 요청 수는 누적값의 차로 낸다. 서버가 비율을 만들지 않는 이유는 두 표본 사이 간격을
 *  화면이 알기 때문이다. 첫 표본에는 이전 값이 없으므로 빈칸으로 둔다. */
type RateSample = { at: number; total: number };

/**
 * 노드별 초당 요청. 이전 조회의 표본과 이번 조회의 표본을 뺀다. 이전 표본이 없거나 값이 줄었으면
 * (재기동) null이다. 표본 시각은 조회가 성공한 시각이라 렌더가 몇 번 돌든 값이 같다.
 */
function sampleRates(
  prev: Map<string, RateSample>,
  nodes: CapacityNode[],
  at: number,
): { samples: Map<string, RateSample>; rates: Map<string, number | null> } {
  const samples = new Map<string, RateSample>();
  const rates = new Map<string, number | null>();
  for (const n of nodes) {
    if (n.httpRequests === null) {
      rates.set(n.id, null);
      continue;
    }
    samples.set(n.id, { at, total: n.httpRequests });
    const p = prev.get(n.id);
    const seconds = p ? (at - p.at) / 1000 : 0;
    rates.set(n.id, !p || n.httpRequests < p.total || seconds < 1 ? null : (n.httpRequests - p.total) / seconds);
  }
  return { samples, rates };
}

/** 이 화면은 부하 입력과 용량 조치를 한자리에 둔다. 그래서 두 원천을 함께 읽는다 */
interface CapacityView {
  cap: CapacityState;
  sim: SimStatus;
}

export default function CapacityPage() {
  const api = getAdminApi();
  // 부하를 넣는 레버와 그 결과로 움직이는 대수가 다른 화면에 있으면 시연이 화면 전환에서
  // 끊기고, 보는 쪽은 무엇이 무엇을 움직였는지 잇지 못한다
  const load = useCallback(
    async (): Promise<CapacityView> => {
      const [cap, sim] = await Promise.all([api.getCapacity(), api.getSimStatus()]);
      return { cap, sim };
    },
    [api],
  );
  const res = useAdminResource<CapacityView>(load, { isSessionError });
  const canScale = useCan("SUPER");
  const canOps = useCan("BOT_OPS");
  const [pending, setPending] = useState<"SCALE_OUT" | "SCALE_IN" | null>(null);
  const [busy, setBusy] = useState(false);
  const samples = useRef<Map<string, RateSample>>(new Map());
  const [rates, setRates] = useState<Map<string, number | null>>(new Map());

  // 표본은 조회 한 번에 하나다. 렌더마다 찍으면 같은 누적값끼리 빼서 manual 화면이 늘 0이나 빈칸이 된다
  const capData = res.data?.cap ?? null;
  const sampledAt = res.lastUpdatedAt;
  useEffect(() => {
    if (!capData || sampledAt === null) return;
    const next = sampleRates(samples.current, capData.nodes, sampledAt);
    samples.current = next.samples;
    setRates(next.rates);
  }, [capData, sampledAt]);

  const run = async (kind: "SCALE_OUT" | "SCALE_IN") => {
    setBusy(true);
    try {
      if (kind === "SCALE_OUT") {
        await api.scaleOut();
        toast("노드를 추가했습니다. 준비되면 조회를 나눠 받습니다", "success");
      } else {
        await api.scaleIn();
        toast("노드를 줄입니다. 진행 중인 요청을 먼저 흘려보냅니다", "success");
      }
      setPending(null);
      res.reload();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "요청 실패", "error");
    } finally {
      setBusy(false);
    }
  };

  const header = (
    <PageHeader
      title="용량·부하"
      mark={isAdminHttp() ? undefined : <SimMark />}
      desc="조회 노드를 늘리고 줄입니다. 주문을 처리하는 매칭 노드는 이 화면에서 늘리지 않습니다"
      actions={<ResourceStamp lastUpdatedAt={res.lastUpdatedAt} stale={res.stale} onReload={res.reload} />}
    />
  );
  if (res.loading || res.error || !res.data) {
    return (
      <div className={styles.page}>
        {header}
        {res.loading ? <Skeleton variant="card" width="100%" height={320} /> : <AdminErrorState reason={res.errorReason} onRetry={res.reload} />}
      </div>
    );
  }
  const d = res.data.cap;
  const sim = res.data.sim;
  const pct = d.readAvgCpuRatio === null ? null : Math.round(d.readAvgCpuRatio * 100);

  // 임계 판정의 정본은 서버 advice다. 화면이 0.60을 복제하면 서버가 값을 조일 때 갈린다
  const scaleAdvised = d.advice.some((a) => a.action === "SCALE_OUT");

  return (
    <div className={styles.page}>
      {header}

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

      <div className={styles.opsRow}>
        <PanelCard title="부하 입력">
          {!canOps && <PermissionNote>봇 제어 권한(BOT_OPS 이상) 없음</PermissionNote>}
          <OpsThrottle
            rate={sim.rate}
            currentOps={sim.currentOps}
            canOps={canOps}
            onApplied={res.reload}
          />
        </PanelCard>

      <PanelCard title="노드 대수" actions={<GrafanaLink board="msp-service" label="앱 CPU 추이" />}>
        <div className={styles.kpiGrid} data-cols="3">
          <StatTile
            label="지금 응답하는 조회 노드"
            value={d.observedTotal}
            unit="대"
            hero
            tone={d.reconciled ? undefined : "warning"}
            sub={d.reconciled ? "계약과 일치" : `있어야 할 수 ${d.plan.desired}대`}
            title="observed / desired"
          />
          <StatTile
            label="있어야 할 조회 노드"
            value={d.plan.desired}
            unit="대"
            sub={`최소 ${d.plan.min} · 최대 ${d.plan.max}`}
            title="desired capacity"
          />
          <StatTile
            label="조회 평균 사용률"
            value={pct === null ? sourceWord("MISSING") : pct}
            unit={pct === null ? undefined : "%"}
            /* 임계 판정은 서버 advice가 내린다. 화면이 숫자를 들면 두 곳이 갈린다. 정상은 색을 칠하지 않는다(§12-10) */
            tone={pct === null ? "warning" : scaleAdvised ? "warning" : undefined}
            sub={pct === null ? "판단 근거 없음" : "노드를 추가하면 분산"}
            /* 눈금도 서버 임계를 쓴다. 화면이 값을 복제하면 서버가 환경에 맞춰 조일 때
               판정과 눈금이 갈린다 */
            meter={
              pct === null
                ? undefined
                : { value: pct, max: 100, threshold: Math.round(d.thresholds.scaleOutCpuRatio * 100) }
            }
            title="average CPU of read-serving nodes"
          />
        </div>
        {!d.reconciled && (
          /* 종전 문구는 "약 17초 뒤 맞는다"라고 적어 조절기가 돌고 있음을 단정했다.
             콘솔에는 조절기 생존을 알 원천이 없다. 계약과 관측이 어긋났다는 사실만 적는다 */
          <p className={styles.capacityNote}>
            <SourceMark state="NOT_APPLIED" note={`계약 ${d.plan.desired}대 · 관측 ${d.observedTotal}대`} />
          </p>
        )}
        {!canScale && <PermissionNote>노드 수 변경은 총괄 권한이 필요합니다</PermissionNote>}
      </PanelCard>
      </div>

      <PanelCard title="노드 한 대씩" actions={<GrafanaLink board="msp-infra" label="노드 추이" />}>
        <NodeStrip nodes={d.nodes} perSecond={(n) => rates.get(n.id) ?? null} cpuMark={d.thresholds.scaleOutCpuRatio} />
      </PanelCard>

      <PanelCard title="무엇을 늘릴 수 있나">
        <table className={styles.capacityTable}>
          <thead>
            <tr>
              <th scope="col">축</th>
              <th scope="col">노드를 늘리면</th>
              <th scope="col">근거</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <th scope="row">조회</th>
              <td>용량 증가</td>
              <td>노드별 조회 분담</td>
            </tr>
            <tr>
              <th scope="row">주문</th>
              <td className={styles.stateText}>증가 없음</td>
              <td>
                주문은 마켓마다 순서를 지켜야 해서 마켓마다 리더 한 대가 처리합니다. 조회 노드를
                늘려도 리더는 그대로라 주문 처리량이 오르지 않습니다. 넘치는 주문은 받지 않고 거절합니다.
              </td>
            </tr>
            <tr>
              <th scope="row">데이터베이스</th>
              <td className={styles.stateText}>증가 없음</td>
              <td>
                데이터베이스는 쓰기를 나눌 수 없어 Primary 한 대가 처리합니다. Standby는 비동기 복제만
                받고 평소에는 트래픽을 받지 않습니다. 장애 때는 운영자가 수동으로 승격합니다.
              </td>
            </tr>
          </tbody>
        </table>
      </PanelCard>

      {/* 관측 주소가 없으면 링크가 null을 그린다. 빈 단계를 배열에 넣지 않아 화살표가 겹치지 않는다 */}
      <DecisionFooter
        steps={[
          "평균 사용률 확인",
          ...(grafanaConfigured() ? [<GrafanaLink key="svc" board="msp-service" label="앱 CPU 추이" />] : []),
          "권고에 따라 증설·축소",
        ]}
      />

      {pending && (
        <ConfirmDialog
          title={pending === "SCALE_OUT" ? "노드 한 대 추가" : "노드 한 대 축소"}
          description={
            pending === "SCALE_OUT"
              ? "새 노드가 준비되면 조회 트래픽을 나눠 받습니다. 준비되는 동안 서비스는 멈추지 않고, 노드가 느는 만큼 비용이 늘어납니다."
              : "진행 중인 요청을 먼저 흘려보낸 뒤 노드를 내립니다. 서비스는 멈추지 않습니다."
          }
          target={`조회 노드 ${d.plan.desired}대 → ${d.plan.desired + (pending === "SCALE_OUT" ? 1 : -1)}대`}
          confirmLabel={pending === "SCALE_OUT" ? "추가" : "줄이기"}
          tone={pending === "SCALE_OUT" ? "warning" : "danger"}
          busy={busy}
          onConfirm={() => run(pending)}
          onClose={() => setPending(null)}
        />
      )}
    </div>
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
        <button type="button" className={styles.neutralBtn} onClick={() => onAct(advice.action!)}>
          {advice.action === "SCALE_OUT" ? "노드 추가" : "노드 줄이기"}
        </button>
      )}
    </div>
  );
}
