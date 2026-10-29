package com.datamigration.support;

import com.datamigration.conversion.ValueConverter;
import com.datamigration.jdbc.ConnectionAccess;
import com.datamigration.model.*;
import com.datamigration.sql.SqlSupport;
import java.sql.*;
import java.util.*;

/** Validation SQL describes the EXPECTED final subset, which may exclude deliberately skipped rows. */
public final class E2EValidation {
    private E2EValidation() { }
    public record Source(Map<String,RowDigest> expected,Map<String,Long> migrationRows) { }
    public record Audit(long committed,long skipped,List<String> batches) { }
    public record Result(Map<String,RowDigest> targets,Map<String,Audit> audits) { }
    public static Source source(ConnectionAccess access,MigrationPlan plan,ValueConverter converter,
            Map<String,ResilienceSettings.Validation> queries,int timeout) throws Exception {
        Map<String,RowDigest> expected=new LinkedHashMap<>(); Map<String,Long> counts=new LinkedHashMap<>();
        try(var source=access.openSourceConnection()) {
            for(var task:plan.tables()) {
                var query=query(queries,task.name());
                expected.put(task.name(),RowDigest.read(source,query.sourceSql(),task,converter,timeout));
                try(var st=source.prepareStatement("SELECT COUNT(*) FROM ("+SqlSupport.query(task)+") e2e_source_count")) {
                    st.setQueryTimeout(timeout);
                    try(var rows=st.executeQuery()) { rows.next(); counts.put(task.name(),rows.getLong(1)); }
                }
            }
        }
        return new Source(expected,counts);
    }
    public static Result complete(ConnectionAccess access,MigrationPlan plan,ValueConverter converter,
            Map<String,ResilienceSettings.Validation> queries,int timeout,Source baseline) throws Exception {
        require(source(access,plan,converter,queries,timeout).equals(baseline),"Source changed during E2E");
        Map<String,RowDigest> targets=targets(access,plan,converter,queries,timeout);
        require(targets.equals(baseline.expected()),"Target differs from configured expected subset");
        Map<String,Audit> audits=new LinkedHashMap<>();
        try(var target=access.openTargetConnection()) {
            for(var task:plan.tables()) {
                String table=SqlSupport.table(plan.strategy().auditTableName());
                long committed,skipped;
                try(var st=target.prepareStatement("SELECT state,committed_rows,skipped_rows FROM "+table
                        +" WHERE job_id=? AND task_name=? AND batch_no=0")) {
                    st.setString(1,plan.jobId()); st.setString(2,task.name()); st.setQueryTimeout(timeout);
                    try(var rows=st.executeQuery()) {
                        require(rows.next(),"Missing E2E audit summary");
                        require("SUCCEEDED".equals(rows.getString(1)),"E2E audit summary is not successful");
                        committed=rows.getLong(2); skipped=rows.getLong(3);
                        require(committed+skipped==baseline.migrationRows().get(task.name()),"Audit processed rows differ from migration source");
                    }
                }
                long batchCommitted=0,batchSkipped=0; List<String> batches=new ArrayList<>();
                try(var st=target.prepareStatement("SELECT batch_no,state,committed_rows,skipped_rows,expected_rows FROM "+table
                        +" WHERE job_id=? AND task_name=? AND batch_no>0 ORDER BY batch_no")) {
                    st.setString(1,plan.jobId()); st.setString(2,task.name()); st.setQueryTimeout(timeout);
                    try(var rows=st.executeQuery()) {
                        while(rows.next()) {
                            require("SUCCEEDED".equals(rows.getString(2)),"Unfinished E2E audit batch");
                            batchCommitted+=rows.getLong(3); batchSkipped+=rows.getLong(4);
                            batches.add(rows.getLong(1)+":"+rows.getString(2)+":"+rows.getLong(3)+":"+rows.getLong(4)+":"+rows.getLong(5));
                        }
                    }
                }
                require(batchCommitted==committed && batchSkipped==skipped,"Batch audit totals differ from summary");
                audits.put(task.name(),new Audit(committed,skipped,List.copyOf(batches)));
            }
        }
        return new Result(targets,audits);
    }
    public static Map<String,RowDigest> targets(ConnectionAccess access,MigrationPlan plan,ValueConverter converter,
            Map<String,ResilienceSettings.Validation> queries,int timeout) throws Exception {
        Map<String,RowDigest> result=new LinkedHashMap<>();
        try(var target=access.openTargetConnection()) {
            for(var task:plan.tables()) result.put(task.name(),RowDigest.read(target,query(queries,task.name()).targetSql(),task,converter,timeout));
        }
        return result;
    }
    private static ResilienceSettings.Validation query(Map<String,ResilienceSettings.Validation> queries,String name) {
        var query=queries==null?null:queries.get(name);
        if(query==null || query.sourceSql()==null || query.targetSql()==null)
            throw new IllegalArgumentException("Configure expected source and target validation SQL for every enabled task");
        return query;
    }
    public static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
