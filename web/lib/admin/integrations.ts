/**
 * 연동 상태(관리자 설계 4-8). 설정 화면이 관제 판과 보고 서비스의 전송 기록에서 원천마다 연결됨·연결 전·오류를
 * 가린다. 새로 묻지 않고 이미 받은 판을 읽는다. 연결됨은 이 콘솔이 그 원천의 값을 받고 있다는 뜻이고,
 * Grafana처럼 콘솔이 링크만 거는 원천은 주소가 들어 있는지만 적는다.
 */

import { collectTargets } from "./opsHealth";
import { hm, hms, kst, secWord } from "./timeText";
import type { ControlCenter, OpsSection, OpsSourceName, OpsSourceState, ReportAttempt, ReportDesk } from "./types";

export type IntegrationKey = "prometheus" | "alertmanager" | "grafana" | "databasus" | "teams" | "ai1";
/** ASSUMED는 구축 전이라 연결을 시뮬레이터가 가정한 원천이다(sim의 ai-1). 연결됨으로 세지 않는다 */
export type IntegrationState = "CONNECTED" | "NOT_CONNECTED" | "ERROR" | "ASSUMED";

export interface IntegrationRow {
  key: IntegrationKey;
  name: string;
  role: string;
  state: IntegrationState;
  /** 원천에서 읽은 지금 값이나 연결 전 사유 */
  detail: string;
  /** 마지막으로 확인한 시각. 확인하지 않는 원천(링크만)이면 null이다 */
  checkedAt: string | null;
  /** 시뮬레이터가 흉내 낸 값인가 */
  sim: boolean;
}

export const STATE_WORD: Record<IntegrationState, { shape: string; word: string; tone: "success" | "off" | "danger" }> = {
  CONNECTED: { shape: "●", word: "연결됨", tone: "success" },
  NOT_CONNECTED: { shape: "◌", word: "연결 전", tone: "off" },
  ERROR: { shape: "■", word: "오류", tone: "danger" },
  ASSUMED: { shape: "◌", word: "시뮬레이션 가정", tone: "off" },
};

/** 보고 서비스 경유 Teams 전송의 판정. 설정의 Teams 행과 서비스 흐름의 Teams·AI 운영 보고 자리가 같이 쓴다 */
export function teamsDelivery(desk: OpsSection<ReportDesk> | null): {
  state: IntegrationState;
  last: ReportAttempt | null;
  lastReportAt: string | null;
  detail: string;
} {
  if (desk?.state === "UNAVAILABLE") {
    return { state: "ERROR", last: null, lastReportAt: null, detail: `불러오기 실패 · ${desk.reason}` };
  }
  if (desk?.state !== "READY") {
    return { state: "NOT_CONNECTED", last: null, lastReportAt: null, detail: desk?.state === "NOT_CONNECTED" ? desk.source : "보고 서비스 확인 중" };
  }
  const d = desk.data;
  // 보고 서비스 읽기 프록시(http)는 시도 기록을 싣지 않고 보고마다 보낸 시각만 준다. 그때는 마지막 보낸 보고를 전송으로 센다
  const sent = d.reports.filter((r) => r.sentAt !== null).map((r): ReportAttempt => ({ attempt: 1, at: r.sentAt as string, status: "sent", error: null }));
  const tried = d.reports.flatMap((r) => r.attempts);
  const last = (tried.length > 0 ? tried : sent).concat(d.tests).sort((a, b) => Date.parse(b.at) - Date.parse(a.at))[0] ?? null;
  const lastReportAt = d.reports.map((r) => r.createdAt).sort((a, b) => Date.parse(b) - Date.parse(a))[0] ?? null;
  const failed = d.delivery.dead > 0;
  const detail = last
    ? `마지막 ${kst(last.at, false)} ${last.status === "sent" ? "전송됨" : last.status === "queued" ? "재시도 대기" : "실패"} · 전송 대기 ${d.delivery.pending}${failed ? ` · 실패 ${d.delivery.dead}` : ""}`
    : "보낸 기록 없음";
  return { state: failed ? "ERROR" : last ? "CONNECTED" : "NOT_CONNECTED", last, lastReportAt, detail };
}

function fromSource(sources: OpsSourceState[] | null, name: OpsSourceName): Pick<IntegrationRow, "state" | "detail" | "checkedAt"> {
  if (sources === null) return { state: "NOT_CONNECTED", detail: "관리 API에 관제 모듈 없음(overview 404)", checkedAt: null };
  const s = sources.find((x) => x.name === name);
  if (!s || s.state === "NOT_CONNECTED") return { state: "NOT_CONNECTED", detail: "관제 서버에 원천 설정 없음", checkedAt: null };
  if (s.state === "UNAVAILABLE") return { state: "ERROR", detail: `마지막 확인 실패 · ${s.reason ?? "사유 미상"}`, checkedAt: s.checkedAt };
  return { state: "CONNECTED", detail: "관제 서버가 읽는 중", checkedAt: s.checkedAt };
}

export function integrationsOf(c: ControlCenter | null, desk: OpsSection<ReportDesk> | null, links: { grafana: boolean; databasus: boolean }): IntegrationRow[] {
  const sim = c?.origin === "SIM";
  const at = c?.collectedAt ?? null;
  const rows: IntegrationRow[] = [];

  if (sim && c) {
    const t = collectTargets(c);
    rows.push({ key: "prometheus", name: "Prometheus", role: "지표 수집·조회", state: "CONNECTED", detail: `수집 대상 ${t.targets} · 응답 ${t.ok}/${t.targets}`, checkedAt: at, sim });
    const alerts = c.alerts.state === "READY" ? c.alerts.data : [];
    rows.push({
      key: "alertmanager",
      name: "Alertmanager",
      role: "경보 수신·보류",
      state: "CONNECTED",
      detail: `활성 경보 ${alerts.length} · 보류 ${alerts.filter((a) => a.state === "SILENCED").length}`,
      checkedAt: at,
      sim,
    });
  } else {
    rows.push({ key: "prometheus", name: "Prometheus", role: "지표 수집·조회", ...fromSource(c?.sources ?? null, "prometheus"), sim: false });
    rows.push({ key: "alertmanager", name: "Alertmanager", role: "경보 수신·보류", ...fromSource(c?.sources ?? null, "alertmanager"), sim: false });
  }

  rows.push({
    key: "grafana",
    name: "Grafana",
    role: "추이 대시보드 · 딥링크",
    state: links.grafana ? "CONNECTED" : "NOT_CONNECTED",
    detail: links.grafana ? "딥링크 주소 설정됨 · 콘솔은 추이를 읽지 않음" : "빌드 변수 NEXT_PUBLIC_GRAFANA_BASE 없음",
    checkedAt: null,
    sim: false,
  });

  const jobs = sim && c?.jobs.state === "READY" ? c.jobs.data : null;
  if (jobs && jobs.backup) {
    const b = jobs.backup;
    const restore = b.restoreCheck.outcome === "SUCCESS" ? "통과" : b.restoreCheck.outcome === "FAILED" ? "실패" : "기록 없음";
    rows.push({
      key: "databasus",
      name: "Databasus",
      role: "백업·복원 검증",
      state: b.restoreCheck.outcome === "FAILED" ? "ERROR" : "CONNECTED",
      detail: `마지막 성공 ${b.lastSuccessAt ? hm(b.lastSuccessAt) : "없음"} · 복원 검증 ${restore}${links.databasus ? "" : " · 딥링크 주소 없음"}`,
      checkedAt: at,
      sim,
    });
  } else {
    rows.push({ key: "databasus", name: "Databasus", role: "백업·복원 검증", ...fromSource(c?.sources ?? null, "databasus"), sim: false });
  }

  const teams = teamsDelivery(desk);
  rows.push({
    key: "teams",
    name: "Teams",
    role: "보고 서비스 경유 전송",
    state: teams.state,
    detail: teams.detail,
    checkedAt: teams.last?.at ?? null,
    sim: desk?.state === "READY" && sim,
  });

  if (c?.aiServer.state === "READY") {
    const a = c.aiServer.data;
    const p95 = a.p95Ms === null ? "표본 없음" : secWord(a.p95Ms);
    // 장비 메모리를 모르면(http) 분모 없이 적재 모델 메모리만 적는다
    const mem = a.memUsedGb === null ? "미수집" : a.memTotalGb === null ? `${a.memUsedGb.toFixed(1)}GB` : `${a.memUsedGb.toFixed(1)}/${a.memTotalGb}GB`;
    const values = `모델 ${a.model} · 적재 ${mem} · 지연 p95 ${p95} · 마지막 응답 ${a.lastServedAt ? hms(a.lastServedAt) : "기록 없음"}`;
    rows.push({
      key: "ai1",
      name: "AI 서버 ai-1",
      role: `온프레미스 추론 · ${a.hardware} · ${a.runtime}`,
      // 구축 전이라 sim의 연결은 가정이다. 연결됨으로 세지 않고 가정이라는 사실과 가정한 값을 함께 적는다
      state: a.assumed ? "ASSUMED" : a.link === "OK" ? "CONNECTED" : "ERROR",
      detail: a.assumed ? `구축 전 · 연결 경로 결정 전 · 가정한 값: ${values}` : values,
      checkedAt: a.collectedAt,
      sim,
    });
  } else {
    const src = fromSource(c?.sources ?? null, "llm");
    rows.push({ key: "ai1", name: "AI 서버 ai-1", role: "온프레미스 추론 · 구축 전(ADR 0038)", ...src, sim: false });
  }
  return rows;
}
