package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Read-only official ACM profile, rating history and public coding records. No session is sent. */
@Component
public class NowCoderDataClient {
    static final int PAGE_SIZE=50, MAX_PAGES=5000;
    @Autowired private ObjectMapper json;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"nowcoder-public-deadline");t.setDaemon(true);return t;});
    @PreDestroy public void close(){deadlines.shutdownNow();}
    static void handle(String value){if(value==null || !value.matches("[1-9][0-9]{0,11}"))throw new ObjectiveException(400,"请输入牛客个人主页中的数字用户 ID");}
    protected long now(){return System.currentTimeMillis();}
    protected URI endpoint(String stage,String uid,int page){
        if("PROFILE".equals(stage))return URI.create("https://ac.nowcoder.com/acm/contest/profile/"+uid);
        if("RATING".equals(stage))return URI.create("https://ac.nowcoder.com/acm/contest/rating-history?uid="+uid);
        if("STATUS".equals(stage)||"VERIFY".equals(stage))return URI.create("https://ac.nowcoder.com/acm/contest/profile/"+uid+"/practice-coding?page="+("VERIFY".equals(stage)?1:page)+"&pageSize=50&languageCategoryFilter=-1&orderType=DESC&search=&statusTypeFilter=-1");
        throw new IllegalArgumentException("Unknown NowCoder sync stage");
    }
    public JsonNode fetch(String stage,String uid,int page){
        handle(uid);if(page<1 || page>MAX_PAGES)throw new IllegalArgumentException("Invalid source page");
        try {
            String body=publicText(endpoint(stage,uid,page));
            if("PROFILE".equals(stage))return profile(body,uid);
            if("RATING".equals(stage))return rating(json.readTree(body));
            return submissions(body,uid,"VERIFY".equals(stage)?1:page);
        }catch(ObjectiveException e){throw e;}catch(Exception e){throw invalid();}
    }
    String publicText(URI uri){
        HttpGet request=new HttpGet(uri);
        request.setHeader("User-Agent","HOJ-External-Learning/1.0");request.setHeader("Accept-Language","zh-CN");request.setHeader("Referer","https://ac.nowcoder.com/");
        ScheduledFuture<?> deadline=deadlines.schedule(request::abort,20,TimeUnit.SECONDS);
        try(CloseableHttpClient client=HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(15000).build()).build();CloseableHttpResponse response=client.execute(request)){
            if(response.getStatusLine().getStatusCode()!=200 || response.getEntity()==null)throw invalid();
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(InputStream input=response.getEntity().getContent()){byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1){if(output.size()+count>2*1024*1024)throw invalid();output.write(bytes,0,count);}}
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }catch(ObjectiveException e){throw e;}catch(Exception e){throw invalid();}finally{deadline.cancel(false);}
    }
    private static ObjectiveException invalid(){return new ObjectiveException(502,"牛客公开数据暂不可用或格式变化，已有记录已保留");}
    private Document identity(String html,String uid){
        Document document=Jsoup.parse(html);StringBuilder scripts=new StringBuilder();
        for(Element script:document.select("script:not([src])"))scripts.append(script.data()).append('\n');
        Matcher ids=Pattern.compile("window\\.curUser\\.id\\s*=\\s*[\"']([1-9][0-9]{0,11})[\"']\\s*;").matcher(scripts);
        if(!ids.find() || !uid.equals(ids.group(1)) || ids.find())throw invalid();
        Matcher teams=Pattern.compile("window\\.curUser\\.isTeam\\s*=\\s*(true|false)\\s*;").matcher(scripts);
        if(!teams.find() || !"false".equals(teams.group(1)) || teams.find())throw invalid();
        return document;
    }
    JsonNode profile(String html,String uid){
        Document document=identity(html,uid);Elements names=document.select(".coder-name"),bios=document.select(".coder-brief");
        if(names.size()!=1 || names.get(0).text().trim().isEmpty() || names.get(0).text().length()>255 || bios.size()!=1)throw invalid();
        ObjectNode user=json.createObjectNode().put("handle",uid).put("displayName",names.get(0).text()).put("organization",bios.get(0).text());
        boolean found=false;
        for(Element item:document.select(".status-item"))if(item.select("span").stream().anyMatch(e->"Rating".equals(e.text()))){
            if(found || item.select(".state-num").size()!=1)throw invalid();found=true;
            String value=item.select(".state-num").text();if(!value.matches("[0-9]{1,6}"))throw invalid();user.put("rating",Integer.parseInt(value));
        }
        return json.createArrayNode().add(user);
    }
    static long integer(JsonNode node,long min,long max){
        if(node==null || !node.isNumber())throw invalid();
        try{long result=node.decimalValue().longValueExact();if(result<min || result>max)throw invalid();return result;}catch(ArithmeticException e){throw invalid();}
    }
    JsonNode rating(JsonNode root){
        if(root==null || integer(root.get("code"),0,0)!=0 || !root.path("data").isArray() || root.path("data").size()>10000)throw invalid();
        ArrayNode rows=json.createArrayNode();Set<Long> seen=new HashSet<>();
        for(JsonNode event:root.path("data")){
            long cid=integer(event.get("contestId"),1,Long.MAX_VALUE),score=integer(event.get("rating"),0,Integer.MAX_VALUE),change=integer(event.get("changeValue"),Integer.MIN_VALUE,Integer.MAX_VALUE);
            long old=score-change,seconds=integer(event.get("time"),1000,now()+300000)/1000;
            if(old<0 || old>Integer.MAX_VALUE || !seen.add(cid) || !event.path("contestName").isTextual() || event.path("contestName").asText().length()>255)throw invalid();
            rows.addObject().put("contestId",cid).put("contestName",event.path("contestName").asText()).put("rank",integer(event.get("rank"),1,Integer.MAX_VALUE))
                    .put("oldRating",old).put("newRating",score).put("ratingUpdateTimeSeconds",seconds);
        }
        return rows;
    }
    JsonNode submissions(String html,String uid,int page){
        Document document=identity(html,uid);Map<String,Integer> counts=new HashMap<>();
        for(Element item:document.select(".my-state-item")){
            String label=item.select("span").text(),value=item.select(".state-num").text();
            if(!Arrays.asList("题已挑战","题已通过","次提交").contains(label))continue;
            if(!value.matches("[0-9]{1,9}") || counts.put(label,Integer.parseInt(value))!=null)throw invalid();
        }
        if(counts.size()!=3)throw invalid();int attempted=counts.get("题已挑战"),solved=counts.get("题已通过"),total=counts.get("次提交");
        int pages=(total+PAGE_SIZE-1)/PAGE_SIZE;if(solved>attempted || attempted>total || pages>MAX_PAGES || page>Math.max(1,pages))throw invalid();
        Elements tables=document.select("table.table-hover");if(tables.size()!=1 || tables.get(0).select("thead th").size()!=9)throw invalid();
        Elements pagers=document.select(".pagination ul[data-total]");
        if(total>0){
            if(pagers.size()!=1 || !String.valueOf(pages).equals(pagers.get(0).attr("data-total")) || pagers.get(0).select("li.active a[data-page]").size()!=1 || !String.valueOf(page).equals(pagers.get(0).select("li.active a").attr("data-page")))throw invalid();
        }else if(!pagers.isEmpty() && (pagers.size()!=1 || !Arrays.asList("0","1").contains(pagers.get(0).attr("data-total"))))throw invalid();
        ArrayNode rows=json.createArrayNode();long previousSecond=Long.MAX_VALUE;Set<Long> ids=new HashSet<>();
        for(Element row:tables.get(0).select("tbody tr")){
            Elements cells=row.select("td");if(cells.size()!=9)throw invalid();
            Elements links=cells.get(0).select("a[href]");String text=cells.get(0).text();
            if(links.size()!=1 || !text.matches("[1-9][0-9]{0,17}"))throw invalid();long id=Long.parseLong(text);
            if(!ids.add(id))throw invalid();
            URI submission=sourceUrl(links.get(0).attr("href"));
            if(!"ac.nowcoder.com".equals(submission.getHost()) || !"/acm/contest/view-submission".equals(submission.getRawPath()))throw invalid();
            Map<String,String> query=new HashMap<>();for(String pair:Objects.toString(submission.getRawQuery(),"").split("&")){
                String[] part=pair.split("=",-1);if(part.length!=2 || !Arrays.asList("submissionId","uid","returnHomeType").contains(part[0]) || !part[1].matches("[0-9]+") || query.put(part[0],part[1])!=null)throw invalid();
            }
            if(!uid.equals(query.get("uid")) || !text.equals(query.get("submissionId")))throw invalid();
            Elements problems=cells.get(1).select("a[href]");if(problems.size()!=1 || problems.get(0).text().isEmpty() || problems.get(0).text().length()>255)throw invalid();
            String key=ProblemIdentity.nowcoder(sourceUrl(problems.get(0).attr("href")));
            String verdict=verdict(cells.get(2).text());long seconds;
            try{seconds=LocalDateTime.parse(cells.get(8).text(),DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();}catch(DateTimeException e){throw invalid();}
            if(seconds<0 || seconds>now()/1000+300 || seconds>previousSecond)throw invalid();previousSecond=seconds;
            rows.addObject().put("id",id).put("problemKey",key).put("title",problems.get(0).text()).put("verdict",verdict).put("submittedAt",seconds);
        }
        if(rows.size()!=Math.min(PAGE_SIZE,Math.max(0,total-(page-1)*PAGE_SIZE)))throw invalid();
        return json.createObjectNode().put("uid",uid).put("page",page).put("totalPages",pages).put("attempted",attempted).put("solved",solved).put("submissions",total).set("items",rows);
    }
    static URI sourceUrl(String href){
        try{URI value=URI.create("https://ac.nowcoder.com/").resolve(href);if(!"https".equals(value.getScheme()) || value.getRawUserInfo()!=null || value.getPort()!=-1 || value.getFragment()!=null || !("ac.nowcoder.com".equals(value.getHost()) || "www.nowcoder.com".equals(value.getHost())))throw invalid();return value;}catch(IllegalArgumentException e){throw invalid();}
    }
    static String verdict(String value){
        switch(value){case "答案正确":return "OK";case "答案错误":return "WRONG_ANSWER";case "运行超时":return "TIME_LIMIT_EXCEEDED";case "内存超限":return "MEMORY_LIMIT_EXCEEDED";case "运行错误":return "RUNTIME_ERROR";case "编译错误":return "COMPILATION_ERROR";case "格式错误":return "PRESENTATION_ERROR";case "等待判题":case "正在判题":return "PENDING";default:throw invalid();}
    }
}
