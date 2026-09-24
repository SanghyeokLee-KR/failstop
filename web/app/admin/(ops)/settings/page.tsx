"use client";

/**
 * 설정(/admin/settings, 관리자 설계 4-8). 연동 상태와 연락 체계를 한곳에서 본다. 값을 바꾸는 설정(임계값,
 * AI 한도, AI 모드)은 여기서 바꾸지 않고 승인 절차가 있는 그 화면으로 보낸다.
 *
 * 연동 상태는 이미 받는 관제 판과 보고 서비스의 전송 기록에서 가린다(integrations.ts). 밖으로 메시지를
 * 보내는 'Teams 테스트 전송'은 sim에서만 모의로 동작하고, http는 서버 계약이 생기기 전까지 비활성이다.
 */

import { useCallback, useState } from "react";
import Link from "next/link";
import { AdminError, getAdminApi, isAdminHttp } from "@/lib/admin/api";
import { AI_LIMIT_DEFS } from "@/lib/admin/aiPolicy";
import { integrationsOf, STATE_WORD, type IntegrationRow } from "@/lib/admin/integrations";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import { useCan } from "@/lib/admin/store";
import { kst } from "@/lib/admin/timeText";
import Skeleton from "@/components/Skeleton";
import { toast } from "@/components/Toast";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import GrafanaLink, { grafanaConfigured } from "@/components/admin/GrafanaLink";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import OpsHead from "@/components/admin/control/OpsHead";
import { DatabasusLink } from "@/components/admin/control/Jobs";
import { SimMark } from "@/components/admin/ui";
import { Ago, NotConnected, PanelHead } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import controlStyles from "@/components/admin/control/control.module.css";
import styles from "@/components/admin/settings/settings.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";
const DATABASUS = (process.env.NEXT_PUBLIC_DATABASUS_BASE ?? "").length > 0;

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
        {rows.map((r) => {
          const w = STATE_WORD[r.state];
          return (
            <tr key={r.key}>
              <td>
                <span className={styles.name}>
                  <b>{r.name}</b>
                  <span>{r.role}</span>
                </span>
              </td>
              <td>
                {r.state === "ASSUMED" ? (
                  <SimMark kind="assumed" />
                ) : (
                  <span className={styles.state} data-tone={w.tone === "success" ? undefined : w.tone}>
                    <span className={styles.shape} data-tone={w.tone} aria-hidden="true">
                      {w.shape}
                    </span>
                    {w.word}
                  </span>
                )}
              </td>
              <td className={styles.detail}>{r.detail}</td>
              <td data-num="" className={`${styles.checked} num`}>
                {r.checkedAt ? <Ago at={r.checkedAt} /> : r.key === "grafana" ? "확인하지 않음" : "없음"}
              </td>
              <td data-act="">
                <RowAction row={r} />
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

export default function SettingsPage() {
  const { data, loading, refresh } = useOpsCenter();
  const loadDesk = useCallback(() => getAdminApi().getReports(), []);
  const desk = useAdminResource(loadDesk, { cadence: "idle", isSessionError });
  const loadOnCall = useCallback(() => getAdminApi().getOnCall(), []);
  const onCall = useAdminResource(loadOnCall, { isSessionError });
  const canSuper = useCan("SUPER");
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const http = isAdminHttp();

  const rows = integrationsOf(data, desk.data, { grafana: grafanaConfigured(), databasus: DATABASUS });
  const count = (s: IntegrationRow["state"]) => rows.filter((r) => r.state === s).length;
  const ai = data?.ai.state === "READY" ? data.ai.data : null;
  const tests = desk.data?.state === "READY" ? desk.data.data.tests : [];

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
      <OpsHead title="설정" desc="연동 상태와 연락 체계입니다. 값을 바꾸는 일은 각 화면의 승인 절차에서 합니다" center={data} />
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
            <section className={controlStyles.panel} aria-labelledby="set-oncall">
              <PanelHead id="set-oncall" title="담당자·연락 체계" meta={onCall.data?.state === "READY" && !http ? "예시 명부" : undefined} />
              {onCall.loading ? (
                <Skeleton variant="card" width="100%" height={160} />
              ) : onCall.error || !onCall.data ? (
                <AdminErrorState reason={onCall.errorReason} onRetry={onCall.reload} />
              ) : onCall.data.state === "NOT_CONNECTED" ? (
                <NotConnected what="연락 체계" source={onCall.data.source} />
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
                      {onCall.data.data.map((e) => (
                        <tr key={e.role}>
                          <td>
                            <b>{e.role}</b>
                          </td>
                          <td>
                            {e.person}
                            {e.operatorId && <small className="num">{e.operatorId}</small>}
                          </td>
                          <td>{e.channel}</td>
                          <td>{e.hours}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <p className={styles.reason}>담당 역할 이름은 사건 보고의 권고 담당 역할과 같습니다. 권고 담당은 SLA나 작업 배정이 아닙니다.</p>
                </>
              )}
            </section>

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
                        <span>
                          <span className={styles.shape} data-tone={t.status === "sent" ? "success" : "danger"} aria-hidden="true">
                            {t.status === "sent" ? "●" : "■"}{" "}
                          </span>
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
                  <NotConnected what="AI 한도" source={data.ai.state === "NOT_CONNECTED" ? data.ai.source : ""} compact />
                )}
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
