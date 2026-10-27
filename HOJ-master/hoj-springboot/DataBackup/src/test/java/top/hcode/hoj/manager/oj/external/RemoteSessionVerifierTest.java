package top.hcode.hoj.manager.oj.external;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.remote.RemoteSession;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteSessionVerifierTest {
    @Test void nowcoderChecksTheSignedInOwnerRatherThanTheViewedPublicProfile(){
        String header="<script>\nwindow.isLogin = true;\nwindow.globalInfo = {ownerId:'12345'};\n</script><script>window.curUser.id='99999';</script>";
        verifier.identity("NOWCODER","12345",header);
        assertEquals(403,assertThrows(ObjectiveException.class,()->verifier.identity("NOWCODER","99999",header)).getStatus());
        assertEquals(401,assertThrows(ObjectiveException.class,()->verifier.identity("NOWCODER","12345",header.replace("= true","= false"))).getStatus());
        assertEquals(502,assertThrows(ObjectiveException.class,()->verifier.identity("NOWCODER","12345",header.replace("ownerId:'12345'","ownerId:getUserId()"))).getStatus());
    }
    @Test void nowcoderUsesTheFixedHomeEndpointAndItsSharedSourceGate(){
        List<String> urls=new ArrayList<>();RemoteSessionVerifier stub=new RemoteSessionVerifier(){
            @Override protected void rateGate(long id,String platform){assertEquals("NOWCODER",platform);}
            @Override protected String read(String url,RemoteSession session){urls.add(url);return "<script>\nwindow.isLogin = true;\nwindow.globalInfo = {};\nwindow.globalInfo.ownerId = '12345';\n</script>";}
        };
        try{stub.verify(5,"NOWCODER","12345",RemoteSession.parse("NOWCODER","t=fake-login"));assertEquals(Collections.singletonList("https://ac.nowcoder.com/"),urls);}finally{stub.close();}
        StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);when(values.setIfAbsent(anyString(),anyString(),anyLong(),any())).thenReturn(true);ReflectionTestUtils.setField(verifier,"redis",redis);
        verifier.rateGate(5,"NOWCODER");verify(values).setIfAbsent(eq("external:nowcoder:public-http"),anyString(),eq(3L),any());verify(values,never()).setIfAbsent(eq("external:cf:public-api"),anyString(),anyLong(),any());
    }
    RemoteSessionVerifier verifier;
    String cf(String name){return "<div class='lang-chooser'><a href='/profile/"+name+"'>"+name+"</a><a href='/logout?csrf_token=fake'>Logout</a></div>";}
    String at(String name){return "<div id='navbar-collapse'><ul class='navbar-right'><span class='username'>"+name+"</span></ul></div><form method='POST' action='/logout'></form>";}
    @BeforeEach void setup(){verifier=new RemoteSessionVerifier();}
    @AfterEach void close(){verifier.close();}

    @Test void onlySourceSessionCookiesAreRetainedAndDiagnosticsAreRedacted(){
        assertEquals("REVEL_SESSION=synthetic",RemoteSession.parse("ATCODER","Cookie: tracking=discard; REVEL_SESSION=synthetic; preference=discard").cookieHeader());
        RemoteSession session=RemoteSession.parse("CODEFORCES","39ce7=fake-persistent; JSESSIONID=fake-session; cf_clearance=fake-clearance");
        assertEquals("JSESSIONID=fake-session; 39ce7=fake-persistent; cf_clearance=fake-clearance",session.cookieHeader());assertFalse(session.toString().contains("fake-"));
    }
    @Test void missingCookieForeignPlatformHeaderInjectionAndDuplicateNamesAreRejected(){
        for(String cookie:Arrays.asList("", "REVEL_SESSION=wrong-platform", "JSESSIONID=first; JSESSIONID=second", "JSESSIONID=value\r\nHost: elsewhere", "JSESSIONID=value\\bad", "JSESSIONID=", "JSESSIONID=值", String.join("",Collections.nCopies(8193,"x"))))
            assertThrows(IllegalArgumentException.class,()->RemoteSession.parse("CODEFORCES",cookie));
        assertThrows(IllegalArgumentException.class,()->RemoteSession.parse("NOWCODER","JSESSIONID=synthetic"));
    }
    @Test void authenticatedNavigationMustContainOneMatchingIdentity(){
        verifier.identity("CODEFORCES","tourist",cf("Tourist"));verifier.identity("ATCODER","tourist",at("Tourist"));
        assertEquals(403,assertThrows(ObjectiveException.class,()->verifier.identity("CODEFORCES","another",cf("Tourist"))).getStatus());
        assertEquals(403,assertThrows(ObjectiveException.class,()->verifier.identity("ATCODER","another",at("Tourist"))).getStatus());
    }
    @Test void publicProfilesLoginPagesChallengePagesAndAmbiguousMarkupCannotProveLogin(){
        for(String html:Arrays.asList("<a href='/profile/tourist'>tourist</a>","<form action='/enter'>Login</form>","<title>Just a moment</title>",cf("Tourist")+cf("Another")))
            assertEquals(401,assertThrows(ObjectiveException.class,()->verifier.identity("CODEFORCES","tourist",html)).getStatus());
        assertEquals(401,assertThrows(ObjectiveException.class,()->verifier.identity("ATCODER","tourist",at("Tourist").replace("action='/logout'","action='https://other.invalid/logout'"))).getStatus());
    }
    @Test void platformSelectsOnlyTheFixedProtectedEndpoint(){
        assertEquals(400,assertThrows(ObjectiveException.class,()->verifier.verify(1,"ATCODER","tourist",RemoteSession.parse("CODEFORCES","JSESSIONID=fake"))).getStatus());
        List<String> urls=new ArrayList<>();RemoteSessionVerifier stub=new RemoteSessionVerifier(){
            @Override protected void rateGate(long id){}
            @Override protected String read(String url,RemoteSession session){urls.add(url);return url.contains("atcoder.jp")?at("Tourist"):cf("Tourist");}
        };
        try{stub.verify(1,"CODEFORCES","tourist",RemoteSession.parse("CODEFORCES","JSESSIONID=fake"));stub.verify(3,"ATCODER","tourist",RemoteSession.parse("ATCODER","REVEL_SESSION=fake"));assertEquals(Arrays.asList("https://codeforces.com/settings/general?locale=en","https://atcoder.jp/settings?lang=en"),urls);}finally{stub.close();}
    }
    @Test void rateLimitAndRedisFailureStopBeforeAnySourceRead(){
        StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);ReflectionTestUtils.setField(verifier,"redis",redis);
        when(values.setIfAbsent(anyString(),anyString(),anyLong(),any())).thenReturn(false);
        assertEquals(429,assertThrows(ObjectiveException.class,()->verifier.rateGate(1)).getStatus());
        when(values.setIfAbsent(anyString(),anyString(),anyLong(),any())).thenThrow(new IllegalStateException("synthetic-private-error"));
        ObjectiveException failure=assertThrows(ObjectiveException.class,()->verifier.rateGate(1));assertEquals(503,failure.getStatus());assertFalse(failure.getMessage().contains("synthetic-private-error"));
    }
    @Test void transportSendsCookiesToOneRequestAndNeverFollowsRedirectsOrRetries() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger redirected=new AtomicInteger(),attempts=new AtomicInteger();
        server.createContext("/redirect",exchange->{attempts.incrementAndGet();assertEquals("JSESSIONID=fake",exchange.getRequestHeaders().getFirst("Cookie"));exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/leak");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/leak",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try{assertEquals(401,assertThrows(ObjectiveException.class,()->verifier.read("http://127.0.0.1:"+server.getAddress().getPort()+"/redirect",RemoteSession.parse("CODEFORCES","JSESSIONID=fake"))).getStatus());assertEquals(1,attempts.get());assertEquals(0,redirected.get());}finally{server.stop(0);}
    }
    @Test void transportBoundsResponsesAndDoesNotExposeSourceErrors() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/blocked",exchange->{exchange.sendResponseHeaders(403,-1);exchange.close();});
        server.createContext("/large",exchange->{byte[] body=new byte[2*1024*1024+1];exchange.getResponseHeaders().set("Content-Type","text/html");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/ok",exchange->{byte[] body=at("Tourist").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/html;charset=utf-8");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();RemoteSession session=RemoteSession.parse("ATCODER","REVEL_SESSION=fake");
        try{assertEquals(503,assertThrows(ObjectiveException.class,()->verifier.read(base+"/blocked",session)).getStatus());assertEquals(502,assertThrows(ObjectiveException.class,()->verifier.read(base+"/large",session)).getStatus());verifier.identity("ATCODER","tourist",verifier.read(base+"/ok",session));}finally{server.stop(0);}
    }
}
