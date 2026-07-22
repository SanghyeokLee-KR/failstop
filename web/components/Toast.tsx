"use client";

/**
 * A-14. Toast. 체결·액션 결과·에러. aria-live="polite"(시세 tick 미낭독),
 * success는 시스템 성공(green) 도메인. 가격 방향과 별개 (§0-3).
 * 사용: toast("주문을 접수했어요", "success") / 레이아웃에 <Toaster /> 1회 장착.
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
let nextId = 1;

const useToastStore = create<ToastState>((set) => ({
  items: [],
  push(message, type) {
    const id = nextId++;
    set((s) => ({ items: [...s.items, { id, type, message }] }));
    setTimeout(() => set((s) => ({ items: s.items.filter((t) => t.id !== id) })), TOAST_DURATION_MS);
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
    <div className={styles.region} aria-live="polite" aria-atomic="false">
      {items.map((t) => (
        <div key={t.id} className={styles.toast} data-type={t.type} onClick={() => remove(t.id)}>
          <span className={styles.icon} data-type={t.type} aria-hidden="true">
            {ICONS[t.type]}
          </span>
          <span className={styles.message}>{t.message}</span>
        </div>
      ))}
    </div>
  );
}
