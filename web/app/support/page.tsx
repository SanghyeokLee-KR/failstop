"use client";

/**
 * 데모라 응답 시한을 약속할 수 없어 확정 사실(이 화면에 답변이 붙는다)만 말한다(§4-G).
 * 스레드가 없어 각 항목은 질문과 답 한 쌍으로 닫힌다.
 */

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ConnectionBanner } from "@/components/StatusSignals";
import Pager from "@/components/Pager";
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

/* 한 쪽 5건. 입출금(8건)보다 적은 이유는 항목이 문답 한 쌍이라 세로가 크고 편차도 크기
   때문이다. 훑는 목록이 아니라 읽는 목록이라 한 화면에 담기는 수도 그만큼 적다 */
const PAGE_SIZE = 5;

/* 본문 접기 기준. 이보다 길면 목록에서 3줄로 접고 펼치기를 붙인다. 실측 대신 글자수로
   판정한다. 줄 수를 재려면 항목마다 ref와 ResizeObserver가 붙는데, 그 정확도를 위해
   지불하기에는 비싸다. 경계에서 한두 건이 불필요하게 펼치기를 다는 정도의 오차다 */
const CLAMP_CHARS = 160;
const CLAMP_LINES = 3;

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
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  /* 커서는 앞으로만 발급된다(§2-6). 뒤로 가려면 지나온 쪽의 시작 커서를 들고 있어야 한다.
     i번째 칸이 i쪽을 여는 커서고 첫 쪽은 null이다. 화면에 그리지 않으므로 ref로 둔다 */
  const cursorsRef = useRef<(string | null)[]>([null]);
  /* 펼친 문의. 쪽을 넘기면 비운다. 다른 쪽의 항목이 펼친 채로 남아 있을 이유가 없고,
     external_id를 계속 들고 있으면 오래 머문 세션에서 집합만 커진다 */
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const load = useCallback(async (target = 0) => {
    if (!session) return;
    setListFailed(false);
    try {
      const res = await getDataSource().listInquiries({
        cursor: cursorsRef.current[target] ?? null,
        limit: PAGE_SIZE,
      });
      // 다음 쪽 커서는 이번 쪽을 읽어야 알 수 있다. 읽은 자리에 채워 다음 이동에 쓴다
      cursorsRef.current[target + 1] = res.next_cursor;
      setRows(res.data);
      setHasMore(res.has_more);
      setPage(target);
      setExpanded(new Set());
    } catch {
      setRows(null);
      setListFailed(true);
    }
  }, [session]);

  /* 새 문의가 목록 맨 위에 끼면 지나온 쪽 경계가 한 칸씩 밀린다. 들고 있던 커서는 그
     밀림을 모르므로 버리고 첫 쪽부터 다시 센다. 답변은 행이 제자리에서 상태만 바뀌므로
     커서가 그대로 유효하지만, 이 화면에서 답변을 다는 주체는 운영자라 갱신 계기가 없다 */
  const reload = useCallback(() => {
    cursorsRef.current = [null];
    return load(0);
  }, [load]);

  useEffect(() => {
    void reload();
  }, [reload]);

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

  const toggle = (id: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) next.add(id);
      return next;
    });

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
      void reload();
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
          {/* 비어 있는 화면이 아니라 자격이 없어 막힌 화면이다. 같은 빈 상태 블록을 쓰면
              두 상황이 한 종류로 읽힌다 */}
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

            {/* 기다림의 성격을 제출 전에 말한다. 데모라 시간 약속은 못 하지만 알림이
                없다는 것과 어디서 읽는지는 확정 사실이다. 목록의 화면상 위치는 빼고
                "이 화면"으로 둔다. 옆에 보이는 것을 글자로 가리키지 않는다 */}
            <p className={styles.note} role="note">
              운영자가 확인 후 답변. 별도 알림 없이 이 화면에 표시
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
            <Skeleton variant="card" width="100%" height={240} className={styles.skeletonFill} />
          ) : listFailed ? (
            <div className="empty-state empty-state--compact" role="alert">
              <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
              <span className="empty-state__title">문의를 불러오지 못함</span>
              <button type="button" className="btn btn--secondary" onClick={() => void load(page)}>
                다시 시도
              </button>
            </div>
          ) : rows && rows.length === 0 ? (
            <div className="empty-state empty-state--compact">
              <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
              {/* 설명을 붙이지 않는다. 문의 폼이 같은 화면 왼쪽에 그대로 보이므로
                  "왼쪽 폼으로 등록"은 화면에 있는 것을 글자로 다시 가리키는 말이다.
                  답변이 오는 방식도 폼 안내문이 이미 말한다. 사실만 남긴다 */}
              <span className="empty-state__title">문의 내역 없음</span>
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
                  {/* 질문은 접고 답변은 접지 않는다. 본문은 사용자가 쓴 글이라 목록에서는
                      어느 문의인지 알아보는 용도고, 답변은 이 화면에 다시 온 이유다.
                      둘을 같이 접으면 찾아온 목적이 한 번 더 클릭 뒤로 밀린다 */}
                  <p
                    className={styles.itemBody}
                    data-clamped={clampable(row.body) && !expanded.has(row.external_id)}
                  >
                    {row.body}
                  </p>
                  {clampable(row.body) && (
                    <button
                      type="button"
                      className={styles.itemMore}
                      aria-expanded={expanded.has(row.external_id)}
                      onClick={() => toggle(row.external_id)}
                    >
                      {expanded.has(row.external_id) ? "접기" : "전체 보기"}
                    </button>
                  )}
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

          <Pager
            page={page}
            hasMore={hasMore}
            onMove={(target) => void load(target)}
            ariaLabel="문의 목록 쪽 이동"
          />
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

/**
 * 목록에서 접을 만큼 긴 본문인지.
 *
 * 글자수와 줄바꿈 수를 함께 본다. 줄바꿈만 여럿인 짧은 글도 pre-wrap에서는 세로를
 * 그대로 차지하므로 글자수만으로는 놓친다.
 */
function clampable(body: string): boolean {
  return body.length > CLAMP_CHARS || countLines(body) > CLAMP_LINES;
}

/** 줄바꿈 문자를 세어 줄 수를 낸다. 정규식 리터럴 없이 코드포인트로 비교한다 */
function countLines(body: string): number {
  let n = 1;
  for (const ch of body) {
    if (ch.charCodeAt(0) === 10) n += 1;
  }
  return n;
}

function formatStamp(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
