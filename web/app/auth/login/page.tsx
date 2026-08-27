"use client";

/**
 * 로그인 화면(§4-3). 이메일·비밀번호 + 둘러보기(데모 세션) 링크.
 * 성공 시 세션은 DataSource가 확립하고 store는 구독으로 갱신된다. 이동은 `?next=` 복귀 경로, 없으면 /exchange.
 * 비밀번호는 상위 상태로만 다루고 저장·로깅하지 않는다.
 */

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import TextField from "@/components/TextField";
import { toast } from "@/components/Toast";
import { getDataSource } from "@/lib/api/datasource";
import { useMemberSession } from "@/lib/store";
import { mapAuthError } from "../authErrors";
import { nextFromLocation } from "@/lib/auth/nextPath";
import styles from "../auth.module.css";
import { usePageTitle } from "@/components/PageTitle";

export default function LoginPage() {
  usePageTitle("로그인");
  const router = useRouter();
  const session = useMemberSession();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setFieldErrors({});
    setFormError(null);
    try {
      await getDataSource().login({ email: email.trim(), password });
      toast("로그인 완료", "success");
      router.push(nextFromLocation());
    } catch (err) {
      const mapped = mapAuthError(err);
      if (mapped.field === "email" || mapped.field === "password") {
        setFieldErrors({ [mapped.field]: mapped.message });
      } else {
        setFormError(mapped.message);
      }
      setBusy(false);
    }
  };

  // 로그인 상태에서 인증 화면 재진입 시 무자각 계정 전환을 막는다. 폼 대신 현재 신원을 안내한다
  if (session) {
    return (
      <main className={styles.page}>
        <section className={styles.card} aria-labelledby="login-title">
          <div className={styles.head}>
            <span className={`gauge-plate ${styles.eyebrow}`}>회원</span>
            <h1 id="login-title" className={styles.title}>
              이미 로그인한 상태
            </h1>
            <p className={styles.sub}>{session.user.display_name} 계정으로 접속 중</p>
          </div>
          <Link href="/exchange" className={`btn btn--primary btn--full btn--xl ${styles.submit}`}>
            거래소로 가기
          </Link>
          <Link href="/mypage" className={`btn btn--secondary btn--full ${styles.browse}`}>
            마이페이지
          </Link>
        </section>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <section className={styles.card} aria-labelledby="login-title">
        <div className={styles.head}>
          <span className={`gauge-plate ${styles.eyebrow}`}>회원</span>
          <h1 id="login-title" className={styles.title}>
            로그인
          </h1>
          <p className={styles.sub}>이메일과 비밀번호로 회원 계정에 접속</p>
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
            autoComplete="current-password"
            value={password}
            onChange={setPassword}
            error={fieldErrors.password}
            disabled={busy}
          />

          <button
            type="submit"
            className={`btn btn--primary btn--full btn--xl ${styles.submit}`}
            disabled={busy}
            aria-busy={busy}
          >
            {busy ? "로그인 중" : "로그인"}
          </button>
        </form>

        <div className={styles.foot}>
          <span>아직 회원이 아니신가요?</span>
          <Link href="/auth/signup" className={styles.footLink}>
            회원 가입
          </Link>
        </div>

        <div className={styles.divider}>또는</div>

        <Link href="/exchange" className={`btn btn--secondary btn--full ${styles.browse}`}>
          로그인 없이 둘러보기
        </Link>
      </section>
    </main>
  );
}
