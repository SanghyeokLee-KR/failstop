package kr.exchange.api.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 이 JVM 의 WARN·ERROR 로그를 비슷한 것끼리 묶어 메모리에 둔다(API 설계 §6-F '오류 로그'). 관리 JVM 이 1분마다
 * /actuator/errorlog 로 읽어 노드를 합친다.
 *
 * <p>문장은 묶음에 넣기 전에 먼저 가리고, 그다음에 숫자를 지우고 자른다. 숫자를 먼저 지우거나 먼저 자르면 토큰·이메일·JWT 의
 * 모양이 깨져 가리기를 빠져나가기 때문이다. 메모리에는 가린 문장만 둔다. 로그를 찍는 스레드끼리 막지 않게 동기화 없는 기반
 * 클래스를 쓰고, 가리기는 처음 보는 틀과 같은 묶음의 대표 문장을 1초에 한 번 바꿀 때만 한다. 그래서 로그가 쏟아져도 가리기
 * 비용이 로그 건수에 비례하지 않는다.
 */
public class ErrorLogBook extends UnsynchronizedAppenderBase<ILoggingEvent> {

    public static final int WINDOW_MINUTES = 15;
    static final int MAX_GROUPS = 200;
    static final int MAX_TEMPLATE = 200;
    static final int MAX_MESSAGE = 240;
    static final int MAX_EXCEPTION_MESSAGE = 200;
    // 한글 한 글자는 UTF-8 로 3바이트다. 글자 수와 함께 바이트 수도 묶어 끝점 응답의 크기를 줄인다.
    static final int MAX_TEMPLATE_BYTES = 400;
    static final int MAX_MESSAGE_BYTES = 480;
    static final int MAX_EXCEPTION_MESSAGE_BYTES = 400;
    // 가리기 전에 읽는 길이. 아주 긴 문장에 정규식을 돌리지 않으려는 상한이다. 이 길이에서 자를 때는 끝에 걸친 낱말을 통째로
    // 버린다. 반쯤 잘린 토큰은 가리기 모양과 맞지 않아 그대로 남기 때문이다.
    static final int MAX_SCAN = 2048;
    static final String CUT = "[생략]";
    // 같은 묶음의 대표 문장을 새로 가려 바꾸는 최소 간격(로그 시각 기준).
    static final long MESSAGE_REFRESH_MILLIS = 1000;
    // 자리표시자({})가 든 틀은 코드의 상수라 가린 결과를 기억해 둔다. 인자를 이어 붙인 문장은 비밀이 들 수 있어 기억하지 않는다.
    static final int MAX_TEMPLATE_CACHE = 1024;
    // 끝점 응답의 상한. 수집기(관리 JVM 의 ErrorLogPoller)가 받는 응답 상한 256 KiB 보다 작게 둔다. 묶음은 200개까지인데 한글
    // 문장이 긴 묶음이 모두 차면 이 상한을 넘으므로, ERROR 먼저, 15분 건수가 많고 최근인 순으로 싣다가 넘치면 나머지를 싣지 않고
    // omitted 로 센다.
    public static final int MAX_VIEW_BYTES = 192 * 1024;
    private static final String APP_PACKAGE = "kr.exchange.";

    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    // 가리는 모양. 적용 순서에 뜻이 있다. 주소와 자격 증명을 먼저, 긴 토큰과 숫자열을 나중에 가린다. 상담의 가림과 같은 종류이고
    // 모든 비밀과 개인정보를 알아내지는 못한다. 덩어리를 통째로 읽는 모양은 덩어리의 첫 글자에서만 시작하게 해(뒤보기) 긴
    // 덩어리에서 시작점마다 끝까지 되짚는 일(제곱 시간)이 없게 한다.
    private static final Pattern URL = Pattern.compile("(?i)(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*://\\S+|\\bwww\\.\\S+");
    private static final Pattern EMAIL = Pattern.compile("(?<![\\w.%+-])[\\w.%+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(?:bearer|basic|digest)\\s+[^\\s,;]+");
    // 이름=값, 이름: 값, "이름":"값". 이름은 password·secret·token 같은 낱말이 든 이름 전체(client_secret, db_password)다.
    // 낱말의 첫 글자에서만 시작하게 해(뒤보기) 긴 낱말에서 되짚기가 늘지 않게 한다. 이미 가린 값([...])과 자리표시자({})는 건너뛴다.
    private static final Pattern SECRET = Pattern.compile("(?i)([\"']?)(?<![\\w.-])((?=[\\w.-]*?(?:password|passwd|pwd"
            + "|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|cookie|session|signature|auth))"
            + "[\\w.-]+)\\1(\\s*[:=]\\s*)(?!\\[|\\{\\})(\"[^\"]*\"|'[^']*'|[^\\s,;&)}\\]]+)");
    // 끝이 잘린 JWT 도 헤더만으로 가린다.
    private static final Pattern JWT = Pattern.compile("\\beyJ[\\w-]{8,}(?:\\.[\\w-]*){0,2}");
    // AWS 사설 호스트 이름(ip-172-31-2-86, …compute.internal)
    private static final Pattern HOST = Pattern.compile("(?i)\\bip(?:-\\d{1,3}){4}\\b(?:\\.[\\w-]+)*"
            + "|(?<![\\w.-])(?:[\\w-]+\\.){1,10}(?:compute\\.internal|ec2\\.internal|compute\\.amazonaws\\.com)\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
    // 여덟 조각이거나 :: 로 줄인 IPv6. 시각(03:14:38)과 겹치지 않게 그 둘만 받는다.
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![\\w:.])(?=[0-9a-f:]{2,}[0-9a-f])"
            + "(?:(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}"
            + "|(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{0,4}::(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{0,4})"
            + "(?:%[\\w.]+)?(?![\\w:])");
    // 긴 토큰. base64·base64url·16진 글자로 된 덩어리가 20자 이상이고 숫자와 영문자가 함께 있거나, 32자 이상이면 가린다. / 와 +
    // 가 섞인 base64 도 여기서 가린다. 숫자가 없는 짧은 이름(IllegalStateException)은 남긴다.
    private static final Pattern TOKEN = Pattern.compile("(?<![A-Za-z0-9+/_-])(?:(?=[A-Za-z0-9+/_-]*\\d)"
            + "(?=[A-Za-z0-9+/_-]*[A-Za-z])[A-Za-z0-9+/_-]{20,}|[A-Za-z0-9+/_-]{32,})={0,2}(?![A-Za-z0-9+/_-])");
    // 9자리 이상의 숫자열(카드·전화·계좌 번호). 날짜와 시각(2026-09-13 04:24)은 남긴다.
    private static final Pattern LONG_DIGITS = Pattern.compile(
            "(?<!\\d)(?!\\d{4}-\\d{2}-\\d{2}(?:[ T]\\d{2}:|\\b))\\d(?:[ -]?\\d){8,}");

    /** 끝점 응답의 순서. ERROR 먼저, 15분 건수가 많은 순, 최근 순이다. 응답 상한에 걸리면 뒤에서부터 빠진다. */
    static final Comparator<Group.Snapshot> VIEW_ORDER = Comparator
            .comparing((Group.Snapshot s) -> !"ERROR".equals(s.group().level))
            .thenComparing(Group.Snapshot::count, Comparator.reverseOrder())
            .thenComparing(Group.Snapshot::lastSeen, Comparator.reverseOrder());

    private final Clock clock;
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final Map<String, String> templates = new ConcurrentHashMap<>();
    private final AtomicLong overflow = new AtomicLong();
    // 이 JVM 을 가르는 임의 id. 관리 JVM 이 자기 묶음을 노드 목록으로 한 번 더 읽어 두 번 세지 않게 한다.
    private final String instance;

    public ErrorLogBook(Clock clock) {
        this.clock = clock;
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        this.instance = HexFormat.of().formatHex(random);
    }

    public String instance() {
        return instance;
    }

    /** 루트 로거에 붙인 수집기. 컨텍스트가 닫힐 때 detach 로 뗀다. 스프링 부트의 로깅 초기화 뒤에 불러야 한다. */
    public static ErrorLogBook attach(Clock clock) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ErrorLogBook book = new ErrorLogBook(clock);
        book.setContext(context);
        book.setName("errorlog");
        book.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(book);
        return book;
    }

    public void detach() {
        LoggerContext context = (LoggerContext) getContext();
        if (context != null) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(this);
        }
        stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return;
        }
        IThrowableProxy thrown = event.getThrowableProxy();
        IThrowableProxy root = root(thrown);
        record(event.getLevel().toString(), event.getLoggerName(), event.getMessage(),
                root == null ? null : root.getClassName(), root == null ? null : root.getMessage(), frame(thrown),
                event.getFormattedMessage(), Instant.ofEpochMilli(event.getTimeStamp()));
    }

    /**
     * 로그 한 건을 묶음에 더한다. 묶는 열쇠는 등급, 로거, 가린 뒤 숫자를 지운 메시지 틀, 원인 예외 클래스, 첫 앱 프레임이다.
     * 묶음이 가득 찼는데 자리를 만들 수 없으면(모두 ERROR 인데 새 로그가 WARN) 그 로그는 받지 않고 overflow 로 센다.
     * 시험은 녹화한 로그 줄을 이 경로로 넣는다.
     */
    void record(String level, String logger, String template, String exception, String exceptionMessage,
                String frame, String message, Instant at) {
        String cleaned = template(template);
        String key = level + '|' + logger + '|' + cleaned + '|' + exception + '|' + frame;
        Group group = groups.get(key);
        if (group == null) {
            if (groups.size() >= MAX_GROUPS && !makeRoom(level)) {
                overflow.incrementAndGet();
                return;
            }
            group = groups.computeIfAbsent(key, k -> new Group(id(k), level, logger, cleaned, exception, frame, at));
        }
        group.count(at);
        if (group.claimText(at)) {
            group.text(at.toEpochMilli(), clean(message, MAX_MESSAGE, MAX_MESSAGE_BYTES),
                    clean(exceptionMessage, MAX_EXCEPTION_MESSAGE, MAX_EXCEPTION_MESSAGE_BYTES));
        }
    }

    /**
     * 최근 15분에 건수가 있는 묶음을 VIEW_ORDER 순서로 낸다. 문장은 이미 가린 것이다. 응답 크기(MAX_VIEW_BYTES)를 넘기게 되면
     * 그 뒤의 묶음은 싣지 않고 omitted 로 센다.
     */
    public Map<String, Object> view() {
        Instant now = clock.instant();
        long nowMinute = now.getEpochSecond() / 60;
        List<Group.Snapshot> recent = new ArrayList<>();
        for (Group g : groups.values()) {
            Group.Snapshot s = g.snapshot(nowMinute);
            if (s.count() > 0) {
                recent.add(s);
            }
        }
        recent.sort(VIEW_ORDER);
        List<Map<String, Object>> rows = new ArrayList<>();
        long bytes = 0;
        int omitted = 0;
        for (Group.Snapshot s : recent) {
            Map<String, Object> m = row(s);
            long size = jsonBytes(m) + 1;
            if (omitted > 0 || bytes + size > MAX_VIEW_BYTES) {
                omitted++;
                continue;
            }
            rows.add(m);
            bytes += size;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "errorlog/1");
        out.put("instance", instance);
        out.put("collected_at", ApiFormats.time(now));
        out.put("window_minutes", WINDOW_MINUTES);
        out.put("overflow", overflow.get());
        out.put("omitted", omitted);
        out.put("groups", rows);
        return out;
    }

    private static Map<String, Object> row(Group.Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.group().id);
        m.put("level", s.group().level);
        m.put("logger", s.group().logger);
        m.put("template", s.group().template);
        m.put("exception", s.group().exception);
        m.put("exception_message", s.exceptionMessage());
        m.put("frame", s.group().frame);
        m.put("message", s.message());
        m.put("count", s.count());
        m.put("total", s.total());
        m.put("first_seen", ApiFormats.time(s.group().firstSeen));
        m.put("last_seen", ApiFormats.time(s.lastSeen()));
        return m;
    }

    /**
     * 묶음이 가득 찼을 때 자리를 만든다. 가장 오래 조용한 WARN 묶음을 먼저 버리고, WARN 이 없으면 새 로그가 ERROR 일 때만 가장
     * 오래 조용한 ERROR 묶음을 버린다. ERROR 가 WARN 폭주에 밀려나지 않게 하려는 순서다. 자리를 만들지 못하면 false 다.
     */
    private boolean makeRoom(String level) {
        Map.Entry<String, Group> victim = quietest("WARN");
        if (victim == null && "ERROR".equals(level)) {
            victim = quietest("ERROR");
        }
        if (victim == null) {
            return false;
        }
        if (groups.remove(victim.getKey(), victim.getValue())) {
            overflow.incrementAndGet();
        }
        return true;
    }

    private Map.Entry<String, Group> quietest(String level) {
        Map.Entry<String, Group> found = null;
        for (Map.Entry<String, Group> e : groups.entrySet()) {
            Group g = e.getValue();
            if (g.level.equals(level) && (found == null || g.lastSeen().isBefore(found.getValue().lastSeen()))) {
                found = e;
            }
        }
        return found;
    }

    /** 틀: 가리고, 숫자를 지우고, 자른다. 자리표시자가 든 틀은 코드 상수라 결과를 기억한다. */
    private String template(String raw) {
        String t = raw == null ? "" : raw;
        String known = templates.get(t);
        if (known != null) {
            return known;
        }
        String cleaned = clip(normalize(redact(scanPrefix(t))), MAX_TEMPLATE, MAX_TEMPLATE_BYTES);
        if (t.contains("{}") && templates.size() < MAX_TEMPLATE_CACHE) {
            templates.putIfAbsent(t, cleaned);
        }
        return cleaned;
    }

    /** 문장 하나를 가리고 나서 자른다. 관리 JVM 의 수집기도 노드가 보낸 문장에 한 번 더 건다. */
    public static String clean(String text, int maxChars, int maxBytes) {
        return text == null ? null : clip(redact(scanPrefix(text)), maxChars, maxBytes);
    }

    /** 틀 안의 UUID 와 숫자는 호출마다 달라 묶음을 쪼개므로 #으로 바꾼다. 가린 뒤에 부른다. */
    static String normalize(String template) {
        return DIGITS.matcher(UUID.matcher(template).replaceAll("#")).replaceAll("#");
    }

    /** 주소, 자격 증명, 토큰, 긴 숫자열을 가린다. 문장이 길면 scanPrefix 로 먼저 줄여서 부른다. */
    static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String t = URL.matcher(text).replaceAll("[주소]");
        t = EMAIL.matcher(t).replaceAll("[이메일]");
        t = AUTH.matcher(t).replaceAll("[자격 증명]");
        t = SECRET.matcher(t).replaceAll("$1$2$1$3[비밀]");
        t = JWT.matcher(t).replaceAll("[토큰]");
        t = UUID.matcher(t).replaceAll("[id]");
        t = HOST.matcher(t).replaceAll("[주소]");
        t = IPV4.matcher(t).replaceAll("[주소]");
        t = IPV6.matcher(t).replaceAll("[주소]");
        t = TOKEN.matcher(t).replaceAll("[토큰]");
        return LONG_DIGITS.matcher(t).replaceAll("[숫자]");
    }

    /**
     * 가리기 전에 읽을 앞부분. MAX_SCAN 을 넘으면 그 안의 마지막 공백까지만 남기고 [생략]을 붙인다. 끝에 걸친 낱말(반쯤 잘린
     * 토큰일 수 있다)을 통째로 버리기 위해서다. 공백이 없으면 [생략]만 남는다.
     */
    static String scanPrefix(String text) {
        if (text == null || text.length() <= MAX_SCAN) {
            return text;
        }
        int space = -1;
        for (int i = MAX_SCAN; i >= 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                space = i;
                break;
            }
        }
        return space <= 0 ? CUT : text.substring(0, space) + " " + CUT;
    }

    /** 글자 수와 UTF-8 바이트 수가 모두 상한 안이 되게 코드 포인트 단위로 자른다. */
    static String clip(String text, int maxChars, int maxBytes) {
        if (text == null) {
            return null;
        }
        int bytes = 0;
        int chars = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (chars + 1 > maxChars || bytes + size > maxBytes) {
                break;
            }
            chars++;
            bytes += size;
            i += Character.charCount(cp);
        }
        return i == text.length() ? text : text.substring(0, i);
    }

    /** JSON 으로 쓸 때의 바이트 수를 넉넉하게 잰다. 응답 상한을 지키는 데 쓴다. */
    static long jsonBytes(Map<String, Object> row) {
        long n = 2;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            n += jsonBytes(e.getKey()) + 2;
            Object v = e.getValue();
            n += v == null ? 4 : v instanceof String s ? jsonBytes(s) : String.valueOf(v).length();
        }
        return n;
    }

    private static long jsonBytes(String s) {
        long n = 2;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                n += 2;
            } else if (c < 0x20) {
                n += 6;
            } else if (c < 0x80) {
                n += 1;
            } else if (c < 0x800 || Character.isSurrogate(c)) {
                n += 2;
            } else {
                n += 3;
            }
        }
        return n;
    }

    private static IThrowableProxy root(IThrowableProxy thrown) {
        IThrowableProxy t = thrown;
        while (t != null && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** 예외가 지나간 첫 앱 프레임(클래스.메서드). 바깥 예외에 없으면 원인 쪽을 본다. 줄 번호는 빌드마다 달라 뺀다. */
    private static String frame(IThrowableProxy thrown) {
        for (IThrowableProxy t = thrown; t != null; t = t.getCause()) {
            StackTraceElementProxy[] frames = t.getStackTraceElementProxyArray();
            if (frames == null) {
                continue;
            }
            for (StackTraceElementProxy f : frames) {
                StackTraceElement e = f.getStackTraceElement();
                if (e.getClassName().startsWith(APP_PACKAGE)) {
                    String cls = e.getClassName();
                    return cls.substring(cls.lastIndexOf('.') + 1) + "." + e.getMethodName();
                }
            }
        }
        return null;
    }

    private static String id(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }

    /**
     * 묶음 하나. 분 단위 칸은 최근 15분만 두고, 칸의 분이 지나면 그 칸을 비우고 다시 센다. 대표 문장은 가린 것이고 1초에 한
     * 번만 바꾼다.
     */
    static final class Group {

        record Snapshot(Group group, long count, long total, Instant lastSeen, String message,
                        String exceptionMessage) {
        }

        final String id;
        final String level;
        final String logger;
        final String template;
        final String exception;
        final String frame;
        final Instant firstSeen;
        private final long[] minuteOf = new long[WINDOW_MINUTES];
        private final long[] counts = new long[WINDOW_MINUTES];
        private long total;
        private volatile Instant lastSeen;
        private boolean claimed;
        private long claimedAt;
        private long textAt = Long.MIN_VALUE;
        private String message;
        private String exceptionMessage;

        Group(String id, String level, String logger, String template, String exception, String frame,
              Instant firstSeen) {
            this.id = id;
            this.level = level;
            this.logger = logger;
            this.template = template;
            this.exception = exception;
            this.frame = frame;
            this.firstSeen = firstSeen;
            this.lastSeen = firstSeen;
        }

        Instant lastSeen() {
            return lastSeen;
        }

        synchronized void count(Instant at) {
            long minute = at.getEpochSecond() / 60;
            int slot = (int) Math.floorMod(minute, WINDOW_MINUTES);
            if (minuteOf[slot] != minute) {
                minuteOf[slot] = minute;
                counts[slot] = 0;
            }
            counts[slot]++;
            total++;
            if (at.isAfter(lastSeen)) {
                lastSeen = at;
            }
        }

        /**
         * 대표 문장을 바꿀 차례인가. 처음이거나 마지막으로 차례를 가져간 로그보다 1초 이상 뒤의 로그면 차례를 가져간다. 가리기는
         * 잠금 밖에서 한다.
         */
        synchronized boolean claimText(Instant at) {
            long ms = at.toEpochMilli();
            if (claimed && ms - claimedAt < MESSAGE_REFRESH_MILLIS) {
                return false;
            }
            claimed = true;
            claimedAt = ms;
            return true;
        }

        /** 가린 문장을 넣는다. 더 늦은 로그의 문장이 먼저 들어와 있으면 그대로 둔다. */
        synchronized void text(long at, String message, String exceptionMessage) {
            if (at >= textAt) {
                textAt = at;
                this.message = message;
                this.exceptionMessage = exceptionMessage;
            }
        }

        synchronized Snapshot snapshot(long nowMinute) {
            long count = 0;
            for (int i = 0; i < WINDOW_MINUTES; i++) {
                if (minuteOf[i] > nowMinute - WINDOW_MINUTES && minuteOf[i] <= nowMinute) {
                    count += counts[i];
                }
            }
            return new Snapshot(this, count, total, lastSeen, message, exceptionMessage);
        }
    }
}
