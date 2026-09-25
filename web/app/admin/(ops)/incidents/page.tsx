"use client";

/**
 * 경보·사건(/admin/incidents, 관리자 설계 4-10-2). 무엇이 울렸고, 누가 맡아 어디까지 대응했는지에 답한다. 요약 다섯 칸(활성
 * 경보, 진행 중 사건, 오늘 사건, 평균 확인 시간, 평균 해제 시간) 아래 왼쪽은 활성 경보(확인·보류, 호출 칩, 런북 링크)와
 * 사건 목록(오늘·7일), 오른쪽은 고른 사건의 담당·생애·호출·기록이다. 담당 바꾸기와 사후 분석은 사건 드로어에서 한다.
 *
 * 값은 관제 판(사건 기록, 사건 칸의 담당·호출·사후 분석, 당번 칸의 호출 정책)에서만 읽는다. MTTA·MTTR은 새 값이 아니라
 * 사건 기록에서 센다(opsDetail의 incidentTimes·todayMeans). http는 담당·호출·사후 분석이 원천 연결 전이다. 추이는 Grafana다.
 */

import { useEffect, useState } from "react";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import { incidentsIn, openIncidents, todayMeans } from "@/lib/admin/opsDetail";
import GrafanaLink from "@/components/admin/GrafanaLink";
import { AdminErrorState } from "@/components/admin/ResourceState";
import AlertList from "@/components/admin/control/Alerts";
import OpsHead from "@/components/admin/control/OpsHead";
import { alertCounts, Tile, todayIncidents } from "@/components/admin/control/SummaryStrip";
import { EventRow, OpenElapsed } from "@/components/admin/control/Timeline";
import { durationWord, FlashNum, hm, PanelHead, SectionBody, SEVERITY_WORD, useClock } from "@/components/admin/control/parts";
import IncidentFocus from "@/components/admin/incidents/IncidentFocus";
import IncidentList from "@/components/admin/incidents/IncidentList";
import PageSkeleton from "@/components/admin/control/PageSkeleton";
import StatusShape from "@/components/admin/StatusShape";
import { Segmented } from "@/components/admin/ui";
import { flashTone } from "@/lib/admin/motion";
import type { ControlCenter, IncidentLog } from "@/lib/admin/types";
import adminStyles from "@/components/admin/admin.module.css";
import controlStyles from "@/components/admin/control/control.module.css";
import styles from "@/components/admin/incidents/incidents.module.css";

type Range = "today" | "week";
const RANGES: { value: Range; label: string }[] = [
  { value: "today", label: "오늘" },
  { value: "week", label: "7일" },
];

/** 요약 다섯 칸. 상태를 싣는 칸이라 칸마다 테두리를 둔 Tile이다(§12-14). 오늘은 KST 날짜다 */
function Summary({ center }: { center: ControlCenter }) {
  const now = useClock(60_000);
  if (center.alerts.state !== "READY" || center.incidents.state !== "READY") {
    return (
      <SectionBody section={center.incidents.state !== "READY" ? center.incidents : center.alerts} what="경보·사건 요약">
        {() => null}
      </SectionBody>
    );
  }
  const alerts = center.alerts.data;
  const log = center.incidents.data;
  const c = alertCounts(alerts);
  const t = todayIncidents(log, now);
  const mean = todayMeans(log, now);
  const opens = openIncidents(log);
  const oldest = opens[0] ?? null;
  const tone = c.critical > 0 ? "danger" : c.warning > 0 ? "warning" : undefined;
  return (
    <section className={controlStyles.summary} aria-label="경보·사건 요약">
      <Tile
        icon="incidents"
        label="활성 경보"
        tone={tone}
        value={
          <span className={`${controlStyles.tileValue} num`} data-tone={tone}>
            {tone && <StatusShape kind={tone === "danger" ? "square" : "triangle"} size={14} className={controlStyles.tileMark} />}
            <FlashNum value={alerts.length} tone={flashTone} />
            <span className={controlStyles.tileUnit}>건</span>
          </span>
        }
        sub={
          <span className={controlStyles.alertCounts}>
            {(["critical", "warning", "info"] as const).map((sev) => (
              <span key={sev} className={controlStyles.alertCount} data-sev={sev} data-zero={c[sev] === 0 ? "" : undefined}>
                <span aria-hidden="true">
                  <StatusShape kind={sev === "critical" ? "square" : sev === "warning" ? "triangle" : "circle"} />
                </span>
                <b className="num">{c[sev]}</b>
                <span className="sr-only">{SEVERITY_WORD[sev]}</span>
              </span>
            ))}
          </span>
        }
      />
      <Tile
        icon="clock"
        label="진행 중 사건"
        tone={oldest ? "warning" : undefined}
        value={
          <span className={`${controlStyles.tileValue} num`} data-tone={oldest ? "warning" : undefined}>
            {oldest && <StatusShape kind="triangle" size={14} className={controlStyles.tileMark} />}
            <FlashNum value={opens.length} tone={flashTone} />
            <span className={controlStyles.tileUnit}>건</span>
          </span>
        }
        sub={
          oldest ? (
            // 1280 창의 칸 폭에 들도록 한 건이면 발생 시각과 경과만 적는다
            <>
              {opens.length > 1 ? "가장 오래된 " : ""}
              {hm(oldest.openedAt)} 발생 · <b className="num"><OpenElapsed from={oldest.openedAt} />째</b>
            </>
          ) : (
            "진행 중인 사건 없음"
          )
        }
      />
      <Tile
        icon="audit"
        label="오늘 사건"
        value={
          <span className={`${controlStyles.tileValue} num`}>
            <FlashNum value={t.count} tone={(prev, next) => (next > prev ? "warning" : null)} />
            <span className={controlStyles.tileUnit}>건</span>
          </span>
        }
        sub={
          <>
            해제 <b className="num">{t.count - t.open}</b> · 진행 <b className="num">{t.open}</b>
          </>
        }
      />
      <Tile
        icon="ack"
        label="평균 확인 시간 · MTTA"
        value={<span className={`${controlStyles.tileValue} ${mean.mttaMs === null ? controlStyles.tileValueText : ""} num`}>{mean.mttaMs === null ? "기록 없음" : durationWord(mean.mttaMs)}</span>}
        sub="오늘 연 사건 · 첫 확인·결정까지"
      />
      <Tile
        icon="timer"
        label="평균 해제 시간 · MTTR"
        value={<span className={`${controlStyles.tileValue} ${mean.mttrMs === null ? controlStyles.tileValueText : ""} num`}>{mean.mttrMs === null ? "기록 없음" : durationWord(mean.mttrMs)}</span>}
        sub="오늘 연 사건 · 발생부터 해제까지"
      />
    </section>
  );
}

export default function IncidentsPage() {
  const { data, loading, refresh } = useOpsCenter();
  const now = useClock(60_000);
  const [range, setRange] = useState<Range>("today");
  const [picked, setPicked] = useState<string | null>(null);
  const onChanged = () => void refresh(true);
  const log = data?.incidents.state === "READY" ? data.incidents.data : null;
  const policy = data?.oncall.state === "READY" ? data.oncall.data.policy : null;
  const list = log ? incidentsIn(log, range, now) : [];
  // 고른 사건이 거른 목록에 없으면 진행 중인 사건, 없으면 가장 최근 사건을 고른다
  const current = list.find((i) => i.id === picked) ?? list.find((i) => i.resolvedAt === null) ?? list[0] ?? null;

  // 다른 화면이 ?pick=로 한 사건을 가리키면 먼저 고른다. 드로어를 여는 ?incident=와 다르다
  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get("pick");
    if (id) setPicked(id);
  }, []);

  return (
    <div className={adminStyles.page}>
      <OpsHead title="경보·사건" center={data} actions={<GrafanaLink board="msp-overview" label="경보 추이" />} />
      {data === null ? (
        loading ? <PageSkeleton tiles={5} columns={[1, 1.15]} rows={[3, 4]} label="경보·사건 판을 불러오는 중입니다" /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <>
          <Summary center={data} />
          <div className={styles.layout}>
            <div className={styles.stack}>
              <section className={controlStyles.panel} aria-labelledby="inc-alerts">
                <PanelHead
                  id="inc-alerts"
                  title={
                    <>
                      활성 경보{data.alerts.state === "READY" && <span className={`${styles.count} num`}> {data.alerts.data.length}</span>}
                    </>
                  }
                  meta="확인은 기록이고 경보를 끄지 않습니다 · 보류는 30분까지"
                />
                <SectionBody section={data.alerts} what="경보">
                  {(alerts) => <AlertList alerts={alerts} onChanged={onChanged} context={{ log, policy, runbooks: true }} />}
                </SectionBody>
              </section>
              <section className={controlStyles.panel} aria-labelledby="inc-list">
                <PanelHead
                  id="inc-list"
                  title={
                    <>
                      사건<span className={`${styles.count} num`}> {list.length}</span>
                    </>
                  }
                  actions={<Segmented items={RANGES} value={range} onChange={setRange} ariaLabel="사건 기간" size="xs" />}
                />
                <SectionBody section={data.incidents} what="사건 기록">
                  {(l) =>
                    list.length === 0 ? (
                      <div className={controlStyles.empty}>
                        <span className={controlStyles.emptyMark} aria-hidden="true" />
                        이 기간 사건 없음
                      </div>
                    ) : (
                      <IncidentList list={list} log={l} policy={policy} picked={current?.id ?? null} onPick={setPicked} />
                    )
                  }
                </SectionBody>
              </section>
            </div>
            <div className={styles.stack}>
              {log && current ? (
                // 사건을 바꾸면 새로 그린다. 바꾼 순간의 기록 줄은 새로 온 줄이 아니다
                <IncidentFocus key={current.id} inc={current} log={log} policy={policy} />
              ) : (
                <section className={controlStyles.panel} aria-label="고른 사건">
                  <SectionBody section={data.incidents} what="사건 기록">
                    {() => (
                      <div className={controlStyles.empty}>
                        <span className={controlStyles.emptyMark} aria-hidden="true" />
                        이 기간 사건 없음
                      </div>
                    )}
                  </SectionBody>
                </section>
              )}
              {log && <LooseEvents log={log} />}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/** 사건 밖 운영 기록. 모드 전환, 경보 없는 조치, 백업처럼 사건에 속하지 않는 최근 기록이다 */
function LooseEvents({ log }: { log: IncidentLog }) {
  const loose = log.events.filter((e) => e.incidentId === null).slice(0, 8);
  if (loose.length === 0) return null;
  return (
    <section className={controlStyles.panel} aria-labelledby="inc-loose">
      <PanelHead id="inc-loose" title="사건 밖 운영 기록" meta="모드 전환, 백업처럼 사건에 속하지 않는 최근 기록" />
      <ol className={controlStyles.events}>
        {loose.map((e) => (
          <EventRow key={e.id} e={e} />
        ))}
      </ol>
    </section>
  );
}
