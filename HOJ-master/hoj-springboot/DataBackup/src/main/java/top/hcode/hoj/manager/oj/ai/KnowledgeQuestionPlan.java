package top.hcode.hoj.manager.oj.ai;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact original subquestions from explicit list or sentence boundaries. */
final class KnowledgeQuestionPlan {
    static final String INSTRUCTION = "当下条数据包含questions时，按其中的id逐项作答。每个claims对象必须额外提供非空questionIds数组，只引用它实际回答的子问ID。"
            + "此时unansweredQuestions只填暂缺依据的子问ID，例如[\"Q2\"]，不要改写问题或添加新问题。"
            + "已答questionIds和未答ID必须合计覆盖questions中的全部ID，同一ID不能同时已答和未答；每个数组内不得重复ID。"
            + "例如{\"answerable\":true,\"claims\":[{\"content\":\"有依据的说明\",\"questionIds\":[\"Q1\"],\"evidence\":[{\"citationId\":\"K1\",\"quote\":\"连续原文\"}]}],\"unansweredQuestions\":[\"Q2\"]}。其余原文依据、数量和长度规则保持，服务端按原始问题恢复未答文字。";
    private static final Pattern MARKER = Pattern.compile("^([ \\t]{0,3})(?:([1-9][0-9]*)[.、．)）]|[（(]([1-9][0-9]*)[)）])(?=$|[^0-9])");
    private static final Pattern FENCE = Pattern.compile("^[ \\t]{0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern MARKDOWN_BLOCK = Pattern.compile("^(?: {4}|[ \t]*\t|[ \t]{0,3}(?:[>|]|[-*+]\\s))");
    private static final Pattern TERNARY_OPERAND = Pattern.compile("[ \t]*[A-Za-z_0-9]+[ \t]*:");
    private static final String OPEN_GROUPS = "(（[［【{｛";
    private static final String CLOSE_GROUPS = ")）]］】}｝";
    private static final String OPEN_QUOTES = "\"'“‘「『《";
    private static final String CLOSE_QUOTES = "\"'”’」』》";
    private final String original;
    private final Map<String, String> questions;

    private KnowledgeQuestionPlan(String original, Map<String, String> questions) {
        this.original = original;
        this.questions = Collections.unmodifiableMap(new LinkedHashMap<>(questions));
    }

    private static boolean space(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0xfeff;
    }

    private static boolean blank(String text) {
        return text.codePoints().allMatch(KnowledgeQuestionPlan::space);
    }

    static KnowledgeQuestionPlan from(String query) {
        KnowledgeQuestionPlan plain = new KnowledgeQuestionPlan(query, Collections.emptyMap());
        if (query == null || query.length() > 4000) return plain;
        KnowledgeQuestionPlan list = numbered(query, plain);
        return list.structured() ? list : punctuated(query, plain);
    }

    private static KnowledgeQuestionPlan numbered(String query, KnowledgeQuestionPlan plain) {
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

    private static KnowledgeQuestionPlan punctuated(String query, KnowledgeQuestionPlan plain) {
        if (query.indexOf('？') < 0 && query.indexOf('?') < 0) return plain;
        for (String line : query.split("\\R")) {
            // Preserve free-form handling for mixed lists and block-level Markdown.
            if (!blank(line) && (MARKER.matcher(line).find() || FENCE.matcher(line).matches()
                    || MARKDOWN_BLOCK.matcher(line).find())) return plain;
        }

        Map<String, String> questions = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        Deque<Character> groups = new ArrayDeque<>();
        int start = 0, wordStart = 0, codeTicks = 0;
        char quote = 0;
        for (int i = 0; i < query.length(); i++) {
            char symbol = query.charAt(i);
            if (space(symbol)) {
                wordStart = i + 1;
                continue;
            }
            if (symbol == '`' && quote == 0) {
                int end = i + 1;
                while (end < query.length() && query.charAt(end) == '`') end++;
                int length = end - i;
                if (codeTicks == 0) codeTicks = length;
                else if (codeTicks == length) codeTicks = 0;
                i = end - 1;
                continue;
            }
            if (codeTicks != 0) continue;
            if (symbol == '\\' && i + 1 < query.length()) {
                i++;
                continue;
            }
            if (quote != 0) {
                if (symbol == quote) quote = 0;
                continue;
            }
            int quoteIndex = OPEN_QUOTES.indexOf(symbol);
            if (quoteIndex >= 0 && (symbol != '\'' || i == 0 || !Character.isLetterOrDigit(query.charAt(i - 1)))) {
                quote = CLOSE_QUOTES.charAt(quoteIndex);
                continue;
            }
            int groupIndex = OPEN_GROUPS.indexOf(symbol);
            if (groupIndex >= 0) {
                groups.push(CLOSE_GROUPS.charAt(groupIndex));
                continue;
            }
            if (CLOSE_GROUPS.indexOf(symbol) >= 0) {
                if (groups.isEmpty() || groups.pop() != symbol) return plain;
                continue;
            }
            if (!groups.isEmpty() || (symbol != '？' && symbol != '?')) continue;

            int mark = i, end = i + 1;
            boolean fullwidth = symbol == '？';
            while (end < query.length() && "？?！!".indexOf(query.charAt(end)) >= 0) {
                fullwidth |= query.charAt(end) == '？';
                end++;
            }
            i = end - 1;
            if (!fullwidth && !asciiQuestionEnd(query, wordStart, mark, end)) continue;
            String text = query.substring(start, end).trim();
            if (questions.size() == 8 || text.length() > 2000
                    || text.codePoints().noneMatch(Character::isLetterOrDigit) || !seen.add(text)) return plain;
            questions.put("Q" + (questions.size() + 1), text);
            start = end;
            wordStart = end;
        }
        if (codeTicks != 0 || quote != 0 || !groups.isEmpty() || questions.size() < 2
                || !blank(query.substring(start))) return plain;
        return new KnowledgeQuestionPlan(query, questions);
    }

    private static boolean asciiQuestionEnd(String query, int wordStart, int mark, int end) {
        if (mark == wordStart || (end < query.length() && !space(query.charAt(end)))) return false;
        String prefix = query.substring(wordStart, mark);
        return !prefix.contains("://") && !prefix.startsWith("/") && !prefix.startsWith("www.")
                && !TERNARY_OPERAND.matcher(query).region(end, query.length()).lookingAt();
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
