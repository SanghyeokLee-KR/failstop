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
 */

import { useCallback, useEffect, useRef, useState } from "react";

export type ErrorReason = "session" | "server";

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

  const run = useCallback(async () => {
    try {
      const next = await loaderRef.current();
      setData(next);
      hasData.current = true;
      setError(false);
      setStale(false);
      setLastUpdatedAt(Date.now());
    } catch (e) {
      if (hasData.current) {
        // 폴링 실패는 마지막 값을 유지하고 스테일만 세운다(레이아웃 시프트 0)
        setStale(true);
      } else {
        setErrorReason(sessionRef.current?.(e) ? "session" : "server");
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
