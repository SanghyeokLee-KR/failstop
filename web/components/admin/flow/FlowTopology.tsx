"use client";

/**
 * 서비스 흐름 구성도. AWS 목표 구성(v5)을 AWS 아키텍처 그림의 문법으로 그린다. 지금은 로컬 Docker
 * exchange-chaos 스택이 그 역할을 맡는다(2026-09-23 사용자 결정). 카드에는 아이콘, AWS 역할 이름,
 * 상태 한 줄만 둔다. 이름은 관제센터와 같다(app-1·app-2, read-1~3, db-primary·db-standby, bot-1, load-1,
 * ai-1). 그 역할을 대신하는 컨테이너 이름, 포트, 리스 같은 세부는 카드를 눌러 여는 팝오버에만 싣는다.
 * 재생하지 않는 동안의 값은 관제 판(opsCenter)에서 옮긴 관제 값이고, 재생 중에는 flowSim 의 재생 값이다
 * (flowRun 의 두 모드). ai-1 카드와 판단 요청선은 어느 모드에서나 관제 판을 읽는다.
 *
 * 그림의 뼈대는 사용자가 준 거래소 인프라 그림을 따르고, 칸은 정해 둔 격자에 맞춘다.
 *  - 구성도 세로 가운데 아래를 지나는 가로줄(주축) 하나에 사용자, CloudFront, ALB, 조회 Auto Scaling 그룹의
 *    가운데 줄(read-2), Primary, Standby 가 선다. 사용자 경로, 조회선, 조회 SQL 선, 복제선이 모두 곧은 가로선이다.
 *  - 열은 왼쪽부터 다섯이다. 0열은 AWS 틀 밖이다. 위에서부터 온프레미스(연구소) 틀의 ai-1, 주축의 사용자,
 *    bot-1 칸 높이의 Teams 자리가 선다. 1열 엣지 칸은 AWS 틀 안이지만 리전 밖에서 CloudFront 한 장만 감싼다.
 *    서울 리전 틀 안의 VPC 틀에 2열(load-1 과 퍼블릭 칸의 ALB), 3열(매칭 칸과 조회 Auto Scaling 그룹),
 *    4열(DB 칸의 Primary 와 Standby)이 서고, 맨 아래 bot-1 칸이 2~4열을 받친다.
 *  - 3열 위의 매칭 칸이 app-1·app-2 를 싣는다. 쓰기는 이 두 대 가운데 리더가 받는다. 아래의 Auto Scaling
 *    그룹은 조회 노드(read-1~3)만 감싼다. 매칭 노드는 그룹 밖이다(ADR 0027, 0030). 그룹 머리가 범위와 지금
 *    대수와 사용률을 적고, 조정 중이면 테두리가 주황 점선과 ▲ 로 바뀐다. 조회 노드는 이름과 CPU 한 줄만 싣는
 *    낮은 카드라 세 대가 한 묶음으로 읽힌다.
 *  - ALB 는 조회를 주축으로 그룹에 곧게 보내고, 쓰기는 오른변 위쪽 두 출구에서 퍼블릭 칸과 3열 사이 틈의
 *    세로 줄 둘(bus1, bus2)을 타고 올라 app-1·app-2 로 간다. 가까운 줄이 더 높은 app-1 로 가서 두 선이
 *    엇갈리지 않는다. load-1 은 퍼블릭 칸 바로 위, 시장 봇은 ALB 바로 아래라 두 선이 ALB 가운데 세로줄
 *    하나로 위아래에서 들어온다.
 *  - Primary 로는 SQL 선 둘이 들어간다. 쓰기 SQL 은 매칭 칸 오른변(app-1 과 app-2 사이)에서 나와 3열과 DB 칸
 *    사이 틈으로 내려가 주축 조금 위로, 조회 SQL 은 그룹에서 주축으로 곧게 간다. DB 전환 뒤에는 오른쪽
 *    카드가 Primary 가 된다. 쓰기 SQL 은 DB 칸 위를 지나 새 Primary 윗변 오른쪽으로, 조회 SQL 은 틈에서
 *    DB 칸 바로 위로 올라 윗변 왼쪽으로 내려간다. 두 선은 서로 엇갈리지 않는다.
 *  - ai-1 은 AWS 밖 온프레미스 틀에 선다(ADR 0038). 매칭 칸 이름표 띠 높이의 곧은 점선이 ai-1 과 매칭 칸을
 *    잇고, 선 밑 이름표가 '판단 요청 · 연결 경로 결정 전'이라고 적는다. 연결 경로를 정하지 않아 흐름 선에
 *    넣지 않는다. 관제 판에 판단 요청이 걸리면 요청과 응답 알갱이가 한 번씩 지난다(AiLink).
 *  - bot-1 칸의 관측 오른쪽(Standby 열 아래)에 'AI 운영 보고', 0열 아래 AWS 틀 밖에 'Teams' 자리를 둔다
 *    (관리자 설계 7-10 (i) 6). 두 카드는 설정 화면의 Teams 행과 같은 판정(보고 서비스의 전송 기록)을 적고
 *    누를 수 없다. 관측→AI 운영 보고→Teams 선은 흐름 선(EdgeId)에 들지 않는 보고 경로 선이다.
 *  - CloudFront 는 이 대역에 없다. 사용자가 목표 구성대로 켜진 노드로 그리기로 했고 상태 글자와
 *    팝오버가 그 사실을 밝힌다. chaos-toxiproxy 는 AWS 에 없는 Docker 전용 장애 주입기라 그리지 않는다.
 *  - 학교·개인 계정 모두 NAT 없는 퍼블릭 서브넷 구성이라(ADR 0099) DB 와 bot-1 칸에 프라이빗
 *    서브넷이라는 이름을 붙이지 않는다. 역할 묶음으로만 적는다. 모든 틀 이름표는 AWS 그림처럼 왼쪽 위에 선다.
 *  - 틀은 카드를 가로 p(956px 에서 11, 1180px 에서 16, 1700px 에서 20), 아래 16px 로 감싸고, 틀 안의
 *    틀도 같은 p 와 16px 로 떨어진다. 여러 겹의 테두리가 한 간격으로 층을 이뤄 등고선처럼 몰리지 않는다.
 *  - 주축의 카드 사이 거리는 틀이 몇 겹 끼든 같다. 사용자→CloudFront, →ALB, →조회 그룹, →Primary 가 모두
 *    같은 d 이고, 한 칸 안의 Primary→Standby 만 그보다 좁은 열 틈이다.
 *  - 선에는 글자를 달지 않는다. 예외는 끊긴 선의 ■ 끊김 표식과 판단 요청선의 이름표다. 선 상태와 트래픽은
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
  isEngine,
  isLeader,
  isRead,
  partitionsOf,
  primaryDb,
  writable,
  type DbId,
  type DbStatus,
  type EdgeFlow,
  type EdgeId,
  type EdgeKind,
  type EdgeTier,
  type EngineId,
  type FlowState,
  type NodeId,
  type NodeState,
  type NodeView,
  type ReadId,
  type ScenarioId,
  type TargetId,
} from "@/lib/admin/flowSim";
import { replayBase, useFlowRun } from "@/lib/admin/flowRun";
import type { IntegrationState } from "@/lib/admin/integrations";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import { hm } from "@/lib/admin/timeText";
import type { AiServer, OpsSection } from "@/lib/admin/types";
import { MeterRail } from "@/components/admin/instruments/MeterRail";
import AiLink from "./AiLink";
import { FlowIcon, type FlowIconName } from "./FlowIcon";
import FlowTraffic, {
  ARROW_LEN,
  LineSwatch,
  ReserveWires,
  TierSwatch,
  trafficSignature,
  type Pt,
  type Wire,
} from "./FlowTraffic";
import { useChangeFlags } from "./useChangeFlags";
import styles from "./topology.module.css";

/** 노드를 눌러 고른 재생. 대상은 노드를 지목하는 재생(리더 중단, 재기동, DB 전환, 재편입)에만 붙는다 */
export type FlowPick = { id: ScenarioId; target?: TargetId };

/** 보고 경로 자리(AI 운영 보고, Teams)의 판정. 설정 화면의 Teams 행과 같은 함수(teamsDelivery)에서 온다 */
export interface FlowDelivery {
  state: IntegrationState;
  /** 마지막 보고를 만든 시각 */
  lastReportAt: string | null;
  /** 마지막 전송 시도 시각 */
  lastSentAt: string | null;
}

export interface FlowTopologyProps {
  state: FlowState;
  views: Record<NodeId, NodeView>;
  onPick: (req: FlowPick) => void;
  delivery: FlowDelivery;
}

/** 팝오버를 여는 자리. 노드 카드, Auto Scaling 그룹 머리, 온프레미스 ai-1 이다 */
type PopKey = NodeId | "asg" | "ai";

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
   본문이다. 본문 맨 위에 3열의 매칭 칸이 서고 그 아래 조회 Auto Scaling 그룹이 선다. 그룹 가운데 줄
   (read-2)의 가운데가 주축이다. 틀은 카드를 16px 로 감싸고 틀 안의 틀도 16px 떨어진다 */
const HEAD = 34;
/** 틀 머리 이름표 띠. 이름표(15px)와 위 5px, 아래 4px 여백이다 */
const BAND = 24;
/** 틀 안의 틀 사이 세로 여백. 바깥 틀 아래 가장자리가 안쪽 틀에서 떨어진 거리다 */
const NEST = 16;
/** 틀 아래 가장자리가 카드에서 떨어진 거리 */
const INSET = 16;
/** 카드 높이. 타일 28, 제목 16, 상태 18(핵심 수치 13px 줄)과 그 사이 2px 두 번이 66px 이고, 1.5px 테두리
    두 겹을 더한 69px 에 3px 여유를 둔다 */
const CARD_H = 72;
/** 매칭 칸 안 카드 사이 */
const STACK_GAP = 12;
/** 조회 노드 줄. 이름과 CPU 한 줄만 싣는 낮은 카드다. 줄 32px 와 틈 12px 라 투명 ::after 로 위아래 틈 6px 씩을\n    더하면 누르는 자리가 44px(§10-2)이고 이웃 줄과 겹치지 않는다 */
const READ_H = 32;
const READ_GAP = 12;
/** Auto Scaling 그룹 머리 단추. 이름과 범위(16px), 지금 대수와 사용률(18px), 사용률 막대와 권고 임계(14px)
    세 줄이다. 틀 테두리(1.5px)에서 3px, read-1 에서 6px 띄운다. 6px 이면 포커스 고리(선 2px, 띄움 2px)가
    read-1 테두리에 닿지 않는다 */
const ASG_HEAD_H = 48;
const ASG_HEAD_INSET = 3;
// 머리 단추의 좌우 여백과 셋째 줄(사용률 막대)의 위치다. topology.module.css 의 .asgHead 여백 6px,
// 첫 두 줄(16px, 18px) 아래, .asgRailSlot 높이 14px 와 맞춘다. 세 줄이 48px 을 꽉 채워 줄 위치가 고정이다
const ASG_HEAD_PAD = 6;
const ASG_RAIL_TOP = 16 + 18;
const ASG_RAIL_H = 14;
const ASG_HEAD_GAP = 6;
/** 그룹 마지막 줄 아래 여백. 낮은 줄을 감싸는 틀이라 카드 칸의 16px 보다 좁힌다 */
const ASG_PAD_BOTTOM = 12;
/** 조회 그룹 밑변(또는 주축 칸 밑변)과 bot-1 칸 윗변 사이, 매칭 칸과 그룹 사이 */
const HOST_GAP = 16;

const R_TOP = HEAD;
const V_TOP = R_TOP + BAND;
/** VPC 본문 맨 위. 매칭 칸 윗변이 여기 맞춰 선다 */
const C_TOP = V_TOP + BAND;
const MATCH_TOP = C_TOP;
const APP_Y: Record<EngineId, number> = {
  api1: MATCH_TOP + BAND,
  api2: MATCH_TOP + BAND + CARD_H + STACK_GAP,
};
const MATCH_BOTTOM = APP_Y.api2 + CARD_H + INSET;
const ASG_TOP = MATCH_BOTTOM + HOST_GAP;
const READ_TOP = ASG_TOP + ASG_HEAD_INSET + ASG_HEAD_H + ASG_HEAD_GAP;
const READ_Y: Record<ReadId, number> = {
  read1: READ_TOP,
  read2: READ_TOP + READ_H + READ_GAP,
  read3: READ_TOP + (READ_H + READ_GAP) * 2,
};
const ASG_BOTTOM = READ_Y.read3 + READ_H + ASG_PAD_BOTTOM;
const AXIS = READ_Y.read2 + READ_H / 2;
/** 주축 카드의 윗변. 사용자, CloudFront, ALB, DB 두 대가 이 높이에 선다 */
const ROW_Y = AXIS - CARD_H / 2;
/** 주축 카드 하나를 감싸는 칸(엣지, 퍼블릭, DB)의 위아래 */
const MID_TOP = ROW_Y - BAND;
const MID_BOTTOM = ROW_Y + CARD_H + INSET;
/** bot-1 칸. 그룹과 주축 칸 가운데 더 낮은 밑변에서 HOST_GAP 아래에서 시작해 2~4열을 받친다 */
const HOST_TOP = Math.max(ASG_BOTTOM, MID_BOTTOM) + HOST_GAP;
const HOST_CARD_Y = HOST_TOP + BAND;
const HOST_BOTTOM = HOST_CARD_Y + CARD_H + INSET;
const VPC_BOTTOM = HOST_BOTTOM + NEST;
const REGION_BOTTOM = VPC_BOTTOM + NEST;
const H = REGION_BOTTOM + NEST;
/** load-1 은 퍼블릭 칸 바로 위다. 더 위에 두면 AI 판단 요청선이 지나는 높이와 겹친다 */
const LOAD_Y = MID_TOP - HOST_GAP - CARD_H;
/** AI 판단 요청선의 높이. 매칭 칸 이름표 띠의 가운데이고 ai-1 카드의 가운데가 여기 선다 */
const AI_Y = MATCH_TOP + BAND / 2;
const AI_CARD_Y = AI_Y - CARD_H / 2;
/** 온프레미스 틀. ai-1 카드를 이름표 띠와 INSET 으로 감싼다 */
const ONPREM_TOP = AI_CARD_Y - BAND;
const ONPREM_BOTTOM = AI_CARD_Y + CARD_H + INSET;
/** DB 전환 뒤 조회 SQL 선이 DB 칸 위로 지나는 높이. 칸 윗변보다 18px 위라 끊김 표식이 칸 테두리에 닿지 않는다 */
const SQL_HIGH = MID_TOP - 18;
/** 매칭 칸에서 쓰기 SQL 이 나가는 높이. app-1 과 app-2 사이 틈의 가운데라 어느 한 대를 가리키지 않는다 */
const WRITE_SQL_Y = APP_Y.api1 + CARD_H + STACK_GAP / 2;
/** ALB 오른변의 쓰기 출구와 Primary 왼변의 쓰기 SQL 입구가 주축에서 떨어진 간격. app-1 출구는 두 배다 */
const FAN = 13;
/** DB 전환 뒤 두 SQL 선이 새 Primary 윗변으로 내려가는 자리. 카드 가운데에서 좌우로 이만큼 떨어진다 */
const SQL_SPLIT = 10;

/*
 * 가로. 관리 평면 최소 뷰포트 1280px 에서 패널 안쪽 폭이 956px 이다. 그보다 좁으면 MIN_W 로 두고
 * 가로로 넘친다. 주축에 카드 여섯 장(사용자, CloudFront, ALB, 조회 그룹 줄, Primary, Standby)이 서고,
 * 틀 여백은 모두 같은 p 라 층마다 같은 간격으로 겹친다.
 *  - 카드 폭 112px 는 가장 긴 상태 줄 "● 10 OPS · 주문 실패"(수치 13px 굵게, 형태와 틈을 합쳐 실측 약
 *    105px)에 좌우 여백 1px 와 테두리를 더한 값이다. 956px 에서 여백 p 는 11px 이다.
 *  - 0열(사용자, ai-1, Teams)은 온프레미스 틀이 ai-1 을 p 로 감싸도록 p 만큼 들여 세운다.
 *  - 남는 폭은 틀과 틀 사이가 아니라 카드와 카드 사이가 같아지게 나눈다. 주축 네 틈은 같은 d,
 *    Primary→Standby 는 한 칸 안이라 d 의 DB_RATIO 배다. 엣지와 리전 틀 사이는 p 보다 좁히지 않는다.
 *  - 퍼블릭과 3열, 3열과 DB 칸 사이(d - 2p)와 Primary→Standby 틈에 끊김 표식이 앉는다. 틈이 CUT_ROOM
 *    보다 좁으면 그 틈의 표식을 ■ 하나로 줄인다.
 *  - 넓어지면(크게 보기 설계 폭 1180~1700px) 카드, 여백, 틈이 함께 자라 모양이 그대로 커진다.
 */
const MIN_W = 940;
const W_BASE = 956;
const W_DESIGN = 1180;
const W_WIDE = 1700;
/** "■ 끊김" 표식(테두리까지 약 40px)이 양쪽 틀·카드에서 5px 이상 떨어져 앉는 가장 좁은 틈 */
const CUT_ROOM = 50;
/** Primary→Standby 틈을 주축 카드 사이 거리의 몇 배로 둘지 */
const DB_RATIO = 0.66;
/** Auto Scaling 머리의 둘째 줄 오른쪽에 "사용률 58%"를 다 쓰는 가장 좁은 글자 폭. 좁으면 "58%"만 쓴다 */
const ASG_META_ROOM = 132;
/** 링 펄스를 켜 두는 시간. --dur-pulse 900ms 두 번이다. tokens.css 와 같이 바꾼다 */
const PULSE_HOLD_MS = 1800;
/** 대수 플래시를 켜 두는 시간. useHealthTransition 과 같은 길이다 */
const FLASH_HOLD_MS = 600;

// 서버 렌더에는 레이아웃이 없어 useLayoutEffect 가 할 일이 없다. 같은 자리를 useEffect 로 받는다
const useIsoLayoutEffect = typeof window === "undefined" ? useEffect : useLayoutEffect;

interface Layout {
  width: number;
  aws: Box;
  edge: Box;
  region: Box;
  vpc: Box;
  pub: Box;
  /** 매칭 칸(app-1·app-2) */
  match: Box;
  asg: Box;
  /** Auto Scaling 그룹 머리 단추 */
  asgHead: Box;
  db: Box;
  host: Box;
  /** 온프레미스(연구소) 틀과 ai-1 카드. ai-1 은 흐름 노드가 아니라 NodeId 에 넣지 않는다 */
  onprem: Box;
  ai: Box;
  nodes: Record<NodeId, Box>;
  /** 연결 전 자리. AI 운영 보고는 bot-1 칸의 관측 오른쪽, Teams 는 0열 아래 AWS 틀 밖이다 */
  reserve: { report: Box; teams: Box };
  /** ALB 에서 app-1·app-2 로 오르는 두 세로 줄. 퍼블릭 칸과 3열 사이 틈을 셋으로 나눈 자리다 */
  bus1: number;
  bus2: number;
  /** load-1 과 시장 봇 선이 ALB 로 들어가는 세로줄. ALB 가운데다 */
  spine: number;
  /** 퍼블릭·3열·DB 칸 사이 틈이 좁아 그 틈의 끊김 표식을 ■ 하나로 줄인다 */
  tight: boolean;
  /** Primary→Standby 틈이 좁아 복제선의 끊김 표식을 ■ 하나로 줄인다 */
  tightDb: boolean;
  /** Auto Scaling 머리 둘째 줄에 "사용률"을 적을 자리가 있다 */
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
  // 주축 카드 사이 다섯 틈의 합. 카드 여섯 장, 0열 들임(p), Standby 뒤의 틀 네 겹(DB 칸, VPC, 리전, AWS)을
  // 뺀 폭이다. 틈 안의 틀 여백은 사용자→CloudFront 두 번, CloudFront→ALB 네 번, ALB→그룹과 그룹→Primary 두 번씩이다
  const sum = Math.max(0, W - 6 * c - 5 * p);
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
  /** 퍼블릭 칸과 3열, 3열과 DB 칸 사이 */
  const gap = Math.max(0, d - 2 * p);

  const r = Math.round;
  const colX = p;
  let x = colX + c;
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
  const row = (y: number): Box => ({ x: appX, y, w: c, h: READ_H });
  const nodes: Record<NodeId, Box> = {
    users: card(colX, ROW_Y),
    cdn: card(cdnX, ROW_Y),
    load: card(albX, LOAD_Y),
    alb: card(albX, ROW_Y),
    api1: card(appX, APP_Y.api1),
    api2: card(appX, APP_Y.api2),
    read1: row(READ_Y.read1),
    read2: row(READ_Y.read2),
    read3: row(READ_Y.read3),
    pg1: card(pg1X, ROW_Y),
    pg2: card(pg2X, ROW_Y),
    // bot-1 칸의 카드는 위 열에 맞춘다. 시장 봇은 ALB 바로 밑, 관측은 Primary 열 밑이다. Standby 열 밑은
    // AI 운영 보고 자리가 쓴다
    bot: card(albX, HOST_CARD_Y),
    obs: card(pg1X, HOST_CARD_Y),
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
    match: span(asgX, asgR, MATCH_TOP, MATCH_BOTTOM),
    asg: span(asgX, asgR, ASG_TOP, ASG_BOTTOM),
    asgHead: {
      x: asgX + ASG_HEAD_INSET,
      y: ASG_TOP + ASG_HEAD_INSET,
      w: headW,
      h: ASG_HEAD_H,
    },
    db: span(dbX, dbR, MID_TOP, MID_BOTTOM),
    host: span(pubX, dbR, HOST_TOP, HOST_BOTTOM),
    onprem: span(0, colX + c + p, ONPREM_TOP, ONPREM_BOTTOM),
    ai: card(colX, AI_CARD_Y),
    nodes,
    reserve: { report: card(pg2X, HOST_CARD_Y), teams: card(colX, HOST_CARD_Y) },
    bus1: r(pubR + gap / 3),
    bus2: r(pubR + (gap * 2) / 3),
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
   모든 선은 가로세로로만 꺾이고 카드와 틀 이름표를 가로지르지 않는다. 끊김 표식은 열 사이 틈이나
   빈 자리에 앉아 카드와 틀 테두리를 덮지 않는다. 틈이 좁으면 표식을 ■ 하나로 줄인다(tight) */

const otherDb = (id: DbId): DbId => (id === "pg1" ? "pg2" : "pg1");

function buildWires(L: Layout, endpoint: DbId, replSrc: DbId): Wire[] {
  const n = L.nodes;
  const tight = L.tight;
  // ALB 오른변의 세 출구. 주축은 조회선이 그룹으로 곧게 가고, 그 위 두 출구는 쓰기선이다. 더 높은 출구가
  // 가까운 세로 줄(bus1)을 타고 더 높은 app-1 로 간다. 그래서 두 쓰기선은 서로 엇갈리지 않는다
  const upA = AXIS - FAN * 2;
  const upB = AXIS - FAN;
  // 3열과 DB 칸 사이 틈의 가운데. 쓰기 SQL 이 여기서 내려오고, DB 전환 뒤에는 조회 SQL 이 여기서 오른다
  const gapMid = Math.round((right(L.asg) + L.db.x) / 2);

  // 쓰기 SQL 은 매칭 칸에서, 조회 SQL 은 그룹에서 앱이 보는 DB 로 간다. db-primary 를 보면 둘 다 Primary
  // 왼변으로 들어간다. DB 전환으로 오른쪽 카드를 보면 쓰기 SQL 은 매칭 칸 높이 그대로 DB 칸 위를 지나 새
  // Primary 윗변 오른쪽으로, 조회 SQL 은 틈에서 DB 칸 바로 위로 올라 윗변 왼쪽으로 내려간다. DB 칸 아래는
  // 관측으로 가는 수집선이 세로로 지나므로 그쪽으로 돌리면 선이 엇갈린다
  const write: Pick<Wire, "pts" | "cut" | "tight"> =
    endpoint === "pg1"
      ? {
          pts: [
            [right(L.match), WRITE_SQL_Y],
            [gapMid, WRITE_SQL_Y],
            [gapMid, AXIS - FAN],
            [n.pg1.x, AXIS - FAN],
          ],
          cut: [gapMid, Math.round((WRITE_SQL_Y + AXIS - FAN) / 2)],
          tight,
        }
      : {
          pts: [
            [right(L.match), WRITE_SQL_Y],
            [midX(n.pg2) + SQL_SPLIT, WRITE_SQL_Y],
            [midX(n.pg2) + SQL_SPLIT, n.pg2.y],
          ],
          // DB 칸 위의 가로 토막은 넓어 표식 전체가 앉는다
          cut: [Math.round((right(L.match) + midX(n.pg2)) / 2), WRITE_SQL_Y],
        };
  const read: Pick<Wire, "pts" | "cut" | "tight"> =
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
            [midX(n.pg2) - SQL_SPLIT, SQL_HIGH],
            [midX(n.pg2) - SQL_SPLIT, n.pg2.y],
          ],
          cut: [Math.round((gapMid + midX(n.pg2)) / 2), SQL_HIGH],
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
        [right(n.alb), upA],
        [L.bus1, upA],
        [L.bus1, midY(n.api1)],
        [n.api1.x, midY(n.api1)],
      ],
      cut: [L.bus1, Math.round((upA + midY(n.api1)) / 2)],
      tight,
    },
    {
      id: "alb-api2",
      pts: [
        [right(n.alb), upB],
        [L.bus2, upB],
        [L.bus2, midY(n.api2)],
        [n.api2.x, midY(n.api2)],
      ],
      cut: [L.bus2, Math.round((upB + midY(n.api2)) / 2)],
      tight,
    },
    // 조회선은 노드 하나가 아니라 그룹으로 간다. 화살촉이 그룹 틀 왼변에 닿는다
    {
      id: "alb-read",
      pts: [
        [right(n.alb), AXIS],
        [L.asg.x, AXIS],
      ],
      cut: [Math.round((right(L.pub) + L.asg.x) / 2), AXIS],
      tight,
    },
    { id: "app-db", ...write },
    { id: "read-db", ...read },
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
        [midX(n.obs), bottom(L.db)],
        [midX(n.obs), n.obs.y],
      ],
      cut: [midX(n.obs), (bottom(L.db) + L.host.y) / 2],
      minor: true,
    },
  ];
}

/**
 * 연결 전 자리로 가는 선. 관측에서 AI 운영 보고로 곧게, AI 운영 보고에서 리전 틀 아래 띠를 따라 AWS 틀
 * 밖의 Teams 밑변으로 간다. 흐름 선이 아니라 상태와 알갱이가 없고 늘 꺼짐 모양이다
 */
function buildReserveWires(L: Layout): Pt[][] {
  const obs = L.nodes.obs;
  const { report, teams } = L.reserve;
  const band = REGION_BOTTOM + NEST / 2;
  return [
    [
      [right(obs), midY(obs)],
      [report.x, midY(report)],
    ],
    [
      [midX(report), bottom(report)],
      [midX(report), band],
      [midX(teams), band],
      [midX(teams), bottom(teams)],
    ],
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
    "alb-api1": [name("alb"), name("api1"), "쓰기 1차"],
    "alb-api2": [name("alb"), name("api2"), "쓰기 백업"],
    "alb-read": [name("alb"), "조회 Auto Scaling 그룹", "조회 · least_conn"],
    "app-db": ["매칭 app-1·app-2", name(s.endpoint), `쓰기 SQL → ${DB_NAME[s.endpoint]}`],
    "read-db": ["조회 Auto Scaling 그룹", name(s.endpoint), `조회 SQL → ${DB_NAME[s.endpoint]}`],
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
  read1: "springboot",
  read2: "springboot",
  read3: "springboot",
  pg1: "postgresql",
  pg2: "postgresql",
  bot: "bot",
  obs: ["prometheus", "grafana"],
  load: "load",
};

/** DB 카드 제목. 노드 이름 대신 지금 역할을 적어 전환 뒤에도 어느 쪽이 쓰기를 받는지 바로 읽힌다 */
const DB_TITLE: Record<DbStatus, string> = {
  primary: "Primary",
  standby: "Standby",
  stopped: "옛 Primary",
  rejoining: "Standby 재편입",
};

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

/** 실행 중 노드의 상태 줄에서 키울 핵심 수치. 숫자와 단위(%, VU, OPS, 대)만이다 */
const FIGURE_RE = /(\d+(?:\.\d+)?)(\s?(?:%|VU|OPS|대))/;
/** Auto Scaling 상태 줄의 대수. 가동이면 "3대", 조정 중이면 "2 → 3대"(등록 → 목표)다 */
const ASG_FIGURE_RE = /\d+(?: → \d+)?대/;

/**
 * 글자에서 첫 일치만 핵심 수치 규격으로 감싼다. 'CPU' 같은 라벨은 11px 그대로 두고 수치만 13px 굵게
 * 올려 뒷자리에서도 숫자가 먼저 읽힌다(디자인 시스템 §12-2). 일치가 없으면 글자 그대로다
 */
function withFigure(text: string, re: RegExp, flash?: "success" | "warning"): ReactNode {
  const m = re.exec(text);
  if (m === null) return text;
  return (
    <>
      {text.slice(0, m.index)}
      <span className={styles.figure} data-flash={flash}>
        {m[0]}
      </span>
      {text.slice(m.index + m[0].length)}
    </>
  );
}

/**
 * 상태 한 줄. 앞의 형태가 색 없이도 상태를 가르고, 형태와 글자가 상태색을 받는다.
 * 미구축은 형태가 없고 낱말만 적는다. 실행 중일 때만 핵심 수치를 키운다. 장애·전환 중 줄은 사건 문구라
 * 수치가 주인공이 아니다
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
      <span className={styles.statusText}>{state === "running" ? withFigure(text, FIGURE_RE) : text}</span>
    </span>
  );
}

/**
 * 보고 경로 자리 카드(AI 운영 보고, Teams). 단추가 아니라 누를 수 없고 팝오버도 조치도 없다. 설정 화면의 Teams
 * 행과 같은 판정을 적는다. 보고 서비스가 연결 전이면 미구축 모양(점선 윤곽, ◌ 연결 전), 전송 기록이 있으면 실행
 * 모양과 마지막 시각, 실패가 남았으면 장애 모양이다. 보고 내용은 싣지 않는다. 뜻은 연결 목록의 한 줄이 글로 싣는다
 */
function ReserveCard({ box, icon, title, out, state, text }: { box: Box; icon: FlowIconName; title: string; out?: boolean; state: NodeState; text: string }) {
  return (
    <div
      className={styles.node}
      style={boxStyle(box)}
      data-state={state}
      data-reserve="true"
      data-out={out ? "true" : undefined}
      aria-hidden="true"
    >
      <Tile icon={icon} />
      <span className={styles.titleRow}>
        <span className={styles.title}>{title}</span>
      </span>
      <Status state={state} text={state === "absent" ? `◌ ${text}` : text} />
    </div>
  );
}

/** 보고 경로 두 자리의 모양과 글. 연결 전이면 둘 다 '연결 전'이다 */
function deliveryView(d: FlowDelivery): { report: { state: NodeState; text: string }; teams: { state: NodeState; text: string }; wire: "off" | "idle" | "down" } {
  if (d.state === "NOT_CONNECTED" || d.state === "ASSUMED") {
    return { report: { state: "absent", text: "연결 전" }, teams: { state: "absent", text: "연결 전" }, wire: "off" };
  }
  const report = { state: "running" as NodeState, text: d.lastReportAt ? `보고 ${hm(d.lastReportAt)}` : "보고 없음" };
  if (d.state === "ERROR") return { report, teams: { state: "failure", text: "전송 실패" }, wire: "down" };
  return { report, teams: { state: "running", text: d.lastSentAt ? `전송 ${hm(d.lastSentAt)}` : "보낸 기록 없음" }, wire: "idle" };
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
  const cpuLine = (isEngine(id) || isRead(id)) && s.apps[id].status === "running" && v.state === "running";
  if (!cpuLine && v.status !== STATE_WORD[v.state]) words.push(v.status);
  return words.join(" · ");
}

/* ===== ai-1 =====
   관제 판의 AI 서버 칸을 이 구성도의 노드 상태로 옮긴다. 원천이 연결 전이면 미구축 모양(점선, ◌ 연결 전)이고,
   연결이 끊기면 장애다. 대기와 판단 처리 중은 둘 다 실행이고 상태 줄 글자만 다르다 */

interface AiView {
  state: NodeState;
  status: string;
  server: AiServer | null;
  source: string | null;
}

function aiView(section: OpsSection<AiServer> | null): AiView {
  if (section === null) return { state: "absent", status: "확인 중", server: null, source: null };
  if (section.state === "NOT_CONNECTED") return { state: "absent", status: "연결 전", server: null, source: section.source };
  const a = section.data;
  if (a.link === "DOWN") return { state: "failure", status: "연결 끊김", server: a, source: null };
  return { state: "running", status: a.busy ? "판단 요청 처리 중" : "대기", server: a, source: null };
}

const ms = (v: number | null) => (v === null ? "요청 없음" : v >= 1000 ? `${(v / 1000).toFixed(1)}초` : `${v}ms`);

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

export default function FlowTopology({ state, views, onPick, delivery }: FlowTopologyProps) {
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
  // ai-1 은 흐름 시뮬레이터가 아니라 관제 판의 AI 서버 칸을 읽는다. 운영 관제 셸의 폴러를 함께 쓴다
  const { data: center } = useOpsCenter();
  const aiSection = center?.aiServer ?? null;
  const ai = aiView(aiSection);
  const mode = useFlowRun((r) => r.mode);
  const report = deliveryView(delivery);

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
  const reserveWires = useMemo(() => buildReserveWires(L), [L]);
  // 선 상태는 매초 다시 구하지만 서명 문자열은 상태나 단계가 바뀔 때만 달라진다
  const flows = useMemo(() => edgeFlows(state), [state]);
  const sig = trafficSignature(flows);
  const asg = asgView(state);
  // 그룹이 조정 기준으로 삼는 조회 사용률. 지표 관제의 "조회 사용률 %" 그래프와 같은 표본(마지막 값)을
  // 정수로 골라 두 화면의 숫자가 어긋나지 않는다. 정수라 값이 바뀔 때만 다시 그린다
  const util = useFlowRun((r) => Math.round((r.samples[r.samples.length - 1]?.util ?? 0) * 100));
  // 증설 기준. 관제 값 모드는 임계값 관리의 증설 임계(관제센터 조회 ASG 눈금과 같은 값), 재생은 로컬 조정기의 권고 임계다
  const live = state.live;
  const advice = live ? Math.round(live.scaleOut * 100) : Math.round(SCALE_OUT_ADVICE * 100);
  const adviceWord = live ? "증설" : "권고";
  /** 재생을 걸 세계. 관제 값 모드에서는 재생 세계의 첫 상태로 막힘과 설명을 판정한다 */
  const replayState = (id: ScenarioId, target?: TargetId) => (mode === "live" ? replayBase(id, target) : state);
  // 상태가 바뀐 카드와 그룹 틀에 링 펄스를, 등록 대수가 바뀐 숫자에 플래시를 켠다. 처음 받은 값은 전이가
  // 아니다(useChangeFlags). 매초 바뀌는 CPU·사용률은 상태가 아니라 움직이지 않는다(디자인 시스템 §12-4)
  const pulse = useChangeFlags(
    {
      users: views.users.state,
      cdn: views.cdn.state,
      load: views.load.state,
      alb: views.alb.state,
      api1: views.api1.state,
      api2: views.api2.state,
      read1: views.read1.state,
      read2: views.read2.state,
      read3: views.read3.state,
      pg1: views.pg1.state,
      pg2: views.pg2.state,
      bot: views.bot.state,
      obs: views.obs.state,
      asg: asg.state,
      ai: ai.state,
    },
    PULSE_HOLD_MS,
  );
  const countFlash = useChangeFlags({ n: asg.registered }, FLASH_HOLD_MS);

  const pick = (req: FlowPick) => {
    // 확인 대화상자는 열릴 때의 포커스를 기억했다가 닫힐 때 돌려준다. 팝오버 버튼은 곧 사라지므로
    // 먼저 노드로 옮겨 둔다. 그래야 대화상자를 닫은 뒤 키보드 위치가 노드에 남는다
    if (open !== null) triggers.current.get(open)?.focus();
    setOpen(null);
    onPick(req);
  };

  const w = writable(state);
  const r3 = state.apps.read3;
  const endpointLine = `${DB_NAME[state.endpoint]} · ${DB_CONTAINER[state.endpoint]}`;
  const readRoster = `read-1 · read-2 · read-3${r3.inRead ? "" : " (down)"}`;

  const detail = (key: PopKey): Detail => {
    if (key === "asg") {
      return {
        icon: "asg",
        title: "Auto Scaling group · 조회",
        sub: "capacity_reconciler.py · 호스트",
        state: asg.state,
        status: asg.status,
        rows: live
          ? [
              ["범위", `min ${asg.min} · max ${asg.max}`],
              ["조회 사용률", `${util}% · 증설 임계 ${advice}%`],
              ["목표", `${asg.desired}대`],
              ["등록", `${asg.registered}대 · ALB 조회 대상`],
              ["노드", "read-1 · read-2 · read-3(+1) · 조회 전용"],
              ["매칭 노드", "app-1 · app-2 는 그룹 밖 · 대수 고정"],
              ["임계", `증설 ${advice}% · 축소 ${Math.round(live.scaleIn * 100)}% · 임계값 관리의 현재값`],
              ["AWS", "Auto Scaling group · AWS 지표 정책이 대수를 정합니다"],
              ["값", "관제 판 · 관제센터 조회 ASG와 같은 값"],
            ]
          : [
              ["범위", `min ${asg.min} · max ${asg.max}`],
              ["조회 사용률", `${util}% · 증설 권고 ${advice}%`],
              ["목표", `${asg.desired}대`],
              ["등록", `${asg.registered}대 · ALB 조회 대상`],
              ["노드", "read-1 · read-2 · read-3(+1) · 조회 전용"],
              ["매칭 노드", "app-1 · app-2 는 그룹 밖 · 대수 고정"],
              ["Docker 대역", "capacity_reconciler.py · docker start·stop · ALB down 표식 토글"],
              ["AWS", "Auto Scaling group · AWS 지표 정책이 대수를 정합니다"],
              ["증설 권고", `조회 사용률 ${advice}% 이상 · 로컬 조정기 값 · 관제 임계와 별개`],
              ["승인", "운영자 +1·-1 승인 뒤 조정"],
            ],
        actions: [{ id: "scale", tone: "neutral" }],
      };
    }
    if (key === "ai") {
      const a = ai.server;
      const rows: [string, string][] = a
        ? [
            ["위치", "연구소 · AWS 밖 · 온프레미스"],
            ["장비", `${a.hardware} · 통합 메모리 ${a.memTotalGb}GB`],
            ["실행", `${a.runtime} · 로컬 LLM`],
            ["적재 모델", a.model],
            ["GPU", a.gpuPct === null ? "미수집" : `${a.gpuPct}%`],
            ["메모리", a.memUsedGb === null ? "미수집" : `${a.memUsedGb} / ${a.memTotalGb}GB`],
            ["추론 지연", `p50 ${ms(a.p50Ms)} · p95 ${ms(a.p95Ms)}`],
            ["처리량", a.tokensPerSec === null ? "미수집" : `${a.tokensPerSec} 토큰/초`],
            ["대기 요청", a.queue === null ? "미수집" : `${a.queue}건`],
            ["관제 연결", a.assumed ? "시뮬레이션 가정 · 실제 연결 경로 결정 전" : "연결 경로 결정 전 · 선은 그림"],
            ["구축", "ADR 0038 · 구축 전"],
          ]
        : [
            ["위치", "연구소 · AWS 밖 · 온프레미스"],
            ["원천", ai.source ?? "관제 판 확인 중"],
            ["구축", "ADR 0038 · 구축 전"],
          ];
      return {
        icon: "chip",
        title: "ai-1 · 온프레미스 AI 서버",
        sub: a ? `${a.hardware} · ${a.runtime}${a.assumed ? " · 시뮬레이션 가정" : ""}` : "원천 연결 전",
        state: ai.state,
        status: ai.status,
        rows,
        actions: [],
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
            ["조회 분산", `least_conn · 연결이 적은 노드로 · ${readRoster}`],
            ["Docker 대역", "조회는 chaos-api-1 · chaos-api-2 · chaos-api-3"],
            ["지금", v.status],
          ],
          actions: [],
        };
      case "api1":
      case "api2": {
        const parts = partitionsOf(state, key);
        return {
          ...head,
          rows: live
            ? [
                ["컨테이너", `${APP_CONTAINER[key]} · :${APP_PORT[key]}`],
                ["역할", v.role],
                ["상태", v.status],
                ["리더 리스", parts.length > 0 ? `${parts.map((p) => p.id).join("·")} 보유 · TTL ${LEASE_TTL}초 · 갱신 ${LEASE_TICK}초` : "보유 파티션 없음"],
                ["세대", parts.length > 0 ? parts.map((p) => `${p.id} epoch ${p.epoch}`).join(" · ") : "없음"],
                ["마켓별 리더", "ADR 0034 배치 · 관제 판 값"],
                ["조회", "받지 않음 · 조회는 Auto Scaling 그룹"],
                ["DB 접속", endpointLine],
              ]
            : [
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
                ["마켓별 리더", "ADR 0034 · 이 재생은 단일 리더 측정 기록"],
                ["조회", "받지 않음 · 조회는 Auto Scaling 그룹"],
                ["DB 접속", endpointLine],
              ],
          actions: [
            { id: "leaderKill", target: key, tone: "danger" },
            { id: "restart", target: key, tone: "neutral" },
          ],
        };
      }
      case "read1":
      case "read2":
        return {
          ...head,
          rows: [
            ["역할", v.role],
            ["상태", v.status],
            ["ALB 조회", state.apps[key].inRead ? "등록" : "down 표식"],
            ["Docker 대역", `전용 컨테이너 없음 · ${APP_CONTAINER[key].split(" · ")[0]} 가 조회 겸함`],
            ["재생", "매칭 노드 중단은 AWS 목표 구성대로 조회 노드를 그대로 둠"],
            ["DB 접속", endpointLine],
          ],
          actions: [],
        };
      case "read3":
        return {
          ...head,
          rows: [
            ["컨테이너", `${APP_CONTAINER.read3} · :${APP_PORT.read3}`],
            ["역할", v.role],
            ["상태", v.status],
            ["ALB 조회", r3.inRead ? "등록" : "down 표식"],
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
          ["앱 접속", state.endpoint === key ? "접속 대상 · 쓰기 SQL · 조회 SQL" : "미접속"],
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
            ["발행률", live ? `목표 ${live.botTarget} OPS · 지금 ${state.botOps} OPS · 봇 플릿과 같은 값` : `목표 ${state.botOps} OPS · 기본 ${BASE_OPS}`],
            ["AWS", "bot-1 한 호스트 · 관측과 함께"],
          ],
          actions: [{ id: "traffic", tone: "neutral" }],
        };
      // 수집 원천은 harness/prometheus/prometheus.yml 을 따른다. 앱 지표(/actuator/prometheus)는
      // app-1·app-2·봇만 긁는다. 컨테이너 CPU 는 chaos-cstats 가 도커 API 로 전부 읽어 read-3 도 거기에는 잡힌다
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
            ["미수집", "read-3 앱 지표"],
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
              const base = replayState(a.id, a.target);
              const av = availability(base, a.id, a.target);
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
                    {describeScenario(base, a.id, a.target).title}
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
   * 중지는 회색 테두리에 가라앉은 면이다. 매칭 리더는 테두리 안쪽의 크롬 악센트 레일 하나로만 가른다
   */
  const card = (key: NodeId, out = false) => {
    const v = views[key];
    const leader = isEngine(key) && isLeader(state, key);
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
          data-out={out ? "true" : undefined}
          data-pulse={pulse[key] ? "true" : undefined}
        >
          <Tile icon={ICON[key]} />
          <span className={styles.titleRow}>
            <span className={styles.title}>{cardTitle(key, v, state)}</span>
          </span>
          <Status state={v.state} text={v.status} />
        </button>
        {popover(key, box)}
      </Fragment>
    );
  };

  /**
   * 조회 노드 줄. 그룹 안의 낮은 카드로 이름과 상태 한 줄만 싣는다. 테두리와 면의 상태 규칙은 노드 카드와
   * 같다. read-3 은 그룹의 +1 자리라 꺼져 있으면 점선으로 비워 둔다. 실행 중이면 상태 줄이 CPU 수치만 적는다
   */
  const readRow = (key: ReadId) => {
    const v = views[key];
    const box = L.nodes[key];
    const running = state.apps[key].status === "running" && v.state === "running";
    return (
      <Fragment key={key}>
        <button
          {...triggerProps(key)}
          aria-label={nodeLabel(key, v, state)}
          className={`${styles.node} ${styles.readRow}`}
          style={boxStyle(box)}
          data-state={v.state}
          data-slot={key === "read3" ? "true" : undefined}
          data-pulse={pulse[key] ? "true" : undefined}
        >
          <span className={styles.readName}>{v.name}</span>
          <Status state={v.state} text={running ? v.status.replace(/^CPU /, "") : v.status} />
        </button>
        {popover(key, box)}
      </Fragment>
    );
  };

  const asgLabel = `조회 Auto Scaling group · min ${asg.min} · max ${asg.max} · ${live ? "증설 임계" : "증설 권고"} ${advice}% · ${asg.status}`;
  const countTone = countFlash.n ? (asg.state === "transition" ? "warning" : "success") : undefined;
  const aiLabel = `ai-1 · 온프레미스 AI 서버 · ${ai.server ? `${ai.server.hardware} · ${ai.server.runtime}` : "원천 연결 전"} · ${STATE_WORD[ai.state]}${ai.state === "running" ? ` · ${ai.status}` : ""}${ai.server?.assumed ? " · 구축 전이라 연결은 시뮬레이션 가정" : ""}`;

  return (
    <div className={styles.root}>
      <div
        ref={wrapRef}
        className={styles.topology}
        style={{ height: H, minWidth: MIN_W }}
        role="group"
        aria-label="서비스 흐름 구성도 · AWS 대역 · Docker exchange-chaos · 온프레미스 ai-1"
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
        <div className={styles.frame} data-kind="onprem" style={boxStyle(L.onprem)} aria-hidden="true">
          <FrameLabel icon="building">온프레미스 · 연구소</FrameLabel>
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
        <div className={styles.frame} data-kind="match" style={boxStyle(L.match)} aria-hidden="true">
          <FrameLabel icon="server">매칭 · 쓰기</FrameLabel>
        </div>
        <div className={styles.frame} data-kind="subnet" style={boxStyle(L.db)} aria-hidden="true">
          <FrameLabel icon="db">DB · PostgreSQL</FrameLabel>
        </div>
        {/* bot-1 칸의 이름표도 다른 틀처럼 왼쪽 위다. 시장 봇 선이 ALB 가운데 세로줄로 칸 윗변을 지나는
            자리(956px 에서 칸 왼쪽 끝에서 67px)보다 앞에서 끝나게 "bot-1"(끝이 약 53px)만 적는다 */}
        <div className={styles.frame} data-kind="host" style={boxStyle(L.host)} aria-hidden="true">
          <FrameLabel icon="server">bot-1</FrameLabel>
        </div>
        {/* 조회 Auto Scaling 그룹. 조회 계층을 강조하는 틀이라 테두리를 한 단 굵고 진하게 두고, 조정 중이면
            주황 점선으로 바꾼다. 머리는 단추라 눌러 그룹의 범위와 조정 방법을 연다 */}
        <div
          className={styles.asgFrame}
          data-state={asg.state}
          data-pulse={pulse.asg ? "true" : undefined}
          style={boxStyle(L.asg)}
          aria-hidden="true"
        />

        {/* 선과 트래픽 층. 선 그림은 카드보다 먼저 두어 화살촉이 카드 테두리 밑에 닿고, 알갱이 층은
            z-index 로 카드 위에 올린다(traffic.module.css). 받는 값이 모두 사건 때만 바뀌어 매초의
            다시 그리기가 이 층을 건너뛴다 */}
        <FlowTraffic width={L.width} height={H} wires={wires} sig={sig} />
        <ReserveWires width={L.width} height={H} paths={reserveWires} kind={report.wire} />
        <AiLink
          x0={right(L.ai)}
          x1={L.match.x}
          y={AI_Y}
          width={L.width}
          height={H}
          busy={ai.server?.busy ?? false}
          served={ai.server?.served ?? 0}
          down={ai.state === "failure"}
        />

        {card("users", true)}
        {card("cdn")}
        {card("load")}
        {card("alb")}
        {card("api1")}
        {card("api2")}
        {/* 그룹 머리 단추는 매칭 카드 뒤에 둔다. 탭 순서가 그림을 읽는 순서(사용자, CloudFront, load-1,
            ALB, app-1·app-2, 그룹, read-1~3, DB, bot-1, ai-1)를 따른다 */}
        <button
          {...triggerProps("asg")}
          aria-label={asgLabel}
          className={styles.asgHead}
          style={boxStyle(L.asgHead)}
        >
          <span className={styles.asgTitleRow}>
            <span className={styles.asgTitle}>
              <FlowIcon name="asg" size={14} />
              Auto Scaling
            </span>
            <span className={styles.asgRange}>
              {asg.min}~{asg.max}대
            </span>
          </span>
          {/* 둘째 줄은 지금 대수와 조정 기준(사용률)이다. 조정 중(▲)일 때 왜 늘리는지가 머리에서 바로 읽힌다.
              사용률은 매초 바뀌므로 접근 이름에는 싣지 않고 팝오버에 권고 임계와 함께 적는다 */}
          <span className={styles.asgStateRow}>
            <span className={styles.status} data-state={asg.state}>
              <span className={styles.shape} aria-hidden="true">
                {STATE_SHAPE[asg.state]}
              </span>
              <span className={styles.statusText}>{withFigure(asg.status, ASG_FIGURE_RE, countTone)}</span>
            </span>
            <span className={styles.asgUtil}>
              {L.asgWide ? "사용률 " : ""}
              <span className={styles.figure}>{util}%</span>
            </span>
          </span>
          {/* 셋째 줄은 사용률 막대 자리만 잡는다. 막대(MeterRail)는 div 라 단추의 내용이 될 수 없어
              단추 밖 형제로 같은 자리에 겹쳐 그린다(아래 asgRail) */}
          <span className={styles.asgRailSlot} aria-hidden="true" />
        </button>
        {/* 사용률 막대와 증설 권고 임계. 용량 화면과 같은 계기라 막대 어휘가 같다. 평소 무채이고 임계
            이상일 때만 주의 채움이다. 머리 단추의 셋째 줄 자리에 겹쳐 두고, 누름은 단추로 흘려보낸다.
            막대는 그림이라 숨기고 임계는 단추의 접근 이름에 싣는다 */}
        <div
          className={styles.asgRail}
          style={boxStyle({
            x: L.asgHead.x + ASG_HEAD_PAD,
            y: L.asgHead.y + ASG_RAIL_TOP,
            w: L.asgHead.w - ASG_HEAD_PAD * 2,
            h: ASG_RAIL_H,
          })}
          aria-hidden="true"
        >
          <MeterRail value={util} max={100} threshold={advice} tone={util >= advice ? "warning" : undefined} />
          <span className={styles.asgRailLabel}>
            {adviceWord} {advice}%
          </span>
        </div>
        {popover("asg", L.asgHead)}
        {readRow("read1")}
        {readRow("read2")}
        {readRow("read3")}
        {card("pg1")}
        {card("pg2")}
        {card("bot")}
        {card("obs")}
        <ReserveCard box={L.reserve.report} icon="report" title="AI 운영 보고" state={report.report.state} text={report.report.text} />
        <ReserveCard box={L.reserve.teams} icon="chat" title="Teams" out state={report.teams.state} text={report.teams.text} />
        {/* 온프레미스 ai-1. 관제 판의 AI 서버 칸을 읽는다. 조치가 없는 정보 팝오버만 연다 */}
        <button
          {...triggerProps("ai")}
          aria-label={aiLabel}
          className={styles.node}
          style={boxStyle(L.ai)}
          data-state={ai.state}
          data-out="true"
          data-pulse={pulse.ai ? "true" : undefined}
        >
          <Tile icon="chip" />
          <span className={styles.titleRow}>
            <span className={styles.title}>ai-1</span>
          </span>
          <Status state={ai.state} text={ai.state === "absent" ? `◌ ${ai.status}` : ai.server?.assumed ? `${ai.status} · 가정` : ai.status} />
        </button>
        {popover("ai", L.ai)}

        {/* 선은 그림이라 보조기술에 닿지 않는다. 같은 내용을 목록으로 한 번 더 싣는다. 줄마다 상태와
            세기만 적어 매초 바뀌지 않는다 */}
        <ul className="sr-only" aria-label="연결 상태">
          {EDGE_IDS.map((id) => (
            <li key={id}>{edgeText(id, state, views, flows[id])}</li>
          ))}
          <li>{`DB 승격 · 자동 페일오버 없음 · 수동 승격`}</li>
          <li>{`매칭 app-1·app-2 ↔ ai-1 · 판단 요청 · 연결 경로 결정 전${ai.server?.assumed ? " · 시뮬레이션 가정" : ""} · ${ai.server?.busy ? "요청 처리 중" : ai.status}`}</li>
          <li>{`bot-1 관측 → AI 운영 보고 → Microsoft Teams · ${delivery.state === "CONNECTED" ? `${report.report.text} · ${report.teams.text}` : delivery.state === "ERROR" ? "전송 실패" : "보고 서비스 연결 전"}`}</li>
        </ul>
      </div>
    </div>
  );
}
