package top.hcode.hoj.manager.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.manager.oj.ai.AiAccessGuard;
import top.hcode.hoj.pojo.dto.AiDTO;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LearningRecommendationManagerTest {
    LearningRecommendationManager manager;
    LearningCandidateStore store;
    LearningEvidenceManager evidence;
    AiAccessGuard guard;
    AlgorithmCatalog catalog = new AlgorithmCatalog();
    Map<String, Object> profile;
    List<LearningCandidateStore.Candidate> pool;
    Set<String> attempted;
    Map<String, String> aliases;
    long clock = 1800000000000L;
    @BeforeEach void setup() {
        manager = new LearningRecommendationManager() { @Override protected long now() { return clock; } };
        store = mock(LearningCandidateStore.class); evidence = mock(LearningEvidenceManager.class); guard = mock(AiAccessGuard.class);
        ReflectionTestUtils.setField(manager, "candidates", store); ReflectionTestUtils.setField(manager, "evidence", evidence);
        ReflectionTestUtils.setField(manager, "catalog", catalog); ReflectionTestUtils.setField(manager, "access", guard); ReflectionTestUtils.setField(manager, "json", new ObjectMapper());
        pool = new ArrayList<>(); attempted = new HashSet<>(); aliases = new HashMap<>(); profile = new LinkedHashMap<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (String code : Arrays.asList("HOJ", "CODEFORCES", "ATCODER", "NOWCODER")) {
            Map<String, Object> source = new LinkedHashMap<>(), overall = new LinkedHashMap<>(); overall.put("representativeRating", null); overall.put("ratedSolved", 0);
            source.put("source", code); source.put("overall", overall); source.put("difficultyMetric", code); sources.add(source);
        }
        profile.put("sources", sources); Map<String, Object> combined = new HashMap<>(); List<Map<String, Object>> axes = new ArrayList<>();
        catalog.groups().forEach((code, name) -> { Map<String, Object> axis = new HashMap<>(); axis.put("code", code); axis.put("attempted", 0L); axis.put("solved", 0L); axes.add(axis); });
        combined.put("axes", axes); profile.put("combined", combined);
        when(evidence.profile("alice")).thenReturn(profile); when(store.pool(eq("alice"), anyString())).thenAnswer(call -> pool); when(store.attempted("alice")).thenAnswer(call -> attempted);
        when(store.identities(anyCollection())).thenAnswer(call -> { Map<String, String> result = new HashMap<>(); for (String key : (Collection<String>) call.getArgument(0)) result.put(key, aliases.getOrDefault(key, key)); return result; });
    }
    LearningCandidateStore.Candidate candidate(String id, String key, Integer rating, String kind, String... groups) {
        LearningCandidateStore.Candidate c = new LearningCandidateStore.Candidate(); c.id = id; c.key = key; c.title = "Public " + id; c.source = "HOJ"; c.kind = kind; c.rating = rating; c.displayId = id; c.url = "/problem/" + id; c.groups.addAll(Arrays.asList(groups)); pool.add(c); return c;
    }
    AiDTO.RecommendationFilter filter() { return new AiDTO.RecommendationFilter(); }
    @SuppressWarnings("unchecked") Map<String, Object> basis(Map<String, Object> plan) { return (Map<String, Object>) plan.get("basis"); }
    @SuppressWarnings("unchecked") List<Map<String, Object>> items(Map<String, Object> plan) { return (List<Map<String, Object>>) plan.get("items"); }
    @SuppressWarnings("unchecked") void reference(String code, int rating) { List<Map<String, Object>> sources = (List<Map<String, Object>>) profile.get("sources"); ((Map<String, Object>) sources.stream().filter(s -> code.equals(s.get("source"))).findFirst().get().get("overall")).put("representativeRating", rating); }
    AiDTO.RecommendationExplanation request(Map<String, Object> plan, AiDTO.RecommendationFilter filter) { AiDTO.RecommendationExplanation r = new AiDTO.RecommendationExplanation(); r.setFilters(filter); r.setFingerprint((String) plan.get("fingerprint")); r.setCandidateIds(items(plan).stream().map(c -> (String) c.get("id")).collect(Collectors.toList())); return r; }
    void fail(Runnable call, int status) { assertEquals(status, assertThrows(ObjectiveException.class, call::run).getStatus()); }

    @Test @SuppressWarnings("unchecked") void sourceRefreshStateChangesRecommendationBasisAndInvalidatesOldExplanation() {
        Map<String, Object> cf = ((List<Map<String, Object>>) profile.get("sources")).stream().filter(source -> "CODEFORCES".equals(source.get("source"))).findFirst().get();
        cf.put("historyComplete", true); cf.put("stale", false); cf.put("syncState", "IDLE");
        AiDTO.RecommendationFilter filter = filter(); filter.setSource("CODEFORCES");
        Map<String, Object> previous = manager.plan("alice", filter);
        AiDTO.RecommendationExplanation explanation = request(previous, filter);
        cf.put("stale", true); cf.put("syncState", "RUNNING");
        Map<String, Object> current = manager.plan("alice", filter);
        assertEquals("RUNNING", basis(current).get("syncState"));
        assertEquals(true, basis(current).get("stale")); assertEquals(true, basis(current).get("historyComplete"));
        assertNotEquals(previous.get("fingerprint"), current.get("fingerprint"));
        assertThrows(ObjectiveException.class, () -> manager.verify("alice", explanation));
    }
    @Test @SuppressWarnings("unchecked") void crossPlatformCoverageRefreshInvalidatesLocalAndOtherSourceRecommendations() {
        Map<String, Object> coverage = (Map<String, Object>) profile.get("combined");
        for (String source : Arrays.asList("HOJ", "CODEFORCES")) {
            coverage.put("syncState", "IDLE"); coverage.put("stale", false);
            AiDTO.RecommendationFilter filter = filter(); filter.setSource(source);
            Map<String, Object> previous = manager.plan("alice", filter);
            AiDTO.RecommendationExplanation explanation = request(previous, filter);
            coverage.put("syncState", "RUNNING"); coverage.put("stale", true);
            Map<String, Object> current = manager.plan("alice", filter);
            assertEquals("RUNNING", basis(current).get("coverageSyncState"));
            assertEquals(true, basis(current).get("coverageStale"));
            assertNotEquals(previous.get("fingerprint"), current.get("fingerprint"));
            assertThrows(ObjectiveException.class, () -> manager.verify("alice", explanation));
        }
    }
    @Test void knownAliasesExcludeAttemptsAndPreventDuplicateCandidatesWithoutTitleMatching() {
        candidate("seen", "nowcoder:contest:1:A", 800, "PROGRAMMING", "math"); candidate("one", "nowcoder:acm:2", 800, "PROGRAMMING", "math");
        candidate("alias", "nowcoder:contest:3:A", 800, "PROGRAMMING", "math"); candidate("different", "hoj:4", 800, "PROGRAMMING", "math");
        attempted.add("nowcoder:acm:1"); aliases.put("nowcoder:contest:1:A", "nowcoder:acm:1"); aliases.put("nowcoder:contest:3:A", "nowcoder:acm:2");
        List<Map<String, Object>> records = items(manager.plan("alice", filter())); assertEquals(2, records.size()); assertFalse(records.stream().anyMatch(r -> "seen".equals(r.get("id"))));
        assertTrue(records.stream().anyMatch(r -> "different".equals(r.get("id"))));
    }
    @Test void unknownReferenceStartsAtObservedLowBandAndKeepsUnratedOnlyWhenSelected() {
        candidate("low", "hoj:1", 800, "PROGRAMMING", "math"); candidate("high", "hoj:2", 1800, "PROGRAMMING", "math"); candidate("concept", "objective:3", null, "OBJECTIVE", "math");
        Map<String, Object> plan = manager.plan("alice", filter()); assertEquals(800, basis(plan).get("minRating")); assertEquals(1000, basis(plan).get("maxRating")); assertEquals(2, items(plan).size());
        AiDTO.RecommendationFilter noUnknown = filter(); noUnknown.setIncludeUnrated(false); assertEquals(1, items(manager.plan("alice", noUnknown)).size());
        assertTrue(items(plan).stream().flatMap(r -> ((List<String>) r.get("reasons")).stream()).anyMatch(s -> s.contains("未评级")));
    }
    @Test void manualRangeUsesItsOwnCenterAndDoesNotClaimItIsNearTheHistoricalQuartile() {
        reference("HOJ", 800); candidate("low", "hoj:1", 3000, "PROGRAMMING", "dp"); candidate("center", "hoj:2", 3500, "PROGRAMMING", "dp");
        AiDTO.RecommendationFilter f = filter(); f.setMinRating(3000); f.setMaxRating(4000); Map<String, Object> plan = manager.plan("alice", f);
        assertEquals("center", items(plan).get(0).get("id")); assertEquals(false, basis(plan).get("automaticRange")); assertTrue(items(plan).get(0).get("reasons").toString().contains("你设置"));
        assertFalse(items(plan).get(0).get("reasons").toString().contains("接近"));
    }
    @Test void referenceAndDifficultyAreSelectedFromExactlyOneRequestedPlatform() {
        reference("HOJ", 2200); reference("CODEFORCES", 800); reference("ATCODER", 1350); reference("NOWCODER", 1200);
        candidate("old", "atcoder:task:a", 800, "REMOTE"); candidate("target", "atcoder:task:b", 1350, "REMOTE");
        AiDTO.RecommendationFilter f = filter(); f.setSource("ATCODER"); Map<String, Object> plan = manager.plan("alice", f);
        assertEquals(1350, basis(plan).get("representativeRating")); assertEquals(1, items(plan).size()); assertEquals("target", items(plan).get(0).get("id")); assertEquals(false, basis(plan).get("classificationAvailable"));
    }
    @Test void invalidConditionsAndBlockedUserFailBeforeReadingCandidateMetadata() {
        AiDTO.RecommendationFilter bad = filter(); bad.setGroupCode("invented"); fail(() -> manager.plan("alice", bad), 400); verifyNoInteractions(store);
        bad.setGroupCode(null); bad.setMinRating(1200); bad.setMaxRating(800); fail(() -> manager.plan("alice", bad), 400);
        doThrow(new ObjectiveException(403, "比赛中")).when(guard).requireChat("alice"); fail(() -> manager.plan("alice", filter()), 403); verifyNoInteractions(store);
    }
    @Test void atcoderSavedClassificationDrivesDirectionAndProfileSelectionAndInvalidatesOldExplanation() {
        classifiedRemoteSelection("ATCODER","atcoder:task:");
    }
    @Test void nowcoderSavedClassificationDrivesDirectionAndProfileSelectionAndInvalidatesOldExplanation() {
        classifiedRemoteSelection("NOWCODER","nowcoder:acm:");
    }
    @SuppressWarnings("unchecked") private void classifiedRemoteSelection(String source,String prefix) {
        Map<String,Object> graph=((List<Map<String,Object>>)((Map<String,Object>)profile.get("combined")).get("axes")).stream().filter(axis->"graph".equals(axis.get("code"))).findFirst().get();
        graph.put("attempted",3L);graph.put("solved",1L);
        LearningCandidateStore.Candidate dp=candidate(prefix+"1",prefix+"1",1200,"REMOTE","dp");dp.source=source;
        LearningCandidateStore.Candidate graphs=candidate(prefix+"2",prefix+"2",1200,"REMOTE","graph");graphs.source=source;
        LearningCandidateStore.Candidate unknown=candidate(prefix+"3",prefix+"3",1200,"REMOTE");unknown.source=source;
        AiDTO.RecommendationFilter f=filter();f.setSource(source);
        Map<String,Object> plan=manager.plan("alice",f);assertEquals(true,basis(plan).get("classificationAvailable"));assertEquals(graphs.id,items(plan).get(0).get("id"));
        f.setGroupCode("dp");plan=manager.plan("alice",f);assertEquals(Collections.singletonList(dp.id),items(plan).stream().map(item->item.get("id")).collect(Collectors.toList()));
        AiDTO.RecommendationExplanation dto=request(plan,f);assertEquals(plan.get("fingerprint"),manager.verify("alice",dto).get("fingerprint"));
        dp.groups.clear();fail(()->manager.verify("alice",dto),409);
    }
    @Test void currentListMustMatchBeforeAnExplanationAndAnyEligibilityChangeInvalidatesIt() {
        LearningCandidateStore.Candidate c = candidate("real", "hoj:1", 800, "PROGRAMMING", "math"); AiDTO.RecommendationFilter f = filter(); Map<String, Object> plan = manager.plan("alice", f);
        AiDTO.RecommendationExplanation dto = request(plan, f); assertEquals(plan.get("fingerprint"), manager.verify("alice", dto).get("fingerprint"));
        c.title = "Changed public title"; fail(() -> manager.verify("alice", dto), 409); c.title = "Public real"; attempted.add("hoj:1"); fail(() -> manager.verify("alice", dto), 409);
    }
    @Test void sameEvidenceIsReproducibleAndConceptOnlyTrainingCanFillTheList() {
        for (int i = 1; i <= 10; i++) candidate("Q" + i, "objective:" + i, null, "OBJECTIVE", "math");
        Map<String, Object> first = manager.plan("alice", filter()), next = manager.plan("alice", filter()); assertEquals(6, items(first).size()); assertEquals(first.get("fingerprint"), next.get("fingerprint"));
        candidate("program1", "hoj:1", null, "PROGRAMMING", "math"); candidate("program2", "hoj:2", null, "PROGRAMMING", "math");
        List<Map<String, Object>> mixed = items(manager.plan("alice", filter())); assertEquals(4, mixed.size()); assertEquals(2, mixed.stream().filter(r -> "OBJECTIVE".equals(r.get("kind"))).count());
    }
    @Test void participationStartingDuringReadIsRecheckedBeforePublishingRecommendations() {
        candidate("new", "hoj:1", 800, "PROGRAMMING", "math"); doNothing().doThrow(new ObjectiveException(403, "比赛开始")).when(guard).requireChat("alice");
        fail(() -> manager.plan("alice", filter()), 403); verify(store).pool("alice", "HOJ");
    }
    @Test @SuppressWarnings("unchecked") void conceptRecommendationsUseReleasedConceptEvidenceInsteadOfProgrammingPasses() {
        Map<String,Object> math=((List<Map<String,Object>>)((Map<String,Object>)profile.get("combined")).get("axes")).stream().filter(a->"math".equals(a.get("code"))).findFirst().get();
        math.put("attempted",10L);math.put("solved",10L);math.put("objectiveAttempted",4L);math.put("objectiveSolved",0L);
        candidate("concept-math","objective:1",null,"OBJECTIVE","math");candidate("concept-graph","objective:2",null,"OBJECTIVE","graph");candidate("program-math","hoj:3",800,"PROGRAMMING","math");
        List<Map<String,Object>> result=items(manager.plan("alice",filter()));assertEquals("concept-math",result.get(0).get("id"));assertTrue(result.get(0).get("reasons").toString().contains("未通过的概念记录"));
    }
}
