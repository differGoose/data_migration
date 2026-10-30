package com.datamigration.dialect;

import com.datamigration.model.SequenceSpec;
import com.datamigration.diagnostics.MigrationLog;
import com.datamigration.sql.SqlSupport;
import java.sql.*;

public enum DatabaseDialect {
    H2, POSTGRESQL;

    public static DatabaseDialect target(Connection connection) throws SQLException {
        return switch (connection.getMetaData().getDatabaseProductName()) {
            case "H2" -> H2;
            case "PostgreSQL" -> POSTGRESQL;
            default -> throw new SQLException("Unsupported target database; expected H2 or PostgreSQL", "0A000");
        };
    }

    public String canonicalTable(Connection connection, String table) throws SQLException {
        SqlSupport.table(table);
        String value = table.contains(".") ? table : connection.getSchema() + "." + table;
        return this == H2 ? value.toUpperCase(java.util.Locale.ROOT) : value.toLowerCase(java.util.Locale.ROOT);
    }

    /** Non-transactional sequence effects are finalized after data commit, with idempotent forward-only retries. */
    public void calibrate(Connection connection, String target, SequenceSpec sequence) throws SQLException {
        if (!sequence.enabled()) return;
        SqlSupport.table(sequence.name()); SqlSupport.identifier(sequence.targetColumn());
        MigrationLog.stage("SEQUENCE_FINALIZE", "target", "sequence.target-column");
        Long maximum;
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT MAX(" + sequence.targetColumn() + ") FROM " + SqlSupport.table(target))) {
            result.next();
            java.math.BigDecimal number = result.getBigDecimal(1);
            if (number == null) return;
            try { maximum = number.longValueExact(); }
            catch (ArithmeticException exception) { throw new SQLException("Sequence ID exceeds BIGINT", "22003"); }
        }
        if (maximum == Long.MAX_VALUE) throw new SQLException("Sequence exhausted", "22003");
        MigrationLog.stage("SEQUENCE_FINALIZE", "target", "sequence.name");
        String[] parts = canonicalTable(connection, sequence.name()).split("\\.");
        if (this == H2) {
            try (var query = connection.prepareStatement("SELECT BASE_VALUE,INCREMENT,CYCLE_OPTION FROM INFORMATION_SCHEMA.SEQUENCES" +
                    " WHERE SEQUENCE_SCHEMA=? AND SEQUENCE_NAME=?")) {
                query.setString(1, parts[0]); query.setString(2, parts[1]);
                try (var row = query.executeQuery()) {
                    if (!row.next()) throw new SQLException("Sequence not found", "42S02");
                    if (row.getLong(2) != 1 || !"NO".equals(row.getString(3)))
                        throw new SQLException("Only increment=1 non-cycling sequences supported", "0A000");
                    long next = row.getLong(1);
                    if (row.wasNull()) throw new SQLException("Sequence exhausted", "22003");
                    if (next <= maximum) {
                        try (var statement = connection.createStatement()) {
                            statement.execute("ALTER SEQUENCE " + sequence.name() + " RESTART WITH " + (maximum + 1));
                        }
                    }
                }
            }
        } else {
            try (var query = connection.prepareStatement("SELECT increment_by,cycle FROM pg_sequences WHERE schemaname=? AND sequencename=?")) {
                query.setString(1, parts[0]); query.setString(2, parts[1]);
                try (var row = query.executeQuery()) {
                    if (!row.next()) throw new SQLException("Sequence not found", "42P01");
                    if (row.getLong(1) != 1 || row.getBoolean(2))
                        throw new SQLException("Only increment=1 non-cycling sequences supported", "0A000");
                }
            }
            long last; boolean called;
            try (var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT last_value,is_called FROM " + sequence.name())) {
                row.next(); last = row.getLong(1); called = row.getBoolean(2);
            }
            if (last < maximum || (last == maximum && !called)) {
                try (var statement = connection.prepareStatement("SELECT setval(CAST(? AS regclass),?,true)")) {
                    statement.setString(1, sequence.name()); statement.setLong(2, maximum);
                    statement.executeQuery().close();
                }
            }
        }
    }
}
