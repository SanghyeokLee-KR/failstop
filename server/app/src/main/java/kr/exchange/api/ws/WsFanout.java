package kr.exchange.api.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.ws.relay.RelayEnvelope;
import kr.exchange.api.ws.relay.RelayPublisher;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;

import java.util.Map;

/**
 * 게시 본문을 노드 내장 브로커로 보내고, 중계 모드면 같은 바이트를 중계 봉투로 발행 큐에 넣는다(S6 설계 4-4). 본문은 한 번만
 * 직렬화한다. 내장 브로커에 먼저 보내 자기 세션이 먼저 받는다. 받은 중계 봉투는 deliver 로 내장 브로커에만 보낸다.
 *
 * 바이트를 변환기 없이 보낸다. 변환기를 거치면 바이트 배열이 JSON 문자열로 다시 감싸진다. content-type 을 JSON 으로 두어
 * 브라우저에 텍스트 프레임으로 나간다(octet-stream 이면 바이너리 프레임이다).
 */
public final class WsFanout {

    private final SimpMessagingTemplate messaging;
    private final ObjectMapper json;
    // simple 모드면 null 이다.
    private final RelayPublisher relay;
    private final String holder;

    public WsFanout(SimpMessagingTemplate messaging, ObjectMapper json, RelayPublisher relay, String holder) {
        this.messaging = messaging;
        this.json = json;
        this.relay = relay;
        this.holder = holder;
    }

    public boolean relaying() {
        return relay != null;
    }

    /** 공개 목적지(/topic/...) 게시. 내장 브로커로 보낸 바이트를 돌려준다. */
    public byte[] topic(String destination, String market, long epoch, long seq, Map<String, Object> body) {
        byte[] bytes = serialize(body);
        deliverTopic(destination, bytes);
        if (relay != null) {
            relay.offer(RelayEnvelope.topic(holder, destination, market, epoch, seq, bytes));
        }
        return bytes;
    }

    /** 사용자 목적지(/queue/...) 게시. 그 사용자의 세션이 이 노드에 있을 때만 전달된다. */
    public void user(long userId, String queue, String market, long epoch, long seq, Map<String, Object> body) {
        byte[] bytes = serialize(body);
        deliverUser(userId, queue, bytes);
        if (relay != null) {
            relay.offer(RelayEnvelope.user(holder, userId, queue, market, epoch, seq, bytes));
        }
    }

    /** 이 노드의 세션에만 보낸다. 캔들처럼 노드마다 따로 계산하는 게시가 쓴다. */
    public void localTopic(String destination, Map<String, Object> body) {
        deliverTopic(destination, serialize(body));
    }

    public void deliverTopic(String destination, byte[] body) {
        messaging.send(destination, MessageBuilder.createMessage(body, jsonHeaders().getMessageHeaders()));
    }

    /** convertAndSendToUser 와 같은 목적지(/user/{id}/queue/...)로 보낸다. */
    public void deliverUser(long userId, String queue, byte[] body) {
        messaging.send("/user/" + userId + queue, MessageBuilder.createMessage(body, jsonHeaders().getMessageHeaders()));
    }

    private static SimpMessageHeaderAccessor jsonHeaders() {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        headers.setLeaveMutable(true);
        return headers;
    }

    private byte[] serialize(Map<String, Object> body) {
        try {
            return json.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("WS 게시 본문 직렬화 실패", e);
        }
    }
}
