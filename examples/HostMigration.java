package example;

import com.datamigration.api.MigrationExecution;
import com.datamigration.api.MigrationRunner;
import javax.sql.DataSource;

/** Keep one instance in the host. The host owns the supplied DataSources. */
public final class HostMigration implements AutoCloseable {
    private final MigrationRunner runner;
    public HostMigration(DataSource source, int sourceCapacity, DataSource target, int targetCapacity,
            String... configurationLocations) {
        runner = MigrationRunner.builder().source(source, sourceCapacity).target(target, targetCapacity)
                .configLocations(configurationLocations).build();
    }
    public MigrationExecution migrate() { return runner.migrate(); }
    public MigrationExecution migrateAsync() { return runner.migrateAsync(); }
    @Override public void close() { runner.close(); }
}
