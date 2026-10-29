package com.datamigration.component;
import com.datamigration.autoconfigure.MigrationAutoConfiguration;
import com.datamigration.properties.MigrationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
class ExampleConfigurationTest {
 @Test void exampleBindsWithoutConnectionConfiguration() throws Exception {
  var yaml=new YamlPropertySourceLoader().load("example",new ClassPathResource("migration.yml"));
  new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MigrationAutoConfiguration.class))
   .withInitializer(c -> yaml.forEach(c.getEnvironment().getPropertySources()::addLast)).run(c -> {
    var p=c.getBean(MigrationConfiguration.class).get();
    assertThat(p.toPlan().tables()).isNotEmpty();
    assertThat(p.tables().get(0).batchSize()).isEqualTo(1000);
   });
 }
}
