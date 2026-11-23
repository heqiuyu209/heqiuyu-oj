package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.StatusFailException;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/** Versioned local vocabulary. Remote names are mapped only by explicit aliases, never fuzzy guesses. */
@Component
public class AlgorithmCatalog {
    @Data public static class Topic {
        private String code, name, groupCode, groupName, color;
        private int sortOrder;
        private List<String> aliases = new ArrayList<>();
    }
    @Autowired private JdbcTemplate jdbc;
    private final List<Topic> topics;
    private final Map<String, Topic> aliases = new HashMap<>();

    public AlgorithmCatalog() {
        try (InputStream input = new ClassPathResource("algorithm-topics.json").getInputStream()) {
            topics = Collections.unmodifiableList(new ObjectMapper().readValue(input, new TypeReference<List<Topic>>() {}));
        } catch (IOException e) { throw new IllegalStateException("无法加载算法知识点目录", e); }
        for (Topic topic : topics) {
            for (String alias : topic.getAliases()) addAlias(alias, topic);
            addAlias(topic.getName(), topic);
        }
    }
    private void addAlias(String alias, Topic topic) {
        String key = alias.trim().toLowerCase(Locale.ROOT);
        Topic previous = aliases.put(key, topic);
        if (previous != null && !previous.getCode().equals(topic.getCode())) throw new IllegalStateException("算法别名重复: " + alias);
    }
    public List<Topic> topics() { return topics; }
    public Topic topicForTag(String name) { return name == null ? null : aliases.get(name.trim().toLowerCase(Locale.ROOT)); }
    public Map<String, String> groups() {
        Map<String, String> result = new LinkedHashMap<>();
        topics.forEach(topic -> result.put(topic.getGroupCode(), topic.getGroupName())); return result;
    }
    public Map<String, Object> view() {
        Map<String, Map<String, Object>> links = jdbc.queryForList("SELECT * FROM algorithm_topic").stream()
                .collect(Collectors.toMap(row -> (String) row.get("code"), row -> row));
        List<Map<String, Object>> records = new ArrayList<>();
        for (Topic topic : topics) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", topic.getCode()); row.put("name", topic.getName()); row.put("groupCode", topic.getGroupCode()); row.put("groupName", topic.getGroupName());
            Map<String, Object> link = links.getOrDefault(topic.getCode(), Collections.emptyMap());
            row.put("tagId", link.get("problem_tag_id")); row.put("categoryId", link.get("training_category_id")); row.put("classificationId", link.get("tag_classification_id"));
            records.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("version", "xcpc-common-v1"); result.put("topics", records); result.put("groups", groups()); return result;
    }
    public void requireCustomTag(Long id) throws StatusFailException { requireCustom("problem_tag_id", id); }
    public void requireCustomCategory(Long id) throws StatusFailException { requireCustom("training_category_id", id); }
    public void requireCustomClassification(Long id) throws StatusFailException { requireCustom("tag_classification_id", id); }
    private void requireCustom(String column, Long id) throws StatusFailException {
        if (id != null && jdbc.queryForObject("SELECT COUNT(*) FROM algorithm_topic WHERE " + column + "=?", Integer.class, id) > 0)
            throw new StatusFailException("XCPC 预置知识点固定保留，请通过新增自定义标签扩展");
    }
}
