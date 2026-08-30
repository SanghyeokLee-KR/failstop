# AWS 아키텍처 다이어그램. 공식 그룹 표기 규약(테두리 색·선종)을 따른다.
# 좌표는 10 단위 격자에 맞추고 같은 행의 아이콘은 기준선을 공유한다.
# 캔버스 비율 2.1:1 은 16:9 슬라이드에서 제목을 뺀 본문 영역 비율이다.
#
# 그리는 대상은 기획안 6.3 목표 아키텍처다. 문서가 정본이고 이 파일은 그 파생이다.
# 기각한 관리형(NAT Gateway·RDS·CloudWatch·WAF·EKS)은 그리지 않는다. 9.10 이 IAM Deny 로
# 생성 자체를 막으므로 도면에 있으면 거짓이 된다. 대신 비채택 사유와 절감액을 블록으로 낸다.
#
# ALB 는 채택으로 뒤집었다. 종전 근거는 리더 인지 라우팅을 nginx 로 직접 짜야 한다는 것이었는데,
# API 설계 50행이 이미 readiness=REFUSING_TRAFFIC 프로브 기반 라우팅을 규정한다. 팔로워는
# readiness 가 503 이라 타겟그룹 헬스체크가 그대로 읽어 리더에게만 보낸다. nginx 사이드카는
# ALB 가 기본으로 하는 일을 다시 만든 것이었고 M4 하드닝 246행에 미완으로 남아 있었다.
# 더 큰 이유는 종전 구조의 edge 단일 노드가 단일 장애점이었다는 것이다.
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
    'alb': 'aws/network/elb-application-load-balancer.png',
    'tg': 'aws/network/elastic-load-balancing.png',
    'spring': 'programming/framework/spring.png',
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

R_EDGE = 195

if not SLIDE:
    txt(24, 36, '고가용성 가상 암호화폐 거래소  ·  목표 아키텍처', 19, INK, '700')
    txt(24, 55, 'AWS ap-northeast-2 · ALB 2 가용영역 · 앱 2대 상시   |   온프레미스 검증면(로컬 Rocky Linux 10)   |   WireGuard 터널', 10.5, MUTE)

node(58, R_EDGE, 'users', '사용자', None, 36)

# ================= AWS Cloud =================
CX, CY, CW, CH = 110, 76, 1090, 800
group(CX, CY, CW, CH, 'AWS Cloud', CLOUD, '#FFFFFF', lw=2)
group(CX+16, CY+32, CW-32, CH-48, 'Region  ap-northeast-2', REGION, 'none', '6 4', 1.4)
txt(CX+CW-14, CY+21, '상시면 USD 77.9   ·   증명면 USD 0.1584 /hr (창 구동)', 9.6, MUTE, '600', 'end')

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

ln([(400, R_EDGE+22), (400, 244), (300, 244), (300, 352)], FLOW, 1.6)
txt(414, 248, '/api/*   /ws/*    CachingDisabled · AllViewer', 9, FLOW, '600')
step(300, 300, '3')

# ================= VPC =================
group(146, 262, 1020, 592, 'VPC  10.20.0.0/16', VPCC, '#FFFFFF')
node(1120, 296, 'igw', None, None, 26)
txt(1102, 300, 'Internet gateway', 9.4, BODY, '400', 'end')

# ALB 는 서로 다른 두 가용영역의 서브넷을 최소 2개 요구한다. ALB 채택이 곧 Multi-AZ 강제다
group(166, 320, 980, 122, '퍼블릭 서브넷   10.20.0.0/24 (AZ-a)   ·   10.20.1.0/24 (AZ-c)', PUB, '#FBFCF7', lw=1.4, badge=False)
node(300, 386, 'alb', 'ALB', 'ACM TLS 종단 · 2 AZ 필수', 30)
node(570, 386, 'tg', 'Target Group', '헬스체크 /health/readiness', 28)
node(890, 386, 'fw', 'NAT 인스턴스', 't4g.nano · nftables', 28)
ln([(318, 386), (552, 386)], FLOW, 1.4)
ln([(1010, 386), (1108, 386), (1108, 316)], FLOW, 1.3)
step(1058, 386, '5')

# 프라이빗 2 AZ. 앱을 두 대 상시로 두어 리더 kill 을 상시면에서 라이브로 할 수 있다
group(166, 462, 482, 250, '프라이빗 서브넷  10.20.10.0/24  ·  AZ-a', PRV, '#F8FBFB', lw=1.4, badge=False)
node(268, 542, 'spring', 'app-1', 't4g.small · 리더', 30)
node(478, 542, 'pg', 'PostgreSQL 17', 'primary · t4g.medium', 30)
ln([(286, 542), (460, 542)], FLOW, 1.4)
for i, (k, a) in enumerate([('prom', 'Prometheus'), ('loki', 'Loki'), ('grafana', 'Grafana')]):
    node(258 + i*128, 650, k, a, None, 24)
txt(386, 686, '레코딩 룰 9 · 알람 11 · 대시보드 4종', 8.6, MUTE, '400', 'middle')

group(664, 462, 482, 250, '프라이빗 서브넷  10.20.11.0/24  ·  AZ-c', PRV, '#F8FBFB', lw=1.4, badge=False)
node(760, 542, 'spring', 'app-2', 't4g.small · 팔로워', 30)
txt(848, 526, '팔로워는 readiness 가 REFUSING_TRAFFIC 이라', 9, BODY)
txt(848, 542, '타겟그룹이 자동으로 뺀다. 승격하면 스스로', 9, BODY)
txt(848, 558, 'ACCEPTING 으로 바꿔 다시 들어온다', 9, BODY)
txt(686, 646, '리더 kill 은 상시면에서 한다. 자동 복구가 30초 이내라', 9, MUTE)
txt(686, 662, '외부 접속 요건과 양립한다(6.1). 발표에서 도메인을 열어둔', 9, MUTE)
txt(686, 678, '채로 한 대를 죽이고 타겟그룹 제외를 콘솔로 함께 띄운다.', 9, MUTE)

# 서브넷 상자 아래에서 갈래를 낸다. 아이콘에서 바로 내리면 ALB 부제를 선이 관통한다
ln([(570, 442), (570, 458), (268, 458), (268, 524)], FLOW, 1.4)
ln([(570, 458), (760, 458), (760, 524)], FLOW, 1.4)
step(412, 458, '4')

group(166, 730, 980, 108, '증명면  ·  terraform apply / destroy  ·  창 구동 후 볼륨까지 파기', PROOF, '#FEFBF6', '6 4', 1.4, badge=False)
for i, (k, a, b) in enumerate([('pg', 'pg-standby', 't4g.small'), ('prom', 'obs', 't4g.medium'), ('ec2', 'load', 'c7g.large')]):
    node(360 + i*280, 792, k, a, b, 28)
step(1126, 730, '6', PROOF)

# ================= WireGuard 터널 =================
TX = 1250
ln([(1166, 542), (TX-30, 542)], VPCC, 1.9, '7 4', head=False)
ln([(TX+30, 542), (1300, 542)], VPCC, 1.9, '7 4')
node(TX, 542, 'vpn', None, None, 30)
txt(TX, 490, 'WireGuard 터널', 9.5, VPCC, '700', 'middle')
txt(TX, 503, 'IPsec S2S 대체 · 비용 0', 8.4, MUTE, '400', 'middle')
step(TX, 594, '7', VPCC)

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
step(DX+44, 336, '8', SG)

# ================= 비채택 =================
group(DX, 570, DW, 160, '비채택 관리형 서비스  ·  IAM Deny 로 생성 차단(9.10)', MUTE, '#FAFBFB', lw=1.4)
for i, (a, b) in enumerate([('NAT Gateway x2', '86.14'), ('RDS Multi-AZ', '74.46'), ('EKS', '73.00'),
                            ('CloudWatch', '약 190'), ('WAF', '증거 얇음')]):
    x = DX + 40 + (i % 3)*248
    y = 616 + (i // 3)*44
    add(f'<rect x="{x}" y="{y}" width="222" height="32" rx="3" fill="#FFFFFF" stroke="{HAIR}" stroke-width="1.1"/>')
    add(f'<line x1="{x+10}" y1="{y+16}" x2="{x+118}" y2="{y+16}" stroke="{SG}" stroke-width="1.3"/>')
    txt(x + 12, y + 13, a, 9.6, MUTE, '600')
    txt(x + 212, y + 21, f'USD {b} /월', 9, BODY, '400', 'end')
txt(DX + 536, 682, 'RDS 는 페일오버 내부가 닫혀', 9.2, BODY)
txt(DX + 536, 698, '복제 링크에 tc netem 을 못 건다', 9.2, BODY)
txt(DX + 40, 718, 'ALB 는 채택했다. 타겟그룹 헬스체크가 readiness 를 읽어 리더 라우팅을 대신하고, 두 AZ 서브넷을 요구해 Multi-AZ 가 강제된다.', 9.2, BODY)

# ================= 접속 경로 =================
group(DX, 754, DW, 122, '운영자 접속  ·  SSH 22 전 노드 미개방', CORP, '#FCFDFD', lw=1.4)
node(DX+48, 822, 'user', '운영자', None, 26)
node(DX+156, 822, 'ssm', 'SSM', 'Session Manager', 26)
ln([(DX+66, 822), (DX+138, 822)], CORP, 1.4)
for i, (a, b) in enumerate([('관리 평면 8443', 'mTLS client-auth'), ('Prometheus 9090', '포트 포워딩'),
                            ('Grafana 3000', '포트 포워딩')]):
    chip(DX + 320 + i*160, 820, 150, 42, a, b, CORP)
ln([(DX+174, 822), (DX+244, 822)], CORP, 1.4)
step(DX+744, 772, '9', CORP)

# ================= 범례 =================
LY = 892
add(f'<line x1="24" y1="{LY}" x2="{W-24}" y2="{LY}" stroke="{HAIR}" stroke-width="1.2"/>')
for i, (n, d) in enumerate([('1', 'Route 53 가 도메인을 CloudFront 로 보낸다'),
                            ('2', '기본 경로는 S3 정적 66페이지. EC2 전멸에도 웹은 200'),
                            ('3', 'API·WS 만 ALB 오리진으로. same-origin 이라 CORS 가 0'),
                            ('4', '타겟그룹이 readiness 를 읽어 리더에게만 보낸다'),
                            ('5', 'NAT 인스턴스가 NAT Gateway 를 대신한다. 월 86 절감'),
                            ('6', '증명면은 실험 창에만 만들고 볼륨까지 파기한다'),
                            ('7', 'WireGuard 로 클라우드와 온프레미스를 잇는다'),
                            ('8', '커널 계층 주입은 검증면에서만'),
                            ('9', 'SSH 미개방. 관리 평면은 SSM 포트 포워딩으로만')]):
    x = 40 + (i % 5)*412
    y = LY + 32 + (i // 5)*32
    step(x, y-4, n)
    txt(x+18, y, d, 9.6, BODY)

add('</g>')
add('</svg>')
out = 'arch_aws_slide.svg' if SLIDE else 'arch_aws.svg'
io.open(out, 'w', encoding='utf-8').write(chr(10).join(o))
print(f'{out}  {W}x{H}  비율 {W/H:.2f}')
