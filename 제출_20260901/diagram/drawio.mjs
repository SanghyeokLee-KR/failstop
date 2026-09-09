// 도면 1·2·3 의 draw.io 판. 내용은 lib/content.mjs 하나에서 읽는다.
//
// 왜 별도 형식인가. SVG 판은 제출본에 그대로 박히는 완성물이고 draw.io 판은 손으로
// 고칠 수 있는 편집본이다. 발표 준비에서 문구를 바꾸거나 배치를 옮길 때 생성기를
// 고치는 것보다 빠르다. 두 판이 같은 내용을 말하는 것은 content.mjs 가 보장한다.
//
// 좌표는 SVG 판과 맞추지 않는다. draw.io 는 셀 단위 편집이라 여백 규칙이 다르고,
// 억지로 맞추면 편집할 때마다 어긋난다. 같아야 하는 것은 내용이지 픽셀이 아니다.
//
// 사용: node drawio.mjs   -> 시스템_아키텍처.drawio · 장애대응_흐름.drawio · 계층판별_트리.drawio

import { readFileSync, writeFileSync } from 'node:fs'
import sharp from 'sharp'
import {
  TIER_COLOR, LAYER_COLOR,
  FLOW_KPI, STATIONS, SEV, SEV_NOTE, TIERS, TIER_NOTE, NOAUTO, LEDGER_WHY, SAFE_COND,
  TRIAGE_KPI, TRIAGE_NODES, TRIAGE_TABLE, INJECT_TOOLS, TRIAGE_CASES, TRIAGE_STATUS, FIG_META,
  ARCH_KPI, ARCH_EDGE, ARCH_STATIONS, ARCH_STATION_LEGEND, ARCH_AZ, ARCH_COTENANT,
  ARCH_VERIFY, ARCH_LIMITS, ARCH_FILM, ARCH_COST, ARCH_FOOT,
} from './lib/content.mjs'

// 아이콘은 파일 참조가 아니라 base64 로 박는다. draw.io 파일을 옮기거나 남에게 주면
// 상대경로 참조는 깨지는데 편집본은 그렇게 돌아다니라고 만든 것이다.
// 원본이 256~300px 인데 표시 크기가 28px 안팎이라 96px 로 줄여 넣는다.
// 확대해도 선명한 3배수를 유지하면서 파일이 271KB 에서 104KB 로 내려간다.
const ICON_PX = 96
const ICON_KEYS = [
  'users', 'route53', 'cloudfront', 's3', 'alb', 'tg', 'ssm',
  'spring', 'pg', 'prom', 'grafana', 'linux',
]
const ICONS = {}
for (const k of ICON_KEYS) {
  const buf = await sharp(readFileSync(new URL(`icons/${k}.png`, import.meta.url)))
    .resize(ICON_PX, ICON_PX, { fit: 'inside' })
    .png({ compressionLevel: 9 })
    .toBuffer()
  ICONS[k] = buf.toString('base64')
}

const INK = '#16191F'
const BODY = '#3C4750'
const MUTE = '#5C6B75'
const LINE = '#D0D7DC'

// XML 특수문자. draw.io 는 value 를 HTML 로 읽으므로 &lt; 까지 막아야 한다
const esc = (s) => String(s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

class Dio {
  constructor() { this.cells = []; this.id = 2 }
  next() { return String(this.id++) }

  /** 도형 하나. style 은 draw.io 문법 그대로 넘긴다 */
  cell(value, style, x, y, w, h) {
    const id = this.next()
    this.cells.push(
      `        <mxCell id="${id}" value="${esc(value)}" style="${style}" vertex="1" parent="1">\n` +
      `          <mxGeometry x="${x}" y="${y}" width="${w}" height="${h}" as="geometry"/>\n` +
      `        </mxCell>`)
    return id
  }

  /** 글자만. 테두리 없는 라벨 */
  txt(t, x, y, w, h, { size = 12, color = BODY, bold = false, align = 'left' } = {}) {
    return this.cell(t,
      `text;html=1;strokeColor=none;fillColor=none;align=${align};verticalAlign=middle;` +
      `whiteSpace=wrap;fontSize=${size};fontColor=${color};fontStyle=${bold ? 1 : 0};fontFamily=Malgun Gothic;`,
      x, y, w, h)
  }

  /** 상자. 좌측 색띠가 필요하면 accent 를 준다 */
  box(x, y, w, h, { stroke = LINE, fill = 'none', dashed = false, sw = 1 } = {}) {
    return this.cell('',
      `rounded=0;html=1;fillColor=${fill};strokeColor=${stroke};strokeWidth=${sw};` +
      `dashed=${dashed ? 1 : 0};`, x, y, w, h)
  }

  /** 색 배지. 등급 라벨에 쓴다 */
  badge(t, x, y, w, h, color) {
    return this.cell(t,
      `rounded=1;arcSize=20;html=1;fillColor=${color};strokeColor=none;fontColor=#FFFFFF;` +
      `fontSize=11;fontStyle=1;fontFamily=Malgun Gothic;verticalAlign=middle;align=center;`,
      x, y, w, h)
  }

  /** 아이콘. 없는 키를 조용히 넘기지 않고 던진다. 빠진 아이콘은 눈으로 안 보인다 */
  img(key, x, y, size) {
    const b64 = ICONS[key]
    if (!b64) throw new Error(`아이콘 없음: ${key}`)
    return this.cell('',
      'shape=image;html=1;imageAspect=1;aspect=fixed;labelBackgroundColor=none;' +
      `image=data:image/png,${b64}`, x, y, size, size)
  }

  /** 화살표. 두 셀 사이가 아니라 좌표로 긋는다. 편집할 때 붙였다 떼기 쉽다 */
  arrow(x1, y1, x2, y2, { color = INK, dashed = false, head = true } = {}) {
    const id = this.next()
    this.cells.push(
      `        <mxCell id="${id}" style="edgeStyle=orthogonalEdgeStyle;html=1;rounded=0;` +
      `strokeColor=${color};strokeWidth=1.4;dashed=${dashed ? 1 : 0};` +
      `endArrow=${head ? 'blockThin' : 'none'};endFill=1;" edge="1" parent="1">\n` +
      `          <mxGeometry relative="1" as="geometry">\n` +
      `            <mxPoint x="${x1}" y="${y1}" as="sourcePoint"/>\n` +
      `            <mxPoint x="${x2}" y="${y2}" as="targetPoint"/>\n` +
      `          </mxGeometry>\n        </mxCell>`)
    return id
  }

  toString(name, w, h) {
    return `<mxfile host="app.diagrams.net" type="device">
  <diagram name="${esc(name)}">
    <mxGraphModel dx="${w}" dy="${h}" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="${w}" pageHeight="${h}" math="0" shadow="0">
      <root>
        <mxCell id="0"/>
        <mxCell id="1" parent="0"/>
${this.cells.join('\n')}
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
`
  }
}

/** 제목 밴드. 두 도면이 같은 형태를 쓴다 */
function header(d, title, sub, kpi, W) {
  d.txt(title, 20, 14, 900, 32, { size: 22, color: INK, bold: true })
  d.txt(sub, 20, 44, 1100, 20, { size: 11, color: MUTE })
  kpi.forEach(([k, v, bad], i) => {
    const x = W - 40 - (kpi.length - i) * 150
    d.txt(k, x, 16, 140, 16, { size: 9.5, color: MUTE })
    d.txt(v, x, 34, 140, 22, { size: 15, color: bad ? TIER_COLOR.GAP : INK, bold: true })
  })
  d.cell('', `line;html=1;strokeColor=${LINE};strokeWidth=1;`, 20, 74, W - 40, 8)
}

/* ===================== 도면 1 ===================== */

// 상태 4색. arch_aws.mjs 와 같은 값이다. 이 넷 밖의 색은 쓰지 않는다
const A_OK = '#0E7C66'
const A_FAIL = '#C0392B'
const A_TRANS = '#B8730A'
const A_IDLE = '#98A2A8'
const A_CBY = { INK, FAIL: A_FAIL, OK: A_OK, IDLE: A_IDLE, TRANS: A_TRANS, MUTE }

function serviceArchitecture() {
  const W = 2000, H = 1560
  const d = new Dio()
  const M = FIG_META.arch

  // 헤더. KPI 는 값과 측정 범위를 함께 적는다. 범위 없는 숫자는 이 도면에 없다
  d.txt(`${M.no} · ${M.title}`, 20, 14, 900, 32, { size: 22, color: INK, bold: true })
  d.txt(M.sub, 20, 44, 900, 20, { size: 11, color: MUTE })
  ARCH_KPI.forEach(([k, v, ev, cn], i) => {
    const x = W - 40 - (ARCH_KPI.length - i) * 150
    d.txt(k, x, 12, 145, 14, { size: 9, color: MUTE })
    d.txt(v, x, 28, 145, 20, { size: 14, color: A_CBY[cn], bold: true })
    d.txt(ev, x, 50, 145, 14, { size: 8.5, color: MUTE })
  })
  d.cell('', `line;html=1;strokeColor=${LINE};strokeWidth=1;`, 20, 74, W - 40, 8)

  /* --- AWS 영역 --- */
  d.box(20, 92, W - 40, 748, { stroke: '#8A9299', sw: 1.4 })
  d.txt('AWS Cloud', 36, 100, 140, 20, { size: 13, color: INK, bold: true })
  d.txt('Region ap-northeast-2', 130, 100, 240, 20, { size: 10, color: MUTE })

  /* --- 외부 진입 --- */
  d.txt('External Access', 36, 126, 160, 16, { size: 9.5, color: MUTE, bold: true })
  const EW = 180, EGAP = 34, EH = 48, EY = 152
  ARCH_EDGE.chain.forEach(([ic, label, note], i) => {
    const x = 40 + i * (EW + EGAP)
    d.box(x, EY, EW, EH, { fill: '#FFFFFF' })
    d.img(ic, x + 12, EY + 10, 28)
    d.txt(label, x + 48, EY, EW - 60, EH, { size: 12, color: INK, bold: true })
    if (note) d.txt(note, x, EY + EH + 2, EW, 14, { size: 8.5, color: MUTE, align: 'center' })
    if (i < ARCH_EDGE.chain.length - 1) d.arrow(x + EW, EY + EH / 2, x + EW + EGAP, EY + EH / 2)
  })
  // CloudFront 에서 정적과 동적이 갈린다. 이 분기가 상단의 요지다
  const FX = 40 + 3 * (EW + EGAP)
  ARCH_EDGE.fork.forEach(([ic, label, sub, note], i) => {
    const y = EY - 34 + i * 78
    d.box(FX, y, 300, 48, { fill: '#FFFFFF' })
    d.img(ic, FX + 12, y + 10, 28)
    d.txt(label, FX + 48, y + 4, 244, 20, { size: 11.5, color: INK, bold: true })
    d.txt(sub, FX + 48, y + 24, 244, 16, { size: 9, color: MUTE })
    if (note) d.txt(note, FX + 306, y + 14, 170, 16, { size: 8.5, color: MUTE })
    d.arrow(FX - 26, EY + EH / 2, FX - 26, y + 23, { head: false })
    d.arrow(FX - 26, y + 23, FX, y + 23)
  })

  // 계약 각주 둘. 크기로 눌러 둔다
  ARCH_EDGE.contracts.forEach(([ic, title, line1, line2], i) => {
    const x = 1030 + i * 470
    d.box(x, 138, 450, 78)
    d.img(ic, x + 14, 148, 24)
    d.txt(title, x + 46, 144, 390, 18, { size: 10.5, color: INK, bold: true })
    d.txt(line1, x + 46, 164, 390, 16, { size: 9.5, color: BODY })
    d.txt(line2, x + 14, 184, 424, 28, { size: 8.8, color: MUTE })
  })
  d.badge(ARCH_EDGE.probeBadge, 1030, 232, 250, 20, A_IDLE)

  /* --- 가용영역 둘 --- */
  const AY = [258, 618]
  ARCH_AZ.forEach((az, k) => {
    const y = AY[k]
    const tone = az.tone === 'OK' ? A_OK : A_IDLE
    d.box(36, y, W - 72, k === 0 ? 336 : 210, { stroke: tone, sw: 1.3, dashed: k === 1 })
    d.txt(az.name, 52, y + 8, 300, 20, { size: 12.5, color: tone, bold: true })
    d.txt(az.note, 300, y + 8, 1200, 20, { size: 9.5, color: MUTE })

    // 같은 것은 같은 x 에 둔다. app 과 db 가 두 평면에서 수직으로 맞는다
    const put = (spec, x) => {
      const [ic, name, sub, badgeTxt, badgeCol, extra] = spec
      d.box(x, y + 34, 330, extra ? 64 : 48, { fill: '#FFFFFF' })
      d.img(ic, x + 12, y + 42, 26)
      d.txt(name, x + 48, y + 38, 264, 18, { size: 12, color: INK, bold: true })
      d.txt(sub, x + 48, y + 56, 274, 16, { size: 9, color: MUTE })
      if (extra) d.txt(extra, x + 48, y + 76, 274, 16, { size: 8.6, color: A_TRANS })
      if (badgeTxt) d.badge(badgeTxt, x + 338, y + 46, 128, 20, A_CBY[badgeCol])
    }
    put(az.app, 52)
    put(az.db, 1180)
  })

  // 복제. 청록 점선이고 범례와 같은 스타일이다.
  // x 는 정거장 틈(1288~1300)의 중앙이다. 상자 안을 지나면 등뼈를 관통해 읽을 수 없다.
  // 시작과 끝을 상자 경계에 두는 이유는 도형 안에서 출발하면 draw.io 직교 라우터가 꺾기 때문이다
  d.arrow(1294, 340, 1294, 652, { color: A_OK, dashed: true, head: false })
  d.txt('streaming replication', 1304, 348, 220, 16, { size: 9.5, color: A_OK, bold: true })

  /* --- 거래 쓰기 경로 --- */
  const SY = 376, SW = 300, SGAP = 12
  d.txt('Transaction Write Path', 52, SY, 200, 18, { size: 10.5, color: INK, bold: true })
  d.txt('주문 하나가 밟는 여섯 정거장. 전부 리더 안이다', 236, SY, 500, 18, { size: 9, color: MUTE })
  ARCH_STATIONS.forEach(([n, title, sub, kind], i) => {
    const x = 52 + i * (SW + SGAP)
    const y = SY + 24
    // 지속성과 파생을 색이 아니라 형태로 가른다
    const fill = kind === 'dur' ? '#F2F5F7' : '#FFFFFF'
    d.box(x, y, SW, 72, {
      fill,
      dashed: kind === 'derived',
      sw: kind === 'sole' ? 1.8 : 1,
      stroke: kind === 'sole' ? INK : LINE,
    })
    d.badge(n, x + 12, y + 10, 22, 22, INK)
    d.txt(title, x + 42, y + 10, 190, 22, { size: 13, color: INK, bold: true })
    d.txt(sub, x + 12, y + 40, SW - 100, 18, { size: 9.5, color: BODY })
    d.txt(kind === 'dur' || kind === 'derived' ? 'PostgreSQL' : 'app-1',
      x + SW - 96, y + 40, 84, 18, { size: 8.8, color: MUTE, align: 'right' })
    if (i < ARCH_STATIONS.length - 1) d.arrow(x + SW, y + 36, x + SW + SGAP, y + 36)
  })
  d.txt(ARCH_STATION_LEGEND, 52, SY + 106, 1800, 18, { size: 9.5, color: MUTE })

  /* --- AZ-c 동거 구성요소. 배선 상태를 라벨로 함께 둔다 --- */
  const CY2 = AY[1] + 116
  const COL = [52, 470]
  ARCH_COTENANT.forEach(([icons, title, sub, gap], i) => {
    const x = COL[i]
    icons.forEach((ic, j) => d.img(ic, x + j * 30, CY2 + 4, 26))
    const tx = x + (icons.length ? icons.length * 30 + 8 : 0)
    d.txt(title, tx, CY2, 330, 18, { size: 11, color: INK, bold: true })
    d.txt(sub, tx, CY2 + 20, 380, 16, { size: 9, color: MUTE })
    d.badge(gap, tx, CY2 + 40, i === 0 ? 268 : 150, 20, A_IDLE)
  })
  d.txt('AZ-a 를 통째로 잃으면 등뼈 1~6 이 이 평면에서 다시 선다.', 900, CY2, 1050, 18,
    { size: 10.5, color: INK, bold: true })
  d.txt('승격 소요는 미측정이다.', 900, CY2 + 20, 1050, 16, { size: 9.5, color: BODY })

  /* --- 온프레미스 검증면 --- */
  const VY = 860
  d.box(20, VY, W - 40, 116, { stroke: MUTE, sw: 1.2, dashed: true })
  d.txt('온프레미스 검증면', 36, VY + 8, 180, 20, { size: 12.5, color: MUTE, bold: true })
  d.txt(ARCH_VERIFY.note, 180, VY + 8, 900, 20, { size: 9.5, color: MUTE })
  d.img(ARCH_VERIFY.icon, 40, VY + 44, 26)
  ARCH_VERIFY.tools.forEach(([l, tool, det, kind], i) => {
    const x = 86 + i * 300
    d.txt(l, x, VY + 36, 30, 18, { size: 10.5, color: A_FAIL, bold: true })
    d.txt(tool, x + 30, VY + 36, 200, 18, { size: 11, color: INK, bold: true })
    d.txt(det, x, VY + 56, 270, 16, { size: 9, color: BODY })
    d.txt(kind, x, VY + 74, 270, 16, { size: 8.8, color: MUTE })
  })
  d.cell('', `line;html=1;strokeColor=${LINE};strokeWidth=1;`, 960, VY + 32, 0, 60)
  ARCH_VERIFY.why.forEach((t, i) => d.txt(t, 984, VY + 34 + i * 18, 990, 16, { size: 9.5, color: BODY }))
  d.txt(ARCH_VERIFY.motto, 984, VY + 74, 990, 16, { size: 9.5, color: INK, bold: true })

  /* --- 한계와 메타. 한계는 경고문이 아니라 검토 자료다 --- */
  const LY = 992
  d.box(20, LY, 1360, 124, { stroke: MUTE, sw: 1.1 })
  d.txt('Known Limitations', 36, LY + 8, 200, 20, { size: 12.5, color: MUTE, bold: true })
  d.txt('숨기지 않는다. 이 목록이 남은 작업의 정의다', 190, LY + 8, 500, 20, { size: 9.5, color: MUTE })
  ARCH_LIMITS.forEach((t, i) => {
    d.txt(String(i + 1), 44, LY + 34 + i * 20, 16, 16, { size: 9, color: MUTE, bold: true })
    d.txt(t, 64, LY + 34 + i * 20, 1300, 16, { size: 9.5, color: BODY })
  })
  d.box(1396, LY, 584, 124)
  d.txt(`${M.no} · Metadata`, 1412, LY + 8, 300, 18, { size: 10, color: MUTE, bold: true })
  M.meta.forEach((t, i) => d.txt(t, 1412, LY + 30 + i * 18, 550, 16, { size: 9, color: MUTE }))

  /* --- 상태 전이 --- */
  const FY = 1134
  d.txt('상태 전이', 20, FY, 100, 20, { size: 13, color: INK, bold: true })
  d.txt('같은 배치, 다른 상태 · 장애 도메인이 어디서 끊기는지 본다', 100, FY, 700, 20, { size: 10, color: MUTE })
  const PW = 476, PGAP = 20, PH = 214
  ARCH_FILM.forEach(([label, tone, aRow, cRow, note], k) => {
    const px = 20 + k * (PW + PGAP)
    const py = FY + 26
    const tc = tone === 'ok' ? A_OK : A_TRANS
    d.box(px, py, PW, PH)
    d.cell('', `rounded=0;html=1;fillColor=${tc};strokeColor=none;`, px, py, PW, 3)
    d.txt(label, px + 14, py + 12, 220, 22, { size: 13, color: INK, bold: true })
    d.txt(note, px + PW - 314, py + 12, 300, 22, { size: 9.5, color: tc, bold: true, align: 'right' })
    // 두 AZ 를 같은 기하로 반복한다. 좌표가 패널마다 같아야 상태 차이만 보인다
    const row = (ry, name, cells, dashed) => {
      d.box(px + 14, ry, PW - 28, 74, { dashed })
      d.txt(name, px + 26, ry + 8, 80, 16, { size: 9.5, color: MUTE, bold: true })
      cells.forEach(([lbl, st], j) => {
        const cx = px + 40 + j * 200
        const col = st === 'ok' ? A_OK : st === 'fail' ? A_FAIL : st === 'trans' ? A_TRANS : A_IDLE
        const style = st === 'ok' || st === 'trans'
          ? `ellipse;html=1;fillColor=${col};strokeColor=none;`
          : `ellipse;html=1;fillColor=#FFFFFF;strokeColor=${col};strokeWidth=1.8;dashed=${st === 'idle' ? 1 : 0};`
        d.cell('', style, cx, ry + 34, 20, 20)
        d.txt(lbl, cx + 26, ry + 34, 150, 20, { size: 10, color: col, bold: st !== 'idle' })
      })
    }
    row(py + 40, 'AZ-a', aRow, false)
    row(py + 128, 'AZ-c', cRow, true)
  })

  /* --- 비용과 각주 --- */
  const CY = 1390
  d.cell('', `line;html=1;strokeColor=${LINE};strokeWidth=1;`, 20, CY, W - 40, 8)
  d.txt('운영 상태와 비용', 20, CY + 14, 160, 20, { size: 11, color: INK, bold: true })
  ARCH_COST.forEach(([nm, mode, desc, price], i) => {
    const y = CY + 40 + i * 22
    d.txt(nm, 20, y, 110, 18, { size: 10, color: INK, bold: true })
    d.txt(mode, 132, y, 70, 18, { size: 9.5, color: MUTE })
    d.txt(desc, 206, y, 640, 18, { size: 9.5, color: BODY })
    d.txt(price, 850, y, 170, 18, { size: 10, color: INK, bold: true, align: 'right' })
  })
  d.txt('범례', 1120, CY + 14, 80, 20, { size: 11, color: INK, bold: true })
  const LEG = [['정상 · 실측', A_OK], ['장애 · SPOF', A_FAIL], ['복구 중', A_TRANS], ['비활성 · 계획', A_IDLE]]
  LEG.forEach(([nm, col], i) => {
    const x = 1120 + i * 220
    d.cell('', `ellipse;html=1;fillColor=${col};strokeColor=none;`, x, CY + 42, 14, 14)
    d.txt(nm, x + 22, CY + 40, 180, 18, { size: 9.5, color: BODY })
  })
  d.txt(ARCH_FOOT, 20, CY + 114, 1960, 18, { size: 9.5, color: MUTE })

  return { xml: d.toString('Service Architecture', W, H), out: '시스템_아키텍처.drawio', W, H }
}

/* ===================== 도면 2 ===================== */
function incidentFlow() {
  const W = 1900, H = 1080
  const d = new Dio()
  const MF = FIG_META.flow
  header(d, `${MF.no} · ${MF.title}`,
    `${MF.sub}  ·  계획서 7.1 · 7.2 · 7.5 · 10.2 에서 파생. 색은 손이 닿는 정도를 뜻한다`,
    FLOW_KPI, W)

  // 운영 루프
  d.txt('운영 루프', 20, 96, 120, 20, { size: 13, color: INK, bold: true })
  d.txt('시작과 끝이 같은 정의를 쓴다. 복구 판정이 정상 상태로 돌아왔는가이므로 정상 상태 정의가 곧 복구 기준이다',
    110, 96, 1000, 20, { size: 10, color: MUTE })

  const SW = 248, SH = 118, SGAP = 10
  STATIONS.forEach((st, i) => {
    const x = 20 + i * (SW + SGAP)
    const y = 124
    const col = TIER_COLOR[st.tier]
    d.box(x, y, SW, SH)
    d.cell('', `rounded=0;html=1;fillColor=${col};strokeColor=none;`, x, y, 4, SH)
    d.badge(st.n, x + 14, y + 12, 22, 22, INK)
    d.txt(st.t, x + 44, y + 12, 180, 22, { size: 13.5, color: INK, bold: true })
    d.txt(st.s, x + 14, y + 42, SW - 28, 18, { size: 10, color: BODY })
    d.txt(st.tl, x + 14, y + 62, SW - 28, 16, { size: 10, color: col, bold: true })
    if (st.gap) {
      d.cell(st.gap,
        `rounded=0;html=1;fillColor=#FDF2F1;strokeColor=${TIER_COLOR.GAP};strokeWidth=0.9;` +
        `fontSize=9.5;fontColor=${TIER_COLOR.GAP};fontStyle=1;fontFamily=Malgun Gothic;align=left;spacingLeft=6;`,
        x + 14, y + 84, SW - 28, 20)
    }
    if (i < STATIONS.length - 1) d.arrow(x + SW, y + SH / 2, x + SW + SGAP, y + SH / 2)
  })
  // 회귀선
  const lastX = 20 + 6 * (SW + SGAP) + SW / 2
  d.arrow(lastX, 242, lastX, 268, { color: MUTE, dashed: true, head: false })
  d.arrow(lastX, 268, 20 + SW / 2, 268, { color: MUTE, dashed: true, head: false })
  d.arrow(20 + SW / 2, 268, 20 + SW / 2, 244, { color: MUTE, dashed: true })
  d.txt('정상 상태 재확인 후 루프로 복귀', 20 + SW / 2 + 16, 252, 320, 16, { size: 9.5, color: MUTE })

  // 심각도
  const GY = 300
  d.txt('심각도와 대응 시점', 20, GY, 180, 20, { size: 13, color: INK, bold: true })
  d.txt('출처 harness/prometheus/rules/exchange-alerts.yml 의 severity 라벨 · 등급과 대응 시점은 정본 미확정', 180, GY, 740, 20, { size: 10, color: MUTE })
  d.box(20, GY + 24, 910, 156)
  SEV.forEach(([lv, when, what, col], i) => {
    const y = GY + 38 + i * 48
    d.badge(lv, 36, y, 38, 24, TIER_COLOR[col])
    d.txt(when, 88, y, 150, 24, { size: 12, color: INK, bold: true })
    d.txt(what, 244, y, 670, 24, { size: 10, color: BODY })
  })
  d.txt(SEV_NOTE, 20, GY + 186, 910, 18, { size: 10, color: TIER_COLOR.GAP, bold: true })

  // 자동화 계층
  d.txt('자동화 계층', 960, GY, 140, 20, { size: 13, color: INK, bold: true })
  d.txt('계획서 7.5 스케일링 실행 주체 · 사람 손이 어디서 끊기고 어디서 붙는가', 1060, GY, 420, 20, { size: 10, color: MUTE })
  d.box(960, GY + 24, 920, 156)
  TIERS.forEach(([lv, nm, how, col, items], i) => {
    const y = GY + 38 + i * 36
    d.badge(lv, 976, y, 32, 22, TIER_COLOR[col])
    d.txt(nm, 1018, y, 90, 22, { size: 11.5, color: INK, bold: true })
    d.txt(how, 1110, y, 90, 22, { size: 10, color: TIER_COLOR[col], bold: true })
    d.txt(items, 1206, y, 660, 22, { size: 9.5, color: BODY })
  })
  d.txt(TIER_NOTE, 960, GY + 186, 920, 18, { size: 10, color: MUTE })

  // 일부러 자동화하지 않은 것
  const NY = 520
  d.txt('일부러 자동화하지 않은 것', 20, NY, 240, 20, { size: 13, color: INK, bold: true })
  d.txt('무분별한 자동화는 사고를 키운다. 자동화의 안전 조건을 만족하지 못하는 것을 이름으로 적는다',
    250, NY, 900, 20, { size: 10, color: MUTE })
  d.box(20, NY + 24, 1860, 148)
  NOAUTO.forEach(([what, why], i) => {
    const y = NY + 38 + i * 26
    d.txt('X', 36, y, 20, 22, { size: 10, color: TIER_COLOR.HAND, bold: true, align: 'center' })
    d.txt(what, 62, y, 330, 22, { size: 11, color: INK, bold: true })
    d.txt(why, 400, y, 1460, 22, { size: 10, color: BODY })
  })

  // 원장과 안전 조건
  const DY = 720
  d.box(20, DY, 910, 130, { stroke: TIER_COLOR.HAND, sw: 1.4 })
  d.txt('원장 불일치는 왜 자동으로 고치지 않는가', 40, DY + 14, 600, 24, { size: 13, color: INK, bold: true })
  LEDGER_WHY.forEach((t, i) => d.txt(t, 40, DY + 44 + i * 22, 860, 20, { size: 11, color: BODY }))
  d.txt('계약 위반은 조용히 보정하기보다 명확하게 실패시키는 것을 우선한다',
    40, DY + 96, 860, 20, { size: 10, color: MUTE, bold: true })

  d.box(960, DY, 920, 130)
  d.txt('자동화의 안전 조건 셋', 980, DY + 14, 400, 24, { size: 13, color: INK, bold: true })
  SAFE_COND.forEach((t, i) => {
    d.txt('·', 980, DY + 44 + i * 22, 12, 20, { size: 14, color: TIER_COLOR.AUTO, bold: true })
    d.txt(t, 996, DY + 44 + i * 22, 860, 20, { size: 11, color: BODY })
  })
  d.txt('셋 중 하나라도 아니면 자동화하지 않고 알림까지만 자동으로 둔다',
    980, DY + 108, 860, 18, { size: 10, color: MUTE, bold: true })

  // 범례
  d.cell('', `line;html=1;strokeColor=${LINE};strokeWidth=1;`, 20, 880, W - 40, 8)
  d.txt('범례', 20, 894, 60, 18, { size: 10.5, color: INK, bold: true })
  d.txt('색은 손이 닿는 정도다. 도면 1의 색은 노드 상태를 뜻하므로 두 도면에서 같은 색이 다른 축이다.',
    20, 914, 900, 18, { size: 9.5, color: MUTE })
  ;[['자동. 개입 0', 'AUTO'], ['승인 필요', 'APPR'], ['사람이 판단', 'HAND'], ['현재 결손', 'GAP']]
    .forEach(([nm, k], i) => {
      const x = 660 + i * 200
      d.cell('', `rounded=0;html=1;fillColor=${TIER_COLOR[k]};strokeColor=none;`, x, 896, 24, 12)
      d.txt(nm, x + 32, 890, 160, 22, { size: 9.5, color: BODY })
    })
  d.txt('이 도면은 설계다. 5단계와 6단계 외에는 아직 실행 기록이 없다. 알람 통지 경로와 계층 프로브와 런북이 남은 작업이고 그 셋이 1번 2번 3번 4번 정거장을 막고 있다.',
    20, 940, 1600, 18, { size: 9.5, color: MUTE })

  d.txt(`${MF.no} · Metadata   ${MF.meta.join('   ')}`, 20, 964, 1400, 18, { size: 9, color: MUTE })

  return { xml: d.toString('장애 대응 흐름', W, H), out: '장애대응_흐름.drawio', W, H }
}

/* ===================== 도면 3 ===================== */
function layerTriage() {
  const W = 1900, H = 1100
  const d = new Dio()
  const MT = FIG_META.triage
  header(d, `${MT.no} · ${MT.title}`,
    `${MT.sub}  ·  ${MT.goal}`,
    TRIAGE_KPI, W)

  // 트리
  d.txt('소거 순서', 20, 96, 100, 20, { size: 13, color: INK, bold: true })
  d.txt('위에서 아래로 내려가며 후보를 지운다. 먼저 끊기는 프로브가 계층을 지목한다',
    108, 96, 700, 20, { size: 10, color: MUTE })

  d.box(20, 124, 280, 58, { stroke: INK, sw: 1.4 })
  d.txt('증상 진입', 38, 134, 200, 22, { size: 12.5, color: INK, bold: true })
  d.txt('API 지연 · 오류 증가 · 체결 감소', 38, 156, 240, 18, { size: 10, color: BODY })
  d.arrow(160, 182, 160, 206)

  TRIAGE_NODES.forEach((n, i) => {
    const y = 206 + i * 118
    const col = LAYER_COLOR[n.col]
    d.box(20, y, 280, 58, { stroke: MUTE })
    d.txt(n.q, 38, y + 10, 250, 22, { size: 12, color: INK, bold: true })
    d.txt('예 이면 다음 단계로', 38, y + 32, 250, 18, { size: 9.5, color: MUTE })

    d.arrow(300, y + 29, 420, y + 29, { color: col })
    d.txt(n.no, 320, y + 8, 90, 18, { size: 9.5, color: col, bold: true })

    d.box(420, y, 230, 58, { stroke: col, sw: 1.5, fill: '#FCFDFD' })
    d.cell('', `rounded=0;html=1;fillColor=${col};strokeColor=none;`, 420, y, 4, 58)
    d.txt(n.layer, 440, y + 10, 200, 22, { size: 12.5, color: col, bold: true })
    d.txt(n.own + ' 담당', 440, y + 32, 200, 18, { size: 9.5, color: MUTE })

    d.txt(n.cause, 670, y + 10, 380, 22, { size: 10.5, color: BODY })
    d.txt('RUNBOOK 카드로 이동', 670, y + 32, 300, 18, { size: 9.5, color: MUTE })

    if (i < TRIAGE_NODES.length - 1) d.arrow(160, y + 58, 160, y + 118)
  })

  const allOkY = 206 + 4 * 118
  d.arrow(160, allOkY - 60, 160, allOkY - 24)
  d.box(20, allOkY - 24, 630, 56, { dashed: true })
  d.txt('프로브 전부 정상인데 지연만 높다', 38, allOkY - 16, 400, 22, { size: 12, color: INK, bold: true })
  d.txt('RTT 와 재전송을 확인하고 정상이면 수요 축을 본다. 요청 유입이 줄었는지, 봇이 멈췄는지',
    38, allOkY + 6, 600, 18, { size: 10, color: BODY })

  // 판정표
  d.txt('조합 판정표', 1060, 96, 130, 20, { size: 13, color: INK, bold: true })
  d.txt('실무에서 읽는 것은 이 표다', 1170, 96, 400, 20, { size: 10, color: MUTE })
  d.box(1060, 124, 820, 212)
  const CW = [84, 84, 100, 136]
  ;['ICMP', 'TCP', 'HTTP', '확정 계층', '먼저 볼 것'].forEach((h, i) => {
    const x = 1076 + CW.slice(0, i).reduce((a, b) => a + b, 0)
    d.txt(h, x, 134, 130, 18, { size: 10, color: MUTE, bold: true })
  })
  TRIAGE_TABLE.forEach((r, i) => {
    const y = 160 + i * 34
    ;[r[0], r[1], r[2]].forEach((v, j) => {
      const x = 1076 + CW.slice(0, j).reduce((a, b) => a + b, 0)
      const ok = v === '성공'
      d.cell('', `ellipse;html=1;fillColor=${ok ? '#0E7C66' : '#C0392B'};strokeColor=none;`, x, y + 6, 9, 9)
      d.txt(v, x + 14, y, 70, 22, { size: 10, color: BODY })
    })
    const xL = 1076 + CW.slice(0, 3).reduce((a, b) => a + b, 0)
    d.txt(r[3], xL, y, 130, 22, { size: 11, color: LAYER_COLOR[r[4]], bold: true })
    d.txt(r[5], xL + CW[3], y, 420, 22, { size: 10, color: BODY })
  })

  // 주입 도구
  d.txt('주입 도구와 계층', 1060, 356, 160, 20, { size: 13, color: INK, bold: true })
  d.txt('판별이 맞는지는 아는 계층에 넣어 확인한다', 1190, 356, 420, 20, { size: 10, color: MUTE })
  d.box(1060, 384, 820, 120)
  INJECT_TOOLS.forEach(([lv, tool, how, col, note], i) => {
    const y = 398 + i * 34
    d.badge(lv, 1076, y, 28, 22, LAYER_COLOR[col])
    d.txt(tool, 1114, y, 200, 22, { size: 11, color: INK, bold: true })
    d.txt(how, 1320, y, 210, 22, { size: 10, color: BODY })
    d.txt(note, 1540, y, 330, 22, { size: 9.5, color: MUTE })
  })

  // 실측 사례
  d.txt('실측 사례', 1060, 524, 110, 20, { size: 13, color: INK, bold: true })
  d.txt('둘 다 화면으로는 애플리케이션 장애와 구분되지 않았다', 1150, 524, 500, 20, { size: 10, color: MUTE })
  d.box(1060, 552, 820, 148, { stroke: LAYER_COLOR.L7, sw: 1.3, fill: '#FCFDFD' })
  let cy = 566
  TRIAGE_CASES.forEach((c) => {
    d.txt(c.title, 1078, cy, 600, 22, { size: 12, color: INK, bold: true })
    cy += 24
    d.txt(c.before, 1078, cy, c.after ? 500 : 780, 20, { size: 10, color: TIER_COLOR.GAP })
    if (c.after) d.txt(c.after, 1590, cy, 280, 20, { size: 10, color: LAYER_COLOR.L7, bold: true })
    cy += 22
    if (c.note) { d.txt(c.note, 1078, cy, 780, 20, { size: 9.5, color: MUTE }); cy += 20 }
    cy += 6
  })

  // 현재 상태와 메타. 경고문이 아니라 검토 상태다
  d.box(20, 740, 1340, 130)
  d.txt(TRIAGE_STATUS.title, 40, 750, 400, 22, { size: 13, color: INK, bold: true })
  TRIAGE_STATUS.items.forEach(([what, st, det], i) => {
    const y = 776 + i * 20
    const bad = st.includes('BLOCKED') || st.includes('NOT RUN')
    d.txt(what, 40, y, 220, 18, { size: 10, color: INK, bold: true })
    d.txt(st, 270, y, 150, 18, { size: 10, color: bad ? TIER_COLOR.GAP : MUTE, bold: true })
    d.txt(det, 430, y, 900, 18, { size: 9.5, color: BODY })
  })
  d.txt(TRIAGE_STATUS.foot, 40, 856, 1300, 18, { size: 9.5, color: MUTE, bold: true })

  d.box(1380, 740, 500, 130)
  d.txt(`${MT.no} · Metadata`, 1398, 750, 300, 20, { size: 10, color: MUTE, bold: true })
  MT.meta.forEach((t, i) => d.txt(t, 1398, 774 + i * 20, 460, 18, { size: 9.5, color: MUTE }))

  // 범례
  d.cell('', `line;html=1;strokeColor=${LINE};strokeWidth=1;`, 20, 886, W - 40, 8)
  d.txt('범례', 20, 900, 60, 18, { size: 10.5, color: INK, bold: true })
  ;[['L3 도달', 'L3'], ['L4 연결', 'L4'], ['L7 응답', 'L7'], ['데이터', 'DATA']].forEach(([nm, k], i) => {
    const x = 100 + i * 180
    d.cell('', `rounded=0;html=1;fillColor=${LAYER_COLOR[k]};strokeColor=none;`, x, 902, 24, 12)
    d.txt(nm, x + 32, 896, 140, 22, { size: 9.5, color: BODY })
  })
  ;[
    '판정표의 원은 프로브 응답이다. 초록이 성공, 빨강이 실패다.',
    '마지막 행이 중요하다. 프로브가 전부 정상인데 느린 경우가 있고 그때는 계층 문제가 아니다. 수요가 줄었을 수도 있고 DB 가 느릴 수도 있다.',
    '수치 출처. 실패율 대조는 2026-08-29 LB 배선 회차 실측이고 계획서 하네스 검증설계 3절이 원본이다. 계층 프로브 0건은 저장소 전수 확인 결과다.',
  ].forEach((t, i) => d.txt(t, 20, 926 + i * 22, 1800, 18, { size: 9.5, color: MUTE }))

  return { xml: d.toString('계층 판별', W, H), out: '계층판별_트리.drawio', W, H }
}

for (const g of [serviceArchitecture(), incidentFlow(), layerTriage()]) {
  writeFileSync(new URL(g.out, import.meta.url), g.xml, 'utf8')
  console.log(`${g.out}  ${g.W}x${g.H}`)
}
