"use client";

/**
 * 운영자 바 검색. 관제 판에서 서버, 경보, 사건, 조치 기록을 찾아 그 화면으로 보낸다. sim이면 시뮬레이터
 * 판이고 http면 칸이 연결 전이라 찾을 것이 없다고 적는다. Ctrl+K(맥은 Cmd+K)로 입력칸에 간다.
 * 결과 목록은 콤보박스 규칙을 따른다. 화살표로 고르고 Enter로 가고 Escape로 닫는다.
 */

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useOpsPick } from "@/lib/admin/opsCenter";
import type { ControlCenter, IncidentEventKind } from "@/lib/admin/types";
import { AdminIcon } from "../icons";
import { HOST_KIND } from "./Infra";
import { hm, hms } from "./parts";
import styles from "./control.module.css";

type Group = "서버" | "경보" | "사건" | "조치 기록";
const GROUPS: Group[] = ["서버", "경보", "사건", "조치 기록"];
const PER_GROUP = 4;

interface Hit {
  id: string;
  group: Group;
  title: string;
  meta: string;
  href: string;
}

/** 조치 기록으로 찾는 사건 기록 종류. 발생·해제 같은 시스템 기록은 사건으로 찾는다 */
const ACTION_KINDS = new Set<IncidentEventKind>(["APPROVED", "REJECTED", "ACTED", "AUTO_ACTED", "AUTO_REVERTED", "REVERTED", "MODE", "SILENCED", "ACKED"]);

function indexOf(c: ControlCenter): Hit[] {
  const out: Hit[] = [];
  if (c.hosts.state === "READY") {
    for (const h of c.hosts.data) {
      out.push({ id: `h-${h.id}`, group: "서버", title: h.id, meta: `${HOST_KIND[h.role]}${h.partition ? ` · ${h.partition.id}` : ""}`, href: "/admin/hosts" });
    }
  }
  if (c.readTier.state === "READY") {
    for (const n of c.readTier.data.nodes) out.push({ id: `r-${n.id}`, group: "서버", title: n.id, meta: "Spring Boot · 조회 전용", href: "/admin/hosts" });
  }
  if (c.aiServer.state === "READY") {
    out.push({ id: "s-ai", group: "서버", title: c.aiServer.data.id, meta: `${c.aiServer.data.hardware} · 온프레미스 AI`, href: "/admin/hosts" });
  }
  if (c.alerts.state === "READY") {
    for (const a of c.alerts.data) out.push({ id: `a-${a.id}`, group: "경보", title: a.name, meta: `${a.target} · ${a.detail}`, href: "/admin/incidents" });
  }
  if (c.incidents.state === "READY") {
    for (const i of c.incidents.data.incidents) {
      out.push({ id: `i-${i.id}`, group: "사건", title: i.title, meta: `${hm(i.openedAt)} 발생 · ${i.resolvedAt === null ? "진행 중" : "해제"}`, href: "/admin/incidents" });
    }
    for (const e of c.incidents.data.events) {
      if (!ACTION_KINDS.has(e.kind)) continue;
      out.push({ id: `e-${e.id}`, group: "조치 기록", title: e.text, meta: `${hms(e.at)} · ${e.actorName}`, href: e.actor === "AI" ? "/admin/ai" : "/admin/incidents" });
    }
  }
  return out;
}

export default function OpsSearch() {
  const router = useRouter();
  const listId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const [q, setQ] = useState("");
  // 찾는 글자가 있을 때만 판을 따라간다. 비어 있으면 판이 매초 새로 와도 검색칸을 다시 그리지 않는다
  const searching = q.trim() !== "";
  const data = useOpsPick((c) => (searching ? c : null));
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);

  // Ctrl+K 는 브라우저의 검색 단축키이지만 운영 관제 셸 안에서는 이 입력칸으로 보낸다
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && (e.key === "k" || e.key === "K")) {
        e.preventDefault();
        inputRef.current?.focus();
        inputRef.current?.select();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  const index = useMemo(() => (data ? indexOf(data) : []), [data]);
  const term = q.trim().toLowerCase();
  const hits = useMemo(() => {
    if (term === "") return [];
    const matched = index.filter((h) => `${h.title} ${h.meta}`.toLowerCase().includes(term));
    return GROUPS.flatMap((g) => matched.filter((h) => h.group === g).slice(0, PER_GROUP));
  }, [index, term]);
  const cur = Math.min(active, Math.max(0, hits.length - 1));

  const go = (h: Hit) => {
    setOpen(false);
    setQ("");
    router.push(h.href);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      if (hits.length === 0) return;
      e.preventDefault();
      setOpen(true);
      setActive((cur + (e.key === "ArrowDown" ? 1 : hits.length - 1)) % hits.length);
    } else if (e.key === "Enter") {
      if (hits.length > 0) {
        e.preventDefault();
        go(hits[cur]);
      }
    } else if (e.key === "Escape") {
      if (open && term !== "") {
        e.preventDefault();
        setOpen(false);
      } else {
        setQ("");
        inputRef.current?.blur();
      }
    }
  };

  const connected = data !== null && index.length > 0;
  const showPanel = open && term !== "";
  let empty: string | null = null;
  if (data === null) empty = "관제 판을 불러오는 중입니다";
  else if (!connected) empty = "검색할 관제 기록의 원천이 아직 연결 전입니다";
  else if (hits.length === 0) empty = `'${q.trim()}'와 맞는 서버·경보·사건·조치 기록이 없습니다`;

  return (
    <div className={styles.search} ref={wrapRef}>
      <span className={styles.searchIcon} aria-hidden="true">
        <AdminIcon name="search" size={16} />
      </span>
      <input
        ref={inputRef}
        type="search"
        className={styles.searchInput}
        role="combobox"
        aria-label="관제 검색"
        aria-autocomplete="list"
        aria-expanded={showPanel && hits.length > 0}
        aria-controls={listId}
        aria-activedescendant={showPanel && hits.length > 0 ? `${listId}-${cur}` : undefined}
        placeholder="서버, 경보, 사건, 조치 기록 검색"
        value={q}
        onChange={(e) => {
          setQ(e.target.value);
          setActive(0);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
      />
      <kbd className={styles.kbd} aria-hidden="true">
        Ctrl K
      </kbd>
      {showPanel && (
        <div className={styles.searchPanel}>
          {empty !== null ? (
            <p className={styles.searchEmpty} role="status">
              {empty}
            </p>
          ) : (
            <ul id={listId} role="listbox" aria-label="검색 결과" className={styles.searchList}>
              {GROUPS.filter((g) => hits.some((h) => h.group === g)).map((g) => (
                <li key={g} role="presentation">
                  <span className={styles.searchGroup} aria-hidden="true">
                    {g}
                  </span>
                  <ul role="group" aria-label={g} className={styles.searchList}>
                    {hits.map((h, i) =>
                      h.group !== g ? null : (
                        <li
                          key={h.id}
                          id={`${listId}-${i}`}
                          role="option"
                          aria-selected={i === cur}
                          className={styles.searchHit}
                          // 누름으로 입력칸의 포커스를 잃기 전에 이동한다
                          onMouseDown={(e) => e.preventDefault()}
                          onMouseEnter={() => setActive(i)}
                          onClick={() => go(h)}
                        >
                          <span className={styles.searchHitTitle}>{h.title}</span>
                          <span className={styles.searchHitMeta}>{h.meta}</span>
                        </li>
                      ),
                    )}
                  </ul>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
