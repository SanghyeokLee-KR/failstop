import org.flywaydb.core.Flyway;

/** 거래 앱을 기동하지 않고, 같은 JAR에 포함된 Flyway로 스키마만 적용한다. */
public final class MigrateDatabase {
    public static void main(String[] args) {
        if (args.length != 2 || !(args[0].equals("migrate") || args[0].equals("validate"))) {
            throw new IllegalArgumentException("Usage: MigrateDatabase migrate|validate migration-directory");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(required("MIGRATION_DB_URL"), required("MIGRATION_DB_USER"),
                        required("MIGRATION_DB_PASSWORD"))
                .locations("filesystem:" + args[1])
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load();
        if (args[0].equals("migrate")) {
            System.out.println("Applied migrations: " + flyway.migrate().migrationsExecuted);
        }
        flyway.validate();
        System.out.println("Schema validation passed.");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
