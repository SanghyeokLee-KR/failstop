"use client";

/**
 * 시장 생동감 제어(/admin/bots, 체결_시각효과 §3·봇_방향성 §5). 대수 set-point·방향 바이어스·
 * 자동균형·효과 강도를 한 벌로 튜닝한다. 전부 approval 불요·전량 감사(가역 연출 파라미터).
 * 값은 문자열로 다룬다(바이어스·강도는 분수라 float 표기 금지). 색은 시세 도메인(양전 Red·음전 Blue),
 * 색 단독 금지라 부호·화살표·수치를 병기한다.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { BOT_TYPES, BOT_TYPE_LABEL, FLEET_TYPE_CAP, FLEET_MARKET_CAP, TRADE_FLASH_VALUES } from "@/lib/admin/types";
import type { BotType, FleetScaleTargets, MarketSimState, SimStatus, TradeFlash } from "@/lib/admin/types";
import { COIN_META } from "@/lib/sim/engine";
import { formatCount } from "@/lib/format";
import PanelCard from "@/components/PanelCard";
import SegmentedTab from "@/components/SegmentedTab";
import { PermissionNote } from "@/components/admin/ui";
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

/** net bias 표기: 색 단독 금지 → 방향어·화살표·부호·수치 병기 */
function biasLabel(v: string): { text: string; dir: "up" | "down" | "flat" } {
  const n = Number(v);
  const mag = (Math.round(Math.abs(n) * 100) / 100).toFixed(2);
  if (n > 0.0001) return { text: `양전 ▲ +${mag}`, dir: "up" };
  if (n < -0.0001) return { text: `음전 ▼ −${mag}`, dir: "down" };
  return { text: `중립 ${mag}`, dir: "flat" };
}

function biasNorm(raw: string): string {
  return (Math.round(Number(raw) * 100) / 100).toFixed(2);
}

export default function LivelinessSection({ sim, canOps, onChanged }: Props) {
  const [market, setMarket] = useState(COIN_META[0]?.market ?? "KRW-BTC");
  const state: MarketSimState | undefined = useMemo(
    () => sim?.markets.find((m) => m.market === market),
    [sim, market],
  );

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
    const snap = sim?.markets.find((m) => m.market === market);
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
  const label = biasLabel(biasDraft);

  const applyScale = async () => {
    if (scaleOver) return;
    setScaleBusy(true);
    const targets: FleetScaleTargets = { ...scaleDraft };
    try {
      const res = await getAdminApi().setFleetScale(market, targets);
      if (!res.fleetPresent) {
        toast("플릿이 기동되지 않아 목표 대수만 기록했어요", "info");
      } else {
        toast(res.clamped ? "일부 값이 캡으로 조정되어 적용됐어요" : "봇 대수를 적용했어요", "info");
      }
      onChanged();
    } catch (e) {
      toast(e instanceof Error ? e.message : "대수 적용에 실패했어요", "error");
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
          ? `방향 바이어스를 적용했어요 (effective ${res.effectiveBias})`
          : `플릿이 기동되지 않아 방향 의도만 기록했어요 (effective ${res.effectiveBias})`,
        "info",
      );
      onChanged();
    } catch (e) {
      toast(e instanceof Error ? e.message : "바이어스 적용에 실패했어요", "error");
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
      toast(`효과 강도를 ${FLASH_LABEL[applied]}로 바꿨어요`, "info");
      onChanged();
    } catch (e) {
      setFlash(prev); // 직전 값으로 롤백
      toast(e instanceof Error ? e.message : "효과 강도 변경에 실패했어요", "error");
    } finally {
      flashPending.current = false;
    }
  };

  return (
    <PanelCard title="시장 생동감">
      {!canOps && <PermissionNote>생동감 제어 권한(BOT_OPS 이상)이 없어 조회만 가능합니다.</PermissionNote>}
      <p className={styles.livelinessNote}>
        방향·대수·자동균형·효과 강도는 가역 연출 파라미터입니다. 2인 승인 없이 즉시 반영되며 모든 변경은 감사 로그(admin_audit_log)에 남습니다.
      </p>

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

      {/* ===== 봇 타입별 대수 ===== */}
      <div className={styles.livelinessBlock}>
        <div className={styles.livelinessHead}>
          <h3 className={styles.livelinessTitle}>봇 타입별 대수</h3>
          <span className={styles.livelinessHint}>
            목표 대수(set-point). 서버가 현재 대수와 차이를 생성·제거로 수렴합니다.
          </span>
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
                  <span className={styles.scaleActive}>활성 {formatCount(cur?.active ?? 0)} / 목표 {formatCount(cur?.target ?? 0)}</span>
                </span>
              </div>
            );
          })}
        </div>
        <div className={styles.livelinessFoot}>
          <span className={styles.scaleSum} data-over={scaleOver || undefined}>
            합계 <b className="num">{formatCount(scaleSum)}</b> / 캡 {formatCount(FLEET_MARKET_CAP)}
          </span>
          {scaleOver && (
            <span className={styles.scaleErr} role="alert">
              마켓 합계 상한을 넘습니다. 조정 후 적용하세요.
            </span>
          )}
          <button type="button" className={styles.primaryBtn} disabled={!canOps || scaleBusy || scaleOver} onClick={() => void applyScale()}>
            {scaleBusy ? "적용 중" : "대수 적용"}
          </button>
        </div>
      </div>

      {/* ===== 방향 바이어스 ===== */}
      <div className={styles.livelinessBlock}>
        <div className={styles.livelinessHead}>
          <h3 className={styles.livelinessTitle}>방향 바이어스</h3>
          <span className={styles.livelinessHint}>양전(▲ 상승 압력)~중립~음전(▼ 하락 압력). 실측·유효값으로 수렴을 확인합니다.</span>
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
        </div>
        <div className={styles.biasMeta}>
          <span>
            실측 <b className="num">{state?.measuredNetBias ?? "0.00"}</b>
          </span>
          <span>
            유효 <b className="num">{state?.effectiveBias ?? "0.00"}</b>
          </span>
          <span>
            체결률 <b className="num">{state?.fillRatio ?? "0.00"}</b>
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

      {/* ===== 효과 강도 ===== */}
      <div className={styles.livelinessBlock}>
        <div className={styles.livelinessHead}>
          <h3 className={styles.livelinessTitle}>체결 시각효과 강도</h3>
          <span className={styles.livelinessHint}>트레이딩 화면 팝·플래시 강도. 자동은 체결률 거버너가 상한을 하향 조절합니다.</span>
        </div>
        <SegmentedTab
          items={TRADE_FLASH_VALUES.map((v) => ({ value: v, label: FLASH_LABEL[v] }))}
          value={flash}
          onChange={(v) => void applyFlash(v)}
          variant="neutral"
          ariaLabel="체결 시각효과 강도"
          disabled={!canOps}
        />
        <div className={styles.flashPreview} aria-hidden="true" data-level={flash}>
          <span className={styles.flashCell} data-dir="up">
            ▲ +0.42%
          </span>
          <span className={styles.flashCell} data-dir="down">
            ▼ −0.31%
          </span>
          <span className={styles.flashCell} data-dir="flat">
            0.00%
          </span>
        </div>
      </div>
    </PanelCard>
  );
}
