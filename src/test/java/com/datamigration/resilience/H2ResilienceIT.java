package com.datamigration.resilience;
import com.datamigration.support.*;

import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class H2ResilienceIT {
    @ParameterizedTest(name = "{0}")
    @EnumSource(ResilienceSettings.Scenario.class)
    void resumesAfterFault(ResilienceSettings.Scenario scenario) throws Exception {
        ResilienceSuite.run("file:examples/resilience/scenarios.yml,classpath:resilience/h2.yml",
                Map.of("resilience.scenario", scenario.name()),
                Path.of("target", "resilience-reports", scenario + "-" + UUID.randomUUID()));
    }
}
