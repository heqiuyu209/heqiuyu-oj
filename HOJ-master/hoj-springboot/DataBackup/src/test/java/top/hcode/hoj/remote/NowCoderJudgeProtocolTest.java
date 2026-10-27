package top.hcode.hoj.remote;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static top.hcode.hoj.remote.RemoteSourceFailure.Kind.*;

class NowCoderJudgeProtocolTest {
    HttpServer server;PersonalRemoteTransport http;NowCoderJudgeProtocol protocol;ObjectMapper json=new ObjectMapper();
    NowCoderJudgeProtocol.Context context=new NowCoderJudgeProtocol.Context("12345","1338275","218144","","2","C++");
    String home="<script>\nwindow.isLogin=true;\nwindow.globalInfo={ownerId:'12345'};\n</script>",oauth="{\"success\":true,\"data\":{\"accessToken\":\"Bearer fake-access-token\"}}";
    String acknowledgement="{\"code\":0,\"data\":{\"id\":\"job-1\",\"submissionId\":999}}",status="{\"code\":0,\"data\":{\"id\":\"job-1\",\"submissionId\":999,\"status\":5,\"language\":\"C++\",\"rightHundredRate\":100,\"isSelfTest\":false}}";
    AtomicInteger posts=new AtomicInteger(),barriers=new AtomicInteger(),rotations=new AtomicInteger(),leaks=new AtomicInteger(),requests=new AtomicInteger();int postStatus=200;boolean dropped;JsonNode posted;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
            requests.incrementAndGet();String path=exchange.getRequestURI().getPath(),body;
            if("/".equals(path)){assertEquals("t=fake-login; csrf_token=fake-csrf; NOWCODERUID=fake-device; acw_tc=fake-edge",exchange.getRequestHeaders().getFirst("Cookie"));body=home;}
            else if("/oauth".equals(path)){assertEquals("t=fake-login; csrf_token=fake-csrf; NOWCODERUID=fake-device",exchange.getRequestHeaders().getFirst("Cookie"));assertEquals("https://d.nowcoder.com",exchange.getRequestHeaders().getFirst("Origin"));assertTrue(exchange.getRequestURI().getRawQuery().contains("token=fake-csrf"));exchange.getResponseHeaders().set("Set-Cookie","t=must-not-rotate; Path=/");body=oauth;}
            else if("/submit".equals(path)){
                posts.incrementAndGet();assertEquals(1,barriers.get());assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));assertEquals("POST",exchange.getRequestMethod());assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").startsWith("application/json"));
                posted=json.readTree(read(exchange.getRequestBody()));if(dropped){exchange.close();return;}if(postStatus==302)exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/leak");body=acknowledgement;
            }else if("/status".equals(path)){
                assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));String query=exchange.getRequestURI().getRawQuery();assertTrue(query.contains("id=job-1"));assertTrue(query.contains("token=fake-access-token"));assertFalse(query.contains("content="));assertFalse(query.contains("source="));body=status;
            }else{leaks.incrementAndGet();body="unreachable";}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders("/submit".equals(path)?postStatus:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();URI origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        http=new PersonalRemoteTransport(origin,RemoteSession.parse("NOWCODER","t=fake-login; csrf_token=fake-csrf; NOWCODERUID=fake-device; acw_tc=fake-edge"),()->{}){
            @Override protected URI nowcoderService(String service){super.nowcoderService(service);return origin.resolve("/"+service);}
        };http.onSessionUpdate(updated->rotations.incrementAndGet());protocol=new NowCoderJudgeProtocol(http);
    }
    @AfterEach void close(){http.close();server.stop(0);}
    static String read(InputStream input) throws IOException {ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] bytes=new byte[2048];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);return new String(output.toByteArray(),StandardCharsets.UTF_8);}
    NowCoderJudgeProtocol.Acknowledgement submit(){return protocol.submit(context,"// 中文\nint main(){}",barriers::incrementAndGet);}
    @Test void fixedRolesSendLoginOnlyToReviewedGatewayAndCodeExactlyOnce(){
        NowCoderJudgeProtocol.Acknowledgement result=submit();assertEquals("job-1",result.jobId);assertEquals(999L,result.submissionId);assertEquals(1,posts.get());assertEquals(0,rotations.get());assertEquals("// 中文\nint main(){}",posted.path("content").asText());
        assertEquals("12345",posted.path("userId").asText());assertEquals("1338275",posted.path("questionId").asText());assertEquals("2",posted.path("language").asText());assertEquals(1,posted.path("submitType").asInt());assertEquals("fake-access-token",posted.path("token").asText());assertEquals("{}",posted.path("remark").asText());
        assertFalse(result.toString().contains("fake-"));assertFalse(context.toString().contains("12345"));assertFalse(context.snapshot().toString().contains("token"));
    }
    @Test void durableBarrierFailurePreventsCodeIoEvenAfterTokenRead(){
        assertThrows(IllegalStateException.class,()->protocol.submit(context,"int main(){}",()->{throw new IllegalStateException("lost lease");}));assertEquals(0,posts.get());assertEquals(2,requests.get());
    }
    @Test void lostResponseAndRedirectAreNeverFollowedOrRetried(){
        dropped=true;assertThrows(RemoteSourceFailure.class,this::submit);assertEquals(1,posts.get());assertEquals(1,barriers.get());
        dropped=false;postStatus=302;barriers.set(0);assertThrows(RemoteSourceFailure.class,this::submit);assertEquals(2,posts.get());assertEquals(0,leaks.get());
    }
    @Test void ownerOrTokenFailureStopsBeforeTheCodeBarrier(){
        home=home.replace("12345","99999");assertEquals(AUTH_REQUIRED,assertThrows(RemoteSourceFailure.class,this::submit).kind);assertEquals(1,requests.get());assertEquals(0,barriers.get());
        home=home.replace("99999","12345");oauth="{\"success\":false,\"data\":null}";assertEquals(AUTH_REQUIRED,assertThrows(RemoteSourceFailure.class,this::submit).kind);assertEquals(0,posts.get());
    }
    @Test void tokenShapesAndSourceEnvelopesFailClosedWithoutLeakingTheirContents(){
        oauth=oauth.replace("fake-access-token","fake-private-token\\r\\nInjected");assertEquals(FORMAT_CHANGED,assertThrows(RemoteSourceFailure.class,this::submit).kind);assertEquals(0,posts.get());
        oauth="{\"success\":true,\"data\":{\"accessToken\":\"fake-access-token\"}}";acknowledgement="{\"code\":0,\"code\":999,\"data\":{\"id\":\"job-1\"}}";assertEquals(FORMAT_CHANGED,assertThrows(RemoteSourceFailure.class,this::submit).kind);assertEquals(1,posts.get());
    }
    @Test void sourceJobAndSubmissionIdsRemainDistinctAcrossPendingSealedAndCompletedResults(){
        acknowledgement="{\"code\":0,\"data\":{\"id\":\"job-1\"}}";assertNull(submit().submissionId);
        for(int phase:Arrays.asList(0,1,2,107)){status="{\"code\":0,\"data\":{\"id\":\"job-1\",\"status\":"+phase+"}}";NowCoderJudgeProtocol.Status result=protocol.status(context,"job-1");assertNull(result.verdict);assertNull(result.submissionId);}
        status="{\"code\":0,\"data\":{\"id\":\"job-1\",\"submissionId\":999,\"status\":5,\"rightHundredRate\":100.0,\"language\":2}}";assertEquals(0,protocol.status(context,"job-1").verdict);assertEquals(1,posts.get());
    }
    @Test void unrelatedJobsUsersQuestionsLanguagesAndSelfTestsNeverGradeTheTask(){
        for(String field:Arrays.asList("\"id\":\"other-job\"","\"userId\":\"99999\"","\"questionId\":\"1\"","\"language\":\"Python 3\"")){
            status="{\"code\":0,\"data\":{\"submissionId\":999,\"status\":5,"+field+"}}";assertEquals(AMBIGUOUS_RESULT,assertThrows(RemoteSourceFailure.class,()->protocol.status(context,"job-1")).kind);
        }
        status="{\"code\":0,\"data\":{\"id\":\"job-1\",\"submissionId\":999,\"status\":5,\"isSelfTest\":true}}";assertEquals(FORMAT_CHANGED,assertThrows(RemoteSourceFailure.class,()->protocol.status(context,"job-1")).kind);
    }
    @Test void statusCodesAreTypedAndNoDescriptionCanTurnFailureOrUnknownStateIntoAc(){
        Map<Integer,Integer> typed=new LinkedHashMap<>();typed.put(6,1);typed.put(12,-2);typed.put(14,4);
        for(Map.Entry<Integer,Integer> entry:typed.entrySet()){status="{\"code\":0,\"data\":{\"submissionId\":999,\"status\":"+entry.getKey()+"}}";assertEquals(entry.getValue(),protocol.status(context,"job-1").verdict);}
        status="{\"code\":0,\"data\":{\"submissionId\":999,\"status\":4,\"judgeReplyDesc\":\"答案错误\"}}";assertEquals(-1,protocol.status(context,"job-1").verdict);
        for(String invalid:Arrays.asList("{\"status\":4,\"judgeReplyDesc\":\"答案正确\"}","{\"status\":28}","{\"status\":5,\"rightHundredRate\":50}","{\"status\":5}","{\"status\":4294967301}")){
            status="{\"code\":0,\"data\":"+invalid+"}";assertEquals(FORMAT_CHANGED,assertThrows(RemoteSourceFailure.class,()->protocol.status(context,"job-1")).kind);
        }
        assertEquals(0,posts.get());
    }
    @Test void malformedContextAndAttemptsToLeakCodeThroughStatusAreRejectedBeforeIo(){
        Map<String,String> snapshot=new LinkedHashMap<>(context.snapshot());snapshot.put("userId","12345&token=leak");assertThrows(IllegalArgumentException.class,()->NowCoderJudgeProtocol.Context.from(snapshot));
        Map<String,String> fields=new LinkedHashMap<>();for(String field:Arrays.asList("id","userId","appId","tagId","submitType","remark","token","showId"))fields.put(field,"fake");fields.put("content","private-code");assertThrows(IllegalArgumentException.class,()->http.nowcoderStatus(fields));assertEquals(0,requests.get());
    }
}
