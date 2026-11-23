package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.ai.AiAccessGuard;
import top.hcode.hoj.pojo.dto.AiDTO;
import top.hcode.hoj.pojo.dto.TrainingPlanDTO;
import top.hcode.hoj.utils.ShiroUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Own saved candidate sets. The browser cannot write progress, identities or source metadata. */
@Component
public class LearningTrainingPlanManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private LearningRecommendationManager recommendations;
    @Autowired private LearningCandidateStore candidates;
    @Autowired private AiAccessGuard access;
    @Autowired private ObjectMapper json;
    protected long now() { return System.currentTimeMillis(); }
    protected String uid() { if (ShiroUtils.getProfile() == null) throw new ObjectiveException(401, "请先登录"); return ShiroUtils.getProfile().getUid(); }
    private <T> T tx(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        // A fresh RR snapshot starts after acquiring the owner lock, even with an outer reader.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }
    private long number(Object value) { return ((Number) value).longValue(); }
    private void id(String id) { if (id == null || !id.matches("[a-f0-9]{32}")) throw new ObjectiveException(400, "训练清单标识无效"); }
    private String name(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().codePointCount(0, name.trim().length()) > 80 || name.indexOf('\0') >= 0)
            throw new ObjectiveException(400, "请填写 80 字以内的训练清单名称");
        return name.trim();
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new ObjectiveException(503, "训练清单暂不可用，请稍后重试"); } }
    private String hash(Object value) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format("%02x", b & 255)); return result.toString(); }
        catch (Exception e) { throw new ObjectiveException(503, "训练清单暂不可用，请稍后重试"); }
    }
    private Map<String,Object> snapshot(Map<String,Object> row) {
        try {
            Object value = row.get("snapshot_json");
            String text = value instanceof byte[] ? new String((byte[]) value, StandardCharsets.UTF_8) : value.toString();
            return json.readValue(text, new TypeReference<Map<String,Object>>() {});
        } catch (Exception e) { throw new ObjectiveException(503, "训练清单数据暂不可用"); }
    }
    private Map<String,Object> own(String uid, String id, boolean lock) {
        id(id); List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM learning_training_plan WHERE uid=? AND id=?" + (lock ? " FOR UPDATE" : ""), uid, id);
        if (rows.isEmpty() || "DELETED".equals(rows.get(0).get("state"))) throw new ObjectiveException(404, "训练清单不存在或已删除");
        return rows.get(0);
    }
    private void lockUser(String uid) {
        if (jdbc.queryForList("SELECT uuid FROM user_info WHERE uuid=? AND status=0 FOR UPDATE", uid).isEmpty()) throw new ObjectiveException(403, "当前账号无法保存训练清单");
    }
    private List<Map<String,Object>> live(String uid) {
        // The owner lock serializes writes. Do not gap-lock empty ranges shared by other owners.
        return jdbc.queryForList("SELECT id,state FROM learning_training_plan WHERE uid=? AND state<>'DELETED'", uid);
    }
    private Map<String,Object> oldRequest(String uid, String nonce, String fingerprint, boolean lock) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,state,request_hash FROM learning_training_plan WHERE uid=? AND request_id=?" + (lock ? " FOR UPDATE" : ""), uid, nonce);
        if (rows.isEmpty()) return null;
        Map<String,Object> row = rows.get(0);
        if (!fingerprint.equals(row.get("request_hash"))) throw new ObjectiveException(409, "本次保存标识已用于其他清单，请重新保存");
        return row;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> create(TrainingPlanDTO.Create dto) {
        String uid = uid(); access.requireChat(uid);
        if (dto == null || dto.getRequestId() == null || !dto.getRequestId().matches("[A-Za-z0-9_-]{8,64}") || dto.getSelection() == null || dto.getSelection().getFilters() == null)
            throw new ObjectiveException(400, "请先获取推荐题目，再保存训练清单");
        String title = name(dto.getName()); AiDTO.RecommendationExplanation selection = dto.getSelection(); AiDTO.RecommendationFilter f = selection.getFilters();
        if (selection.getFingerprint() == null || !selection.getFingerprint().matches("[a-f0-9]{64}")
                || selection.getCandidateIds() == null || selection.getCandidateIds().isEmpty() || selection.getCandidateIds().size() > 6
                || selection.getCandidateIds().stream().anyMatch(value -> value == null || value.length() > 160)
                || f.getGroupCode() != null && f.getGroupCode().length() > 80
                || !Arrays.asList("HOJ", "CODEFORCES", "ATCODER", "NOWCODER").contains(f.getSource()))
            throw new ObjectiveException(400, "推荐标识无效，请重新获取候选题");
        String command = hash(Arrays.asList(title, f.getSource(), f.getGroupCode(), f.getMinRating(), f.getMaxRating(), f.isIncludeUnrated(), selection.getFingerprint(), selection.getCandidateIds()));
        Map<String,Object> previous = oldRequest(uid, dto.getRequestId(), command, false);
        if (previous != null) return requestResult(uid, previous);
        Map<String,Object> verified = recommendations.verify(uid, selection);
        Map<String,Object> saved = new LinkedHashMap<>(); saved.put("filters", verified.get("filters")); saved.put("basis", verified.get("basis")); saved.put("items", verified.get("items"));
        saved.put("recommendationVersion", verified.get("version")); saved.put("fingerprint", verified.get("fingerprint"));
        List<Map<String,Object>> items = (List<Map<String,Object>>) saved.get("items");
        if (items == null || items.isEmpty() || items.size() > 6) throw new ObjectiveException(400, "本次没有可保存的推荐题目");
        String body = write(saved); if (body.getBytes(StandardCharsets.UTF_8).length > 65536) throw new ObjectiveException(400, "推荐清单过大，请刷新后重试");
        Map<String,Object> created = tx(() -> {
            lockUser(uid); Map<String,Object> old = oldRequest(uid, dto.getRequestId(), command, false); if (old != null) return old;
            List<Map<String,Object>> rows = live(uid);
            if (rows.size() >= 200 || rows.stream().filter(row -> "ACTIVE".equals(row.get("state"))).count() >= 20)
                throw new ObjectiveException(409, "最多保留 20 份进行中、200 份未删除清单，请先归档或删除旧清单");
            String id = UUID.randomUUID().toString().replace("-", ""); Timestamp time = new Timestamp(now());
            jdbc.update("INSERT INTO learning_training_plan(id,uid,request_id,request_hash,name,state,snapshot_json,revision,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',?,1,?,?)",
                        id, uid, dto.getRequestId(), command, title, body, time, time);
            Map<String,Object> result = new LinkedHashMap<>(); result.put("id", id); result.put("state", "ACTIVE"); return result;
        });
        return requestResult(uid, created);
    }
    private Map<String,Object> requestResult(String uid, Map<String,Object> row) {
        access.requireChat(uid); // Fresh check outside the write transaction.
        if ("DELETED".equals(row.get("state"))) { Map<String,Object> result = new LinkedHashMap<>(); result.put("id", row.get("id")); result.put("state", "DELETED"); return result; }
        return detail((String) row.get("id"));
    }
    public Map<String,Object> list(String state, Integer page) {
        String uid = uid(); access.requireChat(uid);
        if (!Arrays.asList("ACTIVE", "ARCHIVED").contains(state) || page == null || page < 1 || page > 1000) throw new ObjectiveException(400, "清单筛选或页码无效");
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM learning_training_plan WHERE uid=? AND state=? ORDER BY created_at DESC,id DESC LIMIT 10 OFFSET ?", uid, state, (page - 1) * 10);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("items", views(uid, rows));
        result.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM learning_training_plan WHERE uid=? AND state=?", Long.class, uid, state));
        result.put("page", page); access.requireChat(uid); return result;
    }
    public Map<String,Object> detail(String id) {
        String uid = uid(); access.requireChat(uid); Map<String,Object> result = views(uid, Collections.singletonList(own(uid, id, false))).get(0); access.requireChat(uid); return result;
    }
    public Map<String,Object> update(String id, TrainingPlanDTO.Update dto) {
        String uid = uid(); access.requireChat(uid);
        if (dto == null || dto.getExpectedRevision() == null || !Arrays.asList("ACTIVE", "ARCHIVED", "DELETED").contains(dto.getState())) throw new ObjectiveException(400, "清单操作无效");
        String newName = dto.getName() == null ? null : name(dto.getName());
        tx(() -> {
            lockUser(uid); Map<String,Object> row = own(uid, id, true);
            if (number(row.get("revision")) != dto.getExpectedRevision()) throw new ObjectiveException(409, "训练清单已更新，请刷新后重试");
            if ("ACTIVE".equals(dto.getState()) && !"ACTIVE".equals(row.get("state")) && live(uid).stream().filter(item -> "ACTIVE".equals(item.get("state"))).count() >= 20)
                throw new ObjectiveException(409, "进行中的清单已达 20 份，请先归档其他清单");
            if ("DELETED".equals(dto.getState())) jdbc.update("UPDATE learning_training_plan SET state='DELETED',name='',snapshot_json='{}',revision=revision+1,updated_at=? WHERE uid=? AND id=?", new Timestamp(now()), uid, id);
            else jdbc.update("UPDATE learning_training_plan SET state=?,name=?,revision=revision+1,updated_at=? WHERE uid=? AND id=?", dto.getState(), newName == null ? row.get("name") : newName, new Timestamp(now()), uid, id);
            return null;
        });
        access.requireChat(uid);
        if ("DELETED".equals(dto.getState())) { Map<String,Object> result = new LinkedHashMap<>(); result.put("id", id); result.put("state", "DELETED"); return result; }
        return detail(id);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> views(String uid, List<Map<String,Object>> rows) {
        if (rows.isEmpty()) return Collections.emptyList();
        List<Map<String,Object>> snapshots = rows.stream().map(this::snapshot).collect(Collectors.toList());
        Set<String> ids = new LinkedHashSet<>(); for (Map<String,Object> saved : snapshots) for (Map<String,Object> item : (List<Map<String,Object>>) saved.get("items")) ids.add((String) item.get("id"));
        Map<String,LearningCandidateStore.Candidate> visible = candidates.find(uid, ids);
        Set<String> tried = candidates.attempted(uid), solved = candidates.solved(uid), keys = new HashSet<>(tried); keys.addAll(solved); visible.values().forEach(candidate -> keys.add(candidate.key));
        Map<String,String> identities = candidates.identities(keys);
        Set<String> triedIds = tried.stream().map(identities::get).filter(Objects::nonNull).collect(Collectors.toSet()), solvedIds = solved.stream().map(identities::get).filter(Objects::nonNull).collect(Collectors.toSet());
        List<Map<String,Object>> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String,Object> row = rows.get(i), saved = snapshots.get(i), view = new LinkedHashMap<>();
            for (String field : Arrays.asList("id", "name", "state", "revision")) view.put(field, row.get(field));
            view.put("createdAt", ((java.util.Date) row.get("created_at")).getTime()); view.put("updatedAt", ((java.util.Date) row.get("updated_at")).getTime());
            view.put("filters", saved.get("filters")); view.put("savedBasis", saved.get("basis")); view.put("progressAsOf", now());
            List<Map<String,Object>> items = new ArrayList<>();
            for (Map<String,Object> original : (List<Map<String,Object>>) saved.get("items")) {
                String itemId = (String) original.get("id"); LearningCandidateStore.Candidate c = visible.get(itemId); Map<String,Object> item = new LinkedHashMap<>(); item.put("id", itemId); item.put("available", c != null);
                if (c == null) item.put("status", "UNAVAILABLE");
                else {
                    item.put("title", c.title); item.put("displayId", c.displayId); item.put("kind", c.kind); item.put("source", c.source); item.put("url", c.url); item.put("questionId", c.questionId); item.put("rating", c.rating);
                    item.put("savedReasons", original.get("reasons")); String identity = identities.get(c.key);
                    item.put("status", solvedIds.contains(identity) ? "SOLVED" : triedIds.contains(identity) ? "ATTEMPTED" : "NO_RECORD");
                }
                items.add(item);
            }
            view.put("items", items); view.put("solved", items.stream().filter(item -> "SOLVED".equals(item.get("status"))).count()); result.add(view);
        }
        return result;
    }
}
