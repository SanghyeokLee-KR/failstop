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
  | "flow"
  | "metrics"
  | "incidents"
  | "ai"
  | "hosts"
  | "database"
  | "thresholds"
  | "search"
  | "globe"
  | "clock"
  | "errors"
  | "more"
  | "chevronDown"
  | "chat"
  | "reports"
  | "settings"
  | "close"
  | "send";

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
  // 경보·사건: 종 몸통과 추. 울린 것을 모아 보는 자리
  incidents: (
    <>
      <path d="M4 11.25 V7.5 A4 4 0 0 1 12 7.5 V11.25 Z" />
      <path d="M2.75 11.25 H13.25" />
      <path d="M6.75 13.25 H9.25" />
    </>
  ),
  // AI 판단: 핀이 달린 연산 칩. 반짝임 장식 대신 판단을 계산하는 장치로 그린다(디자인 시스템 §12-10)
  ai: (
    <>
      <rect x="4.25" y="4.25" width="7.5" height="7.5" rx="1.5" />
      <path d="M6.5 2 V4.25 M9.5 2 V4.25 M6.5 11.75 V14 M9.5 11.75 V14 M2 6.5 H4.25 M2 9.5 H4.25 M11.75 6.5 H14 M11.75 9.5 H14" />
    </>
  ),
  // 서버·노드: 겹친 서버 두 대와 표시등
  hosts: (
    <>
      <rect x="2.5" y="2.75" width="11" height="4.25" rx="1" />
      <rect x="2.5" y="9" width="11" height="4.25" rx="1" />
      <path d="M5 4.9 H5.1" />
      <path d="M5 11.1 H5.1" />
    </>
  ),
  // 데이터베이스: 원통 두 칸
  database: (
    <>
      <path d="M3 4 A5 1.75 0 0 0 13 4 A5 1.75 0 0 0 3 4 Z" />
      <path d="M3 4 V12 A5 1.75 0 0 0 13 12 V4" />
      <path d="M3 8 A5 1.75 0 0 0 13 8" />
    </>
  ),
  // 임계값 관리: 계기 레일과 그 위에 선 임계 눈금
  thresholds: (
    <>
      <rect x="1.75" y="6.75" width="12.5" height="2.5" rx="1.25" />
      <path d="M3.25 8 H7.5" />
      <path d="M10.5 4 V12" />
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
  // 돋보기
  search: (
    <>
      <circle cx="7" cy="7" r="4.25" />
      <path d="M10.25 10.25 L13.5 13.5" />
    </>
  ),
  // 외부 가용성: 경선과 적도가 있는 지구
  globe: (
    <>
      <circle cx="8" cy="8" r="5.75" />
      <path d="M2.25 8 H13.75" />
      <path d="M8 2.25 C6.25 4 6.25 12 8 13.75 C9.75 12 9.75 4 8 2.25 Z" />
    </>
  ),
  // 응답 지연: 시계
  clock: (
    <>
      <circle cx="8" cy="8" r="5.75" />
      <path d="M8 4.75 V8 L10.25 9.5" />
    </>
  ),
  // 5xx 오류: 원 안의 느낌표
  errors: (
    <>
      <circle cx="8" cy="8" r="5.75" />
      <path d="M8 5 V8.5" />
      <path d="M8 10.9 V11" />
    </>
  ),
  // 더 보기: 세로 점 셋. 점도 선 굵기로 그린다
  more: <path d="M8 3.75 V3.85 M8 7.95 V8.05 M8 12.15 V12.25" strokeWidth="2.25" />,
  chevronDown: <path d="M4.5 6.25 L8 9.75 L11.5 6.25" />,
  // AI 상담: 말풍선 안의 점 셋. 묻고 답하는 대화다. 고객 문의(support)의 한 줄 말풍선과 가른다
  chat: (
    <>
      <path d="M2.75 3.75 A1.5 1.5 0 0 1 4.25 2.25 H11.75 A1.5 1.5 0 0 1 13.25 3.75 V9.25 A1.5 1.5 0 0 1 11.75 10.75 H7 L4.25 13.25 V10.75 A1.5 1.5 0 0 1 2.75 9.25 Z" />
      <path d="M5.6 6.5 V6.6 M8 6.5 V6.6 M10.4 6.5 V6.6" strokeWidth="2" />
    </>
  ),
  // 보고서: 접힌 모서리 문서 위의 추세선. 줄만 그은 감사 로그와 가른다
  reports: (
    <>
      <path d="M3.5 2 H10 L12.5 4.5 V14 H3.5 Z" />
      <path d="M10 2 V4.5 H12.5" />
      <polyline points="5.25 11 7 8.75 8.75 10 10.75 7.25" />
    </>
  ),
  // 설정: 톱니 여섯 개와 축
  settings: (
    <>
      <path d="M6.39 3.58 L6.78 1.72 L9.22 1.72 L9.61 3.58 L11.02 4.4 L12.83 3.8 L14.05 5.92 L12.63 7.18 L12.63 8.82 L14.05 10.08 L12.83 12.2 L11.02 11.6 L9.61 12.42 L9.22 14.28 L6.78 14.28 L6.39 12.42 L4.98 11.6 L3.17 12.2 L1.95 10.08 L3.37 8.82 L3.37 7.18 L1.95 5.92 L3.17 3.8 L4.98 4.4 Z" />
      <circle cx="8" cy="8" r="2" />
    </>
  ),
  close: <path d="M4.25 4.25 L11.75 11.75 M11.75 4.25 L4.25 11.75" />,
  // 보내기: 위로 가는 화살
  send: <path d="M8 12.75 V3.5 M4.25 7.25 L8 3.5 L11.75 7.25" />,
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
