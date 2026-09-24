"use client";

/**
 * AI 상담 패널(관리자 설계 4-6). 운영자가 대화로 지금 상태를 묻는 칸이다. 오른쪽에 뜨고 뒤의 판을 가리지
 * 않도록 모달이 아니다. 답마다 근거 칩, 수집 시각과 모델, 관련 화면 링크를 붙인다.
 *
 * 상담은 조회만 한다. 실행을 부탁하는 말에도 실행 단추를 두지 않고 그 조치가 있는 화면으로 가는 링크만
 * 준다(승인 우회 통로 금지). AI가 정지 중이거나 원천이 연결 전이면 입력을 막고 이유를 적는다.
 * 첫 열림에 불러오는 조각이라(dynamic import) 셸의 첫 그리기에 들지 않는다.
 */

import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import Link from "next/link";
import { getAdminApi } from "@/lib/admin/api";
import { CHAT_MAX_CHARS, linksOf, SUGGESTED_QUESTIONS } from "@/lib/admin/aiChat";
import { CHAT_WIDTH, useChat, type ChatMessage } from "@/lib/admin/chatStore";
import { useOpsPick } from "@/lib/admin/opsCenter";
import { hms } from "@/lib/admin/timeText";
import type { AiChatEvidence } from "@/lib/admin/types";
import { AdminIcon } from "../icons";
import { SimMark } from "../ui";
import styles from "./chat.module.css";

const KIND_WORD: Record<AiChatEvidence["kind"], string> = {
  METRIC: "지표",
  ALERT: "경보",
  INCIDENT: "사건",
  PROPOSAL: "조치안",
  THRESHOLD: "임계값",
  HOST: "호스트",
};
const SEVERITY = { critical: { shape: "■", tone: "danger" }, warning: { shape: "▲", tone: "warning" }, info: { shape: "●", tone: "info" } } as const;

function EvidenceChip({ e, at }: { e: AiChatEvidence; at: string | null }) {
  const sev = e.severity ? SEVERITY[e.severity] : null;
  return (
    <span className={styles.chip} title={`${KIND_WORD[e.kind]} · ${e.label} ${e.value}${at ? ` · 수집 ${hms(at)}` : ""}`}>
      {sev && (
        <span className={styles.shape} data-tone={sev.tone} aria-hidden="true">
          {sev.shape}
        </span>
      )}
      {e.label} <b className="num">{e.value}</b>
    </span>
  );
}

/** 근거를 종류별 줄로 묶는다. 칩마다 종류를 되풀이하지 않고 줄 머리에 한 번 적는다. 줄 순서는 처음 나온 순서다 */
function byKind(list: AiChatEvidence[]): [AiChatEvidence["kind"], AiChatEvidence[]][] {
  const out = new Map<AiChatEvidence["kind"], AiChatEvidence[]>();
  for (const e of list) {
    const row = out.get(e.kind);
    if (row) row.push(e);
    else out.set(e.kind, [e]);
  }
  return [...out];
}

function AiAnswer({ m, onRetry, busy }: { m: ChatMessage; onRetry: (q: string) => void; busy: boolean }) {
  const r = m.reply;
  const head = (
    <div className={styles.aiHead}>
      <span className={styles.aiDot} aria-hidden="true">
        <AdminIcon name="ai" size={14} />
      </span>
      <b>AI</b>
      <time className="num" dateTime={m.at}>
        {hms(m.at)}
      </time>
      {r?.declined && (
        <span className={styles.readOnly}>
          <span aria-hidden="true">○</span> 조회 전용 · 실행하지 않음
        </span>
      )}
    </div>
  );
  if (m.error !== null) {
    return (
      <article className={styles.ai} aria-label="AI 답 받지 못함">
        {head}
        <p className={styles.answer} data-tone="danger">
          <span aria-hidden="true">■ </span>답을 받지 못했습니다 · {m.error}
        </p>
        {m.question && (
          <button type="button" className={`${styles.textBtn} ${styles.retry}`} disabled={busy} onClick={() => onRetry(m.question as string)}>
            다시 묻기
          </button>
        )}
      </article>
    );
  }
  if (r === null) return null;
  if (r.state !== "OK") {
    return (
      <article className={styles.ai} aria-label="AI 답 없음">
        {head}
        <p className={styles.answer} data-tone="warning">
          <span aria-hidden="true">▲ </span>
          {r.state === "STOPPED" ? "AI 정지 중이라 답하지 않습니다. 운영자 바의 'AI 재개'로 재개하면 다시 답합니다." : "상담 모델 설정이 없어 답하지 않습니다."}
        </p>
      </article>
    );
  }
  const links = linksOf(r);
  return (
    <article className={styles.ai} aria-label="AI 답">
      {head}
      <p className={styles.answer}>{r.answer}</p>
      {r.evidence.length > 0 && (
        <dl className={styles.evidence} aria-label="근거">
          {byKind(r.evidence).map(([kind, items]) => (
            <div key={kind} className={styles.evRow}>
              <dt className={styles.evKey}>{KIND_WORD[kind]}</dt>
              <dd className={styles.chips}>
                {items.map((e) => (
                  <EvidenceChip key={e.id} e={e} at={r.collectedAt} />
                ))}
              </dd>
            </div>
          ))}
        </dl>
      )}
      <div className={styles.meta}>
        {/* 안내 답처럼 근거가 없는 답은 '근거 0건'이 아니라 무엇을 보고 답했는지(관제 판의 수집 시각)만 적는다 */}
        <span>
          {r.evidence.length > 0 ? (
            <>
              근거 <b className="num">{r.evidence.length}건</b> · 수집{" "}
            </>
          ) : (
            "관제 판 수집 "
          )}
          <b className="num">{r.collectedAt ? hms(r.collectedAt) : "없음"}</b>
        </span>
        <span>
          모델 <b>{r.model ?? "미확인"}</b>
        </span>
      </div>
      {links.length > 0 && (
        <nav className={styles.links} aria-label="관련 화면">
          {links.map((l) => (
            <Link key={l.href} href={l.href} className={styles.link}>
              {l.label}
              <span aria-hidden="true">›</span>
            </Link>
          ))}
        </nav>
      )}
    </article>
  );
}

export default function ChatPanel() {
  const titleId = useId();
  const hintId = useId();
  const width = useChat((s) => s.width);
  const messages = useChat((s) => s.messages);
  const pending = useChat((s) => s.pending);
  const { setOpen, setWidth, ask, clear } = useChat.getState();
  const api = getAdminApi();
  const sim = api.aiChat === "SIM";
  // AI 칸의 모드만 고른다. 판이 매초 새로 와도 모드가 같으면 다시 그리지 않는다
  const aiMode = useOpsPick((c) => (c?.ai.state === "READY" ? c.ai.data.mode : null));
  const blocked: "http" | "stopped" | null = !sim ? "http" : aiMode === "STOPPED" ? "stopped" : null;

  const [draft, setDraft] = useState("");
  const [drag, setDrag] = useState(false);
  const logRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const [opener] = useState(() => (typeof document === "undefined" ? null : (document.activeElement as HTMLElement | null)));

  // 열리면 입력칸으로(막혔으면 닫기 단추로), 닫히면 연 자리로 포커스를 돌린다
  useEffect(() => {
    (inputRef.current && !inputRef.current.disabled ? inputRef.current : closeRef.current)?.focus();
    return () => {
      if (opener && document.contains(opener)) opener.focus();
    };
  }, [opener]);

  // 새 말이 오면 맨 아래로 내린다. 움직임 줄이기면 곧바로 옮긴다
  useLayoutEffect(() => {
    const el = logRef.current;
    if (!el) return;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    el.scrollTo({ top: el.scrollHeight, behavior: reduce ? "auto" : "smooth" });
  }, [messages.length, pending]);

  // 입력칸 높이는 글에 맞춰 여섯 줄까지 늘린다
  useLayoutEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 144)}px`;
  }, [draft]);

  const send = (text: string) => {
    if (blocked || pending || text.trim() === "") return;
    setDraft("");
    void ask(text);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // 한글 조합 중의 Enter는 글자를 확정하는 입력이라 보내지 않는다
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing && e.keyCode !== 229) {
      e.preventDefault();
      send(draft);
    }
  };

  const onResizeDown = (e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault();
    const startX = e.clientX;
    const startW = width;
    const el = e.currentTarget;
    el.setPointerCapture(e.pointerId);
    setDrag(true);
    const move = (ev: PointerEvent) => setWidth(startW + (startX - ev.clientX), false);
    const up = () => {
      setDrag(false);
      setWidth(useChat.getState().width);
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      el.removeEventListener("pointercancel", up);
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
  };

  const onResizeKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? 64 : 16;
    if (e.key === "ArrowLeft") setWidth(width + step);
    else if (e.key === "ArrowRight") setWidth(width - step);
    else if (e.key === "Home") setWidth(CHAT_WIDTH.max);
    else if (e.key === "End") setWidth(CHAT_WIDTH.min);
    else return;
    e.preventDefault();
  };

  const len = draft.length;

  return (
    <section
      id="ai-chat"
      className={styles.panel}
      style={{ "--chat-w": `${width}px` } as React.CSSProperties}
      role="dialog"
      aria-modal="false"
      aria-labelledby={titleId}
      onKeyDown={(e) => {
        if (e.key === "Escape") {
          e.stopPropagation();
          setOpen(false);
        }
      }}
    >
      <header className={styles.head}>
        <span className={styles.mark} aria-hidden="true">
          <AdminIcon name="ai" size={16} />
        </span>
        <h2 id={titleId} className={styles.title}>
          AI 상담
        </h2>
        {sim && <SimMark />}
        <span className={styles.headTail}>
          <button type="button" className={styles.textBtn} disabled={messages.length === 0 || pending} onClick={clear}>
            대화 지우기
          </button>
          <button ref={closeRef} type="button" className={styles.iconBtn} aria-label="AI 상담 닫기" title="닫기 · Esc" onClick={() => setOpen(false)}>
            <AdminIcon name="close" size={16} />
          </button>
        </span>
      </header>
      <p className={styles.lead}>관제 판의 현재값으로 답합니다. 조회 전용이라 조치를 실행하지 않습니다</p>

      <div ref={logRef} className={styles.log} role="log" aria-live="polite" aria-relevant="additions" aria-label="상담 기록">
        {messages.length === 0 ? (
          <div className={styles.empty}>
            <span className={styles.emptyMark} aria-hidden="true">
              <AdminIcon name="chat" size={20} />
            </span>
            <span className={styles.emptyTitle}>지금 상태를 물어보십시오</span>
            <p className={styles.emptyDesc}>
              경보·사건, 조회 계층, AI 조치안, 서버 상태를 관제 판에서 읽어 답합니다. 축소나 승인처럼 실행을 부탁하면 실행하지 않고 그 조치가 있는 화면과 승인 절차를 알려 드립니다.
            </p>
            <span className={styles.suggestLabel}>추천 질문</span>
            <div className={styles.suggest}>
              {SUGGESTED_QUESTIONS.map((q) => (
                <button key={q} type="button" className={styles.suggestItem} disabled={blocked !== null || pending} onClick={() => send(q)}>
                  {q}
                  <span className={styles.suggestArrow} aria-hidden="true">
                    <AdminIcon name="chevronDown" size={14} />
                  </span>
                </button>
              ))}
            </div>
          </div>
        ) : (
          messages.map((m) =>
            m.role === "operator" ? (
              <div key={m.id} className={styles.me}>
                <p className={styles.bubble}>{m.text}</p>
                <time className={`${styles.time} num`} dateTime={m.at}>
                  {hms(m.at)}
                </time>
              </div>
            ) : (
              <AiAnswer key={m.id} m={m} onRetry={send} busy={pending || blocked !== null} />
            ),
          )
        )}
        {pending && (
          <div className={styles.ai} role="status">
            <div className={styles.aiHead}>
              <span className={styles.aiDot} aria-hidden="true">
                <AdminIcon name="ai" size={14} />
              </span>
              <b>AI</b>
              <span>근거를 모아 답을 쓰는 중</span>
            </div>
            <div className={styles.thinking} aria-hidden="true" />
          </div>
        )}
      </div>

      {blocked === "http" && (
        <div className={styles.blocked} role="note">
          <b>
            <span aria-hidden="true">◌ </span>AI 연결 전
          </b>
          서버 상담 경로(POST /admin/v1/msp/ai/chat)를 붙이기 전이라 질문을 보내지 않습니다.
        </div>
      )}
      {blocked === "stopped" && (
        <div className={styles.blocked} data-kind="stopped" role="note">
          <b>
            <span aria-hidden="true">▲ </span>AI 정지 중
          </b>
          운영자가 AI를 멈춰 상담도 쉽니다. 운영자 바의 'AI 재개'로 재개하면 다시 받습니다.
        </div>
      )}
      <form
        className={styles.composer}
        onSubmit={(e) => {
          e.preventDefault();
          send(draft);
        }}
      >
        <div className={styles.field} data-disabled={blocked ? "" : undefined}>
          <textarea
            ref={inputRef}
            className={styles.input}
            rows={1}
            value={draft}
            maxLength={CHAT_MAX_CHARS}
            disabled={blocked !== null}
            placeholder={blocked ? "질문을 받을 수 없는 상태" : "예: 지금 경보는 무엇인가요?"}
            aria-label="AI 상담 질문"
            aria-describedby={hintId}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={onKeyDown}
          />
          <button type="submit" className={styles.send} aria-label="보내기" disabled={blocked !== null || pending || draft.trim() === ""}>
            <AdminIcon name="send" size={16} />
          </button>
        </div>
        <div className={styles.hint} id={hintId}>
          {/* 질문은 서버 감사(ops.ai.chat)와 모델 입력에 들어간다. 서버가 가리는 것은 모양이 뚜렷한 값뿐이라 여기서 먼저 알린다 */}
          <span className={styles.hintText}>
            <span>Enter 보내기 · Shift+Enter 줄바꿈</span>
            <span>질문은 감사 기록에 남습니다. 비밀 값과 개인정보는 적지 마십시오</span>
          </span>
          <span className={`${styles.count} num`} data-near={len >= CHAT_MAX_CHARS * 0.9 ? "" : undefined}>
            {len}/{CHAT_MAX_CHARS}
          </span>
        </div>
      </form>
      {/* 폭 손잡이. 읽는 순서의 끝에 두어 Tab이 입력칸보다 먼저 닿지 않게 한다 */}
      <div
        className={styles.resize}
        role="separator"
        aria-orientation="vertical"
        aria-label="상담 패널 폭 · 왼쪽·오른쪽 화살표로 조절"
        aria-valuemin={CHAT_WIDTH.min}
        aria-valuemax={CHAT_WIDTH.max}
        aria-valuenow={width}
        tabIndex={0}
        data-drag={drag ? "" : undefined}
        onPointerDown={onResizeDown}
        onKeyDown={onResizeKey}
      />
    </section>
  );
}
