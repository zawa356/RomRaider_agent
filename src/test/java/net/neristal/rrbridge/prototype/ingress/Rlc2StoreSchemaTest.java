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
        System.out.println("RLC2 store schema passed "+checks+" checks");
    }
}
