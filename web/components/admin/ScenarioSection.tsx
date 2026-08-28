"use client";

/**
 * 국면 프리셋(/admin/bots, API §6-A v2.31). 전역 OPS·유형별 대수·방향을 한 번에 얹는
 * 상위 set-point다. rate·scale·bias 세 경로로 도달 가능한 상태만 만들어 승인 불요다.
 * 값이 기준선 대비 배수라 같은 프리셋을 두 번 눌러도 결과가 같고 steady가 복귀 경로다.
 * 서버가 활성 프리셋을 보고하지 않으므로 선택 상태를 만들지 않는다. 아래 판독은 이 세션에서
 * 마지막으로 누른 결과이지 현재 서버 상태의 단정이 아니다.
 */

import { useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { SCENARIO_CATALOG } from "@/lib/admin/catalog";
import type { ScenarioDef } from "@/lib/admin/catalog";
import type { ScenarioResult } from "@/lib/admin/types";
import { formatCount, formatNetBias } from "@/lib/format";
import PanelCard from "@/components/PanelCard";
import { PermissionNote } from "@/components/admin/ui";
import { toast } from "@/components/Toast";
import styles from "@/components/admin/admin.module.css";

interface Props {
  canOps: boolean;
  onChanged: () => void;
}

/** 배수 표기. 1.0은 배수가 아니라 기준선 유지라 그렇게 읽힌다 */
function multLabel(m: number): string {
  return m === 1 ? "기준" : `x${m % 1 === 0 ? m : m.toFixed(1)}`;
}

/** 대수 배수가 전부 1.0이면 플릿을 건드리지 않는다는 뜻이다(하네스 동일 플릿 규정) */
function scaleSummary(def: ScenarioDef): string {
  const changed = Object.entries(def.scale).filter(([, v]) => v !== 1);
  if (changed.length === 0) return "대수 유지";
  return `대수 ${changed.length}종 조정`;
}

export default function ScenarioSection({ canOps, onChanged }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  const [last, setLast] = useState<ScenarioResult | null>(null);

  const activate = async (def: ScenarioDef) => {
    setBusy(def.name);
    try {
      const r = await getAdminApi().activateScenario(def.name);
      setLast(r);
      toast(`${def.label} 국면 적용 · 목표 OPS ${formatCount(r.targetOps)}`, "info");
      onChanged();
    } catch (e) {
      toast(e instanceof Error ? e.message : "국면 적용 실패", "error");
    } finally {
      setBusy(null);
    }
  };

  const tile = (def: ScenarioDef) => {
    const bias = formatNetBias(def.targetBias);
    return (
      <button
        key={def.name}
        type="button"
        className={styles.presetBtn}
        disabled={!canOps || busy !== null}
        title={`${def.name} · ${def.note}`}
        onClick={() => void activate(def)}
      >
        <span className={styles.presetLabel}>{def.label}</span>
        <span className={styles.presetMeta}>
          <span>OPS {multLabel(def.opsMult)}</span>
          <span aria-hidden="true">·</span>
          <span className={styles.presetBias} data-dir={bias.dir}>
            {bias.text}
          </span>
        </span>
      </button>
    );
  };

  const calm = SCENARIO_CATALOG.filter((d) => d.intensity === "calm");
  const surge = SCENARIO_CATALOG.filter((d) => d.intensity === "surge");

  return (
    <PanelCard title="국면 프리셋" actions={<span className={styles.panelMeta}>승인 불요 · 전량 감사</span>}>
      {!canOps && <PermissionNote>봇 제어 권한(BOT_OPS 이상) 없음. 조회만 가능</PermissionNote>}
      <div className={styles.controlRow}>
        {calm.map(tile)}
        {/* 균형 제어를 끄는 두 국면은 스스로 되돌아오지 않는다. 정지 버튼과 같은 규약으로
            거리를 둬 완만한 국면을 겨냥하다 폭주로 빗나가는 것을 막는다 */}
        <span className={styles.controlSep} aria-hidden="true" />
        {surge.map(tile)}
      </div>
      <p className={styles.formHelp}>
        기준선 대비 배수라 같은 국면을 두 번 눌러도 결과가 같습니다. 평상이 복귀 경로입니다. 목표 OPS는
        발행 상한이라 봇 수요가 그 아래면 실제 처리량은 배수만큼 오르지 않습니다.
      </p>
      {last && (
        <div className={styles.presetReadout}>
          <span className={styles.presetReadoutKey}>마지막 적용</span>
          <b>{SCENARIO_CATALOG.find((d) => d.name === last.scenario)?.label ?? last.scenario}</b>
          <span aria-hidden="true">·</span>
          <span>
            목표 OPS <b className="num">{formatCount(last.targetOps)}</b> (기준 {formatCount(last.baseOps)})
          </span>
          <span aria-hidden="true">·</span>
          <span>균형 제어 {last.autoBalance ? "켬" : "끔"}</span>
          <span aria-hidden="true">·</span>
          <span>{last.markets.length}개 마켓</span>
          {last.clamped && <span className={styles.presetClamp}>대수 캡 도달</span>}
        </div>
      )}
    </PanelCard>
  );
}
