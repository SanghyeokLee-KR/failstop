"use client";

/**
 * 입출금 화면(API 설계 §4-E). 원화 전용.
 *
 * 입금은 즉시 반영되고 출금은 운영자 승인을 기다린다. 두 흐름의 결과가 다르므로 요청 후
 * 무엇을 기대해야 하는지를 폼 안에서 미리 말한다. 제출하고 나서야 "대기 중"을 알게 되면
 * 사용자는 실패로 읽는다.
 *
 * 비회원은 폼 대신 로그인 유도를 본다. 데모 세션은 쓰기가 막혀 있어(§3-7) 제출해도
 * 403이고, 막힐 것을 알면서 폼을 보여주는 것은 막다른 길이다.
 */

import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ConnectionBanner } from "@/components/StatusSignals";
import Skeleton from "@/components/Skeleton";
import TextField from "@/components/TextField";
import { toast } from "@/components/Toast";
import { usePageTitle } from "@/components/PageTitle";
import { getDataSource, MAX_TRANSFER_KRW } from "@/lib/api/datasource";
import { ApiError } from "@/lib/api/httpDatasource";
import { useExchangeStore, useLiveChannels, useMemberSession } from "@/lib/store";
import { compareDecimal } from "@/lib/decimal";
import { formatKRW, toNum } from "@/lib/format";
import { loginHref } from "@/lib/auth/nextPath";
import { dest } from "@/lib/types";
import type { TransferDirection, TransferRequest } from "@/lib/types";
import styles from "./page.module.css";

const DIRECTIONS: { value: TransferDirection; label: string }[] = [
  { value: "DEPOSIT", label: "입금" },
  { value: "WITHDRAW", label: "출금" },
];

/** 자주 쓰는 금액. 매번 0을 여섯 번 누르게 하지 않는다 */
const QUICK_AMOUNTS = ["100000", "1000000", "10000000"];

const STATUS_LABEL: Record<string, string> = {
  REQUESTED: "승인 대기",
  PENDING: "처리 중",
  COMPLETED: "완료",
  FAILED: "실패",
  CANCELED: "취소",
};

function newIdemKey(): string {
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export default function TransfersPage() {
  usePageTitle("입출금");
  const session = useMemberSession();
  const assets = useExchangeStore((s) => s.assets);

  const [direction, setDirection] = useState<TransferDirection>("DEPOSIT");
  const [amount, setAmount] = useState("");
  const [rows, setRows] = useState<TransferRequest[] | null>(null);
  const [listFailed, setListFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [touched, setTouched] = useState(false);
  /* 멱등 키는 제출 단위로 고정한다. 재시도마다 새로 만들면 서버 UNIQUE(user_id, idem_key)가
     중복 요청을 접지 못한다. 성공하면 비워 다음 요청이 새 키를 받는다(주문 orderKey 동형) */
  const idemRef = useRef<string | null>(null);

  const loadAccounts = useExchangeStore((s) => s.loadAccounts);
  /* 잔고를 구독하지 않으면 출금 가능액이 0으로 멈춘다. 입금 직후에도 안 바뀌어
     "입금이 안 됐다"로 읽힌다. 초기 1회 조회 + myAsset 델타 구독을 함께 건다 */
  useLiveChannels(useMemo(() => (session ? [dest.myAsset] : []), [session]));
  useEffect(() => {
    if (session) void loadAccounts();
  }, [session, loadAccounts]);

  const krw = assets.find((a) => a.currency === "KRW");
  const availableKrw = krw?.balance ?? "0";

  const load = useCallback(async () => {
    if (!session) return;
    setListFailed(false);
    try {
      setRows(await getDataSource().listTransfers(50));
    } catch {
      setRows(null);
      setListFailed(true);
    }
  }, [session]);

  useEffect(() => {
    void load();
  }, [load]);

  const amountError = useMemo(() => {
    if (!amount) return "금액 입력 필요";
    if (!/^\d+$/.test(amount)) return "원화는 숫자만";
    if (compareDecimal(amount, "0") <= 0) return "금액은 0보다 커야 함";
    if (compareDecimal(amount, MAX_TRANSFER_KRW) > 0) {
      return `1회 한도 ${formatKRW(MAX_TRANSFER_KRW)}원 초과`;
    }
    if (direction === "WITHDRAW" && compareDecimal(amount, availableKrw) > 0) {
      return "출금 가능 금액 초과";
    }
    return null;
  }, [amount, direction, availableKrw]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setTouched(true);
    if (amountError) return;
    setBusy(true);
    idemRef.current ??= newIdemKey();
    try {
      const row = await getDataSource().requestTransfer(
        { direction, asset: "KRW", amount },
        idemRef.current,
      );
      idemRef.current = null;
      setAmount("");
      setTouched(false);
      toast(
        row.status === "COMPLETED" ? "입금 완료" : "출금 요청 접수. 승인 대기",
        row.status === "COMPLETED" ? "success" : "info",
      );
      void load();
      void loadAccounts();
    } catch (err) {
      // 서버가 코드를 주면 그걸 쓰고, 아니면 일반 문구로 떨어뜨린다. 서버 문장을 그대로
      // 띄우지 않는 이유는 화면 어휘와 어투가 갈리기 때문이다(3차 감사 결함)
      toast(transferErrorCopy(err), "error");
    } finally {
      setBusy(false);
    }
  };

  const cancel = async (row: TransferRequest) => {
    try {
      await getDataSource().cancelTransfer(row.external_id);
      toast("출금 요청 취소", "info");
      void load();
      void loadAccounts();
    } catch (err) {
      toast(transferErrorCopy(err), "error");
    }
  };

  if (!session) {
    return (
      <main className={styles.page}>
        <h1 className="sr-only">입출금</h1>
        <ConnectionBanner />
        <section className={`card ${styles.gate}`} aria-labelledby="gate-title">
          <span className="empty-state__mark" aria-hidden="true" />
          <h2 id="gate-title" className={styles.gateTitle}>
            로그인 후 이용
          </h2>
          <p className={styles.gateDesc}>
            회원 계정의 자산을 움직이는 기능. 둘러보기 중에는 시세와 잔고만 열람 가능
          </p>
          <div className={styles.gateActions}>
            <Link href={loginHref("/transfers")} className="btn btn--primary">
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
      <h1 className="sr-only">입출금</h1>
      <ConnectionBanner />

      <div className={styles.grid}>
        <section className={`card ${styles.formCard}`} aria-labelledby="transfer-form-title">
          <div className={styles.head}>
            <span className="gauge-plate">원화</span>
            <h2 id="transfer-form-title" className={styles.title}>
              입출금
            </h2>
          </div>

          <div className={styles.availRow}>
            <span className={styles.availLabel}>출금 가능</span>
            <b className={`${styles.availValue} num`}>
              {formatKRW(availableKrw)} <span className="unit-suffix">원</span>
            </b>
          </div>

          <div className={styles.tabs} role="group" aria-label="입출금 구분">
            {DIRECTIONS.map((d) => (
              <button
                key={d.value}
                type="button"
                className={styles.tab}
                data-on={direction === d.value || undefined}
                aria-pressed={direction === d.value}
                onClick={() => {
                  setDirection(d.value);
                  setTouched(false);
                }}
                disabled={busy}
              >
                {d.label}
              </button>
            ))}
          </div>

          <form className={styles.form} onSubmit={submit} noValidate>
            <TextField
              label="금액"
              value={amount}
              onChange={(v) => setAmount(v.replace(/[^\d]/g, ""))}
              onBlur={() => setTouched(true)}
              inputMode="text"
              placeholder="0"
              maxLength={9}
              hint={`원 단위. 1회 최대 ${formatKRW(MAX_TRANSFER_KRW)}원`}
              error={touched ? amountError ?? undefined : undefined}
              disabled={busy}
            />

            <div className={styles.quick}>
              {QUICK_AMOUNTS.map((q) => (
                <button
                  key={q}
                  type="button"
                  className={styles.quickBtn}
                  onClick={() => setAmount((prev) => String(toNum(prev || "0") + toNum(q)))}
                  disabled={busy}
                >
                  +{formatKRW(q)}
                </button>
              ))}
              <button
                type="button"
                className={styles.quickBtn}
                onClick={() => setAmount("")}
                disabled={busy}
              >
                초기화
              </button>
            </div>

            {/* 요청 후 무엇이 일어나는지 제출 전에 말한다. 출금은 즉시 빠지지 않고
                잠긴 뒤 승인을 기다리므로, 그걸 모르면 잔고가 준 것을 실패로 읽는다 */}
            <p className={styles.note} role="note">
              {direction === "DEPOSIT"
                ? "입금은 즉시 반영. 시뮬레이션이며 실제 은행 이체 없음"
                : "출금 요청 시 금액이 잠기고 운영자 승인 후 빠져나감. 승인 전까지 취소 가능"}
            </p>

            <button
              type="submit"
              className="btn btn--primary btn--full btn--xl"
              disabled={busy}
              aria-busy={busy}
            >
              {busy ? "처리 중" : direction === "DEPOSIT" ? "입금하기" : "출금 요청"}
            </button>
          </form>
        </section>

        <section className={`card ${styles.historyCard}`} aria-labelledby="transfer-history-title">
          <div className={styles.head}>
            <h2 id="transfer-history-title" className={styles.title}>
              입출금 내역
            </h2>
          </div>

          {rows === null && !listFailed ? (
            <Skeleton variant="card" width="100%" height={220} />
          ) : listFailed ? (
            <div className="empty-state empty-state--compact" role="alert">
              <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
              <span className="empty-state__title">내역을 불러오지 못함</span>
              <button type="button" className="btn btn--secondary" onClick={() => void load()}>
                다시 시도
              </button>
            </div>
          ) : rows && rows.length === 0 ? (
            <div className="empty-state empty-state--compact">
              <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
              <span className="empty-state__title">입출금 내역 없음</span>
              <span className="empty-state__desc">첫 입금으로 시작</span>
            </div>
          ) : (
            <ul className={styles.list}>
              {(rows ?? []).map((row) => (
                <li key={row.external_id} className={styles.row}>
                  <span className={styles.rowDir} data-dir={row.direction}>
                    {row.direction === "DEPOSIT" ? "입금" : "출금"}
                  </span>
                  <span className={`${styles.rowAmount} num`}>
                    {formatKRW(row.amount)} <span className="unit-suffix">원</span>
                  </span>
                  <span className={styles.rowStatus} data-status={row.status}>
                    {STATUS_LABEL[row.status] ?? row.status}
                  </span>
                  <time className={`${styles.rowTime} num`} dateTime={new Date(row.updated_at).toISOString()}>
                    {formatStamp(row.updated_at)}
                  </time>
                  {row.status === "REQUESTED" ? (
                    <button type="button" className={styles.rowAction} onClick={() => void cancel(row)}>
                      취소
                    </button>
                  ) : (
                    <span className={styles.rowReason}>{row.reason ?? ""}</span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </main>
  );
}

/** 서버 문장을 그대로 띄우지 않고 코드를 화면 어휘로 옮긴다(인증 화면 mapAuthError 동형) */
function transferErrorCopy(err: unknown): string {
  const code = err instanceof ApiError ? err.code : err instanceof Error ? err.name : "";
  switch (code) {
    case "invalid_transfer_amount":
      return "금액 확인 필요";
    case "invalid_transfer_asset":
      return "원화만 입출금 가능";
    case "insufficient_funds":
      return "출금 가능 금액 초과";
    case "transfer_not_pending":
      return "이미 처리된 요청";
    case "transfer_not_owned":
      return "본인 요청만 취소 가능";
    case "member_session_required":
    case "session_expired":
      return "세션 만료. 다시 로그인 필요";
    case "too_many_requests":
      return "요청 과다. 잠시 후 다시 시도 필요";
    default:
      return "요청 실패. 잠시 후 다시 시도 필요";
  }
}

function formatStamp(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
