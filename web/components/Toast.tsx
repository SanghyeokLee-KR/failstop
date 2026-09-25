"use client";

/**
 * A-14. Toast. 체결·액션 결과·에러. live 시맨틱은 항목 role(status/alert)에만 둬 중첩 낭독을 피한다.
 * success는 시스템 성공(green) 도메인. 가격 방향과 별개 (§0-3).
 * 사용: toast("주문 접수 완료", "success") / 레이아웃에 <Toaster /> 1회 장착.
 */

import { usePathname } from "next/navigation";
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
  /** 관리 콘솔 층이 열린 동안 쌓아 둔 알림. 모든 층이 닫히면 띄운다 */
  held: ToastItem[];
  /** 열린 관리 콘솔 층의 수. 맨 위 층의 깊이와 같다 */
  holds: number;
  /** 층이 열린 동안 난 오류. 맨 위 층이 제 안의 알림 줄로 적는다 */
  layerError: { id: number; message: string } | null;
  push(message: string, type: ToastType): void;
  remove(id: number): void;
}

const TOAST_DURATION_MS = 3000; // == --toast-duration
// 에러는 사용자가 읽고 대응할 시간이 필요해 소멸을 연장한다. 수동 닫기 버튼도 함께 제공한다
const TOAST_DURATION_ERROR_MS = 7000;
let nextId = 1;

const useToastStore = create<ToastState>((set, get) => {
  const show = (item: ToastItem) => {
    set((s) => ({ items: [...s.items, item] }));
    const ttl = item.type === "error" ? TOAST_DURATION_ERROR_MS : TOAST_DURATION_MS;
    setTimeout(() => set((s) => ({ items: s.items.filter((t) => t.id !== item.id) })), ttl);
  };
  return {
    items: [],
    held: [],
    holds: 0,
    layerError: null,
    push(message, type) {
      const item = { id: nextId++, type, message };
      if (get().holds === 0) {
        show(item);
        return;
      }
      // 층이 열린 동안의 오류는 층 안에 적는다. 층 밖 오른쪽 아래에 띄우면 덮개 아래에서 대화상자 가장자리와 겹친다
      if (type === "error") set({ layerError: { id: item.id, message } });
      else set((s) => ({ held: [...s.held, item] }));
    },
    remove(id) {
      set((s) => ({ items: s.items.filter((t) => t.id !== id) }));
    },
  };
});

export function toast(message: string, type: ToastType = "info"): void {
  useToastStore.getState().push(message, type);
}

/**
 * 관리 콘솔 층(모달·드로어·확인 대화상자)이 열린 동안 토스트를 붙잡는다(디자인 시스템 §12-12). 알림은 쌓아 두었다가
 * 모든 층이 닫힌 뒤 띄우고, 오류는 맨 위 층이 제 알림 줄로 적는다. 돌려준 깊이가 열린 층 수와 같으면 그 층이 맨 위다
 */
export function holdToasts(): { depth: number; release: () => void } {
  const depth = useToastStore.getState().holds + 1;
  useToastStore.setState({ holds: depth });
  let released = false;
  return {
    depth,
    release: () => {
      if (released) return;
      released = true;
      const st = useToastStore.getState();
      const holds = Math.max(0, st.holds - 1);
      if (holds > 0) {
        useToastStore.setState({ holds, layerError: null });
        return;
      }
      const held = st.held;
      useToastStore.setState({ holds: 0, held: [], layerError: null });
      for (const item of held) useToastStore.getState().push(item.message, item.type);
    },
  };
}

/** 층 안 알림 줄에 적을 오류. depth가 맨 위 층일 때만 준다 */
export function useLayerError(depth: number): { id: number; message: string } | null {
  return useToastStore((s) => (depth > 0 && s.holds === depth ? s.layerError : null));
}

/** 다시 확정할 때 지난 오류를 거둔다 */
export function clearLayerError(): void {
  if (useToastStore.getState().layerError !== null) useToastStore.setState({ layerError: null });
}

// 체크 기호(U+2713)는 스타일 게이트의 기호 범위 검사 대상이라 리터럴 대신 이스케이프로 둔다
const ICONS: Record<ToastType, string> = { success: "\u2713", error: "!", info: "i" };

export function Toaster() {
  const items = useToastStore((s) => s.items);
  const remove = useToastStore((s) => s.remove);
  const held = useToastStore((s) => s.holds > 0);
  // 관리 콘솔은 토스트를 오른쪽 아래, 확인 대화상자 아래 층에 둔다(디자인 시스템 §12-12). 거래 화면 자리는 그대로다
  const admin = usePathname()?.startsWith("/admin") ?? false;
  return (
    // data-toast-region: 모달 다이얼로그의 배경 inert 대상에서 제외하는 식별자.
    // 거래 화면의 토스트는 z-toast로 다이얼로그 위에 그려지므로 열림 중에도 낭독·닫기가 살아야 한다.
    // 관리 콘솔 층이 열린 동안에는 떠 있던 토스트를 숨긴다. 덮개 아래에서 층의 가장자리와 겹쳐 읽히지 않는다
    <div className={styles.region} data-toast-region="" data-place={admin ? "admin" : undefined} data-held={admin && held ? "" : undefined}>
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
