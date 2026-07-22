"use client";

/**
 * useTradeRateGovernor. 마켓별 체결 도착률 EWMA로 유효 밀도 밴드를 산출한다(설계 §2-3).
 * 지역 상태만 쓴다(전역 스토어 오염 금지). 관리 OPS 값에 의존하지 않고 클라이언트가 실측하므로
 * Sim·Http 양쪽에서 동일 동작한다. 밴드가 바뀔 때만 리렌더한다.
 */

import { useEffect, useRef, useState } from "react";
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
  const topId = useExchangeStore((s) => s.trades[market]?.[0]?.trade_id);
  const rate = useRef(createRateState());
  const band = useRef(0);
  const [result, setResult] = useState<GovernorResult>(() => governorFor(0));

  const applyBand = (next: number) => {
    if (next !== band.current) {
      band.current = next;
      setResult(governorFor(next));
    }
  };

  // 체결 도착마다 표본 반영 후 밴드 재판정
  useEffect(() => {
    if (!topId) return;
    const now = performance.now();
    sampleRate(rate.current, now);
    applyBand(nextBand(band.current, currentRate(rate.current, now)));
  }, [topId]);

  // 유휴 복귀: 밴드가 0보다 크면 주기적으로 재평가해 하향한다
  useEffect(() => {
    if (result.band === 0) return;
    const id = setInterval(() => {
      applyBand(nextBand(band.current, currentRate(rate.current, performance.now())));
    }, IDLE_RECHECK_MS);
    return () => clearInterval(id);
  }, [result.band]);

  // 마켓 전환 시 표본·밴드 초기화
  useEffect(() => {
    rate.current = createRateState();
    applyBand(0);
  }, [market]);

  return result;
}
