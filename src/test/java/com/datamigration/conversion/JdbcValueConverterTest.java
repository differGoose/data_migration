package com.datamigration.conversion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class JdbcValueConverterTest {
    private final JdbcValueConverter converter = new JdbcValueConverter();

    @ParameterizedTest
    @EnumSource(JDBCType.class)
    void nullRemainsNullForEveryJdbcType(JDBCType type) throws Exception {
        assertThat(converter.convert(null, type)).isNull();
    }

    @ParameterizedTest
    @EnumSource(value=JDBCType.class, names={"TINYINT","SMALLINT","INTEGER","BIGINT"})
    void integersRejectFractionAndOverflow(JDBCType type) throws Exception {
        assertThat(((Number) converter.convert("12", type)).longValue()).isEqualTo(12);
        assertThatThrownBy(() -> converter.convert(new BigDecimal("12.5"), type))
                .isInstanceOf(SQLDataException.class);
        assertThatThrownBy(() -> converter.convert(new BigDecimal("99999999999999999999999"), type))
                .isInstanceOf(SQLDataException.class);
    }

    @Test void decimalDoesNotLosePrecision() throws Exception {
        BigDecimal value = new BigDecimal("999999999999999999.123456789");
        assertThat(converter.convert(value, JDBCType.DECIMAL)).isEqualTo(value);
        assertThat(converter.convert(value.toPlainString(), JDBCType.NUMERIC)).isEqualTo(value);
    }

    @Test void temporalValuesRetainTheirSemantics() throws Exception {
        LocalDate date = LocalDate.of(2026,10,28);
        LocalTime time = LocalTime.of(12,34,56);
        LocalDateTime timestamp = date.atTime(time).withNano(123456789);
        assertThat(converter.convert(date, JDBCType.DATE)).isEqualTo(date);
        assertThat(converter.convert(java.sql.Date.valueOf(date), JDBCType.DATE)).isEqualTo(date);
        assertThat(converter.convert(date.toString(), JDBCType.DATE)).isEqualTo(date);
        assertThat(converter.convert(time, JDBCType.TIME)).isEqualTo(time);
        assertThat(converter.convert(Time.valueOf(time), JDBCType.TIME)).isEqualTo(time);
        assertThat(converter.convert(time.toString(), JDBCType.TIME)).isEqualTo(time);
        assertThat(converter.convert(timestamp, JDBCType.TIMESTAMP)).isEqualTo(timestamp);
        assertThat(converter.convert(Timestamp.valueOf(timestamp), JDBCType.TIMESTAMP)).isEqualTo(timestamp);
        assertThat(converter.convert(timestamp.toString(), JDBCType.TIMESTAMP)).isEqualTo(timestamp);
        var offset = timestamp.atOffset(ZoneOffset.ofHours(8));
        assertThat(converter.convert(offset, JDBCType.TIMESTAMP_WITH_TIMEZONE)).isEqualTo(offset);
        assertThat(converter.convert(offset.toString(), JDBCType.TIMESTAMP_WITH_TIMEZONE)).isEqualTo(offset);
        assertThat(converter.convert(offset.toOffsetTime(), JDBCType.TIME_WITH_TIMEZONE)).isEqualTo(offset.toOffsetTime());
        assertThat(converter.convert(offset.toOffsetTime().toString(), JDBCType.TIME_WITH_TIMEZONE)).isEqualTo(offset.toOffsetTime());
        assertThatThrownBy(() -> converter.convert(timestamp, JDBCType.TIMESTAMP_WITH_TIMEZONE))
                .isInstanceOf(SQLDataException.class);
    }

    @Test void booleanBinaryAndFloatingPointRejectAmbiguousValues() throws Exception {
        for (Object value : new Object[]{true, "true", "1"}) assertThat(converter.convert(value,JDBCType.BOOLEAN)).isEqualTo(true);
        for (Object value : new Object[]{false, "false", "0"}) assertThat(converter.convert(value,JDBCType.BIT)).isEqualTo(false);
        assertThatThrownBy(() -> converter.convert("yes",JDBCType.BOOLEAN)).isInstanceOf(SQLDataException.class);
        byte[] binary = {1,2,3};
        assertThat(converter.convert(binary,JDBCType.VARBINARY)).isSameAs(binary);
        assertThatThrownBy(() -> converter.convert("binary",JDBCType.BINARY)).isInstanceOf(SQLDataException.class);
        assertThat(converter.convert("1.5",JDBCType.DOUBLE)).isEqualTo(1.5);
        assertThatThrownBy(() -> converter.convert("Infinity",JDBCType.REAL)).isInstanceOf(SQLDataException.class);
        assertThatThrownBy(() -> converter.convert(new Object(),JDBCType.STRUCT)).isInstanceOf(SQLDataException.class);
        assertThat(converter.convert(123,JDBCType.VARCHAR)).isEqualTo("123");
    }
}
