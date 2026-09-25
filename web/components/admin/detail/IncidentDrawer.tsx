"use client";

/**
 * 사건 드로어(오른쪽 560px, 디자인 시스템 §12-13의 2, 관리자 설계 4-9·4-10-2). 한 사건의 생애 다섯 단계, 확인·해제까지
 * 걸린 시간, 담당(바꾸기), 단계별 호출, 런북, 관련 경보, AI 판단, 운영자 조치, Teams 전송, 사후 분석을 한 줄기로 읽는다.
 * http 판에 기록 줄이 없는 사건은 열 때 한 번 읽는다. 담당·호출·사후 분석은 원천이 없으면(http) 원천 연결 전이다.
 * 주소의 ?incident= 가 이 층을 연다(DetailHost). 담당 지정과 사후 분석 말고는 실행 단추가 없다.
 */

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { escalationOf, incidentSections, incidentTimes, judgmentAt, todayMeans } from "@/lib/admin/opsDetail";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import { consoleRouteFor, runbookById, runbookHref } from "@/lib/admin/runbooks";
import { useCan } from "@/lib/admin/store";
import { kstDay, mdhm } from "@/lib/admin/timeText";
import { directionParticle } from "@/lib/admin/text";
import type { AdminOperator, ControlCenter, Incident, IncidentEvent } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import { useAdminResource } from "../useAdminResource";
import { Drawer } from "../layer/Layer";
import { SimMark, StateChip } from "../ui";
import { durationWord, hm, hms, useClock } from "../control/parts";
import { Lifecycle, OpenElapsed } from "../control/Timeline";
import { EscalationBar, NextPage } from "../incidents/Escalation";
import StatusShape from "../StatusShape";
import PostmortemForm from "./PostmortemForm";
import adminStyles from "../admin.module.css";
import controlStyles from "../control/control.module.css";
import styles from "./detail.module.css";

const ONCALL_SOURCE = "당번 명부·호출(GET /admin/v1/msp/oncall, 사건 확장 필드 · 3라운드 계약 초안)";

/** 사건 드로어의 기록 줄. 사건을 연 날과 다른 날의 줄(다음 날 쓴 사후 분석)은 날짜를 붙이고 목록의 시각 칸을 넓힌다 */
function Rows({ events, empty, day, link }: { events: IncidentEvent[]; empty: string; day: string; link?: (e: IncidentEvent) => React.ReactNode }) {
  if (events.length === 0) return <div className={styles.empty}>{empty}</div>;
  const other = (e: IncidentEvent) => kstDay(Date.parse(e.at)) !== day;
  return (
    <ol className={styles.rows} data-days={events.some(other) ? "" : undefined}>
      {events.map((e) => (
        <li key={e.id} className={styles.row}>
          <span className={`${styles.rowTime} num`}>{other(e) ? mdhm(e.at) : hms(e.at)}</span>
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

/** 담당. 지금 담당과 누가 언제 정했는지, 바꾸기(BOT_OPS 이상)다. 담당은 사건을 맡은 사람이고 보고서의 권고 담당 역할과 다르다 */
function OwnerSection({ inc, operators, onChanged }: {
  inc: Incident;
  operators: AdminOperator[] | null;
  onChanged: () => void;
}) {
  const canAssign = useCan("BOT_OPS");
  const owner = inc.owner;
  const [pick, setPick] = useState<string>("");
  // 고르기는 '바꾸기'를 누를 때 편다. 처음부터 다른 운영자를 골라 두면 지금 담당과 다른 이름이 담당처럼 읽힌다
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  if (owner === undefined) {
    return (
      <div className={controlStyles.notConnected} data-compact="">
        <span className={controlStyles.notConnectedTitle}>
          <span aria-hidden="true">
            <StatusShape kind="dashed" />{" "}
          </span>
          담당 원천 연결 전
        </span>
        <span className={controlStyles.notConnectedSource}>{ONCALL_SOURCE}</span>
      </div>
    );
  }
  const active = (operators ?? []).filter((o) => o.status === "ACTIVE");
  const choice = pick || owner?.operatorId || active[0]?.operatorId || "";
  const same = choice === owner?.operatorId;
  const block = !canAssign ? "봇 운영 이상 권한 필요" : operators === null ? "운영자 목록을 읽는 중" : active.length === 0 ? "지정할 운영자 없음" : same ? "지금 담당과 같음" : null;
  const assign = async () => {
    setBusy(true);
    try {
      await getAdminApi().assignIncident(inc.id, choice);
      const name = active.find((o) => o.operatorId === choice)?.displayName ?? choice;
      toast(`담당을 ${name}${directionParticle(name)} 지정했습니다`, "success");
      setPick("");
      setEditing(false);
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "담당 지정 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <div className={styles.owner}>
        <span className={styles.ownerName}>{owner?.name ?? "담당 없음"}</span>
        {owner && (
          <span className={styles.ownerMeta}>
            {owner.role} · {owner.assignedBy === "시스템" ? "자동 배정 · 당번" : `${owner.assignedBy} 지정`} ·{" "}
            <span className="num">{mdhm(owner.assignedAt)}</span>
          </span>
        )}
        {!editing && (
          <button
            type="button"
            className={`${adminStyles.neutralBtn} ${styles.ownerEdit}`}
            disabled={!canAssign}
            title={canAssign ? undefined : "봇 운영 이상 권한 필요"}
            onClick={() => setEditing(true)}
          >
            바꾸기
          </button>
        )}
      </div>
      {editing && (
        <div className={styles.formRow}>
          <label className={`${adminStyles.field} ${styles.grow}`}>
            <span className="sr-only">새 담당</span>
            <select value={choice} onChange={(e) => setPick(e.target.value)} disabled={!canAssign || active.length === 0}>
              {active.map((o) => (
                <option key={o.operatorId} value={o.operatorId}>
                  {o.displayName} · {o.operatorId}
                  {o.operatorId === owner?.operatorId ? " · 지금 담당" : ""}
                </option>
              ))}
            </select>
          </label>
          <button type="button" className={adminStyles.approveBtn} disabled={block !== null || busy} onClick={() => void assign()}>
            담당 지정
          </button>
          <button
            type="button"
            className={adminStyles.neutralBtn}
            onClick={() => {
              setPick("");
              setEditing(false);
            }}
          >
            취소
          </button>
        </div>
      )}
      {editing && block && block !== "지금 담당과 같음" && <p className={styles.note}>{block}</p>}
      <p className={styles.note}>담당은 이 사건을 맡은 사람입니다. 보고서가 권하는 담당 역할과 다르고, 지정은 사건 기록과 감사에 남습니다.</p>
    </>
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
  const { refresh } = useOpsCenter();
  const loadOperators = useCallback(() => getAdminApi().getOperators(), []);
  const ops = useAdminResource(loadOperators, { cadence: "manual" });
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
  const policy = center.oncall.state === "READY" ? center.oncall.data.policy : null;
  const esc = escalationOf(inc, policy, allEvents);
  const runbook = inc.runbookId ? runbookById(inc.runbookId) : null;
  const consoleRoute = runbook === null ? consoleRouteFor(inc.title) : null;
  const operators = ops.data ?? null;
  const onChanged = () => void refresh(true);
  // 오늘이 아닌 시각은 날짜를 붙인다. 생애 막대의 시각은 이 머리의 날짜를 따른다
  const when = (iso: string) => (kstDay(Date.parse(iso)) === kstDay(now) ? hm(iso) : mdhm(iso));
  const day = kstDay(Date.parse(inc.openedAt));

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
            {when(inc.openedAt)} 발생{inc.resolvedAt ? ` · ${when(inc.resolvedAt)} 해제` : ""}
          </span>
          <span className="num">기록 {events.length}건</span>
          {sim && <SimMark />}
        </>
      }
      onClose={onClose}
    >
      <section className={styles.section} aria-label="생애 단계">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>생애</span>
          <span className={styles.sectionMeta}>대응 칸 아래 점은 AI 판단과 승인입니다</span>
        </div>
        {pendingEvents && <p className={styles.note}>{loaded?.id === id && loaded.failed ? "이 사건의 기록 줄을 불러오지 못했습니다. 다시 열면 한 번 더 읽습니다" : "이 사건의 기록 줄을 불러오는 중입니다"}</p>}
        <Lifecycle inc={inc} events={asc} />
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
          {/* 해제 전에는 MTTR이 없다. 진행 중 경과를 MTTR 자리에 적으면 경과가 복구 시간처럼 읽힌다 */}
          <div className={styles.time}>
            <span className={styles.timeKey}>{t.ttrMs === null ? "경과 · 해제 전" : "해제까지 · MTTR"}</span>
            <span className={`${styles.timeValue} num`}>{t.ttrMs !== null ? durationWord(t.ttrMs) : <OpenElapsed from={inc.openedAt} />}</span>
            <span className={`${styles.timeSub} num`}>
              {t.ttrMs === null ? "MTTR은 해제한 뒤에 셉니다" : `오늘 평균 ${mean.mttrMs === null ? "기록 없음" : durationWord(mean.mttrMs)}`}
            </span>
          </div>
        </div>
      </section>

      <section className={styles.section} aria-label="담당">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>담당</span>
        </div>
        <OwnerSection inc={inc} operators={operators} onChanged={onChanged} />
      </section>

      <section className={styles.section} aria-label="호출">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>호출</span>
          <span className={`${styles.sectionMeta} num`}>
            {esc === null ? "원천 연결 전" : esc.next ? <NextPage view={esc} /> : "확인하거나 해제되면 멈춥니다"}
          </span>
        </div>
        <EscalationBar view={esc} source={ONCALL_SOURCE} />
        {esc !== null && (
          <Rows day={day} events={sec.pages} empty="보낸 호출이 없습니다. 발생 뒤 5분 안에 확인·결정하면 호출하지 않습니다" />
        )}
      </section>

      <section className={styles.section} aria-label="런북">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>런북</span>
        </div>
        {runbook ? (
          <div className={styles.runbook}>
            <Link href={runbookHref(runbook.id)} className={styles.runbookLink} onClick={onClose}>
              {runbook.title}
            </Link>
            <span className={styles.note}>
              {runbook.steps.length}단계 · 운영자가 SSM 세션에서 실행합니다 · 이 콘솔에는 실행 단추가 없습니다
            </span>
          </div>
        ) : consoleRoute ? (
          <div className={styles.runbook}>
            <Link href={consoleRoute.href} className={styles.runbookLink} onClick={onClose}>
              {consoleRoute.title}
            </Link>
            <span className={styles.note}>{consoleRoute.note}</span>
          </div>
        ) : (
          <div className={styles.empty}>이 사건의 경보에는 사람이 따로 할 절차가 없습니다. 경보가 풀리는지 지켜봅니다</div>
        )}
      </section>

      <section className={styles.section} aria-label="관련 경보">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>관련 경보</span>
          <span className={`${styles.sectionMeta} num`}>{sec.alerts.length}건</span>
        </div>
        <Rows day={day} events={sec.alerts} empty="이 사건에 경보 기록이 없습니다. 자동 조치 뒤 악화처럼 경보보다 먼저 연 사건입니다" />
      </section>

      <section className={styles.section} aria-label="AI 판단">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>AI 판단</span>
          <span className={`${styles.sectionMeta} num`}>{sec.ai.length}건</span>
        </div>
        <Rows
          day={day}
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
        <Rows day={day} events={sec.operator} empty="운영자가 한 조치가 없습니다" />
      </section>

      <section className={styles.section} aria-label="Teams 전송">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>Teams 전송</span>
          <span className={`${styles.sectionMeta} num`}>{sec.teams.length}건</span>
        </div>
        <Rows day={day} events={sec.teams} empty="이 사건으로 보낸 Teams 알림이 없습니다. 일일 보고는 보고서 화면에서 봅니다" />
      </section>

      <section className={styles.section} aria-label="사후 분석">
        <div className={styles.sectionHead}>
          <span className={styles.sectionTitle}>사후 분석</span>
          {inc.postmortem && (
            <span className={`${styles.sectionMeta} num`}>
              {inc.postmortem.status === "DONE" ? "완료" : "초안"} · {inc.postmortem.updatedBy} {mdhm(inc.postmortem.updatedAt)}
              {inc.postmortem.status === "DONE" ? "" : " 저장"}
            </span>
          )}
        </div>
        <PostmortemForm
          key={`${inc.id}-${inc.postmortem?.updatedAt ?? "none"}`}
          inc={inc}
          events={asc}
          operators={(operators ?? []).filter((o) => o.status === "ACTIVE").map((o) => o.displayName)}
          onSaved={onChanged}
        />
      </section>
    </Drawer>
  );
}
