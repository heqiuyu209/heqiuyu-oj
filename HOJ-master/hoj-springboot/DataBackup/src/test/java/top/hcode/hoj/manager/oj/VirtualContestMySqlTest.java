package top.hcode.hoj.manager.oj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.ai.AiAccessGuard;
import top.hcode.hoj.pojo.dto.VirtualContestDTO;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.validator.ContestValidator;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class VirtualContestMySqlTest {
    @Test void simultaneousStartsAcrossContestsSeeTheCommittedWindowUnderRepeatableRead() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD"),schema="hoj_virtual_it_"+UUID.randomUUID().toString().replace("-","");
        JdbcTemplate admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/hoj?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password));
        long original=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class);admin.execute("CREATE DATABASE "+schema);
        try {
            DriverManagerDataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",password);
            JdbcTemplate jdbc=new JdbcTemplate(source);TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
            jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");jdbc.update("INSERT INTO user_info VALUES('alice',0)");
            jdbc.execute("CREATE TABLE contest(id BIGINT UNSIGNED PRIMARY KEY,start_time DATETIME(3),end_time DATETIME(3),uid VARCHAR(32) DEFAULT 'teacher',title VARCHAR(500) DEFAULT 'Virtual fixture',auth INT DEFAULT 0,visible BOOLEAN DEFAULT TRUE)");
            jdbc.execute("CREATE TABLE contest_paper(contest_id BIGINT)");jdbc.execute("CREATE TABLE contest_problem(cid BIGINT)");
            jdbc.execute("CREATE TABLE paper_run(id BIGINT PRIMARY KEY,virtual_run_id BIGINT)");
            for(int i=1;i<=2;i++){jdbc.update("INSERT INTO contest(id,start_time,end_time) VALUES(?,?,?)",i,new Timestamp(0),new Timestamp(60000));jdbc.update("INSERT INTO contest_problem VALUES(?)",i);}
            try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("virtual-contests.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("virtual-contests.sql"));}
            AtomicLong clock=new AtomicLong(System.currentTimeMillis());
            AiAccessGuard ai=mock(AiAccessGuard.class);
            ThreadLocal<String> actorUid=new ThreadLocal<>();
            VirtualContestManager manager=new VirtualContestManager(){@Override protected long now(){return clock.get();}@Override protected AccountProfile actor(){AccountProfile actor=new AccountProfile();actor.setUid(actorUid.get()==null?"alice":actorUid.get());return actor;}@Override protected boolean root(){return false;}};
            ReflectionTestUtils.setField(manager,"jdbc",jdbc);ReflectionTestUtils.setField(manager,"validator",mock(ContestValidator.class));ReflectionTestUtils.setField(manager,"ai",ai);
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
                List<Future<Integer>> results=new ArrayList<>();
                for(long cid=1;cid<=2;cid++){final long contestId=cid;results.add(workers.submit(()->{ready.countDown();start.await();try{tx.execute(s->{VirtualContestDTO request=new VirtualContestDTO();request.setRequestId("mysql_request_"+contestId);return manager.start(contestId,request);});return 200;}catch(ObjectiveException error){return error.getStatus();}}));}
                assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();List<Integer> statuses=Arrays.asList(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(Arrays.asList(200,409),statuses);
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM contest_virtual_run",Integer.class));
                Map<String,Object> row=jdbc.queryForMap("SELECT * FROM contest_virtual_run");long cid=((Number)row.get("cid")).longValue();VirtualContestDTO retry=new VirtualContestDTO();retry.setRequestId((String)row.get("request_id"));
                clock.addAndGet(1000);tx.execute(s->manager.start(cid,retry));assertEquals(row.get("ends_at"),jdbc.queryForMap("SELECT * FROM contest_virtual_run").get("ends_at"));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM contest_virtual_run",Integer.class));
                // Different users must not take competing gap locks on an empty run index.
                jdbc.update("DELETE FROM contest_virtual_run");jdbc.update("INSERT INTO user_info VALUES('bob',0)");
                CountDownLatch distinctReady=new CountDownLatch(2),distinctStart=new CountDownLatch(1);List<Future<Integer>> independent=new ArrayList<>();
                for(long contestId=1;contestId<=2;contestId++){final long target=contestId;independent.add(workers.submit(()->{actorUid.set(target==1?"alice":"bob");distinctReady.countDown();distinctStart.await();try{tx.execute(s->{VirtualContestDTO request=new VirtualContestDTO();request.setRequestId("independent_request");return manager.start(target,request);});return 200;}finally{actorUid.remove();}}));}
                assertTrue(distinctReady.await(5,TimeUnit.SECONDS));distinctStart.countDown();assertEquals(200,independent.get(0).get(10,TimeUnit.SECONDS));assertEquals(200,independent.get(1).get(10,TimeUnit.SECONDS));
                assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM contest_virtual_run",Integer.class));
            } finally {workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);}
        } finally {admin.execute("DROP DATABASE "+schema);assertEquals(original,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class));}
    }
}
