"use client";

/**
 * 승인 원장 단일 폴러.
 *
 * 승인 목록은 다섯 화면에 삽입된다. 화면마다 자기 타이머를 들면 같은 원장을 다섯 번 읽는데,
 * 관리 요청은 서버에서 한 건당 10토큰을 쓰므로(AdminAuthFilter) 5초 주기 다섯 폴러는 분당
 * 600토큰이다. 계약 기본 버킷(60토큰/60초)의 열 배이고 학교 배포 버킷이 1분에 채우는 600토큰 전부다.
 *
 * 폴러를 하나로 모으고 구독자 수로 켜고 끈다. 구독자가 0이면 멈추므로 보이지 않는 화면이
 * 원장을 읽지 않는다. 화면마다 다른 액션 범위는 구독자가 클라이언트에서 거른다. 한 번
 * 받아 온 쪽을 나눠 쓰는 것이라 범위가 늘어도 왕복은 늘지 않는다.
 *
 * 조작 직후에는 주기를 기다리지 않는다. invalidate()가 즉시 한 번 돌고, 그 약속은 새 원장이
 * 반영된 뒤에 풀린다. 행이 사라진 뒤로 포커스를 옮기는 소비처가 그 순서에 기댄다.
 */

import { useEffect } from "react";
import { create } from "zustand";
import { getAdminApi } from "./api";
import { onBeat } from "./ticker";
import type { ApprovalRequest } from "./types";

/** 승인은 사람이 만들고 사람이 실행하므로 초 단위 신선도가 필요 없다 */
const POLL_MS = 30000;

interface LedgerState {
  rows: ApprovalRequest[];
  /** 첫 쪽 뒤에 더 있는가. 목록이 받은 만큼만 그린다는 사실을 화면이 적는다 */
  hasMore: boolean;
  /** 마지막 성공 시각. 한 번도 받지 못했으면 null */
  lastAt: number | null;
  /** 첫 로드 완료 전 */
  loading: boolean;
  /** 마지막 읽기가 실패했는가. 받은 적이 없는데 실패했으면 빈 목록이 아니라 모르는 상태다 */
  failed: boolean;
  subscribers: number;
  /** 콘솔 공용 박자 구독을 끊는 함수. 구독자가 없으면 null이다 */
  timer: (() => void) | null;
  /** 다음 읽기 시각(ms) */
  nextAt: number;
  seq: number;
}

interface LedgerActions {
  refresh(): Promise<void>;
  invalidate(): Promise<void>;
  subscribe(): void;
  unsubscribe(): void;
}

const useLedger = create<LedgerState & LedgerActions>((set, get) => {
  /**
   * 구독자 수에 맞춰 콘솔 공용 박자(ticker.ts)를 타거나 내린다. 멱등이라 여러 번 불러도 구독은 하나다.
   * 박자는 탭이 숨으면 멎으므로 숨은 탭은 원장을 읽지 않고, 다시 보이면 밀린 조회를 곧바로 한다
   */
  const retime = () => {
    const { timer, subscribers } = get();
    if (subscribers > 0 && timer === null) {
      set({ timer: onBeat((now) => (now >= get().nextAt ? void get().refresh() : undefined)) });
    } else if (subscribers <= 0 && timer !== null) {
      timer();
      set({ timer: null });
    }
  };

  return {
    rows: [],
    hasMore: false,
    lastAt: null,
    loading: true,
    failed: false,
    subscribers: 0,
    timer: null,
    nextAt: 0,
    seq: 0,

    async refresh() {
      const seq = get().seq + 1;
      set({ seq, nextAt: Date.now() + POLL_MS });
      try {
        /* 실행 대기 목록이라 ISSUED만 본다. 상태 없이 부르면 소비·만료된 이력까지 받아
           화면에서 다시 걸러야 하고, 이력이 쌓일수록 첫 쪽이 처리 대상이 아닌 행으로 찬다 */
        const all = await getAdminApi().listApprovals("ISSUED");
        // 더 새 요청이 출발했으면 이 응답은 버린다. 늦은 응답이 최신 원장을 되돌리지 않는다
        if (seq !== get().seq) return;
        set({ rows: all.data, hasMore: all.hasMore, lastAt: Date.now(), loading: false, failed: false });
      } catch {
        // 세션 만료는 레이아웃 게이트가 처리한다. 여기서는 마지막 값을 두고 실패만 세운다
        if (seq === get().seq) set({ loading: false, failed: true });
      }
    },

    invalidate() {
      return get().refresh();
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
  };
});

/** 폴러 상태와 동작. 화면은 useApprovalLedger를 쓰고, 시험이 타이머·실패 표시를 이 경계로 확인한다 */
export const approvalLedgerStore = useLedger;

/**
 * 원장 구독. 소비처가 마운트돼 있는 동안만 폴러가 돈다.
 * 반환값의 rows는 전 범위다. 화면별 액션 범위는 소비처가 거른다.
 */
export function useApprovalLedger(): {
  rows: ApprovalRequest[];
  hasMore: boolean;
  loading: boolean;
  failed: boolean;
  lastAt: number | null;
  invalidate: () => Promise<void>;
} {
  const rows = useLedger((s) => s.rows);
  const hasMore = useLedger((s) => s.hasMore);
  const loading = useLedger((s) => s.loading);
  const failed = useLedger((s) => s.failed);
  const lastAt = useLedger((s) => s.lastAt);
  const invalidate = useLedger((s) => s.invalidate);

  useEffect(() => {
    const { subscribe, unsubscribe } = useLedger.getState();
    subscribe();
    return unsubscribe;
  }, []);

  return { rows, hasMore, loading, failed, lastAt, invalidate };
}
