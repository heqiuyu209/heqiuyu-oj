package top.hcode.hoj.remote;

import com.fasterxml.jackson.databind.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import static top.hcode.hoj.remote.RemoteSourceFailure.Kind.*;

/** Source-specific forms and result evidence, separate from the legacy administrator adapters. */
public final class PersonalRemoteSource {
    // Bounds source creation time, not recovery/polling time. The POST transport stops after
    // 15 seconds; allow up to two minutes for source processing/clock differences, never
    // adopt an arbitrarily later manual submission with identical code.
    private static final long POST_MATCH_WINDOW_MILLIS=120000;
    // Official ACM editor 2.0.114 categories. Intersect with the current question's metadata;
    // question-bank defaults also include SQL/V8 choices unavailable in the ACM editor.
    private static final Set<String> NOWCODER_ACM_LANGUAGES=Collections.unmodifiableSet(new HashSet<>(Arrays.asList("1","2","3","4","5","8","9","10","11","13","16","17","19","20","21","24","25","27","28","29","30","31")));
    public static final class Form {
        private final String postPath;
        private final Map<String,String> hidden;
        private final Map<String,String> languages;
        private final Map<String,String> sourceContext;
        private Form(String path,Map<String,String> hidden,Map<String,String> languages){this(path,hidden,languages,Collections.emptyMap());}
        private Form(String path,Map<String,String> hidden,Map<String,String> languages,Map<String,String> sourceContext){postPath=path;this.hidden=hidden;this.languages=Collections.unmodifiableMap(languages);this.sourceContext=Collections.unmodifiableMap(new LinkedHashMap<>(sourceContext));}
        public Map<String,String> languages(){return languages;}
        public Map<String,String> sourceContext(){return sourceContext;}
        @Override public String toString(){return "RemoteForm[redacted]";}
    }
    public static final class Candidate {
        public final long id,submittedAt;
        public final String task,handle,language;
        public final Integer verdict,time,memory;
        private Candidate(long id,long at,String task,String handle,String language,Integer verdict,Integer time,Integer memory){this.id=id;submittedAt=at;this.task=task;this.handle=handle;this.language=language;this.verdict=verdict;this.time=time;this.memory=memory;}
        @Override public String toString(){return "RemoteCandidate[id="+id+"]";}
    }
    private final RemoteProblemRef ref;
    private final String handle;
    private final PersonalRemoteTransport http;
    private final ObjectMapper json=new ObjectMapper();
    public PersonalRemoteSource(RemoteProblemRef ref,String handle,PersonalRemoteTransport http) {
        this.ref=Objects.requireNonNull(ref);this.http=Objects.requireNonNull(http);
        if(handle==null || !handle.matches("[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Invalid source handle");this.handle=handle;
    }
    private Document authenticated(String path) {
        Document document=Jsoup.parse(http.get(path));RemoteSessionIdentity.require(ref.platform,handle,document);return document;
    }
    public Form form() {return parseForm(authenticated(ref.formPath()));}
    Form parseForm(Document document) {
        if("NOWCODER".equals(ref.platform))return nowcoderForm(document);
        boolean at="ATCODER".equals(ref.platform);Element form=null;
        URI current=URI.create(ref.origin()+ref.formPath());
        for(Element candidate:document.select("form[method=post],form[method=POST]")) {
            URI target;
            try{String action=candidate.attr("action");target=action.isEmpty()?URI.create(ref.origin()+ref.submitPath()):current.resolve(action);}
            catch(Exception e){continue;}
            if(!ref.origin().equals(target.getScheme()+"://"+target.getAuthority()) || !ref.submitPath().equals(target.getPath()) || target.getFragment()!=null)continue;
            if(candidate.select(at?"[name=data.LanguageId]":"[name=programTypeId]").isEmpty())continue;
            if(form!=null)throw new RemoteSourceFailure(FORMAT_CHANGED);form=candidate;
        }
        if(form==null)throw new RemoteSourceFailure(FORMAT_CHANGED);
        String taskName=at?"data.TaskScreenName":"submittedProblemIndex";
        boolean task=false;
        for(Element element:form.select("[name="+taskName+"]")) {
            if("select".equals(element.tagName())){for(Element option:element.select("option"))if(ref.task.equals(option.attr("value")))task=true;}
            else if(ref.task.equals(element.attr("value")))task=true;
        }
        if(!task)throw new RemoteSourceFailure(FORMAT_CHANGED);
        List<Element> selectors=form.select(at?"select[name=data.LanguageId]":"select[name=programTypeId]");
        // The task page should contain one task-specific selector. Do not guess among per-task lists.
        if(selectors.size()!=1)throw new RemoteSourceFailure(FORMAT_CHANGED);
        Map<String,String> languages=new LinkedHashMap<>();
        for(Element option:selectors.get(0).select("option")) {
            String id=option.attr("value"),label=option.text().trim();
            if(!id.matches("[1-9][0-9]{0,8}") || option.hasAttr("disabled"))continue;
            if(label.isEmpty() || label.length()>200 || languages.put(id,label)!=null || languages.size()>500)throw new RemoteSourceFailure(FORMAT_CHANGED);
        }
        if(languages.isEmpty())throw new RemoteSourceFailure(LANGUAGE_CHANGED);
        Map<String,String> hidden=new LinkedHashMap<>();
        Set<String> allowed=at?Collections.singleton("csrf_token"):new HashSet<>(Arrays.asList("csrf_token","ftaa","bfaa","action","_tta"));
        for(Element input:form.select("input[type=hidden]")) {
            String name=input.attr("name"),value=input.attr("value");if(!allowed.contains(name))continue;
            if(value.length()>4096 || value.indexOf('\r')>=0 || value.indexOf('\n')>=0 || hidden.put(name,value)!=null)throw new RemoteSourceFailure(FORMAT_CHANGED);
        }
        if(!hidden.containsKey("csrf_token") || hidden.get("csrf_token").isEmpty())throw new RemoteSourceFailure(FORMAT_CHANGED);
        String path=ref.submitPath();
        if(!at){hidden.put("action","submitSolutionFormSubmitted");try{path+="?csrf_token="+URLEncoder.encode(hidden.get("csrf_token"),"UTF-8");}catch(Exception e){throw new RemoteSourceFailure(FORMAT_CHANGED);}}
        return new Form(path,hidden,languages);
    }
    private Form nowcoderForm(Document document){
        Map<String,String> fields=NowCoderPage.fields(document,"pageInfo");
        String questionId=NowCoderPage.positiveId(fields,"questionId",16),problemId=NowCoderPage.positiveId(fields,"problemId",12);
        if(ref.contest.isEmpty()){
            if(!ref.task.equals(problemId) || fields.containsKey("contestId"))throw new RemoteSourceFailure(FORMAT_CHANGED);
        }else if(!ref.contest.equals(NowCoderPage.positiveId(fields,"contestId",12)) || !"false".equals(fields.get("isTeamSignUp")))throw new RemoteSourceFailure(FORMAT_CHANGED);
        try{
            JsonNode root=json.readTree(http.nowcoderQuestion(questionId));
            if(root==null || !root.path("code").isIntegralNumber() || !root.path("code").canConvertToInt() || root.path("code").asInt()!=0 || !questionId.equals(root.path("data").path("id").asText()) || !root.path("data").path("codingInfo").path("supportLanguages").isArray())throw new RemoteSourceFailure(FORMAT_CHANGED);
            JsonNode supported=root.path("data").path("codingInfo").path("supportLanguages");if(supported.isEmpty() || supported.size()>500)throw new RemoteSourceFailure(LANGUAGE_CHANGED);
            Map<String,String> languages=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
            for(JsonNode language:supported){
                String id=language.path("langId").asText(),label=language.path("langName").asText().trim();
                if(!id.matches("[1-9][0-9]{0,8}") || !language.path("langName").isTextual() || label.isEmpty() || label.length()>200 || !seen.add(id))throw new RemoteSourceFailure(FORMAT_CHANGED);
                if(NOWCODER_ACM_LANGUAGES.contains(id))languages.put(id,label);
            }
            if(languages.isEmpty())throw new RemoteSourceFailure(LANGUAGE_CHANGED);
            Map<String,String> context=new LinkedHashMap<>();context.put("questionId",questionId);context.put("problemId",problemId);context.put("contestId",ref.contest);
            return new Form(null,Collections.emptyMap(),languages,context);
        }catch(RemoteSourceFailure e){throw e;}catch(Exception e){throw new RemoteSourceFailure(FORMAT_CHANGED);}
    }
    /** No retry is permitted here, even for a 200 error page, throttling or lost response. */
    public void post(Form form,String languageId,String code,Runnable beginPost) {
        if("NOWCODER".equals(ref.platform))throw new RemoteSourceFailure(UNAVAILABLE);
        if(!form.languages.containsKey(languageId))throw new RemoteSourceFailure(LANGUAGE_CHANGED);
        if(code==null || code.isEmpty() || code.length()>65535)throw new IllegalArgumentException("Invalid source code");
        Map<String,String> fields=new LinkedHashMap<>(form.hidden);
        if("ATCODER".equals(ref.platform)){fields.put("data.TaskScreenName",ref.task);fields.put("data.LanguageId",languageId);fields.put("sourceCode",code);}
        else{fields.put("submittedProblemIndex",ref.task);fields.put("programTypeId",languageId);fields.put("source",code);fields.put("tabSize","4");}
        // A response is not acceptance evidence. The journal always reconciles with independent source data.
        http.postCode(form.postPath,fields,beginPost);
    }
    public List<Candidate> recent() {
        if("NOWCODER".equals(ref.platform))return nowcoderRecent();
        if("ATCODER".equals(ref.platform))return parseAtCoderRecent(authenticated(ref.contestPath()+"/submissions/me?lang=en"));
        return parseCodeforcesRecent(http.get("/api/user.status?handle="+handle+"&from=1&count=50"));
    }
    private List<Candidate> nowcoderRecent(){
        List<Candidate> result=new ArrayList<>();Set<Long> seen=new HashSet<>();
        if(!ref.contest.isEmpty()){
            authenticated("/");
            try{
                JsonNode root=json.readTree(http.get("/acm-heavy/acm/contest/status-list?id="+ref.contest+"&page=1&pageSize=50&onlyMyStatusFilter=true"));
                if(root==null || !root.path("code").isIntegralNumber() || root.path("code").asLong()!=0 || !ref.contest.equals(root.path("data").path("basicInfo").path("contestId").asText()) || !root.path("data").path("data").isArray() || root.path("data").path("data").size()>50)throw new RemoteSourceFailure(FORMAT_CHANGED);
                for(JsonNode row:root.path("data").path("data")){
                    long id=positive(row.path("submissionId")),at=positive(row.path("submitTime"));if(!seen.add(id) || at>System.currentTimeMillis()+120000)throw new RemoteSourceFailure(FORMAT_CHANGED);
                    boolean own=handle.equals(row.path("userId").asText()) && row.path("isTeam").isBoolean() && !row.path("isTeam").asBoolean();
                    result.add(new Candidate(id,at,own?row.path("index").asText():"",own?handle:"",row.path("languageName").asText(row.path("language").asText()),null,null,null));
                }
            }catch(RemoteSourceFailure e){throw e;}catch(Exception e){throw new RemoteSourceFailure(FORMAT_CHANGED);}
        }else{
            Document document=authenticated("/acm/contest/profile/"+handle+"/practice-coding?page=1&pageSize=50&languageCategoryFilter=-1&orderType=DESC&search=&statusTypeFilter=-1");
            List<Element> tables=document.select("table.table-hover");if(tables.size()!=1 || tables.get(0).select("thead th").size()!=9)throw new RemoteSourceFailure(FORMAT_CHANGED);
            for(Element row:tables.get(0).select("tbody tr")){
                List<Element> cells=row.select("td");if(cells.size()!=9 || result.size()>=50)throw new RemoteSourceFailure(FORMAT_CHANGED);
                try{
                    String text=cells.get(0).text();if(!text.matches("[1-9][0-9]{0,17}"))throw new RemoteSourceFailure(FORMAT_CHANGED);long id=Long.parseLong(text);if(!seen.add(id))throw new RemoteSourceFailure(FORMAT_CHANGED);
                    List<Element> links=cells.get(0).select("a[href]"),problems=cells.get(1).select("a[href]");if(links.size()!=1 || problems.size()!=1)throw new RemoteSourceFailure(FORMAT_CHANGED);
                    URI link=URI.create(ref.origin()).resolve(links.get(0).attr("href"));if(!"https".equals(link.getScheme()) || !"ac.nowcoder.com".equals(link.getHost()) || link.getPort()!=-1 || link.getUserInfo()!=null || link.getFragment()!=null || !"/acm/contest/view-submission".equals(link.getPath()))throw new RemoteSourceFailure(FORMAT_CHANGED);
                    Map<String,String> query=new HashMap<>();for(String pair:Objects.toString(link.getRawQuery(),"").split("&")){String[] parts=pair.split("=",-1);if(parts.length!=2 || query.put(parts[0],parts[1])!=null)throw new RemoteSourceFailure(FORMAT_CHANGED);}
                    if(!text.equals(query.get("submissionId")) || !handle.equals(query.get("uid")))throw new RemoteSourceFailure(FORMAT_CHANGED);
                    Matcher task=Pattern.compile("^/acm/problem/([1-9][0-9]{0,11})$").matcher(problems.get(0).attr("href"));if(!task.matches())throw new RemoteSourceFailure(FORMAT_CHANGED);
                    long at=LocalDateTime.parse(cells.get(8).text(),DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();if(at<0 || at>System.currentTimeMillis()+120000)throw new RemoteSourceFailure(FORMAT_CHANGED);
                    result.add(new Candidate(id,at,task.group(1),handle,cells.get(7).text(),null,null,null));
                }catch(RemoteSourceFailure e){throw e;}catch(Exception e){throw new RemoteSourceFailure(FORMAT_CHANGED);}
            }
        }
        return result;
    }
    List<Candidate> parseCodeforcesRecent(String body) {
        try {
            JsonNode root=json.readTree(body);if(root==null || !"OK".equals(root.path("status").asText()) || !root.path("result").isArray() || root.path("result").size()>50)throw new RemoteSourceFailure(FORMAT_CHANGED);
            List<Candidate> rows=new ArrayList<>();Set<Long> ids=new HashSet<>();
            for(JsonNode row:root.path("result")) {
                long id=positive(row.path("id")),seconds=positive(row.path("creationTimeSeconds"));
                if(!ids.add(id) || seconds>Long.MAX_VALUE/1000)throw new RemoteSourceFailure(FORMAT_CHANGED);
                JsonNode members=row.path("author").path("members");
                if(!members.isArray() || members.size()!=1 || !handle.equalsIgnoreCase(members.get(0).path("handle").asText()) || !ref.contest.equals(row.path("problem").path("contestId").asText()) || !ref.task.equals(row.path("problem").path("index").asText())) {
                    // Baseline covers the entire source history page, including other contests/teams.
                    rows.add(new Candidate(id,seconds*1000,"","","",null,null,null));continue;
                }
                String task=row.path("problem").path("index").asText(),language=row.path("programmingLanguage").asText();
                rows.add(new Candidate(id,seconds*1000,task,handle,language,cfVerdict(row.path("verdict").asText()),optionalInt(row,"timeConsumedMillis"),memoryKb(row)));
            }
            return rows;
        }catch(RemoteSourceFailure e){throw e;}catch(Exception e){throw new RemoteSourceFailure(FORMAT_CHANGED);}
    }
    List<Candidate> parseAtCoderRecent(Document document) {
        List<Candidate> rows=new ArrayList<>();Set<Long> ids=new HashSet<>();
        boolean table=false;
        for(Element heading:document.select("table th"))if("Submission Time".equals(heading.text().trim()))table=true;
        if(!table)throw new RemoteSourceFailure(FORMAT_CHANGED);
        for(Element row:document.select("table tbody tr")) {
            Element detail=row.select("a[href^="+ref.contestPath()+"/submissions/]").first();if(detail==null)continue;
            List<Element> cells=new ArrayList<>();for(Element cell:row.children())if("td".equals(cell.tagName()))cells.add(cell);
            if(cells.size()!=8 && cells.size()!=10)throw new RemoteSourceFailure(FORMAT_CHANGED);
            try {
                long id=Long.parseLong(detail.attr("href").substring((ref.contestPath()+"/submissions/").length()));
                if(id<=0 || !ids.add(id) || rows.size()>=50)throw new RemoteSourceFailure(FORMAT_CHANGED);
                long time=OffsetDateTime.parse(cells.get(0).text().trim(),DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ")).toInstant().toEpochMilli();
                Element task=cells.get(1).select("a").first(),user=cells.get(2).select("a[href^=/users/]").first();
                if(task==null || user==null || !task.attr("href").startsWith(ref.contestPath()+"/tasks/"))throw new RemoteSourceFailure(FORMAT_CHANGED);
                String taskId=task.attr("href").substring((ref.contestPath()+"/tasks/").length()),name=user.attr("href").substring(7);
                rows.add(new Candidate(id,time,taskId,name,cells.get(3).text().trim(),atVerdict(cells.get(6).text().trim()),cells.size()==10?numberWithUnit(cells.get(7).text(),"ms"):null,cells.size()==10?numberWithUnit(cells.get(8).text(),"KB"):null));
            }catch(RemoteSourceFailure e){throw e;}catch(Exception e){throw new RemoteSourceFailure(FORMAT_CHANGED);}
        }
        return rows;
    }
    public long baseline(List<Candidate> rows){long id=0;for(Candidate row:rows)id=Math.max(id,row.id);return id;}
    public static Candidate nowcoderResult(NowCoderJudgeProtocol.Context context,NowCoderJudgeProtocol.Status result){
        if(!context.signature().equals(result.contextHash) || result.submissionId==null || result.verdict==null)throw new RemoteSourceFailure(AMBIGUOUS_RESULT);
        return new Candidate(result.submissionId,0,context.problemId,context.userId,context.languageName,result.verdict,null,null);
    }
    private boolean matches(Candidate row,String language,long baseline,long postedAt) {
        if(postedAt<=0 || row.submittedAt<=0)return false;
        long elapsed=row.submittedAt-postedAt;
        return row.id>baseline && elapsed>=-2000 && elapsed<=POST_MATCH_WINDOW_MILLIS && row.submittedAt<=System.currentTimeMillis()+120000 && ref.task.equals(row.task) && handle.equalsIgnoreCase(row.handle) && language.equals(row.language);
    }
    /** A unique metadata match plus the exact source-code text is required, never max(id). */
    public Candidate reconcile(List<Candidate> rows,String language,String code,long baseline,long postedAt) {
        List<Candidate> possible=new ArrayList<>();for(Candidate row:rows)if(matches(row,language,baseline,postedAt))possible.add(row);
        // NC metadata alone does not prove which code produced a submission. Keep the identity lane
        // for review until an exact source-code proof or the original job acknowledgement is available.
        if("NOWCODER".equals(ref.platform)){if(possible.size()>1)throw new RemoteSourceFailure(AMBIGUOUS_RESULT);return null;}
        if(possible.size()>3)throw new RemoteSourceFailure(AMBIGUOUS_RESULT);
        Candidate found=null;
        for(Candidate row:possible)if(sameCode(code,sourceCode(row.id))) {
            if(found!=null)throw new RemoteSourceFailure(AMBIGUOUS_RESULT);found=row;
        }
        return found;
    }
    public Candidate result(long id,String language,String code,long baseline,long postedAt) {
        for(Candidate row:recent())if(row.id==id) {
            if(!matches(row,language,baseline,postedAt) || !sameCode(code,sourceCode(id)))throw new RemoteSourceFailure(AMBIGUOUS_RESULT);
            return row;
        }
        throw new RemoteSourceFailure(UNAVAILABLE);
    }
    private String sourceCode(long id) {
        Document document=authenticated(ref.submissionPath(id));
        Element code=document.getElementById("ATCODER".equals(ref.platform)?"submission-code":"program-source-text");
        if(code==null)throw new RemoteSourceFailure(FORMAT_CHANGED);
        StringBuilder result=new StringBuilder();appendCode(code,result);if(result.length()>131072)throw new RemoteSourceFailure(FORMAT_CHANGED);return result.toString();
    }
    private void appendCode(Node node,StringBuilder text) {
        if(node instanceof TextNode)text.append(((TextNode)node).getWholeText());
        else if(node instanceof Element && "br".equals(((Element)node).tagName()))text.append('\n');
        else for(Node child:node.childNodes())appendCode(child,text);
    }
    static boolean sameCode(String first,String second){return first.replace("\r\n","\n").equals(second.replace("\r\n","\n"));}
    private long positive(JsonNode value){if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<=0)throw new RemoteSourceFailure(FORMAT_CHANGED);return value.asLong();}
    private Integer optionalInt(JsonNode row,String field){JsonNode value=row.get(field);if(value==null || value.isNull())return null;if(!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt()<0)throw new RemoteSourceFailure(FORMAT_CHANGED);return value.asInt();}
    private Integer memoryKb(JsonNode row){JsonNode value=row.get("memoryConsumedBytes");if(value==null || value.isNull())return null;if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<0 || value.asLong()/1024>Integer.MAX_VALUE)throw new RemoteSourceFailure(FORMAT_CHANGED);return (int)(value.asLong()/1024);}
    private Integer numberWithUnit(String value,String unit){Matcher match=Pattern.compile("^([0-9]{1,10})\\s*"+unit+"$").matcher(value.trim());if(!match.matches())return null;try{return Integer.valueOf(match.group(1));}catch(Exception e){throw new RemoteSourceFailure(FORMAT_CHANGED);}}
    private Integer cfVerdict(String value) {
        switch(value){case "":case "TESTING":return null;case "OK":return 0;case "WRONG_ANSWER":case "CHALLENGED":return -1;case "COMPILATION_ERROR":return -2;case "TIME_LIMIT_EXCEEDED":return 1;case "MEMORY_LIMIT_EXCEEDED":return 2;case "RUNTIME_ERROR":case "IDLENESS_LIMIT_EXCEEDED":case "SECURITY_VIOLATED":return 3;case "FAILED":case "CRASHED":return 4;default:throw new RemoteSourceFailure(FORMAT_CHANGED);}
    }
    private Integer atVerdict(String value) {
        if(value.matches("(?:WJ|WR|Judging|[0-9]+\\s*/\\s*[0-9]+)(?:\\s.*)?"))return null;
        switch(value){case "AC":return 0;case "WA":return -1;case "CE":return -2;case "TLE":return 1;case "MLE":return 2;case "RE":case "OLE":case "QLE":return 3;case "IE":return 4;default:throw new RemoteSourceFailure(FORMAT_CHANGED);}
    }
}
