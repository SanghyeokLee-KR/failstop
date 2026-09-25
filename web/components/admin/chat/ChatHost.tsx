"use client";

/**
 * AI 상담의 자리(관리자 설계 4-6). 사이드바 머리의 여는 단추와 Ctrl+J가 같은 상태를 연다. 패널은 처음
 * 열 때 불러오는 조각이고(dynamic import) 단추에 포인터를 올리거나 포커스가 오면 미리 받아 둔다.
 */

import dynamic from "next/dynamic";
import { useEffect } from "react";
import { useChat } from "@/lib/admin/chatStore";
import { AdminIcon } from "../icons";
import styles from "../admin.module.css";

const loadPanel = () => import("./ChatPanel");
const ChatPanel = dynamic(loadPanel, { ssr: false });

/** 사이드바 머리의 여는 단추. 이름은 그대로 두고 열림은 aria-expanded로 알린다 */
export function ChatTrigger() {
  const open = useChat((s) => s.open);
  return (
    <button
      type="button"
      className={styles.brandAction}
      aria-label="AI 상담"
      aria-expanded={open}
      aria-controls={open ? "ai-chat" : undefined}
      aria-keyshortcuts="Control+J"
      title="AI 상담 · Ctrl J"
      data-active={open ? "" : undefined}
      onPointerEnter={() => void loadPanel()}
      onFocus={() => void loadPanel()}
      onClick={() => useChat.getState().toggle()}
    >
      <AdminIcon name="chat" size={16} />
    </button>
  );
}

/** Ctrl+J로 여닫고, 열려 있으면 패널을 그린다. 운영 관제 셸만 둔다 */
export default function ChatHost() {
  const open = useChat((s) => s.open);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // Ctrl+J는 브라우저의 내려받기 목록이지만 운영 관제 셸 안에서는 상담을 여닫는다
      if ((e.ctrlKey || e.metaKey) && !e.altKey && (e.key === "j" || e.key === "J")) {
        e.preventDefault();
        useChat.getState().toggle();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);
  return open ? <ChatPanel /> : null;
}
