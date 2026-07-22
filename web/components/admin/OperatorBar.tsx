"use client";

/**
 * 관리 평면 상단 운영자 바(§4-1). 운영자명·역할 배지·세션 만료 카운트다운·인증서 전환·로그아웃.
 * 인증서 전환은 mTLS 신원 재제시를 모사한다. 2인 승인 시연에 필요하다(다른 운영자로 승인).
 */

import { useEffect, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { useAdminSession } from "@/lib/admin/store";
import { toast } from "@/components/Toast";
import ThemeToggle from "@/components/ThemeToggle";
import RoleBadge from "./RoleBadge";
import styles from "./admin.module.css";

function remain(expiresAt: string): string {
  const ms = new Date(expiresAt).getTime() - Date.now();
  if (ms <= 0) return "00:00";
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

export default function OperatorBar() {
  const session = useAdminSession();
  const [, tick] = useState(0);

  useEffect(() => {
    const iv = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(iv);
  }, []);

  if (!session) return null;

  const certs = getAdminApi().listDemoCerts();

  const switchCert = async (certId: string) => {
    if (certId === session.operatorId) return;
    try {
      await getAdminApi().establishSession(certId);
      toast(`${certId} 인증서로 세션을 전환했어요`, "info");
    } catch (e) {
      toast(e instanceof Error ? e.message : "세션 전환에 실패했어요", "error");
    }
  };

  return (
    <header className={styles.opbar}>
      <span className={styles.opbarPlane}>관리 평면 · admin.exchange.local</span>
      <div className={styles.opbarRight}>
        <span className={styles.opIdentity}>
          <span className={styles.opName}>{session.displayName}</span>
          <span className={styles.opId}>{session.operatorId}</span>
        </span>
        <span className={styles.roleRow}>
          {session.roles.map((r) => (
            <RoleBadge key={r} role={r} />
          ))}
        </span>
        <span className={styles.sessionTtl} role="status" aria-label="세션 만료까지 남은 시간">
          세션 <b className="num">{remain(session.expiresAt)}</b>
        </span>
        {certs.length > 0 && (
          <label className={styles.certSwitch}>
            <span className="sr-only">인증서 전환</span>
            <select
              value={session.operatorId}
              onChange={(e) => void switchCert(e.target.value)}
              aria-label="mTLS 인증서 전환"
            >
              {certs.map((c) => (
                <option key={c.certId} value={c.certId}>
                  {c.certId} 인증서
                </option>
              ))}
            </select>
          </label>
        )}
        <ThemeToggle />
        <button type="button" className={styles.logout} onClick={() => getAdminApi().endSession()}>
          세션 종료
        </button>
      </div>
    </header>
  );
}
