"use client";

/**
 * 호스트에서 칠 명령이나 저장소 문서 경로. 버튼처럼 보이지 않게 둔다. 누르면 복사만 되고 실행되지 않는다.
 * 클립보드는 안전 맥락이 아니면 없을 수 있어 실패해도 화면이 깨지지 않게 감싼다.
 */

import { useState } from "react";
import styles from "./admin.module.css";

export function CommandChip({ command, kind = "command", label }: {
  command: string;
  /** 명령인가 문서 경로인가. 보조기술이 읽는 설명이 다르다 */
  kind?: "command" | "path";
  /** 칩에 보이는 짧은 글. 없으면 원문을 그대로 보인다(원문은 늘 툴팁이 싣는다) */
  label?: string;
}) {
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
    <button type="button" className={styles.commandChip} onClick={() => void copy()} title={`${command} · 누르면 복사`}>
      <code>{label ?? command}</code>
      <span className={styles.commandCopy} aria-hidden="true">
        {copied ? "복사됨" : "복사"}
      </span>
      <span className="sr-only">{kind === "path" ? `문서 경로 ${command} 복사` : "호스트 명령 복사. 콘솔은 실행하지 않음"}</span>
    </button>
  );
}
