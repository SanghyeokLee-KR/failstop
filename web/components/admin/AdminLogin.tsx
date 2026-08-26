"use client";

/**
 * 관리 평면 진입 화면(§1-3). mTLS 클라이언트 인증서 제시 → 신원 유래 단기 세션 확립.
 * 데모는 브라우저 인증서 저장소 설치분을 인증서 목록으로 모사한다(§1-6).
 * 인증서가 없으면 접근 불가 안내만 노출한다(고객 평면과 물리 분리).
 */

import { useState } from "react";
import { getAdminApi } from "@/lib/admin/api";
import BrandMark from "@/components/BrandMark";
import { toast } from "@/components/Toast";
import styles from "./admin.module.css";

// http 모드는 mTLS를 TLS 계층(브라우저 인증서)이 처리하고 앱은 POST /admin/v1/session으로
// 신원 유래 세션만 발급받는다. 인증서 열거가 없으므로 세션 확립 액션을 직접 노출한다.
const IS_HTTP = process.env.NEXT_PUBLIC_DATASOURCE === "http";

export default function AdminLogin() {
  const certs = getAdminApi().listDemoCerts();
  const [busy, setBusy] = useState<string | null>(null);

  const present = async (certId: string) => {
    setBusy(certId);
    try {
      await getAdminApi().establishSession(certId);
    } catch (e) {
      toast(e instanceof Error ? e.message : "세션 확립에 실패했습니다", "error");
      setBusy(null);
    }
  };

  return (
    <main className={styles.login}>
      <section className={`card ${styles.loginCard}`} aria-label="관리 평면 접속">
        {/* 제도 도면 타이틀 블록: 마크 + 평면 식별 명판 + hairline */}
        <div className={styles.loginPlate}>
          <span className={styles.loginPlateMark} aria-hidden="true">
            <BrandMark size={18} />
          </span>
          <span className={styles.loginPlateText}>페일스톱 관리 평면 · admin.exchange.local</span>
        </div>
        <h1 className={styles.loginTitle}>관리 평면 접속</h1>
        <p className={styles.loginDesc}>운영자 인증서 신원으로 접속합니다. 세션은 단기 발급됩니다.</p>

        {certs.length === 0 && IS_HTTP ? (
          <>
            <span className={styles.loginPickLabel}>브라우저 클라이언트 인증서로 세션 확립</span>
            <button
              type="button"
              className={styles.certOption}
              onClick={() => void present("")}
              disabled={busy !== null}
              aria-busy={busy === ""}
            >
              <span className={styles.certCn}>운영자 mTLS 세션 발급</span>
              <span className={styles.certIssuer}>POST /admin/v1/session</span>
              <span className={styles.certAction}>{busy === "" ? "확립 중" : "세션 확립"}</span>
            </button>
            <p className={styles.loginHint}>
              TLS 핸드셰이크에서 제시된 운영자 인증서 신원으로 단기 세션을 발급합니다. 인증서 미설치·비인가 신원은 서버가 거절합니다.
            </p>
          </>
        ) : certs.length === 0 ? (
          <div className={styles.loginNoCert} role="alert">
            <span className={styles.loginNoCertTitle}>클라이언트 인증서를 찾지 못했습니다</span>
            <span className={styles.loginNoCertDesc}>
              운영자 네트워크에서 브라우저 인증서 저장소에 설치된 운영자 인증서로 접속하세요. 인증서 없이는 관리 평면에
              접근할 수 없습니다.
            </span>
          </div>
        ) : (
          <>
            <span className={styles.loginPickLabel}>제시할 인증서 선택</span>
            <ul className={styles.certList}>
              {certs.map((c) => (
                <li key={c.certId}>
                  <button
                    type="button"
                    className={styles.certOption}
                    onClick={() => void present(c.certId)}
                    disabled={busy !== null}
                    aria-busy={busy === c.certId}
                  >
                    <span className={styles.certCn}>{c.subjectCn}</span>
                    <span className={styles.certIssuer}>발급 {c.issuer}</span>
                    <span className={styles.certAction}>{busy === c.certId ? "확립 중" : "세션 확립"}</span>
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
    </main>
  );
}
