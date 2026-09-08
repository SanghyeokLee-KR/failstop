package kr.exchange.engine.lease;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * 리스 테이블 준비. 로컬 검증 전용 경로다.
 *
 * 운영에서 쓰지 않는 이유는 권한 경계다. 참으로 켜면 앱 롤에 CreateTable 이 필요하고, 그 권한은
 * 리스 한 항목을 읽고 쓰는 데 필요한 범위를 훨씬 넘는다. 운영은 테이블을 미리 만들고 앱에는
 * 항목 수준 권한만 준다.
 */
public final class DynamoDbLeaseTable {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbLeaseTable.class);

    private DynamoDbLeaseTable() {
    }

    /** 없으면 만든다. 이미 있으면 아무것도 하지 않는다. 동시 생성 경쟁도 흡수한다. */
    public static void ensure(DynamoDbClient client, String table, String partitionKey) {
        try {
            client.describeTable(DescribeTableRequest.builder().tableName(table).build());
            return;
        } catch (ResourceNotFoundException e) {
            // 아래에서 만든다.
        }
        try {
            client.createTable(CreateTableRequest.builder()
                    .tableName(table)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName(partitionKey)
                            .attributeType(ScalarAttributeType.S)
                            .build())
                    .keySchema(KeySchemaElement.builder()
                            .attributeName(partitionKey)
                            .keyType(KeyType.HASH)
                            .build())
                    .build());
            client.waiter().waitUntilTableExists(
                    DescribeTableRequest.builder().tableName(table).build());
            log.info("리스 테이블 생성: {}", table);
        } catch (ResourceInUseException e) {
            // 다른 노드가 먼저 만들었다. 정상 경로다.
            log.info("리스 테이블 선재: {}", table);
        }
    }
}
