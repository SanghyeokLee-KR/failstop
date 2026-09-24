"use client";

/**
 * 관리 평면 상단 운영자 바(§4-1). 왼쪽에 평면이 넘기는 검색 자리, 오른쪽에 상태 신호·현재 시각·테마·
 * 사용자 메뉴를 둔다. 검색과 상태 신호는 관제 데이터를 읽으므로 운영 관제 셸만 넘기고 백오피스는 비운다.
 *
 * 사용자 메뉴가 신원, 세션 남은 시간, 인증서 전환, 세션 종료를 싣는다. 인증서 전환은 mTLS 신원 재제시를
 * 모사한다. 세션이 1분 안에 끝나면 메뉴를 열지 않아도 바에 ▲ 남은 시간을 띄운다.
 */

import { useEffect, useRef, useState, type ReactNode } from "react";
import { getAdminApi } from "@/lib/admin/api";
import { ROLE_LABEL } from "@/lib/admin/roles";
import { useAdminSession } from "@/lib/admin/store";
import { useBeat } from "@/lib/admin/ticker";
import { toast } from "@/components/Toast";
import ThemeToggle from "@/components/ThemeToggle";
import { AdminIcon } from "./icons";
import RoleBadge from "./RoleBadge";
import styles from "./admin.module.css";

// 임박 낭독 임계. 이 값 이하에서만 polite 리전이 1회 경고한다(매초 낭독 방지)
const IMMINENT_SEC = 60;
const WEEKDAY = ["일", "월", "화", "수", "목", "금", "토"];

export function remainMs(expiresAt: string): number {
  return Math.max(0, new Date(expiresAt).getTime() - Date.now());
}

export function mmss(ms: number): string {
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

function clockText(now: number): string {
  const d = new Date(now);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getMonth() + 1}월 ${d.getDate()}일 (${WEEKDAY[d.getDay()]}) ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** 세션 남은 시간 mm:ss. 이 글자만 박자를 탄다 */
export function SessionLeft({ expiresAt }: { expiresAt: string }) {
  useBeat();
  return <>{mmss(remainMs(expiresAt))}</>;
}

function UserMenu() {
  const session = useAdminSession();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  // 팝오버 닫힘: 바깥 클릭·Escape. Escape는 트리거로 포커스를 되돌린다
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setOpen(false);
        ref.current?.querySelector("button")?.focus();
      }
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  if (!session) return null;
  const certs = getAdminApi().listDemoCerts();
  const primary = session.roles.includes("SUPER") ? "SUPER" : session.roles[0];

  const switchCert = async (certId: string) => {
    setOpen(false);
    if (certId === session.operatorId) return;
    try {
      await getAdminApi().establishSession(certId);
      toast(`${certId} 인증서로 세션 전환 완료`, "info");
    } catch (e) {
      toast(e instanceof Error ? e.message : "세션 전환 실패", "error");
    }
  };

  return (
    <div className={styles.userMenu} ref={ref}>
      <button
        type="button"
        className={styles.userBtn}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={`${session.displayName} · ${primary ? ROLE_LABEL[primary] : "역할 없음"} · 사용자 메뉴`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={styles.avatar} aria-hidden="true">
          {session.displayName.slice(0, 1)}
        </span>
        <span className={styles.userText} aria-hidden="true">
          <span className={styles.userName}>{session.displayName}</span>
          <span className={styles.userRole}>{primary ? ROLE_LABEL[primary] : ""}</span>
        </span>
        <span className={styles.userChevron} aria-hidden="true">
          <AdminIcon name="chevronDown" size={14} />
        </span>
      </button>
      {open && (
        <div className={styles.userPanel} role="group" aria-label="사용자 메뉴">
          <div className={styles.userPanelHead}>
            <span className={styles.userPanelName}>{session.displayName}</span>
            <span className={styles.userPanelId}>{session.operatorId}</span>
            <span className={styles.roleRow}>
              {session.roles.map((r) => (
                <RoleBadge key={r} role={r} />
              ))}
            </span>
          </div>
          <div className={styles.userPanelRow}>
            <span>세션 남은 시간</span>
            <b className="num">
              <SessionLeft expiresAt={session.expiresAt} />
            </b>
          </div>
          {certs.length > 0 && (
            // select 금지: 포커스 상태에서 화살표 키가 스치기만 해도 감사상 신원이 바뀐다.
            // 전환은 열기·선택 2클릭의 명시 행위로 만든다(신원 전환이라 확인 다이얼로그 대신 거리로 충분)
            <div className={styles.userPanelGroup} role="group" aria-label="전환할 인증서">
              <span className={styles.userPanelLabel}>mTLS 인증서 전환</span>
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
          <div className={styles.userPanelFoot}>
            <button type="button" className={styles.logout} onClick={() => getAdminApi().endSession()}>
              세션 종료
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/** 현재 시각. 매초 바뀌는 글자라 이 조각만 콘솔 공용 박자를 탄다 */
function Clock() {
  const now = useBeat();
  return (
    <time className={`${styles.clock} num`} dateTime={new Date(now).toISOString()}>
      {clockText(now)}
    </time>
  );
}

/** 세션 만료 임박·만료 표시와 그 낭독. 남은 시간을 매초 읽으므로 이 조각만 박자를 탄다 */
function SessionWarn({ expiresAt }: { expiresAt: string }) {
  useBeat();
  const ms = remainMs(expiresAt);
  const expired = ms === 0;
  const imminent = !expired && ms <= IMMINENT_SEC * 1000;
  // 낭독 문구는 불리언에만 의존한다. 매초 바뀌는 잔여 초를 넣지 않아야 polite 리전이 전이마다 1회만 낭독한다.
  // 만료 문구가 없으면 임박 경고 뒤 정작 만료 순간에 낭독이 멎는다
  const liveMsg = expired
    ? "세션이 만료되었습니다. 다시 인증해야 합니다"
    : imminent
      ? "세션이 1분 안에 만료됩니다. 곧 다시 인증해야 합니다"
      : "";

  return (
    <>
      {(imminent || expired) && (
        // 매초 바뀌는 카운트다운은 낭독 대상에서 제외(aria-hidden). 임박 시 아래 polite 리전이 1회만 고지한다
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
              <span className={styles.sessionWarn}>▲ 세션</span>
              <b className="num">{mmss(ms)}</b>
            </>
          )}
        </span>
      )}
      <span className="sr-only" role="status" aria-live="polite">
        {liveMsg}
      </span>
    </>
  );
}

export default function OperatorBar({ search, signals }: {
  /** 왼쪽 검색 자리. 운영 관제 셸만 넘긴다 */
  search?: ReactNode;
  /** 시각 앞의 상태 신호(전체 상태, 경보 종). 운영 관제 셸만 넘긴다 */
  signals?: ReactNode;
}) {
  const session = useAdminSession();
  if (!session) return null;
  return (
    <header className={styles.opbar}>
      <div className={styles.opbarLead}>{search}</div>
      <div className={styles.opbarRight}>
        {signals}
        <SessionWarn expiresAt={session.expiresAt} />
        <Clock />
        <ThemeToggle size="sm" />
        <UserMenu />
      </div>
    </header>
  );
}
