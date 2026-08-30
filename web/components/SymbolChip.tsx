/**
 * 심볼칩. 코인 브랜드 로고색 칩(하드코딩 hex 예외가 허용되는 유일한 데이터성 색상).
 * 색은 COIN_META(brandColor)에서 주입하고, 라벨 명암과 배경 보정은 resolveChipStyle이 계산한다.
 */

import { useMemo } from "react";
import type { CoinMeta } from "@/lib/types";
import { resolveChipStyle } from "@/lib/brandChip";

interface Props {
  meta: Pick<CoinMeta, "symbol" | "brandColor"> | undefined;
  size?: "md" | "sm";
}

export default function SymbolChip({ meta, size = "md" }: Props) {
  const brandColor = meta?.brandColor;
  // 마켓 목록·랭킹은 칩을 행마다 그린다. 브랜드색은 시드 상수라 색당 1회만 계산하면 된다
  const chip = useMemo(() => (brandColor ? resolveChipStyle(brandColor) : null), [brandColor]);

  if (!meta || !chip) {
    return <span className={`sym-chip${size === "sm" ? " sym-chip--sm" : ""}`} aria-hidden="true" />;
  }
  return (
    <span
      className={`sym-chip${size === "sm" ? " sym-chip--sm" : ""}`}
      style={{
        background: chip.background,
        color: chip.dark ? "var(--text-on-brand-dark)" : "var(--text-on-brand-light)",
      }}
      aria-hidden="true"
    >
      {meta.symbol}
    </span>
  );
}
