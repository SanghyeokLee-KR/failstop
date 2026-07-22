// 홈 대시보드. 정적 export용 서버 셸 + 클라이언트 본체 (§ 대시보드_설계.md).
import type { Metadata } from "next";
import DashboardClient from "./DashboardClient";

export const metadata: Metadata = { title: "홈" };

export default function HomePage() {
  return <DashboardClient />;
}
