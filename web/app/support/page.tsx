"use client";

/**
 * 문의하기(§4-G).
 *
 * 답을 기다리는 화면이다. 그래서 "언제 답이 오는가"를 거짓 없이 말해야 한다. 데모라
 * 영업일 기준 같은 약속을 할 수 없고, 그렇다고 아무 말도 안 하면 사용자는 무한정 기다린다.
 * 운영자가 확인하면 이 화면에 답변이 붙는다는 사실만 말한다. 그것이 실제로 일어나는 일이다.
 *
 * 스레드를 만들지 않는다. 답변이 불충분하면 새 문의를 등록한다(§4-G). 그래서 목록의 각
 * 항목은 질문과 답 한 쌍으로 닫혀 있고, 답변이 오면 그 자리에서 이어 읽힌다.
 */

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ConnectionBanner } from "@/components/StatusSignals";
import Skeleton from "@/components/Skeleton";
import TextField from "@/components/TextField";
import SegmentedTab from "@/components/SegmentedTab";
import { toast } from "@/components/Toast";
import { usePageTitle } from "@/components/PageTitle";
import { getDataSource } from "@/lib/api/datasource";
import { ApiError } from "@/lib/api/httpDatasource";
import { useMemberSession } from "@/lib/store";
import { loginHref } from "@/lib/auth/nextPath";
import type { SupportCategory, SupportInquiry } from "@/lib/types";
import styles from "./page.module.css";

const CATEGORIES = [
  { value: "TRADE" as SupportCategory, label: "거래" },
  { value: "TRANSFER" as SupportCategory, label: "입출금" },
  { value: "ACCOUNT" as SupportCategory, label: "계정" },
  { value: "ETC" as SupportCategory, label: "기타" },
];

const CATEGORY_LABEL: Record<SupportCategory, string> = {
  TRADE: "거래",
  TRANSFER: "입출금",
  ACCOUNT: "계정",
  ETC: "기타",
};

const MAX_TITLE = 120;
const MAX_BODY = 2000;
const MIN_BODY = 5;

export default function SupportPage() {
  usePageTitle("문의하기");
  const session = useMemberSession();

  const [category, setCategory] = useState<SupportCategory>("TRADE");
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [rows, setRows] = useState<SupportInquiry[] | null>(null);
  const [listFailed, setListFailed] = useState(false);

  const load = useCallback(async () => {
    if (!session) return;
    setListFailed(false);
    try {
      setRows(await getDataSource().listInquiries(50));
    } catch {
      setRows(null);
      setListFailed(true);
    }
  }, [session]);

  useEffect(() => {
    void load();
  }, [load]);

  /* 서버와 같은 규칙이다. 여기가 느슨하면 제출을 눌러 왕복한 뒤에야 길이 위반을 안다 */
  const titleError = useMemo(() => {
    const v = title.replace(/\s+/g, " ").trim();
    if (!v) return "제목 입력 필요";
    if ([...v].length > MAX_TITLE) return `제목은 최대 ${MAX_TITLE}자`;
    return null;
  }, [title]);

  const bodyError = useMemo(() => {
    const v = body.trim();
    if ([...v].length < MIN_BODY) return `내용은 최소 ${MIN_BODY}자`;
    if ([...v].length > MAX_BODY) return `내용은 최대 ${MAX_BODY}자`;
    return null;
  }, [body]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setTouched(true);
    if (titleError || bodyError) return;
    setBusy(true);
    try {
      await getDataSource().createInquiry({ category, title: title.trim(), body: body.trim() });
      setTitle("");
      setBody("");
      setTouched(false);
      toast("문의 접수. 답변이 등록되면 이 화면에 표시", "success");
      void load();
    } catch (err) {
      toast(supportErrorCopy(err), "error");
    } finally {
      setBusy(false);
    }
  };

  if (!session) {
    return (
      <main className={styles.page}>
        <h1 className="sr-only">문의하기</h1>
        <ConnectionBanner />
        <section className={`card ${styles.gate}`} aria-labelledby="support-gate-title">
          <span className="empty-state__mark" aria-hidden="true" />
          <h2 id="support-gate-title" className={styles.gateTitle}>
            로그인 후 이용
          </h2>
          {/* 왜 계정이 필요한지 말한다. "로그인하세요"만 있으면 문턱으로만 읽힌다 */}
          <p className={styles.gateDesc}>
            답변은 이 화면에서만 읽을 수 있음. 메일 발송이 없는 데모라 계정 없이 받으면 답을 전할 방법이 없음
          </p>
          <div className={styles.gateActions}>
            <Link href={loginHref("/support")} className="btn btn--primary">
              로그인
            </Link>
            <Link href="/auth/signup" className="btn btn--secondary">
              회원 가입
            </Link>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <h1 className="sr-only">문의하기</h1>
      <ConnectionBanner />

      <div className={styles.grid}>
        <section className={`card ${styles.formCard}`} aria-labelledby="support-form-title">
          <div className={styles.head}>
            <span className="gauge-plate">고객센터</span>
            <h2 id="support-form-title" className={styles.title}>
              문의하기
            </h2>
          </div>

          <form className={styles.form} onSubmit={submit} noValidate>
            <div className={styles.field}>
              <span className={styles.fieldLabel}>분류</span>
              <SegmentedTab
                items={CATEGORIES}
                value={category}
                onChange={setCategory}
                ariaLabel="문의 분류"
                disabled={busy}
              />
            </div>

            <TextField
              label="제목"
              value={title}
              onChange={setTitle}
              onBlur={() => setTouched(true)}
              maxLength={MAX_TITLE}
              placeholder="한 줄로 요약"
              error={touched ? titleError ?? undefined : undefined}
              disabled={busy}
            />

            <label className={styles.field}>
              <span className={styles.fieldLabel}>내용</span>
              <textarea
                className={`focus-ring-owner ${styles.textarea}`}
                value={body}
                onChange={(e) => setBody(e.target.value)}
                onBlur={() => setTouched(true)}
                maxLength={MAX_BODY}
                rows={8}
                placeholder="언제 무엇을 했고 무엇이 예상과 달랐는지 적어주면 확인이 빠름"
                aria-invalid={touched && bodyError ? true : undefined}
                disabled={busy}
              />
              <span className={styles.counter}>
                {touched && bodyError ? (
                  <span className={styles.fieldError} role="alert">
                    {bodyError}
                  </span>
                ) : (
                  <span className={styles.hint}>실제 비밀번호·카드·계좌번호 입력 금지</span>
                )}
                <span className={`${styles.count} num`}>
                  {[...body.trim()].length} / {MAX_BODY}
                </span>
              </span>
            </label>

            {/* 기다림의 성격을 제출 전에 말한다. 데모라 시간 약속은 못 하지만
                어디서 답을 읽는지는 확정 사실이다 */}
            <p className={styles.note} role="note">
              운영자가 확인 후 답변. 등록되면 오른쪽 목록에 표시. 별도 알림 발송 없음
            </p>

            <button
              type="submit"
              className="btn btn--primary btn--full btn--xl"
              disabled={busy}
              aria-busy={busy}
            >
              {busy ? "접수 중" : "문의 등록"}
            </button>
          </form>
        </section>

        <section className={`card ${styles.listCard}`} aria-labelledby="support-list-title">
          <div className={styles.head}>
            <h2 id="support-list-title" className={styles.title}>
              내 문의
            </h2>
          </div>

          {rows === null && !listFailed ? (
            <Skeleton variant="card" width="100%" height={240} />
          ) : listFailed ? (
            <div className="empty-state empty-state--compact" role="alert">
              <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
              <span className="empty-state__title">문의를 불러오지 못함</span>
              <button type="button" className="btn btn--secondary" onClick={() => void load()}>
                다시 시도
              </button>
            </div>
          ) : rows && rows.length === 0 ? (
            <div className="empty-state empty-state--compact">
              <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
              <span className="empty-state__title">문의 내역 없음</span>
              <span className="empty-state__desc">왼쪽 폼으로 문의 등록</span>
            </div>
          ) : (
            <ol className={styles.list}>
              {(rows ?? []).map((row) => (
                <li key={row.external_id} className={styles.item} data-status={row.status}>
                  <div className={styles.itemMeta}>
                    <span className={styles.categoryBadge}>{CATEGORY_LABEL[row.category]}</span>
                    <span className={styles.statusText} data-status={row.status}>
                      {row.status === "ANSWERED" ? "답변 완료" : "답변 대기"}
                    </span>
                    <time className={`${styles.itemTime} num`} dateTime={new Date(row.created_at).toISOString()}>
                      {formatStamp(row.created_at)}
                    </time>
                  </div>
                  <h3 className={styles.itemTitle}>{row.title}</h3>
                  <p className={styles.itemBody}>{row.body}</p>
                  {row.answer && (
                    /* 답변은 질문에 이어 붙는다. 별도 카드로 띄우면 어느 질문의 답인지
                       다시 찾아야 한다. 왼쪽 들여쓰기와 면으로 발화자가 갈린다 */
                    <div className={styles.answer}>
                      <span className={styles.answerLabel}>운영자 답변</span>
                      <p className={styles.answerBody}>{row.answer}</p>
                    </div>
                  )}
                </li>
              ))}
            </ol>
          )}
        </section>
      </div>
    </main>
  );
}

/** 서버 문장을 그대로 띄우지 않는다. 코드를 화면 어휘로 옮긴다 */
function supportErrorCopy(err: unknown): string {
  const code = err instanceof ApiError ? err.code : err instanceof Error ? err.name : "";
  switch (code) {
    case "invalid_support_input":
      return "입력 확인 필요";
    case "too_many_requests":
      return "문의가 많음. 잠시 후 다시 시도 필요";
    case "out_of_scope":
    case "session_expired":
      return "세션 만료. 다시 로그인 필요";
    default:
      return "등록 실패. 잠시 후 다시 시도 필요";
  }
}

function formatStamp(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
