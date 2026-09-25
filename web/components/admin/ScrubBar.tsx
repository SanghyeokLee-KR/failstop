"use client";

/**
 * 타임라인 스크럽 바(디자인 시스템 §12-15 ScrubBar). 시나리오 재생의 가상 시계를 옮긴다. 판 전폭이고 누르는 자리는 44px, 트랙은
 * 4px이다. 단계 경계는 1px x 10px 눈금과 단계 번호, 기록 표지는 사건 기록 레일의 문법(경보 발생 마름모, AI 점, 운영자 점, 시스템 빈
 * 원, 해제 원)이다. 재생 위치는 14px 원 손잡이와 그 위의 mm:ss다.
 *
 * 슬라이더 의미(role=slider)다. ←·→ 1초, Shift+←·→ 10초, PageUp·PageDown 단계, Home·End 처음과 끝, Space 재생·일시정지다. 끄는
 * 동안에는 한 프레임에 한 번만 옮긴다. 재생 중 손잡이와 지나온 몫은 박자마다 transform으로 옮기고(배속에 맞춘 1초 선형), 끌거나
 * 건너뛸 때와 움직임 줄이기에서는 곧바로 선다. 가리키면 그 시각과 가장 가까운 기록 한 줄이 떠 있는 층으로 선다.
 */

import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { mmss } from "@/lib/admin/timeText";
import type { MarkerKind, ReplayMarker } from "@/lib/admin/scenarioWorld";
import styles from "./ScrubBar.module.css";

export interface ScrubStage {
  sec: number | null;
  title: string;
}

const MARK_WORD: Record<MarkerKind, string> = { OPEN: "경보 발생", AI: "AI", OPERATOR: "운영자", SYSTEM: "시스템", RESOLVE: "해제" };

export default function ScrubBar({
  duration,
  value,
  onSeek,
  onToggle,
  onStage,
  stages,
  markers,
  label,
  valueText,
  animate,
  lane,
}: {
  duration: number;
  value: number;
  /** 새 초. 끌기·누르기·건반이 모두 이 길로 온다 */
  onSeek: (sec: number) => void;
  /** Space. 재생과 일시정지 */
  onToggle?: () => void;
  /** PageUp·PageDown. -1은 앞 단계, 1은 다음 단계 */
  onStage?: (dir: -1 | 1) => void;
  stages: ScrubStage[];
  markers: ReplayMarker[];
  label: string;
  valueText: (sec: number) => string;
  /** 재생 박자로 옮기는 중인가. 이때만 손잡이가 1초 선형으로 미끄러진다 */
  animate: boolean;
  /** 비교 보기의 줄 이름(수동, 자동). 주면 줄 머리에 적는다 */
  lane?: string;
}) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  const [hover, setHover] = useState<{ x: number; sec: number } | null>(null);
  const drag = useRef(false);
  const frame = useRef<number | null>(null);
  const pending = useRef<number | null>(null);

  // 손잡이는 left가 아니라 transform으로 옮긴다(§12-4 움직이는 속성). 그래서 트랙 폭을 재 둔다
  useLayoutEffect(() => {
    const el = trackRef.current;
    if (!el) return;
    const measure = () => setWidth(el.getBoundingClientRect().width);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  useEffect(
    () => () => {
      if (frame.current !== null) cancelAnimationFrame(frame.current);
    },
    [],
  );

  const secAt = (clientX: number) => {
    const el = trackRef.current;
    if (!el) return 0;
    const r = el.getBoundingClientRect();
    const p = Math.min(1, Math.max(0, (clientX - r.left) / Math.max(1, r.width)));
    return Math.round(p * duration);
  };

  /** 끄는 동안에는 한 프레임에 한 번만 판을 다시 계산한다 */
  const seekSoon = (sec: number) => {
    pending.current = sec;
    if (frame.current !== null) return;
    frame.current = requestAnimationFrame(() => {
      frame.current = null;
      if (pending.current !== null) onSeek(pending.current);
      pending.current = null;
    });
  };

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    drag.current = true;
    e.currentTarget.setPointerCapture(e.pointerId);
    e.currentTarget.focus();
    seekSoon(secAt(e.clientX));
  };
  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    const r = trackRef.current?.getBoundingClientRect();
    if (r) setHover({ x: Math.min(r.width, Math.max(0, e.clientX - r.left)), sec: secAt(e.clientX) });
    if (drag.current) seekSoon(secAt(e.clientX));
  };
  const onPointerUp = (e: PointerEvent<HTMLDivElement>) => {
    drag.current = false;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? 10 : 1;
    let next: number | null = null;
    if (e.key === "ArrowRight" || e.key === "ArrowUp") next = value + step;
    else if (e.key === "ArrowLeft" || e.key === "ArrowDown") next = value - step;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = duration;
    else if (e.key === "PageUp" || e.key === "PageDown") {
      e.preventDefault();
      onStage?.(e.key === "PageUp" ? -1 : 1);
      return;
    } else if (e.key === " " || e.key === "Spacebar") {
      e.preventDefault();
      onToggle?.();
      return;
    } else return;
    e.preventDefault();
    onSeek(Math.min(duration, Math.max(0, next)));
  };

  const p = duration > 0 ? value / duration : 0;
  const near = hover === null ? null : markers.reduce<ReplayMarker | null>((a, m) => (Math.abs(m.sec - hover.sec) <= Math.max(2, duration / 80) && (a === null || Math.abs(m.sec - hover.sec) < Math.abs(a.sec - hover.sec)) ? m : a), null);
  // 손잡이 위 시각 글자가 트랙 밖으로 나가지 않게 양끝에서는 안쪽으로 붙인다
  const edge = p < 0.04 ? "start" : p > 0.96 ? "end" : "mid";
  // 단계 번호. 트랙에서 16px 안에 붙은 번호는 앞 자리에 모아 둘이면 '2·3', 셋 이상이면 '2~4'로 적고, 손잡이 시각 글자가 덮는
  // 자리의 번호는 비운다(지금 단계는 아래 단계 막대가 적는다)
  const hx = p * width;
  const [cover0, cover1] = edge === "start" ? [hx - 14, hx + 50] : edge === "end" ? [hx - 50, hx + 14] : [hx - 30, hx + 30];
  const numbers: { x: number; from: number; to: number; passed: boolean }[] = [];
  stages.forEach((s, i) => {
    if (s.sec === null) return;
    const x = (s.sec / Math.max(1, duration)) * width;
    const last = numbers[numbers.length - 1];
    if (last && x - last.x < 16) last.to = i + 1;
    else numbers.push({ x, from: i + 1, to: i + 1, passed: s.sec <= value });
  });

  return (
    <div className={styles.wrap} data-lane={lane ? "" : undefined}>
      {lane && <span className={styles.lane}>{lane}</span>}
      <div
        className={styles.bar}
        role="slider"
        tabIndex={0}
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={duration}
        aria-valuenow={value}
        aria-valuetext={valueText(value)}
        onKeyDown={onKeyDown}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onPointerLeave={() => setHover(null)}
        data-animate={animate ? "" : undefined}
      >
        <div className={styles.track} ref={trackRef}>
          <span className={styles.fill} style={{ transform: `scaleX(${p})` }} aria-hidden="true" />
          {stages.map((s, i) =>
            s.sec === null ? null : (
              <span key={`${i}-${s.title}`} className={styles.tick} style={{ left: `${(s.sec / Math.max(1, duration)) * 100}%` }} aria-hidden="true" />
            ),
          )}
          {width > 0 &&
            numbers.map((n) =>
              n.x >= cover0 && n.x <= cover1 ? null : (
                <span key={n.from} className={`${styles.tickNo} num`} style={{ left: `${n.x}px` }} data-passed={n.passed ? "" : undefined} aria-hidden="true">
                  {n.from === n.to ? n.from : n.to - n.from === 1 ? `${n.from}·${n.to}` : `${n.from}~${n.to}`}
                </span>
              ),
            )}
          {markers.map((m) => (
            <span key={`${m.sec}-${m.kind}`} className={styles.mark} data-kind={m.kind} style={{ left: `${(m.sec / Math.max(1, duration)) * 100}%` }} aria-hidden="true" />
          ))}
          <span className={styles.handleRail} style={{ transform: `translateX(${p * width}px)` }} aria-hidden="true">
            <span className={styles.handle} />
            <span className={`${styles.handleTime} num`} data-edge={edge}>
              {mmss(value * 1000)}
            </span>
          </span>
          {hover !== null && (
            <span className={styles.peek} style={{ transform: `translateX(${hover.x}px)` }} data-edge={hover.x < 120 ? "start" : hover.x > width - 120 ? "end" : "mid"} aria-hidden="true">
              <span className="num">{mmss(hover.sec * 1000)}</span>
              {near && (
                <>
                  <span className={styles.peekKind} data-kind={near.kind}>
                    {MARK_WORD[near.kind]} {mmss(near.sec * 1000)}
                  </span>
                  <span className={styles.peekText}>{near.text}</span>
                </>
              )}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}
