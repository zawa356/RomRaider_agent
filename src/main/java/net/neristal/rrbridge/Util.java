package net.neristal.rrbridge;

import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

/**
 * RomRaiderに依存しない共通処理。受信サーバー（rrreceiver.jar）単体でも使うため、
 * このクラスから com.romraider.* や Agent/Stream を参照してはいけない。
 */
final class Util {
    private Util() {}

    /** JSON文字列リテラル（null は null） */
    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        OutputStream o = ex.getResponseBody(); o.write(b); o.close();
    }

    /** 一時ファイルに書いてから置き換える。Windowsでも MoveFileEx(REPLACE_EXISTING) で置換される */
    static void writeAtomic(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            try { w.write(s); } finally { w.close(); }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // 読み取り側が開いている等で失敗したら通常の置換を試す（それも失敗すれば次回に持ち越し）
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            tmp.delete();
        }
    }

    static Properties loadProps(File f) {
        Properties p = new Properties();
        if (f == null || !f.isFile()) return p;
        try { InputStream in = new FileInputStream(f); try { p.load(in); } finally { in.close(); } } catch (IOException ignore) {}
        return p;
    }

    static long longProp(Properties p, String key, long def) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    static String now() { return fmt(new Date()); }
    static String fmt(Date d) { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(d); }

    /** ファイルが maxBytes を超えていたら <name>.1 に退避する（1世代のみ） */
    static void rotateIfLarge(File f, long maxBytes) {
        if (maxBytes <= 0 || !f.isFile() || f.length() < maxBytes) return;
        File old = new File(f.getPath() + ".1");
        old.delete();
        f.renameTo(old);
    }

    /** Bearer トークンの照合（token が空なら常に許可） */
    static boolean authorized(HttpExchange ex, String token) {
        if (token == null || token.isEmpty()) return true;
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.equals("Bearer " + token);
    }
}
