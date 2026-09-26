"use client";

/**
 * 경보·사건 화면의 사건 목록(관리자 설계 4-10-2). 행마다 심각도 형태, 사건 이름, 담당, 경과(진행 중이면 발생 뒤 경과와 다음
 * 호출까지 남은 시간), 확인·해제까지 걸린 시간이다. 고른 행은 사이드바 활성 항목과 같은 왼쪽 2px 선과 sunken 면이고
 * (aria-current), 위아래 화살표로 고른다. 값은 관제 판의 사건 기록과 사건 칸(담당·호출·사후 분석)에서만 읽는다.
 */

import { escalationOf, incidentTimes } from "@/lib/admin/opsDetail";
import { durationWord, hm, mdhm } from "@/lib/admin/timeText";
import { kstDay } from "@/lib/admin/timeText";
import type { EscalationStep, Incident, IncidentLog } from "@/lib/admin/types";
import StatusShape, { GLYPH_SHAPE } from "../StatusShape";
import { SEVERITY_SHAPE, useClock } from "../control/parts";
import { OpenElapsed } from "../control/Timeline";
import { NextPage } from "./Escalation";
import styles from "./incidents.module.css";

function Row({ inc, log, policy, selected, onPick, today }: {
  inc: Incident;
  log: IncidentLog;
  policy: EscalationStep[] | null;
  selected: boolean;
  onPick: () => void;
  today: string;
}) {
  const open = inc.resolvedAt === null;
  const t = incidentTimes(inc, log.events);
  const esc = escalationOf(inc, policy, log.events);
  const opened = Date.parse(inc.openedAt);
  const tone = open ? (inc.severity === "critical" ? "danger" : "warning") : "success";
  const shape = open ? SEVERITY_SHAPE[inc.severity] : "●";
  const pm = inc.postmortem;
  return (
    <button type="button" className={styles.row} aria-current={selected ? "true" : undefined} onClick={onPick} data-open={open ? "" : undefined}>
      <span className={styles.rowShape}>
        <StatusShape kind={GLYPH_SHAPE[shape]} tone={tone} className={styles.shape} />
      </span>
      <span className={styles.rowTitle} title={inc.title}>
        {inc.title}
      </span>
      <span className={`${styles.rowState} num`} data-open={open ? "" : undefined}>
        {open ? (
          <>
            진행 <OpenElapsed from={inc.openedAt} />째
          </>
        ) : (
          <>해제 · {durationWord(Date.parse(inc.resolvedAt as string) - opened)}</>
        )}
      </span>
      <span className={styles.rowMeta}>
        <span className="num">{kstDay(opened) === today ? hm(inc.openedAt) : mdhm(inc.openedAt)} 발생</span>
        <span>
          담당{" "}
          {inc.owner === undefined ? (
            <span className={styles.missing} title="당번 명부 원천 연결 전">◌</span>
          ) : (
            <b>{inc.owner?.name ?? "없음"}</b>
          )}
        </span>
        <span className="num">
          {t.ttaMs !== null ? (
            <>
              확인 <b>{durationWord(t.ttaMs)}</b>
            </>
          ) : open ? (
            "확인 전"
          ) : t.autoOnly ? (
            "확인 없음 · 자동 조치"
          ) : (
            "확인 없음"
          )}
        </span>
        {open && esc?.next && (
          <span className={`${styles.rowNext} num`}>
            <NextPage view={esc} />
          </span>
        )}
        {!open && pm !== undefined && (
          <span>{pm === null ? "사후 분석 작성 전" : pm.status === "DONE" ? "사후 분석 완료" : "사후 분석 초안"}</span>
        )}
      </span>
    </button>
  );
}

export default function IncidentList({ list, log, policy, picked, onPick }: {
  list: Incident[];
  log: IncidentLog;
  policy: EscalationStep[] | null;
  picked: string | null;
  onPick: (id: string) => void;
}) {
  // '오늘' 경계만 읽으므로 분 단위로만 다시 그린다. 진행 중 경과와 남은 시간은 그 조각이 시계를 탄다
  const now = useClock(60_000);
  const today = kstDay(now);
  const onKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    const i = list.findIndex((x) => x.id === picked);
    if (i < 0) return;
    e.preventDefault();
    const next = list[(i + (e.key === "ArrowDown" ? 1 : list.length - 1)) % list.length];
    onPick(next.id);
    e.currentTarget.querySelectorAll<HTMLButtonElement>("button")[list.indexOf(next)]?.focus();
  };
  return (
    <div className={styles.list} role="group" aria-label="사건 목록 · 위아래 화살표로 고름" onKeyDown={onKey}>
      {list.map((inc) => (
        <Row key={inc.id} inc={inc} log={log} policy={policy} selected={inc.id === picked} onPick={() => onPick(inc.id)} today={today} />
      ))}
    </div>
  );
}
