package com.datamigration.support;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
public final class MigrationAssertions {
    public record BatchCheckpoint(long batch, String state, long committedRows, Timestamp updatedAt) {}

    public static List<BatchCheckpoint> businessCheckpoints(TcpDatabases databases) throws Exception {
        var checkpoints = new ArrayList<BatchCheckpoint>();
        try (var connection = DatabaseFixture.connection(databases.targetUrl());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT batch_no,state,committed_rows,updated_at FROM migration_audit"
                     + " WHERE job_id='e2e' AND task_name='customer' AND batch_no>0 ORDER BY batch_no")) {
            while (rows.next()) checkpoints.add(new BatchCheckpoint(rows.getLong(1), rows.getString(2),
                    rows.getLong(3), rows.getTimestamp(4)));
        }
        return checkpoints;
    }

    public static void assertCompletedDataState(TcpDatabases databases, String state) throws Exception {
        try (var connection = DatabaseFixture.connection(databases.targetUrl());
             var statement = connection.prepareStatement("SELECT state,data_complete FROM migration_audit"
                     + " WHERE job_id='e2e' AND task_name='customer' AND batch_no=0");
             var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("state")).isEqualTo(state);
            assertThat(row.getBoolean("data_complete")).isTrue();
        }
    }

    public static void assertEquivalent(TcpDatabases databases) throws Exception {
        try (var source = DatabaseFixture.connection(databases.sourceUrl());
             var target = DatabaseFixture.connection(databases.targetUrl());
             var a = source.createStatement(); var b = target.createStatement();
             var left = a.executeQuery("SELECT * FROM CUSTOMER ORDER BY ID");
             var right = b.executeQuery("SELECT * FROM CUSTOMER ORDER BY ID")) {
            long count = 0;
            while (left.next()) {
                assertThat(right.next()).isTrue();
                assertThat(right.getLong(1)).isEqualTo(left.getLong(1));
                assertThat(right.getString(2)).isEqualTo(left.getString(2));
                assertThat(right.getBigDecimal(3)).isEqualByComparingTo(left.getBigDecimal(3));
                count++;
            }
            assertThat(right.next()).isFalse();
            assertThat(count).isEqualTo(10_000);
            try (var summary = b.executeQuery("SELECT committed_rows FROM migration_audit WHERE job_id='e2e' AND batch_no=0")) {
                assertThat(summary.next()).isTrue();
                assertThat(summary.getLong(1)).isEqualTo(10_000);
            }
        }
    }
}
