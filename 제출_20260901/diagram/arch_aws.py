# AWS 아키텍처 다이어그램. 공식 그룹 표기 규약(테두리 색·선종)을 따른다.
# 좌표는 10 단위 격자에 맞추고 같은 행의 아이콘은 기준선을 공유한다.
# 캔버스 비율 2.1:1 은 16:9 슬라이드에서 제목을 뺀 본문 영역 비율이다.
#
# 그리는 대상은 기획안 6.3 목표 아키텍처다. 문서가 정본이고 이 파일은 그 파생이다.
# 문서가 명시적으로 기각한 관리형 서비스(ALB·NAT Gateway·RDS·CloudWatch·WAF)는
# 그리지 않는다. 9.10 이 IAM Deny 로 생성 자체를 막으므로 도면에 있으면 거짓이 된다.
# 대신 비채택 사유와 절감액을 별도 블록으로 낸다. 안 쓴 것도 설계 결정이다.
import base64, glob, io, os, sys

# 슬라이드용은 도면 제목을 뺀다. 슬라이드 제목과 중복되기 때문이다
SLIDE = '--slide' in sys.argv

ICON = r'C:\Users\DEV-PC\AppData\Local\Programs\Python\Python314\Lib\site-packages\resources'
I = {
    'user': 'aws/general/user.png', 'users': 'aws/general/users.png',
    'route53': 'aws/network/route-53.png', 'cloudfront': 'aws/network/cloudfront.png',
    'acm': 'aws/security/certificate-manager.png',
    's3': 'aws/storage/simple-storage-service-s3.png', 'igw': 'aws/network/internet-gateway.png',
    'ssm': 'aws/management/systems-manager.png', 'ec2': 'aws/compute/ec2-instance.png',
    'nginx': 'onprem/network/nginx.png', 'spring': 'programming/framework/spring.png',
    'pg': 'onprem/database/postgresql.png', 'prom': 'onprem/monitoring/prometheus.png',
    'grafana': 'onprem/monitoring/grafana.png', 'loki': 'onprem/logging/loki.png',
    'k3s': 'onprem/container/k3s.png', 'docker': 'onprem/container/docker.png',
    'vpn': 'generic/network/vpn.png', 'fw': 'generic/network/firewall.png',
    'linux': 'generic/os/red-hat.png',
}
_c = {}
def icon(k):
    if k not in _c:
        p = os.path.join(ICON, I[k].replace('/', os.sep))
        if not os.path.exists(p):
            d, b = os.path.dirname(p), os.path.basename(p)[:-4]
            p = [x for x in glob.glob(os.path.join(d, b + '*.png')) if 'rounded' not in x][0]
        _c[k] = base64.b64encode(open(p, 'rb').read()).decode()
    return _c[k]

# AWS 공식 그룹 색. 채움은 거의 무채색으로 눌러 아이콘 색이 튀지 않게 한다
CLOUD, REGION, VPCC = '#232F3E', '#00A4A6', '#8C4FFF'
PUB, PRV, CORP, SG = '#7AA116', '#00A4A6', '#5A6B75', '#C7392E'
PROOF = '#B8730A'
INK, BODY, MUTE, HAIR = '#16191F', '#4A545B', '#8C979C', '#E3E8EA'
FLOW = '#404B54'
KO = "'Malgun Gothic','맑은 고딕',sans-serif"

W, H = 2100, (938 if SLIDE else 1000)
SHIFT = 62 if SLIDE else 0
o = []
def add(s): o.append(s)

def group(x, y, w, h, label, color, fill='none', dash=None, lw=1.7, badge=True):
    d = f' stroke-dasharray="{dash}"' if dash else ''
    add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="4" fill="{fill}" stroke="{color}" stroke-width="{lw}"{d}/>')
    if label:
        if badge:
            add(f'<rect x="{x+9}" y="{y+9}" width="13" height="13" rx="2" fill="{color}"/>')
            add(f'<text x="{x+28}" y="{y+20}" font-family="{KO}" font-size="11" font-weight="700" fill="{color}">{label}</text>')
        else:
            add(f'<text x="{x+10}" y="{y+18}" font-family="{KO}" font-size="10" font-weight="700" fill="{color}">{label}</text>')

def node(cx, cy, k, l1=None, l2=None, s=36):
    add(f'<image x="{cx-s/2}" y="{cy-s/2}" width="{s}" height="{s}" xlink:href="data:image/png;base64,{icon(k)}"/>')
    if l1: add(f'<text x="{cx}" y="{cy+s/2+14}" font-family="{KO}" font-size="10" fill="{INK}" text-anchor="middle">{l1}</text>')
    if l2: add(f'<text x="{cx}" y="{cy+s/2+26}" font-family="{KO}" font-size="8.6" fill="{MUTE}" text-anchor="middle">{l2}</text>')

def ln(pts, c=FLOW, w=1.5, dash=None, head=True):
    d = f' stroke-dasharray="{dash}"' if dash else ''
    m = f' marker-end="url(#h{c[1:]})"' if head else ''
    add(f'<polyline points="{" ".join(f"{a},{b}" for a, b in pts)}" fill="none" stroke="{c}" stroke-width="{w}"{d}{m}/>')

def step(cx, cy, n, c=FLOW):
    add(f'<circle cx="{cx}" cy="{cy}" r="9" fill="#FFFFFF" stroke="{c}" stroke-width="1.5"/>')
    add(f'<text x="{cx}" y="{cy+3.5}" font-family="{KO}" font-size="10" font-weight="700" fill="{c}" text-anchor="middle">{n}</text>')

def txt(x, y, s, size=10, c=BODY, w='400', an='start'):
    add(f'<text x="{x}" y="{y}" font-family="{KO}" font-size="{size}" font-weight="{w}" fill="{c}" text-anchor="{an}">{s}</text>')

def chip(cx, cy, w, h, t1, t2, c):
    add(f'<rect x="{cx-w/2}" y="{cy-h/2}" width="{w}" height="{h}" rx="3" fill="#FFFFFF" stroke="{c}" stroke-width="1.1"/>')
    txt(cx, cy - 2, t1, 10, c, '700', 'middle')
    txt(cx, cy + 13, t2, 8.6, MUTE, '400', 'middle')

add(f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{W}" height="{H}" viewBox="0 0 {W} {H}"><rect width="{W}" height="{H}" fill="#FFFFFF"/><defs>')
for c in [FLOW, VPCC, SG, PUB, CORP, PROOF]:
    add(f'<marker id="h{c[1:]}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0,1.2 L9,5 L0,8.8 z" fill="{c}"/></marker>')
add('</defs>')
add(f'<g transform="translate(0,{-SHIFT})">')

R_EDGE, R_PUB, R_C1, R_C2 = 195, 418, 610, 728

if not SLIDE:
    txt(24, 36, '고가용성 가상 암호화폐 거래소  ·  목표 아키텍처', 19, INK, '700')
    txt(24, 55, 'AWS ap-northeast-2 상시면 · 증명면   |   온프레미스 검증면(로컬 Rocky Linux 10)   |   WireGuard 터널', 10.5, MUTE)

node(58, R_EDGE, 'users', '사용자', None, 36)

# ================= AWS Cloud =================
CX, CY, CW, CH = 110, 76, 1090, 800
group(CX, CY, CW, CH, 'AWS Cloud', CLOUD, '#FFFFFF', lw=2)
group(CX+16, CY+32, CW-32, CH-48, 'Region  ap-northeast-2', REGION, 'none', '6 4', 1.4)
txt(CX+CW-14, CY+21, '상시면 USD 54.01 /월   ·   증명면 USD 0.2256 /hr (창 구동)', 9.6, MUTE, '600', 'end')

node(220, R_EDGE, 'route53', 'Route 53', 'sang.it.kr', 34)
node(400, R_EDGE, 'cloudfront', 'CloudFront', None, 34)
node(400, 122, 'acm', 'ACM', 'us-east-1', 26)
node(680, R_EDGE, 's3', 'Amazon S3', 'OAC · 정적 66페이지', 34)

ln([(80, R_EDGE), (202, R_EDGE)], FLOW, 1.5)
ln([(238, R_EDGE), (382, R_EDGE)], FLOW, 1.5)
ln([(418, R_EDGE), (662, R_EDGE)], FLOW, 1.5)
ln([(400, 136), (400, R_EDGE-20)], MUTE, 1.2, '4 3', head=False)
txt(540, R_EDGE-9, '/*   CachingOptimized', 8.8, MUTE, '400', 'middle')
step(202, R_EDGE-40, '1'); step(418, R_EDGE-40, '2')

# /api /ws 오리진 분기. CloudFront 아래로 내려 VPC 엣지로 들어간다
ln([(400, R_EDGE+22), (400, 248), (268, 248), (268, 356)], FLOW, 1.6)
txt(410, 243, '/api/*   /ws/*    CachingDisabled · AllViewer', 9, FLOW, '600')
step(268, 306, '3')

# ================= VPC =================
VX, VY, VW, VH = 146, 262, 1020, 592
group(VX, VY, VW, VH, 'VPC  10.20.0.0/16', VPCC, '#FFFFFF')
node(1120, 296, 'igw', None, None, 26)
txt(1102, 300, 'Internet gateway', 9.4, BODY, '400', 'end')

group(166, 326, 980, 168, '퍼블릭 서브넷  public-a  10.20.0.0/24', PUB, '#FBFCF7', lw=1.4, badge=False)
group(190, 356, 900, 124, 'edge  ·  t4g.micro', CORP, '#FFFFFF', '4 3', 1.2, badge=False)
for i, (k, a, b) in enumerate([('nginx', 'nginx', 'TLS 종단 · 리더 인지'), ('fw', 'nftables', 'masquerade(NAT)'),
                               ('vpn', 'WireGuard', '터널 종단'), ('prom', 'exporters', 'node · blackbox'),
                               ('ssm', 'SSM', '배스천')]):
    node(268 + i*168, R_PUB, k, a, b, 28)

group(166, 510, 980, 330, '프라이빗 서브넷  private-a  10.20.10.0/24', PRV, '#F8FBFB', lw=1.4, badge=False)

group(190, 542, 452, 282, 'core  ·  t4g.medium  ·  상시 구동', CORP, '#FFFFFF', '4 3', 1.2, badge=False)
node(280, R_C1, 'spring', 'exchange-app', '리더 · 8080 / 8081', 30)
node(440, R_C1, 'pg', 'PostgreSQL 17', 'primary', 30)
ln([(298, R_C1), (422, R_C1)], FLOW, 1.4)
for i, (k, a) in enumerate([('prom', 'Prometheus'), ('loki', 'Loki'), ('grafana', 'Grafana')]):
    node(280 + i*130, R_C2, k, a, None, 26)
txt(410, R_C2 + 48, '레코딩 룰 9 · 알람 11 · 대시보드 4종', 8.6, MUTE, '400', 'middle')

group(666, 542, 456, 282, '증명면  ·  terraform apply / destroy  ·  창 구동 후 파기', PROOF, '#FEFBF6', '6 4', 1.4, badge=False)
for i, (k, a, b) in enumerate([('ec2', 'app-2', 'c7g.large'), ('pg', 'pg-standby', 't4g.small'),
                               ('prom', 'obs', 't4g.medium'), ('ec2', 'load', 'c7g.large')]):
    node(766 + (i % 2)*226, R_C1 + (i // 2)*118, k, a, b, 28)
step(1098, 542, '5', PROOF)
txt(894, 806, 'AMI 에서 매 회차 생성 · 정지가 아니라 파기 · 상시 전환 시 11.2배', 8.8, MUTE, '400', 'middle')

ln([(268, 480), (268, 542)], FLOW, 1.4)
ln([(1090, 418), (1120, 418), (1120, 316)], FLOW, 1.3)
step(1120, 370, '4')

# ================= WireGuard 터널 =================
TX = 1250
ln([(1166, 418), (TX-30, 418)], VPCC, 1.9, '7 4', head=False)
ln([(TX+30, 418), (1300, 418)], VPCC, 1.9, '7 4')
node(TX, 418, 'vpn', None, None, 30)
txt(TX, 366, 'WireGuard 터널', 9.5, VPCC, '700', 'middle')
txt(TX, 379, 'IPsec S2S 대체 · 비용 0', 8.4, MUTE, '400', 'middle')
step(TX, 470, '6', VPCC)

# ================= 온프레미스 검증면 =================
DX, DW = 1300, 776
group(DX, CY, DW, 470, '온프레미스 검증면  ·  로컬 Rocky Linux 10 (VMware)', CORP, '#FFFFFF', lw=2)
node(DX+52, 152, 'linux', 'Rocky 10', None, 28)
txt(DX+96, 142, '동일 배포판. nftables · SELinux 거동이 클라우드와 같다', 9.4, BODY)
txt(DX+96, 158, '앱 이미지는 GHCR 동일 digest 를 내려받는다. 로컬 빌드본을 올리지 않는다', 9.4, MUTE)

group(DX+24, 186, DW-48, 132, 'k3s 클러스터', CORP, '#FCFDFD', '4 3', 1.2, badge=False)
node(DX+96, 252, 'k3s', 'k3s server', 'control-plane', 28)
for i in range(2):
    node(DX+236 + i*104, 252, 'docker', f'agent-{i+1}', 'containerd', 26)
node(DX+488, 252, 'pg', 'PostgreSQL 17', '동일 태그 고정', 26)
node(DX+600, 252, 'prom', '관측', 'Prom · Loki', 26)
ln([(DX+114, 252), (DX+218, 252)], CORP, 1.3)

group(DX+24, 336, DW-48, 118, '장애 주입 지점  ·  커널 계층까지 도달한다', SG, '#FDF7F6', '5 3', 1.4, badge=False)
for i, (a, b) in enumerate([('tc netem', 'L3  지연 · 손실'), ('nftables', 'L4  drop · reject'),
                            ('앱 지연 · kill', 'L7  예외 주입')]):
    chip(DX + 152 + i*236, 410, 208, 46, a, b, SG)
step(DX+44, 336, '7', SG)

# ================= 비채택 =================
group(DX, 570, DW, 160, '비채택 관리형 서비스  ·  IAM Deny 로 생성 차단(9.10)', MUTE, '#FAFBFB', lw=1.4)
for i, (a, b) in enumerate([('ALB', '22.27'), ('NAT Gateway x2', '86.14'), ('RDS Multi-AZ', '74.46'),
                            ('CloudWatch', '약 190'), ('WAF', '증거 얇음'), ('EKS', '73.00')]):
    x = DX + 40 + (i % 3)*248
    y = 616 + (i // 3)*44
    add(f'<rect x="{x}" y="{y}" width="222" height="32" rx="3" fill="#FFFFFF" stroke="{HAIR}" stroke-width="1.1"/>')
    add(f'<line x1="{x+10}" y1="{y+16}" x2="{x+118}" y2="{y+16}" stroke="{SG}" stroke-width="1.3"/>')
    txt(x + 12, y + 13, a, 9.6, MUTE, '600')
    txt(x + 212, y + 21, f'USD {b} /월', 9, BODY, '400', 'end')
txt(DX + 40, 718, '관리형을 쓰면 페일오버 내부가 닫혀 복제 링크에 tc netem 을 걸 수 없다. 비용 회피가 아니라 증명 범위의 문제다.', 9.2, BODY)

# ================= 접속 경로 =================
group(DX, 754, DW, 122, '운영자 접속  ·  SSH 22 전 노드 미개방', CORP, '#FCFDFD', lw=1.4)
node(DX+48, 820, 'user', '운영자', None, 26)
node(DX+156, 820, 'ssm', 'SSM', 'Session Manager', 26)
ln([(DX+66, 820), (DX+138, 820)], CORP, 1.4)
for i, (a, b) in enumerate([('관리 평면 8443', 'mTLS client-auth'), ('Prometheus 9090', '포트 포워딩'),
                            ('Grafana 3000', '포트 포워딩')]):
    chip(DX + 320 + i*160, 818, 150, 42, a, b, CORP)
ln([(DX+174, 820), (DX+244, 820)], CORP, 1.4)
step(DX+744, 772, '8', CORP)

# ================= 범례 =================
LY = 892
add(f'<line x1="24" y1="{LY}" x2="{W-24}" y2="{LY}" stroke="{HAIR}" stroke-width="1.2"/>')
for i, (n, d) in enumerate([('1', 'Route 53 가 도메인을 CloudFront 로 보낸다'),
                            ('2', '기본 경로는 S3 정적 66페이지. EC2 전멸에도 웹은 200'),
                            ('3', 'API·WS 만 오리진으로. same-origin 이라 CORS 가 0이다'),
                            ('4', 'edge 의 nftables masquerade 가 NAT Gateway 를 대신한다'),
                            ('5', '증명면은 실험 창에만 생성하고 볼륨까지 파기한다'),
                            ('6', 'WireGuard 로 클라우드와 온프레미스를 잇는다'),
                            ('7', '커널 계층 주입은 검증면에서만. 상시면은 비파괴 실험만'),
                            ('8', 'SSH 미개방. 관리 평면은 SSM 포트 포워딩으로만 닿는다')]):
    x = 40 + (i % 4)*520
    y = LY + 32 + (i // 4)*32
    step(x, y-4, n)
    txt(x+18, y, d, 10.2, BODY)

add('</g>')
add('</svg>')
out = 'arch_aws_slide.svg' if SLIDE else 'arch_aws.svg'
io.open(out, 'w', encoding='utf-8').write(chr(10).join(o))
print(f'{out}  {W}x{H}  비율 {W/H:.2f}')
