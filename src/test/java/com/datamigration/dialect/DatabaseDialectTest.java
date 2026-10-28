package com.datamigration.dialect;

import com.datamigration.model.SequenceSpec;
import com.datamigration.support.DatabaseFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseDialectTest {
    private final SequenceSpec sequence = new SequenceSpec(true,"a_seq","id");

    @Test void h2SequenceAlterRetainsExistingTransactionLocksButCannotBeRolledBack() throws Exception {
        try (var db=new DatabaseFixture(); var owner=db.openTargetConnection(); var contender=db.openTargetConnection()) {
            db.target("CREATE TABLE A(ID BIGINT PRIMARY KEY)"); db.target("INSERT INTO A VALUES(5)");
            db.target("CREATE SEQUENCE A_SEQ START WITH 1");
            try (var lock=owner.createStatement()) { lock.executeQuery("SELECT * FROM A FOR UPDATE").close(); }
            DatabaseDialect.H2.calibrate(owner,"A",sequence);
            try (var query=contender.createStatement()) {
                assertThatThrownBy(() -> query.executeQuery("SELECT * FROM A FOR UPDATE NOWAIT"))
                        .isInstanceOf(SQLException.class);
            }
            owner.rollback();
            assertThat(db.scalar("SELECT NEXT VALUE FOR A_SEQ")).isEqualTo(6);
            try (var query=contender.createStatement();var result=query.executeQuery("SELECT * FROM A FOR UPDATE NOWAIT")) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"INCREMENT BY 2", "CYCLE", "MAXVALUE 2"})
    void unsupportedOrExhaustedH2SequencesFail(String definition) throws Exception {
        try (var db = new DatabaseFixture(); var connection = db.openTargetConnection()) {
            db.target("CREATE TABLE A(ID BIGINT)"); db.target("INSERT INTO A VALUES(5)");
            db.target("CREATE SEQUENCE A_SEQ START WITH 1 " + definition);
            if (definition.equals("MAXVALUE 2")) {
                db.scalar("SELECT NEXT VALUE FOR A_SEQ"); db.scalar("SELECT NEXT VALUE FOR A_SEQ");
            }
            assertThatThrownBy(() -> DatabaseDialect.H2.calibrate(connection,"A",sequence)).isInstanceOf(SQLException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings={"9223372036854775807", "9223372036854775808", "1.5"})
    void unsafeMaximumCannotBeRoundedOrOverflow(String maximum) throws Exception {
        try (var db = new DatabaseFixture(); var connection = db.openTargetConnection()) {
            db.target("CREATE TABLE A(ID DECIMAL(30,1))"); db.target("INSERT INTO A VALUES("+maximum+")");
            assertThatThrownBy(() -> DatabaseDialect.H2.calibrate(connection,"A",sequence)).isInstanceOf(SQLException.class);
        }
    }

    @Test void emptyTableDoesNotConsumeSequence() throws Exception {
        try (var db = new DatabaseFixture(); var connection = db.openTargetConnection()) {
            db.target("CREATE TABLE A(ID BIGINT)"); db.target("CREATE SEQUENCE A_SEQ START WITH 9");
            DatabaseDialect.H2.calibrate(connection,"A",sequence);
            assertThat(db.scalar("SELECT NEXT VALUE FOR A_SEQ")).isEqualTo(9);
        }
    }

    // JDBC contract tests for PostgreSQL. These do not replace a real PostgreSQL integration run.
    @ParameterizedTest @CsvSource({"4,true,true", "5,false,true", "5,true,false", "6,false,false"})
    void postgresWaterlineOnlyAdvances(long last, boolean called, boolean change) throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class);
        var max = mock(ResultSet.class); var settings = mock(ResultSet.class); var current = mock(ResultSet.class);
        var query = mock(PreparedStatement.class); var update = mock(PreparedStatement.class);
        when(connection.getSchema()).thenReturn("public"); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT MAX(id) FROM a")).thenReturn(max);
        when(max.getBigDecimal(1)).thenReturn(BigDecimal.valueOf(5));
        when(connection.prepareStatement(startsWith("SELECT increment_by"))).thenReturn(query);
        when(query.executeQuery()).thenReturn(settings); when(settings.next()).thenReturn(true);
        when(settings.getLong(1)).thenReturn(1L);
        when(statement.executeQuery("SELECT last_value,is_called FROM a_seq")).thenReturn(current);
        when(current.getLong(1)).thenReturn(last); when(current.getBoolean(2)).thenReturn(called);
        when(connection.prepareStatement(startsWith("SELECT setval"))).thenReturn(update);
        when(update.executeQuery()).thenReturn(mock(ResultSet.class));
        DatabaseDialect.POSTGRESQL.calibrate(connection,"a",sequence);
        if (change) { verify(update).setLong(2,5); verify(update).setString(1,"a_seq"); verify(update).executeQuery(); }
        else verifyNoInteractions(update);
    }

    @ParameterizedTest @CsvSource({"false,1,false", "true,2,false", "true,1,true"})
    void postgresMissingCyclingAndNonUnitSequencesAreRejected(boolean exists, long increment, boolean cycle) throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class);
        var max = mock(ResultSet.class); var settings = mock(ResultSet.class); var query = mock(PreparedStatement.class);
        when(connection.getSchema()).thenReturn("public"); when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(max); when(max.getBigDecimal(1)).thenReturn(BigDecimal.ONE);
        when(connection.prepareStatement(anyString())).thenReturn(query); when(query.executeQuery()).thenReturn(settings);
        when(settings.next()).thenReturn(exists); when(settings.getLong(1)).thenReturn(increment); when(settings.getBoolean(2)).thenReturn(cycle);
        assertThatThrownBy(() -> DatabaseDialect.POSTGRESQL.calibrate(connection,"a",sequence)).isInstanceOf(SQLException.class);
        verify(statement,times(1)).executeQuery(anyString());
    }

    @Test void dialectDiscoveryRejectsUnsupportedDatabaseAndCanonicalizesIdentifiers() throws Exception {
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata); when(connection.getSchema()).thenReturn("Public");
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        assertThat(DatabaseDialect.target(connection)).isEqualTo(DatabaseDialect.POSTGRESQL);
        assertThat(DatabaseDialect.POSTGRESQL.canonicalTable(connection,"A")).isEqualTo("public.a");
        assertThat(DatabaseDialect.H2.canonicalTable(connection,"other.A")).isEqualTo("OTHER.A");
        when(metadata.getDatabaseProductName()).thenReturn("unsupported");
        assertThatThrownBy(() -> DatabaseDialect.target(connection)).isInstanceOf(SQLException.class);
    }
}
