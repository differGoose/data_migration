package com.datamigration.verification;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

/** Bounded external merge sort. Temporary files contain row hashes and selection flags only. */
final class DigestFiles implements AutoCloseable {
    private static final int CHUNK = 8192, FAN_IN = 16;
    private final Path directory;
    private int serial;
    DigestFiles() throws IOException { directory = Files.createTempDirectory("migration-verification-"); }
    Path file() { return directory.resolve(Integer.toString(serial++)); }
    static BufferedWriter writer(Path path) throws IOException { return Files.newBufferedWriter(path, StandardCharsets.US_ASCII); }
    static BufferedReader reader(Path path) throws IOException { return Files.newBufferedReader(path, StandardCharsets.US_ASCII); }
    static void interrupted() throws SQLException {
        if (Thread.currentThread().isInterrupted()) throw new SQLException("Verification interrupted", "57014");
    }
    Path sort(Path input) throws IOException, SQLException {
        List<Path> runs = new ArrayList<>();
        try (var read = reader(input)) {
            while (true) {
                interrupted();
                List<String> chunk = new ArrayList<>(CHUNK);
                String line;
                while (chunk.size() < CHUNK && (line = read.readLine()) != null) chunk.add(line);
                if (chunk.isEmpty()) break;
                chunk.sort(String::compareTo);
                Path run = file();
                try (var write = writer(run)) { for (String value : chunk) { write.write(value); write.newLine(); } }
                runs.add(run);
            }
        }
        Files.delete(input);
        if (runs.isEmpty()) { Path empty = file(); Files.createFile(empty); return empty; }
        while (runs.size() > 1) {
            List<Path> next = new ArrayList<>();
            for (int offset = 0; offset < runs.size(); offset += FAN_IN)
                next.add(merge(runs.subList(offset, Math.min(offset + FAN_IN, runs.size()))));
            runs = next;
        }
        return runs.get(0);
    }
    private record Head(String value, int index) { }
    private Path merge(List<Path> inputs) throws IOException, SQLException {
        Path output = file();
        List<BufferedReader> readers = new ArrayList<>();
        try (var write = writer(output)) {
            PriorityQueue<Head> queue = new PriorityQueue<>(Comparator.comparing(Head::value));
            for (Path input : inputs) {
                var read = reader(input); readers.add(read);
                String value = read.readLine();
                if (value != null) queue.add(new Head(value, readers.size() - 1));
            }
            while (!queue.isEmpty()) {
                interrupted();
                Head head = queue.remove(); write.write(head.value()); write.newLine();
                String value = readers.get(head.index()).readLine();
                if (value != null) queue.add(new Head(value, head.index()));
            }
        } finally {
            IOException failure = null;
            for (var read : readers) try { read.close(); } catch (IOException e) { failure = e; }
            if (failure != null) throw failure;
        }
        for (Path input : inputs) Files.delete(input);
        return output;
    }
    @Override public void close() throws IOException {
        IOException failure = null;
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) try { Files.deleteIfExists(file); } catch (IOException e) { failure = e; }
        }
        try { Files.delete(directory); } catch (IOException e) { failure = e; }
        if (failure != null) throw failure;
    }
}
