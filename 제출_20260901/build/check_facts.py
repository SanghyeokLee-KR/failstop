# 어투를 고칠 때 사실이 함께 바뀌는 것을 잡는다.
#
# 제출본을 존댓말로 다시 쓰면 문장이 통째로 갈린다. 그 과정에서 숫자 하나가 빠지거나
# 커밋 해시가 다른 값으로 바뀌어도 사람 눈에는 안 보인다. 어투 교정은 사실을 건드리지
# 않아야 하므로, 사실로 취급할 토큰만 뽑아 집합으로 비교한다.
#
# 사용:
#   git show HEAD:경로 > before.md
#   python check_facts.py before.md after.md
#
# 종료코드 0이면 사실 집합이 같다. 1이면 다르다.
import io, re, sys, collections

# 사실로 취급하는 토큰. 어투가 바뀌어도 이 값들은 그대로여야 한다.
PATTERNS = {
    # 경계에 \w 를 쓰면 안 된다. 파이썬 \w 가 한글을 포함해서 "220건" "40일" 처럼
    # 단위가 한글인 수치가 통째로 빠진다. 이 문서는 그런 수치가 대부분이다.
    '수치': r'(?<![\d.])\d{1,3}(?:,\d{3})+(?:\.\d+)?|(?<![\d.])\d+\.\d+|(?<![\d.])\d+(?![\d.])',
    '커밋해시': r'\b[0-9a-f]{7,40}\b',
    '경로': r'[\w가-힣_.-]+/[\w가-힣_./-]+\.\w+|[\w가-힣_-]+\.(?:md|ps1|java|ts|tsx|yml|yaml|sql|json|js|py|csv|png|pdf|docx|pptx)',
    '절번호': r'§\s*[\d.\-]+|\b\d+\.\d+(?:\.\d+)?\s*(?=절|항)',
    '식별자': r'\b[A-Z][A-Z0-9_]{2,}\b|\bC\d{2}\b|\bE\d{1,2}\b|\bT\d\b|\bM\d\b|\bV\d{1,2}\b|\bW\d\b',
    '통화': r'USD\s*[\d,.]+|\b[\d,]+\s*원\b',
}

# 숫자 중 어투 교정으로 정당하게 변할 수 있는 것은 없다고 본다. 다만 조사가 붙어
# 토큰 경계가 흔들리는 한글 특성상, 단독 자릿수(0~9)는 비교에서 뺀다. 순서 표현이나
# 문장 부호와 붙어 나오는 경우가 많아 잡음이 크다.
def extract(text):
    out = {}
    for name, pat in PATTERNS.items():
        vals = re.findall(pat, text)
        if name == '수치':
            vals = [v for v in vals if not (len(v) == 1 and v.isdigit())]
        out[name] = collections.Counter(v.strip() for v in vals if v.strip())
    return out


def main():
    if len(sys.argv) != 3:
        print('사용: python check_facts.py <이전> <이후>')
        return 2
    a = extract(io.open(sys.argv[1], encoding='utf-8').read())
    b = extract(io.open(sys.argv[2], encoding='utf-8').read())

    bad = 0
    for name in PATTERNS:
        lost = a[name] - b[name]
        gained = b[name] - a[name]
        if not lost and not gained:
            print(f'  {name:<8} 동일 ({sum(a[name].values())}개)')
            continue
        bad = 1
        print(f'  {name:<8} 다름')
        for v, n in sorted(lost.items())[:25]:
            print(f'      사라짐  {v}  x{n}')
        for v, n in sorted(gained.items())[:25]:
            print(f'      생김    {v}  x{n}')
        if len(lost) > 25 or len(gained) > 25:
            print(f'      ...(사라짐 {len(lost)}종, 생김 {len(gained)}종)')

    print()
    if bad:
        print('사실 집합이 달라졌다. 어투 교정이 내용을 건드렸다.')
    else:
        print('사실 집합 동일. 어투만 바뀌었다.')
    return bad


if __name__ == '__main__':
    sys.exit(main())
