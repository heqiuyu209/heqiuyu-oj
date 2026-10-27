package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

/** Independent public-context scans. Never changes the canonical submission ledger. */
@Component
public class NowCoderContextManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    protected long now(){return System.currentTimeMillis();}
    private Timestamp time(){return new Timestamp(now());}
    private <T>T tx(Supplier<T> work){return new TransactionTemplate(transactions).execute(status->work.get());}
    private long number(Object value){return ((Number)value).longValue();}
    private boolean flag(Object value){return ExternalAccountManager.flag(value);}
    private void release(long id,String stage,int page,long delay){
        jdbc.update("UPDATE external_nowcoder_context_job SET stage=?,next_page=?,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",stage,page,new Timestamp(now()+delay),id);
    }
    private void restart(Map<String,Object> job,long delay){
        long id=number(job.get("account_id"));String published=(String)job.get("published_token");
        jdbc.update("DELETE FROM external_nowcoder_contest WHERE account_id=? AND (? IS NULL OR scan_token<>?)",id,published,published);
        jdbc.update("UPDATE external_nowcoder_context_job SET stage='LIST',scan_token=?,next_page=1,list_pages=NULL,list_total=NULL,list_head_hash=NULL,contest_id=NULL,page_total=NULL,page_count=NULL,page_head_hash=NULL,last_submit_ms=NULL,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",UUID.randomUUID().toString(),new Timestamp(now()+delay),id);
    }
    public Map<String,Object> claim(){return tx(()->{
        // Eligibility follows the first complete public coding scan; no browser
        // request creates a network job or exposes an unverified account.
        List<Long> due=jdbc.queryForList("SELECT a.id FROM external_account a JOIN user_info u ON u.uuid=a.uid LEFT JOIN external_nowcoder_context_job j ON j.account_id=a.id WHERE u.status=0 AND a.platform='NOWCODER' AND ((j.account_id IS NULL AND a.history_complete=1 AND a.last_success_at IS NOT NULL) OR (j.next_run_at<=? AND (j.lease_until IS NULL OR j.lease_until<=?))) ORDER BY COALESCE(j.next_run_at,a.last_success_at),a.id LIMIT 1",Long.class,time(),time());
        if(due.isEmpty())return null;long id=due.get(0);
        jdbc.queryForList("SELECT id FROM external_account WHERE id=? FOR UPDATE",id);
        List<Map<String,Object>> accounts=jdbc.queryForList("SELECT a.handle,a.history_complete,a.last_success_at FROM external_account a JOIN user_info u ON u.uuid=a.uid WHERE a.id=? AND a.platform='NOWCODER' AND u.status=0 FOR UPDATE",id);
        if(accounts.isEmpty())return null;Map<String,Object> account=accounts.get(0);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM external_nowcoder_context_job WHERE account_id=? FOR UPDATE",id);
        if(rows.isEmpty()){
            if(!flag(account.get("history_complete")) || account.get("last_success_at")==null)return null;
            jdbc.update("INSERT INTO external_nowcoder_context_job(account_id,scan_token,next_run_at) VALUES(?,?,?)",id,UUID.randomUUID().toString(),time());
            rows=jdbc.queryForList("SELECT * FROM external_nowcoder_context_job WHERE account_id=? FOR UPDATE",id);
        }
        Map<String,Object> job=rows.get(0);
        if(((Timestamp)job.get("next_run_at")).getTime()>now() || job.get("lease_until")!=null && ((Timestamp)job.get("lease_until")).getTime()>now())return null;
        job.put("handle",account.get("handle"));
        if("DONE".equals(job.get("stage"))){restart(job,0);job.putAll(jdbc.queryForMap("SELECT * FROM external_nowcoder_context_job WHERE account_id=?",id));}
        String token=UUID.randomUUID().toString();Timestamp until=new Timestamp(now()+90000);
        jdbc.update("UPDATE external_nowcoder_context_job SET lease_token=?,lease_until=? WHERE account_id=?",token,until,id);job.put("lease_token",token);job.put("lease_until",until);return job;
    });}
    private Map<String,Object> leased(Map<String,Object> old){
        long id=number(old.get("account_id"));jdbc.queryForList("SELECT id FROM external_account WHERE id=? FOR UPDATE",id);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT j.*,a.handle FROM external_nowcoder_context_job j JOIN external_account a ON a.id=j.account_id JOIN user_info u ON u.uuid=a.uid WHERE j.account_id=? AND j.lease_token=? AND j.lease_until>? AND a.platform='NOWCODER' AND u.status=0 FOR UPDATE",id,old.get("lease_token"),time());
        return rows.isEmpty()?null:rows.get(0);
    }
    public void defer(Map<String,Object> old){tx(()->{Map<String,Object> job=leased(old);if(job!=null)jdbc.update("UPDATE external_nowcoder_context_job SET next_run_at=?,lease_token=NULL,lease_until=NULL WHERE account_id=?",new Timestamp(now()+3000),job.get("account_id"));return null;});}
    public void failed(Map<String,Object> old,String ignored){tx(()->{Map<String,Object> job=leased(old);if(job!=null){int failures=((Number)job.get("failures")).intValue()+1;long delay=Math.min(3600000L,30000L*(1L<<Math.min(failures,7)));if(failures>=3 && !((String)job.get("stage")).endsWith("LIST"))restart(job,delay);jdbc.update("UPDATE external_nowcoder_context_job SET failures=?,last_error=?,next_run_at=?,lease_token=NULL,lease_until=NULL WHERE account_id=?",failures,"牛客比赛上下文未完成，保留上次完整记录",new Timestamp(now()+delay),job.get("account_id"));}return null;});}
    /** Called after the owning account's authenticated request transaction. */
    public void request(long id){jdbc.update("UPDATE external_nowcoder_context_job SET next_run_at=? WHERE account_id=?",time(),id);}
    private int integer(JsonNode data,String key,int min,int max){JsonNode value=data.path(key);if(!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt()<min || value.asInt()>max)throw new IllegalArgumentException("Invalid context page");return value.asInt();}
    private String hash(JsonNode data){return RemoteCatalogManager.hash(data.toString());}
    private Map<String,Object> contest(Map<String,Object> job){return jdbc.queryForMap("SELECT * FROM external_nowcoder_contest WHERE account_id=? AND scan_token=? AND contest_id=?",job.get("account_id"),job.get("scan_token"),job.get("contest_id"));}
    public void apply(Map<String,Object> old,JsonNode data){tx(()->{
        Map<String,Object> job=leased(old);if(job==null)return null;
        if(data==null || !data.isObject() || !data.path("items").isArray())throw new IllegalArgumentException("Invalid context result");
        String stage=(String)job.get("stage");long id=number(job.get("account_id"));
        int page=integer(data,"page",1,NowCoderContestClient.MAX_PAGES),total=integer(data,"total",0,500000),pages=integer(data,"pages",0,NowCoderContestClient.MAX_PAGES);
        boolean verify=stage.startsWith("VERIFY_");int expected=verify?1:((Number)job.get("next_page")).intValue();
        if(page!=expected || stage.endsWith("LIST") && !job.get("handle").equals(data.path("uid").asText())
                || !stage.endsWith("LIST") && (job.get("contest_id")==null || data.path("contestId").asLong()!=number(job.get("contest_id"))))throw new IllegalArgumentException("Context identity changed");
        if(stage.endsWith("LIST")){
            if(pages!=(total+9)/10 || data.path("items").size()!=Math.min(10,Math.max(0,total-(page-1)*10)))throw new IllegalArgumentException("Invalid contest list size");
            if("VERIFY_LIST".equals(stage)){
                if(job.get("list_total")==null || total!=number(job.get("list_total")) || pages!=number(job.get("list_pages")) || !hash(data.path("items")).equals(job.get("list_head_hash"))){restart(job,30000);return null;}
                if(jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_contest WHERE account_id=? AND scan_token=?",Integer.class,id,job.get("scan_token"))!=total){restart(job,30000);return null;}
                advance(job);return null;
            }
            if(page==1)jdbc.update("UPDATE external_nowcoder_context_job SET list_total=?,list_pages=?,list_head_hash=? WHERE account_id=?",total,pages,hash(data.path("items")),id);
            else if(total!=number(job.get("list_total")) || pages!=number(job.get("list_pages"))){restart(job,30000);return null;}
            for(JsonNode row:data.path("items")){
                long cid=row.path("contestId").asLong();boolean team=row.path("team").asBoolean(),restricted=row.path("restricted").asBoolean();
                if(jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_contest WHERE account_id=? AND scan_token=? AND contest_id=?",Integer.class,id,job.get("scan_token"),cid)>0){restart(job,30000);return null;}
                jdbc.update("INSERT INTO external_nowcoder_contest(account_id,scan_token,contest_id,title,start_ms,end_ms,team,participation,restricted,official_accepted,contest_rank,detail_state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,job.get("scan_token"),cid,row.path("title").asText(),row.path("startMs").asLong(),row.path("endMs").asLong(),team,row.path("participation").asText(),restricted,row.path("officialAccepted").isNumber()?row.path("officialAccepted").asInt():null,row.path("rank").isNumber()?row.path("rank").asInt():null,restricted?"RESTRICTED":team?"TEAM":"WAITING");
                if(job.get("published_token")!=null && (restricted || team || row.path("rank").isNull() && row.path("officialAccepted").isNull())){
                    // A known source restriction revokes cached detail now;
                    // it must not wait for the rest of this scan to finish.
                    int changed=jdbc.update("UPDATE external_nowcoder_contest SET detail_state=CASE WHEN ? THEN 'RESTRICTED' WHEN ? THEN 'TEAM' ELSE detail_state END,team=?,restricted=?,official_accepted=?,contest_rank=? WHERE account_id=? AND scan_token=? AND contest_id=?",restricted,team,team,restricted,row.path("officialAccepted").isNumber()?row.path("officialAccepted").asInt():null,row.path("rank").isNumber()?row.path("rank").asInt():null,id,job.get("published_token"),cid);
                    if(changed>0)jdbc.update("UPDATE external_nowcoder_context_job SET revision=revision+1 WHERE account_id=?",id);
                }
            }
            release(id,page>=pages?"VERIFY_LIST":"LIST",page>=pages?1:page+1,0);return null;
        }
        Map<String,Object> source=contest(job);if(!"WAITING".equals(source.get("detail_state")) || flag(source.get("restricted")) || flag(source.get("team")))throw new IllegalArgumentException("Restricted context cannot request details");
        int size=stage.endsWith("PROBLEMS")?100:20;
        if(pages!=(total+size-1)/size || data.path("items").size()!=Math.min(size,Math.max(0,total-(page-1)*size)))throw new IllegalArgumentException("Invalid context pagination");
        if(verify){
            if(job.get("page_total")==null || total!=number(job.get("page_total")) || pages!=number(job.get("page_count")) || !hash(data.path("items")).equals(job.get("page_head_hash"))){restart(job,30000);return null;}
            String table="VERIFY_PROBLEMS".equals(stage)?"external_nowcoder_contest_problem":"external_nowcoder_contest_submission";
            if(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE account_id=? AND scan_token=? AND contest_id=?",Integer.class,id,job.get("scan_token"),job.get("contest_id"))!=total){restart(job,30000);return null;}
            if("VERIFY_PROBLEMS".equals(stage)){resetPage(id,"STATUS");return null;}
            jdbc.update("UPDATE external_nowcoder_contest SET detail_state='DONE' WHERE account_id=? AND scan_token=? AND contest_id=?",id,job.get("scan_token"),job.get("contest_id"));advance(job);return null;
        }
        if(page==1)jdbc.update("UPDATE external_nowcoder_context_job SET page_total=?,page_count=?,page_head_hash=? WHERE account_id=?",total,pages,hash(data.path("items")),id);
        else if(total!=number(job.get("page_total")) || pages!=number(job.get("page_count"))){restart(job,30000);return null;}
        long previous=job.get("last_submit_ms")==null?Long.MAX_VALUE:number(job.get("last_submit_ms"));
        for(JsonNode row:data.path("items")){
            String key=row.path("problemKey").asText(),index=row.path("index").asText();if(!key.matches("nowcoder:acm:[1-9][0-9]{0,11}") || !index.matches("[A-Z][A-Z0-9]{0,15}"))throw new IllegalArgumentException("Invalid source mapping");
            if("PROBLEMS".equals(stage))jdbc.update("INSERT INTO external_nowcoder_contest_problem(account_id,scan_token,contest_id,problem_index,problem_key,title) VALUES(?,?,?,?,?,?)",id,job.get("scan_token"),job.get("contest_id"),index,key,row.path("title").asText());
            else if("STATUS".equals(stage)){
                if(!job.get("handle").equals(data.path("uid").asText()) || !row.path("id").isIntegralNumber() || row.path("id").asLong()<=0 || !row.path("submittedMs").isIntegralNumber())throw new IllegalArgumentException("Invalid contest evidence");
                long stamp=row.path("submittedMs").asLong();
                if(stamp>previous || "OFFICIAL".equals(source.get("participation")) && (stamp<number(source.get("start_ms")) || stamp>=number(source.get("end_ms")))){restart(job,30000);return null;}previous=stamp;
                if(jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_contest_problem WHERE account_id=? AND scan_token=? AND contest_id=? AND problem_key=? AND problem_index=?",Integer.class,id,job.get("scan_token"),job.get("contest_id"),key,index)!=1)throw new IllegalArgumentException("Unverified contest problem");
                if(jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_contest_submission WHERE account_id=? AND scan_token=? AND contest_id=? AND submission_id=?",Integer.class,id,job.get("scan_token"),job.get("contest_id"),row.path("id").asLong())>0){restart(job,30000);return null;}
                jdbc.update("INSERT INTO external_nowcoder_contest_submission(account_id,scan_token,contest_id,submission_id,problem_key,problem_index,verdict,submitted_ms) VALUES(?,?,?,?,?,?,?,?)",id,job.get("scan_token"),job.get("contest_id"),row.path("id").asLong(),key,index,row.path("verdict").asText(),stamp);
            }else throw new IllegalArgumentException("Invalid context stage");
        }
        if("STATUS".equals(stage))jdbc.update("UPDATE external_nowcoder_context_job SET last_submit_ms=? WHERE account_id=?",data.path("items").size()==0?null:previous,id);
        release(id,page>=pages?("PROBLEMS".equals(stage)?"VERIFY_PROBLEMS":"VERIFY_STATUS"):stage,page>=pages?1:page+1,0);return null;
    });}
    private void resetPage(long id,String stage){jdbc.update("UPDATE external_nowcoder_context_job SET page_total=NULL,page_count=NULL,page_head_hash=NULL,last_submit_ms=NULL WHERE account_id=?",id);release(id,stage,1,0);}
    private void advance(Map<String,Object> job){
        long id=number(job.get("account_id"));List<Long> pending=jdbc.queryForList("SELECT contest_id FROM external_nowcoder_contest WHERE account_id=? AND scan_token=? AND detail_state='WAITING' ORDER BY contest_id LIMIT 1",Long.class,id,job.get("scan_token"));
        if(!pending.isEmpty()){jdbc.update("UPDATE external_nowcoder_context_job SET contest_id=? WHERE account_id=?",pending.get(0),id);resetPage(id,"PROBLEMS");return;}
        jdbc.update("UPDATE external_nowcoder_context_job SET published_token=scan_token,revision=revision+1,last_success_at=?,contest_id=NULL WHERE account_id=?",time(),id);
        jdbc.update("DELETE FROM external_nowcoder_contest WHERE account_id=? AND scan_token<>?",id,job.get("scan_token"));release(id,"DONE",1,86400000L);
    }
    /** The caller has already selected owned or verified-public accounts. */
    public Map<String,Object> view(long id,boolean owner){
        for(int attempt=0;attempt<3;attempt++){Map<String,Object> value=readView(id,owner);if(value!=null)return value;}
        throw new ObjectiveException(409,"比赛上下文刚刚更新，请刷新后重试");
    }
    private Map<String,Object> readView(long id,boolean owner){
        Map<String,Object> result=new LinkedHashMap<>();List<Map<String,Object>> jobs=jdbc.queryForList("SELECT * FROM external_nowcoder_context_job WHERE account_id=?",id);
        result.put("available",false);result.put("listComplete",false);result.put("submissionCoverageComplete",false);result.put("syncState","QUEUED");result.put("contests",Collections.emptyList());
        if(jobs.isEmpty())return result;Map<String,Object> job=jobs.get(0);String token=(String)job.get("published_token");
        result.put("syncState",job.get("lease_until")!=null && ((Timestamp)job.get("lease_until")).getTime()>now()?"RUNNING":number(job.get("failures"))>0?"BACKOFF":"DONE".equals(job.get("stage"))?"IDLE":"QUEUED");
        result.put("lastSuccessAt",job.get("last_success_at"));if(owner)result.put("lastError",job.get("last_error"));
        result.put("stale",job.get("last_success_at")==null || now()-((Timestamp)job.get("last_success_at")).getTime()>86400000L || number(job.get("failures"))>0 || !"DONE".equals(job.get("stage")));
        if(token==null)return result;
        Map<String,Object> coding=jdbc.queryForMap("SELECT stage,scan_token FROM external_sync_job WHERE account_id=?",id);
        int total=jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_contest WHERE account_id=? AND scan_token=?",Integer.class,id,token);
        int covered=jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_contest WHERE account_id=? AND scan_token=? AND detail_state='DONE'",Integer.class,id,token);
        result.put("available",true);result.put("listComplete",true);result.put("submissionCoverageComplete",covered==total);result.put("totalContests",total);result.put("coveredContests",covered);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM external_nowcoder_contest WHERE account_id=? AND scan_token=? ORDER BY start_ms DESC,contest_id DESC LIMIT 20",id,token),views=new ArrayList<>();
        for(Map<String,Object> row:rows){
            long cid=number(row.get("contest_id"));Map<String,Object> view=new LinkedHashMap<>();
            view.put("contestId",cid);view.put("title",row.get("title"));view.put("startMs",row.get("start_ms"));view.put("endMs",row.get("end_ms"));view.put("team",flag(row.get("team")));view.put("participation",row.get("participation"));view.put("officialAccepted",row.get("official_accepted"));view.put("rank",row.get("contest_rank"));view.put("detailState",row.get("detail_state"));view.put("url","https://ac.nowcoder.com/acm/contest/"+cid);
            if("DONE".equals(row.get("detail_state"))){
                Map<String,Object> counts=jdbc.queryForMap("SELECT COUNT(*) AS submissions,COUNT(DISTINCT CASE WHEN verdict='OK' THEN problem_key ELSE NULL END) AS solved FROM external_nowcoder_contest_submission WHERE account_id=? AND scan_token=? AND contest_id=?",id,token,cid);
                view.put("contestSubmissions",counts.get("submissions"));view.put("contestSolved",counts.get("solved"));
                // A same-global-problem pass after the contest may overlap
                // other contests. Label it as related later evidence, not
                // a formal score or an independent-work claim.
                long lower=(number(row.get("end_ms"))+999)/1000;
                long later=jdbc.queryForObject("SELECT COUNT(DISTINCT s.problem_key) FROM external_submission s JOIN external_nowcoder_contest_problem p ON p.account_id=s.account_id AND p.problem_key=s.problem_key WHERE s.account_id=? AND p.scan_token=? AND p.contest_id=? AND s.verdict='OK' AND s.submitted_at>=? AND NOT EXISTS (SELECT 1 FROM external_nowcoder_contest_submission c WHERE c.account_id=s.account_id AND c.scan_token=p.scan_token AND c.contest_id=p.contest_id AND c.submission_id=s.external_id)",Long.class,id,token,cid,lower);
                view.put("postContestSolved","DONE".equals(coding.get("stage"))?later:null);
            }else{view.put("contestSubmissions",null);view.put("contestSolved",null);view.put("postContestSolved",null);}
            views.add(view);
        }
        List<Map<String,Object>> current=jdbc.queryForList("SELECT published_token,revision FROM external_nowcoder_context_job WHERE account_id=?",id);
        if(current.isEmpty() || !Objects.equals(token,current.get(0).get("published_token")) || !Objects.equals(job.get("revision"),current.get(0).get("revision")))return null;
        Map<String,Object> codingNow=jdbc.queryForMap("SELECT stage,scan_token FROM external_sync_job WHERE account_id=?",id);
        if(!coding.equals(codingNow))for(Map<String,Object> value:views)value.put("postContestSolved",null);
        result.put("contests",views);return result;
    }
}
