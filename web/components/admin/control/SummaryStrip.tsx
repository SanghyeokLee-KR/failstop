"use client";

/**
 * 상태 요약 다섯 칸(§12-9). 멀리서 읽히도록 수치는 display-md다. 목표를 벗어난 칸만 형태와
 * 헬스 색을 받고 정상 칸은 잉크로 둔다. 추이는 그리지 않고 Grafana로 보낸다.
 */

import type { ControlCenter, IncidentLog, OpsAlert, OpsRef } from "@/lib/admin/types";
import { refMatches, useOpsFocus } from "@/lib/admin/opsCenter";
import GrafanaLink from "../GrafanaLink";
import { durationWord, SEVERITY_WORD } from "./parts";
import styles from "./control.module.css";

type Tone = "warning" | "danger" | undefined;

function Tile({ label, tone, focusRef, link, children, sub }: {
  label: string;
  tone?: Tone;
  focusRef?: OpsRef;
  link?: boolean;
  children: React.ReactNode;
  sub: React.ReactNode;
}) {
  const focus = useOpsFocus((s) => s.ref);
  const lit = focusRef ? refMatches(focus, focusRef) : false;
  return (
    <div className={styles.tile} data-tone={tone} data-focus={lit ? "" : undefined}>
      <div className={styles.tileHead}>
        <span className={styles.tileLabel}>{label}</span>
        {link && <GrafanaLink board="msp-overview" label="추이" />}
      </div>
      {children}
      <span className={styles.tileSub}>{sub}</span>
    </div>
  );
}

function Missing({ label }: { label: string }) {
  return (
    <div className={styles.tile}>
      <span className={styles.tileLabel}>{label}</span>
      <span className={styles.tileValue}>
        <span className={styles.missing} aria-hidden="true">◌</span>
      </span>
      <span className={styles.tileSub}>원천 연결 전</span>
    </div>
  );
}

/** 오늘 사건 수와 해제된 사건의 평균 복구 시간. 오늘은 로컬 날짜 기준이다 */
export function todayIncidents(log: IncidentLog, now: number): { count: number; open: number; mttrMs: number | null } {
  const today = new Date(now).toDateString();
  const list = log.incidents.filter((i) => new Date(i.openedAt).toDateString() === today);
  const done = list.filter((i) => i.resolvedAt !== null);
  const mttrMs = done.length === 0 ? null
    : done.reduce((a, i) => a + (Date.parse(i.resolvedAt as string) - Date.parse(i.openedAt)), 0) / done.length;
  return { count: list.length, open: list.length - done.length, mttrMs };
}

export function alertCounts(alerts: OpsAlert[]): Record<"critical" | "warning" | "info", number> {
  const out = { critical: 0, warning: 0, info: 0 };
  for (const a of alerts) out[a.severity] += 1;
  return out;
}

export default function SummaryStrip({ center, now }: { center: ControlCenter | null; now: number }) {
  const s = center?.summary.state === "READY" ? center.summary.data : null;
  const alerts = center?.alerts.state === "READY" ? center.alerts.data : null;
  const log = center?.incidents.state === "READY" ? center.incidents.data : null;
  const avTone: Tone = s && s.availabilityPct !== null && s.availabilityPct < s.slo.availabilityPct ? "danger" : undefined;
  const p95Tone: Tone = s && s.p95Ms !== null && s.p95Ms > s.slo.p95Ms ? "warning" : undefined;
  const errTone: Tone = s && s.errorRatePct !== null && s.errorRatePct >= s.slo.errorRatePct ? "warning" : undefined;
  const counts = alerts ? alertCounts(alerts) : null;
  const alertTone: Tone = counts ? (counts.critical > 0 ? "danger" : counts.warning > 0 ? "warning" : undefined) : undefined;
  const acked = alerts ? alerts.filter((a) => a.state === "ACKED").length : 0;
  const silenced = alerts ? alerts.filter((a) => a.state === "SILENCED").length : 0;
  const inc = log ? todayIncidents(log, now) : null;

  return (
    <section className={styles.summary} aria-label="상태 요약">
      {s ? (
        <>
          <Tile label="외부 가용성" tone={avTone} focusRef={{ kind: "SLO", metric: "availability" }} link
            sub={<>목표 {s.slo.availabilityPct}% · 외부 프로브 5분</>}>
            <span className={`${styles.tileValue} num`} data-tone={avTone}>
              {avTone && <span className={styles.tileMark} aria-hidden="true">■</span>}
              {s.availabilityPct ?? "◌"}
              <span className={styles.tileUnit}>%</span>
            </span>
          </Tile>
          <Tile label="응답 p95" tone={p95Tone} focusRef={{ kind: "SLO", metric: "p95" }}
            sub={<>목표 {s.slo.p95Ms}ms 이하</>}>
            <span className={`${styles.tileValue} num`} data-tone={p95Tone}>
              {p95Tone && <span className={styles.tileMark} aria-hidden="true">▲</span>}
              {s.p95Ms ?? "◌"}
              <span className={styles.tileUnit}>ms</span>
            </span>
          </Tile>
          <Tile label="5xx 비율" tone={errTone} focusRef={{ kind: "SLO", metric: "errors" }}
            sub={<>목표 {s.slo.errorRatePct}% 미만</>}>
            <span className={`${styles.tileValue} num`} data-tone={errTone}>
              {errTone && <span className={styles.tileMark} aria-hidden="true">▲</span>}
              {s.errorRatePct ?? "◌"}
              <span className={styles.tileUnit}>%</span>
            </span>
          </Tile>
        </>
      ) : (
        <>
          <Missing label="외부 가용성" />
          <Missing label="응답 p95" />
          <Missing label="5xx 비율" />
        </>
      )}
      {counts ? (
        <Tile label="활성 경보" tone={alertTone}
          sub={acked + silenced > 0 ? <>확인 <b className="num">{acked}</b> · 보류 <b className="num">{silenced}</b></> : "확인·보류 없음"}>
          <span className={`${styles.tileValue} ${styles.alertCounts} num`}>
            {(["critical", "warning", "info"] as const).map((sev) => (
              <span key={sev} className={styles.alertCount} data-sev={sev} data-zero={counts[sev] === 0 ? "" : undefined}>
                <span className={styles.tileMark} aria-hidden="true">{sev === "critical" ? "■" : sev === "warning" ? "▲" : "●"}</span>
                {counts[sev]}
                <span className="sr-only">{SEVERITY_WORD[sev]}</span>
              </span>
            ))}
          </span>
        </Tile>
      ) : (
        <Missing label="활성 경보" />
      )}
      {inc ? (
        <Tile label="오늘 사건" tone={inc.open > 0 ? "warning" : undefined}
          sub={<>평균 복구 <b className="num">{inc.mttrMs === null ? "기록 없음" : durationWord(inc.mttrMs)}</b>{inc.open > 0 && <> · 진행 <b className="num">{inc.open}</b></>}</>}>
          <span className={`${styles.tileValue} num`}>
            {inc.count}
            <span className={styles.tileUnit}>건</span>
          </span>
        </Tile>
      ) : (
        <Missing label="오늘 사건" />
      )}
    </section>
  );
}
