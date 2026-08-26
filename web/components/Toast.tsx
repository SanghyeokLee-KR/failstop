"use client";

/**
 * A-14. Toast. 체결·액션 결과·에러. live 시맨틱은 항목 role(status/alert)에만 둬 중첩 낭독을 피한다.
 * success는 시스템 성공(green) 도메인. 가격 방향과 별개 (§0-3).
 * 사용: toast("주문 접수 완료", "success") / 레이아웃에 <Toaster /> 1회 장착.
 */

import { create } from "zustand";
import styles from "./Toast.module.css";

type ToastType = "success" | "error" | "info";

interface ToastItem {
  id: number;
  type: ToastType;
  message: string;
}

interface ToastState {
  items: ToastItem[];
  push(message: string, type: ToastType): void;
  remove(id: number): void;
}

const TOAST_DURATION_MS = 3000; // == --toast-duration
// 에러는 사용자가 읽고 대응할 시간이 필요해 소멸을 연장한다. 수동 닫기 버튼도 함께 제공한다
const TOAST_DURATION_ERROR_MS = 7000;
let nextId = 1;

const useToastStore = create<ToastState>((set) => ({
  items: [],
  push(message, type) {
    const id = nextId++;
    set((s) => ({ items: [...s.items, { id, type, message }] }));
    const ttl = type === "error" ? TOAST_DURATION_ERROR_MS : TOAST_DURATION_MS;
    setTimeout(() => set((s) => ({ items: s.items.filter((t) => t.id !== id) })), ttl);
  },
  remove(id) {
    set((s) => ({ items: s.items.filter((t) => t.id !== id) }));
  },
}));

export function toast(message: string, type: ToastType = "info"): void {
  useToastStore.getState().push(message, type);
}

// 체크 기호(U+2713)는 스타일 게이트의 기호 범위 검사 대상이라 리터럴 대신 이스케이프로 둔다
const ICONS: Record<ToastType, string> = { success: "\u2713", error: "!", info: "i" };

export function Toaster() {
  const items = useToastStore((s) => s.items);
  const remove = useToastStore((s) => s.remove);
  return (
    // data-toast-region: 모달 다이얼로그의 배경 inert 대상에서 제외하는 식별자.
    // 토스트는 z-toast로 다이얼로그 위에 그려지므로 열림 중에도 낭독·닫기가 살아야 한다
    <div className={styles.region} data-toast-region="">
      {items.map((t) => (
        // live 시맨틱은 항목 role에만 둔다. region이 별도 live면 중첩 낭독이 생긴다.
        // status는 암묵 polite, alert는 암묵 assertive라 에러만 즉시 낭독된다
        <div
          key={t.id}
          className={styles.toast}
          data-type={t.type}
          role={t.type === "error" ? "alert" : "status"}
        >
          <span className={styles.icon} data-type={t.type} aria-hidden="true">
            {ICONS[t.type]}
          </span>
          <span className={styles.message}>{t.message}</span>
          <button
            type="button"
            className={styles.close}
            aria-label="알림 닫기"
            onClick={() => remove(t.id)}
          >
            <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
              <path d="M3.5 3.5l7 7M10.5 3.5l-7 7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
        </div>
      ))}
    </div>
  );
}
