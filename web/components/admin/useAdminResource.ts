"use client";

/**
 * 관리 평면 폴링 리소스 상태 머신(§5.2, 감사 error/rows 패턴 승격).
 * 로딩·빈·에러 삼분과 스테일 신호를 한곳에서 관리한다.
 * 페칭 자체(loader 본문)는 화면·어댑터 소유. 이 훅은 상태만 다룬다.
 *
 * 상태 구분:
 * - loading: 최초 로드 진행 중(데이터 없음). Skeleton.
 * - error: 최초 로드 실패(데이터 없음). empty-state role=alert + 재시도.
 * - stale: 폴링 실패로 마지막 값 유지 중. 지연 배지 + 갱신 영역 opacity 저하.
 * 최초 실패와 폴링 실패는 데이터 유무로 가른다. 세션 사유 구분은 isSessionError로 주입한다.
 * 단 세션 만료만은 데이터 유무보다 우선해 error로 승격한다(스테일로 흡수 금지).
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { AdminError } from "@/lib/admin/api";

export type ErrorReason = "session" | "server";

// 429 백오프 기본 유예(초). Retry-After 부재 시 사용. 서버 버킷(60건/60초, 리필 1건/초) 기준
const RATE_LIMIT_FALLBACK_SEC = 5;

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

interface Options {
  /** 폴링 주기(ms). 생략하면 1회 로드만 한다 */
  intervalMs?: number;
  /** 세션 만료 판별. 어댑터 에러 형태를 화면이 주입한다 */
  isSessionError?: (e: unknown) => boolean;
}

export function useAdminResource<T>(loader: () => Promise<T>, options: Options = {}): AdminResource<T> {
  const { intervalMs, isSessionError } = options;
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
