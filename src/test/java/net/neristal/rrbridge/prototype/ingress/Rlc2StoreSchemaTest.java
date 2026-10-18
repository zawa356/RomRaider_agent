package net.neristal.rrbridge.prototype.ingress;
import java.io.File;
import java.nio.file.Files;

public final class Rlc2StoreSchemaTest {
    static int checks;
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    public static void main(String[] args)throws Exception{
        final File renamed=Rlc2PreviewStoreTest.target();new Rlc2PreviewStore(true,renamed);
        Rlc2PreviewStoreTest.execute(renamed,"ALTER TABLE preview_records RENAME COLUMN ack_eligible TO accepted");
        boolean refused=false;try{new Rlc2PreviewStore(true,renamed);}catch(Exception expected){refused=true;}
        check(refused,"renamed mandatory ACK column rejected before writable open");
        String[] changes={
            "PRAGMA user_version=99",
            "CREATE TRIGGER unknown_trigger AFTER INSERT ON preview_claims BEGIN SELECT 1; END",
            "CREATE VIEW unknown_view AS SELECT 1",
            "CREATE INDEX unknown_index ON preview_records(captured_at)",
            "DROP INDEX preview_run_sequence",
            "ALTER TABLE preview_sources ADD COLUMN unexpected TEXT",
            "DROP TABLE preview_receipts"
        };
        for(String sql:changes){File db=Rlc2PreviewStoreTest.target();new Rlc2PreviewStore(true,db);Rlc2PreviewStoreTest.execute(db,sql);assertRefused(db,"unexpected schema object/version rejected: "+sql.split(" ")[0]);}
        String[][] rewrites={
            {"preview_records","CHECK(ack_eligible=0)",""},
            {"preview_sources","source_path TEXT NOT NULL UNIQUE","source_path TEXT NOT NULL"},
            {"preview_receipts","body_sha256 TEXT NOT NULL","body_sha256 TEXT"},
            {"preview_profiles","profile_id TEXT PRIMARY KEY","profile_id TEXT"},
            {"preview_receipts","REFERENCES preview_sources(source_uuid)",""},
            {"preview_records","captured_at TEXT NOT NULL","captured_at INTEGER NOT NULL"},
            {"preview_records","'observation','event'","'obser vation','event'"}
        };
        for(String[] edit:rewrites){File db=Rlc2PreviewStoreTest.target();new Rlc2PreviewStore(true,db);
            try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+db);java.sql.Statement s=c.createStatement()){
                s.execute("PRAGMA writable_schema=ON");
                try(java.sql.PreparedStatement q=c.prepareStatement("UPDATE sqlite_master SET sql=replace(sql,?,?) WHERE name=?")){q.setString(1,edit[1]);q.setString(2,edit[2]);q.setString(3,edit[0]);q.executeUpdate();}
            }
            assertRefused(db,"missing or changed constraint/type rejected "+edit[1]);
        }
        File legacy=Rlc2PreviewStoreTest.target();
        String ddl=new String(Files.readAllBytes(new File("src/test/resources/rlc2-preview-v0.sql").toPath()),java.nio.charset.StandardCharsets.UTF_8);
        Class.forName("org.sqlite.JDBC");try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+legacy);java.sql.Statement s=c.createStatement()){
            for(String line:ddl.split("\n"))if(!line.startsWith("--")&&!line.trim().isEmpty())s.execute(line);
        }
        byte[] bytes=Files.readAllBytes(legacy.toPath());new Rlc2PreviewStore(true,legacy);
        check(java.util.Arrays.equals(bytes,Files.readAllBytes(legacy.toPath())),"actual pre-I v0 DDL remains accepted without mutation");
        File candidate=Rlc2IntegrationStoreTest.target();Rlc2IntegrationSchema.initialize(true,candidate);
        Rlc2PreviewStoreTest.execute(candidate,"ALTER TABLE integration_bindings RENAME COLUMN run_id TO broken_run");
        byte[] candidateBytes=Files.readAllBytes(candidate.toPath());boolean no=false;try{new Rlc2IntegrationStore(true,candidate);}catch(Exception expected){no=true;}
        check(no&&java.util.Arrays.equals(candidateBytes,Files.readAllBytes(candidate.toPath())),"integration metadata schema tampering rejected unchanged");
        File corrupt=Rlc2PreviewStoreTest.target();new Rlc2PreviewStore(true,corrupt);try(java.io.RandomAccessFile w=new java.io.RandomAccessFile(corrupt,"rw")){w.seek(0);w.write(new byte[]{0,0,0,0});}assertRefused(corrupt,"corrupt SQLite header rejected unchanged");
        System.out.println("RLC2 store schema passed "+checks+" checks");
    }
    static void assertRefused(File file,String why)throws Exception{
        byte[] before=Files.readAllBytes(file.toPath());boolean refused=false;try{new Rlc2PreviewStore(true,file);}catch(Exception expected){refused=true;}
        check(refused,why);check(java.util.Arrays.equals(before,Files.readAllBytes(file.toPath())),"rejected schema evidence unchanged");
    }
}
