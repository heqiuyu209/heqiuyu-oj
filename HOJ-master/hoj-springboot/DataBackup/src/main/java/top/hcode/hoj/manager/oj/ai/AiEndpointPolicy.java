package top.hcode.hoj.manager.oj.ai;

import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;

@Component
public class AiEndpointPolicy {
    private final ExecutorService dns = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
        Thread thread = new Thread(task, "ai-endpoint-dns"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    @PreDestroy public void close() { dns.shutdownNow(); }
    public URI endpoint(String base) {
        try {
            if (base == null || base.length() > 512) throw new IllegalArgumentException();
            URI uri = new URI(base.trim());
            String host = uri.getHost(), path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || host.endsWith(".") || host.startsWith("[") || host.indexOf(':') >= 0 || !host.contains(".")
                    || host.matches("[0-9.]+") || host.toLowerCase(Locale.ROOT).endsWith(".localhost")
                    || uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 8443
                    || path != null && !path.matches("(?:/[A-Za-z0-9_~-]+)*/?")) throw new IllegalArgumentException();
            String cleanPath = path == null ? "" : path.replaceAll("/+$", "");
            if (cleanPath.endsWith("/chat/completions")) throw new IllegalArgumentException();
            return new URI("https", null, host.toLowerCase(Locale.ROOT), uri.getPort(), cleanPath + "/chat/completions", null, null);
        } catch (Exception e) { throw new ObjectiveException(400, "请填写 HTTPS API 基础地址（如 https://服务域名/v1），使用 443 或 8443 端口，不含密钥、查询参数或 chat/completions"); }
    }
    protected InetAddress[] lookup(String host) throws UnknownHostException {
        Future<InetAddress[]> future = null;
        try { future = dns.submit(() -> InetAddress.getAllByName(host)); return future.get(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new UnknownHostException("DNS interrupted"); }
        catch (Exception e) { throw new UnknownHostException("DNS unavailable"); }
        finally { if (future != null) future.cancel(true); }
    }
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = lookup(host);
        if (addresses.length == 0) throw new UnknownHostException("Unavailable API endpoint");
        for (InetAddress address : addresses) if (!isPublic(address)) throw new UnknownHostException("Non-public API endpoint");
        return addresses;
    }
    public boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            // Only global unicast; exclude documentation and transition ranges as well.
            return (bytes[0] & 0xe0) == 0x20 && !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 1 && (bytes[2] & 255) <= 0x1f)
                    && !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 2);
        }
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
        return a > 0 && a < 224 && a != 10 && a != 127 && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && b == 168)
                && !(a == 192 && b == 0) && !(a == 192 && b == 88 && c == 99) && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                && !(a == 203 && b == 0 && c == 113);
    }
}
