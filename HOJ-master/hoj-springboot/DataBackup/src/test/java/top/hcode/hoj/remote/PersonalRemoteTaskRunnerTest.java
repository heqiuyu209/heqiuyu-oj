package top.hcode.hoj.remote;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.*;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.utils.RemoteCredentialCipher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonalRemoteTaskRunnerTest {
    @TempDir Path secrets;
    JdbcTemplate jdbc;
    PersonalRemoteTaskStore store;
    PersonalRemoteTaskRunner runner;
    AtomicLong now;
    AtomicLong sourceSubmittedAt;
    AtomicInteger posts, reads, writes;
    boolean lostPost, pending, ambiguous, failWrite, changeLanguage;
    Runnable onRequest = () -> {};
    String code = "#include <iostream>\nint main() { std::cout << 42; }\n";

    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource data = new DriverManagerDataSource("jdbc:h2:mem:remote_runner;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(data); jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");
        jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,problem_id VARCHAR(120),is_remote BOOLEAN)");
        jdbc.execute("CREATE TABLE judge(submit_id BIGINT PRIMARY KEY,uid VARCHAR(32),pid BIGINT,cid BIGINT,gid BIGINT,language VARCHAR(80),code TEXT,status INT,version INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE result_effect(submit_id BIGINT PRIMARY KEY)");
        for (String file : Arrays.asList("external-learning.sql", "remote-credentials.sql", "personal-remote-tasks.sql")) {
            String ddl = StreamUtils.copyToString(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci", "");
            try (Connection connection = data.getConnection()) { ScriptUtils.executeSqlScript(connection, new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))); }
        }
        jdbc.update("INSERT INTO user_info VALUES('alice',0)");
        jdbc.update("INSERT INTO problem VALUES(1,'CF-4A',TRUE)");
        jdbc.update("INSERT INTO judge VALUES(1,'alice',1,0,NULL,'C++ 23',?,5,0)", code);
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(1,'alice','CODEFORCES','Tourist','tourist','tourist',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        RemoteCredentialCipher cipher = new RemoteCredentialCipher(secrets);
        jdbc.update("INSERT INTO remote_user_credential VALUES(1,?,1,CURRENT_TIMESTAMP)", cipher.encryptSession(1, "alice", "CODEFORCES", "tourist", "JSESSIONID=synthetic-cookie", true));
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(data);
        now = new AtomicLong(System.currentTimeMillis());
        sourceSubmittedAt = new AtomicLong();
        store = new PersonalRemoteTaskStore(jdbc, transactions, now::get);
        new TransactionTemplate(transactions).execute(status -> store.enqueue("alice", 1, 1, "request_0000000001", "5400"));
        posts = new AtomicInteger(); reads = new AtomicInteger(); writes = new AtomicInteger();
        runner = new PersonalRemoteTaskRunner(jdbc, store, cipher,
                (platform, session, before) -> new FakeTransport(platform, session, before),
                platform -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Network must not hold row locks"),
                (id, result, stoppedStatus) -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    jdbc.update("UPDATE judge SET status=? WHERE submit_id=?", result == null ? stoppedStatus : result.verdict, id);
                    jdbc.update("INSERT INTO result_effect VALUES(?)", id);
                    if (failWrite) throw new IllegalStateException("synthetic verdict transaction failure");
                    writes.incrementAndGet();
                });
    }

    class FakeTransport extends PersonalRemoteTransport {
        final Runnable before;
        FakeTransport(String platform, RemoteSession session, Runnable before) { super(platform, session, before); this.before = before; }
        @Override public String get(String path) {
            onRequest.run(); before.run(); reads.incrementAndGet();
            if (path.contains("/submit?")) return nav() + "<form method='post' action='/contest/4/submit'><input type='hidden' name='csrf_token' value='synthetic'><input name='submittedProblemIndex' value='A'><select name='programTypeId'><option value='5400'>" + (changeLanguage ? "changed language" : "C++ 23") + "</option></select></form>";
            if (path.startsWith("/api/user.status")) return "{\"status\":\"OK\",\"result\":[" + row(posts.get() == 0 ? 100 : 101) + (ambiguous && posts.get() > 0 ? "," + row(102) : "") + "]}";
            if (path.contains("/submission/")) return nav() + "<pre id='program-source-text'>" + code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>";
            throw new RemoteSourceFailure(RemoteSourceFailure.Kind.UNAVAILABLE);
        }
        @Override public Reply postCode(String path, Map<String, String> fields, Runnable barrier) {
            onRequest.run(); before.run(); barrier.run();
            assertEquals("SUBMITTING", state()); assertEquals(1, attempts());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(code, fields.get("source")); sourceSubmittedAt.set(now.get()); posts.incrementAndGet();
            if (lostPost) throw new RemoteSourceFailure(RemoteSourceFailure.Kind.UNAVAILABLE);
            return new Reply(302, "synthetic response is not acceptance evidence");
        }
    }
    String nav() { return "<div class='lang-chooser'><a href='/profile/Tourist'>Tourist</a><a href='/logout?csrf_token=synthetic'>Logout</a></div>"; }
    String row(int id) { return "{\"id\":" + id + ",\"creationTimeSeconds\":" + (sourceSubmittedAt.get() == 0 ? now.get() : sourceSubmittedAt.get()) / 1000 + ",\"author\":{\"members\":[{\"handle\":\"Tourist\"}]},\"problem\":{\"contestId\":4,\"index\":\"A\"},\"programmingLanguage\":\"C++ 23\",\"verdict\":\"" + (pending ? "TESTING" : "OK") + "\",\"timeConsumedMillis\":12,\"memoryConsumedBytes\":1024}"; }
    String state() { return jdbc.queryForObject("SELECT state FROM personal_remote_task WHERE submit_id=1", String.class); }
    int attempts() { return jdbc.queryForObject("SELECT submit_attempts FROM personal_remote_task WHERE submit_id=1", Integer.class); }
    void step() { assertTrue(runner.runOne("CODEFORCES")); }
    void advance() { now.addAndGet(6000); }
    void postAndMatch() { step(); assertEquals("RECONCILING", state()); advance(); step(); assertEquals("POLLING", state()); }

    @Test void singlePostIsMatchedIndependentlyAndVerdictAndSideEffectsCommitTogether() {
        postAndMatch(); step();
        assertEquals("COMPLETED", state()); assertEquals(0, jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1", Integer.class));
        assertEquals(1, posts.get()); assertEquals(1, writes.get()); assertFalse(runner.runOne("CODEFORCES"));
    }
    @Test void connectionLostAfterPostOnlyTriggersReadRecovery() {
        lostPost = true; postAndMatch(); step();
        assertEquals("COMPLETED", state()); assertEquals(1, posts.get()); assertEquals(1, attempts());
    }
    @Test void leaseExpiredAfterDurablePostDoesNotSendCodeAgain() {
        Map<String, Object> task = store.claim("CODEFORCES");
        assertTrue(store.beginPost(1, (String) task.get("lease_token"), 100));
        posts.set(1); sourceSubmittedAt.set(now.get()); now.addAndGet(90001); step(); assertEquals("POLLING", state()); step();
        assertEquals("COMPLETED", state()); assertEquals(1, posts.get());
    }
    @Test void revocationBetweenReadsStopsBeforeTheCodePostAndSettlesFailure() {
        onRequest = () -> { if (reads.get() == 1) jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=NULL,credential_version=2 WHERE account_id=1"); };
        step(); assertEquals("FAILED", state()); assertEquals(0, posts.get());
        runner.finishStopped(); assertEquals(10, jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1", Integer.class));
        assertEquals(1, writes.get()); runner.finishStopped(); assertEquals(1, writes.get());
    }
    @Test void expiredLeaseImmediatelyBeforePostPreventsAnyCodeIo() {
        onRequest = () -> { if (reads.get() == 2) now.addAndGet(90001); };
        step(); assertEquals("PREPARING", state()); assertEquals(0, posts.get()); assertEquals(0, attempts());
        onRequest = () -> {}; step(); assertEquals(1, posts.get()); assertEquals("RECONCILING", state());
    }
    @Test void twoIdenticalSourceMatchesRequireReviewAndNeverChooseTheLargerId() {
        ambiguous = true; step(); advance(); step(); assertEquals("NEEDS_REVIEW", state());
        assertEquals("AMBIGUOUS_RESULT", store.status(1, "alice").get("error_code")); assertEquals(1, posts.get()); assertEquals(0, writes.get());
    }
    @Test void ordinaryPendingVerdictsCanBePolledWithoutExhaustingReadRetries() {
        pending = true; postAndMatch();
        for (int i = 0; i < 15; i++) { step(); assertEquals("POLLING", state()); advance(); }
        pending = false; step(); assertEquals("COMPLETED", state()); assertEquals(1, posts.get());
    }
    @Test void lostReplyCannotAdoptLaterManualCodeAndEventuallyRetainsLaneForReview() {
        lostPost = true; step();
        // Both timestamps are in the past; the ordinary future-clock guard cannot mask this bug.
        long originalPost=now.get()-180000;
        jdbc.update("UPDATE personal_remote_task SET post_started_at=? WHERE submit_id=1", new java.sql.Timestamp(originalPost));
        sourceSubmittedAt.set(originalPost+121001); advance();
        for (int i = 0; i < 10; i++) { step(); now.addAndGet(300001); }
        assertEquals("NEEDS_REVIEW", state()); assertEquals(1, posts.get()); assertEquals(0, writes.get());
        assertEquals(1L, jdbc.queryForObject("SELECT active_submit_id FROM personal_remote_lane", Long.class));
        assertNull(jdbc.queryForObject("SELECT remote_submit_id FROM personal_remote_task", Long.class));
        assertEquals(5, jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1", Integer.class));
    }
    @Test void originalSubmissionCanBeReconciledAndPolledAfterALongWorkerOutage() {
        lostPost = true; step(); now.addAndGet(86400000); step();
        assertEquals("POLLING", state()); step();
        assertEquals("COMPLETED", state()); assertEquals(1, posts.get()); assertEquals(1, writes.get());
    }
    @Test void longSourceQueueDoesNotTurnAKnownPendingSubmissionIntoAReadFailure() {
        pending = true; postAndMatch(); now.addAndGet(86400000); step();
        assertEquals("POLLING", state()); assertEquals(0, jdbc.queryForObject("SELECT read_failures FROM personal_remote_task", Integer.class));
        pending = false; advance(); step();
        assertEquals("COMPLETED", state()); assertEquals(1, posts.get()); assertEquals(1, writes.get());
    }
    @Test void failedFinalizationRollsBackBothVerdictAndRelatedRecordsAndOnlyRetriesReads() {
        postAndMatch(); failWrite = true; step(); assertEquals("POLLING", state());
        assertEquals(5, jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM result_effect", Integer.class));
        failWrite = false; advance(); step(); assertEquals("COMPLETED", state()); assertEquals(1, posts.get()); assertEquals(1, writes.get());
    }
    @Test void changedLanguageStopsBeforeTheBaselineAndBeforeAnyCodePost() {
        changeLanguage = true; step(); assertEquals("FAILED", state()); assertEquals(0, posts.get());
        assertEquals("LANGUAGE_CHANGED", store.status(1, "alice").get("error_code")); assertEquals(1, reads.get());
    }
    @Test void changedJudgeVersionDuringPollingCannotBeOverwritten() {
        postAndMatch(); jdbc.update("UPDATE judge SET version=1 WHERE submit_id=1"); step();
        assertEquals("NEEDS_REVIEW", state()); assertEquals(0, writes.get()); assertEquals(1, posts.get());
    }
    @Test void atCoderUsesPinnedContestAndItsOwnFormFieldsThroughTheWholeTaskLifecycle() throws Exception {
        jdbc.update("DELETE FROM personal_remote_task");
        jdbc.execute("CREATE TABLE external_atcoder_problem(problem_key VARCHAR(120),task_id VARCHAR(80),contest_id VARCHAR(80),available INT)");
        jdbc.update("INSERT INTO external_atcoder_problem VALUES('atcoder:task:abc001_a','abc001_a','abc001',1)");
        jdbc.update("UPDATE problem SET problem_id='AC-abc001_a' WHERE id=1");
        jdbc.update("UPDATE external_account SET platform='ATCODER' WHERE id=1");
        RemoteCredentialCipher cipher = new RemoteCredentialCipher(secrets);
        jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=? WHERE account_id=1", cipher.encryptSession(1,"alice","ATCODER","tourist","REVEL_SESSION=synthetic",false));
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> store.enqueue("alice",1,1,"atcoder_request_0001","5400"));
        String nav = "<div id='navbar-collapse'><ul class='navbar-right'><span class='username'>Tourist</span></ul></div><form method='POST' action='/logout'></form>";
        PersonalRemoteTaskRunner at = new PersonalRemoteTaskRunner(jdbc, store, cipher,
                (platform, session, before) -> new PersonalRemoteTransport(platform, session, before) {
                    @Override public String get(String path) {
                        before.run();
                        if (path.equals("/contests/abc001/tasks/abc001_a?lang=en")) return nav + "<form method='POST' action='/contests/abc001/submit'><input type='hidden' name='csrf_token' value='synthetic'><input name='data.TaskScreenName' value='abc001_a'><select name='data.LanguageId'><option value='5400'>C++ 23</option></select></form>";
                        if (path.contains("/submissions/me")) {
                            String time = java.time.Instant.ofEpochMilli(now.get()).atOffset(java.time.ZoneOffset.ofHours(9)).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ"));
                            return nav + "<table><thead><tr><th>Submission Time</th></tr></thead><tbody><tr><td>"+time+"</td><td><a href='/contests/abc001/tasks/abc001_a'>A</a></td><td><a href='/users/Tourist'>Tourist</a></td><td>C++ 23</td><td>100</td><td>20 Byte</td><td>AC</td><td>12 ms</td><td>1 KB</td><td><a href='/contests/abc001/submissions/"+(posts.get()==0?100:101)+"'>Detail</a></td></tr></tbody></table>";
                        }
                        if (path.equals("/contests/abc001/submissions/101")) return nav+"<pre id='submission-code'>"+code.replace("<","&lt;").replace(">","&gt;")+"</pre>";
                        throw new RemoteSourceFailure(RemoteSourceFailure.Kind.FORMAT_CHANGED);
                    }
                    @Override public Reply postCode(String path,Map<String,String> fields,Runnable barrier) {
                        before.run();barrier.run();posts.incrementAndGet();assertEquals("/contests/abc001/submit",path);
                        assertEquals(code,fields.get("sourceCode"));assertEquals("abc001_a",fields.get("data.TaskScreenName"));assertEquals("5400",fields.get("data.LanguageId"));return new Reply(302,"synthetic");
                    }
                }, platform -> {}, (id,result,stopped) -> jdbc.update("UPDATE judge SET status=? WHERE submit_id=?", result.verdict,id));
        assertTrue(at.runOne("ATCODER"));assertEquals("RECONCILING",state());advance();
        assertTrue(at.runOne("ATCODER"));assertEquals("POLLING",state());assertTrue(at.runOne("ATCODER"));
        assertEquals("COMPLETED",state());assertEquals(1,posts.get());assertEquals(0,jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1",Integer.class));
    }
    @Test void realLocalHttpRotationIsEncryptedAndAvailableToTheNextWorkerStep() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        List<ch.qos.logback.classic.Level> levels = new ArrayList<>();
        for (String loggerName : Arrays.asList("org.apache.http.wire","org.apache.http.headers")) {
            ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(loggerName);levels.add(logger.getLevel());logger.setLevel(ch.qos.logback.classic.Level.OFF);
        }
        server.createContext("/", exchange -> {
            String path=exchange.getRequestURI().toString(),body;
            if(path.contains("/submit?") && "GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Set-Cookie","JSESSIONID=synthetic-rotated; Path=/; HttpOnly");
                body=nav()+"<form method='POST' action='/contest/4/submit'><input type='hidden' name='csrf_token' value='synthetic'><input name='submittedProblemIndex' value='A'><select name='programTypeId'><option value='5400'>C++ 23</option></select></form>";
            } else {
                assertEquals("JSESSIONID=synthetic-rotated",exchange.getRequestHeaders().getFirst("Cookie"));
                if(path.startsWith("/api/user.status")) body="{\"status\":\"OK\",\"result\":["+row(posts.get()==0?100:101)+"]}";
                else if("POST".equals(exchange.getRequestMethod())) {posts.incrementAndGet();body="synthetic accepted response, not evidence";}
                else body=nav()+"<pre id='program-source-text'>"+code.replace("<","&lt;").replace(">","&gt;")+"</pre>";
            }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            RemoteCredentialCipher cipher=new RemoteCredentialCipher(secrets);
            PersonalRemoteTaskRunner real=new PersonalRemoteTaskRunner(jdbc,store,cipher,
                    (platform,session,before)->new PersonalRemoteTransport(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()),session,before),
                    platform->{},(id,result,stopped)->jdbc.update("UPDATE judge SET status=? WHERE submit_id=?",result.verdict,id));
            assertTrue(real.runOne("CODEFORCES"));assertEquals("RECONCILING",state());
            String encrypted=jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=1",String.class);
            assertTrue(encrypted.startsWith("s1:"));assertFalse(encrypted.contains("synthetic-rotated"));
            assertEquals("JSESSIONID=synthetic-rotated",cipher.decryptSession(1,"alice","CODEFORCES","tourist",encrypted));
            advance();assertTrue(real.runOne("CODEFORCES"));assertEquals("POLLING",state());assertTrue(real.runOne("CODEFORCES"));
            assertEquals("COMPLETED",state());assertEquals(1,posts.get());
        } finally {
            server.stop(0);int index=0;
            for(String loggerName:Arrays.asList("org.apache.http.wire","org.apache.http.headers"))((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(loggerName)).setLevel(levels.get(index++));
        }
    }
}
