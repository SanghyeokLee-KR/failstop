"use client";

/**
 * 시연(/admin/drill). 대본 네 가지를 단계로 펴고 진행을 라이브 값으로 읽는다.
 *
 * 이 화면이 대신하는 것. 종전 카오스 화면은 주입 버튼 아홉 개였고 무엇을 보고 성공이라
 * 할지는 화면 밖에 있었다. 이 프로젝트가 보여야 하는 것은 아홉 가지 장애 주입이 아니라
 * 네 가지 운영 상황이다. 트래픽이 몰릴 때 조회를 늘리는 일, 주문 노드를 잃었을 때
 * 다른 노드가 서는 일, DB를 사람이 승격시키는 일, 그리고 아직 판정할 수 없는 축이다.
 *
 * 왕복을 대본이 정한다. DB 실습은 별도 프로젝트라 이 콘솔이 읽을 것이 없고, 그래서
 * 그 대본을 고르면 서버 호출이 0이다. 화면이 전부를 매번 읽으면 관리 버킷이 거기서 샌다.
 *
 * 주행을 켜야 빨리 읽는다. 평시 30초, 주행 중 3초다. 시연 구간에만 고빈도를 쓴다는
 * 규칙을 사람이 켜고 끄는 스위치로 만든 것이다. 주행 기록도 이 스위치가 연다.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import {
  DRILLS,
  evaluateDrill,
  type DrillDef,
  type DrillSignals,
  type DrillStep,
} from "@/lib/admin/drills";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DrillStepper from "@/components/admin/DrillStepper";
import DecisionFooter from "@/components/admin/DecisionFooter";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { AdminErrorState } from "@/components/admin/ResourceState";
import { PageHeader, PermissionNote } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { useCan } from "@/lib/admin/store";
import { toast } from "@/components/Toast";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

const EMPTY: DrillSignals = { cap: null, sim: null, ha: null, rec: null };
/** 빈 기록 참조를 고정한다. 매 렌더 새 객체를 주면 판정이 계속 다시 돈다 */
const EMPTY_RECORD: Record<string, string> = {};

export default function DrillPage() {
  const api = getAdminApi();
  const [drillId, setDrillId] = useState(DRILLS[0].id);
  const [running, setRunning] = useState(false);
  const [actBusy, setActBusy] = useState<string | null>(null);
  const def = useMemo(() => DRILLS.find((d) => d.id === drillId) ?? DRILLS[0], [drillId]);
  const canOps = useCan("BOT_OPS");
  const canScale = useCan("SUPER");

  // 대본이 읽는 축만 부른다. needs가 비면 왕복이 하나도 나가지 않는다
  const needs = def.needs;
  // 배열을 의존으로 두면 매 렌더 새 참조라 폴러가 계속 다시 붙는다. 문자열로 좁힌다
  const needKey = needs.join(",");
  const load = useCallback(async (): Promise<DrillSignals> => {
    const want = needKey.length > 0 ? needKey.split(",") : [];
    const [cap, sim, ha, rec] = await Promise.all([
      want.includes("cap") ? api.getCapacity() : Promise.resolve(null),
      want.includes("sim") ? api.getSimStatus() : Promise.resolve(null),
      want.includes("ha") ? api.getHaStatus() : Promise.resolve(null),
      want.includes("rec") ? api.getRecovery() : Promise.resolve(null),
    ]);
    return { cap, sim, ha, rec };
  }, [api, needKey]);

  const res = useAdminResource<DrillSignals>(load, {
    cadence: running ? "live" : "idle",
    isSessionError,
  });

  const signals = res.data ?? EMPTY;
  // 이번 주행의 충족 기록. 대본마다 따로 둔다. 저장하지 않으므로 새로 고치면 비어 있다
  const [metAt, setMetAt] = useState<Record<string, Record<string, string>>>({});
  const mine = metAt[def.id] ?? EMPTY_RECORD;
  const ev = useMemo(() => evaluateDrill(def, signals, mine), [def, signals, mine]);

  const runningRef = useRef(running);
  runningRef.current = running;
  useEffect(() => {
    if (!runningRef.current) return;
    const now = new Date().toISOString();
    setMetAt((prev) => {
      const cur = prev[def.id] ?? {};
      let changed = false;
      const next = { ...cur };
      def.steps.forEach((step, i) => {
        if (ev.raw[i].state === "MET" && next[step.id] === undefined) {
          next[step.id] = now;
          changed = true;
        }
      });
      return changed ? { ...prev, [def.id]: next } : prev;
    });
  }, [def, ev]);

  const act = async (step: DrillStep) => {
    if (!step.act) return;
    setActBusy(step.id);
    try {
      if (step.act.kind === "OPS") {
        const r = await api.simSetRate(step.act.rate);
        toast(
          r.applyPath === "queued"
            ? `발행률 목표 ${step.act.rate} 전달 완료. 반영까지 최대 1초 소요`
            : `발행률 목표 ${step.act.rate} 적용 완료`,
          "info",
        );
      } else if (step.act.kind === "SCALE_OUT") {
        await api.scaleOut();
        toast("서버 추가 요청됨. 준비되면 트래픽 수신", "success");
      } else {
        await api.scaleIn();
        toast("서버 축소 요청됨. 진행 중인 요청을 먼저 흘려보냄", "success");
      }
      res.reload();
    } catch (e) {
      toast(e instanceof Error ? e.message : "요청 실패", "error");
    } finally {
      setActBusy(null);
    }
  };

  // 조작마다 요구 권한이 다르다. 발행률은 봇 제어, 대수 변경은 총괄이다.
  // 한 화면에서 뭉뚱그려 잠그면 봇 권한만 있는 운영자가 부하도 못 넣는다
  const actAllowed = (step: DrillStep) =>
    step.act ? (step.act.kind === "OPS" ? canOps : canScale) : false;
  const anyActLocked = def.steps.some((s) => s.act && !actAllowed(s));

  const resetRun = () => {
    setMetAt((prev) => ({ ...prev, [def.id]: {} }));
    toast("주행 기록 초기화", "info");
  };

  return (
    <div className={styles.page}>
      <PageHeader
        title="시연"
        desc="상황별 단계와 판정. 충족 여부는 지금 읽은 값으로만 적는다"
        actions={
          needs.length > 0 ? (
            <ResourceStamp lastUpdatedAt={res.lastUpdatedAt} stale={res.stale} onReload={res.reload} />
          ) : undefined
        }
      />

      <div className={styles.drillRail} role="tablist" aria-label="시연 대본">
        {DRILLS.map((d) => (
          <DrillTab key={d.id} def={d} active={d.id === def.id} onSelect={() => setDrillId(d.id)} />
        ))}
      </div>

      {res.loading && needs.length > 0 ? (
        <Skeleton variant="card" width="100%" height={420} />
      ) : res.error ? (
        <AdminErrorState reason={res.errorReason} onRetry={res.reload} />
      ) : (
        <div className={`${styles.staleRegion}`} data-stale={res.stale ? "" : undefined}>
          {/* 제목을 패널 머리에 다시 적지 않는다. 고른 탭이 이미 그 이름을 들고 있다.
              대신 이 대본이 증명하려는 것과 주행 조작을 한 줄에 마주 세운다 */}
          <PanelCard srTitle={def.title}>
            <div className={styles.drillLead}>
              <p className={styles.drillClaim}>{def.claim}</p>
              <div className={styles.drillRunBar}>
                {/* 충족 수가 아니라 지금 어느 단계인가를 적는다. 뒤 단계가 먼저 참이 되는
                    경우가 있어 충족 수를 세면 진행보다 큰 수가 나온다 */}
                <span className={`${styles.drillProgress} num`}>
                  {ev.judgeable === 0 ? (
                    <span className={styles.drillProgressNote}>판정 불가</span>
                  ) : ev.currentIndex === null ? (
                    <>
                      완료
                      <span className={styles.drillProgressNote}>
                        {ev.judgeable < ev.total ? `판정 가능 ${ev.judgeable}단계` : `단계 ${ev.total}`}
                      </span>
                    </>
                  ) : (
                    <>
                      {ev.position} / {ev.total}
                      <span className={styles.drillProgressNote}>
                        단계{ev.judgeable < ev.total ? ` · 판정 불가 ${ev.total - ev.judgeable}` : ""}
                      </span>
                    </>
                  )}
                </span>
                {needs.length > 0 && (
                  <>
                    <button
                      type="button"
                      className={running ? "btn" : styles.primaryBtn}
                      onClick={() => setRunning((v) => !v)}
                    >
                      {running ? "주행 종료" : "주행 시작"}
                    </button>
                    <button type="button" className="btn" onClick={resetRun}>
                      기록 초기화
                    </button>
                  </>
                )}
              </div>
            </div>
            {def.prep && def.prep.length > 0 && (
              <div className={styles.drillPrep}>
                <span className={styles.drillPrepLabel}>사전 조건</span>
                <ul>
                  {def.prep.map((p) => (
                    <li key={p}>{p}</li>
                  ))}
                </ul>
              </div>
            )}
            {needs.length === 0 && (
              <p className={styles.drillNote}>
                이 대본은 별도 프로젝트에서 돈다. 콘솔이 읽을 원천이 없어 단계 판정을 내지 않는다.
              </p>
            )}
            {anyActLocked && <PermissionNote>일부 조작에 권한 부족</PermissionNote>}
            <DrillStepper
              def={def}
              checks={ev.checks}
              metAt={mine}
              currentIndex={ev.currentIndex}
              onAct={act}
              actAllowed={actAllowed}
              actBusy={actBusy}
            />
          </PanelCard>

          <DecisionFooter
            steps={["대본 선택", "주행 시작", "단계 판독", "충족 뒤 다음 단계"]}
          />
        </div>
      )}
    </div>
  );
}

/**
 * 대본 탭. 진행은 적지 않는다. 고르지 않은 대본의 값을 읽지 않으므로 그 자리에 적을
 * 것이 없고, 고른 대본의 진행은 아래 패널이 이미 들고 있다.
 */
function DrillTab({ def, active, onSelect }: { def: DrillDef; active: boolean; onSelect: () => void }) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      className={styles.drillTab}
      data-active={active ? "" : undefined}
      onClick={onSelect}
    >
      <span className={styles.drillTabTitle}>{def.title}</span>
      <span className={styles.drillTabLead}>{def.lead}</span>
      <span className={`${styles.drillTabMeta} num`}>단계 {def.steps.length}</span>
    </button>
  );
}
