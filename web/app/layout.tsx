import type { Metadata, Viewport } from "next";
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "@/styles/tokens.css";
import "./globals.css";
import { Toaster } from "@/components/Toast";
import HeaderNav from "@/components/HeaderNav";

export const metadata: Metadata = {
  title: {
    default: "가상 암호화폐 거래소",
    template: "%s · 가상 암호화폐 거래소",
  },
  description: "고가용성 가상 암호화폐 거래소, 완전 시뮬레이션 캡스톤 데모",
};

export const viewport: Viewport = {
  width: "device-width",
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
      </body>
    </html>
  );
}
