package top.hcode.hoj.remote;

import org.springframework.jdbc.core.JdbcTemplate;
import top.hcode.hoj.utils.RemoteCredentialCipher;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/** One durable task step. No state after beginPost can reach the source-code POST again. */
public final class PersonalRemoteTaskRunner {
    public interface TransportFactory {
        PersonalRemoteTransport open(String platform, RemoteSession session, Runnable beforeRequest);
    }
    public interface ResultWriter {
        /** Called inside the journal transaction; source result is null for a pre-POST stop. */
        void apply(long submitId, PersonalRemoteSource.Candidate result, int stoppedStatus);
    }

    private final JdbcTemplate jdbc;
    private final PersonalRemoteTaskStore store;
    private final RemoteCredentialCipher cipher;
    private final TransportFactory transports;
    private final Consumer<String> rateGate;
    private final ResultWriter results;

    public PersonalRemoteTaskRunner(JdbcTemplate jdbc, PersonalRemoteTaskStore store,
                                    RemoteCredentialCipher cipher, TransportFactory transports,
                                    Consumer<String> rateGate, ResultWriter results) {
        this.jdbc = jdbc;
        this.store = store;
        this.cipher = cipher;
        this.transports = transports;
        this.rateGate = rateGate;
        this.results = results;
    }

    /** Returns false only when no due task exists. All network requests are outside DB transactions. */
    public boolean runOne(String platform) {
        Map<String, Object> task = store.claim(platform);
        if (task == null) return false;
        long id = number(task.get("submit_id"));
        String token = (String) task.get("lease_token");
        try {
            if (!store.authorize(id, token)) return true;
            RemoteProblemRef ref = new RemoteProblemRef((String) task.get("remote_problem"), (String) task.get("source_contest"));
            Map<String, Object> judge = jdbc.queryForMap("SELECT language,code FROM judge WHERE submit_id=? AND uid=?", id, task.get("owner_uid"));
            Map<String, Object> account = jdbc.queryForMap("SELECT a.handle,c.credential_version,c.credential_ciphertext FROM external_account a JOIN remote_user_credential c ON c.account_id=a.id WHERE a.id=? AND a.uid=? AND a.platform=? AND a.handle_key=? AND a.verified_identity=a.handle_key", task.get("account_id"), task.get("owner_uid"), platform, task.get("handle_key"));
            if (number(account.get("credential_version")) != number(task.get("credential_version"))) throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED);
            RemoteSession session;
            try {
                session = RemoteSession.parse(platform, cipher.decryptSession(number(task.get("account_id")),
                        (String) task.get("owner_uid"), platform, (String) task.get("handle_key"),
                        (String) account.get("credential_ciphertext")));
            } catch (Exception e) {
                throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED);
            }
            Runnable beforeRequest = () -> {
                rateGate.accept(platform);
                if (!store.authorize(id, token)) throw new PersonalRemoteTaskStore.Conflict("Remote session authorization changed");
            };
            try (PersonalRemoteTransport http = transports.open(platform, session, beforeRequest)) {
                AtomicReference<String> savedCipher = new AtomicReference<>((String) account.get("credential_ciphertext"));
                http.onSessionUpdate(replacement -> {
                    String encrypted;
                    try {
                        encrypted = cipher.encryptSession(number(task.get("account_id")), (String) task.get("owner_uid"),
                                platform, (String) task.get("handle_key"), replacement.cookieHeader(), false);
                    } catch (Exception e) { throw new RemoteSourceFailure(RemoteSourceFailure.Kind.AUTH_REQUIRED); }
                    if (!store.rotateSession(id, token, savedCipher.get(), encrypted)) throw new PersonalRemoteTaskStore.Conflict("Session changed during source rotation");
                    savedCipher.set(encrypted);
                });
                if("NOWCODER".equals(platform)){
                    runNowCoder(task,token,ref,(String)account.get("handle"),(String)judge.get("code"),http);return true;
                }
                PersonalRemoteSource source = new PersonalRemoteSource(ref, (String) account.get("handle"), http);
                String code = (String) judge.get("code"), language = (String) judge.get("language");
                switch ((String) task.get("state")) {
                    case "PREPARING":
                        PersonalRemoteSource.Form form = source.form();
                        if (!Objects.equals(language, form.languages().get(task.get("language_id"))))
                            throw new RemoteSourceFailure(RemoteSourceFailure.Kind.LANGUAGE_CHANGED);
                        long baseline = source.baseline(source.recent());
                        source.post(form, (String) task.get("language_id"), code, () -> {
                            if (!store.beginPost(id, token, baseline)) throw new PersonalRemoteTaskStore.Conflict("Source POST authorization changed");
                        });
                        store.uncertain(id, token); // A response/redirect alone never proves acceptance.
                        break;
                    case "RECONCILING":
                        PersonalRemoteSource.Candidate found = source.reconcile(source.recent(), language, code,
                                number(task.get("baseline_id")), ((Timestamp) task.get("post_started_at")).getTime());
                        if (found == null) store.readFailed(id, token);
                        else store.accepted(id, token, found.id);
                        break;
                    case "POLLING":
                        PersonalRemoteSource.Candidate result = source.result(number(task.get("remote_submit_id")), language, code,
                                number(task.get("baseline_id")), ((Timestamp) task.get("post_started_at")).getTime());
                        if (result.verdict == null) store.waitingForResult(id, token);
                        else store.completed(id, token, () -> results.apply(id, result, 10));
                        break;
                    default:
                        throw new PersonalRemoteTaskStore.Conflict("Unsupported remote worker phase");
                }
            }
        } catch (PersonalRemoteTaskStore.Conflict ignored) {
            // Another owner/worker may have fenced us. Never mutate or retry using this stale lease.
        } catch (Exception failure) {
            failedStep(id, token, failure);
        }
        return true;
    }

    private void runNowCoder(Map<String,Object> task,String token,RemoteProblemRef ref,String handle,String code,PersonalRemoteTransport http){
        long id=number(task.get("submit_id"));NowCoderTaskJournal journal=new NowCoderTaskJournal(jdbc,store.transactions(),store);NowCoderJudgeProtocol.Context context=journal.context(id);NowCoderJudgeProtocol protocol=new NowCoderJudgeProtocol(http);
        PersonalRemoteSource source=new PersonalRemoteSource(ref,handle,http);
        switch((String)task.get("state")){
            case "PREPARING":
                PersonalRemoteSource.Form form=source.form();Map<String,String> current=form.sourceContext();
                if(!context.questionId.equals(current.get("questionId")) || !context.problemId.equals(current.get("problemId")) || !context.contestId.equals(current.get("contestId")))throw new RemoteSourceFailure(RemoteSourceFailure.Kind.FORMAT_CHANGED);
                if(!context.languageName.equals(form.languages().get(context.languageId)))throw new RemoteSourceFailure(RemoteSourceFailure.Kind.LANGUAGE_CHANGED);
                NowCoderJudgeProtocol.Acknowledgement reply=protocol.submit(context,code,()->{if(!store.beginPost(id,token,0))throw new PersonalRemoteTaskStore.Conflict("Source POST authorization changed");});
                journal.acknowledged(id,token,reply);break;
            case "RECONCILING":case "POLLING":
                String job=journal.job(id);
                if(job==null){
                    source.reconcile(source.recent(),context.languageName,code,number(task.get("baseline_id")),((Timestamp)task.get("post_started_at")).getTime());
                    store.review(id,token,PersonalRemoteTaskStore.Error.POST_UNCERTAIN);
                }else{
                    NowCoderJudgeProtocol.Status status=protocol.status(context,job);
                    journal.result(id,token,status,()->results.apply(id,PersonalRemoteSource.nowcoderResult(context,status),10));
                }
                break;
            default:throw new PersonalRemoteTaskStore.Conflict("Unsupported source phase");
        }
    }

    private void failedStep(long id, String token, Exception failure) {
        try {
            Map<String, Object> current = jdbc.queryForMap("SELECT state FROM personal_remote_task WHERE submit_id=? AND lease_token=?", id, token);
            if ("SUBMITTING".equals(current.get("state"))) {
                store.uncertain(id, token); // Includes errors and lost responses after the durable POST barrier.
            } else if (failure instanceof RemoteSourceFailure) {
                switch (((RemoteSourceFailure) failure).kind) {
                    case AUTH_REQUIRED: store.review(id, token, PersonalRemoteTaskStore.Error.AUTH_REQUIRED); break;
                    case CHALLENGE: store.review(id, token, PersonalRemoteTaskStore.Error.SOURCE_BLOCKED); break;
                    case LANGUAGE_CHANGED: store.review(id, token, PersonalRemoteTaskStore.Error.LANGUAGE_CHANGED); break;
                    case AMBIGUOUS_RESULT: store.review(id, token, PersonalRemoteTaskStore.Error.AMBIGUOUS_RESULT); break;
                    case FORMAT_CHANGED: store.review(id, token, PersonalRemoteTaskStore.Error.MANUAL_REVIEW); break;
                    default: store.readFailed(id, token);
                }
            } else {
                store.readFailed(id, token);
            }
        } catch (Exception ignored) {
            // A DB outage or expired lease leaves durable recovery to the next claimant, never a POST retry.
        }
    }

    public void finishStopped() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT t.submit_id,t.state FROM personal_remote_task t JOIN judge j ON j.submit_id=t.submit_id WHERE t.state IN ('FAILED','CANCELLED') AND (t.error_code IS NULL OR t.error_code<>'SOURCE_CHANGED') AND j.status IN (5,6,7,9,15) ORDER BY t.updated_at LIMIT 20");
        for (Map<String, Object> row : rows) {
            long id = number(row.get("submit_id"));
            int status = "CANCELLED".equals(row.get("state")) ? -4 : 10;
            store.finishStopped(id, () -> results.apply(id, null, status));
        }
    }

    private static long number(Object value) { return ((Number) value).longValue(); }
}
