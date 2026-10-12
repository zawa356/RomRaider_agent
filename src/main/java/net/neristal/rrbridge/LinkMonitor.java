package net.neristal.rrbridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RomRaider の log4j 出力（rr_system.log に出ているもの）を横取りして、ECUとの通信状態を数える。
 *
 * RomRaider は通信の成否を log4j にしか出さない（API がない）。そこで log4j 1.2 の Appender を
 * java.lang.reflect.Proxy で実装してルートロガーに追加する。log4j をコンパイル時に参照しないため、
 * rrbridge.jar に依存を増やさずに済む（実行時は RomRaider のクラスパスにある log4j-1.2.14 を使う）。
 *
 * 数えるもの（メッセージ文字列で判定。RomRaider 1.1.0 の実際のログ文言に合わせている。docs/04 §7）:
 *   connects   "connection initialised"            J2534 接続の確立（ECU 応答なしでも出る）
 *   ecuInits   "ECU ID = xxxx"                      ECU が応答した
 *   errors     レベル ERROR かつ "readMsg" / "timeout" / "Error sending init" / "Error occurred"
 *   lastError  直近のエラー文言（例外メッセージを含む）
 *
 * RomRaider は lib/log4j.properties を configureAndWatch しているため、設定の再読込でアペンダーが外れることがある。
 * ensureAttached() を定期的に呼んで付け直す。
 */
final class LinkMonitor {
    static final LinkMonitor INSTANCE = new LinkMonitor();

    final AtomicLong connects = new AtomicLong(), ecuInits = new AtomicLong(), errors = new AtomicLong();
    volatile String ecuId = "", lastError = "", lastErrorAt = "", attachResult = "not attached";
    volatile long lastErrorMs = 0, lastEcuInitMs = 0;
    private Object appender;

    /** ルートロガーに付いていなければ付ける。log4j が無い環境（試験）では何もしない */
    synchronized void ensureAttached() {
        try {
            Class<?> loggerCls = Class.forName("org.apache.log4j.Logger");
            Class<?> appenderCls = Class.forName("org.apache.log4j.Appender");
            Object root = loggerCls.getMethod("getRootLogger").invoke(null);
            if (appender == null) {
                appender = Proxy.newProxyInstance(appenderCls.getClassLoader(), new Class<?>[]{appenderCls}, new Handler());
            }
            boolean attached = (Boolean) loggerCls.getMethod("isAttached", appenderCls).invoke(root, appender);
            if (!attached) {
                loggerCls.getMethod("addAppender", appenderCls).invoke(root, appender);
                attachResult = "attached " + Util.now();
            }
        } catch (Throwable e) {
            attachResult = "attach failed: " + e;
        }
    }

    /** log4j の LoggingEvent を1件処理する（Proxy から呼ばれる。例外を外に出さない） */
    void onEvent(Object loggingEvent) {
        try {
            String msg = String.valueOf(loggingEvent.getClass().getMethod("getRenderedMessage").invoke(loggingEvent));
            Object level = loggingEvent.getClass().getMethod("getLevel").invoke(loggingEvent);
            String throwMsg = "";
            Object ti = loggingEvent.getClass().getMethod("getThrowableInformation").invoke(loggingEvent);
            if (ti != null) {
                Object t = ti.getClass().getMethod("getThrowable").invoke(ti);
                if (t != null) throwMsg = String.valueOf(((Throwable) t).getMessage());
            }
            classify(String.valueOf(level), msg, throwMsg, System.currentTimeMillis());
        } catch (Throwable ignore) {}
    }

    /** 判定本体（試験から直接呼ぶ） */
    void classify(String level, String msg, String throwMsg, long now) {
        if (msg == null) return;
        if (msg.contains("connection initialised")) connects.incrementAndGet();
        int i = msg.indexOf("ECU ID = ");
        if (i >= 0) {
            ecuInits.incrementAndGet();
            ecuId = msg.substring(i + 9).trim();
            lastEcuInitMs = now;
        }
        boolean commError = "ERROR".equals(level) && (msg.contains("readMsg") || msg.contains("timeout")
                || msg.contains("Error sending init") || msg.contains("Error occurred")
                || (throwMsg != null && (throwMsg.contains("readMsg") || throwMsg.contains("timeout"))));
        if (commError) {
            long n = errors.incrementAndGet();
            String text = (msg + (throwMsg == null || throwMsg.isEmpty() ? "" : " / " + throwMsg)).trim();
            lastError = text.length() > 300 ? text.substring(0, 300) : text;
            lastErrorAt = Util.fmt(new java.util.Date(now));
            // 転送ストリームにも残す（連続する場合は10秒に1回まで）
            if (now - lastErrorMs > 10000) Stream.INSTANCE.event("link_error count=" + n + " " + lastError);
            lastErrorMs = now;
        }
    }

    /** 直近 windowMs 内にエラーがあったか */
    boolean recentError(long windowMs) { return lastErrorMs > 0 && System.currentTimeMillis() - lastErrorMs < windowMs; }

    String statusJson() {
        return "{\"attach\":" + Util.q(attachResult) + ",\"ecuId\":" + Util.q(ecuId)
                + ",\"connects\":" + connects.get() + ",\"ecuInits\":" + ecuInits.get() + ",\"errors\":" + errors.get()
                + ",\"lastError\":" + Util.q(lastError) + ",\"lastErrorAt\":" + Util.q(lastErrorAt) + "}";
    }

    private final class Handler implements InvocationHandler {
        private volatile String name = "RRBridgeLinkMonitor";
        public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            if (n.equals("doAppend")) { onEvent(args[0]); return null; }
            if (n.equals("getName")) return name;
            if (n.equals("setName")) { name = (String) args[0]; return null; }
            if (n.equals("requiresLayout")) return Boolean.FALSE;
            if (n.equals("equals")) return proxy == args[0];
            if (n.equals("hashCode")) return System.identityHashCode(proxy);
            if (n.equals("toString")) return "RRBridgeLinkMonitor";
            return null; // addFilter, clearFilters, close, getFilter, getLayout, getErrorHandler, setLayout, setErrorHandler
        }
    }
}
