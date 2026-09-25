"use client";

/**
 * 상태 요약 다섯 칸(§12-9). 칸마다 아이콘, 큰 수치와 흐린 단위, 증감, 작은 추세선, 목표·보조 줄을 둔다. 목표를
 * 벗어난 칸만 형태와 헬스 색을 받고 정상 칸은 잉크로 둔다. 증감은 지금 값과 범위의 6분의 1 앞 시점 값의 차이이고
 * (opsTrends.trendDelta), 색은 좋아졌는지로 정하되 칸의 상태색을 넘지 않는다. 목표를 벗어난 동안에는 증감 대신
 * 벗어난 시간을 적는다. 추세는 화면 머리에서 고른 범위(1·6·24시간)이고 탐색은 Grafana로 보낸다.
 */

import { memo, type ReactNode } from "react";
import type { IncidentLog, OpsAlert, OpsRef, OpsSection, OpsSummary, OpsTrends, OpsTrendSeries, TrendRange } from "@/lib/admin/types";
import { refMatches, useOpsFocus } from "@/lib/admin/opsCenter";
import { flashTone } from "@/lib/admin/motion";
import { breachMinutes, DELTA_BACK_MIN, trendDelta, type TrendDelta } from "@/lib/admin/opsTrends";
import { kstDay } from "@/lib/admin/timeText";
import { AdminIcon, type AdminIconName } from "../icons";
import StatusShape from "../StatusShape";
import { useChangeFlags } from "../useChangeFlags";
import { durationWord, FlashNum, SEVERITY_WORD, useClock } from "./parts";
import Sparkline from "./Sparkline";
import styles from "./control.module.css";

type Tone = "warning" | "danger" | undefined;

/** 범위별 점 사이 간격(분). opsTrends.ts 의 점 개수와 짝이다 */
const STEP_MIN: Record<TrendRange, number> = { 1: 1, 6: 5, 24: 15 };

/** 비교 시점의 말. 1시간 범위는 '10분 전', 그 위는 시간이다 */
function backWord(range: TrendRange): string {
  const m = DELTA_BACK_MIN[range];
  return m < 60 ? `${m}분 전` : `${m / 60}시간 전`;
}

function DeltaChip({ d, range }: { d: TrendDelta | null; range: TrendRange }) {
  if (d === null) {
    return (
      <span className={styles.delta} data-good="none">
        비교 기록 없음
      </span>
    );
  }
  const word = d.dir === "flat" ? "변화 없음" : `${d.dir === "up" ? "늘었습니다" : "줄었습니다"}${d.good ? ", 좋아졌습니다" : ", 나빠졌습니다"}`;
  return (
    <span className={`${styles.delta} num`} data-good={d.good === null ? "flat" : d.good ? "good" : "bad"} title={`${backWord(range)} 대비`}>
      <span aria-hidden="true">
        {d.dir === "up" ? "▲ " : d.dir === "down" ? "▼ " : ""}
        {d.text}
      </span>
      <span className="sr-only">
        {backWord(range)} 대비 {d.text === "±0" ? "" : d.text} {word}
      </span>
    </span>
  );
}

/** 목표를 벗어난 칸의 증감 자리. 칸의 상태색으로 벗어난 시간을 적는다 */
function Breach({ tone, word, minutes }: { tone: "warning" | "danger"; word: string; minutes: number }) {
  return (
    <span className={`${styles.delta} num`} data-tone={tone}>
      {word} {minutes}분째
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
  // 칸이 목표를 벗어나거나 돌아오는 순간 링 펄스를 건다(§12-4 카드 상태 전이). 처음 그린 상태는 전이가 아니다
  const pulse = useChangeFlags({ tone: tone ?? "ok" }, 1800).tone;
  return (
    <div className={styles.tile} data-tone={tone} data-focus={lit ? "" : undefined} data-pulse={pulse ? "" : undefined}>
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

/** 오늘 사건 수와 해제된 사건의 평균 복구 시간. 오늘은 KST 날짜 기준이다(§12-12) */
export function todayIncidents(log: IncidentLog, now: number): { count: number; open: number; mttrMs: number | null } {
  const today = kstDay(now);
  const list = log.incidents.filter((i) => kstDay(Date.parse(i.openedAt)) === today);
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

// 추세선이 memo라 표기 함수는 모듈에 둔다. 칸 안에서 만들면 그릴 때마다 새 함수라 추세선이 매번 다시 그린다
const pct2 = (v: number) => `${v.toFixed(2)}%`;
const ms0 = (v: number) => `${Math.round(v)}ms`;
const alertsN = (v: number) => `경보 ${v}건`;
const incidentsN = (v: number) => `사건 ${v}건`;

/** 요약에 쓰는 네 칸만 받는다. 칸이 바뀔 때만 다시 그린다 */
function SummaryStrip({ summary, alerts: alertSec, incidents, trends, range }: {
  summary: OpsSection<OpsSummary>;
  alerts: OpsSection<OpsAlert[]>;
  incidents: OpsSection<IncidentLog>;
  trends: OpsSection<OpsTrends>;
  range: TrendRange;
}) {
  // '오늘'의 경계만 읽으므로 분 단위로만 다시 그린다
  const now = useClock(60_000);
  const s = summary.state === "READY" ? summary.data : null;
  const alerts = alertSec.state === "READY" ? alertSec.data : null;
  const log = incidents.state === "READY" ? incidents.data : null;
  const tr = trends.state === "READY" ? trends.data[range] : null;
  const avTone: Tone = s && s.availabilityPct !== null && s.availabilityPct < s.slo.availabilityPct ? "danger" : undefined;
  const p95Tone: Tone = s && s.p95Ms !== null && s.p95Ms > s.slo.p95Ms ? "warning" : undefined;
  const errTone: Tone = s && s.errorRatePct !== null && s.errorRatePct >= s.slo.errorRatePct ? "warning" : undefined;
  const counts = alerts ? alertCounts(alerts) : null;
  const alertTone: Tone = counts ? (counts.critical > 0 ? "danger" : counts.warning > 0 ? "warning" : undefined) : undefined;
  const acked = alerts ? alerts.filter((a) => a.state === "ACKED").length : 0;
  const silenced = alerts ? alerts.filter((a) => a.state === "SILENCED").length : 0;
  const inc = log ? todayIncidents(log, now) : null;
  const step = STEP_MIN[range];
  // 벗어난 시간은 분 단위 점열(1시간 범위)에서 센다. 고른 범위와 무관하다
  const minute = trends.state === "READY" ? trends.data[1] : null;
  const firstAlert = alerts && alerts.length > 0 ? Math.min(...alerts.map((a) => Date.parse(a.since))) : null;
  const openInc = log ? log.incidents.find((i) => i.resolvedAt === null) ?? null : null;
  const sinceMin = (t: number) => Math.max(1, Math.floor((now - t) / 60_000) + 1);
  // 축의 최소 폭은 그 지표에서 눈여겨볼 변화의 크기다. 가용성·5xx는 0.06%p, p95는 60ms다
  const spark = (series: OpsTrendSeries | undefined, tone: Tone, format: (v: number) => string, what: string, opt: { kind?: "bars"; minSpan?: number; zero?: boolean } = {}) =>
    series ? (
      <Sparkline points={series.points} tone={tone} format={format} stepMin={step} label={`지난 ${range}시간 ${what}`} kind={opt.kind} minSpan={opt.minSpan} zero={opt.zero} />
    ) : null;
  const mark = (tone: Tone) =>
    tone && (
      <StatusShape kind={tone === "danger" ? "square" : "triangle"} size={14} className={styles.tileMark} />
    );
  const source = (sec: OpsSection<unknown>) => (sec.state === "NOT_CONNECTED" ? sec.source : "");

  return (
    <section className={styles.summary} aria-label="상태 요약">
      {s ? (
        <>
          <Tile icon="globe" label="외부 가용성" tone={avTone} focusRef={{ kind: "SLO", metric: "availability" }}
            delta={
              avTone && minute ? (
                <Breach tone={avTone} word="목표 미달" minutes={breachMinutes(minute.availability.points, (v) => v < s.slo.availabilityPct)} />
              ) : tr ? (
                <DeltaChip d={trendDelta("availability", tr.availability, range, s.availabilityPct)} range={range} />
              ) : null
            }
            trend={spark(tr?.availability, avTone, pct2, "외부 가용성", { minSpan: 0.06 })}
            value={
              <span className={`${styles.tileValue} num`} data-tone={avTone}>
                {mark(avTone)}
                {s.availabilityPct === null ? "◌" : s.availabilityPct.toFixed(2)}
                <span className={styles.tileUnit}>%</span>
              </span>
            }
            sub={<>목표 {s.slo.availabilityPct}% · 외부 프로브 5분</>}
          />
          <Tile icon="clock" label="응답 p95" tone={p95Tone} focusRef={{ kind: "SLO", metric: "p95" }}
            delta={
              p95Tone && minute ? (
                <Breach tone={p95Tone} word="목표 초과" minutes={breachMinutes(minute.p95.points, (v) => v > s.slo.p95Ms)} />
              ) : tr ? (
                <DeltaChip d={trendDelta("p95", tr.p95, range, s.p95Ms)} range={range} />
              ) : null
            }
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
            delta={
              errTone && minute ? (
                <Breach tone={errTone} word="목표 초과" minutes={breachMinutes(minute.errors.points, (v) => v >= s.slo.errorRatePct)} />
              ) : tr ? (
                <DeltaChip d={trendDelta("errors", tr.errors, range, s.errorRatePct)} range={range} />
              ) : null
            }
            trend={spark(tr?.errors, errTone, pct2, "5xx 비율", { minSpan: 0.06 })}
            value={
              <span className={`${styles.tileValue} num`} data-tone={errTone}>
                {mark(errTone)}
                {s.errorRatePct === null ? "◌" : s.errorRatePct.toFixed(2)}
                <span className={styles.tileUnit}>%</span>
              </span>
            }
            sub={<>목표 {s.slo.errorRatePct}% 미만</>}
          />
        </>
      ) : (
        <>
          <Missing icon="globe" label="외부 가용성" source={source(summary)} />
          <Missing icon="clock" label="응답 p95" source={source(summary)} />
          <Missing icon="errors" label="5xx 비율" source={source(summary)} />
        </>
      )}
      {counts && alerts ? (
        <Tile icon="incidents" label="활성 경보" tone={alertTone}
          delta={
            alertTone && firstAlert !== null ? (
              <Breach tone={alertTone} word="첫 경보" minutes={sinceMin(firstAlert)} />
            ) : tr ? (
              <DeltaChip d={trendDelta("alerts", tr.alerts, range, alerts.length)} range={range} />
            ) : null
          }
          trend={spark(tr?.alerts, alertTone, alertsN, "활성 경보", { zero: true })}
          value={
            <span className={`${styles.tileValue} num`} data-tone={alertTone}>
              {mark(alertTone)}
              <FlashNum value={alerts.length} tone={flashTone} />
              <span className={styles.tileUnit}>건</span>
            </span>
          }
          sub={
            <span className={styles.alertCounts}>
              {(["critical", "warning", "info"] as const).map((sev) => (
                <span key={sev} className={styles.alertCount} data-sev={sev} data-zero={counts[sev] === 0 ? "" : undefined}>
                  <span aria-hidden="true"><StatusShape kind={sev === "critical" ? "square" : sev === "warning" ? "triangle" : "circle"} /></span>
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
        <Missing icon="incidents" label="활성 경보" source={source(alertSec)} />
      )}
      {inc ? (
        <Tile icon="audit" label="오늘 사건" tone={inc.open > 0 ? "warning" : undefined}
          delta={
            inc.open > 0 && openInc ? (
              <Breach tone="warning" word="진행" minutes={sinceMin(Date.parse(openInc.openedAt))} />
            ) : tr ? (
              <DeltaChip d={trendDelta("incidents", tr.incidents, range, inc.count)} range={range} />
            ) : null
          }
          trend={spark(tr?.incidents, undefined, incidentsN, "새 사건", { kind: "bars" })}
          value={
            <span className={`${styles.tileValue} num`}>
              <FlashNum value={inc.count} tone={(prev, next) => (next > prev ? "warning" : null)} />
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
        <Missing icon="audit" label="오늘 사건" source={source(incidents)} />
      )}
    </section>
  );
}

export default memo(SummaryStrip);
