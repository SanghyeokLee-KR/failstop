/**
 * 관제 판의 상태 판정. 호스트 카드, 사이드바 배지, 운영자 바의 전체 상태, AI 상담의 답이 같은 규칙을
 * 써야 화면마다 다른 말을 하지 않는다. 상태 어휘와 형태는 디자인 시스템 §12-3을 따른다.
 */

import type { ControlCenter, OpsHost, OpsHostRole, OpsSection, OpsTrading, ReadNodeState, ThresholdKey, ThresholdRegistry, WriteState } from "./types";

export interface NodeState {
  tone: "success" | "warning" | "danger" | "off";
  shape: string;
  word: string;
}

export function thresholdOf(reg: ThresholdRegistry | null, key: ThresholdKey): number | null {
  return reg?.items.find((t) => t.key === key)?.value ?? null;
}

/** 호스트 한 대의 판정. 게이지 가운데 가장 나쁜 쪽을 따른다 */
export function hostTone(h: OpsHost, reg: ThresholdRegistry | null): "warning" | "danger" | undefined {
  if (h.collect === "STALE") return "warning";
  const cpu = thresholdOf(reg, "host.cpu_alert");
  const mem = thresholdOf(reg, "host.mem_alert");
  const free = thresholdOf(reg, "host.disk_free_alert");
  const diskFree = h.diskUsedPct === null ? null : 100 - h.diskUsedPct;
  if (free !== null && diskFree !== null && diskFree < free / 2) return "danger";
  if (cpu !== null && h.cpuPct !== null && h.cpuPct >= cpu) return "warning";
  if (mem !== null && h.memPct !== null && h.memPct >= mem) return "warning";
  if (free !== null && diskFree !== null && diskFree < free) return "warning";
  return undefined;
}

/**
 * 앱 계층(app-1·app-2와 켜진 app-3)의 평균 CPU가 증설 임계 이상인가. 조회는 켜진 앱 노드에 고르게 나뉘므로 이 부하는
 * 한 노드의 임계치 초과가 아니라 앱 노드 모두의 주의다(ADR 0030, 사용자 정정 2026-09-25)
 */
export function appTierHot(c: ControlCenter): boolean {
  if (c.readTier.state !== "READY") return false;
  const reg = c.thresholds.state === "READY" ? c.thresholds.data : null;
  const out = thresholdOf(reg, "read.scale_out_cpu");
  return c.readTier.data.avgCpuPct !== null && out !== null && c.readTier.data.avgCpuPct >= out;
}

/**
 * 카드의 상태 문구. 운영 중, 임계치 초과, 수집 끊김 세 가지로 적는다. 앱 계층이 과부하면(tierHot) 앱 노드는 제 게이지가
 * 임계 안이어도 '조회 부하 높음' 주의다. 헤더 집계·사이드바 배지·카드가 같은 판정을 쓴다
 */
export function hostState(h: OpsHost, reg: ThresholdRegistry | null, tierHot = false): NodeState {
  if (h.collect === "MISSING") return { tone: "off", shape: "◌", word: "수집 끊김" };
  if (h.collect === "STALE") return { tone: "warning", shape: "▲", word: "수집 끊김" };
  const t = hostTone(h, reg);
  if (t === "danger") return { tone: "danger", shape: "■", word: "임계치 초과" };
  if (t === "warning") return { tone: "warning", shape: "▲", word: "임계치 초과" };
  if (tierHot && h.role === "APP") return { tone: "warning", shape: "▲", word: "조회 부하 높음" };
  return { tone: "success", shape: "●", word: "운영 중" };
}

export type OverallLevel = "ok" | "warn" | "down" | "none" | "loading";

export interface Overall {
  level: OverallLevel;
  alerts: number;
  /** 임계를 넘었거나 멈춘 노드 수 */
  nodes: number;
}

/** 전체 상태. 경보는 보류 중이어도 세고(문제가 남아 있다), 정보 등급 경보는 단계를 올리지 않는다 */
export function overallOf(c: ControlCenter | null): Overall {
  if (c === null) return { level: "loading", alerts: 0, nodes: 0 };
  const sections = [c.alerts, c.hosts, c.readTier, c.replication];
  if (sections.every((s) => s.state === "NOT_CONNECTED")) return { level: "none", alerts: 0, nodes: 0 };
  const reg = c.thresholds.state === "READY" ? c.thresholds.data : null;
  let down = false;
  let warn = false;
  let nodes = 0;
  const alerts = c.alerts.state === "READY" ? c.alerts.data : [];
  if (alerts.some((a) => a.severity === "critical")) down = true;
  if (alerts.some((a) => a.severity === "warning")) warn = true;
  const hot = appTierHot(c);
  if (c.hosts.state === "READY") {
    // 수집이 끊긴 노드는 상태를 모르는 것이라 정상으로 세지 않고 주의로 올린다
    for (const h of c.hosts.data) {
      const t = hostState(h, reg, hot).tone;
      if (t === "success") continue;
      nodes += 1;
      if (t === "danger") down = true;
      else warn = true;
    }
  }
  // 앱 계층 과부하는 위에서 앱 노드의 주의로 셌다. 켜진 app-3도 같은 부하를 받고, 기동·드레이닝 중인 app-3는 넘어가는
  // 중이라 주의다. 인프라 칸 집계(infraCounts)와 사이드바 배지가 같은 규칙으로 센다
  if (c.readTier.state === "READY") {
    const tier = c.readTier.data;
    if (tier.serving === 0) {
      down = true;
      nodes += 1;
    }
    for (const n of tier.nodes) {
      if (n.state === "OFF" || (n.state === "IN_SERVICE" && !hot)) continue;
      warn = true;
      nodes += 1;
    }
  }
  if (c.replication.state === "READY") {
    if (c.replication.data.stream === "STOPPED") down = true;
    else if (c.replication.data.stream === "CATCHUP") warn = true;
  }
  // 구축 전이라 연결을 가정한 ai-1은 인프라 집계와 같이 세지 않는다
  if (c.aiServer.state === "READY" && !c.aiServer.data.assumed && c.aiServer.data.link === "DOWN") {
    warn = true;
    nodes += 1;
  }
  return { level: down ? "down" : warn ? "warn" : "ok", alerts: alerts.length, nodes };
}

/**
 * 인프라 칸 머리의 집계. 켜 둔 EC2 노드와 켜진 app-3만 세고, 기동·드레이닝처럼 넘어가는 중인 노드는 주의로 센다.
 * 앱 계층이 과부하면 조회를 받는 앱 노드를 주의로 센다. 구축 전인 ai-1은 세지 않는다. sim이 연결을 가정해 값을 내도
 * 운영 중인 노드가 아니다(관리자 설계 4-4)
 */
export function infraCounts(c: ControlCenter): { ok: number; warn: number; down: number } | null {
  if (c.hosts.state !== "READY") return null;
  const reg = c.thresholds.state === "READY" ? c.thresholds.data : null;
  const out = { ok: 0, warn: 0, down: 0 };
  const hot = appTierHot(c);
  for (const h of c.hosts.data) {
    const s = hostState(h, reg, hot).tone;
    if (s === "success") out.ok += 1;
    else if (s === "danger") out.down += 1;
    else out.warn += 1;
  }
  if (c.readTier.state === "READY") {
    for (const n of c.readTier.data.nodes) {
      if (n.state === "OFF") continue;
      if (n.state === "IN_SERVICE" && !hot) out.ok += 1;
      else out.warn += 1;
    }
  }
  return out;
}

/** 수집 대상 한 줄. EC2 노드와 자동 증설 노드(app-3)를 같은 표에 싣는다 */
export interface CollectRow {
  id: string;
  kind: OpsHostRole | "READ";
  instanceType: string;
  az: string;
  /** 자동 증설 노드면 ASG 안의 상태. 꺼진 노드는 수집 대상이 아니다 */
  readState: ReadNodeState | null;
  /** 값을 받고 있는가. 호스트는 수집 상태, 자동 증설 노드는 CPU 판독이 있는지로 가린다 */
  ok: boolean;
  collectedAt: string | null;
  host: OpsHost | null;
}

/**
 * 수집 대상. EC2 노드 다섯 대와 켜져 있는 app-3다. 서버·노드 화면의 수집 대상 표와 설정 화면의
 * Prometheus 행이 이 함수 하나로 세어 두 화면의 수가 어긋나지 않는다. 구축 전인 ai-1은 넣지 않는다
 */
export function collectTargets(c: ControlCenter): { rows: CollectRow[]; targets: number; ok: number } {
  const rows: CollectRow[] = [];
  if (c.hosts.state === "READY") {
    for (const h of c.hosts.data) {
      rows.push({ id: h.id, kind: h.role, instanceType: h.instanceType, az: h.az, readState: null, ok: h.collect === "OK", collectedAt: h.collectedAt, host: h });
    }
  }
  if (c.readTier.state === "READY") {
    for (const n of c.readTier.data.nodes) {
      rows.push({
        id: n.id, kind: "READ", instanceType: n.instanceType, az: n.az, readState: n.state,
        ok: n.state !== "OFF" && n.cpuPct !== null, collectedAt: n.state === "OFF" ? null : c.collectedAt, host: null,
      });
    }
  }
  const live = rows.filter((r) => r.readState !== "OFF");
  return { rows, targets: live.length, ok: live.filter((r) => r.ok).length };
}

/**
 * 쓰기 상태의 낱말(관리자 설계 4-10-8). 시나리오의 관제 상태 칸과 관제센터 거래 요약 띠가 이 한 판정을 쓴다. 쓰기 실패와 거래 일시
 * 중단은 거래가 멈춘 장애라 ■이다. 평시(열림)와 원천이 싣지 않은 판은 null이라 거래 요약 띠는 아무것도 적지 않는다
 */
export function writeStateOf(trading: OpsSection<OpsTrading> | null): { state: Exclude<WriteState, "OPEN">; word: string; detail: string } | null {
  const w = trading?.state === "READY" ? trading.data.writes : undefined;
  if (w === "FAILING") return { state: w, word: "쓰기 실패", detail: "DB에 쓰지 못해 주문이 실패합니다" };
  if (w === "SUSPENDED") return { state: w, word: "거래 일시 중단", detail: "주문·취소를 거절하고 조회만 받습니다" };
  return null;
}
