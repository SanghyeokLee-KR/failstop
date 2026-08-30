/**
 * 로그인·가입 후 복귀 경로(?next=) 해석. 외부에서 들어온 값이라 그대로 router.push에
 * 넘기면 오픈 리다이렉트가 된다. 같은 출처의 절대 경로 하나만 형태로 판정해 통과시킨다.
 */

/** 복귀 경로가 없거나 거부됐을 때의 기본 목적지 */
export const DEFAULT_NEXT = "/exchange";

/**
 * 거부 사유별 예시. //evil.com(스킴 상대)·https://evil.com(절대)은 외부 출처로 해석되고,
 * /\evil.com은 역슬래시를 슬래시로 정규화하는 브라우저에서 같아진다. exchange는 기준 경로에 따라 달라진다.
 */
export function safeNextPath(raw: string | null | undefined): string {
  if (!raw) return DEFAULT_NEXT;
  let value = raw;
  try {
    // 이중 인코딩으로 판정을 우회하는 입력을 원문으로 되돌린 뒤 본다
    value = decodeURIComponent(raw);
  } catch {
    return DEFAULT_NEXT;
  }
  if (!value.startsWith("/")) return DEFAULT_NEXT;
  if (value.startsWith("//") || value.startsWith("/\\")) return DEFAULT_NEXT;
  // 제어문자는 URL 파싱을 가르는 입력이라 형태 판정과 같이 거른다. 정규식 문자클래스로
  // 쓰면 소스에 실제 제어문자가 박혀 편집기·diff마다 다르게 보이므로 코드포인트로 본다
  if (hasControlChar(value)) return DEFAULT_NEXT;
  return value;
}

/** 현재 위치를 복귀 경로로 붙인 로그인 URL. 쿼리(프리필 가격 등)까지 보존한다 */
export function loginHref(currentPathWithQuery: string): string {
  const next = safeNextPath(currentPathWithQuery);
  if (next === DEFAULT_NEXT) return "/auth/login";
  return `/auth/login?next=${encodeURIComponent(next)}`;
}

function hasControlChar(value: string): boolean {
  for (const ch of value) {
    const code = ch.codePointAt(0) ?? 0;
    if (code < 0x20 || code === 0x7f) return true;
  }
  return false;
}

/**
 * useSearchParams를 쓰면 정적 export에서 페이지 전체가 CSR로 바일아웃한다.
 * 복귀 경로는 제출 뒤 한 번만 필요하므로 렌더가 아니라 이벤트 핸들러에서 읽는다.
 */
export function nextFromLocation(): string {
  if (typeof window === "undefined") return DEFAULT_NEXT;
  return safeNextPath(new URLSearchParams(window.location.search).get("next"));
}
