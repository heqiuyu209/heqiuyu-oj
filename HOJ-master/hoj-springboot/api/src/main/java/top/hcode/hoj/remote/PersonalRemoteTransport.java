package top.hcode.hoj.remote;

import org.apache.http.NameValuePair;
import org.apache.http.Header;
import org.apache.http.cookie.*;
import org.apache.http.impl.cookie.RFC6265StrictSpec;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.ContentType;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Per-operation fixed-origin transport. Code POST has no automatic retry or redirect. */
public class PersonalRemoteTransport implements AutoCloseable {
    public static final class Reply {
        public final int status;
        private final String body;
        public Reply(int status,String body){this.status=status;this.body=body;}
        public String body(){return body;}
        @Override public String toString(){return "RemoteReply[status="+status+"]";}
    }
    private final URI origin;
    private RemoteSession session;
    private Consumer<RemoteSession> sessionUpdated = updated -> {};
    private final Runnable beforeRequest;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"personal-source-deadline");thread.setDaemon(true);return thread;});

    public PersonalRemoteTransport(String platform,RemoteSession session,Runnable beforeRequest) {
        this(fixedOrigin(platform),scopedSession(platform,session),beforeRequest);
    }
    private static RemoteSession scopedSession(String platform,RemoteSession session) {
        if(session==null || !session.belongsTo(platform))throw new IllegalArgumentException("Session source mismatch");
        return session;
    }
    // Package-private origin injection exists only for controlled local protocol tests.
    PersonalRemoteTransport(URI origin,RemoteSession session,Runnable beforeRequest) {
        this.origin=origin;this.session=Objects.requireNonNull(session);this.beforeRequest=Objects.requireNonNull(beforeRequest);
        client=HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(4000).setSocketTimeout(8000).setConnectionRequestTimeout(4000).build()).build();
    }
    private static URI fixedOrigin(String platform) {
        if("CODEFORCES".equals(platform))return URI.create("https://codeforces.com");
        if("ATCODER".equals(platform))return URI.create("https://atcoder.jp");
        if("NOWCODER".equals(platform))return URI.create("https://ac.nowcoder.com");
        throw new IllegalArgumentException("Unsupported source");
    }
    URI endpoint(String path) {
        if(path==null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\") || path.contains("\r") || path.contains("\n"))throw new IllegalArgumentException("Invalid source path");
        URI target=origin.resolve(path);
        if(!Objects.equals(origin.getScheme(),target.getScheme()) || !Objects.equals(origin.getHost(),target.getHost()) || origin.getPort()!=target.getPort() || target.getUserInfo()!=null || target.getFragment()!=null)throw new IllegalArgumentException("Source origin changed");
        return target;
    }
    public String get(String path) {
        Reply result=exchange(new HttpGet(endpoint(path)),null);
        return checked(result);
    }
    /** Only this fixed metadata service can be read without the imported browser session. */
    public String nowcoderQuestion(String questionId) {
        if(!session.belongsTo("NOWCODER") || questionId==null || !questionId.matches("[1-9][0-9]{0,15}"))throw new IllegalArgumentException("Invalid NowCoder question");
        return checked(exchange(new HttpGet(questionEndpoint(questionId)),null,false));
    }
    protected URI questionEndpoint(String questionId){return URI.create("https://questionbank.nowcoder.com/api/qmp/question/detail?id="+questionId+"&version=3&sceneType=3001");}
    protected URI nowcoderService(String service){
        switch(service){
            case "oauth":return URI.create("https://gw-c.nowcoder.com/api/sparta/base-oauth/access-token");
            case "submit":return URI.create("https://victorinox.nowcoder.com/api/service/judge/submit");
            case "status":return URI.create("https://victorinox.nowcoder.com/api/service/judge/submit-status");
            default:throw new IllegalArgumentException("Unknown fixed source service");
        }
    }
    private void nowcoder(){if(!session.belongsTo("NOWCODER"))throw new IllegalArgumentException("Source session mismatch");}
    private String cookie(String name){
        for(String pair:session.cookieHeader().split("; "))if(pair.startsWith(name+"=")){
            String value=pair.substring(name.length()+1);return value.startsWith("\"")&&value.endsWith("\"")?value.substring(1,value.length()-1):value;
        }
        return null;
    }
    private URI query(URI endpoint,Map<String,String> fields){
        try{
            StringBuilder path=new StringBuilder(endpoint.toString());
            for(Map.Entry<String,String> entry:fields.entrySet()){
                if(entry.getValue()==null || entry.getValue().length()>4096)throw new IllegalArgumentException("Invalid source query");
                path.append(path.indexOf("?")<0?'?':'&').append(URLEncoder.encode(entry.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(entry.getValue(),"UTF-8"));
            }
            return URI.create(path.toString());
        }catch(UnsupportedEncodingException e){throw new IllegalStateException(e);}
    }
    /** Login cookies are allowed only at the reviewed first-party OAuth gateway, never the judge. */
    public String nowcoderAccessToken(){
        nowcoder();String csrf=cookie("csrf_token");if(csrf==null || csrf.isEmpty())throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED);
        Map<String,String> fields=new LinkedHashMap<>();fields.put("sceneType","2");fields.put("token",csrf);fields.put("lang","zh-CN");
        List<String> selected=new ArrayList<>();for(String pair:session.cookieHeader().split("; ")){
            String name=pair.substring(0,pair.indexOf('='));if(Arrays.asList("t","csrf_token","NOWCODERUID","NOWCODERCLINETID").contains(name))selected.add(pair);
        }
        return checked(exchange(new HttpGet(query(nowcoderService("oauth"),fields)),null,String.join("; ",selected),false,"https://d.nowcoder.com"));
    }
    /** Source code crosses the durable caller barrier once; no Cookie or browser Authorization follows it. */
    public String nowcoderSubmit(String json,Runnable beginPost){
        nowcoder();if(json==null || json.getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("Invalid source body");
        HttpPost request=new HttpPost(nowcoderService("submit"));request.setEntity(new StringEntity(json,ContentType.APPLICATION_JSON));
        return checked(exchange(request,Objects.requireNonNull(beginPost),null,false,"https://ac.nowcoder.com"));
    }
    public String nowcoderStatus(Map<String,String> fields){
        nowcoder();Set<String> allowed=new HashSet<>(Arrays.asList("id","userId","questionId","language","appId","tagId","submitType","remark","token","showId"));
        if(fields==null || !fields.keySet().containsAll(Arrays.asList("id","userId","appId","tagId","submitType","remark","token","showId")) || !allowed.containsAll(fields.keySet()))throw new IllegalArgumentException("Invalid source status context");
        return checked(exchange(new HttpGet(query(nowcoderService("status"),fields)),null,null,false,"https://ac.nowcoder.com"));
    }
    private String checked(Reply result) {
        if(result.status==401 || result.status>=300 && result.status<400)throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED);
        if(result.status==403 || result.status==429)throw new RemoteSourceFailure(RemoteSourceFailure.Kind.CHALLENGE);
        if(result.status!=200)throw new RemoteSourceFailure(RemoteSourceFailure.Kind.UNAVAILABLE);
        return result.body();
    }
    public PersonalRemoteTransport onSessionUpdate(Consumer<RemoteSession> updated) {
        sessionUpdated = Objects.requireNonNull(updated);
        return this;
    }
    private void updateCookies(CloseableHttpResponse response, URI request) {
        Header[] headers = response.getHeaders("Set-Cookie");
        if (headers.length == 0) return;
        if (headers.length > 32) throw new RemoteSourceFailure(RemoteSourceFailure.Kind.FORMAT_CHANGED);
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String pair : session.cookieHeader().split("; ")) {
            int equals = pair.indexOf('='); cookies.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        CookieOrigin source = new CookieOrigin(origin.getHost(), origin.getPort() < 0 ? ("https".equals(origin.getScheme()) ? 443 : 80) : origin.getPort(), request.getPath(), "https".equals(origin.getScheme()));
        CookieSpec spec = new RFC6265StrictSpec();
        try {
            for (Header header : headers) for (Cookie cookie : spec.parse(header, source)) {
                if (!session.allowsCookie(cookie.getName())) continue;
                spec.validate(cookie, source);
                // Browser imports have no path metadata. Never promote a scoped response cookie to root.
                if (!"/".equals(cookie.getPath())) continue;
                if (cookie.isExpired(new Date())) cookies.remove(cookie.getName());
                else cookies.put(cookie.getName(), cookie.getValue());
            }
            List<String> pairs = new ArrayList<>();
            for (Map.Entry<String, String> cookie : cookies.entrySet()) pairs.add(cookie.getKey() + "=" + cookie.getValue());
            String platform = session.belongsTo("CODEFORCES") ? "CODEFORCES" : session.belongsTo("NOWCODER")?"NOWCODER":"ATCODER";
            RemoteSession replacement = RemoteSession.parse(platform, String.join("; ", pairs));
            if (!session.cookieHeader().equals(replacement.cookieHeader())) {
                sessionUpdated.accept(replacement);
                session = replacement;
            }
        } catch (MalformedCookieException | IllegalArgumentException e) {
            throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED);
        }
    }
    /** The durable beginPost barrier executes after rate/authorization checks, immediately before I/O. */
    public Reply postCode(String path,Map<String,String> fields,Runnable beginPost) {
        HttpPost request=new HttpPost(endpoint(path));
        List<NameValuePair> pairs=new ArrayList<>();for(Map.Entry<String,String> entry:fields.entrySet())pairs.add(new BasicNameValuePair(entry.getKey(),entry.getValue()));
        request.setEntity(new UrlEncodedFormEntity(pairs,StandardCharsets.UTF_8));
        return exchange(request,Objects.requireNonNull(beginPost));
    }
    protected Reply exchange(HttpRequestBase request,Runnable beginPost) {
        return exchange(request,beginPost,true);
    }
    private Reply exchange(HttpRequestBase request,Runnable beginPost,boolean withSession) {
        return exchange(request,beginPost,withSession?session.cookieHeader():null,withSession,origin.toString());
    }
    private Reply exchange(HttpRequestBase request,Runnable beginPost,String cookies,boolean rotate,String requestOrigin) {
        if(cookies!=null)request.setHeader("Cookie",cookies);request.setHeader("User-Agent","HOJ-Personal-Remote/1.0");request.setHeader("Accept-Language","en");
        request.setHeader("Origin",requestOrigin);request.setHeader("Referer",rotate?origin+request.getURI().getPath():requestOrigin+"/");
        beforeRequest.run();
        ScheduledFuture<?> deadline=deadlines.schedule(request::abort,15,TimeUnit.SECONDS);
        try {
            if(beginPost!=null)beginPost.run();
            try(CloseableHttpResponse response=client.execute(request)) {
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                if(response.getEntity()!=null)try(InputStream input=response.getEntity().getContent()) {
                    byte[] bytes=new byte[8192];int count;
                    while((count=input.read(bytes))!=-1) {
                        if(buffer.size()+count>2*1024*1024)throw new IOException("Source response cap");
                        buffer.write(bytes,0,count);
                    }
                }
                if(rotate)updateCookies(response, request.getURI());
                return new Reply(response.getStatusLine().getStatusCode(),new String(buffer.toByteArray(),StandardCharsets.UTF_8));
            }
        } catch(RemoteSourceFailure e){throw e;}
        catch(IOException e){throw new RemoteSourceFailure(RemoteSourceFailure.Kind.UNAVAILABLE);}
        finally{deadline.cancel(false);request.releaseConnection();}
    }
    @Override public void close(){deadlines.shutdownNow();try{client.close();}catch(IOException ignored){}}
}
