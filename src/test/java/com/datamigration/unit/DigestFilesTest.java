package com.datamigration.verification;

import java.nio.file.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Retains the production package to exercise the package-private bounded sort. */
class DigestFilesTest {
    @Test void multiPassMergeSortsMoreThanOneFanInAndDeletesTemporaryDirectory() throws Exception {
        Path directory;
        try (var files = new DigestFiles()) {
            Path input = files.file(); directory = input.getParent();
            try (var write = DigestFiles.writer(input)) {
                for (int i = 140000; i >= 0; i--) { write.write(String.format("%08d", i)); write.newLine(); }
            }
            Path sorted = files.sort(input);
            try (var read = DigestFiles.reader(sorted)) {
                for (int i = 0; i <= 140000; i++) assertThat(read.readLine()).isEqualTo(String.format("%08d", i));
                assertThat(read.readLine()).isNull();
            }
        }
        assertThat(directory).doesNotExist();
    }
    @Test void emptyAndCancelledSortsCleanUp() throws Exception {
        Path directory;
        try (var files = new DigestFiles()) {
            Path empty = files.file(); directory = empty.getParent(); Files.createFile(empty);
            assertThat(Files.size(files.sort(empty))).isZero();
            Path input = files.file(); Files.writeString(input,"hash");
            try {
                Thread.currentThread().interrupt();
                assertThatThrownBy(() -> files.sort(input)).isInstanceOf(SQLException.class);
            } finally { Thread.interrupted(); }
        }
        assertThat(directory).doesNotExist();
    }
}
