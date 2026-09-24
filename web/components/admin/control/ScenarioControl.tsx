"use client";

/**
 * sim 시나리오 재생(관리자 설계 4-4). 서비스 흐름의 재생 조작과 같은 어휘로 머리 오른쪽에 둔다.
 * 재생 중은 상태가 아니라 주의색을 쓰지 않고 브랜드 약틴트 면에 ▶ 형태를 둔다(§12-3).
 * http 원천에는 시나리오가 없어 그리지 않는다.
 */

import { useEffect, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { OPS_SCENARIO_TITLE, SCENARIO_END_SEC } from "@/lib/admin/opsSim";
import type { OpsScenarioId, OpsScenarioView } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import adminStyles from "../admin.module.css";
import { mmss } from "./parts";
import styles from "./control.module.css";

const DESC: Record<OpsScenarioId, string> = {
  READ_SURGE: "정상, 조회 급증 경보, AI 판단(노드 +1, 증설 임계 60→55%), 승인, 조치, 해제",
  NIGHT_SCALE_IN: "세 대에서 축소 제안. 자동 모드면 축소 뒤 조회가 몰릴 때 자동 되돌림과 수동 전환",
};

export default function ScenarioControl({ scenario, onChanged }: { scenario: OpsScenarioView | null; onChanged: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", esc);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", esc);
    };
  }, [open]);
  if (!getAdminApi().opsScenarios) return null;

  const start = async (id: OpsScenarioId) => {
    setOpen(false);
    try {
      await getAdminApi().startOpsScenario(id);
      toast(`시나리오 재생 · ${OPS_SCENARIO_TITLE[id]}`, "info");
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "재생 실패", "error");
    }
  };
  const reset = async () => {
    try {
      await getAdminApi().resetOps();
      toast("관제 시뮬레이션 초기화", "info");
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "초기화 실패", "error");
    }
  };
  const running = scenario !== null && scenario.elapsedSec < SCENARIO_END_SEC;
  return (
    <div className={styles.scenario} ref={ref}>
      {scenario !== null && (
        <span className={styles.runPill}>
          <span aria-hidden="true">{running ? "▶" : "○"}</span>
          {running ? "재생 중" : "재생 끝"} · {scenario.title}
          <b className="num">{mmss(scenario.elapsedSec * 1000)}</b>
        </span>
      )}
      {!running && (
        <button type="button" className={adminStyles.neutralBtn} aria-haspopup="true" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
          시나리오 재생
        </button>
      )}
      <button type="button" className={adminStyles.neutralBtn} onClick={() => void reset()}>
        초기화
      </button>
      {open && (
        <div className={styles.menu} role="group" aria-label="재생할 시나리오">
          {(Object.keys(OPS_SCENARIO_TITLE) as OpsScenarioId[]).map((id) => (
            <button key={id} type="button" className={styles.menuItem} onClick={() => void start(id)}>
              <span>
                <span aria-hidden="true">▶ </span>
                {OPS_SCENARIO_TITLE[id]}
              </span>
              <span className={styles.menuItemDesc}>{DESC[id]}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
