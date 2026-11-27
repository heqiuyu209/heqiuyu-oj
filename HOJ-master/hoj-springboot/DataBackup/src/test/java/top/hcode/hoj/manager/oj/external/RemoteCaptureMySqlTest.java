package top.hcode.hoj.manager.oj.external;

import com.alibaba.druid.pool.DruidDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.admin.problem.RemoteProblemManager;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.crawler.problem.ProblemStrategy;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "HOJ_CAPTURE_TEST_CONTAINER", matches = "hoj-capture-mysql-[a-z0-9]+")
class RemoteCaptureMySqlTest {
    private static final String CF_KEY = "cf:contest:4:A";
    private static final String CF_URL = "https://codeforces.com/problemset/problem/4/A";
    private static final String AT_KEY = "atcoder:task:abc086_a";
    private static final String AT_URL = "https://atcoder.jp/contests/abc086/tasks/abc086_a";
    private static final String REVIEWER = "capture-test-admin";
    private static final String CF_HTML = "<div class='problem-statement'><div class='header'><div class='title'>A. Capture fixture</div>"
            + "<div class='time-limit'>1 second</div><div class='memory-limit'>256 megabytes</div></div>"
            + "<div><p>Fixture <script type='math/tex'>x^2</script></p><script>unsafe()</script></div>"
            + "<div class='input-specification'><p>Two integers</p></div><div class='output-specification'><p>Answer</p></div>"
            + "<div class='sample-tests'><div class='input'><pre>2<br>3</pre></div><div class='output'><pre>5</pre></div></div></div>";
    private static final String AT_HTML = "<meta property='og:url' content='" + AT_URL + "'><div id='main-container'>"
            + "<span class='h2'>A - Product</span><p>Time Limit: 2 sec / Memory Limit: 256 MiB</p>"
            + "<div id='task-statement'><span class='lang-en'><section><h3>Problem Statement</h3><p>Multiply.</p></section>"
            + "<section><h3>Input</h3>Two numbers.</section><section><h3>Output</h3>Result.</section>"
            + "<section><h3>Sample Input 1</h3><pre>3 4</pre></section><section><h3>Sample Output 1</h3><pre>12</pre></section>"
            + "</span></div></div>";

    private static DruidDataSource database;
    private static JdbcTemplate jdbc;
    private final CodeforcesStatementClient cf = new CodeforcesStatementClient();
    private final AtCoderStatementClient at = new AtCoderStatementClient();
    private RemoteCaptureManager captures;
    private RemoteProblemManager repository;
    private String primaryKey;

    @BeforeAll static void connectToDisposableDatabase() throws Exception {
        database = new DruidDataSource();
        database.setUrl("jdbc:mysql://" + System.getenv("HOJ_CAPTURE_TEST_CONTAINER") + ":3306/hoj?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        database.setUsername("root");
        database.setPassword("");
        database.setMaxActive(32);
        database.setMaxWait(10000);
        database.init();
        jdbc = new JdbcTemplate(database);
        assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
    }

    @AfterAll static void closeDatabase() {
        if (database != null) database.close();
    }

    @BeforeEach void prepareFixtures() {
        jdbc.update("DELETE FROM remote_capture_queue");
        jdbc.update("DELETE FROM remote_capture_credential");
        jdbc.update("DELETE FROM remote_problem_import WHERE problem_key IN (?,?)", CF_KEY, AT_KEY);
        jdbc.update("DELETE FROM remote_statement_current WHERE problem_key IN (?,?)", CF_KEY, AT_KEY);
        jdbc.update("DELETE FROM remote_statement_version WHERE problem_key IN (?,?)", CF_KEY, AT_KEY);
        jdbc.update("DELETE FROM problem WHERE author=?", REVIEWER);
        jdbc.update("INSERT IGNORE INTO user_info(uuid,username,password,status) VALUES(?,?,?,0)", REVIEWER, REVIEWER, "fixture");
        jdbc.update("INSERT IGNORE INTO remote_catalog_problem(problem_key,contest_id,problem_index,title,tags_json,content_hash,seen_token,last_seen_at,changed_at) "
                + "VALUES(?,4,'A','Capture fixture','[]','fixture','fixture',NOW(3),NOW(3))", CF_KEY);
        jdbc.update("INSERT IGNORE INTO external_atcoder_problem(problem_key,task_id,contest_id,problem_index,title,content_hash,seen_token,last_seen_at,changed_at) "
                + "VALUES(?,'abc086_a','abc086','A','Product','fixture','fixture',NOW(3),NOW(3))", AT_KEY);
        primaryKey = seedKey(0);

        ObjectMapper mapper = new ObjectMapper();
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(database);
        RemoteStatementManager statements = new RemoteStatementManager() {
            @Override protected String uid() { return REVIEWER; }
        };
        repository = mock(RemoteProblemManager.class);
        when(repository.adminAddOtherOJProblem(any(), anyString())).thenAnswer(action -> saveProblem(action.getArgument(0)));
        when(repository.adminAddOtherOJProblem(any(), anyString(), eq(true))).thenAnswer(action -> saveProblem(action.getArgument(0)));
        ReflectionTestUtils.setField(statements, "jdbc", jdbc);
        ReflectionTestUtils.setField(statements, "transactions", transactions);
        ReflectionTestUtils.setField(statements, "json", mapper);
        ReflectionTestUtils.setField(statements, "remoteProblems", repository);
        captures = new RemoteCaptureManager(jdbc, transactions, mapper, cf, at, statements);
        captures.setEnabled(true);
    }

    @AfterEach void closeParsers() {
        cf.close();
        at.close();
    }

    @Test void fourHundredDistinctUsersShareOneUploadAndConcurrentApproversPublishOnce() throws Exception {
        List<String> keys = new ArrayList<>();
        keys.add(primaryKey);
        for (int i = 1; i < 400; i++) keys.add(seedKey(i));
        ExecutorService requests = Executors.newFixedThreadPool(64);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Map.Entry<String, Map<String, Object>>>> calls = keys.stream().map(key ->
                    requests.<Map.Entry<String, Map<String, Object>>>submit(() -> {
                        start.await();
                        return new AbstractMap.SimpleEntry<>(key, captures.reserve(key, "codeforces", "4A", CF_URL));
                    })).collect(Collectors.toList());
            start.countDown();
            Map.Entry<String, Map<String, Object>> winner = null;
            for (Future<Map.Entry<String, Map<String, Object>>> pending : calls) {
                Map.Entry<String, Map<String, Object>> result = pending.get(20, TimeUnit.SECONDS);
                if ("UPLOAD".equals(result.getValue().get("state"))) {
                    assertNull(winner, "only one browser can upload the HTML");
                    winner = result;
                } else assertEquals("RECEIVING", result.getValue().get("state"));
            }
            assertNotNull(winner);
            assertEquals("QUEUED", captures.upload(winner.getKey(), (String) winner.getValue().get("lease"),
                    "codeforces", "4A", CF_URL, request(CF_HTML)).get("state"));
            assertEquals("QUEUED", captures.reserve(primaryKey, "codeforces", "4A", CF_URL).get("state"));
            assertEquals(1L, captures.queue().get("total"));
            assertTrue(captures.preview(CF_KEY).getDescription().contains("$x^2$"));
            assertFalse(captures.preview(CF_KEY).getDescription().contains("unsafe"));

            Callable<Map<String, Object>> approve = () -> captures.approveBatch(Collections.singletonList(CF_KEY));
            List<Future<Map<String, Object>>> results = requests.invokeAll(Arrays.asList(approve, approve));
            assertEquals(1, (int) results.get(0).get().get("imported") + (int) results.get(1).get().get("imported"));
            assertEquals(1, jdbc.queryForObject("SELECT auth FROM problem WHERE problem_id='CF-4A'", Integer.class));
            assertEquals("EXISTS", captures.reserve(primaryKey, "codeforces", "4A", CF_URL).get("state"));
            verify(repository, times(1)).adminAddOtherOJProblem(any(), anyString());
        } finally {
            requests.shutdownNow();
        }
    }

    @Test void pauseProtectsUploadAndApprovalAndPendingAtCoderRemainsReviewable() {
        String lease = (String) captures.reserve(primaryKey, "atcoder", "abc086_a", AT_URL).get("lease");
        captures.setEnabled(false);
        assertEquals("DISABLED", captures.upload(primaryKey, lease, "atcoder", "abc086_a", AT_URL,
                request(AT_HTML)).get("state"));
        captures.setEnabled(true);
        assertEquals("QUEUED", captures.upload(primaryKey, lease, "atcoder", "abc086_a", AT_URL,
                request(AT_HTML)).get("state"));
        captures.setEnabled(false);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM problem WHERE problem_id='AC-abc086_a'", Integer.class));
        assertEquals("Product", captures.preview(AT_KEY).getTitle());
        assertEquals(409, assertThrows(ObjectiveException.class, () -> captures.approveBatch(Collections.singletonList(AT_KEY))).getStatus());
        captures.setEnabled(true);
        Map<String, Object> result = captures.approveBatch(Collections.singletonList(AT_KEY));
        assertEquals(1, result.get("imported"), result.toString());
        assertEquals("<input>3 4</input><output>12</output>", jdbc.queryForObject(
                "SELECT examples FROM problem WHERE problem_id='AC-abc086_a'", String.class));
    }

    @Test void staleReservationReopensAndAnIndependentExistingProblemIsNeverOverwritten() {
        String lease = (String) captures.reserve(primaryKey, "codeforces", "4A", CF_URL).get("lease");
        assertEquals(401, assertThrows(ObjectiveException.class, () -> captures.reserve("bad", "codeforces", "4A", CF_URL)).getStatus());
        assertEquals(400, assertThrows(ObjectiveException.class, () -> captures.reserve(primaryKey, "codeforces", "44A", CF_URL)).getStatus());
        jdbc.update("UPDATE remote_capture_queue SET lease_until=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE problem_key=?", CF_KEY);
        String replacement = (String) captures.reserve(primaryKey, "codeforces", "4A", CF_URL).get("lease");
        assertNotEquals(lease, replacement);
        assertEquals(409, assertThrows(ObjectiveException.class,
                () -> captures.upload(primaryKey, lease, "codeforces", "4A", CF_URL, request(CF_HTML))).getStatus());
        assertEquals("QUEUED", captures.upload(primaryKey, replacement, "codeforces", "4A", CF_URL, request(CF_HTML)).get("state"));
        jdbc.update("INSERT INTO problem(problem_id,title,author) VALUES('CF-4A','Existing',?)", REVIEWER);
        assertEquals(1, captures.approveBatch(Collections.singletonList(CF_KEY)).get("skipped"));
        assertEquals("Existing", jdbc.queryForObject("SELECT title FROM problem WHERE problem_id='CF-4A'", String.class));
        assertEquals(0L, captures.queue().get("total"));
        verifyNoInteractions(repository);
    }

    @Test void anArchivedSourceDoesNotPreventAnAdminFromRecreatingTheMissingLibraryRecord() {
        uploadCodeforces();
        assertEquals(1, captures.approveBatch(Collections.singletonList(CF_KEY)).get("imported"));
        jdbc.update("DELETE FROM problem WHERE problem_id='CF-4A' AND author=?", REVIEWER);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM remote_statement_current WHERE problem_key=?", Integer.class, CF_KEY));
        uploadCodeforces();
        assertEquals(1, captures.approveBatch(Collections.singletonList(CF_KEY)).get("imported"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM problem WHERE problem_id='CF-4A'", Integer.class));
    }

    private void uploadCodeforces() {
        Map<String, Object> reservation = captures.reserve(primaryKey, "codeforces", "4A", CF_URL);
        assertEquals("UPLOAD", reservation.get("state"));
        assertEquals("QUEUED", captures.upload(primaryKey, (String) reservation.get("lease"), "codeforces", "4A",
                CF_URL, request(CF_HTML)).get("state"));
    }

    private String seedKey(int number) {
        String uid = "capture-test-" + number;
        String key = String.format("%064x", number);
        jdbc.update("INSERT IGNORE INTO user_info(uuid,username,password,status) VALUES(?,?,?,0)", uid, uid, "fixture");
        jdbc.update("INSERT INTO remote_capture_credential(uid,secret_hash,expires_at) VALUES(?,?,DATE_ADD(NOW(3),INTERVAL 1 HOUR))",
                uid, RemoteCatalogManager.hash(key));
        return key;
    }

    private Problem saveProblem(ProblemStrategy.RemoteProblemInfo item) {
        Problem problem = item.getProblem();
        jdbc.update("INSERT INTO problem(problem_id,title,author,auth,is_remote,description,examples) VALUES(?,?,?,?,?,?,?)",
                problem.getProblemId(), problem.getTitle(), problem.getAuthor(), problem.getAuth(),
                problem.getIsRemote(), problem.getDescription(), problem.getExamples());
        problem.setId(jdbc.queryForObject("SELECT id FROM problem WHERE problem_id=?", Long.class, problem.getProblemId()));
        return problem;
    }

    private static MockHttpServletRequest request(String html) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(html.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
