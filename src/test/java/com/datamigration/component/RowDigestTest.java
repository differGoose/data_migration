package com.datamigration.component;
import com.datamigration.support.*;

import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.model.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RowDigestTest {
    private TableTask task() {
        return new TableTask("t", "SELECT ID,N,T,B FROM T ORDER BY ID", "INSERT INTO T VALUES (?,?,?,?)",
                List.of(new FieldMapping("ID", JDBCType.BIGINT), new FieldMapping("N", JDBCType.DECIMAL),
                        new FieldMapping("T", JDBCType.VARCHAR), new FieldMapping("B", JDBCType.VARBINARY)),
                "T", List.of("ID"), 1, TableTask.Mode.APPEND_RESUME, new SequenceSpec(false, null, null));
    }
    @Test void checksumNormalizesDecimalsAndLobsButDetectsNullsChangesAndDuplicates() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
            try (var s = connection.createStatement()) {
                s.execute("CREATE TABLE T(ID BIGINT,N DECIMAL(20,4),T CLOB,B BLOB)");
                s.execute("INSERT INTO T VALUES (1,1.2000,'text',X'010203'),(2,NULL,NULL,NULL)");
            }
            var converter = new JdbcValueConverter();
            var original = RowDigest.read(connection, "SELECT ID,N,T,B FROM T ORDER BY ID", task(), converter, 5);
            var equivalent = RowDigest.read(connection, "SELECT ID,CAST(N AS DECIMAL(20,2)),CAST(T AS VARCHAR),CAST(B AS VARBINARY) FROM T ORDER BY ID", task(), converter, 5);
            assertThat(equivalent).isEqualTo(original);
            try (var s = connection.createStatement()) { s.execute("UPDATE T SET T='' WHERE ID=2"); }
            assertThat(RowDigest.read(connection, task().selectSql(), task(), converter, 5)).isNotEqualTo(original);
            try (var s = connection.createStatement()) { s.execute("INSERT INTO T SELECT * FROM T WHERE ID=1"); }
            assertThat(RowDigest.read(connection, task().selectSql(), task(), converter, 5).rows()).isEqualTo(3);
            assertThatThrownBy(() -> RowDigest.read(connection, "DELETE FROM T", task(), converter, 5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
