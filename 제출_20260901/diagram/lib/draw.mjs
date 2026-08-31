// 도면 프리미티브. arch_aws.py 를 Node 로 옮긴 것이고 도면 5장이 이 파일을 공유한다.
//
// 왜 옮겼는가. 종전 생성기는 파이썬 diagrams 패키지의 리소스 경로를 절대경로로 박고 있어
// 그 패키지가 깔린 PC 에서만 돌았다. 아이콘은 커밋된 SVG 에 base64 로 들어 있었으므로
// icons/ 로 뽑아내면 파이썬도 그 패키지도 필요 없다. 제출물 생성이 PC 에 묶이지 않는다.
//
// 좌표 규약은 종전과 같다. 10 단위 격자에 맞추고 같은 행의 아이콘은 기준선을 공유한다.

import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ICON_DIR = join(HERE, '..', 'icons')

// AWS 공식 그룹 색. 채움은 거의 무채색으로 눌러 아이콘 색이 튀지 않게 한다.
//
// 회색 세 단계는 대비를 재서 잡았다. 종전 MUTE 는 #8C979C 였는데 흰 바탕에서 명암비가
// 2.8:1 이라 9pt 본문이 인쇄와 빔프로젝터에서 뭉갰다. 도면 전체가 파스텔로 읽히는 원인이
// 서체나 배치가 아니라 이 한 값이었다. #5C6B75 는 같은 자리에서 5.4:1 이다.
export const C = {
  CLOUD: '#232F3E', REGION: '#00A4A6', VPCC: '#8C4FFF',
  PUB: '#7AA116', PRV: '#00A4A6', CORP: '#5A6B75', SG: '#C7392E',
  PROOF: '#B8730A',
  INK: '#16191F', BODY: '#3C4750', MUTE: '#5C6B75', HAIR: '#D7DDE1',
  FLOW: '#2E3B45',
}

export const KO = "'Malgun Gothic','맑은 고딕',sans-serif"

// 파이썬 float 표기를 그대로 낸다. cx - s/2 는 파이썬에서 항상 float 라 40 이 아니라 40.0 으로 찍힌다.
// 이식본이 종전 SVG 와 바이트 동일해야 이식이 맞았는지 기계로 판정할 수 있으므로 표기까지 맞춘다.
const F = (n) => (Number.isInteger(n) ? n.toFixed(1) : String(n))

const iconCache = new Map()
function icon(key) {
  if (!iconCache.has(key)) {
    iconCache.set(key, readFileSync(join(ICON_DIR, key + '.png')).toString('base64'))
  }
  return iconCache.get(key)
}

/** 도면 하나를 그리는 캔버스. 출력 순서가 곧 z 순서라 배열에 그대로 쌓는다. */
export class Canvas {
  /**
   * @param contentY 본문을 아래로 미는 양. 그만큼 위쪽이 제목 밴드로 비고,
   *   본문 좌표는 밴드가 없을 때와 같은 값을 그대로 쓴다. 제목을 넣느라 본문 좌표를
   *   전부 다시 계산하면 SVG 판과 draw.io 판의 좌표가 갈린다.
   */
  constructor(width, height, { shift = 0, markers = [], contentY = 0 } = {}) {
    this.w = width
    this.h = height
    this.shift = shift
    this.contentY = contentY
    // 출력은 세 층이다. head 는 svg 여는 태그와 defs, pre 는 제목 밴드, o 는 본문이다.
    // 본문만 translate 그룹 안에 들어가므로 세 층을 섞어 담으면 안 된다.
    this.head = []
    this.pre = []
    this.o = []
    this.head.push(
      `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"` +
      ` width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">` +
      `<rect width="${width}" height="${height}" fill="#FFFFFF"/><defs>`)
    for (const c of markers) {
      this.head.push(
        `<marker id="h${c.slice(1)}" viewBox="0 0 10 10" refX="9" refY="5"` +
        ` markerWidth="6" markerHeight="6" orient="auto-start-reverse">` +
        `<path d="M0,1.2 L9,5 L0,8.8 z" fill="${c}"/></marker>`)
    }
    this.head.push('</defs>')
  }

  add(s) { this.o.push(s) }

  /** 제목 밴드에 쓰는 텍스트. contentY 이동 밖에 있어 캔버스 최상단 기준이다. */
  headTxt(x, y, t, size = 10, c = C.BODY, w = '400', an = 'start') {
    this.pre.push(`<text x="${x}" y="${y}" font-family="${KO}" font-size="${size}" font-weight="${w}" fill="${c}" text-anchor="${an}">${t}</text>`)
  }

  /** 그룹 상자. badge 는 AWS 공식 표기의 좌상단 색 사각이고, 하위 그룹은 라벨만 둔다. */
  group(x, y, w, h, label, color, fill = 'none', dash = null, lw = 1.7, badge = true) {
    const d = dash ? ` stroke-dasharray="${dash}"` : ''
    this.add(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="4" fill="${fill}" stroke="${color}" stroke-width="${lw}"${d}/>`)
    if (!label) return
    if (badge) {
      this.add(`<rect x="${x + 9}" y="${y + 9}" width="13" height="13" rx="2" fill="${color}"/>`)
      this.add(`<text x="${x + 28}" y="${y + 20}" font-family="${KO}" font-size="11" font-weight="700" fill="${color}">${label}</text>`)
    } else {
      this.add(`<text x="${x + 10}" y="${y + 18}" font-family="${KO}" font-size="10" font-weight="700" fill="${color}">${label}</text>`)
    }
  }

  /**
   * 아이콘 노드. 중심 좌표를 받고 라벨 두 줄을 아래에 건다.
   * 이름 줄은 굵게 둔다. 아이콘만으로는 같은 계열 서비스가 구분되지 않아
   * 실제로 읽히는 것은 이름 줄이다.
   */
  node(cx, cy, key, l1 = null, l2 = null, s = 36) {
    this.add(`<image x="${F(cx - s / 2)}" y="${F(cy - s / 2)}" width="${s}" height="${s}" xlink:href="data:image/png;base64,${icon(key)}"/>`)
    if (l1) this.add(`<text x="${cx}" y="${F(cy + s / 2 + 15)}" font-family="${KO}" font-size="11" font-weight="700" fill="${C.INK}" text-anchor="middle">${l1}</text>`)
    if (l2) this.add(`<text x="${cx}" y="${F(cy + s / 2 + 28)}" font-family="${KO}" font-size="9.4" fill="${C.MUTE}" text-anchor="middle">${l2}</text>`)
  }

  /** 꺾은선. 화살촉은 색별 marker 를 참조하므로 그 색이 markers 에 등록돼 있어야 한다. */
  ln(pts, c = C.FLOW, w = 1.5, dash = null, head = true) {
    const d = dash ? ` stroke-dasharray="${dash}"` : ''
    const m = head ? ` marker-end="url(#h${c.slice(1)})"` : ''
    this.add(`<polyline points="${pts.map(([a, b]) => `${a},${b}`).join(' ')}" fill="none" stroke="${c}" stroke-width="${w}"${d}${m}/>`)
  }

  /** 경로 순서 번호. 실제 순서가 있는 흐름에만 붙인다. */
  step(cx, cy, n, c = C.FLOW) {
    this.add(`<circle cx="${cx}" cy="${cy}" r="9" fill="#FFFFFF" stroke="${c}" stroke-width="1.5"/>`)
    this.add(`<text x="${cx}" y="${F(cy + 3.5)}" font-family="${KO}" font-size="10" font-weight="700" fill="${c}" text-anchor="middle">${n}</text>`)
  }

  txt(x, y, s, size = 10, c = C.BODY, w = '400', an = 'start') {
    this.add(`<text x="${x}" y="${y}" font-family="${KO}" font-size="${size}" font-weight="${w}" fill="${c}" text-anchor="${an}">${s}</text>`)
  }

  /** 두 줄짜리 라벨 상자. 아이콘이 없는 개념을 놓을 때 쓴다. */
  chip(cx, cy, w, h, t1, t2, c) {
    this.add(`<rect x="${F(cx - w / 2)}" y="${F(cy - h / 2)}" width="${w}" height="${h}" rx="3" fill="#FFFFFF" stroke="${c}" stroke-width="1.1"/>`)
    this.txt(cx, cy - 2, t1, 10, c, '700', 'middle')
    this.txt(cx, cy + 13, t2, 8.6, C.MUTE, '400', 'middle')
  }

  toString() {
    return [
      ...this.head,
      ...this.pre,
      `<g transform="translate(0,${this.contentY - this.shift})">`,
      ...this.o,
      '</g>',
      '</svg>',
    ].join('\n')
  }
}

/**
 * SVG 를 파일로 낸다. 줄끝은 CRLF 로 고정한다.
 *
 * .gitattributes 가 `제출_20260901/diagram/** -text` 로 이 폴더를 바이트 고정해 두었고
 * 기존 커밋물이 CRLF 다. LF 로 내면 재생성할 때마다 전 줄이 바뀐 것으로 잡혀
 * 실제 도면 변경과 줄끝 변경이 diff 에서 구분되지 않는다.
 * 종전 파이썬 생성기가 Windows 텍스트 모드로 열어 CRLF 를 내던 것과 결과가 같다.
 */
export function writeSvg(url, canvas) {
  writeFileSync(url, canvas.toString().replace(/\n/g, '\r\n'), 'utf8')
}
