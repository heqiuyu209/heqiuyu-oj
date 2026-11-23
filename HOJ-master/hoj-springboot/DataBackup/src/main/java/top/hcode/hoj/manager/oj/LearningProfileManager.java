package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.pojo.dto.ObjectiveDTO;

import java.util.*;
import java.util.stream.Collectors;

/** Evidence for coaching, not an invented cross-platform rating or probability of mastery. */
@Component
public class LearningProfileManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AlgorithmCatalog catalog;
    @Autowired private ObjectiveManager objectives;
    @Autowired private ObjectMapper json;
    private static class Evidence {
        String key;
        boolean objective, solved;
        Integer rating;
        Set<String> groups = new HashSet<>();
    }
    private <T> T read(Object value, TypeReference<T> type) {
        try { return json.readValue((String) value, type); }
        catch (Exception e) { throw new IllegalStateException("学习记录格式无效", e); }
    }
    protected long now() { return System.currentTimeMillis(); }

    public List<Map<String, Object>> publicProgrammingEvidence(String uid) {
        return programmingEvidence(uid, false);
    }
    public List<Map<String, Object>> publicProgrammingDetails(String uid) {
        return programmingEvidence(uid, true);
    }
    private List<Map<String, Object>> programmingEvidence(String uid, boolean details) {
        String extra = details ? ",p.title,COUNT(*) AS submissions,MIN(j.submit_time) AS first_at,MAX(j.submit_time) AS last_at,MIN(CASE WHEN j.status=0 THEN j.submit_time ELSE NULL END) AS first_solved_at" : "";
        return jdbc.queryForList("SELECT p.id,p.problem_id,p.is_remote,p.rating,MAX(CASE WHEN j.status=0 THEN 1 ELSE 0 END) AS solved" + extra + " FROM judge j JOIN problem p ON p.id=j.pid JOIN user_info u ON u.uuid=j.uid WHERE j.uid=? AND j.paper_run_id IS NULL AND j.cid=0 AND j.gid IS NULL AND j.status IN (-3,-2,-1,0,1,2,3,8) AND p.auth=1 AND (p.is_group=0 OR p.is_group IS NULL) AND (p.author IS NULL OR p.author<>u.username) GROUP BY p.id,p.problem_id,p.is_remote,p.rating" + (details ? ",p.title" : ""), uid);
    }

    public Map<Long, Set<String>> publicProgrammingGroups(String uid) {
        Map<Long, Set<String>> groups = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT DISTINCT pt.pid,t.name FROM judge j JOIN problem_tag pt ON pt.pid=j.pid JOIN tag t ON t.id=pt.tid WHERE j.uid=? AND t.gid IS NULL", uid)) {
            AlgorithmCatalog.Topic topic = catalog.topicForTag((String) row.get("name"));
            if (topic != null) groups.computeIfAbsent(((Number) row.get("pid")).longValue(), key -> new HashSet<>()).add(topic.getGroupCode());
        }
        return groups;
    }

    public Map<String, Object> profile(String uid) {
        if (uid == null || uid.length() > 32 || jdbc.queryForObject("SELECT COUNT(*) FROM user_info WHERE uuid=? AND status=0", Integer.class, uid) != 1)
            throw new ObjectiveException(404, "用户不存在或不可访问");
        Map<Long, String> tags = new HashMap<>();
        for (Map<String, Object> tag : jdbc.queryForList("SELECT id,name FROM tag WHERE gid IS NULL")) tags.put(((Number) tag.get("id")).longValue(), (String) tag.get("name"));
        Map<Long, Set<String>> programGroups = publicProgrammingGroups(uid);
        List<Evidence> evidence = new ArrayList<>();
        for (Map<String, Object> row : publicProgrammingEvidence(uid)) {
            Evidence item = new Evidence(); long id = ((Number) row.get("id")).longValue();
            item.key = "PROGRAMMING:" + id; item.solved = ((Number) row.get("solved")).intValue() == 1;
            item.rating = row.get("rating") == null ? null : ((Number) row.get("rating")).intValue();
            item.groups = programGroups.getOrDefault(id, Collections.emptySet()); evidence.add(item);
        }
        Map<Long, Evidence> concepts = concepts(uid, tags);
        evidence.addAll(concepts.values());
        List<Map<String, Object>> axes = new ArrayList<>();
        for (Map.Entry<String, String> group : catalog.groups().entrySet()) {
            List<Evidence> items = evidence.stream().filter(item -> item.groups.contains(group.getKey())).collect(Collectors.toList());
            Map<String, Object> axis = summarize(items); axis.put("code", group.getKey()); axis.put("name", group.getValue()); axes.add(axis);
        }
        Map<String, Object> overall = summarize(evidence);
        overall.put("unmappedSolved", evidence.stream().filter(item -> item.solved && item.groups.isEmpty()).count());
        overall.put("unratedProgrammingSolved", evidence.stream().filter(item -> !item.objective && item.solved && item.rating == null).count());
        Map<String, Object> result = new LinkedHashMap<>(); result.put("uid", uid); result.put("version", "local-evidence-v1"); result.put("scope", "PUBLIC_STANDALONE_PRACTICE");
        result.put("assistanceTracked", false); result.put("crossPlatformDeduplicated", false);
        result.put("source", "HOJ"); result.put("asOf", now()); result.put("overall", overall); result.put("axes", axes); return result;
    }
    public Map<Long, Boolean> publicObjectiveEvidence(String uid) {
        Map<Long, Boolean> result = new LinkedHashMap<>();
        concepts(uid, Collections.emptyMap()).forEach((id, item) -> result.put(id, item.solved));
        return result;
    }
    private Map<Long, Evidence> concepts(String uid, Map<Long, String> tags) {
        Map<Long, Evidence> concepts = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT r.*,q.content_json AS current_question_json FROM objective_run r JOIN objective_question q ON q.id=r.question_id WHERE r.uid=? AND r.set_id IS NULL AND r.mode='PRACTICE' AND r.state='FINISHED' AND q.published=1 AND q.practice_enabled=1 AND q.created_by<>r.uid AND r.answers_json<>'{}'", uid)) {
            ObjectiveDTO.Question current = read(row.get("current_question_json"), new TypeReference<ObjectiveDTO.Question>() {});
            String policy = current.getAnswerPolicy() == null ? "AFTER_ANSWER" : current.getAnswerPolicy();
            // A side-channel profile must never reveal correctness while feedback is locked.
            if (!objectives.released(policy, current.getAnswerReleaseAt(), true, true, now())) continue;
            List<ObjectiveDTO.Question> snapshot = read(row.get("snapshot_json"), new TypeReference<List<ObjectiveDTO.Question>>() {});
            Map<String, List<String>> answers = read(row.get("answers_json"), new TypeReference<Map<String, List<String>>>() {});
            for (ObjectiveDTO.Question question : snapshot) {
                if (!Objects.equals(question.getId(), current.getId())) continue;
                List<String> selected = answers.get(question.getId().toString());
                if (selected == null || selected.isEmpty()) continue;
                Evidence item = concepts.computeIfAbsent(question.getId(), id -> { Evidence entry = new Evidence(); entry.key = "OBJECTIVE:" + id; entry.objective = true; return entry; });
                item.solved |= objectives.score(question, selected) == objectives.maxScore(question);
                if (!tags.isEmpty() && current.getTagIds() != null) for (Long tagId : current.getTagIds()) {
                    AlgorithmCatalog.Topic topic = catalog.topicForTag(tags.get(tagId)); if (topic != null) item.groups.add(topic.getGroupCode());
                }
            }
        }
        return concepts;
    }
    private Map<String, Object> summarize(List<Evidence> items) {
        List<Integer> ratings = items.stream().filter(item -> !item.objective && item.solved && item.rating != null).map(item -> item.rating).sorted().collect(Collectors.toList());
        long programs = items.stream().filter(item -> !item.objective).count(), solved = items.stream().filter(item -> !item.objective && item.solved).count();
        Map<String, Object> row = new LinkedHashMap<>(); row.put("attempted", programs); row.put("solved", solved);
        row.put("objectiveAttempted", items.stream().filter(item -> item.objective).count()); row.put("objectiveSolved", items.stream().filter(item -> item.objective && item.solved).count());
        row.put("ratedSolved", ratings.size()); row.put("representativeRating", ratings.isEmpty() ? null : ratings.get((int) Math.ceil(ratings.size() * 0.75) - 1));
        row.put("confidence", ratings.size() < 3 ? "INSUFFICIENT" : ratings.size() < 10 ? "LIMITED" : "SUPPORTED"); return row;
    }
}
