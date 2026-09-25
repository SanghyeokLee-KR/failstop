"use client";

/**
 * 사건 기록. 사건 생애 다섯 단계(발생, 확인, 대응, 해제, 사후 분석)의 막대와, 누가 언제 무엇을 했는지의 기록이다.
 * 기록은 세로 레일 위의 점으로 늘어서고, 점은 누가 했는지(시스템·AI·운영자)와 발생·해제를 모양과 색으로 가른다.
 */

import { useState } from "react";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { lifecycleOf } from "@/lib/admin/opsDetail";
import { kstDay, mdhm } from "@/lib/admin/timeText";
import type { Incident, IncidentEvent, IncidentLog, OpsActor } from "@/lib/admin/types";
import StageBar from "../StageBar";
import { useFreshKeys } from "../useFreshKeys";
import { AiMark, durationWord, hms, SEVERITY_SHAPE, Shape, useClock } from "./parts";
import styles from "./control.module.css";

/** 기록 거르기. 경보는 시스템이 낸 기록(경보 발생·해제·알림), AI와 운영자는 행위자로 가른다 */
export type EventFilter = "ALL" | OpsActor;
const FILTERS: { id: EventFilter; label: string }[] = [
  { id: "ALL", label: "전체" },
  { id: "SYSTEM", label: "경보" },
  { id: "AI", label: "AI" },
  { id: "OPERATOR", label: "운영자" },
];

/**
 * 사건 생애 다섯 단계(발생, 확인, 대응, 해제, 사후 분석, 관리자 설계 4-10-2). 모든 화면이 이 한 막대를 쓴다. AI 판단과
 * 승인은 대응 칸 아래의 작은 표지이고, AI의 판단 흐름은 AI 판단 상세의 판단 과정이 싣는다
 */
export function Lifecycle({ inc, events }: { inc: Incident; events: IncidentEvent[] }) {
  return <StageBar items={lifecycleOf(inc, events)} label="사건 생애 단계" urgent={inc.resolvedAt === null} />;
}

/** 점의 모양. 발생과 해제는 상태 모양을 따르고, 나머지는 누가 했는지를 적는다 */
function dotKind(e: IncidentEvent): string {
  if (e.kind === "OPENED") return "opened";
  if (e.kind === "RESOLVED") return "resolved";
  if (e.kind === "AUTO_REVERTED") return "reverted";
  return e.actor.toLowerCase();
}

/**
 * 기록 한 줄. day(사건을 연 KST 날짜)를 주면 그날이 아닌 줄(다음 날 쓴 사후 분석)은 시각 앞에 날짜를 붙인다. 그런 줄이
 * 있는 목록은 events에 data-days를 달아 시각 칸을 넓힌다
 */
export function EventRow({ e, showIncident, fresh, day }: { e: IncidentEvent; showIncident?: string | null; fresh?: boolean; day?: string }) {
  const other = day !== undefined && kstDay(Date.parse(e.at)) !== day;
  return (
    <li className={styles.event} data-dot={dotKind(e)} data-fresh={fresh ? "" : undefined}>
      <span className={styles.eventDot} aria-hidden="true" />
      <span className={`${styles.eventTime} num`}>{other ? mdhm(e.at) : hms(e.at)}</span>
      <span className={styles.actor} data-actor={e.actor} title={e.actorName}>
        {e.actor === "AI" && <AiMark size={11} />}
        <span className={styles.actorName}>{e.actorName}</span>
      </span>
      <span className={styles.eventText} data-kind={e.kind}>
        {e.text}
        {showIncident && <span className={styles.incTag}> · {showIncident}</span>}
      </span>
    </li>
  );
}

export function FilterSwitch({ value, onChange, label }: { value: EventFilter; onChange: (f: EventFilter) => void; label: string }) {
  return (
    <span className={styles.switch} role="group" aria-label={label}>
      {FILTERS.map((f) => (
        <button key={f.id} type="button" className={styles.switchBtn} data-size="xs" aria-pressed={value === f.id} onClick={() => onChange(f.id)}>
          {f.label}
        </button>
      ))}
    </span>
  );
}

export const byFilter = (f: EventFilter) => (e: IncidentEvent) => f === "ALL" || e.actor === f;

/** 진행 중인 사건의 경과. 매초 바뀌는 글자라 이 조각만 시계를 탄다 */
export function OpenElapsed({ from }: { from: string }) {
  const now = useClock();
  return <>{durationWord(now - Date.parse(from))}</>;
}

/** 사건 한 건의 머리, 단계 줄, 기록 */
export function IncidentView({ inc, log, limit, filter = "ALL" }: { inc: Incident; log: IncidentLog; limit?: number; filter?: EventFilter }) {
  const events = log.events.filter((e) => e.incidentId === inc.id);
  const asc = [...events].reverse();
  const picked = events.filter(byFilter(filter));
  const shown = limit ? picked.slice(0, limit) : picked;
  const open = inc.resolvedAt === null;
  // 새로 쌓인 기록은 들어오며 잠깐 플래시를 켠다(§12-4 목록 행 등장). 처음 그린 기록은 새 행이 아니다
  const fresh = useFreshKeys(shown.map((e) => e.id));
  return (
    <div className={styles.stack} style={{ gap: "var(--space-3)" }}>
      <div className={styles.incHead}>
        <Shape tone={open ? (inc.severity === "critical" ? "danger" : "warning") : "success"}>{open ? SEVERITY_SHAPE[inc.severity] : "●"}</Shape>
        {/* 사건 이름을 누르면 사건 드로어가 열린다(§12-13의 2) */}
        {/* 말줄임은 안쪽 글이 맡는다. 단추가 넘친 것을 자르면 44px로 넓힌 누르는 자리까지 잘린다 */}
        <button
          type="button"
          className={styles.incOpen}
          title={`${inc.title} · 사건 상세`}
          aria-haspopup="dialog"
          onClick={() => useOpsDetail.getState().openIncident(inc.id)}
        >
          <span className={styles.incTitle}>{inc.title}</span>
        </button>
        <span className={styles.incState}>
          {open ? "진행 중" : "해제"} ·{" "}
          <b className="num">{open ? <OpenElapsed from={inc.openedAt} /> : durationWord(Date.parse(inc.resolvedAt as string) - Date.parse(inc.openedAt))}</b>
        </span>
      </div>
      <Lifecycle inc={inc} events={asc} />
      {shown.length === 0 ? (
        <p className={styles.eventsEmpty}>이 사건에 고른 종류의 기록이 없습니다</p>
      ) : (
        <ol className={styles.events} aria-label="사건 기록">
          {shown.map((e) => (
            <EventRow key={e.id} e={e} fresh={fresh.has(e.id)} />
          ))}
        </ol>
      )}
    </div>
  );
}

/** 관제센터 오른쪽 기둥의 타임라인. 진행 중인 사건이 없으면 가장 최근 사건을 보인다 */
export default function TimelineBlock({ log }: { log: IncidentLog }) {
  const [filter, setFilter] = useState<EventFilter>("ALL");
  const current = log.incidents.find((i) => i.resolvedAt === null) ?? log.incidents[0] ?? null;
  // 진행 중인 사건이 있으면 그 기록만 싣는다. 사건 밖 기록은 사건이 없을 때만 자리를 쓴다
  const loose = current?.resolvedAt === null ? [] : log.events.filter((e) => e.incidentId === null).filter(byFilter(filter)).slice(0, 2);
  return (
    <div className={styles.stack} style={{ gap: "var(--space-3)" }}>
      <div className={styles.subHead}>
        <span>사건 기록</span>
        <FilterSwitch value={filter} onChange={setFilter} label="기록 거르기" />
      </div>
      {current === null ? (
        <div className={styles.empty}>
          <span className={styles.emptyMark} aria-hidden="true" />
          오늘 사건 없음
        </div>
      ) : (
        <>
          <IncidentView inc={current} log={log} limit={4} filter={filter} />
          {loose.length > 0 && (
            <>
              <div className={styles.subHead}>사건 밖 운영 기록</div>
              <ol className={styles.events}>
                {loose.map((e) => (
                  <EventRow key={e.id} e={e} />
                ))}
              </ol>
            </>
          )}
        </>
      )}
    </div>
  );
}
