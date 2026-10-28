// Copy into the host application's source tree; this is not a second Maven module.
package example.migrationjob;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class MigrationJobApplication {
    public static void main(String[] args) {
        // Configure DATA_MIGRATION_RUN_ON_STARTUP=true. Startup failures remain uncaught.
        try (var context = new SpringApplicationBuilder(MigrationJobApplication.class)
                .web(WebApplicationType.NONE).run(args)) {
            // ApplicationRunner has completed synchronously before run() returns.
        }
    }
}
