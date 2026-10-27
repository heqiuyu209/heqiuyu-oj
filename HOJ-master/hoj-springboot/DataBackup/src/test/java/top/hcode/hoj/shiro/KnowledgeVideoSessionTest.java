package top.hcode.hoj.shiro;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.utils.JwtUtils;

import javax.servlet.http.Cookie;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeVideoSessionTest {
    private KnowledgeVideoSession sessions;
    private JwtUtils jwt;
    private ValueOperations<String,String> values;
    private Map<String,String> cache;
    private AtomicLong clock;
    private final String id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private final String token = "header.payload.signature";
    private String path;

    @SuppressWarnings("unchecked") @BeforeEach void setup() {
        clock = new AtomicLong(1000000L); sessions = new KnowledgeVideoSession() { @Override protected long now() { return clock.get(); } };
        jwt = mock(JwtUtils.class); StringRedisTemplate redis = mock(StringRedisTemplate.class); values = mock(ValueOperations.class);
        cache = new HashMap<>(); when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), eq(120L), eq(TimeUnit.SECONDS))).thenAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1)); return true; });
        when(values.get(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
        doAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1)); return null; }).when(values).set(anyString(), anyString(), eq(120L), eq(TimeUnit.SECONDS));
        Claims claims = new DefaultClaims(); claims.setSubject("alice"); claims.setExpiration(new Date(9999999999999L));
        when(jwt.getClaimByToken(token)).thenReturn(claims); when(jwt.hasToken("alice")).thenReturn(true);
        ReflectionTestUtils.setField(sessions, "jwt", jwt); ReflectionTestUtils.setField(sessions, "redis", redis); ReflectionTestUtils.setField(sessions, "json", new ObjectMapper());
        path = "/api/knowledge/documents/" + id + "/video";
    }

    private String grant(String target) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", target + "/access"); request.addHeader("Authorization", token);
        MockHttpServletResponse response = new MockHttpServletResponse(); Map<String,Object> result = sessions.grant(request, response, target, "alice");
        assertEquals(target, result.get("url")); assertEquals(120, result.get("expiresIn")); assertFalse(result.toString().contains(token));
        String cookie = response.getHeader("Set-Cookie"); assertTrue(cookie.contains("HttpOnly; SameSite=Strict")); assertTrue(cookie.contains("Path=" + target + ";"));
        assertFalse(cookie.contains(token)); return cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
    }

    private MockHttpServletRequest media(String method, String target, String handle) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, target); request.setCookies(new Cookie(KnowledgeVideoSession.COOKIE, handle)); return request;
    }

    @Test void shortCookieIsServerBoundToExactVideoGetAndNeverAuthorizesOtherRoutesOrPosts() {
        String handle = grant(path); assertTrue(handle.matches("[a-f0-9]{64}"));
        MockHttpServletRequest request = media("GET", path, handle); assertEquals(token, sessions.credential(request)); sessions.requireCurrent(request, "alice");
        for (String target : new String[] { "/api/admin/knowledge", "/api/knowledge/search", path + "/", path + ";x", path.replace(id, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), path.replace("/video", "/video-access") })
            assertNull(sessions.credential(media("GET", target, handle)), target);
        for (String method : new String[] { "POST", "PUT", "DELETE", "HEAD", "OPTIONS" }) assertNull(sessions.credential(media(method, path, handle)));
        clock.addAndGet(120000L); assertNull(sessions.credential(request));
    }

    @Test void headerAlwaysWinsAndQueryStringCannotBecomeCredentials() {
        String handle = grant(path); MockHttpServletRequest request = media("GET", path, handle);
        request.addHeader("Authorization", "invalid"); assertEquals("invalid", sessions.credential(request));
        assertEquals(401, assertThrows(ObjectiveException.class, () -> sessions.requireCurrent(request, "alice")).getStatus());
        MockHttpServletRequest query = new MockHttpServletRequest("GET", path); query.setParameter("token", token); assertNull(sessions.credential(query));
        MockHttpServletRequest blank = media("GET", path, handle); blank.addHeader("Authorization", ""); assertEquals("", sessions.credential(blank));
    }

    @Test void logoutDifferentIdentityAndExpiredJwtInvalidatePlaybackEvenBeforeCookieExpiry() {
        MockHttpServletRequest request = media("GET", path, grant(path));
        assertEquals(401, assertThrows(ObjectiveException.class, () -> sessions.requireCurrent(request, "bob")).getStatus());
        when(jwt.hasToken("alice")).thenReturn(false); assertEquals(401, assertThrows(ObjectiveException.class, () -> sessions.requireCurrent(request, "alice")).getStatus());
        when(jwt.hasToken("alice")).thenReturn(true); when(jwt.isTokenExpired(any())).thenReturn(true);
        assertEquals(401, assertThrows(ObjectiveException.class, () -> sessions.requireCurrent(request, "alice")).getStatus());
    }

    @Test void duplicateInvalidMissingAndUnavailableCookiesFailClosed() {
        String handle = grant(path); MockHttpServletRequest duplicate = media("GET", path, handle);
        duplicate.setCookies(new Cookie(KnowledgeVideoSession.COOKIE, handle), new Cookie(KnowledgeVideoSession.COOKIE, handle)); assertNull(sessions.credential(duplicate));
        assertNull(sessions.credential(media("GET", path, "bad"))); cache.clear(); assertNull(sessions.credential(media("GET", path, handle)));
        when(values.get(anyString())).thenThrow(new IllegalStateException("private redis detail")); assertNull(sessions.credential(media("GET", path, handle)));
    }

    @Test void grantingRequiresHeaderAndReturnsOnlyPathWhileHttpsCookieIsSecureAndClearable() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path + "-access"); MockHttpServletResponse response = new MockHttpServletResponse();
        assertEquals(401, assertThrows(ObjectiveException.class, () -> sessions.grant(request, response, path, "alice")).getStatus());
        request.addHeader("Authorization", token); request.setSecure(true); sessions.grant(request, response, path, "alice");
        assertTrue(response.getCookie(KnowledgeVideoSession.COOKIE).getSecure());
        MockHttpServletResponse cleared = new MockHttpServletResponse(); sessions.clear(request, cleared, path); assertTrue(cleared.getHeader("Set-Cookie").contains("Max-Age=0"));
        assertEquals(400, assertThrows(ObjectiveException.class, () -> sessions.clear(request, cleared, "/api/knowledge/search")).getStatus());
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenThrow(new IllegalStateException("private storage detail"));
        ObjectiveException error = assertThrows(ObjectiveException.class, () -> sessions.grant(request, response, path, "alice")); assertEquals(503, error.getStatus()); assertFalse(error.getMessage().contains("private"));
    }

    @Test void administrativeAndDraftCookiesRemainConfinedToTheirOwnResource() {
        for (String target : new String[] { "/api/admin/knowledge/video-imports/" + id + "/video", "/api/admin/knowledge/documents/" + id + "/versions/2/video" }) {
            String handle = grant(target); assertEquals(token, sessions.credential(media("GET", target, handle))); assertNull(sessions.credential(media("GET", path, handle)));
        }
    }

    @Test void actualJwtFilterCreatesNormalLoginTokenOnlyForScopedMediaCookie() throws Exception {
        JwtFilter filter = new JwtFilter(); ReflectionTestUtils.setField(filter, "videoSessions", sessions);
        String handle = grant(path); MockHttpServletResponse response = new MockHttpServletResponse();
        assertEquals(token, filter.createToken(media("GET", path, handle), response).getPrincipal());
        assertNull(filter.createToken(media("POST", path, handle), response));
        assertNull(filter.createToken(media("GET", "/api/admin/knowledge", handle), response));
    }

    @Test void authenticatedRefreshKeepsOngoingMediaHandleAliveWithoutMakingCookieAnAjaxCredential() {
        String handle = grant(path); MockHttpServletRequest ongoing = media("GET", path, handle);
        clock.addAndGet(119000L);
        MockHttpServletRequest refresh = media("POST", path + "/access", handle); assertNull(sessions.credential(refresh));
        refresh.addHeader("Authorization", token); MockHttpServletResponse response = new MockHttpServletResponse(); sessions.grant(refresh, response, path, "alice");
        assertEquals(handle, response.getCookie(KnowledgeVideoSession.COOKIE).getValue());
        clock.addAndGet(2000L); sessions.requireCurrent(ongoing, "alice");
        clock.addAndGet(118000L); assertNull(sessions.credential(ongoing));
    }
}
