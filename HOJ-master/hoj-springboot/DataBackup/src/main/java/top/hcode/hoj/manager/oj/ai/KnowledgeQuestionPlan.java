package top.hcode.hoj.manager.oj.ai;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only explicit top-level numbered lists; never infer implicit subquestions. */
final class KnowledgeQuestionPlan {
    static final String INSTRUCTION = "当下条数据包含questions时，按其中的id逐项作答。每个claims对象必须额外提供非空questionIds数组，只引用它实际回答的子问ID。"
            + "此时unansweredQuestions只填暂缺依据的子问ID，例如[\"Q2\"]，不要改写问题或添加新问题。"
            + "已答questionIds和未答ID必须合计覆盖questions中的全部ID，同一ID不能同时已答和未答；每个数组内不得重复ID。"
            + "例如{\"answerable\":true,\"claims\":[{\"content\":\"有依据的说明\",\"questionIds\":[\"Q1\"],\"evidence\":[{\"citationId\":\"K1\",\"quote\":\"连续原文\"}]}],\"unansweredQuestions\":[\"Q2\"]}。其余原文依据、数量和长度规则保持，服务端按原始问题恢复未答文字。";
    private static final Pattern MARKER = Pattern.compile("^([ \\t]{0,3})(?:([1-9][0-9]*)[.、．)）]|[（(]([1-9][0-9]*)[)）])(?=$|[^0-9])");
    private static final Pattern FENCE = Pattern.compile("^[ \\t]{0,3}(`{3,}|~{3,})(.*)$");
    private final String original;
    private final Map<String, String> questions;

    private KnowledgeQuestionPlan(String original, Map<String, String> questions) {
        this.original = original;
        this.questions = Collections.unmodifiableMap(new LinkedHashMap<>(questions));
    }

    private static boolean blank(String text) {
        return text.codePoints().allMatch(point -> Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0xfeff);
    }

    static KnowledgeQuestionPlan from(String query) {
        KnowledgeQuestionPlan plain = new KnowledgeQuestionPlan(query, Collections.emptyMap());
        if (query == null || query.length() > 4000) return plain;
        List<int[]> parts = new ArrayList<>();
        char fence = 0;
        int fenceLength = 0, indentation = -1, offset = 0;
        while (offset < query.length()) {
            int next = query.indexOf('\n', offset);
            int end = next < 0 ? query.length() : next;
            String line = query.substring(offset, end);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            Matcher code = FENCE.matcher(line);
            if (fence != 0) {
                if (code.matches() && code.group(1).charAt(0) == fence
                        && code.group(1).length() >= fenceLength && code.group(2).trim().isEmpty()) fence = 0;
            } else if (code.matches()) {
                if (parts.isEmpty()) return plain;
                fence = code.group(1).charAt(0); fenceLength = code.group(1).length();
            } else {
                Matcher marker = MARKER.matcher(line);
                if (marker.find()) {
                    int indent = marker.group(1).length();
                    if (marker.group(1).indexOf('\t') >= 0) return plain;
                    if (indentation >= 0 && indent > indentation) {
                        // A nested ordered list belongs to the current question.
                    } else {
                        String number = marker.group(2) == null ? marker.group(3) : marker.group(2);
                        if (indentation >= 0 && indent != indentation || parts.size() == 8
                                || !number.equals(Integer.toString(parts.size() + 1))) return plain;
                        indentation = indent;
                        parts.add(new int[]{offset, offset + marker.end()});
                        Matcher inlineCode = FENCE.matcher(line.substring(marker.end()));
                        if (inlineCode.matches()) {
                            fence = inlineCode.group(1).charAt(0); fenceLength = inlineCode.group(1).length();
                        }
                    }
                } else if (parts.isEmpty() && !blank(line)) return plain;
            }
            if (next < 0) break;
            offset = next + 1;
        }
        if (fence != 0 || parts.size() < 2) return plain;
        Map<String, String> questions = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            int end = i + 1 < parts.size() ? parts.get(i + 1)[0] : query.length();
            if (blank(query.substring(parts.get(i)[1], end))) return plain;
            String text = query.substring(parts.get(i)[0], end).trim();
            if (text.length() > 2000) return plain;
            questions.put("Q" + (i + 1), text);
        }
        return new KnowledgeQuestionPlan(query, questions);
    }

    boolean structured() { return !questions.isEmpty(); }
    String text(String id) { return questions.get(id); }
    Set<String> ids() { return questions.keySet(); }
    List<String> unanswered() {
        return structured() ? new ArrayList<>(questions.values()) : Collections.singletonList(original);
    }
    List<Map<String, String>> material() {
        List<Map<String, String>> result = new ArrayList<>();
        questions.forEach((id, text) -> {
            Map<String, String> item = new LinkedHashMap<>(); item.put("id", id); item.put("text", text); result.add(item);
        });
        return result;
    }
}
