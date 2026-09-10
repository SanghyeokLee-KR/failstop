// 검증면 도커 구성도. 정본 5.1 평면 분리의 검증면 실물이다.
//
// 왜 이 도면이 필요한가. 상시면 도면은 아직 사실이 아니다. 클라우드에 인스턴스를 세운
// 적이 0건이고 인프라 코드화 파일이 0개다(정본 3.2, 13.4). 반면 증거가 전부 이 평면에서
// 나왔다. 증거의 출처를 그리지 않으면 "어디서 쟀는가" 에 답할 그림이 없다.
//
// 이 도면의 요점은 배치가 아니라 경계다. 무엇이 컨테이너로 따로 도는 프로세스이고
// 무엇이 아닌지, 부하가 어디서 들어와 어디로 가는지가 갈려야 한다. 그래서 컨테이너에는
// ctn 으로 머리띠와 적층 글리프를 주고, 컨테이너가 아닌 것은 chip 으로 둔다.
//
// 2026-09-10 개정. 네 가지를 실물과 맞췄다. 전부 실험을 무효로 만든 오해였다.
//
// 1. 로드밸런서에 쓰기와 조회를 가르는 분기가 없다. harness/nginx/nginx.conf 는
//    location 하나에 업스트림 하나이고 chaos-api-2 는 backup 이다. backup 은 1차가
//    죽었을 때만 쓴다. 즉 평시에 api-2 는 조회조차 받지 않는다. 가르는 배선은
//    deploy/nginx-edge.conf.template 의 상시면 것이고 이 평면에는 없다.
// 2. chaos-bots 는 앱 안의 스레드가 아니라 별도 컨테이너다. 같은 이미지를 bots
//    프로파일로 띄우고 EXCHANGE_SIM_BASE_URL 로 chaos-lb 를 친다. k6 와 같은
//    입구로 진짜 주문을 넣는 두 번째 부하원이다. 이것을 내부 부품으로 그리면
//    표에 남은 봇 몫을 시스템 값으로 오독한다(2026-09-09 실측: 여섯 칸 무효).
// 3. k6 는 상시 13개에 들지 않는다. 컴포즈에 없고 러너가 회차마다
//    docker run --rm 으로 띄웠다 버린다(harness/scale/k6_ceiling.py).
// 4. chaos-web 과 chaos-admin 은 이 묶음에 없다. 루트 compose.yaml 에만 있다.
//    실험 러너는 -f docker-compose.chaos.yml -f docker-compose.scale.yml 만 쓴다.
import { Canvas, C, writeSvg } from './lib/draw.mjs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const SLIDE = process.argv.includes('--slide')

const W = 2100
const TITLE = 96

// 세로 배치를 값으로 잡는다. 눈대중으로 두면 상자가 부모 밖으로 삐져나간다.
const NY = 100                    // 카오스 망 상단
const NETH = 700                  // 카오스 망 높이
const HOSTH = NY + NETH + 30      // 호스트는 망을 감싼다
const EY = HOSTH + 30             // 실험 방식 띠
const EH = 108
const LY = SLIDE ? HOSTH + 44 : EY + EH + 36
const H = TITLE + LY + 34

// 색 역할. 시스템 본체, 계측기, 관측, 호스트를 색으로 가른다.
const SYS = C.VPCC      // 시스템 경계
const PROBE = C.PROOF   // 계측기와 조작
const OBS = C.PRV       // 관측
const HOST = C.CORP     // 호스트
const APP = C.PUB       // 앱 배포단위

const d = new Canvas(W, H, {
  contentY: TITLE,
  markers: [C.FLOW, PROBE, OBS, C.PUB, C.SG, C.MUTE],
})

// 제목 밴드
d.pre.push(`<rect x="0" y="0" width="${W}" height="${TITLE}" fill="#FFFFFF"/>`)
d.pre.push(`<line x1="0" y1="${TITLE}" x2="${W}" y2="${TITLE}" stroke="${C.HAIR}" stroke-width="1"/>`)
d.pre.push(`<text x="56" y="46" font-family="${C.KO || "'Malgun Gothic',sans-serif"}" font-size="30" font-weight="700" fill="${C.INK}">검증면 도커 구성</text>`)
d.pre.push(`<text x="56" y="72" font-family="'Malgun Gothic',sans-serif" font-size="13" fill="${C.MUTE}">VERIFICATION PLANE / DOCKER TOPOLOGY · 실험과 증거가 나오는 곳</text>`)
const tags = [['단일 호스트', HOST], ['상시 컨테이너 13', SYS], ['부하원 둘', PROBE]]
tags.forEach(([t, c], i) => {
  const x = W - 56 - (tags.length - i) * 190
  d.pre.push(`<rect x="${x}" y="26" width="176" height="30" rx="4" fill="${c}22" stroke="${c}" stroke-width="1"/>`)
  d.pre.push(`<text x="${x + 88}" y="46" font-family="'Malgun Gothic',sans-serif" font-size="12.5" font-weight="700" fill="${c}" text-anchor="middle">${t}</text>`)
})

// 호스트
d.group(40, 30, W - 80, HOSTH, 'Windows 11 + WSL2 · Docker Desktop · 단일 호스트', HOST, '#FFFFFF', null, 1.8)
d.txt(70, 78, '컨테이너 13개가 한 대에 있다. 진짜 노드 분리가 아니고 자원을 공유한다(정본 13.1)', 11, C.MUTE)

// 호스트 실행. 컨테이너가 아닌 것들이다
d.group(80, 100, 420, 230, '호스트 실행 · 컨테이너 아님', PROBE, '#FFFDF6', '7 5')
d.chip(190, 158, 190, 46, 'harness/scripts', 'e1~e9.ps1 · 실험 9종', PROBE)
d.chip(190, 226, 190, 46, 'harness/scale', '격자와 사다리 러너', PROBE)
d.chip(390, 158, 170, 46, 'docker API', '정지와 재기동', PROBE)
d.chip(390, 226, 170, 46, 'compose', '규격과 풀 교체', PROBE)
d.txt(100, 300, '시스템 밖에서 조작한다. 앱 코드에 실험용 분기를 넣지 않는다', 10, C.MUTE)

// 컨테이너 명부. 무엇이 따로 도는 프로세스인지 세어 둔다
d.group(80, 356, 420, 214, '상시 컨테이너 13 · 역할별', SYS, '#FCFAFF')
const roster = [
  ['서빙 2', 'chaos-api-1 · chaos-api-2'],
  ['부하 1', 'chaos-bots'],
  ['경로 1', 'chaos-lb'],
  ['저장 2', 'chaos-pg · chaos-dynamodb'],
  ['주입 1', 'chaos-toxiproxy'],
  ['관측 6', 'prometheus · grafana · alertmanager'],
  ['', 'blackbox · pg-exporter · cstats'],
]
roster.forEach(([k, v], i) => {
  const y = 392 + i * 21
  if (k) d.txt(100, y, k, 9.6, SYS, '700')
  d.txt(168, y, v, 9.6, C.BODY)
})
d.txt(100, 552, 'k6 는 여기 없다. 회차마다 docker run --rm 으로 떴다 진다', 9.4, PROBE, '700')

// 이 묶음에 없는 것
d.group(80, 596, 420, 132, '이 묶음에 없는 것', C.MUTE, '#FAFAFA', '5 4')
d.txt(100, 634, 'chaos-web 과 chaos-admin 은 루트 compose.yaml 에만 있다', 9.6, C.BODY)
d.txt(100, 654, '실험 러너는 chaos.yml 과 scale.yml 둘만 겹쳐 쓴다', 9.6, C.MUTE)
d.txt(100, 682, 'chaos-toxiproxy-init 은 한 번 돌고 끝나는 일회성이라 13 에 안 센다', 9.6, C.MUTE)
d.txt(100, 710, '상시면 도면은 arch_aws 쪽이다', 9.6, C.MUTE)

// 카오스 네트워크
const NX = 560
const NW = W - NX - 70
d.group(NX, NY, NW, NETH, 'exchange-chaos_chaos-net · 도커 브리지 네트워크', SYS, '#FCFAFF', null, 1.8)

// 부하원 둘. 같은 입구로 들어간다
d.group(NX + 24, NY + 40, 210, 320, '부하원 · 둘 다 진짜 주문', PROBE, '#FFFDF6')
d.ctn(NX + 38, NY + 74, 182, 84, 'k6', 'k6:1.4.0', ['개방 루프 · 계측기', '일회성 · docker run --rm'], PROBE)
d.txt(NX + 129, NY + 174, '천장을 잰다', 9.5, PROBE, '700', 'middle')
d.ctn(NX + 38, NY + 204, 182, 84, 'chaos-bots', 'app:local', ['bots 프로파일 · 상시', 'SIM_BASE_URL 이 chaos-lb'], APP)
d.txt(NX + 129, NY + 304, '시장을 만든다', 9.5, APP, '700', 'middle')
d.txt(NX + 34, NY + 340, '같은 입구로 들어가 접수 지표에 섞인다', 9.2, C.MUTE)

// 로드밸런서
d.ctn(NX + 292, NY + 116, 190, 106, 'chaos-lb', 'nginx:1.29', ['location / 하나', '업스트림 하나', ':58080 호스트 노출'], C.FLOW)
d.txt(NX + 387, NY + 242, '쓰기와 조회를 가르지 않는다', 9.4, C.INK, '700', 'middle')
d.txt(NX + 387, NY + 258, '가르는 배선은 상시면에만 있다', 9, C.MUTE, '400', 'middle')

// 앱 배포단위
d.group(NX + 528, NY + 56, 420, 244, '서빙 노드 · 같은 이미지 둘', APP, '#FBFDF6')
d.ctn(NX + 546, NY + 92, 384, 80, 'chaos-api-1', 'app:local', ['매칭 리더 · 단일 라이터', '평시 트래픽 전량을 받는다'], APP)
d.ctn(NX + 546, NY + 190, 384, 80, 'chaos-api-2', 'app:local', ['nginx backup · 평시 트래픽 0', 'api-1 이 죽어야 받는다'], C.MUTE, '#FAFAFA')
d.txt(NX + 542, NY + 288, '노드를 늘려도 부하는 안 갈린다. 이 평면의 사실이다', 9.4, C.MUTE)

// 저장과 주입. 오른쪽 한 열로 쌓는다
const RX = NX + 1010
d.ctn(RX, NY + 92, 250, 112, 'chaos-pg', 'postgres:17-alpine', ['단일 primary', 'leader_lease 기본', 'standby 는 실험 세션에만'], C.SG)
d.ctn(RX, NY + 222, 250, 60, 'chaos-dynamodb', 'dynamodb:2.5.2', ['리더 임차 · 선택'], C.MUTE, '#FAFAFA')
d.ctn(RX, NY + 300, 250, 60, 'chaos-toxiproxy', 'toxiproxy:2.12.0', ['커밋 경로 절단 · 장애 주입'], PROBE)

// 관측
const OY = NY + 392
const OGW = NW - 48
d.group(NX + 24, OY, OGW, 262, '관측 · 피검체 밖에서 당긴다', OBS, '#F5FCFC')
const obs = [
  ['chaos-prometheus', 'v3.13.0', ':59090 · 수집 축 다섯'],
  ['chaos-grafana', 'v13.1.0', ':53001 · 대시보드'],
  ['chaos-alertmanager', 'v0.34.0', '알람 17 · 수신 주소 미기입'],
  ['chaos-blackbox', 'v0.25.0', '외부 프로브'],
  ['chaos-pg-exporter', 'v0.17.1', 'DB 지표'],
  ['chaos-cstats', 'py3.12', '도커 API 로 컨테이너 자원'],
]
obs.forEach(([n, img, role], i) => {
  d.ctn(NX + 42 + i * 228, OY + 38, 210, 62, n, img, [role], OBS)
})
d.txt(NX + 42, OY + 136, '수집 축 다섯. 앱 액추에이터, 봇, 진입 프로브, 데이터베이스, 컨테이너 경계', 10.5, C.BODY)
d.txt(NX + 42, OY + 158, 'cAdvisor 를 쓰지 않는다. Docker Desktop 에서 컨테이너 메타데이터를 못 읽어 도커 API 를 직접 읽는다(2026-09-09 실측)', 9.6, C.MUTE)
d.txt(NX + 42, OY + 180, '알람 수신 주소가 비어 있다. 통지 경로는 배선됐고 주소만 미기입이다(정본 한계 15)', 9.6, C.MUTE)
d.txt(NX + 42, OY + 210, 'http_server_requests 에는 수용 거절과 인증 실패가 안 잡힌다. Micrometer 필터 앞에서 끊기기 때문이다', 9.6, C.INK, '700')
d.txt(NX + 42, OY + 230, '제공 부하는 접수에 exchange_admission_rejected_total 을 더해 구하고, 봇 기저를 빼야 k6 몫이 나온다(2026-09-09 실측)', 9.6, C.MUTE)

// 흐름선. 부하원 둘이 같은 입구로 들어간다
d.ln([[NX + 220, NY + 116], [NX + 258, NY + 116], [NX + 258, NY + 152], [NX + 288, NY + 152]], PROBE, 1.8)
d.ln([[NX + 220, NY + 246], [NX + 258, NY + 246], [NX + 258, NY + 192], [NX + 288, NY + 192]], APP, 1.8)
d.ln([[NX + 482, NY + 168], [NX + 512, NY + 168], [NX + 512, NY + 132], [NX + 544, NY + 132]], C.FLOW, 2.2)
d.ln([[NX + 512, NY + 168], [NX + 512, NY + 230], [NX + 544, NY + 230]], C.MUTE, 1.2, '5 4')
d.txt(NX + 502, NY + 254, 'backup', 8.6, C.MUTE, '700', 'middle')
d.ln([[NX + 930, NY + 132], [NX + 970, NY + 132], [NX + 970, NY + 140], [RX - 2, NY + 140]], C.FLOW, 2.2)
d.step(NX + 262, NY + 92, 1, PROBE)
d.step(NX + 512, NY + 102, 2, C.FLOW)
d.step(NX + 970, NY + 102, 3, C.FLOW)

// 임차 보관소
d.ln([[NX + 738, NY + 172], [NX + 738, NY + 330], [RX - 40, NY + 330], [RX - 40, NY + 252], [RX - 2, NY + 252]], C.MUTE, 1.2, '5 4')

// 장애 주입
d.ln([[RX + 60, NY + 300], [RX + 60, NY + 210]], PROBE, 1.4, '5 4')

// 관측이 앱을 당긴다
d.ln([[NX + 147, OY + 36], [NX + 147, OY - 26], [NX + 738, OY - 26], [NX + 738, NY + 272]], OBS, 1.4, '6 4')
d.txt(NX + 240, OY - 34, 'scrape :8080 · 앱을 당긴다', 9.5, OBS, '700')

// 조작선
d.ln([[500, 190], [530, 190], [530, NY - 16], [NX + 640, NY - 16], [NX + 640, NY + 54]], PROBE, 1.4, '5 4')
d.ln([[500, 158], [NX + 12, NY + 158], [NX + 12, NY + 116], [NX + 36, NY + 116]], PROBE, 1.4, '5 4')

// 실험 방식
if (!SLIDE) {
  d.group(80, EY, W - 160, EH, '실험 방식', C.INK, '#FAFAFA', null, 1.5)
  const how = [
    ['장애 주입', 'e1~e9 · 노드 정지, 네트워크 분단, 커밋 경로 절단'],
    ['부하 주입', 'k6 개방 루프 + 봇. 둘 다 chaos-lb 를 친다'],
    ['판정', '불변식 27개 SQL · 재생 결정론 · RTO 와 RPO'],
    ['기록', 'harness/evidence/ · 회차마다 표본과 판정을 남긴다'],
  ]
  how.forEach(([t, s], i) => {
    const x = 130 + i * 480
    d.txt(x, EY + 44, t, 12, C.INK, '700')
    d.txt(x, EY + 66, s, 10, C.MUTE)
  })
}

// 범례
const leg = [['서비스 요청', C.FLOW, null], ['부하 주입', PROBE, null], ['관측과 조작', OBS, '6 4'], ['평시 미사용', C.MUTE, '5 4']]
leg.forEach(([t, c, dash], i) => {
  const x = 80 + i * 240
  d.ln([[x, LY], [x + 42, LY]], c, 1.8, dash)
  d.txt(x + 52, LY + 4, t, 10.5, C.MUTE)
})
d.txt(W - 80, LY + 4, '증거가 전부 이 평면에서 나왔다. 상시면은 아직 인스턴스 0대다', 10, C.MUTE, '400', 'end')

const out = join(HERE, SLIDE ? 'chaos_topology_slide.svg' : 'chaos_topology.svg')
writeSvg(out, d)
console.log('%s  %dx%d', out.split(/[\\/]/).pop(), W, H)
