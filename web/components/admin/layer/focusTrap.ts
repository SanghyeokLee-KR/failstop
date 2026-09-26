/**
 * 관리 콘솔 층(모달·드로어·확인 대화상자)의 포커스 규칙(디자인 시스템 §12-13). DOM을 직접 만지지 않는 순수
 * 함수라 웹 단위 시험이 가짜 요소로 돌린다. 훅(useLayer)이 실제 요소를 넘긴다.
 */

/** 층이 겹친 순서. Esc와 Tab은 맨 위 층만 받는다 */
const stack: object[] = [];

/** 층을 쌓고, 걷어 내는 함수를 돌려준다. 가운데 층이 먼저 닫혀도 제 자리만 걷는다 */
export function pushLayer(token: object): () => void {
  stack.push(token);
  return () => {
    const i = stack.lastIndexOf(token);
    if (i >= 0) stack.splice(i, 1);
  };
}

export function isTopLayer(token: object): boolean {
  return stack.length > 0 && stack[stack.length - 1] === token;
}

/** 시험이 층 쌓임을 비운다 */
export function resetLayers(): void {
  stack.length = 0;
}

/**
 * Tab 순환. 층 안의 첫·끝에서만 반대 끝을 돌려주고, 안쪽이면 null이라 브라우저가 옮긴다. 포커스가 층 밖에
 * 있으면(초기 포커스 전 등) 방향에 맞는 끝으로 들인다. 누를 것이 없으면 null이고 호출자가 층 자체에 둔다
 */
export function wrapFocus<T>(items: readonly T[], active: T | null, backwards: boolean): T | null {
  if (items.length === 0) return null;
  const first = items[0];
  const last = items[items.length - 1];
  if (active === null || !items.includes(active)) return backwards ? last : first;
  if (backwards && active === first) return last;
  if (!backwards && active === last) return first;
  return null;
}

export interface InertTarget {
  hasAttribute(name: string): boolean;
  setAttribute(name: string, value: string): void;
  removeAttribute(name: string): void;
}

/**
 * 배경 격리. 층 밖의 형제를 inert로 만들고 되돌리는 함수를 준다. 이미 inert였던 형제(아래 층이 격리해 둔
 * 것)는 건드리지 않아, 위 층이 닫혀도 아래 층의 격리가 풀리지 않는다
 */
export function isolate(siblings: readonly InertTarget[], keep: (el: InertTarget) => boolean): () => void {
  const touched = siblings.filter((el) => !keep(el) && !el.hasAttribute("inert"));
  for (const el of touched) el.setAttribute("inert", "");
  return () => {
    for (const el of touched) el.removeAttribute("inert");
  };
}

export interface FocusReturn {
  focus(options?: { preventScroll?: boolean }): void;
  isConnected?: boolean;
}

/**
 * 닫힌 뒤 돌아갈 자리. 연 단추가 아직 문서에 있으면 그 단추, 판이 바뀌어 사라졌으면 대신할 자리(그 판의 제목)다.
 * 포커스를 body로 떨어뜨리면 키보드 사용자가 판의 처음부터 다시 찾아야 한다
 */
export function returnTarget<T extends FocusReturn>(opener: T | null, fallback: T | null): T | null {
  if (opener && opener.isConnected !== false) return opener;
  return fallback;
}
