package top.hcode.hoj.manager.oj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_TRAINING_PLAN_TEST",matches="1")
class LearningTrainingPlanMySqlTest {
    interface Scenario { void run(LearningTrainingPlanFixture f) throws Exception; }
    void isolated(Scenario scenario) throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD"), schema="hoj_plan_it_"+UUID.randomUUID().toString().replace("-","");
        String prefix="jdbc:mysql://127.0.0.1:13306/", suffix="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        JdbcTemplate admin=new JdbcTemplate(new DriverManagerDataSource(prefix+"hoj"+suffix,"root",password));
        long before=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class); admin.execute("CREATE DATABASE "+schema);
        try { LearningTrainingPlanFixture f=new LearningTrainingPlanFixture(prefix+schema+suffix,"root",password); assertEquals("REPEATABLE-READ",f.jdbc.queryForObject("SELECT @@transaction_isolation",String.class)); scenario.run(f); }
        finally { assertTrue(schema.matches("hoj_plan_it_[a-f0-9]{32}")); admin.execute("DROP DATABASE "+schema); assertEquals(before,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class)); }
    }
    List<Object> pair(Supplier<?> first, Supplier<?> second) throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2); CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1);
        try { List<Future<?>> futures=new ArrayList<>(); for(Supplier<?> task:Arrays.asList(first,second))futures.add(workers.submit(()->{ready.countDown();start.await();try{return task.get();}catch(ObjectiveException e){return e.getStatus();}})); assertTrue(ready.await(5,TimeUnit.SECONDS)); start.countDown(); return Arrays.asList(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS)); }
        finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void concurrentOwnersAndSameNonceDoNotDeadlockOrDuplicateAndQuotaIsSerialized() throws Exception {
        isolated(f->{
            List<Object> independent=pair(()->f.manager.create(f.create("independent_alice")),()->{f.actor.set("bob");return f.manager.create(f.create("independent_bob"));});
            assertTrue(independent.stream().allMatch(value->value instanceof Map)); assertEquals(2,f.count());
            List<Object> same=pair(()->f.manager.create(f.create("same_nonce_request")),()->f.manager.create(f.create("same_nonce_request")));
            assertEquals(((Map<?,?>)same.get(0)).get("id"),((Map<?,?>)same.get(1)).get("id")); assertEquals(3,f.count());
            for(int i=0;i<17;i++)f.manager.create(f.create("fill_request_"+i)); // Alice has nineteen active plans.
            List<Object> boundary=pair(()->f.manager.create(f.create("boundary_request_1")),()->f.manager.create(f.create("boundary_request_2")));
            assertEquals(1,boundary.stream().filter(value->value instanceof Map).count()); assertEquals(1,boundary.stream().filter(value->Integer.valueOf(409).equals(value)).count());
            assertEquals(20,f.jdbc.queryForObject("SELECT COUNT(*) FROM learning_training_plan WHERE uid='alice'",Integer.class));
        });
    }
    @Test void anOuterRepeatableReadSnapshotCannotBypassTheRestoreQuota() throws Exception {
        isolated(f->{
            Map<String,Object> first=f.manager.create(f.create("archive_request"));
            Map<String,Object> archived=f.manager.update((String)first.get("id"),f.update(first,"ARCHIVED"));
            for(int i=0;i<19;i++)f.manager.create(f.create("nineteen_request_"+i));
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try { new TransactionTemplate(new DataSourceTransactionManager(f.source)).execute(status->{
                assertEquals(19,f.jdbc.queryForObject("SELECT COUNT(*) FROM learning_training_plan WHERE state='ACTIVE'",Integer.class));
                try { worker.submit(()->f.manager.create(f.create("twentieth_request"))).get(15,TimeUnit.SECONDS); } catch(Exception e){throw new IllegalStateException(e);}
                ObjectiveException error=assertThrows(ObjectiveException.class,()->f.manager.update((String)archived.get("id"),f.update(archived,"ACTIVE"))); assertEquals(409,error.getStatus()); return null;
            }); } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)); }
            assertEquals(20,f.jdbc.queryForObject("SELECT COUNT(*) FROM learning_training_plan WHERE state='ACTIVE'",Integer.class)); assertEquals("ARCHIVED",f.manager.detail((String)archived.get("id")).get("state"));
        });
    }
}
