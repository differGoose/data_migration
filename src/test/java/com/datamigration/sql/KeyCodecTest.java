package com.datamigration.sql;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KeyCodecTest {
    @Test void encodesCompositeKeysWithoutDecimalPrecisionLoss() {
        var keys = List.of(new KeyCodec.Value(Types.DECIMAL,"12345678901234567890.123456789"),
                new KeyCodec.Value(Types.VARCHAR,"引号\"和换行\n"),
                new KeyCodec.Value(Types.TIMESTAMP,"2026-10-28T12:34:56.123456789"));
        assertThat(KeyCodec.decode(KeyCodec.encode(keys))).isEqualTo(keys);
        assertThat(KeyCodec.encode(null)).isNull();
        assertThat(KeyCodec.decode(null)).isNull();
        assertThatThrownBy(() -> KeyCodec.decode("{broken")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyCodec.decode("[null]")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readsAndBindsTypedKeys() throws Exception {
        Object[][] cases = {
            {Types.DECIMAL,new BigDecimal("123.4500")},
            {Types.DATE,LocalDate.of(2026,10,28)},
            {Types.TIME,LocalTime.of(1,2,3)},
            {Types.TIMESTAMP,LocalDateTime.parse("2026-10-28T01:02:03.123456789")},
            {Types.TIMESTAMP_WITH_TIMEZONE,OffsetDateTime.parse("2026-10-28T01:02:03+08:00")},
            {Types.TIME_WITH_TIMEZONE,OffsetTime.parse("01:02:03+08:00")},
            {Types.BOOLEAN,true},
            {Types.VARCHAR,"abc"}
        };
        for (Object[] pair : cases) {
            int type=(int)pair[0]; Object value=pair[1];
            ResultSet result=mock(ResultSet.class);
            ResultSetMetaData metadata=mock(ResultSetMetaData.class);
            when(result.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnType(1)).thenReturn(type);
            when(result.getObject(1)).thenReturn(value);
            doReturn(value).when(result).getObject(eq(1),any(Class.class));
            var values=KeyCodec.read(result,1);
            PreparedStatement insert=mock(PreparedStatement.class);
            KeyCodec.decode(KeyCodec.encode(values)).getFirst().bind(insert,1);
            verify(insert).setObject(1,value,type);
        }
        ResultSet result=mock(ResultSet.class);
        ResultSetMetaData metadata=mock(ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);
        assertThatThrownBy(() -> KeyCodec.read(result,1)).isInstanceOf(SQLException.class).hasMessageContaining("Null primary key");
    }
}
