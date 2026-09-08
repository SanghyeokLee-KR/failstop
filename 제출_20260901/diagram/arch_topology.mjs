/**
 * arch_topology.mjs. 시스템 구성도. 한눈에 읽는 배치도다.
 *
 * 사용:
 *   node arch_topology.mjs
 *
 * 산출: 시스템_구성도.svg / .png(4000x2280) / .drawio
 *
 * `arch_aws.mjs` 와 목적이 다르다. 그쪽은 증거를 실은 문서형 도면이라 한계·상태 전이·비용까지
 * 한 장에 담는다. 이 파일은 배치만 그린다. 심사자가 처음 5 초에 무엇이 어디에 있는지 보는 용도다.
 * 수치와 판정은 정본과 arch_aws 도면이 갖는다. 여기에 복사하지 않는다.
 *
 * 원천은 정본 `제출_20260901/기획안_원본.md` 6.3 목표 아키텍처, 6.4 네트워크와 보안그룹,
 * 6.11 리더 인지 라우팅이다. 도면은 그 절들의 파생물이고 절이 바뀌면 이 파일을 고친다.
 *
 * 아이콘은 AWS 공식 아키텍처 아이콘(2026-07-31 패키지)이다. 원본 경로는
 * `icons-aws/manifest.json` 에 있고 색과 비율은 원본을 유지한다.
 * https://aws.amazon.com/architecture/icons/
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

const ASSET_KEYS = ['ec2', 'db', 'alb', 'cloudfront', 'route53', 's3', 'users', 'cloud', 'vpc', 'public', 'private', 'region'];
const assets = Object.fromEntries(ASSET_KEYS.map((k) => [
  k, readFileSync(join(ROOT, 'icons-aws', k + '.svg'), 'utf8').replace(/<\?xml[^>]*>/, '').trim(),
]));

const W = 2000;
const H = 1140;
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
    if (o.sub) this.text(x + size / 2, y + size + (o.fs || 20) + 20, o.sub, o.subSize || 16, { align: 'center', color: C.grey, w: o.labelWidth || 240 });
  }

  group(id, kind, x, y, w, h, title, o = {}) {
    const cols = { cloud: C.ink, vpc: C.purple, public: C.green, private: C.blue, region: C.blue };
    this.shapes.push({ type: 'group', id, kind, x, y, w, h, title, color: cols[kind] || C.grey, ...o });
  }

  line(points, o = {}) {
    this.links.push({ points, ...o });
    if (o.label) this.text(o.lx, o.ly, o.label, o.fs || 18, { align: 'center', color: o.color || C.grey, w: o.lw || 240, background: o.background !== false, weight: o.bold ? 600 : 400 });
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

  async save() {
    const svg = [];
    const cells = [];
    const def = Object.entries(C).map(([k, color]) => `<marker id="${k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10z" fill="${color}"/></marker>`).join('');

    for (const s of this.shapes.filter((v) => v.type === 'group')) {
      const dash = s.dashed ?? false;
      svg.push(`<rect x="${s.x}" y="${s.y}" width="${s.w}" height="${s.h}" fill="${s.fill || '#FFFFFF'}" stroke="${s.color}" stroke-width="1.4"${dash ? ' stroke-dasharray="8 5"' : ''}/>`);
      cells.push(`<mxCell id="${s.id}" value="" style="rounded=0;html=1;fillColor=${s.fill || '#FFFFFF'};strokeColor=${s.color};strokeWidth=1.4;dashed=${dash ? 1 : 0};dashPattern=8 5;" vertex="1" parent="1"><mxGeometry x="${s.x}" y="${s.y}" width="${s.w}" height="${s.h}" as="geometry"/></mxCell>`);
      this.renderIcon({ id: s.id + '-mark', asset: s.kind, x: s.x + 14, y: s.y + 12, w: 28, h: 28 }, svg, cells);
      this.renderText({ x: s.x + 52, y: s.y + 14, value: s.title, size: s.fontSize || 19, color: s.color, weight: 600, w: s.w - 70 }, svg, cells);
    }

    for (const l of this.links) {
      const color = l.color || C.ink;
      const width = l.width || 2;
      const key = Object.entries(C).find(([, v]) => v === color)?.[0] || 'ink';
      svg.push(`<polyline points="${l.points.map((p) => p.join(',')).join(' ')}" fill="none" stroke="${color}" stroke-width="${width}"${l.dashed ? ' stroke-dasharray="8 5"' : ''}${l.arrow === false ? '' : ` marker-end="url(#${key})"`}/>`);
      const start = l.points[0];
      const end = l.points.at(-1);
      const pts = l.points.slice(1, -1).map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('');
      cells.push(`<mxCell id="e${this.n++}" style="edgeStyle=none;html=1;rounded=0;strokeColor=${color};strokeWidth=${width};endArrow=${l.arrow === false ? 'none' : 'block'};endFill=1;dashed=${l.dashed ? 1 : 0};" edge="1" parent="1"><mxGeometry relative="1" as="geometry"><mxPoint x="${start[0]}" y="${start[1]}" as="sourcePoint"/><mxPoint x="${end[0]}" y="${end[1]}" as="targetPoint"/>${pts ? `<Array as="points">${pts}</Array>` : ''}</mxGeometry></mxCell>`);
    }

    for (const s of this.shapes.filter((v) => v.type === 'icon')) this.renderIcon(s, svg, cells);
    for (const t of this.texts) this.renderText(t, svg, cells);

    this.renderText({ x: 64, y: 34, value: this.title, size: 32, weight: 600, w: 1500 }, svg, cells);
    this.renderText({ x: 64, y: 82, value: this.subtitle, size: 18, color: C.grey, w: 1500 }, svg, cells);
    this.renderText({ x: 1936, y: 42, value: '설계안 · 클라우드 미배포', size: 16, color: C.grey, align: 'right', w: 400 }, svg, cells);

    const xml = `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><title>${esc(this.title)}</title><defs>${def}</defs><rect width="${W}" height="${H}" fill="white"/>${svg.join('\n')}</svg>`;
    writeFileSync(join(ROOT, this.id + '.svg'), xml);
    await sharp(Buffer.from(xml), { density: 144 }).png().toFile(join(ROOT, this.id + '.png'));
    writeFileSync(join(ROOT, this.id + '.drawio'), `<mxfile host="app.diagrams.net"><diagram id="${this.id}" name="${esc(this.title)}"><mxGraphModel grid="1" gridSize="10" page="1" pageScale="1" pageWidth="${W}" pageHeight="${H}" background="#FFFFFF"><root><mxCell id="0"/><mxCell id="1" parent="0"/>${cells.join('\n')}</root></mxGraphModel></diagram></mxfile>`);
    console.log(`${this.id}  PNG 4000x2280 / SVG / draw.io`);
  }
}

function topology() {
  const p = new Page(OUT, '거래소 시스템 구성도', 'AWS ap-northeast-2 · 2 가용영역 · 매칭엔진 단일 라이터');

  /* ===== 경계. 정본 6.4 서브넷 표 그대로다 ===== */
  p.group('cloud', 'cloud', 250, 130, 1690, 966, 'AWS Cloud');
  p.group('vpc', 'vpc', 300, 372, 1600, 706, 'VPC · ap-northeast-2   10.20.0.0/16');
  p.group('public', 'public', 350, 446, 1500, 174, 'Public subnets · AZ-a / AZ-c', { fill: '#FAFCF8', fontSize: 18 });
  p.group('app-a', 'private', 350, 684, 730, 182, 'Private app · AZ-a', { fill: '#F8FBFD', fontSize: 18 });
  p.group('app-c', 'private', 1120, 684, 730, 182, 'Private app · AZ-c', { fill: '#F8FBFD', fontSize: 18 });
  p.group('db-a', 'private', 350, 890, 730, 170, 'Private DB · AZ-a', { fill: '#F8FBFD', fontSize: 18 });
  p.group('db-c', 'private', 1120, 890, 730, 170, 'Private DB · AZ-c', { fill: '#F8FBFD', fontSize: 18 });

  /* ===== 외부 ===== */
  p.icon('users', 'users', 70, 470, 84, 'Users', { fs: 22 });
  p.icon('dns', 'route53', 370, 200, 72, 'Route 53', { fs: 21 });
  p.icon('cdn', 'cloudfront', 660, 200, 72, 'CloudFront', { fs: 21 });
  p.icon('s3', 's3', 1210, 200, 72, 'Amazon S3', { fs: 21, sub: '정적 화면', labelWidth: 260 });

  /* ===== 퍼블릭 ===== */
  p.icon('alb', 'alb', 1056, 470, 86);
  p.text(1036, 502, 'Application Load Balancer', 20, { align: 'right', weight: 600, w: 420 });
  p.icon('nat-a', 'ec2', 470, 490, 56, 'NAT-a', { fs: 18, sub: '아웃바운드 · t4g.nano', subSize: 15, labelWidth: 230 });
  p.icon('nat-c', 'ec2', 1690, 490, 56, 'NAT-c', { fs: 18, sub: '아웃바운드 · t4g.nano', subSize: 15, labelWidth: 230 });

  /* ===== 앱 ===== */
  p.icon('app1', 'ec2', 640, 710, 64, 'app-1', { fs: 20, sub: 'Leader · t4g.small', labelWidth: 250 });
  p.icon('app2', 'ec2', 1180, 710, 64, 'app-2', { fs: 20, sub: 'Follower · t4g.small', labelWidth: 250 });
  p.icon('obs', 'ec2', 1420, 714, 56, '관측', { fs: 18, sub: 'Prometheus · Grafana', subSize: 15, labelWidth: 230 });
  p.icon('bot', 'ec2', 1660, 714, 56, 'bot-1', { fs: 18, sub: '봇 · t4g.micro', subSize: 15, labelWidth: 190 });

  /* ===== DB ===== */
  p.icon('pg1', 'db', 760, 936, 62, 'PostgreSQL primary', { fs: 19, sub: 't4g.small', subSize: 15, labelWidth: 260 });
  p.icon('pg2', 'db', 1240, 936, 62, 'PostgreSQL standby', { fs: 19, sub: 't4g.small', subSize: 15, labelWidth: 260 });

  /* ===== 외부 진입 ===== */
  p.line([[154, 512], [212, 512], [212, 330], [612, 330], [612, 254], [660, 254]], { label: 'HTTPS', lx: 300, ly: 296, lw: 100 });
  p.line([[442, 220], [660, 220]], { dashed: true, label: 'DNS', lx: 551, ly: 184, lw: 80 });
  p.line([[732, 220], [1210, 220]], { label: '/*  정적', lx: 971, ly: 184, lw: 150 });
  p.line([[732, 254], [828, 254], [828, 398], [1099, 398], [1099, 470]], { label: '/api/*   /ws/*', lx: 963, ly: 330, lw: 200 });

  /* ===== 타겟그룹 두 갈래. 이 도면의 핵심이다 ===== */
  p.line([[1078, 556], [1078, 652], [658, 652], [658, 710]], { width: 2.6, color: C.ink });
  p.line([[1099, 556], [1099, 666], [690, 666], [690, 710]], { color: C.blue });
  p.line([[1120, 556], [1120, 666], [1212, 666], [1212, 710]], { color: C.blue });
  p.text(860, 624, '쓰기 · 리더 1대', 17, { align: 'center', color: C.ink, weight: 600, w: 240, background: true });
  p.text(1380, 624, '조회 · 2대 분산', 17, { align: 'center', color: C.blue, weight: 600, w: 240, background: true });

  /* ===== 앱에서 DB. 조회도 primary 를 읽는다. standby 는 페일오버 대기다(정본 6.5) ===== */
  p.line([[672, 866], [672, 878], [775, 878], [775, 936]]);
  p.line([[1212, 866], [1212, 878], [805, 878], [805, 936]], { color: C.blue });

  /* ===== 복제 ===== */
  p.line([[826, 967], [1236, 967]], { dashed: true, color: C.green });
  p.text(1070, 930, 'streaming replication', 16, { align: 'right', color: C.green, w: 300 });

  /* ===== 비채택. 다른 구성과 갈리는 지점이라 한 줄만 남긴다 ===== */
  p.text(1936, 1104, 'RDS · NAT Gateway · EKS 비채택   ·   SSH 미개방, SSM Session Manager 단독', 15, { align: 'right', color: C.grey, w: 950 });

  return p;
}

await topology().save();
