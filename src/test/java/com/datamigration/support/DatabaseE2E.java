package com.datamigration.support;

import static com.datamigration.support.MigrationAwait.await;

import com.datamigration.api.*;
import com.datamigration.conversion.JdbcValueConverter;
import com.datamigration.jdbc.DataSourceConnections;
import com.datamigration.properties.MigrationConfiguration;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.core.env.ConfigurableEnvironment;

/** Public TEST helper. Caller-supplied DataSources remain caller-owned. No database fixture is created. */
public final class DatabaseE2E {
    private DatabaseE2E() { }
    public record Report(Path directory,MigrationResult initial,MigrationResult repeated,E2EValidation.Result validation) { }

    public static Report run(DataSource source,int sourceCapacity,DataSource target,int targetCapacity,
            String... locations) throws Exception {
        return run(source,sourceCapacity,target,targetCapacity,E2EHost.environment(locations));
    }
    private static Report run(DataSource source,int sourceCapacity,DataSource target,int targetCapacity,
            ConfigurableEnvironment env) throws Exception {
        var settings=E2ESettings.read(env);
        if(settings.scenario()!=E2ESettings.Scenario.MIGRATION)
            throw new IllegalArgumentException("Fault scenarios require the configured external host/child-JVM entry");
        var plan=new MigrationConfiguration(env).get().toPlan();
        var connections=new DataSourceConnections(source,target); var converter=new JdbcValueConverter();
        Path output=Path.of(settings.reportDirectory()).resolve(UUID.randomUUID().toString()); Files.createDirectories(output);
        Files.writeString(output.resolve("manifest.properties"),"run-id="+output.getFileName()+"\nscenario=MIGRATION\njob-id="+plan.jobId()+"\n");
        var worker=Executors.newSingleThreadExecutor(r -> { var t=new Thread(r,"database-e2e"); t.setDaemon(true); return t; });
        try(var runner=MigrationRunner.builder().source(source,sourceCapacity).target(target,targetCapacity).environment(env).build()) {
            Future<Report> future=worker.submit(() -> {
                int timeout=seconds(settings.phaseTimeout());
                var baseline=E2EValidation.source(connections,plan,converter,settings.validation(),timeout);
                var first=await(runner);
                var validated=E2EValidation.complete(connections,plan,converter,settings.validation(),timeout,baseline);
                var repeated=await(runner);
                var second=E2EValidation.complete(connections,plan,converter,settings.validation(),timeout,baseline);
                E2EValidation.require(second.equals(validated),"Repeated migration changed data or audit totals/batches");
                return new Report(output,first,repeated,second);
            });
            try {
                var result=future.get(settings.phaseTimeout().toMillis(),TimeUnit.MILLISECONDS);
                Files.writeString(output.resolve("result.properties"),"status=PASSED\nscenario=MIGRATION\nrepeatIdempotent=true\nvalidatedTables="+plan.tables().size()+"\n");
                return result;
            }
            catch(TimeoutException failure) { future.cancel(true); throw new IllegalStateException("E2E deadline exceeded; migration cancelled"); }
            catch(ExecutionException failure) {
                if(failure.getCause() instanceof Exception e) throw e;
                if(failure.getCause() instanceof Error e) throw e;
                throw new IllegalStateException("E2E execution failed");
            }
            catch(InterruptedException failure) { future.cancel(true); Thread.currentThread().interrupt(); throw failure; }
        } catch(Exception | AssertionError failure) {
            Files.writeString(output.resolve("result.properties"),"status=FAILED\nscenario=MIGRATION\n"); throw failure;
        } finally { worker.shutdownNow(); }
    }

    /** Config files name external @Configuration classes, never credentials owned by this helper. */
    public static Path runConfigured(String... locations) throws Exception {
        var env=E2EHost.environment(locations); var settings=E2ESettings.read(env);
        if(settings.configurationClasses().isEmpty())
            throw new IllegalArgumentException("Provide external DataSource configuration classes; no H2 fallback");
        if(settings.scenario()==E2ESettings.Scenario.MIGRATION) {
            try(var context=E2EHost.open(env,settings.configurationClasses())) {
                var source=E2EHost.endpoint(context,MigrationSource.class); var target=E2EHost.endpoint(context,MigrationTarget.class);
                return run(source.dataSource(),source.capacity(),target.dataSource(),target.capacity(),env).directory();
            }
        }
        Path output=Path.of(settings.reportDirectory()).resolve(UUID.randomUUID().toString());
        ResilienceSuite.run(String.join(",",locations),Map.of("e2e.external-host",true),output); return output;
    }
    public static int seconds(Duration duration) { return (int)Math.max(1,Math.min(Integer.MAX_VALUE,duration.toSeconds())); }
    public static void main(String[] locations) {
        try { System.out.println("E2E report: "+runConfigured(locations)); }
        catch(Throwable failure) { System.err.println("Database E2E failed; inspect the private report/configuration"); System.exit(1); }
    }
}
