"use client";

/**
 * 상세 층의 여는 상태(관리자 설계 4-9). 노드 상세와 사건 드로어는 판 위의 바닥 층(base)이고, AI 판단 상세는
 * 그 위에 겹칠 수 있는 층(judgment)이다. 사건 드로어에서 판단을 열고 닫으면 드로어로 돌아온다. 여는 쪽(카드,
 * 사건 기록, 관제 검색)과 그리는 쪽(운영 관제 셸의 DetailHost)이 서로 다른 자리에 있어 작은 저장소에 둔다.
 */

import { create } from "zustand";

export type DetailBase = { kind: "node"; id: string } | { kind: "incident"; id: string };

interface DetailState {
  base: DetailBase | null;
  /** 겹쳐 연 AI 판단 기록의 id */
  judgment: string | null;
  openNode(id: string): void;
  openIncident(id: string): void;
  openJudgment(id: string): void;
  closeBase(): void;
  closeJudgment(): void;
}

export const useOpsDetail = create<DetailState>((set) => ({
  base: null,
  judgment: null,
  // 바닥 층을 새로 열면 위에 겹친 판단 층은 거둔다. 다른 대상의 층 위에 옛 판단이 남지 않게 한다
  openNode: (id) => set({ base: { kind: "node", id }, judgment: null }),
  openIncident: (id) => set({ base: { kind: "incident", id }, judgment: null }),
  openJudgment: (id) => set({ judgment: id }),
  closeBase: () => set({ base: null, judgment: null }),
  closeJudgment: () => set({ judgment: null }),
}));

/** 주소의 ?incident=·?node= 를 층으로 읽는다. 둘 다 있으면 사건이 먼저다 */
export function detailFromSearch(search: string): DetailBase | null {
  const q = new URLSearchParams(search);
  const incident = q.get("incident");
  if (incident) return { kind: "incident", id: incident };
  const node = q.get("node");
  if (node) return { kind: "node", id: node };
  return null;
}

/** 층에 맞춰 주소의 검색어를 고친다. 다른 검색어(보고서의 ?id= 등)는 그대로 둔다 */
export function searchWithDetail(search: string, base: DetailBase | null): string {
  const q = new URLSearchParams(search);
  q.delete("incident");
  q.delete("node");
  if (base) q.set(base.kind, base.id);
  const out = q.toString();
  return out ? `?${out}` : "";
}
