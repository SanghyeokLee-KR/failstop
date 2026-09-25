"use client";

/**
 * 사건 드로어(오른쪽 560px, 디자인 시스템 §12-13의 2, 관리자 설계 4-9). 한 사건의 생애 단계, 확인·해제까지 걸린
 * 시간, 관련 경보, AI 판단, 운영자 조치, Teams 전송을 한 줄기로 읽는다. http 판에 기록 줄이 없는 사건은 열 때 한 번 읽는다.
 * 사후 분석은 보고 서비스와 잇는 양식의 자리만 둔다. 주소의 ?incident= 가 이 층을 연다(DetailHost).
 */

import { useEffect, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { incidentSections, incidentTimes, judgmentAt, todayMeans } from "@/lib/admin/opsDetail";
import type { ControlCenter, IncidentEvent } from "@/lib/admin/types";
import { Drawer } from "../layer/Layer";
import { SimMark, StateChip } from "../ui";
import { durationWord, hm, hms, useClock } from "../control/parts";
import { OpenElapsed, Stages } from "../control/Timeline";
import controlStyles from "../control/control.module.css";
import styles from "./detail.module.css";
import StatusShape from "../StatusShape";

function Rows({ events, empty, link }: { events: IncidentEvent[]; empty: string; link?: (e: IncidentEvent) => React.ReactNode }) {
  if (events.length === 0) return <div className={styles.empty}>{empty}</div>;
  return (
    <ol className={styles.rows}>
      {events.map((e) => (
        <li key={e.id} className={styles.row}>
          <span className={`${styles.rowTime} num`}>{hms(e.at)}</span>
          <span className={styles.rowText}>
            {e.actor !== "SYSTEM" && <b>{e.actorName} </b>}
            {e.text}
          </span>
          <span>{link?.(e)}</span>
        </li>
      ))}
    </ol>
  );
}

export default function IncidentDrawer({ center, id, sim, onClose, onOpenJudgment }: {
  center: ControlCenter;
  id: string;
  sim: boolean;
  onClose: () => void;
  onOpenJudgment: (id: string) => void;
}) {
  // 오늘 평균의 '오늘' 경계만 읽는다. 진행 중 경과처럼 매초 바뀌는 글자는 그 조각이 시계를 탄다
  const now = useClock(60_000);
  const log = center.incidents.state === "READY" ? center.incidents.data : null;
  const inc = log?.incidents.find((i) => i.id === id) ?? null;
  // http 판은 최근 사건 몇 건의 기록만 싣는다. 기록이 판에 없는 사건은 열 때 한 번 읽는다(GET .../incidents/{id})
  const lazy = inc?.eventsLoaded === false;
  const [loaded, setLoaded] = useState<{ id: string; events: IncidentEvent[] | null; failed: boolean } | null>(null);
  useEffect(() => {
    if (!lazy) return;
    let alive = true;
    getAdminApi()
      .getIncidentEvents(id)
      .then((events) => alive && setLoaded({ id, events, failed: false }))
      .catch(() => alive && setLoaded({ id, events: null, failed: true }));
    return () => {
      alive = false;
    };
  }, [lazy, id]);
  if (!log || !inc) {
    return (
      <Drawer name="incident" width={560} title="사건을 찾지 못했습니다" onClose={onClose}>
        <p className={styles.note}>이 판에 {id} 사건이 없습니다. 주소가 오래됐거나 사건 기록 원천이 연결 전입니다.</p>
      </Drawer>
    );
  }
  const open = inc.resolvedAt === null;
  const extra = lazy && loaded?.id === id && loaded.events ? loaded.events : null;
  const allEvents = extra ? [...log.events.filter((e) => e.incidentId !== inc.id), ...extra] : log.events;
  const events = allEvents.filter((e) => e.incidentId === inc.id).sort((a, b) => Date.parse(b.at) - Date.parse(a.at));
  const asc = [...events].reverse();
  const t = incidentTimes(inc, allEvents);
  const mean = todayMeans(log, now);
  const sec = incidentSections(inc, allEvents);
  const pendingEvents = lazy && !extra;
  const history = center.ai.state === "READY" ? center.ai.data.history : [];

  return (
    <Drawer
      name="incident"
      width={560}
      title={inc.title}
      badge={
        <StateChip
          tone={open ? (inc.severity === "critical" ? "danger" : "warning") : "success"}
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
      }
      sub={
        <>
          <span className="num">
            {hm(inc.openedAt)} 발생{inc.resolvedAt ? ` · ${hm(inc.resolvedAt)} 해제` : ""}
          </span>
          <span className="num">기록 {events.length}건</span>
          {sim && <SimMark />}
        </>
      }
      onClose={onClose}
    >
      <section className={styles.section} aria-label="생애 단계">
        <span className={styles.sectionTitle}>생애 단계</span>
        {pendingEvents && <p className={styles.note}>{loaded?.id === id && loaded.failed ? "이 사건의 기록 줄을 불러오지 못했습니다. 다시 열면 한 번 더 읽습니다" : "이 사건의 기록 줄을 불러오는 중입니다"}</p>}
        <Stages inc={inc} events={asc} />
      </section>

      <section className={styles.section} aria-label="확인·해제까지">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>확인·해제까지</span>
          <span className={styles.sectionMeta}>오늘 평균은 KST 오늘 연 사건</span>
        </div>
        <div className={styles.times}>
          <div className={styles.time}>
            <span className={styles.timeKey}>확인까지 · MTTA</span>
            <span className={`${styles.timeValue} num`}>{t.ttaMs !== null ? durationWord(t.ttaMs) : t.autoOnly ? "확인 없음" : open ? "확인 전" : "기록 없음"}</span>
            <span className={`${styles.timeSub} num`}>
              {t.autoOnly && t.ttaMs === null ? "자동 조치로 다뤘습니다" : `오늘 평균 ${mean.mttaMs === null ? "기록 없음" : durationWord(mean.mttaMs)}`}
            </span>
          </div>
          <div className={styles.time}>
            <span className={styles.timeKey}>해제까지 · MTTR</span>
            <span className={`${styles.timeValue} num`}>{t.ttrMs !== null ? durationWord(t.ttrMs) : <OpenElapsed from={inc.openedAt} />}</span>
            <span className={`${styles.timeSub} num`}>
              {t.ttrMs === null ? "진행 중 · 경과" : `오늘 평균 ${mean.mttrMs === null ? "기록 없음" : durationWord(mean.mttrMs)}`}
            </span>
          </div>
        </div>
      </section>

      <section className={styles.section} aria-label="관련 경보">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>관련 경보</span>
          <span className={`${styles.sectionMeta} num`}>{sec.alerts.length}건</span>
        </div>
        <Rows events={sec.alerts} empty="이 사건에 경보 기록이 없습니다. 자동 조치 뒤 악화처럼 경보보다 먼저 연 사건입니다" />
      </section>

      <section className={styles.section} aria-label="AI 판단">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>AI 판단</span>
          <span className={`${styles.sectionMeta} num`}>{sec.ai.length}건</span>
        </div>
        <Rows
          events={sec.ai}
          empty="이 사건에 AI 판단 기록이 없습니다"
          link={(e) => {
            const j = judgmentAt(history, e.at);
            return (
              <button type="button" className={styles.linkBtn} disabled={j === null} title={j === null ? "판이 싣는 최근 판단 기록 밖입니다" : undefined} onClick={() => j && onOpenJudgment(j.id)}>
                판단 과정
              </button>
            );
          }}
        />
      </section>

      <section className={styles.section} aria-label="운영자 조치">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>운영자 조치</span>
          <span className={`${styles.sectionMeta} num`}>{sec.operator.length}건</span>
        </div>
        <Rows events={sec.operator} empty="운영자가 한 조치가 없습니다" />
      </section>

      <section className={styles.section} aria-label="Teams 전송">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>Teams 전송</span>
          <span className={`${styles.sectionMeta} num`}>{sec.teams.length}건</span>
        </div>
        <Rows events={sec.teams} empty="이 사건으로 보낸 Teams 알림이 없습니다. 일일 보고는 보고서 화면에서 봅니다" />
      </section>

      <section className={styles.section} aria-label="사후 분석">
        <div className={styles.postmortem}>
          <span className={controlStyles.notConnectedTitle}>
            <span aria-hidden="true"><StatusShape kind="dashed" /> </span>사후 분석 양식 · 2단계
          </span>
          <span className={controlStyles.notConnectedSource}>
            해제 뒤 보고 서비스가 사건 보고를 만들면 이 자리에서 이어 적습니다. 지금은 입력과 저장을 받지 않습니다
          </span>
          <ul className={styles.pmFields}>
            <li>
              원인<span>무엇이 왜 일어났는가</span>
            </li>
            <li>
              영향<span>누가 얼마나 겪었는가</span>
            </li>
            <li>
              재발 방지<span>무엇을 바꾸는가</span>
            </li>
            <li>
              담당<span>누가 언제까지</span>
            </li>
          </ul>
        </div>
      </section>
    </Drawer>
  );
}
