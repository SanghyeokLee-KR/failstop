"use client";

/**
 * 설정(/admin/settings, 관리자 설계 4-8·4-10-7). 무엇에 연결돼 있고 누구에게 어떤 순서로 알리는지에 답한다. 값을 바꾸는
 * 설정(임계값, AI 한도, AI 모드)은 여기서 바꾸지 않고 승인 절차가 있는 그 화면으로 보낸다.
 *
 * 판은 위에서부터 연동 표, 담당자·연락 체계와 오른쪽 칸(Teams 연결 시험, AI 한도·모드), 호출 단계와 당번, 알림 채널, 역할·권한
 * 행렬이다. 연동과 알림 채널은 이미 받는 관제 판과 보고 서비스의 전송 기록에서 가린다(integrations.ts). 연락 체계, 호출 정책,
 * 당번표는 관제 판의 당번 칸(oncall)이고 http는 원천 연결 전이다. 역할·권한 행렬은 두 원천이 같은 정적 표(roles.ts)이고 운영자
 * 목록(GET /admin/v1/operators)으로 역할마다 가진 사람과 지금 운영자의 '보유'를 단다. 밖으로 메시지를 보내는 'Teams 테스트
 * 전송'은 sim에서만 모의로 동작하고, http는 서버 계약이 생기기 전까지 비활성이다.
 */

import { useCallback, useState, type ReactNode } from "react";
import Link from "next/link";
import { AdminError, getAdminApi, isAdminHttp } from "@/lib/admin/api";
import { AI_LIMIT_DEFS } from "@/lib/admin/aiPolicy";
import { channelsOf, integrationsOf, STATE_WORD, type ChannelRow, type IntegrationRow, type IntegrationState } from "@/lib/admin/integrations";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import { ESCALATION_LEVEL_WORD, type StageMark } from "@/lib/admin/opsDetail";
import { OPS_PERMISSIONS, ROLE_DESC, ROLE_LABEL, roleAllows, type OpsPermission } from "@/lib/admin/roles";
import { useAdminSession, useCan } from "@/lib/admin/store";
import { hm, kst, mdw } from "@/lib/admin/timeText";
import type { AdminOperator, AdminRole, OnCallDesk, OpsSection } from "@/lib/admin/types";
import Skeleton from "@/components/Skeleton";
import { toast } from "@/components/Toast";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import GrafanaLink, { grafanaConfigured } from "@/components/admin/GrafanaLink";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import OpsHead from "@/components/admin/control/OpsHead";
import { DatabasusLink } from "@/components/admin/control/Jobs";
import StageBar from "@/components/admin/StageBar";
import StatusShape, { GLYPH_SHAPE } from "@/components/admin/StatusShape";
import { SimMark, StateChip } from "@/components/admin/ui";
import { Ago, NotConnected, PanelHead, SectionGap, sectionSource } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import controlStyles from "@/components/admin/control/control.module.css";
import styles from "@/components/admin/settings/settings.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";
const DATABASUS = (process.env.NEXT_PUBLIC_DATABASUS_BASE ?? "").length > 0;
/** 행렬의 역할 열 순서. 권한이 좁은 쪽에서 넓은 쪽이고 회원 운영은 관제 조작과 따로 선 역할이라 총괄 앞이다 */
const ROLE_ORDER: AdminRole[] = ["VIEWER", "BOT_OPS", "CHAOS_OPS", "MEMBER_MOD", "SUPER"];

function RowAction({ row }: { row: IntegrationRow }) {
  switch (row.key) {
    case "grafana":
      return <GrafanaLink board="msp-overview" label="Grafana 열기" />;
    case "databasus":
      return <DatabasusLink />;
    case "alertmanager":
      return (
        <Link href="/admin/incidents" className={controlStyles.stripLink}>
          경보·사건
        </Link>
      );
    case "ai1":
      return (
        <Link href="/admin/hosts" className={controlStyles.stripLink}>
          서버·노드
        </Link>
      );
    case "teams":
      return (
        <Link href="/admin/reports" className={controlStyles.stripLink}>
          보고서
        </Link>
      );
    default:
      return null;
  }
}

/** 연동 상태 한 칸. 연동 표와 알림 채널 표가 같은 모양으로 적는다 */
function StateWord({ state }: { state: IntegrationState }) {
  if (state === "ASSUMED") return <SimMark kind="assumed" />;
  const w = STATE_WORD[state];
  return (
    <span className={styles.state} data-tone={w.tone === "success" ? undefined : w.tone}>
      <StatusShape kind={GLYPH_SHAPE[w.shape]} tone={w.tone} className={styles.shape} />
      {w.word}
    </span>
  );
}

function IntegrationTable({ rows }: { rows: IntegrationRow[] }) {
  return (
    <table className={styles.integrations}>
      <thead>
        <tr>
          <th scope="col">원천</th>
          <th scope="col">상태</th>
          <th scope="col">현재값</th>
          <th scope="col" data-num="">
            마지막 확인
          </th>
          <th scope="col">
            <span className="sr-only">바로 가기</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.key}>
            <td>
              <span className={styles.name}>
                <b>{r.name}</b>
                <span>{r.role}</span>
              </span>
            </td>
            <td>
              <StateWord state={r.state} />
            </td>
            <td className={styles.detail}>{r.detail}</td>
            <td data-num="" className={`${styles.checked} num`}>
              {r.checkedAt ? <Ago at={r.checkedAt} /> : r.key === "grafana" ? "확인하지 않음" : "없음"}
            </td>
            <td data-act="">
              <RowAction row={r} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** 담당자·연락 체계. 관제 판의 당번 칸에서 명부를 읽고, 당번 교대 줄에는 지금 당번을 함께 적는다 */
function ContactsPanel({ oncall, sim }: { oncall: OpsSection<OnCallDesk>; sim: boolean }) {
  return (
    <section className={controlStyles.panel} aria-labelledby="set-oncall">
      <PanelHead id="set-oncall" title="담당자·연락 체계" meta={oncall.state === "READY" && sim ? "예시 명부" : undefined} />
      {oncall.state !== "READY" ? (
        <SectionGap section={oncall} what="연락 체계" />
      ) : (
        <>
          <table className={styles.contacts}>
            <thead>
              <tr>
                <th scope="col">담당 역할</th>
                <th scope="col">담당자</th>
                <th scope="col">연락 경로</th>
                <th scope="col">대응 시간</th>
              </tr>
            </thead>
            <tbody>
              {oncall.data.roster.map((e) => {
                const duty = oncall.data.onDuty && e.role === oncall.data.onDuty.role ? oncall.data.onDuty : null;
                return (
                  <tr key={e.role}>
                    <td>
                      <b>{e.role}</b>
                    </td>
                    <td>
                      {e.person}
                      {duty ? <small>지금 {duty.person}</small> : e.operatorId && <small className="num">{e.operatorId}</small>}
                    </td>
                    <td>{e.channel}</td>
                    <td>{e.hours}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className={styles.reason}>담당 역할 이름은 사건 보고의 권고 담당 역할과 같습니다. 권고 담당은 SLA나 작업 배정이 아닙니다.</p>
        </>
      )}
    </section>
  );
}

/**
 * 호출 정책의 단계 막대 칸. 첫 알림(Teams)과 정책의 세 단계다. 진행이 없는 정책이라 단계 막대의 정책 보기로 그리고, 칸 아래에
 * 누구(역할과 사람)와 어떤 경로를 두 줄로 적는다. 1차의 사람은 그 주의 당번이다
 */
function policyStages(desk: OnCallDesk): { items: StageMark[]; lines: Map<string, { who: string; how: string }> } {
  const lines = new Map<string, { who: string; how: string }>([["first", { who: "Teams 운영 채널", how: "경보 카드" }]]);
  for (const s of desk.policy) {
    const person = desk.onDuty && s.role === desk.onDuty.role ? desk.onDuty.person : desk.roster.find((r) => r.role === s.role)?.person ?? null;
    lines.set(`l${s.level}`, { who: person ? `${s.role} · ${person}` : s.role, how: s.channel });
  }
  // 칸의 보조 말은 화면 낭독이 읽는 글이다. 보이는 두 줄은 renderWhen이 같은 값으로 그린다
  const mark = (key: string, label: string): StageMark => {
    const l = lines.get(key);
    return { key, label, state: "todo", at: null, note: l ? `${l.who} · ${l.how}` : null, ai: false, marks: [] };
  };
  const items = [mark("first", "첫 알림 · 0분"), ...desk.policy.map((s) => mark(`l${s.level}`, `${ESCALATION_LEVEL_WORD[s.level]} · ${s.afterMin}분`))];
  return { items, lines };
}

function EscalationPanel({ oncall }: { oncall: OpsSection<OnCallDesk> }) {
  return (
    <section className={controlStyles.panel} aria-labelledby="set-escalation">
      <PanelHead id="set-escalation" title="호출 단계와 당번" meta="사건이 열린 뒤 확인·결정이 없을 때" />
      {oncall.state !== "READY" ? <SectionGap section={oncall} what="호출 정책과 당번표" /> : <EscalationBody desk={oncall.data} />}
    </section>
  );
}

function EscalationBody({ desk }: { desk: OnCallDesk }) {
  const { items, lines } = policyStages(desk);
  const renderWhen = (m: StageMark): ReactNode => {
    const l = lines.get(m.key);
    return l ? (
      <>
        <span className={styles.stageWho}>{l.who}</span>
        <span className={styles.stageHow}>{l.how}</span>
      </>
    ) : null;
  };
  return (
    <div className={styles.escalation}>
      <StageBar items={items} label="호출 단계" plan renderWhen={renderWhen} />
      <div className={styles.escalationFoot}>
        <div>
          <div className={styles.subHead}>
            <h3 className={styles.subTitle}>당번표</h3>
            <span className={styles.subMeta}>매주 월요일 09:00 교대</span>
          </div>
          {desk.rotation.length === 0 ? (
            <p className={styles.reason}>당번 교대 없음</p>
          ) : (
            <table className={styles.rotation}>
              <tbody>
                {desk.rotation.map((w, i) => {
                  const now = desk.onDuty !== null && w.operatorId === desk.onDuty.operatorId && w.until === desk.onDuty.until;
                  return (
                    <tr key={w.from}>
                      <th scope="row">{i === 0 ? "이번 주" : "다음 주"}</th>
                      <td>
                        <span className={styles.duty}>
                          <b>{w.person}</b>
                          {now && <StateChip tone="neutral" label="지금 당번" />}
                        </span>
                      </td>
                      <td className="num">
                        {mdw(w.from)} {hm(w.from)} ~ {mdw(w.until)} {hm(w.until)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
        <dl className={styles.rules}>
          <dt>멈춤</dt>
          <dd>누구든 경보를 확인하거나 조치안을 결정하면 다음 호출을 멈춥니다. 경보가 해제돼도 멈춥니다.</dd>
          <dt>담당 배정</dt>
          <dd>1차 호출을 받는 당번이 새 사건의 담당으로 자동 배정됩니다.</dd>
          <dt>바꾸는 곳</dt>
          <dd>이 화면은 읽기만 합니다. 명부 원천이 생기면 그 원천에서 바꿉니다.</dd>
        </dl>
      </div>
    </div>
  );
}

function ChannelPanel({ rows }: { rows: ChannelRow[] }) {
  return (
    <section className={controlStyles.panel} aria-labelledby="set-channels">
      <PanelHead id="set-channels" title="알림 채널" meta="상태는 연동 표와 같은 판정" />
      <table className={`${styles.integrations} ${styles.channels}`}>
        <thead>
          <tr>
            <th scope="col">받는 곳</th>
            <th scope="col">상태</th>
            <th scope="col">쓰는 일</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key}>
              <td className={styles.channelName}>
                <span className={styles.name}>
                  <b>{r.name}</b>
                  {r.via && <span>{r.via}</span>}
                </span>
              </td>
              <td className={styles.channelState}>
                {r.state === null ? (
                  <span className={styles.none}>경로 없음</span>
                ) : (
                  <span className={styles.stateStack}>
                    <StateWord state={r.state} />
                    {r.detail && <span className={styles.stateDetail}>{r.detail}</span>}
                  </span>
                )}
              </td>
              <td className={styles.detail}>{r.uses}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/**
 * 역할·권한 행렬(읽기 전용). 행은 운영 관제 조작, 열은 역할 다섯이고 칸의 점은 그 역할 하나로 할 수 있다는 뜻이다. 열 머리에
 * 그 역할을 가진 운영자와, 지금 운영자가 가진 역할이면 '보유' 칩을 단다. 운영자 목록을 못 읽으면 보유 칩과 사람만 비운다
 */
function PermissionPanel({ operators, failed, me }: { operators: AdminOperator[] | null; failed: boolean; me: string | null }) {
  const mine = operators?.find((o) => o.operatorId === me)?.roles ?? [];
  const holders = (r: AdminRole) => (operators ?? []).filter((o) => o.status === "ACTIVE" && o.roles.includes(r)).map((o) => o.displayName);
  const groups: [string, OpsPermission[]][] = [];
  for (const p of OPS_PERMISSIONS) {
    const g = groups.find(([name]) => name === p.group);
    if (g) g[1].push(p);
    else groups.push([p.group, [p]]);
  }
  return (
    <section className={`${controlStyles.panel} ${styles.permPanel}`} aria-labelledby="set-roles">
      <PanelHead
        id="set-roles"
        title="역할·권한"
        meta={failed ? "운영자 목록 불러오기 실패 · 보유 표시 없음" : "읽기 전용"}
        actions={
          <Link href="/admin/operators" className={controlStyles.stripLink}>
            운영자·역할 관리
          </Link>
        }
      />
      <div className={styles.permLayout}>
        <div className={styles.matrixWrap}>
          <table className={styles.matrix}>
            <caption className="sr-only">운영 관제 조작마다 역할별로 할 수 있는지와 승인 티켓</caption>
            <thead>
              <tr>
                <th scope="col" className={styles.groupHead}>
                  <span className="sr-only">묶음</span>
                </th>
                <th scope="col">조작</th>
                {ROLE_ORDER.map((r) => {
                  const who = holders(r);
                  const held = mine.includes(r);
                  return (
                    <th key={r} scope="col" data-role="" data-held={held ? "" : undefined} title={ROLE_DESC[r]}>
                      <span className={styles.roleHead}>
                        <b>{ROLE_LABEL[r]}</b>
                        <code>{r}</code>
                        {operators !== null && <span className={styles.holders}>{who.length > 0 ? who.join(", ") : "운영자 없음"}</span>}
                        {held && <span className={styles.held}>보유</span>}
                      </span>
                    </th>
                  );
                })}
                <th scope="col" className={styles.ticketHead}>
                  승인 티켓
                </th>
              </tr>
            </thead>
            {groups.map(([group, rows]) => (
              <tbody key={group}>
                {rows.map((p, i) => (
                  <tr key={p.action}>
                    {i === 0 && (
                      <th scope="rowgroup" rowSpan={rows.length} className={styles.group}>
                        {group}
                      </th>
                    )}
                    <th scope="row" className={styles.action}>
                      {p.action}
                      {p.note && <span className={styles.actionNote}>{p.note}</span>}
                    </th>
                    {ROLE_ORDER.map((r) => {
                      const ok = roleAllows(r, p.role);
                      return (
                        <td key={r} data-role="" data-held={mine.includes(r) ? "" : undefined}>
                          {ok && <StatusShape kind="circle" size={8} className={styles.allow} />}
                          <span className="sr-only">
                            {ROLE_LABEL[r]} {ok ? "가능" : "불가"}
                          </span>
                        </td>
                      );
                    })}
                    <td className={styles.ticket}>
                      {p.ticket ? (
                        <code>{p.ticket}</code>
                      ) : (
                        <>
                          <span className={styles.none} aria-hidden="true">
                            -
                          </span>
                          <span className="sr-only">티켓 없음</span>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            ))}
          </table>
        </div>
        {/* 역할의 뜻과 읽는 법. 넓은 판에서는 행렬 오른쪽 칸이고(조작 글과 점 사이를 줄인다), 좁으면 행렬 아래다 */}
        <aside className={styles.permNotes} aria-label="역할의 뜻과 읽는 법">
          <dl className={styles.roleList}>
            {ROLE_ORDER.map((r) => (
              <div key={r} className={styles.roleItem}>
                <dt>{ROLE_LABEL[r]}</dt>
                <dd>{ROLE_DESC[r]}</dd>
              </div>
            ))}
          </dl>
          <p className={styles.reason}>
            점은 그 역할 하나로 할 수 있다는 뜻입니다. 총괄은 모든 조작을 하고 실험 운영은 봇 운영의 조작을 함께 합니다. 승인 티켓은 역할과
            따로 1인 승인을 거칩니다. 계약 초안은 서버 경로가 3라운드 계약 초안인 조작입니다.
          </p>
        </aside>
      </div>
    </section>
  );
}

export default function SettingsPage() {
  const { data, loading, refresh } = useOpsCenter();
  const loadDesk = useCallback(() => getAdminApi().getReports(), []);
  const desk = useAdminResource(loadDesk, { cadence: "idle", isSessionError });
  const loadOperators = useCallback(() => getAdminApi().getOperators(), []);
  const operators = useAdminResource(loadOperators, { isSessionError });
  const session = useAdminSession();
  const canSuper = useCan("SUPER");
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const http = isAdminHttp();

  const rows = integrationsOf(data, desk.data, { grafana: grafanaConfigured(), databasus: DATABASUS });
  const count = (s: IntegrationRow["state"]) => rows.filter((r) => r.state === s).length;
  const ai = data?.ai.state === "READY" ? data.ai.data : null;
  const tests = desk.data?.state === "READY" ? desk.data.data.tests : [];
  const sim = data?.origin === "SIM";

  const sendTest = async () => {
    setBusy(true);
    try {
      await getAdminApi().sendTeamsTest();
      toast("Teams 연결 시험 전송 · 모의", "info");
      setConfirm(false);
      desk.reload();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "연결 시험 실패", "error");
    } finally {
      setBusy(false);
    }
  };

  const testBlocked = http ? "서버 계약(연결 시험 경로)이 생기기 전이라 보내지 않습니다" : !canSuper ? "총괄 권한 필요" : desk.data?.state !== "READY" ? "보고 서비스 연결 전" : null;

  return (
    <div className={adminStyles.page}>
      <OpsHead title="설정" center={data} />
      {data === null && loading ? (
        <Skeleton variant="card" width="100%" height={420} />
      ) : data === null ? (
        <AdminErrorState onRetry={() => void refresh(true)} />
      ) : (
        <div className={adminStyles.pageStack}>
          <section className={controlStyles.panel} aria-labelledby="set-integrations">
            <PanelHead
              id="set-integrations"
              title="연동"
              meta={
                <span className="num">
                  연결됨 {count("CONNECTED")} · 연결 전 {count("NOT_CONNECTED")} · 오류 {count("ERROR")}
                  {count("ASSUMED") > 0 && ` · 시뮬레이션 가정 ${count("ASSUMED")}`}
                </span>
              }
            />
            <IntegrationTable rows={rows} />
          </section>

          <div className={styles.grid}>
            <ContactsPanel oncall={data.oncall} sim={sim} />

            <div className={styles.stack}>
              <section className={controlStyles.panel} aria-labelledby="set-teams">
                <PanelHead id="set-teams" title="Teams 연결 시험" meta="보고 서비스 경유" />
                <p className={styles.prose}>운영 자료를 싣지 않는 모의 메시지 한 건을 보고 서비스의 Teams 웹훅으로 보냅니다. 전송됨은 웹훅이 받았다는 뜻이고 채널 게시를 보장하지 않습니다.</p>
                <div className={styles.row}>
                  <span className={styles.reason}>{testBlocked ?? (http ? "" : "sim은 밖으로 보내지 않고 기록만 남깁니다")}</span>
                  <button type="button" className={adminStyles.neutralBtn} disabled={testBlocked !== null || busy} onClick={() => setConfirm(true)}>
                    테스트 전송
                  </button>
                </div>
                {tests.length > 0 && (
                  <ol className={styles.tests} aria-label="연결 시험 기록">
                    {tests.slice(0, 4).map((t) => (
                      <li key={t.at} className={styles.test}>
                        <span className={styles.testWord}>
                          <StatusShape kind={t.status === "sent" ? "circle" : "square"} tone={t.status === "sent" ? "success" : "danger"} className={styles.shape} />
                          {t.status === "sent" ? "전송됨" : "실패"}
                          {t.error && ` · ${t.error}`}
                        </span>
                        <time className="num" dateTime={t.at}>
                          {kst(t.at)}
                        </time>
                      </li>
                    ))}
                  </ol>
                )}
              </section>

              <section className={controlStyles.panel} aria-labelledby="set-ai">
                <PanelHead
                  id="set-ai"
                  title="AI 한도·모드"
                  meta={ai ? (ai.mode === "AUTO" ? "자동 모드" : ai.mode === "STOPPED" ? "▲ AI 정지" : "수동 모드") : "연결 전"}
                />
                {ai ? (
                  <dl className={styles.limits}>
                    {AI_LIMIT_DEFS.map((d) => {
                      const l = ai.limits.find((x) => x.key === d.key);
                      return (
                        <div key={d.key} style={{ display: "contents" }}>
                          <dt>{d.label}</dt>
                          <dd className="num">
                            {l ? l.value : d.initial}
                            {d.unit}
                            {l?.pending != null && ` → ${l.pending}${d.unit}`}
                          </dd>
                        </div>
                      );
                    })}
                  </dl>
                ) : (
                  <NotConnected what="AI 한도" source={sectionSource(data.ai)} compact />
                )}
                <p className={styles.reason}>여기서는 읽기만 합니다. 한도와 모드는 승인 절차가 있는 AI 판단 화면과 관제센터 모드 줄에서 바꿉니다.</p>
                <div className={styles.links}>
                  <Link href="/admin/ai#ai-limits" className={controlStyles.stripLink}>
                    AI 한도 바꾸기
                  </Link>
                  <Link href="/admin/ai#ai-audit" className={controlStyles.stripLink}>
                    모드·한도 변경 기록
                  </Link>
                  <Link href="/admin" className={controlStyles.stripLink}>
                    관제센터 모드 줄
                  </Link>
                </div>
              </section>
            </div>
          </div>

          <EscalationPanel oncall={data.oncall} />
          <ChannelPanel rows={channelsOf(rows, sim)} />
          <PermissionPanel operators={operators.data} failed={operators.error} me={session?.operatorId ?? null} />
        </div>
      )}
      {confirm && (
        <ConfirmDialog
          title="Teams 연결 시험"
          description="운영 자료를 싣지 않는 모의 메시지 한 건을 보냅니다. 받는 채널의 사람들이 알림을 받을 수 있습니다."
          target="보고 서비스 Teams 웹훅"
          blastRadius="채널 메시지 1건 · 감사 로그에 남음"
          tone="warning"
          confirmLabel="보내기"
          busy={busy}
          onConfirm={() => void sendTest()}
          onClose={() => {
            if (!busy) setConfirm(false);
          }}
        />
      )}
    </div>
  );
}
