"use client";

/** 라이트/다크 토글. [data-theme] 명시 토글(시스템 자동값 위에 우선), localStorage 유지 */

import { useEffect, useState } from "react";

export default function ThemeToggle({ size }: { size?: "sm" }) {
  const [theme, setTheme] = useState<"light" | "dark" | null>(null);

  useEffect(() => {
    const t = document.documentElement.dataset.theme;
    if (t === "dark" || t === "light") setTheme(t);
    else setTheme(window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  }, []);

  const toggle = () => {
    const next = theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    // 운영자가 고른 순간 관리 기본값 표시를 거둔다. 관리 평면을 떠나도 이 선택은 남는다
    delete document.documentElement.dataset.themeOrigin;
    try {
      localStorage.setItem("theme", next);
    } catch {
      /* no-op */
    }
    setTheme(next);
  };

  // 작은 변형은 관리 콘솔 운영자 바가 쓴다. 18px로 그려 화면에서 선이 1.5px가 되게 관리 아이콘 한 벌과 굵기를 맞춘다
  const px = size === "sm" ? 18 : 22;
  const sw = size === "sm" ? 2 : 1.8;
  return (
    <button
      type="button"
      className="icon-btn"
      data-size={size}
      onClick={toggle}
      aria-label={theme === "dark" ? "라이트 모드로 전환" : "다크 모드로 전환"}
    >
      {theme === "dark" ? (
        /* 해 아이콘 */
        <svg width={px} height={px} viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="12" cy="12" r="4.2" stroke="currentColor" strokeWidth={sw} />
          <path
            d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M18.4 5.6L17 7M7 17l-1.4 1.4"
            stroke="currentColor"
            strokeWidth={sw}
            strokeLinecap="round"
          />
        </svg>
      ) : (
        /* 달 아이콘 */
        <svg width={px} height={px} viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M20 14.5A8.5 8.5 0 0 1 9.5 4 8.5 8.5 0 1 0 20 14.5Z"
            stroke="currentColor"
            strokeWidth={sw}
            strokeLinejoin="round"
          />
        </svg>
      )}
    </button>
  );
}
