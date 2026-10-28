package com.datamigration.component;
import com.datamigration.support.*;
import com.datamigration.jdbc.*;

import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcRowsTest {
    private final JdbcRows writer = new JdbcRows(new JdbcValueConverter());
    private TableTask task(JDBCType type) {
        return new TableTask("A", "SELECT V FROM A", "INSERT INTO A VALUES (?)",
                List.of(new FieldMapping("V", type)), "A", List.of(), 1,
                TableTask.Mode.APPEND_RESUME, new SequenceSpec(false, null, null));
    }
    private MigrationStrategy strategy(long bytes, long lob) {
        return new MigrationStrategy(10, 10, 10, 0, 0, Duration.ofMillis(1), Duration.ofMillis(1),
                1, true, "migration_audit", bytes, lob);
    }
    private ResultSet source(JDBCType type, int rows) throws Exception {
        var source = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
        when(source.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("V");
        when(metadata.getColumnType(1)).thenReturn(type.getVendorTypeNumber());
        var index = new java.util.concurrent.atomic.AtomicInteger();
        when(source.next()).thenAnswer(call -> index.getAndIncrement() < rows);
        return source;
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void clobStreamsAreConsumedAndFreedIncludingNull(boolean national) throws Exception {
        var source = source(national ? JDBCType.NCLOB : JDBCType.CLOB, 2);
        var clob = mock(Clob.class); var reader = spy(new StringReader("中文abc"));
        when(clob.length()).thenReturn(5L); when(clob.getCharacterStream()).thenReturn(reader);
        when(source.getClob(1)).thenReturn(clob, (Clob) null);
        var target = mock(PreparedStatement.class);
        when(target.executeUpdate()).thenReturn(1); when(target.executeBatch()).thenReturn(new int[]{1});
        var result = writer.copy(source, target, task(JDBCType.VARCHAR), strategy(100,100), false, n -> {});
        assertThat(result.written()).isEqualTo(2);
        verify(target).setCharacterStream(1, reader, 5L);
        verify(target).setNull(1, Types.VARCHAR);
        verify(reader).close(); verify(clob).free();
    }

    @ParameterizedTest @ValueSource(strings={"BLOB", "CLOB", "VARBINARY", "VARCHAR"})
    void oversizedValuesFailBeforeBindingAndFreeLobs(String kind) throws Exception {
        JDBCType type = JDBCType.valueOf(kind);
        var source = source(type, 1); var target = mock(PreparedStatement.class);
        Blob blob = mock(Blob.class); Clob clob = mock(Clob.class);
        when(blob.length()).thenReturn(101L); when(clob.length()).thenReturn(51L);
        when(source.getBlob(1)).thenReturn(blob); when(source.getClob(1)).thenReturn(clob);
        when(source.getObject(1)).thenReturn(type == JDBCType.VARBINARY ? new byte[101] : "x".repeat(51));
        assertThatThrownBy(() -> writer.copy(source, target, task(type), strategy(100,100), false, n -> {}))
                .isInstanceOf(SQLDataException.class).hasMessageContaining("max-lob-size");
        verifyNoInteractions(target);
        if (type == JDBCType.BLOB) verify(blob).free();
        if (type == JDBCType.CLOB) verify(clob).free();
    }

    @ParameterizedTest @ValueSource(strings={"BLOB", "CLOB"})
    void incompatibleLobMappingIsRejected(String kind) throws Exception {
        var source = source(JDBCType.valueOf(kind), 1);
        assertThatThrownBy(() -> writer.copy(source, mock(PreparedStatement.class), task(JDBCType.BIGINT),
                strategy(100,100), false, n -> {})).isInstanceOf(SQLDataException.class).hasMessageContaining("requires");
    }

    @Test void nullBlobIsBoundWithExplicitTargetType() throws Exception {
        var source = source(JDBCType.BLOB, 1); var target = mock(PreparedStatement.class);
        when(target.executeBatch()).thenReturn(new int[]{Statement.SUCCESS_NO_INFO});
        assertThat(writer.copy(source, target, task(JDBCType.VARBINARY), strategy(100,100), false, n -> {}).written()).isEqualTo(1);
        verify(target).setNull(1, Types.VARBINARY);
    }

    @ParameterizedTest @ValueSource(strings={"DATE", "TIME", "TIMESTAMP", "TIME_WITH_TIMEZONE", "TIMESTAMP_WITH_TIMEZONE"})
    void temporalValuesUseTypedJdbcAccess(String kind) throws Exception {
        JDBCType type = JDBCType.valueOf(kind);
        var source = source(type, 1); var target = mock(PreparedStatement.class);
        Object value = switch(type) {
            case DATE -> LocalDate.of(2024,1,2);
            case TIME -> LocalTime.of(3,4,5);
            case TIMESTAMP -> LocalDateTime.of(2024,1,2,3,4,5);
            case TIME_WITH_TIMEZONE -> OffsetTime.parse("03:04:05+08:00");
            default -> OffsetDateTime.parse("2024-01-02T03:04:05+08:00");
        };
        doReturn(value).when(source).getObject(1, value.getClass());
        when(target.executeBatch()).thenReturn(new int[]{1});
        writer.copy(source,target,task(type),strategy(100,100),false,n -> {});
        verify(target).setObject(1,value,type.getVendorTypeNumber());
    }

    @ParameterizedTest @ValueSource(ints={0,2,-3})
    void driverBatchCountsCannotSilentlyLoseOrMultiplyRows(int count) throws Exception {
        var source = source(JDBCType.INTEGER,1); var target = mock(PreparedStatement.class);
        when(source.getObject(1)).thenReturn(1);
        when(target.executeBatch()).thenReturn(new int[]{count});
        assertThatThrownBy(() -> writer.copy(source,target,task(JDBCType.INTEGER),strategy(100,100),false,n -> {}))
                .isInstanceOf(SQLException.class).hasMessageContaining("one row");
    }

    @Test void byteLimitFlushesBeforeAddingNextRow() throws Exception {
        var source = source(JDBCType.VARCHAR,3); var target = mock(PreparedStatement.class);
        when(source.getObject(1)).thenReturn("1234");
        when(target.executeBatch()).thenReturn(new int[]{1});
        writer.copy(source,target,task(JDBCType.VARCHAR),strategy(10,100),false,n -> {});
        verify(target,times(3)).executeBatch();
    }

    @Test void shortDriverResultIsAnError() throws Exception {
        var source = source(JDBCType.INTEGER,1); var target = mock(PreparedStatement.class);
        when(target.executeBatch()).thenReturn(new int[0]);
        assertThatThrownBy(() -> writer.copy(source,target,task(JDBCType.INTEGER),strategy(100,100),false,n -> {}))
                .isInstanceOf(SQLException.class).hasMessageContaining("length");
    }

    @Test void interruptedWorkerDoesNotBindRows() throws Exception {
        var source = source(JDBCType.INTEGER,1); var target = mock(PreparedStatement.class);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> writer.copy(source,target,task(JDBCType.INTEGER),strategy(100,100),false,n -> {}))
                    .isInstanceOf(SQLException.class).hasMessageContaining("interrupted");
        } finally { Thread.interrupted(); }
        verifyNoInteractions(target);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void mappingMustResolveExactlyOneColumn(boolean duplicate) throws Exception {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn(duplicate ? "V" : "OTHER");
        when(metadata.getColumnLabel(2)).thenReturn(duplicate ? "v" : "ANOTHER");
        assertThatThrownBy(() -> JdbcRows.columns(metadata,task(JDBCType.INTEGER).fields()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(duplicate ? "Ambiguous" : "Missing");
    }

    @Test void allLobResourcesAreReleasedEvenWhenBothCloseOperationsFail() throws Exception {
        var source = source(JDBCType.BLOB,1); var target = mock(PreparedStatement.class);
        var blob = mock(Blob.class); var stream = mock(InputStream.class);
        when(source.getBlob(1)).thenReturn(blob); when(blob.length()).thenReturn(10L);
        when(blob.getBinaryStream()).thenReturn(stream); when(target.executeUpdate()).thenReturn(1);
        doThrow(new IOException("stream close")).when(stream).close();
        doThrow(new SQLException("blob free")).when(blob).free();
        assertThatThrownBy(() -> writer.copy(source,target,task(JDBCType.VARBINARY),strategy(100,100),false,n -> {}))
                .isInstanceOf(SQLException.class).hasMessageContaining("release source LOB");
        verify(stream).close(); verify(blob).free();
    }

    @Test void failureOpeningLobStreamRetainsOriginalExceptionAndCleanupFailure() throws Exception {
        var source = source(JDBCType.BLOB,1); var target = mock(PreparedStatement.class);
        var blob = mock(Blob.class); when(source.getBlob(1)).thenReturn(blob); when(blob.length()).thenReturn(10L);
        when(blob.getBinaryStream()).thenThrow(new SQLException("stream unavailable", "08006"));
        doThrow(new SQLException("free failed")).when(blob).free();
        Throwable failure = catchThrowable(() -> writer.copy(source,target,task(JDBCType.VARBINARY),strategy(100,100),false,n -> {}));
        assertThat(failure).hasMessage("stream unavailable");
        assertThat(failure.getSuppressed()).hasSize(1);
        verifyNoInteractions(target);
    }

    @Test void streamedInsertMustAffectOneRow() throws Exception {
        var source = source(JDBCType.BLOB,1); var target = mock(PreparedStatement.class);
        var blob = mock(Blob.class); when(source.getBlob(1)).thenReturn(blob);
        when(blob.getBinaryStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(target.executeUpdate()).thenReturn(0);
        assertThatThrownBy(() -> writer.copy(source,target,task(JDBCType.BLOB),strategy(100,100),false,n -> {}))
                .isInstanceOf(SQLException.class).hasMessageContaining("exactly one row");
        verify(blob).free();
    }
}
