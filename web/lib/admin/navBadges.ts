/**
 * 운영 관제 사이드바 메뉴 배지의 판정(디자인 시스템 §12-9, 관리자 설계 4-10-1). 배지는 셀 것이 있을 때만 서고 형태와 색을
 * 함께 쓴다(§12-3). 관제 판 하나에서 정하므로 화면(경보·사건, 서버·노드, 변경·작업, 네트워크·보안)과 같은 판정을 쓴다.
 * 그리는 쪽은 components/admin/control/NavStatus다.
 */

import type { ControlCenter, ThresholdKey } from "./types";
import { appTierHot, hostState } from "./opsHealth";
import { certDaysLeft } from "./certs";
import { openWindows } from "./changes";

/** 운영 관제 메뉴 옆 상태 배지의 종류. 메뉴 표(nav.ts)가 항목마다 고른다 */
export type OpsBadgeKind = "incidents" | "ai" | "hosts" | "database" | "thresholds" | "security" | "changes";

/** 배지의 형태(StatusShape의 한 벌에서 배지가 쓰는 넷). 이 판정은 화면 부품에 기대지 않는다 */
export type BadgeShape = "square" | "triangle" | "circle" | "play";

/** 원시 값으로만 된 배지. 사이드바가 배지를 얕게 견줘 바뀔 때만 다시 그린다 */
export interface Badge {
  tone: "danger" | "warning" | "info" | "ai";
  /** 상태 배지의 모양(§12-3). AI 배지는 모양 대신 이름표를 쓴다 */
  shape: BadgeShape | null;
  /** 펼친 메뉴에서만 보이는 이름표('제안', 'AI') */
  label: string | null;
  /** 건수. 복제 끊김처럼 셀 것이 없으면 null이다 */
  count: number | null;
  sr: string;
}

export function badgeOf(kind: OpsBadgeKind, c: ControlCenter): Badge | null {
  const reg = c.thresholds.state === "READY" ? c.thresholds.data : null;
  const val = (k: ThresholdKey) => reg?.items.find((t) => t.key === k)?.value ?? null;
  switch (kind) {
    case "incidents": {
      if (c.alerts.state !== "READY") return null;
      const firing = c.alerts.data.filter((a) => a.state !== "SILENCED");
      const crit = firing.filter((a) => a.severity === "critical").length;
      const warn = firing.filter((a) => a.severity === "warning").length;
      const info = firing.filter((a) => a.severity === "info").length;
      if (crit > 0) return { tone: "danger", shape: "square", label: null, count: crit, sr: `장애 경보 ${crit}건` };
      if (warn > 0) return { tone: "warning", shape: "triangle", label: null, count: warn, sr: `주의 경보 ${warn}건` };
      if (info > 0) return { tone: "info", shape: "circle", label: null, count: info, sr: `정보 경보 ${info}건` };
      return null;
    }
    case "ai": {
      if (c.ai.state !== "READY") return null;
      const open = c.ai.data.proposals.filter((p) => p.status === "PROPOSED").length;
      return open > 0 ? { tone: "ai", shape: null, label: "제안", count: open, sr: `AI 조치안 ${open}건 대기` } : null;
    }
    case "hosts": {
      if (c.hosts.state !== "READY") return null;
      // 전체 상태(운영자 바)·인프라 칸 집계와 같은 판정을 쓴다. 수집이 끊긴 노드도, 앱 계층 과부하의 앱 노드도 주의로 센다
      const hot = appTierHot(c);
      const tones = c.hosts.data.map((h) => hostState(h, reg, hot).tone);
      const tier = c.readTier.state === "READY" ? c.readTier.data : null;
      // 켜진 app-3도 같은 부하를 받는다. 기동·드레이닝 중인 app-3는 넘어가는 중이라 주의다
      const app3 = tier?.nodes.filter((n) => n.state !== "OFF" && (hot || n.state !== "IN_SERVICE")).length ?? 0;
      const bad = tones.filter((t) => t !== "success").length + app3;
      if (tones.includes("danger")) return { tone: "danger", shape: "square", label: null, count: bad, sr: `장애 호스트 ${bad}대` };
      if (bad > 0) return { tone: "warning", shape: "triangle", label: null, count: bad, sr: `주의 노드 ${bad}대` };
      return null;
    }
    case "database": {
      const repl = c.replication.state === "READY" ? c.replication.data : null;
      const lag = val("db.replication_lag_alert");
      const late = repl !== null && repl.lagSec !== null && lag !== null && repl.lagSec >= lag;
      const backupLate = c.alerts.state === "READY" && c.alerts.data.some((a) => a.id === "backup_delay");
      if (repl?.stream === "STOPPED") return { tone: "danger", shape: "square", label: null, count: null, sr: "복제 끊김" };
      if (late || backupLate || (repl !== null && repl.stream !== "STREAMING")) return { tone: "warning", shape: "triangle", label: null, count: null, sr: "DB 주의" };
      return null;
    }
    case "thresholds": {
      if (!reg) return null;
      const n = reg.items.filter((t) => t.aiSuggested !== null).length;
      return n > 0 ? { tone: "ai", shape: null, label: "AI", count: n, sr: `AI 제안값 ${n}건` } : null;
    }
    case "security": {
      // 인증서 만료. 관제센터 운영 작업 줄, 네트워크·보안과 같은 판정(certTone: 30일 이내 주의, 7일 이내 장애)이다
      const certs = c.jobs.state === "READY" ? c.jobs.data.certificates : null;
      if (!certs) return null;
      const days = certs.map((x) => certDaysLeft(x, Date.parse(c.generatedAt)));
      const danger = days.filter((d) => d <= 7).length;
      const warn = days.filter((d) => d <= 30).length;
      if (danger > 0) return { tone: "danger", shape: "square", label: null, count: danger, sr: `7일 안에 만료되는 인증서 ${danger}장` };
      if (warn > 0) return { tone: "warning", shape: "triangle", label: null, count: warn, sr: `30일 안에 만료되는 인증서 ${warn}장` };
      return null;
    }
    case "changes": {
      // 진행 중인 계획 작업 창. 상태가 아니라 사실이라 무채 윤곽의 '진행'이다(§12-9). 모드 줄, 변경·작업과 같은 판정이다
      if (c.changes.state !== "READY") return null;
      const n = openWindows(c.changes.data.windows, Date.parse(c.generatedAt)).length;
      return n > 0 ? { tone: "info", shape: "play", label: "진행", count: n > 1 ? n : null, sr: `진행 중인 계획 작업 창 ${n}개` } : null;
    }
  }
}
