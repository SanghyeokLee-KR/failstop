"use client";

/**
 * 관제센터 한 판을 읽는 단일 폴러. 관제 화면들과 사이드바 배지, AI 상담이 같은 판을 나눠 본다.
 *
 * 승인 원장 폴러(approvals.ts)와 같이 구독자 수로 켜고 끈다. 사이드바 배지는 운영 관제 셸이 떠 있는
 * 동안 늘 구독하므로 운영 관제 화면 어디에 있든 판이 하나만 돈다. 백오피스 셸은 구독하지 않는다.
 *
 * 폴러는 콘솔 공용 박자(ticker.ts)를 탄다. 탭이 숨으면 박자가 멎어 읽지 않고, 다시 보이면 곧바로
 * 한 번 읽는다. 주기는 원천이 정한다. sim은 브라우저 안의 시뮬레이터라 서버 예산이 없고 시나리오가
 * 초 단위로 움직이므로 박자마다 읽는다. http는 관리 API의 한 판 조회(서버 계약 초안의 overview) 한
 * 건을 15초마다 읽는다. 요청 한 건이 운영자 버킷에서 10토큰을 쓰므로 분당 40토큰이다. 관제 모듈이 없는
 * 배포(404)면 5분마다만 다시 확인한다. 실패가 이어지면 주기를 두 배씩 늘려 5분에서 멈추고, 429의
 * Retry-After가 더 길면 그만큼 쉰다.
 *
 * 새 판을 받으면 이전 판과 같은 가지는 이전 객체를 그대로 둔다(shareEqual). 바뀐 칸만 새 참조가 되므로
 * 칸을 받아 그리는 화면(memo)은 그 칸이 바뀔 때만 다시 그린다.
 */

import { useEffect } from "react";
import { create } from "zustand";
import { useShallow } from "zustand/react/shallow";
import { AdminError, getAdminApi, isAdminHttp } from "./api";
import { onBeat } from "./ticker";
import type { ControlCenter, OpsRef } from "./types";

const SIM_MS = 1_000;
const HTTP_MS = 15_000;
/** 관리 API에 관제 모듈이 없을 때(404) 다시 확인하는 주기. 관리 요청은 모두 감사에 남으므로 없는 경로를 15초마다 두드리지 않는다 */
const HTTP_ABSENT_MS = 300_000;
const BACKOFF_MAX_MS = 300_000;

/**
 * 이전 값과 같은 가지는 이전 참조를 돌려준다. 평범한 객체와 배열만 들여다보고 나머지는 값으로 견준다.
 * 판 전체가 같으면 이전 판을 그대로 돌려준다
 */
export function shareEqual<T>(prev: unknown, next: T): T {
  if (Object.is(prev, next)) return prev as T;
  if (Array.isArray(prev) && Array.isArray(next)) {
    let same = prev.length === next.length;
    const out = next.map((v, i) => {
      const s = shareEqual(prev[i], v);
      if (s !== prev[i]) same = false;
      return s;
    });
    return (same ? prev : out) as T;
  }
  if (isPlain(prev) && isPlain(next)) {
    const keys = Object.keys(next);
    let same = keys.length === Object.keys(prev).length;
    const out: Record<string, unknown> = {};
    for (const k of keys) {
      const s = shareEqual(prev[k], next[k]);
      out[k] = s;
      if (s !== prev[k] || !(k in prev)) same = false;
    }
    return (same ? prev : out) as T;
  }
  return next;
}

function isPlain(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && Object.getPrototypeOf(v) === Object.prototype;
}

/** 실패 n번째 뒤의 대기. 원천 주기를 두 배씩 늘리고 상한에서 멈춘다. Retry-After가 더 길면 그것을 따른다 */
export function backoffMs(periodMs: number, failures: number, retryAfterSec: number | null): number {
  const grown = Math.min(BACKOFF_MAX_MS, periodMs * 2 ** failures);
  return Math.max(grown, (retryAfterSec ?? 0) * 1000);
}

interface CenterState {
  data: ControlCenter | null;
  /** 마지막 성공 시각. 한 번도 받지 못했으면 null */
  lastAt: number | null;
  loading: boolean;
  /** 마지막 읽기가 실패했는가. 받은 적이 있으면 지연, 없으면 오류로 그린다 */
  failed: boolean;
  subscribers: number;
  /** 이어진 실패 수. 성공하면 0이다 */
  failures: number;
  /** 다음 읽기 시각(ms) */
  nextAt: number;
  /**
   * 한 판을 읽는다. 읽는 중이면 새 요청을 내지 않고 그 요청을 기다린다. 조작 직후(force)에는 이미
   * 떠난 요청이 조작 전 판일 수 있어 그 뒤에 한 번 더 읽는다. 여러 조작이 겹쳐도 더 읽는 것은 한 번이다
   */
  refresh(force?: boolean): Promise<void>;
  subscribe(): void;
  unsubscribe(): void;
}

let inflight: Promise<void> | null = null;
let again: Promise<void> | null = null;
let stopBeat: (() => void) | null = null;

const useCenter = create<CenterState>((set, get) => {
  const period = (c: ControlCenter | null) => (!isAdminHttp() ? SIM_MS : c?.sources === null ? HTTP_ABSENT_MS : HTTP_MS);

  const load = async () => {
    try {
      const next = await getAdminApi().getControlCenter();
      set({ data: shareEqual(get().data, next), lastAt: Date.now(), loading: false, failed: false, failures: 0, nextAt: Date.now() + period(next) });
    } catch (e) {
      // 세션 만료는 관리 게이트가 처리한다. 여기서는 마지막 판을 두고 실패만 세운다
      const failures = get().failures + 1;
      const retryAfter = e instanceof AdminError ? e.retryAfter : null;
      set({ loading: false, failed: true, failures, nextAt: Date.now() + backoffMs(period(get().data), failures, retryAfter) });
    }
  };

  const refresh = (force = false): Promise<void> => {
    if (inflight) {
      if (!force) return inflight;
      again ??= inflight.then(() => {
        again = null;
        return refresh();
      });
      return again;
    }
    inflight = load().finally(() => {
      inflight = null;
    });
    return inflight;
  };

  return {
    data: null,
    lastAt: null,
    loading: true,
    failed: false,
    subscribers: 0,
    failures: 0,
    nextAt: 0,
    refresh,
    subscribe() {
      set({ subscribers: get().subscribers + 1 });
      if (stopBeat) return;
      // 첫 구독이면 박자를 기다리지 않고 판이 오래됐을 때 바로 읽는다. 박자가 이미 돌고 있으면 다음 박자까지 1초를 기다리게 된다
      if (Date.now() >= get().nextAt) void refresh();
      stopBeat = onBeat((now) => {
        if (now >= get().nextAt) void refresh();
      });
    },
    unsubscribe() {
      const n = Math.max(0, get().subscribers - 1);
      set({ subscribers: n });
      if (n === 0 && stopBeat) {
        stopBeat();
        stopBeat = null;
      }
    },
  };
});

/** 시험이 폴러 상태를 이 경계로 본다 */
export const opsCenterStore = useCenter;

function useSubscription(): void {
  useEffect(() => {
    const { subscribe, unsubscribe } = useCenter.getState();
    subscribe();
    return unsubscribe;
  }, []);
}

/** 관제 판 구독. 소비처가 떠 있는 동안만 폴러가 돈다 */
export function useOpsCenter(): {
  data: ControlCenter | null;
  loading: boolean;
  failed: boolean;
  lastAt: number | null;
  refresh: (force?: boolean) => Promise<void>;
} {
  const data = useCenter((s) => s.data);
  const loading = useCenter((s) => s.loading);
  const failed = useCenter((s) => s.failed);
  const lastAt = useCenter((s) => s.lastAt);
  const refresh = useCenter((s) => s.refresh);
  useSubscription();
  return { data, loading, failed, lastAt, refresh };
}

/**
 * 판에서 한 조각만 골라 구독한다. 고른 값이 바뀔 때만 다시 그린다. 문자열·숫자처럼 값으로 견줄 수
 * 있는 조각을 고르거나, 판의 칸(shareEqual이 같은 참조를 지킨다)을 고른다. 고를 때마다 새로 만드는
 * 객체는 useOpsPickShallow로 고른다
 */
export function useOpsPick<T>(pick: (c: ControlCenter | null) => T): T {
  const value = useCenter((s) => pick(s.data));
  useSubscription();
  return value;
}

/** 고른 객체의 필드를 얕게 견준다. 배지처럼 글자 몇 개로 된 조각을 새 객체로 만들어 고를 때 쓴다 */
export function useOpsPickShallow<T>(pick: (c: ControlCenter | null) => T): T {
  const value = useCenter(useShallow((s: CenterState) => pick(s.data)));
  useSubscription();
  return value;
}

/* ===== 근거 강조 =====
 * AI 근거 칩을 누르면 그 근거가 가리키는 경보 행이나 카드에 2초 동안 고리를 세운다(디자인 시스템 §12-4).
 * 가리키는 쪽과 받는 쪽이 서로 다른 기둥에 있어 화면 상태가 아니라 작은 저장소에 둔다 */

interface FocusState {
  ref: OpsRef | null;
  seq: number;
  point(ref: OpsRef): void;
}

const FOCUS_MS = 2000;

export const useOpsFocus = create<FocusState>((set, get) => ({
  ref: null,
  seq: 0,
  point(ref) {
    const seq = get().seq + 1;
    set({ ref, seq });
    setTimeout(() => {
      if (get().seq === seq) set({ ref: null });
    }, FOCUS_MS);
  },
}));

/** 이 자리가 지금 강조 대상인가. 경보를 가리키는 근거는 그 경보가 가리키는 자리도 함께 켠다 */
export function refMatches(focus: OpsRef | null, target: OpsRef, alertRefs?: Map<string, OpsRef | null>): boolean {
  if (focus === null) return false;
  const same = (a: OpsRef, b: OpsRef) => JSON.stringify(a) === JSON.stringify(b);
  if (same(focus, target)) return true;
  if (focus.kind === "ALERT" && alertRefs) {
    const via = alertRefs.get(focus.id);
    return via != null && same(via, target);
  }
  return false;
}
