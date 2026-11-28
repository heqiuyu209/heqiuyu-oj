package top.hcode.hoj.manager.oj.external;

import org.apache.shiro.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.annotation.HOJAccessEnum;
import top.hcode.hoj.common.exception.*;
import top.hcode.hoj.exception.AccessException;
import top.hcode.hoj.manager.oj.BeforeDispatchInitManager;
import top.hcode.hoj.manager.oj.PaperManager;
import top.hcode.hoj.mapper.JudgeMapper;
import top.hcode.hoj.pojo.dto.CodeforcesSyncDTO;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.utils.Constants;
import top.hcode.hoj.utils.RedisUtils;
import top.hcode.hoj.utils.ShiroUtils;
import top.hcode.hoj.validator.AccessValidator;
import top.hcode.hoj.validator.GroupValidator;

import java.sql.Timestamp;
import java.util.*;

/** Applies completed public Codeforces snapshots to HOJ's existing result and ranking readers. */
@Component
public class CodeforcesSyncManager {
    public static final String JUDGER = "CodeforcesSync";
    private static final Logger log = LoggerFactory.getLogger(CodeforcesSyncManager.class);
    private static final long REFRESH_INTERVAL = 60_000L;
    private static final String VERIFIED = "a.platform='CODEFORCES' AND a.verified_identity=a.handle_key AND u.status=0";
    private static final String CF = "p.is_remote=1 AND p.problem_id LIKE 'CF-%'";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JudgeMapper judges;
    private final ExternalAccountManager accounts;
    private final CodeforcesPracticeRecords practices;
    private final BeforeDispatchInitManager submission;
    private final PaperManager papers;
    private final GroupValidator groups;
    private final AccessValidator access;
    private final RedisUtils redis;

    public CodeforcesSyncManager(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, JudgeMapper judges,
                                 ExternalAccountManager accounts, CodeforcesPracticeRecords practices,
                                 BeforeDispatchInitManager submission, PaperManager papers, GroupValidator groups,
                                 AccessValidator access, RedisUtils redis) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.judges = judges;
        this.accounts = accounts;
        this.practices = practices;
        this.submission = submission;
        this.papers = papers;
        this.groups = groups;
        this.access = access;
        this.redis = redis;
    }

    public static boolean isCodeforcesProblem(Problem problem) {
        return problem != null && Boolean.TRUE.equals(problem.getIsRemote())
                && problem.getProblemId() != null && problem.getProblemId().startsWith("CF-")
                && ProblemIdentity.local(0, problem.getProblemId(), true).startsWith("cf:contest:");
    }

    public Map<String, Object> request(long id, CodeforcesSyncDTO dto) {
        AccountProfile actor = ShiroUtils.getProfile();
        if (actor == null) throw new ObjectiveException(401, "请先登录");
        if (dto == null) throw new ObjectiveException(400, "缺少同步目标");
        String problem = practices.requireProblem(dto.getProblem()).problem;
        Scope scope = authorize(dto, actor, problem);
        transactions.execute(status -> {
            Map<String, Object> account = lockedAccount(id, actor.getUid());
            if (account == null) throw new ObjectiveException(404, "请选择本人已验证的 Codeforces 账号");
            if (scope != null) bind(scope, actor.getUid(), id, (String) account.get("handle_key"));
            Date success = (Date) account.get("last_success_at");
            long previous = Math.max(((Date) account.get("last_requested_at")).getTime(), success == null ? 0 : success.getTime());
            Timestamp next = new Timestamp(Math.max(System.currentTimeMillis(), previous + REFRESH_INTERVAL));
            // Leave a running scan and its retry/backoff alone.
            jdbc.update("UPDATE external_sync_job SET next_run_at=? WHERE account_id=? AND stage='DONE' "
                    + "AND (next_run_at IS NULL OR next_run_at>?)", next, id, next);
            return null;
        });
        return withState(accounts.mine(), scope == null ? problem : null);
    }

    private Scope authorize(CodeforcesSyncDTO dto, AccountProfile actor, String problem) {
        Judge target = new Judge();
        String displayId = dto.getDisplayId();
        Long group = dto.getGroupId();
        if (dto.getRunId() != null) {
            if (dto.getContestId() != null || dto.getTrainingId() != null || group != null)
                throw new ObjectiveException(400, "同步目标冲突");
            papers.requireCodeforcesSyncItem(dto.getRunId(), dto.getItemKey(), problem);
            return new Scope("PAPER", dto.getRunId());
        }
        try {
            if (dto.getContestId() != null) {
                if (dto.getTrainingId() != null) throw new ObjectiveException(400, "同步目标冲突");
                access.validateAccess(HOJAccessEnum.CONTEST_JUDGE);
                submission.prepareContestResultSync(dto.getContestId(), displayId, actor, target);
                requireTarget(target, problem, actor, false);
                return new Scope("CONTEST", dto.getContestId());
            }
            access.validateAccess(group == null ? HOJAccessEnum.PUBLIC_JUDGE : HOJAccessEnum.GROUP_JUDGE);
            if (dto.getTrainingId() != null) {
                submission.prepareTrainingSubmission(dto.getTrainingId(), displayId, actor, target);
                requireTarget(target, problem, actor, false);
                return new Scope("TRAINING", dto.getTrainingId());
            }
            submission.prepareCommonSubmission(problem, group, target);
            requireTarget(target, problem, actor, target.getGid() == null);
            return target.getGid() == null ? null : new Scope("GROUP", target.getGid());
        } catch (StatusNotFoundException e) {
            throw new ObjectiveException(404, e.getMessage());
        } catch (StatusFailException e) {
            throw new ObjectiveException(400, e.getMessage());
        } catch (StatusForbiddenException | StatusAccessDeniedException | AccessException e) {
            throw new ObjectiveException(403, e.getMessage());
        }
    }

    private void requireTarget(Judge target, String problem, AccountProfile actor, boolean publicOnly) {
        if (!problem.equals(target.getDisplayPid()) || jdbc.queryForObject("SELECT COUNT(*) FROM problem p WHERE p.id=? AND "
                + CF + (publicOnly ? " AND p.auth=1 AND p.gid IS NULL" : " AND p.auth<>2"),
                Integer.class, target.getPid()) != 1)
            throw new ObjectiveException(404, "当前题目不支持 Codeforces 结果同步");
        if (target.getGid() != null && !SecurityUtils.getSubject().hasRole("root")
                && !groups.isGroupMember(actor.getUid(), target.getGid())) throw new ObjectiveException(403, "请先加入所属团队");
    }

    private void bind(Scope scope, String uid, long accountId, String handle) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT account_id,handle_key FROM codeforces_sync_scope WHERE kind=? AND scope_id=? AND uid=? FOR UPDATE",
                scope.kind, scope.id, uid);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO codeforces_sync_scope(kind,scope_id,uid,account_id,handle_key) VALUES(?,?,?,?,?)",
                    scope.kind, scope.id, uid, accountId, handle);
        } else {
            Map<String, Object> previous = existing.get(0);
            if (!handle.equals(previous.get("handle_key"))) throw new ObjectiveException(409, "此处已关联另一个 Codeforces 账号，请选择原账号同步");
            if (previous.get("account_id") != null && accountId == number(previous.get("account_id"))) return;
            jdbc.update("UPDATE codeforces_sync_scope SET account_id=? WHERE kind=? AND scope_id=? AND uid=?",
                    accountId, scope.kind, scope.id, uid);
        }
        jdbc.update("DELETE FROM codeforces_sync_cursor WHERE account_id=?", accountId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> withState(Map<String, Object> snapshot, String publicProblem) {
        for (Map<String, Object> account : (List<Map<String, Object>>) snapshot.get("accounts")) {
            if (!"CODEFORCES".equals(account.get("platform")) || !Boolean.TRUE.equals(account.get("verified"))) continue;
            List<Map<String, Object>> state = jdbc.queryForList(
                    "SELECT a.last_success_at,c.synced_at,j.next_run_at FROM external_account a "
                            + "JOIN external_sync_job j ON j.account_id=a.id "
                            + "LEFT JOIN codeforces_sync_cursor c ON c.account_id=a.id WHERE a.id=? AND a.uid=?",
                    account.get("id"), snapshot.get("uid"));
            if (state.isEmpty()) continue;
            Date synced = (Date) state.get(0).get("synced_at");
            Date fetched = (Date) state.get(0).get("last_success_at");
            Date next = (Date) state.get(0).get("next_run_at");
            account.put("projectionAt", synced);
            account.put("projectionPending", fetched != null && (synced == null || fetched.after(synced)));
            account.put("scheduledSoon", next != null && next.getTime() <= System.currentTimeMillis() + REFRESH_INTERVAL + 1000L);
        }
        return publicProblem == null ? snapshot : practices.withLatest(snapshot, practices.requireProblem(publicProblem));
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void projectReadyAccounts() {
        List<Long> pending = jdbc.queryForList("SELECT a.id FROM external_account a JOIN user_info u ON u.uuid=a.uid "
                + "JOIN external_sync_job j ON j.account_id=a.id LEFT JOIN codeforces_sync_cursor c ON c.account_id=a.id "
                + "WHERE " + VERIFIED + " AND j.stage='DONE' AND a.last_success_at IS NOT NULL "
                + "AND (c.synced_at IS NULL OR c.synced_at<a.last_success_at) ORDER BY a.last_success_at,a.id LIMIT 16", Long.class);
        for (long id : pending) {
            try {
                transactions.execute(status -> { project(id); return null; });
            } catch (RuntimeException error) {
                log.warn("Codeforces result projection failed for external account {}", id, error);
            }
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void refreshActiveParticipation() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Timestamp threshold = new Timestamp(now.getTime() - REFRESH_INTERVAL);
        Timestamp recentEnd = new Timestamp(now.getTime() - 10 * REFRESH_INTERVAL);
        jdbc.update("UPDATE external_sync_job j JOIN external_account a ON a.id=j.account_id JOIN user_info u ON u.uuid=a.uid "
                        + "SET j.next_run_at=? WHERE j.stage='DONE' AND (j.next_run_at IS NULL OR j.next_run_at>?) AND "
                        + VERIFIED + " AND a.last_requested_at<=? AND (a.last_success_at IS NULL OR a.last_success_at<=?) AND EXISTS ("
                        + "SELECT 1 FROM codeforces_sync_scope s LEFT JOIN contest c ON s.kind='CONTEST' AND c.id=s.scope_id "
                        + "LEFT JOIN paper_run r ON s.kind='PAPER' AND r.id=s.scope_id "
                        + "LEFT JOIN contest mc ON mc.id=r.contest_id LEFT JOIN contest_virtual_run v ON v.id=r.virtual_run_id "
                        + "WHERE s.account_id=a.id AND ((c.start_time<=? AND c.end_time>?) "
                        + "OR (r.state='IN_PROGRESS' AND COALESCE(mc.end_time,v.ends_at,?)>?) "
                        + "OR (r.state='FINISHED' AND r.gmt_modified>?)))",
                now, now, threshold, threshold, now, recentEnd, now, recentEnd, recentEnd);
    }

    private Map<String, Object> lockedAccount(long id, String uid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.*,u.username,u.realname FROM external_account a JOIN user_info u ON u.uuid=a.uid WHERE a.id=? AND "
                        + VERIFIED + (uid == null ? "" : " AND a.uid=?") + " FOR UPDATE", uid == null ? new Object[]{id} : new Object[]{id, uid});
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void project(long id) {
        Map<String, Object> account = lockedAccount(id, null);
        if (account == null || account.get("last_success_at") == null) return;
        List<String> stage = jdbc.queryForList("SELECT stage FROM external_sync_job WHERE account_id=? FOR UPDATE", String.class, id);
        if (!stage.contains("DONE")) return;
        Timestamp fetched = (Timestamp) account.get("last_success_at");
        List<Timestamp> applied = jdbc.queryForList("SELECT synced_at FROM codeforces_sync_cursor WHERE account_id=? FOR UPDATE", Timestamp.class, id);
        if (!applied.isEmpty() && !applied.get(0).before(fetched)) return;
        Set<Long> changedContests = new HashSet<>();
        for (Target target : targets(id)) {
            if (target.problemId == null || !target.problemId.startsWith("CF-")) continue;
            String key = ProblemIdentity.local(target.pid, target.problemId, true);
            if (!key.startsWith("cf:contest:")) continue;
            List<Map<String, Object>> receipts = jdbc.queryForList(
                    "SELECT s.external_id,s.submitted_at,s.verdict,m.submit_id,j.uid AS judge_uid,j.status AS judge_status,j.score AS judge_score "
                            + "FROM external_submission s LEFT JOIN codeforces_sync_projection m ON m.external_id=s.external_id "
                            + "AND m.kind=? AND m.scope_id=? AND m.slot_id=? LEFT JOIN judge j ON j.submit_id=m.submit_id WHERE s.account_id=? "
                            + "AND s.problem_key=? AND s.individual=1 AND s.submitted_at>=? AND s.submitted_at<? "
                            + "ORDER BY s.submitted_at,s.external_id",
                    target.kind, target.scopeId, target.slot, id, key, firstSecond(target.start), firstSecond(target.end));
            for (Map<String, Object> receipt : receipts) apply(account, target, receipt, changedContests);
            if (!receipts.isEmpty() && ("PRACTICE".equals(target.kind) || "GROUP".equals(target.kind)))
                linkTraining((String) account.get("uid"), target);
        }
        int updated = jdbc.update("UPDATE codeforces_sync_cursor SET synced_at=? WHERE account_id=?", fetched, id);
        if (updated == 0) jdbc.update("INSERT INTO codeforces_sync_cursor(account_id,synced_at) VALUES(?,?)", id, fetched);
        if (!changedContests.isEmpty()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                String prefix = Constants.Contest.CONTEST_RANK_CAL_RESULT_CACHE.getName();
                for (long cid : changedContests) {
                    try {
                        redis.del(prefix + "_" + cid, prefix + "_contains_after_" + cid);
                    } catch (RuntimeException error) {
                        log.warn("Could not invalidate cached Codeforces contest standings for {}", cid, error);
                    }
                }
            }
        });
    }

    // Codeforces reports full Unix seconds. Ceil both bounds preserves start-inclusive / end-exclusive.
    static long firstSecond(long millis) {
        long floor = Math.floorDiv(millis, 1000L);
        return floor + (Math.floorMod(millis, 1000L) == 0 ? 0 : 1);
    }

    private List<Target> targets(long account) {
        List<Target> result = new ArrayList<>();
        Set<String> keys = new HashSet<>(jdbc.queryForList("SELECT DISTINCT problem_key FROM external_submission "
                + "WHERE account_id=? AND individual=1", String.class, account));
        jdbc.queryForList("SELECT p.id,p.problem_id FROM problem p WHERE p.auth=1 AND p.gid IS NULL AND "
                + CF).forEach(p -> {
                    long pid = number(p.get("id"));
                    String displayId = (String) p.get("problem_id");
                    if (keys.contains(ProblemIdentity.local(pid, displayId, true)))
                        result.add(new Target("PRACTICE", 0, pid, pid, displayId, null));
                });
        List<Map<String, Object>> scoped = jdbc.queryForList("SELECT kind,scope_id,uid FROM codeforces_sync_scope WHERE account_id=?", account);
        for (Map<String, Object> row : scoped) {
            long id = number(row.get("scope_id"));
            String uid = (String) row.get("uid");
            switch ((String) row.get("kind")) {
                case "CONTEST": result.addAll(contestTargets(id)); break;
                case "PAPER": result.addAll(papers.codeforcesSyncTargets(id, uid)); break;
                case "TRAINING":
                    jdbc.queryForList("SELECT p.id,p.problem_id,t.gid FROM training t JOIN training_problem tp ON tp.tid=t.id "
                                    + "JOIN problem p ON p.id=tp.pid WHERE t.id=? AND t.status=1 AND t.gid IS NOT NULL AND p.auth=1 AND " + CF, id)
                            .forEach(p -> result.add(groupTarget(p)));
                    break;
                case "GROUP":
                    jdbc.queryForList("SELECT p.id,p.problem_id,p.gid FROM problem p WHERE p.gid=? AND p.auth=1 AND " + CF, id)
                            .forEach(p -> result.add(groupTarget(p)));
                    break;
                default: throw new IllegalStateException("Unknown Codeforces sync scope");
            }
        }
        return result;
    }

    private Target groupTarget(Map<String, Object> problem) {
        long pid = number(problem.get("id")), gid = number(problem.get("gid"));
        return new Target("GROUP", gid, pid, pid, (String) problem.get("problem_id"), gid);
    }

    private List<Target> contestTargets(long id) {
        return jdbc.query("SELECT cp.id,cp.pid,cp.display_id,p.problem_id,p.io_score,COALESCE(p.gid,c.gid) AS gid,"
                        + "c.start_time,c.end_time,c.type FROM contest c JOIN contest_problem cp ON cp.cid=c.id "
                        + "JOIN problem p ON p.id=cp.pid WHERE c.id=? AND p.auth<>2 AND " + CF
                        + " AND NOT EXISTS (SELECT 1 FROM contest_paper m WHERE m.contest_id=c.id)",
                (rs, row) -> {
                    Number gid = (Number) rs.getObject("gid");
                    Target target = new Target("CONTEST", id, rs.getLong("id"), rs.getLong("pid"), rs.getString("problem_id"), gid == null ? null : gid.longValue());
                    target.start = rs.getTimestamp("start_time").getTime();
                    target.end = rs.getTimestamp("end_time").getTime();
                    target.displayId = rs.getString("display_id");
                    if (rs.getInt("type") == 1) {
                        int score = rs.getInt("io_score");
                        target.maximum = rs.wasNull() ? 100 : score;
                    }
                    return target;
                }, id);
    }

    private void apply(Map<String, Object> account, Target target, Map<String, Object> receipt, Set<Long> changedContests) {
        Integer status = verdict((String) receipt.get("verdict"));
        if (status == null) return;
        String uid = (String) account.get("uid");
        long externalId = number(receipt.get("external_id"));
        Date time = new Timestamp(Math.multiplyExact(number(receipt.get("submitted_at")), 1000));
        long cid = "CONTEST".equals(target.kind) ? target.scopeId : 0;
        long cpid = cid != 0 ? target.slot : 0;
        Long paperId = "PAPER".equals(target.kind) ? target.scopeId : null;
        Integer recordStatus = contestStatus(status);
        Integer score = target.maximum == null || recordStatus == null ? null : status == 0 ? target.maximum : 0;
        Long submitId;
        if (receipt.get("submit_id") != null) {
            if (!uid.equals(receipt.get("judge_uid")) || status.equals(receipt.get("judge_status"))
                    && Objects.equals(score, receipt.get("judge_score"))) return;
            submitId = number(receipt.get("submit_id"));
            jdbc.update("UPDATE judge SET status=?,score=? WHERE submit_id=?", status, score, submitId);
        } else {
            List<Long> prior = jdbc.queryForList("SELECT submit_id FROM judge WHERE uid=? AND pid=? AND cid=? AND cpid=? "
                            + "AND vjudge_submit_id=? AND (gid=? OR (gid IS NULL AND ? IS NULL)) AND "
                            + "(paper_run_id=? OR (paper_run_id IS NULL AND ? IS NULL)) AND COALESCE(judger,'')<>? LIMIT 1",
                    Long.class, uid, target.pid, cid, cpid, externalId, target.gid, target.gid, paperId, paperId, JUDGER);
            if (!prior.isEmpty()) return;
            Judge judge = new Judge().setPid(target.pid).setDisplayPid(target.problemId).setGid(target.gid).setCid(cid).setCpid(cpid)
                    .setUid(uid).setUsername((String) account.get("username")).setSubmitTime(time).setStatus(status).setScore(score)
                    .setCode("").setShare(false).setJudger(JUDGER).setVjudgeSubmitId(externalId)
                    .setPaperRunId(paperId).setPaperSnapshotId(paperId == null ? null : target.slot);
            judges.insert(judge);
            submitId = judge.getSubmitId();
            jdbc.update("INSERT INTO codeforces_sync_projection(kind,scope_id,slot_id,external_id,submit_id) VALUES(?,?,?,?,?)",
                    target.kind, target.scopeId, target.slot, externalId, submitId);
            if (cid != 0) jdbc.update("INSERT INTO contest_record(cid,pid,cpid,uid,username,realname,display_id,submit_id,submit_time,time) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?)", cid, target.pid, cpid, uid, account.get("username"),
                    account.get("realname"), target.displayId, submitId, time, (time.getTime() - target.start) / 1000);
        }
        if (cid != 0) {
            jdbc.update("UPDATE contest_record SET status=?,score=? WHERE submit_id=?", recordStatus, score, submitId);
            changedContests.add(cid);
        }
    }

    private void linkTraining(String uid, Target target) {
        jdbc.update("INSERT INTO training_record(tid,tpid,pid,uid,submit_id) "
                        + "SELECT t.id,tp.id,tp.pid,j.uid,j.submit_id FROM judge j JOIN training_problem tp ON tp.pid=j.pid JOIN training t ON t.id=tp.tid "
                        + "WHERE j.uid=? AND j.pid=? AND j.cid=0 AND j.paper_run_id IS NULL AND j.judger=? "
                        + "AND t.status=1 AND t.auth='Private' AND (t.gid=j.gid OR (t.gid IS NULL AND j.gid IS NULL)) "
                        + "AND (j.gid=? OR (j.gid IS NULL AND ? IS NULL)) "
                        + "AND EXISTS (SELECT 1 FROM training_register tr WHERE tr.tid=t.id AND tr.uid=j.uid AND tr.status=1) "
                        + "AND NOT EXISTS (SELECT 1 FROM training_record tr WHERE tr.tpid=tp.id AND tr.submit_id=j.submit_id)",
                uid, target.pid, JUDGER, target.gid, target.gid);
    }

    static Integer verdict(String value) {
        if (value == null) return null;
        switch (value) {
            case "OK": return 0;
            case "WRONG_ANSWER": case "CHALLENGED": return -1;
            case "COMPILATION_ERROR": return -2;
            case "TIME_LIMIT_EXCEEDED": return 1;
            case "MEMORY_LIMIT_EXCEEDED": return 2;
            case "RUNTIME_ERROR": case "IDLENESS_LIMIT_EXCEEDED": case "SECURITY_VIOLATED": return 3;
            case "TESTING": case "PENDING": return 7;
            case "PARTIAL": return 8;
            case "SKIPPED": case "REJECTED": return -4;
            case "FAILED": case "CRASHED": return 4;
            default: return null;
        }
    }

    static Integer contestStatus(int status) {
        if (status == 7) return null;
        if (status == 0) return 1;
        return status == -1 || status >= 1 && status <= 3 ? -1 : 0;
    }

    private static long number(Object value) { return ((Number) value).longValue(); }

    private static final class Scope {
        final String kind;
        final long id;
        Scope(String kind, long id) { this.kind = kind; this.id = id; }
    }

    public static final class Target {
        final String kind;
        final long scopeId;
        final long slot;
        final long pid;
        final String problemId;
        final Long gid;
        long start;
        long end = Long.MAX_VALUE;
        String displayId;
        Integer maximum;
        Target(String kind, long scopeId, long slot, long pid, String problemId, Long gid) {
            this.kind = kind; this.scopeId = scopeId; this.slot = slot; this.pid = pid; this.problemId = problemId; this.gid = gid;
        }
        public static Target paper(long runId, long snapshotId, Problem problem, long from, long until) {
            Target target = new Target("PAPER", runId, snapshotId, problem.getId(), problem.getProblemId(), null);
            target.start = from; target.end = until;
            return target;
        }
    }
}
