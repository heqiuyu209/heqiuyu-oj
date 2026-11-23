package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.ai.AiAccessGuard;
import top.hcode.hoj.pojo.dto.AiDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Reproducible candidate selection. A model may explain this list, never create its task identities. */
@Component
public class LearningRecommendationManager {
    @Autowired private LearningCandidateStore candidates;
    @Autowired private LearningEvidenceManager evidence;
    @Autowired private AlgorithmCatalog catalog;
    @Autowired private AiAccessGuard access;
    @Autowired private ObjectMapper json;
    protected long now() { return System.currentTimeMillis(); }
    private long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private String hash(String text) {
        try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder h = new StringBuilder(); for (byte b : bytes) h.append(String.format("%02x", b & 255)); return h.toString(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static class Ranked {
        LearningCandidateStore.Candidate candidate;
        String group, tie;
        int priority;
        long solved, distance;
        List<String> reasons = new ArrayList<>();
    }
    private long attempts(Map<String, Object> axis, boolean concept) { return number(axis.get(concept ? "objectiveAttempted" : "attempted")); }
    private long solved(Map<String, Object> axis, boolean concept) { return number(axis.get(concept ? "objectiveSolved" : "solved")); }
    private int priority(Map<String, Object> axis, boolean concept) {
        return attempts(axis, concept) > solved(axis, concept) ? 0 : attempts(axis, concept) == 0 ? 1 : 2;
    }
    private Map<String, Object> filters(AiDTO.RecommendationFilter dto) {
        if (dto == null || !Arrays.asList("HOJ", "CODEFORCES", "ATCODER", "NOWCODER").contains(dto.getSource())
                || dto.getGroupCode() != null && !dto.getGroupCode().isEmpty() && !catalog.groups().containsKey(dto.getGroupCode())
                || dto.getMinRating() != null && (dto.getMinRating() < 0 || dto.getMinRating() > 1000000)
                || dto.getMaxRating() != null && (dto.getMaxRating() < 0 || dto.getMaxRating() > 1000000)
                || dto.getMinRating() != null && dto.getMaxRating() != null && dto.getMinRating() > dto.getMaxRating())
            throw new ObjectiveException(400, "推荐条件无效，请检查题源、知识方向和难度范围");
        Map<String, Object> result = new LinkedHashMap<>(); result.put("source", dto.getSource()); result.put("groupCode", dto.getGroupCode() == null ? "" : dto.getGroupCode());
        result.put("minRating", dto.getMinRating()); result.put("maxRating", dto.getMaxRating()); result.put("includeUnrated", dto.isIncludeUnrated()); return result;
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> plan(String uid, AiDTO.RecommendationFilter dto) {
        access.requireChat(uid); Map<String, Object> query = filters(dto), profile = evidence.profile(uid);
        Map<String, Object> source = ((List<Map<String, Object>>) profile.get("sources")).stream().filter(item -> dto.getSource().equals(item.get("source"))).findFirst().get();
        Map<String, Object> sourceOverall = (Map<String, Object>) source.get("overall");
        Map<String, Map<String, Object>> axes = new HashMap<>();
        for (Map<String, Object> axis : (List<Map<String, Object>>) ((Map<String, Object>) profile.get("combined")).get("axes")) axes.put((String) axis.get("code"), axis);
        List<LearningCandidateStore.Candidate> pool = candidates.pool(uid, dto.getSource());
        Set<String> attempted = candidates.attempted(uid), keys = new HashSet<>(attempted); pool.forEach(c -> keys.add(c.key));
        Map<String, String> identities = candidates.identities(keys); Set<String> tried = attempted.stream().map(identities::get).collect(Collectors.toSet());
        List<LearningCandidateStore.Candidate> unseen = pool.stream().filter(c -> !tried.contains(identities.get(c.key)))
                .filter(c -> dto.getGroupCode() == null || dto.getGroupCode().isEmpty() || c.groups.contains(dto.getGroupCode())).collect(Collectors.toList());
        Integer reference = (Integer) sourceOverall.get("representativeRating");
        Integer lowest = unseen.stream().filter(c -> c.rating != null).map(c -> c.rating).min(Integer::compare).orElse(null);
        boolean automatic = dto.getMinRating() == null && dto.getMaxRating() == null;
        Integer anchor = reference == null ? lowest : reference;
        Integer minimum = automatic ? anchor == null ? null : Math.max(0, anchor - (reference == null ? 0 : 200)) : dto.getMinRating();
        Integer maximum = automatic ? anchor == null ? null : (int) Math.min(1000000L, (long) anchor + (reference == null ? 200 : 400)) : dto.getMaxRating();
        long target = !automatic && minimum != null && maximum != null ? ((long) minimum + maximum) / 2 : !automatic && minimum != null ? minimum : !automatic && maximum != null ? maximum : anchor == null ? 0 : anchor;
        String day = Instant.ofEpochMilli(now()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
        List<Ranked> ranked = new ArrayList<>();
        for (LearningCandidateStore.Candidate c : unseen) {
            if (c.rating == null ? !dto.isIncludeUnrated() : minimum != null && c.rating < minimum || maximum != null && c.rating > maximum) continue;
            Ranked r = new Ranked(); r.candidate = c;
            final boolean concept = "OBJECTIVE".equals(c.kind);
            r.group = c.groups.stream().filter(axes::containsKey).min(Comparator.<String>comparingInt(code -> priority(axes.get(code), concept)).thenComparingLong(code -> solved(axes.get(code), concept)).thenComparing(code -> code)).orElse("");
            r.priority = r.group.isEmpty() ? 3 : priority(axes.get(r.group), concept); r.solved = r.group.isEmpty() ? 0 : solved(axes.get(r.group), concept);
            r.distance = c.rating == null ? Long.MAX_VALUE : Math.abs((long) c.rating - target); r.tie = hash(uid + "\n" + day + "\n" + c.id);
            if (!r.group.isEmpty()) {
                Map<String, Object> axis = axes.get(r.group); String name = catalog.groups().get(r.group);
                String type = concept ? "概念" : "编程";
                r.reasons.add(attempts(axis, concept) == 0 ? "补充“" + name + "”方向的" + type + "训练覆盖" : attempts(axis, concept) < 3 ? "“" + name + "”方向的" + type + "样本较少，先积累新题记录" : r.priority == 0 ? "“" + name + "”方向仍有未通过的" + type + "记录，可用新题补充练习" : "继续巩固“" + name + "”方向的" + type + "训练");
            } else r.reasons.add("暂缺可靠算法分类，按当前题源的难度选择");
            r.reasons.add(c.rating == null ? "题目未评级，先查看题面再判断是否合适" : !automatic ? "符合你设置的当前题源难度范围" : reference == null ? "尚无该题源的评级通过样本，先从现有较低难度开始" : "接近该题源已通过题目的 P75 难度");
            if ("OBJECTIVE".equals(c.kind)) r.reasons.add("补充概念训练，与编程实现成绩分别记录");
            ranked.add(r);
        }
        ranked.sort(Comparator.comparingInt((Ranked r) -> r.priority).thenComparingLong(r -> r.solved).thenComparingLong(r -> r.distance).thenComparing(r -> r.tie));
        List<Ranked> selected = new ArrayList<>(); Set<String> used = new HashSet<>(); Map<String, Integer> groupCounts = new HashMap<>();
        int concepts = 0, conceptLimit = ranked.stream().anyMatch(r -> !"OBJECTIVE".equals(r.candidate.kind)) ? 2 : 6;
        for (int pass = 0; pass < 2 && selected.size() < 6; pass++) for (Ranked r : ranked) {
            String identity = identities.get(r.candidate.key); boolean concept = "OBJECTIVE".equals(r.candidate.kind);
            if (selected.size() == 6) break;
            if (used.contains(identity) || concept && concepts >= conceptLimit || pass == 0 && !r.group.isEmpty() && groupCounts.getOrDefault(r.group, 0) >= 2) continue;
            selected.add(r); used.add(identity); groupCounts.put(r.group, groupCounts.getOrDefault(r.group, 0) + 1); if (concept) concepts++;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Ranked r : selected) {
            LearningCandidateStore.Candidate c = r.candidate; Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.id); item.put("kind", c.kind); item.put("source", c.source); item.put("displayId", c.displayId); item.put("title", c.title); item.put("url", c.url); item.put("questionId", c.questionId);
            item.put("rating", c.rating); item.put("groups", c.groups.stream().sorted().map(code -> { Map<String, String> g = new LinkedHashMap<>(); g.put("code", code); g.put("name", catalog.groups().get(code)); return g; }).collect(Collectors.toList()));
            item.put("reasons", r.reasons); items.add(item);
        }
        Map<String, Object> basis = new LinkedHashMap<>(); basis.put("difficultyMetric", source.get("difficultyMetric")); basis.put("representativeRating", reference); basis.put("ratedSolved", sourceOverall.get("ratedSolved"));
        basis.put("automaticRange", automatic); basis.put("minRating", minimum); basis.put("maxRating", maximum); basis.put("historyComplete", source.get("historyComplete")); basis.put("stale", source.get("stale"));
        basis.put("syncState", source.get("syncState"));
        Map<String, Object> coverage = (Map<String, Object>) profile.get("combined");
        basis.put("coverageSyncState", coverage.get("syncState")); basis.put("coverageStale", coverage.get("stale"));
        basis.put("classificationAvailable", pool.stream().anyMatch(c -> !c.groups.isEmpty())); basis.put("assistanceTracked", false); basis.put("selection", "UNATTEMPTED_PUBLIC_EVIDENCE");
        Map<String, Object> result = new LinkedHashMap<>(); result.put("filters", query); result.put("basis", basis); result.put("items", items); result.put("groups", catalog.groups()); result.put("version", "coverage-distance-v1");
        try { result.put("fingerprint", hash(json.writeValueAsString(Arrays.asList(query, basis, items)))); }
        catch (Exception e) { throw new ObjectiveException(503, "推荐数据暂不可用，请稍后重试"); }
        access.requireChat(uid); return result;
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> verify(String uid, AiDTO.RecommendationExplanation dto) {
        if (dto == null || dto.getFilters() == null || dto.getFingerprint() == null || !dto.getFingerprint().matches("[a-f0-9]{64}") || dto.getCandidateIds() == null || dto.getCandidateIds().isEmpty() || dto.getCandidateIds().size() > 6)
            throw new ObjectiveException(400, "请先获取候选题，再请求模型解释");
        Map<String, Object> current = plan(uid, dto.getFilters());
        List<String> ids = ((List<Map<String, Object>>) current.get("items")).stream().map(item -> (String) item.get("id")).collect(Collectors.toList());
        if (!dto.getFingerprint().equals(current.get("fingerprint")) || !dto.getCandidateIds().equals(ids)) throw new ObjectiveException(409, "题目或学习记录已变化，请刷新推荐后重试");
        return current;
    }
}
