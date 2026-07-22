"use client";

/**
 * useLargeTradeThreshold. 마켓별 체결량 롤링 중앙값과 K배 임계를 계산한다(설계 §2-1·§2-6).
 * 스토어의 최근 체결(최대 60건)을 표본으로 쓴다. 별도 링버퍼를 두지 않아 상수 메모리.
 */

import { useMemo } from "react";
import { useExchangeStore } from "@/lib/store";
import { toNum } from "@/lib/format";
import { isLargeVolume, medianOf } from "./effectGovernor";

// 대형 체결 배수(§2-1, 조정 가능)
const LARGE_MULTIPLE = 8;

export interface LargeTradeThreshold {
  /** 중앙값의 K배. 표본 부족 시 Infinity(전부 비대형) */
  threshold: number;
  /** 체결량 문자열·숫자가 대형인지 판정 */
  isLarge: (volume: string | number) => boolean;
}

export function useLargeTradeThreshold(market: string, k = LARGE_MULTIPLE): LargeTradeThreshold {
  const trades = useExchangeStore((s) => s.trades[market]);
  return useMemo(() => {
    const sorted = (trades ?? [])
      .map((t) => toNum(t.volume))
      .filter((v) => v > 0)
      .sort((a, b) => a - b);
    const median = medianOf(sorted);
    return {
      threshold: median > 0 ? median * k : Infinity,
      isLarge: (volume) => isLargeVolume(toNum(volume), median, k),
    };
  }, [trades, k]);
}
