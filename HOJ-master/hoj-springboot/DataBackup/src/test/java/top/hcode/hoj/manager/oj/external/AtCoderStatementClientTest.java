package top.hcode.hoj.manager.oj.external;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AtCoderStatementClientTest {
    HttpServer server;AtCoderStatementClient client;String body;int status;AtomicInteger redirects,requests;
    String page(String sections){return "<meta property='og:url' content='https://atcoder.jp/contests/abc086/tasks/abc086_a?lang=en'><div id='main-container'><span class='h2'>A - Product<a>Editorial</a></span><p>Time Limit: 1.5 sec / Memory Limit: 256 MiB</p><div id='task-statement'><span class='lang-ja'>Japanese</span><span class='lang-en'>"+sections+"</span></div></div>";}
    String section(String name,String text){return "<section><h3>"+name+"</h3>"+text+"</section>";}
    String sections(){return section("Problem Statement","<p>Multiply <var>a &lt; b</var>.</p><img src='//img.atcoder.jp/a.png'><img src='https://evil.test/a'><script>bad()</script>")+section("Constraints","<ul><li>positive</li></ul><table><tr><td>limit</td></tr></table>")+section("Input","<pre><var>a</var> <var>b</var></pre>")+section("Output","<p>answer</p>")+section("Sample Input 1","<pre>3  4\n5 &lt; 6</pre>")+section("Sample Output 1","<pre>12</pre><p>Multiply the values.</p>");}
    @BeforeEach void setup() throws Exception {
        status=200;redirects=new AtomicInteger();requests=new AtomicInteger();body=page(sections());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/statement",e->{requests.incrementAndGet();assertNull(e.getRequestHeaders().getFirst("Cookie"));assertNull(e.getRequestHeaders().getFirst("Authorization"));if(status==302)e.getResponseHeaders().set("Location","/redirected");byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();});
        server.createContext("/redirected",e->{redirects.incrementAndGet();e.sendResponseHeaders(200,0);e.close();});server.start();
        client=new AtCoderStatementClient(){@Override protected URI endpoint(String contest,String task){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/statement");}};
    }
    @AfterEach void close(){client.close();server.stop(0);}
    @Test void englishMathConstraintsExamplesAndExplanationsSurviveWithoutUnsafeMarkup(){
        Content c=client.fetch("abc086","abc086_a");assertEquals("Product",c.getTitle());assertEquals(1500,c.getTimeLimit());assertEquals(256,c.getMemoryLimit());
        assertTrue(c.getDescription().contains("Constraints"));assertTrue(c.getDescription().contains("<table>"));assertTrue(c.getDescription().contains("$a &lt; b$"));assertTrue(c.getDescription().contains("https://img.atcoder.jp/a.png"));assertFalse(c.getDescription().contains("evil"));assertFalse(c.getDescription().contains("script"));assertTrue(c.getInput().contains("$a$"));
        assertEquals("<input>3  4\n5 < 6</input><output>12</output>",c.getExamples());assertTrue(c.getHint().contains("Multiply the values"));assertEquals(c.getDescription(),AtCoderStatementClient.clean(c.getDescription()));
    }
    @Test void missingEnglishWrongTaskIncompleteSamplesAndChallengesNeverPublish(){
        body=body.replace("lang-en","lang-unknown");assertTrue(assertThrows(ObjectiveException.class,()->client.fetch("abc086","abc086_a")).getMessage().contains("英文"));
        body=page(sections()).replace("tasks/abc086_a","tasks/abc086_b");assertThrows(ObjectiveException.class,()->client.fetch("abc086","abc086_a"));
        body=page(sections().replace("Sample Output 1","Sample Output 2"));assertThrows(ObjectiveException.class,()->client.fetch("abc086","abc086_a"));
        body="<html>Sign in or verify access</html>";assertThrows(ObjectiveException.class,()->client.fetch("abc086","abc086_a"));
    }
    @Test void redirectsAreNotFollowedAndFailuresAreNotRetriedAndUrlsStayFixed(){
        status=302;assertThrows(ObjectiveException.class,()->client.fetch("abc086","abc086_a"));assertEquals(0,redirects.get());assertEquals(1,requests.get());
        assertThrows(ObjectiveException.class,()->client.fetch("https://evil.test","abc086_a"));assertEquals(1,requests.get());
        status=503;assertThrows(ObjectiveException.class,()->client.fetch("abc086","abc086_a"));assertEquals(2,requests.get());
    }
    @Test @EnabledIfEnvironmentVariable(named="HOJ_ATCODER_STATEMENT_SAMPLE_DIR",matches=".+") void officialReadOnlySamplesParseAndOldJapaneseOnlyPageIsNotCalledEnglish() throws Exception {
        Path dir=Paths.get(System.getenv("HOJ_ATCODER_STATEMENT_SAMPLE_DIR"));
        Content product=client.parse(new String(Files.readAllBytes(dir.resolve("abc086_a.html")),StandardCharsets.UTF_8),"abc086","abc086_a");assertEquals("Product",product.getTitle());assertEquals(2000,product.getTimeLimit());assertTrue(product.getExamples().contains("<input>3 4</input><output>Even</output>"));
        Content legend=client.parse(new String(Files.readAllBytes(dir.resolve("abc319_a.html")),StandardCharsets.UTF_8),"abc319","abc319_a");assertTrue(legend.getDescription().contains("3858"));assertTrue(legend.getHint().contains("https://img.atcoder.jp/"));assertTrue(legend.getExamples().contains("tourist"));
        assertThrows(ObjectiveException.class,()->client.parse(new String(Files.readAllBytes(dir.resolve("abc001_1.html")),StandardCharsets.UTF_8),"abc001","abc001_1"));
    }
}
