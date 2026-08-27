package kr.exchange.api.common;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;

/**
 * 요청 바디 상한(API 설계 §2-6·§2-7, 64KB) 단일 정의. RequestSizeLimitFilter와 ApiAuthFilter가
 * 같은 상한을 공유해 두 곳에 상수가 갈라지지 않게 한다.
 */
public final class RequestBodyLimit {

    public static final int MAX_BODY_BYTES = 64 * 1024;

    private RequestBodyLimit() {
    }

    /**
     * 상한을 넘으면 전량 적재하지 않고 즉시 413으로 끊는다. Content-Length가 있으면 선검사로 조기 차단하고,
     * 없거나 위조된 경우에도 실제 읽기에서 상한+1 바이트를 확인해 chunked 우회를 막는다.
     */
    public static byte[] readBounded(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            throw payloadTooLarge();
        }
        InputStream in = request.getInputStream();
        byte[] buffer = new byte[MAX_BODY_BYTES + 1];
        int total = 0;
        int read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        if (total > MAX_BODY_BYTES) {
            throw payloadTooLarge();
        }
        byte[] body = new byte[total];
        System.arraycopy(buffer, 0, body, 0, total);
        return body;
    }

    public static ApiException payloadTooLarge() {
        return new ApiException(413, "payload_too_large", "요청 바디 64KB 상한 초과");
    }
}
