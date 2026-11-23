package top.hcode.hoj.manager.oj;

import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.common.exception.StatusFailException;
import top.hcode.hoj.common.exception.StatusForbiddenException;
import top.hcode.hoj.manager.oj.ai.AiAccessGuard;
import top.hcode.hoj.pojo.dto.VirtualContestDTO;
import top.hcode.hoj.pojo.entity.contest.Contest;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.utils.ShiroUtils;
import top.hcode.hoj.validator.ContestValidator;

import java.sql.Timestamp;
import java.util.*;

/** Personal server-timed practice windows; independent from the original contest's clock. */
@Component
public class VirtualContestManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContestValidator validator;
    @Autowired private AiAccessGuard ai;
    @Autowired private PaperManager papers;

    protected long now() { return System.currentTimeMillis(); }
    protected AccountProfile actor() { return ShiroUtils.getProfile(); }
    protected boolean root() { return SecurityUtils.getSubject().hasRole("root"); }
    private String uid() {
        AccountProfile actor = actor();
        if (actor == null) throw new ObjectiveException(401, "请先登录");
        return actor.getUid();
    }
    private Contest accessible(Long cid, boolean lock) {
        List<Contest> rows = jdbc.query("SELECT * FROM contest WHERE id=?" + (lock ? " FOR UPDATE" : ""), new BeanPropertyRowMapper<>(Contest.class), cid);
        if (rows.isEmpty()) throw new ObjectiveException(404, "比赛不存在");
        Contest contest = rows.get(0);
        // Derive status from the authoritative timestamps, not the scheduled status updater.
        if (contest.getStartTime() == null || contest.getEndTime() == null)
            throw new ObjectiveException(400, "比赛起止时间无效");
        contest.setStatus(now() < contest.getStartTime().getTime() ? -1 : now() < contest.getEndTime().getTime() ? 0 : 1);
        try {
            boolean root = root();
            validator.validateContestAuth(contest, actor(), root);
            if (!root && !Objects.equals(contest.getUid(), uid())) {
                validator.validateJudgeAuth(contest, uid());
                if (Boolean.TRUE.equals(contest.getOpenAccountLimit())
                        && !validator.validateAccountRule(contest.getAccountLimitRule(), actor().getUsername()))
                    throw new ObjectiveException(403, "账号不符合比赛参赛规则");
            }
        } catch (StatusFailException | StatusForbiddenException error) {
            throw new ObjectiveException(403, error.getMessage());
        }
        return contest;
    }
    public Map<String, Object> current(Long cid) {
        String uid = uid(); ai.requireActiveUser(uid); accessible(cid, false);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM contest_virtual_run WHERE uid=? AND cid=? ORDER BY id DESC LIMIT 1", uid, cid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", rows.isEmpty() ? null : view(rows.get(0)));
        return result;
    }
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(Long cid, VirtualContestDTO request) {
        String uid = uid();
        if (request == null || request.getRequestId() == null || !request.getRequestId().matches("[A-Za-z0-9_-]{8,64}"))
            throw new ObjectiveException(400, "请求标识无效");
        // Serialize every start for this user, including starts on different contests. Locking reads
        // precede consistent reads so REPEATABLE READ cannot retain a pre-lock active-window snapshot.
        // No range locks on absent runs: different users must be able to insert concurrently.
        List<Map<String, Object>> users = jdbc.queryForList("SELECT uuid,status FROM user_info WHERE uuid=? FOR UPDATE", uid);
        if (users.isEmpty() || ((Number) users.get(0).get("status")).intValue() != 0)
            throw new ObjectiveException(403, "当前账号无法参加虚拟练习");
        List<Map<String, Object>> duplicates = jdbc.queryForList("SELECT * FROM contest_virtual_run WHERE uid=? AND request_id=?", uid, request.getRequestId());
        if (!duplicates.isEmpty()) {
            if (((Number) duplicates.get(0).get("cid")).longValue() != cid)
                throw new ObjectiveException(409, "请求标识已用于其他比赛");
            accessible(cid, false);
            return result(duplicates.get(0));
        }
        List<Map<String, Object>> active = jdbc.queryForList("SELECT * FROM contest_virtual_run WHERE uid=? AND ends_at>?", uid, new Timestamp(now()));
        if (!active.isEmpty()) {
            if (((Number) active.get(0).get("cid")).longValue() != cid)
                throw new ObjectiveException(409, "你已有进行中的虚拟练习，请在结束后再开始其他比赛");
            accessible(cid, false);
            return result(active.get(0));
        }
        // A locking metadata read also sees administrative changes committed while waiting,
        // independent of the consistent snapshot used for this user's run history.
        Contest contest = accessible(cid, true);
        if (now() < contest.getEndTime().getTime()) throw new ObjectiveException(400, "比赛结束后才能开始虚拟练习");
        boolean mixed = jdbc.queryForObject("SELECT COUNT(*) FROM contest_paper WHERE contest_id=?", Integer.class, cid) > 0;
        if (!mixed && jdbc.queryForObject("SELECT COUNT(*) FROM contest_problem WHERE cid=?", Integer.class, cid) == 0)
            throw new ObjectiveException(400, "该比赛尚无可练习题目");
        ai.requireChat(uid);
        long start = now(), end;
        try {
            long duration = Math.subtractExact(contest.getEndTime().getTime(), contest.getStartTime().getTime());
            if (duration <= 0) throw new ArithmeticException();
            end = Math.addExact(start, duration);
        } catch (ArithmeticException error) { throw new ObjectiveException(400, "比赛时长无效"); }
        jdbc.update("INSERT INTO contest_virtual_run(uid,cid,request_id,title,started_at,ends_at) VALUES(?,?,?,?,?,?)",
                uid, cid, request.getRequestId(), contest.getTitle(), new Timestamp(start), new Timestamp(end));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM contest_virtual_run WHERE uid=? AND request_id=?", uid, request.getRequestId());
        if (mixed) papers.startVirtual(((Number) row.get("id")).longValue());
        return result(row);
    }
    private Map<String, Object> result(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("run", view(row)); return result;
    }
    private Map<String, Object> view(Map<String, Object> row) {
        long serverNow = now(), end = ((Date) row.get("ends_at")).getTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id")); result.put("contestId", row.get("cid")); result.put("title", row.get("title"));
        result.put("startedAt", ((Date) row.get("started_at")).getTime()); result.put("endsAt", end);
        result.put("serverNow", serverNow); result.put("state", serverNow < end ? "RUNNING" : "ENDED");
        List<Long> paperIds = jdbc.queryForList("SELECT id FROM paper_run WHERE virtual_run_id=?", Long.class, row.get("id"));
        result.put("paperRunId", paperIds.isEmpty() ? null : paperIds.get(0));
        result.put("kind", paperIds.isEmpty() ? "PROGRAMMING" : "MIXED");
        return result;
    }
}
