package top.hcode.hoj.shiro;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.utils.JwtUtils;
import top.hcode.hoj.utils.ShiroUtils;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Browser media cannot attach Authorization. Only these exact GET routes accept this cookie. */
@Component
public class KnowledgeVideoSession {
    public static final String COOKIE = "HOJ_KB_VIDEO";
    public static final int TTL = 120;
    private static final String PREFIX = "knowledge:video:session:";
    private static final SecureRandom RANDOM = new SecureRandom();
    @Autowired private StringRedisTemplate redis;
    @Autowired private JwtUtils jwt;
    @Autowired private ObjectMapper json;

    protected long now() { return System.currentTimeMillis(); }

    public static boolean videoPath(String path) {
        return path != null && (path.matches("/api/knowledge/documents/[a-f0-9]{32}/video")
                || path.matches("/api/admin/knowledge/documents/[a-f0-9]{32}/versions/[1-9][0-9]{0,9}/video")
                || path.matches("/api/admin/knowledge/video-imports/[a-f0-9]{32}/video"));
    }

    private String path(HttpServletRequest request) {
        String context = request.getContextPath();
        String uri = request.getRequestURI();
        return context == null || context.isEmpty() ? uri : uri != null && uri.startsWith(context + "/") ? uri.substring(context.length()) : null;
    }

    private String cookie(HttpServletRequest request) {
        Cookie[] values = request.getCookies(); String result = null;
        if (values != null) for (Cookie value : values) if (COOKIE.equals(value.getName())) {
            if (result != null || value.getValue() == null || !value.getValue().matches("[a-f0-9]{64}")) return null;
            result = value.getValue();
        }
        return result;
    }

    private Map<String,Object> record(String handle) {
        if (handle == null) return null;
        try {
            String value = redis.opsForValue().get(PREFIX + handle);
            if (value == null || value.length() > 8192) return null;
            Map<String,Object> record = json.readValue(value, new TypeReference<Map<String,Object>>() {});
            return record.get("expires") instanceof Number && ((Number) record.get("expires")).longValue() > now() ? record : null;
        } catch (Exception error) { return null; }
    }

    /** Header always wins, including an explicitly blank/invalid header. No URL credentials. */
    public String credential(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null) return header;
        if (!"GET".equals(request.getMethod()) || !videoPath(path(request))) return null;
        Map<String,Object> record = record(cookie(request));
        return record != null && path(request).equals(record.get("path")) && record.get("token") instanceof String ? (String) record.get("token") : null;
    }

    public void requireCurrent(HttpServletRequest request, String uid) {
        String token = credential(request);
        if (token == null || token.trim().isEmpty() || token.length() > 4096) throw new ObjectiveException(401, "播放授权已失效，请重新打开视频");
        Claims claims = jwt.getClaimByToken(token);
        if (claims == null || claims.getExpiration() == null || jwt.isTokenExpired(claims.getExpiration())
                || !uid.equals(claims.getSubject()) || !jwt.hasToken(uid)) throw new ObjectiveException(401, "播放授权已失效，请重新打开视频");
    }

    public void requireAuthenticated(HttpServletRequest request) {
        if (ShiroUtils.getProfile() == null) throw new ObjectiveException(401, "请先登录");
        requireCurrent(request, ShiroUtils.getProfile().getUid());
    }

    private void setCookie(HttpServletRequest request, HttpServletResponse response, String path, String value, int seconds) {
        if (!videoPath(path)) throw new ObjectiveException(400, "视频路径无效");
        String context = request.getContextPath() == null ? "" : request.getContextPath();
        // Only the configured servlet context and fixed route are allowed in a Set-Cookie header.
        if (!context.matches("(/[A-Za-z0-9_-]+)*")) throw new ObjectiveException(503, "视频播放路径尚未配置");
        response.addHeader("Set-Cookie", COOKIE + "=" + value + "; Path=" + context + path + "; Max-Age=" + seconds
                + "; HttpOnly; SameSite=Strict" + (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")) ? "; Secure" : ""));
        response.setHeader("Cache-Control", "no-store");
    }

    /** Caller checks the resource ACL first; granting is always an authenticated AJAX POST. */
    public Map<String,Object> grant(HttpServletRequest request, HttpServletResponse response, String path, String uid) {
        if (!"POST".equals(request.getMethod()) || request.getHeader("Authorization") == null || !videoPath(path))
            throw new ObjectiveException(401, "请重新获取视频播放授权");
        requireCurrent(request, uid);
        // /video/access receives the /video cookie, but POST still requires the
        // normal Authorization header. Refresh this same handle so an ongoing
        // range request can observe renewal instead of breaking every 2 minutes.
        String handle = cookie(request); Map<String,Object> previous = record(handle);
        boolean renew = previous != null && path.equals(previous.get("path")) && uid.equals(previous.get("uid"));
        if (!renew) {
            byte[] entropy = new byte[32]; RANDOM.nextBytes(entropy); StringBuilder fresh = new StringBuilder();
            for (byte value : entropy) fresh.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            handle = fresh.toString();
        }
        Map<String,Object> record = new LinkedHashMap<>(); record.put("path", path); record.put("uid", uid); record.put("token", request.getHeader("Authorization")); record.put("expires", now() + TTL * 1000L);
        try {
            if (renew) redis.opsForValue().set(PREFIX + handle, json.writeValueAsString(record), TTL, TimeUnit.SECONDS);
            else if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + handle, json.writeValueAsString(record), TTL, TimeUnit.SECONDS)))
                throw new IllegalStateException();
        } catch (Exception error) { throw new ObjectiveException(503, "视频播放授权暂不可用，请稍后重试"); }
        setCookie(request, response, path, handle, TTL);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("url", (request.getContextPath() == null ? "" : request.getContextPath()) + path); result.put("expiresIn", TTL); return result;
    }

    public void clear(HttpServletRequest request, HttpServletResponse response, String path) { setCookie(request, response, path, "", 0); }
}
