"use client";

/**
 * 지표 관제(/admin/monitor). 서비스 흐름 화면이 재생하는 주행을 시계열과 인스턴스 표로 본다.
 * 서비스 흐름에는 구성도와 사건 기록만 남기고 차트는 이 화면으로 옮겼다(2026-09-23 사용자 결정).
 * 구성도 한 장과 차트 여섯 장을 한 화면에 쌓으면 시연 중에 스크롤을 오가느라 어느 조작 뒤에 무엇이
 * 움직였는지를 놓친다.
 *
 * 두 화면은 같은 주행을 본다(lib/admin/flowRun.ts). 서비스 흐름에서 DB 전환을 걸고 이 화면으로
 * 넘어오면 그 전환이 차트에 이어져 있다. 머리의 일시정지·초기화도 같은 주행에 걸린다.
 *
 * 백엔드에 연결하지 않았다. 재생하지 않는 동안은 관제 시뮬레이터의 한 판에서 쌓은 표본이라 관제센터와 같은
 * 값이고, 재생 중에는 lib/admin/flowSim.ts 의 재생 값이다. 실제 추이는 Grafana 가 소유한다(ADR 0032). 로컬
 * Docker 스택이 AWS 역할을 대신하므로 이름은 AWS 이름으로 적고 컨테이너 이름은 표에 따로 둔다.
 */

import dynamic from "next/dynamic";
import Link from "next/link";
import { useMemo } from "react";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import GrafanaLink, { grafanaConfigured } from "@/components/admin/GrafanaLink";
import { PageHeader, SimMark } from "@/components/admin/ui";
import { isAdminHttp } from "@/lib/admin/api";
import { hms } from "@/lib/admin/timeText";
import FlowRunControls from "@/components/admin/flow/FlowRunControls";
import InstanceTable from "@/components/admin/flow/InstanceTable";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/flow/metrics.module.css";
import { SCALE_OUT_ADVICE, nodeViews, type FlowEvent, type Health } from "@/lib/admin/flowSim";
import { useFlowClock, useFlowRun } from "@/lib/admin/flowRun";

/* 차트 라이브러리는 브라우저에서만 돈다. 서버 렌더에서 빼고, 불러오는 동안 1280px 화면에서 잰
   FlowMetrics 높이만큼 자리를 잡아 둔다. 그래야 차트가 뜰 때 아래 인스턴스 표가 밀려 내려가지 않는다 */
const METRICS_PX = 1054;
const FlowMetrics = dynamic(() => import("@/components/admin/flow/FlowMetrics"), {
  ssr: false,
  loading: () => <Skeleton variant="chart" width="100%" height={METRICS_PX} />,
});

/** 보조기술용 사건 표의 상한. 재생을 여러 번 돌려도 표가 끝없이 길어지지 않게 최근 것만 둔다 */
const MAX_EVENT_ROWS = 50;

const HEALTH_WORD: Record<Health, string> = { success: "정상", warning: "주의", danger: "장애" };

function clock(sec: number): string {
  return hms(new Date(sec * 1000).toISOString());
}

const NO_CPU = { api1: null, api2: null, read1: null, read2: null, read3: null };

export default function MonitorPage() {
  // 주행 시계는 보여 주는 화면이 하나씩 건다. 이 화면에 있는 동안만 틱이 나아간다
  useFlowClock();
  const key = useFlowRun((r) => r.key);
  const mode = useFlowRun((r) => r.mode);
  const waiting = useFlowRun((r) => r.waiting);
  const origin = useFlowRun((r) => r.origin);
  const state = useFlowRun((r) => r.state);
  const samples = useFlowRun((r) => r.samples);

  // 마지막 표본의 CPU 가 표와 구성도의 지금 값이다. 관제 값 모드는 첫 판이 오기 전에 표본이 없다(waiting)
  const cpuNow = samples[samples.length - 1]?.cpu ?? NO_CPU;
  // 조회 사용률 차트의 기준선. 관제 값이면 임계값 관리의 증설 임계, 재생이면 로컬 조정기의 권고 임계다
  const basis = useMemo(
    () => ({ live: state.live !== null, scaleOutPct: Math.round((state.live?.scaleOut ?? SCALE_OUT_ADVICE) * 100) }),
    [state.live],
  );
  const views = useMemo(() => nodeViews(state, cpuNow), [state, cpuNow]);

  // 같은 틱에 사건 둘이 겹치면(재생 첫 단계와 권고) 마지막 하나만 읽히지 않게 그 틱의 사건을 모두 싣는다
  const lastEvent = state.events[state.events.length - 1];
  const announce = lastEvent
    ? state.events
        .filter((e) => e.at === lastEvent.at)
        .map((e) => `${HEALTH_WORD[e.health]} ${e.label}`)
        .join(", ")
    : "";
  const eventRows: FlowEvent[] = state.events.slice(-MAX_EVENT_ROWS);

  return (
    <div className={`${adminStyles.page} ${styles.page}`}>
      <PageHeader
        title="지표 관제"
        mark={mode === "replay" ? <SimMark kind="replay" /> : isAdminHttp() ? undefined : <SimMark />}
        desc={mode === "replay" ? "서비스 흐름과 같은 재생 주행 · 실제 추이는 Grafana" : "관제센터와 같은 관제 판 값 · 실제 추이는 Grafana"}
        actions={<FlowRunControls />}
      />

      <div className={styles.metricsGroup}>
        {/* 제목 없는 도구 줄. Grafana 링크는 주소가 빌드에 없으면 그려지지 않으므로 조건으로 뺀다.
            서비스 흐름 링크는 늘 있어 줄이 비는 일이 없다 */}
        <div className={styles.toolbar}>
          {grafanaConfigured() && <GrafanaLink board="msp-service" label="Grafana 실제 추이" />}
          <Link href="/admin/flow" className={adminStyles.boardLink}>
            서비스 흐름 보기
          </Link>
        </div>
        {/* 초기화마다 열쇠가 바뀌어 차트를 새로 만든다. 이전 주행의 원점으로 잡은 시간축이 남지 않는다 */}
        {waiting ? (
          <Skeleton variant="chart" width="100%" height={METRICS_PX} />
        ) : (
          <FlowMetrics key={key} samples={samples} events={state.events} origin={origin} basis={basis} />
        )}
      </div>

      <PanelCard title="인스턴스 상태">
        <InstanceTable state={state} views={views} cpuNow={cpuNow} />
      </PanelCard>

      {/* 사건은 차트의 표식으로만 보인다. 새 사건을 낭독하고 지금까지의 사건을 표로 한 번 더 싣는다 */}
      <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {announce}
      </p>
      <table className="sr-only">
        <caption>재생 사건</caption>
        <thead>
          <tr>
            <th scope="col">시각</th>
            <th scope="col">사건</th>
          </tr>
        </thead>
        <tbody>
          {eventRows.length === 0 ? (
            <tr>
              <td colSpan={2}>사건 없음</td>
            </tr>
          ) : (
            eventRows.map((e, i) => (
              <tr key={`${e.at}-${i}`}>
                <td>{clock(origin + e.at)}</td>
                <td>
                  {HEALTH_WORD[e.health]} {e.label}
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
