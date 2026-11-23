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
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static top.hcode.hoj.remote.PersonalRemoteTaskStore.*;

/** Opt-in local MySQL only. No worker, remote login, platform POST, or existing-user changes. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST", matches="1")
class PersonalRemoteTaskMySqlTest {
    private long insert(JdbcTemplate jdbc, String sql, Object... args) {
        KeyHolder key=new GeneratedKeyHolder();jdbc.update(connection->{PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},key);return Objects.requireNonNull(key.getKey()).longValue();
    }
    @Test void realMySqlSerializesWorkersRecoversOnlyReadsAndCleansOnlyItsFixtures() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");assertNotNull(password,"Provide the local Compose database password through the environment");
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/hoj?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password);
        JdbcTemplate jdbc=new JdbcTemplate(source);DataSourceTransactionManager transactions=new DataSourceTransactionManager(source);TransactionTemplate tx=new TransactionTemplate(transactions);AtomicLong now=new AtomicLong(System.currentTimeMillis());PersonalRemoteTaskStore store=new PersonalRemoteTaskStore(jdbc,transactions,now::get),other=new PersonalRemoteTaskStore(jdbc,transactions,now::get);
        String uid=UUID.randomUUID().toString().replace("-",""),suffix=uid.substring(0,12),handle="task_it_"+suffix,remoteProblem="CF-"+(900000000+new Random().nextInt(99999999))+"A";Long pid=null;
        long originalProblems=jdbc.queryForObject("SELECT COUNT(*) FROM problem",Long.class);
        try {
            jdbc.update("INSERT INTO user_info(uuid,username,password) VALUES(?,?,?)",uid,handle,"synthetic-local-test-only");
            pid=insert(jdbc,"INSERT INTO problem(problem_id,title,author,is_remote,auth) VALUES(?,?,?,TRUE,2)",remoteProblem,"synthetic personal-remote journal fixture",handle);
            long account=insert(jdbc,"INSERT INTO external_account(uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(?,'CODEFORCES',?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",uid,handle,handle,handle);
            jdbc.update("INSERT INTO remote_user_credential VALUES(?,'s1:synthetic-test-cipher',1,CURRENT_TIMESTAMP(3))",account);
            long first=insert(jdbc,"INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code,language) VALUES(?,?,?,?,NOW(),5,'synthetic source','C++')",pid,remoteProblem,uid,handle);
            long second=insert(jdbc,"INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code,language) VALUES(?,?,?,?,NOW(),5,'synthetic second source','C++')",pid,remoteProblem,uid,handle);
            tx.execute(status->store.enqueue(uid,first,account,"request_"+suffix+"_one","54"));tx.execute(status->store.enqueue(uid,second,account,"request_"+suffix+"_two","54"));
            ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);Map<String,Object> claimed;
            try {Future<Map<String,Object>> a=workers.submit(()->{go.await();return store.claim("CODEFORCES");}),b=workers.submit(()->{go.await();return other.claim("CODEFORCES");});go.countDown();Map<String,Object> left=a.get(10,TimeUnit.SECONDS),right=b.get(10,TimeUnit.SECONDS);assertEquals(1,(left==null?0:1)+(right==null?0:1));claimed=left==null?right:left;}
            finally{workers.shutdownNow();}
            assertEquals(first,((Number)claimed.get("submit_id")).longValue());String token=(String)claimed.get("lease_token");assertTrue(store.authorize(first,token));assertTrue(store.beginPost(first,token,100));
            assertTrue(store.rotateSession(first,token,"s1:synthetic-test-cipher","s1:synthetic-rotated"));
            assertFalse(other.rotateSession(first,token,"s1:synthetic-test-cipher","s1:synthetic-stale"));
            now.addAndGet(90001);Map<String,Object> recovered=other.claim("CODEFORCES");assertEquals("RECONCILING",recovered.get("state"));String replacement=(String)recovered.get("lease_token");assertThrows(Conflict.class,()->store.beginPost(first,replacement,100));assertThrows(Conflict.class,()->store.accepted(first,token,101));
            other.review(first,replacement,PersonalRemoteTaskStore.Error.AUTH_REQUIRED);
            jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=NULL,credential_version=2 WHERE account_id=?",account);
            assertThrows(Conflict.class,()->store.reconcile(first,uid));
            jdbc.update("UPDATE remote_user_credential SET credential_ciphertext='s1:synthetic-new-session',credential_version=3 WHERE account_id=?",account);
            store.reconcile(first,uid);recovered=other.claim("CODEFORCES");assertEquals(3,((Number)recovered.get("credential_version")).intValue());
            String readOnlyToken=(String)recovered.get("lease_token");assertTrue(other.authorize(first,readOnlyToken));assertThrows(Conflict.class,()->other.beginPost(first,readOnlyToken,100));
            other.accepted(first,readOnlyToken,101);Map<String,Object> poll=store.claim("CODEFORCES");store.waitingForResult(first,(String)poll.get("lease_token"));now.addAndGet(5001);poll=store.claim("CODEFORCES");
            store.completed(first,(String)poll.get("lease_token"),()->jdbc.update("UPDATE judge SET status=0 WHERE submit_id=?",first));assertEquals(0,jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=?",Integer.class,first));
            Map<String,Object> queued=other.claim("CODEFORCES");assertEquals(second,((Number)queued.get("submit_id")).longValue());store.cancelBeforePost(second,uid);assertThrows(Conflict.class,()->store.beginPost(second,(String)queued.get("lease_token"),101));
            assertTrue(store.finishStopped(second,()->jdbc.update("UPDATE judge SET status=-4 WHERE submit_id=?",second)));
            assertFalse(other.finishStopped(second,()->fail("Already settled cancellation")));
            System.out.println("Local MySQL personal journal: concurrent lease, identity serialization, uncertain POST recovery, stale callback fencing, atomic verdict and cancellation passed; no remote request sent.");
        } finally {
            jdbc.update("DELETE FROM judge WHERE uid=?",uid);jdbc.update("DELETE FROM external_account WHERE uid=?",uid);jdbc.update("DELETE FROM personal_remote_lane WHERE platform='CODEFORCES' AND handle_key=? AND active_submit_id IS NULL",handle);
            if(pid!=null)jdbc.update("DELETE FROM problem WHERE id=? AND author=?",pid,handle);jdbc.update("DELETE FROM user_info WHERE uuid=?",uid);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM personal_remote_task WHERE owner_uid=?",Integer.class,uid));assertEquals(originalProblems,jdbc.queryForObject("SELECT COUNT(*) FROM problem",Long.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_info WHERE uuid=?",Integer.class,uid));
        }
    }
}
