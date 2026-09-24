"use client";

/**
 * AI 관제 패널(§12-9). 관제센터의 주인공이다.
 *
 * 지금 상황, 근거, 원인 가설과 확신도, 권장 조치, 판단 이력 순서로 읽힌다. 조치 카드는 허용 목록
 * 다섯 종에서만 나오고 금지 목록 조치는 어휘에 없어 카드가 될 수 없다(ADR 0035). 승인 버튼은
 * 운영자의 결정이라 AI 색이 아니라 브랜드 approve다. http 원천에서는 판단을 지어내지 않고
 * 'AI 연결 전'만 그린다.
 */

import { useState } from "react";
import Link from "next/link";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { AI_INITIAL } from "@/lib/admin/aiPolicy";
import { useOpsFocus } from "@/lib/admin/opsCenter";
import { useCan } from "@/lib/admin/store";
import type { AiDecision, AiDesk, AiProposal, ControlCenter } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import ConfirmDialog from "../ConfirmDialog";
import adminStyles from "../admin.module.css";
import { RulesDisclosure } from "./ModeBar";
import { ago, AiMark, hms, mmss, SEVERITY_SHAPE } from "./parts";
import styles from "./control.module.css";

const RISK_WORD = { LOW: "낮음", MEDIUM: "중간" } as const;

function Decided({ p, now }: { p: AiProposal; now: number }) {
  const who = p.decidedBy ?? "";
  const when = p.decidedAt ? hms(p.decidedAt) : "";
  switch (p.status) {
    case "APPROVED":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true">●</span> 승인 · {who} {when} · 실행됨
        </span>
      );
    case "REJECTED":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true">○</span> 거절 · {who} {when}
        </span>
      );
    case "DEFERRED":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true">◌</span> 나중에 · {who} · 5분 동안 다시 내지 않음
        </span>
      );
    case "AUTO_EXECUTED": {
      const left = p.revertibleUntil ? Date.parse(p.revertibleUntil) - now : 0;
      return (
        <span className={styles.decided} data-tone="ai">
          <AiMark size={12} /> 자동 실행됨 · {when}
          {left > 0 && (
            <>
              {" "}
              · 되돌리기 <b className="num">{mmss(left)}</b>
            </>
          )}
        </span>
      );
    }
    case "AUTO_REVERTED":
      return (
        <span className={styles.decided} data-tone="warning">
          <span aria-hidden="true">▲</span> 자동 되돌림 · 수동 모드로 전환 · {when}
        </span>
      );
    case "REVERTED":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true">○</span> 되돌림 · {who} {when}
        </span>
      );
    case "WITHDRAWN":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true">○</span> 상황이 바뀌어 거둠 · {when}
        </span>
      );
    case "PROPOSED":
      return null;
  }
}

function ProposalCard({ p, index, mode, now, onChanged }: {
  p: AiProposal;
  index: number;
  mode: AiDesk["mode"];
  now: number;
  onChanged: () => void;
}) {
  const canSuper = useCan("SUPER");
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const decide = async (decision: AiDecision) => {
    setBusy(true);
    try {
      await getAdminApi().decideAiProposal(p.id, decision);
      toast(decision === "APPROVE" ? "승인 · 조치 실행" : decision === "REJECT" ? "조치안 거절" : "나중에 다시 판단", "info");
      setConfirm(false);
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "처리 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  const revert = async () => {
    setBusy(true);
    try {
      await getAdminApi().revertAiAction(p.id);
      toast("자동 조치 되돌림", "info");
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "되돌리기 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  const revertible = p.status === "AUTO_EXECUTED" && p.revertibleUntil !== null && Date.parse(p.revertibleUntil) > now && p.kind !== "BACKUP_NOW";
  return (
    <article className={styles.card} data-status={p.status} aria-label={`조치안 ${index}. ${p.title}`}>
      <div className={styles.cardTop}>
        <span className={`${styles.cardNo} num`} aria-hidden="true">
          {index}
        </span>
        <span className={styles.cardTitle}>{p.title}</span>
        <span className={styles.risk}>
          <span aria-hidden="true" className={styles.shape} data-tone={p.risk === "LOW" ? "success" : "warning"}>
            {p.risk === "LOW" ? "●" : "▲"}
          </span>
          위험 {RISK_WORD[p.risk]}
        </span>
      </div>
      <dl className={styles.cardRows}>
        <dt>대상</dt>
        <dd>
          {p.target} · 확신도 <b className="num">{p.confidencePct}%</b>
        </dd>
        <dt>예상 영향</dt>
        <dd>{p.impact}</dd>
        <dt>되돌리기</dt>
        <dd>{p.revertPlan}</dd>
      </dl>
      <div className={styles.cardFoot}>
        {p.status === "PROPOSED" ? (
          <>
            <button
              type="button"
              className={adminStyles.approveBtn}
              disabled={!canSuper || busy}
              title={canSuper ? undefined : "총괄 권한 필요"}
              onClick={() => setConfirm(true)}
            >
              승인하고 실행
            </button>
            <button type="button" className={adminStyles.neutralBtn} disabled={busy} onClick={() => void decide("REJECT")}>
              거절
            </button>
            <button type="button" className={adminStyles.neutralBtn} disabled={busy} onClick={() => void decide("DEFER")}>
              나중에
            </button>
            {mode === "AUTO" && p.autoHold && <span className={styles.hold}>자동 대기 · {p.autoHold}</span>}
          </>
        ) : (
          <>
            <Decided p={p} now={now} />
            {revertible && (
              <>
                <span className={styles.spacer} />
                <button type="button" className={adminStyles.neutralBtn} disabled={!canSuper || busy} onClick={() => void revert()}>
                  되돌리기
                </button>
              </>
            )}
          </>
        )}
      </div>
      {confirm && (
        <ConfirmDialog
          title="AI 조치안 승인"
          description={`승인하면 곧바로 실행한다. 되돌리기 · ${p.revertPlan}`}
          target={p.title}
          blastRadius={p.impact}
          tone="warning"
          confirmLabel="승인하고 실행"
          busy={busy}
          onConfirm={() => void decide("APPROVE")}
          onClose={() => {
            if (!busy) setConfirm(false);
          }}
        >
          {p.kind === "THRESHOLD" && (
            <p className={styles.reviewText}>
              임계값 변경이라 1인 승인 티켓(ops.threshold.set)을 발급하고 곧바로 소각해 승인 원장과 감사 로그에 남긴다.
            </p>
          )}
        </ConfirmDialog>
      )}
    </article>
  );
}

function Confidence({ pct }: { pct: number }) {
  const word = pct >= 85 ? "높음" : pct >= 70 ? "보통" : "낮음";
  return (
    <div className={styles.confidence}>
      <span className={styles.confidenceHead}>
        <span>확신도 · {word}</span>
        <b className="num">{pct}%</b>
      </span>
      <span className={styles.confTrack} aria-hidden="true">
        <span className={styles.confFill} style={{ width: `${pct}%` }} />
        <span className={styles.confMark} style={{ left: `${AI_INITIAL.confidencePct}%` }} />
      </span>
      <span className={styles.confNote}>눈금 {AI_INITIAL.confidencePct}%는 자동 실행 기준</span>
    </div>
  );
}

function AiOff({ source }: { source: string }) {
  return (
    <div className={styles.aiOff}>
      <span className={styles.aiOffMark}>
        <AiMark size={28} />
      </span>
      <span className={styles.aiOffTitle}>
        <span aria-hidden="true">◌ </span>AI 연결 전
      </span>
      <span className={styles.aiOffDesc}>
        AI 판단 서비스와 서버 정책 엔진을 붙이는 2단계 전이다. 판단과 조치안을 지어내지 않는다. 경보와 ASG 정책은 AI 없이 그대로 동작한다.
      </span>
      <span className={styles.notConnectedSource}>{source}</span>
    </div>
  );
}

export default function AiPanel({ center, now, onChanged, full = false }: {
  center: ControlCenter | null;
  now: number;
  onChanged: () => void;
  /** AI 판단 화면은 결정된 조치안과 판단 이력을 모두 싣는다 */
  full?: boolean;
}) {
  const point = useOpsFocus((s) => s.point);
  const section = center?.ai ?? null;
  const sim = center?.origin === "SIM";
  const ai = section?.state === "READY" ? section.data : null;
  const open = ai ? ai.proposals.filter((p) => p.status === "PROPOSED") : [];
  const others = ai ? ai.proposals.filter((p) => p.status !== "PROPOSED") : [];
  const shown = full ? [...open, ...others] : [...open, ...others.slice(0, Math.max(1, 3 - open.length))];
  const history = ai ? (full ? ai.history : ai.history.slice(0, 4)) : [];

  return (
    <section className={styles.ai} aria-labelledby="ai-title" aria-busy={ai?.thinking || undefined}>
      <header className={styles.aiHead}>
        <h2 id="ai-title" className={styles.aiTitle}>
          <AiMark size={18} />
          AI 관제
        </h2>
        {sim && <span className={styles.simTag}>시뮬레이션</span>}
        {ai && <span className={styles.modeTag} data-mode={ai.mode}>{ai.mode === "AUTO" ? "자동 모드" : "수동 모드"}</span>}
        <span className={styles.aiStatus}>
          {ai === null ? (
            section === null ? "불러오는 중" : "연결 전"
          ) : ai.thinking ? (
            <>
              <span className={styles.dots} aria-hidden="true">
                <i />
                <i />
                <i />
              </span>
              <b>판단 중</b> · 근거 {ai.evidence.length}건 분석
            </>
          ) : (
            <>
              판단 <b className="num">{ai.judgedAt ? hms(ai.judgedAt) : "없음"}</b>
            </>
          )}
        </span>
      </header>
      <div className={styles.scan} data-on={ai?.thinking ? "" : undefined} aria-hidden="true" />
      {section !== null && section.state === "NOT_CONNECTED" ? (
        <AiOff source={section.source} />
      ) : ai === null ? (
        <div className={styles.aiOff}>
          <span className={styles.aiOffTitle}>판단 불러오는 중</span>
        </div>
      ) : (
        <>
          <div className={styles.aiBody}>
            <div>
              <span className={styles.aiKicker}>지금 상황</span>
              <p className={styles.situation}>{ai.situation}</p>
            </div>
            {ai.evidence.length > 0 && (
              <div className={styles.evidence}>
                <span className={styles.evidenceKey}>근거</span>
                <div className={styles.evidenceChips}>
                {ai.evidence.map((e) => (
                  <button
                    key={e.id}
                    type="button"
                    className={styles.chip}
                    onClick={() => {
                      if (e.ref) point(e.ref);
                    }}
                    aria-label={`근거 ${e.label} ${e.value}. 누르면 해당 자리 강조`}
                  >
                    {e.severity && <span aria-hidden="true">{SEVERITY_SHAPE[e.severity]}</span>}
                    {e.label} <b className="num">{e.value}</b>
                  </button>
                ))}
                </div>
              </div>
            )}
            {ai.hypothesis && (
              <div className={styles.hypo}>
                <div>
                  <span className={styles.aiKicker}>원인 가설</span>
                  <p className={styles.hypoText}>{ai.hypothesis}</p>
                </div>
                {ai.confidencePct !== null && <Confidence pct={ai.confidencePct} />}
              </div>
            )}
            <div>
              <div className={styles.subHead}>
                권장 조치 <span className={`${styles.count} num`}>{open.length}</span>건
                {full ? null : (
                  <Link href="/admin/ai" className={styles.stripLink}>
                    판단 기록 전체
                  </Link>
                )}
              </div>
              <div className={styles.proposals} style={{ marginTop: "var(--space-2)" }}>
                {shown.length === 0 ? (
                  <div className={styles.empty}>
                    <span className={styles.emptyMark} aria-hidden="true" />
                    조치안 없음 · 허용 목록에 맞는 조치가 필요할 때만 낸다
                  </div>
                ) : (
                  shown.map((p, i) => <ProposalCard key={p.id} p={p} index={i + 1} mode={ai.mode} now={now} onChanged={onChanged} />)
                )}
              </div>
            </div>
            <div>
              <div className={styles.subHead}>판단 이력</div>
              <ol className={styles.historyList} style={{ marginTop: "var(--space-1)" }}>
                {history.map((h) => (
                  <li key={h.id} className={styles.historyRow}>
                    <span className={`${styles.historyTime} num`}>{hms(h.at)}</span>
                    <span className={styles.historyOutcome}>{h.outcome}</span>
                    <span className={styles.historyText} title={h.summary}>
                      {h.summary}
                    </span>
                  </li>
                ))}
              </ol>
            </div>
          </div>
          <footer className={styles.aiFoot}>
            <div className={styles.aiFootRow}>
              <span>
                모델 <b>{ai.model}</b>
              </span>
              <span>
                판단 <b className="num">{ai.judgedAt ? hms(ai.judgedAt) : "없음"}</b>
              </span>
              <span>
                근거 수집 <b className="num">{ai.evidenceAt ? hms(ai.evidenceAt) : "없음"}</b>
                {ai.evidenceAt && ` · ${ago(ai.evidenceAt, now)}`}
              </span>
            </div>
            <div className={styles.guard}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" aria-hidden="true">
                <path d="M8 1.75 L13.25 3.75 V8 C13.25 11 11 13.25 8 14.25 C5 13.25 2.75 11 2.75 8 V3.75 Z" />
              </svg>
              <span className={styles.guardText}>
                AI는 허용 목록 안의 조치만 제안하고, 자동 모드에서만 한도 안에서 실행한다. DB 승격, 데이터 삭제, 보안 설정, 재시작은 어느 모드에서도 하지 않는다.
              </span>
              <RulesDisclosure label="목록" up />
            </div>
          </footer>
        </>
      )}
    </section>
  );
}
