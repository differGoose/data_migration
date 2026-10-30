package com.datamigration.audit;

import com.datamigration.sql.KeyCodec;
import com.datamigration.diagnostics.MigrationLog;
import com.datamigration.sql.SqlSupport;
import java.sql.*;
import java.util.*;

/** All methods use caller-owned transactions; no DDL or implicit commit. */
public final class CheckpointStore {
    private final String table;
    private final String job;
    private final String task;
    public CheckpointStore(String table, String job, String task) {
        this.table = SqlSupport.table(table); this.job = job; this.task = task;
    }
    public String table() { return table; }

    public Checkpoint find(Connection connection, long batch, boolean lock) throws SQLException {
        return inAudit("CHECKPOINT_READ", () -> findScoped(connection, batch, lock));
    }
    private Checkpoint findScoped(Connection connection, long batch, boolean lock) throws SQLException {
        String sql = "SELECT * FROM " + table + " WHERE job_id=? AND task_name=? AND batch_no=?" +
                (lock ? " FOR UPDATE" : "");
        try (var statement = statement(connection, sql)) {
            id(statement, batch);
            try (var result = statement.executeQuery()) { return result.next() ? read(result) : null; }
        }
    }

    public List<Checkpoint> pending(Connection connection, int limit) throws SQLException {
        return inAudit("CHECKPOINT_READ", () -> pendingScoped(connection, limit));
    }
    private List<Checkpoint> pendingScoped(Connection connection, int limit) throws SQLException {
        try (var statement = statement(connection, "SELECT * FROM " + table +
                " WHERE job_id=? AND task_name=? AND batch_no>0 AND state<>'SUCCEEDED' ORDER BY batch_no")) {
            statement.setString(1, job); statement.setString(2, task); statement.setMaxRows(limit);
            try (var result = statement.executeQuery()) {
                List<Checkpoint> rows = new ArrayList<>();
                while (result.next()) rows.add(read(result));
                return rows;
            }
        }
    }

    public Checkpoint last(Connection connection) throws SQLException {
        return inAudit("CHECKPOINT_READ", () -> lastScoped(connection));
    }
    private Checkpoint lastScoped(Connection connection) throws SQLException {
        try (var statement = statement(connection, "SELECT * FROM " + table +
                " WHERE job_id=? AND task_name=? AND batch_no>0 ORDER BY batch_no DESC")) {
            statement.setString(1, job); statement.setString(2, task); statement.setMaxRows(1);
            try (var result = statement.executeQuery()) { return result.next() ? read(result) : null; }
        }
    }

    public void create(Connection connection, long batch, String target,
                       List<KeyCodec.Value> lower, List<KeyCodec.Value> upper, long expected) throws SQLException {
        inAudit("RANGE_PLAN_AUDIT", () -> { createScoped(connection, batch, target, lower, upper, expected); return null; });
    }
    private void createScoped(Connection connection, long batch, String target,
                       List<KeyCodec.Value> lower, List<KeyCodec.Value> upper, long expected) throws SQLException {
        try (var statement = statement(connection, "INSERT INTO " + table +
                " (job_id,task_name,batch_no,target_table,lower_key,upper_key,expected_rows,state)" +
                " VALUES (?,?,?,?,?,?,?,'PLANNED')")) {
            id(statement, batch);
            statement.setString(4, target);
            statement.setString(5, KeyCodec.encode(lower)); statement.setString(6, KeyCodec.encode(upper));
            statement.setLong(7, expected); statement.executeUpdate();
        }
    }

    public void prepared(Connection connection, long total) throws SQLException {
        update(connection, "prepared=TRUE,total_rows=?,state='RUNNING',error_code=NULL", 0, total);
    }
    public void planningComplete(Connection connection) throws SQLException {
        update(connection, "planning_complete=TRUE", 0);
    }
    public void dataComplete(Connection connection) throws SQLException {
        update(connection, "data_complete=TRUE,state='FINALIZING'", 0);
    }
    public void progress(Connection connection, long processed) throws SQLException {
        update(connection, "processed_rows=?", 0, processed);
    }
    public void failure(Connection connection, long batch, String state, String code) throws SQLException {
        update(connection, "state=?,error_code=?", batch, state, code);
    }
    public void attempt(Connection connection, long batch) throws SQLException {
        update(connection, "retries=retries+1,state='RUNNING',error_code=NULL", batch);
    }
    public void state(Connection connection, long batch, String state) throws SQLException {
        update(connection, "state=?,error_code=NULL", batch, state);
    }

    /** Called before business commit on the SAME connection. Locks the table summary to enforce skip budget. */
    public void complete(Connection connection, long batch, long committed, long skipped, long skipLimit) throws SQLException {
        inAudit("AUDIT_UPDATE", () -> { completeScoped(connection, batch, committed, skipped, skipLimit); return null; });
    }
    private void completeScoped(Connection connection, long batch, long committed, long skipped, long skipLimit) throws SQLException {
        Checkpoint summary = find(connection, 0, true);
        if (batch > 0 && summary.skipped() + skipped > skipLimit)
            throw new SQLException("Per-table skip budget exceeded", "P0001");
        if (batch > 0) {
            update(connection, "committed_rows=committed_rows+?,skipped_rows=skipped_rows+?", 0, committed, skipped);
        }
        update(connection, "state='SUCCEEDED',committed_rows=?,skipped_rows=?,processed_rows=?,error_code=NULL", batch,
                committed, skipped, committed + skipped);
    }

    public void reconcile(Connection connection) throws SQLException {
        inAudit("AUDIT_UPDATE", () -> { reconcileScoped(connection); return null; });
    }
    private void reconcileScoped(Connection connection) throws SQLException {
        try (var statement = statement(connection, "SELECT COALESCE(SUM(committed_rows),0),COALESCE(SUM(skipped_rows),0)" +
                " FROM " + table + " WHERE job_id=? AND task_name=? AND batch_no>0 AND state='SUCCEEDED'")) {
            statement.setString(1, job); statement.setString(2, task);
            try (var result = statement.executeQuery()) {
                result.next();
                update(connection, "committed_rows=?,skipped_rows=?", 0, result.getLong(1), result.getLong(2));
            }
        }
    }

    public void check(Connection connection) throws SQLException {
        inAudit("AUDIT_CHECK", () -> { checkScoped(connection); return null; });
    }
    private void checkScoped(Connection connection) throws SQLException {
        try (var statement = statement(connection, "SELECT job_id,task_name,batch_no,state," +
                "prepared,data_complete,planning_complete,lower_key,upper_key FROM " + table + " WHERE 1=0")) {
            statement.executeQuery().close();
        }
    }

    private void update(Connection connection, String assignments, long batch, Object... values) throws SQLException {
        inAudit("AUDIT_UPDATE", () -> { updateScoped(connection, assignments, batch, values); return null; });
    }
    private void updateScoped(Connection connection, String assignments, long batch, Object... values) throws SQLException {
        try (var statement = statement(connection, "UPDATE " + table + " SET " + assignments +
                ",updated_at=CURRENT_TIMESTAMP WHERE job_id=? AND task_name=? AND batch_no=?")) {
            int index = 1;
            for (Object value : values) statement.setObject(index++, value);
            statement.setString(index++, job); statement.setString(index++, task); statement.setLong(index, batch);
            if (statement.executeUpdate() != 1) throw new SQLException("Missing checkpoint record", "P0002");
        }
    }

    @FunctionalInterface private interface AuditWork<T> { T call() throws SQLException; }
    private <T> T inAudit(String stage, AuditWork<T> work) throws SQLException {
        try (var diagnostic = MigrationLog.phase(stage, "target", "data-migration.strategy.audit.table-name")) {
            try { return work.call(); }
            catch (SQLException | RuntimeException failure) { MigrationLog.remember(failure); throw failure; }
        }
    }

    private void id(PreparedStatement statement, long batch) throws SQLException {
        statement.setString(1, job); statement.setString(2, task); statement.setLong(3, batch);
    }
    public static PreparedStatement statement(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(60);
        return statement;
    }
    private Checkpoint read(ResultSet result) throws SQLException {
        return new Checkpoint(result.getLong("batch_no"), result.getString("state"),
                KeyCodec.decode(result.getString("lower_key")), KeyCodec.decode(result.getString("upper_key")),
                result.getLong("expected_rows"), result.getLong("committed_rows"), result.getLong("skipped_rows"),
                result.getInt("retries"), result.getLong("total_rows"), result.getLong("processed_rows"),
                result.getBoolean("planning_complete"), result.getBoolean("prepared"),
                result.getBoolean("data_complete"));
    }
}
