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
  | "config"
  | "transfers"
  | "support"
  | "community"
  | "members"
  | "operators"
  | "audit"
  | "drill"
  | "flow"
  | "metrics";

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
  // 양방향 이동: 들어오는 화살표와 나가는 화살표. 입출금은 방향이 둘이라 한쪽만 그리면
  // 절반만 뜻하게 된다. 같은 제도 어휘(직선 + 45도 촉)로 맞춘다
  // 사람 실루엣. 운영자(operators)와 구분되게 단수 형태로 둔다
  members: (
    <>
      <circle cx="8" cy="5.75" r="2.75" />
      <path d="M2.75 13.25 A5.25 5.25 0 0 1 13.25 13.25" />
    </>
  ),
  // 말풍선 + 신고 표식. 게시판 운영은 대화면 위의 판정이다
  community: (
    <>
      <path d="M2.25 4.25 A1.5 1.5 0 0 1 3.75 2.75 H12.25 A1.5 1.5 0 0 1 13.75 4.25 V9.75 A1.5 1.5 0 0 1 12.25 11.25 H6.5 L3.75 13.5 V11.25 A1.5 1.5 0 0 1 2.25 9.75 Z" />
      <path d="M8 5 V7.5" />
      <path d="M8 9.1 V9.2" />
    </>
  ),
  transfers: (
    <>
      <path d="M3.25 5.25 H11.5" />
      <path d="M9.25 3.25 L11.5 5.25 L9.25 7.25" />
      <path d="M12.75 10.75 H4.5" />
      <path d="M6.75 8.75 L4.5 10.75 L6.75 12.75" />
    </>
  ),
  // 말풍선: 묻고 답하는 자리. 꼬리 방향이 아래라 들어온 말을 뜻한다
  support: (
    <>
      <path d="M2.75 4.25 H13.25 V10.75 H7.25 L4.75 13.25 V10.75 H2.75 Z" />
      <path d="M5.5 7.5 H10.5" />
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
  // 단계 기둥: 세로선 위 세 마디
  drill: (
    <>
      <path d="M4.25 2.75 V13.25" />
      <circle cx="4.25" cy="4.5" r="1.25" />
      <circle cx="4.25" cy="8" r="1.25" />
      <circle cx="4.25" cy="11.5" r="1.25" />
      <path d="M7.5 4.5 H12.25" />
      <path d="M7.5 8 H12.25" />
      <path d="M7.5 11.5 H10" />
    </>
  ),
  /* 서비스 흐름. 한 입구에서 두 갈래로 나가는 연결 */
  flow: (
    <>
      <circle cx="3.5" cy="8" r="1.75" />
      <circle cx="12.5" cy="4" r="1.75" />
      <circle cx="12.5" cy="12" r="1.75" />
      <path d="M5.25 8 H8 V4 H10.75" />
      <path d="M8 8 V12 H10.75" />
    </>
  ),
  /* 지표 관제. 바닥선 위 높이가 다른 세 막대. 용량·부하의 펄스 선(monitoring)과 겹쳐 보이지 않게 막대로 둔다 */
  metrics: (
    <>
      <path d="M2.25 13.25 H13.75" />
      <path d="M4.75 10.75 V8" />
      <path d="M8 10.75 V3.25" />
      <path d="M11.25 10.75 V5.75" />
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
