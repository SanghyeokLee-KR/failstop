"use client";

/**
 * 시나리오(/admin/scenarios, 관리자 설계 4-10-6). 이 장애가 나면 관제·AI·사람이 어떻게 움직이는지에 답한다. 왼쪽 목록(320px, 위에
 * 붙음)과 오른쪽 재생 판이다. 관제 판과 따로 도는 재생 세계라 관제센터와 다른 화면의 값을 바꾸지 않고, 화면 머리와 재생 판 머리에
 * '재생 · 관제 값과 별개' 표식을 크게 둔다. 두 데이터 원천 모두 재생은 브라우저 시뮬레이터가 한다.
 *
 * 주소는 ?id=<시나리오>와 ?mode=<MANUAL|AUTO|COMPARE>를 받고, 고르면 replaceState로 바꿔 뒤로 가기 기록을 늘리지 않는다.
 */

import { useEffect, useState } from "react";
import { scenarioById, SCENARIOS } from "@/lib/admin/scenarios";
import type { ScenarioId } from "@/lib/admin/types";
import OpsHead from "@/components/admin/control/OpsHead";
import PageSkeleton from "@/components/admin/control/PageSkeleton";
import Player from "@/components/admin/scenarios/Player";
import ScenarioList from "@/components/admin/scenarios/ScenarioList";
import type { ReplayView } from "@/components/admin/scenarios/useReplay";
import { SimMark } from "@/components/admin/ui";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/scenarios/scenarios.module.css";

const VIEWS: ReplayView[] = ["MANUAL", "AUTO", "COMPARE"];

function setQuery(key: string, value: string) {
  const q = new URLSearchParams(window.location.search);
  q.set(key, value);
  window.history.replaceState(window.history.state, "", `${window.location.pathname}?${q.toString()}`);
}

export default function ScenariosPage() {
  const [picked, setPicked] = useState<ScenarioId>(SCENARIOS[0].id);
  const [view, setView] = useState<ReplayView>("MANUAL");
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    const id = q.get("id");
    const mode = q.get("mode");
    if (scenarioById(id)) setPicked(id as ScenarioId);
    if (mode && (VIEWS as string[]).includes(mode)) setView(mode as ReplayView);
    setReady(true);
  }, []);

  const pick = (id: ScenarioId) => {
    setPicked(id);
    setQuery("id", id);
  };
  const spec = scenarioById(picked) ?? SCENARIOS[0];

  return (
    <div className={adminStyles.page}>
      <OpsHead title="시나리오" center={null} mark={<SimMark kind="scenario" />} />
      <div className={styles.layout}>
        <ScenarioList picked={spec.id} onPick={pick} />
        {ready ? (
          <Player
            key={spec.id}
            spec={spec}
            initialView={view}
            onView={(v) => {
              setView(v);
              setQuery("mode", v);
            }}
          />
        ) : (
          <PageSkeleton tiles={0} columns={[1, 1.1, 1.1, 1]} rows={[3, 3, 3, 3]} label="시나리오 재생 판을 준비하는 중입니다" />
        )}
      </div>
    </div>
  );
}
