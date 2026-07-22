package kr.exchange.api.common;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 바디를 바이트로 캐시해 여러 소비자(크기 제한 필터·서명 검증 필터·컨트롤러 파싱)가 같은 바이트를
 * 반복해서 읽게 한다. 원본 입력 스트림은 1회만 소비 가능하므로 캐시가 없으면 후속 소비자가 빈 바디를 본다.
 */
public final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        // getInputStream만 재정의하면 getReader를 쓰는 컨트롤러(@RequestBody 대신 reader 파싱)가
        // 원본 소진된 스트림을 읽어 빈 바디를 본다. 캐시 바이트를 요청 charset으로 재현한다.
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }
}
