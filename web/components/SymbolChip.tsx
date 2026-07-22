/**
 * 심볼칩. 코인 브랜드 로고색 칩 (하드코딩 hex 예외가 허용되는 유일한 "데이터성 색상").
 * 색은 lib/sim/engine의 COIN_META(brandColor)에서 주입한다.
 */

import type { CoinMeta } from "@/lib/types";

interface Props {
  meta: Pick<CoinMeta, "symbol" | "brandColor" | "brandTextDark"> | undefined;
  size?: "md" | "sm";
}

export default function SymbolChip({ meta, size = "md" }: Props) {
  if (!meta) return <span className={`sym-chip${size === "sm" ? " sym-chip--sm" : ""}`} aria-hidden="true" />;
  return (
    <span
      className={`sym-chip${size === "sm" ? " sym-chip--sm" : ""}`}
      style={{
        background: meta.brandColor,
        color: meta.brandTextDark ? "#16191F" : "#FFFFFF",
      }}
      aria-hidden="true"
    >
      {meta.symbol}
    </span>
  );
}
