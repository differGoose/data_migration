package com.datamigration.audit;

import com.datamigration.api.TableResult;
import java.util.UUID;
/** Accept only sanitized errors, never raw row values or credentials.
 * Implementations must report persistence failures to the caller. */
public interface AuditRepository {
    void record(UUID runId, TableResult result);
}
