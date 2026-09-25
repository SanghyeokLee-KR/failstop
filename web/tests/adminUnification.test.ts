/**
 * 관리 콘솔 부품 한 벌의 되돌림 방지(디자인 시스템 §12-2·§12-10·§12-14). 용량·봇·백오피스가 관제 화면과 다른 세대의
 * 부품(전역 44px 분절 탭, 둥근 상태 알약)으로 돌아가지 않았는지, 글자 크기를 토큰 밖 픽셀로 적지 않았는지, 칩·배지를
 * 알약으로 깎지 않았는지, 왼쪽 2px 크롬 선이 '고른 것·지금 위치' 한 뜻인지를 원본에서 센다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";

const WEB = path.resolve(__dirname, "..", "..");

function files(dir: string, ext: RegExp): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = path.join(dir, name);
    if (statSync(p).isDirectory()) out.push(...files(p, ext));
    else if (ext.test(name)) out.push(p);
  }
  return out;
}

const ADMIN_DIRS = [path.join(WEB, "components", "admin"), path.join(WEB, "app", "admin")];
const TSX = ADMIN_DIRS.flatMap((d) => files(d, /\.tsx?$/));
const CSS = ADMIN_DIRS.flatMap((d) => files(d, /\.module\.css$/));
const rel = (p: string) => path.relative(WEB, p).replace(/\\/g, "/");

/** 주석을 뺀 CSS 규칙(선택자, 본문) 목록 */
function rules(css: string): { selector: string; body: string }[] {
  const plain = css.replace(/\/\*[\s\S]*?\*\//g, "");
  const out: { selector: string; body: string }[] = [];
  for (const m of plain.matchAll(/([^{}]+)\{([^{}]*)\}/g)) out.push({ selector: m[1].trim(), body: m[2] });
  return out;
}

test("관리 화면은 전역 SegmentedTab과 둥근 status-pill을 쓰지 않는다", () => {
  assert.ok(TSX.length > 50, "관리 원본을 읽지 못하면 공허 통과다");
  const hits = TSX.filter((p) => {
    const s = readFileSync(p, "utf8");
    return /@\/components\/SegmentedTab|["']status-pill["']|className="status-pill"|HealthPill/.test(s);
  }).map(rel);
  assert.deepEqual(hits, []);
});

test("관리 CSS는 글자 크기와 굵기를 토큰으로만 적는다(여섯 단계·굵기 셋, §12-2)", () => {
  assert.ok(CSS.length > 5);
  const hits: string[] = [];
  for (const p of CSS) {
    for (const r of rules(readFileSync(p, "utf8"))) {
      if (/font-size:\s*\d|font-weight:\s*\d/.test(r.body)) hits.push(`${rel(p)} ${r.selector}`);
    }
  }
  assert.deepEqual(hits, []);
});

test("칩·배지·태그·알림은 네모이고 미터 트랙·채움은 곧은 막대다. 완전히 둥근 모양을 쓰지 않는다(§12-10)", () => {
  const hits: string[] = [];
  for (const p of CSS) {
    for (const r of rules(readFileSync(p, "utf8"))) {
      if (!/radius-full/.test(r.body)) continue;
      // 접힌 사이드바의 모서리 표식은 점(8px 모양 둘레)이라 둥글어도 된다
      const sel = r.selector.replace(/\.navBadgeSlot\s*>\s*\*/g, "");
      // 클래스 이름을 낱말로 본다. stageDot의 'tag'처럼 낱말 가운데 걸린 글자는 세지 않는다
      const names = [...sel.matchAll(/\.([a-zA-Z_][\w-]*)/g)].map((m) => m[1]);
      if (names.some((n) => /^(chip|badge|tag|pill|toast|notice|track|fill)|(Chip|Badge|Tag|Pill|Toast|Notice|Track|Rail|Fill|Mini)/.test(n))) hits.push(`${rel(p)} ${r.selector}`);
    }
  }
  assert.deepEqual(hits, []);
});

test("왼쪽 2px 크롬 선은 '고른 것·지금 위치'에만 선다(§12-14)", () => {
  const hits: string[] = [];
  for (const p of CSS) {
    for (const r of rules(readFileSync(p, "utf8"))) {
      const inset = /inset 2px 0 0/.test(r.body) && /--chrome-accent/.test(r.body);
      const rail = /width:\s*2px/.test(r.body) && /background:\s*var\(--chrome-accent\)/.test(r.body) && /::before/.test(r.selector);
      if (!inset && !rail) continue;
      if (/\[data-active\]|\[aria-current|\[aria-pressed="true"\]|\[aria-selected="true"\]/.test(r.selector)) continue;
      hits.push(`${rel(p)} ${r.selector}`);
    }
  }
  assert.deepEqual(hits, []);
});

test("CSS 모듈이 정의하지 않은 클래스를 관리 원본이 부르지 않는다", () => {
  const missing: string[] = [];
  for (const p of TSX) {
    const src = readFileSync(p, "utf8");
    for (const m of src.matchAll(/import\s+(\w+)\s+from\s+"([^"]+\.module\.css)"/g)) {
      const [, alias, spec] = m;
      const file = spec.startsWith("@/") ? path.join(WEB, spec.slice(2)) : path.resolve(path.dirname(p), spec);
      const defined = new Set([...readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/\.([a-zA-Z_][\w-]*)/g)].map((x) => x[1]));
      for (const use of src.matchAll(new RegExp(`\\b${alias}\\.([a-zA-Z_]\\w*)`, "g"))) {
        if (!defined.has(use[1])) missing.push(`${rel(p)} ${alias}.${use[1]}`);
      }
    }
  }
  assert.deepEqual([...new Set(missing)], []);
});

test("앱 계층은 app-1·app-2 고정과 app-3 자동 증설이다. 관리 화면과 sim에 read-N 조회 노드가 남지 않는다(ADR 0030 사용자 정정 2026-09-25)", () => {
  const LIB = files(path.join(WEB, "lib", "admin"), /\.tsx?$/);
  assert.ok(LIB.length > 10, "sim 원본을 읽지 못하면 공허 통과다");
  const hits: string[] = [];
  for (const p of [...TSX, ...CSS, ...LIB]) {
    const lines = readFileSync(p, "utf8").split("\n");
    lines.forEach((line, i) => {
      if (/read-[123]\b|\bread[123]\b|조회 노드|조회 ASG|노드 \+1/.test(line)) hits.push(`${rel(p)}:${i + 1}`);
    });
  }
  assert.deepEqual(hits, []);
});
