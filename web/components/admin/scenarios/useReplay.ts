"use client";

/**
 * 시나리오 재생의 상태(관리자 설계 4-10-6). 고른 시나리오의 두 모드를 끝까지 한 번씩 돌려 타임라인을 만들고, 지금 초의 한 판을
 * 재생 세계에서 꺼낸다. 배속은 가상 시계의 속도이고 판의 계산은 콘솔 공용 박자(1초)에 맞춘다(배속 4배면 박자마다 네 틱). 탭이 숨으면
 * 박자가 멎어 재생도 멈춘다. 재생 계산이 예외로 멈추면 오류를 싣고 처음부터 다시 만들 수 있게 한다.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { onBeat } from "@/lib/admin/ticker";
import { ReplayWorld, replayStart, runTimeline, stageAt, type ReplayMode, type ReplayTimeline } from "@/lib/admin/scenarioWorld";
import type { AiJudgmentTrace, ControlCenter, ScenarioSpec } from "@/lib/admin/types";

export type ReplayView = ReplayMode | "COMPARE";
export type ReplaySpeed = 1 | 2 | 4;

export interface ReplayFrame {
  board: ControlCenter;
  hypothetical: Pick<AiJudgmentTrace, "candidates" | "checks"> | null;
}

export interface Replay {
  t0: number;
  timelines: Record<ReplayMode, ReplayTimeline> | null;
  sec: number;
  playing: boolean;
  speed: ReplaySpeed;
  view: ReplayView;
  /** 지금 초의 한 판. 비교 보기는 판 대신 타임라인의 판독을 쓴다 */
  frame: ReplayFrame | null;
  /** 마지막 변화가 재생 박자인가. 이때만 움직임을 건다(건너뛰기와 끌기는 곧바로 선다) */
  animate: boolean;
  error: string | null;
  /** 지금 모드(비교 보기는 수동)의 단계 번호 */
  stage: number;
  toggle: () => void;
  pause: () => void;
  seek: (sec: number) => void;
  stageStep: (dir: -1 | 1) => void;
  restart: () => void;
  setSpeed: (s: ReplaySpeed) => void;
  setView: (v: ReplayView) => void;
  retry: () => void;
}

export function useReplay(spec: ScenarioSpec, initialView: ReplayView = "MANUAL"): Replay {
  const [t0] = useState(() => replayStart(Date.now()));
  const [attempt, setAttempt] = useState(0);
  const [sec, setSec] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState<ReplaySpeed>(1);
  const [view, setView] = useState<ReplayView>(initialView);
  const [animate, setAnimate] = useState(false);
  const lastBeat = useRef(0);

  // 두 모드의 타임라인. 시나리오나 다시 시도가 바뀔 때만 만든다
  const built = useMemo(() => {
    try {
      return { timelines: { MANUAL: runTimeline(spec, "MANUAL", t0), AUTO: runTimeline(spec, "AUTO", t0) }, error: null };
    } catch (e) {
      return { timelines: null, error: e instanceof Error ? e.message : "재생 계산 실패" };
    }
    // attempt는 '처음부터 다시'가 같은 시나리오를 새로 만들게 하는 열쇠다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [spec, t0, attempt]);

  const worlds = useRef<{ key: string; w: Record<ReplayMode, ReplayWorld> } | null>(null);
  const key = `${spec.id}:${attempt}`;
  if (worlds.current === null || worlds.current.key !== key) {
    worlds.current = { key, w: { MANUAL: new ReplayWorld(spec, "MANUAL", t0), AUTO: new ReplayWorld(spec, "AUTO", t0) } };
  }

  // 시나리오가 바뀌면 처음으로 돌아가 멈춘다
  useEffect(() => {
    setSec(0);
    setPlaying(false);
    setAnimate(false);
  }, [spec.id]);

  const frameResult = useMemo((): { frame: ReplayFrame | null; error: string | null } => {
    if (view === "COMPARE" || built.timelines === null) return { frame: null, error: null };
    try {
      const w = (worlds.current as { w: Record<ReplayMode, ReplayWorld> }).w[view];
      w.seek(sec);
      return { frame: { board: w.board(), hypothetical: w.hypothetical() }, error: null };
    } catch (e) {
      return { frame: null, error: e instanceof Error ? e.message : "재생 계산 실패" };
    }
    // worlds는 key로 바뀌고 key는 spec과 attempt에서 나온다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, sec, key, built]);

  const duration = spec.durationSec;

  // 재생. 박자마다 배속만큼 가상 초를 밟고 끝에 닿으면 멈춘다. 첫 구독이 곧바로 뛰는 박자는 한 번 건너뛴다
  useEffect(() => {
    if (!playing) return;
    lastBeat.current = Date.now();
    return onBeat((now) => {
      if (now - lastBeat.current < 900) return;
      lastBeat.current = now;
      setAnimate(true);
      setSec((s) => {
        const next = Math.min(duration, s + speed);
        if (next >= duration) setPlaying(false);
        return next;
      });
    });
  }, [playing, speed, duration]);

  const seek = useCallback((s: number) => {
    setAnimate(false);
    setSec(Math.max(0, Math.min(duration, Math.round(s))));
  }, [duration]);

  const tl = built.timelines ? built.timelines[view === "AUTO" ? "AUTO" : "MANUAL"] : null;
  const stage = tl ? stageAt(tl.stageSecs, sec) : 0;

  const stageStep = useCallback((dir: -1 | 1) => {
    if (!tl) return;
    const secs = tl.stageSecs.map((s, i) => ({ s, i })).filter((x): x is { s: number; i: number } => x.s !== null);
    const cur = stageAt(tl.stageSecs, sec);
    const target = dir > 0 ? secs.find((x) => x.i > cur) : [...secs].reverse().find((x) => x.i < cur || (x.i === cur && x.s < sec));
    if (target) seek(target.s);
    else if (dir < 0) seek(0);
  }, [tl, sec, seek]);

  return {
    t0,
    timelines: built.timelines,
    sec,
    playing,
    speed,
    view,
    frame: frameResult.frame,
    animate: animate && playing,
    error: built.error ?? frameResult.error,
    stage,
    toggle: () => {
      if (!playing && sec >= duration) {
        setAnimate(false);
        setSec(0);
      }
      setPlaying((p) => !p);
    },
    pause: () => setPlaying(false),
    seek,
    stageStep,
    restart: () => {
      setPlaying(false);
      seek(0);
    },
    setSpeed,
    setView,
    retry: () => {
      setPlaying(false);
      setSec(0);
      setAttempt((n) => n + 1);
    },
  };
}
