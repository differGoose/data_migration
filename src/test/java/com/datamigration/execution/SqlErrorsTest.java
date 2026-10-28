package com.datamigration.execution;

import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class SqlErrorsTest {
    @Test void onlyExplicitDataErrorsAreSkippable() {
        for (String state : new String[]{"22001","23502","23503","23505","23513","23514"})
            assertThat(SqlErrors.data(new SQLException("private data",state))).isTrue();
        for (String state : new String[]{"42000","08006","40001","23000","P0001"})
            assertThat(SqlErrors.data(new SQLException("private data",state))).isFalse();
        assertThat(SqlErrors.data(new SQLException())).isFalse();
        assertThat(SqlErrors.data(new SQLException("invalid H2 check","23514",23514))).isFalse();
        for (SQLException cause : new SQLException[]{new SQLRecoverableException(),
                new SQLTransientConnectionException(),new SQLNonTransientConnectionException()}) {
            SQLException wrapper=new SQLException("constraint wrapper","23505",cause);
            assertThat(SqlErrors.data(wrapper)).isFalse();
        }
    }
    @Test void transientClassificationDoesNotRetrySyntaxOrPermissions() {
        assertThat(SqlErrors.transientFailure(new SQLTransientException())).isTrue();
        assertThat(SqlErrors.transientFailure(new SQLRecoverableException())).isTrue();
        for (String state : new String[]{"08006","40001","55P03"})
            assertThat(SqlErrors.transientFailure(new SQLException("test",state))).isTrue();
        assertThat(SqlErrors.transientFailure(new SQLException())).isFalse();
        assertThat(SqlErrors.transientFailure(new SQLException("syntax","42000"))).isFalse();
    }
    @Test void summariesNeverIncludeDriverMessagesOrInvalidStates() {
        assertThat(SqlErrors.summary(new RuntimeException(new SQLException("password=secret","23505",7))))
                .isEqualTo("SQLSTATE=23505; vendor=7");
        assertThat(SqlErrors.summary(new SQLException("secret","malicious private data"))).isEqualTo("SQLSTATE=unknown; vendor=0");
        assertThat(SqlErrors.summary(new SQLException("secret"))).isEqualTo("SQLSTATE=unknown; vendor=0");
        assertThat(SqlErrors.summary(new IllegalArgumentException("private SQL"))).isEqualTo("INVALID_CONFIGURATION_OR_MAPPING");
        assertThat(SqlErrors.summary(new RuntimeException("private row"))).isEqualTo("MIGRATION_FAILED");
    }
}
