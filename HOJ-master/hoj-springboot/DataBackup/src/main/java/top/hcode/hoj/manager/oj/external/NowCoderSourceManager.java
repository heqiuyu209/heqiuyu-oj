package top.hcode.hoj.manager.oj.external;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.remote.RemoteProblemRef;
import top.hcode.hoj.utils.ShiroUtils;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

@Component
public class NowCoderSourceManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private NowCoderStatementClient client;
    @Autowired private NowCoderCatalogManager catalogue;
    protected String uid(){return ShiroUtils.getProfile().getUid();}
    private void authorized(String uid){if(jdbc.queryForObject("SELECT COUNT(*) FROM user_info u JOIN user_role ur ON ur.uid=u.uuid JOIN role r ON r.id=ur.role_id WHERE u.uuid=? AND u.status=0 AND r.role IN ('root','admin','problem_admin')",Integer.class,uid)==0)throw new ObjectiveException(403,"当前账号无权获取来源题目");}
    public static String key(String input){
        if(input==null || input.trim().isEmpty() || input.length()>200)throw new ObjectiveException(400,"请输入牛客题号或比赛题号");String value=input.trim();
        if(value.startsWith("nowcoder:")){NowCoderStatementClient.ref(value);return value;}
        if(value.startsWith("https://"))try{String key=ProblemIdentity.nowcoder(URI.create(value));NowCoderStatementClient.ref(key);return key;}catch(IllegalArgumentException e){throw new ObjectiveException(400,"请使用固定的牛客 ACM 题目地址");}
        Matcher match=Pattern.compile("(?i)(?:NC-?)?([1-9][0-9]{0,11})(?:[-/]([A-Z][A-Z0-9]{0,15}))?").matcher(value);if(!match.matches())throw new ObjectiveException(400,"牛客题号无效，例如 NC218144 或 11244/A");return match.group(2)==null?"nowcoder:acm:"+match.group(1):"nowcoder:contest:"+match.group(1)+":"+match.group(2).toUpperCase(Locale.ROOT);
    }
    public void gate(){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        try{do{if(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("external:nowcoder:public-http",UUID.randomUUID().toString(),3,TimeUnit.SECONDS)))return;Thread.sleep(250);}while(System.nanoTime()<deadline);}
        catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception e){throw new ObjectiveException(503,"来源频率控制暂不可用，请稍后重试");}
        throw new ObjectiveException(429,"牛客来源请求较频繁，请稍后重试");
    }
    public Map<String,Object> lookup(String input){
        String key=key(input),actor=uid();authorized(actor);
        try{if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("remote:nowcoder:lookup:"+actor,"1",10,TimeUnit.SECONDS)))throw new ObjectiveException(429,"题目查找较频繁，请稍后重试");}catch(ObjectiveException e){throw e;}catch(Exception e){throw new ObjectiveException(503,"来源频率控制暂不可用");}
        NowCoderStatementClient.Statement statement=client.fetch(key,()->{authorized(actor);gate();});authorized(actor);catalogue.discovered(key,statement);RemoteProblemRef ref=NowCoderStatementClient.ref(key);Map<String,Object> result=new LinkedHashMap<>();result.put("problemKey",key);result.put("displayId",ref.problem);result.put("title",statement.content.getTitle());result.put("sourceUrl",NowCoderStatementClient.sourceUrl(key));return result;
    }
}
