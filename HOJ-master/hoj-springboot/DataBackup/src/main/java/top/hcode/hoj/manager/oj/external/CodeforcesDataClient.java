package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Anonymous, fixed-host public API. Never requests sources, cookies, private keys or user-provided URLs. */
@Component
public class CodeforcesDataClient {
    @Autowired private ObjectMapper json;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task,"cf-data-deadline"); thread.setDaemon(true); return thread;
    });
    @PreDestroy public void close() { deadlines.shutdownNow(); }
    protected URI endpoint(String method, String query) { return URI.create("https://codeforces.com/api/" + method + "?" + query); }
    public JsonNode fetch(String stage, String handle, int from) {
        boolean catalogue = "CATALOG".equals(stage);
        if (!catalogue && (handle == null || !handle.matches("[A-Za-z0-9_.-]{1,64}"))) throw new ObjectiveException(400,"CF 用户名格式无效");
        String method, query;
        if (catalogue) { method="problemset.problems"; query="lang=en"; }
        else if ("PROFILE".equals(stage)) { method="user.info"; query="handles="+handle+"&checkHistoricHandles=false&lang=en"; }
        else if ("RATING".equals(stage)) { method="user.rating"; query="handle="+handle; }
        else if (("STATUS".equals(stage) || "VERIFY_STATUS".equals(stage)) && from>0) { method="user.status"; query="handle="+handle+"&from="+from+"&count=500"; }
        else throw new IllegalArgumentException("Unknown sync stage");
        return request(method, query, catalogue);
    }

    public JsonNode recentBrowserSubmissions(String handle) {
        if (handle == null || !handle.matches("[A-Za-z0-9_.-]{1,64}")) throw new ObjectiveException(400,"CF 用户名格式无效");
        return request("user.status", "handle=" + handle + "&from=1&count=40", false);
    }

    private JsonNode request(String method, String query, boolean catalogue) {
        HttpGet request = new HttpGet(endpoint(method,query));
        ScheduledFuture<?> timeout=deadlines.schedule(request::abort,catalogue?45:20,TimeUnit.SECONDS);
        try (CloseableHttpClient client=HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(12000).build()).build();
             CloseableHttpResponse response=client.execute(request)) {
            if (response.getStatusLine().getStatusCode()!=200 || response.getEntity()==null) throw new ObjectiveException(502,"CF 暂时不可用，已保留上次同步记录");
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try (InputStream input=response.getEntity().getContent()) {
                byte[] bytes=new byte[8192]; int count;
                while ((count=input.read(bytes))!=-1) { if(buffer.size()+count>(catalogue?32:5)*1024*1024) throw new IOException("Response too large"); buffer.write(bytes,0,count); }
            }
            JsonNode root=json.readTree(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
            if(root==null || !"OK".equals(root.path("status").asText()) || !(catalogue?root.path("result").path("problems").isArray():root.path("result").isArray()))
                throw new ObjectiveException(502,"CF 未返回有效公开数据，请核对用户名后重试");
            return root.get("result");
        } catch (ObjectiveException e) { throw e; }
        catch (Exception e) { throw new ObjectiveException(502,"CF 连接失败或超时，已保留上次同步记录"); }
        finally { timeout.cancel(false); }
    }
}
