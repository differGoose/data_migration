package com.datamigration.e2e;

import com.datamigration.api.MigrationService;
import com.datamigration.autoconfigure.MigrationConnectionPools;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.execution.JdbcMigrationEngine;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.properties.MigrationConfiguration;
import com.datamigration.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class H2ProcessRecoveryIT {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings={"before","after"})
    void newJvmRecoversFromAbruptExitAtCommitBoundary(String crash) throws Exception {
        try (var databases=new TcpDatabases(directory.resolve("db"),false)) {
            databases.seed(10_000);
            assertThat(run(databases,crash)).isEqualTo(73);
            try (var connection=DatabaseFixture.connection(databases.targetUrl());
                 var statement=connection.createStatement();
                 var result=statement.executeQuery("SELECT COUNT(*) FROM CUSTOMER")) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(crash.equals("after") ? 1000 : 0);
            }
            assertThat(run(databases,"resume")).isZero();
            H2MigrationIT.assertEquivalent(databases);
        }
    }

    private int run(TcpDatabases databases,String mode) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path output=directory.resolve(mode+".log");
        Process process=new ProcessBuilder(java,"-cp",classpath,Worker.class.getName(),mode,
                "--spring.config.location=classpath:recovery.yml","--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off","--data-migration.job-id=e2e",
                "--data-migration.source.jdbc-url="+databases.sourceUrl(),
                "--data-migration.source.username=sa","--data-migration.source.password=",
                "--data-migration.source.driver-class-name=org.h2.Driver",
                "--data-migration.target.jdbc-url="+databases.targetUrl(),
                "--data-migration.target.username=sa","--data-migration.target.password=",
                "--data-migration.target.driver-class-name=org.h2.Driver")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(45,TimeUnit.SECONDS)).withFailMessage("Child process timed out: "+output).isTrue();
            int code=process.exitValue();
            assertThat(code).withFailMessage(new String(Files.readAllBytes(output),StandardCharsets.UTF_8)).isIn(0,73);
            return code;
        } finally { process.destroyForcibly(); }
    }

    public static class Worker {
        public static void main(String[] args) {
            String mode=args[0];
            try (var context=new SpringApplicationBuilder(H2MigrationIT.Host.class)
                    .initializers(application -> application.getBeanFactory().registerSingleton("processService",
                            (MigrationService) () -> {
                                var pools=application.getBean(MigrationConnectionPools.class);
                                var config=application.getBean(MigrationConfiguration.class).get();
                                ConnectionAccess access=new ConnectionAccess() {
                                    public Connection openSourceConnection() throws SQLException { return pools.openSourceConnection(); }
                                    public Connection openTargetConnection() throws SQLException {
                                        Connection delegate=pools.openTargetConnection();
                                        AtomicBoolean business=new AtomicBoolean();
                                        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                                                new Class<?>[]{Connection.class},(proxy,method,values) -> {
                                            if (method.getName().equals("commit") && business.get() && mode.equals("before"))
                                                Runtime.getRuntime().halt(73);
                                            Object value;
                                            try { value=method.invoke(delegate,values); }
                                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                                            if (method.getName().equals("prepareStatement") && values[0] instanceof String sql
                                                    && sql.startsWith("INSERT INTO CUSTOMER")) {
                                                PreparedStatement prepared=(PreparedStatement)value;
                                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                                        new Class<?>[]{PreparedStatement.class},(p,operation,parameters) -> {
                                                    Object outcome;
                                                    try { outcome=operation.invoke(prepared,parameters); }
                                                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                                                    if (operation.getName().equals("executeBatch")) business.set(true);
                                                    return outcome;
                                                });
                                            }
                                            if (method.getName().equals("commit") && business.get() && mode.equals("after"))
                                                Runtime.getRuntime().halt(73);
                                            return value;
                                        });
                                    }
                                };
                                try (var engine=new JdbcMigrationEngine(access,4,4,
                                        config.source().jdbcUrl()+"|"+config.target().jdbcUrl(),new JdbcValueConverter())) {
                                    return engine.execute(config.toPlan());
                                }
                            }))
                    .run(java.util.Arrays.copyOfRange(args,1,args.length))) {
                context.getBean(MigrationService.class).migrate();
            }
        }
    }
}
