package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** OpenAI-compatible text chat only: no tools, browsing, redirects, or platform credentials. */
@Component
public class AiProviderClient {
    @Autowired private AiEndpointPolicy endpoints;
    @Autowired private ObjectMapper json;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ai-request-deadline"); thread.setDaemon(true); return thread;
    });
    @PreDestroy public void close() { deadlines.shutdownNow(); }

    public String chat(String baseUrl, String model, String apiKey, List<Map<String, String>> messages) {
        return request(baseUrl,model,apiKey,messages,2048,false);
    }
    /** Bounded structured transformations need room for a whole document and reject explicit truncation. */
    public String chat(String baseUrl,String model,String apiKey,List<Map<String,String>> messages,int outputTokens){
        if(outputTokens<256 || outputTokens>8192)throw new IllegalArgumentException("Invalid model output budget");
        return request(baseUrl,model,apiKey,messages,outputTokens,true);
    }
    private String request(String baseUrl,String model,String apiKey,List<Map<String,String>> messages,int outputTokens,boolean requireComplete){
        URI uri = endpoints.endpoint(baseUrl);
        HttpPost request = new HttpPost(uri);
        ScheduledFuture<?> deadline = deadlines.schedule(request::abort, 60, TimeUnit.SECONDS);
        try (CloseableHttpClient client = HttpClients.custom()
                .setDnsResolver(host -> endpoints.resolve(host))
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).setConnectionRequestTimeout(1000).setSocketTimeout(45000).build()).build()) {
            Map<String, Object> body = new LinkedHashMap<>(); body.put("model", model); body.put("messages", messages); body.put("stream", false); body.put("max_tokens", outputTokens);
            request.setHeader("Authorization", "Bearer " + apiKey); request.setHeader("Accept", "application/json");
            for (int attempt = 0; attempt < 2; attempt++) {
                request.setEntity(new StringEntity(json.writeValueAsString(body), ContentType.APPLICATION_JSON));
                try (CloseableHttpResponse response = client.execute(request)) {
                    if (attempt == 0 && response.getStatusLine().getStatusCode() == 400) {
                        JsonNode error = json.readTree(readText(response));
                        // Only a definite rejection of this parameter permits a compatibility retry.
                        if (error != null && "unsupported_parameter".equals(error.path("error").path("code").asText())
                                && "max_tokens".equals(error.path("error").path("param").asText())) {
                            body.remove("max_tokens"); body.put("max_completion_tokens", outputTokens); continue;
                        }
                    }
                    return reply(response,requireComplete);
                }
            }
            throw new ObjectiveException(502, "AI 服务未能完成回复");
        } catch (ObjectiveException e) { throw e; }
        catch (Exception e) { throw new ObjectiveException(502, "AI 服务连接失败或超时，请检查 API 地址与服务状态"); }
        finally { deadline.cancel(false); }
    }
    protected String reply(CloseableHttpResponse response) throws IOException {
        return reply(response,false);
    }
    private String reply(CloseableHttpResponse response,boolean requireComplete) throws IOException {
        int status = response.getStatusLine().getStatusCode();
        if (status == 401 || status == 403) throw new ObjectiveException(400, "AI 服务拒绝访问，请检查 API Key 与模型权限");
        if (status == 429) throw new ObjectiveException(429, "AI 服务额度不足或请求过多，请稍后再试");
        if (status != 200) throw new ObjectiveException(502, "AI 服务返回异常，请检查地址、模型和服务状态");
        JsonNode root = json.readTree(readText(response));
        JsonNode finish=root==null?null:root.path("choices").path(0).path("finish_reason");
        if(requireComplete && finish!=null && !finish.isMissingNode() && !finish.isNull() && !"stop".equals(finish.asText()))throw new ObjectiveException(502,"模型未完整生成内容，请重试或缩小本次请求范围");
        JsonNode content = root == null ? null : root.path("choices").path(0).path("message").path("content");
        if (content == null || !content.isTextual() || content.asText().trim().isEmpty()) throw new ObjectiveException(502, "AI 服务没有返回文字回复，请更换兼容的对话模型");
        if (content.asText().length() > 24000) throw new ObjectiveException(502, "AI 回复超过长度限制，请缩小提问范围");
        return content.asText();
    }
    private String readText(CloseableHttpResponse response) throws IOException {
        if (response.getEntity() == null) throw new ObjectiveException(502, "AI 服务返回了空内容");
        if (response.getEntity().getContentLength() > 262144) throw new ObjectiveException(502, "AI 回复超过长度限制，请缩小提问范围");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream input = response.getEntity().getContent()) {
            byte[] chunk = new byte[4096]; int length;
            while ((length = input.read(chunk)) != -1) {
                if (buffer.size() + length > 262144) throw new ObjectiveException(502, "AI 回复超过长度限制，请缩小提问范围");
                buffer.write(chunk, 0, length);
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
