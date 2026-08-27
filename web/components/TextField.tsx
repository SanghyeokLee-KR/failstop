"use client";

/**
 * TextField. 라벨 + 입력 + 인라인 에러 범용 필드(§4-3).
 * 인벤토리에 범용 텍스트 입력이 없어 신설. 로그인·가입·마이페이지 폼이 공유한다.
 * 비밀번호는 표시·숨김 토글을 제공하되 값은 상위가 소유하고 여기서 저장·로깅하지 않는다.
 */

import { useId, useState } from "react";
import styles from "./TextField.module.css";

interface TextFieldProps {
  label: string;
  value: string;
  onChange(value: string): void;
  /** 포커스 이탈 시 호출. 폼 검증을 타이핑 중이 아니라 입력을 마친 시점에 걸기 위한 훅이다 */
  onBlur?(): void;
  type?: "text" | "email" | "password";
  placeholder?: string;
  autoComplete?: string;
  /** 인라인 에러 문구. 있으면 보더·안내를 danger로 전환하고 aria-invalid 설정 */
  error?: string | null;
  /** 라벨 아래 상시 안내(형식·정책 등) */
  hint?: string;
  disabled?: boolean;
  autoFocus?: boolean;
  inputMode?: "text" | "email";
  /** 입력 단계 길이 상한. 서버 상한 초과 입력이 제출 후에야 실패하지 않게 선차단한다 */
  maxLength?: number;
}

export default function TextField({
  label,
  value,
  onChange,
  onBlur,
  type = "text",
  placeholder,
  autoComplete,
  error,
  hint,
  disabled,
  autoFocus,
  inputMode,
  maxLength,
}: TextFieldProps) {
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const [reveal, setReveal] = useState(false);
  const isPassword = type === "password";
  const inputType = isPassword && reveal ? "text" : type;

  const describedBy = [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(" ") || undefined;

  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={id}>
        {label}
      </label>
      <div className={`focus-ring-owner ${styles.control}`} data-invalid={error ? true : undefined}>
        <input
          id={id}
          className={styles.input}
          type={inputType}
          value={value}
          placeholder={placeholder}
          autoComplete={autoComplete}
          inputMode={inputMode}
          maxLength={maxLength}
          disabled={disabled}
          autoFocus={autoFocus}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          onChange={(e) => onChange(e.target.value)}
          onBlur={onBlur}
        />
        {isPassword && (
          <button
            type="button"
            className={styles.toggle}
            onClick={() => setReveal((r) => !r)}
            aria-pressed={reveal}
            aria-label={reveal ? "비밀번호 숨기기" : "비밀번호 표시"}
            tabIndex={disabled ? -1 : 0}
          >
            {reveal ? "숨김" : "표시"}
          </button>
        )}
      </div>
      {hint && !error && (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className={styles.error} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
