package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

/** A successful catalogue is published atomically. Fetching never holds database locks. */
@Component
public class RemoteCatalogManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    protected long now() { return System.currentTimeMillis(); }
    private Timestamp time() { return new Timestamp(now()); }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    static String hash(String value) {
        try {
            StringBuilder result=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) result.append(String.format("%02x",b&255));
            return result.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    static String limited(String value,int length) { return value.substring(0,value.offsetByCodePoints(0,Math.min(length,value.codePointCount(0,value.length())))); }
    String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid source data",e); } }
    public Map<String,Object> status() {
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM remote_source_job WHERE source_name='CODEFORCES_PROBLEMS'");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("lastSuccessAt",row.get("last_success_at"));result.put("nextRunAt",row.get("next_run_at"));result.put("lastError",row.get("last_error"));
        result.put("state",row.get("lease_until")!=null && ((Timestamp)row.get("lease_until")).getTime()>now()?"RUNNING":ExternalAccountManager.number(row.get("failures"))>0?"BACKOFF":row.get("last_success_at")==null?"QUEUED":"IDLE");
        for(String field:Arrays.asList("total","added","changed","removed")) result.put(field,row.get(field+"_count"));
        return result;
    }
    public Map<String,Object> requestSync() {
        tx(() -> {
            Map<String,Object> row=jdbc.queryForMap("SELECT * FROM remote_source_job WHERE source_name='CODEFORCES_PROBLEMS' FOR UPDATE");
            if(row.get("last_requested_at")!=null && now()-((Timestamp)row.get("last_requested_at")).getTime()<600000) throw new ObjectiveException(429,"目录十分钟内只能请求更新一次");
            if(row.get("lease_until")!=null && ((Timestamp)row.get("lease_until")).getTime()>now()) throw new ObjectiveException(409,"目录正在更新，请等待本次完成");
            jdbc.update("UPDATE remote_source_job SET last_requested_at=?,next_run_at=?,failures=0,last_error=NULL WHERE source_name='CODEFORCES_PROBLEMS'",time(),time());return null;
        });return status();
    }
    public Map<String,Object> claim() {
        return tx(() -> {
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM remote_source_job WHERE source_name='CODEFORCES_PROBLEMS' AND next_run_at<=? AND (lease_until IS NULL OR lease_until<=?) FOR UPDATE",time(),time());
            if(rows.isEmpty()) return null;
            String token=UUID.randomUUID().toString();jdbc.update("UPDATE remote_source_job SET lease_token=?,lease_until=? WHERE source_name='CODEFORCES_PROBLEMS'",token,new Timestamp(now()+180000));
            rows.get(0).put("lease_token",token);return rows.get(0);
        });
    }
    private boolean leased(Map<String,Object> job) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT lease_until FROM remote_source_job WHERE source_name='CODEFORCES_PROBLEMS' AND lease_token=? AND lease_until>? FOR UPDATE",job.get("lease_token"),time());
        // The bound SQL time may predate a wait for the current job row.
        return !rows.isEmpty() && ((Timestamp)rows.get(0).get("lease_until")).getTime()>now();
    }
    public void apply(Map<String,Object> job,JsonNode result) {
        JsonNode problems=result==null?null:result.get("problems");
        if(problems==null || !problems.isArray() || problems.size()==0 || problems.size()>100000) throw new IllegalArgumentException("Invalid catalogue");
        String token=UUID.randomUUID().toString();List<Object[]> catalogue=new ArrayList<>(),evidence=new ArrayList<>();Set<String> keys=new HashSet<>();
        Map<String,String> hashes=new HashMap<>();
        for(JsonNode problem:problems) {
            String key=ProblemIdentity.codeforces(problem);if(!key.startsWith("cf:contest:") || !keys.add(key)) throw new IllegalArgumentException("Invalid catalogue identity");
            if(!problem.path("name").isTextual() || problem.path("name").asText().trim().isEmpty()) throw new IllegalArgumentException("Missing source title");
            String title=limited(problem.path("name").asText(),255);Integer rating=null;
            if(problem.has("rating")) {if(!problem.path("rating").isIntegralNumber() || !problem.path("rating").canConvertToInt() || problem.path("rating").asInt()<0) throw new IllegalArgumentException("Invalid rating");rating=problem.path("rating").asInt();}
            if(!problem.path("tags").isArray() || problem.path("tags").size()>64) throw new IllegalArgumentException("Invalid source tags");
            SortedSet<String> tags=new TreeSet<>();for(JsonNode tag:problem.path("tags")) {if(!tag.isTextual() || tag.asText().length()>100) throw new IllegalArgumentException("Invalid source tag");tags.add(tag.asText());}
            String tagJson=write(tags),fingerprint=hash(write(Arrays.asList(title,rating,tags)));hashes.put(key,fingerprint);
            catalogue.add(new Object[]{key,problem.path("contestId").asLong(),problem.path("index").asText().toUpperCase(Locale.ROOT),title,rating,tagJson,fingerprint,token,time(),time()});
            evidence.add(new Object[]{key,"CODEFORCES",title,rating,tagJson,time()});
        }
        tx(() -> {
            if(!leased(job)) return null;
            int added=0,changed=0;
            Map<String,Map<String,Object>> previous=new HashMap<>();for(Map<String,Object> row:jdbc.queryForList("SELECT problem_key,content_hash,available FROM remote_catalog_problem")) previous.put((String)row.get("problem_key"),row);
            for(String key:keys) {Map<String,Object> old=previous.get(key);if(old==null) added++;else if(!hashes.get(key).equals(old.get("content_hash")) || !ExternalAccountManager.flag(old.get("available"))) changed++;}
            jdbc.batchUpdate("INSERT INTO remote_catalog_problem(problem_key,contest_id,problem_index,title,rating,tags_json,content_hash,seen_token,last_seen_at,changed_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE title=VALUES(title),rating=VALUES(rating),tags_json=VALUES(tags_json),revision=CASE WHEN content_hash<>VALUES(content_hash) OR available=0 THEN revision+1 ELSE revision END,changed_at=CASE WHEN content_hash<>VALUES(content_hash) OR available=0 THEN VALUES(changed_at) ELSE changed_at END,content_hash=VALUES(content_hash),seen_token=VALUES(seen_token),last_seen_at=VALUES(last_seen_at),available=1",catalogue);
            jdbc.batchUpdate("INSERT INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE title=VALUES(title),rating=VALUES(rating),tags_json=VALUES(tags_json),synced_at=VALUES(synced_at)",evidence);
            int removed=jdbc.update("UPDATE remote_catalog_problem SET available=0,changed_at=?,revision=revision+1 WHERE available=1 AND seen_token<>?",time(),token);
            jdbc.update("UPDATE remote_source_job SET next_run_at=?,last_success_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL,total_count=?,added_count=?,changed_count=?,removed_count=? WHERE source_name='CODEFORCES_PROBLEMS'",new Timestamp(now()+3600000),time(),keys.size(),added,changed,removed);return null;
        });
    }
    public void failed(Map<String,Object> job,String reason) {
        tx(() -> {
            if(!leased(job)) return null;
            int failures=Math.min(10,((Number)job.get("failures")).intValue()+1);
            jdbc.update("UPDATE remote_source_job SET failures=?,last_error=?,next_run_at=?,lease_token=NULL,lease_until=NULL WHERE source_name='CODEFORCES_PROBLEMS'",failures,limited(reason,200),new Timestamp(now()+Math.min(21600000L,60000L*(1L<<Math.min(9,failures-1)))));return null;
        });
    }
    public Map<String,Object> list(int page,String keyword,Integer minimum,Integer maximum) {
        if(page<1 || page>10000 || keyword!=null && keyword.length()>100 || minimum!=null && minimum<0 || maximum!=null && maximum<0 || minimum!=null && maximum!=null && minimum>maximum) throw new ObjectiveException(400,"目录筛选条件无效");
        String where=" WHERE c.available=1";List<Object> args=new ArrayList<>();
        if(keyword!=null && !keyword.trim().isEmpty()) {where+=" AND (c.title LIKE ? OR CONCAT(c.contest_id,c.problem_index) LIKE ?)";args.add("%"+keyword.trim()+"%");args.add("%"+keyword.trim()+"%");}
        if(minimum!=null) {where+=" AND c.rating>=?";args.add(minimum);}if(maximum!=null) {where+=" AND c.rating<=?";args.add(maximum);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM remote_catalog_problem c"+where,Long.class,args.toArray());args.add((page-1)*30);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT c.*,i.state AS import_state,i.problem_id AS local_id,i.last_error AS import_error,i.catalog_hash,en.content_hash AS statement_hash,zh.source_hash AS translation_source_hash FROM remote_catalog_problem c LEFT JOIN remote_problem_import i ON i.problem_key=c.problem_key LEFT JOIN remote_statement_current sc ON sc.problem_key=c.problem_key LEFT JOIN remote_statement_version en ON en.id=sc.version_id LEFT JOIN remote_statement_version zh ON zh.id=sc.translation_version_id"+where+" ORDER BY c.contest_id DESC,c.problem_index LIMIT 30 OFFSET ?",args.toArray());
        List<Map<String,Object>> views=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            Map<String,Object> view=new LinkedHashMap<>();String key=(String)row.get("problem_key"),display=row.get("contest_id")+String.valueOf(row.get("problem_index"));
            view.put("problemKey",key);view.put("displayId",display);view.put("title",row.get("title"));view.put("rating",row.get("rating"));view.put("revision",row.get("revision"));view.put("changedAt",row.get("changed_at"));
            try {view.put("tags",json.readTree((String)row.get("tags_json")));}catch(Exception e){throw new IllegalStateException(e);}
            view.put("sourceUrl","https://codeforces.com/problemset/problem/"+row.get("contest_id")+"/"+row.get("problem_index"));
            List<Map<String,Object>> local=jdbc.queryForList("SELECT id,is_remote,auth FROM problem WHERE problem_id=? AND gid IS NULL AND (is_group=0 OR is_group IS NULL)","CF-"+display);
            view.put("localId",local.isEmpty()?null:local.get(0).get("id"));view.put("localAuth",local.isEmpty()?null:local.get(0).get("auth"));
            view.put("importState",!local.isEmpty() && !ExternalAccountManager.flag(local.get(0).get("is_remote"))?"CONFLICT":row.get("import_state"));view.put("importError",row.get("import_error"));
            view.put("translationStale",row.get("translation_source_hash")!=null && !Objects.equals(row.get("translation_source_hash"),row.get("statement_hash")));view.put("metadataChanged",row.get("catalog_hash")!=null && !row.get("catalog_hash").equals(row.get("content_hash")));views.add(view);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("records",views);result.put("total",total);result.put("status",status());return result;
    }
}
