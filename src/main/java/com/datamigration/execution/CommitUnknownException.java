package com.datamigration.execution;
import java.sql.SQLException;
/** Do not replay until the original transaction has been resolved through the ledger lock. */
public final class CommitUnknownException extends SQLException {
    public CommitUnknownException() { super("Commit outcome cannot be established", "08007"); }
}
