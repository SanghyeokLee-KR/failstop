"use client";

/**
 * 서비스 흐름 구성도. AWS 목표 구성(v5)을 AWS 아키텍처 그림의 문법으로 그린다. 지금은 로컬 Docker
 * exchange-chaos 스택이 그 역할을 맡는다(2026-09-23 사용자 결정). 카드에는 아이콘, AWS 역할 이름,
 * 상태 한 줄만 둔다. 그 역할을 대신하는 컨테이너 이름, 포트, 리스 같은 세부는 카드를 눌러 여는
 * 팝오버에만 싣는다. 값은 모두 flowSim 이 만든 시뮬레이션이라 여기서는 상태를 읽어 그리기만 한다.
 *
 * 그림의 뼈대는 사용자가 준 거래소 인프라 그림을 따르고, 칸은 정해 둔 격자에 맞춘다.
 *  - 구성도 세로 가운데를 지나는 가로줄(주축) 하나에 사용자, CloudFront, ALB, 앱 그룹 가운데 카드
 *    (app-2), Primary, Standby 가 선다. 그래서 사용자 경로, SQL 선, 복제선이 모두 곧은 가로선이다.
 *  - 열은 왼쪽부터 다섯이다. 0열 사용자는 AWS 틀 밖, 1열 엣지 칸은 AWS 틀 안이지만 리전 밖에서
 *    CloudFront 한 장만 감싼다. 서울 리전 틀 안의 VPC 틀에 2열(load-1 과 퍼블릭 칸의 ALB),
 *    3열(Auto Scaling 그룹), 4열(DB 칸의 Primary 와 Standby)이 서고, 맨 아래 bot-1 칸이 2~4열을 받친다.
 *  - 앱 계층은 따로 카드를 두지 않고 Auto Scaling 그룹 상자 자체로 강조한다(2026-09-23 사용자 요청).
 *    상자 머리가 min·max 와 지금 대수를 적고, 조정 중이면 테두리가 주황 점선과 ▲ 로 바뀐다.
 *    그룹 안은 위에서부터 app-1(리더 경쟁 1차), app-2, app-3(+1) 순서다. ALB 는 app-2 로 곧게,
 *    app-1 과 app-3 으로는 위아래로 한 번씩 꺾여 부채꼴로 갈라진다. 조회가 세 대에 나뉘는 모양이다.
 *  - DB 는 앱 계층 오른쪽에 Primary 와 Standby 를 나란히 둔다. 카드 제목은 지금 역할이라 DB 전환
 *    뒤에는 오른쪽 카드가 Primary 가 된다. SQL 선은 그때도 주축에서 그룹을 나오고, 그룹과 DB 칸 사이
 *    틈에서 위로 꺾여 DB 칸 위를 지나 새 Primary 윗변으로 들어간다. 바뀌는 것은 도착점뿐이다.
 *  - load-1 은 ALB 바로 위, 시장 봇은 ALB 바로 아래라 두 선이 ALB 가운데 세로줄 하나로 위아래에서
 *    들어온다. 관측은 DB 칸 바로 아래다. DB 수집선은 곧게 위로, 앱 수집선은 한 번 꺾여 그룹 밑변으로 간다.
 *  - CloudFront 는 이 대역에 없다. 사용자가 목표 구성대로 켜진 노드로 그리기로 했고 상태 글자와
 *    팝오버가 그 사실을 밝힌다. chaos-toxiproxy 는 AWS 에 없는 Docker 전용 장애 주입기라 그리지 않는다.
 *  - 학교·개인 계정 모두 NAT 없는 퍼블릭 서브넷 구성이라(ADR 0099) DB 와 bot-1 칸에 프라이빗
 *    서브넷이라는 이름을 붙이지 않는다. 역할 묶음으로만 적는다. 모든 틀 이름표는 AWS 그림처럼 왼쪽 위에
 *    선다. bot-1 칸은 이름표를 "bot-1" 로 줄여 시장 봇 선(ALB 가운데 세로줄)보다 앞에서 끝낸다. 한
 *    호스트라는 사실은 두 카드의 팝오버와 접근 이름이 싣는다.
 *  - 틀은 카드를 가로 p(956px 에서 11, 1180px 에서 16, 1700px 에서 20), 아래 16px 로 감싸고, 틀 안의
 *    틀도 같은 p 와 16px 로 떨어진다. 여러 겹의 테두리가 한 간격으로 층을 이뤄 등고선처럼 몰리지 않는다.
 *  - 주축의 카드 사이 거리는 틀이 몇 겹 끼든 같다. 사용자→CloudFront, →ALB, →app-2, →Primary 가 모두
 *    같은 d(1180px 에서 약 82px)이고, 한 칸 안의 Primary→Standby 만 그보다 좁은 열 틈(약 54px)이다.
 *  - 선에는 글자를 달지 않는다. 예외는 끊긴 선의 ■ 끊김 표식 하나다. 선 상태와 트래픽은
 *    FlowTraffic 이 그리고, 선의 이름과 나르는 것은 화면 낭독기용 연결 목록이 싣는다.
 *
 * 배치는 패널 안쪽 폭(clientWidth)만 보고 잡는다. 창 크기는 읽지 않는다. 크게 보기는 페이지가 설계
 * 폭으로 배치한 뒤 통째로 확대하므로 여기서는 받은 폭이 곧 배치 폭이다. 세로는 폭과 무관하게 고정이다.
 * 페이지가 높이를 재서 설계 폭을 고르므로 높이가 폭을 따라 바뀌면 둘이 서로를 쫓는다.
 */

import {
  Fragment,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";
import GrafanaLink, { grafanaConfigured } from "@/components/admin/GrafanaLink";
import adminStyles from "@/components/admin/admin.module.css";
import {
  APP_CONTAINER,
  APP_NAME,
  APP_PORT,
  BASE_OPS,
  DB_CONTAINER,
  DB_NAME,
  EDGE_IDS,
  LEASE_TICK,
  LEASE_TTL,
  SCALE_OUT_ADVICE,
  asgView,
  availability,
  describeScenario,
  edgeFlows,
  primaryDb,
  writable,
  type AppId,
  type DbId,
  type DbStatus,
  type EdgeFlow,
  type EdgeId,
  type EdgeKind,
  type EdgeTier,
  type FlowState,
  type NodeId,
  type NodeState,
  type NodeView,
  type ScenarioId,
  type TargetId,
} from "@/lib/admin/flowSim";
import { useFlowRun } from "@/lib/admin/flowRun";
import { FlowIcon, type FlowIconName } from "./FlowIcon";
import FlowTraffic, { ARROW_LEN, LineSwatch, TierSwatch, trafficSignature, type Pt, type Wire } from "./FlowTraffic";
import styles from "./topology.module.css";

/** 노드를 눌러 고른 재생. 대상은 노드를 지목하는 재생(리더 중단, 재기동, DB 전환, 재편입)에만 붙는다 */
export type FlowPick = { id: ScenarioId; target?: TargetId };

export interface FlowTopologyProps {
  state: FlowState;
  views: Record<NodeId, NodeView>;
  onPick: (req: FlowPick) => void;
}

/** 팝오버를 여는 자리. 노드 카드와 Auto Scaling 그룹 머리다 */
type PopKey = NodeId | "asg";

/* ===== 노드 상태의 모양과 말 =====
   색 하나로 알리지 않는다. 상태마다 상태 줄 앞의 형태와 카드 테두리 모양을 함께 바꾼다 */

const STATE_SHAPE: Record<NodeState, string> = {
  running: "●",
  transition: "▲",
  failure: "■",
  stopped: "○",
  absent: "",
};
const STATE_WORD: Record<NodeState, string> = {
  running: "실행",
  transition: "전환 중",
  failure: "장애",
  stopped: "중지",
  absent: "미구축",
};
/** 범례에 싣는 노드 상태. 미구축은 지금 그림에 없어 뺀다 */
const LEGEND_STATES: NodeState[] = ["running", "transition", "failure", "stopped"];

/* ===== 배치 ===== */

interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

/* 세로 좌표. 폭과 무관한 상수다. 위에서부터 AWS 틀 머리(이름과 범례), 리전 이름표 띠, VPC 이름표 띠,
   본문이다. 본문 맨 위 줄에 load-1 과 Auto Scaling 그룹이 함께 서고, 그룹 안 가운데 카드(app-2)의
   가운데가 주축이다. 모든 카드는 같은 크기다. 틀은 카드를 16px 로 감싸고 틀 안의 틀도 16px 떨어진다 */
const HEAD = 34;
/** 틀 머리 이름표 띠. 이름표(15px)와 위 5px, 아래 4px 여백이다 */
const BAND = 24;
/** 틀 안의 틀 사이 세로 여백. 바깥 틀 아래 가장자리가 안쪽 틀에서 떨어진 거리다 */
const NEST = 16;
/** 틀 아래 가장자리가 카드에서 떨어진 거리 */
const INSET = 16;
/** 카드 높이. 타일 28, 제목 16, 상태 15 와 그 사이 2px 두 번이 1.5px 테두리 안에 꼭 맞는다 */
const CARD_H = 66;
/** 그룹 안 카드 사이 */
const STACK_GAP = 12;
/** Auto Scaling 그룹 머리 단추. 이름, 범위와 사용률, 지금 대수 세 줄(16·14·14px)이다. 956px 폭의 그룹에는
    한 줄로 이어 쓸 자리가 없다. 틀 테두리(1.5px)에서 3px, app-1 에서 6px 띄운다. 6px 이면 포커스 고리
    (선 2px, 띄움 2px)가 app-1 테두리에 닿지 않는다 */
const ASG_HEAD_H = 44;
const ASG_HEAD_INSET = 3;
const ASG_HEAD_GAP = 6;
/** Auto Scaling 그룹 밑변과 bot-1 칸 윗변 사이. 다른 틀 사이와 같은 16px 다. 그룹 테두리가 한 단
    굵고 진해 두 틀이 붙어 보이지 않고, 주축이 구성도 세로 가운데에 한 걸음 더 다가간다 */
const HOST_GAP = 16;

const R_TOP = HEAD;
const V_TOP = R_TOP + BAND;
/** VPC 본문 맨 위. load-1 카드와 Auto Scaling 그룹 윗변이 여기 맞춰 선다 */
const C_TOP = V_TOP + BAND;
/** 그룹 안 카드 순서. 위에서부터 app-1, app-2, app-3 이고 가운데 app-2 가 ALB 에서 곧은 선을 받는다 */
const APP_TOP = C_TOP + ASG_HEAD_INSET + ASG_HEAD_H + ASG_HEAD_GAP;
const APP_Y: Record<AppId, number> = {
  api1: APP_TOP,
  api2: APP_TOP + CARD_H + STACK_GAP,
  api3: APP_TOP + (CARD_H + STACK_GAP) * 2,
};
const ASG_BOTTOM = APP_Y.api3 + CARD_H + INSET;
const AXIS = APP_Y.api2 + CARD_H / 2;
/** 주축 카드의 윗변. 사용자, CloudFront, ALB, DB 두 대가 이 높이에 선다 */
const ROW_Y = AXIS - CARD_H / 2;
/** 주축 카드 하나를 감싸는 칸(엣지, 퍼블릭, DB)의 위아래 */
const MID_TOP = ROW_Y - BAND;
const MID_BOTTOM = ROW_Y + CARD_H + INSET;
/** bot-1 칸. 그룹 밑변에서 HOST_GAP 아래에서 시작해 2~4열을 받친다 */
const HOST_TOP = ASG_BOTTOM + HOST_GAP;
const HOST_CARD_Y = HOST_TOP + BAND;
const HOST_BOTTOM = HOST_CARD_Y + CARD_H + INSET;
const VPC_BOTTOM = HOST_BOTTOM + NEST;
const REGION_BOTTOM = VPC_BOTTOM + NEST;
const H = REGION_BOTTOM + NEST;
/** DB 전환 뒤 SQL 선이 DB 칸 위로 지나는 높이. 칸 윗변보다 18px 위라 끊김 표식이 칸 테두리에 닿지 않는다 */
const SQL_HIGH = MID_TOP - 18;
/** ALB 오른변에서 app-1·app-3 으로 가는 선이 나가는 자리. 주축에서 위아래로 이만큼 떨어진다.
    강 단계 선의 빛(8px)끼리 12px 이 남아 세 갈래가 한 줄기로 뭉개지지 않는다 */
const FAN = 20;

/*
 * 가로. 관리 평면 최소 뷰포트 1280px 에서 패널 안쪽 폭이 956px 이다. 그보다 좁으면 MIN_W 로 두고
 * 가로로 넘친다. 한 줄에 카드 여섯 장(사용자, CloudFront, ALB, 앱, Primary, Standby)이 서고, 틀 여백은
 * 모두 같은 p 라 층마다 같은 간격으로 겹친다.
 *  - 카드 폭 112px 는 가장 긴 상태 줄 "● 10 OPS · 주문 실패"(형태와 틈을 합쳐 약 99px)에 좌우 여백
 *    4px 와 테두리를 더한 값이다. 956px 에서 여백 p 는 11px 이다.
 *  - 남는 폭은 틀과 틀 사이가 아니라 카드와 카드 사이가 같아지게 나눈다. 카드마다 끼는 틀의 겹수가
 *    달라(사용자→CloudFront 는 두 겹, CloudFront→ALB 는 네 겹) 틀 사이를 같게 두면 가장 중요한 사용자
 *    경로가 가장 좁고 앱 쪽이 두 배 가까이 벌어졌다. 주축 네 틈은 같은 d, Primary→Standby 는 한 칸 안이라
 *    d 의 DB_RATIO 배다. 엣지와 리전 틀 사이는 p 보다 좁히지 않는다. 956px 에서는 그 틈만 d 보다 조금 넓다.
 *  - 퍼블릭과 그룹, 그룹과 DB 칸 사이(d - 2p)와 Primary→Standby 틈에 끊김 표식이 앉는다. 틈이 CUT_ROOM
 *    보다 좁으면 그 틈의 표식을 ■ 하나로 줄인다. 956px 에서는 29·33px 라 ■, 1180px 부터는 50·54px 라
 *    "■ 끊김" 전체가 앉는다.
 *  - 넓어지면(크게 보기 설계 폭 1180~1700px) 카드, 여백, 틈이 함께 자라 모양이 그대로 커진다.
 */
const MIN_W = 940;
const W_BASE = 956;
const W_DESIGN = 1180;
const W_WIDE = 1700;
/** "■ 끊김" 표식(테두리까지 약 40px)이 양쪽 틀·카드에서 5px 이상 떨어져 앉는 가장 좁은 틈 */
const CUT_ROOM = 50;
/** Primary→Standby 틈을 주축 카드 사이 거리의 몇 배로 둘지. 1180px 에서 두 끊김 자리(그룹 양옆 틈과 이 틈)가
    모두 CUT_ROOM 을 넘고, 이 틈에서는 표식이 화살촉을 뺀 구간에 들어가도 양쪽에 6px 가 남는 값이다 */
const DB_RATIO = 0.66;
/** Auto Scaling 머리의 둘째 줄을 "min 2 · max 3 · 사용률 58%"(약 129px)로 다 쓰는 가장 좁은 글자 폭.
    1116px(글자 폭 133px)부터 긴 꼴이고, 956px(118px)에서는 "2~3대 · 사용률 58%"(약 93px)로 줄인다 */
const ASG_META_ROOM = 132;

// 서버 렌더에는 레이아웃이 없어 useLayoutEffect 가 할 일이 없다. 같은 자리를 useEffect 로 받는다
const useIsoLayoutEffect = typeof window === "undefined" ? useEffect : useLayoutEffect;

interface Layout {
  width: number;
  aws: Box;
  edge: Box;
  region: Box;
  vpc: Box;
  pub: Box;
  asg: Box;
  /** Auto Scaling 그룹 머리 단추 */
  asgHead: Box;
  db: Box;
  host: Box;
  nodes: Record<NodeId, Box>;
  /** ALB 에서 app-1·app-3 으로 갈라지는 세로 줄. 퍼블릭 칸과 그룹 사이 틈의 가운데다 */
  bus: number;
  /** load-1 과 시장 봇 선이 ALB 로 들어가는 세로줄. ALB 가운데다 */
  spine: number;
  /** 퍼블릭·그룹·DB 칸 사이 틈이 좁아 그 틈의 끊김 표식을 ■ 하나로 줄인다 */
  tight: boolean;
  /** Primary→Standby 틈이 좁아 복제선의 끊김 표식을 ■ 하나로 줄인다 */
  tightDb: boolean;
  /** Auto Scaling 머리 둘째 줄을 긴 꼴로 쓸 자리가 있다 */
  asgWide: boolean;
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));

/**
 * 폭 하나로 모든 상자를 잡는다. 956px 에서 1180px 까지 카드 폭 112→122px, 여백 11→16px 로 자라고,
 * 1700px 까지 다시 180px, 20px 로 자란다. 남는 폭은 주축 카드 사이 거리가 같아지게 나눈다. 모든
 * 가장자리를 정수 px 에 맞춰 1px 테두리가 번지지 않게 한다
 */
function computeLayout(measured: number): Layout {
  const W = Math.max(measured, MIN_W);
  const t1 = clamp01((W - W_BASE) / (W_DESIGN - W_BASE));
  const t2 = clamp01((W - W_DESIGN) / (W_WIDE - W_DESIGN));
  const c = Math.round(112 + 10 * t1 + 58 * t2);
  const p = Math.round(11 + 5 * t1 + 4 * t2);
  // 주축 카드 사이 다섯 틈의 합. 카드 여섯 장과 Standby 뒤의 틀 네 겹(DB 칸, VPC, 리전, AWS)을 뺀 폭이다.
  // 틈 안의 틀 여백은 사용자→CloudFront 두 번, CloudFront→ALB 네 번, ALB→app-2 와 app-2→Primary 두 번씩이다
  const sum = Math.max(0, W - 6 * c - 4 * p);
  let d = sum / (4 + DB_RATIO);
  // CloudFront→ALB 사이에는 엣지 칸과 리전 틀 사이가 끼어 있다. 그 사이가 p 보다 좁으면 두 점선 틀이
  // 한 틀의 안팎처럼 붙어 보이므로 그 틈만 5p 로 두고 나머지 세 틈과 DB 틈을 다시 나눈다
  let cdnGap = d;
  if (d < 5 * p) {
    cdnGap = 5 * p;
    d = Math.max(0, sum - cdnGap) / (3 + DB_RATIO);
  }
  const dbGap = d * DB_RATIO;
  /** 사용자 카드와 AWS 틀 사이 */
  const outer = Math.max(0, d - 2 * p);
  /** 엣지 칸과 리전 틀 사이 */
  const seam = cdnGap - 4 * p;
  /** 퍼블릭 칸과 그룹, 그룹과 DB 칸 사이 */
  const gap = Math.max(0, d - 2 * p);

  const r = Math.round;
  let x = c;
  const awsX = r((x += outer));
  const edgeX = r((x += p));
  const cdnX = r((x += p));
  const edgeR = r((x += c + p));
  const regionX = r((x += seam));
  const vpcX = r((x += p));
  const pubX = r((x += p));
  const albX = r((x += p));
  const pubR = r((x += c + p));
  const asgX = r((x += gap));
  const appX = r((x += p));
  const asgR = r((x += c + p));
  const dbX = r((x += gap));
  const pg1X = r((x += p));
  const pg2X = r((x += c + dbGap));
  const dbR = r((x += c + p));
  const vpcR = r((x += p));
  const regionR = r((x += p));
  const awsR = Math.max(W, regionR + p);

  const card = (cx: number, y: number): Box => ({ x: cx, y, w: c, h: CARD_H });
  const nodes: Record<NodeId, Box> = {
    users: card(0, ROW_Y),
    cdn: card(cdnX, ROW_Y),
    load: card(albX, C_TOP),
    alb: card(albX, ROW_Y),
    api1: card(appX, APP_Y.api1),
    api2: card(appX, APP_Y.api2),
    api3: card(appX, APP_Y.api3),
    pg1: card(pg1X, ROW_Y),
    pg2: card(pg2X, ROW_Y),
    // bot-1 칸의 두 카드는 위 열에 맞춘다. 시장 봇은 ALB 바로 밑, 관측은 DB 칸 가운데 밑이다
    bot: card(albX, HOST_CARD_Y),
    obs: card(r((dbX + dbR - c) / 2), HOST_CARD_Y),
  };
  const span = (x0: number, x1: number, y0: number, y1: number): Box => ({ x: x0, y: y0, w: x1 - x0, h: y1 - y0 });
  // load-1 과 시장 봇 선은 ALB 가운데 세로줄로 퍼블릭 칸과 bot-1 칸의 윗변을 지난다. 두 칸의 이름표
  // (왼쪽 끝에서 퍼블릭 약 55px, bot-1 약 53px)는 이 줄(956px 에서 67px)보다 앞에서 끝난다
  const spine = albX + c / 2;
  const headW = asgR - asgX - ASG_HEAD_INSET * 2;
  return {
    width: awsR,
    aws: span(awsX, awsR, 0, H),
    edge: span(edgeX, edgeR, MID_TOP, MID_BOTTOM),
    region: span(regionX, regionR, R_TOP, REGION_BOTTOM),
    vpc: span(vpcX, vpcR, V_TOP, VPC_BOTTOM),
    pub: span(pubX, pubR, MID_TOP, MID_BOTTOM),
    asg: span(asgX, asgR, C_TOP, ASG_BOTTOM),
    asgHead: {
      x: asgX + ASG_HEAD_INSET,
      y: C_TOP + ASG_HEAD_INSET,
      w: headW,
      h: ASG_HEAD_H,
    },
    db: span(dbX, dbR, MID_TOP, MID_BOTTOM),
    host: span(pubX, dbR, HOST_TOP, HOST_BOTTOM),
    nodes,
    bus: r((pubR + asgX) / 2),
    spine,
    tight: gap < CUT_ROOM,
    tightDb: dbGap < CUT_ROOM,
    // 머리 단추의 좌우 여백 6px 씩을 뺀 글자 폭
    asgWide: headW - 12 >= ASG_META_ROOM,
  };
}

const right = (b: Box) => b.x + b.w;
const bottom = (b: Box) => b.y + b.h;
const midX = (b: Box) => b.x + b.w / 2;
const midY = (b: Box) => b.y + b.h / 2;

/* ===== 연결선 =====
   선의 모양과 트래픽은 FlowTraffic 이 그린다. 여기서는 점열(흐름 방향 순서)과 끊김 표식 자리만 잡는다.
   점열은 배치와 두 가지 사건(앱이 보는 DB, 복제 방향)에만 기대므로 매초 새로 만들지 않는다.
   모든 선은 가로세로로만 꺾이고 카드와 틀 이름표를 가로지르지 않는다. 끊김 표식은 늘 열 사이 틈의
   가운데에 앉아 카드와 틀 테두리를 덮지 않는다. 틈이 좁으면 표식을 ■ 하나로 줄인다(tight) */

const otherDb = (id: DbId): DbId => (id === "pg1" ? "pg2" : "pg1");

function buildWires(L: Layout, endpoint: DbId, replSrc: DbId): Wire[] {
  const n = L.nodes;
  const tight = L.tight;
  // ALB 오른변의 세 출구. 가운데는 app-2 로 곧게 가고 위아래는 틈 가운데 세로 줄에서 app-1·app-3 으로
  // 한 번씩 꺾인다. 세 선의 끊김 표식은 그 세로 줄 위에 한 열로 선다
  const up = AXIS - FAN;
  const down = AXIS + FAN;
  // 그룹과 DB 칸 사이 틈의 가운데. SQL 선의 끊김 표식이 앉고, DB 전환 뒤에는 SQL 선이 여기서 위로 꺾인다
  const gapMid = Math.round((right(L.asg) + L.db.x) / 2);

  // SQL 선은 앱 계층(그룹 상자)에서 앱이 보는 DB 로 간다. 늘 주축에서 그룹을 나온다. db-1 이면 그대로
  // 곧게 가고, DB 전환으로 db-2 를 보면 틈 가운데에서 DB 칸 위로 올라 db-2 윗변으로 내려간다. DB 칸
  // 아래는 관측으로 가는 수집선이 세로로 지나므로 그쪽으로 돌리면 두 선이 엇갈린다
  const sql: Pick<Wire, "pts" | "cut" | "tight"> =
    endpoint === "pg1"
      ? {
          pts: [
            [right(L.asg), AXIS],
            [n.pg1.x, AXIS],
          ],
          cut: [gapMid, AXIS],
          tight,
        }
      : {
          pts: [
            [right(L.asg), AXIS],
            [gapMid, AXIS],
            [gapMid, SQL_HIGH],
            [midX(n.pg2), SQL_HIGH],
            [midX(n.pg2), n.pg2.y],
          ],
          // DB 칸 위의 가로 토막은 넓어 표식 전체가 앉는다
          cut: [(gapMid + midX(n.pg2)) / 2, SQL_HIGH],
        };

  // 복제는 Primary 에서 Standby 로 흐른다. Primary 가 없는 전환 공백에는 앱이 보던 DB 쪽에서 긋는다.
  // 끊김 표식은 틈 가운데가 아니라 화살촉을 뺀 구간의 가운데에 앉는다. DB 전환 뒤 화살이 옛 Primary 로
  // 돌아서면 틈 가운데의 표식이 촉에 붙어 "<■"로 읽혔다
  const replCut: Pt = [
    (right(n.pg1) + n.pg2.x + (replSrc === "pg1" ? -ARROW_LEN : ARROW_LEN)) / 2,
    AXIS,
  ];
  const replPts: Pt[] =
    replSrc === "pg1"
      ? [
          [right(n.pg1), AXIS],
          [n.pg2.x, AXIS],
        ]
      : [
          [n.pg2.x, AXIS],
          [right(n.pg1), AXIS],
        ];

  return [
    {
      id: "users-cdn",
      pts: [
        [right(n.users), AXIS],
        [n.cdn.x, AXIS],
      ],
    },
    {
      id: "cdn-alb",
      pts: [
        [right(n.cdn), AXIS],
        [n.alb.x, AXIS],
      ],
    },
    {
      id: "load-alb",
      pts: [
        [L.spine, bottom(n.load)],
        [L.spine, n.alb.y],
      ],
    },
    {
      id: "bot-alb",
      pts: [
        [L.spine, n.bot.y],
        [L.spine, bottom(n.alb)],
      ],
    },
    {
      id: "alb-api1",
      pts: [
        [right(n.alb), up],
        [L.bus, up],
        [L.bus, midY(n.api1)],
        [n.api1.x, midY(n.api1)],
      ],
      cut: [L.bus, (up + midY(n.api1)) / 2],
      tight,
    },
    {
      id: "alb-api2",
      pts: [
        [right(n.alb), AXIS],
        [n.api2.x, AXIS],
      ],
      cut: [L.bus, AXIS],
      tight,
    },
    {
      id: "alb-api3",
      pts: [
        [right(n.alb), down],
        [L.bus, down],
        [L.bus, midY(n.api3)],
        [n.api3.x, midY(n.api3)],
      ],
      cut: [L.bus, (down + midY(n.api3)) / 2],
      tight,
    },
    { id: "app-db", ...sql },
    { id: "repl", pts: replPts, cut: replCut, tight: L.tightDb },
    // 수집선의 방향은 지표가 가는 쪽(계층에서 관측으로)이다. 앱 계층 선은 그룹 밑변 가운데에서 내려와
    // 한 번 꺾여 관측 왼변으로, DB 선은 DB 칸 밑변 가운데에서 곧게 관측 윗변으로 들어간다
    {
      id: "scrape-app",
      pts: [
        [midX(L.asg), bottom(L.asg)],
        [midX(L.asg), midY(n.obs)],
        [n.obs.x, midY(n.obs)],
      ],
      minor: true,
    },
    {
      id: "scrape-db",
      pts: [
        [midX(L.db), bottom(L.db)],
        [midX(L.db), n.obs.y],
      ],
      cut: [midX(L.db), (bottom(L.db) + L.host.y) / 2],
      minor: true,
    },
  ];
}

/* 선 상태와 세기의 말. 화면 낭독기용 연결 목록과 범례가 쓴다. 건강하지만 지나는 것이 없는 선은
   "유휴"다. 카드의 "대기"는 멈춘 부하 발생기(회색 꺼짐 선)라 같은 말을 쓰면 범례에서 엉뚱한 선을 찾는다 */
const KIND_WORD: Record<EdgeKind, string> = {
  flow: "흐름",
  idle: "유휴",
  off: "꺼짐",
  down: "끊김",
  transition: "전환 중",
};
const TIER_WORD: Record<EdgeTier, string> = { 0: "", 1: "약", 2: "중", 3: "강" };

/**
 * 연결 목록의 한 줄. 양끝 이름, 선이 나르는 것, 상태와 세기다. 초당 건수는 싣지 않는다. 매초
 * 바뀌는 값이라 목록을 읽는 도중 내용이 달라진다. 상태와 세기는 사건 때만 바뀐다
 */
function edgeText(id: EdgeId, s: FlowState, views: Record<NodeId, NodeView>, f: EdgeFlow): string {
  const name = (key: NodeId) => views[key].name;
  const src = primaryDb(s) ?? s.endpoint;
  const ends: Record<EdgeId, [string, string, string]> = {
    "users-cdn": [name("users"), name("cdn"), "요청 · 목표 구성"],
    "cdn-alb": [name("cdn"), name("alb"), "요청 · 목표 구성 · Docker 대역은 ALB 직결"],
    "bot-alb": [name("bot"), name("alb"), "주문 API"],
    "load-alb": [name("load"), name("alb"), "조회 부하"],
    "alb-api1": [name("alb"), name("api1"), "쓰기 1차 · 조회"],
    "alb-api2": [name("alb"), name("api2"), "쓰기 백업 · 조회"],
    // 조회 업스트림에는 늘 적혀 있고 조정기가 down 표식만 넣고 뺀다
    "alb-api3": [name("alb"), name("api3"), s.apps.api3.inRead ? "조회" : "조회 · down 표식"],
    "app-db": ["앱 계층", name(s.endpoint), `SQL → ${DB_NAME[s.endpoint]}`],
    repl: [name(src), name(otherDb(src)), "비동기 복제"],
    "scrape-app": ["앱 계층", name("obs"), "지표 수집"],
    "scrape-db": ["DB 계층", name("obs"), "지표 수집"],
  };
  const [from, to, what] = ends[id];
  const word = f.kind === "flow" ? `${KIND_WORD.flow} ${TIER_WORD[f.tier]}` : KIND_WORD[f.kind];
  return `${from} → ${to} · ${what} · ${word}`;
}

/**
 * 범례의 선 모양. 흐름은 약·중·강 세 토막을 나란히 그린 견본 하나로 세 단계를 함께 적는다. 끊김과
 * 전환 중은 선 위 표식의 형태(■ ▲)를 앞에 붙이고, 그 형태는 구성도의 표식과 같은 상태색을 받는다
 */
const LINE_LEGEND: { kind: EdgeKind; tier?: EdgeTier; minor?: boolean; ramp?: boolean; mark?: NodeState; label: string }[] = [
  { kind: "flow", ramp: true, label: "흐름 약·중·강" },
  { kind: "idle", label: KIND_WORD.idle },
  { kind: "off", label: KIND_WORD.off },
  { kind: "down", mark: "failure", label: KIND_WORD.down },
  { kind: "transition", mark: "transition", label: KIND_WORD.transition },
  { kind: "flow", tier: 1, minor: true, label: "수집" },
];

/* ===== 카드 부품 ===== */

type TileIcon = FlowIconName | [FlowIconName, FlowIconName];

const ICON: Record<NodeId, TileIcon> = {
  users: "user",
  cdn: "globe",
  alb: "lb",
  api1: "springboot",
  api2: "springboot",
  api3: "springboot",
  pg1: "postgresql",
  pg2: "postgresql",
  bot: "bot",
  obs: ["prometheus", "grafana"],
  load: "load",
};

/** DB 카드 제목. 노드 이름(db-1·db-2) 대신 지금 역할을 적어 전환 뒤에도 어느 쪽이 쓰기를 받는지 바로 읽힌다 */
const DB_TITLE: Record<DbStatus, string> = {
  primary: "Primary",
  standby: "Standby",
  stopped: "옛 Primary",
  rejoining: "Standby 재편입",
};

const isApp = (id: PopKey): id is AppId => id === "api1" || id === "api2" || id === "api3";
const isDb = (id: PopKey): id is DbId => id === "pg1" || id === "pg2";

/** 카드에 보이는 제목. bot-1 칸의 두 카드는 칸 이름표가 bot-1 을 이미 적어 뒷부분만 둔다 */
function cardTitle(key: NodeId, v: NodeView, s: FlowState): string {
  if (isDb(key)) return DB_TITLE[s.db[key]];
  if (key === "bot" || key === "obs") return v.name.replace(/^bot-1 · /, "");
  return v.name;
}

function Tile({ icon, size = 18 }: { icon: TileIcon; size?: number }) {
  if (Array.isArray(icon)) {
    return (
      <span className={styles.tile} data-pair="true">
        <FlowIcon name={icon[0]} size={13} />
        <FlowIcon name={icon[1]} size={13} />
      </span>
    );
  }
  return (
    <span className={styles.tile}>
      <FlowIcon name={icon} size={size} />
    </span>
  );
}

/**
 * 상태 한 줄. 앞의 형태가 색 없이도 상태를 가르고, 형태와 글자가 상태색을 받는다.
 * 미구축은 형태가 없고 낱말만 적는다
 */
function Status({ state, text }: { state: NodeState; text: string }) {
  const shape = STATE_SHAPE[state];
  return (
    <span className={styles.status} data-state={state}>
      {shape && (
        <span className={styles.shape} aria-hidden="true">
          {shape}
        </span>
      )}
      <span className={styles.statusText}>{text}</span>
    </span>
  );
}

/** 묶음 상자의 머리 이름표. 왼쪽 위의 작은 표식과 이름 */
function FrameLabel({ icon, children }: { icon: FlowIconName; children: ReactNode }) {
  return (
    <span className={styles.frameLabel}>
      <FlowIcon name={icon} size={13} />
      {children}
    </span>
  );
}

function boxStyle(b: Box): CSSProperties {
  return { left: b.x, top: b.y, width: b.w, height: b.h };
}

const POP_W = 300;

/**
 * 카드 옆에 연다. 오른쪽에 자리가 없으면 왼쪽이다. 세로는 카드 윗변에 맞추되 팝오버가 구성도 아래 끝을
 * 넘으면 넘는 만큼만 올린다. 크게 보기는 구성도 밖을 잘라 내므로 [0, H] 를 벗어나면 행과 단추가 잘린다.
 * 높이는 연 뒤에 잰 값(popH)이고, 그래도 넘치는 내용은 팝오버 안에서 스크롤한다
 */
function popoverStyle(b: Box, width: number, popH: number): CSSProperties {
  const gap = 8;
  const left = right(b) + gap + POP_W <= width ? right(b) + gap : Math.max(0, b.x - gap - POP_W);
  const top = Math.max(0, Math.min(b.y, H - popH));
  return { left, top, width: POP_W, maxHeight: H };
}

/**
 * 노드 단추의 접근 이름. AWS 이름을 맨 앞에 두고 카드에 보이는 제목은 그 안에 든다(WCAG 2.5.3).
 * 앱 노드가 정상으로 돌 때 상태 줄은 거의 매초 바뀌는 CPU 값이다. 그 값을 이름에 섞으면 포커스를
 * 둔 노드를 화면 낭독기가 매번 다시 읽으므로 싣지 않는다. 나머지 상태 줄은 사건 때만 바뀐다
 */
function nodeLabel(id: NodeId, v: NodeView, s: FlowState): string {
  const words = [v.name, v.container ?? "대응 컨테이너 없음", v.role, STATE_WORD[v.state]];
  // bot-1 칸 이름표는 "bot-1" 로 줄였다. 두 카드가 한 호스트에 함께 선다는 사실은 이름에 싣는다
  if (id === "bot" || id === "obs") words.splice(1, 0, "bot-1 한 호스트");
  // DB 카드의 제목은 지금 역할이다("Standby 재편입"). 이름이나 역할 줄에 그 말이 없으면 따로 싣는다
  const title = cardTitle(id, v, s);
  if (!words.some((w) => w.includes(title))) words.splice(1, 0, title);
  const cpuLine = isApp(id) && s.apps[id].status === "running" && v.state === "running";
  if (!cpuLine && v.status !== STATE_WORD[v.state]) words.push(v.status);
  return words.join(" · ");
}

/* ===== 팝오버 내용 ===== */

interface ActionSpec {
  id: ScenarioId;
  target?: TargetId;
  tone: "neutral" | "danger";
}

interface Detail {
  icon: TileIcon;
  title: string;
  sub: string;
  state: NodeState;
  status: string;
  rows: [string, string][];
  actions: ActionSpec[];
  foot?: ReactNode;
}

/* ===== 본체 ===== */

export default function FlowTopology({ state, views, onPick }: FlowTopologyProps) {
  const uid = useId();
  const popId = `${uid}-pop`;
  const titleId = `${uid}-pop-title`;
  const wrapRef = useRef<HTMLDivElement>(null);
  const popRef = useRef<HTMLDivElement>(null);
  const triggers = useRef(new Map<PopKey, HTMLButtonElement>());
  const refCallbacks = useRef(new Map<PopKey, (el: HTMLButtonElement | null) => void>());
  const [width, setWidth] = useState(MIN_W);
  const [open, setOpen] = useState<PopKey | null>(null);
  /** 열린 팝오버의 높이. 구성도 아래 끝을 넘지 않게 세로 자리를 고르는 데 쓴다 */
  const [popH, setPopH] = useState(0);

  // 패널 안쪽 폭을 재서 배치를 다시 잡는다. 페인트 전에 재야 첫 화면에서 선이 한 번 튀지 않는다
  useIsoLayoutEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const measure = () => setWidth(el.clientWidth || MIN_W);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // 열린 팝오버의 높이를 페인트 전에 잰다. 첫 그림은 카드 윗변에 맞춰 그려지고, 잰 높이가 아래 끝을
  // 넘으면 같은 프레임 안에서 올라간다. 막힌 이유 줄이 생기는 것처럼 내용이 바뀌면 다시 잰다
  useIsoLayoutEffect(() => {
    if (open === null) return;
    const pop = popRef.current;
    if (!pop) return;
    const measure = () => setPopH(pop.offsetHeight);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(pop);
    return () => ro.disconnect();
  }, [open]);

  // 팝오버가 열리면 첫 조작 요소로 포커스를 옮긴다. 조작이 없는 정보 팝오버는 대화상자 자체가 받는다.
  // 닫힘은 세 길이다. Escape 는 포커스를 노드로 돌려주고, 바깥 누름은 그 자리에 둔다. 키보드로
  // 팝오버와 그 노드 밖으로 나가면 나간 자리에 포커스를 둔 채 닫는다. 그 자리로 포커스를 되돌리면
  // 탭으로 빠져나갈 수 없고, 열어 두면 팝오버가 다음 노드를 덮어 포커스가 가려진다(WCAG 2.4.11)
  useEffect(() => {
    if (open === null) return;
    const pop = popRef.current;
    const trigger = triggers.current.get(open);
    const first = pop?.querySelector<HTMLElement>("button:not([disabled]), a[href]");
    (first ?? pop)?.focus();
    const onDown = (e: PointerEvent) => {
      const t = e.target;
      if (!(t instanceof Element)) return;
      // 노드 누름은 노드 자신의 토글이 처리한다. 여기서 먼저 닫으면 같은 노드가 다시 열린다
      if (pop?.contains(t) || t.closest("[data-flow-node]")) return;
      setOpen(null);
    };
    // Escape 는 팝오버 안이나 그 노드에 포커스가 있을 때만 받는다. 문서 전체에서 받으면 포커스가 이미
    // 다른 곳으로 간 뒤에도 Escape 한 번에 노드로 끌려온다. 받은 Escape 는 여기서 멈춘다. 문서까지
    // 올라가면 페이지의 크게 보기도 같은 키로 닫혀, 팝오버 하나를 닫으려다 확대 화면까지 빠져나간다
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopPropagation();
      setOpen(null);
      trigger?.focus();
    };
    // 새 포커스 자리가 비었으면(창 밖으로 나감, 포커스를 받지 않는 곳을 누름) 닫지 않는다. 바깥 누름은
    // pointerdown 이 따로 닫고, 창을 오간 것만으로 닫히면 돌아와서 다시 열어야 한다
    const onLeave = (e: FocusEvent) => {
      const next = e.relatedTarget;
      if (!(next instanceof Node) || pop?.contains(next) || trigger?.contains(next)) return;
      setOpen(null);
    };
    const owned: HTMLElement[] = [];
    if (pop) owned.push(pop);
    if (trigger) owned.push(trigger);
    document.addEventListener("pointerdown", onDown);
    for (const el of owned) {
      el.addEventListener("keydown", onKey);
      el.addEventListener("focusout", onLeave);
    }
    return () => {
      document.removeEventListener("pointerdown", onDown);
      for (const el of owned) {
        el.removeEventListener("keydown", onKey);
        el.removeEventListener("focusout", onLeave);
      }
    };
  }, [open]);

  const L = useMemo(() => computeLayout(width), [width]);
  // 선 점열은 배치와 두 사건 값에만 기댄다. 매초 새 상태가 와도 같은 배열을 넘겨 FlowTraffic 의
  // memo 가 다시 그리기를 건너뛰게 한다
  const endpoint = state.endpoint;
  const replSrc: DbId = primaryDb(state) ?? state.endpoint;
  const wires = useMemo(() => buildWires(L, endpoint, replSrc), [L, endpoint, replSrc]);
  // 선 상태는 매초 다시 구하지만 서명 문자열은 상태나 단계가 바뀔 때만 달라진다
  const flows = useMemo(() => edgeFlows(state), [state]);
  const sig = trafficSignature(flows);
  const asg = asgView(state);
  // 그룹이 조정 기준으로 삼는 조회 사용률. 지표 관제의 "조회 사용률 %" 그래프와 같은 표본(마지막 값)을
  // 정수로 골라 두 화면의 숫자가 어긋나지 않는다. 정수라 값이 바뀔 때만 다시 그린다
  const util = useFlowRun((r) => Math.round(r.samples[r.samples.length - 1].util * 100));
  const advice = Math.round(SCALE_OUT_ADVICE * 100);

  const pick = (req: FlowPick) => {
    // 확인 대화상자는 열릴 때의 포커스를 기억했다가 닫힐 때 돌려준다. 팝오버 버튼은 곧 사라지므로
    // 먼저 노드로 옮겨 둔다. 그래야 대화상자를 닫은 뒤 키보드 위치가 노드에 남는다
    if (open !== null) triggers.current.get(open)?.focus();
    setOpen(null);
    onPick(req);
  };

  const w = writable(state);
  const a3 = state.apps.api3;
  const endpointLine = `${DB_NAME[state.endpoint]} · ${DB_CONTAINER[state.endpoint]}`;

  const detail = (key: PopKey): Detail => {
    if (key === "asg") {
      return {
        icon: "asg",
        title: "Auto Scaling group",
        sub: "capacity_reconciler.py · 호스트",
        state: asg.state,
        status: asg.status,
        rows: [
          ["범위", `min ${asg.min} · max ${asg.max}`],
          ["조회 사용률", `${util}% · 증설 권고 ${advice}%`],
          ["목표", `${asg.desired}대`],
          ["등록", `${asg.registered}대 · ALB 조회 대상`],
          ["+1 노드", "app-3 · 조회 전용 · 리더 경쟁 제외"],
          ["Docker 대역", "capacity_reconciler.py · docker start·stop · ALB down 표식 토글"],
          ["AWS", "AWS 에서는 Auto Scaling group"],
          ["증설 권고", `조회 사용률 ${advice}% 이상 · AWS 운영값 60%`],
          ["승인", "운영자 +1·-1 승인 뒤 조정"],
        ],
        actions: [{ id: "scale", tone: "neutral" }],
      };
    }
    const v = views[key];
    const head = {
      icon: ICON[key],
      title: isDb(key) ? `${DB_TITLE[state.db[key]]} · ${v.name}` : v.name,
      sub: v.container ?? "대응 컨테이너 없음",
      state: v.state,
      status: v.status,
    };
    switch (key) {
      case "users":
        return {
          ...head,
          rows: [
            ["목표 경로", "CloudFront 경유 · ALB"],
            ["Docker 대역", "localhost:58080 · ALB 직결"],
            ["쓰기", w ? "가능" : "불가 · 쓰기 리더 없음"],
          ],
          actions: [{ id: "traffic", tone: "neutral" }],
        };
      case "cdn":
        return {
          ...head,
          rows: [
            ["구성", "목표 구성 · 켜진 노드로 표시"],
            ["역할", v.role],
            ["Docker 대역", "CloudFront 없음 · 요청은 ALB 로 바로"],
            ["AWS", "정적 웹은 S3, API·WebSocket 은 ALB 로"],
          ],
          actions: [],
        };
      case "load":
        return {
          ...head,
          rows: [
            ["위치", "read-load.py · 호스트 프로세스"],
            ["AWS", "load-1 · k6 부하 발생 · 별도 노드"],
            ["대상", "ALB :58080 조회 경로"],
            ["부하", v.status],
          ],
          actions: [{ id: "scale", tone: "neutral" }],
        };
      case "alb":
        return {
          ...head,
          rows: [
            ["컨테이너", "chaos-lb · nginx"],
            ["호스트 포트", ":58080"],
            ["쓰기", "app-1 1차 · app-2 백업"],
            ["조회 분산", `least_conn · 연결이 적은 노드로 · app-1 · app-2 · app-3${a3.inRead ? "" : " (down)"}`],
            ["지금", v.status],
          ],
          actions: [],
        };
      case "api1":
      case "api2":
        return {
          ...head,
          rows: [
            ["컨테이너", `${APP_CONTAINER[key]} · :${APP_PORT[key]}`],
            ["역할", v.role],
            ["상태", v.status],
            [
              "리더 리스",
              state.holder === key
                ? `보유 · 잔여 ${state.lease}초 · TTL ${LEASE_TTL}초 · 갱신 ${LEASE_TICK}초`
                : `app-1 · app-2 경쟁 · TTL ${LEASE_TTL}초`,
            ],
            ["세대", state.holder === null ? `epoch ${state.epoch} · 보유 없음` : `epoch ${state.epoch} · 보유 ${APP_NAME[state.holder]}`],
            [
              "조회",
              key === "api2"
                ? "쓰기 백업이어도 조회는 받음 · ALB least_conn 분산"
                : "ALB least_conn 분산으로 조회도 받음",
            ],
            ["DB 접속", endpointLine],
          ],
          actions: [
            { id: "leaderKill", target: key, tone: "danger" },
            { id: "restart", target: key, tone: "neutral" },
          ],
        };
      case "api3":
        return {
          ...head,
          rows: [
            ["컨테이너", `${APP_CONTAINER.api3} · :${APP_PORT.api3}`],
            ["역할", "조회 전용 · 리더 경쟁 제외"],
            ["상태", v.status],
            ["ALB 조회", a3.inRead ? "등록" : "down 표식"],
            ["기동", "Auto Scaling 그룹의 +1 · 조정기가 기동·중지"],
            ["앱 지표", "미수집 · 컨테이너 CPU 만 수집"],
          ],
          actions: [{ id: "scale", tone: "neutral" }],
        };
      case "pg1":
      case "pg2": {
        const rows: [string, string][] = [
          ["컨테이너", DB_CONTAINER[key]],
          ["역할", v.role],
          ["상태", v.status],
          ["앱 접속", state.endpoint === key ? "접속 대상" : "미접속"],
          ["복제", state.replicating ? "비동기 · 스트리밍 중" : "끊김"],
          ["전환", "자동 페일오버 없음 · 수동 승격"],
        ];
        if (key === "pg1") rows.splice(1, 0, ["호스트 포트", ":55432"]);
        else rows.push(["구성", "2단계에서 compose 에 추가"]);
        return {
          ...head,
          rows,
          actions: [
            { id: "dbSwitch", target: key, tone: "danger" },
            { id: "rejoin", target: key, tone: "neutral" },
          ],
        };
      }
      case "bot":
        return {
          ...head,
          rows: [
            ["컨테이너", "chaos-bots"],
            ["주문", "ALB :58080 경유"],
            ["DB", "봇 계정용 직결"],
            ["발행률", `목표 ${state.botOps} OPS · 기본 ${BASE_OPS}`],
            ["AWS", "bot-1 한 호스트 · 관측과 함께"],
          ],
          actions: [{ id: "traffic", tone: "neutral" }],
        };
      // 수집 원천은 harness/prometheus/prometheus.yml 을 따른다. 앱 지표(/actuator/prometheus)는
      // app-1·app-2·봇만 긁는다. 컨테이너 CPU 는 chaos-cstats 가 도커 API 로 전부 읽어 app-3 도 거기에는 잡힌다
      case "obs":
        return {
          ...head,
          rows: [
            ["구성", "chaos-prometheus · chaos-grafana"],
            ["기동", "선택 · --profile obs"],
            ["앱 지표", "app-1 · app-2 · 봇"],
            ["컨테이너 CPU", "실행 중 전 컨테이너 · chaos-cstats"],
            ["DB", "chaos-pg-exporter · Primary 만"],
            ["프로브", "blackbox · 헬스 경로"],
            ["미수집", "app-3 앱 지표"],
            ["AWS", "bot-1 한 호스트 · 시장 봇과 함께"],
          ],
          actions: [],
          foot: grafanaConfigured() ? <GrafanaLink board="msp-service" label="Grafana 추이" /> : undefined,
        };
    }
  };

  // 콜백 ref 를 자리마다 한 번만 만든다. 매 틱 새 함수를 넘기면 React 가 떼고 다시 붙이기를 되풀이한다
  const bindTrigger = (key: PopKey) => {
    let fn = refCallbacks.current.get(key);
    if (!fn) {
      fn = (el: HTMLButtonElement | null) => {
        if (el) triggers.current.set(key, el);
        else triggers.current.delete(key);
      };
      refCallbacks.current.set(key, fn);
    }
    return fn;
  };

  /** 여닫는 단추가 공통으로 받는 속성 */
  const triggerProps = (key: PopKey) => ({
    type: "button" as const,
    ref: bindTrigger(key),
    "data-flow-node": "",
    "aria-haspopup": "dialog" as const,
    "aria-expanded": open === key,
    "aria-controls": open === key ? popId : undefined,
    onClick: () => setOpen((cur) => (cur === key ? null : key)),
  });

  const popover = (key: PopKey, anchor: Box) => {
    if (open !== key) return null;
    const d = detail(key);
    return (
      <div
        ref={popRef}
        id={popId}
        className={styles.popover}
        style={popoverStyle(anchor, L.width, popH)}
        role="dialog"
        aria-labelledby={titleId}
        tabIndex={-1}
      >
        <div className={styles.popHead}>
          <Tile icon={d.icon} size={18} />
          <div className={styles.popHeadText}>
            <p id={titleId} className={styles.popTitle}>
              {d.title}
            </p>
            <p className={styles.popSub}>{d.sub}</p>
            <Status state={d.state} text={d.status} />
          </div>
        </div>
        <dl className={styles.popRows}>
          {d.rows.map(([k, val]) => (
            <Fragment key={k}>
              <dt>{k}</dt>
              <dd>{val}</dd>
            </Fragment>
          ))}
        </dl>
        {d.actions.length > 0 && (
          <div className={styles.popActions}>
            {d.actions.map((a) => {
              const av = availability(state, a.id, a.target);
              const actionKey = `${a.id}-${a.target ?? "any"}`;
              const reasonId = `${uid}-reason-${actionKey}`;
              return (
                <div key={actionKey} className={styles.action}>
                  <button
                    type="button"
                    className={a.tone === "danger" ? adminStyles.dangerBtn : `${adminStyles.neutralBtn} ${styles.popBtn}`}
                    disabled={!av.ok}
                    aria-describedby={!av.ok && av.reason ? reasonId : undefined}
                    onClick={() => pick(a.target === undefined ? { id: a.id } : { id: a.id, target: a.target })}
                  >
                    {describeScenario(state, a.id, a.target).title}
                  </button>
                  {!av.ok && av.reason && (
                    <span id={reasonId} className={styles.reason}>
                      {av.reason}
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        )}
        {d.foot != null && <div className={styles.popFoot}>{d.foot}</div>}
      </div>
    );
  };

  /**
   * 노드 카드. 아이콘 타일, 제목, 상태 한 줄만 싣는다.
   *
   * 테두리가 상태를 싣는다. 실행은 초록, 전환 중은 주황과 옅은 면, 장애는 굵은 빨강과 옅은 면,
   * 중지는 회색 테두리에 가라앉은 면이다. app-3 은 그룹의 +1 자리라 꺼져 있으면 점선으로 비워 둔다.
   * 매칭 리더는 테두리 안쪽의 크롬 악센트 레일 하나로만 가른다
   */
  const card = (key: NodeId, out = false) => {
    const v = views[key];
    const leader = isApp(key) && state.holder === key;
    const box = L.nodes[key];
    return (
      <Fragment key={key}>
        <button
          {...triggerProps(key)}
          aria-label={nodeLabel(key, v, state)}
          className={styles.node}
          style={boxStyle(box)}
          data-state={v.state}
          data-leader={leader ? "true" : undefined}
          data-slot={key === "api3" ? "true" : undefined}
          data-out={out ? "true" : undefined}
        >
          <Tile icon={ICON[key]} />
          <span className={styles.titleRow}>
            <span className={styles.title}>{cardTitle(key, v, state)}</span>
            {key === "api3" && <span className={styles.plus}>+1</span>}
          </span>
          <Status state={v.state} text={v.status} />
        </button>
        {popover(key, box)}
      </Fragment>
    );
  };

  const asgLabel = `Auto Scaling group · min ${asg.min} · max ${asg.max} · capacity_reconciler.py · ${asg.status}`;

  return (
    <div className={styles.root}>
      <div
        ref={wrapRef}
        className={styles.topology}
        style={{ height: H, minWidth: MIN_W }}
        role="group"
        aria-label="서비스 흐름 구성도 · AWS 대역 · Docker exchange-chaos"
      >
        {/* 틀과 묶음 상자는 묶음을 보이는 그림이다. 뜻은 카드 이름과 연결 목록이 싣는다 */}
        <div className={styles.aws} style={boxStyle(L.aws)} aria-hidden="true">
          <span className={styles.awsTitle}>
            <FlowIcon name="cloud" size={16} />
            AWS Cloud
          </span>
          {/* 범례는 AWS 틀 머리 오른쪽에 한 줄로 둔다. 모양의 뜻은 카드의 접근 이름과 연결 목록이 이미 글로 싣는다 */}
          <span className={styles.legend}>
            <span className={styles.legendGroup}>
              <span className={styles.legendTitle}>노드</span>
              {LEGEND_STATES.map((st) => (
                <span key={st} className={styles.legendItem}>
                  <span className={styles.shape} data-state={st}>
                    {STATE_SHAPE[st]}
                  </span>
                  {STATE_WORD[st]}
                </span>
              ))}
              <span className={styles.legendItem}>
                <span className={styles.railSwatch} />
                리더
              </span>
            </span>
            <span className={styles.legendGroup}>
              <span className={styles.legendTitle}>선</span>
              {LINE_LEGEND.map((l) => (
                <span key={l.label} className={styles.legendItem}>
                  {l.ramp ? <TierSwatch /> : <LineSwatch kind={l.kind} tier={l.tier} minor={l.minor} />}
                  {l.mark && (
                    <span className={styles.shape} data-state={l.mark}>
                      {STATE_SHAPE[l.mark]}
                    </span>
                  )}
                  {l.label}
                </span>
              ))}
            </span>
          </span>
        </div>
        <div className={styles.frame} data-kind="edge" style={boxStyle(L.edge)} aria-hidden="true">
          <FrameLabel icon="globe">엣지</FrameLabel>
        </div>
        <div className={styles.frame} data-kind="region" style={boxStyle(L.region)} aria-hidden="true">
          <FrameLabel icon="pin">서울 리전 · ap-northeast-2</FrameLabel>
        </div>
        <div className={styles.frame} data-kind="vpc" style={boxStyle(L.vpc)} aria-hidden="true">
          <FrameLabel icon="vpc">VPC</FrameLabel>
        </div>
        <div className={styles.frame} data-kind="subnet" style={boxStyle(L.pub)} aria-hidden="true">
          <FrameLabel icon="unlock">퍼블릭</FrameLabel>
        </div>
        <div className={styles.frame} data-kind="subnet" style={boxStyle(L.db)} aria-hidden="true">
          <FrameLabel icon="db">DB · PostgreSQL</FrameLabel>
        </div>
        {/* bot-1 칸의 이름표도 다른 틀처럼 왼쪽 위다. 시장 봇 선이 ALB 가운데 세로줄로 칸 윗변을 지나는
            자리(956px 에서 칸 왼쪽 끝에서 67px)보다 앞에서 끝나게 "bot-1"(끝이 약 53px)만 적는다 */}
        <div className={styles.frame} data-kind="host" style={boxStyle(L.host)} aria-hidden="true">
          <FrameLabel icon="server">bot-1</FrameLabel>
        </div>
        {/* Auto Scaling 그룹. 앱 계층을 강조하는 틀이라 테두리를 한 단 굵고 진하게 두고, 조정 중이면
            주황 점선으로 바꾼다. 머리는 단추라 눌러 그룹의 범위와 조정 방법을 연다. 머리는 app-1 위,
            그룹 안에 이름, min·max, 지금 대수 세 줄(44px)로 선다 */}
        <div className={styles.asgFrame} data-state={asg.state} style={boxStyle(L.asg)} aria-hidden="true" />

        {/* 선과 트래픽 층. 선 그림은 카드보다 먼저 두어 화살촉이 카드 테두리 밑에 닿고, 알갱이 층은
            z-index 로 카드 위에 올린다(traffic.module.css). 받는 값이 모두 사건 때만 바뀌어 매초의
            다시 그리기가 이 층을 건너뛴다 */}
        <FlowTraffic width={L.width} height={H} wires={wires} sig={sig} />

        {card("users", true)}
        {card("cdn")}
        {card("load")}
        {card("alb")}
        {/* 그룹 머리 단추는 ALB 카드 뒤에 둔다. 탭 순서가 그림을 읽는 순서(사용자, CloudFront, load-1,
            ALB, 그룹, app-1~3, DB, bot-1)를 따른다 */}
        <button
          {...triggerProps("asg")}
          aria-label={asgLabel}
          className={styles.asgHead}
          style={boxStyle(L.asgHead)}
        >
          <span className={styles.asgTitle}>
            <FlowIcon name="asg" size={16} />
            Auto Scaling
          </span>
          {/* 둘째 줄은 범위와 조정 기준이다. 조정 중(▲)일 때 왜 늘리는지가 머리에서 바로 읽힌다.
              사용률은 매초 바뀌므로 접근 이름에는 싣지 않고 팝오버에 권고 임계와 함께 적는다 */}
          <span className={styles.asgMeta}>
            {L.asgWide
              ? `min ${asg.min} · max ${asg.max} · 사용률 ${util}%`
              : `${asg.min}~${asg.max}대 · 사용률 ${util}%`}
          </span>
          <Status state={asg.state} text={asg.status} />
        </button>
        {popover("asg", L.asgHead)}
        {card("api1")}
        {card("api2")}
        {card("api3")}
        {card("pg1")}
        {card("pg2")}
        {card("bot")}
        {card("obs")}

        {/* 선은 그림이라 보조기술에 닿지 않는다. 같은 내용을 목록으로 한 번 더 싣는다. 줄마다 상태와
            세기만 적어 매초 바뀌지 않는다 */}
        <ul className="sr-only" aria-label="연결 상태">
          {EDGE_IDS.map((id) => (
            <li key={id}>{edgeText(id, state, views, flows[id])}</li>
          ))}
          <li>{`DB 승격 · 자동 페일오버 없음 · 수동 승격`}</li>
        </ul>
      </div>
    </div>
  );
}
