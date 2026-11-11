package com.datamigration.component;

import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.model.*;
import com.datamigration.support.DatabaseFixture;
import com.datamigration.verification.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TableVerifierTest {
    private final TableVerifier verifier = new TableVerifier(new JdbcValueConverter());
    private TableTask task(List<FieldMapping> fields) {
        return new TableTask("A", "SELECT * FROM A", "INSERT INTO A(" + String.join(",", fields.stream().map(FieldMapping::sourceColumn).toList())
                + ") VALUES (" + String.join(",", Collections.nCopies(fields.size(), "?")) + ")", fields, "A", List.of(), 1,
                TableTask.Mode.APPEND_RESUME, new SequenceSpec(false, null, null), 10, new VerificationSpec(true, new BigDecimal("100")));
    }
    @Test void orderDoesNotMatterAndDuplicateMultiplicitiesAreChecked() throws Exception {
        try (var db = new DatabaseFixture(); var source = db.openSourceConnection(); var target = db.openTargetConnection()) {
            db.source("CREATE TABLE A(V VARCHAR)"); db.target("CREATE TABLE A(V VARCHAR)");
            db.source("INSERT INTO A VALUES('one'),('one'),('two'),(NULL)");
            db.target("INSERT INTO A VALUES(NULL),('two'),('one'),('one')");
            var task = task(List.of(new FieldMapping("V", JDBCType.VARCHAR)));
            verifier.verify(source, target, "SELECT * FROM A ORDER BY V DESC", task, 4);
            db.target("UPDATE A SET V='two' WHERE V='one'");
            assertThatThrownBy(() -> verifier.verify(source,target,task.selectSql(),task,4))
                    .isInstanceOf(VerificationMismatch.class).hasMessage("Verification mismatch");
        }
    }
    @Test void scalarAndStreamedLobRepresentationsAreCanonicalAcrossJdbcTypes() throws Exception {
        try (var db = new DatabaseFixture(); var source = db.openSourceConnection(); var target = db.openTargetConnection()) {
            String columns = "(N DECIMAL(10,2),D DATE,T TIMESTAMP,Z TIMESTAMP WITH TIME ZONE,O TIME WITH TIME ZONE,B BOOLEAN,F DOUBLE,C CLOB,X BLOB,E VARCHAR)";
            db.source("CREATE TABLE A" + columns); db.target("CREATE TABLE A" + columns);
            String values = "INSERT INTO A VALUES(100.00,DATE '2024-01-02',TIMESTAMP '2024-01-02 12:30:00',"
                    + "TIMESTAMP WITH TIME ZONE '2024-01-02 12:30:00+02:00',TIME WITH TIME ZONE '12:30:00+02:00',TRUE,1.5,REPEAT('hello',3000),X'00ff',NULL)";
            db.source(values); db.target(values);
            var task = task(List.of(new FieldMapping("N",JDBCType.DECIMAL),new FieldMapping("D",JDBCType.DATE),
                    new FieldMapping("T",JDBCType.TIMESTAMP),new FieldMapping("Z",JDBCType.TIMESTAMP_WITH_TIMEZONE),
                    new FieldMapping("O",JDBCType.TIME_WITH_TIMEZONE),new FieldMapping("B",JDBCType.BOOLEAN),
                    new FieldMapping("F",JDBCType.DOUBLE),new FieldMapping("C",JDBCType.CLOB),
                    new FieldMapping("X",JDBCType.BLOB),new FieldMapping("E",JDBCType.VARCHAR)));
            verifier.verify(source,target,task.selectSql(),task,1);
            db.target("UPDATE A SET C='changed'");
            assertThatThrownBy(() -> verifier.verify(source,target,task.selectSql(),task,1)).isInstanceOf(VerificationMismatch.class);
        }
    }
    @Test void blobAndClobCompareWithBinaryAndStringWithoutDriverObjectIdentity() throws Exception {
        try (var db = new DatabaseFixture(); var source = db.openSourceConnection(); var target = db.openTargetConnection()) {
            db.source("CREATE TABLE A(B BLOB,C CLOB)"); db.target("CREATE TABLE A(B VARBINARY,C VARCHAR)");
            db.source("INSERT INTO A VALUES(X'00ff','hello')"); db.target("INSERT INTO A VALUES(X'00ff','hello')");
            var task = task(List.of(new FieldMapping("B",JDBCType.BLOB), new FieldMapping("C",JDBCType.CLOB)));
            verifier.verify(source,target,"SELECT C,B FROM A",task,1);
            db.target("UPDATE A SET B=X'00fe'");
            assertThatThrownBy(() -> verifier.verify(source,target,task.selectSql(),task,1)).isInstanceOf(VerificationMismatch.class);
        }
    }
    @Test void conversionsAndMultipleFieldsHaveUnambiguousFraming() throws Exception {
        try (var db = new DatabaseFixture(); var source = db.openSourceConnection(); var target = db.openTargetConnection()) {
            db.source("CREATE TABLE A(N VARCHAR,A VARCHAR,B VARCHAR)"); db.target("CREATE TABLE A(N INTEGER,A VARCHAR,B VARCHAR)");
            db.source("INSERT INTO A VALUES('100','ab','c')"); db.target("INSERT INTO A VALUES(100,'ab','c')");
            var task = task(List.of(new FieldMapping("N",JDBCType.INTEGER),new FieldMapping("A",JDBCType.VARCHAR),new FieldMapping("B",JDBCType.VARCHAR)));
            verifier.verify(source,target,task.selectSql(),task,1);
            db.target("UPDATE A SET A='a',B='bc'");
            assertThatThrownBy(() -> verifier.verify(source,target,task.selectSql(),task,1)).isInstanceOf(VerificationMismatch.class);
        }
    }
    @Test void interruptedVerificationDoesNotClassifyAsMismatch() throws Exception {
        try (var db = new DatabaseFixture(); var source = db.openSourceConnection(); var target = db.openTargetConnection()) {
            db.seed("A", 2);
            var task = TableVerificationTest.verified(db.task("A"), "100", true);
            db.target("INSERT INTO A VALUES(1,'row-1'),(2,'row-2')");
            try {
                Thread.currentThread().interrupt();
                assertThatThrownBy(() -> verifier.verify(source,target,task.selectSql(),task,2))
                        .isInstanceOf(SQLException.class).isNotInstanceOf(VerificationMismatch.class);
            } finally { Thread.interrupted(); }
        }
    }
}
