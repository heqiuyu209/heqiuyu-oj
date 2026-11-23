package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Whitelist;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import top.hcode.hoj.remote.*;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Fixed, public ACM endpoints only. Chinese source pages never become an English version. */
@Component
public class NowCoderStatementClient {
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"nowcoder-statement-deadline");t.setDaemon(true);return t;});
    @PreDestroy public void close(){deadlines.shutdownNow();}
    private static ObjectiveException invalid(){return new ObjectiveException(502,"牛客公开题面或目录不完整、不可访问或格式变化，已有版本已保留");}
    public static RemoteProblemRef ref(String key){
        if(key!=null && key.matches("nowcoder:acm:[1-9][0-9]{0,11}"))return new RemoteProblemRef("NC-"+key.substring("nowcoder:acm:".length()),null);
        Matcher contest=Pattern.compile("nowcoder:contest:([1-9][0-9]{0,11}):([A-Z][A-Z0-9]{0,15})").matcher(key==null?"":key);
        if(contest.matches())return new RemoteProblemRef("NC-"+contest.group(1)+"-"+contest.group(2),null);
        throw new ObjectiveException(400,"牛客来源编号无效，请使用普通题号或比赛题号");
    }
    public static String sourceUrl(String key){RemoteProblemRef ref=ref(key);return ref.origin()+ref.formPath();}
    protected URI endpoint(String path){return URI.create("https://ac.nowcoder.com"+path);}
    protected String get(String path,Runnable beforeRequest){
        beforeRequest.run();HttpGet request=new HttpGet(endpoint(path));ScheduledFuture<?> deadline=deadlines.schedule(request::abort,25,TimeUnit.SECONDS);
        try(CloseableHttpClient client=HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(15000).build()).build();CloseableHttpResponse response=client.execute(request)){
            if(response.getStatusLine().getStatusCode()!=200 || response.getEntity()==null)throw invalid();ByteArrayOutputStream body=new ByteArrayOutputStream();
            try(InputStream input=response.getEntity().getContent()){byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1){if(body.size()+count>4*1024*1024)throw invalid();body.write(bytes,0,count);}}
            return new String(body.toByteArray(),StandardCharsets.UTF_8);
        }catch(ObjectiveException e){throw e;}catch(Exception e){throw invalid();}finally{deadline.cancel(false);}
    }
    public static final class Statement {
        public final String questionId,nativeProblemId;
        public final Content content;
        Statement(String questionId,String nativeProblemId,Content content){this.questionId=questionId;this.nativeProblemId=nativeProblemId;this.content=content;}
    }
    public Statement fetch(String key,Runnable beforeRequest){
        RemoteProblemRef ref=ref(key);JsonNode manifest=null;
        if(!ref.contest.isEmpty())try{manifest=json.readTree(get("/acm/contest/problem-list?id="+ref.contest,beforeRequest));}catch(ObjectiveException e){throw e;}catch(Exception e){throw invalid();}
        return parse(get(ref.formPath(),beforeRequest),key,manifest);
    }
    public Statement parse(String html,String key,JsonNode manifest){
        RemoteProblemRef ref=ref(key);Document document=Jsoup.parse(html,sourceUrl(key));document.outputSettings().prettyPrint(false);
        try{
            Map<String,String> fields=NowCoderPage.fields(document,"pageInfo");String question=NowCoderPage.positiveId(fields,"questionId",16),nativeId=NowCoderPage.positiveId(fields,"problemId",12);
            if(!"false".equals(fields.get("isPdfContent")) || !("'0'".equals(fields.get("codeJudgeType")) || "0".equals(fields.get("codeJudgeType"))))throw invalid();
            if(ref.contest.isEmpty()){if(fields.containsKey("contestId") || !ref.task.equals(nativeId))throw invalid();}
            else{
                if(!ref.contest.equals(NowCoderPage.positiveId(fields,"contestId",12)) || !"false".equals(fields.get("isTeamSignUp")))throw invalid();
                if(manifest==null || !manifest.path("code").isIntegralNumber() || manifest.path("code").asLong()!=0 || !ref.contest.equals(manifest.path("data").path("basicInfo").path("contestId").asText()))throw invalid();
                JsonNode rows=manifest.path("data").path("data");if(!rows.isArray() || rows.isEmpty() || rows.size()>1000 || manifest.path("data").path("basicInfo").path("problemCount").asInt()!=rows.size())throw invalid();
                int matches=0;Set<String> indices=new HashSet<>();for(JsonNode row:rows){String index=row.path("index").asText();if(!index.matches("[A-Z][A-Z0-9]{0,15}") || !indices.add(index))throw invalid();if(ref.task.equals(index)){matches++;if(!nativeId.equals(row.path("problemId").asText()))throw invalid();}}
                if(matches!=1)throw invalid();
            }
            List<Element> bodies=document.select(".terminal-topic .subject-describe"),titles=document.select(".header-bar .question-title");if(bodies.size()!=1 || titles.size()!=1)throw invalid();
            Element body=bodies.get(0);List<Element> descriptions=body.select(".subject-question");if(descriptions.size()!=1)throw invalid();
            Content c=new Content();c.setTitle(titles.get(0).text().trim());c.setDescription(clean(descriptions.get(0).html()));
            String limits=document.select(".terminal-topic .subject-item-wrap").text();c.setTimeLimit(limit(limits,"([0-9]+(?:\\.[0-9]+)?)\\s*(毫秒|ms|秒|s)",true));c.setMemoryLimit(limit(limits,"([0-9]+(?:\\.[0-9]+)?)\\s*(MiB|MB|M)\\b",false));
            String input=null,output=null;StringBuilder hint=new StringBuilder();
            for(Element header:body.children())if("h2".equals(header.tagName())){
                String name=header.text().replaceAll("[：:]","").trim();Element next=header.nextElementSibling();if(next==null)throw invalid();
                if("输入描述".equals(name)){if(input!=null)throw invalid();input=next.html();}
                else if("输出描述".equals(name)){if(output!=null)throw invalid();output=next.html();}
                else if("备注".equals(name))hint.append(next.outerHtml());
            }
            if(input==null || output==null)throw invalid();c.setInput(clean(input));c.setOutput(clean(output));StringBuilder examples=new StringBuilder();int number=1;
            for(Element sample:body.select(".question-oi")){
                String heading=sample.select(".question-oi-hd").text().replaceAll("\\s","");if(!heading.equals("示例"+number++))throw invalid();String in=null,out=null;
                for(Element group:sample.select(".question-oi-mod")){
                    String name=group.select("h2").text().trim();List<Element> values=group.select("textarea[data-clipboard-text-id]");if(values.isEmpty())values=group.select(".question-oi-cont pre");
                    if("输入".equals(name)){if(in!=null || values.size()!=1)throw invalid();in=sampleText(values.get(0));}
                    else if("输出".equals(name)){if(out!=null || values.size()!=1)throw invalid();out=sampleText(values.get(0));}
                    else if("说明".equals(name))hint.append(group.select(".question-oi-cont").html());
                    else throw invalid();
                }
                if(in==null || out==null)throw invalid();examples.append("<input>").append(in).append("</input><output>").append(out).append("</output>");
            }
            if(number==1 || c.getTitle().isEmpty() || c.getTitle().length()>255 || Jsoup.parse(c.getDescription()).text().trim().isEmpty())throw invalid();
            c.setExamples(examples.toString());c.setHint(clean(hint.toString()));return new Statement(question,nativeId,c);
        }catch(ObjectiveException e){throw e;}catch(Exception e){throw invalid();}
    }
    private int limit(String text,String pattern,boolean time){Matcher m=Pattern.compile(pattern,Pattern.CASE_INSENSITIVE).matcher(text);if(!m.find())throw invalid();double value=Double.parseDouble(m.group(1));if(time && ("秒".equals(m.group(2)) || "s".equalsIgnoreCase(m.group(2))))value*=1000;if(value<1 || value>(time?3600000:1048576))throw invalid();return (int)Math.round(value);}
    private static String sampleText(Element element){return Parser.unescapeEntities(element.html().replaceAll("(?i)<br\\s*/?>","\n").replaceAll("(?i)</div>","\n").replaceAll("<[^>]+>",""),false).replace("\r\n","\n");}
    public static String clean(String html){
        Document document=Jsoup.parseBodyFragment(html==null?"":html,"https://ac.nowcoder.com/");document.outputSettings().prettyPrint(false);
        for(Element image:document.select("img[src]"))try{
            URI uri=URI.create(image.absUrl("src"));if("https".equals(uri.getScheme()) && ("www.nowcoder.com".equals(uri.getHost()) || "ac.nowcoder.com".equals(uri.getHost())) && "/equation".equals(uri.getPath())){
                String tex=null;for(String pair:(uri.getRawQuery()==null?"":uri.getRawQuery()).split("&")){String[] field=pair.split("=",2);if("tex".equals(field[0])){if(tex!=null || field.length!=2)throw invalid();tex=URLDecoder.decode(field[1],"UTF-8");}}
                if(tex==null || tex.isEmpty() || tex.length()>10000)throw invalid();image.replaceWith(new TextNode("$"+tex+"$"));
            }
        }catch(ObjectiveException e){throw e;}catch(Exception e){image.remove();}
        Whitelist allowed=Whitelist.relaxed().addTags("div","span","h1","h2","h3","h4","pre","code","sup","sub").addAttributes(":all","class").removeProtocols("img","src","http").removeProtocols("a","href","http","ftp","mailto");
        document=Jsoup.parseBodyFragment(Jsoup.clean(document.body().html(),"https://ac.nowcoder.com/",allowed,new Document.OutputSettings().prettyPrint(false)));document.outputSettings().prettyPrint(false);
        for(Element image:document.select("img[src]"))try{URI uri=URI.create(image.attr("src"));String host=uri.getHost();if(!"https".equals(uri.getScheme()) || host==null || !(host.equals("nowcoder.com") || host.endsWith(".nowcoder.com") || host.equals("nowcoder.net") || host.endsWith(".nowcoder.net")))image.remove();}catch(Exception e){image.remove();}
        for(Element link:document.select("a[href]"))link.attr("target","_blank").attr("rel","noopener noreferrer");return document.body().html();
    }
    public static final class Entry {
        public final String problemId,title;
        public final Integer rating;
        Entry(String problemId,String title,Integer rating){this.problemId=problemId;this.title=title;this.rating=rating;}
    }
    public static final class Page {
        public final int page,total,pages;
        public final List<Entry> entries;
        Page(int page,int total,int pages,List<Entry> entries){this.page=page;this.total=total;this.pages=pages;this.entries=Collections.unmodifiableList(new ArrayList<>(entries));}
    }
    public Page fetchPage(int page,Runnable beforeRequest){if(page<1 || page>2000)throw new ObjectiveException(400,"牛客目录页码无效");return parsePage(get("/acm/problem/list?keyword=&tagId=&platformTagId=0&sourceTagId=0&difficulty=0&status=all&order=id&asc=false&pageSize=50&page="+page,beforeRequest),page);}
    private static String literal(String value){if(value==null)return "";if(value.matches("'[^'\\\\]*'|\"[^\"\\\\]*\""))return value.substring(1,value.length()-1);return value;}
    public Page parsePage(String html,int expectedPage){
        try{
            Document document=Jsoup.parse(html,"https://ac.nowcoder.com/acm/problem/list");Map<String,String> list=NowCoderPage.fields(document,"pageInfo.listData"),filters=NowCoderPage.fields(document,"pageInfo.extendGreatHan");
            if(!"id".equals(literal(filters.get("order"))) || !"false".equals(filters.get("asc")) || !"".equals(literal(filters.get("keyword"))) || !"".equals(literal(filters.get("tagId"))) || !"0".equals(literal(filters.get("platformTagId"))) || !"0".equals(literal(filters.get("sourceTagId"))) || !"0".equals(literal(filters.get("difficulty"))) || !"all".equals(literal(filters.get("status"))))throw invalid();
            JsonNode info=json.readTree(list.get("pageInfo"));int total=info.path("totalCount").asInt(),pages=info.path("pageCount").asInt();
            if(!info.path("totalCount").isIntegralNumber() || total<1 || total>100000 || info.path("pageSize").asInt()!=50 || pages!=(total+49)/50 || info.path("pageCurrent").asInt()!=expectedPage || expectedPage<1 || expectedPage>pages)throw invalid();
            List<Entry> entries=new ArrayList<>();Set<String> seen=new HashSet<>();
            for(Element row:document.select("table tr[data-problemid]")){
                String id=row.attr("data-problemid");if(!id.matches("[1-9][0-9]{0,11}") || !seen.add(id))throw invalid();List<Element> cells=row.children();if(cells.size()!=5)throw invalid();
                List<Element> titles=cells.get(1).select("a.title");if(titles.size()!=1 || !sourceUrl("nowcoder:acm:"+id).equals(titles.get(0).absUrl("href")) || !cells.get(0).text().equals("NC"+id))throw invalid();String title=titles.get(0).text().trim();if(title.isEmpty() || title.length()>255)throw invalid();
                String value=cells.get(2).text().trim();Integer rating=null;if(!value.isEmpty() && !"--".equals(value)){if(!value.matches("[0-9]{1,5}") || Integer.parseInt(value)>10000)throw invalid();rating=Integer.valueOf(value);}entries.add(new Entry(id,title,rating));
            }
            if(entries.size()!=Math.min(50,total-(expectedPage-1)*50))throw invalid();return new Page(expectedPage,total,pages,entries);
        }catch(ObjectiveException e){throw e;}catch(Exception e){throw invalid();}
    }
}
