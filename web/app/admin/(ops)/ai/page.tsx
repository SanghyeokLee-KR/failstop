"use client";

/**
 * AI 판단(/admin/ai, 관리자 설계 4-2·4-4). AI가 무엇을 보고 무엇을 제안했는지, 누가 승인했는지,
 * 자동 모드의 한도가 얼마나 남았는지를 한곳에 둔다. 규칙은 ADR 0035다. 왼쪽은 판단과 감사 기록, 오른쪽은
 * 자동 조치 수·쿨다운(표 하나), AI 한도, 허용·금지 목록이다(레이아웃 §7 v1.7)
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
import { StatStrip, StatTile } from "@/components/admin/ui";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";
import StatusShape from "@/components/admin/StatusShape";

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
        desc="AI가 무엇을 보고 무엇을 제안했는지, 누가 승인했는지 남깁니다. 규칙은 ADR 0035를 따릅니다"
        center={data}
      />
      {data === null ? (
        loading ? <Skeleton variant="card" width="100%" height={520} /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <>
          <ModeBar section={data.ai} onChanged={onChanged} />
          <div className={styles.split} data-ratio="wide">
            <div className={styles.stack}>
              <div className={styles.aiTall}>
                <AiPanel section={data.ai} onChanged={onChanged} full />
              </div>
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
                  /* 열 너비를 고정해 카드 폭 안에 둔다. 감사 기록은 원문을 다 읽어야 해서 자르지 않는다. 액션 코드는 기계
                     식별자라 어디서든 끊고, 대상은 낱말 단위로 다음 줄로 넘긴다(디자인 시스템 §12-12) */
                  <table className={styles.hist}>
                    <thead>
                      <tr>
                        <th scope="col" data-col="at">시각</th>
                        <th scope="col" data-col="who">누가</th>
                        <th scope="col">액션</th>
                        <th scope="col">대상</th>
                        <th scope="col" data-col="ok">승인</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((e) => (
                        <tr key={e.id}>
                          <td className="num">{hms(e.at)}</td>
                          <td title={e.operatorName}>
                            <b>{e.operatorName}</b>
                          </td>
                          <td data-code="">{e.action}</td>
                          <td data-wrap="">{e.target}</td>
                          <td>{e.approvalId ? "티켓" : "없음"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </section>
            </div>
            <div className={styles.stack}>
              <section className={styles.panel} aria-labelledby="ai-usage">
                <PanelHead id="ai-usage" title="자동 조치 수와 쿨다운" meta="자동 모드에서 실행 전에 모두 확인합니다" />
                {ai === null ? (
                  <p className={styles.lead}>AI 판단 서비스와 서버 정책 엔진을 연결하기 전이라 한도를 읽지 못합니다</p>
                ) : (
                  <>
                    <StatStrip label="자동 조치 현황" count={3}>
                      <StatTile
                        label="이번 자동 모드"
                        value={`${ai.autoUsed}/${ai.autoCap}`}
                        unit="회"
                        sub="자동을 켤 때마다 0부터 셉니다"
                      />
                      <StatTile
                        label="관찰 창"
                        value={ai.observation ? mmss(now - Date.parse(ai.observation.startedAt)) : "없음"}
                        sub={ai.observation ? `05:00 중 · ${ai.observation.action}` : "자동 조치 뒤 5분 동안 봅니다"}
                      />
                      <StatTile
                        label="자동 모드"
                        value={ai.auto ? `${hms(ai.auto.until)}까지` : ai.stopped ? "AI 정지" : "꺼짐"}
                        tone={ai.stopped ? "warning" : undefined}
                        sub={ai.auto ? `승인 ${ai.auto.by} · ${ai.auto.reason}` : ai.stopped ? `${ai.stopped.by} ${hms(ai.stopped.at)}부터` : "수동 모드입니다"}
                      />
                    </StatStrip>
                    {/* 조치 다섯 종의 쿨다운은 같은 모양의 카드 다섯 장이 아니라 표 한 벌이다(§12-14) */}
                    <table className={styles.cooldowns}>
                      <thead>
                        <tr>
                          <th scope="col">조치</th>
                          <th scope="col">쿨다운 규칙</th>
                          <th scope="col" data-num="">남은 시간</th>
                        </tr>
                      </thead>
                      <tbody>
                        {KINDS.map((k) => {
                          const cd = ai.cooldowns.find((c) => c.kind === k);
                          const left = cd ? Date.parse(cd.until) - now : 0;
                          const rule = cooldownSec(k, limits);
                          return (
                            <tr key={k} data-active={left > 0 ? "" : undefined}>
                              <th scope="row">{AI_ACTION_LABEL[k]}</th>
                              <td>{rule > 0 ? `실행 뒤 ${rule / 60}분` : "규칙 별도 · 보류 30분 상한, 백업 하루 1회"}</td>
                              <td data-num="" className="num">
                                {left > 0 ? <b>{mmss(left)}</b> : "없음"}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
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
                          <span aria-hidden="true"><StatusShape kind="circle" /></span>
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
                          <span aria-hidden="true"><StatusShape kind="square" /></span>
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
                      <span aria-hidden="true"><StatusShape kind="triangle" /></span>
                      <span>{r}</span>
                    </li>
                  ))}
                </ul>
              </section>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
