/**
 * 관제 판의 상태 판정. 호스트 카드, 사이드바 배지, 운영자 바의 전체 상태, AI 상담의 답이 같은 규칙을
 * 써야 화면마다 다른 말을 하지 않는다. 상태 어휘와 형태는 디자인 시스템 §12-3을 따른다.
 */

import type { ControlCenter, OpsHost, ThresholdKey, ThresholdRegistry } from "./types";

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

/** 카드의 상태 문구. 운영 중, 임계치 초과, 수집 끊김 세 가지로 적는다 */
export function hostState(h: OpsHost, reg: ThresholdRegistry | null): NodeState {
  if (h.collect === "MISSING") return { tone: "off", shape: "◌", word: "수집 끊김" };
  if (h.collect === "STALE") return { tone: "warning", shape: "▲", word: "수집 끊김" };
  const t = hostTone(h, reg);
  if (t === "danger") return { tone: "danger", shape: "■", word: "임계치 초과" };
  if (t === "warning") return { tone: "warning", shape: "▲", word: "임계치 초과" };
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
  if (c.hosts.state === "READY") {
    // 수집이 끊긴 노드는 상태를 모르는 것이라 정상으로 세지 않고 주의로 올린다
    for (const h of c.hosts.data) {
      const t = hostState(h, reg).tone;
      if (t === "success") continue;
      nodes += 1;
      if (t === "danger") down = true;
      else warn = true;
    }
  }
  if (c.readTier.state === "READY") {
    const tier = c.readTier.data;
    const serving = tier.nodes.filter((n) => n.state === "IN_SERVICE").length;
    const out = thresholdOf(reg, "read.scale_out_cpu");
    if (tier.desired > 0 && serving === 0) {
      down = true;
      nodes += 1;
    } else if (tier.avgCpuPct !== null && out !== null && tier.avgCpuPct >= out) {
      warn = true;
      nodes += 1;
    }
  }
  if (c.replication.state === "READY") {
    if (c.replication.data.stream === "STOPPED") down = true;
    else if (c.replication.data.stream === "CATCHUP") warn = true;
  }
  if (c.aiServer.state === "READY" && c.aiServer.data.link === "DOWN") {
    warn = true;
    nodes += 1;
  }
  return { level: down ? "down" : warn ? "warn" : "ok", alerts: alerts.length, nodes };
}
