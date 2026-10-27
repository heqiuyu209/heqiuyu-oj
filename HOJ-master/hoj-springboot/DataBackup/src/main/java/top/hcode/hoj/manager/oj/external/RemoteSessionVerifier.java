package top.hcode.hoj.manager.oj.external;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.remote.RemoteSession;
import top.hcode.hoj.remote.NowCoderPage;
import top.hcode.hoj.remote.RemoteSourceFailure;

import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Read-only authentication check. Never follows a redirect with the imported source cookies. */
@Component
public class RemoteSessionVerifier {
    @Autowired private StringRedisTemplate redis;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "remote-session-deadline"); thread.setDaemon(true); return thread;
    });

    @PreDestroy public void close() { deadlines.shutdownNow(); }

    protected void rateGate(long accountId) {
        rateGateKey(accountId,"external:cf:public-api");
    }
    protected void rateGate(long accountId,String platform) {
        if("NOWCODER".equals(platform)) rateGateKey(accountId,"external:nowcoder:public-http");else rateGate(accountId);
    }
    private void rateGateKey(long accountId,String sourceKey) {
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("external:session-check:" + accountId, "1", 10, TimeUnit.SECONDS))
                    || !Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(sourceKey, UUID.randomUUID().toString(), 3, TimeUnit.SECONDS)))
                throw new ObjectiveException(429, "身份核对较频繁，请稍后重试");
        } catch (ObjectiveException e) { throw e; }
        catch (Exception e) { throw new ObjectiveException(503, "会话核对暂时不可用，请稍后重试"); }
    }

    public void verify(long accountId, String platform, String expectedHandle, RemoteSession session) {
        if(session==null || !session.belongsTo(platform))throw new ObjectiveException(400,"会话与所选平台不匹配");
        String url;
        if ("CODEFORCES".equals(platform)) url = "https://codeforces.com/settings/general?locale=en";
        else if ("ATCODER".equals(platform)) url = "https://atcoder.jp/settings?lang=en";
        else if ("NOWCODER".equals(platform)) {NowCoderDataClient.handle(expectedHandle);url="https://ac.nowcoder.com/";}
        else throw new ObjectiveException(400, "该平台暂不支持会话授权");
        rateGate(accountId,platform);
        identity(platform, expectedHandle, read(url, session));
    }

    protected String read(String url, RemoteSession session) {
        HttpGet request = new HttpGet(url);
        request.setHeader("Cookie", session.cookieHeader());
        request.setHeader("Accept", "text/html");
        request.setHeader("Accept-Language", "en");
        request.setHeader("User-Agent", "HOJ-Personal-Remote/1.0");
        ScheduledFuture<?> deadline = deadlines.schedule(request::abort, 15, TimeUnit.SECONDS);
        try (CloseableHttpClient client = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(8000).setConnectionRequestTimeout(5000).build()).build();
             CloseableHttpResponse response = client.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            if (status >= 300 && status < 400 || status == 401)
                throw new ObjectiveException(401, "源站会话已失效，请在浏览器重新登录后授权");
            if (status == 403 || status == 429)
                throw new ObjectiveException(503, "源站要求验证或暂不接受服务器访问，请在源站完成验证后重试");
            if (status != 200 || response.getEntity() == null)
                throw new ObjectiveException(502, "源站暂时不可用，原授权已保留");
            String type = response.getFirstHeader("Content-Type") == null ? "" : response.getFirstHeader("Content-Type").getValue();
            if (!type.toLowerCase(Locale.ROOT).startsWith("text/html"))
                throw new ObjectiveException(502, "源站页面格式变化，暂时无法核对登录身份");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = response.getEntity().getContent()) {
                byte[] bytes = new byte[8192]; int count;
                while ((count = input.read(bytes)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) throw new IOException("Response limit exceeded");
                    output.write(bytes, 0, count);
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (ObjectiveException e) { throw e; }
        catch (Exception e) { throw new ObjectiveException(502, "源站连接失败或超时，原授权已保留"); }
        finally { deadline.cancel(false); }
    }

    void identity(String platform, String expected, String html) {
        Document document = Jsoup.parse(html);
        if("NOWCODER".equals(platform)){
            String owner;
            try{owner=NowCoderPage.authenticatedOwner(document);}
            catch(RemoteSourceFailure e){
                if(e.kind==RemoteSourceFailure.Kind.AUTH_REQUIRED)throw new ObjectiveException(401,"源站会话已失效或无法确认登录，请重新登录并授权");
                throw new ObjectiveException(502,"源站身份标记格式变化，原授权已保留");
            }
            if(!Objects.equals(expected,owner))throw new ObjectiveException(403,"会话登录账号与绑定账号不一致，请切换到绑定账号后重新授权");
            return;
        }
        Set<String> handles = new HashSet<>();
        boolean logout;
        if ("CODEFORCES".equals(platform)) {
            logout = !document.select(".lang-chooser a[href^=/logout]").isEmpty();
            for (Element link : document.select(".lang-chooser a[href^=/profile/]")) {
                Matcher match = Pattern.compile("^/profile/([A-Za-z0-9_.-]{1,64})(?:\\?locale=en)?$").matcher(link.attr("href"));
                if (match.matches() && match.group(1).equalsIgnoreCase(link.text().trim()))
                    handles.add(match.group(1).toLowerCase(Locale.ROOT));
            }
        } else if("ATCODER".equals(platform)) {
            logout = !document.select("form[action=/logout][method=post], form[action=/logout][method=POST]").isEmpty();
            for (Element name : document.select("#navbar-collapse .navbar-right .username")) {
                String handle = name.text().trim();
                if (handle.matches("[A-Za-z0-9_]{1,32}")) handles.add(handle.toLowerCase(Locale.ROOT));
            }
        } else throw new ObjectiveException(400,"该平台暂不支持会话授权");
        if (!logout || handles.size() != 1)
            throw new ObjectiveException(401, "无法确认源站登录身份，请重新登录；如源站要求验证，请先完成验证");
        if (expected == null || !handles.contains(expected.toLowerCase(Locale.ROOT)))
            throw new ObjectiveException(403, "会话登录账号与绑定账号不一致，请切换到绑定账号后重新授权");
    }
}
