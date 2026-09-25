"use client";

/**
 * AI 관제 패널(§12-9). 관제센터의 가운데 기둥이다.
 *
 * 큰 한 줄 판단, 권장 조치, 근거와 원인 가설, 처리된 조치, 판단 기록 순서로 읽힌다. 운영자가 할 일(승인하고
 * 실행)이 판단 바로 아래 첫 화면에 서게 하려는 순서다. 대기 조치안이 없으면 빈 상자 대신 평상 한 줄을 두고, 15분 안에
 * 처리한 조치가 있으면 그 자리에 마지막 조치 결과를 둔다(§12-9 v1.14). AI 판단 요약(확신도, 위험도, 과거 같은 조치)은 조치
 * 카드마다 한 줄로 싣는다. AI라는 사실은 보라 빛이 아니라 이 내용과 판단 과정으로 드러낸다(§12-10). 조치 카드는
 * 허용 목록 다섯 종에서만 나오고 금지 목록 조치는 어휘에 없어 카드가 될 수 없다(ADR 0035). 번호는 대기 중인
 * 조치안에만 붙이고 카드마다 ai-proposal-n 앵커를 두어 모드 줄의 링크가 첫 카드로 온다. 승인 버튼은 운영자의
 * 결정이라 AI 색이 아니라 브랜드 approve다. http 원천에서는 관제센터 서버의 판단과 조치 요청을 그리고, 서버가 없으면
 * 판단을 지어내지 않고 'AI 연결 전'만 그린다.
 */

import { memo, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { AdminError, getAdminApi, isAdminHttp } from "@/lib/admin/api";
import { actionKey, AI_ACTION_LABEL, canAutoRun, limitsOf, type AiLimits } from "@/lib/admin/aiPolicy";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { flashTone } from "@/lib/admin/motion";
import { useOpsFocus } from "@/lib/admin/opsCenter";
import { useCan } from "@/lib/admin/store";
import type { AiActionKind, AiDecision, AiDesk, AiProposal, OpsRef, OpsSection } from "@/lib/admin/types";
import { joinDots } from "@/lib/admin/text";
import { toast } from "@/components/Toast";
import adminStyles from "../admin.module.css";
import { AdminIcon } from "../icons";
import StatusShape from "../StatusShape";
import { useFlip } from "../useFlip";
import { useFreshKeys } from "../useFreshKeys";
import ActionConfirm from "./ActionConfirm";
import { RulesDisclosure } from "./ModeBar";
import { Ago, AiMark, FlashNum, hms, mmss, sectionSource, SEVERITY_SHAPE, useClock } from "./parts";
import styles from "./control.module.css";

const RISK_WORD = { LOW: "낮음", MEDIUM: "중간" } as const;

/** '마지막 조치 결과'로 올리는 처리. 거둔 조치와 '나중에'는 한 일이 없어 올리지 않는다 */
const LAST_RESULT = new Set<AiProposal["status"]>(["APPROVED", "AUTO_EXECUTED", "AUTO_REVERTED", "REVERTED", "REJECTED", "EXPIRED", "FAILED", "UNKNOWN"]);

/** 만료 까닭. 서버는 정지로 만료한 요청에 M9를 적고, 그 밖의 만료는 시간 초과다. sim의 만료는 정지뿐이다 */
function expiredWord(p: AiProposal): string {
  return p.hold && p.hold.rule !== "M9" ? "시간이 지나 만료" : "AI 정지로 만료";
}

/** 조치가 가리키는 자리. '근거 보기'가 그 자리를 잠깐 강조한다 */
const KIND_REF: Record<AiActionKind, OpsRef | null> = {
  READ_NODES: { kind: "READ_TIER" },
  THRESHOLD: { kind: "READ_TIER" },
  SILENCE: null,
  BOT_LOAD: { kind: "HOST", id: "bot-1" },
  BACKUP_NOW: { kind: "BACKUP" },
};

/**
 * 확신도의 낱말. 자동 실행 기준(AI 한도의 확신도 기준) 이상이면 높음, 60% 이상이면 보통, 그 아래는 낮음이다.
 * 기준을 넘는 값을 '보통'으로 적으면 자동 실행 조건과 모순된다(§12-9)
 */
export function confidenceWord(pct: number, threshold: number): string {
  if (pct >= threshold) return "높음";
  return pct >= 60 ? "보통" : "낮음";
}

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
          <span aria-hidden="true"><StatusShape kind="circle" /></span> 승인 · {who} {when} · 실행됨
        </span>
      );
    case "REJECTED":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true"><StatusShape kind="ring" /></span> 거절 · {who} {when}
        </span>
      );
    case "DEFERRED":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true"><StatusShape kind="dashed" /></span> 나중에 · {who} ·{" "}
          {/* 서버는 나중에로 둔 요청을 자동 실행에서 영구히 빼고 사람 승인만 받는다(§6-F). sim은 5분 동안 다시 내지 않는다 */}
          {p.hold !== null || isAdminHttp() ? "자동 실행하지 않고 승인만 받음" : "5분 동안 다시 내지 않음"}
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
          <span aria-hidden="true"><StatusShape kind="triangle" /></span> 자동 되돌림 · {when}
        </span>
      );
    case "REVERTED":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true"><StatusShape kind="ring" /></span> 되돌림 · {who} {when}
        </span>
      );
    case "WITHDRAWN":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true"><StatusShape kind="ring" /></span> 상황이 바뀌어 거둠 · {when}
        </span>
      );
    case "EXPIRED":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true"><StatusShape kind="ring" /></span> {expiredWord(p)} · {who} {when}
        </span>
      );
    // 아래 넷은 서버 정책 엔진에만 있는 상태다(API §6-F)
    case "EXECUTING":
      return (
        <span className={styles.decided} data-tone="warning">
          <span aria-hidden="true"><StatusShape kind="triangle" /></span> 실행 중 · {when}
        </span>
      );
    case "FAILED":
      return (
        <span className={styles.decided} data-tone="danger">
          <span aria-hidden="true"><StatusShape kind="square" /></span> 실행 실패 · {when}
          {p.hold && <> · {p.hold.reason}</>}
        </span>
      );
    case "UNKNOWN":
      return (
        <span className={styles.decided} data-tone="warning">
          <span aria-hidden="true"><StatusShape kind="triangle" /></span> 결과 확인 필요 · 실행이 중간에 끊겼습니다
        </span>
      );
    case "DENIED":
      return (
        <span className={styles.decided}>
          <span aria-hidden="true"><StatusShape kind="square" /></span> 정책이 막음{p.hold && <> · {p.hold.rule} {p.hold.reason}</>}
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
/**
 * AI 정지로 만료된 조치의 한 줄 상태(§12-9 v1.14). 누를 수 없는 승인 단추를 카드째 남기지 않는다
 */
function ExpiredLine({ p, fresh }: { p: AiProposal; fresh: boolean }) {
  return (
    <p className={styles.doneLine} data-flip={p.id} data-fresh={fresh ? "" : undefined}>
      <StatusShape kind="ring" className={styles.shape} tone="off" />
      <span>
        <b>{expiredWord(p)}</b> · {p.title}
        {p.decidedBy && (
          <>
            {" "}
            · {p.decidedBy} <span className="num">{p.decidedAt ? hms(p.decidedAt) : ""}</span>
          </>
        )}
      </span>
    </p>
  );
}

function DoneCard({ p, onChanged, fresh }: { p: AiProposal; onChanged: () => void; fresh: boolean }) {
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
  if (p.status === "EXPIRED") return <ExpiredLine p={p} fresh={fresh} />;
  return (
    <article
      className={styles.card}
      data-status={p.status}
      data-flip={p.id}
      data-fresh={fresh ? "" : undefined}
      data-pulse={fresh && p.status === "AUTO_REVERTED" ? "" : undefined}
      aria-label={`처리된 조치. ${p.title}`}
    >
      <div className={styles.cardTop}>
        <span className={styles.cardTitle}>{p.title}</span>
        <span className={styles.badge} data-kind="done">
          처리됨
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

/**
 * 조치안이 자동 모드에서 실행될 수 있는가. 확신도가 기준에 닿아야 하고, 수동 모드면 늘 승인이다. 완화 방향의 임계값과
 * 봇 용량 보호 기준 아래의 봇 부하 줄임은 자동 모드에서도 승인으로만 실행한다(ADR 0035, 사용자 확정 2026-09-25)
 */
function autoBadge(p: AiProposal, mode: AiDesk["mode"], limits: AiLimits): { kind: "auto" | "approve"; text: string } {
  if (canAutoRun(p, mode, limits)) return { kind: "auto", text: "자동 실행 가능" };
  return { kind: "approve", text: "승인 필요" };
}

function CardMenu({ p, onEvidence, judgmentId }: { p: AiProposal; onEvidence: (p: AiProposal) => void; judgmentId: string | null }) {
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
          <button
            type="button"
            className={styles.menuItem}
            disabled={judgmentId === null}
            onClick={() => {
              setOpen(false);
              if (judgmentId) useOpsDetail.getState().openJudgment(judgmentId);
            }}
          >
            판단 과정
          </button>
          <Link href="/admin/ai" className={styles.menuItem}>
            기록 보기
          </Link>
        </span>
      )}
    </span>
  );
}

/**
 * 조치 카드 안의 요약 한 줄(§12-9). 확신도와 자동 실행 기준 눈금, 위험도, 감사 기록에서 센 과거 같은 조치다.
 * 좁은 카드에서는 항목 단위로 다음 줄로 넘어가고 항목 안에서는 끊지 않는다
 */
function CardSummary({ p, ai, limits }: { p: AiProposal; ai: AiDesk; limits: AiLimits }) {
  const stats = ai.actionStats[actionKey(p.kind, p.title)];
  return (
    <div className={styles.cardSummary}>
      <span className={styles.sumItem}>
        확신도{" "}
        <b className="num">
          {p.confidencePct}% · {confidenceWord(p.confidencePct, limits.confidencePct)}
        </b>
        <span className={styles.confMini} aria-hidden="true">
          <span className={styles.confFill} style={{ transform: `scaleX(${p.confidencePct / 100})` }} />
          <span className={styles.confMark} style={{ left: `${limits.confidencePct}%` }} />
        </span>
        <span className="sr-only">자동 실행 기준 {limits.confidencePct}%</span>
      </span>
      <span className={styles.sumItem}>
        위험{" "}
        {/* 위험도는 상태가 아니라 조치의 성질이라 낮음에 초록을 칠하지 않는다. 중간만 주의 색 ▲다(§12-9) */}
        <StatusShape kind={p.risk === "LOW" ? "circle" : "triangle"} tone={p.risk === "LOW" ? undefined : "warning"} className={styles.shape} />{" "}
        <b>{RISK_WORD[p.risk]}</b>
      </span>
      <span className={styles.sumItem}>
        {stats ? (
          <>
            과거 같은 조치 <b className="num">{stats.runs}</b>회 · 되돌림 <b className="num">{stats.reverts}</b>회
          </>
        ) : (
          "과거 같은 조치 기록 없음"
        )}
      </span>
    </div>
  );
}

function ProposalCard({ p, index, ai, limits, onChanged, onEvidence, fresh }: {
  p: AiProposal;
  index: number;
  ai: AiDesk;
  limits: AiLimits;
  onChanged: () => void;
  onEvidence: (p: AiProposal) => void;
  fresh: boolean;
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
  // 이 조치안을 후보로 낸 가장 최근 판단 기록. ⋮ 메뉴의 '판단 과정'이 연다
  const judgmentId = ai.history.find((h) => h.trace?.candidates.some((c) => c.id === p.id))?.id ?? null;
  return (
    <article
      id={`ai-proposal-${index}`}
      tabIndex={-1}
      className={styles.card}
      data-status={p.status}
      data-flip={p.id}
      data-fresh={fresh ? "" : undefined}
      aria-label={`조치안 ${index}. ${p.title}`}
    >
      <div className={styles.cardTop}>
        <span className={`${styles.cardNo} num`} aria-hidden="true">
          {index}
        </span>
        <span className={styles.cardTitle}>{p.title}</span>
        <span className={styles.badge} data-kind={badge.kind}>
          {badge.text}
        </span>
        <CardMenu p={p} onEvidence={onEvidence} judgmentId={judgmentId} />
      </div>
      <CardSummary p={p} ai={ai} limits={limits} />
      <dl className={styles.cardRows}>
        <dt>대상</dt>
        <dd>{p.target}</dd>
        <dt>예상 영향</dt>
        <dd>{joinDots(p.impact)}</dd>
        <dt>되돌리기</dt>
        <dd>{joinDots(p.revertPlan)}</dd>
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
        <ActionConfirm p={p} index={index} ai={ai} limits={limits} busy={busy} onConfirm={() => void decide("APPROVE")} onClose={() => setConfirm(false)} />
      )}
    </article>
  );
}

function AiOff({ source }: { source: string }) {
  return (
    <div className={styles.aiOff}>
      <span className={styles.aiOffMark}>
        <AiMark size={24} />
      </span>
      <span className={styles.aiOffTitle}>
        <span aria-hidden="true"><StatusShape kind="dashed" /> </span>AI 연결 전
      </span>
      <span className={styles.aiOffDesc}>
        관리 API에 관제센터 서버(AI 판단 서비스와 정책 엔진)가 없어 판단과 조치안을 지어내지 않습니다. 경보와 ASG 정책은 AI 없이 그대로 동작합니다.
      </span>
      <span className={styles.notConnectedSource}>{source}</span>
    </div>
  );
}

/**
 * AI 칸(판의 ai)만 받는다. 판은 매초 새로 오지만 이 칸은 판단이 바뀔 때만 새 참조라(opsCenter의
 * shareEqual) 패널이 매초 다시 그려지지 않는다
 */
function AiPanel({ section, onChanged, full = false }: {
  /** 판을 받기 전이면 null이다 */
  section: OpsSection<AiDesk> | null;
  onChanged: () => void;
  /** AI 판단 화면은 결정된 조치안과 판단 기록을 모두 싣는다 */
  full?: boolean;
}) {
  const point = useOpsFocus((s) => s.point);
  const evidenceRef = useRef<HTMLDivElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const ai = section?.state === "READY" ? section.data : null;
  const open = ai ? ai.proposals.filter((p) => p.status === "PROPOSED") : [];
  const others = ai ? ai.proposals.filter((p) => p.status !== "PROPOSED") : [];
  // 대기 조치안이 없으면 가장 최근에 처리한 조치를 '마지막 조치 결과'로 올린다. 판은 15분 안에 처리한 조치만 싣는다
  const last = open.length === 0 ? others.find((p) => LAST_RESULT.has(p.status)) ?? null : null;
  const remaining = last ? others.filter((p) => p.id !== last.id) : others;
  const done = full ? remaining : remaining.slice(0, Math.max(last ? 0 : 1, 3 - open.length - (last ? 1 : 0)));
  const fresh = useFreshKeys(ai ? ai.proposals.map((p) => `${p.id}:${p.status}`) : []);
  const isFresh = (p: AiProposal) => fresh.has(`${p.id}:${p.status}`);
  useFlip(bodyRef);
  const history = ai ? (full ? ai.history : ai.history.slice(0, 4)) : [];
  const limits = limitsOf(ai?.limits);
  const stopped = ai?.mode === "STOPPED";
  const [lead, rest] = ai ? splitLead(ai.situation) : ["", ""];
  // 새 판단 기록이 생기면 기둥 안 스크롤을 맨 위로 되돌린다. 새 판단과 대기 조치안이 먼저 보여야 한다(§12-9)
  const latest = ai?.history[0]?.id ?? null;
  useEffect(() => {
    if (latest !== null) bodyRef.current?.scrollTo({ top: 0 });
  }, [latest]);
  const judgedAt = ai?.judgedAt ? hms(ai.judgedAt) : null;

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
        {ai && (
          <span className={styles.modeTag} data-mode={ai.mode}>
            {ai.mode === "AUTO" ? "자동 모드" : ai.mode === "STOPPED" ? <><span aria-hidden="true"><StatusShape kind="triangle" /> </span>AI 정지</> : "수동 모드"}
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
      {section !== null && section.state !== "READY" ? (
        <AiOff source={sectionSource(section)} />
      ) : ai === null ? (
        <div className={styles.aiOff}>
          <span className={styles.aiOffTitle}>판단 불러오는 중</span>
        </div>
      ) : (
        <>
          <div className={styles.aiBody} ref={bodyRef}>
            <div className={styles.judgement}>
              <p className={styles.aiLead}>{lead}</p>
              {rest && <p className={styles.aiLeadRest}>{joinDots(rest)}</p>}
            </div>
            {open.length > 0 ? (
              <div className={styles.aiSection}>
                <div className={styles.subHead}>
                  <span>
                    권장 조치{" "}
                    <span className={`${styles.count} num`}>
                      <FlashNum value={open.length} tone={flashTone} />
                    </span>
                    건
                  </span>
                </div>
                <div className={styles.proposals}>
                  {open.map((p, i) => (
                    <ProposalCard key={p.id} p={p} index={i + 1} ai={ai} limits={limits} onChanged={onChanged} onEvidence={showEvidence} fresh={isFresh(p)} />
                  ))}
                </div>
              </div>
            ) : last ? (
              <div className={styles.aiSection}>
                <div className={styles.subHead}>
                  <span>마지막 조치 결과</span>
                  <span className={styles.quietLine}>권장 조치 없음</span>
                </div>
                <div className={styles.proposals}>
                  <DoneCard p={last} onChanged={onChanged} fresh={isFresh(last)} />
                </div>
              </div>
            ) : (
              /* 평상 한 줄(§12-9 v1.14). 빈 상자 두 개로 자리를 먹지 않는다 */
              <p className={styles.quietLine}>
                권장 조치 없음
                {stopped ? (
                  <> · AI 정지 중이라 판단하지 않습니다 · 재개하면 다시 판단합니다</>
                ) : (
                  <>
                    {" "}
                    · 5초마다 판단 · 지난 24시간 AI 조치 <b className="num">{ai.recent.runs}</b>회 · 되돌림 <b className="num">{ai.recent.reverts}</b>회
                  </>
                )}
              </p>
            )}
            {ai.evidence.length > 0 && (
              <div className={styles.evidence} ref={evidenceRef}>
                <span className={styles.evidenceKey}>근거</span>
                <div className={styles.evidenceChips}>
                  {judgedAt && (
                    <span className={`${styles.evidenceAt} num`}>
                      판단 <b>{judgedAt}</b> 기준
                    </span>
                  )}
                  {ai.evidence.map((e) => (
                    <button
                      key={e.id}
                      type="button"
                      className={styles.chip}
                      title={judgedAt ? `판단 ${judgedAt} 기준 값` : undefined}
                      onClick={() => {
                        if (e.ref) point(e.ref);
                      }}
                      aria-label={`근거 ${e.label} ${e.value}${judgedAt ? `, 판단 ${judgedAt} 기준` : ""}. 누르면 해당 자리 강조`}
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
            {done.length > 0 && (
              <div className={styles.aiSection}>
                <div className={styles.subHead}>
                  <span>
                    처리된 조치 <span className={`${styles.count} num`}>{remaining.length}</span>건
                  </span>
                </div>
                <div className={styles.proposals}>
                  {done.map((p) => (
                    <DoneCard key={p.id} p={p} onChanged={onChanged} fresh={isFresh(p)} />
                  ))}
                </div>
              </div>
            )}
            <div className={styles.aiSection}>
              <div className={styles.subHead}>판단 기록</div>
              <ol className={styles.historyList}>
                {history.map((h) => (
                  <li key={h.id}>
                    <button
                      type="button"
                      className={styles.historyRow}
                      aria-haspopup="dialog"
                      title={h.summary}
                      onClick={() => useOpsDetail.getState().openJudgment(h.id)}
                    >
                      <span className={styles.historyHead}>
                        <span className={`${styles.historyTime} num`}>{hms(h.at)}</span>
                        <span className={styles.historyOutcome}>{h.outcome}</span>
                        {h.trace && <span className={styles.historyMore}>판단 과정</span>}
                      </span>
                      <span className={styles.historyText}>{joinDots(h.summary)}</span>
                    </button>
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
