package kr.exchange.api.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 응답 표기 규약 단일 지점. 시각은 UTC 밀리초 3자리 고정, 금액은 문자열이다(API 설계 §2-1). */
public final class ApiFormats {

    public static final String SCHEMA_VERSION = "1.0";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private ApiFormats() {
    }

    public static String time(Instant instant) {
        return TS.format(instant);
    }

    public static String time(OffsetDateTime odt) {
        return TS.format(odt.toInstant());
    }

    public static String timeMs(long epochMs) {
        return TS.format(Instant.ofEpochMilli(epochMs));
    }

    /** JSON float 금지 계약의 표기. 후행 0을 제거하되 지수 표기는 쓰지 않는다. */
    public static String amount(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
