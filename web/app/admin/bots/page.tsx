"use client";

/**
 * 봇 플릿(/admin/bots). start/stop/pause·OPS·생성은 BOT_OPS 즉시 실행(가역).
 * 실행 중 파라미터 변경은 파괴 액션으로 2인 승인 필수(§2-1, §3-2).
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import { BOT_TYPES, BOT_TYPE_LABEL } from "@/lib/admin/types";
import type { Bot, BotType, DestructiveAction, SimStatus } from "@/lib/admin/types";
import { COIN_META } from "@/lib/sim/engine";
import { formatCount } from "@/lib/format";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import RequestButton from "@/components/admin/RequestButton";
import ApprovalList from "@/components/admin/ApprovalList";
import LivelinessSection from "@/components/admin/LivelinessSection";
import { HealthPill, PageHeader, PermissionNote, StatTile } from "@/components/admin/ui";
import { toast } from "@/components/Toast";
import styles from "@/components/admin/admin.module.css";

const PARAM_ACTIONS: DestructiveAction[] = ["sim.bots.params"];

export default function BotsPage() {
  const canOps = useCan("BOT_OPS");
  const [sim, setSim] = useState<SimStatus | null>(null);
  const [newType, setNewType] = useState<BotType>("MARKET_MAKER");
  const [newMarket, setNewMarket] = useState(COIN_META[0]?.market ?? "KRW-BTC");

  const [paramBotId, setParamBotId] = useState("");
  const [spread, setSpread] = useState("0.0008");
  const [size, setSize] = useState("0.02");
  const [ttl, setTtl] = useState("1500");

  const load = useCallback(async () => {
    try {
      setSim(await getAdminApi().getSimStatus());
    } catch {
      // 세션 게이트 처리
    }
  }, []);

  useEffect(() => {
    void load();
    const iv = setInterval(() => void load(), 2000);
    return () => clearInterval(iv);
  }, [load]);

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      toast(ok, "info");
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : "처리에 실패했어요", "error");
    }
  };

  const api = getAdminApi();
  const paramActions = useMemo(() => PARAM_ACTIONS, []);

  const botCols: DataColumn<Bot>[] = [
    { key: "id", label: "봇 ID", width: "minmax(140px, 1.2fr)", render: (b) => b.id },
    { key: "type", label: "유형", width: "110px", render: (b) => BOT_TYPE_LABEL[b.type] },
    { key: "market", label: "마켓", width: "110px", render: (b) => b.market },
    {
      key: "active",
      label: "상태",
      width: "96px",
      render: (b) => (b.active ? <HealthPill health="success" label="가동" /> : <span className={styles.neutralPill}>정지</span>),
    },
    { key: "ops", label: "OPS", width: "80px", align: "right", render: (b) => b.ops },
    {
      key: "params",
      label: "파라미터",
      width: "minmax(160px, 1fr)",
      render: (b) => (
        <span className={styles.paramCell}>
          spread {b.params.spread} · size {b.params.size} · ttl {b.params.ttl_ms}
        </span>
      ),
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader title="봇 플릿" desc="시뮬레이션 봇 제어. 파라미터 변경은 2인 승인이 필요합니다." />

      {!sim ? (
        <div className={styles.kpiGrid}>
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} variant="card" width="100%" height={96} />
          ))}
        </div>
      ) : (
        <div className={styles.kpiGrid}>
          <div className={styles.statTile}>
            <span className={styles.statLabel}>플릿 상태</span>
            <span className={styles.statPillWrap}>
              {sim.running ? (
                sim.paused ? (
                  <HealthPill health="warning" label="일시정지" />
                ) : (
                  <HealthPill health="success" label="가동 중" />
                )
              ) : (
                <span className={styles.neutralPill}>정지</span>
              )}
            </span>
          </div>
          <StatTile label="현재 OPS · 목표" value={formatCount(sim.currentOps)} sub={`목표 ${formatCount(sim.rate)}`} />
          <StatTile label="활성 봇" value={`${sim.activeBots} / ${sim.botCount}`} sub="활성 / 전체" />
          <StatTile label="성공률" value={`${(Number(sim.successRate) * 100).toFixed(2)}%`} tone="success" />
          <StatTile label="평균 지연" value={`${sim.avgLatencyMs}ms`} />
          <StatTile
            label="중복 체결"
            value={sim.duplicateFillTotal}
            sub="duplicate_fill_total"
            tone={sim.duplicateFillTotal === 0 ? "success" : "danger"}
          />
        </div>
      )}

      <PanelCard title="플릿 제어">
        {!canOps && <PermissionNote>봇 제어 권한(BOT_OPS 이상)이 없어 조회만 가능합니다.</PermissionNote>}
        <div className={styles.controlRow}>
          <button type="button" className={styles.controlBtn} disabled={!canOps} onClick={() => void run(() => api.simStart(), "플릿을 시작했어요")}>
            시작
          </button>
          <button type="button" className={styles.controlBtn} disabled={!canOps} onClick={() => void run(() => api.simPause(), "일시정지를 전환했어요")}>
            일시정지 전환
          </button>
          <button type="button" className={styles.controlBtn} disabled={!canOps} onClick={() => void run(() => api.simStop(), "플릿을 정지했어요")}>
            정지
          </button>
          {sim && (
            <label className={styles.rateField}>
              <span>전역 OPS</span>
              <input
                type="range"
                min={0}
                max={600}
                step={10}
                defaultValue={sim.rate}
                disabled={!canOps}
                aria-label="전역 OPS 목표"
                onChange={(e) => void run(() => api.simSetRate(Number(e.target.value)), `OPS 목표를 ${e.target.value}로 바꿨어요`)}
              />
              <b className="num">{formatCount(sim.rate)}</b>
            </label>
          )}
        </div>
      </PanelCard>

      <LivelinessSection sim={sim} canOps={canOps} onChanged={load} />

      <PanelCard title="봇 생성">
        {!canOps && <PermissionNote>봇 생성 권한(BOT_OPS 이상)이 없습니다.</PermissionNote>}
        <div className={styles.formRow}>
          <label className={styles.field}>
            <span>유형</span>
            <select value={newType} onChange={(e) => setNewType(e.target.value as BotType)} disabled={!canOps} aria-label="봇 유형">
              {BOT_TYPES.map((t) => (
                <option key={t} value={t}>
                  {BOT_TYPE_LABEL[t]}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.field}>
            <span>마켓</span>
            <select value={newMarket} onChange={(e) => setNewMarket(e.target.value)} disabled={!canOps} aria-label="봇 마켓">
              {COIN_META.map((c) => (
                <option key={c.market} value={c.market}>
                  {c.koreanName} ({c.market})
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className={styles.primaryBtn}
            disabled={!canOps}
            onClick={() => void run(() => api.createBot(newType, newMarket), "봇을 생성했어요")}
          >
            생성
          </button>
        </div>
      </PanelCard>

      <PanelCard title="봇 목록">
        {!sim ? (
          <Skeleton variant="card" width="100%" height={200} />
        ) : (
          <DataTable columns={botCols} rows={sim.bots} rowKey={(b) => b.id} rowHeight={44} ariaLabel="봇 목록" />
        )}
      </PanelCard>

      <PanelCard title="파라미터 변경 요청 (2인 승인)">
        <PermissionNote>실행 중 파라미터 변경은 파괴 액션입니다. 요청 후 다른 운영자의 승인과 요청자 실행이 필요합니다.</PermissionNote>
        <div className={styles.formRow}>
          <label className={styles.field}>
            <span>대상 봇</span>
            <select value={paramBotId} onChange={(e) => setParamBotId(e.target.value)} aria-label="대상 봇">
              <option value="">봇 선택</option>
              {sim?.bots.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.id} · {BOT_TYPE_LABEL[b.type]} {b.market}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.field}>
            <span>spread</span>
            <input value={spread} onChange={(e) => setSpread(e.target.value)} inputMode="decimal" aria-label="spread" />
          </label>
          <label className={styles.field}>
            <span>size</span>
            <input value={size} onChange={(e) => setSize(e.target.value)} inputMode="decimal" aria-label="size" />
          </label>
          <label className={styles.field}>
            <span>ttl_ms</span>
            <input value={ttl} onChange={(e) => setTtl(e.target.value)} inputMode="numeric" aria-label="ttl_ms" />
          </label>
          {paramBotId ? (
            <RequestButton
              action="sim.bots.params"
              payload={{ action: "sim.bots.params", botId: paramBotId, params: { spread, size, ttl_ms: ttl } }}
              label="변경 요청"
              onRequested={load}
            />
          ) : (
            <button type="button" className={styles.dangerBtn} disabled>
              변경 요청
            </button>
          )}
        </div>
        <ApprovalList actions={paramActions} onChange={load} emptyLabel="대기 중인 파라미터 변경 승인이 없어요" />
      </PanelCard>
    </div>
  );
}
