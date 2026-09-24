"use client";

/**
 * AI 운영 모드 줄(디자인 시스템 §12-9, ADR 0035). 수동이 기본이다.
 *
 * 자동 켜기는 사유와 유효 시간을 받은 뒤 1인 승인 티켓을 발급·소각한다. 'AI 즉시 정지'는 두 모드
 * 어디서든 늘 보이고 승인 없이 누구나 누른다. 정지하면 줄이 주의 면으로 바뀌고 'AI 재개'가 선다.
 * 자동인 동안 줄 전체가 AI 강조 면이 되고 남은 시간, 이번 자동 모드의 조치 수, 쿨다운, 관찰 창을
 * 싣는다. 줄 높이는 모드와 무관하게 같아 아래 판이 움직이지 않는다.
 */

import { useState } from "react";
import Link from "next/link";
import { getAdminApi, AdminError } from "@/lib/admin/api";
import { AI_ACTION_LABEL, AI_FORBIDDEN, allowedRules, autoPreconditions, AUTO_HOURS, limitsOf, type AiLimits } from "@/lib/admin/aiPolicy";
import { useCan } from "@/lib/admin/store";
import type { AiDesk, ControlCenter } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import ConfirmDialog from "../ConfirmDialog";
import adminStyles from "../admin.module.css";
import { AiMark, hmmss, hms, mmss, useClock } from "./parts";
import styles from "./control.module.css";

/** 허용·금지 목록 펼침. 모드 줄과 AI 패널 바닥이 같은 목록을 연다 */
export function RulesDisclosure({ label = "허용·금지 목록", up = false, limits }: { label?: string; up?: boolean; limits?: AiLimits }) {
  return (
    <details className={styles.rules}>
      <summary>{label}</summary>
      <div className={styles.rulesPanel} data-up={up ? "" : undefined}>
        <span className={styles.rulesTitle}>허용 목록(되돌릴 수 있고 데이터와 무관한 조치)</span>
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
        <span className={styles.rulesTitle}>금지 목록(어느 모드에서도 AI가 하지 않음)</span>
        <ul className={styles.rulesList}>
          {AI_FORBIDDEN.map((r) => (
            <li key={r}>
              <span aria-hidden="true">■</span>
              <span>{r}</span>
            </li>
          ))}
        </ul>
        <span className={styles.rulesTitle}>자동 실행 조건</span>
        <ul className={styles.rulesList}>
          {autoPreconditions(limits).map((r) => (
            <li key={r}>
              <span aria-hidden="true">▲</span>
              <span>{r}</span>
            </li>
          ))}
        </ul>
      </div>
    </details>
  );
}

function AutoDialog({ limits, onClose, onDone }: { limits: AiLimits; onClose: () => void; onDone: () => void }) {
  const [hours, setHours] = useState<number>(2);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const ready = reason.trim().length >= 2;
  const submit = async () => {
    setBusy(true);
    try {
      await getAdminApi().enableAiAuto(hours, reason);
      toast(`AI 자동 모드 켜짐 · ${hours}시간 뒤 수동 복귀`, "info");
      onDone();
      onClose();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "자동 모드 켜기 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <ConfirmDialog
      title="AI 자동 모드 켜기"
      description="허용 목록 조치를 AI가 한도 안에서 자동으로 실행한다. 켜면 1인 승인 티켓을 발급하고 곧바로 소각해 승인 원장과 감사 로그에 남긴다."
      target="AI 운영 · 허용 목록 5종"
      blastRadius={`${hours}시간 뒤 수동 복귀 · 이번 자동 모드 ${limits.autoCap}회 상한`}
      tone="warning"
      confirmLabel="승인하고 켜기"
      busy={busy}
      wide
      confirmDisabled={!ready}
      onConfirm={() => void submit()}
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <div className={styles.autoForm}>
        <span className={styles.autoFormLabel} id="auto-hours">
          유효 시간 · 최대 8시간
        </span>
        <div className={styles.switch} role="group" aria-labelledby="auto-hours">
          {AUTO_HOURS.map((h) => (
            <button
              key={h}
              type="button"
              className={styles.switchBtn}
              aria-pressed={hours === h}
              onClick={() => setHours(h)}
            >
              {h}시간
            </button>
          ))}
        </div>
        <label className={styles.autoFormLabel} htmlFor="auto-reason">
          사유 · 감사 로그와 Teams 알림에 남음
        </label>
        <input
          id="auto-reason"
          className={styles.autoReason}
          value={reason}
          maxLength={80}
          placeholder="예: 야간 조회 급증 대응"
          onChange={(e) => setReason(e.target.value)}
        />
        <div className={styles.autoRules}>
          <div>
            <span className={styles.rulesTitle}>자동으로 하는 것</span>
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
            <span className={styles.rulesTitle}>어느 모드에서도 하지 않는 것</span>
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
        <p className={styles.autoNote}>
          자동 조치 수는 켤 때마다 0부터 셉니다. 쿨다운과 관찰 창은 그대로 남습니다. 악화하면 자동으로 되돌리고
          수동으로 바뀝니다. AI 즉시 정지는 승인 없이 언제든 누를 수 있습니다.
        </p>
      </div>
    </ConfirmDialog>
  );
}

function ResumeDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const ready = reason.trim().length >= 2;
  const submit = async () => {
    setBusy(true);
    try {
      await getAdminApi().resumeAi(reason);
      toast("AI 재개 · 수동 모드", "info");
      onDone();
      onClose();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "AI 재개 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <ConfirmDialog
      title="AI 재개"
      description="수동 모드로만 재개합니다. AI가 다시 판단하고 조치안을 내지만 실행은 운영자 승인입니다. 자동 모드는 다시 승인 티켓(ops.ai.auto)을 거쳐 켭니다."
      target="AI 운영 · 수동으로 재개"
      blastRadius="새 판단과 조치안 · 실행 없음"
      tone="warning"
      confirmLabel="수동으로 재개"
      busy={busy}
      confirmDisabled={!ready}
      onConfirm={() => void submit()}
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <div className={styles.autoForm}>
        <label className={styles.autoFormLabel} htmlFor="resume-reason">
          사유 · 감사 로그와 사건 기록에 남음
        </label>
        <input
          id="resume-reason"
          className={styles.autoReason}
          value={reason}
          maxLength={80}
          placeholder="예: 원인 확인 끝, 판단 다시 받음"
          onChange={(e) => setReason(e.target.value)}
        />
      </div>
    </ConfirmDialog>
  );
}

function AutoItems({ ai, now }: { ai: AiDesk; now: number }) {
  const until = ai.auto ? Date.parse(ai.auto.until) : now;
  const obs = ai.observation;
  const obsTotal = obs ? Date.parse(obs.until) - Date.parse(obs.startedAt) : 1;
  const obsDone = obs ? Math.min(1, Math.max(0, (now - Date.parse(obs.startedAt)) / obsTotal)) : 0;
  const cooldowns = ai.cooldowns.filter((c) => Date.parse(c.until) > now);
  return (
    <div className={styles.modeItems}>
      <span className={styles.modeItem}>
        <span className={styles.modeItemKey}>남은 시간</span>
        <b className="num" aria-hidden="true">{hmmss(until - now)}</b>
        <span className="sr-only">자동 모드 유효 시간 남음</span>
      </span>
      <span className={styles.modeSep} aria-hidden="true" />
      <span className={styles.modeItem}>
        <span className={styles.modeItemKey}>이번 자동 모드</span>
        <b className="num">
          {ai.autoUsed}/{ai.autoCap}
        </b>
      </span>
      <span className={styles.modeSep} aria-hidden="true" />
      <span className={styles.modeItem}>
        <span className={styles.modeItemKey}>쿨다운</span>
        {cooldowns.length === 0 ? (
          <span>없음</span>
        ) : (
          cooldowns.map((c) => (
            <span key={c.kind}>
              {AI_ACTION_LABEL[c.kind]} <b className="num">{mmss(Date.parse(c.until) - now)}</b>
            </span>
          ))
        )}
      </span>
      <span className={styles.modeSep} aria-hidden="true" />
      <span className={styles.observe}>
        <span className={styles.modeItemKey}>관찰 창</span>
        {obs ? (
          <>
            <span className={styles.observeTrack} aria-hidden="true">
              <span className={styles.observeFill} style={{ transform: `scaleX(${obsDone})` }} />
            </span>
            <b className="num">
              {mmss(now - Date.parse(obs.startedAt))}/{mmss(obsTotal)}
            </b>
          </>
        ) : (
          <span>없음</span>
        )}
      </span>
      {ai.auto && (
        <>
          <span className={styles.modeSep} aria-hidden="true" />
          <span className={styles.modeItem} title={ai.auto.reason}>
            <span className={styles.modeItemKey}>사유</span>
            <span className={styles.reasonText}>{ai.auto.reason}</span>
          </span>
        </>
      )}
    </div>
  );
}

export default function ModeBar({ center, onChanged }: { center: ControlCenter | null; onChanged: () => void }) {
  const now = useClock();
  const canSuper = useCan("SUPER");
  const [dialog, setDialog] = useState<"auto" | "resume" | null>(null);
  const [busy, setBusy] = useState(false);
  const section = center?.ai ?? null;
  const ai = section?.state === "READY" ? section.data : null;
  const connected = ai !== null;
  const auto = ai?.mode === "AUTO";
  const stopped = ai?.mode === "STOPPED";
  const pending = ai ? ai.proposals.filter((p) => p.status === "PROPOSED").length : 0;
  const limits = limitsOf(ai?.limits);

  const run = async (call: () => Promise<void>, done: string, failed: string) => {
    setBusy(true);
    try {
      await call();
      toast(done, "info");
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : failed, "error");
    } finally {
      setBusy(false);
    }
  };
  const toManual = () => run(() => getAdminApi().stopAiAuto(), "AI 자동 모드 꺼짐 · 수동", "수동 전환 실패");
  const stopAi = () => run(() => getAdminApi().stopAi(), "AI 정지 · 새 판단과 조치안 멈춤", "AI 정지 실패");

  return (
    <section className={styles.modeBar} data-mode={stopped ? "STOPPED" : auto ? "AUTO" : "MANUAL"} aria-label="AI 운영 모드">
      <span className={styles.modeLabel}>
        <AiMark />
        AI 운영 모드
      </span>
      <div className={styles.switch} role="group" aria-label="AI 운영 모드 선택">
        <button
          type="button"
          className={styles.switchBtn}
          aria-pressed={ai?.mode === "MANUAL"}
          disabled={!connected || busy || stopped}
          title={stopped ? "AI 정지 중 · 재개하면 수동" : undefined}
          onClick={() => {
            if (auto) void toManual();
          }}
        >
          수동
        </button>
        <button
          type="button"
          className={styles.switchBtn}
          data-auto=""
          aria-pressed={auto}
          disabled={!connected || !canSuper || busy || stopped}
          title={!connected ? "서버 정책 엔진 연결 전" : stopped ? "AI 정지 중 · 재개 뒤 켜기" : !canSuper ? "총괄 권한 필요" : undefined}
          onClick={() => {
            if (!auto) setDialog("auto");
          }}
        >
          자동
        </button>
      </div>
      {!connected ? (
        <span className={styles.modeText}>
          {section === null ? "모드 확인 중" : <><b>서버 정책 엔진 연결 전</b> · AI 판단, 자동 모드, AI 즉시 정지는 2단계에서 연결</>}
        </span>
      ) : stopped ? (
        <span className={styles.modeText} data-tone="warning">
          <b className={styles.stopWord}>
            <span aria-hidden="true">▲ </span>AI 정지 중
          </b>{" "}
          · 새 판단과 조치안 없음. 경보와 ASG 정책은 그대로
          {ai.stopped && (
            <>
              {" "}
              · 정지 {ai.stopped.by} <span className="num">{hms(ai.stopped.at)}</span>
            </>
          )}
          {ai.observation && " · 관찰 창은 끝까지 봄"}
        </span>
      ) : auto ? (
        <AutoItems ai={ai} now={now} />
      ) : (
        <span className={styles.modeText}>
          <b>수동</b> · AI는 판단과 조치안까지만 한다. 실행은 운영자 승인
          {pending > 0 && <> · 조치안 <b className="num">{pending}</b>건 대기</>}
        </span>
      )}
      <span className={styles.modeTail}>
        <RulesDisclosure limits={limits} />
        <Link href="/admin/ai#ai-limits" className={styles.stripLink}>
          AI 한도
        </Link>
        {stopped ? (
          <button
            type="button"
            className={adminStyles.neutralBtn}
            disabled={!canSuper || busy}
            title={canSuper ? undefined : "총괄 권한 필요"}
            onClick={() => setDialog("resume")}
          >
            AI 재개
          </button>
        ) : (
          <button
            type="button"
            className={adminStyles.dangerBtn}
            disabled={!connected || busy}
            title={connected ? "승인 없이 곧바로 멈춥니다" : "서버 정책 엔진 연결 전"}
            onClick={() => void stopAi()}
          >
            <span aria-hidden="true">■&nbsp;</span>AI 즉시 정지
          </button>
        )}
      </span>
      {dialog === "auto" && <AutoDialog limits={limits} onClose={() => setDialog(null)} onDone={onChanged} />}
      {dialog === "resume" && <ResumeDialog onClose={() => setDialog(null)} onDone={onChanged} />}
    </section>
  );
}
