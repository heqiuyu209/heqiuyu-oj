package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CodeforcesDataClientTest {
    HttpServer server;CodeforcesDataClient client;List<String> requests;String body;int status;AtomicInteger redirected;
    @BeforeEach void setup() throws Exception {
        requests=new ArrayList<>();body="{\"status\":\"OK\",\"result\":[]}";status=200;redirected=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/",exchange -> {
            requests.add(exchange.getRequestURI().toString());assertNull(exchange.getRequestHeaders().getFirst("Authorization"));assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
            if(status==302)exchange.getResponseHeaders().set("Location","/redirected");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.createContext("/redirected",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});server.start();
        client=new CodeforcesDataClient(){@Override protected URI endpoint(String method,String query){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/"+method+"?"+query);}};
        ReflectionTestUtils.setField(client,"json",new ObjectMapper());
    }
    @AfterEach void close(){client.close();server.stop(0);}
    @Test void publicRequestsDisableHistoricHandlesAndNeverAskForSources(){
        client.fetch("PROFILE","a.b",1);client.fetch("RATING","a.b",1);client.fetch("STATUS","a.b",501);
        assertEquals(Arrays.asList("/api/user.info?handles=a.b&checkHistoricHandles=false&lang=en","/api/user.rating?handle=a.b","/api/user.status?handle=a.b&from=501&count=500"),requests);
    }
    @Test void verificationUsesTheSameAnonymousFirstStatusPage() {
        assertTrue(client.fetch("VERIFY_STATUS","tourist",1).isArray());
        assertEquals(Collections.singletonList("/api/user.status?handle=tourist&from=1&count=500"),requests);
    }
    @Test void redirectsAreNotFollowedAndRemoteErrorsAreNotReflected(){
        status=302;assertThrows(ObjectiveException.class,()->client.fetch("PROFILE","tourist",1));assertEquals(0,redirected.get());
        status=200;body="{\"status\":\"FAILED\",\"comment\":\"sensitive-upstream-text\"}";
        assertFalse(assertThrows(ObjectiveException.class,()->client.fetch("PROFILE","tourist",1)).getMessage().contains("sensitive"));
    }
    @Test void catalogueUsesPublicProblemsetObjectInsteadOfAccountArray(){
        body="{\"status\":\"OK\",\"result\":{\"problems\":[],\"problemStatistics\":[]}}";
        assertTrue(client.fetch("CATALOG",null,1).path("problems").isArray());
        assertEquals("/api/problemset.problems?lang=en",requests.get(0));
        body="{\"status\":\"OK\",\"result\":[]}";assertThrows(ObjectiveException.class,()->client.fetch("CATALOG",null,1));
    }
    @Test void oversizedOrMalformedPayloadsAndInvalidQueriesCannotBeImported(){
        body=String.join("",Collections.nCopies(5*1024*1024+1,"x"));assertThrows(ObjectiveException.class,()->client.fetch("STATUS","tourist",1));
        body="{\"status\":\"OK\",\"result\":{}}";assertThrows(ObjectiveException.class,()->client.fetch("STATUS","tourist",1));
        int count=requests.size();assertThrows(ObjectiveException.class,()->client.fetch("PROFILE","a&apiKey=x",1));assertThrows(IllegalArgumentException.class,()->client.fetch("STATUS","a",0));assertEquals(count,requests.size());
    }
}
