package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** Explicit, isolated storage contract v2; never an automatic v1 product migration. */
public final class Rlc2IntegrationSchema {
    static final int APPLICATION_ID=0x524c4932, VERSION=1; // RLI2, migration revision 1
    static final String BINDINGS="CREATE TABLE integration_bindings(session_uuid TEXT PRIMARY KEY REFERENCES preview_sessions(session_uuid),session_path TEXT NOT NULL UNIQUE,run_id TEXT NOT NULL UNIQUE,contract_version INTEGER NOT NULL CHECK(contract_version=2))";
    static final String ADMISSIONS="CREATE TABLE integration_admissions(source_uuid TEXT PRIMARY KEY REFERENCES preview_sources(source_uuid),session_uuid TEXT NOT NULL REFERENCES integration_bindings(session_uuid))";
    private Rlc2IntegrationSchema(){}
    public interface BeforeCommit { void run()throws Exception; }
    public static void initialize(boolean enable,File file)throws Exception {initialize(enable,file,new BeforeCommit(){public void run(){}});}
    static void initialize(boolean enable,File file,BeforeCommit hook)throws Exception {
        if(!enable)throw new IllegalStateException("integration schema disabled");
        if(file==null||!file.getName().endsWith(".rlc2-integration.db"))throw new IOException("dedicated integration extension required");
        IngressDiagnostics.safePath(file.toPath());
        if(file.getAbsoluteFile().getParentFile()==null||!file.getAbsoluteFile().getParentFile().isDirectory())throw new IOException("existing parent required");
        Class.forName("org.sqlite.JDBC");
        if(Files.exists(file.toPath())) {
            try(Connection c=IngressDiagnostics.readOnly(file)){verify(c);}return;
        }
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file.getAbsolutePath());Statement s=c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA synchronous=FULL");s.execute("BEGIN IMMEDIATE");
            try {
                // No implicit adoption of a DB created by another initializer.
                try(ResultSet r=s.executeQuery("SELECT count(*) FROM sqlite_master")){r.next();if(r.getInt(1)!=0)throw new IOException("database initialized concurrently; retry verification");}
                Rlc2PreviewStore.createSchema(s);
                s.execute(BINDINGS);s.execute(ADMISSIONS);
                s.execute("PRAGMA application_id="+APPLICATION_ID);s.execute("PRAGMA user_version="+VERSION);
                verify(c);hook.run();s.execute("COMMIT");
            }catch(Exception failure){s.execute("ROLLBACK");throw failure;}
        }
    }
    static void verify(Connection c)throws Exception {
        try(Statement s=c.createStatement()){
            try(ResultSet r=s.executeQuery("PRAGMA application_id")){if(!r.next()||r.getInt(1)!=APPLICATION_ID)throw new IOException("not integration-owned");}
            try(ResultSet r=s.executeQuery("PRAGMA user_version")){if(!r.next()||r.getInt(1)!=VERSION)throw new IOException("unsupported integration migration version");}
            try(ResultSet r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('preview_sessions','preview_claims','preview_sources','preview_receipts','preview_profiles','preview_records','integration_bindings','integration_admissions')")){r.next();if(r.getInt(1)!=8)throw new IOException("incomplete integration schema");}
        }
        Rlc2SchemaVerifier.verify(c,true);
    }
    /** Static, closed v1 compatibility inventory; no DDL, adoption or ACK mutation. */
    public static void requireUnclaimed(File legacy,String device,String vehicle,String session,String run)throws Exception {
        try(Connection c=IngressDiagnostics.readOnly(legacy)) {
            String[] queries={"SELECT 1 FROM v1_source_claims WHERE device_id=? AND vehicle_id=? AND session_id=?",
                "SELECT 1 FROM v1_journal_import_cursors WHERE device_id=? AND vehicle_id=? AND session_id=?"};
            for(String text:queries)try(PreparedStatement q=c.prepareStatement(text)){
                q.setString(1,device);q.setString(2,vehicle);q.setString(3,session);
                try(ResultSet r=q.executeQuery()){if(r.next())throw new IOException("legacy lane already owns logical session");}
            }
            try(PreparedStatement q=c.prepareStatement("SELECT source_path FROM v1_import_cursors WHERE device_id=? AND vehicle_id=?")){
                q.setString(1,device);q.setString(2,vehicle);try(ResultSet r=q.executeQuery()){
                    while(r.next()){String p=r.getString(1).replace('\\','/');if(p.equals("rr_"+session+".tsv")||p.endsWith("/rr_"+session+".tsv"))throw new IOException("legacy TSV cursor owns logical session");}
                }
            }
            try(PreparedStatement q=c.prepareStatement("SELECT 1 FROM v1_records WHERE run_id=?")){
                q.setString(1,run);try(ResultSet r=q.executeQuery()){if(r.next())throw new IOException("legacy run has existing records");}
            }
        }
    }
}
