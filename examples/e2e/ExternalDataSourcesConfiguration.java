// Copy into YOUR test sources. Replace YourExistingDataSources with your existing usable DataSources.
package example.e2e;
import com.datamigration.api.MigrationSource;
import com.datamigration.api.MigrationTarget;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods = false)
public class ExternalDataSourcesConfiguration {
    @Bean(destroyMethod = "") @org.springframework.context.annotation.Lazy @MigrationSource(capacity = 4)
    public DataSource source() { return YourExistingDataSources.source(); }
    @Bean(destroyMethod = "") @org.springframework.context.annotation.Lazy @MigrationTarget(capacity = 4)
    public DataSource target() { return YourExistingDataSources.target(); }
}
