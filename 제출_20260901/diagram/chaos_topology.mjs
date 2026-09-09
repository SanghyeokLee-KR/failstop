// 검증면 도커 구성도. 정본 5.1 평면 분리의 검증면 실물이다.
//
// 왜 이 도면이 필요한가. 상시면 도면은 아직 사실이 아니다. 클라우드에 인스턴스를 세운
// 적이 0건이고 인프라 코드화 파일이 0개다(정본 3.2, 13.4). 반면 증거 28건이 전부 이
// 평면에서 나왔다. 증거의 출처를 그리지 않으면 "어디서 쟀는가" 에 답할 그림이 없다.
//
// 이 도면의 요점은 배치가 아니라 경계다. 무엇이 시스템이고 무엇이 계측기인지, 장애를
// 어디에 꽂는지, 관측이 어느 쪽에 서 있는지가 갈려야 한다.
//
// 봇과 k6 를 같은 망에 그리되 역할을 갈라 표시한다. 봇은 시장을 만드는 시스템의 일부이고
// 닫힌 루프다. k6 는 천장을 재는 계측기이고 개방 루프이며 시스템 밖이다. 2026-09-09
// 실측이 그 차이를 숫자로 보였다. 봇은 목표 400 에도 거절 0 이고 k6 는 20 에서 게이트를
// 때린다. 도면에서 둘을 같은 모양으로 그리면 그 사실이 사라진다.
import { Canvas, C, writeSvg } from './lib/draw.mjs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const SLIDE = process.argv.includes('--slide')

const W = 2100
const H = SLIDE ? 1000 : 1120
const TITLE = 96

// 색 역할. 시스템 본체, 계측기, 관측, 장애 주입을 색으로 가른다.
const SYS = C.VPCC      // 시스템 경계
const PROBE = C.PROOF   // 계측기와 장애 주입
const OBS = C.PRV       // 관측
const HOST = C.CORP     // 호스트

const d = new Canvas(W, H, {
  contentY: TITLE,
  markers: [C.FLOW, PROBE, OBS, C.PUB, C.SG],
})

// ── 제목 밴드 ──────────────────────────────────────────────────────────
d.pre.push(`<rect x="0" y="0" width="${W}" height="${TITLE}" fill="#FFFFFF"/>`)
d.pre.push(`<line x1="0" y1="${TITLE}" x2="${W}" y2="${TITLE}" stroke="${C.HAIR}" stroke-width="1"/>`)
d.pre.push(`<text x="56" y="46" font-family="${C.KO || "'Malgun Gothic',sans-serif"}" font-size="30" font-weight="700" fill="${C.INK}">검증면 도커 구성</text>`)
d.pre.push(`<text x="56" y="72" font-family="'Malgun Gothic',sans-serif" font-size="13" fill="${C.MUTE}">VERIFICATION PLANE / DOCKER TOPOLOGY · 실험과 증거가 나오는 곳</text>`)
const tags = [['단일 호스트', HOST], ['컨테이너 16', SYS], ['개방 루프 계측', PROBE]]
tags.forEach(([t, c], i) => {
  const x = W - 56 - (tags.length - i) * 190
  d.pre.push(`<rect x="${x}" y="26" width="176" height="30" rx="4" fill="${c}22" stroke="${c}" stroke-width="1"/>`)
  d.pre.push(`<text x="${x + 88}" y="46" font-family="'Malgun Gothic',sans-serif" font-size="12.5" font-weight="700" fill="${c}" text-anchor="middle">${t}</text>`)
})

// ── 호스트 ────────────────────────────────────────────────────────────
d.group(40, 30, W - 80, H - TITLE - 130, 'Windows 11 + WSL2 · Docker Desktop · 단일 호스트', HOST, '#FFFFFF', null, 1.8)
d.txt(70, 78, '컨테이너 16개가 한 대에 있다. 진짜 노드 분리가 아니고 자원을 공유한다(정본 13.1)', 11, C.MUTE)

// ── 조작 계층. 호스트에서 도는 스크립트 ────────────────────────────────
d.group(80, 100, 420, 250, '호스트 실행 · 실험 조작', PROBE, '#FFFDF6', '7 5')
d.chip(190, 165, 190, 46, 'harness/scripts', 'e1~e9.ps1 · 장애 주입 9종', PROBE)
d.chip(190, 235, 190, 46, 'harness/scale', '격자와 사다리 러너', PROBE)
d.chip(390, 165, 170, 46, 'docker API', '컨테이너 정지와 재기동', PROBE)
d.chip(390, 235, 170, 46, 'Toxiproxy admin', ':8474 · 지연과 절단', PROBE)
d.txt(100, 320, '시스템 밖에서 조작한다. 앱 코드에 실험용 분기를 넣지 않는다', 10, C.MUTE)

// ── 카오스 네트워크 ────────────────────────────────────────────────────
const NX = 560
const NY = 100
const NW = W - NX - 90
d.group(NX, NY, NW, 720, 'exchange-chaos_chaos-net · 도커 브리지 네트워크', SYS, '#FCFAFF', null, 1.8)

// 진입
d.node(NX + 110, NY + 120, 'user', 'k6 계측기', 'chaos-k6 · 개방 루프')
d.txt(NX + 110, NY + 200, '초당 400 iteration 주입', 9.5, PROBE, '700', 'middle')
d.txt(NX + 110, NY + 216, '시스템 밖. 느려져도 안 물러선다', 9, C.MUTE, '400', 'middle')

d.node(NX + 330, NY + 120, 'alb', 'chaos-lb', 'nginx:1.29 · :58080')

// 앱 그룹
d.group(NX + 470, NY + 40, 480, 310, 'Spring Boot · 배포단위 셋', C.PUB, '#FBFDF6')
d.chip(NX + 585, NY + 110, 200, 52, 'chaos-api-1', '매칭 리더 · :18080', C.PUB)
d.chip(NX + 585, NY + 190, 200, 52, 'chaos-api-2', '조회와 일반 쓰기 · :28080', C.PUB)
d.chip(NX + 585, NY + 270, 200, 52, 'chaos-bots', '시장 봇 · 닫힌 루프', C.PUB)
d.chip(NX + 830, NY + 215, 170, 52, 'chaos-admin', ':8443 mTLS', C.SG)
d.txt(NX + 830, NY + 258, '별도 배포단위 · 관리 평면', 9, C.MUTE, '400', 'middle')
d.txt(NX + 485, NY + 335, '봇은 앱 안에서 도는 스레드다. 시장을 만들지 천장을 재지 않는다', 9.5, C.MUTE)

// 장애 주입
d.node(NX + 1010, NY + 120, 'fw', 'chaos-toxiproxy', '노드별 프록시 2개')
d.txt(NX + 1010, NY + 200, 'pg-api1 · pg-api2', 9.5, PROBE, '700', 'middle')
d.txt(NX + 1010, NY + 216, '리더만 겨냥할 수 있다', 9, C.MUTE, '400', 'middle')

// DB
d.node(NX + 1210, NY + 120, 'pg', 'chaos-pg', 'postgres:17-alpine')
d.txt(NX + 1210, NY + 200, 'standby 는 실험 때만', 9, C.MUTE, '400', 'middle')
d.txt(NX + 1210, NY + 214, 'standby_up.sh 로 세운다', 9, C.MUTE, '400', 'middle')

// 웹
d.chip(NX + 330, NY + 250, 190, 52, 'chaos-web', 'nginx · :3000 정적', C.PUB)
d.txt(NX + 330, NY + 300, '같은 출처 프록시로', 9, C.MUTE, '400', 'middle')
d.txt(NX + 330, NY + 314, '/api/ 와 /ws/ 를 넘긴다', 9, C.MUTE, '400', 'middle')

// ── 관측 그룹 ──────────────────────────────────────────────────────────
const OY = NY + 390
d.group(NX + 30, OY, NW - 60, 300, '관측 · 피검체 밖에서 당긴다', OBS, '#F5FCFC')
d.node(NX + 160, OY + 110, 'prom', 'chaos-prometheus', ':59090 · scrape')
d.node(NX + 400, OY + 110, 'grafana', 'chaos-grafana', ':53001 · 대시보드 9종')
d.chip(NX + 640, OY + 105, 190, 52, 'chaos-alertmanager', ':59093 · 알람 17', OBS)
d.chip(NX + 870, OY + 105, 190, 52, 'chaos-blackbox', '외부 프로브', OBS)
d.chip(NX + 1100, OY + 105, 190, 52, 'chaos-pg-exporter', 'DB 지표', OBS)
d.chip(NX + 1330, OY + 105, 190, 52, 'chaos-cstats', '컨테이너 자원', OBS)
d.txt(NX + 60, OY + 200, '수집 축 다섯. 앱 액추에이터, 봇, 진입 프로브, 데이터베이스, 컨테이너 경계', 10.5, C.BODY)
d.txt(NX + 60, OY + 222, 'cAdvisor 를 쓰지 않는다. Docker Desktop 에서 컨테이너 메타데이터를 못 읽어 도커 API 를 직접 읽는다(2026-09-09 실측)', 9.6, C.MUTE)
d.txt(NX + 60, OY + 244, '알람 수신 주소가 비어 있다. 통지 경로는 배선됐고 주소만 미기입이다(정본 한계 15)', 9.6, C.MUTE)

// 임차 보관소
d.chip(NX + 1330, NY + 120, 180, 52, 'chaos-dynamodb', '리더 임차 · 선택', C.MUTE)
d.txt(NX + 1330, NY + 172, '기본은 PG leader_lease', 9, C.MUTE, '400', 'middle')

// ── 흐름선 ────────────────────────────────────────────────────────────
// 계측기 -> LB -> 앱 -> DB
d.ln([[NX + 150, NY + 120], [NX + 292, NY + 120]], PROBE, 1.8)
d.ln([[NX + 368, NY + 120], [NX + 485, NY + 120]], C.FLOW, 1.8)
d.ln([[NX + 685, NY + 110], [NX + 760, NY + 110], [NX + 760, NY + 120], [NX + 972, NY + 120]], C.FLOW, 1.6)
d.ln([[NX + 1048, NY + 120], [NX + 1172, NY + 120]], C.FLOW, 1.8)
d.step(NX + 430, NY + 96, 1, C.FLOW)
d.step(NX + 900, NY + 96, 2, C.FLOW)

// 임차
d.ln([[NX + 620, NY + 84], [NX + 620, NY + 60], [NX + 1330, NY + 60], [NX + 1330, NY + 94]], C.MUTE, 1.2, '5 4')

// 관측이 앱을 당긴다
d.ln([[NX + 160, OY + 60], [NX + 160, OY - 24], [NX + 585, OY - 24], [NX + 585, NY + 355]], OBS, 1.4, '6 4')
d.txt(NX + 250, OY - 32, 'scrape :8080 · 앱을 당긴다', 9.5, OBS, '700')
d.ln([[NX + 1195, OY + 80], [NX + 1195, OY - 40], [NX + 1210, OY - 40], [NX + 1210, NY + 165]], OBS, 1.4, '6 4')

// 조작선
d.ln([[500, 235], [NX + 990, NY + 150]], PROBE, 1.4, '5 4')
d.ln([[500, 165], [NX + 92, NY + 108]], PROBE, 1.4, '5 4')

// ── 실험 방식 ──────────────────────────────────────────────────────────
const EY = H - TITLE - 150
d.group(80, EY, W - 160, 110, '실험 방식', C.INK, '#FAFAFA', null, 1.5)
const how = [
  ['장애 주입', 'e1~e9 · 프록시 지연과 절단, 노드 정지, 네트워크 분단'],
  ['부하 주입', 'k6 개방 루프 · constant-arrival-rate'],
  ['판정', '불변식 27개 SQL · 재생 결정론 · RTO 와 RPO'],
  ['기록', 'harness/evidence/ · 회차마다 표본과 판정을 남긴다'],
]
how.forEach(([t, s], i) => {
  const x = 130 + i * 480
  d.txt(x, EY + 45, t, 12, C.INK, '700')
  d.txt(x, EY + 68, s, 10, C.MUTE)
})

// ── 범례 ──────────────────────────────────────────────────────────────
const LY = H - TITLE - 26
const leg = [['서비스 요청', C.FLOW, null], ['계측과 조작', PROBE, '5 4'], ['관측 수집', OBS, '6 4']]
leg.forEach(([t, c, dash], i) => {
  const x = 80 + i * 240
  d.ln([[x, LY], [x + 42, LY]], c, 1.8, dash)
  d.txt(x + 52, LY + 4, t, 10.5, C.MUTE)
})
d.txt(W - 80, LY + 4, '증거 28건이 이 평면에서 나왔다. 상시면은 아직 인스턴스 0대다', 10, C.MUTE, '400', 'end')

const out = join(HERE, SLIDE ? 'chaos_topology_slide.svg' : 'chaos_topology.svg')
writeSvg(out, d)
console.log('%s  %dx%d', out.split(/[\\/]/).pop(), W, H)
