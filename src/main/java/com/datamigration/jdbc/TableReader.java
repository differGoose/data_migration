package com.datamigration.jdbc;

import com.datamigration.model.TableTask;
import java.sql.Connection;
import java.sql.SQLException;
public interface TableReader {
    RowCursor open(Connection connection, TableTask task, int fetchSize) throws SQLException;
}
