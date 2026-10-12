package net.neristal.rrbridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * 検証用の受信サーバー（Telemetry API の試作）。RRBridge から TSV を受け、SQLite に縦持ちで保存する。
 * 将来は VPC 上で MariaDB に置き換える想定。スキーマは引継ぎ資料 4.3 の概念モデルに沿う。
 *   java -cp rrbridge.jar;sqlite-jdbc.jar net.neristal.rrbridge.Receiver [db] [bind] [port] [token]
 */
public final class Receiver {
    static Connection db;
    static String token = "";

    public static void main(String[] a) throws Exception {
        String dbPath = a.length > 0 ? a[0] : "telemetry.db";
        String bind = a.length > 1 ? a[1] : "127.0.0.1";
        int port = a.length > 2 ? Integer.parseInt(a[2]) : 18766;
        token = a.length > 3 ? a[3] : "";
        Class.forName("org.sqlite.JDBC");
        db = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        Statement st = db.createStatement();
        st.execute("PRAGMA journal_mode=WAL");
        st.execute("CREATE TABLE IF NOT EXISTS sessions(session TEXT PRIMARY KEY, first_received_ms INTEGER, last_received_ms INTEGER)");
        st.execute("CREATE TABLE IF NOT EXISTS parameters(session TEXT, segment INTEGER, param_id TEXT, name TEXT, units TEXT, PRIMARY KEY(session, segment, param_id))");
        st.execute("CREATE TABLE IF NOT EXISTS samples(id INTEGER PRIMARY KEY, session TEXT, segment INTEGER, seq INTEGER, t_ms INTEGER, received_ms INTEGER, UNIQUE(session, seq))");
        st.execute("CREATE TABLE IF NOT EXISTS measurements(sample_id INTEGER, param_id TEXT, value REAL, PRIMARY KEY(sample_id, param_id))");
        st.execute("CREATE TABLE IF NOT EXISTS events(session TEXT, segment INTEGER, t_ms INTEGER, text TEXT, received_ms INTEGER, UNIQUE(session, t_ms, text))");
        st.execute("CREATE INDEX IF NOT EXISTS samples_t ON samples(session, t_ms)");
        st.close();
        db.setAutoCommit(false);

        HttpServer s = HttpServer.create(new InetSocketAddress(bind, port), 0);
        s.createContext("/ingest", new HttpHandler() { public void handle(HttpExchange ex) throws IOException {
            if (!authorized(ex)) { reply(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            if (!"POST".equals(ex.getRequestMethod())) { reply(ex, 405, "{\"ok\":false}"); return; }
            String body = Util.read(ex.getRequestBody());
            try { reply(ex, 200, ingest(body)); }
            catch (Exception e) { try { db.rollback(); } catch (SQLException ignore) {} reply(ex, 500, "{\"ok\":false,\"error\":" + Util.q(e.toString()) + "}"); }
        }});
        s.createContext("/stats", new HttpHandler() { public void handle(HttpExchange ex) throws IOException {
            if (!authorized(ex)) { reply(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}"); return; }
            try { reply(ex, 200, stats()); } catch (Exception e) { reply(ex, 500, "{\"ok\":false,\"error\":" + Util.q(e.toString()) + "}"); }
        }});
        s.start();
        System.out.println("Receiver listening on " + bind + ":" + port + " db=" + new File(dbPath).getAbsolutePath());
    }

    static boolean authorized(HttpExchange ex) {
        if (token.isEmpty()) return true;
        return ("Bearer " + token).equals(ex.getRequestHeaders().getFirst("Authorization"));
    }

    static synchronized String ingest(String body) throws SQLException {
        long now = System.currentTimeMillis();
        int nS = 0, nM = 0, nE = 0, dup = 0;
        long maxLag = 0;
        PreparedStatement ps = db.prepareStatement("INSERT OR IGNORE INTO parameters VALUES(?,?,?,?,?)");
        PreparedStatement ss = db.prepareStatement("INSERT OR IGNORE INTO samples(session,segment,seq,t_ms,received_ms) VALUES(?,?,?,?,?)");
        PreparedStatement sid = db.prepareStatement("SELECT id FROM samples WHERE session=? AND seq=?");
        PreparedStatement ms = db.prepareStatement("INSERT OR IGNORE INTO measurements VALUES(?,?,?)");
        PreparedStatement es = db.prepareStatement("INSERT OR IGNORE INTO events VALUES(?,?,?,?,?)");
        PreparedStatement se = db.prepareStatement("INSERT INTO sessions VALUES(?,?,?) ON CONFLICT(session) DO UPDATE SET last_received_ms=excluded.last_received_ms");
        Set<String> sessions = new HashSet<String>();
        for (String line : body.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 4) continue;
            sessions.add(f[1]);
            if (f[0].equals("M") && f.length >= 6) {
                ps.setString(1, f[1]); ps.setInt(2, Integer.parseInt(f[2])); ps.setString(3, f[3]); ps.setString(4, f[4]); ps.setString(5, f[5]); ps.executeUpdate();
            } else if (f[0].equals("S") && f.length >= 6) {
                long t = Long.parseLong(f[4]);
                ss.setString(1, f[1]); ss.setInt(2, Integer.parseInt(f[2])); ss.setLong(3, Long.parseLong(f[3])); ss.setLong(4, t); ss.setLong(5, now);
                if (ss.executeUpdate() == 0) { dup++; continue; }
                nS++;
                maxLag = Math.max(maxLag, now - t);
                sid.setString(1, f[1]); sid.setLong(2, Long.parseLong(f[3]));
                ResultSet rs = sid.executeQuery(); rs.next(); long id = rs.getLong(1); rs.close();
                for (String kv : f[5].split(",")) {
                    int i = kv.indexOf('=');
                    if (i <= 0) continue;
                    ms.setLong(1, id); ms.setString(2, kv.substring(0, i)); ms.setDouble(3, Double.parseDouble(kv.substring(i + 1))); ms.addBatch(); nM++;
                }
                ms.executeBatch();
            } else if (f[0].equals("E") && f.length >= 5) {
                es.setString(1, f[1]); es.setInt(2, Integer.parseInt(f[2])); es.setLong(3, Long.parseLong(f[3])); es.setString(4, f[4]); es.setLong(5, now); es.executeUpdate(); nE++;
            }
        }
        for (String s : sessions) { se.setString(1, s); se.setLong(2, now); se.setLong(3, now); se.executeUpdate(); }
        db.commit();
        return "{\"ok\":true,\"samples\":" + nS + ",\"measurements\":" + nM + ",\"events\":" + nE + ",\"duplicates\":" + dup + ",\"maxLagMs\":" + maxLag + "}";
    }

    static synchronized String stats() throws SQLException {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"sessions\":[");
        Statement st = db.createStatement();
        ResultSet rs = st.executeQuery("SELECT s.session, COUNT(x.id), MIN(x.t_ms), MAX(x.t_ms), AVG(x.received_ms - x.t_ms), MAX(x.received_ms - x.t_ms) FROM sessions s LEFT JOIN samples x ON x.session=s.session GROUP BY s.session ORDER BY s.session");
        boolean first = true;
        while (rs.next()) {
            if (!first) sb.append(','); first = false;
            sb.append("{\"session\":").append(Util.q(rs.getString(1))).append(",\"samples\":").append(rs.getLong(2))
              .append(",\"firstT\":").append(rs.getLong(3)).append(",\"lastT\":").append(rs.getLong(4))
              .append(",\"avgLagMs\":").append(Math.round(rs.getDouble(5))).append(",\"maxLagMs\":").append(rs.getLong(6)).append('}');
        }
        rs.close();
        sb.append("],\"latest\":[");
        rs = st.executeQuery("SELECT p.name, p.units, m.value, x.t_ms FROM samples x JOIN measurements m ON m.sample_id=x.id JOIN parameters p ON p.session=x.session AND p.segment=x.segment AND p.param_id=m.param_id WHERE x.id=(SELECT MAX(id) FROM samples)");
        first = true;
        while (rs.next()) {
            if (!first) sb.append(','); first = false;
            sb.append("{\"name\":").append(Util.q(rs.getString(1))).append(",\"units\":").append(Util.q(rs.getString(2))).append(",\"value\":").append(rs.getDouble(3)).append(",\"t\":").append(rs.getLong(4)).append('}');
        }
        rs.close(); st.close();
        return sb.append("]}").toString();
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException { Util.reply(ex, code, body); }
}
