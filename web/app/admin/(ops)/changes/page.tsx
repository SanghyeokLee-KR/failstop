"use client";

/**
 * 변경·작업(/admin/changes, 관리자 설계 4-10-4). 언제 무엇을 바꾸기로 했고 그동안 무엇이 허용되는지에 답한다. 위는 두 칸
 * 1.4 : 1이다. 왼쪽은 계획 달력(월 보기), 오른쪽은 AI 보류 규칙, 진행 중인 계획 작업 창, 고른 날의 항목이다. 아래 전폭에
 * 변경 요청 표와 변경 기록(종료한 요청의 결과, 되돌리기 계획, 창 안의 조치)을 둔다. 등록과 상세는 오른쪽 560px 드로어다.
 *
 * AI의 경보 보류는 계획 작업 창 안의 주의(warning) 경보뿐이고 보류의 끝은 창의 끝을 넘지 않는다(ADR 0035, S5~S8). sim은 관제
 * 시뮬레이터의 창과 변경 요청을 읽고 쓰며, 진행 중인 창이 있으면 AI의 보류 판단도 그 창만 본다. http는 창을 서버(§6-F)에서
 * 읽고 등록·조기 종료를 서버로 보낸다. 변경 요청은 서버 경로가 3라운드 계약 초안이라 원천 연결 전이다.
 *
 * 창 안의 조치는 조치 기록(감사)에서 거른다. 관제 판에 싣지 않는 조회라 sim은 판의 기록이 바뀔 때, http는 들어올 때와
 * 새로고침 때 읽는다.
 */

import { useEffect, useMemo, useState } from "react";
import { isAdminHttp } from "@/lib/admin/api";
import {
  actionsIn,
  CHANGE_STATE_WORD,
  changeSpan,
  openPlanItems,
  planDurationWord,
  planItems,
  planSpanWord,
  RISK_WORD,
  WINDOW_RULES,
  windowTargetWord,
  type PlanItem,
} from "@/lib/admin/changes";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import { useCan } from "@/lib/admin/store";
import { kstDay, mdhm, mdw } from "@/lib/admin/timeText";
import type { ActionRecord, ChangeRequest, ControlCenter } from "@/lib/admin/types";
import CalendarView, { CALENDAR_SHAPE, CalendarLegend, CalendarSkeleton, dayStart, MonthNav, shiftMonth, type CalendarItem } from "@/components/admin/CalendarView";
import ChangeDrawer, { ActionRows, ChangeChip, EndWindowButton, WindowState, type ChangeLayer } from "@/components/admin/changes/ChangeDrawer";
import { useActionFeed } from "@/components/admin/actions/useActionFeed";
import KeepNames from "@/components/admin/KeepNames";
import { AdminErrorState } from "@/components/admin/ResourceState";
import StatusShape from "@/components/admin/StatusShape";
import OpsHead from "@/components/admin/control/OpsHead";
import { PanelHead, SectionBody, useClock } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";
import chgStyles from "@/components/admin/changes/changes.module.css";

const DAY_MS = 86_400_000;

/** 달력과 목록의 상태 낱말. 창만 등록한 항목은 창의 말(예약·열림·닫힘)이다 */
function itemWord(it: PlanItem): string {
  if (it.kind === "change") return CHANGE_STATE_WORD[it.state];
  return it.state === "APPROVED" ? "예약" : it.state === "IN_PROGRESS" ? "열림" : "닫힘";
}

function Empty({ children }: { children: string }) {
  return (
    <div className={styles.empty}>
      <span className={styles.emptyMark} aria-hidden="true" />
      {children}
    </div>
  );
}

/** 오른쪽 칸의 진행 중인 창. 변경 요청이 연 창이면 그 요청의 제목과 함께 적는다 */
function OpenWindows({ items, onOpen, onChanged }: { items: PlanItem[]; onOpen: (l: ChangeLayer) => void; onChanged: () => void }) {
  if (items.length === 0) return <Empty>진행 중인 계획 작업 창 없음</Empty>;
  return (
    <ul className={chgStyles.openList}>
      {items.map((it) => {
        const w = it.window;
        if (!w) return null;
        return (
          <li key={w.id} className={chgStyles.openItem}>
            <div className={chgStyles.openTop}>
              <WindowState w={w} />
              <span className={chgStyles.openTitle}>
                <KeepNames text={it.change ? it.change.title : w.reason} />
              </span>
            </div>
            <div className={chgStyles.openMeta}>
              <span className="num">{planSpanWord(w.startsAt, w.endsAt)}</span>
              <code className={chgStyles.code}>{windowTargetWord(w)}</code>
            </div>
            <div className={chgStyles.openActions}>
              <button
                type="button"
                className={chgStyles.inlineLink}
                aria-haspopup="dialog"
                onClick={() => onOpen(it.change ? { kind: "change", id: it.change.id } : { kind: "windowDetail", id: w.id })}
              >
                {it.change ? `${it.change.id} 상세` : `창 ${w.id} 상세`}
              </button>
              <EndWindowButton w={w} onChanged={onChanged} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}

/** 고른 날의 항목. 행 전체가 단추이고 드로어를 연다 */
function DayItems({ items, onOpen }: { items: PlanItem[]; onOpen: (l: ChangeLayer) => void }) {
  return (
    <ul className={chgStyles.dayList}>
      {items.map((it) => (
        <li key={it.id}>
          <button
            type="button"
            className={chgStyles.dayItem}
            data-state={it.state}
            aria-haspopup="dialog"
            onClick={() => onOpen(it.kind === "change" ? { kind: "change", id: it.id } : { kind: "windowDetail", id: it.id })}
          >
            <StatusShape kind={CALENDAR_SHAPE[it.state]} size={9} className={chgStyles.dayShape} />
            <span className={chgStyles.dayTitle} title={it.title}>
              {it.title}
            </span>
            <span className={chgStyles.dayState}>{itemWord(it)}</span>
            <span className={chgStyles.daySub}>
              <span className="num">{it.kind === "change" ? it.id : `창 ${it.id}`}</span>
              <span className="num">{planSpanWord(new Date(it.start).toISOString(), new Date(it.end).toISOString())}</span>
              <span>
                <KeepNames text={it.target} />
              </span>
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function RiskMark({ risk }: { risk: ChangeRequest["risk"] }) {
  return (
    <span className={chgStyles.risk} data-risk={risk}>
      <StatusShape kind={risk === "LOW" ? "circle" : "triangle"} size={9} />
      {RISK_WORD[risk]}
    </span>
  );
}

/** 변경 요청 표. 행을 누르면 드로어가 열린다. 식별 글은 왼쪽, 시각은 tabular다 */
function ChangeTable({ list, onOpen }: { list: ChangeRequest[]; onOpen: (id: string) => void }) {
  return (
    <div className={chgStyles.tableWrap}>
      <table className={chgStyles.table}>
        <colgroup>
          <col className={chgStyles.cId} />
          <col />
          <col className={chgStyles.cWhen} />
          <col className={chgStyles.cRisk} />
          <col className={chgStyles.cState} />
          <col className={chgStyles.cWho} />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">요청</th>
            <th scope="col">제목·대상</th>
            <th scope="col">계획 시각</th>
            <th scope="col">위험</th>
            <th scope="col">상태</th>
            <th scope="col">등록·승인</th>
          </tr>
        </thead>
        <tbody>
          {list.map((c) => (
            <tr key={c.id} className={chgStyles.row} onClick={() => onOpen(c.id)}>
              <th scope="row">
                <span className={chgStyles.idText}>{c.id}</span>
              </th>
              <td>
                <span className={chgStyles.line}>
                  <button
                    type="button"
                    className={chgStyles.rowBtn}
                    aria-haspopup="dialog"
                    title={c.title}
                    onClick={(e) => {
                      e.stopPropagation();
                      onOpen(c.id);
                    }}
                  >
                    {c.title}
                  </button>
                </span>
                <span className={chgStyles.sub} title={c.target}>
                  {c.target}
                </span>
              </td>
              <td className="num">
                <span className={chgStyles.line}>{mdhm(c.startsAt)}</span>
                <span className={chgStyles.sub}>{planDurationWord(Date.parse(c.endsAt) - Date.parse(c.startsAt))}</span>
              </td>
              <td>
                <RiskMark risk={c.risk} />
              </td>
              <td>
                <ChangeChip state={c.state} />
              </td>
              <td>
                <span className={chgStyles.line}>{c.requestedBy}</span>
                <span className={chgStyles.sub}>{c.approvedBy ? `승인 ${c.approvedBy}` : c.state === "CANCELED" ? "승인 없음" : "승인 대기"}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** 변경 기록. 종료한 요청마다 결과, 되돌리기 계획, 창 시각, 그 시각 안의 조치(조치 기록에서 거른 것)와 되돌림 여부다 */
function ChangeRecords({ list, center, records, sim, onOpen }: {
  list: ChangeRequest[];
  center: ControlCenter;
  records: ActionRecord[] | null;
  sim: boolean;
  onOpen: (id: string) => void;
}) {
  const windows = center.changes.state === "READY" ? center.changes.data.windows : [];
  const closed = list.filter((c) => c.state === "DONE" || c.state === "ROLLED_BACK");
  if (closed.length === 0) return <Empty>종료한 변경 요청 없음</Empty>;
  return (
    <div className={chgStyles.recordsWrap}>
      <ul className={chgStyles.records}>
        {closed.map((c) => {
          const span = changeSpan(c, windows);
          const rows = records === null ? null : actionsIn(records, span);
          const reverted = rows?.filter((r) => r.result === "REVERTED").length ?? 0;
          return (
            <li key={c.id} className={chgStyles.record}>
              <div className={chgStyles.recordMain}>
                <div className={chgStyles.recordHead}>
                  <ChangeChip state={c.state} />
                  <button type="button" className={chgStyles.recordTitle} aria-haspopup="dialog" onClick={() => onOpen(c.id)}>
                    <KeepNames text={c.title} />
                  </button>
                  <span className={chgStyles.idText}>{c.id}</span>
                </div>
                <dl className={chgStyles.recordKv}>
                  <dt>결과</dt>
                  <dd>
                    <KeepNames text={c.result ?? "기록 없음"} />
                  </dd>
                  <dt>되돌리기 계획</dt>
                  <dd>
                    <KeepNames text={c.rollback} />
                  </dd>
                  <dt>창 시각</dt>
                  <dd className="num">{planSpanWord(span.start, span.end)}</dd>
                  <dt>종료</dt>
                  <dd>
                    {c.closedBy ?? "기록 없음"}
                    {c.closedAt && (
                      <>
                        {" · "}
                        <span className="num">{mdhm(c.closedAt)}</span>
                      </>
                    )}
                  </dd>
                </dl>
              </div>
              <div className={chgStyles.recordSide}>
                <div className={chgStyles.recordSideHead}>
                  창 안의 조치
                  <span className="num">{rows === null ? "조치 기록을 읽는 중" : `${rows.length}건 · 되돌림 ${reverted}건${sim ? "" : " · 받아 온 쪽만"}`}</span>
                </div>
                {rows === null ? <Empty>조치 기록을 읽는 중입니다</Empty> : <ActionRows rows={rows} />}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export default function ChangesPage() {
  const { data, loading, refresh } = useOpsCenter();
  const sim = !isAdminHttp();
  const canWrite = useCan("BOT_OPS");
  // 목록의 열림·닫힘과 '오늘'을 가르는 시각이다. 판의 시각과 벽시계 가운데 늦은 쪽을 쓴다. 판보다 늦은 벽시계만 쓰면 방금
  // 연 창이 판에는 열렸는데 이 화면에서는 예약으로 보인다(사이드바 배지는 판의 시각으로 가른다). 남은 시간 글은 그 조각이 매초 탄다
  const clock = useClock(10_000);
  const boardAt = data ? Date.parse(data.generatedAt) : 0;
  const now = Math.max(clock, Number.isFinite(boardAt) ? boardAt : 0);
  const today = kstDay(now);
  const [month, setMonth] = useState(today.slice(0, 7));
  const [day, setDay] = useState(today);
  const [layer, setLayer] = useState<ChangeLayer | null>(null);
  // sim은 판의 기록(사건 밖 운영 기록 포함)이 바뀔 때 조치 기록을 다시 읽는다. http는 들어올 때와 새로고침 때만 읽는다
  const signal = sim && data?.incidents.state === "READY" ? data.incidents.data.events[0]?.id ?? null : null;
  const feed = useActionFeed(signal);

  // 다른 화면(SLA 주요 변경, 조치 기록)의 링크가 ?id=CHG-n 이나 ?window=W-n 으로 드로어를 연다
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    const id = q.get("id");
    const win = q.get("window");
    if (id) setLayer({ kind: "change", id });
    else if (win) setLayer({ kind: "windowDetail", id: win });
  }, []);

  const desk = data?.changes.state === "READY" ? data.changes.data : null;
  const changes = desk?.changes.state === "READY" ? desk.changes.data : null;
  const items = useMemo(() => (desk ? planItems(desk.windows, changes, now) : []), [desk, changes, now]);
  const calItems = useMemo<CalendarItem[]>(
    () =>
      items.map((it) => ({
        id: it.id,
        start: it.start,
        end: it.end,
        label: it.title,
        state: it.state,
        title: `${it.kind === "change" ? it.id : `창 ${it.id}`} · ${it.title} · ${itemWord(it)}`,
      })),
    [items],
  );
  const open = useMemo(() => openPlanItems(items, now), [items, now]);
  const dayFrom = dayStart(day);
  const dayItems = items.filter((it) => it.start < dayFrom + DAY_MS && it.end > dayFrom);
  const monthFrom = dayStart(`${month}-01`);
  const monthTo = dayStart(`${shiftMonth(month, 1)}-01`);
  const monthEmpty = !items.some((it) => it.start < monthTo && it.end > monthFrom);
  // 조작 뒤에는 판과 조치 기록을 함께 다시 읽는다. 새로 등록한 요청을 곧바로 여는 드로어는 판을 다 읽은 뒤에 연다
  const changed = async () => {
    feed.res.reload();
    await refresh(true);
  };
  const openItem = (l: ChangeLayer) => setLayer(l);
  const pending = changes?.filter((c) => c.state === "REQUESTED").length ?? 0;
  const planned = changes?.filter((c) => c.state === "APPROVED").length ?? 0;
  const running = changes?.filter((c) => c.state === "IN_PROGRESS").length ?? 0;
  const changesConnected = desk?.changes.state === "READY";

  const head = (
    <OpsHead
      title="변경·작업"
      center={data}
      actions={
        <>
          <button
            type="button"
            className={adminStyles.neutralBtn}
            disabled={!canWrite || desk === null || !changesConnected}
            title={!changesConnected && desk !== null ? "변경 요청 원천 연결 전" : !canWrite ? "봇 운영(BOT_OPS) 이상" : undefined}
            aria-haspopup="dialog"
            onClick={() => setLayer({ kind: "new" })}
          >
            변경 요청 등록
          </button>
          <button
            type="button"
            className={adminStyles.neutralBtn}
            disabled={!canWrite || desk === null}
            title={!canWrite ? "봇 운영(BOT_OPS) 이상" : undefined}
            aria-haspopup="dialog"
            onClick={() => setLayer({ kind: "window" })}
          >
            창만 등록
          </button>
        </>
      }
    />
  );

  if (data === null) {
    return (
      <div className={adminStyles.page}>
        {head}
        {loading ? (
          <div className={styles.split} data-ratio="wide">
            <section className={styles.panel} aria-label="계획 달력">
              <PanelHead title="계획 달력" />
              <CalendarSkeleton label="계획 달력을 불러오는 중입니다" />
            </section>
            <div className={styles.stack} aria-hidden="true">
              {[3, 2, 4].map((n, i) => (
                <div key={i} className={styles.skCard}>
                  <i className={styles.bone} style={{ width: "38%", height: 14 }} />
                  {Array.from({ length: n }, (_, j) => (
                    <i key={j} className={styles.bone} style={{ width: `${86 - j * 12}%`, height: 10 }} />
                  ))}
                </div>
              ))}
            </div>
          </div>
        ) : (
          <AdminErrorState onRetry={() => void refresh()} />
        )}
      </div>
    );
  }

  return (
    <div className={adminStyles.page}>
      {head}
      <SectionBody section={data.changes} what="계획 작업 창">
        {(d) => (
          <>
            <div className={styles.split} data-ratio="wide">
              <section className={styles.panel} aria-labelledby="chg-cal">
                <PanelHead id="chg-cal" title="계획 달력" actions={<MonthNav month={month} today={today} onMonth={setMonth} />} />
                <CalendarView
                  month={month}
                  today={today}
                  selected={day}
                  onSelect={(dd) => {
                    setDay(dd);
                    if (dd.slice(0, 7) !== month) setMonth(dd.slice(0, 7));
                  }}
                  onMonth={setMonth}
                  items={calItems}
                  onItem={(id) => {
                    const it = items.find((x) => x.id === id);
                    if (it) openItem(it.kind === "change" ? { kind: "change", id } : { kind: "windowDetail", id });
                  }}
                  label={`${month.slice(0, 4)}년 ${Number(month.slice(5, 7))}월 계획 달력 · 화살표로 날을 옮기고 Enter로 고름`}
                />
                <div className={chgStyles.calFoot}>
                  <CalendarLegend />
                  <span className={chgStyles.calNote}>
                    {changesConnected ? "막대를 누르면 상세가 열립니다" : "변경 요청 원천 연결 전 · 달력은 계획 작업 창만 싣습니다"}
                  </span>
                </div>
              </section>

              <div className={styles.stack}>
                <section className={styles.panel} aria-labelledby="chg-rules">
                  <PanelHead id="chg-rules" title="AI 보류 규칙" meta="ADR 0035 · 창 정책 S5~S8" />
                  <ol className={chgStyles.rules}>
                    {WINDOW_RULES.map((r) => (
                      <li key={r}>{r}</li>
                    ))}
                  </ol>
                </section>
                <section className={styles.panel} aria-labelledby="chg-open">
                  <PanelHead id="chg-open" title="진행 중인 계획 작업 창" meta={open.length > 0 ? <span className="num">{open.length}개</span> : undefined} />
                  <OpenWindows items={open} onOpen={openItem} onChanged={changed} />
                </section>
                <section className={styles.panel} aria-labelledby="chg-day">
                  <PanelHead
                    id="chg-day"
                    title={
                      <>
                        <span className="num">{mdw(new Date(dayFrom + 12 * 3_600_000).toISOString())}</span> 항목
                      </>
                    }
                    meta={day === today ? "오늘" : undefined}
                  />
                  {monthEmpty ? <Empty>이번 달 계획 없음</Empty> : dayItems.length === 0 ? <Empty>이날 계획 없음</Empty> : <DayItems items={dayItems} onOpen={openItem} />}
                </section>
              </div>
            </div>

            <section className={styles.panel} aria-labelledby="chg-requests">
              <PanelHead
                id="chg-requests"
                title="변경 요청"
                meta={
                  changesConnected ? (
                    <span className="num">
                      승인 대기 {pending} · 예정 {planned} · 진행 중 {running}
                    </span>
                  ) : undefined
                }
              />
              <SectionBody section={d.changes} what="변경 요청">
                {(list) => (list.length === 0 ? <Empty>등록한 변경 요청 없음</Empty> : <ChangeTable list={list} onOpen={(id) => openItem({ kind: "change", id })} />)}
              </SectionBody>
            </section>

            <section className={styles.panel} aria-labelledby="chg-records">
              <PanelHead id="chg-records" title="변경 기록" meta="종료한 변경 요청 · 창 안의 조치는 조치 기록에서 거릅니다" />
              <SectionBody section={d.changes} what="변경 기록">
                {(list) => <ChangeRecords list={list} center={data} records={feed.rows} sim={sim} onOpen={(id) => openItem({ kind: "change", id })} />}
              </SectionBody>
            </section>
          </>
        )}
      </SectionBody>

      {layer && (
        <ChangeDrawer
          layer={layer}
          center={data}
          records={feed.rows}
          sim={sim}
          onClose={() => setLayer(null)}
          onChanged={changed}
          onOpen={setLayer}
        />
      )}
    </div>
  );
}
