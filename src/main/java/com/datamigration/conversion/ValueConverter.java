package com.datamigration.conversion;

import java.sql.JDBCType;
import java.sql.SQLException;
/** Convert while the source cursor is valid; return null for SQL NULL. */
public interface ValueConverter {
    Object convert(Object sourceValue, JDBCType targetType) throws SQLException;
}
