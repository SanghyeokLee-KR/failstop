// 도면 2·3 의 내용. SVG 판과 draw.io 판이 이 파일 하나를 읽는다.
//
// 왜 갈라 뒀는가. 같은 도면을 두 형식으로 내면 한쪽만 고치는 사고가 반드시 난다.
// 좌표와 배치는 형식마다 다르지만 무엇을 적는가는 하나여야 한다.
// 계획서 수치가 바뀌면 이 파일만 고치고 두 형식을 다시 굽는다.
//
// 출처는 전부 계획서다. 7.4 소거 절차, 7.11 심각도, 7.12 자동화 계층,
// 하네스 검증설계 3절 LB 배선 실측이다. 여기서 새로 만든 수치는 없다.

// 손이 닿는 정도. 도면 2 의 색 축
export const TIER_COLOR = {
  AUTO: '#0E7C66',
  APPR: '#B8730A',
  HAND: '#5C6B75',
  GAP: '#C0392B',
}

// 계층. 도면 3 의 색 축
export const LAYER_COLOR = {
  L3: '#8C4FFF',
  L4: '#0972D3',
  L7: '#0E7C66',
  DATA: '#B8730A',
}

/* ========== 도면 2. 장애 대응 흐름 ========== */

export const FLOW_KPI = [
  ['알람', '11종', false],
  ['통지 경로', '0종', true],
  ['자동 복구 실측', 'E4 1회', false],
  ['수동 잔여', '4층 7항목', false],
]

export const STATIONS = [
  { n: '1', t: '감지', s: '메트릭 임계 이탈', tier: 'AUTO', tl: '자동', gap: '통지 경로 없음' },
  { n: '2', t: '심각도 판정', s: 'S1 · S2 · S3', tier: 'HAND', tl: '사람', gap: '어휘 두 갈래' },
  { n: '3', t: '계층 소거', s: 'ICMP -> TCP -> HTTP', tier: 'HAND', tl: '사람', gap: '프로브 0건' },
  { n: '4', t: '조치', s: '계층별 Runbook', tier: 'APPR', tl: '층에 따라', gap: '런북 0건' },
  { n: '5', t: '복구 확인', s: '프로브와 API 응답', tier: 'AUTO', tl: '자동', gap: null },
  { n: '6', t: '데이터 판정', s: '불변식 권위 run', tier: 'AUTO', tl: '자동', gap: null },
  { n: '7', t: '종료', s: 'verdict 아카이브', tier: 'HAND', tl: '사람', gap: null },
]

export const SEV = [
  ['S1', '즉시 호출', 'LedgerImbalance · 불변식 CRITICAL · FailoverRtoExceeded · LeaderAbsent · RecoverableStandDownRepeating', 'GAP'],
  ['S2', '당일 근무 시간', '불변식 HIGH · SequencerQueueBackpressure · OrderRejectSpike', 'APPR'],
  ['S3', '다음 영업일', '불변식 WARN · 스냅샷 3종 · 체결 전파 지연 초과', 'HAND'],
]

export const SEV_NOTE = '11종 전부 통지 경로가 없어 무음이다. 사람이 화면을 열기 전까지 장애를 모른다'

export const TIERS = [
  ['1층', '자가 복구', '개입 0', 'AUTO', '리스 만료 후 무개입 승격 · readiness 503 자동 배제 · 쓰기 fencing · 좀비 리더 자발적 강등 · 백프레셔 무대기 거절'],
  ['2층', '자동 완화', '승인 필요', 'APPR', '승인 명령 소비기 · 2인 승인 토큰 단회 소각'],
  ['3층', '자동 진단', '사람이 판단', 'HAND', '불변식 스캔 · 권위 run 판정 · 카오스 종료코드 9종 · CI 관측 게이트'],
  ['4층', '수동', '남은 작업', 'GAP', 'DB primary 승격 · 롤백 · 시크릿 주입 · 이미지 digest 반영 · 비용 확인'],
]

export const TIER_NOTE = '4층이 남은 작업의 목록이다. Patroni 설정 파일 0건이라 DB 승격이 현재 수동이다'

export const NOAUTO = [
  ['알람에 자동 액션 연결', '알람 11개의 실제 발화 확인 건수가 0이다. 정확도 미검증 신호에 롤백을 걸면 오탐이 곧 사고다'],
  ['파괴 액션을 서버가 직접 수행', '리더를 죽이는 명령을 받은 프로세스가 그 명령으로 사라지면 기록할 주체가 없다'],
  ['종신 강등 노드의 자동 재승격', '재생 불가 노드가 자동 복귀하면 승격과 반납이 반복돼 서비스가 계속 끊긴다'],
  ['수용 제한 대기열', '큐를 두면 노드를 죽인 대기 스레드가 다시 쌓인다'],
  ['시계 스큐 초과 시 실험 강행', '거짓 FAIL 이 남는다. 자동화가 판단을 내리지 않고 판단 불가를 선언한다'],
]

export const LEDGER_WHY = [
  '보정 자체가 새 쓰기다. 잘못된 보정은 원래 상태로 돌아갈 근거를 없앤다.',
  '긴급하다는 것과 자동으로 고쳐도 된다는 것은 다른 판단이다. S1 이면서 4층인 유일한 자리다.',
]

export const SAFE_COND = [
  '조치 대상이 상태를 갖지 않거나 되돌릴 수 있는가',
  '오작동했을 때 영향 범위가 조치 전보다 넓어지지 않는가',
  '조치가 들었는지를 기계가 판정할 수 있는가',
]

/* ========== 도면 3. 계층 판별 ========== */

export const TRIAGE_KPI = [
  ['계층 프로브', '0건', true],
  ['판별 소요 실측', '없음', true],
  ['대조군 있는 사례', '1건', false],
]

export const TRIAGE_NODES = [
  { q: 'ICMP 응답하는가', no: '실패', layer: 'L3 도달', col: 'L3',
    cause: '라우팅 · 보안그룹 · 인스턴스 정지', own: '네트워크' },
  { q: 'TCP 연결 성립하는가', no: '실패', layer: 'L4 연결', col: 'L4',
    cause: '프로세스 정지 · 방화벽 · 백로그 포화', own: '인프라' },
  { q: 'HTTP 2xx 인가', no: '5xx · timeout', layer: 'L7 응답', col: 'L7',
    cause: '프록시 설정 · 앱 내부 · 타겟그룹 unhealthy', own: '개발' },
  { q: 'DB 커넥션과 질의가 정상인가', no: '포화 · 지연', layer: '데이터', col: 'DATA',
    cause: '커넥션 누수 · 장기 트랜잭션 · 잠금 경합', own: '개발' },
]

export const TRIAGE_TABLE = [
  ['실패', '실패', '실패', 'L3 도달', 'L3', '라우팅 테이블과 보안그룹'],
  ['성공', '실패', '실패', 'L4 연결', 'L4', '프로세스 생존과 리스닝 포트'],
  ['성공', '성공', '5xx', 'L7 응답', 'L7', 'ALB 타겟그룹 상태와 앱 로그'],
  ['성공', '성공', 'timeout', 'L7 무응답', 'L7', '스레드 덤프와 요청 풀'],
  ['성공', '성공', '2xx', '계층 아님', 'DATA', 'DB 지연과 수요 축'],
]

export const INJECT_TOOLS = [
  ['L3', 'tc netem', '지연 · 손실 · 재정렬', 'L3', '커널. 검증면 전용'],
  ['L4', 'nftables', 'DROP · REJECT', 'L4', '커널. 검증면 전용'],
  ['L7', 'process kill · Toxiproxy', 'SIGTERM · 프록시 모사', 'L7', '프록시 모사는 커널 큐를 재현하지 않는다'],
]

export const TRIAGE_CASES = [
  {
    title: 'nginx 백업 업스트림 max_fails 기본값',
    before: 'no live upstreams 44,050건 · 실패율 9.61%',
    after: 'max_fails=0 에서 568건 · 0.89%',
    note: null,
  },
  {
    title: 'nginx resolve 미설정',
    before: '컨테이너 재생성 후 죽은 IP 지속 타격 · 502 응답 10,239건. 교정 후 주소 변경을 추종한다',
    after: null,
    note: '다만 같은 창 5xx 147건은 미규명이라 이 교정이 5xx 를 0으로 만든다고 주장하지 않는다',
  },
]

export const TRIAGE_GAP = {
  head: '이 도면은 설계다. 분기의 5단계 중 셋이 지금 실행 불가다',
  body: [
    'ICMP · TCP · HTTP 프로브가 0건이라 위 트리의 첫 세 분기를 지금은 탈 수 없다. 계층 프로브 배선이 이 축의 선행 작업이고,',
    '그것이 되기 전까지 계층 판별은 로그 상관 조회와 패킷 캡처에 의존한다. 판별 소요를 재는 지표도 그 뒤에 생긴다.',
  ],
  foot: '실측 사례 둘은 프로브 없이 사후 분석으로 밝힌 것이다. 사후 분석이 가능하다는 것과 장애 중에 좁힐 수 있다는 것은 다르다',
}
