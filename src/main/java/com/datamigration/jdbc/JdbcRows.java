package com.datamigration.jdbc;

import com.datamigration.conversion.ValueConverter;
import com.datamigration.diagnostics.MigrationLog;
import com.datamigration.model.*;
import com.datamigration.execution.SqlErrors;
import java.io.*;
import java.sql.*;
import java.util.*;

/** Bounded JDBC batches; streams are consumed before advancing the source cursor. */
public final class JdbcRows {
    public record Counts(long read, long written, long skipped) {}
    @FunctionalInterface public interface Progress { void report(long processed) throws SQLException; }
    private final ValueConverter converter;
    public JdbcRows(ValueConverter converter) { this.converter = converter; }

    public Counts copy(ResultSet source, PreparedStatement target, TableTask task, MigrationStrategy strategy,
                       boolean isolateRows, Progress progress) throws SQLException {
        try { return copyRows(source, target, task, strategy, isolateRows, progress); }
        catch (SQLException | RuntimeException failure) { MigrationLog.remember(failure); throw failure; }
    }
    private Counts copyRows(ResultSet source, PreparedStatement target, TableTask task, MigrationStrategy strategy,
                       boolean isolateRows, Progress progress) throws SQLException {
        int[] columns = columns(source.getMetaData(), task.fields());
        long read = 0, written = 0, skipped = 0, bytes = 0;
        int pending = 0;
        long lastProgress = System.nanoTime();
        while (true) {
            MigrationLog.stage("BATCH_READ", "source", "select-sql");
            if (!source.next()) break;
            if (Thread.currentThread().isInterrupted()) throw new SQLException("Migration interrupted", "57014");
            read++;
            Savepoint savepoint = isolateRows ? target.getConnection().setSavepoint() : null;
            try (Row row = read(source, columns, task, strategy.maxLobSize())) {
                if (row.streamed || isolateRows) {
                    if (pending > 0) { executeBatch(target, pending); pending = 0; bytes = 0; }
                    MigrationLog.stage("BATCH_WRITE", "target", "insert-sql");
                    bind(target, task, row);
                    if (target.executeUpdate() != 1) throw new SQLException("INSERT must affect exactly one row", "P0003");
                    written++;
                } else {
                    if (pending > 0 && bytes + row.bytes > strategy.maxBatchBytes()) {
                        executeBatch(target, pending); pending = 0; bytes = 0;
                    }
                    MigrationLog.stage("BATCH_WRITE", "target", "insert-sql");
                    bind(target, task, row);
                    target.addBatch(); pending++; bytes += row.bytes; written++;
                    if (pending >= task.batchSize() || bytes >= strategy.maxBatchBytes()) {
                        executeBatch(target, pending); pending = 0; bytes = 0;
                    }
                }
            } catch (SQLException exception) {
                if (!isolateRows || !SqlErrors.data(exception)) throw exception;
                MigrationLog.stage("ROW_ROLLBACK", "target", "data-migration.strategy.skip-limit");
                target.getConnection().rollback(savepoint);
                target.clearParameters();
                skipped++;
            } finally {
                if (savepoint != null) target.getConnection().releaseSavepoint(savepoint);
            }
            if (System.nanoTime() - lastProgress >= 5_000_000_000L) {
                progress.report(read);
                lastProgress = System.nanoTime();
            }
        }
        if (pending > 0) executeBatch(target, pending);
        progress.report(read);
        return new Counts(read, written, skipped);
    }

    public static int[] columns(ResultSetMetaData metadata, List<FieldMapping> mappings) throws SQLException {
        int[] columns = new int[mappings.size()];
        for (int i = 0; i < mappings.size(); i++) {
            MigrationLog.stage("FIELD_MAPPING", "source", "fields[" + i + "].source-column");
            for (int col = 1; col <= metadata.getColumnCount(); col++) {
                if (mappings.get(i).sourceColumn().equalsIgnoreCase(metadata.getColumnLabel(col))) {
                    if (columns[i] != 0) throw new IllegalArgumentException("Ambiguous source column");
                    columns[i] = col;
                }
            }
            if (columns[i] == 0) throw new IllegalArgumentException("Missing source column: " + mappings.get(i).sourceColumn());
        }
        return columns;
    }

    private Row read(ResultSet source, int[] columns, TableTask task, long maxLob) throws SQLException {
        Row row = new Row();
        int fieldIndex = -1;
        try {
            for (int i = 0; i < columns.length; i++) {
                fieldIndex = i;
                int col = columns[i];
                JDBCType sourceType = JDBCType.valueOf(source.getMetaData().getColumnType(col));
                JDBCType targetType = task.fields().get(i).targetJdbcType();
                Object value;
                if (sourceType == JDBCType.BLOB) {
                    if (!Set.of(JDBCType.BINARY, JDBCType.VARBINARY, JDBCType.LONGVARBINARY, JDBCType.BLOB).contains(targetType))
                        throw new SQLDataException("BLOB requires a binary target type", "22005");
                    Blob blob = source.getBlob(col);
                    if (blob == null) value = null;
                    else {
                        row.resources.add(blob::free);
                        long size = blob.length();
                        if (size > maxLob) throw new SQLDataException("BLOB exceeds max-lob-size", "22001");
                        InputStream stream = blob.getBinaryStream();
                        row.resources.add(stream);
                        value = new Binary(stream, size);
                        row.streamed = true;
                    }
                } else if (sourceType == JDBCType.CLOB || sourceType == JDBCType.NCLOB) {
                    if (!Set.of(JDBCType.CHAR, JDBCType.VARCHAR, JDBCType.LONGVARCHAR, JDBCType.CLOB,
                            JDBCType.NCHAR, JDBCType.NVARCHAR, JDBCType.LONGNVARCHAR, JDBCType.NCLOB).contains(targetType))
                        throw new SQLDataException("CLOB requires a character target type", "22005");
                    Clob clob = source.getClob(col);
                    if (clob == null) value = null;
                    else {
                        row.resources.add(clob::free);
                        long size = clob.length();
                        if (size > maxLob / 2) throw new SQLDataException("CLOB exceeds max-lob-size in UTF-16 units", "22001");
                        Reader stream = clob.getCharacterStream();
                        row.resources.add(stream);
                        value = new Characters(stream, size);
                        row.streamed = true;
                    }
                } else {
                    Object raw = switch (targetType) {
                        case DATE -> source.getObject(col, java.time.LocalDate.class);
                        case TIME -> source.getObject(col, java.time.LocalTime.class);
                        case TIMESTAMP -> source.getObject(col, java.time.LocalDateTime.class);
                        case TIMESTAMP_WITH_TIMEZONE -> source.getObject(col, java.time.OffsetDateTime.class);
                        case TIME_WITH_TIMEZONE -> source.getObject(col, java.time.OffsetTime.class);
                        default -> source.getObject(col);
                    };
                    value = converter.convert(raw, targetType);
                    long length = value instanceof byte[] binary ? binary.length :
                            value instanceof String text ? text.length() * 2L : 32;
                    if (length > maxLob) throw new SQLDataException("Field exceeds max-lob-size", "22001");
                    row.bytes += length;
                }
                row.values.add(value);
            }
            return row;
        } catch (SQLException | RuntimeException exception) {
            MigrationLog.stage("FIELD_CONVERSION", "source", "fields[" + fieldIndex + "].target-jdbc-type");
            // Remember only terminal errors in copyRows; row isolation may legitimately skip this one.
            try { row.close(); } catch (SQLException close) { exception.addSuppressed(close); }
            throw exception;
        }
    }

    private void bind(PreparedStatement statement, TableTask task, Row row) throws SQLException {
        statement.clearParameters();
        for (int i = 0; i < row.values.size(); i++) {
            Object value = row.values.get(i);
            int index = i + 1;
            if (value == null) statement.setNull(index, task.fields().get(i).targetJdbcType().getVendorTypeNumber());
            else if (value instanceof Binary binary) statement.setBinaryStream(index, binary.stream, binary.length);
            else if (value instanceof Characters characters) statement.setCharacterStream(index, characters.stream, characters.length);
            else statement.setObject(index, value, task.fields().get(i).targetJdbcType().getVendorTypeNumber());
        }
    }

    private static void executeBatch(PreparedStatement target, int expected) throws SQLException {
        MigrationLog.stage("BATCH_WRITE", "target", "insert-sql");
        int[] counts = target.executeBatch();
        target.clearBatch();
        if (counts.length != expected) throw new SQLException("Unexpected batch result length", "P0003");
        for (int count : counts) if (count != 1 && count != Statement.SUCCESS_NO_INFO)
            throw new SQLException("INSERT batch did not write one row per input", "P0003");
    }

    private record Binary(InputStream stream, long length) {}
    private record Characters(Reader stream, long length) {}
    private static final class Row implements AutoCloseable {
        private final List<Object> values = new ArrayList<>();
        private final List<AutoCloseable> resources = new ArrayList<>();
        private boolean streamed;
        private long bytes;
        @Override public void close() throws SQLException {
            SQLException failure = null;
            for (int i = resources.size() - 1; i >= 0; i--) {
                try { resources.get(i).close(); }
                catch (Exception exception) { if (failure == null) failure = new SQLException("Cannot release source LOB", "58000"); }
            }
            if (failure != null) throw failure;
        }
    }
}
