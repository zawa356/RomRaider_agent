package net.neristal.rrbridge.prototype.ingress;

import java.io.IOException;
import java.sql.*;
import java.util.*;

/** Independent, typed, exact row-set verification. Does not ingest or repair the candidate. */
final class LegacyStagedEvidence {
    static void createVault(Statement s)throws Exception {
        s.execute("CREATE TABLE vault_meta(version INTEGER PRIMARY KEY CHECK(version=1),v1_sha256 TEXT NOT NULL,formal_ack INTEGER NOT NULL CHECK(formal_ack=0))");
        s.execute("CREATE TABLE vault_records(v1_local_id INTEGER PRIMARY KEY,record_id TEXT NOT NULL UNIQUE,run_id TEXT NOT NULL,v1_body_sha256 TEXT NOT NULL,body_json TEXT,"
            +"v1_acked INTEGER NOT NULL CHECK(v1_acked IN (0,1)),v1_acked_batch_id TEXT,v1_acked_at TEXT,category TEXT NOT NULL,reason TEXT NOT NULL,rli2_record INTEGER NOT NULL CHECK(rli2_record IN (0,1)))");
        s.execute("CREATE TABLE vault_sessions(run_id TEXT PRIMARY KEY,category TEXT NOT NULL)");
    }
    private static Map<String,String> objects(Connection c)throws Exception {
        Map<String,String> result=new TreeMap<String,String>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT type,name,tbl_name,sql FROM sqlite_master")){
            while(r.next())result.put(r.getString(1)+"|"+r.getString(2)+"|"+r.getString(3),r.getString(4));
        }return result;
    }
    static void vault(Connection c,LegacyProvenanceClassifier.Report report)throws Exception {
        try(Statement s=c.createStatement()){
            for(String pragma:new String[]{"quick_check","integrity_check"})try(ResultSet r=s.executeQuery("PRAGMA "+pragma)){
                if(!r.next()||!"ok".equals(r.getString(1))||r.next())throw new IOException("VAULT_INTEGRITY");
            }
            try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new IOException("VAULT_FOREIGN_KEY");}
            try(ResultSet r=s.executeQuery("PRAGMA application_id")){if(!r.next()||r.getInt(1)!=LegacyProvenanceClassifier.VAULT_APPLICATION_ID)throw new IOException("VAULT_FORMAT");}
            try(ResultSet r=s.executeQuery("PRAGMA user_version")){if(!r.next()||r.getInt(1)!=1)throw new IOException("VAULT_VERSION");}
        }
        try(Connection reference=DriverManager.getConnection("jdbc:sqlite::memory:");Statement s=reference.createStatement()){
            createVault(s);if(!objects(reference).equals(objects(c)))throw new IOException("VAULT_SCHEMA");
        }
        List<Object[]> rows=new ArrayList<Object[]>();
        rows.add(new Object[]{1,report.v1Sha256,0});exact(c,"vault_meta",rows);
        rows.clear();for(LegacyProvenanceClassifier.Row r:report.rows)rows.add(new Object[]{r.localId,r.recordId,r.runId,r.bodySha,r.bodyJson,r.acked?1:0,r.ackedBatch,r.ackedAt,r.category.name(),r.reason,report.migratable(r)?1:0});
        exact(c,"vault_records",rows);
        rows.clear();for(Map.Entry<String,String> e:report.sessions.entrySet())rows.add(new Object[]{e.getKey(),e.getValue()});exact(c,"vault_sessions",rows);
    }
    static Map<String,List<Object[]>> expected(){
        Map<String,List<Object[]>> tables=new LinkedHashMap<String,List<Object[]>>();
        for(String t:new String[]{"preview_sessions","preview_claims","preview_sources","preview_receipts","preview_profiles","preview_records","integration_bindings","integration_admissions"})tables.put(t,new ArrayList<Object[]>());
        return tables;
    }
    static void add(Map<String,List<Object[]>> tables,Rlc2ProductAdapter.Projection p,String directory){
        String run=p.records.get(0).run;
        tables.get("preview_sessions").add(new Object[]{p.manifestSession,p.device,p.vehicle,p.payloadSession,p.manifestGeneration,p.manifestBytes(),p.records.size(),p.receipts.size()});
        tables.get("preview_claims").add(new Object[]{p.device,p.vehicle,p.payloadSession,"rlc2-preview",p.manifestSession});
        tables.get("integration_bindings").add(new Object[]{p.manifestSession,directory,run,2});
        for(Rlc2ProductAdapter.Source s:p.sources){
            WatermarkV2Prototype.Marker m=s.marker;
            tables.get("preview_sources").add(new Object[]{s.uuid,p.manifestSession,s.index,s.path,m.identity.tag.code,WatermarkLedgerPrototype.hex(m.identity.value()),m.generation,m.sequence,m.offset,WatermarkLedgerPrototype.hex(m.prefixHash()),s.sealed?1:0});
            tables.get("integration_admissions").add(new Object[]{s.uuid,p.manifestSession});
        }
        for(Rlc2ProductAdapter.Receipt r:p.receipts)tables.get("preview_receipts").add(new Object[]{r.sourceUuid,r.sequence,r.receiptId,r.offset,r.bodySha256,r.body});
        for(Map.Entry<String,String> e:p.profiles.entrySet())tables.get("preview_profiles").add(new Object[]{e.getKey(),e.getValue()});
        for(Rlc2ProductAdapter.Record r:p.records)tables.get("preview_records").add(new Object[]{r.id,r.run,r.segment,r.sequence,r.kind,r.profile,r.capturedAt,r.source,r.json,p.manifestSession,0});
    }
    // Include SQLite storage classes: text "1", real 1.0 and integer 1 are distinct evidence.
    private static String row(Object[] values){
        StringBuilder key=new StringBuilder();
        for(Object value:values){
            String type,text;
            if(value==null){type="null";text="";}
            else if(value instanceof byte[]){type="blob";text=Base64.getEncoder().encodeToString((byte[])value);}
            else if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long){type="integer";text=value.toString();}
            else if(value instanceof Number){type="real";text=value.toString();}
            else{type="text";text=value.toString();}
            key.append(type).append(':').append(text.length()).append(':').append(text).append(';');
        }return key.toString();
    }
    static void exact(Connection c,String table,List<Object[]> expected)throws Exception {
        Map<String,Integer> remaining=new HashMap<String,Integer>();
        for(Object[] values:expected){String key=row(values);Integer n=remaining.get(key);remaining.put(key,n==null?1:n+1);}
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM "+table)){
            while(r.next()){
                Object[] values=new Object[r.getMetaData().getColumnCount()];for(int i=0;i<values.length;i++)values[i]=r.getObject(i+1);
                String key=row(values);Integer count=remaining.get(key);if(count==null)throw new IOException("STAGED_ROWS_MISMATCH:"+table);
                if(count==1)remaining.remove(key);else remaining.put(key,count-1);
            }
        }
        if(!remaining.isEmpty())throw new IOException("STAGED_ROWS_MISSING:"+table);
    }
    private LegacyStagedEvidence(){}
}
