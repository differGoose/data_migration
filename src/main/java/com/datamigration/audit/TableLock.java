package com.datamigration.audit;

import com.datamigration.jdbc.ConnectionAccess;
import java.sql.*;

/** Dedicated transaction holds a lock row across coordinator commits, on both H2 and PostgreSQL. */
public final class TableLock implements AutoCloseable {
    private final Connection guard;
    private final CheckpointStore ownership;
    private final String token;
    private TableLock(Connection guard, CheckpointStore ownership, String token) {
        this.guard = guard; this.ownership = ownership; this.token = token;
    }

    public static TableLock acquire(ConnectionAccess connections, Connection control, String audit, String canonicalTarget) throws SQLException {
        var locks = new CheckpointStore(audit, "__table_lock__", canonicalTarget);
        if (locks.find(control, 0, false) == null) {
            Savepoint savepoint = control.setSavepoint();
            try { locks.create(control, 0, canonicalTarget, "LOCK", null, null, 0); }
            catch (SQLException exception) {
                control.rollback(savepoint);
                if (!"23505".equals(exception.getSQLState())) throw exception;
            }
        }
        control.commit();
        Connection guard = connections.openTargetConnection();
        try {
            try (var statement = CheckpointStore.statement(guard, "SELECT batch_no FROM " + audit +
                    " WHERE job_id='__table_lock__' AND task_name=? AND batch_no=0 FOR UPDATE NOWAIT")) {
                statement.setString(1, canonicalTarget);
                try (var row = statement.executeQuery()) {
                    if (!row.next()) throw new SQLException("Missing table execution lock", "P0002");
                }
            }
            String token = java.util.UUID.randomUUID().toString();
            if (locks.find(control, 1, false) == null)
                locks.create(control, 1, canonicalTarget, token, null, null, 0);
            else {
                try (var update = CheckpointStore.statement(control, "UPDATE " + audit +
                        " SET fingerprint=? WHERE job_id='__table_lock__' AND task_name=? AND batch_no=1")) {
                    update.setString(1, token); update.setString(2, canonicalTarget);
                    update.executeUpdate();
                }
            }
            control.commit(); // Fence stale workers BEFORE waiting for their open transactions.
            // Coordinators may be clearing a target before any batch rows exist. Drain their
            // summary locks separately; keeping those locks while draining batches would invert
            // the worker's batch-then-summary lock order and could deadlock.
            try (var drain = CheckpointStore.statement(control, "SELECT job_id,task_name FROM " + audit +
                    " WHERE target_table=? AND job_id<>'__table_lock__' AND batch_no=0" +
                    " ORDER BY job_id,task_name FOR UPDATE")) {
                drain.setString(1, canonicalTarget);
                try (var records = drain.executeQuery()) { while (records.next()) { } }
            }
            control.commit();
            try (var drain = CheckpointStore.statement(control, "SELECT job_id,task_name,batch_no FROM " + audit +
                    " WHERE target_table=? AND job_id<>'__table_lock__' AND batch_no>0" +
                    " ORDER BY job_id,task_name,batch_no FOR UPDATE")) {
                drain.setString(1, canonicalTarget);
                try (var records = drain.executeQuery()) {
                    while (records.next()) { /* Acquire all existing batch locks before reset/recovery. */ }
                }
            }
            control.commit();
            return new TableLock(guard, locks, token);
        } catch (SQLException exception) {
            guard.close();
            throw exception;
        }
    }

    public void check() throws SQLException {
        if (!guard.isValid(2)) throw new SQLException("Table execution lock connection lost", "08006");
        try (var query = guard.createStatement()) { query.executeQuery("SELECT 1").close(); }
    }
    /** Invoke after locking the batch row, before business writes, using READ_COMMITTED isolation. */
    public void assertOwner(Connection worker) throws SQLException {
        Checkpoint current = ownership.find(worker, 1, false);
        if (current == null || !token.equals(current.fingerprint()))
            throw new SQLException("Stale table execution owner", "P0005");
    }
    @Override public void close() throws SQLException {
        try { guard.rollback(); } finally { guard.close(); }
    }
}
