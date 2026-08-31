# 기획안 마크다운을 Word 문서로 변환한다.
# pandoc 이 없어 직접 변환한다. 표 93개·제목 92개라 수작업은 불가능하다.
#
# 도면과 코드 펜스를 다룬다. 종전 판은 둘 다 없어서 두 가지가 깨졌다.
# 첫째, 마크다운의 이미지 문법을 몰라 제출본에 삽입된 그림이 0장이었다. 학교 공지가
# 인프라 다이어그램을 명시 강조했는데 캡션만 남고 그림이 없는 상태였다.
# 둘째, 코드 펜스를 몰라 펜스 안의 ASCII 도면이 본문 폰트 문단으로 쏟아졌고, 그 안의
# 세로줄이 표 구분자로 잡혀 도면 한 장이 깨진 표 여러 개로 변했다.
# 그래서 펜스 판정을 표 판정보다 먼저 둔다. 순서가 곧 정확성이다.
import io, os, re, sys
from docx import Document
from docx.shared import Pt, Cm, RGBColor, Emu
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

KO = '맑은 고딕'
INK = RGBColor(0x1B, 0x24, 0x29)
MUTE = RGBColor(0x5A, 0x6B, 0x75)
# lxml 은 xml 접두사를 nsmap 으로 풀지 않으므로 전체 URI 를 쓴다
XML_SPACE = '{http://www.w3.org/XML/1998/namespace}space'

src, dst = sys.argv[1], sys.argv[2]
# 이미지 경로는 마크다운 파일 위치를 기준으로 푼다. 실행 디렉터리 기준으로 풀면
# build.ps1 이 어디서 호출되느냐에 따라 그림이 붙었다 빠졌다 한다
SRC_DIR = os.path.dirname(os.path.abspath(src))
md = io.open(src, encoding='utf-8').read().split('\n')

doc = Document()

# 기본 스타일. 한글 폰트는 eastAsia 속성까지 지정해야 적용된다
st = doc.styles['Normal']
st.font.name = KO
st.font.size = Pt(10)
st.element.rPr.rFonts.set(qn('w:eastAsia'), KO)
st.paragraph_format.space_after = Pt(4)
st.paragraph_format.line_spacing = 1.32

for sec in doc.sections:
    sec.top_margin = Cm(2.0); sec.bottom_margin = Cm(2.0)
    sec.left_margin = Cm(2.2); sec.right_margin = Cm(2.2)

def set_ko(run, name=KO):
    run.font.name = name
    run._element.rPr.rFonts.set(qn('w:eastAsia'), name)

INLINE = re.compile(r'(\*\*.+?\*\*|`[^`]+`)')

def emit_runs(p, text):
    for part in INLINE.split(text):
        if not part:
            continue
        if part.startswith('**') and part.endswith('**'):
            r = p.add_run(part[2:-2]); r.bold = True; set_ko(r)
        elif part.startswith('`') and part.endswith('`'):
            r = p.add_run(part[1:-1]); set_ko(r, 'Consolas'); r.font.size = Pt(9)
        else:
            r = p.add_run(part); set_ko(r)

def shade(cell, hexcolor):
    el = OxmlElement('w:shd')
    el.set(qn('w:val'), 'clear'); el.set(qn('w:fill'), hexcolor)
    cell._tc.get_or_add_tcPr().append(el)

def heading(text, level):
    h = doc.add_heading(level=level)
    sizes = {1: 20, 2: 14, 3: 11.5}
    for part in INLINE.split(text):
        if not part:
            continue
        clean = part[2:-2] if part.startswith('**') else part
        r = h.add_run(clean); set_ko(r)
        r.font.size = Pt(sizes.get(level, 11))
        r.font.color.rgb = INK
        r.bold = True
    h.paragraph_format.space_before = Pt(14 if level <= 2 else 9)
    h.paragraph_format.space_after = Pt(5)
    return h

def parse_row(line):
    return [c.strip() for c in line.strip().strip('|').split('|')]


# 본문 폭. 그림은 이 폭에 맞춰 넣고 세로는 비율대로 따라간다
SEC = doc.sections[0]
TEXT_WIDTH = Emu(SEC.page_width - SEC.left_margin - SEC.right_margin)

IMAGE = re.compile(r'^!\[([^\]]*)\]\(([^)\s]+)\)\s*$')
# 캡션은 코드 스팬으로 감싼 [그림 N] 형식이다. 본문 표기를 바꾸지 않고 그대로 받는다
CAPTION = re.compile(r'^`?\[그림\s*\d+\][^`]*`?$')


def add_image(path, alt):
    """그림 한 장. 파일이 없으면 자리를 알리는 문단으로 대신하고 계속 진행한다.

    여기서 예외를 올리면 그림 하나 때문에 제출본 생성 전체가 멈춘다. 마감 직전에
    그 거동은 위험하다. 대신 무엇이 없는지 문서에 남겨 빌드 로그가 아니라 결과물에서
    결손이 보이게 한다.
    """
    full = path if os.path.isabs(path) else os.path.join(SRC_DIR, path)
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(8)
    p.paragraph_format.space_after = Pt(2)
    if not os.path.exists(full):
        r = p.add_run('[그림 누락] ' + (alt or path) + '  ·  ' + path)
        set_ko(r)
        r.font.size = Pt(9)
        r.font.color.rgb = RGBColor(0xBC, 0x1B, 0x2A)
        print('경고: 그림 없음', full)
        return
    p.add_run().add_picture(full, width=TEXT_WIDTH)


def add_caption(text):
    """그림 아래 캡션. 가운데 정렬에 작은 회색이라 본문과 구분된다."""
    clean = text.strip().strip('`').strip()
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = p.add_run(clean)
    set_ko(r)
    r.font.size = Pt(9)
    r.font.color.rgb = MUTE
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(10)


def add_code_block(lines):
    """코드 펜스. 등폭 글꼴에 들여쓰기를 보존한다.

    ASCII 도면이 여기로 들어온다. 비례 글꼴로 그리면 세로선이 어긋나 도면이 아니라
    글자 더미가 된다. 선행 공백은 Word 가 기본으로 잘라내므로 xml:space 를 명시한다.
    """
    for raw in lines:
        p = doc.add_paragraph()
        p.paragraph_format.space_after = Pt(0)
        p.paragraph_format.space_before = Pt(0)
        p.paragraph_format.line_spacing = 1.0
        r = p.add_run(raw if raw else ' ')
        # 라틴만 등폭으로 두고 한글은 맑은 고딕에 맡긴다. eastAsia 까지 Consolas 로 두면
        # Consolas 에 한글 글리프가 없어 Word 가 임의 글꼴로 대체하고 자간이 더 흐트러진다
        r.font.name = 'Consolas'
        r._element.rPr.rFonts.set(qn('w:eastAsia'), KO)
        r.font.size = Pt(8)
        r.font.color.rgb = INK
        for t in r._element.findall(qn('w:t')):
            t.set(XML_SPACE, 'preserve')
    gap = doc.add_paragraph()
    gap.paragraph_format.space_after = Pt(6)

def add_toc():
    # Word 가 열릴 때 채우는 TOC 필드. 여기서는 자리와 코드만 심는다
    heading('목차', 1)
    p = doc.add_paragraph()
    fld = OxmlElement('w:fldSimple')
    fld.set(qn('w:instr'), r'TOC \o "1-2" \h \z \u')
    inner = OxmlElement('w:r')
    t = OxmlElement('w:t')
    t.text = '문서를 열고 F9 를 누르면 목차가 갱신된다.'
    inner.append(t); fld.append(inner)
    p._p.append(fld)
    br = doc.add_paragraph()
    r = br.add_run(); r.add_break(WD_BREAK.PAGE)

toc_done = False
in_summary = False   # 요약은 본문보다 조밀하게 조판한다
i = 0
n = len(md)
while i < n:
    line = md[i]
    stripped = line.strip()

    # 코드 펜스를 가장 먼저 본다. 뒤로 미루면 펜스 안의 빈 줄이 건너뛰기에 먹히고,
    # 세로줄이 표로, 우물정자가 제목으로 잡혀 도면 한 장이 통째로 부서진다
    if stripped.startswith('```'):
        j = i + 1
        block = []
        while j < n and not md[j].strip().startswith('```'):
            block.append(md[j]); j += 1
        add_code_block(block)
        i = j + 1; continue

    if not stripped:
        i += 1; continue

    if stripped == '---':
        i += 1; continue

    im = IMAGE.match(stripped)
    if im:
        add_image(im.group(2), im.group(1))
        # 바로 다음 유효 줄이 캡션이면 그림에 붙여 조판한다
        j = i + 1
        while j < n and not md[j].strip():
            j += 1
        if j < n and CAPTION.match(md[j].strip()):
            add_caption(md[j].strip())
            i = j + 1; continue
        i += 1; continue

    if CAPTION.match(stripped):
        add_caption(stripped)
        i += 1; continue

    # 목차 자리를 마크다운이 직접 지정한다. 요약을 목차 앞에 두기 위해서다
    if stripped == '<!--TOC-->':
        in_summary = False
        if not toc_done:
            add_toc(); toc_done = True
        i += 1; continue

    m = re.match(r'^(#{1,6})\s+(.*)$', stripped)
    if m:
        level = min(len(m.group(1)), 3)
        if m.group(2).strip() == '요약':
            in_summary = True
        heading(m.group(2), level)
        i += 1; continue

    # 표: 헤더행 + 구분행 + 본문행
    if stripped.startswith('|') and i + 1 < n and re.match(r'^\|[\s:\-|]+\|$', md[i+1].strip()):
        headers = parse_row(stripped)
        rows = []
        j = i + 2
        while j < n and md[j].strip().startswith('|'):
            rows.append(parse_row(md[j].strip())); j += 1
        t = doc.add_table(rows=1, cols=len(headers))
        t.style = 'Table Grid'
        t.alignment = WD_TABLE_ALIGNMENT.CENTER
        for k, h in enumerate(headers):
            c = t.rows[0].cells[k]
            c.text = ''
            p = c.paragraphs[0]; p.paragraph_format.space_after = Pt(1)
            emit_runs(p, h)
            for r in p.runs:
                r.bold = True; r.font.size = Pt(9); r.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
            shade(c, '2F3E47')
        for row in rows:
            cells = t.add_row().cells
            for k in range(len(headers)):
                c = cells[k]; c.text = ''
                p = c.paragraphs[0]; p.paragraph_format.space_after = Pt(1)
                emit_runs(p, row[k] if k < len(row) else '')
                for r in p.runs:
                    r.font.size = Pt(8.5 if in_summary else 9)
                    if in_summary:
                        p.paragraph_format.line_spacing = 1.05
        gap = doc.add_paragraph()
        gap.paragraph_format.space_after = Pt(2)
        if in_summary:
            gap.paragraph_format.line_spacing = 0.6
        i = j; continue

    # 목록
    lm = re.match(r'^(\s*)[-*]\s+(.*)$', line)
    if lm:
        depth = len(lm.group(1)) // 2
        p = doc.add_paragraph(style='List Bullet' if depth == 0 else 'List Bullet 2')
        emit_runs(p, lm.group(2))
        for r in p.runs:
            r.font.size = Pt(10)
        p.paragraph_format.space_after = Pt(2)
        i += 1; continue

    nm = re.match(r'^(\s*)(\d+)\.\s+(.*)$', line)
    if nm:
        p = doc.add_paragraph(style='List Number')
        emit_runs(p, nm.group(3))
        for r in p.runs:
            r.font.size = Pt(10)
        p.paragraph_format.space_after = Pt(2)
        i += 1; continue

    p = doc.add_paragraph()
    emit_runs(p, stripped)
    if in_summary:
        for r in p.runs:
            r.font.size = Pt(9.5)
        p.paragraph_format.line_spacing = 1.1
        p.paragraph_format.space_after = Pt(4)
    else:
        p.paragraph_format.space_after = Pt(6)
    i += 1

doc.save(dst)
print('생성:', dst)
