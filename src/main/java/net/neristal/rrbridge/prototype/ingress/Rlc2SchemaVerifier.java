package net.neristal.rrbridge.prototype.ingress;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;

/** Compare every SQLite schema object with authoritative DDL, including implicit indexes. */
final class Rlc2SchemaVerifier {
    private static Map<String,String> preview,integration;
    private Rlc2SchemaVerifier(){}
    static void verify(Connection db,boolean candidate)throws Exception{
        try(Statement s=db.createStatement()){
            try(ResultSet r=s.executeQuery("PRAGMA user_version")){if(!r.next()||r.getInt(1)!=(candidate?Rlc2IntegrationSchema.VERSION:0))throw new IOException("unsupported store schema version");}
            try(ResultSet r=s.executeQuery("PRAGMA quick_check")){if(!r.next()||!"ok".equals(r.getString(1))||r.next())throw new IOException("SQLite integrity check failed");}
            try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new IOException("SQLite foreign key check failed");}
        }
        if(!expected(candidate).equals(objects(db)))throw new IOException("store schema objects differ from contract");
    }
    private static synchronized Map<String,String> expected(boolean candidate)throws Exception{
        Map<String,String> cached=candidate?integration:preview;if(cached!=null)return cached;
        try(Connection c=DriverManager.getConnection("jdbc:sqlite::memory:");Statement s=c.createStatement()){
            Rlc2PreviewStore.createSchema(s);
            if(candidate){s.execute(Rlc2IntegrationSchema.BINDINGS);s.execute(Rlc2IntegrationSchema.ADMISSIONS);}
            cached=java.util.Collections.unmodifiableMap(objects(c));
        }
        if(candidate)integration=cached;else preview=cached;return cached;
    }
    private static Map<String,String> objects(Connection db)throws Exception{
        Map<String,String> result=new TreeMap<String,String>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name")){
            while(r.next())result.put(r.getString(1)+"|"+r.getString(2)+"|"+r.getString(3),normalize(r.getString(4)));
        }return result;
    }
    private static String normalize(String sql){
        if(sql==null)return "<implicit>";StringBuilder b=new StringBuilder();char quote=0;
        for(int i=0;i<sql.length();i++){
            char c=sql.charAt(i);
            if(quote!=0){b.append(c);if(c==quote)quote=0;}
            else if(c=='\''||c=='"'||c=='`'){quote=c;b.append(c);}
            else if(!Character.isWhitespace(c))b.append(Character.toLowerCase(c));
        }return b.toString();
    }
}
