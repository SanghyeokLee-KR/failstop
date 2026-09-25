"use client";

/**
 * AI 운영 모드 줄(디자인 시스템 §12-9, ADR 0035). 수동이 기본이다.
 *
 * 자동 켜기는 사유와 유효 시간을 받은 뒤 1인 승인 티켓을 발급·소각한다. 'AI 즉시 정지'와 'AI 재개'는 이 줄이
 * 아니라 운영자 바에 있다(AiStop). 정지하면 줄이 주의 면으로 바뀐다. 대기 조치안 수는 링크라 누르면 AI 관제의
 * 첫 대기 카드로 스크롤해 포커스를 옮긴다. 자동인 동안 줄 테두리만 AI 강조색이 되고 남은 시간, 이번 자동 모드의
 * 조치 수, 쿨다운, 관찰 창을 숫자 먼저 싣는다. 켠 사유는 정보 표지의 툴팁으로 옮겼다. 자동 되돌림, AI 정지와
 * 재개, 자동 상한 도달, 유효 시간 만료 뒤 30초 동안은 뜻 글자 자리에 그 사건을 한 줄로 적는다(알림 줄, §12-9 v1.14).
 * 알림과 그 끝은 관제 판의 notice가 정한다. 줄 높이는 모드와 무관하게 같아 아래 판이 움직이지 않는다.
 */

import { memo, useState } from "react";
import Link from "next/link";
import { getAdminApi, AdminError } from "@/lib/admin/api";
import { AI_ACTION_LABEL, AI_FORBIDDEN, allowedRules, AUTO_HOURS, limitsOf, type AiLimits } from "@/lib/admin/aiPolicy";
import { openWindows, windowRemainWord, windowTargetWord } from "@/lib/admin/changes";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { useOpsPick } from "@/lib/admin/opsCenter";
import { useCan } from "@/lib/admin/store";
import type { AiDesk, AiNotice, OpsSection } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import ConfirmDialog from "../ConfirmDialog";
import adminStyles from "../admin.module.css";
import { prefersReducedMotion } from "@/lib/reducedMotion";
import { AdminIcon } from "../icons";
import StatusShape from "../StatusShape";
import { AllowedList, ConditionList, ForbiddenList } from "./AiRules";
import { AiMark, hms, mmss, remainWord, useClock } from "./parts";
import styles from "./control.module.css";

/** 허용·금지 목록 펼침. 모드 줄과 AI 패널 바닥이 같은 목록을 연다 */
export function RulesDisclosure({ label = "허용·금지 목록", up = false, limits }: { label?: string; up?: boolean; limits?: AiLimits }) {
  return (
    <details className={styles.rules}>
      <summary>{label}</summary>
      <div className={styles.rulesPanel} data-up={up ? "" : undefined}>
        <span className={styles.rulesTitle}>허용 목록(되돌릴 수 있고 데이터와 무관한 조치)</span>
        <AllowedList limits={limits} />
        <span className={styles.rulesTitle}>금지 목록(어느 모드에서도 AI가 하지 않음)</span>
        <ForbiddenList />
        <span className={styles.rulesTitle}>자동 실행 조건</span>
        <ConditionList limits={limits} />
      </div>
    </details>
  );
}

const AI_ACTION_KINDS = allowedRules().length;

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
      description="허용 목록 조치를 AI가 한도 안에서 자동으로 실행합니다. 켜면 1인 승인 티켓을 발급하고 곧바로 소각해 승인 원장과 감사 로그에 남깁니다."
      target="AI 운영 · 허용 목록 5종"
      blastRadius={`${hours}시간 뒤 수동 복귀 · 이번 자동 모드 ${limits.autoCap}회 상한`}
      tone="approve"
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
        {/* 두 목록은 접어 둔다. 펼쳐 두면 1280x800 창에서 대화상자가 창보다 길어진다. 대상 줄이 '허용 목록 5종'을 적는다 */}
        <details className={styles.autoRulesFold}>
          <summary>
            {/* 요약 줄은 한 덩이 글이다. 줄이 플렉스라 숫자를 따로 감싸면 '5 종'처럼 칸이 벌어진다 */}
            <span>{`자동으로 하는 것 ${AI_ACTION_KINDS}종과 하지 않는 것 ${AI_FORBIDDEN.length}종`}</span>
          </summary>
          <div className={styles.autoRules}>
            <div>
              <span className={styles.rulesTitle}>자동으로 하는 것</span>
              <AllowedList limits={limits} />
            </div>
            <div>
              <span className={styles.rulesTitle}>어느 모드에서도 하지 않는 것</span>
              <ForbiddenList />
            </div>
          </div>
        </details>
        <p className={styles.autoNote}>
          자동 조치 수는 켤 때마다 0부터 셉니다. 쿨다운과 관찰 창은 그대로 남습니다. 악화하면 자동으로 되돌리고
          수동으로 바뀝니다. AI 즉시 정지는 승인 없이 언제든 누를 수 있습니다.
        </p>
      </div>
    </ConfirmDialog>
  );
}

/** 모드 줄의 id. 승인한 조치의 결과(알림 줄)가 서는 자리라 승인 뒤 포커스가 이리 온다 */
const MODE_BAR_ID = "ai-mode-bar";
/** 본문 스크롤 상자 맨 위에 붙어 있는 운영자 바의 높이(레이아웃 §7) */
const OPERATOR_BAR_H = 56;

/**
 * 승인한 조치의 결과가 서는 모드 줄을 첫 화면에 올리고 포커스를 옮긴다(관리자 설계 4-4 v1.20). 모드 줄이 운영자 바 아래에 보이면
 * 스크롤하지 않는다. 대화상자가 연 단추로 돌려준 포커스는 카드가 처리된 조치로 옮겨 가면 사라지므로 두 프레임 뒤에 옮긴다
 */
export function revealModeBar(): void {
  requestAnimationFrame(() =>
    requestAnimationFrame(() => {
      const bar = document.getElementById(MODE_BAR_ID);
      const main = document.getElementById("admin-main");
      if (!bar || !main) return;
      if (bar.getBoundingClientRect().top < main.getBoundingClientRect().top + OPERATOR_BAR_H) {
        main.scrollTo({ top: 0, behavior: prefersReducedMotion() ? "auto" : "smooth" });
      }
      bar.focus({ preventScroll: true });
    }),
  );
}

/**
 * 대기 조치안 링크. 누르면 AI 관제의 첫 대기 카드(ai-proposal-1)로 스크롤하고 그 카드에 포커스를 옮긴다. 조치안이
 * 기둥 안쪽 스크롤이나 본문 아래에 있어도 '승인하고 실행'까지 한 번에 닿게 한다
 */
export function PendingLink({ count }: { count: number }) {
  if (count === 0) return null;
  return (
    <a
      href="#ai-proposal-1"
      className={styles.pendingLink}
      onClick={(e) => {
        const el = document.getElementById("ai-proposal-1");
        if (!el) return;
        e.preventDefault();
        el.scrollIntoView({ block: "center", behavior: prefersReducedMotion() ? "auto" : "smooth" });
        el.focus({ preventScroll: true });
      }}
    >
      대기 조치안 <b className="num">{count}</b>건
    </a>
  );
}

/** 자동 모드의 남은 시간·쿨다운·관찰 창. 매초 바뀌는 숫자라 이 조각만 시계를 탄다 */
function AutoItems({ ai, pending }: { ai: AiDesk; pending: number }) {
  const now = useClock();
  const until = ai.auto ? Date.parse(ai.auto.until) : now;
  const obs = ai.observation;
  const obsTotal = obs ? Date.parse(obs.until) - Date.parse(obs.startedAt) : 1;
  const obsDone = obs ? Math.min(1, Math.max(0, (now - Date.parse(obs.startedAt)) / obsTotal)) : 0;
  const cooldowns = ai.cooldowns.filter((c) => Date.parse(c.until) > now);
  return (
    <div className={styles.modeItems}>
      <span className={styles.modeItem}>
        <span className={styles.modeItemKey}>남은 시간</span>
        <b className="num" aria-hidden="true">{remainWord(until - now)}</b>
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
      <span className={styles.modeItem} data-shrink="">
        <span className={styles.modeItemKey}>쿨다운</span>
        {cooldowns.length === 0 ? (
          <span>없음</span>
        ) : (
          cooldowns.map((c) => (
            <span key={c.kind} className={styles.nowrap}>
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
      {pending > 0 && (
        <>
          <span className={styles.modeSep} aria-hidden="true" />
          <PendingLink count={pending} />
        </>
      )}
      {ai.auto && (
        <span className={styles.reasonMark} title={`켠 사유 · ${ai.auto.reason}`} aria-label={`켠 사유 ${ai.auto.reason}`} role="img">
          <AdminIcon name="info" size={14} />
        </span>
      )}
    </div>
  );
}

/**
 * 알림 줄(§12-9 v1.14). 되돌림과 정지는 주의, 재개·상한·만료는 무채다. 사건 기록이 있으면 그 사건을 여는 링크를 둔다
 */
function NoticeLine({ n, pending }: { n: AiNotice; pending: number }) {
  const incidentId = n.incidentId;
  return (
    <span className={styles.noticeLine} data-tone={n.tone} role="status">
      <StatusShape kind={n.tone === "warning" ? "triangle" : "circle"} tone={n.tone === "warning" ? "warning" : "off"} className={styles.shape} />
      <b className="num">{hms(n.at)}</b>
      <span className={styles.noticeText} title={n.text}>
        {n.text}
      </span>
      {incidentId && (
        <button type="button" className={styles.noticeLink} aria-haspopup="dialog" onClick={() => useOpsDetail.getState().openIncident(incidentId)}>
          사건 기록
        </button>
      )}
      <PendingLink count={pending} />
    </span>
  );
}

/**
 * 진행 중인 계획 작업 창(관리자 설계 4-10-4). 창 안에서만 AI가 주의 경보를 보류할 수 있어 모드 줄 끝에 남은 시간을 적는다.
 * 변경·작업 화면과 사이드바 배지와 같은 판정(openWindows)이다. 남은 시간이 매초 바뀌므로 이 조각만 시계를 탄다
 */
function WindowLink() {
  const windows = useOpsPick((c) => (c?.changes.state === "READY" ? c.changes.data.windows : null));
  const now = useClock();
  const open = windows ? openWindows(windows, now) : [];
  if (open.length === 0) return null;
  const w = open[0];
  return (
    <Link href="/admin/changes" className={`${styles.stripLink} ${styles.windowLink}`} title={`${w.id} · ${windowTargetWord(w)}`}>
      <StatusShape kind="play" size={8} />
      계획 작업 창{open.length > 1 ? ` ${open.length}개` : ""} · <span className="num">{windowRemainWord(Date.parse(w.endsAt) - now)}</span> 남음
    </Link>
  );
}

/** AI 칸만 받는다. 모드 줄은 매초 다시 그리지 않고, 자동 모드의 시계 글자만 따로 돈다 */
function ModeBar({ section, onChanged }: { section: OpsSection<AiDesk> | null; onChanged: () => void }) {
  const canSuper = useCan("SUPER");
  const [dialog, setDialog] = useState<"auto" | null>(null);
  const [busy, setBusy] = useState(false);
  const ai = section?.state === "READY" ? section.data : null;
  const connected = ai !== null;
  const auto = ai?.mode === "AUTO";
  const stopped = ai?.mode === "STOPPED";
  const pending = ai ? ai.proposals.filter((p) => p.status === "PROPOSED").length : 0;
  const limits = limitsOf(ai?.limits);

  const toManual = async () => {
    setBusy(true);
    try {
      await getAdminApi().stopAiAuto();
      toast("AI 자동 모드 꺼짐 · 수동", "info");
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "수동 전환 실패", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section id={MODE_BAR_ID} tabIndex={-1} className={styles.modeBar} data-mode={stopped ? "STOPPED" : auto ? "AUTO" : "MANUAL"} aria-label="AI 운영 모드">
      <span className={styles.modeLabel}>
        <AiMark />
        <span className={styles.modeLabelText}>AI 운영 모드</span>
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
          title={!connected ? "관제센터 서버 없음(관제 모듈 꺼짐)" : stopped ? "AI 정지 중 · 재개 뒤 켜기" : !canSuper ? "총괄 권한 필요" : undefined}
          onClick={() => {
            if (!auto) setDialog("auto");
          }}
        >
          자동
        </button>
      </div>
      {!connected ? (
        <span className={styles.modeText}>
          {section === null ? "모드 확인 중" : <><b>관제센터 서버 없음</b> · AI 판단과 자동 모드, AI 즉시 정지는 관제 모듈을 켠 관리 API에서만 씁니다</>}
        </span>
      ) : auto ? (
        <AutoItems ai={ai} pending={pending} />
      ) : ai.notice ? (
        <NoticeLine key={ai.notice.at} n={ai.notice} pending={pending} />
      ) : stopped ? (
        <span className={styles.modeText} data-tone="warning" title="새 판단과 조치안을 내지 않습니다. 경보와 ASG 정책은 AI 없이 그대로 동작합니다">
          <b className={styles.stopWord}>
            <span aria-hidden="true"><StatusShape kind="triangle" /> </span>AI 정지 중
          </b>
          {ai.stopped && (
            <>
              {" "}
              · {ai.stopped.by} <span className="num">{hms(ai.stopped.at)}</span> 정지
            </>
          )}
          {ai.observation && " · 관찰 창은 끝까지 봅니다"}
          {" · 경보와 ASG 정책은 그대로 동작합니다"}
        </span>
      ) : (
        <span className={styles.modeLine}>
          <span className={styles.modeText}>
            <b>수동</b> · AI는 판단과 조치안까지만 내고 실행은 운영자가 승인합니다
          </span>
          <PendingLink count={pending} />
        </span>
      )}
      <span className={styles.modeTail}>
        <WindowLink />
        <RulesDisclosure limits={limits} />
        <Link href="/admin/ai#ai-limits" className={styles.stripLink}>
          AI 한도
        </Link>
      </span>
      {dialog === "auto" && <AutoDialog limits={limits} onClose={() => setDialog(null)} onDone={onChanged} />}
    </section>
  );
}

export default memo(ModeBar);
