import type { Metadata } from "next";
import AdminGate from "@/components/admin/AdminGate";

export const metadata: Metadata = {
  title: "관리 콘솔",
  robots: { index: false, follow: false },
};

/**
 * 관리 콘솔은 다크로 시작한다(디자인 시스템 §12-1). 루트 레이아웃이 저장된 테마를 먼저
 * 적용하고, 이 스크립트는 저장값도 명시 테마도 없을 때만 다크를 세운다. 자기가 세웠다는
 * 표시(themeOrigin)를 남겨, 관리 평면을 떠날 때 AdminGate가 그 표시만 거둔다.
 * 첫 그리기 전에 돌아야 흰 화면이 번쩍이지 않아 인라인으로 둔다. 클라이언트 이동으로 들어올
 * 때는 이 스크립트가 다시 돌지 않으므로 AdminGate가 같은 조건으로 한 번 더 세운다.
 */
const adminThemeInit = `try{var d=document.documentElement;if(!localStorage.getItem("theme")&&!d.dataset.theme){d.dataset.theme="dark";d.dataset.themeOrigin="admin";}}catch(e){}`;

/**
 * 세션 게이트만 소유한다. 크롬은 평면별 라우트 그룹이 각자 갖는다.
 * (ops)는 운영 관제, (back)은 백오피스이며 라우트 그룹이라 주소에는 나타나지 않는다.
 */
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <script dangerouslySetInnerHTML={{ __html: adminThemeInit }} />
      <AdminGate>{children}</AdminGate>
    </>
  );
}
