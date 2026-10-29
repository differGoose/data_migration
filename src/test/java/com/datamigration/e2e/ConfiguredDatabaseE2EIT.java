package com.datamigration.e2e;

import com.datamigration.support.DatabaseE2E;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit opt-in: never connects to real databases in default CI. */
@EnabledIfSystemProperty(named="e2e.config",matches=".+")
class ConfiguredDatabaseE2EIT {
    @Test void configuredExternalDataSources() throws Exception {
        DatabaseE2E.runConfigured(System.getProperty("e2e.config").split(","));
    }
}
