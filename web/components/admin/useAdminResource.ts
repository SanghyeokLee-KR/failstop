"use client";

/**
 * 최초 로드 실패(error)와 폴링 실패(stale)는 데이터 유무로 가른다.
 * 세션 만료만 데이터 유무보다 우선해 error로 승격한다(스테일로 흡수 금지).
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { AdminError } from "@/lib/admin/api";

export type ErrorReason = "session" | "server";

// 429 백오프 기본 유예(초). Retry-After 부재 시 사용.
// 요청 한 건(10토큰)이 계약 기본 버킷(60토큰/60초)에서 다시 차는 시간이다
const RATE_LIMIT_FALLBACK_SEC = 10;

/** 레이트리밋 오류면 유예 초를 돌려준다(Retry-After 우선). 아니면 null */
function rateLimitPauseSec(e: unknown): number | null {
  if (!(e instanceof AdminError)) return null;
  if (e.code !== "too_many_requests" && e.code !== "rate_limited") return null;
  return e.retryAfter !== null && e.retryAfter > 0 ? e.retryAfter : RATE_LIMIT_FALLBACK_SEC;
}

export interface AdminResource<T> {
  data: T | null;
  /** 최초 로드 진행 중(데이터 없음) */
  loading: boolean;
  /** 최초 로드 실패(데이터 없음) */
  error: boolean;
  /** 최초 실패 사유. 세션 만료와 서버·네트워크를 구분해 안내 문구를 가른다 */
  errorReason: ErrorReason;
  /** 폴링 실패로 마지막 값을 유지 중 */
  stale: boolean;
  /** 마지막 성공 갱신 시각(ms). 지연 배지 경과 계산용 */
  lastUpdatedAt: number | null;
  /** 수동 재시도. 에러 상태를 초기화하고 다시 로드 */
  reload: () => void;
}

/**
 * 갱신 리듬. 화면이 밀리초를 직접 적지 않는다. 예산이 화면마다 흩어져 있으면 한 화면을
 * 고칠 때 다른 화면의 여유를 알 수 없다.
 *
 * 예산의 사실. 서버는 관리 요청 한 건마다 운영자 버킷에서 10토큰을 뺀다(AdminAuthFilter).
 * 계약 기본값 60토큰/60초는 분당 6건이라 종합 화면 진입(합성 6건과 승인 원장 1건)도 다 받지
 * 못한다. 콘솔을 쓰는 배포는 버킷을 올린다. 학교는 600토큰(분당 60건), 로컬 프로파일은
 * 6000토큰(분당 600건)이다. live 3초에 세 축을 읽는 화면은 분당 60건이라 학교 버킷이 1분에 다시
 * 채우는 양 전부를 폴링이 쓴다.
 *
 * manual 화면은 값이 언제 것인지가 반드시 보여야 하므로 최종 실측 시각과 새로고침을
 * 머리에 상설한다(ResourceStamp).
 */
export type Cadence = "manual" | "idle" | "live";

/** live는 시연 구간용이다. 평시에 쓰면 조작 요청이 쓸 버킷이 폴링으로 먼저 빠진다 */
const CADENCE_MS: Record<Cadence, number | null> = { manual: null, idle: 30000, live: 3000 };

interface Options {
  /** 갱신 리듬. 생략하면 manual이고 진입 1회 + 새로고침만 한다 */
  cadence?: Cadence;
  /** 세션 만료 판별. 어댑터 에러 형태를 화면이 주입한다 */
  isSessionError?: (e: unknown) => boolean;
}

export function useAdminResource<T>(loader: () => Promise<T>, options: Options = {}): AdminResource<T> {
  const { cadence = "manual", isSessionError } = options;
  const intervalMs = CADENCE_MS[cadence];
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState(false);
  const [errorReason, setErrorReason] = useState<ErrorReason>("server");
  const [stale, setStale] = useState(false);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null);

  // loader·판별자 최신 참조를 ref로 잡아 폴링 재구독을 막는다(주기만 의존)
  const loaderRef = useRef(loader);
  loaderRef.current = loader;
  const sessionRef = useRef(isSessionError);
  sessionRef.current = isSessionError;
  // 최초 실패와 폴링 실패 구분용. 성공 이력을 렌더 밖에서 추적한다
  const hasData = useRef(false);
  // 429 백오프 만료 시각(ms). 유예 중 폴링 틱은 서버 왕복 없이 건너뛴다.
  // 백오프 없이 고정 주기를 유지하면 폴러가 버킷 리필을 계속 소진해 상호작용 액션까지 굶긴다
  const pauseUntil = useRef(0);
  // 폴링 틱과 reload가 병행할 수 있다. 먼저 출발한 요청의 늦은 응답이 최신 데이터를
  // 이전 스냅샷으로 되돌리지 않도록 최신 요청 seq만 반영한다
  const runSeq = useRef(0);

  const run = useCallback(async () => {
    if (Date.now() < pauseUntil.current) return;
    const seq = ++runSeq.current;
    try {
      const next = await loaderRef.current();
      if (seq !== runSeq.current) return; // 스테일 응답 폐기
      setData(next);
      hasData.current = true;
      setError(false);
      setStale(false);
      setLastUpdatedAt(Date.now());
    } catch (e) {
      if (seq !== runSeq.current) return; // 스테일 실패도 최신 상태를 오염시키지 않는다
      const pauseSec = rateLimitPauseSec(e);
      if (pauseSec !== null) pauseUntil.current = Date.now() + pauseSec * 1000;
      // 세션 만료는 데이터 유무와 무관하게 에러로 승격한다. 스테일로 흡수하면
      // 로그아웃된 콘솔이 얼어붙은 값으로 정상을 계속 주장한다(관제 도구가 내면 안 되는 오보)
      if (sessionRef.current?.(e)) {
        setErrorReason("session");
        setError(true);
      } else if (hasData.current) {
        // 폴링 실패는 마지막 값을 유지하고 스테일만 세운다(레이아웃 시프트 0)
        setStale(true);
      } else {
        setErrorReason("server");
        setError(true);
      }
    }
  }, []);

  useEffect(() => {
    void run();
    if (!intervalMs) return;
    const iv = setInterval(() => void run(), intervalMs);
    return () => clearInterval(iv);
  }, [run, intervalMs]);

  const reload = useCallback(() => {
    setError(false);
    pauseUntil.current = 0; // 수동 재시도는 사용자 의도이므로 백오프를 해제한다
    void run();
  }, [run]);

  return {
    data,
    loading: data === null && !error,
    error,
    errorReason,
    stale,
    lastUpdatedAt,
    reload,
  };
}
