package com.datamigration.support;

import com.datamigration.conversion.ValueConverter;
import com.datamigration.model.TableTask;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Ordered streaming checksum, with explicit nulls and length framing. Never persists business values. */
public record RowDigest(long rows, String sha256) {
    public static RowDigest read(Connection connection, String sql, TableTask task, ValueConverter converter,
                                 int timeoutSeconds) throws Exception {
        // Reuse the production SELECT policy, including rejecting multi-statement/mutating queries.
        com.datamigration.sql.SqlSupport.query(new TableTask(task.name(), sql, task.insertSql(), task.fields(),
                task.targetTable(), task.primaryKey(), task.workerCount(), task.mode(), task.sequence()));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (var statement = connection.createStatement()) {
            statement.setFetchSize(500); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery(sql)) {
                if (rows.getMetaData().getColumnCount() != task.fields().size())
                    throw new IllegalArgumentException("Validation query column count must match fields");
                while (rows.next()) {
                    digest.update((byte) 10);
                    for (int i = 0; i < task.fields().size(); i++) {
                        Object value = rows.getObject(i + 1);
                        if (value == null) { digest.update((byte) 0); continue; }
                        digest.update((byte) 1);
                        byte[] bytes;
                        if (value instanceof Blob blob) {
                            try (var stream = blob.getBinaryStream()) { bytes = hash(stream); }
                            finally { blob.free(); }
                        } else if (value instanceof Clob clob) {
                            try (var reader = clob.getCharacterStream()) { bytes = hash(reader); }
                            finally { clob.free(); }
                        } else {
                            var type = task.fields().get(i).targetJdbcType();
                            if (value instanceof byte[] binary) bytes = MessageDigest.getInstance("SHA-256").digest(binary);
                            else if (type == JDBCType.CLOB || type == JDBCType.NCLOB
                                    || type == JDBCType.VARCHAR || type == JDBCType.LONGVARCHAR
                                    || type == JDBCType.NVARCHAR || type == JDBCType.LONGNVARCHAR) {
                                bytes = hash(new StringReader(value.toString()));
                            } else {
                                Object converted = converter.convert(value, type);
                                String canonical = converted instanceof BigDecimal n ? n.stripTrailingZeros().toPlainString()
                                        : converted instanceof java.time.OffsetDateTime t ? t.toInstant().toString() : converted.toString();
                                bytes = canonical.getBytes(StandardCharsets.UTF_8);
                            }
                        }
                        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
                    }
                    count++;
                }
            }
        }
        return new RowDigest(count, HexFormat.of().formatHex(digest.digest()));
    }
    private static byte[] hash(InputStream input) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192]; int size;
        while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
        return digest.digest();
    }
    private static byte[] hash(Reader input) throws Exception {
        // UTF-16 code units avoid surrogate-pair boundary differences between reader implementations.
        var digest = MessageDigest.getInstance("SHA-256"); char[] buffer = new char[4096]; int size;
        while ((size = input.read(buffer)) != -1) for (int i = 0; i < size; i++) {
            digest.update((byte) (buffer[i] >>> 8)); digest.update((byte) buffer[i]);
        }
        return digest.digest();
    }
}
