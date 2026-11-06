package top.hcode.hoj.manager.oj.ai;

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
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in disposable MySQL schemas; observes the exact callback RECORD lock. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class VideoLeaseMySqlTest extends VideoLeaseTest {
    JdbcTemplate admin;String schema;boolean created;long mainDocuments;
    @Override @BeforeEach void setup() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");assertNotNull(password);
        String base="jdbc:mysql://127.0.0.1:13306/",opts="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        admin=new JdbcTemplate(new DriverManagerDataSource(base+"hoj"+opts,"root",password));mainDocuments=admin.queryForObject("SELECT COUNT(*) FROM knowledge_document",Long.class);
        schema="hoj_video_lease_it_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");created=true;
        initialize(new DriverManagerDataSource(base+schema+opts,"root",password));assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
    }
    @AfterEach void cleanup(){if(created){assertTrue(schema.matches("hoj_video_lease_it_[0-9a-f]{32}"));admin.execute("DROP DATABASE "+schema);}if(admin!=null)assertEquals(mainDocuments,admin.queryForObject("SELECT COUNT(*) FROM knowledge_document",Long.class));}
    static Stream<Arguments> locks(){return cases().flatMap(a->Stream.of("user_info","role").map(table->Arguments.of(a.get()[0],a.get()[1],a.get()[2],table)));}
    @ParameterizedTest @MethodSource("locks")
    void expiryWhileWaitingForAdminRecords(String stage,boolean allowed,boolean live,String table) throws Exception {
        Map<String,Object> job=prepare(stage),before=snapshot(job);long cutoff=deadline(job);
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));ExecutorService workers=Executors.newSingleThreadExecutor();
        AtomicLong connection=new AtomicLong();CountDownLatch started=new CountDownLatch(1);AtomicReference<Future<?>> pending=new AtomicReference<>();
        try{
            tx.execute(held->{
                jdbc.queryForList("SELECT * FROM "+table+" WHERE "+("role".equals(table)?"id=1":"uuid='alice'")+" FOR UPDATE");
                pending.set(workers.submit(()->tx.execute(waiting->{connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));started.countDown();invoke(stage,job);return null;})));
                try{
                    assertTrue(started.await(5,TimeUnit.SECONDS));long timeout=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
                    while(System.nanoTime()<timeout){blocked=admin.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.threads t ON t.THREAD_ID=w.REQUESTING_THREAD_ID JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=? AND l.LOCK_TYPE='RECORD'",Integer.class,connection.get(),schema,table)>0;if(blocked)break;Thread.sleep(50);}
                    assertTrue(blocked,"Exact callback connection must wait for target admin record");
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                if(!allowed)jdbc.update("role".equals(table)?"UPDATE role SET role='normal' WHERE id=1":"UPDATE user_info SET status=1 WHERE uuid='alice'");
                clock.set(cutoff-(live?1:0));return null;
            });pending.get().get(10,TimeUnit.SECONDS);assertOutcome(stage,allowed,live,job,before);
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
