/**
 * 가입 입력 검증. 서버 MemberInputs.java의 미러이고 서버가 정본이다.
 * 규칙이 갈리면 폼은 통과하는데 서버가 거절하므로 판정 순서와 거절 사유를 서버와 맞춘다.
 * 규칙을 바꿀 때는 MemberInputs.java를 먼저 고친다(tests/memberInputs.test.ts가 두 답을 대조한다).
 */

/** RFC 5321 경로 상한 */
export const MAX_EMAIL_LENGTH = 254;
/** RFC 5321 로컬파트 상한 */
export const MAX_LOCAL_LENGTH = 64;
/** DNS 라벨 상한 */
export const MAX_LABEL_LENGTH = 63;
/** user_profiles.display_name VARCHAR(32). 코드포인트로 센다 */
export const MAX_DISPLAY_NAME = 32;
/** 서버 MIN_PASSWORD_LENGTH */
export const MIN_PASSWORD_LENGTH = 8;
/** 서버 MAX_PASSWORD_LENGTH. Argon2에 임의 길이를 먹이지 않기 위한 상한이다 */
export const MAX_PASSWORD_LENGTH = 128;

const LOCAL_SPECIALS = "._%+-";

const isAsciiLetter = (c: string) => (c >= "a" && c <= "z") || (c >= "A" && c <= "Z");
const isAsciiAlnum = (c: string) => isAsciiLetter(c) || (c >= "0" && c <= "9");

/** 서버 trim()과 같은 범위. U+0020 이하만 걷는다(그 밖의 공백은 안쪽 검사에서 잡힌다) */
function trimAsciiSpace(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && value.charCodeAt(start) <= 0x20) start++;
  while (end > start && value.charCodeAt(end - 1) <= 0x20) end--;
  return value.slice(start, end);
}

/**
 * @returns 오류 문구. 통과면 null
 */
export function emailError(raw: string): string | null {
  const value = trimAsciiSpace(raw);
  if (value.length === 0) return "이메일 입력 필요";
  if (value.length > MAX_EMAIL_LENGTH) return "이메일이 너무 김";
  for (const ch of value) {
    const code = ch.codePointAt(0) ?? 0;
    // 서버는 c <= ' ' || isWhitespace || isISOControl을 본다. 같은 집합을 쓴다.
    // 여기서도 `\s`를 피하는 이유는 표시명 쪽과 같다(ECMAScript는 U+FEFF를 공백으로 친다).
    // U+FEFF는 공백이 아니라 쓸 수 없는 문자로 아래 문자 검사가 잡는다
    const isoControl = code <= 0x1f || (code >= 0x7f && code <= 0x9f);
    if (code <= 0x20 || isoControl || isJavaWhitespace(ch)) {
      return "이메일에 공백을 쓸 수 없음";
    }
  }
  const at = value.indexOf("@");
  if (at < 0 || at !== value.lastIndexOf("@")) return "이메일 형식 확인 필요";

  const local = value.slice(0, at);
  if (local.length === 0 || local.length > MAX_LOCAL_LENGTH) return "이메일 형식 확인 필요";
  if (local.startsWith(".") || local.endsWith(".")) return "이메일의 점 위치 확인 필요";
  let prev = "";
  for (const c of local) {
    if (!isAsciiAlnum(c) && !LOCAL_SPECIALS.includes(c)) return "이메일에 쓸 수 없는 문자 있음";
    if (c === "." && prev === ".") return "이메일에 점이 연속될 수 없음";
    prev = c;
  }

  const domain = value.slice(at + 1);
  if (domain.length === 0) return "이메일 형식 확인 필요";
  if (domain.startsWith(".") || domain.endsWith(".")) return "이메일의 점 위치 확인 필요";
  const labels = domain.split(".");
  if (labels.length < 2) return "이메일 도메인 형식 확인 필요";
  for (const label of labels) {
    if (label.length === 0 || label.length > MAX_LABEL_LENGTH) return "이메일 도메인 형식 확인 필요";
    if (label.startsWith("-") || label.endsWith("-")) return "이메일 도메인의 하이픈 위치 확인 필요";
    for (const c of label) {
      if (!isAsciiAlnum(c) && c !== "-") return "이메일 도메인에 쓸 수 없는 문자 있음";
    }
  }
  const tld = labels[labels.length - 1];
  if (tld.length < 2) return "이메일 도메인 형식 확인 필요";
  for (const c of tld) {
    if (!isAsciiLetter(c)) return "이메일 도메인 형식 확인 필요";
  }
  return null;
}

/**
 * 서버 collapseWhitespace 미러. ECMAScript의 \s는 U+FEFF(BOM)를 포함하지만 Java에는 없어,
 * \s로 접으면 프론트는 BOM을 통과시키고 서버는 거절한다. Java와 같은 집합만 접는다.
 */
const SEPARATORS = /[\p{Zs}\p{Zl}\p{Zp}]/u;

function isJavaWhitespace(ch: string): boolean {
  const code = ch.codePointAt(0) ?? 0;
  // 탭·개행·수직탭·폼피드·캐리지리턴과 파일·그룹·레코드·유닛 구분자(0x1C~0x1F).
  // Java isWhitespace가 true를 주는 제어문자는 이 둘뿐이다
  if (code === 0x09 || code === 0x0a || code === 0x0b || code === 0x0c || code === 0x0d) return true;
  if (code >= 0x1c && code <= 0x1f) return true;
  return SEPARATORS.test(ch);
}

export function normalizeDisplayName(raw: string): string {
  const nfc = raw.normalize("NFC");
  const out: string[] = [];
  let pendingSpace = false;
  for (const ch of nfc) {
    if (isJavaWhitespace(ch)) {
      pendingSpace = out.length > 0;
      continue;
    }
    if (pendingSpace) {
      out.push(" ");
      pendingSpace = false;
    }
    out.push(ch);
  }
  return out.join("");
}

/**
 * @returns 오류 문구. 통과면 null
 */
export function displayNameError(raw: string): string | null {
  const value = normalizeDisplayName(raw);
  if (value.length === 0) return "표시명 입력 필요";
  // 보이지 않는 문자를 막는 이유는 표시명이 게시판에서 작성자로 렌더되기 때문이다.
  // 제로폭이면 두 계정이 화면상 같은 이름을 갖고, 양방향 제어면 이름이 거꾸로 읽힌다
  if (/[\p{Cc}\p{Cf}\p{Co}\p{Cs}\p{Zl}\p{Zp}]/u.test(value)) {
    return "표시명에 보이지 않는 문자를 쓸 수 없음";
  }
  if ([...value].length > MAX_DISPLAY_NAME) return `표시명은 최대 ${MAX_DISPLAY_NAME}자`;
  return null;
}

/**
 * 복잡도 규칙(특수문자·대소문자 혼용)은 정본이 "연극적 규칙"으로 배제했다(회원_설계 §1-1).
 * 상한은 보안이 아니라 Argon2가 임의 길이 입력을 해싱하지 않게 하는 방어다.
 *
 * @returns 오류 문구. 통과면 null
 */
export function passwordError(raw: string): string | null {
  if (raw.length === 0) return "비밀번호 입력 필요";
  if (raw.length < MIN_PASSWORD_LENGTH) return `비밀번호는 최소 ${MIN_PASSWORD_LENGTH}자`;
  if (raw.length > MAX_PASSWORD_LENGTH) return `비밀번호는 최대 ${MAX_PASSWORD_LENGTH}자`;
  return null;
}
