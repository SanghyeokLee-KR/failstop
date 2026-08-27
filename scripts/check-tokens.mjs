/**
 * check-tokens.mjs. 디자인 토큰 드리프트 게이트.
 *
 * 사용:
 *   node scripts/check-tokens.mjs           검사만
 *   node scripts/check-tokens.mjs --matrix  대비 행렬도 출력
 *
 * 배경: 토큰 계약은 tokens.css에 실측 근거까지 명문화돼 있는데 소비 쪽이 반복해서 어긋났다.
 * 같은 파일 31행에 "sunken 위 secondary 금지"를 적고 97줄 아래에서 어긴 사례가 대표적이다.
 * 규칙을 사람이 기억하는 대신 검사로 고정한다.
 *
 * 한계: 정적 검사는 "이 요소가 어느 면 위에 놓이는가"를 모른다. 요소 단위 실측 대비는
 * scripts/sweep-contrast.js를 브라우저에서 돌려야 한다. 이 파일은 토큰 값 자체와
 * 소비 규칙만 본다.
 */

import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL(".", import.meta.url)), "..");
const WEB = join(ROOT, "web");
const TOKENS = join(WEB, "styles", "tokens.css");

/* ===== 스캔 대상 ===== */

const SCAN_DIRS = ["app", "components", "styles", "lib"].map((d) => join(WEB, d));
const SKIP_DIRS = new Set(["node_modules", ".next", "out", ".test-dist"]);

/* 코인 브랜드색은 데이터다(CLAUDE.md 하드코딩 hex 예외). 라벨 상수는 대비 계산 입력이고
   렌더는 var(--text-on-brand-*)가 담당한다(brandChip.ts 주석) */
const COLOR_LITERAL_EXEMPT = ["lib/sim/engine.ts", "lib/brandChip.ts", "styles/tokens.css"];

/* 시드 게시글·댓글은 사용자 작성물이라 UI 카피 규칙 대상이 아니다 */
const COPY_TONE_EXEMPT = ["lib/community/datasource.ts"];

/* 척도(scale)는 미사용 단계가 있어도 결함이 아니다. 완결성이 곧 계약이라 중간 단계를
   빼면 소비처가 임의값을 쓴다. 팔레트 램프, 타이포 램프, 간격·라운드·그림자·모션·z 척도가 해당한다.
   시맨틱 토큰과 §6 별칭만 소비를 강제한다 */
const SCALE = [
  /^--(grey|brand|red|azure|green|orange|crimson)-(\d+|alpha-\d+)$/,
  /^--font-[a-z0-9-]+-(size|line|weight)$/,
  /^--fw-[a-z]+$/,
  /^--space-\d+$/,
  /^--radius-[a-z0-9]+$/,
  /^--shadow-\d+$/,
  /^--dur-[a-z]+$/,
  /^--ease-[a-z]+$/,
  /^--z-[a-z]+$/,
  /^--effect-pop-scale-[a-z]+$/,
];
const isScale = (tok) => SCALE.some((re) => re.test(tok));

/**
 * JS가 값을 미러링하는 토큰. CSS 소비처는 없지만 계약은 살아 있다.
 * 미러 위치를 적어 두 값이 갈릴 때 어디를 봐야 하는지 남긴다.
 */
const JS_MIRRORED = {
  "--flash-throttle": "components/TickCell.tsx THROTTLE_FOR.normal",
  "--toast-duration": "components/Toast.tsx 자동 소거 타이머",
  "--toast-duration-action": "components/Toast.tsx 액션 토스트 타이머",
};

/* ===== 대비 계약 =====
   tokens.css 주석이 근거로 든 조합을 실행 가능한 단정으로 옮긴다.
   값이 흔들리면 여기서 먼저 깨진다. [텍스트토큰, 면토큰, 최소비] */
const TEXT_SURFACES = ["--surface-default", "--surface-elevated", "--bg-muted"];
const SUNKEN_SURFACES = ["--surface-sunken", "--surface-hover"];
const CONTRACT = [
  ...["--text-strong", "--text-default"].flatMap((t) =>
    [...TEXT_SURFACES, ...SUNKEN_SURFACES].map((s) => [t, s, 4.5]),
  ),
  /* secondary는 sunken·hover 위에서 4.27:1이라 계약에서 뺀다. 그 면에는 default를 쓴다 */
  ...TEXT_SURFACES.map((s) => ["--text-secondary", s, 4.5]),
  ["--color-up-text", "--surface-default", 4.5],
  ["--color-down-text", "--surface-default", 4.5],
  /* 틴트·sunken 위 소형 방향 텍스트는 -strong(700) */
  ...[...SUNKEN_SURFACES, "--color-up-bg", "--color-down-bg"].flatMap((s) => [
    ["--color-up-strong", s, 4.5],
    ["--color-down-strong", s, 4.5],
  ]),
  ["--color-success-text", "--surface-default", 4.5],
  ["--color-success-text", "--color-success-bg", 4.5],
  ["--color-warning-text", "--surface-default", 4.5],
  ["--color-warning-text", "--color-warning-bg", 4.5],
  /* CTA·버튼 라벨 */
  ["--text-inverse", "--color-buy", 4.5],
  ["--text-inverse", "--color-sell", 4.5],
  ["--text-on-brand", "--brand-primary-fill", 4.5],
  /* 비텍스트(1.4.11): 포커스 링·스크롤바 썸 */
  ["--focus-outline-color", "--surface-default", 3],
  ["--scrollbar-thumb", "--surface-default", 3],
  /* secondary 버튼 경계는 다크만 3:1을 요구한다. 라이트는 sunken 면이 카드와 명도로 이미 갈려
     경계가 형태 보조라 1.41:1을 의도적으로 쓴다(tokens.css §2 주석) */
  ["--btn-secondary-border", "--surface-default", 3, "다크"],
  ["--btn-secondary-border", "--surface-elevated", 3, "다크"],
];

/**
 * formatChangeRate 직접 호출 허용 목록.
 * 기본은 formatDirectional(화살표+부호)이다. 예외는 이유를 적어 남긴다.
 * 새 호출부가 생기면 여기에 이유를 쓰거나 formatDirectional로 바꿔야 게이트를 지난다.
 */
const DIRECTION_FORMAT_ALLOW = {
  "lib/format.ts": "formatDirectional이 내부에서 부른다. 원천",
  "app/assets/page.tsx": "짝이 되는 formatDirectionalAmount가 이미 화살표를 싣는다",
  "components/PricePill.tsx": "금액 병기 모드에서 화살표는 금액 포맷터 소관",
  "components/dashboard/MarketSummaryStrip.tsx": "aria-label 전용. 화살표 글자는 낭독 소음",
  "components/trade/OrderbookPanel.tsx": "20행이 같은 부호라 화살표가 반복 소음이 된다. 방향은 매도·매수 구획과 부호가 전달",
  "components/trade/TradesPanel.tsx": "체결 테이프 고밀도 열. 방향은 체결가 색과 부호가 전달",
};

/* ===== 유틸 ===== */

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (SKIP_DIRS.has(name)) continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(css|ts|tsx)$/.test(name)) out.push(p);
  }
  return out;
}

const rel = (p) => relative(WEB, p).split(sep).join("/");

/** 주석 제거. 주석에는 실측 hex와 규칙 인용이 많아 걷어내지 않으면 전부 오탐이 된다 */
function stripComments(src, isCss) {
  let s = src.replace(/\/\*[\s\S]*?\*\//g, " ");
  if (!isCss) s = s.replace(/(^|[^:"'`\\])\/\/[^\n]*/g, "$1");
  return s;
}

/* ===== 토큰 파싱 ===== */

/** tokens.css를 라이트·다크 두 맵으로 푼다. 다크는 라이트에 덮어쓴 결과다 */
function parseTokens() {
  /* 주석을 먼저 걷는다. tokens.css 주석은 규칙 근거로 토큰명을 인용하는데,
     선언 정규식이 주석 안 "--x:var(--y)"를 선언으로 물면 다음 세미콜론까지 삼키면서
     그 구간의 진짜 선언이 통째로 누락된다 */
  const src = stripComments(readFileSync(TOKENS, "utf8"), true);
  const light = new Map();
  const dark = new Map();
  /* 위치 기반 판정은 못 쓴다. tokens.css는 다크 블록 뒤에도 :root{}를 연다(§5 간격, §6 별칭).
     "첫 다크 블록 이후는 전부 다크"로 보면 그 구간이 통째로 오분류돼 검사에서 빠진다.
     중괄호 깊이를 따라가며 블록마다 선택자를 보고 판정한다 */
  /* 1단계: 시스템 다크 미디어 블록의 문자 범위를 중괄호 짝맞춤으로 구한다 */
  const darkMediaRanges = [];
  const mediaRe = /@media[^{]*prefers-color-scheme:\s*dark[^{]*\{/g;
  let mm;
  while ((mm = mediaRe.exec(src)) !== null) {
    let depth = 1;
    let j = mm.index + mm[0].length;
    while (j < src.length && depth > 0) {
      if (src[j] === "{") depth++;
      else if (src[j] === "}") depth--;
      j++;
    }
    darkMediaRanges.push([mm.index, j]);
  }
  const inDarkMedia = (idx) => darkMediaRanges.some(([a, b]) => idx > a && idx < b);

  /* 2단계: 선언 블록(중첩 없는 SELECTOR{...})을 훑어 선택자로 테마를 가른다.
     커스텀 프로퍼티 블록은 중첩되지 않으므로 본문에 중괄호가 없다 */
  const blockRe = /([^{}]*)\{([^{}]*)\}/g;
  let b;
  while ((b = blockRe.exec(src)) !== null) {
    const selector = b[1];
    const body = b[2];
    if (!body.includes("--")) continue;
    const isDark = /\[data-theme="dark"\]/.test(selector) || inDarkMedia(b.index);
    const target = isDark ? dark : light;
    const decl = /(--[a-z0-9-]+)\s*:\s*([^;]+);/gi;
    let d;
    while ((d = decl.exec(body)) !== null) {
      if (!target.has(d[1])) target.set(d[1], d[2].trim());
    }
  }
  const darkFull = new Map(light);
  for (const [k, v] of dark) darkFull.set(k, v);
  return { light, dark: darkFull, declared: new Set([...light.keys(), ...dark.keys()]), src };
}

/** var() 사슬을 풀어 색 리터럴까지 내려간다 */
function resolve(name, map, depth = 0) {
  if (depth > 12) return null;
  const v = map.get(name);
  if (!v) return null;
  const varRef = v.match(/^var\(\s*(--[a-z0-9-]+)\s*\)$/i);
  if (varRef) return resolve(varRef[1], map, depth + 1);
  return v;
}

function toRgba(value) {
  if (!value) return null;
  const hex = value.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
  if (hex) {
    const h = hex[1].length === 3 ? hex[1].replace(/./g, (c) => c + c) : hex[1];
    return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), 1];
  }
  const fn = value.match(/^rgba?\(([^)]+)\)$/i);
  if (fn) {
    const p = fn[1].split(/[,/]/).map((x) => parseFloat(x.trim()));
    return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1];
  }
  return null;
}

const lin = (c) => {
  const x = c / 255;
  return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
};
const lum = ([r, g, b]) => 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
const over = (fg, bg) => fg.slice(0, 3).map((c, i) => c * fg[3] + bg[i] * (1 - fg[3]));
function ratio(a, b) {
  const x = lum(a);
  const y = lum(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

/* ===== 검사 ===== */

const problems = [];
const fail = (rule, where, msg) => problems.push({ rule, where, msg });

function checkContract(tokens) {
  for (const [theme, map] of [["라이트", tokens.light], ["다크", tokens.dark]]) {
    for (const [textTok, surfTok, min, onlyTheme] of CONTRACT) {
      if (onlyTheme && onlyTheme !== theme) continue;
      const fgRaw = toRgba(resolve(textTok, map));
      const bgRaw = toRgba(resolve(surfTok, map));
      if (!fgRaw || !bgRaw) {
        fail("contract", `${theme} ${textTok} on ${surfTok}`, "토큰을 색으로 풀지 못함");
        continue;
      }
      /* 면이 반투명이면 기본 면 위에 얹어 실제 표시색을 만든다 */
      const base = toRgba(resolve("--surface-default", map)) ?? [255, 255, 255, 1];
      const bg = bgRaw[3] < 1 ? over(bgRaw, base.slice(0, 3)) : bgRaw.slice(0, 3);
      const fg = fgRaw[3] < 1 ? over(fgRaw, bg) : fgRaw.slice(0, 3);
      const r = ratio(fg, bg);
      if (r < min) {
        fail("contract", `${theme} ${textTok} on ${surfTok}`, `${r.toFixed(2)}:1, 최소 ${min}`);
      }
    }
  }
}

function checkFiles(tokens, files) {
  const referenced = new Set();
  const declaredAnywhere = new Set(tokens.declared);
  const cssSources = [];

  for (const file of files) {
    const r = rel(file);
    const isCss = file.endsWith(".css");
    const raw = readFileSync(file, "utf8");
    const code = stripComments(raw, isCss);
    if (isCss) {
      cssSources.push({ r, code });
      for (const m of code.matchAll(/(--[a-z0-9-]+)\s*:/gi)) declaredAnywhere.add(m[1]);
    }
    for (const m of code.matchAll(/var\(\s*(--[a-z0-9-]+)/gi)) referenced.add(m[1]);
    /* JS가 getComputedStyle로 읽는 토큰도 소비다 */
    for (const m of code.matchAll(/["'`](--[a-z0-9-]+)["'`]/g)) referenced.add(m[1]);

    if (!COLOR_LITERAL_EXEMPT.includes(r)) {
      for (const m of code.matchAll(/#[0-9a-f]{3,8}\b|\brgba?\([^)]*\)|\bhsla?\([^)]*\)/gi)) {
        /* 데이터 URI 안의 색은 토큰 파생이라 tokens.css 예외로 이미 걸러진다 */
        fail("color-literal", `${r}`, `색 리터럴 ${m[0].slice(0, 28)}. 토큰으로 소비할 것`);
      }
    }

    if (!COPY_TONE_EXEMPT.includes(r) && /\.tsx?$/.test(file)) {
      const strings = [...code.matchAll(/"([^"\n]*[가-힣][^"\n]*)"/g)].map((m) => m[1]);
      const jsx = [...code.matchAll(/>([^<>{}\n]*[가-힣][^<>{}\n]*)</g)].map((m) => m[1].trim());
      for (const t of [...strings, ...jsx]) {
        if (/(습니다|하세요|하십시오|십시오|해 주세요)/.test(t)) {
          fail("copy-tone", r, `존댓말 종결 "${t.slice(0, 34)}". UI 카피는 명사형`);
        }
      }
    }

    if (/\.tsx?$/.test(file) && !(r in DIRECTION_FORMAT_ALLOW) && /\bformatChangeRate\s*\(/.test(code)) {
      fail("direction-format", r, "formatChangeRate 직접 호출. formatDirectional을 쓰거나 허용 목록에 이유를 남길 것");
    }
  }

  for (const tok of referenced) {
    if (!declaredAnywhere.has(tok)) fail("undefined-token", "-", `${tok} 선언 없음`);
  }
  for (const tok of tokens.declared) {
    if (isScale(tok) || tok in JS_MIRRORED) continue;
    if (!referenced.has(tok)) fail("dead-token", "styles/tokens.css", `${tok} 소비처 0`);
  }

  /* 포커스 링은 전역 규칙과 토큰이 한 벌이다. 컴포넌트가 outline을 따로 그리면 어휘가 갈린다 */
  for (const { r, code } of cssSources) {
    for (const m of code.matchAll(/:focus(-visible|-within)?[^{]*\{([^}]*)\}/g)) {
      const body = m[2];
      const outline = body.match(/outline\s*:\s*([^;]+)/);
      if (!outline) continue;
      const v = outline[1].trim();
      if (v === "none" || v.includes("--focus-outline")) continue;
      fail("focus-ring", r, `자체 outline "${v.slice(0, 30)}". --focus-outline-* 소비 또는 none`);
    }
  }
}

/* ===== 실행 ===== */

const tokens = parseTokens();
const files = SCAN_DIRS.flatMap((d) => walk(d));
checkContract(tokens);
checkFiles(tokens, files);

if (process.argv.includes("--matrix")) {
  console.log("대비 행렬 (텍스트 토큰 x 면 토큰)\n");
  const surfaces = [...TEXT_SURFACES, ...SUNKEN_SURFACES];
  const texts = ["--text-strong", "--text-default", "--text-secondary", "--text-tertiary",
    "--color-up-text", "--color-down-text", "--color-up-strong", "--color-down-strong"];
  for (const [theme, map] of [["라이트", tokens.light], ["다크", tokens.dark]]) {
    console.log(`[${theme}]`);
    console.log("  " + "".padEnd(22) + surfaces.map((s) => s.replace("--", "").padStart(17)).join(""));
    for (const t of texts) {
      const cells = surfaces.map((s) => {
        const fg = toRgba(resolve(t, map));
        const bg = toRgba(resolve(s, map));
        if (!fg || !bg) return "-".padStart(17);
        const r = ratio(fg.slice(0, 3), bg.slice(0, 3));
        return `${r.toFixed(2)}${r >= 4.5 ? " " : "!"}`.padStart(17);
      });
      console.log("  " + t.replace("--", "").padEnd(22) + cells.join(""));
    }
    console.log("");
  }
}

if (problems.length === 0) {
  console.log(`토큰 게이트 통과. 파일 ${files.length}개, 계약 ${CONTRACT.length * 2}쌍 검사.`);
  process.exit(0);
}

const byRule = new Map();
for (const p of problems) {
  if (!byRule.has(p.rule)) byRule.set(p.rule, []);
  byRule.get(p.rule).push(p);
}
for (const [rule, list] of byRule) {
  console.log(`\n[${rule}] ${list.length}건`);
  for (const p of list.slice(0, 40)) console.log(`  ${p.where}: ${p.msg}`);
  if (list.length > 40) console.log(`  ... 외 ${list.length - 40}건`);
}
console.log(`\n토큰 게이트 실패. 총 ${problems.length}건.`);
process.exit(1);
