/**
 * 관리 콘솔 글자의 줄바꿈 자리(디자인 시스템 §12-12). 가운뎃점으로 이은 글('앱 계층 · 평균 74%')은 가운뎃점 앞
 * 공백을 줄바꿈하지 않는 공백(U+00A0)으로 바꿔 줄이 가운뎃점으로 시작하지 않게 한다. 뒤 공백은 그대로라 줄은
 * 가운뎃점 뒤에서 바뀐다. 퍼센트 기호 뒤에 조사가 붙은 낱말('74%입니다', '73%로')은 기호와 조사 사이에 낱말
 * 잇기(U+2060)를 넣는다. keep-all이어도 브라우저는 % 뒤를 줄 바꿀 자리로 보아 '74%' 끝에서 줄이 갈렸다.
 * 시뮬레이터와 상담 규칙이 만든 문장은 시험이 보는 원문이라 그리는 자리에서 바꾼다
 */
export function joinDots(text: string): string {
  return text.replace(/ ·/g, "\u00a0·").replace(/%(?=[가-힣])/g, "%\u2060");
}

/**
 * 받침에 맞춘 조사. 조치 확인 제목('증설 CPU 임계를 60%에서 55%로 낮춥니다')처럼 값과 단위가 바뀌는 문장에서
 * '20시간로', '임계값를' 같은 어긋남을 막는다. 마지막 글자가 한글이 아니면 읽는 소리로 본다(%는 '퍼센트', 숫자는
 * 한자어 수 읽기, 영문 대문자 약어는 받침 없음). ㄹ 받침은 '로'를 받는다
 */
const DIGIT_FINAL: Record<string, number> = { "0": 21, "1": 8, "2": 0, "3": 16, "4": 0, "5": 0, "6": 1, "7": 8, "8": 8, "9": 0 };

function finalOf(word: string): number {
  const ch = word.trim().slice(-1);
  if (ch === "") return 0;
  const code = ch.charCodeAt(0);
  if (code >= 0xac00 && code <= 0xd7a3) return (code - 0xac00) % 28;
  if (ch in DIGIT_FINAL) return DIGIT_FINAL[ch];
  return 0;
}

/** 목적격 조사(을/를) */
export function objectParticle(word: string): string {
  return finalOf(word) === 0 ? "를" : "을";
}

/** 방향 조사(으로/로). 받침이 없거나 ㄹ 받침이면 '로'다 */
export function directionParticle(word: string): string {
  const f = finalOf(word);
  return f === 0 || f === 8 ? "로" : "으로";
}
