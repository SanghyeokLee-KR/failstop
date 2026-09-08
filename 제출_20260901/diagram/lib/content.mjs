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
  { n: '1', t: '감지', s: '메트릭 임계 이탈', tier: 'AUTO', tl: 'AUTO · 자동', gap: '통지 경로 없음' },
  { n: '2', t: '심각도 판정', s: 'S1 · S2 · S3', tier: 'HAND', tl: 'OPERATOR · 사람', gap: '어휘 두 갈래' },
  { n: '3', t: '계층 소거', s: 'ICMP -> TCP -> HTTP', tier: 'HAND', tl: 'OPERATOR · assisted', gap: '프로브 0건' },
  { n: '4', t: '조치', s: '계층별 Runbook', tier: 'APPR', tl: '자동화 층에 따라 다름', gap: '런북 0건' },
  { n: '5', t: '복구 확인', s: '프로브와 API 응답', tier: 'AUTO', tl: 'AUTO · 자동', gap: null },
  { n: '6', t: '데이터 판정', s: '불변식 권위 run', tier: 'AUTO', tl: 'AUTO · 자동', gap: null },
  { n: '7', t: '종료', s: 'verdict 아카이브', tier: 'HAND', tl: 'OPERATOR · 사람', gap: null },
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
  'Ledger invariant violation = S1. 자동 보정하지 않는다.',
  '잘못된 보정은 원인 분석 근거와 원래 상태를 함께 훼손한다. S1 이면서 4층 수동인 유일한 자리다.',
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
  ['L3', 'tc netem', '지연 · 손실 · 재정렬', 'L3', 'Kernel-level Injection · 검증면 전용'],
  ['L4', 'nftables', 'DROP · REJECT', 'L4', 'Kernel-level Injection · 검증면 전용'],
  ['L7', 'process kill · Toxiproxy', 'SIGTERM · Proxy Simulation', 'L7', 'Proxy-level Simulation 은 커널 큐를 재현하지 않는다'],
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

export const TRIAGE_STATUS = {
  title: 'Current Status',
  items: [
    ['판별 로직', 'Designed', '7.14 분기 트리와 조합 판정표'],
    ['L3 / L4 / L7 프로브 배선', 'BLOCKED · pending', '11.4 A2 · W2. 배선 전까지 첫 세 분기를 탈 수 없다'],
    ['클라우드 검증', 'NOT RUN', '배포 0건'],
    ['로컬 참조 사례', 'Available', 'nginx upstream 실측. 프로브 없이 사후 분석으로 밝힌 것이다'],
  ],
  foot: '사후 분석이 가능하다는 것과 장애 중에 좁힐 수 있다는 것은 다르다. 배선 전까지 판별은 로그 상관 조회와 패킷 캡처에 의존한다',
}

/* ========== 도면 공통 메타 ========== */
// 제목과 메타데이터 블록. SVG 판과 draw.io 판이 여기서 읽어 제목이 갈리지 않는다.
// Ref 는 실재하는 계획서 절만 적는다. 없는 절 번호를 만들지 않는다.

export const FIG_META = {
  arch: {
    no: 'Figure 1',
    title: 'Service Architecture Overview',
    sub: 'Application 2AZ  ·  Single Writer  ·  PostgreSQL Streaming Replication',
    meta: [
      'As of: 2026-08-31',
      'Scope: Steady + Proof Environment',
      'Status: Partially Implemented · Cloud NOT RUN',
      'Source: 저장소 실측 + 기획안 6절',
      'Ref: 기획안 6절 인프라 · 10절 검증 · 12.7 Scorecard',
    ],
  },
  triage: {
    no: 'Figure 2',
    title: 'Fault Isolation and Troubleshooting Flow',
    sub: 'L3 / L4 / L7 / 애플리케이션 / DB 장애 계층 판별',
    goal: '운영 목표. 서비스가 안 된다는 현상을 실제 장애 계층으로 구체화한다',
    meta: [
      'As of: 2026-08-31',
      'Status: Design Complete · Probe Wiring Pending',
      'Ref: 기획안 2.5 · 7.4 · 7.14 · 10.3',
    ],
  },
  flow: {
    no: 'Figure 3',
    title: 'Incident Response and Recovery Flow',
    sub: 'Detection -> Classification -> Recovery -> Data Validation',
    meta: [
      'As of: 2026-08-31',
      'Status: Partial Automation',
      'Ref: 기획안 7.11 · 7.12 · 7.15 · 7.16',
    ],
  },
}

/* ===================== 도면 1. Service Architecture ===================== */
// arch_aws.mjs(SVG)와 drawio.mjs(편집본)가 아래를 함께 읽는다. 좌표는 각 생성기가
// 따로 갖고 여기에는 무엇을 적는가만 둔다. 수치가 바뀌면 이 블록만 고친다.

// 값과 측정 범위를 반드시 함께 적는다. 범위 없는 숫자는 도면 1 에 없다
export const ARCH_KPI = [
  ['Application HA', '2 AZ', '설계 · Cloud NOT RUN', 'INK'],
  ['전체 AZ 내성', '미성립', 'DB 승격 수동', 'FAIL'],
  ['Failover RTO', '6.688 s', 'Local · 1 run', 'INK'],
  ['RPO', '0', 'Local · same run', 'INK'],
  ['Ledger 위반', '0 rows', 'Local validation', 'INK'],
]

// 외부 진입. CloudFront 에서 정적과 동적이 갈리는 것이 상단의 요지다
export const ARCH_EDGE = {
  chain: [
    ['users', '사용자', null],
    ['route53', 'Route 53', null],
    ['cloudfront', 'CloudFront', 'ACM  us-east-1'],
  ],
  fork: [
    ['s3', 'Amazon S3', '정적 · /*', null],
    ['alb', 'Application Load Balancer', '동적 · /api/*  /ws/*', 'ACM  ap-northeast-2'],
  ],
  contracts: [
    ['tg', 'Target Group  x2',
      '쓰기 /actuator/health/readiness   ·   조회 /actuator/health/read   port 8081',
      '쓰기는 팔로워가 503 이라 빠지고 조회는 두 노드가 다 남는다. 트래픽 8080 과 헬스체크 8081 이 다르다'],
    ['ssm', '운영 경로  ·  Operations',
      'SSM Session Manager 단독 · SSH 22 전 노드 미개방 · NAT 인스턴스 AZ 로컬 2대',
      '관리 평면 8443 mTLS 는 ALB 뒤에 두지 않는다. TLS 종단이 X509 전달을 끊는다'],
  ],
  probeBadge: 'L3/L4/L7 probe · not wired',
}

// 주문 하나가 밟는 여섯 정거장. kind 가 지속성과 파생을 형태로 가른다
export const ARCH_STATIONS = [
  ['1', '접수', '서명 · nonce · 멱등', 'mem'],
  ['2', '저널 append', 'command_journal · fsync', 'dur'],
  ['3', 'ACK', '여기서 200 을 낸다', 'mem'],
  ['4', '매칭엔진', 'single writer · seq 오름차순', 'sole'],
  ['5', '원장 정산', '복식부기 · 한 트랜잭션', 'dur'],
  ['6', '프로젝션', '재계산 가능한 파생', 'derived'],
]

export const ARCH_STATION_LEGEND =
  '옅은 채움 = PostgreSQL 에 남는다     흰 바탕 = 메모리에서 돈다     파선 = 재계산 가능한 파생     굵은 테두리 = 이중화할 수 없는 유일점'

// 두 가용영역. 같은 것은 같은 자리에 둔다
export const ARCH_AZ = [
  {
    id: 'a', name: '가용영역 ap-northeast-2a', tone: 'OK',
    note: '정상 서비스 경로. 쓰기 경로 전체가 이 안에 있다 · private-app-a / private-db-a',
    app: ['spring', 'app-1', 'Leader  ·  EC2 t4g.small  ·  Spring Boot', null, null],
    db: ['pg', 'PostgreSQL 17', 'primary  ·  EC2 t4g.small', 'SPOF · 승격 수동', 'FAIL'],
  },
  {
    id: 'c', name: '가용영역 ap-northeast-2c', tone: 'IDLE',
    note: '복구 경로. 평시에는 트래픽을 받지 않는다 · private-app-c / private-db-c',
    app: ['spring', 'app-2', 'Follower  ·  readiness 503', null, null],
    db: ['pg', 'PostgreSQL 17', 'standby  ·  streaming replication', 'PLANNED', 'IDLE',
      'Patroni 설정 0건 · 승격 현재 수동'],
  },
]

// AZ-c 동거 구성요소. 배선 상태를 라벨로 함께 둔다. 배선 없는 아이콘은 답할 자산이 없다
export const ARCH_COTENANT = [
  [['prom', 'grafana'], '관측  Prometheus · Grafana', 'app-2 노드 동거. AZ-a 상실 시 승격을 관측해야 한다',
    '상시면 컴포즈 정의 0건 · Local harness only'],
  [[], 'Bot Fleet', 'standby 노드 동거 계획 · 24시간 기저 트래픽',
    'compose 정의 0건 · W1'],
]

// 온프레미스 검증면. 커널 계층 주입 전용이다
export const ARCH_VERIFY = {
  icon: 'linux',
  note: '세션 구동 · 비용 0 · Rocky Linux 10 · VM 2대 또는 network namespace · Kernel-level Injection 전용',
  tools: [
    ['L3', 'tc netem', '지연 · 손실 · 재정렬', 'Kernel Injection'],
    ['L4', 'nftables', 'DROP · REJECT', 'Kernel Injection'],
    ['L7', 'process kill', 'SIGTERM · SIGKILL', 'Fault Injection'],
  ],
  why: [
    '커널 계층 주입은 이 평면 전용이다. 클라우드 노드에 걸면 SSM 접근 경로가 같은 네트워크를 타서 노드를 통째로 잃는다.',
    '두 평면을 잇는 것은 상시 터널이 아니라 같은 GHCR digest 와 저장소에 커밋한 verdict 파일이다.',
  ],
  motto: '검증면이 탐색하고, 증명면이 재현하고, 상시면이 서비스한다.',
}

// 한계는 경고문이 아니라 검토 자료다. 이 목록이 남은 작업의 정의다
export const ARCH_LIMITS = [
  'Application 계층만 2 AZ 다. 전체 AZ 장애 내성은 미성립이고 AZ-a 상실 시 DB 승격 소요는 미측정이다',
  'PostgreSQL 승격이 수동이다. Patroni · etcd 설정 파일 0건. primary 가 자동 복구 기준의 단일 장애점이다',
  '클라우드 배포와 카오스 실측이 NOT RUN 이다. RTO · RPO · Ledger 수치는 전부 로컬 카오스 스택 실측이다',
  '상시면 관측 스택 컴포즈 정의 0건, 알람 통지 경로 0건. E1 · E2 · E5 · E9 는 상시면 이식 불가라 카오스는 로컬 전속이다',
]

// 같은 배치, 다른 상태. 좌표가 패널마다 동일해야 상태 차이만 보인다
export const ARCH_FILM = [
  ['정상', 'ok', [['app-1', 'ok'], ['primary', 'ok']], [['app-2', 'idle'], ['standby', 'ok']], '불변식 26 체크 통과'],
  ['app-1 kill', 'ok', [['app-1', 'fail'], ['primary', 'ok']], [['app-2', 'ok'], ['standby', 'ok']], 'RTO 6.688 s · Local 1 run'],
  ['AZ-a 상실', 'trans', [['app-1', 'fail'], ['primary', 'fail']], [['app-2', 'ok'], ['standby', 'trans']], 'DB 승격 필요 · 미측정'],
  ['승격 · 재생', 'trans', [['app-1', 'fail'], ['primary', 'fail']], [['app-2', 'trans'], ['primary', 'ok']], '저널 재생 중 · 503 재시도'],
]

// 비용은 계획서 9.5 달력 월 판정과 같은 값이다
export const ARCH_COST = [
  ['BASELINE', '24/7', 'Route 53 · CloudFront · S3. 외부 접속 요건 성립', 'USD 0.72 /월'],
  ['HA STEADY', '24/7', '위 토폴로지 전체. app x2 · primary/standby · NAT x2', '달력 월 97,318원'],
  ['PROOF', '창 구동', '부하 발생기 c7g.large 단독. 달력 월 45시간 예산', 'USD 0.0816 /hr'],
]

export const ARCH_FOOT =
  '수치에 출처가 없으면 미측정이다. RTO 6.688 s 는 E4-20260829 로컬 실측 1회차이고 AZ 상실 회차는 아직 재지 않았다. 비용은 계획서 9.5 달력 월 판정과 같은 값이다.'
