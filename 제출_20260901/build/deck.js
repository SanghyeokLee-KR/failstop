const pptx = require('pptxgenjs');
const P = new pptx();
P.layout = 'LAYOUT_WIDE';           // 13.333 x 7.5
P.author = 'Sanghyeok Lee';
P.title = '고가용성 가상 암호화폐 거래소 계획서';

const DARK='222E35', INK='1B2429', BODY='3C4A52', MUTE='7A8A93',
      LINE='D8DEE2', PAPER='FFFFFF', PANEL='F2F5F6', RED='B33A32', OK='2F6B54';
const KO='맑은 고딕';
const W=13.333, M=0.62, CW=W-M*2;

function head(s, no, t, h, dark){
  s.addText(t, {x:M, y:0.42, w:CW-1.1, h:0.62, fontFace:KO, fontSize:31, bold:true,
    color:dark?PAPER:INK, margin:0, valign:'middle'});
  s.addText(String(no).padStart(2,'0'), {x:W-M-0.85, y:0.42, w:0.85, h:0.62, fontFace:KO,
    fontSize:31, bold:true, color:dark?'4A5B66':'C9D2D7', align:'right', margin:0, valign:'middle'});
  s.addText(h, {x:M, y:1.06, w:CW, h:0.42, fontFace:KO, fontSize:14.5,
    color:dark?'AFC0C9':BODY, italic:true, margin:0, valign:'middle'});
}
function bullets(s, arr, x, y, w, sz){
  s.addText(arr.map((b,i)=>({text:b, options:{bullet:{code:'2013'}, breakLine:i<arr.length-1}})),
    {x, y, w, h:0.28*arr.length+0.35, fontFace:KO, fontSize:sz||12.5, color:BODY,
     lineSpacing:20, paraSpaceAfter:5, margin:0});
}
function card(s,x,y,w,h,fill){ s.addShape(P.ShapeType.roundRect,{x,y,w,h,rectRadius:0.05,
  fill:{color:fill||PANEL}, line:{color:LINE,width:0.75}}); }
// onDark: 어두운 배경 슬라이드에서는 본문 셀에 밝은 바탕을 깔아야 글자가 읽힌다
function tbl(s, headers, rows, x, y, w, colW, sz, onDark){
  const hFill = onDark ? '3D4E58' : DARK;
  const th = headers.map(t=>({text:t,options:{bold:true,color:PAPER,fill:{color:hFill},fontSize:(sz||10.5)}}));
  const body = rows.map(r=>r.map(c=>({text:c,options:{color:BODY,fontSize:(sz||10.5),
    fill:onDark?{color:'F7F9FA'}:undefined}})));
  s.addTable([th,...body], {x,y,w, colW, fontFace:KO, border:{type:'solid',color:onDark?'5A6B75':LINE,pt:0.5},
    rowH:0.34, valign:'middle', margin:[3,6,3,6]});
}

/* ---------- 1 표지 ---------- */
let s = P.addSlide(); s.background={color:DARK};
s.addText('고가용성 가상 암호화폐 거래소', {x:M, y:1.55, w:CW, h:0.9, fontFace:KO,
  fontSize:40, bold:true, color:PAPER, margin:0});
s.addText('장애를 일부러 주입하고, 데이터가 맞는지 원장 SQL로 판정한다', {x:M, y:2.5, w:CW, h:0.6,
  fontFace:KO, fontSize:19, color:'9FB3BE', margin:0});
[['220','커밋'],['19,867','서버 줄'],['23,749','웹 줄'],['26','불변식']].forEach((v,i)=>{
  const x=M+i*2.32;
  s.addShape(P.ShapeType.roundRect,{x, y:3.62, w:2.05, h:1.0, rectRadius:0.05,
    fill:{color:'2C3A43'}, line:{color:'3D4E58',width:0.75}});
  s.addText(v[0],{x, y:3.72, w:2.05, h:0.5, fontFace:KO, fontSize:23, bold:true, color:PAPER, align:'center', margin:0});
  s.addText(v[1],{x, y:4.2, w:2.05, h:0.3, fontFace:KO, fontSize:11, color:'8AA0AC', align:'center', margin:0});
});
s.addText([{text:'1인 프로젝트  ·  한국폴리텍대학 광명융합기술교육원 데이터분석과',options:{breakLine:true}},
           {text:'작성 2026-08-25  ·  발표 2026-10-16  ·  이상혁',options:{}}],
  {x:M, y:5.6, w:CW, h:0.7, fontFace:KO, fontSize:12, color:'8AA0AC', margin:0, lineSpacing:19});
s.addNotes('봇이 실시간으로 매매하는 완전 시뮬레이션 거래소입니다. 실거래와 실자산은 없습니다. 이 시스템에 장애를 의도적으로 주입하고, 원인이 어느 계층인지 좁히고, 장애가 지나간 뒤 데이터가 맞는지를 원장 SQL로 판정합니다. 서비스는 이미 서 있고 남은 6주는 전부 인프라와 운영에 씁니다.');

/* ---------- 2 문제 정의 ---------- */
s = P.addSlide(); s.background={color:PAPER};
head(s,2,'문제 정의','응답시간은 장애가 지나간 뒤 데이터가 맞는지를 말하지 못한다');
s.addShape(P.ShapeType.roundRect,{x:M, y:1.72, w:2.6, h:0.5, rectRadius:0.1, fill:{color:DARK}});
s.addText('"안 돼요"  /  "느려요"',{x:M, y:1.72, w:2.6, h:0.5, fontFace:KO, fontSize:13,
  bold:true, color:PAPER, align:'center', valign:'middle', margin:0});
[['L3  도달','ICMP 프로브','네트워크'],['L4  연결','TCP·패킷 캡처','인프라'],['L7  응답','HTTP·앱 로그','개발']]
 .forEach((r,i)=>{ const y=2.46+i*1.02;
  card(s,M,y,5.7,0.84);
  s.addText(r[0],{x:M+0.16,y,w:1.35,h:0.84,fontFace:KO,fontSize:13,bold:true,color:INK,valign:'middle',margin:0});
  s.addText(r[1],{x:M+1.55,y,w:2.5,h:0.84,fontFace:KO,fontSize:11.5,color:BODY,valign:'middle',margin:0});
  s.addText(r[2],{x:M+4.05,y,w:1.5,h:0.84,fontFace:KO,fontSize:11.5,bold:true,color:RED,align:'right',valign:'middle',margin:0});
 });
s.addText('계층 판단이 곧 책임 소재 판단이다',{x:M, y:5.62, w:5.7, h:0.34, fontFace:KO,
  fontSize:11.5, color:MUTE, italic:true, margin:0});
const RX=M+6.2, RW=CW-6.2;
tbl(s,['도메인','정답 상태','장애 후 판정'],
  [['게시판·URL 단축','없음','육안'],['재고·예약','부분','단일 제약'],
   ['부하테스트 대상','없음','TPS·p99'],['거래소 복식부기','있음','SQL 위반 행 수']],
  RX,1.72,RW,[2.3,1.3,2.4],10.5);
bullets(s,['가용성 지표는 다시 200이 나오는 것까지만 말한다',
  '성능 지표는 느렸다까지만 말한다',
  '자산별 원장 합 0은 내가 정한 규칙이 아니라 회계 성립 조건이다',
  '틀리면 반드시 위반 행으로 드러난다'], RX, 3.95, RW, 13);
s.addNotes('운영 현장에서 장애는 지표로 도착하지 않고 문장으로 도착합니다. 엔지니어의 일은 그 한 문장을 계층으로 좁히는 것입니다. 그런데 대부분의 장애 실습은 사후 판정을 못 합니다. 응답시간은 데이터가 틀어졌는지 말해주지 않습니다. 거래소를 고른 것은 취향이 아니라 복식부기 원장이 정답 상태를 이미 갖고 있기 때문입니다.');

/* ---------- 3 증명 명제 ---------- */
s = P.addSlide(); s.background={color:PAPER};
head(s,3,'증명 명제와 판정 방식','기능 목록이 아니라 반증 조건이 붙은 명제 두 개를 증명한다');
[['증명 ①  체결 무결','자산별 원장 합 0','주문 중복 접수·중복 체결·유실 0','반증: 어느 한 술어라도 1행'],
 ['증명 ②  자동 복구','사람 개입 없이 승격·복구','RTO 30초 이내, epoch 정확히 +1','반증: 30초 초과 또는 수동 개입']]
 .forEach((c,i)=>{ const x=M+i*(CW/2+0.12), w=CW/2-0.12;
  card(s,x,1.72,w,1.58);
  s.addText(c[0],{x:x+0.2,y:1.84,w:w-0.4,h:0.34,fontFace:KO,fontSize:14,bold:true,color:INK,margin:0});
  s.addText([{text:c[1],options:{breakLine:true}},{text:c[2],options:{}}],
    {x:x+0.2,y:2.2,w:w-0.4,h:0.62,fontFace:KO,fontSize:11.5,color:BODY,lineSpacing:17,margin:0});
  s.addText(c[3],{x:x+0.2,y:2.88,w:w-0.4,h:0.3,fontFace:KO,fontSize:10.5,color:RED,margin:0});
 });
['SQL 위반 행 수','메트릭 임계','스크립트 exit code'].forEach((t,i)=>{
  const x=M+i*(CW/3), w=CW/3-0.14;
  s.addShape(P.ShapeType.roundRect,{x,y:3.5,w,h:0.46,rectRadius:0.08,fill:{color:DARK}});
  s.addText(t,{x,y:3.5,w,h:0.46,fontFace:KO,fontSize:12,bold:true,color:PAPER,align:'center',valign:'middle',margin:0});
});
s.addText('판정 주체는 사람 눈이 아니다',{x:M,y:4.04,w:CW,h:0.3,fontFace:KO,fontSize:11,color:MUTE,italic:true,margin:0});
tbl(s,['실험','실측 RTO','판정'],
  [['E2  PG 단절','13.882초 (재실행 14.367초)','PASS'],
   ['E5  네트워크 분단','11.99초','PASS'],
   ['E7  그레이 장애','9.983초','PASS'],
   ['E4  리더 kill','미확인. 술어 교체 후 재측정','보류']],
  M,4.46,CW,[3.6,5.1,3.4],11);
s.addText('불변식 26개 상시 검증 · 판정기 자체를 검증하는 반증 테스트 8건',
  {x:M,y:6.34,w:CW,h:0.3,fontFace:KO,fontSize:11,color:MUTE,margin:0});
s.addNotes('명제는 반증 조건을 함께 적을 때만 명제입니다. 판정은 SQL 위반 행 수, 메트릭 임계, 스크립트 종료 코드 세 가지로만 합니다. 데모 화면이 잘 도는 것은 증거로 치지 않습니다. E4는 판정 술어가 교체된 뒤 재측정하지 않아 보류로 적었습니다. 미확인을 미확인이라고 적는 것이 이 문서의 규율입니다.');

/* ---------- 4 아키텍처 ---------- */
s = P.addSlide(); s.background={color:PAPER};
head(s,4,'시스템 아키텍처','상시 접속 요건과 커널 장애 주입은 평면을 나눠야 동시에 성립한다');
// 도면은 코드로 생성한다(diagram/arch_aws.py). 계획이 바뀌면 재생성해 갈아끼운다
s.addImage({path:'시스템_아키텍처_슬라이드용.png', x:0.34, y:1.56, w:12.66, h:5.65});
s.addNotes('왼쪽이 AWS 상시면입니다. 퍼블릭 서브넷의 edge 한 대가 TLS 를 끝내고 nftables masquerade 로 NAT 역할까지 합니다. NAT Gateway 를 안 쓴 자리입니다. 프라이빗의 core 가 앱 리더와 PostgreSQL primary 와 관측을 함께 담습니다. 점선 상자가 증명면이고 실험 창에만 terraform 으로 만들었다가 볼륨까지 지웁니다. 오른쪽이 온프레미스 검증면입니다. 로컬 Rocky Linux 에 k3s 를 올려 GHCR 같은 digest 를 돌립니다. 커널 계층 주입은 여기서만 합니다. 상시면은 외부 접속 요건이 걸려 있어 비파괴 실험만 받습니다. 두 면은 WireGuard 로 잇습니다. IPsec Site-to-Site 가 월 36달러인데 여기는 0입니다. 아래 비채택 블록이 이 설계의 핵심입니다. ALB 와 NAT Gateway 는 IAM Deny 로 생성 자체를 막아 뒀습니다. 비용 회피가 아니라, 관리형을 쓰면 페일오버 내부가 닫혀서 복제 링크에 tc netem 을 걸 수 없기 때문입니다.');

/* ---------- 5 인프라·배포 ---------- */
s = P.addSlide(); s.background={color:PAPER};
head(s,5,'인프라와 배포 구성','상시 접속 요건과 파괴 실험은 평면을 나눠야 동시에 성립한다');
card(s,M,1.72,CW,2.52);
s.addText('상시면  24시간 구동',{x:M+0.22,y:1.82,w:3.2,h:0.32,fontFace:KO,fontSize:12.5,bold:true,color:OK,margin:0});
[['브라우저','CloudFront','S3 · 정적 66페이지'],['','오리진 2개 분기','edge · nginx TLS · nftables'],
 ['','','core · app · PostgreSQL · 관측']].forEach((r,i)=>{
  r.forEach((c,j)=>{ if(!c) return; const x=M+0.3+j*4.0;
   s.addShape(P.ShapeType.roundRect,{x,y:2.28+i*0.58,w:3.6,h:0.46,rectRadius:0.06,
     fill:{color:PAPER},line:{color:LINE,width:0.75}});
   s.addText(c,{x,y:2.28+i*0.58,w:3.6,h:0.46,fontFace:KO,fontSize:10.5,color:BODY,align:'center',valign:'middle',margin:0});
  });
 });
[0,1].forEach(k=>s.addShape(P.ShapeType.line,{x:M+0.3+3.6+k*4.0+0.06,y:2.51,w:0.28,h:0,
  line:{color:MUTE,width:1.25,endArrowType:'triangle'}}));
s.addShape(P.ShapeType.roundRect,{x:M,y:4.44,w:CW,h:1.16,rectRadius:0.06,
  fill:{color:'FAFAFA'},line:{color:MUTE,width:1,dashType:'dash'}});
s.addText('증명면  실험 창에만 구동 후 파기',{x:M+0.22,y:4.56,w:4.5,h:0.3,fontFace:KO,fontSize:12.5,bold:true,color:RED,margin:0});
s.addText('app-2  ·  pg-standby  ·  obs  ·  load   =  65시간 · 20,530원',
  {x:M+0.22,y:4.96,w:CW-0.44,h:0.5,fontFace:KO,fontSize:11.5,color:BODY,margin:0});
bullets(s,['RDS 비채택. 커널 주입 권한이 없으면 증명 밖으로 나간다',
  'Rocky 10은 iptables 제거. nftables · SELinux enforcing',
  'SSH 미개방. SSM 세션으로만 접속'], M, 5.86, CW, 12.5);
s.addNotes('학교 필수 조건이 외부에서 접속 가능한 상태로 서비스하는 것입니다. 그런데 이 프로젝트의 핵심 실험은 커널에 장애를 주입하는 것이라, 상시 서비스와 파괴 실험이 한 평면에서는 양립하지 않습니다. 그래서 평면을 나눴습니다. 관리형 DB를 쓰지 않은 것도 같은 이유입니다. 주입 권한이 없으면 증명 자체가 성립하지 않습니다.');

/* ---------- 6 운영·보안·비용 ---------- */
s = P.addSlide(); s.background={color:PAPER};
head(s,6,'운영 · 보안 · 비용','설정했다는 말은 증거가 아니고 거부 코드가 나온 기록만 증거다');
const C3=(CW-0.4)/3;
[['운영',['계층 프로브 ICMP·TCP·HTTP 신규','Grafana 대시보드 4종','레코딩 룰 9 · 알람 11','로그 중앙화']],
 ['보안',['DB 롤 분리 후 42501 실제 재현','원장 직접 UPDATE는 P0001 차단','TLS 종단 · 인증서 자동 갱신','장기 액세스 키 0개, OIDC']],
 ['비용',['월 상한 100,000원','계획값 92,425원','6주 총계 139,854원','감축 순서 1~4 사전 확정']]]
 .forEach((c,i)=>{ const x=M+i*(C3+0.2);
  card(s,x,1.72,C3,2.62);
  s.addText(c[0],{x:x+0.2,y:1.84,w:C3-0.4,h:0.34,fontFace:KO,fontSize:14,bold:true,color:INK,margin:0});
  bullets(s,c[1],x+0.2,2.24,C3-0.4,11);
 });
tbl(s,['비용 항목','6주 총계','비고'],
  [['상시면 EC2 · EBS · IP','95,827원','24시간 구동'],
   ['증명면 65시간','20,530원','창 구동 후 파기'],
   ['도메인 · 정적 · 아웃바운드','6,864원','CloudFront 프리티어'],
   ['부가세 포함 총계','139,854원','월 환산 92,425원']],
  M,4.56,CW,[4.6,3.6,3.9],11);
s.addText('단가 원천은 AWS Price List Bulk API 서울 리전 오퍼 파일 20260824193147. 3자 요약 사이트 수치는 쓰지 않는다',
  {x:M,y:6.42,w:CW,h:0.3,fontFace:KO,fontSize:10,color:MUTE,italic:true,margin:0});
s.addNotes('운영과 보안은 설정했다는 말로는 증거가 되지 않습니다. DB 롤을 분리한 뒤 실제로 42501 권한 거부가 나오는 것, 원장에 직접 UPDATE를 시도했을 때 P0001로 막히는 것을 캡처로 남깁니다. 비용은 결과가 아니라 설계 변수로 다뤘습니다. 상한을 먼저 정하고 그 안에 들어오는 토폴로지를 골랐습니다.');

/* ---------- 7 검증 계획 ---------- */
s = P.addSlide(); s.background={color:PAPER};
head(s,7,'검증 계획','지금까지의 네트워크 실험은 전부 프록시 모사이고 커널 주입이 아니다');
const EX=[['E1','PG 지연','PASS'],['E2','DB 단절','PASS'],['E3','Redis 장애','SKIP 유효'],
 ['E4','리더 kill','재측정'],['E5','네트워크 분단','PASS'],['E6','폭주+kill','PASS'],
 ['E7','그레이 장애','PASS'],['E8','앱 assault','배선'],['E9','커밋 단절','PASS'],['E10','tc netem','신규']];
EX.forEach((e,i)=>{ const x=M+(i%5)*(CW/5), y=1.78+Math.floor(i/5)*1.12, w=CW/5-0.16;
  const st=e[2], fill = st==='PASS'?'E4EDE8' : st==='신규'?'F6E9E7' : PANEL;
  const col = st==='PASS'?OK : st==='신규'?RED : MUTE;
  card(s,x,y,w,0.92,fill);
  s.addText(e[0],{x:x+0.14,y:y+0.08,w:w-0.28,h:0.28,fontFace:KO,fontSize:13,bold:true,color:INK,margin:0});
  s.addText(e[1],{x:x+0.14,y:y+0.36,w:w-0.28,h:0.26,fontFace:KO,fontSize:10,color:BODY,margin:0});
  s.addText(st,{x:x+0.14,y:y+0.6,w:w-0.28,h:0.26,fontFace:KO,fontSize:10,bold:true,color:col,margin:0});
});
['사전 권위 run','장애 주입','무개입 관찰','사후 권위 run'].forEach((t,i)=>{
  const x=M+i*(CW/4), w=CW/4-0.3;
  s.addShape(P.ShapeType.roundRect,{x,y:4.28,w,h:0.46,rectRadius:0.08,fill:{color:DARK}});
  s.addText(t,{x,y:4.28,w,h:0.46,fontFace:KO,fontSize:11.5,bold:true,color:PAPER,align:'center',valign:'middle',margin:0});
  if(i<3) s.addShape(P.ShapeType.line,{x:x+w+0.04,y:4.51,w:0.22,h:0,line:{color:MUTE,width:1.25,endArrowType:'triangle'}});
});
bullets(s,['라이브 PASS 5건. E5·E7은 C01~C23 전건 0위반',
  'E10 tc netem 커널 주입을 신규 추가한다',
  'k6 시나리오 4종은 실재하나 실행 배선 0건이라 이번에 닫는다',
  '실험 산출물을 파일로 보존해 증거 휘발을 막는다'], M, 5.22, CW, 13);
s.addNotes('실험 9종이 이미 배선돼 있고 라이브 통과가 5건입니다. 다만 지금까지의 네트워크 실험은 프록시 레벨 모사이지 커널 주입이 아닙니다. 그래서 tc netem으로 실제 커널에 지연과 손실을 넣는 E10을 새로 추가합니다. 판정은 주입 전후로 권위 검증을 한 번씩 돌려 대조하는 방식입니다.');

/* ---------- 8 일정 ---------- */
s = P.addSlide(); s.background={color:DARK};
head(s,8,'일정과 완료 기준','주 단위 완료 정의를 이진 판정하고 밀렸을 때 자를 순서를 미리 고정했다',true);
const TY=1.9, TW=CW;
s.addShape(P.ShapeType.rect,{x:M,y:TY+0.3,w:TW,h:0.055,fill:{color:'4A5B66'}});
[['09-01','계획서 제출',0],['09-08','외부 접속 성립',0.19],['10-09','기능 동결',0.72],['10-16','최종 발표',1]]
 .forEach(p=>{ const x=M+TW*p[2]-0.02;
  s.addShape(P.ShapeType.rect,{x, y:TY+0.14, w:0.055, h:0.38, fill:{color:RED}});
  s.addText(p[0],{x:x-0.75,y:TY-0.2,w:1.6,h:0.28,fontFace:KO,fontSize:11,bold:true,color:PAPER,align:'center',margin:0});
  s.addText(p[1],{x:x-0.95,y:TY+0.56,w:2.0,h:0.28,fontFace:KO,fontSize:10,color:'9FB3BE',align:'center',margin:0});
 });
tbl(s,['주차','주제','완료 정의'],
  [['W0~W1','클라우드 착지, 방화벽','도메인 HTTPS 200, 외부 nmap 차단 확인'],
   ['W2~W3','관측 하단, 부하·분산','계층 3종 down 순서 판독, k6 exit code'],
   ['W4~W5','DB 복제, 권한 분리, netem','streaming 1행, 42501 재현, E10 exit 0'],
   ['W6~W7','최종 완주, 발표 준비','8실험 verdict 파일, 리허설 3회']],
  M,3.06,CW,[1.5,4.3,6.3],11,true);
[['336h','순 가용'],['186h','계획'],['60h','예비'],['7항목','절대 미감량']].forEach((v,i)=>{
  const x=M+i*(CW/4), w=CW/4-0.25;
  s.addShape(P.ShapeType.roundRect,{x,y:5.05,w,h:0.86,rectRadius:0.05,
    fill:{color:'2C3A43'},line:{color:'3D4E58',width:0.75}});
  s.addText(v[0],{x,y:5.13,w,h:0.42,fontFace:KO,fontSize:18,bold:true,color:PAPER,align:'center',margin:0});
  s.addText(v[1],{x,y:5.53,w,h:0.28,fontFace:KO,fontSize:10.5,color:'8AA0AC',align:'center',margin:0});
});
s.addText('완료 기준은 전부 기계 판정이다. 육안 판정 항목은 0개다',
  {x:M,y:6.16,w:CW,h:0.32,fontFace:KO,fontSize:11.5,color:'9FB3BE',italic:true,margin:0});
s.addNotes('전체 53일, 순 가용 336시간입니다. 계획에 186시간을 쓰고 60시간을 예비로 남겼습니다. 이 저장소는 환경 마찰로 며칠씩 날린 전례가 다섯 건 문서로 남아 있어서 슬랙 없는 계획은 실패합니다. 고정점 네 개는 침범하지 않고, 밀리면 미리 정한 순서대로 자릅니다.');

P.writeFile({fileName:'기획안_요약.pptx'}).then(f=>console.log('생성:',f));
