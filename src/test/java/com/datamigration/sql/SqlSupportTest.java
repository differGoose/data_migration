package com.datamigration.sql;

import com.datamigration.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlSupportTest {
    private TableTask task(String select, String insert) {
        return new TableTask("a",select,insert,List.of(new FieldMapping("ID",JDBCType.BIGINT)),
                "DEST",List.of("ID"),1,TableTask.Mode.APPEND_RESUME,new SequenceSpec(false,null,null));
    }
    @ParameterizedTest
    @ValueSource(strings={"DELETE FROM SRC","SELECT ID FROM SRC; DELETE FROM SRC","SELECT ID INTO NEW_TABLE FROM SRC",
            "SELECT ID FROM SRC FOR UPDATE","SELECT ID FROM SRC WHERE ID=?","SELECT S.NEXTVAL FROM SRC",
            "WITH Q AS (SELECT ID FROM SRC) SELECT * FROM Q","not a valid SQL statement"})
    void rejectsUnsafeOrUnsupportedSourceQueries(String sql) {
        assertThatThrownBy(() -> SqlSupport.query(task(sql,"INSERT INTO DEST VALUES (?)"))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest
    @ValueSource(strings={"DELETE FROM DEST","INSERT INTO OTHER VALUES (?)",
            "INSERT INTO DEST VALUES (?); DELETE FROM DEST","INSERT INTO DEST SELECT ID FROM SRC",
            "INSERT INTO DEST VALUES (?) RETURNING ID","INSERT INTO DEST VALUES (?) ON CONFLICT DO NOTHING",
            "INSERT INTO DEST VALUES (1)","INSERT INTO DEST VALUES (?,?)",
            "INSERT INTO DEST VALUES (?),(1)"})
    void validatesDestinationAndParameterContract(String sql) {
        assertThatThrownBy(() -> SqlSupport.query(task("SELECT ID FROM SRC",sql))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void literalsAndCommentsDoNotMasqueradeAsParametersOrWrites() {
        var query = SqlSupport.query(task("SELECT ID FROM SRC WHERE NAME='DELETE ? '' UPDATE' /* FOR UPDATE */ -- ?\n",
                "INSERT INTO DEST VALUES (?)"));
        assertThat(query).contains("SELECT");
        assertThat(SqlSupport.tokens("SELECT \"odd\"\"alias\", 'a''b', ? -- ?\n /* ? */ ID_2 FROM T"))
                .containsExactly("SELECT","?","ID_2","FROM","T");
        assertThat(SqlSupport.tokens("SELECT ID /* unterminated")).containsExactly("SELECT","ID");
        assertThat(SqlSupport.tokens("SELECT 'unterminated")).containsExactly("SELECT");
    }
    @Test void validatesNamesAndResolvesUnambiguousNonLobKeys() throws Exception {
        for (String value : Arrays.asList(null,"","a-b","a.b.c","a;DELETE","schema.")) {
            assertThatThrownBy(() -> SqlSupport.table(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(SqlSupport.table("public.dest")).isEqualTo("public.dest");
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("ID");
        when(meta.getColumnLabel(2)).thenReturn("NAME");
        when(meta.getColumnType(1)).thenReturn(Types.BIGINT);
        assertThat(SqlSupport.resolveKeys(meta,List.of("id"))).containsExactly("\"ID\"");
        assertThatThrownBy(() -> SqlSupport.resolveKeys(meta,List.of("MISSING"))).isInstanceOf(IllegalArgumentException.class);
        when(meta.getColumnLabel(2)).thenReturn("id");
        when(meta.getColumnType(2)).thenReturn(Types.BIGINT);
        assertThatThrownBy(() -> SqlSupport.resolveKeys(meta,List.of("ID"))).isInstanceOf(IllegalArgumentException.class);
        when(meta.getColumnType(1)).thenReturn(Types.BLOB);
        assertThatThrownBy(() -> SqlSupport.resolveKeys(meta,List.of("ID"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void compositeRangeBindsEveryLexicographicTermInCorrectOrder() throws Exception {
        var lower = List.of(new KeyCodec.Value(Types.BIGINT,"10"),new KeyCodec.Value(Types.INTEGER,"2"));
        var upper = List.of(new KeyCodec.Value(Types.BIGINT,"20"),new KeyCodec.Value(Types.INTEGER,"7"));
        var range = SqlSupport.range("SELECT A,B FROM SRC",List.of("A","B"),lower,upper,true);
        assertThat(range.sql()).contains("(A > ?) OR (A = ? AND B > ?)","(A < ?) OR (A = ? AND B <= ?)","ORDER BY A, B");
        assertThat(range.parameters()).extracting(KeyCodec.Value::text).containsExactly("10","10","2","20","20","7");
        assertThatThrownBy(() -> SqlSupport.range("SELECT A FROM SRC",List.of("A"),lower,null,false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SqlSupport.range("SELECT A FROM SRC",List.of(),null,null,false).sql()).doesNotContain("WHERE","ORDER BY");
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString(),anyInt(),anyInt())).thenReturn(statement);
        assertThat(range.prepare(connection,100)).isSameAs(statement);
        verify(statement).setFetchSize(100);
        doThrow(new SQLException("driver failure")).when(statement).setFetchSize(100);
        assertThatThrownBy(() -> range.prepare(connection,100)).isInstanceOf(SQLException.class);
        verify(statement).close();
    }
}
