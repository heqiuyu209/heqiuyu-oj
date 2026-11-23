package top.hcode.hoj.manager.oj.external;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.remote.*;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PersonalRemoteFormClient {
    @Autowired private RemoteCredentialManager credentials;
    @Autowired private StringRedisTemplate redis;
    public static final class Prepared {
        public final Map<String,String> languages,sourceContext;
        public Prepared(Map<String,String> languages,Map<String,String> sourceContext){this.languages=Collections.unmodifiableMap(new LinkedHashMap<>(languages));this.sourceContext=Collections.unmodifiableMap(new LinkedHashMap<>(sourceContext));}
    }

    public Map<String,String> languages(long id, String uid, long version, String handle, RemoteProblemRef ref) {
        return preparation(id,uid,version,handle,ref).languages;
    }
    public Prepared preparation(long id,String uid,long version,String handle,RemoteProblemRef ref){
        RemoteSession session=credentials.sessionForDispatch(id,uid,version);
        AtomicReference<RemoteSession> current=new AtomicReference<>(session);
        AtomicBoolean firstRequest=new AtomicBoolean(true);
        try(PersonalRemoteTransport http=new PersonalRemoteTransport(ref.platform,session,()->{
            if(firstRequest.getAndSet(false) && !Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("external:personal-options:"+id,"1",10,TimeUnit.SECONDS)))
                throw new ObjectiveException(429,"语言读取较频繁，请稍后重试");
            sourceGate(ref.platform);
            RemoteSession latest=credentials.sessionForDispatch(id,uid,version);
            if(!latest.cookieHeader().equals(current.get().cookieHeader()))throw new ObjectiveException(409,"会话已更新，请重新读取语言");
        })) {
            http.onSessionUpdate(updated->{credentials.rotateSession(id,uid,version,current.get(),updated);current.set(updated);});
            PersonalRemoteSource.Form form=new PersonalRemoteSource(ref,handle,http).form();return new Prepared(form.languages(),form.sourceContext());
        } catch(RemoteSourceFailure e) {
            if(e.kind==RemoteSourceFailure.Kind.AUTH_REQUIRED)throw new ObjectiveException(409,"源站会话已失效或身份不符，请重新登录并授权");
            if(e.kind==RemoteSourceFailure.Kind.CHALLENGE)throw new ObjectiveException(503,"源站要求登录验证，请先在源站完成验证，再更新会话授权");
            throw new ObjectiveException(503,"暂时无法读取源站语言，请稍后重试");
        }
    }
    private void sourceGate(String platform){
        String key="NOWCODER".equals(platform)?"external:nowcoder:public-http":"CODEFORCES".equals(platform)?"external:cf:public-api":"external:at:personal-http";
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos("NOWCODER".equals(platform)?8:0);
        try{
            do{
                if(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key,UUID.randomUUID().toString(),3,TimeUnit.SECONDS)))return;
                if(!"NOWCODER".equals(platform))break;Thread.sleep(250);
            }while(System.nanoTime()<deadline);
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        throw new ObjectiveException(429,"语言读取较频繁，请稍后重试");
    }
}
