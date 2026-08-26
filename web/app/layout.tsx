import type { Metadata, Viewport } from "next";
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "@/styles/tokens.css";
import "./globals.css";
import { Toaster } from "@/components/Toast";
import HeaderNav from "@/components/HeaderNav";
import GlobalErrorReporter from "@/components/GlobalErrorReporter";

export const metadata: Metadata = {
  title: {
    default: "페일스톱 · 모의 거래소",
    template: "%s · 페일스톱",
  },
  description: "장애를 견디는 모의 암호화폐 거래소 페일스톱. 고가용성 캡스톤 데모",
};

// 고정형 데스크톱 전용이다(데스크톱_레이아웃설계 §4: 반응형 리플로우 없음, 모바일 미지원).
// width=device-width는 좁은 화면에 레이아웃을 맞추라는 지시라 이 정책과 정면으로 어긋난다.
// 그리드 기준 폭을 그대로 알려 좁은 화면에서는 축소해 보이게 한다. 데스크톱 브라우저는
// 이 값을 무시하므로 실제 대상 환경의 렌더는 달라지지 않는다.
export const viewport: Viewport = {
  width: 1280,
  initialScale: 1,
  viewportFit: "cover",
};

/** FOUC 방지: 저장된 테마를 first paint 전에 적용 ([data-theme] 우선, 미지정 시 시스템) */
const themeInit = `try{var t=localStorage.getItem("theme");if(t==="dark"||t==="light")document.documentElement.dataset.theme=t;}catch(e){}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeInit }} />
      </head>
      <body>
        <a href="#main" className="skip-link">
          본문 바로가기
        </a>
        <HeaderNav />
        {/* 스킵 링크·리딩 순서의 본문 시작점. 각 페이지는 이 안에서 자체 main 랜드마크를 렌더한다 */}
        <div id="main" tabIndex={-1}>
          {children}
        </div>
        <Toaster />
        {/* 아무도 잡지 않은 Promise rejection을 토스트로 통지한다. 렌더 예외는 error.tsx 담당 */}
        <GlobalErrorReporter />
      </body>
    </html>
  );
}
