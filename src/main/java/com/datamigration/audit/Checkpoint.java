package com.datamigration.audit;
import com.datamigration.sql.KeyCodec;
import java.util.List;
public record Checkpoint(long batch, String state, List<KeyCodec.Value> lower, List<KeyCodec.Value> upper,
        long expected, long committed, long skipped, int retries, long total, long processed,
        boolean planningComplete, boolean prepared, boolean dataComplete) {
    public boolean succeeded() { return "SUCCEEDED".equals(state); }
}
