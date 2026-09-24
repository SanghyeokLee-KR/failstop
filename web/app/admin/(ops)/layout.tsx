import AdminShell from "@/components/admin/AdminShell";
import { AiModeFoot } from "@/components/admin/control/NavStatus";
import OpsSearch from "@/components/admin/control/OpsSearch";
import OpsSignals from "@/components/admin/control/OpsSignals";
import { OPS_NAV } from "@/lib/admin/nav";

/**
 * 운영 관제 셸. 관제, 인프라, 조치, 시연이 여기 있다. 운영자 바에 관제 검색과 전체 상태·경보 종을,
 * 사이드바 바닥에 AI 운영 모드를 둔다
 */
export default function OpsLayout({ children }: { children: React.ReactNode }) {
  return (
    <AdminShell
      title="운영 관제"
      groups={OPS_NAV}
      crossLink={{ href: "/admin/back", label: "백오피스로" }}
      sidebarFoot={<AiModeFoot />}
      barSearch={<OpsSearch />}
      barSignals={<OpsSignals />}
    >
      {children}
    </AdminShell>
  );
}
