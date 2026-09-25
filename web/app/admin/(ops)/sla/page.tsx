"use client";

/**
 * SLA 월간 보고(/admin/sla, 관리자 설계 4-10-5). 이번 달 약속한 서비스 수준을 지켰는지에 답한다. 고객사에 내는 MSP 월간 보고의
 * 순서(요약, 서비스 수준, 사건, 변경, 자동화, 권고)를 따른다. 가용성은 외부 프로브 성공률이고 주문 API SLO(API 설계 §7-4)와 섞지
 * 않는다.
 *
 * 달을 골라 읽으므로 관제 판에 싣지 않고 관리 어댑터의 조회(getSlaMonth)를 읽는다. sim은 관제 판의 기록이 바뀔 때와 분이 바뀔 때
 * 다시 읽어 오늘 줄이 경보·사건 화면의 오늘 값과 어긋나지 않게 한다. http는 월 집계 경로가 계약 초안이라 판 전체가 원천 연결 전이다.
 * 이 화면은 보고를 보내지 않는다(보내는 일은 보고 서비스의 몫이다).
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { useOpsCenter, useOpsPick } from "@/lib/admin/opsCenter";
import { monthOf, prevMonth } from "@/lib/admin/sla";
import { durationWord, kstDay, mdhm } from "@/lib/admin/timeText";
import type { SlaDay, SlaIncident, SlaMonth } from "@/lib/admin/types";
import CalendarView from "@/components/admin/CalendarView";
import KeepNames from "@/components/admin/KeepNames";
import StatusShape from "@/components/admin/StatusShape";
import { AdminErrorState } from "@/components/admin/ResourceState";
import OpsHead from "@/components/admin/control/OpsHead";
import PageSkeleton from "@/components/admin/control/PageSkeleton";
import { PanelHead, SectionGap } from "@/components/admin/control/parts";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { Segmented, StateChip, StatStrip, StatTile } from "@/components/admin/ui";
import adminStyles from "@/components/admin/admin.module.css";
import controlStyles from "@/components/admin/control/control.module.css";
import styles from "@/components/admin/sla/sla.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";
const SEV_SHAPE = { critical: "square", warning: "triangle", info: "circle" } as const;
const SEV_TONE = { critical: "danger", warning: "warning", info: "info" } as const;
const PM_WORD: Record<SlaIncident["postmortem"], string> = { DONE: "완료", DRAFT: "초안", NONE: "작성 전" };

function Head({ month, which, onWhich }: { month: string; which: "this" | "last"; onWhich: (w: "this" | "last") => void }) {
  const { data } = useOpsCenter();
  const [y, m] = month.split("-").map(Number);
  return (
    <OpsHead
      title="SLA 월간 보고"
      center={data}
      actions={
        <span className={styles.monthPick}>
          <span className={`${styles.monthWord} num`} aria-live="polite">
            {y}년 {m}월
          </span>
          <Segmented
            items={[
              { value: "this", label: "이번 달" },
              { value: "last", label: "지난달" },
            ]}
            value={which}
            onChange={onWhich}
            ariaLabel="보고 달"
            semantics="pressed"
          />
        </span>
      }
    />
  );
}

const minWord = (v: number) => `${v.toFixed(1)}분`;

function Summary({ s }: { s: SlaMonth }) {
  const av = s.availabilityPct;
  const used = s.budget.usedMin;
  const ratio = used === null ? null : used / s.budget.allowedMin;
  const first = Number(s.throughDay.slice(8, 10));
  const span = s.throughDay.slice(0, 7) === s.month && first === 1 ? "이번 달 기록 1일째" : `1~${first}일`;
  const ai = s.ai;
  return (
    <StatStrip label="SLA 요약" count={6}>
      <StatTile
        label="외부 가용성"
        value={av === null ? "기록 없음" : av.toFixed(2)}
        unit="%"
        hero
        tone={av !== null && av < s.targetPct ? "warning" : undefined}
        sub={`목표 ${s.targetPct.toFixed(2)}% · ${span}`}
      />
      <StatTile
        label="오류 예산 사용"
        value={used === null ? "기록 없음" : used.toFixed(1)}
        unit="분"
        tone={ratio !== null && ratio >= 1 ? "danger" : ratio !== null && ratio >= 0.8 ? "warning" : undefined}
        sub={`허용 ${s.budget.allowedMin}분 · ${ratio === null ? "사용 기록 없음" : `${Math.round(ratio * 1000) / 10}% 사용`}`}
        meter={used === null ? undefined : { value: Math.min(used, s.budget.allowedMin), max: s.budget.allowedMin, threshold: s.budget.allowedMin * 0.8 }}
      />
      <StatTile
        label="사건"
        value={s.incidents.total}
        unit="건"
        sub={`장애 ${s.incidents.bySeverity.critical} · 주의 ${s.incidents.bySeverity.warning} · 사후 분석 ${s.incidents.postmortems}`}
      />
      <StatTile label="평균 확인 시간(MTTA)" value={s.incidents.mttaMs === null ? "기록 없음" : durationWord(s.incidents.mttaMs)} sub="운영자의 첫 확인·결정까지" />
      <StatTile label="평균 해제 시간(MTTR)" value={s.incidents.mttrMs === null ? "기록 없음" : durationWord(s.incidents.mttrMs)} sub="발생에서 해제까지" />
      <StatTile
        label="AI 조치"
        value={ai.runs}
        unit="회"
        sub={`그 가운데 자동 ${ai.autoRuns} · 되돌림 ${ai.reverts}`}
      />
    </StatStrip>
  );
}

/**
 * 달력의 날이 어디서 왔는지. 관제 판이 닿는 날(지난 48시간이 걸친 날)은 달 끝의 이어진 며칠이라 경계 하루로 적는다. 칸 안의 '관제
 * 판' 표지는 달력이 좁으면 숨으므로 이 글이 늘 경계를 알린다
 */
function sourceWord(days: SlaDay[]): string {
  const first = days.find((d) => d.source === "BOARD");
  if (!first) return "모두 시뮬레이터 예시 날입니다";
  if (days[0].source === "BOARD") return "모두 관제 판의 기록으로 센 날입니다";
  return `${Number(first.day.slice(8, 10))}일부터 관제 판의 기록으로 세고, 그 앞은 시뮬레이터 예시 날입니다`;
}

function DayCell({ d, target }: { d: SlaDay; target: number }) {
  const low = d.availabilityPct !== null && d.availabilityPct < target;
  return (
    <span className={styles.day} title={d.source === "SEED" ? "시뮬레이터 예시 날" : "관제 판의 사건 기록과 추세로 센 날"}>
      <span className={`${styles.dayAv} num`} data-low={low ? "" : undefined}>
        {d.availabilityPct === null ? "-" : d.availabilityPct >= 99.995 ? "100%" : `${d.availabilityPct.toFixed(2)}%`}
      </span>
      <span className={styles.dayMeta}>
        <StatusShape kind={low ? "triangle" : "circle"} tone={low ? "warning" : "ink"} size={8} className={styles.shape} />
        {/* 달력 칸이 좁으면(1280 창) 사건 글을 수와 '건'으로 줄인다 */}
        <span className={`${styles.dayWide} num`}>{d.incidents > 0 ? `사건 ${d.incidents}` : "사건 없음"}</span>
        <span className={`${styles.dayNarrow} num`}>{d.incidents}건</span>
      </span>
      {d.source === "BOARD" && <span className={styles.dayBoard}>관제 판</span>}
    </span>
  );
}

function IncidentTable({ rows, day, onClear }: { rows: SlaIncident[]; day: string | null; onClear: () => void }) {
  const list = day === null ? rows : rows.filter((r) => kstDay(Date.parse(r.openedAt)) === day);
  return (
    <section className={controlStyles.panel} aria-labelledby="sla-inc">
      <PanelHead
        id="sla-inc"
        title="사건"
        meta={<span className="num">{day === null ? `이 달 ${rows.length}건` : `${Number(day.slice(5, 7))}.${Number(day.slice(8, 10))} · ${list.length}건`}</span>}
        actions={
          day !== null ? (
            <button type="button" className={styles.clear} onClick={onClear}>
              이 달 모두 보기
            </button>
          ) : undefined
        }
      />
      {list.length === 0 ? (
        <div className={controlStyles.empty}>
          <span className={controlStyles.emptyMark} aria-hidden="true" />
          {day === null ? "이 달 사건 없음" : "이날 사건 없음"}
        </div>
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th scope="col">연 때</th>
                <th scope="col">사건</th>
                <th scope="col" data-num="">
                  확인까지
                </th>
                <th scope="col" data-num="">
                  해제까지
                </th>
                <th scope="col">사후 분석</th>
              </tr>
            </thead>
            <tbody>
              {list.slice(0, 12).map((r) => (
                <tr key={r.id} data-seed={r.source === "SEED" ? "" : undefined}>
                  <td className="num">{mdhm(r.openedAt)}</td>
                  <td>
                    {r.source === "BOARD" ? (
                      <button type="button" className={styles.rowLink} aria-haspopup="dialog" onClick={() => useOpsDetail.getState().openIncident(r.id)}>
                        <StatusShape kind={SEV_SHAPE[r.severity]} tone={SEV_TONE[r.severity]} className={styles.shape} />
                        <span>
                          <KeepNames text={r.title} />
                        </span>
                      </button>
                    ) : (
                      <span className={styles.rowText} title="시뮬레이터 예시 사건 · 관제 판 밖이라 드로어가 없습니다">
                        <StatusShape kind={SEV_SHAPE[r.severity]} tone={SEV_TONE[r.severity]} className={styles.shape} />
                        {/* 형태 옆 글은 한 덩어리로 흐른다. 이름 토막이 따로 선 칸이 되지 않게 감싼다 */}
                        <span>
                          <KeepNames text={r.title} />
                          <span className={styles.seedTag}>예시</span>
                        </span>
                      </span>
                    )}
                    {r.pagedLevel >= 2 && <span className={styles.paged}>확인 전 {r.pagedLevel === 3 ? "책임자" : "2차"} 호출</span>}
                  </td>
                  <td data-num="" className="num">
                    {r.ttaMs === null ? "확인 없음" : durationWord(r.ttaMs)}
                  </td>
                  <td data-num="" className="num">
                    {r.ttrMs === null ? "진행 중" : durationWord(r.ttrMs)}
                  </td>
                  <td>{PM_WORD[r.postmortem]}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {list.length > 12 && <p className={styles.note}>최근 12건만 적습니다 · 이 달 {list.length}건</p>}
        </div>
      )}
    </section>
  );
}

function Changes({ s }: { s: SlaMonth }) {
  return (
    <section className={controlStyles.panel} aria-labelledby="sla-chg">
      <PanelHead id="sla-chg" title="주요 변경" meta={<span className="num">완료 {s.changes.done} · 되돌림 {s.changes.rolledBack}</span>} />
      {s.changeRows.length === 0 ? (
        <div className={controlStyles.empty}>
          <span className={controlStyles.emptyMark} aria-hidden="true" />이 달 끝난 변경 없음
        </div>
      ) : (
        <ul className={styles.changes}>
          {s.changeRows.slice(0, 8).map((c) => (
            <li key={c.id} className={styles.change}>
              <span className={`${styles.changeId} num`}>{c.id}</span>
              <span className={styles.changeBody}>
                {c.source === "BOARD" ? (
                  <Link href={`/admin/changes?id=${c.id}`} className={styles.changeLink}>
                    <KeepNames text={c.title} />
                  </Link>
                ) : (
                  <span className={styles.changeTitle}>
                    <KeepNames text={c.title} />
                    <span className={styles.seedTag}>예시</span>
                  </span>
                )}
                {c.result && (
                  <span className={styles.changeResult}>
                    <KeepNames text={c.result} />
                  </span>
                )}
              </span>
              <span className={styles.changeState}>
                {c.state === "ROLLED_BACK" ? <StateChip tone="warning" label="되돌림" /> : <StateChip tone="success" label="완료" />}
                <span className="num">{mdhm(c.closedAt)}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Recommendations({ s }: { s: SlaMonth }) {
  return (
    <section className={controlStyles.panel} aria-labelledby="sla-rec">
      <PanelHead id="sla-rec" title="다음 달 권고" meta="이 달의 수에서 나온 문장만 적습니다" />
      {s.recommendations.length === 0 ? (
        <div className={controlStyles.empty}>
          <span className={controlStyles.emptyMark} aria-hidden="true" />
          권고 규칙에 걸린 항목 없음
        </div>
      ) : (
        <ol className={styles.recs}>
          {s.recommendations.map((r, i) => (
            <li key={r.rule + r.basis} className={styles.rec}>
              <span className={`${styles.recNo} num`}>{i + 1}</span>
              <span className={styles.recBody}>
                <b>
                  <KeepNames text={r.basis} />
                </b>
                <span>
                  <KeepNames text={r.text} />
                </span>
              </span>
            </li>
          ))}
        </ol>
      )}
      <p className={styles.note}>규칙은 오류 예산 80% 이상, 같은 경보로 세 번 이상 연 사건, AI 자동 되돌림, 확인 없이 2차 이상까지 간 호출, 되돌린 변경 요청입니다.</p>
    </section>
  );
}

function AiBox({ s }: { s: SlaMonth }) {
  const ai = s.ai;
  return (
    <section className={controlStyles.panel} aria-labelledby="sla-ai">
      <PanelHead id="sla-ai" title="AI 자동화" meta="감사 기록에서 셈" />
      <dl className={styles.aiFacts}>
        <dt>승인·자동 실행</dt>
        <dd className="num">{ai.runs}회</dd>
        <dt>그 가운데 자동 실행</dt>
        <dd className="num">{ai.autoRuns}회</dd>
        <dt>되돌림</dt>
        <dd className="num">
          {ai.reverts}회{ai.autoReverts > 0 ? ` · 자동 ${ai.autoReverts}` : ""}
        </dd>
        {ai.recent && (
          <>
            <dt>지난 24시간</dt>
            <dd className="num">
              {ai.recent.runs}회 · 되돌림 {ai.recent.reverts}
            </dd>
          </>
        )}
      </dl>
      <div className={styles.links}>
        <Link href="/admin/actions?actor=AI" className={controlStyles.stripLink}>
          조치 기록에서 AI만 보기
        </Link>
        <Link href="/admin/ai" className={controlStyles.stripLink}>
          AI 판단
        </Link>
      </div>
    </section>
  );
}

export default function SlaPage() {
  const [which, setWhich] = useState<"this" | "last">("this");
  const [day, setDay] = useState<string | null>(null);
  // 관제 판의 기록이 바뀌거나 분이 바뀌면 다시 읽는다. 오늘 줄이 경보·사건 화면과 같은 판을 보게 한다
  const signal = useOpsPick((c) => {
    if (!c) return "";
    const log = c.incidents.state === "READY" ? c.incidents.data : null;
    return `${log?.events.length ?? 0}:${log?.events[0]?.id ?? ""}:${Math.floor(Date.parse(c.generatedAt) / 60_000)}`;
  });
  const [now, setNow] = useState(() => Date.now());
  const month = which === "this" ? monthOf(now) : prevMonth(monthOf(now));
  const load = useCallback(() => getAdminApi().getSlaMonth(month), [month]);
  const res = useAdminResource(load, { isSessionError });
  const reload = res.reload;
  useEffect(() => {
    setNow(Date.now());
    reload();
  }, [signal, month, reload]);
  useEffect(() => setDay(null), [month]);

  const s = res.data?.state === "READY" ? res.data.data : null;
  const dayOf = useMemo(() => new Map((s?.days ?? []).map((d) => [d.day, d])), [s]);
  const today = kstDay(now);

  return (
    <div className={adminStyles.page}>
      <Head month={month} which={which} onWhich={setWhich} />
      {res.loading ? (
        <PageSkeleton tiles={6} columns={[1, 1]} rows={[4, 4]} label="SLA 월간 보고를 불러오는 중입니다" />
      ) : res.error || res.data === null ? (
        <AdminErrorState reason={res.errorReason} onRetry={res.reload} />
      ) : res.data.state !== "READY" ? (
        <div className={styles.gap}>
          <SectionGap section={res.data} what="SLA 월 집계" />
          <p className={styles.note}>외부 프로브가 없어 가용성 실적의 원천부터 없습니다. 목표 99.5%와 오류 예산 식은 관리자 설계 4-10-5에 있습니다.</p>
        </div>
      ) : (
        s && (
          <div className={adminStyles.pageStack}>
            <Summary s={s} />
            <div className={styles.grid}>
              <section className={controlStyles.panel} aria-labelledby="sla-cal">
                <PanelHead id="sla-cal" title="일별 가용성" meta={<span className="num">목표 {s.targetPct.toFixed(2)}%</span>} />
                <CalendarView
                  month={s.month}
                  today={today}
                  selected={day}
                  onSelect={(d) => setDay((cur) => (cur === d || d > s.throughDay || d.slice(0, 7) !== s.month ? null : d))}
                  onMonth={(m) => {
                    if (m === monthOf(now)) setWhich("this");
                    else if (m === prevMonth(monthOf(now))) setWhich("last");
                  }}
                  renderDay={(d) => {
                    const row = dayOf.get(d);
                    return row ? <DayCell d={row} target={s.targetPct} /> : null;
                  }}
                  dayLabel={(d) => {
                    const row = dayOf.get(d);
                    if (!row) return "기록 전";
                    return `${row.availabilityPct === null ? "가용성 기록 없음" : `가용성 ${row.availabilityPct.toFixed(2)}%`} · 사건 ${row.incidents}건 · ${row.source === "SEED" ? "예시" : "관제 판"}`;
                  }}
                  label={`${s.month} 일별 가용성 · 날을 고르면 그날 사건만 봅니다`}
                />
                <p className={styles.legend}>
                  <span>
                    <StatusShape kind="circle" tone="ink" size={8} className={styles.shape} /> 목표 이상
                  </span>
                  <span>
                    <StatusShape kind="triangle" tone="warning" size={8} className={styles.shape} /> 목표 미만
                  </span>
                  <span>{sourceWord(s.days)}</span>
                </p>
              </section>
              <div className={styles.stack}>
                <IncidentTable rows={s.incidentRows} day={day} onClear={() => setDay(null)} />
                <Changes s={s} />
              </div>
            </div>
            <div className={styles.bottom}>
              <AiBox s={s} />
              <Recommendations s={s} />
            </div>
            <p className={styles.note}>
              가용성은 외부 프로브 성공률입니다. 주문 API SLO 99.9%(API 설계 §7-4)와 재는 지표가 달라 섞지 않습니다. 목표 99.5%는 관제 시뮬레이터의
              목표값이고 서버의 목표 설정은 결정 대기입니다. 오류 예산은 그 달의 분에 0.5%를 곱한 값입니다.
            </p>
          </div>
        )
      )}
    </div>
  );
}
