package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.ai.AiAccessGuard;
import top.hcode.hoj.manager.oj.external.*;
import top.hcode.hoj.pojo.dto.LearningHistoryDTO;
import top.hcode.hoj.utils.ShiroUtils;
import java.net.URLEncoder;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

/** Own drill-down of exactly the programming evidence used by the ability chart. */
@Component
public class LearningProblemHistoryManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LearningProfileManager profiles;
    @Autowired private AlgorithmCatalog catalog;
    @Autowired private ObjectMapper json;
    @Autowired private AiAccessGuard access;
    private static final List<String> SOURCES = Arrays.asList("HOJ","CODEFORCES","ATCODER","NOWCODER");
    protected String uid() { if(ShiroUtils.getProfile()==null)throw new ObjectiveException(401,"请先登录");return ShiroUtils.getProfile().getUid(); }
    protected long now() { return System.currentTimeMillis(); }
    private long number(Object value) { return value instanceof Number?((Number)value).longValue():0; }
    private boolean flag(Object value) { return Boolean.TRUE.equals(value)||value instanceof Number&&number(value)!=0; }
    private Integer rating(Object value) { return value instanceof Number&&number(value)>=0?((Number)value).intValue():null; }
    private Long millis(Object value,boolean seconds) {
        if(value instanceof java.util.Date)return ((java.util.Date)value).getTime();
        long n=number(value);return n<=0||seconds&&n>Long.MAX_VALUE/1000?null:seconds?n*1000:n;
    }
    private String localUrl(String id) { try{return "/problem/"+URLEncoder.encode(id,"UTF-8").replace("+","%20");}catch(Exception e){throw new IllegalStateException(e);} }
    private Set<String> groups(Object text) {
        Set<String> result=new TreeSet<>();
        try{JsonNode tags=json.readTree((String)text);if(!tags.isArray())throw new IllegalArgumentException();for(JsonNode tag:tags){AlgorithmCatalog.Topic topic=catalog.topicForTag(tag.asText());if(topic!=null)result.add(topic.getGroupCode());}return result;}
        catch(Exception e){throw new ObjectiveException(503,"来源分类暂不可用，请稍后重试");}
    }
    private Map<String,Object> entry(String source,String key,Object solved,Object submissions,Object first,Object last,Object passed,boolean seconds) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("source",source);result.put("problemKey",key);result.put("solved",flag(solved));result.put("submissions",number(submissions));
        result.put("firstAttemptAt",millis(first,seconds));result.put("lastAttemptAt",millis(last,seconds));result.put("firstSolvedAt",millis(passed,seconds));
        result.put("difficultyMetric","HOJ".equals(source)?"HOJ_RATING":"CODEFORCES".equals(source)?"CODEFORCES_PROBLEM_RATING":"ATCODER".equals(source)?"ATCODER_PROBLEMS_ESTIMATE":"NOWCODER_SOURCE_RATING");return result;
    }
    private Map<String,Map<String,Object>> metadata(ProblemIdentityResolver resolver,String table,Collection<String> keys) {
        Map<String,Map<String,Object>> result=new HashMap<>();for(Map<String,Object> row:resolver.catalogue(table,keys))result.put((String)row.get("problem_key"),row);return result;
    }
    private String remoteUrl(String source,String key,Map<String,Object> meta) {
        try {
            if("CODEFORCES".equals(source))return meta!=null&&flag(meta.get("available"))?CodeforcesStatementClient.sourceUrl(key):null;
            if("ATCODER".equals(source))return meta==null?null:AtCoderStatementClient.sourceUrl((String)meta.get("contest_id"),(String)meta.get("task_id"));
            return NowCoderStatementClient.sourceUrl(key);
        }catch(IllegalArgumentException e){return null;}
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> list(LearningHistoryDTO.Query query) {
        String uid=uid();access.requireActiveUser(uid);
        if(query==null||!("ALL".equals(query.getSource())||SOURCES.contains(query.getSource()))||!Arrays.asList("ALL","SOLVED","ATTEMPTED").contains(query.getStatus())
                ||query.getPage()==null||query.getPage()<1||query.getPage()>100000||query.getKeyword()!=null&&query.getKeyword().length()>100
                ||query.getGroupCode()!=null&&!query.getGroupCode().isEmpty()&&!catalog.groups().containsKey(query.getGroupCode()))throw new ObjectiveException(400,"题目筛选条件无效");
        List<Map<String,Object>> local=profiles.publicProgrammingDetails(uid);
        Map<Long,Set<String>> localGroups=profiles.publicProgrammingGroups(uid);
        List<Map<String,Object>> external=jdbc.queryForList("SELECT a.platform,a.handle,a.id AS account_id,s.problem_key,p.title,p.rating,p.tags_json,MAX(CASE WHEN s.verdict='OK' THEN 1 ELSE 0 END) AS solved,COUNT(*) AS submissions,MIN(s.submitted_at) AS first_at,MAX(s.submitted_at) AS last_at,MIN(CASE WHEN s.verdict='OK' THEN s.submitted_at ELSE NULL END) AS first_solved_at FROM external_account a JOIN external_submission s ON s.account_id=a.id JOIN external_problem p ON p.problem_key=s.problem_key AND p.platform=a.platform WHERE a.uid=? AND a.verified_identity IS NOT NULL AND a.platform IN ('CODEFORCES','ATCODER','NOWCODER') AND (a.platform<>'CODEFORCES' OR s.individual=1) GROUP BY a.platform,a.handle,a.id,s.problem_key,p.title,p.rating,p.tags_json",uid);
        Set<String> keys=new HashSet<>();for(Map<String,Object> row:local)keys.add(ProblemIdentity.local(number(row.get("id")),(String)row.get("problem_id"),flag(row.get("is_remote"))));for(Map<String,Object> row:external)keys.add((String)row.get("problem_key"));
        ProblemIdentityResolver resolver=new ProblemIdentityResolver(jdbc);Map<String,String> identities=resolver.resolve(keys);
        Set<String> sourceKeysWithEvidence=external.stream().map(row->identities.get((String)row.get("problem_key"))).collect(Collectors.toSet());
        Map<String,Set<String>> publicGroups=new PublicSourceClassification(jdbc,catalog).groups(sourceKeysWithEvidence);
        Map<String,Map<String,Map<String,Object>>> meta=new HashMap<>();
        for(String source:SOURCES.subList(1,4)) {
            String prefix="CODEFORCES".equals(source)?"cf:":"ATCODER".equals(source)?"atcoder:":"nowcoder:";
            Set<String> selected=identities.values().stream().filter(key->key.startsWith(prefix)).collect(Collectors.toSet());
            meta.put(source,metadata(resolver,"CODEFORCES".equals(source)?"remote_catalog_problem":"ATCODER".equals(source)?"external_atcoder_problem":"remote_nowcoder_problem",selected));
        }
        Map<String,List<Map<String,Object>>> all=new LinkedHashMap<>();Map<String,Set<String>> classified=new HashMap<>();
        for(Map<String,Object> row:local){
            String key=ProblemIdentity.local(number(row.get("id")),(String)row.get("problem_id"),flag(row.get("is_remote"))),canonical=identities.get(key);
            Map<String,Object> item=entry("HOJ",key,row.get("solved"),row.get("submissions"),row.get("first_at"),row.get("last_at"),row.get("first_solved_at"),false);
            item.put("displayId",row.get("problem_id"));item.put("title",row.get("title"));item.put("url",localUrl((String)row.get("problem_id")));item.put("rating",rating(row.get("rating")));item.put("handle",null);item.put("metadataAvailable",true);
            all.computeIfAbsent(canonical,ignored->new ArrayList<>()).add(item);classified.computeIfAbsent(canonical,ignored->new TreeSet<>()).addAll(localGroups.getOrDefault(number(row.get("id")),Collections.emptySet()));
        }
        for(Map<String,Object> row:external){
            String source=(String)row.get("platform"),key=(String)row.get("problem_key"),canonical=identities.get(key);Map<String,Object> data=meta.get(source).get(canonical);boolean available=data!=null&&flag(data.get("available"));
            classified.computeIfAbsent(canonical,ignored->new TreeSet<>()).addAll(publicGroups.getOrDefault(canonical,Collections.emptySet()));
            Map<String,Object> item=entry(source,key,row.get("solved"),row.get("submissions"),row.get("first_at"),row.get("last_at"),row.get("first_solved_at"),true);
            item.put("displayId",key);item.put("title",available?data.get("title"):row.get("title"));item.put("handle",row.get("handle"));item.put("url",remoteUrl(source,key,data));item.put("metadataAvailable",available);
            item.put("rating","CODEFORCES".equals(source)?rating(available?data.get("rating"):row.get("rating")):"ATCODER".equals(source)?rating(data==null?row.get("rating"):available?data.get("estimated_difficulty"):null):rating(available?data.get("source_rating"):null));
            if("CODEFORCES".equals(source))classified.computeIfAbsent(canonical,ignored->new TreeSet<>()).addAll(groups(available?data.get("tags_json"):row.get("tags_json")));
            all.computeIfAbsent(canonical,ignored->new ArrayList<>()).add(item);
        }
        String keyword=query.getKeyword()==null?"":query.getKeyword().trim().toLowerCase(Locale.ROOT);
        List<Map<String,Object>> rows=new ArrayList<>();long solved=0,solvedRecords=0,records=0;
        for(Map.Entry<String,List<Map<String,Object>>> group:all.entrySet()){
            boolean anySolved=group.getValue().stream().anyMatch(item->flag(item.get("solved")));if(anySolved)solved++;
            solvedRecords+=group.getValue().stream().filter(item->flag(item.get("solved"))).count();records+=group.getValue().size();
            List<Map<String,Object>> entries=group.getValue().stream().filter(item->"ALL".equals(query.getSource())||query.getSource().equals(item.get("source"))).collect(Collectors.toList());if(entries.isEmpty())continue;
            boolean passed=entries.stream().anyMatch(item->flag(item.get("solved")));if("SOLVED".equals(query.getStatus())&&!passed||"ATTEMPTED".equals(query.getStatus())&&passed||query.isOnlyDuplicates()&&group.getValue().size()<2)continue;
            Set<String> groups=classified.getOrDefault(group.getKey(),Collections.emptySet());if(query.getGroupCode()!=null&&!query.getGroupCode().isEmpty()&&!groups.contains(query.getGroupCode()))continue;
            if(!keyword.isEmpty()&&!group.getKey().toLowerCase(Locale.ROOT).contains(keyword)&&entries.stream().noneMatch(item->(item.get("title")+" "+item.get("displayId")).toLowerCase(Locale.ROOT).contains(keyword)))continue;
            entries.sort(Comparator.comparingInt((Map<String,Object> item)->SOURCES.indexOf(item.get("source"))).thenComparing(item->item.get("problemKey")+" "+item.get("handle")+" "+item.get("displayId")));
            Map<String,Object> row=new LinkedHashMap<>();row.put("identity",group.getKey());row.put("title",entries.get(0).get("title"));row.put("solved",passed);row.put("entries",entries);row.put("recordCount",group.getValue().size());row.put("groups",groups.stream().map(code->{Map<String,String> value=new LinkedHashMap<>();value.put("code",code);value.put("name",catalog.groups().get(code));return value;}).collect(Collectors.toList()));
            row.put("lastAttemptAt",entries.stream().map(item->item.get("lastAttemptAt")).filter(Objects::nonNull).mapToLong(this::number).max().orElse(0));rows.add(row);
        }
        rows.sort(Comparator.comparingLong((Map<String,Object> row)->number(row.get("lastAttemptAt"))).reversed().thenComparing(row->(String)row.get("identity")));
        int offset=Math.min(rows.size(),(query.getPage()-1)*20);Map<String,Object> result=new LinkedHashMap<>();result.put("uid",uid);result.put("items",new ArrayList<>(rows.subList(offset,Math.min(rows.size(),offset+20))));result.put("total",rows.size());result.put("page",query.getPage());result.put("pageSize",20);
        Map<String,Object> totals=new LinkedHashMap<>();totals.put("attempted",all.size());totals.put("solved",solved);totals.put("sourceRecords",records);totals.put("solvedSourceRecords",solvedRecords);totals.put("duplicatesRemoved",solvedRecords-solved);result.put("totals",totals);result.put("groups",catalog.groups());result.put("asOf",now());result.put("scope","PUBLIC_LOCAL_PRACTICE_AND_VERIFIED_EXTERNAL_PROGRAMMING");result.put("assistanceTracked",false);
        List<Map<String,Object>> accounts=jdbc.queryForList("SELECT a.platform,a.history_complete,a.last_success_at,j.failures FROM external_account a JOIN external_sync_job j ON j.account_id=a.id WHERE a.uid=? AND a.verified_identity IS NOT NULL",uid);
        result.put("verifiedAccounts",accounts.size());result.put("historyComplete",accounts.stream().allMatch(row->flag(row.get("history_complete"))&&row.get("last_success_at")!=null));result.put("stale",accounts.stream().anyMatch(row->row.get("last_success_at")==null||now()-((Timestamp)row.get("last_success_at")).getTime()>3600000||number(row.get("failures"))>0));
        access.requireActiveUser(uid);return result;
    }
}
