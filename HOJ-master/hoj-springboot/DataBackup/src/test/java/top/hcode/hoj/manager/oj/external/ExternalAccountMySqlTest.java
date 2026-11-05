package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Production DDL and real row locks, isolated from the application's schema. No source requests. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class ExternalAccountMySqlTest extends ExternalAccountManagerTest {
    String schema;JdbcTemplate admin;long original;boolean created;
    @Override @BeforeEach void setup() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");assertNotNull(password);
        String base="jdbc:mysql://127.0.0.1:13306/",options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        admin=new JdbcTemplate(new DriverManagerDataSource(base+"hoj"+options,"root",password));
        original=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class);
        schema="hoj_cf_pages_it_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema+" DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
        initialize(new DriverManagerDataSource(base+schema+options,"root",password));
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
        assertEquals("InnoDB",jdbc.queryForObject("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='external_sync_job'",String.class));
    }
    @AfterEach void cleanup(){
        if(created){assertTrue(schema.matches("hoj_cf_pages_it_[a-f0-9]{32}"));admin.execute("DROP DATABASE "+schema);assertEquals(original,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class));}
    }
    @Test void simultaneousClaimsAndExpiredTakeoverFenceAllOldCallbacks() throws Exception {
        link();ready("");ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            CountDownLatch go=new CountDownLatch(1);Callable<Map<String,Object>> claim=()->{assertTrue(go.await(5,TimeUnit.SECONDS));return manager.claim();};
            Future<Map<String,Object>> a=workers.submit(claim),b=workers.submit(claim);go.countDown();
            Map<String,Object> first=a.get(10,TimeUnit.SECONDS),second=b.get(10,TimeUnit.SECONDS);assertEquals(1,(first==null?0:1)+(second==null?0:1));
            Map<String,Object> old=first==null?second:first;clock+=90001;Map<String,Object> current=manager.claim();assertNotNull(current);
            Map<String,Object> state=jdbc.queryForMap("SELECT * FROM external_sync_job");
            manager.apply(old,publicPage(old,10));manager.failed(old,"old failure");manager.defer(old);assertEquals(state,jdbc.queryForMap("SELECT * FROM external_sync_job"));
            manager.apply(current,publicPage(current,10));assertEquals("VERIFY_STATUS",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void duplicateConcurrentApplyCommitsOnlyOnePageAndOneCursorAdvance() throws Exception {
        long id=link();ready("");Map<String,Object> job=manager.claim();ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            CountDownLatch go=new CountDownLatch(1);Callable<Void> apply=()->{assertTrue(go.await(5,TimeUnit.SECONDS));manager.apply(job,publicPage(job,1000));return null;};
            Future<Void> a=workers.submit(apply),b=workers.submit(apply);go.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertEquals(500,jdbc.queryForObject("SELECT from_index FROM external_sync_job",Integer.class));
            assertEquals(500,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=?",Integer.class,id));
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    void blockedOldCallbackCannotSurviveOwnerMutation(boolean unlink) throws Exception {
        long id=link();Map<String,Object> old=manager.claim();clock+=60001;
        ExecutorService worker=Executors.newSingleThreadExecutor();AtomicLong connectionId=new AtomicLong();CountDownLatch started=new CountDownLatch(1);
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));List<Future<?>> pending=new ArrayList<>();
        try{
            tx.execute(status->{
                jdbc.queryForList("SELECT id FROM external_account WHERE id=? FOR UPDATE",id);
                pending.add(worker.submit(()->tx.execute(other->{connectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));started.countDown();manager.apply(old,profile(""));return null;})));
                try{
                    assertTrue(started.await(5,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                    while(System.nanoTime()<deadline){
                        waiting=admin.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=? AND trx_state='LOCK WAIT'",Integer.class,connectionId.get())==1;
                        if(waiting)break;Thread.sleep(25);
                    }
                    assertTrue(waiting,"The stale apply must actually wait for the owner row lock");
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                if(unlink)manager.unlink(id);else manager.requestSync(id,false);return null;
            });
            pending.get(0).get(10,TimeUnit.SECONDS);manager.failed(old,"late failure");manager.defer(old);
            if(unlink){assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM external_sync_job",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM external_account",Integer.class));}
            else{assertEquals("PROFILE",jdbc.queryForObject("SELECT stage FROM external_sync_job",String.class));assertNull(jdbc.queryForObject("SELECT rating FROM external_account",Integer.class));assertNotEquals(old.get("scan_token"),jdbc.queryForObject("SELECT scan_token FROM external_sync_job",String.class));}
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void ownerResetWinsAgainstAStaleCallbackBlockedOnTheActualRowLock() throws Exception {blockedOldCallbackCannotSurviveOwnerMutation(false);}
    @Test void unlinkWinsAgainstAStaleCallbackBlockedOnTheActualRowLock() throws Exception {blockedOldCallbackCannotSurviveOwnerMutation(true);}
    @Test void additiveMigrationCanBeRepeatedWithoutResettingAnExistingCursorOrCachedEvidence() throws Exception {
        long id=link();ready("");Map<String,Object> first=manager.claim();manager.apply(first,publicPage(first,1000));
        jdbc.execute("ALTER TABLE external_sync_job DROP COLUMN cf_head_hash");String scan=jdbc.queryForObject("SELECT scan_token FROM external_sync_job",String.class);
        for(int n=0;n<2;n++)try(Connection connection=jdbc.getDataSource().getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("external-cf-pagination.sql"));}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='external_sync_job' AND column_name='cf_head_hash'",Integer.class));
        assertEquals(500,jdbc.queryForObject("SELECT from_index FROM external_sync_job",Integer.class));assertEquals(scan,jdbc.queryForObject("SELECT scan_token FROM external_sync_job",String.class));
        assertEquals(500,jdbc.queryForObject("SELECT COUNT(*) FROM external_submission WHERE account_id=?",Integer.class,id));
        assertEquals("PROFILE",manager.claim().get("stage"));assertNotEquals(scan,jdbc.queryForObject("SELECT scan_token FROM external_sync_job",String.class));
    }
}
