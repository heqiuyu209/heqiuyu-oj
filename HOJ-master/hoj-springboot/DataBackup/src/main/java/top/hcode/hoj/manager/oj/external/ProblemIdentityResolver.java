package top.hcode.hoj.manager.oj.external;

import org.springframework.jdbc.core.JdbcTemplate;
import top.hcode.hoj.common.exception.ObjectiveException;
import java.util.*;

/** Statistics aliases only: submission and statement contexts retain their original keys. */
public final class ProblemIdentityResolver {
    private final JdbcTemplate jdbc;
    public ProblemIdentityResolver(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, String> resolve(Collection<String> keys) {
        Map<String, String> imports = importedLocals(keys);
        Map<String, String> result = new HashMap<>();
        Set<String> atcoder = new HashSet<>(), contests = new HashSet<>();
        for (String key : keys) {
            result.put(key, imports.getOrDefault(key, key));
            if (key.startsWith("atcoder:task:")) atcoder.add(key.toLowerCase(Locale.ROOT));
            if (key.matches("nowcoder:contest:[1-9][0-9]{0,11}:[A-Z][A-Z0-9]{0,15}")) contests.add(key);
        }
        Map<String, List<String>> canonical = new HashMap<>();
        for (Map<String, Object> row : rows("external_atcoder_problem", "LOWER(problem_key)", atcoder)) {
            String key = (String) row.get("problem_key");
            canonical.computeIfAbsent(key.toLowerCase(Locale.ROOT), unused -> new ArrayList<>()).add(key);
        }
        for (String key : keys) {
            List<String> matches = canonical.get(key.toLowerCase(Locale.ROOT));
            if (matches != null && matches.size() == 1) result.put(key, matches.get(0));
        }
        for (Map<String, Object> row : rows("remote_nowcoder_problem", "problem_key", contests)) {
            // Both IDs came from the fixed official manifest and the matching statement.
            if (ExternalAccountManager.flag(row.get("available")) && row.get("question_id") instanceof String
                    && ((String) row.get("question_id")).matches("[1-9][0-9]{0,15}")
                    && row.get("native_problem_id") instanceof Number
                    && ((Number) row.get("native_problem_id")).longValue() > 0) {
                result.put((String) row.get("problem_key"), "nowcoder:acm:" + row.get("native_problem_id"));
            }
        }
        return result;
    }

    private Map<String, String> importedLocals(Collection<String> keys) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String key : keys) {
            if (!key.matches("hoj:[1-9][0-9]{0,18}")) continue;
            try { ids.add(Long.parseLong(key.substring(4))); }
            catch (NumberFormatException ignored) { /* An invalid local ID cannot establish an alias. */ }
        }
        Map<Long, List<Map<String, Object>>> mappings = new HashMap<>();
        List<Long> selected = new ArrayList<>(ids);
        for (int offset = 0; offset < selected.size(); offset += 400) {
            List<Long> batch = selected.subList(offset, Math.min(selected.size(), offset + 400));
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT p.id,p.problem_id,r.problem_key FROM problem p JOIN remote_problem_import r ON r.problem_id=p.id "
                            + "WHERE p.is_remote=1 AND p.id IN (" + String.join(",", Collections.nCopies(batch.size(), "?")) + ")",
                    batch.toArray())) {
                long id = ((Number) row.get("id")).longValue();
                mappings.computeIfAbsent(id, unused -> new ArrayList<>()).add(row);
            }
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<Long, List<Map<String, Object>>> entry : mappings.entrySet()) {
            // Concatenating contest/index loses their boundary for numeric task
            // indices. Only a single publication mapping can recover it; an
            // unsuccessful later refresh does not invalidate that identity.
            if (entry.getValue().size() != 1) continue;
            Map<String, Object> row = entry.getValue().get(0);
            String key = (String) row.get("problem_key");
            try { CodeforcesStatementClient.sourceUrl(key); }
            catch (ObjectiveException invalid) { continue; }
            String[] parts = key.split(":");
            String suffix = parts[2] + parts[3];
            if (Objects.equals(row.get("problem_id"), "CF-" + suffix)
                    || Objects.equals(row.get("problem_id"), "GYM-" + suffix))
                result.put("hoj:" + entry.getKey(), key);
        }
        return result;
    }

    public List<Map<String, Object>> catalogue(String table, Collection<String> keys) {
        if (!Arrays.asList("remote_catalog_problem", "external_atcoder_problem", "remote_nowcoder_problem").contains(table))
            throw new IllegalArgumentException("Unknown source catalogue");
        return rows(table, "problem_key", keys);
    }

    private List<Map<String, Object>> rows(String table, String column, Collection<String> keys) {
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(keys));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int offset = 0; offset < unique.size(); offset += 400) {
            List<String> batch = unique.subList(offset, Math.min(unique.size(), offset + 400));
            rows.addAll(jdbc.queryForList("SELECT * FROM " + table + " WHERE " + column + " IN ("
                    + String.join(",", Collections.nCopies(batch.size(), "?")) + ")", batch.toArray()));
        }
        return rows;
    }
}
