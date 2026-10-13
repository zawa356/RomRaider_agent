package net.neristal.rrbridge;
import java.util.concurrent.atomic.AtomicLong;
public final class LinkMonitor {
 static final LinkMonitor INSTANCE=new LinkMonitor();
 final AtomicLong errors=new AtomicLong(), connects=new AtomicLong();
 void ensureAttached(){}
}
