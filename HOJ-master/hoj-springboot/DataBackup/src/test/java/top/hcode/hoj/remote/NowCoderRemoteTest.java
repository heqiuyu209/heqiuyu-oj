package top.hcode.hoj.remote;

import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static top.hcode.hoj.remote.RemoteSourceFailure.Kind.*;

class NowCoderRemoteTest {
    static String nav(String id){return "<script>\nwindow.isLogin = true;\nwindow.globalInfo = {ownerId: '"+id+"', canLoadInIframe: 'true'};\n</script>";}
    static String form(){return nav("12345")+"<script>\nwindow.pageInfo = {questionId: '1338275', problemId: '218144', tagId:'4', supportLang:'java,cpp'};\n</script>";}
    static String metadata(){return "{\"code\":0,\"data\":{\"id\":\"1338275\",\"codingInfo\":{\"supportLanguages\":[{\"langId\":2,\"langName\":\"C++\"},{\"langId\":11,\"langName\":\"Python 3\"}]}}}";}
    @Test void numericSourcePathsAreExactAndNoDispatchPathIsInvented(){
        RemoteProblemRef acm=new RemoteProblemRef("NC-218144",null),contest=new RemoteProblemRef("NC-11244-a",null);
        assertEquals("NOWCODER",acm.platform);assertEquals("https://ac.nowcoder.com",acm.origin());assertEquals("/acm/problem/218144",acm.formPath());assertEquals("/acm/contest/11244/A",contest.formPath());assertTrue(acm.submissionSupported());assertThrows(IllegalStateException.class,acm::submitPath);assertEquals("/acm/contest/view-submission?submissionId=100",acm.submissionPath(100));
        for(String value:Arrays.asList("NC-001","NC-1-other/path","NC-PRACTICE-0123456789abcdef0123456789abcdef","https://ac.nowcoder.com/acm/problem/1"))assertThrows(IllegalArgumentException.class,()->new RemoteProblemRef(value,null));
    }
    @Test void ownerIdentityUsesLoginHeaderAndIgnoresPublicProfileAndBodyText(){
        RemoteSessionIdentity.require("NOWCODER","12345",Jsoup.parse(nav("12345")));
        assertEquals(AUTH_REQUIRED,assertThrows(RemoteSourceFailure.class,()->RemoteSessionIdentity.require("NOWCODER","12345",Jsoup.parse(nav("12346")))).kind);
        for(String invalid:Arrays.asList(nav("12345").replace("= true","= false"),"<script>window.curUser.id='12345'; window.curUser.isTeam=false;</script>","<div>window.isLogin = true; window.globalInfo = {ownerId:'12345'};</div>","<script src='external.js'>\nwindow.isLogin = true;\nwindow.globalInfo = {ownerId:'12345'};\n</script>"))assertThrows(RemoteSourceFailure.class,()->NowCoderPage.authenticatedOwner(Jsoup.parse(invalid)));
    }
    @Test void literalParserHandlesNestedFieldsCommentsAndBracesWithoutExecutingScripts(){
        String html="<script>\nwindow.isLogin = true;\nwindow.globalInfo = {note:'escaped \\\" }; text', nested: {ownerId:'wrong'}, /* comment */ canLoadInIframe:'true'};\nwindow.globalInfo.ownerId = '12345';\n</script>";
        assertEquals("12345",NowCoderPage.authenticatedOwner(Jsoup.parse(html)));
        for(String invalid:Arrays.asList(nav("12345").replace("ownerId: '12345'","ownerId:'12345', ownerId:'99999'"),nav("12345")+nav("99999"),nav("12345").replace("'12345'","getUserId()"),nav("12345").replace("'12345'","'0012345'")))assertThrows(RemoteSourceFailure.class,()->NowCoderPage.authenticatedOwner(Jsoup.parse(invalid)));
    }
    @Test void onlyNecessaryNowcoderCookieNamesSurviveAndAnonymousDeviceCookiesCannotAuthorize(){
        RemoteSession session=RemoteSession.parse("NOWCODER","tracking=discard; t=fake-login; csrf_token=fake-csrf; NOWCODERUID=fake-device; NOWCODERCLINETID=fake-client; acw_tc=fake-edge");
        assertEquals("t=fake-login; csrf_token=fake-csrf; NOWCODERUID=fake-device; NOWCODERCLINETID=fake-client; acw_tc=fake-edge",session.cookieHeader());assertFalse(session.toString().contains("fake-"));
        for(String value:Arrays.asList("NOWCODERUID=fake-device; csrf_token=fake", "t=first; t=second", "t=fake\r\nHost: other", "t=", "REVEL_SESSION=fake"))assertThrows(IllegalArgumentException.class,()->RemoteSession.parse("NOWCODER",value));
    }
    @Test void currentQuestionLanguagesComeFromMetadataAndPreflightNeverPostsCode(){
        try(PersonalRemoteTransport http=new PersonalRemoteTransport("NOWCODER",RemoteSession.parse("NOWCODER","t=fake"),()->{}){
            @Override public String get(String path){assertEquals("/acm/problem/218144",path);return form();}
            @Override public String nowcoderQuestion(String id){assertEquals("1338275",id);return metadata();}
        }){
            PersonalRemoteSource source=new PersonalRemoteSource(new RemoteProblemRef("NC-218144",null),"12345",http);PersonalRemoteSource.Form form=source.form();assertEquals(2,form.languages().size());assertEquals("Python 3",form.languages().get("11"));assertThrows(UnsupportedOperationException.class,()->form.languages().put("99","fake"));
            AtomicInteger posts=new AtomicInteger();assertEquals(UNAVAILABLE,assertThrows(RemoteSourceFailure.class,()->source.post(form,"2","int main(){}",posts::incrementAndGet)).kind);assertEquals(0,posts.get());
        }
    }
    @Test void wrongQuestionDuplicateLanguageAndTeamContextCannotBecomeAnOffer(){
        List<String> responses=Arrays.asList(metadata().replace("1338275","1"),metadata().replace("\"langId\":11","\"langId\":2"),metadata().replace("\"code\":0","\"code\":4294967296"),metadata().replace("Python 3",""));
        for(String response:responses)try(PersonalRemoteTransport http=new PersonalRemoteTransport("NOWCODER",RemoteSession.parse("NOWCODER","t=fake"),()->{}){
            @Override public String get(String path){return form();}@Override public String nowcoderQuestion(String id){return response;}
        }){assertThrows(RemoteSourceFailure.class,()->new PersonalRemoteSource(new RemoteProblemRef("NC-218144",null),"12345",http).form());}
        try(PersonalRemoteTransport http=new PersonalRemoteTransport("NOWCODER",RemoteSession.parse("NOWCODER","t=fake"),()->{}){
            @Override public String nowcoderQuestion(String id){return metadata();}
        }){
            PersonalRemoteSource source=new PersonalRemoteSource(new RemoteProblemRef("NC-11244-A",null),"12345",http);
            for(String context:Arrays.asList("contestId:'11245',isTeamSignUp:false,","contestId:'11244',isTeamSignUp:true,"))assertThrows(RemoteSourceFailure.class,()->source.parseForm(Jsoup.parse(form().replace("questionId:",context+"questionId:"))));
        }
    }
    @Test void publicQuestionMetadataNeverReceivesCookiesAndCannotRotateTheSession() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger requests=new AtomicInteger(),rotations=new AtomicInteger();
        server.createContext("/question",exchange->{requests.incrementAndGet();assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));exchange.getResponseHeaders().set("Set-Cookie","t=must-not-be-imported; Path=/");byte[] body=metadata().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try(PersonalRemoteTransport http=new PersonalRemoteTransport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),RemoteSession.parse("NOWCODER","t=fake-login"),()->{}){
            @Override protected URI questionEndpoint(String id){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/question?id="+id);}
        }){http.onSessionUpdate(updated->rotations.incrementAndGet());assertEquals(metadata(),http.nowcoderQuestion("1338275"));assertThrows(IllegalArgumentException.class,()->http.nowcoderQuestion("1&token=leak"));assertEquals(1,requests.get());assertEquals(0,rotations.get());}finally{server.stop(0);}
    }
    @Test void savedOfficialQuestionShapesRemainSupportedWithoutSourceLogin() throws Exception {
        assertEquals("1338275",NowCoderPage.positiveId(NowCoderPage.fields(Jsoup.parse(form()),"pageInfo"),"questionId",16));String directory=System.getenv("HOJ_NOWCODER_SAMPLE_DIR");if(directory==null)return;
        String metadata=new String(Files.readAllBytes(Paths.get(directory,"question.json")),StandardCharsets.UTF_8);
        try(PersonalRemoteTransport http=new PersonalRemoteTransport("NOWCODER",RemoteSession.parse("NOWCODER","t=unused-fixture"),()->{}){@Override public String nowcoderQuestion(String id){return metadata;}}){
            PersonalRemoteSource source=new PersonalRemoteSource(new RemoteProblemRef("NC-218144",null),"12345",http);
            String html=new String(Files.readAllBytes(Paths.get(directory,"problem.html")),StandardCharsets.UTF_8);Map<String,String> languages=source.parseForm(Jsoup.parse(html)).languages();assertTrue(languages.containsKey("13"));assertTrue(languages.containsKey("11"));for(String excluded:Arrays.asList("14","15","32"))assertFalse(languages.containsKey(excluded));
            String contest=new String(Files.readAllBytes(Paths.get(directory,"contest-problem.html")),StandardCharsets.UTF_8);assertEquals("1751829",NowCoderPage.positiveId(NowCoderPage.fields(Jsoup.parse(contest),"pageInfo"),"questionId",16));
            assertThrows(RemoteSourceFailure.class,()->NowCoderPage.authenticatedOwner(Jsoup.parse(html)));
        }
    }
}
