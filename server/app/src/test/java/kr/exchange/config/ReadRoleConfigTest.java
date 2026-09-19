package kr.exchange.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class ReadRoleConfigTest {

    private MockEnvironment valid() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "read");
        env.setProperty("exchange.node.role", "read");
        for (String key : new String[]{"exchange.engine.enabled", "spring.flyway.enabled",
                "exchange.harness.enabled", "exchange.admin.enabled"}) {
            env.setProperty(key, "false");
        }
        env.setProperty("exchange.leader.lease.provider", "postgres");
        for (String key : new String[]{"spring.datasource.hikari.read-only",
                "exchange.engine.append-datasource.hikari.read-only",
                "exchange.engine.lease-datasource.hikari.read-only"}) {
            env.setProperty(key, "true");
        }
        return env;
    }

    @Test
    void acceptsOnlyTheConstrainedReadRole() {
        assertDoesNotThrow(() -> ReadRoleConfig.validate(valid()));
        for (String profile : new String[]{"dev", "bots", "admin", "admin-local", "chaos-monkey"}) {
            MockEnvironment env = valid();
            env.addActiveProfile(profile);
            assertThrows(IllegalStateException.class, () -> ReadRoleConfig.validate(env), profile);
        }
    }

    @Test
    void rejectsOverridesThatCouldStartWritersOrMigrations() {
        for (String key : new String[]{"exchange.node.role", "exchange.engine.enabled",
                "spring.flyway.enabled", "exchange.harness.enabled", "exchange.admin.enabled",
                "exchange.leader.lease.provider", "spring.datasource.hikari.read-only",
                "exchange.engine.append-datasource.hikari.read-only",
                "exchange.engine.lease-datasource.hikari.read-only"}) {
            MockEnvironment env = valid();
            env.setProperty(key, "unsafe-override");
            assertThrows(IllegalStateException.class, () -> ReadRoleConfig.validate(env), key);
        }
        MockEnvironment env = valid();
        env.setActiveProfiles("read");
        assertThrows(IllegalStateException.class, () -> ReadRoleConfig.validate(env));
    }
}
