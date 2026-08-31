// 도면 1. 시스템 아키텍처. 계획서 6절의 파생물이고 문서가 정본이다.
//
// 설계 규약 넷을 지킨다. 어긴 자리를 고치는 것이 이 판의 목적이다.
//
// 하나. 색은 상태에만 쓴다. 정상 청록, 장애 적색, 전이 주황, 비활성 회색 넷뿐이다.
//   구조는 색이 아니라 1px 회색 선과 여백으로 가른다. 종전 판은 색을 카테고리와 상태
//   두 축에 겹쳐 걸어서 어느 쪽 신호인지 판별할 수 없었다.
//
// 둘. 지속성과 파생을 형태로 표현한다. PostgreSQL 에 남는 정거장은 옅은 채움,
//   메모리에서 도는 정거장은 흰 바탕, 재계산 가능한 프로젝션은 파선 테두리다.
//   색을 하나도 쓰지 않고 구분한다.
//
// 셋. 같은 것은 같은 x 에 둔다. app-1 과 app-2, primary 와 standby 를 수직으로 맞춘다.
//   그래야 "같은 자리가 다른 AZ 에서 다시 선다"가 배치로 읽힌다.
//
// 넷. 설명보다 구조가 먼저다. 정거장 부제는 한 줄로 자르고 긴 논거는 계획서 본문에 둔다.
//
// 사용: node arch_aws.mjs           -> arch_aws.svg
//       node arch_aws.mjs --slide   -> arch_aws_slide.svg (하단 제외)

import { Canvas, C, writeSvg } from './lib/draw.mjs'

const SLIDE = process.argv.includes('--slide')

// 상태 4색. 이 넷 밖의 색은 쓰지 않는다
const OK = '#0E7C66'      // 정상
const FAIL = '#C0392B'    // 장애
const TRANS = '#B8730A'   // 복구 중
const IDLE = '#98A2A8'    // 비활성
// 구조는 무채색으로만 가른다
const LINE = '#D0D7DC'
const INK = C.INK
const BODY = C.BODY
const MUTE = C.MUTE

const W = 2100
const H_MAIN = 1218
const H = SLIDE ? H_MAIN : H_MAIN + 140

const d = new Canvas(W, H, { markers: [INK, MUTE, OK, IDLE] })

/** 얇은 경계 상자. 이 도면의 모든 구획은 이것 하나로 그린다. */
function box(x, y, w, h, { stroke = LINE, fill = 'none', dash = null, sw = 1 } = {}) {
  const ds = dash ? ` stroke-dasharray="${dash}"` : ''
  d.add(`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="2" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"${ds}/>`)
}

/**
 * 문자열의 대략 렌더 폭. 한글은 전각이라 라틴의 약 두 배를 잡는다.
 * 라틴 기준으로 계산하면 한글 제목 뒤에 붙인 부제가 제목 위로 겹친다.
 */
function textWidth(s, size) {
  let w = 0
  for (const ch of s) w += /[ㄱ-힝]/.test(ch) ? size : size * 0.55
  return w
}

/** 구획 제목. 좌상단에 붙이고 부제는 같은 줄 오른쪽에 회색으로 단다. */
function caption(x, y, title, note = null, col = INK) {
  d.txt(x, y, title, 12, col, '700')
  if (note) d.txt(x + textWidth(title, 12) + 20, y, note, 9.5, MUTE)
}

// ===================================================================== 1. 헤더
d.txt(W / 2, 40, '고가용성 가상 암호화폐 거래소  ·  시스템 아키텍처', 24, INK, '700', 'middle')
d.txt(W / 2, 62, 'AWS ap-northeast-2  ·  Multi-AZ  ·  Single-writer matching engine  ·  PostgreSQL streaming replication', 11.5, MUTE, '400', 'middle')
d.add(`<line x1="40" y1="86" x2="${W - 40}" y2="86" stroke="${LINE}" stroke-width="1"/>`)

// KPI 는 보조 정보다. 제목과 같은 밴드 안에 두되 크기로 눌러 둔다
;[['AZ 장애 내성', '1 AZ'],
  ['Failover RTO', '6.688 s'],
  ['RPO', '0'],
  ['불변식 위반', '0 행']].forEach(([k, v], i) => {
  const x = 1592 + i * 120
  d.txt(x, 34, k, 9, MUTE, '400')
  d.txt(x, 54, v, 15, INK, '700')
  if (i < 3) d.add(`<line x1="${x + 104}" y1="24" x2="${x + 104}" y2="60" stroke="${LINE}" stroke-width="1"/>`)
})

// ===================================================================== 2. AWS 영역
box(40, 104, 2020, 656, { stroke: '#8A9299', sw: 1.4 })
caption(56, 126, 'AWS Cloud', 'ap-northeast-2')

// --------------------------------------------------------------- 엣지. 분기를 기하로 낸다
const EY = 214
d.node(78, EY, 'users', null, null, 26)
d.txt(78, EY + 26, '사용자', 10.5, BODY, '400', 'middle')

d.node(206, EY, 'route53', null, null, 26)
d.txt(206, EY + 26, 'Route 53', 11, INK, '700', 'middle')

d.node(348, EY, 'cloudfront', null, null, 26)
d.txt(348, EY + 26, 'CloudFront', 11, INK, '700', 'middle')

// CloudFront 에서 정적과 동적이 갈린다. 이 분기가 이 도면 상단의 요지다
d.node(536, EY - 46, 's3', null, null, 26)
d.txt(566, EY - 50, 'S3', 11, INK, '700')
d.txt(566, EY - 36, '정적 · /*', 9, MUTE)

d.node(536, EY + 46, 'alb', null, null, 26)
d.txt(566, EY + 42, 'ALB', 11, INK, '700')
d.txt(566, EY + 56, '동적 · /api/*  /ws/*', 9, MUTE)

d.ln([[94, EY], [192, EY]], INK, 1.3)
d.ln([[220, EY], [334, EY]], INK, 1.3)
d.ln([[362, EY], [430, EY], [430, EY - 46], [522, EY - 46]], INK, 1.3)
d.ln([[430, EY], [430, EY + 46], [522, EY + 46]], INK, 1.3)

// 인증서는 보조 표기다. 리전이 다르다는 사실만 남긴다
d.ln([[348, 176], [348, 200]], MUTE, 1, '3 2', false)
d.txt(348, 170, 'ACM  us-east-1', 8.8, MUTE, '400', 'middle')
d.txt(566, EY + 70, 'ACM  ap-northeast-2', 8.8, MUTE)

// 타겟그룹은 계약 각주다. 크기로 눌러 둔다
box(700, EY + 14, 560, 64)
d.txt(714, EY + 34, 'Target Group', 10.5, INK, '700')
d.txt(714, EY + 50, '/actuator/health/readiness   port 8081', 9.5, BODY)
d.txt(714, EY + 66, '팔로워는 503 이라 자동으로 빠진다. 트래픽 8080 과 헬스체크 8081 이 다르다', 8.8, MUTE)

box(1300, EY + 14, 736, 64)
d.txt(1314, EY + 34, '운영 경로', 10.5, INK, '700')
d.txt(1314, EY + 50, 'SSM Session Manager 단독 · SSH 22 전 노드 미개방 · NAT 인스턴스 AZ 로컬 2대', 9.5, BODY)
d.txt(1314, EY + 66, '관리 평면 8443 mTLS 는 ALB 뒤에 두지 않는다. TLS 종단이 X509 전달을 끊는다', 8.8, MUTE)

// --------------------------------------------------------------- AZ-a. 서비스 경로
const AX = 64, AW = 1972
box(AX, 320, AW, 268, { stroke: OK, sw: 1.3 })
caption(AX + 16, 342, '가용영역 ap-northeast-2a', '정상 서비스 경로. 쓰기 경로 전체가 이 안에 있다', OK)

// 호스트 두 대. 같은 x 에 AZ-c 의 짝이 선다
d.node(AX + 56, 386, 'spring', null, null, 24)
d.txt(AX + 82, 382, 'app-1', 12, INK, '700')
d.txt(AX + 82, 396, 'leader  ·  t4g.small', 9, MUTE)

d.node(AX + 1288, 386, 'pg', null, null, 24)
d.txt(AX + 1314, 382, 'PostgreSQL 17', 12, INK, '700')
d.txt(AX + 1314, 396, 'primary  ·  t4g.small', 9, MUTE)

d.ln([[536, EY + 62], [536, 300], [AX + 56, 300], [AX + 56, 370]], INK, 1.3)

// --------------------------------------------------------------- 쓰기 등뼈
const SY = 480
const SW = 288, SGAP = 32
const SX0 = AX + 24
const ST = [
  ['1', '접수', '서명 · nonce · 멱등', 'mem'],
  ['2', '저널 append', 'command_journal · fsync', 'dur'],
  ['3', 'ACK', '여기서 200 을 낸다', 'mem'],
  ['4', '매칭엔진', 'single writer · seq 오름차순', 'sole'],
  ['5', '원장 정산', '복식부기 · 한 트랜잭션', 'dur'],
  ['6', '프로젝션', '재계산 가능한 파생', 'derived'],
]

d.add(`<line x1="${SX0}" y1="${SY}" x2="${SX0 + 5 * (SW + SGAP) + SW}" y2="${SY}" stroke="${INK}" stroke-width="2.6"/>`)

ST.forEach(([n, title, sub, kind], i) => {
  const x = SX0 + i * (SW + SGAP)
  // 지속성과 파생을 색이 아니라 형태로 가른다
  const fill = kind === 'dur' ? '#F2F5F7' : '#FFFFFF'
  const dash = kind === 'derived' ? '4 3' : null
  const sw = kind === 'sole' ? 1.8 : 1
  box(x, SY - 32, SW, 64, { fill, dash, sw, stroke: kind === 'sole' ? INK : LINE })
  d.add(`<circle cx="${x + 24}" cy="${SY - 10}" r="11" fill="${INK}"/>`)
  d.txt(x + 24, SY - 6, n, 11, '#FFFFFF', '700', 'middle')
  d.txt(x + 44, SY - 6, title, 13, INK, '700')
  d.txt(x + 14, SY + 16, sub, 9.5, BODY)
  d.txt(x + SW - 14, SY + 16, kind === 'dur' || kind === 'derived' ? 'PostgreSQL' : 'app-1', 8.8, MUTE, '400', 'end')
  if (i < 5) {
    d.add(`<path d="M ${x + SW} ${SY} L ${x + SW + SGAP} ${SY}" stroke="${INK}" stroke-width="2.6" marker-end="url(#h${INK.slice(1)})"/>`)
  }
})

d.txt(SX0, SY + 62, '옅은 채움 = PostgreSQL 에 남는다     흰 바탕 = 메모리에서 돈다     파선 = 재계산 가능한 파생     굵은 테두리 = 이중화할 수 없는 유일점', 9.5, MUTE)

// --------------------------------------------------------------- AZ-c. 복구 경로
box(AX, 612, AW, 132, { stroke: IDLE, sw: 1.3, dash: '5 4' })
caption(AX + 16, 634, '가용영역 ap-northeast-2c', '복구 경로. 평시에는 트래픽을 받지 않는다', MUTE)

d.node(AX + 56, 690, 'spring', null, null, 24)
d.txt(AX + 82, 686, 'app-2', 12, INK, '700')
d.txt(AX + 82, 700, 'follower  ·  readiness 503', 9, MUTE)

d.node(AX + 1288, 690, 'pg', null, null, 24)
d.txt(AX + 1314, 686, 'PostgreSQL 17', 12, INK, '700')
d.txt(AX + 1314, 700, 'standby  ·  Patroni 자동 승격', 9, MUTE)

// 복제. 청록 점선이고 범례와 같은 스타일이다
d.ln([[AX + 1288, 404], [AX + 1288, 674]], OK, 1.6, '4 3')
d.txt(AX + 1302, 560, 'streaming replication', 9.5, OK, '700')

// Loki 를 그리지 않는다. 저장소 실측으로 배선이 0이다. compose 서비스 0건, 수집기 0건,
// Grafana 데이터소스에도 미등록이다. 로그 중앙화는 계획서가 W2 작업으로 적은 미래 항목이고
// 도면은 지금 서 있는 것만 그린다. 배선 없는 아이콘은 질의응답에서 답할 자산이 없다
d.node(AX + 640, 690, 'prom', null, null, 20)
d.node(AX + 690, 690, 'grafana', null, null, 20)
d.txt(AX + 720, 686, '관측  Prometheus · Grafana', 11, INK, '700')
d.txt(AX + 720, 700, 'app-2 노드에 동거. AZ-a 상실 시 승격을 관측해야 한다', 9, MUTE)

d.txt(AX + 1560, 686, 'AZ-a 를 통째로 잃으면 등뼈 1~6 이 이 평면에서 다시 선다.', 10.5, INK, '700')
d.txt(AX + 1560, 702, '서버가 두 대라서 HA 가 아니라, 한 AZ 를 잃어도 다시 설 수 있어서 HA 다.', 9.5, BODY)

// ===================================================================== 2-b. 검증면
// 우측 열이 아니라 하단 띠로 넣는다. 우측에 두면 등뼈 6정거장이 좁아져
// 이 도면의 주역이 밀린다. 검증면은 평면 하나지 등뼈만큼의 지분은 아니다
box(40, 776, 2020, 104, { stroke: MUTE, sw: 1.2, dash: '5 4' })
caption(56, 798, '온프레미스 검증면', '세션 구동 · 비용 0 · Rocky Linux 10 · VM 2대 또는 network namespace', MUTE)
d.node(74, 840, 'linux', null, null, 22)

;[['L3', 'tc netem', '지연 · 손실 · 재정렬', '네트워크'],
  ['L4', 'nftables', 'DROP · REJECT', '인프라'],
  ['L7', 'process kill', 'SIGTERM · SIGKILL', '개발']].forEach(([l, tool, det, own], i) => {
  const x = 110 + i * 300
  d.txt(x, 828, l, 10.5, FAIL, '700')
  d.txt(x + 26, 828, tool, 11, INK, '700')
  d.txt(x, 846, det, 9, BODY)
  d.txt(x, 862, `담당 · ${own}`, 8.8, MUTE)
})

d.add(`<line x1="1030" y1="806" x2="1030" y2="866" stroke="${LINE}" stroke-width="1"/>`)
d.txt(1054, 828, '커널 계층 주입은 이 평면 전용이다. 클라우드 노드에 걸면 SSM 접근 경로가 같은 네트워크를 타서 노드를 통째로 잃는다.', 9.5, BODY)
d.txt(1054, 846, '두 평면을 잇는 것은 상시 터널이 아니라 같은 GHCR digest 와 저장소에 커밋한 verdict 파일이다.', 9.5, BODY)
d.txt(1054, 864, '검증면이 탐색하고, 증명면이 재현하고, 상시면이 서비스한다.', 9.5, INK, '700')

// ===================================================================== 3. 상태 전이
d.txt(40, 918, '상태 전이', 14, INK, '700')
d.txt(112, 918, '같은 배치, 다른 상태', 10, MUTE)

const FILM = [
  ['정상', 'ok', [['app-1', 'ok'], ['primary', 'ok']], [['app-2', 'idle'], ['standby', 'ok']], '불변식 26 체크 통과'],
  ['app-1 kill', 'ok', [['app-1', 'fail'], ['primary', 'ok']], [['app-2', 'ok'], ['standby', 'ok']], 'RTO 6.688 s 실측'],
  ['AZ-a 상실', 'trans', [['app-1', 'fail'], ['primary', 'fail']], [['app-2', 'ok'], ['standby', 'trans']], 'DB 승격 필요 · 미측정'],
  ['승격 · 재생', 'trans', [['app-1', 'fail'], ['primary', 'fail']], [['app-2', 'trans'], ['primary', 'ok']], '저널 재생 중 · 503 재시도'],
]
const PW = 484, PGAP = 24, PY = 934, PH = 246

FILM.forEach(([label, tone, aRow, cRow, note], k) => {
  const px = 40 + k * (PW + PGAP)
  const tc = tone === 'ok' ? OK : TRANS
  box(px, PY, PW, PH)
  d.add(`<line x1="${px}" y1="${PY}" x2="${px + PW}" y2="${PY}" stroke="${tc}" stroke-width="3"/>`)
  d.txt(px + 16, PY + 28, label, 13.5, INK, '700')
  d.txt(px + PW - 16, PY + 28, note, 9.5, tc, '700', 'end')

  // 두 AZ 를 같은 기하로 반복한다. 좌표가 패널마다 동일해야 상태 차이만 보인다
  const row = (ry, name, cells, boxDash) => {
    box(px + 16, ry, PW - 32, 76, { dash: boxDash })
    d.txt(px + 28, ry + 18, name, 9.5, MUTE, '700')
    cells.forEach(([lbl, st], j) => {
      const cx = px + 140 + j * 180
      const cy = ry + 46
      const col = st === 'ok' ? OK : st === 'fail' ? FAIL : st === 'trans' ? TRANS : IDLE
      if (st === 'fail') {
        d.add(`<circle cx="${cx}" cy="${cy}" r="11" fill="#FFFFFF" stroke="${FAIL}" stroke-width="1.8"/>`)
        d.add(`<path d="M ${cx - 6} ${cy - 6} L ${cx + 6} ${cy + 6} M ${cx + 6} ${cy - 6} L ${cx - 6} ${cy + 6}" stroke="${FAIL}" stroke-width="1.8"/>`)
      } else if (st === 'idle') {
        d.add(`<circle cx="${cx}" cy="${cy}" r="11" fill="#FFFFFF" stroke="${IDLE}" stroke-width="1.8" stroke-dasharray="3 2"/>`)
      } else {
        d.add(`<circle cx="${cx}" cy="${cy}" r="11" fill="${col}"/>`)
      }
      d.txt(cx + 22, cy + 4, lbl, 10, st === 'fail' ? FAIL : st === 'idle' ? IDLE : INK, st === 'idle' ? '400' : '700')
    })
  }
  row(PY + 46, 'AZ-a', aRow, null)
  row(PY + 142, 'AZ-c', cRow, '5 4')
})

if (!SLIDE) {
  // =================================================================== 4. 하단
  d.add(`<line x1="40" y1="1214" x2="${W - 40}" y2="1214" stroke="${LINE}" stroke-width="1"/>`)

  d.txt(40, 1240, '운영 상태와 비용', 11, INK, '700')
  ;[['BASELINE', '24/7', 'Route 53 · CloudFront · S3. 외부 접속 요건 성립', 'USD 0.72 /월'],
    ['HA STEADY', '24/7', '위 토폴로지 전체. app x2 · primary/standby · NAT x2', '달력 월 97,318원'],
    ['PROOF', '창 구동', '부하 발생기 c7g.large 단독. 달력 월 45시간 예산', 'USD 0.0816 /hr']].forEach(([nm, mode, desc, price], i) => {
    const y = 1264 + i * 22
    d.txt(40, y, nm, 10, INK, '700')
    d.txt(150, y, mode, 9.5, MUTE)
    d.txt(220, y, desc, 9.5, BODY)
    d.txt(1000, y, price, 10, INK, '700', 'end')
  })

  d.txt(1120, 1240, '범례', 11, INK, '700')
  const LG = [
    ['쓰기 등뼈', INK, 2.6, null],
    ['서비스 트래픽', INK, 1.3, null],
    ['복제', OK, 1.6, '4 3'],
    ['관리 · 인증서', MUTE, 1, '3 2'],
  ]
  LG.forEach(([nm, col, sw, dash], i) => {
    const x = 1120 + i * 240
    const y = 1268
    d.add(`<line x1="${x}" y1="${y}" x2="${x + 40}" y2="${y}" stroke="${col}" stroke-width="${sw}"${dash ? ` stroke-dasharray="${dash}"` : ''}/>`)
    d.txt(x + 50, y + 4, nm, 9.5, BODY)
  })
  ;[['정상', OK], ['장애', FAIL], ['복구 중', TRANS], ['비활성', IDLE]].forEach(([nm, col], i) => {
    const x = 1120 + i * 240
    const y = 1300
    d.add(`<circle cx="${x + 8}" cy="${y}" r="7" fill="${col}"/>`)
    d.txt(x + 24, y + 4, nm, 9.5, BODY)
  })
  d.txt(40, 1332, '수치에 출처가 없으면 미측정이다. RTO 6.688 s 는 E4-20260829 실측이고 AZ 상실 회차는 아직 재지 않았다. 비용은 계획서 9.5 달력 월 판정과 같은 값이다.', 9.5, MUTE)
}

const out = SLIDE ? 'arch_aws_slide.svg' : 'arch_aws.svg'
writeSvg(new URL(out, import.meta.url), d)
console.log(`${out}  ${W}x${H}  비율 ${(W / H).toFixed(2)}`)
