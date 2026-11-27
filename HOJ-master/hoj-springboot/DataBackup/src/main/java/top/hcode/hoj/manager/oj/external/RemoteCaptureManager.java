package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.RemoteStatementDTO.Content;
import top.hcode.hoj.utils.ShiroUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RemoteCaptureManager {
    private static final int MAX_HTML_BYTES = 4 * 1024 * 1024;
    private static final int BATCH_SIZE = 30;
    private static final Pattern CF_PATH = Pattern.compile(
            "/(?:problemset/problem/([1-9][0-9]{0,18})/([A-Z][A-Z0-9]{0,15})|contest/([1-9][0-9]{0,18})/problem/([A-Z][A-Z0-9]{0,15}))/?");
    private static final Pattern ATCODER_PATH = Pattern.compile("/contests/([A-Za-z0-9_-]{1,80})/tasks/([A-Za-z0-9_-]{1,80})/?");
    private static final String RESERVATION = "INSERT INTO remote_capture_queue "
            + "(problem_key, display_id, source_url, uploader, state, lease_token, lease_until) "
            + "VALUES (?, ?, ?, ?, 'RECEIVING', ?, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 2 MINUTE)) "
            + "ON DUPLICATE KEY UPDATE "
            + "uploader = IF(state='RECEIVING' AND lease_until<=CURRENT_TIMESTAMP(3), VALUES(uploader), uploader), "
            + "lease_token = IF(state='RECEIVING' AND lease_until<=CURRENT_TIMESTAMP(3), VALUES(lease_token), lease_token), "
            + "lease_until = IF(state='RECEIVING' AND lease_until<=CURRENT_TIMESTAMP(3), VALUES(lease_until), lease_until)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final CodeforcesStatementClient codeforces;
    private final AtCoderStatementClient atcoder;
    private final RemoteStatementManager statements;
    private final Semaphore uploadSlots = new Semaphore(4);

    public RemoteCaptureManager(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json,
                                CodeforcesStatementClient codeforces, AtCoderStatementClient atcoder,
                                RemoteStatementManager statements) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.json = json;
        this.codeforces = codeforces;
        this.atcoder = atcoder;
        this.statements = statements;
    }

    public Map<String, Object> credentialStatus() {
        List<Object> expires = jdbc.query("SELECT expires_at FROM remote_capture_credential "
                + "WHERE uid=? AND expires_at>CURRENT_TIMESTAMP(3)", (rs, row) -> rs.getTimestamp(1), uid());
        return Collections.singletonMap("expiresAt", expires.isEmpty() ? null : expires.get(0));
    }

    public Map<String, Object> issueCredential() {
        String uid = uid();
        if (!exists("SELECT 1 FROM user_info WHERE uuid=? AND status=0", uid)) {
            throw new ObjectiveException(403, "当前账号不可用");
        }
        String key = token() + token();
        jdbc.update("INSERT INTO remote_capture_credential(uid, secret_hash, expires_at) "
                + "VALUES (?, ?, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 DAY)) ON DUPLICATE KEY UPDATE "
                + "secret_hash=VALUES(secret_hash), expires_at=VALUES(expires_at)", uid, RemoteCatalogManager.hash(key));
        Map<String, Object> result = new LinkedHashMap<>(credentialStatus());
        result.put("key", key);
        return result;
    }

    public Map<String, Object> revokeCredential() {
        jdbc.update("DELETE FROM remote_capture_credential WHERE uid=?", uid());
        return credentialStatus();
    }

    public Map<String, Object> connection(String key) {
        authenticate(key);
        return Collections.singletonMap("enabled", enabled(false));
    }

    public Map<String, Object> reserve(String key, String platform, String id, String url) {
        String uploader = authenticate(key);
        if (!enabled(false)) return state("DISABLED");
        Source source = identify(platform, id, url);
        if (inProblemBank(source.displayId)) return state("EXISTS");
        if (exists("SELECT 1 FROM remote_capture_queue WHERE problem_key=? AND state='READY'", source.key)) {
            return state("QUEUED");
        }
        if (exists("SELECT 1 FROM remote_capture_queue WHERE problem_key=? AND state='RECEIVING' "
                + "AND lease_until>CURRENT_TIMESTAMP(3)", source.key)) return state("RECEIVING");
        checkCatalogue(source);

        return tx(() -> {
            if (!enabled(true)) return state("DISABLED");
            String lease = token();
            jdbc.update(RESERVATION, source.key, source.displayId, source.sourceUrl, uploader, lease);
            Map<String, Object> row = jdbc.queryForMap("SELECT state, lease_token FROM remote_capture_queue WHERE problem_key=?", source.key);
            if (lease.equals(row.get("lease_token"))) {
                if (inProblemBank(source.displayId)) {
                    jdbc.update("DELETE FROM remote_capture_queue WHERE problem_key=?", source.key);
                    return state("EXISTS");
                }
                Map<String, Object> result = state("UPLOAD");
                result.put("lease", lease);
                return result;
            }
            return state("READY".equals(row.get("state")) ? "QUEUED" : "RECEIVING");
        });
    }

    public Map<String, Object> upload(String key, String lease, String platform, String id, String url,
                                      HttpServletRequest request) {
        String uploader = authenticate(key);
        if (!enabled(false)) return state("DISABLED");
        Source source = identify(platform, id, url);
        if (inProblemBank(source.displayId)) return state("EXISTS");
        if (exists("SELECT 1 FROM remote_capture_queue WHERE problem_key=? AND state='READY'", source.key)) {
            return state("QUEUED");
        }
        verifyLease(source.key, uploader, lease);
        if (!uploadSlots.tryAcquire()) throw new ObjectiveException(429, "题面采集繁忙，稍后再次打开题面即可重试");
        try {
            String html = readHtml(request);
            Content parsed = source.atcoderContest == null ? codeforces.preview(html, source.key, source.pageUrl)
                    : atcoder.parse(html, source.atcoderContest, id);
            Content content = statements.normalize(parsed, source.key);
            String body = serialize(content);
            return tx(() -> {
                if (!enabled(true)) return state("DISABLED");
                authenticate(key);
                int count = jdbc.update("UPDATE remote_capture_queue SET state='READY', title=?, content_json=?, "
                                + "queued_at=CURRENT_TIMESTAMP(3), lease_token=NULL, lease_until=NULL "
                                + "WHERE problem_key=? AND state='RECEIVING' AND uploader=? AND lease_token=? "
                                + "AND lease_until>CURRENT_TIMESTAMP(3)",
                        content.getTitle(), body, source.key, uploader, lease);
                if (count == 0) throw leaseExpired();
                return state("QUEUED");
            });
        } finally {
            uploadSlots.release();
        }
    }

    public Map<String, Object> queue() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled(false));
        result.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM remote_capture_queue WHERE state='READY'", Long.class));
        result.put("items", jdbc.queryForList("SELECT problem_key AS problemKey, display_id AS displayId, "
                        + "source_url AS sourceUrl, title, queued_at AS queuedAt FROM remote_capture_queue "
                        + "WHERE state='READY' ORDER BY queued_at, problem_key LIMIT ?", BATCH_SIZE));
        return result;
    }

    public Map<String, Object> setEnabled(boolean enabled) {
        jdbc.update("UPDATE remote_capture_policy SET enabled=? WHERE id=1", enabled);
        return Collections.singletonMap("enabled", enabled);
    }

    public Content preview(String problemKey) {
        List<String> content = jdbc.query("SELECT content_json FROM remote_capture_queue WHERE problem_key=? AND state='READY'",
                (rs, row) -> rs.getString(1), problemKey);
        if (content.isEmpty()) throw new ObjectiveException(404, "待导入题面已处理");
        return deserialize(content.get(0));
    }

    public Map<String, Object> discard(String problemKey) {
        jdbc.update("DELETE FROM remote_capture_queue WHERE problem_key=? AND state='READY'", problemKey);
        return Collections.emptyMap();
    }

    public Map<String, Object> approveBatch(List<String> keys) {
        if (!enabled(false)) throw disabled();
        if (keys == null || keys.isEmpty() || keys.size() > BATCH_SIZE
                || keys.stream().anyMatch(key -> key == null || key.length() > 128)) {
            throw new ObjectiveException(400, "请选择最多 30 道待导入题目");
        }
        int imported = 0;
        int skipped = 0;
        Map<String, String> failures = new LinkedHashMap<>();
        for (String problemKey : new LinkedHashSet<>(keys)) {
            try {
                if (tx(() -> approveOne(problemKey))) imported++;
                else skipped++;
            } catch (ObjectiveException error) {
                failures.put(problemKey, error.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("failures", failures);
        return result;
    }

    private boolean approveOne(String problemKey) {
        if (!enabled(true)) throw disabled();
        List<String> content = jdbc.query("SELECT content_json FROM remote_capture_queue "
                        + "WHERE problem_key=? AND state='READY' FOR UPDATE", (rs, row) -> rs.getString(1), problemKey);
        if (content.isEmpty()) return false;
        boolean imported = statements.publishCapturedOriginal(problemKey, deserialize(content.get(0)));
        jdbc.update("DELETE FROM remote_capture_queue WHERE problem_key=?", problemKey);
        return imported;
    }

    private void verifyLease(String sourceKey, String uploader, String lease) {
        if (lease == null || !lease.matches("[a-f0-9]{32}") || !exists(
                "SELECT 1 FROM remote_capture_queue WHERE problem_key=? AND state='RECEIVING' "
                        + "AND uploader=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(3)", sourceKey, uploader, lease)) {
            throw leaseExpired();
        }
    }

    private static ObjectiveException leaseExpired() {
        return new ObjectiveException(409, "题面上传授权已失效，重新打开题面后可重试");
    }

    private static ObjectiveException disabled() {
        return new ObjectiveException(409, "超管已暂停题面自动导入");
    }

    private String authenticate(String key) {
        if (key != null && key.matches("[a-f0-9]{64}")) {
            List<String> users = jdbc.query("SELECT c.uid FROM remote_capture_credential c JOIN user_info u ON u.uuid=c.uid "
                            + "WHERE c.secret_hash=? AND c.expires_at>CURRENT_TIMESTAMP(3) AND u.status=0",
                    (rs, row) -> rs.getString(1), RemoteCatalogManager.hash(key));
            if (!users.isEmpty()) return users.get(0);
        }
        throw new ObjectiveException(401, "HOJ 题面采集密钥无效或已过期");
    }

    private boolean enabled(boolean lock) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM remote_capture_policy WHERE id=1"
                + (lock ? " FOR SHARE" : ""), Boolean.class));
    }

    private boolean inProblemBank(String id) {
        return exists("SELECT 1 FROM problem WHERE problem_id=? LIMIT 1", id);
    }

    private void checkCatalogue(Source source) {
        boolean available = source.atcoderContest == null
                ? exists("SELECT 1 FROM remote_catalog_problem WHERE problem_key=? AND available=1", source.key)
                : exists("SELECT 1 FROM external_atcoder_problem WHERE problem_key=? AND contest_id=? AND available=1",
                        source.key, source.atcoderContest);
        if (!available) throw new ObjectiveException(404, "题目尚未出现在 HOJ 的公开源站目录，目录同步后可重试");
    }

    private boolean exists(String sql, Object... parameters) {
        return !jdbc.queryForList(sql, Integer.class, parameters).isEmpty();
    }

    private <T> T tx(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private static String uid() {
        return ShiroUtils.getProfile().getUid();
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Map<String, Object> state(String state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        return result;
    }

    private static String readHtml(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_HTML_BYTES) throw tooLarge();
        try (InputStream input = request.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_HTML_BYTES) throw tooLarge();
                output.write(buffer, 0, count);
            }
            if (output.size() == 0) throw new ObjectiveException(400, "题面内容为空");
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new ObjectiveException(400, "题面上传中断，请重新打开题面");
        }
    }

    private static ObjectiveException tooLarge() {
        return new ObjectiveException(413, "题面不得超过 4 MiB");
    }

    private String serialize(Content content) {
        try {
            return json.writeValueAsString(content);
        } catch (JsonProcessingException error) {
            throw new ObjectiveException(400, "题面格式无法保存");
        }
    }

    private Content deserialize(String body) {
        try {
            return json.readValue(body, Content.class);
        } catch (IOException error) {
            throw new ObjectiveException(409, "待导入题面格式有误，请移除后重新采集");
        }
    }

    private static Source identify(String platform, String id, String url) {
        if (url == null || url.length() > 400) throw invalidSource();
        URI page;
        try {
            page = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw invalidSource();
        }
        if (!"https".equals(page.getScheme()) || page.getHost() == null || page.getPort() != -1
                || page.getRawUserInfo() != null || page.getRawQuery() != null || page.getRawFragment() != null) {
            throw invalidSource();
        }
        if ("codeforces".equals(platform) && "codeforces.com".equals(page.getHost())) {
            Matcher match = CF_PATH.matcher(page.getRawPath());
            if (match.matches()) {
                String contest = match.group(1) == null ? match.group(3) : match.group(1);
                String index = match.group(2) == null ? match.group(4) : match.group(2);
                if ((contest + index).equals(id)) {
                    String key = "cf:contest:" + contest + ":" + index;
                    return new Source(key, "CF-" + id, CodeforcesStatementClient.sourceUrl(key), url, null);
                }
            }
        }
        if ("atcoder".equals(platform) && "atcoder.jp".equals(page.getHost())) {
            Matcher match = ATCODER_PATH.matcher(page.getRawPath());
            if (match.matches() && match.group(2).equals(id)) {
                String contest = match.group(1);
                return new Source(ProblemIdentity.atcoder(id), "AC-" + id, AtCoderStatementClient.sourceUrl(contest, id), url, contest);
            }
        }
        throw invalidSource();
    }

    private static ObjectiveException invalidSource() {
        return new ObjectiveException(400, "题号与受支持的源站页面不一致");
    }

    private static final class Source {
        private final String key;
        private final String displayId;
        private final String sourceUrl;
        private final String pageUrl;
        private final String atcoderContest;

        private Source(String key, String displayId, String sourceUrl, String pageUrl, String atcoderContest) {
            this.key = key;
            this.displayId = displayId;
            this.sourceUrl = sourceUrl;
            this.pageUrl = pageUrl;
            this.atcoderContest = atcoderContest;
        }
    }
}
