package top.hcode.hoj.manager.oj.external;

import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.external.CodeforcesBrowserSubmitManager.BrowserEvent;
import top.hcode.hoj.manager.oj.external.CodeforcesBrowserSubmitManager.NewSubmission;
import top.hcode.hoj.mapper.JudgeMapper;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.validator.AccessValidator;

import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "HOJ_CF_BROWSER_MYSQL", matches = "hoj-cf-test-[a-z0-9]+")
class CodeforcesBrowserSubmitMySqlTest {
    private static final String OWNER = "cf-browser-test-owner";
    private static final String OTHER = "cf-browser-test-other";
    private static final String PROBLEM = "CF-999998A";
    private static final String CODE = "int main() { return 42; }\n";
    private static final String LANGUAGE = "GNU G++20 13.2 (64 bit, winlibs)";
    private static final String API_LANGUAGE = "C++20 (GCC 13-64)";
    private static final String CLAIM = "1111111111111111111111111111111111111111111111111111111111111111";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static DruidDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JudgeMapper judges;
    private CodeforcesBrowserSubmitManager manager;
    private String uid;
    private long accountId;
    private long pid;
    private long clock;

    @BeforeAll static void connect() throws Exception {
        dataSource = new DruidDataSource();
        dataSource.setUrl("jdbc:mysql://" + System.getenv("HOJ_CF_BROWSER_MYSQL")
                + ":3306/hoj?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        dataSource.setUsername("root");
        dataSource.setPassword("");
        dataSource.init();
        jdbc = new JdbcTemplate(dataSource);
        assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        SqlSessionFactory sessions = factory.getObject();
        sessions.getConfiguration().addMapper(JudgeMapper.class);
        judges = new SqlSessionTemplate(sessions).getMapper(JudgeMapper.class);
    }

    @AfterAll static void disconnect() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach void fixtures() {
        jdbc.update("DELETE FROM codeforces_browser_submit WHERE owner_uid IN (?,?)", OWNER, OTHER);
        jdbc.update("DELETE FROM problem WHERE problem_id=?", PROBLEM);
        jdbc.update("DELETE FROM external_account WHERE uid IN (?,?)", OWNER, OTHER);
        jdbc.update("INSERT IGNORE INTO user_info(uuid,username,password,status) VALUES(?,?,?,0),(?,?,?,0)",
                OWNER, OWNER, "fixture", OTHER, OTHER, "fixture");
        jdbc.update("INSERT INTO problem(problem_id,title,author,auth,is_remote) VALUES(?, 'Fixture', ?, 1, 1)", PROBLEM, OWNER);
        jdbc.update("INSERT INTO external_account(uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) "
                + "VALUES(?,'CODEFORCES','FixtureTester','fixturetester','fixturetester',NOW(3),NOW(3))", OWNER);
        pid = jdbc.queryForObject("SELECT id FROM problem WHERE problem_id=?", Long.class, PROBLEM);
        accountId = jdbc.queryForObject("SELECT id FROM external_account WHERE uid=?", Long.class, OWNER);
        uid = OWNER;
        clock = 1795737600123L;
        manager = new CodeforcesBrowserSubmitManager(jdbc, new DataSourceTransactionManager(dataSource), mock(AccessValidator.class),
                judges, mock(CodeforcesDataClient.class), mock(StringRedisTemplate.class)) {
            @Override protected AccountProfile actor() {
                AccountProfile actor = new AccountProfile();
                actor.setUid(uid);
                return actor;
            }
            @Override protected long now() { return clock; }
        };
    }

    @Test void retryAndConcurrentClaimsCannotSendTwice() throws Exception {
        NewSubmission input = input("task-fixture-same");
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            assertEquals(1, callers.invokeAll(java.util.Arrays.<Callable<String>>asList(() -> id(manager.create(input)),
                    () -> id(manager.create(input)))).stream().map(future -> {
                try { return future.get(); } catch (Exception failure) { throw new AssertionError(failure); }
            }).distinct().count());
            long id = Long.parseLong(id(manager.latest(PROBLEM)));
            Callable<String> claim = () -> {
                try { return (String) manager.claim(id, CLAIM).get("receipt"); }
                catch (ObjectiveException expired) { assertEquals(409, expired.getStatus()); return null; }
            };
            String receipt = null;
            for (Future<String> result : callers.invokeAll(java.util.Arrays.asList(claim, claim))) {
                String value = result.get();
                if (value != null) { assertNull(receipt); receipt = value; }
            }
            assertNotNull(receipt);
            String key = receipt;
            BrowserEvent wrong = sending();
            wrong.handle = "wrong-user";
            assertEquals(400, assertThrows(ObjectiveException.class, () -> manager.browserEvent(id, key, wrong)).getStatus());
            assertEquals("SENT", manager.browserEvent(id, key, sending()).get("state"));
            assertEquals(409, assertThrows(ObjectiveException.class, () -> manager.browserEvent(id, key, sending())).getStatus());
            uid = OTHER;
            assertEquals(404, assertThrows(ObjectiveException.class, () -> manager.status(id)).getStatus());
            assertEquals(403, assertThrows(ObjectiveException.class, () -> manager.create(input)).getStatus());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test void browserChecksKeepDistinctReasonsWithoutSending() {
        String[] reasons = {"SOURCE_ACCOUNT_UNREADABLE", "SOURCE_FORM_UNAVAILABLE", "SOURCE_HISTORY_UNAVAILABLE"};
        for (int i = 0; i < reasons.length; i++) {
            long id = start("task-fixture-pre-send-" + i);
            String receipt = (String) manager.claim(id, CLAIM).get("receipt");
            BrowserEvent failure = new BrowserEvent();
            failure.event = "failed";
            failure.errorCode = reasons[i];
            Map<String, Object> task = manager.browserEvent(id, receipt, failure);
            assertEquals("FAILED", task.get("state"));
            assertEquals(reasons[i], task.get("error"));
            assertNull(task.get("remoteId"));
        }
    }

    @Test void exactOfficialResultProjectsOnePracticeRowWithSourceAndMetrics() {
        long id = start("task-fixture-result");
        String receipt = authorize(id);
        assertEquals("VERIFYING", attach(id, receipt, 120L).get("state"));
        manager.acceptPublicResult(id, apiResult(121L, "OK", "A"));
        assertEquals("VERIFYING", manager.status(id).get("state"));
        assertEquals(0, countJudges());
        manager.acceptPublicResult(id, apiResult(120L, "TESTING", "A"));
        assertEquals("TESTING", manager.status(id).get("verdict"));
        manager.acceptPublicResult(id, apiResult(120L, "OK", "A"));
        manager.acceptPublicResult(id, apiResult(120L, "OK", "A"));
        Map<String, Object> completed = attach(id, receipt, 120L);
        assertEquals("COMPLETED", completed.get("state"));
        assertEquals("OK", completed.get("verdict"));
        assertNotNull(completed.get("submitId"));
        assertEquals(1, countJudges());
        assertEquals(CODE, jdbc.queryForObject("SELECT code FROM judge WHERE pid=?", String.class, pid));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM judge WHERE pid=?", Integer.class, pid));
        assertEquals(16, jdbc.queryForObject("SELECT memory FROM judge WHERE pid=?", Integer.class, pid));
        assertEquals("", jdbc.queryForObject("SELECT code FROM codeforces_browser_submit WHERE id=?", String.class, id));
    }

    @Test void oldCandidateAndOtherProblemDoNotProduceAResult() {
        long id = start("task-fixture-mismatch");
        String receipt = authorize(id);
        assertEquals(400, assertThrows(ObjectiveException.class, () -> attach(id, receipt, 100L)).getStatus());
        attach(id, receipt, 120L);
        manager.acceptPublicResult(id, apiResult(120L, "OK", "B"));
        assertEquals("RECEIPT_MISMATCH", manager.status(id).get("error"));
        assertEquals(0, countJudges());
        long second = start("task-fixture-repeat");
        String secondReceipt = authorize(second);
        assertEquals(409, assertThrows(ObjectiveException.class, () -> attach(second, secondReceipt, 120L)).getStatus());
    }

    @Test void existingManualSyncProjectionIsReusedForWrongAnswer() {
        long id = start("task-fixture-manual");
        String receipt = authorize(id);
        attach(id, receipt, 120L);
        jdbc.update("INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code,cid,cpid,judger,vjudge_submit_id) "
                        + "VALUES(?,?,?,?,?,7,'',0,0,'CodeforcesSync',120)",
                pid, PROBLEM, OWNER, OWNER, new Timestamp(clock));
        Long known = jdbc.queryForObject("SELECT submit_id FROM judge WHERE pid=?", Long.class, pid);
        jdbc.update("INSERT INTO codeforces_sync_projection(kind,scope_id,slot_id,external_id,submit_id) VALUES('PRACTICE',0,?,120,?)", pid, known);
        manager.acceptPublicResult(id, apiResult(120L, "WRONG_ANSWER", "A"));
        assertEquals(String.valueOf(known), manager.status(id).get("submitId"));
        assertEquals(-1, jdbc.queryForObject("SELECT status FROM judge WHERE pid=?", Integer.class, pid));
        assertEquals(1, countJudges());
    }

    @Test void resultForDifferentCppStandardIsRejected() {
        long id = start("task-fixture-language-mismatch");
        attach(id, authorize(id), 120L);
        JsonNode rows = apiResult(120L, "OK", "A");
        ((ObjectNode) rows.get(0)).put("programmingLanguage", "C++17 (GCC 7-32)");
        manager.acceptPublicResult(id, rows);
        assertEquals("RECEIPT_MISMATCH", manager.status(id).get("error"));
        assertEquals(0, countJudges());
    }

    private static String id(Map<String, Object> row) { return (String) row.get("id"); }
    private long start(String requestId) { return Long.parseLong(id(manager.create(input(requestId)))); }
    private int countJudges() { return jdbc.queryForObject("SELECT COUNT(*) FROM judge WHERE pid=?", Integer.class, pid); }

    private NewSubmission input(String requestId) {
        NewSubmission input = new NewSubmission();
        input.problem = PROBLEM;
        input.accountId = accountId;
        input.language = LANGUAGE;
        input.code = CODE;
        input.requestId = requestId;
        input.claim = CLAIM;
        return input;
    }

    private String authorize(long id) {
        String receipt = (String) manager.claim(id, CLAIM).get("receipt");
        manager.browserEvent(id, receipt, sending());
        return receipt;
    }

    private BrowserEvent sending() {
        BrowserEvent message = new BrowserEvent();
        message.event = "sending";
        message.handle = "FixtureTester";
        message.baselineId = 100L;
        message.language = LANGUAGE;
        return message;
    }

    private Map<String, Object> attach(long id, String receipt, long remoteId) {
        BrowserEvent message = new BrowserEvent();
        message.event = "receipt";
        message.remoteId = remoteId;
        message.codeHash = CodeforcesBrowserSubmitManager.hashCode(CODE);
        return manager.browserEvent(id, receipt, message);
    }

    private JsonNode apiResult(long id, String verdict, String index) {
        ObjectNode row = JSON.createObjectNode();
        row.put("id", id).put("creationTimeSeconds", clock / 1000 + 1).put("programmingLanguage", API_LANGUAGE).put("verdict", verdict)
                .put("timeConsumedMillis", 21).put("memoryConsumedBytes", 16384);
        row.putObject("problem").put("contestId", 999998).put("index", index);
        row.putObject("author").put("participantType", "PRACTICE").putArray("members").addObject().put("handle", "FixtureTester");
        return JSON.createArrayNode().add(row);
    }
}
