package com.datamigration.api;

import com.datamigration.properties.MigrationProperties.Endpoint;
import java.sql.SQLException;

/**
 * Called for each new physical connection, not for pooled connection borrowing.
 * Implementations must be thread safe and bound external calls with timeouts.
 * Caching/expiry management belongs to the implementation. Do not log secrets.
 */
@FunctionalInterface
public interface DatabaseCredentialsProvider {
    enum EndpointRole { SOURCE, TARGET }
    DatabaseCredentials resolve(EndpointRole role, Endpoint endpoint) throws SQLException;
}
