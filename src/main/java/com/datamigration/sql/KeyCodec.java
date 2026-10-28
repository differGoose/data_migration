package com.datamigration.sql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Decimal and temporal checkpoint values are strings, never JSON floating point. */
public final class KeyCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private KeyCodec() {}
    public record Value(int jdbcType, String text) {
        public void bind(PreparedStatement statement, int index) throws SQLException {
            Object value = switch (JDBCType.valueOf(jdbcType)) {
                case TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL, NUMERIC, REAL, FLOAT, DOUBLE -> new BigDecimal(text);
                case DATE -> LocalDate.parse(text);
                case TIME -> LocalTime.parse(text);
                case TIMESTAMP -> LocalDateTime.parse(text);
                case TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.parse(text);
                case TIME_WITH_TIMEZONE -> OffsetTime.parse(text);
                case BOOLEAN, BIT -> Boolean.valueOf(text);
                default -> text;
            };
            statement.setObject(index, value, jdbcType);
        }
    }
    public static List<Value> read(ResultSet result, int count) throws SQLException {
        List<Value> values = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            int type = result.getMetaData().getColumnType(i);
            Object value = switch (JDBCType.valueOf(type)) {
                case DATE -> result.getObject(i, LocalDate.class);
                case TIME -> result.getObject(i, LocalTime.class);
                case TIMESTAMP -> result.getObject(i, LocalDateTime.class);
                case TIMESTAMP_WITH_TIMEZONE -> result.getObject(i, OffsetDateTime.class);
                case TIME_WITH_TIMEZONE -> result.getObject(i, OffsetTime.class);
                default -> result.getObject(i);
            };
            if (value == null) throw new SQLException("Null primary key", "22004");
            values.add(new Value(type, value instanceof BigDecimal n ? n.toPlainString() : value.toString()));
        }
        return List.copyOf(values);
    }
    public static String encode(List<Value> values) {
        if (values == null) return null;
        try { return JSON.writeValueAsString(values); }
        catch (Exception exception) { throw new IllegalArgumentException("Cannot encode checkpoint"); }
    }
    public static List<Value> decode(String json) {
        if (json == null) return null;
        try { return List.copyOf(JSON.readValue(json, new TypeReference<List<Value>>() {})); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid checkpoint encoding"); }
    }
}
