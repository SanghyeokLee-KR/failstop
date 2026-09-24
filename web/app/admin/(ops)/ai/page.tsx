"use client";

/**
 * AI 판단(/admin/ai, 관리자 설계 4-2·4-4). AI가 무엇을 보고 무엇을 제안했는지, 누가 승인했는지,
 * 자동 모드의 한도가 얼마나 남았는지를 한곳에 둔다. 규칙은 ADR 0035다.
 */

import { useCallback } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { AI_ACTION_LABEL, AI_FORBIDDEN, allowedRules, autoPreconditions, cooldownSec, limitsOf } from "@/lib/admin/aiPolicy";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import type { AiActionKind, AuditEntry } from "@/lib/admin/types";
import Skeleton from "@/components/Skeleton";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import AiLimitsPanel from "@/components/admin/control/AiLimits";
import AiPanel from "@/components/admin/control/AiPanel";
import ModeBar from "@/components/admin/control/ModeBar";
import OpsHead from "@/components/admin/control/OpsHead";
import { hms, mmss, PanelHead, useClock } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";
const KINDS: AiActionKind[] = ["READ_NODES", "THRESHOLD", "SILENCE", "BOT_LOAD", "BACKUP_NOW"];

/** AI와 관제 조작만 거른다. 감사 원장은 관리 요청 전체를 싣는다 */
function isOpsAudit(e: AuditEntry): boolean {
  return e.action.startsWith("ai.") || e.action.startsWith("ops.") || e.action.includes(":ops.");
}

export default function AiPage() {
  const { data, loading, refresh } = useOpsCenter();
  const now = useClock();
  const loadAudit = useCallback(() => getAdminApi().getAudit({ limit: 200 }), []);
  const audit = useAdminResource(loadAudit, { cadence: "idle", isSessionError });
  const reloadAudit = audit.reload;
  const onChanged = useCallback(() => {
    void refresh(true);
    reloadAudit();
  }, [refresh, reloadAudit]);
  const ai = data?.ai.state === "READY" ? data.ai.data : null;
  const limits = limitsOf(ai?.limits);
  const rows = (audit.data?.data ?? []).filter(isOpsAudit).slice(0, 14);

  return (
    <div className={adminStyles.page}>
      <OpsHead
        title="AI 판단"
        desc="AI가 무엇을 보고 무엇을 제안했는지, 누가 승인했는지 남깁니다. 규칙은 ADR 0035"
        center={data}
      />
      {data === null ? (
        loading ? <Skeleton variant="card" width="100%" height={520} /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <>
          <ModeBar section={data.ai} onChanged={onChanged} />
          <div className={styles.split} data-ratio="wide">
            <div className={styles.aiTall}>
              <AiPanel section={data.ai} sim={data.origin === "SIM"} onChanged={onChanged} full />
            </div>
            <div className={styles.stack}>
              <section className={styles.panel} aria-labelledby="ai-usage">
                <PanelHead id="ai-usage" title="자동 조치 수와 쿨다운" meta="자동 모드에서 실행 전에 모두 확인합니다" />
                {ai === null ? (
                  <p className={styles.lead}>AI 판단 서비스와 서버 정책 엔진 연결 전. 한도 판독 없음</p>
                ) : (
                  <>
                    <dl className={styles.kv}>
                      <dt>이번 자동 모드</dt>
                      <dd>
                        <b className="num">
                          {ai.autoUsed}/{ai.autoCap}
                        </b>{" "}
                        · 자동을 새로 켜면 0부터 셉니다. 닿으면 수동 전환과 Teams 알림
                      </dd>
                      <dt>관찰 창</dt>
                      <dd>
                        {ai.observation ? (
                          <>
                            <b className="num">{mmss(now - Date.parse(ai.observation.startedAt))}</b> / 05:00 · {ai.observation.action}
                          </>
                        ) : (
                          "없음"
                        )}
                      </dd>
                      <dt>자동 모드</dt>
                      <dd>
                        {ai.auto ? (
                          <>
                            <b className="num">{hms(ai.auto.until)}</b>까지 · 승인 {ai.auto.by} · 사유 {ai.auto.reason}
                          </>
                        ) : ai.stopped ? (
                          <>
                            <span aria-hidden="true">▲ </span>AI 정지 중 · {ai.stopped.by} <b className="num">{hms(ai.stopped.at)}</b>부터
                          </>
                        ) : (
                          "꺼짐(수동)"
                        )}
                      </dd>
                    </dl>
                    <div className={styles.limitGrid}>
                      {KINDS.map((k) => {
                        const cd = ai.cooldowns.find((c) => c.kind === k);
                        const left = cd ? Date.parse(cd.until) - now : 0;
                        return (
                          <div key={k} className={styles.limit} data-active={left > 0 ? "" : undefined}>
                            <span>{AI_ACTION_LABEL[k]}</span>
                            <b className="num">{left > 0 ? mmss(left) : "쿨다운 없음"}</b>
                            <span>{cooldownSec(k, limits) > 0 ? `쿨다운 ${cooldownSec(k, limits) / 60}분` : "쿨다운 규칙 별도"}</span>
                          </div>
                        );
                      })}
                    </div>
                    <p className={styles.reviewText}>쿨다운과 관찰 창은 시스템 안정을 위한 것이라 모드를 바꾸거나 자동을 다시 켜도 초기화하지 않습니다.</p>
                  </>
                )}
              </section>
              <AiLimitsPanel ai={ai} onChanged={onChanged} />
              <section className={styles.panel} aria-labelledby="ai-rules">
                <PanelHead id="ai-rules" title="허용·금지 목록" meta="어느 모드에서도 금지 목록은 조치 카드가 되지 않습니다" />
                <div className={styles.autoRules} style={{ marginTop: 0 }}>
                  <div>
                    <span className={styles.rulesTitle}>허용 목록</span>
                    <ul className={styles.rulesList}>
                      {allowedRules(limits).map((r) => (
                        <li key={r.kind}>
                          <span aria-hidden="true">●</span>
                          <span>
                            <b>{AI_ACTION_LABEL[r.kind]}</b> · {r.rule}
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                  <div>
                    <span className={styles.rulesTitle}>금지 목록</span>
                    <ul className={styles.rulesList}>
                      {AI_FORBIDDEN.map((r) => (
                        <li key={r}>
                          <span aria-hidden="true">■</span>
                          <span>{r}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
                <span className={styles.rulesTitle}>자동 실행 조건</span>
                <ul className={styles.rulesList}>
                  {autoPreconditions(limits).map((r) => (
                    <li key={r}>
                      <span aria-hidden="true">▲</span>
                      <span>{r}</span>
                    </li>
                  ))}
                </ul>
              </section>
              <section className={styles.panel} aria-labelledby="ai-audit">
                <PanelHead id="ai-audit" title="감사 기록" meta="AI 운영 계정과 운영자의 관제 조작" />
                {audit.error ? (
                  <AdminErrorState reason={audit.errorReason} onRetry={audit.reload} />
                ) : rows.length === 0 ? (
                  <div className={styles.empty}>
                    <span className={styles.emptyMark} aria-hidden="true" />
                    관제 조작 기록 없음
                  </div>
                ) : (
                  <table className={styles.hist}>
                    <thead>
                      <tr>
                        <th>시각</th>
                        <th>누가</th>
                        <th>액션</th>
                        <th>대상</th>
                        <th>승인</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((e) => (
                        <tr key={e.id}>
                          <td className="num">{hms(e.at)}</td>
                          <td>
                            <b>{e.operatorName}</b>
                          </td>
                          <td>{e.action}</td>
                          <td className={styles.cellClip} title={e.target}>
                            {e.target}
                          </td>
                          <td>{e.approvalId ? "티켓" : "없음"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </section>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
