"use client";

/**
 * 관제(앱)와 온프레미스 ai-1 사이의 판단 요청선(ADR 0038). 연결 경로(사설 전용선, VPN, 공개 끝점 등)는
 * 아직 정하지 않았다. 그래서 선은 흐름 선(EdgeId)이 아니라 연결을 뜻하지 않는 점선 그림이고, 선 밑
 * 이름표가 '연결 경로 결정 전'이라고 적는다. 이 화면은 ai-1 에 아무것도 보내지 않는다.
 *
 * 관제 판(opsCenter)에서 AI 판단 요청이 걸리면(busy 가 켜지는 순간) 요청 알갱이 하나가 매칭 칸에서 ai-1 로,
 * 응답이 돌아오면(served 가 오르는 순간) 응답 알갱이 하나가 ai-1 에서 매칭 칸으로 지난다. 판단이 없을 때는
 * 아무것도 움직이지 않는다. 알갱이와 요청이 걸린 동안의 선이 이 화면에서 AI 강조색을 쓰는 자리다(디자인 시스템 §12-10).
 *
 * 알갱이는 transform 만 움직이는 Web Animations 라 합성 단계에서 돈다(FlowTraffic 과 같은 이유).
 * 요청이 걸려 있는 동안에는 선이 AI 강조색으로 바뀌고 이름표가 '판단 요청 처리 중'을 적는다. 움직임이 없어도
 * 상태가 읽힌다. 움직임 줄이기 설정이면 알갱이를 띄우지 않고 선 가운데에 멈춘 점 하나를 더 둔다.
 */

import { useEffect, useRef, useState } from "react";
import styles from "./topology.module.css";

/** 알갱이 한 번이 선을 건너는 시간. 판단 중 표시(2초) 안에 요청이 닿아 보이는 길이다 */
const TRAVEL_MS = 900;
const REDUCED_MOTION = "(prefers-reduced-motion: reduce)";

function useReducedMotion(): boolean {
  const [reduce, setReduce] = useState(false);
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const mq = window.matchMedia(REDUCED_MOTION);
    setReduce(mq.matches);
    const on = (e: MediaQueryListEvent) => setReduce(e.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  return reduce;
}

export default function AiLink({ x0, x1, y, width, height, busy, served, down }: {
  /** ai-1 카드 오른변 */
  x0: number;
  /** 매칭 칸 왼변 */
  x1: number;
  y: number;
  width: number;
  height: number;
  /** 관제 판의 판단 요청이 걸려 있다. 원천이 없으면 false */
  busy: boolean;
  /** 끝낸 판단 요청의 순번. 오르는 순간 응답 알갱이가 지난다 */
  served: number;
  /** 관제 판이 ai-1 과의 연결 끊김을 알린다 */
  down: boolean;
}) {
  const reqRef = useRef<HTMLSpanElement>(null);
  const resRef = useRef<HTMLSpanElement>(null);
  const prevBusy = useRef(busy);
  const prevServed = useRef(served);
  const reduce = useReducedMotion();

  // 요청은 켜지는 순간에만 한 번 보낸다. 화면을 열 때 이미 걸려 있던 요청은 지난 일이라 그리지 않는다
  useEffect(() => {
    const rising = busy && !prevBusy.current;
    prevBusy.current = busy;
    const el = reqRef.current;
    if (!rising || reduce || !el || typeof el.animate !== "function") return;
    el.animate(
      [
        { transform: `translate(${x1}px, ${y}px)`, opacity: 0 },
        { transform: `translate(${x1 - 12}px, ${y}px)`, opacity: 1, offset: 0.1 },
        { transform: `translate(${x0 + 12}px, ${y}px)`, opacity: 1, offset: 0.9 },
        { transform: `translate(${x0}px, ${y}px)`, opacity: 0 },
      ],
      { duration: TRAVEL_MS, easing: "cubic-bezier(.4,0,.2,1)" },
    );
  }, [busy, reduce, x0, x1, y]);

  useEffect(() => {
    const answered = served > prevServed.current;
    prevServed.current = served;
    const el = resRef.current;
    if (!answered || reduce || !el || typeof el.animate !== "function") return;
    el.animate(
      [
        { transform: `translate(${x0}px, ${y}px)`, opacity: 0 },
        { transform: `translate(${x0 + 12}px, ${y}px)`, opacity: 1, offset: 0.1 },
        { transform: `translate(${x1 - 12}px, ${y}px)`, opacity: 1, offset: 0.9 },
        { transform: `translate(${x1}px, ${y}px)`, opacity: 0 },
      ],
      { duration: TRAVEL_MS, easing: "cubic-bezier(.4,0,.2,1)" },
    );
  }, [served, reduce, x0, x1, y]);

  const holding = reduce && busy;
  return (
    <div className={styles.aiLink} data-down={down ? "true" : undefined} data-busy={busy && !down ? "true" : undefined} aria-hidden="true">
      <svg className={styles.aiLinkSvg} width={width} height={height} viewBox={`0 0 ${width} ${height}`} focusable="false">
        <path className={styles.aiLinkLine} d={`M${x0} ${y} H${x1}`} />
      </svg>
      {/* 이름표는 선 밑, 매칭 칸 왼변에 오른쪽을 맞춘다. 선 위는 리전·VPC 이름표 띠라 비워 둔다 */}
      <span className={styles.aiLinkLabel} style={{ right: width - x1 + 8, top: y + 5, maxWidth: Math.max(96, x1 - x0 - 24) }}>
        {down ? "■ 연결 끊김 · 연결 경로 결정 전" : busy ? "판단 요청 처리 중 · 연결 경로 결정 전" : "판단 요청 · 연결 경로 결정 전"}
      </span>
      <span ref={reqRef} className={styles.aiPacket} data-dir="req" />
      <span ref={resRef} className={styles.aiPacket} data-dir="res" />
      {holding && <span className={styles.aiPacket} data-static="true" style={{ transform: `translate(${(x0 + x1) / 2}px, ${y}px)` }} />}
    </div>
  );
}
