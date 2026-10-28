package com.datamigration.execution;
import java.sql.*;
public final class SqlErrors {
    private SqlErrors() {}
    public static boolean data(SQLException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLNonTransientConnectionException || cause instanceof SQLRecoverableException
                    || cause instanceof SQLTransientConnectionException) return false;
        }
        String state = error.getSQLState();
        return state != null && (state.startsWith("22") || state.equals("23502") || state.equals("23503")
                || state.equals("23505") || state.equals("23513")
                || (state.equals("23514") && error.getErrorCode() != 23514));
    }
    public static boolean transientFailure(SQLException error) {
        String state = error.getSQLState();
        return error instanceof SQLTransientException || error instanceof SQLRecoverableException
                || state != null && (state.startsWith("08") || state.startsWith("40") || state.equals("55P03"));
    }
    public static String summary(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                return "SQLSTATE=" + (state != null && state.matches("[A-Za-z0-9]{5}") ? state : "unknown")
                        + "; vendor=" + sql.getErrorCode();
            }
        }
        return error instanceof IllegalArgumentException ? "INVALID_CONFIGURATION_OR_MAPPING" : "MIGRATION_FAILED";
    }
}
