package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.crawler.problem.ProblemStrategy;
import top.hcode.hoj.manager.admin.problem.RemoteProblemManager;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import top.hcode.hoj.pojo.entity.problem.*;
import top.hcode.hoj.utils.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

@Component
public class RemoteStatementManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @Autowired private RemoteProblemManager remoteProblems;
    @Autowired private NowCoderCatalogManager nowcoderCatalogue;
    private static final long REFRESH_INTERVAL=24*60*60*1000L;
    protected long now(){return System.currentTimeMillis();}
    protected String uid(){return ShiroUtils.getProfile().getUid();}
    protected boolean privileged(){return SecurityUtils.getSubject().hasRole("root") || SecurityUtils.getSubject().hasRole("problem_admin");}
    private Timestamp time(){return new Timestamp(now());}
    private <T>T tx(Supplier<T> action){return new TransactionTemplate(transactions).execute(status->action.get());}
    private Map<String,Object> actor(){List<Map<String,Object>> rows=jdbc.queryForList("SELECT uuid,username FROM user_info WHERE uuid=? AND status=0",uid());if(rows.isEmpty())throw new ObjectiveException(403,"当前账号不可用");return rows.get(0);}
    static boolean nowcoder(String key){if(key!=null && key.startsWith("nowcoder:")){NowCoderStatementClient.ref(key);return true;}return false;}
    private String sourceLanguage(String key){return nowcoder(key)?"zh-CN":"en";}
    static boolean atcoder(String key){if(nowcoder(key))return false;if(key!=null && key.startsWith("atcoder:task:")){try{if(key.equals(ProblemIdentity.atcoder(key.substring(13))))return true;}catch(IllegalArgumentException ignored){}throw new ObjectiveException(400,"题源编号无效");}CodeforcesStatementClient.sourceUrl(key);return false;}
    private String table(String key){return nowcoder(key)?"remote_nowcoder_problem":atcoder(key)?"external_atcoder_problem":"remote_catalog_problem";}
    private String display(String key){return nowcoder(key)?NowCoderStatementClient.ref(key).problem:atcoder(key)?"AC-"+key.substring(13):"CF-"+key.substring("cf:contest:".length()).replace(":","");}
    private String sourceUrl(String key){if(nowcoder(key))return NowCoderStatementClient.sourceUrl(key);if(!atcoder(key))return CodeforcesStatementClient.sourceUrl(key);Map<String,Object> row=jdbc.queryForMap("SELECT contest_id,task_id FROM external_atcoder_problem WHERE problem_key=?",key);return AtCoderStatementClient.sourceUrl((String)row.get("contest_id"),(String)row.get("task_id"));}
    private void lockCatalogue(String key){jdbc.queryForList("SELECT problem_key FROM "+table(key)+" WHERE problem_key=? FOR UPDATE",key);}
    public Map<String,Object> atcoderSource(Map<String,Object> job){Map<String,Object> row=catalogue((String)job.get("problem_key"));job.put("fetch_catalog_hash",row.get("content_hash"));return row;}
    private Map<String,Object> catalogue(String key){boolean at=atcoder(key) || nowcoder(key);List<Map<String,Object>> rows=jdbc.queryForList("SELECT *"+(at?",NULL AS rating,'[]' AS tags_json":"")+" FROM "+table(key)+" WHERE problem_key=? AND available=1",key);if(rows.isEmpty())throw new ObjectiveException(404,"题目不在当前公开目录中");return rows.get(0);}
    private Map<String,Object> local(String key){String display=display(key);List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,author,is_remote,is_group,gid FROM problem WHERE problem_id=?",display);return rows.isEmpty()?null:rows.get(0);}
    private void editable(String key,Map<String,Object> actor){Map<String,Object> p=local(key);if(p!=null && (!ExternalAccountManager.flag(p.get("is_remote")) || ExternalAccountManager.flag(p.get("is_group")) || p.get("gid")!=null))throw new ObjectiveException(409,"该题号已被其他题目使用");if(p!=null && !privileged() && !Objects.equals(actor.get("username"),p.get("author")))throw new ObjectiveException(403,"只能维护本人导入的题目");}
    public Map<String,Object> request(String key){
        Map<String,Object> actor=actor();catalogue(key);editable(key,actor);
        tx(()->{
            jdbc.queryForList("SELECT uuid FROM user_info WHERE uuid=? AND status=0 FOR UPDATE",actor.get("uuid"));
            actor();editable(key,actor);
            lockCatalogue(key);
            List<Map<String,Object>> jobs=jdbc.queryForList("SELECT * FROM remote_problem_import WHERE problem_key=? FOR UPDATE",key);
            if(!jobs.isEmpty() && now()-((Timestamp)jobs.get(0).get("last_requested_at")).getTime()<60000)throw new ObjectiveException(429,"同一道题每分钟可请求一次");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM remote_problem_import WHERE requested_by=? AND state IN ('QUEUED','RUNNING','BACKOFF')",Integer.class,actor.get("uuid"))>=5)throw new ObjectiveException(429,"已有五道题等待获取，请等待完成");
            jdbc.update("INSERT INTO remote_problem_import(problem_key,requested_by,requested_author,last_requested_at,next_run_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE requested_by=VALUES(requested_by),requested_author=VALUES(requested_author),last_requested_at=VALUES(last_requested_at),next_run_at=VALUES(next_run_at),state='QUEUED',lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL",key,actor.get("uuid"),actor.get("username"),time(),time());return null;
        });return adminView(key);
    }
    private boolean workerAuthorized(Map<String,Object> job){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT u.username,r.role FROM user_info u JOIN user_role ur ON ur.uid=u.uuid JOIN role r ON r.id=ur.role_id WHERE u.uuid=? AND u.status=0 AND r.role IN ('root','problem_admin','admin')",job.get("requested_by"));
        if(rows.isEmpty())return false;Map<String,Object> existing=local((String)job.get("problem_key"));
        if(existing==null)return true;if(!ExternalAccountManager.flag(existing.get("is_remote")) || ExternalAccountManager.flag(existing.get("is_group")) || existing.get("gid")!=null)return false;
        return rows.stream().anyMatch(row->!"admin".equals(row.get("role")) || Objects.equals(row.get("username"),existing.get("author")));
    }
    public Map<String,Object> claim(){return tx(()->{
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM remote_problem_import WHERE next_run_at<=? AND (state IN ('QUEUED','RUNNING','BACKOFF') OR (auto_refresh=1 AND state IN ('DONE','FAILED'))) AND (lease_until IS NULL OR lease_until<=?) ORDER BY CASE WHEN state IN ('DONE','FAILED') THEN 1 ELSE 0 END,next_run_at,problem_key LIMIT 1 FOR UPDATE",time(),time());if(rows.isEmpty())return null;
        Map<String,Object> job=rows.get(0);boolean available;try{catalogue((String)job.get("problem_key"));available=true;}catch(ObjectiveException e){available=false;}if(!available || !workerAuthorized(job)){jdbc.update("UPDATE remote_problem_import SET state='FAILED',auto_refresh=0,next_run_at=NULL,lease_token=NULL,lease_until=NULL,last_error=? WHERE problem_key=?","来源目录、请求账号或题目权限已变化，请管理员重新操作",job.get("problem_key"));return null;}
        if("FAILED".equals(job.get("state")))job.put("failures",0);
        String token=UUID.randomUUID().toString();jdbc.update("UPDATE remote_problem_import SET state='RUNNING',failures=?,lease_token=?,lease_until=? WHERE problem_key=?",job.get("failures"),token,new Timestamp(now()+90000),job.get("problem_key"));job.put("lease_token",token);return job;
    });}
    private boolean leased(Map<String,Object> job){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT lease_until FROM remote_problem_import WHERE problem_key=? AND lease_token=? AND lease_until>? FOR UPDATE",job.get("problem_key"),job.get("lease_token"),time());
        // Recheck after acquiring the row lock, not only before the SQL call.
        return !rows.isEmpty() && ((Timestamp)rows.get(0).get("lease_until")).getTime()>now();
    }
    public void defer(Map<String,Object> job){tx(()->{if(leased(job))jdbc.update("UPDATE remote_problem_import SET state='QUEUED',lease_token=NULL,lease_until=NULL,next_run_at=? WHERE problem_key=?",new Timestamp(now()+3000),job.get("problem_key"));return null;});}
    public void apply(Map<String,Object> job,Content content){tx(()->{String key=(String)job.get("problem_key");lockCatalogue(key);if(!leased(job))return null;if(atcoder(key) && !Objects.equals(job.get("fetch_catalog_hash"),catalogue(key).get("content_hash")))throw new ObjectiveException(409,"AtCoder 题目来源已变化，请重新获取");if(!workerAuthorized(job))throw new ObjectiveException(403,"请求账号或题目权限已变化");publishSource((String)job.get("problem_key"),normalize(content,(String)job.get("problem_key")),"SOURCE",(String)job.get("requested_by"),(String)job.get("requested_author"));return null;});}
    public void applyNowCoder(Map<String,Object> job,NowCoderStatementClient.Statement source){tx(()->{
        String key=(String)job.get("problem_key");if(!nowcoder(key))throw new ObjectiveException(400,"牛客来源编号无效");lockCatalogue(key);if(!leased(job))return null;
        if(!workerAuthorized(job))throw new ObjectiveException(403,"请求账号或题目权限已变化");nowcoderCatalogue.discovered(key,source);publishSource(key,normalize(source.content,key),"SOURCE",(String)job.get("requested_by"),(String)job.get("requested_author"));return null;
    });}
    public void failed(Map<String,Object> job,String reason){tx(()->{if(!leased(job))return null;int failures=((Number)job.get("failures")).intValue()+1;boolean terminal=failures>=3;boolean auto=ExternalAccountManager.flag(jdbc.queryForObject("SELECT auto_refresh FROM remote_problem_import WHERE problem_key=?",Object.class,job.get("problem_key")));jdbc.update("UPDATE remote_problem_import SET state=?,failures=?,last_error=?,next_run_at=?,last_checked_at=?,lease_token=NULL,lease_until=NULL WHERE problem_key=?",terminal?"FAILED":"BACKOFF",failures,RemoteCatalogManager.limited(reason,200),terminal?(auto?new Timestamp(now()+REFRESH_INTERVAL):null):new Timestamp(now()+60000L*(1L<<(failures-1))),time(),job.get("problem_key"));return null;});}
    /** Updating the policy invalidates pending callbacks as well as the old schedule. */
    public Map<String,Object> refresh(String key,Boolean enabled){
        if(enabled==null)throw new ObjectiveException(400,"请选择是否自动刷新");catalogue(key);Map<String,Object> actor=actor();editable(key,actor);
        tx(()->{lockCatalogue(key);actor();editable(key,actor);if(current(key)==null)throw new ObjectiveException(409,"请先获取或保存原文");
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT problem_key FROM remote_problem_import WHERE problem_key=? FOR UPDATE",key);if(rows.size()!=1)throw new ObjectiveException(409,"题目导入记录不存在");
            jdbc.update("UPDATE remote_problem_import SET auto_refresh=?,requested_by=?,requested_author=?,state='DONE',failures=0,last_error=NULL,next_run_at=?,lease_token=NULL,lease_until=NULL WHERE problem_key=?",enabled,actor.get("uuid"),actor.get("username"),enabled?new Timestamp(now()+REFRESH_INTERVAL):null,key);return null;
        });return adminView(key);
    }
    Content normalize(Content content,String key){
        if(content==null || content.getTitle()==null || content.getTitle().trim().isEmpty() || content.getTitle().length()>255 || content.getDescription()==null || content.getDescription().trim().isEmpty())throw new ObjectiveException(400,"请填写完整标题与题干");
        if(content.getTimeLimit()==null || content.getTimeLimit()<1 || content.getTimeLimit()>3600000 || content.getMemoryLimit()==null || content.getMemoryLimit()<1 || content.getMemoryLimit()>1048576)throw new ObjectiveException(400,"时间或内存限制无效");
        for(String value:Arrays.asList(content.getDescription(),content.getInput(),content.getOutput(),content.getHint(),content.getExamples()))if(value!=null && value.length()>500000)throw new ObjectiveException(400,"题面内容过长");
        Content result=new Content();result.setTitle(content.getTitle().trim());result.setDescription(clean(content.getDescription(),key));result.setInput(clean(content.getInput(),key));result.setOutput(clean(content.getOutput(),key));result.setHint(clean(content.getHint(),key));result.setExamples(content.getExamples()==null?"":content.getExamples());result.setTimeLimit(content.getTimeLimit());result.setMemoryLimit(content.getMemoryLimit());
        if(org.jsoup.Jsoup.parse(result.getDescription()).text().trim().isEmpty())throw new ObjectiveException(400,"题干清理后为空，请填写有效内容");
        return result;
    }
    private String clean(String value,String key){return nowcoder(key)?NowCoderStatementClient.clean(value):atcoder(key)?AtCoderStatementClient.clean(value):CodeforcesStatementClient.clean(value);}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> current(String key){List<Map<String,Object>> rows=jdbc.queryForList("SELECT v.* FROM remote_statement_current c JOIN remote_statement_version v ON v.id=c.version_id WHERE c.problem_key=?",key);return rows.isEmpty()?null:rows.get(0);}
    private long version(String key,String language,String body,String sourceHash,String origin,String actor){String hash=RemoteCatalogManager.hash(body);jdbc.update("INSERT IGNORE INTO remote_statement_version(problem_key,language,content_hash,source_hash,content_json,origin,review_status,created_by,created_at) VALUES(?,?,?,?,?,?,'APPROVED',?,?)",key,language,hash,sourceHash,body,origin,actor,time());return jdbc.queryForObject("SELECT id FROM remote_statement_version WHERE problem_key=? AND language=? AND content_hash=? AND source_hash=?",Long.class,key,language,hash,sourceHash);}
    /** Only a byte-for-byte untouched statement copy follows an automatic source update. */
    private void refreshLibraryCopy(Map<String,Object> existing,Map<String,Object> previous,Content content){
        Content original;
        try{original=json.readValue((String)previous.get("content_json"),Content.class);}catch(Exception e){throw new IllegalStateException("Invalid stored source statement",e);}
        // A locking read sees a manual edit committed while this worker waited, even if its
        // REPEATABLE READ snapshot was established by the earlier authorization queries.
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT author,is_remote,is_group,gid,title,description,input,output,hint,examples,time_limit,memory_limit FROM problem WHERE id=? FOR UPDATE",existing.get("id"));
        if(rows.size()!=1)return;Map<String,Object> copy=rows.get(0);
        if(!Objects.equals(existing.get("author"),copy.get("author")) || !ExternalAccountManager.flag(copy.get("is_remote")) || ExternalAccountManager.flag(copy.get("is_group")) || copy.get("gid")!=null)return;
        String[] fields={"title","description","input","output","hint","examples"};
        String[] values={original.getTitle(),original.getDescription(),original.getInput(),original.getOutput(),original.getHint(),original.getExamples()};
        for(int i=0;i<fields.length;i++)if(!Objects.equals(copy.get(fields[i]),values[i]))return;
        if(!(copy.get("time_limit") instanceof Number) || !(copy.get("memory_limit") instanceof Number) || ((Number)copy.get("time_limit")).intValue()!=original.getTimeLimit() || ((Number)copy.get("memory_limit")).intValue()!=original.getMemoryLimit())return;
        jdbc.update("UPDATE problem SET title=?,description=?,input=?,output=?,hint=?,examples=?,time_limit=?,memory_limit=?,gmt_modified=? WHERE id=?",content.getTitle(),content.getDescription(),content.getInput(),content.getOutput(),content.getHint(),content.getExamples(),content.getTimeLimit(),content.getMemoryLimit(),time(),existing.get("id"));
    }
    private void publishSource(String key,Content content,String origin,String actor,String author){
        Map<String,Object> previous="SOURCE".equals(origin)?current(key):null;
        Map<String,Object> catalogue=catalogue(key);boolean at=atcoder(key),nc=nowcoder(key);String oj=nc?"NC":at?"AC":"CF";String body=write(content),hash=RemoteCatalogManager.hash(body);long version=version(key,sourceLanguage(key),body,hash,origin,actor);
        jdbc.update("INSERT INTO remote_statement_current(problem_key,version_id) VALUES(?,?) ON DUPLICATE KEY UPDATE version_id=VALUES(version_id)",key,version);
        Map<String,Object> existing=local(key);long pid;
        if(existing==null){
            boolean localEditor=nc || at && jdbc.queryForObject("SELECT COUNT(*) FROM language WHERE oj='AC'",Integer.class)==0;
            if(jdbc.queryForObject("SELECT COUNT(*) FROM language WHERE oj=?",Integer.class,localEditor?"ME":oj)==0)throw new ObjectiveException(400,"尚未配置 "+(localEditor?"本站编辑器":oj+" 远程")+"语言，请先在系统中配置");
            Problem p=new Problem().setProblemId(display(key)).setTitle(content.getTitle()).setAuthor(author).setType(0).setJudgeMode("default").setJudgeCaseMode("default").setAuth(2).setIsRemote(true).setIsGroup(false).setOpenCaseResult(true).setIsRemoveEndBlank(false).setDifficulty(1).setRating((Integer)catalogue.get("rating")).setTimeLimit(content.getTimeLimit()).setMemoryLimit(content.getMemoryLimit()).setDescription(content.getDescription()).setInput(content.getInput()).setOutput(content.getOutput()).setHint(content.getHint()).setExamples(content.getExamples()).setSource("<a href=\""+sourceUrl(key)+"\" target=\"_blank\" rel=\"noopener noreferrer\">"+(nc?"牛客":at?"AtCoder":"Codeforces")+"</a>");
            List<Tag> tags=new ArrayList<>();try{for(com.fasterxml.jackson.databind.JsonNode tag:json.readTree((String)catalogue.get("tags_json")))tags.add(new Tag().setName(tag.asText()));}catch(Exception e){throw new IllegalStateException(e);}
            ProblemStrategy.RemoteProblemInfo info=new ProblemStrategy.RemoteProblemInfo().setProblem(p).setTagList(tags).setRemoteOJ(nc?null:at?Constants.RemoteOJ.ATCODER:Constants.RemoteOJ.CODEFORCES);
            Problem saved=localEditor?remoteProblems.adminAddOtherOJProblem(info,oj,true):remoteProblems.adminAddOtherOJProblem(info,oj);if(saved==null)throw new ObjectiveException(500,"题目入库失败");pid=saved.getId();
        }else{
            pid=ExternalAccountManager.number(existing.get("id"));
            if(previous!=null && !Objects.equals(previous.get("content_hash"),hash))refreshLibraryCopy(existing,previous,content);
        }
        boolean fromSource="SOURCE".equals(origin);
        jdbc.update("INSERT INTO remote_problem_import(problem_key,requested_by,requested_author,state,last_requested_at,problem_id,imported_hash,catalog_hash,auto_refresh,last_checked_at,last_success_at,next_run_at) VALUES(?,?,?,'DONE',?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE state='DONE',auto_refresh=CASE WHEN ? THEN COALESCE(auto_refresh,1) ELSE 0 END,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL,problem_id=VALUES(problem_id),imported_hash=VALUES(imported_hash),catalog_hash=VALUES(catalog_hash),last_checked_at=CASE WHEN ? THEN VALUES(last_checked_at) ELSE last_checked_at END,last_success_at=CASE WHEN ? THEN VALUES(last_success_at) ELSE last_success_at END",
                key,actor,author,time(),pid,hash,catalogue.get("content_hash"),fromSource,fromSource?time():null,fromSource?time():null,null,fromSource,fromSource,fromSource);
        jdbc.update("UPDATE remote_problem_import SET next_run_at=CASE WHEN auto_refresh=1 THEN ? ELSE NULL END WHERE problem_key=?",new Timestamp(now()+REFRESH_INTERVAL),key);
    }
    public Map<String,Object> save(RemoteStatementDTO dto,boolean translation){
        if(dto==null)throw new ObjectiveException(400,"题面不能为空");String key=dto.getProblemKey();catalogue(key);Map<String,Object> actor=actor();editable(key,actor);
        tx(()->{lockCatalogue(key);actor();editable(key,actor);
            if(!translation){
                Map<String,Object> source=current(key);
                if(!Objects.equals(dto.getSourceHash(),source==null?null:source.get("content_hash")))throw new ObjectiveException(409,"原文已更新，请重新打开后编辑");
                publishSource(key,normalize(dto.getContent(),key),"MANUAL",(String)actor.get("uuid"),(String)actor.get("username"));
            }
            else{
                Map<String,Object> source=current(key);if(source==null)throw new ObjectiveException(400,"请先保存原文");if(!Objects.equals(dto.getSourceHash(),source.get("content_hash")))throw new ObjectiveException(409,"原文已更新，请重新核对译文后保存");
                Content original;try{original=json.readValue((String)source.get("content_json"),Content.class);}catch(Exception e){throw new IllegalStateException(e);}
                if(dto.getContent()==null)throw new ObjectiveException(400,"译文不能为空");dto.getContent().setTimeLimit(original.getTimeLimit());dto.getContent().setMemoryLimit(original.getMemoryLimit());
                long translationId=version(key,nowcoder(key)?"en":"zh-CN",write(normalize(dto.getContent(),key)),(String)source.get("content_hash"),"MANUAL",(String)actor.get("uuid"));
                jdbc.update("UPDATE remote_statement_current SET translation_version_id=? WHERE problem_key=?",translationId,key);
            }return null;});return adminView(key);
    }
    private Map<String,Object> view(String key){
        Map<String,Object> source=current(key),result=new LinkedHashMap<>();result.put("problemKey",key);result.put("sourceUrl",sourceUrl(key));result.put("platform",nowcoder(key)?"NOWCODER":atcoder(key)?"ATCODER":"CODEFORCES");result.put("sourceLanguage",source==null?sourceLanguage(key):source.get("language"));result.put("translationLanguage",nowcoder(key)?"en":"zh-CN");result.put("source",source==null?null:versionView(source));
        List<Map<String,Object>> chinese=jdbc.queryForList("SELECT v.* FROM remote_statement_current c JOIN remote_statement_version v ON v.id=c.translation_version_id WHERE c.problem_key=? AND v.review_status='APPROVED'",key);
        Map<String,Object> candidate=chinese.isEmpty()?null:chinese.get(0);boolean stale=candidate!=null && (source==null || !Objects.equals(candidate.get("source_hash"),source.get("content_hash")));
        result.put("translation",candidate==null?null:versionView(candidate));result.put("translationStale",stale);return result;
    }
    private Map<String,Object> versionView(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();for(String field:Arrays.asList("id","origin","language"))result.put(field,row.get(field));result.put("contentHash",row.get("content_hash"));result.put("createdAt",row.get("created_at"));try{result.put("content",json.readTree((String)row.get("content_json")));}catch(Exception e){throw new IllegalStateException(e);}return result;}
    public Map<String,Object> adminView(String key){catalogue(key);Map<String,Object> actor=actor();editable(key,actor);Map<String,Object> result=view(key);List<Map<String,Object>> imports=jdbc.queryForList("SELECT state,last_error,next_run_at,problem_id,auto_refresh,last_checked_at,last_success_at FROM remote_problem_import WHERE problem_key=?",key);result.put("import",imports);
        Map<String,Object> refresh=new LinkedHashMap<>();Map<String,Object> row=imports.isEmpty()?Collections.emptyMap():imports.get(0);refresh.put("enabled",ExternalAccountManager.flag(row.get("auto_refresh")));refresh.put("intervalHours",24);refresh.put("lastCheckedAt",row.get("last_checked_at"));refresh.put("lastSuccessAt",row.get("last_success_at"));refresh.put("nextCheckAt",row.get("next_run_at"));refresh.put("lastError",row.get("last_error"));result.put("refresh",refresh);return result;}
    public Map<String,Object> publicView(long pid){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM problem WHERE id=? AND auth=1 AND is_remote=1 AND gid IS NULL AND (is_group=0 OR is_group IS NULL)",Integer.class,pid)!=1)throw new ObjectiveException(404,"暂无公开的多语言题面");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT problem_key FROM remote_problem_import WHERE problem_id=?",pid);
        if(rows.isEmpty())return Collections.singletonMap("available",false);
        Map<String,Object> result=view((String)rows.get(0).get("problem_key"));result.put("available",result.get("source")!=null);if(Boolean.TRUE.equals(result.get("translationStale")))result.put("translation",null);return result;
    }
    /** Rechecks actual roles, rather than relying on a cached Shiro role during a model request. */
    public Map<String,Object> translationSource(String key){
        Map<String,Object> actor=actor();
        List<String> roles=jdbc.queryForList("SELECT r.role FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.uid=? AND r.role IN ('root','admin','problem_admin')",String.class,actor.get("uuid"));
        if(roles.isEmpty())throw new ObjectiveException(403,"当前账号没有题目维护权限");
        Map<String,Object> problem=local(key);
        if(problem!=null && !roles.contains("root") && !roles.contains("problem_admin") && !Objects.equals(problem.get("author"),actor.get("username")))throw new ObjectiveException(403,"只能翻译本人维护的题目");
        Map<String,Object> view=adminView(key);if(view.get("source")==null)throw new ObjectiveException(400,"请先获取或保存原文，再生成译文");return view;
    }
}
