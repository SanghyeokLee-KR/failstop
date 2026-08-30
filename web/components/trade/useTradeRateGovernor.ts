"use client";

/**
 * 표본 수집은 zustand transient 구독으로 한다(설계 §2-3). 셀렉터 구독은 trade_id가 체결마다 바뀌어
 * 호출 컴포넌트를 표본 1건 넣자고 통째로 리렌더시킨다.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { useExchangeStore } from "@/lib/store";
import {
  createRateState,
  currentRate,
  governorFor,
  nextBand,
  sampleRate,
  type GovernorResult,
} from "./effectGovernor";

// 유휴 시 밴드 자연 복귀를 위한 재평가 주기. 체결이 멈추면 경과가 커져 밴드가 내려간다
const IDLE_RECHECK_MS = 1000;

export function useTradeRateGovernor(market: string): GovernorResult {
  const rate = useRef(createRateState());
  const band = useRef(0);
  const [result, setResult] = useState<GovernorResult>(() => governorFor(0));

  const applyBand = useCallback((next: number) => {
    if (next !== band.current) {
      band.current = next;
      setResult(governorFor(next));
    }
  }, []);

  // 체결 도착마다 표본 반영 후 밴드 재판정(리렌더 없는 스토어 관찰)
  useEffect(() => {
    let lastTop = useExchangeStore.getState().trades[market]?.[0]?.trade_id;
    return useExchangeStore.subscribe((state) => {
      const top = state.trades[market]?.[0]?.trade_id;
      if (!top || top === lastTop) return;
      lastTop = top;
      const now = performance.now();
      sampleRate(rate.current, now);
      applyBand(nextBand(band.current, currentRate(rate.current, now)));
    });
  }, [market, applyBand]);

  // 유휴 복귀: 밴드가 0보다 크면 주기적으로 재평가해 하향한다
  useEffect(() => {
    if (result.band === 0) return;
    const id = setInterval(() => {
      applyBand(nextBand(band.current, currentRate(rate.current, performance.now())));
    }, IDLE_RECHECK_MS);
    return () => clearInterval(id);
  }, [result.band, applyBand]);

  // 마켓 전환 시 표본·밴드 초기화
  useEffect(() => {
    rate.current = createRateState();
    applyBand(0);
  }, [market, applyBand]);

  return result;
}
