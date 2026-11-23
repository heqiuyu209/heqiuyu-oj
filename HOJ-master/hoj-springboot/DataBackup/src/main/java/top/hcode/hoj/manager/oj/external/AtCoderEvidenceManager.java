package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;

/** Called inside the account manager's leased transaction. Timestamp pagination overlaps its boundary. */
@Component
public class AtCoderEvidenceManager {
    @Autowired private JdbcTemplate jdbc;
    protected long now() { return System.currentTimeMillis(); }
    private Timestamp time() { return new Timestamp(now()); }
    public void reset(long id,boolean full) {
        Long newest=jdbc.queryForObject("SELECT MAX(submitted_at) FROM external_submission WHERE account_id=?",Long.class,id);
        long from=full||newest==null?0:Math.max(0,newest-172800);
        jdbc.update("UPDATE external_atcoder_cursor SET from_second=?,scan_start_second=? WHERE account_id=?",from,from,id);
    }
    public void apply(long id,Map<String,Object> account,Map<String,Object> job,JsonNode result) {
        String stage=(String)job.get("stage");
        if("RATING".equals(stage)) {rating(id,result);next(id,"STATUS");}
        else if("STATUS".equals(stage)) submissions(id,account,job,result);
        else if("BOUNDARY".equals(stage)) boundary(id,job,result);
        else throw new IllegalArgumentException("Invalid AtCoder stage");
    }
    private void next(long id,String stage) {
        jdbc.update("UPDATE external_sync_job SET stage=?,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",stage,time(),id);
    }
    private void rating(long id,JsonNode result) {
        if(result==null || !result.isArray() || result.size()>10000) throw new IllegalArgumentException("Invalid AtCoder rating history");
        Set<String> seen=new HashSet<>();List<Object[]> rows=new ArrayList<>();
        for(JsonNode event:result) {
            if(!event.path("IsRated").isBoolean()) throw new IllegalArgumentException("Invalid rated status");
            if(!event.path("IsRated").asBoolean()) continue;
            String contest=event.path("ContestScreenName").asText().replaceFirst("\\.contest\\.atcoder\\.jp$","");
            if(!contest.matches("[A-Za-z0-9_-]{1,80}") || !seen.add(contest.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Invalid rated contest");
            for(String field:Arrays.asList("Place","OldRating","NewRating")) if(!event.path(field).isIntegralNumber() || !event.path(field).canConvertToInt() || event.path(field).asInt()<0) throw new IllegalArgumentException("Invalid rating history");
            long occurred=OffsetDateTime.parse(event.path("EndTime").asText()).toEpochSecond();if(occurred<=0) throw new IllegalArgumentException("Invalid contest time");
            rows.add(new Object[]{id,contest,RemoteCatalogManager.limited(event.path("ContestName").asText(""),255),event.path("Place").asInt(),event.path("OldRating").asInt(),event.path("NewRating").asInt(),occurred});
        }
        jdbc.update("DELETE FROM external_atcoder_rating WHERE account_id=?",id);
        if(!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO external_atcoder_rating(account_id,contest_id,title,contest_rank,old_rating,new_rating,occurred_at) VALUES(?,?,?,?,?,?,?)",rows);
        jdbc.update("UPDATE external_account SET rated_contests=? WHERE id=?",rows.size(),id);
    }
    private void submissions(long id,Map<String,Object> account,Map<String,Object> job,JsonNode result) {
        if(result==null || !result.isArray() || result.size()>500) throw new IllegalArgumentException("Invalid AtCoder submission page");
        long from=ExternalAccountManager.number(job.get("from_second")),previous=from,latest=from;
        List<Object[]> problems=new ArrayList<>(),rows=new ArrayList<>();Set<Long> ids=new HashSet<>();
        for(JsonNode item:result) {
            if(!item.path("id").isIntegralNumber() || !item.path("id").canConvertToLong() || item.path("id").asLong()<=0 || !ids.add(item.path("id").asLong()) || !item.path("epoch_second").isIntegralNumber() || !item.path("epoch_second").canConvertToLong()) throw new IllegalArgumentException("Invalid submission identity");
            long second=item.path("epoch_second").asLong();
            if(second<previous || second>now()/1000+300 || !item.path("user_id").asText().equalsIgnoreCase((String)account.get("handle"))) throw new IllegalArgumentException("Unexpected submission owner or ordering");
            previous=second;latest=second;String task=item.path("problem_id").asText(),key=ProblemIdentity.atcoder(task),verdict=item.path("result").asText();
            if(!verdict.matches("[A-Z0-9 /]{1,32}")) throw new IllegalArgumentException("Invalid source verdict");
            // Source API does not establish solo/team participation or whether assistance was used.
            problems.add(new Object[]{key,"ATCODER",task,null,"[]",time()});
            rows.add(new Object[]{id,item.path("id").asLong(),key,"AC".equals(verdict)?"OK":verdict,second,false,job.get("scan_token")});
        }
        if(!problems.isEmpty()) jdbc.batchUpdate("INSERT IGNORE INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(?,?,?,?,?,?)",problems);
        if(!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE problem_key=VALUES(problem_key),verdict=VALUES(verdict),submitted_at=VALUES(submitted_at),individual=VALUES(individual),seen_scan=VALUES(seen_scan)",rows);
        if(result.size()==500) {
            jdbc.update("UPDATE external_atcoder_cursor SET from_second=? WHERE account_id=?",latest,id);
            next(id,latest==from?"BOUNDARY":"STATUS");return;
        }
        // Only a completed scan can reconcile deleted/rejudged evidence in its covered interval.
        jdbc.update("DELETE FROM external_submission WHERE account_id=? AND submitted_at>=? AND seen_scan<>?",id,job.get("scan_start_second"),job.get("scan_token"));
        boolean full=ExternalAccountManager.flag(job.get("full_scan"));
        jdbc.update("UPDATE external_account SET history_complete=CASE WHEN ? THEN 1 ELSE history_complete END,last_success_at=?,last_full_at=CASE WHEN ? THEN ? ELSE last_full_at END WHERE id=?",full,time(),full,time(),id);
        jdbc.update("UPDATE external_sync_job SET stage='DONE',next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",account.get("verified_identity")==null?null:new Timestamp(now()+900000),id);
    }
    private void boundary(long id,Map<String,Object> job,JsonNode result) {
        if(result==null || !result.path("count").isIntegralNumber() || !result.path("count").canConvertToLong() || result.path("count").asLong()<0) throw new IllegalArgumentException("Invalid boundary count");
        long from=ExternalAccountManager.number(job.get("from_second"));
        long cached=jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=? AND submitted_at=? AND seen_scan=?",Long.class,id,from,job.get("scan_token"));
        if(result.path("count").asLong()!=cached) throw new ObjectiveException(502,"同一秒的提交超出来源分页能力，本次记录仍不完整，已保留缓存");
        if(from==Long.MAX_VALUE) throw new IllegalArgumentException("Invalid cursor");
        jdbc.update("UPDATE external_atcoder_cursor SET from_second=? WHERE account_id=?",from+1,id);next(id,"STATUS");
    }
}
