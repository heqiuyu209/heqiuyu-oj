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
import java.util.concurrent.*;
import java.util.regex.*;

/** Reads a normal public page. Access challenges are reported, never solved or bypassed. */
@Component
public class CodeforcesStatementClient {
    private static final Pattern KEY=Pattern.compile("cf:contest:([1-9][0-9]{0,18}):([A-Z0-9]{1,16})");
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"cf-statement-deadline");t.setDaemon(true);return t;});
    @PreDestroy public void close(){deadlines.shutdownNow();}
    public static String sourceUrl(String key) {
        Matcher match=KEY.matcher(key==null?"":key);
        if(!match.matches()) throw new ObjectiveException(400,"题源编号无效");
        try {Long.parseLong(match.group(1));}catch(NumberFormatException e){throw new ObjectiveException(400,"题源编号无效");}
        return "https://codeforces.com/problemset/problem/"+match.group(1)+"/"+match.group(2);
    }
    protected URI endpoint(String key){return URI.create(sourceUrl(key)+"?locale=en");}
    public Content fetch(String key) {
        sourceUrl(key);HttpGet request=new HttpGet(endpoint(key));
        ScheduledFuture<?> timeout=deadlines.schedule(request::abort,25,TimeUnit.SECONDS);
        try(CloseableHttpClient client=HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(15000).build()).build();CloseableHttpResponse response=client.execute(request)) {
            int status=response.getStatusLine().getStatusCode();
            if(status!=200 || response.getEntity()==null) throw new ObjectiveException(502,"CF 暂时无法读取题面（HTTP "+status+"），可在源站查看或由管理员录入原文");
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream input=response.getEntity().getContent()){byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1){if(buffer.size()+count>4*1024*1024)throw new IOException("Oversized statement");buffer.write(bytes,0,count);}}
            return parse(new String(buffer.toByteArray(),StandardCharsets.UTF_8),key);
        }catch(ObjectiveException e){throw e;}catch(Exception e){throw new ObjectiveException(502,"CF 题面读取失败，已有版本已保留");}finally{timeout.cancel(false);}
    }
    public Content parse(String html,String key) {
        Document document=Jsoup.parse(html,sourceUrl(key));document.outputSettings().prettyPrint(false);Element statement=document.select(".problem-statement").first();
        if(statement==null || statement.select(".header .title").isEmpty()) throw new ObjectiveException(502,"CF 未返回完整题面，请在源站查看或由管理员录入原文");
        Content content=new Content();String title=statement.select(".header .title").first().text();content.setTitle(title.replaceFirst("^[A-Za-z0-9]+\\.\\s*",""));
        content.setTimeLimit(limit(statement,".time-limit","([0-9]+(?:\\.[0-9]+)?)\\s*(?:seconds?|s)\\b",1000));
        content.setMemoryLimit(limit(statement,".memory-limit","([0-9]+(?:\\.[0-9]+)?)\\s*(?:megabytes|MB)\\b",1));
        StringBuilder description=new StringBuilder();
        for(Element child:statement.children()) if(!child.hasClass("header") && !child.hasClass("input-specification") && !child.hasClass("output-specification") && !child.hasClass("sample-tests") && !child.hasClass("note")) description.append(child.outerHtml());
        content.setDescription(clean(description.toString()));content.setInput(section(statement,".input-specification"));content.setOutput(section(statement,".output-specification"));content.setHint(section(statement,".note"));
        StringBuilder examples=new StringBuilder();java.util.List<Element> inputs=statement.select(".sample-tests .input pre"),outputs=statement.select(".sample-tests .output pre");
        for(int n=0;n<Math.min(inputs.size(),outputs.size());n++) examples.append("<input>").append(sample(inputs.get(n))).append("</input><output>").append(sample(outputs.get(n))).append("</output>");
        content.setExamples(examples.toString());if(Jsoup.parse(content.getDescription()).text().trim().isEmpty()) throw new ObjectiveException(502,"CF 题干解析不完整，已有版本已保留");return content;
    }
    private int limit(Element statement,String selector,String pattern,int factor){Matcher m=Pattern.compile(pattern,Pattern.CASE_INSENSITIVE).matcher(statement.select(selector).text());if(!m.find())throw new ObjectiveException(502,"CF 资源限制解析失败");double value=Double.parseDouble(m.group(1))*factor;if(value<1 || value>Integer.MAX_VALUE)throw new ObjectiveException(502,"CF 资源限制无效");return (int)Math.round(value);}
    private String section(Element statement,String selector){Element section=statement.select(selector).first();if(section==null)return "";section=section.clone();section.select(".section-title").remove();return clean(section.html());}
    private String sample(Element pre){return org.jsoup.parser.Parser.unescapeEntities(pre.html().replaceAll("(?i)<br\\s*/?>","\n").replaceAll("(?i)</div>","\n").replaceAll("<[^>]+>",""),false).replace("\r\n","\n").trim();}
    public static String clean(String html){
        Whitelist allowed=Whitelist.relaxed().addTags("div","span","h1","h2","h3","h4","pre","code","sup","sub").addAttributes(":all","class").removeProtocols("img","src","http").removeProtocols("a","href","http","ftp","mailto");
        Document document=Jsoup.parseBodyFragment(Jsoup.clean(html==null?"":html,"https://codeforces.com/",allowed));document.outputSettings().prettyPrint(false);
        for(Element image:document.select("img")){try{URI uri=URI.create(image.attr("src"));String host=uri.getHost();if(!"https".equalsIgnoreCase(uri.getScheme()) || host==null || !(host.equals("codeforces.com") || host.endsWith(".codeforces.com") || host.equals("codeforces.org") || host.endsWith(".codeforces.org")))image.remove();}catch(Exception e){image.remove();}}
        for(Element link:document.select("a[href]"))link.attr("target","_blank").attr("rel","noopener noreferrer");
        // CF uses $$$...$$$; the existing HOJ renderer expects $...$.
        return document.body().html().replace("$$$","$");
    }
}
