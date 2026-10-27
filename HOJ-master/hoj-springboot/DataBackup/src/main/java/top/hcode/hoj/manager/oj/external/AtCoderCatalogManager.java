package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

/** AtCoder Problems estimates are separate from official AtCoder ratings and HOJ ratings. */
@Component
public class AtCoderCatalogManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    protected long now() { return System.currentTimeMillis(); }
    private Timestamp time() { return new Timestamp(now()); }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    public Map<String,Object> claim() {
        return tx(() -> {
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM remote_source_job WHERE source_name IN ('ATCODER_PROBLEMS','ATCODER_MODELS') AND next_run_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_run_at,source_name LIMIT 1 FOR UPDATE",time(),time());
            if(rows.isEmpty()) return null;
            Map<String,Object> job=rows.get(0);String token=UUID.randomUUID().toString();
            jdbc.update("UPDATE remote_source_job SET lease_token=?,lease_until=? WHERE source_name=?",token,new Timestamp(now()+180000),job.get("source_name"));
            job.put("lease_token",token);return job;
        });
    }
    private boolean leased(Map<String,Object> job) {
        return !jdbc.queryForList("SELECT source_name FROM remote_source_job WHERE source_name=? AND source_name IN ('ATCODER_PROBLEMS','ATCODER_MODELS') AND lease_token=? FOR UPDATE",job.get("source_name"),job.get("lease_token")).isEmpty();
    }
    public void apply(Map<String,Object> job,JsonNode result) {
        if("ATCODER_PROBLEMS".equals(job.get("source_name"))) problems(job,result);
        else if("ATCODER_MODELS".equals(job.get("source_name"))) models(job,result);
        else throw new IllegalArgumentException("Unknown AtCoder dataset");
    }
    private void problems(Map<String,Object> job,JsonNode result) {
        if(result==null || !result.isArray() || result.isEmpty() || result.size()>100000) throw new IllegalArgumentException("Invalid AtCoder catalogue");
        List<Object[]> rows=new ArrayList<>();Set<String> seen=new HashSet<>();String token=UUID.randomUUID().toString();Map<String,String> hashes=new HashMap<>();
        for(JsonNode problem:result) {
            String task=problem.path("id").asText(),contest=problem.path("contest_id").asText(),index=problem.path("problem_index").asText();
            String key=ProblemIdentity.atcoder(task);
            // Existing source-key tables use a case-insensitive collation. Reject ambiguity, never merge it.
            if(!seen.add(key.toLowerCase(Locale.ROOT)) || !contest.matches("[A-Za-z0-9_-]{1,80}") || index.isEmpty() || index.length()>32 || !problem.path("title").isTextual() || problem.path("title").asText().trim().isEmpty()) throw new IllegalArgumentException("Invalid AtCoder identity");
            String title=RemoteCatalogManager.limited(problem.path("title").asText(),255);
            String hash=RemoteCatalogManager.hash(task+"\n"+contest+"\n"+index+"\n"+title);hashes.put(key,hash);
            rows.add(new Object[]{key,task,contest,index,title,hash,token,time(),time()});
        }
        tx(() -> {
            if(!leased(job)) return null;
            Map<String,Map<String,Object>> old=new HashMap<>();for(Map<String,Object> row:jdbc.queryForList("SELECT problem_key,content_hash,available FROM external_atcoder_problem")) old.put((String)row.get("problem_key"),row);
            int added=0,changed=0;for(Map.Entry<String,String> item:hashes.entrySet()) {Map<String,Object> previous=old.get(item.getKey());if(previous==null)added++;else if(!item.getValue().equals(previous.get("content_hash")) || !ExternalAccountManager.flag(previous.get("available")))changed++;}
            jdbc.batchUpdate("INSERT INTO external_atcoder_problem(problem_key,task_id,contest_id,problem_index,title,content_hash,seen_token,last_seen_at,changed_at) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE task_id=VALUES(task_id),contest_id=VALUES(contest_id),problem_index=VALUES(problem_index),title=VALUES(title),revision=CASE WHEN content_hash<>VALUES(content_hash) OR available=0 THEN revision+1 ELSE revision END,changed_at=CASE WHEN content_hash<>VALUES(content_hash) OR available=0 THEN VALUES(changed_at) ELSE changed_at END,content_hash=VALUES(content_hash),seen_token=VALUES(seen_token),last_seen_at=VALUES(last_seen_at),available=1",rows);
            int removed=jdbc.update("UPDATE external_atcoder_problem SET available=0,changed_at=?,revision=revision+1 WHERE available=1 AND seen_token<>?",time(),token);
            publishEvidence();finish(job,result.size(),added,changed,removed,3600000L);return null;
        });
    }
    static Integer estimate(double raw,boolean experimental) {
        if(!Double.isFinite(raw) || raw< -1000000 || raw>1000000) throw new IllegalArgumentException("Invalid difficulty model");
        return experimental?null:(int)Math.round(raw>=400?raw:400/Math.exp(1-raw/400));
    }
    private void models(Map<String,Object> job,JsonNode result) {
        if(result==null || !result.isObject() || result.isEmpty() || result.size()>100000) throw new IllegalArgumentException("Invalid AtCoder models");
        List<Object[]> rows=new ArrayList<>();Set<String> seen=new HashSet<>();Iterator<Map.Entry<String,JsonNode>> fields=result.fields();
        while(fields.hasNext()) {
            Map.Entry<String,JsonNode> item=fields.next();String key=ProblemIdentity.atcoder(item.getKey());JsonNode model=item.getValue();
            if(!seen.add(key.toLowerCase(Locale.ROOT)) || !model.isObject()) throw new IllegalArgumentException("Invalid model identity");
            // Provider also publishes prediction models without a difficulty; those remain unknown.
            if(!model.has("difficulty")) continue;
            if(!model.path("difficulty").isNumber() || !model.path("is_experimental").isBoolean()) throw new IllegalArgumentException("Invalid difficulty model");
            double raw=model.path("difficulty").asDouble();boolean experimental=model.path("is_experimental").asBoolean();
            rows.add(new Object[]{raw,estimate(raw,experimental),experimental,time(),key});
        }
        tx(() -> {
            if(!leased(job)) return null;
            jdbc.update("UPDATE external_atcoder_problem SET raw_difficulty=NULL,estimated_difficulty=NULL,model_experimental=NULL,model_updated_at=?",time());
            if(!rows.isEmpty()) jdbc.batchUpdate("UPDATE external_atcoder_problem SET raw_difficulty=?,estimated_difficulty=?,model_experimental=?,model_updated_at=? WHERE problem_key=?",rows);
            publishEvidence();finish(job,result.size(),0,rows.size(),0,21600000L);return null;
        });
    }
    private void publishEvidence() {
        List<Object[]> rows=new ArrayList<>();for(Map<String,Object> problem:jdbc.queryForList("SELECT problem_key,title,estimated_difficulty,available FROM external_atcoder_problem")) rows.add(new Object[]{problem.get("problem_key"),"ATCODER",problem.get("title"),ExternalAccountManager.flag(problem.get("available"))?problem.get("estimated_difficulty"):null,"[]",time()});
        if(!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE title=VALUES(title),rating=VALUES(rating),tags_json=VALUES(tags_json),synced_at=VALUES(synced_at)",rows);
    }
    private void finish(Map<String,Object> job,int total,int added,int changed,int removed,long interval) {
        jdbc.update("UPDATE remote_source_job SET last_success_at=?,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL,total_count=?,added_count=?,changed_count=?,removed_count=? WHERE source_name=?",time(),new Timestamp(now()+interval),total,added,changed,removed,job.get("source_name"));
    }
    public void failed(Map<String,Object> job,String reason) {
        tx(() -> {if(!leased(job)) return null;int failures=Math.min(10,((Number)job.get("failures")).intValue()+1);
            jdbc.update("UPDATE remote_source_job SET failures=?,last_error=?,next_run_at=?,lease_token=NULL,lease_until=NULL WHERE source_name=?",failures,RemoteCatalogManager.limited(reason,200),new Timestamp(now()+Math.min(21600000L,60000L*(1L<<Math.min(9,failures-1)))),job.get("source_name"));return null;});
    }
    public Map<String,Object> status(){
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM remote_source_job WHERE source_name='ATCODER_PROBLEMS'"),result=new LinkedHashMap<>();
        result.put("lastSuccessAt",row.get("last_success_at"));result.put("nextRunAt",row.get("next_run_at"));result.put("lastError",row.get("last_error"));
        result.put("state",row.get("lease_until")!=null && ((Timestamp)row.get("lease_until")).getTime()>now()?"RUNNING":ExternalAccountManager.number(row.get("failures"))>0?"BACKOFF":row.get("last_success_at")==null?"QUEUED":"IDLE");
        for(String field:Arrays.asList("total","added","changed","removed"))result.put(field,row.get(field+"_count"));return result;
    }
    public Map<String,Object> requestSync(){
        tx(()->{
            List<Map<String,Object>> jobs=jdbc.queryForList("SELECT * FROM remote_source_job WHERE source_name IN ('ATCODER_PROBLEMS','ATCODER_MODELS') ORDER BY source_name FOR UPDATE");
            for(Map<String,Object> row:jobs){
                if(row.get("last_requested_at")!=null && now()-((Timestamp)row.get("last_requested_at")).getTime()<600000)throw new ObjectiveException(429,"目录十分钟内只能请求更新一次");
                if(row.get("lease_until")!=null && ((Timestamp)row.get("lease_until")).getTime()>now())throw new ObjectiveException(409,"目录正在更新，请等待本次完成");
            }
            jdbc.update("UPDATE remote_source_job SET last_requested_at=?,next_run_at=?,failures=0,last_error=NULL WHERE source_name IN ('ATCODER_PROBLEMS','ATCODER_MODELS')",time(),time());return null;
        });return status();
    }
    public Map<String,Object> list(int page,String keyword,Integer minimum,Integer maximum){
        if(page<1 || page>10000 || keyword!=null && keyword.length()>100 || minimum!=null && minimum<0 || maximum!=null && maximum<0 || minimum!=null && maximum!=null && minimum>maximum)throw new ObjectiveException(400,"目录筛选条件无效");
        String where=" WHERE c.available=1";List<Object> args=new ArrayList<>();
        if(keyword!=null && !keyword.trim().isEmpty()){where+=" AND (c.title LIKE ? OR c.task_id LIKE ? OR c.contest_id LIKE ?)";for(int n=0;n<3;n++)args.add("%"+keyword.trim()+"%");}
        if(minimum!=null){where+=" AND c.estimated_difficulty>=?";args.add(minimum);}if(maximum!=null){where+=" AND c.estimated_difficulty<=?";args.add(maximum);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM external_atcoder_problem c"+where,Long.class,args.toArray());args.add((page-1)*30);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT c.*,i.state AS import_state,i.last_error AS import_error,i.catalog_hash,en.content_hash AS statement_hash,zh.source_hash AS translation_source_hash FROM external_atcoder_problem c LEFT JOIN remote_problem_import i ON i.problem_key=c.problem_key LEFT JOIN remote_statement_current sc ON sc.problem_key=c.problem_key LEFT JOIN remote_statement_version en ON en.id=sc.version_id LEFT JOIN remote_statement_version zh ON zh.id=sc.translation_version_id"+where+" ORDER BY c.contest_id DESC,c.problem_index,c.task_id LIMIT 30 OFFSET ?",args.toArray());
        List<Map<String,Object>> records=new ArrayList<>();for(Map<String,Object> row:rows){
            Map<String,Object> view=new LinkedHashMap<>();String task=(String)row.get("task_id");
            view.put("problemKey",row.get("problem_key"));view.put("displayId",task);view.put("title",row.get("title"));view.put("rating",null);view.put("estimatedDifficulty",row.get("estimated_difficulty"));view.put("tags",Collections.emptyList());view.put("revision",row.get("revision"));view.put("changedAt",row.get("changed_at"));
            view.put("sourceUrl",AtCoderStatementClient.sourceUrl((String)row.get("contest_id"),task));
            List<Map<String,Object>> local=jdbc.queryForList("SELECT id,auth,is_remote,is_group,gid FROM problem WHERE problem_id=?","AC-"+task);
            boolean conflict=!local.isEmpty() && (!ExternalAccountManager.flag(local.get(0).get("is_remote")) || ExternalAccountManager.flag(local.get(0).get("is_group")) || local.get(0).get("gid")!=null);
            view.put("localId",local.isEmpty()?null:local.get(0).get("id"));view.put("localAuth",local.isEmpty()?null:local.get(0).get("auth"));view.put("importState",conflict?"CONFLICT":row.get("import_state"));view.put("importError",row.get("import_error"));view.put("translationStale",row.get("translation_source_hash")!=null && !Objects.equals(row.get("translation_source_hash"),row.get("statement_hash")));view.put("metadataChanged",row.get("catalog_hash")!=null && !row.get("catalog_hash").equals(row.get("content_hash")));records.add(view);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("records",records);result.put("total",total);result.put("status",status());return result;
    }
}
