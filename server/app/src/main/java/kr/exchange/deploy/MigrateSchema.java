package kr.exchange.deploy;

import org.flywaydb.core.Flyway;

/** 배포 창에 한 번 실행하는 마이그레이션 진입점. 웹·봇·매칭 엔진은 생성하지 않는다. */
public final class MigrateSchema {

    private MigrateSchema() { }

    public static void main(String[] args) {
        Flyway.configure()
                .dataSource(required("MIGRATION_JDBC_URL"), required("MIGRATION_DB_USER"),
                        required("MIGRATION_DB_PASSWORD"))
                .locations("classpath:db/migration")
                .initSql("SET TIME ZONE 'UTC'")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("마이그레이션 필수 설정이 없습니다: " + key);
        }
        return value;
    }
}
