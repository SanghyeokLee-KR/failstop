import AdminShell from "@/components/admin/AdminShell";
import { BACK_NAV } from "@/lib/admin/nav";

/** 백오피스 셸. 제품 운영이라 실험 대상이 아니다 */
export default function BackLayout({ children }: { children: React.ReactNode }) {
  return (
    <AdminShell title="백오피스" groups={BACK_NAV} crossLink={{ href: "/admin", label: "운영 관제로" }}>
      {children}
    </AdminShell>
  );
}
