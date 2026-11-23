package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static top.hcode.hoj.manager.oj.external.ExternalAccountManager.*;

/** Called inside the account lease transaction. Cleanup and source totals require a stable head recheck. */
@Component
public class NowCoderEvidenceManager {
    @Autowired private JdbcTemplate jdbc;
    protected long now(){return System.currentTimeMillis();}
    private Timestamp time(){return new Timestamp(now());}
    public void reset(long id){
        jdbc.update("INSERT IGNORE INTO external_nowcoder_profile(account_id) VALUES(?)",id);
        jdbc.update("UPDATE external_nowcoder_profile SET scan_challenged_count=NULL,scan_solved_count=NULL,scan_submission_count=NULL,scan_pages=NULL,scan_last_second=NULL,scan_head_hash=NULL WHERE account_id=?",id);
    }
    public void profile(long id,JsonNode user){
        if(!user.path("displayName").isTextual() || user.path("displayName").asText().isEmpty() || user.path("displayName").asText().length()>255)throw new IllegalArgumentException("Invalid NowCoder display name");
        jdbc.update("UPDATE external_nowcoder_profile SET display_name=? WHERE account_id=?",user.path("displayName").asText(),id);
    }
    public void apply(long id,Map<String,Object> account,Map<String,Object> job,JsonNode page){
        if(page==null || !page.isObject() || !account.get("handle").equals(page.path("uid").asText()) || !page.path("items").isArray())throw new IllegalArgumentException("Invalid NowCoder page");
        for(String field:Arrays.asList("page","totalPages","attempted","solved","submissions"))if(!page.path(field).isIntegralNumber() || !page.path(field).canConvertToInt() || page.path(field).asInt()<0)throw new IllegalArgumentException("Invalid NowCoder counts");
        int total=page.path("submissions").asInt(),pages=page.path("totalPages").asInt(),from=((Number)job.get("from_index")).intValue();
        boolean verify="VERIFY".equals(job.get("stage"));int expectedPage=verify?1:from;
        if(page.path("page").asInt()!=expectedPage || pages!=(total+49L)/50 || pages>NowCoderDataClient.MAX_PAGES || page.path("solved").asInt()>page.path("attempted").asInt() || page.path("attempted").asInt()>total || page.path("items").size()!=Math.min(50,Math.max(0,total-(expectedPage-1)*50)))throw new IllegalArgumentException("Invalid NowCoder pagination");
        Map<String,Object> snapshot=jdbc.queryForMap("SELECT * FROM external_nowcoder_profile WHERE account_id=?",id);
        long head=0;for(JsonNode item:page.path("items"))head=Math.max(head,item.path("id").asLong());
        if(verify || from>1){
            if(snapshot.get("scan_submission_count")==null || number(snapshot.get("scan_submission_count"))!=total || number(snapshot.get("scan_challenged_count"))!=page.path("attempted").asInt() || number(snapshot.get("scan_solved_count"))!=page.path("solved").asInt() || number(snapshot.get("scan_pages"))!=pages || verify && !digest(page.path("items")).equals(snapshot.get("scan_head_hash"))){restart(id);return;}
        }else{
            jdbc.update("UPDATE external_nowcoder_profile SET scan_challenged_count=?,scan_solved_count=?,scan_submission_count=?,scan_pages=?,scan_head_hash=? WHERE account_id=?",page.path("attempted").asInt(),page.path("solved").asInt(),total,pages,digest(page.path("items")),id);
        }
        if(verify){finish(id,account,job,total);return;}
        if(!"STATUS".equals(job.get("stage")))throw new IllegalArgumentException("Invalid NowCoder stage");
        long low=Long.MAX_VALUE,lowSecond=snapshot.get("scan_last_second")==null?Long.MAX_VALUE:number(snapshot.get("scan_last_second"));Set<Long> ids=new HashSet<>();
        List<Object[]> records=new ArrayList<>();Map<String,Object[]> problems=new LinkedHashMap<>();
        for(JsonNode item:page.path("items")){
            if(!item.path("id").isIntegralNumber() || !item.path("id").canConvertToLong() || !item.path("submittedAt").isIntegralNumber() || !item.path("submittedAt").canConvertToLong())throw new IllegalArgumentException("Invalid NowCoder submission");
            long sid=item.path("id").asLong();if(sid<=0 || !ids.add(sid) || item.path("submittedAt").asLong()>lowSecond){restart(id);return;}low=Math.min(low,sid);lowSecond=item.path("submittedAt").asLong();
            String key=item.path("problemKey").asText(),title=item.path("title").asText(),verdict=item.path("verdict").asText();
            if(!key.matches("nowcoder:(?:acm:[1-9][0-9]{0,11}|contest:[1-9][0-9]{0,11}:[A-Z][A-Z0-9]{0,15}|practice:[a-f0-9]{32})") || title.isEmpty() || title.length()>255 || !Arrays.asList("OK","WRONG_ANSWER","TIME_LIMIT_EXCEEDED","MEMORY_LIMIT_EXCEEDED","RUNTIME_ERROR","COMPILATION_ERROR","PRESENTATION_ERROR","PENDING").contains(verdict) || item.path("submittedAt").asLong()<0 || item.path("submittedAt").asLong()>now()/1000+300)throw new IllegalArgumentException("Invalid NowCoder evidence");
            problems.put(key,new Object[]{key,"NOWCODER",title,null,"[]",time()});
            records.add(new Object[]{id,sid,key,verdict,item.path("submittedAt").asLong(),false,job.get("scan_token")});
        }
        if(!problems.isEmpty())jdbc.batchUpdate("INSERT INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE title=VALUES(title),synced_at=VALUES(synced_at)",new ArrayList<>(problems.values()));
        if(!records.isEmpty())jdbc.batchUpdate("INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE problem_key=VALUES(problem_key),verdict=VALUES(verdict),submitted_at=VALUES(submitted_at),individual=VALUES(individual),seen_scan=VALUES(seen_scan)",records);
        long overlap=account.get("last_success_at")==null?0:((Timestamp)account.get("last_success_at")).getTime()/1000-172800;
        boolean covered=from>=pages || !flag(job.get("full_scan")) && from>=2 && lowSecond<overlap;
        jdbc.update("UPDATE external_nowcoder_profile SET scan_last_second=? WHERE account_id=?",records.isEmpty()?null:lowSecond,id);
        jdbc.update("UPDATE external_sync_job SET stage=?,from_index=?,working_high_id=?,last_page_min_id=?,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",covered?"VERIFY":"STATUS",covered?from:from+1,Math.max(head,number(job.get("working_high_id"))),records.isEmpty()?null:low,time(),id);
    }
    private void restart(long id){
        reset(id);
        jdbc.update("UPDATE external_sync_job SET stage='STATUS',from_index=1,working_high_id=0,last_page_min_id=NULL,scan_token=?,next_run_at=?,lease_token=NULL,lease_until=NULL,last_error=? WHERE account_id=?",UUID.randomUUID().toString(),new Timestamp(now()+30000),"牛客分页在同步期间变化，保留缓存并重新核对",id);
    }
    private void finish(long id,Map<String,Object> account,Map<String,Object> job,int total){
        boolean full=flag(job.get("full_scan"));
        Map<String,Object> scan=jdbc.queryForMap("SELECT * FROM external_nowcoder_profile WHERE account_id=?",id);
        int seen=jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=? AND seen_scan=?",Integer.class,id,job.get("scan_token"));
        if(full && seen!=total){restart(id);return;}
        // A source total mismatch can reveal an older deletion outside an incremental window.
        long lower=scan.get("scan_last_second")==null?0:number(scan.get("scan_last_second"));
        // Keep unseen submissions tied at the last second until the next full scan covers the tie.
        String covered=full?"seen_scan=?":"(seen_scan=? OR submitted_at<="+lower+")";
        if(jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=? AND "+covered,Integer.class,id,job.get("scan_token"))!=total){
            jdbc.update("UPDATE external_sync_job SET full_scan=1 WHERE account_id=?",id);restart(id);return;
        }
        long distinct=jdbc.queryForObject("SELECT COUNT(DISTINCT problem_key) FROM external_submission WHERE account_id=? AND "+covered,Long.class,id,job.get("scan_token"));
        long solved=jdbc.queryForObject("SELECT COUNT(DISTINCT problem_key) FROM external_submission WHERE account_id=? AND verdict='OK' AND "+covered,Long.class,id,job.get("scan_token"));
        if(distinct!=number(scan.get("scan_challenged_count")) || solved!=number(scan.get("scan_solved_count"))){
            jdbc.update("UPDATE external_sync_job SET full_scan=1 WHERE account_id=?",id);restart(id);return;
        }
        if(full)jdbc.update("DELETE FROM external_submission WHERE account_id=? AND seen_scan<>?",id,job.get("scan_token"));
        else if(scan.get("scan_last_second")!=null)jdbc.update("DELETE FROM external_submission WHERE account_id=? AND submitted_at>? AND seen_scan<>?",id,lower,job.get("scan_token"));
        jdbc.update("UPDATE external_nowcoder_profile SET challenged_count=scan_challenged_count,solved_count=scan_solved_count,submission_count=scan_submission_count WHERE account_id=?",id);
        jdbc.update("UPDATE external_account SET history_complete=CASE WHEN ? THEN 1 ELSE history_complete END,last_success_at=?,last_full_at=CASE WHEN ? THEN ? ELSE last_full_at END WHERE id=?",full,time(),full,time(),id);
        jdbc.update("UPDATE external_sync_job SET stage='DONE',checkpoint_id=?,from_index=1,working_high_id=0,last_page_min_id=NULL,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",Math.max(number(job.get("working_high_id")),number(job.get("checkpoint_id"))),account.get("verified_identity")==null?null:new Timestamp(now()+900000),id);
    }
    private static String digest(JsonNode items){
        try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(items.toString().getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}catch(Exception e){throw new IllegalStateException(e);}
    }
}
