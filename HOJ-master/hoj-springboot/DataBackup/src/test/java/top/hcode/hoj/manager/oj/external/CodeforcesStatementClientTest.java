package top.hcode.hoj.manager.oj.external;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CodeforcesStatementClientTest {
    HttpServer server;CodeforcesStatementClient client;String body;int status;AtomicInteger redirected;
    @BeforeEach void setup() throws Exception {
        status=200;redirected=new AtomicInteger();
        body="<div class='problem-statement'><div class='header'><div class='title'>A. Count integers</div><div class='time-limit'>time limit per test 1.5 seconds</div><div class='memory-limit'>memory limit per test 256 megabytes</div></div>"
            +"<div><p>Count $$$n$$$ integers.</p><script>leak()</script><img src='https://untrusted.test/track' onerror='leak()'></div>"
            +"<div class='input-specification'><div class='section-title'>Input</div><p>n</p></div><div class='output-specification'><div class='section-title'>Output</div><p>answer</p></div>"
            +"<div class='sample-tests'><div class='input'><pre>2<br>3 &lt; 4</pre></div><div class='output'><pre><div>7</div><div>8</div></pre></div></div><div class='note'><div class='section-title'>Note</div><p>Explanation</p></div></div>";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/statement",exchange->{
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
            if(status==302)exchange.getResponseHeaders().set("Location","/redirected");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.createContext("/redirected",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});server.start();
        client=new CodeforcesStatementClient(){@Override protected URI endpoint(String key){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/statement");}};
    }
    @AfterEach void close(){client.close();server.stop(0);}
    @Test void parserPreservesMathAndSampleLinesAndRemovesUnsafeMarkup(){
        Content c=client.fetch("cf:contest:4:A");assertEquals("Count integers",c.getTitle());assertEquals(1500,c.getTimeLimit());assertEquals(256,c.getMemoryLimit());
        assertTrue(c.getDescription().contains("$n$"));assertFalse(c.getDescription().contains("$$$"));assertFalse(c.getDescription().contains("script"));assertFalse(c.getDescription().contains("untrusted"));assertFalse(c.getInput().contains("section-title"));
        assertEquals("<input>2\n3 < 4</input><output>7\n8</output>",c.getExamples());assertTrue(c.getHint().contains("Explanation"));
    }
    @Test void offlinePreviewAcceptsPageOrFragmentAndRecoversRenderedMath() {
        String rendered = body.replace("$$$n$$$",
                "<span class='MathJax_Preview'>duplicate</span><span class='MathJax'>duplicate</span>"
                        + "<script type='math/tex'>n^2</script>");
        Content result = client.preview("<html><body>" + rendered + "</body></html>",
                "cf:contest:4:A", "https://codeforces.com/contest/4/problem/A?locale=en");
        assertEquals("Count integers", result.getTitle());
        assertEquals(1500, result.getTimeLimit());
        assertEquals(256, result.getMemoryLimit());
        assertTrue(result.getDescription().contains("$n^2$"));
        assertFalse(result.getDescription().contains("duplicate"));
        assertFalse(result.getDescription().contains("<script"));
        assertEquals("<input>2\n3 < 4</input><output>7\n8</output>", result.getExamples());
        assertEquals(result.getTitle(), client.preview(rendered, "cf:contest:4:A", null).getTitle());
    }

    @Test void offlinePreviewRejectsMismatchedAndPartialPages() {
        assertEquals(400, assertThrows(ObjectiveException.class,
                () -> client.preview(body, "cf:contest:4:A", "https://codeforces.com/problemset/problem/5/A")).getStatus());
        assertEquals(400, assertThrows(ObjectiveException.class,
                () -> client.preview(body, "cf:contest:4:B", null)).getStatus());
        assertEquals(400, assertThrows(ObjectiveException.class,
                () -> client.preview(body + body, "cf:contest:4:A", null)).getStatus());
        assertEquals(400, assertThrows(ObjectiveException.class,
                () -> client.preview("<title>Just a moment</title>", "cf:contest:4:A", null)).getStatus());
        String incomplete = body.replace("<pre><div>7</div><div>8</div></pre>", "");
        assertEquals(400, assertThrows(ObjectiveException.class,
                () -> client.preview(incomplete, "cf:contest:4:A", null)).getStatus());
    }

    @Test void challengesAndRedirectsAndPartialStatementsNeverPublishBlankContent(){
        status=403;assertTrue(assertThrows(ObjectiveException.class,()->client.fetch("cf:contest:4:A")).getMessage().contains("403"));
        status=302;assertThrows(ObjectiveException.class,()->client.fetch("cf:contest:4:A"));assertEquals(0,redirected.get());
        status=200;body="<html>Access challenge or changed markup</html>";assertThrows(ObjectiveException.class,()->client.fetch("cf:contest:4:A"));
    }
    @Test void onlyFixedContestIdentifiersAndSafeSourceLinksAreAccepted(){
        assertThrows(ObjectiveException.class,()->client.fetch("cf:contest:4:A?x=1"));assertThrows(ObjectiveException.class,()->CodeforcesStatementClient.sourceUrl("cf:contest:9999999999999999999:A"));
        String clean=CodeforcesStatementClient.clean("<a href='javascript:alert(1)' onclick='x()'>x</a><a href='https://codeforces.com/'>CF</a><iframe src='https://x/'></iframe><img src='https://codeforces.org/a.png'><img src='https://codeforces.com.evil/a'>");
        assertFalse(clean.contains("javascript:"));assertFalse(clean.contains("onclick"));assertFalse(clean.contains("iframe"));assertFalse(clean.contains(".evil"));assertTrue(clean.contains("noopener noreferrer"));assertTrue(clean.contains("https://codeforces.org/a.png"));
    }
    @Test void numericIndicesRetainLeadingZerosThroughSourceLinksAndStatementParsing(){
        assertEquals("https://codeforces.com/problemset/problem/921/01",CodeforcesStatementClient.sourceUrl("cf:contest:921:01"));
        assertEquals("https://codeforces.com/problemset/problem/921/12",CodeforcesStatementClient.sourceUrl("cf:contest:921:12"));
        body=body.replace("A. Count integers","01. Count integers");
        assertEquals("Count integers",client.fetch("cf:contest:921:01").getTitle());
        for(String index:new String[]{"01/../A","01?locale=ru","01#x","%30%31","01_","ABCDEFGHIJKLMNOPQ"})
            assertEquals(400,assertThrows(ObjectiveException.class,()->CodeforcesStatementClient.sourceUrl("cf:contest:921:"+index)).getStatus());
    }
}
