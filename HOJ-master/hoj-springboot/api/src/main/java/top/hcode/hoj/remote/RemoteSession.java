package top.hcode.hoj.remote;

import java.util.*;

/** A source-scoped, explicitly imported browser session. Never serialize or log its contents. */
public final class RemoteSession {
    private final String header;
    private final String platform;

    private RemoteSession(String platform, String header) { this.platform = platform; this.header = header; }

    public static RemoteSession parse(String platform, String input) {
        Set<String> allowed;
        String required;
        if ("CODEFORCES".equals(platform)) {
            allowed = new LinkedHashSet<>(Arrays.asList("JSESSIONID", "39ce7", "cf_clearance", "__cf_bm"));
            required = "JSESSIONID";
        } else if ("ATCODER".equals(platform)) {
            allowed = new LinkedHashSet<>(Arrays.asList("REVEL_SESSION", "cf_clearance", "__cf_bm"));
            required = "REVEL_SESSION";
        } else if ("NOWCODER".equals(platform)) {
            allowed = new LinkedHashSet<>(Arrays.asList("t", "csrf_token", "NOWCODERUID", "NOWCODERCLINETID", "acw_tc"));
            required = "t";
        } else throw new IllegalArgumentException("Unsupported session platform");
        if (input == null || input.isEmpty() || input.length() > 8192)
            throw new IllegalArgumentException("Invalid session size");
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c < 32 || c > 126) throw new IllegalArgumentException("Invalid session characters");
        }
        String value = input.trim();
        if (value.regionMatches(true, 0, "Cookie:", 0, 7)) value = value.substring(7).trim();
        Map<String,String> cookies = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        String[] pairs = value.split(";", -1);
        if (pairs.length > 64) throw new IllegalArgumentException("Too many cookies");
        for (String pair : pairs) {
            pair = pair.trim();
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            if (equals < 1) throw new IllegalArgumentException("Invalid cookie pair");
            String name = pair.substring(0, equals).trim(), secret = pair.substring(equals + 1).trim();
            if (!name.matches("[A-Za-z0-9_!#$%&'*+.^`|~-]{1,64}") || !seen.add(name))
                throw new IllegalArgumentException("Invalid or duplicate cookie name");
            String octets = secret;
            if (octets.length() >= 2 && octets.startsWith("\"") && octets.endsWith("\""))
                octets = octets.substring(1, octets.length() - 1);
            if (!octets.matches("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]*"))
                throw new IllegalArgumentException("Invalid cookie value");
            if (allowed.contains(name) && !octets.isEmpty()) cookies.put(name, secret);
        }
        if (!cookies.containsKey(required)) throw new IllegalArgumentException("Missing source session cookie");
        List<String> selected = new ArrayList<>();
        for (String name : allowed) if (cookies.containsKey(name)) selected.add(name + "=" + cookies.get(name));
        return new RemoteSession(platform, String.join("; ", selected));
    }

    /** Only pass to the fixed-origin source transport, never a user-selected URL. */
    public String cookieHeader() { return header; }
    public boolean belongsTo(String source) { return platform.equals(source); }
    boolean allowsCookie(String name) {
        return ("CODEFORCES".equals(platform) ? Arrays.asList("JSESSIONID", "39ce7", "cf_clearance", "__cf_bm")
                : "NOWCODER".equals(platform) ? Arrays.asList("t", "csrf_token", "NOWCODERUID", "NOWCODERCLINETID", "acw_tc")
                : Arrays.asList("REVEL_SESSION", "cf_clearance", "__cf_bm")).contains(name);
    }

    @Override public String toString() { return "RemoteSession[redacted]"; }
}
