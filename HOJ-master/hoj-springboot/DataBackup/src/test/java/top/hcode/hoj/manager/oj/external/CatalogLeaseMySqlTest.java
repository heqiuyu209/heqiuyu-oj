package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Repeats finite callbacks in disposable MySQL schemas and waits on exact RECORD locks. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class CatalogLeaseMySqlTest extends CatalogLeaseTest {
    JdbcTemplate admin;String schema;boolean created;long mainProblems;
    @Override @BeforeEach void setup() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");assertNotNull(password);
        String base="jdbc:mysql://127.0.0.1:13306/",opts="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        admin=new JdbcTemplate(new DriverManagerDataSource(base+"hoj"+opts,"root",password));mainProblems=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class);
        schema="hoj_catalog_lease_it_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
        initialize(new DriverManagerDataSource(base+schema+opts,"root",password));assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
    }
    @AfterEach void cleanup(){if(created){assertTrue(schema.matches("hoj_catalog_lease_it_[0-9a-f]{32}"));admin.execute("DROP DATABASE "+schema);}if(admin!=null)assertEquals(mainProblems,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class));}
    void afterLocked(String action,boolean live,boolean scanLock) throws Exception {
        Map<String,Object> job=lease(action);Map<String,Object> before=snapshot(action);long until=deadline(job);
        String table=scanLock?"remote_nowcoder_scan":"remote_source_job";
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        ExecutorService worker=Executors.newSingleThreadExecutor();AtomicLong connection=new AtomicLong();CountDownLatch started=new CountDownLatch(1);AtomicReference<Future<?>> pending=new AtomicReference<>();
        try {
            tx.execute(held->{
                jdbc.queryForList("SELECT source_name FROM "+table+" WHERE source_name=? FOR UPDATE",source(action));
                pending.set(worker.submit(()->tx.execute(waiting->{connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));started.countDown();invoke(action,job);return null;})));
                try {
                    assertTrue(started.await(5,TimeUnit.SECONDS));long timeout=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
                    while(System.nanoTime()<timeout){
                        blocked=admin.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.threads t ON t.THREAD_ID=w.REQUESTING_THREAD_ID JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=? AND l.LOCK_TYPE='RECORD'",Integer.class,connection.get(),schema,table)>0;
                        if(blocked)break;Thread.sleep(50);
                    }
                    assertTrue(blocked,"Actual callback connection must wait for the exact target table record");
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                clock.set(until-(live?1:0));return null;
            });pending.get().get(10,TimeUnit.SECONDS);
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        if(live){assertNotEquals(before,snapshot(action));assertNull(jdbc.queryForMap("SELECT * FROM remote_source_job WHERE source_name=?",source(action)).get("lease_token"));}
        else{assertEquals(before,snapshot(action));assertFreshWorkerRecovers(action,job);}
    }
    @ParameterizedTest @MethodSource("callbacks")
    void deadlineWhileWaitingForJobCannotChangeCache(String action,boolean live) throws Exception {afterLocked(action,live,false);}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void nowCoderScanLockAlsoRequiresALiveLease(boolean live) throws Exception {afterLocked("NC_APPLY",live,true);}
}
