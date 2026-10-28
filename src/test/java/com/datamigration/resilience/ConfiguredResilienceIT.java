package com.datamigration.resilience;
import com.datamigration.support.*;

import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in only. Ordinary verify never connects to a DEV database. */
@EnabledIfSystemProperty(named = "resilience.config", matches = ".+")
class ConfiguredResilienceIT {
    @Test void configuredEnvironmentRecovers() throws Exception {
        String scenario = System.getProperty("resilience.scenario");
        if (scenario == null || scenario.isBlank())
            throw new IllegalArgumentException("Explicit -Dresilience.scenario is required");
        ResilienceSuite.run(System.getProperty("resilience.config"),
                Map.of("resilience.scenario", ResilienceSettings.Scenario.valueOf(scenario).name()),
                Path.of("target", "resilience-reports", "configured-" + UUID.randomUUID()));
    }
}
