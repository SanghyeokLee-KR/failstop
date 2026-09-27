/**
 * 서버·노드 자산 목록(관리자 설계 4-10-3). 행은 노드 상세의 이웃 순서와 같다(앱 계층 app-1·app-2·app-3, DB, 관측,
 * 온프레미스). 수집기 상태는 설정 화면의 Prometheus 행과 같은 함수(collectTargets)로 센다. 자산 칸(OS·커널, 가동 시간,
 * inode, 실패한 systemd 서비스, 보안 업데이트)은 sim이 예시를 싣고 http는 서버 경로가 계약 초안이라 칸마다 원천 연결 전이다.
 * 구축 전인 ai-1은 '시뮬레이션 가정' 행이고 OS·가동 시간 칸을 비운다.
 */

import { collectTargets } from "./opsHealth";
import { nodeList } from "./opsDetail";
import type { ControlCenter, HostAsset, OpsHostRole } from "./types";

/** 자산 칸의 원천. http에서 칸이 비면 이 이름을 툴팁과 접근 이름에 싣는다 */
export const ASSET_SOURCE: Record<keyof HostAsset, string> = {
  os: "node_uname_info 명명 질의(GET /admin/v1/msp/hosts/inventory · 3라운드 계약 초안)",
  kernel: "node_uname_info 명명 질의(GET /admin/v1/msp/hosts/inventory · 3라운드 계약 초안)",
  bootedAt: "node_boot_time_seconds(GET /admin/v1/msp/hosts/inventory · 3라운드 계약 초안)",
  inodeUsedPct: "node_filesystem_files_free(GET /admin/v1/msp/hosts/inventory · 3라운드 계약 초안)",
  failedUnits: "node_systemd_unit_state(2단계)",
  securityUpdates: "호스트 증거 파일(수집기 없음)",
};

/** 자산 표의 짧은 역할. 카드의 긴 정체(HOST_KIND)는 노드 상세가 싣는다 */
const ROLE_SHORT: Record<OpsHostRole, string> = {
  APP: "매칭 리더 · 조회",
  DB_PRIMARY: "DB Primary",
  DB_STANDBY: "DB Standby",
  BOT_OBS: "시장 봇 · 관측",
};

export type AssetCollect = "OK" | "STALE" | "MISSING" | "OFF" | "PENDING" | "DRAINING" | "ASSUMED" | "LIVE";

export interface AssetRow {
  id: string;
  kind: "host" | "read" | "ai";
  role: string;
  location: string;
  type: string;
  /** 자산 칸. 인스턴스가 없거나(꺼진 app-3) 구축 전(ai-1 가정)이면 null이다 */
  asset: HostAsset | null;
  collect: AssetCollect;
  /** 값을 받고 있는가. 수집 대상 표의 '응답'과 같은 판정이다(collectTargets). 기동·드레이닝 중에도 값이 오면 참이다 */
  responding: boolean;
  collectedAt: string | null;
  diskUsedPct: number | null;
  /** 구축 전이라 연결과 값이 시뮬레이터의 가정인 행 */
  assumed: boolean;
}

/** 자산 표의 행. 관제 판에 호스트 칸이 없으면 빈 배열이다(화면이 칸 상태를 그린다) */
export function assetRows(c: ControlCenter): AssetRow[] {
  const collected = new Map(collectTargets(c).rows.map((r) => [r.id, r]));
  return nodeList(c).map((ref): AssetRow => {
    if (ref.kind === "host") {
      const h = ref.host;
      return {
        id: h.id, kind: "host", role: h.roleKnown === false ? "DB · 역할 미수집" : ROLE_SHORT[h.role], location: h.az || "미확인", type: h.instanceType || "미확인",
        asset: h.asset ?? null, collect: h.collect, responding: collected.get(h.id)?.ok ?? false, collectedAt: h.collectedAt, diskUsedPct: h.diskUsedPct, assumed: false,
      };
    }
    if (ref.kind === "read") {
      const n = ref.node;
      const row = collected.get(n.id);
      const collect: AssetCollect = n.state === "OFF" ? "OFF" : n.state === "PENDING" ? "PENDING" : n.state === "DRAINING" ? "DRAINING" : row?.ok ? "OK" : "MISSING";
      return {
        id: n.id, kind: "read", role: "조회 전용 · Auto Scaling", location: n.az || "미확인", type: n.instanceType || "미확인",
        asset: n.asset ?? null, collect, responding: row?.ok ?? false, collectedAt: row?.collectedAt ?? null, diskUsedPct: null, assumed: false,
      };
    }
    const a = ref.server;
    return {
      id: a.id, kind: "ai", role: "로컬 LLM", location: "온프레미스", type: a.hardware,
      asset: null, collect: a.assumed ? "ASSUMED" : a.link === "OK" ? "LIVE" : "MISSING", responding: false, collectedAt: a.collectedAt, diskUsedPct: null, assumed: a.assumed,
    };
  });
}
