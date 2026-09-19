"use client";

/**
 * 운영자 로그인 화면. 학교 배포는 HTTPS 비밀번호 게이트웨이를 쓰고,
 * 기본 mTLS 모드는 브라우저 인증서로 세션을 발급한다. 데모 모드는 인증서 목록을 모사한다.
 */

import { useState } from "react";
import { getAdminApi, isAdminHttp } from "@/lib/admin/api";
import BrandMark from "@/components/BrandMark";
import { toast } from "@/components/Toast";
import styles from "./admin.module.css";

// 데이터 원본은 API 어댑터와 같은 판정을 사용한다. 비밀번호 모드는 HTTP 어댑터에서만 켠다.
const IS_HTTP = isAdminHttp();
const PASSWORD_LOGIN = IS_HTTP && process.env.NEXT_PUBLIC_ADMIN_AUTH === "password";

export default function AdminLogin() {
  const certs = getAdminApi().listDemoCerts();
  const [busy, setBusy] = useState<string | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  const login = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBusy("password");
    try {
      await getAdminApi().establishSession(username, password);
    } catch (e) {
      toast(e instanceof Error ? e.message : "로그인 실패", "error");
    } finally {
      setPassword("");
      setBusy(null);
    }
  };

  const present = async (certId: string) => {
    setBusy(certId);
    try {
      await getAdminApi().establishSession(certId);
    } catch (e) {
      toast(e instanceof Error ? e.message : "세션 확립 실패", "error");
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
          <span className={styles.loginPlateText}>페일스톱 · 운영 관리</span>
        </div>
        <h1 className={styles.loginTitle}>{PASSWORD_LOGIN ? "운영자 로그인" : "관리 평면 접속"}</h1>
        <p className={styles.loginDesc}>{PASSWORD_LOGIN ? "운영자 계정으로 로그인해 시장 봇을 관리합니다." : "운영자 인증서 신원으로 접속. 세션은 단기 발급"}</p>

        {PASSWORD_LOGIN ? (
          <form className={styles.passwordLogin} onSubmit={(event) => void login(event)}>
            <label className={styles.field}>
              아이디
              <input autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} required disabled={busy !== null} />
            </label>
            <label className={styles.field}>
              비밀번호
              <input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required disabled={busy !== null} />
            </label>
            <button type="submit" className={styles.certOption} disabled={busy !== null} aria-busy={busy !== null}>
              <span className={styles.certCn}>{busy ? "로그인 중" : "로그인"}</span>
            </button>
            <p className={styles.loginHint}>Grafana 계정과 별도로 등록한 봇 운영자 계정을 사용합니다.</p>
          </form>
        ) : certs.length === 0 && IS_HTTP ? (
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
            <span className={styles.loginNoCertTitle}>클라이언트 인증서 없음</span>
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
