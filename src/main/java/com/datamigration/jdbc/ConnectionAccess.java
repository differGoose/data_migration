package com.datamigration.jdbc;
import java.sql.Connection;
import java.sql.SQLException;
/** Acquired connections belong to the caller; never register as the host DataSource. */
public interface ConnectionAccess {
    Connection openSourceConnection() throws SQLException;
    Connection openTargetConnection() throws SQLException;
}
