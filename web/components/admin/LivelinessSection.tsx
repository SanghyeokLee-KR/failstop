"use client";

/**
 * 전부 approval 불요·전량 감사인 가역 연출 파라미터다.
 * 값은 문자열로 다룬다(바이어스·강도는 분수라 float 표기 금지). 색은 시세 도메인에 부호·화살표를 병기한다.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { BOT_TYPES, BOT_TYPE_LABEL, FLEET_TYPE_CAP, FLEET_MARKET_CAP, TRADE_FLASH_VALUES } from "@/lib/admin/types";
import type { BotType, FleetScaleTargets, MarketSimState, SimStatus, TradeFlash } from "@/lib/admin/types";
import { COIN_META } from "@/lib/sim/engine";
import { formatCount, formatNetBias } from "@/lib/format";
import PanelCard from "@/components/PanelCard";
import { PermissionNote, Segmented } from "@/components/admin/ui";
import { MeterRail } from "@/components/admin/instruments/MeterRail";
import { BiasReadout } from "@/components/admin/instruments/BiasReadout";
import { toast } from "@/components/Toast";
import styles from "@/components/admin/admin.module.css";

const FLASH_LABEL: Record<TradeFlash, string> = {
  off: "끔",
  subtle: "약",
  normal: "보통",
  strong: "강",
  auto: "자동",
};

interface Props {
  sim: SimStatus | null;
  canOps: boolean;
  onChanged: () => void;
}

function biasNorm(raw: string): string {
  return (Math.round(Number(raw) * 100) / 100).toFixed(2);
}

export default function LivelinessSection({ sim, canOps, onChanged }: Props) {
  const [market, setMarket] = useState(COIN_META[0]?.market ?? "KRW-BTC");
  const state: MarketSimState | undefined = useMemo(
    () => sim?.markets?.find((m) => m.market === market),
    [sim, market],
  );
  // 미보고와 "이 마켓에 봇이 없음"은 다르다. 미보고를 0.00 으로 그리면 중립 바이어스로 읽힌다
  const unknown = sim != null && sim.markets == null;

  // 대수·바이어스는 로컬 드래프트(낙관 편집). 마켓 전환 시에만 서버 스냅샷으로 시딩한다
  const [scaleDraft, setScaleDraft] = useState<Record<BotType, number>>({ MARKET_MAKER: 0, TAKER: 0, TREND: 0, NOISE: 0 });
  const [biasDraft, setBiasDraft] = useState("0.00");
  const [autoDraft, setAutoDraft] = useState(false);
  const [strengthDraft, setStrengthDraft] = useState("0.50");
  const [flash, setFlash] = useState<TradeFlash>("normal");
  const [scaleBusy, setScaleBusy] = useState(false);
  const [biasBusy, setBiasBusy] = useState(false);
  // 시딩 추적: 마켓 전환 시, 그리고 해당 마켓의 실데이터가 처음 도착할 때 1회 시딩한다.
  // 이후 폴링 갱신은 드래프트를 덮지 않는다(사용자 편집 보존).
  const seedRef = useRef<{ market: string; withData: boolean }>({ market: "", withData: false });
  const flashPending = useRef(false);

  useEffect(() => {
    const snap = sim?.markets?.find((m) => m.market === market);
    const prev = seedRef.current;
    const marketChanged = prev.market !== market;
    if (!marketChanged && (prev.withData || !snap)) return;
    setScaleDraft({
      MARKET_MAKER: snap?.scale.MARKET_MAKER?.target ?? 0,
      TAKER: snap?.scale.TAKER?.target ?? 0,
      TREND: snap?.scale.TREND?.target ?? 0,
      NOISE: snap?.scale.NOISE?.target ?? 0,
    });
    setBiasDraft(snap?.targetNetBias ?? "0.00");
    setAutoDraft(snap?.autoBalance.enabled ?? false);
    setStrengthDraft(snap?.autoBalance.strength ?? "0.50");
    seedRef.current = { market, withData: !!snap };
  }, [market, sim]);

  useEffect(() => {
    // 낙관 반영 중에는 폴링 값으로 되돌리지 않는다(적용 확정 후 재개)
    if (sim && !flashPending.current) setFlash(sim.tradeFlash);
  }, [sim]);

  const scaleSum = BOT_TYPES.reduce((n, t) => n + (scaleDraft[t] ?? 0), 0);
  const scaleOver = scaleSum > FLEET_MARKET_CAP;
  const label = formatNetBias(biasDraft);

  const applyScale = async () => {
    if (scaleOver) return;
    setScaleBusy(true);
    const targets: FleetScaleTargets = { ...scaleDraft };
    try {
      const res = await getAdminApi().setFleetScale(market, targets);
      if (!res.fleetPresent) {
        toast("플릿 미기동. 목표 대수만 기록", "info");
      } else {
        toast(res.clamped ? "일부 값이 캡으로 조정돼 적용됨" : "봇 대수 적용 완료", "info");
      }
      onChanged();
    } catch (e) {
      toast(e instanceof Error ? e.message : "대수 적용 실패", "error");
    } finally {
      setScaleBusy(false);
    }
  };

  const applyBias = async () => {
    setBiasBusy(true);
    try {
      const res = await getAdminApi().setDirectionBias(market, biasNorm(biasDraft), autoDraft, biasNorm(strengthDraft));
      toast(
        res.fleetPresent
          ? `방향 바이어스 적용 완료. 유효 ${res.effectiveBias}`
          : `플릿 미기동. 방향 의도만 기록. 유효 ${res.effectiveBias}`,
        "info",
      );
      onChanged();
    } catch (e) {
      toast(e instanceof Error ? e.message : "바이어스 적용 실패", "error");
    } finally {
      setBiasBusy(false);
    }
  };

  const applyFlash = async (next: TradeFlash) => {
    const prev = flash;
    flashPending.current = true;
    setFlash(next); // 낙관 반영
    try {
      const applied = await getAdminApi().setEffectConfig(next);
      setFlash(applied);
      toast(`효과 강도 ${FLASH_LABEL[applied]} 적용 완료`, "info");
      onChanged();
    } catch (e) {
      setFlash(prev); // 직전 값으로 롤백
      toast(e instanceof Error ? e.message : "효과 강도 변경 실패", "error");
    } finally {
      flashPending.current = false;
    }
  };

  return (
    <PanelCard title="시장 생동감">
      {!canOps && <PermissionNote>생동감 제어 권한(BOT_OPS 이상) 없음. 조회만 가능</PermissionNote>}
      <label className={styles.field}>
        <span>대상 마켓</span>
        <select value={market} onChange={(e) => setMarket(e.target.value)} aria-label="생동감 대상 마켓">
          {COIN_META.map((c) => (
            <option key={c.market} value={c.market}>
              {c.koreanName} ({c.market})
            </option>
          ))}
        </select>
      </label>

      <div className={styles.livelinessBlock}>
        <div className={styles.livelinessHead}>
          <h3 className={styles.livelinessTitle}>봇 타입별 대수</h3>
          <span className={styles.livelinessHint}>서버가 목표 대수로 자동 수렴 · 적용 버튼으로 반영</span>
        </div>
        <div className={styles.scaleGrid}>
          {BOT_TYPES.map((t) => {
            const cur = state?.scale[t];
            return (
              <div key={t} className={styles.scaleRow}>
                <span className={styles.scaleLabel}>{BOT_TYPE_LABEL[t]}</span>
                <input
                  type="range"
                  min={0}
                  max={FLEET_TYPE_CAP}
                  step={1}
                  value={scaleDraft[t]}
                  disabled={!canOps}
                  aria-label={`${BOT_TYPE_LABEL[t]} 목표 대수`}
                  onChange={(e) => setScaleDraft((d) => ({ ...d, [t]: Number(e.target.value) }))}
                />
                <span className={`${styles.scaleValue} num`}>
                  <b>{formatCount(scaleDraft[t])}</b>
                  <span className={styles.scaleActive}>
                    {unknown
                      ? "활성 미상 / 목표 미상"
                      : `활성 ${formatCount(cur?.active ?? 0)} / 목표 ${formatCount(cur?.target ?? 0)}`}
                  </span>
                </span>
              </div>
            );
          })}
        </div>
        <div className={styles.livelinessFoot}>
          <span className={styles.scaleSum} data-over={scaleOver || undefined}>
            합계 <b className="num">{formatCount(scaleSum)}</b> / 캡 {formatCount(FLEET_MARKET_CAP)}
          </span>
          <span className={styles.scaleSumRailSlot}>
            {/* 합계·캡 수치는 좌측 텍스트가 낭독하므로 레일은 aria-hidden */}
            <MeterRail
              value={scaleSum}
              max={FLEET_MARKET_CAP}
              threshold={FLEET_MARKET_CAP}
              tone={scaleOver ? "danger" : undefined}
            />
          </span>
          {scaleOver && (
            <span className={styles.scaleErr} role="alert">
              마켓 합계 상한 초과. 조정 후 적용 필요
            </span>
          )}
          <button type="button" className={styles.primaryBtn} disabled={!canOps || scaleBusy || scaleOver} onClick={() => void applyScale()}>
            {scaleBusy ? "적용 중" : "대수 적용"}
          </button>
        </div>
      </div>

      <div className={styles.livelinessBlock}>
        <div className={styles.livelinessHead}>
          <h3 className={styles.livelinessTitle}>방향 바이어스</h3>
          <span className={styles.livelinessHint}>적용 버튼으로 반영 · 실측·유효로 수렴 확인</span>
        </div>
        <div className={styles.biasRow}>
          <input
            type="range"
            className={styles.biasTrack}
            min={-1}
            max={1}
            step={0.05}
            value={Number(biasDraft)}
            disabled={!canOps}
            aria-label="목표 방향 바이어스"
            onChange={(e) => setBiasDraft(biasNorm(e.target.value))}
          />
          <span className={`${styles.biasValue} num`} data-dir={label.dir}>
            {label.text}
          </span>
          {/* 판독 레일. 마커 형태는 아래 biasMeta 라벨의 형태 문자가 범례를 겸한다 */}
          <div className={styles.biasReadoutSlot}>
            {/* 미보고에 레일을 그리면 마커가 중립 위치에 서서 실측 0.00 으로 읽힌다 */}
            {unknown ? (
              <p className={styles.formHelp}>봇 노드 상태 미보고 · 판독 불가</p>
            ) : (
              <BiasReadout
                target={state?.targetNetBias ?? "0.00"}
                measured={state?.measuredNetBias ?? "0.00"}
                effective={state?.effectiveBias ?? "0.00"}
              />
            )}
          </div>
        </div>
        <div className={styles.biasMeta}>
          <span>
            ▽ 목표 <b className="num">{unknown ? "미상" : (state?.targetNetBias ?? "0.00")}</b>
          </span>
          <span>
            ▲ 실측 <b className="num">{unknown ? "미상" : (state?.measuredNetBias ?? "0.00")}</b>
          </span>
          <span>
            | 유효 <b className="num">{unknown ? "미상" : (state?.effectiveBias ?? "0.00")}</b>
          </span>
          <span>
            체결률 <b className="num">{unknown ? "미상" : (state?.fillRatio ?? "0.00")}</b>
          </span>
        </div>
        <div className={styles.biasControls}>
          <label className={styles.toggleField}>
            <input type="checkbox" checked={autoDraft} disabled={!canOps} onChange={(e) => setAutoDraft(e.target.checked)} />
            <span>자동 균형</span>
          </label>
          <label className={styles.field} data-dim={autoDraft ? undefined : true}>
            <span>강도 {biasNorm(strengthDraft)}</span>
            <input
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={Number(strengthDraft)}
              disabled={!canOps || !autoDraft}
              aria-label="자동 균형 강도"
              onChange={(e) => setStrengthDraft(biasNorm(e.target.value))}
            />
          </label>
          <button type="button" className={styles.primaryBtn} disabled={!canOps || biasBusy} onClick={() => void applyBias()}>
            {biasBusy ? "적용 중" : "바이어스 적용"}
          </button>
        </div>
      </div>

      <div className={styles.livelinessBlock}>
        <div className={styles.livelinessHead}>
          <h3 className={styles.livelinessTitle}>체결 시각효과 강도</h3>
          <span className={styles.livelinessHint}>선택 즉시 반영 · 자동은 체결률에 따라 상한 조절</span>
        </div>
        <Segmented
          items={TRADE_FLASH_VALUES.map((v) => ({ value: v, label: FLASH_LABEL[v] }))}
          value={flash}
          onChange={(v) => void applyFlash(v)}
          semantics="pressed"
          ariaLabel="체결 시각효과 강도"
          disabled={!canOps}
        />
        <div className={styles.flashPreview} aria-hidden="true" data-level={flash}>
          <span className={styles.flashCell} data-dir="up">
            <span className={styles.flashTint} />
            <span className={styles.flashText}>▲ +0.42%</span>
          </span>
          <span className={styles.flashCell} data-dir="down">
            <span className={styles.flashTint} />
            <span className={styles.flashText}>▼ −0.31%</span>
          </span>
          <span className={styles.flashCell} data-dir="flat">
            <span className={styles.flashText}>0.00%</span>
          </span>
          <span className={styles.flashAutoNote}>자동</span>
        </div>
      </div>
    </PanelCard>
  );
}
