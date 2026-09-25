"use client";

/**
 * 관리 콘솔 전체가 나눠 쓰는 1초 박자. 시계 글자, 남은 시간, 관제·승인 폴러, sim 틱이 모두 이 박자
 * 하나를 탄다. 화면마다 setInterval을 두면 타이머가 화면 수만큼 늘고 박자가 어긋나 같은 초에 여러 번
 * 다시 그린다.
 *
 * 탭이 숨으면 박자를 멈춘다. 숨은 탭의 폴링과 다시 그리기는 아무도 보지 않는 일이다. 다시 보이면
 * 곧바로 한 번 뛰어 폴러가 밀린 갱신을 바로 한다. 박자는 초 경계 바로 뒤에 뛰어 시계 글자가 벽시계와
 * 같은 순간에 바뀐다.
 */

import { useSyncExternalStore } from "react";

type Beat = (now: number) => void;

const beats = new Set<Beat>();
let timer: ReturnType<typeof setTimeout> | null = null;
let last = Date.now();

function hidden(): boolean {
  return typeof document !== "undefined" && document.visibilityState === "hidden";
}

function beat(): void {
  timer = null;
  last = Date.now();
  for (const b of [...beats]) b(last);
  // 다음 초 경계 바로 뒤로 다시 건다. 타이머가 늦게 깨도 늦은 만큼이 다음 박자에 쌓이지 않는다
  if (beats.size > 0 && !hidden()) timer = setTimeout(beat, 1000 - (Date.now() % 1000) + 4);
}

function sync(): void {
  const run = beats.size > 0 && !hidden();
  if (run && timer === null) beat();
  else if (!run && timer !== null) {
    clearTimeout(timer);
    timer = null;
  }
}

if (typeof document !== "undefined") document.addEventListener("visibilitychange", sync);

/** 박자를 구독한다. 첫 구독이거나 탭이 다시 보이면 곧바로 한 번 뛴다 */
export function onBeat(b: Beat): () => void {
  beats.add(b);
  sync();
  return () => {
    beats.delete(b);
    sync();
  };
}

/** 시험이 박자 상태를 이 경계로 본다 */
export function beatRunning(): boolean {
  return timer !== null;
}

const subscribe = (notify: () => void) => onBeat(notify);

/**
 * 박자에 맞춰 다시 그리는 시각. periodMs를 주면 그 간격마다 한 번만 바뀐다. 남은 시간과 'n초 전'처럼
 * 벽시계로 읽는 글자만 쓰고, 이 값을 넓은 화면 위에서 받아 아래로 내리지 않는다. 받은 곳이 매초 통째로
 * 다시 그려진다
 */
export function useBeat(periodMs = 1000): number {
  const snap = () => Math.floor(last / periodMs) * periodMs;
  return useSyncExternalStore(subscribe, snap, snap);
}
