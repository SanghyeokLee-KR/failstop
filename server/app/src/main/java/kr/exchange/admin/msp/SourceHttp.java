package kr.exchange.admin.msp;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 원천 HTTP 호출. 리다이렉트를 따르지 않고 프록시를 쓰지 않으며, 응답 한 건의 시간과 크기에 상한을 둔다
 * (deploy/console/metrics.py 와 같은 규칙). 시간 상한은 본문을 다 받을 때까지를 잰다.
 */
final class SourceHttp {

    static final int MAX_RESPONSE = 1024 * 1024;

    record Response(int status, byte[] body) {
    }

    private final HttpClient http;
    private final Duration timeout;
    private final int maxResponse;

    SourceHttp(Duration timeout) {
        this(timeout, timeout, MAX_RESPONSE, null);
    }

    /** ssl 이 있으면 그 신뢰 저장소만 믿는다. 호스트 이름 검증은 끄지 않는다. */
    SourceHttp(Duration connectTimeout, Duration timeout, int maxResponse, SSLContext ssl) {
        this.timeout = timeout;
        this.maxResponse = maxResponse;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY);
        if (ssl != null) {
            builder.sslContext(ssl);
        }
        this.http = builder.build();
    }

    Response get(URI uri) {
        return get(uri, Map.of());
    }

    Response get(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
        headers.forEach(b::header);
        return send(b.GET().build());
    }

    Response post(URI uri, byte[] json) {
        return post(uri, json, Map.of());
    }

    Response post(URI uri, byte[] json, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "application/json");
        headers.forEach(b::header);
        return send(b.POST(HttpRequest.BodyPublishers.ofByteArray(json)).build());
    }

    Response delete(URI uri) {
        return send(HttpRequest.newBuilder(uri).timeout(timeout).DELETE().build());
    }

    private Response send(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request, info -> new CappedBody(maxResponse));
        try {
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new SourceException("timeout", true);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new SourceException("timeout", true);
        } catch (ExecutionException e) {
            throw classify(e.getCause());
        }
    }

    private static SourceException classify(Throwable cause) {
        if (cause instanceof ResponseTooLarge) {
            return new SourceException("response_limit", false);
        }
        // 연결을 맺지 못한 실패는 요청이 원천에 닿지 않았다. 그 밖의 입출력 실패는 닿았는지 모른다.
        if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                || cause instanceof UnresolvedAddressException) {
            return new SourceException("unreachable", false);
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return new SourceException("timeout", true);
        }
        return new SourceException("unreachable", true);
    }

    static final class ResponseTooLarge extends RuntimeException {
        ResponseTooLarge() {
            super("response_limit", null, false, false);
        }
    }

    /** 상한을 넘는 순간 구독을 끊고 실패로 끝낸다. 넘는 본문을 끝까지 받지 않는다. */
    static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final int limit;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CappedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : items) {
                if (out.size() + buffer.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLarge());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }
    }
}
