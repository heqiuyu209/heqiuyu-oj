package top.hcode.hoj.remote;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Shared backend/judge journal. Only the holder of a live lease may perform a transition. */
public final class PersonalRemoteTaskStore {
    public enum State { QUEUED, PREPARING, SUBMITTING, RECONCILING, POLLING, NEEDS_REVIEW, COMPLETED, FAILED, CANCELLED }
    public enum Error { ACCOUNT_REVOKED, CREDENTIAL_CHANGED, SOURCE_CHANGED, READ_UNAVAILABLE, POST_UNCERTAIN, MANUAL_REVIEW, AUTH_REQUIRED, SOURCE_BLOCKED, LANGUAGE_CHANGED, AMBIGUOUS_RESULT }
    public static final class Conflict extends IllegalStateException {
        public Conflict(String message) { super(message); }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final LongSupplier clock;
    private static final long LEASE_MILLIS = 90000;

    public PersonalRemoteTaskStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, System::currentTimeMillis);
    }
    public PersonalRemoteTaskStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, LongSupplier clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }
    private <T> T tx(Supplier<T> action) { return transaction.execute(status -> action.get()); }
    Timestamp time() { return new Timestamp(clock.getAsLong()); }
    PlatformTransactionManager transactions(){return transaction.getTransactionManager();}
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static boolean terminal(String state) { return Arrays.asList("COMPLETED", "FAILED", "CANCELLED").contains(state); }
    private Map<String, Object> row(long id, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM personal_remote_task WHERE submit_id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new Conflict("Remote submission does not exist");
        return rows.get(0);
    }
    private Long lockLane(Map<String, Object> task) {
        List<Map<String, Object>> lanes = jdbc.queryForList("SELECT active_submit_id FROM personal_remote_lane WHERE platform=? AND handle_key=? FOR UPDATE", task.get("platform"), task.get("handle_key"));
        if (lanes.size() != 1) throw new Conflict("Remote account lane does not exist");
        Object active = lanes.get(0).get("active_submit_id");
        return active == null ? null : number(active);
    }
    private Map<String, Object> owned(long id, String uid) {
        Map<String, Object> task = row(id, false);
        if (!Objects.equals(uid, task.get("owner_uid")) || jdbc.queryForObject("SELECT COUNT(*) FROM user_info WHERE uuid=? AND status=0", Integer.class, uid) != 1)
            throw new Conflict("Remote submission is not accessible");
        return task;
    }
    /** Only reads the locked task snapshot; later authorization/association locks may take time. */
    void requireLiveLease(Map<String, Object> task) {
        Timestamp until = (Timestamp) task.get("lease_until");
        if (until == null || until.getTime() <= clock.getAsLong()) throw new Conflict("Remote submission lease expired");
    }
    Map<String, Object> leased(long id, String token) {
        Map<String, Object> first = row(id, false);
        lockLane(first);
        Map<String, Object> task = row(id, true);
        if (token == null || !token.equals(task.get("lease_token")) || terminal((String) task.get("state")))
            throw new Conflict("Remote submission lease expired");
        requireLiveLease(task);
        return task;
    }
    public static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint source", e); }
    }
    private static String field(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.length() + ":" + text;
    }
    /** Must share the transaction which creates the judge and its contest/training records. */
    public long enqueue(String uid, long submitId, long accountId, String requestId, String languageId) {
        return enqueue(uid, submitId, accountId, requestId, languageId, null);
    }
    public long enqueue(String uid, long submitId, long accountId, String requestId, String languageId, String requestHash) {
        return enqueue(uid, submitId, accountId, requestId, languageId, requestHash, null);
    }
    public long enqueue(String uid, long submitId, long accountId, String requestId, String languageId, String requestHash, Long expectedCredentialVersion) {
        return enqueue(uid,submitId,accountId,requestId,languageId,requestHash,expectedCredentialVersion,null);
    }
    public long enqueue(String uid,long submitId,long accountId,String requestId,String languageId,String requestHash,Long expectedCredentialVersion,NowCoderJudgeProtocol.Context source) {
        if (requestHash != null && !requestHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid request fingerprint");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Enqueue requires the judge creation transaction");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}") || languageId == null || !languageId.matches("[1-9][0-9]{0,19}"))
            throw new IllegalArgumentException("Invalid remote submission request or language ID");
        Map<String, Object> account = lockForEnqueue(uid, accountId, expectedCredentialVersion);
        List<Map<String, Object>> judges = jdbc.queryForList("SELECT j.*,p.problem_id AS remote_problem,p.is_remote FROM judge j JOIN problem p ON p.id=j.pid WHERE j.submit_id=? AND j.uid=? FOR UPDATE", submitId, uid);
        if (judges.size() != 1) throw new Conflict("Judge does not belong to this user");
        Map<String, Object> judge = judges.get(0);
        String problem = (String) judge.get("remote_problem"), platform = (String) account.get("platform");
        boolean remote = Boolean.TRUE.equals(judge.get("is_remote")) || (judge.get("is_remote") instanceof Number && number(judge.get("is_remote")) == 1);
        boolean supported = "CODEFORCES".equals(platform) && problem != null && problem.matches("(?:CF|GYM)-[0-9]+[A-Za-z][A-Za-z0-9]{0,15}")
                || "ATCODER".equals(platform) && problem != null && problem.matches("AC-[A-Za-z0-9_-]{1,80}")
                || "NOWCODER".equals(platform) && source!=null && problem!=null && problem.matches("NC-[1-9][0-9]{0,11}(?:-[A-Za-z][A-Za-z0-9]{0,15})?");
        if(source!=null && (!"NOWCODER".equals(platform) || !source.userId.equals(account.get("handle_key")) || !source.languageId.equals(languageId)))throw new Conflict("Source snapshot does not match the account");
        String code = (String) judge.get("code");
        if (!remote || !supported || code == null || code.isEmpty() || code.length() > 65535) throw new Conflict("Unsupported remote problem or source");
        String sourceContest = sourceContest(problem);
        if(source!=null && (!source.contestId.equals(sourceContest) || sourceContest.isEmpty() && !source.problemId.equals(new RemoteProblemRef(problem,null).task)))throw new Conflict("Source snapshot does not match the problem");
        String payload = fingerprint(accountId, problem, sourceContest, languageId, judge);
        if(source!=null)payload=bindSource(payload,source);
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT submit_id,payload_hash FROM personal_remote_task WHERE owner_uid=? AND request_id=? FOR UPDATE", uid, requestId);
        if (!existing.isEmpty()) {
            if (!payload.equals(existing.get(0).get("payload_hash")) || number(existing.get(0).get("submit_id")) != submitId) throw new Conflict("Submission request ID already exists");
            return submitId;
        }
        if (number(judge.get("status")) != 5) throw new Conflict("Only a new pending judge can be enqueued");
        Timestamp now = time();
        try {
            jdbc.update("INSERT INTO personal_remote_task(submit_id,owner_uid,account_id,account_ref,platform,handle_key,credential_version,request_id,request_hash,remote_problem,source_contest,language_id,code_hash,payload_hash,state,next_run_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?)",
                    submitId, uid, accountId, accountId, platform, account.get("handle_key"), account.get("credential_version"), requestId, requestHash, problem, sourceContest, languageId, hash(code), payload, now, now, now);
        } catch (DuplicateKeyException e) { throw new Conflict("Submission request ID already exists"); }
        if(source!=null)NowCoderTaskJournal.insertContext(jdbc,submitId,source);
        return submitId;
    }
    /** Acquire this before preparing/inserting the judge so callers retain the worker's lock order. */
    public Map<String, Object> lockForEnqueue(String uid, long accountId, Long expectedCredentialVersion) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Enqueue locks require a transaction");
        List<Map<String, Object>> identity = jdbc.queryForList("SELECT platform,handle_key FROM external_account WHERE id=? AND uid=?", accountId, uid);
        if (identity.size() != 1) throw new Conflict("Verified personal credentials are required");
        jdbc.update("INSERT IGNORE INTO personal_remote_lane(platform,handle_key) VALUES(?,?)", identity.get(0).get("platform"), identity.get(0).get("handle_key"));
        lockLane(identity.get(0)); // Every writer locks lane -> account -> judge/task.
        List<Map<String, Object>> accounts = jdbc.queryForList("SELECT a.*,c.credential_version FROM external_account a JOIN user_info u ON u.uuid=a.uid JOIN remote_user_credential c ON c.account_id=a.id WHERE a.id=? AND a.uid=? AND u.status=0 AND a.verified_identity=a.handle_key AND LEFT(c.credential_ciphertext,3)='s1:' FOR UPDATE", accountId, uid);
        if (accounts.size() != 1) throw new Conflict("Verified personal credentials are required");
        Map<String, Object> account = accounts.get(0);
        if (expectedCredentialVersion != null && number(account.get("credential_version")) != expectedCredentialVersion) throw new Conflict("Remote session authorization changed");
        if (!Objects.equals(identity.get(0).get("platform"), account.get("platform")) || !Objects.equals(identity.get(0).get("handle_key"), account.get("handle_key"))) throw new Conflict("Remote identity changed");
        return account;
    }
    /** Private status, with no credentials, cookies, source, fingerprints, or worker lease. */
    public Map<String, Object> status(long id, String uid) {
        Map<String, Object> task = owned(id, uid), result = new LinkedHashMap<>();
        for (String name : Arrays.asList("submit_id", "platform", "remote_problem", "state", "remote_submit_id", "submit_attempts", "error_code", "created_at", "updated_at")) result.put(name, task.get(name));
        return result;
    }
    /** A platform worker calls this only after acquiring its source-rate gate. */
    public Map<String, Object> claim(String platform) {
        if (!Arrays.asList("CODEFORCES", "ATCODER", "NOWCODER").contains(platform)) throw new IllegalArgumentException("Unsupported remote platform");
        return tx(() -> {
            Timestamp now = time();
            List<Map<String, Object>> candidates = jdbc.queryForList("SELECT t.* FROM personal_remote_task t JOIN personal_remote_lane l ON l.platform=t.platform AND l.handle_key=t.handle_key WHERE t.platform=? AND t.state IN ('QUEUED','PREPARING','SUBMITTING','RECONCILING','POLLING') AND t.next_run_at<=? AND (t.lease_until IS NULL OR t.lease_until<=?) AND (l.active_submit_id IS NULL OR l.active_submit_id=t.submit_id) ORDER BY t.created_at,t.submit_id LIMIT 20", platform, now, now);
            for (Map<String, Object> candidate : candidates) {
                // FOR UPDATE is a current read under MySQL REPEATABLE READ. A subsequent plain
                // SELECT could reuse the pre-lock snapshot and allow another task in this lane.
                Long active = lockLane(candidate);
                Map<String, Object> task = row(number(candidate.get("submit_id")), true);
                long id = number(task.get("submit_id"));
                Timestamp leaseUntil = (Timestamp) task.get("lease_until"), next = (Timestamp) task.get("next_run_at");
                String state = (String) task.get("state");
                if (terminal(state) || "NEEDS_REVIEW".equals(state) || active != null && active != id || next == null || next.after(now) || leaseUntil != null && leaseUntil.after(now)) continue;
                if ("QUEUED".equals(state) || "PREPARING".equals(state)) state = "PREPARING";
                if ("SUBMITTING".equals(state)) state = "RECONCILING"; // A crashed POST is never replayed.
                String token = UUID.randomUUID().toString();
                jdbc.update("UPDATE personal_remote_lane SET active_submit_id=? WHERE platform=? AND handle_key=?", id, platform, task.get("handle_key"));
                jdbc.update("UPDATE personal_remote_task SET state=?,lease_token=?,lease_until=?,updated_at=? WHERE submit_id=?", state, token, new Timestamp(clock.getAsLong() + LEASE_MILLIS), now, id);
                return row(id, false);
            }
            return null;
        });
    }
    private Error invalidContext(Map<String, Object> task) {
        List<Map<String, Object>> users = jdbc.queryForList("SELECT status FROM user_info WHERE uuid=? FOR UPDATE", task.get("owner_uid"));
        if (users.size() != 1 || users.get(0).get("status") == null || number(users.get(0).get("status")) != 0) return Error.ACCOUNT_REVOKED;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.uid,a.platform,a.handle_key,a.verified_identity,c.credential_version,c.credential_ciphertext FROM external_account a LEFT JOIN remote_user_credential c ON c.account_id=a.id WHERE a.id=? FOR UPDATE", task.get("account_id"));
        if (rows.size() != 1) return Error.ACCOUNT_REVOKED;
        Map<String, Object> account = rows.get(0);
        if (!Objects.equals(task.get("owner_uid"), account.get("uid")) || !Objects.equals(task.get("platform"), account.get("platform")) || !Objects.equals(task.get("handle_key"), account.get("handle_key")) || !Objects.equals(task.get("handle_key"), account.get("verified_identity"))) return Error.ACCOUNT_REVOKED;
        if (account.get("credential_ciphertext") == null || !((String)account.get("credential_ciphertext")).startsWith("s1:") || account.get("credential_version") == null || number(account.get("credential_version")) != number(task.get("credential_version"))) return Error.CREDENTIAL_CHANGED;
        return sourceCurrent(task) ? null : Error.SOURCE_CHANGED;
    }
    private String sourceContest(String problem) {
        String contest = null;
        if (problem != null && problem.startsWith("AC-")) {
            String task=problem.substring(3),key="atcoder:task:"+task;
            List<Map<String,Object>> rows = jdbc.queryForList("SELECT problem_key,task_id,contest_id FROM external_atcoder_problem WHERE problem_key=? AND task_id=? AND available=1",key,task);
            // Display IDs and canonical source keys are different namespaces. MySQL's
            // case-insensitive collation must not silently change the native task identity.
            if (rows.size() != 1 || !key.equals(rows.get(0).get("problem_key")) || !task.equals(rows.get(0).get("task_id"))) throw new Conflict("Source task scope is unavailable");
            contest = (String)rows.get(0).get("contest_id");
        }
        try { return new RemoteProblemRef(problem, contest).contest; }
        catch (IllegalArgumentException e) { throw new Conflict("Source task scope is invalid"); }
    }
    public RemoteProblemRef problemReference(String problem) {
        return new RemoteProblemRef(problem, sourceContest(problem));
    }
    private static String fingerprint(Object accountRef, Object problem, Object contest, Object languageId, Map<String, Object> judge) {
        return hash(field(accountRef) + field(problem) + field(contest) + field(languageId) + field(judge.get("pid")) + field(judge.get("cid")) + field(judge.get("gid")) + field(judge.get("language")) + field(judge.get("version")) + field(judge.get("code")));
    }
    boolean sourceCurrent(Map<String, Object> task) {
        List<Map<String, Object>> judges = jdbc.queryForList("SELECT j.*,p.problem_id,p.is_remote FROM judge j JOIN problem p ON p.id=j.pid WHERE j.submit_id=? AND j.uid=? FOR UPDATE", task.get("submit_id"), task.get("owner_uid"));
        if (judges.size() != 1) return false;
        Map<String, Object> judge = judges.get(0);
        boolean remote = Boolean.TRUE.equals(judge.get("is_remote")) || (judge.get("is_remote") instanceof Number && number(judge.get("is_remote")) == 1);
        String contest;
        try { contest = sourceContest((String) judge.get("problem_id")); }
        catch (Conflict e) { return false; }
        if (!Objects.equals(contest, task.get("source_contest"))) return false;
        String payload = fingerprint(task.get("account_ref"), judge.get("problem_id"), contest, task.get("language_id"), judge);
        if("NOWCODER".equals(task.get("platform")))try{payload=bindSource(payload,NowCoderTaskJournal.context(jdbc,number(task.get("submit_id"))));}catch(Conflict e){return false;}
        return remote && judge.get("code") != null && payload.equals(task.get("payload_hash")) && judge.get("status") != null && Arrays.asList(5, 6, 7, 9, 15).contains(((Number) judge.get("status")).intValue());
    }
    private static String bindSource(String payload,NowCoderJudgeProtocol.Context context){return hash(payload+field(context.signature()));}
    /** Call before any authenticated platform request, including result recovery after a restart. */
    public boolean authorize(long id, String token) {
        return tx(() -> {
            Map<String, Object> task = leased(id, token);
            Error reason = invalidContext(task);
            requireLiveLease(task);
            if (reason == null) return true;
            stop(task, number(task.get("submit_attempts")) == 0 ? "FAILED" : "NEEDS_REVIEW", reason);
            return false;
        });
    }
    /** Persist before the single source-code POST. An exception or crash after this point means reconcile. */
    public boolean beginPost(long id, String token, long baselineId) {
        if (baselineId < 0) throw new IllegalArgumentException("Invalid source baseline");
        return tx(() -> {
            Map<String, Object> task = leased(id, token);
            if (!"PREPARING".equals(task.get("state")) || number(task.get("submit_attempts")) != 0) throw new Conflict("This submission cannot be posted again");
            Error reason = invalidContext(task);
            requireLiveLease(task);
            if (reason != null) { stop(task, "FAILED", reason); return false; }
            Timestamp now = time();
            jdbc.update("UPDATE personal_remote_task SET state='SUBMITTING',baseline_id=?,submit_attempts=1,post_started_at=?,updated_at=? WHERE submit_id=?", baselineId, now, now, id);
            return true;
        });
    }
    /** Source rotation preserves explicit authorization/version and cannot undo a user revocation. */
    public boolean rotateSession(long id, String token, String expectedCipher, String replacementCipher) {
        if (replacementCipher == null || !replacementCipher.startsWith("s1:")) throw new IllegalArgumentException("Invalid source session");
        return tx(() -> {
            Map<String, Object> task = leased(id, token);
            Error reason = invalidContext(task);
            requireLiveLease(task);
            if (reason != null) return false;
            String current = jdbc.queryForObject("SELECT credential_ciphertext FROM remote_user_credential WHERE account_id=? FOR UPDATE", String.class, task.get("account_id"));
            requireLiveLease(task);
            if (!Objects.equals(expectedCipher, current)) return false;
            jdbc.update("UPDATE remote_user_credential SET credential_ciphertext=?,updated_at=? WHERE account_id=?", replacementCipher, time(), task.get("account_id"));
            return true;
        });
    }
    /** A result ID must have been independently matched by the platform adapter, never taken from a browser. */
    public void accepted(long id, String token, long remoteId) {
        tx(() -> {
            Map<String, Object> task = leased(id, token);
            if (!Arrays.asList("SUBMITTING", "RECONCILING").contains(task.get("state")) || number(task.get("submit_attempts")) != 1 || remoteId <= number(task.get("baseline_id"))) throw new Conflict("Unmatched remote submission ID");
            jdbc.update("UPDATE personal_remote_task SET state='POLLING',remote_submit_id=?,error_code=NULL,read_failures=0,next_run_at=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE submit_id=?", remoteId, time(), time(), id);
            return null;
        });
    }
    public void uncertain(long id, String token) {
        tx(() -> {
            Map<String, Object> task = leased(id, token);
            if (!"SUBMITTING".equals(task.get("state"))) throw new Conflict("No uncertain source POST");
            jdbc.update("UPDATE personal_remote_task SET state='RECONCILING',error_code='POST_UNCERTAIN',next_run_at=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE submit_id=?", new Timestamp(clock.getAsLong() + 5000), time(), id);
            return null;
        });
    }
    /** Read failures retry only reads; they can never move a task back across the POST boundary. */
    public void readFailed(long id, String token) {
        tx(() -> {
            Map<String, Object> task = leased(id, token);
            if (!Arrays.asList("PREPARING", "RECONCILING", "POLLING").contains(task.get("state"))) throw new Conflict("Invalid read retry phase");
            long failures = number(task.get("read_failures")) + 1;
            if (failures >= 10) { stop(task, number(task.get("submit_attempts")) == 0 ? "FAILED" : "NEEDS_REVIEW", Error.READ_UNAVAILABLE); return null; }
            long delay = Math.min(300000, 5000L << Math.min(6, failures - 1));
            jdbc.update("UPDATE personal_remote_task SET read_failures=?,error_code='READ_UNAVAILABLE',next_run_at=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE submit_id=?", failures, new Timestamp(clock.getAsLong() + delay), time(), id);
            return null;
        });
    }
    /** Ordinary source judging is not a network failure and must not consume the read retry budget. */
    public void waitingForResult(long id, String token) {
        tx(() -> {
            Map<String, Object> task = leased(id, token);
            if (!"POLLING".equals(task.get("state"))) throw new Conflict("Remote result is not identified");
            jdbc.update("UPDATE personal_remote_task SET read_failures=0,error_code=NULL,next_run_at=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE submit_id=?", new Timestamp(clock.getAsLong() + 5000), time(), id);
            return null;
        });
    }
    /** Failed/cancelled tasks also settle the pending HOJ judge, atomically and at most once. */
    public boolean finishStopped(long id, Runnable applyVerdict) {
        return tx(() -> {
            Map<String, Object> first = row(id, false);
            lockLane(first);
            Map<String, Object> task = row(id, true);
            if (!Arrays.asList("FAILED", "CANCELLED").contains(task.get("state"))) return false;
            if (!sourceCurrent(task)) {
                jdbc.update("UPDATE personal_remote_task SET error_code='SOURCE_CHANGED',updated_at=? WHERE submit_id=?", time(), id);
                return false;
            }
            applyVerdict.run();
            return true;
        });
    }
    /** Completing verdict + HOJ records is one transaction; a stale callback cannot apply either. */
    public boolean completed(long id, String token, Runnable applyVerdict) {
        return tx(() -> {
            Map<String, Object> task = leased(id, token);
            if (!"POLLING".equals(task.get("state")) || task.get("remote_submit_id") == null) throw new Conflict("Remote result is not identified");
            Error reason = invalidContext(task);
            requireLiveLease(task);
            if (reason != null) { stop(task, "NEEDS_REVIEW", reason); return false; }
            applyVerdict.run();
            // Association writes can wait after authorization. Expiry must roll them all back.
            // Do not re-read sourceCurrent: this callback has already made judge terminal.
            requireLiveLease(task);
            stop(task, "COMPLETED", null);
            return true;
        });
    }
    public void review(long id, String token, Error reason) {
        Objects.requireNonNull(reason);
        tx(() -> { Map<String, Object> task = leased(id, token); stop(task, number(task.get("submit_attempts")) == 0 ? "FAILED" : "NEEDS_REVIEW", reason); return null; });
    }
    private void stop(Map<String, Object> task, String state, Error reason) {
        jdbc.update("UPDATE personal_remote_task SET state=?,error_code=?,next_run_at=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE submit_id=?", state, reason == null ? null : reason.name(), time(), task.get("submit_id"));
        if (terminal(state)) jdbc.update("UPDATE personal_remote_lane SET active_submit_id=NULL WHERE platform=? AND handle_key=? AND active_submit_id=?", task.get("platform"), task.get("handle_key"), task.get("submit_id"));
    }
    /** Owner-requested recovery is a result lookup; there is deliberately no retry-POST operation. */
    public void reconcile(long id, String uid) {
        tx(() -> {
            Map<String, Object> first = owned(id, uid);
            lockLane(first);
            Map<String, Object> task = row(id, true);
            if (!"NEEDS_REVIEW".equals(task.get("state")) || number(task.get("submit_attempts")) != 1) throw new Conflict("Only an uncertain submission can be reconciled");
            // Explicit owner recovery may adopt a newly authorized session for the same identity.
            // It never resets submit_attempts, baseline, source, code, or remote submission ID.
            List<Map<String, Object>> accounts = jdbc.queryForList("SELECT a.id,c.credential_version FROM external_account a JOIN user_info u ON u.uuid=a.uid JOIN remote_user_credential c ON c.account_id=a.id WHERE a.uid=? AND u.status=0 AND a.platform=? AND a.handle_key=? AND a.verified_identity=a.handle_key AND LEFT(c.credential_ciphertext,3)='s1:' FOR UPDATE", task.get("owner_uid"), task.get("platform"), task.get("handle_key"));
            if (accounts.size() != 1 || !sourceCurrent(task)) throw new Conflict("Authorize the same source account before recovering results");
            jdbc.update("UPDATE personal_remote_task SET account_id=?,credential_version=? WHERE submit_id=?", accounts.get(0).get("id"), accounts.get(0).get("credential_version"), id);
            boolean known=task.get("remote_submit_id")!=null || "NOWCODER".equals(task.get("platform")) && jdbc.queryForObject("SELECT backend_job_id FROM personal_remote_nowcoder WHERE submit_id=? FOR UPDATE",String.class,id)!=null;
            jdbc.update("UPDATE personal_remote_task SET state=?,read_failures=0,error_code=NULL,next_run_at=?,updated_at=? WHERE submit_id=?",known?"POLLING":"RECONCILING",time(),time(),id);
            return null;
        });
    }
    public void cancelBeforePost(long id, String uid) {
        tx(() -> {
            Map<String, Object> first = owned(id, uid);
            lockLane(first);
            Map<String, Object> task = row(id, true);
            if (number(task.get("submit_attempts")) != 0 || terminal((String) task.get("state"))) throw new Conflict("A possibly accepted submission cannot be cancelled or replayed");
            stop(task, "CANCELLED", null);
            return null;
        });
    }
}
