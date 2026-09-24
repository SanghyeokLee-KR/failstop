/**
 * 서비스 흐름 시안의 아이콘. 두 갈래를 한 이름표로 부른다.
 *
 * 제품 로고(nginx, Spring Boot, PostgreSQL, Prometheus, Grafana)는 simple-icons 16.32.0 의
 * SVG 를 그대로 두고 CSS mask 로만 쓴다. 관리 평면은 헬스 도메인 말고는 무채라서 브랜드색을
 * 들이지 않는다. 모양만 빌리고 색은 currentColor 로 소비처의 잉크를 따른다. 로고 파일은
 * CC0 이지만 상표는 각 회사의 것이라 모양을 고치거나 합성하지 않는다.
 *
 * PostgreSQL 로고는 가는 윤곽선이라 같은 크기의 꽉 찬 로고(Spring Boot) 옆에서 흐리게 보인다. 모양은
 * 그대로 두고 그리는 크기만 1.2배로 키운다(topology.module.css 의 .iconPostgresql). 자리 크기는 그대로라
 * 줄 맞춤이 흔들리지 않는다.
 *
 * 로고가 없는 것(사람, 부하 발생기, 봇, 부하 분산기, 구름, 지구)은 icons.tsx 와 같은 제도 어휘로
 * 직접 그린다. 16px 격자, 선 굵기 1.5, 둥근 끝이다. AWS 서비스 아이콘도 상표라 빌리지 않는다.
 * 구름(클라우드 경계), 지구(CloudFront 같은 가장자리 배포), 한 입구가 세 대상으로 갈라지는 부채꼴(ALB),
 * 쌓인 상자와 더하기(Auto Scaling 그룹), 지도 핀(리전), 이어진 두 점을 두른 상자(VPC),
 * 열린 자물쇠(퍼블릭 서브넷), 원통(DB 묶음), 선반 두 칸(호스트 한 대)을 같은 어휘로 새로 그린다. 그중
 * 구름, 지도 핀, VPC, 자물쇠, 원통, 선반은 묶음 상자의 머리 표식으로만 쓴다. DB 칸 이름표가 13px 에서
 * 로고를 쓰면 윤곽선이 뭉개져 다른 틀의 표식과 굵기가 맞지 않아 원통을 쓴다. 제품 이름은 글자가 적는다.
 * 연결 전 자리의 AI 운영 보고는 줄 세 개가 있는 문서, Teams 는 말풍선으로 그린다. 메신저 제품 로고도
 * 상표라 빌리지 않는다. 온프레미스 AI 서버(ai-1)는 관리 콘솔의 AI 표지와 같은 칩, 그 서버가 놓인 연구소는
 * 건물로 그린다.
 *
 * 구성도 카드의 ALB 는 nginx 로고가 아니라 부채꼴 그림이다. nginx 는 Docker 대역이 ALB 역할을 맡긴
 * 제품이라 AWS 목표 구성을 그리는 카드 면에 두지 않는다. 컨테이너 단위 표(InstanceTable)는 nginx 를 쓴다.
 *
 * 아이콘은 장식이라 늘 aria-hidden 이다. 뜻은 옆의 글자가 전한다.
 */

import type { ReactNode } from "react";
import styles from "./topology.module.css";

export type FlowBrandIcon = "nginx" | "springboot" | "postgresql" | "prometheus" | "grafana";
export type FlowGlyphIcon =
  | "user"
  | "load"
  | "bot"
  | "lb"
  | "cloud"
  | "globe"
  | "asg"
  | "pin"
  | "vpc"
  | "unlock"
  | "db"
  | "server"
  | "report"
  | "chat"
  | "chip"
  | "building";
export type FlowIconName = FlowBrandIcon | FlowGlyphIcon;

const BRAND_CLASS: Record<FlowBrandIcon, string> = {
  nginx: styles.iconNginx,
  springboot: styles.iconSpringboot,
  postgresql: styles.iconPostgresql,
  prometheus: styles.iconPrometheus,
  grafana: styles.iconGrafana,
};

const GLYPH: Record<FlowGlyphIcon, ReactNode> = {
  // 사람. 머리 원과 어깨 호
  user: (
    <>
      <circle cx="8" cy="5.5" r="2.75" />
      <path d="M2.75 13.25 A5.25 5.25 0 0 1 13.25 13.25" />
    </>
  ),
  // 부하 발생기. 한 원점에서 같은 방향으로 거듭 나가는 두 줄
  load: (
    <>
      <path d="M2.75 3.25 V12.75" />
      <path d="M5.5 5.5 H12.75" />
      <path d="M10.5 3.25 L12.75 5.5 L10.5 7.75" />
      <path d="M5.5 10.5 H12.75" />
      <path d="M10.5 8.25 L12.75 10.5 L10.5 12.75" />
    </>
  ),
  // 봇. 안테나를 단 둥근 머리와 두 눈
  bot: (
    <>
      <rect x="3" y="5.5" width="10" height="7.5" rx="2" />
      <path d="M8 5.5 V2.75" />
      <path d="M6 9 V9.5" />
      <path d="M10 9 V9.5" />
      <path d="M1.25 8.5 V10" />
      <path d="M14.75 8.5 V10" />
    </>
  ),
  // 부하 분산기(ALB). 왼쪽의 한 입구에서 부채꼴로 갈라진 세 선이 오른쪽 세 대상에 닿는다.
  // 요청을 여러 노드에 나눠 보내는 자리다. 직각으로 꺾으면 작은 크기에서 글자 E 처럼 읽힌다
  lb: (
    <>
      <circle cx="3.5" cy="8" r="2.25" />
      <path d="M5.75 8 L11.5 3.75" />
      <path d="M5.75 8 H11.25" />
      <path d="M5.75 8 L11.5 12.25" />
      <circle cx="13.25" cy="3" r="1.25" />
      <circle cx="13.25" cy="8" r="1.25" />
      <circle cx="13.25" cy="13" r="1.25" />
    </>
  ),
  // 구름. 클라우드 경계의 머리 표식. 큰 봉우리 하나와 양옆 작은 봉우리로 윤곽만 그린다
  cloud: (
    <path d="M4.5 12.75 H11.5 A2.75 2.75 0 0 0 11.9 7.28 A4 4 0 0 0 4.35 7.6 A2.6 2.6 0 0 0 4.5 12.75 Z" />
  ),
  // 지구. 가장자리 배포(CDN)의 표식. 적도 하나와 경선 둘
  globe: (
    <>
      <circle cx="8" cy="8" r="6.25" />
      <path d="M1.75 8 H14.25" />
      <path d="M8 1.75 C5.4 4.6 5.4 11.4 8 14.25" />
      <path d="M8 1.75 C10.6 4.6 10.6 11.4 8 14.25" />
    </>
  ),
  // Auto Scaling 그룹. 위아래로 쌓인 노드 두 칸과 옆의 더하기. 같은 노드를 한 대 더 붙이는 묶음이다
  asg: (
    <>
      <rect x="1.75" y="2.25" width="7.5" height="4.5" rx="1" />
      <rect x="1.75" y="9.25" width="7.5" height="4.5" rx="1" />
      <path d="M12.75 8.75 V13.75" />
      <path d="M10.25 11.25 H15.25" />
    </>
  ),
  // 리전. 지도 핀. 한 지역의 경계라는 뜻이다
  pin: (
    <>
      <path d="M8 14.25 C8 14.25 3.25 9.9 3.25 6.5 A4.75 4.75 0 0 1 12.75 6.5 C12.75 9.9 8 14.25 8 14.25 Z" />
      <circle cx="8" cy="6.5" r="1.75" />
    </>
  ),
  // VPC. 둥근 상자 안에서 이어진 두 점. 사설 네트워크 경계라는 뜻이다
  vpc: (
    <>
      <rect x="1.75" y="2.75" width="12.5" height="10.5" rx="2" />
      <circle cx="5.25" cy="8" r="1.25" />
      <circle cx="10.75" cy="8" r="1.25" />
      <path d="M6.5 8 H9.5" />
    </>
  ),
  // 퍼블릭 서브넷. 걸쇠가 열린 자물쇠. 바깥에서 들어오는 요청을 받는 칸이다
  unlock: (
    <>
      <rect x="3" y="7.25" width="10" height="6.5" rx="1.25" />
      <path d="M5.5 7.25 V5 A2.5 2.5 0 0 1 10.4 4.3" />
    </>
  ),
  // DB 묶음. 위 뚜껑 타원과 옆선, 가운데 띠 하나를 두른 원통
  db: (
    <>
      <ellipse cx="8" cy="3.75" rx="5.25" ry="1.75" />
      <path d="M2.75 3.75 V12.25 A5.25 1.75 0 0 0 13.25 12.25 V3.75" />
      <path d="M2.75 8 A5.25 1.75 0 0 0 13.25 8" />
    </>
  ),
  // 호스트 한 대. 선반 두 칸과 표시등
  server: (
    <>
      <rect x="2.25" y="2.5" width="11.5" height="4.75" rx="1" />
      <rect x="2.25" y="8.75" width="11.5" height="4.75" rx="1" />
      <path d="M4.75 4.9 H5.25" />
      <path d="M4.75 11.1 H5.25" />
    </>
  ),
  // 운영 보고. 모서리 하나를 접은 문서와 글 줄 셋
  report: (
    <>
      <path d="M3.25 1.75 H9.75 L12.75 4.75 V14.25 H3.25 Z" />
      <path d="M9.75 1.75 V4.75 H12.75" />
      <path d="M5.5 7.5 H10.5" />
      <path d="M5.5 9.75 H10.5" />
      <path d="M5.5 12 H8.5" />
    </>
  ),
  // AI 추론 서버. 핀이 네 방향으로 난 칩. 관리 콘솔 사이드바의 AI 표지(icons.tsx ai)와 같은 모양이다
  chip: (
    <>
      <rect x="4.25" y="4.25" width="7.5" height="7.5" rx="1.5" />
      <path d="M6.5 2 V4.25 M9.5 2 V4.25 M6.5 11.75 V14 M9.5 11.75 V14 M2 6.5 H4.25 M2 9.5 H4.25 M11.75 6.5 H14 M11.75 9.5 H14" />
    </>
  ),
  // 온프레미스(연구소). 높낮이가 다른 두 동과 창 여섯, 바닥선
  building: (
    <>
      <path d="M3 14.25 V3.25 A1 1 0 0 1 4 2.25 H10 A1 1 0 0 1 11 3.25 V14.25" />
      <path d="M11 7.25 H12.75 A1 1 0 0 1 13.75 8.25 V14.25" />
      <path d="M1.75 14.25 H14.25" />
      <path d="M5.5 5 H6 M8 5 H8.5 M5.5 7.75 H6 M8 7.75 H8.5 M5.5 10.5 H6 M8 10.5 H8.5" />
    </>
  ),
  // 메시지 채널. 꼬리가 왼쪽 아래로 난 말풍선
  chat: (
    <>
      <path d="M3.5 2.75 H12.5 A1.75 1.75 0 0 1 14.25 4.5 V9.5 A1.75 1.75 0 0 1 12.5 11.25 H7 L3.75 13.75 V11.25 H3.5 A1.75 1.75 0 0 1 1.75 9.5 V4.5 A1.75 1.75 0 0 1 3.5 2.75 Z" />
      <path d="M5 6 H11" />
      <path d="M5 8.25 H9" />
    </>
  ),
};

function isBrand(name: FlowIconName): name is FlowBrandIcon {
  return name in BRAND_CLASS;
}

export function FlowIcon({ name, size = 16 }: { name: FlowIconName; size?: number }) {
  if (isBrand(name)) {
    return (
      <span
        className={`${styles.brandIcon} ${BRAND_CLASS[name]}`}
        style={{ width: size, height: size }}
        aria-hidden="true"
      />
    );
  }
  return (
    <svg
      className={styles.glyphIcon}
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {GLYPH[name]}
    </svg>
  );
}

export default FlowIcon;
