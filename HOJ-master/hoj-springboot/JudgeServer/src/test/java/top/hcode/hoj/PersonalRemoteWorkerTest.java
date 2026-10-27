package top.hcode.hoj;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import top.hcode.hoj.judge.JudgeContext;
import top.hcode.hoj.remote.*;
import top.hcode.hoj.remoteJudge.personal.PersonalRemoteWorker;
import java.util.*;
import static org.mockito.Mockito.*;

class PersonalRemoteWorkerTest {
    PersonalRemoteSource.Candidate sourceResult(String verdict) {
        try (PersonalRemoteTransport http = new PersonalRemoteTransport("CODEFORCES", RemoteSession.parse("CODEFORCES", "JSESSIONID=synthetic"), () -> {}) {
            @Override public String get(String path) {
                return "{\"status\":\"OK\",\"result\":[{\"id\":101,\"creationTimeSeconds\":1800000000,\"author\":{\"members\":[{\"handle\":\"Tourist\"}]},\"problem\":{\"contestId\":4,\"index\":\"A\"},\"programmingLanguage\":\"C++\",\"verdict\":\"" + verdict + "\",\"timeConsumedMillis\":12,\"memoryConsumedBytes\":1024}]}";
            }
        }) { return new PersonalRemoteSource(new RemoteProblemRef("CF-4A", null), "Tourist", http).recent().get(0); }
    }
    void verifyWrite(long cid, PersonalRemoteSource.Candidate result, int status, Integer score) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class); JudgeContext context = mock(JudgeContext.class);
        Map<String, Object> judge = new HashMap<>(); judge.put("uid", "alice"); judge.put("pid", 1L); judge.put("cid", cid); judge.put("gid", null);
        when(jdbc.queryForMap("SELECT uid,pid,cid,gid FROM judge WHERE submit_id=?", 42L)).thenReturn(judge);
        PersonalRemoteWorker worker = new PersonalRemoteWorker(jdbc, mock(PlatformTransactionManager.class), mock(StringRedisTemplate.class), context, "unused-local-test-key-directory");
        ReflectionTestUtils.invokeMethod(worker, "applyResult", 42L, result, status);
        verify(context).updateOtherTable(42L, status, cid, "alice", 1L, null, score, result == null ? null : result.time);
        verify(jdbc).update(eq("UPDATE judge SET status=?,time=?,memory=?,score=?,error_message=?,judger='PersonalRemote',vjudge_submit_id=?,vjudge_username=NULL,vjudge_password=NULL,gmt_modified=CURRENT_TIMESTAMP WHERE submit_id=?"),
                eq(status), eq(result == null ? null : result.time), eq(result == null ? null : result.memory), eq(score),
                eq(result == null ? "Personal remote task stopped before code submission" : null), eq(result == null ? null : result.id), eq(42L));
    }
    @Test void acceptedPracticePreservesNormalAcRecordWithoutContestScore() { verifyWrite(0, sourceResult("OK"), 0, null); }
    @Test void contestVerdictsUseExistingRemoteScoreConventionAndUpdateContestRecord() {
        verifyWrite(1, sourceResult("OK"), 0, 100); verifyWrite(1, sourceResult("WRONG_ANSWER"), -1, 0); verifyWrite(1, sourceResult("TIME_LIMIT_EXCEEDED"), 1, 0);
    }
    @Test void prePostFailureAndCancellationAlsoSettleTheExistingContestRecord() { verifyWrite(1, null, 10, 0); verifyWrite(1, null, -4, 0); }
}
