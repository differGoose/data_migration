package com.datamigration.conversion;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;

public final class JdbcValueConverter implements ValueConverter {
    @Override public Object convert(Object value, JDBCType type) throws SQLException {
        if (value == null) return null;
        try {
            return switch (type) {
                case TINYINT -> decimal(value).byteValueExact();
                case SMALLINT -> decimal(value).shortValueExact();
                case INTEGER -> decimal(value).intValueExact();
                case BIGINT -> decimal(value).longValueExact();
                case NUMERIC, DECIMAL -> decimal(value);
                case CHAR, VARCHAR, LONGVARCHAR, NCHAR, NVARCHAR, LONGNVARCHAR -> value.toString();
                case DATE -> value instanceof java.sql.Date date ? date.toLocalDate() :
                        value instanceof LocalDate date ? date : LocalDate.parse(value.toString());
                case TIMESTAMP -> value instanceof Timestamp time ? time.toLocalDateTime() :
                        value instanceof LocalDateTime time ? time : LocalDateTime.parse(value.toString());
                case TIME -> value instanceof Time time ? time.toLocalTime() :
                        value instanceof LocalTime time ? time : LocalTime.parse(value.toString());
                case TIMESTAMP_WITH_TIMEZONE -> value instanceof OffsetDateTime ? value : OffsetDateTime.parse(value.toString());
                case TIME_WITH_TIMEZONE -> value instanceof OffsetTime ? value : OffsetTime.parse(value.toString());
                case BOOLEAN, BIT -> {
                    if (value instanceof Boolean) yield value;
                    String text = value.toString();
                    if (text.equals("1") || text.equalsIgnoreCase("true")) yield true;
                    if (text.equals("0") || text.equalsIgnoreCase("false")) yield false;
                    throw new IllegalArgumentException();
                }
                case BINARY, VARBINARY, LONGVARBINARY -> {
                    if (!(value instanceof byte[])) throw new IllegalArgumentException();
                    yield value;
                }
                case FLOAT, REAL, DOUBLE -> {
                    double number = Double.parseDouble(value.toString());
                    if (!Double.isFinite(number)) throw new IllegalArgumentException();
                    yield number;
                }
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException exception) {
            throw new SQLDataException("Value cannot be represented by target JDBC type", "22003");
        }
    }
    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal number ? number : new BigDecimal(value.toString());
    }
}
