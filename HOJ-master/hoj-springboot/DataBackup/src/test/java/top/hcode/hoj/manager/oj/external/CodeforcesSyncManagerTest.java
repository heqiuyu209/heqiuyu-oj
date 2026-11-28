package top.hcode.hoj.manager.oj.external;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.BeforeDispatchInitManager;
import top.hcode.hoj.manager.oj.PaperManager;
import top.hcode.hoj.mapper.JudgeMapper;
import top.hcode.hoj.pojo.dto.CodeforcesSyncDTO;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.utils.RedisUtils;
import top.hcode.hoj.validator.AccessValidator;
import top.hcode.hoj.validator.GroupValidator;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeforcesSyncManagerTest {
    private static final Instant START = Instant.parse("2026-11-26T04:00:00Z");
    private JdbcTemplate jdbc;
    private CodeforcesSyncManager sync;
    private PaperManager papers;
    private RedisTemplate<String, Object> redisTemplate;
    private RedisTemplate<?, ?> previousRedisTemplate;
    private JudgeMapper judges;

    @BeforeEach void setup() {
        String url = System.getenv("HOJ_CF_SYNC_TEST_URL");
        boolean mysql = url != null;
        if (mysql) assertTrue(url.matches("jdbc:mysql://[^/]+/hoj_cf_sync_test\\?.+"), "Use an isolated fixture database");
        DriverManagerDataSource db = mysql
                ? new DriverManagerDataSource(url, "root", System.getenv("HOJ_CF_SYNC_TEST_PASSWORD"))
                : new DriverManagerDataSource("jdbc:h2:mem:cf_sync;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(db);
        if (mysql) {
            for (String table : Arrays.asList("codeforces_sync_projection", "codeforces_sync_cursor", "codeforces_sync_scope",
                    "training_record", "training_register", "training_problem", "training", "external_submission",
                    "external_sync_job", "external_account", "contest_record", "contest_problem", "contest_paper",
                    "contest_virtual_run", "contest", "paper_run", "judge", "problem", "user_info"))
                jdbc.execute("DROP TABLE IF EXISTS " + table);
        } else jdbc.execute("DROP ALL OBJECTS");
        schema();
        jdbc.update("INSERT INTO user_info VALUES('u','alice','Alice',0),('v','bob','Bob',0)");
        jdbc.update("INSERT INTO problem VALUES(1,'CF-4A',1,1,NULL,100),(2,'CF-5B',1,1,NULL,100),(3,'CF-6C',1,2,NULL,100)");
        jdbc.update("INSERT INTO external_account VALUES(1,'u','CODEFORCES','alice','alice',?,?),(2,'v','CODEFORCES','bob',NULL,?,?)",
                time(100), time(95), time(100), time(95));
        jdbc.update("INSERT INTO external_sync_job(account_id,stage) VALUES(1,'DONE'),(2,'DONE')");
        jdbc.update("INSERT INTO contest VALUES(10,?,?,0,NULL),(11,?,?,1,NULL)", time(0), time(60), time(0), time(60));
        jdbc.update("INSERT INTO contest_problem VALUES(42,10,1,'A'),(43,11,2,'A')");
        jdbc.update("INSERT INTO training VALUES(5,NULL,'Private',1),(6,NULL,'Private',1)");
        jdbc.update("INSERT INTO training_problem VALUES(30,5,1),(31,6,1)");
        jdbc.update("INSERT INTO training_register VALUES(5,'u',1),(6,'u',0)");

        judges = mock(JudgeMapper.class);
        when(judges.insert(any(Judge.class))).thenAnswer(invocation -> {
            Judge record = invocation.getArgument(0);
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO judge(pid,uid,cid,cpid,gid,paper_run_id,paper_snapshot_id,judger,vjudge_submit_id,status,score,submit_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                Object[] values = {record.getPid(),record.getUid(),record.getCid(),record.getCpid(),record.getGid(),record.getPaperRunId(),
                        record.getPaperSnapshotId(),record.getJudger(),record.getVjudgeSubmitId(),record.getStatus(),record.getScore(),
                        new Timestamp(record.getSubmitTime().getTime())};
                for (int i = 0; i < values.length; i++) insert.setObject(i + 1, values[i]);
                return insert;
            }, key);
            record.setSubmitId(Objects.requireNonNull(key.getKey()).longValue());
            return 1;
        });
        papers = mock(PaperManager.class);
        RedisUtils redis = new RedisUtils();
        previousRedisTemplate = (RedisTemplate<?, ?>) ReflectionTestUtils.getField(RedisUtils.class, "redisTemplate");
        redisTemplate = mock(RedisTemplate.class);
        redis.setRedisTemplate(redisTemplate);
        ExternalAccountManager accounts = mock(ExternalAccountManager.class);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("uid", "u");
        snapshot.put("accounts", Collections.emptyList());
        when(accounts.mine()).thenReturn(snapshot);
        sync = new CodeforcesSyncManager(jdbc, new DataSourceTransactionManager(db), judges, accounts,
                new CodeforcesPracticeRecords(jdbc), mock(BeforeDispatchInitManager.class), papers,
                mock(GroupValidator.class), mock(AccessValidator.class), redis);
    }

    @AfterEach void cleanup() {
        ThreadContext.remove();
        new RedisUtils().setRedisTemplate(previousRedisTemplate);
    }

    private void schema() {
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,username VARCHAR(32),realname VARCHAR(32),status INT)");
        jdbc.execute("CREATE TABLE external_account(id BIGINT PRIMARY KEY,uid VARCHAR(32),platform VARCHAR(24),handle_key VARCHAR(64),verified_identity VARCHAR(64),last_success_at DATETIME(3),last_requested_at DATETIME(3))");
        jdbc.execute("CREATE TABLE external_sync_job(account_id BIGINT PRIMARY KEY,stage VARCHAR(24),next_run_at DATETIME(3))");
        jdbc.execute("CREATE TABLE external_submission(account_id BIGINT,external_id BIGINT,problem_key VARCHAR(120),verdict VARCHAR(32),submitted_at BIGINT,individual BOOLEAN,PRIMARY KEY(account_id,external_id))");
        jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,problem_id VARCHAR(100),is_remote BOOLEAN,auth INT,gid BIGINT,io_score INT)");
        jdbc.execute("CREATE TABLE judge(submit_id BIGINT AUTO_INCREMENT PRIMARY KEY,pid BIGINT,uid VARCHAR(32),cid BIGINT,cpid BIGINT,gid BIGINT,paper_run_id BIGINT,paper_snapshot_id BIGINT,judger VARCHAR(20),vjudge_submit_id BIGINT,status INT,score INT,submit_time DATETIME)");
        jdbc.execute("CREATE TABLE contest(id BIGINT PRIMARY KEY,start_time DATETIME,end_time DATETIME,type INT,gid BIGINT)");
        jdbc.execute("CREATE TABLE contest_problem(id BIGINT PRIMARY KEY,cid BIGINT,pid BIGINT,display_id VARCHAR(255))");
        jdbc.execute("CREATE TABLE contest_record(submit_id BIGINT PRIMARY KEY,cid BIGINT,pid BIGINT,cpid BIGINT,uid VARCHAR(32),username VARCHAR(32),realname VARCHAR(32),display_id VARCHAR(255),submit_time DATETIME,time BIGINT,status INT,score INT)");
        jdbc.execute("CREATE TABLE contest_paper(contest_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE contest_virtual_run(id BIGINT PRIMARY KEY,ends_at DATETIME(3))");
        jdbc.execute("CREATE TABLE paper_run(id BIGINT PRIMARY KEY,contest_id BIGINT,virtual_run_id BIGINT,state VARCHAR(24),gmt_modified DATETIME(3))");
        jdbc.execute("CREATE TABLE training(id BIGINT PRIMARY KEY,gid BIGINT,auth VARCHAR(20),status BOOLEAN)");
        jdbc.execute("CREATE TABLE training_problem(id BIGINT PRIMARY KEY,tid BIGINT,pid BIGINT)");
        jdbc.execute("CREATE TABLE training_register(tid BIGINT,uid VARCHAR(32),status INT)");
        jdbc.execute("CREATE TABLE training_record(tid BIGINT,tpid BIGINT,pid BIGINT,uid VARCHAR(32),submit_id BIGINT)");
        jdbc.execute("CREATE TABLE codeforces_sync_scope(kind VARCHAR(12),scope_id BIGINT,uid VARCHAR(32),account_id BIGINT,handle_key VARCHAR(64),PRIMARY KEY(kind,scope_id,uid))");
        jdbc.execute("CREATE TABLE codeforces_sync_cursor(account_id BIGINT PRIMARY KEY,synced_at DATETIME(3))");
        jdbc.execute("CREATE TABLE codeforces_sync_projection(kind VARCHAR(12),scope_id BIGINT,slot_id BIGINT,external_id BIGINT,submit_id BIGINT UNIQUE,PRIMARY KEY(kind,scope_id,slot_id,external_id))");
    }

    @Test void projectsOnlyEligibleReceiptsAndUpdatesRanksAndPracticeInPlace() {
        scope("CONTEST", 10); scope("CONTEST", 11); scope("PAPER", 20);
        Problem problem = new Problem().setId(1L).setProblemId("CF-4A");
        when(papers.codeforcesSyncTargets(20, "u")).thenReturn(Collections.singletonList(
                CodeforcesSyncManager.Target.paper(20, 400, problem, time(10).getTime(), time(60).getTime())));
        receipt(1, 101, "4:A", -1, "OK", true);
        receipt(1, 102, "4:A", 0, "WRONG_ANSWER", true);
        receipt(1, 103, "4:A", 10, "OK", true);
        receipt(1, 104, "4:A", 59, "TESTING", true);
        receipt(1, 105, "4:A", 60, "OK", true);
        receipt(1, 106, "4:A", 31, "OK", false);
        receipt(1, 107, "5:B", 20, "OK", true);
        receipt(1, 108, "6:C", 20, "OK", true);
        receipt(2, 109, "4:A", 20, "OK", true);
        sync.projectReadyAccounts();

        assertEquals(Arrays.asList(101L, 102L, 103L, 104L, 105L, 107L), ids("cid=0 AND paper_run_id IS NULL"));
        assertEquals(Arrays.asList(102L, 103L, 104L), ids("cid=10"));
        assertEquals(Collections.singletonList(107L), ids("cid=11"));
        assertEquals(Arrays.asList(103L, 104L), ids("paper_run_id=20 AND paper_snapshot_id=400"));
        assertEquals(Arrays.asList(-1, 1), jdbc.queryForList("SELECT status FROM contest_record WHERE cid=10 AND status IS NOT NULL ORDER BY time", Integer.class));
        assertEquals(Arrays.asList(0L, 10L, 59L), jdbc.queryForList("SELECT time FROM contest_record WHERE cid=10 ORDER BY time", Long.class));
        assertEquals(100, jdbc.queryForObject("SELECT score FROM contest_record WHERE cid=11", Integer.class));
        assertEquals(5, count("training_record WHERE tid=5"));
        assertEquals(0, count("training_record WHERE tid=6"));
        assertEquals(time(10), jdbc.queryForObject("SELECT submit_time FROM judge WHERE vjudge_submit_id=103 AND cid=10", Timestamp.class));
        verify(redisTemplate).delete(Arrays.asList("contest_rank_cal_result_cache_10", "contest_rank_cal_result_cache_contains_after_10"));

        long localId = jdbc.queryForObject("SELECT submit_id FROM judge WHERE vjudge_submit_id=104 AND cid=10", Long.class);
        jdbc.update("UPDATE external_submission SET verdict='OK' WHERE external_id=104");
        jdbc.update("UPDATE external_account SET last_success_at=? WHERE id=1", time(110));
        sync.projectReadyAccounts();
        sync.projectReadyAccounts();
        assertEquals(12, count("judge"));
        assertEquals(5, count("training_record"));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM contest_record WHERE submit_id=?", Integer.class, localId));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM judge WHERE vjudge_submit_id=104 AND paper_run_id=20", Integer.class));
    }

    @Test void concurrentWorkersGenerateOnlyOneProjectionFromACompletedScan() throws Exception {
        receipt(1, 201, "4:A", 5, "OK", true);
        jdbc.update("UPDATE external_sync_job SET stage='VERIFY_STATUS' WHERE account_id=1");
        sync.projectReadyAccounts();
        assertEquals(0, count("judge"));
        jdbc.update("UPDATE external_sync_job SET stage='DONE' WHERE account_id=1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            Callable<Void> worker = () -> { start.await(5, TimeUnit.SECONDS); sync.projectReadyAccounts(); return null; };
            Future<Void> first = pool.submit(worker), second = pool.submit(worker);
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(1, count("judge"));
        assertEquals(1, count("codeforces_sync_projection"));
        assertEquals(1, count("training_record"));
        assertEquals(1, count("codeforces_sync_cursor"));
    }

    @Test void requestCannotChangeTheVerifiedOwnerOrSwitchAccountsWithinAScope() {
        AccountProfile actor = new AccountProfile(); actor.setUid("u");
        Subject subject = mock(Subject.class);
        when(subject.getPrincipal()).thenReturn(actor);
        ThreadContext.bind(subject);
        CodeforcesSyncDTO request = new CodeforcesSyncDTO();
        request.setProblem("CF-4A"); request.setRunId(25L); request.setItemKey("PROGRAMMING:1");
        assertEquals(404, assertThrows(ObjectiveException.class, () -> sync.request(2, request)).getStatus());
        sync.request(1, request);
        verify(papers, times(2)).requireCodeforcesSyncItem(25L, "PROGRAMMING:1", "CF-4A");
        assertEquals(1, count("codeforces_sync_scope"));
        jdbc.update("INSERT INTO external_account VALUES(3,'u','CODEFORCES','alice2','alice2',?,?)", time(100), time(95));
        jdbc.update("INSERT INTO external_sync_job(account_id,stage) VALUES(3,'DONE')");
        assertEquals(409, assertThrows(ObjectiveException.class, () -> sync.request(3, request)).getStatus());
    }

    @Test void sameInstantInDifferentZonesProducesTheSameSortAndAdmissionBounds() {
        ZonedDateTime shanghai = START.atZone(ZoneId.of("Asia/Shanghai"));
        ZonedDateTime newYork = START.atZone(ZoneId.of("America/New_York"));
        assertNotEquals(shanghai.toLocalDate(), newYork.toLocalDate());
        long sameStart = shanghai.toInstant().toEpochMilli();
        assertEquals(CodeforcesSyncManager.firstSecond(sameStart), CodeforcesSyncManager.firstSecond(newYork.toInstant().toEpochMilli()));
        assertEquals(START.getEpochSecond(), CodeforcesSyncManager.firstSecond(sameStart));
        assertEquals(START.getEpochSecond() + 1, CodeforcesSyncManager.firstSecond(sameStart + 1));
    }

    private static Timestamp time(long seconds) { return Timestamp.from(START.plusSeconds(seconds)); }
    private void scope(String kind, long id) { jdbc.update("INSERT INTO codeforces_sync_scope VALUES(?,?,'u',1,'alice')", kind, id); }
    private void receipt(long account, long id, String problem, long seconds, String verdict, boolean individual) {
        jdbc.update("INSERT INTO external_submission VALUES(?,?,?,?,?,?)", account, id, "cf:contest:" + problem,
                verdict, START.getEpochSecond() + seconds, individual);
    }
    private List<Long> ids(String where) { return jdbc.queryForList("SELECT vjudge_submit_id FROM judge WHERE " + where + " ORDER BY vjudge_submit_id", Long.class); }
    private int count(String from) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + from, Integer.class); }
}
