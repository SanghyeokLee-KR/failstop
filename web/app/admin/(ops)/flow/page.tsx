"use client";

/**
 * 서비스 흐름(/admin/flow). AWS 목표 구성(v5)의 역할과 요청 경로를 한 화면에 그리는 시안이다.
 * 지금은 로컬 Docker exchange-chaos 스택(compose.yaml 이 포함하는 harness/docker-compose.chaos.yml)이
 * 그 역할을 대신한다. Docker 에서 먼저 되면 같은 화면을 AWS 로 옮긴다(2026-09-23 사용자 결정).
 * 노드 이름은 AWS 역할 이름이고, 그 역할을 맡은 컨테이너나 호스트 프로세스는 구성도가 옆에 적는다.
 *
 * 백엔드에 연결하지 않았다. 주행은 lib/admin/flowRun.ts 가 지표 관제(/admin/monitor)와 함께 들고 있고 두 모드가
 * 있다. 재생하지 않는 동안은 관제 시뮬레이터의 한 판에서 옮긴 관제 값이라 관제센터와 같은 숫자를 그린다. 노드를
 * 눌러 재생을 걸면 로컬 Docker 에서 잰 기록의 사건 순서를 재생하고 사건 사이의 값은 lib/admin/flowSim.ts 가
 * 지어낸다. 머리에는 설명 줄을 두지 않는다(2026-09-23 사용자 결정). 값의 출처는 화면 머리의 표식(시뮬레이션,
 * 재생 중에는 '재생 · 관제 값과 별개')과 무대 머리 줄 맨 왼쪽의 출처가 보여 준다.
 *
 * 무대 머리 줄은 출처 칩, 재생 중에만 서는 상황 자막, 크게 보기 순서다(관리자 설계 7-10 (i)). 자막이
 * 재생 표지와 같은 내용을 실어 이 화면의 머리 조작은 표지 없이 일시정지와 초기화만 둔다.
 *
 * 이 화면은 구성도만 둔다. 사건 기록 목록도 사용자 요청으로 뺐고 보조기술용 알림만 남겼다. 체결률, 리스, CPU, 사용률 추이는 지표 관제로 옮겼고, 리더·세대·
 * Primary 같은 판독 줄도 사용자 요청으로 뺐다. 그 값은 노드 팝오버에 있다.
 *
 * 채택하려면 결정부터 다시 봐야 한다. ADR 0032 와 docs/reference/adr/0099-current-architecture.md 는
 * 추이를 Grafana 에 맡기고 콘솔이 그리지 않게 했다. 원천 없이 지어낸 토폴로지는 8276cd7 에서
 * 버렸고, 카오스 주입 화면은 186570f 에서 걷어냈다. 시안으로 보여 주는 것까지가 이 파일의 범위다.
 *
 * 재생은 화면 안의 일이다. 컨테이너에 명령을 보내지 않고, 같은 일을 실제로 일으키는 호스트
 * 명령은 대화상자에서 복사만 할 수 있다.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import { PageHeader, SimMark } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import FlowTopology, { type FlowPick } from "@/components/admin/flow/FlowTopology";
import FlowRunControls from "@/components/admin/flow/FlowRunControls";
import FlowCaption from "@/components/admin/flow/FlowCaption";
import SourceChip from "@/components/admin/flow/SourceChip";
import ExperimentDialog from "@/components/admin/flow/ExperimentDialog";
import EventLog from "@/components/admin/flow/EventLog";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/flow/flow.module.css";
import { getAdminApi, isAdminHttp } from "@/lib/admin/api";
import { availability, describeScenario, nodeViews } from "@/lib/admin/flowSim";
import { replayBase, useFlowClock, useFlowRun } from "@/lib/admin/flowRun";
import { teamsDelivery } from "@/lib/admin/integrations";

/**
 * 구성도의 설계 폭. 구성도는 잰 폭에 맞춰 가로 위치만 늘리고 카드 크기는 그대로 둔다. 넓은 화면에
 * 그대로 펼치면 옆으로만 벌어지고 카드는 작게 남는다. 그래서 이 폭으로 배치한 뒤 남은 폭과 화면 높이
 * 가운데 먼저 닿는 쪽에 맞춰 통째로 확대·축소한다. 사용자 요청(2026-09-23)대로 어느 모니터에서나 같은
 * 그림이 한 화면에 꽉 차는 정적 페이지처럼 보인다. transform 한 번이라 배치를 다시 계산하지 않는다.
 * 하한은 구성도가 배치를 맞춘 가장 좁은 폭(FlowTopology W_BASE 956px)이다. 1180px 로 두면 세로로 긴 남은
 * 자리(1440x900 등)에서 폭에 먼저 닿아 구성도 아래가 140px 가까이 비었다
 */
const DESIGN_W = 956;
/** 화면이 구성도보다 가로로 길면 설계 폭을 이만큼까지 넓혀 비율을 맞춘다. 구성도는 폭이 늘면 가로 간격만
    벌어지므로 이 안에서는 모양이 무너지지 않는다. 그 덕에 확대한 구성도가 가로와 세로를 함께 채운다 */
const DESIGN_W_MAX = 1700;
/** 확대 상한. 아주 큰 화면에서 글자가 지나치게 커지지 않게 둔다 */
const MAX_ZOOM = 2.4;
/** 축소 하한. 작은 발표 화면에서 이보다 작아지면 글자를 못 읽으니 그때는 세로로 스크롤한다 */
const MIN_ZOOM = 0.62;
/** 셸의 본문 스크롤 상자(AdminShell 의 main#admin-main). 평소 보기의 남은 높이를 이 상자 기준으로 잰다 */
const SCROLL_ROOT_ID = "admin-main";
/** 관제 값 모드에서 첫 판을 기다리는 동안의 골격 높이. 구성도의 설계 높이(FlowTopology H)와 같다 */
const STAGE_H = 659;
const NO_CPU = { api1: null, api2: null, app3: null };

/**
 * 평소 보기에서 구성도가 쓸 수 있는 높이. 본문 스크롤 상자의 아래 끝에서 무대 본문 윗변을 빼고, 무대 아래에
 * 붙는 것들(카드 안쪽 여백, 페이지 아래 여백)의 높이를 한 번 더 뺀다. 그 높이는 고정값으로 두지 않고 지금
 * 배치에서 잰다. 여백 토큰이 바뀌어도 스크롤이 다시 생기지 않는다.
 *
 * 아래에 붙는 높이는 스크롤 상자의 scrollHeight 로 재지 않는다. scrollHeight 는 내용이 짧아도 상자 높이
 * 아래로 내려가지 않아서 빈 공간까지 아래 내용으로 센다. 그러면 남은 자리가 거의 0 이 되어 배율이 하한에
 * 붙는다. 대신 스크롤 상자 바로 아래의 페이지 요소를 찾아 그 아래 끝과 상자의 아래 안쪽 여백까지 잰다
 */
function roomBelow(body: HTMLElement): number {
  const root = document.getElementById(SCROLL_ROOT_ID);
  let top: HTMLElement | null = body;
  while (top && top.parentElement !== root) top = top.parentElement;
  if (!root || !top) return window.innerHeight - body.getBoundingClientRect().top;
  const b = body.getBoundingClientRect();
  const below =
    top.getBoundingClientRect().bottom -
    b.bottom +
    parseFloat(getComputedStyle(top).marginBottom) +
    parseFloat(getComputedStyle(root).paddingBottom);
  return root.getBoundingClientRect().bottom - b.top - Math.max(0, below);
}

export default function FlowPage() {
  // 주행 시계는 주행을 보여 주는 화면마다 하나씩 건다. 지표 관제로 옮겨도 같은 주행이 이어진다
  useFlowClock();
  // 선택자를 값 하나씩 둔다. 객체를 돌려주는 선택자는 매번 새 참조라 다시 그리기가 멈추지 않는다
  const key = useFlowRun((r) => r.key);
  const mode = useFlowRun((r) => r.mode);
  const waiting = useFlowRun((r) => r.waiting);
  const origin = useFlowRun((r) => r.origin);
  const state = useFlowRun((r) => r.state);
  // 구성도는 지금 CPU 만 쓴다. 표본 배열 대신 마지막 표본의 값을 고르면 참조가 표본마다 한 번만 바뀐다.
  // 관제 값 모드는 첫 판이 오기 전에 표본이 없어 그동안 무대를 골격으로 둔다(waiting)
  const cpuNow = useFlowRun((r) => r.samples[r.samples.length - 1]?.cpu ?? NO_CPU);
  // 보고 경로 자리(AI 운영 보고, Teams)는 설정 화면의 Teams 행과 같은 판정을 읽는다
  const loadDesk = useCallback(() => getAdminApi().getReports(), []);
  const desk = useAdminResource(loadDesk, { cadence: "idle" });
  const teams = teamsDelivery(desk.data);
  const delivery = useMemo(
    () => ({ state: teams.state, lastReportAt: teams.lastReportAt, lastSentAt: teams.last?.at ?? null }),
    [teams.state, teams.lastReportAt, teams.last?.at],
  );
  const [pick, setPick] = useState<FlowPick | null>(null);
  const [expanded, setExpanded] = useState(false);
  const expandBtnRef = useRef<HTMLButtonElement>(null);
  const closeBtnRef = useRef<HTMLButtonElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const innerRef = useRef<HTMLDivElement>(null);
  const [zoom, setZoom] = useState({ k: 1, h: 0, w: DESIGN_W });

  const onPick = useCallback((req: FlowPick) => setPick(req), []);
  const closeDialog = useCallback(() => setPick(null), []);

  // 멈춘 동안에도 재생은 걸 수 있다. 0초 단계만 바로 적용되고 나머지 단계는 재개한 뒤 틱을 따라 진행한다.
  // 막힌 재생이면 스토어가 아무것도 바꾸지 않는다. 대화상자는 닫히면서 여는 순간의 노드로 포커스를 돌려준다
  const startPicked = () => {
    if (pick === null) return;
    useFlowRun.getState().start(pick.id, pick.target);
    setPick(null);
  };

  const views = useMemo(() => nodeViews(state, cpuNow), [state, cpuNow]);
  // 관제 값 모드에서 고른 재생은 재생 세계의 첫 상태에서 설명하고 막힘을 판정한다
  const pickBase = pick === null ? null : mode === "live" ? replayBase(pick.id, pick.target) : state;

  /* 크게 보기. 구성도 상자를 뷰포트 전체로 띄우고, 되면 브라우저 전체 화면도 함께 건다. 페이지 전체를
     전체 화면으로 올리는 이유는 재생 대화상자가 body 포털이라서다. 구성도 상자만 전체 화면으로 올리면
     그 밖의 포털이 가려져 노드를 눌러도 대화상자가 보이지 않는다. 브라우저가 Esc 로 전체 화면을
     풀면 크게 보기도 같이 닫는다 */
  const closeExpanded = useCallback(() => {
    setExpanded(false);
    if (typeof document !== "undefined" && document.fullscreenElement) void document.exitFullscreen().catch(() => {});
  }, []);
  const openExpanded = () => {
    setExpanded(true);
    const root = document.documentElement;
    if (root.requestFullscreen) void root.requestFullscreen().catch(() => {});
  };
  useEffect(() => {
    if (!expanded) {
      // 닫힐 때 여는 버튼으로 포커스를 돌린다. 열기 전 자리를 잃지 않게 한다
      return;
    }
    closeBtnRef.current?.focus();
    const onFs = () => {
      if (!document.fullscreenElement) setExpanded(false);
    };
    // 대화상자가 떠 있을 때의 Esc 는 대화상자가 먼저 받는다. 크게 보기는 대화상자가 없을 때만 닫는다
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !document.querySelector('[role="dialog"][aria-modal="true"]')) closeExpanded();
    };
    document.addEventListener("fullscreenchange", onFs);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("fullscreenchange", onFs);
      document.removeEventListener("keydown", onKey);
      expandBtnRef.current?.focus();
    };
  }, [expanded, closeExpanded]);

  /* 남은 자리와 구성도의 본래 높이가 바뀔 때마다 배율을 다시 구한다. 크게 보기에서는 무대 본문이 뷰포트를
     채우므로 그 높이를 쓰고, 평소에는 구성도 윗변부터 화면 아래까지를 쓴다. 평소 보기의 무대 높이는 내용이
     정하므로 clientHeight 가 아니라 화면 높이에서 잰다 */
  useEffect(() => {
    const body = bodyRef.current;
    const inner = innerRef.current;
    if (!body || !inner) return;
    const fit = () => {
      const h = inner.offsetHeight;
      const availW = body.clientWidth;
      const availH = expanded ? body.clientHeight : roomBelow(body);
      if (h === 0 || availW === 0 || availH <= 0) return;
      // 구성도 높이는 폭과 무관하다. 남은 자리의 가로세로 비율에 맞춰 설계 폭을 먼저 정하고 배율을 구한다
      const w = Math.round(Math.min(DESIGN_W_MAX, Math.max(DESIGN_W, (availW / availH) * h)));
      const k = Math.max(MIN_ZOOM, Math.min(availW / w, availH / h, MAX_ZOOM));
      setZoom((z) => (Math.abs(z.k - k) < 0.005 && z.h === h && z.w === w ? z : { k, h, w }));
    };
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(body);
    ro.observe(inner);
    // 창 높이만 바뀌면 무대 본문 크기는 그대로라 관찰자가 모른다. 창 크기 변화도 함께 받는다
    window.addEventListener("resize", fit);
    return () => {
      ro.disconnect();
      window.removeEventListener("resize", fit);
    };
  }, [expanded]);

  return (
    <div className={styles.root}>
      <PageHeader
        title="서비스 흐름"
        mark={mode === "replay" ? <SimMark kind="replay" /> : isAdminHttp() ? undefined : <SimMark />}
        actions={<FlowRunControls indicator={false} />}
      />

      <PanelCard srTitle="서비스 구성">
        <div
          className={styles.stage}
          data-expanded={expanded || undefined}
          role={expanded ? "region" : undefined}
          aria-label={expanded ? "서비스 흐름 크게 보기" : undefined}
        >
          <div className={styles.stageBar}>
            {expanded && <span className={styles.stageTitle}>서비스 흐름</span>}
            <SourceChip mode={mode} />
            {/* 자막이 없을 때도 가운데 칸을 비워 두어 크게 보기 버튼이 오른쪽 끝에 남는다 */}
            <div className={styles.stageCaption}>
              <FlowCaption />
            </div>
            {expanded && <FlowRunControls indicator={false} />}
            {expanded ? (
              <button ref={closeBtnRef} type="button" className={adminStyles.neutralBtn} onClick={closeExpanded}>
                닫기
              </button>
            ) : (
              <button ref={expandBtnRef} type="button" className={adminStyles.neutralBtn} onClick={openExpanded}>
                크게 보기
              </button>
            )}
          </div>
          {/* 겉 상자는 확대된 크기만큼 자리를 잡고, 속 상자는 설계 폭으로 배치한 뒤 통째로 확대한다.
              크게 보기를 열고 닫아도 같은 요소라 구성도는 다시 붙지 않는다 */}
          <div className={styles.stageBody} ref={bodyRef}>
            <div className={styles.zoomBox} style={{ width: zoom.w * zoom.k, height: zoom.h * zoom.k }}>
              <div ref={innerRef} className={styles.zoomInner} style={{ width: zoom.w, transform: `scale(${zoom.k})` }}>
                {waiting ? (
                  <Skeleton variant="card" width="100%" height={STAGE_H} />
                ) : (
                  <FlowTopology state={state} views={views} onPick={onPick} delivery={delivery} />
                )}
              </div>
            </div>
          </div>
        </div>
        {/* 사건 기록 목록은 사용자 요청으로 뺐다. 사건 낭독과 보조기술용 표만 남긴다. 카드 안에 두어
            페이지 칸 사이 간격을 하나 더 만들지 않는다 */}
        <EventLog key={key} events={state.events} origin={origin} visible={false} />
      </PanelCard>

      {pick !== null && pickBase !== null && (
        <ExperimentDialog
          view={describeScenario(pickBase, pick.id, pick.target)}
          availability={availability(pickBase, pick.id, pick.target)}
          onStart={startPicked}
          onClose={closeDialog}
        />
      )}
    </div>
  );
}
