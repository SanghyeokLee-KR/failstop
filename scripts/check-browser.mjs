/**
 * check-browser.mjs. 실제 브라우저에서 재는 불변식 게이트.
 *
 * check-tokens.mjs는 토큰 값과 소비 규칙을 정적으로 본다. 하지만 "이 텍스트가 실제로
 * 어느 면 위에 놓이는가", "이 셀이 행 높이 안에 들어가는가"는 계산된 스타일과 배치가
 * 있어야 안다. 정적 게이트를 통과한 회귀가 두 번 나왔고 둘 다 이 종류였다.
 *
 *   마켓 목록 등락률 2줄 감김: 행 40px에 내용 46px. 값은 전부 정상이라 정적으로 안 보인다.
 *   sunken 면 위 secondary 4.27:1: 면과 글자색이 다른 파일에 있어 짝을 정적으로 못 짓는다.
 *
 * 픽셀 비교를 쓰지 않는 이유: 시세가 초당 바뀐다. 시간·엔진을 동결해도 캔버스 렌더와
 * 폰트 힌팅이 흔들려 오탐이 상시로 난다. 재는 대상을 픽셀이 아니라 기하로 바꾼다.
 *
 * 사용:
 *   node scripts/check-browser.mjs                 dev 서버를 직접 띄우고 전 화면 검사
 *   node scripts/check-browser.mjs --url=...       이미 뜬 서버 사용
 *   node scripts/check-browser.mjs --only=exchange 경로에 문자열이 든 화면만
 *   node scripts/check-browser.mjs --headed        브라우저를 띄워서 본다
 */

import { spawn, spawnSync } from "node:child_process";
import { createServer } from "node:net";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const WEB_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "web");

/* playwright는 web의 devDependency다. 이 파일은 루트 scripts/에 있어 기본 해석 경로가
   web/node_modules를 지나지 않는다. 검사 대상이 web이므로 의존도 web에 두고 여기서 끌어온다 */
const { chromium } = createRequire(path.join(WEB_DIR, "package.json"))("playwright");

/* 테마 전환은 CSS transition이라 값이 확정되기까지 시간이 걸린다. reduced-motion으로
   대부분 죽지만 트랜지션을 가드하지 않은 규칙이 남아 있어 여유를 둔다 */
const SETTLE_MS = 900;
/* sim 엔진이 첫 틱을 내고 패널이 데이터 상태로 넘어갈 때까지 */
const DATA_MS = 2600;

const t0 = Date.now();
/* 화면당 수 초가 걸린다. 아무 출력 없이 몇 분을 기다리면 멈춘 건지 도는 건지 알 수 없다 */
const step = (msg) => console.log(`[${String((Date.now() - t0) / 1000).padStart(6)}s] ${msg}`);

const argv = process.argv.slice(2);
const arg = (k) => argv.find((a) => a.startsWith(`--${k}=`))?.split("=").slice(1).join("=");
const has = (k) => argv.includes(`--${k}`);

/* 뷰포트: 1280은 고정형 최소 폭이고 900은 무스크롤 예산의 기준 높이다.
   1440x820은 예산 브레이크포인트(max-height: 820px)가 켜지는 경계다 */
const VIEWPORTS = [
  { name: "1280x900", width: 1280, height: 900 },
  { name: "1440x820", width: 1440, height: 820 },
];

/** 무스크롤 예산이 걸린 화면. 세로 스크롤이 생기면 설계 위반이다 */
const NO_SCROLL = [/^\/exchange\//];

const SCREENS = [
  "/", "/home", "/welcome", "/market", "/exchange", "/exchange/KRW-BTC",
  "/order/KRW-BTC", "/coin/KRW-BTC", "/community/KRW-BTC",
  "/assets", "/transfers", "/history", "/notices", "/support", "/mypage", "/system",
  "/auth/login", "/auth/signup",
  "/admin", "/admin/audit", "/admin/bots", "/admin/chaos",
  "/admin/config", "/admin/monitoring", "/admin/operators", "/admin/transfers", "/admin/support",
];

/* ---------- 페이지 안에서 도는 프로브 ---------- */

/**
 * 요소 단위 실측 대비. 소형 4.5:1, large text(24px 이상 또는 18.66px 이상 700) 3:1.
 * 비활성 컨트롤은 1.4.3 면제, sr-only는 화면에 없다.
 *
 * aria-hidden 글리프는 텍스트가 아니다. 접근성 트리에서 뺐다는 건 의미를 옆 텍스트가
 * 지고 이건 시각 장식이라는 선언이다(빵부스러기 구분자 "/", 메타 구분점 "·", 배너 아이콘).
 * 그래서 1.4.3의 4.5:1이 아니라 1.4.11의 3:1을 적용한다. 면제가 아니라 기준 교체다.
 * 눈에는 보이니까 최소 대비는 지켜야 한다.
 */
function probeContrast() {
  const lin = (c) => {
    const x = c / 255;
    return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
  };
  const lum = ([r, g, b]) => 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
  const parse = (s) => {
    const m = String(s).match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const p = m[1].split(/[,/]/).map((x) => parseFloat(x));
    return { rgb: [p[0], p[1], p[2]], a: p.length > 3 ? p[3] : 1 };
  };
  const over = (fg, bg) => fg.rgb.map((c, i) => c * fg.a + bg[i] * (1 - fg.a));
  const ratio = (a, b) => {
    const x = lum(a);
    const y = lum(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
  };
  /** 조상을 거슬러 올라가며 반투명 면을 차례로 합성해 실제 배경색을 만든다 */
  const effectiveBg = (el) => {
    const stack = [];
    let n = el;
    while (n && n !== document.documentElement) {
      const c = parse(getComputedStyle(n).backgroundColor);
      if (c && c.a > 0) {
        stack.push(c);
        if (c.a === 1) break;
      }
      n = n.parentElement;
    }
    const root = parse(getComputedStyle(document.documentElement).backgroundColor);
    let base = root && root.a === 1 ? root.rgb : [255, 255, 255];
    for (let i = stack.length - 1; i >= 0; i--) base = over(stack[i], base);
    return base;
  };

  const out = [];
  for (const el of document.querySelectorAll("*")) {
    if (el.closest("nextjs-portal")) continue;
    const text = [...el.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent.trim()).join("");
    if (!text) continue;
    const cs = getComputedStyle(el);
    /* display·visibility만 보면 닫힌 details·dialog 안의 내용이 "보이는 것"으로 잡힌다.
       그 요소들은 자신도 조상도 none이 아니고 rect까지 갖는다. 감사 중에 지표 메뉴
       체크박스를 실제로 그렇게 오판했다. checkVisibility가 그 판정을 대신한다 */
    if (!el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) continue;
    if (parseFloat(cs.opacity) === 0) continue;
    if (el.classList.contains("sr-only")) continue;
    if (el.disabled || el.closest("button[disabled],fieldset[disabled]")) continue;
    const box = el.getBoundingClientRect();
    if (box.width < 1 || box.height < 1) continue;
    const fgRaw = parse(cs.color);
    if (!fgRaw) continue;
    const bg = effectiveBg(el);
    const size = parseFloat(cs.fontSize);
    const weight = parseInt(cs.fontWeight) || 400;
    const decorative = !!el.closest("[aria-hidden='true']");
    const large = size >= 24 || (size >= 18.66 && weight >= 700);
    const need = decorative || large ? 3 : 4.5;
    const r = ratio(over(fgRaw, bg), bg);
    if (r >= need) continue;
    out.push({
      kind: decorative ? "장식대비" : "대비",
      where: String(el.className || el.tagName).slice(0, 44),
      detail: `${r.toFixed(2)}:1 (필요 ${need}:1, ${size}px) "${text.slice(0, 18)}"`,
      key: `c|${el.className}|${cs.color}|${bg.join()}|${size}`,
    });
  }
  return out;
}

/**
 * 플레이스홀더 대비. ::placeholder는 의사요소라 텍스트 노드 순회에 안 잡힌다.
 * 이 사각지대를 게이트가 스스로 드러냈다. tertiary(장식 대역)로 칠해져 있었는데
 * 요소 스캔으로는 영원히 안 보였다. 플레이스홀더는 1.4.3상 텍스트라 4.5:1이다.
 */
function probePlaceholder() {
  const lin = (c) => {
    const x = c / 255;
    return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
  };
  const lum = ([r, g, b]) => 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
  const parse = (s) => {
    const m = String(s).match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const p = m[1].split(/[,/]/).map((x) => parseFloat(x));
    return { rgb: [p[0], p[1], p[2]], a: p.length > 3 ? p[3] : 1 };
  };
  const over = (fg, bg) => fg.rgb.map((c, i) => c * fg.a + bg[i] * (1 - fg.a));
  const solidBg = (el) => {
    for (let n = el; n && n !== document.documentElement; n = n.parentElement) {
      const c = parse(getComputedStyle(n).backgroundColor);
      if (c && c.a === 1) return c.rgb;
    }
    const root = parse(getComputedStyle(document.documentElement).backgroundColor);
    return root && root.a === 1 ? root.rgb : [255, 255, 255];
  };

  const out = [];
  for (const el of document.querySelectorAll("input[placeholder],textarea[placeholder]")) {
    if (el.disabled || !el.getAttribute("placeholder")) continue;
    const cs = getComputedStyle(el, "::placeholder");
    const fgRaw = parse(cs.color);
    if (!fgRaw) continue;
    const bg = solidBg(el);
    const fg = over(fgRaw, bg);
    const x = lum(fg);
    const y = lum(bg);
    const r = (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    if (r >= 4.5) continue;
    out.push({
      kind: "플레이스홀더대비",
      where: String(el.className || el.name || el.id || el.tagName).slice(0, 44),
      detail: `${r.toFixed(2)}:1 (필요 4.5:1) "${el.getAttribute("placeholder").slice(0, 18)}"`,
      key: `p|${el.className}|${cs.color}|${bg.join()}`,
    });
  }
  return out;
}

/**
 * 의도치 않은 감김. 한 줄로 설계된 텍스트가 자리가 모자라 두 줄이 되고, 늘어난 높이가
 * 조상 상자를 넘겨 옆 행 위에 겹쳐 그려지는 상태다. 마켓 목록 등락률이 정확히 이거였다.
 *
 * scrollHeight로는 못 잰다. 44px 히트 확장 ::after가 absolute라 상자마다 scrollHeight를
 * 부풀리고, 의사요소는 자손 순회로 걸러낼 수 없다. 그래서 요소 상자가 아니라 텍스트의
 * 줄 상자를 직접 잰다(Range.getClientRects). 의사요소도 배경도 여기 안 잡힌다.
 *
 * 판정은 두 조건을 모두 만족할 때만이다. 여러 줄이면서, 그 높이가 조상 상자를 넘길 것.
 * 한 줄 텍스트의 줄 높이가 상자보다 큰 경우는 글리프가 그보다 작아 화면상 멀쩡하므로 뺀다.
 * 조상이 자르거나 스크롤하면(overflow가 visible이 아니면) 겹침이 아니라 다른 문제다.
 */
function probeWrap() {
  const SLACK = 1;
  const out = [];
  const range = document.createRange();
  for (const el of document.querySelectorAll("*")) {
    if (el.closest("nextjs-portal")) continue;
    if (el.classList.contains("sr-only")) continue;
    if (![...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())) continue;
    // 닫힌 details·dialog 안의 텍스트도 줄 상자를 갖는다. 대비 프로브와 같은 판정을 쓴다
    if (!el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) continue;

    range.selectNodeContents(el);
    const rects = [...range.getClientRects()].filter((r) => r.width > 0.5 && r.height > 0.5);
    if (rects.length < 2) continue;
    const tops = new Set(rects.map((r) => Math.round(r.top)));
    if (tops.size < 2) continue;
    const bottom = Math.max(...rects.map((r) => r.bottom));
    const height = bottom - Math.min(...rects.map((r) => r.top));

    for (let n = el.parentElement; n && n !== document.documentElement; n = n.parentElement) {
      if (getComputedStyle(n).overflowY !== "visible") break;
      const box = n.getBoundingClientRect();
      if (box.height < 2 || bottom <= box.bottom + SLACK) continue;
      out.push({
        kind: "감김",
        where: String(el.className || el.tagName).slice(0, 44),
        detail: `${tops.size}줄 ${Math.round(height)}px가 ${String(n.className || n.tagName).slice(0, 28)} 상자 ${Math.round(box.height)}px를 넘김`,
        key: `w|${el.className}|${tops.size}|${Math.round(height)}`,
      });
      break;
    }
  }
  return out;
}

/**
 * 화면 단위 기하. 고정형 1280px에서 가로 스크롤이 생기면 최소 폭 계약이 깨진 것이고,
 * 무스크롤 예산이 걸린 화면에 세로 스크롤이 생기면 예산이 깨진 것이다.
 */
function probeViewport(noScroll) {
  const d = document.documentElement;
  const out = [];
  if (d.scrollWidth > d.clientWidth + 1) {
    out.push({
      kind: "가로스크롤",
      where: "document",
      detail: `문서 ${d.scrollWidth}px > 뷰포트 ${d.clientWidth}px`,
      key: `vw|${d.scrollWidth}`,
    });
  }
  if (noScroll && d.scrollHeight > d.clientHeight + 1) {
    out.push({
      kind: "예산초과",
      where: "document",
      detail: `문서 ${d.scrollHeight}px > 뷰포트 ${d.clientHeight}px (무스크롤 화면)`,
      key: `vh|${d.scrollHeight}`,
    });
  }
  return out;
}

/* ---------- 구동 ---------- */

const freePort = () =>
  new Promise((res, rej) => {
    const s = createServer();
    s.on("error", rej);
    s.listen(0, "127.0.0.1", () => {
      const { port } = s.address();
      s.close(() => res(port));
    });
  });

async function startDev() {
  const port = await freePort();
  /* 시드 데이터가 있어야 패널이 데이터 상태로 간다. http 소스는 백엔드가 필요해
     게이트가 서버 기동에 묶인다. sim은 프론트 안에서 닫힌다 */
  const proc = spawn("npx", ["next", "dev", "--port", String(port)], {
    cwd: WEB_DIR,
    env: { ...process.env, NEXT_PUBLIC_DATASOURCE: "sim" },
    shell: process.platform === "win32",
    stdio: ["ignore", "pipe", "pipe"],
  });
  const url = `http://127.0.0.1:${port}`;
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error("dev 서버 기동 시간 초과")), 90_000);
    const onData = (b) => {
      if (/Ready in|started server|Local:/i.test(String(b))) {
        clearTimeout(t);
        res();
      }
    };
    proc.stdout.on("data", onData);
    proc.stderr.on("data", onData);
    proc.on("exit", (c) => rej(new Error(`dev 서버가 코드 ${c}로 종료`)));
  });
  /* Windows에서 proc.kill()은 shell 래퍼만 죽이고 그 아래 next 프로세스는 남는다.
     게이트를 돌릴 때마다 dev 서버가 하나씩 쌓여 포트와 CPU를 먹는다. 트리째 죽인다.
     동기로 부르는 이유는 stop() 직후 프로세스가 끝나기 때문이다. 비동기로 띄우면
     taskkill이 실제로 도는지 확인할 틈 없이 부모가 빠져나가 정리가 안 된 채 남는다 */
  const stop = () => {
    if (process.platform === "win32") {
      spawnSync("taskkill", ["/pid", String(proc.pid), "/T", "/F"], { stdio: "ignore" });
    } else {
      proc.kill();
    }
  };
  return { url, stop };
}

const dedupe = (list) => {
  const seen = new Set();
  return list.filter((f) => (seen.has(f.key) ? false : (seen.add(f.key), true)));
};

async function main() {
  const only = arg("only");
  const screens = only ? SCREENS.filter((s) => s.includes(only)) : SCREENS;
  if (!screens.length) {
    console.error(`--only=${only} 에 걸리는 화면 없음`);
    process.exit(2);
  }

  const external = arg("url");
  step(external ? `서버 ${external}` : "dev 서버 기동");
  const dev = external ? { url: external, stop: () => {} } : await startDev();
  step(`서버 준비 ${dev.url}`);
  const browser = await chromium.launch({ headless: !has("headed") });
  const findings = [];
  let checks = 0;

  try {
    for (const vp of VIEWPORTS) {
      const ctx = await browser.newContext({
        viewport: { width: vp.width, height: vp.height },
        /* 시세 플래시·팝 애니메이션이 살아 있으면 대비 측정이 매 실행 달라진다.
           게이트는 같은 입력에 같은 답을 내야 한다. 전이 상태는 이 게이트의 사각지대다 */
        reducedMotion: "reduce",
        colorScheme: "light",
      });
      const page = await ctx.newPage();
      for (const route of screens) {
        const noScroll = NO_SCROLL.some((re) => re.test(route));
        step(`${vp.name} ${route}`);
        await page.goto(dev.url + route, { waitUntil: "domcontentloaded", timeout: 60_000 });
        await page.waitForTimeout(DATA_MS);
        for (const theme of ["light", "dark"]) {
          await page.evaluate((t) => document.documentElement.setAttribute("data-theme", t), theme);
          await page.waitForTimeout(SETTLE_MS);
          const found = [
            ...(await page.evaluate(probeContrast)),
            ...(await page.evaluate(probePlaceholder)),
            ...(await page.evaluate(probeWrap)),
            ...(await page.evaluate(probeViewport, noScroll)),
          ];
          checks += 1;
          for (const f of dedupe(found)) findings.push({ ...f, route, vp: vp.name, theme });
        }
      }
      await ctx.close();
    }
  } finally {
    await browser.close();
    dev.stop();
  }

  if (!findings.length) {
    console.log(
      `브라우저 게이트 통과. 화면 ${screens.length}개, 뷰포트 ${VIEWPORTS.length}종, 테마 2종, 검사 ${checks}회.`,
    );
    return;
  }

  const byKind = {};
  for (const f of findings) (byKind[f.kind] ??= []).push(f);
  console.error(`브라우저 게이트 실패 ${findings.length}건.\n`);
  for (const [kind, list] of Object.entries(byKind)) {
    console.error(`[${kind}] ${list.length}건`);
    for (const f of list) console.error(`  ${f.route} ${f.vp} ${f.theme}\n    ${f.where}\n    ${f.detail}`);
    console.error("");
  }
  process.exit(1);
}

main().catch((e) => {
  console.error(e.message);
  process.exit(2);
});
