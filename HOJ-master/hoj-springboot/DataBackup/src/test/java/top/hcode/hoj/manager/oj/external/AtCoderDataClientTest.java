package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class AtCoderDataClientTest {
    AtCoderDataClient client;HttpServer server;String body="[]";int status=200;boolean gzip;List<String> requests=new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));assertNull(exchange.getRequestHeaders().getFirst("Cookie"));requests.add(exchange.getRequestURI().toString());
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);if(gzip){ByteArrayOutputStream output=new ByteArrayOutputStream();try(GZIPOutputStream compressed=new GZIPOutputStream(output)){compressed.write(bytes);}bytes=output.toByteArray();exchange.getResponseHeaders().set("Content-Encoding","gzip");}
            if(status==302)exchange.getResponseHeaders().set("Location","/redirected");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();client=new AtCoderDataClient(){@Override protected URI endpoint(String stage,String handle,long from){URI original=super.endpoint(stage,handle,from);return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+original.getRawPath()+(original.getRawQuery()==null?"":"?"+original.getRawQuery()));}};ReflectionTestUtils.setField(client,"json",new ObjectMapper());
    }
    @AfterEach void close(){client.close();server.stop(0);}
    @Test void officialProfileParsesAffiliationWithoutInventingRegistrationOrUnratedValues(){
        body="<a class='username' href='/users/Tourist'><span>Tourist</span></a><table><tr><th>Affiliation</th><td>School HOJ-code</td></tr><tr><th>Rating</th><td><span>2800</span> (Dan)</td></tr><tr><th>Highest Rating</th><td>3100</td></tr></table>";
        JsonNode profile=client.fetch("PROFILE","tourist",0).get(0);assertEquals(2800,profile.path("rating").asInt());assertEquals("School HOJ-code",profile.path("organization").asText());assertFalse(profile.has("registrationTimeSeconds"));assertEquals("/users/tourist?lang=en&contestType=algo",requests.get(0));
        body="<a class='username' href='/users/Tourist'>Tourist</a>";assertFalse(client.fetch("PROFILE","tourist",0).get(0).has("rating"));body="<a class='username' href='/users/other'>other</a>";assertThrows(ObjectiveException.class,()->client.fetch("PROFILE","tourist",0));
    }
    @Test void timestampAndCountRequestsPreserveLongCursorAndPublicSourceIdentity(){
        client.fetch("STATUS","tourist",2100000000L);body="{\"count\":500}";client.fetch("BOUNDARY","tourist",2100000000L);body="[]";client.fetch("RATING","tourist",0);
        assertEquals(Arrays.asList("/atcoder/atcoder-api/v3/user/submissions?user=tourist&from_second=2100000000","/atcoder/atcoder-api/v3/user/submission_count?user=tourist&from_second=2100000000&to_second=2100000001","/users/tourist/history/json?contestType=algo"),requests);
    }
    @Test void gzipIsDecodedAndSizeLimitAppliesAfterDecompression(){
        gzip=true;body="{\"abc100_a\":{\"difficulty\":1200}}";assertTrue(client.fetch("MODELS",null,0).isObject());body=String.join("",Collections.nCopies(5*1024*1024+1,"x"));assertThrows(ObjectiveException.class,()->client.fetch("STATUS","tourist",0));
    }
    @Test void redirectsInvalidQueriesAndMalformedSourceCannotBeAccepted(){
        status=302;assertThrows(ObjectiveException.class,()->client.fetch("STATUS","tourist",0));assertEquals(1,requests.size());status=200;body="{\"error\":\"upstream secret\"}";assertFalse(assertThrows(ObjectiveException.class,()->client.fetch("STATUS","tourist",0)).getMessage().contains("secret"));
        assertThrows(ObjectiveException.class,()->client.fetch("STATUS","x&token=secret",0));assertThrows(IllegalArgumentException.class,()->client.fetch("STATUS","tourist",-1));
    }
}
