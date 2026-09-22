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

export interface AdminNavItem {
  href: string;
  label: string;
  icon: AdminIconName;
  /** 하위 경로까지 활성으로 볼지. 루트 경로는 정확히 일치할 때만 활성이다 */
  exact?: boolean;
}

export interface AdminNavGroup {
  label: string;
  items: AdminNavItem[];
}

/** 운영 관제. 인프라 상태와 조작, 그리고 시연 */
export const OPS_NAV: AdminNavGroup[] = [
  {
    label: "상황",
    items: [{ href: "/admin", label: "종합 관제", icon: "overview", exact: true }],
  },
  {
    label: "조작",
    items: [
      { href: "/admin/capacity", label: "용량·부하", icon: "monitoring" },
      { href: "/admin/bots", label: "봇 플릿", icon: "bots" },
    ],
  },
];

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
