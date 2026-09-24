"use client";

/**
 * AI 관제 패널(§12-9). 관제센터의 가운데 기둥이다.
 *
 * 큰 한 줄 판단, 근거, AI 판단 요약(예상 영향, 확신도, 위험도, 되돌리기), 권장 조치, 처리된 조치, 판단
 * 이력 순서로 읽힌다. AI라는 사실은 보라 빛이 아니라 이 내용과 판단 과정으로 드러낸다(§12-10). 조치 카드는
 * 허용 목록 다섯 종에서만 나오고 금지 목록 조치는 어휘에 없어 카드가 될 수 없다(ADR 0035). 번호는 대기
 * 중인 조치안에만 붙인다. 승인 버튼은 운영자의 결정이라 AI 색이 아니라 브랜드 approve다. http 원천에서는
 * 판단을 지어내지 않고 'AI 연결 전'만 그린다.
 */

import { memo, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { actionKey, AI_ACTION_LABEL, limitsOf, type AiLimits } from "@/lib/admin/aiPolicy";
import { useOpsFocus } from "@/lib/admin/opsCenter";
import { useCan } from "@/lib/admin/store";
import type { AiActionKind, AiDecision, AiDesk, AiProposal, OpsRef, OpsSection } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import ConfirmDialog from "../ConfirmDialog";
import adminStyles from "../admin.module.css";
import { AdminIcon } from "../icons";
import { RulesDisclosure } from "./ModeBar";
import { Ago, AiMark, hms, mmss, SEVERITY_SHAPE, useClock } from "./parts";
import styles from "./control.module.css";

const RISK_WORD = { LOW: "낮음", MEDIUM: "중간" } as const;

/** 조치가 가리키는 자리. '근거 보기'가 그 자리를 잠깐 강조한다 */
const KIND_REF: Record<AiActionKind, OpsRef | null> = {
  READ_NODES: { kind: "READ_TIER" },
  THRESHOLD: { kind: "READ_TIER" },
  SILENCE: null,
  BOT_LOAD: { kind: "HOST", id: "bot-1" },
  BACKUP_NOW: { kind: "BACKUP" },
};

/** 판단 문장을 첫 문장과 나머지로 나눈다. 첫 문장이 패널에서 가장 크게 읽히는 한 줄이다 */
export function splitLead(text: string): [string, string] {
  const m = /^(.+?[.!?])\s+(.+)$/.exec(text.trim());
  return m ? [m[1], m[2]] : [text.trim(), ""];
}

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
          <span aria-hidden="true">▲</span> 자동 되돌림 · {when}
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
    case "EXPIRED":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true">○</span> AI 정지로 만료 · {who} {when}
        </span>
      );
    case "PROPOSED":
      return null;
  }
}

/**
 * 처리된 조치. 번호와 예상 영향 대신 실제로 바꾼 것(결과)을 적는다. 되돌리기 창이 열린 자동 실행 카드만
 * 남은 시간을 매초 다시 그리고, 나머지 카드는 시계를 사실상 타지 않는다(한 시간 간격)
 */
function DoneCard({ p, onChanged }: { p: AiProposal; onChanged: () => void }) {
  const now = useClock(p.status === "AUTO_EXECUTED" ? 1000 : 3_600_000);
  const canSuper = useCan("SUPER");
  const [busy, setBusy] = useState(false);
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
    <article className={styles.card} data-status={p.status} aria-label={`처리된 조치. ${p.title}`}>
      <div className={styles.cardTop}>
        <span className={styles.cardTitle}>{p.title}</span>
        <span className={styles.badge} data-kind="done">
          {p.status === "EXPIRED" ? "정지로 만료" : "처리됨"}
        </span>
      </div>
      <dl className={styles.cardRows}>
        <dt>대상</dt>
        <dd>{p.target}</dd>
        {p.outcome && (
          <>
            <dt>결과</dt>
            <dd>{p.outcome}</dd>
          </>
        )}
        {revertible && (
          <>
            <dt>되돌리기</dt>
            <dd>{p.revertPlan}</dd>
          </>
        )}
      </dl>
      <div className={styles.cardFoot}>
        {p.status === "EXPIRED" && (
          <button type="button" className={adminStyles.approveBtn} disabled title="AI 정지로 만료되었습니다. 재개한 뒤 새 판단을 기다립니다">
            승인하고 실행
          </button>
        )}
        <Decided p={p} now={now} />
        {revertible && (
          <>
            <span className={styles.spacer} />
            <button type="button" className={adminStyles.neutralBtn} disabled={!canSuper || busy} onClick={() => void revert()}>
              되돌리기
            </button>
          </>
        )}
      </div>
    </article>
  );
}

/** 조치안이 자동 모드에서 실행될 수 있는가. 확신도가 기준에 닿아야 하고, 수동 모드면 늘 승인이다 */
function autoBadge(p: AiProposal, mode: AiDesk["mode"], limits: AiLimits): { kind: "auto" | "approve"; text: string } {
  if (mode === "AUTO" && p.confidencePct >= limits.confidencePct) return { kind: "auto", text: "자동 실행 가능" };
  return { kind: "approve", text: "승인 필요" };
}

function CardMenu({ p, onEvidence }: { p: AiProposal; onEvidence: (p: AiProposal) => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setOpen(false);
        ref.current?.querySelector("button")?.focus();
      }
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", esc);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", esc);
    };
  }, [open]);
  return (
    <span className={styles.cardMenu} ref={ref}>
      <button type="button" className={styles.iconBtn} aria-haspopup="true" aria-expanded={open} aria-label={`${p.title} 메뉴`} onClick={() => setOpen((v) => !v)}>
        <AdminIcon name="more" size={16} />
      </button>
      {open && (
        <span className={styles.cardMenuPanel} role="group" aria-label="조치안 메뉴">
          <button
            type="button"
            className={styles.menuItem}
            onClick={() => {
              setOpen(false);
              onEvidence(p);
            }}
          >
            근거 보기
          </button>
          <Link href="/admin/ai" className={styles.menuItem}>
            기록 보기
          </Link>
        </span>
      )}
    </span>
  );
}

function ProposalCard({ p, index, ai, limits, onChanged, onEvidence }: {
  p: AiProposal;
  index: number;
  ai: AiDesk;
  limits: AiLimits;
  onChanged: () => void;
  onEvidence: (p: AiProposal) => void;
}) {
  const canSuper = useCan("SUPER");
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const badge = autoBadge(p, ai.mode, limits);
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
  return (
    <article className={styles.card} data-status={p.status} aria-label={`조치안 ${index}. ${p.title}`}>
      <div className={styles.cardTop}>
        <span className={`${styles.cardNo} num`} aria-hidden="true">
          {index}
        </span>
        <span className={styles.cardTitle}>{p.title}</span>
        <span className={styles.badge} data-kind={badge.kind}>
          {badge.text}
        </span>
        <CardMenu p={p} onEvidence={onEvidence} />
      </div>
      <dl className={styles.cardRows}>
        <dt>대상</dt>
        <dd>
          {p.target} · 확신도 <b className="num">{p.confidencePct}%</b> · 위험 {RISK_WORD[p.risk]}
        </dd>
        <dt>예상 영향</dt>
        <dd>{p.impact}</dd>
        <dt>되돌리기</dt>
        <dd>{p.revertPlan}</dd>
      </dl>
      <div className={styles.cardFoot}>
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
        {ai.mode === "AUTO" && p.autoHold && <span className={styles.hold}>자동 대기 · {p.autoHold}</span>}
      </div>
      {confirm && (
        <ConfirmDialog
          title="AI 조치안 승인"
          description={`승인하면 곧바로 실행합니다. 되돌리기 · ${p.revertPlan}`}
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
              임계값 변경이라 1인 승인 티켓(ops.threshold.set)을 발급하고 곧바로 소각해 승인 원장과 감사 로그에 남깁니다.
            </p>
          )}
        </ConfirmDialog>
      )}
    </article>
  );
}

/**
 * AI 판단 요약 네 칸. 맨 앞 대기 조치안의 예상 영향, 판단의 확신도, 위험도, 되돌리기다. 되돌리기 칸의
 * '과거 같은 조치'는 감사 기록에서 센 값이고 근거 없는 성공률을 적지 않는다. 조치안이 없어도 칸은 남겨
 * 배치가 흔들리지 않게 한다
 */
function Summary({ ai, top, limits }: { ai: AiDesk; top: AiProposal | null; limits: AiLimits }) {
  const [impact, impactRest] = top ? splitLead(top.impact) : ["해당 없음", "권장 조치 없음"];
  const conf = ai.confidencePct;
  const word = conf === null ? "" : conf >= 85 ? "높음" : conf >= 70 ? "보통" : "낮음";
  const stats = top ? ai.actionStats[actionKey(top.kind, top.title)] : undefined;
  return (
    <dl className={styles.summaryCells} aria-label="AI 판단 요약">
      <div className={styles.cell} data-empty={top ? undefined : ""}>
        <dt>예상 영향</dt>
        <dd className={styles.cellValue}>{impact.replace(/\.$/, "")}</dd>
        <dd className={styles.cellSub}>{impactRest}</dd>
      </div>
      <div className={styles.cell} data-empty={conf === null ? "" : undefined}>
        <dt>확신도</dt>
        <dd className={`${styles.cellValue} num`}>{conf === null ? "해당 없음" : `${conf}% · ${word}`}</dd>
        <dd className={styles.cellSub}>
          <span className={styles.confTrack} aria-hidden="true">
            {conf !== null && <span className={styles.confFill} style={{ transform: `scaleX(${conf / 100})` }} />}
            <span className={styles.confMark} style={{ left: `${limits.confidencePct}%` }} />
          </span>
          자동 실행 기준 {limits.confidencePct}%
        </dd>
      </div>
      <div className={styles.cell} data-empty={top ? undefined : ""}>
        <dt>위험도</dt>
        <dd className={styles.cellValue}>
          {top ? (
            <>
              <span className={styles.shape} data-tone={top.risk === "LOW" ? "success" : "warning"} aria-hidden="true">
                {top.risk === "LOW" ? "●" : "▲"}
              </span>{" "}
              {RISK_WORD[top.risk]}
            </>
          ) : (
            "해당 없음"
          )}
        </dd>
        <dd className={styles.cellSub}>{top ? `${AI_ACTION_LABEL[top.kind]} · 허용 목록` : "금지 목록 조치는 내지 않음"}</dd>
      </div>
      <div className={styles.cell} data-empty={top ? undefined : ""}>
        <dt>되돌리기</dt>
        <dd className={styles.cellValue}>{top ? (top.kind === "BACKUP_NOW" ? "되돌릴 것 없음" : "가능") : "해당 없음"}</dd>
        <dd className={`${styles.cellSub} num`}>
          {top ? (stats ? `과거 같은 조치 ${stats.runs}회 · 되돌림 ${stats.reverts}회` : "과거 같은 조치 기록 없음") : "감사 기록에서 셈"}
        </dd>
      </div>
    </dl>
  );
}

function AiOff({ source }: { source: string }) {
  return (
    <div className={styles.aiOff}>
      <span className={styles.aiOffMark}>
        <AiMark size={24} />
      </span>
      <span className={styles.aiOffTitle}>
        <span aria-hidden="true">◌ </span>AI 연결 전
      </span>
      <span className={styles.aiOffDesc}>
        AI 판단 서비스와 서버 정책 엔진을 붙이는 2단계 전입니다. 판단과 조치안을 지어내지 않습니다. 경보와 ASG 정책은 AI 없이 그대로 동작합니다.
      </span>
      <span className={styles.notConnectedSource}>{source}</span>
    </div>
  );
}

/**
 * AI 칸(판의 ai)만 받는다. 판은 매초 새로 오지만 이 칸은 판단이 바뀔 때만 새 참조라(opsCenter의
 * shareEqual) 패널이 매초 다시 그려지지 않는다
 */
function AiPanel({ section, sim, onChanged, full = false }: {
  /** 판을 받기 전이면 null이다 */
  section: OpsSection<AiDesk> | null;
  sim: boolean;
  onChanged: () => void;
  /** AI 판단 화면은 결정된 조치안과 판단 이력을 모두 싣는다 */
  full?: boolean;
}) {
  const point = useOpsFocus((s) => s.point);
  const evidenceRef = useRef<HTMLDivElement>(null);
  const ai = section?.state === "READY" ? section.data : null;
  const open = ai ? ai.proposals.filter((p) => p.status === "PROPOSED") : [];
  const others = ai ? ai.proposals.filter((p) => p.status !== "PROPOSED") : [];
  const done = full ? others : others.slice(0, Math.max(1, 3 - open.length));
  const history = ai ? (full ? ai.history : ai.history.slice(0, 4)) : [];
  const limits = limitsOf(ai?.limits);
  const stopped = ai?.mode === "STOPPED";
  const [lead, rest] = ai ? splitLead(ai.situation) : ["", ""];

  const showEvidence = (p: AiProposal) => {
    const ref = KIND_REF[p.kind];
    if (ref) point(ref);
    evidenceRef.current?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  };

  return (
    <section className={styles.ai} aria-labelledby="ai-title" aria-busy={ai?.thinking || undefined}>
      <header className={styles.aiHead}>
        <h2 id="ai-title" className={styles.aiTitle}>
          <span className={styles.aiGlyph} aria-hidden="true">
            <AiMark size={16} />
          </span>
          AI 관제
        </h2>
        {sim && <span className={styles.simTag}>시뮬레이션</span>}
        {ai && (
          <span className={styles.modeTag} data-mode={ai.mode}>
            {ai.mode === "AUTO" ? "자동 모드" : ai.mode === "STOPPED" ? <><span aria-hidden="true">▲ </span>AI 정지</> : "수동 모드"}
          </span>
        )}
        <span className={styles.aiStatus}>
          {ai === null ? (
            section === null ? "불러오는 중" : "연결 전"
          ) : stopped ? (
            <>
              판단 멈춤{ai.stopped && <> · 정지 <b className="num">{hms(ai.stopped.at)}</b></>}
            </>
          ) : ai.thinking ? (
            <>
              <b>판단 중</b> · 근거 {ai.evidence.length}건
            </>
          ) : (
            <>
              판단 <b className="num">{ai.judgedAt ? hms(ai.judgedAt) : "없음"}</b>
            </>
          )}
        </span>
        {!full && ai && (
          <Link href="/admin/ai" className={styles.stripLink}>
            판단 기록
          </Link>
        )}
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
            <div className={styles.judgement}>
              <p className={styles.aiLead}>{lead}</p>
              {rest && <p className={styles.aiLeadRest}>{rest}</p>}
            </div>
            {ai.evidence.length > 0 && (
              <div className={styles.evidence} ref={evidenceRef}>
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
                      {e.severity && (
                        <span className={styles.shape} data-tone={e.severity === "critical" ? "danger" : e.severity === "warning" ? "warning" : "info"} aria-hidden="true">
                          {SEVERITY_SHAPE[e.severity]}
                        </span>
                      )}
                      {e.label} <b className="num">{e.value}</b>
                    </button>
                  ))}
                </div>
              </div>
            )}
            {ai.hypothesis && (
              <p className={styles.hypo}>
                <span className={styles.hypoKey}>원인 가설</span>
                {ai.hypothesis}
              </p>
            )}
            <div className={styles.aiSection}>
              <div className={styles.subHead}>AI 판단 요약</div>
              <Summary ai={ai} top={open[0] ?? null} limits={limits} />
            </div>
            <div className={styles.aiSection}>
              <div className={styles.subHead}>
                <span>
                  권장 조치 <span className={`${styles.count} num`}>{open.length}</span>건
                </span>
              </div>
              <div className={styles.proposals}>
                {open.length === 0 ? (
                  <div className={styles.empty}>
                    <span className={styles.emptyMark} aria-hidden="true" />
                    {stopped ? "AI 정지 중입니다. 재개하면 다시 판단합니다" : "조치안이 없습니다. 허용 목록에 맞는 조치가 필요할 때만 냅니다"}
                  </div>
                ) : (
                  open.map((p, i) => (
                    <ProposalCard key={p.id} p={p} index={i + 1} ai={ai} limits={limits} onChanged={onChanged} onEvidence={showEvidence} />
                  ))
                )}
              </div>
            </div>
            {done.length > 0 && (
              <div className={styles.aiSection}>
                <div className={styles.subHead}>
                  <span>
                    처리된 조치 <span className={`${styles.count} num`}>{others.length}</span>건
                  </span>
                </div>
                <div className={styles.proposals}>
                  {done.map((p) => (
                    <DoneCard key={p.id} p={p} onChanged={onChanged} />
                  ))}
                </div>
              </div>
            )}
            <div className={styles.aiSection}>
              <div className={styles.subHead}>판단 이력</div>
              <ol className={styles.historyList}>
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
                {ai.evidenceAt && (
                  <>
                    {" · "}
                    <Ago at={ai.evidenceAt} />
                  </>
                )}
              </span>
            </div>
            <div className={styles.guard}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" aria-hidden="true">
                <path d="M8 1.75 L13.25 3.75 V8 C13.25 11 11 13.25 8 14.25 C5 13.25 2.75 11 2.75 8 V3.75 Z" />
              </svg>
              <span className={styles.guardText}>
                AI는 허용 목록 안의 조치만 제안하고, 자동 모드에서만 한도 안에서 실행합니다. DB 승격, 데이터 삭제, 보안 설정, 재시작은 어느 모드에서도 하지 않습니다.
              </span>
              <RulesDisclosure label="목록" up limits={limits} />
            </div>
          </footer>
        </>
      )}
    </section>
  );
}

export default memo(AiPanel);
