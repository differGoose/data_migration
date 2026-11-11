package com.datamigration.resilience;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.datamigration.api.MigrationException;
import com.datamigration.api.MigrationRunner;
import com.datamigration.api.TableResult;
import com.datamigration.support.MigrationAssertions;
import com.datamigration.support.TcpDatabases;
import com.datamigration.support.TcpFaultProxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Cuts the source TCP connection after verification counts pass, then restores
 * connectivity and retries verification on the same runner and checkpoints.
 */
class H2VerificationRecoveryIT {
    @TempDir Path directory;

    @Test
    void sourceOutageDuringContentVerificationPreservesCommittedData() throws Exception {
        try (var databases = new TcpDatabases(directory)) {
            databases.seed(10_000);
            URI upstream = URI.create(databases.sourceUrl().substring("jdbc:h2:".length()));
            try (var proxy = new TcpFaultProxy(upstream.getHost(), upstream.getPort(), 0);
                 var runner = MigrationRunner.builder()
                         .source(new DriverManagerDataSource("jdbc:h2:tcp://127.0.0.1:" + proxy.port()
                                 + upstream.getRawPath(), "sa", ""), 2)
                         .target(new DriverManagerDataSource(databases.targetUrl(), "sa", ""), 2)
                         .configLocations("classpath:recovery.yml")
                         .arguments("--data-migration.job-id=e2e",
                                 "--test.recovery.verification=true")
                         .build()) {
                var disconnected = new AtomicBoolean();
                Logger logger = (Logger) LoggerFactory.getLogger("com.datamigration");
                var fault = new AppenderBase<ILoggingEvent>() {
                    @Override protected void append(ILoggingEvent event) {
                        if (event.getFormattedMessage().startsWith("Content verification started ")
                                && disconnected.compareAndSet(false, true)) {
                            proxy.cut();
                        }
                    }
                };
                fault.start();
                logger.addAppender(fault);
                try {
                    var failure = catchThrowableOfType(runner::migrate, MigrationException.class);
                    assertThat(disconnected).isTrue();
                    assertThat(failure).isNotNull();
                    assertThat(failure.result().tables()).singleElement().satisfies(table -> {
                        assertThat(table.status()).isEqualTo(TableResult.Status.VERIFICATION_ERROR);
                        assertThat(table.committedRows()).isEqualTo(10_000);
                    });
                    MigrationAssertions.assertCompletedDataState(databases, "VERIFICATION_ERROR");
                    MigrationAssertions.assertEquivalent(databases);
                    var batches = MigrationAssertions.businessCheckpoints(databases);
                    assertThat(batches).hasSize(10);

                    proxy.restore();
                    var resumed = runner.migrate().completion().toCompletableFuture().join();
                    assertThat(resumed.runId()).isNotEqualTo(failure.result().runId());
                    MigrationAssertions.assertCompletedDataState(databases, "SUCCEEDED");
                    MigrationAssertions.assertEquivalent(databases);
                    assertThat(MigrationAssertions.businessCheckpoints(databases)).isEqualTo(batches);
                } finally {
                    logger.detachAppender(fault);
                    fault.stop();
                }
            }
        }
    }
}
