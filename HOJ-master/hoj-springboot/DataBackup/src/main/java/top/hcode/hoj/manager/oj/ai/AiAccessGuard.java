package top.hcode.hoj.manager.oj.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.sql.Timestamp;
import java.util.Collections;

@Component
public class AiAccessGuard {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    protected long now() { return System.currentTimeMillis(); }
    public void requireActiveUser(String uid) {
        if (uid == null || jdbc.queryForObject("SELECT COUNT(*) FROM user_info WHERE uuid=? AND status=0", Integer.class, uid) != 1)
            throw new ObjectiveException(403, "当前账号无法使用 AI 助教");
    }
    public boolean contestBlocked(String uid) {
        Timestamp time = new Timestamp(now());
        // Membership comes from persisted server-side activity, never a browser contestId or role exemption.
        return jdbc.queryForObject("SELECT COUNT(*) FROM contest c WHERE c.start_time<=? AND (c.end_time>? OR c.end_time IS NULL) AND ("
                + "EXISTS (SELECT 1 FROM contest_register r WHERE r.cid=c.id AND r.uid=? AND r.status=0) OR "
                + "EXISTS (SELECT 1 FROM ai_contest_entry e WHERE e.cid=c.id AND e.uid=?) OR "
                + "EXISTS (SELECT 1 FROM judge j WHERE j.cid=c.id AND j.uid=? AND j.submit_time>=c.start_time) OR "
                + "EXISTS (SELECT 1 FROM paper_run p WHERE p.contest_id=c.id AND p.uid=?))",
                Integer.class, time, time, uid, uid, uid, uid) > 0
                || jdbc.queryForObject("SELECT COUNT(*) FROM contest_virtual_run WHERE uid=? AND started_at<=? AND ends_at>?",
                Integer.class, uid, time, time) > 0;
    }
    public void recordPublicContestEntry(String uid, Long cid) {
        Timestamp time = new Timestamp(now());
        // Public contests do not require password registration. Reading an authorized statement starts participation.
        jdbc.update("INSERT INTO ai_contest_entry(uid,cid) SELECT ?,id FROM contest WHERE id=? AND auth=0 AND visible=TRUE "
                + "AND start_time<=? AND (end_time>? OR end_time IS NULL) ON DUPLICATE KEY UPDATE cid=VALUES(cid)", uid, cid, time, time);
    }
    public void requireChat(String uid) {
        requireActiveUser(uid);
        if (contestBlocked(uid)) throw new ObjectiveException(403, "你正在参赛，所有 AI 助教入口暂时停用，比赛结束后恢复");
    }
    public void acquire(String uid, String requestId) {
        try {
            String script = "if redis.call('exists',KEYS[1])==1 then return 0 end "
                    + "local n=redis.call('incr',KEYS[2]);if n==1 then redis.call('expire',KEYS[2],60) end "
                    + "if n>6 then return -1 end redis.call('set',KEYS[1],ARGV[1],'EX',90);return 1";
            Long result = redis.execute(new DefaultRedisScript<>(script, Long.class), java.util.Arrays.asList("ai:active:" + uid, "ai:minute:" + uid), requestId);
            if (result == null || result != 1L) throw new ObjectiveException(429, "请等待当前回复完成，每分钟最多发送 6 次");
        } catch (ObjectiveException e) { throw e; }
        catch (Exception e) { throw new ObjectiveException(503, "AI 请求控制暂不可用，请稍后重试"); }
    }
    public void allowRecommendationRead(String uid) {
        requireChat(uid);
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("ai:recommendation-read:" + uid, "1", 5, java.util.concurrent.TimeUnit.SECONDS)))
                throw new ObjectiveException(429, "推荐条件刷新较频繁，请等待几秒后重试");
        } catch (ObjectiveException e) { throw e; }
        catch (Exception e) { throw new ObjectiveException(503, "推荐请求控制暂不可用，请稍后重试"); }
    }
    public void allowKnowledgeRead(String uid) {
        requireChat(uid);
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("ai:knowledge-read:" + uid, "1", 2, java.util.concurrent.TimeUnit.SECONDS)))
                throw new ObjectiveException(429, "检索较频繁，请等待两秒后重试");
        } catch (ObjectiveException e) { throw e; }
        catch (Exception e) { throw new ObjectiveException(503, "知识库请求控制暂不可用，请稍后重试"); }
    }
    public void release(String uid, String requestId) {
        try {
            redis.execute(new DefaultRedisScript<>("if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class), Collections.singletonList("ai:active:" + uid), requestId);
        } catch (Exception ignored) { /* The lock also expires; never log request bodies or keys. */ }
    }
}
