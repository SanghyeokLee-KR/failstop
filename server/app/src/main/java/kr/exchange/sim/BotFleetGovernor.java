package kr.exchange.sim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 봇 방향성·대수·자동균형 상태 보관소(설계 §3·§4). OpsGovernor 옆 인메모리 volatile.
 * 안전 경계.
 * - 대수 스케일은 OPS 버킷을 만지지 않는다. 봇 수 증가는 처리량을 못 올린다(백프레셔 무해).
 * - 목표 대수는 타입·마켓·전역 하드캡으로 클램프한다. 초과는 거부가 아니라 클램프+감사.
 * - effective bias는 [-0.7, +0.7] 하드캡. 자동 균형 중 기준가 대비 ±40% 이탈 시 목표·오차
 *   무관 0으로 강제한다(평균 회귀). auto off는 밴드 판정 없이 수동 target 클램프값을 직접
 *   반영한다(봇_방향성_거버넌스_설계 §4-2·§4-5 정본).
 * - bias는 확률·틱 오프셋 계산에만 쓰는 스칼라다. 가격·수량 NUMERIC 산술에 곱해지지 않는다.
 */
public class BotFleetGovernor {

    public static final double EFFECTIVE_MIN = -0.7;
    public static final double EFFECTIVE_MAX = 0.7;
    public static final double BIAS_MIN = -1.0;
    public static final double BIAS_MAX = 1.0;
    public static final double DEADBAND = 0.1;
    public static final double KP_MAX = 0.2;
    public static final double PRICE_BAND = 0.40;
    public static final long CONTROL_PERIOD_MS = 3_000;
    // 레짐 시드 미지정 생성자용 상수. props.seed 미주입 경로(단위 테스트)의 재현 기준.
    public static final long DEFAULT_REGIME_SEED = 42L;

    private static final int TYPE_COUNT = BotType.values().length;

    /**
     * 변동성 레짐(설계 §5-r5). controlStep당 마켓별 시드 PRNG로 1회 전이한다. 배수는 스칼라라
     * 봇 의사결정(드리프트 크기·스프레드 폭·도착률·고래 확률)만 곱하고 NUMERIC 산술에 유입되지
     * 않는다. CALM 기준, TREND/SHOCK로 갈수록 변동성이 커진다.
     */
    public enum Regime {
        CALM(1.0, 1.0, 1.0, 1.0),
        TREND(1.5, 1.2, 1.3, 1.5),
        SHOCK(2.5, 2.0, 1.8, 3.0);

        private final double drift;
        private final double spread;
        private final double lambda;
        private final double pWhale;

        Regime(double drift, double spread, double lambda, double pWhale) {
            this.drift = drift;
            this.spread = spread;
            this.lambda = lambda;
            this.pWhale = pWhale;
        }
    }

    /**
     * 마켓별 레짐 프로파일(설계 §2-6 r14). STANDARD는 기존 전이 행렬·배수를 그대로 쓴다. MEME는
     * 고변동 밈 자산용으로 TREND/SHOCK 진입·잔류 확률을 올린 전이 행렬과 증폭 배수를 쓴다.
     * 프로파일은 전이 임계값 해석과 배수 증폭만 바꾼다. 마켓별 PRNG 시드와 controlStep당 소비량
     * (nextDouble 1회)은 불변이라 rng 순서·결정론이 보존된다. 배수는 봇 의사결정 스칼라라
     * NUMERIC 산술에 유입되지 않는다.
     */
    public enum RegimeProfile {
        STANDARD(1.0, 1.0, 1.0, 1.0),
        MEME(1.6, 1.4, 1.3, 1.8);

        private final double driftAmp;
        private final double spreadAmp;
        private final double lambdaAmp;
        private final double pWhaleAmp;

        RegimeProfile(double driftAmp, double spreadAmp, double lambdaAmp, double pWhaleAmp) {
            this.driftAmp = driftAmp;
            this.spreadAmp = spreadAmp;
            this.lambdaAmp = lambdaAmp;
            this.pWhaleAmp = pWhaleAmp;
        }

        Regime next(Regime current, double u) {
            return this == MEME ? nextRegimeMeme(current, u) : nextRegime(current, u);
        }

        double drift(Regime r) {
            return r.drift * driftAmp;
        }

        double spread(Regime r) {
            return r.spread * spreadAmp;
        }

        double lambda(Regime r) {
            return r.lambda * lambdaAmp;
        }

        double pWhale(Regime r) {
            return r.pWhale * pWhaleAmp;
        }

        /** 설정 문자열 파싱. 미지정(null·공백)은 STANDARD 폴백(하위호환). 미지 값은 거부. */
        public static RegimeProfile parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return STANDARD;
            }
            return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "STANDARD" -> STANDARD;
                case "MEME" -> MEME;
                default -> throw new IllegalArgumentException(
                        "regimeProfile은 STANDARD|MEME여야 합니다: " + raw);
            };
        }
    }

    private final int typeCap;
    private final int marketCap;
    private final int globalCap;
    private final int poolPerType;
    private final long regimeSeed;

    private final Map<String, MarketState> markets = new ConcurrentHashMap<>();

    public BotFleetGovernor(int typeCap, int marketCap, int globalCap, int poolPerType) {
        this(typeCap, marketCap, globalCap, poolPerType, DEFAULT_REGIME_SEED);
    }

    public BotFleetGovernor(int typeCap, int marketCap, int globalCap, int poolPerType,
                            long regimeSeed) {
        this.typeCap = Math.max(1, typeCap);
        this.marketCap = Math.max(1, marketCap);
        this.globalCap = Math.max(1, globalCap);
        this.poolPerType = Math.max(1, poolPerType);
        this.regimeSeed = regimeSeed;
    }

    /**
     * 데모 기본 캡. globalCap은 하네스 normal(140봇) 기준 여유 상한이다.
     * poolPerType은 typeCap과 같게 잡아 캡 상향이 재기동을 요구하지 않게 한다(§3-3).
     */
    public static BotFleetGovernor defaults() {
        return defaults(DEFAULT_REGIME_SEED);
    }

    public static BotFleetGovernor defaults(long regimeSeed) {
        return new BotFleetGovernor(30, 80, 300, 30, regimeSeed);
    }

    /**
     * 마켓명 안정 해시(FNV-1a 32bit). String.hashCode도 계약상 안정이나, 레짐 시퀀스 재현이
     * 봇 결정성의 일부라 JVM·구현 무관하게 고정되는 해시를 명시적으로 쓴다.
     */
    static long stableSalt(String market) {
        long h = 0x811c9dc5L;
        for (int i = 0; i < market.length(); i++) {
            h ^= market.charAt(i);
            h = (h * 0x01000193L) & 0xffffffffL;
        }
        return h;
    }

    /** 표준 레짐 전이 1스텝(순수). u는 [0,1) 균등. 설계 §5-r5 전이 행렬. */
    static Regime nextRegime(Regime current, double u) {
        return switch (current) {
            case CALM -> u < 0.90 ? Regime.CALM : u < 0.98 ? Regime.TREND : Regime.SHOCK;
            case TREND -> u < 0.30 ? Regime.CALM : u < 0.90 ? Regime.TREND : Regime.SHOCK;
            case SHOCK -> u < 0.50 ? Regime.CALM : u < 0.70 ? Regime.TREND : Regime.SHOCK;
        };
    }

    /**
     * MEME 레짐 전이 1스텝(순수, 설계 §2-6 r14). 표준 대비 TREND/SHOCK 진입·잔류 확률을 올려
     * 고변동을 만든다. u는 [0,1) 균등. 표준과 동일하게 nextDouble 1회만 소비한다.
     */
    static Regime nextRegimeMeme(Regime current, double u) {
        return switch (current) {
            case CALM -> u < 0.60 ? Regime.CALM : u < 0.85 ? Regime.TREND : Regime.SHOCK;
            case TREND -> u < 0.10 ? Regime.CALM : u < 0.70 ? Regime.TREND : Regime.SHOCK;
            case SHOCK -> u < 0.25 ? Regime.CALM : u < 0.55 ? Regime.TREND : Regime.SHOCK;
        };
    }

    public Regime regime(String market) {
        MarketState state = markets.get(market);
        return state == null ? Regime.CALM : state.regime;
    }

    public RegimeProfile regimeProfile(String market) {
        MarketState state = markets.get(market);
        return state == null ? RegimeProfile.STANDARD : state.profile;
    }

    public double regimeDriftMult(String market) {
        MarketState state = markets.get(market);
        return state == null ? 1.0 : state.profile.drift(state.regime);
    }

    public double regimeSpreadMult(String market) {
        MarketState state = markets.get(market);
        return state == null ? 1.0 : state.profile.spread(state.regime);
    }

    public double regimeLambdaMult(String market) {
        MarketState state = markets.get(market);
        return state == null ? 1.0 : state.profile.lambda(state.regime);
    }

    /**
     * 국면 프리셋 도착률 배수(§6-A v2.31). 레짐 배수와 곱해 쓴다. 미등록·미적용은 1.0이라
     * 프리셋을 쓰지 않는 배포의 봇 거동이 바뀌지 않는다.
     */
    public double scenarioLambdaMult(String market) {
        MarketState state = markets.get(market);
        return state == null ? 1.0 : state.scenarioLambda;
    }

    /**
     * 테이커 도착 간격에 곱할 최종 배수. 레짐과 국면 두 스칼라의 곱이며 합성 규칙을 봇이
     * 아니라 여기에 둔다. 한 MarketState 참조에서 두 필드를 읽어 마켓이 뒤섞인 조합은 막지만
     * 두 읽기가 원자적이지는 않다. 부하 셰이핑 스칼라라 문제되지 않는다.
     * 버스트 확률은 이 값을 쓰지 않는다(레짐 단독).
     */
    public double takerArrivalMult(String market) {
        MarketState state = markets.get(market);
        return state == null ? 1.0 : state.profile.lambda(state.regime) * state.scenarioLambda;
    }

    public double regimePWhaleMult(String market) {
        MarketState state = markets.get(market);
        return state == null ? 1.0 : state.profile.pWhale(state.regime);
    }

    public int poolPerType() {
        return poolPerType;
    }

    public int typeCap() {
        return typeCap;
    }

    /** 플릿 기동 시 마켓 등록. referencePrice는 가격 밴드 기준가(±40%)로 고정된다. */
    public void registerMarket(String market, BigDecimal referencePrice,
                               Map<BotType, Integer> initialTargets) {
        registerMarket(market, referencePrice, initialTargets, RegimeProfile.STANDARD);
    }

    /**
     * 레짐 프로파일 지정 등록(설계 §2-6 r14). 프로파일은 마켓 상태 최초 생성 시점에만 고정되므로
     * 등록은 setScale·setBias보다 먼저 호출한다(BotFleet.start이 이 순서를 지킨다).
     */
    public void registerMarket(String market, BigDecimal referencePrice,
                               Map<BotType, Integer> initialTargets, RegimeProfile profile) {
        MarketState state = markets.computeIfAbsent(market,
                m -> new MarketState(m, referencePrice, profile));
        if (referencePrice != null && state.referencePrice == null) {
            state.referencePrice = referencePrice;
        }
        if (initialTargets != null) {
            for (BotType type : BotType.values()) {
                Integer t = initialTargets.get(type);
                if (t != null) {
                    state.targets.set(type.ordinal(), clampType(t));
                }
            }
            // 기준선은 최초 등록분만 잡는다. 활성화 시점 현재값을 기준으로 삼으면 같은
            // 프리셋 재활성화가 누적돼 배수가 거듭제곱으로 커진다(§6-A v2.31 멱등).
            if (!state.baselineSet) {
                for (int i = 0; i < TYPE_COUNT; i++) {
                    state.baseline.set(i, state.targets.get(i));
                }
                state.baselineSet = true;
            }
        }
    }

    /** 프리셋 배수 기준선. 미등록이면 null이고, 호출측은 대수를 건드리지 않는다. */
    public Map<BotType, Integer> baseline(String market) {
        MarketState state = markets.get(market);
        if (state == null || !state.baselineSet) {
            return null;
        }
        EnumMap<BotType, Integer> out = new EnumMap<>(BotType.class);
        for (BotType type : BotType.values()) {
            out.put(type, state.baseline.get(type.ordinal()));
        }
        return out;
    }

    /** 등록된 마켓명 정렬 목록. 프리셋 적용 순서를 고정해 클램프 결과를 재현 가능하게 한다. */
    public List<String> marketNames() {
        List<String> keys = new ArrayList<>(markets.keySet());
        keys.sort(String::compareTo);
        return keys;
    }

    private EnumMap<BotType, Integer> currentTargets(String market) {
        MarketState state = state(market);
        EnumMap<BotType, Integer> out = new EnumMap<>(BotType.class);
        for (BotType type : BotType.values()) {
            out.put(type, state.targets.get(type.ordinal()));
        }
        return out;
    }

    public record ScenarioMarketResult(String market, boolean scaleApplied,
                                       Map<BotType, Integer> applied, double effectiveBias,
                                       boolean clamped) {
    }

    /**
     * 프리셋을 전 등록 마켓에 적용한다(§6-A v2.31). 대수는 기준선 배수를 setScale로 흘려
     * 기존 캡 클램프를 그대로 태우고, bias는 프리셋 고정값이다. 기준선 미등록 마켓은 대수를
     * 건드리지 않고 bias만 적용한다. 기준선 0을 곱해 봇을 조용히 0으로 만드는 경로를 막는다.
     * setScale과 같은 모니터를 잡아 배치 중간에 다른 대수 변경이 끼어들지 않게 한다.
     * 전역 캡 잔여는 마켓 순서대로 소진되므로 상향 국면에서 뒤 마켓이 먼저 클램프된다.
     */
    public synchronized List<ScenarioMarketResult> applyScenario(SimScenario scenario) {
        List<ScenarioMarketResult> out = new ArrayList<>();
        for (String market : marketNames()) {
            Map<BotType, Integer> base = baseline(market);
            Map<BotType, Integer> applied;
            boolean clamped = false;
            if (base == null) {
                applied = currentTargets(market);
            } else {
                EnumMap<BotType, Integer> targets = new EnumMap<>(BotType.class);
                for (BotType type : BotType.values()) {
                    targets.put(type, scenario.scaled(type, base.get(type)));
                }
                ScaleResult result = setScale(market, targets);
                applied = result.applied();
                clamped = result.clamped();
            }
            BiasResult bias = setBias(market, scenario.targetBias(), scenario.autoBalance(),
                    scenario.strength());
            // 도착률은 대수 기준선과 무관하다. 기준선 없는 마켓도 국면을 따른다
            state(market).scenarioLambda = scenario.lambdaMult();
            out.add(new ScenarioMarketResult(market, base != null, applied,
                    bias.effectiveBias(), clamped));
        }
        return out;
    }

    private MarketState state(String market) {
        return markets.computeIfAbsent(market,
                m -> new MarketState(m, null, RegimeProfile.STANDARD));
    }

    // ----------------------------------------------------------------
    // 대수 스케일(설계 §3, §5-1)
    // ----------------------------------------------------------------

    public record ScaleResult(Map<BotType, Integer> applied, boolean clamped) {
    }

    /**
     * 타입별 목표 대수 갱신. 각 타입 캡·마켓 캡·전역 캡으로 클램프한다.
     * 클램프가 한 번이라도 걸리면 clamped=true. OPS 버킷은 만지지 않는다.
     */
    public synchronized ScaleResult setScale(String market, Map<BotType, Integer> targets) {
        MarketState state = state(market);
        boolean clamped = false;
        EnumMap<BotType, Integer> requested = new EnumMap<>(BotType.class);
        for (BotType type : BotType.values()) {
            int cur = state.targets.get(type.ordinal());
            Integer req = targets == null ? null : targets.get(type);
            int value = req == null ? cur : Math.max(0, req);
            int typeClamped = Math.min(value, typeCap);
            if (typeClamped != value) {
                clamped = true;
            }
            requested.put(type, typeClamped);
        }
        clamped |= clampAgainst(requested, marketCap);
        clamped |= clampAgainst(requested, remainingGlobal(market));
        EnumMap<BotType, Integer> applied = new EnumMap<>(BotType.class);
        for (BotType type : BotType.values()) {
            int value = requested.get(type);
            state.targets.set(type.ordinal(), value);
            applied.put(type, value);
        }
        return new ScaleResult(applied, clamped);
    }

    public record CreateResult(int existing, int appliedTarget, int fleetTotal, boolean clamped) {
    }

    /**
     * 봇 생성 경로(§6-A)의 유형별 목표 대수 원자 증분. read-modify-write를 setScale과 같은
     * 락 안에서 수행해 동시 생성의 lost-update를 막는다. fleetTotal은 전 마켓 목표 합계다.
     */
    public synchronized CreateResult increaseTarget(String market, BotType type, int delta) {
        MarketState state = state(market);
        int existing = state.targets.get(type.ordinal());
        EnumMap<BotType, Integer> targets = new EnumMap<>(BotType.class);
        targets.put(type, existing + Math.max(0, delta));
        ScaleResult result = setScale(market, targets);
        int appliedTarget = result.applied().getOrDefault(type, existing);
        int fleetTotal = 0;
        for (MarketState s : markets.values()) {
            fleetTotal += s.totalTarget();
        }
        return new CreateResult(existing, appliedTarget, fleetTotal, result.clamped());
    }

    /** 요청 합이 상한을 넘으면 유형 순서(ordinal) 역순으로 순차 감축한다. 하나라도 줄면 true. */
    private static boolean clampAgainst(EnumMap<BotType, Integer> requested, int cap) {
        int sum = requested.values().stream().mapToInt(Integer::intValue).sum();
        if (sum <= cap) {
            return false;
        }
        // 고정 순서로 깎아야 재생 결정론이 유지된다.
        int excess = sum - cap;
        BotType[] order = BotType.values();
        for (int i = order.length - 1; i >= 0 && excess > 0; i--) {
            BotType type = order[i];
            int cur = requested.get(type);
            int cut = Math.min(cur, excess);
            requested.put(type, cur - cut);
            excess -= cut;
        }
        return true;
    }

    /** 전역 캡에서 이 마켓을 뺀 나머지 마켓이 이미 점유한 목표를 제외한 잔여 예산. */
    private int remainingGlobal(String market) {
        int others = 0;
        for (MarketState s : markets.values()) {
            if (!s.market.equals(market)) {
                others += s.totalTarget();
            }
        }
        return Math.max(0, globalCap - others);
    }

    // ----------------------------------------------------------------
    // 방향·자동균형(설계 §4, §5-2)
    // ----------------------------------------------------------------

    public record BiasResult(double targetBias, boolean autoBalance, double strength,
                             double effectiveBias) {
    }

    /**
     * 목표 net bias·자동균형 갱신. auto off면 effective = clamp(target) 즉시 반영(§4-5).
     * auto on이면 균형 루프(controlStep)가 다음 주기부터 effective를 수렴시킨다.
     */
    public BiasResult setBias(String market, double targetBias, boolean autoBalance,
                              double strength) {
        MarketState state = state(market);
        state.targetBias = clampBias(targetBias);
        state.autoBalance = autoBalance;
        state.strength = clampStrength(strength);
        if (!autoBalance) {
            state.effectiveBias = clampEffective(state.targetBias);
        }
        return new BiasResult(state.targetBias, state.autoBalance, state.strength,
                state.effectiveBias);
    }

    /**
     * 균형 루프 1회. 마켓별로 측정 net bias를 집계하고, auto on이면 비례 제어로 effective를
     * 갱신한다. auto off면 effective는 clamp(target) 유지. 측정 창은 매 주기 리셋한다.
     */
    public void controlStep() {
        for (MarketState state : markets.values()) {
            // 레짐 전이는 마켓별 시드 PRNG를 controlStep당 정확히 1회 전진시킨다. 같은 시드 +
            // 같은 호출 횟수 = 같은 레짐 시퀀스. 프로파일은 임계값 해석만 바꾸고 소비량은 불변.
            state.regime = state.profile.next(state.regime, state.regimeRng.nextDouble());
            long buy = state.buyAgg.getAndSet(0);
            long sell = state.sellAgg.getAndSet(0);
            long fills = state.fillAgg.getAndSet(0);
            long aggressors = buy + sell;
            state.measuredBias = (double) (buy - sell) / Math.max(aggressors, 1L);
            // 접수 성공/개시 시도 비율. 창당 시도 <= 접수라 [0, 1]. 생동감 프록시(§4-1).
            state.fillRatio = aggressors == 0 ? 0.0
                    : Math.min(1.0, (double) fills / aggressors);
            if (!state.autoBalance) {
                state.effectiveBias = clampEffective(state.targetBias);
                continue;
            }
            boolean band = priceBandBreached(state.currentPrice, state.referencePrice, PRICE_BAND);
            double kp = KP_MAX * state.strength;
            state.effectiveBias = nextEffectiveBias(state.effectiveBias, state.targetBias,
                    state.measuredBias, kp, DEADBAND, band);
        }
    }

    // ----------------------------------------------------------------
    // 봇 런타임 조회·집계
    // ----------------------------------------------------------------

    /** 파킹 게이트. 봇 인덱스(1-based)가 현재 목표 이하일 때만 활성이다. */
    public boolean active(String market, BotType type, int index1based) {
        MarketState state = markets.get(market);
        if (state == null) {
            return false;
        }
        // 물리 풀 초과 인덱스는 기동된 스레드가 없다. activeCount·snapshot과 동일하게 풀로 클램프해
        // typeCap>poolPerType 구성에서 활성 판정이 실제 대수보다 커지지 않게 한다.
        return index1based <= Math.min(state.targets.get(type.ordinal()), poolPerType);
    }

    public double effectiveBias(String market) {
        MarketState state = markets.get(market);
        return state == null ? 0.0 : state.effectiveBias;
    }

    /**
     * 테이커가 개시 방향을 보고한다. 측정 net bias의 유일한 입력이다(관측 지표 미소비).
     * 실제 주문 제출 직전(호가 존재 확인 후)에만 호출해 미접수 방향이 측정에 새지 않게 한다.
     */
    public void recordAggressor(String market, boolean buy) {
        MarketState state = markets.get(market);
        if (state == null) {
            return;
        }
        if (buy) {
            state.buyAgg.incrementAndGet();
        } else {
            state.sellAgg.incrementAndGet();
        }
    }

    /** 개시 주문이 접수(2xx)되면 보고한다. fill_ratio 프록시의 분자다(recordAggressor가 분모). */
    public void recordFill(String market) {
        MarketState state = markets.get(market);
        if (state != null) {
            state.fillAgg.incrementAndGet();
        }
    }

    /** MM이 fair value 유래 현재가를 보고한다. 가격 밴드 판정 입력이다. */
    public void recordPrice(String market, BigDecimal price) {
        MarketState state = markets.get(market);
        if (state != null) {
            state.currentPrice = price;
        }
    }

    public int activeCount(String market, BotType type) {
        MarketState state = markets.get(market);
        if (state == null) {
            return 0;
        }
        return Math.min(state.targets.get(type.ordinal()), poolPerType);
    }

    // ----------------------------------------------------------------
    // 상태 스냅샷(§5-5)
    // ----------------------------------------------------------------

    public record MarketSnapshot(String market, double targetBias, double measuredBias,
                                 double effectiveBias, boolean autoBalance, double strength,
                                 Map<BotType, int[]> scale, double fillRatio) {
    }

    public List<MarketSnapshot> snapshot() {
        List<MarketSnapshot> out = new ArrayList<>();
        List<String> keys = new ArrayList<>(markets.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            MarketState state = markets.get(key);
            EnumMap<BotType, int[]> scale = new EnumMap<>(BotType.class);
            for (BotType type : BotType.values()) {
                int target = state.targets.get(type.ordinal());
                scale.put(type, new int[]{target, Math.min(target, poolPerType)});
            }
            out.add(new MarketSnapshot(key, state.targetBias, state.measuredBias,
                    state.effectiveBias, state.autoBalance, state.strength, scale,
                    state.fillRatio));
        }
        return out;
    }

    // ----------------------------------------------------------------
    // 순수 함수(단위 테스트 대상). bias 도메인 스칼라 연산이며 자금 경로가 아니다.
    // ----------------------------------------------------------------

    public static double clampEffective(double v) {
        return Math.max(EFFECTIVE_MIN, Math.min(EFFECTIVE_MAX, v));
    }

    public static double clampBias(double v) {
        return Math.max(BIAS_MIN, Math.min(BIAS_MAX, v));
    }

    public static double clampStrength(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 기준가 대비 이탈이 밴드를 넘으면 true. price 비교 전용이며 원장 산술이 아니다. */
    public static boolean priceBandBreached(BigDecimal price, BigDecimal reference, double bandPct) {
        if (price == null || reference == null || reference.signum() <= 0) {
            return false;
        }
        double ratio = price.doubleValue() / reference.doubleValue() - 1.0;
        return Math.abs(ratio) > bandPct;
    }

    /**
     * 비례 제어 1스텝. 밴드 이탈이면 목표·오차 무관 0으로 강제(평균 회귀). 데드밴드 내면
     * 조정하지 않는다(플래핑 억제). 결과는 [-0.7, +0.7] 하드캡.
     */
    public static double nextEffectiveBias(double current, double target, double measured,
                                           double kp, double deadband, boolean bandBreached) {
        if (bandBreached) {
            return 0.0;
        }
        double error = target - measured;
        double next = Math.abs(error) < deadband ? current : current + kp * error;
        return clampEffective(next);
    }

    /** 문자열 target_net_bias 파싱 + 정의역 검증([-1, +1]). */
    public static double parseBias(String raw) {
        double v = new BigDecimal(raw.trim()).doubleValue();
        if (v < BIAS_MIN || v > BIAS_MAX) {
            throw new IllegalArgumentException("target_net_bias는 [-1, +1] 범위여야 합니다.");
        }
        return v;
    }

    /** 문자열 strength 파싱. off/subtle/normal/strong 별칭 또는 [0, 1] 숫자. */
    public static double parseStrength(String raw) {
        String s = raw == null ? "" : raw.trim();
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "off" -> 0.0;
            case "subtle" -> 0.25;
            case "normal", "" -> 0.5;
            case "strong" -> 1.0;
            default -> {
                double v = new BigDecimal(s).doubleValue();
                if (v < 0.0 || v > 1.0) {
                    throw new IllegalArgumentException("strength는 [0, 1] 또는 별칭이어야 합니다.");
                }
                yield v;
            }
        };
    }

    private int clampType(int value) {
        return Math.max(0, Math.min(value, typeCap));
    }

    private final class MarketState {
        private final String market;
        private volatile BigDecimal referencePrice;
        private volatile BigDecimal currentPrice;
        private final AtomicIntegerArray targets = new AtomicIntegerArray(TYPE_COUNT);
        private final AtomicIntegerArray baseline = new AtomicIntegerArray(TYPE_COUNT);
        private volatile boolean baselineSet;
        private volatile double targetBias;
        private volatile double effectiveBias;
        private volatile double measuredBias;
        private volatile double fillRatio;
        private volatile boolean autoBalance;
        private volatile double strength = 0.5;
        private volatile Regime regime = Regime.CALM;
        /* 국면 프리셋의 도착률 배수. 레짐 소유 스칼라를 덮지 않고 곱해진다. 기본 1.0이라
           프리셋 미적용 배포는 기존과 동일하다(§6-A v2.31) */
        private volatile double scenarioLambda = 1.0;
        private final RegimeProfile profile;
        private final SplittableRandom regimeRng;
        private final AtomicLong buyAgg = new AtomicLong();
        private final AtomicLong sellAgg = new AtomicLong();
        private final AtomicLong fillAgg = new AtomicLong();

        private MarketState(String market, BigDecimal referencePrice, RegimeProfile profile) {
            this.market = market;
            this.referencePrice = referencePrice;
            this.profile = profile == null ? RegimeProfile.STANDARD : profile;
            this.regimeRng = new SplittableRandom(regimeSeed ^ stableSalt(market));
        }

        private int totalTarget() {
            int sum = 0;
            for (int i = 0; i < TYPE_COUNT; i++) {
                sum += targets.get(i);
            }
            return sum;
        }
    }
}
