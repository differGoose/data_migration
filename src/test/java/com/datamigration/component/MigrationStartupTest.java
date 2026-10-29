package com.datamigration.component;
import com.datamigration.api.*;
import com.datamigration.support.ResilienceHost;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.assertj.core.api.Assertions.*;
class MigrationStartupTest {
 @Test void autoDiscoveryDoesNotRunMigration() {
  try(var c=new SpringApplicationBuilder(ResilienceHost.class).run("--spring.main.web-application-type=none","--spring.main.banner-mode=off")) {
   assertThat(c.getBean(MigrationService.class)).isNotNull();
   assertThat(c.getBeansOfType(org.springframework.boot.ApplicationRunner.class)).isEmpty();
  }
 }
}
