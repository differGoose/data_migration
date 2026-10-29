// Copy into a HOST project with its own Hikari dependency; Oracle/PostgreSQL runtime drivers come from the module.
package example.migrationjob;
import com.datamigration.api.MigrationRunner;
import com.zaxxer.hikari.HikariDataSource;
public final class MigrationJobApplication {
    public static void main(String[] args) {
        try (var source = pool("SOURCE"); var target = pool("TARGET");
             var migration = MigrationRunner.builder().source(source, 4).target(target, 4)
                 .configLocations("file:/run/config/migration.yml").arguments(args).build()) {
            migration.migrate(); // Exceptions propagate: the process exits unsuccessfully.
        }
    }
    private static HikariDataSource pool(String side) {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(System.getenv(side + "_JDBC_URL"));
        ds.setUsername(System.getenv(side + "_USERNAME"));
        ds.setPassword(System.getenv(side + "_PASSWORD"));
        ds.setMaximumPoolSize(4); ds.setMinimumIdle(0); ds.setConnectionTimeout(10000);
        return ds;
    }
}
