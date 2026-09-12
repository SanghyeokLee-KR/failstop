/**
 * arch_topology.mjs. 시스템 구성도. 한눈에 읽는 배치도다.
 *
 * 사용:
 *   node arch_topology.mjs
 *
 * 산출: 시스템_구성도.svg / .png / .drawio
 *
 * `arch_aws.mjs` 와 목적이 다르다. 그쪽은 증거를 실은 문서형 도면이라 한계·상태 전이·비용까지
 * 한 장에 담는다. 이 파일은 배치만 그린다. 심사자가 처음 5 초에 무엇이 어디에 있는지 보는 용도다.
 * 그래서 설명 문장을 싣지 않는다. 수치와 판정은 정본과 arch_aws 도면이 갖는다.
 *
 * 평면이 둘이라 도면도 둘로 가른다. 왼쪽이 온프레미스 검증면이고 오른쪽이 AWS 상시면이다.
 * 가운데는 둘을 잇는 자리인데 네트워크가 아니다. 정본 4.3 이 Site-to-Site VPN 을 기각하면서
 * "두 평면을 잇는 것은 네트워크가 아니라 같은 GHCR 이미지 digest 와 저장소에 커밋한 verdict
 * 파일이다" 라고 적었다. 그 문장을 그대로 그린다.
 *
 * 실선과 파선을 가르는 기준은 평면마다 다르다. 온프레미스 검증면에서는 compose 에 정의가
 * 있으면 실선이고 수동 기동이면 파선이다. AWS 상시면에는 IaC 가 0 건이라 실선이 배선을 뜻할
 * 수 없다. 그 면의 실선은 정본이 확정한 구성이라는 뜻이고 파선은 정본 안에서도 아직 미정이거나
 * 배선 계획이 없는 것이다. 관측 스택이 그 예다. ADR 0016 이 bot-1 자리를 정했지만 상시면 컴포즈
 * 3종(`deploy/compose.node-{app,bot,data}.yml`)에 관측 서비스 정의가 0 건이라 파선으로 적는다.
 * 한 줄로 뭉쳐 적었더니 AWS 면 전체가 배선된 것으로 읽혔다. 2026-09-08 전수 조사에서 나온 정정이다.
 *
 * 원천은 정본 `제출_20260901/기획안_원본.md` 4.2 선택표, 6.2 노드 구성,
 * 6.4 조회 라우팅과 `harness/docker-compose.chaos.yml` 이다.
 *
 * 아이콘은 AWS 공식 아키텍처 아이콘(2025-07-31 자산 패키지)이다. 원본 경로는
 * `icons-aws/manifest.json` 에 있고 색과 비율은 원본을 유지한다.
 * https://aws.amazon.com/architecture/icons/
 *
 * Spring Boot 와 PostgreSQL 과 nginx 와 Prometheus 는 이름표로만 적는다. AWS 자산 패키지에
 * 없는 글리프를 흉내 내 그리지 않는다는 것이 이 도면의 규약이다. 특히 CloudWatch 아이콘을
 * 빌려 자체 운영 관측을 표시하지 않는다. 비채택한 서비스를 쓰는 것처럼 읽히기 때문이다.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const ROOT = dirname(fileURLToPath(import.meta.url));
const OUT = '시스템_구성도';

const C = {
  ink: '#232F3E', grey: '#566573', hair: '#C8CFD5',
  purple: '#8C4FFF', orange: '#ED7100', pink: '#E7157B',
  blue: '#147EBA', green: '#248814', red: '#C9252C',
};

/** 런타임 이름표 색. 각 프로젝트의 상징색이고 글리프가 아니라 색만 쓴다. */
const RT = {
  spring: '#6DB33F', postgres: '#336791', nginx: '#009639',
  obs: '#E6522C', bot: '#8C4FFF', ddb: '#C925D1',
};

const ASSET_KEYS = ['ec2', 'db', 'alb', 'cloudfront', 'route53', 's3', 'dynamodb', 'acm', 'ssm',
  'iam', 'users', 'cloud', 'vpc', 'public', 'private', 'asg'];
const assets = Object.fromEntries(ASSET_KEYS.map((k) => [
  k, readFileSync(join(ROOT, 'icons-aws', k + '.svg'), 'utf8').replace(/<\?xml[^>]*>/, '').trim(),
]));

const W = 3600;
const H = 2500;
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

class Page {
  constructor(id, title, subtitle) {
    this.id = id; this.title = title; this.subtitle = subtitle;
    this.n = 0; this.shapes = []; this.links = []; this.texts = [];
  }

  text(x, y, value, size = 20, o = {}) { this.texts.push({ x, y, value, size, ...o }); }

  icon(id, asset, x, y, size, label = '', o = {}) {
    this.shapes.push({ type: 'icon', id, asset, x, y, w: size, h: size, ...o });
    if (label) this.text(x + size / 2, y + size + 12, label, o.fs || 20, { align: 'center', weight: 600, w: o.labelWidth || 240 });
    if (o.sub) this.text(x + size / 2, y + size + (o.fs || 20) + 20, o.sub, o.subSize || 15, { align: 'center', color: C.grey, w: o.labelWidth || 240 });
  }

  /** 평면 상자. 제목 띠를 위에 얹는다. AWS 그룹 아이콘을 쓰지 않는 것은 온프레미스 평면 때문이다. */
  plane(id, x, y, w, h, title, color) {
    this.shapes.push({ type: 'plane', id, x, y, w, h, title, color });
  }

  /** 계층 띠. 물리에서 컨테이너까지 쌓인 순서를 보인다. */
  strip(id, x, y, w, h, label, right, color) {
    this.shapes.push({ type: 'strip', id, x, y, w, h, label, right, color });
  }

  /**
   * 인스턴스 상자. 아이콘은 인스턴스 종류이고 이름표는 그 위에서 도는 런타임이다.
   * 이 둘을 한 상자에 묶는 것이 이 도면의 목적이다. pills 는 런타임 이름표 배열이다.
   */
  node(id, x, y, w, h, name, sub, asset, pills, o = {}) {
    this.shapes.push({ type: 'node', id, x, y, w, h, name, sub, asset, pills, ...o });
  }

  /** 아이콘이 없는 구성요소의 이름표 상자. 공식 글리프를 빌려 쓰지 않기 위해 쓴다. */
  card(id, x, y, w, h, title, sub, color, o = {}) {
    this.shapes.push({ type: 'card', id, x, y, w, h, title, sub, color, ...o });
  }

  group(id, kind, x, y, w, h, title, o = {}) {
    const cols = { cloud: C.ink, vpc: C.purple, public: C.green, private: C.blue };
    this.shapes.push({ type: 'group', id, kind, x, y, w, h, title, color: cols[kind] || C.grey, ...o });
  }

  line(points, o = {}) {
    this.links.push({ points, ...o });
    if (o.label) this.text(o.lx, o.ly, o.label, o.fs || 16, { align: 'center', color: o.color || C.grey, w: o.lw || 160, background: o.background !== false, weight: 600 });
  }

  renderText(t, svg, cells) {
    const ls = String(t.value).split('\n');
    const align = t.align || 'left';
    const anchor = align === 'center' ? 'middle' : align === 'right' ? 'end' : 'start';
    const w = t.w || 650;
    const x = align === 'center' ? t.x - w / 2 : align === 'right' ? t.x - w : t.x;
    if (t.background) svg.push(`<rect x="${x}" y="${t.y - 3}" width="${w}" height="${ls.length * t.size * 1.4 + 6}" fill="white"/>`);
    svg.push(`<text font-family="Malgun Gothic, Arial, sans-serif" font-size="${t.size}" font-weight="${t.weight || 400}" fill="${t.color || C.ink}" text-anchor="${anchor}">${ls.map((s, i) => `<tspan x="${t.x}" y="${t.y + t.size + i * t.size * 1.42}">${esc(s)}</tspan>`).join('')}</text>`);
    cells.push(`<mxCell id="t${this.n++}" value="${esc(esc(t.value).replace(/\n/g, '<br>'))}" style="text;html=1;whiteSpace=wrap;strokeColor=none;fillColor=${t.background ? '#FFFFFF' : 'none'};align=${align};verticalAlign=top;fontFamily=Malgun Gothic;fontSize=${t.size};fontColor=${t.color || C.ink};fontStyle=${(t.weight || 400) >= 600 ? 1 : 0};spacing=0;" vertex="1" parent="1"><mxGeometry x="${x}" y="${t.y}" width="${w}" height="${ls.length * t.size * 1.42 + 6}" as="geometry"/></mxCell>`);
  }

  renderIcon(s, svg, cells) {
    const source = assets[s.asset];
    const data = Buffer.from(source).toString('base64');
    svg.push(`<image x="${s.x}" y="${s.y}" width="${s.w}" height="${s.h}" xlink:href="data:image/svg+xml;base64,${data}"/>`);
    cells.push(`<mxCell id="${s.id}" value="" style="shape=image;imageAspect=0;aspect=fixed;html=1;image=data:image/svg+xml,${encodeURIComponent(source)};" vertex="1" parent="1"><mxGeometry x="${s.x}" y="${s.y}" width="${s.w}" height="${s.h}" as="geometry"/></mxCell>`);
  }

  rect(svg, cells, id, x, y, w, h, fill, stroke, sw, dashed, rx) {
    svg.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}"${rx ? ` rx="${rx}"` : ''} fill="${fill}" stroke="${stroke}" stroke-width="${sw}"${dashed ? ' stroke-dasharray="8 5"' : ''}/>`);
    cells.push(`<mxCell id="${id}" value="" style="rounded=${rx ? 1 : 0};arcSize=${rx ? 10 : 0};html=1;fillColor=${fill};strokeColor=${stroke};strokeWidth=${sw};dashed=${dashed ? 1 : 0};dashPattern=8 5;" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${w}" height="${h}" as="geometry"/></mxCell>`);
  }

  async save() {
    const svg = [];
    const cells = [];
    const def = Object.entries(C).map(([k, color]) => `<marker id="${k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10z" fill="${color}"/></marker>`).join('')
      + Object.entries(RT).map(([k, color]) => `<marker id="rt-${k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10z" fill="${color}"/></marker>`).join('');

    for (const s of this.shapes.filter((v) => v.type === 'plane')) {
      this.rect(svg, cells, s.id, s.x, s.y, s.w, s.h, '#FFFFFF', s.color, 2.4, false);
      svg.push(`<rect x="${s.x}" y="${s.y}" width="${s.w}" height="46" fill="${s.color}"/>`);
      cells.push(`<mxCell id="${s.id}-b" value="" style="rounded=0;html=1;fillColor=${s.color};strokeColor=none;" vertex="1" parent="1"><mxGeometry x="${s.x}" y="${s.y}" width="${s.w}" height="46" as="geometry"/></mxCell>`);
      this.renderText({ x: s.x + s.w / 2, y: s.y + 10, value: s.title, size: 22, weight: 600, align: 'center', color: '#FFFFFF', w: s.w - 24 }, svg, cells);
    }

    for (const s of this.shapes.filter((v) => v.type === 'group')) {
      this.rect(svg, cells, s.id, s.x, s.y, s.w, s.h, s.fill || '#FFFFFF', s.color, 1.4, s.dashed ?? false);
      this.renderIcon({ id: s.id + '-mark', asset: s.kind, x: s.x + 14, y: s.y + 12, w: 28, h: 28 }, svg, cells);
      if (s.title) this.renderText({ x: s.x + 52, y: s.y + 14, value: s.title, size: s.fontSize || 19, color: s.color, weight: 600, w: s.w - 70 }, svg, cells);
    }

    for (const s of this.shapes.filter((v) => v.type === 'strip')) {
      this.rect(svg, cells, s.id, s.x, s.y, s.w, s.h, '#F4F6F8', s.color || C.hair, 1.4, false, 4);
      this.renderText({ x: s.x + 20, y: s.y + (s.h - 25) / 2, value: s.label, size: 18, weight: 600, color: s.color || C.ink, w: s.w - 40 }, svg, cells);
      if (s.right) this.renderText({ x: s.x + s.w - 20, y: s.y + (s.h - 22) / 2, value: s.right, size: 15, align: 'right', color: C.grey, w: s.w - 40 }, svg, cells);
    }

    for (const l of this.links) {
      const color = l.color || C.ink;
      const width = l.width || 2;
      const key = Object.entries(C).find(([, v]) => v === color)?.[0]
        || (Object.entries(RT).find(([, v]) => v === color)?.[0] ?? '').replace(/^(.)/, 'rt-$1')
        || 'ink';
      svg.push(`<polyline points="${l.points.map((p) => p.join(',')).join(' ')}" fill="none" stroke="${color}" stroke-width="${width}"${l.dashed ? ' stroke-dasharray="8 5"' : ''}${l.arrow === false ? '' : ` marker-end="url(#${key})"`}/>`);
      const start = l.points[0];
      const end = l.points.at(-1);
      const pts = l.points.slice(1, -1).map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('');
      cells.push(`<mxCell id="e${this.n++}" style="edgeStyle=none;html=1;rounded=0;strokeColor=${color};strokeWidth=${width};endArrow=${l.arrow === false ? 'none' : 'block'};endFill=1;dashed=${l.dashed ? 1 : 0};" edge="1" parent="1"><mxGeometry relative="1" as="geometry"><mxPoint x="${start[0]}" y="${start[1]}" as="sourcePoint"/><mxPoint x="${end[0]}" y="${end[1]}" as="targetPoint"/>${pts ? `<Array as="points">${pts}</Array>` : ''}</mxGeometry></mxCell>`);
    }

    /* 상자는 선보다 뒤에 그린다. 상자 몸통이 아래를 지나는 선을 덮어야 읽힌다. */
    for (const s of this.shapes.filter((v) => v.type === 'card')) {
      this.rect(svg, cells, s.id, s.x, s.y, s.w, s.h, '#FFFFFF', s.color, 2.2, s.dashed, 8);
      this.renderText({ x: s.x + s.w / 2, y: s.y + (s.sub ? 20 : (s.h - 26) / 2), value: s.title, size: 19, weight: 600, align: 'center', w: s.w - 16, color: s.color }, svg, cells);
      if (s.sub) this.renderText({ x: s.x + s.w / 2, y: s.y + 56, value: s.sub, size: 15, align: 'center', w: s.w - 16, color: C.grey }, svg, cells);
    }

    for (const s of this.shapes.filter((v) => v.type === 'node')) {
      const edge = s.dashed ? C.hair : C.ink;
      this.rect(svg, cells, s.id, s.x, s.y, s.w, s.h, '#FFFFFF', edge, 1.8, s.dashed, 8);
      this.renderText({ x: s.x + 20, y: s.y + 12, value: s.name, size: 19, weight: 600, w: s.w - 40 }, svg, cells);
      const iconSize = s.h >= 130 ? 46 : 40;
      this.renderIcon({ id: s.id + '-i', asset: s.asset, x: s.x + 20, y: s.y + 46, w: iconSize, h: iconSize }, svg, cells);
      const px = s.x + 36 + iconSize + 20;
      const pw = s.x + s.w - 20 - px;
      const tops = s.pills.length === 1 ? [s.y + 46 + (iconSize - 32) / 2] : [s.y + 44, s.y + 84];
      s.pills.forEach((pill, i) => {
        const py = tops[i];
        svg.push(`<rect x="${px}" y="${py}" width="${pw}" height="32" rx="16" fill="#FFFFFF" stroke="${pill.color}" stroke-width="1.8"${pill.dashed ? ' stroke-dasharray="6 4"' : ''}/>`);
        cells.push(`<mxCell id="${s.id}-p${i}" value="" style="rounded=1;arcSize=50;html=1;fillColor=#FFFFFF;strokeColor=${pill.color};strokeWidth=1.8;dashed=${pill.dashed ? 1 : 0};dashPattern=6 4;" vertex="1" parent="1"><mxGeometry x="${px}" y="${py}" width="${pw}" height="32" as="geometry"/></mxCell>`);
        this.renderText({ x: px + pw / 2, y: py + 7, value: pill.text, size: 15, weight: 600, align: 'center', color: pill.color, w: pw - 8 }, svg, cells);
      });
      this.renderText({ x: s.x + 20, y: s.y + s.h - 30, value: s.sub, size: 14, color: C.grey, w: s.w - 40 }, svg, cells);
    }

    for (const s of this.shapes.filter((v) => v.type === 'icon')) this.renderIcon(s, svg, cells);
    for (const t of this.texts) this.renderText(t, svg, cells);

    this.renderText({ x: 64, y: 28, value: this.title, size: 34, weight: 600, w: 1800 }, svg, cells);
    this.renderText({ x: 64, y: 84, value: this.subtitle, size: 19, color: C.grey, w: 2200 }, svg, cells);
    this.renderText({ x: 3536, y: 36, value: 'AWS 상시면은 설계안이고 아직 배포하지 않았다', size: 16, color: C.grey, align: 'right', w: 700 }, svg, cells);

    const xml = `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><title>${esc(this.title)}</title><defs>${def}</defs><rect width="${W}" height="${H}" fill="white"/>${svg.join('\n')}</svg>`;
    writeFileSync(join(ROOT, this.id + '.svg'), xml);
    await sharp(Buffer.from(xml), { density: 144 }).png().toFile(join(ROOT, this.id + '.png'));
    writeFileSync(join(ROOT, this.id + '.drawio'), `<mxfile host="app.diagrams.net"><diagram id="${this.id}" name="${esc(this.title)}"><mxGraphModel grid="1" gridSize="10" page="1" pageScale="1" pageWidth="${W}" pageHeight="${H}" background="#FFFFFF"><root><mxCell id="0"/><mxCell id="1" parent="0"/>${cells.join('\n')}</root></mxGraphModel></diagram></mxfile>`);
    console.log(`${this.id}  SVG ${W}x${H} / PNG / draw.io`);
  }
}

function topology() {
  const p = new Page(OUT, '거래소 하이브리드 인프라 구성도',
    '평면 셋이다(정본 5). AWS 상시면 24시간 · AWS 증명면 창 구동 · 온프레미스 검증면 세션 구동. 상시 필요한 것은 AWS 에 두고 세션 중에만 필요한 것은 검증면에 둔다');

  p.icon('users', 'users', 1500, 118, 76, 'Users', { fs: 19 });

  /* ============ 왼쪽. 온프레미스 검증면. 여기 있는 것은 전부 실물이다 ============ */
  p.plane('lp', 80, 250, 1240, 1770, 'Private · 온프레미스 검증면 (세션 구동 · 비용 0)', C.green);

  p.strip('l1', 110, 320, 1180, 50, 'VMware Workstation', '한글 경로·Windows 컨테이너 마찰 제거');
  p.strip('l2', 110, 378, 1180, 50, 'Rocky Linux 10 VM', '커널 계층 주입 권한');
  p.strip('l3', 110, 436, 1180, 50, 'Docker CE', '카오스 스크립트가 직접 호출하는 런타임');

  p.group('chaos', 'private', 110, 510, 1180, 716, '카오스 스택 · compose.yaml · harness/docker-compose.chaos.yml',
    { color: C.grey, fill: '#FAFBFC', fontSize: 18 });

  const cx = [130, 520, 910];
  const chip = (id, c, r, name, sub, color, dashed) =>
    p.card(id, cx[c], r, 370, 100, name, sub, color, { dashed });
  chip('c-web', 0, 566, 'chaos-web', 'nginx · 정적 화면', RT.nginx);
  chip('c-lb', 1, 566, 'chaos-lb', 'nginx · 리더 인지 라우팅', RT.nginx);
  chip('c-a1', 2, 566, 'chaos-api-1', 'Spring Boot · 리더', RT.spring);
  chip('c-a2', 0, 676, 'chaos-api-2', 'Spring Boot · 팔로워', RT.spring);
  chip('c-pg', 1, 676, 'chaos-pg', 'PostgreSQL 17', RT.postgres);
  chip('c-ddb', 2, 676, 'chaos-dynamodb', 'DynamoDB Local · 리더 리스', RT.ddb);
  // 관리 평면은 고객 평면과 물리 분리된 배포 단위다(compose.yaml:99). 같은 격자에 두되
  // mTLS 포트를 적어 다른 평면임을 드러낸다.
  chip('c-adm', 0, 786, 'chaos-admin', '관리 평면 · mTLS 8443 · 엔진 OFF', C.orange);
  chip('c-bots', 1, 786, 'chaos-bots', '봇 플릿 · 유형 4종 · 활성 14', RT.bot);
  chip('c-tox', 2, 786, 'chaos-toxiproxy', 'DB 경로 지연·절단 · init 1회', C.red);
  chip('c-prom', 0, 896, 'chaos-prometheus', '레코딩 9 · 알람 15', RT.obs);
  chip('c-graf', 1, 896, 'chaos-grafana', '대시보드 9 · 패널 79', RT.obs);
  chip('c-am', 2, 896, 'chaos-alertmanager', '통지 라우팅', RT.obs);
  chip('c-k6', 0, 1006, 'k6 부하', 'harness/load · cleanup · preflight', C.grey);
  chip('c-bb', 1, 1006, 'blackbox_exporter', '수동 기동 · 외부 왕복', C.grey, true);
  chip('c-pgx', 2, 1006, 'postgres_exporter', '수동 기동', C.grey, true);
  chip('c-cs', 0, 1116, 'container_stats', '수동 기동', C.grey, true);

  /* 실험 축. 상시면 설계가 여기서 나왔다 */
  p.group('exp', 'private', 110, 1244, 1180, 320, '실험과 판정 · harness/evidence/ 28건',
    { color: C.red, fill: '#FDFAFA', fontSize: 18 });
  const ex = (id, c, r, name, sub) => p.card(id, cx[c], r, 370, 110, name, sub, C.red);
  ex('x-chaos', 0, 1300, '카오스 e1~e9', 'Toxiproxy · docker kill/pause · 분리');
  ex('x-repl', 1, 1300, '복제 결함', 'replication-fault');
  ex('x-sync', 2, 1300, '복제 방식별 대가', 'sync-replication');
  ex('x-fo', 0, 1424, '리더 페일오버', 'leader-failover');
  ex('x-lease', 1, 1424, '리더 리스 분리', 'leader-lease-dynamodb');
  ex('x-rejoin', 2, 1424, '부하 중 재합류', 'follower-rejoin');

  p.strip('l5', 110, 1594, 1180, 62, '복제 실험 노드 ex-pg · ex-pg2 · ex-pg3', 'ANY 1 정족수를 여기서 쟀다', C.blue);
  p.strip('l6', 110, 1668, 1180, 62, '스케일링 실험 asg-scenario · scaleout-timing', 'readnode · lb-distribution', C.orange);
  p.strip('l7', 110, 1742, 1180, 62, '판정 산출물 · harness/evidence/',
    '샘플 · 분석 · verdict 파일 배선은 M4 잔여', C.ink);

  p.text(700, 1830, '커널 계층 주입(tc netem)은 E10 이고 아직 돌리지 않았다. 관리형 계층에는 그 권한이 없다', 15,
    { align: 'center', color: C.grey, w: 1100 });
  p.text(700, 1864, '관측 스택이 지금 도는 곳은 여기뿐이다', 15, { align: 'center', color: C.grey, w: 1000 });

  /* ============ 가운데. 두 평면을 잇는 것 ============ */
  p.text(1540, 296, '온프레미스와 클라우드를', 17, { align: 'center', color: C.grey, w: 360 });
  p.text(1540, 326, '잇는 것', 21, { align: 'center', weight: 600, w: 360 });
  p.text(1540, 362, '네트워크가 아니다', 16, { align: 'center', color: C.grey, w: 360 });

  p.card('gha', 1370, 700, 340, 112, 'GitHub Actions', 'workflows 4종', C.ink);
  p.card('ghcr', 1370, 852, 340, 112, 'GHCR', '같은 이미지 digest', C.ink);
  p.card('ev', 1370, 1004, 340, 112, '커밋한 증거', 'harness/evidence/ 28건', C.ink);
  p.card('dis', 1370, 1200, 340, 112, 'Discord', '웹훅 URL 미투입', RT.obs, { dashed: true });

  p.text(1540, 1380, 'Site-to-Site VPN 비채택', 17, { align: 'center', weight: 600, color: C.red, w: 360 });
  p.text(1540, 1416, '상시 터널을 두지 않는다', 15, { align: 'center', color: C.grey, w: 360 });

  p.line([[1540, 812], [1540, 852]]);
  p.line([[1320, 756], [1370, 756]]);
  p.line([[1320, 1060], [1370, 1060]]);
  p.line([[1280, 993], [1340, 993], [1340, 1256], [1370, 1256]], { dashed: true, color: RT.obs });
  p.line([[1710, 908], [1760, 908]]);

  /* ============ 오른쪽. AWS 상시면 ============ */
  p.plane('rp', 1760, 250, 1780, 1770, 'Public · AWS 상시면 (ap-northeast-2 · 24시간)', C.ink);

  p.icon('dns', 'route53', 1836, 340, 64, 'Route 53', { fs: 17, labelWidth: 220 });
  p.icon('cdn', 'cloudfront', 1836, 500, 64, 'CloudFront', { fs: 17, sub: '2 오리진', subSize: 14, labelWidth: 220 });
  p.icon('s3', 's3', 1836, 672, 64, 'Amazon S3', { fs: 17, sub: '정적 화면', subSize: 14, labelWidth: 220 });
  p.icon('acm', 'acm', 1836, 844, 64, 'ACM', { fs: 17, sub: 'TLS 인증서', subSize: 14, labelWidth: 220 });

  p.line([[1576, 156], [1868, 156], [1868, 340]]);
  p.line([[1868, 404], [1868, 500]], { dashed: true });
  p.line([[1900, 532], [1980, 532], [1980, 704], [1900, 704]], { arrow: false, dashed: true });
  p.line([[1900, 564], [2010, 564], [2010, 474], [2060, 474]]);

  p.group('cloud', 'cloud', 2060, 310, 1440, 1550, 'AWS Cloud');
  p.group('vpc', 'vpc', 2090, 380, 1380, 1300, 'VPC · 10.20.0.0/16');

  p.text(2400, 404, 'ap-northeast-2a', 18, { align: 'center', weight: 600, color: C.grey, w: 400 });
  p.text(3160, 404, 'ap-northeast-2c', 18, { align: 'center', weight: 600, color: C.grey, w: 400 });

  p.group('albband', 'public', 2120, 434, 1320, 80, '', { fill: '#FAFCF8', dashed: true });
  p.icon('alb', 'alb', 2134, 442, 56);
  p.text(2206, 456, 'Application Load Balancer · 2 가용영역 · 타겟그룹 2', 19, { weight: 600, w: 900 });

  p.group('pub-a', 'public', 2120, 542, 560, 128, 'Public subnet', { fill: '#FAFCF8', fontSize: 16 });
  p.group('pub-c', 'public', 2880, 542, 560, 128, 'Public subnet', { fill: '#FAFCF8', fontSize: 16 });
  p.icon('nat-a', 'ec2', 2210, 584, 46, 'NAT-a  t4g.nano', { fs: 15, labelWidth: 280 });
  p.icon('nat-c', 'ec2', 2970, 584, 46, 'NAT-c  t4g.nano', { fs: 15, labelWidth: 280 });

  p.group('app-a', 'private', 2120, 700, 560, 580, 'Private subnet · Application', { fill: '#F8FBFD', fontSize: 16 });
  p.group('app-c', 'private', 2880, 700, 560, 580, 'Private subnet · Application', { fill: '#F8FBFD', fontSize: 16 });

  // ADR 0015. 그룹은 쓰고 스케일링 정책만 두지 않는다. 용량은 그 ADR 의 값이다.
  // 멤버십(리더 포함 여부)은 ADR 에 명시가 없어 상자만 두르고 주장하지 않는다.
  p.group('asg', 'asg', 2130, 746, 1300, 304, 'Auto Scaling group',
    { color: C.orange, dashed: true, fill: 'none', fontSize: 16 });

  p.text(2160, 1062, '스케일링 정책 없음 · Min 2 / Desired 2 / Max 3', 15,
    { color: C.orange, weight: 600, w: 480 });
  p.text(2160, 1096, '확장 대상은 조회 계층뿐이다. 쓰기는 단일 라이터라 늘려도 천장이 오르지 않는다', 14,
    { color: C.grey, w: 520 });

  p.node('app1', 2146, 792, 508, 140, 'app-1', 'Leader · 단일 라이터 · actuator 8081 · t4g.small', 'ec2',
    [{ text: 'Spring Boot', color: RT.spring }]);
  p.node('app2', 2906, 792, 508, 116, 'app-2', '조회 · t4g.small', 'ec2',
    [{ text: 'Spring Boot', color: RT.spring }]);
  p.node('app3', 2906, 920, 508, 116, 'app-3', '증설분 · 평시 없음', 'ec2',
    [{ text: 'Spring Boot', color: RT.spring, dashed: true }], { dashed: true });
  p.node('bot', 2906, 1080, 508, 180, 'bot-1', '봇 유형 4종 · 활성 14 · 관측 동거 ADR 0016 · t4g.small', 'ec2',
    [{ text: '봇 플릿 · Spring Boot', color: RT.bot },
     { text: 'Prometheus · Grafana · Alertmanager', color: RT.obs, dashed: true }]);

  p.group('db-a', 'private', 2120, 1310, 560, 330, 'Private subnet · Database', { fill: '#F8FBFD', fontSize: 16 });
  p.group('db-c', 'private', 2880, 1310, 560, 330, 'Private subnet · Database', { fill: '#F8FBFD', fontSize: 16 });

  p.node('pg1', 2146, 1358, 508, 140, 'pg-primary', 'Flyway V0~V34 · 자체 운영 · t4g.small', 'db',
    [{ text: 'PostgreSQL 17', color: RT.postgres }]);
  p.card('lease', 2146, 1520, 508, 96, 'leader_lease · fencing 검증',
    '트랜잭션 안의 검증은 여기 남는다', RT.postgres);
  p.node('pg2', 2906, 1358, 508, 116, 'pg-standby', '비동기 · 읽기 전용 · t4g.small · ADR 0024', 'db',
    [{ text: 'PostgreSQL 17', color: RT.postgres, dashed: true }], { dashed: true });

  p.icon('ddb', 'dynamodb', 2200, 1720, 56, 'Amazon DynamoDB', { fs: 16, sub: '리더 리스 항목 1개', subSize: 14, labelWidth: 320 });
  p.icon('ssm', 'ssm', 2620, 1720, 56, 'Systems Manager', { fs: 16, sub: '파라미터 · 세션 · SSH 폐쇄', subSize: 14, labelWidth: 340 });
  p.icon('iam', 'iam', 3040, 1720, 56, 'IAM', { fs: 16, sub: '비채택 서비스 생성 Deny', subSize: 14, labelWidth: 340 });

  /* 타겟그룹 두 갈래. 이 도면의 핵심이다(정본 6.4) */
  p.line([[2300, 514], [2300, 690], [2460, 690], [2460, 792]], { width: 2.6 });
  p.line([[3000, 514], [3000, 690], [3180, 690], [3180, 792]], { color: C.blue });
  p.line([[3060, 514], [3060, 664], [3320, 664], [3320, 920]], { color: C.blue, dashed: true });
  // 조회 타겟그룹은 리더도 대상이다. 빼면 조회 분산이 구조적으로 0 이 된다(정본 6.4).
  p.line([[2340, 514], [2340, 706], [2600, 706], [2600, 792]], { color: C.blue });
  p.text(2210, 678, '쓰기 TG · HC readiness', 14,
    { align: 'center', weight: 600, w: 280, background: true });
  p.text(2960, 678, '조회 TG · HC read', 14,
    { align: 'center', weight: 600, color: C.blue, w: 260, background: true });
  p.text(2640, 1690, '두 타겟그룹 모두 트래픽 8080 · 헬스체크 8081. 갈리는 축은 경로다', 15,
    { align: 'center', color: C.grey, w: 900 });

  /* 앱은 셋 다 primary 만 본다. standby 는 페일오버 대기다 */
  p.line([[2460, 932], [2460, 1358]]);
  p.line([[3180, 1280], [3180, 1294], [2850, 1294], [2850, 1440], [2654, 1440]], { color: C.blue });

  /* 관측은 스크레이프다. 상시면 배선이 없어 파선이다 */
  p.line([[2906, 1170], [2760, 1170], [2760, 862], [2654, 862]], { dashed: true, color: RT.obs });

  /* 비동기 스트리밍 복제. standby 상실이 primary 커밋을 멈추지 않는다(ADR 0024) */
  p.line([[2654, 1400], [2906, 1400]], { dashed: true, color: C.green });
  p.text(2780, 1500, '비동기 WAL 복제 · RPO 미보장', 15,
    { align: 'center', color: C.green, weight: 600, w: 300, background: true });

  /* 리스 갱신은 커밋 경로 밖이다(ADR 0017) */
  p.line([[2146, 892], [2100, 892], [2100, 1748], [2200, 1748]], { dashed: true, color: RT.ddb });

  /* 증명면. 같은 AWS 이지만 창 구동이고 창이 끝나면 볼륨까지 파기한다(정본 5).
     상시면과 한 상자에 담으면 그 파기 계약이 보이지 않는다. */
  p.group('proof', 'asg', 2060, 1880, 1440, 120, '증명면 · AWS 창 구동 · IaC 미배선 · 수동 기동과 파기',
    { color: C.red, dashed: true, fill: 'none', fontSize: 17 });
  p.icon('load', 'ec2', 2100, 1920, 46);
  p.text(2166, 1930, 'load  c7g.large  부하 발생기 전용 · 비버스터블 · 창이 끝나면 볼륨까지 파기한다', 16,
    { w: 1200 });
  p.line([[1320, 1940], [2060, 1940]], { dashed: true, color: C.red });
  p.text(1690, 1898, 'k6 시나리오를 같은 저장소에서 가져간다', 14,
    { align: 'center', color: C.grey, w: 460, background: true });

  /* ============ 아래. 스케일링 축과 범례 ============ */
  p.plane('ops', 80, 2020, 3460, 420, '운영 · 스케일링 · 범례', C.orange);

  /* 범례. 두 평면의 실선이 뜻하는 것이 다르다. 검증면 실선은 compose 정의이고 상시면에는
     IaC 가 0 건이라 실선이 배선을 뜻할 수 없다. 한 줄로 뭉치면 AWS 면 전체가 배선된 것으로
     읽힌다. 2026-09-08 저장소 전수 조사에서 나온 정정이다. */
  p.text(120, 2074, '범례', 19, { weight: 600, w: 200 });
  p.text(130, 2120, '실선', 17, { weight: 600, w: 100 });
  p.line([[200, 2132], [290, 2132]], { arrow: false, width: 2.4 });
  p.text(306, 2120, '검증면은 compose 에 정의가 있다', 16, { color: C.grey, w: 420 });
  p.text(130, 2166, '파선', 17, { weight: 600, w: 100 });
  p.line([[200, 2178], [290, 2178]], { arrow: false, width: 2.4, dashed: true });
  p.text(306, 2166, '배선이 없다. 검증면은 수동 기동, 상시면은 설계만', 16, { color: C.grey, w: 520 });

  p.text(130, 2216, 'AWS 상시면은 전부 설계안이다. IaC 가 0 건이라 그 면의 실선은 배선이 아니라 정본 확정을 뜻한다', 16,
    { color: C.red, w: 1500 });
  p.text(130, 2262, 'RDS · NAT Gateway · CloudWatch · EKS · WAF · Secrets Manager · Site-to-Site VPN 비채택', 16,
    { color: C.grey, w: 1500 });
  p.text(130, 2306, 'AWS 평면 오케스트레이터 미채택. 스케줄러 자동 재기동이 kill 실험 관측을 오염시킨다', 16,
    { color: C.grey, w: 1500 });

  /* 스케일링 축. 인과는 사람에서 시작한다. ADR 0015 가 자동 판정을 기각했으므로 조정기는
     버튼의 상류가 아니라 하류다. 지표는 권고를 띄울 뿐이고 값을 바꾸는 것은 사람이다. */
  p.text(1740, 2074, '스케일링 · 판정은 지표가 하고 실행은 사람이 한다 (ADR 0015)', 19, { weight: 600, w: 1300 });

  p.card('metric', 1740, 2130, 300, 116, '노드 지표', 'CPU · 요청 수 · 도달성', C.grey);
  p.card('adm', 2100, 2130, 300, 116, '관리 페이지', '권고 표시 · Scale-Out / In', C.orange);
  p.icon('op', 'users', 2220, 2264, 60, '운영자', { fs: 16, sub: '버튼을 누른다', subSize: 13, labelWidth: 220 });
  p.card('plan', 2460, 2130, 300, 116, 'capacity_plan', 'desired_nodes UPDATE', C.grey);
  p.card('recon', 2820, 2130, 320, 116, 'capacity_reconciler.py', '희망 대수를 노드로 실현', C.grey);
  p.card('sc-app', 3200, 2090, 300, 106, '조회 계층 2~3대', '로컬 ex-app2 · ex-app3', C.orange);
  p.card('sc-db', 3200, 2230, 300, 106, 'DB 수직 스케일', '정본 설계 · 콘솔 미구현', C.grey, { dashed: true });

  p.line([[2040, 2188], [2100, 2188]], { color: C.grey });
  p.line([[2250, 2264], [2250, 2246]], { color: C.orange });
  p.line([[2400, 2188], [2460, 2188]], { color: C.orange });
  p.line([[2760, 2188], [2820, 2188]], { color: C.grey });
  p.line([[3140, 2188], [3170, 2188], [3170, 2143], [3200, 2143]], { color: C.orange });
  p.line([[3140, 2188], [3170, 2188], [3170, 2283], [3200, 2283]], { dashed: true, color: C.grey });

  /* 조정기는 로컬 도커만 조작한다(asg_controller.py:41 POOL, capacity_reconciler.py).
     서버에 AWS autoscaling SDK 가 0 건이라 상시면 증감은 아직 설계다. */
  p.line([[3500, 2120], [3524, 2120], [3524, 982], [3440, 982]], { dashed: true, color: C.orange });
  p.text(3400, 1982, '앱 노드 증감 · 미배선', 15,
    { align: 'center', color: C.orange, weight: 600, w: 260, background: true });

  p.text(1740, 2404, 'asg_controller.py 는 ADR 0015 가 기각한 자동 판정 형태의 모사기다. asg-scenario 증거 전용이고 채택 경로가 아니다', 14,
    { color: C.grey, w: 1780 });

  return p;
}

await topology().save();
