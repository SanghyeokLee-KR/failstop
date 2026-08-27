package kr.exchange.api.auth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import kr.exchange.api.common.ApiException;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 서명 canonical 산출 단일 지점(API 설계 §3-3). 클라이언트(테스트 서명기 포함)와 서버가
 * 같은 구현 규약(JCS RFC 8785, 디코딩된 쿼리 키 정렬)을 공유해야 바이트 단위 재현이 성립한다.
 */
public final class CanonicalRequests {

    // JsonCanonicalizer는 중복 키를 조용히 마지막 값으로 덮어 서명 우회를 허용한다. §3-3(b)는 중복 키
    // 400을 요구하므로 정규화 전에 엄격 파서로 검출한다.
    private static final JsonFactory STRICT_JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private CanonicalRequests() {
    }

    /** (a) 쿼리 파라미터 요청. 값은 URL 디코딩 후 기준, 키 오름차순, 중복 키 금지. */
    public static String forQuery(String method, String path, Map<String, String[]> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String[]> e : params.entrySet()) {
            if (e.getValue() != null && e.getValue().length > 1) {
                throw new ApiException(400, "invalid_parameter",
                        "중복 쿼리 키 불가: " + e.getKey());
            }
            sorted.put(e.getKey(),
                    e.getValue() == null || e.getValue().length == 0 ? "" : e.getValue()[0]);
        }
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(e.getKey()).append('=').append(e.getValue());
        }
        return method + "\n" + path + "\n" + query;
    }

    /** (b) JSON 바디 요청. JCS(RFC 8785) 정규화 원문 바이트가 CANONICAL_PAYLOAD다. */
    public static String forBody(String method, String path, byte[] body) {
        return method + "\n" + path + "\n" + jcs(body);
    }

    public static String jcs(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        rejectDuplicateKeys(body);
        try {
            return new JsonCanonicalizer(new String(body, StandardCharsets.UTF_8)).getEncodedString();
        } catch (IOException e) {
            throw new ApiException(400, "invalid_parameter", "요청 바디가 유효한 JSON이 아닙니다.");
        }
    }

    private static void rejectDuplicateKeys(byte[] body) {
        try (JsonParser parser = STRICT_JSON.createParser(body)) {
            while (parser.nextToken() != null) {
                // 전체 순회로 중첩 오브젝트 중복 키까지 검출한다.
            }
        } catch (IOException e) {
            throw new ApiException(400, "invalid_parameter",
                    "요청 바디에 중복 키가 있거나 유효한 JSON이 아닙니다.");
        }
    }

    public static String sha512Hex(String canonical) {
        return HexFormat.of().formatHex(
                digest("SHA-512").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(
                digest("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " 미지원 런타임", e);
        }
    }
}
