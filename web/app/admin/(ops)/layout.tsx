import AdminShell from "@/components/admin/AdminShell";
import { OPS_NAV } from "@/lib/admin/nav";

/** 운영 관제 셸. 인프라 상태와 조작, 시연이 여기 있다 */
export default function OpsLayout({ children }: { children: React.ReactNode }) {
  return (
    <AdminShell title="운영 관제" groups={OPS_NAV} crossLink={{ href: "/admin/back", label: "백오피스로" }}>
      {children}
    </AdminShell>
  );
}
