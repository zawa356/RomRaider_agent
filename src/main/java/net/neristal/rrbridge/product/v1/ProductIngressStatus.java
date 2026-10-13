package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Read-only, token-free CLI suitable for a future Supervisor poll.
 * This does NOT change ECU, Logger, worker state, spool, or review latch.
 *
 * Exit 0: no journal ingress review latch (NOT a guarantee of safe driving)
 * Exit 5: persistent review hold
 * Exit 6: cannot determine status (fail closed)
 * Exit 2: invalid argument or source path
 */
public final class ProductIngressStatus {
    private ProductIngressStatus(){}
    public static void main(String[] args)throws Exception{
        if(args.length!=1){
            System.err.println("Usage: ProductIngressStatus <existing-sqlite-db>");
            System.exit(2);
        }
        File path=new File(args[0]).getAbsoluteFile();
        if(!path.isFile()||Files.isSymbolicLink(path.toPath())||
           path.getParentFile()==null||Files.isSymbolicLink(path.getParentFile().toPath())){
            System.err.println("INGRESS_STATUS=UNKNOWN (invalid or missing local database)");
            System.exit(2);
        }
        try {
            Class.forName("org.sqlite.JDBC");
            // SQLite URI mode=ro opens the existing DB read-only rather than
            // creating a new database or invoking DurableSpool schema DDL.
            String jdbc="jdbc:sqlite:"+path.toURI().toASCIIString()+"?mode=ro";
            try(Connection db=DriverManager.getConnection(jdbc)){
                try(Statement s=db.createStatement()){
                    s.execute("PRAGMA query_only=ON");
                }
                String reason=null;
                try(PreparedStatement p=db.prepareStatement(
                    "SELECT reason_code FROM v1_ingress_review_state WHERE singleton=1");
                    ResultSet r=p.executeQuery()){
                    if(r.next())reason=r.getString(1);
                }
                if(reason==null){
                    System.out.println("INGRESS_STATUS=NO_REVIEW_LATCH");
                    return;
                }
                // Never include source/SQL exception text, tokens, paths or
                // original journal contents in machine-readable status.
                if(!("JOURNAL_PREFLIGHT".equals(reason)||
                     "JOURNAL_IMPORT".equals(reason)||
                     "JOURNAL_SOURCE_MISSING".equals(reason)))
                    throw new IllegalStateException("Unrecognized review reason");
                System.out.println("INGRESS_STATUS=REVIEW_REQUIRED reason="+reason);
                System.exit(5);
            }
        }catch(Exception unknown){
            System.err.println("INGRESS_STATUS=UNKNOWN (read-only verification failed)");
            System.exit(6);
        }
    }
}
