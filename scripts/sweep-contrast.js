/**
 * sweep-contrast.js. 요소 단위 실측 대비 스윕.
 *
 * 사용: dev 서버를 띄우고 검사할 화면에서 devtools 콘솔에 이 파일 전체를 붙여넣는다.
 *   await sweep()            현재 테마만
 *   await sweep({both:true}) 라이트·다크 둘 다 (테마를 토글하고 되돌린다)
 *
 * 왜 별도인가: scripts/check-tokens.mjs는 토큰 값과 소비 규칙을 본다. 하지만 "이 텍스트가
 * 실제로 어느 면 위에 놓이는가"는 계산된 스타일이 있어야 안다. 우리를 두 번 문 결함이
 * 정확히 그 종류였다. sunken 면 위 secondary는 4.27:1인데, 그 조합은 CSS 두 파일에
 * 나뉘어 있어 정적으로는 안 보인다.
 *
 * 판정: 소형 텍스트 4.5:1, large text(24px 이상 또는 18.66px 이상 700) 3:1.
 * 비활성 컨트롤은 1.4.3 면제라 제외한다. sr-only도 제외한다.
 *
 * 주의: 테마를 바꾼 직후에는 전환 애니메이션이 끝나야 값이 확정된다. both 모드는
 * 테마마다 2.5초를 기다린다. 그 대기 없이 재면 전환 중간값이 잡혀 오탐이 난다.
 */

(() => {
  const SETTLE_MS = 2500;

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
  function effectiveBg(el) {
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
  }

  function scanOnce() {
    const out = [];
    for (const el of document.querySelectorAll("*")) {
      const text = [...el.childNodes]
        .filter((n) => n.nodeType === 3)
        .map((n) => n.textContent.trim())
        .join("");
      if (!text) continue;
      const cs = getComputedStyle(el);
      if (cs.visibility === "hidden" || cs.display === "none" || parseFloat(cs.opacity) === 0) continue;
      if (el.classList.contains("sr-only")) continue;
      if (el.disabled || el.closest("button[disabled],fieldset[disabled]")) continue;
      const box = el.getBoundingClientRect();
      if (box.width < 1 || box.height < 1) continue;
      const fgRaw = parse(cs.color);
      if (!fgRaw) continue;
      const bg = effectiveBg(el);
      const fg = over(fgRaw, bg);
      const size = parseFloat(cs.fontSize);
      const weight = parseInt(cs.fontWeight) || 400;
      const need = size >= 24 || (size >= 18.66 && weight >= 700) ? 3 : 4.5;
      const r = ratio(fg, bg);
      if (r >= need) continue;
      out.push({
        text: text.slice(0, 22),
        cls: String(el.className || el.tagName).slice(0, 40),
        size,
        ratio: +r.toFixed(2),
        need,
        fg: cs.color,
        bg: `rgb(${bg.map(Math.round).join(",")})`,
      });
    }
    const seen = new Set();
    return out.filter((o) => {
      const key = o.cls + o.fg + o.bg + o.size;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  const wait = (ms) => new Promise((r) => setTimeout(r, ms));

  window.sweep = async ({ both = false } = {}) => {
    const root = document.documentElement;
    const original = root.getAttribute("data-theme");
    const report = {};
    const themes = both ? ["light", "dark"] : [original || getComputedStyle(root).colorScheme];
    for (const t of themes) {
      if (both) {
        root.setAttribute("data-theme", t);
        await wait(SETTLE_MS);
      }
      report[t] = scanOnce();
    }
    if (both) {
      if (original) root.setAttribute("data-theme", original);
      else root.removeAttribute("data-theme");
    }

    const total = Object.values(report).reduce((a, b) => a + b.length, 0);
    console.log(`${location.pathname} 대비 스윕: 실패 ${total}건`);
    for (const [t, list] of Object.entries(report)) {
      if (!list.length) {
        console.log(`  [${t}] 통과`);
        continue;
      }
      console.log(`  [${t}] ${list.length}건`);
      console.table(list);
    }
    return report;
  };

  console.log('sweep() 준비됨. await sweep() 또는 await sweep({both:true})');
})();
