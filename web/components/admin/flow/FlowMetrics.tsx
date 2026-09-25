"use client";

/**
 * 지표 관제 화면(/admin/monitor)의 시계열. 위에 넓은 타임라인 한 장(체결/초, 봇 발행률)을 두고
 * 아래에 작은 카드 다섯 장을 두 줄로 깐다. 윗줄은 조회 경로(조회 사용률, 앱 계층 대수, 노드 CPU)를
 * 증설이 번지는 순서로, 아랫줄은 쓰기 경로의 뒤편(리더 리스 잔여, DB 복제 지연)을 넓게 둔다.
 * 어느 조작 뒤에 어느 값이 움직였는지를 한 시간축에 대 보려는 화면이다. 선 이름은 AWS 역할
 * 이름(app-1·2·3)이고 컨테이너 이름은 인스턴스 표가 맡는다.
 *
 * 값이 없던 구간은 0 으로 그리지 않고 끊는다. 컨테이너 CPU 는 chaos-cstats 가 재는데 꺼진
 * 컨테이너는 잴 대상이 없다. 복제 지연도 복제가 끊기면 잴 상대가 없다.
 * 0 으로 채우면 "CPU 가 놀았다", "지연이 없었다"로 읽혀 중단 구간이 가장 좋은 구간처럼 보인다.
 *
 * 사건 표식은 타임라인 위 별도 띠에만 둔다. 증설 시나리오는 몇 초 사이에 사건 서너 개가 몰려
 * 패널 위에 얹으면 표식끼리 겹치고 선을 가린다. 띠에서 줄을 나눠 놓고 세로선만 패널을
 * 가로지르게 했다. 작은 카드에는 이름표 없이 옅은 세로선만 같은 시각에 긋는다.
 *
 * 창은 여섯 차트 모두 최근 150초로 고정하고 스크롤·확대를 막았다. 시연 중 손이 스쳐 창이
 * 밀리면 사건 표식이 화면 밖으로 나가고 카드끼리 시각이 어긋난다.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { hms } from "@/lib/admin/timeText";
import {
  ColorType,
  LineSeries,
  LineStyle,
  LineType,
  createChart,
  type AutoscaleInfo,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type ISeriesPrimitive,
  type ISeriesPrimitiveAxisView,
  type LineData,
  type LineWidth,
  type Logical,
  type MouseEventParams,
  type SeriesAttachedParameter,
  type Time,
  type UTCTimestamp,
  type WhitespaceData,
} from "lightweight-charts";
import PanelCard from "@/components/PanelCard";
import { HEALTH_ICON } from "@/components/admin/ui";
import type { ChartTokens } from "@/components/chart/chartTheme";
import { readChartTokens, useChartTheme } from "@/components/chart/useChartTheme";
import {
  APP_NAME,
  LEASE_TICK,
  LEASE_TTL,
  linePoints,
  type FlowEvent,
  type FlowSample,
  type Health,
} from "@/lib/admin/flowSim";
import styles from "./metrics.module.css";
import StatusShape from "../StatusShape";

const WINDOW = 150;
/** 사건 띠의 줄 수와 줄 높이. 증설 시나리오에서 1~2초 간격으로 이어지는 사건 셋과 뒤따르는
    권고 사건을 선 좌우 배치까지 써서 겹치지 않게 놓는 최소 줄 수다. 표식 높이가 17px 이라 줄 사이에
    1px 만 남긴다. 사건이 없을 때도 이 높이를 잡아 두어 첫 사건이 들어올 때 차트가 밀려 내려가지 않는다 */
const LANE_ROWS = 4;
const LANE_ROW_PX = 18;
const LANE_BAND_PX = LANE_ROWS * LANE_ROW_PX;
/** 줄 번호의 위 끝. 0번 줄이 차트에 가장 가까운 맨 아래다. 사건이 드문 평시에는 표식이 차트 바로 위
    한 줄에만 서고 남는 줄은 띠 위쪽의 여백으로 읽힌다. 위에서부터 채우면 표식 하나가 차트와 멀리
    떨어져 뜨고 그 사이가 빈 칸으로 보였다 */
const rowTop = (row: number) => (LANE_ROWS - 1 - row) * LANE_ROW_PX;
/** 띠와 차트 사이 틈 */
const LANE_GAP_PX = 6;
const LANE_PX = LANE_BAND_PX + LANE_GAP_PX;
/** 같은 줄에서 앞 표식과 띄울 최소 간격 */
const TAG_GAP_PX = 6;
/** 표식의 글자 밖 폭. 좌우 여백 4px 둘, 테두리 1px 둘, 형태와 글자 사이 4px, 반올림 여유 */
const TAG_CHROME_PX = 16;

type Tone = 1 | 2 | 3 | 4;
type Dash = "solid" | "dashed" | "dotted";
type RefTone = "neutral" | "warning";

interface LineSpec {
  name: string;
  tone: Tone;
  dash: Dash;
  width: LineWidth;
  pane: number;
  steps?: boolean;
  /** 겹칠 때 아래에 깔 선. 같은 값이면 위 선의 점선 틈으로 아래 선이 비쳐 둘 다 읽힌다 */
  under?: boolean;
  /** 이 틱 간격의 표본만 선으로 잇는다. 나머지 틱은 빈 점으로 두어 시간축의 1초 간격은 그대로다 */
  every?: number;
  pick: (s: FlowSample) => number | null;
  /** 판독값 소수 자릿수 */
  digits: number;
  /** 축 눈금 소수 자릿수. 눈금은 판독값보다 거칠어야 읽기 쉽다 */
  axisDigits: number;
  unit: string;
}

/** 창(pane)마다의 축. fixed 는 범위 고정, floor 는 바닥 0 에 천장만 최소값 이상 */
type Scale = { kind: "fixed"; min: number; max: number; top: number } | { kind: "floor"; minCeil: number };

interface RefSpec {
  /** 기준선을 붙일 선의 색인 */
  line: number;
  price: number;
  title: string;
  tone: RefTone;
  /** 머리 범례 문구 */
  legend: string;
}

interface ChartSpec {
  title: string;
  lines: LineSpec[];
  scales: Scale[];
  refs: RefSpec[];
  stretch?: number[];
  scaleWidth: number;
  /** 작은 카드가 6열 격자에서 차지하는 칸 수. 세 장인 줄은 2칸씩, 두 장인 줄은 3칸씩이다 */
  span?: 2 | 3;
}

const TIMELINE: ChartSpec = {
  title: "타임라인",
  lines: [
    { name: "체결/초", tone: 1, dash: "solid", width: 2, pane: 0, pick: (s) => s.trades, digits: 1, axisDigits: 1, unit: "" },
    { name: "봇 발행률 OPS", tone: 2, dash: "dashed", width: 2, pane: 1, pick: (s) => s.botOps, digits: 0, axisDigits: 0, unit: "" },
  ],
  // 체결과 발행률은 바닥을 0 에 묶고 천장을 최소값 이상으로 둔다. 자동 축이 평시 잡음에 확대해
  // 붙으면 3.8~4.2 사이 흔들림이 급증처럼 보인다
  scales: [
    { kind: "floor", minCeil: 10 },
    { kind: "floor", minCeil: 20 },
  ],
  refs: [],
  // 체결이 이 화면의 주 지표라 조금 더 크게 둔다
  stretch: [1.2, 1],
  scaleWidth: 64,
};

/**
 * 조회 사용률 차트의 기준선. 관제 값 모드는 임계값 관리의 증설 임계(관제센터 앱 계층 눈금과 같은 값), 재생은 로컬
 * 조정기의 권고 임계다. 관제 값 모드에서는 리스를 두 파티션이 따로 쥐고, 복제 지연은 KB 정수로 적어 관제센터의
 * DB 카드와 같은 자릿수다
 */
export interface MetricsBasis {
  live: boolean;
  scaleOutPct: number;
}

/* 윗줄은 조회 경로다. 부하가 사용률을 올리고, 운영자 승인으로 app-3 이 켜져 앱 계층 대수가 바뀌고, app-3 의 CPU 가
   살아나는 순서로 왼쪽에서 오른쪽으로 읽힌다. 아랫줄은 쓰기 경로의 뒤편이라 DB 전환 재생에서 함께 본다 */
function cardsFor(b: MetricsBasis): ChartSpec[] {
  const word = b.live ? "증설 임계" : "증설 권고";
  return [
  {
    title: "조회 사용률 %",
    lines: [
      { name: "사용률", tone: 1, dash: "solid", width: 2, pane: 0, pick: (s) => s.util * 100, digits: b.live ? 0 : 1, axisDigits: 0, unit: "%" },
    ],
    scales: [{ kind: "fixed", min: 0, max: 100, top: 0.06 }],
    refs: [
      {
        line: 0,
        price: b.scaleOutPct,
        title: word,
        tone: "warning",
        legend: `${word} ${b.scaleOutPct}%`,
      },
    ],
    scaleWidth: 56,
    span: 2,
  },
  {
    title: "앱 계층 대",
    lines: [
      { name: "목표", tone: 3, dash: "dashed", width: 2, pane: 0, steps: true, pick: (s) => s.desired, digits: 0, axisDigits: 0, unit: "대" },
      { name: "등록", tone: 1, dash: "solid", width: 2, pane: 0, steps: true, under: true, pick: (s) => s.registered, digits: 0, axisDigits: 0, unit: "대" },
    ],
    scales: [{ kind: "fixed", min: 0, max: 4, top: 0.08 }],
    refs: [],
    scaleWidth: 56,
    span: 2,
  },
  {
    title: "노드 CPU %",
    lines: [
      { name: APP_NAME.api1, tone: 1, dash: "solid", width: 2, pane: 0, pick: (s) => s.cpu.api1, digits: b.live ? 0 : 1, axisDigits: 0, unit: "%" },
      { name: APP_NAME.api2, tone: 2, dash: "dashed", width: 2, pane: 0, pick: (s) => s.cpu.api2, digits: b.live ? 0 : 1, axisDigits: 0, unit: "%" },
      // 앱 계층 세 대를 모두 싣는다. app-3 은 켜져 있을 때만 값이 있어 꺼진 구간은 끊긴다
      { name: APP_NAME.app3, tone: 3, dash: "dotted", width: 2, pane: 0, pick: (s) => s.cpu.app3, digits: b.live ? 0 : 1, axisDigits: 0, unit: "%" },
    ],
    // 기본 여백(위 20%)을 두면 축이 120% 까지 올라가 있을 수 없는 값을 눈금으로 보인다
    scales: [{ kind: "fixed", min: 0, max: 100, top: 0.06 }],
    refs: [],
    scaleWidth: 56,
    span: 2,
  },
  {
    title: b.live ? "파티션 리스 잔여 초" : "리더 리스 잔여 초",
    lines: [
      // 평시에는 2초 갱신으로 6과 5 사이를 매초 오간다. 초마다 이으면 1초 간격 톱니가 촘촘한 띠나
      // 바코드처럼 보여 정상이 오히려 요란하다. 선은 갱신 주기(2초)마다 한 점만 잇는다. 평시는 TTL 에
      // 붙은 곧은 선, 장애 때는 1초에 1씩 줄어드는 그대로의 기울기로 0 까지 내려간다. 지금 값은
      // 판독값이 초마다 적는다
      {
        name: "잔여",
        tone: 1,
        dash: "solid",
        width: 2,
        pane: 0,
        every: LEASE_TICK,
        pick: (s) => s.lease,
        digits: 0,
        axisDigits: 0,
        unit: "초",
      },
    ],
    // TTL 선이 천장에 붙으면 테두리와 겹쳐 안 보인다. 위 여백을 조금 더 준다
    scales: [{ kind: "fixed", min: 0, max: LEASE_TTL, top: 0.14 }],
    refs: [{ line: 0, price: LEASE_TTL, title: "TTL", tone: "neutral", legend: `TTL ${LEASE_TTL}초` }],
    scaleWidth: 56,
    span: 3,
  },
  {
    title: "DB 복제 지연 KB",
    lines: [
      // 복제가 끊기면(Primary 중단, 재편입 중) 잴 상대가 없어 공백이다. 0 KB 로 그리면 가장 잘
      // 따라붙은 순간처럼 읽힌다
      { name: "지연", tone: 1, dash: "solid", width: 2, pane: 0, pick: (s) => s.replLagKb, digits: b.live ? 0 : 1, axisDigits: 0, unit: "KB" },
    ],
    // 평시 2KB 안팎의 흔들림에 축이 확대해 붙지 않게 바닥 0, 천장 최소 5KB 로 둔다
    scales: [{ kind: "floor", minCeil: 5 }],
    refs: [],
    scaleWidth: 56,
    span: 3,
  },
  ];
}

const LINE_STYLE: Record<Dash, LineStyle> = {
  solid: LineStyle.Solid,
  dashed: LineStyle.Dashed,
  dotted: LineStyle.Dotted,
};

/* 범례 견본의 점선 간격. 캔버스 선과 같은 모양으로 보이게 굵기 2px 기준으로 잡았다 */
const DASH_ARRAY: Record<Dash, string | undefined> = {
  solid: undefined,
  dashed: "4 3",
  dotted: "0.1 4",
};

const HEALTH_WORD: Record<Health, string> = { success: "정상", warning: "주의", danger: "장애" };

type Point = LineData<Time> | WhitespaceData<Time>;

interface Placed {
  key: string;
  x: number;
  event: FlowEvent;
}

interface Frame {
  plotW: number;
  axisH: number;
  paneTops: number[];
  marks: Placed[];
}

const EMPTY_FRAME: Frame = { plotW: 0, axisH: 28, paneTops: [], marks: [] };

function sameFrame(a: Frame, b: Frame): boolean {
  if (a.plotW !== b.plotW || a.axisH !== b.axisH) return false;
  if (a.paneTops.length !== b.paneTops.length || a.paneTops.some((t, i) => t !== b.paneTops[i])) return false;
  if (a.marks.length !== b.marks.length) return false;
  return a.marks.every((m, i) => m.key === b.marks[i].key && m.x === b.marks[i].x);
}

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function toneColor(t: ChartTokens, tone: Tone): string {
  return [t.series1, t.series2, t.series3, t.series4][tone - 1];
}

/**
 * 기준선의 선 색과 축 표지 색. 선은 그래픽이라 3:1 만 넘으면 되어 주황(--color-warning)을 그대로 쓴다.
 * 표지는 11~12px 글자를 얹으므로 4.5:1 이 필요하다. 라이브러리에 맡기면 선 색을 면으로 칠하고 글자색을
 * 밝기로 골라서, 라이트에서 흰 글자가 주황 면 위 3.09:1 로 떨어졌다. 그래서 표지 글자는 카드 면 색으로
 * 따로 준다.
 *
 * 주황은 실제 주의에만 쓴다(디자인 시스템 §12-3). 기준선은 늘 서 있는 눈금이라 평소 표지는 무채 기준선
 * (TTL)과 같은 면이고, 선 값이 기준 이상인 동안만 표지를 경고 글자색 면(라이트 orange-800, 흰 글자와
 * 6.5:1)으로 칠한다. 다크에서는 두 경고 토큰이 같은 밝은 주황이라 어두운 글자와 9.15:1 이다
 */
function refFace(t: ChartTokens, tone: RefTone, over: boolean) {
  const warning = tone === "warning";
  return {
    color: warning ? cssVar("--color-warning") : t.axisText,
    axisLabelColor: warning && over ? cssVar("--color-warning-text") : t.axisText,
    axisLabelTextColor: t.surface,
  };
}

/* 십자선. 시간 표지와 가격 표지는 무채색 조작 면(--surface-control-hover)에 가장 진한 잉크를 얹는다.
   지금 값 표지는 선 색(파랑 램프)을 칠하므로 둘이 나란히 서도 어느 쪽이 지금 값인지 색으로 갈린다.
   라이브러리 기본값은 검정 고정 면이라 라이트에서는 지금 값보다 더 세게 읽히고, 본문 잉크를 면으로
   뒤집으면 다크에서 지금 값 표지(가장 밝은 파랑)와 같은 흰 표지가 된다. 글자는 두 테마 모두 면 위
   11:1 을 넘는다. 가격 표지는 라이브러리 것을 끄고 CrossLabel 이 대신 그린다 */
function crosshairOptions(t: ChartTokens) {
  return {
    vertLine: { color: t.crosshairLine, labelBackgroundColor: cssVar("--surface-control-hover") },
    horzLine: { color: t.crosshairLine, labelVisible: false },
  };
}
function crossFace(): { back: string; text: string } {
  return { back: cssVar("--surface-control-hover"), text: cssVar("--text-strong") };
}

/** 라이브러리가 그리는 축 표지(지금 값, 기준선) 높이. 기본 글자 12px(layout.fontSize)에 위아래 여백 2.5px */
const AXIS_TAG_PX = 17;
/** 플러그인 축 표지 높이. 라이브러리가 플러그인 표지에만 위아래 2px 을 더 준다 */
const CROSS_TAG_PX = AXIS_TAG_PX + 4;
/** 십자선 표지와 쉬는 표지가 겹치지 않는 가운데 사이 최소 거리 */
const TAG_CLEAR_PX = (AXIS_TAG_PX + CROSS_TAG_PX) / 2;
/** 눈금 숫자의 반 높이. 12px 글꼴의 숫자는 약 9px 높이로 눈금 높이 가운데에 선다 */
const TICK_GLYPH_HALF_PX = 5;
/**
 * 눈금 가운데가 쉬는 표지 가운데에서 이 거리 안이면 그 눈금 글자를 비운다. 표지 반 높이와 숫자 반
 * 높이에 2px 틈을 더한 값이다. 라이브러리는 눈금 글자 위에 표지를 덮어 그릴 뿐 비키지 않아서, 표지가
 * 눈금과 반쯤 겹치면 눈금 숫자의 아래나 위 절반이 표지 밖으로 비어져 나왔다(평시 사용률 6% 표지 밑의
 * 0%). 1px 틈으로는 표지 바로 밑에 눈금이 붙어 두 숫자가 한 덩이로 읽혔다. 눈금 간격은 라이브러리가
 * 30px 이상으로 잡으므로 표지 하나가 비우는 눈금은 대개 하나이고, 두 눈금 한가운데에 설 때만 둘이다
 */
const TICK_CLEAR_PX = AXIS_TAG_PX / 2 + TICK_GLYPH_HALF_PX + 2;
/**
 * 한 번 비운 눈금은 표지가 이만큼 더 멀어져야 다시 적는다. 지금 값은 매초 조금씩 흔들리므로 문턱
 * 하나로만 가르면 문턱 근처에서 눈금이 몇 초마다 켜졌다 꺼지며 깜박인다. 가장 크게 흔들리는 평시
 * 체결/초가 1440px 화면에서 축 위 7px 안팎을 오가서 그보다 조금 크게 잡았다. 비운 채로 두는 눈금은
 * 표지 바로 옆이라 지금 값 표지가 대신 읽힌다
 */
const TICK_HOLD_PX = 8;
/** 눈금 값을 비운 눈금 기록의 열쇠로 바꾼다. 눈금 값은 나눗셈에서 나와 끝자리 오차가 있을 수 있다 */
const tickKey = (p: number) => Math.round(p * 1e6);

/**
 * 쉬는 표지가 축에 실제로 서는 높이. 라이브러리는 겹친 표지를 창의 첫 선 지금 값을 가운데 삼아 위아래로
 * 밀어 내고, 밀린 표지가 창 끝을 넘으면 그 무리를 통째로 안으로 당긴다(가격 축 alignLabels). 그 결과를
 * 읽는 공개 API 가 없어 lightweight-charts 5.2 의 PriceAxisWidget 정렬 규칙을 여기서 그대로 다시 돈다.
 * 라이브러리 판을 올리면 이 규칙이 그대로인지 확인해야 한다.
 * raw 는 라이브러리가 표지를 모으는 순서(선을 만든 순서, 한 선 안에서는 지금 값 다음 기준선)여야 한다.
 * 같은 높이의 표지끼리는 이 순서로 밀리는 쪽이 정해진다
 */
function settleTags(raw: number[], center: number, paneH: number): number[] {
  const half = Math.floor(AXIS_TAG_PX / 2);
  const tags = raw.map((y) => ({ y, at: y }));
  const top = tags.filter((g) => g.y <= center).sort((a, b) => b.y - a.y);
  const bottom = tags.filter((g) => g.y > center);
  if (top.length > 0 && bottom.length > 0) bottom.push(top[0]);
  bottom.sort((a, b) => a.y - b.y);
  for (const g of tags) {
    if (g.y > -half && g.y < half) g.at = half;
    if (g.y > paneH - half && g.y < paneH + half) g.at = paneH - half;
  }
  spreadTags(top, 1, paneH);
  spreadTags(bottom, -1, paneH);
  return tags.map((g) => g.at);
}

/** settleTags 의 한쪽 밀어 내기. dir 1 은 가운데에서 위로, -1 은 아래로 민다 */
function spreadTags(tags: { at: number }[], dir: 1 | -1, paneH: number) {
  if (tags.length === 0) return;
  const h = AXIS_TAG_PX;
  let groupStart = 0;
  let room = Math.max(0, dir === 1 ? paneH / 2 - (tags[0].at - h / 2) : tags[0].at - h / 2 - paneH / 2);
  for (let i = 1; i < tags.length; i += 1) {
    const cur = tags[i].at;
    const prev = tags[i - 1].at;
    if (dir === 1 ? cur <= prev - h : cur >= prev + h) {
      groupStart = i;
      room = dir === 1 ? prev - h - cur : cur - (prev + h);
      continue;
    }
    const at = prev - h * dir;
    tags[i].at = at;
    const edge = at - (dir * h) / 2;
    if ((dir === 1 ? edge < 0 : edge > paneH) && room > 0) {
      const shift = Math.min(dir === 1 ? -1 - edge : edge - paneH, room);
      for (let k = groupStart; k < tags.length; k += 1) tags[k].at += dir * shift;
      room -= shift;
    }
  }
}

/**
 * 십자선 표지를 세울 높이. 원하는 높이(십자선 가로선)가 쉬는 표지와 겹치지 않으면 그대로 두고, 겹치면
 * 쉬는 표지 바로 위나 아래 중 원래 높이에 가까운 빈자리로 비켜 세운다. 창 밖으로는 내보내지 않는다.
 * 비키는 거리는 표지 한 칸까지다. 그 안에 빈자리가 없으면(창 끝이나 다른 표지에 막히면) null 을 돌려
 * 표지를 내린다. 축에서 높이가 곧 값이라 두 칸 넘게 떨어진 표지는 기준선 너머의 다른 값처럼 읽혔다.
 * 그 자리의 십자선은 바로 옆 쉬는 표지와 한 칸 안쪽 값이라 그 표지가 대신 읽힌다
 */
function crossSlot(want: number, taken: number[], paneH: number): number | null {
  const lo = CROSS_TAG_PX / 2;
  const hi = paneH - CROSS_TAG_PX / 2;
  const clamp = (y: number) => Math.min(Math.max(y, lo), hi);
  const free = (y: number) => taken.every((t) => Math.abs(t - y) >= TAG_CLEAR_PX);
  const near = (y: number) => Math.abs(y - want) <= TAG_CLEAR_PX + 0.5;
  const slots = [want, ...taken.flatMap((t) => [t - TAG_CLEAR_PX, t + TAG_CLEAR_PX])]
    .map(clamp)
    .filter((y) => free(y) && near(y));
  if (slots.length === 0) return null;
  return slots.reduce((best, y) => (Math.abs(y - want) < Math.abs(best - want) ? y : best));
}

/**
 * 십자선 가격 표지. 라이브러리의 십자선 가격 표지는 축의 다른 표지와 자리를 나누지 않고 맨 위에 덮어
 * 그려서, 지금 값 표지 근처에 오면 그 숫자를 가렸다. 그래서 그 표지를 끄고 창마다 이 표지를 둔다.
 * 높이를 고정 좌표로 주므로 라이브러리가 다른 표지를 밀어 내지 않고(지금 값 표지는 쉴 때 자리 그대로),
 * 이 표지만 crossSlot 이 고른 빈자리에 선다. 가격선 표지로 두면 겹침 정리에 함께 들어가서 창 아래 끝
 * 근처에서 지금 값 표지가 기준선 표지 밑으로 밀려 들어갔다
 */
class CrossLabel implements ISeriesPrimitive<Time> {
  private y: number | null = null;
  private label = "";
  private face = { back: "", text: "" };
  private request: (() => void) | null = null;
  private readonly views: readonly ISeriesPrimitiveAxisView[] = [
    {
      // 고정 좌표 표지는 정렬에서 빠진다. 문서가 권하는 대로 정렬 자리는 창 밖 멀리 둔다
      coordinate: () => -1e6,
      fixedCoordinate: () => this.y ?? undefined,
      text: () => this.label,
      textColor: () => this.face.text,
      backColor: () => this.face.back,
      visible: () => this.y !== null,
    },
  ];

  constructor(face: { back: string; text: string }) {
    this.face = face;
  }

  attached({ requestUpdate }: SeriesAttachedParameter<Time>) {
    this.request = requestUpdate;
  }

  detached() {
    this.request = null;
  }

  priceAxisViews() {
    return this.views;
  }

  show(y: number, label: string) {
    if (this.y === y && this.label === label) return;
    this.y = y;
    this.label = label;
    this.request?.();
  }

  hide() {
    if (this.y === null) return;
    this.y = null;
    this.request?.();
  }

  setFace(face: { back: string; text: string }) {
    this.face = face;
    this.request?.();
  }
}

/** 축과 십자선의 시각. 관리 콘솔 시각은 KST다(디자인 시스템 §12-12) */
function clock(t: Time | number): string {
  return hms(new Date((t as number) * 1000).toISOString());
}

/* 표식 폭은 캔버스로 잰다. DOM 에 한 번 그린 뒤 재면 매초 두 번 렌더된다. 글꼴이 다 받아지기
   전에 잰 폭은 대체 글꼴 기준이라 캐시하지 않는다 */
let measureCtx: CanvasRenderingContext2D | null | undefined;
const widthCache = new Map<string, number>();

function tagWidth(text: string): number {
  const hit = widthCache.get(text);
  if (hit !== undefined) return hit;
  if (measureCtx === undefined) {
    measureCtx = document.createElement("canvas").getContext("2d");
  }
  let w: number;
  if (measureCtx) {
    const weight = cssVar("--font-micro-weight") || "500";
    const size = cssVar("--font-micro-size") || "11px";
    measureCtx.font = `${weight} ${size} ${cssVar("--font-sans") || "sans-serif"}`;
    w = Math.ceil(measureCtx.measureText(text).width) + TAG_CHROME_PX;
  } else {
    // 캔버스가 없으면 micro 글꼴에서 한글 12px, 그 밖 7px 로 넉넉히 잡는다
    w = TAG_CHROME_PX;
    for (const ch of text) w += /[가-힣]/.test(ch) ? 12 : 7;
  }
  if (typeof document.fonts === "undefined" || document.fonts.status === "loaded") widthCache.set(text, w);
  return w;
}

interface Tag extends Placed {
  left: number;
  row: number;
}

/**
 * 표식을 띠의 줄에 나눠 놓는다. 한 사건마다 세로선 가운데, 선 오른쪽, 선 왼쪽 순서로 놓아 보고
 * 앞 표식과 겹치지 않는 가장 낮은 번호의 줄(차트에 가장 가까운 줄)을 쓴다. 가운데만 고집하면
 * 20초 안에 사건 셋이 이어질 때 네 줄로도 모자란다. 끝내 자리가 없으면 가장 먼저 비는 줄에
 * 가운데로 겹쳐 둔다.
 */
function packTags(marks: Placed[], plotW: number): Tag[] {
  const rowEnd = new Array<number>(LANE_ROWS).fill(-Infinity);
  const out: Tag[] = [];
  for (const m of marks) {
    const w = tagWidth(`${HEALTH_ICON[m.event.health]} ${m.event.label}`);
    // 그림 영역 밖으로 나가지 않게 민다. 표식이 영역보다 넓으면 왼쪽 끝에 붙인다
    const clamp = (l: number) => Math.min(Math.max(l, 0), Math.max(0, plotW - w));
    const candidates = [clamp(m.x - w / 2), clamp(m.x), clamp(m.x - w)];
    let row = -1;
    let left = candidates[0];
    for (let r = 0; r < LANE_ROWS && row < 0; r += 1) {
      const fit = candidates.find((c) => c > rowEnd[r] + TAG_GAP_PX);
      if (fit !== undefined) {
        row = r;
        left = fit;
      }
    }
    if (row < 0) row = rowEnd.indexOf(Math.min(...rowEnd));
    rowEnd[row] = left + w;
    out.push({ ...m, left, row });
  }
  return out;
}

/**
 * 차트 하나를 만들고 표본을 싣고 사건 좌표를 잰다. 카드 여섯 장이 같은 규칙을 따라야 시각이
 * 맞으므로 한 곳에 모았다. spec 은 모듈 상수라 차트는 마운트 때 한 번만 만든다.
 */
function useFlowChart(spec: ChartSpec, samples: FlowSample[], events: FlowEvent[], origin: number) {
  const hostRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Line">[]>([]);
  /** 기준선마다 선 값이 기준 이상인지(over). 넘나들 때만 축 표지 색을 다시 준다 */
  const refLinesRef = useRef<{ line: IPriceLine; tone: RefTone; spec: RefSpec; over: boolean }[]>([]);
  /** 창마다 하나씩 둔 십자선 가격 표지. 색인이 창 번호다 */
  const crossRef = useRef<CrossLabel[]>([]);
  /** 선마다 마지막 값과 그 표지를 켰는지. 십자선 표지와 눈금 비우기가 쉬는 표지의 자리를 계산할 때 읽는다 */
  const lastRef = useRef<{ value: number | null; shown: boolean }[]>([]);
  /** 쉬는 표지가 움직여 비울 눈금이 바뀌었으면 축 눈금을 다시 짓게 한다. 차트를 만들 때 채운다 */
  const tickRefreshRef = useRef<(() => void) | null>(null);
  // 크기 변경 콜백은 차트가 부른다. 그때도 최신 표본·사건을 읽도록 ref 로 둔다
  const eventsRef = useRef<FlowEvent[]>([]);
  const samplesRef = useRef<FlowSample[]>([]);
  const originRef = useRef(origin);
  const [frame, setFrame] = useState<Frame>(EMPTY_FRAME);

  // 세로선과 창 이름표 위치는 캔버스 좌표에서 나온다. 창이 움직이거나 크기가 바뀔 때마다 다시 잰다
  const measure = useCallback(() => {
    const chart = chartRef.current;
    if (!chart) return;
    const ts = chart.timeScale();
    const plotW = ts.width();
    const first = samplesRef.current[0]?.at;
    const marks: Placed[] = [];
    eventsRef.current.forEach((event, i) => {
      let x = ts.timeToCoordinate((originRef.current + event.at) as UTCTimestamp);
      // 첫 표본보다 앞선 틱(재생 직후 틱 0)의 사건은 시간축에 그 시각이 없다. 표본이 1초마다
      // 하나씩 이어지므로 논리 색인으로 좌표를 구한다
      if (x === null && first !== undefined) x = ts.logicalToCoordinate((event.at - first) as Logical);
      if (x === null || x < 0 || x > plotW) return;
      marks.push({ key: `${event.at}-${i}`, x, event });
    });
    let top = 0;
    const paneTops = chart.panes().map((p) => {
      const cur = top;
      top += p.getHeight() + 1;
      return cur;
    });
    const next: Frame = { plotW, axisH: ts.height(), paneTops, marks };
    setFrame((prev) => (sameFrame(prev, next) ? prev : next));
  }, []);

  const applyTheme = useCallback(() => {
    const chart = chartRef.current;
    if (!chart) return;
    const t = readChartTokens();
    chart.applyOptions({
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: t.axisText,
        fontFamily: t.font,
        panes: { separatorColor: t.grid, separatorHoverColor: t.grid },
      },
      grid: { vertLines: { color: t.grid }, horzLines: { color: t.grid } },
      rightPriceScale: { borderColor: t.grid },
      timeScale: { borderColor: t.grid },
      crosshair: crosshairOptions(t),
    });
    seriesRef.current.forEach((line, i) => line.applyOptions({ color: toneColor(t, spec.lines[i].tone) }));
    refLinesRef.current.forEach(({ line, tone, over }) => line.applyOptions(refFace(t, tone, over)));
    crossRef.current.forEach((label) => label.setFace(crossFace()));
  }, [spec]);

  useChartTheme(applyTheme);

  useEffect(() => {
    const el = hostRef.current;
    if (!el) return;
    const t = readChartTokens();
    const chart = createChart(el, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: t.axisText,
        fontFamily: t.font,
        attributionLogo: false,
        panes: { separatorColor: t.grid, separatorHoverColor: t.grid, enableResize: false },
      },
      grid: { vertLines: { color: t.grid }, horzLines: { color: t.grid } },
      // 축 폭을 카드끼리 맞춘다. 눈금 글자 길이에 따라 폭이 달라지면 두 열의 시각이 어긋난다
      rightPriceScale: { borderColor: t.grid, minimumWidth: spec.scaleWidth },
      timeScale: {
        borderColor: t.grid,
        timeVisible: true,
        secondsVisible: true,
        tickMarkFormatter: (time: Time) => clock(time),
      },
      localization: { locale: "ko-KR", timeFormatter: (time: Time) => clock(time) },
      crosshair: crosshairOptions(t),
      handleScroll: false,
      handleScale: false,
    });
    chartRef.current = chart;

    // 아래에 깔 선을 먼저 만든다. 나중에 만든 선이 위에 그려진다
    const order = spec.lines.map((_, i) => i).sort((a, b) => Number(!!spec.lines[b].under) - Number(!!spec.lines[a].under));
    // 창마다 그 창에 든 선의 색인. 만든 순서라 첫 원소가 그 창의 축을 소유하는 선이다
    const inPane = spec.scales.map((_, pane) => order.filter((i) => spec.lines[i].pane === pane));
    const series: ISeriesApi<"Line">[] = new Array(spec.lines.length);

    /**
     * 창의 쉬는 표지(지금 값, 기준선)가 축에 실제로 서는 높이. 십자선 표지가 비킬 자리와 비울 눈금을
     * 이 값으로 고른다. 표지는 라이브러리가 모으는 순서대로 적는다. 선을 만든 순서, 한 선 안에서는
     * 지금 값 다음 기준선
     */
    const restingTags = (pane: number): number[] => {
      const idx = inPane[pane];
      const anchor = series[idx[0]];
      if (anchor === undefined) return [];
      const raw: number[] = [];
      for (const i of idx) {
        const last = lastRef.current[i];
        const y = last?.shown && last.value !== null ? series[i].priceToCoordinate(last.value) : null;
        if (y !== null) raw.push(y);
        for (const r of spec.refs) {
          const ry = r.line === i ? series[i].priceToCoordinate(r.price) : null;
          if (ry !== null) raw.push(ry);
        }
      }
      const paneH = chart.panes()[pane]?.getHeight() ?? 0;
      // 정렬의 가운데는 창 첫 선의 마지막 값이다. 표지를 내린 선이어도 라이브러리는 그 값의 높이를 쓴다
      const firstLast = lastRef.current[idx[0]]?.value ?? null;
      const center = (firstLast === null ? null : anchor.priceToCoordinate(firstLast)) ?? paneH / 2;
      return settleTags(raw, center, paneH);
    };

    /**
     * 눈금마다 쉬는 표지에 가려 글자를 비울지. TICK_CLEAR_PX 안에 표지 가운데가 있으면 비운다.
     * held 는 지난번에 비운 눈금이다. 그 눈금은 TICK_HOLD_PX 만큼 더 멀어져야 다시 적는다
     */
    const hiddenTicks = (pane: number, prices: readonly number[], held: ReadonlySet<number>): boolean[] => {
      const anchor = series[inPane[pane][0]];
      const tags = restingTags(pane);
      return prices.map((p) => {
        const y = anchor?.priceToCoordinate(p) ?? null;
        const clear = held.has(tickKey(p)) ? TICK_CLEAR_PX + TICK_HOLD_PX : TICK_CLEAR_PX;
        return y !== null && tags.some((tag) => Math.abs(tag - y) < clear);
      });
    };

    // 창마다 라이브러리가 마지막으로 지은 눈금 값과 그때 비운 눈금. 라이브러리는 축 범위나 높이가 바뀔
    // 때만 눈금을 다시 짓는다. 범위를 고정한 창은 표본이 들어와도 다시 짓지 않으므로, 표지가 움직여 비울
    // 눈금이 달라졌는지를 이 기록과 대 보고 직접 다시 짓게 한다(tickRefreshRef)
    const ticksBuilt: { prices: number[]; held: Set<number>; sig: string }[] = [];
    const noneHeld: ReadonlySet<number> = new Set();
    const tickLabels = (pane: number, prices: readonly number[], format: (p: number) => string): string[] => {
      const hidden = hiddenTicks(pane, prices, ticksBuilt[pane]?.held ?? noneHeld);
      ticksBuilt[pane] = {
        prices: [...prices],
        held: new Set(prices.filter((_, i) => hidden[i]).map(tickKey)),
        sig: hidden.join(),
      };
      return prices.map((p, i) => (hidden[i] ? "" : format(p)));
    };
    tickRefreshRef.current = () => {
      inPane.forEach((idx, pane) => {
        const built = ticksBuilt[pane];
        if (built === undefined || hiddenTicks(pane, built.prices, built.held).join() === built.sig) return;
        // 빈 옵션도 축의 눈금 캐시를 비우고 다시 그리게 한다. 비울 눈금이 바뀔 때만 오므로 드물다
        series[idx[0]].priceScale().applyOptions({});
      });
    };

    for (const i of order) {
      const l = spec.lines[i];
      const scale = spec.scales[l.pane];
      const minMove = 10 ** -l.axisDigits;
      const format = (p: number) => `${p.toFixed(l.axisDigits)}${l.unit === "%" ? "%" : ""}`;
      const common = {
        lineWidth: l.width,
        lineStyle: LINE_STYLE[l.dash],
        lineType: l.steps ? LineType.WithSteps : LineType.Simple,
        lastValueVisible: true,
        priceLineVisible: false,
        priceFormat: {
          type: "custom" as const,
          formatter: format,
          // 눈금 글자만 이 규칙을 따른다. 표지와 십자선 값은 formatter 가 그대로 적는다
          tickmarksFormatter: (prices: number[]) => tickLabels(l.pane, prices, format),
          minMove,
        },
        autoscaleInfoProvider:
          scale.kind === "fixed"
            ? () => ({ priceRange: { minValue: scale.min, maxValue: scale.max } })
            : (base: () => AutoscaleInfo | null): AutoscaleInfo | null => {
                const r = base();
                // 창 안 값이 전부 공백이면 base 가 범위를 주지 않는다. 그때도 축을 0..최소 천장으로 둔다
                return { ...r, priceRange: { minValue: 0, maxValue: Math.max(r?.priceRange?.maxValue ?? 0, scale.minCeil) } };
              },
      };
      const line = chart.addSeries(LineSeries, { ...common, color: toneColor(t, l.tone) }, l.pane);
      if (scale.kind === "fixed") line.priceScale().applyOptions({ scaleMargins: { top: scale.top, bottom: 0.04 } });
      series[i] = line;
    }
    seriesRef.current = series;

    refLinesRef.current = spec.refs.map((r) => ({
      tone: r.tone,
      spec: r,
      over: false,
      line: series[r.line].createPriceLine({
        price: r.price,
        ...refFace(t, r.tone, false),
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: r.title,
      }),
    }));

    // 창마다 십자선 가격 표지 하나를 그 창의 첫 선에 단다(CrossLabel)
    crossRef.current = inPane.map((idx) => {
      const label = new CrossLabel(crossFace());
      series[idx[0]].attachPrimitive(label);
      return label;
    });

    // 십자선이 움직이면 그 창의 표지를 십자선 가로선의 값으로 세운다. 가로선은 자석 방식이라 마우스 높이에서
    // 가장 가까운 선의 그 시각 값에 붙고, 그 시각에 값이 없으면 마우스 높이에 선다. 표지가 선과 어긋나지
    // 않게 같은 규칙으로 값을 고른다. 표본이 들어와 창이 밀릴 때도 라이브러리가 이 이벤트를 다시 내므로
    // 멈춘 마우스 아래에서도 표지가 선을 따라간다
    const onCrosshair = (p: MouseEventParams<Time>) => {
      const pane = p.point !== undefined ? p.paneIndex : undefined;
      crossRef.current.forEach((label, i) => {
        if (i !== pane) label.hide();
      });
      const label = pane === undefined ? undefined : crossRef.current[pane];
      if (p.point === undefined || pane === undefined || label === undefined) return;
      const idx = inPane[pane];
      const anchor = series[idx[0]];
      let want: number | null = null;
      let price: number | null = null;
      for (const i of idx) {
        const d = p.seriesData.get(series[i]);
        const v = d !== undefined && "value" in d ? d.value : undefined;
        const y = v === undefined ? null : series[i].priceToCoordinate(v);
        if (v === undefined || y === null) continue;
        if (want === null || Math.abs(y - p.point.y) < Math.abs(want - p.point.y)) {
          want = y;
          price = v;
        }
      }
      if (want === null || price === null) {
        want = p.point.y;
        price = anchor.coordinateToPrice(want);
      }
      if (price === null) {
        label.hide();
        return;
      }
      const paneH = chart.panes()[pane]?.getHeight() ?? 0;
      const y = crossSlot(want, restingTags(pane), paneH);
      if (y === null) label.hide();
      else label.show(y, anchor.priceFormatter().format(price));
    };
    chart.subscribeCrosshairMove(onCrosshair);

    if (spec.stretch) {
      const panes = chart.panes();
      spec.stretch.forEach((f, i) => panes[i]?.setStretchFactor(f));
    }

    const ts = chart.timeScale();
    ts.subscribeVisibleLogicalRangeChange(measure);
    ts.subscribeSizeChange(measure);
    return () => {
      chart.unsubscribeCrosshairMove(onCrosshair);
      ts.unsubscribeVisibleLogicalRangeChange(measure);
      ts.unsubscribeSizeChange(measure);
      chart.remove();
      chartRef.current = null;
      seriesRef.current = [];
      refLinesRef.current = [];
      crossRef.current = [];
      lastRef.current = [];
      tickRefreshRef.current = null;
    };
  }, [spec, measure]);

  // 매초 줄을 통째로 싣는다. 끊김 표시는 공백이 온 뒤에야 앞 점에 달 수 있어서, 끝 점만
  // 고치는 update 로는 이미 그린 점을 되돌릴 수 없다. 600점 세 줄은 초당 한 번 싣기에 가볍다
  useEffect(() => {
    samplesRef.current = samples;
    originRef.current = origin;
    const chart = chartRef.current;
    const series = seriesRef.current;
    if (!chart || series.length === 0) return;
    const last = samples.length > 0 ? samples[samples.length - 1] : undefined;
    series.forEach((line, i) => {
      const { pick, every = 1 } = spec.lines[i];
      // 간격을 둔 선은 남길 틱의 표본만으로 공백을 판정한다. 건너뛴 틱까지 넣으면 모든 점이 공백
      // 직전으로 잡혀 선 전체가 투명해진다
      const kept = every > 1 ? samples.filter((s) => s.at % every === 0) : samples;
      const byTick = new Map(linePoints(kept, pick).map((p) => [p.at, p]));
      // 선 계열은 공백을 가로질러 직선을 긋는다. 한 구간의 색은 앞 점에서 오므로 공백 직전 점을
      // 투명하게 칠해 그 구간만 지운다. 건너뛴 틱도 빈 점으로 싣는다. 빼면 이 차트만 논리 색인이
      // 절반이 되어 창 폭과 사건선 자리가 다른 카드와 어긋난다
      const data: Point[] = samples.map((s) => {
        const time = (origin + s.at) as UTCTimestamp;
        const p = byTick.get(s.at);
        if (p === undefined || p.value === null) return { time };
        return p.cut ? { time, value: p.value, color: "transparent" } : { time, value: p.value };
      });
      // 지금 값이 없으면 축의 마지막 값 표식도 내린다. 공백 전 값이 현재 값처럼 남는다. 간격을 둔 선은
      // 마지막으로 이은 점이 지금 값과 다를 수 있어 표식을 늘 내리고 지금 값은 판독값에만 맡긴다
      const shown = every === 1 && last !== undefined && pick(last) !== null;
      // 십자선 표지가 읽을 값은 싣기 전에 적는다. 싣는 동안 라이브러리가 십자선 이벤트를 다시 낸다
      let lastValue: number | null = null;
      for (let k = data.length - 1; k >= 0 && lastValue === null; k -= 1) {
        const pt = data[k];
        if ("value" in pt) lastValue = pt.value;
      }
      lastRef.current[i] = { value: lastValue, shown };
      line.setData(data);
      line.applyOptions({ lastValueVisible: shown });
    });
    // 기준선 표지는 마지막 표본의 값이 기준 이상인 동안만 경고 면이다. 넘나드는 순간에만 색을 다시 준다
    let refTokens: ChartTokens | null = null;
    for (const ref of refLinesRef.current) {
      const v = last !== undefined ? spec.lines[ref.spec.line].pick(last) : null;
      const over = v !== null && v >= ref.spec.price;
      if (over === ref.over) continue;
      ref.over = over;
      refTokens ??= readChartTokens();
      ref.line.applyOptions(refFace(refTokens, ref.tone, over));
    }
    // 창 폭을 고정한다. 표본이 150개가 안 되면 왼쪽이 비고 오른쪽 끝이 늘 지금이다
    const n = samples.length;
    if (n > 0) chart.timeScale().setVisibleLogicalRange({ from: n - WINDOW, to: n + 1 });
    // 지금 값 표지가 움직였으니 그 밑의 눈금을 다시 고른다
    tickRefreshRef.current?.();
    measure();
  }, [samples, origin, spec, measure]);

  // 사건만 새로 생긴 경우(재생 시작 버튼)에도 표식을 바로 다시 놓는다
  useEffect(() => {
    eventsRef.current = events;
    measure();
  }, [events, measure]);

  return { hostRef, frame };
}

function Swatch({ tone, dash, width }: { tone: Tone | RefTone; dash: Dash; width: number }) {
  return (
    <svg className={styles.swatch} data-tone={tone} width="20" height="8" viewBox="0 0 20 8" aria-hidden="true">
      <line
        x1="1"
        y1="4"
        x2="19"
        y2="4"
        stroke="currentColor"
        strokeWidth={width}
        strokeLinecap={dash === "dotted" ? "round" : "butt"}
        strokeDasharray={DASH_ARRAY[dash]}
      />
    </svg>
  );
}

/** 선마다 이름과 지금 값. 값은 마지막 표본에서 읽고 원천이 없으면 미수집으로 적는다 */
function Readouts({ lines, last }: { lines: LineSpec[]; last: FlowSample | undefined }) {
  return (
    <dl className={styles.readouts}>
      {lines.map((l) => {
        const v = last ? l.pick(last) : null;
        return (
          <div key={l.name} className={styles.readout}>
            <dt className={styles.readoutName}>
              <Swatch tone={l.tone} dash={l.dash} width={l.width} />
              {l.name}
            </dt>
            <dd className={styles.readoutValue}>
              {v === null ? (
                <span className={styles.missing}>
                  <span aria-hidden="true"><StatusShape kind="dashed" /> </span>
                  미수집
                </span>
              ) : (
                <>
                  {v.toFixed(l.digits)}
                  {l.unit && <span className={styles.unit}>{l.unit}</span>}
                </>
              )}
            </dd>
          </div>
        );
      })}
    </dl>
  );
}

function RefLegend({ refs }: { refs: RefSpec[] }) {
  return (
    <span className={styles.legend}>
      {refs.map((r) => (
        <span key={r.title} className={styles.legendItem}>
          <Swatch tone={r.tone} dash="dashed" width={1.5} />
          {r.legend}
        </span>
      ))}
    </span>
  );
}

/* 사건 형태 범례. 색은 보조라 형태와 낱말을 같이 적는다 */
function ShapeLegend() {
  return (
    <span className={styles.legend}>
      {(["success", "warning", "danger"] as const).map((h) => (
        <span key={h} className={styles.legendItem}>
          <span className={styles.glyph} data-health={h} aria-hidden="true">
            {HEALTH_ICON[h]}
          </span>
          {HEALTH_WORD[h]}
        </span>
      ))}
    </span>
  );
}

interface Props {
  samples: FlowSample[];
  events: FlowEvent[];
  /** 틱 0 의 벽시계 초. 틱을 그대로 시간축에 올리면 1970년 눈금이 찍힌다 */
  origin: number;
}

function TimelineCard({ samples, events, origin }: Props) {
  const { hostRef, frame } = useFlowChart(TIMELINE, samples, events, origin);
  const tags = useMemo(() => packTags(frame.marks, frame.plotW), [frame.marks, frame.plotW]);
  const last = samples.length > 0 ? samples[samples.length - 1] : undefined;
  const paneNames = TIMELINE.lines.map((l) => l.name);

  return (
    <PanelCard title={TIMELINE.title} actions={<ShapeLegend />}>
      <Readouts lines={TIMELINE.lines} last={last} />
      <div className={styles.timeline} style={{ paddingTop: LANE_PX }}>
        {/* 사건 띠. 그림 영역 폭만큼 옅은 면을 깔고 이름은 오른쪽 축 칸에 둔다. 축 칸은 표식이 들어가지
            않는 자리라 이름이 표식과 겹치지 않는다. 비어 있을 때는 빈 띠가 아니라 사건 없음으로 읽힌다 */}
        <div
          className={styles.lane}
          style={{ height: LANE_BAND_PX, width: frame.plotW > 0 ? frame.plotW : undefined }}
          aria-hidden="true"
        >
          {tags.length === 0 && <span className={styles.laneEmpty}>사건 없음</span>}
        </div>
        {frame.plotW > 0 && (
          <span className={styles.laneLabel} style={{ left: frame.plotW, height: LANE_BAND_PX }} aria-hidden="true">
            사건
          </span>
        )}
        <div className={`${styles.chartBody} ${styles.timelineBody}`}>
          <div className={styles.chartHost} ref={hostRef} aria-hidden="true" />
          {frame.paneTops.map((top, i) => (
            <span key={paneNames[i] ?? i} className={styles.paneLabel} style={{ top: top + 4 }} aria-hidden="true">
              {paneNames[i]}
            </span>
          ))}
        </div>
        {/* 세로선을 먼저 모두 그리고 표식을 뒤에 그린다. 위 줄 사건의 선이 아래 줄 표식을 지나도
            표식이 선을 덮어 글자가 가려지지 않는다 */}
        <div className={styles.eventLayer} aria-hidden="true">
          {tags.map((t) => (
            <span
              key={`line-${t.key}`}
              className={styles.eventLine}
              data-health={t.event.health}
              style={{ left: t.x, top: rowTop(t.row) + LANE_ROW_PX, bottom: frame.axisH }}
            />
          ))}
          {tags.map((t) => (
            <span
              key={`tag-${t.key}`}
              className={styles.eventTag}
              data-health={t.event.health}
              style={{ left: t.left, top: rowTop(t.row) }}
            >
              <span className={styles.glyph} data-health={t.event.health}>
                {HEALTH_ICON[t.event.health]}
              </span>
              {t.event.label}
            </span>
          ))}
        </div>
      </div>
      {/* 캔버스와 표식 층은 보조기술에 닿지 않는다. 사건 목록은 페이지가 시각과 함께 표 하나로
          싣는다(monitor/page.tsx). 여기서 창 안 사건을 한 번 더 적으면 같은 사건을 두 번 읽는다 */}
    </PanelCard>
  );
}

function MetricCard({ spec, samples, events, origin }: Props & { spec: ChartSpec }) {
  const { hostRef, frame } = useFlowChart(spec, samples, events, origin);
  const last = samples.length > 0 ? samples[samples.length - 1] : undefined;

  return (
    <PanelCard
      dense
      className={spec.span === 3 ? styles.span3 : styles.span2}
      title={spec.title}
      actions={spec.refs.length > 0 ? <RefLegend refs={spec.refs} /> : undefined}
    >
      <Readouts lines={spec.lines} last={last} />
      <div className={`${styles.chartBody} ${styles.cardBody}`}>
        <div className={styles.chartHost} ref={hostRef} aria-hidden="true" />
        <div className={styles.faintLayer} aria-hidden="true">
          {frame.marks.map((m) => (
            <span key={m.key} className={styles.faintLine} style={{ left: m.x, bottom: frame.axisH }} />
          ))}
        </div>
      </div>
    </PanelCard>
  );
}

export default function FlowMetrics({ samples, events, origin, basis }: Props & { basis: MetricsBasis }) {
  // 기준이 바뀔 때만 새 사양을 만든다. 사양이 바뀌면 차트를 새로 세운다(useFlowChart)
  const { live, scaleOutPct } = basis;
  const cards = useMemo(() => cardsFor({ live, scaleOutPct }), [live, scaleOutPct]);
  return (
    <div className={styles.root}>
      <TimelineCard samples={samples} events={events} origin={origin} />
      <div className={styles.grid}>
        {cards.map((spec) => (
          <MetricCard key={spec.title} spec={spec} samples={samples} events={events} origin={origin} />
        ))}
      </div>
      <p className={styles.caption}>{basis.live ? "최근 150초 · 관제 판 값 · 값 없는 구간은 공백" : "최근 150초 · 재생 · 사건 사이 값은 지어낸 값 · 값 없는 구간은 공백"}</p>
    </div>
  );
}
