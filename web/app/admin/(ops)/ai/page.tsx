"use client";

/**
 * AI 판단(/admin/ai, 관리자 설계 4-10-2). AI가 무엇을 왜 판단했고, 어디까지 할 수 있으며, 얼마나 믿을 만한가에 답한다.
 * 규칙은 ADR 0035다. 왼쪽 칸은 무엇을 왜 판단했고 얼마나 믿을 만한가다. AI 관제 전체판(판단 한 줄, 권장 조치, 근거,
 * 처리된 조치, 판단 기록), 모드·정지 이력, ai-1 상태, 모델 평가 순서다. 오른쪽 칸은 어디까지 할 수 있는가다. 자동 조치
 * 수와 쿨다운(표 하나), AI 한도, 허용·금지 목록이다(레이아웃 §7 3라운드).
 * 감사 기록 칸은 조치 기록 화면으로 옮겼고 이 화면은 모드·정지 이력만 감사에서 읽는다. 판단 기록 행은 AI 판단 상세를 연다.
 */

import { useCallback } from "react";
import { AdminError, getAdminApi, isAdminHttp } from "@/lib/admin/api";
import { AI_ACTION_LABEL, AI_FORBIDDEN, allowedRules, autoPreconditions, cooldownSec, limitsOf } from "@/lib/admin/aiPolicy";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import type { AiActionKind } from "@/lib/admin/types";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import AiLimitsPanel from "@/components/admin/control/AiLimits";
import AiPanel from "@/components/admin/control/AiPanel";
import ModeBar from "@/components/admin/control/ModeBar";
import OpsHead from "@/components/admin/control/OpsHead";
import PageSkeleton from "@/components/admin/control/PageSkeleton";
import { hms, mmss, PanelHead, useClock } from "@/components/admin/control/parts";
import AiServerPanel from "@/components/admin/ai/AiServerPanel";
import ModeHistory from "@/components/admin/ai/ModeHistory";
import ModelEvalPanel from "@/components/admin/ai/ModelEvalPanel";
import { StatStrip, StatTile } from "@/components/admin/ui";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";
import KeepNames from "@/components/admin/KeepNames";
import StatusShape from "@/components/admin/StatusShape";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";
const KINDS: AiActionKind[] = ["READ_NODES", "THRESHOLD", "SILENCE", "BOT_LOAD", "BACKUP_NOW"];

export default function AiPage() {
  const { data, loading, refresh } = useOpsCenter();
  const now = useClock();
  // 모드·정지 이력은 감사 기록의 ops.ai.* 행이다. 감사 원장은 관리 요청 전체를 싣으므로 최근 한 쪽만 읽는다
  const loadAudit = useCallback(() => getAdminApi().getAudit({ limit: 200 }), []);
  const audit = useAdminResource(loadAudit, { cadence: "idle", isSessionError });
  const reloadAudit = audit.reload;
  const onChanged = useCallback(() => {
    void refresh(true);
    reloadAudit();
  }, [refresh, reloadAudit]);
  const ai = data?.ai.state === "READY" ? data.ai.data : null;
  const limits = limitsOf(ai?.limits);

  return (
    <div className={adminStyles.page}>
      <OpsHead title="AI 판단" center={data} />
      {data === null ? (
        loading ? (
          <PageSkeleton columns={[1.4, 1]} rows={[5, 4]} label="AI 판단 판을 불러오는 중입니다" />
        ) : (
          <AdminErrorState onRetry={() => void refresh()} />
        )
      ) : (
        <>
          <ModeBar section={data.ai} onChanged={onChanged} />
          <div className={styles.split} data-ratio="wide">
            <div className={styles.stack}>
              <div className={styles.aiTall}>
                <AiPanel section={data.ai} onChanged={onChanged} full />
              </div>
              <ModeHistory audit={audit} live={isAdminHttp()} />
              <AiServerPanel section={data.aiServer} />
              <ModelEvalPanel />
            </div>
            <div className={styles.stack}>
              <section className={styles.panel} aria-labelledby="ai-usage">
                <PanelHead id="ai-usage" title="자동 조치 수와 쿨다운" meta="자동 모드에서 실행 전에 모두 확인합니다" />
                {ai === null ? (
                  <p className={styles.lead}>AI 판단 서비스와 서버 정책 엔진을 연결하기 전이라 한도를 읽지 못합니다</p>
                ) : (
                  <>
                    <StatStrip label="자동 조치 현황" count={3}>
                      <StatTile label="이번 자동 모드" value={`${ai.autoUsed}/${ai.autoCap}`} unit="회" sub="자동을 켤 때마다 0부터 셉니다" />
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
                          <span aria-hidden="true">
                            <StatusShape kind="circle" />
                          </span>
                          <span>
                            <b>
                              <KeepNames text={AI_ACTION_LABEL[r.kind]} />
                            </b>{" "}
                            · <KeepNames text={r.rule} />
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
                          <span aria-hidden="true">
                            <StatusShape kind="square" />
                          </span>
                          <span>
                            <KeepNames text={r} />
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
                <span className={styles.rulesTitle}>자동 실행 조건</span>
                <ul className={styles.rulesList}>
                  {autoPreconditions(limits).map((r) => (
                    <li key={r}>
                      <span aria-hidden="true">
                        <StatusShape kind="triangle" />
                      </span>
                      <span>
                        <KeepNames text={r} />
                      </span>
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
