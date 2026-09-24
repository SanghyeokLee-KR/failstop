"use client";

/**
 * 운영 관제 사이드바의 상태 배지와 AI 모드 표시. 관제 판 폴러(opsCenter)를 구독하므로 운영 관제
 * 셸에서만 그린다. 배지는 형태와 색을 함께 쓰고(§12-3) 정상이면 그리지 않는다.
 */

import type { ReactNode } from "react";
import type { OpsBadgeKind } from "@/lib/admin/nav";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import type { ControlCenter, ThresholdKey } from "@/lib/admin/types";
import { hostState } from "./Infra";
import { AiMark, useClock } from "./parts";
import styles from "./control.module.css";

interface Badge {
  tone: "danger" | "warning" | "info" | "ai";
  text: ReactNode;
  sr: string;
}

function badgeOf(kind: OpsBadgeKind, c: ControlCenter): Badge | null {
  const reg = c.thresholds.state === "READY" ? c.thresholds.data : null;
  const val = (k: ThresholdKey) => reg?.items.find((t) => t.key === k)?.value ?? null;
  switch (kind) {
    case "incidents": {
      if (c.alerts.state !== "READY") return null;
      const firing = c.alerts.data.filter((a) => a.state !== "SILENCED");
      const crit = firing.filter((a) => a.severity === "critical").length;
      const warn = firing.filter((a) => a.severity === "warning").length;
      const info = firing.filter((a) => a.severity === "info").length;
      if (crit > 0) return { tone: "danger", text: <>■ {crit}</>, sr: `장애 경보 ${crit}건` };
      if (warn > 0) return { tone: "warning", text: <>▲ {warn}</>, sr: `주의 경보 ${warn}건` };
      if (info > 0) return { tone: "info", text: <>● {info}</>, sr: `정보 경보 ${info}건` };
      return null;
    }
    case "ai": {
      if (c.ai.state !== "READY") return null;
      const open = c.ai.data.proposals.filter((p) => p.status === "PROPOSED").length;
      return open > 0 ? { tone: "ai", text: <>제안 {open}</>, sr: `AI 조치안 ${open}건 대기` } : null;
    }
    case "hosts": {
      if (c.hosts.state !== "READY") return null;
      // 전체 상태(운영자 바)와 같은 판정을 쓴다. 수집이 끊긴 노드도 주의로 센다
      const tones = c.hosts.data.map((h) => hostState(h, reg).tone);
      const bad = tones.filter((t) => t !== "success").length;
      const tier = c.readTier.state === "READY" ? c.readTier.data : null;
      const out = val("read.scale_out_cpu");
      const hot = tier !== null && tier.avgCpuPct !== null && out !== null && tier.avgCpuPct >= out;
      if (tones.includes("danger")) return { tone: "danger", text: <>■ {bad}</>, sr: `장애 호스트 ${bad}대` };
      if (bad > 0 || hot) return { tone: "warning", text: <>▲ {bad + (hot ? 1 : 0)}</>, sr: "주의 노드 있음" };
      return null;
    }
    case "database": {
      const repl = c.replication.state === "READY" ? c.replication.data : null;
      const lag = val("db.replication_lag_alert");
      const late = repl !== null && repl.lagSec !== null && lag !== null && repl.lagSec >= lag;
      const backupLate = c.alerts.state === "READY" && c.alerts.data.some((a) => a.id === "backup_delay");
      if (repl?.stream === "STOPPED") return { tone: "danger", text: <>■</>, sr: "복제 끊김" };
      if (late || backupLate || (repl !== null && repl.stream !== "STREAMING")) return { tone: "warning", text: <>▲</>, sr: "DB 주의" };
      return null;
    }
    case "thresholds": {
      if (!reg) return null;
      const n = reg.items.filter((t) => t.aiSuggested !== null).length;
      return n > 0 ? { tone: "ai", text: <>AI {n}</>, sr: `AI 제안값 ${n}건` } : null;
    }
  }
}

export function NavBadge({ kind }: { kind: OpsBadgeKind }) {
  const { data } = useOpsCenter();
  if (!data) return null;
  const b = badgeOf(kind, data);
  if (!b) return null;
  return (
    <span className={styles.navBadge} data-tone={b.tone}>
      <span aria-hidden="true">{b.text}</span>
      <span className="sr-only">{b.sr}</span>
    </span>
  );
}

/** 사이드바 바닥의 AI 모드. 자동이면 남은 시간을 시:분으로, 정지면 주의 모양으로 적는다 */
export function AiModeFoot() {
  const { data } = useOpsCenter();
  const now = useClock(15_000);
  const ai = data?.ai.state === "READY" ? data.ai.data : null;
  const auto = ai?.mode === "AUTO" && ai.auto !== null;
  const stopped = ai?.mode === "STOPPED";
  let text: ReactNode = "AI 연결 전";
  if (stopped) {
    text = (
      <>
        <span aria-hidden="true">▲ </span>AI <b>정지</b> · 판단 멈춤
      </>
    );
  } else if (ai && auto && ai.auto) {
    const left = Math.max(0, Date.parse(ai.auto.until) - now);
    const h = Math.floor(left / 3_600_000);
    const m = Math.floor((left % 3_600_000) / 60_000);
    text = (
      <>
        AI <b>자동</b> · {h}:{String(m).padStart(2, "0")} 남음
      </>
    );
  } else if (ai) {
    text = (
      <>
        AI <b>수동</b> · 실행은 승인
      </>
    );
  }
  return (
    <div className={styles.aiFootNav} data-mode={stopped ? "STOPPED" : auto ? "AUTO" : "MANUAL"} title="AI 운영 모드">
      <AiMark size={14} />
      <span className={styles.aiFootNavText}>{text}</span>
    </div>
  );
}
