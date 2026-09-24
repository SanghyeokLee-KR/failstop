"use client";

/**
 * 상태 요약 다섯 칸(§12-9). 칸마다 아이콘, 큰 수치와 흐린 단위, 앞 구간 대비 증감, 작은 추세선, 목표·보조
 * 줄을 둔다. 목표를 벗어난 칸만 형태와 헬스 색을 받고 정상 칸은 잉크로 둔다. 증감의 색은 오르내림이 아니라
 * 좋아졌는지로 정한다(5xx가 줄면 좋다). 추세는 화면 머리에서 고른 범위(1·6·24시간)이고 탐색은 Grafana로 보낸다.
 */

import type { ReactNode } from "react";
import type { ControlCenter, IncidentLog, OpsAlert, OpsRef, OpsSection, OpsTrendSeries, TrendRange } from "@/lib/admin/types";
import { refMatches, useOpsFocus } from "@/lib/admin/opsCenter";
import { AdminIcon, type AdminIconName } from "../icons";
import { durationWord, SEVERITY_WORD } from "./parts";
import Sparkline from "./Sparkline";
import styles from "./control.module.css";

type Tone = "warning" | "danger" | undefined;

/** 범위별 점 사이 간격(분). opsTrends.ts 의 점 개수와 짝이다 */
const STEP_MIN: Record<TrendRange, number> = { 1: 1, 6: 5, 24: 15 };

interface Delta {
  text: string;
  dir: "up" | "down" | "flat";
  good: boolean | null;
}

/**
 * 앞 구간 대비. pp는 퍼센트포인트 차, pct는 앞 구간 대비 비율, count는 건수 차다.
 * 앞 구간 값이 없으면 비교하지 않는다
 */
function deltaOf(s: OpsTrendSeries, how: "pp" | "pct" | "count", better: "up" | "down"): Delta | null {
  if (s.current === null || s.previous === null) return null;
  let diff = s.current - s.previous;
  let text: string;
  if (how === "pct") {
    if (s.previous === 0) return null;
    diff = (diff / s.previous) * 100;
    text = `${Math.round(Math.abs(diff))}%`;
    if (Math.round(Math.abs(diff)) === 0) diff = 0;
  } else if (how === "pp") {
    text = `${Math.abs(diff).toFixed(2)}%p`;
    if (Math.abs(diff) < 0.005) diff = 0;
  } else {
    text = `${Math.abs(Math.round(diff))}건`;
  }
  if (diff === 0) return { text: how === "count" ? "0건" : how === "pp" ? "0.00%p" : "0%", dir: "flat", good: null };
  const dir = diff > 0 ? "up" : "down";
  return { text, dir, good: dir === better };
}

function DeltaChip({ d, range }: { d: Delta | null; range: TrendRange }) {
  if (d === null) {
    return (
      <span className={styles.delta} data-good="none">
        비교 기록 없음
      </span>
    );
  }
  const word = d.dir === "flat" ? "변화 없음" : `${d.dir === "up" ? "늘었습니다" : "줄었습니다"}${d.good ? ", 좋아졌습니다" : ", 나빠졌습니다"}`;
  return (
    <span className={`${styles.delta} num`} data-good={d.good === null ? "flat" : d.good ? "good" : "bad"} title={`이전 ${range}시간 대비`}>
      <span aria-hidden="true">
        {d.dir === "up" ? "▲ " : d.dir === "down" ? "▼ " : ""}
        {d.text}
      </span>
      <span className="sr-only">
        이전 {range}시간 대비 {d.text} {word}
      </span>
    </span>
  );
}

/** 요약 칸 하나. 경보·사건 화면의 머리 칸도 같은 모양을 쓴다(추세와 증감 없이) */
export function Tile({ icon, label, tone, focusRef, value, delta, trend, sub }: {
  icon: AdminIconName;
  label: string;
  tone?: Tone;
  focusRef?: OpsRef;
  value: ReactNode;
  delta?: ReactNode;
  trend?: ReactNode;
  sub: ReactNode;
}) {
  const focus = useOpsFocus((s) => s.ref);
  const lit = focusRef ? refMatches(focus, focusRef) : false;
  return (
    <div className={styles.tile} data-tone={tone} data-focus={lit ? "" : undefined}>
      <span className={styles.tileIcon} aria-hidden="true">
        <AdminIcon name={icon} size={16} />
      </span>
      <div className={styles.tileBody}>
        <div className={styles.tileTop}>
          <span className={styles.tileLabel}>{label}</span>
          {delta}
        </div>
        <div className={styles.tileMid}>
          {value}
          {trend}
        </div>
        <span className={styles.tileSub}>{sub}</span>
      </div>
    </div>
  );
}

function Missing({ icon, label, source }: { icon: AdminIconName; label: string; source: string }) {
  return (
    <div className={styles.tile} data-missing="">
      <span className={styles.tileIcon} aria-hidden="true">
        <AdminIcon name={icon} size={16} />
      </span>
      <div className={styles.tileBody}>
        <div className={styles.tileTop}>
          <span className={styles.tileLabel}>{label}</span>
        </div>
        <div className={styles.tileMid}>
          <span className={styles.tileValue}>
            <span className={styles.missing} aria-hidden="true">
              ◌
            </span>
          </span>
        </div>
        <span className={styles.tileSub}>원천 연결 전 · {source}</span>
      </div>
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

const pct2 = (v: number) => `${v.toFixed(2)}%`;
const ms0 = (v: number) => `${Math.round(v)}ms`;

export default function SummaryStrip({ center, now, range }: { center: ControlCenter | null; now: number; range: TrendRange }) {
  const s = center?.summary.state === "READY" ? center.summary.data : null;
  const alerts = center?.alerts.state === "READY" ? center.alerts.data : null;
  const log = center?.incidents.state === "READY" ? center.incidents.data : null;
  const tr = center?.trends.state === "READY" ? center.trends.data[range] : null;
  const avTone: Tone = s && s.availabilityPct !== null && s.availabilityPct < s.slo.availabilityPct ? "danger" : undefined;
  const p95Tone: Tone = s && s.p95Ms !== null && s.p95Ms > s.slo.p95Ms ? "warning" : undefined;
  const errTone: Tone = s && s.errorRatePct !== null && s.errorRatePct >= s.slo.errorRatePct ? "warning" : undefined;
  const counts = alerts ? alertCounts(alerts) : null;
  const alertTone: Tone = counts ? (counts.critical > 0 ? "danger" : counts.warning > 0 ? "warning" : undefined) : undefined;
  const acked = alerts ? alerts.filter((a) => a.state === "ACKED").length : 0;
  const silenced = alerts ? alerts.filter((a) => a.state === "SILENCED").length : 0;
  const inc = log ? todayIncidents(log, now) : null;
  const step = STEP_MIN[range];
  // 축의 최소 폭은 그 지표에서 눈여겨볼 변화의 크기다. 가용성·5xx는 0.06%p, p95는 60ms다
  const spark = (series: OpsTrendSeries | undefined, tone: Tone, format: (v: number) => string, what: string, opt: { kind?: "bars"; minSpan?: number; zero?: boolean } = {}) =>
    series ? (
      <Sparkline points={series.points} tone={tone} format={format} stepMin={step} now={now} label={`지난 ${range}시간 ${what}`} kind={opt.kind} minSpan={opt.minSpan} zero={opt.zero} />
    ) : null;
  const mark = (tone: Tone) =>
    tone && (
      <span className={styles.tileMark} aria-hidden="true">
        {tone === "danger" ? "■" : "▲"}
      </span>
    );
  const source = (sec: OpsSection<unknown> | undefined) => (sec?.state === "NOT_CONNECTED" ? sec.source : "");

  return (
    <section className={styles.summary} aria-label="상태 요약">
      {s ? (
        <>
          <Tile icon="globe" label="외부 가용성" tone={avTone} focusRef={{ kind: "SLO", metric: "availability" }}
            delta={tr ? <DeltaChip d={deltaOf(tr.availability, "pp", "up")} range={range} /> : null}
            trend={spark(tr?.availability, avTone, pct2, "외부 가용성", { minSpan: 0.06 })}
            value={
              <span className={`${styles.tileValue} num`} data-tone={avTone}>
                {mark(avTone)}
                {s.availabilityPct ?? "◌"}
                <span className={styles.tileUnit}>%</span>
              </span>
            }
            sub={<>목표 {s.slo.availabilityPct}% · 외부 프로브 5분</>}
          />
          <Tile icon="clock" label="응답 p95" tone={p95Tone} focusRef={{ kind: "SLO", metric: "p95" }}
            delta={tr ? <DeltaChip d={deltaOf(tr.p95, "pct", "down")} range={range} /> : null}
            trend={spark(tr?.p95, p95Tone, ms0, "응답 p95", { minSpan: 60 })}
            value={
              <span className={`${styles.tileValue} num`} data-tone={p95Tone}>
                {mark(p95Tone)}
                {s.p95Ms ?? "◌"}
                <span className={styles.tileUnit}>ms</span>
              </span>
            }
            sub={<>목표 {s.slo.p95Ms}ms 이하</>}
          />
          <Tile icon="errors" label="5xx 비율" tone={errTone} focusRef={{ kind: "SLO", metric: "errors" }}
            delta={tr ? <DeltaChip d={deltaOf(tr.errors, "pp", "down")} range={range} /> : null}
            trend={spark(tr?.errors, errTone, pct2, "5xx 비율", { minSpan: 0.06 })}
            value={
              <span className={`${styles.tileValue} num`} data-tone={errTone}>
                {mark(errTone)}
                {s.errorRatePct ?? "◌"}
                <span className={styles.tileUnit}>%</span>
              </span>
            }
            sub={<>목표 {s.slo.errorRatePct}% 미만</>}
          />
        </>
      ) : (
        <>
          <Missing icon="globe" label="외부 가용성" source={source(center?.summary)} />
          <Missing icon="clock" label="응답 p95" source={source(center?.summary)} />
          <Missing icon="errors" label="5xx 비율" source={source(center?.summary)} />
        </>
      )}
      {counts && alerts ? (
        <Tile icon="incidents" label="활성 경보" tone={alertTone}
          delta={tr ? <DeltaChip d={deltaOf(tr.alerts, "count", "down")} range={range} /> : null}
          trend={spark(tr?.alerts, alertTone, (v) => `경보 ${v}건`, "활성 경보", { zero: true })}
          value={
            <span className={`${styles.tileValue} num`} data-tone={alertTone}>
              {mark(alertTone)}
              {alerts.length}
              <span className={styles.tileUnit}>건</span>
            </span>
          }
          sub={
            <span className={styles.alertCounts}>
              {(["critical", "warning", "info"] as const).map((sev) => (
                <span key={sev} className={styles.alertCount} data-sev={sev} data-zero={counts[sev] === 0 ? "" : undefined}>
                  <span aria-hidden="true">{sev === "critical" ? "■" : sev === "warning" ? "▲" : "●"}</span>
                  <b className="num">{counts[sev]}</b>
                  <span className="sr-only">{SEVERITY_WORD[sev]}</span>
                </span>
              ))}
              {/* 확인·보류가 없으면 적지 않는다. 좁은 칸에서 형태 개수를 밀어내던 줄이다 */}
              {acked + silenced > 0 && (
                <span>
                  확인 <b className="num">{acked}</b> · 보류 <b className="num">{silenced}</b>
                </span>
              )}
            </span>
          }
        />
      ) : (
        <Missing icon="incidents" label="활성 경보" source={source(center?.alerts)} />
      )}
      {inc ? (
        <Tile icon="audit" label="오늘 사건" tone={inc.open > 0 ? "warning" : undefined}
          delta={tr ? <DeltaChip d={deltaOf(tr.incidents, "count", "down")} range={range} /> : null}
          trend={spark(tr?.incidents, undefined, (v) => `사건 ${v}건`, "새 사건", { kind: "bars" })}
          value={
            <span className={`${styles.tileValue} num`}>
              {inc.count}
              <span className={styles.tileUnit}>건</span>
            </span>
          }
          sub={
            <>
              평균 복구 <b className="num">{inc.mttrMs === null ? "기록 없음" : durationWord(inc.mttrMs)}</b>
              {inc.open > 0 && (
                <>
                  {" "}
                  · 진행 <b className="num">{inc.open}</b>
                </>
              )}
            </>
          }
        />
      ) : (
        <Missing icon="audit" label="오늘 사건" source={source(center?.incidents)} />
      )}
    </section>
  );
}
