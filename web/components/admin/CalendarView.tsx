"use client";

/**
 * 달력 보기(디자인 시스템 §12-15 CalendarView). 변경·작업의 계획 달력과 SLA 월간 보고의 일별 가용성이 같은 부품을 쓴다.
 * 월요일에 시작하는 7열 격자이고 날짜의 경계는 KST다. 여러 날에 걸친 항목은 칸 경계를 넘어 이어진 막대로 그리고 주가 바뀌면
 * 끊어 다음 줄에서 잇는다. 한 칸에 셋을 넘으면 '+n'으로 줄이고 그날 목록은 고른 날의 목록이 싣는다.
 *
 * 격자 의미(role=grid)다. 화살표 키로 날을 옮기고 PageUp·PageDown으로 달을, Home·End로 주의 처음과 끝을 옮기며 Enter로 그날을
 * 고른다. 막대는 누르면 그 항목을 여는 손 조작 지름길이고, 건반 사용자는 그날을 골라 목록에서 연다. 그래서 막대는 보조기술에
 * 숨기고 칸의 접근 이름이 그날 항목 수를 싣는다.
 */

import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import StatusShape, { type StatusShapeKind } from "./StatusShape";
import controlStyles from "./control/control.module.css";
import styles from "./CalendarView.module.css";

const DAY_MS = 86_400_000;
const WEEKDAYS = ["월", "화", "수", "목", "금", "토", "일"];
/** 칸 안에 막대로 그리는 줄 수. 넘치는 항목은 '+n'이다 */
const LANES = 3;

export type CalendarState = "REQUESTED" | "APPROVED" | "IN_PROGRESS" | "DONE" | "ROLLED_BACK" | "CANCELED";

export interface CalendarItem {
  id: string;
  /** 시작·끝 시각(ms). 끝은 포함하지 않는다 */
  start: number;
  end: number;
  /** 칸이 넓을 때 막대에 적는 글 */
  label: string;
  state: CalendarState;
  /** 툴팁 원문 */
  title: string;
}

const STATE_SHAPE: Record<CalendarState, StatusShapeKind> = {
  REQUESTED: "dashed",
  APPROVED: "ring",
  IN_PROGRESS: "play",
  DONE: "circle",
  ROLLED_BACK: "triangle",
  CANCELED: "ring",
};

/** KST 날짜 열쇠('2026-09-25')의 자정(ms) */
export function dayStart(day: string): number {
  return Date.parse(`${day}T00:00:00+09:00`);
}

export function dayKeyOf(ms: number): string {
  return new Date(ms + 9 * 3_600_000).toISOString().slice(0, 10);
}

function addDays(day: string, n: number): string {
  return dayKeyOf(dayStart(day) + n * DAY_MS + 12 * 3_600_000);
}

/** 그 달의 격자. 월요일에 시작하고 다른 달의 날로 앞뒤 주를 채운다 */
export function monthWeeks(month: string): string[][] {
  const first = `${month}-01`;
  const dow = (new Date(dayStart(first) + 9 * 3_600_000).getUTCDay() + 6) % 7;
  let cur = addDays(first, -dow);
  const weeks: string[][] = [];
  do {
    const week: string[] = [];
    for (let i = 0; i < 7; i += 1) {
      week.push(cur);
      cur = addDays(cur, 1);
    }
    weeks.push(week);
  } while (cur.slice(0, 7) === month);
  return weeks;
}

export function shiftMonth(month: string, n: number): string {
  const [y, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(y, m - 1 + n, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

interface Segment {
  item: CalendarItem;
  col: number;
  span: number;
  lane: number;
  /** 앞 주에서 이어 온 막대인가, 다음 주로 이어지는가 */
  cont: boolean;
  more: boolean;
}

/** 한 주의 막대. 겹치지 않게 줄을 나누고 줄 수를 넘는 항목은 날마다 센다 */
function weekSegments(week: string[], items: CalendarItem[]): { segs: Segment[]; hidden: number[] } {
  const ws = dayStart(week[0]);
  const we = ws + 7 * DAY_MS;
  const raw = items
    .filter((it) => it.start < we && it.end > ws)
    .map((it) => {
      const col = Math.max(0, Math.floor((Math.max(it.start, ws) - ws) / DAY_MS));
      const last = Math.min(6, Math.floor((Math.min(it.end, we) - 1 - ws) / DAY_MS));
      return { item: it, col, span: Math.max(1, last - col + 1), cont: it.start < ws, more: it.end > we };
    })
    .sort((a, b) => a.col - b.col || b.span - a.span || a.item.start - b.item.start);
  const laneEnd: number[] = [];
  const segs: Segment[] = [];
  const hidden = [0, 0, 0, 0, 0, 0, 0];
  for (const r of raw) {
    let lane = laneEnd.findIndex((end) => end < r.col);
    if (lane < 0) lane = laneEnd.length;
    if (lane >= LANES) {
      for (let c = r.col; c < r.col + r.span; c += 1) hidden[c] += 1;
      continue;
    }
    laneEnd[lane] = r.col + r.span - 1;
    segs.push({ ...r, lane });
  }
  return { segs, hidden };
}

export default function CalendarView({
  month,
  today,
  selected,
  onSelect,
  onMonth,
  items = [],
  onItem,
  renderDay,
  dayLabel,
  label,
}: {
  /** 'YYYY-MM'(KST) */
  month: string;
  today: string;
  selected: string | null;
  onSelect: (day: string) => void;
  onMonth: (month: string) => void;
  items?: CalendarItem[];
  /** 막대를 눌렀을 때 */
  onItem?: (id: string) => void;
  /** 칸 안의 내용(SLA의 일별 가용성). 주면 막대 대신 이것을 그린다 */
  renderDay?: (day: string) => ReactNode;
  /** 칸의 접근 이름에 덧붙일 글 */
  dayLabel?: (day: string) => string;
  label: string;
}) {
  const weeks = useMemo(() => monthWeeks(month), [month]);
  const [focus, setFocus] = useState<string>(selected ?? (today.slice(0, 7) === month ? today : `${month}-01`));
  const refs = useRef(new Map<string, HTMLDivElement | null>());
  const wantFocus = useRef(false);

  // 달이 바뀌면 초점 날을 그 달 안으로 옮긴다
  useEffect(() => {
    if (focus.slice(0, 7) !== month) setFocus(selected && selected.slice(0, 7) === month ? selected : `${month}-01`);
  }, [month, focus, selected]);

  useEffect(() => {
    if (!wantFocus.current) return;
    wantFocus.current = false;
    refs.current.get(focus)?.focus();
  }, [focus, month]);

  const move = (day: string) => {
    wantFocus.current = true;
    if (day.slice(0, 7) !== month) onMonth(day.slice(0, 7));
    setFocus(day);
  };

  const onKey = (e: React.KeyboardEvent, day: string, dow: number) => {
    let next: string | null = null;
    if (e.key === "ArrowRight") next = addDays(day, 1);
    else if (e.key === "ArrowLeft") next = addDays(day, -1);
    else if (e.key === "ArrowDown") next = addDays(day, 7);
    else if (e.key === "ArrowUp") next = addDays(day, -7);
    else if (e.key === "Home") next = addDays(day, -dow);
    else if (e.key === "End") next = addDays(day, 6 - dow);
    else if (e.key === "PageDown" || e.key === "PageUp") {
      const m = shiftMonth(day.slice(0, 7), e.key === "PageDown" ? 1 : -1);
      const lastDay = new Date(Date.UTC(Number(m.slice(0, 4)), Number(m.slice(5, 7)), 0)).getUTCDate();
      next = `${m}-${String(Math.min(Number(day.slice(8, 10)), lastDay)).padStart(2, "0")}`;
    } else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onSelect(day);
      return;
    } else return;
    e.preventDefault();
    move(next);
  };

  const countOf = (day: string) => {
    const s = dayStart(day);
    return items.filter((it) => it.start < s + DAY_MS && it.end > s).length;
  };

  return (
    <div className={styles.calendar}>
      <div role="grid" aria-label={label} className={styles.grid} key={month}>
        <div role="row" className={styles.head}>
          {WEEKDAYS.map((w, i) => (
            <span key={w} role="columnheader" className={styles.weekday} data-weekend={i >= 5 ? "" : undefined}>
              {w}
            </span>
          ))}
        </div>
        {weeks.map((week) => {
          const { segs, hidden } = weekSegments(week, items);
          return (
            <div key={week[0]} role="row" className={styles.week}>
              {week.map((day, i) => {
                const other = day.slice(0, 7) !== month;
                const n = countOf(day);
                const d = Number(day.slice(8, 10));
                const extra = dayLabel?.(day);
                return (
                  <div
                    key={day}
                    ref={(el) => {
                      refs.current.set(day, el);
                    }}
                    role="gridcell"
                    className={styles.cell}
                    style={{ gridColumn: i + 1 }}
                    data-other={other ? "" : undefined}
                    data-today={day === today ? "" : undefined}
                    aria-selected={day === selected}
                    aria-current={day === today ? "date" : undefined}
                    aria-label={`${Number(day.slice(5, 7))}월 ${d}일${day === today ? " · 오늘" : ""}${extra ? ` · ${extra}` : items.length > 0 ? ` · 계획 ${n}건` : ""}`}
                    tabIndex={day === focus ? 0 : -1}
                    onClick={() => {
                      setFocus(day);
                      onSelect(day);
                    }}
                    onKeyDown={(e) => onKey(e, day, i)}
                  >
                    <span className={`${styles.date} num`} aria-hidden="true">
                      {d}
                    </span>
                    {renderDay && !other && <span className={styles.dayBody}>{renderDay(day)}</span>}
                    {hidden[i] > 0 && (
                      <span className={`${styles.more} num`} aria-hidden="true">
                        +{hidden[i]}
                      </span>
                    )}
                  </div>
                );
              })}
              {!renderDay &&
                segs.map((s) => (
                  <span
                    key={`${s.item.id}-${week[0]}`}
                    className={styles.bar}
                    data-state={s.item.state}
                    data-cont={s.cont ? "" : undefined}
                    data-more={s.more ? "" : undefined}
                    style={{ gridColumn: `${s.col + 1} / span ${s.span}`, gridRow: s.lane + 2 }}
                    title={s.item.title}
                    aria-hidden="true"
                    onClick={(e) => {
                      e.stopPropagation();
                      onItem?.(s.item.id);
                    }}
                  >
                    <StatusShape kind={STATE_SHAPE[s.item.state]} size={8} className={styles.barShape} />
                    <span className={styles.barLabel}>{s.item.label}</span>
                  </span>
                ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** 달 머리의 이동 단추. 이전 달, 오늘, 다음 달이다 */
export function MonthNav({ month, today, onMonth }: { month: string; today: string; onMonth: (m: string) => void }) {
  const [y, m] = month.split("-").map(Number);
  return (
    <div className={styles.nav}>
      <span className={`${styles.monthTitle} num`} aria-live="polite">
        {y}년 {m}월
      </span>
      <span className={styles.navBtns}>
        <button type="button" className={styles.navBtn} onClick={() => onMonth(shiftMonth(month, -1))} aria-label="이전 달">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M9.75 3.5 L5.25 8 L9.75 12.5" />
          </svg>
        </button>
        <button type="button" className={styles.navToday} disabled={today.slice(0, 7) === month} onClick={() => onMonth(today.slice(0, 7))}>
          오늘
        </button>
        <button type="button" className={styles.navBtn} onClick={() => onMonth(shiftMonth(month, 1))} aria-label="다음 달">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M6.25 3.5 L10.75 8 L6.25 12.5" />
          </svg>
        </button>
      </span>
    </div>
  );
}

/**
 * 달력 격자의 골격(관리자 설계 4-10-4 로딩). 요일 머리와 다섯 주의 칸을 같은 격자로 세워 판이 들어와도 자리가 움직이지 않게
 * 한다. 띠는 기다리는 동안에만 지나가고 움직임 줄이기면 멈춘 골격이다
 */
export function CalendarSkeleton({ label }: { label: string }) {
  return (
    <div className={styles.calendar} aria-busy="true">
      <span className="sr-only">{label}</span>
      <div className={styles.grid} aria-hidden="true">
        <div className={styles.head}>
          {WEEKDAYS.map((w) => (
            <span key={w} className={styles.weekday}>
              {w}
            </span>
          ))}
        </div>
        {Array.from({ length: 5 }, (_, r) => (
          <div key={r} className={styles.week}>
            {Array.from({ length: 7 }, (_, c) => (
              <span key={c} className={styles.cell} data-skeleton="" style={{ gridColumn: c + 1 }}>
                <i className={controlStyles.bone} style={{ width: 18, height: 12 }} />
                {(r * 7 + c) % 5 === 1 && <i className={controlStyles.bone} style={{ width: "72%", height: 12, marginTop: 10 }} />}
              </span>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

/** 상태의 모양과 낱말. 달력 범례와 목록이 같은 말을 쓴다 */
export const CALENDAR_LEGEND: { state: CalendarState; word: string }[] = [
  { state: "REQUESTED", word: "승인 대기" },
  { state: "APPROVED", word: "예정" },
  { state: "IN_PROGRESS", word: "진행 중" },
  { state: "DONE", word: "완료" },
  { state: "ROLLED_BACK", word: "되돌림" },
  { state: "CANCELED", word: "취소" },
];

export function CalendarLegend() {
  return (
    <ul className={styles.legend} aria-label="달력 범례">
      {CALENDAR_LEGEND.map((l) => (
        <li key={l.state} className={styles.legendItem} data-state={l.state}>
          <StatusShape kind={STATE_SHAPE[l.state]} size={8} className={styles.barShape} />
          {l.word}
        </li>
      ))}
    </ul>
  );
}

export { STATE_SHAPE as CALENDAR_SHAPE };
