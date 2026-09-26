"use client";

/**
 * 글 작성·수정 폼. 제목(TextField 재사용) + 본문(textarea). 길이 가드·문자 수 표시.
 * 제출·에러 매핑·롤백은 상위 책임. 여기선 입력·검증 안내·인라인 에러 표시만.
 */

import { useId, useState } from "react";
import TextField from "@/components/TextField";
import { POST_BODY_MAX, POST_TITLE_MAX } from "@/lib/community/types";
import { formatCount } from "@/lib/format";
import { useExchangeStore } from "@/lib/store";
import { isSuspended, SAVE_BLOCKED_LABEL, SAVE_BLOCKED_SR } from "@/lib/tradingStatus";
import styles from "./PostComposer.module.css";

interface Props {
  initialTitle?: string;
  initialBody?: string;
  submitLabel: string;
  busyLabel: string;
  onSubmit(title: string, body: string): Promise<void>;
  onCancel?(): void;
  titleError?: string | null;
  bodyError?: string | null;
}

export default function PostComposer({
  initialTitle = "",
  initialBody = "",
  submitLabel,
  busyLabel,
  onSubmit,
  onCancel,
  titleError,
  bodyError,
}: Props) {
  const bodyId = useId();
  const [title, setTitle] = useState(initialTitle);
  const [body, setBody] = useState(initialBody);
  const [busy, setBusy] = useState(false);

  // 거래 일시 중단 동안 게시판 쓰기도 받지 않는다(API 설계 §2-5-e). 입력은 그대로 둔다
  const suspended = useExchangeStore((s) => isSuspended(s.tradingStatus));
  const canSubmit = title.trim().length > 0 && body.trim().length > 0 && !busy && !suspended;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    try {
      await onSubmit(title.trim(), body.trim());
    } finally {
      setBusy(false);
    }
  };

  const bodyDescId = bodyError ? `${bodyId}-error` : `${bodyId}-count`;

  return (
    <form className={styles.form} onSubmit={submit} noValidate>
      <TextField
        label="제목"
        value={title}
        onChange={setTitle}
        error={titleError}
        placeholder="제목 입력"
        maxLength={POST_TITLE_MAX}
        disabled={busy}
        autoFocus
      />

      <div className={styles.field}>
        <label className={styles.label} htmlFor={bodyId}>
          본문
        </label>
        <textarea
          id={bodyId}
          className={styles.textarea}
          value={body}
          rows={8}
          maxLength={POST_BODY_MAX}
          disabled={busy}
          placeholder="종목에 대한 생각을 자유롭게 입력"
          aria-invalid={bodyError ? true : undefined}
          aria-describedby={bodyDescId}
          data-invalid={bodyError ? true : undefined}
          onChange={(e) => setBody(e.target.value)}
        />
        {bodyError ? (
          <p id={`${bodyId}-error`} className={styles.error} role="alert">
            {bodyError}
          </p>
        ) : (
          <p id={`${bodyId}-count`} className={`${styles.count} num`}>
            {formatCount(body.length)} / {formatCount(POST_BODY_MAX)}
          </p>
        )}
      </div>

      {suspended && (
        <p role="status">
          <span aria-hidden="true">{SAVE_BLOCKED_LABEL}</span>
          <span className="sr-only">{SAVE_BLOCKED_SR}</span>
        </p>
      )}
      <div className={styles.actions}>
        {onCancel && (
          <button type="button" className="btn btn--secondary" onClick={onCancel} disabled={busy}>
            취소
          </button>
        )}
        <button type="submit" className="btn btn--primary" disabled={!canSubmit} aria-busy={busy}>
          {busy ? busyLabel : submitLabel}
        </button>
      </div>
      <span className="sr-only">제목 최대 {POST_TITLE_MAX}자</span>
    </form>
  );
}
