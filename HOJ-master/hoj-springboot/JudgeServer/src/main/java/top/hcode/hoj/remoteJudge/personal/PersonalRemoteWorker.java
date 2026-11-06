package top.hcode.hoj.remoteJudge.personal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import top.hcode.hoj.judge.JudgeContext;
import top.hcode.hoj.remote.*;
import top.hcode.hoj.utils.RemoteCredentialCipher;

import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Independent from the administrator account pool and its re-submit/retry queues. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "hoj.personal.remote", name = "enabled", havingValue = "true")
public class PersonalRemoteWorker {
    private static final Logger log = LoggerFactory.getLogger(PersonalRemoteWorker.class);
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final JudgeContext judgeContext;
    private final PersonalRemoteTaskRunner runner;

    public PersonalRemoteWorker(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                StringRedisTemplate redis, JudgeContext judgeContext,
                                @Value("${hoj.remote-credentials.secret-dir:/hoj/secrets/remote}") String secretDirectory) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.judgeContext = judgeContext;
        PersonalRemoteTaskStore store = new PersonalRemoteTaskStore(jdbc, transactions);
        runner = new PersonalRemoteTaskRunner(jdbc, store, new RemoteCredentialCipher(Paths.get(secretDirectory)),
                PersonalRemoteTransport::new, this::rateGate, this::applyResult);
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 15000)
    public void tick() {
        try {
            runner.runOne("CODEFORCES");
            runner.runOne("ATCODER");
            runner.runOne("NOWCODER");
            runner.finishStopped();
        } catch (Exception e) {
            // Never log JDBC parameters, response bodies, cookies, source code, or exception messages.
            log.warn("Personal remote worker step failed ({})", e.getClass().getSimpleName());
        }
    }

    private void rateGate(String platform) {
        String key = "NOWCODER".equals(platform)?"external:nowcoder:public-http":"CODEFORCES".equals(platform) ? "external:cf:public-api" : "external:at:personal-http";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        try {
            do {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, UUID.randomUUID().toString(), 3, TimeUnit.SECONDS))) return;
                Thread.sleep(250);
            } while (System.nanoTime() < deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // A failed shared limiter must not turn into unrestricted source traffic.
        }
        throw new RemoteSourceFailure(RemoteSourceFailure.Kind.UNAVAILABLE);
    }

    private void applyResult(long id, PersonalRemoteSource.Candidate result, int stoppedStatus) {
        Map<String, Object> judge = jdbc.queryForMap("SELECT uid,pid,cid,gid FROM judge WHERE submit_id=?", id);
        int status = result == null ? stoppedStatus : result.verdict;
        long cid = ((Number) judge.get("cid")).longValue();
        Integer score = cid == 0 ? null : status == 0 ? 100 : 0;
        Integer time = result == null ? null : result.time;
        int updated = jdbc.update("UPDATE judge SET status=?,time=?,memory=?,score=?,error_message=?,judger='PersonalRemote',vjudge_submit_id=?,vjudge_username=NULL,vjudge_password=NULL,gmt_modified=CURRENT_TIMESTAMP WHERE submit_id=?",
                status, time, result == null ? null : result.memory, score,
                result == null ? "Personal remote task stopped before code submission" : null,
                result == null ? null : result.id, id);
        if (updated != 1) throw new IllegalStateException("Personal remote judge update failed");
        judgeContext.updatePersonalRemoteRecords(id, status, cid, (String) judge.get("uid"),
                ((Number) judge.get("pid")).longValue(), judge.get("gid") == null ? null : ((Number) judge.get("gid")).longValue(), score, time);
    }
}
