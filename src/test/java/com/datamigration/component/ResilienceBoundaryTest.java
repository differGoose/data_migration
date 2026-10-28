package com.datamigration.support;

import java.sql.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ResilienceBoundaryTest {
    @Test void onlyExecutedConfiguredBusinessStatementsTriggerAndRollbackResets() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(raw.prepareStatement(anyString())).thenReturn(statement);
        ResilienceWorker.Boundary boundary = mock(ResilienceWorker.Boundary.class);
        Connection wrapped = ResilienceWorker.wrap(raw, Set.of("INSERT INTO ORDERS VALUES (?)"), boundary, false);
        wrapped.prepareStatement("INSERT INTO AUDIT VALUES (?)").executeUpdate();
        wrapped.commit();
        wrapped.prepareStatement("INSERT INTO ORDERS_ARCHIVE VALUES (?)").executeBatch();
        wrapped.commit();
        wrapped.prepareStatement("INSERT INTO ORDERS VALUES (?)");
        wrapped.commit();
        verifyNoInteractions(boundary);
        wrapped.prepareStatement("INSERT INTO ORDERS VALUES (?)").executeBatch();
        wrapped.rollback();
        wrapped.commit();
        verifyNoInteractions(boundary);
        wrapped.prepareStatement("INSERT INTO ORDERS VALUES (?)").executeBatch();
        wrapped.commit();
        wrapped.commit();
        verify(boundary, times(1)).reached();
    }
}
