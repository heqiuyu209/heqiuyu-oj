package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;

import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

/** Two independently verified paper projections; never writes the canonical submission ledger. */
@Component
public class NowCoderPracticeManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    protected long now() { return System.currentTimeMillis(); }
    private Timestamp time() { return new Timestamp(now()); }
    private long number(Object value) { return ((Number) value).longValue(); }
    private boolean flag(Object value) { return ExternalAccountManager.flag(value); }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    private void release(long id, String stage, int page, long delay) {
        jdbc.update("UPDATE external_nowcoder_practice_job SET stage=?,next_page=?,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",
                stage, page, new Timestamp(now() + delay), id);
    }
    private void restart(Map<String, Object> job, long delay) {
        long id = number(job.get("account_id")); String published = (String) job.get("published_token");
        jdbc.update("DELETE FROM external_nowcoder_practice_record WHERE account_id=? AND (? IS NULL OR scan_token<>?)", id, published, published);
        jdbc.update("UPDATE external_nowcoder_practice_job SET stage='PROFILE',scan_token=?,next_page=1,all_total=NULL,finished_total=NULL,next_run_at=?,lease_token=NULL,lease_until=NULL,failures=0,last_error=NULL WHERE account_id=?",
                UUID.randomUUID().toString(), new Timestamp(now() + delay), id);
    }
    public Map<String, Object> claim() { return tx(() -> {
        List<Long> due = jdbc.queryForList("SELECT a.id FROM external_account a JOIN user_info u ON u.uuid=a.uid LEFT JOIN external_nowcoder_practice_job j ON j.account_id=a.id WHERE u.status=0 AND a.platform='NOWCODER' AND (j.account_id IS NULL OR (j.next_run_at<=? AND (j.lease_until IS NULL OR j.lease_until<=?))) ORDER BY COALESCE(j.next_run_at,a.created_at),a.id LIMIT 1", Long.class, time(), time());
        if (due.isEmpty()) return null; long id = due.get(0);
        jdbc.queryForList("SELECT id FROM external_account WHERE id=? FOR UPDATE", id);
        List<Map<String, Object>> accounts = jdbc.queryForList("SELECT a.handle FROM external_account a JOIN user_info u ON u.uuid=a.uid WHERE a.id=? AND a.platform='NOWCODER' AND u.status=0 FOR UPDATE", id);
        if (accounts.isEmpty()) return null; Map<String, Object> account = accounts.get(0);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM external_nowcoder_practice_job WHERE account_id=? FOR UPDATE", id);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO external_nowcoder_practice_job(account_id,scan_token,next_run_at) VALUES(?,?,?)", id, UUID.randomUUID().toString(), time());
            rows = jdbc.queryForList("SELECT * FROM external_nowcoder_practice_job WHERE account_id=? FOR UPDATE", id);
        }
        Map<String, Object> job = rows.get(0);
        if (((Timestamp) job.get("next_run_at")).getTime() > now()
                || job.get("lease_until") != null && ((Timestamp) job.get("lease_until")).getTime() > now()) return null;
        if ("DONE".equals(job.get("stage"))) {
            restart(job, 0); job.putAll(jdbc.queryForMap("SELECT * FROM external_nowcoder_practice_job WHERE account_id=?", id));
        }
        String token = UUID.randomUUID().toString(); Timestamp until = new Timestamp(now() + 90000);
        jdbc.update("UPDATE external_nowcoder_practice_job SET lease_token=?,lease_until=? WHERE account_id=?", token, until, id);
        job.put("handle", account.get("handle")); job.put("lease_token", token); job.put("lease_until", until);
        return job;
    }); }
    private Map<String, Object> leased(Map<String, Object> old) {
        long id = number(old.get("account_id"));
        jdbc.queryForList("SELECT id FROM external_account WHERE id=? FOR UPDATE", id);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT j.*,a.handle FROM external_nowcoder_practice_job j JOIN external_account a ON a.id=j.account_id JOIN user_info u ON u.uuid=a.uid WHERE j.account_id=? AND j.lease_token=? AND j.lease_until>? AND a.platform='NOWCODER' AND u.status=0 FOR UPDATE", id, old.get("lease_token"), time());
        if (rows.isEmpty()) return null;
        Map<String, Object> job = rows.get(0);
        // Recheck the clock after any wait for the job/user row locks.
        return ((Timestamp) job.get("lease_until")).getTime() > now() ? job : null;
    }
    public void request(long id) { jdbc.update("UPDATE external_nowcoder_practice_job SET next_run_at=? WHERE account_id=?", time(), id); }
    public void defer(Map<String, Object> old) { tx(() -> {
        Map<String, Object> job = leased(old);
        if (job != null) jdbc.update("UPDATE external_nowcoder_practice_job SET next_run_at=?,lease_token=NULL,lease_until=NULL WHERE account_id=?", new Timestamp(now() + 3000), job.get("account_id"));
        return null;
    }); }
    public void failed(Map<String, Object> old, String ignored) { tx(() -> {
        Map<String, Object> job = leased(old);
        if (job != null) {
            int failures = ((Number) job.get("failures")).intValue() + 1;
            long delay = Math.min(3600000L, 30000L * (1L << Math.min(failures, 7)));
            if (failures >= 3) restart(job, delay);
            jdbc.update("UPDATE external_nowcoder_practice_job SET failures=?,last_error=?,next_run_at=?,lease_token=NULL,lease_until=NULL WHERE account_id=?",
                    failures, "牛客专项与试卷记录未完成，保留上次完整记录", new Timestamp(now() + delay), job.get("account_id"));
        }
        return null;
    }); }
    private int integer(JsonNode data, String name, int min, int max) {
        JsonNode value = data.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < min || value.asInt() > max) throw new IllegalArgumentException("Invalid practice result");
        return value.asInt();
    }
    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception error) { throw new IllegalStateException("Invalid saved practice projection"); }
    }
    public void apply(Map<String, Object> old, JsonNode data) { tx(() -> {
        Map<String, Object> job = leased(old); if (job == null) return null;
        if (data == null || !data.isObject() || !Objects.equals(job.get("handle"), data.path("uid").asText())) throw new IllegalArgumentException("Practice identity changed");
        long id = number(job.get("account_id")); String stage = (String) job.get("stage");
        if (stage.endsWith("PROFILE")) {
            if (!data.path("visible").isBoolean()) throw new IllegalArgumentException("Missing source visibility");
            boolean visible = data.path("visible").asBoolean();
            if (!visible) {
                // Revoke published data as soon as the source restriction is known.
                jdbc.update("UPDATE external_nowcoder_practice_job SET source_visible=0,published_token=NULL,revision=revision+1 WHERE account_id=?", id);
                jdbc.update("DELETE FROM external_nowcoder_practice_record WHERE account_id=?", id);
                release(id, "DONE", 1, 86400000L);
            } else {
                jdbc.update("UPDATE external_nowcoder_practice_job SET source_visible=1 WHERE account_id=?", id);
                if ("PROFILE".equals(stage)) release(id, "COUNT_ALL", 1, 0);
                else publish(job);
            }
            return null;
        }
        boolean finished = stage.endsWith("FINISHED");
        if (!data.path("finished").isBoolean() || data.path("finished").asBoolean() != finished) throw new IllegalArgumentException("Practice projection changed");
        if (stage.contains("COUNT")) {
            int total = integer(data, "total", 0, NowCoderPracticeClient.MAX_TOTAL);
            String field = finished ? "finished_total" : "all_total";
            if (stage.startsWith("VERIFY_")) {
                if (job.get(field) == null || total != number(job.get(field))) { restart(job, 30000); return null; }
            } else jdbc.update("UPDATE external_nowcoder_practice_job SET " + field + "=? WHERE account_id=?", total, id);
            switch (stage) {
                case "COUNT_ALL": release(id, "COUNT_FINISHED", 1, 0); break;
                case "COUNT_FINISHED":
                    if (job.get("all_total") == null || total > number(job.get("all_total"))) restart(job, 30000);
                    else release(id, "ALL", 1, 0);
                    break;
                case "VERIFY_COUNT_ALL": release(id, "VERIFY_COUNT_FINISHED", 1, 0); break;
                case "VERIFY_COUNT_FINISHED": release(id, "VERIFY_PROFILE", 1, 0); break;
                default: throw new IllegalArgumentException("Unknown practice count stage");
            }
            return null;
        }
        if (!Arrays.asList("ALL", "FINISHED", "VERIFY_ALL", "VERIFY_FINISHED").contains(stage) || !data.path("items").isArray()) throw new IllegalArgumentException("Unknown practice page stage");
        applyPage(job, data, finished, stage.startsWith("VERIFY_")); return null;
    }); }
    private void applyPage(Map<String, Object> job, JsonNode data, boolean finished, boolean verify) {
        long id = number(job.get("account_id")); String projection = finished ? "FINISHED" : "ALL";
        int page = integer(data, "page", 1, NowCoderPracticeClient.MAX_PAGES);
        int total = integer(data, "total", 0, NowCoderPracticeClient.MAX_TOTAL);
        int pages = integer(data, "pages", 0, NowCoderPracticeClient.MAX_PAGES);
        if (page != number(job.get("next_page")) || pages != (total + 19) / 20 || page > Math.max(1, pages)
                || data.path("items").size() != Math.min(20, Math.max(0, total - (page - 1) * 20))) throw new IllegalArgumentException("Invalid practice page");
        if (job.get(finished ? "finished_total" : "all_total") == null || total != number(job.get(finished ? "finished_total" : "all_total"))) { restart(job, 30000); return; }
        if (verify) {
            List<String> saved = jdbc.queryForList("SELECT record_hash FROM external_nowcoder_practice_record WHERE account_id=? AND scan_token=? AND projection=? AND page_no=? ORDER BY position_no", String.class, id, job.get("scan_token"), projection, page);
            if (saved.size() != data.path("items").size()) { restart(job, 30000); return; }
            // Tree parsing may narrow LongNode to IntNode or change DecimalNode
            // to another numeric node. Compare the producer's stable wire value.
            for (int n = 0; n < saved.size(); n++) if (!saved.get(n).equals(RemoteCatalogManager.hash(data.path("items").get(n).toString()))) { restart(job, 30000); return; }
        } else {
            int position = 0;
            for (JsonNode row : data.path("items")) {
                String paper = row.path("paperId").asText(), record = row.path("recordId").asText(), kind = row.path("kind").asText();
                if (!paper.matches("[1-9][0-9]{0,18}") || !record.matches("[1-9][0-9]{0,18}")
                        || !Arrays.asList("PAPER", "SPECIAL", "INTERVIEW").contains(kind) || !row.path("completed").isBoolean()
                        || finished && !row.path("completed").asBoolean()) throw new IllegalArgumentException("Invalid paper record");
                if (jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_practice_record WHERE account_id=? AND scan_token=? AND projection=? AND (paper_id=? OR record_id=?)", Integer.class, id, job.get("scan_token"), projection, Long.parseLong(paper), Long.parseLong(record)) != 0) { restart(job, 30000); return; }
                String recordJson = row.toString();
                jdbc.update("INSERT INTO external_nowcoder_practice_record(account_id,scan_token,projection,paper_id,record_id,page_no,position_no,kind,completed,record_json,record_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        id, job.get("scan_token"), projection, Long.parseLong(paper), Long.parseLong(record), page, position++, kind, row.path("completed").asBoolean(), recordJson, RemoteCatalogManager.hash(recordJson));
            }
        }
        if (page < pages) { release(id, (String) job.get("stage"), page + 1, 0); return; }
        int stored = jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_practice_record WHERE account_id=? AND scan_token=? AND projection=?", Integer.class, id, job.get("scan_token"), projection);
        if (stored != total) { restart(job, 30000); return; }
        release(id, verify ? finished ? "VERIFY_COUNT_ALL" : "FINISHED" : finished ? "VERIFY_FINISHED" : "VERIFY_ALL", 1, 0);
    }
    private void publish(Map<String, Object> job) {
        long id = number(job.get("account_id")); Object token = job.get("scan_token");
        for (String projection : Arrays.asList("ALL", "FINISHED")) {
            Object total = job.get("ALL".equals(projection) ? "all_total" : "finished_total");
            if (total == null || jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_practice_record WHERE account_id=? AND scan_token=? AND projection=?", Integer.class, id, token, projection) != number(total)) throw new IllegalStateException("Incomplete paper projection");
        }
        // Compare server-computed lowercase SHA256, not TEXT under unicode_ci collation.
        int mismatch = jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_practice_record f LEFT JOIN external_nowcoder_practice_record a ON a.account_id=f.account_id AND a.scan_token=f.scan_token AND a.projection='ALL' AND a.paper_id=f.paper_id LEFT JOIN external_nowcoder_practice_record r ON r.account_id=f.account_id AND r.scan_token=f.scan_token AND r.projection='ALL' AND r.record_id=f.record_id WHERE f.account_id=? AND f.scan_token=? AND f.projection='FINISHED' AND (a.paper_id IS NULL OR a.kind<>f.kind OR (a.record_id=f.record_id AND a.record_hash<>f.record_hash) OR (r.paper_id IS NOT NULL AND r.paper_id<>f.paper_id))", Integer.class, id, token);
        if (mismatch != 0) { restart(job, 30000); return; }
        jdbc.update("UPDATE external_nowcoder_practice_job SET published_token=scan_token,revision=revision+1,last_success_at=? WHERE account_id=?", time(), id);
        jdbc.update("DELETE FROM external_nowcoder_practice_record WHERE account_id=? AND scan_token<>?", id, token);
        release(id, "DONE", 1, 86400000L);
    }
    /** Caller supplies only an owned account or an already verified public identity. */
    public Map<String, Object> view(long id, boolean owner) {
        // Do not inherit a caller's older REPEATABLE READ snapshot: the final
        // publication/visibility check must observe intervening revocation.
        TransactionTemplate read = new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read.setReadOnly(true);
        return read.execute(status -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                Map<String, Object> value = readView(id, owner); if (value != null) return value;
            }
            throw new ObjectiveException(409, "专项记录刚刚更新，请刷新后重试");
        });
    }
    private Map<String, Object> readView(long id, boolean owner) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false); result.put("paperListsComplete", false); result.put("attemptHistoryComplete", false);
        result.put("questionEvidenceAvailable", false); result.put("uniqueQuestionCount", null); result.put("syncState", "QUEUED"); result.put("records", Collections.emptyList());
        List<Map<String, Object>> jobs = jdbc.queryForList("SELECT j.*,a.handle FROM external_nowcoder_practice_job j JOIN external_account a ON a.id=j.account_id WHERE j.account_id=?", id);
        if (jobs.isEmpty()) return result; Map<String, Object> job = jobs.get(0);
        result.put("syncState", job.get("lease_until") != null && ((Timestamp) job.get("lease_until")).getTime() > now() ? "RUNNING" : number(job.get("failures")) > 0 ? "BACKOFF" : "DONE".equals(job.get("stage")) ? "IDLE" : "QUEUED");
        result.put("restricted", job.get("source_visible") != null && !flag(job.get("source_visible")));
        result.put("stale", job.get("last_success_at") == null || now() - ((Timestamp) job.get("last_success_at")).getTime() > 86400000L || number(job.get("failures")) > 0 || !"DONE".equals(job.get("stage")));
        result.put("lastSuccessAt", job.get("last_success_at")); if (owner) result.put("lastError", job.get("last_error"));
        String token = (String) job.get("published_token"); if (token == null || !flag(job.get("source_visible"))) return result;
        result.put("sourceUrl", "https://www.nowcoder.com/users/" + job.get("handle") + "/tests");
        int all = 0, completed = 0, special = 0, completedSpecial = 0;
        for (Map<String, Object> row : jdbc.queryForList("SELECT projection,COUNT(*) AS records,SUM(CASE WHEN kind='SPECIAL' THEN 1 ELSE 0 END) AS special FROM external_nowcoder_practice_record WHERE account_id=? AND scan_token=? GROUP BY projection", id, token)) {
            if ("ALL".equals(row.get("projection"))) { all = ((Number) row.get("records")).intValue(); special = ((Number) row.get("special")).intValue(); }
            else { completed = ((Number) row.get("records")).intValue(); completedSpecial = ((Number) row.get("special")).intValue(); }
        }
        result.put("totalPapers", all); result.put("completedPapers", completed); result.put("specialPapers", special); result.put("completedSpecialPapers", completedSpecial);
        List<Object> records = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT a.record_json,f.record_json AS finished_json FROM external_nowcoder_practice_record a LEFT JOIN external_nowcoder_practice_record f ON f.account_id=a.account_id AND f.scan_token=a.scan_token AND f.projection='FINISHED' AND f.paper_id=a.paper_id WHERE a.account_id=? AND a.scan_token=? AND a.projection='ALL' ORDER BY a.page_no,a.position_no LIMIT 20", id, token)) {
            Map<String, Object> record = json.convertValue(read((String) row.get("record_json")), Map.class);
            record.put("completedRecord", row.get("finished_json") == null ? null : json.convertValue(read((String) row.get("finished_json")), Map.class)); records.add(record);
        }
        List<Map<String, Object>> latest = jdbc.queryForList("SELECT published_token,revision,source_visible FROM external_nowcoder_practice_job WHERE account_id=?", id);
        if (latest.isEmpty() || !Objects.equals(token, latest.get(0).get("published_token")) || !Objects.equals(job.get("revision"), latest.get(0).get("revision")) || !flag(latest.get(0).get("source_visible"))) return null;
        result.put("available", true); result.put("paperListsComplete", true); result.put("records", records); return result;
    }
}
