package top.hcode.hoj.manager.oj.external;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Repeats publication cases and competing leases against isolated MySQL schemas. */
@EnabledIfEnvironmentVariable(named = "HOJ_LOCAL_REMOTE_TASK_TEST", matches = "1")
class NowCoderPracticeMySqlTest extends NowCoderPracticeManagerTest {
    String schema; JdbcTemplate admin; long original; DriverManagerDataSource mysqlSource;
    @Override @BeforeEach void setup() throws Exception {
        String password = System.getenv("HOJ_LOCAL_MYSQL_PASSWORD"); assertNotNull(password);
        String base = "jdbc:mysql://127.0.0.1:13306/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "hoj" + options, "root", password));
        original = admin.queryForObject("SELECT COUNT(*) FROM problem", Long.class);
        schema = "hoj_nc_practice_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        mysqlSource = new DriverManagerDataSource(base + schema + options, "root", password);
        initialize(mysqlSource);
        assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
    }
    @AfterEach void cleanup() {
        if (admin != null && schema != null) {
            assertTrue(schema.matches("hoj_nc_practice_it_[0-9a-f]{32}"));
            admin.execute("DROP DATABASE " + schema);
            assertEquals(original, admin.queryForObject("SELECT COUNT(*) FROM problem", Long.class));
        }
    }
    @Test void simultaneousFirstClaimsAndExpiredLeasesUseCurrentReads() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Map<String, Object>> claim = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return manager.claim(); };
            Future<Map<String, Object>> a = workers.submit(claim), b = workers.submit(claim); start.countDown();
            Map<String, Object> first = a.get(10, TimeUnit.SECONDS), second = b.get(10, TimeUnit.SECONDS);
            assertEquals(1, (first == null ? 0 : 1) + (second == null ? 0 : 1));
            Map<String, Object> old = first == null ? second : first;
            clock += 90001; Map<String, Object> current = manager.claim(); assertNotNull(current);
            manager.apply(old, profile(false)); manager.failed(old, "ignored");
            assertNull(jdbc.queryForObject("SELECT source_visible FROM external_nowcoder_practice_job", Boolean.class));
            manager.apply(current, profile(true)); assertEquals("COUNT_ALL", stage());
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void duplicatePageCallbackCannotPublishOrDuplicateRows() throws Exception {
        throughCounts(1, 0); Map<String, Object> lease = manager.claim();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Void> apply = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); manager.apply(lease, page(false, record("102", "201", false, null))); return null; };
            Future<Void> a = workers.submit(apply), b = workers.submit(apply); start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            assertEquals(1, rows()); assertEquals("VERIFY_ALL", stage()); assertEquals(false, manager.view(1, true).get("available"));
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void inheritedRepeatableReadSnapshotCannotReExposeRevokedPublicRecords() {
        complete();
        TransactionTemplate oldSnapshot = new TransactionTemplate(new DataSourceTransactionManager(mysqlSource));
        oldSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        oldSnapshot.execute(status -> {
            assertNotNull(jdbc.queryForObject("SELECT published_token FROM external_nowcoder_practice_job", String.class));
            // Independent thread/connection commits the known source restriction.
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                worker.submit(() -> { manager.request(1); step(profile(false)); }).get(10, TimeUnit.SECONDS);
                // This connection demonstrably retains its older MVCC snapshot.
                assertNotNull(jdbc.queryForObject("SELECT published_token FROM external_nowcoder_practice_job", String.class));
                Map<String, Object> view = manager.view(1, false);
                assertEquals(false, view.get("available")); assertEquals(true, view.get("restricted"));
                assertFalse(view.containsKey("totalPapers"));
            } catch (Exception error) { throw new AssertionError(error); }
            finally { worker.shutdownNow(); }
            return null;
        });
    }

    @ParameterizedTest
    @CsvSource({"apply,90000,false","failed,90000,false","defer,90000,false",
            "apply,89999,true","failed,89999,true","defer,89999,true"})
    void expiredCallbackAfterJobLock(String action,long elapsed,boolean live) throws Exception {
        Map<String,Object> lease=manager.claim();assertNotNull(lease);
        Map<String,Object> before=jdbc.queryForMap("SELECT * FROM external_nowcoder_practice_job WHERE account_id=1");
        FetchLeaseLockTestSupport.whileJobLocked(jdbc,admin,"external_nowcoder_practice_job",()->{
            if("apply".equals(action))manager.apply(lease,profile(true));
            else if("failed".equals(action))manager.failed(lease,"late callback");
            else manager.defer(lease);
        },()->clock+=elapsed);
        Map<String,Object> after=jdbc.queryForMap("SELECT * FROM external_nowcoder_practice_job WHERE account_id=1");
        if(live){assertNotEquals(before,after);assertNull(after.get("lease_token"));}
        else {
            assertEquals(before,after);
            Map<String,Object> fresh=manager.claim();assertNotNull(fresh);
            assertNotEquals(lease.get("lease_token"),fresh.get("lease_token"));
            manager.apply(fresh,profile(true));
            assertNull(jdbc.queryForMap("SELECT * FROM external_nowcoder_practice_job WHERE account_id=1").get("lease_token"));
        }
    }
}
