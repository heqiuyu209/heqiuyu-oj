package top.hcode.hoj.remote;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real local HTTP; no source platform requests, passwords, or browser sessions. */
class PersonalRemoteTransportTest {
    HttpServer server;
    PersonalRemoteTransport http;
    AtomicInteger requests;
    List<String> order;
    List<ch.qos.logback.classic.Level> levels;
    final List<String> quiet = Arrays.asList("org.apache.http.wire", "org.apache.http.headers");
    @BeforeEach void setup() throws Exception {
        levels = new ArrayList<>();
        for (String name : quiet) {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(name);
            levels.add(logger.getLevel()); logger.setLevel(ch.qos.logback.classic.Level.OFF);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        requests = new AtomicInteger(); order = Collections.synchronizedList(new ArrayList<>()); server.start();
        http = new PersonalRemoteTransport(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), RemoteSession.parse("CODEFORCES", "JSESSIONID=synthetic-initial"), () -> order.add("authorize"));
    }
    @AfterEach void close() {
        if (http != null) http.close(); if (server != null) server.stop(0);
        for (int i = 0; i < quiet.size(); i++) ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(quiet.get(i))).setLevel(levels.get(i));
    }
    void reply(HttpExchange exchange, int status, String text) throws java.io.IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    @Test void postBarrierPrecedesRealIoAndA200ErrorPageIsNotRetried() {
        server.createContext("/submit", exchange -> { requests.incrementAndGet(); order.add("io"); assertEquals("POST", exchange.getRequestMethod()); reply(exchange, 200, "synthetic rejected submission"); });
        PersonalRemoteTransport.Reply result = http.postCode("/submit", Collections.singletonMap("source", "synthetic code"), () -> order.add("barrier"));
        assertEquals(200, result.status); assertEquals(Arrays.asList("authorize", "barrier", "io"), order); assertEquals(1, requests.get());
        assertFalse(result.toString().contains("synthetic"));
    }
    @Test void failedBarrierPreventsPhysicalRequest() {
        server.createContext("/submit", exchange -> { requests.incrementAndGet(); reply(exchange, 200, "unexpected"); });
        assertThrows(IllegalStateException.class, () -> http.postCode("/submit", Collections.singletonMap("source", "synthetic"), () -> { throw new IllegalStateException("stale"); }));
        assertEquals(0, requests.get());
    }
    @Test void codePostRedirectIsReturnedWithoutFollowingItOrReposting() {
        server.createContext("/submit", exchange -> { requests.incrementAndGet(); exchange.getResponseHeaders().add("Location", "/other"); reply(exchange, 302, "redirect"); });
        server.createContext("/other", exchange -> { requests.addAndGet(100); reply(exchange, 200, "unexpected"); });
        assertEquals(302, http.postCode("/submit", Collections.singletonMap("source", "synthetic"), () -> {}).status); assertEquals(1, requests.get());
        assertEquals(RemoteSourceFailure.Kind.AUTH_REQUIRED, assertThrows(RemoteSourceFailure.class, () -> http.get("/submit")).kind); assertEquals(2, requests.get());
    }
    @Test void lostPostResponseDoesNotTriggerHttpClientAutomaticRetry() {
        server.createContext("/submit", exchange -> { requests.incrementAndGet(); exchange.getRequestBody().close(); exchange.close(); });
        assertEquals(RemoteSourceFailure.Kind.UNAVAILABLE, assertThrows(RemoteSourceFailure.class, () -> http.postCode("/submit", Collections.singletonMap("source", "synthetic"), () -> {})).kind); assertEquals(1, requests.get());
    }
    @Test void responseCapAndSourceChallengeFailClosed() {
        server.createContext("/large", exchange -> reply(exchange, 200, String.join("", Collections.nCopies(2100, String.join("", Collections.nCopies(1024, "x"))))));
        server.createContext("/blocked", exchange -> reply(exchange, 429, "synthetic challenge"));
        assertEquals(RemoteSourceFailure.Kind.UNAVAILABLE, assertThrows(RemoteSourceFailure.class, () -> http.get("/large")).kind);
        assertEquals(RemoteSourceFailure.Kind.CHALLENGE, assertThrows(RemoteSourceFailure.class, () -> http.get("/blocked")).kind);
    }
    @Test void fixedOriginRejectsAlternateHostsAndInvalidPathsBeforeSendingCookies() {
        for (String path : Arrays.asList("https://other.invalid/", "//other.invalid/", "/\\other.invalid/", "/ok\r\nX:1", "/ok#secret")) assertThrows(IllegalArgumentException.class, () -> http.get(path));
        assertTrue(order.isEmpty());
    }
    @Test void sourceCookieRotationIsScopedAndPersistedBeforeTheNextRequest() {
        AtomicReference<RemoteSession> saved = new AtomicReference<>(); http.onSessionUpdate(saved::set);
        server.createContext("/rotate", exchange -> { exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=synthetic-rotated; Path=/; HttpOnly"); exchange.getResponseHeaders().add("Set-Cookie", "unrelated=discard; Path=/"); reply(exchange, 200, "ok"); });
        server.createContext("/check", exchange -> { assertEquals("JSESSIONID=synthetic-rotated", exchange.getRequestHeaders().getFirst("Cookie")); reply(exchange, 200, "ok"); });
        assertEquals("ok", http.get("/rotate")); assertEquals("JSESSIONID=synthetic-rotated", saved.get().cookieHeader()); assertEquals("ok", http.get("/check"));
    }
    @Test void cookieDeletionOrForeignDomainDoesNotSilentlyBecomeAnotherAuthorizedSession() {
        server.createContext("/delete", exchange -> { exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=gone; Path=/; Max-Age=0"); reply(exchange, 200, "ok"); });
        server.createContext("/foreign", exchange -> { exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=foreign; Domain=other.invalid; Path=/"); reply(exchange, 200, "ok"); });
        assertEquals(RemoteSourceFailure.Kind.AUTH_REQUIRED, assertThrows(RemoteSourceFailure.class, () -> http.get("/delete")).kind);
        assertEquals(RemoteSourceFailure.Kind.AUTH_REQUIRED, assertThrows(RemoteSourceFailure.class, () -> http.get("/foreign")).kind);
    }
}
