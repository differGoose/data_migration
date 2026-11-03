package example;

import com.datamigration.api.MigrationExecution;
import com.datamigration.api.MigrationRunner;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Copy this ONE class into the host's scanned package, then call MigrationUtil's static methods. */
@Configuration(proxyBeanMethods = false)
public class MigrationUtil {
    private static final String CONFIG_LOCATION = "classpath:migration.yml";

    // This reference guards initialization only. Runner handles migration deduplication itself.
    private static final AtomicReference<MigrationRunner> SHARED = new AtomicReference<>();
    private final MigrationRunner owned;

    public MigrationUtil(
            @Qualifier("sourceDataSource") DataSource source,
            @Qualifier("targetDataSource") DataSource target) {
        owned = MigrationRunner.builder()
                .source(source)
                .target(target)
                .configLocations(CONFIG_LOCATION)
                .build();
        if (!SHARED.compareAndSet(null, owned)) {
            owned.close();
            throw new IllegalStateException("MigrationUtil is already initialized by another Spring context");
        }
    }

    @Bean(destroyMethod = "close")
    public MigrationRunner hostMigrationRunner() {
        return owned;
    }

    /** First invocation waits. A duplicate of a running/successful attempt returns its current handle. */
    public static MigrationExecution migrate() {
        return runner().migrate();
    }

    /** Returns immediately; repeats reuse the Runner's existing execution. */
    public static MigrationExecution migrateAsync() {
        return runner().migrateAsync();
    }

    private static MigrationRunner runner() {
        MigrationRunner runner = SHARED.get();
        if (runner == null) {
            throw new IllegalStateException("MigrationUtil is not initialized; load its Spring configuration first");
        }
        return runner;
    }

    @PreDestroy
    public void shutdown() {
        // Also covers context initialization failure before the Runner bean was registered.
        try { owned.close(); }
        finally { SHARED.compareAndSet(owned, null); }
        // Never close HOST DataSources here.
    }
}
