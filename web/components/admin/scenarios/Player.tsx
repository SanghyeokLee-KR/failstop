"use client";

/**
 * 시나리오 재생 판(관리자 설계 4-10-6, 디자인 시스템 §12-15 시나리오 재생 판). 위에서부터 머리(제목, 가정 표식, 전제 칩), 조작 줄(재생·
 * 일시정지, 처음으로, 이전·다음 단계, 배속, 빈 구간, 모드, 관제센터에서 실시간으로 걸기), 타임라인 스크럽 바, 단계 막대, 네 칸이다.
 * 비교 보기에서는 두 줄의 스크럽 바와 결과 표가 네 칸 자리를 쓴다.
 *
 * 조작은 Space 재생·일시정지, ←·→ 1초, Shift+←·→ 10초, PageUp·PageDown 단계, Home 처음이다. 스크럽 바에 초점이 있으면 바가 받고,
 * 판의 다른 자리(단추·입력 밖)에서도 같은 건반이 듣는다. 관제센터에서 실시간으로 걸기는 관제 판에 걸 수 있는 시나리오(조회 급증
 * 대응, 야간 축소 뒤 악화)만 sim 원천에서 연다. http 원천에서도 재생은 되지만 실시간으로 걸기는 비활성이다.
 */

import { useState, type KeyboardEvent } from "react";
import Link from "next/link";
import { AdminError, getAdminApi, isAdminHttp } from "@/lib/admin/api";
import type { StageMark } from "@/lib/admin/opsDetail";
import { stageAt } from "@/lib/admin/scenarioWorld";
import { durationWord, mmss } from "@/lib/admin/timeText";
import type { ScenarioSpec } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import ConfirmDialog from "../ConfirmDialog";
import { AdminIcon, type AdminIconName } from "../icons";
import ScrubBar from "../ScrubBar";
import StageBar from "../StageBar";
import { Segmented, SimMark } from "../ui";
import adminStyles from "../admin.module.css";
import ComparePanel from "./ComparePanel";
import { AiPanel, OperatorPanel, PolicyPanel, StatePanel } from "./ReplayPanels";
import { useReplay, type ReplaySpeed, type ReplayView } from "./useReplay";
import styles from "./scenarios.module.css";

const VIEWS: { value: ReplayView; label: string }[] = [
  { value: "MANUAL", label: "수동" },
  { value: "AUTO", label: "자동" },
  { value: "COMPARE", label: "비교" },
];
const SPEEDS: { value: `${ReplaySpeed}`; label: string }[] = [
  { value: "1", label: "1배" },
  { value: "2", label: "2배" },
  { value: "4", label: "4배" },
];
const QUIET: { value: "skip" | "watch"; label: string }[] = [
  { value: "skip", label: "건너뛰기" },
  { value: "watch", label: "보기" },
];

/** 빈 구간을 넘었을 때 보조기술에 알리는 글. 길이 글은 '초'나 '분'으로 끝난다 */
function skipNotice(sec: number): string {
  const word = durationWord(sec * 1000);
  return `빈 구간 ${word}${word.endsWith("초") ? "를" : "을"} 건너뛰었습니다`;
}

function IconBtn({ icon, label, onClick, disabled }: { icon: AdminIconName; label: string; onClick: () => void; disabled?: boolean }) {
  return (
    <button type="button" className={styles.iconBtn} onClick={onClick} disabled={disabled} aria-label={label} title={label}>
      <AdminIcon name={icon} size={16} />
    </button>
  );
}

export default function Player({ spec, initialView, onView }: { spec: ScenarioSpec; initialView: ReplayView; onView: (v: ReplayView) => void }) {
  const r = useReplay(spec, initialView);
  const [confirmLive, setConfirmLive] = useState(false);
  const [busy, setBusy] = useState(false);
  const [liveStarted, setLiveStarted] = useState(false);
  const tl = r.timelines ? r.timelines[r.view === "AUTO" ? "AUTO" : "MANUAL"] : null;
  const http = isAdminHttp();

  const valueText = (s: number) => {
    if (!tl) return mmss(s * 1000);
    const i = stageAt(tl.stageSecs, s);
    return `${mmss(s * 1000)} · ${i + 1}단계 ${spec.stages[i].title}`;
  };

  const marks: StageMark[] = tl
    ? spec.stages.map((st, i) => {
        const at = tl.stageSecs[i];
        const passed = at !== null && at <= r.sec;
        const reachedLater = tl.stageSecs.slice(i + 1).some((x) => x !== null && x <= r.sec);
        const state: StageMark["state"] = i === r.stage ? "current" : passed ? "done" : at === null && reachedLater ? "skipped" : "todo";
        return { key: st.key, label: st.title, state, at: null, note: at === null ? "이 모드에 없음" : mmss(at * 1000), ai: st.key === "judge", marks: [] };
      })
    : [];

  const onKey = (e: KeyboardEvent<HTMLElement>) => {
    const t = e.target as HTMLElement;
    if (t.closest("button, a, input, select, textarea, [role=slider], [role=tab]")) return;
    if (e.key === " ") {
      e.preventDefault();
      r.toggle();
    } else if (e.key === "ArrowRight" || e.key === "ArrowLeft") {
      e.preventDefault();
      r.seek(r.sec + (e.key === "ArrowRight" ? 1 : -1) * (e.shiftKey ? 10 : 1));
    } else if (e.key === "PageUp" || e.key === "PageDown") {
      e.preventDefault();
      r.stageStep(e.key === "PageUp" ? -1 : 1);
    } else if (e.key === "Home") {
      e.preventDefault();
      r.restart();
    }
  };

  const startLive = async () => {
    if (!spec.live) return;
    setBusy(true);
    try {
      await getAdminApi().startOpsScenario(spec.live);
      toast(`관제센터에서 실시간으로 거는 중 · ${spec.title}`, "info");
      setLiveStarted(true);
      setConfirmLive(false);
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "실시간으로 걸지 못했습니다", "error");
    } finally {
      setBusy(false);
    }
  };

  const setView = (v: ReplayView) => {
    r.setView(v);
    onView(v);
  };
  const assumption = spec.assumption === "PATRONI_APPLIED" ? "patroni" : spec.assumption === "AWS_ASG_POLICY" ? "asgPolicy" : null;

  return (
    <section className={styles.player} aria-labelledby="sc-title" onKeyDown={onKey}>
      <div className={styles.console}>
      <header className={styles.playerHead}>
        <div className={styles.playerTitleRow}>
          <SimMark kind="scenario" />
          <h2 id="sc-title" className={styles.playerTitle}>
            {spec.title}
          </h2>
          {assumption && <SimMark kind={assumption} />}
        </div>
        <p className={styles.question}>{spec.question}</p>
        <ul className={styles.premise} aria-label="전제">
          {spec.premise.map((p) => (
            <li key={p} className={styles.premiseChip}>
              {p}
            </li>
          ))}
        </ul>
      </header>

      <div className={styles.controls} role="group" aria-label="재생 조작">
        <button type="button" className={styles.playBtn} onClick={r.toggle} aria-pressed={r.playing} disabled={r.error !== null}>
          <AdminIcon name={r.playing ? "pause" : "play"} size={16} />
          {r.playing ? "일시정지" : r.sec >= spec.durationSec ? "다시 재생" : "재생"}
        </button>
        <IconBtn icon="restart" label="처음으로" onClick={r.restart} disabled={r.error !== null} />
        <IconBtn icon="stepBack" label="이전 단계" onClick={() => r.stageStep(-1)} disabled={r.error !== null} />
        <IconBtn icon="stepForward" label="다음 단계" onClick={() => r.stageStep(1)} disabled={r.error !== null} />
        <span className={`${styles.clock} num`} aria-live="off">
          {mmss(r.sec * 1000)}
          <span className={styles.clockTotal}> / {mmss(spec.durationSec * 1000)}</span>
        </span>
        <span className="sr-only" aria-live="polite">
          {r.skipped ? skipNotice(r.skipped.sec) : ""}
        </span>
        <span className={styles.controlSep} aria-hidden="true" />
        {/* 이름과 조작은 한 묶음으로 줄을 바꾼다. 좁은 창에서 이름만 앞 줄에 남지 않게 한다 */}
        <span className={styles.controlGroup}>
          <span className={styles.controlLabel} aria-hidden="true">
            배속
          </span>
          <Segmented items={SPEEDS} value={`${r.speed}`} onChange={(v) => r.setSpeed(Number(v) as ReplaySpeed)} ariaLabel="배속" semantics="pressed" size="xs" />
        </span>
        <span className={styles.controlGroup}>
          <span className={styles.controlLabel} aria-hidden="true">
            빈 구간
          </span>
          <Segmented items={QUIET} value={r.skipQuiet ? "skip" : "watch"} onChange={(v) => r.setSkipQuiet(v === "skip")} ariaLabel="빈 구간" semantics="pressed" size="xs" />
        </span>
        <span className={styles.controlGroup}>
          <span className={styles.controlLabel} aria-hidden="true">
            모드
          </span>
          <Segmented items={VIEWS} value={r.view} onChange={setView} ariaLabel="모드" semantics="pressed" size="xs" />
        </span>
        <span className={styles.liveSlot}>
          {spec.live === null ? null : http ? (
            <span className={styles.liveOff} title="http 원천에서는 관제 판에 시나리오를 걸지 않습니다">
              실시간으로 걸기 · http 원천 없음
            </span>
          ) : liveStarted ? (
            <Link href="/admin" className={styles.textLink}>
              관제센터에서 보기
            </Link>
          ) : (
            <button type="button" className={styles.textBtn} onClick={() => setConfirmLive(true)} aria-haspopup="dialog">
              관제센터에서 실시간으로 걸기
            </button>
          )}
        </span>
      </div>

      {r.view !== "COMPARE" && r.error === null && r.timelines !== null && (
        <>
          <ScrubBar
            duration={spec.durationSec}
            value={r.sec}
            onSeek={r.seek}
            onToggle={r.toggle}
            onStage={r.stageStep}
            stages={spec.stages.map((st, i) => ({ sec: tl?.stageSecs[i] ?? null, title: st.title }))}
            markers={tl?.markers ?? []}
            quiet={r.skipQuiet ? r.quiet : []}
            label="재생 위치"
            valueText={valueText}
            animate={r.animate}
          />
          <div className={styles.stages}>
            <StageBar items={marks} label="시나리오 단계" renderWhen={(m) => m.note} />
          </div>
          <p className={styles.stageNow} aria-live="polite">
            <span className={`${styles.stageNo} num`}>
              {r.stage + 1}/{spec.stages.length}
            </span>
            <b>{spec.stages[r.stage].title}</b>
            <span>{spec.stages[r.stage].expect}</span>
          </p>
        </>
      )}
      </div>

      {r.error !== null || r.timelines === null ? (
        <div className={styles.failed} role="alert">
          <span className={styles.failedTitle}>
            <span aria-hidden="true">■ </span>재생 계산이 멈췄습니다
          </span>
          <span className={styles.failedText}>{r.error ?? "재생 계산 실패"}</span>
          <button type="button" className={adminStyles.neutralBtn} onClick={r.retry}>
            처음부터 다시
          </button>
        </div>
      ) : r.view === "COMPARE" ? (
        <ComparePanel
          spec={spec}
          timelines={r.timelines}
          sec={r.sec}
          onSeek={r.seek}
          onToggle={r.toggle}
          onStage={r.stageStep}
          animate={r.animate}
          valueText={valueText}
          quiet={r.skipQuiet ? r.quiet : []}
        />
      ) : (
        r.frame &&
        tl && (
          <div className={styles.grid}>
            <StatePanel board={r.frame.board} spec={spec} animate={r.animate} />
            <AiPanel board={r.frame.board} t0={r.t0} animate={r.animate} />
            <PolicyPanel board={r.frame.board} spec={spec} t0={r.t0} hypothetical={r.frame.hypothetical} animate={r.animate} />
            <OperatorPanel spec={spec} timeline={tl} sec={r.sec} t0={r.t0} animate={r.animate} />
          </div>
        )
      )}

      {spec.caveats.length > 0 && (
        <ul className={styles.caveats} aria-label="재생의 한계">
          {spec.caveats.map((c) => (
            <li key={c}>{c}</li>
          ))}
        </ul>
      )}

      {confirmLive && spec.live && (
        <ConfirmDialog
          title="관제센터에서 실시간으로 걸기"
          description={`${spec.title} 시나리오를 관제 판에 겁니다. 관제센터와 모든 운영 관제 화면의 값이 시나리오대로 움직입니다. 초기화는 관제센터 머리의 시나리오 조작에서 합니다.`}
          target="관제 판(sim)"
          blastRadius="모든 운영 관제 화면의 sim 값 · 감사 로그에 남음"
          tone="warning"
          confirmLabel="걸기"
          busy={busy}
          onConfirm={() => void startLive()}
          onClose={() => {
            if (!busy) setConfirmLive(false);
          }}
        />
      )}
    </section>
  );
}
