package net.neristal.rrbridge;
import java.io.*;
import java.util.Properties;
public final class Util {
 static Properties loadProps(File f){return new Properties();}
 static String q(String s){return String.valueOf(s);}
 static String read(InputStream s) throws IOException {
  if(s==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();
  byte[] buf=new byte[512];int n;while((n=s.read(buf))!=-1)b.write(buf,0,n);
  return new String(b.toByteArray(),"UTF-8");
 }
 static String now(){return "synthetic"; }
 static void writeAtomic(File f,String content){try(FileWriter w=new FileWriter(f)){w.write(content);}
  catch(IOException e){throw new IllegalStateException(e);}}
}
