/**
 * 관리 평면의 두 갈래 내비(§4-1).
 *
 * 운영 관제는 인프라를 움직이고 시연하는 자리다. 부하를 넣고, 대수를 바꾸고, 노드 상태를
 * 본다. 백오피스는 제품 운영이라 실험이 필요 없다. 회원 문의에 답하고 출금을 승인하고
 * 신고를 처리한다. 둘은 같은 콘솔에 있었지만 동선이 섞여 시연 중에 고객 응대 화면을
 * 지나가야 했다.
 *
 * URL은 그대로 둔다. Next 라우트 그룹은 주소에 나타나지 않으므로 백오피스 일곱 화면의
 * 기존 주소가 전부 살아 있다. 갈라지는 것은 셸과 진입점이지 주소가 아니다.
 */

import type { AdminIconName } from "@/components/admin/icons";

/** 운영 관제 메뉴 옆 상태 배지의 종류(components/admin/control/NavStatus) */
export type OpsBadgeKind = "incidents" | "ai" | "hosts" | "database" | "thresholds";

export interface AdminNavItem {
  href: string;
  label: string;
  icon: AdminIconName;
  /** 하위 경로까지 활성으로 볼지. 루트 경로는 정확히 일치할 때만 활성이다 */
  exact?: boolean;
  /** 운영 관제 메뉴 옆 상태 배지. 관제 판에서 읽는다 */
  badge?: OpsBadgeKind;
}

export interface AdminNavGroup {
  label: string;
  items: AdminNavItem[];
  /** 처음에는 접어 둔다. 운영자가 펼치면 그 선택을 기억한다 */
  closedByDefault?: boolean;
}

/**
 * 운영 관제. MSP 관제센터 구조다(2026-09-24 사용자 요구로 개편, 관리자 설계 4-1).
 * 관제는 판단과 기록, 인프라는 판독, 조치는 값을 바꾸는 자리, 시연은 재생 시안이다. 보고서는 bot-1 보고
 * 서비스가 Teams로 보낸 보고를 읽는 자리라 관제에 둔다. 시연은 1280x800 창에서 목록 끝이 스크롤 없이
 * 보이도록 접은 채로 시작한다.
 */
export const OPS_NAV: AdminNavGroup[] = [
  {
    label: "관제",
    items: [
      { href: "/admin", label: "관제센터", icon: "overview", exact: true },
      { href: "/admin/incidents", label: "경보·사건", icon: "incidents", badge: "incidents" },
      { href: "/admin/ai", label: "AI 판단", icon: "ai", badge: "ai" },
      { href: "/admin/reports", label: "보고서", icon: "reports" },
    ],
  },
  {
    label: "인프라",
    items: [
      { href: "/admin/hosts", label: "서버·노드", icon: "hosts", badge: "hosts" },
      { href: "/admin/database", label: "데이터베이스", icon: "database", badge: "database" },
      { href: "/admin/capacity", label: "용량·부하", icon: "monitoring" },
    ],
  },
  {
    label: "조치",
    items: [
      { href: "/admin/thresholds", label: "임계값 관리", icon: "thresholds", badge: "thresholds" },
      { href: "/admin/bots", label: "봇 플릿", icon: "bots" },
    ],
  },
  {
    label: "시연",
    closedByDefault: true,
    items: [
      // 시안. 값은 시뮬레이션이고 백엔드에 연결하지 않았다(flowSim.ts)
      { href: "/admin/flow", label: "서비스 흐름", icon: "flow" },
      // 서비스 흐름과 같은 재생 주행의 추이. 흐름 화면에서 걸어 둔 재생이 여기 차트로 이어진다(flowRun.ts)
      { href: "/admin/monitor", label: "지표 관제", icon: "metrics" },
    ],
  },
];

/** 운영 관제 바닥 줄의 화면. 연동 상태와 연락 체계는 자주 오지 않는 자리라 묶음 밖 바닥에 둔다 */
export const OPS_FOOT: AdminNavItem[] = [{ href: "/admin/settings", label: "설정", icon: "settings" }];

/** 백오피스. 제품 운영이라 실험 대상이 아니다 */
export const BACK_NAV: AdminNavGroup[] = [
  {
    label: "고객 응대",
    items: [
      { href: "/admin/back", label: "처리 대기", icon: "overview", exact: true },
      { href: "/admin/transfers", label: "출금 승인", icon: "transfers" },
      { href: "/admin/support", label: "문의 답변", icon: "support" },
      { href: "/admin/community", label: "커뮤니티 신고", icon: "community" },
      { href: "/admin/members", label: "회원 운영", icon: "members" },
    ],
  },
  {
    label: "구성·거버넌스",
    items: [
      { href: "/admin/config", label: "마켓·공지", icon: "config" },
      { href: "/admin/operators", label: "운영자·역할", icon: "operators" },
      { href: "/admin/audit", label: "감사 로그", icon: "audit" },
    ],
  },
];
