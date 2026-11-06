package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Repeats publication/rollback cases on MySQL and checks competing leases in an isolated schema. */
@EnabledIfEnvironmentVariable(named="HOJ_LOCAL_REMOTE_TASK_TEST",matches="1")
class NowCoderContextMySqlTest extends NowCoderContextManagerTest {
    String schema;JdbcTemplate admin;long original;
    @Override @BeforeEach void setup() throws Exception {
        String password=System.getenv("HOJ_LOCAL_MYSQL_PASSWORD");assertNotNull(password);
        String base="jdbc:mysql://127.0.0.1:13306/";
        String options="?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        admin=new JdbcTemplate(new DriverManagerDataSource(base+"hoj"+options,"root",password));
        original=admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class);
        schema="hoj_nc_context_it_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+schema+" DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        initialize(new DriverManagerDataSource(base+schema+options,"root",password));
        assertEquals("REPEATABLE-READ",jdbc.queryForObject("SELECT @@transaction_isolation",String.class));
    }
    @AfterEach void cleanup(){
        if(admin!=null && schema!=null){assertTrue(schema.matches("hoj_nc_context_it_[0-9a-f]{32}"));admin.execute("DROP DATABASE "+schema);assertEquals(original,admin.queryForObject("SELECT COUNT(*) FROM problem",Long.class));}
    }
    @Test void competingFirstClaimsAndExpiredLeaseUseCurrentReads() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            CountDownLatch start=new CountDownLatch(1);
            Callable<Map<String,Object>> claim=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return contexts.claim();};
            Future<Map<String,Object>> a=workers.submit(claim),b=workers.submit(claim);start.countDown();
            Map<String,Object> first=a.get(10,TimeUnit.SECONDS),second=b.get(10,TimeUnit.SECONDS);
            assertEquals(1,(first==null?0:1)+(second==null?0:1));Map<String,Object> old=first==null?second:first;
            clock+=90001;Map<String,Object> current=contexts.claim();assertNotNull(current);
            contexts.apply(old,listing(false,false));contexts.failed(old,"ignored private response");assertEquals(0,count("external_nowcoder_contest"));
            contexts.apply(current,listing(false,false));assertEquals("VERIFY_LIST",stage());assertEquals(1,count("external_nowcoder_contest"));
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void duplicatePublicationOfOneLeaseWritesOnlyOneStatusPage() throws Exception {
        startDetails();Map<String,Object> lease=contexts.claim();ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            CountDownLatch start=new CountDownLatch(1);
            Callable<Void> apply=()->{assertTrue(start.await(5,TimeUnit.SECONDS));contexts.apply(lease,status());return null;};
            Future<Void> a=workers.submit(apply),b=workers.submit(apply);start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertEquals(1,count("external_nowcoder_contest_submission"));assertEquals("VERIFY_STATUS",stage());assertEquals(false,contexts.view(1,true).get("available"));
            step(status());assertEquals(true,contexts.view(1,true).get("available"));
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @ParameterizedTest
    @CsvSource({"apply,90000,false","failed,90000,false","defer,90000,false",
            "apply,89999,true","failed,89999,true","defer,89999,true"})
    void expiredCallbackAfterJobLock(String action,long elapsed,boolean live) throws Exception {
        Map<String,Object> lease=contexts.claim();assertNotNull(lease);
        Map<String,Object> before=jdbc.queryForMap("SELECT * FROM external_nowcoder_context_job WHERE account_id=1");
        FetchLeaseLockTestSupport.whileJobLocked(jdbc,admin,"external_nowcoder_context_job",()->{
            if("apply".equals(action))contexts.apply(lease,listing(false,false));
            else if("failed".equals(action))contexts.failed(lease,"late callback");
            else contexts.defer(lease);
        },()->clock+=elapsed);
        Map<String,Object> after=jdbc.queryForMap("SELECT * FROM external_nowcoder_context_job WHERE account_id=1");
        if(live){assertNotEquals(before,after);assertNull(after.get("lease_token"));}
        else {
            assertEquals(before,after);
            Map<String,Object> fresh=contexts.claim();assertNotNull(fresh);
            assertNotEquals(lease.get("lease_token"),fresh.get("lease_token"));
            contexts.apply(fresh,listing(false,false));
            assertNull(jdbc.queryForMap("SELECT * FROM external_nowcoder_context_job WHERE account_id=1").get("lease_token"));
        }
    }
}
