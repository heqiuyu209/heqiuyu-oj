package top.hcode.hoj.manager.oj.external;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import javax.annotation.*;
import java.util.*;
import java.util.concurrent.*;

/** Isolated worker: a slow remote source cannot delay judging or contest deadline tasks. */
@Component
public class ExternalSyncWorker {
    @Autowired private ExternalAccountManager accounts;
    @Autowired private CodeforcesDataClient client;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RemoteCatalogManager catalogue;
    @Autowired private RemoteStatementManager statements;
    @Autowired private CodeforcesStatementClient statementClient;
    @Autowired private AtCoderStatementClient atcoderStatementClient;
    @Autowired private AtCoderDataClient atcoderClient;
    @Autowired private NowCoderDataClient nowcoderClient;
    @Autowired private AtCoderCatalogManager atcoderCatalogue;
    @Autowired private NowCoderCatalogManager nowcoderCatalogue;
    @Autowired private NowCoderStatementClient nowcoderStatements;
    @Autowired private NowCoderSourceManager nowcoderSources;
    @Autowired private NowCoderContextManager nowcoderContexts;
    @Autowired private NowCoderContestClient nowcoderContestClient;
    @Autowired private NowCoderPracticeManager nowcoderPractice;
    @Autowired private NowCoderPracticeClient nowcoderPracticeClient;
    private long turn;
    @Value("${hoj.external-sync.enabled:true}") private boolean enabled;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task -> {Thread thread=new Thread(task,"external-learning-sync");thread.setDaemon(true);return thread;});
    @PostConstruct public void start() { if(enabled) worker.scheduleWithFixedDelay(this::tick,10,5,TimeUnit.SECONDS); }
    @PreDestroy public void close() { worker.shutdownNow(); }
    public void tick() {
        Map<String,Object> job=null;String kind=null;
        try {
            // Shared across backend instances. Redis outages fail closed rather than flooding the source.
            if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("external:cf:public-api",UUID.randomUUID().toString(),3,TimeUnit.SECONDS))) return;
            // Share the source-rate gate across providers and fairly rotate every kind of work.
            int first=(int)(turn++%7);
            for(int n=0;n<7 && job==null;n++){
                int choice=(first+n)%7;
                if(choice==0){kind="CATALOG";job=catalogue.claim();}
                else if(choice==1){kind="STATEMENT";job=statements.claim();}
                else if(choice==2){kind="ACCOUNT";job=accounts.claim();}
                else if(choice==3){kind="ATCODER_CATALOG";job=atcoderCatalogue.claim();}
                else if(choice==4){kind="NOWCODER_CATALOG";job=nowcoderCatalogue.claim();}
                else if(choice==5){kind="NOWCODER_CONTEXT";job=nowcoderContexts.claim();}
                else{kind="NOWCODER_PRACTICE";job=nowcoderPractice.claim();}
            }
            if(job==null)return;
            if("CATALOG".equals(kind))catalogue.apply(job,client.fetch("CATALOG",null,1));
            else if("STATEMENT".equals(kind)){
                if(RemoteStatementManager.nowcoder((String)job.get("problem_key"))){
                    statements.applyNowCoder(job,nowcoderStatements.fetch((String)job.get("problem_key"),nowcoderSources::gate));
                }else if(RemoteStatementManager.atcoder((String)job.get("problem_key"))){
                    if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("external:at:personal-http",UUID.randomUUID().toString(),3,TimeUnit.SECONDS))){statements.defer(job);return;}
                    Map<String,Object> source=statements.atcoderSource(job);
                    statements.apply(job,atcoderStatementClient.fetch((String)source.get("contest_id"),(String)source.get("task_id")));
                }else statements.apply(job,statementClient.fetch((String)job.get("problem_key")));
            }
            else if("NOWCODER_PRACTICE".equals(kind)){
                if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("external:nowcoder:public-http",UUID.randomUUID().toString(),3,TimeUnit.SECONDS))){nowcoderPractice.defer(job);return;}
                nowcoderPractice.apply(job,nowcoderPracticeClient.fetch(job));
            }
            else if("NOWCODER_CONTEXT".equals(kind)){
                if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("external:nowcoder:public-http",UUID.randomUUID().toString(),3,TimeUnit.SECONDS))){nowcoderContexts.defer(job);return;}
                nowcoderContexts.apply(job,nowcoderContestClient.fetch(job));
            }
            else if("NOWCODER_CATALOG".equals(kind)){
                if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("external:nowcoder:public-http",UUID.randomUUID().toString(),3,TimeUnit.SECONDS))){nowcoderCatalogue.defer(job);return;}
                nowcoderCatalogue.apply(job,nowcoderStatements.fetchPage(((Number)job.get("next_page")).intValue(),()->{}));
            }
            else if("ATCODER_CATALOG".equals(kind))atcoderCatalogue.apply(job,atcoderClient.fetch("ATCODER_MODELS".equals(job.get("source_name"))?"MODELS":"PROBLEMS",null,0));
            else if("NOWCODER".equals(job.get("platform"))) {
                if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("external:nowcoder:public-http",UUID.randomUUID().toString(),3,TimeUnit.SECONDS))){accounts.defer(job);return;}
                accounts.apply(job,nowcoderClient.fetch((String)job.get("stage"),(String)job.get("handle"),((Number)job.get("from_index")).intValue()));
            }
            else if("ATCODER".equals(job.get("platform")))accounts.apply(job,atcoderClient.fetch((String)job.get("stage"),(String)job.get("handle"),((Number)job.get("from_second")).longValue()));
            else accounts.apply(job,client.fetch((String)job.get("stage"),(String)job.get("handle"),((Number)job.get("from_index")).intValue()));
        } catch(Exception e) {
            if(job!=null)try{
                String reason=e instanceof ObjectiveException?e.getMessage():"同步未完成，已保留已有记录，稍后自动重试";
                if("NOWCODER_PRACTICE".equals(kind))nowcoderPractice.failed(job,reason);else if("NOWCODER_CONTEXT".equals(kind))nowcoderContexts.failed(job,reason);else if("NOWCODER_CATALOG".equals(kind))nowcoderCatalogue.failed(job,reason);else if("STATEMENT".equals(kind) && RemoteStatementManager.nowcoder((String)job.get("problem_key")) && e instanceof ObjectiveException && ((ObjectiveException)e).getStatus()==429)statements.defer(job);else if("CATALOG".equals(kind))catalogue.failed(job,reason);
                else if("STATEMENT".equals(kind)){
                    if(e instanceof CodeforcesStatementClient.AccessRejected)statements.failed(job,reason,true);
                    else statements.failed(job,reason);
                }else if("ATCODER_CATALOG".equals(kind))atcoderCatalogue.failed(job,reason);else accounts.failed(job,reason);
            }catch(Exception ignored){/* A lease can be recovered after expiry. */}
        }
    }
}
