package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.hcode.hoj.manager.oj.external.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

/** Counts may be combined; ratings from different platforms never share a percentile. */
@Component
public class LearningEvidenceManager {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AlgorithmCatalog catalog;
    @Autowired private LearningProfileManager profiles;
    @Autowired private ObjectMapper json;
    protected long now() { return System.currentTimeMillis(); }
    private static class Evidence {
        String key;
        boolean solved;
        Integer rating;
        Set<String> groups = new HashSet<>();
    }
    private long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private boolean flag(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number && number(value) != 0; }
    private String syncState(List<Map<String, Object>> accounts) {
        if (accounts.isEmpty()) return "UNLINKED";
        if (accounts.stream().anyMatch(row -> !"DONE".equals(row.get("stage")) && row.get("lease_until") != null && ((Timestamp) row.get("lease_until")).getTime() > now())) return "RUNNING";
        if (accounts.stream().anyMatch(row -> !"DONE".equals(row.get("stage")) && number(row.get("failures")) == 0)) return "QUEUED";
        if (accounts.stream().anyMatch(row -> number(row.get("failures")) > 0)) return "BACKOFF";
        return "IDLE";
    }
    private Integer rating(Object value) { return value instanceof Number && number(value) >= 0 ? ((Number) value).intValue() : null; }
    private String localKey(Map<String, Object> row) {
        return ProblemIdentity.local(number(row.get("id")), (String) row.get("problem_id"), flag(row.get("is_remote")));
    }
    private void merge(Map<String, Evidence> target, Evidence incoming) {
        Evidence item = target.computeIfAbsent(incoming.key, key -> { Evidence e = new Evidence(); e.key = key; return e; });
        item.solved |= incoming.solved;
        item.groups.addAll(incoming.groups);
        // Source metadata is the same for a canonical task. Combined evidence ignores rating.
        if (incoming.rating != null) item.rating = incoming.rating;
    }
    private Set<String> groups(Object tags) {
        Set<String> result = new HashSet<>();
        try {
            JsonNode values = json.readTree((String) tags);
            if (!values.isArray()) throw new IllegalArgumentException("Invalid source tags");
            for (JsonNode tag : values) {
                AlgorithmCatalog.Topic topic = catalog.topicForTag(tag.asText());
                if (topic != null) result.add(topic.getGroupCode());
            }
            return result;
        } catch (Exception e) { throw new IllegalStateException("来源算法分类数据无效", e); }
    }
    private Map<String, Object> summarize(Collection<Evidence> items, boolean difficulty) {
        List<Integer> ratings = items.stream().filter(e -> e.solved && e.rating != null).map(e -> e.rating).sorted().collect(Collectors.toList());
        long solved = items.stream().filter(e -> e.solved).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attempted", (long) items.size()); result.put("solved", solved);
        result.put("objectiveAttempted", 0L); result.put("objectiveSolved", 0L);
        result.put("ratedSolved", difficulty ? ratings.size() : null);
        result.put("representativeRating", !difficulty || ratings.isEmpty() ? null : ratings.get((int) Math.ceil(ratings.size() * .75) - 1));
        result.put("confidence", !difficulty ? "NOT_COMPARABLE" : ratings.size() < 3 ? "INSUFFICIENT" : ratings.size() < 10 ? "LIMITED" : "SUPPORTED");
        result.put("unratedProgrammingSolved", difficulty ? solved - ratings.size() : null);
        result.put("unmappedSolved", items.stream().filter(e -> e.solved && e.groups.isEmpty()).count());
        return result;
    }
    private Map<String, Object> view(String source, Collection<Evidence> items, boolean difficulty) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source); result.put("coverageOnly", !difficulty); result.put("overall", summarize(items, difficulty));
        List<Map<String, Object>> axes = new ArrayList<>();
        catalog.groups().forEach((code, name) -> {
            Map<String, Object> axis = summarize(items.stream().filter(e -> e.groups.contains(code)).collect(Collectors.toList()), difficulty);
            axis.put("code", code); axis.put("name", name); axes.add(axis);
        });
        result.put("axes", axes);
        result.put("coverageAvailable", items.stream().anyMatch(e -> !e.groups.isEmpty()));
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> profile(String uid) {
        // Reuses all local visibility and objective feedback checks; the local AI contract is unchanged.
        Map<String, Object> local = new LinkedHashMap<>(profiles.profile(uid));
        List<Map<String, Object>> localRows = profiles.publicProgrammingEvidence(uid);
        Map<Long, Set<String>> localGroups = profiles.publicProgrammingGroups(uid);
        List<Map<String, Object>> accounts = jdbc.queryForList("SELECT a.platform,a.history_complete,a.last_success_at,j.stage,j.lease_until,j.failures FROM external_account a JOIN external_sync_job j ON j.account_id=a.id WHERE a.uid=? AND a.verified_identity IS NOT NULL", uid);
        List<Map<String, Object>> external = jdbc.queryForList("SELECT a.platform,s.problem_key,p.rating,p.tags_json,MAX(CASE WHEN s.verdict='OK' THEN 1 ELSE 0 END) AS solved,COUNT(DISTINCT CASE WHEN s.verdict='OK' THEN a.id ELSE NULL END) AS solved_accounts FROM external_account a JOIN external_submission s ON s.account_id=a.id JOIN external_problem p ON p.problem_key=s.problem_key AND p.platform=a.platform WHERE a.uid=? AND a.verified_identity IS NOT NULL AND (a.platform<>'CODEFORCES' OR s.individual=1) GROUP BY a.platform,s.problem_key,p.rating,p.tags_json", uid);
        Set<String> keys = new HashSet<>(); localRows.forEach(row -> keys.add(localKey(row))); external.forEach(row -> keys.add((String) row.get("problem_key")));
        ProblemIdentityResolver resolver = new ProblemIdentityResolver(jdbc);
        Map<String, String> identities = resolver.resolve(keys);
        Set<String> sourceKeysWithEvidence = external.stream().map(row -> identities.get((String) row.get("problem_key"))).collect(Collectors.toSet());
        Map<String, Set<String>> publicGroups = new PublicSourceClassification(jdbc, catalog).groups(sourceKeysWithEvidence);
        Map<String, Evidence> combined = new LinkedHashMap<>();
        Map<String, Set<String>> knownGroups = new HashMap<>();
        long solvedRecords = 0;
        for (Map<String, Object> row : localRows) {
            Evidence e = new Evidence(); e.key = identities.get(localKey(row)); e.solved = number(row.get("solved")) == 1;
            e.groups.addAll(localGroups.getOrDefault(number(row.get("id")), Collections.emptySet()));
            knownGroups.computeIfAbsent(e.key, key -> new HashSet<>()).addAll(e.groups);
            if (e.solved) solvedRecords++;
            merge(combined, e);
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        local.put("source", "HOJ"); local.put("coverageOnly", false);
        local.put("coverageAvailable", ((List<Map<String, Object>>) local.get("axes")).stream().anyMatch(axis -> number(axis.get("attempted")) > 0 || number(axis.get("objectiveAttempted")) > 0));
        local.put("difficultyMetric", "HOJ_RATING"); local.put("participationScope", "PUBLIC_STANDALONE_PRACTICE"); sources.add(local);
        for (String source : Arrays.asList("CODEFORCES", "ATCODER", "NOWCODER")) {
            List<Map<String, Object>> records = external.stream().filter(row -> source.equals(row.get("platform"))).collect(Collectors.toList());
            Set<String> sourceKeys = records.stream().map(row -> identities.get((String) row.get("problem_key"))).collect(Collectors.toSet());
            String table = "CODEFORCES".equals(source) ? "remote_catalog_problem" : "ATCODER".equals(source) ? "external_atcoder_problem" : "remote_nowcoder_problem";
            Map<String, Map<String, Object>> metadata = new HashMap<>();
            for (Map<String, Object> row : resolver.catalogue(table, sourceKeys)) metadata.put((String) row.get("problem_key"), row);
            Map<String, Evidence> items = new LinkedHashMap<>();
            for (Map<String, Object> row : records) {
                Evidence e = new Evidence(); e.key = identities.get((String) row.get("problem_key")); e.solved = number(row.get("solved")) == 1;
                e.groups.addAll(knownGroups.getOrDefault(e.key, Collections.emptySet()));
                e.groups.addAll(publicGroups.getOrDefault(e.key, Collections.emptySet()));
                Map<String, Object> meta = metadata.get(e.key);
                boolean available = meta != null && flag(meta.get("available"));
                if ("CODEFORCES".equals(source)) {
                    e.rating = rating(available ? meta.get("rating") : row.get("rating"));
                    e.groups.addAll(groups(available ? meta.get("tags_json") : row.get("tags_json")));
                } else if ("ATCODER".equals(source)) {
                    e.rating = rating(meta == null ? row.get("rating") : available ? meta.get("estimated_difficulty") : null);
                } else e.rating = rating(available ? meta.get("source_rating") : null);
                merge(items, e); merge(combined, e);
                solvedRecords += number(row.get("solved_accounts"));
            }
            Map<String, Object> result = view(source, items.values(), true);
            List<Map<String, Object>> sourceAccounts = accounts.stream().filter(row -> source.equals(row.get("platform"))).collect(Collectors.toList());
            result.put("verifiedAccounts", sourceAccounts.size());
            result.put("historyComplete", !sourceAccounts.isEmpty() && sourceAccounts.stream().allMatch(row -> flag(row.get("history_complete")) && row.get("last_success_at") != null));
            result.put("syncState", syncState(sourceAccounts));
            result.put("stale", sourceAccounts.stream().anyMatch(row -> !"DONE".equals(row.get("stage")) || row.get("last_success_at") == null || now() - ((Timestamp) row.get("last_success_at")).getTime() > 3600000 || number(row.get("failures")) > 0));
            result.put("difficultyMetric", "CODEFORCES".equals(source) ? "CODEFORCES_PROBLEM_RATING" : "ATCODER".equals(source) ? "ATCODER_PROBLEMS_ESTIMATE" : "NOWCODER_SOURCE_RATING");
            result.put("participationScope", "CODEFORCES".equals(source) ? "INDIVIDUAL_SUBMISSIONS" : "ACCOUNT_ATTRIBUTED");
            if ("NOWCODER".equals(source)) result.put("statisticsScope", "PUBLIC_PROFILE_CODING");
            sources.add(result);
        }
        Map<String, Object> total = view("ALL", combined.values(), false);
        total.put("syncState", syncState(accounts));
        total.put("stale", sources.stream().anyMatch(source -> Boolean.TRUE.equals(source.get("stale"))));
        Map<String, Object> overall = (Map<String, Object>) total.get("overall"), localOverall = (Map<String, Object>) local.get("overall");
        overall.put("duplicatesRemoved", solvedRecords - number(overall.get("solved")));
        for (String field : Arrays.asList("objectiveAttempted", "objectiveSolved")) overall.put(field, localOverall.get(field));
        Map<String, Map<String, Object>> localAxes = new HashMap<>();
        for (Map<String, Object> axis : (List<Map<String, Object>>) local.get("axes")) localAxes.put((String) axis.get("code"), axis);
        for (Map<String, Object> axis : (List<Map<String, Object>>) total.get("axes"))
            for (String field : Arrays.asList("objectiveAttempted", "objectiveSolved")) axis.put(field, localAxes.get(axis.get("code")).get(field));
        total.put("coverageAvailable", ((List<Map<String, Object>>) total.get("axes")).stream().anyMatch(axis -> number(axis.get("attempted")) > 0 || number(axis.get("objectiveAttempted")) > 0));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uid", uid); result.put("version", "verified-sources-v1"); result.put("scope", "PUBLIC_LOCAL_PRACTICE_AND_VERIFIED_EXTERNAL");
        result.put("identityRule", "EXPLICIT_SOURCE_IDS_AND_CATALOGUE_LINKS"); result.put("assistanceTracked", false);
        result.put("crossPlatformDeduplicated", true);
        result.put("combined", total); result.put("sources", sources); result.put("asOf", now());
        return result;
    }
}
