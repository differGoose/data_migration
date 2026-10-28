package com.datamigration.properties;

import com.datamigration.model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.sql.JDBCType;
import org.springframework.util.unit.DataSize;
import java.time.Duration;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "data-migration", ignoreUnknownFields = false)
public record MigrationProperties(
        @DefaultValue("false") boolean runOnStartup,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,119}") String jobId,
        @NotNull @Valid Endpoint source,
        @NotNull @Valid Endpoint target,
        @DefaultValue @Valid Strategy strategy,
        @NotEmpty List<@NotNull @Valid Table> tables) {

    @AssertTrue(message = "table names must be unique")
    public boolean isUniqueTaskNames() {
        if (tables == null) return true;
        Set<String> names = new HashSet<>();
        return tables.stream().filter(t -> t != null && t.name() != null)
                .allMatch(t -> names.add(t.name().trim().toLowerCase(Locale.ROOT)));
    }

    @AssertTrue(message = "at least one table must be enabled")
    public boolean isActiveTaskPresent() {
        return tables == null || tables.stream().anyMatch(t -> t != null && t.enabled());
    }

    @AssertTrue(message = "enabled tables must have distinct target-table values")
    public boolean isUniqueTargets() {
        if (tables == null) return true;
        Set<String> targets = new HashSet<>();
        return tables.stream().filter(t -> t != null && t.enabled() && t.targetTable() != null)
                .allMatch(t -> targets.add(t.targetTable().toLowerCase(Locale.ROOT)));
    }

    public MigrationPlan toPlan() {
        return new MigrationPlan(jobId, new MigrationStrategy(strategy.fetchSize(), strategy.batchSize(),
                strategy.commitSize(), strategy.skipLimit(), strategy.transientRetryCount(),
                strategy.retryInitialBackoff(), strategy.retryMaxBackoff(), strategy.maxConcurrentTables(),
                strategy.audit().enabled(), strategy.audit().tableName(),
                strategy.maxBatchBytes().toBytes(), strategy.maxLobSize().toBytes()),
                tables.stream().filter(Table::enabled)
                    .map(t -> new TableTask(t.name(), t.selectSql(), t.insertSql(),
                        t.fields().stream().map(f -> new FieldMapping(f.sourceColumn(), f.targetJdbcType())).toList(),
                        t.targetTable(), t.primaryKey(), t.workerCount(), t.mode(),
                        new SequenceSpec(t.sequence().enabled(), t.sequence().name(), t.sequence().targetColumn())))
                    .toList());
    }

    // Do not expose SQL or endpoint credentials through generated record diagnostics.
    @Override public String toString() { return "MigrationProperties[redacted]"; }

    public record Endpoint(
            @NotBlank @Pattern(regexp = "^jdbc:.*", message = "must be a JDBC URL") String jdbcUrl,
            @NotBlank String username,
            @NotNull String password,
            @NotBlank String driverClassName,
            @DefaultValue @Valid Pool pool) {
        @Override public String toString() { return "Endpoint[redacted]"; }
    }

    public record Pool(
            @DefaultValue("4") @Min(1) int maximumPoolSize,
            @DefaultValue("0") @Min(0) int minimumIdle,
            @DefaultValue("30s") Duration connectionTimeout,
            @DefaultValue("10m") Duration idleTimeout,
            @DefaultValue("30m") Duration maxLifetime) {
        @AssertTrue(message = "minimum-idle must not exceed maximum-pool-size")
        public boolean isSizeValid() { return minimumIdle <= maximumPoolSize; }

        @AssertTrue(message = "connection-timeout >= 250ms; idle-timeout is 0 or >= 10s; max-lifetime is 0 or >= 30s")
        public boolean isTimeoutsValid() {
            return connectionTimeout != null && connectionTimeout.compareTo(Duration.ofMillis(250)) >= 0
                    && validOptionalTimeout(idleTimeout, Duration.ofSeconds(10))
                    && validOptionalTimeout(maxLifetime, Duration.ofSeconds(30));
        }
        private static boolean validOptionalTimeout(Duration value, Duration minimum) {
            return value != null && (value.isZero() || value.compareTo(minimum) >= 0);
        }
    }

    public record Strategy(
            @DefaultValue("1000") @Min(1) int fetchSize,
            @DefaultValue("500") @Min(1) int batchSize,
            @DefaultValue("2000") @Min(1) int commitSize,
            @DefaultValue("0") @Min(0) long skipLimit,
            @DefaultValue("3") @Min(0) int transientRetryCount,
            @DefaultValue("1s") Duration retryInitialBackoff,
            @DefaultValue("10s") Duration retryMaxBackoff,
            @DefaultValue("4") @Min(1) int maxConcurrentTables,
            @DefaultValue("16MB") DataSize maxBatchBytes,
            @DefaultValue("32MB") DataSize maxLobSize,
            @DefaultValue @Valid Audit audit) {
        @AssertTrue(message = "byte limits must be positive")
        public boolean isByteLimitsValid() {
            return maxBatchBytes != null && maxBatchBytes.toBytes() > 0
                    && maxLobSize != null && maxLobSize.toBytes() > 0;
        }
        @AssertTrue(message = "retry backoff must be positive and max >= initial")
        public boolean isBackoffValid() {
            return retryInitialBackoff != null && retryMaxBackoff != null
                    && retryInitialBackoff.compareTo(Duration.ZERO) > 0
                    && retryMaxBackoff.compareTo(retryInitialBackoff) >= 0;
        }
    }

    public record Audit(
            @DefaultValue("true") @AssertTrue(message = "audit is required for resumable migration") boolean enabled,
            @DefaultValue("migration_audit")
            @NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")
            String tableName) {}

    public record Table(
            @NotBlank @Size(max = 200) String name,
            @DefaultValue("true") boolean enabled,
            @NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?") String targetTable,
            @DefaultValue List<@Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String> primaryKey,
            @DefaultValue("1") @Min(1) int workerCount,
            @DefaultValue("APPEND_RESUME") TableTask.Mode mode,
            @DefaultValue @Valid Sequence sequence,
            @NotBlank String selectSql,
            @NotBlank String insertSql,
            @NotEmpty List<@NotNull @Valid Field> fields) {
        public Table { primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey); }
        @AssertTrue(message = "keyless tables require worker-count=1; primary-key columns must be unique")
        public boolean isKeyValid() {
            return (!primaryKey.isEmpty() || workerCount == 1)
                    && primaryKey.stream().map(k -> k.toUpperCase(Locale.ROOT)).distinct().count() == primaryKey.size();
        }
        @Override public String toString() { return "Table[SQL redacted]"; }
    }

    public record Sequence(@DefaultValue("false") boolean enabled, String name, String targetColumn) {
        @AssertTrue(message = "enabled sequence requires a simple name and target-column")
        public boolean isValid() {
            return !enabled || (name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")
                    && targetColumn != null && targetColumn.matches("[A-Za-z_][A-Za-z0-9_]*"));
        }
    }

    public record Field(@NotBlank String sourceColumn, @NotNull JDBCType targetJdbcType) {}
}
