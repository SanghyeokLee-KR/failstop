/**
 * copy-tone.mjs. UI 카피 종결형 규칙(디자인 시스템 §10-1). check-tokens.mjs가 가져다 쓰고
 * scripts/tests/copy-tone.test.mjs가 따로 시험한다. 외부 의존 없이 node 내장만 쓴다.
 *
 * 평면마다 규칙이 다르다(2026-09-25 사용자 결정).
 *   관리 콘솔(app/admin, components/admin, lib/admin): 문장은 합니다체, 라벨은 명사형.
 *     반말 평서문('한다', '된다', '없다', '이다')으로 끝나는 문구를 잡는다.
 *   그 밖의 화면(거래 화면 등): 종전 그대로 명사형. 존댓말 종결('합니다', '하세요')을 잡는다.
 */

/** 관리 콘솔 파일인가. web/ 기준 상대 경로를 받는다 */
export const ADMIN_CONSOLE_PREFIXES = ["app/admin/", "components/admin/", "lib/admin/"];
export const isAdminConsole = (rel) => ADMIN_CONSOLE_PREFIXES.some((p) => rel.startsWith(p));

/** 한글 음절의 종성이 ㅂ인가. 음절은 (코드 - 0xAC00) % 28 이 종성 색인이고 ㅂ은 17이다 */
const hasBieupFinal = (c) => c >= 0xac00 && c <= 0xd7a3 && (c - 0xac00) % 28 === 17;

/**
 * 'ㅂ니다' 어미인가. 종전 규칙은 '습니다'만 보아 '됩니다'·'뜹니다'·'입니다'를 통과시켰다.
 * 그렇다고 '니다'를 통으로 잡으면 '아니다' 같은 평서형까지 존댓말로 걸린다.
 * 앞 글자의 종성이 ㅂ인지로 가른다.
 */
export function hasHonorificNida(text) {
  for (let i = 1; i + 1 < text.length; i += 1) {
    if (text[i] !== "니" || text[i + 1] !== "다") continue;
    if (hasBieupFinal(text.charCodeAt(i - 1))) return true;
  }
  return false;
}

/** 거래 화면 등 관리 콘솔 밖에서 잡는 존댓말 종결 */
export function honorificEnding(text) {
  const m = text.match(/하세요|하십시오|십시오|해 주세요|해주세요/);
  if (m) return m[0];
  return hasHonorificNida(text) ? "ㅂ니다" : null;
}

/**
 * '다'로 끝나지만 종결이 아닌 어절. 비교·반복 조사(보다, 마다)다.
 * 문구가 식을 사이에 두고 끊기면('{a}보다 {b}') 조사가 조각 끝에 서므로 어절째 뺀다.
 */
const NOT_ENDING = [/보다$/, /마다$/];

/**
 * 관리 콘솔에서 잡는 반말 평서문 종결. 한글 어간 뒤 '다'로 끝나는 어절을 찾고, 'ㅂ니다'(합니다체)와
 * 조사를 뺀다. 찾은 어절을 돌려주고 없으면 null이다. '아니다'는 종성이 ㅂ이 아니라 반말로 잡힌다.
 */
export function plainEnding(text) {
  for (const m of text.matchAll(/([가-힣]+)다(?![가-힣])/g)) {
    const word = `${m[1]}다`;
    if (m[1].endsWith("니") && m[1].length >= 2 && hasBieupFinal(m[1].charCodeAt(m[1].length - 2))) continue;
    if (NOT_ENDING.some((re) => re.test(word))) continue;
    return word;
  }
  return null;
}

/**
 * 소스에서 한국어가 든 문자열·JSX 텍스트를 모은다. 세 따옴표를 모두 본다.
 *
 * 한국어 조건을 정규식 안에 넣지 않는 이유가 있다. 그렇게 하면 한국어가 없는 문자열의
 * 닫는 따옴표와 그다음 문자열의 여는 따옴표가 한 쌍으로 잡혀, 사이의 코드를 문자열로
 * 삼키고 정작 그다음의 진짜 문구를 놓친다. OperatorBar의 토스트가 그렇게 빠져나갔다.
 * 따옴표 짝은 순서대로 뜨고 한국어 여부는 그 뒤에 가른다.
 *
 * jsxSplit을 켜면 식({...})으로 끊긴 JSX 문구 조각도 모은다. '자동 조치 {n}회를 실행했다'의
 * 뒤 조각처럼 태그와 식 사이에 놓인 문구는 태그 사이만 보는 규칙으로는 빠진다.
 */
export function koreanLiterals(code, { jsxSplit = false } = {}) {
  const out = [];
  const take = (t, fold) => {
    if (!/[가-힣]/.test(t)) return;
    /* 중첩 표현식과 JSX 때문에 구간이 엉뚱하게 길게 잡힐 수 있어 코드로 보이는 것은 버린다.
       UI 문구에는 세미콜론도 큰따옴표도 닫는 태그도 들어가지 않는다 */
    if (t.length > 200 || /[;"]|<\/|\/>/.test(t)) return;
    out.push(fold ? t.replace(/\s+/g, " ") : t);
  };
  for (const m of code.matchAll(/"([^"\n]*)"/g)) {
    if (/[가-힣]/.test(m[1])) out.push(m[1]);
  }
  for (const m of code.matchAll(/'([^'\n]*)'/g)) take(m[1], false);
  /* 백틱은 줄바꿈을 품을 수 있다. 한 문장으로 접어 어미만 본다 */
  for (const m of code.matchAll(/`([^`]*)`/g)) take(m[1], true);
  /* JSX 텍스트는 줄바꿈을 품는다. 태그 사이에 홀로 놓인 문단이 그렇다. 줄바꿈을 막으면
     그 형태가 통째로 빠지는데, 로그인 안내문이 정확히 그 모양으로 규칙을 피해 있었다.
     꺾쇠와 중괄호를 제외한 구간이라 태그를 넘어가지 않는다 */
  for (const m of code.matchAll(/>([^<>{}]*)</g)) {
    const t = m[1].trim();
    if (/[가-힣]/.test(t)) out.push(t.replace(/\s+/g, " "));
  }
  if (jsxSplit) {
    for (const m of code.matchAll(/[>}]([^<>{}]*)[<{]/g)) {
      const t = m[1].trim();
      /* 태그 사이 조각은 위에서 이미 모았다. 식에 붙은 조각만 더한다 */
      if (m[0].startsWith(">") && m[0].endsWith("<")) continue;
      take(t, true);
    }
  }
  return out;
}

/**
 * 파일 하나의 문구를 평면 규칙으로 검사한다. rel은 web/ 기준 경로, code는 주석을 걷은 소스다.
 * 문제마다 { text, found, rule } 를 돌려준다.
 */
export function checkCopyTone(rel, code) {
  const admin = isAdminConsole(rel);
  const problems = [];
  for (const t of koreanLiterals(code, { jsxSplit: true })) {
    const found = admin ? plainEnding(t) : honorificEnding(t);
    if (found) problems.push({ text: t, found, rule: admin ? "admin-plain" : "honorific" });
  }
  return problems;
}
