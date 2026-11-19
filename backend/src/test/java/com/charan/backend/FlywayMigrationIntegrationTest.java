package com.charan.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("Verify that Flyway migrations V1, V2, and V3 executed successfully on the throwaway container")
    void flywayMigrationsV1AndV2AndV3RanSuccessfully() {
        MigrationInfo[] appliedMigrations = flyway.info().applied();

        // Must have applied exactly 3 migrations: V1, V2, and V3
        assertThat(appliedMigrations).hasSize(3);

        // V1 verification
        assertThat(appliedMigrations[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(appliedMigrations[0].getDescription()).isEqualTo("init schema");
        assertThat(appliedMigrations[0].getState().isApplied()).isTrue();

        // V2 verification
        assertThat(appliedMigrations[1].getVersion().getVersion()).isEqualTo("2");
        assertThat(appliedMigrations[1].getDescription()).isEqualTo("workspace roles and invites");
        assertThat(appliedMigrations[1].getState().isApplied()).isTrue();

        // V3 verification
        assertThat(appliedMigrations[2].getVersion().getVersion()).isEqualTo("3");
        assertThat(appliedMigrations[2].getDescription()).isEqualTo("channels and dms");
        assertThat(appliedMigrations[2].getState().isApplied()).isTrue();

        // Current schema version must be 3
        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(current.getVersion().getVersion()).isEqualTo("3");
    }
}
