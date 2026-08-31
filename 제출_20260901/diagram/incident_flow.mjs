// 도면 2. 장애 대응 흐름. 계획서 7.4·7.11·7.12 의 파생물이고 문서가 정본이다.
//
// 도면 1이 무엇을 운영하는지를 그린다면 이 도면은 어떻게 운영하는지를 그린다.
// 상태 전이 패널은 시스템이 어떤 모습이 되는지 보여 주지 운영자가 무엇을 하는지는
// 보여 주지 않는다. 그 자리를 이 도면이 채운다.
//
// 색 규약은 도면 1과 같되 축이 다르다. 도면 1의 색은 노드 상태였고 여기서는 손이
// 닿는 정도다. 자동은 청록, 승인 필요는 주황, 사람은 회색이다. 구조는 무채색 선으로만
// 가른다. 두 도면을 나란히 놓고 볼 때 같은 색이 다른 뜻이 되므로 범례에 축을 적는다.
//
// 결손을 흐리게 그리지 않는다. 알람 11개가 전부 무음이라는 사실이 이 도면의 결론이고
// 그것을 옅은 회색으로 밀어 두면 도면이 계획을 완료로 위장한다.
//
// 사용: node incident_flow.mjs           -> incident_flow.svg
//       node incident_flow.mjs --slide   -> incident_flow_slide.svg (하단 제외)

import { Canvas, C, writeSvg } from './lib/draw.mjs'
import {
  TIER_COLOR, FLOW_KPI, STATIONS as ST_DATA, SEV as SEV_DATA, SEV_NOTE,
  TIERS as TIER_DATA, TIER_NOTE, NOAUTO, LEDGER_WHY, SAFE_COND, FIG_META,
} from './lib/content.mjs'

const SLIDE = process.argv.includes('--slide')

// 손이 닿는 정도. 이 셋 밖의 색은 쓰지 않는다
const { AUTO, APPR, HAND, GAP } = TIER_COLOR
const LINE = '#D0D7DC'
const INK = C.INK
const BODY = C.BODY
const MUTE = C.MUTE

const W = 2100
const H_MAIN = 1006
const H = SLIDE ? H_MAIN : H_MAIN + 132

const d = new Canvas(W, H, { markers: [INK, MUTE, AUTO] })

function box(x, y, w, h, { stroke = LINE, fill = 'none', dash = null, sw = 1 } = {}) {
  const ds = dash ? ` stroke-dasharray="${dash}"` : ''
  d.add(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="2" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"${ds}/>`)
}

/* ===================== 제목 밴드 ===================== */
const MF = FIG_META.flow
d.txt(40, 42, `${MF.no}  ·  ${MF.title}`, 23, INK, '700')
d.txt(40, 64, MF.sub, 11.5, MUTE)
d.txt(40, 84, '계획서 7.4 소거 절차 · 7.11 심각도 · 7.12 자동화 계층에서 파생 · 색은 손이 닿는 정도를 뜻한다', 9.5, BODY)

const KPI = FLOW_KPI
KPI.forEach(([k, v, bad], i) => {
  const x = 1500 + i * 152
  d.add(`<line x1="${x - 16}" y1="26" x2="${x - 16}" y2="76" stroke="${LINE}" stroke-width="1"/>`)
  d.txt(x, 42, k, 10, MUTE)
  d.txt(x, 66, v, 17, bad ? GAP : INK, '700')
})
d.add(`<line x1="40" y1="98" x2="${W - 40}" y2="98" stroke="${LINE}" stroke-width="1.2"/>`)

/* ===================== 운영 루프 등뼈 ===================== */
d.txt(40, 136, '운영 루프', 14, INK, '700')
d.txt(122, 136, '시작과 끝이 같은 정의를 쓴다. 복구 판정이 정상 상태로 돌아왔는가이므로 정상 상태 정의가 곧 복구 기준이다', 11, MUTE)

const STATIONS = ST_DATA.map((s) => ({ ...s, tier: TIER_COLOR[s.tier] }))

const SX = 40, SY = 170, SW = 274, SH = 128, SG_ = 12
STATIONS.forEach((st, i) => {
  const x = SX + i * (SW + SG_)
  box(x, SY, SW, SH, { stroke: LINE })
  // 좌측 색띠가 손이 닿는 정도다. 상자 채움을 쓰면 결손 표기와 축이 겹친다
  d.add(`<rect x="${x}" y="${SY}" width="4" height="${SH}" rx="2" fill="${st.tier}"/>`)
  d.step(x + 26, SY + 26, st.n, INK)
  d.txt(x + 46, SY + 31, st.t, 14.5, INK, '700')
  d.txt(x + 18, SY + 60, st.s, 11, BODY)
  d.txt(x + 18, SY + 84, st.tl, 10.5, st.tier, '700')
  if (st.gap) {
    d.add(`<rect x="${x + 18}" y="${SY + 96}" width="${SW - 36}" height="20" rx="2" fill="#FDF2F1" stroke="${GAP}" stroke-width="0.9"/>`)
    d.txt(x + 26, SY + 110, st.gap, 9.6, GAP, '700')
  }
  if (i < STATIONS.length - 1) {
    d.ln([[x + SW + 1, SY + SH / 2], [x + SW + SG_ - 1, SY + SH / 2]], INK, 1.4)
  }
})

// 루프 회귀선. 종료에서 감지로 돌아간다
const LBY = SY + SH + 30
d.ln([[SX + 6 * (SW + SG_) + SW / 2, SY + SH], [SX + 6 * (SW + SG_) + SW / 2, LBY],
      [SX + SW / 2, LBY], [SX + SW / 2, SY + SH + 2]], MUTE, 1.2, '5 4')
d.txt(SX + SW / 2 + 20, LBY - 8, '정상 상태 재확인 후 루프로 복귀', 10, MUTE)

/* ===================== 심각도 ===================== */
const GY = 380
d.txt(40, GY, '심각도와 대응 시점', 14, INK, '700')
d.txt(214, GY, '7.11. 알람은 경로가 있을 때만 알람이다', 11, MUTE)

const SEV = SEV_DATA.map(([a,b,c,k]) => [a,b,c,TIER_COLOR[k]])
box(40, GY + 16, 1000, 176, { stroke: LINE })
SEV.forEach(([lv, when, what, col], i) => {
  const y = GY + 44 + i * 56
  d.add(`<rect x="58" y="${y - 16}" width="42" height="26" rx="3" fill="${col}"/>`)
  d.txt(79, y + 2, lv, 13, '#FFFFFF', '700', 'middle')
  d.txt(116, y + 2, when, 12.5, INK, '700')
  d.txt(272, y + 2, what, 10.4, BODY)
  if (i < 2) d.add(`<line x1="58" y1="${y + 24}" x2="1022" y2="${y + 24}" stroke="${LINE}" stroke-width="1"/>`)
})
d.txt(58, GY + 210, SEV_NOTE, 10.6, GAP, '700')

/* ===================== 자동화 계층 ===================== */
d.txt(1074, GY, '자동화 계층', 14, INK, '700')
d.txt(1186, GY, '7.12. 사람 손이 어디서 끊기고 어디서 붙는가', 11, MUTE)

const TIERS = TIER_DATA.map(([a,b,c,k,e]) => [a,b,c,TIER_COLOR[k],e])
box(1074, GY + 16, 986, 176, { stroke: LINE })
TIERS.forEach(([lv, nm, how, col, items], i) => {
  const y = GY + 44 + i * 40
  d.add(`<rect x="1092" y="${y - 15}" width="34" height="24" rx="3" fill="${col}"/>`)
  d.txt(1109, y + 2, lv, 11, '#FFFFFF', '700', 'middle')
  d.txt(1136, y + 2, nm, 12, INK, '700')
  d.txt(1218, y + 2, how, 10.4, col, '700')
  d.txt(1310, y + 2, items, 9.8, BODY)
  if (i < 3) d.add(`<line x1="1092" y1="${y + 16}" x2="2042" y2="${y + 16}" stroke="${LINE}" stroke-width="1"/>`)
})
d.txt(1092, GY + 210, TIER_NOTE + ' · 1층 대표 사례는 리더 kill(E4 로컬 실측)', 10.6, MUTE)

/* ===================== 일부러 자동화하지 않은 것 ===================== */
const NY = 626
d.txt(40, NY, '일부러 자동화하지 않은 것', 14, INK, '700')
d.txt(268, NY, '무분별한 자동화는 사고를 키운다. 자동화의 안전 조건을 만족하지 못하는 것을 이름으로 적는다', 11, MUTE)

box(40, NY + 16, 2020, 152, { stroke: LINE })
NOAUTO.forEach(([what, why], i) => {
  const y = NY + 44 + i * 26
  d.add(`<circle cx="66" cy="${y - 4}" r="4.5" fill="none" stroke="${HAND}" stroke-width="1.4"/>`)
  d.add(`<line x1="63" y1="${y - 7}" x2="69" y2="${y - 1}" stroke="${HAND}" stroke-width="1.4"/>`)
  d.txt(84, y, what, 11.4, INK, '700')
  d.txt(430, y, why, 10.4, BODY)
})

/* ===================== 데이터 판정을 자동 복구하지 않는 이유 ===================== */
const DY = 826
box(40, DY, 1000, 146, { stroke: HAND, sw: 1.4 })
d.txt(62, DY + 30, '원장 불일치는 왜 자동으로 고치지 않는가', 14, INK, '700')
LEDGER_WHY.forEach((t, i) => d.txt(62, DY + 58 + i * 22, t, 11.4, BODY))
d.txt(62, DY + 116, '계약 위반은 조용히 보정하기보다 명확하게 실패시키는 것을 우선한다', 10.6, MUTE, '700')

box(1074, DY, 986, 146, { stroke: LINE })
d.txt(1096, DY + 30, '자동화의 안전 조건 셋', 14, INK, '700')
SAFE_COND.forEach((t, i) => {
  const y = DY + 58 + i * 24
  d.add(`<circle cx="1108" cy="${y - 4}" r="3" fill="${AUTO}"/>`)
  d.txt(1122, y, t, 11.2, BODY)
})
d.txt(1096, DY + 134, '셋 중 하나라도 아니면 자동화하지 않고 알림까지만 자동으로 둔다', 10.6, MUTE, '700')

/* ===================== 하단 범례 ===================== */
if (!SLIDE) {
  d.add(`<line x1="40" y1="${H_MAIN + 28}" x2="${W - 40}" y2="${H_MAIN + 28}" stroke="${LINE}" stroke-width="1.2"/>`)
  d.txt(40, H_MAIN + 58, '범례', 11, INK, '700')
  d.txt(40, H_MAIN + 80, '색은 손이 닿는 정도다. 도면 1의 색은 노드 상태를 뜻하므로 두 도면에서 같은 색이 다른 축이다.', 9.8, MUTE)
  ;[['자동. 개입 0', AUTO], ['승인 필요', APPR], ['사람이 판단', HAND], ['현재 결손', GAP]].forEach(([nm, col], i) => {
    const x = 700 + i * 220
    d.add(`<rect x="${x}" y="${H_MAIN + 50}" width="26" height="12" rx="2" fill="${col}"/>`)
    d.txt(x + 36, H_MAIN + 60, nm, 9.8, BODY)
  })
  d.txt(40, H_MAIN + 108, '이 도면은 설계다. 5단계와 6단계 외에는 아직 실행 기록이 없다. 알람 통지 경로와 계층 프로브와 런북이 남은 작업이고 그 셋이 1번 2번 3번 4번 정거장을 막고 있다.', 9.8, MUTE)
}

d.txt(W - 40, H_MAIN - 14, `${MF.no} · Metadata   ${MF.meta.join('   ')}`, 8.8, MUTE, '400', 'end')

const out = SLIDE ? 'incident_flow_slide.svg' : 'incident_flow.svg'
writeSvg(new URL(out, import.meta.url), d)
console.log(`${out}  ${W}x${H}  비율 ${(W / H).toFixed(2)}`)
