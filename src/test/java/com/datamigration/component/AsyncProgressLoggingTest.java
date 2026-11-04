package com.datamigration.component;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.datamigration.api.*;
import com.datamigration.execution.*;
import com.datamigration.model.*;
import java.sql.JDBCType;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class AsyncProgressLoggingTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.datamigration");
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final CountDownLatch failedReport = new CountDownLatch(1);
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override protected void append(ILoggingEvent event) {
            String message = event.getFormattedMessage(); messages.add(message);
            if (message.startsWith("Migration attempt finished") && message.contains("status=FAILED finished=true")) failedReport.countDown();
        }
    };
    private Level previous;
    @BeforeEach void capture() { previous = logger.getLevel(); logger.setLevel(Level.INFO); appender.start(); logger.addAppender(appender); }
    @AfterEach void detach() { logger.detachAppender(appender); appender.stop(); logger.setLevel(previous); }
    private MigrationPlan plan() {
        var table = new TableTask("A\nforged", "SELECT 1", "INSERT INTO A VALUES(?)", List.of(new FieldMapping("ID",JDBCType.BIGINT)),
                "A",List.of(),1,TableTask.Mode.APPEND_RESUME,new SequenceSpec(false,null,null),10);
        return new MigrationPlan("progress",new MigrationStrategy(0,0,Duration.ofMillis(1),Duration.ofMillis(1),1,true,"migration_audit",1024,1024),List.of(table));
    }
    private MigrationResult success(MigrationMonitor monitor) {
        return new MigrationResult(monitor.id(),Instant.now(),Instant.now(),List.of(
                new TableResult("A\nforged",Instant.now(),Instant.now(),10,0,0,TableResult.Status.SUCCEEDED,null)));
    }
    @Test void bothMethodsLogSameCommittedSnapshotAndSuccessOnRepeatedCalls() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var migration = new AsyncMigration(monitor -> {
            calls.incrementAndGet(); monitor.plan(plan()); monitor.counted("A\nforged",10);
            monitor.committed("A\nforged",1,4,0); entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return success(monitor);
        })) {
            try {
                var first = migration.start(); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(messages).anyMatch(m -> m.contains("tablesKnown=false") && m.contains("finished=false"));
                messages.clear();
                assertThat(migration.startSync()).isSameAs(first);
                assertThat(migration.start()).isSameAs(first);
                assertThat(messages.stream().filter(m -> m.startsWith("Table migration progress")).count()).isEqualTo(2);
                assertThat(messages).anyMatch(m -> m.contains("task=A_forged") && m.contains("state=RUNNING totalRows=10 committedRows=4 skippedRows=0"));
                assertThat(messages).noneMatch(m -> m.contains("\n") || m.contains("SELECT 1") || m.contains("INSERT INTO"));
                release.countDown(); first.completion().toCompletableFuture().get(5,TimeUnit.SECONDS);
                messages.clear();
                assertThat(migration.startSync()).isNotSameAs(first);
                assertThat(messages).anyMatch(m -> m.contains("status=SUCCEEDED finished=true") && m.contains("committedRows=10"));
                assertThat(messages).anyMatch(m -> m.contains("state=SUCCEEDED totalRows=10 committedRows=10"));
                assertThat(calls.get()).isEqualTo(2);
            } finally { release.countDown(); }
        }
    }
    @Test void synchronousFirstCallWaitsAndCompletionLogsFailedOrSuccessfulOutcome() throws Exception {
        var calls = new AtomicInteger();
        try (var migration = new AsyncMigration(monitor -> {
            monitor.plan(plan());
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("private-host-token");
            return success(monitor);
        })) {
            assertThatThrownBy(migration::startSync).isInstanceOf(IllegalStateException.class);
            assertThat(failedReport.await(5,TimeUnit.SECONDS)).isTrue();
            // The failed attempt is explicitly retried; it is not silently reused as a successful result.
            assertThat(migration.startSync().progress().status()).isEqualTo(MigrationProgress.Status.SUCCEEDED);
        }
        assertThat(messages).anyMatch(m -> m.startsWith("Migration attempt finished") && m.contains("status=FAILED finished=true"));
        assertThat(messages).anyMatch(m -> m.startsWith("Table migration progress") && m.contains("totalRows=unknown"));
        assertThat(messages).anyMatch(m -> m.startsWith("Migration attempt finished") && m.contains("status=SUCCEEDED finished=true"));
        assertThat(messages).noneMatch(m -> m.contains("private-host-token"));
    }
}
