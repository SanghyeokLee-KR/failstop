"use client";

/**
 * 운영 관제 셸의 상세 층 자리(관리자 설계 4-9). 노드 상세·사건 드로어(바닥 층)와 AI 판단 상세(겹치는 층)를 한곳에서
 * 그려, 관제센터·경보·사건·AI 판단·서버·노드 어느 화면에서 열어도 같은 층이다. 주소의 ?incident=·?node= 와 층을
 * 맞춘다. 주소는 replaceState로 바꿔 뒤로 가기 기록을 늘리지 않는다. 층이 열린 동안만 관제 판을 읽는다.
 */

import { useEffect, useRef } from "react";
import { usePathname } from "next/navigation";
import { isAdminHttp } from "@/lib/admin/api";
import { detailFromSearch, searchWithDetail, useOpsDetail, type DetailBase } from "@/lib/admin/detailStore";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import IncidentDrawer from "./IncidentDrawer";
import JudgmentDetail from "./JudgmentDetail";
import NodeDetail from "./NodeDetail";

function Layers({ base, judgment }: { base: DetailBase | null; judgment: string | null }) {
  const { data } = useOpsCenter();
  const { openNode, openIncident, openJudgment, closeBase, closeJudgment } = useOpsDetail.getState();
  if (data === null) return null;
  const sim = !isAdminHttp();
  return (
    <>
      {base?.kind === "node" && (
        <NodeDetail key="node" center={data} id={base.id} sim={sim} onMove={openNode} onClose={closeBase} onOpenIncident={openIncident} />
      )}
      {base?.kind === "incident" && (
        <IncidentDrawer key={`incident-${base.id}`} center={data} id={base.id} sim={sim} onClose={closeBase} onOpenJudgment={openJudgment} />
      )}
      {judgment !== null && <JudgmentDetail key={`judgment-${judgment}`} center={data} id={judgment} sim={sim} onClose={closeJudgment} />}
    </>
  );
}

export default function DetailHost() {
  const base = useOpsDetail((s) => s.base);
  const judgment = useOpsDetail((s) => s.judgment);
  const pathname = usePathname();
  const lastPath = useRef(pathname);

  // 주소로 들어오면 그 층을 연다
  useEffect(() => {
    const d = detailFromSearch(window.location.search);
    if (d === null) return;
    const { openNode, openIncident } = useOpsDetail.getState();
    if (d.kind === "node") openNode(d.id);
    else openIncident(d.id);
  }, []);

  // 층과 주소를 맞춘다. 다른 검색어(보고서의 ?id=)는 그대로 둔다
  useEffect(() => {
    const next = searchWithDetail(window.location.search, base);
    if (next === window.location.search) return;
    window.history.replaceState(window.history.state, "", `${window.location.pathname}${next}${window.location.hash}`);
  }, [base]);

  // 다른 화면으로 옮기면 층을 닫는다. 층의 링크(용량·부하 등)를 눌러 떠난 경우다
  useEffect(() => {
    if (lastPath.current === pathname) return;
    lastPath.current = pathname;
    useOpsDetail.getState().closeBase();
  }, [pathname]);

  if (base === null && judgment === null) return null;
  return <Layers base={base} judgment={judgment} />;
}
