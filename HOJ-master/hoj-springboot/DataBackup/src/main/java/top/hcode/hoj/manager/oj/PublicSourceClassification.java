package top.hcode.hoj.manager.oj;

import org.springframework.jdbc.core.JdbcTemplate;
import top.hcode.hoj.manager.oj.external.ProblemIdentity;
import top.hcode.hoj.manager.oj.external.ProblemIdentityResolver;
import java.util.*;

/** Current public classification metadata; donors never become a user's practice evidence. */
public final class PublicSourceClassification {
    private final JdbcTemplate jdbc;
    private final AlgorithmCatalog catalog;
    public PublicSourceClassification(JdbcTemplate jdbc, AlgorithmCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    /** Groups for the requested original keys only. Unknown or ambiguous relations stay separate. */
    public Map<String, Set<String>> groups(Collection<String> requested) {
        Set<String> targets = new HashSet<>();
        for (String key : requested)
            if (key.startsWith("atcoder:") || key.startsWith("nowcoder:")) targets.add(key);
        Map<String, Set<String>> donors = new HashMap<>(), result = new HashMap<>();
        if (targets.isEmpty()) return result;
        for (String prefix : Arrays.asList("atcoder:", "nowcoder:")) {
            if (targets.stream().noneMatch(key -> key.startsWith(prefix))) continue;
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT p.id,p.problem_id,t.name FROM problem p JOIN problem_tag pt ON pt.pid=p.id JOIN tag t ON t.id=pt.tid "
                            + "WHERE p.auth=1 AND p.is_remote=1 AND (p.is_group=0 OR p.is_group IS NULL) AND p.gid IS NULL AND t.gid IS NULL AND p.problem_id LIKE ?",
                    "atcoder:".equals(prefix) ? "AC-%" : "NC-%")) {
                AlgorithmCatalog.Topic topic = catalog.topicForTag((String) row.get("name"));
                if (topic == null) continue;
                String key = ProblemIdentity.local(((Number) row.get("id")).longValue(), (String) row.get("problem_id"), true);
                if (key.startsWith(prefix)) donors.computeIfAbsent(key, ignored -> new HashSet<>()).add(topic.getGroupCode());
            }
        }
        if (donors.isEmpty()) return result;
        Set<String> keys = new HashSet<>(donors.keySet()), folded = new HashSet<>();
        donors.keySet().forEach(key -> folded.add(key.toLowerCase(Locale.ROOT)));
        // Skip unrelated AT catalogue keys; NC contexts may share a confirmed native ID.
        for (String key : targets)
            if (key.startsWith("nowcoder:") || folded.contains(key.toLowerCase(Locale.ROOT))) keys.add(key);
        Map<String, String> identities = new ProblemIdentityResolver(jdbc).resolve(keys);
        Map<String, Set<String>> canonical = new HashMap<>();
        donors.forEach((key, value) -> canonical.computeIfAbsent(identities.get(key), ignored -> new HashSet<>()).addAll(value));
        for (String key : targets) {
            Set<String> known = canonical.get(identities.getOrDefault(key, key));
            if (known != null) result.put(key, new HashSet<>(known));
        }
        return result;
    }
}
