package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.manager.oj.external.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public metadata only. No answer, explanation, code or credential is loaded into a candidate. */
@Component
public class LearningCandidateStore {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private AlgorithmCatalog catalog;
    @Autowired private LearningProfileManager profiles;
    public static class Candidate {
        public String id, key, title, displayId, kind, source, url;
        public Integer rating;
        public Long questionId;
        public Set<String> groups = new HashSet<>();
    }
    private long number(Object value) { return ((Number) value).longValue(); }
    private boolean flag(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number && number(value) != 0; }
    private Integer rating(Object value) { return value instanceof Number && number(value) >= 0 ? ((Number) value).intValue() : null; }
    private Integer objectiveRating(Object value) {
        if (value == null) return null;
        try {
            JsonNode stored = json.readTree(text(value));
            return stored.isIntegralNumber() && stored.canConvertToInt() && stored.intValue() >= 0 && stored.intValue() <= 5000 ? stored.intValue() : null;
        } catch (Exception e) { throw new IllegalStateException("客观题评级数据无效", e); }
    }
    private String text(Object value) { return value instanceof byte[] ? new String((byte[]) value, StandardCharsets.UTF_8) : value.toString(); }
    private Set<String> groups(Object value) {
        Set<String> groups = new HashSet<>();
        try {
            JsonNode tags = value == null ? json.createArrayNode() : json.readTree(value.toString());
            if (!tags.isArray()) throw new IllegalArgumentException("Invalid candidate tags");
            for (JsonNode tag : tags) { AlgorithmCatalog.Topic topic = catalog.topicForTag(tag.asText()); if (topic != null) groups.add(topic.getGroupCode()); }
        } catch (Exception e) { throw new IllegalStateException("候选题目分类数据无效", e); }
        return groups;
    }
    private String path(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }
    public List<Candidate> pool(String uid, String source) {
        return load(uid, source, null);
    }
    private String selection(String column, Collection<?> values, List<Object> args) {
        if (values == null) return "";
        if (values.isEmpty()) return " AND 1=0";
        args.addAll(values);
        return " AND " + column + " IN (" + String.join(",", Collections.nCopies(values.size(), "?")) + ")";
    }
    private List<Long> localIds(Collection<String> ids, String kind) {
        if (ids == null) return null;
        List<Long> result = new ArrayList<>();
        for (String id : ids) if (id.startsWith(kind + ":")) {
            try { long value = Long.parseLong(id.substring(kind.length() + 1)); if (value <= 0) throw new NumberFormatException(); result.add(value); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid saved problem identity"); }
        }
        return result;
    }
    /** Targeted current-public lookup; saved plans must not depend on catalogue pool limits. */
    public Map<String, Candidate> find(String uid, Collection<String> ids) {
        if (ids == null || ids.size() > 120) throw new IllegalArgumentException("Invalid saved problem set");
        Map<String, List<String>> bySource = new LinkedHashMap<>();
        for (String id : new LinkedHashSet<>(ids)) {
            if (id == null || id.length() > 160) throw new IllegalArgumentException("Invalid saved problem identity");
            String source = id.startsWith("PROGRAMMING:") || id.startsWith("OBJECTIVE:") ? "HOJ"
                    : id.startsWith("cf:") ? "CODEFORCES" : id.startsWith("atcoder:") ? "ATCODER"
                    : id.startsWith("nowcoder:") ? "NOWCODER" : null;
            if (source == null) throw new IllegalArgumentException("Invalid saved problem identity");
            bySource.computeIfAbsent(source, ignored -> new ArrayList<>()).add(id);
        }
        Map<String, Candidate> result = new LinkedHashMap<>();
        bySource.forEach((source, selected) -> load(uid, source, selected).forEach(candidate -> result.put(candidate.id, candidate)));
        return result;
    }
    private List<Candidate> load(String uid, String source, Collection<String> selected) {
        List<Candidate> result = new ArrayList<>();
        if ("HOJ".equals(source)) {
            Map<Long, Candidate> programs = new LinkedHashMap<>();
            List<Object> programArgs = new ArrayList<>(Collections.singletonList(uid));
            String programSelection = selection("p.id", localIds(selected, "PROGRAMMING"), programArgs);
            for (Map<String, Object> row : jdbc.queryForList("SELECT p.id,p.problem_id,p.title,p.rating,p.is_remote FROM problem p JOIN user_info u ON u.uuid=? WHERE p.auth=1 AND (p.is_group=0 OR p.is_group IS NULL) AND p.gid IS NULL AND (p.author IS NULL OR p.author<>u.username)" + programSelection + " ORDER BY p.id LIMIT 5000", programArgs.toArray())) {
                long id = number(row.get("id")); Candidate c = new Candidate(); c.id = "PROGRAMMING:" + id;
                c.key = ProblemIdentity.local(id, (String) row.get("problem_id"), flag(row.get("is_remote"))); c.kind = "PROGRAMMING";
                c.source = source; c.title = (String) row.get("title"); c.displayId = (String) row.get("problem_id"); c.rating = rating(row.get("rating")); c.url = "/problem/" + path(c.displayId); programs.put(id, c);
            }
            List<Long> ids = new ArrayList<>(programs.keySet());
            for (int start = 0; start < ids.size(); start += 400) {
                List<Long> batch = ids.subList(start, Math.min(ids.size(), start + 400));
                for (Map<String, Object> row : jdbc.queryForList("SELECT pt.pid,t.name FROM problem_tag pt JOIN tag t ON t.id=pt.tid WHERE t.gid IS NULL AND pt.pid IN (" + String.join(",", Collections.nCopies(batch.size(), "?")) + ")", batch.toArray())) {
                    AlgorithmCatalog.Topic topic = catalog.topicForTag((String) row.get("name")); if (topic != null) programs.get(number(row.get("pid"))).groups.add(topic.getGroupCode());
                }
            }
            result.addAll(programs.values());
            Map<Long, String> tags = new HashMap<>();
            for (Map<String, Object> row : jdbc.queryForList("SELECT id,name FROM tag WHERE gid IS NULL")) { AlgorithmCatalog.Topic topic = catalog.topicForTag((String) row.get("name")); if (topic != null) tags.put(number(row.get("id")), topic.getGroupCode()); }
            List<Object> objectiveArgs = new ArrayList<>(Collections.singletonList(uid));
            String objectiveSelection = selection("id", localIds(selected, "OBJECTIVE"), objectiveArgs);
            for (Map<String, Object> row : jdbc.queryForList("SELECT id,title,JSON_EXTRACT(content_json,'$.tagIds') AS tag_ids,JSON_EXTRACT(content_json,'$.rating') AS task_rating FROM objective_question WHERE published=1 AND practice_enabled=1 AND created_by<>?" + objectiveSelection + " ORDER BY id LIMIT 5000", objectiveArgs.toArray())) {
                Candidate c = new Candidate(); c.questionId = number(row.get("id")); c.id = "OBJECTIVE:" + c.questionId; c.key = "objective:" + c.questionId;
                c.kind = "OBJECTIVE"; c.source = source; c.title = (String) row.get("title"); c.displayId = "Q" + c.questionId; c.url = "/objective"; c.rating = objectiveRating(row.get("task_rating"));
                try { JsonNode values = row.get("tag_ids") == null ? json.createArrayNode() : json.readTree(text(row.get("tag_ids"))); if (values.isArray()) for (JsonNode id : values) if (tags.containsKey(id.asLong())) c.groups.add(tags.get(id.asLong())); }
                catch (Exception e) { throw new IllegalStateException("客观题分类数据无效", e); }
                result.add(c);
            }
            return result;
        }
        String query = "CODEFORCES".equals(source) ? "SELECT problem_key,contest_id,problem_index,title,rating,tags_json FROM remote_catalog_problem WHERE available=1 ORDER BY problem_key LIMIT 20000"
                : "ATCODER".equals(source) ? "SELECT problem_key,title,estimated_difficulty AS rating,contest_id,task_id FROM external_atcoder_problem WHERE available=1 ORDER BY problem_key LIMIT 20000"
                : "SELECT p.problem_key,p.title,COALESCE(p.source_rating,n.source_rating) AS rating FROM remote_nowcoder_problem p LEFT JOIN remote_nowcoder_problem n ON n.problem_key=CONCAT('nowcoder:acm:',p.native_problem_id) AND n.available=1 AND p.question_id REGEXP '^[1-9][0-9]{0,15}$' AND p.native_problem_id>0 WHERE p.available=1 ORDER BY p.problem_key LIMIT 20000";
        List<Object> sourceArgs = new ArrayList<>();
        query = query.replace(" ORDER BY", selection("NOWCODER".equals(source) ? "p.problem_key" : "problem_key", selected, sourceArgs) + " ORDER BY");
        for (Map<String, Object> row : jdbc.queryForList(query, sourceArgs.toArray())) {
            Candidate c = new Candidate(); c.id = (String) row.get("problem_key"); c.key = c.id; c.kind = "REMOTE"; c.source = source;
            c.title = (String) row.get("title"); c.rating = rating(row.get("rating"));
            if ("CODEFORCES".equals(source)) {
                long contest = number(row.get("contest_id")); String index = (String) row.get("problem_index");
                if (contest <= 0 || index == null || !index.matches("[A-Za-z0-9]{1,16}") || !c.key.equals("cf:contest:" + contest + ":" + index.toUpperCase(Locale.ROOT))) throw new IllegalStateException("CF 来源目录编号不一致");
                c.url = "https://codeforces.com/problemset/problem/" + contest + "/" + index; c.displayId = "CF-" + contest + index; c.groups.addAll(groups(row.get("tags_json")));
            }
            else if ("ATCODER".equals(source)) { c.url = AtCoderStatementClient.sourceUrl((String) row.get("contest_id"), (String) row.get("task_id")); c.displayId = "AC-" + row.get("task_id"); }
            else { c.url = NowCoderStatementClient.sourceUrl(c.key); c.displayId = NowCoderStatementClient.ref(c.key).problem; }
            result.add(c);
        }
        if ("ATCODER".equals(source) || "NOWCODER".equals(source)) {
            List<String> keys = new ArrayList<>(); result.forEach(candidate -> keys.add(candidate.key));
            Map<String, Set<String>> groups = new PublicSourceClassification(jdbc, catalog).groups(keys);
            result.forEach(candidate -> candidate.groups.addAll(groups.getOrDefault(candidate.key, Collections.emptySet())));
        }
        return result;
    }
    public Set<String> attempted(String uid) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : profiles.publicProgrammingEvidence(uid)) keys.add(ProblemIdentity.local(number(row.get("id")), (String) row.get("problem_id"), flag(row.get("is_remote"))));
        for (Map<String, Object> row : jdbc.queryForList("SELECT p.id,p.problem_id,p.is_remote FROM problem p JOIN user_info u ON u.username=p.author WHERE u.uuid=? AND p.auth=1 AND (p.is_group=0 OR p.is_group IS NULL) AND p.gid IS NULL", uid)) keys.add(ProblemIdentity.local(number(row.get("id")), (String) row.get("problem_id"), flag(row.get("is_remote"))));
        keys.addAll(jdbc.queryForList("SELECT DISTINCT s.problem_key FROM external_submission s JOIN external_account a ON a.id=s.account_id WHERE a.uid=? AND a.verified_identity IS NOT NULL", String.class, uid));
        for (Long id : jdbc.queryForList("SELECT DISTINCT question_id FROM objective_run WHERE uid=? AND question_id IS NOT NULL AND set_id IS NULL AND mode='PRACTICE'", Long.class, uid)) keys.add("objective:" + id);
        return keys;
    }
    public Map<String, String> identities(Collection<String> keys) { return new ProblemIdentityResolver(jdbc).resolve(keys); }
    public Set<String> solved(String uid) {
        Set<String> keys = new HashSet<>();
        for (Map<String,Object> row : profiles.publicProgrammingEvidence(uid)) if (flag(row.get("solved")))
            keys.add(ProblemIdentity.local(number(row.get("id")), (String) row.get("problem_id"), flag(row.get("is_remote"))));
        keys.addAll(jdbc.queryForList("SELECT DISTINCT s.problem_key FROM external_submission s JOIN external_account a ON a.id=s.account_id WHERE a.uid=? AND a.verified_identity IS NOT NULL AND s.verdict='OK' AND (a.platform<>'CODEFORCES' OR s.individual=1)", String.class, uid));
        profiles.publicObjectiveEvidence(uid).forEach((id, passed) -> { if (passed) keys.add("objective:" + id); });
        return keys;
    }
}
