import type { Metadata } from "next";
import AdminGate from "@/components/admin/AdminGate";

export const metadata: Metadata = {
  title: "관리 콘솔",
  robots: { index: false, follow: false },
};

/**
 * 세션 게이트만 소유한다. 크롬은 평면별 라우트 그룹이 각자 갖는다.
 * (ops)는 운영 관제, (back)은 백오피스이며 라우트 그룹이라 주소에는 나타나지 않는다.
 */
export default function Layout({ children }: { children: React.ReactNode }) {
  return <AdminGate>{children}</AdminGate>;
}
