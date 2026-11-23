package top.hcode.hoj.manager.oj.external;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.safety.Whitelist;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Public English statements only. Neither login sessions nor source scripts are used. */
@Component
public class AtCoderStatementClient {
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"atcoder-statement-deadline");t.setDaemon(true);return t;});
    @PreDestroy public void close(){deadlines.shutdownNow();}
    public static String sourceUrl(String contest,String task){
        if(contest==null || !contest.matches("[A-Za-z0-9_-]{1,80}") || task==null || !task.matches("[A-Za-z0-9_-]{1,80}"))throw new ObjectiveException(400,"AtCoder 来源编号无效");
        return "https://atcoder.jp/contests/"+contest+"/tasks/"+task;
    }
    protected URI endpoint(String contest,String task){return URI.create(sourceUrl(contest,task)+"?lang=en");}
    public Content fetch(String contest,String task){
        sourceUrl(contest,task);HttpGet request=new HttpGet(endpoint(contest,task));
        ScheduledFuture<?> timeout=deadlines.schedule(request::abort,25,TimeUnit.SECONDS);
        try(CloseableHttpClient client=HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(15000).build()).build();CloseableHttpResponse response=client.execute(request)){
            int status=response.getStatusLine().getStatusCode();
            if(status!=200 || response.getEntity()==null)throw new ObjectiveException(502,"AtCoder 暂时无法读取题面（HTTP "+status+"），已有版本已保留");
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream input=response.getEntity().getContent()){byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1){if(buffer.size()+count>4*1024*1024)throw new IOException("Oversized statement");buffer.write(bytes,0,count);}}
            return parse(new String(buffer.toByteArray(),StandardCharsets.UTF_8),contest,task);
        }catch(ObjectiveException e){throw e;}catch(Exception e){throw new ObjectiveException(502,"AtCoder 题面读取失败，已有版本已保留");}finally{timeout.cancel(false);}
    }
    private ObjectiveException invalid(){return new ObjectiveException(502,"AtCoder 英文题面不完整或格式已变化，请在源站核对后录入英文原文");}
    public Content parse(String html,String contest,String task){
        String url=sourceUrl(contest,task);Document document=Jsoup.parse(html,url);document.outputSettings().prettyPrint(false);
        List<Element> identities=document.select("meta[property=og:url]");
        if(identities.size()!=1 || !(url.equals(identities.get(0).attr("content")) || (url+"?lang=en").equals(identities.get(0).attr("content"))))throw invalid();
        List<Element> languages=document.select("#task-statement .lang-en");
        if(languages.size()!=1)throw new ObjectiveException(502,"AtCoder 未提供可识别的英文题面，请在源站核对后录入英文原文");
        Element heading=document.select("#main-container .h2").first();if(heading==null)throw invalid();heading=heading.clone();heading.select("a,button").remove();
        Content content=new Content();content.setTitle(heading.text().replaceFirst("^[A-Za-z0-9]+\\s*-\\s*",""));
        Element statement=document.getElementById("task-statement");
        String limits=statement.previousElementSibling()==null?"":statement.previousElementSibling().text();
        // Limits are a sibling paragraph in both archived and current task pages.
        if(!limits.contains("Time Limit"))limits=document.select("#main-container").text();
        content.setTimeLimit(limit(limits,"Time Limit:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*sec",1000));
        content.setMemoryLimit(limit(limits,"Memory Limit:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*MiB",1));
        StringBuilder description=new StringBuilder(),hint=new StringBuilder();String input=null,output=null;SortedMap<Integer,String> inputs=new TreeMap<>(),outputs=new TreeMap<>();boolean main=false;
        for(Element section:languages.get(0).select("section")){
            Element title=section.select("h3").first();if(title==null)throw invalid();String name=title.text().trim();
            Element copy=section.clone();copy.select("h3").first().remove();
            Matcher sample=Pattern.compile("Sample (Input|Output) ([1-9][0-9]?)").matcher(name);
            if(sample.matches()){
                List<Element> pres=copy.select("pre");if(pres.size()!=1)throw invalid();
                String raw=org.jsoup.parser.Parser.unescapeEntities(pres.get(0).html().replaceAll("(?i)<br\\s*/?>","\n").replaceAll("<[^>]+>",""),false).replace("\r\n","\n").trim();
                SortedMap<Integer,String> samples="Input".equals(sample.group(1))?inputs:outputs;if(samples.put(Integer.parseInt(sample.group(2)),raw)!=null)throw invalid();
                copy.select("pre").remove();if(!copy.text().trim().isEmpty())hint.append("<h4>").append(name).append("</h4>").append(copy.html());
            }else if("Input".equals(name)){if(input!=null)throw invalid();input=copy.html();}
            else if("Output".equals(name)){if(output!=null)throw invalid();output=copy.html();}
            else {if("Problem Statement".equals(name))main=true;description.append(section.outerHtml());}
        }
        if(!main || input==null || output==null || inputs.isEmpty() || !inputs.keySet().equals(outputs.keySet()))throw invalid();
        StringBuilder examples=new StringBuilder();int next=1;for(int index:inputs.keySet()){if(index!=next++)throw invalid();examples.append("<input>").append(inputs.get(index)).append("</input><output>").append(outputs.get(index)).append("</output>");}
        content.setDescription(clean(description.toString()));content.setInput(clean(input));content.setOutput(clean(output));content.setHint(clean(hint.toString()));content.setExamples(examples.toString());
        if(content.getTitle().isEmpty() || Jsoup.parse(content.getDescription()).text().trim().isEmpty())throw invalid();return content;
    }
    private int limit(String text,String pattern,int factor){Matcher match=Pattern.compile(pattern).matcher(text);if(!match.find())throw invalid();double value=Double.parseDouble(match.group(1))*factor;if(value<1 || value>Integer.MAX_VALUE)throw invalid();return (int)Math.round(value);}
    public static String clean(String html){
        Whitelist allowed=Whitelist.relaxed().addTags("div","span","h1","h2","h3","h4","var","pre","code","sup","sub").addAttributes(":all","class").removeProtocols("img","src","http").removeProtocols("a","href","http","ftp","mailto");
        Document document=Jsoup.parseBodyFragment(html==null?"":html,"https://atcoder.jp/");document.outputSettings().prettyPrint(false);
        // AtCoder uses var elements as TeX containers. Preserve literal sample code separately above.
        for(Element math:document.select("var"))math.replaceWith(new TextNode("$"+math.text()+"$"));
        document=Jsoup.parseBodyFragment(Jsoup.clean(document.body().html(),"https://atcoder.jp/",allowed,new Document.OutputSettings().prettyPrint(false)));document.outputSettings().prettyPrint(false);
        for(Element image:document.select("img")){try{URI uri=URI.create(image.attr("src"));String host=uri.getHost();if(!"https".equalsIgnoreCase(uri.getScheme()) || host==null || !(host.equals("atcoder.jp") || host.endsWith(".atcoder.jp")))image.remove();}catch(Exception e){image.remove();}}
        for(Element link:document.select("a[href]"))link.attr("target","_blank").attr("rel","noopener noreferrer");return document.body().html();
    }
}
