"use client";

/**
 * useEffectIntensity. 스토어의 효과 강도 config를 셀렉터로 구독한다(체결_시각효과 설계 §2-6).
 * 셀·패널은 이 훅으로 현재 mode·level을 읽는다. config 미수신 시 스토어 기본값(auto·normal).
 */

import type { EffectConfig } from "./types";
import { useExchangeStore } from "./store";

export function useEffectIntensity(): EffectConfig {
  return useExchangeStore((s) => s.effectConfig);
}
