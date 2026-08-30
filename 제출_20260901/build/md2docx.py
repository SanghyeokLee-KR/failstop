# 기획안 마크다운을 Word 문서로 변환한다.
# pandoc 이 없어 직접 변환한다. 표 93개·제목 92개라 수작업은 불가능하다.
import io, re, sys
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

KO = '맑은 고딕'
INK = RGBColor(0x1B, 0x24, 0x29)
MUTE = RGBColor(0x5A, 0x6B, 0x75)

src, dst = sys.argv[1], sys.argv[2]
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

    if not stripped:
        i += 1; continue

    if stripped == '---':
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
