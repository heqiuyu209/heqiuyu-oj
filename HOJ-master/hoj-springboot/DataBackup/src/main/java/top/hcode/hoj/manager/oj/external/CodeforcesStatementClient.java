package top.hcode.hoj.manager.oj.external;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.regex.*;

/** Reads a normal public page. Access challenges are reported, never solved or bypassed. */
@Component
public class CodeforcesStatementClient {
    private static final Pattern KEY=Pattern.compile("cf:contest:([1-9][0-9]{0,18}):([A-Z0-9]{1,16})");
    private static final int MAX_HTML_BYTES = 4 * 1024 * 1024;
    private static final Pattern TITLE_INDEX = Pattern.compile("^([A-Z0-9]{1,16})\\.\\s+");
    private static final Pattern PAGE_PATH = Pattern.compile("^/(?:problemset/problem/([1-9][0-9]{0,18})/([A-Z0-9]{1,16})|contest/([1-9][0-9]{0,18})/problem/([A-Z0-9]{1,16}))/?$");

    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"cf-statement-deadline");t.setDaemon(true);return t;});
    @PreDestroy public void close(){deadlines.shutdownNow();}
    public static String sourceUrl(String key) {
        Matcher match=KEY.matcher(key==null?"":key);
        if(!match.matches()) throw new ObjectiveException(400,"题源编号无效");
        try {Long.parseLong(match.group(1));}catch(NumberFormatException e){throw new ObjectiveException(400,"题源编号无效");}
        return "https://codeforces.com/problemset/problem/"+match.group(1)+"/"+match.group(2);
    }
    static final class AccessRejected extends ObjectiveException {
        AccessRejected() {
            super(403, "Codeforces 拒绝了本站的题面请求（HTTP 403）。可在原文编辑器导入网页 HTML 后校对");
        }
    }
    protected URI endpoint(String key){return URI.create(sourceUrl(key)+"?locale=en");}
    public Content fetch(String key) {
        sourceUrl(key);HttpGet request=new HttpGet(endpoint(key));
        ScheduledFuture<?> timeout=deadlines.schedule(request::abort,25,TimeUnit.SECONDS);
        try(CloseableHttpClient client=HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(15000).build()).build();CloseableHttpResponse response=client.execute(request)) {
            int status=response.getStatusLine().getStatusCode();
            if (status == 403) throw new AccessRejected();
            if (status == 429) throw new ObjectiveException(429, "Codeforces 正在限制请求频率（HTTP 429），请稍后再试或打开源站查看");
            if(status!=200 || response.getEntity()==null) throw new ObjectiveException(502,"CF 暂时无法读取题面（HTTP "+status+"），可在源站查看或由管理员录入原文");
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream input=response.getEntity().getContent()){byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1){if(buffer.size()+count>MAX_HTML_BYTES)throw new IOException("Oversized statement");buffer.write(bytes,0,count);}}
            return parse(new String(buffer.toByteArray(),StandardCharsets.UTF_8),key);
        }catch(ObjectiveException e){throw e;}catch(Exception e){throw new ObjectiveException(502,"CF 题面读取失败，已有版本已保留");}finally{timeout.cancel(false);}
    }
    public Content parse(String html, String key) {
        String source = sourceUrl(key);
        Document document = Jsoup.parse(html, source);
        document.outputSettings().prettyPrint(false);
        Element statement = document.selectFirst(".problem-statement");
        if (statement == null || statement.selectFirst(".header .title") == null) {
            throw new ObjectiveException(502, "CF 未返回完整题面，请在源站查看或由管理员录入原文");
        }
        return parseStatement(statement, source);
    }

    Content preview(String html, String key, String pageUrl) {
        String source = sourceUrl(key);
        if (html == null || html.isEmpty()) {
            throw new ObjectiveException(400, "请选择题目网页 HTML 或粘贴页面源代码");
        }
        if (html.length() > MAX_HTML_BYTES || html.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
            throw new ObjectiveException(400, "网页 HTML 最大为 4 MiB");
        }
        verifyPageUrl(pageUrl, key);
        Document document = Jsoup.parse(html, source);
        document.outputSettings().prettyPrint(false);
        Elements matches = document.select(".problem-statement");
        if (matches.size() != 1 || matches.first().selectFirst(".header .title") == null) {
            throw new ObjectiveException(400, "请导入一题完整的 CF 题面。请先在浏览器确认能看到题目内容");
        }
        Element statement = matches.first();
        Matcher index = TITLE_INDEX.matcher(statement.selectFirst(".header .title").text());
        if (index.find() && !index.group(1).equals(key.substring(key.lastIndexOf(':') + 1))) {
            throw new ObjectiveException(400, "网页题号与当前选择的 CF 题目不一致");
        }
        restoreMath(statement);
        try {
            return parseStatement(statement, source);
        } catch (ObjectiveException error) {
            throw new ObjectiveException(400, error.getMessage());
        }
    }

    private void verifyPageUrl(String pageUrl, String key) {
        if (pageUrl == null || pageUrl.length() > 2048) return;
        URI page;
        try {
            page = URI.create("https://codeforces.com/").resolve(pageUrl);
        } catch (IllegalArgumentException error) {
            return;
        }
        if (!"codeforces.com".equalsIgnoreCase(page.getHost()) && !"www.codeforces.com".equalsIgnoreCase(page.getHost())) return;
        Matcher path = PAGE_PATH.matcher(page.getRawPath());
        if (!path.matches()) return;
        String pageKey = "cf:contest:" + (path.group(1) == null
                ? path.group(3) + ":" + path.group(4)
                : path.group(1) + ":" + path.group(2));
        if (!pageKey.equals(key)) throw new ObjectiveException(400, "网页题号与当前选择的 CF 题目不一致");
    }

    private void restoreMath(Element statement) {
        String mathJax = ".MathJax_Preview, .MathJax, .MathJax_Display, .MathJax_CHTML, .MathJax_SVG";
        for (Element script : statement.select("script[type]")) {
            String type = script.attr("type").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (!type.equals("math/tex") && !type.startsWith("math/tex;")) continue;
            Element previous = script.previousElementSibling();
            while (previous != null && previous.is(mathJax)) {
                Element prior = previous.previousElementSibling();
                previous.remove();
                previous = prior;
            }
            String delimiter = type.contains("mode=display") ? "$$" : "$";
            script.replaceWith(new TextNode(delimiter + script.data() + delimiter));
        }
        if (statement.selectFirst(mathJax + ", mjx-container") != null) {
            throw new ObjectiveException(400, "这份网页含已渲染的公式，请改用浏览器的页面源代码");
        }
    }

    private Content parseStatement(Element statement, String source) {
        Content content=new Content();String title=statement.select(".header .title").first().text();content.setTitle(title.replaceFirst("^[A-Za-z0-9]+\\.\\s*",""));
        content.setTimeLimit(limit(statement,".time-limit","([0-9]+(?:\\.[0-9]+)?)\\s*(?:seconds?|s)\\b",1000));
        content.setMemoryLimit(limit(statement,".memory-limit","([0-9]+(?:\\.[0-9]+)?)\\s*(?:megabytes|MB)\\b",1));
        StringBuilder description=new StringBuilder();
        for(Element child:statement.children()) if(!child.hasClass("header") && !child.hasClass("input-specification") && !child.hasClass("output-specification") && !child.hasClass("sample-tests") && !child.hasClass("note")) description.append(child.outerHtml());
        content.setDescription(clean(description.toString(),source));content.setInput(section(statement,".input-specification",source));content.setOutput(section(statement,".output-specification",source));content.setHint(section(statement,".note",source));
        StringBuilder examples=new StringBuilder();java.util.List<Element> inputs=statement.select(".sample-tests .input pre"),outputs=statement.select(".sample-tests .output pre");
        if (inputs.size() != outputs.size()) throw new ObjectiveException(502, "CF 样例输入和输出数量不一致，请核对完整题面");
        for(int n=0;n<inputs.size();n++) examples.append("<input>").append(sample(inputs.get(n))).append("</input><output>").append(sample(outputs.get(n))).append("</output>");
        content.setExamples(examples.toString());if(Jsoup.parse(content.getDescription()).text().trim().isEmpty()) throw new ObjectiveException(502,"CF 题干解析不完整，已有版本已保留");return content;
    }
    private int limit(Element statement,String selector,String pattern,int factor){Matcher m=Pattern.compile(pattern,Pattern.CASE_INSENSITIVE).matcher(statement.select(selector).text());if(!m.find())throw new ObjectiveException(502,"CF 资源限制解析失败");double value=Double.parseDouble(m.group(1))*factor;if(value<1 || value>Integer.MAX_VALUE)throw new ObjectiveException(502,"CF 资源限制无效");return (int)Math.round(value);}
    private String section(Element statement,String selector,String source){Element section=statement.select(selector).first();if(section==null)return "";section=section.clone();section.select(".section-title").remove();return clean(section.html(),source);}
    private String sample(Element pre){return org.jsoup.parser.Parser.unescapeEntities(pre.html().replaceAll("(?i)<br\\s*/?>","\n").replaceAll("(?i)</div>","\n").replaceAll("<[^>]+>",""),false).replace("\r\n","\n").trim();}
    public static String clean(String html) {
        return clean(html, "https://codeforces.com/");
    }

    private static String clean(String html, String source) {
        Whitelist allowed=Whitelist.relaxed().addTags("div","span","h1","h2","h3","h4","pre","code","sup","sub").addAttributes(":all","class").removeProtocols("img","src","http").removeProtocols("a","href","http","ftp","mailto");
        Document document=Jsoup.parseBodyFragment(Jsoup.clean(html==null?"":html,source,allowed));document.outputSettings().prettyPrint(false);
        for(Element image:document.select("img")){try{URI uri=URI.create(image.attr("src"));String host=uri.getHost();if(!"https".equalsIgnoreCase(uri.getScheme()) || host==null || !(host.equals("codeforces.com") || host.endsWith(".codeforces.com") || host.equals("codeforces.org") || host.endsWith(".codeforces.org")))image.remove();}catch(Exception e){image.remove();}}
        for(Element link:document.select("a[href]"))link.attr("target","_blank").attr("rel","noopener noreferrer");
        // CF uses $$$...$$$; the existing HOJ renderer expects $...$.
        return document.body().html().replace("$$$","$");
    }
}
