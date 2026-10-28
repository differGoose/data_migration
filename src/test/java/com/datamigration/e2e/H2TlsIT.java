package com.datamigration.e2e;

import com.datamigration.api.MigrationService;
import com.datamigration.support.TcpDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class H2TlsIT {
    @TempDir Path directory;

    @Test void migratesOverTlsAndRejectsUntrustedCertificate() throws Exception {
        Path good = directory.resolve("server.p12");
        Path wrong = directory.resolve("unrelated.p12");
        certificate(good, "localhost");
        certificate(wrong, "unrelated");
        System.setProperty("javax.net.ssl.keyStore", good.toString());
        System.setProperty("javax.net.ssl.keyStorePassword", "test-password");
        System.setProperty("javax.net.ssl.trustStore", good.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "test-password");
        try (var databases = new TcpDatabases(directory.resolve("db"), true)) {
            databases.seed(10_000);
            try (var context = new SpringApplicationBuilder(H2MigrationIT.Host.class).properties(databases.properties())
                    .run("--spring.config.location=classpath:e2e.yml")) {
                assertThat(context.getBean(MigrationService.class).migrate().tables().getFirst().committedRows()).isEqualTo(10_000);
            }
            H2MigrationIT.assertEquivalent(databases);
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Path log = directory.resolve("untrusted.log");
            Process process = new ProcessBuilder(javaTool("java"), "-Djavax.net.ssl.trustStore=" + wrong,
                    "-Djavax.net.ssl.trustStorePassword=test-password", "-cp", classpath,
                    UntrustedClient.class.getName(), databases.sourceUrl()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            try {
                assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isNotZero();
                assertThat(new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8)).contains("PKIX");
            } finally { process.destroyForcibly(); }
        } finally {
            for (String key : List.of("javax.net.ssl.keyStore", "javax.net.ssl.keyStorePassword",
                    "javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword")) System.clearProperty(key);
        }
    }

    private void certificate(Path path, String name) throws Exception {
        Path log = directory.resolve(name + ".log");
        Process process = new ProcessBuilder(javaTool("keytool"), "-genkeypair", "-alias", "test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", path.toString(), "-storepass", "test-password", "-keypass", "test-password",
                "-dname", "CN=" + name, "-ext", "SAN=dns:" + name + ",ip:127.0.0.1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8)).isZero();
        } finally { process.destroyForcibly(); }
    }
    private String javaTool(String name) {
        return Path.of(System.getProperty("java.home"), "bin",
                name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString();
    }
    public static final class UntrustedClient {
        public static void main(String[] args) throws Exception {
            try (var connection = java.sql.DriverManager.getConnection(args[0], "sa", "")) {
                connection.createStatement().executeQuery("SELECT 1").close();
            }
        }
    }
}
