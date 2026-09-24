"use client";

/**
 * 호스트에서 칠 명령. 버튼처럼 보이지 않게 둔다. 누르면 복사만 되고 실행되지 않는다.
 * 클립보드는 안전 맥락이 아니면 없을 수 있어 실패해도 화면이 깨지지 않게 감싼다.
 */

import { useState } from "react";
import styles from "./admin.module.css";

export function CommandChip({ command }: { command: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(command);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      // 복사가 막힌 환경에서는 문자열이 화면에 그대로 있으므로 손으로 옮긴다
    }
  };
  return (
    <button type="button" className={styles.commandChip} onClick={() => void copy()} title="클릭하면 복사">
      <code>{command}</code>
      <span className={styles.commandCopy} aria-hidden="true">
        {copied ? "복사됨" : "복사"}
      </span>
      <span className="sr-only">호스트 명령 복사. 콘솔은 실행하지 않음</span>
    </button>
  );
}
