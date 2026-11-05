package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** A loopback-only controlled provider; production policy is not relaxed. No external requests. */
class AiProviderClientTest {
    HttpServer server; AiProviderClient client; AiEndpointPolicy policy; ObjectMapper json=new ObjectMapper();
    AtomicInteger status=new AtomicInteger(200), requests=new AtomicInteger(), redirected=new AtomicInteger();
    AtomicBoolean rejectLegacy=new AtomicBoolean();
    AtomicReference<String> authorization=new AtomicReference<>(), body=new AtomicReference<>();
    AtomicReference<String> finishReason=new AtomicReference<>(), replyContent=new AtomicReference<>("先检查边界条件。");
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.incrementAndGet();authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[1024];int n;while((n=exchange.getRequestBody().read(chunk))!=-1)buffer.write(chunk,0,n);body.set(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Location","/capture");boolean unsupported=rejectLegacy.getAndSet(false);
            com.fasterxml.jackson.databind.node.ObjectNode completion=json.createObjectNode();completion.putArray("choices").addObject().put("finish_reason",finishReason.get()).putObject("message").put("content",replyContent.get());
            byte[] output=unsupported?"{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"max_tokens\"}}".getBytes(StandardCharsets.UTF_8):json.writeValueAsBytes(completion);
            exchange.sendResponseHeaders(unsupported?400:status.get(),output.length);exchange.getResponseBody().write(output);exchange.close();
        });
        server.createContext("/capture",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();
        policy=new AiEndpointPolicy(){
            @Override public URI endpoint(String base){return URI.create("http://controlled-provider.test:"+server.getAddress().getPort()+"/v1/chat/completions");}
            @Override public InetAddress[] resolve(String host)throws UnknownHostException{return new InetAddress[]{InetAddress.getByName("127.0.0.1")};}
        };
        client=new AiProviderClient();ReflectionTestUtils.setField(client,"endpoints",policy);ReflectionTestUtils.setField(client,"json",json);
    }
    @AfterEach void cleanup(){client.close();policy.close();server.stop(0);}
    String call(){Map<String,String> message=new LinkedHashMap<>();message.put("role","user");message.put("content","如何检查循环边界？");return client.chat("unused","controlled-model","test-only-key",Collections.singletonList(message));}
    @Test void serializesCompatibleRequestAndSendsCredentialOnlyInHeader()throws Exception{
        assertEquals("先检查边界条件。",call());assertEquals("Bearer test-only-key",authorization.get());assertFalse(body.get().contains("test-only-key"));assertEquals("controlled-model",json.readTree(body.get()).path("model").asText());assertFalse(json.readTree(body.get()).path("stream").asBoolean());assertEquals(1,requests.get());
    }
    @Test void doesNotFollowRedirectsOrAutomaticallyRetryFailures(){
        status.set(307);assertThrows(ObjectiveException.class,this::call);assertEquals(0,redirected.get());assertEquals(1,requests.get());
        status.set(500);assertThrows(ObjectiveException.class,this::call);assertEquals(2,requests.get());
    }
    @Test void retriesOnlyAnExplicitUnsupportedTokenParameterRejection()throws Exception{
        rejectLegacy.set(true);assertEquals("先检查边界条件。",call());assertEquals(2,requests.get());
        assertFalse(json.readTree(body.get()).has("max_tokens"));assertEquals(2048,json.readTree(body.get()).path("max_completion_tokens").asInt());
    }
    @Test void structuredOutputKeepsItsBudgetOnCompatibilityRetry()throws Exception{
        rejectLegacy.set(true);assertEquals("先检查边界条件。",client.chat("unused","controlled-model","test-only-key",Collections.emptyList(),8192));
        assertEquals(2,requests.get());assertEquals(8192,json.readTree(body.get()).path("max_completion_tokens").asInt());
        assertThrows(IllegalArgumentException.class,()->client.chat("unused","controlled-model","test-only-key",Collections.emptyList(),8193));assertEquals(2,requests.get());
    }
    @Test void validKnowledgeJsonDoesNotOverrideAnExplicitIncompleteFinishReason() throws Exception {
        Map<String,Object> citation=new LinkedHashMap<>();citation.put("citationId","K1");citation.put("excerpt","区间和是两个前缀和之差");
        replyContent.set("{\"answerable\":true,\"claims\":[{\"content\":\"先作差。\",\"evidence\":[{\"citationId\":\"K1\",\"quote\":\"两个前缀和之差\"}]}]}");
        for(String reason:Arrays.asList("length","content_filter","tool_calls")){
            finishReason.set(reason);assertThrows(ObjectiveException.class,()->client.chat("unused","controlled-model","test-only-key",Collections.emptyList(),2048));
        }
        finishReason.set("stop");String reply=client.chat("unused","controlled-model","test-only-key",Collections.emptyList(),2048);
        assertEquals(true,KnowledgeAnswer.parse(reply,Collections.singletonList(citation),json).get("answerable"));
        assertEquals(4,requests.get());assertEquals(2048,json.readTree(body.get()).path("max_tokens").asInt());
    }
    @Test void explicitlyTruncatedFilteredOrToolResponsesCannotBecomeStructuredDrafts(){
        for(String reason:Arrays.asList("length","content_filter","tool_calls")){
            finishReason.set(reason);assertThrows(ObjectiveException.class,()->client.chat("unused","controlled-model","test-only-key",Collections.emptyList(),8192));
        }
        assertEquals(3,requests.get());finishReason.set("stop");assertEquals("先检查边界条件。",client.chat("unused","controlled-model","test-only-key",Collections.emptyList(),8192));
    }
}
