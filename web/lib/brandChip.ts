/**
 * 칩 배경은 브랜드 로고색(데이터)이라 앱 테마와 무관하고, 라벨 색은 배경 휘도가 정한다.
 * 대비가 높은 라벨을 고르고, 그래도 4.5:1에 못 미치면 라벨 반대 방향으로 배경 명도를 보정한다.
 * AVAX(#E84142)는 흰 3.99·검 4.41이라 라벨 선택만으로는 어느 쪽도 통과하지 못한다.
 */

/* 라벨 색 실값. 렌더는 var(--text-on-brand-*)로 하고 여기서는 대비 계산 입력으로만 쓴다.
   두 토큰은 테마 분기가 없는 고정값이라 복제해도 분기 드리프트가 없다.
   tokens.css §2의 --text-on-brand-light·--text-on-brand-dark와 값이 같아야 한다 */
const LABEL_LIGHT: RGB = [255, 255, 255]; // == --text-on-brand-light #FFFFFF
const LABEL_DARK: RGB = [22, 25, 31]; //    == --text-on-brand-dark  #16191F

/** 칩 모노그램은 10~12px이라 large-text 예외 밖. 소형 텍스트 기준을 적용한다 */
const MIN_RATIO = 4.5;

/** 보정 1회 이동폭. 작을수록 브랜드색에 가깝게 멈추고 반복만 늘어난다 */
const MIX_STEP = 0.02;

type RGB = [number, number, number];

export interface ChipStyle {
  /** 칩 배경. 대비 보정이 필요 없으면 입력 brandColor 그대로다 */
  background: string;
  /** true면 어두운 라벨(--text-on-brand-dark) */
  dark: boolean;
}

function parseHex(hex: string): RGB | null {
  const m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return null;
  const h = m[1].length === 3 ? m[1].replace(/./g, (c) => c + c) : m[1];
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

function toHex([r, g, b]: RGB): string {
  const p = (v: number) => Math.round(v).toString(16).padStart(2, "0");
  return `#${p(r)}${p(g)}${p(b)}`.toUpperCase();
}

function luminance([r, g, b]: RGB): number {
  const ch = (v: number) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
}

export function contrastRatio(a: RGB, b: RGB): number {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

function mix(from: RGB, to: RGB, t: number): RGB {
  return [
    from[0] + (to[0] - from[0]) * t,
    from[1] + (to[1] - from[1]) * t,
    from[2] + (to[2] - from[2]) * t,
  ];
}

/**
 * 브랜드색에 대한 칩 배경·라벨 결정.
 * 파싱 실패는 밝은 라벨로 떨어진다. 배경이 안 그려지는 것보다 낫고, 시드 오류는 따로 드러나야 한다.
 */
export function resolveChipStyle(brandColor: string): ChipStyle {
  const rgb = parseHex(brandColor);
  if (!rgb) return { background: brandColor, dark: false };

  const onLight = contrastRatio(rgb, LABEL_LIGHT);
  const onDark = contrastRatio(rgb, LABEL_DARK);
  const dark = onDark >= onLight;
  const label = dark ? LABEL_DARK : LABEL_LIGHT;

  if (Math.max(onLight, onDark) >= MIN_RATIO) return { background: brandColor, dark };

  // 라벨 반대 극(어두운 라벨이면 흰쪽)으로 배경을 밀어 4.5:1을 확보한다.
  // 대비가 높은 쪽 라벨을 이미 골랐으므로 필요한 이동폭이 최소가 된다.
  const target: RGB = dark ? LABEL_LIGHT : LABEL_DARK;
  for (let t = MIX_STEP; t <= 1; t += MIX_STEP) {
    const shifted = mix(rgb, target, t);
    if (contrastRatio(shifted, label) >= MIN_RATIO) {
      return { background: toHex(shifted), dark };
    }
  }
  // 라벨 극까지 밀어도 못 넘기는 색은 없다(흰·검 어느 쪽이든 반대 극과 21:1). 방어적 반환.
  return { background: toHex(target), dark };
}
