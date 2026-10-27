package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.*;
import top.hcode.hoj.utils.ShiroUtils;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** Public account evidence is kept separate from HOJ results and remote-submission credentials. */
@Component
public class ExternalAccountManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @Autowired private LearningProfileManager profiles;
    @Autowired private AlgorithmCatalog catalog;
    @Autowired private AtCoderEvidenceManager atcoder;
    @Autowired private NowCoderEvidenceManager nowcoder;
    @Autowired private NowCoderContextManager nowcoderContexts;
    @Autowired private NowCoderPracticeManager nowcoderPractice;
    protected long now() { return System.currentTimeMillis(); }
    protected String uid() {
        if (ShiroUtils.getProfile()==null) throw new ObjectiveException(401,"请先登录");
        return ShiroUtils.getProfile().getUid();
    }
    private Timestamp time() { return new Timestamp(now()); }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    private void active(String uid) {
        if(uid==null || uid.length()>32 || jdbc.queryForObject("SELECT COUNT(*) FROM user_info WHERE uuid=? AND status=0",Integer.class,uid)!=1)
            throw new ObjectiveException(404,"用户不存在或不可访问");
    }
    private Map<String,Object> owned(long id, boolean lock) {
        String actor=uid(); active(actor);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM external_account WHERE id=? AND uid=?"+(lock?" FOR UPDATE":""),id,actor);
        if(rows.isEmpty()) throw new ObjectiveException(404,"账号关联不存在");
        return rows.get(0);
    }
    static long number(Object value) { return value==null?0:((Number)value).longValue(); }
    static boolean flag(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number && ((Number)value).intValue()!=0; }
    private static String shortText(String value,int length) { return value.substring(0,value.offsetByCodePoints(0,Math.min(length,value.codePointCount(0,value.length())))); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    public Map<String,Object> mine() { String actor=uid(); return snapshot(actor,true); }
    public Map<String,Object> publicView(String user) { return snapshot(user,false); }
    public Map<String,Object> link(String platform,String handle) {
        String actor=uid(); active(actor);
        if(!Arrays.asList("CODEFORCES","ATCODER","NOWCODER").contains(platform)) throw new ObjectiveException(400,"请选择支持的外部平台");
        String name=handle==null?"":handle.trim();
        if("NOWCODER".equals(platform)) NowCoderDataClient.handle(name);
        else if("ATCODER".equals(platform)) AtCoderDataClient.handle(name);
        else if(!name.matches("[A-Za-z0-9_.-]{1,64}")) throw new ObjectiveException(400,"CF 用户名格式无效");
        try {
            tx(() -> {
                jdbc.queryForList("SELECT uuid FROM user_info WHERE uuid=? FOR UPDATE",actor);
                if(jdbc.queryForObject("SELECT COUNT(*) FROM external_account WHERE uid=?",Integer.class,actor)>=5) throw new ObjectiveException(400,"每人最多关联 5 个外部账号");
                KeyHolder key=new GeneratedKeyHolder(); Timestamp current=time();
                jdbc.update(connection -> {
                    PreparedStatement statement=connection.prepareStatement("INSERT INTO external_account(uid,platform,handle,handle_key,last_requested_at,created_at) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1,actor);statement.setString(2,platform);statement.setString(3,name);statement.setString(4,name.toLowerCase(Locale.ROOT));statement.setTimestamp(5,current);statement.setTimestamp(6,current);return statement;
                },key);
                jdbc.update("INSERT INTO external_sync_job(account_id,scan_token,next_run_at) VALUES(?,?,?)",key.getKey().longValue(),UUID.randomUUID().toString(),current);
                if("ATCODER".equals(platform)) jdbc.update("INSERT INTO external_atcoder_cursor(account_id) VALUES(?)",key.getKey().longValue());
                if("NOWCODER".equals(platform)) nowcoder.reset(key.getKey().longValue());
                return null;
            });
        } catch(DuplicateKeyException e) { throw new ObjectiveException(409,"该用户名已经关联，请勿重复添加"); }
        return mine();
    }
    public Map<String,Object> unlink(long id) {
        tx(() -> { owned(id,true);jdbc.update("DELETE FROM external_account WHERE id=?",id);return null; });return mine();
    }
    public Map<String,Object> proof(long id) {
        tx(() -> {
            Map<String,Object> account=owned(id,true);
            if(account.get("verified_identity")!=null) throw new ObjectiveException(400,"该账号已经验证");
            String code="HOJ-"+UUID.randomUUID().toString().replace("-","");
            jdbc.update("UPDATE external_account SET proof_code=?,proof_expires_at=?,verification_note=NULL WHERE id=?",code,new Timestamp(now()+30*60*1000L),id);return null;
        });return mine();
    }
    public Map<String,Object> requestSync(long id,boolean verification) {
        boolean context=tx(() -> {
            Map<String,Object> account=owned(id,true);
            if(verification && (account.get("proof_code")==null || ((Timestamp)account.get("proof_expires_at")).getTime()<=now())) throw new ObjectiveException(400,"校验码已失效，请重新生成");
            if(now()-((Timestamp)account.get("last_requested_at")).getTime()<60000) throw new ObjectiveException(429,"每个账号每分钟可请求同步一次，请稍后重试");
            jdbc.update("UPDATE external_account SET last_requested_at=? WHERE id=?",time(),id);
            boolean full=!flag(account.get("history_complete")) || account.get("last_full_at")==null || now()-((Timestamp)account.get("last_full_at")).getTime()>=86400000L;
            resetJob(id,full);return "NOWCODER".equals(account.get("platform"));
        });
        if(context){nowcoderContexts.request(id);nowcoderPractice.request(id);}
        return mine();
    }
    private void resetJob(long id,boolean full) {
        String platform=jdbc.queryForObject("SELECT platform FROM external_account WHERE id=?",String.class,id);
        if("ATCODER".equals(platform)) atcoder.reset(id,full);
        if("NOWCODER".equals(platform)) nowcoder.reset(id);
        jdbc.update("UPDATE external_sync_job SET stage='PROFILE',from_index=1,full_scan=?,working_high_id=0,last_page_min_id=NULL,scan_token=?,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",full,UUID.randomUUID().toString(),time(),id);
    }
    /** A short transaction leases work. No network request holds a database lock. */
    public Map<String,Object> claim() {
        return tx(() -> {
            List<Map<String,Object>> jobs=jdbc.queryForList("SELECT j.*,a.platform,a.handle,a.handle_key,a.history_complete,a.last_full_at FROM external_sync_job j JOIN external_account a ON a.id=j.account_id JOIN user_info u ON u.uuid=a.uid WHERE u.status=0 AND j.next_run_at<=? AND (j.lease_until IS NULL OR j.lease_until<=?) ORDER BY j.next_run_at,j.account_id LIMIT 1 FOR UPDATE",time(),time());
            if(jobs.isEmpty()) return null;
            Map<String,Object> job=jobs.get(0);long id=number(job.get("account_id"));
            if("DONE".equals(job.get("stage"))) {
                boolean full=job.get("last_full_at")==null || now()-((Timestamp)job.get("last_full_at")).getTime()>=86400000L;
                resetJob(id,full);job.putAll(jdbc.queryForMap("SELECT * FROM external_sync_job WHERE account_id=?",id));
            }
            if("ATCODER".equals(job.get("platform"))) job.putAll(jdbc.queryForMap("SELECT from_second,scan_start_second FROM external_atcoder_cursor WHERE account_id=?",id));
            String token=UUID.randomUUID().toString();
            jdbc.update("UPDATE external_sync_job SET lease_token=?,lease_until=? WHERE account_id=?",token,new Timestamp(now()+90000),id);
            job.put("lease_token",token);return job;
        });
    }
    private Map<String,Object> leased(Map<String,Object> job) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.* FROM external_account a JOIN external_sync_job j ON j.account_id=a.id WHERE a.id=? AND j.lease_token=? FOR UPDATE",job.get("account_id"),job.get("lease_token"));
        return rows.isEmpty()?null:rows.get(0);
    }
    public void apply(Map<String,Object> job,JsonNode result) {
        tx(() -> {
            Map<String,Object> account=leased(job);if(account==null) return null;
            long id=number(account.get("id"));String stage=(String)job.get("stage");
            if("PROFILE".equals(stage)) { if(result==null || !result.isArray()) throw new IllegalArgumentException("Invalid public result");applyProfile(id,account,result);next(id,"RATING"); }
            else if("ATCODER".equals(account.get("platform"))) atcoder.apply(id,account,job,result);
            else if("NOWCODER".equals(account.get("platform")) && !"RATING".equals(stage)) nowcoder.apply(id,account,job,result);
            else if(result==null || !result.isArray()) throw new IllegalArgumentException("Invalid public result");
            else if("RATING".equals(stage)) { applyRating(id,result);
                if("NOWCODER".equals(account.get("platform"))) jdbc.update("UPDATE external_account SET max_rating=(SELECT MAX(new_rating) FROM external_rating_event WHERE account_id=?) WHERE id=?",id,id);
                next(id,"STATUS"); }
            else if("STATUS".equals(stage)) applySubmissions(id,account,job,result);
            else throw new IllegalArgumentException("Invalid stage");
            return null;
        });
    }
    private void next(long id,String stage) {
        jdbc.update("UPDATE external_sync_job SET stage=?,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",stage,time(),id);
    }
    private void applyProfile(long id,Map<String,Object> account,JsonNode result) {
        if(result.size()!=1) throw new IllegalArgumentException("Missing profile");
        JsonNode user=result.get(0);String handle=user.path("handle").asText();
        boolean isAtCoder="ATCODER".equals(account.get("platform")),isNowCoder="NOWCODER".equals(account.get("platform")),isCf=!isAtCoder&&!isNowCoder;
        if(!handle.equalsIgnoreCase((String)account.get("handle")) || isCf && (!user.path("registrationTimeSeconds").isIntegralNumber() || !user.path("registrationTimeSeconds").canConvertToLong() || user.path("registrationTimeSeconds").asLong()<=0)) throw new IllegalArgumentException("Unexpected identity");
        Long registration=isCf?user.path("registrationTimeSeconds").asLong():null;
        if(isNowCoder) nowcoder.profile(id,user);
        if(isCf && account.get("registration_time")!=null && number(account.get("registration_time"))!=registration) {
            jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=NULL,credential_version=credential_version+1,updated_at=? WHERE account_id=?",time(),id);
            jdbc.update("DELETE FROM external_submission WHERE account_id=?",id);jdbc.update("DELETE FROM external_rating_event WHERE account_id=?",id);
            jdbc.update("UPDATE external_account SET verified_identity=NULL,verified_at=NULL,proof_code=NULL,proof_expires_at=NULL,history_complete=0,last_success_at=NULL,last_full_at=NULL,verification_note=? WHERE id=?","平台账号身份发生变化，请重新验证",id);
            jdbc.update("UPDATE external_sync_job SET full_scan=1,checkpoint_id=0,from_index=1,working_high_id=0,last_page_min_id=NULL WHERE account_id=?",id);
            account.put("proof_code",null);
        }
        jdbc.update("UPDATE external_account SET handle=?,registration_time=?,rating=?,max_rating=?,profile_synced_at=? WHERE id=?",handle,registration,user.path("rating").isIntegralNumber()?user.path("rating").intValue():null,user.path("maxRating").isIntegralNumber()?user.path("maxRating").intValue():null,time(),id);
        if(account.get("proof_code")!=null && ((Timestamp)account.get("proof_expires_at")).getTime()>now() && user.path("organization").asText("").contains((String)account.get("proof_code"))) {
            // A deleted HOJ user must not reserve the same external identity forever.
            jdbc.update("UPDATE external_account SET verified_identity=NULL,verified_at=NULL WHERE platform=? AND verified_identity=? AND NOT EXISTS (SELECT 1 FROM user_info u WHERE u.uuid=external_account.uid)",account.get("platform"),account.get("handle_key"));
            try { jdbc.update("UPDATE external_account SET verified_identity=handle_key,verified_at=?,proof_code=NULL,proof_expires_at=NULL,verification_note=NULL WHERE id=?",time(),id); }
            catch(DuplicateKeyException e) { jdbc.update("UPDATE external_account SET verification_note=? WHERE id=?","该平台账号已由其他站内用户验证",id); }
        } else if(account.get("proof_code")!=null && ((Timestamp)account.get("proof_expires_at")).getTime()>now()) {
            jdbc.update("UPDATE external_account SET verification_note=? WHERE id=?",isNowCoder?"尚未在牛客公开简介中找到校验码；保存资料后请重新检查":isAtCoder?"尚未在 AtCoder 公开 Affiliation 字段找到校验码；保存资料后请重新检查":"尚未在 CF 公开 Organization 字段找到校验码；保存资料后请重新检查",id);
        }
    }
    private void applyRating(long id,JsonNode result) {
        if(result.size()>10000) throw new IllegalArgumentException("Rating history too large");
        List<Object[]> rows=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(JsonNode event:result) {
            for(String field:Arrays.asList("contestId","rank","oldRating","newRating","ratingUpdateTimeSeconds")) if(!event.path(field).isIntegralNumber()) throw new IllegalArgumentException("Invalid rating history");
            long cid=event.path("contestId").asLong();if(!seen.add(cid)) throw new IllegalArgumentException("Repeated rating contest");
            rows.add(new Object[]{id,cid,shortText(event.path("contestName").asText(""),255),event.path("rank").intValue(),event.path("oldRating").intValue(),event.path("newRating").intValue(),event.path("ratingUpdateTimeSeconds").longValue()});
        }
        jdbc.update("DELETE FROM external_rating_event WHERE account_id=?",id);
        if(!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO external_rating_event(account_id,contest_id,title,contest_rank,old_rating,new_rating,occurred_at) VALUES(?,?,?,?,?,?,?)",rows);
        jdbc.update("UPDATE external_account SET rated_contests=? WHERE id=?",rows.size(),id);
    }
    private void applySubmissions(long id,Map<String,Object> account,Map<String,Object> job,JsonNode result) {
        if(result.size()>500) throw new IllegalArgumentException("Oversized submission page");
        long high=number(job.get("working_high_id")), low=Long.MAX_VALUE,previous=Long.MAX_VALUE;
        Map<String,Object[]> problems=new LinkedHashMap<>();List<Object[]> submissions=new ArrayList<>();
        for(JsonNode submission:result) {
            if(!submission.path("id").isIntegralNumber() || !submission.path("creationTimeSeconds").isIntegralNumber()) throw new IllegalArgumentException("Invalid submission");
            long sid=submission.path("id").asLong();if(sid<=0 || sid>=previous) throw new IllegalArgumentException("Unordered submission page");previous=sid;
            high=Math.max(high,sid);low=Math.min(low,sid);
            if(submission.path("author").path("ghost").asBoolean(false)) continue;
            JsonNode problem=submission.path("problem");String key=ProblemIdentity.codeforces(problem);
            List<String> tags=new ArrayList<>();if(!problem.path("tags").isArray() || problem.path("tags").size()>64) throw new IllegalArgumentException("Invalid tags");
            for(JsonNode tag:problem.path("tags")) { if(!tag.isTextual() || tag.asText().length()>100) throw new IllegalArgumentException("Invalid tag");tags.add(tag.asText()); }
            problems.put(key,new Object[]{key,"CODEFORCES",shortText(problem.path("name").asText(""),255),problem.path("rating").isIntegralNumber()?problem.path("rating").intValue():null,write(tags),time()});
            JsonNode members=submission.path("author").path("members");
            boolean individual=members.isArray() && members.size()==1 && members.get(0).path("handle").asText().equalsIgnoreCase((String)account.get("handle")) && !submission.path("author").has("teamId");
            String verdict=submission.path("verdict").asText("PENDING");if(!verdict.matches("[A-Z_]{1,32}")) throw new IllegalArgumentException("Invalid verdict");
            submissions.add(new Object[]{id,sid,key,verdict,submission.path("creationTimeSeconds").asLong(),individual,job.get("scan_token")});
        }
        if(!result.isEmpty() && job.get("last_page_min_id")!=null && low>=number(job.get("last_page_min_id"))) throw new IllegalArgumentException("Pagination made no progress");
        if(!problems.isEmpty()) jdbc.batchUpdate("INSERT INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE title=VALUES(title),rating=COALESCE(VALUES(rating),rating),tags_json=VALUES(tags_json),synced_at=VALUES(synced_at)",new ArrayList<>(problems.values()));
        if(!submissions.isEmpty()) jdbc.batchUpdate("INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE problem_key=VALUES(problem_key),verdict=VALUES(verdict),submitted_at=VALUES(submitted_at),individual=VALUES(individual),seen_scan=VALUES(seen_scan)",submissions);
        boolean full=flag(job.get("full_scan"));int from=((Number)job.get("from_index")).intValue();
        if(result.size()<500 || !full && from>500 && low<=number(job.get("checkpoint_id"))) {
            if(full) jdbc.update("DELETE FROM external_submission WHERE account_id=? AND seen_scan<>?",id,job.get("scan_token"));
            jdbc.update("UPDATE external_account SET history_complete=CASE WHEN ? THEN 1 ELSE history_complete END,last_success_at=?,last_full_at=CASE WHEN ? THEN ? ELSE last_full_at END WHERE id=?",full,time(),full,time(),id);
            jdbc.update("UPDATE external_sync_job SET stage='DONE',checkpoint_id=?,working_high_id=0,from_index=1,last_page_min_id=NULL,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",Math.max(high,number(job.get("checkpoint_id"))),account.get("verified_identity")==null?null:new Timestamp(now()+900000),id);
        } else {
            if(from>Integer.MAX_VALUE-500) throw new IllegalArgumentException("Submission history too large");
            jdbc.update("UPDATE external_sync_job SET from_index=?,working_high_id=?,last_page_min_id=? WHERE account_id=?",from+500,high,low,id);next(id,"STATUS");
        }
    }
    public void defer(Map<String,Object> job) {
        jdbc.update("UPDATE external_sync_job SET next_run_at=?,lease_token=NULL,lease_until=NULL WHERE account_id=? AND lease_token=?",new Timestamp(now()+5000),job.get("account_id"),job.get("lease_token"));
    }
    public void failed(Map<String,Object> job,String reason) {
        tx(() -> {
            if(leased(job)==null) return null;
            int failures=Math.min(10,((Number)job.get("failures")).intValue()+1);long delay=Math.min(21600000L,60000L*(1L<<Math.min(9,failures-1)));
            jdbc.update("UPDATE external_sync_job SET failures=?,last_error=?,next_run_at=?,lease_token=NULL,lease_until=NULL WHERE account_id=?",failures,shortText(reason,200),new Timestamp(now()+delay),job.get("account_id"));return null;
        });
    }
    private Map<String,Object> snapshot(String user,boolean owner) {
        active(user);List<Map<String,Object>> accounts=jdbc.queryForList("SELECT a.*,j.stage,j.next_run_at,j.lease_until,j.failures,j.last_error FROM external_account a JOIN external_sync_job j ON j.account_id=a.id WHERE a.uid=?"+(owner?"":" AND a.verified_identity IS NOT NULL")+" ORDER BY a.id",user);
        List<Map<String,Object>> views=new ArrayList<>();Set<String> local=new HashSet<>(),external=new HashSet<>();long localCount=0,sourceSolved=0;boolean complete=true;
        for(Map<String,Object> evidence:profiles.publicProgrammingEvidence(user)) if(number(evidence.get("solved"))==1) {
            localCount++;String key=ProblemIdentity.local(number(evidence.get("id")),(String)evidence.get("problem_id"),flag(evidence.get("is_remote")));
            local.add(key);
        }
        for(Map<String,Object> account:accounts) {
            boolean isAtCoder="ATCODER".equals(account.get("platform")),isNowCoder="NOWCODER".equals(account.get("platform")),accountScope=isAtCoder||isNowCoder;
            long id=number(account.get("id"));List<Map<String,Object>> evidence=jdbc.queryForList("SELECT s.problem_key,p.rating,p.tags_json,MAX(CASE WHEN s.verdict='OK' THEN 1 ELSE 0 END) AS solved,MAX(CASE WHEN s.verdict='OK' AND s.individual=1 THEN 1 ELSE 0 END) AS solo FROM external_submission s JOIN external_problem p ON p.problem_key=s.problem_key WHERE s.account_id=? GROUP BY s.problem_key,p.rating,p.tags_json",id);
            long solved=0,soloSolved=0;List<Integer> ratings=new ArrayList<>();boolean verified=account.get("verified_identity")!=null;
            Map<String,Integer> coverage=new LinkedHashMap<>();catalog.groups().keySet().forEach(code -> coverage.put(code,0));
            for(Map<String,Object> item:evidence) if(number(item.get("solved"))==1) {
                solved++;if(verified) external.add((String)item.get("problem_key"));
                if(accountScope || number(item.get("solo"))==1) {
                    soloSolved++;if(item.get("rating")!=null) ratings.add(((Number)item.get("rating")).intValue());
                    try {
                        Set<String> groups=new HashSet<>();
                        for(JsonNode tag:json.readTree((String)item.get("tags_json"))) { AlgorithmCatalog.Topic topic=catalog.topicForTag(tag.asText());if(topic!=null) groups.add(topic.getGroupCode()); }
                        groups.forEach(code -> coverage.put(code,coverage.get(code)+1));
                    } catch(Exception e) { throw new IllegalStateException("Invalid cached source tags",e); }
                }
            }
            Collections.sort(ratings);Map<String,Object> view=new LinkedHashMap<>();
            for(String field:Arrays.asList("id","platform","handle","rating","max_rating","rated_contests","verified_at","profile_synced_at","history_complete","last_success_at","next_run_at")) view.put(camel(field),account.get(field));
            view.put("verified",verified);view.put("profileUrl",(isNowCoder?"https://ac.nowcoder.com/acm/contest/profile/":isAtCoder?"https://atcoder.jp/users/":"https://codeforces.com/profile/")+account.get("handle"));
            view.put("identityContinuity",isNowCoder?"NUMERIC_USER_ID":isAtCoder?"HANDLE_ONLY":"REGISTRATION_TIME");
            view.put("representativeScope",accountScope?"ACCOUNT_ACCEPTED":"INDIVIDUAL_ACCEPTED");view.put("individualStatusVerified",!accountScope);
            view.put("dataSource",isNowCoder?"NOWCODER_OFFICIAL_PUBLIC_CODING":isAtCoder?"ATCODER_OFFICIAL_AND_ATCODER_PROBLEMS":"CODEFORCES_OFFICIAL");view.put("difficultySource",isNowCoder?"UNAVAILABLE":isAtCoder?"ATCODER_PROBLEMS_ESTIMATE":"CODEFORCES_OFFICIAL");
            view.put("historyComplete",flag(account.get("history_complete")));
            view.put("syncState",account.get("lease_until")!=null && ((Timestamp)account.get("lease_until")).getTime()>now()?"RUNNING":number(account.get("failures"))>0?"BACKOFF":"DONE".equals(account.get("stage"))?"IDLE":"QUEUED");
            boolean available=flag(account.get("history_complete")) || !evidence.isEmpty();
            view.put("attempted",available?evidence.size():null);view.put("solved",available?solved:null);view.put("ratedSolved",ratings.size());view.put("representativeRating",ratings.isEmpty()?null:ratings.get((int)Math.ceil(ratings.size()*.75)-1));
            view.put("individualSolved",available && !accountScope?soloSolved:null);view.put("coverageAvailable",!accountScope);
            List<Map<String,Object>> axes=new ArrayList<>();if(!accountScope) catalog.groups().forEach((code,name) -> {Map<String,Object> axis=new LinkedHashMap<>();axis.put("code",code);axis.put("name",name);axis.put("solved",coverage.get(code));axes.add(axis);});view.put("coverage",axes);
            List<Map<String,Object>> history=new ArrayList<>();
            for(Map<String,Object> event:jdbc.queryForList("SELECT contest_id,title,contest_rank,old_rating,new_rating,occurred_at FROM "+(isAtCoder?"external_atcoder_rating":"external_rating_event")+" WHERE account_id=? ORDER BY occurred_at DESC,contest_id DESC LIMIT 20",id)) {Map<String,Object> entry=new LinkedHashMap<>();event.forEach((key,value) -> entry.put(camel(key),value));entry.put("contestUrl",(isNowCoder?"https://ac.nowcoder.com/acm/contest/":isAtCoder?"https://atcoder.jp/contests/":"https://codeforces.com/contest/")+event.get("contest_id"));history.add(entry);}view.put("ratingHistory",history);
            if(isNowCoder) {
                Map<String,Object> metadata=jdbc.queryForMap("SELECT display_name,challenged_count,solved_count,submission_count FROM external_nowcoder_profile WHERE account_id=?",id);
                view.put("displayName",metadata.get("display_name"));
                view.put("statisticsScope","PUBLIC_PROFILE_CODING");view.put("maximumRatingScope","AVAILABLE_RATED_HISTORY");
                view.put("contestContext",nowcoderContexts.view(id,owner));
                view.put("practiceContext",nowcoderPractice.view(id,owner));
                Map<String,Object> sourceCounts=new LinkedHashMap<>();metadata.forEach((key,value)->sourceCounts.put(camel(key),value));view.put("metadata",sourceCounts);
            }
            if(isAtCoder) {
                Map<String,Object> metadata=new LinkedHashMap<>();
                for(Map<String,Object> job:jdbc.queryForList("SELECT source_name,last_success_at,failures FROM remote_source_job WHERE source_name IN ('ATCODER_PROBLEMS','ATCODER_MODELS')")) {
                    String prefix="ATCODER_MODELS".equals(job.get("source_name"))?"models":"problems";
                    metadata.put(prefix+"SyncedAt",job.get("last_success_at"));metadata.put(prefix+"Stale",job.get("last_success_at")==null || number(job.get("failures"))>0 || now()-((Timestamp)job.get("last_success_at")).getTime()>(prefix.equals("models")?43200000L:7200000L));
                }view.put("metadata",metadata);
            }
            view.put("stale",account.get("last_success_at")==null || now()-((Timestamp)account.get("last_success_at")).getTime()>3600000 || number(account.get("failures"))>0);
            if(owner) {view.put("lastError",account.get("last_error"));view.put("verificationNote",account.get("verification_note"));if(account.get("proof_expires_at")!=null && ((Timestamp)account.get("proof_expires_at")).getTime()>now()) {view.put("proofCode",account.get("proof_code"));view.put("proofExpiresAt",account.get("proof_expires_at"));}}
            views.add(view);if(verified) {sourceSolved+=solved;complete&=flag(account.get("history_complete")) && number(account.get("failures"))==0;}
        }
        Set<String> keys=new HashSet<>(local);keys.addAll(external);
        Map<String,String> identities=new ProblemIdentityResolver(jdbc).resolve(keys);
        Set<String> unique=new HashSet<>(),externalUnique=new HashSet<>();
        local.forEach(key->unique.add(identities.get(key)));external.forEach(key->externalUnique.add(identities.get(key)));unique.addAll(externalUnique);
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("localSolved",localCount);summary.put("externalSolved",externalUnique.size());summary.put("uniqueSolved",unique.size());summary.put("duplicatesRemoved",localCount+sourceSolved-unique.size());summary.put("complete",complete);summary.put("scope","HOJ_PUBLIC_PRACTICE_AND_VERIFIED_EXTERNAL");summary.put("identityRule","EXPLICIT_SOURCE_IDS");summary.put("catalogueAliases",true);summary.put("assistanceTracked",false);
        Map<String,Object> result=new LinkedHashMap<>();result.put("uid",user);result.put("accounts",views);result.put("summary",summary);result.put("asOf",now());return result;
    }
    private static String camel(String name) { StringBuilder result=new StringBuilder();boolean upper=false;for(char c:name.toCharArray()) {if(c=='_') upper=true;else {result.append(upper?Character.toUpperCase(c):c);upper=false;}}return result.toString(); }
}
