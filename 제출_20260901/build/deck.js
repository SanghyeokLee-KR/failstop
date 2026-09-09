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
s.addText('불변식 27개 상시 검증 · 판정기 자체를 검증하는 반증 테스트 8건',
  {x:M,y:6.34,w:CW,h:0.3,fontFace:KO,fontSize:11,color:MUTE,margin:0});
s.addNotes('명제는 반증 조건을 함께 적을 때만 명제입니다. 판정은 SQL 위반 행 수, 메트릭 임계, 스크립트 종료 코드 세 가지로만 합니다. 데모 화면이 잘 도는 것은 증거로 치지 않습니다. E4는 판정 술어가 교체된 뒤 재측정하지 않아 보류로 적었습니다. 미확인을 미확인이라고 적는 것이 이 문서의 규율입니다.');

/* ---------- 4 아키텍처 ---------- */
s = P.addSlide(); s.background={color:PAPER};
head(s,4,'시스템 아키텍처','상시 접속 요건과 커널 장애 주입은 평면을 나눠야 동시에 성립한다');
// 도면은 코드로 생성한다(diagram/arch_aws.py). 계획이 바뀌면 재생성해 갈아끼운다
// 도면 비율에 맞춰 넣는다. 고정 폭으로 채우면 세로가 눌린다. 슬라이드판 2100x1330, 비율 1.579
s.addImage({path:'시스템_아키텍처_슬라이드용.png', x:2.21, y:1.56, w:8.92, h:5.65});
s.addNotes('가운데 굵은 가로선이 쓰기 경로입니다. 정거장 여섯 개를 왼쪽에서 오른쪽으로 읽으면 접수, 저널 append, ACK, 매칭엔진, 원장 정산, 프로젝션입니다. 200 을 내는 지점이 세 번째라는 것이 이 설계의 요지입니다. 디스크에 남기고 나서 응답하므로 그 뒤에 무엇이 죽어도 재생할 수 있습니다. 옅게 채운 상자가 PostgreSQL 에 남는 것, 흰 상자가 메모리에서 도는 것, 파선이 재계산 가능한 파생입니다. 색을 쓰지 않고 형태로만 갈랐습니다. 굵은 테두리를 두른 매칭엔진이 이중화할 수 없는 유일점입니다. 위 칸이 가용영역 a 이고 아래 점선 칸이 c 입니다. app-1 과 app-2 를 같은 x 에 두었기 때문에 같은 자리가 다른 가용영역에서 다시 선다는 것이 배치로 읽힙니다. ALB 는 앱이 내는 readiness 503 을 그대로 읽습니다. 팔로워가 스스로 빠지므로 헬스체크를 자작하지 않았고 판정 주체는 여전히 앱입니다. 맨 아래 점선 띠가 온프레미스 검증면입니다. tc netem 과 nftables 같은 커널 계층 주입은 이 평면 전용입니다. 클라우드 노드에 걸면 SSM 접근 경로가 같은 네트워크를 타서 노드를 통째로 잃습니다. 두 평면을 잇는 것은 상시 터널이 아니라 같은 GHCR digest 와 저장소에 커밋한 verdict 파일입니다.');

/* ---------- 5 인프라·배포 ---------- */
s = P.addSlide(); s.background={color:PAPER};
head(s,5,'인프라와 배포 구성','상시 접속 요건과 파괴 실험은 평면을 나눠야 동시에 성립한다');
card(s,M,1.72,CW,2.52);
s.addText('상시면  24시간 구동',{x:M+0.22,y:1.82,w:3.2,h:0.32,fontFace:KO,fontSize:12.5,bold:true,color:OK,margin:0});
[['브라우저','CloudFront','S3 · 정적 오리진'],['','오리진 2개 분기','ALB · 동적 오리진 · 2 AZ'],
 ['','','app x2 · PostgreSQL primary/standby']].forEach((r,i)=>{
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
s.addText('증명면  부하 창에만 구동 후 파기',{x:M+0.22,y:4.56,w:4.5,h:0.3,fontFace:KO,fontSize:12.5,bold:true,color:RED,margin:0});
s.addText('load c7g.large 단독   =  달력 월 45시간 예산 · USD 0.0816 /hr. app-2 와 pg-standby 는 상시면으로 올라갔다',
  {x:M+0.22,y:4.96,w:CW-0.44,h:0.5,fontFace:KO,fontSize:11.5,color:BODY,margin:0});
bullets(s,['ALB 채택. 앱의 readiness 503 을 그대로 읽어 팔로워가 스스로 빠진다',
  'RDS 비채택. 복제 링크에 주입할 권한이 없으면 증명 밖으로 나간다',
  'SSH 미개방. SSM 세션 단독. 커널 주입은 온프레미스 검증면 전용'], M, 5.86, CW, 12.5);
s.addNotes('학교 필수 조건이 외부에서 접속 가능한 상태로 서비스하는 것입니다. 그런데 이 프로젝트의 핵심 실험은 커널에 장애를 주입하는 것이라 한 평면에서는 양립하지 않습니다. 그래서 세 평면으로 나눴습니다. 상시면은 두 가용영역에 이중화한 채로 24시간 서고, 증명면은 부하 창에만 뜨는 발생기 한 대이며, 검증면은 집 PC 의 온프레미스라 비용이 0입니다. 로드밸런서는 ALB 를 채택했습니다. 자작 헬스체크를 없애려고 관리형을 쓴 것이 아니라 앱이 이미 내고 있는 readiness 계약을 그대로 소비시킨 것이라 판정 주체는 여전히 앱입니다. 관리형 DB를 안 쓴 것은 반대 방향의 판단입니다. 복제 링크에 tc netem 을 걸 수 없으면 증명 두 번째 명제가 성립하지 않습니다.');

/* ---------- 6 운영·보안·비용 ---------- */
s = P.addSlide(); s.background={color:PAPER};
head(s,6,'운영 · 보안 · 비용','설정했다는 말은 증거가 아니고 거부 코드가 나온 기록만 증거다');
const C3=(CW-0.4)/3;
[['운영',['계층 프로브 ICMP·TCP·HTTP 신규','Grafana 대시보드 9종','레코딩 룰 9 · 알람 15','로그 중앙화는 W5 이월']],
 ['보안',['DB 롤 분리 후 42501 실제 재현','원장 직접 UPDATE는 P0001 차단','TLS 종단 · 인증서 자동 갱신','장기 액세스 키 0개, OIDC']],
 ['비용',['월 상한 100,000원','달력 월 실청구 97,318원','발표까지 총계 USD 139.03','감축 순서 1~5 사전 확정']]]
 .forEach((c,i)=>{ const x=M+i*(C3+0.2);
  card(s,x,1.72,C3,2.62);
  s.addText(c[0],{x:x+0.2,y:1.84,w:C3-0.4,h:0.34,fontFace:KO,fontSize:14,bold:true,color:INK,margin:0});
  bullets(s,c[1],x+0.2,2.24,C3-0.4,11);
 });
tbl(s,['비용 항목','금액 USD','비고'],
  [['S0 baseline  09-01 ~ 09-14','0.72','Route 53 · CloudFront · S3'],
   ['S1 HA steady  09-15 ~ 10-16','117.87','app x2 · primary/standby · NAT x2'],
   ['S2 부하 창  90시간','7.79','달력 월 45시간 예산'],
   ['부가세 포함 총계','139.03','달력 월 97,318원 · 상한 100,000원 안']],
  M,4.56,CW,[4.6,2.4,5.1],11);
s.addText('단가 원천은 AWS Price List Bulk API 서울 리전 오퍼 파일 20260824193147. 3자 요약 사이트 수치는 쓰지 않는다',
  {x:M,y:6.42,w:CW,h:0.3,fontFace:KO,fontSize:10,color:MUTE,italic:true,margin:0});
s.addNotes('운영과 보안은 설정했다는 말로는 증거가 되지 않습니다. DB 롤을 분리한 뒤 실제로 42501 권한 거부가 나오는 것, 원장에 직접 UPDATE 를 시도했을 때 P0001 로 막히는 것을 캡처로 남깁니다. 비용은 결과가 아니라 설계 변수로 다뤘습니다. 상한을 먼저 정하고 그 안에 들어오는 토폴로지를 골랐습니다. 판정면도 바꿨습니다. 730시간 런레이트는 이 구조가 한 번도 실현하지 않는 값이라 달력 월 실청구로 판정합니다. 구월과 시월이 각각 97,318원이고 여유가 2,682원뿐입니다. 그 여유가 환율의 함수라서 부하 발생기 시간을 환율별 가용치 표로 정의해 두었습니다. 크레딧이 남아 있어 실제 현금 지출은 0이지만 상한을 현금 기준으로 완화하지 않았습니다. 완화하면 RDS 대신 자체 복제를 고른 근거가 함께 사라집니다.');

/* ---------- 7 검증 계획 ---------- */
s = P.addSlide(); s.background={color:PAPER};
head(s,7,'검증 계획','장애를 일부러 내고 데이터가 맞는지 확인한다. 예상과 실측을 같은 행에서 대조한다');
// 카드 격자를 표로 바꾼다. 격자는 상태만 보이고 무엇을 확인했는지가 안 보인다
const EX=[
 ['E1','시스템을 일부러 느리게 해도 멈추지 않고 빠르게 정상화되는지 확인','PASS · 2.06초 만에 복구','ok'],
 ['E2','저장소 연결이 끊겨도 데이터 유실 없이 새 대표가 정상 전환되는지 확인','PASS · 약 13.9초 만에 복구','ok'],
 ['E3','Redis가 중단돼도 핵심 서비스에 영향이 없는지 확인','정상 SKIP · 실제 의존 관계 없음','na'],
 ['E4','대표 서버가 갑자기 죽어도 30초 안에 자동 복구되는지 확인','미확인 · 기준 변경 후 재시험 필요','todo'],
 ['E5','대표 서버의 네트워크가 끊겨도 새 대표로 안전하게 전환되는지 확인','PASS · 약 12초 만에 복구, 오류 0건','ok'],
 ['E6','높은 부하 중 대표 서버가 죽어도 서비스가 계속 유지되는지 확인','PASS · 0.107초 만에 복구','ok'],
 ['E7','대표 서버가 멈췄다가 다시 살아나도 잘못된 처리가 발생하지 않는지 확인','PASS · 약 10초 만에 복구, 오류 0건','ok'],
 ['E8','앱 내부에 오류를 일부러 발생시켜도 정상 복구되는지 확인','준비 완료 · 라이브 기록 아직 없음','na'],
 ['E9','처리 완료 후 응답만 끊겨도 중복 처리가 발생하지 않는지 확인','PASS · 다만 증거 신뢰도는 낮음','ok'],
 ['E10','실제 네트워크 장애를 만들어도 데이터 오류 없이 원인을 확인할 수 있는지 검증','미실시 · 09-30 ~ 10-06 목표','todo'],
];
const CLR={ok:OK, na:MUTE, todo:RED};
const th7=['항목','한 줄 요약','결과'].map(t=>({text:t,options:{bold:true,color:PAPER,fill:{color:DARK},fontSize:10.5}}));
const rows7=EX.map(e=>[
  {text:e[0],options:{bold:true,color:INK,fontSize:10.5,align:'center'}},
  {text:e[1],options:{color:BODY,fontSize:10}},
  {text:e[2],options:{bold:true,color:CLR[e[3]],fontSize:10}},
]);
s.addTable([th7,...rows7],{x:M,y:1.62,w:CW,colW:[0.78,7.55,3.76],fontFace:KO,
  border:{type:'solid',color:LINE,pt:0.5},rowH:0.33,valign:'middle',margin:[3,7,3,7]});
bullets(s,['라이브 PASS 6건. E5·E7은 불변식 C01~C23 전건 0위반이었다',
  'E1~E9는 전부 프록시 모사다. 커널에 직접 넣는 것은 E10이 처음이다'], M, 5.42, CW, 12.5);
s.addNotes('실험 열 종입니다. 왼쪽이 무엇을 확인하는 실험인지, 오른쪽이 실제로 나온 결과입니다. 라이브 통과가 여섯 건이고 그중 E5와 E7은 불변식 스물세 개가 전건 0위반이었습니다. 복구됐다는 사실과 데이터가 맞다는 사실을 같은 실행 안에서 함께 판정했다는 뜻입니다. 빨간 두 줄이 남은 일입니다. E4는 리더 킬인데 측정 기준이 8월 18일에 바뀐 뒤 재측정을 안 했습니다. 옛 숫자를 그대로 쓰면 거짓이 되므로 이 계획서에 수치로 적지 않았습니다. E10은 지금까지와 층위가 다릅니다. E1부터 E9까지는 전부 프록시가 느린 척 끊긴 척한 모사이고, tc netem 은 리눅스 커널 큐에 직접 지연과 손실을 넣습니다. 그게 실제 네트워크 장애와 같은 층위입니다.');

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
[['303h','순 가용'],['186h','계획'],['60h','예비'],['7항목','절대 미감량']].forEach((v,i)=>{
  const x=M+i*(CW/4), w=CW/4-0.25;
  s.addShape(P.ShapeType.roundRect,{x,y:5.05,w,h:0.86,rectRadius:0.05,
    fill:{color:'2C3A43'},line:{color:'3D4E58',width:0.75}});
  s.addText(v[0],{x,y:5.13,w,h:0.42,fontFace:KO,fontSize:18,bold:true,color:PAPER,align:'center',margin:0});
  s.addText(v[1],{x,y:5.53,w,h:0.28,fontFace:KO,fontSize:10.5,color:'8AA0AC',align:'center',margin:0});
});
s.addText('완료 기준은 전부 기계 판정이다. 육안 판정 항목은 0개다',
  {x:M,y:6.16,w:CW,h:0.32,fontFace:KO,fontSize:11.5,color:'9FB3BE',italic:true,margin:0});
s.addNotes('전체 48일, 순 가용 303시간입니다. 계획에 186시간을 쓰고 60시간을 예비로 남겼습니다. 이 저장소는 환경 마찰로 며칠씩 날린 전례가 다섯 건 문서로 남아 있어서 슬랙 없는 계획은 실패합니다. 고정점 네 개는 침범하지 않고, 밀리면 미리 정한 순서대로 자릅니다.');

P.writeFile({fileName:'기획안_요약.pptx'}).then(f=>console.log('생성:',f));
