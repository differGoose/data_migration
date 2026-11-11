package com.datamigration.verification;

import com.datamigration.conversion.ValueConverter;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.diagnostics.MigrationLog;
import com.datamigration.jdbc.JdbcRows;
import com.datamigration.model.TableTask;
import com.datamigration.sql.SqlSupport;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Exact-size uniform row sampling with multiset comparisons, independent of result ordering or keys. */
public final class TableVerifier {
    private final ValueConverter converter;
    private final JdbcValueConverter canonicalConverter = new JdbcValueConverter();
    public TableVerifier(ValueConverter converter) { this.converter = converter; }

    public void verify(Connection source, Connection target, String query, TableTask task, long expected) throws SQLException {
        MigrationLog.stage("VERIFICATION_COUNT", "-", "verification");
        MigrationLog.stage("VERIFICATION_COUNT", "source", "select-sql");
        long sourceCount = count(source, "SELECT COUNT(*) FROM (" + query + ") migration_verify");
        MigrationLog.stage("VERIFICATION_COUNT", "target", "verification");
        long targetCount = count(target, "SELECT COUNT(*) FROM " + SqlSupport.table(task.targetTable()));
        MigrationLog.info("Verification counts checked", "sourceRows=" + sourceCount + " targetRows=" + targetCount + " auditedRows=" + expected);
        if (sourceCount != expected || targetCount != expected) throw new VerificationMismatch("VERIFICATION_COUNT_MISMATCH");
        long samples = task.verification().samples(sourceCount);
        MigrationLog.stage("VERIFICATION_SAMPLE", "-", "verification.sample-percent");
        MigrationLog.info("Content verification started", "sampleRows=" + samples + " samplePercent=" + task.verification().samplePercent());
        try (var files = new DigestFiles()) {
            Path sourceFile = files.file(), targetFile = files.file();
            scan(source, query, task, true, sourceCount, samples, sourceFile);
            String projection = String.join(",", SqlSupport.verificationColumns(task));
            scan(target, "SELECT " + projection + " FROM " + SqlSupport.table(task.targetTable()), task, false, targetCount, 0, targetFile);
            compare(files.sort(sourceFile), files.sort(targetFile));
        } catch (IOException e) { throw new SQLException("Verification temporary storage failed", "HY000"); }
        MigrationLog.info("Content verification succeeded", "sampleRows=" + samples);
    }
    private long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            try (var result = statement.executeQuery(sql)) { result.next(); return result.getLong(1); }
        }
    }
    private void scan(Connection connection, String sql, TableTask task, boolean source, long expected,
                      long samples, Path file) throws SQLException, IOException {
        MigrationLog.stage("VERIFICATION_SAMPLE", source ? "source" : "target", "verification");
        try (var statement = connection.createStatement(); var write = DigestFiles.writer(file)) {
            statement.setFetchSize(task.batchSize()); statement.setQueryTimeout(60);
            try (var result = statement.executeQuery(sql)) {
                int[] columns = source ? JdbcRows.columns(result.getMetaData(), task.fields()) :
                        java.util.stream.IntStream.rangeClosed(1, task.fields().size()).toArray();
                long seen = 0, remaining = samples;
                Random random = new SecureRandom();
                while (result.next()) {
                    DigestFiles.interrupted();
                    if (seen >= expected) throw new VerificationMismatch("VERIFICATION_COUNT_MISMATCH");
                    boolean selected = source && random.nextLong(expected - seen) < remaining;
                    if (selected) remaining--;
                    write.write(hash(result, columns, task, source)); write.write(selected ? '1' : '0'); write.newLine(); seen++;
                }
                if (seen != expected) throw new VerificationMismatch("VERIFICATION_COUNT_MISMATCH");
            }
        }
    }
    private String hash(ResultSet row, int[] columns, TableTask task, boolean source) throws SQLException, IOException {
        MessageDigest digest = digest();
        for (int i = 0; i < columns.length; i++) {
            JDBCType type = task.fields().get(i).targetJdbcType();
            Object value;
            JDBCType sourceType = JDBCType.valueOf(row.getMetaData().getColumnType(columns[i]));
            // Match JdbcRows: source LOBs bypass the converter; scalar source values are converted once.
            if (source && sourceType == JDBCType.BLOB) value = row.getBlob(columns[i]);
            else if (source && (sourceType == JDBCType.CLOB || sourceType == JDBCType.NCLOB)) value = row.getClob(columns[i]);
            else {
                value = switch (type) {
                    case DATE -> row.getObject(columns[i], LocalDate.class);
                    case TIME -> row.getObject(columns[i], LocalTime.class);
                    case TIMESTAMP -> row.getObject(columns[i], LocalDateTime.class);
                    case TIMESTAMP_WITH_TIMEZONE -> row.getObject(columns[i], OffsetDateTime.class);
                    case TIME_WITH_TIMEZONE -> row.getObject(columns[i], OffsetTime.class);
                    default -> row.getObject(columns[i]);
                };
                if (source) value = converter.convert(value, type);
            }
            if (value == null) { digest.update((byte) 0); continue; }
            digest.update((byte) 1);
            byte[] bytes;
            if (type == JDBCType.BLOB || type == JDBCType.BINARY || type == JDBCType.VARBINARY || type == JDBCType.LONGVARBINARY) {
                if (value instanceof Blob blob) {
                    try (var input = blob.getBinaryStream()) { bytes = binary(input); } finally { blob.free(); }
                } else if (value instanceof byte[] data) bytes = digest().digest(data);
                else throw new SQLException("Unsupported verification binary representation", "22005");
            } else if (type == JDBCType.CLOB || type == JDBCType.NCLOB || type == JDBCType.CHAR || type == JDBCType.NCHAR
                    || type == JDBCType.VARCHAR || type == JDBCType.NVARCHAR || type == JDBCType.LONGVARCHAR || type == JDBCType.LONGNVARCHAR) {
                if (value instanceof Clob clob) {
                    try (var input = clob.getCharacterStream()) { bytes = characters(input); } finally { clob.free(); }
                } else bytes = characters(new StringReader(value.toString()));
            } else {
                Object converted = canonicalConverter.convert(value, type);
                String text = converted instanceof BigDecimal n ? n.stripTrailingZeros().toPlainString()
                        : converted instanceof OffsetDateTime t ? t.toInstant().toString()
                        : converted instanceof OffsetTime t ? t.withOffsetSameInstant(ZoneOffset.UTC).toString() : converted.toString();
                bytes = text.getBytes(StandardCharsets.UTF_8);
            }
            // Every non-null field contributes a fixed-size hash: unambiguous framing across types and lengths.
            digest.update(digest().digest(bytes));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private byte[] binary(InputStream input) throws IOException, SQLException {
        var hash = digest(); byte[] buffer = new byte[8192]; int read;
        while ((read = input.read(buffer)) != -1) { DigestFiles.interrupted(); hash.update(buffer, 0, read); }
        return hash.digest();
    }
    private byte[] characters(Reader input) throws IOException, SQLException {
        var hash = digest(); char[] buffer = new char[4096]; int read;
        while ((read = input.read(buffer)) != -1) {
            DigestFiles.interrupted();
            for (int i = 0; i < read; i++) { hash.update((byte) (buffer[i] >>> 8)); hash.update((byte) buffer[i]); }
        }
        return hash.digest();
    }
    private void compare(Path source, Path target) throws IOException, SQLException {
        try (var left = new Groups(source); var right = new Groups(target)) {
            while (left.next()) {
                DigestFiles.interrupted();
                if (!left.selected) continue;
                while (right.key == null || right.key.compareTo(left.key) < 0) if (!right.next()) break;
                if (!left.key.equals(right.key) || left.count != right.count)
                    throw new VerificationMismatch("VERIFICATION_CONTENT_MISMATCH");
            }
        }
    }
    private static final class Groups implements AutoCloseable {
        private final BufferedReader reader;
        private String pending, key;
        private long count;
        private boolean selected;
        Groups(Path path) throws IOException { reader = DigestFiles.reader(path); pending = reader.readLine(); }
        boolean next() throws IOException {
            if (pending == null) return false;
            key = pending.substring(0, 64); count = 0; selected = false;
            do { count++; selected |= pending.charAt(64) == '1'; pending = reader.readLine(); }
            while (pending != null && pending.startsWith(key));
            return true;
        }
        @Override public void close() throws IOException { reader.close(); }
    }
}
