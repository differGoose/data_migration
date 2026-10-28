package com.datamigration.support;
import static org.assertj.core.api.Assertions.*;
public final class MigrationAssertions {
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
