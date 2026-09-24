"use client";

/**
 * 관제센터 한 판을 읽는 단일 폴러. 관제 화면들과 사이드바 배지가 같은 판을 나눠 본다.
 *
 * 승인 원장 폴러(approvals.ts)와 같이 구독자 수로 켜고 끈다. 사이드바 배지는 운영 관제 셸이 떠
 * 있는 동안 늘 구독하므로 운영 관제 화면 어디에 있든 판이 하나만 돈다. 백오피스 셸은 구독하지
 * 않는다.
 *
 * 주기는 원천이 정한다. sim은 브라우저 안의 시뮬레이터라 서버 예산이 없고 시나리오가 초 단위로
 * 움직이므로 1초로 읽는다. http는 서버를 부르지 않는 연결 전 판이라 30초로 둔다(2단계에서
 * 실측 원천을 붙일 때 useAdminResource의 리듬 예산으로 다시 정한다).
 */

import { useEffect } from "react";
import { create } from "zustand";
import { getAdminApi, isAdminHttp } from "./api";
import type { ControlCenter, OpsRef } from "./types";

interface CenterState {
  data: ControlCenter | null;
  /** 마지막 성공 시각. 한 번도 받지 못했으면 null */
  lastAt: number | null;
  loading: boolean;
  /** 마지막 읽기가 실패했는가. 받은 적이 있으면 지연, 없으면 오류로 그린다 */
  failed: boolean;
  subscribers: number;
  timer: ReturnType<typeof setInterval> | null;
  seq: number;
  refresh(): Promise<void>;
  subscribe(): void;
  unsubscribe(): void;
}

const useCenter = create<CenterState>((set, get) => {
  const retime = () => {
    const { timer, subscribers } = get();
    if (timer) clearInterval(timer);
    if (subscribers <= 0) {
      set({ timer: null });
      return;
    }
    set({ timer: setInterval(() => void get().refresh(), isAdminHttp() ? 30_000 : 1_000) });
  };
  return {
    data: null,
    lastAt: null,
    loading: true,
    failed: false,
    subscribers: 0,
    timer: null,
    seq: 0,
    async refresh() {
      const seq = get().seq + 1;
      set({ seq });
      try {
        const data = await getAdminApi().getControlCenter();
        // 늦게 온 응답이 더 새 판을 덮지 않게 한다
        if (seq !== get().seq) return;
        set({ data, lastAt: Date.now(), loading: false, failed: false });
      } catch {
        // 세션 만료는 관리 게이트가 처리한다. 여기서는 마지막 판을 두고 실패만 세운다
        if (seq === get().seq) set({ loading: false, failed: true });
      }
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

/** 시험이 폴러 상태를 이 경계로 본다 */
export const opsCenterStore = useCenter;

/** 관제 판 구독. 소비처가 떠 있는 동안만 폴러가 돈다 */
export function useOpsCenter(): {
  data: ControlCenter | null;
  loading: boolean;
  failed: boolean;
  lastAt: number | null;
  refresh: () => Promise<void>;
} {
  const data = useCenter((s) => s.data);
  const loading = useCenter((s) => s.loading);
  const failed = useCenter((s) => s.failed);
  const lastAt = useCenter((s) => s.lastAt);
  const refresh = useCenter((s) => s.refresh);
  useEffect(() => {
    const { subscribe, unsubscribe } = useCenter.getState();
    subscribe();
    return unsubscribe;
  }, []);
  return { data, loading, failed, lastAt, refresh };
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
