package top.hcode.hoj.remote;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static top.hcode.hoj.remote.PersonalRemoteTaskStore.*;
import top.hcode.hoj.remote.PersonalRemoteTaskStore.Error;

class PersonalRemoteTaskStoreTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    TransactionTemplate tx;
    PersonalRemoteTaskStore store;
    AtomicLong now;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:personal_tasks;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000", "sa", "");
        jdbc = new JdbcTemplate(source); jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)");
        jdbc.execute("CREATE TABLE problem(id BIGINT PRIMARY KEY,problem_id VARCHAR(120),is_remote BOOLEAN)");
        jdbc.execute("CREATE TABLE external_atcoder_problem(problem_key VARCHAR(120),task_id VARCHAR(80),contest_id VARCHAR(80),available INT)");
        jdbc.update("INSERT INTO external_atcoder_problem VALUES('atcoder:task:abc001_a','abc001_a','abc001',1)");
        jdbc.execute("CREATE TABLE judge(submit_id BIGINT PRIMARY KEY,uid VARCHAR(32),pid BIGINT,cid BIGINT,gid BIGINT,language VARCHAR(80),code TEXT,status INT,version INT NOT NULL DEFAULT 0)");
        for (String file : Arrays.asList("external-learning.sql", "remote-credentials.sql", "personal-remote-tasks.sql")) {
            String ddl = StreamUtils.copyToString(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8).replace(" COLLATE=utf8mb4_unicode_ci", "");
            try (Connection connection = source.getConnection()) { ScriptUtils.executeSqlScript(connection, new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))); }
        }
        jdbc.update("INSERT INTO user_info VALUES('alice',0),('bob',0)");
        jdbc.update("INSERT INTO problem VALUES(1,'CF-4A',TRUE),(2,'AC-abc001_a',TRUE),(3,'LOCAL-1',FALSE)");
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(1,'alice','CODEFORCES','Tourist','tourist','tourist',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'bob','CODEFORCES','Second','second','second',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(3,'alice','ATCODER','Third','third','third',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO remote_user_credential VALUES(1,'s1:synthetic-cipher',1,CURRENT_TIMESTAMP),(2,'s1:synthetic-cipher',1,CURRENT_TIMESTAMP),(3,'s1:synthetic-cipher',1,CURRENT_TIMESTAMP)");
        transactions = new DataSourceTransactionManager(source); tx = new TransactionTemplate(transactions);
        now = new AtomicLong(1800000000000L); store = new PersonalRemoteTaskStore(jdbc, transactions, now::get);
        judge(1, "alice", 1); judge(2, "alice", 1); judge(3, "bob", 1); judge(4, "alice", 2);
    }
    void judge(long id, String uid, long problem) { jdbc.update("INSERT INTO judge(submit_id,uid,pid,cid,gid,language,code,status) VALUES(?,?,?,0,NULL,'C++','synthetic source code',5)", id, uid, problem); }
    @Test void legacyPasswordCannotEnterOrAuthorizeTheSessionOnlyTaskPath() {
        jdbc.update("UPDATE remote_user_credential SET credential_ciphertext='v1:synthetic-old-password' WHERE account_id=1");
        assertThrows(Conflict.class,()->enqueue(1,"alice",1));
        jdbc.update("UPDATE remote_user_credential SET credential_ciphertext='s1:synthetic-session' WHERE account_id=1");
        enqueue(1,"alice",1);Map<String,Object> task=store.claim("CODEFORCES");
        jdbc.update("UPDATE remote_user_credential SET credential_ciphertext='v1:synthetic-old-password' WHERE account_id=1");
        assertFalse(store.beginPost(1,token(task),0));assertEquals("FAILED",state(1));
    }
    void enqueue(long id, String uid, long account) { tx.execute(status -> store.enqueue(uid, id, account, "request_000000000" + id, "54")); }
    String token(Map<String,Object> task) { return (String) task.get("lease_token"); }
    String state(long id) { return jdbc.queryForObject("SELECT state FROM personal_remote_task WHERE submit_id=?", String.class, id); }
    @Test void judgeAndTaskRollbackTogetherAndRequestIdsCannotAliasDifferentPayloads() {
        assertThrows(IllegalStateException.class, () -> store.enqueue("alice", 1, 1, "request_0000000001", "54"));
        assertThrows(RuntimeException.class, () -> tx.execute(status -> { judge(5,"alice",1); store.enqueue("alice",5,1,"request_0000000005","54"); throw new RuntimeException("synthetic transaction failure"); }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM judge WHERE submit_id=5", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM personal_remote_task", Integer.class));
        enqueue(1,"alice",1); enqueue(1,"alice",1);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM personal_remote_task", Integer.class));
        assertThrows(Conflict.class, () -> tx.execute(status -> store.enqueue("alice",2,1,"request_0000000001","54")));
        assertThrows(Conflict.class, () -> tx.execute(status -> store.enqueue("alice",1,1,"request_0000000001","55")));
    }
    @Test void ownershipProofPlatformAndPendingSourceAreRequiredBeforeCreatingAnyTask() {
        assertThrows(Conflict.class, () -> tx.execute(status -> store.enqueue("alice",1,2,"request_0000000001","54")));
        assertThrows(Conflict.class, () -> tx.execute(status -> store.enqueue("bob",1,2,"request_0000000001","54")));
        assertThrows(Conflict.class, () -> tx.execute(status -> store.enqueue("alice",1,3,"request_0000000001","54")));
        jdbc.update("UPDATE external_account SET verified_identity=NULL WHERE id=1");
        assertThrows(Conflict.class, () -> tx.execute(status -> store.enqueue("alice",1,1,"request_0000000001","54")));
        jdbc.update("UPDATE external_account SET verified_identity='tourist' WHERE id=1");
        jdbc.update("UPDATE judge SET status=0 WHERE submit_id=1");
        assertThrows(Conflict.class, () -> tx.execute(status -> store.enqueue("alice",1,1,"request_0000000001","54")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM personal_remote_task",Integer.class));
    }
    @Test void crashBeforePostIsRecoverableButCrashAfterPostOnlyReconciles() {
        enqueue(1,"alice",1); Map<String,Object> first=store.claim("CODEFORCES");
        now.addAndGet(90001); Map<String,Object> replacement=store.claim("CODEFORCES");
        assertEquals("PREPARING",replacement.get("state"));
        assertThrows(Conflict.class,()->store.beginPost(1,token(first),100));
        assertTrue(store.beginPost(1,token(replacement),100));
        now.addAndGet(90001); Map<String,Object> recovered=store.claim("CODEFORCES");
        assertEquals("RECONCILING",recovered.get("state")); assertEquals(1,((Number)recovered.get("submit_attempts")).intValue());
        assertThrows(Conflict.class,()->store.beginPost(1,token(recovered),100));
        assertThrows(Conflict.class,()->store.accepted(1,token(replacement),101));
        store.accepted(1,token(recovered),101); assertEquals("POLLING",state(1));
    }
    @Test void sourceIdentityIsSerializedWhileOtherRemoteAccountsCanProgress() {
        enqueue(1,"alice",1);enqueue(2,"alice",1);enqueue(3,"bob",2);
        Map<String,Object> first=store.claim("CODEFORCES"),other=store.claim("CODEFORCES");
        assertEquals(1L,first.get("submit_id"));assertEquals(3L,other.get("submit_id"));assertNull(store.claim("CODEFORCES"));
        assertTrue(store.beginPost(1,token(first),0));store.accepted(1,token(first),10);
        Map<String,Object> poll=store.claim("CODEFORCES");store.completed(1,token(poll),()->jdbc.update("UPDATE judge SET status=0 WHERE submit_id=1"));
        assertEquals(2L,store.claim("CODEFORCES").get("submit_id"));
    }
    @Test void revokedOrChangedCredentialsAndEditedSourceAreFencedBeforeTheCodePost() {
        for(String mutation:Arrays.asList("UPDATE remote_user_credential SET credential_version=2 WHERE account_id=1","UPDATE external_account SET verified_identity=NULL WHERE id=1","UPDATE user_info SET status=1 WHERE uuid='alice'","UPDATE judge SET code='changed' WHERE submit_id=1","UPDATE judge SET cid=42 WHERE submit_id=1","UPDATE problem SET is_remote=FALSE WHERE id=1")) {
            enqueue(1,"alice",1);Map<String,Object> job=store.claim("CODEFORCES");jdbc.update(mutation);
            assertFalse(store.beginPost(1,token(job),0));assertEquals("FAILED",state(1));
            assertEquals(0,jdbc.queryForObject("SELECT submit_attempts FROM personal_remote_task WHERE submit_id=1",Integer.class));
            jdbc.update("DELETE FROM personal_remote_task");jdbc.update("UPDATE external_account SET verified_identity='tourist' WHERE id=1");jdbc.update("UPDATE remote_user_credential SET credential_version=1 WHERE account_id=1");jdbc.update("UPDATE user_info SET status=0 WHERE uuid='alice'");jdbc.update("UPDATE judge SET cid=0,code='synthetic source code' WHERE submit_id=1");jdbc.update("UPDATE problem SET is_remote=TRUE WHERE id=1");
        }
    }
    @Test void uncertainPostReadFailuresNeverPermitResubmissionAndRequireOwnerRecovery() {
        enqueue(1,"alice",1);Map<String,Object> job=store.claim("CODEFORCES");store.beginPost(1,token(job),100);store.uncertain(1,token(job));
        assertThrows(Conflict.class,()->store.cancelBeforePost(1,"alice"));
        for(int n=0;n<10;n++){now.addAndGet(300001);Map<String,Object> retry=store.claim("CODEFORCES");assertEquals("RECONCILING",retry.get("state"));store.readFailed(1,token(retry));}
        assertEquals("NEEDS_REVIEW",state(1));assertNull(store.claim("CODEFORCES"));
        assertThrows(Conflict.class,()->store.reconcile(1,"bob"));store.reconcile(1,"alice");
        Map<String,Object> recovered=store.claim("CODEFORCES");assertEquals("RECONCILING",recovered.get("state"));assertThrows(Conflict.class,()->store.beginPost(1,token(recovered),100));
    }
    @Test void unlinkAndRebindDoesNotReleaseAnUncertainRequestOnTheSameRemoteIdentity() {
        enqueue(1,"alice",1);Map<String,Object> first=store.claim("CODEFORCES");store.beginPost(1,token(first),0);store.uncertain(1,token(first));
        jdbc.update("DELETE FROM external_account WHERE id=1");
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(5,'alice','CODEFORCES','Tourist','tourist','tourist',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");jdbc.update("INSERT INTO remote_user_credential VALUES(5,'s1:synthetic-cipher',1,CURRENT_TIMESTAMP)");enqueue(2,"alice",5);
        now.addAndGet(5001);Map<String,Object> old=store.claim("CODEFORCES");assertEquals(1L,old.get("submit_id"));assertFalse(store.authorize(1,token(old)));assertEquals("NEEDS_REVIEW",state(1));assertNull(store.claim("CODEFORCES"));
    }
    @Test void resultIdAndVerdictWriteAreFencedAndAtomic() {
        enqueue(1,"alice",1);Map<String,Object> job=store.claim("CODEFORCES");store.beginPost(1,token(job),100);
        assertThrows(Conflict.class,()->store.accepted(1,token(job),100));store.accepted(1,token(job),101);
        Map<String,Object> poll=store.claim("CODEFORCES");
        assertThrows(RuntimeException.class,()->store.completed(1,token(poll),()->{jdbc.update("UPDATE judge SET status=0 WHERE submit_id=1");throw new RuntimeException("synthetic verdict-write failure");}));
        assertEquals(5,jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1",Integer.class));assertEquals("POLLING",state(1));
        now.addAndGet(90001);Map<String,Object> recovered=store.claim("CODEFORCES");AtomicInteger staleWrites=new AtomicInteger();
        assertThrows(Conflict.class,()->store.completed(1,token(poll),staleWrites::incrementAndGet));assertEquals(0,staleWrites.get());
        store.completed(1,token(recovered),()->jdbc.update("UPDATE judge SET status=0 WHERE submit_id=1"));assertEquals("COMPLETED",state(1));assertEquals(0,jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1",Integer.class));
        assertThrows(Conflict.class,()->store.completed(1,token(recovered),staleWrites::incrementAndGet));assertEquals(0,staleWrites.get());
    }
    @Test void twoWorkerInstancesCannotClaimTheSameLease() throws Exception {
        enqueue(1,"alice",1);PersonalRemoteTaskStore second=new PersonalRemoteTaskStore(jdbc,transactions,now::get);CountDownLatch go=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        try {Future<Map<String,Object>> a=workers.submit(()->{go.await();return store.claim("CODEFORCES");}),b=workers.submit(()->{go.await();return second.claim("CODEFORCES");});go.countDown();assertEquals(1,(a.get(6,TimeUnit.SECONDS)==null?0:1)+(b.get(6,TimeUnit.SECONDS)==null?0:1));}
        finally{workers.shutdownNow();}
    }
    @Test void aResultForAnOlderJudgeVersionCannotOverwriteANewRejudge() {
        enqueue(1,"alice",1);Map<String,Object> job=store.claim("CODEFORCES");store.beginPost(1,token(job),100);store.accepted(1,token(job),101);Map<String,Object> poll=store.claim("CODEFORCES");jdbc.update("UPDATE judge SET version=version+1 WHERE submit_id=1");AtomicInteger writes=new AtomicInteger();
        assertFalse(store.completed(1,token(poll),writes::incrementAndGet));assertEquals(0,writes.get());assertEquals("NEEDS_REVIEW",state(1));assertEquals("SOURCE_CHANGED",store.status(1,"alice").get("error_code"));assertEquals(5,jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1",Integer.class));
    }
    @Test void ownerStatusIsAnAllowlistAndCancelledPreparationCannotPost() {
        enqueue(1,"alice",1);Map<String,Object> job=store.claim("CODEFORCES");
        assertThrows(Conflict.class,()->store.status(1,"bob"));Map<String,Object> visible=store.status(1,"alice");
        assertFalse(visible.containsKey("code"));assertFalse(visible.containsKey("code_hash"));assertFalse(visible.containsKey("payload_hash"));assertFalse(visible.containsKey("lease_token"));assertFalse(visible.containsKey("credential_version"));
        store.cancelBeforePost(1,"alice");assertEquals("CANCELLED",state(1));assertThrows(Conflict.class,()->store.beginPost(1,token(job),0));
    }
    @Test void changedAtCoderContestScopeCannotBeDispatchedOrOverwriteAJudge() {
        enqueue(4,"alice",3);
        assertEquals("abc001",jdbc.queryForObject("SELECT source_contest FROM personal_remote_task WHERE submit_id=4",String.class));
        Map<String,Object> task=store.claim("ATCODER");
        jdbc.update("UPDATE external_atcoder_problem SET contest_id='another_contest'");
        assertFalse(store.authorize(4,token(task)));assertEquals("FAILED",state(4));
        assertFalse(store.finishStopped(4,()->fail("Changed source must not be overwritten")));
    }
    @Test void atcoderDisplayIdRequiresItsCanonicalAvailableDirectoryEntry(){
        RemoteProblemRef ref=store.problemReference("AC-abc001_a");assertEquals("abc001",ref.contest);assertEquals("abc001_a",ref.task);
        jdbc.update("UPDATE external_atcoder_problem SET available=0");assertThrows(Conflict.class,()->store.problemReference("AC-abc001_a"));
        jdbc.update("UPDATE external_atcoder_problem SET available=1,problem_key='AC-abc001_a'");assertThrows(Conflict.class,()->store.problemReference("AC-abc001_a"));
    }
    @Test void ordinaryJudgingDoesNotExhaustTheReadFailureBudget() {
        enqueue(1,"alice",1);Map<String,Object> task=store.claim("CODEFORCES");
        store.beginPost(1,token(task),100);store.accepted(1,token(task),101);
        for(int i=0;i<15;i++) {task=store.claim("CODEFORCES");store.waitingForResult(1,token(task));now.addAndGet(5001);}
        assertEquals("POLLING",state(1));assertEquals(0,jdbc.queryForObject("SELECT read_failures FROM personal_remote_task WHERE submit_id=1",Integer.class));
    }
    @Test void explicitRecoveryAdoptsNewSessionOfSameOwnerAndIdentityWithoutEnablingAnotherPost() {
        enqueue(1,"alice",1);Map<String,Object> task=store.claim("CODEFORCES");store.beginPost(1,token(task),100);
        store.review(1,token(task),Error.AUTH_REQUIRED);
        jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=NULL,credential_version=2 WHERE account_id=1");
        assertThrows(Conflict.class,()->store.reconcile(1,"alice"));
        jdbc.update("UPDATE remote_user_credential SET credential_ciphertext='s1:new-synthetic',credential_version=3 WHERE account_id=1");
        store.reconcile(1,"alice");Map<String,Object> recovered=store.claim("CODEFORCES");
        assertEquals("RECONCILING",recovered.get("state"));assertEquals(3,((Number)recovered.get("credential_version")).intValue());
        assertTrue(store.authorize(1,token(recovered)));assertThrows(Conflict.class,()->store.beginPost(1,token(recovered),100));
    }
    @Test void ownerCanRecoverAfterRelinkingTheSameVerifiedSourceIdentity() {
        enqueue(1,"alice",1);Map<String,Object> task=store.claim("CODEFORCES");store.beginPost(1,token(task),100);store.review(1,token(task),Error.AUTH_REQUIRED);
        jdbc.update("DELETE FROM external_account WHERE id=1");
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(9,'alice','CODEFORCES','Tourist','tourist','tourist',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO remote_user_credential VALUES(9,'s1:replacement-synthetic',1,CURRENT_TIMESTAMP)");
        store.reconcile(1,"alice");Map<String,Object> recovered=store.claim("CODEFORCES");
        assertEquals(9,((Number)recovered.get("account_id")).intValue());assertEquals(1,((Number)recovered.get("account_ref")).intValue());
        assertTrue(store.authorize(1,token(recovered)));assertThrows(Conflict.class,()->store.beginPost(1,token(recovered),100));
    }
    @Test void cancelledJudgeAndRelatedRecordsSettleAtomicallyAndOnlyOnce() {
        enqueue(1,"alice",1);store.cancelBeforePost(1,"alice");
        assertThrows(RuntimeException.class,()->store.finishStopped(1,()->{jdbc.update("UPDATE judge SET status=-4 WHERE submit_id=1");throw new RuntimeException("synthetic write failure");}));
        assertEquals(5,jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1",Integer.class));
        assertTrue(store.finishStopped(1,()->jdbc.update("UPDATE judge SET status=-4 WHERE submit_id=1")));
        assertFalse(store.finishStopped(1,()->fail("Cancellation must settle once")));
    }
    @Test void sourceSessionRotationCannotOverwriteRevocationOrAnotherSessionUpdate() {
        enqueue(1,"alice",1);Map<String,Object> task=store.claim("CODEFORCES");String lease=token(task);
        assertTrue(store.rotateSession(1,lease,"s1:synthetic-cipher","s1:rotated-synthetic"));
        assertEquals(1,jdbc.queryForObject("SELECT credential_version FROM remote_user_credential WHERE account_id=1",Integer.class));
        assertFalse(store.rotateSession(1,lease,"s1:synthetic-cipher","s1:stale-synthetic"));
        jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=NULL,credential_version=2 WHERE account_id=1");
        assertFalse(store.rotateSession(1,lease,"s1:rotated-synthetic","s1:resurrected-synthetic"));
        assertNull(jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=1",String.class));
    }

    Map<String,Object> pollingLease(){
        enqueue(1,"alice",1);Map<String,Object> lease=store.claim("CODEFORCES");
        assertTrue(store.beginPost(1,token(lease),100));store.accepted(1,token(lease),101);
        return store.claim("CODEFORCES");
    }
    @ParameterizedTest
    @ValueSource(strings={"UNLINK","DISABLE","REVOKE","SESSION_VERSION","UNVERIFY"})
    void completionRechecksRevocationAfterSourceRead(String mutation){
        Map<String,Object> poll=pollingLease();enqueue(2,"alice",1);
        Map<String,Object> posted=jdbc.queryForMap("SELECT * FROM personal_remote_task WHERE submit_id=1");
        if("UNLINK".equals(mutation))jdbc.update("DELETE FROM external_account WHERE id=1");
        else if("DISABLE".equals(mutation))jdbc.update("UPDATE user_info SET status=1 WHERE uuid='alice'");
        else if("REVOKE".equals(mutation))jdbc.update("DELETE FROM remote_user_credential WHERE account_id=1");
        else if("SESSION_VERSION".equals(mutation))jdbc.update("UPDATE remote_user_credential SET credential_version=2 WHERE account_id=1");
        else jdbc.update("UPDATE external_account SET verified_identity=NULL WHERE id=1");
        AtomicInteger verdicts=new AtomicInteger();
        assertFalse(store.completed(1,token(poll),()->{verdicts.incrementAndGet();jdbc.update("UPDATE judge SET status=0 WHERE submit_id=1");}));
        assertEquals(0,verdicts.get());assertEquals("NEEDS_REVIEW",state(1));
        assertEquals(5,jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1",Integer.class));
        assertEquals(1L,jdbc.queryForObject("SELECT active_submit_id FROM personal_remote_lane WHERE handle_key='tourist'",Long.class));
        assertNull(store.claim("CODEFORCES"));
        // Explicitly rebind the same source identity; the original payload reference remains.
        jdbc.update("UPDATE user_info SET status=0 WHERE uuid='alice'");jdbc.update("DELETE FROM external_account WHERE id=1");
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(9,'alice','CODEFORCES','Tourist','tourist','tourist',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO remote_user_credential VALUES(9,'s1:replacement-synthetic',3,CURRENT_TIMESTAMP)");
        store.reconcile(1,"alice");Map<String,Object> recovered=store.claim("CODEFORCES");
        assertEquals("POLLING",recovered.get("state"));assertEquals(1,((Number)recovered.get("submit_attempts")).intValue());
        assertEquals(posted.get("account_ref"),recovered.get("account_ref"));assertEquals(posted.get("baseline_id"),recovered.get("baseline_id"));
        assertEquals(posted.get("post_started_at"),recovered.get("post_started_at"));assertEquals(posted.get("payload_hash"),recovered.get("payload_hash"));
        assertThrows(Conflict.class,()->store.beginPost(1,token(recovered),100));
        assertTrue(store.completed(1,token(recovered),()->jdbc.update("UPDATE judge SET status=0 WHERE submit_id=1")));
        assertEquals(2L,store.claim("CODEFORCES").get("submit_id"));
    }
    @Test void deadlineReachedInsideVerdictRollsBackAllDatabaseEffects(){
        Map<String,Object> poll=pollingLease();jdbc.execute("CREATE TABLE result_effect(submit_id BIGINT PRIMARY KEY)");
        Map<String,Object> before=jdbc.queryForMap("SELECT * FROM personal_remote_task WHERE submit_id=1");
        long deadline=((java.sql.Timestamp)poll.get("lease_until")).getTime();
        assertThrows(Conflict.class,()->store.completed(1,token(poll),()->{
            jdbc.update("UPDATE judge SET status=0 WHERE submit_id=1");jdbc.update("INSERT INTO result_effect VALUES(1)");now.set(deadline);
        }));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM personal_remote_task WHERE submit_id=1"));
        assertEquals(5,jdbc.queryForObject("SELECT status FROM judge WHERE submit_id=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM result_effect",Integer.class));
        assertEquals(1L,jdbc.queryForObject("SELECT active_submit_id FROM personal_remote_lane WHERE handle_key='tourist'",Long.class));
        Map<String,Object> fresh=store.claim("CODEFORCES");assertNotEquals(token(poll),token(fresh));
        assertTrue(store.completed(1,token(fresh),()->{jdbc.update("UPDATE judge SET status=0 WHERE submit_id=1");jdbc.update("INSERT INTO result_effect VALUES(1)");}));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM result_effect",Integer.class));
    }
}
