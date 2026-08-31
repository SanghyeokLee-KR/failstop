// 도면 3. 계층 판별 트리. 계획서 7.4 2단계와 2.5 사용자 진술 표의 파생물이다.
//
// 이 도면 하나가 답하는 질문은 하나다. 안 돼요 를 어느 계층의 문제로 번역하는가.
// 응답이 느리다는 현상은 네트워크와 애플리케이션과 데이터베이스에서 똑같이 나온다.
// 프로브 셋의 전이 시점이 갈리는 지점에서만 계층이 결정된다.
//
// 트리를 그리되 판정표를 함께 둔다. 트리는 순서를 보여 주고 표는 조합을 보여 준다.
// 실무에서 읽는 것은 표이고 절차를 익힐 때 보는 것이 트리다.
//
// 실측 사례를 하단에 붙인다. 이 도면의 분기가 가상이 아니라는 근거이고,
// 둘 다 화면으로는 애플리케이션 장애와 구분되지 않았던 실제 사고다.
//
// 사용: node layer_triage.mjs           -> layer_triage.svg
//       node layer_triage.mjs --slide   -> layer_triage_slide.svg (하단 제외)

import { Canvas, C, writeSvg } from './lib/draw.mjs'
import {
  LAYER_COLOR, TIER_COLOR, TRIAGE_KPI, TRIAGE_NODES, TRIAGE_TABLE, INJECT_TOOLS,
  TRIAGE_STATUS, FIG_META,
} from './lib/content.mjs'

const SLIDE = process.argv.includes('--slide')

const { L3, L4, L7, DATA } = LAYER_COLOR
const GAP = TIER_COLOR.GAP
const LINE = '#D0D7DC'
const INK = C.INK
const BODY = C.BODY
const MUTE = C.MUTE

const W = 2100
const H_MAIN = 1030
const H = SLIDE ? H_MAIN : H_MAIN + 250

const d = new Canvas(W, H, { markers: [INK, MUTE, L3, L4, L7, DATA] })

function box(x, y, w, h, { stroke = LINE, fill = 'none', dash = null, sw = 1 } = {}) {
  const ds = dash ? ` stroke-dasharray="${dash}"` : ''
  d.add(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="2" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"${ds}/>`)
}

/* ===================== 제목 밴드 ===================== */
const MT = FIG_META.triage
d.txt(40, 42, `${MT.no}  ·  ${MT.title}`, 23, INK, '700')
d.txt(40, 64, `${MT.sub}  ·  계획서 7.4 2단계에서 파생`, 11.5, MUTE)
d.txt(40, 84, `${MT.goal}. 프로브 셋의 전이 시점이 갈리는 지점에서 계층이 결정된다`, 9.5, BODY)

const KPI = TRIAGE_KPI
KPI.forEach(([k, v, bad], i) => {
  const x = 1660 + i * 148
  d.add(`<line x1="${x - 16}" y1="26" x2="${x - 16}" y2="76" stroke="${LINE}" stroke-width="1"/>`)
  d.txt(x, 42, k, 10, MUTE)
  d.txt(x, 66, v, 17, bad ? GAP : INK, '700')
})
d.add(`<line x1="40" y1="98" x2="${W - 40}" y2="98" stroke="${LINE}" stroke-width="1.2"/>`)

/* ===================== 트리 ===================== */
d.txt(40, 136, '소거 순서', 14, INK, '700')
d.txt(140, 136, '위에서 아래로 내려가며 후보를 지운다. 먼저 끊기는 프로브가 계층을 지목한다', 11, MUTE)

// 진입
box(40, 158, 300, 66, { stroke: INK, sw: 1.4 })
d.txt(58, 186, '증상 진입', 13, INK, '700')
d.txt(58, 208, 'API 지연 · 오류 증가 · 체결 감소', 10.6, BODY)
d.ln([[190, 224], [190, 254]], INK, 1.4)

const NODES = TRIAGE_NODES.map((n, i) => ({ ...n, y: 254 + i * 130, col: LAYER_COLOR[n.col] }))

NODES.forEach((n, i) => {
  // 판단 상자
  box(40, n.y, 300, 66, { stroke: MUTE })
  d.txt(58, n.y + 28, n.q, 12.4, INK, '700')
  d.txt(58, n.y + 50, '예 이면 다음 단계로', 10, MUTE)

  // 아니오 가지
  d.ln([[340, n.y + 33], [470, n.y + 33]], n.col, 1.6)
  d.txt(392, n.y + 25, n.no, 10, n.col, '700', 'middle')

  // 계층 확정
  box(470, n.y, 250, 66, { stroke: n.col, sw: 1.5, fill: '#FCFDFD' })
  d.add(`<rect x="470" y="${n.y}" width="4" height="66" rx="2" fill="${n.col}"/>`)
  d.txt(492, n.y + 28, n.layer, 13.5, n.col, '700')
  d.txt(492, n.y + 50, n.own + ' 담당', 10, MUTE)

  // 원인 후보
  d.txt(748, n.y + 28, n.cause, 11.2, BODY)
  d.txt(748, n.y + 50, 'RUNBOOK 카드로 이동', 9.8, MUTE)

  // 다음 단계로 내려가는 선
  if (i < NODES.length - 1) d.ln([[190, n.y + 66], [190, n.y + 130]], INK, 1.4)
})

// 전부 정상인 경우
d.ln([[190, 710], [190, 748]], INK, 1.4)
box(40, 748, 680, 62, { stroke: LINE, dash: '5 4' })
d.txt(58, 776, '프로브 전부 정상인데 지연만 높다', 12.4, INK, '700')
d.txt(58, 798, 'RTT 와 재전송을 확인하고 정상이면 수요 축을 본다. 요청 유입이 줄었는지, 봇이 멈췄는지', 10.4, BODY)

/* ===================== 판정표 ===================== */
d.txt(1180, 136, '조합 판정표', 14, INK, '700')
d.txt(1310, 136, '실무에서 읽는 것은 이 표다', 11, MUTE)

const TH = ['ICMP', 'TCP', 'HTTP', '확정 계층', '먼저 볼 것']
const ROWS = TRIAGE_TABLE.map((r) => [r[0], r[1], r[2], r[3], LAYER_COLOR[r[4]], r[5]])
const TX = 1180, TY = 158, CW = [92, 92, 108, 148, 380]
box(TX, TY, 880, 232, { stroke: LINE })
let cx = TX + 18
TH.forEach((h, i) => { d.txt(cx, TY + 28, h, 10.6, MUTE, '700'); cx += CW[i] })
d.add(`<line x1="${TX + 18}" y1="${TY + 40}" x2="${TX + 862}" y2="${TY + 40}" stroke="${LINE}" stroke-width="1"/>`)
ROWS.forEach((r, i) => {
  const y = TY + 68 + i * 34
  let x = TX + 18
  ;[r[0], r[1], r[2]].forEach((v, j) => {
    const ok = v === '성공'
    d.add(`<circle cx="${x + 7}" cy="${y - 4}" r="4.5" fill="${ok ? '#0E7C66' : '#C0392B'}"/>`)
    d.txt(x + 20, y, v, 10.4, BODY)
    x += CW[j]
  })
  d.txt(x, y, r[3], 11.4, r[4], '700')
  x += CW[3]
  // AWS 관련 분기에만 아이콘을 단다. L2 라우팅은 igw, L4 는 ec2, L7 은 alb
  const IC = { 0: 'igw', 1: 'ec2', 2: 'alb' }
  if (IC[i] !== undefined) { d.node(x - 16, y - 4, IC[i], null, null, 14); }
  d.txt(x, y, r[5], 10.4, BODY)
  if (i < ROWS.length - 1) d.add(`<line x1="${TX + 18}" y1="${y + 12}" x2="${TX + 862}" y2="${y + 12}" stroke="${LINE}" stroke-width="1"/>`)
})

/* ===================== 주입 도구 대조 ===================== */
d.txt(1180, 438, '주입 도구와 계층', 14, INK, '700')
d.txt(1320, 438, '판별이 맞는지는 아는 계층에 넣어 확인한다', 11, MUTE)

box(1180, 460, 880, 130, { stroke: LINE })
INJECT_TOOLS.map(([a,b,c,k,e]) => [a,b,c,LAYER_COLOR[k],e]).forEach(([lv, tool, how, col, note], i) => {
  const y = 492 + i * 34
  d.add(`<rect x="1198" y="${y - 15}" width="30" height="22" rx="3" fill="${col}"/>`)
  d.txt(1213, y, lv, 10.4, '#FFFFFF', '700', 'middle')
  d.txt(1242, y, tool, 11.2, INK, '700')
  d.txt(1440, y, how, 10.4, BODY)
  d.txt(1660, y, note, 10, MUTE)
})

/* ===================== 실측 사례 ===================== */
d.txt(1180, 638, '실측 사례 · Observed Case', 14, INK, '700')
d.txt(1414, 638, '둘 다 화면으로는 애플리케이션 장애와 구분되지 않았다', 11, MUTE)

box(1180, 660, 880, 150, { stroke: L7, sw: 1.3, fill: '#FCFDFD' })
d.txt(1202, 690, 'nginx 백업 업스트림 max_fails 기본값', 12.4, INK, '700')
d.txt(1202, 714, 'no live upstreams 44,050건 · 실패율 9.61%', 10.8, GAP)
d.ln([[1690, 709], [1730, 709]], MUTE, 1.3)
d.txt(1744, 714, 'max_fails=0 에서 568건 · 0.89%', 10.8, L7, '700')
d.add(`<line x1="1202" y1="732" x2="2038" y2="732" stroke="${LINE}" stroke-width="1"/>`)
d.txt(1202, 758, 'nginx resolve 미설정', 12.4, INK, '700')
d.txt(1202, 782, '컨테이너 재생성 후 죽은 IP 지속 타격 · 502 응답 10,239건. 교정 후 주소 변경을 추종한다', 10.8, BODY)
d.txt(1202, 800, '다만 같은 창 5xx 147건은 미규명이라 이 교정이 5xx 를 0으로 만든다고 주장하지 않는다', 9.8, MUTE)

/* ===================== 현재 상태와 메타 ===================== */
box(40, 848, 1372, 148, { stroke: MUTE, sw: 1.1 })
d.txt(62, 874, TRIAGE_STATUS.title, 13, INK, '700')
TRIAGE_STATUS.items.forEach(([what, st, det], i) => {
  const y = 900 + i * 20
  d.txt(78, y, what, 10, INK, '700')
  d.txt(300, y, st, 10, st.includes('BLOCKED') || st.includes('NOT RUN') ? GAP : st === 'Available' ? L7 : MUTE, '700')
  d.txt(452, y, det, 9.5, BODY)
})
d.txt(62, 984, TRIAGE_STATUS.foot, 9.5, MUTE, '700')

box(1428, 848, 632, 148, { stroke: LINE })
d.txt(1444, 874, `${MT.no} · Metadata`, 10, MUTE, '700')
MT.meta.forEach((t, i) => d.txt(1444, 898 + i * 20, t, 9.5, MUTE))

/* ===================== 하단 범례 ===================== */
if (!SLIDE) {
  d.add(`<line x1="40" y1="${H_MAIN + 28}" x2="${W - 40}" y2="${H_MAIN + 28}" stroke="${LINE}" stroke-width="1.2"/>`)
  d.txt(40, H_MAIN + 58, '범례', 11, INK, '700')
  ;[['L3 도달', L3], ['L4 연결', L4], ['L7 응답', L7], ['데이터', DATA]].forEach(([nm, col], i) => {
    const x = 140 + i * 200
    d.add(`<rect x="${x}" y="${H_MAIN + 48}" width="26" height="12" rx="2" fill="${col}"/>`)
    d.txt(x + 36, H_MAIN + 58, nm, 9.8, BODY)
  })
  d.txt(40, H_MAIN + 88, '판정표의 원은 프로브 응답이다. 초록이 성공, 빨강이 실패다.', 9.8, MUTE)
  d.txt(40, H_MAIN + 112, '마지막 행이 중요하다. 프로브가 전부 정상인데 느린 경우가 있고 그때는 계층 문제가 아니다. 수요가 줄었을 수도 있고 DB 가 느릴 수도 있다.', 9.8, MUTE)
  d.txt(40, H_MAIN + 136, '그 구분이 없으면 정상 유휴 구간을 장애로 오인한다. 저부하 침묵 판정이 계획서 7.9 다.', 9.8, MUTE)
  d.txt(40, H_MAIN + 172, '수치 출처. 실패율 대조는 2026-08-29 LB 배선 회차 실측이고 계획서 하네스 검증설계 3절이 원본이다.', 9.8, MUTE)
  d.txt(40, H_MAIN + 196, '502 10,239건은 같은 회차의 교정 전 계수다. 계층 프로브 0건은 저장소 전수 확인 결과다.', 9.8, MUTE)
}

const out = SLIDE ? 'layer_triage_slide.svg' : 'layer_triage.svg'
writeSvg(new URL(out, import.meta.url), d)
console.log(`${out}  ${W}x${H}  비율 ${(W / H).toFixed(2)}`)
