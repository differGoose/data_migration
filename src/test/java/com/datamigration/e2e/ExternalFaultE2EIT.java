package com.datamigration.e2e;

import com.datamigration.support.*;
import java.net.URI;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/** Caller-prepared H2 servers exercise the EXTERNAL-host route without claiming real DB acceptance. */
class ExternalFaultE2EIT {
    @TempDir Path directory;
    Path config(TcpDatabases db,E2ESettings.Scenario scenario,boolean keyless) throws Exception {
        Path yaml=directory.resolve(scenario+".yml");
        String rules;
        try(var input=getClass().getResourceAsStream("/resilience/h2.yml")) {
            String original=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            rules=original.substring(original.indexOf("data-migration:"));
        }
        rules=rules.replace("job-id: local-resilience","job-id: external-"+scenario);
        if(keyless) rules=rules.replace("primary-key: [ID]","primary-key: []\n      mode: CLEAR_AND_REIMPORT");
        int sourcePort=URI.create(db.sourceUrl().substring("jdbc:h2:".length())).getPort();
        int targetPort=URI.create(db.targetUrl().substring("jdbc:h2:".length())).getPort();
        String settings="""
            e2e:
              scenario: SCENARIO
              phase-timeout: 90s
              report-directory: REPORT
              configuration-classes: [com.datamigration.support.HostDataSources]
              trigger-task: customer
              trigger-commit: 1
              source-proxy:
                upstream-host: 127.0.0.1
                upstream-port: SOURCE_PORT
              target-proxy:
                upstream-host: 127.0.0.1
                upstream-port: TARGET_PORT
              validation:
                customer:
                  source-sql: SELECT ID, NAME, AMOUNT FROM CUSTOMER ORDER BY ID
                  target-sql: SELECT ID, NAME, AMOUNT FROM CUSTOMER ORDER BY ID
            host-databases:
              source:
                jdbc-url: SOURCE_URL
              target:
                jdbc-url: TARGET_URL
            """.replace("SCENARIO",scenario.name()).replace("REPORT",directory.resolve("reports").toString().replace('\\','/'))
                .replace("SOURCE_PORT",""+sourcePort).replace("TARGET_PORT",""+targetPort)
                .replace("SOURCE_URL",db.sourceUrl().replace(":"+sourcePort+"/",":${e2e.source-proxy.port}/"))
                .replace("TARGET_URL",db.targetUrl().replace(":"+targetPort+"/",":${e2e.target-proxy.port}/"));
        Files.writeString(yaml,rules+settings); return yaml;
    }
    @ParameterizedTest @EnumSource(value=E2ESettings.Scenario.class,names={"MIGRATION"},mode=EnumSource.Mode.EXCLUDE)
    void externalConfigurationUsesRunnerAndPreservesJobAcrossFaultAndRepeat(E2ESettings.Scenario scenario) throws Exception {
        try(var db=new TcpDatabases(directory.resolve("caller-db"))) {
            db.seed(10_000);
            Path yaml=config(db,scenario,false);
            Path output=DatabaseE2E.runConfigured(yaml.toUri().toString());
            assertThat(Files.readString(output.resolve("result.properties"))).contains("status=PASSED","faultTriggered=true");
            assertThat(Files.readString(output.resolve("manifest.properties"))).contains("job-id=external-"+scenario);
            assertThat(Files.readString(output.resolve("runtime.properties"))).doesNotContain("source-sql","target-sql","password");
            // Another invocation is a completed job, not a fresh job or an empty-target requirement.
            Path repeat=DatabaseE2E.runConfigured(yaml.toUri().toString());
            assertThat(Files.readString(repeat.resolve("result.properties"))).contains("status=PASSED","faultTriggered=false","alreadyCompleted=true");
        }
    }
    @Test void keylessExplicitReimportCanRecoverBeforeCommitOnNonemptyTarget() throws Exception {
        try(var db=new TcpDatabases(directory.resolve("caller-db"))) {
            db.seed(10_000);
            DatabaseFixture.execute(db.targetUrl(),"INSERT INTO CUSTOMER VALUES(99999,'old',1)");
            Path output=DatabaseE2E.runConfigured(config(db,E2ESettings.Scenario.APP_BEFORE_COMMIT,true).toUri().toString());
            assertThat(Files.readString(output.resolve("result.properties"))).contains("status=PASSED","faultTriggered=true");
        }
    }
}
