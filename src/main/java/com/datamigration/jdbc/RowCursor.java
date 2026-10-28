package com.datamigration.jdbc;

import java.sql.SQLException;
import java.util.List;
/** Close the statement/result set, but not the caller-owned connection.
 * Values are ordered by field mapping; materialize LOBs before advancing. */
public interface RowCursor extends AutoCloseable {
    boolean next() throws SQLException;
    List<Object> values() throws SQLException;
    @Override void close() throws SQLException;
}
