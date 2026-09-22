"use client";

/**
 * 승인 원장 단일 폴러.
 *
 * ApprovalList가 자체 setInterval을 들고 다섯 화면에 삽입돼 있었다. 같은 원장을 다섯 폴러가
 * 5초마다 각각 읽어 분당 60건이 나갔고, 그것만으로 관리 레이트리밋 예산(계약 60토큰/60초)을
 * 다 쓰는 셈이었다. 나머지 화면의 주기가 신선도가 아니라 남은 예산으로 정해진 뿌리가 여기다.
 *
 * 폴러를 하나로 모으고 구독자 수로 켜고 끈다. 구독자가 0이면 멈추므로 보이지 않는 화면이
 * 원장을 읽지 않는다. 화면마다 다른 액션 범위는 구독자가 클라이언트에서 거른다. 한 번
 * 받아 온 쪽을 나눠 쓰는 것이라 범위가 늘어도 왕복은 늘지 않는다.
 *
 * 조작 직후에는 주기를 기다리지 않는다. invalidate()가 즉시 한 번 돈다.
 */

import { useEffect } from "react";
import { create } from "zustand";
import { getAdminApi } from "./api";
import type { ApprovalRequest } from "./types";

/** 평시 30초. 승인은 사람이 만들고 사람이 실행하므로 초 단위 신선도가 필요 없다 */
const IDLE_MS = 30000;
/** 시연 구간. 단계 판정이 승인 상태를 보고 넘어간다 */
const LIVE_MS = 5000;

interface LedgerState {
  rows: ApprovalRequest[];
  /** 첫 쪽 뒤에 더 있는가. 목록이 받은 만큼만 그린다는 사실을 화면이 적는다 */
  hasMore: boolean;
  lastAt: number | null;
  /** 첫 로드 완료 전 */
  loading: boolean;
  subscribers: number;
  live: boolean;
  timer: ReturnType<typeof setInterval> | null;
  seq: number;
}

interface LedgerActions {
  refresh(): Promise<void>;
  invalidate(): void;
  subscribe(): void;
  unsubscribe(): void;
  setLive(live: boolean): void;
}

const useLedger = create<LedgerState & LedgerActions>((set, get) => {
  /** 구독자 수와 리듬에 맞춰 타이머를 다시 건다. 멱등이라 여러 번 불러도 타이머는 하나다 */
  const retime = () => {
    const { timer, subscribers, live } = get();
    if (timer) clearInterval(timer);
    if (subscribers <= 0) {
      set({ timer: null });
      return;
    }
    const ms = live ? LIVE_MS : IDLE_MS;
    set({ timer: setInterval(() => void get().refresh(), ms) });
  };

  return {
    rows: [],
    hasMore: false,
    lastAt: null,
    loading: true,
    subscribers: 0,
    live: false,
    timer: null,
    seq: 0,

    async refresh() {
      const seq = get().seq + 1;
      set({ seq });
      try {
        /* 실행 대기 목록이라 ISSUED만 본다. 상태 없이 부르면 소비·만료된 이력까지 받아
           화면에서 다시 걸러야 하고, 이력이 쌓일수록 첫 쪽이 처리 대상이 아닌 행으로 찬다 */
        const all = await getAdminApi().listApprovals("ISSUED");
        // 더 새 요청이 출발했으면 이 응답은 버린다. 늦은 응답이 최신 원장을 되돌리지 않는다
        if (seq !== get().seq) return;
        set({ rows: all.data, hasMore: all.hasMore, lastAt: Date.now(), loading: false });
      } catch {
        // 세션 만료 등은 레이아웃 게이트가 처리한다. 여기서는 마지막 값을 유지한다
        if (seq === get().seq) set({ loading: false });
      }
    },

    invalidate() {
      void get().refresh();
    },

    subscribe() {
      set({ subscribers: get().subscribers + 1 });
      if (get().subscribers === 1) void get().refresh();
      retime();
    },

    unsubscribe() {
      set({ subscribers: Math.max(0, get().subscribers - 1) });
      retime();
    },

    setLive(live) {
      if (get().live === live) return;
      set({ live });
      retime();
    },
  };
});

/**
 * 원장 구독. 소비처가 마운트돼 있는 동안만 폴러가 돈다.
 * 반환값의 rows는 전 범위다. 화면별 액션 범위는 소비처가 거른다.
 */
export function useApprovalLedger(): {
  rows: ApprovalRequest[];
  hasMore: boolean;
  loading: boolean;
  lastAt: number | null;
  invalidate: () => void;
} {
  const rows = useLedger((s) => s.rows);
  const hasMore = useLedger((s) => s.hasMore);
  const loading = useLedger((s) => s.loading);
  const lastAt = useLedger((s) => s.lastAt);
  const invalidate = useLedger((s) => s.invalidate);

  useEffect(() => {
    const { subscribe, unsubscribe } = useLedger.getState();
    subscribe();
    return unsubscribe;
  }, []);

  return { rows, hasMore, loading, lastAt, invalidate };
}

/** 시연 구간 승격. 구간이 끝나면 반드시 false로 되돌린다 */
export function setApprovalLedgerLive(live: boolean): void {
  useLedger.getState().setLive(live);
}
