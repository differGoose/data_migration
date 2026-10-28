package com.datamigration.support;

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

public final class H2RecoveryWorker {

        public static void main(String[] args) {
            String mode=args[0];
            try (var context=new SpringApplicationBuilder(ResilienceHost.class)
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
