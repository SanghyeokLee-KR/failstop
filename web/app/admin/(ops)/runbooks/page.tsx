"use client";

/**
 * 런북(/admin/runbooks, 관리자 설계 4-10-4). 이 상황에서 사람은 무엇을 어떤 순서로 확인하고 되돌리는지에 답한다. 왼쪽 목록
 * 칸(340px, 위에 붙음)과 오른쪽 상세다. 상세는 쓰는 상황, 누가 어디서, AI가 하지 않는 이유, 관련 경보(지금 울리는지), 단계
 * (세로 레일. 할 일, 확인 조건, 명령 복사 칩), 되돌리기, 관련 문서, 마지막 실행 기록, 이 런북을 가리킨 사건이다.
 *
 * 운영자가 SSM 세션에서 실행하는 절차라 이 화면에는 실행 단추가 없다. 명령 칩은 누르면 복사만 된다. 명령은 원문 문서에 적힌
 * 것만 옮긴다(runbooks.ts). 목록과 단계는 두 원천이 같은 정적 자료이고, 관련 경보의 '지금 울림'과 사건만 관제 판을 읽는다.
 * 마지막 실행 기록은 SSM 세션 기록을 읽는 경로와 권한이 없어 두 원천 모두 연결 전이다.
 */

import { useEffect, useState, type KeyboardEvent, type ReactNode } from "react";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { useOpsCenter, useOpsPick } from "@/lib/admin/opsCenter";
import { RUNBOOKS, runbookForAlert } from "@/lib/admin/runbooks";
import { mdhm } from "@/lib/admin/timeText";
import type { Incident, OpsAlert, OpsSection, Runbook, RunbookId } from "@/lib/admin/types";
import { CommandChip } from "@/components/admin/CommandChip";
import KeepNames from "@/components/admin/KeepNames";
import StatusShape from "@/components/admin/StatusShape";
import OpsHead from "@/components/admin/control/OpsHead";
import { NotConnected, SectionGap } from "@/components/admin/control/parts";
import { StateChip } from "@/components/admin/ui";
import adminStyles from "@/components/admin/admin.module.css";
import controlStyles from "@/components/admin/control/control.module.css";
import styles from "@/components/admin/runbooks/runbooks.module.css";

const LAST_RUN_SOURCE = "SSM 세션 기록(AWS Systems Manager 세션 기록을 읽는 경로와 권한 없음)";

/** 머리. 출처 표식이 판을 매 박자 읽으므로 이 조각만 판을 구독한다 */
function Head() {
  const { data } = useOpsCenter();
  return <OpsHead title="런북" center={data} />;
}

/** 이 런북으로 이어지는 지금 울리는 경보. 판의 경보는 해제되지 않은 것만이라 확인·보류한 경보도 센다 */
function firingOf(id: RunbookId, alerts: OpsAlert[]): OpsAlert[] {
  return alerts.filter((a) => runbookForAlert(a) === id);
}

/** 런북 표의 경보 한 쌍이 지금 울리는가. 서버 규칙 이름의 괄호(대상 서비스)는 떼고 견준다 */
function pairFiring(pair: Runbook["alerts"][number], firing: OpsAlert[]): OpsAlert | null {
  const rule = pair.rule?.replace(/\(.*\)$/, "") ?? null;
  return firing.find((a) => a.name === pair.sim || (rule !== null && a.name === rule)) ?? null;
}

function worst(list: OpsAlert[]): "danger" | "warning" | null {
  if (list.some((a) => a.severity === "critical")) return "danger";
  return list.length > 0 ? "warning" : null;
}

function RunbookList({ picked, onPick, alerts }: { picked: RunbookId; onPick: (id: RunbookId) => void; alerts: OpsAlert[] | null }) {
  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    const i = RUNBOOKS.findIndex((r) => r.id === picked);
    e.preventDefault();
    const next = RUNBOOKS[(i + (e.key === "ArrowDown" ? 1 : RUNBOOKS.length - 1)) % RUNBOOKS.length];
    onPick(next.id);
    e.currentTarget.querySelectorAll<HTMLButtonElement>("button")[RUNBOOKS.indexOf(next)]?.focus();
  };
  return (
    <aside className={styles.listPanel} aria-labelledby="rb-list">
      <div className={styles.listHead}>
        <h2 id="rb-list" className={styles.listTitle}>
          런북
        </h2>
        <span className={`${styles.listMeta} num`}>{RUNBOOKS.length}개 · 사람이 SSM에서 실행</span>
      </div>
      <div className={styles.list} role="group" aria-label="런북 목록 · 위아래 화살표로 고름" onKeyDown={onKey}>
        {RUNBOOKS.map((r) => {
          const firing = alerts ? firingOf(r.id, alerts) : [];
          const tone = worst(firing);
          return (
            <button key={r.id} type="button" className={styles.row} aria-current={r.id === picked ? "true" : undefined} onClick={() => onPick(r.id)}>
              <span className={styles.rowTitle}>{r.title}</span>
              {tone ? (
                <span className={styles.rowFiring} data-tone={tone}>
                  <StatusShape kind={tone === "danger" ? "square" : "triangle"} size={9} />
                  <span className="num">경보 {firing.length}</span>
                </span>
              ) : (
                <span />
              )}
              <span className={styles.rowWhen}>
                <KeepNames text={r.when} />
              </span>
              <span className={`${styles.rowMeta} num`}>
                단계 {r.steps.length} · 명령 {r.steps.reduce((n, s) => n + s.commands.length, 0)}
              </span>
            </button>
          );
        })}
      </div>
      <p className={styles.listFoot}>명령은 원문 문서에 적힌 것만 옮깁니다. 칩을 누르면 복사만 됩니다</p>
    </aside>
  );
}

function Fact({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className={styles.fact}>
      <span className={styles.factKey}>{title}</span>
      {children}
    </div>
  );
}

function RelatedAlerts({ rb, alerts }: { rb: Runbook; alerts: OpsSection<OpsAlert[]> | null }) {
  if (alerts === null) return <span className={styles.factText}>관제 판을 읽는 중입니다</span>;
  if (alerts.state !== "READY") return <SectionGap section={alerts} what="경보" compact />;
  const firing = firingOf(rb.id, alerts.data);
  return (
    <ul className={styles.alerts}>
      {rb.alerts.map((p) => {
        const hit = pairFiring(p, firing);
        const tone = hit ? (hit.severity === "critical" ? "danger" : "warning") : undefined;
        return (
          <li key={`${p.sim ?? ""}-${p.rule ?? ""}`} className={styles.alert} data-tone={tone}>
            <StatusShape kind={hit ? (hit.severity === "critical" ? "square" : "triangle") : "ring"} size={9} />
            <span className={styles.alertName}>
              {p.sim && <span>{p.sim}</span>}
              {p.sim && p.rule && " · "}
              {p.rule && <code>{p.rule}</code>}
              {" · "}
              <span className={styles.alertState}>
                {hit ? `${hit.state === "SILENCED" ? "울림 · 보류 중" : hit.state === "ACKED" ? "울림 · 확인함" : "지금 울림"} · ${mdhm(hit.since)}부터` : "울리지 않음"}
              </span>
            </span>
          </li>
        );
      })}
    </ul>
  );
}

function RelatedIncidents({ rb, incidents }: { rb: Runbook; incidents: OpsSection<{ incidents: Incident[] }> | null }) {
  if (incidents === null || incidents.state !== "READY") {
    return incidents === null ? null : <SectionGap section={incidents} what="사건 기록" compact />;
  }
  const list = incidents.data.incidents.filter((i) => i.runbookId === rb.id).slice(0, 5);
  if (list.length === 0) {
    return (
      <div className={controlStyles.empty}>
        <span className={controlStyles.emptyMark} aria-hidden="true" />이 런북을 가리킨 사건 없음
      </div>
    );
  }
  return (
    <ul className={styles.incidents}>
      {list.map((i) => {
        const open = i.resolvedAt === null;
        const tone = open ? (i.severity === "critical" ? "danger" : "warning") : "success";
        return (
          <li key={i.id}>
            <button type="button" className={styles.incident} aria-haspopup="dialog" onClick={() => useOpsDetail.getState().openIncident(i.id)}>
              <StatusShape kind={open ? (i.severity === "critical" ? "square" : "triangle") : "circle"} tone={tone} size={9} />
              <span className={styles.incidentTitle}>{i.title}</span>
              <span className={`${styles.incidentWhen} num`}>{open ? `${mdhm(i.openedAt)} 발생 · 진행 중` : `${mdhm(i.openedAt)} · 해제`}</span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

function RunbookDetail({ rb, alerts, incidents }: { rb: Runbook; alerts: OpsSection<OpsAlert[]> | null; incidents: OpsSection<{ incidents: Incident[] }> | null }) {
  return (
    <div className={styles.detailWrap}>
      <article className={styles.detail} key={rb.id} aria-labelledby="rb-title">
        <header className={styles.detailHead}>
          <div className={styles.titleRow}>
            <h2 id="rb-title" className={styles.title}>
              {rb.title}
            </h2>
            <StateChip tone="neutral" shape="ring" label="사람이 실행" />
          </div>
          <p className={styles.when}>
            <KeepNames text={rb.when} />
          </p>
        </header>

        <div className={styles.facts}>
          <Fact title="누가 어디서">
            <span className={styles.factText}>운영자가 SSM 세션에서 실행합니다 · 이 콘솔에는 실행 단추가 없습니다</span>
          </Fact>
          <Fact title="AI가 하지 않는 이유">
            <span className={styles.factText}>
              <KeepNames text={rb.notForAi} />
            </span>
          </Fact>
          <Fact title="관련 경보">
            <RelatedAlerts rb={rb} alerts={alerts} />
          </Fact>
        </div>

        <section aria-labelledby="rb-steps">
          <div className={styles.sectionHead}>
            <h3 id="rb-steps" className={styles.sectionTitle}>
              단계
            </h3>
            <span className={`${styles.sectionMeta} num`}>{rb.steps.length}단계 · 확인 조건을 지나야 다음 단계로 갑니다</span>
          </div>
          <ol className={styles.steps}>
            {rb.steps.map((s, i) => (
              <li key={s.title} className={styles.step}>
                <span className={`${styles.stepNo} num`} aria-hidden="true">
                  {i + 1}
                </span>
                <div className={styles.stepBody}>
                  <span className={styles.stepTitle}>
                    <span className="sr-only">{i + 1}단계 </span>
                    {s.title}
                  </span>
                  <p className={styles.stepText}>
                    <KeepNames text={s.detail} />
                  </p>
                  <p className={styles.check}>
                    <span className={styles.checkKey}>확인</span>
                    <span>
                      <KeepNames text={s.check} />
                    </span>
                  </p>
                  {s.commands.length > 0 ? (
                    <ul className={styles.commands} aria-label={`${i + 1}단계 명령`}>
                      {s.commands.map((c) => (
                        <li key={c}>
                          <CommandChip command={c} />
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <span className={styles.noCommand}>원문에 명령이 없는 단계입니다 · 할 일과 확인 조건만 적습니다</span>
                  )}
                </div>
              </li>
            ))}
          </ol>
        </section>

        <div className={styles.twoCol}>
          <section aria-labelledby="rb-rollback">
            <div className={styles.sectionHead}>
              <h3 id="rb-rollback" className={styles.sectionTitle}>
                되돌리기
              </h3>
            </div>
            <p className={styles.rollback}>
              <KeepNames text={rb.rollback} />
            </p>
          </section>
          <section aria-labelledby="rb-sources">
            <div className={styles.sectionHead}>
              <h3 id="rb-sources" className={styles.sectionTitle}>
                관련 문서
              </h3>
              <span className={styles.sectionMeta}>배포한 웹은 저장소 문서를 서빙하지 않아 경로를 복사합니다</span>
            </div>
            <ul className={styles.sources}>
              {rb.sources.map((s) => (
                <li key={`${s.path}-${s.section}`} className={styles.source}>
                  <CommandChip command={s.path} kind="path" />
                  <span>
                    <KeepNames text={`'${s.section}'`} />
                  </span>
                </li>
              ))}
            </ul>
          </section>
        </div>

        <div className={styles.twoCol}>
          <section aria-labelledby="rb-last">
            <div className={styles.sectionHead}>
              <h3 id="rb-last" className={styles.sectionTitle}>
                마지막 실행 기록
              </h3>
            </div>
            <NotConnected what="실행 기록" source={LAST_RUN_SOURCE} compact />
          </section>
          <section aria-labelledby="rb-incidents">
            <div className={styles.sectionHead}>
              <h3 id="rb-incidents" className={styles.sectionTitle}>
                이 런북을 가리킨 사건
              </h3>
              <span className={styles.sectionMeta}>최근 다섯 건</span>
            </div>
            <RelatedIncidents rb={rb} incidents={incidents} />
          </section>
        </div>
      </article>
    </div>
  );
}

export default function RunbooksPage() {
  const alerts = useOpsPick((c) => c?.alerts ?? null);
  const incidents = useOpsPick((c) => c?.incidents ?? null);
  const [picked, setPicked] = useState<RunbookId>(RUNBOOKS[0].id);

  // 경보 행과 사건 드로어의 '런북' 링크가 ?id=<런북>으로 이 화면을 열고 그 런북을 고른다
  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get("id");
    if (id && RUNBOOKS.some((r) => r.id === id)) setPicked(id as RunbookId);
  }, []);

  const pick = (id: RunbookId) => {
    setPicked(id);
    const q = new URLSearchParams(window.location.search);
    q.set("id", id);
    window.history.replaceState(window.history.state, "", `${window.location.pathname}?${q.toString()}`);
  };
  const rb = RUNBOOKS.find((r) => r.id === picked) ?? RUNBOOKS[0];
  const liveAlerts = alerts?.state === "READY" ? alerts.data : null;

  return (
    <div className={adminStyles.page}>
      <Head />
      <div className={styles.layout}>
        <RunbookList picked={rb.id} onPick={pick} alerts={liveAlerts} />
        <RunbookDetail rb={rb} alerts={alerts} incidents={incidents} />
      </div>
    </div>
  );
}
