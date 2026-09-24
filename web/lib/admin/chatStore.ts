"use client";

/**
 * AI 상담 패널의 상태. 사이드바의 여는 단추, Ctrl+J, 패널이 같은 상태를 본다. 대화는 이 탭의 메모리에만
 * 두고 새로 고치면 지워진다. 운영 기록이 아니라 묻고 답한 흔적이라 저장소에 남기지 않는다. 패널 폭만
 * 이 브라우저에 기억한다.
 */

import { create } from "zustand";
import { isoNow } from "../format";
import { AdminError, getAdminApi } from "./api";
import { CHAT_RECENT_TURNS, type AiChatReply, type AiChatTurn } from "./types";

export const CHAT_WIDTH = { min: 360, max: 720, initial: 440 } as const;
const WIDTH_KEY = "admin.chat.width";
/** 답이 곧바로 와도 생성 중 표시를 이만큼은 보인다. 표시가 깜빡임처럼 스쳐 지나가지 않게 한다 */
const MIN_THINK_MS = 500;
/** 패널에 두는 말 수(질문과 답 40쌍). 긴 대화가 쌓여도 패널을 다시 그리는 비용이 늘지 않게 오래된 것부터 버린다 */
const KEEP_MESSAGES = 80;
const keep = (list: ChatMessage[]) => (list.length > KEEP_MESSAGES ? list.slice(-KEEP_MESSAGES) : list);

export interface ChatMessage {
  id: string;
  role: AiChatTurn["role"];
  text: string;
  at: string;
  /** AI 답이면 서버(sim) 응답이다 */
  reply: AiChatReply | null;
  /** 답을 받지 못했으면 그 사유다. 질문을 다시 보낼 수 있다 */
  error: string | null;
  /** 실패한 답이 어떤 질문의 것인지. 다시 묻기가 쓴다 */
  question: string | null;
}

interface ChatState {
  open: boolean;
  width: number;
  messages: ChatMessage[];
  pending: boolean;
  setOpen(open: boolean): void;
  toggle(): void;
  /** 폭을 바꾼다. save면 이 브라우저에 기억한다. 손잡이를 끌어 옮기는 동안에는 기억하지 않고 놓을 때 한 번 기억한다 */
  setWidth(width: number, save?: boolean): void;
  ask(question: string): Promise<void>;
  clear(): void;
}

let seq = 0;
const nextId = () => `chat-${(seq += 1)}`;

function savedWidth(): number {
  try {
    const v = Number(window.localStorage.getItem(WIDTH_KEY));
    if (Number.isFinite(v) && v >= CHAT_WIDTH.min && v <= CHAT_WIDTH.max) return v;
  } catch {
    // 저장소가 막힌 환경은 기본 폭으로 연다
  }
  return CHAT_WIDTH.initial;
}

export const useChat = create<ChatState>((set, get) => ({
  open: false,
  width: CHAT_WIDTH.initial,
  messages: [],
  pending: false,
  setOpen(open) {
    set(open ? { open, width: get().open ? get().width : savedWidth() } : { open });
  },
  toggle() {
    get().setOpen(!get().open);
  },
  setWidth(width, save = true) {
    const w = Math.round(Math.min(CHAT_WIDTH.max, Math.max(CHAT_WIDTH.min, width)));
    set({ width: w });
    if (!save) return;
    try {
      window.localStorage.setItem(WIDTH_KEY, String(w));
    } catch {
      // 기억만 못 할 뿐 지금 폭은 그대로 쓴다
    }
  },
  async ask(question) {
    const text = question.trim();
    if (text === "" || get().pending) return;
    // 서버 계약은 질문과 최근 대화를 받는다. 실패한 답은 대화가 아니라 보내지 않는다
    const recent: AiChatTurn[] = get()
      .messages.filter((m) => m.error === null)
      .slice(-CHAT_RECENT_TURNS)
      .map((m) => ({ role: m.role, text: m.text }));
    set({
      pending: true,
      messages: keep([...get().messages, { id: nextId(), role: "operator", text, at: isoNow(), reply: null, error: null, question: null }]),
    });
    try {
      const [reply] = await Promise.all([getAdminApi().askAi(text, recent), new Promise((r) => setTimeout(r, MIN_THINK_MS))]);
      set({ messages: keep([...get().messages, { id: nextId(), role: "ai", text: reply.answer, at: isoNow(), reply, error: null, question: text }]) });
    } catch (e) {
      const why = e instanceof AdminError ? e.message : "답을 받지 못했습니다";
      set({ messages: keep([...get().messages, { id: nextId(), role: "ai", text: "", at: isoNow(), reply: null, error: why, question: text }]) });
    } finally {
      set({ pending: false });
    }
  },
  clear() {
    if (!get().pending) set({ messages: [] });
  },
}));
