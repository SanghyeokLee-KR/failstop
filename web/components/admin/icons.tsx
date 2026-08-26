/**
 * 관리 평면 내비 아이콘. 16px 그리드 제도(製圖) 어휘: 직선·원호만, stroke 1.5 round.
 * 아이콘은 장식 채널이라 aria-hidden 고정. 의미는 항상 인접 텍스트 라벨이 전달한다.
 * 색은 currentColor 상속만 받아 소비처의 토큰 잉크를 그대로 따른다.
 */

import type { ReactNode } from "react";

export type AdminIconName =
  | "overview"
  | "monitoring"
  | "bots"
  | "chaos"
  | "config"
  | "operators"
  | "audit";

const ICON_SHAPES: Record<AdminIconName, ReactNode> = {
  // 게이지: 눈금 원 + 바늘
  overview: (
    <>
      <circle cx="8" cy="8" r="5.5" />
      <path d="M8 8 L11.2 4.8" />
      <path d="M8 2.5 V4" />
    </>
  ),
  // 펄스 라인
  monitoring: <polyline points="1.75 8.5 5 8.5 6.75 4.75 9.25 11.75 11 8.5 14.25 8.5" />,
  // 격자 노드 4개 + 링크
  bots: (
    <>
      <circle cx="4.75" cy="4.75" r="1.75" />
      <circle cx="11.25" cy="4.75" r="1.75" />
      <circle cx="4.75" cy="11.25" r="1.75" />
      <circle cx="11.25" cy="11.25" r="1.75" />
      <path d="M6.5 4.75 H9.5" />
      <path d="M6.5 11.25 H9.5" />
      <path d="M4.75 6.5 V9.5" />
      <path d="M11.25 6.5 V9.5" />
    </>
  ),
  // 분기: 단일 입력이 두 갈래 종점 노드로 갈라진다
  chaos: (
    <>
      <path d="M2.5 8 H6" />
      <path d="M6 8 L10.6 4.5" />
      <path d="M6 8 L10.6 11.5" />
      <circle cx="12.1" cy="4.5" r="1.5" />
      <circle cx="12.1" cy="11.5" r="1.5" />
    </>
  ),
  // 슬라이더 2열: 트랙 절단부에 썸 원
  config: (
    <>
      <path d="M2.5 5.25 H4.5" />
      <path d="M8 5.25 H13.5" />
      <circle cx="6.25" cy="5.25" r="1.75" />
      <path d="M2.5 10.75 H8" />
      <path d="M11.5 10.75 H13.5" />
      <circle cx="9.75" cy="10.75" r="1.75" />
    </>
  ),
  // 인장 카드: 인물 원 + 정보 줄
  operators: (
    <>
      <rect x="2" y="3.25" width="12" height="9.5" rx="1.5" />
      <circle cx="5.75" cy="8" r="1.6" />
      <path d="M9 6.75 H11.75" />
      <path d="M9 9.25 H11.75" />
    </>
  ),
  // 줄 눈금 문서
  audit: (
    <>
      <rect x="3.75" y="2" width="8.5" height="12" rx="1.5" />
      <path d="M6 5.5 H10" />
      <path d="M6 8 H10" />
      <path d="M6 10.5 H8.25" />
    </>
  ),
};

export function AdminIcon({ name, size = 16 }: { name: AdminIconName; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {ICON_SHAPES[name]}
    </svg>
  );
}
