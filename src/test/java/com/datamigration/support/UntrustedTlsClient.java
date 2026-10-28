package com.datamigration.support;

import com.datamigration.api.MigrationService;
import com.datamigration.support.TcpDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

public final class UntrustedTlsClient {

        public static void main(String[] args) throws Exception {
            try (var connection = java.sql.DriverManager.getConnection(args[0], "sa", "")) {
                connection.createStatement().executeQuery("SELECT 1").close();
            }
        }
    }
