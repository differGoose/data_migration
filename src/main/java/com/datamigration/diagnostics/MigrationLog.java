package com.datamigration.diagnostics;

import com.datamigration.execution.SqlErrors;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Internal diagnostic context. Never render exceptions, SQL, configuration values or JDBC metadata. */
public final class MigrationLog {
    private static final Logger LOG = LoggerFactory.getLogger("com.datamigration");
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final Map<String, String> SQL_CATEGORIES = Map.ofEntries(
            Map.entry("28", "AUTHENTICATION_FAILURE"), Map.entry("08", "CONNECTION_FAILURE"),
            Map.entry("40", "TRANSACTION_CONFLICT"), Map.entry("22", "DATA_ERROR"),
            Map.entry("23", "CONSTRAINT_VIOLATION"), Map.entry("42", "SQL_OR_SCHEMA_ERROR"),
            Map.entry("42P01", "TABLE_NOT_FOUND"), Map.entry("42S02", "TABLE_NOT_FOUND"),
            Map.entry("42102", "TABLE_NOT_FOUND"), Map.entry("42703", "COLUMN_NOT_FOUND"),
            Map.entry("42S22", "COLUMN_NOT_FOUND"), Map.entry("42122", "COLUMN_NOT_FOUND"),
            Map.entry("42601", "SQL_SYNTAX_ERROR"), Map.entry("42000", "SQL_SYNTAX_OR_PERMISSION_ERROR"),
            Map.entry("42501", "PERMISSION_DENIED"), Map.entry("0A", "UNSUPPORTED_FEATURE"),
            Map.entry("P0001", "SKIP_BUDGET_EXCEEDED"), Map.entry("P0004", "SOURCE_COUNT_CHANGED"));
    private static final Map<Integer, String> VENDOR_CATEGORIES = Map.of(
            1017, "AUTHENTICATION_FAILURE", 1031, "PERMISSION_DENIED", 942, "TABLE_NOT_FOUND",
            904, "COLUMN_NOT_FOUND", 12514, "CONNECTION_FAILURE", 12541, "CONNECTION_FAILURE",
            17002, "CONNECTION_FAILURE");
    private static final Set<String> VALIDATION_REASONS = Set.of(
            "Invalid SQL identifier", "Missing target identifier", "Expected table or schema.table",
            "Source SQL must be one SELECT", "Source SQL must be a non-locking SELECT without parameters or sequence advancement",
            "INSERT must address target-table", "Only parameterized single-row INSERT VALUES is supported",
            "INSERT VALUES must contain exactly one row", "INSERT parameter count differs from fields",
            "Cannot parse migration SQL", "Unsupported primary-key JDBC type", "Ambiguous source column",
            "INSERT parameter metadata differs from fields", "Primary-key contains nulls",
            "Primary-key is not unique in SELECT results", "Source row count changed",
            "Audit is required for durable recovery", "No enabled table tasks", "Invalid job-id",
            "Keyless tables require one worker", "Duplicate target table", "Missing data-migration configuration",
            "Use either environment or configLocations/arguments", "Declare positive source and target migration capacity",
            "Only increment=1 non-cycling sequences supported", "Sequence not found", "Sequence exhausted",
            "Sequence ID exceeds BIGINT", "BLOB exceeds max-lob-size", "CLOB exceeds max-lob-size in UTF-16 units",
            "Field exceeds max-lob-size", "Source batch changed", "Per-table skip budget exceeded",
            "Migration engine is closed", "Migration runner is closed", "Migration service is closed",
            "A migration is already running", "A migration is already running in this JVM",
            "Migration stopped before all tasks completed", "Migration interrupted", "Migration capacity must be positive",
            "Unsupported target database; expected H2 or PostgreSQL");

    private MigrationLog() { }

    private static final class Trace {
        final UUID id = UUID.randomUUID();
        final Set<String> connected = ConcurrentHashMap.newKeySet();
        final Map<String, String> paths = new ConcurrentHashMap<>();
    }
    private static final class FailureHolder { String detail; }
    private static final class Frame {
        final Trace trace;
        final FailureHolder failure;
        String job = "-", task = "-", taskPath = "data-migration", stage = "INVOCATION", endpoint = "-", property = "-";
        String propertyKey = "";
        long batch = -1;
        Frame(Trace trace, FailureHolder failure) { this.trace = trace; this.failure = failure; }
        Frame copy(boolean independentFailure) {
            var copy = new Frame(trace, independentFailure ? new FailureHolder() : failure);
            copy.job = job; copy.task = task; copy.taskPath = taskPath; copy.stage = stage;
            copy.endpoint = endpoint; copy.property = property; copy.propertyKey = propertyKey; copy.batch = batch;
            return copy;
        }
    }
    public static final class Scope implements AutoCloseable {
        private final Frame previous = CURRENT.get();
        private Scope(Frame frame) { CURRENT.set(frame); }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
    private static Frame frame() {
        var frame = CURRENT.get();
        return frame == null ? new Frame(new Trace(), new FailureHolder()) : frame;
    }
    public static Scope invocation() { return new Scope(frame().copy(false)); }
    public static Scope phase(String stage, String endpoint, String property) {
        var scope = new Scope(frame().copy(false)); stage(stage, endpoint, property); return scope;
    }
    public static Scope table(String job, String task, int index) {
        var frame = frame().copy(true);
        frame.job = job; frame.task = task;
        frame.taskPath = frame.trace.paths.getOrDefault(task, "data-migration.tables[" + index + "]");
        frame.property = frame.taskPath; frame.propertyKey = ""; frame.stage = "TABLE_START";
        return new Scope(frame);
    }
    public static Scope batch(long batch) {
        var frame = frame().copy(false); frame.batch = batch; frame.stage = "BATCH_START"; return new Scope(frame);
    }
    public static <T> Callable<T> wrap(Callable<T> work) {
        var captured = frame().copy(true);
        return () -> { try (var scope = new Scope(captured)) { return work.call(); } };
    }
    public static final class Pending<T> {
        private final Frame diagnostic;
        private final Future<T> future;
        private Pending(Frame diagnostic, Future<T> future) { this.diagnostic = diagnostic; this.future = future; }
        public T get() throws InterruptedException, ExecutionException {
            try { return future.get(); }
            catch (ExecutionException failure) {
                var parent = CURRENT.get();
                if (parent != null && (parent.failure.detail == null
                        || failure.getCause() instanceof com.datamigration.execution.CommitUnknownException))
                    parent.failure.detail = diagnostic.failure.detail;
                throw failure;
            }
        }
    }
    public static <T> Pending<T> submit(ExecutorService workers, Callable<T> work) {
        var captured = frame().copy(true);
        Future<T> future = workers.submit(() -> {
            try (var scope = new Scope(captured)) { return work.call(); }
        });
        return new Pending<>(captured, future);
    }
    public static UUID runId() { return frame().trace.id; }
    public static void job(String job) { frame().job = job; }
    public static void taskPath(String task, int index) { frame().trace.paths.put(task, "data-migration.tables[" + index + "]"); }
    public static void stage(String stage, String endpoint, String property) {
        var frame = CURRENT.get();
        if (frame == null) return;
        frame.stage = stage; frame.endpoint = endpoint;
        if (property.equals(frame.propertyKey)) return;
        frame.propertyKey = property;
        frame.property = property.startsWith("data-migration") || property.startsWith("spring.") || property.equals("-")
                ? property : frame.taskPath + "." + property;
    }
    public static void resetFailure() { frame().failure.detail = null; }
    public static void remember(Throwable failure) {
        var frame = CURRENT.get();
        if (frame != null && frame.failure.detail == null) frame.failure.detail = detail(frame, failure);
    }
    public static void connecting(String endpoint) {
        var frame = frame();
        if (frame.trace.connected.add(frame.task + ":" + endpoint + ":start")) info("Opening migration connection", "endpoint=" + endpoint);
    }
    public static void connected(String endpoint) {
        var frame = frame();
        if (frame.trace.connected.add(frame.task + ":" + endpoint)) info("Database connection ready", "endpoint=" + endpoint);
    }
    public static String safe(Object value) {
        String text = Objects.toString(value, "-").replaceAll("[\\p{Cntrl}\\u2028\\u2029]", "_");
        return text.substring(0, Math.min(300, text.length()));
    }
    private static String context(Frame frame) {
        return "runId=" + frame.trace.id + " job=" + safe(frame.job) + " task=" + safe(frame.task)
                + " batch=" + frame.batch + " stage=" + frame.stage + " endpoint=" + frame.endpoint
                + " property=" + safe(frame.property);
    }
    public static void info(String event, String details) { LOG.info("{} {} {}", event, context(frame()), details); }
    public static void debug(String event, String details) { LOG.debug("{} {} {}", event, context(frame()), details); }
    public static void warn(String event, String details) { LOG.warn("{} {} {}", event, context(frame()), details); }
    public static void error(String event, Throwable failure) {
        var frame = frame();
        if (frame.failure.detail == null) frame.failure.detail = detail(frame, failure);
        LOG.error("{} {}", event, frame.failure.detail);
    }
    public static void secondary(String event, Throwable failure) { LOG.warn("{} {}", event, detail(frame(), failure)); }
    public static String failureDetails() { return Objects.toString(frame().failure.detail, "unclassified"); }

    private static String detail(Frame frame, Throwable failure) {
        String category = frame.stage.startsWith("CONFIG") ? "CONFIGURATION_ERROR" : "UNKNOWN";
        String reason = "See stage and configuration property; verify host DataSource and database permissions";
        SQLException sql = null;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException found) { sql = found; break; }
        }
        if (sql != null) {
            String state = Objects.toString(sql.getSQLState(), "unknown");
            category = VENDOR_CATEGORIES.getOrDefault(sql.getErrorCode(), SQL_CATEGORIES.getOrDefault(state,
                    SQL_CATEGORIES.getOrDefault(state.substring(0, Math.min(2, state.length())), "UNKNOWN")));
            if (sql instanceof SQLTimeoutException) category = "TIMEOUT";
            reason = "Check " + frame.endpoint + " pool capacity/acquisition timeout, connectivity, account permissions and the indicated configuration property";
            if (VALIDATION_REASONS.contains(Objects.toString(sql.getMessage(), ""))) reason = sql.getMessage();
        } else if (failure instanceof org.springframework.boot.context.config.ConfigDataLocationNotFoundException
                || failure instanceof org.springframework.boot.context.config.ConfigDataResourceNotFoundException) {
            category = "CONFIGURATION_RESOURCE_NOT_FOUND";
            reason = "Check configLocations, classpath resources and the process working directory";
        } else if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException) {
            category = "CONFIGURATION_OR_LIFECYCLE";
            String message = Objects.toString(failure.getMessage(), "");
            if (VALIDATION_REASONS.contains(message)) reason = message;
            else if (message.startsWith("Missing source column:")) reason = "Source column missing from query result";
            else if (message.startsWith("Missing or ambiguous primary-key column:")) reason = "Primary-key column missing or ambiguous";
            else if (message.startsWith("Insufficient pool capacity;")) reason = "Declared migration capacity is below the connection budget";
        }
        return context(frame) + " category=" + category + " reason=" + reason + " " + SqlErrors.summary(failure);
    }
}
