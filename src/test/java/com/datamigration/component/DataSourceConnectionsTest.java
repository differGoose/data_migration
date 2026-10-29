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
}
