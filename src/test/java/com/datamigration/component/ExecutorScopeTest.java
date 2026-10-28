package com.datamigration.component;
import com.datamigration.support.*;
import com.datamigration.execution.*;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class ExecutorScopeTest {
    @Test void normalCloseWaitsForSubmittedWork() {
        var executor = Executors.newSingleThreadExecutor();
        AtomicBoolean finished = new AtomicBoolean();
        try (var scope = new ExecutorScope(executor)) {
            executor.submit(() -> finished.set(true));
        }
        assertThat(finished).isTrue();
        assertThat(executor.isTerminated()).isTrue();
        assertThatThrownBy(() -> executor.submit(() -> {})).isInstanceOf(RejectedExecutionException.class);
    }

    @Test void interruptedCloseCancelsWorkersWaitsForCleanupAndRestoresInterrupt() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var scope = new ExecutorScope(executor);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean();
        executor.submit(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { cleaned.set(true); }
        });
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.currentThread().interrupt();
            scope.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(executor.isTerminated()).isTrue();
            assertThat(cleaned).isTrue();
        } finally {
            Thread.interrupted();
            scope.close();
        }
    }
}
