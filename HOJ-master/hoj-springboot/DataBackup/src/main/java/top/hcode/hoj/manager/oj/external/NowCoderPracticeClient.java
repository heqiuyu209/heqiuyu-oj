package top.hcode.hoj.manager.oj.external;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.hcode.hoj.common.exception.ObjectiveException;
import top.hcode.hoj.remote.NowCoderPage;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;

/** Public paper projections only. A paper record is neither an attempt ledger nor a solved question. */
@Component
public class NowCoderPracticeClient {
    static final int PAGE_SIZE = 20, MAX_PAGES = 5000, MAX_TOTAL = PAGE_SIZE * MAX_PAGES;
    @Autowired private NowCoderDataClient transport;
    @Autowired private ObjectMapper json;

    protected long now() { return System.currentTimeMillis(); }
    private ObjectiveException invalid() {
        return new ObjectiveException(502, "牛客专项与试卷记录暂不可用或格式变化，保留上次完整记录");
    }
    protected URI endpoint(String stage, String uid, int page) {
        NowCoderDataClient.handle(uid);
        if (page < 1 || page > MAX_PAGES) throw new IllegalArgumentException("Invalid practice page");
        if (Arrays.asList("PROFILE", "VERIFY_PROFILE").contains(stage))
            return URI.create("https://www.nowcoder.com/users/" + uid + "/tests");
        boolean finished = stage.endsWith("FINISHED");
        String base = "https://gw-c.nowcoder.com/api/sparta/user/question-training/";
        if (Arrays.asList("COUNT_ALL", "COUNT_FINISHED", "VERIFY_COUNT_ALL", "VERIFY_COUNT_FINISHED").contains(stage))
            return URI.create(base + "test-papers-count/" + uid + "?onlyFinish=" + finished);
        if (Arrays.asList("ALL", "FINISHED", "VERIFY_ALL", "VERIFY_FINISHED").contains(stage))
            return URI.create(base + "test-papers/" + uid + "?pageNo=" + page + "&pageSize=20&onlyFinish=" + finished);
        throw new IllegalArgumentException("Invalid practice stage");
    }
    public JsonNode fetch(Map<String, Object> job) {
        String stage = (String) job.get("stage"), uid = (String) job.get("handle");
        int page = ((Number) job.get("next_page")).intValue();
        String body = transport.publicText(endpoint(stage, uid, page));
        if (stage.endsWith("PROFILE")) return profile(body, uid);
        if (stage.contains("COUNT")) return count(body, uid, stage.endsWith("FINISHED"));
        return page(body, uid, page, stage.endsWith("FINISHED"));
    }
    /** Bounded JSON literals only; never evaluate scripts from source pages. */
    private JsonNode read(String body) {
        try {
            if (body == null || body.length() > 2 * 1024 * 1024) throw invalid();
            try (JsonParser parser = json.getFactory().createParser(body)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                int depth = 0; JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
                        if (++depth > 32) throw invalid();
                    } else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) depth--;
                }
            }
            try (JsonParser parser = json.getFactory().createParser(body)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode result = json.readTree(parser);
                if (result == null || !result.isObject() || parser.nextToken() != null) throw invalid();
                return result;
            }
        } catch (ObjectiveException error) { throw error; }
        catch (Exception error) { throw invalid(); }
    }
    private long integer(JsonNode value, long min, long max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.asLong() < min || value.asLong() > max) throw invalid();
        return value.asLong();
    }
    private boolean bool(JsonNode value) {
        if (value == null || !value.isBoolean()) throw invalid();
        return value.asBoolean();
    }
    private JsonNode data(String body) {
        JsonNode root = read(body);
        if (!bool(root.get("success")) || integer(root.get("code"), 0, 0) != 0 || !root.path("data").isObject()) throw invalid();
        return root.path("data");
    }
    JsonNode profile(String body, String uid) {
        NowCoderDataClient.handle(uid);
        try {
            if (body == null || body.length() > 2 * 1024 * 1024) throw invalid();
            Map<String, String> fields = NowCoderPage.fields(Jsoup.parse(body), "__INITIAL_STATE__");
            // Official SSR encodes '/' as '\\u002F'; compare the decoded JSON string.
            JsonNode path = read("{\"path\":" + fields.get("path") + "}").path("path");
            if (!path.isTextual() || !Objects.equals("/users/" + uid + "/tests", path.asText())) throw invalid();
            JsonNode owner = read(fields.get("store")).path("profile");
            if (bool(owner.get("isSelf"))) throw invalid();
            JsonNode profile = owner.path("profile");
            if (!uid.equals(Long.toString(integer(profile.get("userId"), 1, 999999999999L)))) throw invalid();
            return json.createObjectNode().put("uid", uid).put("visible", bool(profile.path("privacySetting").get("doQuestion")));
        } catch (ObjectiveException error) { throw error; }
        catch (Exception error) { throw invalid(); }
    }
    JsonNode count(String body, String uid, boolean finished) {
        NowCoderDataClient.handle(uid);
        return json.createObjectNode().put("uid", uid).put("finished", finished)
                .put("total", integer(data(body).get("result"), 0, MAX_TOTAL));
    }
    JsonNode page(String body, String uid, int expectedPage, boolean finished) {
        NowCoderDataClient.handle(uid);
        if (expectedPage < 1 || expectedPage > MAX_PAGES) throw new IllegalArgumentException("Invalid practice page");
        JsonNode data = data(body), rows = data.path("records");
        int total = (int) integer(data.get("total"), 0, MAX_TOTAL);
        int pages = (int) integer(data.get("totalPage"), 0, MAX_PAGES);
        if (integer(data.get("current"), 1, MAX_PAGES) != expectedPage
                || integer(data.get("size"), PAGE_SIZE, PAGE_SIZE) != PAGE_SIZE
                || pages != (total + PAGE_SIZE - 1) / PAGE_SIZE || expectedPage > Math.max(1, pages)
                || !rows.isArray() || rows.size() != Math.min(PAGE_SIZE, Math.max(0, total - (expectedPage - 1) * PAGE_SIZE)))
            throw invalid();
        ObjectNode result = json.createObjectNode().put("uid", uid).put("finished", finished)
                .put("page", expectedPage).put("pages", pages).put("total", total);
        ArrayNode items = result.putArray("items");
        Set<String> ids = new HashSet<>(), papers = new HashSet<>();
        for (JsonNode row : rows) {
            String id = Long.toString(integer(row.get("id"), 1, Long.MAX_VALUE));
            String paper = Long.toString(integer(row.get("paperId"), 1, Long.MAX_VALUE));
            if (!ids.add(id) || !papers.add(paper)
                    || !uid.equals(Long.toString(integer(row.get("userId"), 1, 999999999999L)))) throw invalid();
            JsonNode title = row.get("name");
            if (title == null || !title.isTextual() || title.asText().trim().isEmpty()
                    || title.asText().codePointCount(0, title.asText().length()) > 255 || title.asText().indexOf('\0') >= 0) throw invalid();
            int status = (int) integer(row.get("status"), 0, 2);
            boolean complete = status == 1 || status == 2;
            if (finished && !complete) throw invalid();
            boolean special = bool(row.get("intelligent")), interview = bool(row.get("interview"));
            if (special && interview) throw invalid();
            ObjectNode item = items.addObject().put("recordId", id).put("paperId", paper).put("title", title.asText())
                    .put("kind", special ? "SPECIAL" : interview ? "INTERVIEW" : "PAPER")
                    .put("completed", complete).put("status", status).put("sourceFinish", bool(row.get("finish")))
                    .put("tagId", integer(row.get("tagId"), 0, Integer.MAX_VALUE))
                    .put("participatedCount", integer(row.get("participatedCnt"), 0, Integer.MAX_VALUE));
            JsonNode time = row.get("time");
            if (time != null && !time.isNull()) item.put("completedAt", integer(time, 1, now() + 120000));
            else item.putNull("completedAt");
            item.putNull("accuracy"); item.putNull("score");
            if (complete && special) {
                JsonNode rate = row.get("rightRate");
                if (rate == null || !rate.isTextual() || !rate.asText().matches("(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,6})?%")) throw invalid();
                BigDecimal value = new BigDecimal(rate.asText().substring(0, rate.asText().length() - 1));
                if (value.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid();
                item.put("accuracy", rate.asText());
            } else if (complete && !interview) {
                JsonNode score = row.get("score");
                if (score == null || !score.isNumber() || score.decimalValue().signum() < 0
                        || score.decimalValue().compareTo(BigDecimal.valueOf(1000000)) > 0) throw invalid();
                item.put("score", score.decimalValue().stripTrailingZeros());
            }
        }
        return result;
    }
}
