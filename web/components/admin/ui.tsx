"use client";

/**
 * 관리 평면 공용 부품(디자인 시스템 §12-14 부품 한 벌). 화면 머리, 판독 묶음(StatStrip·StatTile), 상태 칩(StateChip),
 * 분절 조작(Segmented)이다. 헬스 색 도메인(success/warning/danger)만 쓰고 시세색을 차용하지 않는다.
 * 상태 표현은 색 단독 금지라 형태 기호와 낱말을 늘 함께 둔다(§4-3).
 */

import { useRef, type ReactNode } from "react";
import styles from "./admin.module.css";
import controlStyles from "./control/control.module.css";
import { MeterRail, type MeterMark } from "./instruments/MeterRail";
import StatusShape, { type StatusShapeKind } from "./StatusShape";

export type Health = "success" | "warning" | "danger";

// 색약·모노 변별을 위해 심각도별 형태 분리(success 원·warning 삼각·danger 사각). 색은 보조 채널
export const HEALTH_ICON: Record<Health, string> = { success: "●", warning: "▲", danger: "■" };
const HEALTH_SHAPE: Record<Health, StatusShapeKind> = { success: "circle", warning: "triangle", danger: "square" };
// 형태·색은 aria-hidden이라 보조기술에 심각도가 안 닿는다. sr-only로 낭독할 라벨
const HEALTH_LABEL: Record<Health, string> = { success: "정상", warning: "주의", danger: "장애" };

export type ChipTone = Health | "neutral" | "off";

/**
 * 상태 칩(§12-14). 네모 칩 하나에 형태 기호와 낱말을 싣는다. 정상은 면과 테두리 없이 초록 형태와 잉크 글자다.
 * 이 콘솔은 대부분 정상이라 정상까지 칠하면 초록으로 덮인 판에서 주의 하나를 찾아야 한다
 */
export function StateChip({ tone, label, shape }: { tone: ChipTone; label: ReactNode; shape?: StatusShapeKind }) {
  const kind = shape ?? (tone === "neutral" || tone === "off" ? (tone === "off" ? "ring" : undefined) : HEALTH_SHAPE[tone]);
  return (
    <span className={styles.stateChip} data-tone={tone}>
      {kind && <StatusShape kind={kind} tone={tone} />}
      {label}
    </span>
  );
}

/** StatTile 보조 게이지. tone은 별도 필드 없이 칸의 tone을 상속한다 */
export interface StatTileMeter {
  value: number;
  max: number;
  min?: number;
  threshold?: number;
  marks?: MeterMark[];
}

/**
 * 판독 묶음(§12-14). 한 헤어라인 상자를 세로·가로 헤어라인으로 가른 칸들이다. 같은 모양의 카드를 여러 장
 * 늘어놓지 않는다. 여섯 칸은 상자 폭이 1200px 이상이면 한 줄, 그보다 좁으면 세 칸 두 줄이다(레이아웃 §7)
 */
export function StatStrip({ label, children, count }: { label: string; children: ReactNode; count: number }) {
  return (
    <div className={styles.statStripWrap}>
      <div className={styles.statStrip} data-cells={count} role="group" aria-label={label}>
        {children}
      </div>
    </div>
  );
}

/**
 * 판독 칸. 이름, 값과 흐린 단위, 보조 줄이다. 색 단독 금지라 tone이 있으면 값 앞에 형태 기호를 둔다.
 * hero는 화면당 대표 지표 하나에만 쓴다. 멱등 위반 같은 장애(alarm)만 칸 면을 칠한다
 */
export function StatTile({
  label,
  value,
  sub,
  tone,
  hero,
  alarm,
  unit,
  meter,
  title,
}: {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  tone?: Health;
  hero?: boolean;
  alarm?: boolean;
  unit?: string;
  meter?: StatTileMeter;
  /** 라벨 원어 gloss. 평이한 한국어 라벨 뒤로 강등된 기술 용어를 싣는다 */
  title?: string;
}) {
  // 색·형태로만 전달되던 심각도를 낭독한다. alarm은 tone 미지정 시 warning 승격과 동일하게 맞춘다
  const severity = tone ?? (alarm ? "warning" : undefined);
  return (
    <div className={styles.statTile} data-alarm={alarm ? tone ?? "warning" : undefined} title={title}>
      <span className={styles.statLabel}>{label}</span>
      <span className={`${styles.statValue} num`} data-tone={tone ?? undefined} data-hero={hero ? "" : undefined}>
        {tone && tone !== "success" && <StatusShape kind={HEALTH_SHAPE[tone]} tone={tone} className={styles.statShape} />}
        {severity && <span className="sr-only">심각도 {HEALTH_LABEL[severity]} </span>}
        {value}
        {unit && <span className={styles.statUnit}>{unit}</span>}
      </span>
      {sub != null && <span className={styles.statSub}>{sub}</span>}
      {meter && (
        // 값·라벨 텍스트가 이미 낭독되므로 레일은 aria-hidden(ariaLabel 미지정)
        <span className={styles.statMeter}>
          <MeterRail value={meter.value} max={meter.max} min={meter.min} threshold={meter.threshold} marks={meter.marks} tone={tone === "success" ? undefined : tone} />
        </span>
      )}
    </div>
  );
}

export interface SegmentedItem<T extends string> {
  value: T;
  label: ReactNode;
}

/**
 * 분절 조작(§12-14). 판을 가르는 필터와 상세의 탭은 탭 의미(화살표 키로 옮기고 고른 칸만 Tab 정지), 값을 고르는
 * 스위치는 눌림 의미(aria-pressed)다. 모양은 관제 화면의 분절 한 벌(32px, 거르기는 28px)이다
 */
export function Segmented<T extends string>({
  items,
  value,
  onChange,
  ariaLabel,
  semantics = "tabs",
  size,
  panelId,
  disabled = false,
}: {
  items: SegmentedItem<T>[];
  value: T;
  onChange: (value: T) => void;
  ariaLabel: string;
  semantics?: "tabs" | "pressed";
  size?: "sm" | "xs";
  /** 탭이 여닫는 패널의 id. 고른 탭이 aria-controls로 패널을 가리키고 패널은 segmentedTabId로 되짚는다 */
  panelId?: string;
  disabled?: boolean;
}) {
  const refs = useRef<Array<HTMLButtonElement | null>>([]);
  const tabs = semantics === "tabs";
  const current = Math.max(0, items.findIndex((it) => it.value === value));
  const onKeyDown = (e: React.KeyboardEvent, i: number) => {
    if (!tabs || disabled) return;
    let next = -1;
    if (e.key === "ArrowRight" || e.key === "ArrowDown") next = (i + 1) % items.length;
    else if (e.key === "ArrowLeft" || e.key === "ArrowUp") next = (i - 1 + items.length) % items.length;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = items.length - 1;
    else return;
    e.preventDefault();
    // 상세의 탭 목록 안에서 ←·→가 이웃 노드로 새지 않게 여기서 멈춘다
    e.stopPropagation();
    refs.current[next]?.focus();
    onChange(items[next].value);
  };
  return (
    <span className={controlStyles.switch} role={tabs ? "tablist" : "group"} aria-label={ariaLabel}>
      {items.map((it, i) => {
        const on = it.value === value;
        return (
          <button
            key={it.value}
            ref={(el) => {
              refs.current[i] = el;
            }}
            type="button"
            className={controlStyles.switchBtn}
            data-size={size}
            disabled={disabled}
            role={tabs ? "tab" : undefined}
            id={tabs && panelId ? segmentedTabId(panelId, it.value) : undefined}
            aria-selected={tabs ? on : undefined}
            aria-controls={tabs && panelId && on ? panelId : undefined}
            aria-pressed={tabs ? undefined : on}
            tabIndex={tabs ? (i === current ? 0 : -1) : undefined}
            onClick={() => onChange(it.value)}
            onKeyDown={(e) => onKeyDown(e, i)}
          >
            {it.label}
          </button>
        );
      })}
    </span>
  );
}

/** Segmented가 탭마다 다는 id. 패널의 aria-labelledby가 같은 규칙으로 찾는다 */
export function segmentedTabId(panelId: string, value: string): string {
  return `${panelId}-tab-${value}`;
}

export type SimMarkKind = "sim" | "assumed" | "replay";

const SIM_MARK: Record<SimMarkKind, { text: string; sr: string }> = {
  sim: { text: "시뮬레이션", sr: "값은 브라우저 시뮬레이터가 만든 것입니다" },
  assumed: { text: "시뮬레이션 가정", sr: "구축 전이라 연결과 값은 시뮬레이터의 가정입니다" },
  replay: { text: "재생 · 관제 값과 별개", sr: "로컬 Docker 측정 기록의 사건 순서를 재생하고 사이 값은 지어낸 것이라 관제센터의 값과 다릅니다" },
};

/**
 * 값의 출처 표식 하나(디자인 시스템 §12-12). 화면마다 머리에 한 번 둔다. 구축 전 ai-1은 '시뮬레이션 가정',
 * 로컬 측정 기록 재생은 관제 판과 다른 세계라 '재생 · 관제 값과 별개'를 크게 적는다.
 *
 * 보조기술용 설명은 표식 밖의 형제로 둔다. 표식 안에 두면 가려진 글의 줄 상자가 표식 아래로 삐져나가 좁은 줄
 * (인프라 카드 머리)에서 감김으로 잡히고, 보이는 표식은 설명과 같은 뜻이라 낭독에서 뺀다
 */
export function SimMark({ kind = "sim", short }: { kind?: SimMarkKind; short?: string }) {
  const m = SIM_MARK[kind];
  // 좁은 카드(서비스 흐름의 ai-1, 112px)에서는 줄인 낱말만 보이고 원문은 툴팁과 보조기술 설명이 싣는다
  return (
    <>
      <span className={styles.simMark} data-kind={kind} aria-hidden="true" title={short ? m.text : undefined}>
        {kind === "replay" && <StatusShape kind="play" size={9} />}
        {short ?? m.text}
      </span>
      <span className="sr-only">
        {m.text} · {m.sr}
      </span>
    </>
  );
}

/** 화면 머리(§12-14). 제목과 출처 표식, 설명 한 줄, 오른쪽의 판독과 조작. 관제 화면의 OpsHead와 같은 뼈대다 */
export function PageHeader({ title, desc, actions, mark }: { title: string; desc?: ReactNode; actions?: ReactNode; mark?: ReactNode }) {
  return (
    <header className={styles.pageHead}>
      <div>
        <div className={styles.titleRow}>
          <h1 className={styles.pageTitle}>{title}</h1>
          {mark}
        </div>
        {desc && <p className={styles.pageDesc}>{desc}</p>}
      </div>
      {actions != null && <div className={styles.pageActions}>{actions}</div>}
    </header>
  );
}

/** 권한 부족 안내(읽기는 허용, 변경 버튼만 잠길 때 섹션 상단에 병기) */
export function PermissionNote({ children }: { children: ReactNode }) {
  return (
    <p className={styles.permNote} role="note">
      {children}
    </p>
  );
}
