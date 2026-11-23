package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.ObjectiveException;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NowCoderPracticeClientTest {
    final ObjectMapper json = new ObjectMapper();
    NowCoderPracticeClient client;
    @BeforeEach void setup() {
        client = new NowCoderPracticeClient();
        ReflectionTestUtils.setField(client, "json", json);
    }
    ObjectNode record() {
        return json.createObjectNode().put("id", 101).put("paperId", 201).put("userId", 12345)
                .put("name", "专项练习-数组").put("time", 1000).put("score", 0.0).put("tagId", 1)
                .put("finish", true).put("participatedCnt", 1).put("intelligent", true)
                .put("rightRate", "40%").put("status", 2).put("interview", false);
    }
    ObjectNode response(ObjectNode... rows) {
        ObjectNode root = json.createObjectNode().put("success", true).put("code", 0);
        ObjectNode data = root.putObject("data").put("current", 1).put("size", 20).put("total", rows.length)
                .put("totalPage", rows.length == 0 ? 0 : 1);
        ArrayNode values = data.putArray("records"); for (ObjectNode row : rows) values.add(row);
        return root;
    }
    JsonNode parse(ObjectNode row, boolean finished) { return client.page(response(row).toString(), "12345", 1, finished).path("items").get(0); }
    String profile(String uid, boolean visible) {
        return "<script>window.__INITIAL_STATE__={\"path\":\"/users/" + uid + "/tests\",\"store\":{\"profile\":{\"isSelf\":false,\"profile\":{\"userId\":" + uid
                + ",\"privacySetting\":{\"doQuestion\":" + visible + "}}}}};</script>";
    }
    @Test void endpointsUseActualOfficialPagingAndSeparateFinishedProjection() {
        assertEquals("https://gw-c.nowcoder.com/api/sparta/user/question-training/test-papers/12345?pageNo=2&pageSize=20&onlyFinish=false", client.endpoint("ALL", "12345", 2).toString());
        assertTrue(client.endpoint("VERIFY_FINISHED", "12345", 2).toString().endsWith("pageNo=2&pageSize=20&onlyFinish=true"));
        assertTrue(client.endpoint("VERIFY_COUNT_FINISHED", "12345", 1).toString().endsWith("test-papers-count/12345?onlyFinish=true"));
        assertEquals("https://www.nowcoder.com/users/12345/tests", client.endpoint("PROFILE", "12345", 1).toString());
        for (String uid : Arrays.asList("0123", "123/../../", "https://evil.invalid", "123?x=1")) assertThrows(Exception.class, () -> client.endpoint("ALL", uid, 1));
        assertThrows(IllegalArgumentException.class, () -> client.endpoint("UNKNOWN", "12345", 1));
        assertThrows(IllegalArgumentException.class, () -> client.endpoint("ALL", "12345", 0));
    }
    @Test void publicProfileRequiresActualIdentityRouteAndExplicitVisibility() {
        assertTrue(client.profile(profile("12345", true), "12345").path("visible").asBoolean());
        assertTrue(client.profile(profile("12345", true).replace("/users/12345/tests", "\\u002Fusers\\u002F12345\\u002Ftests"), "12345").path("visible").asBoolean());
        assertFalse(client.profile(profile("12345", false), "12345").path("visible").asBoolean());
        assertThrows(ObjectiveException.class, () -> client.profile(profile("12346", true), "12345"));
        assertThrows(ObjectiveException.class, () -> client.profile(profile("12345", true).replace("doQuestion", "unknown"), "12345"));
        assertThrows(ObjectiveException.class, () -> client.profile(profile("12345", true).replace("isSelf\":false", "isSelf\":true"), "12345"));
        assertThrows(ObjectiveException.class, () -> client.profile(profile("12345", true) + profile("12345", true), "12345"));
        // Page scripts following the JSON assignment are ignored, never evaluated.
        assertTrue(client.profile(profile("12345", true).replace(";</script>", ";fetch('/private')</script>"), "12345").path("visible").asBoolean());
    }
    @Test void unfinishedZeroIsUnknownAndDoesNotBecomeAScoreOrCompletion() {
        ObjectNode source = record().put("status", 0).put("finish", false).put("rightRate", "0%"); source.putNull("time");
        JsonNode row = parse(source, false);
        assertFalse(row.path("completed").asBoolean()); assertTrue(row.path("accuracy").isNull()); assertTrue(row.path("score").isNull());
        assertThrows(ObjectiveException.class, () -> parse(source, true));
        assertEquals("0%", parse(record().put("rightRate", "0%"), true).path("accuracy").asText());
    }
    @Test void finishedProjectionCanReferenceAnOlderRecordForTheSamePaper() {
        ObjectNode current = record().put("id", 102).put("status", 0).put("finish", false); current.putNull("time");
        JsonNode all = parse(current, false), finished = parse(record(), true);
        assertEquals(all.path("paperId"), finished.path("paperId"));
        assertNotEquals(all.path("recordId"), finished.path("recordId"));
        assertFalse(all.path("completed").asBoolean()); assertTrue(finished.path("completed").asBoolean());
    }
    @Test void paperAndInterviewScoresStaySeparateFromSpecialAccuracy() {
        JsonNode paper = parse(record().put("intelligent", false).put("score", 88.5), true);
        assertEquals("PAPER", paper.path("kind").asText()); assertEquals(88.5, paper.path("score").asDouble()); assertTrue(paper.path("accuracy").isNull());
        JsonNode interview = parse(record().put("intelligent", false).put("interview", true).put("score", 99), true);
        assertEquals("INTERVIEW", interview.path("kind").asText()); assertTrue(interview.path("score").isNull());
        assertThrows(ObjectiveException.class, () -> parse(record().put("interview", true), true));
    }
    @Test void sourceRecordAndPaperIdsRetainPrecisionAndHaveDifferentMeanings() {
        JsonNode row = parse(record().put("id", 9007199254740993L).put("paperId", 9007199254740995L).put("participatedCnt", 3).put("firstQuestionId", 987), true);
        assertEquals("9007199254740993", row.path("recordId").asText()); assertTrue(row.path("recordId").isTextual());
        assertEquals("9007199254740995", row.path("paperId").asText());
        assertFalse(row.has("questionCount")); assertFalse(row.has("solved")); assertFalse(row.has("firstQuestionId"));
    }
    @Test void foreignIdentityUnknownStateAndFutureCompletionAreRejected() {
        assertThrows(ObjectiveException.class, () -> parse(record().put("userId", 12346), false));
        assertThrows(ObjectiveException.class, () -> parse(record().put("status", 3), false));
        assertThrows(ObjectiveException.class, () -> parse(record().put("time", System.currentTimeMillis() + 300000), false));
        assertThrows(ObjectiveException.class, () -> parse(record().put("finish", "true"), false));
    }
    @Test void incompleteWrongOrDuplicatePagesCannotBeTreatedAsACompleteScan() {
        ObjectNode shortPage = response(record()); ((ObjectNode) shortPage.path("data")).put("total", 21).put("totalPage", 2);
        assertThrows(ObjectiveException.class, () -> client.page(shortPage.toString(), "12345", 1, false));
        ObjectNode wrongPage = response(record()); ((ObjectNode) wrongPage.path("data")).put("current", 2);
        assertThrows(ObjectiveException.class, () -> client.page(wrongPage.toString(), "12345", 1, false));
        assertThrows(ObjectiveException.class, () -> client.page(response(record(), record().put("id", 102)).toString(), "12345", 1, false));
        assertThrows(ObjectiveException.class, () -> client.page(response(record(), record().put("paperId", 202)).toString(), "12345", 1, false));
        assertEquals(0, client.page(response().toString(), "12345", 1, false).path("items").size());
    }
    @Test void malformedAndTrailingJsonOrDuplicateKeysNeverProduceRecords() {
        String body = response(record()).toString();
        for (String malformed : Arrays.asList(body + "{}", body.replace("\"success\":true", "\"success\":true,\"success\":false"), "<html>login</html>"))
            assertThrows(ObjectiveException.class, () -> client.page(malformed, "12345", 1, false));
        String nested = "{}"; for (int n = 0; n < 33; n++) nested = "{\"x\":" + nested + "}";
        final String tooDeep = nested;
        assertThrows(ObjectiveException.class, () -> client.page(tooDeep, "12345", 1, false));
    }
    @Test void percentageMustBeSourcePercentageRatherThanAnInferredQuestionCount() {
        assertEquals("100.00%", parse(record().put("rightRate", "100.00%"), true).path("accuracy").asText());
        for (String rate : Arrays.asList("101%", "NaN%", "0.4", "-1%", "40% of 10", ""))
            assertThrows(ObjectiveException.class, () -> parse(record().put("rightRate", rate), true));
    }
    @Test void sourceCountsAreBoundedIntegersNotNumericStrings() {
        assertEquals(22, client.count("{\"success\":true,\"code\":0,\"data\":{\"result\":22}}", "12345", true).path("total").asInt());
        assertThrows(ObjectiveException.class, () -> client.count("{\"success\":true,\"code\":0,\"data\":{\"result\":\"22\"}}", "12345", true));
    }
    @Test void archivedOfficialPublicPagesProveBothDifferentProjectionsWhenSupplied() throws Exception {
        String supplied = System.getenv("HOJ_NOWCODER_PRACTICE_SAMPLES");
        Assumptions.assumeTrue(supplied != null && !supplied.isEmpty(), "Explicit anonymous source archive required");
        Path root = Paths.get(supplied); Set<String> all = new HashSet<>(), finished = new HashSet<>();
        Map<String, JsonNode> current = new HashMap<>(), done = new HashMap<>();
        for (int page = 1; page <= 2; page++) {
            JsonNode a = client.page(new String(Files.readAllBytes(root.resolve("practice-4980952-p" + page + ".json")), StandardCharsets.UTF_8), "4980952", page, false);
            JsonNode f = client.page(new String(Files.readAllBytes(root.resolve("practice-4980952-finished-p" + page + ".json")), StandardCharsets.UTF_8), "4980952", page, true);
            assertEquals(38, a.path("total").asInt()); assertEquals(22, f.path("total").asInt());
            for (JsonNode row : a.path("items")) { assertTrue(all.add(row.path("recordId").asText())); current.put(row.path("paperId").asText(), row); }
            for (JsonNode row : f.path("items")) { assertTrue(finished.add(row.path("recordId").asText())); done.put(row.path("paperId").asText(), row); }
        }
        assertEquals(38, all.size()); assertEquals(22, finished.size());
        assertFalse(current.get("62119359").path("completed").asBoolean());
        assertTrue(done.get("62119359").path("completed").asBoolean());
        assertNotEquals(current.get("62119359").path("recordId"), done.get("62119359").path("recordId"));
        assertTrue(client.profile(new String(Files.readAllBytes(root.resolve("practice-list.html")), StandardCharsets.UTF_8), "4980952").path("visible").asBoolean());
    }
}
