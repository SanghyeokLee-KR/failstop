"use client";

/**
 * 경보·사건(/admin/incidents, 관리자 설계 4-2). 활성 경보를 확인·보류하고 사건이 발생에서 해제까지
 * 어떻게 흘렀는지 누가 언제 했는지와 함께 본다. 추이는 Grafana로 보낸다.
 */

import { useState } from "react";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import Skeleton from "@/components/Skeleton";
import GrafanaLink from "@/components/admin/GrafanaLink";
import { AdminErrorState } from "@/components/admin/ResourceState";
import AlertList from "@/components/admin/control/Alerts";
import OpsHead from "@/components/admin/control/OpsHead";
import { alertCounts, Tile, todayIncidents } from "@/components/admin/control/SummaryStrip";
import { byFilter, EventRow, FilterSwitch, IncidentView, type EventFilter } from "@/components/admin/control/Timeline";
import { durationWord, hm, PanelHead, SectionBody, SEVERITY_SHAPE, useClock } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";

export default function IncidentsPage() {
  const { data, loading, refresh } = useOpsCenter();
  const now = useClock();
  const [picked, setPicked] = useState<string | null>(null);
  const [filter, setFilter] = useState<EventFilter>("ALL");
  const onChanged = () => void refresh();
  const today = new Date(now).toDateString();

  return (
    <div className={adminStyles.page}>
      <OpsHead
        title="경보·사건"
        desc="경보를 확인·보류하고 사건이 발생에서 해제까지 흐른 순서를 봅니다. 추이는 Grafana"
        center={data}
        now={now}
        actions={<GrafanaLink board="msp-overview" label="경보 추이" />}
      />
      {data === null ? (
        loading ? <Skeleton variant="card" width="100%" height={420} /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <>
          <section className={styles.summary} data-cols="4" aria-label="경보·사건 요약">
            {data.alerts.state === "READY" && data.incidents.state === "READY" ? (
              (() => {
                const c = alertCounts(data.alerts.data);
                const t = todayIncidents(data.incidents.data, now);
                const open = data.incidents.data.incidents.find((i) => i.resolvedAt === null) ?? null;
                const tone = c.critical > 0 ? "danger" : c.warning > 0 ? "warning" : undefined;
                return (
                  <>
                    <Tile
                      icon="incidents"
                      label="활성 경보"
                      tone={tone}
                      value={
                        <span className={`${styles.tileValue} num`} data-tone={tone}>
                          {data.alerts.data.length}
                          <span className={styles.tileUnit}>건</span>
                        </span>
                      }
                      sub={
                        <span className={styles.alertCounts}>
                          {(["critical", "warning", "info"] as const).map((sev) => (
                            <span key={sev} className={styles.alertCount} data-sev={sev} data-zero={c[sev] === 0 ? "" : undefined}>
                              <span aria-hidden="true">{SEVERITY_SHAPE[sev]}</span>
                              <b className="num">{c[sev]}</b>
                            </span>
                          ))}
                          <span>장애 · 주의 · 정보</span>
                        </span>
                      }
                    />
                    <Tile
                      icon="clock"
                      label="진행 중 사건"
                      tone={open ? "warning" : undefined}
                      value={
                        <span className={`${styles.tileValue} num`}>
                          {open ? 1 : 0}
                          <span className={styles.tileUnit}>건</span>
                        </span>
                      }
                      sub={open ? `${hm(open.openedAt)}부터 · ${durationWord(now - Date.parse(open.openedAt))}` : "진행 중인 사건 없음"}
                    />
                    <Tile
                      icon="audit"
                      label="오늘 사건"
                      value={
                        <span className={`${styles.tileValue} num`}>
                          {t.count}
                          <span className={styles.tileUnit}>건</span>
                        </span>
                      }
                      sub={`해제 ${t.count - t.open} · 진행 ${t.open}`}
                    />
                    <Tile
                      icon="clock"
                      label="평균 복구 시간"
                      value={<span className={`${styles.tileValue} ${styles.tileValueText} num`}>{t.mttrMs === null ? "기록 없음" : durationWord(t.mttrMs)}</span>}
                      sub="오늘 해제한 사건 · 발생부터 해제까지"
                    />
                  </>
                );
              })()
            ) : null}
          </section>
          <div className={styles.split}>
            <div className={styles.stack}>
              <section className={styles.panel} aria-labelledby="inc-alerts">
                <PanelHead id="inc-alerts" title="활성 경보" meta="확인은 기록이고 경보를 끄지 않습니다. 보류는 최대 30분" />
                <SectionBody section={data.alerts} what="경보">
                  {(alerts) => <AlertList alerts={alerts} now={now} onChanged={onChanged} />}
                </SectionBody>
              </section>
              <section className={styles.panel} aria-labelledby="inc-list">
                <PanelHead id="inc-list" title="오늘 사건" meta="누르면 오른쪽에 기록을 펼칩니다" />
                <SectionBody section={data.incidents} what="사건 기록">
                  {(log) => {
                    const list = log.incidents.filter((i) => new Date(i.openedAt).toDateString() === today);
                    if (list.length === 0) {
                      return (
                        <div className={styles.empty}>
                          <span className={styles.emptyMark} aria-hidden="true" />
                          오늘 사건 없음
                        </div>
                      );
                    }
                    return (
                      <ul className={styles.incList}>
                        {list.map((i, n) => {
                          const selected = (picked ?? list[0]?.id) === i.id;
                          const open = i.resolvedAt === null;
                          return (
                            <li key={i.id}>
                              <button type="button" className={styles.incRow} aria-pressed={selected} onClick={() => setPicked(i.id)}>
                                <span className={styles.shape} data-tone={open ? "warning" : "success"} aria-hidden="true">
                                  {open ? SEVERITY_SHAPE[i.severity] : "●"}
                                </span>
                                <span>
                                  <b>{i.title}</b>
                                  <br />
                                  {hm(i.openedAt)} 발생 · {open ? "진행 중" : `${durationWord(Date.parse(i.resolvedAt as string) - Date.parse(i.openedAt))} 만에 해제`}
                                </span>
                                <span className="num">#{list.length - n}</span>
                              </button>
                            </li>
                          );
                        })}
                      </ul>
                    );
                  }}
                </SectionBody>
              </section>
            </div>
            <section className={styles.panel} aria-labelledby="inc-detail">
              <PanelHead
                id="inc-detail"
                title="사건 기록"
                meta="발생 · AI 판단 · 승인 · 조치 · 해제"
                actions={<FilterSwitch value={filter} onChange={setFilter} label="기록 거르기" />}
              />
              <SectionBody section={data.incidents} what="사건 기록">
                {(log) => {
                  const list = log.incidents.filter((i) => new Date(i.openedAt).toDateString() === today);
                  const inc = list.find((i) => i.id === (picked ?? list[0]?.id)) ?? null;
                  const loose = log.events.filter((e) => e.incidentId === null).filter(byFilter(filter)).slice(0, 12);
                  return (
                    <div className={styles.stack}>
                      {inc ? (
                        <IncidentView inc={inc} log={log} now={now} filter={filter} />
                      ) : (
                        <div className={styles.empty}>
                          <span className={styles.emptyMark} aria-hidden="true" />
                          오늘 사건 없음
                        </div>
                      )}
                      {loose.length > 0 && (
                        <>
                          <div className={styles.divider} />
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
                }}
              </SectionBody>
            </section>
          </div>
        </>
      )}
    </div>
  );
}
