"use client";

/**
 * 경보·사건 화면의 오른쪽 칸(관리자 설계 4-10-2). 고른 사건 하나를 판에서 읽는다. 담당과 확인·해제까지의 판독, 생애 다섯
 * 단계, 호출 단계, 사건 기록 순서다. 담당 바꾸기와 사후 분석은 사건 드로어에서 한다. 이 칸은 읽는 자리라 실행 단추가 없다.
 */

import Link from "next/link";
import { useState } from "react";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { escalationOf, incidentTimes, todayMeans } from "@/lib/admin/opsDetail";
import { runbookById, runbookHref } from "@/lib/admin/runbooks";
import { durationWord, hms, kstDay } from "@/lib/admin/timeText";
import type { EscalationStep, Incident, IncidentLog } from "@/lib/admin/types";
import StatusShape, { GLYPH_SHAPE } from "../StatusShape";
import { StateChip, StatStrip, StatTile } from "../ui";
import { SEVERITY_SHAPE, useClock } from "../control/parts";
import { byFilter, EventRow, FilterSwitch, Lifecycle, OpenElapsed, type EventFilter } from "../control/Timeline";
import { useFreshKeys } from "../useFreshKeys";
import { EscalationBar, NextPage } from "./Escalation";
import controlStyles from "../control/control.module.css";
import styles from "./incidents.module.css";

const ONCALL_SOURCE = "당번 명부·호출(GET /admin/v1/msp/oncall, 사건 확장 필드 · 3라운드 계약 초안)";

function OwnerValue({ inc }: { inc: Incident }) {
  if (inc.owner === undefined) {
    return (
      <span className={styles.missingValue} title={`원천 연결 전 · ${ONCALL_SOURCE}`}>
        ◌<span className="sr-only">원천 연결 전</span>
      </span>
    );
  }
  return <>{inc.owner?.name ?? "없음"}</>;
}

export default function IncidentFocus({ inc, log, policy }: { inc: Incident; log: IncidentLog; policy: EscalationStep[] | null }) {
  const [filter, setFilter] = useState<EventFilter>("ALL");
  // 오늘 평균의 '오늘' 경계만 읽는다
  const now = useClock(60_000);
  const open = inc.resolvedAt === null;
  const t = incidentTimes(inc, log.events);
  const mean = todayMeans(log, now);
  const esc = escalationOf(inc, policy, log.events);
  const events = log.events.filter((e) => e.incidentId === inc.id);
  const asc = [...events].reverse();
  const shown = events.filter(byFilter(filter));
  const fresh = useFreshKeys(shown.map((e) => e.id));
  const tone = open ? (inc.severity === "critical" ? "danger" : "warning") : "success";
  const owner = inc.owner;
  const runbook = inc.runbookId ? runbookById(inc.runbookId) : null;
  const openDrawer = () => useOpsDetail.getState().openIncident(inc.id);
  const day = kstDay(Date.parse(inc.openedAt));

  return (
    <section className={controlStyles.panel} aria-labelledby="inc-focus-title">
      <div className={styles.focusHead}>
        <StatusShape kind={GLYPH_SHAPE[open ? SEVERITY_SHAPE[inc.severity] : "●"]} tone={tone} size={12} className={styles.shape} />
        <h2 id="inc-focus-title" className={styles.focusTitle}>
          <button type="button" className={styles.focusOpen} aria-haspopup="dialog" title={`${inc.title} · 사건 상세`} onClick={openDrawer}>
            {inc.title}
          </button>
        </h2>
        <StateChip
          tone={tone}
          label={
            open ? (
              <>
                진행 중 · <OpenElapsed from={inc.openedAt} />째
              </>
            ) : (
              <>해제 · {durationWord(Date.parse(inc.resolvedAt as string) - Date.parse(inc.openedAt))}</>
            )
          }
        />
        <button type="button" className={`${controlStyles.smallBtn} ${styles.focusDetail}`} aria-haspopup="dialog" onClick={openDrawer}>
          상세·사후 검토
        </button>
      </div>

      <StatStrip label="사건 판독" count={4}>
        <StatTile
          label="담당"
          value={<OwnerValue inc={inc} />}
          sub={
            owner === undefined
              ? "당번 명부 원천 연결 전"
              : owner === null
                ? "담당 없음"
                : owner.assignedBy === "시스템"
                  ? `${owner.role} · 자동 배정`
                  : `${owner.role} · ${owner.assignedBy} 지정`
          }
        />
        <StatTile
          label="확인까지"
          value={<span className="num">{t.ttaMs !== null ? durationWord(t.ttaMs) : open ? "확인 전" : "없음"}</span>}
          tone={open && t.ttaMs === null && esc?.paging ? "warning" : undefined}
          sub={
            t.ttaMs === null && t.autoOnly
              ? "자동 조치로 다뤘습니다"
              : `오늘 평균 ${mean.mttaMs === null ? "기록 없음" : durationWord(mean.mttaMs)}`
          }
        />
        <StatTile
          label={open ? "경과" : "해제까지"}
          value={<span className="num">{t.ttrMs !== null ? durationWord(t.ttrMs) : <OpenElapsed from={inc.openedAt} />}</span>}
          sub={t.ttrMs === null ? `${hms(inc.openedAt)} 발생` : `오늘 평균 ${mean.mttrMs === null ? "기록 없음" : durationWord(mean.mttrMs)}`}
        />
        <StatTile
          label="런북"
          value={
            runbook ? (
              <Link href={runbookHref(runbook.id)} className={styles.runbookLink}>
                {runbook.title}
              </Link>
            ) : (
              <span className={styles.quiet}>없음</span>
            )
          }
          sub={runbook ? `${runbook.steps.length}단계 · SSM에서 사람이 실행` : "이 경보에 맞는 런북이 없습니다"}
        />
      </StatStrip>

      <div className={styles.block}>
        <div className={styles.blockHead}>
          <h3 className={styles.blockTitle}>생애</h3>
          <span className={styles.blockMeta}>발생 · 확인 · 대응 · 해제 · 사후 분석</span>
        </div>
        <Lifecycle inc={inc} events={asc} />
      </div>

      <div className={styles.block}>
        <div className={styles.blockHead}>
          <h3 className={styles.blockTitle}>호출</h3>
          <span className={`${styles.blockMeta} num`}>
            {esc === null ? "원천 연결 전" : esc.next ? <NextPage view={esc} /> : esc.level > 0 ? (esc.paging ? "호출 중" : "호출 멈춤") : open ? "확인되어 호출하지 않음" : "호출 없이 해제"}
          </span>
        </div>
        <EscalationBar view={esc} source={ONCALL_SOURCE} />
      </div>

      <div className={styles.block}>
        <div className={styles.blockHead}>
          <h3 className={styles.blockTitle}>
            사건 기록 <span className={`${styles.count} num`}>{events.length}</span>
          </h3>
          <FilterSwitch value={filter} onChange={setFilter} label="기록 거르기" />
        </div>
        {shown.length === 0 ? (
          <p className={controlStyles.eventsEmpty}>이 사건에 고른 종류의 기록이 없습니다</p>
        ) : (
          <ol className={controlStyles.events} aria-label="사건 기록" data-days={shown.some((e) => kstDay(Date.parse(e.at)) !== day) ? "" : undefined}>
            {shown.map((e) => (
              <EventRow key={e.id} e={e} fresh={fresh.has(e.id)} day={day} />
            ))}
          </ol>
        )}
      </div>
    </section>
  );
}
