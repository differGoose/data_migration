package com.datamigration.jdbc;

import com.datamigration.model.TableTask;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
/** Binds ordered fields and executes a batch; caller owns commit/rollback and connection.
 * Rows may contain null values. Never log bound values. */
public interface BatchWriter {
    int[] write(Connection connection, TableTask task, List<List<Object>> rows) throws SQLException;
}
