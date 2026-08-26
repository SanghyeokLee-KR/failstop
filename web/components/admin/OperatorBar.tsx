"use client";

/**
 * 관리 평면 상단 운영자 바(§4-1). 운영자명·역할 배지·세션 만료 카운트다운·인증서 전환·로그아웃.
 * 인증서 전환은 mTLS 신원 재제시를 모사한다. 2인 승인 시연에 필요하다(다른 운영자로 승인).
 * 운영자 ID는 인증서 트리거가 표시하므로 신원 행은 표시 이름만 남긴다.
 */

import { useEffect, useRef, useState } from "react";
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
  const [certOpen, setCertOpen] = useState(false);
  const certRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const iv = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(iv);
  }, []);

  // 팝오버 닫힘: 바깥 클릭·Escape. Escape는 트리거로 포커스를 되돌린다
  useEffect(() => {
    if (!certOpen) return;
    const onDown = (e: MouseEvent) => {
      if (certRef.current && !certRef.current.contains(e.target as Node)) setCertOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setCertOpen(false);
        certRef.current?.querySelector("button")?.focus();
      }
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [certOpen]);

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
    setCertOpen(false);
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
            // select 금지: 포커스 상태에서 화살표 키가 스치기만 해도 감사상 신원이 바뀐다.
            // 전환은 열기·선택 2클릭의 명시 행위로 만든다(신원 전환이라 확인 다이얼로그 대신 거리로 충분)
            <div className={styles.certSwitch} ref={certRef}>
              <button
                type="button"
                className={styles.certBtn}
                aria-haspopup="true"
                aria-expanded={certOpen}
                aria-label="mTLS 인증서 전환"
                onClick={() => setCertOpen((v) => !v)}
              >
                {session.operatorId} 인증서
              </button>
              {certOpen && (
                <div className={styles.certMenu} role="group" aria-label="전환할 인증서">
                  {certs.map((c) => {
                    const current = c.certId === session.operatorId;
                    return (
                      <button
                        key={c.certId}
                        type="button"
                        className={styles.certMenuItem}
                        disabled={current}
                        onClick={() => void switchCert(c.certId)}
                      >
                        {c.certId} 인증서
                        <span className={styles.certMenuMeta}>{current ? "현재 신원" : c.subjectCn}</span>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
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
