# AWS 아키텍처 다이어그램. 공식 그룹 표기 규약(테두리 색·선종)을 따른다.
# 좌표는 10 단위 격자에 맞추고 같은 행의 아이콘은 기준선을 공유한다.
# 캔버스 비율 2.1:1 은 16:9 슬라이드에서 제목을 뺀 본문 영역 비율이다.
import base64, glob, io, os, sys

# 슬라이드용은 도면 제목을 뺀다. 슬라이드 제목과 중복되기 때문이다
SLIDE = '--slide' in sys.argv

ICON = r'C:\Users\DEV-PC\AppData\Local\Programs\Python\Python314\Lib\site-packages\resources'
I = {
    'user': 'aws/general/user.png', 'users': 'aws/general/users.png',
    'srv': 'aws/general/traditional-server.png',
    'route53': 'aws/network/route-53.png', 'waf': 'aws/security/waf.png',
    'cloudfront': 'aws/network/cloudfront.png', 'acm': 'aws/security/certificate-manager.png',
    's3': 'aws/storage/simple-storage-service-s3.png', 'igw': 'aws/network/internet-gateway.png',
    'alb': 'aws/network/elb-application-load-balancer.png', 'nat': 'aws/network/nat-gateway.png',
    'rt': 'aws/network/route-table.png', 'ssm': 'aws/management/systems-manager.png',
    'cw': 'aws/management/cloudwatch.png', 'vgw': 'aws/network/site-to-site-vpn.png',
    'cgw': 'aws/network/vpc-customer-gateway.png', 'ec2': 'aws/compute/ec2-instance.png',
    'nginx': 'onprem/network/nginx.png', 'spring': 'programming/framework/spring.png',
    'pg': 'onprem/database/postgresql.png', 'docker': 'onprem/container/docker.png',
    'vmware': 'generic/virtualization/vmware.png', 'linux': 'generic/os/linux-general.png',
    'switch': 'generic/network/switch.png', 'store': 'generic/storage/storage.png',
    'dns': 'onprem/dns/coredns.png', 'zabbix': 'onprem/monitoring/zabbix.png',
    'prom': 'onprem/monitoring/prometheus.png', 'grafana': 'onprem/monitoring/grafana.png',
    'fw': 'generic/network/firewall.png',
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

add(f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{W}" height="{H}" viewBox="0 0 {W} {H}"><rect width="{W}" height="{H}" fill="#FFFFFF"/><defs>')
for c in [FLOW, VPCC, SG, PUB, CORP]:
    add(f'<marker id="h{c[1:]}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0,1.2 L9,5 L0,8.8 z" fill="{c}"/></marker>')
add('</defs>')
add(f'<g transform="translate(0,{-SHIFT})">')

# 행 기준선
R_EDGE, R_TOP, R_PUB, R_PRV, R_MGMT = 180, 300, 420, 560, 690

if not SLIDE:
    txt(24, 36, '고가용성 가상 암호화폐 거래소  ·  시스템 아키텍처', 19, INK, '700')
    txt(24, 55, 'AWS ap-northeast-2   |   온프레미스 IDC   |   IPsec Site-to-Site VPN', 10.5, MUTE)

node(58, R_EDGE, 'users', '사용자', None, 36)
node(58, R_PRV, 'user', '운영자', None, 32)

# ================= AWS Cloud =================
CX, CY, CW, CH = 110, 76, 1040, 690
group(CX, CY, CW, CH, 'AWS Cloud', CLOUD, '#FFFFFF', lw=2)
group(CX+16, CY+32, CW-32, CH-48, 'Region  ap-northeast-2', REGION, 'none', '6 4', 1.4)

for i, (k, a) in enumerate([('route53', 'Route 53'), ('waf', 'AWS WAF'), ('cloudfront', 'CloudFront'),
                            ('acm', 'ACM'), ('s3', 'Amazon S3')]):
    node(CX+110+i*180, R_EDGE, k, a, None, 34)
for i in range(4):
    ln([(CX+128+i*180, R_EDGE), (CX+272+i*180, R_EDGE)], FLOW, 1.5, '4 3' if i == 3 else None)
ln([(80, R_EDGE), (CX+92, R_EDGE)], FLOW, 1.5)
step(CX+92, R_EDGE-36, '1'); step(CX+452, R_EDGE-36, '2'); step(CX+812, R_EDGE-36, '3')

VX, VY, VW, VH = CX+34, 232, CW-68, 512
group(VX, VY, VW, VH, 'VPC  10.0.0.0/16', VPCC, '#FFFFFF')
node(VX+80, R_TOP, 'igw', 'Internet gateway', None, 30)
node(VX+250, R_TOP, 'alb', 'Application Load Balancer', None, 30)
node(VX+VW-80, R_TOP, 'vgw', 'Virtual private gateway', None, 30)
ln([(VX+96, R_TOP), (VX+234, R_TOP)], FLOW, 1.5)

for j in range(2):
    ax = VX + 22 + j*470
    group(ax, 342, 450, 288, f'가용영역  ap-northeast-2{"ac"[j]}', REGION, 'none', '6 4', 1.3)
    group(ax+14, 372, 422, 96, f'퍼블릭 서브넷  10.0.{j*2}.0/24', PUB, '#FBFCF7', lw=1.4, badge=False)
    node(ax+130, R_PUB, 'nat', 'NAT gateway', None, 28)
    node(ax+300, R_PUB, 'nginx', 'nginx', 'TLS 종단', 28)
    group(ax+14, 490, 422, 130, f'프라이빗 서브넷  10.0.{j*2+1}.0/24', PRV, '#F8FBFB', lw=1.4, badge=False)
    node(ax+110, R_PRV, 'spring', f'app-{j+1}', 'Spring Boot', 30)
    node(ax+230, R_PRV, 'pg', 'PostgreSQL 17', 'primary' if j == 0 else 'replica', 30)
    node(ax+350, R_PRV, 'ec2', 'Bastion', None, 26)
    ln([(ax+126, R_PRV), (ax+212, R_PRV)], FLOW, 1.4)
    if j == 0: step(ax+300, 392, '4')

# ALB 에서 두 AZ 로 가는 분기. 수평 버스 뒤 수직으로 내려 라벨 관통을 피한다
ln([(VX+266, R_TOP), (VX+792, R_TOP)], FLOW, 1.4, head=False)
ln([(VX+322, R_TOP), (VX+322, R_PUB-16)], FLOW, 1.4)
ln([(VX+792, R_TOP), (VX+792, R_PUB-16)], FLOW, 1.4)

ln([(VX+252, R_PRV+22), (VX+252, 640), (VX+722, 640), (VX+722, R_PRV+22)], PUB, 1.5, '6 4')
txt(VX+487, 634, '스트리밍 복제', 9.5, PUB, '600', 'middle')
step(VX+340, 640, '5', PUB)

group(VX+22, 664, 920, 54, None, HAIR, '#FCFDFD', lw=1.2, badge=False)
for i, (k, a) in enumerate([('rt', 'Route tables'), ('ssm', 'Systems Manager'), ('cw', 'CloudWatch')]):
    node(VX+150+i*300, R_MGMT+1, k, None, None, 24)
    txt(VX+170+i*300, R_MGMT+5, a, 9.5, BODY)

# ================= VPN =================
TX = CX + CW + 8
ln([(VX+VW-64, R_TOP), (TX+52, R_TOP)], VPCC, 1.9, '7 4')
txt(TX+52, R_TOP-34, 'IPsec Site-to-Site', 9.5, VPCC, '700', 'middle')
txt(TX+52, R_TOP-21, 'IKEv2 · AES-256 · PFS 14', 8.4, MUTE, '400', 'middle')
add(f'<line x1="{TX+8}" y1="{R_TOP+22}" x2="{TX+96}" y2="{R_TOP+22}" stroke="{PUB}" stroke-width="2.2"/>')
txt(TX+52, R_TOP+35, '터널 1  Active', 8.4, PUB, '600', 'middle')
add(f'<line x1="{TX+8}" y1="{R_TOP+50}" x2="{TX+96}" y2="{R_TOP+50}" stroke="{MUTE}" stroke-width="2.2" stroke-dasharray="5 3"/>')
txt(TX+52, R_TOP+63, '터널 2  Standby', 8.4, MUTE, '600', 'middle')
step(TX+52, R_TOP-52, '6', VPCC)

# ================= Corporate data center =================
DX = TX + 104
DW = W - DX - 24
group(DX, CY, DW, CH, 'Corporate data center  ·  온프레미스 IDC', CORP, '#FFFFFF', lw=2)
node(DX+56, R_EDGE, 'cgw', 'Customer gateway', None, 30)
node(DX+56, R_TOP, 'fw', '방화벽', 'nftables', 28)
ln([(DX+22, R_TOP), (DX+40, R_EDGE+8)], CORP, 1.5)
ln([(DX+56, R_EDGE+24), (DX+56, R_TOP-20)], CORP, 1.4)

group(DX+108, CY+34, DW-132, 268, 'VMware vSphere  ·  ESXi', CORP, '#FCFDFD', lw=1.4)
node(DX+168, CY+102, 'vmware', 'vCenter', None, 28)
node(DX+168, CY+216, 'srv', 'ESXi host', 'esxi-01', 28)
group(DX+228, CY+66, 186, 96, '업링크 vmnic', CORP, '#FFFFFF', '4 3', 1.1, badge=False)
node(DX+282, CY+122, 'switch', 'vmnic0', None, 22)
node(DX+362, CY+122, 'switch', 'vmnic1', None, 22)
group(DX+228, CY+176, 186, 96, '분산 포트 그룹', CORP, '#FFFFFF', '4 3', 1.1, badge=False)
node(DX+282, CY+232, 'switch', 'VM 망', None, 22)
node(DX+362, CY+232, 'switch', '관리 망', None, 22)
group(DX+430, CY+66, DW-462, 206, 'Rocky Linux 10  ·  Docker', CORP, '#FFFFFF', '4 3', 1.1, badge=False)
for i, (k, a, b) in enumerate([('docker', 'exch-node1', 'app + LB'), ('docker', 'exch-node2', 'app'),
                               ('pg', 'PostgreSQL', 'standby')]):
    node(DX+500+i*110, CY+118, k, a, b, 28)
for i, (k, a, b) in enumerate([('dns', 'DNS', None), ('store', 'NFS', 'DataStore'), ('linux', 'Bastion', None)]):
    node(DX+500+i*110, CY+212, k, a, b, 26)
ln([(DX+186, CY+216), (DX+240, CY+232)], CORP, 1.3)
ln([(DX+414, CY+122), (DX+482, CY+118)], CORP, 1.3)

group(DX+108, 400, DW-132, 104, '장애 주입 지점  (실험 창 한정)', SG, '#FDF7F6', '5 3', 1.4, badge=False)
for i, (a, b) in enumerate([('tc netem', 'L3  지연 · 손실'), ('nftables', 'L4  drop · reject'),
                            ('앱 지연 · kill', 'L7  예외 주입')]):
    cx = DX + 230 + i*200
    add(f'<rect x="{cx-88}" y="428" width="176" height="44" rx="3" fill="#FFFFFF" stroke="{SG}" stroke-width="1.1"/>')
    txt(cx, 446, a, 10, SG, '700', 'middle')
    txt(cx, 461, b, 8.6, MUTE, '400', 'middle')
step(DX+128, 400, '8', SG)

group(DX+108, 530, DW-132, 186, '관제', CORP, '#FCFDFD', lw=1.4)
for i, (k, a, b) in enumerate([('zabbix', 'Zabbix', 'SNMP'), ('prom', 'Prometheus', None),
                               ('grafana', 'Grafana', None)]):
    node(DX+170+i*112, 604, k, a, b, 30)
for i, (a, b) in enumerate([('Zabbix', '장비 · VM · 인터페이스 · VPN 터널'),
                            ('Prometheus', '애플리케이션 메트릭 · 불변식'),
                            ('Grafana', '대시보드 4종 · 알람 11')]):
    txt(DX+490, 582+i*24, a, 9.5, INK, '700')
    txt(DX+582, 582+i*24, b, 9.5, BODY)
step(DX+128, 530, '7', CORP)

# ================= 흐름 범례 =================
LY = 796
add(f'<line x1="24" y1="{LY}" x2="{W-24}" y2="{LY}" stroke="{HAIR}" stroke-width="1.2"/>')
for i, (n, d) in enumerate([('1', 'Route 53 DNS 질의'), ('2', 'WAF 필터 후 CloudFront 캐시'),
                            ('3', 'S3 정적 콘텐츠 반환'), ('4', 'ALB 가 두 AZ 의 nginx 로 분산'),
                            ('5', 'PostgreSQL 스트리밍 복제'), ('6', 'IPsec 터널로 IDC 연결'),
                            ('7', 'Zabbix 와 Prometheus 가 각각 수집'), ('8', '실험 창에만 커널 계층 장애 주입')]):
    x = 40 + (i % 4)*520
    y = LY + 36 + (i // 4)*34
    step(x, y-4, n)
    txt(x+18, y, d, 10.5, BODY)

add('</g>')
add('</svg>')
out = 'arch_aws_slide.svg' if SLIDE else 'arch_aws.svg'
io.open(out, 'w', encoding='utf-8').write(chr(10).join(o))
print(f'{out}  {W}x{H}  비율 {W/H:.2f}')
