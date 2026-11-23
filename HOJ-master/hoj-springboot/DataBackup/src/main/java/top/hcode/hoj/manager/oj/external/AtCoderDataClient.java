package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.regex.*;

/** Official profile/rating plus the explicitly attributed AtCoder Problems public dataset. */
@Component
public class AtCoderDataClient {
    @Autowired private ObjectMapper json;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"atcoder-data-deadline");t.setDaemon(true);return t;});
    @PreDestroy public void close(){deadlines.shutdownNow();}
    static void handle(String value){if(value==null || !value.matches("[A-Za-z0-9_]{1,32}"))throw new ObjectiveException(400,"AtCoder 用户名格式无效");}
    protected URI endpoint(String stage,String handle,long from){
        if("PROBLEMS".equals(stage))return URI.create("https://kenkoooo.com/atcoder/resources/problems.json");
        if("MODELS".equals(stage))return URI.create("https://kenkoooo.com/atcoder/resources/problem-models.json");
        if("PROFILE".equals(stage))return URI.create("https://atcoder.jp/users/"+handle+"?lang=en&contestType=algo");
        if("RATING".equals(stage))return URI.create("https://atcoder.jp/users/"+handle+"/history/json?contestType=algo");
        if("STATUS".equals(stage))return URI.create("https://kenkoooo.com/atcoder/atcoder-api/v3/user/submissions?user="+handle+"&from_second="+from);
        if("BOUNDARY".equals(stage))return URI.create("https://kenkoooo.com/atcoder/atcoder-api/v3/user/submission_count?user="+handle+"&from_second="+from+"&to_second="+(from+1));
        throw new IllegalArgumentException("Unknown AtCoder sync stage");
    }
    public JsonNode fetch(String stage,String handle,long from){
        boolean metadata="PROBLEMS".equals(stage)||"MODELS".equals(stage);
        if(!metadata)handle(handle);
        if(from<0 || from>Long.MAX_VALUE-1)throw new IllegalArgumentException("Invalid source time");
        HttpGet request=new HttpGet(endpoint(stage,handle,from));
        ScheduledFuture<?> deadline=deadlines.schedule(request::abort,metadata?45:25,TimeUnit.SECONDS);
        try(CloseableHttpClient client=HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(15000).build()).build();CloseableHttpResponse response=client.execute(request)){
            if(response.getStatusLine().getStatusCode()!=200 || response.getEntity()==null)throw new ObjectiveException(502,"AtCoder 公开数据暂时不可用，已有记录已保留");
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            // HttpClient decompresses gzip; the cap applies to the decompressed response.
            try(InputStream input=response.getEntity().getContent()){byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1){if(output.size()+count>(metadata?32:5)*1024*1024)throw new IOException("Source response too large");output.write(bytes,0,count);}}
            String body=new String(output.toByteArray(),StandardCharsets.UTF_8);
            if("PROFILE".equals(stage))return profile(body,handle);
            JsonNode result=json.readTree(body);
            boolean valid="MODELS".equals(stage)?result!=null&&result.isObject():"BOUNDARY".equals(stage)?result!=null&&result.path("count").isIntegralNumber()&&result.path("count").canConvertToLong()&&result.path("count").asLong()>=0:result!=null&&result.isArray();
            if(!valid)throw new ObjectiveException(502,"AtCoder 来源数据格式变化，已有记录已保留");return result;
        }catch(ObjectiveException e){throw e;}catch(Exception e){throw new ObjectiveException(502,"AtCoder 公开数据连接失败或超时，已有记录已保留");}finally{deadline.cancel(false);}
    }
    JsonNode profile(String html,String expected){
        Document document=Jsoup.parse(html);Element name=document.select("a.username[href^=/users/]").first();
        if(name==null || !expected.equalsIgnoreCase(name.text().trim()) || !name.attr("href").equals("/users/"+name.text().trim()))throw new ObjectiveException(502,"AtCoder 未返回匹配的公开资料");
        ObjectNode user=json.createObjectNode().put("handle",name.text().trim()).put("organization","");
        for(Element row:document.select("table tr")){
            Element heading=row.select("th").first(),value=row.select("td").first();if(heading==null||value==null)continue;
            if("Affiliation".equals(heading.text()))user.put("organization",value.text());
            else if("Rating".equals(heading.text()) || "Highest Rating".equals(heading.text())){
                Matcher number=Pattern.compile("^([0-9]{1,6})(?:\\D|$)").matcher(value.text().trim());if(number.find())user.put("Rating".equals(heading.text())?"rating":"maxRating",Integer.parseInt(number.group(1)));
            }
        }
        return json.createArrayNode().add(user);
    }
}
