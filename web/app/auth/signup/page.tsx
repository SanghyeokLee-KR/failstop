"use client";

/**
 * 가입 화면(§4-3). 이메일·비밀번호·표시명 + 데모 안전 배너.
 * 이메일은 미검증 데모 핸들, 비밀번호는 데모 전용임을 배너로 상시 고지(§1-1, §6).
 * 실제 금융정보(카드·계좌·주민번호) 필드는 두지 않는다(보안 규칙).
 */

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import TextField from "@/components/TextField";
import { toast } from "@/components/Toast";
import { getDataSource } from "@/lib/api/datasource";
import { useMemberSession } from "@/lib/store";
import { mapAuthError } from "../authErrors";
import styles from "../auth.module.css";
import { usePageTitle } from "@/components/PageTitle";

export default function SignupPage() {
  usePageTitle("회원 가입");
  const router = useRouter();
  const session = useMemberSession();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [fieldErrors, setFieldErrors] = useState<{
    email?: string;
    password?: string;
    display_name?: string;
  }>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setFieldErrors({});
    setFormError(null);
    if (displayName.trim().length === 0) {
      setFieldErrors({ display_name: "표시명 입력 필요" });
      setBusy(false);
      return;
    }
    try {
      await getDataSource().signup({
        email: email.trim(),
        password,
        display_name: displayName.trim(),
      });
      toast("가입 완료. 데모 자산 1억 지급", "success");
      router.push("/exchange");
    } catch (err) {
      const mapped = mapAuthError(err);
      if (mapped.field) {
        setFieldErrors({ [mapped.field]: mapped.message });
      } else {
        setFormError(mapped.message);
      }
      setBusy(false);
    }
  };

  // 로그인 상태에서 가입 화면 재진입 시 무자각 계정 전환을 막는다. 폼 대신 현재 신원을 안내한다
  if (session) {
    return (
      <main className={styles.page}>
        <section className={styles.card} aria-labelledby="signup-title">
          <div className={styles.head}>
            <span className={`gauge-plate ${styles.eyebrow}`}>회원</span>
            <h1 id="signup-title" className={styles.title}>
              이미 로그인한 상태
            </h1>
            <p className={styles.sub}>{session.user.display_name} 계정으로 접속 중</p>
          </div>
          <Link href="/exchange" className={`btn btn--primary btn--full btn--xl ${styles.submit}`}>
            거래소로 가기
          </Link>
          <Link href="/mypage" className="btn btn--secondary btn--full">
            마이페이지
          </Link>
        </section>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <section className={styles.card} aria-labelledby="signup-title">
        <div className={styles.head}>
          <span className={`gauge-plate ${styles.eyebrow}`}>회원</span>
          <h1 id="signup-title" className={styles.title}>
            회원 가입
          </h1>
          <p className={styles.sub}>이메일로 회원 계정을 만들고 개인 자산·주문 이력을 관리</p>
        </div>

        <div className={styles.banner} role="note">
          <span className={styles.bannerIcon} aria-hidden="true">
            !
          </span>
          <span className={styles.bannerText}>
            데모 거래소. 실제로 쓰는 비밀번호나 카드·계좌·주민번호 같은 금융정보 입력 금지. 이메일은 검증하지 않는 데모
            핸들.
          </span>
        </div>

        <form className={styles.form} onSubmit={submit} noValidate>
          {formError && (
            <div className={styles.formError} role="alert">
              <span className={styles.formErrorIcon} aria-hidden="true">
                !
              </span>
              <span>{formError}</span>
            </div>
          )}

          <TextField
            label="이메일"
            type="email"
            inputMode="email"
            autoComplete="email"
            value={email}
            onChange={setEmail}
            error={fieldErrors.email}
            placeholder="trader@demo.local"
            disabled={busy}
            autoFocus
          />
          <TextField
            label="비밀번호"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={setPassword}
            error={fieldErrors.password}
            hint="8자 이상. 데모 전용, 실제 비밀번호 사용 금지"
            disabled={busy}
          />
          <TextField
            label="표시명"
            type="text"
            autoComplete="nickname"
            value={displayName}
            onChange={setDisplayName}
            error={fieldErrors.display_name}
            placeholder="데모트레이더"
            maxLength={32}
            disabled={busy}
          />

          <button
            type="submit"
            className={`btn btn--primary btn--full btn--xl ${styles.submit}`}
            disabled={busy}
            aria-busy={busy}
          >
            {busy ? "가입 중" : "가입하기"}
          </button>
        </form>

        <div className={styles.foot}>
          <span>이미 회원이신가요?</span>
          <Link href="/auth/login" className={styles.footLink}>
            로그인
          </Link>
        </div>
      </section>
    </main>
  );
}
