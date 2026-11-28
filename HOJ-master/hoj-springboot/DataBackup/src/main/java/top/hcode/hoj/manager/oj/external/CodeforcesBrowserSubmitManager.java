package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.annotation.HOJAccessEnum;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.exception.AccessException;
import top.hcode.hoj.mapper.JudgeMapper;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.utils.ShiroUtils;
import top.hcode.hoj.validator.AccessValidator;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CodeforcesBrowserSubmitManager {
    private static final long START_WINDOW = 180_000L;
    private static final long RESULT_WINDOW = 900_000L;
    private static final Pattern CF_KEY = Pattern.compile("cf:contest:([1-9][0-9]{0,18}):([A-Z][A-Z0-9]{0,15})");
    private static final Pattern CPP = Pattern.compile("(?i)^(?:GNU\\s+)?(?:C|G)\\+\\+\\s*(17|20|23)(?:\\b|$)");
    private static final String VERIFIED = "a.platform='CODEFORCES' AND a.verified_identity=a.handle_key AND u.status=0";
    private static final Set<String> BEFORE_SEND = new HashSet<>(Arrays.asList(
            "LOGIN_REQUIRED", "ACCOUNT_MISMATCH", "SOURCE_UNAVAILABLE", "SOURCE_ACCOUNT_UNREADABLE",
            "SOURCE_FORM_UNAVAILABLE", "SOURCE_HISTORY_UNAVAILABLE", "LANGUAGE_UNAVAILABLE", "BRIDGE_FAILED"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccessValidator access;
    private final JudgeMapper judges;
    private final CodeforcesDataClient codeforces;
    private final StringRedisTemplate redis;

    public CodeforcesBrowserSubmitManager(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                          AccessValidator access, JudgeMapper judges,
                                          CodeforcesDataClient codeforces, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
        this.access = access;
        this.judges = judges;
        this.codeforces = codeforces;
        this.redis = redis;
    }

    public static class NewSubmission {
        public String problem;
        public Long accountId;
        public String language;
        public String code;
        public String requestId;
        public String claim;
    }

    public static class BrowserEvent {
        public String event;
        public String handle;
        public String language;
        public Long baselineId;
        public Long remoteId;
        public String codeHash;
        public String errorCode;
    }

    protected long now() { return System.currentTimeMillis(); }
    protected AccountProfile actor() { return ShiroUtils.getProfile(); }

    public Map<String, Object> create(NewSubmission input) {
        AccountProfile user = login();
        try {
            access.validateAccess(HOJAccessEnum.PUBLIC_JUDGE);
        } catch (AccessException error) {
            throw new ObjectiveException(403, error.getMessage());
        }
        if (input == null || input.accountId == null || input.accountId <= 0
                || input.problem == null || input.problem.length() > 100 || input.code == null
                || input.code.length() > 262144 || input.code.trim().isEmpty()
                || input.code.getBytes(StandardCharsets.UTF_8).length > 262144
                || input.requestId == null || !input.requestId.matches("[A-Za-z0-9_-]{16,64}")
                || !token(input.claim)) throw new ObjectiveException(400, "题目、源码或提交凭据无效");
        String standard = standard(input.language);
        if (standard == null) throw new ObjectiveException(400, "请选择 C++17、C++20 或 C++23");
        return tx(() -> {
            Map<String, Object> account = account(input.accountId, user.getUid(), true);
            if (account == null) throw new ObjectiveException(403, "请选择本人已验证的 Codeforces 账号");
            Map<String, Object> problem = practice(input.problem);
            long pid = number(problem.get("id"));
            String key = ProblemIdentity.local(pid, input.problem, true);
            if (!CF_KEY.matcher(key).matches()) throw new ObjectiveException(404, "题号无法关联到 Codeforces");
            String codeHash = hashCode(input.code), claimHash = hash(input.claim);
            Map<String, Object> previous = one("SELECT * FROM codeforces_browser_submit WHERE owner_uid=? AND request_id=? FOR UPDATE",
                    user.getUid(), input.requestId);
            if (previous != null) {
                if (pid != number(previous.get("pid")) || input.accountId != number(previous.get("account_id"))
                        || !Objects.equals(claimHash, previous.get("claim_hash"))
                        || !Objects.equals(codeHash, previous.get("code_hash"))
                        || !Objects.equals(standard, previous.get("cpp_standard")))
                    throw conflict("这次请求已经关联到另一份提交");
                return view(previous);
            }
            Timestamp cutoff = time(now() - START_WINDOW);
            if (one("SELECT id FROM codeforces_browser_submit WHERE account_id=? AND "
                            + "state IN ('QUEUED','CLAIMED','SENT') AND updated_at>=? LIMIT 1", input.accountId, cutoff) != null)
                throw conflict("该 CF 账号有提交正在确认，请稍后重试");
            Timestamp at = time(now());
            jdbc.update("INSERT INTO codeforces_browser_submit(owner_uid,account_id,handle_key,pid,problem_key,"
                            + "request_id,claim_hash,code,code_hash,cpp_standard,state,created_at,updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,'QUEUED',?,?)", user.getUid(), input.accountId,
                    account.get("handle_key"), pid, key, input.requestId, claimHash, input.code, codeHash, standard, at, at);
            return view(one("SELECT * FROM codeforces_browser_submit WHERE owner_uid=? AND request_id=?", user.getUid(), input.requestId));
        });
    }

    public Map<String, Object> latest(String problem) {
        String uid = login().getUid();
        long pid = number(practice(problem).get("id"));
        Map<String, Object> result = one("SELECT * FROM codeforces_browser_submit WHERE owner_uid=? AND pid=? ORDER BY id DESC LIMIT 1", uid, pid);
        return result == null ? null : view(result);
    }

    public Map<String, Object> status(long id) {
        Map<String, Object> job = one("SELECT * FROM codeforces_browser_submit WHERE id=? AND owner_uid=?", id, login().getUid());
        if (job == null) throw new ObjectiveException(404, "提交记录不存在");
        return view(job);
    }

    public Map<String, Object> claim(long id, String claim) {
        return tx(() -> {
            Map<String, Object> job = authenticate(id, "claim_hash", claim);
            if (!"QUEUED".equals(job.get("state")) || elapsed(job, "created_at") > START_WINDOW)
                throw conflict("提交凭据已使用或过期");
            String receipt = randomToken();
            jdbc.update("UPDATE codeforces_browser_submit SET state='CLAIMED', receipt_hash=?,updated_at=? WHERE id=?",
                    hash(receipt), time(now()), id);
            Matcher key = CF_KEY.matcher((String) job.get("problem_key"));
            if (!key.matches()) throw conflict("无法识别 CF 题号");
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("receipt", receipt);
            reply.put("handle", job.get("handle_key"));
            reply.put("contest", key.group(1));
            reply.put("index", key.group(2));
            reply.put("standard", job.get("cpp_standard"));
            reply.put("code", job.get("code"));
            reply.put("codeHash", job.get("code_hash"));
            return reply;
        });
    }

    public Map<String, Object> browserEvent(long id, String receipt, BrowserEvent event) {
        if (event == null || event.event == null) throw new ObjectiveException(400, "缺少提交回执");
        try {
            return tx(() -> {
                Map<String, Object> job = authenticate(id, "receipt_hash", receipt);
                String state = (String) job.get("state");
                Timestamp at = time(now());
                switch (event.event) {
                    case "sending":
                        if (!"CLAIMED".equals(state) || elapsed(job, "created_at") > START_WINDOW)
                            throw conflict("本次提交已发送或已过期");
                        if (event.handle == null || !event.handle.equalsIgnoreCase((String) job.get("handle_key"))
                                || event.baselineId == null || event.baselineId < 0
                                || !Objects.equals(job.get("cpp_standard"), standard(event.language)))
                            throw new ObjectiveException(400, "浏览器账号、语言或提交基准不一致");
                        jdbc.update("UPDATE codeforces_browser_submit SET state='SENT',source_language=?,baseline_id=?,"
                                + "sent_at=?,updated_at=? WHERE id=?", event.language.trim(), event.baselineId, at, at, id);
                        break;
                    case "receipt":
                        if (event.remoteId != null && job.get("remote_id") != null
                                && event.remoteId == number(job.get("remote_id"))) return view(job);
                        if (!"SENT".equals(state) && !("NEEDS_REVIEW".equals(state) && job.get("remote_id") == null))
                            throw conflict("此时无法关联 CF 编号");
                        if (event.remoteId == null || event.remoteId <= number(job.get("baseline_id"))
                                || !Objects.equals(job.get("code_hash"), event.codeHash) || elapsed(job, "sent_at") > RESULT_WINDOW)
                            throw new ObjectiveException(400, "CF 编号或源码核对未通过");
                        jdbc.update("UPDATE codeforces_browser_submit SET state='VERIFYING',remote_id=?,error_code=NULL,"
                                + "next_check_at=?,updated_at=? WHERE id=?", event.remoteId, at, at, id);
                        break;
                    case "failed":
                        if (!"CLAIMED".equals(state) || !BEFORE_SEND.contains(event.errorCode)) throw conflict("本次提交已发送");
                        transition(id, "FAILED", event.errorCode);
                        break;
                    case "stopped":
                    case "rejected":
                        String error = event.errorCode;
                        if (!"SENT".equals(state) || !("stopped".equals(event.event)
                                ? BEFORE_SEND.contains(error) : "DUPLICATE_CODE".equals(error))) throw conflict("无法确认这次提交已停止");
                        transition(id, "FAILED", error);
                        break;
                    case "review":
                        if ("SENT".equals(state)) transition(id, "NEEDS_REVIEW", "RECEIPT_UNKNOWN");
                        break;
                    default: throw new ObjectiveException(400, "未知的提交回执");
                }
                return view(one("SELECT * FROM codeforces_browser_submit WHERE id=?", id));
            });
        } catch (DuplicateKeyException error) {
            throw conflict("该 CF 提交编号已关联另一条记录");
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void verifyPending() {
        Timestamp at = time(now());
        jdbc.update("UPDATE codeforces_browser_submit SET state='FAILED',error_code='EXPIRED',code='',updated_at=? "
                + "WHERE state IN ('QUEUED','CLAIMED') AND created_at<? LIMIT 100", at, time(now() - START_WINDOW));
        jdbc.update("UPDATE codeforces_browser_submit SET state='NEEDS_REVIEW',error_code='RECEIPT_UNKNOWN',updated_at=? "
                + "WHERE state='SENT' AND sent_at<? LIMIT 100", at, time(now() - START_WINDOW));
        jdbc.update("UPDATE codeforces_browser_submit SET code='' WHERE state='NEEDS_REVIEW' AND sent_at<? AND code<>'' LIMIT 100",
                time(now() - RESULT_WINDOW));
        Map<String, Object> task = one("SELECT * FROM codeforces_browser_submit WHERE state='VERIFYING' AND next_check_at<=? "
                + "ORDER BY next_check_at,id LIMIT 1", at);
        if (task == null) return;
        long id = number(task.get("id"));
        if (jdbc.update("UPDATE codeforces_browser_submit SET next_check_at=? WHERE id=? AND state='VERIFYING' AND next_check_at<=?",
                time(now() + 35_000L), id, at) != 1) return;
        if (elapsed(task, "sent_at") > RESULT_WINDOW) {
            jdbc.update("UPDATE codeforces_browser_submit SET state='NEEDS_REVIEW',error_code='RESULT_UNAVAILABLE',updated_at=? "
                    + "WHERE state='VERIFYING' AND id=?", at, id);
            return;
        }
        try {
            RemoteReadRateGate.acquire(redis, RemoteReadRateGate.SHARED_SYNC_KEY, null, "");
            acceptPublicResult(id, codeforces.recentBrowserSubmissions((String) task.get("handle_key")));
        } catch (ObjectiveException error) {
            // The saved checkpoint remains due for a later check.
        }
    }

    void acceptPublicResult(long id, JsonNode rows) {
        if (rows == null || !rows.isArray()) return;
        Map<String, Object> pending = one("SELECT * FROM codeforces_browser_submit WHERE id=? AND state='VERIFYING'", id);
        if (pending == null) return;
        tx(() -> {
            Map<String, Object> account = account(number(pending.get("account_id")), (String) pending.get("owner_uid"), true);
            Map<String, Object> job = one("SELECT * FROM codeforces_browser_submit WHERE id=? AND state='VERIFYING' FOR UPDATE", id);
            if (job == null) return null;
            if (account == null || !Objects.equals(account.get("handle_key"), job.get("handle_key"))) {
                transition(id, "NEEDS_REVIEW", "ACCOUNT_CHANGED");
                return null;
            }
            for (JsonNode row : rows) {
                if (row.path("id").asLong(-1) != number(job.get("remote_id"))) continue;
                if (!matches(job, row)) {
                    transition(id, "NEEDS_REVIEW", "RECEIPT_MISMATCH");
                    return null;
                }
                String verdict = row.path("verdict").asText("");
                Integer status = CodeforcesSyncManager.verdict(verdict);
                if ("INPUT_PREPARATION_CRASHED".equals(verdict)) status = 4;
                if (status != null && status != 7) finish(job, account, row, verdict, status);
                else jdbc.update("UPDATE codeforces_browser_submit SET verdict='TESTING',updated_at=? WHERE id=?", time(now()), id);
                break;
            }
            return null;
        });
    }

    private boolean matches(Map<String, Object> job, JsonNode row) {
        JsonNode members = row.path("author").path("members");
        long submitted = row.path("creationTimeSeconds").asLong(0);
        long sent = ((Date) job.get("sent_at")).getTime() / 1000L;
        if (!members.isArray() || members.size() != 1 || !members.get(0).path("handle").asText().equalsIgnoreCase((String) job.get("handle_key"))
                || !"PRACTICE".equals(row.path("author").path("participantType").asText())
                || !Objects.equals(job.get("cpp_standard"), standard(row.path("programmingLanguage").asText()))
                || submitted < sent - 5 || submitted > sent + START_WINDOW / 1000L) return false;
        try {
            return Objects.equals(job.get("problem_key"), ProblemIdentity.codeforces(row.path("problem")));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private void finish(Map<String, Object> job, Map<String, Object> account, JsonNode row, String verdict, int status) {
        long id = number(job.get("id")), pid = number(job.get("pid")), remoteId = number(job.get("remote_id"));
        String uid = (String) job.get("owner_uid");
        Map<String, Object> problem = one("SELECT problem_id FROM problem WHERE id=? AND auth=1 AND gid IS NULL AND is_remote=1", pid);
        if (problem == null || !Objects.equals(job.get("problem_key"), ProblemIdentity.local(pid, (String) problem.get("problem_id"), true))) {
            transition(id, "NEEDS_REVIEW", "PROBLEM_CHANGED");
            return;
        }
        Map<String, Object> prior = one("SELECT j.submit_id,j.uid,j.code FROM codeforces_sync_projection m JOIN judge j ON j.submit_id=m.submit_id "
                        + "WHERE m.kind='PRACTICE' AND m.scope_id=0 AND m.slot_id=? AND m.external_id=? FOR UPDATE", pid, remoteId);
        long submitId;
        Integer runtime = metric(row, "timeConsumedMillis", 1);
        Integer memory = metric(row, "memoryConsumedBytes", 1024);
        int length = ((String) job.get("code")).getBytes(StandardCharsets.UTF_8).length;
        if (prior != null) {
            if (!uid.equals(prior.get("uid")) || prior.get("code") != null && !prior.get("code").equals("")
                    && !Objects.equals(job.get("code_hash"), hashCode((String) prior.get("code")))) {
                transition(id, "NEEDS_REVIEW", "RECEIPT_MISMATCH");
                return;
            }
            submitId = number(prior.get("submit_id"));
            jdbc.update("UPDATE judge SET code=?,language=?,status=?,time=?,memory=?,length=? WHERE submit_id=?",
                    job.get("code"), job.get("source_language"), status, runtime, memory, length, submitId);
        } else {
            Judge judge = new Judge().setPid(pid).setDisplayPid((String) problem.get("problem_id")).setCid(0L).setCpid(0L)
                    .setUid(uid).setUsername((String) account.get("username")).setSubmitTime(time(row.path("creationTimeSeconds").asLong()*1000L))
                    .setStatus(status).setCode((String) job.get("code")).setLanguage((String) job.get("source_language"))
                    .setTime(runtime).setMemory(memory).setLength(length)
                    .setShare(false).setJudger(CodeforcesSyncManager.JUDGER).setVjudgeSubmitId(remoteId);
            judges.insert(judge);
            submitId = judge.getSubmitId();
            jdbc.update("INSERT INTO codeforces_sync_projection(kind,scope_id,slot_id,external_id,submit_id) VALUES('PRACTICE',0,?,?,?)",
                    pid, remoteId, submitId);
        }
        jdbc.update("UPDATE codeforces_browser_submit SET state='COMPLETED',verdict=?,submit_id=?,code='',updated_at=? WHERE id=?",
                verdict, submitId, time(now()), id);
    }

    private Map<String, Object> authenticate(long id, String credentialColumn, String credential) {
        if (!token(credential)) throw new ObjectiveException(401, "提交凭据无效");
        Map<String, Object> seed = one("SELECT account_id,owner_uid FROM codeforces_browser_submit WHERE id=?", id);
        if (seed == null) throw new ObjectiveException(401, "提交凭据无效");
        Map<String, Object> account = account(number(seed.get("account_id")), (String) seed.get("owner_uid"), true);
        Map<String, Object> job = one("SELECT * FROM codeforces_browser_submit WHERE id=? FOR UPDATE", id);
        if (account == null || job == null || !Objects.equals(job.get("handle_key"), account.get("handle_key"))
                || !Objects.equals(job.get(credentialColumn), hash(credential)))
            throw new ObjectiveException(401, "提交凭据无效或 CF 账号归属已变更");
        return job;
    }

    private Map<String, Object> account(long id, String uid, boolean lock) {
        return one("SELECT a.handle_key,u.username FROM external_account a JOIN user_info u ON u.uuid=a.uid "
                + "WHERE a.id=? AND a.uid=? AND " + VERIFIED + (lock ? " FOR UPDATE" : ""), id, uid);
    }

    private Map<String, Object> practice(String display) {
        Map<String, Object> row = display == null ? null : one("SELECT id FROM problem WHERE problem_id=? AND auth=1 "
                + "AND gid IS NULL AND is_remote=1 AND problem_id LIKE 'CF-%' LIMIT 1", display);
        if (row == null) throw new ObjectiveException(404, "此入口仅支持公开 Codeforces 练习题");
        return row;
    }

    private Map<String, Object> view(Map<String, Object> job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(job.get("id")));
        result.put("state", job.get("state"));
        result.put("error", job.get("error_code"));
        result.put("sendStarted", job.get("sent_at") != null);
        result.put("remoteId", job.get("remote_id") == null ? null : String.valueOf(job.get("remote_id")));
        result.put("submitId", job.get("submit_id") == null ? null : String.valueOf(job.get("submit_id")));
        result.put("verdict", job.get("verdict"));
        return result;
    }

    private void transition(long id, String state, String error) {
        jdbc.update("UPDATE codeforces_browser_submit SET state=?, error_code=?, updated_at=?,code=IF(?='FAILED','',code) WHERE id=?",
                state, error, time(now()), state, id);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AccountProfile login() {
        AccountProfile user = actor();
        if (user == null) throw new ObjectiveException(401, "请先登录 HOJ");
        return user;
    }

    private long elapsed(Map<String, Object> row, String dateColumn) { return now() - ((Date) row.get(dateColumn)).getTime(); }
    private <T> T tx(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    private static Timestamp time(long millis) { return new Timestamp(millis); }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static Integer metric(JsonNode result, String name, int divisor) {
        long value = result.path(name).asLong(-1);
        return value >= 0 && value / divisor <= Integer.MAX_VALUE ? (int) (value / divisor) : null;
    }
    private static boolean token(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static String hash(String value) { return RemoteCatalogManager.hash(value); }
    static String hashCode(String code) { return hash(code.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\\n+$", "")); }
    private static String randomToken() { return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""); }
    private static String standard(String label) {
        if (label == null || label.length() > 128) return null;
        Matcher match = CPP.matcher(label.trim());
        return match.find() ? match.group(1) : null;
    }
    private static ObjectiveException conflict(String message) { return new ObjectiveException(409, message); }
}
