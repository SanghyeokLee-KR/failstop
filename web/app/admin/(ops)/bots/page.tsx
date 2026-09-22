"use client";

/**
 * 봇 플릿(/admin/bots). start/stop/pause·OPS·생성은 BOT_OPS 즉시 실행(가역).
 * 실행 중 파라미터 변경은 파괴 액션으로 승인 티켓 필수(1인 승인, §2-1, §3-2).
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import { BOT_TYPES, BOT_TYPE_LABEL } from "@/lib/admin/types";
import type { BotType, DestructiveAction, MarketSimState, SimStatus } from "@/lib/admin/types";
import { COIN_META } from "@/lib/sim/engine";
import { formatCount, formatNetBias } from "@/lib/format";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import RequestButton from "@/components/admin/RequestButton";
import ApprovalList from "@/components/admin/ApprovalList";
import LivelinessSection from "@/components/admin/LivelinessSection";
import ScenarioSection from "@/components/admin/ScenarioSection";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import OpsThrottle from "@/components/admin/OpsThrottle";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { PageHeader, PermissionNote, StatTile } from "@/components/admin/ui";
import type { Health } from "@/components/admin/ui";
import { toast } from "@/components/Toast";
import styles from "@/components/admin/admin.module.css";

const PARAM_ACTIONS: DestructiveAction[] = ["sim.bots.params"];

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

// 전역 OPS 상한. 슬라이더와 현재 OPS 미터가 같은 눈금을 소비한다(서버 rate 클램프와 정렬)
const OPS_MAX = 600;

/**
 * 수치의 출처를 한 줄로 적는다. 관리 평면은 봇 JVM과 분리돼 있어 상태가 발행분일 수 있고,
 * 발행이 끊기면 옛 값이 남는다. 출처를 안 적으면 어느 쪽이든 현재로 읽힌다(status 1.3 source).
 */
function sourceNote(sim: SimStatus): string | undefined {
  if (sim.source === "unknown") return "봇 노드 상태 미보고";
  if (sim.source !== "published") return undefined;
  return sim.stale ? "발행 끊김 · 마지막 관측값" : "봇 노드 발행분";
}

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

  // 정지는 가역이라 승인 티켓까지는 불필요하지만, 파급이 전 마켓이라 확인은 받는다
  const [stopOpen, setStopOpen] = useState(false);

  const [paramBotId, setParamBotId] = useState("");
  const [spread, setSpread] = useState("0.0008");
  const [size, setSize] = useState("0.02");
  const [ttl, setTtl] = useState("1500");

  // 전역 OPS는 로컬 드래프트 + 적용(§3). 슬라이더 드래그마다 커밋하던 즉시 폭주를 없앤다

  const res = useAdminResource<SimStatus>(() => getAdminApi().getSimStatus(), { cadence: "idle", isSessionError });
  const sim = res.data;
  const load = res.reload;

  const run = async <T,>(
    fn: () => Promise<T>,
    ok: string,
    // 결과에 따라 문구를 바꾸는 경로가 있다. null이면 기본 문구를 쓴다.
    // 제네릭이라 호출부가 캐스팅 없이 결과 타입을 그대로 받는다. unknown으로 두면
    // 어댑터가 void를 돌려줘도 컴파일이 통과하고 런타임에서 터진다.
    okOf?: (result: T) => string | null,
  ) => {
    try {
      const result = await fn();
      toast(okOf?.(result) ?? ok, "info");
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : "처리 실패", "error");
    }
  };

  const api = getAdminApi();

  const paramActions = useMemo(() => PARAM_ACTIONS, []);

  // 분기는 미반영 어댑터 대비다. 반영 실패는 어댑터가 에러로 던져 아래 run()의 에러 토스트를 탄다
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

  /* 첫 열은 판단에 쓰는 값이어야 한다. 기계 식별자는 개별 봇을 지목할 때만 필요해 뒤로 보낸다 */
  /* 플릿 인벤토리 한 행. 마켓 x 유형이 단위다.
     종전에는 봇 인스턴스 한 마리가 한 행이었는데, 그건 sim 어댑터에만 있는 모델이었다.
     실제 플릿은 이름 붙은 인스턴스를 두지 않는다. 유형별 최대 풀을 미리 띄워 두고 인덱스가
     현재 목표 이하인 봇만 활성으로 두는 파킹 방식이라(BotFleet), 서버가 내려주는 것은
     markets[].scale[유형].{target, active}뿐이고 bots 배열은 비어 온다.
     그래서 실서버에 붙이면 표가 머리글만 남고 비었다. 화면이 데이터를 못 받은 것처럼 보였다.
     양쪽 어댑터가 모두 채우는 scale 을 단위로 삼아 한 표로 맞춘다. */
  const fleetRows = useMemo(() => sim?.markets ?? [], [sim]);
  // markets 부재는 "마켓이 없다"가 아니라 "모른다"다. 둘을 같은 빈 표로 그리면
  // 목표를 올리라는 안내가 도는 플릿에도 뜬다
  const fleetUnknown = sim != null && sim.markets == null;

  type FleetRow = MarketSimState;

  /* 이름 붙은 봇 인스턴스. 파라미터 변경은 봇 id 를 대상으로 받는 계약이라 이 목록이 있어야
     대상을 고를 수 있다. 실제 플릿은 파킹 풀이라 id 를 내지 않아 여기가 빈다(status 의 bots 가 빈 배열).
     빈 채로 select 만 두면 "봇 선택"이 열리는데 안이 비어 고장으로 읽히므로 사유를 적는다 */
  const namedBots = sim?.bots ?? [];

  /* 마켓이 행, 유형이 열이다. 서버가 주는 scale 이 원래 마켓 x 유형 행렬이라 그 모양 그대로 둔다.
     종전에는 이것을 12행으로 폈는데, 세로를 세 배로 쓰면서 가로는 800px 를 비워 놨다.
     행렬로 두면 마켓 하나가 한 줄이라 세 마켓을 한눈에 견주고, 남는 가로에 마켓 단위 관측치
     (체결률·실측 방향)가 들어간다. 그 둘은 지금 시장 생동감에서 한 번에 한 마켓씩만 보인다 */
  const typeCol = (t: BotType): DataColumn<FleetRow> => ({
    key: t,
    label: BOT_TYPE_LABEL[t],
    width: "110px",
    align: "center",
    render: (m) => {
      const s = m.scale[t];
      // 미등록 유형은 0 이 아니라 없음이다. 0 을 적으면 목표를 0 으로 내린 것과 구분되지 않는다
      if (!s) return <span className={styles.stateTextDim}>-</span>;
      // 수렴 중일 때만 목표를 병기한다. 늘 적으면 12칸이 전부 "1 / 1" 이라 어긋난 칸이 안 보인다
      return s.active === s.target ? <>{s.active}</> : <>{s.active} / {s.target}</>;
    },
  });

  const botCols: DataColumn<FleetRow>[] = [
    { key: "market", label: "마켓", width: "130px", align: "center", render: (m) => m.market },
    ...BOT_TYPES.map(typeCol),
    {
      key: "sum",
      label: "합계",
      width: "90px",
      align: "center",
      render: (m) => BOT_TYPES.reduce((n, t) => n + (m.scale[t]?.active ?? 0), 0),
    },
    {
      key: "fill",
      label: "체결률",
      width: "96px",
      align: "center",
      render: (m) => m.fillRatio,
    },
    {
      key: "bias",
      label: "실측 방향",
      width: "130px",
      align: "center",
      // 프리셋 타일과 같은 표기다. 같은 값을 두 곳에서 다른 모양으로 적지 않는다
      render: (m) => {
        const b = formatNetBias(m.measuredNetBias);
        return <span className={styles.presetBias} data-dir={b.dir}>{b.text}</span>;
      },
    },
    {
      key: "state",
      label: "가동",
      // 전 열 중앙 정렬이다. 한 자리 수가 넓은 머리글 아래 우측에 붙어 있으면 제 이름표에서
      // 떨어져 보이고, 자릿수가 모두 같아 우측 정렬로 얻는 자리 맞춤도 없다.
      // 남는 폭은 이 열이 받는다
      width: "minmax(140px, 1fr)",
      align: "center",
      /* 이 열이 답하는 것은 "이 봇들이 지금 일하고 있는가"다. 대수가 목표에 닿았다는 뜻의
         "목표 도달"은 작업이 끝났다는 뜻으로 읽혔다. 봇은 끝나는 일이 아니라 계속 호가를 낸다.
         더 나쁜 것은 플릿을 정지시켜도 그 표기가 남았다는 점이다. active 는 파킹 풀의 설정
         대수라 running 을 반영하지 않는다. 그래서 플릿 수명주기를 먼저 본다.
         정상(거래 중)은 잉크, 이탈만 pill 로 세운다 */
      render: (m) => {
        // 미보고는 정지가 아니다. 정지로 칠하면 도는 플릿을 멈춘 것으로 읽는다
        if (sim?.running == null) return <span className={styles.stateTextDim}>-</span>;
        if (!sim.running) return <span className={styles.neutralPill}>플릿 정지</span>;
        if (sim.paused) return <span className={styles.neutralPill}>일시정지</span>;
        const off = BOT_TYPES.filter((t) => m.scale[t] && m.scale[t]!.active !== m.scale[t]!.target).length;
        if (off > 0) return <span className={styles.neutralPill}>수렴 중 {off}개 유형</span>;
        return <span className={styles.stateText}>거래 중</span>;
      },
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader title="봇 플릿" actions={<ResourceStamp lastUpdatedAt={res.lastUpdatedAt} stale={res.stale} onReload={res.reload} />} />

      {res.error ? (
        <AdminErrorState reason={res.errorReason} onRetry={res.reload} />
      ) : !sim ? (
        <div className={styles.kpiGrid} data-cols="3">
          {/* 미터 타일·보조행 타일 실측 높이와 같은 값이라 로드 후 시프트 0 */}
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
          {/* 원천이 셋이라 수치의 출처를 함께 적는다. 미보고를 정지로 칠하면 도는 플릿을
              멈춘 것으로 읽고, 조작 버튼을 누르게 된다 */}
          <StatTile
            label="플릿 상태"
            value={sim.running == null ? "알 수 없음" : sim.running ? (sim.paused ? "일시정지" : "가동 중") : "정지"}
            sub={sourceNote(sim)}
            tone={sim.running == null || sim.stale ? "warning" : sim.running && sim.paused ? "warning" : undefined}
          />
          <StatTile
            label="초당 주문 수"
            title="OPS"
            value={sim.currentOps == null ? "미상" : formatCount(sim.currentOps)}
            sub={sim.rate == null ? "목표 미상" : `목표 ${formatCount(sim.rate)}`}
            tone={sim.currentOps == null ? "warning" : undefined}
            // 미상에 미터를 그리면 바늘이 왼쪽 끝에 붙어 0으로 읽힌다
            meter={
              sim.currentOps == null || sim.rate == null
                ? undefined
                : { value: sim.currentOps, max: OPS_MAX, marks: [{ at: sim.rate, label: "목표" }] }
            }
          />
          {/* 정지 중에도 대수는 그대로 뜬다. active 는 파킹 풀의 설정 대수라 수명주기와 무관하다.
              숫자만 두면 "14대가 돌고 있다"로 읽히므로 정지·일시정지일 때 사유를 병기한다 */}
          <StatTile
            label="활성 봇"
            value={sim.activeBots == null || sim.botCount == null ? "미상" : `${sim.activeBots} / ${sim.botCount}`}
            sub={
              sim.running == null
                ? "플릿 상태 미보고"
                : sim.running
                  ? sim.paused ? "일시정지 중 · 발행 없음" : undefined
                  : "플릿 정지 중 · 발행 없음"
            }
            tone={sim.running && !sim.paused ? undefined : "warning"}
          />
          {/* 아래 셋은 서버 status가 내지 않는 축이다. 상수를 채우면 성공률 100%·지연 0ms·
              중복체결 0건이 실측처럼 보인다. 원천이 생기기 전까지 미측정으로 둔다 */}
          <StatTile
            label="성공률"
            value={sim.successRate == null ? "미측정" : (Number(sim.successRate) * 100).toFixed(1)}
            unit={sim.successRate == null ? undefined : "%"}
            tone={sim.successRate == null ? undefined : successRateTone(Number(sim.successRate))}
          />
          <StatTile
            label="평균 지연"
            value={sim.avgLatencyMs == null ? "미측정" : sim.avgLatencyMs}
            unit={sim.avgLatencyMs == null ? undefined : "ms"}
          />
          {/* 정의 캡션("0이어야 정상")은 걷는다. 정상은 침묵, 위반 시에만 행동 지시가 발화한다 */}
          <StatTile
            label="중복 체결"
            value={sim.duplicateFillTotal == null ? "미측정" : sim.duplicateFillTotal}
            sub={sim.duplicateFillTotal ? "멱등 위반 · 원장 대조 필요" : undefined}
            tone={sim.duplicateFillTotal ? "danger" : undefined}
            alarm={!!sim.duplicateFillTotal}
          />
        </div>
      )}

      {/* 2층 조작대. 수명주기와 부하 레버(좌)를 국면 프리셋(우)과 나란히 둔다.
          세로로 쌓으면 슬라이더가 화면 폭을 가로질러 라벨과 값이 멀어지고,
          장면 전환과 미세 조정이 스크롤로 갈라진다 */}
      <div className={styles.opsRow} data-cols="control">
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
        </div>
        {/* 용량 화면과 같은 컴포넌트다. 부하 레버가 두 곳에 필요하되 구현은 하나여야
            두 화면이 다른 값을 말하지 않는다 */}
        {sim && (
          <OpsThrottle rate={sim.rate} currentOps={sim.currentOps} canOps={canOps} onApplied={load} />
        )}
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

      {/* 국면은 OPS·대수·방향을 한 번에 얹는 상위 제어라 개별 튜닝(생동감)보다 위에 둔다.
          당직자의 순서가 장면 전환 다음 미세 조정이다 */}
      <ScenarioSection canOps={canOps} onChanged={load} />
      </div>

      {/* 조회 빈도 순서: 목록이 튜닝·생성보다 위. 당직자는 봇이 뭘 하는지부터 본다 */}
      <PanelCard title="플릿 인벤토리" actions={<span className={styles.panelMeta}>마켓 x 유형</span>}>
        {!sim ? (
          <Skeleton variant="card" width="100%" height={200} />
        ) : (
          <DataTable
            columns={botCols}
            rows={fleetRows}
            rowKey={(m) => m.market}
            rowHeight={44}
            ariaLabel="플릿 인벤토리"
            defaultAlign="center"
            /* 빈 표를 머리글만 남기고 두지 않는다. 종전에는 empty 를 주지 않아 실서버에서
               행 없는 표가 그대로 떴고, 데이터를 못 받은 화면과 구분되지 않았다 */
            empty={
              <p className={styles.formHelp}>
                {fleetUnknown
                  ? "플릿 구성 미보고. 봇 노드가 상태를 발행하지 않아 대수를 읽을 수 없음"
                  : "등록된 마켓 없음. 아래 봇 타입별 대수에서 목표를 올리면 서버가 수렴"}
              </p>
            }
          />
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

      <PanelCard title="파라미터 변경 요청" actions={<span className={styles.panelMeta}>1인 승인</span>}>
        {/* 정상 절차 설명은 경보면이 아니라 무채 각주. 경보색은 권한 부족 등 이탈 전용 */}
        <p className={styles.formHelp}>실행 중 파라미터 변경은 파괴 액션. 승인 발급 후 요청자가 실행</p>
        {sim && namedBots.length === 0 && (
          <p className={styles.formHelp}>
            기동 중인 플릿은 봇 id를 발행하지 않음(유형별 파킹 풀). 대상 지정이 필요한 이 경로는
            id를 내는 플릿 전용. 대수·방향·OPS는 위 제어로 변경
          </p>
        )}
        <div className={styles.formRow}>
          <label className={styles.field}>
            <span>대상 봇</span>
            <select
              value={paramBotId}
              onChange={(e) => setParamBotId(e.target.value)}
              aria-label="대상 봇"
              disabled={namedBots.length === 0}
            >
              <option value="">{namedBots.length === 0 ? "대상 없음" : "봇 선택"}</option>
              {namedBots.map((b) => (
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
