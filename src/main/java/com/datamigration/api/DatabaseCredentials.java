package com.datamigration.api;

/** Credentials for one physical connection. Never log individual accessors. */
public record DatabaseCredentials(String username, String password) {
    @Override public String toString() { return "DatabaseCredentials[redacted]"; }
}
