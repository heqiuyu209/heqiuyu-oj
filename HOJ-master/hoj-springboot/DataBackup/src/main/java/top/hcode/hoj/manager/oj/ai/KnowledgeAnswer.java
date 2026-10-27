package top.hcode.hoj.manager.oj.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.hcode.hoj.common.exception.ObjectiveException;

import java.util.*;

/** Checks exact source provenance. Matching a quote is not semantic entailment. */
final class KnowledgeAnswer {
    static final String INSUFFICIENT = "当前知识库没有足够依据，无法给出有资料支持的回答。";
    private KnowledgeAnswer() { }
    private static boolean blank(String value) {
        return value.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0xfeff);
    }

    static Map<String, Object> parse(String reply, List<Map<String, Object>> citations, ObjectMapper json) {
        try {
            if (reply == null || reply.length() > 24000 || citations.size() > 10) throw new IllegalArgumentException();
            String text = reply.trim();
            if (text.startsWith("```json") && text.endsWith("```")) text = text.substring(7, text.length() - 3).trim();
            // The wire schema needs at most five levels; bound nesting before
            // constructing a tree, including for malformed provider replies.
            try (JsonParser parser = json.getFactory().createParser(text)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                int depth = 0; JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
                        if (++depth > 8) throw new IllegalArgumentException();
                    } else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) depth--;
                }
            }
            JsonNode root;
            try (JsonParser parser = json.getFactory().createParser(text)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                root = json.readTree(parser);
                if (parser.nextToken() != null) throw new IllegalArgumentException();
            }
            if (root == null || !root.isObject() || root.size() != 2 || !root.path("answerable").isBoolean()
                    || !root.path("claims").isArray() || root.path("claims").size() > 8) throw new IllegalArgumentException();
            boolean answerable = root.path("answerable").asBoolean();
            if (answerable == (root.path("claims").size() == 0)) throw new IllegalArgumentException();
            Map<String, Map<String, Object>> known = new LinkedHashMap<>();
            for (Map<String, Object> citation : citations) {
                Object id = citation.get("citationId"), excerpt = citation.get("excerpt");
                if (!(id instanceof String) || !((String) id).matches("K(?:[1-9]|10)") || !(excerpt instanceof String)
                        || known.put((String) id, citation) != null) throw new IllegalArgumentException();
            }
            List<Map<String, Object>> claims = new ArrayList<>();
            Map<String, Map<String, Object>> used = new LinkedHashMap<>();
            StringBuilder content = new StringBuilder();
            int quoted = 0;
            for (JsonNode claim : root.path("claims")) {
                if (!claim.isObject() || claim.size() != 2 || !claim.path("content").isTextual()
                        || blank(claim.path("content").asText()) || claim.path("content").asText().length() > 2000
                        || !claim.path("evidence").isArray() || claim.path("evidence").size() < 1 || claim.path("evidence").size() > 3)
                    throw new IllegalArgumentException();
                String paragraph = claim.path("content").asText().trim();
                if (content.length() != 0) content.append("\n\n");
                content.append(paragraph);
                if (content.length() > 8000) throw new IllegalArgumentException();
                List<Map<String, String>> evidence = new ArrayList<>();
                Set<String> pairs = new HashSet<>();
                for (JsonNode item : claim.path("evidence")) {
                    if (!item.isObject() || item.size() != 2 || !item.path("citationId").isTextual() || !item.path("quote").isTextual())
                        throw new IllegalArgumentException();
                    String id = item.path("citationId").asText(), quote = item.path("quote").asText();
                    if (!known.containsKey(id) || blank(quote) || quote.length() > 800
                            || !((String) known.get(id).get("excerpt")).contains(quote)
                            || !pairs.add(id + "\n" + quote)) throw new IllegalArgumentException();
                    quoted += quote.length();
                    if (quoted > 6000) throw new IllegalArgumentException();
                    Map<String, String> verified = new LinkedHashMap<>(); verified.put("citationId", id); verified.put("quote", quote);
                    evidence.add(verified); used.put(id, known.get(id));
                }
                Map<String, Object> verified = new LinkedHashMap<>(); verified.put("content", paragraph); verified.put("evidence", evidence); claims.add(verified);
            }
            Map<String, Object> result = new LinkedHashMap<>(); result.put("answerable", answerable);
            result.put("content", answerable ? content.toString() : INSUFFICIENT); result.put("claims", claims);
            result.put("citations", new ArrayList<>(used.values())); return result;
        } catch (Exception error) {
            throw new ObjectiveException(502, "模型返回的回答或原文依据无效，请重试或仅检索资料");
        }
    }
}
