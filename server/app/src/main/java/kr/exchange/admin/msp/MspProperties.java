package kr.exchange.admin.msp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 관제센터 설정(exchange.msp.*, 설계 3절). 원천 주소가 없으면 그 칸은 '원천 연결 전'으로 낸다.
 * 값이 틀리면 조용히 고치지 않고 기동을 멈춘다(validate).
 */
@ConfigurationProperties(prefix = "exchange.msp")
public class MspProperties {

    public enum Environment { LOCAL_DOCKER, SCHOOL_AWS, PERSONAL_AWS, UNKNOWN }

    public enum ReadCapacityMode { CAPACITY_PLAN, ASG_POLICY, NONE }

    public enum HostRole { APP, DB, BOT_OBS }

    // job 선택자는 PromQL 문자열 안에 그대로 들어간다. 따옴표·역슬래시가 끼면 질의가 바뀌므로 이 모양만 받는다.
    private static final Pattern JOB_SELECTOR = Pattern.compile("^[a-z0-9_|-]{1,64}$");

    // API 바인딩 표의 보류 분 상한(1~480)
    private static final Duration SILENCE_CEILING = Duration.ofHours(8);

    private boolean enabled = false;
    private Environment environment = Environment.UNKNOWN;
    private String prometheusUrl;
    private String alertmanagerUrl;
    private final Prometheus prometheus = new Prometheus();
    private Duration queryTimeout = Duration.ofSeconds(3);
    private Duration cacheTtl = Duration.ofSeconds(10);
    private Duration staleAfter = Duration.ofSeconds(60);
    private final List<Host> hosts = new ArrayList<>();
    private final ReadCapacity readCapacity = new ReadCapacity();
    private final Slo slo = new Slo();
    private ZoneId dayZone = ZoneId.of("Asia/Seoul");
    private final Incident incident = new Incident();
    private final Silence silence = new Silence();
    private final Llm llm = new Llm();
    private final Ai ai = new Ai();
    private final Reporting reporting = new Reporting();

    /** 기동 때 한 번 부른다. 설정 오류를 실행 중의 500으로 미루지 않는다. */
    public void validate() {
        llm.validate();
        ai.validate();
        reporting.validate();
        // instant 질의 묶음과 노드 관측의 공유 수명. 근거 신선도(60초) 판정이 캐시 때문에 흔들리지 않게 짧게 묶는다(설계 4-6).
        if (cacheTtl.compareTo(Duration.ofSeconds(1)) < 0 || cacheTtl.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalStateException("exchange.msp.cache-ttl 은 1~10초여야 한다");
        }
        sourceUri("prometheus-url", prometheusUrl);
        sourceUri("alertmanager-url", alertmanagerUrl);
        for (String job : List.of(prometheus.hostJobs, prometheus.appJob, prometheus.dbJob, prometheus.probeJob,
                prometheus.patroniJob)) {
            if (!job.isEmpty() && !JOB_SELECTOR.matcher(job).matches()) {
                throw new IllegalStateException("exchange.msp.prometheus 의 job 선택자가 허용 모양이 아니다: " + job);
            }
        }
        if (silence.humanMax.compareTo(Duration.ofMinutes(1)) < 0 || silence.humanMax.compareTo(SILENCE_CEILING) > 0) {
            throw new IllegalStateException("exchange.msp.silence.human-max 는 1분~8시간이어야 한다: " + silence.humanMax);
        }
        if (!incident.pollInterval.isPositive() || incident.resolveAfter.compareTo(incident.pollInterval) <= 0) {
            throw new IllegalStateException("exchange.msp.incident.resolve-after 는 poll-interval 보다 길어야 한다");
        }
        for (Host host : hosts) {
            if (host.node == null || host.node.isBlank() || host.role == null) {
                throw new IllegalStateException("exchange.msp.hosts 항목에는 node 와 role 이 필요하다");
            }
        }
    }

    /**
     * 원천 주소. 비어 있으면 null(연결 전)이다. http·https 만 받고 사용자 정보·쿼리·조각이 있으면
     * 거부한다. 주소 문자열이 예외 문구로 새지 않게 설정 키 이름만 싣는다.
     */
    static URI sourceUri(String key, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("exchange.msp." + key + " 형식 오류");
        }
        String scheme = uri.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("exchange.msp." + key
                    + " 는 사용자 정보·쿼리·조각이 없는 http·https 주소여야 한다");
        }
        String text = uri.toString();
        return URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
    }

    public URI prometheusUri() {
        return sourceUri("prometheus-url", prometheusUrl);
    }

    public URI alertmanagerUri() {
        return sourceUri("alertmanager-url", alertmanagerUrl);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public String getPrometheusUrl() {
        return prometheusUrl;
    }

    public void setPrometheusUrl(String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    public String getAlertmanagerUrl() {
        return alertmanagerUrl;
    }

    public void setAlertmanagerUrl(String alertmanagerUrl) {
        this.alertmanagerUrl = alertmanagerUrl;
    }

    public Prometheus getPrometheus() {
        return prometheus;
    }

    public Duration getQueryTimeout() {
        return queryTimeout;
    }

    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    public List<Host> getHosts() {
        return hosts;
    }

    public ReadCapacity getReadCapacity() {
        return readCapacity;
    }

    public Slo getSlo() {
        return slo;
    }

    public ZoneId getDayZone() {
        return dayZone;
    }

    public void setDayZone(ZoneId dayZone) {
        this.dayZone = dayZone;
    }

    public Incident getIncident() {
        return incident;
    }

    public Silence getSilence() {
        return silence;
    }

    public Llm getLlm() {
        return llm;
    }

    public Ai getAi() {
        return ai;
    }

    public Reporting getReporting() {
        return reporting;
    }

    /**
     * 보고 서비스(deploy/reporting). Teams 통지를 넘기고 보고 목록과 전송 상태를 읽는다(설계 7-12). 주소가 없으면 그 칸은
     * 연결 전이다. 토큰은 파일 경로로만 받고, 주소는 루프백·사설만 받는다(ReportingClient 가 기동 때 검사한다).
     */
    public static class Reporting {

        // 보고 서비스의 고객·환경 범위 모양(deploy/reporting/console_api.py SCOPE)
        private static final Pattern SCOPE = Pattern.compile("^[a-zA-Z0-9_.-]{1,80}$");

        private String url;
        private String tokenFile;
        private String customer = "exchange";
        private String environment;
        private String artifactBaseUrl;

        void validate() {
            if (!configured()) {
                return;
            }
            sourceUri("reporting.url", url);
            if (tokenFile == null || tokenFile.isBlank()) {
                throw new IllegalStateException("exchange.msp.reporting.token-file 이 필요하다");
            }
            if (customer == null || !SCOPE.matcher(customer).matches()
                    || environment == null || !SCOPE.matcher(environment).matches()) {
                throw new IllegalStateException("exchange.msp.reporting.customer 와 environment 는 보고 서비스 범위 모양이어야 한다");
            }
            URI base = sourceUri("reporting.artifact-base-url", artifactBaseUrl);
            if (base != null && !"https".equals(base.getScheme())) {
                throw new IllegalStateException("exchange.msp.reporting.artifact-base-url 은 https 여야 한다");
            }
        }

        public boolean configured() {
            return url != null && !url.isBlank();
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getTokenFile() {
            return tokenFile;
        }

        public void setTokenFile(String tokenFile) {
            this.tokenFile = tokenFile;
        }

        public String getCustomer() {
            return customer;
        }

        public void setCustomer(String customer) {
            this.customer = customer;
        }

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = environment;
        }

        public String getArtifactBaseUrl() {
            return artifactBaseUrl;
        }

        public void setArtifactBaseUrl(String artifactBaseUrl) {
            this.artifactBaseUrl = artifactBaseUrl;
        }
    }

    /**
     * 판단 모델(설계 3절·7-3). base-url 이 없으면 AI 판단 주기가 돌지 않는다. 주소 규칙과 원격 허용은 LlmClient 가
     * 기동 때 검사한다. 비밀 값은 파일 경로로만 받는다.
     */
    public static class Llm {

        public enum Provider { OPENAI_COMPATIBLE, OLLAMA_NATIVE }

        // 근거 신선도 60초 안에서 수집·캐시 지연(최대 약 15초)을 뺀 값. 이보다 긴 호출은 판단이 늘 오래된 근거로 끝난다.
        static final Duration MAX_TIMEOUT = Duration.ofSeconds(45);

        private Provider provider = Provider.OPENAI_COMPATIBLE;
        private String baseUrl;
        private String model;
        private String apiKeyFile;
        private Duration timeout = Duration.ofSeconds(30);
        private Duration loadTimeout = Duration.ofSeconds(120);
        private String think;
        private String caFile;
        private boolean allowInsecureHttp = false;
        private boolean allowRemote = false;

        void validate() {
            if (!configured()) {
                return;
            }
            if (model == null || model.isBlank() || model.length() > 128 || !model.strip().matches("^[\\w.:/-]+$")) {
                throw new IllegalStateException("exchange.msp.llm.model 은 1~128자의 모델 이름이어야 한다");
            }
            if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
                throw new IllegalStateException("exchange.msp.llm.timeout 은 1~45초여야 한다(근거 신선도 60초 안)");
            }
            if (loadTimeout.compareTo(Duration.ofSeconds(10)) < 0 || loadTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalStateException("exchange.msp.llm.load-timeout 은 10초~10분이어야 한다");
            }
            if (think != null && !think.isBlank() && !think.strip().matches("^(low|medium|high|true|false)$")) {
                throw new IllegalStateException("exchange.msp.llm.think 는 low, medium, high, true, false 가운데 하나여야 한다");
            }
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }

        public Provider getProvider() {
            return provider;
        }

        public void setProvider(Provider provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model == null ? null : model.strip();
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKeyFile() {
            return apiKeyFile;
        }

        public void setApiKeyFile(String apiKeyFile) {
            this.apiKeyFile = apiKeyFile;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getLoadTimeout() {
            return loadTimeout;
        }

        public void setLoadTimeout(Duration loadTimeout) {
            this.loadTimeout = loadTimeout;
        }

        public String getThink() {
            return think == null || think.isBlank() ? null : think.strip();
        }

        public void setThink(String think) {
            this.think = think;
        }

        public String getCaFile() {
            return caFile;
        }

        public void setCaFile(String caFile) {
            this.caFile = caFile;
        }

        public boolean isAllowInsecureHttp() {
            return allowInsecureHttp;
        }

        public void setAllowInsecureHttp(boolean allowInsecureHttp) {
            this.allowInsecureHttp = allowInsecureHttp;
        }

        public boolean isAllowRemote() {
            return allowRemote;
        }

        public void setAllowRemote(boolean allowRemote) {
            this.allowRemote = allowRemote;
        }
    }

    /**
     * AI 판단 주기의 제안값(설계 12절). ADR 0035 가 정한 값(자동 8시간, 근거 60초, 연속 2회, 관찰 5분, 쿨다운 10·30분,
     * 보류 30분)은 설정이 아니라 코드 상수라 설정으로 느슨하게 할 수 없다.
     */
    public static class Ai {

        private Duration interval = Duration.ofSeconds(60);
        private Duration recheckInterval = Duration.ofMinutes(10);
        private Duration proposalTtl = Duration.ofMinutes(15);
        private Duration scaleInSustain = Duration.ofMinutes(10);
        private Duration calmInterval = Duration.ofMinutes(5);

        void validate() {
            if (interval.compareTo(Duration.ofSeconds(15)) < 0 || interval.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalStateException("exchange.msp.ai.interval 은 15초~10분이어야 한다");
            }
            if (recheckInterval.compareTo(interval) < 0) {
                throw new IllegalStateException("exchange.msp.ai.recheck-interval 은 interval 보다 짧을 수 없다");
            }
            // 연속 두 번의 판단이 요청 하나의 수명 안에 들어와야 자동 실행이 성립한다(정책 A4).
            if (proposalTtl.compareTo(interval.multipliedBy(2)) <= 0 || proposalTtl.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalStateException("exchange.msp.ai.proposal-ttl 은 interval 의 두 배보다 길고 1시간 이하여야 한다");
            }
            if (scaleInSustain.compareTo(Duration.ofMinutes(1)) < 0 || scaleInSustain.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalStateException("exchange.msp.ai.scale-in-sustain 은 1분~1시간이어야 한다");
            }
            if (calmInterval.compareTo(Duration.ofSeconds(15)) < 0 || calmInterval.compareTo(Duration.ofMinutes(30)) > 0) {
                throw new IllegalStateException("exchange.msp.ai.calm-interval 은 15초~30분이어야 한다");
            }
        }

        /** 평온할 때의 판단 주기. 판단 주기보다 짧으면 판단 주기를 쓴다. */
        public Duration getCalmInterval() {
            return calmInterval.compareTo(interval) < 0 ? interval : calmInterval;
        }

        public void setCalmInterval(Duration calmInterval) {
            this.calmInterval = calmInterval;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getRecheckInterval() {
            return recheckInterval;
        }

        public void setRecheckInterval(Duration recheckInterval) {
            this.recheckInterval = recheckInterval;
        }

        public Duration getProposalTtl() {
            return proposalTtl;
        }

        public void setProposalTtl(Duration proposalTtl) {
            this.proposalTtl = proposalTtl;
        }

        public Duration getScaleInSustain() {
            return scaleInSustain;
        }

        public void setScaleInSustain(Duration scaleInSustain) {
            this.scaleInSustain = scaleInSustain;
        }
    }

    /** 명명 질의에 끼우는 job 선택자. 비어 있으면 그 원천의 칸은 연결 전이다. */
    public static class Prometheus {

        private String hostJobs = "linux|linux-host";
        private String appJob = "exchange-api";
        private String dbJob = "postgres";
        private String probeJob = "blackbox-http";
        // Patroni REST /metrics 의 수집 job. 이 job 의 지표가 있으면 DB 승격 방식을 자동(Patroni)으로 적는다. 없는 배치(학교 AWS,
        // ADR 0024)는 질의 결과가 비어 승격 방식이 미확인이다. 고객 자원 집계(수집 대상 수)에는 넣지 않는다
        private String patroniJob = "patroni";

        public String getHostJobs() {
            return hostJobs;
        }

        public void setHostJobs(String hostJobs) {
            this.hostJobs = hostJobs == null ? "" : hostJobs.trim();
        }

        public String getAppJob() {
            return appJob;
        }

        public void setAppJob(String appJob) {
            this.appJob = appJob == null ? "" : appJob.trim();
        }

        public String getDbJob() {
            return dbJob;
        }

        public void setDbJob(String dbJob) {
            this.dbJob = dbJob == null ? "" : dbJob.trim();
        }

        public String getProbeJob() {
            return probeJob;
        }

        public void setProbeJob(String probeJob) {
            this.probeJob = probeJob == null ? "" : probeJob.trim();
        }

        public String getPatroniJob() {
            return patroniJob;
        }

        public void setPatroniJob(String patroniJob) {
            this.patroniJob = patroniJob == null ? "" : patroniJob.trim();
        }
    }

    /** 호스트 카드 한 장. 배치 역할은 설정이고, DB 의 실제 Primary·Standby 는 recovery 지표로 정한다. */
    public static class Host {

        private String node;
        private HostRole role;
        private String instanceType;
        private String az;

        public String getNode() {
            return node;
        }

        public void setNode(String node) {
            this.node = node;
        }

        public HostRole getRole() {
            return role;
        }

        public void setRole(HostRole role) {
            this.role = role;
        }

        public String getInstanceType() {
            return instanceType;
        }

        public void setInstanceType(String instanceType) {
            this.instanceType = instanceType;
        }

        public String getAz() {
            return az;
        }

        public void setAz(String az) {
            this.az = az;
        }
    }

    /** 조회 계층의 대수를 누가 정하는가. AWS 는 ASG 가 대상당 요청 수로 정하므로 CPU 권고 임계를 쓰지 않는다. */
    public static class ReadCapacity {

        private ReadCapacityMode mode = ReadCapacityMode.NONE;

        public ReadCapacityMode getMode() {
            return mode;
        }

        public void setMode(ReadCapacityMode mode) {
            this.mode = mode;
        }
    }

    /** 요약 칸의 목표. 없으면 목표 비교를 하지 않는다. */
    public static class Slo {

        private BigDecimal availabilityRatio;
        private BigDecimal p95Seconds;
        private BigDecimal errorRatio;

        public BigDecimal getAvailabilityRatio() {
            return availabilityRatio;
        }

        public void setAvailabilityRatio(BigDecimal availabilityRatio) {
            this.availabilityRatio = availabilityRatio;
        }

        public BigDecimal getP95Seconds() {
            return p95Seconds;
        }

        public void setP95Seconds(BigDecimal p95Seconds) {
            this.p95Seconds = p95Seconds;
        }

        public BigDecimal getErrorRatio() {
            return errorRatio;
        }

        public void setErrorRatio(BigDecimal errorRatio) {
            this.errorRatio = errorRatio;
        }
    }

    public static class Incident {

        private Duration pollInterval = Duration.ofSeconds(15);
        // Prometheus 재전송 1분 두 번에 평가 주기를 더한 것보다 길다(설계 6-1).
        private Duration resolveAfter = Duration.ofSeconds(150);
        private List<String> heartbeatAlertnames = new ArrayList<>(List.of("Watchdog"));

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getResolveAfter() {
            return resolveAfter;
        }

        public void setResolveAfter(Duration resolveAfter) {
            this.resolveAfter = resolveAfter;
        }

        public List<String> getHeartbeatAlertnames() {
            return heartbeatAlertnames;
        }

        public void setHeartbeatAlertnames(List<String> heartbeatAlertnames) {
            this.heartbeatAlertnames = heartbeatAlertnames;
        }
    }

    public static class Silence {

        private Duration humanMax = SILENCE_CEILING;

        public Duration getHumanMax() {
            return humanMax;
        }

        public void setHumanMax(Duration humanMax) {
            this.humanMax = humanMax;
        }
    }
}
