package kr.exchange.deploy;

import kr.exchange.config.MigrationOrderGuard;
import org.flywaydb.core.Flyway;

/** 배포 창에 한 번 실행하는 마이그레이션 진입점. 웹·봇·매칭 엔진은 생성하지 않는다. */
public final class MigrateSchema {

    private MigrateSchema() { }

    public static void main(String[] args) {
        migrate(required("MIGRATION_JDBC_URL"), required("MIGRATION_DB_USER"), required("MIGRATION_DB_PASSWORD"));
    }

    /** 적용 순서를 점검한 뒤 적용한다. 점검이 거부하면 아무것도 적용하지 않고 예외로 멈춘다(DB 설계 §10.6). */
    public static void migrate(String jdbcUrl, String user, String password) {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration")
                .initSql("SET TIME ZONE 'UTC'")
                .cleanDisabled(true)
                .load();
        MigrationOrderGuard.check(flyway);
        flyway.migrate();
    }

    static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("마이그레이션 필수 설정 누락: " + key);
        }
        return value;
    }
}
