package top.hcode.hoj.remote;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.hcode.hoj.utils.RemoteCredentialCipher;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NowCoderTaskRunnerTest {
    @TempDir Path secret;NowCoderTaskJournalTest fixture;RemoteCredentialCipher cipher;AtomicInteger posts=new AtomicInteger(),reads=new AtomicInteger(),grades=new AtomicInteger();boolean loseReply,revokeBeforePost,historyUnavailable;long historyId,baselineAtPost=-1;String question="1338275",language="C++",status;
    @BeforeEach void setup() throws Exception {
        fixture=new NowCoderTaskJournalTest();fixture.setup();cipher=new RemoteCredentialCipher(secret);String saved=cipher.encryptSession(1,"alice","NOWCODER","12345","t=synthetic; csrf_token=synthetic",true);fixture.jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=? WHERE account_id=1",saved);fixture.enqueue();
        status="{\"code\":0,\"data\":{\"id\":\"opaque-job\",\"submissionId\":100,\"status\":5}}";
    }
    @AfterEach void close(){fixture.close();}
    PersonalRemoteTaskRunner runner(){
        return new PersonalRemoteTaskRunner(fixture.jdbc,fixture.tasks,cipher,(platform,session,guard)->new PersonalRemoteTransport(platform,session,guard){
            @Override public String get(String path){
                guard.run();reads.incrementAndGet();String nav="<script>\nwindow.isLogin=true;\nwindow.globalInfo={ownerId:'12345'};\n</script>";
                if(path.contains("practice-coding")){
                    if(historyUnavailable)throw new RemoteSourceFailure(RemoteSourceFailure.Kind.UNAVAILABLE);
                    String row=historyId==0?"":"<tr><td><a href='/acm/contest/view-submission?submissionId="+historyId+"&uid=12345'>"+historyId+"</a></td><td><a href='/acm/problem/218144'>Prior submission</a></td>"+String.join("",Collections.nCopies(5,"<td>value</td>"))+"<td>C++</td><td>2020-01-01 00:00:00</td></tr>";
                    return nav+"<table class='table-hover'><thead><tr>"+String.join("",Collections.nCopies(9,"<th>header</th>"))+"</tr></thead><tbody>"+row+"</tbody></table>";
                }
                if("/".equals(path))return nav;
                assertEquals("/acm/problem/218144",path);return nav+"<script>\nwindow.pageInfo={questionId:'"+question+"',problemId:'218144'};\n</script>";
            }
            @Override public String nowcoderQuestion(String id){guard.run();reads.incrementAndGet();return "{\"code\":0,\"data\":{\"id\":\""+id+"\",\"codingInfo\":{\"supportLanguages\":[{\"langId\":2,\"langName\":\""+language+"\"}]}}}";}
            @Override public String nowcoderAccessToken(){guard.run();reads.incrementAndGet();return "{\"success\":true,\"data\":{\"accessToken\":\"never-persisted-token\"}}";}
            @Override public String nowcoderSubmit(String body,Runnable barrier){
                guard.run();if(revokeBeforePost)fixture.jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=NULL,credential_version=2 WHERE account_id=1");barrier.run();baselineAtPost=fixture.jdbc.queryForObject("SELECT baseline_id FROM personal_remote_task WHERE submit_id=1",Long.class);posts.incrementAndGet();if(loseReply)throw new RemoteSourceFailure(RemoteSourceFailure.Kind.UNAVAILABLE);return "{\"code\":0,\"data\":{\"id\":\"opaque-job\"}}";
            }
            @Override public String nowcoderStatus(Map<String,String> query){guard.run();reads.incrementAndGet();assertEquals("opaque-job",query.get("id"));return status;}
        },platform->assertEquals("NOWCODER",platform),(id,result,stopped)->{assertEquals(0,result.verdict);assertEquals(100L,result.id);fixture.grade();grades.incrementAndGet();});
    }
    @Test void realRunnerPersistsSourceJobAndFreshRunnerCompletesWithoutReposting(){
        assertTrue(runner().runOne("NOWCODER"));assertEquals("POLLING",fixture.state());assertEquals("opaque-job",fixture.journal.job(1));assertEquals(1,posts.get());assertEquals(0,grades.get());
        assertTrue(runner().runOne("NOWCODER"));assertEquals("COMPLETED",fixture.state());assertEquals(1,posts.get());assertEquals(1,grades.get());assertEquals(1,fixture.count("grading_event"));assertFalse(runner().runOne("NOWCODER"));
        String snapshot=fixture.jdbc.queryForObject("SELECT context_json FROM personal_remote_nowcoder",String.class);assertFalse(snapshot.contains("token"));assertFalse(snapshot.contains("synthetic source"));
    }
    @Test void waitingOrHiddenFeedbackNeverUsesTheReadFailureBudgetOrCreatesAnotherPost(){
        runner().runOne("NOWCODER");status="{\"code\":0,\"data\":{\"id\":\"opaque-job\",\"status\":107}}";runner().runOne("NOWCODER");assertEquals("POLLING",fixture.state());assertEquals(0,fixture.jdbc.queryForObject("SELECT read_failures FROM personal_remote_task",Integer.class));assertEquals(1,posts.get());assertEquals(0,grades.get());
        fixture.now.addAndGet(5001);status="{\"code\":0,\"data\":{\"id\":\"opaque-job\",\"submissionId\":100,\"status\":5}}";runner().runOne("NOWCODER");assertEquals("COMPLETED",fixture.state());assertEquals(1,posts.get());
    }
    @Test void lostPostReplyOnlyLooksUpHistoryAndRetainsUnconfirmedIdentityForReview(){
        loseReply=true;runner().runOne("NOWCODER");assertEquals("RECONCILING",fixture.state());assertEquals(1,posts.get());assertNull(fixture.journal.job(1));fixture.now.addAndGet(5001);int before=reads.get();runner().runOne("NOWCODER");
        assertTrue(reads.get()>before);assertEquals("NEEDS_REVIEW",fixture.state());assertEquals("POST_UNCERTAIN",fixture.jdbc.queryForObject("SELECT error_code FROM personal_remote_task",String.class));assertEquals(1,posts.get());assertEquals(0,grades.get());assertFalse(runner().runOne("NOWCODER"));
        fixture.tasks.reconcile(1,"alice");runner().runOne("NOWCODER");assertEquals("NEEDS_REVIEW",fixture.state());assertEquals(1,posts.get());assertEquals(1L,fixture.jdbc.queryForObject("SELECT active_submit_id FROM personal_remote_lane",Long.class));
    }
    @Test void sourceSnapshotAndLanguageAreRecheckedBeforeAnyCodePost(){
        question="99999";runner().runOne("NOWCODER");assertEquals("FAILED",fixture.state());assertEquals(0,posts.get());assertEquals(0,grades.get());
    }
    @Test void revocationAtTheLastBarrierStopsBeforeCodeIo(){
        revokeBeforePost=true;runner().runOne("NOWCODER");assertEquals("FAILED",fixture.state());assertEquals(0,posts.get());assertEquals(0,grades.get());assertEquals(0,fixture.jdbc.queryForObject("SELECT submit_attempts FROM personal_remote_task",Integer.class));
    }
    @Test void reviewedTaskWithKnownJobResumesPollingUsingANewSessionWithoutPosting(){
        runner().runOne("NOWCODER");Map<String,Object> poll=fixture.claim();fixture.tasks.review(1,fixture.token(poll),PersonalRemoteTaskStore.Error.AUTH_REQUIRED);fixture.tasks.reconcile(1,"alice");assertEquals("POLLING",fixture.state());runner().runOne("NOWCODER");assertEquals("COMPLETED",fixture.state());assertEquals(1,posts.get());
    }
    @Test void priorSubmissionBoundaryIsDurableBeforePostingAndAValidNewResultStillGrades(){
        historyId=90;runner().runOne("NOWCODER");assertEquals(90L,baselineAtPost);assertEquals("POLLING",fixture.state());assertEquals(90L,fixture.jdbc.queryForObject("SELECT baseline_id FROM personal_remote_task",Long.class));
        runner().runOne("NOWCODER");assertEquals("COMPLETED",fixture.state());assertEquals(1,posts.get());assertEquals(1,grades.get());
    }
    @Test void emptyHistoryKeepsTheInitialBoundaryAtZero(){
        runner().runOne("NOWCODER");assertEquals(0L,baselineAtPost);assertEquals(1,posts.get());
    }
    @Test void historyReadFailureCannotEnterThePostBarrier(){
        historyUnavailable=true;runner().runOne("NOWCODER");assertEquals(0,posts.get());assertEquals(-1L,baselineAtPost);assertNull(fixture.journal.job(1));assertEquals(0,fixture.jdbc.queryForObject("SELECT submit_attempts FROM personal_remote_task",Integer.class));assertEquals(0,grades.get());
    }
    @Test void anOldPublicSubmissionCannotBeGradedAsTheNewTask(){
        historyId=100;runner().runOne("NOWCODER");assertEquals("POLLING",fixture.state());runner().runOne("NOWCODER");assertEquals(0,grades.get());assertEquals(0,fixture.count("grading_event"));assertNull(fixture.jdbc.queryForObject("SELECT remote_submit_id FROM personal_remote_task",Long.class));assertEquals(1,posts.get());
        assertEquals("NEEDS_REVIEW",fixture.state());assertEquals("AMBIGUOUS_RESULT",fixture.jdbc.queryForObject("SELECT error_code FROM personal_remote_task",String.class));assertNull(fixture.jdbc.queryForObject("SELECT lease_token FROM personal_remote_task",String.class));assertEquals(1L,fixture.jdbc.queryForObject("SELECT active_submit_id FROM personal_remote_lane",Long.class));
        int before=reads.get();fixture.now.addAndGet(90001);assertFalse(runner().runOne("NOWCODER"));assertEquals(before,reads.get());assertEquals(1,posts.get());
    }
    @Test void uncertainPostKeepsItsOriginalBoundaryAcrossWorkersAndManualReconciliation(){
        historyId=90;loseReply=true;runner().runOne("NOWCODER");assertEquals("RECONCILING",fixture.state());assertEquals(90L,baselineAtPost);
        historyId=99;fixture.now.addAndGet(5001);runner().runOne("NOWCODER");assertEquals("NEEDS_REVIEW",fixture.state());fixture.tasks.reconcile(1,"alice");runner().runOne("NOWCODER");
        assertEquals("NEEDS_REVIEW",fixture.state());assertEquals(90L,fixture.jdbc.queryForObject("SELECT baseline_id FROM personal_remote_task",Long.class));assertEquals(1,posts.get());assertEquals(0,grades.get());assertEquals(1L,fixture.jdbc.queryForObject("SELECT active_submit_id FROM personal_remote_lane",Long.class));
    }
    @Test void aChangedPublicSubmissionStopsPollingAndKeepsTheConfirmedIdentity(){
        historyId=90;runner().runOne("NOWCODER");status="{\"code\":0,\"data\":{\"id\":\"opaque-job\",\"submissionId\":100,\"status\":107}}";runner().runOne("NOWCODER");assertEquals("POLLING",fixture.state());assertEquals(100L,fixture.jdbc.queryForObject("SELECT remote_submit_id FROM personal_remote_task",Long.class));
        fixture.now.addAndGet(5001);status="{\"code\":0,\"data\":{\"id\":\"opaque-job\",\"submissionId\":101,\"status\":5}}";runner().runOne("NOWCODER");
        assertEquals("NEEDS_REVIEW",fixture.state());assertEquals("AMBIGUOUS_RESULT",fixture.jdbc.queryForObject("SELECT error_code FROM personal_remote_task",String.class));assertEquals(100L,fixture.jdbc.queryForObject("SELECT remote_submit_id FROM personal_remote_task",Long.class));assertEquals(0,grades.get());assertEquals(1,posts.get());
    }
}
