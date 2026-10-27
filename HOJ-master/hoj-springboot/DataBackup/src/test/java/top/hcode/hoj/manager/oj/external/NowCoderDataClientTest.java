package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class NowCoderDataClientTest {
    NowCoderDataClient client;HttpServer server;String body="";int status=200;boolean gzip;List<String> requests=new ArrayList<>();ObjectMapper json=new ObjectMapper();
    static String identity="<script>window.curUser.id = '12345'; window.curUser.isTeam = false;</script>";
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
            assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));assertEquals("GET",exchange.getRequestMethod());requests.add(exchange.getRequestURI().toString());
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);if(gzip){ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream compressed=new GZIPOutputStream(out)){compressed.write(bytes);}bytes=out.toByteArray();exchange.getResponseHeaders().set("Content-Encoding","gzip");}
            if(status==302)exchange.getResponseHeaders().set("Location","/redirected");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();client=new NowCoderDataClient(){@Override protected URI endpoint(String stage,String uid,int page){URI original=super.endpoint(stage,uid,page);return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+original.getRawPath()+(original.getRawQuery()==null?"":"?"+original.getRawQuery()));}};ReflectionTestUtils.setField(client,"json",json);
    }
    @AfterEach void close(){client.close();server.stop(0);}
    static String page(){
        return identity+"<div class='my-state-item'><div class='state-num'>1</div><span>题已挑战</span></div><div class='my-state-item'><div class='state-num'>1</div><span>题已通过</span></div><div class='my-state-item'><div class='state-num'>1</div><span>次提交</span></div>"+
                "<table class='table-hover'><thead><tr>"+String.join("",Collections.nCopies(9,"<th>header</th>"))+"</tr></thead><tbody><tr><td><a href='/acm/contest/view-submission?submissionId=99&amp;uid=12345&amp;returnHomeType=1'>99</a></td><td><a href='/acm/problem/218144'>Sample</a></td><td>答案正确</td><td>100</td><td>1</td><td>2</td><td>3</td><td>C++</td><td>2025-01-18 17:25:46</td></tr></tbody></table><div class='pagination'><ul data-total='1'><li class='active'><a data-page='1'>1</a></li></ul></div>";
    }
    @Test void officialProfileUsesNumericIdentityAndPublicBiography(){
        body=identity+"<a class='coder-name'>显示名称</a><div class='coder-brief'>School HOJ-proof</div><div class='status-item'><div class='state-num'>947</div><span>Rating</span></div>";
        JsonNode user=client.fetch("PROFILE","12345",1).get(0);assertEquals("12345",user.path("handle").asText());assertEquals("显示名称",user.path("displayName").asText());assertEquals("School HOJ-proof",user.path("organization").asText());assertEquals(947,user.path("rating").asInt());assertFalse(user.has("registrationTimeSeconds"));
        body=body.replace("947","not available");assertThrows(ObjectiveException.class,()->client.fetch("PROFILE","12345",1));
        assertThrows(ObjectiveException.class,()->client.fetch("PROFILE","name",1));assertThrows(ObjectiveException.class,()->client.fetch("PROFILE","001",1));assertThrows(ObjectiveException.class,()->client.fetch("PROFILE","1?uid=2",1));
    }
    @Test void profileIdentityMismatchTeamsDuplicateIdsAndTextSpoofingFailClosed(){
        String profile="<a class='coder-name'>Name</a><div class='coder-brief'>HOJ-proof</div>";
        for(String prefix:Arrays.asList(identity.replace("12345","99999"),identity.replace("false","true"),identity+identity,"<div>window.curUser.id = '12345'; window.curUser.isTeam = false;</div>")){
            body=prefix+profile;assertThrows(ObjectiveException.class,()->client.fetch("PROFILE","12345",1));
        }
    }
    @Test void officialIntegralDecimalRatingsAreAcceptedWithoutRounding() throws Exception {
        body="{\"code\":0,\"msg\":\"OK\",\"data\":[{\"contestId\":99785,\"rating\":947.0,\"rank\":1128,\"changeValue\":40.0,\"time\":1737111600000,\"contestName\":\"Sample\"}]}";
        JsonNode rows=client.fetch("RATING","12345",1);assertEquals(947,rows.get(0).path("newRating").asInt());assertEquals(907,rows.get(0).path("oldRating").asInt());assertEquals(1737111600L,rows.get(0).path("ratingUpdateTimeSeconds").asLong());assertEquals("/acm/contest/rating-history?uid=12345",requests.get(0));
        for(String field:Arrays.asList("rating","changeValue","rank","contestId","time")){
            ObjectNode invalid=(ObjectNode)json.readTree(body);((ObjectNode)invalid.path("data").get(0)).put(field,1.5);assertThrows(ObjectiveException.class,()->client.rating(invalid));
        }
        ObjectNode repeated=(ObjectNode)json.readTree(body);((ArrayNode)repeated.path("data")).add(repeated.path("data").get(0).deepCopy());assertThrows(ObjectiveException.class,()->client.rating(repeated));
    }
    @Test void regularPracticeUsesExplicitSubmissionLinksAndShanghaiTime(){
        body=page();JsonNode result=client.fetch("STATUS","12345",1);assertEquals(1,result.path("solved").asInt());assertEquals("nowcoder:acm:218144",result.path("items").get(0).path("problemKey").asText());assertEquals("OK",result.path("items").get(0).path("verdict").asText());assertEquals(1737192346L,result.path("items").get(0).path("submittedAt").asLong());
        assertTrue(requests.get(0).contains("page=1&pageSize=50"));assertTrue(requests.get(0).contains("statusTypeFilter=-1"));client.fetch("VERIFY","12345",7);assertEquals(requests.get(0),requests.get(1));
    }
    @Test void incompletePagesUnknownVerdictsBadIdentityAndExternalLinksCannotBecomeEvidence(){
        for(String value:Arrays.asList(page().replace("uid=12345","uid=777"),page().replace("答案正确","新状态"),page().replace("/acm/problem/218144","https://other.test/acm/problem/218144"),page().replace("data-total='1'","data-total='2'"),page().replace("<span>次提交</span>","<span>unknown</span>"),page().replace("2025-01-18","2025-02-30"),page().replace("<td>100</td>",""))){
            body=value;assertThrows(ObjectiveException.class,()->client.fetch("STATUS","12345",1));
        }
    }
    @Test void sourceProblemNamespacesStaySeparateAndOnlyExplicitLocalIdsMerge(){
        assertEquals("nowcoder:contest:11244:A",ProblemIdentity.nowcoder(URI.create("https://ac.nowcoder.com/acm/contest/11244/a")));
        assertEquals("nowcoder:acm:218144",ProblemIdentity.local(1,"NC-218144",true));assertEquals("nowcoder:contest:11244:A",ProblemIdentity.local(2,"NC-11244-a",true));
        assertEquals("nowcoder:practice:0123456789abcdef0123456789abcdef",ProblemIdentity.local(3,"NC-PRACTICE-0123456789ABCDEF0123456789ABCDEF",true));
        assertEquals("hoj:4",ProblemIdentity.local(4,"NC-218144",false));assertEquals("hoj:5",ProblemIdentity.local(5,"NC-001",true));
        for(String url:Arrays.asList("https://ac.nowcoder.com/acm/problem/218144?uid=2","https://other.test/acm/problem/218144","https://ac.nowcoder.com/acm/problem/%32","https://ac.nowcoder.com/acm/contest/1/../A"))assertThrows(IllegalArgumentException.class,()->ProblemIdentity.nowcoder(URI.create(url)));
    }
    @Test void redirectsAndOversizedDecodedBodiesFailWithoutRetry(){
        status=302;body="secret";assertThrows(ObjectiveException.class,()->client.fetch("STATUS","12345",1));assertEquals(1,requests.size());status=200;gzip=true;body=String.join("",Collections.nCopies(2*1024*1024+1,"x"));assertFalse(assertThrows(ObjectiveException.class,()->client.fetch("STATUS","12345",1)).getMessage().contains("secret"));assertEquals(2,requests.size());
    }
    @Test void savedOfficialReadOnlySamplesCanBeCheckedWithoutNetwork() throws Exception {
        String directory=System.getenv("HOJ_NOWCODER_SAMPLE_DIR");if(directory==null)return;
        assertEquals("399269849",client.profile(new String(Files.readAllBytes(Paths.get(directory,"profile.html")),StandardCharsets.UTF_8),"399269849").get(0).path("handle").asText());
        assertEquals(4,client.rating(json.readTree(Files.readAllBytes(Paths.get(directory,"rating.json")))).size());
        JsonNode rows=client.submissions(new String(Files.readAllBytes(Paths.get(directory,"practice.html")),StandardCharsets.UTF_8),"399269849",1);assertEquals(50,rows.path("items").size());assertEquals(84,rows.path("submissions").asInt());
    }
}
