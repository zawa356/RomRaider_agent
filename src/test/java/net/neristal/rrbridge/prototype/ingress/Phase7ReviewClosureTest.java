package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * Phase VII review closure: F2 (preflight collapsed uncoded failures into INSPECTION_UNAVAILABLE with no cause) and the
 * DeploymentTransition.fileSha bound (checked only before reading, not while a growing file is hashed).
 */
public final class Phase7ReviewClosureTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}
    static final class Endless extends InputStream {
        long left;Endless(long n){left=n;}
        public int read(){if(left<=0)return -1;left--;return 7;}
        public int read(byte[] b,int off,int len){if(left<=0)return -1;int n=(int)Math.min(len,left);Arrays.fill(b,off,off+n,(byte)7);left-=n;return n;}
    }
    static OfflineCandidatePreflight.Report failing(Phase6PreflightTest.Fixture f,final Exception e){
        return OfflineCandidatePreflight.inspect(true,f.layout,0,f.manifest,f.admission,f.jars,f.expected,Long.MAX_VALUE,new WatermarkV2Prototype.ReadOpener(){
            public WatermarkV2Prototype.ReadHandle open(Path source)throws IOException{if(e instanceof IOException)throw (IOException)e;throw (RuntimeException)e;}});
    }

    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);

        // ---- fileSha bound is enforced on bytes actually read.
        byte[] small=new byte[1000];Arrays.fill(small,(byte)7);
        check(DeploymentTransition.boundedSha(new Endless(1000),1000).equals(DeploymentTransition.sha(small)),"exactly the bound hashes normally");
        String refused;try{DeploymentTransition.boundedSha(new Endless(1001),1000);refused="ACCEPTED";}catch(DeploymentTransition.Refusal r){refused=r.getMessage();}
        check("FILE_BOUND".equals(refused),"stream longer than the bound is refused while reading ["+refused+"]");

        // ---- F2: uncoded preflight failures keep a code-only cause; free text never reaches the JSON.
        Phase6PreflightTest.Fixture f=new Phase6PreflightTest.Fixture();
        String secret=f.source.home.toString();
        OfflineCandidatePreflight.Report text=failing(f,new IllegalStateException("free text at "+secret));
        check("INSPECTION_UNAVAILABLE".equals(text.reason)&&!text.prerequisitesMatched,"uncoded failure still INSPECTION_UNAVAILABLE ["+text.reason+"]");
        check(text.json().matches(".*\"inspection_cause\":\"[A-Za-z]*(Exception|Error)\".*"),"free-text failure reports only an exception class "+text.json());
        check(!text.json().contains(secret)&&!text.json().contains("free text"),"free text and paths are not exposed");
        // A coded reader failure is absorbed by IngressDiagnostics and re-thrown by the adapter as free text without a cause:
        // end to end only the class survives (documented limit). The cause walk itself keeps the first code in a chain.
        OfflineCandidatePreflight.Report coded=failing(f,new IOException("SYNTHETIC_READER_FAULT"));
        check("INSPECTION_UNAVAILABLE".equals(coded.reason)&&coded.json().contains("\"inspection_cause\":\"IOException\""),"coded reader fault absorbed by diagnostics surfaces as its class "+coded.json());
        check("SYNTHETIC_CODE".equals(OfflineCandidatePreflight.cause(new IOException("wrapper at "+secret,new IllegalStateException("SYNTHETIC_CODE")))),"cause chain keeps the first stable code");
        check("IllegalArgumentException".equals(OfflineCandidatePreflight.cause(new IOException("wrapper",new IllegalArgumentException("free text")))),"uncoded chain reports the innermost class");
        String j=coded.json();
        check(j.contains("\"state\":\"BLOCKED\"")&&j.contains("\"launch_authorized\":false")&&j.contains("\"production_authorized\":false")&&j.contains("\"ack_eligible\":false"),"failure stays BLOCKED, no launch/production/ACK");
        OfflineCandidatePreflight.Report ok=f.inspect();
        check(!ok.json().contains("inspection_cause"),"successful preflight carries no inspection_cause ["+ok.reason+"]");
        System.out.println("Phase VII review closure checks="+checks);
    }
}
