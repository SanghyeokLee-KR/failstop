"use client";

/**
 * 운영 관제 사이드바의 상태 배지와 AI 모드 표시. 관제 판 폴러(opsCenter)를 구독하므로 운영 관제
 * 셸에서만 그린다. 배지는 형태와 색을 함께 쓰고(§12-3) 정상이면 그리지 않는다.
 *
 * 배지는 두 벌을 함께 그린다. 펼친 메뉴는 '제안 2'처럼 모양·이름표·수를 싣고, 접힌 아이콘 기둥은 8px 모양 하나만
 * 아이콘 오른쪽 위 모서리에 얹는다(§12-9 v1.14). 기둥 폭 72px에서 글자 배지는 아이콘을 덮고 목록 상자에 잘렸다.
 * 접힌 기둥의 건수는 화면 낭독용 글과 운영자 바의 경보 종이 싣는다.
 */

import type { ReactNode } from "react";
import type { OpsBadgeKind } from "@/lib/admin/nav";
import { useOpsPick, useOpsPickShallow } from "@/lib/admin/opsCenter";
import type { ControlCenter, ThresholdKey } from "@/lib/admin/types";
import { appTierHot, hostState } from "@/lib/admin/opsHealth";
import StatusShape, { type StatusShapeKind } from "../StatusShape";
import { AiMark, remainWord, useClock } from "./parts";
import styles from "./control.module.css";

/** 원시 값으로만 된 배지. 사이드바가 배지를 얕게 견줘 바뀔 때만 다시 그린다 */
export interface Badge {
  tone: "danger" | "warning" | "info" | "ai";
  /** 상태 배지의 모양(§12-3). AI 배지는 모양 대신 이름표를 쓴다 */
  shape: StatusShapeKind | null;
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
  }
}

export function NavBadge({ kind }: { kind: OpsBadgeKind }) {
  // 판은 매초 새로 오지만 배지 글자는 드물게 바뀐다. 글자가 같으면 다시 그리지 않는다
  const b = useOpsPickShallow((c) => (c ? badgeOf(kind, c) : null));
  if (!b) return null;
  return (
    <span className={styles.navBadge} data-tone={b.tone} data-bare={b.label === null && b.count === null ? "" : undefined}>
      <span className={styles.navBadgeInk} data-badge-part="ink" aria-hidden="true">
        {b.shape && <StatusShape kind={b.shape} size={8} />}
        {b.label && <span>{b.label}</span>}
        {b.count !== null && <span className="num">{b.count}</span>}
      </span>
      <span className={styles.navBadgeMark} data-badge-part="mark" aria-hidden="true">
        <StatusShape kind={b.shape ?? "circle"} size={8} />
      </span>
      <span className="sr-only">{b.sr}</span>
    </span>
  );
}

/**
 * 사이드바 바닥의 AI 모드. 자동이면 남은 시간을 모드 줄과 같은 꼴('1시간 59분 남음', §12-12)로, 정지면 주의 모양으로
 * 적는다. 모드 줄과 같은 박자(1초)로 읽어야 분이 바뀌는 순간에 두 자리가 다른 값을 보이지 않는다
 */
export function AiModeFoot() {
  const section = useOpsPick((c) => c?.ai ?? null);
  const now = useClock();
  const ai = section?.state === "READY" ? section.data : null;
  const auto = ai?.mode === "AUTO" && ai.auto !== null;
  const stopped = ai?.mode === "STOPPED";
  let text: ReactNode = "AI 연결 전";
  if (stopped) {
    text = (
      <>
        <span aria-hidden="true"><StatusShape kind="triangle" /> </span>AI <b>정지</b> · 판단 멈춤
      </>
    );
  } else if (ai && auto && ai.auto) {
    text = (
      <>
        AI <b>자동</b> · {remainWord(Date.parse(ai.auto.until) - now)} 남음
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
