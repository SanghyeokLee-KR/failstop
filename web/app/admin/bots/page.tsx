"use client";

/**
 * 봇 플릿(/admin/bots). start/stop/pause·OPS·생성은 BOT_OPS 즉시 실행(가역).
 * 실행 중 파라미터 변경은 파괴 액션으로 2인 승인 필수(§2-1, §3-2).
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import { BOT_TYPES, BOT_TYPE_LABEL } from "@/lib/admin/types";
import type { Bot, BotType, DestructiveAction, SimStatus } from "@/lib/admin/types";
import { COIN_META } from "@/lib/sim/engine";
import { formatCount } from "@/lib/format";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import RequestButton from "@/components/admin/RequestButton";
import ApprovalList from "@/components/admin/ApprovalList";
import LivelinessSection from "@/components/admin/LivelinessSection";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { PageHeader, PermissionNote, StatTile } from "@/components/admin/ui";
import type { Health } from "@/components/admin/ui";
import { toast } from "@/components/Toast";
import styles from "@/components/admin/admin.module.css";

const PARAM_ACTIONS: DestructiveAction[] = ["sim.bots.params"];

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

// 전역 OPS 상한. 슬라이더와 현재 OPS 미터가 같은 눈금을 소비한다(서버 rate 클램프와 정렬)
const OPS_MAX = 600;

// 성공률 톤 임계. 99% 이상은 정상이라 잉크(무톤), 95% 미만 위험, 사이는 주의
const SUCCESS_RATE_OK = 0.99;
const SUCCESS_RATE_WARN = 0.95;
function successRateTone(rate: number): Health | undefined {
  if (rate >= SUCCESS_RATE_OK) return undefined;
  if (rate >= SUCCESS_RATE_WARN) return "warning";
  return "danger";
}

export default function BotsPage() {
  const canOps = useCan("BOT_OPS");
  const [newType, setNewType] = useState<BotType>("MARKET_MAKER");
  const [newMarket, setNewMarket] = useState(COIN_META[0]?.market ?? "KRW-BTC");
  const [newCount, setNewCount] = useState(1);

  // 정지는 가역이라 2인 승인까지는 불필요하지만, 파급이 전 마켓이라 확인은 받는다
  const [stopOpen, setStopOpen] = useState(false);

  const [paramBotId, setParamBotId] = useState("");
  const [spread, setSpread] = useState("0.0008");
  const [size, setSize] = useState("0.02");
  const [ttl, setTtl] = useState("1500");

  // 전역 OPS는 로컬 드래프트 + 적용(§3). 슬라이더 드래그마다 커밋하던 즉시 폭주를 없앤다
  const [rateDraft, setRateDraft] = useState(0);
  const rateSeeded = useRef(false);

  const res = useAdminResource<SimStatus>(() => getAdminApi().getSimStatus(), { intervalMs: 2000, isSessionError });
  const sim = res.data;
  const load = res.reload;

  useEffect(() => {
    if (sim && !rateSeeded.current) {
      setRateDraft(sim.rate);
      rateSeeded.current = true;
    }
  }, [sim]);

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      toast(ok, "info");
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : "처리 실패", "error");
    }
  };

  const api = getAdminApi();
  const paramActions = useMemo(() => PARAM_ACTIONS, []);

  // v2.22부터 sim·http 모두 실반영(controlApplied=true). 분기는 미래의 미반영 어댑터 대비로
  // 남긴다. 플릿 부재 등 반영 실패는 어댑터가 에러로 던져 아래 run()의 에러 토스트를 탄다
  const controlOk = (applied: string) =>
    api.controlApplied ? applied : "제어 요청을 감사에 기록. 반영 여부는 플릿 상태로 확인";

  // governor set-point 상향. 응답의 created·fleet_total로 결과를 표기하고 clamped로 캡 도달을 안내한다
  const createBot = async () => {
    try {
      const r = await api.createBot(newType, newMarket, newCount);
      toast(`봇 ${r.created}대 생성 · 전체 목표 ${r.fleetTotal}대${r.clamped ? " (캡 도달)" : ""}`, "info");
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : "생성 실패", "error");
    }
  };

  const botCols: DataColumn<Bot>[] = [
    { key: "id", label: "봇 ID", width: "minmax(140px, 1.2fr)", render: (b) => b.id },
    { key: "type", label: "유형", width: "110px", render: (b) => BOT_TYPE_LABEL[b.type] },
    { key: "market", label: "마켓", width: "110px", render: (b) => b.market },
    {
      key: "active",
      label: "상태",
      width: "96px",
      // 정상(가동)은 잉크, 정지만 pill로 세운다
      render: (b) => (b.active ? <span className={styles.stateText}>가동</span> : <span className={styles.neutralPill}>정지</span>),
    },
    { key: "ops", label: "OPS", width: "80px", align: "right", render: (b) => b.ops },
    {
      key: "params",
      label: "파라미터",
      width: "minmax(160px, 1fr)",
      render: (b) => (
        <span className={styles.paramCell} title="spread 호가 간격 · size 주문 수량 · ttl_ms 주문 수명">
          spread {b.params.spread} · size {b.params.size} · ttl {b.params.ttl_ms}
        </span>
      ),
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader title="봇 플릿" actions={res.stale ? <StaleBadge since={res.lastUpdatedAt} /> : undefined} />

      {res.error ? (
        <AdminErrorState reason={res.errorReason} onRetry={res.reload} />
      ) : !sim ? (
        <div className={styles.kpiGrid} data-cols="3">
          {/* 실측: 1행은 미터 타일 기준 122(보더 2+패딩 32+라벨 18+값 28+보조 18+미터 12+간격 12), 2행은 보조행 타일 기준 106 */}
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} variant="card" width="100%" height={122} />
          ))}
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} variant="card" width="100%" height={106} />
          ))}
        </div>
      ) : (
        // 폴링 실패 시 헤드라인 지표만 opacity로 강등한다. 제어 슬라이더·버튼은 아래 별도 카드라 흐려지지 않는다
        <div className={`${styles.kpiGrid} ${styles.staleRegion}`} data-cols="3" data-stale={res.stale ? "" : undefined}>
          <StatTile
            label="플릿 상태"
            value={sim.running ? (sim.paused ? "일시정지" : "가동 중") : "정지"}
            tone={sim.running && sim.paused ? "warning" : undefined}
          />
          <StatTile
            label="초당 주문 수"
            title="OPS"
            value={formatCount(sim.currentOps)}
            sub={`목표 ${formatCount(sim.rate)}`}
            meter={{ value: sim.currentOps, max: OPS_MAX, marks: [{ at: sim.rate, label: "목표" }] }}
          />
          <StatTile label="활성 봇" value={`${sim.activeBots} / ${sim.botCount}`} />
          <StatTile
            label="성공률"
            value={(Number(sim.successRate) * 100).toFixed(2)}
            unit="%"
            tone={successRateTone(Number(sim.successRate))}
          />
          <StatTile label="평균 지연" value={sim.avgLatencyMs} unit="ms" />
          {/* 정의 캡션("0이어야 정상")은 걷는다. 정상은 침묵, 위반 시에만 행동 지시가 발화한다 */}
          <StatTile
            label="중복 체결"
            value={sim.duplicateFillTotal}
            sub={sim.duplicateFillTotal === 0 ? undefined : "멱등 위반 · 원장 대조 필요"}
            tone={sim.duplicateFillTotal === 0 ? undefined : "danger"}
            alarm={sim.duplicateFillTotal !== 0}
          />
        </div>
      )}

      <PanelCard title="플릿 제어">
        {!canOps && <PermissionNote>봇 제어 권한(BOT_OPS 이상) 없음. 조회만 가능</PermissionNote>}
        <div className={styles.controlRow}>
          <button type="button" className={styles.controlBtn} disabled={!canOps} onClick={() => void run(() => api.simStart(), controlOk("플릿 시작 완료"))}>
            시작
          </button>
          <button type="button" className={styles.controlBtn} disabled={!canOps} onClick={() => void run(() => api.simPause(), controlOk("일시정지 전환 완료"))}>
            일시정지 전환
          </button>
          {/* 정지만 나머지 둘과 갈라 놓는다. 같은 무채 알약으로 붙여 두면
              일시정지 전환을 겨냥하다 한 칸 빗나가 전 마켓 호가 공급이 끊긴다 */}
          <span className={styles.controlSep} aria-hidden="true" />
          <button type="button" className={styles.dangerBtn} disabled={!canOps} onClick={() => setStopOpen(true)}>
            정지
          </button>
          {sim && (
            <div className={styles.rateField}>
              <span>전역 OPS</span>
              <input
                type="range"
                // 서버 parsePositive가 0을 400으로 반려한다(sim도 동일 정렬). 정지는 전용 버튼 소관
                min={10}
                max={OPS_MAX}
                step={10}
                value={rateDraft}
                disabled={!canOps}
                aria-label="전역 OPS 목표"
                onChange={(e) => setRateDraft(Number(e.target.value))}
              />
              <b className="num">{formatCount(rateDraft)}</b>
              {rateDraft !== sim.rate && <span className={styles.rateApplied}>적용값 {formatCount(sim.rate)}</span>}
              <button
                type="button"
                className={styles.primaryBtn}
                disabled={!canOps || rateDraft === sim.rate}
                onClick={() => void run(() => api.simSetRate(rateDraft), `OPS 목표를 ${rateDraft}로 바꿨습니다`)}
              >
                적용
              </button>
            </div>
          )}
        </div>
        {stopOpen && (
          <ConfirmDialog
            title="봇 플릿 정지"
            description="전 마켓 호가 공급 즉시 중단. 시작 버튼으로 복구 가능"
            target="전체 플릿"
            blastRadius="전 마켓 호가·체결 생성 중단"
            confirmLabel="정지"
            tone="danger"
            onConfirm={() => {
              setStopOpen(false);
              void run(() => api.simStop(), controlOk("플릿 정지 완료"));
            }}
            onClose={() => setStopOpen(false)}
          />
        )}
      </PanelCard>

      {/* 조회 빈도 순서: 목록이 튜닝·생성보다 위. 당직자는 봇이 뭘 하는지부터 본다 */}
      <PanelCard title="봇 목록">
        {!sim ? (
          <Skeleton variant="card" width="100%" height={200} />
        ) : (
          <DataTable columns={botCols} rows={sim.bots} rowKey={(b) => b.id} rowHeight={44} ariaLabel="봇 목록" />
        )}
      </PanelCard>

      <LivelinessSection sim={sim} canOps={canOps} onChanged={load} />

      <PanelCard title="봇 생성">
        {!canOps && <PermissionNote>봇 생성 권한(BOT_OPS 이상) 없음</PermissionNote>}
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
          <label className={styles.field}>
            <span>대수</span>
            <input
              type="number"
              min={1}
              max={30}
              value={newCount}
              disabled={!canOps}
              aria-label="생성 대수"
              onChange={(e) => setNewCount(Math.max(1, Math.round(Number(e.target.value) || 1)))}
            />
          </label>
          <button type="button" className={styles.primaryBtn} disabled={!canOps} onClick={() => void createBot()}>
            생성
          </button>
        </div>
      </PanelCard>

      <PanelCard title="파라미터 변경 요청" actions={<span className={styles.panelMeta}>2인 승인</span>}>
        {/* 정상 절차 설명은 경보면이 아니라 무채 각주. 경보색은 권한 부족 등 이탈 전용 */}
        <p className={styles.formHelp}>실행 중 파라미터 변경은 파괴 액션이라 다른 운영자의 승인과 요청자 실행을 거칩니다.</p>
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
            <span>호가 간격(spread)</span>
            <input value={spread} onChange={(e) => setSpread(e.target.value)} inputMode="decimal" aria-label="호가 간격(spread)" />
          </label>
          <label className={styles.field}>
            <span>주문 수량(size)</span>
            <input value={size} onChange={(e) => setSize(e.target.value)} inputMode="decimal" aria-label="주문 수량(size)" />
          </label>
          <label className={styles.field}>
            <span>주문 수명 ms(ttl_ms)</span>
            <input value={ttl} onChange={(e) => setTtl(e.target.value)} inputMode="numeric" aria-label="주문 수명 ms(ttl_ms)" />
          </label>
          {paramBotId ? (
            <RequestButton
              action="sim.bots.params"
              payload={{ action: "sim.bots.params", botId: paramBotId, params: { spread, size, ttl_ms: ttl } }}
              label="변경 요청"
              onRequested={load}
            />
          ) : (
            <button type="button" className={styles.neutralBtn} disabled>
              변경 요청
            </button>
          )}
        </div>
        <ApprovalList
          actions={paramActions}
          onChange={load}
          emptyLabel="대기 중인 파라미터 변경 승인 없음"
          emptyDesc="위 폼에서 요청하면 여기에서 발급·실행"
        />
      </PanelCard>
    </div>
  );
}
