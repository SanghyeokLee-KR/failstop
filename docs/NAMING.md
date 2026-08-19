# 프로젝트 명명 결정서

대상: 고가용성 시뮬레이션 거래소 워크로드 위에서 재생 결정론·페일오버 정확성·원장 무결성을 증명하는 저장소.
목적: GitHub 저장소 이름 및 대외 지칭 확정.
방법: 5개 축에서 후보 생성, 웹 검색으로 충돌·코인 오염 검증, 3개 렌즈(채용 담당 엔지니어 / 검색 고유성 / 과장 탐지)로 채점.

---

## 1. 최종 추천 3개

세 후보는 서로 다른 축을 가리킨다. 1안은 장애 모델, 2안은 불변식, 3안은 검증 도구다. 셋 중 하나를 고르는 일은 취향 선택이 아니라 이 저장소를 무엇으로 부를지 정하는 일이다.

### 1안. failstop (페일스톱)

- **저장소 표기**: `failstop`. 하이픈형 `fail-stop`은 쓰지 않는다. 그 표기는 논문·강의자료와 검색을 나눠 갖는다.
- **뜻**: fail-stop. 노드가 이상 동작 없이 그냥 멈춰 죽는다고 보는 내결함성 문헌의 고전 장애 모델.
- **면접 답변**: "노드가 조용히 멈춘다는 fail-stop 가정에서 출발했고, freeze나 그레이 장애처럼 그 가정이 깨지는 경우까지 실험으로 주입해 stale 쓰기 0을 실측한 프로젝트라 모델 이름을 그대로 달았다."
- **점수**: hiring 8 / search 7 / honesty 8, 합계 23.
- **맞는 사람**: 이름 한 줄로 분야를 세우는 것이 최우선인 경우. 내결함성 문헌을 읽고 온 사람이라는 신호가 설명 없이 전달된다. 인프라·SRE·플랫폼 직군 지원에서 가장 손해가 적다.
- **감수할 약점**: 앞머리가 fail이라 비전공 심사자(인사, 비개발 면접관)에게는 실패로 먼저 읽힌다. 서류 1차가 비개발자를 거치는 조직에서는 이 오독이 실제로 발생한다. 또 이름이 실제 범위보다 좁다. 이 프로젝트는 fail-stop이 아닌 장애(freeze, 그레이)까지 다루는데 이름은 fail-stop만 말한다. 축소 방향의 부정확이라 과장보다는 낫지만, "왜 fail-stop이라 해놓고 비-fail-stop을 다뤘냐"는 질문은 반드시 나온다. 그 질문에 답할 준비를 이름값에 포함해야 한다.

### 2안. nullsum (널섬)

- **저장소 표기**: `nullsum`.
- **뜻**: 합이 0. 독일어 Nullsumme 형태. 복식부기 원장 전체 잔액의 대수합이 항상 0이라는 불변식 자체를 이름으로 삼았다.
- **면접 답변**: "장애를 주입하는 동안에도 복식부기 자산 합이 0을 유지하는지가 이 시스템의 유일한 합격 기준이라, 그 문장을 그대로 이름으로 썼다."
- **점수**: hiring 4 / search 8 / honesty 9, 합계 21.
- **맞는 사람**: 이름이 면접에서 반박당하지 않는 것을 최우선에 두는 경우. 이름이 가리키는 대상이 SQL 불변식 C01~C25로 실재하고 실측 결과가 있어, 코드를 열어 이름을 방어하기가 가장 쉽다. 원장·정합성·금융 백엔드 쪽 서사를 밀 계획이면 1안보다 낫다.
- **감수할 약점**: 채용 렌즈 4점이 명백한 결격이다. null은 프로그래밍에서 부재·오류 뉘앙스가 강해 부정적으로 읽히고, zero-sum game 연상 때문에 게임이론이나 경제 시뮬레이션 습작으로 분류될 확률이 실재한다. 이름만 보고 분산시스템이라고 알아보는 사람은 없다. 즉 이 이름은 저장소를 열어본 사람에게만 작동하고 목록에서 스캔당하는 단계에서는 도움이 되지 않는다. 채택하려면 README 첫 줄과 저장소 description이 그 공백을 전부 메워야 한다.

### 3안. faultbench (폴트벤치)

- **저장소 표기**: `faultbench`.
- **뜻**: fault injection과 testbench의 결합. 결함을 주입한 채로 대상을 돌려 계측하는 시험대.
- **면접 답변**: "결함을 주입한 상태로 거래소 워크로드를 돌리고 원장 합과 페일오버 결과를 자동 판정하는 시험대라, 하드웨어 검증의 테스트벤치를 시스템 단위로 올린 이름을 썼다."
- **점수**: hiring 7 / search 8 / honesty 7, 합계 22.
- **맞는 사람**: 검색에서 이 저장소가 1위를 잡는 것이 최우선인 경우. 조어라 토큰화 선례가 없고 동명 오픈소스도 학술 벤치마크 하나뿐이라 이름 점유가 가장 쉽다. 카오스 엔지니어링·QA 자동화·플랫폼 검증 직군을 노린다면 뜻이 즉시 읽힌다는 장점이 크다.
- **감수할 약점**: 이름이 도구를 가리켜 본체가 가려진다. 이 저장소에는 매칭엔진·원장·웹 프런트가 전부 들어 있는데 faultbench는 harness/ 디렉터리 하나만 가리키는 것처럼 들린다. 규모를 실제보다 작게 말한다. 두 번째로 bench가 benchmark로 오독돼 성능 측정 도구로 분류될 수 있다. 세 번째로 은유가 없어 기억에 남는 힘이 약하다. 뜻은 즉시 전달되지만 하루 뒤에 이름이 기억나지 않는다.

**기본값**: `failstop`. 이 자산의 1차 소비자가 채용 담당 엔지니어이고, 세 렌즈 중 그 렌즈에서 최고점이면서 나머지 두 렌즈에서 손실이 없는 유일한 후보다.

---

## 2. 차점 후보 표

| 이름 | 축 | hiring / search / honesty | 합 | 한 줄 평 | 충돌 상태 |
| --- | --- | --- | --- | --- | --- |
| epochfence | 구현 메커니즘(펜싱 에폭) | 8 / 5 / 8 | 21 | 채용 렌즈는 1안과 동률이지만 어근 epoch가 스테이킹 주기라는 크립토 핵심어라 제약 1번과 어긋난다 | CLEAR. 복합어 동명 토큰 0건, PacketFence 형태 연상만 존재 |
| fencewright | 구현 메커니즘(펜싱) + 장인 조어 | 5 / 9 / 5 | 19 | 검색 고유성 최고점. 대신 -wright 접미사가 자기 미화로 읽혀 시니어에게 잡힌다 | MINOR. 호주 울타리 시공사, 영국 법인. 코인 0건 |
| ringlane | 자료구조(링버퍼 단일 라이터) | 4 / 7 / 7 | 18 | 링버퍼를 아는 사람에게만 반쯤 읽히고 lane 접미어는 최근 남발돼 신선도가 없다 | MINOR. ringlane.com 주얼리 소매, 검색 상위 쿠폰 스팸 오염 |
| refold | 이벤트 소싱(fold 재적용) | 5 / 3 / 9 | 17 | 정직성은 최상위지만 어학 브랜드 Refold와 단백질 재접힘 문헌이 검색을 이미 먹었다 | MINOR. refold.la, Refold AI. 코인 0건 |
| writepath | 구조 서술(단일 쓰기 경로) | 6 / 3 / 8 | 17 | 실무 용어라 분야는 서지만 일반어라 변별력이 없다. KILL:search | MINOR. WritePath Inc가 GitHub 조직명·.co 도메인 선점 |
| fixpoint | 수학(고정점) | 6 / 4 / 6 | 16 | 이론 문해력 신호는 오나 정적분석·컴파일러 쪽으로 먼저 읽힌다 | MINOR. YC 스타트업 fixpoint.co, github.com/fixpoint. FIX 티커 대역 혼잡 |
| escapement | 은유(시계 탈진기) | 3 / 6 / 7 | 16 | 비유는 정교하나 이름에서 오는 것은 시계 부품이다. KILL:hiring | MINOR. 시계 전문 매체, hodinkee/escapement-rb |
| standdown | 페일오버(옛 리더 강등) | 4 / 6 / 5 | 15 | 군사 관용어로 먼저 읽히고, 강등 신호가 라이브 미발동으로 남은 이력이 있어 방어가 불안하다 | MINOR. Rakuten Rewards standdown-sdk, Operation Stand Down |
| stillzero | 실측 결과(합·stale 쓰기 0) | 2 / 6 / 7 | 15 | 자기 성적표를 이름으로 삼는 구조라 홍보 문구로 읽힌다. KILL:hiring | MINOR. 이탈리아 음료 브랜드, 미국 록밴드. zero 형태소 인접 오염 |
| orrery | 은유(태양계 기계 모형) | 2 / 7 / 6 | 15 | 뜻을 아는 사람이 드물고 분야 정보를 주지 않는다. KILL:hiring | MINOR. CaseyHaralson/orrery, 취미 시각화 저장소 다수 |
| playhead | 은유(저널 오프셋 커서) | 3 / 4 / 7 | 14 | 미디어 편집 어휘라 2초 스캔에서 영상 도구로 오분류된다. KILL:hiring | MINOR. playheadapp.com, Steam 게임 Playhead |

---

## 3. 탈락과 이유

### 3.1 코인 오염으로 사망 (13건)

이번 검증의 핵심 산출물이다. 후보가 좋아 보일수록 크립토 쪽에서 이미 쓰고 있었다. 제약 1번(암호화폐 연상 차단)을 지키려고 고른 이름이 정확히 그 반대로 작동한 사례가 절반을 넘는다.

| 이름 | 오염 실체 | 심각도 |
| --- | --- | --- |
| spindle | SPINDLE(SPD) ERC-20 실재. 일본 최대 규모 ICO, 가수 GACKT 전면 홍보, 금융청 무등록 영업 통지 후 폭락, 일본 매체가 사기 코인으로 다룸 | 최악. 검색 1페이지가 코인 사기 기사 |
| noether | noether.exchange. Stellar Soroban 기반 무기한 선물 DEX, 13개 perp 마켓, 10배 레버리지 광고. cartesi/noether PoS 노드 | 최악. 동명의 실제 크립토 거래소 |
| gauntlet | gauntlet.xyz. DeFi 리스크 관리 기업, 하루 수천 건 에이전트 시뮬레이션으로 프로토콜 스트레스 테스트. Aave·Compound 고객 | 최악. 업무 내용까지 이 프로젝트와 동형 |
| quipu | QuipuSwap(Tezos 최대 DEX)와 거버넌스 토큰 QUIPU, Binance 구매 가이드 존재. 별도로 상용 회계 SaaS getquipu.com | 거래소·토큰 동시 충돌 |
| crucible | Alchemist Crucible v1 ERC-721, $MIST 스테이킹 결합. 추가로 Atlassian Crucible 상용 코드 리뷰 제품 | 크립토 상품과 개발자 도구 이중 충돌 |
| touchstone | touchstone.city(Flow 블록체인 NFT 발행 플랫폼), Touchstone ITS(블록체인 개발사). 어원 자체가 금 순도 판정 도구 | 크립토 서비스 2건 + 상표 밀도 |
| counterpoise | counterpoise-finance.com. 시총 상위 10종 동일 비중 주간 리밸런싱, 연 200% 초과 수익 광고, 현재 DNS 소멸 | 고수익 표방 후 접힌 크립토 펀드와 동명 |
| zerodrift | zerodrift.xyz(Web3 스마트컨트랙트 감사, 온체인 50억 달러 보호 주장). 별개로 a16z 투자 zerodrift.com | 크립토 보안 기업 + 자금 조달 완료 동명사 상표 위험 |
| laplace | laplace.digital. 크립토 퀀트에서 출발해 토큰화 증권·기관용 스테이블코인 인프라를 파는 디지털 투자은행 | 크립토 금융 기업 실재 |
| idem | IDEM Club 유틸리티 토큰(미러 도메인 다수, idempay.net 결제 데모). 동시에 VMware Idem이 멱등 개념을 선점 | 토큰 + 동일 개념 오픈소스 |
| chaosproof | 동일 철자 토큰은 없으나 CHAOS 토큰이 Base·Solana 등에 다수(AIXBT 발행분 시총 2500만 달러). proof는 PoW·PoS 접미사 | 두 크립토 형태소를 직결한 조어. 이름이 티커처럼 읽힘 |
| lane-zero | 검색이 통째로 LayerZero(ZRO)와 Layer 0 코인 카테고리로 흡수. 발화에서 layer zero로 오독 | 근접 동음이의 흡수 |
| retrace | 토큰은 없으나 retracement가 크립토·외환 차트 은어라 검색 1페이지가 전부 크립토 트레이딩 콘텐츠. 별도로 Stackify Retrace 상용 APM | 어휘 자체가 크립토 은어 |

교훈 세 가지를 남긴다.
첫째, 동명 토큰 0건이 청정을 뜻하지 않는다. retrace, chaosproof, lane-zero는 정확 일치 토큰이 없는데도 검색 결과가 크립토로 채워졌다. 어근 단위로 검증해야 한다.
둘째, 정확성·검증·리스크를 뜻하는 좋은 단어는 DeFi 리스크 업계가 먼저 쓰고 있다. gauntlet, counterpoise, zerodrift가 같은 패턴이다.
셋째, 어원이 귀금속·화폐·순도 판정과 닿은 단어(touchstone, crucible)는 크립토가 반드시 선점했다.

### 3.2 코인 외 사유로 사망 (15건)

| 이름 | 사유 | 점유자 |
| --- | --- | --- |
| sequent | 동일 분야 정면 충돌 | Sequent Computer Systems(분산·병렬 벤더), zilverline/sequent(CQRS·이벤트소싱 프레임워크) |
| fencepost | 동일 분야 + 의미 사고 | pivovarit/fencepost(PostgreSQL 펜싱 토큰 락킹, Java). fencepost error는 off-by-one 버그의 표준 표현 |
| hotpath | 동일 분야 활성 도구 | hotpath.rs(Rust 프로파일러, crates.io·도메인 선점) |
| cutover | 동일 업종 상용 제품 | Cutover Ltd. 재해복구·페일오버 리허설 런북 플랫폼을 미국 3대 은행에 판매 |
| reconciler | 상용 제품 + 일반 용어화 | EY Blockchain Analyzer: Reconciler. 인프라 엔지니어에게 reconciler는 오퍼레이터 내부 함수명 |
| proofmark | 활성 소프트웨어 2건 | proofmark.it(2025-12 런칭 진위 인증 플랫폼), proofmark.dev(LLM 품질 게이트) |
| deadreckon | 동일 철자 + 인접 도메인 | gregce/deadreckon. 에이전트 감독 하네스, 서명 기반 워치독 게이트 |
| pawl | 대규모 오픈소스 + 겹치는 분야 | ratchetphp/Pawl. Packagist 설치 1059만, WebSocket 클라이언트. 상위 조직명이 ratchet |
| splitguard | 선행 논문·구현 점유 | SplitGuard(arXiv 2108.09052, ACM WPES 2022) 및 공식 구현 저장소 |
| verbatim | 전역 상표 | Verbatim(1969년 창업 저장매체 브랜드). 사명 자체가 정확한 데이터 기록 원칙에서 유래, 57년 선점 |
| invar | 집행되는 상표 | Aperam Alloys Imphy 등록상표(1906 출원). WIPO D2012-0773으로 invar.com 도메인 이전 판정 |
| linearizer | 상표 + 제품군 유통 | Linearizer Communications Group, MACOM이 4900만 달러에 인수 후 제품명 유지 |
| holdfast | 상용 게임 점유 + 상표 | Holdfast: Nations At War. GitHub 토픽까지 게임이 점유. HOLD FAST 등록상표 복수 |
| fenceline | 소프트웨어 회사 + 산업 용어 | fenceline.ai(PitchBook 등재), fenceline monitoring(EPA 규정 준수 분야), fenceline community(환경정의 용어) |
| lockstep | 상용 제품 + 일반명사성 | Sage 소유 Lockstep(회계·수금 SaaS). 게다가 lockstep은 이 프로젝트가 쓰는 결정론 모델의 표준 용어라 고유명으로 기능하지 못한다 |

---

## 4. 판단 노트

### 4.1 합계 1위를 그대로 밀지 않았는지

밀었다. 다만 합계가 높아서가 아니다. failstop과 faultbench의 차이는 1점이고 그 1점은 반올림 오차 수준이다. 실제 근거는 렌즈 분포다.

- faultbench: 7 / 8 / 7. 세 렌즈가 전부 중간 위. 어디서도 지지 않지만 어디서도 이기지 않는다.
- failstop: 8 / 7 / 8. 채용 렌즈 최고점.

이 자산의 소비자는 사실상 한 종류다. 저장소 목록에서 2초 안에 판단하는 채용 담당 엔지니어. 검색 고유성은 그 사람이 이름을 이미 알고 찾아올 때만 작동하고, 이 프로젝트에서 그런 상황은 이력서에 URL이 적혀 있는 경우뿐이다. 즉 search 렌즈의 1점은 hiring 렌즈의 1점보다 싸다. 그래서 가중치를 적용해 failstop을 위로 놓았다.

### 4.2 epochfence가 3위에 없는 이유

epochfence는 채용 렌즈 8점으로 failstop과 동률이다. "한 렌즈가 만점을 준 이름이 낫다"는 기준을 그대로 적용하면 올라와야 한다. 올리지 않은 이유는 두 가지다.

첫째, 그 8점이 failstop의 8점과 같은 종류다. 둘 다 내결함성 어휘로 분야를 세운다. 두 이름이 주는 정보가 거의 겹치므로 epochfence는 failstop의 부분집합이고, 대신 search 5로 손해만 본다. 선택지가 되려면 성격이 달라야 하는데 다르지 않다.

둘째, 어근 epoch가 제약 1번을 스친다. epoch는 Cardano·Solana 스테이킹 주기 단위이고 CoinGecko에 EPOCH 계열 토큰이 복수 상장돼 있다. 복합어로는 충돌이 없지만, 크립토 연상을 끊으려고 이름을 바꾸는 작업에서 크립토 스테이킹 어휘를 앞머리에 놓는 것은 목적과 어긋난다. 금지 어휘 목록에 없다는 것과 목적에 맞는다는 것은 다르다.

### 4.3 fencewright의 search 9를 왜 안 샀는가

fencewright는 검색 렌즈에서 이번 검증 통틀어 유일한 9점이다. 조어라 동명 토큰 0건, 동명 저장소 0건, 검색하면 이 저장소가 곧바로 1위를 잡는다. 그런데 합계는 19로 5위다. 정직성 5점이 깎았다.

두 렌즈의 성격이 다르다. 검색 고유성은 이름이 자기 자리를 지키는 능력이고, 정직성은 이름이 면접 자리에서 버티는 능력이다. 시니어 면접관은 -wright 접미사를 멋 부린 조어로 즉시 읽어내고, 그 인상은 이름이 검색 1위인 것과 무관하게 대화 내내 남는다. 검색은 한 번 이기면 끝이지만 정직성은 매번 심사받는다. 후자가 비싸다.

여기에 -wright의 실무 비용이 붙는다. 한국어 면접에서 "펜스라이트"라고 발음하면 light·right·write와 구분되지 않아 철자를 매번 불러줘야 한다. 이름을 말할 때마다 발생하는 마찰은 점수표에 잘 안 잡히지만 몇 년간 누적된다.

### 4.4 KILL 판정을 복구 불가로 본 근거

stillzero, orrery, playhead, escapement는 KILL:hiring이 붙었고, writepath는 KILL:search가 붙었다. 이 표시는 다른 두 렌즈 점수와 무관하게 후보를 탈락시켰다.

hiring KILL을 복구 불가로 본 이유는 위와 같다. 채용 렌즈가 이 자산의 존재 이유다. orrery는 검색 7 정직성 6으로 나쁘지 않은데 채용 2점이다. 뜻을 아는 사람이 드물어 저장소 목록에서 클릭이 일어나지 않고, 클릭되지 않은 저장소의 검색 순위는 의미가 없다. 즉 hiring이 0에 가까우면 다른 렌즈의 점수가 실현되지 못한다. 곱셈 관계이지 덧셈 관계가 아니다.

search KILL은 성격이 다르다. writepath는 GitHub 조직명과 .co 도메인이 활성 기업에 이미 잡혀 있다. 점수가 낮은 것이 아니라 이름을 가질 수 없다. 이건 판단이 아니라 사실 확인이다.

### 4.5 nullsum을 2안에 둔 근거

nullsum의 채용 4점은 다른 KILL 후보들과 비슷한 수준이다. 그런데 KILL을 붙이지 않고 2안에 올렸다. 차이는 회복 가능성이다.

orrery나 escapement의 낮은 점수는 이름이 엉뚱한 분야를 가리켜서 생긴 것이라 README로 고칠 수 없다. 이름이 계속 반대 방향을 가리킨다. nullsum은 아무 분야도 가리키지 않는 쪽에 가깝다. 정보가 틀린 게 아니라 없다. 이 공백은 저장소 description 한 줄과 README 첫 문장으로 메워진다. 그리고 그 자리에는 정직성 9점이 남는다. 이번 검증에서 유일한 9점이고, 이름이 가리키는 대상(SQL 불변식과 실측 결과)이 코드에 그대로 있어 면접에서 이름 때문에 곤란해질 일이 없는 유일한 후보다.

원장 정합성을 서사의 중심에 놓을 계획이면 이 이름을 1안보다 위로 올려도 된다. 그 경우 감수하는 것은 저장소 목록 스캔 단계의 손실이다.

### 4.6 이번 검증에서 배운 명명 규칙

- 좋은 기술 단어는 이미 팔리고 있다. 단독 검색 전에 "<이름> software company"와 "<이름> crypto token coin"을 먼저 친다.
- 조어가 안전하지만 조어 티가 나면 정직성에서 되돌려준다. faultbench는 서술형 조어라 살았고 fencewright는 장식형 조어라 깎였다.
- 이 프로젝트가 하는 일을 정확히 묘사하는 단어일수록 DeFi 리스크 업계와 충돌한다. 그쪽이 같은 일을 팔고 있기 때문이다.

---

## 5. 이름 적용 범위

### 5.1 현재 상태 실측

저장소를 실제로 조사한 결과다. 워크트리(`.claude/worktrees/`)는 제외했다.

- 문자열 `exchange`를 포함한 파일 351개. 내역은 server 242, web 46, harness 28, docs 26, 기타 9.
- 자바 패키지 `kr.exchange` 사용 파일 236개.
- `server/settings.gradle`: `rootProject.name = "exchange"`.
- `web/package.json`: `"name": "exchange-web"`.
- `harness/docker-compose.chaos.yml`: 컴포즈 프로젝트명 `exchange-chaos`, 이미지 태그 `exchange-chaos-app:local`, DB명·계정·비밀번호 기본값 `exchange`.
- `server/app/src/main/resources/application.yml`: Spring 설정 prefix `exchange:` 3개 프로파일, `spring.application.name: exchange`, 커넥션 풀명 `exchange-lease`, JDBC URL의 DB명 `exchange`.
- Prometheus 메트릭 `exchange_*` 20종 이상(`exchange_leader_epoch`, `exchange_ledger_imbalance`, `exchange_failover_downtime_seconds`, `exchange_journal_replay_commands_total`, `exchange_invariant_violations` 등). 룰 파일 `exchange-alerts.yml`·`exchange-recording.yml`, Grafana 대시보드 4종이 이 이름을 참조.
- 웹 라우트 `/exchange/[market]`.
- README H1: `# 고가용성 가상 암호화폐 거래소`.
- 로컬 폴더명: `프로젝트`(한글). 별도로 gradle 빌드용 정션 `C:\dev\exchange`.

### 5.2 판단: 공개 표면만 바꾸고 내부 식별자 `exchange`는 유지한다

근거 세 가지다.

**첫째, `exchange`는 프로젝트 이름이 아니라 도메인 모델 이름이다.** 제약은 저장소 간판이 코인 사이드 프로젝트로 분류되는 것을 막으려는 것이지, 도메인 어휘 자체를 지우라는 것이 아니다. 이 시스템은 실제로 주문·호가·체결을 다루므로 코드 안에서 `OrderBook`, `Trade`, `exchange` 패키지는 정확한 서술이다. 이걸 중립어로 치환하면 도메인 모델이 읽히지 않게 되고, 그건 코드 품질 감점이다. 저장소 이름은 프로젝트가 무엇을 증명하는지를 말하고, 패키지 이름은 무엇을 모델링하는지를 말한다. 층위가 다르다.

**둘째, 비용이 비대칭이다.** 패키지 리네임은 236개 파일과 그 import 전량에서 끝나지 않는다. Flyway 마이그레이션 경로, `@ConfigurationProperties` prefix, 리플렉션 참조, 하네스 SQL, 컨테이너 이미지 태그로 번진다. 더 나쁜 것은 회귀 형태다. 메트릭명 `exchange_*`를 바꾸면 빌드는 통과하고 Grafana 대시보드 4개와 Prometheus 룰 2개가 조용히 빈다. 카오스 실험 판정이 메트릭에 걸려 있으므로 판정 경로가 무증상으로 망가진다. 여기에 지금까지 축적한 실험 시계열의 연속성도 끊긴다. 이 프로젝트의 핵심 산출물이 "장애 중에도 어긋나지 않았다는 데이터"인데, 이름 때문에 그 데이터의 연속성을 버리는 것은 본말전도다.

**셋째, 얻는 것이 없다.** 채용 담당자는 패키지명을 grep하지 않는다. 판단은 저장소 이름, description, 토픽, README 첫 세 줄에서 끝난다. 그 네 곳만 바꾸면 목적의 전부를 달성한다. 저장소를 열어 `kr.exchange`를 본 사람은 이미 워크로드가 거래소라는 것을 README에서 읽은 뒤다. 그 시점에 패키지명이 도메인을 그대로 말하는 것은 감점이 아니라 일관성이다.

### 5.3 즉시 바꿀 것 (공개 표면)

1. GitHub 저장소 이름. 새 이름으로 변경. GitHub가 리다이렉트를 유지하므로 기존 clone은 깨지지 않는다.
2. 로컬 폴더명 `프로젝트` → 새 이름(ASCII). 부수 효과가 크다. 한글 경로 때문에 gradle 빌드에서 가짜 ClassNotFound가 나던 문제와, 그것을 우회하려고 만든 정션 `C:\dev\exchange` 의존이 함께 사라진다. 리네임 작업 중 유일하게 기술 부채를 갚는 항목이다.
3. `README.md` H1과 첫 문단. 현재 첫 줄이 "고가용성 가상 암호화폐 거래소"라 이름을 바꾼 효과가 그 자리에서 상쇄된다. 재배열 원칙은 성취를 앞에, 워크로드를 뒤에 놓는 것이다. 재생 결정론, 페일오버 정확성(stale 쓰기 0, RTO 5~14초), 원장 불변식 25종을 먼저 쓰고, 거래소는 "그것을 증명하려고 고른 워크로드"로 한 문장에 넣는다. 완전 시뮬레이션 고지는 유지한다.
4. 저장소 description과 토픽. 토픽 후보: `distributed-systems`, `chaos-engineering`, `event-sourcing`, `fault-injection`, `leader-election`, `double-entry-ledger`. `cryptocurrency`, `trading` 계열 토픽은 붙이지 않는다.
5. `docs/README.md`와 각 정본 문서 헤더의 프로젝트 지칭. 문서 본문의 도메인 서술(주문, 체결, 마켓)은 손대지 않는다.
6. `CLAUDE.md`, `AGENTS.md`, `AI_인수인계서.md`, `실행_가이드.md` 첫 줄의 프로젝트 지칭.
7. `docs/15분_라이브데모_대본.md` 도입부. 현재 "캡스톤 발표" 표현이 남아 있는데 이 자산은 캡스톤이 아니라 포트폴리오다. 이름 변경과 함께 정정한다.
8. 이력서·포트폴리오 문서의 프로젝트명과 저장소 링크.

### 5.4 조건부 (지금은 하지 않음)

- `server/settings.gradle`의 `rootProject.name`. 산출물 jar 파일명과 CI 캐시 키에 노출된다. 바꾸려면 harness 이미지 빌드 경로와 CI 워크플로를 같은 커밋에서 고쳐야 한다. 빌드 산출물을 외부에 배포하기 전까지는 후순위.
- 컴포즈 프로젝트명 `exchange-chaos`. 변경하면 도커 볼륨·네트워크 이름이 바뀌어 실행 중인 실험 상태가 초기화된다. 진행 중인 카오스 실험이 없는 시점에만 착수한다. 실익은 낮다.

### 5.5 바꾸지 않을 것

- 자바 패키지 `kr.exchange.*`와 도메인 클래스명.
- DB 스키마·테이블·컬럼명, DB명·계정 `exchange`.
- Prometheus 메트릭 `exchange_*` 전체, 룰 파일명, Grafana 대시보드 참조.
- Spring 설정 prefix `exchange:`, `spring.application.name`, 풀명 `exchange-lease`.
- `web/package.json`의 `"name": "exchange-web"`. 정적 export 산출물에 노출되지 않는다.
- 웹 라우트 `/exchange/[market]`. 사용자에게 보이는 경로지만 그 페이지가 실제로 거래 화면이므로 정확하다.

### 5.6 실행 순서

이름 확정 후 공개 표면만 묶어 1커밋으로 처리한다. 커밋 제목 예: `docs: 저장소 이름을 <이름>으로 확정`. 본문에는 선정 근거 대신 이 문서 링크만 남긴다.

내부 식별자 리네임은 별도 이슈로 등록하되 착수하지 않는다. 이슈 본문에 5.2의 비용 분석을 그대로 옮겨, 나중에 같은 판단을 다시 하지 않게 한다.