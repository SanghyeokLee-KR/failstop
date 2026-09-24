"use client";

/**
 * 사건 타임라인. 발생, AI 판단, 승인, 조치, 해제 다섯 단계를 누가 언제 했는지와 함께 적는다.
 * 자동 모드의 조치는 사전 승인(자동 모드 켜기 티켓)을 딛고 실행되므로 승인 단계에 '자동'으로 적는다.
 */

import type { Incident, IncidentEvent, IncidentLog } from "@/lib/admin/types";
import { AiMark, durationWord, hms, SEVERITY_SHAPE } from "./parts";
import styles from "./control.module.css";

const STAGES = ["발생", "AI 판단", "승인", "조치", "해제"] as const;

interface StageMark {
  at: string | null;
  ai: boolean;
  note?: string;
}

/** 사건의 단계별 첫 기록. 기록이 오래된 순서로 들어온다 */
export function stagesOf(inc: Incident, events: IncidentEvent[]): StageMark[] {
  const opened = events.find((e) => e.kind === "OPENED") ?? null;
  // 단계는 발생 뒤의 기록만 본다. 자동 조치 뒤 악화 사건처럼 원인이 된 앞선 기록은 목록에만 남는다
  const from = Date.parse(opened?.at ?? inc.openedAt);
  const first = (pred: (e: IncidentEvent) => boolean) => events.find((e) => Date.parse(e.at) >= from && pred(e)) ?? null;
  const judged = first((e) => e.kind === "AI_JUDGED" || e.kind === "AUTO_ACTED" || e.kind === "AUTO_REVERTED");
  const approved = first((e) => e.kind === "APPROVED" || e.kind === "REJECTED" || e.kind === "AUTO_ACTED" || e.kind === "AUTO_REVERTED");
  const acted = first((e) => (e.kind === "ACTED" && e.actor !== "OPERATOR") || e.kind === "AUTO_ACTED" || e.kind === "AUTO_REVERTED" || e.kind === "REVERTED");
  const auto = approved !== null && (approved.kind === "AUTO_ACTED" || approved.kind === "AUTO_REVERTED");
  return [
    { at: opened?.at ?? inc.openedAt, ai: false },
    { at: judged?.at ?? null, ai: true },
    { at: approved?.at ?? null, ai: auto, note: auto ? "자동" : approved?.kind === "REJECTED" ? "거절" : undefined },
    { at: acted?.at ?? null, ai: acted !== null && acted.actor === "AI" },
    { at: inc.resolvedAt, ai: false },
  ];
}

export function Stages({ inc, events }: { inc: Incident; events: IncidentEvent[] }) {
  const marks = stagesOf(inc, events);
  const current = inc.resolvedAt === null ? marks.findIndex((m) => m.at === null) : -1;
  return (
    <ol className={styles.stages} aria-label="사건 단계">
      {STAGES.map((label, i) => {
        const m = marks[i];
        const done = m.at !== null;
        return (
          <li
            key={label}
            className={styles.stage}
            data-done={done ? "" : undefined}
            data-current={i === current ? "" : undefined}
            data-ai={m.ai ? "" : undefined}
          >
            <span className={styles.stageDot} aria-hidden="true" />
            <span>{label}</span>
            <span className={`${styles.stageWhen} num`}>
              {done ? `${m.at ? hms(m.at).slice(0, 5) : ""}${m.note ? ` · ${m.note}` : ""}` : i === current ? "대기" : ""}
            </span>
            <span className="sr-only">{done ? "완료" : i === current ? "진행 중" : "앞 단계 대기"}</span>
          </li>
        );
      })}
    </ol>
  );
}

export function EventRow({ e, showIncident }: { e: IncidentEvent; showIncident?: string | null }) {
  return (
    <li className={styles.event}>
      <span className={`${styles.eventTime} num`}>{hms(e.at)}</span>
      <span className={styles.actor} data-actor={e.actor} title={e.actorName}>
        {e.actor === "AI" && <AiMark size={11} />}
        {e.actorName}
      </span>
      <span className={styles.eventText} data-kind={e.kind}>
        {e.text}
        {showIncident && <span className={styles.incTag}> · {showIncident}</span>}
      </span>
    </li>
  );
}

/** 사건 한 건의 머리, 단계 줄, 기록 */
export function IncidentView({ inc, log, now, limit }: { inc: Incident; log: IncidentLog; now: number; limit?: number }) {
  const events = log.events.filter((e) => e.incidentId === inc.id);
  const asc = [...events].reverse();
  const shown = limit ? events.slice(0, limit) : events;
  const open = inc.resolvedAt === null;
  const elapsed = (open ? now : Date.parse(inc.resolvedAt as string)) - Date.parse(inc.openedAt);
  return (
    <div className={styles.stack} style={{ gap: "var(--space-3)" }}>
      <div className={styles.incHead}>
        <span className={styles.shape} data-tone={open ? (inc.severity === "critical" ? "danger" : "warning") : "success"} aria-hidden="true">
          {open ? SEVERITY_SHAPE[inc.severity] : "●"}
        </span>
        <span className={styles.incTitle} title={inc.title}>
          {inc.title}
        </span>
        <span className={styles.incState}>
          {open ? "진행 중" : "해제"} · <b className="num">{durationWord(elapsed)}</b>
        </span>
      </div>
      <Stages inc={inc} events={asc} />
      <ol className={styles.events} aria-label="사건 기록">
        {shown.map((e) => (
          <EventRow key={e.id} e={e} />
        ))}
      </ol>
    </div>
  );
}

/** 관제센터 오른쪽 기둥의 타임라인. 진행 중인 사건이 없으면 가장 최근 사건을 보인다 */
export default function TimelineBlock({ log, now }: { log: IncidentLog; now: number }) {
  const current = log.incidents.find((i) => i.resolvedAt === null) ?? log.incidents[0] ?? null;
  // 진행 중인 사건이 있으면 그 기록만 싣는다. 사건 밖 기록은 사건이 없을 때만 자리를 쓴다
  const loose = current?.resolvedAt === null ? [] : log.events.filter((e) => e.incidentId === null).slice(0, 2);
  if (current === null) {
    return (
      <div className={styles.empty}>
        <span className={styles.emptyMark} aria-hidden="true" />
        오늘 사건 없음
      </div>
    );
  }
  return (
    <div className={styles.stack} style={{ gap: "var(--space-3)" }}>
      <IncidentView inc={current} log={log} now={now} limit={4} />
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
    </div>
  );
}
