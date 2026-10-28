package com.datamigration.e2e;

import com.datamigration.api.*;
import com.datamigration.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class H2ScenariosIT {
    @TempDir Path directory;

    @Test void independentTablesAtomicKeylessLobsCompositeKeysAndExplicitReimport() throws Exception {
        try (var databases = new TcpDatabases(directory,false)) {
            databases.seed(10_000); databases.seedDocuments();
            DatabaseFixture.execute(databases.targetUrl(), "INSERT INTO CUSTOMER VALUES(99999,'old',1)");
            DatabaseFixture.execute(databases.targetUrl(), "ALTER TABLE CUSTOMER ADD CONSTRAINT bad_customer CHECK(ID<>10000)");
            String ddl = "CREATE TABLE ORDERS(TENANT INTEGER, ID BIGINT, PRIMARY KEY(TENANT,ID))";
            DatabaseFixture.execute(databases.sourceUrl(),ddl); DatabaseFixture.execute(databases.targetUrl(),ddl);
            DatabaseFixture.execute(databases.sourceUrl(),"INSERT INTO ORDERS SELECT MOD(X,3),X FROM SYSTEM_RANGE(1,250)");
            DatabaseFixture.execute(databases.targetUrl(),"ALTER TABLE ORDERS ADD CONSTRAINT bad_order CHECK(ID<>5 AND ID<>105)");
            DatabaseFixture.execute(databases.targetUrl(),"CREATE SEQUENCE ORDER_SEQ START WITH 1");
            try (var context = context(databases,"scenario")) {
                var failure = catchThrowableOfType(() -> context.getBean(MigrationService.class).migrate(),MigrationException.class);
                assertThat(failure.result().tables()).extracting(TableResult::status).containsExactly(
                        TableResult.Status.FAILED,TableResult.Status.SUCCEEDED,TableResult.Status.SUCCEEDED);
                assertThat(failure.result().tables().get(2).skippedRows()).isEqualTo(2);
            }
            assertThat(scalar(databases,"SELECT COUNT(*) FROM CUSTOMER")).isEqualTo(1);
            assertThat(scalar(databases,"SELECT ID FROM CUSTOMER")).isEqualTo(99999);
            assertDocuments(databases);
            assertThat(scalar(databases,"SELECT COUNT(*) FROM ORDERS")).isEqualTo(248);
            assertThat(scalar(databases,"SELECT NEXT VALUE FOR ORDER_SEQ")).isEqualTo(251);

            DatabaseFixture.execute(databases.targetUrl(),"ALTER TABLE CUSTOMER DROP CONSTRAINT bad_customer");
            try (var context = context(databases,"scenario")) {
                assertThat(context.getBean(MigrationService.class).migrate().tables())
                        .allMatch(result -> result.status() == TableResult.Status.SUCCEEDED);
            }
            assertCustomerRows(databases);
            // New job explicitly clears/reimports. Existing success markers must not hide this work.
            DatabaseFixture.execute(databases.targetUrl(),"UPDATE CUSTOMER SET NAME='changed' WHERE ID=1");
            DatabaseFixture.execute(databases.targetUrl(),"UPDATE DOCUMENTS SET CONTENT=NULL WHERE ID=1");
            try (var context = context(databases,"scenario-new")) { context.getBean(MigrationService.class).migrate(); }
            assertCustomerRows(databases); assertDocuments(databases);
            assertThat(scalar(databases,"SELECT COUNT(*) FROM ORDERS")).isEqualTo(248);
            assertThat(scalar(databases,"SELECT NEXT VALUE FOR ORDER_SEQ")).isEqualTo(252);
            assertThat(scalar(databases,"SELECT COUNT(*) FROM migration_audit WHERE job_id='scenario-new' AND batch_no=0 AND state='SUCCEEDED'")).isEqualTo(3);
        }
    }

    private ConfigurableApplicationContext context(TcpDatabases databases,String job) {
        return new SpringApplicationBuilder(H2MigrationIT.Host.class).properties(databases.properties()).run(
                "--spring.config.location=classpath:scenarios.yml", "--data-migration.job-id="+job,
                "--data-migration.source.pool.maximum-pool-size=10", "--data-migration.target.pool.maximum-pool-size=10");
    }
    private long scalar(TcpDatabases databases,String sql) throws Exception {
        try (var connection=DatabaseFixture.connection(databases.targetUrl()); var statement=connection.createStatement();
             var result=statement.executeQuery(sql)) { result.next(); return result.getLong(1); }
    }
    private void assertCustomerRows(TcpDatabases databases) throws Exception {
        assertThat(scalar(databases,"SELECT COUNT(*) FROM CUSTOMER")).isEqualTo(10_000);
        try (var source=DatabaseFixture.connection(databases.sourceUrl()); var target=DatabaseFixture.connection(databases.targetUrl());
             var leftStatement=source.createStatement(); var rightStatement=target.createStatement();
             var left=leftStatement.executeQuery("SELECT * FROM CUSTOMER ORDER BY ID");
             var right=rightStatement.executeQuery("SELECT * FROM CUSTOMER ORDER BY ID")) {
            while (left.next()) {
                assertThat(right.next()).isTrue();
                for (int col=1; col<=3; col++) assertThat(right.getObject(col)).isEqualTo(left.getObject(col));
            }
            assertThat(right.next()).isFalse();
        }
    }
    private void assertDocuments(TcpDatabases databases) throws Exception {
        try (var source=DatabaseFixture.connection(databases.sourceUrl()); var target=DatabaseFixture.connection(databases.targetUrl());
             var leftStatement=source.createStatement(); var rightStatement=target.createStatement();
             var left=leftStatement.executeQuery("SELECT * FROM DOCUMENTS ORDER BY ID");
             var right=rightStatement.executeQuery("SELECT * FROM DOCUMENTS ORDER BY ID")) {
            int count=0;
            while (left.next()) {
                assertThat(right.next()).isTrue();
                assertThat(right.getLong(1)).isEqualTo(left.getLong(1));
                assertThat(right.getBytes(2)).isEqualTo(left.getBytes(2));
                assertThat(right.getString(3)).isEqualTo(left.getString(3)); count++;
            }
            assertThat(count).isEqualTo(3); assertThat(right.next()).isFalse();
        }
    }
}
