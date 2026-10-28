package com.datamigration.demo;
import com.datamigration.support.*;

import com.datamigration.support.TcpDatabases;
import java.nio.file.Path;

/** Run this test-source main method in the IDE. Not a JUnit test; never blocks mvn verify. */
public final class H2Demo {
    private H2Demo() {}
    public static void main(String[] args) throws Exception { startDemoDatabases(); }

    public static void startDemoDatabases() throws Exception {
        Path directory = Path.of("target", "h2-demo-" + System.currentTimeMillis());
        try (var databases = new TcpDatabases(directory, false)) {
            databases.seed(10_000);
            databases.seedDocuments();
            System.out.println("Source: " + databases.sourceUrl());
            System.out.println("Target: " + databases.targetUrl());
            System.out.println("Test credentials: username=sa, password=<empty>");
            System.out.println("Sample YAML: src/test/resources/e2e.yml; replace source/target URLs.");
            System.out.println("Additional DOCUMENTS table: 3MB BLOB, empty BLOB, NULL, and CLOB sample rows.");
            System.out.println("Press Enter to stop the two local H2 servers.");
            System.in.read();
        }
    }
}
