package top.hcode.hoj.manager.oj.external;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.remote.RemoteProblemRef;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

/** Durable, bounded public pages. Removal requires a complete scan and an unchanged head. */
@Component
public class NowCoderCatalogManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    protected long now(){return System.currentTimeMillis();}
    private Timestamp time(){return new Timestamp(now());}
    private <T>T tx(Supplier<T> action){return new TransactionTemplate(transactions).execute(s->action.get());}
    private long number(Object value){return ((Number)value).longValue();}
    private String hash(String title,Integer rating){return RemoteCatalogManager.hash(title+"\n"+rating);}
    public Map<String,Object> claim(){return tx(()->{
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM remote_source_job WHERE source_name='NOWCODER_PROBLEMS' AND next_run_at<=? AND (lease_until IS NULL OR lease_until<=?) FOR UPDATE",time(),time());if(rows.isEmpty())return null;
        Map<String,Object> job=rows.get(0),scan=jdbc.queryForMap("SELECT * FROM remote_nowcoder_scan WHERE source_name='NOWCODER_PROBLEMS' FOR UPDATE");
        if(scan.get("scan_token")==null){String generation=UUID.randomUUID().toString();jdbc.update("UPDATE remote_nowcoder_scan SET scan_token=?,next_page=1,stage='HEAD',target_total=NULL,last_id=NULL,head_hash=NULL,head_added=0,head_changed=0 WHERE source_name='NOWCODER_PROBLEMS'",generation);scan.put("scan_token",generation);scan.put("next_page",1);scan.put("stage","HEAD");scan.put("target_total",null);scan.put("last_id",null);scan.put("head_hash",null);}
        String lease=UUID.randomUUID().toString();jdbc.update("UPDATE remote_source_job SET lease_token=?,lease_until=? WHERE source_name='NOWCODER_PROBLEMS'",lease,new Timestamp(now()+90000));job.putAll(scan);job.put("lease_token",lease);return job;
    });}
    private Timestamp leased(Map<String,Object> job){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT lease_until FROM remote_source_job WHERE source_name='NOWCODER_PROBLEMS' AND lease_token=? AND lease_until>? FOR UPDATE",job.get("lease_token"),time());
        Timestamp deadline=rows.isEmpty()?null:(Timestamp)rows.get(0).get("lease_until");
        return deadline!=null && deadline.getTime()>now()?deadline:null;
    }
    private String fingerprint(NowCoderStatementClient.Page page){StringBuilder value=new StringBuilder().append(page.total);for(NowCoderStatementClient.Entry row:page.entries)value.append('\n').append(row.problemId).append(':').append(hash(row.title,row.rating));return RemoteCatalogManager.hash(value.toString());}
    private int[] publish(List<NowCoderStatementClient.Entry> entries){
        Map<String,Map<String,Object>> old=new HashMap<>();for(Map<String,Object> row:jdbc.queryForList("SELECT problem_key,content_hash,available FROM remote_nowcoder_problem"))old.put((String)row.get("problem_key"),row);
        int added=0,changed=0;List<Object[]> rows=new ArrayList<>();for(NowCoderStatementClient.Entry e:entries){String key="nowcoder:acm:"+e.problemId,h=hash(e.title,e.rating);Map<String,Object> previous=old.get(key);if(previous==null)added++;else if(!h.equals(previous.get("content_hash")) || !ExternalAccountManager.flag(previous.get("available")))changed++;
            rows.add(new Object[]{key,Long.valueOf(e.problemId),e.title,e.rating,h,time(),time()});}
        if(!rows.isEmpty())jdbc.batchUpdate("INSERT INTO remote_nowcoder_problem(problem_key,native_problem_id,title,source_rating,content_hash,from_catalog,last_seen_at,changed_at) VALUES(?,?,?,?,?,1,?,?) ON DUPLICATE KEY UPDATE title=VALUES(title),source_rating=VALUES(source_rating),revision=CASE WHEN content_hash<>VALUES(content_hash) OR available=0 THEN revision+1 ELSE revision END,changed_at=CASE WHEN content_hash<>VALUES(content_hash) OR available=0 THEN VALUES(changed_at) ELSE changed_at END,content_hash=VALUES(content_hash),available=1,from_catalog=1,last_seen_at=VALUES(last_seen_at)",rows);
        return new int[]{added,changed};
    }
    // Official list order can jump between display IDs. Detect actual repeated IDs, not numeric order.
    private boolean overlaps(String token,NowCoderStatementClient.Page page){
        List<Object> values=new ArrayList<>();values.add(token);for(NowCoderStatementClient.Entry row:page.entries)values.add(Long.valueOf(row.problemId));
        return jdbc.queryForObject("SELECT COUNT(*) FROM remote_nowcoder_catalog_stage WHERE scan_token=? AND native_problem_id IN ("+String.join(",",Collections.nCopies(page.entries.size(),"?"))+")",Long.class,values.toArray())>0;
    }
    private void stage(String token,NowCoderStatementClient.Page page){List<Object[]> rows=new ArrayList<>();for(NowCoderStatementClient.Entry e:page.entries)rows.add(new Object[]{token,Long.valueOf(e.problemId),e.title,e.rating,hash(e.title,e.rating)});jdbc.batchUpdate("INSERT INTO remote_nowcoder_catalog_stage(scan_token,native_problem_id,title,source_rating,content_hash) VALUES(?,?,?,?,?)",rows);}
    private void release(long delay){jdbc.update("UPDATE remote_source_job SET next_run_at=?,lease_token=NULL,lease_until=NULL WHERE source_name='NOWCODER_PROBLEMS'",new Timestamp(now()+delay));}
    private void restart(Map<String,Object> job){
        jdbc.update("DELETE FROM remote_nowcoder_catalog_stage WHERE scan_token=?",job.get("scan_token"));jdbc.update("UPDATE remote_nowcoder_scan SET scan_token=NULL,stage='HEAD',next_page=1,target_total=NULL,last_id=NULL,head_hash=NULL WHERE source_name='NOWCODER_PROBLEMS'");
        int failures=Math.min(10,((Number)job.get("failures")).intValue()+1);jdbc.update("UPDATE remote_source_job SET failures=?,last_error=? WHERE source_name='NOWCODER_PROBLEMS'",failures,"牛客目录分页发生变化，保留原目录并重新核对");release(Math.min(3600000L,60000L*(1L<<Math.min(6,failures-1))));
    }
    private void finish(Map<String,Object> job,int total,int added,int changed,int removed,boolean full,String head){
        jdbc.update("DELETE FROM remote_nowcoder_catalog_stage WHERE scan_token=?",job.get("scan_token"));
        jdbc.update("UPDATE remote_nowcoder_scan SET scan_token=NULL,stage='HEAD',next_page=1,target_total=NULL,last_id=NULL,head_hash=NULL,last_head_hash=?,last_full_success_at=CASE WHEN ? THEN ? ELSE last_full_success_at END WHERE source_name='NOWCODER_PROBLEMS'",head,full,time());
        jdbc.update("UPDATE remote_source_job SET last_success_at=?,failures=0,last_error=NULL,total_count=?,added_count=?,changed_count=?,removed_count=? WHERE source_name='NOWCODER_PROBLEMS'",time(),total,added,changed,removed);release(3600000);
    }
    public void apply(Map<String,Object> job,NowCoderStatementClient.Page page){tx(()->{
        Timestamp deadline=leased(job);if(deadline==null)return null;Map<String,Object> scan=jdbc.queryForMap("SELECT * FROM remote_nowcoder_scan WHERE source_name='NOWCODER_PROBLEMS' FOR UPDATE");
        // The scan lock is acquired after the job lock and can also outlive the lease.
        if(deadline.getTime()<=now())return null;
        if(!Objects.equals(job.get("scan_token"),scan.get("scan_token")) || page.page!=number(scan.get("next_page")))throw new ObjectiveException(409,"牛客目录扫描进度已变化");String token=(String)scan.get("scan_token"),phase=(String)scan.get("stage");
        if("HEAD".equals(phase)){
            String head=fingerprint(page);int[] delta=publish(page.entries);boolean fresh=scan.get("last_full_success_at")!=null && now()-((Timestamp)scan.get("last_full_success_at")).getTime()<86400000L;
            if(fresh && head.equals(scan.get("last_head_hash"))){finish(job,page.total,delta[0],delta[1],0,false,head);return null;}
            stage(token,page);jdbc.update("UPDATE remote_nowcoder_scan SET stage=?,next_page=?,target_total=?,last_id=?,head_hash=?,head_added=?,head_changed=? WHERE source_name='NOWCODER_PROBLEMS'",page.pages==1?"VERIFY":"FULL",page.pages==1?1:2,page.total,Long.valueOf(page.entries.get(page.entries.size()-1).problemId),head,delta[0],delta[1]);jdbc.update("UPDATE remote_source_job SET failures=0,last_error=NULL WHERE source_name='NOWCODER_PROBLEMS'");release(3000);return null;
        }
        if(page.total!=number(scan.get("target_total"))){restart(job);return null;}
        if("FULL".equals(phase)){
            if(overlaps(token,page)){restart(job);return null;}
            stage(token,page);jdbc.update("UPDATE remote_nowcoder_scan SET stage=?,next_page=?,last_id=? WHERE source_name='NOWCODER_PROBLEMS'",page.page==page.pages?"VERIFY":"FULL",page.page==page.pages?1:page.page+1,Long.valueOf(page.entries.get(page.entries.size()-1).problemId));jdbc.update("UPDATE remote_source_job SET failures=0,last_error=NULL WHERE source_name='NOWCODER_PROBLEMS'");release(3000);return null;
        }
        if(!"VERIFY".equals(phase) || !fingerprint(page).equals(scan.get("head_hash")) || jdbc.queryForObject("SELECT COUNT(*) FROM remote_nowcoder_catalog_stage WHERE scan_token=?",Long.class,token)!=page.total){restart(job);return null;}
        List<NowCoderStatementClient.Entry> all=new ArrayList<>();for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM remote_nowcoder_catalog_stage WHERE scan_token=? ORDER BY native_problem_id DESC",token))all.add(new NowCoderStatementClient.Entry(String.valueOf(row.get("native_problem_id")),(String)row.get("title"),(Integer)row.get("source_rating")));
        int[] delta=publish(all);int removed=jdbc.update("UPDATE remote_nowcoder_problem p SET available=0,revision=revision+1,changed_at=? WHERE p.available=1 AND p.from_catalog=1 AND p.contest_id IS NULL AND NOT EXISTS(SELECT 1 FROM remote_nowcoder_catalog_stage s WHERE s.scan_token=? AND s.native_problem_id=p.native_problem_id)",time(),token);
        finish(job,page.total,delta[0]+((Number)scan.get("head_added")).intValue(),delta[1]+((Number)scan.get("head_changed")).intValue(),removed,true,(String)scan.get("head_hash"));return null;
    });}
    public void defer(Map<String,Object> job){tx(()->{if(leased(job)!=null)release(3000);return null;});}
    public void failed(Map<String,Object> job,String reason){tx(()->{if(leased(job)==null)return null;int failures=Math.min(10,((Number)job.get("failures")).intValue()+1);jdbc.update("UPDATE remote_source_job SET failures=?,last_error=? WHERE source_name='NOWCODER_PROBLEMS'",failures,RemoteCatalogManager.limited(reason,200));release(Math.min(21600000L,60000L*(1L<<Math.min(9,failures-1))));return null;});}
    /** Only the fixed public client can supply the source title and IDs; never accepts an arbitrary URL. */
    public void discovered(String key, NowCoderStatementClient.Statement statement) {
        tx(() -> {
            RemoteProblemRef ref = NowCoderStatementClient.ref(key);
            List<Map<String, Object>> prior = jdbc.queryForList(
                    "SELECT source_rating FROM remote_nowcoder_problem WHERE problem_key=? FOR UPDATE", key);
            Integer rating = prior.isEmpty() ? null : (Integer) prior.get(0).get("source_rating");
            String title = statement.content.getTitle();
            Timestamp seenAt = time();
            // Compare the stored hash before replacing it, as for catalogue page updates.
            jdbc.update("INSERT INTO remote_nowcoder_problem(problem_key,native_problem_id,contest_id,problem_index,"
                            + "question_id,title,content_hash,last_seen_at,changed_at) VALUES(?,?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE revision=CASE WHEN content_hash<>VALUES(content_hash) OR available=0 "
                            + "THEN revision+1 ELSE revision END,changed_at=CASE WHEN content_hash<>VALUES(content_hash) OR available=0 "
                            + "THEN VALUES(changed_at) ELSE changed_at END,native_problem_id=VALUES(native_problem_id),"
                            + "question_id=VALUES(question_id),title=VALUES(title),content_hash=VALUES(content_hash),"
                            + "available=1,last_seen_at=VALUES(last_seen_at)",
                    key, Long.valueOf(statement.nativeProblemId), ref.contest.isEmpty() ? null : Long.valueOf(ref.contest),
                    ref.contest.isEmpty() ? null : ref.task, statement.questionId, title, hash(title, rating), seenAt, seenAt);
            return null;
        });
    }
    public Map<String,Object> status(){Map<String,Object> job=jdbc.queryForMap("SELECT * FROM remote_source_job WHERE source_name='NOWCODER_PROBLEMS'"),scan=jdbc.queryForMap("SELECT * FROM remote_nowcoder_scan WHERE source_name='NOWCODER_PROBLEMS'"),result=new LinkedHashMap<>();
        result.put("lastSuccessAt",job.get("last_success_at"));result.put("lastError",job.get("last_error"));result.put("nextRunAt",job.get("next_run_at"));result.put("lastFullSuccessAt",scan.get("last_full_success_at"));result.put("total",jdbc.queryForObject("SELECT COUNT(*) FROM remote_nowcoder_problem WHERE available=1",Long.class));result.put("reportedTotal",scan.get("target_total")==null?job.get("total_count"):scan.get("target_total"));result.put("scanPage",scan.get("next_page"));result.put("state",job.get("lease_until")!=null && ((Timestamp)job.get("lease_until")).getTime()>now()?"RUNNING":number(job.get("failures"))>0?"BACKOFF":scan.get("scan_token")!=null?"RUNNING":job.get("last_success_at")==null?"QUEUED":"IDLE");for(String field:Arrays.asList("added","changed","removed"))result.put(field,job.get(field+"_count"));return result;
    }
    public Map<String,Object> requestSync(){tx(()->{Map<String,Object> job=jdbc.queryForMap("SELECT * FROM remote_source_job WHERE source_name='NOWCODER_PROBLEMS' FOR UPDATE"),scan=jdbc.queryForMap("SELECT * FROM remote_nowcoder_scan WHERE source_name='NOWCODER_PROBLEMS' FOR UPDATE");if(scan.get("scan_token")!=null && (number(job.get("failures"))==0 || job.get("lease_until")!=null && ((Timestamp)job.get("lease_until")).getTime()>now()))throw new ObjectiveException(409,"牛客目录正在同步，请等待当前分页完成");if(job.get("last_requested_at")!=null && now()-((Timestamp)job.get("last_requested_at")).getTime()<600000)throw new ObjectiveException(429,"目录十分钟内只能请求更新一次");jdbc.update("UPDATE remote_source_job SET next_run_at=?,last_requested_at=?,failures=0,last_error=NULL WHERE source_name='NOWCODER_PROBLEMS'",time(),time());return null;});return status();}
    public Map<String,Object> list(int page,String keyword,Integer minimum,Integer maximum){
        if(page<1 || page>10000 || keyword!=null && keyword.length()>100 || minimum!=null && minimum<0 || maximum!=null && maximum<0 || minimum!=null && maximum!=null && minimum>maximum)throw new ObjectiveException(400,"目录筛选条件无效");
        String where=" WHERE p.available=1";List<Object> args=new ArrayList<>();if(keyword!=null && !keyword.trim().isEmpty()){where+=" AND (p.title LIKE ? OR p.problem_key LIKE ?)";args.add("%"+keyword.trim()+"%");args.add("%"+keyword.trim().replaceFirst("^NC-?","").replace('-',':')+"%");}if(minimum!=null){where+=" AND p.source_rating>=?";args.add(minimum);}if(maximum!=null){where+=" AND p.source_rating<=?";args.add(maximum);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM remote_nowcoder_problem p"+where,Long.class,args.toArray());args.add((page-1)*30);List<Map<String,Object>> records=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT p.*,i.state AS import_state,i.last_error AS import_error,i.catalog_hash,en.content_hash AS statement_hash,tr.source_hash AS translation_source_hash FROM remote_nowcoder_problem p LEFT JOIN remote_problem_import i ON i.problem_key=p.problem_key LEFT JOIN remote_statement_current c ON c.problem_key=p.problem_key LEFT JOIN remote_statement_version en ON en.id=c.version_id LEFT JOIN remote_statement_version tr ON tr.id=c.translation_version_id"+where+" ORDER BY p.native_problem_id DESC,p.problem_key LIMIT 30 OFFSET ?",args.toArray())){
            String key=(String)row.get("problem_key");Map<String,Object> view=new LinkedHashMap<>();RemoteProblemRef ref=NowCoderStatementClient.ref(key);view.put("problemKey",key);view.put("displayId",ref.problem);view.put("title",row.get("title"));view.put("rating",null);view.put("sourceRating",row.get("source_rating"));view.put("tags",Collections.emptyList());view.put("revision",row.get("revision"));view.put("sourceUrl",NowCoderStatementClient.sourceUrl(key));
            List<Map<String,Object>> local=jdbc.queryForList("SELECT id,auth,is_remote,is_group,gid FROM problem WHERE problem_id=?",ref.problem);boolean conflict=!local.isEmpty() && (!ExternalAccountManager.flag(local.get(0).get("is_remote")) || ExternalAccountManager.flag(local.get(0).get("is_group")) || local.get(0).get("gid")!=null);view.put("localId",local.isEmpty()?null:local.get(0).get("id"));view.put("localAuth",local.isEmpty()?null:local.get(0).get("auth"));view.put("importState",conflict?"CONFLICT":row.get("import_state"));view.put("importError",row.get("import_error"));view.put("metadataChanged",row.get("catalog_hash")!=null && !Objects.equals(row.get("catalog_hash"),row.get("content_hash")));view.put("translationStale",row.get("translation_source_hash")!=null && !Objects.equals(row.get("translation_source_hash"),row.get("statement_hash")));records.add(view);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("records",records);result.put("total",total);result.put("status",status());return result;
    }
}
