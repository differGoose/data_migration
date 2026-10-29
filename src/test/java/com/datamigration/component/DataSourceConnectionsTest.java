package com.datamigration.component;

import com.datamigration.jdbc.DataSourceConnections;
import org.junit.jupiter.api.Test;
import java.sql.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataSourceConnectionsTest {
    @Test void returnsBorrowedConnectionAfterRollbackAndRestoresHostSettings() throws Exception {
        DataSource ds=mock(DataSource.class); Connection raw=mock(Connection.class);
        when(ds.getConnection()).thenReturn(raw); when(raw.getAutoCommit()).thenReturn(true);
        when(raw.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
        var connections=new DataSourceConnections(ds,ds);
        var connection=connections.openSourceConnection();
        verify(raw).setAutoCommit(false); verify(raw).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        SQLException driverFailure=new SQLException("statement failure");
        when(raw.prepareStatement("bad")).thenThrow(driverFailure);
        assertThatThrownBy(() -> connection.prepareStatement("bad")).isSameAs(driverFailure);
        connection.close(); connection.close();
        var order=inOrder(raw);
        order.verify(raw).rollback(); order.verify(raw).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        order.verify(raw).setAutoCommit(true); order.verify(raw).close();
        verify(raw,times(1)).close();
    }
    @Test void rollbackFailureNeverEnablesAutoCommitAndStillCloses() throws Exception {
        DataSource ds=mock(DataSource.class); Connection raw=mock(Connection.class);
        when(ds.getConnection()).thenReturn(raw); when(raw.getAutoCommit()).thenReturn(true);
        doThrow(new SQLException("rollback failed")).when(raw).rollback();
        var c=new DataSourceConnections(ds,ds).openTargetConnection();
        assertThatThrownBy(c::close).isInstanceOf(SQLException.class);
        verify(raw,never()).setAutoCommit(true); verify(raw).close();
    }
    @Test void acquisitionAndInitializationFailuresDoNotLeakSecrets() throws Exception {
        DataSource ds=mock(DataSource.class); Connection raw=mock(Connection.class);
        var access=new DataSourceConnections(ds,ds);
        when(ds.getConnection()).thenThrow(new SQLException("password=private", "08006", 42));
        var failure=catchThrowableOfType(access::openSourceConnection,SQLException.class);
        assertThat((Throwable)failure).hasMessage("Cannot borrow migration connection").hasNoCause();
        assertThat(failure.getSQLState()).isEqualTo("08006");
        assertThat(failure.getErrorCode()).isEqualTo(42);
        doReturn(raw).when(ds).getConnection();
        when(raw.getAutoCommit()).thenThrow(new SQLException("password=private"));
        doThrow(new SQLException("close failed")).when(raw).close();
        assertThatThrownBy(access::openTargetConnection).hasMessage("Cannot initialize migration connection").hasNoCause();
        verify(raw).close();
        doThrow(new IllegalStateException("password=private")).when(ds).getConnection();
        assertThatThrownBy(access::openSourceConnection).hasMessage("Cannot borrow migration connection").hasNoCause();
    }
    Connection endpoint(String url,String user,String schema) throws Exception {
        var raw=mock(Connection.class); var metadata=mock(DatabaseMetaData.class);
        when(raw.getMetaData()).thenReturn(metadata); when(metadata.getURL()).thenReturn(url);
        when(metadata.getUserName()).thenReturn(user); when(raw.getSchema()).thenReturn(schema);
        return raw;
    }
    @Test void identityIgnoresRotatingSecretsButIncludesDatabaseUserAndSchema() throws Exception {
        var ds=mock(DataSource.class);
        when(ds.getConnection()).thenAnswer(inv -> endpoint("jdbc:postgresql://db/database?password=old&token=old","user","public"));
        var access=new DataSourceConnections(ds,ds); String initial=access.identity();
        assertThat(initial).hasSize(64).doesNotContain("jdbc", "old");
        when(ds.getConnection()).thenAnswer(inv -> endpoint("jdbc:postgresql://db/database?password=new&token=new","user","public"));
        assertThat(access.identity()).isEqualTo(initial);
        when(ds.getConnection()).thenAnswer(inv -> endpoint("jdbc:postgresql://db/database","other","public"));
        assertThat(access.identity()).isNotEqualTo(initial);
        when(ds.getConnection()).thenAnswer(inv -> endpoint("jdbc:postgresql://db/database","user","other_schema"));
        assertThat(access.identity()).isNotEqualTo(initial);
        when(ds.getConnection()).thenAnswer(inv -> endpoint("jdbc:postgresql://db/other_database","user","public"));
        assertThat(access.identity()).isNotEqualTo(initial);
    }
    @Test void missingMetadataIdentityFailsClearly() throws Exception {
        for (String[] identity:new String[][]{{null,"user"},{"not-jdbc","user"},{"jdbc:h2:mem:x",null},{"jdbc:h2:mem:x",""}}) {
            var ds=mock(DataSource.class);
            when(ds.getConnection()).thenAnswer(inv -> endpoint(identity[0],identity[1],"public"));
            assertThatThrownBy(() -> new DataSourceConnections(ds,ds).identity())
                .hasMessage("Cannot establish stable migration endpoint identity").hasNoCause();
        }
    }
}
