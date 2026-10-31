package com.datamigration.api;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** A live execution handle. Duplicate calls reuse this handle while running or after success. */
public final class MigrationExecution {
    private final Supplier<MigrationProgress> snapshots;
    private final CompletionStage<MigrationResult> completion;
    public MigrationExecution(Supplier<MigrationProgress> snapshots, CompletionStage<MigrationResult> completion) {
        this.snapshots = java.util.Objects.requireNonNull(snapshots);
        this.completion = java.util.Objects.requireNonNull(completion);
    }
    public MigrationProgress progress() { return snapshots.get(); }
    /** Completion callbacks receive the result or original failure; cancelling a derived future does not cancel migration. */
    public CompletionStage<MigrationResult> completion() { return completion; }
}
