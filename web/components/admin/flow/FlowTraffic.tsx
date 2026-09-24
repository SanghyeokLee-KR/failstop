"use client";

/**
 * 구성도의 연결선과 그 위를 지나는 트래픽 알갱이. 선의 색과 모양, 알갱이의 속도와 간격은 모두
 * flowSim 의 edgeFlows 가 정한 상태(흐름·유휴·꺼짐·끊김·전환 중)와 세기 단계(약·중·강)만 따른다.
 * 선에는 글자를 달지 않는다(2026-09-23 사용자 요청). 예외는 끊긴 선 가운데의 끊김 표식과 전환 중인
 * 선의 ▲ 하나다. 선 이름과 포트는 카드 팝오버와 구성도의 연결 목록이 싣는다.
 *
 * 성능을 이렇게 잡았다.
 *  - 주행은 1초마다 상태 객체를 새로 만든다. 이 층은 React.memo 로 감싸고 받는 값을 선 배치(크기가
 *    바뀌거나 DB 전환처럼 경로가 바뀔 때만 새로 만든다)와 선 상태 서명 문자열로 줄였다. 서명은 선마다
 *    상태 한 글자와 단계 한 자리라 상태나 단계가 바뀔 때만 달라진다. 매초의 다시 그리기가 여기 닿지 않는다.
 *  - 알갱이는 transform 만 움직이는 Web Animations(element.animate)다. 합성 단계에서 돌아 매 프레임의
 *    스타일 계산, 레이아웃, 페인트가 없다. requestAnimationFrame 이나 타이머로 좌표를 옮기지 않는다.
 *    CSS 애니메이션(animation-name)을 쓰지 않는 이유는 주기 끝 이벤트다. React 는 문서 뿌리에서
 *    animationiteration 을 늘 듣고 있어, CSS 애니메이션이면 레일 하나가 주기를 마칠 때마다 브라우저가
 *    그 이벤트를 내려고 메인 스레드를 깨우고 레일 전체의 스타일을 다시 계산한다(09-23 실측, 쉴 때 초당
 *    6프레임, 증설 정점 11프레임). Web Animations 는 주기 끝 이벤트가 없어 메인 스레드가 1초 틱만 돈다.
 *  - 직선 토막마다 트랙 하나를 토막 시작점에 두고 방향만큼 돌린다. 트랙은 토막 길이만큼 잘라 보이고,
 *    그 안의 레일 하나에 알갱이를 배경 무늬로 일정 간격마다 찍는다. 레일 폭은 트랙을 덮는 간격의
 *    정수배를 두 벌 이어 붙인 길이이고, 한 주기에 자기 폭의 절반만큼 앞으로 간다. 그래서 주기 끝과
 *    처음이 같은 그림이라 끊김 없이 되풀이되고, 퍼센트가 레일 자신의 폭을 가리켜 키프레임이 하나로
 *    족하다. 토막 하나에 애니메이션이 하나라 알갱이 수가 늘어도 애니메이션 수는 그대로다.
 *  - 선 하나의 모든 토막은 한 시계(경로를 따라 간 거리)를 나눠 쓴다. 토막마다 그 거리에서 트랙
 *    시작까지의 길이를 빼 재생 위치를 정하고, 같은 순간을 기준으로 시작 시각을 한 번에 적는다. 이 값은
 *    레일을 만들 때 한 번 정하는 고정 지연이다. 그래서 꺾이는 모서리에서 알갱이가 다음 토막으로 이어져
 *    나가고, 주기마다 JS 가 위치를 고치지 않는다.
 *  - 꺾이는 모서리에서는 들어오는 트랙이 모서리 앞에서, 나가는 트랙이 모서리 뒤에서 알갱이 반지름과
 *    고리만큼 떨어져 끊긴다. 알갱이는 카드 가장자리로 들어갈 때처럼 곧게 잘리며 모서리 밑으로 들어갔다가
 *    다음 토막에서 나온다. 두 트랙이 모서리에서 맞닿으면 한 알갱이가 두 트랙에 반씩 걸려 한 사분면이
 *    빈 원으로 보였고, 45도 사선으로 나누면 두 반쪽의 중심이 어긋나 고리가 원 안을 가로질렀다.
 *  - 마지막 토막의 트랙은 화살촉 앞에서 끝난다. 촉 길이에 알갱이 반지름과 고리를 더한 만큼 짧게 잘라
 *    알갱이가 촉을 덮지 않는다. 트랙만 줄이고 경로 위 거리 기준은 그대로라 알갱이 위상도 그대로다.
 *  - 세기 단계가 바뀌면 같은 애니메이션 객체의 길이만 고치고, 경로 시작에서 첫 알갱이까지의 거리를
 *    그대로 둔 채 새 속도로 잇는다. 첫 알갱이는 제자리에서 빨라지거나 느려지고, 나머지만 새 간격으로
 *    다시 선다. 다른 선의 애니메이션은 건드리지 않는다.
 *  - 주행이 멈췄을 때, 구성도가 화면 밖일 때 애니메이션을 세운다. 앞은 스토어의 멈춤 값, 뒤는
 *    IntersectionObserver 하나가 층 요소에 속성을 적고 애니메이션을 직접 세워 React 다시 그리기가 없다.
 *    모션 줄이기 설정이면 애니메이션을 아예 만들지 않는다. display:none 은 CSS 애니메이션과 달리
 *    Web Animations 를 멈추지 않는다. 선 색과 끝의 화살촉이 방향과 상태를 싣는다.
 */

import { memo, useEffect, useLayoutEffect, useMemo, useRef, type CSSProperties } from "react";
import { EDGE_IDS, type EdgeId, type EdgeKind, type EdgeTier } from "@/lib/admin/flowSim";
import { useFlowRun } from "@/lib/admin/flowRun";
import styles from "./traffic.module.css";

export type Pt = [number, number];

/** 선 하나의 배치. 점열은 흐름 방향 순서다. 화살촉은 마지막 점에 붙는다 */
export interface Wire {
  id: EdgeId;
  pts: Pt[];
  /** 끊김·전환 표식 자리. 없으면 가장 긴 토막의 가운데에 둔다 */
  cut?: Pt;
  /** 관측 수집과 조정기 제어. 요청이 지나는 선이 아니라 점선으로 한 단 낮춘다 */
  minor?: boolean;
  /** 끊김 표식이 앉을 틈이 좁다. "■ 끊김" 대신 ■ 하나만 그려 양쪽 카드와 틀에 닿지 않게 한다 */
  tight?: boolean;
}

export interface FlowTrafficProps {
  width: number;
  height: number;
  /** 배치가 바뀔 때만 새 배열이어야 한다. 매 틱 새로 만들면 memo 가 소용없다 */
  wires: Wire[];
  /** trafficSignature 의 결과 */
  sig: string;
}

/* ===== 선 상태 서명 ===== */

interface Line {
  kind: EdgeKind;
  tier: EdgeTier;
}

const KIND_CODE: Record<EdgeKind, string> = { flow: "f", idle: "i", off: "o", down: "d", transition: "t" };
const CODE_KIND: Record<string, EdgeKind> = { f: "flow", i: "idle", o: "off", d: "down", t: "transition" };

/**
 * 선 전체의 상태를 문자열 하나로 접는다. EDGE_IDS 순서로 선마다 상태 한 글자와 단계 한 자리를 잇는다.
 * 초당 건수(rate)는 넣지 않는다. 매초 조금씩 흔들리는 값이라 넣으면 서명이 매초 바뀐다
 */
export function trafficSignature(flows: Record<EdgeId, Line>): string {
  return EDGE_IDS.map((id) => KIND_CODE[flows[id].kind] + flows[id].tier).join("");
}

function decodeSignature(sig: string): Record<EdgeId, Line> {
  const out = {} as Record<EdgeId, Line>;
  EDGE_IDS.forEach((id, i) => {
    const kind = CODE_KIND[sig[i * 2]] ?? "off";
    const tier = Number(sig[i * 2 + 1]);
    out[id] = { kind, tier: tier === 1 || tier === 2 || tier === 3 ? tier : 0 };
  });
  return out;
}

/* ===== 선 모양 ===== */

const f1 = (v: number) => Math.round(v * 10) / 10;

/** 직각으로 꺾이는 점열을 모서리만 둥글린 경로로 바꾼다 */
function wirePath(pts: Pt[], r = 6): string {
  let d = `M${f1(pts[0][0])} ${f1(pts[0][1])}`;
  for (let i = 1; i < pts.length - 1; i += 1) {
    const [px, py] = pts[i - 1];
    const [cx, cy] = pts[i];
    const [nx, ny] = pts[i + 1];
    const l1 = Math.hypot(cx - px, cy - py);
    const l2 = Math.hypot(nx - cx, ny - cy);
    if (l1 === 0 || l2 === 0) continue;
    const rr = Math.min(r, l1 / 2, l2 / 2);
    const ax = cx - ((cx - px) / l1) * rr;
    const ay = cy - ((cy - py) / l1) * rr;
    const bx = cx + ((nx - cx) / l2) * rr;
    const by = cy + ((ny - cy) / l2) * rr;
    d += ` L${f1(ax)} ${f1(ay)} Q${f1(cx)} ${f1(cy)} ${f1(bx)} ${f1(by)}`;
  }
  const [ex, ey] = pts[pts.length - 1];
  return `${d} L${f1(ex)} ${f1(ey)}`;
}

/** 화살촉의 길이(px). 끝점에서 촉 밑동까지다. 알갱이 트랙도 이만큼 앞에서 멈추고, 구성도는 좁은 틈의
    끊김 표식을 촉을 뺀 구간 가운데에 앉힌다 */
export const ARROW_LEN = 6;

/** 끝점에 여는 화살촉. 점선이어도 촉은 실선으로 따로 그려 방향이 끊겨 보이지 않게 한다 */
function arrowPath(pts: Pt[]): string {
  const [ex, ey] = pts[pts.length - 1];
  const [px, py] = pts[pts.length - 2];
  const len = Math.hypot(ex - px, ey - py) || 1;
  const ux = (ex - px) / len;
  const uy = (ey - py) / len;
  const bx = ex - ux * ARROW_LEN;
  const by = ey - uy * ARROW_LEN;
  return `M${f1(bx - uy * 3.5)} ${f1(by + ux * 3.5)} L${f1(ex)} ${f1(ey)} L${f1(bx + uy * 3.5)} ${f1(by - ux * 3.5)}`;
}

/* ===== 알갱이 계획 ===== */

/**
 * 세기 단계의 속도(px/초)와 알갱이 간격(px). 강할수록 빠르고 촘촘하다. 한 지점을 지나는 알갱이는
 * 약 3.8초에 하나, 중 1.1초에 하나, 강 0.37초에 하나다. 선 굵기는 CSS 가 단계별로 주고 알갱이 지름은
 * 아래 PACKET 이 준다
 */
const PACE: Record<1 | 2 | 3, { speed: number; gap: number }> = {
  1: { speed: 32, gap: 120 },
  2: { speed: 64, gap: 72 },
  3: { speed: 120, gap: 44 },
};
/** 수집선은 느린 알갱이 하나만 돈다. 경로를 다 지나면 이만큼 쉰 뒤 다시 나온다(px) */
const MINOR_SPEED = 24;
const MINOR_REST = 60;
/**
 * 알갱이 지름(px). 단계가 오를수록 커진다. 수집선 알갱이는 점선의 점(2px)보다 두 배 넘게 커야 점선
 * 무늬와 갈린다. 트랙 높이와 모서리·화살촉 자리 계산이 같은 값을 써야 하므로 CSS 에 두지 않고 여기서
 * 흐름 묶음의 --packet 으로 내려 준다
 */
const PACKET: Record<1 | 2 | 3, number> = { 1: 6, 2: 7, 3: 8 };
const MINOR_PACKET = 5;
/** 알갱이 둘레 고리까지의 여유(px). 트랙 높이는 지름에 이 값의 두 배를 더한 값이다(traffic.module.css) */
const RING = 2;
/** 한 화면에 동시에 보일 수 있는 알갱이 상한. 넘으면 요청 선 전체의 간격을 같은 비율로 넓힌다 */
const MAX_PACKETS = 48;

interface Seg {
  x: number;
  y: number;
  len: number;
  deg: number;
  /** 경로 시작에서 이 토막 시작까지의 길이 */
  at: number;
}

interface Geo {
  wire: Wire;
  /** 점열을 적은 열쇠. 경로가 바뀌면 알갱이 묶음을 새로 붙인다 */
  key: string;
  d: string;
  arrow: string;
  segs: Seg[];
  total: number;
  cut: Pt;
}

function toGeo(wire: Wire): Geo {
  const segs: Seg[] = [];
  let at = 0;
  for (let i = 0; i < wire.pts.length - 1; i += 1) {
    const [x0, y0] = wire.pts[i];
    const [x1, y1] = wire.pts[i + 1];
    const len = Math.hypot(x1 - x0, y1 - y0);
    if (len < 0.5) continue;
    segs.push({ x: x0, y: y0, len, deg: Math.round((Math.atan2(y1 - y0, x1 - x0) * 180) / Math.PI), at });
    at += len;
  }
  const longest = segs.reduce((a, b) => (b.len > a.len ? b : a), segs[0]);
  const rad = (longest.deg * Math.PI) / 180;
  const cut: Pt = wire.cut ?? [
    longest.x + (Math.cos(rad) * longest.len) / 2,
    longest.y + (Math.sin(rad) * longest.len) / 2,
  ];
  return {
    wire,
    key: wire.pts.map((p) => `${f1(p[0])},${f1(p[1])}`).join(" "),
    d: wirePath(wire.pts),
    arrow: arrowPath(wire.pts),
    segs,
    total: at,
    cut,
  };
}

interface RailPlan {
  /** 트랙 시작점. 모서리에서 나가는 토막이면 토막 시작보다 알갱이 반지름과 고리만큼 뒤다 */
  x: number;
  y: number;
  /** 트랙 폭(보이는 길이) */
  len: number;
  deg: number;
  /** 한 주기에 레일이 가는 거리. 트랙을 덮는 가장 짧은 간격의 정수배다. 레일 폭은 그 두 배다 */
  span: number;
  /** 경로 시작에서 트랙 시작까지의 길이 */
  at: number;
}

interface StreamPlan {
  key: string;
  id: EdgeId;
  tier: EdgeTier;
  minor: boolean;
  /** 알갱이 지름(px) */
  size: number;
  /** px/초 */
  speed: number;
  /** 알갱이 간격(px). 배경 무늬 한 칸의 폭이다 */
  gap: number;
  rails: RailPlan[];
  /** 동시에 보일 수 있는 알갱이 수의 위 한계 */
  packets: number;
}

const mod = (a: number, m: number) => ((a % m) + m) % m;



function planStreams(geo: Geo[], lines: Record<EdgeId, Line>): StreamPlan[] {
  const live = geo.filter((g) => lines[g.wire.id].kind === "flow" && lines[g.wire.id].tier > 0);
  let major = 0;
  let minor = 0;
  for (const g of live) {
    if (g.wire.minor) minor += 1;
    else major += g.total / PACE[lines[g.wire.id].tier as 1 | 2 | 3].gap;
  }
  const room = Math.max(1, MAX_PACKETS - minor);
  const spread = major > room ? major / room : 1;

  return live.map((g) => {
    const tier = lines[g.wire.id].tier;
    const minorLine = g.wire.minor === true;
    // 간격은 정수 px 로 둔다. 배경 무늬 한 칸이 소수 px 면 브라우저가 칸을 반올림해 격자가 밀릴 수 있다
    const pace = minorLine
      ? { speed: MINOR_SPEED, gap: Math.ceil(g.total + MINOR_REST) }
      : { speed: PACE[tier as 1 | 2 | 3].speed, gap: Math.ceil(PACE[tier as 1 | 2 | 3].gap * spread) };
    const size = minorLine ? MINOR_PACKET : PACKET[tier as 1 | 2 | 3];
    // 알갱이 반지름과 고리. 트랙 끝이 모서리나 화살촉에서 이만큼 떨어지면 잘리는 알갱이가 거기 닿지 않는다
    const h = size / 2 + RING;
    const last = g.segs.length - 1;
    let packets = 0;
    const rails = g.segs.map((s, i): RailPlan => {
      // 트랙의 양끝(토막 시작에서 잰 거리). 모서리 쪽은 h 만큼 물리고, 화살촉이 붙는 끝은 촉 밑동에서
      // h 만큼 더 앞에서 멈춘다. 가장 짧은 토막(956px 의 DB 전환 뒤 SQL 첫 토막)도 1px 는 남긴다
      const s0 = i > 0 ? h : 0;
      const s1 = i < last ? s.len - h : s.len - ARROW_LEN - h;
      const width = Math.max(1, s1 - s0);
      const rad = (s.deg * Math.PI) / 180;
      packets += Math.ceil(width / pace.gap);
      return {
        x: s.x + Math.cos(rad) * s0,
        y: s.y + Math.sin(rad) * s0,
        len: width,
        deg: s.deg,
        // 주기 끝 이벤트가 없으므로 주기를 늘려 둘 까닭이 없다. 트랙을 덮는 가장 짧은 레일이 합성 레이어도
        // 작다. 레일 이동 거리가 간격의 정수배라 트랙 길이가 달라도 무늬의 위상은 경로 위 거리만 따른다
        span: Math.max(1, Math.ceil(width / pace.gap)) * pace.gap,
        at: s.at + s0,
      };
    });
    return {
      key: `${g.wire.id}@${g.key}`,
      id: g.wire.id,
      tier,
      minor: minorLine,
      size,
      speed: pace.speed,
      gap: pace.gap,
      rails,
      packets: minorLine ? 1 : packets,
    };
  });
}

function trackStyle(r: RailPlan): CSSProperties {
  // 시작점을 중심으로 돌리고, 트랙 높이의 절반만큼 올려 가운데 줄이 선 위에 오게 한다
  return {
    left: f1(r.x),
    top: f1(r.y),
    width: f1(r.len),
    transform: r.deg === 0 ? "translateY(-50%)" : `rotate(${r.deg}deg) translateY(-50%)`,
  };
}

/** 흐름 묶음의 알갱이 지름. 트랙 높이와 알갱이 무늬가 이 한 값을 따른다 */
function streamStyle(st: StreamPlan): CSSProperties {
  return { "--packet": `${st.size}px` } as CSSProperties;
}

function railStyle(r: RailPlan, gap: number): CSSProperties {
  return { width: r.span * 2, backgroundSize: `${gap}px 100%` };
}

/* ===== 알갱이 구동 =====
   애니메이션 객체는 React 가 그리는 값이 아니다. 레일 요소는 React 가 만들고, 구동기는 그 요소에
   element.animate 로 붙인 객체를 열쇠(선 id 와 토막 순번)로 들고 있다가 계획이 바뀐 선만 고친다.
   다른 선의 객체는 건드리지 않아 세기가 바뀐 선 말고는 합성 단계로 다시 보내는 것이 없다 */

/** 레일은 자기 폭의 절반(span)만큼 앞으로 간다. 퍼센트는 레일 자신의 폭을 가리킨다 */
const RAIL_KEYFRAMES: Keyframe[] = [{ transform: "translateX(-50%)" }, { transform: "translateX(0)" }];
const REDUCED_MOTION = "(prefers-reduced-motion: reduce)";

/**
 * 선 하나의 시계. 경로 시작에서 잰 알갱이 격자의 위치(px)를 d 로 들고, 돌고 있으면 t(타임라인 ms)부터
 * v px/초로 늘어난다. 멈춰 있으면 t 가 null 이고 d 가 그대로다
 */
interface Clock {
  d: number;
  t: number | null;
  v: number;
  g: number;
}

interface LiveRail {
  el: HTMLElement;
  anim: Animation;
  /** 지금 걸린 주기(ms). 같으면 updateTiming 을 부르지 않는다 */
  dur: number;
}

const travelled = (c: Clock, now: number) => (c.t === null ? c.d : c.d + (c.v * (now - c.t)) / 1000);

/** 애니메이션이 쓰는 문서 타임라인의 지금. 같은 작업 안에서는 값이 같아 여러 레일의 시작 시각이 한 순간에 맞는다 */
function timelineNow(): number {
  const t = typeof document !== "undefined" ? document.timeline?.currentTime : null;
  return typeof t === "number" ? t : performance.now();
}

class PacketMotor {
  private layer: HTMLElement | null = null;
  private streams: StreamPlan[] = [];
  private paused = false;
  private onscreen = true;
  private reduce = false;
  private clocks = new Map<EdgeId, Clock>();
  private rails = new Map<string, LiveRail>();

  attach(layer: HTMLElement, reduce: boolean) {
    this.layer = layer;
    this.reduce = reduce;
    this.sync();
  }

  detach() {
    this.stopAll();
    this.layer = null;
  }

  plan(streams: StreamPlan[], paused: boolean) {
    this.streams = streams;
    this.paused = paused;
    this.sync();
  }

  setOnscreen(onscreen: boolean) {
    if (onscreen === this.onscreen) return;
    this.onscreen = onscreen;
    this.sync();
  }

  setReduce(reduce: boolean) {
    if (reduce === this.reduce) return;
    this.reduce = reduce;
    this.sync();
  }

  private stopAll() {
    for (const r of this.rails.values()) r.anim.cancel();
    this.rails.clear();
    this.clocks.clear();
  }

  /** 계획과 지금 애니메이션을 맞춘다. 새 레일에만 객체를 만들고, 속도·간격이나 돌고 멈춤이 바뀐 선만 다시 놓는다 */
  private sync() {
    const layer = this.layer;
    if (!layer) return;
    if (this.reduce) {
      this.stopAll();
      return;
    }
    const els = new Map<string, HTMLElement>();
    layer.querySelectorAll<HTMLElement>("[data-rail]").forEach((el) => {
      if (el.dataset.rail) els.set(el.dataset.rail, el);
    });
    const run = !this.paused && this.onscreen;
    const now = timelineNow();
    const keptRails = new Set<string>();
    const keptClocks = new Set<EdgeId>();

    for (const st of this.streams) {
      keptClocks.add(st.id);
      const prev = this.clocks.get(st.id);
      let clock = prev;
      let moved = false;
      if (!prev || prev.v !== st.speed || prev.g !== st.gap) {
        // 새 흐름이거나 세기가 바뀌었다. 알갱이는 무늬 한 칸의 가운데(격자 위치 + 간격 절반)에 찍힌다.
        // 경로 시작에서 첫 알갱이 가운데까지의 거리를 그대로 두고 간격과 속도만 바꾼다. 그 알갱이는
        // 제자리에서 빨라지거나 느려지고, 앞뒤의 알갱이만 새 간격으로 다시 선다
        const first = prev ? mod(travelled(prev, now) + prev.g / 2, prev.g) : st.gap / 2;
        clock = { d: first - st.gap / 2, t: run ? now : null, v: st.speed, g: st.gap };
        moved = true;
      } else if ((prev.t !== null) !== run) {
        clock = { d: travelled(prev, now), t: run ? now : null, v: prev.v, g: prev.g };
        moved = true;
      }
      const c = clock as Clock;
      this.clocks.set(st.id, c);

      st.rails.forEach((r, i) => {
        const key = `${st.id}:${i}`;
        const el = els.get(key);
        if (!el) return;
        keptRails.add(key);
        let live = this.rails.get(key);
        // 경로가 바뀌면 React 가 레일 요소를 새로 만든다. 옛 요소의 객체는 버린다
        if (live && live.el !== el) {
          live.anim.cancel();
          live = undefined;
        }
        if (!live) {
          live = {
            el,
            anim: el.animate(RAIL_KEYFRAMES, { duration: 1000, iterations: Infinity, easing: "linear" }),
            dur: 1000,
          };
          this.rails.set(key, live);
          place(live, r, c, now);
        } else if (moved) {
          place(live, r, c, now);
        }
      });
    }

    for (const [key, live] of this.rails) {
      if (keptRails.has(key)) continue;
      live.anim.cancel();
      this.rails.delete(key);
    }
    for (const id of this.clocks.keys()) if (!keptClocks.has(id)) this.clocks.delete(id);
  }
}

/**
 * 레일 하나를 시계에 맞춰 놓는다. 재생 위치는 경로 위 격자 위치에서 토막 시작까지의 길이를 뺀 값이라
 * 같은 선의 토막들이 한 격자를 이룬다. 돌고 있으면 시작 시각을 적고(적으면 멈춰 있던 객체도 그 자리에서
 * 다시 돈다), 멈춰 있으면 세운 뒤 그 위치에 고정한다
 */
function place(live: LiveRail, r: RailPlan, c: Clock, now: number) {
  const dur = (r.span / c.v) * 1000;
  if (live.dur !== dur) {
    live.anim.effect?.updateTiming({ duration: dur });
    live.dur = dur;
  }
  const at = (mod(travelled(c, now) - r.at, r.span) / c.v) * 1000;
  if (c.t === null) {
    live.anim.pause();
    live.anim.currentTime = at;
  } else {
    live.anim.startTime = now - at;
  }
}

// 서버 렌더에는 레이아웃이 없어 useLayoutEffect 가 할 일이 없다. 같은 자리를 useEffect 로 받는다
const useIsoLayoutEffect = typeof window === "undefined" ? useEffect : useLayoutEffect;

/* ===== 본체 ===== */

function FlowTraffic({ width, height, wires, sig }: FlowTrafficProps) {
  const paused = useFlowRun((r) => r.paused);
  const layerRef = useRef<HTMLDivElement>(null);
  const motorRef = useRef<PacketMotor | null>(null);

  const lines = useMemo(() => decodeSignature(sig), [sig]);
  const geo = useMemo(() => wires.map(toGeo), [wires]);
  const streams = useMemo(() => planStreams(geo, lines), [geo, lines]);
  const packets = streams.reduce((n, s) => n + s.packets, 0);

  // 구동기를 층 요소에 붙인다. 화면 밖이면 IntersectionObserver 가 속성을 적고 애니메이션을 세운다.
  // 속성은 React 가 그리는 값이 아니라 다시 그려도 지워지지 않고, 스크롤마다 React 가 다시 그리지 않는다.
  // 모션 줄이기 설정이 도중에 바뀌어도 따라간다
  useIsoLayoutEffect(() => {
    const el = layerRef.current;
    if (!el) return;
    const motor = new PacketMotor();
    motorRef.current = motor;
    const mq = typeof window.matchMedia === "function" ? window.matchMedia(REDUCED_MOTION) : null;
    motor.attach(el, mq?.matches ?? false);
    const onMotion = (e: MediaQueryListEvent) => motor.setReduce(e.matches);
    mq?.addEventListener("change", onMotion);
    let io: IntersectionObserver | null = null;
    if (typeof IntersectionObserver !== "undefined") {
      io = new IntersectionObserver((entries) => {
        const on = entries[entries.length - 1].isIntersecting;
        el.dataset.onscreen = on ? "true" : "false";
        motor.setOnscreen(on);
      });
      io.observe(el);
    }
    return () => {
      io?.disconnect();
      mq?.removeEventListener("change", onMotion);
      motor.detach();
      motorRef.current = null;
    };
  }, []);

  // 계획이나 주행 멈춤이 바뀌면 구동기가 바뀐 선만 고친다. 레일 요소가 DOM 에 붙은 뒤, 페인트 전에 돈다
  useIsoLayoutEffect(() => {
    motorRef.current?.plan(streams, paused);
  }, [streams, paused]);

  return (
    <>
      <svg
        className={styles.wires}
        width={width}
        height={height}
        viewBox={`0 0 ${width} ${height}`}
        aria-hidden="true"
        focusable="false"
      >
        {geo.map((g) => {
          const line = lines[g.wire.id];
          return (
            <g
              key={g.wire.id}
              className={styles.conn}
              data-kind={line.kind}
              data-tier={line.tier}
              data-minor={g.wire.minor ? "true" : undefined}
            >
              <path className={styles.halo} d={g.d} />
              <path className={styles.wire} d={g.d} />
              <path className={styles.arrow} d={g.arrow} />
            </g>
          );
        })}
      </svg>
      <div
        ref={layerRef}
        className={styles.traffic}
        data-flow-traffic=""
        data-paused={paused ? "true" : undefined}
        data-packets={packets}
        aria-hidden="true"
      >
        {streams.map((st) => (
          <div
            key={st.key}
            className={styles.stream}
            style={streamStyle(st)}
            data-edge={st.id}
            data-tier={st.tier}
            data-minor={st.minor ? "true" : undefined}
          >
            {st.rails.map((r, i) => (
              <div key={i} className={styles.track} style={trackStyle(r)}>
                <div className={styles.rail} data-rail={`${st.id}:${i}`} style={railStyle(r, st.gap)} />
              </div>
            ))}
          </div>
        ))}
        {geo.map((g) => {
          const kind = lines[g.wire.id].kind;
          if (kind !== "down" && kind !== "transition") return null;
          const tight = kind === "down" && g.wire.tight === true;
          return (
            <span
              key={g.wire.id}
              className={styles.cut}
              data-kind={kind}
              data-tight={tight ? "true" : undefined}
              style={{ left: g.cut[0], top: g.cut[1] }}
            >
              {kind === "transition" ? "▲" : tight ? "■" : "■ 끊김"}
            </span>
          );
        })}
      </div>
    </>
  );
}

export default memo(FlowTraffic);

/** 범례의 선 견본. 구성도 선과 같은 규칙을 입혀 범례와 그림이 따로 놀지 않는다 */
export function LineSwatch({ kind, tier = 0, minor = false }: { kind: EdgeKind; tier?: EdgeTier; minor?: boolean }) {
  return (
    <svg className={styles.swatch} viewBox="0 0 26 10" focusable="false" aria-hidden="true">
      <g className={styles.conn} data-kind={kind} data-tier={tier} data-minor={minor ? "true" : undefined}>
        <path className={styles.wire} d="M1 5 H25" />
      </g>
    </svg>
  );
}

const RAMP: EdgeTier[] = [1, 2, 3];

/**
 * 범례의 흐름 세기 견본. 약·중·강 세 단계의 선을 틈 없이 이어 그려 한 줄이 오른쪽으로 갈수록 굵어진다.
 * 토막 사이를 띄우면 점선 견본(꺼짐·끊김)과 헷갈린다. 강 토막에는 구성도 선처럼 옅은 빛이 깔린다
 */
export function TierSwatch() {
  return (
    <svg className={styles.swatch} data-ramp="true" viewBox="0 0 34 10" focusable="false" aria-hidden="true">
      {RAMP.map((tier, i) => {
        const d = `M${2 + i * 10} 5 H${12 + i * 10}`;
        return (
          <g key={tier} className={styles.conn} data-kind="flow" data-tier={tier}>
            <path className={styles.halo} d={d} />
            <path className={styles.wire} d={d} />
          </g>
        );
      })}
    </svg>
  );
}

/**
 * 연결 전 자리로 가는 선(관측 → AI 운영 보고 → Teams). 흐름 선(EdgeId)이 아니라 상태 서명과 알갱이가
 * 없고 늘 꺼짐 모양(회색 점선과 화살촉)이다. 같은 경로·촉 함수와 꺼짐 규칙을 써서 구성도의 다른 꺼짐 선과
 * 한 모양으로 읽힌다
 */
export function ReserveWires({ width, height, paths }: { width: number; height: number; paths: Pt[][] }) {
  return (
    <svg
      className={styles.wires}
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      aria-hidden="true"
      focusable="false"
    >
      {paths.map((pts, i) => (
        <g key={i} className={styles.conn} data-kind="off" data-tier={0}>
          <path className={styles.wire} d={wirePath(pts)} />
          <path className={styles.arrow} d={arrowPath(pts)} />
        </g>
      ))}
    </svg>
  );
}
