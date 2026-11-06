package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NowCoderPracticeManagerTest {
    JdbcTemplate jdbc; NowCoderPracticeManager manager; ObjectMapper json = new ObjectMapper(); volatile long clock = 1800000000000L;
    @BeforeEach void setup() throws Exception {
        initialize(new DriverManagerDataSource("jdbc:h2:mem:nc_practice_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
    }
    void initialize(DriverManagerDataSource source) throws Exception {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE user_info(uuid VARCHAR(32) PRIMARY KEY,status INT)"); jdbc.update("INSERT INTO user_info VALUES('alice',0)");
        for (String name : Arrays.asList("external-learning.sql", "nowcoder-practice.sql")) {
            String ddl = StreamUtils.copyToString(new ClassPathResource(name).getInputStream(), StandardCharsets.UTF_8);
            if (source.getUrl().startsWith("jdbc:h2:")) ddl = ddl.replace(" COLLATE=utf8mb4_unicode_ci", "");
            try (Connection connection = source.getConnection()) { ScriptUtils.executeSqlScript(connection, new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))); }
        }
        Timestamp now = new Timestamp(clock);
        jdbc.update("INSERT INTO external_account(id,uid,platform,handle,handle_key,verified_identity,history_complete,last_success_at,last_requested_at,created_at) VALUES(1,'alice','NOWCODER','12345','12345','12345',1,?,?,?)", now, now, now);
        jdbc.update("INSERT INTO external_sync_job(account_id,stage,scan_token) VALUES(1,'DONE','coding-scan')");
        manager = new NowCoderPracticeManager() { @Override protected long now() { return clock; } };
        ReflectionTestUtils.setField(manager, "jdbc", jdbc);
        ReflectionTestUtils.setField(manager, "transactions", new DataSourceTransactionManager(source));
        ReflectionTestUtils.setField(manager, "json", json);
    }
    String stage() { return jdbc.queryForObject("SELECT stage FROM external_nowcoder_practice_job WHERE account_id=1", String.class); }
    int rows() { return jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_practice_record", Integer.class); }
    ObjectNode profile(boolean visible) { return json.createObjectNode().put("uid", "12345").put("visible", visible); }
    ObjectNode count(boolean finished, int total) { return json.createObjectNode().put("uid", "12345").put("finished", finished).put("total", total); }
    ObjectNode record(String id, String paper, boolean complete, String accuracy) {
        ObjectNode value = json.createObjectNode().put("recordId", id).put("paperId", paper).put("title", "Source paper")
                .put("kind", "SPECIAL").put("completed", complete).put("status", complete ? 2 : 0).put("sourceFinish", complete)
                .put("tagId", 1).put("participatedCount", complete ? 1 : 0);
        if (complete) value.put("completedAt", 1000); else value.putNull("completedAt");
        if (accuracy == null) value.putNull("accuracy"); else value.put("accuracy", accuracy);
        value.putNull("score"); return value;
    }
    ObjectNode page(boolean finished, ObjectNode... records) {
        ObjectNode value = json.createObjectNode().put("uid", "12345").put("finished", finished).put("page", 1)
                .put("pages", records.length == 0 ? 0 : 1).put("total", records.length);
        ArrayNode items = value.putArray("items"); for (ObjectNode row : records) items.add(row); return value;
    }
    Map<String, Object> step(JsonNode data) { Map<String, Object> job = manager.claim(); assertNotNull(job); manager.apply(job, data); return job; }
    void throughCounts(int all, int finished) { step(profile(true)); step(count(false, all)); step(count(true, finished)); }
    void scanPages(ObjectNode all, ObjectNode finished) { step(all); step(all); step(finished); step(finished); step(count(false, all.path("total").asInt())); step(count(true, finished.path("total").asInt())); }
    void complete() {
        throughCounts(1, 1); scanPages(page(false, record("102", "201", false, null)), page(true, record("101", "201", true, "40%"))); step(profile(true));
    }
    @SuppressWarnings("unchecked") Map<String, Object> first() { return ((List<Map<String, Object>>) manager.view(1, true).get("records")).get(0); }
    @Test void twoPaperProjectionsPublishTogetherWithoutAddingProgrammingEvidence() {
        throughCounts(1, 1); ObjectNode all = page(false, record("102", "201", false, null)), finished = page(true, record("101", "201", true, "40%"));
        step(all); assertEquals(false, manager.view(1, true).get("available")); step(all);
        step(finished); step(finished); assertEquals(false, manager.view(1, true).get("available"));
        step(count(false, 1)); step(count(true, 1)); assertEquals(false, manager.view(1, true).get("available")); step(profile(true));
        Map<String, Object> view = manager.view(1, true);
        assertEquals("DONE", stage()); assertEquals(true, view.get("paperListsComplete")); assertEquals(false, view.get("attemptHistoryComplete"));
        assertEquals(1, view.get("totalPapers")); assertEquals(1, view.get("completedPapers")); assertEquals(1, view.get("completedSpecialPapers"));
        assertEquals("102", first().get("recordId")); assertEquals(false, first().get("completed"));
        assertEquals("101", ((Map<?, ?>) first().get("completedRecord")).get("recordId"));
        assertNull(view.get("uniqueQuestionCount")); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM external_submission", Integer.class));
        assertFalse(manager.view(1, false).containsKey("lastError")); assertFalse(view.containsKey("scan_token"));
    }
    @Test void knownPrivateSourceRevokesCachedDataImmediatelyAndDoesNotFetchPapers() {
        complete(); manager.request(1); step(profile(false));
        assertEquals("DONE", stage()); assertEquals(0, rows());
        assertEquals(false, manager.view(1, true).get("available")); assertEquals(true, manager.view(1, true).get("restricted"));
        assertFalse(manager.view(1, true).containsKey("totalPapers")); assertNull(manager.claim());
    }
    @Test void privacyChangeAtTheFinalCheckCannotPublishNewData() {
        complete(); manager.request(1); throughCounts(1, 1);
        scanPages(page(false, record("104", "201", false, null)), page(true, record("103", "201", true, "60%")));
        step(profile(false)); assertEquals(0, rows()); assertEquals(false, manager.view(1, false).get("available"));
    }
    @Test void changedInteriorPageRestartsRatherThanPublishingAMixedScan() {
        complete(); manager.request(1); throughCounts(21, 0);
        ObjectNode firstPage = page(false); firstPage.put("pages", 2).put("total", 21);
        for (int n = 0; n < 20; n++) ((ArrayNode) firstPage.path("items")).add(record(Integer.toString(300 + n), Integer.toString(400 + n), false, null));
        ObjectNode last = page(false, record("320", "420", false, null)); last.put("page", 2).put("pages", 2).put("total", 21);
        step(firstPage); step(last); step(firstPage);
        ObjectNode changed = last.deepCopy(); ((ObjectNode) changed.path("items").get(0)).put("title", "Changed after scanning"); step(changed);
        assertEquals("PROFILE", stage()); assertEquals("102", first().get("recordId")); assertEquals(2, rows());
    }
    @Test void changedFinalCountOrMissingPaperMappingKeepsPreviousSnapshot() {
        complete(); manager.request(1); throughCounts(1, 1);
        scanPages(page(false, record("104", "201", false, null)), page(true, record("103", "999", true, "60%")));
        step(profile(true)); assertEquals("PROFILE", stage()); assertEquals("102", first().get("recordId"));
        clock += 30001; throughCounts(1, 1);
        step(page(false, record("104", "201", false, null))); step(page(false, record("104", "201", false, null)));
        step(page(true, record("103", "201", true, "60%"))); step(page(true, record("103", "201", true, "60%")));
        step(count(false, 2)); assertEquals("PROFILE", stage()); assertEquals("102", first().get("recordId"));
    }
    @Test void zeroPaperListsAreCompleteWithoutInventingQuestions() {
        throughCounts(0, 0); scanPages(page(false), page(true)); step(profile(true));
        assertEquals(true, manager.view(1, true).get("available")); assertEquals(0, manager.view(1, true).get("totalPapers"));
        assertEquals(0, manager.view(1, true).get("completedSpecialPapers")); assertNull(manager.view(1, true).get("uniqueQuestionCount"));
    }
    @Test void sameRecordMustMatchExactlyAcrossProjectionsIncludingTitleCase() {
        throughCounts(1, 1);
        scanPages(page(false, record("101", "201", true, "40%").put("title", "Array")),
                page(true, record("101", "201", true, "40%").put("title", "ARRAY")));
        step(profile(true));
        assertEquals("PROFILE", stage()); assertEquals(false, manager.view(1, true).get("available")); assertEquals(0, rows());
    }
    @Test void recordIdentityCannotReferToADifferentPaperInTheOtherProjection() {
        throughCounts(2, 1);
        scanPages(page(false, record("101", "201", false, null), record("102", "202", false, null)),
                page(true, record("102", "201", true, "40%")));
        step(profile(true)); assertEquals("PROFILE", stage()); assertEquals(0, rows());
        assertEquals(false, manager.view(1, true).get("available"));
    }
    @Test void expiredLeaseCannotApplyOrFailTheReplacementAndBusyDeferralIsNotAFailure() {
        Map<String, Object> old = manager.claim(); clock += 90001; Map<String, Object> current = manager.claim();
        assertNotEquals(old.get("lease_token"), current.get("lease_token")); manager.apply(old, profile(false)); manager.failed(old, "source secret");
        assertNull(jdbc.queryForObject("SELECT source_visible FROM external_nowcoder_practice_job", Boolean.class));
        manager.defer(current); assertEquals(0, jdbc.queryForObject("SELECT failures FROM external_nowcoder_practice_job", Integer.class));
        assertNull(manager.claim()); clock += 3001; assertNotNull(manager.claim());
    }
    @Test void sourceErrorsKeepPublishedSnapshotAndNeverPersistRemoteErrorText() {
        complete(); manager.request(1); Map<String, Object> job = manager.claim(); manager.failed(job, "private response including session token");
        assertEquals(true, manager.view(1, true).get("available")); assertEquals("BACKOFF", manager.view(1, true).get("syncState"));
        assertFalse(manager.view(1, true).get("lastError").toString().contains("token")); assertEquals("102", first().get("recordId"));
    }
    @Test void disabledOrUnlinkedAccountsCannotContinueOrExposeStagedRecords() {
        Map<String, Object> job = manager.claim(); jdbc.update("UPDATE user_info SET status=1"); manager.apply(job, profile(true));
        assertEquals("PROFILE", stage()); assertNull(manager.claim()); jdbc.update("DELETE FROM external_account WHERE id=1");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_practice_job", Integer.class)); assertEquals(0, rows());
        assertEquals(false, manager.view(1, true).get("available"));
    }
    @Test void publicPaperSyncDoesNotWaitForAnUnrelatedCodingHistoryScan() {
        jdbc.update("UPDATE external_account SET history_complete=0,last_success_at=NULL WHERE id=1");
        jdbc.update("DELETE FROM external_sync_job WHERE account_id=1");
        complete(); assertEquals(true, manager.view(1, true).get("paperListsComplete"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM external_sync_job", Integer.class));
    }
    @Test void actualClientNumericNodeWidthsSurviveDatabaseRoundTripDuringVerification() throws Exception {
        NowCoderPracticeClient source = new NowCoderPracticeClient(); ReflectionTestUtils.setField(source, "json", json);
        for (int variant = 0; variant < 3; variant++) {
            boolean special = variant == 0; double score = variant == 1 ? 0.0 : 88.5;
            if (jdbc.queryForObject("SELECT COUNT(*) FROM external_nowcoder_practice_job", Integer.class) > 0) manager.request(1);
            ObjectNode raw = json.createObjectNode().put("success", true).put("code", 0);
            ObjectNode data = raw.putObject("data").put("current", 1).put("size", 20).put("total", 1).put("totalPage", 1);
            data.putArray("records").addObject().put("id", 101).put("paperId", 201).put("userId", 12345)
                    .put("name", "Actual client projection").put("time", 1000).put("score", score).put("tagId", 1)
                    .put("finish", true).put("participatedCnt", 1).put("intelligent", special).put("rightRate", "40%")
                    .put("status", 2).put("interview", false);
            ObjectNode all = (ObjectNode) source.page(raw.toString(), "12345", 1, false);
            ObjectNode finished = (ObjectNode) source.page(raw.toString(), "12345", 1, true);
            JsonNode typed = all.path("items").get(0);
            assertTrue(typed.path("tagId").isLong());
            assertTrue(json.readTree(typed.toString()).path("tagId").isInt());
            if (!special) assertTrue(typed.path("score").isBigDecimal());
            throughCounts(1, 1); scanPages(all, finished); step(profile(true));
            assertEquals("DONE", stage()); assertEquals(true, manager.view(1, true).get("paperListsComplete"));
            if (!special) assertEquals(score, ((Number) first().get("score")).doubleValue());
        }
    }
    @Test void repeatedSourcePaperAcrossPagesDoesNotInflateCounts() {
        throughCounts(21, 0); ObjectNode firstPage = page(false); firstPage.put("pages", 2).put("total", 21);
        for (int n = 0; n < 20; n++) ((ArrayNode) firstPage.path("items")).add(record(Integer.toString(300 + n), Integer.toString(400 + n), false, null));
        step(firstPage); ObjectNode last = page(false, record("999", "400", false, null)); last.put("page", 2).put("pages", 2).put("total", 21); step(last);
        assertEquals("PROFILE", stage()); assertEquals(0, rows()); assertEquals(false, manager.view(1, true).get("available"));
    }
}
