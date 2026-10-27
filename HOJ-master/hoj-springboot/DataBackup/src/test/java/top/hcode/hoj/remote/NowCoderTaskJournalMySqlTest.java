package top.hcode.hoj.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.support.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local MySQL only, no network transport or real source session. Deletes only this fixture's rows. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class NowCoderTaskJournalMySqlTest {
    private long insert(JdbcTemplate jdbc,String sql,Object... args){KeyHolder key=new GeneratedKeyHolder();jdbc.update(connection->{PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int n=0;n<args.length;n++)statement.setObject(n+1,args[n]);return statement;},key);return Objects.requireNonNull(key.getKey()).longValue();}
    @Test void repeatedReadIsolationSerializesNcAcknowledgementAndFinalVerdict() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");assertNotNull(password);
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/hoj?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password);JdbcTemplate jdbc=new JdbcTemplate(ds);DataSourceTransactionManager tm=new DataSourceTransactionManager(ds);TransactionTemplate tx=new TransactionTemplate(tm);
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM personal_remote_task WHERE platform='NOWCODER'",Integer.class));
        String uid=UUID.randomUUID().toString().replace("-",""),name="nc_job_"+uid.substring(0,10),sourceId=String.valueOf(800000000000L+Long.parseLong(uid.substring(0,10),16)%100000000000L),problemId="NC-"+sourceId;Long pid=null;long original=jdbc.queryForObject("SELECT COUNT(*) FROM problem",Long.class);
        AtomicLong clock=new AtomicLong(System.currentTimeMillis());PersonalRemoteTaskStore tasks=new PersonalRemoteTaskStore(jdbc,tm,clock::get);NowCoderTaskJournal journal=new NowCoderTaskJournal(jdbc,tm,tasks);
        try{
            jdbc.update("INSERT INTO user_info(uuid,username,password) VALUES(?,?,?)",uid,name,"synthetic-local-fixture-only");pid=insert(jdbc,"INSERT INTO problem(problem_id,title,author,is_remote,auth) VALUES(?,?,?,TRUE,2)",problemId,"synthetic NC journal fixture",name);
            long account=insert(jdbc,"INSERT INTO external_account(uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(?,'NOWCODER',?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",uid,sourceId,sourceId,sourceId);jdbc.update("INSERT INTO remote_user_credential VALUES(?,'s1:synthetic-never-dispatched',1,CURRENT_TIMESTAMP(3))",account);
            long id=insert(jdbc,"INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code,language) VALUES(?,?,?,?,NOW(),5,'synthetic source','C++')",pid,problemId,uid,name);
            NowCoderJudgeProtocol.Context context=new NowCoderJudgeProtocol.Context(sourceId,"1338275",sourceId,"","2","C++");tx.execute(status->tasks.enqueue(uid,id,account,"nc_request_"+uid.substring(0,12),"2",null,1L,context));
            Map<String,Object> task=tasks.claim("NOWCODER");assertEquals(id,((Number)task.get("submit_id")).longValue());String token=(String)task.get("lease_token");
            try(NowCoderTaskJournalTest.FakeSource source=new NowCoderTaskJournalTest.FakeSource(){@Override public String get(String path){return "<script>\nwindow.isLogin=true;\nwindow.globalInfo={ownerId:'"+sourceId+"'};\n</script>";}}){
                NowCoderJudgeProtocol protocol=new NowCoderJudgeProtocol(source);NowCoderJudgeProtocol.Acknowledgement ack=protocol.submit(context,"synthetic source",()->assertTrue(tasks.beginPost(id,token,90)));journal.acknowledged(id,token,ack);
                assertEquals("opaque-job",journal.job(id));assertNull(jdbc.queryForObject("SELECT remote_submit_id FROM personal_remote_task WHERE submit_id=?",Long.class,id));
                Map<String,Object> poll=tasks.claim("NOWCODER");String lease=(String)poll.get("lease_token");NowCoderJudgeProtocol.Status result=protocol.status(context,"opaque-job");
                ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);AtomicInteger publications=new AtomicInteger();
                Callable<Boolean> complete=()->{start.await();try{return journal.result(id,lease,result,()->{jdbc.update("UPDATE judge SET status=0 WHERE submit_id=?",id);publications.incrementAndGet();});}catch(PersonalRemoteTaskStore.Conflict expected){return false;}};
                try{Future<Boolean> first=workers.submit(complete),second=workers.submit(complete);start.countDown();assertEquals(1,(first.get(10,TimeUnit.SECONDS)?1:0)+(second.get(10,TimeUnit.SECONDS)?1:0));}finally{workers.shutdownNow();}
                assertEquals(1,publications.get());assertEquals("COMPLETED",jdbc.queryForObject("SELECT state FROM personal_remote_task WHERE submit_id=?",String.class,id));assertEquals(100L,jdbc.queryForObject("SELECT remote_submit_id FROM personal_remote_task WHERE submit_id=?",Long.class,id));assertEquals(100L,jdbc.queryForObject("SELECT source_submission_id FROM personal_remote_nowcoder WHERE submit_id=?",Long.class,id));assertEquals(0,jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=?",Integer.class,id));assertEquals(1,source.posts);
            }
        }finally{
            jdbc.update("DELETE FROM judge WHERE uid=?",uid);jdbc.update("DELETE FROM external_account WHERE uid=?",uid);jdbc.update("DELETE FROM personal_remote_lane WHERE platform='NOWCODER' AND handle_key=? AND active_submit_id IS NULL",sourceId);if(pid!=null)jdbc.update("DELETE FROM problem WHERE id=? AND author=?",pid,name);jdbc.update("DELETE FROM user_info WHERE uuid=?",uid);
            assertEquals(original,jdbc.queryForObject("SELECT COUNT(*) FROM problem",Long.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM personal_remote_task WHERE owner_uid=?",Integer.class,uid));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_info WHERE uuid=?",Integer.class,uid));
        }
    }
}
