package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.annotation.HOJAccessEnum;
import top.hcode.hoj.common.exception.*;
import top.hcode.hoj.config.NacosSwitchConfig;
import top.hcode.hoj.exception.AccessException;
import top.hcode.hoj.manager.oj.BeforeDispatchInitManager;
import top.hcode.hoj.manager.oj.JudgeManager;
import top.hcode.hoj.pojo.dto.PersonalRemoteSubmissionDTO;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.remote.*;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.utils.ShiroUtils;
import top.hcode.hoj.validator.AccessValidator;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Browser entry for personal remote jobs. Network language reads never run inside the submit transaction. */
@Component
public class PersonalRemoteSubmissionManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private BeforeDispatchInitManager before;
    @Autowired private AccessValidator access;
    @Autowired private PersonalRemoteFormClient forms;
    @Autowired private StringRedisTemplate redis;
    @Autowired private NacosSwitchConfig switchConfig;
    @Autowired private JudgeManager judges;
    @Value("${hoj.personal.remote.enabled:false}") private boolean enabled;
    private final ObjectMapper json=new ObjectMapper();

    protected AccountProfile profile() { return ShiroUtils.getProfile(); }
    private AccountProfile actor() {
        AccountProfile actor=profile();
        if(actor==null)throw new ObjectiveException(401,"请先登录");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_info WHERE uuid=? AND status=0",Integer.class,actor.getUid())!=1)
            throw new ObjectiveException(403,"当前账号不可提交");
        return actor;
    }
    protected PersonalRemoteTaskStore store() { return new PersonalRemoteTaskStore(jdbc,transactions); }
    private void requireEnabled() { if(!enabled)throw new ObjectiveException(503,"个人远程提交尚未启用"); }
    private static long value(Long number) { return number==null?0:number; }
    private static long number(Object number) { return ((Number)number).longValue(); }
    private static String field(Object value) { String text=value==null?"":String.valueOf(value);return text.length()+":"+text; }
    private String scopeKey(PersonalRemoteSubmissionDTO input) {
        return field(input.pid)+field(value(input.cid))+field(value(input.tid))+field(value(input.gid));
    }
    private String requestHash(PersonalRemoteSubmissionDTO input) {
        return PersonalRemoteTaskStore.hash(scopeKey(input)+field(input.accountId)+field(input.languageId)+field(input.code));
    }
    private void validateScope(PersonalRemoteSubmissionDTO input) {
        if(input==null || input.pid==null || input.pid.trim().isEmpty() || input.pid.length()>120
                || value(input.cid)<0 || value(input.tid)<0 || value(input.gid)<0 || value(input.cid)>0 && value(input.tid)>0)
            throw new ObjectiveException(400,"题目或提交范围无效");
    }
    private static final class Context {
        final Judge judge; final RemoteProblemRef ref;
        Context(Judge judge,RemoteProblemRef ref){this.judge=judge;this.ref=ref;}
    }
    private Context context(PersonalRemoteSubmissionDTO input,AccountProfile actor) {
        validateScope(input);
        Judge judge=new Judge().setCid(value(input.cid)).setGid(value(input.gid)==0?null:input.gid).setUid(actor.getUid());
        checked(()->{
            access.validateAccess(value(input.cid)>0?HOJAccessEnum.CONTEST_JUDGE:value(input.gid)>0?HOJAccessEnum.GROUP_JUDGE:HOJAccessEnum.PUBLIC_JUDGE);
            if(value(input.cid)>0)before.prepareContestSubmission(input.cid,input.pid,actor,judge);
            else if(value(input.tid)>0)before.prepareTrainingSubmission(input.tid,input.pid,actor,judge);
            else before.prepareCommonSubmission(input.pid,judge.getGid(),judge);
            if(judge.getGid()!=null)access.validateAccess(HOJAccessEnum.GROUP_JUDGE);
        });
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT problem_id,is_remote,auth,is_group,gid FROM problem WHERE id=?"+(TransactionSynchronizationManager.isActualTransactionActive()?" FOR UPDATE":""),judge.getPid());
        if(rows.size()!=1)throw new ObjectiveException(404,"题目不存在");
        Map<String,Object> problem=rows.get(0);
        if(!(Boolean.TRUE.equals(problem.get("is_remote")) || problem.get("is_remote") instanceof Number && number(problem.get("is_remote"))==1))
            throw new ObjectiveException(400,"这道题不使用远程提交");
        if(number(problem.get("auth"))==2 || value(input.cid)==0 && value(input.tid)==0 && number(problem.get("auth"))!=1)
            throw new ObjectiveException(403,"请从题目所属的比赛或训练提交");
        if(!Objects.equals(judge.getDisplayPid(),problem.get("problem_id")))throw new ObjectiveException(409,"题目来源已变化，请重新打开题目");
        if(value(input.cid)==0 && value(input.tid)==0 && value(input.gid)>0 && !Objects.equals(problem.get("gid"),input.gid))throw new ObjectiveException(403,"题目不属于该团队");
        if(value(input.gid)>0 && !Objects.equals(judge.getGid(),input.gid))throw new ObjectiveException(403,"提交的团队范围不符");
        RemoteProblemRef ref;
        try{ref=store().problemReference((String)problem.get("problem_id"));}
        catch(IllegalArgumentException | PersonalRemoteTaskStore.Conflict e){throw new ObjectiveException(400,"该来源尚不支持本人提交，或源站目录尚未就绪");}
        return new Context(judge,ref);
    }
    private String contextKey(PersonalRemoteSubmissionDTO input,Context context) {
        return PersonalRemoteTaskStore.hash(scopeKey(input)+field(context.judge.getPid())+field(context.judge.getGid())+field(context.ref.problem)+field(context.ref.contest));
    }
    private Map<String,Object> account(long id,String uid,String platform) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.*,c.credential_version,c.credential_ciphertext FROM external_account a LEFT JOIN remote_user_credential c ON c.account_id=a.id WHERE a.id=? AND a.uid=? AND a.platform=?",id,uid,platform);
        if(rows.size()!=1)throw new ObjectiveException(404,"本人平台账号不存在或来源不匹配");
        return rows.get(0);
    }
    private boolean ready(Map<String,Object> account) {
        return Objects.equals(account.get("handle_key"),account.get("verified_identity"))
                && account.get("credential_ciphertext") instanceof String && ((String)account.get("credential_ciphertext")).startsWith("s1:");
    }
    public Map<String,Object> accounts(PersonalRemoteSubmissionDTO input) {
        AccountProfile actor=actor();requireEnabled();Context context=context(input,actor);
        List<Map<String,Object>> visible=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT a.id,a.handle,a.platform,a.handle_key,a.verified_identity,c.credential_ciphertext FROM external_account a LEFT JOIN remote_user_credential c ON c.account_id=a.id WHERE a.uid=? AND a.platform=? ORDER BY a.id",actor.getUid(),context.ref.platform)) {
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",row.get("id"));item.put("handle",row.get("handle"));item.put("platform",row.get("platform"));
            item.put("ownershipVerified",Objects.equals(row.get("handle_key"),row.get("verified_identity")));item.put("authorized",ready(row));visible.add(item);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("platform",context.ref.platform);result.put("submissionSupported",context.ref.submissionSupported());result.put("accounts",visible);return result;
    }
    public static final class Offer {
        public String uid,context,problem,contest,platform;
        public long accountId,version;
        public Map<String,String> languages;
        public Map<String,String> sourceContext;
    }
    public Map<String,Object> options(PersonalRemoteSubmissionDTO input) {
        AccountProfile actor=actor();requireEnabled();Context context=context(input,actor);
        if(input.accountId==null || input.accountId<=0)throw new ObjectiveException(400,"请选择本人平台账号");
        Map<String,Object> account=account(input.accountId,actor.getUid(),context.ref.platform);
        if(!ready(account))throw new ObjectiveException(409,"请先验证账号归属并授权源站登录会话");
        long version=number(account.get("credential_version"));
        PersonalRemoteFormClient.Prepared prepared="NOWCODER".equals(context.ref.platform)?forms.preparation(input.accountId,actor.getUid(),version,(String)account.get("handle"),context.ref):null;
        if("NOWCODER".equals(context.ref.platform) && prepared==null)throw new ObjectiveException(503,"牛客题目校验暂不可用，请稍后重试");
        Map<String,String> languages=prepared==null?forms.languages(input.accountId,actor.getUid(),version,(String)account.get("handle"),context.ref):prepared.languages;
        Map<String,Object> latest=account(input.accountId,actor.getUid(),context.ref.platform);
        if(!ready(latest) || number(latest.get("credential_version"))!=version)
            throw new ObjectiveException(409,"会话授权已变化，请重新读取语言");
        if(!contextKey(input,context(input,actor)).equals(contextKey(input,context)))throw new ObjectiveException(409,"题目范围已变化，请重新读取语言");
        Offer offer=new Offer();offer.uid=actor.getUid();offer.context=contextKey(input,context);offer.problem=context.ref.problem;offer.contest=context.ref.contest;offer.platform=context.ref.platform;
        offer.accountId=input.accountId;offer.version=version;offer.languages=new LinkedHashMap<>(languages);
        if(prepared!=null)offer.sourceContext=new LinkedHashMap<>(prepared.sourceContext);
        if(offer.languages.isEmpty())throw new ObjectiveException(503,"源站未提供可用语言");
        String offerId=UUID.randomUUID().toString();
        try{redis.opsForValue().set("personal:remote:offer:"+offerId,json.writeValueAsString(offer),5,TimeUnit.MINUTES);}
        catch(Exception e){throw new ObjectiveException(503,"语言选择暂时无法保存，请稍后重试");}
        Map<String,Object> result=new LinkedHashMap<>();result.put("offerId",offerId);result.put("languages",offer.languages);result.put("expiresIn",300);result.put("submissionSupported",context.ref.submissionSupported());return result;
    }
    private Offer offer(PersonalRemoteSubmissionDTO input,AccountProfile actor,Context context) {
        if(input.offerId==null || !input.offerId.matches("[A-Za-z0-9_-]{16,64}"))throw new ObjectiveException(400,"请先读取源站语言");
        Offer offer;
        try{String value=redis.opsForValue().get("personal:remote:offer:"+input.offerId);offer=value==null?null:json.readValue(value,Offer.class);}
        catch(Exception e){throw new ObjectiveException(503,"暂时无法校验语言选择，请稍后重试");}
        if(offer==null || !Objects.equals(offer.uid,actor.getUid()) || offer.accountId!=input.accountId || !Objects.equals(offer.context,contextKey(input,context))
                || offer.languages==null || !offer.languages.containsKey(input.languageId))throw new ObjectiveException(409,"语言选择已失效，请重新读取");
        Map<String,Object> account=account(input.accountId,actor.getUid(),context.ref.platform);
        if(!ready(account) || number(account.get("credential_version"))!=offer.version)throw new ObjectiveException(409,"会话授权已变化，请重新读取语言");
        return offer;
    }
    private Long existing(String uid,String requestId,String hash) { return existing(uid,requestId,hash,false); }
    private Long existing(String uid,String requestId,String hash,boolean current) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT submit_id,request_hash FROM personal_remote_task WHERE owner_uid=? AND request_id=?"+(current?" FOR UPDATE":""),uid,requestId);
        if(rows.isEmpty())return null;
        if(!hash.equals(rows.get(0).get("request_hash")))throw new ObjectiveException(409,"同一请求编号不能提交不同的代码或范围");
        return number(rows.get(0).get("submit_id"));
    }
    public Map<String,Object> submit(PersonalRemoteSubmissionDTO input,String ip) {
        AccountProfile actor=actor();requireEnabled();validateScope(input);
        if(input.accountId==null || input.accountId<=0 || input.languageId==null || !input.languageId.matches("[1-9][0-9]{0,8}")
                || input.requestId==null || !input.requestId.matches("[A-Za-z0-9_-]{16,64}") || input.code==null || input.code.trim().isEmpty()
                || input.code.getBytes(StandardCharsets.UTF_8).length>65535)throw new ObjectiveException(400,"提交信息不完整，或代码超过 65535 字节");
        String hash=requestHash(input);Long previous=existing(actor.getUid(),input.requestId,hash);
        if(previous!=null)return submitted(previous,actor.getUid());
        Long id;
        try {
            id=new TransactionTemplate(transactions).execute(transaction->{
                Long duplicate=existing(actor.getUid(),input.requestId,hash);if(duplicate!=null)return duplicate;
                Map<String,Object> owner=store().lockForEnqueue(actor.getUid(),input.accountId,null);
                Context context=context(input,actor);
                if(!context.ref.submissionSupported())throw new ObjectiveException(503,"牛客代码投递与结果核对仍在接入，目前仅支持会话授权和提交前校验");
                duplicate=existing(actor.getUid(),input.requestId,hash,true);if(duplicate!=null)return duplicate;
                Offer offer=offer(input,actor,context);
                int interval=value(input.cid)>0?0:switchConfig.getSwitchConfig().getDefaultSubmitInterval();
                if(interval>0 && !Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("personal:remote:submit:"+actor.getUid(),"1",interval,TimeUnit.SECONDS)))
                    throw new ObjectiveException(429,"提交较频繁，请稍后重试");
                Judge judge=context.judge.setShare(false).setCode(input.code).setLanguage(offer.languages.get(input.languageId))
                        .setLength(input.code.length()).setUsername(actor.getUsername()).setStatus(5).setSubmitTime(new Date()).setVersion(0).setIp(ip);
                checked(()->{
                    if(value(input.cid)>0)before.initContestSubmission(input.cid,input.pid,actor,judge);
                    else if(value(input.tid)>0)before.initTrainingSubmission(input.tid,input.pid,actor,judge);
                    else before.initCommonSubmission(input.pid,judge.getGid(),judge);
                });
                NowCoderJudgeProtocol.Context source=null;
                if("NOWCODER".equals(context.ref.platform))try{
                    if(offer.sourceContext==null || offer.sourceContext.size()!=3)throw new IllegalArgumentException();
                    source=new NowCoderJudgeProtocol.Context((String)owner.get("handle_key"),offer.sourceContext.get("questionId"),offer.sourceContext.get("problemId"),offer.sourceContext.get("contestId"),input.languageId,offer.languages.get(input.languageId));
                }catch(IllegalArgumentException e){throw new ObjectiveException(409,"牛客题目校验已失效，请重新读取语言");}
                store().enqueue(actor.getUid(),judge.getSubmitId(),input.accountId,input.requestId,input.languageId,hash,offer.version,source);
                return judge.getSubmitId();
            });
        } catch(PersonalRemoteTaskStore.Conflict e) {
            // A racing identical request rolls back its judge/training/contest inserts before reading the winner.
            Long winner=existing(actor.getUid(),input.requestId,hash);
            if(winner==null)throw new ObjectiveException(409,"账号、题目或提交状态已变化，请重新读取后提交");id=winner;
        }
        return submitted(id,actor.getUid());
    }
    private Map<String,Object> submitted(long id,String uid) {
        Map<String,Object> task=store().status(id,uid);
        // Submission acknowledgements do not disclose source links that bypass sealed OI results.
        task.remove("remote_submit_id");
        Map<String,Object> result=new LinkedHashMap<>();result.put("submitId",id);result.put("task",task);return result;
    }
    public Map<String,Object> status(long id) {
        AccountProfile actor=actor();Map<String,Object> task=store().status(id,actor.getUid());
        Judge judge;
        try{judge=judges.getSubmission(id).getSubmission();}
        catch(Exception e){throw new ObjectiveException(403,"当前不可查看该提交结果");}
        Map<String,Object> result=new LinkedHashMap<>();result.put("submitId",id);result.put("task",task);
        result.put("verdict",judge.getStatus());result.put("time",judge.getTime());result.put("memory",judge.getMemory());result.put("score",judge.getScore());
        boolean hidden=judge.getSubmitId()==null;result.put("resultHidden",hidden);if(hidden)task.remove("remote_submit_id");return result;
    }
    public Map<String,Object> request(String requestId) {
        AccountProfile actor=actor();
        if(requestId==null || !requestId.matches("[A-Za-z0-9_-]{16,64}"))throw new ObjectiveException(400,"请求编号无效");
        List<Long> ids=jdbc.queryForList("SELECT submit_id FROM personal_remote_task WHERE owner_uid=? AND request_id=?",Long.class,actor.getUid(),requestId);
        if(ids.isEmpty())throw new ObjectiveException(404,"尚未找到该请求的提交，可以使用原请求重试");return status(ids.get(0));
    }
    public Map<String,Object> reconcile(long id) { AccountProfile actor=actor();store().reconcile(id,actor.getUid());return submitted(id,actor.getUid()); }
    public Map<String,Object> cancel(long id) { AccountProfile actor=actor();store().cancelBeforePost(id,actor.getUid());return submitted(id,actor.getUid()); }
    private interface CheckedAction { void run() throws Exception; }
    private void checked(CheckedAction action) {
        try{action.run();}
        catch(StatusNotFoundException e){throw new ObjectiveException(404,e.getMessage());}
        catch(StatusForbiddenException | StatusAccessDeniedException | AccessException e){throw new ObjectiveException(403,e.getMessage());}
        catch(StatusFailException e){throw new ObjectiveException(400,e.getMessage());}
        catch(ObjectiveException e){throw e;}
        catch(Exception e){throw new ObjectiveException(503,"暂时无法检查提交权限");}
    }
}
