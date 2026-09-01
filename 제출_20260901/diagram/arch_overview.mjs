// 도면 0. 시스템 구성도. 인스턴스 단위 배치만 그린다.
//
// Figure 1(arch_aws.mjs)과 무엇이 다른가. Figure 1 은 설계 리뷰용이라 쓰기 경로와
// 한계와 상태 전이를 함께 싣는다. 그 도면은 읽는 데 시간이 걸린다.
// 이 도면은 발표 첫 장과 인수인계 첫 장 자리다. 무엇이 몇 대 떠 있고 각 대에 무엇이
// 올라가는가만 답한다. 설명 문장을 넣지 않는다. 라벨은 이름과 타입까지다.
//
// 규약 셋.
//
// 하나. EC2 는 대수를 상자로 센다. 종전 Figure 1 은 app 과 db 를 아이콘 한 개씩으로
//   찍고 NAT 를 아예 안 그려서 6대가 2대로 읽혔다. 상자 여섯 개면 세는 데 실수가 없다.
//
// 둘. 상자 안이 곧 그 인스턴스에 올라가는 것이다. 동거 구성요소를 옆에 적지 않고
//   같은 상자에 넣는다. app-2 에 관측이 붙고 pg-standby 에 봇이 붙는 사실이 배치로 읽힌다.
//
// 셋. 화살표 라벨은 포트와 프로토콜까지다. 사유는 계획서가 적는다.
//
// 사용: node arch_overview.mjs   -> arch_overview.svg

import { Canvas, C, writeSvg } from './lib/draw.mjs'

const INK = C.INK
const BODY = C.BODY
const MUTE = C.MUTE
const HAIR = C.HAIR
const AWS = '#8A9299'
const VPC = '#8C4FFF'
const AZA = '#0E7C66'
const AZC = '#5C6B75'
const FLOW = '#2E3B45'

const W = 1660, H = 1250
const d = new Canvas(W, H, { markers: [FLOW, AZA] })

const line = (x1, y1, x2, y2, col = HAIR, w = 1) =>
  d.add(`<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${col}" stroke-width="${w}"/>`)

// ===================================================================== 제목
d.txt(40, 46, '시스템 구성도', 22, INK, '700')
d.txt(40, 70, 'AWS ap-northeast-2  ·  EC2 6대  ·  2 가용영역', 11.5, MUTE)
line(40, 88, W - 40, 88)

// ===================================================================== 1. 엣지
const EY = 156

d.node(110, EY, 'users', '사용자', null, 42)
d.node(340, EY, 'route53', 'Route 53', null, 42)
d.node(580, EY, 'cloudfront', 'CloudFront', null, 42)
d.node(580, 106, 'acm', null, null, 24)
d.txt(604, 110, 'ACM  us-east-1', 9, MUTE)
d.ln([[580, 118], [580, 133]], MUTE, 1, '3 2', false)

d.ln([[136, EY], [312, EY]], FLOW, 1.4)
d.ln([[368, EY], [552, EY]], FLOW, 1.4)
d.txt(224, EY - 10, 'HTTPS 443', 9, MUTE, '400', 'middle')

// 2 오리진 분기
d.node(880, EY - 74, 's3', 'Amazon S3', '정적', 42)
d.node(880, EY + 74, 'alb', null, null, 42)
d.txt(912, EY + 70, 'ALB', 12, INK, '700')
d.txt(912, EY + 86, '동적', 9.4, MUTE)
d.node(1010, EY + 120, 'acm', null, null, 24)
d.txt(1032, EY + 124, 'ACM  ap-northeast-2', 9, MUTE)
d.ln([[912, EY + 116], [994, EY + 116]], MUTE, 1, '3 2', false)

d.ln([[608, EY], [700, EY], [700, EY - 74], [852, EY - 74]], FLOW, 1.4)
d.ln([[700, EY], [700, EY + 74], [852, EY + 74]], FLOW, 1.4)
d.txt(760, EY - 84, '/*', 9, MUTE)
d.txt(716, EY + 64, '/api/*  /ws/*', 9, MUTE)

// 운영 경로
d.node(1560, EY, 'user', '운영자', null, 42)
d.node(1360, EY, 'ssm', 'SSM', null, 36)
d.ln([[1534, EY], [1388, EY]], FLOW, 1.4)
d.txt(1462, EY - 10, 'SSH 미개방', 9, MUTE, '400', 'middle')

// ===================================================================== 2. VPC
d.group(60, 304, W - 120, 700, 'VPC  10.20.0.0/16', VPC)

const AZW = 740, AZH = 606
const AZX = [92, 856]
const AZM = [
  ['가용영역  ap-northeast-2a', AZA],
  ['가용영역  ap-northeast-2c', AZC],
]
AZM.forEach(([label, col], k) => d.group(AZX[k], 356, AZW, AZH, label, col, 'none', k === 1 ? '6 4' : null, 1.4))

// 인스턴스 카드. 상자 하나가 EC2 한 대다
const CW = 684, CH = 168, CY = [400, 592, 784]

function card(x, y, name, type, stack) {
  d.add(`<rect x="${x}" y="${y}" width="${CW}" height="${CH}" rx="3" fill="#FFFFFF" stroke="${HAIR}" stroke-width="1.2"/>`)
  d.node(x + 56, y + 62, 'ec2', null, null, 40)
  d.txt(x + 56, y + 116, 'EC2', 9, MUTE, '400', 'middle')
  d.txt(x + 112, y + 52, name, 15, INK, '700')
  d.txt(x + 112, y + 72, type, 10.5, MUTE)
  line(x + 264, y + 26, x + 264, y + CH - 26)
  stack.forEach(([key, lbl], i) => {
    const sx = x + 348 + i * 116
    d.node(sx, y + 62, key, null, null, 38)
    d.txt(sx, y + 104, lbl, 9.5, BODY, '400', 'middle')
  })
}

// AZ-a
card(AZX[0] + 28, CY[0], 'app-1', 't4g.small  ·  Leader', [['spring', 'Spring Boot']])
card(AZX[0] + 28, CY[1], 'pg-primary', 't4g.small  ·  primary', [['pg', 'PostgreSQL 17']])
card(AZX[0] + 28, CY[2], 'nat-a', 't4g.nano', [['fw', 'nftables NAT']])

// AZ-c. 동거 구성요소를 옆이 아니라 같은 상자에 넣는다
card(AZX[1] + 28, CY[0], 'app-2', 't4g.small  ·  Follower', [['spring', 'Spring Boot'], ['prom', 'Prometheus'], ['grafana', 'Grafana']])
card(AZX[1] + 28, CY[1], 'pg-standby', 't4g.small  ·  standby', [['pg', 'PostgreSQL 17'], ['spring', 'Bot Fleet']])
card(AZX[1] + 28, CY[2], 'nat-c', 't4g.nano', [['fw', 'nftables NAT']])

// 배선
d.ln([[880, EY + 96], [880, 336], [AZX[0] + 200, 336], [AZX[0] + 200, CY[0]]], FLOW, 1.4)
d.ln([[AZX[0] + 200, 336], [AZX[1] + 200, 336], [AZX[1] + 200, CY[0]]], FLOW, 1.4)
d.txt(AZX[0] + 212, 328, '8080  ·  health 8081', 9, MUTE)

d.ln([[AZX[0] + 370, CY[0] + CH], [AZX[0] + 370, CY[1]]], FLOW, 1.4)
d.txt(AZX[0] + 382, CY[0] + CH + 24, '5432', 9, MUTE)
d.ln([[AZX[1] + 370, CY[0] + CH], [AZX[1] + 370, CY[1]]], FLOW, 1.4)
d.txt(AZX[1] + 382, CY[0] + CH + 24, '5432', 9, MUTE)

d.ln([[AZX[0] + 28 + CW, CY[1] + CH / 2], [AZX[1] + 28, CY[1] + CH / 2]], AZA, 1.6, '5 4')
d.txt((AZX[0] + 28 + CW + AZX[1] + 28) / 2, CY[1] + CH / 2 - 10, 'streaming replication', 9.5, AZA, '700', 'middle')

d.ln([[1360, EY + 26], [1360, 302]], MUTE, 1.2, '4 3')

// ===================================================================== 3. 배포 경로
const DY = 1104
line(40, 1042, W - 40, 1042)
d.txt(40, 1070, '배포 경로', 12, INK, '700')

const chips = [
  ['Local', 'IntelliJ'],
  ['GitHub', 'push'],
  ['GitHub Actions', 'build · test'],
]
chips.forEach(([t1, t2], i) => {
  const cx = 220 + i * 250
  d.chip(cx, DY, 176, 54, t1, t2, MUTE)
  if (i < chips.length - 1) d.ln([[cx + 88, DY], [cx + 162, DY]], FLOW, 1.4)
})
d.ln([[808, DY], [882, DY]], FLOW, 1.4)
d.node(940, DY - 8, 'docker', 'GHCR', 'image digest', 40)
d.ln([[992, DY - 8], [1090, DY - 8]], FLOW, 1.4)
d.node(1160, DY - 8, 'ec2', 'app-1 · app-2', 'compose pull', 40)

writeSvg(new URL('arch_overview.svg', import.meta.url), d)
console.log(`arch_overview.svg  ${W}x${H}  비율 ${(W / H).toFixed(2)}`)
