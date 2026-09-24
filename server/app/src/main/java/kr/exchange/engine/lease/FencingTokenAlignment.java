package kr.exchange.engine.lease;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.Map;

/**
 * 보관소 전환 이음매. DynamoDB 의 fencing 토큰을 PostgreSQL 의 현재 epoch 위로 올린다.
 *
 * 왜 필요한가. 두 보관소는 서로 다른 카운터를 쓴다. `leader_lease.epoch` 은 그동안의 승격 횟수만큼
 * 올라가 있는데 새 DynamoDB 항목의 토큰은 0 에서 시작한다. 그대로 켜면 첫 승격이 토큰 1 을 받고,
 * `publish_leader_epoch` 의 단조 조건(`epoch < p_epoch`)이 1 을 9 위에 올리기를 거부한다.
 * 게시 실패는 승격 실패이므로 클러스터가 리더를 영원히 뽑지 못한다. 실제로 2026-09-08 전환에서
 * PostgreSQL epoch 이 9 였고 이 경로로 막혔다.
 *
 * 왜 절차가 아니라 코드인가. 빠뜨리면 증상이 '리더 선출이 안 된다' 이고 원인은 로그 한 줄에만
 * 나온다. 잊을 수 있는 수동 단계를 배포 경로에 두지 않는다.
 *
 * 안전성. 조건부 쓰기라 여러 노드가 동시에 불러도 단조다. 토큰이 이미 충분히 크면 아무것도 하지
 * 않으므로 재기동마다 불러도 무해하다. 리스 보유자와 만료 시각은 건드리지 않는다.
 */
public final class FencingTokenAlignment {

    private static final Logger log = LoggerFactory.getLogger(FencingTokenAlignment.class);

    private FencingTokenAlignment() {
    }

    /**
     * PostgreSQL 의 현재 epoch 을 읽어 DynamoDB 토큰의 하한으로 건다.
     *
     * 실패는 삼킨다. 이 정렬은 전환 1 회차에만 의미가 있고, 실패해도 그 결과는 승격 게시 거부라는
     * 뚜렷한 신호로 드러난다. 여기서 기동을 막으면 정렬이 필요 없는 배포까지 못 뜬다.
     */
    public static void align(DynamoDbClient client, String table, String clusterId,
                             JdbcClient jdbc) {
        Long pgEpoch;
        try {
            pgEpoch = floor(jdbc);
        } catch (RuntimeException e) {
            log.warn("fencing 토큰 정렬을 건너뛴다(leader_lease 조회 실패): {}", e.toString());
            return;
        }
        if (pgEpoch == null || pgEpoch <= 0) {
            return;
        }
        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("clusterId", AttributeValue.builder().s(clusterId).build()))
                    .conditionExpression(
                            "attribute_not_exists(clusterId) OR fencingToken < :floor")
                    // 토큰만 심으면 만료 시각이 없는 항목이 생기고, 없는 속성과의 비교는
                    // 거짓이라 승격 조건이 영원히 성립하지 않는다. 만료 시각과 보유자를 함께
                    // 기본값으로 채워 '아무도 안 잡은 만료된 리스' 형태를 온전히 만든다.
                    .updateExpression("SET fencingToken = :floor, "
                            + "leaseUntil = if_not_exists(leaseUntil, :zero), "
                            + "leaderId = if_not_exists(leaderId, :empty)")
                    .expressionAttributeValues(Map.of(
                            ":floor", AttributeValue.builder().n(Long.toString(pgEpoch)).build(),
                            ":zero", AttributeValue.builder().n("0").build(),
                            ":empty", AttributeValue.builder().s("").build()))
                    .build());
            log.info("fencing 토큰 정렬: DynamoDB 토큰 하한을 PostgreSQL epoch {} 으로 올렸다", pgEpoch);
        } catch (ConditionalCheckFailedException e) {
            // 이미 충분히 크다. 정상 경로다.
        } catch (RuntimeException e) {
            log.warn("fencing 토큰 정렬 실패(승격 게시가 거부되면 이 줄을 먼저 본다): {}", e.toString());
        }
    }

    /**
     * DynamoDB 토큰의 하한. epoch 배정 규칙과 같은 max(모든 리스 행 epoch, 획득 이력 epoch) 다(DB 설계 §5.4-b v2.54).
     *
     * 종전에는 matching-engine 행 하나만 읽었다. 모드 전환(set_leader_protocol)은 리스 행 셋을 새 epoch 로 퇴역시키고 그
     * 이력을 lease_acquisitions 에 남기므로, 한 행만 읽은 하한 + 1 게시가 다른 행의 RETIRE 이력과 겹쳐 "게시 epoch 가 다른
     * 획득 이력과 겹친다"로 거부될 수 있었다(S3 설계 11절 R4). 게시 실패는 승격 포기라 그만큼 리더가 없다. 전환 함수가
     * matching-engine 을 마지막에 퇴역시키는 것과 함께 두 겹이다. 게시 판정은 바꾸지 않으므로 fencing 은 약해지지 않는다.
     */
    public static long floor(JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT GREATEST((SELECT COALESCE(max(epoch), 0) FROM leader_lease),
                                        (SELECT COALESCE(max(epoch), 0) FROM lease_acquisitions))
                        """)
                .query(Long.class)
                .single();
    }
}
