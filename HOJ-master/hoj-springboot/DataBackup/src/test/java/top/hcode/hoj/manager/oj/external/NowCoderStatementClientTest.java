package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NowCoderStatementClientTest {
    NowCoderStatementClient client;HttpServer server;String body;int status;AtomicInteger requests,redirects,gates;
    String fixture(){return "<div class='header-bar'><div class='question-title'>加法</div></div><div class='terminal-topic'><div class='subject-item-wrap'>题号：NC6 时间限制：C/C++ 1秒，其他语言2秒 空间限制：C/C++ 256 M，其他语言512 M</div><div class='subject-describe'><div class='subject-question'><p>加 <img src='https://www.nowcoder.com/equation?tex=a%3Cb'>。</p><script>bad()</script><img src='https://evil.test/pixel'></div><h2>输入描述:</h2><pre>a b</pre><h2>输出描述:</h2><pre>answer</pre><div class='question-oi'><div class='question-oi-hd'>示例1</div><div class='question-oi-mod'><h2>输入</h2><textarea data-clipboard-text-id='input1'>  1  2</textarea></div><div class='question-oi-mod'><h2>输出</h2><textarea data-clipboard-text-id='output1'>3</textarea></div><div class='question-oi-mod'><h2>说明</h2><div class='question-oi-cont'><pre>求和</pre></div></div></div><h2>备注:</h2><pre>整数</pre></div></div><script>\nwindow.pageInfo = {questionId:'8',problemId:'6',isPdfContent:false,codeJudgeType:'0'};\n</script>";}
    @BeforeEach void setup() throws Exception {
        body=fixture();status=200;requests=new AtomicInteger();redirects=new AtomicInteger();gates=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{requests.incrementAndGet();assertNull(e.getRequestHeaders().getFirst("Cookie"));assertNull(e.getRequestHeaders().getFirst("Authorization"));if(e.getRequestURI().getPath().equals("/redirect")){redirects.incrementAndGet();e.sendResponseHeaders(200,0);e.close();return;}if(status==302)e.getResponseHeaders().set("Location","/redirect");byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        client=new NowCoderStatementClient(){@Override protected URI endpoint(String path){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+path);}};
    }
    @AfterEach void close(){client.close();server.stop(0);}
    @Test void chineseSourceKeepsMathNotesAndSampleWhitespaceWithoutExecutingOrEmbeddingScripts(){
        NowCoderStatementClient.Statement result=client.fetch("nowcoder:acm:6",gates::incrementAndGet);assertEquals("8",result.questionId);assertEquals("6",result.nativeProblemId);assertEquals("加法",result.content.getTitle());assertEquals(1000,result.content.getTimeLimit());assertEquals(256,result.content.getMemoryLimit());
        assertTrue(result.content.getDescription().contains("$a&lt;b$"));assertFalse(result.content.getDescription().contains("bad"));assertFalse(result.content.getDescription().contains("evil"));assertTrue(result.content.getHint().contains("求和"));assertTrue(result.content.getHint().contains("整数"));assertEquals("<input>  1  2</input><output>3</output>",result.content.getExamples());assertEquals(result.content.getDescription(),NowCoderStatementClient.clean(result.content.getDescription()));assertEquals(1,gates.get());
    }
    @Test void unknownPageIdentityIncompleteSamplesAndPdfPagesNeverPublish(){
        body=fixture().replace("problemId:'6'","problemId:'7'");assertThrows(ObjectiveException.class,()->client.fetch("nowcoder:acm:6",gates::incrementAndGet));
        body=fixture().replace("isPdfContent:false","isPdfContent:true");assertThrows(ObjectiveException.class,()->client.fetch("nowcoder:acm:6",gates::incrementAndGet));
        body=fixture().replace("<h2>输出</h2>","<h2>unknown</h2>");assertThrows(ObjectiveException.class,()->client.fetch("nowcoder:acm:6",gates::incrementAndGet));
        body="<html>Login or access verification</html>";assertThrows(ObjectiveException.class,()->client.fetch("nowcoder:acm:6",gates::incrementAndGet));
    }
    @Test void fixedSourceIdsNoRedirectNoRetryAndPerRequestGuardAreEnforced(){
        assertThrows(ObjectiveException.class,()->client.fetch("https://evil.test",gates::incrementAndGet));assertEquals(0,requests.get());status=302;assertThrows(ObjectiveException.class,()->client.fetch("nowcoder:acm:6",gates::incrementAndGet));assertEquals(1,requests.get());assertEquals(0,redirects.get());
        status=503;assertThrows(ObjectiveException.class,()->client.fetch("nowcoder:acm:6",gates::incrementAndGet));assertEquals(2,requests.get());status=200;assertThrows(IllegalStateException.class,()->client.fetch("nowcoder:acm:6",()->{throw new IllegalStateException("guard");}));assertEquals(2,requests.get());
    }
    @Test @EnabledIfEnvironmentVariable(named="HOJ_NOWCODER_SAMPLE_DIR",matches=".+") void officialOrdinaryContestAndTwoCataloguePagesMatchExplicitSourceIds() throws Exception {
        Path dir=Paths.get(System.getenv("HOJ_NOWCODER_SAMPLE_DIR"));String ordinary=new String(Files.readAllBytes(dir.resolve("problem.html")),StandardCharsets.UTF_8),contest=new String(Files.readAllBytes(dir.resolve("contest-problem.html")),StandardCharsets.UTF_8);
        NowCoderStatementClient.Statement normal=client.parse(ordinary,"nowcoder:acm:218144",null);assertEquals("1338275",normal.questionId);assertEquals(3000,normal.content.getTimeLimit());assertTrue(normal.content.getExamples().contains("YES\nYES\nYES\nNO\nNO"));
        ObjectMapper json=new ObjectMapper();com.fasterxml.jackson.databind.JsonNode manifest=json.readTree(Files.readAllBytes(dir.resolve("contest-list.json")));NowCoderStatementClient.Statement match=client.parse(contest,"nowcoder:contest:11244:A",manifest);assertEquals("222132",match.nativeProblemId);assertTrue(match.content.getDescription().contains("$i&lt;j$"));assertThrows(ObjectiveException.class,()->client.parse(contest,"nowcoder:contest:11244:B",manifest));
        NowCoderStatementClient.Page first=client.parsePage(new String(Files.readAllBytes(dir.resolve("list.html")),StandardCharsets.UTF_8),1),second=client.parsePage(new String(Files.readAllBytes(dir.resolve("list2.html")),StandardCharsets.UTF_8),2);assertEquals(50,first.entries.size());assertEquals(first.total,second.total);java.util.Set<String> ids=new java.util.HashSet<>();for(NowCoderStatementClient.Entry e:first.entries)ids.add(e.problemId);for(NowCoderStatementClient.Entry e:second.entries)assertTrue(ids.add(e.problemId));assertNotNull(first.entries.get(0).rating);NowCoderStatementClient.Page mixed=client.parsePage(new String(Files.readAllBytes(dir.resolve("list48.html")),StandardCharsets.UTF_8),48);assertEquals(50,mixed.entries.size());assertTrue(mixed.entries.stream().anyMatch(e->e.problemId.equals("322048")));
        assertThrows(ObjectiveException.class,()->client.parsePage(new String(Files.readAllBytes(dir.resolve("list.html")),StandardCharsets.UTF_8),2));
    }
}
