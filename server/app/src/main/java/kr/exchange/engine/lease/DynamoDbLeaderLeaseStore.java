package kr.exchange.engine.lease;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB 항목 하나를 리스 보관소로 쓰는 구현. 거래 데이터의 커밋 경로와 완전히 분리된다.
 *
 * 항목 하나가 클러스터 하나다. 파티션 키는 clusterId 이고 값은 leaderId, leaseUntil,
 * fencingToken, updatedAt 이다. fencingToken 이 PostgreSQL 의 epoch 과 같은 것이며 승격마다
 * 단조 증가한다.
 *
 * 세 연산 모두 조건부 쓰기 한 번이다. 읽고 나서 쓰면 두 노드가 같은 만료를 보고 함께 승격한다.
 * DynamoDB 의 단일 항목 조건부 갱신은 원자적이므로 경쟁에서 정확히 한 쪽만 성공한다.
 *
 * 시계에 관한 한계를 적어 둔다. PostgreSQL 보관소는 만료를 clock_timestamp() 로 판정해 모든
 * 노드가 같은 시계를 봤다. DynamoDB 는 조건식에서 쓸 서버 시계를 노출하지 않으므로 만료 판정의
 * 기준 시각이 호출한 노드의 시계다. 곧 노드 사이 시계 스큐가 리스 만료 판정에 들어온다.
 * 스큐가 TTL 에 가까우면 이른 탈취가 가능하다. 이 저장소는 시계 스큐 내성을 검증 범위 밖으로
 * 두고 있으므로(기획안 13.2 한계 7) 이 성질을 그 자리에 함께 등재한다.
 */
public final class DynamoDbLeaderLeaseStore implements LeaderLeaseStore {

    private static final String KEY = "clusterId";
    private static final String LEADER = "leaderId";
    private static final String UNTIL = "leaseUntil";
    private static final String TOKEN = "fencingToken";
    private static final String UPDATED = "updatedAt";

    private final DynamoDbClient client;
    private final String table;
    private final String clusterId;
    private final Clock clock;
    // 이 노드가 승격을 감시하기 시작한 시각. 첫 다운타임 산정에서 잡는다. 하한이 없으면 스택을
    // 껐다 켠 시간이 통째로 다운타임이 되어 RTO 게이지가 기동마다 오염된다.
    private volatile Instant watchingSince;

    public DynamoDbLeaderLeaseStore(DynamoDbClient client, String table, String clusterId,
                                    Clock clock) {
        if (client == null || table == null || table.isBlank()
                || clusterId == null || clusterId.isBlank() || clock == null) {
            throw new IllegalArgumentException("client·table·clusterId·clock은 필수");
        }
        this.client = client;
        this.table = table;
        this.clusterId = clusterId;
        this.clock = clock;
    }

    /**
     * 승격. 항목이 없거나 만료된 경우에만 성립하고 fencingToken 을 1 올린다.
     *
     * if_not_exists 로 최초 승격을 같은 문장에 담는다. 항목을 미리 심어 두는 부트스트랩을 두면
     * 그 심기가 빠진 환경에서 최초 선출이 영구 불가능해진다. PostgreSQL 보관소가 시드 행을
     * 요구하는 것이 그 형태이고 여기서는 그 요구를 없앤다.
     */
    @Override
    public Optional<Long> acquire(String holder, Duration ttl) {
        long now = clock.millis();
        try {
            UpdateItemResponse res = client.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(key())
                    // 만료 시각이 없는 항목도 만료로 본다. 전환 정렬(FencingTokenAlignment)이
                    // 토큰만 심어 둔 항목이 그 형태이고, 없는 속성과의 비교는 거짓이라
                    // 이 갈래가 없으면 그 항목에서 승격이 영원히 실패한다. 2026-09-08 전환에서
                    // 실제로 막혔다.
                    .conditionExpression("attribute_not_exists(" + KEY + ") OR attribute_not_exists("
                            + UNTIL + ") OR " + UNTIL + " < :now")
                    .updateExpression("SET " + LEADER + " = :me, " + UNTIL + " = :until, "
                            + TOKEN + " = if_not_exists(" + TOKEN + ", :zero) + :one, "
                            + UPDATED + " = :now")
                    .expressionAttributeValues(values(Map.of(
                            ":now", num(now),
                            ":until", num(now + ttl.toMillis()),
                            ":me", str(holder),
                            ":zero", num(0),
                            ":one", num(1))))
                    .returnValues(ReturnValue.ALL_NEW)
                    .build());
            return Optional.of(Long.parseLong(res.attributes().get(TOKEN).n()));
        } catch (ConditionalCheckFailedException e) {
            // 유효한 타 리더가 있다. 실패가 아니라 정상 경로다.
            return Optional.empty();
        }
    }

    /**
     * 하트비트. 보유자와 토큰이 그대로이고 아직 만료 전일 때만 성립한다.
     *
     * 만료 전 조건을 남기는 이유는 PostgreSQL renew_leader_lease 와 같은 성질을 지키기 위해서다.
     * 만료 뒤 갱신을 허용하면 오래 멈췄던 노드가 아무 일 없었다는 듯 리더를 이어받고, 그 사이
     * 승격한 노드와 겹친다.
     *
     * 조건 불일치만 거짓이다. 왕복 실패는 그대로 던져 호출자가 '거부' 와 '모름' 을 가르게 한다.
     */
    @Override
    public boolean renew(String holder, long epoch, Duration ttl) {
        long now = clock.millis();
        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(key())
                    .conditionExpression(LEADER + " = :me AND " + TOKEN + " = :tok AND "
                            + UNTIL + " >= :now")
                    .updateExpression("SET " + UNTIL + " = :until, " + UPDATED + " = :now")
                    .expressionAttributeValues(values(Map.of(
                            ":now", num(now),
                            ":until", num(now + ttl.toMillis()),
                            ":me", str(holder),
                            ":tok", num(epoch))))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /** 반납. 만료 시각만 과거로 민다. fencingToken 은 건드리지 않는다. */
    @Override
    public void release(String holder, long epoch) {
        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(key())
                    .conditionExpression(LEADER + " = :me AND " + TOKEN + " = :tok")
                    .updateExpression("SET " + UNTIL + " = :past, " + UPDATED + " = :now")
                    .expressionAttributeValues(values(Map.of(
                            ":now", num(clock.millis()),
                            ":past", num(0),
                            ":me", str(holder),
                            ":tok", num(epoch))))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // 이미 남의 리스다. 반납할 것이 없다.
        }
    }

    /** 강한 일관성으로 읽는다. 최종 일관성 읽기는 방금 자기가 쓴 리스도 낡게 볼 수 있다. */
    @Override
    public Optional<LeaseSnapshot> current() {
        Map<String, AttributeValue> item = client.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(key())
                .consistentRead(true)
                .build()).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LeaseSnapshot(
                item.getOrDefault(LEADER, str("")).s(),
                Long.parseLong(item.getOrDefault(TOKEN, num(0)).n()),
                Instant.ofEpochMilli(Long.parseLong(item.getOrDefault(UNTIL, num(0)).n()))));
    }

    /**
     * 구 리더 최종 심박부터 지금까지. 항목이 없거나 보유자가 비었으면 실 페일오버가 아니므로 0 이다.
     * 읽기 실패는 0 으로 흡수한다. 관측 게이지 하나 때문에 승격을 막을 이유가 없다.
     */
    @Override
    public double priorDowntimeSeconds(Duration ttl) {
        try {
            Instant now = clock.instant();
            if (watchingSince == null) {
                watchingSince = now;
            }
            Optional<LeaseSnapshot> snap = current();
            if (snap.isEmpty() || snap.get().holder().isEmpty()) {
                return 0;
            }
            Instant lastHeartbeatFloor = snap.get().leaseUntil().isAfter(watchingSince)
                    ? snap.get().leaseUntil() : watchingSince;
            double expiredSeconds = Duration.between(lastHeartbeatFloor, now).toMillis() / 1000.0;
            if (expiredSeconds < 0) {
                return 0;
            }
            return expiredSeconds + ttl.toMillis() / 1000.0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public String describe() {
        return "dynamodb(table=" + table + ", clusterId=" + clusterId + ")";
    }

    private Map<String, AttributeValue> key() {
        return Map.of(KEY, str(clusterId));
    }

    /** Map.of 는 순서가 없다. 조건식 디버깅에서 값 순서가 흔들리지 않게 정렬 맵으로 옮긴다. */
    private static Map<String, AttributeValue> values(Map<String, AttributeValue> raw) {
        return new LinkedHashMap<>(raw);
    }

    private static AttributeValue str(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static AttributeValue num(long v) {
        return AttributeValue.builder().n(Long.toString(v)).build();
    }
}
