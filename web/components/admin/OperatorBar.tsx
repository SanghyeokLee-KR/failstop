"use client";

/**
 * 관리 평면 상단 운영자 바(§4-1). 운영자명·역할 배지·세션 만료 카운트다운·인증서 전환·로그아웃.
 * 인증서 전환은 mTLS 신원 재제시를 모사한다. 2인 승인 시연에 필요하다(다른 운영자로 승인).
 * 운영자 ID는 인증서 select가 표시하므로 신원 행은 표시 이름만 남긴다.
 */

import { useEffect, useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { useAdminSession } from "@/lib/admin/store";
import { toast } from "@/components/Toast";
import ThemeToggle from "@/components/ThemeToggle";
import RoleBadge from "./RoleBadge";
import styles from "./admin.module.css";

// 임박 낭독 임계. 이 값 이하에서만 polite 리전이 1회 경고한다(매초 낭독 방지)
const IMMINENT_SEC = 60;

function remainMs(expiresAt: string): number {
  return Math.max(0, new Date(expiresAt).getTime() - Date.now());
}

function mmss(ms: number): string {
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

  const ms = remainMs(session.expiresAt);
  const expired = ms === 0;
  const imminent = !expired && ms <= IMMINENT_SEC * 1000;
  // 낭독 문구는 불리언에만 의존한다. 매초 바뀌는 잔여 초를 넣지 않아야 polite 리전이 전이마다 1회만 낭독한다.
  // 만료 문구가 없으면 임박 경고 뒤 정작 만료 순간에 낭독이 멎는다
  const liveMsg = expired
    ? "세션이 만료됐습니다. 다시 인증해야 합니다."
    : imminent
      ? "세션 만료가 1분 이내로 임박했습니다. 곧 다시 인증해야 합니다."
      : "";

  const certs = getAdminApi().listDemoCerts();

  const switchCert = async (certId: string) => {
    if (certId === session.operatorId) return;
    try {
      await getAdminApi().establishSession(certId);
      toast(`${certId} 인증서로 세션을 전환했습니다`, "info");
    } catch (e) {
      toast(e instanceof Error ? e.message : "세션 전환에 실패했습니다", "error");
    }
  };

  return (
    <header className={styles.opbar}>
      <div className={styles.opbarRight}>
        <span className={styles.opbarSeg}>
          <span className={styles.opName}>{session.displayName}</span>
          <span className={styles.roleRow}>
            {session.roles.map((r) => (
              <RoleBadge key={r} role={r} />
            ))}
          </span>
        </span>
        <span className={styles.opbarDivider} aria-hidden="true" />
        <span className={styles.opbarSeg}>
          {/* 매초 바뀌는 카운트다운은 낭독 대상에서 제외(aria-hidden). 임박 시 아래 polite 리전이 1회만 고지한다 */}
          <span
            className={styles.sessionTtl}
            data-imminent={imminent ? "" : undefined}
            data-expired={expired ? "" : undefined}
            aria-hidden="true"
          >
            {expired ? (
              // 만료 후 00:00 카운트다운을 계속 보여주면 살아 있는 세션으로 읽힌다
              <span className={styles.sessionWarn}>■ 세션 만료</span>
            ) : (
              <>
                {imminent && <span className={styles.sessionWarn}>▲ 만료 임박</span>}
                세션 <b className="num">{mmss(ms)}</b>
              </>
            )}
          </span>
          <span className="sr-only" role="status" aria-live="polite">
            {liveMsg}
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
        </span>
        <span className={styles.opbarDivider} aria-hidden="true" />
        <span className={styles.opbarSeg}>
          <ThemeToggle size="sm" />
          <button type="button" className={styles.logout} onClick={() => getAdminApi().endSession()}>
            세션 종료
          </button>
        </span>
      </div>
    </header>
  );
}
